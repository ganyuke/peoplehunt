package io.github.ganyuke.peoplehunt.game.match;

import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

public class PrimeContext {
    public final Location initialRunnerLocation;
    public final boolean keepPlayersFull;
    public final Set<UUID> participantIds;
    public final long primedAtEpochMillis = System.currentTimeMillis();

    public PrimeContext(Location initialRunnerLocation, boolean keepPlayersFull, Set<UUID> participantIds) {
        this.initialRunnerLocation = initialRunnerLocation;
        this.keepPlayersFull = keepPlayersFull;
        this.participantIds = participantIds;
    }
}