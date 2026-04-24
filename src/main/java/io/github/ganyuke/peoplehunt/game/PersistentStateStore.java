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

public final class PersistentStateStore {
    private final Path file;
    private final Gson gson;

    public PersistentStateStore(Path file, Gson gson) {
        this.file = file;
        this.gson = gson;
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
        public UUID runnerUuid;
        public Set<UUID> explicitHunters = new LinkedHashSet<>();
        public String activeKitId;
        public KeepInventoryMode inventoryControlMode = KeepInventoryMode.NONE;
        public long selectionGeneration = 0L;
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
