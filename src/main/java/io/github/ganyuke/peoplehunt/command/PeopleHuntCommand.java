package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.report.ReportModels.IndexEntry;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.report.ViewerAssets;
import io.github.ganyuke.peoplehunt.util.SelectorUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.jetbrains.annotations.Nullable;

public final class PeopleHuntCommand implements CommandExecutor, TabCompleter {
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
                case "start"                      -> handleStart(sender);
                case "stop"                       -> handleStop(sender);
                case "prime"                      -> handlePrime(sender, args);
                case "prepare"                    -> handlePrepare(sender);
                case "runner"                     -> handleRunner(sender, args);
                case "hunter"                     -> handleHunter(sender, args);
                case "status"                     -> handleStatus(sender);
                case "surround"                   -> handleSurround(sender, args);
                case "compass"                    -> handleCompass(sender, args);
                case "kit"                        -> handleKit(sender, args);
                case "keepinventory", "keepinv"   -> handleKeepInventory(sender, args);
                case "aar"                        -> handleAar(sender, args);
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
        sender.sendMessage(Component.text("Manhunt force-stopped.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handlePrime(CommandSender sender, String[] args) {
        Boolean keepFull = args.length >= 2 ? Boolean.parseBoolean(args[1]) : null;
        matchManager.prime(keepFull);
        sender.sendMessage(Component.text("Waiting for runner to move...", NamedTextColor.GREEN));
        return true;
    }

    private boolean handlePrepare(CommandSender sender) {
        matchManager.prepare(true);
        sender.sendMessage(Component.text("Prepared participants.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleRunner(CommandSender sender, String[] args) {
        List<Player> targets = args.length >= 2
                ? SelectorUtil.resolvePlayers(sender, args[1])
                : SelectorUtil.resolvePlayers(sender, null);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No player matched.", NamedTextColor.RED));
            return true;
        }
        Player player = targets.getFirst();
        boolean set = matchManager.toggleRunner(player.getUniqueId());
        sender.sendMessage(Component.text(
                (set ? "Runner set to " : "Runner unset: ") + player.getName(),
                NamedTextColor.GREEN));
        return true;
    }

    private boolean handleHunter(CommandSender sender, String[] args) {
        List<Player> targets = args.length >= 2
                ? SelectorUtil.resolvePlayers(sender, args[1])
                : SelectorUtil.resolvePlayers(sender, null);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No players matched.", NamedTextColor.RED));
            return true;
        }
        int added = 0;
        int removed = 0;
        for (Player player : targets) {
            if (player.getUniqueId().equals(matchManager.selectedRunnerUuid())) {
                continue;
            }
            boolean wasExplicit = matchManager.explicitHunters().contains(player.getUniqueId());
            boolean nowSelected = matchManager.toggleHunter(player.getUniqueId());
            if (nowSelected && !wasExplicit) added++;
            else if (!nowSelected && wasExplicit) removed++;
        }
        sender.sendMessage(Component.text(
                "Hunters updated. Added: " + added + ", removed: " + removed,
                NamedTextColor.GREEN));
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage(matchManager.buildStatusComponent());
        return true;
    }

    private boolean handleSurround(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /peoplehunt surround <min-radius> [max-radius]", NamedTextColor.RED));
            return true;
        }
        double min = Double.parseDouble(args[1]);
        Double max = args.length >= 3 ? Double.parseDouble(args[2]) : null;
        matchManager.surroundHunters(min, max);
        sender.sendMessage(Component.text("Hunters positioned around the runner.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleCompass(CommandSender sender, String[] args) {
        List<Player> targets = args.length >= 2
                ? SelectorUtil.resolvePlayers(sender, args[1])
                : SelectorUtil.resolvePlayers(sender, null);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No target players matched.", NamedTextColor.RED));
            return true;
        }
        compassService.giveCompass(targets);
        // Mirrors the standalone /compass command: name for one, count for many.
        boolean selfOnly = targets.size() == 1
                && sender instanceof Player player
                && targets.getFirst().equals(player);
        if (!selfOnly) {
            Component message = targets.size() == 1
                    ? Component.text("Gave compass to " + targets.getFirst().getName() + ".", NamedTextColor.GREEN)
                    : Component.text("Gave compass to " + targets.size() + " players.", NamedTextColor.GREEN);
            sender.sendMessage(message);
        }
        return true;
    }

    private boolean handleKit(CommandSender sender, String[] args) throws IOException {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /peoplehunt kit <set|delete> <identifier>", NamedTextColor.RED));
            return true;
        }
        String action = args[1].toLowerCase();
        String identifier = args[2];
        switch (action) {
            case "set" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Only a player can save a kit from inventory.", NamedTextColor.RED));
                    return true;
                }
                kitService.saveKit(identifier, player);
                matchManager.setActiveKitId(identifier);
                sender.sendMessage(Component.text("Saved and selected kit '" + identifier + "'.", NamedTextColor.GREEN));
            }
            case "delete" -> {
                boolean removed = kitService.deleteKit(identifier);
                if (removed && identifier.equals(matchManager.activeKitId())) {
                    matchManager.setActiveKitId(null);
                }
                sender.sendMessage(Component.text(
                        removed ? "Deleted kit '" + identifier + "'." : "Kit not found.",
                        removed ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            default -> sender.sendMessage(Component.text("Unknown kit action.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleKeepInventory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /peoplehunt keepinventory <none|kit|all>", NamedTextColor.RED));
            return true;
        }
        KeepInventoryMode mode;
        try {
            mode = KeepInventoryMode.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Unknown mode '" + args[1] + "'. Valid: none, kit, all.", NamedTextColor.RED));
            return true;
        }
        if (mode == KeepInventoryMode.INHERIT) {
            sender.sendMessage(Component.text("INHERIT is only valid inside deathstreak config tiers.", NamedTextColor.RED));
            return true;
        }
        matchManager.setKeepInventoryMode(mode);
        sender.sendMessage(Component.text("Keep inventory mode set to " + mode + '.', NamedTextColor.GREEN));
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
        Path export = reportService.export(reportId, viewerAssets.render("LOCAL_EXPORT"));
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
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {

        String partial = args[args.length - 1].toLowerCase();

        if (args.length == 1) {
            return filter(List.of(
                    "start", "stop", "prime", "prepare",
                    "runner", "hunter", "status", "surround",
                    "compass", "kit", "keepinventory", "keepinv",
                    "aar", "portal"), partial);
        }

        if (args.length == 2) {
            List<String> opts = switch (args[0].toLowerCase()) {
                case "prime"                    -> List.of("true", "false");
                case "runner", "hunter",
                     "compass"                  -> playerSuggestions(sender);
                case "kit"                      -> List.of("set", "delete");
                case "keepinventory", "keepinv" -> List.of("none", "kit", "all");
                case "aar"                      -> List.of("list", "export");
                default                         -> List.of();
            };
            return filter(opts, partial);
        }

        if (args.length == 3) {
            // kit delete <identifier>
            if (args[0].equalsIgnoreCase("kit") && args[1].equalsIgnoreCase("delete")) {
                return filter(new ArrayList<>(kitService.identifiers()), partial);
            }
            // aar export <uuid>
            if (args[0].equalsIgnoreCase("aar") && args[1].equalsIgnoreCase("export")) {
                return filter(
                        reportService.listReports().stream()
                                .map(e -> e.reportId().toString())
                                .toList(),
                        partial);
            }
        }

        return List.of();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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