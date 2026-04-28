package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.SessionConfig;
import io.github.ganyuke.peoplehunt.game.*;
import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.tools.SurroundService;
import io.github.ganyuke.peoplehunt.report.ReportModels;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.ExceptionUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the authoritative match state machine.
 *
 * <p>This class separates three operator-visible phases:
 * <ul>
 *   <li>idle: player selections and settings only</li>
 *   <li>primed: waiting for the runner's first movement to begin the run</li>
 *   <li>active: live match with reporting, compasses, and listener-driven game logic</li>
 * </ul>
 * All commands and listeners route through this class when they need to mutate the match model.
 */
public final class MatchManager {
    public enum PrepareMode {
        HEALTH_AND_HUNGER,
        STATUS_EFFECTS,
        EXPERIENCE,
        INVENTORY
    }

    private static final String HEADER_LINE = "<gray>———— [ <gold><b>%s</b></gold> ] ————</gray>";
    private static final String FOOTER_LINE = "<gray>———————————————————————</gray>";
    private static final String SECTION_PREFIX = "<gray><b>%s</b></gray>";
    private static final String LABEL_PREFIX = "<yellow>%s:</yellow> ";
    private static final String VALUE = "<white>%s</white>";
    private static final String BULLET = " <dark_gray>»</dark_gray> ";

    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final PersistentStateStore stateStore;
    private final PersistentStateStore.StateData stateData;
    private final KitService kitService;
    private final CompassService compassService;
    private final ReportService reportService;
    private final SurroundService surroundService;
    private final MatchTickService tickService;
    private SessionConfig sessionConfig;

    private PrimeContext primeContext;
    private MatchSession activeSession;
    private MatchOutcome pendingFinalizationOutcome;
    private @Nullable BukkitTask announcementTask;

    public MatchManager(
            JavaPlugin plugin, PeopleHuntConfig config, PersistentStateStore stateStore,
            PersistentStateStore.StateData stateData, KitService kitService,
            CompassService compassService, ReportService reportService,
            SurroundService surroundService, MatchTickService tickService,
            SessionConfig sessionConfig
    ) {
        this.plugin = plugin;
        this.config = config;
        this.stateStore = stateStore;
        this.stateData = stateData;
        this.kitService = kitService;
        this.compassService = compassService;
        this.reportService = reportService;
        this.surroundService = surroundService;
        this.tickService = tickService;
        this.sessionConfig = sessionConfig;
    }

    public MatchSession getSession() {
        return activeSession;
    }

    public PrimeContext getPrimeContext() {
        return primeContext;
    }

    public PersistentStateStore.StateData getStateData() {
        return stateData;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public SessionConfig getSessionConfig() {
        return sessionConfig;
    }

    public void applySessionConfig(SessionConfig config) {
        this.sessionConfig = config;
        if (activeSession != null) {
            activeSession.activeKitId = config.kitSelected();
            activeSession.keepInventoryMode = activeSession.endInventoryControlActivated
                    ? config.endInventoryControlMode()
                    : config.inventoryControlMode();
            reportService.updateSessionSettings(activeSession.keepInventoryMode, activeSession.activeKitId);
        }
    }

    public boolean hasActiveMatch() {
        return activeSession != null;
    }

    public boolean isPrimeActive() {
        return primeContext != null;
    }

    public UUID selectedRunnerUuid() {
        return stateData.runnerUuid;
    }

    public Player selectedRunnerPlayer() {
        return stateData.runnerUuid == null ? null : Bukkit.getPlayer(stateData.runnerUuid);
    }

    public Set<UUID> explicitHunters() {
        return Collections.unmodifiableSet(stateData.explicitHunters);
    }

    public KeepInventoryMode inventoryControlMode() {
        return sessionConfig.inventoryControlMode();
    }

    public void setInventoryControlMode(KeepInventoryMode mode) {
        KeepInventoryMode resolved = (mode == null || mode == KeepInventoryMode.INHERIT)
                ? KeepInventoryMode.NONE : mode;
        applySessionConfig(sessionConfig.toBuilder().inventoryControlMode(resolved).build());
    }

    /**
     * Switches the active session's effective inventory control mode to the
     * configured end-dimension override. Called once when the runner first
     * enters the End. No-op if the session has already been switched or if
     * there is no active session.
     */
    public void activateEndInventoryControl(KeepInventoryMode endMode) {
        if (activeSession == null || activeSession.endInventoryControlActivated) return;
        activeSession.endInventoryControlActivated = true;
        activeSession.keepInventoryMode = endMode;
        reportService.updateSessionSettings(endMode, activeSession.activeKitId);
        broadcast(Text.mm("<yellow>Runner has entered the End. Inventory control switched to <white>"
                + endMode.name() + "</white>.</yellow>"));
    }

    public String activeKitId() {
        return sessionConfig.kitSelected();
    }

    public void setActiveKitId(String activeKitId) {
        applySessionConfig(sessionConfig.toBuilder().kitSelected(activeKitId).build());
    }

    public boolean toggleRunner(UUID playerUuid) {
        ensureNotActive();
        boolean set;
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            stateData.runnerUuid = null;
            set = false;
        } else {
            stateData.runnerUuid = playerUuid;
            stateData.explicitHunters.remove(playerUuid);
            set = true;
        }
        touchSelections();
        return set;
    }

    public boolean toggleHunter(UUID playerUuid) {
        if (stateData.explicitHunters.contains(playerUuid)) {
            removeHunter(playerUuid);
            return false;
        }
        return addHunter(playerUuid);
    }

    public boolean addHunter(UUID playerUuid) {
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            return false;
        }
        boolean changed = stateData.explicitHunters.add(playerUuid);
        touchSelections();
        if (activeSession != null) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) addHunterToActiveMatch(player);
        }
        return changed;
    }

    public boolean removeHunter(UUID playerUuid) {
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            return false;
        }
        boolean changed = stateData.explicitHunters.remove(playerUuid);
        touchSelections();
        if (activeSession != null) {
            removeHunterFromActiveMatch(playerUuid);
        }
        return changed;
    }

    public int clearExplicitHunters() {
        ensureNotActive();
        int removed = stateData.explicitHunters.size();
        if (removed > 0) {
            stateData.explicitHunters.clear();
            touchSelections();
        }
        return removed;
    }

    public void prepare(EnumSet<PrepareMode> modes) {
        EnumSet<PrepareMode> resolvedModes = modes == null || modes.isEmpty()
                ? EnumSet.of(PrepareMode.HEALTH_AND_HUNGER, PrepareMode.STATUS_EFFECTS, PrepareMode.EXPERIENCE)
                : EnumSet.copyOf(modes);

        // Prepare remains intentionally role-neutral: operators can choose which state buckets to
        // normalize, but this command never teleports players or changes match roles/game modes.
        for (Player player : resolvePrepareParticipants()) {
            if (resolvedModes.contains(PrepareMode.STATUS_EFFECTS)) {
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
                player.setFireTicks(0);
                player.setFreezeTicks(0);
            }
            if (resolvedModes.contains(PrepareMode.HEALTH_AND_HUNGER)) {
                player.setHealth(maxHealth(player));
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
            }
            if (resolvedModes.contains(PrepareMode.EXPERIENCE)) {
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
            if (resolvedModes.contains(PrepareMode.INVENTORY)) {
                player.getInventory().clear();
                player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                player.getInventory().setItemInOffHand(null);
                player.updateInventory();
            }
        }
    }

    public void prime(Boolean keepPlayersFullOverride) {
        if (activeSession != null) throw new IllegalStateException("A match is already active.");
        Player runner = requireOnlineRunner();
        Set<UUID> participants = resolveNextParticipantUuids(true);
        if (participants.isEmpty()) participants.add(runner.getUniqueId());

        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setHealth(maxHealth(player));
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
        }
        // Prime stores the runner's starting position and freezes the participant set that will be
        // kept full while waiting for first movement.
        primeContext = PrimeContext.create(
                runner.getLocation().clone(),
                keepPlayersFullOverride,
                participants
        );
        tickService.startPrimeTask();

        // Notify all participants that the match is primed.
        String runnerName = runner.getName();
        broadcast(Text.mm("<aqua><b>Match primed.</b></aqua> <gray>The match will start as soon as <white>"
                + runnerName + "</white> moves.</gray>"));

        // Give the runner a more prominent, personal heads-up.
        runner.sendMessage(Text.mm(
                "<gold><b>You are the runner.</b></gold> <yellow>Move to begin the manhunt!</yellow>"));
    }

    public UUID startNow() throws IOException {
        if (activeSession != null) throw new IllegalStateException("A match is already active.");
        if (pendingFinalizationOutcome != null || reportService.isRunning()) {
            throw new IllegalStateException("The previous match report is still pending finalization. Fix report storage and run /peoplehunt stop again before starting a new match.");
        }
        Player runner = requireOnlineRunner();
        // Starting always clears the primed state first so the live session becomes the single
        // source of truth for runtime logic.
        cancelPrimeInternal();

        Set<UUID> hunterIds = resolveNextHunterUuids();
        Set<UUID> spectatorIds = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!uuid.equals(runner.getUniqueId()) && !hunterIds.contains(uuid)) {
                spectatorIds.add(uuid);
            }
        }

        List<ReportService.ParticipantSeed> participants = new ArrayList<>();
        participants.add(new ReportService.ParticipantSeed(
                runner.getUniqueId(), runner.getName(), Role.RUNNER.name(), false, false
        ));
        for (UUID hunterId : hunterIds) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null) {
                participants.add(new ReportService.ParticipantSeed(
                        hunter.getUniqueId(), hunter.getName(), Role.HUNTER.name(), false, false
                ));
            }
        }
        for (UUID spectatorId : spectatorIds) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null) {
                participants.add(new ReportService.ParticipantSeed(
                        spectator.getUniqueId(), spectator.getName(), Role.SPECTATOR.name(), false, true
                ));
            }
        }

        UUID reportId;
        try {
            reportId = reportService.startSession(
                    runner.getUniqueId(), runner.getName(), sessionConfig.inventoryControlMode(),
                    sessionConfig.kitSelected(), participants
            );
        } catch (IOException exception) {
            reportOperatorFailure("initialize", "the match report database", "match start", exception);
            throw exception;
        }

        // Active session state is kept entirely in memory; only operator selections/settings are
        // persisted across restarts.
        activeSession = new MatchSession(
                reportId,
                System.currentTimeMillis(),
                runner.getUniqueId(),
                hunterIds,
                spectatorIds,
                sessionConfig.kitSelected(),
                sessionConfig.inventoryControlMode()
        );
        activeSession.currentRunnerLocation = runner.getLocation().clone();

        activeSession.lifeIndex.put(runner.getUniqueId(), 1);
        for (UUID hunterId : hunterIds) {
            activeSession.lifeIndex.put(hunterId, 1);
            activeSession.deathstreaks.put(hunterId, new MatchSession.DeathstreakState());
        }
        for (UUID spectatorId : spectatorIds) {
            activeSession.lifeIndex.put(spectatorId, 1);
        }

        for (World world : Bukkit.getWorlds()) {
            Location spawn = world.getSpawnLocation();
            reportService.recordMarker("world_spawn", null, null, spawn, "World Spawn", "World spawn in " + io.github.ganyuke.peoplehunt.util.PrettyNames.key(world.getKey().asString()), "#9ca3af");
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(runner.getUniqueId())) {
                activeSession.roles.put(player.getUniqueId(), Role.RUNNER);
                player.setGameMode(GameMode.SURVIVAL);
            } else if (hunterIds.contains(player.getUniqueId())) {
                activeSession.roles.put(player.getUniqueId(), Role.HUNTER);
                player.setGameMode(GameMode.SURVIVAL);
                compassService.giveCompass(List.of(player));
                if (sessionConfig.kitApplyOnStart() && sessionConfig.kitSelected() != null) {
                    kitService.applyMissingKit(player, sessionConfig.kitSelected());
                }
            } else {
                activeSession.roles.put(player.getUniqueId(), Role.SPECTATOR);
                if (sessionConfig.autoSpectateNewJoins()) player.setGameMode(GameMode.SPECTATOR);
            }
        }

        tickService.startRuntimeTasks();
        scheduleElapsedAnnouncementsForCurrentSession();
        for (UUID uuid : new ArrayList<>(activeSession.roles.keySet())) {
            Player sampled = Bukkit.getPlayer(uuid);
            if (sampled != null) {
                tickService.captureImmediateSample(sampled);
            }
        }
        broadcast(Text.mm("<green>Manhunt started."));
        reportService.recordTimeline(runner.getUniqueId(), runner.getName(), "match", "Manhunt started");
        persistQuietly();
        return reportId;
    }

    public void stopInconclusive() {
        if (activeSession == null) {
            cancelPrimeInternal();
            if (pendingFinalizationOutcome != null) {
                finalizePendingReportAsync();
            }
            return;
        }
        finishMatchAsync(MatchOutcome.INCONCLUSIVE);
    }

    public Optional<ReportService.FinishResult> stopInconclusiveBlocking() throws IOException {
        if (activeSession == null) {
            cancelPrimeInternal();
            return pendingFinalizationOutcome == null ? Optional.empty() : finalizePendingReportBlocking();
        }
        return finishMatchBlocking(MatchOutcome.INCONCLUSIVE);
    }

    public void endHunterVictory() {
        if (activeSession != null) {
            finishMatchAsync(MatchOutcome.HUNTER_VICTORY);
        }
    }

    public void endRunnerVictory() {
        if (activeSession != null) {
            finishMatchAsync(MatchOutcome.RUNNER_VICTORY);
        }
    }

    private void announceFinishedSummaryOnce(String summary) {
        Component component = Text.mm(summary);
        // Send exactly once, after the current event has unwound, so the summary appears after
        // vanilla death/victory chat without being tied to respawn or join timing.
        Bukkit.getScheduler().runTask(plugin, () -> broadcast(component));
    }

    private void finishMatchAsync(MatchOutcome outcome) {
        if (activeSession == null) {
            return;
        }
        tickService.stopRuntimeTasks();
        cancelAnnouncementTask();
        activeSession = null;
        pendingFinalizationOutcome = outcome;
        broadcast(Text.mm("<green>Manhunt ended."));
        finalizePendingReportAsync();
    }

    private Optional<ReportService.FinishResult> finishMatchBlocking(MatchOutcome outcome) throws IOException {
        if (activeSession == null) {
            return Optional.empty();
        }
        tickService.stopRuntimeTasks();
        cancelAnnouncementTask();
        activeSession = null;
        pendingFinalizationOutcome = outcome;
        broadcast(Text.mm("<green>Manhunt ended."));
        return finalizePendingReportBlocking();
    }

    private void finalizePendingReportAsync() {
        if (pendingFinalizationOutcome == null) {
            return;
        }
        MatchOutcome outcome = pendingFinalizationOutcome;
        UUID reportId = reportService.activeReportId();
        boolean scheduled = reportService.finishAsync(outcome, result -> {
            pendingFinalizationOutcome = null;
            result.ifPresent(finish -> applyFinishedReport(outcome, finish));
        }, exception -> notifyOperatorsOfFailure(
                "finalize",
                reportId == null ? "the finished match report" : "report " + reportId,
                "automatic match end",
                exception
        ));
        if (!scheduled && !reportService.isFinalizationInProgress()) {
            warnOperators("PeopleHunt reporting error: match finalization could not be scheduled. Try /peoplehunt stop again once report storage is healthy.");
        }
    }

    private Optional<ReportService.FinishResult> finalizePendingReportBlocking() throws IOException {
        if (pendingFinalizationOutcome == null) {
            return Optional.empty();
        }
        MatchOutcome outcome = pendingFinalizationOutcome;
        Optional<ReportService.FinishResult> result = reportService.finishBlocking(outcome);
        pendingFinalizationOutcome = null;
        result.ifPresent(finish -> applyFinishedReport(outcome, finish));
        return result;
    }

    private void applyFinishedReport(MatchOutcome outcome, ReportService.FinishResult finish) {
        String summary = buildFinishedStatsMessage(finish.snapshot());
        // The formatted summary is cached into persisted state so /peoplehunt status can show
        // the last finished match even after restart, as long as selections have not changed.
        announceFinishedSummaryOnce(summary);
        stateData.lastStatusSnapshot = new PersistentStateStore.LastStatusSnapshot(
                finish.indexEntry().reportId(),
                stateData.selectionGeneration,
                finish.indexEntry().startedAtEpochMillis(),
                finish.indexEntry().endedAtEpochMillis(),
                outcome,
                finish.indexEntry().runnerUuid(),
                finish.indexEntry().runnerName(),
                Text.formatTimestamp(finish.indexEntry().startedAtEpochMillis()),
                Text.formatDurationMillis(
                        finish.indexEntry().endedAtEpochMillis() - finish.indexEntry().startedAtEpochMillis()
                ),
                summary
        );
        persistQuietly();
    }

    public boolean isRunner(UUID uuid) {
        return activeSession != null
                ? activeSession.roles.get(uuid) == Role.RUNNER
                : Objects.equals(stateData.runnerUuid, uuid);
    }

    public boolean isHunter(UUID uuid) {
        return activeSession != null
                ? activeSession.roles.get(uuid) == Role.HUNTER
                : stateData.explicitHunters.contains(uuid);
    }

    public boolean isParticipant(UUID uuid) {
        return activeSession != null && activeSession.roles.containsKey(uuid);
    }

    public Role roleOf(UUID uuid) {
        return activeSession == null ? null : activeSession.roles.get(uuid);
    }

    public Collection<Player> onlineHunters() {
        if (activeSession == null) {
            return resolveNextHunterUuids().stream()
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return activeSession.hunterIds.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();
    }

    public void surroundHunters(double minRadius, Double maxRadius) {
        Player runner = requireOnlineRunner();
        surroundService.surround(runner, new ArrayList<>(onlineHunters()), minRadius, maxRadius);
    }

    public void cancelPrimeInternal() {
        primeContext = null;
        tickService.cancelPrimeTask();
    }

    public void cancelAnnouncementTask() {
        if (announcementTask != null) {
            announcementTask.cancel();
            announcementTask = null;
        }
    }

    public @Nullable String scheduleElapsedAnnouncementsForCurrentSession() {
        cancelAnnouncementTask();
        MatchSession session = activeSession;
        if (session == null) {
            return null;
        }
        int intervalMinutes = sessionConfig.elapsedAnnouncementMinutes();
        if (intervalMinutes <= 0) {
            return "Elapsed time announcements disabled.";
        }

        long elapsedMs = Math.max(0L, System.currentTimeMillis() - session.startedAtEpochMillis);
        long intervalMs = intervalMinutes * 60_000L;
        long offsetMs = intervalMs - (elapsedMs % intervalMs);
        long delayTicks = Math.max(1L, (offsetMs + 49L) / 50L);
        long intervalTicks = Math.max(1L, (intervalMs + 49L) / 50L);
        announcementTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeSession != session) {
                return;
            }
            announceElapsedTime(session);
            announcementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (activeSession != session) {
                    cancelAnnouncementTask();
                    return;
                }
                announceElapsedTime(session);
            }, intervalTicks, intervalTicks);
        }, delayTicks);
        return "Next elapsed announcement in "
                + Text.formatDurationMillis(offsetMs)
                + ". Repeating every " + intervalMinutes + " minutes.";
    }

    private void announceElapsedTime(MatchSession session) {
        long elapsedMinutes = Math.max(0L, (System.currentTimeMillis() - session.startedAtEpochMillis) / 60_000L);
        broadcast(Text.mm("<yellow>Manhunt time elapsed: " + elapsedMinutes + " minutes"));
    }

    public void broadcast(Component component) {
        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(component);
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public void warnOperators(String message) {
        Component component = Text.mm("<red>" + message + "</red>");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp() || player.hasPermission("peoplehunt.admin")) {
                player.sendMessage(component);
            }
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public void notifyOperatorsOfFailure(String action, String subject, String trigger, Throwable throwable) {
        StringBuilder message = new StringBuilder("PeopleHunt: failed to ")
                .append(action)
                .append(' ')
                .append(subject);
        if (trigger != null && !trigger.isBlank()) {
            message.append(" via ").append(trigger);
        }
        message.append(" due to ")
                .append(ExceptionUtil.summarize(throwable))
                .append(". See console.");
        warnOperators(message.toString());
    }

    public void reportOperatorFailure(String action, String subject, String trigger, Throwable throwable) {
        notifyOperatorsOfFailure(action, subject, trigger, throwable);
        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Failed to " + action + ' ' + subject + (trigger == null || trigger.isBlank() ? "" : " via " + trigger) + '.',
                throwable);
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) return "unset";
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        var offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() == null ? uuid.toString() : offline.getName();
    }

    private void ensureNotActive() {
        if (activeSession != null || primeContext != null) {
            throw new IllegalStateException("Stop or finish the current session first.");
        }
    }

    private Player requireOnlineRunner() {
        if (stateData.runnerUuid == null) throw new IllegalStateException("A runner must be selected first.");
        Player runner = Bukkit.getPlayer(stateData.runnerUuid);
        if (runner == null) throw new IllegalStateException("The selected runner must be online.");
        return runner;
    }

    private void touchSelections() {
        stateData.selectionGeneration++;
        stateData.lastStatusSnapshot = null;
        persistQuietly();
    }

    private void persistQuietly() {
        stateStore.saveAsync(stateData);
    }

    private Set<UUID> resolveNextParticipantUuids(boolean includeRunner) {
        Set<UUID> out = new LinkedHashSet<>();
        if (includeRunner && stateData.runnerUuid != null) out.add(stateData.runnerUuid);
        out.addAll(resolveNextHunterUuids());
        return out;
    }

    private Set<UUID> resolveNextHunterUuids() {
        // Explicit hunter selections win. Otherwise the plugin falls back to the common manhunt
        // convenience rule: every online non-runner becomes a hunter.
        if (!stateData.explicitHunters.isEmpty()) {
            return stateData.explicitHunters.stream()
                    .filter(uuid -> !Objects.equals(uuid, stateData.runnerUuid))
                    .filter(uuid -> Bukkit.getPlayer(uuid) != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .filter(uuid -> !Objects.equals(uuid, stateData.runnerUuid))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Player> resolvePrepareParticipants() {
        Set<Player> players = new LinkedHashSet<>();
        if (stateData.runnerUuid != null) {
            Player runner = Bukkit.getPlayer(stateData.runnerUuid);
            if (runner != null) players.add(runner);
        }
        if (!stateData.explicitHunters.isEmpty()) {
            for (UUID uuid : stateData.explicitHunters) {
                Player hunter = Bukkit.getPlayer(uuid);
                if (hunter != null) players.add(hunter);
            }
        } else {
            players.addAll(Bukkit.getOnlinePlayers());
        }
        return new ArrayList<>(players);
    }

    public void addHunterToActiveMatch(Player player) {
        if (activeSession == null || player == null || player.getUniqueId().equals(activeSession.runnerUuid)) return;
        UUID uuid = player.getUniqueId();

        // Mid-match role changes are applied immediately and also forwarded to report state so the
        // after-action report reflects late joins and manual admin intervention.
        activeSession.hunterIds.add(uuid);
        activeSession.spectatorIds.remove(uuid);
        activeSession.roles.put(uuid, Role.HUNTER);
        activeSession.lifeIndex.putIfAbsent(uuid, 1);
        activeSession.deathstreaks.putIfAbsent(uuid, new MatchSession.DeathstreakState());
        player.setGameMode(GameMode.SURVIVAL);
        compassService.giveCompass(List.of(player));
        if (sessionConfig.kitSelected() != null) kitService.applyMissingKit(player, sessionConfig.kitSelected());
        reportService.registerParticipant(uuid, player.getName(), Role.HUNTER.name(), true, false);
        reportService.recordTimeline(uuid, player.getName(), "participant", "joined as hunter");
    }

    public void removeHunterFromActiveMatch(UUID uuid) {
        if (activeSession == null || uuid == null || !activeSession.hunterIds.contains(uuid)) return;

        // Removing a hunter demotes them to spectator rather than fully removing them from the
        // session so their historical activity remains represented in the report.
        activeSession.hunterIds.remove(uuid);
        activeSession.spectatorIds.add(uuid);
        activeSession.roles.put(uuid, Role.SPECTATOR);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && sessionConfig.autoSpectateNewJoins()) player.setGameMode(GameMode.SPECTATOR);
        if (player != null) {
            reportService.registerParticipant(uuid, player.getName(), Role.SPECTATOR.name(), true, false);
            reportService.recordTimeline(uuid, player.getName(), "participant", "changed to spectator");
        }
    }

    public Component buildStatusComponent() {
        if (activeSession != null) {
            return Text.mm(buildActiveStatusMessage());
        }
        if (primeContext != null) {
            return Text.mm(buildPrimedStatusMessage());
        }
        if (stateData.lastStatusSnapshot != null
                && stateData.lastStatusSnapshot.selectionGeneration() == stateData.selectionGeneration) {
            return Text.mm(stateData.lastStatusSnapshot.summaryText());
        }
        return Text.mm(buildIdleStatusMessage());
    }

    private String buildActiveStatusMessage() {
        List<String> lines = new ArrayList<>(List.of(
                statusLine("State", "<green><b>ACTIVE</b></green>"),
                statusLine("Runner", white(nameOf(stateData.runnerUuid))),
                statusLine("Hunters", white(namesOf(activeSession.hunterIds))),
                statusLine("Spectators", white(namesOf(activeSession.spectatorIds))),
                statusLine("Started", white(Text.formatTimestamp(activeSession.startedAtEpochMillis))),
                statusLine("Elapsed", white(Text.formatDurationMillis(
                        System.currentTimeMillis() - activeSession.startedAtEpochMillis
                ))),
                statusLine("Inventory control", white(String.valueOf(activeSession.keepInventoryMode)))
        ));
        if (activeSession.keepInventoryMode == KeepInventoryMode.KIT) {
            lines.add(statusLine("Kit", white(displayKit(activeSession.activeKitId))));
        }
        return messageBlock("PEOPLEHUNT STATUS", lines);
    }

    private String buildPrimedStatusMessage() {
        return messageBlock("PEOPLEHUNT STATUS", List.of(
                statusLine("State", "<aqua><b>PRIMED</b></aqua>"),
                statusLine("Runner", white(nameOf(stateData.runnerUuid))),
                statusLine("Hunters", white(namesOf(resolveNextHunterUuids()))),
                statusLine("Primed at", white(Text.formatTimestamp(primeContext.primedAtEpochMillis()))),
                statusLine("Keep full", white(String.valueOf(primeContext.keepPlayersFull(sessionConfig))))
        ));
    }

    private String buildIdleStatusMessage() {
        List<String> lines = new ArrayList<>(List.of(
                statusLine("State", "<gray><b>IDLE</b></gray>"),
                statusLine("Pending runner", white(nameOf(stateData.runnerUuid))),
                statusLine(
                        "Pending hunters",
                        white(stateData.explicitHunters.isEmpty()
                                ? "all online except runner"
                                : namesOf(stateData.explicitHunters))
                ),
                statusLine("Inventory control", white(String.valueOf(sessionConfig.inventoryControlMode())))
        ));
        if (sessionConfig.inventoryControlMode() == KeepInventoryMode.KIT) {
            lines.add(statusLine("Kit", white(displayKit(sessionConfig.kitSelected()))));
        }
        return messageBlock("PEOPLEHUNT STATUS", lines);
    }

    private String buildFinishedStatsMessage(ReportModels.ViewerSnapshot snapshot) {
        long duration = snapshot.metadata().endedAtEpochMillis() - snapshot.metadata().startedAtEpochMillis();

        List<String> lines = new ArrayList<>();
        lines.add(statusLine("Result", coloredOutcome(snapshot.metadata().outcome())));
        lines.add(statusLine("Runner", white(snapshot.metadata().runnerName())));
        lines.add(
                statusLine(
                        "Time",
                        white(Text.formatDurationMillis(duration))
                                + " <dark_gray>(started: "
                                + Text.formatTimestamp(snapshot.metadata().startedAtEpochMillis())
                                + ")</dark_gray>"
                )
        );
        lines.add("");
        lines.add(section("Participant Performance"));
        lines.addAll(snapshot.stats().stream()
                .map(this::participantStatLine)
                .toList());

        return messageBlock("POST-MATCH STATS", lines);
    }

    private String participantStatLine(ReportModels.ParticipantStats stat) {
        return BULLET
                + white(nameOf(stat.uuid()))
                + " <dark_gray>|</dark_gray> <red>☠ "
                + stat.deaths()
                + "</red> <aqua>⚔ "
                + stat.playerKills()
                + "</aqua>";
    }

    private String messageBlock(String title, List<String> lines) {
        StringBuilder builder = new StringBuilder();
        builder.append(HEADER_LINE.formatted(Text.escapeTags(title))).append("\n");

        for (int i = 0; i < lines.size(); i++) {
            builder.append(lines.get(i));
            if (i < lines.size() - 1) {
                builder.append("\n");
            }
        }

        builder.append("\n").append(FOOTER_LINE);
        return builder.toString();
    }

    private String statusLine(String label, String value) {
        return LABEL_PREFIX.formatted(Text.escapeTags(label)) + value;
    }

    private String section(String title) {
        return SECTION_PREFIX.formatted(Text.escapeTags(title + ":"));
    }

    private String displayOutcome(String outcome) {
        return switch (outcome) {
            case "HUNTER_VICTORY" -> "Hunter Victory";
            case "RUNNER_VICTORY" -> "Runner Victory";
            case "INCONCLUSIVE" -> "Inconclusive";
            default -> outcome.replace('_', ' ');
        };
    }

    private String white(String value) {
        return VALUE.formatted(Text.escapeTags(value));
    }

    private String coloredOutcome(String outcome) {
        String pretty = displayOutcome(outcome);

        if (outcome.contains("HUNTER")) {
            return "<red><b>" + pretty + "</b></red>";
        }
        if (outcome.contains("RUNNER")) {
            return "<green><b>" + pretty + "</b></green>";
        }
        return "<yellow><b>" + pretty + "</b></yellow>";
    }

    private String displayKit(String kitId) {
        return kitId == null ? "none" : kitId;
    }

    private String namesOf(Collection<UUID> uuids) {
        return (uuids == null || uuids.isEmpty())
                ? "none"
                : uuids.stream().map(this::nameOf).collect(Collectors.joining(", "));
    }

    public Optional<Location> consumeEndPortalPrompt(UUID uuid) {
        MatchSession session = this.getSession();
        return session == null ? Optional.empty() : Optional.ofNullable(session.pendingPortalPrompt.remove(uuid));
    }


    public int resetDeathstreaks(UUID targetUuid) {
        if (activeSession == null) {
            throw new IllegalStateException("No active match to reset deathstreaks for.");
        }
        if (targetUuid != null && !activeSession.hunterIds.contains(targetUuid)) {
            throw new IllegalArgumentException("Target player is not an active hunter.");
        }

        int reset = 0;
        for (UUID hunterUuid : new ArrayList<>(activeSession.hunterIds)) {
            if (targetUuid != null && !targetUuid.equals(hunterUuid)) {
                continue;
            }
            MatchSession.DeathstreakState state = activeSession.deathstreaks.get(hunterUuid);
            if (state == null || state.streakDeaths == 0) {
                continue;
            }
            state.streakDeaths = 0;
            Player player = Bukkit.getPlayer(hunterUuid);
            String playerName = player != null ? player.getName() : nameOf(hunterUuid);
            reportService.recordTimeline(
                    hunterUuid,
                    playerName,
                    "admin",
                    "deathstreak reset by operator",
                    "DEATHSTREAK_RESET",
                    "#fbbf24"
            );
            reset++;
        }

        return reset;
    }


    public int rollback(UUID targetUuid, long rewindMillis, boolean teleport, boolean restoreGameMode, boolean restoreEffects) {
        if (activeSession == null) {
            throw new IllegalStateException("No active match to roll back.");
        }
        long targetTime = System.currentTimeMillis() - Math.max(0L, rewindMillis);
        int restored = 0;
        for (UUID uuid : new ArrayList<>(activeSession.roles.keySet())) {
            if (targetUuid != null && !targetUuid.equals(uuid)) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            MatchSession.RollbackState state = chooseRollbackState(activeSession.rollbackBuffer.get(uuid), targetTime);
            if (state == null) continue;
            applyRollbackState(player, state, teleport, restoreGameMode, restoreEffects);
            reportService.recordTimeline(uuid, player.getName(), "rollback", "rolled back by operator to " + Text.formatDurationMillis(Math.max(0L, rewindMillis)) + " ago", Long.toString(rewindMillis), "#fca5a5");
            restored++;
        }
        return restored;
    }

    private MatchSession.RollbackState chooseRollbackState(java.util.Deque<MatchSession.RollbackState> buffer, long targetTime) {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        MatchSession.RollbackState candidate = buffer.peekFirst();
        for (MatchSession.RollbackState state : buffer) {
            if (state.capturedAtEpochMillis() <= targetTime) {
                candidate = state;
                continue;
            }
            break;
        }
        return candidate;
    }

    private double maxHealth(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? player.getHealth() : attribute.getValue();
    }

    private void applyRollbackState(Player player, MatchSession.RollbackState state, boolean teleport, boolean restoreGameMode, boolean restoreEffects) {
        if (teleport && state.location() != null && state.location().getWorld() != null) {
            player.teleport(state.location());
        }
        if (restoreGameMode && state.gameMode() != null) {
            player.setGameMode(state.gameMode());
        }

        // Restore inventory state
        player.getInventory().clear();
        ItemStack[] contents = new ItemStack[player.getInventory().getSize()];
        for (int i = 0; i < contents.length && i < state.contents().size(); i++) {
            ItemStack item = state.contents().get(i);
            contents[i] = item == null ? null : item.clone();
        }
        player.getInventory().setContents(contents);
        player.getInventory().setHelmet(state.helmet() == null ? null : state.helmet().clone());
        player.getInventory().setChestplate(state.chestplate() == null ? null : state.chestplate().clone());
        player.getInventory().setLeggings(state.leggings() == null ? null : state.leggings().clone());
        player.getInventory().setBoots(state.boots() == null ? null : state.boots().clone());
        player.getInventory().setItemInOffHand(state.offHand() == null ? null : state.offHand().clone());

        // Restore status effects
        if (restoreEffects) {
            for (var effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            for (var effect : state.effects()) {
                player.addPotionEffect(effect);
            }
        }

        // Restore vitals
        // Must restore health AFTER effects so that health boost appies correctly
        var maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? state.maxHealth() : maxHealthAttribute.getValue();
        player.setAbsorptionAmount(state.absorption());
        player.setHealth(Math.max(0.1, Math.min(maxHealth, state.health())));
        player.setFoodLevel(state.food());
        player.setSaturation(state.saturation());
        player.setLevel(state.level());
        player.setTotalExperience(state.totalExperience());

    }

}
