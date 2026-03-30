package io.github.ganyuke.manhunt.core;

import io.github.ganyuke.manhunt.config.PluginSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ConfigManager {
    private final JavaPlugin plugin;
    private volatile PluginSettings settings;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        saveResourceIfMissing("milestones.yml");
        reload();
    }

    public synchronized PluginSettings reload() {
        plugin.reloadConfig();
        File milestoneFile = new File(plugin.getDataFolder(), "milestones.yml");
        YamlConfiguration milestones = YamlConfiguration.loadConfiguration(milestoneFile);
        this.settings = PluginSettings.from(plugin.getConfig(), milestones);
        return this.settings;
    }

    public PluginSettings settings() {
        return this.settings;
    }

    private void saveResourceIfMissing(String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }
    }
}
