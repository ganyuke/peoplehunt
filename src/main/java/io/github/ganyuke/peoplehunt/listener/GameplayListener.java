package io.github.ganyuke.peoplehunt.listener;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.Role;
import io.github.ganyuke.peoplehunt.game.match.AttributionManager;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchSession;
import io.github.ganyuke.peoplehunt.game.match.MatchSession.Attribution;
import io.github.ganyuke.peoplehunt.game.match.MatchTickService;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.PrettyNames;
import io.github.ganyuke.peoplehunt.util.SnapshotUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import io.papermc.paper.block.bed.BedEnterAction;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerBedFailEnterEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

public final class GameplayListener implements Listener {
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final ReportService reportService;
    private final AttributionManager attributionManager;
    private final JavaPlugin plugin;
    private final MatchTickService tickService;
    private final Map<UUID, UUID> lastWhisperPartners = new HashMap<>();

    public GameplayListener(
            PeopleHuntConfig config,
            MatchManager matchManager,
            ReportService reportService,
            AttributionManager attributionManager,
            JavaPlugin plugin,
            MatchTickService tickService
    ) {
        this.config = config;
        this.matchManager = matchManager;
        this.reportService = reportService;
        this.attributionManager = attributionManager;
        this.plugin = plugin;
        this.tickService = tickService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!matchManager.hasActiveMatch()) return;
        Component rendered = event.renderer().render(event.getPlayer(), event.getPlayer().displayName(), event.message(), event.getPlayer());
        reportService.recordChat("chat", event.getPlayer().getUniqueId(), event.getPlayer().getName(), rendered);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        // This is intentionally command-based rather than plugin-message-based. The report wants a
        // server-log-style whisper line, and intercepting the common private-message commands keeps
        // that behavior stable even when the actual delivery formatting varies by client.
        if (!matchManager.hasActiveMatch()) return;
        String message = event.getMessage();
        if (!message.startsWith("/")) return;
        String[] parts = message.substring(1).split("\\s+", 3);
        if (parts.length < 2) return;
        String command = parts[0].toLowerCase(java.util.Locale.ROOT);
        int colon = command.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < command.length()) {
            command = command.substring(colon + 1);
        }
        Player sender = event.getPlayer();
        Player target;
        String body;
        if (Set.of("msg", "tell", "w", "whisper", "m", "pm", "dm").contains(command)) {
            target = Bukkit.getPlayerExact(parts[1]);
            if (target == null) return;
            body = parts.length >= 3 ? parts[2] : "";
        } else if (Set.of("reply", "r").contains(command)) {
            UUID targetUuid = lastWhisperPartners.get(sender.getUniqueId());
            if (targetUuid == null) return;
            target = Bukkit.getPlayer(targetUuid);
            if (target == null) return;
            body = parts.length >= 3 ? parts[1] + " " + parts[2] : parts[1];
        } else {
            return;
        }
        lastWhisperPartners.put(sender.getUniqueId(), target.getUniqueId());
        lastWhisperPartners.put(target.getUniqueId(), sender.getUniqueId());
        Component rendered = Component.text(sender.getName() + " whispered to " + target.getName() + ": " + body);
        reportService.recordChat("whisper", sender.getUniqueId(), sender.getName(), rendered);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!config.captureAdvancements() || !matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        String key = event.getAdvancement().getKey().asString();
        String pretty = PrettyNames.advancementTitle(event.getAdvancement());
        reportService.recordMilestone(event.getPlayer().getUniqueId(), event.getPlayer().getName(), key, pretty, key, PrettyNames.advancementFrameColorHex(event.getAdvancement()));
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), PrettyNames.advancementKindLabel(event.getAdvancement()), pretty, key, PrettyNames.advancementFrameColorHex(event.getAdvancement()));
        reportService.recordChat("advancement", event.getPlayer().getUniqueId(), event.getPlayer().getName(), PrettyNames.advancementMessage(event.getPlayer().getName(), key, event.getAdvancement()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            attributionManager.trackProjectileLaunch(event.getEntity(), player);
            if (event.getEntity().getType().name().contains("PEARL")) {
                reportService.recordTimeline(player.getUniqueId(), player.getName(), "projectile", "threw " + PrettyNames.enumName(event.getEntity().getType().name()), event.getEntity().getType().name(), null);
            }
            return;
        }
        if (event.getEntity().getShooter() instanceof LivingEntity living) {
            attributionManager.trackHostileProjectileLaunch(event.getEntity(), living);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        attributionManager.trackProjectileHit(event.getEntity());
        attributionManager.trackHostileProjectileHit(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent) return;
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        LivingEntity livingEntity = event.getEntity();

        MatchSession.TrackedMobState tracked = session.trackedMobs.remove(livingEntity.getUniqueId());
        if (tracked == null) {
            return;
        }

        reportService.finishMobTrack(livingEntity.getUniqueId(), livingEntity.getLocation(), "death");
        AttributionManager.DeathAttribution attribution = attributionManager.resolveLivingEntityDeathAttribution(livingEntity, event.getDamageSource());
        String cause = livingEntity.getLastDamageCause() == null ? "UNKNOWN" : livingEntity.getLastDamageCause().getCause().name();
        reportService.recordMobDeath(
                livingEntity.getUniqueId(),
                livingEntity.getType().name(),
                tracked.targetPlayerUuid,
                tracked.targetPlayerName,
                attribution == null ? null : attribution.killerUuid(),
                attribution == null ? null : attribution.killerName(),
                attribution == null ? null : attribution.killerEntityType(),
                cause,
                attribution == null ? null : attribution.weapon(),
                livingEntity.getLocation()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        if (event.getEntity() instanceof EnderDragon dragon) {
            recordDragonEntityDamage(event, dragon);
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !matchManager.isParticipant(victim.getUniqueId())) return;

        Attribution attribution = attributionManager.resolveAndStore(event);
        if (attribution != null) {
            recordAttributedDamage(attribution, victim, event.getCause().name(), event.getFinalDamage());
            maybeRecordBlockTimeline(victim, attribution.playerName(), event.getDamage(), event.getFinalDamage());
            captureImmediateSampleNextTick(victim);
            recordMilestoneIfAbsent(session, attribution.playerUuid(), attribution.playerName(), "first_hit", "First hit");
            if (victim.getUniqueId().equals(session.runnerUuid) && !session.globalFirstBloodRecorded) {
                session.globalFirstBloodRecorded = true;
                reportService.recordMilestone(attribution.playerUuid(), attribution.playerName(), "first_blood", "First blood on the runner");
            }
            return;
        }

        Entity damager = event.getDamager();
        String attackerEntityType = damager.getType().name();
        String attackerName = PrettyNames.enumName(attackerEntityType);
        String weapon = damager instanceof Projectile projectile ? PrettyNames.enumName(projectile.getType().name()) : attackerName;
        Location attackerLocation = damager.getLocation();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living && !(living instanceof Player)) {
            attackerEntityType = living.getType().name();
            attackerName = PrettyNames.enumName(attackerEntityType);
            attackerLocation = living.getLocation();
        }
        reportService.recordDamage(
                null,
                damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living && !(living instanceof Player) ? living.getUniqueId() : damager.getUniqueId(),
                attackerName,
                attackerEntityType,
                victim.getUniqueId(),
                victim.getName(),
                event.getCause().name(),
                event.getFinalDamage(),
                weapon,
                damager instanceof Projectile projectile ? projectile.getUniqueId() : null,
                attackerLocation,
                victim.getLocation()
        );
        maybeRecordBlockTimeline(victim, attackerName, event.getDamage(), event.getFinalDamage());
        captureImmediateSampleNextTick(victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByBlock(EntityDamageByBlockEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        if (event.getEntity() instanceof EnderDragon dragon) {
            recordDragonBlockDamage(event, dragon);
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !matchManager.isParticipant(victim.getUniqueId())) return;

        Attribution attribution = attributionManager.resolveAndStore(event);
        if (attribution != null) {
            recordAttributedDamage(attribution, victim, event.getCause().name(), event.getFinalDamage());
        } else {
            recordEnvironmentalDamage(victim, event.getCause(), event.getFinalDamage(), blockDamageName(event));
        }
        captureImmediateSampleNextTick(victim);
    }

    private void maybeRecordBlockTimeline(Player victim, String attackerName, double baseDamage, double finalDamage) {
        if (!victim.isBlocking()) return;
        double blocked = Math.max(0.0, baseDamage - finalDamage);
        if (blocked <= 0.0) return;
        reportService.recordBlock(victim.getUniqueId(), victim.getName(), attackerName, attackerName, blocked, victim.getLocation(), null);
    }

    private void recordDragonEntityDamage(EntityDamageByEntityEvent event, EnderDragon dragon) {
        MatchSession.Attribution attribution = attributionManager.resolveEntityDamage(event);
        if (attribution != null) {
            reportService.recordDamage(
                    attribution.playerUuid(),
                    attribution.playerName(),
                    dragon.getUniqueId(),
                    "Ender Dragon",
                    event.getCause().name(),
                    event.getFinalDamage(),
                    attribution.weapon(),
                    attribution.projectileUuid(),
                    attribution.location(),
                    dragon.getLocation()
            );
        } else {
            Entity damager = event.getDamager();
            String attackerEntityType = damager.getType().name();
            String attackerName = PrettyNames.enumName(attackerEntityType);
            String weapon = damager instanceof Projectile projectile ? PrettyNames.enumName(projectile.getType().name()) : attackerName;
            Location attackerLocation = damager.getLocation();
            UUID attackerEntityUuid = damager.getUniqueId();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living && !(living instanceof Player)) {
                attackerEntityType = living.getType().name();
                attackerName = PrettyNames.enumName(attackerEntityType);
                attackerLocation = living.getLocation();
                attackerEntityUuid = living.getUniqueId();
            }
            reportService.recordDamage(
                    null,
                    attackerEntityUuid,
                    attackerName,
                    attackerEntityType,
                    dragon.getUniqueId(),
                    "Ender Dragon",
                    event.getCause().name(),
                    event.getFinalDamage(),
                    weapon,
                    damager instanceof Projectile projectile ? projectile.getUniqueId() : null,
                    attackerLocation,
                    dragon.getLocation()
            );
        }
        captureDragonSampleNextTick(dragon);
    }

    private void recordDragonBlockDamage(EntityDamageByBlockEvent event, EnderDragon dragon) {
        MatchSession.Attribution attribution = attributionManager.resolveBlockDamage(event);
        if (attribution != null) {
            reportService.recordDamage(
                    attribution.playerUuid(),
                    attribution.playerName(),
                    dragon.getUniqueId(),
                    "Ender Dragon",
                    event.getCause().name(),
                    event.getFinalDamage(),
                    attribution.weapon(),
                    attribution.projectileUuid(),
                    attribution.location(),
                    dragon.getLocation()
            );
        } else {
            String sourceName = blockDamageName(event);
            reportService.recordDamage(
                    null,
                    null,
                    sourceName,
                    "ENVIRONMENT",
                    dragon.getUniqueId(),
                    "Ender Dragon",
                    event.getCause().name(),
                    event.getFinalDamage(),
                    sourceName,
                    null,
                    dragon.getLocation(),
                    dragon.getLocation()
            );
        }
        captureDragonSampleNextTick(dragon);
    }

    private void recordDragonGenericDamage(EntityDamageEvent event, EnderDragon dragon) {
        MatchSession.Attribution attribution = attributionManager.resolveGenericDamage(dragon.getLocation(), event.getCause());
        if (attribution != null) {
            reportService.recordDamage(
                    attribution.playerUuid(),
                    attribution.playerName(),
                    dragon.getUniqueId(),
                    "Ender Dragon",
                    event.getCause().name(),
                    event.getFinalDamage(),
                    attribution.weapon(),
                    attribution.projectileUuid(),
                    attribution.location(),
                    dragon.getLocation()
            );
        } else {
            String sourceName = PrettyNames.enumName(event.getCause().name());
            reportService.recordDamage(
                    null,
                    null,
                    sourceName,
                    "ENVIRONMENT",
                    dragon.getUniqueId(),
                    "Ender Dragon",
                    event.getCause().name(),
                    event.getFinalDamage(),
                    sourceName,
                    null,
                    dragon.getLocation(),
                    dragon.getLocation()
            );
        }
        captureDragonSampleNextTick(dragon);
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

    private void recordEnvironmentalDamage(Player victim, EntityDamageEvent.DamageCause cause, double finalDamage, String sourceName) {
        String causeName = cause == null ? "UNKNOWN" : cause.name();
        String resolvedSource = sourceName == null || sourceName.isBlank() ? PrettyNames.enumName(causeName) : sourceName;
        reportService.recordDamage(
                null,
                null,
                resolvedSource,
                "ENVIRONMENT",
                victim.getUniqueId(),
                victim.getName(),
                causeName,
                finalDamage,
                resolvedSource,
                null,
                victim.getLocation(),
                victim.getLocation()
        );
    }

    private String blockDamageName(EntityDamageByBlockEvent event) {
        if (event.getDamager() != null) {
            return PrettyNames.enumName(event.getDamager().getType().name());
        }
        return PrettyNames.enumName(event.getCause().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || event instanceof EntityDamageByBlockEvent) return;
        if (event.getEntity() instanceof EnderDragon dragon && matchManager.hasActiveMatch()) {
            recordDragonGenericDamage(event, dragon);
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !matchManager.hasActiveMatch() || !matchManager.isParticipant(victim.getUniqueId())) return;

        Attribution attribution = attributionManager.resolveGenericDamage(victim, event.getCause());
        if (attribution != null) {
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
        } else {
            recordEnvironmentalDamage(victim, event.getCause(), event.getFinalDamage(), null);
        }
        captureImmediateSampleNextTick(victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) return;
        Attribution attribution = attributionManager.consumeRecentVictimAttribution(player.getUniqueId());
        if (attribution == null) {
            attribution = attributionManager.resolveDeathAttribution(player);
        }

        MatchSession session = matchManager.getSession();
        if (session != null && attribution != null) {
            session.lastDeathAttribution.put(player.getUniqueId(), attribution);
        }

        Role role = matchManager.roleOf(player.getUniqueId());
        var maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        float maxHealth = (float) (maxHealthAttribute == null ? Math.max(20.0, player.getHealth()) : maxHealthAttribute.getValue());
        if (session != null && role != null) {
            // Capture a terminal life sample so the replay shows the victim at 0.0 health until
            // their next real path sample (for example, after respawn) instead of freezing on the
            // last pre-death health value.
            reportService.recordPath(
                    player.getUniqueId(),
                    player.getName(),
                    session.lifeIndex.getOrDefault(player.getUniqueId(), 1),
                    role.name(),
                    player.getGameMode().name(),
                    false,
                    player.getLocation(),
                    0.0f,
                    maxHealth,
                    0.0f,
                    player.getFoodLevel(),
                    player.getSaturation(),
                    player.getLevel(),
                    player.getTotalExperience(),
                    player.getInventory().getHeldItemSlot(),
                    player.getExp(),
                    SnapshotUtil.inventory(player),
                    SnapshotUtil.effects(player)
            );
        }

        String cause = player.getLastDamageCause() == null ? "UNKNOWN" : player.getLastDamageCause().getCause().name();
        String killerEntityType = attribution == null ? (lastDamagerType(player) == null ? "ENVIRONMENT" : lastDamagerType(player)) : (attribution.playerUuid() == null ? attribution.weapon() : "PLAYER");
        reportService.recordDeath(
                player.getUniqueId(),
                player.getName(),
                attribution == null ? null : attribution.playerUuid(),
                attribution == null ? null : attribution.playerName(),
                killerEntityType,
                cause,
                attribution == null ? null : attribution.weapon(),
                player.getLocation(),
                player.getLevel(),
                SnapshotUtil.inventory(player),
                event.deathMessage()
        );
    }

    private void captureImmediateSampleNextTick(Player player) {
        runNextTickIfOnline(player, () -> tickService.captureImmediateSample(player));
    }

    private void captureDragonSampleNextTick(EnderDragon dragon) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!dragon.isValid() || dragon.isDead() || dragon.getWorld() == null) {
                return;
            }
            var attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = attribute == null ? dragon.getHealth() : attribute.getValue();
            reportService.recordDragonSample(dragon.getLocation(), (float) dragon.getHealth(), (float) maxHealth);
        });
    }

    private void runNextTickIfOnline(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    private String lastDamagerType(Player player) {
        if (!(player.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        return byEntity.getDamager().getType().name();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;

        Player player = event.getPlayer();
        tickService.primeObservedState(player);
        reportService.recordMarker("dimension_entry", player.getUniqueId(), player.getName(), player.getLocation(), "Entered " + PrettyNames.key(player.getWorld().getKey().asString()), "Changed dimension", null);

        long nowEpochMillis = System.currentTimeMillis();
        MatchSession.PendingPortalArrival pendingPortalArrival = session.consumeFreshPendingPortalArrival(player.getUniqueId(), nowEpochMillis);
        // World-change location is the first reliable post-transfer position. Record the portal
        // arrival marker and path sample there instead of trusting the raw teleport target.
        runNextTickIfOnline(player, () -> {
            if (pendingPortalArrival != null) {
                reportService.recordMarker("portal", player.getUniqueId(), player.getName(), player.getLocation(), "Portal", "Portal arrival via " + PrettyNames.enumName(pendingPortalArrival.causeName()), null);
            }
            tickService.captureImmediateSample(player);
        });

        World.Environment env = player.getWorld().getEnvironment();
        switch (env) {
            case NETHER -> recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_nether", "Entered the Nether");
            case THE_END -> {
                recordMilestoneIfAbsent(session, event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_end", "Entered the End");
                if (session.runnerUuid.equals(event.getPlayer().getUniqueId())) {
                    matchManager.activateEndInventoryControl(matchManager.getSessionConfig().endInventoryControlMode());
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        if (event.getFrom().getWorld() == null || event.getTo().getWorld() == null) return;
        Player player = event.getPlayer();
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        boolean sameWorld = event.getFrom().getWorld().getUID().equals(event.getTo().getWorld().getUID());
        boolean portalTravel = event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL;
        session.pruneExpiredPendingPortalArrivals(System.currentTimeMillis());
        if (portalTravel) {
            reportService.recordMarker("portal", player.getUniqueId(), player.getName(), event.getFrom(), "Portal", "Portal departure via " + PrettyNames.enumName(event.getCause().name()), null);
            session.pendingPortalArrivals.put(player.getUniqueId(), new MatchSession.PendingPortalArrival(event.getCause().name(), System.currentTimeMillis()));
            reportService.recordTimeline(player.getUniqueId(), player.getName(), "travel", "used " + PrettyNames.enumName(event.getCause().name()), event.getCause().name(), null);
            return;
        }
        if (sameWorld) {
            session.pendingTeleportPathFlags.add(player.getUniqueId());
            reportService.recordMarker("jump", player.getUniqueId(), player.getName(), event.getFrom(), "Jump", "Teleport discontinuity from here", null);
            reportService.recordMarker("jump", player.getUniqueId(), player.getName(), event.getTo(), "Jump", "Teleport discontinuity to here", null);
            reportService.recordTimeline(player.getUniqueId(), player.getName(), "travel", "teleported via " + PrettyNames.enumName(event.getCause().name()), event.getCause().name(), null);
            captureImmediateSampleNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        MatchSession session = matchManager.getSession();
        if (session != null) {
            session.lastSampleGameModes.put(event.getPlayer().getUniqueId(), event.getNewGameMode().name());
        }
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "gamemode", "changed to " + PrettyNames.enumName(event.getNewGameMode().name()), event.getNewGameMode().name(), null);
        captureImmediateSampleNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem().clone();
        String itemName = SnapshotUtil.itemPrettyName(item);
        String rawId = item.getType().getKey().asString();
        String color = SnapshotUtil.materialTextColor(item.getType());
        // Consumption mutates hunger, saturation, absorption, and sometimes health after the event
        // callback returns. Read the state one tick later so the structured food record matches the
        // actual post-consumption player state.
        runNextTickIfOnline(player, () -> {
            if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) return;
            reportService.recordFood(
                    player.getUniqueId(),
                    player.getName(),
                    rawId,
                    itemName,
                    color,
                    (float) player.getHealth(),
                    (float) player.getAbsorptionAmount(),
                    player.getFoodLevel(),
                    player.getSaturation()
            );
            tickService.captureImmediateSample(player);
        });
        if (item.getType() == Material.GOLDEN_APPLE) {
            reportService.recordMilestone(player.getUniqueId(), player.getName(), "golden_apple", "Ate a Golden Apple", rawId, color);
        }
        if (item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            reportService.recordMilestone(player.getUniqueId(), player.getName(), "enchanted_golden_apple", "Ate an Enchanted Golden Apple", rawId, "#ff55ff");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player) || !matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) return;
        reportService.recordTimeline(player.getUniqueId(), player.getName(), "health", "regained %.1f health from %s".formatted(event.getAmount(), PrettyNames.enumName(event.getRegainReason().name())), event.getRegainReason().name(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || !matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) return;
        PotionEffectType type = event.getModifiedType();
        String name = PrettyNames.effect(type);
        String causeKey = event.getCause().name();
        String cause = PrettyNames.enumName(causeKey);
        String description = switch (event.getAction()) {
            case ADDED -> "gained " + name + " from " + cause;
            case CHANGED -> "effect changed: " + name + " via " + cause;
            case REMOVED, CLEARED -> "lost " + name + " after " + cause;
        };
        String rawName = type.getKey().asString();
        reportService.recordEffect(
                player.getUniqueId(),
                player.getName(),
                event.getAction().name(),
                rawName,
                name,
                event.getNewEffect() == null ? (event.getOldEffect() == null ? 0 : event.getOldEffect().getAmplifier()) : event.getNewEffect().getAmplifier(),
                event.getNewEffect() == null ? (event.getOldEffect() == null ? 0 : event.getOldEffect().getDuration()) : event.getNewEffect().getDuration(),
                cause,
                causeKey,
                null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || !matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId())) return;
        reportService.recordTotem(player.getUniqueId(), player.getName(), player.getLocation(), "#ffff55");
        reportService.recordMilestone(player.getUniqueId(), player.getName(), "totem", "Totem of Undying activated", "minecraft:totem_of_undying", "#ffff55");
        captureImmediateSampleNextTick(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !matchManager.hasActiveMatch() || !matchManager.isParticipant(player.getUniqueId()) || event.getRecipe() == null) return;
        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType().isAir()) return;
        String rawId = result.getType().getKey().asString();
        String pretty = SnapshotUtil.itemPrettyName(result);
        String color = SnapshotUtil.materialTextColor(result.getType());
        reportService.recordTimeline(player.getUniqueId(), player.getName(), "craft", "crafted " + pretty, rawId, color);
        reportService.recordMilestone(player.getUniqueId(), player.getName(), "craft:" + rawId, "Crafted " + pretty, rawId, color);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        BedEnterAction enterAction = readBedEnterAction(event);
        String detail = describeBedAction(enterAction, null, false);
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "spawn", "bed interaction: " + detail, rawBedProblem(enterAction), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBedFailEnter(PlayerBedFailEnterEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        BedEnterAction enterAction = readBedEnterAction(event);
        String detail = describeBedAction(enterAction, event.getMessage(), event.getWillExplode());
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "spawn", "bed interaction: " + detail, rawBedProblem(enterAction), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        if (event.getLocation() == null || event.getLocation().getWorld() == null) {
            reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "spawn", "cleared respawn point", String.valueOf(event.getCause()), null);
            return;
        }
        String cause = PrettyNames.enumName(event.getCause().name());
        reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "spawn", "set respawn point via " + cause, event.getCause().name(), null);
        reportService.recordSpawnMarker(event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getLocation(), "Spawn", "Respawn point via " + cause, null);
    }

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null || !(event.getEntity() instanceof Player player) || !matchManager.isParticipant(player.getUniqueId())) return;

        Material type = event.getItem().getItemStack().getType();
        if (type == Material.IRON_INGOT || type == Material.RAW_IRON) {
            recordMilestoneIfAbsent(session, player.getUniqueId(), player.getName(), "first_iron", "First iron");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.LAVA || event.getBlockPlaced().getType() == Material.LAVA_CAULDRON) {
            attributionManager.trackPlacedLava(event.getPlayer(), event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getBucket() == Material.LAVA_BUCKET) {
            attributionManager.trackPlacedLava(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!matchManager.hasActiveMatch() || !matchManager.isParticipant(event.getPlayer().getUniqueId())) return;
        if (event.getItem() != null && event.getItem().getType() == Material.ENDER_EYE && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            reportService.recordMilestone(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "first_eye_of_ender", "Threw an Eye of Ender", "minecraft:ender_eye", "#55ffff");
        }
        if (event.getClickedBlock() == null) return;

        Material type = event.getClickedBlock().getType();
        boolean bedExplodes = type.name().endsWith("_BED") && event.getPlayer().getWorld().getEnvironment() != World.Environment.NORMAL;
        boolean anchorExplodes = type == Material.RESPAWN_ANCHOR && event.getPlayer().getWorld().getEnvironment() != World.Environment.NETHER;
        if (bedExplodes) {
            attributionManager.trackExplosiveHazard(event.getPlayer(), "BED_EXPLOSION", event.getClickedBlock().getLocation());
        } else if (anchorExplodes) {
            attributionManager.trackExplosiveHazard(event.getPlayer(), "RESPAWN_ANCHOR", event.getClickedBlock().getLocation());
        } else if (type == Material.RESPAWN_ANCHOR && event.getPlayer().getWorld().getEnvironment() == World.Environment.NETHER) {
            reportService.recordTimeline(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "spawn", "respawn anchor interaction", "RESPAWN_ANCHOR", null);
        }
    }

    private BedEnterAction readBedEnterAction(PlayerBedEnterEvent event) {
        return event.enterAction();
    }

    private BedEnterAction readBedEnterAction(PlayerBedFailEnterEvent event) {
        return event.enterAction();
    }

    private String describeBedAction(BedEnterAction enterAction, Component message, boolean willExplode) {
        String explicitMessage = message == null ? "" : Text.plain(message).trim();
        if (!explicitMessage.isBlank()) {
            return explicitMessage;
        }
        String problem = rawBedProblem(enterAction);
        if (problem != null && !problem.isBlank()) {
            String pretty = PrettyNames.enumName(problem);
            if (willExplode && !pretty.toLowerCase(java.util.Locale.ROOT).contains("explode")) {
                return pretty + " (explodes)";
            }
            return pretty;
        }
        if (willExplode) {
            return "explodes";
        }
        return canSleep(enterAction) ? "entered bed" : "bed interaction";
    }

    private String rawBedProblem(BedEnterAction enterAction) {
        if (enterAction == null || enterAction.problem() == null) {
            return null;
        }
        return enterAction.problem().toString();
    }

    private boolean canSleep(BedEnterAction enterAction) {
        return enterAction != null && enterAction.problem() == null && enterAction.canSleep().success();
    }

    private void recordMilestoneIfAbsent(MatchSession session, UUID uuid, String name, String key, String description) {
        Set<String> playerMilestones = session.milestones.computeIfAbsent(uuid, ignored -> new HashSet<>());
        if (playerMilestones.add(key)) {
            reportService.recordMilestone(uuid, name, key, description);
        }
    }
}
