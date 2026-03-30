package io.github.ganyuke.peoplehunt.report;

import java.util.List;
import java.util.UUID;

public final class ReportModels {
    private ReportModels() {}

    public record SessionMetadata(
            UUID reportId,
            UUID runnerUuid,
            String runnerName,
            long startedAtEpochMillis,
            long endedAtEpochMillis,
            String outcome,
            String keepInventoryMode,
            String activeKitId,
            String storageFormat
    ) {}

    public record Participant(
            UUID uuid,
            String name,
            String role,
            String colorHex,
            boolean joinedLate,
            boolean spectatorOnly
    ) {}

    public record ParticipantStats(
            UUID uuid,
            int deaths,
            int playerKills,
            double playerDamageDealt,
            double playerDamageTaken
    ) {}

    public record LocationRecord(
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {}

    public record SimplePoint(
            String world,
            double x,
            double y,
            double z,
            long offsetMillis
    ) {}

    public record DamageRecord(
            long offsetMillis,
            UUID attackerUuid,
            String attackerName,
            UUID victimUuid,
            String victimName,
            String cause,
            double damage,
            String weapon,
            UUID projectileUuid,
            List<SimplePoint> projectilePath,
            LocationRecord attackerLocation,
            LocationRecord victimLocation
    ) {}

    public record DeathRecord(
            long offsetMillis,
            UUID victimUuid,
            String victimName,
            UUID killerUuid,
            String killerName,
            String cause,
            String weapon,
            LocationRecord location,
            String deathMessageHtml,
            String deathMessagePlain
    ) {}

    public record PathPoint(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            int lifeIndex,
            String world,
            double x,
            double y,
            double z,
            float health,
            int food,
            float saturation
    ) {}

    public record MilestoneRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String key,
            String description
    ) {}

    public record ChatRecord(
            long offsetMillis,
            String kind,
            UUID playerUuid,
            String playerName,
            String html,
            String plainText
    ) {}

    public record ProjectileRecord(
            UUID projectileUuid,
            UUID shooterUuid,
            String shooterName,
            String type,
            long launchedAtOffsetMillis,
            Long endedAtOffsetMillis,
            List<SimplePoint> points
    ) {}

    public record TimelineRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String kind,
            String description
    ) {}

    public record ViewerSnapshot(
            SessionMetadata metadata,
            List<Participant> participants,
            List<ParticipantStats> stats,
            List<DamageRecord> damage,
            List<DeathRecord> deaths,
            List<PathPoint> paths,
            List<MilestoneRecord> milestones,
            List<ChatRecord> chat,
            List<ProjectileRecord> projectiles,
            List<TimelineRecord> timeline
    ) {}

    public record IndexEntry(
            UUID reportId,
            long startedAtEpochMillis,
            long endedAtEpochMillis,
            String outcome,
            UUID runnerUuid,
            String runnerName,
            String storageFormat
    ) {}
}
