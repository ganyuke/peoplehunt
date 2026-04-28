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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small JSON-backed store for operator-facing selections and the cached last-status snapshot.
 *
 * <p>Reads happen synchronously during startup, but routine writes are debounced onto a dedicated
 * background thread so command-driven selection changes do not block the main thread.
 */
public final class PersistentStateStore {
    private static final Logger LOGGER = Logger.getLogger(PersistentStateStore.class.getName());
    private static final long SAVE_DEBOUNCE_MILLIS = 250L;

    private final Path file;
    private final Gson gson;
    private final ScheduledExecutorService executor;
    private final Object saveLock = new Object();
    private StateData pendingSnapshot;
    private ScheduledFuture<?> pendingSave;

    public PersistentStateStore(Path file, Gson gson) {
        this.file = file;
        this.gson = gson;
        this.executor = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("PeopleHunt-StateStore"));
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
        writeSnapshot(snapshotOf(data));
    }

    public void saveAsync(StateData data) {
        synchronized (saveLock) {
            pendingSnapshot = snapshotOf(data);
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = executor.schedule(this::flushPendingAsync, SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public void shutdown() {
        StateData snapshot;
        synchronized (saveLock) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
            snapshot = pendingSnapshot;
            pendingSnapshot = null;
        }
        if (snapshot != null) {
            try {
                writeSnapshot(snapshot);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Failed to flush pending plugin state during shutdown", exception);
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void flushPendingAsync() {
        StateData snapshot;
        synchronized (saveLock) {
            snapshot = pendingSnapshot;
            pendingSnapshot = null;
            pendingSave = null;
        }
        if (snapshot == null) {
            return;
        }
        try {
            writeSnapshot(snapshot);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to persist PeopleHunt state asynchronously", exception);
        }
    }

    private void writeSnapshot(StateData snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(snapshot, writer);
        }
    }

    private static StateData snapshotOf(StateData data) {
        StateData snapshot = new StateData();
        snapshot.runnerUuid = data.runnerUuid;
        snapshot.explicitHunters = new LinkedHashSet<>(data.explicitHunters == null ? Set.of() : data.explicitHunters);
        snapshot.activeKitId = data.activeKitId;
        snapshot.inventoryControlMode = data.inventoryControlMode;
        snapshot.selectionGeneration = data.selectionGeneration;
        snapshot.lastStatusSnapshot = data.lastStatusSnapshot;
        return snapshot.sanitize();
    }

    private static ThreadFactory namedThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
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
