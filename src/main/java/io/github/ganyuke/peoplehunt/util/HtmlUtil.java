package io.github.ganyuke.peoplehunt.util;

import java.util.ArrayDeque;
import java.util.Deque;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class HtmlUtil {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private HtmlUtil() {}

    public static String componentToHtml(Component component) {
        return legacyToHtml(LEGACY.serialize(component));
    }

    public static String escape(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static String legacyToHtml(String legacy) {
        StringBuilder html = new StringBuilder();
        Deque<String> closes = new ArrayDeque<>();
        String color = "#ffffff";
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        html.append(openSpan(color, bold, italic, underlined, strikethrough));
        closes.push("</span>");
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(++i));
                if (code == 'r') {
                    while (!closes.isEmpty()) {
                        html.append(closes.pop());
                    }
                    color = "#ffffff";
                    bold = false;
                    italic = false;
                    underlined = false;
                    strikethrough = false;
                    html.append(openSpan(color, bold, italic, underlined, strikethrough));
                    closes.push("</span>");
                    continue;
                }
                String newColor = colorFor(code);
                if (newColor != null) {
                    while (!closes.isEmpty()) {
                        html.append(closes.pop());
                    }
                    color = newColor;
                    bold = false;
                    italic = false;
                    underlined = false;
                    strikethrough = false;
                    html.append(openSpan(color, bold, italic, underlined, strikethrough));
                    closes.push("</span>");
                    continue;
                }
                if (code == 'l') {
                    bold = true;
                } else if (code == 'o') {
                    italic = true;
                } else if (code == 'n') {
                    underlined = true;
                } else if (code == 'm') {
                    strikethrough = true;
                }
                while (!closes.isEmpty()) {
                    html.append(closes.pop());
                }
                html.append(openSpan(color, bold, italic, underlined, strikethrough));
                closes.push("</span>");
                continue;
            }
            if (c == '\n') {
                html.append("<br>");
            } else {
                html.append(escape(String.valueOf(c)));
            }
        }
        while (!closes.isEmpty()) {
            html.append(closes.pop());
        }
        return html.toString();
    }

    private static String openSpan(String color, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {
        StringBuilder style = new StringBuilder("color:").append(color).append(';');
        if (bold) {
            style.append("font-weight:bold;");
        }
        if (italic) {
            style.append("font-style:italic;");
        }
        if (underlined || strikethrough) {
            style.append("text-decoration:");
            if (underlined) {
                style.append(" underline");
            }
            if (strikethrough) {
                style.append(" line-through");
            }
            style.append(';');
        }
        return "<span style=\"" + style + "\">";
    }

    private static String colorFor(char code) {
        return switch (code) {
            case '0' -> "#000000";
            case '1' -> "#0000aa";
            case '2' -> "#00aa00";
            case '3' -> "#00aaaa";
            case '4' -> "#aa0000";
            case '5' -> "#aa00aa";
            case '6' -> "#ffaa00";
            case '7' -> "#aaaaaa";
            case '8' -> "#555555";
            case '9' -> "#5555ff";
            case 'a' -> "#55ff55";
            case 'b' -> "#55ffff";
            case 'c' -> "#ff5555";
            case 'd' -> "#ff55ff";
            case 'e' -> "#ffff55";
            case 'f' -> "#ffffff";
            default -> null;
        };
    }
}
