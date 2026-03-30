package io.github.ganyuke.manhunt.game;

import io.github.ganyuke.manhunt.core.ConfigManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

import java.util.Set;

public final class SafeLocationResolver {
    private static final Set<Material> HAZARDS = Set.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CACTUS,
            Material.MAGMA_BLOCK,
            Material.WATER
    );

    private final ConfigManager configManager;

    public SafeLocationResolver(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Location resolveSafeStandingLocation(Location desired) {
        World world = desired.getWorld();
        if (world == null) {
            return desired;
        }
        int baseX = desired.getBlockX();
        int baseY = Math.max(world.getMinHeight() + 1, desired.getBlockY());
        int baseZ = desired.getBlockZ();
        int radius = Math.max(0, configManager.settings().surroundSafeSearchRadius());
        int vertical = Math.max(0, configManager.settings().surroundSafeSearchVertical());

        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dy = -vertical; dy <= vertical; dy++) {
                        int x = baseX + dx;
                        int y = baseY + dy;
                        int z = baseZ + dz;
                        if (isSafe(world, x, y, z)) {
                            return centered(world, x, y, z, desired.getYaw(), desired.getPitch());
                        }
                    }
                }
            }
        }

        Location fallback = world.getHighestBlockAt(baseX, baseZ).getLocation().add(0.5D, 1.0D, 0.5D);
        if (isSafe(world, fallback.getBlockX(), fallback.getBlockY(), fallback.getBlockZ())) {
            fallback.setYaw(desired.getYaw());
            fallback.setPitch(desired.getPitch());
            return fallback;
        }
        return desired;
    }

    private boolean isSafe(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 2) {
            return false;
        }
        Location feetLocation = new Location(world, x + 0.5D, y, z + 0.5D);
        if (!insideBorder(feetLocation)) {
            return false;
        }
        Block below = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        if (!below.getType().isSolid()) {
            return false;
        }
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        return !HAZARDS.contains(below.getType()) && !HAZARDS.contains(feet.getType()) && !HAZARDS.contains(head.getType());
    }

    private boolean insideBorder(Location location) {
        WorldBorder border = location.getWorld().getWorldBorder();
        double half = border.getSize() / 2.0D;
        Location center = border.getCenter();
        return Math.abs(location.getX() - center.getX()) <= half && Math.abs(location.getZ() - center.getZ()) <= half;
    }

    private Location centered(World world, int x, int y, int z, float yaw, float pitch) {
        Location location = new Location(world, x + 0.5D, y, z + 0.5D, yaw, pitch);
        return location;
    }
}
