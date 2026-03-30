package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import org.bukkit.Location;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AnalyticsRecorder {
    void openSession(MatchSession session);

    void markPrimed(MatchSession session);

    void markStarted(MatchSession session);

    void markEnded(MatchSession session);

    void recordLifeStart(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location);

    void recordLifeEnd(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String reason);

    void recordPathPoint(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String sampleKind, Map<String, ?> data);

    void recordDamage(UUID sessionId, UUID victimId, UUID attackerId, Instant at, double rawDamage, double finalDamage,
                      String damageType, String causingEntityType, String directEntityType, UUID causingEntityId, Location sourceLocation);

    void recordDeath(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location,
                     String damageType, String causingEntityType, String directEntityType, UUID causingEntityId);

    void recordHealthSample(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location,
                            double health, double maxHealth, int food, float saturation, double armor, int level);

    void recordMilestone(UUID sessionId, UUID playerId, Role role, Instant at, Location location,
                         String milestoneKey, String title, Map<String, ?> data);

    Path getSessionDirectory(UUID sessionId);

    void flush();

    void close();
}
