package io.github.ganyuke.peoplehunt.game;

import org.bukkit.Location;
import org.bukkit.World;

public final class CoordinateUtil {
    private CoordinateUtil() {}

    public static Location convertFromSource(Location source, World.Environment sourceEnvironment) {
        if (source == null || source.getWorld() == null) {
            return null;
        }
        double factor = sourceEnvironment == World.Environment.NETHER ? 8.0 : 0.125;
        return new Location(
                source.getWorld(),
                source.getX() * factor,
                source.getY(),
                source.getZ() * factor,
                source.getYaw(),
                source.getPitch()
        );
    }

    public static ConvertedCoordinate convert(World.Environment sourceEnvironment, double x, double y, double z) {
        if (sourceEnvironment == World.Environment.NETHER) {
            return new ConvertedCoordinate(World.Environment.NORMAL, x * 8.0, y, z * 8.0);
        }
        if (sourceEnvironment == World.Environment.NORMAL) {
            return new ConvertedCoordinate(World.Environment.NETHER, x / 8.0, y, z / 8.0);
        }
        return new ConvertedCoordinate(sourceEnvironment, x, y, z);
    }

    public record ConvertedCoordinate(World.Environment targetEnvironment, double x, double y, double z) {}
}
