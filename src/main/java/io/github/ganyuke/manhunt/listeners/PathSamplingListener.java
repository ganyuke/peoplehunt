package io.github.ganyuke.manhunt.listeners;

import io.github.ganyuke.manhunt.analytics.PathSampler;
import io.github.ganyuke.manhunt.game.MatchManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;

public final class PathSamplingListener implements Listener {
    private final MatchManager matchManager;
    private final PathSampler pathSampler;

    public PathSamplingListener(MatchManager matchManager, PathSampler pathSampler) {
        this.matchManager = matchManager;
        this.pathSampler = pathSampler;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !session.isParticipant(event.getPlayer().getUniqueId()) || event.getTo() == null) {
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }
        pathSampler.maybeSampleMove(session, event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !session.isParticipant(event.getPlayer().getUniqueId()) || event.getTo() == null) {
            return;
        }
        pathSampler.forceSample(session, event.getPlayer(), event.getTo(), "TELEPORT", Map.of("cause", event.getCause().name()));
    }
}
