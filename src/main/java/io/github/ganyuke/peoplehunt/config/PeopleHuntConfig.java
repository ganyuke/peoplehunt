package io.github.ganyuke.peoplehunt.config;

import io.github.ganyuke.peoplehunt.game.compass.CompassDimensionMode;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.report.ReportStorageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

public final class PeopleHuntConfig {
    private final int elapsedAnnouncementMinutes;
    private final boolean primeKeepPlayersFull;
    private final long playerPathSampleIntervalTicks;
    private final boolean autoSpectateNewJoins;
    private final long compassUpdateIntervalTicks;
    private final CompassDimensionMode compassDimensionMode;
    private final String compassName;
    private final List<String> compassLore;
    private final boolean applyKitOnStart;
    private final boolean endPortalRespawnEnabled;
    private final double endPortalRespawnRadius;
    private final ReportStorageFormat reportStorageFormat;
    private final boolean captureAdvancements;
    private final boolean webEnabled;
    private final int webPort;
    private final boolean deathstreaksEnabled;
    private final DeathstreakAttributionMode deathstreakAttributionMode;
    private final List<DeathstreakTier> deathstreakTiers;
    private final KeepInventoryMode endInventoryControlMode;

    private PeopleHuntConfig(
            int elapsedAnnouncementMinutes,
            boolean primeKeepPlayersFull,
            long playerPathSampleIntervalTicks,
            boolean autoSpectateNewJoins,
            long compassUpdateIntervalTicks,
            CompassDimensionMode compassDimensionMode,
            String compassName,
            List<String> compassLore,
            boolean applyKitOnStart,
            boolean endPortalRespawnEnabled,
            double endPortalRespawnRadius,
            ReportStorageFormat reportStorageFormat,
            boolean captureAdvancements,
            boolean webEnabled,
            int webPort,
            boolean deathstreaksEnabled,
            DeathstreakAttributionMode deathstreakAttributionMode,
            List<DeathstreakTier> deathstreakTiers,
            KeepInventoryMode endInventoryControlMode
    ) {
        this.elapsedAnnouncementMinutes = elapsedAnnouncementMinutes;
        this.primeKeepPlayersFull = primeKeepPlayersFull;
        this.playerPathSampleIntervalTicks = playerPathSampleIntervalTicks;
        this.autoSpectateNewJoins = autoSpectateNewJoins;
        this.compassUpdateIntervalTicks = compassUpdateIntervalTicks;
        this.compassDimensionMode = compassDimensionMode;
        this.compassName = compassName;
        this.compassLore = List.copyOf(compassLore);
        this.applyKitOnStart = applyKitOnStart;
        this.endPortalRespawnEnabled = endPortalRespawnEnabled;
        this.endPortalRespawnRadius = endPortalRespawnRadius;
        this.reportStorageFormat = reportStorageFormat;
        this.captureAdvancements = captureAdvancements;
        this.webEnabled = webEnabled;
        this.webPort = webPort;
        this.deathstreaksEnabled = deathstreaksEnabled;
        this.deathstreakAttributionMode = deathstreakAttributionMode;
        this.deathstreakTiers = List.copyOf(deathstreakTiers);
        this.endInventoryControlMode = endInventoryControlMode;
    }

    public static PeopleHuntConfig from(FileConfiguration config) {
        List<DeathstreakTier> tiers = new ArrayList<>();
        List<?> tierRaw = config.getList("deathstreaks.tiers", Collections.emptyList());
        for (Object element : tierRaw) {
            if (!(element instanceof ConfigurationSection section)) {
                continue;
            }
            tiers.add(DeathstreakTier.from(section));
        }
        if (tiers.isEmpty()) {
            ConfigurationSection deathstreaks = config.getConfigurationSection("deathstreaks");
            if (deathstreaks != null) {
                List<?> list = deathstreaks.getList("tiers", Collections.emptyList());
                for (Object candidate : list) {
                    if (candidate instanceof java.util.Map<?, ?> map) {
                        tiers.add(DeathstreakTier.fromMap(map));
                    }
                }
            }
        }
        return new PeopleHuntConfig(
                config.getInt("match.elapsed-announcement-minutes", 30),
                config.getBoolean("match.prime.keep-players-full", true),
                config.getLong("match.player-path-sample-interval-ticks", 20L),
                config.getBoolean("match.auto-spectate-new-joins", true),
                config.getLong("compass.update-interval-ticks", 10L),
                CompassDimensionMode.valueOf(config.getString("compass.dimension-mode", "LAST_KNOWN").toUpperCase()),
                config.getString("compass.item.name", "<light_purple>Hunter Compass"),
                config.getStringList("compass.item.lore"),
                config.getBoolean("kit.apply-on-start", true),
                config.getBoolean("end-portal-respawn.enabled", true),
                config.getDouble("end-portal-respawn.overworld-radius", 64.0),
                ReportStorageFormat.valueOf(config.getString("reporting.storage-format", "JSONL").toUpperCase()),
                config.getBoolean("reporting.capture-advancements", true),
                config.getBoolean("reporting.web.enabled", true),
                config.getInt("reporting.web.port", 18765),
                config.getBoolean("deathstreaks.enabled", true),
                parseAttributionMode(config.getString("deathstreaks.attribution-mode", "UUID_STRICT")),
                tiers,
                parseInventoryControlMode(config.getString("inventory-control.end-mode", "NONE"))
        );
    }

    private static DeathstreakAttributionMode parseAttributionMode(String raw) {
        try {
            return DeathstreakAttributionMode.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DeathstreakAttributionMode.UUID_STRICT;
        }
    }

    private static KeepInventoryMode parseInventoryControlMode(String raw) {
        try {
            KeepInventoryMode mode = KeepInventoryMode.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            return mode == KeepInventoryMode.INHERIT ? KeepInventoryMode.NONE : mode;
        } catch (IllegalArgumentException e) {
            return KeepInventoryMode.NONE;
        }
    }

    public int elapsedAnnouncementMinutes() {
        return elapsedAnnouncementMinutes;
    }

    public boolean primeKeepPlayersFull() {
        return primeKeepPlayersFull;
    }

    public long playerPathSampleIntervalTicks() {
        return playerPathSampleIntervalTicks;
    }

    public boolean autoSpectateNewJoins() {
        return autoSpectateNewJoins;
    }

    public long compassUpdateIntervalTicks() {
        return compassUpdateIntervalTicks;
    }

    public CompassDimensionMode compassDimensionMode() {
        return compassDimensionMode;
    }

    public String compassName() {
        return compassName;
    }

    public List<String> compassLore() {
        return compassLore;
    }

    public boolean applyKitOnStart() {
        return applyKitOnStart;
    }

    public boolean endPortalRespawnEnabled() {
        return endPortalRespawnEnabled;
    }

    public double endPortalRespawnRadius() {
        return endPortalRespawnRadius;
    }

    public ReportStorageFormat reportStorageFormat() {
        return reportStorageFormat;
    }

    public boolean captureAdvancements() {
        return captureAdvancements;
    }

    public boolean webEnabled() {
        return webEnabled;
    }

    public int webPort() {
        return webPort;
    }

    public boolean deathstreaksEnabled() {
        return deathstreaksEnabled;
    }

    public DeathstreakAttributionMode deathstreakAttributionMode() {
        return deathstreakAttributionMode;
    }

    public List<DeathstreakTier> deathstreakTiers() {
        return deathstreakTiers;
    }

    public KeepInventoryMode endInventoryControlMode() {
        return endInventoryControlMode;
    }

    /**
     * Controls which deaths are counted toward a hunter's deathstreak.
     *
     * <ul>
     *   <li>{@link #UUID_STRICT}    – only deaths where the resolved attribution UUID matches
     *                                  the runner's UUID. Most reliable; default.</li>
     *   <li>{@link #MESSAGE_STRICT} – only deaths where the death message text contains the
     *                                  runner's name. Catches edge cases not covered by UUID
     *                                  attribution but can false-positive on name substrings.</li>
     *   <li>{@link #EITHER}         – increment if either condition is met.</li>
     * </ul>
     */
    public enum DeathstreakAttributionMode {
        UUID_STRICT,
        MESSAGE_STRICT,
        EITHER
    }

    public record DeathstreakTier(
            int deaths,
            // Deprecated config compatibility: damage no longer resets hunter deathstreaks.
            double damageToReset,
            SaturationBoost saturationBoost,
            List<PotionGrant> potionGrants,
            List<ItemGrant> itemGrants
    ) {
        public static DeathstreakTier from(ConfigurationSection section) {
            ConfigurationSection saturation = section.getConfigurationSection("saturation");
            List<PotionGrant> potions = new ArrayList<>();
            for (Object item : section.getList("potion-effects", Collections.emptyList())) {
                if (item instanceof java.util.Map<?, ?> map) {
                    potions.add(PotionGrant.fromMap(map));
                }
            }
            List<ItemGrant> grants = new ArrayList<>();
            for (Object item : section.getList("items", Collections.emptyList())) {
                if (item instanceof java.util.Map<?, ?> map) {
                    grants.add(ItemGrant.fromMap(map));
                }
            }
            return new DeathstreakTier(
                    section.getInt("deaths", 0),
                    section.getDouble("damage-to-reset", 0.0),
                    new SaturationBoost(
                            saturation == null ? 20 : saturation.getInt("food", 20),
                            saturation == null ? 5.0f : (float) saturation.getDouble("saturation", 5.0)
                    ),
                    potions,
                    grants
            );
        }

        public static DeathstreakTier fromMap(java.util.Map<?, ?> map) {
            java.util.Map<?, ?> saturation = map.get("saturation") instanceof java.util.Map<?, ?> sat ? sat : Collections.emptyMap();
            List<PotionGrant> potions = new ArrayList<>();
            Object potionEffects = map.get("potion-effects");
            if (potionEffects instanceof List<?> list) {
                for (Object value : list) {
                    if (value instanceof java.util.Map<?, ?> potion) {
                        potions.add(PotionGrant.fromMap(potion));
                    }
                }
            }
            List<ItemGrant> grants = new ArrayList<>();
            Object items = map.get("items");
            if (items instanceof List<?> list) {
                for (Object value : list) {
                    if (value instanceof java.util.Map<?, ?> item) {
                        grants.add(ItemGrant.fromMap(item));
                    }
                }
            }
            return new DeathstreakTier(
                    intValue(map, "deaths", 0),
                    doubleValue(map, "damage-to-reset", 0.0),
                    new SaturationBoost(
                            intValue(saturation, "food", 20),
                            (float) doubleValue(saturation, "saturation", 5.0)
                    ),
                    potions,
                    grants
            );
        }
    }

    public record SaturationBoost(int foodLevel, float saturation) {}

    public record PotionGrant(PotionEffectType type, int durationSeconds, int amplifier) {
        public static PotionGrant fromMap(java.util.Map<?, ?> map) {
            String key = stringValue(map, "type", "SPEED");
            // TODO: replace getByName() with post-1.20.3 non-deprecated technique
            PotionEffectType effectType = PotionEffectType.getByName(key.toUpperCase());
            if (effectType == null) {
                effectType = PotionEffectType.SPEED;
            }
            return new PotionGrant(
                    effectType,
                    intValue(map, "duration-seconds", 30),
                    intValue(map, "amplifier", 0)
            );
        }
    }

    private static String stringValue(java.util.Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String out = String.valueOf(value);
        return out == null || out.isBlank() ? fallback : out;
    }

    private static int intValue(java.util.Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double doubleValue(java.util.Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public record ItemGrant(Material material, int amount) {
        public static ItemGrant fromMap(java.util.Map<?, ?> map) {
            Material material = Material.matchMaterial(stringValue(map, "material", "IRON_HELMET"));
            if (material == null) {
                material = Material.IRON_HELMET;
            }
            return new ItemGrant(material, intValue(map, "amount", 1));
        }
    }
}
