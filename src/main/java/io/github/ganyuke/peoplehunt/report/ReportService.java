package io.github.ganyuke.peoplehunt.report;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.MatchOutcome;
import io.github.ganyuke.peoplehunt.report.ReportModels.ChatRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.DamageRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.DeathRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.IndexEntry;
import io.github.ganyuke.peoplehunt.report.ReportModels.LocationRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.MilestoneRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.Participant;
import io.github.ganyuke.peoplehunt.report.ReportModels.ParticipantStats;
import io.github.ganyuke.peoplehunt.report.ReportModels.PathPoint;
import io.github.ganyuke.peoplehunt.report.ReportModels.ProjectileRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.SessionMetadata;
import io.github.ganyuke.peoplehunt.report.ReportModels.SimplePoint;
import io.github.ganyuke.peoplehunt.report.ReportModels.TimelineRecord;
import io.github.ganyuke.peoplehunt.report.ReportModels.ViewerSnapshot;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
            ReportStorageFormat storageFormat,
            Collection<ParticipantSeed> participants
    ) {
        UUID reportId = UUID.randomUUID();
        currentSession = new CurrentSession(reportId, System.currentTimeMillis(), runnerUuid, runnerName, keepInventoryMode, activeKitId, storageFormat);
        int index = 0;
        for (ParticipantSeed seed : participants) {
            currentSession.participants.put(seed.uuid(), new Participant(
                    seed.uuid(),
                    seed.name(),
                    seed.role(),
                    PALETTE[index % PALETTE.length],
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
            String color = PALETTE[currentSession.participants.size() % PALETTE.length];
            currentSession.participants.put(uuid, new Participant(uuid, name, role, color, joinedLate, spectatorOnly));
            currentSession.stats.put(uuid, new MutableStats());
            currentSession.timeline.add(new TimelineRecord(offset(), uuid, name, "participant", role.toLowerCase() + " joined"));
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
        if (currentSession == null) {
            return;
        }
        currentSession.milestones.add(new MilestoneRecord(offset(), playerUuid, playerName, key, description));
        currentSession.timeline.add(new TimelineRecord(offset(), playerUuid, playerName, "milestone", description));
    }

    public synchronized void recordTimeline(UUID playerUuid, String playerName, String kind, String description) {
        if (currentSession == null) {
            return;
        }
        currentSession.timeline.add(new TimelineRecord(offset(), playerUuid, playerName, kind, description));
    }

    public synchronized void recordPath(UUID playerUuid, String playerName, int lifeIndex, Location location, float health, int food, float saturation) {
        if (currentSession == null || location == null || location.getWorld() == null) {
            return;
        }
        currentSession.paths.add(new PathPoint(
                offset(),
                playerUuid,
                playerName,
                lifeIndex,
                location.getWorld().getKey().asString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                health,
                food,
                saturation
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
        if (currentSession == null) {
            return;
        }
        List<SimplePoint> projectilePath = projectileUuid == null ? List.of() : currentSession.projectiles.getOrDefault(projectileUuid, TrackedProjectile.empty()).snapshotPoints();
        currentSession.damage.add(new DamageRecord(
                offset(),
                attackerUuid,
                attackerName,
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
            victimStats.playerDamageTaken += damage;
        }
        currentSession.timeline.add(new TimelineRecord(offset(), attackerUuid, attackerName, "damage", "dealt %.2f to %s with %s".formatted(damage, victimName, weapon == null ? cause : weapon)));
    }

    public synchronized void recordDeath(
            UUID victimUuid,
            String victimName,
            UUID killerUuid,
            String killerName,
            String cause,
            String weapon,
            Location location,
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
                cause,
                weapon,
                LocationUtil.toRecord(location),
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
        currentSession.chat.add(new ChatRecord(offset(), "death", victimUuid, victimName, html, plain));
        currentSession.timeline.add(new TimelineRecord(offset(), victimUuid, victimName, "death", plain));
    }

    public synchronized void startProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String type, Location start) {
        if (currentSession == null || projectileUuid == null) {
            return;
        }
        TrackedProjectile tracked = new TrackedProjectile(projectileUuid, shooterUuid, shooterName, type, offset());
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
                        session.activeKitId,
                        session.storageFormat.name()
                ),
                new ArrayList<>(session.participants.values()),
                session.stats.entrySet().stream().map(entry -> entry.getValue().toImmutable(entry.getKey())).toList(),
                List.copyOf(session.damage),
                List.copyOf(session.deaths),
                List.copyOf(session.paths),
                List.copyOf(session.milestones),
                List.copyOf(session.chat),
                session.projectiles.values().stream().map(TrackedProjectile::toRecord).toList(),
                List.copyOf(session.timeline)
        );
        Path reportDir = reportsDirectory.resolve(session.reportId.toString());
        Files.createDirectories(reportDir);
        switch (session.storageFormat) {
            case JSONL -> writeJsonl(reportDir, snapshot);
            case SQLITE -> writeSqlite(reportDir, snapshot);
        }
        writeSnapshot(reportDir, snapshot);
        IndexEntry indexEntry = new IndexEntry(
                session.reportId,
                session.startedAtEpochMillis,
                endedAt,
                outcome.name(),
                session.runnerUuid,
                session.runnerName,
                session.storageFormat.name()
        );
        indexEntries.removeIf(entry -> entry.reportId().equals(indexEntry.reportId()));
        indexEntries.add(indexEntry);
        indexEntries.sort(Comparator.comparingLong(IndexEntry::endedAtEpochMillis).reversed());
        saveIndex();
        return Optional.of(new FinishResult(indexEntry, snapshot));
    }

    public synchronized List<IndexEntry> listReports() {
        return indexEntries.stream()
                .sorted(Comparator.comparingLong(IndexEntry::endedAtEpochMillis).reversed())
                .toList();
    }

    public synchronized UUID latestReportId() {
        return indexEntries.stream()
                .max(Comparator.comparingLong(IndexEntry::endedAtEpochMillis))
                .map(IndexEntry::reportId)
                .orElse(null);
    }

    public synchronized ViewerSnapshot readSnapshot(UUID reportId) throws IOException {
        Path reportDir = reportsDirectory.resolve(reportId.toString());
        Path snapshotFile = reportDir.resolve("viewer-snapshot.json");
        if (Files.exists(snapshotFile)) {
            try (Reader reader = Files.newBufferedReader(snapshotFile)) {
                ViewerSnapshot snapshot = gson.fromJson(reader, ViewerSnapshot.class);
                if (snapshot != null) {
                    return snapshot;
                }
            }
        }
        Path sqlite = reportDir.resolve("report.db");
        if (Files.exists(sqlite)) {
            return readFromSqlite(sqlite);
        }
        return readFromJsonl(reportDir);
    }

    public synchronized Optional<IndexEntry> findIndex(UUID reportId) {
        return indexEntries.stream().filter(entry -> entry.reportId().equals(reportId)).findFirst();
    }

    public synchronized Path export(UUID reportId, String viewerHtml) throws IOException {
        ViewerSnapshot snapshot = readSnapshot(reportId);
        Path reportDir = reportsDirectory.resolve(reportId.toString());
        Path exportDir = reportsDirectory.resolve(reportId + "-export");
        if (Files.exists(exportDir)) {
            deleteRecursively(exportDir);
        }
        Files.createDirectories(exportDir.resolve("raw"));
        Files.createDirectories(exportDir.resolve("viewer"));
        try (var stream = Files.walk(reportDir)) {
            stream.forEach(path -> {
                try {
                    Path target = exportDir.resolve("raw").resolve(reportDir.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(path, target);
                    }
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
        Files.writeString(exportDir.resolve("viewer/index.html"), viewerHtml);
        Files.writeString(exportDir.resolve("viewer/snapshot.json"), gson.toJson(snapshot));
        Path zipFile = reportsDirectory.resolve("report-" + reportId + ".zip");
        if (Files.exists(zipFile)) {
            Files.delete(zipFile);
        }
        ZipUtil.zipDirectory(exportDir, zipFile);
        deleteRecursively(exportDir);
        return zipFile;
    }

    private long offset() {
        return currentSession == null ? 0L : System.currentTimeMillis() - currentSession.startedAtEpochMillis;
    }

    private void writeSnapshot(Path reportDir, ViewerSnapshot snapshot) throws IOException {
        try (Writer writer = Files.newBufferedWriter(reportDir.resolve("viewer-snapshot.json"))) {
            gson.toJson(snapshot, writer);
        }
    }

    private void writeJsonl(Path reportDir, ViewerSnapshot snapshot) throws IOException {
        try (Writer writer = Files.newBufferedWriter(reportDir.resolve("metadata.json"))) {
            gson.toJson(snapshot.metadata(), writer);
        }
        writeJson(reportDir.resolve("participants.json"), snapshot.participants());
        writeJson(reportDir.resolve("stats.json"), snapshot.stats());
        writeJsonLines(reportDir.resolve("damage.jsonl"), snapshot.damage());
        writeJsonLines(reportDir.resolve("deaths.jsonl"), snapshot.deaths());
        writeJsonLines(reportDir.resolve("paths.jsonl"), snapshot.paths());
        writeJsonLines(reportDir.resolve("milestones.jsonl"), snapshot.milestones());
        writeJsonLines(reportDir.resolve("chat.jsonl"), snapshot.chat());
        writeJson(reportDir.resolve("projectiles.json"), snapshot.projectiles());
        writeJsonLines(reportDir.resolve("timeline.jsonl"), snapshot.timeline());
    }

    private void writeJson(Path file, Object value) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(value, writer);
        }
    }

    private void writeJsonLines(Path file, Collection<?> values) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            for (Object value : values) {
                writer.write(gson.toJson(value));
                writer.write(System.lineSeparator());
            }
        }
    }

    private void writeSqlite(Path reportDir, ViewerSnapshot snapshot) throws IOException {
        Path sqliteFile = reportDir.resolve("report.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE entries (kind TEXT NOT NULL, json TEXT NOT NULL)");
            }
            writeMetadata(connection, snapshot.metadata());
            writeEntries(connection, "participant", snapshot.participants());
            writeEntries(connection, "stats", snapshot.stats());
            writeEntries(connection, "damage", snapshot.damage());
            writeEntries(connection, "death", snapshot.deaths());
            writeEntries(connection, "path", snapshot.paths());
            writeEntries(connection, "milestone", snapshot.milestones());
            writeEntries(connection, "chat", snapshot.chat());
            writeEntries(connection, "projectile", snapshot.projectiles());
            writeEntries(connection, "timeline", snapshot.timeline());
        } catch (Exception exception) {
            throw new IOException("Unable to write sqlite report", exception);
        }
    }

    private void writeMetadata(Connection connection, SessionMetadata metadata) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO metadata (key, value) VALUES (?, ?)")) {
            statement.setString(1, "json");
            statement.setString(2, gson.toJson(metadata));
            statement.executeUpdate();
        }
    }

    private void writeEntries(Connection connection, String kind, Collection<?> values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO entries (kind, json) VALUES (?, ?)")) {
            for (Object value : values) {
                statement.setString(1, kind);
                statement.setString(2, gson.toJson(value));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private ViewerSnapshot readFromSqlite(Path sqliteFile) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            SessionMetadata metadata = null;
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT value FROM metadata WHERE key='json'")) {
                if (result.next()) {
                    metadata = gson.fromJson(result.getString(1), SessionMetadata.class);
                }
            }
            List<Participant> participants = readEntries(connection, "participant", Participant.class);
            List<ParticipantStats> stats = readEntries(connection, "stats", ParticipantStats.class);
            List<DamageRecord> damage = readEntries(connection, "damage", DamageRecord.class);
            List<DeathRecord> deaths = readEntries(connection, "death", DeathRecord.class);
            List<PathPoint> paths = readEntries(connection, "path", PathPoint.class);
            List<MilestoneRecord> milestones = readEntries(connection, "milestone", MilestoneRecord.class);
            List<ChatRecord> chat = readEntries(connection, "chat", ChatRecord.class);
            List<ProjectileRecord> projectiles = readEntries(connection, "projectile", ProjectileRecord.class);
            List<TimelineRecord> timeline = readEntries(connection, "timeline", TimelineRecord.class);
            return new ViewerSnapshot(metadata, participants, stats, damage, deaths, paths, milestones, chat, projectiles, timeline);
        } catch (Exception exception) {
            throw new IOException("Unable to read sqlite report", exception);
        }
    }

    private <T> List<T> readEntries(Connection connection, String kind, Class<T> type) throws Exception {
        List<T> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT json FROM entries WHERE kind=?")) {
            statement.setString(1, kind);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(gson.fromJson(result.getString(1), type));
                }
            }
        }
        return values;
    }

    private ViewerSnapshot readFromJsonl(Path reportDir) throws IOException {
        SessionMetadata metadata;
        try (Reader reader = Files.newBufferedReader(reportDir.resolve("metadata.json"))) {
            metadata = gson.fromJson(reader, SessionMetadata.class);
        }
        List<Participant> participants = readJson(reportDir.resolve("participants.json"), new TypeToken<List<Participant>>() {}.getType());
        List<ParticipantStats> stats = readJson(reportDir.resolve("stats.json"), new TypeToken<List<ParticipantStats>>() {}.getType());
        List<DamageRecord> damage = readJsonl(reportDir.resolve("damage.jsonl"), DamageRecord.class);
        List<DeathRecord> deaths = readJsonl(reportDir.resolve("deaths.jsonl"), DeathRecord.class);
        List<PathPoint> paths = readJsonl(reportDir.resolve("paths.jsonl"), PathPoint.class);
        List<MilestoneRecord> milestones = readJsonl(reportDir.resolve("milestones.jsonl"), MilestoneRecord.class);
        List<ChatRecord> chat = readJsonl(reportDir.resolve("chat.jsonl"), ChatRecord.class);
        List<ProjectileRecord> projectiles = readJson(reportDir.resolve("projectiles.json"), new TypeToken<List<ProjectileRecord>>() {}.getType());
        List<TimelineRecord> timeline = readJsonl(reportDir.resolve("timeline.jsonl"), TimelineRecord.class);
        return new ViewerSnapshot(metadata, participants, stats, damage, deaths, paths, milestones, chat, projectiles, timeline);
    }

    private <T> List<T> readJson(Path file, java.lang.reflect.Type type) throws IOException {
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<T> values = gson.fromJson(reader, type);
            return values == null ? Collections.emptyList() : values;
        }
    }

    private <T> List<T> readJsonl(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        List<T> values = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                values.add(gson.fromJson(line, type));
            }
        }
        return values;
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

    private static final class CurrentSession {
        private final UUID reportId;
        private final long startedAtEpochMillis;
        private final UUID runnerUuid;
        private final String runnerName;
        private KeepInventoryMode keepInventoryMode;
        private String activeKitId;
        private final ReportStorageFormat storageFormat;
        private final Map<UUID, Participant> participants = new LinkedHashMap<>();
        private final Map<UUID, MutableStats> stats = new LinkedHashMap<>();
        private final List<DamageRecord> damage = new ArrayList<>();
        private final List<DeathRecord> deaths = new ArrayList<>();
        private final List<PathPoint> paths = new ArrayList<>();
        private final List<MilestoneRecord> milestones = new ArrayList<>();
        private final List<ChatRecord> chat = new ArrayList<>();
        private final Map<UUID, TrackedProjectile> projectiles = new LinkedHashMap<>();
        private final List<TimelineRecord> timeline = new ArrayList<>();

        private CurrentSession(UUID reportId, long startedAtEpochMillis, UUID runnerUuid, String runnerName, KeepInventoryMode keepInventoryMode, String activeKitId, ReportStorageFormat storageFormat) {
            this.reportId = reportId;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.runnerUuid = runnerUuid;
            this.runnerName = runnerName;
            this.keepInventoryMode = keepInventoryMode;
            this.activeKitId = activeKitId;
            this.storageFormat = storageFormat;
        }
    }

    private static final class MutableStats {
        private int deaths;
        private int playerKills;
        private double playerDamageDealt;
        private double playerDamageTaken;

        private ParticipantStats toImmutable(UUID uuid) {
            return new ParticipantStats(uuid, deaths, playerKills, playerDamageDealt, playerDamageTaken);
        }
    }

    private static final class TrackedProjectile {
        private final UUID projectileUuid;
        private final UUID shooterUuid;
        private final String shooterName;
        private final String type;
        private final long launchedAtOffsetMillis;
        private Long endedAtOffsetMillis;
        private final List<SimplePoint> points = new ArrayList<>();

        private TrackedProjectile(UUID projectileUuid, UUID shooterUuid, String shooterName, String type, long launchedAtOffsetMillis) {
            this.projectileUuid = projectileUuid;
            this.shooterUuid = shooterUuid;
            this.shooterName = shooterName;
            this.type = type;
            this.launchedAtOffsetMillis = launchedAtOffsetMillis;
        }

        private static TrackedProjectile empty() {
            return new TrackedProjectile(new UUID(0L, 0L), null, null, "unknown", 0L);
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
            return new ProjectileRecord(projectileUuid, shooterUuid, shooterName, type, launchedAtOffsetMillis, endedAtOffsetMillis, List.copyOf(points));
        }
    }
}
