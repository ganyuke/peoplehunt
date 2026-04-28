package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.game.compass.CompassDimensionMode;
import io.github.ganyuke.peoplehunt.game.compass.CompassTargetProvider;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Tracks runner movement for two separate gameplay concerns:
 * first movement after /prime starts the match, and ongoing movement keeps compass targeting and
 * cross-dimension last-known-location data current.
 */
public class MatchMovementService implements Listener, CompassTargetProvider {
    private final MatchManager matchManager;

    public MatchMovementService(MatchManager matchManager) {
        this.matchManager = matchManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isSelectedRunner(event.getPlayer())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        maybeStartPrimedMatch(from, to);
        updateCurrentRunnerLocation(to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!isSelectedRunner(event.getPlayer())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        maybeStartPrimedMatch(from, to);

        MatchSession session = matchManager.getSession();
        if (session == null || from.getWorld() == null || to.getWorld() == null) return;
        if (!from.getWorld().getUID().equals(to.getWorld().getUID())) {
            // Store the departure point as the holder-facing last-known location for that source
            // dimension so compasses can still point somewhere meaningful after dimension changes.
            session.lastKnownRunnerLocations.put(from.getWorld().getUID(), from.clone());
            if (from.getWorld().getEnvironment() == World.Environment.NORMAL
                    && to.getWorld().getEnvironment() == World.Environment.THE_END
                    && event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
                // End-return assistance is based on the actual stronghold End Portal the runner used,
                // not arbitrary command/plugin teleports into the End.
                session.lastRunnerOverworldEndPortal = from.clone();
            }
        }
        updateCurrentRunnerLocation(to);
    }

    private boolean isSelectedRunner(Player player) {
        return player.getUniqueId().equals(matchManager.selectedRunnerUuid());
    }

    private void maybeStartPrimedMatch(Location from, Location to) {
        // Primed matches begin on the runner's first real displacement. Treat teleports the same
        // way as movement here so plugins, portals, and commands cannot sidestep the prime state.
        if (matchManager.isPrimeActive() && changedBlockOrWorld(from, to)) {
            startPrimedMatch();
        }
    }

    private void updateCurrentRunnerLocation(Location location) {
        MatchSession session = matchManager.getSession();
        if (session != null && location != null) {
            session.currentRunnerLocation = location.clone();
        }
    }

    @Override
    public Location resolveCompassTarget(Player holder) {
        UUID runnerUuid = matchManager.selectedRunnerUuid();
        if (runnerUuid == null) return null;

        Player onlineRunner = Bukkit.getPlayer(runnerUuid);
        Location current = null;
        MatchSession session = matchManager.getSession();

        if (onlineRunner != null) current = onlineRunner.getLocation();
        else if (session != null && session.currentRunnerLocation != null) current = session.currentRunnerLocation.clone();

        if (current == null) return null;
        if (holder.getWorld().getUID().equals(current.getWorld().getUID())) return current;

        if (matchManager.getSessionConfig().compassDimensionMode() == CompassDimensionMode.LAST_KNOWN && session != null) {
            // In cross-dimension situations the compass can either clear or point at the last place
            // the runner was seen in the holder's current dimension.
            Location lastKnown = session.lastKnownRunnerLocations.get(holder.getWorld().getUID());
            if (lastKnown != null) return lastKnown.clone();
        }
        return null;
    }

    @Override
    public UUID selectedRunnerUuid() {
        return matchManager.selectedRunnerUuid();
    }

    private void startPrimedMatch() {
        try {
            matchManager.startNow();
        } catch (IOException exception) {
            // startNow() already warned operators and left the match unstarted.
        }
    }

    private boolean changedBlockOrWorld(Location from, Location to) {
        if (from == null || to == null) return false;
        boolean changedWorld = from.getWorld() != null && to.getWorld() != null
                && !from.getWorld().getUID().equals(to.getWorld().getUID());
        return changedWorld
                || from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }
}
