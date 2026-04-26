package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.Role;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.PrettyNames;
import io.github.ganyuke.peoplehunt.util.SnapshotUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;

/**
 * Owns scheduled runtime tasks for active or primed matches.
 */
public class MatchTickService {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final ReportService reportService;
    private MatchManager matchManager;

    private BukkitTask pathTask;
    private BukkitTask rollbackTask;
    private BukkitTask elapsedTask;
    private BukkitTask scanTask;
    private BukkitTask primeTask;

    public MatchTickService(JavaPlugin plugin, PeopleHuntConfig config, ReportService reportService) {
        this.plugin = plugin;
        this.config = config;
        this.reportService = reportService;
    }

    public void setMatchManager(MatchManager matchManager) {
        this.matchManager = matchManager;
    }

    public void startRuntimeTasks() {
        stopRuntimeTasks();
        recordRollbackSample();
        pathTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recordPathSample, config.playerPathSampleIntervalTicks(), config.playerPathSampleIntervalTicks());
        rollbackTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recordRollbackSample, Math.max(1L, config.rollbackSampleIntervalTicks()), Math.max(1L, config.rollbackSampleIntervalTicks()));
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanRuntimeState, 10L, 10L);
        elapsedTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            MatchSession session = matchManager.getSession();
            if (session == null) return;
            long elapsedMinutes = (System.currentTimeMillis() - session.startedAtEpochMillis) / 60000L;
            if (elapsedMinutes >= session.nextElapsedAnnouncementMinutes) {
                matchManager.broadcast(Text.mm("<yellow>Manhunt time elapsed: " + elapsedMinutes + " minutes"));
                session.nextElapsedAnnouncementMinutes += Math.max(1, config.elapsedAnnouncementMinutes());
            }
        }, 20L, 20L);
    }

    public void stopRuntimeTasks() {
        cancelTask(pathTask);
        cancelTask(rollbackTask);
        cancelTask(elapsedTask);
        cancelTask(scanTask);
        pathTask = null;
        rollbackTask = null;
        elapsedTask = null;
        scanTask = null;
    }

    public void startPrimeTask() {
        cancelPrimeTask();
        primeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            PrimeContext ctx = matchManager.getPrimeContext();
            if (ctx == null || !ctx.keepPlayersFull()) return;
            for (UUID uuid : ctx.participantIds()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) continue;
                var attribute = player.getAttribute(Attribute.MAX_HEALTH);
                if (attribute != null) {
                    player.setHealth(attribute.getValue());
                }
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
            }
        }, 20L, 20L);
    }

    public void cancelPrimeTask() {
        cancelTask(primeTask);
        primeTask = null;
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    public void captureImmediateSample(Player player) {
        MatchSession session = matchManager.getSession();
        if (session == null || player == null) return;
        Role role = session.roles.get(player.getUniqueId());
        if (role == null) return;
        sampleParticipant(session, role, player);
    }

    private void recordPathSample() {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        for (Map.Entry<UUID, Role> entry : session.roles.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            sampleParticipant(session, entry.getValue(), player);
        }
    }

    private void sampleParticipant(MatchSession session, Role role, Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = attribute == null ? player.getHealth() : attribute.getValue();
        boolean isTeleport = session.pendingTeleportPathFlags.remove(player.getUniqueId());
        reportService.recordPath(
                player.getUniqueId(),
                player.getName(),
                session.lifeIndex.getOrDefault(player.getUniqueId(), 1),
                role.name(),
                player.getGameMode().name(),
                isTeleport,
                player.getLocation(),
                (float) player.getHealth(),
                (float) maxHealth,
                (float) player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getLevel(),
                player.getTotalExperience(),
                player.getInventory().getHeldItemSlot(),
                player.getExp(),
                SnapshotUtil.inventory(player),
                SnapshotUtil.effects(player)
        );
        detectStateTransitions(session, player);
    }

    private void recordRollbackSample() {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        // Rollback snapshots are a separate operator-facing feature from AAR path samples.
        // Sampling them less frequently cuts inventory cloning cost without changing report fidelity.
        for (UUID uuid : session.roles.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            var attribute = player.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = attribute == null ? player.getHealth() : attribute.getValue();
            captureRollbackSnapshot(session, player, maxHealth);
        }
    }

    private void detectStateTransitions(MatchSession session, Player player) {
        UUID uuid = player.getUniqueId();
        String gameMode = player.getGameMode().name();
        String previousGameMode = session.lastSampleGameModes.put(uuid, gameMode);
        if (previousGameMode != null && !previousGameMode.equals(gameMode)) {
            reportService.recordTimeline(uuid, player.getName(), "gamemode", "changed to " + PrettyNames.enumName(gameMode), gameMode, null);
        }

        String world = player.getWorld().getKey().asString();
        String previousWorld = session.lastSampleWorlds.put(uuid, world);
        if (previousWorld != null && !previousWorld.equals(world)) {
            reportService.recordMarker(
                    "dimension_entry",
                    uuid,
                    player.getName(),
                    player.getLocation(),
                    "Entered " + PrettyNames.key(world),
                    "Changed dimension to " + PrettyNames.key(world),
                    colorFor(uuid)
            );
        }
    }

    public void primeObservedState(Player player) {
        MatchSession session = matchManager.getSession();
        if (session == null || player == null) return;
        session.lastSampleGameModes.put(player.getUniqueId(), player.getGameMode().name());
        session.lastSampleWorlds.put(player.getUniqueId(), player.getWorld().getKey().asString());
    }

    private void captureRollbackSnapshot(MatchSession session, Player player, double maxHealth) {
        long now = System.currentTimeMillis();
        long cutoff = now - (Math.max(1, config.rollbackMemoryMinutes()) * 60_000L);

        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            contents.add(stack == null ? null : stack.clone());
        }

        List<PotionEffect> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(effect);
        }

        MatchSession.RollbackState snapshot = new MatchSession.RollbackState(
                now,
                player.getLocation().clone(),
                player.getGameMode(),
                player.getHealth(),
                maxHealth,
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getLevel(),
                player.getTotalExperience(),
                contents,
                clone(player.getInventory().getHelmet()),
                clone(player.getInventory().getChestplate()),
                clone(player.getInventory().getLeggings()),
                clone(player.getInventory().getBoots()),
                clone(player.getInventory().getItemInOffHand()),
                effects
        );

        Deque<MatchSession.RollbackState> deque = session.rollbackBuffer.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        deque.addLast(snapshot);
        while (!deque.isEmpty() && deque.peekFirst().capturedAtEpochMillis() < cutoff) {
            deque.removeFirst();
        }
    }

    private static ItemStack clone(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }

    private void scanRuntimeState() {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        scanMobs(session);
        scanEndState(session);
    }

    private void scanMobs(MatchSession session) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Role> entry : session.roles.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            for (Entity entity : player.getNearbyEntities(config.mobTrackRadius(), config.mobTrackRadius(), config.mobTrackRadius())) {
                if (!(entity instanceof Mob mob)) continue;
                Player target = mob.getTarget() instanceof Player candidate && matchManager.isParticipant(candidate.getUniqueId()) ? candidate : null;
                boolean interesting = target != null || entity instanceof Creeper;
                if (!interesting) continue;
                MatchSession.TrackedMobState state = session.trackedMobs.computeIfAbsent(entity.getUniqueId(), ignored -> new MatchSession.TrackedMobState(entity.getUniqueId(), entity.getType().name(), target == null ? null : target.getUniqueId(), target == null ? null : target.getName(), now));
                if (target != null) {
                    state.targetPlayerUuid = target.getUniqueId();
                    state.targetPlayerName = target.getName();
                }
                state.lastSeenEpochMillis = now;
                reportService.startMobTrack(entity.getUniqueId(), entity.getType().name(), state.targetPlayerUuid, state.targetPlayerName, "#f97316", entity.getLocation());
                reportService.recordMobPoint(entity.getUniqueId(), state.targetPlayerUuid, state.targetPlayerName, entity.getLocation());
            }
        }

        Iterator<Map.Entry<UUID, MatchSession.TrackedMobState>> iterator = session.trackedMobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MatchSession.TrackedMobState> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            boolean stale = now - entry.getValue().lastSeenEpochMillis > config.mobStaleMillis();
            boolean far = true;
            if (entity != null && entity.isValid()) {
                for (UUID uuid : session.roles.keySet()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.getWorld().equals(entity.getWorld())) continue;
                    if (player.getLocation().distanceSquared(entity.getLocation()) <= config.mobStaleRadius() * config.mobStaleRadius()) {
                        far = false;
                        break;
                    }
                }
            }
            if (entity == null || !entity.isValid() || stale || far) {
                reportService.finishMobTrack(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void scanEndState(MatchSession session) {
        boolean anyParticipantInEnd = false;
        for (UUID uuid : session.roles.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.getWorld().getEnvironment() == World.Environment.THE_END) {
                anyParticipantInEnd = true;
                break;
            }
        }
        if (!anyParticipantInEnd) return;

        Set<UUID> currentlySeenCrystals = new java.util.LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                var attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = attribute == null ? dragon.getHealth() : attribute.getValue();
                reportService.recordDragonSample(dragon.getLocation(), (float) dragon.getHealth(), (float) maxHealth);
            }
            for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
                currentlySeenCrystals.add(crystal.getUniqueId());
                reportService.upsertEndCrystal(crystal.getUniqueId(), crystal.getLocation());
            }
        }
        for (UUID known : new ArrayList<>(session.seenEndCrystals)) {
            if (!currentlySeenCrystals.contains(known)) {
                reportService.markEndCrystalDestroyed(known);
            }
        }
        session.seenEndCrystals.clear();
        session.seenEndCrystals.addAll(currentlySeenCrystals);
    }

    private String colorFor(UUID uuid) {
        String color = reportService.colorOfParticipant(uuid);
        return color == null ? "#7dd3fc" : color;
    }
}
