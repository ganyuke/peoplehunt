package io.github.ganyuke.peoplehunt.util;

import io.github.ganyuke.peoplehunt.report.ReportModels;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class LocationUtil {
    private LocationUtil() {}

    public static ReportModels.LocationRecord toRecord(Location location) {
        if (location == null || location.getWorld() == null) {
            return new ReportModels.LocationRecord("unknown", 0.0, 0.0, 0.0, 0.0f, 0.0f);
        }
        return new ReportModels.LocationRecord(
                location.getWorld().getKey().asString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    public static ReportModels.SimplePoint toSimplePoint(Location location, long offsetMillis) {
        if (location == null || location.getWorld() == null) {
            return new ReportModels.SimplePoint("unknown", 0.0, 0.0, 0.0, offsetMillis);
        }
        return new ReportModels.SimplePoint(
                location.getWorld().getKey().asString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                offsetMillis
        );
    }

    public static Location fromRecord(ReportModels.LocationRecord record) {
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(record.world());
        World world = key == null ? null : Bukkit.getWorld(key.getKey());
        if (world == null) {
            return null;
        }
        return new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch());
    }

    public static boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
