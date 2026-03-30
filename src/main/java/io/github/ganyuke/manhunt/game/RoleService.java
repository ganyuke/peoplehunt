package io.github.ganyuke.manhunt.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class RoleService {
    private UUID runnerId;
    private final Set<UUID> hunterIds = new LinkedHashSet<>();

    public UUID getRunnerId() {
        return runnerId;
    }

    public void setRunner(UUID runnerId) {
        this.runnerId = runnerId;
        this.hunterIds.remove(runnerId);
    }

    public boolean hasRunner() {
        return runnerId != null;
    }

    public Set<UUID> getHunterIds() {
        return Set.copyOf(hunterIds);
    }

    public boolean hasHunters() {
        return !hunterIds.isEmpty();
    }

    public void addHunter(UUID hunterId) {
        if (hunterId.equals(runnerId)) {
            return;
        }
        hunterIds.add(hunterId);
    }

    public void removeHunter(UUID hunterId) {
        hunterIds.remove(hunterId);
    }

    public void autoAssignHunters() {
        hunterIds.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getUniqueId().equals(runnerId)) {
                hunterIds.add(player.getUniqueId());
            }
        }
    }

    public Role getRole(UUID playerId) {
        if (runnerId != null && runnerId.equals(playerId)) {
            return Role.RUNNER;
        }
        if (hunterIds.contains(playerId)) {
            return Role.HUNTER;
        }
        return Role.NONE;
    }

    public boolean isRunner(UUID playerId) {
        return runnerId != null && runnerId.equals(playerId);
    }

    public boolean isHunter(UUID playerId) {
        return hunterIds.contains(playerId);
    }

    public boolean isParticipant(UUID playerId) {
        return getRole(playerId) != Role.NONE;
    }
}
