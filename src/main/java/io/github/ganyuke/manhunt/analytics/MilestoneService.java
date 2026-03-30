package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.evaluator.InventoryAnyMilestoneEvaluator;
import io.github.ganyuke.manhunt.evaluator.WorldEnvironmentMilestoneEvaluator;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.Role;
import io.github.ganyuke.manhunt.game.RoleService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MilestoneService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final AnalyticsRecorder analyticsRecorder;
    private final RoleService roleService;
    private final Map<MilestoneType, MilestoneEvaluator> evaluators = new EnumMap<>(MilestoneType.class);
    private final Map<UUID, Set<String>> completedMilestones = new HashMap<>();
    private BukkitTask task;
    private MatchSession session;

    public MilestoneService(JavaPlugin plugin, ConfigManager configManager, AnalyticsRecorder analyticsRecorder, RoleService roleService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.analyticsRecorder = analyticsRecorder;
        this.roleService = roleService;
        this.evaluators.put(MilestoneType.INVENTORY_ANY, new InventoryAnyMilestoneEvaluator());
        this.evaluators.put(MilestoneType.WORLD_ENVIRONMENT, new WorldEnvironmentMilestoneEvaluator());
    }

    public void start(MatchSession session) {
        stop();
        this.session = session;
        this.completedMilestones.clear();
        if (!configManager.settings().milestonesEnabled()) {
            return;
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, configManager.settings().milestoneScanIntervalTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        session = null;
        completedMilestones.clear();
    }

    public void restartIfRunning(MatchSession activeSession) {
        if (activeSession != null && activeSession.isRunning()) {
            start(activeSession);
        }
    }

    public void shutdown() {
        stop();
    }

    public void handleWorldChange(MatchSession session, Player player) {
        if (session == null || !session.isRunning()) {
            return;
        }
        evaluatePlayer(session, player);
    }

    private void tick() {
        if (session == null || !session.isRunning()) {
            return;
        }
        for (UUID participantId : session.participants()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline()) {
                evaluatePlayer(session, player);
            }
        }
    }

    private void evaluatePlayer(MatchSession session, Player player) {
        Role role = roleService.getRole(player.getUniqueId());
        if (role == Role.NONE) {
            return;
        }
        Set<String> completedForPlayer = completedMilestones.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
        for (MilestoneDefinition definition : configManager.settings().milestones()) {
            if (!definition.enabled() || !definition.roles().contains(role) || completedForPlayer.contains(definition.key())) {
                continue;
            }
            MilestoneEvaluator evaluator = evaluators.get(definition.type());
            if (evaluator == null) {
                continue;
            }
            if (!evaluator.isSatisfied(definition, player)) {
                continue;
            }
            completedForPlayer.add(definition.key());
            analyticsRecorder.recordMilestone(
                    session.sessionId(),
                    player.getUniqueId(),
                    role,
                    Instant.now(),
                    player.getLocation().clone(),
                    definition.key(),
                    definition.title(),
                    evaluator.values(definition, player)
            );
        }
    }
}
