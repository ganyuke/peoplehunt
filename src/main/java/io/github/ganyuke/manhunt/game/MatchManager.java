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
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class MatchManager {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final RoleService roleService;
    private final FreezeService freezeService;
    private final TimerService timerService;
    private final CompassService compassService;
    private final AnalyticsRecorder analyticsRecorder;
    private final LifeTracker lifeTracker;
    private final PathSampler pathSampler;
    private final MilestoneService milestoneService;
    private final HealthSampler healthSampler;
    private final DeathstreakService deathstreakService;
    private final MapPublisher mapPublisher;

    private MatchSession currentSession;
    private UUID latestSessionId;
    private TerminalEvent pendingTerminal;
    private BukkitTask terminalTask;

    public MatchManager(JavaPlugin plugin,
                        ConfigManager configManager,
                        RoleService roleService,
                        FreezeService freezeService,
                        TimerService timerService,
                        CompassService compassService,
                        AnalyticsRecorder analyticsRecorder,
                        LifeTracker lifeTracker,
                        PathSampler pathSampler,
                        MilestoneService milestoneService,
                        HealthSampler healthSampler,
                        DeathstreakService deathstreakService,
                        MapPublisher mapPublisher) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.roleService = roleService;
        this.freezeService = freezeService;
        this.timerService = timerService;
        this.compassService = compassService;
        this.analyticsRecorder = analyticsRecorder;
        this.lifeTracker = lifeTracker;
        this.pathSampler = pathSampler;
        this.milestoneService = milestoneService;
        this.healthSampler = healthSampler;
        this.deathstreakService = deathstreakService;
        this.mapPublisher = mapPublisher;
    }

    public synchronized MatchSession prime() {
        if (isRoleSelectionLocked()) {
            throw new IllegalStateException("The match is already primed or running.");
        }
        MatchSession session = prepareFreshSession();
        session.markPrimed(Instant.now());
        freezeService.setFrozen(true);
        analyticsRecorder.markPrimed(session);
        messageParticipants(session, "&eMatch primed. The first runner block movement will start the hunt.");
        return session;
    }

    public synchronized MatchSession start(String reason) {
        if (currentSession != null && currentSession.isRunning()) {
            throw new IllegalStateException("The match is already running.");
        }
        MatchSession session = (currentSession != null && currentSession.isPrimed()) ? currentSession : prepareFreshSession();
        session.markStarted(Instant.now());
        freezeService.setFrozen(false);
        analyticsRecorder.markStarted(session);
        if (configManager.settings().autoGiveCompassesOnStart()) {
            compassService.giveTrackersToHunters(session);
        }
        compassService.start(session);
        timerService.start(session);
        lifeTracker.beginSession(session);
        pathSampler.beginSession(session);
        milestoneService.start(session);
        healthSampler.start(session);
        deathstreakService.beginSession(session);
        messageParticipants(session, "&aMatch started: &f" + reason);
        return session;
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
        currentSession = new MatchSession(UUID.randomUUID(), roleService.getRunnerId(), roleService.getHunterIds());
        analyticsRecorder.openSession(currentSession);
        return currentSession;
    }

    private void ensureRolesConfigured() {
        if (!roleService.hasRunner()) {
            throw new IllegalStateException("Choose a runner first.");
        }
        if (!roleService.hasHunters()) {
            throw new IllegalStateException("Choose at least one hunter first.");
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
        freezeService.setFrozen(false);
        timerService.stop();
        compassService.stop();
        healthSampler.stop();
        milestoneService.stop();
        deathstreakService.endSession();
        pathSampler.endSession();
        lifeTracker.endSession(currentSession, terminal.reason());
        analyticsRecorder.markEnded(currentSession);
        analyticsRecorder.flush();
        Path sessionDirectory = analyticsRecorder.getSessionDirectory(currentSession.sessionId());
        mapPublisher.publishSession(currentSession, sessionDirectory);
        messageParticipants(currentSession, terminalMessage(terminal));
    }

    private String terminalMessage(TerminalEvent terminal) {
        return switch (terminal.victoryType()) {
            case RUNNER -> "&bRunner victory: &f" + terminal.reason();
            case HUNTERS -> "&cHunters win: &f" + terminal.reason();
            case NONE -> "&7Match stopped: &f" + terminal.reason();
        };
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

    private record TerminalEvent(VictoryType victoryType, String reason, TerminalCause cause) {
    }
}
