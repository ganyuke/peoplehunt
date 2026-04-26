package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class CompassCommand implements CommandExecutor, TabCompleter {
    private final CompassService compassService;

    public CompassCommand(CompassService compassService) {
        this.compassService = compassService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return CompassCommandSupport.giveCompass(sender, compassService, args.length == 0 ? null : args[0]);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase();
        List<String> completions = new ArrayList<>(List.of("@s", "@p", "@a"));
        sender.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .forEach(completions::add);
        if (partial.isEmpty()) {
            return completions;
        }
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(partial))
                .toList();
    }
}
