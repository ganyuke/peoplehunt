package io.github.ganyuke.manhunt.game;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class MatchSession {
    private final UUID sessionId;
    private final UUID runnerId;
    private final Set<UUID> hunterIds;
    private SessionState state = SessionState.IDLE;
    private Instant primedAt;
    private Instant startedAt;
    private Instant endedAt;
    private VictoryType victoryType = VictoryType.NONE;
    private String endReason = "Not finished";

    public MatchSession(UUID sessionId, UUID runnerId, Collection<UUID> hunterIds) {
        this.sessionId = sessionId;
        this.runnerId = runnerId;
        this.hunterIds = new LinkedHashSet<>(hunterIds);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID runnerId() {
        return runnerId;
    }

    public synchronized Set<UUID> hunterIds() {
        return Set.copyOf(hunterIds);
    }

    public synchronized int hunterCount() {
        return hunterIds.size();
    }

    public synchronized void replaceHunters(Collection<UUID> hunters) {
        hunterIds.clear();
        if (hunters == null) {
            return;
        }
        for (UUID hunterId : hunters) {
            if (hunterId != null && !hunterId.equals(runnerId)) {
                hunterIds.add(hunterId);
            }
        }
    }

    public synchronized void addHunter(UUID hunterId) {
        if (hunterId != null && !hunterId.equals(runnerId)) {
            hunterIds.add(hunterId);
        }
    }

    public synchronized void removeHunter(UUID hunterId) {
        hunterIds.remove(hunterId);
    }

    public synchronized Set<UUID> participants() {
        LinkedHashSet<UUID> participants = new LinkedHashSet<>(hunterIds);
        participants.add(runnerId);
        return participants;
    }

    public synchronized boolean isParticipant(UUID playerId) {
        return runnerId.equals(playerId) || hunterIds.contains(playerId);
    }

    public SessionState state() {
        return state;
    }

    public Instant primedAt() {
        return primedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public VictoryType victoryType() {
        return victoryType;
    }

    public String endReason() {
        return endReason;
    }

    public boolean isPrimed() {
        return state == SessionState.PRIMED;
    }

    public boolean isRunning() {
        return state == SessionState.RUNNING;
    }

    public boolean hasStarted() {
        return startedAt != null;
    }

    public boolean hasEnded() {
        return state == SessionState.ENDED;
    }

    public void markPrimed(Instant now) {
        this.state = SessionState.PRIMED;
        this.primedAt = now;
    }

    public void markStarted(Instant now) {
        this.state = SessionState.RUNNING;
        this.startedAt = now;
    }

    public void markEnded(Instant now, VictoryType victoryType, String reason) {
        this.state = SessionState.ENDED;
        this.endedAt = now;
        this.victoryType = victoryType;
        this.endReason = reason;
    }
}
