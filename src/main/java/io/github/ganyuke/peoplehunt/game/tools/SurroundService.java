package io.github.ganyuke.peoplehunt.game.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class SurroundService {
    private static final List<Material> DANGEROUS_GROUND = List.of(
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.POWDER_SNOW
    );

    public void surround(Player runner, List<Player> hunters, double minRadius, Double maxRadius) {
        if (runner == null || hunters.isEmpty() || runner.getWorld() == null) {
            return;
        }
        double effectiveMax = maxRadius == null ? minRadius : Math.max(maxRadius, minRadius);
        Location center = runner.getLocation();
        World world = center.getWorld();
        for (int i = 0; i < hunters.size(); i++) {
            double angle = (Math.PI * 2.0D * i) / hunters.size();
            Location target = findBestLocation(world, center, minRadius, effectiveMax, angle);
            hunters.get(i).teleport(target);
        }
    }

    private Location findBestLocation(World world, Location center, double minRadius, double maxRadius, double angle) {
        List<ScoredLocation> candidates = new ArrayList<>();
        int baseY = center.getBlockY();
        for (double radius = minRadius; radius <= maxRadius + 0.001; radius += 1.0) {
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            for (int yOffset = 0; yOffset <= 4; yOffset++) {
                Location sameY = new Location(world, x, baseY + yOffset, z);
                if (isSafeFeetLocation(sameY)) {
                    candidates.add(new ScoredLocation(sameY, radius, yOffset));
                }
                if (yOffset > 0) {
                    Location belowY = new Location(world, x, baseY - yOffset, z);
                    if (isSafeFeetLocation(belowY)) {
                        candidates.add(new ScoredLocation(belowY, radius, yOffset + 0.25));
                    }
                }
            }
            int highestY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            Location highest = new Location(world, x, highestY + 1.0, z);
            if (isSafeFeetLocation(highest)) {
                candidates.add(new ScoredLocation(highest, radius + 1.0, Math.abs(highest.getY() - baseY) + 1.0));
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(ScoredLocation::score))
                .map(ScoredLocation::location)
                .orElse(center.clone().add(0.5, 0.0, 0.5));
    }

    private boolean isSafeFeetLocation(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return feet.isPassable()
                && head.isPassable()
                && ground.getType().isSolid()
                && !DANGEROUS_GROUND.contains(ground.getType())
                && !DANGEROUS_GROUND.contains(feet.getType())
                && !DANGEROUS_GROUND.contains(head.getType())
                && verticalDrop(ground, 6) < 4;
    }

    private int verticalDrop(Block startingBlock, int maxScan) {
        int drop = 0;
        Block current = startingBlock;
        while (drop < maxScan && current.getType().isAir()) {
            current = current.getRelative(0, -1, 0);
            drop++;
        }
        return drop;
    }

    private record ScoredLocation(Location location, double radiusDistance, double yPenalty) {
        private double score() {
            return radiusDistance + (yPenalty * 2.0);
        }
    }
}
