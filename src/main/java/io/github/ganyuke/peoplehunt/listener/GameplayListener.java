package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.match.AttributionManager;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchSession;
import io.github.ganyuke.peoplehunt.game.match.MatchSession.Attribution;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

public final class GameplayListener implements Listener {
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final ReportService reportService;
    private final AttributionManager attributionManager;

    public GameplayListener(
            PeopleHuntConfig config,
            MatchManager matchManager,
            ReportService reportService,
            AttributionManager attributionManager
    ) {
        this.config = config;
        this.matchManager = matchManager;
        this.reportService = reportService;
        this.attributionManager = attributionManager;
    }

    /* CHAT LOG RECORDING */

    /**
     * Start recording the chat during the duration of the manhunt for the after-action report.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!matchManager.hasActiveMatch()) return;
        Component line = Component.text(event.getPlayer().getName() + ": ").append(event.message());
        reportService.recordChat("chat", event.getPlayer().getUniqueId(), event.getPlayer().getName(), line);
    }

    /**
     * Record advancements to mimic the chat for the after-action report.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!config.captureAdvancements() || !matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        String key = event.getAdvancement().getKey().asString();
        Component line = Component.text(event.getPlayer().getName() + " made the advancement [" + key + "]");
        reportService.recordChat("advancement", event.getPlayer().getUniqueId(), event.getPlayer().getName(), line);
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "advancement", key);
    }

    /* PROJECTILE TRACKING */

    /**
     * Track path of projectiles fired by players so we can visualize bow shots
     * in the after-action report.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        attributionManager.trackProjectileLaunch(event.getEntity(), player);
    }

    /**
     * Track when we can clean up tracking for projectiles paths.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        attributionManager.trackProjectileHit(event.getEntity());
    }

    /* DEATH TRACKING */

    /**
     * Only player victims matter here because the listener is producing player-centric match
     * reports, and attribution is resolved first so downstream reporting uses one consistent source.
     * First-hit and first-blood are recorded here because direct combat damage is the earliest
     * reliable point where those combat milestones become true. Deathstreaks are intentionally not
     * updated from damage anymore; they are based only on deaths attributed to the runner.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return;

        Attribution attribution = attributionManager.resolveAndStore(event);
        if (attribution == null) return;

        recordAttributedDamage(attribution, victim, event.getCause().name(), event.getFinalDamage());

        recordMilestoneIfAbsent(session, attribution.playerUuid(), attribution.playerName(), "first_hit", "First hit");
        if (victim.getUniqueId().equals(session.runnerUuid) && !session.globalFirstBloodRecorded) {
            session.globalFirstBloodRecorded = true;
            reportService.recordMilestone(attribution.playerUuid(), attribution.playerName(), "first_blood", "First blood on the runner");
        }
    }

    /**
     * Block-based damage is handled separately because its source is less direct than entity damage.
     * It still goes through the attribution manager so lava, beds, anchors, and similar hazards
     * can be credited consistently.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByBlock(EntityDamageByBlockEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player victim)) return;

        Attribution attribution = attributionManager.resolveAndStore(event);
        if (attribution == null) return;

        recordAttributedDamage(attribution, victim, event.getCause().name(), event.getFinalDamage());
    }

    private void recordAttributedDamage(Attribution attribution, Player victim, String cause, double finalDamage) {
        reportService.recordDamage(
                attribution.playerUuid(), attribution.playerName(),
                victim.getUniqueId(), victim.getName(),
                cause, finalDamage,
                attribution.weapon(), attribution.projectileUuid(),
                attribution.location(), victim.getLocation()
        );
    }

    /**
     * Generic damage is only used for cases not already covered by the more specific damage events.
     * That avoids double-recording the same hit while still allowing delayed damage like fire tick
     * to inherit earlier attribution.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || event instanceof EntityDamageByBlockEvent) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Attribution attribution = attributionManager.resolveGenericDamage(victim);
        if (attribution == null) return;

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

    /**
     * Death first consumes any recent attribution because the most recent confirmed attacker is
     * usually more reliable than reconstructing causality after the fact from the last damage event alone.
     * The fallback resolver still exists because some deaths are delayed or indirect and may not have
     * a directly consumed attribution entry by the time the death event fires.
     */
    @EventHandler(priority = EventPriority.HIGHEST) // need HIGHEST here to update the death count reporting BEFORE MatchLifecycleListener
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        Attribution attribution = attributionManager.consumeRecentVictimAttribution(player.getUniqueId());
        if (attribution == null) {
            attribution = attributionManager.resolveDeathAttribution(player);
        }

        MatchSession session = matchManager.getSession();
        if (session != null && attribution != null) {
            session.lastDeathAttribution.put(player.getUniqueId(), attribution);
        }

        String cause = player.getLastDamageCause() == null
                ? "UNKNOWN"
                : player.getLastDamageCause().getCause().name();

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
    }

    /**
     * Dimension milestones are recorded on world change because entering the Nether or End is
     * a meaningful progression signal in this game mode and should only be counted for participants.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        World.Environment env = event.getPlayer().getWorld().getEnvironment();
        switch (env) {
            case NETHER:
                recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_nether", "Entered the Nether");
                return;
            case THE_END:
                recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_end", "Entered the End");
                // If the runner just entered the End, activate the end inventory-control override.
                if (session.runnerUuid.equals(event.getPlayer().getUniqueId())) {
                    matchManager.activateEndInventoryControl(config.endInventoryControlMode());
                }
        }
    }

    /**
     * Block break milestones are based on progression materials because they act as simple,
     * low-cost signals for early-game, mid-game, and gear progression in the match timeline.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        Material type = event.getBlock().getType();
        if (Tag.LOGS.isTagged(type)) {
            recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_wood", "First wood");
        }
        if (type == Material.IRON_ORE || type == Material.DEEPSLATE_IRON_ORE) {
            recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_iron", "First iron");
        }
        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
            recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_diamond", "First diamond");
        }
    }

    /**
     * Iron can be acquired without mining the ore directly, so pickup also awards the iron milestone.
     * That avoids undercounting progression when loot, smelting, or teammate item flow is involved.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player player) || !matchManager.isParticipant(player.getUniqueId())) return;

        Material type = event.getItem().getItemStack().getType();
        if (type == Material.IRON_INGOT || type == Material.RAW_IRON) {
            recordMilestoneIfAbsent(session, player.getUniqueId(), player.getName(), "first_iron", "First iron");
        }
    }

    /**
     * Lava placement is tracked at placement time because later lava damage no longer carries enough
     * information by itself to identify who created the hazard.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.LAVA || event.getBlockPlaced().getType() == Material.LAVA_CAULDRON) {
            attributionManager.trackPlacedLava(event.getPlayer(), event.getBlockPlaced());
        }
    }

    /**
     * Bucket empty is also tracked because lava placement can surface through bucket events and the
     * attribution system needs to capture the hazard at creation time, not when damage happens later.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getBucket() == Material.LAVA_BUCKET) {
            attributionManager.trackPlacedLava(event.getPlayer(), event.getBlock());
        }
    }

    /**
     * Beds and respawn anchors explode only in certain dimensions, so the listener records those
     * interactions up front. That is necessary because the eventual explosion damage event does not
     * inherently remember who triggered the hazard.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        if (!matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        Material type = event.getClickedBlock().getType();
        boolean bedExplodes = type.name().endsWith("_BED")
                && event.getPlayer().getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL;
        boolean anchorExplodes = type == Material.RESPAWN_ANCHOR
                && event.getPlayer().getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER;

        if (bedExplodes) {
            attributionManager.trackExplosiveHazard(event.getPlayer(), "BED_EXPLOSION", event.getClickedBlock().getLocation());
        } else if (anchorExplodes) {
            attributionManager.trackExplosiveHazard(event.getPlayer(), "RESPAWN_ANCHOR", event.getClickedBlock().getLocation());
        }
    }

    /**
     * Milestones are stored as a per-player set so each milestone is reported once.
     * The report layer should show progression events, not repeated noise from the same achievement.
     */
    private void recordMilestoneIfAbsent(MatchSession session, UUID uuid, String name, String key, String description) {
        Set<String> playerMilestones = session.milestones.computeIfAbsent(uuid, ignored -> new HashSet<>());
        if (playerMilestones.add(key)) {
            reportService.recordMilestone(uuid, name, key, description);
        }
    }
}
