package io.github.ganyuke.manhunt.command;

import io.github.ganyuke.manhunt.Manhunt;
import io.github.ganyuke.manhunt.analytics.SessionExporter;
import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.CompassService;
import io.github.ganyuke.manhunt.game.MatchManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.RoleService;
import io.github.ganyuke.manhunt.game.SessionState;
import io.github.ganyuke.manhunt.game.SurroundService;
import io.github.ganyuke.manhunt.util.PlayerUtil;
import io.github.ganyuke.manhunt.util.TextUtil;
import io.github.ganyuke.manhunt.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ManhuntCommand implements TabExecutor {
    private final Manhunt plugin;
    private final ConfigManager configManager;
    private final RoleService roleService;
    private final MatchManager matchManager;
    private final SurroundService surroundService;
    private final CompassService compassService;
    private final SessionExporter sessionExporter;

    public ManhuntCommand(Manhunt plugin,
                          ConfigManager configManager,
                          RoleService roleService,
                          MatchManager matchManager,
                          SurroundService surroundService,
                          CompassService compassService,
                          SessionExporter sessionExporter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.roleService = roleService;
        this.matchManager = matchManager;
        this.surroundService = surroundService;
        this.compassService = compassService;
        this.sessionExporter = sessionExporter;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "runner" -> handleRunner(sender, args);
                case "hunters" -> handleHunters(sender, args);
                case "prime" -> handlePrime(sender);
                case "start" -> handleStart(sender);
                case "surround" -> handleSurround(sender, args);
                case "compass" -> handleCompass(sender, args);
                case "status" -> handleStatus(sender);
                case "stop" -> handleStop(sender, args);
                case "aar" -> handleAar(sender, args);
                case "reload" -> handleReload(sender);
                default -> sendHelp(sender);
            }
        } catch (IllegalStateException exception) {
            message(sender, "&c" + exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("runner", "hunters", "prime", "start", "surround", "compass", "status", "stop", "aar", "reload"), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "runner" -> filter(PlayerUtil.onlinePlayerNames(), args[1]);
                case "hunters" -> filter(List.of("auto", "add", "remove"), args[1]);
                case "compass" -> filter(List.of("give"), args[1]);
                case "aar" -> filter(List.of("export"), args[1]);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 3) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "hunters" -> filter(PlayerUtil.onlinePlayerNames(), args[2]);
                case "compass" -> {
                    List<String> options = new ArrayList<>(PlayerUtil.onlinePlayerNames());
                    options.add("hunters");
                    yield filter(options, args[2]);
                }
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }

    private void handleRunner(CommandSender sender, String[] args) {
        ensureRoleEditable();
        if (args.length < 2) {
            throw new IllegalStateException("Usage: /manhunt runner <player>");
        }
        Player player = requireOnlinePlayer(args[1]);
        roleService.setRunner(player.getUniqueId());
        message(sender, "&aRunner set to &f" + player.getName());
    }

    private void handleHunters(CommandSender sender, String[] args) {
        ensureRoleEditable();
        if (args.length < 2) {
            throw new IllegalStateException("Usage: /manhunt hunters <auto|add|remove> [player]");
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "auto" -> {
                roleService.autoAssignHunters();
                message(sender, "&aHunters auto-assigned from online players.");
            }
            case "add" -> {
                if (args.length < 3) {
                    throw new IllegalStateException("Usage: /manhunt hunters add <player>");
                }
                Player player = requireOnlinePlayer(args[2]);
                roleService.addHunter(player.getUniqueId());
                message(sender, "&aAdded hunter &f" + player.getName());
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalStateException("Usage: /manhunt hunters remove <player>");
                }
                Player player = requireOnlinePlayer(args[2]);
                roleService.removeHunter(player.getUniqueId());
                message(sender, "&eRemoved hunter &f" + player.getName());
            }
            default -> throw new IllegalStateException("Unknown hunters subcommand.");
        }
    }

    private void handlePrime(CommandSender sender) {
        MatchSession session = matchManager.prime();
        message(sender, "&aPrimed session &f" + session.sessionId());
    }

    private void handleStart(CommandSender sender) {
        MatchSession session = matchManager.start("Started by " + sender.getName());
        message(sender, "&aStarted session &f" + session.sessionId());
    }

    private void handleSurround(CommandSender sender, String[] args) {
        int radius = args.length >= 2 ? Integer.parseInt(args[1]) : configManager.settings().surroundDefaultRadius();
        MatchSession session = matchManager.getCurrentSession();
        if (session == null) {
            if (!roleService.hasRunner() || !roleService.hasHunters()) {
                throw new IllegalStateException("Choose a runner and at least one hunter first.");
            }
            session = new MatchSession(UUID.randomUUID(), roleService.getRunnerId(), roleService.getHunterIds());
        }
        int moved = surroundService.surroundRunner(session, radius);
        message(sender, "&aTeleported &f" + moved + "&a hunters around the runner.");
    }

    private void handleCompass(CommandSender sender, String[] args) {
        if (args.length < 2 || !"give".equalsIgnoreCase(args[1])) {
            throw new IllegalStateException("Usage: /manhunt compass give [hunters|player]");
        }
        if (args.length < 3 || "hunters".equalsIgnoreCase(args[2])) {
            int given = 0;
            MatchSession session = matchManager.getCurrentSession();
            if (session != null) {
                for (UUID hunterId : session.hunterIds()) {
                    Player hunter = Bukkit.getPlayer(hunterId);
                    if (hunter != null) {
                        compassService.giveTrackerCompass(hunter);
                        given++;
                    }
                }
            } else {
                for (UUID hunterId : roleService.getHunterIds()) {
                    Player hunter = Bukkit.getPlayer(hunterId);
                    if (hunter != null) {
                        compassService.giveTrackerCompass(hunter);
                        given++;
                    }
                }
            }
            message(sender, "&aGave tracker compasses to &f" + given + "&a hunters.");
            return;
        }
        Player target = requireOnlinePlayer(args[2]);
        compassService.giveTrackerCompass(target);
        message(sender, "&aGave tracker compass to &f" + target.getName());
    }

    private void handleStatus(CommandSender sender) {
        MatchSession session = matchManager.getCurrentSession();
        SessionState state = session == null ? SessionState.IDLE : session.state();
        message(sender, "&eState: &f" + state.name());
        message(sender, "&eRunner: &f" + (roleService.getRunnerId() == null ? "none" : PlayerUtil.name(roleService.getRunnerId())));
        List<String> hunters = roleService.getHunterIds().stream().map(PlayerUtil::name).toList();
        message(sender, "&eHunters: &f" + (hunters.isEmpty() ? "none" : String.join(", ", hunters)));
        if (session != null) {
            message(sender, "&eSession ID: &f" + session.sessionId());
            if (session.hasStarted()) {
                message(sender, "&eElapsed: &f" + TimeFormat.mmss(matchManager.elapsed()));
            }
            message(sender, "&eVictory: &f" + session.victoryType().name());
            message(sender, "&eReason: &f" + session.endReason());
        }
    }

    private void handleStop(CommandSender sender, String[] args) {
        String reason = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Stopped by " + sender.getName();
        matchManager.stop(reason);
        message(sender, "&eStopped the match.");
    }

    private void handleAar(CommandSender sender, String[] args) {
        if (args.length < 2 || !"export".equalsIgnoreCase(args[1])) {
            throw new IllegalStateException("Usage: /manhunt aar export [session-id]");
        }
        UUID sessionId;
        if (args.length >= 3) {
            sessionId = UUID.fromString(args[2]);
        } else {
            sessionId = matchManager.getMostRecentSessionId();
        }
        if (sessionId == null) {
            throw new IllegalStateException("No session available to export.");
        }
        Path exportPath = sessionExporter.exportSession(sessionId);
        if (exportPath == null) {
            throw new IllegalStateException("Could not export that session.");
        }
        message(sender, "&aExported session to &f" + exportPath);
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPluginConfiguration();
        message(sender, "&aReloaded Manhunt config files.");
    }

    private void ensureRoleEditable() {
        if (matchManager.isRoleSelectionLocked()) {
            throw new IllegalStateException("Roles cannot be edited while the match is primed or running.");
        }
    }

    private Player requireOnlinePlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            player = Bukkit.getPlayer(name);
        }
        if (player == null) {
            throw new IllegalStateException("Player not found: " + name);
        }
        return player;
    }

    private void sendHelp(CommandSender sender) {
        message(sender, "&e/manhunt runner <player>");
        message(sender, "&e/manhunt hunters <auto|add|remove> [player]");
        message(sender, "&e/manhunt prime");
        message(sender, "&e/manhunt start");
        message(sender, "&e/manhunt surround [radius]");
        message(sender, "&e/manhunt compass give [hunters|player]");
        message(sender, "&e/manhunt status");
        message(sender, "&e/manhunt stop [reason]");
        message(sender, "&e/manhunt aar export [session-id]");
        message(sender, "&e/manhunt reload");
    }

    private void message(CommandSender sender, String message) {
        sender.sendMessage(TextUtil.prefixed(configManager.settings().prefix(), message));
    }

    private List<String> filter(List<String> values, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
