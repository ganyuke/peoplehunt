package io.github.ganyuke.manhunt.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class RoleService {
    private UUID runnerId;
    private final Set<UUID> manualHunters = new LinkedHashSet<>();
    private final Set<UUID> excludedAutoHunters = new LinkedHashSet<>();
    private final Set<UUID> activeHunters = new LinkedHashSet<>();
    private boolean sessionHuntersLocked;

    public UUID getRunnerId() {
        return runnerId;
    }

    public void setRunner(UUID runnerId) {
        this.runnerId = runnerId;
        this.manualHunters.remove(runnerId);
        this.excludedAutoHunters.remove(runnerId);
        this.activeHunters.remove(runnerId);
    }

    public boolean hasRunner() {
        return runnerId != null;
    }

    public Set<UUID> getHunterIds() {
        return sessionHuntersLocked ? Set.copyOf(activeHunters) : previewHunterIds();
    }

    public Set<UUID> previewHunterIds() {
        LinkedHashSet<UUID> hunters = new LinkedHashSet<>();
        if (runnerId == null) {
            return Set.of();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (playerId.equals(runnerId) || excludedAutoHunters.contains(playerId)) {
                continue;
            }
            hunters.add(playerId);
        }
        for (UUID hunterId : manualHunters) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null && hunter.isOnline() && !hunterId.equals(runnerId)) {
                hunters.add(hunterId);
            }
        }
        return Set.copyOf(hunters);
    }

    public Set<UUID> resolveHuntersForMatchStart() {
        return previewHunterIds();
    }

    public boolean hasHunters() {
        return !getHunterIds().isEmpty();
    }

    public void addHunter(UUID hunterId) {
        if (hunterId == null || hunterId.equals(runnerId)) {
            return;
        }
        manualHunters.add(hunterId);
        excludedAutoHunters.remove(hunterId);
        if (sessionHuntersLocked) {
            activeHunters.add(hunterId);
        }
    }

    public void addMidgameHunter(UUID hunterId) {
        if (hunterId == null || hunterId.equals(runnerId)) {
            return;
        }
        manualHunters.add(hunterId);
        excludedAutoHunters.remove(hunterId);
        activeHunters.add(hunterId);
        sessionHuntersLocked = true;
    }

    public void removeHunter(UUID hunterId) {
        if (hunterId == null) {
            return;
        }
        manualHunters.remove(hunterId);
        if (sessionHuntersLocked) {
            activeHunters.remove(hunterId);
            return;
        }
        if (!hunterId.equals(runnerId)) {
            excludedAutoHunters.add(hunterId);
        }
    }

    public void autoAssignHunters() {
        manualHunters.clear();
        excludedAutoHunters.clear();
    }

    public void lockHuntersForSession(Collection<UUID> hunters) {
        activeHunters.clear();
        if (hunters != null) {
            for (UUID hunterId : hunters) {
                if (hunterId != null && !hunterId.equals(runnerId)) {
                    activeHunters.add(hunterId);
                }
            }
        }
        sessionHuntersLocked = true;
    }

    public void unlockSessionHunters() {
        activeHunters.clear();
        sessionHuntersLocked = false;
    }

    public boolean isSessionHuntersLocked() {
        return sessionHuntersLocked;
    }

    public Role getRole(UUID playerId) {
        if (runnerId != null && runnerId.equals(playerId)) {
            return Role.RUNNER;
        }
        if (getHunterIds().contains(playerId)) {
            return Role.HUNTER;
        }
        return Role.NONE;
    }

    public boolean isRunner(UUID playerId) {
        return runnerId != null && runnerId.equals(playerId);
    }

    public boolean isHunter(UUID playerId) {
        return getHunterIds().contains(playerId);
    }

    public boolean isParticipant(UUID playerId) {
        return getRole(playerId) != Role.NONE;
    }
}
