package io.github.ganyuke.peoplehunt.report;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.match.MatchOutcome;
import io.github.ganyuke.peoplehunt.report.ReportModels.*;
import io.github.ganyuke.peoplehunt.util.ExceptionUtil;
import io.github.ganyuke.peoplehunt.util.HtmlUtil;
import io.github.ganyuke.peoplehunt.util.LocationUtil;
import io.github.ganyuke.peoplehunt.util.PrettyNames;
import io.github.ganyuke.peoplehunt.util.Text;
import io.github.ganyuke.peoplehunt.util.ZipUtil;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class ReportService {
    private static final Object SQLITE_DRIVER_LOCK = new Object();
    private static volatile boolean sqliteDriverReady = false;
    private static final String[] PALETTE = {
            "#ff6b6b", "#4ecdc4", "#ffe66d", "#1a535c", "#ff9f1c", "#6a4c93", "#8ac926", "#1982c4",
            "#ff595e", "#8338ec", "#3a86ff", "#ffbe0b"
    };

    private final JavaPlugin plugin;
    private final Path reportsDirectory;
    private final Gson gson;
    private final Logger logger;
    private final Path indexFile;
    private final int pathFlushMaxBufferedPoints;
    private final long pathFlushMaxBufferedMillis;
    private final List<IndexEntry> indexEntries = new ArrayList<>();
    private final ExecutorService writeExecutor;
    private final ExecutorService backgroundExecutor;
    private Consumer<String> runtimeWarningSink = ignored -> {};
    private CurrentSession currentSession;

    public ReportService(JavaPlugin plugin, Path reportsDirectory, Gson gson, Logger logger, int pathFlushMaxBufferedPoints, long pathFlushMaxBufferedMillis) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.reportsDirectory = reportsDirectory;
        this.gson = gson;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.indexFile = reportsDirectory.resolve("index.json");
        this.pathFlushMaxBufferedPoints = Math.max(1, pathFlushMaxBufferedPoints);
        this.pathFlushMaxBufferedMillis = Math.max(1000L, pathFlushMaxBufferedMillis);
        this.writeExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("PeopleHunt-Report-Writer"));
        this.backgroundExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("PeopleHunt-Report-Background"));
    }

    public void shutdown() {
        shutdownExecutor(backgroundExecutor, "report background executor");
        shutdownExecutor(writeExecutor, "report writer executor");
    }

    public void setRuntimeWarningSink(Consumer<String> runtimeWarningSink) {
        this.runtimeWarningSink = runtimeWarningSink == null ? ignored -> {} : runtimeWarningSink;
    }

    private static ThreadFactory namedThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void shutdownExecutor(ExecutorService executor, String description) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while shutting down " + description + '.', exception);
            executor.shutdownNow();
        }
    }

    private void runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    public void verifySqliteRuntime() throws IOException {
        Files.createDirectories(reportsDirectory);
        Path probeFile = Files.createTempFile(reportsDirectory, "sqlite-startup-probe-", ".db");
        try {
            try (Connection connection = openSqliteConnection(probeFile);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE startup_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("INSERT INTO startup_probe (value) VALUES ('ok')");
                try (ResultSet rs = statement.executeQuery("SELECT value FROM startup_probe WHERE id = 1")) {
                    if (!rs.next()) {
                        throw new IOException("SQLite startup probe returned no rows");
                    }
                    String value = rs.getString(1);
                    if (!Objects.equals("ok", value)) {
                        throw new IOException("SQLite startup probe returned unexpected value: " + value);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IOException("SQLite startup probe failed; report storage is not usable", exception);
        } finally {
            Files.deleteIfExists(probeFile);
        }
    }

    public void loadIndex() throws IOException {
        indexEntries.clear();
        Files.createDirectories(reportsDirectory);
        if (Files.exists(indexFile)) {
            try (Reader reader = Files.newBufferedReader(indexFile)) {
                List<IndexEntry> entries = gson.fromJson(reader, TypeToken.getParameterized(List.class, IndexEntry.class).getType());
                if (entries != null) {
                    indexEntries.addAll(entries);
                }
            }
        }
        reconcileIndexWithReportDatabases();
        indexEntries.sort(Comparator.comparingLong(IndexEntry::endedAtEpochMillis).reversed());
        saveIndex();
    }

    public void saveIndex() throws IOException {
        Files.createDirectories(reportsDirectory);
        try (Writer writer = Files.newBufferedWriter(indexFile)) {
            gson.toJson(indexEntries, writer);
        }
    }
    private void reconcileIndexWithReportDatabases() throws IOException {
        Map<UUID, IndexEntry> validEntries = new LinkedHashMap<>();
        for (IndexEntry entry : indexEntries) {
            if (entry != null && entry.reportId() != null && Files.isRegularFile(reportsDirectory.resolve(entry.reportId().toString()).resolve("report.db"))) {
                validEntries.put(entry.reportId(), entry);
            }
        }
        try (var stream = Files.list(reportsDirectory)) {
            stream
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        UUID reportId;
                        try {
                            reportId = UUID.fromString(dir.getFileName().toString());
                        } catch (IllegalArgumentException ignored) {
                            return;
                        }
                        Path sqlite = dir.resolve("report.db");
                        if (!Files.isRegularFile(sqlite) || validEntries.containsKey(reportId)) {
                            return;
                        }
                        try {
                            ViewerSnapshot snapshot = readFromSqlite(sqlite);
                            SessionMetadata metadata = snapshot.metadata();
                            validEntries.put(reportId, new IndexEntry(
                                    reportId,
                                    metadata.startedAtEpochMillis(),
                                    metadata.endedAtEpochMillis(),
                                    metadata.outcome(),
                                    metadata.runnerUuid(),
                                    metadata.runnerName()
                            ));
                        } catch (IOException ignored) {
                            // Keep startup resilient: a half-written or externally copied report should
                            // not keep the plugin from enabling. It also should not remain listed until
                            // it can be read successfully.
                        }
                    });
        }
        indexEntries.clear();
        indexEntries.addAll(validEntries.values());
    }


    public UUID startSession(
            UUID runnerUuid,
            String runnerName,
            KeepInventoryMode keepInventoryMode,
            String activeKitId,
            Collection<ParticipantSeed> participants
    ) throws IOException {
        if (currentSession != null) {
            throw new IllegalStateException("A previous report session is still active or finalizing.");
        }
        UUID reportId = UUID.randomUUID();
        Path reportDirectory = reportsDirectory.resolve(reportId.toString());
        Path sqliteFile = reportDirectory.resolve("report.db");
        try {
            Files.createDirectories(reportDirectory);
            initializeSqliteReport(sqliteFile);
        } catch (IOException exception) {
            try {
                deleteRecursively(reportDirectory);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new IOException("Unable to initialize report database for match " + reportId, exception);
        }

        currentSession = new CurrentSession(
                reportId,
                System.currentTimeMillis(),
                runnerUuid,
                runnerName,
                keepInventoryMode,
                activeKitId,
                sqliteFile
        );
        logger.info("Started report session " + reportId + " for runner " + runnerName + ".");
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

    public boolean flushActiveReportPathsManuallyAsync(Consumer<ManualFlushResult> onSuccess, Consumer<IOException> onFailure) {
        CurrentSession session = currentSession;
        if (session == null || session.finalizationInProgress) {
            return false;
        }

        List<PathPoint> batch = drainPendingPaths(session);
        int bufferedBefore = batch.size();
        boolean wasSuspended = session.autoFlushSuspended;
        boolean recreatedMissingDatabase = !Files.exists(session.sqliteFile);
        if (bufferedBefore == 0 && !recreatedMissingDatabase) {
            runOnMainThread(() -> onSuccess.accept(new ManualFlushResult(
                    session.reportId,
                    0,
                    session.pendingPaths.size(),
                    false,
                    false,
                    wasSuspended && !session.autoFlushSuspended
            )));
            return true;
        }

        session.lastPathFlushAtEpochMillis = System.currentTimeMillis();
        writeExecutor.execute(() -> {
            try {
                boolean reportMayBeIncomplete = false;
                if (recreatedMissingDatabase) {
                    initializeSqliteReport(session.sqliteFile);
                    session.writerState.lastInventoryByLife.clear();
                    reportMayBeIncomplete = true;
                    warnRuntime(
                            "PeopleHunt reporting warning: the active report database for " + session.reportId
                                    + " was missing during a manual flush attempt. A new staging database was created, so this report may be incomplete.",
                            new IOException("Active report database was missing and had to be recreated")
                    );
                }
                if (!batch.isEmpty()) {
                    writePathBatchToSqlite(session, batch, false, false);
                } else {
                    session.autoFlushSuspended = false;
                }
                boolean finalReportMayBeIncomplete = reportMayBeIncomplete;
                runOnMainThread(() -> onSuccess.accept(new ManualFlushResult(
                        session.reportId,
                        batch.size(),
                        session.pendingPaths.size(),
                        recreatedMissingDatabase,
                        finalReportMayBeIncomplete,
                        wasSuspended && !session.autoFlushSuspended
                )));
            } catch (IOException exception) {
                session.autoFlushSuspended = true;
                warnRuntime(
                        "PeopleHunt reporting warning: a manual report flush failed for report " + session.reportId
                                + ". Automatic path flushing remains suspended until storage is working again.",
                        exception
                );
                runOnMainThread(() -> onFailure.accept(exception));
            }
        });
        return true;
    }

    public UUID activeReportId() {
        return currentSession == null ? null : currentSession.reportId;
    }

    public boolean isRunning() {
        return currentSession != null && !currentSession.finalizationInProgress;
    }

    public boolean isFinalizationInProgress() {
        return currentSession != null && currentSession.finalizationInProgress;
    }

    public String colorOfParticipant(UUID uuid) {
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

    public UUID currentReportId() {
        return currentSession == null ? null : currentSession.reportId;
    }

    public void updateSessionSettings(KeepInventoryMode keepInventoryMode, String activeKitId) {
        if (currentSession == null) {
            return;
        }
        currentSession.keepInventoryMode = keepInventoryMode;
        currentSession.activeKitId = activeKitId;
    }

    public void registerParticipant(UUID uuid, String name, String role, boolean joinedLate, boolean spectatorOnly) {
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

    public void recordChat(String kind, UUID playerUuid, String playerName, Component component) {
        if (currentSession == null) {
            return;
        }
        String html = HtmlUtil.componentToHtml(component);
        String plain = Text.plain(component);
        currentSession.chat.add(new ChatRecord(offset(), kind, playerUuid, playerName, html, plain));
    }

    public void recordChatRaw(String kind, UUID playerUuid, String playerName, String html, String plain) {
        if (currentSession == null) {
            return;
        }
        currentSession.chat.add(new ChatRecord(offset(), kind, playerUuid, playerName, html, plain));
    }

    public void recordMilestone(UUID playerUuid, String playerName, String key, String description) {
        recordMilestone(playerUuid, playerName, key, description, null, null);
    }

    public void recordMilestone(UUID playerUuid, String playerName, String key, String description, String rawName, String colorHex) {
        if (currentSession == null) {
            return;
        }
        String resolvedColor = defaultColor(playerUuid, colorHex);
        currentSession.milestones.add(new MilestoneRecord(offset(), playerUuid, playerName, key, description, rawName, resolvedColor));
        currentSession.timeline.add(new TimelineRecord(offset(), playerUuid, playerName, "milestone", description, rawName, resolvedColor));
    }

    public void recordTimeline(UUID playerUuid, String playerName, String kind, String description) {
        recordTimeline(playerUuid, playerName, kind, description, null, null);
    }

    public void recordTimeline(UUID playerUuid, String playerName, String kind, String description, String rawName, String colorHex) {
        if (currentSession == null) {
            return;
        }
        currentSession.timeline.add(new TimelineRecord(offset(), playerUuid, playerName, kind, description, rawName, defaultColor(playerUuid, colorHex)));
    }

    public void recordFood(UUID playerUuid, String playerName, String rawName, String prettyName, String colorHex,
                                        float health, float absorption, int food, float saturation) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String resolvedColor = defaultColor(playerUuid, colorHex);
        currentSession.food.add(new FoodRecord(now, playerUuid, playerName, rawName, prettyName, resolvedColor, health, absorption, food, saturation));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "food", "ate " + prettyName, rawName, resolvedColor));
    }

    public void recordEffect(UUID playerUuid, String playerName, String action, String rawName, String prettyName,
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

    public void recordTotem(UUID playerUuid, String playerName, Location location, String colorHex) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String resolvedColor = defaultColor(playerUuid, colorHex == null ? "#ffff55" : colorHex);
        currentSession.totems.add(new TotemRecord(now, playerUuid, playerName, LocationUtil.toRecord(location), resolvedColor));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "totem", "Totem of Undying activated", "minecraft:totem_of_undying", resolvedColor));
    }

    public void recordBlock(UUID playerUuid, String playerName, String attackerName, String rawName, double blockedDamage, Location location, String colorHex) {
        if (currentSession == null) {
            return;
        }
        long now = offset();
        String resolvedColor = defaultColor(playerUuid, colorHex);
        currentSession.blocks.add(new BlockRecord(now, playerUuid, playerName, attackerName, rawName, blockedDamage, LocationUtil.toRecord(location), resolvedColor));
        currentSession.timeline.add(new TimelineRecord(now, playerUuid, playerName, "shield", "blocked %.1f damage from %s".formatted(blockedDamage, attackerName), rawName, resolvedColor));
    }

    public void recordMarker(String kind, UUID playerUuid, String playerName, Location location, String label, String description, String colorHex) {
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

    public void recordSpawnMarker(UUID playerUuid, String playerName, Location location, String label, String description, String colorHex) {
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

    public void recordPath(
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
            int selectedHotbarSlot,
            float experienceProgress,
            List<InventoryItem> inventory,
            List<EffectState> effects
    ) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        CurrentSession session = currentSession;
        session.pendingPaths.add(new PathPoint(
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
                selectedHotbarSlot,
                experienceProgress,
                inventory == null ? List.of() : List.copyOf(inventory),
                effects == null ? List.of() : List.copyOf(effects)
        ));
        maybeFlushPendingPaths(session);
    }

    public void recordDamage(
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

    public void recordDamage(
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

    public void recordDeath(
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

    public void recordDeath(
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

    public void startProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String type, Location start) {
        startProjectile(projectileUuid, shooterUuid, shooterName, shooterUuid == null ? null : "PLAYER", type, "player", null, start);
    }

    public void startProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String shooterEntityType, String type, String kind, String colorHex, Location start) {
        if (currentSession == null || projectileUuid == null) {
            return;
        }
        TrackedProjectile tracked = new TrackedProjectile(projectileUuid, shooterUuid, shooterName, shooterEntityType, type, kind, defaultColor(shooterUuid, colorHex), offset());
        tracked.addPoint(start, offset());
        currentSession.projectiles.put(projectileUuid, tracked);
    }

    public void recordProjectilePoint(UUID projectileUuid, Location location) {
        if (currentSession == null || projectileUuid == null || location == null) {
            return;
        }
        TrackedProjectile tracked = currentSession.projectiles.get(projectileUuid);
        if (tracked != null) {
            tracked.addPoint(location, offset());
        }
    }

    public void finishProjectile(UUID projectileUuid, Location location) {
        if (currentSession == null || projectileUuid == null) {
            return;
        }
        TrackedProjectile tracked = currentSession.projectiles.get(projectileUuid);
        if (tracked != null) {
            tracked.addPoint(location, offset());
            tracked.end(offset());
        }
    }

    public void startMobTrack(UUID entityUuid, String entityType, UUID targetPlayerUuid, String targetPlayerName, String colorHex, Location start) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        currentSession.mobs.computeIfAbsent(entityUuid, ignored -> {
            TrackedMob mob = new TrackedMob(entityUuid, entityType, targetPlayerUuid, targetPlayerName, colorHex, offset());
            mob.touch(targetPlayerUuid, targetPlayerName, start, offset());
            return mob;
        }).touch(targetPlayerUuid, targetPlayerName, start, offset());
    }

    public void recordMobPoint(UUID entityUuid, UUID targetPlayerUuid, String targetPlayerName, Location location) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        TrackedMob mob = currentSession.mobs.get(entityUuid);
        if (mob != null) {
            mob.touch(targetPlayerUuid, targetPlayerName, location, offset());
        }
    }

    public void finishMobTrack(UUID entityUuid) {
        finishMobTrack(entityUuid, null, "untracked");
    }

    public void finishMobTrack(UUID entityUuid, Location location, String endReason) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        TrackedMob mob = currentSession.mobs.get(entityUuid);
        if (mob != null) {
            long now = offset();
            mob.end(location, now, endReason);
        }
    }

    public void recordMobDeath(
            UUID entityUuid,
            String entityType,
            UUID targetPlayerUuid,
            String targetPlayerName,
            UUID killerUuid,
            String killerName,
            String killerEntityType,
            String cause,
            String weapon,
            Location location
    ) {
        if (currentSession == null || entityUuid == null) {
            return;
        }
        long now = offset();
        currentSession.mobDeaths.add(new MobDeathRecord(
                now,
                entityUuid,
                entityType,
                targetPlayerUuid,
                targetPlayerName,
                killerUuid,
                killerName,
                killerEntityType,
                cause,
                weapon,
                LocationUtil.toRecord(location)
        ));

        String victimText = PrettyNames.enumName(entityType == null ? "UNKNOWN" : entityType);
        String killerText = killerName;
        if (killerText == null || killerText.isBlank()) {
            killerText = killerEntityType == null || killerEntityType.isBlank()
                    ? null
                    : PrettyNames.enumName(killerEntityType);
        }
        StringBuilder description = new StringBuilder(victimText).append(" died");
        if (killerText != null && !killerText.isBlank()) {
            description.append(" · by ").append(killerText);
        }
        if (weapon != null && !weapon.isBlank()) {
            description.append(" · ").append(PrettyNames.enumName(weapon));
        }
        currentSession.timeline.add(new TimelineRecord(now, targetPlayerUuid, targetPlayerName, "mob_death", description.toString(), entityType, "#f97316"));
    }

    public void recordDragonSample(Location location, float health, float maxHealth) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.dragon.add(new DragonSample(offset(), location.getWorld().getKey().asString(), location.getX(), location.getY(), location.getZ(), health, maxHealth));
    }

    public void upsertEndCrystal(UUID crystalUuid, Location location) {
        if (currentSession == null || crystalUuid == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.endCrystals.computeIfAbsent(crystalUuid, ignored -> new MutableCrystal(crystalUuid, location.getWorld().getKey().asString(), location.getX(), location.getY(), location.getZ(), offset()));
    }

    public void markEndCrystalDestroyed(UUID crystalUuid) {
        if (currentSession == null || crystalUuid == null) {
            return;
        }
        MutableCrystal crystal = currentSession.endCrystals.get(crystalUuid);
        if (crystal != null && crystal.destroyedAtOffsetMillis == null) {
            crystal.destroyedAtOffsetMillis = offset();
        }
    }

    public Set<UUID> activeProjectiles() {
        if (currentSession == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(currentSession.projectiles.keySet());
    }

    public boolean finishAsync(MatchOutcome outcome, Consumer<Optional<FinishResult>> onSuccess, Consumer<IOException> onFailure) {
        CurrentSession session = currentSession;
        if (session == null) {
            runOnMainThread(() -> onSuccess.accept(Optional.empty()));
            return true;
        }
        if (session.finalizationInProgress) {
            return false;
        }
        session.finalizationInProgress = true;
        session.autoFlushSuspended = false;
        long endedAt = System.currentTimeMillis();
        List<PathPoint> finalBatch = drainPendingPaths(session);
        ViewerSnapshot snapshot = buildFrozenSnapshot(session, endedAt, outcome);
        writeExecutor.execute(() -> {
            try {
                FinishResult result = finalizeSessionOnWriteThread(session, outcome, endedAt, snapshot, finalBatch);
                runOnMainThread(() -> {
                    if (currentSession == session) {
                        currentSession = null;
                    }
                    onSuccess.accept(Optional.of(result));
                });
            } catch (IOException exception) {
                session.finalizationInProgress = false;
                logger.log(Level.SEVERE, "Failed to finalize report " + session.reportId + '.', exception);
                runOnMainThread(() -> onFailure.accept(exception));
            }
        });
        return true;
    }

    public Optional<FinishResult> finishBlocking(MatchOutcome outcome) throws IOException {
        CurrentSession session = currentSession;
        if (session == null) {
            return Optional.empty();
        }
        if (session.finalizationInProgress) {
            throw new IOException("Report finalization is already in progress.");
        }
        session.finalizationInProgress = true;
        session.autoFlushSuspended = false;
        long endedAt = System.currentTimeMillis();
        List<PathPoint> finalBatch = drainPendingPaths(session);
        ViewerSnapshot snapshot = buildFrozenSnapshot(session, endedAt, outcome);
        Future<FinishResult> future = writeExecutor.submit(() -> finalizeSessionOnWriteThread(session, outcome, endedAt, snapshot, finalBatch));
        try {
            FinishResult result = future.get();
            if (currentSession == session) {
                currentSession = null;
            }
            return Optional.of(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            session.finalizationInProgress = false;
            throw new IOException("Interrupted while finalizing the report", exception);
        } catch (Exception exception) {
            session.finalizationInProgress = false;
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Unable to finalize the report", cause);
        }
    }

    private ViewerSnapshot buildFrozenSnapshot(CurrentSession session, long endedAt, MatchOutcome outcome) {
        return new ViewerSnapshot(
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
                List.of(),
                List.copyOf(session.milestones),
                List.copyOf(session.chat),
                session.projectiles.values().stream().map(TrackedProjectile::toRecord).toList(),
                session.mobs.values().stream().map(TrackedMob::toRecord).toList(),
                List.copyOf(session.mobDeaths),
                List.copyOf(session.markers),
                List.copyOf(session.dragon),
                session.endCrystals.values().stream().map(MutableCrystal::toRecord).toList(),
                List.copyOf(session.food),
                List.copyOf(session.effects),
                List.copyOf(session.totems),
                List.copyOf(session.blocks),
                List.copyOf(session.timeline)
        );
    }

    private FinishResult finalizeSessionOnWriteThread(CurrentSession session, MatchOutcome outcome, long endedAt, ViewerSnapshot snapshot, List<PathPoint> finalBatch) throws IOException {
        if (!Files.exists(session.sqliteFile)) {
            initializeSqliteReport(session.sqliteFile);
            session.writerState.lastInventoryByLife.clear();
            warnRuntime(
                    "PeopleHunt reporting warning: the active report database for " + session.reportId
                            + " was missing during report finalization. A new staging database was created, so this report may be incomplete.",
                    new IOException("Active report database was missing and had to be recreated before finalization")
            );
        }
        if (!finalBatch.isEmpty()) {
            writePathBatchToSqlite(session, finalBatch, true, false);
        }
        logger.info("Finalizing report in " + session.sqliteFile);
        finalizeSqliteReport(session.sqliteFile, snapshot);
        logger.info("Finished writing report to " + session.sqliteFile);
        IndexEntry indexEntry = new IndexEntry(
                session.reportId,
                session.startedAtEpochMillis,
                endedAt,
                outcome.name(),
                session.runnerUuid,
                session.runnerName
        );
        synchronized (this) {
            indexEntries.removeIf(entry -> entry.reportId().equals(indexEntry.reportId()));
            indexEntries.add(indexEntry);
            indexEntries.sort(Comparator.comparingLong(IndexEntry::endedAtEpochMillis).reversed());
            saveIndex();
        }
        ViewerSnapshot finishedSnapshot = readFromSqlite(session.sqliteFile);
        return new FinishResult(indexEntry, finishedSnapshot);
    }

    private void maybeFlushPendingPaths(CurrentSession session) {
        if (session == null || session.pendingPaths.isEmpty() || session.autoFlushSuspended || session.finalizationInProgress) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean flushByCount = session.pendingPaths.size() >= pathFlushMaxBufferedPoints;
        boolean flushByAge = (now - session.lastPathFlushAtEpochMillis) >= pathFlushMaxBufferedMillis;
        if (!flushByCount && !flushByAge) {
            return;
        }
        List<PathPoint> batch = drainPendingPaths(session);
        if (batch.isEmpty()) {
            return;
        }
        session.lastPathFlushAtEpochMillis = now;
        writeExecutor.execute(() -> {
            try {
                writePathBatchToSqlite(session, batch, false, false);
            } catch (IOException exception) {
                session.autoFlushSuspended = true;
                warnRuntime(
                        "PeopleHunt reporting warning: live path flushing failed for report " + session.reportId
                                + ". The match will continue, but report path data is now buffering in memory until finalization. See console.",
                        exception
                );
            }
        });
    }

    private List<PathPoint> drainPendingPaths(CurrentSession session) {
        if (session == null || session.pendingPaths.isEmpty()) {
            return List.of();
        }
        List<PathPoint> batch = List.copyOf(session.pendingPaths);
        session.pendingPaths.clear();
        return batch;
    }

    private void writePathBatchToSqlite(CurrentSession session, List<PathPoint> batch, boolean finalFlush, boolean recreateIfMissing) throws IOException {
        if (session == null || batch.isEmpty()) {
            if (session != null) {
                session.autoFlushSuspended = false;
            }
            return;
        }
        if (recreateIfMissing && !Files.exists(session.sqliteFile)) {
            initializeSqliteReport(session.sqliteFile);
            session.writerState.lastInventoryByLife.clear();
        }
        try (Connection connection = openSqliteConnection(session.sqliteFile)) {
            connection.setAutoCommit(false);
            try {
                writePathBatch(connection, batch, session.writerState.lastInventoryByLife);
                connection.commit();
                session.lastPathFlushAtEpochMillis = System.currentTimeMillis();
                session.autoFlushSuspended = false;
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                String context = finalFlush ? "final report path flush" : "live report path flush";
                throw new IOException("Unable to write " + context + " to sqlite", exception);
            }
        } catch (SQLException exception) {
            String context = finalFlush ? "final report path flush" : "live report path flush";
            throw new IOException("Unable to open sqlite connection for " + context, exception);
        }
    }

    private void warnRuntime(String message, Exception exception) {
        String operatorMessage = message.contains(" See console.")
                ? message.replace(" See console.", " Cause: " + ExceptionUtil.summarize(exception) + ". See console.")
                : message + " Cause: " + ExceptionUtil.summarize(exception) + ". See console.";
        logger.log(Level.SEVERE, message + " Cause: " + ExceptionUtil.summarize(exception), exception);
        runOnMainThread(() -> runtimeWarningSink.accept(operatorMessage));
    }

    private void rollbackQuietly(Connection connection, Exception primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            primary.addSuppressed(rollbackException);
        }
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
        Path zipFile = reportsDirectory.resolve("report-" + reportId + ".zip");
        logger.info("Exporting report " + reportId + " to " + zipFile);
        if (Files.exists(exportDir)) {
            deleteRecursively(exportDir);
        }
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("index.html"), viewerHtml);
        if (Files.exists(zipFile)) {
            Files.delete(zipFile);
        }
        ZipUtil.zipDirectory(exportDir, zipFile);
        deleteRecursively(exportDir);
        logger.info("Exported report " + reportId + " to " + zipFile);
        return zipFile;
    }

    public void exportAsync(UUID reportId, ViewerAssets viewerAssets, Consumer<Path> onSuccess, Consumer<IOException> onFailure) {
        backgroundExecutor.execute(() -> {
            try {
                ViewerSnapshot snapshot = readSnapshot(reportId);
                String viewerHtml = viewerAssets.render("LOCAL_EXPORT", toJson(snapshot));
                Path export = export(reportId, viewerHtml);
                runOnMainThread(() -> onSuccess.accept(export));
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Failed to export report " + reportId + '.', exception);
                runOnMainThread(() -> onFailure.accept(exception));
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, "Failed to render export viewer for report " + reportId + '.', exception);
                runOnMainThread(() -> onFailure.accept(new IOException("Unable to render export viewer", exception)));
            }
        });
    }

    public synchronized String toJson(ViewerSnapshot snapshot) {
        return gson.toJson(snapshot);
    }

    private long offset() {
        return currentSession == null ? 0L : System.currentTimeMillis() - currentSession.startedAtEpochMillis;
    }

    private void initializeSqliteReport(Path sqliteFile) throws IOException {
        try {
            Files.deleteIfExists(sqliteFile);
        } catch (IOException exception) {
            throw new IOException("Unable to clear stale sqlite report file", exception);
        }
        try (Connection connection = openSqliteConnection(sqliteFile)) {
            connection.setAutoCommit(false);
            try {
                createSchema(connection);
                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                throw new IOException("Unable to initialize sqlite report schema", exception);
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to create sqlite report", exception);
        }
    }

    private void finalizeSqliteReport(Path sqliteFile, ViewerSnapshot snapshot) throws IOException {
        try (Connection connection = openSqliteConnection(sqliteFile)) {
            connection.setAutoCommit(false);
            try {
                createSchema(connection);
                clearFinalizedSnapshotTables(connection);
                writeSnapshotTables(connection, snapshot, false);
                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                throw new IOException("Unable to finalize sqlite report", exception);
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to open sqlite report for finalization", exception);
        }
    }

    private void writeSqlite(Path reportDir, ViewerSnapshot snapshot) throws IOException {
        Path sqliteFile = reportDir.resolve("report.db");
        try {
            Files.deleteIfExists(sqliteFile);
        } catch (IOException exception) {
            throw new IOException("Unable to clear previous sqlite report", exception);
        }
        try (Connection connection = openSqliteConnection(sqliteFile)) {
            connection.setAutoCommit(false);
            try {
                createSchema(connection);
                writeSnapshotTables(connection, snapshot, true);
                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                throw new IOException("Unable to write sqlite report", exception);
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to open sqlite report", exception);
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS session_metadata (report_id TEXT PRIMARY KEY, runner_uuid TEXT, runner_name TEXT, started_at INTEGER, ended_at INTEGER, outcome TEXT, keep_inventory_mode TEXT, active_kit_id TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS participants (uuid TEXT PRIMARY KEY, name TEXT, role TEXT, color_hex TEXT, joined_late INTEGER, spectator_only INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS participant_stats (uuid TEXT PRIMARY KEY, deaths INTEGER, player_kills INTEGER, player_damage_dealt REAL, player_damage_taken REAL, non_player_damage_taken REAL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS damage_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, attacker_uuid TEXT, attacker_entity_uuid TEXT, attacker_name TEXT, attacker_entity_type TEXT, victim_uuid TEXT, victim_name TEXT, cause TEXT, damage REAL, weapon TEXT, projectile_uuid TEXT, attacker_location_json TEXT, victim_location_json TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS damage_projectile_points (damage_idx INTEGER, point_idx INTEGER, world TEXT, x REAL, y REAL, z REAL, offset_ms INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS deaths (idx INTEGER PRIMARY KEY, offset_ms INTEGER, victim_uuid TEXT, victim_name TEXT, killer_uuid TEXT, killer_name TEXT, killer_entity_type TEXT, cause TEXT, weapon TEXT, location_json TEXT, xp_level INTEGER, inventory_json TEXT, death_message_html TEXT, death_message_plain TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS path_points (path_id INTEGER PRIMARY KEY AUTOINCREMENT, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, life_index INTEGER, role TEXT, game_mode TEXT, is_teleport INTEGER, world TEXT, x REAL, y REAL, z REAL, health REAL, max_health REAL, absorption REAL, food INTEGER, saturation REAL, xp_level INTEGER, total_experience INTEGER, held_hotbar_slot INTEGER, experience_progress REAL, full_inventory_snapshot INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS path_point_effects (path_id INTEGER, effect_idx INTEGER, raw_type TEXT, pretty_name TEXT, amplifier INTEGER, duration_ticks INTEGER, ambient INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS path_point_inventory_deltas (path_id INTEGER, slot INTEGER, removed INTEGER, raw_id TEXT, pretty_name TEXT, amount INTEGER, enchanted INTEGER, text_color_hex TEXT, serialized_item TEXT, enchantments_json TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS milestones (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, key TEXT, description TEXT, raw_name TEXT, color_hex TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS chat (idx INTEGER PRIMARY KEY, offset_ms INTEGER, kind TEXT, player_uuid TEXT, player_name TEXT, html TEXT, plain_text TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS projectiles (projectile_uuid TEXT PRIMARY KEY, shooter_uuid TEXT, shooter_name TEXT, shooter_entity_type TEXT, type TEXT, kind TEXT, color_hex TEXT, launched_at_offset_ms INTEGER, ended_at_offset_ms INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS projectile_points (projectile_uuid TEXT, point_idx INTEGER, world TEXT, x REAL, y REAL, z REAL, offset_ms INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mobs (entity_uuid TEXT PRIMARY KEY, entity_type TEXT, target_player_uuid TEXT, target_player_name TEXT, color_hex TEXT, started_at_offset_ms INTEGER, ended_at_offset_ms INTEGER, end_reason TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mob_points (entity_uuid TEXT, point_idx INTEGER, world TEXT, x REAL, y REAL, z REAL, offset_ms INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mob_deaths (idx INTEGER PRIMARY KEY, offset_ms INTEGER, entity_uuid TEXT, entity_type TEXT, target_player_uuid TEXT, target_player_name TEXT, killer_uuid TEXT, killer_name TEXT, killer_entity_type TEXT, cause TEXT, weapon TEXT, location_json TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS map_markers (marker_uuid TEXT PRIMARY KEY, offset_ms INTEGER, ended_at_offset_ms INTEGER, kind TEXT, player_uuid TEXT, player_name TEXT, world TEXT, x REAL, y REAL, z REAL, label TEXT, description TEXT, color_hex TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS dragon_samples (idx INTEGER PRIMARY KEY, offset_ms INTEGER, world TEXT, x REAL, y REAL, z REAL, health REAL, max_health REAL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS end_crystals (crystal_uuid TEXT PRIMARY KEY, world TEXT, x REAL, y REAL, z REAL, spawned_at_offset_ms INTEGER, destroyed_at_offset_ms INTEGER)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS food_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, raw_name TEXT, pretty_name TEXT, color_hex TEXT, health REAL, absorption REAL, food INTEGER, saturation REAL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS effect_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, action TEXT, raw_name TEXT, pretty_name TEXT, amplifier INTEGER, duration_ticks INTEGER, cause TEXT, source_name TEXT, color_hex TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS totem_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, location_json TEXT, color_hex TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS block_events (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, attacker_name TEXT, raw_name TEXT, blocked_damage REAL, location_json TEXT, color_hex TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS timeline (idx INTEGER PRIMARY KEY, offset_ms INTEGER, player_uuid TEXT, player_name TEXT, kind TEXT, description TEXT, raw_name TEXT, color_hex TEXT)");
        }
    }

    private void clearFinalizedSnapshotTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM session_metadata");
            statement.executeUpdate("DELETE FROM participants");
            statement.executeUpdate("DELETE FROM participant_stats");
            statement.executeUpdate("DELETE FROM damage_events");
            statement.executeUpdate("DELETE FROM damage_projectile_points");
            statement.executeUpdate("DELETE FROM deaths");
            statement.executeUpdate("DELETE FROM milestones");
            statement.executeUpdate("DELETE FROM chat");
            statement.executeUpdate("DELETE FROM projectiles");
            statement.executeUpdate("DELETE FROM projectile_points");
            statement.executeUpdate("DELETE FROM mobs");
            statement.executeUpdate("DELETE FROM mob_points");
            statement.executeUpdate("DELETE FROM mob_deaths");
            statement.executeUpdate("DELETE FROM map_markers");
            statement.executeUpdate("DELETE FROM dragon_samples");
            statement.executeUpdate("DELETE FROM end_crystals");
            statement.executeUpdate("DELETE FROM food_events");
            statement.executeUpdate("DELETE FROM effect_events");
            statement.executeUpdate("DELETE FROM totem_events");
            statement.executeUpdate("DELETE FROM block_events");
            statement.executeUpdate("DELETE FROM timeline");
        }
    }

    private Connection openSqliteConnection(Path sqliteFile) throws IOException, SQLException {
        ensureSqliteDriverLoaded();
        return DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
    }

    private static void ensureSqliteDriverLoaded() throws IOException {
        if (sqliteDriverReady) {
            return;
        }
        synchronized (SQLITE_DRIVER_LOCK) {
            if (sqliteDriverReady) {
                return;
            }
            try {
                Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, ReportService.class.getClassLoader());
                Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
                DriverManager.registerDriver(new DriverShim(driver));
                sqliteDriverReady = true;
            } catch (Exception exception) {
                throw new IOException("SQLite JDBC driver is not available on the plugin runtime classpath", exception);
            }
        }
    }

    private void writeSnapshotTables(Connection connection, ViewerSnapshot snapshot, boolean includePaths) throws Exception {
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
        if (includePaths) {
            writePaths(connection, snapshot.paths());
        }
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
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO mobs VALUES (?, ?, ?, ?, ?, ?, ?, ?)"); PreparedStatement points = connection.prepareStatement("INSERT INTO mob_points VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (MobTrackRecord record : snapshot.mobs()) {
                ps.setString(1, record.entityUuid().toString());
                ps.setString(2, record.entityType());
                ps.setString(3, toString(record.targetPlayerUuid()));
                ps.setString(4, record.targetPlayerName());
                ps.setString(5, record.colorHex());
                ps.setLong(6, record.startedAtOffsetMillis());
                if (record.endedAtOffsetMillis() == null) ps.setNull(7, java.sql.Types.BIGINT); else ps.setLong(7, record.endedAtOffsetMillis());
                ps.setString(8, record.endReason());
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
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO mob_deaths VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            int idx = 0;
            for (MobDeathRecord record : snapshot.mobDeaths()) {
                ps.setInt(1, idx++);
                ps.setLong(2, record.offsetMillis());
                ps.setString(3, toString(record.entityUuid()));
                ps.setString(4, record.entityType());
                ps.setString(5, toString(record.targetPlayerUuid()));
                ps.setString(6, record.targetPlayerName());
                ps.setString(7, toString(record.killerUuid()));
                ps.setString(8, record.killerName());
                ps.setString(9, record.killerEntityType());
                ps.setString(10, record.cause());
                ps.setString(11, record.weapon());
                ps.setString(12, gson.toJson(record.location()));
                ps.addBatch();
            }
            ps.executeBatch();
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
        writePathBatch(connection, paths, new HashMap<>());
    }

    private void writePathBatch(Connection connection, List<PathPoint> paths, Map<String, Map<Integer, InventoryItem>> lastInventoryByLife) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO path_points (offset_ms, player_uuid, player_name, life_index, role, game_mode, is_teleport, world, x, y, z, health, max_health, absorption, food, saturation, xp_level, total_experience, held_hotbar_slot, experience_progress, full_inventory_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
             PreparedStatement effects = connection.prepareStatement("INSERT INTO path_point_effects VALUES (?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement inventory = connection.prepareStatement("INSERT INTO path_point_inventory_deltas VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                ps.setInt(19, point.selectedHotbarSlot());
                ps.setFloat(20, point.experienceProgress());
                ps.setInt(21, fullSnapshot ? 1 : 0);
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
            inventory.setString(10, null);
        } else {
            inventory.setString(4, item.rawId());
            inventory.setString(5, item.prettyName());
            inventory.setInt(6, item.amount());
            inventory.setInt(7, item.enchanted() ? 1 : 0);
            inventory.setString(8, item.textColorHex());
            inventory.setString(9, item.serializedItem());
            inventory.setString(10, gson.toJson(item.enchantments() == null ? List.of() : item.enchantments()));
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
                && Objects.equals(a.serializedItem(), b.serializedItem())
                && Objects.equals(a.enchantments(), b.enchantments());
    }

    private ViewerSnapshot readFromSqlite(Path sqliteFile) throws IOException {
        try (Connection connection = openSqliteConnection(sqliteFile)) {
            Set<String> tables = existingTables(connection);
            Map<String, Set<String>> columnsByTable = new HashMap<>();

            if (!tables.contains("session_metadata")) {
                throw new IOException("Report database is missing session_metadata table");
            }
            Set<String> metadataColumns = tableColumns(connection, columnsByTable, "session_metadata");
            SessionMetadata metadata;
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM session_metadata LIMIT 1")) {
                if (!rs.next()) {
                    throw new IOException("Report database is missing session metadata");
                }
                metadata = new SessionMetadata(
                        UUID.fromString(stringOrNull(rs, metadataColumns, "report_id")),
                        uuid(stringOrNull(rs, metadataColumns, "runner_uuid")),
                        stringOrNull(rs, metadataColumns, "runner_name"),
                        longOrDefault(rs, metadataColumns, "started_at", 0L),
                        longOrDefault(rs, metadataColumns, "ended_at", 0L),
                        stringOrNull(rs, metadataColumns, "outcome"),
                        stringOrNull(rs, metadataColumns, "keep_inventory_mode"),
                        stringOrNull(rs, metadataColumns, "active_kit_id")
                );
            }

            List<Participant> participants = new ArrayList<>();
            if (tables.contains("participants")) {
                Set<String> participantColumns = tableColumns(connection, columnsByTable, "participants");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM participants ORDER BY rowid")) {
                    while (rs.next()) {
                        participants.add(new Participant(
                                uuid(stringOrNull(rs, participantColumns, "uuid")),
                                stringOrNull(rs, participantColumns, "name"),
                                stringOrNull(rs, participantColumns, "role"),
                                stringOrNull(rs, participantColumns, "color_hex"),
                                booleanOrDefault(rs, participantColumns, "joined_late", false),
                                booleanOrDefault(rs, participantColumns, "spectator_only", false)
                        ));
                    }
                }
            }

            List<ParticipantStats> stats = new ArrayList<>();
            if (tables.contains("participant_stats")) {
                Set<String> statColumns = tableColumns(connection, columnsByTable, "participant_stats");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM participant_stats ORDER BY rowid")) {
                    while (rs.next()) {
                        stats.add(new ParticipantStats(
                                uuid(stringOrNull(rs, statColumns, "uuid")),
                                intOrDefault(rs, statColumns, "deaths", 0),
                                intOrDefault(rs, statColumns, "player_kills", 0),
                                doubleOrDefault(rs, statColumns, "player_damage_dealt", 0.0d),
                                doubleOrDefault(rs, statColumns, "player_damage_taken", 0.0d),
                                doubleOrDefault(rs, statColumns, "non_player_damage_taken", 0.0d)
                        ));
                    }
                }
            }

            List<DamageRecord> damage = readDamage(connection, tables, columnsByTable);
            List<DeathRecord> deaths = readDeaths(connection, tables, columnsByTable);
            List<PathPoint> paths = readPaths(connection, tables, columnsByTable);
            List<MilestoneRecord> milestones = new ArrayList<>();
            if (tables.contains("milestones")) {
                Set<String> milestoneColumns = tableColumns(connection, columnsByTable, "milestones");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM milestones ORDER BY idx")) {
                    while (rs.next()) {
                        milestones.add(new MilestoneRecord(
                                longOrDefault(rs, milestoneColumns, "offset_ms", 0L),
                                uuid(stringOrNull(rs, milestoneColumns, "player_uuid")),
                                stringOrNull(rs, milestoneColumns, "player_name"),
                                stringOrNull(rs, milestoneColumns, "key"),
                                stringOrNull(rs, milestoneColumns, "description"),
                                stringOrNull(rs, milestoneColumns, "raw_name"),
                                stringOrNull(rs, milestoneColumns, "color_hex")
                        ));
                    }
                }
            }
            List<ChatRecord> chat = new ArrayList<>();
            if (tables.contains("chat")) {
                Set<String> chatColumns = tableColumns(connection, columnsByTable, "chat");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM chat ORDER BY idx")) {
                    while (rs.next()) {
                        chat.add(new ChatRecord(
                                longOrDefault(rs, chatColumns, "offset_ms", 0L),
                                stringOrNull(rs, chatColumns, "kind"),
                                uuid(stringOrNull(rs, chatColumns, "player_uuid")),
                                stringOrNull(rs, chatColumns, "player_name"),
                                stringOrNull(rs, chatColumns, "html"),
                                stringOrNull(rs, chatColumns, "plain_text")
                        ));
                    }
                }
            }
            List<ProjectileRecord> projectiles = readProjectiles(connection, tables, columnsByTable);
            List<MobTrackRecord> mobs = readMobs(connection, tables, columnsByTable);
            List<MobDeathRecord> mobDeaths = readMobDeaths(connection, tables, columnsByTable);
            List<MapMarker> markers = new ArrayList<>();
            if (tables.contains("map_markers")) {
                Set<String> markerColumns = tableColumns(connection, columnsByTable, "map_markers");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM map_markers ORDER BY offset_ms, rowid")) {
                    while (rs.next()) {
                        String markerId = stringOrNull(rs, markerColumns, "marker_uuid");
                        if (markerId == null || markerId.isBlank()) {
                            continue;
                        }
                        markers.add(new MapMarker(
                                UUID.fromString(markerId),
                                longOrDefault(rs, markerColumns, "offset_ms", 0L),
                                nullableLong(rs, markerColumns, "ended_at_offset_ms"),
                                stringOrNull(rs, markerColumns, "kind"),
                                uuid(stringOrNull(rs, markerColumns, "player_uuid")),
                                stringOrNull(rs, markerColumns, "player_name"),
                                stringOrNull(rs, markerColumns, "world"),
                                doubleOrDefault(rs, markerColumns, "x", 0.0d),
                                doubleOrDefault(rs, markerColumns, "y", 0.0d),
                                doubleOrDefault(rs, markerColumns, "z", 0.0d),
                                stringOrNull(rs, markerColumns, "label"),
                                stringOrNull(rs, markerColumns, "description"),
                                stringOrNull(rs, markerColumns, "color_hex")
                        ));
                    }
                }
            }
            List<DragonSample> dragon = new ArrayList<>();
            if (tables.contains("dragon_samples")) {
                Set<String> dragonColumns = tableColumns(connection, columnsByTable, "dragon_samples");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM dragon_samples ORDER BY idx")) {
                    while (rs.next()) {
                        dragon.add(new DragonSample(
                                longOrDefault(rs, dragonColumns, "offset_ms", 0L),
                                stringOrNull(rs, dragonColumns, "world"),
                                doubleOrDefault(rs, dragonColumns, "x", 0.0d),
                                doubleOrDefault(rs, dragonColumns, "y", 0.0d),
                                doubleOrDefault(rs, dragonColumns, "z", 0.0d),
                                floatOrDefault(rs, dragonColumns, "health", 0.0f),
                                floatOrDefault(rs, dragonColumns, "max_health", 0.0f)
                        ));
                    }
                }
            }
            List<EndCrystalRecord> endCrystals = new ArrayList<>();
            if (tables.contains("end_crystals")) {
                Set<String> crystalColumns = tableColumns(connection, columnsByTable, "end_crystals");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM end_crystals ORDER BY spawned_at_offset_ms, rowid")) {
                    while (rs.next()) {
                        String crystalId = stringOrNull(rs, crystalColumns, "crystal_uuid");
                        if (crystalId == null || crystalId.isBlank()) {
                            continue;
                        }
                        endCrystals.add(new EndCrystalRecord(
                                UUID.fromString(crystalId),
                                stringOrNull(rs, crystalColumns, "world"),
                                doubleOrDefault(rs, crystalColumns, "x", 0.0d),
                                doubleOrDefault(rs, crystalColumns, "y", 0.0d),
                                doubleOrDefault(rs, crystalColumns, "z", 0.0d),
                                longOrDefault(rs, crystalColumns, "spawned_at_offset_ms", 0L),
                                nullableLong(rs, crystalColumns, "destroyed_at_offset_ms")
                        ));
                    }
                }
            }
            List<FoodRecord> food = new ArrayList<>();
            if (tables.contains("food_events")) {
                Set<String> foodColumns = tableColumns(connection, columnsByTable, "food_events");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM food_events ORDER BY idx")) {
                    while (rs.next()) {
                        food.add(new FoodRecord(
                                longOrDefault(rs, foodColumns, "offset_ms", 0L),
                                uuid(stringOrNull(rs, foodColumns, "player_uuid")),
                                stringOrNull(rs, foodColumns, "player_name"),
                                stringOrNull(rs, foodColumns, "raw_name"),
                                stringOrNull(rs, foodColumns, "pretty_name"),
                                stringOrNull(rs, foodColumns, "color_hex"),
                                floatOrDefault(rs, foodColumns, "health", 0.0f),
                                floatOrDefault(rs, foodColumns, "absorption", 0.0f),
                                intOrDefault(rs, foodColumns, "food", 0),
                                floatOrDefault(rs, foodColumns, "saturation", 0.0f)
                        ));
                    }
                }
            }
            List<EffectRecord> effects = new ArrayList<>();
            if (tables.contains("effect_events")) {
                Set<String> effectColumns = tableColumns(connection, columnsByTable, "effect_events");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM effect_events ORDER BY idx")) {
                    while (rs.next()) {
                        effects.add(new EffectRecord(
                                longOrDefault(rs, effectColumns, "offset_ms", 0L),
                                uuid(stringOrNull(rs, effectColumns, "player_uuid")),
                                stringOrNull(rs, effectColumns, "player_name"),
                                stringOrNull(rs, effectColumns, "action"),
                                stringOrNull(rs, effectColumns, "raw_name"),
                                stringOrNull(rs, effectColumns, "pretty_name"),
                                intOrDefault(rs, effectColumns, "amplifier", 0),
                                intOrDefault(rs, effectColumns, "duration_ticks", 0),
                                stringOrNull(rs, effectColumns, "cause"),
                                stringOrNull(rs, effectColumns, "source_name"),
                                stringOrNull(rs, effectColumns, "color_hex")
                        ));
                    }
                }
            }
            List<TotemRecord> totems = new ArrayList<>();
            if (tables.contains("totem_events")) {
                Set<String> totemColumns = tableColumns(connection, columnsByTable, "totem_events");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM totem_events ORDER BY idx")) {
                    while (rs.next()) {
                        totems.add(new TotemRecord(
                                longOrDefault(rs, totemColumns, "offset_ms", 0L),
                                uuid(stringOrNull(rs, totemColumns, "player_uuid")),
                                stringOrNull(rs, totemColumns, "player_name"),
                                locationOrNull(rs, totemColumns, "location_json"),
                                stringOrNull(rs, totemColumns, "color_hex")
                        ));
                    }
                }
            }
            List<BlockRecord> blocks = new ArrayList<>();
            if (tables.contains("block_events")) {
                Set<String> blockColumns = tableColumns(connection, columnsByTable, "block_events");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM block_events ORDER BY idx")) {
                    while (rs.next()) {
                        blocks.add(new BlockRecord(
                                longOrDefault(rs, blockColumns, "offset_ms", 0L),
                                uuid(stringOrNull(rs, blockColumns, "player_uuid")),
                                stringOrNull(rs, blockColumns, "player_name"),
                                stringOrNull(rs, blockColumns, "attacker_name"),
                                stringOrNull(rs, blockColumns, "raw_name"),
                                doubleOrDefault(rs, blockColumns, "blocked_damage", 0.0d),
                                locationOrNull(rs, blockColumns, "location_json"),
                                stringOrNull(rs, blockColumns, "color_hex")
                        ));
                    }
                }
            }
            List<TimelineRecord> timeline = new ArrayList<>();
            if (tables.contains("timeline")) {
                Set<String> timelineColumns = tableColumns(connection, columnsByTable, "timeline");
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM timeline ORDER BY idx")) {
                    while (rs.next()) {
                        timeline.add(new TimelineRecord(
                                longOrDefault(rs, timelineColumns, "offset_ms", 0L),
                                uuid(stringOrNull(rs, timelineColumns, "player_uuid")),
                                stringOrNull(rs, timelineColumns, "player_name"),
                                stringOrNull(rs, timelineColumns, "kind"),
                                stringOrNull(rs, timelineColumns, "description"),
                                stringOrNull(rs, timelineColumns, "raw_name"),
                                stringOrNull(rs, timelineColumns, "color_hex")
                        ));
                    }
                }
            }
            return new ViewerSnapshot(metadata, participants, stats, damage, deaths, paths, milestones, chat, projectiles, mobs, mobDeaths, markers, dragon, endCrystals, food, effects, totems, blocks, timeline);
        } catch (Exception exception) {
            throw new IOException("Unable to read sqlite report", exception);
        }
    }

    private List<DamageRecord> readDamage(Connection connection, Set<String> tables, Map<String, Set<String>> columnsByTable) throws Exception {
        if (!tables.contains("damage_events")) {
            return List.of();
        }
        Map<Integer, List<SimplePoint>> pointsByDamage = new HashMap<>();
        if (tables.contains("damage_projectile_points")) {
            Set<String> projectilePointColumns = tableColumns(connection, columnsByTable, "damage_projectile_points");
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM damage_projectile_points ORDER BY damage_idx, point_idx")) {
                while (rs.next()) {
                    pointsByDamage.computeIfAbsent(intOrDefault(rs, projectilePointColumns, "damage_idx", 0), ignored -> new ArrayList<>()).add(new SimplePoint(
                            stringOrNull(rs, projectilePointColumns, "world"),
                            doubleOrDefault(rs, projectilePointColumns, "x", 0.0d),
                            doubleOrDefault(rs, projectilePointColumns, "y", 0.0d),
                            doubleOrDefault(rs, projectilePointColumns, "z", 0.0d),
                            longOrDefault(rs, projectilePointColumns, "offset_ms", 0L)
                    ));
                }
            }
        }
        Set<String> damageColumns = tableColumns(connection, columnsByTable, "damage_events");
        List<DamageRecord> records = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM damage_events ORDER BY idx")) {
            while (rs.next()) {
                int idx = intOrDefault(rs, damageColumns, "idx", 0);
                records.add(new DamageRecord(
                        longOrDefault(rs, damageColumns, "offset_ms", 0L),
                        uuid(stringOrNull(rs, damageColumns, "attacker_uuid")),
                        uuid(stringOrNull(rs, damageColumns, "attacker_entity_uuid")),
                        stringOrNull(rs, damageColumns, "attacker_name"),
                        stringOrNull(rs, damageColumns, "attacker_entity_type"),
                        uuid(stringOrNull(rs, damageColumns, "victim_uuid")),
                        stringOrNull(rs, damageColumns, "victim_name"),
                        stringOrNull(rs, damageColumns, "cause"),
                        doubleOrDefault(rs, damageColumns, "damage", 0.0d),
                        stringOrNull(rs, damageColumns, "weapon"),
                        uuid(stringOrNull(rs, damageColumns, "projectile_uuid")),
                        pointsByDamage.getOrDefault(idx, List.of()),
                        locationOrNull(rs, damageColumns, "attacker_location_json"),
                        locationOrNull(rs, damageColumns, "victim_location_json")
                ));
            }
        }
        return records;
    }

    private List<DeathRecord> readDeaths(Connection connection, Set<String> tables, Map<String, Set<String>> columnsByTable) throws Exception {
        if (!tables.contains("deaths")) {
            return List.of();
        }
        Set<String> deathColumns = tableColumns(connection, columnsByTable, "deaths");
        java.lang.reflect.Type inventoryType = TypeToken.getParameterized(List.class, InventoryItem.class).getType();
        List<DeathRecord> records = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM deaths ORDER BY idx")) {
            while (rs.next()) {
                List<InventoryItem> inventory = gson.fromJson(stringOrNull(rs, deathColumns, "inventory_json"), inventoryType);
                records.add(new DeathRecord(
                        longOrDefault(rs, deathColumns, "offset_ms", 0L),
                        uuid(stringOrNull(rs, deathColumns, "victim_uuid")),
                        stringOrNull(rs, deathColumns, "victim_name"),
                        uuid(stringOrNull(rs, deathColumns, "killer_uuid")),
                        stringOrNull(rs, deathColumns, "killer_name"),
                        stringOrNull(rs, deathColumns, "killer_entity_type"),
                        stringOrNull(rs, deathColumns, "cause"),
                        stringOrNull(rs, deathColumns, "weapon"),
                        locationOrNull(rs, deathColumns, "location_json"),
                        intOrDefault(rs, deathColumns, "xp_level", 0),
                        inventory == null ? List.of() : inventory,
                        stringOrNull(rs, deathColumns, "death_message_html"),
                        stringOrNull(rs, deathColumns, "death_message_plain")
                ));
            }
        }
        return records;
    }

    private List<PathPoint> readPaths(Connection connection, Set<String> tables, Map<String, Set<String>> columnsByTable) throws Exception {
        if (!tables.contains("path_points")) {
            return List.of();
        }
        Map<Long, List<EffectState>> effectsByPath = new HashMap<>();
        if (tables.contains("path_point_effects")) {
            Set<String> effectColumns = tableColumns(connection, columnsByTable, "path_point_effects");
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM path_point_effects ORDER BY path_id, effect_idx")) {
                while (rs.next()) {
                    long pathId = longOrDefault(rs, effectColumns, "path_id", 0L);
                    effectsByPath.computeIfAbsent(pathId, ignored -> new ArrayList<>()).add(new EffectState(
                            stringOrNull(rs, effectColumns, "raw_type"),
                            stringOrNull(rs, effectColumns, "pretty_name"),
                            intOrDefault(rs, effectColumns, "amplifier", 0),
                            intOrDefault(rs, effectColumns, "duration_ticks", 0),
                            booleanOrDefault(rs, effectColumns, "ambient", false)
                    ));
                }
            }
        }
        Map<Long, List<InventoryDeltaRow>> inventoryByPath = new HashMap<>();
        if (tables.contains("path_point_inventory_deltas")) {
            Set<String> inventoryColumns = tableColumns(connection, columnsByTable, "path_point_inventory_deltas");
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM path_point_inventory_deltas ORDER BY path_id, slot")) {
                while (rs.next()) {
                    long pathId = longOrDefault(rs, inventoryColumns, "path_id", 0L);
                    List<ReportModels.InventoryEnchant> enchantments = parseInventoryEnchantments(stringOrNull(rs, inventoryColumns, "enchantments_json"));
                    inventoryByPath.computeIfAbsent(pathId, ignored -> new ArrayList<>()).add(new InventoryDeltaRow(
                            intOrDefault(rs, inventoryColumns, "slot", 0),
                            booleanOrDefault(rs, inventoryColumns, "removed", false),
                            stringOrNull(rs, inventoryColumns, "raw_id"),
                            stringOrNull(rs, inventoryColumns, "pretty_name"),
                            intOrDefault(rs, inventoryColumns, "amount", 0),
                            booleanOrDefault(rs, inventoryColumns, "enchanted", false),
                            stringOrNull(rs, inventoryColumns, "text_color_hex"),
                            stringOrNull(rs, inventoryColumns, "serialized_item"),
                            enchantments
                    ));
                }
            }
        }
        Map<String, Map<Integer, InventoryItem>> inventoryStateByLife = new HashMap<>();
        Set<String> pathColumns = tableColumns(connection, columnsByTable, "path_points");
        List<PathPoint> paths = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM path_points ORDER BY offset_ms, path_id")) {
            while (rs.next()) {
                long pathId = longOrDefault(rs, pathColumns, "path_id", 0L);
                String playerUuidRaw = stringOrNull(rs, pathColumns, "player_uuid");
                if (playerUuidRaw == null || playerUuidRaw.isBlank()) {
                    continue;
                }
                UUID playerUuid = UUID.fromString(playerUuidRaw);
                int lifeIndex = intOrDefault(rs, pathColumns, "life_index", 0);
                String lifeKey = playerUuid + ":" + lifeIndex;
                Map<Integer, InventoryItem> state = inventoryStateByLife.computeIfAbsent(lifeKey, ignored -> new HashMap<>());
                if (booleanOrDefault(rs, pathColumns, "full_inventory_snapshot", false)) {
                    state.clear();
                }
                for (InventoryDeltaRow delta : inventoryByPath.getOrDefault(pathId, List.of())) {
                    if (delta.removed()) {
                        state.remove(delta.slot());
                    } else {
                        state.put(delta.slot(), new InventoryItem(delta.slot(), delta.rawId(), delta.prettyName(), delta.amount(), delta.enchanted(), delta.textColorHex(), delta.serializedItem(), delta.enchantments()));
                    }
                }
                List<InventoryItem> inventory = state.values().stream().sorted(Comparator.comparingInt(InventoryItem::slot)).toList();
                paths.add(new PathPoint(
                        longOrDefault(rs, pathColumns, "offset_ms", 0L),
                        playerUuid,
                        stringOrNull(rs, pathColumns, "player_name"),
                        lifeIndex,
                        stringOrNull(rs, pathColumns, "role"),
                        stringOrNull(rs, pathColumns, "game_mode"),
                        booleanOrDefault(rs, pathColumns, "is_teleport", false),
                        stringOrNull(rs, pathColumns, "world"),
                        doubleOrDefault(rs, pathColumns, "x", 0.0d),
                        doubleOrDefault(rs, pathColumns, "y", 0.0d),
                        doubleOrDefault(rs, pathColumns, "z", 0.0d),
                        floatOrDefault(rs, pathColumns, "health", 0.0f),
                        floatOrDefault(rs, pathColumns, "max_health", 20.0f),
                        floatOrDefault(rs, pathColumns, "absorption", 0.0f),
                        intOrDefault(rs, pathColumns, "food", 20),
                        floatOrDefault(rs, pathColumns, "saturation", 0.0f),
                        intOrDefault(rs, pathColumns, "xp_level", 0),
                        intOrDefault(rs, pathColumns, "total_experience", 0),
                        intOrDefault(rs, pathColumns, "held_hotbar_slot", 0),
                        floatOrDefault(rs, pathColumns, "experience_progress", 0.0f),
                        inventory,
                        effectsByPath.getOrDefault(pathId, List.of())
                ));
            }
        }
        return paths;
    }

    private List<ProjectileRecord> readProjectiles(Connection connection, Set<String> tables, Map<String, Set<String>> columnsByTable) throws Exception {
        if (!tables.contains("projectiles")) {
            return List.of();
        }
        Map<UUID, List<SimplePoint>> pointsByProjectile = new HashMap<>();
        if (tables.contains("projectile_points")) {
            Set<String> pointColumns = tableColumns(connection, columnsByTable, "projectile_points");
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM projectile_points ORDER BY projectile_uuid, point_idx")) {
                while (rs.next()) {
                    String projectileId = stringOrNull(rs, pointColumns, "projectile_uuid");
                    if (projectileId == null || projectileId.isBlank()) {
                        continue;
                    }
                    UUID uuid = UUID.fromString(projectileId);
                    pointsByProjectile.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(new SimplePoint(
                            stringOrNull(rs, pointColumns, "world"),
                            doubleOrDefault(rs, pointColumns, "x", 0.0d),
                            doubleOrDefault(rs, pointColumns, "y", 0.0d),
                            doubleOrDefault(rs, pointColumns, "z", 0.0d),
                            longOrDefault(rs, pointColumns, "offset_ms", 0L)
                    ));
                }
            }
        }
        Set<String> projectileColumns = tableColumns(connection, columnsByTable, "projectiles");
        List<ProjectileRecord> projectiles = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM projectiles ORDER BY launched_at_offset_ms, rowid")) {
            while (rs.next()) {
                String projectileId = stringOrNull(rs, projectileColumns, "projectile_uuid");
                if (projectileId == null || projectileId.isBlank()) {
                    continue;
                }
                UUID projectileUuid = UUID.fromString(projectileId);
                projectiles.add(new ProjectileRecord(
                        projectileUuid,
                        uuid(stringOrNull(rs, projectileColumns, "shooter_uuid")),
                        stringOrNull(rs, projectileColumns, "shooter_name"),
                        stringOrNull(rs, projectileColumns, "shooter_entity_type"),
                        stringOrNull(rs, projectileColumns, "type"),
                        stringOrNull(rs, projectileColumns, "kind"),
                        stringOrNull(rs, projectileColumns, "color_hex"),
                        longOrDefault(rs, projectileColumns, "launched_at_offset_ms", 0L),
                        nullableLong(rs, projectileColumns, "ended_at_offset_ms"),
                        pointsByProjectile.getOrDefault(projectileUuid, List.of())
                ));
            }
        }
        return projectiles;
    }

    private List<MobTrackRecord> readMobs(Connection connection, Set<String> tables, Map<String, Set<String>> columnsByTable) throws Exception {
        if (!tables.contains("mobs")) {
            return List.of();
        }
        Map<UUID, List<SimplePoint>> pointsByMob = new HashMap<>();
        if (tables.contains("mob_points")) {
            Set<String> pointColumns = tableColumns(connection, columnsByTable, "mob_points");
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM mob_points ORDER BY entity_uuid, point_idx")) {
                while (rs.next()) {
                    String entityId = stringOrNull(rs, pointColumns, "entity_uuid");
                    if (entityId == null || entityId.isBlank()) {
                        continue;
                    }
                    UUID uuid = UUID.fromString(entityId);
                    pointsByMob.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(new SimplePoint(
                            stringOrNull(rs, pointColumns, "world"),
                            doubleOrDefault(rs, pointColumns, "x", 0.0d),
                            doubleOrDefault(rs, pointColumns, "y", 0.0d),
                            doubleOrDefault(rs, pointColumns, "z", 0.0d),
                            longOrDefault(rs, pointColumns, "offset_ms", 0L)
                    ));
                }
            }
        }
        Set<String> mobColumns = tableColumns(connection, columnsByTable, "mobs");
        List<MobTrackRecord> mobs = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM mobs ORDER BY started_at_offset_ms, rowid")) {
            while (rs.next()) {
                String entityId = stringOrNull(rs, mobColumns, "entity_uuid");
                if (entityId == null || entityId.isBlank()) {
                    continue;
                }
                UUID entityUuid = UUID.fromString(entityId);
                mobs.add(new MobTrackRecord(
                        entityUuid,
                        stringOrNull(rs, mobColumns, "entity_type"),
                        uuid(stringOrNull(rs, mobColumns, "target_player_uuid")),
                        stringOrNull(rs, mobColumns, "target_player_name"),
                        stringOrNull(rs, mobColumns, "color_hex"),
                        longOrDefault(rs, mobColumns, "started_at_offset_ms", 0L),
                        nullableLong(rs, mobColumns, "ended_at_offset_ms"),
                        stringOrNull(rs, mobColumns, "end_reason"),
                        pointsByMob.getOrDefault(entityUuid, List.of())
                ));
            }
        }
        return mobs;
    }

    private List<MobDeathRecord> readMobDeaths(Connection connection, Set<String> tables, Map<String, Set<String>> columnsByTable) throws Exception {
        if (!tables.contains("mob_deaths")) {
            return List.of();
        }
        Set<String> mobDeathColumns = tableColumns(connection, columnsByTable, "mob_deaths");
        List<MobDeathRecord> mobDeaths = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM mob_deaths ORDER BY idx")) {
            while (rs.next()) {
                mobDeaths.add(new MobDeathRecord(
                        longOrDefault(rs, mobDeathColumns, "offset_ms", 0L),
                        uuid(stringOrNull(rs, mobDeathColumns, "entity_uuid")),
                        stringOrNull(rs, mobDeathColumns, "entity_type"),
                        uuid(stringOrNull(rs, mobDeathColumns, "target_player_uuid")),
                        stringOrNull(rs, mobDeathColumns, "target_player_name"),
                        uuid(stringOrNull(rs, mobDeathColumns, "killer_uuid")),
                        stringOrNull(rs, mobDeathColumns, "killer_name"),
                        stringOrNull(rs, mobDeathColumns, "killer_entity_type"),
                        stringOrNull(rs, mobDeathColumns, "cause"),
                        stringOrNull(rs, mobDeathColumns, "weapon"),
                        locationOrNull(rs, mobDeathColumns, "location_json")
                ));
            }
        }
        return mobDeaths;
    }

    private Set<String> existingTables(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    tables.add(name.toLowerCase());
                }
            }
        }
        return tables;
    }

    private Set<String> tableColumns(Connection connection, Map<String, Set<String>> cache, String tableName) throws SQLException {
        Set<String> cached = cache.get(tableName);
        if (cached != null) {
            return cached;
        }
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    columns.add(name.toLowerCase());
                }
            }
        }
        cache.put(tableName, columns);
        return columns;
    }

    private String stringOrNull(ResultSet rs, Set<String> columns, String column) throws SQLException {
        return columns.contains(column) ? rs.getString(column) : null;
    }

    private long longOrDefault(ResultSet rs, Set<String> columns, String column, long defaultValue) throws SQLException {
        return columns.contains(column) ? rs.getLong(column) : defaultValue;
    }

    private Long nullableLong(ResultSet rs, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column)) {
            return null;
        }
        Object value = rs.getObject(column);
        return value == null ? null : rs.getLong(column);
    }

    private int intOrDefault(ResultSet rs, Set<String> columns, String column, int defaultValue) throws SQLException {
        return columns.contains(column) ? rs.getInt(column) : defaultValue;
    }

    private double doubleOrDefault(ResultSet rs, Set<String> columns, String column, double defaultValue) throws SQLException {
        return columns.contains(column) ? rs.getDouble(column) : defaultValue;
    }

    private float floatOrDefault(ResultSet rs, Set<String> columns, String column, float defaultValue) throws SQLException {
        return columns.contains(column) ? rs.getFloat(column) : defaultValue;
    }

    private boolean booleanOrDefault(ResultSet rs, Set<String> columns, String column, boolean defaultValue) throws SQLException {
        return columns.contains(column) ? rs.getInt(column) != 0 : defaultValue;
    }

    private LocationRecord locationOrNull(ResultSet rs, Set<String> columns, String column) throws SQLException {
        String json = stringOrNull(rs, columns, column);
        return json == null || json.isBlank() ? null : gson.fromJson(json, ReportModels.LocationRecord.class);
    }

    private List<ReportModels.InventoryEnchant> parseInventoryEnchantments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<ReportModels.InventoryEnchant> parsed = gson.fromJson(json, TypeToken.getParameterized(List.class, ReportModels.InventoryEnchant.class).getType());
        return parsed == null ? List.of() : List.copyOf(parsed);
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

    public record ManualFlushResult(
            UUID reportId,
            int flushedPathPoints,
            int remainingBufferedPathPoints,
            boolean recreatedMissingDatabase,
            boolean reportMayBeIncomplete,
            boolean autoFlushResumed
    ) {}

    private record InventoryDeltaRow(int slot, boolean removed, String rawId, String prettyName, int amount, boolean enchanted, String textColorHex, String serializedItem, List<ReportModels.InventoryEnchant> enchantments) {}

    private static final class CurrentSession {
        private final UUID reportId;
        private final long startedAtEpochMillis;
        private final UUID runnerUuid;
        private final String runnerName;
        private final Path sqliteFile;
        private KeepInventoryMode keepInventoryMode;
        private String activeKitId;
        private final Map<UUID, Participant> participants = new LinkedHashMap<>();
        private final Map<UUID, MutableStats> stats = new LinkedHashMap<>();
        private final List<DamageRecord> damage = new ArrayList<>();
        private final List<DeathRecord> deaths = new ArrayList<>();
        private final List<PathPoint> pendingPaths = new ArrayList<>();
        private final WriterState writerState = new WriterState();
        private volatile long lastPathFlushAtEpochMillis;
        private volatile boolean autoFlushSuspended;
        private volatile boolean finalizationInProgress;
        private final List<MilestoneRecord> milestones = new ArrayList<>();
        private final List<ChatRecord> chat = new ArrayList<>();
        private final Map<UUID, TrackedProjectile> projectiles = new LinkedHashMap<>();
        private final Map<UUID, TrackedMob> mobs = new LinkedHashMap<>();
        private final List<MobDeathRecord> mobDeaths = new ArrayList<>();
        private final List<MapMarker> markers = new ArrayList<>();
        private final Map<String, MapMarker> spawnMarkers = new HashMap<>();
        private final List<DragonSample> dragon = new ArrayList<>();
        private final Map<UUID, MutableCrystal> endCrystals = new LinkedHashMap<>();
        private final List<FoodRecord> food = new ArrayList<>();
        private final List<EffectRecord> effects = new ArrayList<>();
        private final List<TotemRecord> totems = new ArrayList<>();
        private final List<BlockRecord> blocks = new ArrayList<>();
        private final List<TimelineRecord> timeline = new ArrayList<>();

        private CurrentSession(UUID reportId, long startedAtEpochMillis, UUID runnerUuid, String runnerName, KeepInventoryMode keepInventoryMode, String activeKitId, Path sqliteFile) {
            this.reportId = reportId;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.runnerUuid = runnerUuid;
            this.runnerName = runnerName;
            this.sqliteFile = sqliteFile;
            this.keepInventoryMode = keepInventoryMode;
            this.activeKitId = activeKitId;
            this.lastPathFlushAtEpochMillis = startedAtEpochMillis;
            this.autoFlushSuspended = false;
            this.finalizationInProgress = false;
        }
    }

    private static final class WriterState {
        private final Map<String, Map<Integer, InventoryItem>> lastInventoryByLife = new HashMap<>();
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
        private String endReason;
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
            this.endReason = null;
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

        private void end(Location location, long offsetMillis, String endReason) {
            if (location != null && location.getWorld() != null) {
                SimplePoint point = LocationUtil.toSimplePoint(location, offsetMillis);
                if (points.isEmpty()) {
                    points.add(point);
                } else {
                    SimplePoint last = points.get(points.size() - 1);
                    if (!last.world().equals(point.world())
                            || Math.abs(last.x() - point.x()) > 0.1
                            || Math.abs(last.y() - point.y()) > 0.1
                            || Math.abs(last.z() - point.z()) > 0.1) {
                        points.add(point);
                    }
                }
            }
            this.endedAtOffsetMillis = offsetMillis;
            this.endReason = endReason;
        }

        private MobTrackRecord toRecord() {
            return new MobTrackRecord(entityUuid, entityType, targetPlayerUuid, targetPlayerName, colorHex, startedAtOffsetMillis, endedAtOffsetMillis, endReason, List.copyOf(points));
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
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() {
            try {
                return delegate.getParentLogger();
            } catch (java.sql.SQLFeatureNotSupportedException ignored) {
                return Logger.getLogger("org.sqlite");
            }
        }
    }

}
