package io.github.ganyuke.peoplehunt.game.tools;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;

public final class WhereWasStore {
    private static final Logger LOGGER = Logger.getLogger(WhereWasStore.class.getName());
    private static final long SAVE_DEBOUNCE_MILLIS = 250L;

    private final Path file;
    private final Gson gson;
    private final Map<UUID, Map<String, SavedCoordinate>> values = new HashMap<>();
    private final ScheduledExecutorService executor;
    private final Object saveLock = new Object();
    private Map<UUID, Map<String, SavedCoordinate>> pendingSnapshot;
    private ScheduledFuture<?> pendingSave;

    public WhereWasStore(Path file, Gson gson) {
        this.file = file;
        this.gson = gson;
        this.executor = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("PeopleHunt-WhereWasStore"));
    }

    public Path path() {
        return file;
    }

    public void load() throws IOException {
        values.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Data data = gson.fromJson(reader, Data.class);
            if (data != null && data.values != null) {
                values.putAll(data.values);
            }
        }
    }

    public void save() throws IOException {
        writeSnapshot(snapshotOfValues());
    }

    public void shutdown() {
        Map<UUID, Map<String, SavedCoordinate>> snapshot;
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
                LOGGER.log(Level.WARNING, "Failed to flush pending wherewas data during shutdown", exception);
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

    public void remember(UUID playerUuid, String identifier, Location location) {
        values.computeIfAbsent(playerUuid, ignored -> new HashMap<>())
                .put(identifier.toLowerCase(Locale.ROOT), SavedCoordinate.from(location));
        saveAsync();
    }

    public Optional<SavedCoordinate> lookup(UUID playerUuid, String identifier) {
        return Optional.ofNullable(values.getOrDefault(playerUuid, Map.of()).get(identifier.toLowerCase(Locale.ROOT)));
    }

    public List<String> listIdentifiers(UUID playerUuid) {
        Map<String, SavedCoordinate> playerMap = values.get(playerUuid);
        if (playerMap == null) return List.of();
        return List.copyOf(playerMap.keySet());
    }

    public boolean forget(UUID playerUuid, String identifier) {
        Map<String, SavedCoordinate> map = values.get(playerUuid);
        if (map == null) {
            return false;
        }
        SavedCoordinate removed = map.remove(identifier.toLowerCase(Locale.ROOT));
        saveAsync();
        return removed != null;
    }

    private void saveAsync() {
        synchronized (saveLock) {
            pendingSnapshot = snapshotOfValues();
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = executor.schedule(this::flushPendingAsync, SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushPendingAsync() {
        Map<UUID, Map<String, SavedCoordinate>> snapshot;
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
            LOGGER.log(Level.WARNING, "Failed to persist wherewas data asynchronously", exception);
        }
    }

    private Map<UUID, Map<String, SavedCoordinate>> snapshotOfValues() {
        Map<UUID, Map<String, SavedCoordinate>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, Map<String, SavedCoordinate>> entry : values.entrySet()) {
            snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return snapshot;
    }

    private void writeSnapshot(Map<UUID, Map<String, SavedCoordinate>> snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(new Data(snapshot), writer);
        }
    }

    private static ThreadFactory namedThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private record Data(Map<UUID, Map<String, SavedCoordinate>> values) {}

    public record SavedCoordinate(String world, double x, double y, double z) {
        public static SavedCoordinate from(Location location) {
            return new SavedCoordinate(
                    location.getWorld() == null ? "unknown" : location.getWorld().getKey().asString(),
                    location.getX(),
                    location.getY(),
                    location.getZ()
            );
        }

        public String display() {
            return "%s: %.2f, %.2f, %.2f".formatted(world, x, y, z);
        }

        public NamespacedKey worldKey() {
            return NamespacedKey.fromString(world);
        }
    }
}
