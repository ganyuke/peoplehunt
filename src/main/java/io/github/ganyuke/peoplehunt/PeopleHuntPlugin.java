package io.github.ganyuke.peoplehunt;

import com.google.gson.Gson;
import io.github.ganyuke.peoplehunt.command.CompassCommand;
import io.github.ganyuke.peoplehunt.command.CoordinateCommand;
import io.github.ganyuke.peoplehunt.command.PeopleHuntCommand;
import io.github.ganyuke.peoplehunt.command.WhereWasCommand;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.SessionConfig;
import io.github.ganyuke.peoplehunt.config.SessionConfigLoader;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.PersistentStateStore;
import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.match.AttributionManager;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchMovementService;
import io.github.ganyuke.peoplehunt.game.match.MatchTickService;
import io.github.ganyuke.peoplehunt.game.tools.SurroundService;
import io.github.ganyuke.peoplehunt.game.tools.WhereWasStore;
import io.github.ganyuke.peoplehunt.listener.CompassDeathFilterListener;
import io.github.ganyuke.peoplehunt.listener.GameplayListener;
import io.github.ganyuke.peoplehunt.listener.MatchLifecycleListener;
import io.github.ganyuke.peoplehunt.report.EmbeddedWebServer;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.report.ViewerAssets;
import io.github.ganyuke.peoplehunt.util.JsonUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>The plugin boot sequence is intentionally split into small private methods so startup order is
 * obvious: load persisted state, construct services, wire circular dependencies, register Bukkit
 * hooks, then start recurring/background services.
 */
public final class PeopleHuntPlugin extends JavaPlugin {
    private Gson gson;
    private PeopleHuntConfig peopleHuntConfig;
    private final Persistence persistence = new Persistence();
    private final Services services = new Services();

    @Override
    public void onEnable() {
        try {
            // Startup order matters because later services depend on both configuration and
            // already-loaded persisted state.
            saveDefaultConfig();
            peopleHuntConfig = PeopleHuntConfig.from(getConfig());
            gson = JsonUtil.gson();

            Path dataPath = initializeDataDirectories();
            loadSessionConfig(dataPath);
            loadPersistence(dataPath);
            createServices();
            wireServices();
            registerListeners();
            registerCommands();
            startServices();
            registerTicking();
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to enable PeopleHunt", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopActiveMatchSafely();
        saveStateSafely();
        stopServices();
    }

    private Path initializeDataDirectories() throws IOException {
        Path dataPath = getDataFolder().toPath();
        Files.createDirectories(dataPath);
        Files.createDirectories(dataPath.resolve("reports"));
        Files.createDirectories(dataPath.resolve("state"));
        getLogger().info("PeopleHunt data directory ready at " + dataPath);
        getLogger().info("Report directory ready at " + dataPath.resolve("reports"));
        getLogger().info("State directory ready at " + dataPath.resolve("state"));
        return dataPath;
    }

    private void loadSessionConfig(Path dataPath) throws IOException {
        services.sessionConfigLoader = new SessionConfigLoader(this);
        services.sessionConfigFile = dataPath.resolve("session-config.yml").toFile();
        if (!services.sessionConfigFile.exists()) {
            try {
                services.sessionConfigLoader.generateDefault(services.sessionConfigFile);
            } catch (IOException exception) {
                getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Failed to generate session-config.yml at " + services.sessionConfigFile.getAbsolutePath(),
                        exception
                );
                throw exception;
            }
            getLogger().info("session-config.yml generated. If you had custom session settings in config.yml, please migrate them to session-config.yml.");
        }
        services.sessionConfig = services.sessionConfigLoader.load(services.sessionConfigFile);
    }

    private void loadPersistence(Path dataPath) throws IOException {
        // Long-lived state is stored separately from per-report output so operator-facing settings
        // survive restarts while after-action reports remain append-only.
        persistence.stateStore = new PersistentStateStore(dataPath.resolve("state/state.json"), gson);
        getLogger().info("Loading plugin state from " + persistence.stateStore.path());
        persistence.stateData = persistence.stateStore.load();

        services.kitService = new KitService(dataPath.resolve("state/kits.json"), gson);
        getLogger().info("Loading saved kits from " + services.kitService.path());
        services.kitService.load();

        persistence.whereWasStore = new WhereWasStore(dataPath.resolve("state/wherewas.json"), gson);
        getLogger().info("Loading wherewas coordinates from " + persistence.whereWasStore.path());
        persistence.whereWasStore.load();

        services.reportService = new ReportService(
                this,
                dataPath.resolve("reports"),
                gson,
                getLogger(),
                peopleHuntConfig.reportingPathFlushMaxBufferedPoints(),
                peopleHuntConfig.reportingPathFlushMaxBufferedSeconds() * 1000L
        );
        getLogger().info("Loading report index from " + dataPath.resolve("reports/index.json"));
        getLogger().info("Verifying SQLite driver and report storage startup probe...");
        services.reportService.verifySqliteRuntime();
        services.reportService.loadIndex();

        services.viewerAssets = new ViewerAssets(this);
    }

    private void createServices() {
        // Pure service construction happens before wiring so each object stays easy to reason about.
        services.compassService = new CompassService(this, peopleHuntConfig);
        services.surroundService = new SurroundService();
        services.tickService = new MatchTickService(this, peopleHuntConfig, services.reportService);
    }

    private void wireServices() {
        // MatchManager is the central coordinator for active match state. Other services mostly
        // project or react to that state.
        services.matchManager = new MatchManager(
                this,
                peopleHuntConfig,
                persistence.stateStore,
                persistence.stateData,
                services.kitService,
                services.compassService,
                services.reportService,
                services.surroundService,
                services.tickService,
                services.sessionConfig
        );

        services.tickService.setMatchManager(services.matchManager);
        services.reportService.setRuntimeWarningSink(services.matchManager::warnOperators);

        services.movementService = new MatchMovementService(services.matchManager);
        services.compassService.setTargetProvider(services.movementService);

        services.attributionManager = new AttributionManager(peopleHuntConfig, services.matchManager, services.reportService);
    }

    private void registerListeners() {
        // Event listeners are split by concern: lifecycle, gameplay/reporting, movement/compass,
        // and death-drop cleanup.
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(services.movementService, this);
        pluginManager.registerEvents(
                new MatchLifecycleListener(
                        this,
                        peopleHuntConfig,
                        services.matchManager,
                        services.attributionManager,
                        services.kitService,
                        services.compassService,
                         services.reportService,
                        services.tickService
                ),
                this
        );
        pluginManager.registerEvents(
                new GameplayListener(
                        peopleHuntConfig,
                        services.matchManager,
                        services.reportService,
                        services.attributionManager,
                        this,
                        services.tickService
                ),
                this
        );
        pluginManager.registerEvents(new CompassDeathFilterListener(services.compassService), this);
    }

    private void registerCommands() {
        // Command handlers are registered explicitly rather than by reflection so missing commands
        // fail fast during enable.
        PeopleHuntCommand peopleHuntCommand = new PeopleHuntCommand(
                services.matchManager,
                services.compassService,
                services.kitService,
                services.reportService,
                services.viewerAssets,
                services.sessionConfigLoader,
                services.sessionConfigFile
        );

        register("peoplehunt", peopleHuntCommand);
        register("compass", new CompassCommand(services.compassService));
        register("coordinate", new CoordinateCommand());
        register("wherewas", new WhereWasCommand(persistence.whereWasStore));
    }

    private void registerTicking() {
        // Attribution state needs a per-tick heartbeat for projectile path sampling and short-lived
        // hazard cleanup.
        services.attributionTask = getServer().getScheduler().runTaskTimer(this, services.attributionManager::tick, 1L, 1L);
    }

    private void startServices() throws Exception {
        services.compassService.start();

        if (peopleHuntConfig.webEnabled()) {
            getLogger().info("Starting embedded web server on " + peopleHuntConfig.webBindAddress() + ":" + peopleHuntConfig.webPort());
            services.webServer = new EmbeddedWebServer(
                    services.reportService,
                    services.viewerAssets,
                    gson,
                    getLogger(),
                    peopleHuntConfig.webBindAddress(),
                    peopleHuntConfig.webPort()
            );
            services.webServer.start();
            getLogger().info("Embedded web server started on " + peopleHuntConfig.webBindAddress() + ":" + peopleHuntConfig.webPort());
        } else {
            getLogger().info("Skipping embedded web server because it is disabled in config.");
        }
    }

    private void stopActiveMatchSafely() {
        try {
            if (services.matchManager != null) {
                // A server shutdown does not currently support resumable matches, so any active
                // match is closed as inconclusive before state is saved.
                services.matchManager.stopInconclusiveBlocking();
            }
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to finish active match cleanly.", exception);
        }
    }

    private void saveStateSafely() {
        try {
            // Persist mutable operator state after the match is stopped so saved snapshots reflect
            // the final session outcome.
            if (persistence.stateStore != null && persistence.stateData != null) {
                getLogger().info("Writing plugin state to disk at " + persistence.stateStore.path());
                persistence.stateStore.save(persistence.stateData);
            }
            if (services.kitService != null) {
                getLogger().info("Writing saved kits to disk at " + services.kitService.path());
                services.kitService.save();
            }
            if (persistence.whereWasStore != null) {
                getLogger().info("Writing wherewas coordinates to disk at " + persistence.whereWasStore.path());
                persistence.whereWasStore.save();
            }
            if (services.reportService != null) {
                getLogger().info("Writing report index to disk at " + dataPath().resolve("reports/index.json"));
                services.reportService.saveIndex();
            }
        } catch (IOException exception) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to save plugin state.", exception);
        }
    }

    private Path dataPath() {
        return getDataFolder().toPath();
    }

    private void stopServices() {
        if (services.attributionTask != null) {
            services.attributionTask.cancel();
            services.attributionTask = null;
        }
        if (services.compassService != null) {
            services.compassService.stop();
        }
        if (services.webServer != null) {
            getLogger().info("Stopping embedded web server.");
            services.webServer.stop();
        }
        if (services.reportService != null) {
            services.reportService.shutdown();
        }
        if (services.kitService != null) {
            services.kitService.shutdown();
        }
        if (persistence.whereWasStore != null) {
            persistence.whereWasStore.shutdown();
        }
        if (persistence.stateStore != null) {
            persistence.stateStore.shutdown();
        }
    }

    private void register(String name, Object handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Missing command: " + name);
        }
        if (handler instanceof org.bukkit.command.CommandExecutor executor) {
            command.setExecutor(executor);
        }
        if (handler instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    private static final class Persistence {
        private PersistentStateStore stateStore;
        private PersistentStateStore.StateData stateData;
        private WhereWasStore whereWasStore;
    }

    private static final class Services {
        private KitService kitService;
        private ReportService reportService;
        private ViewerAssets viewerAssets;
        private CompassService compassService;
        private SurroundService surroundService;
        private MatchTickService tickService;
        private MatchManager matchManager;
        private MatchMovementService movementService;
        private EmbeddedWebServer webServer;
        private AttributionManager attributionManager;
        private BukkitTask attributionTask;
        private SessionConfigLoader sessionConfigLoader;
        private java.io.File sessionConfigFile;
        private SessionConfig sessionConfig;
    }
}
