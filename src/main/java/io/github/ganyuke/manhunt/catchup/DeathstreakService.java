package io.github.ganyuke.manhunt.catchup;

import io.github.ganyuke.manhunt.config.PluginSettings;
import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.MatchManager;
import io.github.ganyuke.manhunt.game.MatchSession;
import io.github.ganyuke.manhunt.game.RoleService;
import io.github.ganyuke.manhunt.game.SafeLocationResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.World;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeathstreakService {
    private final ConfigManager configManager;
    private final RoleService roleService;
    private final SafeLocationResolver safeLocationResolver;
    private final Map<UUID, HunterState> states = new HashMap<>();
    private MatchSession session;
    private MatchManager matchManager;

    public DeathstreakService(ConfigManager configManager, RoleService roleService, SafeLocationResolver safeLocationResolver) {
        this.configManager = configManager;
        this.roleService = roleService;
        this.safeLocationResolver = safeLocationResolver;
    }

    public void setMatchManager(MatchManager matchManager) {
        this.matchManager = matchManager;
    }

    public void beginSession(MatchSession session) {
        this.session = session;
        this.states.clear();
    }

    public void endSession() {
        this.session = null;
        this.states.clear();
    }

    public void recordContribution(Player hunter, double finalDamage) {
        if (!isEnabled() || !roleService.isHunter(hunter.getUniqueId())) {
            return;
        }
        HunterState state = states.computeIfAbsent(hunter.getUniqueId(), ignored -> new HunterState());
        state.recentContribution += Math.max(0.0D, finalDamage);
        state.lastContributionAt = Instant.now();
    }

    public void handleHunterDeath(PlayerDeathEvent event) {
        if (!isEnabled() || session == null || !session.isRunning()) {
            return;
        }
        Player player = event.getEntity();
        if (!roleService.isHunter(player.getUniqueId())) {
            return;
        }
        HunterState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new HunterState());
        boolean meaningful = state.lastContributionAt != null
                && Duration.between(state.lastContributionAt, Instant.now()).getSeconds() <= configManager.settings().contributionWindowSeconds()
                && state.recentContribution >= configManager.settings().meaningfulDamageThreshold();
        if (meaningful) {
            state.consecutiveDeathsWithoutContribution = 0;
        } else {
            state.consecutiveDeathsWithoutContribution++;
        }
        state.recentContribution = 0.0D;
        state.lastContributionAt = null;

        PluginSettings.CatchupRule rule = highestRule(state.consecutiveDeathsWithoutContribution);
        if (rule == null) {
            state.pendingKitId = null;
            state.pendingEndRally = false;
            return;
        }
        if (rule.keepInventory()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
        state.pendingKitId = rule.kitId();
        state.pendingEndRally = rule.endRally();
    }

    public void applyRespawnLocation(PlayerRespawnEvent event) {
        if (!isEnabled() || session == null || !session.isRunning() || !roleService.isHunter(event.getPlayer().getUniqueId())) {
            return;
        }
        HunterState state = states.get(event.getPlayer().getUniqueId());
        if (state == null || !state.pendingEndRally || !configManager.settings().endRallyEnabled()) {
            return;
        }
        Player runner = Bukkit.getPlayer(session.runnerId());
        if (runner == null || runner.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        double angle = Math.toRadians(Math.floorMod(event.getPlayer().getUniqueId().hashCode(), 360));
        int radius = configManager.settings().endRallyRadius();
        Location desired = runner.getLocation().clone().add(radius * Math.cos(angle), 0.0D, radius * Math.sin(angle));
        Location safe = safeLocationResolver.resolveSafeStandingLocation(desired);
        Vector direction = runner.getLocation().toVector().subtract(safe.toVector());
        safe.setDirection(direction);
        event.setRespawnLocation(safe);
    }

    public void grantPendingKit(Player player) {
        if (!isEnabled()) {
            return;
        }
        HunterState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.pendingKitId != null) {
            List<PluginSettings.KitItem> kitItems = configManager.settings().kits().get(state.pendingKitId);
            if (kitItems != null) {
                for (PluginSettings.KitItem kitItem : kitItems) {
                    player.getInventory().addItem(new ItemStack(kitItem.material(), kitItem.amount()));
                }
            }
        }
        state.pendingKitId = null;
        state.pendingEndRally = false;
    }

    private PluginSettings.CatchupRule highestRule(int deathCount) {
        PluginSettings.CatchupRule result = null;
        for (PluginSettings.CatchupRule rule : configManager.settings().catchupRules()) {
            if (deathCount >= rule.deaths()) {
                result = rule;
            }
        }
        return result;
    }

    private boolean isEnabled() {
        return configManager.settings().catchupEnabled();
    }

    private static final class HunterState {
        private int consecutiveDeathsWithoutContribution;
        private double recentContribution;
        private Instant lastContributionAt;
        private String pendingKitId;
        private boolean pendingEndRally;
    }
}
