package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.core.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SessionExporter {
    private final JavaPlugin plugin;
    private final AnalyticsRecorder analyticsRecorder;
    private final ConfigManager configManager;

    public SessionExporter(JavaPlugin plugin, AnalyticsRecorder analyticsRecorder, ConfigManager configManager) {
        this.plugin = plugin;
        this.analyticsRecorder = analyticsRecorder;
        this.configManager = configManager;
    }

    public Path exportSession(UUID sessionId) {
        analyticsRecorder.flush();
        Path sessionDirectory = analyticsRecorder.getSessionDirectory(sessionId);
        if (sessionDirectory == null || !Files.exists(sessionDirectory)) {
            return null;
        }
        try {
            Path exportDirectory = plugin.getDataFolder().toPath().resolve("exports");
            Files.createDirectories(exportDirectory);
            Path zipPath = exportDirectory.resolve(sessionId + ".zip");
            Files.deleteIfExists(zipPath);
            try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                Files.walk(sessionDirectory)
                        .filter(Files::isRegularFile)
                        .forEach(path -> writeEntry(sessionDirectory, path, outputStream));
            }
            return zipPath;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to export session " + sessionId + ": " + exception.getMessage());
            return null;
        }
    }

    private void writeEntry(Path root, Path file, ZipOutputStream outputStream) {
        String entryName = root.relativize(file).toString().replace('\\', '/');
        try (InputStream inputStream = Files.newInputStream(file)) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            inputStream.transferTo(outputStream);
            outputStream.closeEntry();
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to add file to export zip: " + file + " -> " + exception.getMessage());
        }
    }
}
