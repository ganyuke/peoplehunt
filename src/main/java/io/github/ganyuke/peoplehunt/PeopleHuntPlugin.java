package io.github.ganyuke.peoplehunt;

import com.google.gson.Gson;
import io.github.ganyuke.peoplehunt.command.CompassCommand;
import io.github.ganyuke.peoplehunt.command.CoordinateCommand;
import io.github.ganyuke.peoplehunt.command.PeopleHuntCommand;
import io.github.ganyuke.peoplehunt.command.WhereWasCommand;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
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

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class PeopleHuntPlugin extends JavaPlugin {
    private Gson gson;
    private PeopleHuntConfig peopleHuntConfig;
    private final Persistence persistence = new Persistence();
    private final Services services = new Services();

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            peopleHuntConfig = PeopleHuntConfig.from(getConfig());
            gson = JsonUtil.gson();

            Path dataPath = initializeDataDirectories();
            loadPersistence(dataPath);
            createServices();
            wireServices();
            registerListeners();
            registerCommands();
            startServices();
            reigsterTicking();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to enable PeopleHunt", exception);
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
        return dataPath;
    }

    private void loadPersistence(Path dataPath) throws IOException {
        persistence.stateStore = new PersistentStateStore(dataPath.resolve("state/state.json"), gson);
        persistence.stateData = persistence.stateStore.load();

        services.kitService = new KitService(dataPath.resolve("state/kits.json"), gson);
        services.kitService.load();

        persistence.whereWasStore = new WhereWasStore(dataPath.resolve("state/wherewas.json"), gson);
        persistence.whereWasStore.load();

        services.reportService = new ReportService(dataPath.resolve("reports"), gson);
        services.reportService.loadIndex();

        services.viewerAssets = new ViewerAssets(this);
    }

    private void createServices() {
        services.compassService = new CompassService(this, peopleHuntConfig);
        services.surroundService = new SurroundService();
        services.tickService = new MatchTickService(this, peopleHuntConfig, services.reportService);
    }

    private void wireServices() {
        services.matchManager = new MatchManager(
                this,
                peopleHuntConfig,
                persistence.stateStore,
                persistence.stateData,
                services.kitService,
                services.compassService,
                services.reportService,
                services.surroundService,
                services.tickService
        );

        services.tickService.setMatchManager(services.matchManager);

        services.movementService = new MatchMovementService(services.matchManager, peopleHuntConfig);
        services.compassService.setTargetProvider(services.movementService);

        services.attributionManager = new AttributionManager(services.matchManager, services.reportService);
    }

    private void registerListeners() {
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
                        services.reportService
                ),
                this
        );
        pluginManager.registerEvents(
                new GameplayListener(
                        peopleHuntConfig,
                        services.matchManager,
                        services.reportService,
                        services.attributionManager
                ),
                this
        );
        pluginManager.registerEvents(new CompassDeathFilterListener(services.compassService), this);
    }

    private void registerCommands() {
        PeopleHuntCommand peopleHuntCommand = new PeopleHuntCommand(
                services.matchManager,
                services.compassService,
                services.kitService,
                services.reportService,
                services.viewerAssets
        );

        register("peoplehunt", peopleHuntCommand);
        register("compass", new CompassCommand(services.compassService));
        register("coordinate", new CoordinateCommand());
        register("wherewas", new WhereWasCommand(persistence.whereWasStore));
    }

    private void reigsterTicking() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, services.attributionManager::tick, 1L, 1L);
    }

    private void startServices() throws Exception {
        services.compassService.start();

        if (peopleHuntConfig.webEnabled()) {
            services.webServer = new EmbeddedWebServer(
                    services.reportService,
                    services.viewerAssets,
                    gson,
                    peopleHuntConfig.webPort()
            );
            services.webServer.start();
        }
    }

    private void stopActiveMatchSafely() {
        try {
            if (services.matchManager != null) {
                services.matchManager.stopInconclusive();
            }
        } catch (Exception exception) {
            getLogger().warning("Failed to finish active match cleanly: " + exception.getMessage());
        }
    }

    private void saveStateSafely() {
        try {
            if (persistence.stateStore != null && persistence.stateData != null) {
                persistence.stateStore.save(persistence.stateData);
            }
            if (services.kitService != null) {
                services.kitService.save();
            }
            if (persistence.whereWasStore != null) {
                persistence.whereWasStore.save();
            }
            if (services.reportService != null) {
                services.reportService.saveIndex();
            }
        } catch (IOException exception) {
            getLogger().warning("Failed to save plugin state: " + exception.getMessage());
        }
    }

    private void stopServices() {
        if (services.compassService != null) {
            services.compassService.stop();
        }
        if (services.webServer != null) {
            services.webServer.stop();
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
    }
}