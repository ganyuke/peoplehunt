package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CompassService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final NamespacedKey trackerKey;
    private final Map<String, Location> runnerLastKnownByWorld = new HashMap<>();
    private final Map<String, Location> runnerPortalAnchors = new HashMap<>();
    private BukkitTask task;
    private MatchSession session;

    public CompassService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.trackerKey = new NamespacedKey(plugin, "runner_tracker");
    }

    public void start(MatchSession session) {
        stop();
        this.session = session;
        if (!configManager.settings().compassEnabled()) {
            return;
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, configManager.settings().compassRefreshTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        session = null;
    }

    public void restartIfRunning(MatchSession activeSession) {
        if (activeSession != null && activeSession.isRunning()) {
            start(activeSession);
        }
    }

    public void shutdown() {
        stop();
    }

    public void giveTrackerCompass(Player player) {
        player.getInventory().addItem(createTrackerCompass());
    }

    public void giveTrackersToHunters(MatchSession session) {
        for (UUID hunterId : session.hunterIds()) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null) {
                giveTrackerCompass(hunter);
            }
        }
    }

    public void recordRunnerLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        runnerLastKnownByWorld.put(location.getWorld().getName(), location.clone());
    }

    public void recordPortalAnchor(Location from, Location to) {
        if (from != null && from.getWorld() != null) {
            runnerPortalAnchors.put(from.getWorld().getName(), from.clone());
        }
        if (to != null && to.getWorld() != null) {
            runnerPortalAnchors.put(to.getWorld().getName(), to.clone());
        }
    }

    private void tick() {
        if (session == null || !session.isRunning()) {
            return;
        }
        Player runner = Bukkit.getPlayer(session.runnerId());
        if (runner != null && runner.isOnline()) {
            recordRunnerLocation(runner.getLocation());
        }
        for (UUID hunterId : session.hunterIds()) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }
            updateHunterTrackers(hunter, resolveTarget(hunter, runner));
        }
    }

    private Location resolveTarget(Player hunter, Player runner) {
        if (runner != null && runner.isOnline() && runner.getWorld().equals(hunter.getWorld())) {
            return runner.getLocation().clone();
        }
        return switch (configManager.settings().compassTrackingMode()) {
            case STRICT_VANILLA -> null;
            case LAST_KNOWN -> cloneOrNull(runnerLastKnownByWorld.get(hunter.getWorld().getName()));
            case PORTAL_ANCHOR -> {
                Location anchor = runnerPortalAnchors.get(hunter.getWorld().getName());
                if (anchor != null) {
                    yield anchor.clone();
                }
                yield cloneOrNull(runnerLastKnownByWorld.get(hunter.getWorld().getName()));
            }
        };
    }

    private void updateHunterTrackers(Player hunter, Location target) {
        PlayerInventory inventory = hunter.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!isTracker(itemStack)) {
                continue;
            }
            if (!(itemStack.getItemMeta() instanceof CompassMeta compassMeta)) {
                continue;
            }
            if (target != null) {
                compassMeta.setLodestone(target);
                compassMeta.setLodestoneTracked(false);
                itemStack.setItemMeta(compassMeta);
                inventory.setItem(slot, itemStack);
            }
        }
    }

    private ItemStack createTrackerCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.setDisplayName(TextUtil.colorize(configManager.settings().compassTitle()));
        List<String> lore = configManager.settings().compassLore().stream().map(TextUtil::colorize).toList();
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(trackerKey, PersistentDataType.BYTE, (byte) 1);
        meta.setLodestoneTracked(false);
        compass.setItemMeta(meta);
        return compass;
    }

    private boolean isTracker(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.COMPASS) {
            return false;
        }
        if (!(itemStack.getItemMeta() instanceof CompassMeta compassMeta)) {
            return false;
        }
        return compassMeta.getPersistentDataContainer().has(trackerKey, PersistentDataType.BYTE);
    }

    private Location cloneOrNull(Location location) {
        return location == null ? null : location.clone();
    }
}
