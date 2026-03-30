package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.CompassService;
import io.github.ganyuke.peoplehunt.util.SelectorUtil;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CompassCommand implements CommandExecutor, TabCompleter {
    private final CompassService compassService;

    public CompassCommand(CompassService compassService) {
        this.compassService = compassService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<Player> targets = args.length == 0 ? SelectorUtil.resolvePlayers(sender, null) : SelectorUtil.resolvePlayers(sender, args[0]);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No target players matched.", NamedTextColor.RED));
            return true;
        }
        compassService.giveCompass(targets);
        sender.sendMessage(Component.text("Gave compass to " + targets.size() + " player(s).", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("@s", "@p", "@a");
        }
        return List.of();
    }
}
