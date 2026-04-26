package io.github.ganyuke.peoplehunt.game;

import com.google.gson.Gson;
import io.github.ganyuke.peoplehunt.game.match.MatchOutcome;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persists operator-controlled configuration state that should survive server restarts.
 *
 * <p>This store intentionally excludes live match state. Active matches are not resumable; the
 * plugin ends them inconclusively on shutdown and only keeps the last finished summary snapshot.
 */
public final class PersistentStateStore {
    private final Path file;
    private final Gson gson;

    public PersistentStateStore(Path file, Gson gson) {
        this.file = file;
        this.gson = gson;
    }

    public Path path() {
        return file;
    }

    public StateData load() throws IOException {
        if (!Files.exists(file)) {
            return new StateData();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            StateData data = gson.fromJson(reader, StateData.class);
            return data == null ? new StateData() : data.sanitize();
        }
    }

    public void save(StateData data) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(data, writer);
        }
    }

    public static final class StateData {
        // Pending selections/settings for the next match.
        public UUID runnerUuid;
        public Set<UUID> explicitHunters = new LinkedHashSet<>();
        public String activeKitId;
        public KeepInventoryMode inventoryControlMode = KeepInventoryMode.NONE;
        public long selectionGeneration = 0L;
        // Cached summary of the last finished match, invalidated whenever selections change so the
        // status command does not present stale context as if it applied to the next run.
        public LastStatusSnapshot lastStatusSnapshot;

        public StateData sanitize() {
            if (explicitHunters == null) {
                explicitHunters = new LinkedHashSet<>();
            }
            if (inventoryControlMode == null || inventoryControlMode == KeepInventoryMode.INHERIT) {
                inventoryControlMode = KeepInventoryMode.NONE;
            }
            return this;
        }
    }

    public record LastStatusSnapshot(
            UUID reportId,
            long selectionGeneration,
            long startedAtEpochMillis,
            long endedAtEpochMillis,
            MatchOutcome outcome,
            UUID runnerUuid,
            String runnerName,
            String startedAtDisplay,
            String elapsedDisplay,
            String summaryText
    ) {}
}
