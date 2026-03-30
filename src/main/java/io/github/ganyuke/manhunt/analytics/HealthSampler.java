package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import io.github.ganyuke.manhunt.game.RoleService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.UUID;

public final class HealthSampler {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final AnalyticsRecorder analyticsRecorder;
    private final RoleService roleService;
    private final LifeTracker lifeTracker;
    private BukkitTask task;
    private MatchSession session;

    public HealthSampler(JavaPlugin plugin, ConfigManager configManager, AnalyticsRecorder analyticsRecorder, RoleService roleService, LifeTracker lifeTracker) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.analyticsRecorder = analyticsRecorder;
        this.roleService = roleService;
        this.lifeTracker = lifeTracker;
    }

    public void start(MatchSession session) {
        stop();
        this.session = session;
        if (!configManager.settings().analyticsEnabled()) {
            return;
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, configManager.settings().healthSampleIntervalTicks());
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

    private void tick() {
        if (session == null || !session.isRunning()) {
            return;
        }
        for (UUID participantId : session.participants()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            Role role = roleService.getRole(player.getUniqueId());
            int lifeNo = lifeTracker.currentLifeNumber(player.getUniqueId());
            Location location = player.getLocation().clone();
            analyticsRecorder.recordHealthSample(
                    session.sessionId(),
                    player.getUniqueId(),
                    role,
                    lifeNo,
                    Instant.now(),
                    location,
                    player.getHealth(),
                    attributeValue(player, Attribute.MAX_HEALTH, 20.0D),
                    player.getFoodLevel(),
                    player.getSaturation(),
                    attributeValue(player, Attribute.ARMOR, 0.0D),
                    player.getLevel()
            );
        }
    }

    private double attributeValue(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }
}
