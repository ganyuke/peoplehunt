package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import io.github.ganyuke.manhunt.game.RoleService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PathSampler {
    private final ConfigManager configManager;
    private final AnalyticsRecorder analyticsRecorder;
    private final LifeTracker lifeTracker;
    private final RoleService roleService;
    private final Map<UUID, SampleCursor> cursors = new HashMap<>();
    private UUID activeSessionId;

    public PathSampler(ConfigManager configManager, AnalyticsRecorder analyticsRecorder, LifeTracker lifeTracker, RoleService roleService) {
        this.configManager = configManager;
        this.analyticsRecorder = analyticsRecorder;
        this.lifeTracker = lifeTracker;
        this.roleService = roleService;
    }

    public void beginSession(MatchSession session) {
        this.activeSessionId = session.sessionId();
        this.cursors.clear();
        for (UUID participantId : session.participants()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline()) {
                forceSample(session, player, player.getLocation(), "SESSION_START", Map.of());
            }
        }
    }

    public void endSession() {
        this.activeSessionId = null;
        this.cursors.clear();
    }

    public void maybeSampleMove(MatchSession session, Player player, Location to) {
        if (!isActive(session, player)) {
            return;
        }
        SampleCursor cursor = cursors.get(player.getUniqueId());
        Instant now = Instant.now();
        if (cursor == null) {
            forceSample(session, player, to, "MOVE", Map.of());
            return;
        }
        boolean worldChanged = !cursor.location().getWorld().equals(to.getWorld());
        boolean distanceExceeded = cursor.location().distanceSquared(to) >= Math.pow(configManager.settings().sampleDistance(), 2.0D);
        boolean timeExceeded = Duration.between(cursor.instant(), now).toMillis() >= (long) configManager.settings().sampleIntervalTicks() * 50L;
        if (worldChanged || distanceExceeded || timeExceeded) {
            forceSample(session, player, to, worldChanged ? "WORLD_CHANGE" : "MOVE", Map.of());
        }
    }

    public void forceSample(MatchSession session, Player player, Location location, String sampleKind, Map<String, ?> data) {
        if (!isActive(session, player)) {
            return;
        }
        Location cloned = location == null ? null : location.clone();
        Role role = roleService.getRole(player.getUniqueId());
        int lifeNo = lifeTracker.currentLifeNumber(player.getUniqueId());
        analyticsRecorder.recordPathPoint(session.sessionId(), player.getUniqueId(), role, lifeNo, Instant.now(), cloned, sampleKind, new LinkedHashMap<>(data));
        if (cloned != null) {
            cursors.put(player.getUniqueId(), new SampleCursor(cloned, Instant.now()));
        }
    }

    private boolean isActive(MatchSession session, Player player) {
        return session != null
                && session.sessionId().equals(activeSessionId)
                && session.isParticipant(player.getUniqueId())
                && session.isRunning()
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR;
    }

    private record SampleCursor(Location location, Instant instant) {
    }
}
