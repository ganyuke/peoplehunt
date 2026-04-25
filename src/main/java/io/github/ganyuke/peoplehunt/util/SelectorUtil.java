package io.github.ganyuke.peoplehunt.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class SelectorUtil {
    private SelectorUtil() {}

    /**
     * Resolves a player token using vanilla selectors first and name lookup second.
     *
     * <p>A null or blank token means "the sender" for player senders, which keeps command UX terse
     * for common self-targeting admin actions.
     */
    public static List<Player> resolvePlayers(CommandSender sender, String token) {
        Set<Player> players = new LinkedHashSet<>();
        if (token == null || token.isBlank()) {
            if (sender instanceof Player player) {
                players.add(player);
            }
            return new ArrayList<>(players);
        }
        if (token.startsWith("@")) {
            try {
                for (Entity entity : Bukkit.selectEntities(sender, token)) {
                    if (entity instanceof Player player) {
                        players.add(player);
                    }
                }
                return new ArrayList<>(players);
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        Player exact = Bukkit.getPlayerExact(token);
        if (exact != null) {
            players.add(exact);
            return new ArrayList<>(players);
        }
        Player fuzzy = Bukkit.getPlayer(token);
        if (fuzzy != null) {
            players.add(fuzzy);
        }
        return new ArrayList<>(players);
    }
}
