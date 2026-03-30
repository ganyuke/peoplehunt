package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import org.bukkit.Location;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class NoopAnalyticsRecorder implements AnalyticsRecorder {
    @Override
    public void openSession(MatchSession session) {
    }

    @Override
    public void markPrimed(MatchSession session) {
    }

    @Override
    public void markStarted(MatchSession session) {
    }

    @Override
    public void markEnded(MatchSession session) {
    }

    @Override
    public void recordLifeStart(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location) {
    }

    @Override
    public void recordLifeEnd(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String reason) {
    }

    @Override
    public void recordPathPoint(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String sampleKind, Map<String, ?> data) {
    }

    @Override
    public void recordDamage(UUID sessionId, UUID victimId, UUID attackerId, Instant at, double rawDamage, double finalDamage, String damageType, String causingEntityType, String directEntityType, UUID causingEntityId, Location sourceLocation) {
    }

    @Override
    public void recordDeath(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String damageType, String causingEntityType, String directEntityType, UUID causingEntityId) {
    }

    @Override
    public void recordHealthSample(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, double health, double maxHealth, int food, float saturation, double armor, int level) {
    }

    @Override
    public void recordMilestone(UUID sessionId, UUID playerId, Role role, Instant at, Location location, String milestoneKey, String title, Map<String, ?> data) {
    }

    @Override
    public Path getSessionDirectory(UUID sessionId) {
        return null;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
