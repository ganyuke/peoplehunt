package io.github.ganyuke.manhunt.listeners;

import io.github.ganyuke.manhunt.analytics.AnalyticsRecorder;
import io.github.ganyuke.manhunt.analytics.LifeTracker;
import io.github.ganyuke.manhunt.catchup.DeathstreakService;
import io.github.ganyuke.manhunt.game.MatchManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import io.github.ganyuke.manhunt.game.RoleService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.time.Instant;

public final class CombatAnalyticsListener implements Listener {
    private final MatchManager matchManager;
    private final RoleService roleService;
    private final AnalyticsRecorder analyticsRecorder;
    private final LifeTracker lifeTracker;
    private final DeathstreakService deathstreakService;

    public CombatAnalyticsListener(MatchManager matchManager, RoleService roleService, AnalyticsRecorder analyticsRecorder, LifeTracker lifeTracker, DeathstreakService deathstreakService) {
        this.matchManager = matchManager;
        this.roleService = roleService;
        this.analyticsRecorder = analyticsRecorder;
        this.lifeTracker = lifeTracker;
        this.deathstreakService = deathstreakService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onParticipantDamage(EntityDamageEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!session.isParticipant(victim.getUniqueId())) {
            return;
        }
        Entity causing = event.getDamageSource().getCausingEntity();
        Entity direct = event.getDamageSource().getDirectEntity();
        Player attackingPlayer = causing instanceof Player player ? player : null;
        Location sourceLocation = null;
        if (causing != null) {
            sourceLocation = causing.getLocation().clone();
        } else if (direct != null) {
            sourceLocation = direct.getLocation().clone();
        } else if (event.getDamageSource().getDamageLocation() != null) {
            sourceLocation = event.getDamageSource().getDamageLocation().clone();
        }
        analyticsRecorder.recordDamage(
                session.sessionId(),
                victim.getUniqueId(),
                attackingPlayer == null ? null : attackingPlayer.getUniqueId(),
                Instant.now(),
                event.getDamage(),
                event.getFinalDamage(),
                event.getDamageSource().getDamageType().toString(),
                causing == null ? null : causing.getType().name(),
                direct == null ? null : direct.getType().name(),
                causing == null ? null : causing.getUniqueId(),
                sourceLocation
        );
        if (attackingPlayer != null
                && session.runnerId().equals(victim.getUniqueId())
                && roleService.isHunter(attackingPlayer.getUniqueId())
                && event.getFinalDamage() > 0.0D) {
            deathstreakService.recordContribution(attackingPlayer, event.getFinalDamage());
            matchManager.recordParticipantDamage(victim.getUniqueId(), attackingPlayer.getUniqueId(), event.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHunterDeathCatchup(PlayerDeathEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !roleService.isHunter(event.getEntity().getUniqueId())) {
            return;
        }
        deathstreakService.handleHunterDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onParticipantDeathRecord(PlayerDeathEvent event) {
        MatchSession session = matchManager.getCurrentSession();
        if (session == null || !session.isRunning() || !session.isParticipant(event.getEntity().getUniqueId())) {
            return;
        }
        Role role = roleService.getRole(event.getEntity().getUniqueId());
        if (role == Role.NONE) {
            role = session.runnerId().equals(event.getEntity().getUniqueId()) ? Role.RUNNER : Role.HUNTER;
        }
        Entity causing = event.getDamageSource().getCausingEntity();
        Entity direct = event.getDamageSource().getDirectEntity();
        analyticsRecorder.recordDeath(
                session.sessionId(),
                event.getEntity().getUniqueId(),
                role,
                lifeTracker.currentLifeNumber(event.getEntity().getUniqueId()),
                Instant.now(),
                event.getEntity().getLocation().clone(),
                event.getDamageSource().getDamageType().toString(),
                causing == null ? null : causing.getType().name(),
                direct == null ? null : direct.getType().name(),
                causing == null ? null : causing.getUniqueId()
        );
        matchManager.recordParticipantDeath(event.getEntity().getUniqueId(), role);
    }
}
