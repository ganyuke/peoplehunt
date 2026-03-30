package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.Role;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.Text;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MatchTickService {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final ReportService reportService;
    private MatchManager matchManager; // Setter injection to prevent circular dependency

    private int pathTaskId = -1;
    private int elapsedTaskId = -1;
    private int primeTaskId = -1;

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
        pathTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::recordPathSample, config.playerPathSampleIntervalTicks(), config.playerPathSampleIntervalTicks());
        elapsedTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
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
        if (pathTaskId != -1) Bukkit.getScheduler().cancelTask(pathTaskId);
        if (elapsedTaskId != -1) Bukkit.getScheduler().cancelTask(elapsedTaskId);
        pathTaskId = -1;
        elapsedTaskId = -1;
    }

    public void startPrimeTask() {
        cancelPrimeTask();
        primeTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            PrimeContext ctx = matchManager.getPrimeContext();
            if (ctx == null || !ctx.keepPlayersFull) return;
            for (UUID uuid : ctx.participantIds) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                    player.setFoodLevel(20);
                    player.setSaturation(20.0f);
                }
            }
        }, 20L, 20L);
    }

    public void cancelPrimeTask() {
        if (primeTaskId != -1) Bukkit.getScheduler().cancelTask(primeTaskId);
        primeTaskId = -1;
    }

    private void recordPathSample() {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        for (Map.Entry<UUID, Role> entry : session.roles.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                reportService.recordPath(player.getUniqueId(), player.getName(), session.lifeIndex.getOrDefault(player.getUniqueId(), 1), player.getLocation(), (float) player.getHealth(), player.getFoodLevel(), player.getSaturation());
            }
        }
    }
}