package io.github.ganyuke.peoplehunt.command;

import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.util.SelectorUtil;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Shared helper for the standalone /compass command and the /peoplehunt compass subcommand.
 *
 * <p>The logic here is intentionally boring: resolve the same selectors both commands accept,
 * give the compass, then produce identical operator feedback so the two entry points cannot drift.
 */
final class CompassCommandSupport {
    private CompassCommandSupport() {}

    static boolean giveCompass(CommandSender sender, CompassService compassService, @Nullable String selector) {
        List<Player> targets = selector == null
                ? SelectorUtil.resolvePlayers(sender, null)
                : SelectorUtil.resolvePlayers(sender, selector);
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No target players matched.", NamedTextColor.RED));
            return true;
        }
        compassService.giveCompass(targets);
        sendFeedback(sender, targets);
        return true;
    }

    private static void sendFeedback(CommandSender sender, List<Player> targets) {
        // Stay silent when a player gives the compass only to themselves; that matches the
        // old behavior and avoids chat spam during common self-service use.
        boolean selfOnly = targets.size() == 1
                && sender instanceof Player player
                && targets.getFirst().equals(player);
        if (selfOnly) {
            return;
        }
        Component message = targets.size() == 1
                ? Component.text("Gave compass to " + targets.getFirst().getName() + ".", NamedTextColor.GREEN)
                : Component.text("Gave compass to " + targets.size() + " players.", NamedTextColor.GREEN);
        sender.sendMessage(message);
    }
}
