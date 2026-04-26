package io.github.ganyuke.peoplehunt.util;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class Text {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.##");

    private Text() {}

    public static Component mm(String value) {
        return MINI.deserialize(value);
    }

    public static void send(Audience audience, String message) {
        audience.sendMessage(mm(message));
    }

    public static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    public static String formatDurationMillis(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remaining = seconds % 60L;
        if (hours > 0L) {
            return "%dh %02dm %02ds".formatted(hours, minutes, remaining);
        }
        return "%dm %02ds".formatted(minutes, remaining);
    }

    public static String formatTimestamp(long epochMillis) {
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String coord(double x, double y, double z) {
        return DECIMAL.format(x) + ", " + DECIMAL.format(y) + ", " + DECIMAL.format(z);
    }

    public static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    public static String escapeTags(String value) {
        return value == null ? "" : MINI.escapeTags(value);
    }
}
