package io.github.ganyuke.peoplehunt.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.plugin.java.JavaPlugin;

public final class ViewerAssets {
    private final String template;

    public ViewerAssets(JavaPlugin plugin) throws IOException {
        this.template = read(plugin, "web/viewer.inline.html");
    }

    public String render(String reportId) {
        return render(reportId, "null");
    }

    public String render(String reportId, String embeddedSnapshotJson) {
        return template
                .replace("__REPORT_ID__", reportId)
                .replace("__INLINE_SNAPSHOT__", embeddedSnapshotJson == null ? "null" : embeddedSnapshotJson.replace("</", "<\\/"));
    }

    private static String read(JavaPlugin plugin, String path) throws IOException {
        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                throw new IOException("Missing resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
