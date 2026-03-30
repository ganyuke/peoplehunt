package io.github.ganyuke.manhunt.util;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

public final class JsonLineWriter {
    private JsonLineWriter() {
    }

    public static String toJson(Object value) {
        StringBuilder builder = new StringBuilder();
        append(builder, value);
        return builder.toString();
    }

    private static void append(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String stringValue) {
            builder.append('"').append(escape(stringValue)).append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            builder.append('"').append(escape(enumValue.name())).append('"');
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            builder.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = mapValue.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                append(builder, entry.getValue());
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append('}');
            return;
        }
        if (value instanceof Collection<?> collectionValue) {
            builder.append('[');
            Iterator<?> iterator = collectionValue.iterator();
            while (iterator.hasNext()) {
                append(builder, iterator.next());
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            builder.append('[');
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                append(builder, Array.get(value, index));
                if (index + 1 < length) {
                    builder.append(',');
                }
            }
            builder.append(']');
            return;
        }
        builder.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static String escape(String input) {
        StringBuilder builder = new StringBuilder(input.length() + 16);
        for (char character : input.toCharArray()) {
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }
}
