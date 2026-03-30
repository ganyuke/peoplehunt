package io.github.ganyuke.manhunt.analytics;

import io.github.ganyuke.manhunt.game.Role;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Set;

public record MilestoneDefinition(
        String key,
        String title,
        boolean enabled,
        MilestoneType type,
        Set<Role> roles,
        Set<Material> materials,
        int amount,
        World.Environment environment
) {
}
