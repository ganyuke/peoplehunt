package io.github.ganyuke.manhunt.map;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.util.JsonLineWriter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StaticWebMapPublisher implements MapPublisher {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;

    public StaticWebMapPublisher(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void publishSession(MatchSession session, Path sessionDirectory) {
        if (sessionDirectory == null || !Files.exists(sessionDirectory)) {
            return;
        }
        if (!"STATIC_WEB".equalsIgnoreCase(configManager.settings().mapPublisherMode())) {
            return;
        }
        try {
            Path viewerDirectory = sessionDirectory.resolve(configManager.settings().mapViewerDirectoryName());
            Files.createDirectories(viewerDirectory);
            writeManifest(session, sessionDirectory, viewerDirectory);
            writeViewerData(sessionDirectory, viewerDirectory);
            copyResource("viewer/index.html", viewerDirectory.resolve("index.html"));
            copyResource("viewer/app.js", viewerDirectory.resolve("app.js"));
            copyResource("viewer/styles.css", viewerDirectory.resolve("styles.css"));
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to publish static web viewer for session " + session.sessionId() + ": " + exception.getMessage());
        }
    }

    private void writeManifest(MatchSession session, Path sessionDirectory, Path viewerDirectory) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("sessionId", session.sessionId().toString());
        manifest.put("runnerId", session.runnerId().toString());
        List<String> hunters = new ArrayList<>();
        for (UUID hunterId : session.hunterIds()) {
            hunters.add(hunterId.toString());
        }
        manifest.put("hunterIds", hunters);
        manifest.put("state", session.state().name());
        manifest.put("victory", session.victoryType().name());
        manifest.put("endReason", session.endReason());
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("viewerIndex", sessionDirectory.relativize(viewerDirectory.resolve("index.html")).toString().replace('\\', '/'));
        manifest.put("files", List.of(
                "session-summary.json",
                "session-events.jsonl",
                "lives.jsonl",
                "path-points.jsonl",
                "damage.jsonl",
                "deaths.jsonl",
                "health.jsonl",
                "milestones.jsonl"
        ));
        Files.writeString(
                sessionDirectory.resolve("viewer-manifest.json"),
                JsonLineWriter.toJson(manifest),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private void writeViewerData(Path sessionDirectory, Path viewerDirectory) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", Instant.now().toString());
        data.put("sessionSummaryJson", readIfExists(sessionDirectory.resolve("session-summary.json")));
        data.put("sessionEventsJsonl", readIfExists(sessionDirectory.resolve("session-events.jsonl")));
        data.put("livesJsonl", readIfExists(sessionDirectory.resolve("lives.jsonl")));
        data.put("pathPointsJsonl", readIfExists(sessionDirectory.resolve("path-points.jsonl")));
        data.put("damageJsonl", readIfExists(sessionDirectory.resolve("damage.jsonl")));
        data.put("deathsJsonl", readIfExists(sessionDirectory.resolve("deaths.jsonl")));
        data.put("healthJsonl", readIfExists(sessionDirectory.resolve("health.jsonl")));
        data.put("milestonesJsonl", readIfExists(sessionDirectory.resolve("milestones.jsonl")));
        String script = "window.MANHUNT_VIEWER_DATA = " + JsonLineWriter.toJson(data) + ";" + System.lineSeparator();
        Files.writeString(
                viewerDirectory.resolve("viewer-data.js"),
                script,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private String readIfExists(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private void copyResource(String resourcePath, Path targetPath) throws IOException {
        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing resource: " + resourcePath);
            }
            Files.writeString(
                    targetPath,
                    new String(inputStream.readAllBytes(), StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        }
    }
}
