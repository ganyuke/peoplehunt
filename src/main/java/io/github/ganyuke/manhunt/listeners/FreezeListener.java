package io.github.ganyuke.manhunt.listeners;

import io.github.ganyuke.manhunt.game.FreezeService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class FreezeListener implements Listener {
    private final FreezeService freezeService;

    public FreezeListener(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!freezeService.isFrozen() || !event.hasChangedBlock()) {
            return;
        }
        if (!freezeService.shouldFreeze(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setTo(event.getFrom());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!freezeService.isFrozen()) {
            return;
        }
        if (event.getEntity() instanceof Player player && freezeService.shouldFreeze(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!freezeService.isFrozen()) {
            return;
        }
        if (event.getDamager() instanceof Player player && freezeService.shouldFreeze(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player && freezeService.shouldFreeze(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (freezeService.shouldFreeze(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (freezeService.shouldFreeze(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (freezeService.shouldFreeze(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (freezeService.shouldFreeze(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
