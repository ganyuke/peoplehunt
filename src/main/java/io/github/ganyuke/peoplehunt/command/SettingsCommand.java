package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.SessionConfig;
import io.github.ganyuke.peoplehunt.config.SessionConfigLoader;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.compass.CompassDimensionMode;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Handles /ph settings.
 */
public final class SettingsCommand {
    private final MatchManager matchManager;
    private final KitService kitService;
    private final SessionConfigLoader sessionConfigLoader;
    private final File sessionConfigFile;

    public SettingsCommand(MatchManager matchManager, KitService kitService, SessionConfigLoader sessionConfigLoader, File sessionConfigFile) {
        this.matchManager = matchManager;
        this.kitService = kitService;
        this.sessionConfigLoader = sessionConfigLoader;
        this.sessionConfigFile = sessionConfigFile;
    }

    public boolean handle(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /peoplehunt settings <list|save|reload|set>", NamedTextColor.RED));
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> handleList(sender);
            case "save" -> handleSave(sender);
            case "reload" -> handleReload(sender);
            case "set" -> handleSet(sender, args);
            default -> {
                sender.sendMessage(Component.text("Unknown settings action. Valid: list, save, reload, set.", NamedTextColor.RED));
                yield true;
            }
        };
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return filter(List.of("list", "save", "reload", "set"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return filter(List.of("match", "kit", "inventory-control", "compass", "deathstreaks"), args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            return filter(keysForSection(args[2]), args[3]);
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("set")) {
            return filter(valuesFor(args[2], args[3]), args[4]);
        }
        return List.of();
    }

    private boolean handleList(org.bukkit.command.CommandSender sender) {
        SessionConfig config = matchManager.getSessionConfig();
        sendSection(sender, "match");
        sendEntry(sender, "match.prime.keep-players-full", String.valueOf(config.primeKeepPlayersFull()));
        sendEntry(sender, "match.auto-spectate-new-joins", String.valueOf(config.autoSpectateNewJoins()));
        sendEntry(sender, "match.elapsed-announcement-minutes", Integer.toString(config.elapsedAnnouncementMinutes()));

        sendSection(sender, "kit");
        sendEntry(sender, "kit.apply-on-start", String.valueOf(config.kitApplyOnStart()));
        sendEntry(sender, "kit.selected", config.kitSelected() == null ? "none" : config.kitSelected());

        sendSection(sender, "inventory-control");
        sendEntry(sender, "inventory-control.mode", config.inventoryControlMode().name());
        sendEntry(sender, "inventory-control.end-mode", config.endInventoryControlMode().name());

        sendSection(sender, "compass");
        sendEntry(sender, "compass.dimension-mode", config.compassDimensionMode().name());

        sendSection(sender, "deathstreaks");
        sendEntry(sender, "deathstreaks.enabled", String.valueOf(config.deathstreaksEnabled()));
        sendEntry(sender, "deathstreaks.attribution-mode", config.deathstreakAttributionMode().name());
        sendEntry(sender, "deathstreaks.occupied-armor-mode", config.deathstreakOccupiedArmorMode().name());
        sendEntry(sender, "deathstreaks.tiers", config.deathstreakTiers().size() + " tiers configured");
        return true;
    }

    private boolean handleSave(org.bukkit.command.CommandSender sender) {
        try {
            sessionConfigLoader.save(sessionConfigFile, matchManager.getSessionConfig());
        } catch (IOException exception) {
            matchManager.getPlugin().getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Failed to save session-config.yml to " + sessionConfigFile.getAbsolutePath(),
                    exception
            );
            sender.sendMessage(Component.text("Failed to save session-config.yml. See console for details.", NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("Session config saved to session-config.yml.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleReload(org.bukkit.command.CommandSender sender) {
        if (!sessionConfigFile.exists()) {
            try {
                sessionConfigLoader.generateDefault(sessionConfigFile);
            } catch (IOException exception) {
                matchManager.getPlugin().getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Failed to regenerate missing session-config.yml at " + sessionConfigFile.getAbsolutePath(),
                        exception
                );
                sender.sendMessage(Component.text("Failed to regenerate session-config.yml. See console for details.", NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("session-config.yml was missing and has been regenerated from defaults.", NamedTextColor.YELLOW));
        }
        SessionConfig previous = matchManager.getSessionConfig();
        SessionConfig updated = sessionConfigLoader.load(sessionConfigFile);
        matchManager.applySessionConfig(updated);
        sender.sendMessage(Component.text("Session config reloaded from session-config.yml.", NamedTextColor.GREEN));
        emitChangedSettingMessages(sender, previous, updated, true);
        return true;
    }

    private boolean handleSet(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("Usage: /peoplehunt settings set <section> <key> <value>", NamedTextColor.RED));
            return true;
        }

        String section = args[2].toLowerCase(Locale.ROOT);
        String key = args[3].toLowerCase(Locale.ROOT);
        String value = args[4];

        SessionConfig previous = matchManager.getSessionConfig();
        SessionConfig.Builder builder = previous.toBuilder();

        switch (section) {
            case "match" -> {
                switch (key) {
                    case "prime-keep-players-full" -> builder.primeKeepPlayersFull(parseBoolean(value));
                    case "auto-spectate-new-joins" -> builder.autoSpectateNewJoins(parseBoolean(value));
                    case "elapsed-announcement-minutes" -> builder.elapsedAnnouncementMinutes(parseNonNegativeInt(value));
                    default -> {
                        sender.sendMessage(Component.text("Unknown match setting '" + key + "'.", NamedTextColor.RED));
                        return true;
                    }
                }
            }
            case "kit" -> {
                switch (key) {
                    case "apply-on-start" -> builder.kitApplyOnStart(parseBoolean(value));
                    case "selected" -> builder.kitSelected(parseKitSelection(value));
                    default -> {
                        sender.sendMessage(Component.text("Unknown kit setting '" + key + "'.", NamedTextColor.RED));
                        return true;
                    }
                }
            }
            case "inventory-control" -> {
                switch (key) {
                    case "mode" -> builder.inventoryControlMode(parseInventoryMode(value));
                    case "end-mode" -> builder.endInventoryControlMode(parseInventoryMode(value));
                    default -> {
                        sender.sendMessage(Component.text("Unknown inventory-control setting '" + key + "'.", NamedTextColor.RED));
                        return true;
                    }
                }
            }
            case "compass" -> {
                if (!"dimension-mode".equals(key)) {
                    sender.sendMessage(Component.text("Unknown compass setting '" + key + "'.", NamedTextColor.RED));
                    return true;
                }
                builder.compassDimensionMode(parseEnum(value, CompassDimensionMode.class));
            }
            case "deathstreaks" -> {
                switch (key) {
                    case "enabled" -> builder.deathstreaksEnabled(parseBoolean(value));
                    case "attribution-mode" -> builder.deathstreakAttributionMode(parseEnum(value, PeopleHuntConfig.DeathstreakAttributionMode.class));
                    case "occupied-armor-mode" -> builder.deathstreakOccupiedArmorMode(parseEnum(value, PeopleHuntConfig.DeathstreakOccupiedArmorMode.class));
                    default -> {
                        sender.sendMessage(Component.text("Unknown deathstreaks setting '" + key + "'.", NamedTextColor.RED));
                        return true;
                    }
                }
            }
            default -> {
                sender.sendMessage(Component.text("Unknown settings section '" + section + "'.", NamedTextColor.RED));
                return true;
            }
        }

        SessionConfig updated = builder.build();
        matchManager.applySessionConfig(updated);
        boolean sentChangeMessage = emitChangedSettingMessages(sender, previous, updated, false);
        if (previous.equals(updated)) {
            sender.sendMessage(Component.text("Setting unchanged.", NamedTextColor.YELLOW));
        } else if (!sentChangeMessage) {
            sender.sendMessage(Component.text("Setting updated.", NamedTextColor.GREEN));
        }
        return true;
    }

    private boolean emitChangedSettingMessages(org.bukkit.command.CommandSender sender, SessionConfig previous, SessionConfig updated, boolean reload) {
        boolean sent = false;
        if (previous.elapsedAnnouncementMinutes() != updated.elapsedAnnouncementMinutes()) {
            String message = matchManager.hasActiveMatch() ? matchManager.scheduleElapsedAnnouncementsForCurrentSession() : null;
            if (message != null && !message.isBlank()) {
                sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
                sent = true;
            }
        }
        if (previous.compassDimensionMode() != updated.compassDimensionMode()) {
            sender.sendMessage(Component.text("Compass dimension mode updated. Takes effect on next compass tick.", NamedTextColor.GREEN));
            sent = true;
        }
        if (!equals(previous.kitSelected(), updated.kitSelected())) {
            sender.sendMessage(Component.text("Kit selection updated.", NamedTextColor.GREEN));
            sent = true;
            if (matchManager.hasActiveMatch() && updated.kitApplyOnStart()) {
                sender.sendMessage(Component.text("Hunters will receive the new kit on next respawn.", NamedTextColor.GREEN));
            sent = true;
            }
        }
        if (previous.kitApplyOnStart() != updated.kitApplyOnStart()) {
            sender.sendMessage(Component.text("Kit apply-on-start updated. Takes effect at next match start.", NamedTextColor.GREEN));
            sent = true;
        }
        if (previous.inventoryControlMode() != updated.inventoryControlMode()
                || previous.endInventoryControlMode() != updated.endInventoryControlMode()) {
            sender.sendMessage(Component.text("Inventory control mode updated. Applies on next death.", NamedTextColor.GREEN));
            sent = true;
        }
        if (previous.deathstreaksEnabled() != updated.deathstreaksEnabled()) {
            sender.sendMessage(Component.text(
                    updated.deathstreaksEnabled() ? "Deathstreaks enabled." : "Deathstreaks disabled.",
                    NamedTextColor.GREEN));
            sent = true;
        }
        if (previous.deathstreakAttributionMode() != updated.deathstreakAttributionMode()) {
            sender.sendMessage(Component.text("Deathstreak attribution mode updated.", NamedTextColor.GREEN));
            sent = true;
        }
        if (previous.deathstreakOccupiedArmorMode() != updated.deathstreakOccupiedArmorMode()) {
            sender.sendMessage(Component.text("Deathstreak occupied-armor mode updated.", NamedTextColor.GREEN));
            sent = true;
        }
        if (previous.autoSpectateNewJoins() != updated.autoSpectateNewJoins()) {
            sender.sendMessage(Component.text(
                    updated.autoSpectateNewJoins() ? "Auto-spectate new joins enabled." : "Auto-spectate new joins disabled.",
                    NamedTextColor.GREEN));
            sent = true;
        }
        if (previous.primeKeepPlayersFull() != updated.primeKeepPlayersFull()) {
            sender.sendMessage(Component.text(
                    updated.primeKeepPlayersFull() ? "Prime keep-players-full enabled." : "Prime keep-players-full disabled.",
                    NamedTextColor.GREEN));
            sent = true;
        }
        if (reload && !previous.deathstreakTiers().equals(updated.deathstreakTiers())) {
            sender.sendMessage(Component.text("Deathstreak tiers reloaded. Existing death counts unaffected.", NamedTextColor.GREEN));
            sent = true;
        }
        return sent;
    }

    private static List<String> keysForSection(String section) {
        return switch (section.toLowerCase(Locale.ROOT)) {
            case "match" -> List.of("prime-keep-players-full", "auto-spectate-new-joins", "elapsed-announcement-minutes");
            case "kit" -> List.of("apply-on-start", "selected");
            case "inventory-control" -> List.of("mode", "end-mode");
            case "compass" -> List.of("dimension-mode");
            case "deathstreaks" -> List.of("enabled", "attribution-mode", "occupied-armor-mode");
            default -> List.of();
        };
    }

    private List<String> valuesFor(String section, String key) {
        return switch (section.toLowerCase(Locale.ROOT)) {
            case "match" -> switch (key.toLowerCase(Locale.ROOT)) {
                case "prime-keep-players-full", "auto-spectate-new-joins" -> List.of("true", "false");
                case "elapsed-announcement-minutes" -> List.of("0", "15", "30", "60");
                default -> List.of();
            };
            case "kit" -> switch (key.toLowerCase(Locale.ROOT)) {
                case "apply-on-start" -> List.of("true", "false");
                case "selected" -> {
                    List<String> values = new ArrayList<>(kitService.identifiers());
                    values.add("none");
                    yield values;
                }
                default -> List.of();
            };
            case "inventory-control" -> List.of("NONE", "KIT", "KEEP");
            case "compass" -> List.of("VANILLA", "LAST_KNOWN");
            case "deathstreaks" -> switch (key.toLowerCase(Locale.ROOT)) {
                case "enabled" -> List.of("true", "false");
                case "attribution-mode" -> List.of("UUID_STRICT", "MESSAGE_STRICT", "EITHER");
                case "occupied-armor-mode" -> List.of("SKIP", "GIVE_OR_DROP");
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private static boolean parseBoolean(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("Expected true or false.");
    }

    private static int parseNonNegativeInt(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a non-negative integer.");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("Expected a non-negative integer.");
        }
        return parsed;
    }

    private String parseKitSelection(String value) {
        if (value.equalsIgnoreCase("none")) {
            return null;
        }
        if (kitService.get(value).isPresent()) {
            return value;
        }
        throw new IllegalArgumentException("Unknown kit '" + value + "'.");
    }

    private static KeepInventoryMode parseInventoryMode(String value) {
        KeepInventoryMode mode = parseEnum(value, KeepInventoryMode.class);
        if (mode == KeepInventoryMode.INHERIT) {
            throw new IllegalArgumentException("INHERIT is not a valid inventory control mode here.");
        }
        return mode;
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass) {
        try {
            return Enum.valueOf(enumClass, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid value '" + value + "'.");
        }
    }

    private static void sendSection(org.bukkit.command.CommandSender sender, String title) {
        sender.sendMessage(Component.text(title + ':', NamedTextColor.GOLD));
    }

    private static void sendEntry(org.bukkit.command.CommandSender sender, String key, String value) {
        sender.sendMessage(Component.text(key + ": ", NamedTextColor.YELLOW)
                .append(Component.text(value, NamedTextColor.AQUA)));
    }

    private static List<String> filter(List<String> values, String partial) {
        String lowered = partial.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }

    private static boolean equals(Object left, Object right) {
        return java.util.Objects.equals(left, right);
    }
}
