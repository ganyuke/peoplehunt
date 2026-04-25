package io.github.ganyuke.peoplehunt.game.compass;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.util.Text;
import java.util.Collection;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Creates, identifies, distributes, and periodically retargets the plugin's custom hunter compass.
 *
 * <p>The service does not decide <em>where</em> a compass should point; it delegates that to a
 * {@link CompassTargetProvider}. This keeps compass item management separate from movement/match
 * rules.
 */
public final class CompassService {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final NamespacedKey compassKey;
    private CompassTargetProvider targetProvider;
    private int taskId = -1;

    public CompassService(JavaPlugin plugin, PeopleHuntConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.compassKey = new NamespacedKey(plugin, "hunter_compass");
    }

    public void setTargetProvider(CompassTargetProvider targetProvider) {
        this.targetProvider = targetProvider;
    }

    public void start() {
        stop();
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::updateAllCompasses, 1L, Math.max(1L, config.compassUpdateIntervalTicks()));
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public ItemStack createCompass() {
        // The persistent-data marker lets the plugin distinguish its compasses from vanilla ones so
        // death-drop cleanup and retargeting remain precise.
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.displayName(Text.mm(config.compassName()));
        if (!config.compassLore().isEmpty()) {
            meta.lore(config.compassLore().stream().map(Text::mm).toList());
        }
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        meta.clearLodestone();
        compass.setItemMeta(meta);
        return compass;
    }

    public void giveCompass(Collection<Player> players) {
        // Hunters should have exactly one plugin compass. This method is safe to call on match
        // start, rejoin, or respawn because it skips players who already carry one.
        ItemStack template = createCompass();
        for (Player player : players) {
            if (player == null || hasCompass(player)) {
                continue;
            }
            player.getInventory().addItem(template.clone());
        }
    }

    public boolean hasCompass(Player player) {
        if (player == null) return false;
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getContents()) {
            if (isPluginCompass(item)) return true;
        }
        for (ItemStack item : inventory.getArmorContents()) {
            if (isPluginCompass(item)) return true;
        }
        return isPluginCompass(inventory.getItemInOffHand());
    }

    public boolean isPluginCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    public void updateAllCompasses() {
        if (targetProvider == null) {
            return;
        }
        // Every online player is scanned because plugin compasses can be in any inventory, not only
        // the inventories of players currently expected to be hunters.
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerCompasses(player);
        }
    }

    public void updatePlayerCompasses(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isPluginCompass(item)) {
                ItemStack updated = updateCompassItem(item, player);
                if (updated != item) {
                    inventory.setItem(slot, updated);
                }
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (isPluginCompass(offhand)) {
            ItemStack updated = updateCompassItem(offhand, player);
            if (updated != offhand) {
                inventory.setItemInOffHand(updated);
            }
        }
    }

    private ItemStack updateCompassItem(ItemStack item, Player holder) {
        Location target = targetProvider.resolveCompassTarget(holder);
        CompassMeta meta = (CompassMeta) item.getItemMeta();

        boolean shouldClear = (target == null || targetProvider.selectedRunnerUuid() == null);
        Location currentLodestone = meta.getLodestone();

        // 1. Check if we need to clear it, but it's already cleared
        if (shouldClear) {
            if (!meta.hasLodestone()) {
                return item; // No change needed
            }
        }
        // 2. Check if the target is in the exact same block as the current compass points to
        else if (currentLodestone != null) {
            if (Objects.equals(target.getWorld(), currentLodestone.getWorld()) &&
                    target.getBlockX() == currentLodestone.getBlockX() &&
                    target.getBlockY() == currentLodestone.getBlockY() &&
                    target.getBlockZ() == currentLodestone.getBlockZ()) {
                return item; // No change needed, return original object
            }
        }

        // Once target state differs, clone-and-replace the stack so Bukkit sees a real inventory
        // mutation and the updated lodestone metadata persists correctly.
        ItemStack clone = item.clone();
        CompassMeta newMeta = (CompassMeta) clone.getItemMeta();

        if (shouldClear) {
            newMeta.clearLodestone();
        } else {
            newMeta.setLodestone(target);
            newMeta.setLodestoneTracked(false);
        }

        clone.setItemMeta(newMeta);
        return clone;
    }
}
