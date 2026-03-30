package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.MatchManager;
import io.github.ganyuke.peoplehunt.game.Role;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.HtmlUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameplayListener implements Listener {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final ReportService reportService;
    private final Map<UUID, ProjectileAttribution> trackedProjectiles = new HashMap<>();
    private final Map<UUID, Attribution> recentVictimAttribution = new HashMap<>();
    private final Map<BlockKey, Attribution> lavaSources = new HashMap<>();
    private final List<Attribution> recentExplosiveHazards = new ArrayList<>();
    private final Map<UUID, Set<String>> milestones = new HashMap<>();
    private boolean globalFirstBloodRecorded;

    public GameplayListener(JavaPlugin plugin, PeopleHuntConfig config, MatchManager matchManager, ReportService reportService) {
        this.plugin = plugin;
        this.config = config;
        this.matchManager = matchManager;
        this.reportService = reportService;
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::sampleProjectiles, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        matchManager.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getPlayer().getUniqueId().equals(matchManager.selectedRunnerUuid())) {
            matchManager.noteRunnerMove(event.getFrom(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.getPlayer().getUniqueId().equals(matchManager.selectedRunnerUuid())) {
            return;
        }
        if (event.getFrom().getWorld() != null && event.getTo() != null && event.getTo().getWorld() != null
                && !event.getFrom().getWorld().getUID().equals(event.getTo().getWorld().getUID())) {
            matchManager.noteRunnerPortal(event.getFrom(), event.getTo());
            recordDimensionMilestone(event.getPlayer(), event.getTo().getWorld().getEnvironment());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        recordDimensionMilestone(event.getPlayer(), event.getPlayer().getWorld().getEnvironment());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> matchManager.onRespawn(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!matchManager.hasActiveMatch()) {
            return;
        }
        Component line = Component.text(event.getPlayer().getName() + ": ").append(event.message());
        reportService.recordChat("chat", event.getPlayer().getUniqueId(), event.getPlayer().getName(), line);
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!config.captureAdvancements() || !matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        String key = event.getAdvancement().getKey().asString();
        Component line = Component.text(event.getPlayer().getName() + " made the advancement [" + key + "]");
        reportService.recordChat("advancement", event.getPlayer().getUniqueId(), event.getPlayer().getName(), line);
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "advancement", key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            return;
        }
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) {
            return;
        }
        String weapon = player.getInventory().getItemInMainHand() == null ? event.getEntity().getType().name() : player.getInventory().getItemInMainHand().getType().name();
        trackedProjectiles.put(event.getEntity().getUniqueId(), new ProjectileAttribution(player.getUniqueId(), player.getName(), weapon));
        reportService.startProjectile(event.getEntity().getUniqueId(), player.getUniqueId(), player.getName(), event.getEntity().getType().name(), event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (trackedProjectiles.remove(event.getEntity().getUniqueId()) != null) {
            reportService.finishProjectile(event.getEntity().getUniqueId(), event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !matchManager.hasActiveMatch()) {
            return;
        }
        Attribution attribution = resolveEntityAttribution(event.getDamager());
        if (attribution == null) {
            return;
        }
        recentVictimAttribution.put(victim.getUniqueId(), attribution);
        reportService.recordDamage(
                attribution.playerUuid(),
                attribution.playerName(),
                victim.getUniqueId(),
                victim.getName(),
                event.getCause().name(),
                event.getFinalDamage(),
                attribution.weapon(),
                attribution.projectileUuid(),
                attribution.location(),
                victim.getLocation()
        );
        if (matchManager.isHunter(attribution.playerUuid())) {
            Player attacker = Bukkit.getPlayer(attribution.playerUuid());
            if (attacker != null) {
                matchManager.noteHunterPlayerDamage(attacker, event.getFinalDamage());
            }
        }
        recordMilestoneIfAbsent(attribution.playerUuid(), attribution.playerName(), "first_hit", "First hit");
        if (!globalFirstBloodRecorded) {
            globalFirstBloodRecorded = true;
            reportService.recordTimeline(attribution.playerUuid(), attribution.playerName(), "milestone", "First blood");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByBlock(EntityDamageByBlockEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !matchManager.hasActiveMatch()) {
            return;
        }
        Attribution attribution = resolveBlockAttribution(event.getDamager(), event.getCause(), victim.getLocation());
        if (attribution == null) {
            return;
        }
        recentVictimAttribution.put(victim.getUniqueId(), attribution);
        reportService.recordDamage(
                attribution.playerUuid(),
                attribution.playerName(),
                victim.getUniqueId(),
                victim.getName(),
                event.getCause().name(),
                event.getFinalDamage(),
                attribution.weapon(),
                attribution.projectileUuid(),
                attribution.location(),
                victim.getLocation()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || event instanceof EntityDamageByBlockEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !matchManager.hasActiveMatch()) {
            return;
        }
        Attribution attribution = recentVictimAttribution.get(victim.getUniqueId());
        if (attribution == null) {
            attribution = resolveExplosionAttribution(victim.getLocation());
        }
        if (attribution == null) {
            return;
        }
        reportService.recordDamage(
                attribution.playerUuid(),
                attribution.playerName(),
                victim.getUniqueId(),
                victim.getName(),
                event.getCause().name(),
                event.getFinalDamage(),
                attribution.weapon(),
                attribution.projectileUuid(),
                attribution.location(),
                victim.getLocation()
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHunterDeath(PlayerDeathEvent event) {
        if (!matchManager.hasActiveMatch()) {
            return;
        }
        Player player = event.getPlayer();
        Role role = matchManager.roleOf(player.getUniqueId());
        if (role == Role.HUNTER) {
            KeepInventoryMode mode = matchManager.effectiveKeepInventoryMode(player.getUniqueId());
            if (mode == KeepInventoryMode.ALL) {
                event.setKeepInventory(true);
                event.getDrops().clear();
                event.setKeepLevel(true);
                event.setDroppedExp(0);
            } else if (mode == KeepInventoryMode.KIT) {
                List<ItemStack> preserved = matchManager.captureKitPreservedDrops(event.getDrops());
                if (!preserved.isEmpty()) {
                    matchManager.stashRespawnRestore(player.getUniqueId(), preserved);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!matchManager.hasActiveMatch()) {
            return;
        }
        Player player = event.getPlayer();
        Attribution attribution = recentVictimAttribution.remove(player.getUniqueId());
        if (attribution == null) {
            attribution = resolveDeathAttribution(player);
        }
        String cause = player.getLastDamageCause() == null ? "UNKNOWN" : player.getLastDamageCause().getCause().name();
        reportService.recordDeath(
                player.getUniqueId(),
                player.getName(),
                attribution == null ? null : attribution.playerUuid(),
                attribution == null ? null : attribution.playerName(),
                cause,
                attribution == null ? null : attribution.weapon(),
                player.getLocation(),
                event.deathMessage()
        );
        if (player.getWorld().getEnvironment() == org.bukkit.World.Environment.THE_END) {
            matchManager.notePlayerDiedInEnd(player);
        }
        Role role = matchManager.roleOf(player.getUniqueId());
        if (role == Role.RUNNER) {
            matchManager.noteRunnerDeath(player);
            try {
                matchManager.endHunterVictory();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        } else if (role == Role.HUNTER) {
            matchManager.noteHunterDeath(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon) || !matchManager.hasActiveMatch()) {
            return;
        }
        try {
            matchManager.endRunnerVictory();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type.name().endsWith("_LOG") || type.name().endsWith("_WOOD") || type == Material.CRIMSON_STEM || type == Material.WARPED_STEM) {
            recordMilestoneIfAbsent(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_wood", "First wood");
        }
        if (type == Material.IRON_ORE || type == Material.DEEPSLATE_IRON_ORE) {
            recordMilestoneIfAbsent(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_iron", "First iron");
        }
        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
            recordMilestoneIfAbsent(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_diamond", "First diamond");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) {
            return;
        }
        Material type = event.getItem().getItemStack().getType();
        if (type == Material.IRON_INGOT || type == Material.RAW_IRON) {
            recordMilestoneIfAbsent(player.getUniqueId(), player.getName(), "first_iron", "First iron");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getBlockPlaced().getType() == Material.LAVA || event.getBlockPlaced().getType() == Material.LAVA_CAULDRON) {
            lavaSources.put(BlockKey.of(event.getBlockPlaced()), new Attribution(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "LAVA_BUCKET", null, event.getPlayer().getLocation(), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getBucket() == Material.LAVA_BUCKET) {
            lavaSources.put(BlockKey.of(event.getBlock()), new Attribution(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "LAVA_BUCKET", null, event.getPlayer().getLocation(), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!matchManager.hasActiveMatch() || event.getClickedBlock() == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        Material type = event.getClickedBlock().getType();
        boolean bedExplodes = type.name().endsWith("_BED") && event.getPlayer().getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL;
        boolean anchorExplodes = type == Material.RESPAWN_ANCHOR && event.getPlayer().getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER;
        if (bedExplodes || anchorExplodes) {
            recentExplosiveHazards.add(new Attribution(
                    event.getPlayer().getUniqueId(),
                    event.getPlayer().getName(),
                    bedExplodes ? "BED_EXPLOSION" : "RESPAWN_ANCHOR",
                    null,
                    event.getClickedBlock().getLocation(),
                    System.currentTimeMillis()
            ));
        }
    }

    private void sampleProjectiles() {
        Iterator<Map.Entry<UUID, ProjectileAttribution>> iterator = trackedProjectiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ProjectileAttribution> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Projectile projectile) || !projectile.isValid()) {
                reportService.finishProjectile(entry.getKey(), entity == null ? null : entity.getLocation());
                iterator.remove();
                continue;
            }
            reportService.recordProjectilePoint(entry.getKey(), projectile.getLocation());
        }
        cleanupOldHazards();
    }

    private void cleanupOldHazards() {
        long cutoff = System.currentTimeMillis() - 30000L;
        lavaSources.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis() < cutoff);
        recentExplosiveHazards.removeIf(attribution -> attribution.createdAtEpochMillis() < cutoff);
        recentVictimAttribution.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis() < cutoff);
    }

    private void recordDimensionMilestone(Player player, org.bukkit.World.Environment environment) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) {
            return;
        }
        if (environment == org.bukkit.World.Environment.NETHER) {
            recordMilestoneIfAbsent(player.getUniqueId(), player.getName(), "first_nether", "Entered the Nether");
        }
        if (environment == org.bukkit.World.Environment.THE_END) {
            recordMilestoneIfAbsent(player.getUniqueId(), player.getName(), "first_end", "Entered the End");
        }
    }

    private void recordMilestoneIfAbsent(UUID uuid, String name, String key, String description) {
        Set<String> playerMilestones = milestones.computeIfAbsent(uuid, ignored -> new HashSet<>());
        if (playerMilestones.add(key)) {
            reportService.recordMilestone(uuid, name, key, description);
        }
    }

    private Attribution resolveEntityAttribution(Entity damager) {
        if (damager instanceof Player player && matchManager.isParticipant(player.getUniqueId())) {
            return new Attribution(player.getUniqueId(), player.getName(), weaponName(player.getInventory().getItemInMainHand()), null, player.getLocation(), System.currentTimeMillis());
        }
        if (damager instanceof Projectile projectile) {
            ProjectileAttribution tracked = trackedProjectiles.get(projectile.getUniqueId());
            if (tracked != null) {
                return new Attribution(tracked.playerUuid(), tracked.playerName(), tracked.weapon(), projectile.getUniqueId(), projectile.getLocation(), System.currentTimeMillis());
            }
            if (projectile.getShooter() instanceof Player player && matchManager.isParticipant(player.getUniqueId())) {
                return new Attribution(player.getUniqueId(), player.getName(), projectile.getType().name(), projectile.getUniqueId(), player.getLocation(), System.currentTimeMillis());
            }
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player && matchManager.isParticipant(player.getUniqueId())) {
            return new Attribution(player.getUniqueId(), player.getName(), "TNT", null, tnt.getLocation(), System.currentTimeMillis());
        }
        return null;
    }

    private Attribution resolveBlockAttribution(Block block, EntityDamageEvent.DamageCause cause, Location victimLocation) {
        if (block != null) {
            Attribution lava = lavaSources.get(BlockKey.of(block));
            if (lava != null) {
                return lava.withLocation(victimLocation);
            }
        }
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return resolveExplosionAttribution(victimLocation);
        }
        return null;
    }

    private Attribution resolveExplosionAttribution(Location location) {
        long cutoff = System.currentTimeMillis() - 5000L;
        return recentExplosiveHazards.stream()
                .filter(attribution -> attribution.createdAtEpochMillis() >= cutoff)
                .filter(attribution -> attribution.location() != null && attribution.location().getWorld() != null && location != null && location.getWorld() != null)
                .filter(attribution -> attribution.location().getWorld().getUID().equals(location.getWorld().getUID()))
                .filter(attribution -> attribution.location().distanceSquared(location) <= 64.0)
                .max((a, b) -> Long.compare(a.createdAtEpochMillis(), b.createdAtEpochMillis()))
                .orElse(null);
    }

    private Attribution resolveDeathAttribution(Player player) {
        EntityDamageEvent last = player.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            return resolveEntityAttribution(byEntity.getDamager());
        }
        if (last instanceof EntityDamageByBlockEvent byBlock) {
            return resolveBlockAttribution(byBlock.getDamager(), byBlock.getCause(), player.getLocation());
        }
        if (last != null && (last.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || last.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || last.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK || last.getCause() == EntityDamageEvent.DamageCause.LAVA)) {
            Attribution recent = recentVictimAttribution.get(player.getUniqueId());
            if (recent != null) {
                return recent;
            }
            return resolveExplosionAttribution(player.getLocation());
        }
        return null;
    }

    private String weaponName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "HAND";
        }
        return item.getType().name();
    }

    private record ProjectileAttribution(UUID playerUuid, String playerName, String weapon) {}

    private record Attribution(UUID playerUuid, String playerName, String weapon, UUID projectileUuid, Location location, long createdAtEpochMillis) {
        private Attribution withLocation(Location location) {
            return new Attribution(playerUuid, playerName, weapon, projectileUuid, location, createdAtEpochMillis);
        }
    }

    private record BlockKey(UUID worldUuid, int x, int y, int z) {
        private static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
