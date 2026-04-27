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
            String activeKitId
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
            double playerDamageTaken,
            double nonPlayerDamageTaken
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

    public record InventoryEnchant(
            String rawId,
            String prettyName,
            int level
    ) {}

    public record InventoryItem(
            int slot,
            String rawId,
            String prettyName,
            int amount,
            boolean enchanted,
            String textColorHex,
            String serializedItem,
            List<InventoryEnchant> enchantments
    ) {}

    public record EffectState(
            String rawType,
            String prettyName,
            int amplifier,
            int durationTicks,
            boolean ambient
    ) {}

    public record DamageRecord(
            long offsetMillis,
            UUID attackerUuid,
            UUID attackerEntityUuid,
            String attackerName,
            String attackerEntityType,
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
            String killerEntityType,
            String cause,
            String weapon,
            LocationRecord location,
            int xpLevel,
            List<InventoryItem> inventory,
            String deathMessageHtml,
            String deathMessagePlain
    ) {}

    public record FoodRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String rawName,
            String prettyName,
            String colorHex,
            float health,
            float absorption,
            int food,
            float saturation
    ) {}

    public record EffectRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String action,
            String rawName,
            String prettyName,
            int amplifier,
            int durationTicks,
            String cause,
            String sourceName,
            String colorHex
    ) {}

    public record TotemRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            LocationRecord location,
            String colorHex
    ) {}

    public record BlockRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String attackerName,
            String rawName,
            double blockedDamage,
            LocationRecord location,
            String colorHex
    ) {}

    public record PathPoint(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            int lifeIndex,
            String role,
            String gameMode,
            boolean isTeleport,
            String world,
            double x,
            double y,
            double z,
            float health,
            float maxHealth,
            float absorption,
            int food,
            float saturation,
            int xpLevel,
            int totalExperience,
            int selectedHotbarSlot,
            float experienceProgress,
            List<InventoryItem> inventory,
            List<EffectState> effects
    ) {}

    public record MilestoneRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String key,
            String description,
            String rawName,
            String colorHex
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
            String shooterEntityType,
            String type,
            String kind,
            String colorHex,
            long launchedAtOffsetMillis,
            Long endedAtOffsetMillis,
            List<SimplePoint> points
    ) {}

    public record MobTrackRecord(
            UUID entityUuid,
            String entityType,
            UUID targetPlayerUuid,
            String targetPlayerName,
            String colorHex,
            long startedAtOffsetMillis,
            Long endedAtOffsetMillis,
            String endReason,
            List<SimplePoint> points
    ) {}

    public record MobDeathRecord(
            long offsetMillis,
            UUID entityUuid,
            String entityType,
            UUID targetPlayerUuid,
            String targetPlayerName,
            UUID killerUuid,
            String killerName,
            String killerEntityType,
            String cause,
            String weapon,
            LocationRecord location
    ) {}

    public record MapMarker(
            UUID markerUuid,
            long offsetMillis,
            Long endedAtOffsetMillis,
            String kind,
            UUID playerUuid,
            String playerName,
            String world,
            double x,
            double y,
            double z,
            String label,
            String description,
            String colorHex
    ) {}

    public record DragonSample(
            long offsetMillis,
            String world,
            double x,
            double y,
            double z,
            float health,
            float maxHealth
    ) {}

    public record EndCrystalRecord(
            UUID crystalUuid,
            String world,
            double x,
            double y,
            double z,
            long spawnedAtOffsetMillis,
            Long destroyedAtOffsetMillis
    ) {}

    public record TimelineRecord(
            long offsetMillis,
            UUID playerUuid,
            String playerName,
            String kind,
            String description,
            String rawName,
            String colorHex
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
            List<MobTrackRecord> mobs,
            List<MobDeathRecord> mobDeaths,
            List<MapMarker> markers,
            List<DragonSample> dragon,
            List<EndCrystalRecord> endCrystals,
            List<FoodRecord> food,
            List<EffectRecord> effects,
            List<TotemRecord> totems,
            List<BlockRecord> blocks,
            List<TimelineRecord> timeline
    ) {}

    public record IndexEntry(
            UUID reportId,
            long startedAtEpochMillis,
            long endedAtEpochMillis,
            String outcome,
            UUID runnerUuid,
            String runnerName
    ) {}
}
