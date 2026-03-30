package io.github.ganyuke.peoplehunt.game;

public enum KeepInventoryMode {
    NONE,
    KIT,
    ALL,
    INHERIT;

    public KeepInventoryMode resolve(KeepInventoryMode fallback) {
        return this == INHERIT ? fallback : this;
    }
}
