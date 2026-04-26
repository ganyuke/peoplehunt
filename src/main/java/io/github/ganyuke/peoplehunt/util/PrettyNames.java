package io.github.ganyuke.peoplehunt.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.advancement.Advancement;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

public final class PrettyNames {
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type STRING_MAP = TypeToken.getParameterized(Map.class, String.class, String.class).getType();
    private static final Map<String, String> TRANSLATIONS = new HashMap<>();
    private static final Map<String, String> RAW_KEY_OVERRIDES = new HashMap<>();
    private static final Map<String, String> ENUM_OVERRIDES = new HashMap<>();

    static {
        loadBundledEnglish();
        loadFallbacks();
        seedFallbacks();
    }

    private PrettyNames() {}

    public static String key(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unknown";
        }
        String normalized = raw.trim();
        String exact = RAW_KEY_OVERRIDES.get(normalized);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        for (String candidate : translationCandidates(normalized)) {
            String translated = TRANSLATIONS.get(candidate);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return words(stripNamespaceAndPath(normalized));
    }

    public static String enumName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unknown";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        String exact = ENUM_OVERRIDES.get(normalized);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        return words(raw);
    }

    public static String translate(String translationKey, String fallback, List<String> arguments) {
        if (translationKey == null || translationKey.isBlank()) {
            return fallback == null || fallback.isBlank() ? "" : fallback;
        }
        String pattern = TRANSLATIONS.get(translationKey);
        if (pattern == null || pattern.isBlank()) {
            if (fallback != null && !fallback.isBlank()) {
                return formatTemplate(fallback, arguments);
            }
            return key(translationKey);
        }
        return formatTemplate(pattern, arguments);
    }

    public static String item(ItemStack stack) {
        return SnapshotUtil.itemPrettyName(stack);
    }

    public static String effect(PotionEffectType type) {
        return type == null ? "Unknown" : key(type.getKey().asString());
    }

    public static String advancementTitle(Advancement advancement) {
        if (advancement == null) {
            return "Unknown Advancement";
        }
        AdvancementDisplay display = advancement.getDisplay();
        if (display != null) {
            String title = Text.plain(display.title()).trim();
            if (!title.isBlank()) {
                return title;
            }
            String displayName = Text.plain(display.displayName()).replace("[", "").replace("]", "").trim();
            if (!displayName.isBlank()) {
                return displayName;
            }
        }
        return key(advancement.getKey().asString());
    }

    public static Component advancementMessage(String playerName, String rawKey, Advancement advancement) {
        String pretty = advancementTitle(advancement);
        NamedTextColor accent = advancementColor(advancement);
        Component displayComponent = advancement == null ? null : advancement.displayName();
        if (displayComponent == null) {
            displayComponent = Component.text("[" + pretty + "]", accent);
        }
        displayComponent = displayComponent
                .colorIfAbsent(accent)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(rawKey == null ? pretty : rawKey, NamedTextColor.GRAY)));
        String lead = switch (advancementKindLabel(advancement)) {
            case "challenge" -> playerName + " has completed the challenge ";
            case "goal" -> playerName + " has reached the goal ";
            default -> playerName + " has made the advancement ";
        };
        return Component.text(lead).append(displayComponent);
    }

    public static String advancementKindLabel(Advancement advancement) {
        AdvancementDisplay.Frame frame = advancementFrame(advancement);
        if (frame == null) {
            return "advancement";
        }
        return switch (frame) {
            case CHALLENGE -> "challenge";
            case GOAL -> "goal";
            default -> "advancement";
        };
    }

    public static String advancementFrameColorHex(Advancement advancement) {
        AdvancementDisplay.Frame frame = advancementFrame(advancement);
        if (frame == null) {
            return "#55ff55";
        }
        return switch (frame) {
            case CHALLENGE -> "#ff55ff";
            case GOAL, TASK -> "#55ff55";
        };
    }

    private static NamedTextColor advancementColor(Advancement advancement) {
        AdvancementDisplay.Frame frame = advancementFrame(advancement);
        if (frame == null) {
            return NamedTextColor.GREEN;
        }
        return switch (frame) {
            case CHALLENGE -> NamedTextColor.LIGHT_PURPLE;
            case GOAL, TASK -> NamedTextColor.GREEN;
        };
    }

    private static AdvancementDisplay.Frame advancementFrame(Advancement advancement) {
        if (advancement == null || advancement.getDisplay() == null) {
            return null;
        }
        return advancement.getDisplay().frame();
    }

    private static void loadBundledEnglish() {
        try (InputStream stream = PrettyNames.class.getClassLoader().getResourceAsStream("lang/en_us.json")) {
            if (stream == null) {
                return;
            }
            Map<String, String> loaded = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), STRING_MAP);
            if (loaded != null) {
                loaded.forEach((key, value) -> {
                    if (key != null && value != null && !value.isBlank()) {
                        TRANSLATIONS.put(key, value);
                    }
                });
            }
        } catch (IOException ignored) {
            // Resource loading failure should not break the plugin; fallbacks remain available.
        }
    }

    private static void loadFallbacks() {
        try (InputStream stream = PrettyNames.class.getClassLoader().getResourceAsStream("lang/report_fallbacks.json")) {
            if (stream == null) {
                return;
            }
            ReportFallbacks loaded = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), ReportFallbacks.class);
            if (loaded == null) {
                return;
            }
            if (loaded.rawKeys != null) {
                loaded.rawKeys.forEach((key, value) -> {
                    if (key != null && value != null && !value.isBlank()) {
                        RAW_KEY_OVERRIDES.put(key, value);
                    }
                });
            }
            if (loaded.enums != null) {
                loaded.enums.forEach((key, value) -> {
                    if (key != null && value != null && !value.isBlank()) {
                        ENUM_OVERRIDES.put(key.toUpperCase(Locale.ROOT), value);
                    }
                });
            }
        } catch (IOException ignored) {
            // Resource loading failure should not break the plugin; fallbacks remain available.
        }
    }

    private static void seedFallbacks() {
        RAW_KEY_OVERRIDES.putIfAbsent("minecraft:overworld", "Overworld");
        RAW_KEY_OVERRIDES.putIfAbsent("minecraft:the_nether", "Nether");
        RAW_KEY_OVERRIDES.putIfAbsent("minecraft:the_end", "The End");

        ENUM_OVERRIDES.putIfAbsent("SPECTATOR", "Spectator");
        ENUM_OVERRIDES.putIfAbsent("SURVIVAL", "Survival");
        ENUM_OVERRIDES.putIfAbsent("CREATIVE", "Creative");
        ENUM_OVERRIDES.putIfAbsent("ADVENTURE", "Adventure");
        ENUM_OVERRIDES.putIfAbsent("PLUGIN", "Plugin");
        ENUM_OVERRIDES.putIfAbsent("COMMAND", "Command");
        ENUM_OVERRIDES.putIfAbsent("UNKNOWN", "Unknown");
    }

    private static List<String> translationCandidates(String raw) {
        List<String> candidates = new ArrayList<>();
        String normalized = raw.trim();
        candidates.add(normalized);
        if (!normalized.contains(":")) {
            return candidates;
        }
        String namespaced = normalized.replace(':', '.').replace('/', '.');
        candidates.add("item." + namespaced);
        candidates.add("block." + namespaced);
        candidates.add("entity." + namespaced);
        candidates.add("effect." + namespaced);
        candidates.add("dimension." + namespaced);
        candidates.add("enchantment." + namespaced);
        candidates.add("biome." + namespaced);
        return candidates;
    }

    private static String stripNamespaceAndPath(String raw) {
        String value = raw;
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        return value;
    }

    private static String formatTemplate(String template, List<String> arguments) {
        if (template == null || template.isBlank()) {
            return "";
        }
        List<String> args = arguments == null ? List.of() : arguments;
        if (!template.contains("%")) {
            return template;
        }
        StringBuilder out = new StringBuilder();
        int autoIndex = 0;
        for (int i = 0; i < template.length(); i++) {
            char ch = template.charAt(i);
            if (ch != '%') {
                out.append(ch);
                continue;
            }
            if (i + 1 < template.length() && template.charAt(i + 1) == '%') {
                out.append('%');
                i++;
                continue;
            }
            int j = i + 1;
            int explicitIndex = -1;
            while (j < template.length() && Character.isDigit(template.charAt(j))) {
                j++;
            }
            if (j > i + 1 && j < template.length() && template.charAt(j) == '$') {
                explicitIndex = Integer.parseInt(template.substring(i + 1, j)) - 1;
                j++;
            }
            if (j < template.length()) {
                char specifier = template.charAt(j);
                if (specifier == 's' || specifier == 'd' || specifier == 'f') {
                    int argumentIndex = explicitIndex >= 0 ? explicitIndex : autoIndex++;
                    String replacement = argumentIndex >= 0 && argumentIndex < args.size() ? args.get(argumentIndex) : "";
                    out.append(replacement);
                    i = j;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String words(String raw) {
        String normalized = raw.replace('-', '_').replace('/', '_').replace('.', '_');
        String[] parts = normalized.split("_+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            String upper = part.toUpperCase(Locale.ROOT);
            if (upper.length() <= 3 && upper.equals(part.toUpperCase(Locale.ROOT))) {
                builder.append(upper);
            } else {
                builder.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    builder.append(part.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return builder.isEmpty() ? raw : builder.toString();
    }

    private static final class ReportFallbacks {
        Map<String, String> rawKeys = Map.of();
        Map<String, String> enums = Map.of();
    }
}
