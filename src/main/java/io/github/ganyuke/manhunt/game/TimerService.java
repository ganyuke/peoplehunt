package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class TimerService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private BossBar bossBar;
    private BukkitTask task;
    private MatchSession session;

    public TimerService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void start(MatchSession session) {
        stop();
        this.session = session;
        if (!configManager.settings().timerEnabled()) {
            return;
        }
        BarColor color = configManager.settings().bossBarColor();
        BarStyle style = configManager.settings().bossBarStyle();
        this.bossBar = Bukkit.createBossBar("00:00", color, style);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, configManager.settings().timerUpdateTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        session = null;
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
        if (session == null || bossBar == null || !session.hasStarted()) {
            return;
        }
        Instant startedAt = session.startedAt();
        if (startedAt == null) {
            return;
        }
        Duration elapsed = Duration.between(startedAt, Instant.now());
        bossBar.setTitle("Manhunt • " + TimeFormat.mmss(elapsed));
        bossBar.setProgress(1.0D);
        refreshAudience();
    }

    private void refreshAudience() {
        if (bossBar == null || session == null) {
            return;
        }
        bossBar.removeAll();
        if (configManager.settings().timerVisibleToRunner()) {
            Player runner = Bukkit.getPlayer(session.runnerId());
            if (runner != null) {
                bossBar.addPlayer(runner);
            }
        }
        if (configManager.settings().timerVisibleToHunters()) {
            for (UUID hunterId : session.hunterIds()) {
                Player hunter = Bukkit.getPlayer(hunterId);
                if (hunter != null) {
                    bossBar.addPlayer(hunter);
                }
            }
        }
    }
}
