package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.core.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;

public final class TimerService {
    @FunctionalInterface
    public interface IntervalListener {
        void onInterval(MatchSession session, Duration elapsed, int intervalIndex);
    }

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private BukkitTask task;
    private MatchSession session;
    private int announcedIntervalIndex;
    private IntervalListener intervalListener;

    public TimerService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void setIntervalListener(IntervalListener intervalListener) {
        this.intervalListener = intervalListener;
    }

    public void start(MatchSession session) {
        stop();
        this.session = session;
        this.announcedIntervalIndex = 0;
        if (!configManager.settings().notificationsEnabled()) {
            return;
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, configManager.settings().notificationCheckTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        session = null;
        announcedIntervalIndex = 0;
    }

    public void restartIfRunning(MatchSession activeSession) {
        if (activeSession != null && activeSession.isRunning()) {
            start(activeSession);
        }
    }

    public void shutdown() {
        stop();
    }

    private void tick() {
        if (session == null || !session.isRunning() || session.startedAt() == null) {
            return;
        }
        int intervalMinutes = configManager.settings().notificationIntervalMinutes();
        if (intervalMinutes <= 0) {
            return;
        }
        Instant startedAt = session.startedAt();
        Duration elapsed = Duration.between(startedAt, Instant.now());
        int currentIntervalIndex = (int) (elapsed.toMinutes() / intervalMinutes);
        if (currentIntervalIndex <= announcedIntervalIndex) {
            return;
        }
        for (int intervalIndex = announcedIntervalIndex + 1; intervalIndex <= currentIntervalIndex; intervalIndex++) {
            if (intervalListener != null) {
                intervalListener.onInterval(session, Duration.ofMinutes((long) intervalIndex * intervalMinutes), intervalIndex);
            }
        }
        announcedIntervalIndex = currentIntervalIndex;
    }
}
