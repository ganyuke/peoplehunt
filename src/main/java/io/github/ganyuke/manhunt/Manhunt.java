package io.github.ganyuke.manhunt;

import io.github.ganyuke.manhunt.analytics.AnalyticsRecorder;
import io.github.ganyuke.manhunt.analytics.AsyncJsonAnalyticsRecorder;
import io.github.ganyuke.manhunt.analytics.HealthSampler;
import io.github.ganyuke.manhunt.analytics.LifeTracker;
import io.github.ganyuke.manhunt.analytics.MilestoneService;
import io.github.ganyuke.manhunt.analytics.NoopAnalyticsRecorder;
import io.github.ganyuke.manhunt.analytics.PathSampler;
import io.github.ganyuke.manhunt.analytics.SessionExporter;
import io.github.ganyuke.manhunt.catchup.DeathstreakService;
import io.github.ganyuke.manhunt.command.ManhuntCommand;
import io.github.ganyuke.manhunt.core.ConfigManager;
import io.github.ganyuke.manhunt.game.CompassService;
import io.github.ganyuke.manhunt.game.MatchManager;
import io.github.ganyuke.manhunt.game.MatchStatsService;
import io.github.ganyuke.manhunt.game.RoleService;
import io.github.ganyuke.manhunt.game.SafeLocationResolver;
import io.github.ganyuke.manhunt.game.SurroundService;
import io.github.ganyuke.manhunt.game.TimerService;
import io.github.ganyuke.manhunt.listeners.CombatAnalyticsListener;
import io.github.ganyuke.manhunt.listeners.MatchLifecycleListener;
import io.github.ganyuke.manhunt.listeners.PathSamplingListener;
import io.github.ganyuke.manhunt.map.MapPublisher;
import io.github.ganyuke.manhunt.map.StaticWebMapPublisher;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class Manhunt extends JavaPlugin {
    private ConfigManager configManager;
    private RoleService roleService;
    private TimerService timerService;
    private CompassService compassService;
    private SafeLocationResolver safeLocationResolver;
    private SurroundService surroundService;
    private AnalyticsRecorder analyticsRecorder;
    private LifeTracker lifeTracker;
    private PathSampler pathSampler;
    private HealthSampler healthSampler;
    private MilestoneService milestoneService;
    private DeathstreakService deathstreakService;
    private MatchStatsService matchStatsService;
    private SessionExporter sessionExporter;
    private MapPublisher mapPublisher;
    private MatchManager matchManager;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.roleService = new RoleService();
        this.timerService = new TimerService(this, configManager);
        this.compassService = new CompassService(this, configManager);
        this.safeLocationResolver = new SafeLocationResolver(configManager);
        this.surroundService = new SurroundService(this, configManager, safeLocationResolver);
        this.analyticsRecorder = configManager.settings().analyticsEnabled() ? new AsyncJsonAnalyticsRecorder(this, configManager) : new NoopAnalyticsRecorder();
        this.lifeTracker = new LifeTracker(roleService, analyticsRecorder);
        this.pathSampler = new PathSampler(configManager, analyticsRecorder, lifeTracker, roleService);
        this.healthSampler = new HealthSampler(this, configManager, analyticsRecorder, roleService, lifeTracker);
        this.milestoneService = new MilestoneService(this, configManager, analyticsRecorder, roleService);
        this.deathstreakService = new DeathstreakService(configManager, roleService, safeLocationResolver);
        this.matchStatsService = new MatchStatsService();
        this.sessionExporter = new SessionExporter(this, analyticsRecorder, configManager);
        this.mapPublisher = new StaticWebMapPublisher(this, configManager);
        this.matchManager = new MatchManager(
                this,
                configManager,
                roleService,
                timerService,
                compassService,
                analyticsRecorder,
                lifeTracker,
                pathSampler,
                milestoneService,
                healthSampler,
                deathstreakService,
                matchStatsService,
                mapPublisher
        );
        this.deathstreakService.setMatchManager(matchManager);

        registerCommands();
        registerListeners();
        getLogger().info("Manhunt enabled.");
    }

    @Override
    public void onDisable() {
        if (matchManager != null) {
            matchManager.shutdown();
        }
        if (analyticsRecorder != null) {
            analyticsRecorder.close();
        }
    }

    public void reloadPluginConfiguration() {
        configManager.reload();
        timerService.restartIfRunning(matchManager.getCurrentSession());
        compassService.restartIfRunning(matchManager.getCurrentSession());
        healthSampler.restartIfRunning(matchManager.getCurrentSession());
        milestoneService.restartIfRunning(matchManager.getCurrentSession());
    }

    private void registerCommands() {
        PluginCommand manhuntCommand = getCommand("manhunt");
        if (manhuntCommand == null) {
            throw new IllegalStateException("Command /manhunt is missing from plugin.yml");
        }
        ManhuntCommand handler = new ManhuntCommand(this, configManager, roleService, matchManager, surroundService, compassService, sessionExporter);
        manhuntCommand.setExecutor(handler);
        manhuntCommand.setTabCompleter(handler);
    }

    private void registerListeners() {
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new CombatAnalyticsListener(matchManager, roleService, analyticsRecorder, lifeTracker, deathstreakService), this);
        pluginManager.registerEvents(new PathSamplingListener(matchManager, pathSampler), this);
        pluginManager.registerEvents(new MatchLifecycleListener(this, configManager, matchManager, lifeTracker, compassService, pathSampler, milestoneService, deathstreakService), this);
    }
}
