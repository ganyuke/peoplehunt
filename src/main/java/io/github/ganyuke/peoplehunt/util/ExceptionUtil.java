package io.github.ganyuke.peoplehunt.util;

import java.util.Objects;

/**
 * Small helpers for turning nested exceptions into short operator-facing summaries while keeping
 * full stack traces in the server console.
 */
public final class ExceptionUtil {
    private ExceptionUtil() {
    }

    public static Throwable rootCause(Throwable throwable) {
        Throwable current = Objects.requireNonNullElse(throwable, new RuntimeException("Unknown failure"));
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public static String summarize(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 157) + "..." : normalized;
    }
}
