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
     * Plugin compasses should never be dropped on death. Respawn/rejoin code may call
     * CompassService#giveCompass again, but that method is duplicate-safe.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(compassService::isPluginCompass);
    }
}
