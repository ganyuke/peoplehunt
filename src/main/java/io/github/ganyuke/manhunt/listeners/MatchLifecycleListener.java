package io.github.ganyuke.manhunt.listeners;

import io.github.ganyuke.manhunt.analytics.LifeTracker;
import io.github.ganyuke.manhunt.analytics.MilestoneService;
import io.github.ganyuke.manhunt.analytics.PathSampler;
import io.github.ganyuke.manhunt.catchup.DeathstreakService;
import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.CompassService;
import io.github.ganyuke.manhunt.game.MatchManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.TerminalCause;
import io.github.ganyuke.manhunt.game.VictoryType;
import org.bukkit.Bukkit;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class MatchLifecycleListener implements Listener {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final MatchManager matchManager;
    private final LifeTracker lifeTracker;
    private final CompassService compassService;
    private final PathSampler pathSampler;
    private final MilestoneService milestoneService;
    private final DeathstreakService deathstreakService;

    public MatchLifecycleListener(JavaPlugin plugin,
                                  ConfigManager configManager,
                                  MatchManager matchManager,
                                  LifeTracker lifeTracker,
                                  CompassService compassService,
                                  PathSampler pathSampler,
                                  MilestoneService milestoneService,
                                  DeathstreakService deathstreakService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.matchManager = matchManager;
        this.lifeTracker = lifeTracker;
        this.compassService = compassService;
        this.pathSampler = pathSampler;
        this.milestoneService = milestoneService;
        this.deathstreakService = deathstreakService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRunnerMove(PlayerMoveEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isPrimed() || !configManager.settings().startOnRunnerMove()) {
            return;
        }
        if (!session.runnerId().equals(event.getPlayer().getUniqueId()) || !event.hasChangedBlock()) {
            return;
        }
        matchManager.start("Runner moved");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onParticipantDeath(PlayerDeathEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !session.isParticipant(event.getEntity().getUniqueId())) {
            return;
        }
        Player player = event.getEntity();
        pathSampler.forceSample(session, player, player.getLocation(), "DEATH", Map.of("damageType", event.getDamageSource().getDamageType().toString()));
        if (session.runnerId().equals(player.getUniqueId())) {
            matchManager.requestTerminalResult(VictoryType.HUNTERS, "Runner died", TerminalCause.RUNNER_DEATH);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onParticipantDeathLifeClose(PlayerDeathEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isParticipant(event.getEntity().getUniqueId())) {
            return;
        }
        lifeTracker.endLife(session, event.getEntity().getUniqueId(), event.getEntity().getLocation(), "death");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragonDeath(EntityDeathEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !(event.getEntity() instanceof EnderDragon)) {
            return;
        }
        matchManager.requestTerminalResult(VictoryType.RUNNER, "Ender Dragon died", TerminalCause.DRAGON_DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        deathstreakService.applyRespawnLocation(event);
        Bukkit.getScheduler().runTask(plugin, () -> {
            MatchSession liveSession = matchManager.getCurrentSession();
            if (liveSession == null || !liveSession.isRunning() || !liveSession.isParticipant(event.getPlayer().getUniqueId())) {
                return;
            }
            lifeTracker.startLife(liveSession, event.getPlayer(), event.getPlayer().getLocation());
            pathSampler.forceSample(liveSession, event.getPlayer(), event.getPlayer().getLocation(), "RESPAWN", Map.of());
            deathstreakService.grantPendingKit(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        if (session.runnerId().equals(event.getPlayer().getUniqueId())) {
            compassService.recordPortalAnchor(event.getFrom(), event.getTo());
        }
        if (session.isRunning()) {
            String toWorld = event.getTo() != null && event.getTo().getWorld() != null ? event.getTo().getWorld().getName() : "unknown";
            pathSampler.forceSample(session, event.getPlayer(), event.getFrom(), "PORTAL", Map.of("toWorld", toWorld));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !session.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        if (session.runnerId().equals(event.getPlayer().getUniqueId())) {
            compassService.recordRunnerLocation(event.getPlayer().getLocation());
        }
        pathSampler.forceSample(session, event.getPlayer(), event.getPlayer().getLocation(), "WORLD_CHANGE", Map.of("world", event.getPlayer().getWorld().getName()));
        milestoneService.handleWorldChange(session, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        if (session.isRunning()) {
            pathSampler.forceSample(session, event.getPlayer(), event.getPlayer().getLocation(), "QUIT", Map.of());
        }
        if (session.isRunning() && session.runnerId().equals(event.getPlayer().getUniqueId()) && configManager.settings().quitAsHunterWin()) {
            matchManager.requestTerminalResult(VictoryType.HUNTERS, "Runner quit", TerminalCause.RUNNER_QUIT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !session.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!lifeTracker.hasActiveLife(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                MatchSession liveSession = matchManager.getCurrentSession();
                if (liveSession != null && liveSession.isRunning() && liveSession.isParticipant(event.getPlayer().getUniqueId())) {
                    lifeTracker.startLife(liveSession, event.getPlayer(), event.getPlayer().getLocation());
                    pathSampler.forceSample(liveSession, event.getPlayer(), event.getPlayer().getLocation(), "JOIN", Map.of());
                }
            });
        }
        milestoneService.handleWorldChange(session, event.getPlayer());
    }
}
