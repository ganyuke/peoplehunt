package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.PrettyNames;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
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
    private final int lavaAttributionHorizontalRadius;
    private final int lavaAttributionVerticalRadius;
    private final long lavaAttributionWindowMillis;
    private final double explosionAttributionRadiusSquared;
    private final long explosionAttributionWindowMillis;
    private int tickCounter = 0;

    public record DeathAttribution(
            UUID killerUuid,
            String killerName,
            String killerEntityType,
            String weapon,
            UUID projectileUuid,
            Location location
    ) {}

    public AttributionManager(PeopleHuntConfig config, MatchManager matchManager, ReportService reportService) {
        this.matchManager = matchManager;
        this.reportService = reportService;
        this.lavaAttributionHorizontalRadius = Math.max(0, config.lavaAttributionHorizontalRadius());
        this.lavaAttributionVerticalRadius = Math.max(0, config.lavaAttributionVerticalRadius());
        this.lavaAttributionWindowMillis = Math.max(0L, config.lavaAttributionWindowMillis());
        double explosionRadius = Math.max(0.0, config.explosionAttributionRadius());
        // Config is expressed in blocks for operators; square it once here so hot-path distance
        // checks can keep using distanceSquared(...) without repeated conversion work.
        this.explosionAttributionRadiusSquared = explosionRadius * explosionRadius;
        this.explosionAttributionWindowMillis = Math.max(0L, config.explosionAttributionWindowMillis());
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
        MatchSession.Attribution attribution = resolveEntityDamage(event);
        if (attribution != null) {
            session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        }
        return attribution;
    }

    public MatchSession.Attribution resolveAndStore(EntityDamageByBlockEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return null;
        MatchSession.Attribution attribution = resolveBlockDamage(event);
        if (attribution != null) {
            session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        }
        return attribution;
    }

    public MatchSession.Attribution resolveEntityDamage(EntityDamageByEntityEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || event == null) {
            return null;
        }
        return resolveEntityAttribution(session, event.getDamager());
    }

    public MatchSession.Attribution resolveBlockDamage(EntityDamageByBlockEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || event == null || event.getEntity() == null) {
            return null;
        }
        return resolveBlockAttribution(session, event.getDamager(), event.getCause(), event.getEntity().getLocation());
    }

    public MatchSession.Attribution resolveGenericDamage(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        return resolveGenericDamage(victim, last == null ? null : last.getCause());
    }

    public MatchSession.Attribution resolveGenericDamage(Player victim, EntityDamageEvent.DamageCause cause) {
        MatchSession session = matchManager.getSession();
        if (session == null) return null;
        MatchSession.Attribution attribution = session.recentVictimAttribution.get(victim.getUniqueId());
        if (attribution == null) {
            attribution = resolveGenericDamage(victim.getLocation(), cause);
        }
        if (attribution != null) {
            session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        }
        return attribution;
    }

    public MatchSession.Attribution resolveGenericDamage(Location victimLocation, EntityDamageEvent.DamageCause cause) {
        MatchSession session = matchManager.getSession();
        if (session == null) {
            return null;
        }
        if (isLavaLike(cause)) {
            MatchSession.Attribution lava = resolveLavaAttribution(session, victimLocation);
            if (lava != null) {
                return lava;
            }
        }
        return resolveExplosionAttribution(session, victimLocation);
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
        if (last == null) {
            return null;
        }
        return switch (last) {
            case EntityDamageByEntityEvent byEntity -> resolveEntityAttribution(session, byEntity.getDamager());
            case EntityDamageByBlockEvent byBlock ->
                    resolveBlockAttribution(session, byBlock.getDamager(), byBlock.getCause(), player.getLocation());
            default -> {
                EntityDamageEvent.DamageCause cause = last.getCause();
                if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                        || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                        || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                        || cause == EntityDamageEvent.DamageCause.LAVA) {
                    MatchSession.Attribution recent = session.recentVictimAttribution.get(player.getUniqueId());
                    if (recent != null) {
                        yield recent;
                    }
                    if (isLavaLike(cause)) {
                        MatchSession.Attribution lava = resolveLavaAttribution(session, player.getLocation());
                        if (lava != null) {
                            yield lava;
                        }
                    }
                    yield resolveExplosionAttribution(session, player.getLocation());
                }
                yield null;
            }
        };
    }

    public DeathAttribution resolveLivingEntityDeathAttribution(LivingEntity victim, DamageSource damageSource) {
        MatchSession session = matchManager.getSession();
        if (session == null || victim == null) {
            return null;
        }

        Entity causingEntity = damageSource == null ? null : damageSource.getCausingEntity();
        Entity directEntity = damageSource == null ? null : damageSource.getDirectEntity();

        DeathAttribution fromEntities = resolveDeathAttribution(session, causingEntity, directEntity, victim.getLocation());
        if (fromEntities != null) {
            return fromEntities;
        }

        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        EntityDamageEvent.DamageCause cause = lastDamage == null ? null : lastDamage.getCause();
        if (isLavaLike(cause)) {
            MatchSession.Attribution lava = resolveLavaAttribution(session, victim.getLocation());
            if (lava != null) {
                return new DeathAttribution(lava.playerUuid(), lava.playerName(), lava.playerUuid() == null ? null : "PLAYER", lava.weapon(), lava.projectileUuid(), lava.location());
            }
        }
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            MatchSession.Attribution explosion = resolveExplosionAttribution(session, victim.getLocation());
            if (explosion != null) {
                return new DeathAttribution(explosion.playerUuid(), explosion.playerName(), explosion.playerUuid() == null ? null : "PLAYER", explosion.weapon(), explosion.projectileUuid(), explosion.location());
            }
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

    private DeathAttribution resolveDeathAttribution(MatchSession session, Entity causingEntity, Entity directEntity, Location fallbackLocation) {
        if (causingEntity instanceof Player player) {
            UUID projectileUuid = directEntity instanceof Projectile projectile ? projectile.getUniqueId() : null;
            String weapon = projectileUuid == null ? weaponName(player.getInventory().getItemInMainHand()) : directEntity.getType().name();
            MatchSession.ProjectileAttribution tracked = projectileUuid == null ? null : session.trackedProjectiles.get(projectileUuid);
            if (tracked != null) {
                weapon = tracked.weapon();
            }
            return new DeathAttribution(
                    player.getUniqueId(),
                    player.getName(),
                    "PLAYER",
                    weapon,
                    projectileUuid,
                    sourceLocation(causingEntity, directEntity, fallbackLocation)
            );
        }

        if (causingEntity instanceof LivingEntity living) {
            UUID projectileUuid = directEntity instanceof Projectile projectile ? projectile.getUniqueId() : null;
            String weapon = projectileUuid == null ? living.getType().name() : directEntity.getType().name();
            return new DeathAttribution(
                    null,
                    PrettyNames.enumName(living.getType().name()),
                    living.getType().name(),
                    weapon,
                    projectileUuid,
                    sourceLocation(causingEntity, directEntity, fallbackLocation)
            );
        }

        if (directEntity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return new DeathAttribution(
                    player.getUniqueId(),
                    player.getName(),
                    "PLAYER",
                    "TNT",
                    null,
                    sourceLocation(player, directEntity, fallbackLocation)
            );
        }

        if (directEntity instanceof Projectile projectile) {
            MatchSession.ProjectileAttribution trackedPlayerProjectile = session.trackedProjectiles.get(projectile.getUniqueId());
            if (trackedPlayerProjectile != null) {
                return new DeathAttribution(
                        trackedPlayerProjectile.playerUuid(),
                        trackedPlayerProjectile.playerName(),
                        trackedPlayerProjectile.playerUuid() == null ? null : "PLAYER",
                        trackedPlayerProjectile.weapon(),
                        projectile.getUniqueId(),
                        sourceLocation(null, directEntity, fallbackLocation)
                );
            }
            MatchSession.HostileProjectileAttribution trackedHostileProjectile = session.trackedHostileProjectiles.get(projectile.getUniqueId());
            if (trackedHostileProjectile != null) {
                return new DeathAttribution(
                        null,
                        PrettyNames.enumName(trackedHostileProjectile.shooterEntityType()),
                        trackedHostileProjectile.shooterEntityType(),
                        trackedHostileProjectile.weapon(),
                        projectile.getUniqueId(),
                        sourceLocation(null, directEntity, fallbackLocation)
                );
            }
        }

        return null;
    }

    private Location sourceLocation(Entity causingEntity, Entity directEntity, Location fallbackLocation) {
        if (directEntity != null && directEntity.getWorld() != null) {
            return directEntity.getLocation();
        }
        if (causingEntity != null && causingEntity.getWorld() != null) {
            return causingEntity.getLocation();
        }
        return fallbackLocation;
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


    private boolean isLavaLike(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
    }

    private MatchSession.Attribution resolveLavaAttribution(MatchSession session, Location location) {
        if (location == null || location.getWorld() == null) return null;
        long cutoff = System.currentTimeMillis() - lavaAttributionWindowMillis;
        UUID worldUuid = location.getWorld().getUID();
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();
        return session.lavaSources.entrySet().stream()
                .filter(entry -> entry.getValue().createdAtEpochMillis() >= cutoff)
                .filter(entry -> entry.getKey().worldUuid().equals(worldUuid))
                .filter(entry -> Math.abs(entry.getKey().x() - bx) <= lavaAttributionHorizontalRadius
                        && Math.abs(entry.getKey().y() - by) <= lavaAttributionVerticalRadius
                        && Math.abs(entry.getKey().z() - bz) <= lavaAttributionHorizontalRadius)
                .min(java.util.Comparator.comparingDouble(entry -> squared(entry.getKey(), bx, by, bz)))
                .map(entry -> entry.getValue().withLocation(location))
                .orElse(null);
    }

    private double squared(MatchSession.BlockKey key, int x, int y, int z) {
        double dx = key.x() - x;
        double dy = key.y() - y;
        double dz = key.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private MatchSession.Attribution resolveExplosionAttribution(MatchSession session, Location location) {
        long cutoff = System.currentTimeMillis() - explosionAttributionWindowMillis;
        return session.recentExplosiveHazards.stream()
                .filter(attribution -> attribution.createdAtEpochMillis() >= cutoff)
                .filter(attribution -> attribution.location() != null && attribution.location().getWorld() != null)
                .filter(attribution -> location != null && location.getWorld() != null)
                .filter(attribution -> attribution.location().getWorld().getUID().equals(location.getWorld().getUID()))
                .filter(attribution -> attribution.location().distanceSquared(location) <= explosionAttributionRadiusSquared)
                .max(java.util.Comparator.comparingLong(MatchSession.Attribution::createdAtEpochMillis))
                .orElse(null);
    }

    private String weaponName(ItemStack item) {
        return (item == null || item.getType().isAir()) ? "HAND" : item.getType().name();
    }
}
