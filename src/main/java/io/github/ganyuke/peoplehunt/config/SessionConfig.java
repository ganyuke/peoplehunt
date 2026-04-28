package io.github.ganyuke.peoplehunt.config;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakAttributionMode;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakOccupiedArmorMode;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.ItemGrant;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.SaturationBoost;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.compass.CompassDimensionMode;
import java.util.List;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable live session configuration.
 *
 * <p>These settings are host-owned rather than infrastructure-owned, so they can be reloaded or
 * changed mid-session without touching {@code config.yml}.
 */
public final class SessionConfig {
    private final boolean primeKeepPlayersFull;
    private final boolean autoSpectateNewJoins;
    private final int elapsedAnnouncementMinutes;
    private final boolean kitApplyOnStart;
    private final @Nullable String kitSelected;
    private final KeepInventoryMode inventoryControlMode;
    private final KeepInventoryMode endInventoryControlMode;
    private final CompassDimensionMode compassDimensionMode;
    private final boolean deathstreaksEnabled;
    private final DeathstreakAttributionMode deathstreakAttributionMode;
    private final DeathstreakOccupiedArmorMode deathstreakOccupiedArmorMode;
    private final List<DeathstreakTier> deathstreakTiers;

    private SessionConfig(Builder builder) {
        this.primeKeepPlayersFull = builder.primeKeepPlayersFull;
        this.autoSpectateNewJoins = builder.autoSpectateNewJoins;
        this.elapsedAnnouncementMinutes = builder.elapsedAnnouncementMinutes;
        this.kitApplyOnStart = builder.kitApplyOnStart;
        this.kitSelected = normalizeKitSelected(builder.kitSelected);
        this.inventoryControlMode = sanitizeMode(builder.inventoryControlMode);
        this.endInventoryControlMode = sanitizeMode(builder.endInventoryControlMode);
        this.compassDimensionMode = builder.compassDimensionMode == null
                ? CompassDimensionMode.LAST_KNOWN
                : builder.compassDimensionMode;
        this.deathstreaksEnabled = builder.deathstreaksEnabled;
        this.deathstreakAttributionMode = builder.deathstreakAttributionMode == null
                ? DeathstreakAttributionMode.UUID_STRICT
                : builder.deathstreakAttributionMode;
        this.deathstreakOccupiedArmorMode = builder.deathstreakOccupiedArmorMode == null
                ? DeathstreakOccupiedArmorMode.SKIP
                : builder.deathstreakOccupiedArmorMode;
        this.deathstreakTiers = List.copyOf(builder.deathstreakTiers == null
                ? defaults().deathstreakTiers
                : builder.deathstreakTiers);
    }

    public static SessionConfig defaults() {
        return builder()
                .primeKeepPlayersFull(true)
                .autoSpectateNewJoins(true)
                .elapsedAnnouncementMinutes(30)
                .kitApplyOnStart(true)
                .kitSelected(null)
                .inventoryControlMode(KeepInventoryMode.NONE)
                .endInventoryControlMode(KeepInventoryMode.NONE)
                .compassDimensionMode(CompassDimensionMode.LAST_KNOWN)
                .deathstreaksEnabled(true)
                .deathstreakAttributionMode(DeathstreakAttributionMode.UUID_STRICT)
                .deathstreakOccupiedArmorMode(DeathstreakOccupiedArmorMode.SKIP)
                .deathstreakTiers(defaultDeathstreakTiers())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .primeKeepPlayersFull(primeKeepPlayersFull)
                .autoSpectateNewJoins(autoSpectateNewJoins)
                .elapsedAnnouncementMinutes(elapsedAnnouncementMinutes)
                .kitApplyOnStart(kitApplyOnStart)
                .kitSelected(kitSelected)
                .inventoryControlMode(inventoryControlMode)
                .endInventoryControlMode(endInventoryControlMode)
                .compassDimensionMode(compassDimensionMode)
                .deathstreaksEnabled(deathstreaksEnabled)
                .deathstreakAttributionMode(deathstreakAttributionMode)
                .deathstreakOccupiedArmorMode(deathstreakOccupiedArmorMode)
                .deathstreakTiers(deathstreakTiers);
    }

    public boolean primeKeepPlayersFull() {
        return primeKeepPlayersFull;
    }

    public boolean autoSpectateNewJoins() {
        return autoSpectateNewJoins;
    }

    public int elapsedAnnouncementMinutes() {
        return elapsedAnnouncementMinutes;
    }

    public boolean kitApplyOnStart() {
        return kitApplyOnStart;
    }

    public @Nullable String kitSelected() {
        return kitSelected;
    }

    public KeepInventoryMode inventoryControlMode() {
        return inventoryControlMode;
    }

    public KeepInventoryMode endInventoryControlMode() {
        return endInventoryControlMode;
    }

    public CompassDimensionMode compassDimensionMode() {
        return compassDimensionMode;
    }

    public boolean deathstreaksEnabled() {
        return deathstreaksEnabled;
    }

    public DeathstreakAttributionMode deathstreakAttributionMode() {
        return deathstreakAttributionMode;
    }

    public DeathstreakOccupiedArmorMode deathstreakOccupiedArmorMode() {
        return deathstreakOccupiedArmorMode;
    }

    public List<DeathstreakTier> deathstreakTiers() {
        return deathstreakTiers;
    }


    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SessionConfig that)) {
            return false;
        }
        return primeKeepPlayersFull == that.primeKeepPlayersFull
                && autoSpectateNewJoins == that.autoSpectateNewJoins
                && elapsedAnnouncementMinutes == that.elapsedAnnouncementMinutes
                && kitApplyOnStart == that.kitApplyOnStart
                && deathstreaksEnabled == that.deathstreaksEnabled
                && java.util.Objects.equals(kitSelected, that.kitSelected)
                && inventoryControlMode == that.inventoryControlMode
                && endInventoryControlMode == that.endInventoryControlMode
                && compassDimensionMode == that.compassDimensionMode
                && deathstreakAttributionMode == that.deathstreakAttributionMode
                && deathstreakOccupiedArmorMode == that.deathstreakOccupiedArmorMode
                && deathstreakTiers.equals(that.deathstreakTiers);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                primeKeepPlayersFull,
                autoSpectateNewJoins,
                elapsedAnnouncementMinutes,
                kitApplyOnStart,
                kitSelected,
                inventoryControlMode,
                endInventoryControlMode,
                compassDimensionMode,
                deathstreaksEnabled,
                deathstreakAttributionMode,
                deathstreakOccupiedArmorMode,
                deathstreakTiers
        );
    }

    private static @Nullable String normalizeKitSelected(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static KeepInventoryMode sanitizeMode(KeepInventoryMode mode) {
        if (mode == null || mode == KeepInventoryMode.INHERIT) {
            return KeepInventoryMode.NONE;
        }
        return mode;
    }

    private static List<DeathstreakTier> defaultDeathstreakTiers() {
        return List.of(
                new DeathstreakTier(
                        3,
                        0.0,
                        new SaturationBoost(20, 6.0f),
                        List.of(),
                        List.of(
                                new ItemGrant(Material.IRON_HELMET, 1),
                                new ItemGrant(Material.IRON_CHESTPLATE, 1),
                                new ItemGrant(Material.IRON_LEGGINGS, 1),
                                new ItemGrant(Material.IRON_BOOTS, 1),
                                new ItemGrant(Material.IRON_SWORD, 1),
                                new ItemGrant(Material.IRON_AXE, 1),
                                new ItemGrant(Material.IRON_PICKAXE, 1)
                        )
                ),
                new DeathstreakTier(
                        6,
                        0.0,
                        new SaturationBoost(20, 5.0f),
                        List.of(),
                        List.of(
                                new ItemGrant(Material.STONE_SWORD, 1),
                                new ItemGrant(Material.STONE_AXE, 1),
                                new ItemGrant(Material.STONE_PICKAXE, 1)
                        )
                )
        );
    }

    public static final class Builder {
        private boolean primeKeepPlayersFull;
        private boolean autoSpectateNewJoins;
        private int elapsedAnnouncementMinutes;
        private boolean kitApplyOnStart;
        private @Nullable String kitSelected;
        private KeepInventoryMode inventoryControlMode;
        private KeepInventoryMode endInventoryControlMode;
        private CompassDimensionMode compassDimensionMode;
        private boolean deathstreaksEnabled;
        private DeathstreakAttributionMode deathstreakAttributionMode;
        private DeathstreakOccupiedArmorMode deathstreakOccupiedArmorMode;
        private List<DeathstreakTier> deathstreakTiers;

        private Builder() {
        }

        public Builder primeKeepPlayersFull(boolean value) {
            this.primeKeepPlayersFull = value;
            return this;
        }

        public Builder autoSpectateNewJoins(boolean value) {
            this.autoSpectateNewJoins = value;
            return this;
        }

        public Builder elapsedAnnouncementMinutes(int value) {
            this.elapsedAnnouncementMinutes = Math.max(0, value);
            return this;
        }

        public Builder kitApplyOnStart(boolean value) {
            this.kitApplyOnStart = value;
            return this;
        }

        public Builder kitSelected(@Nullable String value) {
            this.kitSelected = value;
            return this;
        }

        public Builder inventoryControlMode(KeepInventoryMode value) {
            this.inventoryControlMode = value;
            return this;
        }

        public Builder endInventoryControlMode(KeepInventoryMode value) {
            this.endInventoryControlMode = value;
            return this;
        }

        public Builder compassDimensionMode(CompassDimensionMode value) {
            this.compassDimensionMode = value;
            return this;
        }

        public Builder deathstreaksEnabled(boolean value) {
            this.deathstreaksEnabled = value;
            return this;
        }

        public Builder deathstreakAttributionMode(DeathstreakAttributionMode value) {
            this.deathstreakAttributionMode = value;
            return this;
        }

        public Builder deathstreakOccupiedArmorMode(DeathstreakOccupiedArmorMode value) {
            this.deathstreakOccupiedArmorMode = value;
            return this;
        }

        public Builder deathstreakTiers(List<DeathstreakTier> value) {
            this.deathstreakTiers = value == null ? null : List.copyOf(value);
            return this;
        }

        public SessionConfig build() {
            return new SessionConfig(this);
        }
    }
}
