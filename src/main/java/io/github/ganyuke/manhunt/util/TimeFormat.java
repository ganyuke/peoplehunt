package io.github.ganyuke.manhunt.util;

import java.time.Duration;

public final class TimeFormat {
    private TimeFormat() {
    }

    public static String mmss(Duration duration) {
        long totalSeconds = Math.max(0L, duration.getSeconds());
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public static String roughHuman(Duration duration) {
        long totalMinutes = Math.max(0L, duration.toMinutes());
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours <= 0L) {
            return totalMinutes + (totalMinutes == 1L ? " minute" : " minutes");
        }
        if (minutes == 0L) {
            return hours + (hours == 1L ? " hour" : " hours");
        }
        return hours + (hours == 1L ? " hour" : " hours") + " " + minutes + (minutes == 1L ? " minute" : " minutes");
    }

    public static String compactHuman(Duration duration) {
        long totalMinutes = Math.max(0L, duration.toMinutes());
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours <= 0L) {
            return totalMinutes + "m";
        }
        if (minutes == 0L) {
            return hours + "h";
        }
        return hours + "h " + minutes + "m";
    }
}
