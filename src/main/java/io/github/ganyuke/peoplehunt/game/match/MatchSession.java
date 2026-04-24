package io.github.ganyuke.peoplehunt.game.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.Role;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

public class MatchSession {
    // --- Core Match Data ---
    public final UUID reportId;
    public final long startedAtEpochMillis;
    public final UUID runnerUuid;
    public final Set<UUID> hunterIds;
    public final Set<UUID> spectatorIds;
    public String activeKitId;
    public KeepInventoryMode keepInventoryMode;
    public boolean endInventoryControlActivated = false;
    public long nextElapsedAnnouncementMinutes = 1L;

    // --- Player State ---
    public final Map<UUID, Role> roles = new LinkedHashMap<>();
    public final Map<UUID, Integer> lifeIndex = new HashMap<>();
    public final Map<UUID, DeathstreakState> deathstreaks = new HashMap<>();
    public final Map<UUID, List<ItemStack>> pendingRespawnRestore = new HashMap<>();
    public final Map<UUID, Location> pendingPortalPrompt = new HashMap<>();
    public final Map<UUID, Location> lastKnownRunnerLocations = new HashMap<>();
    public final Set<UUID> diedInEnd = new LinkedHashSet<>();
    public Location currentRunnerLocation;
    public Location lastRunnerOverworldEndPortal;

    // --- GameplayListener Ephemeral State ---
    public final Map<UUID, ProjectileAttribution> trackedProjectiles = new HashMap<>();
    public final Map<UUID, Attribution> recentVictimAttribution = new HashMap<>();
    public final Map<BlockKey, Attribution> lavaSources = new HashMap<>();
    public final List<Attribution> recentExplosiveHazards = new ArrayList<>();
    public final Map<UUID, Set<String>> milestones = new HashMap<>();
    public boolean globalFirstBloodRecorded = false;

    public MatchSession(UUID reportId, long startedAtEpochMillis, UUID runnerUuid, Set<UUID> hunterIds, Set<UUID> spectatorIds, String activeKitId, KeepInventoryMode keepInventoryMode) {
        this.reportId = reportId;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.runnerUuid = runnerUuid;
        this.hunterIds = new LinkedHashSet<>(hunterIds);
        this.spectatorIds = new LinkedHashSet<>(spectatorIds);
        this.activeKitId = activeKitId;
        this.keepInventoryMode = keepInventoryMode;
    }

    public void cleanupOldHazards() {
        long cutoff = System.currentTimeMillis() - 30000L;
        lavaSources.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis() < cutoff);
        recentExplosiveHazards.removeIf(attribution -> attribution.createdAtEpochMillis() < cutoff);
        recentVictimAttribution.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis() < cutoff);
    }

    public static class DeathstreakState {
        public int streakDeaths = 0;
        public double damageThisLife = 0.0;
    }

    public record ProjectileAttribution(UUID playerUuid, String playerName, String weapon) {}

    public record Attribution(UUID playerUuid, String playerName, String weapon, UUID projectileUuid, Location location, long createdAtEpochMillis) {
        public Attribution withLocation(Location location) {
            return new Attribution(playerUuid, playerName, weapon, projectileUuid, location, createdAtEpochMillis);
        }
    }

    public record BlockKey(UUID worldUuid, int x, int y, int z) {
        public static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}