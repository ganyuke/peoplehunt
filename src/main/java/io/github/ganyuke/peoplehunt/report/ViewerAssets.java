package io.github.ganyuke.peoplehunt.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class ViewerAssets {
    private final String template;
    private final String css;
    private final String js;

    public ViewerAssets(JavaPlugin plugin) throws IOException {
        this.template = read(plugin, "web/viewer.html");
        this.css = read(plugin, "web/viewer.css");
        this.js = read(plugin, "web/viewer.js");
    }

    public String render(String reportId) {
        return template
                .replace("__INLINE_CSS__", css)
                .replace("__INLINE_JS__", js)
                .replace("__REPORT_ID__", reportId);
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
