package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.core.ConfigManager;

import java.util.UUID;

public final class FreezeService {
    private final ConfigManager configManager;
    private final RoleService roleService;
    private volatile boolean frozen;

    public FreezeService(ConfigManager configManager, RoleService roleService) {
        this.configManager = configManager;
        this.roleService = roleService;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen && configManager.settings().freezeDuringPrime();
    }

    public boolean isFrozen() {
        return frozen;
    }

    public boolean shouldFreeze(UUID playerId) {
        return frozen && roleService.isParticipant(playerId);
    }
}
