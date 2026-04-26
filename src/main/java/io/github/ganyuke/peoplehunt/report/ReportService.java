package io.github.ganyuke.peoplehunt.report;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.match.MatchOutcome;
import io.github.ganyuke.peoplehunt.report.ReportModels.*;
import io.github.ganyuke.peoplehunt.util.HtmlUtil;
import io.github.ganyuke.peoplehunt.util.LocationUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import io.github.ganyuke.peoplehunt.util.ZipUtil;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;

public final class ReportService {
    private static final String[] PALETTE = {
            "#ff6b6b", "#4ecdc4", "#ffe66d", "#1a535c", "#ff9f1c", "#6a4c93", "#8ac926", "#1982c4",
            "#ff595e", "#8338ec", "#3a86ff", "#ffbe0b"
    };

    private final Path reportsDirectory;
    private final Gson gson;
    private final Path indexFile;
    private final List<IndexEntry> indexEntries = new ArrayList<>();
    private CurrentSession currentSession;

    public ReportService(Path reportsDirectory, Gson gson) {
        this.reportsDirectory = reportsDirectory;
        this.gson = gson;
        this.indexFile = reportsDirectory.resolve("index.json");
    }

    public void loadIndex() throws IOException {
        indexEntries.clear();
        if (!Files.exists(indexFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(indexFile)) {
            List<IndexEntry> entries = gson.fromJson(reader, new TypeToken<List<IndexEntry>>() {}.getType());
            if (entries != null) {
                indexEntries.addAll(entries);
            }
        }
    }

    public void saveIndex() throws IOException {
        Files.createDirectories(reportsDirectory);
        try (Writer writer = Files.newBufferedWriter(indexFile)) {
            gson.toJson(indexEntries, writer);
        }
    }

    public synchronized UUID startSession(
            UUID runnerUuid,
            String runnerName,
            KeepInventoryMode keepInventoryMode,
            String activeKitId,
            Collection<ParticipantSeed> participants
    ) {
        UUID reportId = UUID.randomUUID();
        currentSession = new CurrentSession(reportId, System.currentTimeMillis(), runnerUuid, runnerName, keepInventoryMode, activeKitId);
        int index = 0;
        for (ParticipantSeed seed : participants) {
            currentSession.participants.put(seed.uuid(), new Participant(
                    seed.uuid(),
                    seed.name(),
                    seed.role(),
                    assignedColor(seed.uuid(), index),
                    seed.joinedLate(),
                    seed.spectatorOnly()
            ));
            currentSession.stats.put(seed.uuid(), new MutableStats());
            index++;
        }
        return reportId;
    }

    public synchronized boolean isRunning() {
        return currentSession != null;
    }

    public synchronized String colorOfParticipant(UUID uuid) {
        if (uuid == null || currentSession == null) {
            return null;
        }
        Participant participant = currentSession.participants.get(uuid);
        return participant == null ? null : participant.colorHex();
    }

    private String defaultColor(UUID uuid, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return colorOfParticipant(uuid);
    }

    private String assignedColor(UUID uuid, int index) {
        if (index < PALETTE.length) {
            return PALETTE[index];
        }
        Set<String> used = new LinkedHashSet<>();
        if (currentSession != null) {
            currentSession.participants.values().forEach(participant -> used.add(participant.colorHex()));
        }
        int hash = uuid == null ? index * 7919 : uuid.hashCode();
        double hue = Math.floorMod(hash, 360);
        double saturation = 0.72;
        double lightness = 0.62;
        for (int attempt = 0; attempt < 720; attempt++) {
            String candidate = hslToHex(Math.floorMod((int) Math.round(hue + (attempt * 137.508)), 360), saturation, lightness);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return hslToHex(Math.floorMod(hash, 360), 0.72, 0.62);
    }

    private String hslToHex(double hueDegrees, double saturation, double lightness) {
        double hue = (hueDegrees % 360.0) / 360.0;
        double q = lightness < 0.5 ? lightness * (1 + saturation) : (lightness + saturation - lightness * saturation);
        double p = 2 * lightness - q;
        double r = hueToRgb(p, q, hue + (1.0 / 3.0));
        double g = hueToRgb(p, q, hue);
        double b = hueToRgb(p, q, hue - (1.0 / 3.0));
        return String.format("#%02x%02x%02x", Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    private double hueToRgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < (1.0 / 6.0)) return p + (q - p) * 6 * t;
        if (t < 0.5) return q;
        if (t < (2.0 / 3.0)) return p + (q - p) * ((2.0 / 3.0) - t) * 6;
        return p;
    }

    public synchronized UUID currentReportId() {
        return currentSession == null ? null : currentSession.reportId;
    }

    public synchronized void updateSessionSettings(KeepInventoryMode keepInventoryMode, String activeKitId) {
        if (currentSession == null) {
            return;
        }
        currentSession.keepInventoryMode = keepInventoryMode;
        currentSession.activeKitId = activeKitId;
    }

    public synchronized void registerParticipant(UUID uuid, String name, String role, boolean joinedLate, boolean spectatorOnly) {
        if (currentSession == null) {
            return;
        }
        Participant existing = currentSession.participants.get(uuid);
        if (existing == null) {
            String color = assignedColor(uuid, currentSession.participants.size());
            currentSession.participants.put(uuid, new Participant(uuid, name, role, color, joinedLate, spectatorOnly));
            currentSession.stats.put(uuid, new MutableStats());
            currentSession.timeline.add(new TimelineRecord(offset(), uuid, name, "participant", role.toLowerCase() + " joined", role, color));
            return;
        }
        currentSession.participants.put(uuid, new Participant(
                uuid,
                name,
                existing.role().equals("RUNNER") ? existing.role() : role,
                existing.colorHex(),
                existing.joinedLate() || joinedLate,
                existing.spectatorOnly() && spectatorOnly
        ));
    }

    public synchronized void recordChat(String kind, UUID playerUuid, String playerName, Component component) {
        if (currentSession == null) {
            return;
        }
        String html = HtmlUtil.componentToHtml(component);
        String plain = Text.plain(component);
        currentSession.chat.add(new ChatRecord(offset(), kind, playerUuid, playerName, html, plain));
    }

    public synchronized void recordChatRaw(String kind, UUID playerUuid, String playerName, String html, String plain) {
        if (currentSession == null) {
            return;
        }
        currentSession.chat.add(new ChatRecord(offset(), kind, playerUuid, playerName, html, plain));
    }

    public synchronized void recordMilestone(UUID playerUuid, String playerName, String key, String description) {
        recordMilestone(playerUuid, playerName, key, description, null, null);
    }

    public synchronized void recordMilestone(UUID playerUuid, String playerName, String key, String description, String rawName, String colorHex) {
        if (currentSession == null) {
            return;
        }
        String resolvedColor = defaultColor(playerUuid, colorHex);
        currentSession.milestones.add(new MilestoneRecord(offset(), playerUuid, playerName, key, description, rawName, resolvedColor));
        currentSession.timeline.add(new TimelineRecord(offset(), playerUuid, playerName, "milestone", description, rawName, resolvedColor));
    }

    public synchronized void recordTimeline(UUID playerUuid, String playerName, String kind, String description) {
        recordTimeline(playerUuid, playerName, kind, description, null, null);
    }

    public synchronized void recordTimeline(UUID playerUuid, String playerName, String kind, String description, String rawName, String colorHex) {
        if (currentSession == null) {
            return;
        }
        currentSession.timeline.add(new TimelineRecord(offset(), playerUuid, playerName, kind, description, rawName, defaultColor(playerUuid, colorHex)));
    }

    public synchronized void recordFood(UUID playerUuid, String playerName, String rawName, String prettyName, String colorHex,
                                        float health, float absorption, int food, float saturation) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String resolvedColor = defaultColor(playerUuid, colorHex);
        currentSession.food.add(new FoodRecord(now, playerUuid, playerName, rawName, prettyName, resolvedColor, health, absorption, food, saturation));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "food", "ate " + prettyName, rawName, resolvedColor));
    }

    public synchronized void recordEffect(UUID playerUuid, String playerName, String action, String rawName, String prettyName,
                                          int amplifier, int durationTicks, String cause, String sourceName, String colorHex) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String description = switch (String.valueOf(action)) {
            case "ADDED" -> cause == null || cause.isBlank() ? "gained " + prettyName : "gained " + prettyName + " from " + cause;
            case "CHANGED" -> cause == null || cause.isBlank() ? "effect changed: " + prettyName : "effect changed: " + prettyName + " via " + cause;
            default -> cause == null || cause.isBlank() ? "lost " + prettyName : "lost " + prettyName + " after " + cause;
        };
        currentSession.effects.add(new EffectRecord(now, playerUuid, playerName, action, rawName, prettyName, amplifier, durationTicks, cause, sourceName, defaultColor(playerUuid, colorHex)));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "effect", description, rawName, defaultColor(playerUuid, colorHex)));
    }

    public synchronized void recordTotem(UUID playerUuid, String playerName, Location location, String colorHex) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String resolvedColor = defaultColor(playerUuid, colorHex == null ? "#ffff55" : colorHex);
        currentSession.totems.add(new TotemRecord(now, playerUuid, playerName, LocationUtil.toRecord(location), resolvedColor));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "totem", "Totem of Undying activated", "minecraft:totem_of_undying", resolvedColor));
    }

    public synchronized void recordBlock(UUID playerUuid, String playerName, String attackerName, String rawName, double blockedDamage, Location location, String colorHex) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String resolvedColor = defaultColor(playerUuid, colorHex);
        currentSession.blocks.add(new BlockRecord(now, playerUuid, playerName, attackerName, rawName, blockedDamage, LocationUtil.toRecord(location), resolvedColor));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "shield", "blocked %.1f damage from %s".formatted(blockedDamage, attackerName), rawName, resolvedColor));
    }

    public synchronized void recordMarker(String kind, UUID playerUuid, String playerName, Location location, String label, String description, String colorHex) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.markers.add(new MapMarker(
                UUID.randomUUID(),
                offset(),
                null,
                kind,
                playerUuid,
                playerName,
                location.getWorld().getKey().asString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                label,
                description,
                defaultColor(playerUuid, colorHex)
        ));
    }

    public synchronized void recordSpawnMarker(UUID playerUuid, String playerName, Location location, String label, String description, String colorHex) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        String key = location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
        MapMarker existing = currentSession.spawnMarkers.get(key);
        String ownerText = playerName == null || playerName.isBlank() ? "" : playerName;
        String mergedDescription = description;
        if (!ownerText.isBlank()) {
            mergedDescription = (description == null || description.isBlank() ? "Respawn location" : description) + " — " + ownerText;
        }
        if (existing == null) {
            MapMarker marker = new MapMarker(
                    UUID.randomUUID(), offset(), null, "spawn", playerUuid, playerName,
                    location.getWorld().getKey().asString(), location.getX(), location.getY(), location.getZ(),
                    label, mergedDescription, defaultColor(playerUuid, colorHex)
            );
            currentSession.spawnMarkers.put(key, marker);
            currentSession.markers.add(marker);
            return;
        }
        String mergedPlayerName = mergeNames(existing.playerName(), playerName);
        String mergedPlayerUuid = mergedPlayerName != null && mergedPlayerName.contains(",") ? null : toString(existing.playerUuid() == null ? playerUuid : existing.playerUuid());
        String merged = mergeDescriptions(existing.description(), ownerText);
        MapMarker updated = new MapMarker(
                existing.markerUuid(),
                existing.offsetMillis(),
                existing.endedAtOffsetMillis(),
                existing.kind(),
                mergedPlayerUuid == null ? null : uuid(mergedPlayerUuid),
                mergedPlayerName,
                existing.world(),
                existing.x(),
                existing.y(),
                existing.z(),
                existing.label(),
                merged,
                existing.colorHex()
        );
        currentSession.spawnMarkers.put(key, updated);
        int index = currentSession.markers.indexOf(existing);
        if (index >= 0) {
            currentSession.markers.set(index, updated);
        }
    }

    private String mergeNames(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        for (String piece : existing.split(",\\s*")) {
            if (piece.equalsIgnoreCase(incoming)) {
                return existing;
            }
        }
        return existing + ", " + incoming;
    }

    private String mergeDescriptions(String existing, String ownerText) {
        if (ownerText == null || ownerText.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return ownerText;
        }
        if (existing.contains(ownerText)) {
            return existing;
        }
        return existing + ", " + ownerText;
    }

    public synchronized void recordPath(
            UUID playerUuid,
            String playerName,
            int lifeIndex,
            String role,
            String gameMode,
            boolean isTeleport,
            Location location,
            float health,
            float maxHealth,
            float absorption,
            int food,
            float saturation,
            int xpLevel,
            int totalExperience,
            List<InventoryItem> inventory,
            List<EffectState> effects
    ) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.paths.add(new PathPoint(
                offset(),
                playerUuid,
                playerName,
                lifeIndex,
                role,
                gameMode,
                isTeleport,
                location.getWorld().getKey().asString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                health,
                maxHealth,
                absorption,
                food,
                saturation,
                xpLevel,
                totalExperience,
                inventory == null ? List.of() : List.copyOf(inventory),
                effects == null ? List.of() : List.copyOf(effects)
        ));
    }

    public synchronized void recordDamage(
            UUID attackerUuid,
            String attackerName,
            UUID victimUuid,
            String victimName,
            String cause,
            double damage,
            String weapon,
            UUID projectileUuid,
            Location attackerLocation,
            Location victimLocation
    ) {
        recordDamage(attackerUuid, attackerUuid, attackerName, attackerUuid == null ? null : "PLAYER", victimUuid, victimName, cause, damage, weapon, projectileUuid, attackerLocation, victimLocation);
    }

    public synchronized void recordDamage(
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
            Location attackerLocation,
            Location victimLocation
    ) {
        if (currentSession == null) {
            return;
        }
        List<SimplePoint> projectilePath = projectileUuid == null ? List.of() : currentSession.projectiles.getOrDefault(projectileUuid, TrackedProjectile.empty()).snapshotPoints();
        currentSession.damage.add(new DamageRecord(
                offset(),
                attackerUuid,
                attackerEntityUuid,
                attackerName,
                attackerEntityType,
                victimUuid,
                victimName,
                cause,
                damage,
                weapon,
                projectileUuid,
                projectilePath,
                LocationUtil.toRecord(attackerLocation),
                LocationUtil.toRecord(victimLocation)
        ));
        MutableStats attackerStats = currentSession.stats.get(attackerUuid);
        if (attackerStats != null) {
            attackerStats.playerDamageDealt += damage;
        }
        MutableStats victimStats = currentSession.stats.get(victimUuid);
        if (victimStats != null) {
            if (attackerUuid != null) {
                victimStats.playerDamageTaken += damage;
            } else {
                victimStats.nonPlayerDamageTaken += damage;
            }
        }
        currentSession.timeline.add(new TimelineRecord(offset(), attackerUuid, attackerName, "damage", "dealt %.2f to %s with %s".formatted(damage, victimName, weapon == null ? cause : weapon), cause, null));
    }

    public synchronized void recordDeath(
            UUID victimUuid,
            String victimName,
            UUID killerUuid,
            String killerName,
            String cause,
            String weapon,
            Location location,
            int xpLevel,
            List<InventoryItem> inventory,
            Component deathMessage
    ) {
        recordDeath(victimUuid, victimName, killerUuid, killerName, killerUuid == null ? null : "PLAYER", cause, weapon, location, xpLevel, inventory, deathMessage);
    }

    public synchronized void recordDeath(
            UUID victimUuid,
            String victimName,
            UUID killerUuid,
            String killerName,
            String killerEntityType,
            String cause,
            String weapon,
            Location location,
            int xpLevel,
            List<InventoryItem> inventory,
            Component deathMessage
    ) {
        if (currentSession == null) {
            return;
        }
        String html = deathMessage == null ? HtmlUtil.escape(victimName + " died") : HtmlUtil.componentToHtml(deathMessage);
        String plain = deathMessage == null ? victimName + " died" : Text.plain(deathMessage);
        currentSession.deaths.add(new DeathRecord(
                offset(),
                victimUuid,
                victimName,
                killerUuid,
                killerName,
                killerEntityType,
                cause,
                weapon,
                LocationUtil.toRecord(location),
                xpLevel,
                inventory == null ? List.of() : List.copyOf(inventory),
                html,
                plain
        ));
        MutableStats victimStats = currentSession.stats.get(victimUuid);
        if (victimStats != null) {
            victimStats.deaths++;
        }
        MutableStats killerStats = currentSession.stats.get(killerUuid);
        if (killerStats != null) {
            killerStats.playerKills++;
        }
        currentSession.timeline.add(new TimelineRecord(offset(), victimUuid, victimName, "death", plain, cause, "#ff6b6b"));
    }

    public synchronized void startProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String type, Location start) {
        startProjectile(projectileUuid, shooterUuid, shooterName, shooterUuid == null ? null : "PLAYER", type, "player", null, start);
    }

    public synchronized void startProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String shooterEntityType, String type, String kind, String colorHex, Location start) {
        if (currentSession == null || projectileUuid == null) {
            return;
        }
        TrackedProjectile tracked = new TrackedProjectile(projectileUuid, shooterUuid, shooterName, shooterEntityType, type, kind, defaultColor(shooterUuid, colorHex), offset());
        tracked.addPoint(start, offset());
        currentSession.projectiles.put(projectileUuid, tracked);
    }

    public synchronized void recordProjectilePoint(UUID projectileUuid, Location location) {
        if (currentSession == null || projectileUuid == null || location == null) {
            return;
        }
        TrackedProjectile tracked = currentSession.projectiles.get(projectileUuid);
        if (tracked != null) {
            tracked.addPoint(location, offset());
        }
    }

    public synchronized void finishProjectile(UUID projectileUuid, Location location) {
        if (currentSession == null || projectileUuid == null) {
            return;
        }
        TrackedProjectile tracked = currentSession.projectiles.get(projectileUuid);
        if (tracked != null) {
            tracked.addPoint(location, offset());
            tracked.end(offset());
        }
    }

    public synchronized void startMobTrack(UUID entityUuid, String entityType, UUID targetPlayerUuid, String targetPlayerName, String colorHex, Location start) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        currentSession.mobs.computeIfAbsent(entityUuid, ignored -> {
            TrackedMob mob = new TrackedMob(entityUuid, entityType, targetPlayerUuid, targetPlayerName, colorHex, offset());
            mob.touch(targetPlayerUuid, targetPlayerName, start, offset());
            return mob;
        }).touch(targetPlayerUuid, targetPlayerName, start, offset());
    }

    public synchronized void recordMobPoint(UUID entityUuid, UUID targetPlayerUuid, String targetPlayerName, Location location) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        TrackedMob mob = currentSession.mobs.get(entityUuid);
        if (mob != null) {
            mob.touch(targetPlayerUuid, targetPlayerName, location, offset());
        }
    }

    public synchronized void finishMobTrack(UUID entityUuid) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        TrackedMob mob = currentSession.mobs.get(entityUuid);
        if (mob != null) {
            mob.end(offset());
        }
    }

    public synchronized void recordDragonSample(Location location, float health, float maxHealth) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.dragon.add(new DragonSample(offset(), location.getWorld().getKey().asString(), location.getX(), location.getY(), location.getZ(), health, maxHealth));
    }

    public synchronized void upsertEndCrystal(UUID crystalUuid, Location location) {
        if (currentSession == null || crystalUuid == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.endCrystals.computeIfAbsent(crystalUuid, ignored -> new MutableCrystal(crystalUuid, location.getWorld().getKey().asString(), location.getX(), location.getY(), location.getZ(), offset()));
    }

    public synchronized void markEndCrystalDestroyed(UUID crystalUuid) {
        if (currentSession == null || crystalUuid == null) {
            return;
        }
        MutableCrystal crystal = currentSession.endCrystals.get(crystalUuid);
        if (crystal != null && crystal.destroyedAtOffsetMillis == null) {
            crystal.destroyedAtOffsetMillis = offset();
        }
    }

    public synchronized Set<UUID> activeProjectiles() {
        if (currentSession == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(currentSession.projectiles.keySet());
    }

    public synchronized Optional<FinishResult> finish(MatchOutcome outcome) throws IOException {
        if (currentSession == null) {
            return Optional.empty();
        }
        CurrentSession session = currentSession;
        currentSession = null;
        long endedAt = System.currentTimeMillis();
        ViewerSnapshot snapshot = new ViewerSnapshot(
                new SessionMetadata(
                        session.reportId,
                        session.runnerUuid,
                        session.runnerName,
                        session.startedAtEpochMillis,
                        endedAt,
                        outcome.name(),
                        session.keepInventoryMode.name(),
                        session.activeKitId
                ),
                new ArrayList<>(session.participants.values()),
                session.stats.entrySet().stream().map(entry -> entry.getValue().toImmutable(entry.getKey())).toList(),
                List.copyOf(session.damage),
                List.copyOf(session.deaths),
                List.copyOf(session.paths),
                List.copyOf(session.milestones),
                List.copyOf(session.chat),
                session.projectiles.values().stream().map(TrackedProjectile::toRecord).toList(),
                session.mobs.values().stream().map(TrackedMob::toRecord).toList(),
                List.copyOf(session.markers),
                List.copyOf(session.dragon),
                session.endCrystals.values().stream().map(MutableCrystal::toRecord).toList(),
                List.copyOf(session.food),
                List.copyOf(session.effects),
                List.copyOf(session.totems),
                List.copyOf(session.blocks),
                List.copyOf(session.timeline)
        );
        Path reportDir = reportsDirectory.resolve(session.reportId.toString());
        Files.createDirectories(reportDir);
        writeSqlite(reportDir, snapshot);
        IndexEntry indexEntry = new IndexEntry(
                session.reportId,
                session.startedAtEpochMillis,
                endedAt,
                outcome.name(),
                session.runnerUuid,
                session.runnerName
        );
        indexEntries.removeIf(entry -> entry.reportId().equals(indexEntry.reportId()));
        indexEntries.add(indexEntry);
        indexEntries.sort(Comparator.comparingLong(IndexEntry::endedAtEpochMillis).reversed());
        saveIndex();
        return Optional.of(new FinishResult(indexEntry, snapshot));
    }

    public synchronized List<IndexEntry> listReports() {
        return indexEntries.stream().sorted(Comparator.comparingLong(IndexEntry::endedAtEpochMillis).reversed()).toList();
    }

    public synchronized UUID latestReportId() {
        return indexEntries.stream().max(Comparator.comparingLong(IndexEntry::endedAtEpochMillis)).map(IndexEntry::reportId).orElse(null);
    }

    public synchronized ViewerSnapshot readSnapshot(UUID reportId) throws IOException {
        Path reportDir = reportsDirectory.resolve(reportId.toString());
        Path sqlite = reportDir.resolve("report.db");
        if (!Files.exists(sqlite)) {
            throw new IOException("Missing report database for " + reportId);
        }
        return readFromSqlite(sqlite);
    }

    public synchronized Optional<IndexEntry> findIndex(UUID reportId) {
        return indexEntries.stream().filter(entry -> entry.reportId().equals(reportId)).findFirst();
    }

    public synchronized Path export(UUID reportId, String viewerHtml) throws IOException {
        Path exportDir = reportsDirectory.resolve(reportId + "-export");
        if (Files.exists(exportDir)) {
            deleteRecursively(exportDir);
        }
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("index.html"), viewerHtml);
        Path zipFile = reportsDirectory.resolve("report-" + reportId + ".zip");
        if (Files.exists(zipFile)) {
            Files.delete(zipFile);
        }
        ZipUtil.zipDirectory(exportDir, zipFile);
        deleteRecursively(exportDir);
        return zipFile;
    }

    public synchronized String toJson(ViewerSnapshot snapshot) {
        return gson.toJson(snapshot);
    }

    private long offset() {
        return currentSession == null ? 0L : System.currentTimeMillis() - currentSession.startedAtEpochMillis;
    }

    private void writeSqlite(Path reportDir, ViewerSnapshot snapshot) throws IOException {
        Path sqliteFile = reportDir.resolve("report.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE session_metadata (report_id TEXT PRIMARY KEY, runner_uuid TEXT, runner_name TEXT, started_at INTEGER, ended_at INTEGER, outcome TEXT, keep_inventory_mode TEXT, active_kit_id TEXT)");
                statement.executeUpdate("CREATE TABLE participants (uuid TEXT PRIMARY KEY, name TEXT, role TEXT, color_hex TEXT, joined_late INTEGER, spectator_only INTEGER)");
                statement.executeUpdate("CREATE TABLE participant_stats (uuid TEXT PRIMARY KEY, deaths INTEGER, player_kills INTEGER, player_damage_dealt REAL, player_damage_taken REAL, non_player_damage_taken REAL)");
                statement.executeUpdate("CREATE TABLE damage_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, attacker_uuid TEXT, attacker_entity_uuid TEXT, attacker_name TEXT, attacker_entity_type TEXT, victim_uuid TEXT, victim_name TEXT, cause TEXT, damage REAL, weapon TEXT, projectile_uuid TEXT, attacker_location_json TEXT, victim_location_json TEXT)");
                statement.executeUpdate("CREATE TABLE damage_projectile_points (damage_idx INTEGER, point_idx INTEGER, world TEXT, x REAL, y REAL, z REAL, offset_ms INTEGER)");
                statement.executeUpdate("CREATE TABLE deaths (idx INTEGER PRIMARY KEY, offset_ms INTEGER, victim_uuid TEXT, victim_name TEXT, killer_uuid TEXT, killer_name TEXT, killer_entity_type TEXT, cause TEXT, weapon TEXT, location_json TEXT, xp_level INTEGER, inventory_json TEXT, death_message_html TEXT, death_message_plain TEXT)");
                statement.executeUpdate("CREATE TABLE path_points (path_id INTEGER PRIMARY KEY AUTOINCREMENT, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, life_index INTEGER, role TEXT, game_mode TEXT, is_teleport INTEGER, world TEXT, x REAL, y REAL, z REAL, health REAL, max_health REAL, absorption REAL, food INTEGER, saturation REAL, xp_level INTEGER, total_experience INTEGER, full_inventory_snapshot INTEGER)");
                statement.executeUpdate("CREATE TABLE path_point_effects (path_id INTEGER, effect_idx INTEGER, raw_type TEXT, pretty_name TEXT, amplifier INTEGER, duration_ticks INTEGER, ambient INTEGER)");
                statement.executeUpdate("CREATE TABLE path_point_inventory_deltas (path_id INTEGER, slot INTEGER, removed INTEGER, raw_id TEXT, pretty_name TEXT, amount INTEGER, enchanted INTEGER, text_color_hex TEXT, serialized_item TEXT)");
                statement.executeUpdate("CREATE TABLE milestones (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, key TEXT, description TEXT, raw_name TEXT, color_hex TEXT)");
                statement.executeUpdate("CREATE TABLE chat (idx INTEGER PRIMARY KEY, offset_ms INTEGER, kind TEXT, player_uuid TEXT, player_name TEXT, html TEXT, plain_text TEXT)");
                statement.executeUpdate("CREATE TABLE projectiles (projectile_uuid TEXT PRIMARY KEY, shooter_uuid TEXT, shooter_name TEXT, shooter_entity_type TEXT, type TEXT, kind TEXT, color_hex TEXT, launched_at_offset_ms INTEGER, ended_at_offset_ms INTEGER)");
                statement.executeUpdate("CREATE TABLE projectile_points (projectile_uuid TEXT, point_idx INTEGER, world TEXT, x REAL, y REAL, z REAL, offset_ms INTEGER)");
                statement.executeUpdate("CREATE TABLE mobs (entity_uuid TEXT PRIMARY KEY, entity_type TEXT, target_player_uuid TEXT, target_player_name TEXT, color_hex TEXT, started_at_offset_ms INTEGER, ended_at_offset_ms INTEGER)");
                statement.executeUpdate("CREATE TABLE mob_points (entity_uuid TEXT, point_idx INTEGER, world TEXT, x REAL, y REAL, z REAL, offset_ms INTEGER)");
                statement.executeUpdate("CREATE TABLE map_markers (marker_uuid TEXT PRIMARY KEY, offset_ms INTEGER, ended_at_offset_ms INTEGER, kind TEXT, player_uuid TEXT, player_name TEXT, world TEXT, x REAL, y REAL, z REAL, label TEXT, description TEXT, color_hex TEXT)");
                statement.executeUpdate("CREATE TABLE dragon_samples (idx INTEGER PRIMARY KEY, offset_ms INTEGER, world TEXT, x REAL, y REAL, z REAL, health REAL, max_health REAL)");
                statement.executeUpdate("CREATE TABLE end_crystals (crystal_uuid TEXT PRIMARY KEY, world TEXT, x REAL, y REAL, z REAL, spawned_at_offset_ms INTEGER, destroyed_at_offset_ms INTEGER)");
                statement.executeUpdate("CREATE TABLE food_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, raw_name TEXT, pretty_name TEXT, color_hex TEXT, health REAL, absorption REAL, food INTEGER, saturation REAL)");
                statement.executeUpdate("CREATE TABLE effect_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, action TEXT, raw_name TEXT, pretty_name TEXT, amplifier INTEGER, duration_ticks INTEGER, cause TEXT, source_name TEXT, color_hex TEXT)");
                statement.executeUpdate("CREATE TABLE totem_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, location_json TEXT, color_hex TEXT)");
                statement.executeUpdate("CREATE TABLE block_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, attacker_name TEXT, raw_name TEXT, blocked_damage REAL, location_json TEXT, color_hex TEXT)");
                statement.executeUpdate("CREATE TABLE timeline (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, kind TEXT, description TEXT, raw_name TEXT, color_hex TEXT)");
            }
            writeSnapshotTables(connection, snapshot);
            connection.commit();
        } catch (Exception exception) {
            throw new IOException("Unable to write sqlite report", exception);
        }
    }

    private void writeSnapshotTables(Connection connection, ViewerSnapshot snapshot) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO session_metadata VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            SessionMetadata m = snapshot.metadata();
            ps.setString(1, m.reportId().toString());
            ps.setString(2, m.runnerUuid() == null ? null : m.runnerUuid().toString());
            ps.setString(3, m.runnerName());
            ps.setLong(4, m.startedAtEpochMillis());
            ps.setLong(5, m.endedAtEpochMillis());
            ps.setString(6, m.outcome());
            ps.setString(7, m.keepInventoryMode());
            ps.setString(8, m.activeKitId());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO participants VALUES (?, ?, ?, ?, ?, ?)"); PreparedStatement st = connection.prepareStatement("INSERT INTO participant_stats VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Participant participant : snapshot.participants()) {
                ps.setString(1, participant.uuid().toString());
                ps.setString(2, participant.name());
                ps.setString(3, participant.role());
                ps.setString(4, participant.colorHex());
                ps.setInt(5, participant.joinedLate() ? 1 : 0);
                ps.setInt(6, participant.spectatorOnly() ? 1 : 0);
                ps.addBatch();
            }
            for (ParticipantStats stats : snapshot.stats()) {
                st.setString(1, stats.uuid().toString());
                st.setInt(2, stats.deaths());
                st.setInt(3, stats.playerKills());
                st.setDouble(4, stats.playerDamageDealt());
                st.setDouble(5, stats.playerDamageTaken());
                st.setDouble(6, stats.nonPlayerDamageTaken());
                st.addBatch();
            }
            ps.executeBatch();
            st.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO damage_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"); PreparedStatement psp = connection.prepareStatement("INSERT INTO damage_projectile_points VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (DamageRecord record : snapshot.damage()) {
                ps.setInt(1, idx);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.attackerUuid()));
                ps.setString(4, toString(record.attackerEntityUuid()));
                ps.setString(5, record.attackerName());
                ps.setString(6, record.attackerEntityType());
                ps.setString(7, toString(record.victimUuid()));
                ps.setString(8, record.victimName());
                ps.setString(9, record.cause());
                ps.setDouble(10, record.damage());
                ps.setString(11, record.weapon());
                ps.setString(12, toString(record.projectileUuid()));
                ps.setString(13, gson.toJson(record.attackerLocation()));
                ps.setString(14, gson.toJson(record.victimLocation()));
                ps.addBatch();
                int pidx = 0;
                for (SimplePoint point : record.projectilePath()) {
                    psp.setInt(1, idx);
                    psp.setInt(2, pidx++);
                    psp.setString(3, point.world());
                    psp.setDouble(4, point.x());
                    psp.setDouble(5, point.y());
                    psp.setDouble(6, point.z());
                    psp.setLong(7, point.offsetMillis());
                    psp.addBatch();
                }
                idx++;
            }
            ps.executeBatch();
            psp.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO deaths VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (DeathRecord record : snapshot.deaths()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.victimUuid()));
                ps.setString(4, record.victimName());
                ps.setString(5, toString(record.killerUuid()));
                ps.setString(6, record.killerName());
                ps.setString(7, record.killerEntityType());
                ps.setString(8, record.cause());
                ps.setString(9, record.weapon());
                ps.setString(10, gson.toJson(record.location()));
                ps.setInt(11, record.xpLevel());
                ps.setString(12, gson.toJson(record.inventory()));
                ps.setString(13, record.deathMessageHtml());
                ps.setString(14, record.deathMessagePlain());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        writePaths(connection, snapshot.paths());
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO milestones VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (MilestoneRecord record : snapshot.milestones()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.playerUuid()));
                ps.setString(4, record.playerName());
                ps.setString(5, record.key());
                ps.setString(6, record.description());
                ps.setString(7, record.rawName());
                ps.setString(8, record.colorHex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO chat VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (ChatRecord record : snapshot.chat()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, record.kind());
                ps.setString(4, toString(record.playerUuid()));
                ps.setString(5, record.playerName());
                ps.setString(6, record.html());
                ps.setString(7, record.plainText());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO food_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (FoodRecord record : snapshot.food()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.playerUuid()));
                ps.setString(4, record.playerName());
                ps.setString(5, record.rawName());
                ps.setString(6, record.prettyName());
                ps.setString(7, record.colorHex());
                ps.setFloat(8, record.health());
                ps.setFloat(9, record.absorption());
                ps.setInt(10, record.food());
                ps.setFloat(11, record.saturation());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO effect_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (EffectRecord record : snapshot.effects()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.playerUuid()));
                ps.setString(4, record.playerName());
                ps.setString(5, record.action());
                ps.setString(6, record.rawName());
                ps.setString(7, record.prettyName());
                ps.setInt(8, record.amplifier());
                ps.setInt(9, record.durationTicks());
                ps.setString(10, record.cause());
                ps.setString(11, record.sourceName());
                ps.setString(12, record.colorHex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO totem_events VALUES (?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (TotemRecord record : snapshot.totems()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.playerUuid()));
                ps.setString(4, record.playerName());
                ps.setString(5, gson.toJson(record.location()));
                ps.setString(6, record.colorHex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO block_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (BlockRecord record : snapshot.blocks()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.playerUuid()));
                ps.setString(4, record.playerName());
                ps.setString(5, record.attackerName());
                ps.setString(6, record.rawName());
                ps.setDouble(7, record.blockedDamage());
                ps.setString(8, gson.toJson(record.location()));
                ps.setString(9, record.colorHex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO projectiles VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"); PreparedStatement points = connection.prepareStatement("INSERT INTO projectile_points VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (ProjectileRecord record : snapshot.projectiles()) {
                ps.setString(1, record.projectileUuid().toString());
                ps.setString(2, toString(record.shooterUuid()));
                ps.setString(3, record.shooterName());
                ps.setString(4, record.shooterEntityType());
                ps.setString(5, record.type());
                ps.setString(6, record.kind());
                ps.setString(7, record.colorHex());
                ps.setLong(8, record.launchedAtOffsetMillis());
                if (record.endedAtOffsetMillis() == null) ps.setNull(9, java.sql.Types.BIGINT); else ps.setLong(9, record.endedAtOffsetMillis());
                ps.addBatch();
                int pidx = 0;
                for (SimplePoint point : record.points()) {
                    points.setString(1, record.projectileUuid().toString());
                    points.setInt(2, pidx++);
                    points.setString(3, point.world());
                    points.setDouble(4, point.x());
                    points.setDouble(5, point.y());
                    points.setDouble(6, point.z());
                    points.setLong(7, point.offsetMillis());
                    points.addBatch();
                }
            }
            ps.executeBatch();
            points.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO mobs VALUES (?, ?, ?, ?, ?, ?, ?)"); PreparedStatement points = connection.prepareStatement("INSERT INTO mob_points VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (MobTrackRecord record : snapshot.mobs()) {
                ps.setString(1, record.entityUuid().toString());
                ps.setString(2, record.entityType());
                ps.setString(3, toString(record.targetPlayerUuid()));
                ps.setString(4, record.targetPlayerName());
                ps.setString(5, record.colorHex());
                ps.setLong(6, record.startedAtOffsetMillis());
                if (record.endedAtOffsetMillis() == null) ps.setNull(7, java.sql.Types.BIGINT); else ps.setLong(7, record.endedAtOffsetMillis());
                ps.addBatch();
                int pidx = 0;
                for (SimplePoint point : record.points()) {
                    points.setString(1, record.entityUuid().toString());
                    points.setInt(2, pidx++);
                    points.setString(3, point.world());
                    points.setDouble(4, point.x());
                    points.setDouble(5, point.y());
                    points.setDouble(6, point.z());
                    points.setLong(7, point.offsetMillis());
                    points.addBatch();
                }
            }
            ps.executeBatch();
            points.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO map_markers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (MapMarker record : snapshot.markers()) {
                ps.setString(1, record.markerUuid().toString());
                ps.setLong(2, record.offsetMillis());
                if (record.endedAtOffsetMillis() == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, record.endedAtOffsetMillis());
                ps.setString(4, record.kind());
                ps.setString(5, toString(record.playerUuid()));
                ps.setString(6, record.playerName());
                ps.setString(7, record.world());
                ps.setDouble(8, record.x());
                ps.setDouble(9, record.y());
                ps.setDouble(10, record.z());
                ps.setString(11, record.label());
                ps.setString(12, record.description());
                ps.setString(13, record.colorHex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO dragon_samples VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (DragonSample record : snapshot.dragon()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, record.world());
                ps.setDouble(4, record.x());
                ps.setDouble(5, record.y());
                ps.setDouble(6, record.z());
                ps.setFloat(7, record.health());
                ps.setFloat(8, record.maxHealth());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO end_crystals VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (EndCrystalRecord record : snapshot.endCrystals()) {
                ps.setString(1, record.crystalUuid().toString());
                ps.setString(2, record.world());
                ps.setDouble(3, record.x());
                ps.setDouble(4, record.y());
                ps.setDouble(5, record.z());
                ps.setLong(6, record.spawnedAtOffsetMillis());
                if (record.destroyedAtOffsetMillis() == null) ps.setNull(7, java.sql.Types.BIGINT); else ps.setLong(7, record.destroyedAtOffsetMillis());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO timeline VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (TimelineRecord record : snapshot.timeline()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.playerUuid()));
                ps.setString(4, record.playerName());
                ps.setString(5, record.kind());
                ps.setString(6, record.description());
                ps.setString(7, record.rawName());
                ps.setString(8, record.colorHex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Store one fully-materialized path stream as normalized rows. The first point of each life
     * writes a complete inventory snapshot; later points only write changed slots. That keeps the
     * viewer data rich without exploding SQLite size for long matches.
     */
    private void writePaths(Connection connection, List<PathPoint> paths) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO path_points (offset_ms, player_uuid, player_name, life_index, role, game_mode, is_teleport, world, x, y, z, health, max_health, absorption, food, saturation, xp_level, total_experience, full_inventory_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
             PreparedStatement effects = connection.prepareStatement("INSERT INTO path_point_effects VALUES (?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement inventory = connection.prepareStatement("INSERT INTO path_point_inventory_deltas VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            Map<String, Map<Integer, InventoryItem>> lastInventoryByLife = new HashMap<>();
            for (PathPoint point : paths) {
                String lifeKey = point.playerUuid() + ":" + point.lifeIndex();
                Map<Integer, InventoryItem> previous = lastInventoryByLife.get(lifeKey);
                boolean fullSnapshot = previous == null;
                ps.setLong(1, point.offsetMillis());
                ps.setString(2, point.playerUuid().toString());
                ps.setString(3, point.playerName());
                ps.setInt(4, point.lifeIndex());
                ps.setString(5, point.role());
                ps.setString(6, point.gameMode());
                ps.setInt(7, point.isTeleport() ? 1 : 0);
                ps.setString(8, point.world());
                ps.setDouble(9, point.x());
                ps.setDouble(10, point.y());
                ps.setDouble(11, point.z());
                ps.setFloat(12, point.health());
                ps.setFloat(13, point.maxHealth());
                ps.setFloat(14, point.absorption());
                ps.setInt(15, point.food());
                ps.setFloat(16, point.saturation());
                ps.setInt(17, point.xpLevel());
                ps.setInt(18, point.totalExperience());
                ps.setInt(19, fullSnapshot ? 1 : 0);
                ps.executeUpdate();
                long pathId;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    pathId = keys.getLong(1);
                }
                int eidx = 0;
                for (EffectState effect : point.effects()) {
                    effects.setLong(1, pathId);
                    effects.setInt(2, eidx++);
                    effects.setString(3, effect.rawType());
                    effects.setString(4, effect.prettyName());
                    effects.setInt(5, effect.amplifier());
                    effects.setInt(6, effect.durationTicks());
                    effects.setInt(7, effect.ambient() ? 1 : 0);
                    effects.addBatch();
                }
                Map<Integer, InventoryItem> current = new HashMap<>();
                for (InventoryItem item : point.inventory()) {
                    current.put(item.slot(), item);
                }
                if (fullSnapshot) {
                    for (InventoryItem item : point.inventory()) {
                        bindInventoryDelta(inventory, pathId, item.slot(), false, item);
                        inventory.addBatch();
                    }
                } else {
                    for (InventoryItem item : point.inventory()) {
                        InventoryItem last = previous.get(item.slot());
                        if (!inventoryEquals(last, item)) {
                            bindInventoryDelta(inventory, pathId, item.slot(), false, item);
                            inventory.addBatch();
                        }
                    }
                    for (Integer removedSlot : previous.keySet()) {
                        if (!current.containsKey(removedSlot)) {
                            bindInventoryDelta(inventory, pathId, removedSlot, true, null);
                            inventory.addBatch();
                        }
                    }
                }
                lastInventoryByLife.put(lifeKey, current);
            }
            effects.executeBatch();
            inventory.executeBatch();
        }
    }

    private void bindInventoryDelta(PreparedStatement inventory, long pathId, int slot, boolean removed, InventoryItem item) throws Exception {
        inventory.setLong(1, pathId);
        inventory.setInt(2, slot);
        inventory.setInt(3, removed ? 1 : 0);
        if (item == null) {
            inventory.setString(4, null);
            inventory.setString(5, null);
            inventory.setNull(6, java.sql.Types.INTEGER);
            inventory.setNull(7, java.sql.Types.INTEGER);
            inventory.setString(8, null);
            inventory.setString(9, null);
        } else {
            inventory.setString(4, item.rawId());
            inventory.setString(5, item.prettyName());
            inventory.setInt(6, item.amount());
            inventory.setInt(7, item.enchanted() ? 1 : 0);
            inventory.setString(8, item.textColorHex());
            inventory.setString(9, item.serializedItem());
        }
    }

    private boolean inventoryEquals(InventoryItem a, InventoryItem b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.slot() == b.slot()
                && Objects.equals(a.rawId(), b.rawId())
                && Objects.equals(a.prettyName(), b.prettyName())
                && a.amount() == b.amount()
                && a.enchanted() == b.enchanted()
                && Objects.equals(a.textColorHex(), b.textColorHex())
                && Objects.equals(a.serializedItem(), b.serializedItem());
    }

    private ViewerSnapshot readFromSqlite(Path sqliteFile) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            SessionMetadata metadata;
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM session_metadata LIMIT 1")) {
                if (!rs.next()) {
                    throw new IOException("Report database is missing session metadata");
                }
                metadata = new SessionMetadata(
                        UUID.fromString(rs.getString("report_id")),
                        uuid(rs.getString("runner_uuid")),
                        rs.getString("runner_name"),
                        rs.getLong("started_at"),
                        rs.getLong("ended_at"),
                        rs.getString("outcome"),
                        rs.getString("keep_inventory_mode"),
                        rs.getString("active_kit_id")
                );
            }
            List<Participant> participants = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM participants ORDER BY rowid")) {
                while (rs.next()) {
                    participants.add(new Participant(uuid(rs.getString("uuid")), rs.getString("name"), rs.getString("role"), rs.getString("color_hex"), rs.getInt("joined_late") != 0, rs.getInt("spectator_only") != 0));
                }
            }
            List<ParticipantStats> stats = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM participant_stats ORDER BY rowid")) {
                while (rs.next()) {
                    stats.add(new ParticipantStats(uuid(rs.getString("uuid")), rs.getInt("deaths"), rs.getInt("player_kills"), rs.getDouble("player_damage_dealt"), rs.getDouble("player_damage_taken"), rs.getDouble("non_player_damage_taken")));
                }
            }
            List<DamageRecord> damage = readDamage(connection);
            List<DeathRecord> deaths = readDeaths(connection);
            List<PathPoint> paths = readPaths(connection);
            List<MilestoneRecord> milestones = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM milestones ORDER BY idx")) {
                while (rs.next()) {
                    milestones.add(new MilestoneRecord(rs.getLong("offset_ms"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("key"), rs.getString("description"), rs.getString("raw_name"), rs.getString("color_hex")));
                }
            }
            List<ChatRecord> chat = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM chat ORDER BY idx")) {
                while (rs.next()) {
                    chat.add(new ChatRecord(rs.getLong("offset_ms"), rs.getString("kind"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("html"), rs.getString("plain_text")));
                }
            }
            List<ProjectileRecord> projectiles = readProjectiles(connection);
            List<MobTrackRecord> mobs = readMobs(connection);
            List<MapMarker> markers = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM map_markers ORDER BY offset_ms, rowid")) {
                while (rs.next()) {
                    Long ended = rs.getObject("ended_at_offset_ms") == null ? null : rs.getLong("ended_at_offset_ms");
                    markers.add(new MapMarker(UUID.fromString(rs.getString("marker_uuid")), rs.getLong("offset_ms"), ended, rs.getString("kind"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getString("label"), rs.getString("description"), rs.getString("color_hex")));
                }
            }
            List<DragonSample> dragon = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM dragon_samples ORDER BY idx")) {
                while (rs.next()) {
                    dragon.add(new DragonSample(rs.getLong("offset_ms"), rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("health"), rs.getFloat("max_health")));
                }
            }
            List<EndCrystalRecord> endCrystals = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM end_crystals ORDER BY spawned_at_offset_ms, rowid")) {
                while (rs.next()) {
                    Long destroyed = rs.getObject("destroyed_at_offset_ms") == null ? null : rs.getLong("destroyed_at_offset_ms");
                    endCrystals.add(new EndCrystalRecord(UUID.fromString(rs.getString("crystal_uuid")), rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getLong("spawned_at_offset_ms"), destroyed));
                }
            }
            List<FoodRecord> food = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM food_events ORDER BY idx")) {
                while (rs.next()) {
                    food.add(new FoodRecord(rs.getLong("offset_ms"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("raw_name"), rs.getString("pretty_name"), rs.getString("color_hex"), rs.getFloat("health"), rs.getFloat("absorption"), rs.getInt("food"), rs.getFloat("saturation")));
                }
            }
            List<EffectRecord> effects = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM effect_events ORDER BY idx")) {
                while (rs.next()) {
                    effects.add(new EffectRecord(rs.getLong("offset_ms"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("action"), rs.getString("raw_name"), rs.getString("pretty_name"), rs.getInt("amplifier"), rs.getInt("duration_ticks"), rs.getString("cause"), rs.getString("source_name"), rs.getString("color_hex")));
                }
            }
            List<TotemRecord> totems = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM totem_events ORDER BY idx")) {
                while (rs.next()) {
                    totems.add(new TotemRecord(rs.getLong("offset_ms"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), gson.fromJson(rs.getString("location_json"), ReportModels.LocationRecord.class), rs.getString("color_hex")));
                }
            }
            List<BlockRecord> blocks = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM block_events ORDER BY idx")) {
                while (rs.next()) {
                    blocks.add(new BlockRecord(rs.getLong("offset_ms"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("attacker_name"), rs.getString("raw_name"), rs.getDouble("blocked_damage"), gson.fromJson(rs.getString("location_json"), ReportModels.LocationRecord.class), rs.getString("color_hex")));
                }
            }
            List<TimelineRecord> timeline = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM timeline ORDER BY idx")) {
                while (rs.next()) {
                    timeline.add(new TimelineRecord(rs.getLong("offset_ms"), uuid(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("kind"), rs.getString("description"), rs.getString("raw_name"), rs.getString("color_hex")));
                }
            }
            return new ViewerSnapshot(metadata, participants, stats, damage, deaths, paths, milestones, chat, projectiles, mobs, markers, dragon, endCrystals, food, effects, totems, blocks, timeline);
        } catch (Exception exception) {
            throw new IOException("Unable to read sqlite report", exception);
        }
    }

    private List<DamageRecord> readDamage(Connection connection) throws Exception {
        Map<Integer, List<SimplePoint>> pointsByDamage = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM damage_projectile_points ORDER BY damage_idx, point_idx")) {
            while (rs.next()) {
                pointsByDamage.computeIfAbsent(rs.getInt("damage_idx"), ignored -> new ArrayList<>()).add(new SimplePoint(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getLong("offset_ms")));
            }
        }
        List<DamageRecord> records = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM damage_events ORDER BY idx")) {
            while (rs.next()) {
                int idx = rs.getInt("idx");
                records.add(new DamageRecord(rs.getLong("offset_ms"), uuid(rs.getString("attacker_uuid")), uuid(rs.getString("attacker_entity_uuid")), rs.getString("attacker_name"), rs.getString("attacker_entity_type"), uuid(rs.getString("victim_uuid")), rs.getString("victim_name"), rs.getString("cause"), rs.getDouble("damage"), rs.getString("weapon"), uuid(rs.getString("projectile_uuid")), pointsByDamage.getOrDefault(idx, List.of()), gson.fromJson(rs.getString("attacker_location_json"), ReportModels.LocationRecord.class), gson.fromJson(rs.getString("victim_location_json"), ReportModels.LocationRecord.class)));
            }
        }
        return records;
    }

    private List<DeathRecord> readDeaths(Connection connection) throws Exception {
        List<DeathRecord> records = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM deaths ORDER BY idx")) {
            while (rs.next()) {
                java.lang.reflect.Type inventoryType = new com.google.gson.reflect.TypeToken<List<InventoryItem>>() {}.getType();
                List<InventoryItem> inventory = gson.fromJson(rs.getString("inventory_json"), inventoryType);
                records.add(new DeathRecord(rs.getLong("offset_ms"), uuid(rs.getString("victim_uuid")), rs.getString("victim_name"), uuid(rs.getString("killer_uuid")), rs.getString("killer_name"), rs.getString("killer_entity_type"), rs.getString("cause"), rs.getString("weapon"), gson.fromJson(rs.getString("location_json"), ReportModels.LocationRecord.class), rs.getInt("xp_level"), inventory == null ? List.of() : inventory, rs.getString("death_message_html"), rs.getString("death_message_plain")));
            }
        }
        return records;
    }

    private List<PathPoint> readPaths(Connection connection) throws Exception {
        Map<Long, List<EffectState>> effectsByPath = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM path_point_effects ORDER BY path_id, effect_idx")) {
            while (rs.next()) {
                long pathId = rs.getLong("path_id");
                effectsByPath.computeIfAbsent(pathId, ignored -> new ArrayList<>()).add(new EffectState(rs.getString("raw_type"), rs.getString("pretty_name"), rs.getInt("amplifier"), rs.getInt("duration_ticks"), rs.getInt("ambient") != 0));
            }
        }
        Map<Long, List<InventoryDeltaRow>> inventoryByPath = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM path_point_inventory_deltas ORDER BY path_id, slot")) {
            while (rs.next()) {
                long pathId = rs.getLong("path_id");
                inventoryByPath.computeIfAbsent(pathId, ignored -> new ArrayList<>()).add(new InventoryDeltaRow(rs.getInt("slot"), rs.getInt("removed") != 0, rs.getString("raw_id"), rs.getString("pretty_name"), rs.getObject("amount") == null ? 0 : rs.getInt("amount"), rs.getObject("enchanted") != null && rs.getInt("enchanted") != 0, rs.getString("text_color_hex"), rs.getString("serialized_item")));
            }
        }
        // Rebuild each life's inventory by replaying slot deltas in timestamp order.
        Map<String, Map<Integer, InventoryItem>> inventoryStateByLife = new HashMap<>();
        List<PathPoint> paths = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM path_points ORDER BY offset_ms, path_id")) {
            while (rs.next()) {
                long pathId = rs.getLong("path_id");
                UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                int lifeIndex = rs.getInt("life_index");
                String lifeKey = playerUuid + ":" + lifeIndex;
                Map<Integer, InventoryItem> state = inventoryStateByLife.computeIfAbsent(lifeKey, ignored -> new HashMap<>());
                if (rs.getInt("full_inventory_snapshot") != 0) {
                    state.clear();
                }
                for (InventoryDeltaRow delta : inventoryByPath.getOrDefault(pathId, List.of())) {
                    if (delta.removed()) {
                        state.remove(delta.slot());
                    } else {
                        state.put(delta.slot(), new InventoryItem(delta.slot(), delta.rawId(), delta.prettyName(), delta.amount(), delta.enchanted(), delta.textColorHex(), delta.serializedItem()));
                    }
                }
                List<InventoryItem> inventory = state.values().stream().sorted(Comparator.comparingInt(InventoryItem::slot)).toList();
                paths.add(new PathPoint(
                        rs.getLong("offset_ms"),
                        playerUuid,
                        rs.getString("player_name"),
                        lifeIndex,
                        rs.getString("role"),
                        rs.getString("game_mode"),
                        rs.getInt("is_teleport") != 0,
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("health"),
                        rs.getFloat("max_health"),
                        rs.getFloat("absorption"),
                        rs.getInt("food"),
                        rs.getFloat("saturation"),
                        rs.getInt("xp_level"),
                        rs.getInt("total_experience"),
                        inventory,
                        effectsByPath.getOrDefault(pathId, List.of())
                ));
            }
        }
        return paths;
    }

    private List<ProjectileRecord> readProjectiles(Connection connection) throws Exception {
        Map<UUID, List<SimplePoint>> pointsByProjectile = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM projectile_points ORDER BY projectile_uuid, point_idx")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("projectile_uuid"));
                pointsByProjectile.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(new SimplePoint(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getLong("offset_ms")));
            }
        }
        List<ProjectileRecord> projectiles = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM projectiles ORDER BY launched_at_offset_ms, rowid")) {
            while (rs.next()) {
                UUID projectileUuid = UUID.fromString(rs.getString("projectile_uuid"));
                Long ended = rs.getObject("ended_at_offset_ms") == null ? null : rs.getLong("ended_at_offset_ms");
                projectiles.add(new ProjectileRecord(projectileUuid, uuid(rs.getString("shooter_uuid")), rs.getString("shooter_name"), rs.getString("shooter_entity_type"), rs.getString("type"), rs.getString("kind"), rs.getString("color_hex"), rs.getLong("launched_at_offset_ms"), ended, pointsByProjectile.getOrDefault(projectileUuid, List.of())));
            }
        }
        return projectiles;
    }

    private List<MobTrackRecord> readMobs(Connection connection) throws Exception {
        Map<UUID, List<SimplePoint>> pointsByMob = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM mob_points ORDER BY entity_uuid, point_idx")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("entity_uuid"));
                pointsByMob.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(new SimplePoint(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getLong("offset_ms")));
            }
        }
        List<MobTrackRecord> mobs = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM mobs ORDER BY started_at_offset_ms, rowid")) {
            while (rs.next()) {
                UUID entityUuid = UUID.fromString(rs.getString("entity_uuid"));
                Long ended = rs.getObject("ended_at_offset_ms") == null ? null : rs.getLong("ended_at_offset_ms");
                mobs.add(new MobTrackRecord(entityUuid, rs.getString("entity_type"), uuid(rs.getString("target_player_uuid")), rs.getString("target_player_name"), rs.getString("color_hex"), rs.getLong("started_at_offset_ms"), ended, pointsByMob.getOrDefault(entityUuid, List.of())));
            }
        }
        return mobs;
    }

    private UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private String toString(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException io) {
                throw io;
            }
            throw exception;
        }
    }

    public record ParticipantSeed(UUID uuid, String name, String role, boolean joinedLate, boolean spectatorOnly) {}

    public record FinishResult(IndexEntry indexEntry, ViewerSnapshot snapshot) {}

    private record InventoryDeltaRow(int slot, boolean removed, String rawId, String prettyName, int amount, boolean enchanted, String textColorHex, String serializedItem) {}

    private static final class CurrentSession {
        private final UUID reportId;
        private final long startedAtEpochMillis;
        private final UUID runnerUuid;
        private final String runnerName;
        private KeepInventoryMode keepInventoryMode;
        private String activeKitId;
        private final Map<UUID, Participant> participants = new LinkedHashMap<>();
        private final Map<UUID, MutableStats> stats = new LinkedHashMap<>();
        private final List<DamageRecord> damage = new ArrayList<>();
        private final List<DeathRecord> deaths = new ArrayList<>();
        private final List<PathPoint> paths = new ArrayList<>();
        private final List<MilestoneRecord> milestones = new ArrayList<>();
        private final List<ChatRecord> chat = new ArrayList<>();
        private final Map<UUID, TrackedProjectile> projectiles = new LinkedHashMap<>();
        private final Map<UUID, TrackedMob> mobs = new LinkedHashMap<>();
        private final List<MapMarker> markers = new ArrayList<>();
        private final Map<String, MapMarker> spawnMarkers = new HashMap<>();
        private final List<DragonSample> dragon = new ArrayList<>();
        private final Map<UUID, MutableCrystal> endCrystals = new LinkedHashMap<>();
        private final List<FoodRecord> food = new ArrayList<>();
        private final List<EffectRecord> effects = new ArrayList<>();
        private final List<TotemRecord> totems = new ArrayList<>();
        private final List<BlockRecord> blocks = new ArrayList<>();
        private final List<TimelineRecord> timeline = new ArrayList<>();

        private CurrentSession(UUID reportId, long startedAtEpochMillis, UUID runnerUuid, String runnerName, KeepInventoryMode keepInventoryMode, String activeKitId) {
            this.reportId = reportId;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.runnerUuid = runnerUuid;
            this.runnerName = runnerName;
            this.keepInventoryMode = keepInventoryMode;
            this.activeKitId = activeKitId;
        }
    }

    private static final class MutableStats {
        private int deaths;
        private int playerKills;
        private double playerDamageDealt;
        private double playerDamageTaken;
        private double nonPlayerDamageTaken;

        private ParticipantStats toImmutable(UUID uuid) {
            return new ParticipantStats(uuid, deaths, playerKills, playerDamageDealt, playerDamageTaken, nonPlayerDamageTaken);
        }
    }

    private static final class TrackedProjectile {
        private final UUID projectileUuid;
        private final UUID shooterUuid;
        private final String shooterName;
        private final String shooterEntityType;
        private final String type;
        private final String kind;
        private final String colorHex;
        private final long launchedAtOffsetMillis;
        private Long endedAtOffsetMillis;
        private final List<SimplePoint> points = new ArrayList<>();

        private TrackedProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String shooterEntityType, String type, String kind, String colorHex, long launchedAtOffsetMillis) {
            this.projectileUuid = projectileUuid;
            this.shooterUuid = shooterUuid;
            this.shooterName = shooterName;
            this.shooterEntityType = shooterEntityType;
            this.type = type;
            this.kind = kind;
            this.colorHex = colorHex;
            this.launchedAtOffsetMillis = launchedAtOffsetMillis;
        }

        private static TrackedProjectile empty() {
            return new TrackedProjectile(new UUID(0L, 0L), null, null, null, "unknown", "player", null, 0L);
        }

        private void addPoint(Location location, long offsetMillis) {
            if (location == null || location.getWorld() == null) {
                return;
            }
            SimplePoint point = LocationUtil.toSimplePoint(location, offsetMillis);
            if (points.isEmpty()) {
                points.add(point);
                return;
            }
            SimplePoint last = points.get(points.size() - 1);
            if (!last.world().equals(point.world())
                    || Math.abs(last.x() - point.x()) > 0.1
                    || Math.abs(last.y() - point.y()) > 0.1
                    || Math.abs(last.z() - point.z()) > 0.1) {
                points.add(point);
            }
        }

        private void end(long offsetMillis) {
            this.endedAtOffsetMillis = offsetMillis;
        }

        private List<SimplePoint> snapshotPoints() {
            return List.copyOf(points);
        }

        private ProjectileRecord toRecord() {
            return new ProjectileRecord(projectileUuid, shooterUuid, shooterName, shooterEntityType, type, kind, colorHex, launchedAtOffsetMillis, endedAtOffsetMillis, List.copyOf(points));
        }
    }

    private static final class TrackedMob {
        private final UUID entityUuid;
        private final String entityType;
        private UUID targetPlayerUuid;
        private String targetPlayerName;
        private final String colorHex;
        private final long startedAtOffsetMillis;
        private Long endedAtOffsetMillis;
        private final List<SimplePoint> points = new ArrayList<>();

        private TrackedMob(UUID entityUuid, String entityType, UUID targetPlayerUuid, String targetPlayerName, String colorHex, long startedAtOffsetMillis) {
            this.entityUuid = entityUuid;
            this.entityType = entityType;
            this.targetPlayerUuid = targetPlayerUuid;
            this.targetPlayerName = targetPlayerName;
            this.colorHex = colorHex;
            this.startedAtOffsetMillis = startedAtOffsetMillis;
        }

        private void touch(UUID targetPlayerUuid, String targetPlayerName, Location location, long offsetMillis) {
            this.targetPlayerUuid = targetPlayerUuid;
            this.targetPlayerName = targetPlayerName;
            this.endedAtOffsetMillis = null;
            if (location == null || location.getWorld() == null) {
                return;
            }
            SimplePoint point = LocationUtil.toSimplePoint(location, offsetMillis);
            if (points.isEmpty()) {
                points.add(point);
                return;
            }
            SimplePoint last = points.get(points.size() - 1);
            if (!last.world().equals(point.world())
                    || Math.abs(last.x() - point.x()) > 0.5
                    || Math.abs(last.y() - point.y()) > 0.5
                    || Math.abs(last.z() - point.z()) > 0.5) {
                points.add(point);
            }
        }

        private void end(long offsetMillis) {
            this.endedAtOffsetMillis = offsetMillis;
        }

        private MobTrackRecord toRecord() {
            return new MobTrackRecord(entityUuid, entityType, targetPlayerUuid, targetPlayerName, colorHex, startedAtOffsetMillis, endedAtOffsetMillis, List.copyOf(points));
        }
    }

    private static final class MutableCrystal {
        private final UUID crystalUuid;
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final long spawnedAtOffsetMillis;
        private Long destroyedAtOffsetMillis;

        private MutableCrystal(UUID crystalUuid, String world, double x, double y, double z, long spawnedAtOffsetMillis) {
            this.crystalUuid = crystalUuid;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnedAtOffsetMillis = spawnedAtOffsetMillis;
        }

        private EndCrystalRecord toRecord() {
            return new EndCrystalRecord(crystalUuid, world, x, y, z, spawnedAtOffsetMillis, destroyedAtOffsetMillis);
        }
    }
}
