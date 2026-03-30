package io.github.ganyuke.manhunt.util;

import org.bukkit.ChatColor;

public final class TextUtil {
    private TextUtil() {
    }

    public static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static String prefixed(String prefix, String message) {
        return colorize(prefix + message);
    }
}
