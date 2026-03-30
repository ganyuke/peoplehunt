package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.tools.WhereWasStore;
import io.github.ganyuke.peoplehunt.util.SelectorUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WhereWasCommand implements CommandExecutor, TabCompleter {
    private final WhereWasStore whereWasStore;

    public WhereWasCommand(WhereWasStore whereWasStore) {
        this.whereWasStore = whereWasStore;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command requires a player sender.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /wherewas <this|remember|forget> <identifier>", NamedTextColor.RED));
            return true;
        }
        String action = args[0].toLowerCase();
        String identifier = args[1];
        try {
            switch (action) {
                case "this" -> showCoordinate(player, identifier, args);
                case "remember" -> rememberCoordinate(player, identifier, args);
                case "forget" -> forgetCoordinate(player, identifier);
                default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
            }
        } catch (IOException exception) {
            sender.sendMessage(Component.text("Unable to save coordinate data.", NamedTextColor.RED));
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Coordinates must be numeric.", NamedTextColor.RED));
        }
        return true;
    }

    private void showCoordinate(Player player, String identifier, String[] args) {
        var saved = whereWasStore.lookup(player.getUniqueId(), identifier);
        if (saved.isEmpty()) {
            player.sendMessage(Component.text("No coordinate saved for '" + identifier + "'.", NamedTextColor.RED));
            return;
        }
        String message = identifier + ": " + saved.get().display();
        Component component = Component.text(message, NamedTextColor.GREEN);
        player.sendMessage(component);
        if (args.length >= 3) {
            List<Player> targets = SelectorUtil.resolvePlayers(player, args[2]);
            for (Player target : targets) {
                if (target.equals(player)) {
                    continue;
                }
                target.sendMessage(Component.text(player.getName() + " shared " + message, NamedTextColor.AQUA));
            }
        }
    }

    private void rememberCoordinate(Player player, String identifier, String[] args) throws IOException {
        Location location;
        if (args.length == 3 || args.length == 4) {
            // args[2] or args[2..3] present but incomplete — not enough for x y z
            player.sendMessage(Component.text("Coordinates must be specified as x y z.", NamedTextColor.RED));
            return;
        }
        if (args.length >= 5) {
            location = player.getLocation().clone();
            location.setX(Double.parseDouble(args[2]));
            location.setY(Double.parseDouble(args[3]));
            location.setZ(Double.parseDouble(args[4]));
        } else {
            location = player.getLocation();
        }
        whereWasStore.remember(player.getUniqueId(), identifier, location);
        player.sendMessage(Component.text(
                "Saved '" + identifier + "' at " + location.getWorld().getKey().asString()
                        + ": " + String.format("%.2f, %.2f, %.2f", location.getX(), location.getY(), location.getZ()),
                NamedTextColor.GREEN));
    }

    private void forgetCoordinate(Player player, String identifier) throws IOException {
        boolean removed = whereWasStore.forget(player.getUniqueId(), identifier);
        if (removed) {
            player.sendMessage(Component.text("Forgot '" + identifier + "'.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Nothing saved for '" + identifier + "'.", NamedTextColor.RED));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {

        // Tab completion is only meaningful for player senders since identifiers are per-player.
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        String partial = args[args.length - 1].toLowerCase();

        return switch (args.length) {
            // arg[0]: subcommand
            case 1 -> filter(List.of("this", "remember", "forget"), partial);

            // arg[1]: identifier — offer saved identifiers for subcommands that read/delete them,
            // and a generic "<identifier>" hint for "remember" (which creates new ones).
            case 2 -> {
                String sub = args[0].toLowerCase();
                if (sub.equals("this") || sub.equals("forget")) {
                    List<String> identifiers = new ArrayList<>(whereWasStore.listIdentifiers(player.getUniqueId()));
                    yield filter(identifiers, partial);
                }
                // "remember": no existing identifiers to offer, but give a placeholder hint.
                yield filter(List.of("<identifier>"), partial);
            }

            // arg[2]: context-sensitive
            case 3 -> {
                String sub = args[0].toLowerCase();
                // "this <identifier> <player>" — offer player selectors
                if (sub.equals("this")) {
                    List<String> players = new ArrayList<>(List.of("@s", "@p", "@a"));
                    sender.getServer().getOnlinePlayers()
                            .stream()
                            .map(Player::getName)
                            .forEach(players::add);
                    yield filter(players, partial);
                }
                // "remember <identifier> <x>" — suggest current X as a hint
                if (sub.equals("remember")) {
                    yield filter(List.of(String.valueOf((int) player.getLocation().getX())), partial);
                }
                yield List.of();
            }

            // arg[3]: "remember <identifier> <x> <y>"
            case 4 -> {
                if (args[0].equalsIgnoreCase("remember")) {
                    yield filter(List.of(String.valueOf((int) player.getLocation().getY())), partial);
                }
                yield List.of();
            }

            // arg[4]: "remember <identifier> <x> <y> <z>"
            case 5 -> {
                if (args[0].equalsIgnoreCase("remember")) {
                    yield filter(List.of(String.valueOf((int) player.getLocation().getZ())), partial);
                }
                yield List.of();
            }

            default -> List.of();
        };
    }

    /** Returns entries from {@code options} whose lowercase form starts with {@code partial}. */
    private static List<String> filter(List<String> options, String partial) {
        if (partial.isEmpty()) {
            return options;
        }
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(partial))
                .toList();
    }
}