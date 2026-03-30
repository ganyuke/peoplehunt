package io.github.ganyuke.manhunt.game;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MatchStatsService {
    private UUID activeSessionId;
    private int hunterDeaths;
    private int runnerDeaths;
    private double totalDamageToRunner;
    private final Map<UUID, Integer> playerDeaths = new LinkedHashMap<>();
    private final Map<UUID, Double> hunterDamageToRunner = new LinkedHashMap<>();

    public void beginSession(MatchSession session) {
        clear();
        this.activeSessionId = session.sessionId();
    }

    public void recordDamage(MatchSession session, UUID victimId, UUID attackerId, double finalDamage) {
        if (!isActive(session) || finalDamage <= 0.0D || victimId == null || attackerId == null) {
            return;
        }
        if (!session.runnerId().equals(victimId) || !session.hunterIds().contains(attackerId)) {
            return;
        }
        totalDamageToRunner += finalDamage;
        hunterDamageToRunner.merge(attackerId, finalDamage, Double::sum);
    }

    public void recordDeath(MatchSession session, UUID playerId, Role role) {
        if (!isActive(session) || playerId == null || role == Role.NONE) {
            return;
        }
        playerDeaths.merge(playerId, 1, Integer::sum);
        if (role == Role.RUNNER) {
            runnerDeaths++;
        } else if (role == Role.HUNTER) {
            hunterDeaths++;
        }
    }

    public Snapshot snapshot(MatchSession session) {
        return new Snapshot(
                session == null ? Duration.ZERO : duration(session),
                runnerDeaths,
                hunterDeaths,
                totalDamageToRunner,
                Map.copyOf(playerDeaths),
                Map.copyOf(hunterDamageToRunner)
        );
    }

    public Snapshot endSession(MatchSession session) {
        Snapshot snapshot = snapshot(session);
        clear();
        return snapshot;
    }

    private Duration duration(MatchSession session) {
        Instant startedAt = session.startedAt();
        Instant endedAt = session.endedAt();
        if (startedAt == null) {
            return Duration.ZERO;
        }
        if (endedAt == null) {
            return Duration.between(startedAt, Instant.now());
        }
        return Duration.between(startedAt, endedAt);
    }

    private boolean isActive(MatchSession session) {
        return session != null && activeSessionId != null && activeSessionId.equals(session.sessionId());
    }

    private void clear() {
        activeSessionId = null;
        hunterDeaths = 0;
        runnerDeaths = 0;
        totalDamageToRunner = 0.0D;
        playerDeaths.clear();
        hunterDamageToRunner.clear();
    }

    public record Snapshot(
            Duration duration,
            int runnerDeaths,
            int hunterDeaths,
            double totalDamageToRunner,
            Map<UUID, Integer> playerDeaths,
            Map<UUID, Double> hunterDamageToRunner
    ) {
    }
}
