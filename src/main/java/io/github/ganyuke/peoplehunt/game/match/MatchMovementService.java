package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import java.io.IOException;
import java.util.UUID;

import io.github.ganyuke.peoplehunt.game.compass.CompassDimensionMode;
import io.github.ganyuke.peoplehunt.game.compass.CompassTargetProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class MatchMovementService implements Listener, CompassTargetProvider {
    private final MatchManager matchManager;
    private final PeopleHuntConfig config;

    public MatchMovementService(MatchManager matchManager, PeopleHuntConfig config) {
        this.matchManager = matchManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.getPlayer().getUniqueId().equals(matchManager.selectedRunnerUuid())) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (matchManager.isPrimeActive() && movedPosition(from, to)) {
            try {
                matchManager.startNow();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start match after prime", exception);
            }
        }

        MatchSession session = matchManager.getSession();
        if (session != null && to != null) {
            session.currentRunnerLocation = to.clone();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.getPlayer().getUniqueId().equals(matchManager.selectedRunnerUuid())) return;
        MatchSession session = matchManager.getSession();
        if (session == null) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != null && to != null && to.getWorld() != null && !from.getWorld().getUID().equals(to.getWorld().getUID())) {
            session.lastKnownRunnerLocations.put(from.getWorld().getUID(), from.clone());
            if (from.getWorld().getEnvironment() == World.Environment.NORMAL && to.getWorld().getEnvironment() == World.Environment.THE_END) {
                session.lastRunnerOverworldEndPortal = from.clone();
            }
            session.currentRunnerLocation = to.clone();
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

        if (config.compassDimensionMode() == CompassDimensionMode.LAST_KNOWN && session != null) {
            Location lastKnown = session.lastKnownRunnerLocations.get(holder.getWorld().getUID());
            if (lastKnown != null) return lastKnown.clone();
        }
        return null;
    }

    @Override
    public UUID selectedRunnerUuid() {
        return matchManager.selectedRunnerUuid();
    }

    private boolean movedPosition(Location from, Location to) {
        return from != null && to != null && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ());
    }
}