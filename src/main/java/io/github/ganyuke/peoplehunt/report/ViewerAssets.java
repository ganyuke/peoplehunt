package io.github.ganyuke.peoplehunt.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;

public final class ViewerAssets {
    private final String viewerTemplate;
    private final String indexTemplate;

    public ViewerAssets(JavaPlugin plugin) throws IOException {
        this.viewerTemplate = read(plugin, "web/viewer.inline.html");
        this.indexTemplate = read(plugin, "web/index.template.html");
    }

    public String render(String reportId) {
        return render(reportId, "null");
    }

    public String render(String reportId, String embeddedSnapshotJson) {
        String safeSnapshot = embeddedSnapshotJson == null ? "null" : embeddedSnapshotJson
                .replace("</", "<\\/")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
        return viewerTemplate
                .replace("__REPORT_ID__", reportId)
                .replace("__INLINE_SNAPSHOT__", safeSnapshot);
    }

    public String renderIndex(Map<String, String> replacements) {
        String html = indexTemplate;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            html = html.replace(entry.getKey(), entry.getValue());
        }
        return html;
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
