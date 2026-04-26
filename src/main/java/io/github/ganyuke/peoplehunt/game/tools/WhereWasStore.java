package io.github.ganyuke.peoplehunt.game.tools;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;

public final class WhereWasStore {
    private final Path file;
    private final Gson gson;
    private final Map<UUID, Map<String, SavedCoordinate>> values = new HashMap<>();

    public WhereWasStore(Path file, Gson gson) {
        this.file = file;
        this.gson = gson;
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
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(new Data(values), writer);
        }
    }

    public void remember(UUID playerUuid, String identifier, Location location) throws IOException {
        values.computeIfAbsent(playerUuid, ignored -> new HashMap<>())
                .put(identifier.toLowerCase(Locale.ROOT), SavedCoordinate.from(location));
        save();
    }

    public Optional<SavedCoordinate> lookup(UUID playerUuid, String identifier) {
        return Optional.ofNullable(values.getOrDefault(playerUuid, Map.of()).get(identifier.toLowerCase(Locale.ROOT)));
    }

    public List<String> listIdentifiers(UUID playerUuid) {
        Map<String, SavedCoordinate> playerMap = values.get(playerUuid);
        if (playerMap == null) return List.of();
        return List.copyOf(playerMap.keySet());
    }

    public boolean forget(UUID playerUuid, String identifier) throws IOException {
        Map<String, SavedCoordinate> map = values.get(playerUuid);
        if (map == null) {
            return false;
        }
        SavedCoordinate removed = map.remove(identifier.toLowerCase(Locale.ROOT));
        save();
        return removed != null;
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
