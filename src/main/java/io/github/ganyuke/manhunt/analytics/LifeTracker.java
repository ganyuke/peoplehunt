package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import io.github.ganyuke.manhunt.game.RoleService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LifeTracker {
    private final RoleService roleService;
    private final AnalyticsRecorder analyticsRecorder;
    private final Map<UUID, Integer> nextLifeNumber = new HashMap<>();
    private final Map<UUID, Integer> activeLifeNumber = new HashMap<>();
    private UUID activeSessionId;

    public LifeTracker(RoleService roleService, AnalyticsRecorder analyticsRecorder) {
        this.roleService = roleService;
        this.analyticsRecorder = analyticsRecorder;
    }

    public void beginSession(MatchSession session) {
        clear();
        this.activeSessionId = session.sessionId();
        for (UUID participantId : session.participants()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline()) {
                startLife(session, player, player.getLocation());
            }
        }
    }

    public void startLife(MatchSession session, Player player, Location location) {
        if (!session.sessionId().equals(activeSessionId)) {
            return;
        }
        Role role = roleService.getRole(player.getUniqueId());
        if (role == Role.NONE) {
            return;
        }
        if (activeLifeNumber.containsKey(player.getUniqueId())) {
            return;
        }
        int next = nextLifeNumber.getOrDefault(player.getUniqueId(), 0) + 1;
        nextLifeNumber.put(player.getUniqueId(), next);
        activeLifeNumber.put(player.getUniqueId(), next);
        analyticsRecorder.recordLifeStart(session.sessionId(), player.getUniqueId(), role, next, Instant.now(), cloneLocation(location));
    }

    public void endLife(MatchSession session, UUID playerId, Location location, String reason) {
        if (!session.sessionId().equals(activeSessionId)) {
            return;
        }
        Integer activeLife = activeLifeNumber.remove(playerId);
        if (activeLife == null) {
            return;
        }
        Role role = roleService.getRole(playerId);
        if (role == Role.NONE) {
            role = session.runnerId().equals(playerId) ? Role.RUNNER : Role.HUNTER;
        }
        analyticsRecorder.recordLifeEnd(session.sessionId(), playerId, role, activeLife, Instant.now(), cloneLocation(location), reason);
    }

    public boolean hasActiveLife(UUID playerId) {
        return activeLifeNumber.containsKey(playerId);
    }

    public int currentLifeNumber(UUID playerId) {
        return activeLifeNumber.getOrDefault(playerId, nextLifeNumber.getOrDefault(playerId, 0));
    }

    public void endSession(MatchSession session, String reason) {
        if (activeSessionId == null || !activeSessionId.equals(session.sessionId())) {
            return;
        }
        for (UUID playerId : Map.copyOf(activeLifeNumber).keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            Location location = player == null ? null : player.getLocation();
            endLife(session, playerId, location, reason);
        }
        clear();
    }

    public void clear() {
        nextLifeNumber.clear();
        activeLifeNumber.clear();
        activeSessionId = null;
    }

    private Location cloneLocation(Location location) {
        return location == null ? null : location.clone();
    }
}
