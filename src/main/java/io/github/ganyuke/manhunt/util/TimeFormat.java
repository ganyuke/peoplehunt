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
}
