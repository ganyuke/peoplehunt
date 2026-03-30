package io.github.ganyuke.peoplehunt;

import com.google.gson.Gson;
import io.github.ganyuke.peoplehunt.command.CompassCommand;
import io.github.ganyuke.peoplehunt.command.CoordinateCommand;
import io.github.ganyuke.peoplehunt.command.PeopleHuntCommand;
import io.github.ganyuke.peoplehunt.command.WhereWasCommand;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.CompassService;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.MatchManager;
import io.github.ganyuke.peoplehunt.game.PersistentStateStore;
import io.github.ganyuke.peoplehunt.game.SurroundService;
import io.github.ganyuke.peoplehunt.game.WhereWasStore;
import io.github.ganyuke.peoplehunt.listener.GameplayListener;
import io.github.ganyuke.peoplehunt.report.EmbeddedWebServer;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.report.ViewerAssets;
import io.github.ganyuke.peoplehunt.util.JsonUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PeopleHuntPlugin extends JavaPlugin {
    private Gson gson;
    private PeopleHuntConfig peopleHuntConfig;
    private PersistentStateStore stateStore;
    private PersistentStateStore.StateData stateData;
    private KitService kitService;
    private WhereWasStore whereWasStore;
    private ReportService reportService;
    private ViewerAssets viewerAssets;
    private CompassService compassService;
    private MatchManager matchManager;
    private EmbeddedWebServer webServer;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            peopleHuntConfig = PeopleHuntConfig.from(getConfig());
            gson = JsonUtil.gson();
            Path dataPath = getDataFolder().toPath();
            Files.createDirectories(dataPath);
            Files.createDirectories(dataPath.resolve("reports"));
            Files.createDirectories(dataPath.resolve("state"));

            stateStore = new PersistentStateStore(dataPath.resolve("state/state.json"), gson);
            stateData = stateStore.load();
            kitService = new KitService(dataPath.resolve("state/kits.json"), gson);
            kitService.load();
            whereWasStore = new WhereWasStore(dataPath.resolve("state/wherewas.json"), gson);
            whereWasStore.load();
            reportService = new ReportService(dataPath.resolve("reports"), gson);
            reportService.loadIndex();
            viewerAssets = new ViewerAssets(this);
            compassService = new CompassService(this, peopleHuntConfig);
            matchManager = new MatchManager(this, peopleHuntConfig, stateStore, stateData, kitService, compassService, reportService, new SurroundService());
            compassService.setTargetProvider(matchManager);

            registerCommands();
            getServer().getPluginManager().registerEvents(new GameplayListener(this, peopleHuntConfig, matchManager, reportService), this);
            compassService.start();

            if (peopleHuntConfig.webEnabled()) {
                webServer = new EmbeddedWebServer(reportService, viewerAssets, gson, peopleHuntConfig.webPort());
                webServer.start();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to enable PeopleHunt", exception);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (matchManager != null) {
                matchManager.stopInconclusive();
            }
        } catch (Exception exception) {
            getLogger().warning("Failed to finish active match cleanly: " + exception.getMessage());
        }
        try {
            if (stateStore != null && stateData != null) {
                stateStore.save(stateData);
            }
            if (kitService != null) {
                kitService.save();
            }
            if (whereWasStore != null) {
                whereWasStore.save();
            }
            if (reportService != null) {
                reportService.saveIndex();
            }
        } catch (IOException exception) {
            getLogger().warning("Failed to save plugin state: " + exception.getMessage());
        }
        if (compassService != null) {
            compassService.stop();
        }
        if (webServer != null) {
            webServer.stop();
        }
    }

    private void registerCommands() {
        PeopleHuntCommand peopleHuntCommand = new PeopleHuntCommand(matchManager, compassService, kitService, reportService, viewerAssets);
        register("peoplehunt", peopleHuntCommand);
        register("compass", new CompassCommand(compassService));
        register("coordinate", new CoordinateCommand());
        register("wherewas", new WhereWasCommand(whereWasStore));
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
}
