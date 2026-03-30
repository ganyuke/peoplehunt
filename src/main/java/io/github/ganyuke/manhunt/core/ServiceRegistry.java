package io.github.ganyuke.manhunt.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, T instance) {
        services.put(type, instance);
    }

    @SuppressWarnings("unchecked")
    public <T> T require(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("No service registered for " + type.getName());
        }
        return (T) service;
    }
}
