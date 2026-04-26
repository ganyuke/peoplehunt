package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.report.ReportService;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

public final class AttributionManager {
    private final MatchManager matchManager;
    private final ReportService reportService;
    private int tickCounter = 0;

    public AttributionManager(MatchManager matchManager, ReportService reportService) {
        this.matchManager = matchManager;
        this.reportService = reportService;
    }

    public void tick() {
        MatchSession session = matchManager.getSession();
        if (session == null) return;

        tickProjectileMap(session.trackedProjectiles.entrySet().iterator());
        tickProjectileMap(session.trackedHostileProjectiles.entrySet().iterator());

        if (++tickCounter % 20 == 0) {
            session.cleanupOldHazards();
        }
    }

    /**
     * Advance one family of tracked projectile UUIDs. Both player-fired and hostile-fired
     * projectiles share exactly the same lifecycle: sample while the entity exists, then close
     * out the path using the last known location once the projectile disappears.
     */
    private void tickProjectileMap(Iterator<? extends Map.Entry<UUID, ?>> iterator) {
        while (iterator.hasNext()) {
            Map.Entry<UUID, ?> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Projectile projectile) || !projectile.isValid()) {
                reportService.finishProjectile(entry.getKey(), entity == null ? null : entity.getLocation());
                iterator.remove();
                continue;
            }
            reportService.recordProjectilePoint(entry.getKey(), projectile.getLocation());
        }
    }

    public void trackProjectileLaunch(Projectile projectile, Player shooter) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(shooter.getUniqueId())) return;

        ItemStack mainHand = shooter.getInventory().getItemInMainHand();
        String weapon = mainHand.getType() == Material.AIR ? projectile.getType().name() : mainHand.getType().name();

        session.trackedProjectiles.put(projectile.getUniqueId(), new MatchSession.ProjectileAttribution(shooter.getUniqueId(), shooter.getName(), weapon));
        reportService.startProjectile(projectile.getUniqueId(), shooter.getUniqueId(), shooter.getName(), "PLAYER", projectile.getType().name(), projectile.getType().name().contains("PEARL") ? "ender_pearl" : "player", null, projectile.getLocation());
    }

    public void trackHostileProjectileLaunch(Projectile projectile, LivingEntity shooter) {
        MatchSession session = matchManager.getSession();
        if (session == null || shooter == null) return;
        Player target = shooter instanceof Mob mob && mob.getTarget() instanceof Player player && matchManager.isParticipant(player.getUniqueId()) ? player : null;
        if (target == null) {
            org.bukkit.Location shooterLocation = shooter.getLocation();
            double best = Double.MAX_VALUE;
            for (UUID uuid : session.roles.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.getWorld().equals(shooter.getWorld())) continue;
                double d = player.getLocation().distanceSquared(shooterLocation);
                if (d < best && d <= 32 * 32) {
                    best = d;
                    target = player;
                }
            }
        }
        if (target == null) return;
        session.trackedHostileProjectiles.put(projectile.getUniqueId(), new MatchSession.HostileProjectileAttribution(shooter.getType().name(), target.getUniqueId(), target.getName(), projectile.getType().name()));
        reportService.startProjectile(projectile.getUniqueId(), null, shooter.getType().name(), shooter.getType().name(), projectile.getType().name(), "hostile", "#f97316", projectile.getLocation());
    }

    public void trackProjectileHit(Projectile projectile) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        if (session.trackedProjectiles.remove(projectile.getUniqueId()) != null) {
            reportService.finishProjectile(projectile.getUniqueId(), projectile.getLocation());
        }
    }

    public void trackHostileProjectileHit(Projectile projectile) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        if (session.trackedHostileProjectiles.remove(projectile.getUniqueId()) != null) {
            reportService.finishProjectile(projectile.getUniqueId(), projectile.getLocation());
        }
    }

    public void trackPlacedLava(Player player, Block block) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(player.getUniqueId())) return;
        session.lavaSources.put(MatchSession.BlockKey.of(block), new MatchSession.Attribution(player.getUniqueId(), player.getName(), "LAVA_BUCKET", null, player.getLocation(), System.currentTimeMillis()));
    }

    public void trackExplosiveHazard(Player player, String weapon, Location location) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(player.getUniqueId())) return;
        session.recentExplosiveHazards.add(new MatchSession.Attribution(player.getUniqueId(), player.getName(), weapon, null, location, System.currentTimeMillis()));
    }

    public MatchSession.Attribution resolveAndStore(EntityDamageByEntityEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return null;
        MatchSession.Attribution attribution = resolveEntityAttribution(session, event.getDamager());
        if (attribution != null) {
            session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        }
        return attribution;
    }

    public MatchSession.Attribution resolveAndStore(EntityDamageByBlockEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return null;
        MatchSession.Attribution attribution = resolveBlockAttribution(session, event.getDamager(), event.getCause(), victim.getLocation());
        if (attribution != null) {
            session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        }
        return attribution;
    }

    public MatchSession.Attribution resolveGenericDamage(Player victim) {
        MatchSession session = matchManager.getSession();
        if (session == null) return null;
        MatchSession.Attribution attribution = session.recentVictimAttribution.get(victim.getUniqueId());
        if (attribution == null) {
            attribution = resolveExplosionAttribution(session, victim.getLocation());
        }
        return attribution;
    }

    public MatchSession.Attribution consumeRecentVictimAttribution(UUID victimUuid) {
        MatchSession session = matchManager.getSession();
        if (session == null) return null;
        return session.recentVictimAttribution.remove(victimUuid);
    }

    public MatchSession.Attribution resolveDeathAttribution(Player player) {
        MatchSession session = matchManager.getSession();
        if (session == null) return null;
        EntityDamageEvent last = player.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            return resolveEntityAttribution(session, byEntity.getDamager());
        }
        if (last instanceof EntityDamageByBlockEvent byBlock) {
            return resolveBlockAttribution(session, byBlock.getDamager(), byBlock.getCause(), player.getLocation());
        }
        if (last != null && (last.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || last.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || last.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK || last.getCause() == EntityDamageEvent.DamageCause.LAVA)) {
            MatchSession.Attribution recent = session.recentVictimAttribution.get(player.getUniqueId());
            if (recent != null) return recent;
            return resolveExplosionAttribution(session, player.getLocation());
        }
        return null;
    }

    private MatchSession.Attribution resolveEntityAttribution(MatchSession session, Entity damager) {
        if (damager instanceof Player player && matchManager.isParticipant(player.getUniqueId())) {
            return new MatchSession.Attribution(player.getUniqueId(), player.getName(), weaponName(player.getInventory().getItemInMainHand()), null, player.getLocation(), System.currentTimeMillis());
        }
        if (damager instanceof Projectile projectile) {
            MatchSession.ProjectileAttribution tracked = session.trackedProjectiles.get(projectile.getUniqueId());
            if (tracked != null) {
                return new MatchSession.Attribution(tracked.playerUuid(), tracked.playerName(), tracked.weapon(), projectile.getUniqueId(), projectile.getLocation(), System.currentTimeMillis());
            }
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player && matchManager.isParticipant(player.getUniqueId())) {
            return new MatchSession.Attribution(player.getUniqueId(), player.getName(), "TNT", null, tnt.getLocation(), System.currentTimeMillis());
        }
        return null;
    }

    private MatchSession.Attribution resolveBlockAttribution(MatchSession session, Block block, EntityDamageEvent.DamageCause cause, Location victimLocation) {
        if (block != null) {
            MatchSession.Attribution lava = session.lavaSources.get(MatchSession.BlockKey.of(block));
            if (lava != null) {
                return lava.withLocation(victimLocation);
            }
        }
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return resolveExplosionAttribution(session, victimLocation);
        }
        return null;
    }

    private MatchSession.Attribution resolveExplosionAttribution(MatchSession session, Location location) {
        long cutoff = System.currentTimeMillis() - 5000L;
        return session.recentExplosiveHazards.stream()
                .filter(attribution -> attribution.createdAtEpochMillis() >= cutoff)
                .filter(attribution -> attribution.location() != null && attribution.location().getWorld() != null)
                .filter(attribution -> location != null && location.getWorld() != null)
                .filter(attribution -> attribution.location().getWorld().getUID().equals(location.getWorld().getUID()))
                .filter(attribution -> attribution.location().distanceSquared(location) <= 64.0)
                .max(java.util.Comparator.comparingLong(MatchSession.Attribution::createdAtEpochMillis))
                .orElse(null);
    }

    private String weaponName(ItemStack item) {
        return (item == null || item.getType().isAir()) ? "HAND" : item.getType().name();
    }
}
