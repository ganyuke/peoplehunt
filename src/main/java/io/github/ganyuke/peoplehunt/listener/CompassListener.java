package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class CompassListener implements Listener {

    private final CompassService compassService;

    public CompassListener(CompassService compassService) {
        this.compassService = compassService;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Automatically checks all dropped items and removes them if they are the plugin compass
        event.getDrops().removeIf(compassService::isPluginCompass);
    }
}