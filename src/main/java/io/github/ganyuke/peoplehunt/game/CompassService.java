package io.github.ganyuke.peoplehunt.game;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.util.Text;
import java.util.Collection;
import java.util.List;
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
        ItemStack template = createCompass();
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            player.getInventory().addItem(template.clone());
        }
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
        ItemStack clone = item.clone();
        CompassMeta meta = (CompassMeta) clone.getItemMeta();
        if (target == null || targetProvider.selectedRunnerUuid() == null) {
            meta.clearLodestone();
        } else {
            meta.setLodestone(target);
            meta.setLodestoneTracked(false);
        }
        clone.setItemMeta(meta);
        return clone;
    }
}
