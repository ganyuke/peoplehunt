package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import io.github.ganyuke.manhunt.util.JsonLineWriter;
import org.bukkit.Location;
import io.github.ganyuke.manhunt.util.PlayerUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class AsyncJsonAnalyticsRecorder implements AnalyticsRecorder {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ExecutorService executor;

    public AsyncJsonAnalyticsRecorder(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.executor = Executors.newSingleThreadExecutor(new AnalyticsThreadFactory());
    }

    @Override
    public void openSession(MatchSession session) {
        Path sessionDirectory = getOrCreateSessionDirectory(session.sessionId());
        Map<String, Object> event = baseSessionEvent("session-opened", session);
        append(sessionDirectory.resolve("session-events.jsonl"), event);
        rewriteSummary(session);
    }

    @Override
    public void markPrimed(MatchSession session) {
        append(getOrCreateSessionDirectory(session.sessionId()).resolve("session-events.jsonl"), baseSessionEvent("session-primed", session));
        rewriteSummary(session);
    }

    @Override
    public void markStarted(MatchSession session) {
        append(getOrCreateSessionDirectory(session.sessionId()).resolve("session-events.jsonl"), baseSessionEvent("session-started", session));
        rewriteSummary(session);
    }

    @Override
    public void markEnded(MatchSession session) {
        append(getOrCreateSessionDirectory(session.sessionId()).resolve("session-events.jsonl"), baseSessionEvent("session-ended", session));
        rewriteSummary(session);
    }

    @Override
    public void recordLifeStart(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", "life-start");
        data.put("sessionId", sessionId.toString());
        data.put("playerId", playerId.toString());
        data.put("role", role.name());
        data.put("lifeNo", lifeNo);
        data.put("at", at.toString());
        data.put("epochMillis", at.toEpochMilli());
        data.put("location", locationMap(location));
        append(getOrCreateSessionDirectory(sessionId).resolve("lives.jsonl"), data);
    }

    @Override
    public void recordLifeEnd(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", "life-end");
        data.put("sessionId", sessionId.toString());
        data.put("playerId", playerId.toString());
        data.put("role", role.name());
        data.put("lifeNo", lifeNo);
        data.put("at", at.toString());
        data.put("epochMillis", at.toEpochMilli());
        data.put("location", locationMap(location));
        data.put("reason", reason);
        append(getOrCreateSessionDirectory(sessionId).resolve("lives.jsonl"), data);
    }

    @Override
    public void recordPathPoint(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String sampleKind, Map<String, ?> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.toString());
        payload.put("playerId", playerId.toString());
        payload.put("role", role.name());
        payload.put("lifeNo", lifeNo);
        payload.put("at", at.toString());
        payload.put("epochMillis", at.toEpochMilli());
        payload.put("sampleKind", sampleKind);
        payload.put("location", locationMap(location));
        payload.put("data", data == null ? Map.of() : new LinkedHashMap<>(data));
        append(getOrCreateSessionDirectory(sessionId).resolve("path-points.jsonl"), payload);
    }

    @Override
    public void recordDamage(UUID sessionId, UUID victimId, UUID attackerId, Instant at, double rawDamage, double finalDamage, String damageType, String causingEntityType, String directEntityType, UUID causingEntityId, Location sourceLocation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.toString());
        payload.put("victimId", victimId.toString());
        payload.put("attackerId", attackerId == null ? null : attackerId.toString());
        payload.put("at", at.toString());
        payload.put("epochMillis", at.toEpochMilli());
        payload.put("rawDamage", rawDamage);
        payload.put("finalDamage", finalDamage);
        payload.put("damageType", damageType);
        payload.put("causingEntityType", causingEntityType);
        payload.put("directEntityType", directEntityType);
        payload.put("causingEntityId", causingEntityId == null ? null : causingEntityId.toString());
        payload.put("sourceLocation", locationMap(sourceLocation));
        append(getOrCreateSessionDirectory(sessionId).resolve("damage.jsonl"), payload);
    }

    @Override
    public void recordDeath(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, String damageType, String causingEntityType, String directEntityType, UUID causingEntityId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.toString());
        payload.put("playerId", playerId.toString());
        payload.put("role", role.name());
        payload.put("lifeNo", lifeNo);
        payload.put("at", at.toString());
        payload.put("epochMillis", at.toEpochMilli());
        payload.put("location", locationMap(location));
        payload.put("damageType", damageType);
        payload.put("causingEntityType", causingEntityType);
        payload.put("directEntityType", directEntityType);
        payload.put("causingEntityId", causingEntityId == null ? null : causingEntityId.toString());
        append(getOrCreateSessionDirectory(sessionId).resolve("deaths.jsonl"), payload);
    }

    @Override
    public void recordHealthSample(UUID sessionId, UUID playerId, Role role, int lifeNo, Instant at, Location location, double health, double maxHealth, int food, float saturation, double armor, int level) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.toString());
        payload.put("playerId", playerId.toString());
        payload.put("role", role.name());
        payload.put("lifeNo", lifeNo);
        payload.put("at", at.toString());
        payload.put("epochMillis", at.toEpochMilli());
        payload.put("location", locationMap(location));
        payload.put("health", health);
        payload.put("maxHealth", maxHealth);
        payload.put("food", food);
        payload.put("saturation", saturation);
        payload.put("armor", armor);
        payload.put("level", level);
        append(getOrCreateSessionDirectory(sessionId).resolve("health.jsonl"), payload);
    }

    @Override
    public void recordMilestone(UUID sessionId, UUID playerId, Role role, Instant at, Location location, String milestoneKey, String title, Map<String, ?> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.toString());
        payload.put("playerId", playerId.toString());
        payload.put("role", role.name());
        payload.put("at", at.toString());
        payload.put("epochMillis", at.toEpochMilli());
        payload.put("location", locationMap(location));
        payload.put("milestoneKey", milestoneKey);
        payload.put("title", title);
        payload.put("data", data == null ? Map.of() : new LinkedHashMap<>(data));
        append(getOrCreateSessionDirectory(sessionId).resolve("milestones.jsonl"), payload);
    }

    @Override
    public Path getSessionDirectory(UUID sessionId) {
        Path path = baseDirectory().resolve(sessionId.toString());
        return Files.exists(path) ? path : null;
    }

    @Override
    public void flush() {
        try {
            Future<?> future = executor.submit(() -> {
            });
            future.get(10L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to flush analytics queue: " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private Path baseDirectory() {
        return plugin.getDataFolder().toPath().resolve(configManager.settings().analyticsFolder());
    }

    private Path getOrCreateSessionDirectory(UUID sessionId) {
        Path path = baseDirectory().resolve(sessionId.toString());
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create analytics directory", exception);
        }
        return path;
    }

    private void append(Path file, Map<String, ?> data) {
        String line = JsonLineWriter.toJson(data) + System.lineSeparator();
        executor.submit(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to append analytics data to " + file + ": " + exception.getMessage());
            }
        });
    }

    private void rewriteSummary(MatchSession session) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionId", session.sessionId().toString());
        summary.put("runnerId", session.runnerId().toString());
        summary.put("runnerName", PlayerUtil.name(session.runnerId()));
        List<String> hunters = new ArrayList<>();
        List<Map<String, Object>> hunterEntries = new ArrayList<>();
        for (UUID hunterId : session.hunterIds()) {
            hunters.add(hunterId.toString());
            hunterEntries.add(playerEntry(hunterId, "HUNTER"));
        }
        summary.put("hunterIds", hunters);
        summary.put("runner", playerEntry(session.runnerId(), "RUNNER"));
        summary.put("hunters", hunterEntries);
        summary.put("state", session.state().name());
        summary.put("primedAt", session.primedAt() == null ? null : session.primedAt().toString());
        summary.put("startedAt", session.startedAt() == null ? null : session.startedAt().toString());
        summary.put("endedAt", session.endedAt() == null ? null : session.endedAt().toString());
        summary.put("victory", session.victoryType().name());
        summary.put("endReason", session.endReason());
        Path file = getOrCreateSessionDirectory(session.sessionId()).resolve("session-summary.json");
        String content = JsonLineWriter.toJson(summary);
        executor.submit(() -> {
            try {
                Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to write session summary: " + exception.getMessage());
            }
        });
    }

    private Map<String, Object> baseSessionEvent(String eventName, MatchSession session) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", eventName);
        event.put("sessionId", session.sessionId().toString());
        event.put("runnerId", session.runnerId().toString());
        event.put("runnerName", PlayerUtil.name(session.runnerId()));
        List<String> hunters = new ArrayList<>();
        List<Map<String, Object>> hunterEntries = new ArrayList<>();
        for (UUID hunterId : session.hunterIds()) {
            hunters.add(hunterId.toString());
            hunterEntries.add(playerEntry(hunterId, "HUNTER"));
        }
        event.put("hunterIds", hunters);
        event.put("runner", playerEntry(session.runnerId(), "RUNNER"));
        event.put("hunters", hunterEntries);
        event.put("state", session.state().name());
        event.put("at", Instant.now().toString());
        event.put("epochMillis", Instant.now().toEpochMilli());
        event.put("victory", session.victoryType().name());
        event.put("endReason", session.endReason());
        return event;
    }

    private Map<String, Object> playerEntry(UUID playerId, String role) {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("id", playerId == null ? null : playerId.toString());
        player.put("name", playerId == null ? null : PlayerUtil.name(playerId));
        player.put("role", role);
        return player;
    }

    private Map<String, Object> locationMap(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", location.getWorld().getName());
        map.put("environment", location.getWorld().getEnvironment().name());
        map.put("x", location.getX());
        map.put("y", location.getY());
        map.put("z", location.getZ());
        map.put("yaw", location.getYaw());
        map.put("pitch", location.getPitch());
        return map;
    }

    private static final class AnalyticsThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "manhunt-analytics-writer");
            thread.setDaemon(true);
            return thread;
        }
    }
}
