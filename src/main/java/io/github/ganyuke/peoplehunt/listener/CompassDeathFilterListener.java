package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class CompassDeathFilterListener implements Listener {

    private final CompassService compassService;

    public CompassDeathFilterListener(CompassService compassService) {
        this.compassService = compassService;
    }

    /**
     * Handle removal of the plugin's hunter compass on player
     * death to avoid duplication.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(compassService::isPluginCompass);
    }
}