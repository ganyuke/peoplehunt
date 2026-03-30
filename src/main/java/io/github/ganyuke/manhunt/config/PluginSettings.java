package io.github.ganyuke.manhunt.config;

import io.github.ganyuke.manhunt.analytics.MilestoneDefinition;
import io.github.ganyuke.manhunt.analytics.MilestoneType;
import io.github.ganyuke.manhunt.game.DimensionTrackingMode;
import io.github.ganyuke.manhunt.game.EndResolutionPolicy;
import io.github.ganyuke.manhunt.game.Role;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record PluginSettings(
        String prefix,
        boolean freezeDuringPrime,
        boolean startOnRunnerMove,
        boolean quitAsHunterWin,
        boolean autoGiveCompassesOnStart,
        EndResolutionPolicy endResolutionPolicy,
        boolean compassEnabled,
        int compassRefreshTicks,
        double compassMinimumTargetChangeDistance,
        DimensionTrackingMode compassTrackingMode,
        String compassTitle,
        List<String> compassLore,
        int surroundDefaultRadius,
        int surroundMinRadius,
        int surroundMaxRadius,
        int surroundSafeSearchRadius,
        int surroundSafeSearchVertical,
        boolean notificationsEnabled,
        int notificationCheckTicks,
        int notificationIntervalMinutes,
        boolean analyticsEnabled,
        String analyticsFolder,
        double sampleDistance,
        int sampleIntervalTicks,
        int healthSampleIntervalTicks,
        int flushIntervalTicks,
        String mapPublisherMode,
        String mapViewerDirectoryName,
        boolean catchupEnabled,
        double meaningfulDamageThreshold,
        int contributionWindowSeconds,
        boolean endRallyEnabled,
        int endRallyRadius,
        List<CatchupRule> catchupRules,
        Map<String, List<KitItem>> kits,
        boolean milestonesEnabled,
        int milestoneScanIntervalTicks,
        List<MilestoneDefinition> milestones
) {
    public record CatchupRule(int deaths, boolean keepInventory, String kitId, boolean endRally) {
    }

    public record KitItem(Material material, int amount) {
    }

    public static PluginSettings from(FileConfiguration config, YamlConfiguration milestoneConfig) {
        String prefix = config.getString("messages.prefix", "&6[Manhunt]&r ");

        boolean freezeDuringPrime = config.getBoolean("game.freeze-during-prime", false);
        boolean startOnRunnerMove = config.getBoolean("game.start-on-runner-move", true);
        boolean quitAsHunterWin = config.getBoolean("game.quit-as-hunter-win", true);
        boolean autoGiveCompassesOnStart = config.getBoolean("game.auto-give-compasses-on-start", true);
        EndResolutionPolicy endResolutionPolicy = parseEnum(
                config.getString("game.simultaneous-end-policy", EndResolutionPolicy.FIRST_EVENT.name()),
                EndResolutionPolicy.class,
                EndResolutionPolicy.FIRST_EVENT
        );

        boolean compassEnabled = config.getBoolean("compass.enabled", true);
        int compassRefreshTicks = Math.max(1, config.getInt("compass.refresh-ticks", 40));
        double compassMinimumTargetChangeDistance = Math.max(0.5D, config.getDouble("compass.minimum-target-change-distance", 8.0D));
        DimensionTrackingMode compassMode = parseEnum(
                config.getString("compass.mode", DimensionTrackingMode.PORTAL_ANCHOR.name()),
                DimensionTrackingMode.class,
                DimensionTrackingMode.PORTAL_ANCHOR
        );
        String compassTitle = config.getString("compass.title", "&bRunner Tracker");
        List<String> compassLore = List.copyOf(config.getStringList("compass.lore"));

        int surroundDefaultRadius = config.getInt("surround.default-radius", 8);
        int surroundMinRadius = config.getInt("surround.min-radius", 4);
        int surroundMaxRadius = config.getInt("surround.max-radius", 32);
        int surroundSafeSearchRadius = config.getInt("surround.safe-search-radius", 6);
        int surroundSafeSearchVertical = config.getInt("surround.safe-search-vertical", 8);

        boolean notificationsEnabled = config.getBoolean("notifications.enabled", true);
        int notificationCheckTicks = Math.max(1, config.getInt("notifications.check-ticks", 100));
        int notificationIntervalMinutes = Math.max(1, config.getInt("notifications.interval-minutes", 30));

        boolean analyticsEnabled = config.getBoolean("analytics.enabled", true);
        String analyticsFolder = config.getString("analytics.folder", "analytics");
        double sampleDistance = Math.max(0.5D, config.getDouble("analytics.sample-distance", 6.0D));
        int sampleIntervalTicks = Math.max(1, config.getInt("analytics.sample-interval-ticks", 20));
        int healthSampleIntervalTicks = Math.max(1, config.getInt("analytics.health-sample-interval-ticks", 40));
        int flushIntervalTicks = Math.max(1, config.getInt("analytics.flush-interval-ticks", 200));

        String mapPublisherMode = config.getString("map.publisher", "STATIC_WEB");
        String mapViewerDirectoryName = config.getString("map.viewer-directory", "viewer");

        boolean catchupEnabled = config.getBoolean("catchup.enabled", true);
        double meaningfulDamageThreshold = Math.max(0.0D, config.getDouble("catchup.meaningful-damage-threshold", 4.0D));
        int contributionWindowSeconds = Math.max(1, config.getInt("catchup.contribution-window-seconds", 180));
        boolean endRallyEnabled = config.getBoolean("catchup.end-rally.enabled", true);
        int endRallyRadius = Math.max(1, config.getInt("catchup.end-rally.radius", 12));

        List<CatchupRule> catchupRules = parseCatchupRules(config.getConfigurationSection("catchup"));
        Map<String, List<KitItem>> kits = parseKits(config.getConfigurationSection("catchup.kits"));

        boolean milestonesEnabled = true;
        int milestoneScanIntervalTicks = Math.max(1, milestoneConfig.getInt("scan-interval-ticks", 20));
        List<MilestoneDefinition> milestones = parseMilestones(milestoneConfig.getConfigurationSection("milestones"));

        return new PluginSettings(
                prefix,
                freezeDuringPrime,
                startOnRunnerMove,
                quitAsHunterWin,
                autoGiveCompassesOnStart,
                endResolutionPolicy,
                compassEnabled,
                compassRefreshTicks,
                compassMinimumTargetChangeDistance,
                compassMode,
                compassTitle,
                compassLore,
                surroundDefaultRadius,
                surroundMinRadius,
                surroundMaxRadius,
                surroundSafeSearchRadius,
                surroundSafeSearchVertical,
                notificationsEnabled,
                notificationCheckTicks,
                notificationIntervalMinutes,
                analyticsEnabled,
                analyticsFolder,
                sampleDistance,
                sampleIntervalTicks,
                healthSampleIntervalTicks,
                flushIntervalTicks,
                mapPublisherMode,
                mapViewerDirectoryName,
                catchupEnabled,
                meaningfulDamageThreshold,
                contributionWindowSeconds,
                endRallyEnabled,
                endRallyRadius,
                List.copyOf(catchupRules),
                Map.copyOf(kits),
                milestonesEnabled,
                milestoneScanIntervalTicks,
                List.copyOf(milestones)
        );
    }

    private static List<CatchupRule> parseCatchupRules(ConfigurationSection catchupSection) {
        if (catchupSection == null) {
            return Collections.emptyList();
        }
        List<Map<?, ?>> rawRules = catchupSection.getMapList("rules");
        List<CatchupRule> rules = new ArrayList<>();
        for (Map<?, ?> rawRule : rawRules) {
            int deaths = parseInt(rawRule.get("deaths"), 0);
            boolean keepInventory = parseBoolean(rawRule.get("keep-inventory"), false);
            String kitId = rawRule.get("kit") == null ? null : String.valueOf(rawRule.get("kit"));
            boolean endRally = parseBoolean(rawRule.get("end-rally"), false);
            if (deaths > 0) {
                rules.add(new CatchupRule(deaths, keepInventory, kitId, endRally));
            }
        }
        rules.sort(Comparator.comparingInt(CatchupRule::deaths));
        return rules;
    }

    private static Map<String, List<KitItem>> parseKits(ConfigurationSection kitsSection) {
        if (kitsSection == null) {
            return Collections.emptyMap();
        }
        Map<String, List<KitItem>> kits = new LinkedHashMap<>();
        for (String key : kitsSection.getKeys(false)) {
            ConfigurationSection kitSection = kitsSection.getConfigurationSection(key);
            if (kitSection == null) {
                continue;
            }
            List<KitItem> items = new ArrayList<>();
            for (String rawItem : kitSection.getStringList("items")) {
                String[] parts = rawItem.trim().split("\\s+");
                if (parts.length == 0) {
                    continue;
                }
                Material material = parseMaterial(parts[0]);
                if (material == null) {
                    continue;
                }
                int amount = parts.length >= 2 ? parseInt(parts[1], 1) : 1;
                items.add(new KitItem(material, Math.max(1, amount)));
            }
            kits.put(key, List.copyOf(items));
        }
        return kits;
    }

    private static List<MilestoneDefinition> parseMilestones(ConfigurationSection milestonesSection) {
        if (milestonesSection == null) {
            return Collections.emptyList();
        }
        List<MilestoneDefinition> milestones = new ArrayList<>();
        for (String key : milestonesSection.getKeys(false)) {
            ConfigurationSection section = milestonesSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            boolean enabled = section.getBoolean("enabled", true);
            String title = section.getString("title", key);
            MilestoneType type = parseEnum(section.getString("type", MilestoneType.INVENTORY_ANY.name()), MilestoneType.class, MilestoneType.INVENTORY_ANY);
            Set<Role> roles = parseRoles(section.getStringList("roles"));
            Set<Material> materials = parseMaterials(section.getStringList("materials"));
            int amount = Math.max(1, section.getInt("amount", 1));
            World.Environment environment = parseEnum(section.getString("environment", "NORMAL"), World.Environment.class, World.Environment.NORMAL);
            milestones.add(new MilestoneDefinition(key, title, enabled, type, roles, materials, amount, environment));
        }
        return milestones;
    }

    private static Set<Role> parseRoles(List<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) {
            return EnumSet.of(Role.RUNNER, Role.HUNTER);
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String rawRole : rawRoles) {
            Role role = parseEnum(rawRole, Role.class, Role.NONE);
            if (role != Role.NONE) {
                roles.add(role);
            }
        }
        return roles.isEmpty() ? EnumSet.of(Role.RUNNER, Role.HUNTER) : roles;
    }

    private static Set<Material> parseMaterials(List<String> rawMaterials) {
        if (rawMaterials == null || rawMaterials.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Material> materials = new LinkedHashSet<>();
        for (String rawMaterial : rawMaterials) {
            Material material = parseMaterial(rawMaterial);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    private static Material parseMaterial(String rawMaterial) {
        try {
            return Material.valueOf(rawMaterial.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static int parseInt(Object rawValue, int fallback) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(rawValue));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static boolean parseBoolean(Object rawValue, boolean fallback) {
        if (rawValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (rawValue == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(rawValue));
    }
}
