package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.tools.CoordinateUtil;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CoordinateCommand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            World.Environment sourceEnvironment;
            int offset;
            if (args.length > 0 && (args[0].equalsIgnoreCase("overworld") || args[0].equalsIgnoreCase("nether"))) {
                sourceEnvironment = args[0].equalsIgnoreCase("nether") ? World.Environment.NETHER : World.Environment.NORMAL;
                offset = 1;
            } else if (sender instanceof Player player) {
                sourceEnvironment = player.getWorld().getEnvironment();
                offset = 0;
            } else {
                sourceEnvironment = World.Environment.NORMAL;
                offset = 0;
            }
            double x;
            double y;
            double z;
            if (args.length - offset >= 3) {
                x = Double.parseDouble(args[offset]);
                y = Double.parseDouble(args[offset + 1]);
                z = Double.parseDouble(args[offset + 2]);
            } else if (sender instanceof Player player) {
                x = player.getLocation().getX();
                y = player.getLocation().getY();
                z = player.getLocation().getZ();
            } else {
                sender.sendMessage(Component.text("Console must specify x y z.", NamedTextColor.RED));
                return true;
            }
            CoordinateUtil.ConvertedCoordinate converted = CoordinateUtil.convert(sourceEnvironment, x, y, z);
            sender.sendMessage(formatResult(sourceEnvironment, converted));
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Coordinates must be numeric.", NamedTextColor.RED));
        }
        return true;
    }

    private static Component formatResult(World.Environment source, CoordinateUtil.ConvertedCoordinate converted) {
        return Component.text()
                .append(Component.text(friendlyName(source), environmentColor(source), TextDecoration.BOLD))
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(Component.text(friendlyName(converted.targetEnvironment()), environmentColor(converted.targetEnvironment()), TextDecoration.BOLD))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text("%.0f, %.0f, %.0f".formatted(converted.x(), converted.y(), converted.z()), NamedTextColor.WHITE))
                .build();
    }

    /** Returns a player-friendly dimension name. */
    private static String friendlyName(World.Environment env) {
        return switch (env) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> env.name().toLowerCase();
        };
    }

    /** Returns a thematically appropriate color per dimension. */
    private static NamedTextColor environmentColor(World.Environment env) {
        return switch (env) {
            case NORMAL -> NamedTextColor.GREEN;
            case NETHER -> NamedTextColor.RED;
            case THE_END -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("overworld", "nether");
        }
        return List.of();
    }
}
