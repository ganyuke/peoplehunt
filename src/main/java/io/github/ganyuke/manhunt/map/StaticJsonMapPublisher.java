package io.github.ganyuke.manhunt.map;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.util.JsonLineWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StaticJsonMapPublisher implements MapPublisher {
    private final ConfigManager configManager;

    public StaticJsonMapPublisher(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public void publishSession(MatchSession session, Path sessionDirectory) {
        if (sessionDirectory == null || !Files.exists(sessionDirectory)) {
            return;
        }
        if (!"STATIC_JSON".equalsIgnoreCase(configManager.settings().mapPublisherMode())) {
            return;
        }
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
        try {
            Files.writeString(
                    sessionDirectory.resolve("viewer-manifest.json"),
                    JsonLineWriter.toJson(manifest),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ignored) {
        }
    }
}
