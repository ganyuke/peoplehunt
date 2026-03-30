package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.analytics.AnalyticsRecorder;
import io.github.ganyuke.manhunt.analytics.HealthSampler;
import io.github.ganyuke.manhunt.analytics.LifeTracker;
import io.github.ganyuke.manhunt.analytics.MilestoneService;
import io.github.ganyuke.manhunt.analytics.PathSampler;
import io.github.ganyuke.manhunt.catchup.DeathstreakService;
import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.map.MapPublisher;
import io.github.ganyuke.manhunt.util.PlayerUtil;
import io.github.ganyuke.manhunt.util.TextUtil;
import io.github.ganyuke.manhunt.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class MatchManager {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final RoleService roleService;
    private final TimerService timerService;
    private final CompassService compassService;
    private final AnalyticsRecorder analyticsRecorder;
    private final LifeTracker lifeTracker;
    private final PathSampler pathSampler;
    private final MilestoneService milestoneService;
    private final HealthSampler healthSampler;
    private final DeathstreakService deathstreakService;
    private final MatchStatsService matchStatsService;
    private final MapPublisher mapPublisher;

    private MatchSession currentSession;
    private UUID latestSessionId;
    private TerminalEvent pendingTerminal;
    private BukkitTask terminalTask;

    public MatchManager(JavaPlugin plugin,
                        ConfigManager configManager,
                        RoleService roleService,
                        TimerService timerService,
                        CompassService compassService,
                        AnalyticsRecorder analyticsRecorder,
                        LifeTracker lifeTracker,
                        PathSampler pathSampler,
                        MilestoneService milestoneService,
                        HealthSampler healthSampler,
                        DeathstreakService deathstreakService,
                        MatchStatsService matchStatsService,
                        MapPublisher mapPublisher) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.roleService = roleService;
        this.timerService = timerService;
        this.compassService = compassService;
        this.analyticsRecorder = analyticsRecorder;
        this.lifeTracker = lifeTracker;
        this.pathSampler = pathSampler;
        this.milestoneService = milestoneService;
        this.healthSampler = healthSampler;
        this.deathstreakService = deathstreakService;
        this.matchStatsService = matchStatsService;
        this.mapPublisher = mapPublisher;
        this.timerService.setIntervalListener(this::announceElapsedInterval);
    }

    public synchronized MatchSession prime() {
        if (isRoleSelectionLocked()) {
            throw new IllegalStateException("The match is already primed or running.");
        }
        MatchSession session = prepareFreshSession();
        refreshHuntersBeforeStart(session);
        session.markPrimed(Instant.now());
        analyticsRecorder.markPrimed(session);
        messageParticipants(session, "&eMatch primed. It will start when the runner changes blocks.");
        return session;
    }

    public synchronized MatchSession start(String reason) {
        if (currentSession != null && currentSession.isRunning()) {
            throw new IllegalStateException("The match is already running.");
        }
        MatchSession session = (currentSession != null && currentSession.isPrimed()) ? currentSession : prepareFreshSession();
        refreshHuntersBeforeStart(session);
        roleService.lockHuntersForSession(session.hunterIds());
        session.markStarted(Instant.now());
        analyticsRecorder.markStarted(session);
        if (configManager.settings().autoGiveCompassesOnStart()) {
            compassService.giveTrackersToHunters(session);
        }
        compassService.start(session);
        timerService.start(session);
        matchStatsService.beginSession(session);
        lifeTracker.beginSession(session);
        pathSampler.beginSession(session);
        milestoneService.start(session);
        healthSampler.start(session);
        deathstreakService.beginSession(session);
        messageParticipants(session, "&eManhunt started. Runner: &f" + PlayerUtil.name(session.runnerId()) + "&e • Hunters: &f" + session.hunterCount());
        if (reason != null && !reason.isBlank()) {
            messageParticipants(session, "&eStart trigger: &f" + reason);
        }
        return session;
    }

    public synchronized void addMidgameHunterSpectator(Player player) {
        if (currentSession == null || !currentSession.isRunning()) {
            throw new IllegalStateException("The match must be running to add a hunter spectator.");
        }
        if (currentSession.runnerId().equals(player.getUniqueId())) {
            throw new IllegalStateException("The runner cannot be added as a hunter.");
        }
        boolean alreadyHunter = currentSession.hunterIds().contains(player.getUniqueId());
        roleService.addMidgameHunter(player.getUniqueId());
        currentSession.addHunter(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        if (!alreadyHunter) {
            messageParticipants(currentSession, "&e" + player.getName() + " joined as a hunter spectator.");
        }
    }

    public synchronized void requestTerminalResult(VictoryType victoryType, String reason, TerminalCause cause) {
        if (currentSession == null || !currentSession.isRunning()) {
            return;
        }
        TerminalEvent incoming = new TerminalEvent(victoryType, reason, cause);
        if (pendingTerminal == null) {
            pendingTerminal = incoming;
            scheduleTerminalResolution();
            return;
        }
        pendingTerminal = chooseTerminal(pendingTerminal, incoming);
    }

    public synchronized void stop(String reason) {
        if (currentSession == null) {
            return;
        }
        cancelTerminalTask();
        finalizeFinish(new TerminalEvent(VictoryType.NONE, reason, TerminalCause.MANUAL_STOP));
    }

    public synchronized MatchSession getCurrentSession() {
        return currentSession;
    }

    public synchronized UUID getMostRecentSessionId() {
        if (currentSession != null) {
            return currentSession.sessionId();
        }
        return latestSessionId;
    }

    public synchronized boolean isRoleSelectionLocked() {
        return currentSession != null && (currentSession.isPrimed() || currentSession.isRunning());
    }

    public synchronized boolean isParticipant(UUID playerId) {
        return currentSession != null && currentSession.isParticipant(playerId);
    }

    public synchronized Duration elapsed() {
        if (currentSession == null || currentSession.startedAt() == null) {
            return Duration.ZERO;
        }
        return Duration.between(currentSession.startedAt(), Instant.now());
    }

    public synchronized void recordParticipantDamage(UUID victimId, UUID attackerId, double finalDamage) {
        if (currentSession == null || !currentSession.isRunning()) {
            return;
        }
        matchStatsService.recordDamage(currentSession, victimId, attackerId, finalDamage);
    }

    public synchronized void recordParticipantDeath(UUID playerId, Role role) {
        if (currentSession == null) {
            return;
        }
        matchStatsService.recordDeath(currentSession, playerId, role);
    }

    public synchronized void shutdown() {
        cancelTerminalTask();
        timerService.shutdown();
        compassService.shutdown();
        healthSampler.shutdown();
        milestoneService.shutdown();
        if (currentSession != null && (currentSession.isRunning() || currentSession.isPrimed())) {
            finalizeFinish(new TerminalEvent(VictoryType.NONE, "Plugin disabled", TerminalCause.MANUAL_STOP));
        }
    }

    private MatchSession prepareFreshSession() {
        ensureRolesConfigured();
        currentSession = new MatchSession(UUID.randomUUID(), roleService.getRunnerId(), roleService.resolveHuntersForMatchStart());
        analyticsRecorder.openSession(currentSession);
        return currentSession;
    }

    private void refreshHuntersBeforeStart(MatchSession session) {
        Set<UUID> hunters = roleService.resolveHuntersForMatchStart();
        if (hunters.isEmpty()) {
            throw new IllegalStateException("At least one non-runner player must be online to start.");
        }
        session.replaceHunters(hunters);
    }

    private void ensureRolesConfigured() {
        if (!roleService.hasRunner()) {
            throw new IllegalStateException("Choose a runner first.");
        }
        if (roleService.resolveHuntersForMatchStart().isEmpty()) {
            throw new IllegalStateException("At least one non-runner player must be online to hunt.");
        }
    }

    private void scheduleTerminalResolution() {
        cancelTerminalTask();
        terminalTask = Bukkit.getScheduler().runTask(plugin, this::resolvePendingTerminal);
    }

    private synchronized void resolvePendingTerminal() {
        if (pendingTerminal == null) {
            return;
        }
        TerminalEvent terminal = pendingTerminal;
        pendingTerminal = null;
        terminalTask = null;
        finalizeFinish(terminal);
    }

    private synchronized void finalizeFinish(TerminalEvent terminal) {
        if (currentSession == null || currentSession.hasEnded()) {
            return;
        }
        currentSession.markEnded(Instant.now(), terminal.victoryType(), terminal.reason());
        latestSessionId = currentSession.sessionId();
        timerService.stop();
        compassService.stop();
        healthSampler.stop();
        milestoneService.stop();
        deathstreakService.endSession();
        pathSampler.endSession();
        lifeTracker.endSession(currentSession, terminal.reason());
        MatchStatsService.Snapshot statsSnapshot = matchStatsService.endSession(currentSession);
        analyticsRecorder.markEnded(currentSession);
        analyticsRecorder.flush();
        Path sessionDirectory = analyticsRecorder.getSessionDirectory(currentSession.sessionId());
        mapPublisher.publishSession(currentSession, sessionDirectory);
        messageMatchEnd(currentSession, terminal, statsSnapshot);
        roleService.unlockSessionHunters();
    }

    private void messageMatchEnd(MatchSession session, TerminalEvent terminal, MatchStatsService.Snapshot statsSnapshot) {
        String result = switch (terminal.victoryType()) {
            case RUNNER -> "Runner victory";
            case HUNTERS -> "Hunters win";
            case NONE -> "Match stopped";
        };
        messageParticipants(session, "&eMatch ended. &f" + result + "&e • Reason: &f" + terminal.reason());
        messageParticipants(session,
                "&eDuration: &f" + TimeFormat.compactHuman(statsSnapshot.duration())
                        + "&e • Runner deaths: &f" + statsSnapshot.runnerDeaths()
                        + "&e • Hunter deaths: &f" + statsSnapshot.hunterDeaths());
        messageParticipants(session, "&eHunter damage to runner: &f" + String.format(java.util.Locale.US, "%.1f", statsSnapshot.totalDamageToRunner()));
    }

    private TerminalEvent chooseTerminal(TerminalEvent first, TerminalEvent second) {
        return switch (configManager.settings().endResolutionPolicy()) {
            case FIRST_EVENT -> first;
            case DRAGON_PRIORITY -> (first.cause() == TerminalCause.DRAGON_DEATH || second.cause() == TerminalCause.DRAGON_DEATH)
                    ? (first.cause() == TerminalCause.DRAGON_DEATH ? first : second)
                    : first;
            case RUNNER_DEATH_PRIORITY -> (first.cause() == TerminalCause.RUNNER_DEATH || first.cause() == TerminalCause.RUNNER_QUIT
                    || second.cause() == TerminalCause.RUNNER_DEATH || second.cause() == TerminalCause.RUNNER_QUIT)
                    ? ((first.cause() == TerminalCause.RUNNER_DEATH || first.cause() == TerminalCause.RUNNER_QUIT) ? first : second)
                    : first;
        };
    }

    private void cancelTerminalTask() {
        if (terminalTask != null) {
            terminalTask.cancel();
            terminalTask = null;
        }
        pendingTerminal = null;
    }

    private void messageParticipants(MatchSession session, String message) {
        String formatted = TextUtil.prefixed(configManager.settings().prefix(), message);
        Player runner = Bukkit.getPlayer(session.runnerId());
        if (runner != null) {
            runner.sendMessage(formatted);
        }
        for (UUID hunterId : session.hunterIds()) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null) {
                hunter.sendMessage(formatted);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(formatted);
    }

    private void announceElapsedInterval(MatchSession session, Duration elapsed, int intervalIndex) {
        if (session == null || !session.isRunning()) {
            return;
        }
        messageParticipants(session, "&e" + TimeFormat.roughHuman(elapsed) + " elapsed.");
    }

    private record TerminalEvent(VictoryType victoryType, String reason, TerminalCause cause) {
    }
}
