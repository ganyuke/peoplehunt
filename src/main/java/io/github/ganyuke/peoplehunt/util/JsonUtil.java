package io.github.ganyuke.peoplehunt.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Type;
import java.util.UUID;

public final class JsonUtil {
    private JsonUtil() {}

    public static Gson gson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(UUID.class, new UUIDAdapter())
                .create();
    }

    private static final class UUIDAdapter implements com.google.gson.JsonSerializer<UUID>, com.google.gson.JsonDeserializer<UUID> {
        @Override
        public com.google.gson.JsonElement serialize(UUID src, Type typeOfSrc, com.google.gson.JsonSerializationContext context) {
            return src == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(src.toString());
        }

        @Override
        public UUID deserialize(com.google.gson.JsonElement json, Type typeOfT, com.google.gson.JsonDeserializationContext context) {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String value = json.getAsString();
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        }
    }
}
