package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchManager.PrepareMode;
import io.github.ganyuke.peoplehunt.report.ReportModels.IndexEntry;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.report.ViewerAssets;
import io.github.ganyuke.peoplehunt.util.SelectorUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Administrative command surface for the plugin.
 *
 * <p>Most subcommands mutate or inspect {@link MatchManager}. The lone exception is
 * {@code /peoplehunt portal}, which is intentionally left available to non-admin players because it
 * services a one-shot respawn prompt during an active match.
 */
public final class PeopleHuntCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PREPARE_MODE_SUGGESTIONS = List.of("health", "status", "xp", "inventory");
    private static final List<String> ROLLBACK_FLAGS = List.of("--tp", "--gamemode", "--no-effects");

    private final MatchManager matchManager;
    private final CompassService compassService;
    private final KitService kitService;
    private final ReportService reportService;
    private final ViewerAssets viewerAssets;

    public PeopleHuntCommand(MatchManager matchManager, CompassService compassService, KitService kitService, ReportService reportService, ViewerAssets viewerAssets) {
        this.matchManager = matchManager;
        this.compassService = compassService;
        this.kitService = kitService;
        this.reportService = reportService;
        this.viewerAssets = viewerAssets;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase();
        try {
            // portal is intentionally exempt from the admin gate.
            if (subcommand.equals("portal")) {
                return handlePortal(sender);
            }
            if (!isAdmin(sender)) {
                sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
                return true;
            }
            return switch (subcommand) {
                case "start"                               -> handleStart(sender);
                case "stop"                                -> handleStop(sender);
                case "prime"                               -> handlePrime(sender, args);
                case "prepare"                             -> handlePrepare(sender, args);
                case "runner"                              -> handleRunner(sender, args);
                case "hunter"                              -> handleHunter(sender, args);
                case "status"                              -> handleStatus(sender);
                case "surround"                            -> handleSurround(sender, args);
                case "compass"                             -> handleCompass(sender, args);
                case "kit"                                 -> handleKit(sender, args);
                case "inventorycontrol", "ic"              -> handleInventoryControl(sender, args);
                case "aar"                                 -> handleAar(sender, args);
                case "rollback"                            -> handleRollback(sender, args);
                default -> {
                    sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
                    yield true;
                }
            };
        } catch (IllegalStateException exception) {
            sender.sendMessage(Component.text(exception.getMessage(), NamedTextColor.RED));
            return true;
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text("Invalid argument: " + exception.getMessage(), NamedTextColor.RED));
            return true;
        } catch (IOException exception) {
            sender.sendMessage(Component.text("I/O error: " + exception.getMessage(), NamedTextColor.RED));
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Subcommand handlers
    // -------------------------------------------------------------------------

    private boolean handleStart(CommandSender sender) throws IOException {
        matchManager.startNow();
        // match manager handles the manhunt start message
        return true;
    }

    private boolean handleStop(CommandSender sender) throws IOException {
        matchManager.stopInconclusive();
        sender.sendMessage(Component.text("Match force-stopped.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handlePrime(CommandSender sender, String[] args) {
        Boolean keepFull = args.length >= 2 ? Boolean.parseBoolean(args[1]) : null;
        // prime() handles participant notification including the runner's heads-up message.
        matchManager.prime(keepFull);
        sender.sendMessage(Component.text("Match primed — waiting for runner to move.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handlePrepare(CommandSender sender, String[] args) {
        EnumSet<PrepareMode> modes = parsePrepareModes(args);
        matchManager.prepare(modes);
        String desc = describePrepareModes(modes);
        sender.sendMessage(Component.text("✔ Prepared all participants — reset: " + desc + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleRunner(CommandSender sender, String[] args) {
        List<Player> targets = args.length >= 2
                ? SelectorUtil.resolvePlayers(sender, args[1])
                : SelectorUtil.resolvePlayers(sender, null);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text(
                    "No player matched. Usage: /peoplehunt runner [player]", NamedTextColor.RED));
            return true;
        }
        Player player = targets.getFirst();
        boolean set = matchManager.toggleRunner(player.getUniqueId());
        sender.sendMessage(Component.text(
                set ? "✔ Runner set to " + player.getName() + "."
                    : "✔ Runner unset (" + player.getName() + " removed).",
                NamedTextColor.GREEN));
        return true;
    }

    private boolean handleHunter(CommandSender sender, String[] args) {
        String requestedAction = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        if (requestedAction.equals("clear")) {
            int removed = matchManager.clearExplicitHunters();
            sender.sendMessage(Component.text(
                    removed > 0
                        ? "✔ Cleared " + removed + " explicit hunter(s). Next match will use all online non-runner players."
                        : "No explicit hunters were set — next match already uses all online non-runner players.",
                    NamedTextColor.GREEN));
            return true;
        }

        String action = switch (requestedAction) {
            case "add", "remove", "toggle" -> requestedAction;
            default -> "toggle";
        };
        int selectorIndex = switch (requestedAction) {
            case "add", "remove", "toggle" -> 2;
            default -> 1;
        };

        List<Player> targets = args.length > selectorIndex
                ? SelectorUtil.resolvePlayers(sender, args[selectorIndex])
                : SelectorUtil.resolvePlayers(sender, null);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text(
                    "No players matched. Usage: /peoplehunt hunter [add|remove|toggle|clear] [player]",
                    NamedTextColor.RED));
            return true;
        }

        int added = 0;
        int removed = 0;
        int skippedRunner = 0;
        for (Player player : targets) {
            if (player.getUniqueId().equals(matchManager.selectedRunnerUuid())) {
                skippedRunner++;
                continue;
            }
            boolean wasExplicit = matchManager.explicitHunters().contains(player.getUniqueId());
            boolean nowSelected;
            switch (action) {
                case "add" -> {
                    matchManager.addHunter(player.getUniqueId());
                    nowSelected = true;
                }
                case "remove" -> {
                    matchManager.removeHunter(player.getUniqueId());
                    nowSelected = false;
                }
                default -> nowSelected = matchManager.toggleHunter(player.getUniqueId());
            }
            if (nowSelected && !wasExplicit) added++;
            else if (!nowSelected && wasExplicit) removed++;
        }

        List<String> parts = new ArrayList<>();
        if (added > 0)         parts.add(added + " added");
        if (removed > 0)       parts.add(removed + " removed");
        if (skippedRunner > 0) parts.add(skippedRunner + " skipped (runner)");
        String summary = parts.isEmpty() ? "No changes made." : "✔ Hunters updated — " + String.join(", ", parts) + ".";
        sender.sendMessage(Component.text(summary, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage(matchManager.buildStatusComponent());
        return true;
    }

    private boolean handleSurround(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /peoplehunt surround <min-radius> [max-radius]", NamedTextColor.RED));
            return true;
        }
        double min = Double.parseDouble(args[1]);
        Double max = args.length >= 3 ? Double.parseDouble(args[2]) : null;
        matchManager.surroundHunters(min, max);
        String radiusDesc = max != null
                ? String.format("%.0f–%.0f blocks", min, max)
                : String.format("%.0f blocks", min);
        sender.sendMessage(Component.text(
                "✔ Hunters positioned around the runner (" + radiusDesc + " radius).",
                NamedTextColor.GREEN));
        return true;
    }

    private boolean handleCompass(CommandSender sender, String[] args) {
        return CompassCommandSupport.giveCompass(sender, compassService, args.length >= 2 ? args[1] : null);
    }

    private boolean handleKit(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /peoplehunt kit <save|select|clear|delete> [identifier]",
                    NamedTextColor.RED));
            return true;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "save" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text(
                            "Usage: /peoplehunt kit save <identifier>", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text(
                            "Only an in-game player can save a kit from their inventory.", NamedTextColor.RED));
                    return true;
                }
                String identifier = args[2];
                kitService.saveKit(identifier, player);
                matchManager.setActiveKitId(identifier);
                sender.sendMessage(Component.text(
                        "✔ Kit '" + identifier + "' saved from your inventory and set as active.",
                        NamedTextColor.GREEN));
            }
            case "select" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text(
                            "Usage: /peoplehunt kit select <identifier>", NamedTextColor.RED));
                    return true;
                }
                String identifier = args[2];
                if (kitService.get(identifier).isEmpty()) {
                    sender.sendMessage(Component.text(
                            "Kit '" + identifier + "' not found. Use '/peoplehunt kit save' to create it.",
                            NamedTextColor.RED));
                    return true;
                }
                matchManager.setActiveKitId(identifier);
                sender.sendMessage(Component.text(
                        "✔ Active kit set to '" + identifier + "'.", NamedTextColor.GREEN));
            }
            case "clear" -> {
                String previous = matchManager.activeKitId();
                matchManager.setActiveKitId(null);
                sender.sendMessage(Component.text(
                        previous != null
                            ? "✔ Active kit '" + previous + "' cleared — hunters will not receive a kit on respawn."
                            : "No active kit was set.",
                        NamedTextColor.GREEN));
            }
            case "delete" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text(
                            "Usage: /peoplehunt kit delete <identifier>", NamedTextColor.RED));
                    return true;
                }
                String identifier = args[2];
                boolean wasActive = identifier.equals(matchManager.activeKitId());
                boolean deleted = kitService.deleteKit(identifier);
                if (!deleted) {
                    sender.sendMessage(Component.text(
                            "Kit '" + identifier + "' not found.", NamedTextColor.RED));
                    return true;
                }
                if (wasActive) matchManager.setActiveKitId(null);
                sender.sendMessage(Component.text(
                        "✔ Kit '" + identifier + "' deleted."
                            + (wasActive ? " Active kit cleared." : ""),
                        NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text(
                    "Unknown kit action '" + action + "'. Valid: save, select, clear, delete.",
                    NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleInventoryControl(CommandSender sender, String[] args) {
        if (args.length < 2) {
            KeepInventoryMode current = matchManager.inventoryControlMode();
            sender.sendMessage(Component.text(
                    "Inventory control is currently: " + current
                    + ". Usage: /peoplehunt inventorycontrol <none|kit|keep>",
                    NamedTextColor.RED));
            return true;
        }
        String raw = args[1].toUpperCase(Locale.ROOT);
        KeepInventoryMode mode;
        try {
            mode = KeepInventoryMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text(
                    "Unknown mode '" + args[1] + "'. Valid: none, kit, keep.", NamedTextColor.RED));
            return true;
        }
        if (mode == KeepInventoryMode.INHERIT) {
            sender.sendMessage(Component.text(
                    "INHERIT is not a valid inventory control mode.", NamedTextColor.RED));
            return true;
        }
        matchManager.setInventoryControlMode(mode);
        // setInventoryControlMode already broadcasts if a session is active; confirm to sender.
        sender.sendMessage(Component.text(
                "✔ Inventory control set to " + mode + ".", NamedTextColor.GREEN));
        return true;
    }


    private boolean handleRollback(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /peoplehunt rollback <all|player> <duration> [--tp] [--gamemode] [--no-effects]", NamedTextColor.RED));
            return true;
        }
        UUID targetUuid = null;
        if (!args[1].equalsIgnoreCase("all")) {
            List<Player> targets = SelectorUtil.resolvePlayers(sender, args[1]);
            if (targets.isEmpty()) {
                sender.sendMessage(Component.text("No player matched rollback target.", NamedTextColor.RED));
                return true;
            }
            targetUuid = targets.getFirst().getUniqueId();
        }
        long durationMillis = parseDuration(args[2]);
        boolean teleport = false;
        boolean restoreGameMode = false;
        boolean restoreEffects = true;
        for (int i = 3; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--tp" -> teleport = true;
                case "--gamemode" -> restoreGameMode = true;
                case "--no-effects" -> restoreEffects = false;
                default -> { }
            }
        }
        int restored = matchManager.rollback(targetUuid, durationMillis, teleport, restoreGameMode, restoreEffects);
        sender.sendMessage(Component.text("Rolled back " + restored + " player(s).", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleAar(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /peoplehunt aar <list|export>", NamedTextColor.RED));
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "list"   -> handleAarList(sender);
            case "export" -> handleAarExport(sender, args);
            default -> {
                sender.sendMessage(Component.text("Unknown aar action.", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean handleAarList(CommandSender sender) {
        List<IndexEntry> entries = reportService.listReports();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("No reports found.", NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("Reports:", NamedTextColor.GOLD));
        for (IndexEntry entry : entries) {
            Component line = Component.text(entry.reportId().toString(), NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Runner UUID: " + entry.runnerUuid(), NamedTextColor.GRAY)))
                    .append(Component.text(
                            " — " + entry.runnerName()
                                    + " — " + entry.outcome()
                                    + " — " + Text.formatTimestamp(entry.endedAtEpochMillis()),
                            NamedTextColor.GRAY));
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean handleAarExport(CommandSender sender, String[] args) throws IOException {
        UUID reportId = args.length >= 3 ? UUID.fromString(args[2]) : reportService.latestReportId();
        if (reportId == null) {
            sender.sendMessage(Component.text("No finished report is available.", NamedTextColor.RED));
            return true;
        }
        Path export = reportService.export(reportId, viewerAssets.render("LOCAL_EXPORT", reportService.toJson(reportService.readSnapshot(reportId))));
        sender.sendMessage(Component.text("Exported report to " + export.getFileName(), NamedTextColor.GREEN));
        return true;
    }

    private boolean handlePortal(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this prompt.", NamedTextColor.RED));
            return true;
        }
        Location target = matchManager.consumeEndPortalPrompt(player.getUniqueId()).orElse(null);
        if (target == null) {
            player.sendMessage(Component.text("No portal prompt is pending.", NamedTextColor.RED));
            return true;
        }
        player.teleport(target.clone().add(0.5, 0.0, 0.5));
        player.sendMessage(Component.text("Teleported to the runner's last End Portal.", NamedTextColor.GREEN));
        return true;
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {

        if (!isAdmin(sender) && (args.length == 0 || !args[0].equalsIgnoreCase("portal"))) {
            return List.of();
        }

        String partial = args[args.length - 1].toLowerCase();

        if (args.length == 1) {
            return filter(List.of(
                    "start", "stop", "prime", "prepare",
                    "runner", "hunter", "status", "surround",
                    "compass", "kit", "inventorycontrol", "ic",
                    "aar", "rollback", "portal"), partial);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            List<String> opts = switch (sub) {
                case "prime"                   -> List.of("true", "false");
                case "prepare"                 -> List.of("health", "status", "xp", "all");
                case "runner", "compass"       -> playerSuggestions(sender);
                case "hunter"                  -> List.of("add", "remove", "toggle", "clear");
                case "surround"                -> List.of("5", "10", "20");
                case "kit"                     -> List.of("save", "select", "clear", "delete");
                case "inventorycontrol", "ic"  -> List.of("none", "kit", "keep");
                case "aar"                     -> List.of("list", "export");
                case "rollback"                -> { List<String> rollbackTargets = new ArrayList<>(); rollbackTargets.add("all"); rollbackTargets.addAll(playerSuggestions(sender)); yield rollbackTargets; }
                default                        -> List.of();
            };
            return filter(opts, partial);
        }

        if (args.length == 3) {
            switch (sub) {
                case "hunter" -> {
                    String action = args[1].toLowerCase();
                    if (List.of("add", "remove", "toggle").contains(action)) {
                        // For remove/toggle, only suggest players already in the explicit hunter list.
                        // For add, suggest all online players (minus the runner).
                        if (action.equals("remove")) {
                            return filter(onlinePlayerNames(matchManager.explicitHunters()), partial);
                        }
                        return filter(playerSuggestions(sender), partial);
                    }
                }
                case "prepare" -> {
                    // Continue suggesting remaining prepare modes (exclude ones already typed).
                    return remainingPrepareModes(args, partial);
                }
                case "surround" -> {
                    // Suggest max-radius values larger than the typed min.
                    return filter(List.of("10", "20", "30", "50"), partial);
                }
                case "kit" -> {
                    return switch (args[1].toLowerCase()) {
                        case "select", "delete" -> filter(new ArrayList<>(kitService.identifiers()), partial);
                        case "save"             -> List.of("<identifier>");
                        default                 -> List.of();
                    };
                }
                case "rollback" -> {
                    return filter(List.of("5m", "30s", "1m", "10m"), partial);
                }
                case "aar" -> {
                    if (args[1].equalsIgnoreCase("export")) {
                        List<String> ids = reportService.listReports().stream()
                                .map(e -> e.reportId().toString())
                                .toList();
                        return ids.isEmpty() ? List.of() : filter(ids, partial);
                    }
                }
            }
        }

        // prepare accepts up to 4 mode tokens — keep suggesting remaining ones.
        if (args.length >= 4 && sub.equals("prepare")) {
            return remainingPrepareModes(args, partial);
        }


        if (args.length >= 4 && sub.equals("rollback")) {
            if (args.length == 4) {
                return filter(ROLLBACK_FLAGS, partial);
            }
            return filter(ROLLBACK_FLAGS, partial);
        }

        return List.of();
    }

    private List<String> remainingPrepareModes(String[] args, String partial) {
        List<String> remaining = new ArrayList<>(PREPARE_MODE_SUGGESTIONS);
        for (int i = 1; i < args.length - 1; i++) {
            remaining.remove(args[i].toLowerCase(Locale.ROOT));
        }
        return filter(remaining, partial);
    }

    /** Returns the names of online players whose UUIDs are in the given set. */
    private static List<String> onlinePlayerNames(java.util.Collection<java.util.UUID> uuids) {
        return uuids.stream()
                .map(org.bukkit.Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull)
                .map(Player::getName)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private EnumSet<PrepareMode> parsePrepareModes(String[] args) {
        if (args.length < 2) {
            return EnumSet.of(PrepareMode.HEALTH_AND_HUNGER, PrepareMode.STATUS_EFFECTS, PrepareMode.EXPERIENCE);
        }

        EnumSet<PrepareMode> modes = EnumSet.noneOf(PrepareMode.class);
        for (int i = 1; i < args.length; i++) {
            String token = args[i].toLowerCase(Locale.ROOT);
            switch (token) {
                case "all" -> modes.addAll(EnumSet.allOf(PrepareMode.class));
                case "health", "hunger", "food", "health-hunger", "health_and_hunger" -> modes.add(PrepareMode.HEALTH_AND_HUNGER);
                case "status", "effects", "effect", "potion" -> modes.add(PrepareMode.STATUS_EFFECTS);
                case "xp", "experience", "levels" -> modes.add(PrepareMode.EXPERIENCE);
                case "inventory", "inv", "clear-inventory", "clear_inventory" -> modes.add(PrepareMode.INVENTORY);
                default -> throw new IllegalArgumentException("Unknown prepare mode '" + args[i]
                        + "'. Valid: all, health, status, xp, inventory.");
            }
        }
        return modes;
    }


    private long parseDuration(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("ms")) return parseDurationAmount(value, 2, 1L);
        if (value.endsWith("s")) return parseDurationAmount(value, 1, 1000L);
        if (value.endsWith("m")) return parseDurationAmount(value, 1, 60_000L);
        if (value.endsWith("h")) return parseDurationAmount(value, 1, 3_600_000L);
        return Long.parseLong(value);
    }

    private long parseDurationAmount(String value, int suffixLength, long multiplier) {
        return Long.parseLong(value.substring(0, value.length() - suffixLength)) * multiplier;
    }

    private String describePrepareModes(EnumSet<PrepareMode> modes) {
        List<String> labels = new ArrayList<>();
        if (modes.contains(PrepareMode.HEALTH_AND_HUNGER)) labels.add("health & hunger");
        if (modes.contains(PrepareMode.STATUS_EFFECTS))    labels.add("status effects");
        if (modes.contains(PrepareMode.EXPERIENCE))        labels.add("XP");
        if (modes.contains(PrepareMode.INVENTORY))         labels.add("inventory");
        return labels.isEmpty() ? "nothing" : String.join(", ", labels);
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("peoplehunt.admin") || sender.isOp();
    }

    /** Builds the standard player selector + online name list. */
    private static List<String> playerSuggestions(CommandSender sender) {
        List<String> suggestions = new ArrayList<>(List.of("@s", "@p", "@a"));
        sender.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .forEach(suggestions::add);
        return suggestions;
    }

    /** Returns entries from {@code options} whose lowercase form starts with {@code partial}. */
    private static List<String> filter(List<String> options, String partial) {
        if (partial.isEmpty()) return options;
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(partial))
                .toList();
    }
}
