package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.CoordinateUtil;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            sender.sendMessage(Component.text(
                    "%s -> %s: %.2f, %.2f, %.2f".formatted(sourceEnvironment.name().toLowerCase(), converted.targetEnvironment().name().toLowerCase(), converted.x(), converted.y(), converted.z()),
                    NamedTextColor.GREEN
            ));
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Coordinates must be numeric.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("overworld", "nether");
        }
        return List.of();
    }
}
