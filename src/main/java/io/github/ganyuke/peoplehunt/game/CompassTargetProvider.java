package io.github.ganyuke.peoplehunt.game;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface CompassTargetProvider {
    UUID selectedRunnerUuid();

    Location resolveCompassTarget(Player holder);
}
