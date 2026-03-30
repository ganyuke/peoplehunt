package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.core.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SurroundService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final SafeLocationResolver safeLocationResolver;

    public SurroundService(JavaPlugin plugin, ConfigManager configManager, SafeLocationResolver safeLocationResolver) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.safeLocationResolver = safeLocationResolver;
    }

    public int surroundRunner(MatchSession session, int requestedRadius) {
        Player runner = Bukkit.getPlayer(session.runnerId());
        if (runner == null || !runner.isOnline()) {
            return 0;
        }
        List<Player> hunters = new ArrayList<>();
        for (UUID hunterId : session.hunterIds()) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null && hunter.isOnline()) {
                hunters.add(hunter);
            }
        }
        if (hunters.isEmpty()) {
            return 0;
        }
        int radius = Math.max(configManager.settings().surroundMinRadius(), Math.min(configManager.settings().surroundMaxRadius(), requestedRadius));
        Location runnerLocation = runner.getLocation();
        double step = (Math.PI * 2.0D) / hunters.size();
        int moved = 0;
        for (int index = 0; index < hunters.size(); index++) {
            Player hunter = hunters.get(index);
            double angle = step * index;
            double x = runnerLocation.getX() + (radius * Math.cos(angle));
            double z = runnerLocation.getZ() + (radius * Math.sin(angle));
            Location desired = new Location(runnerLocation.getWorld(), x, runnerLocation.getY(), z, hunter.getLocation().getYaw(), hunter.getLocation().getPitch());
            Location safe = safeLocationResolver.resolveSafeStandingLocation(desired);
            Vector direction = runnerLocation.toVector().subtract(safe.toVector());
            safe.setDirection(direction);
            hunter.teleport(safe);
            moved++;
        }
        return moved;
    }
}
