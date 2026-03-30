package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.WhereWasStore;
import io.github.ganyuke.peoplehunt.util.SelectorUtil;
import java.io.IOException;
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
        if (args.length >= 5) {
            location = player.getLocation().clone();
            location.setX(Double.parseDouble(args[2]));
            location.setY(Double.parseDouble(args[3]));
            location.setZ(Double.parseDouble(args[4]));
        } else {
            location = player.getLocation();
        }
        whereWasStore.remember(player.getUniqueId(), identifier, location);
        player.sendMessage(Component.text("Saved '" + identifier + "' at " + location.getWorld().getKey().asString() + ": " + String.format("%.2f, %.2f, %.2f", location.getX(), location.getY(), location.getZ()), NamedTextColor.GREEN));
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
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("this", "remember", "forget");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("this")) {
            return List.of("@s", "@p", "@a");
        }
        return List.of();
    }
}
