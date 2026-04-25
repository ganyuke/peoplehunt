package io.github.ganyuke.peoplehunt.game.match;

import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Hold context for a primed match (started with /manhunt prime)
 */
public record PrimeContext(
        Location initialRunnerLocation,
        boolean keepPlayersFull,
        Set<UUID> participantIds,
        long primedAtEpochMillis
) {
    public static PrimeContext create(Location initialRunnerLocation, boolean keepPlayersFull, Set<UUID> participantIds) {
        return new PrimeContext(initialRunnerLocation, keepPlayersFull, participantIds, System.currentTimeMillis());
    }
}
