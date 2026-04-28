package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.SessionConfig;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Hold context for a primed match (started with /manhunt prime).
 */
public record PrimeContext(
        Location initialRunnerLocation,
        @Nullable Boolean keepPlayersFullOverride,
        Set<UUID> participantIds,
        long primedAtEpochMillis
) {
    public static PrimeContext create(Location initialRunnerLocation, @Nullable Boolean keepPlayersFullOverride, Set<UUID> participantIds) {
        return new PrimeContext(initialRunnerLocation, keepPlayersFullOverride, participantIds, System.currentTimeMillis());
    }

    public boolean keepPlayersFull(SessionConfig sessionConfig) {
        return keepPlayersFullOverride != null ? keepPlayersFullOverride : sessionConfig.primeKeepPlayersFull();
    }
}
