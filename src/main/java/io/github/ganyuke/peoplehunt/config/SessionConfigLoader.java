package io.github.ganyuke.peoplehunt.config;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakAttributionMode;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakOccupiedArmorMode;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.compass.CompassDimensionMode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Loads and saves {@code session-config.yml} without ever failing hard.
 */
public final class SessionConfigLoader {
    private static final String TEMPLATE_RESOURCE = "session-config.yml";

    private final JavaPlugin plugin;
    private final Logger logger;

    public SessionConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public SessionConfig load(File file) {
        SessionConfig defaults = SessionConfig.defaults();
        if (!file.exists()) {
            warnField("session-config.yml", "file missing; using hardcoded defaults.");
            return defaults;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception exception) {
            logger.log(Level.WARNING,
                    "Failed to parse session-config.yml; using hardcoded defaults.",
                    exception);
            return defaults;
        }

        SessionConfig.Builder builder = defaults.toBuilder();
        builder.primeKeepPlayersFull(readBoolean(yaml, "match.prime.keep-players-full", defaults.primeKeepPlayersFull()));
        builder.autoSpectateNewJoins(readBoolean(yaml, "match.auto-spectate-new-joins", defaults.autoSpectateNewJoins()));
        builder.elapsedAnnouncementMinutes(readNonNegativeInt(yaml, "match.elapsed-announcement-minutes", defaults.elapsedAnnouncementMinutes()));
        builder.kitApplyOnStart(readBoolean(yaml, "kit.apply-on-start", defaults.kitApplyOnStart()));
        builder.kitSelected(readNullableString(yaml, "kit.selected", defaults.kitSelected()));
        builder.inventoryControlMode(readEnum(yaml, "inventory-control.mode", KeepInventoryMode.class, defaults.inventoryControlMode(), true));
        builder.endInventoryControlMode(readEnum(yaml, "inventory-control.end-mode", KeepInventoryMode.class, defaults.endInventoryControlMode(), true));
        builder.compassDimensionMode(readEnum(yaml, "compass.dimension-mode", CompassDimensionMode.class, defaults.compassDimensionMode(), false));
        builder.deathstreaksEnabled(readBoolean(yaml, "deathstreaks.enabled", defaults.deathstreaksEnabled()));
        builder.deathstreakAttributionMode(readEnum(yaml, "deathstreaks.attribution-mode", DeathstreakAttributionMode.class, defaults.deathstreakAttributionMode(), false));
        builder.deathstreakOccupiedArmorMode(readEnum(yaml, "deathstreaks.occupied-armor-mode", DeathstreakOccupiedArmorMode.class, defaults.deathstreakOccupiedArmorMode(), false));
        builder.deathstreakTiers(readDeathstreakTiers(yaml, defaults.deathstreakTiers()));
        return builder.build();
    }

    public void generateDefault(File file) throws IOException {
        save(file, SessionConfig.defaults());
    }

    public void save(File file, SessionConfig config) throws IOException {
        java.nio.file.Path parent = file.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file.toPath(), render(config), StandardCharsets.UTF_8);
    }

    private boolean readBoolean(YamlConfiguration yaml, String path, boolean fallback) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String string) {
            if (string.equalsIgnoreCase("true") || string.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(string);
            }
        }
        warnField(path, "expected true or false; using default " + fallback + '.');
        return fallback;
    }

    private int readNonNegativeInt(YamlConfiguration yaml, String path, int fallback) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            int value = number.intValue();
            if (value >= 0) {
                return value;
            }
        } else if (raw instanceof String string) {
            try {
                int value = Integer.parseInt(string.trim());
                if (value >= 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        warnField(path, "expected a non-negative integer; using default " + fallback + '.');
        return fallback;
    }

    private @Nullable String readNullableString(YamlConfiguration yaml, String path, @Nullable String fallback) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof String string) {
            String value = string.trim();
            if (value.isEmpty() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("none")) {
                return null;
            }
            return value;
        }
        warnField(path, "expected a string or null; using default " + (fallback == null ? "null" : quoteScalar(fallback)) + '.');
        return fallback;
    }

    private <E extends Enum<E>> E readEnum(
            YamlConfiguration yaml,
            String path,
            Class<E> enumClass,
            E fallback,
            boolean disallowInherit
    ) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        try {
            E parsed = Enum.valueOf(enumClass, value.toUpperCase(Locale.ROOT));
            if (disallowInherit && "INHERIT".equals(parsed.name())) {
                throw new IllegalArgumentException("INHERIT is not allowed here.");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            warnField(path, "expected one of " + enumNames(enumClass, disallowInherit) + "; using default " + fallback.name() + '.');
            return fallback;
        }
    }

    private List<DeathstreakTier> readDeathstreakTiers(YamlConfiguration yaml, List<DeathstreakTier> fallback) {
        List<?> raw = yaml.getList("deathstreaks.tiers");
        if (raw == null) {
            return fallback;
        }
        try {
            List<DeathstreakTier> tiers = new ArrayList<>();
            for (Object entry : raw) {
                if (entry instanceof Map<?, ?> map) {
                    tiers.add(DeathstreakTier.fromMap(map));
                } else if (entry instanceof ConfigurationSection section) {
                    tiers.add(DeathstreakTier.from(section));
                } else {
                    throw new IllegalArgumentException("Tier entries must be maps.");
                }
            }
            return List.copyOf(tiers);
        } catch (RuntimeException exception) {
            warnField("deathstreaks.tiers", "could not parse tier list; using hardcoded defaults.");
            return fallback;
        }
    }

    private String render(SessionConfig config) {
        String template = loadTemplate();
        return template
                .replace("${match.prime.keep-players-full}", String.valueOf(config.primeKeepPlayersFull()))
                .replace("${match.auto-spectate-new-joins}", String.valueOf(config.autoSpectateNewJoins()))
                .replace("${match.elapsed-announcement-minutes}", Integer.toString(config.elapsedAnnouncementMinutes()))
                .replace("${kit.apply-on-start}", String.valueOf(config.kitApplyOnStart()))
                .replace("${kit.selected}", renderNullableScalar(config.kitSelected()))
                .replace("${inventory-control.mode}", config.inventoryControlMode().name())
                .replace("${inventory-control.end-mode}", config.endInventoryControlMode().name())
                .replace("${compass.dimension-mode}", config.compassDimensionMode().name())
                .replace("${deathstreaks.enabled}", String.valueOf(config.deathstreaksEnabled()))
                .replace("${deathstreaks.attribution-mode}", config.deathstreakAttributionMode().name())
                .replace("${deathstreaks.occupied-armor-mode}", config.deathstreakOccupiedArmorMode().name())
                .replace("${deathstreaks.tiers}", renderDeathstreakTiers(config.deathstreakTiers()));
    }

    private String loadTemplate() {
        try (InputStream stream = plugin.getResource(TEMPLATE_RESOURCE)) {
            if (stream == null) {
                return fallbackTemplate();
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Failed to read bundled session-config.yml template; using fallback template.", exception);
            return fallbackTemplate();
        }
    }

    private static String renderDeathstreakTiers(List<DeathstreakTier> tiers) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tiers.size(); i++) {
            DeathstreakTier tier = tiers.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("    - deaths: ").append(tier.deaths()).append('\n');
            builder.append("      saturation:\n");
            builder.append("        food: ").append(tier.saturationBoost().foodLevel()).append('\n');
            builder.append("        saturation: ").append(tier.saturationBoost().saturation()).append('\n');
            builder.append("      potion-effects:");
            if (tier.potionGrants().isEmpty()) {
                builder.append(" []\n");
            } else {
                builder.append('\n');
                tier.potionGrants().forEach(potion -> {
                    builder.append("        - type: ").append(potion.type().getKey().getKey().toUpperCase(Locale.ROOT)).append('\n');
                    builder.append("          duration-seconds: ").append(potion.durationSeconds()).append('\n');
                    builder.append("          amplifier: ").append(potion.amplifier()).append('\n');
                });
            }
            builder.append("      items:");
            if (tier.itemGrants().isEmpty()) {
                builder.append(" []\n");
            } else {
                builder.append('\n');
                tier.itemGrants().forEach(item -> {
                    builder.append("        - material: ").append(item.material().name()).append('\n');
                    builder.append("          amount: ").append(item.amount()).append('\n');
                });
            }
        }
        return builder.toString();
    }

    private static String renderNullableScalar(@Nullable String value) {
        return value == null ? "null" : quoteScalar(value);
    }

    private static String quoteScalar(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }

    private static String enumNames(Class<? extends Enum<?>> enumClass, boolean disallowInherit) {
        List<String> names = new ArrayList<>();
        for (Enum<?> constant : enumClass.getEnumConstants()) {
            if (disallowInherit && "INHERIT".equals(constant.name())) {
                continue;
            }
            names.add(constant.name());
        }
        return String.join(", ", names);
    }

    private void warnField(String field, String detail) {
        logger.warning("session-config.yml: " + field + " - " + detail);
    }

    private String fallbackTemplate() {
        return "# PeopleHunt session configuration.\n"
                + "# These settings apply to each match session.\n"
                + "# Edit this file directly, then run /ph settings reload to apply changes live.\n"
                + "# Run /ph settings save to write the current live session state to this file.\n\n"
                + "match:\n"
                + "  # Whether to keep hunters at full food during the prime (headstart) window.\n"
                + "  prime:\n"
                + "    keep-players-full: ${match.prime.keep-players-full}\n\n"
                + "  # Automatically place players who join mid-match into spectator mode.\n"
                + "  auto-spectate-new-joins: ${match.auto-spectate-new-joins}\n\n"
                + "  # How often (in minutes) to announce elapsed match time.\n"
                + "  # Set to 0 to disable announcements.\n"
                + "  elapsed-announcement-minutes: ${match.elapsed-announcement-minutes}\n\n"
                + "kit:\n"
                + "  # Whether to apply the selected kit automatically when the match starts.\n"
                + "  apply-on-start: ${kit.apply-on-start}\n\n"
                + "  # The identifier of the kit to apply on start. null = no kit.\n"
                + "  selected: ${kit.selected}\n\n"
                + "# Inventory control: how hunters' inventories are handled on death.\n"
                + "# NONE     - full vanilla drop\n"
                + "# KIT      - drop everything; active kit is restored on respawn\n"
                + "# KEEP     - keep full inventory across death\n"
                + "inventory-control:\n"
                + "  mode: ${inventory-control.mode}\n\n"
                + "  # When the runner first enters the End, inventory control is replaced with\n"
                + "  # this value. Set to the same value as `mode` for no End-specific behaviour.\n"
                + "  end-mode: ${inventory-control.end-mode}\n\n"
                + "compass:\n"
                + "  # How the hunter compass resolves the runner's position across dimensions.\n"
                + "  # VANILLA    - standard Minecraft compass behaviour\n"
                + "  # LAST_KNOWN - tracks the runner's last known position when in another dimension\n"
                + "  dimension-mode: ${compass.dimension-mode}\n\n"
                + "deathstreaks:\n"
                + "  # Whether deathstreak rewards are active.\n"
                + "  enabled: ${deathstreaks.enabled}\n\n"
                + "  # How runner-attributed deaths are counted for streak purposes.\n"
                + "  # UUID_STRICT    - death attribution UUID must match the runner's UUID (recommended)\n"
                + "  # MESSAGE_STRICT - death message text must contain the runner's name\n"
                + "  # EITHER         - either condition is sufficient\n"
                + "  attribution-mode: ${deathstreaks.attribution-mode}\n\n"
                + "  # What to do when a deathstreak armor grant targets a slot the hunter already has filled.\n"
                + "  # SKIP         - do not give the armor piece; keeps inventories tidy\n"
                + "  # GIVE_OR_DROP - try to add to inventory, drop if full\n"
                + "  occupied-armor-mode: ${deathstreaks.occupied-armor-mode}\n\n"
                + "  # Deathstreak reward tiers. Each tier fires once when a hunter reaches\n"
                + "  # the specified number of runner-attributed deaths.\n"
                + "  # Tiers are evaluated in order; only the first matching tier fires per death.\n"
                + "  tiers:\n"
                + "${deathstreaks.tiers}\n";
    }
}
