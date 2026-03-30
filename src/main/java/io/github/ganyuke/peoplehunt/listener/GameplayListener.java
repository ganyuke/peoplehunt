package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchSession;
import io.github.ganyuke.peoplehunt.game.match.MatchSession.Attribution;
import io.github.ganyuke.peoplehunt.game.match.MatchSession.ProjectileAttribution;
import io.github.ganyuke.peoplehunt.game.match.MatchSession.BlockKey;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameplayListener implements Listener {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final ReportService reportService;
    private int tickCounter = 0;

    public GameplayListener(JavaPlugin plugin, PeopleHuntConfig config, MatchManager matchManager, ReportService reportService) {
        this.plugin = plugin;
        this.config = config;
        this.matchManager = matchManager;
        this.reportService = reportService;
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickTasks, 1L, 1L);
    }

    private void tickTasks() {
        MatchSession session = matchManager.getSession();
        if (session == null) return;

        Iterator<Map.Entry<UUID, MatchSession.ProjectileAttribution>> iterator = session.trackedProjectiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MatchSession.ProjectileAttribution> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Projectile projectile) || !projectile.isValid()) {
                reportService.finishProjectile(entry.getKey(), entity == null ? null : entity.getLocation());
                iterator.remove();
                continue;
            }
            reportService.recordProjectilePoint(entry.getKey(), projectile.getLocation());
        }

        if (++tickCounter % 20 == 0) session.cleanupOldHazards();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!matchManager.hasActiveMatch()) return;
        Component line = Component.text(event.getPlayer().getName() + ": ").append(event.message());
        reportService.recordChat("chat", event.getPlayer().getUniqueId(), event.getPlayer().getName(), line);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!config.captureAdvancements() || !matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        String key = event.getAdvancement().getKey().asString();
        Component line = Component.text(event.getPlayer().getName() + " made the advancement [" + key + "]");
        reportService.recordChat("advancement", event.getPlayer().getUniqueId(), event.getPlayer().getName(), line);
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "advancement", key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity().getShooter() instanceof Player player) || !matchManager.isParticipant(player.getUniqueId())) return;

        String weapon = player.getInventory().getItemInMainHand() == null ? event.getEntity().getType().name() : player.getInventory().getItemInMainHand().getType().name();
        session.trackedProjectiles.put(event.getEntity().getUniqueId(), new MatchSession.ProjectileAttribution(player.getUniqueId(), player.getName(), weapon));
        reportService.startProjectile(event.getEntity().getUniqueId(), player.getUniqueId(), player.getName(), event.getEntity().getType().name(), event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        MatchSession session = matchManager.getSession();
        if (session != null && session.trackedProjectiles.remove(event.getEntity().getUniqueId()) != null) {
            reportService.finishProjectile(event.getEntity().getUniqueId(), event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return;

        Attribution attribution = resolveEntityAttribution(session, event.getDamager());
        if (attribution == null) return;

        session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        reportService.recordDamage(
                attribution.playerUuid(), attribution.playerName(), victim.getUniqueId(), victim.getName(),
                event.getCause().name(), event.getFinalDamage(), attribution.weapon(), attribution.projectileUuid(),
                attribution.location(), victim.getLocation()
        );

        if (matchManager.isHunter(attribution.playerUuid())) {
            MatchSession.DeathstreakState state = session.deathstreaks.get(attribution.playerUuid());
            if (state != null) {
                state.damageThisLife += event.getFinalDamage();
                DeathstreakTier activeTier = config.deathstreakTiers().stream().filter(t -> state.streakDeaths >= t.deaths()).max(java.util.Comparator.comparingInt(DeathstreakTier::deaths)).orElse(null);
                if (activeTier != null && state.damageThisLife >= activeTier.damageToReset()) {
                    state.streakDeaths = 0;
                    state.damageThisLife = 0.0;
                    reportService.recordTimeline(attribution.playerUuid(), attribution.playerName(), "deathstreak", "deathstreak reset by dealing enough damage");
                }
            }
        }

        recordMilestoneIfAbsent(session, attribution.playerUuid(), attribution.playerName(), "first_hit", "First hit");
        if (!session.globalFirstBloodRecorded) {
            session.globalFirstBloodRecorded = true;
            reportService.recordTimeline(attribution.playerUuid(), attribution.playerName(), "milestone", "First blood");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByBlock(EntityDamageByBlockEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return;

        Attribution attribution = resolveBlockAttribution(session, event.getDamager(), event.getCause(), victim.getLocation());
        if (attribution == null) return;

        session.recentVictimAttribution.put(victim.getUniqueId(), attribution);
        reportService.recordDamage(attribution.playerUuid(), attribution.playerName(), victim.getUniqueId(), victim.getName(), event.getCause().name(), event.getFinalDamage(), attribution.weapon(), attribution.projectileUuid(), attribution.location(), victim.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || event instanceof EntityDamageByEntityEvent || event instanceof EntityDamageByBlockEvent || !(event.getEntity() instanceof Player victim)) return;

        Attribution attribution = session.recentVictimAttribution.get(victim.getUniqueId());
        if (attribution == null) attribution = resolveExplosionAttribution(session, victim.getLocation());
        if (attribution == null) return;

        reportService.recordDamage(attribution.playerUuid(), attribution.playerName(), victim.getUniqueId(), victim.getName(), event.getCause().name(), event.getFinalDamage(), attribution.weapon(), attribution.projectileUuid(), attribution.location(), victim.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;

        Player player = event.getPlayer();
        MatchSession.Attribution attribution = session.recentVictimAttribution.remove(player.getUniqueId());
        if (attribution == null) attribution = resolveDeathAttribution(session, player);

        String cause = player.getLastDamageCause() == null ? "UNKNOWN" : player.getLastDamageCause().getCause().name();
        reportService.recordDeath(
                player.getUniqueId(), player.getName(),
                attribution == null ? null : attribution.playerUuid(), attribution == null ? null : attribution.playerName(),
                cause, attribution == null ? null : attribution.weapon(),
                player.getLocation(), event.deathMessage()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        org.bukkit.World.Environment env = event.getPlayer().getWorld().getEnvironment();
        if (env == org.bukkit.World.Environment.NETHER) recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_nether", "Entered the Nether");
        if (env == org.bukkit.World.Environment.THE_END) recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_end", "Entered the End");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        Material type = event.getBlock().getType();
        if (Tag.LOGS.isTagged(type)) recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_wood", "First wood");
        if (type == Material.IRON_ORE || type == Material.DEEPSLATE_IRON_ORE) recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_iron", "First iron");
        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_diamond", "First diamond");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player player) || !matchManager.isParticipant(player.getUniqueId())) return;

        Material type = event.getItem().getItemStack().getType();
        if (type == Material.IRON_INGOT || type == Material.RAW_IRON) recordMilestoneIfAbsent(session, player.getUniqueId(), player.getName(), "first_iron", "First iron");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        if (event.getBlockPlaced().getType() == Material.LAVA || event.getBlockPlaced().getType() == Material.LAVA_CAULDRON) {
            session.lavaSources.put(BlockKey.of(event.getBlockPlaced()), new Attribution(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "LAVA_BUCKET", null, event.getPlayer().getLocation(), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        if (event.getBucket() == Material.LAVA_BUCKET) {
            session.lavaSources.put(BlockKey.of(event.getBlock()), new Attribution(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "LAVA_BUCKET", null, event.getPlayer().getLocation(), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || event.getClickedBlock() == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        Material type = event.getClickedBlock().getType();
        boolean bedExplodes = type.name().endsWith("_BED") && event.getPlayer().getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL;
        boolean anchorExplodes = type == Material.RESPAWN_ANCHOR && event.getPlayer().getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER;
        if (bedExplodes || anchorExplodes) {
            session.recentExplosiveHazards.add(new Attribution(event.getPlayer().getUniqueId(), event.getPlayer().getName(), bedExplodes ? "BED_EXPLOSION" : "RESPAWN_ANCHOR", null, event.getClickedBlock().getLocation(), System.currentTimeMillis()));
        }
    }

    private void recordMilestoneIfAbsent(MatchSession session, UUID uuid, String name, String key, String description) {
        Set<String> playerMilestones = session.milestones.computeIfAbsent(uuid, ignored -> new HashSet<>());
        if (playerMilestones.add(key)) reportService.recordMilestone(uuid, name, key, description);
    }

    private Attribution resolveEntityAttribution(MatchSession session, Entity damager) {
        if (damager instanceof Player player && matchManager.isParticipant(player.getUniqueId())) return new Attribution(player.getUniqueId(), player.getName(), weaponName(player.getInventory().getItemInMainHand()), null, player.getLocation(), System.currentTimeMillis());
        if (damager instanceof Projectile projectile) {
            ProjectileAttribution tracked = session.trackedProjectiles.get(projectile.getUniqueId());
            if (tracked != null) return new Attribution(tracked.playerUuid(), tracked.playerName(), tracked.weapon(), projectile.getUniqueId(), projectile.getLocation(), System.currentTimeMillis());
            if (projectile.getShooter() instanceof Player player && matchManager.isParticipant(player.getUniqueId())) return new Attribution(player.getUniqueId(), player.getName(), projectile.getType().name(), projectile.getUniqueId(), player.getLocation(), System.currentTimeMillis());
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player && matchManager.isParticipant(player.getUniqueId())) return new Attribution(player.getUniqueId(), player.getName(), "TNT", null, tnt.getLocation(), System.currentTimeMillis());
        return null;
    }

    private Attribution resolveBlockAttribution(MatchSession session, Block block, EntityDamageEvent.DamageCause cause, Location victimLocation) {
        if (block != null) {
            Attribution lava = session.lavaSources.get(BlockKey.of(block));
            if (lava != null) return lava.withLocation(victimLocation);
        }
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return resolveExplosionAttribution(session, victimLocation);
        return null;
    }

    private Attribution resolveExplosionAttribution(MatchSession session, Location location) {
        long cutoff = System.currentTimeMillis() - 5000L;
        return session.recentExplosiveHazards.stream()
                .filter(attribution -> attribution.createdAtEpochMillis() >= cutoff)
                .filter(attribution -> attribution.location() != null && attribution.location().getWorld() != null && location != null && location.getWorld() != null)
                .filter(attribution -> attribution.location().getWorld().getUID().equals(location.getWorld().getUID()))
                .filter(attribution -> attribution.location().distanceSquared(location) <= 64.0)
                .max(java.util.Comparator.comparingLong(Attribution::createdAtEpochMillis))
                .orElse(null);
    }

    private Attribution resolveDeathAttribution(MatchSession session, Player player) {
        EntityDamageEvent last = player.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) return resolveEntityAttribution(session, byEntity.getDamager());
        if (last instanceof EntityDamageByBlockEvent byBlock) return resolveBlockAttribution(session, byBlock.getDamager(), byBlock.getCause(), player.getLocation());
        if (last != null && (last.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || last.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || last.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK || last.getCause() == EntityDamageEvent.DamageCause.LAVA)) {
            Attribution recent = session.recentVictimAttribution.get(player.getUniqueId());
            if (recent != null) return recent;
            return resolveExplosionAttribution(session, player.getLocation());
        }
        return null;
    }

    private String weaponName(ItemStack item) {
        return (item == null || item.getType().isAir()) ? "HAND" : item.getType().name();
    }
}