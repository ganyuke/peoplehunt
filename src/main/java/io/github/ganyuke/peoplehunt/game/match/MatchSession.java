package io.github.ganyuke.peoplehunt.game.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Deque;
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

/**
 * In-memory runtime state for one active match.
 *
 * <p>The session intentionally mixes long-lived match facts (runner, hunters, report id) with
 * short-lived attribution caches used by event listeners. None of this is restored after restart;
 * a shutdown ends the session inconclusively instead.
 */
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
    // Used for KIT inventory control so selected kit items survive a hunter death even though the
    // actual Bukkit death drops are still allowed to happen.
    public final Map<UUID, List<ItemStack>> pendingRespawnRestore = new HashMap<>();
    // One-shot clickable prompt offered to hunters who die in the End and respawn back in the
    // Overworld, allowing them to return near the runner's most recent End portal.
    public final Map<UUID, Location> pendingPortalPrompt = new HashMap<>();
    public final Map<UUID, Location> lastKnownRunnerLocations = new HashMap<>();
    public final Map<UUID, String> lastSampleGameModes = new HashMap<>();
    public final Map<UUID, String> lastSampleWorlds = new HashMap<>();
    public final Set<UUID> pendingTeleportPathFlags = new LinkedHashSet<>();
    public final Map<UUID, PendingPortalArrival> pendingPortalArrivals = new HashMap<>();
    public final Set<UUID> diedInEnd = new LinkedHashSet<>();
    public final Map<UUID, Deque<RollbackState>> rollbackBuffer = new HashMap<>();
    public Location currentRunnerLocation;
    public Location lastRunnerOverworldEndPortal;

    // --- GameplayListener Ephemeral State ---
    public final Map<UUID, ProjectileAttribution> trackedProjectiles = new HashMap<>();
    public final Map<UUID, HostileProjectileAttribution> trackedHostileProjectiles = new HashMap<>();
    public final Map<UUID, TrackedMobState> trackedMobs = new HashMap<>();
    public final Set<UUID> seenEndCrystals = new LinkedHashSet<>();
    public final Map<UUID, Attribution> recentVictimAttribution = new HashMap<>();
    public final Map<BlockKey, Attribution> lavaSources = new HashMap<>();
    public final List<Attribution> recentExplosiveHazards = new ArrayList<>();
    public final Map<UUID, Set<String>> milestones = new HashMap<>();
    // GameplayListener resolves death attribution before MatchLifecycleListener updates game rules,
    // so the attribution is cached here to keep reporting and deathstreak accounting consistent.
    public final Map<UUID, Attribution> lastDeathAttribution = new HashMap<>();
    public boolean globalFirstBloodRecorded = false;
    public boolean globalFirstHunterDeathRecorded = false;

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
        // Hazard and attribution caches are deliberately short-lived so delayed damage can still be
        // credited while stale world state does not leak across unrelated fights.
        long cutoff = System.currentTimeMillis() - 30000L;
        lavaSources.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis() < cutoff);
        recentExplosiveHazards.removeIf(attribution -> attribution.createdAtEpochMillis() < cutoff);
        recentVictimAttribution.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis() < cutoff);
    }

    public static class DeathstreakState {
        // Counts only hunter deaths attributed to the runner. Damage dealt by the hunter no longer
        // resets or advances deathstreak state.
        public int streakDeaths = 0;
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


    public record HostileProjectileAttribution(String shooterEntityType, UUID targetPlayerUuid, String targetPlayerName, String weapon) {}

    public record PendingPortalArrival(String causeName, long recordedAtEpochMillis) {}

    public static final class TrackedMobState {
        public final UUID entityUuid;
        public final String entityType;
        public UUID targetPlayerUuid;
        public String targetPlayerName;
        public long lastSeenEpochMillis;

        public TrackedMobState(UUID entityUuid, String entityType, UUID targetPlayerUuid, String targetPlayerName, long lastSeenEpochMillis) {
            this.entityUuid = entityUuid;
            this.entityType = entityType;
            this.targetPlayerUuid = targetPlayerUuid;
            this.targetPlayerName = targetPlayerName;
            this.lastSeenEpochMillis = lastSeenEpochMillis;
        }
    }

    public record RollbackState(
            long capturedAtEpochMillis,
            Location location,
            org.bukkit.GameMode gameMode,
            double health,
            double maxHealth,
            double absorption,
            int food,
            float saturation,
            int level,
            int totalExperience,
            List<ItemStack> contents,
            ItemStack helmet,
            ItemStack chestplate,
            ItemStack leggings,
            ItemStack boots,
            ItemStack offHand,
            List<org.bukkit.potion.PotionEffect> effects
    ) {}

}
