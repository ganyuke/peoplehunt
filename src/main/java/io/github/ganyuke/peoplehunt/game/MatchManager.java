package io.github.ganyuke.peoplehunt.game;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.report.ReportModels;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.report.ReportStorageFormat;
import io.github.ganyuke.peoplehunt.util.ItemUtil;
import io.github.ganyuke.peoplehunt.util.Text;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

public final class MatchManager implements CompassTargetProvider {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final PersistentStateStore stateStore;
    private final PersistentStateStore.StateData stateData;
    private final KitService kitService;
    private final CompassService compassService;
    private final ReportService reportService;
    private final SurroundService surroundService;

    private PrimeContext primeContext;
    private ActiveMatch activeMatch;
    private int pathTaskId = -1;
    private int elapsedTaskId = -1;
    private int primeTaskId = -1;

    public MatchManager(
            JavaPlugin plugin,
            PeopleHuntConfig config,
            PersistentStateStore stateStore,
            PersistentStateStore.StateData stateData,
            KitService kitService,
            CompassService compassService,
            ReportService reportService,
            SurroundService surroundService
    ) {
        this.plugin = plugin;
        this.config = config;
        this.stateStore = stateStore;
        this.stateData = stateData;
        this.kitService = kitService;
        this.compassService = compassService;
        this.reportService = reportService;
        this.surroundService = surroundService;
    }

    public boolean hasActiveMatch() {
        return activeMatch != null;
    }

    public boolean isPrimeActive() {
        return primeContext != null;
    }

    public UUID selectedRunnerUuid() {
        return stateData.runnerUuid;
    }

    public Player selectedRunnerPlayer() {
        return stateData.runnerUuid == null ? null : Bukkit.getPlayer(stateData.runnerUuid);
    }

    public Set<UUID> explicitHunters() {
        return Collections.unmodifiableSet(stateData.explicitHunters);
    }

    public KeepInventoryMode keepInventoryMode() {
        return stateData.keepInventoryMode;
    }

    public void setKeepInventoryMode(KeepInventoryMode mode) {
        KeepInventoryMode resolved = mode == null ? KeepInventoryMode.NONE : mode;
        stateData.keepInventoryMode = resolved;
        if (activeMatch != null) {
            activeMatch.keepInventoryMode = resolved;
            reportService.updateSessionSettings(resolved, activeMatch.activeKitId);
        }
        persistQuietly();
    }

    public String activeKitId() {
        return stateData.activeKitId;
    }

    public void setActiveKitId(String activeKitId) {
        stateData.activeKitId = activeKitId;
        if (activeMatch != null) {
            activeMatch.activeKitId = activeKitId;
            reportService.updateSessionSettings(activeMatch.keepInventoryMode, activeKitId);
        }
        persistQuietly();
    }

    public boolean toggleRunner(UUID playerUuid) {
        ensureNotActive();
        boolean set;
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            stateData.runnerUuid = null;
            set = false;
        } else {
            stateData.runnerUuid = playerUuid;
            stateData.explicitHunters.remove(playerUuid);
            set = true;
        }
        touchSelections();
        return set;
    }

    public boolean toggleHunter(UUID playerUuid) {
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            return false;
        }
        boolean nowSelected;
        if (stateData.explicitHunters.contains(playerUuid)) {
            stateData.explicitHunters.remove(playerUuid);
            nowSelected = false;
        } else {
            stateData.explicitHunters.add(playerUuid);
            nowSelected = true;
        }
        touchSelections();
        if (activeMatch != null) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (nowSelected) {
                addHunterToActiveMatch(player);
            } else {
                removeHunterFromActiveMatch(playerUuid);
            }
        }
        return nowSelected;
    }

    public void prepare(boolean wipeEffects) {
        for (Player player : resolvePrepareParticipants()) {
            preparePlayer(player, wipeEffects);
        }
    }

    public void prime(Boolean keepPlayersFullOverride) {
        if (activeMatch != null) {
            throw new IllegalStateException("A match is already active.");
        }
        Player runner = requireOnlineRunner();
        Set<UUID> participants = resolveNextParticipantUuids(true);
        if (participants.isEmpty()) {
            participants.add(runner.getUniqueId());
        }
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
        }
        primeContext = new PrimeContext(runner.getLocation().clone(), keepPlayersFullOverride == null ? config.primeKeepPlayersFull() : keepPlayersFullOverride, participants);
        startPrimeTask();
    }

    public UUID startNow() throws IOException {
        if (activeMatch != null) {
            throw new IllegalStateException("A match is already active.");
        }
        Player runner = requireOnlineRunner();
        cancelPrimeInternal();

        Set<UUID> hunterIds = resolveNextHunterUuids();
        Set<UUID> spectatorIds = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!uuid.equals(runner.getUniqueId()) && !hunterIds.contains(uuid)) {
                spectatorIds.add(uuid);
            }
        }

        List<ReportService.ParticipantSeed> participants = new ArrayList<>();
        participants.add(new ReportService.ParticipantSeed(runner.getUniqueId(), runner.getName(), Role.RUNNER.name(), false, false));
        for (UUID hunterId : hunterIds) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null) {
                participants.add(new ReportService.ParticipantSeed(hunter.getUniqueId(), hunter.getName(), Role.HUNTER.name(), false, false));
            }
        }
        for (UUID spectatorId : spectatorIds) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null) {
                participants.add(new ReportService.ParticipantSeed(spectator.getUniqueId(), spectator.getName(), Role.SPECTATOR.name(), false, true));
            }
        }

        UUID reportId = reportService.startSession(
                runner.getUniqueId(),
                runner.getName(),
                stateData.keepInventoryMode,
                stateData.activeKitId,
                config.reportStorageFormat(),
                participants
        );
        activeMatch = new ActiveMatch(reportId, System.currentTimeMillis(), runner.getUniqueId(), hunterIds, spectatorIds, stateData.activeKitId, stateData.keepInventoryMode);
        activeMatch.nextElapsedAnnouncementMinutes = Math.max(1, config.elapsedAnnouncementMinutes());
        activeMatch.currentRunnerLocation = runner.getLocation().clone();
        activeMatch.lifeIndex.put(runner.getUniqueId(), 1);
        for (UUID hunterId : hunterIds) {
            activeMatch.lifeIndex.put(hunterId, 1);
            activeMatch.deathstreaks.put(hunterId, new DeathstreakState());
        }
        for (UUID spectatorId : spectatorIds) {
            activeMatch.lifeIndex.put(spectatorId, 1);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(runner.getUniqueId())) {
                activeMatch.roles.put(player.getUniqueId(), Role.RUNNER);
                player.setGameMode(GameMode.SURVIVAL);
            } else if (hunterIds.contains(player.getUniqueId())) {
                activeMatch.roles.put(player.getUniqueId(), Role.HUNTER);
                player.setGameMode(GameMode.SURVIVAL);
                compassService.giveCompass(List.of(player));
                if (config.applyKitOnStart() && stateData.activeKitId != null) {
                    kitService.applyMissingKit(player, stateData.activeKitId);
                }
            } else {
                activeMatch.roles.put(player.getUniqueId(), Role.SPECTATOR);
                if (config.autoSpectateNewJoins()) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            }
        }
        startRuntimeTasks();
        broadcast(Text.mm("<green>Manhunt started."));
        reportService.recordTimeline(runner.getUniqueId(), runner.getName(), "match", "Manhunt started");
        persistQuietly();
        return reportId;
    }

    public Optional<ReportService.FinishResult> stopInconclusive() throws IOException {
        if (activeMatch == null) {
            cancelPrimeInternal();
            return Optional.empty();
        }
        return finishMatch(MatchOutcome.INCONCLUSIVE);
    }

    public Optional<ReportService.FinishResult> endHunterVictory() throws IOException {
        if (activeMatch == null) {
            return Optional.empty();
        }
        return finishMatch(MatchOutcome.HUNTER_VICTORY);
    }

    public Optional<ReportService.FinishResult> endRunnerVictory() throws IOException {
        if (activeMatch == null) {
            return Optional.empty();
        }
        return finishMatch(MatchOutcome.RUNNER_VICTORY);
    }

    private Optional<ReportService.FinishResult> finishMatch(MatchOutcome outcome) throws IOException {
        stopRuntimeTasks();
        ActiveMatch finishing = activeMatch;
        activeMatch = null;
        Optional<ReportService.FinishResult> result = reportService.finish(outcome);
        broadcast(Text.mm("<green>Manhunt ended."));
        result.ifPresent(finish -> {
            Component stats = buildFinishedStatsComponent(finish.snapshot());
            broadcast(stats);
            stateData.lastStatusSnapshot = new PersistentStateStore.LastStatusSnapshot(
                    finish.indexEntry().reportId(),
                    stateData.selectionGeneration,
                    finish.indexEntry().startedAtEpochMillis(),
                    finish.indexEntry().endedAtEpochMillis(),
                    outcome,
                    finish.indexEntry().runnerUuid(),
                    finish.indexEntry().runnerName(),
                    Text.formatTimestamp(finish.indexEntry().startedAtEpochMillis()),
                    Text.formatDurationMillis(finish.indexEntry().endedAtEpochMillis() - finish.indexEntry().startedAtEpochMillis()),
                    buildFinishedStatsMiniMessage(finish.snapshot())
            );
            persistQuietly();
        });
        return result;
    }

    public void noteRunnerMove(Location from, Location to) {
        if (primeContext != null && stateData.runnerUuid != null && selectedRunnerPlayer() != null) {
            if (movedPosition(from, to)) {
                try {
                    startNow();
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to start match after prime", exception);
                }
            }
        }
        if (activeMatch != null && Objects.equals(stateData.runnerUuid, selectedRunnerUuid()) && to != null) {
            activeMatch.currentRunnerLocation = to.clone();
        }
    }

    public void noteRunnerPortal(Location from, Location to) {
        if (activeMatch == null || from == null) {
            return;
        }
        activeMatch.lastKnownRunnerLocations.put(from.getWorld().getUID(), from.clone());
        if (from.getWorld().getEnvironment() == World.Environment.NORMAL && to != null && to.getWorld() != null && to.getWorld().getEnvironment() == World.Environment.THE_END) {
            activeMatch.lastRunnerOverworldEndPortal = from.clone();
        }
        if (to != null) {
            activeMatch.currentRunnerLocation = to.clone();
        }
    }

    public void noteHunterPlayerDamage(Player hunter, double damage) {
        if (activeMatch == null || hunter == null || !isHunter(hunter.getUniqueId())) {
            return;
        }
        DeathstreakState state = activeMatch.deathstreaks.computeIfAbsent(hunter.getUniqueId(), ignored -> new DeathstreakState());
        state.damageThisLife += damage;
        DeathstreakTier activeTier = activeDeathstreakTier(hunter.getUniqueId());
        if (activeTier != null && state.damageThisLife >= activeTier.damageToReset()) {
            state.streakDeaths = 0;
            state.damageThisLife = 0.0;
            reportService.recordTimeline(hunter.getUniqueId(), hunter.getName(), "deathstreak", "deathstreak reset by dealing enough damage");
        }
    }

    public void noteHunterDeath(Player hunter) {
        if (activeMatch == null || hunter == null || !isHunter(hunter.getUniqueId())) {
            return;
        }
        activeMatch.lifeIndex.compute(hunter.getUniqueId(), (ignored, current) -> (current == null ? 1 : current) + 1);
        DeathstreakState state = activeMatch.deathstreaks.computeIfAbsent(hunter.getUniqueId(), ignored -> new DeathstreakState());
        state.streakDeaths++;
        state.damageThisLife = 0.0;
    }

    public void noteRunnerDeath(Player runner) {
        if (activeMatch == null || runner == null || !isRunner(runner.getUniqueId())) {
            return;
        }
        activeMatch.lifeIndex.compute(runner.getUniqueId(), (ignored, current) -> (current == null ? 1 : current) + 1);
    }

    public void notePlayerDiedInEnd(Player player) {
        if (activeMatch == null || player == null) {
            return;
        }
        activeMatch.diedInEnd.add(player.getUniqueId());
    }

    public void onRespawn(Player player) {
        if (activeMatch == null || player == null || !isParticipant(player.getUniqueId())) {
            return;
        }
        Role role = roleOf(player.getUniqueId());
        if (role == Role.HUNTER) {
            player.setGameMode(GameMode.SURVIVAL);
            List<ItemStack> restore = activeMatch.pendingRespawnRestore.remove(player.getUniqueId());
            if (restore != null) {
                for (ItemStack item : restore) {
                    ItemUtil.giveOrDrop(player, item);
                }
            }
            if (stateData.activeKitId != null) {
                kitService.applyMissingKit(player, stateData.activeKitId);
            }
            applyDeathstreakRewards(player);
            compassService.giveCompass(List.of(player));
            maybeOfferEndPortalTeleport(player);
        } else if (role == Role.RUNNER) {
            player.setGameMode(GameMode.SURVIVAL);
        } else {
            if (config.autoSpectateNewJoins()) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    public void onJoin(Player player) {
        if (activeMatch == null || player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (activeMatch.roles.containsKey(uuid)) {
            Role role = activeMatch.roles.get(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (role) {
                    case RUNNER, HUNTER -> player.setGameMode(GameMode.SURVIVAL);
                    case SPECTATOR -> {
                        if (config.autoSpectateNewJoins()) {
                            player.setGameMode(GameMode.SPECTATOR);
                        }
                    }
                }
                if (role == Role.HUNTER) {
                    compassService.giveCompass(List.of(player));
                }
            });
            return;
        }
        activeMatch.roles.put(uuid, Role.SPECTATOR);
        activeMatch.spectatorIds.add(uuid);
        activeMatch.lifeIndex.put(uuid, 1);
        reportService.registerParticipant(uuid, player.getName(), Role.SPECTATOR.name(), true, true);
        reportService.recordTimeline(uuid, player.getName(), "participant", "joined as spectator");
        if (config.autoSpectateNewJoins()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.setGameMode(GameMode.SPECTATOR));
        }
    }

    public boolean isRunner(UUID uuid) {
        return activeMatch != null ? activeMatch.roles.get(uuid) == Role.RUNNER : Objects.equals(stateData.runnerUuid, uuid);
    }

    public boolean isHunter(UUID uuid) {
        return activeMatch != null ? activeMatch.roles.get(uuid) == Role.HUNTER : stateData.explicitHunters.contains(uuid);
    }

    public boolean isParticipant(UUID uuid) {
        return activeMatch != null && activeMatch.roles.containsKey(uuid);
    }

    public Role roleOf(UUID uuid) {
        return activeMatch == null ? null : activeMatch.roles.get(uuid);
    }

    public int lifeIndex(UUID uuid) {
        if (activeMatch == null) {
            return 1;
        }
        return activeMatch.lifeIndex.getOrDefault(uuid, 1);
    }

    public KeepInventoryMode effectiveKeepInventoryMode(UUID hunterUuid) {
        if (activeMatch == null || hunterUuid == null) {
            return stateData.keepInventoryMode;
        }
        DeathstreakTier tier = activeDeathstreakTier(hunterUuid);
        if (tier != null) {
            return tier.keepInventoryMode().resolve(activeMatch.keepInventoryMode);
        }
        return activeMatch.keepInventoryMode;
    }

    public List<ItemStack> captureKitPreservedDrops(List<ItemStack> drops) {
        if (stateData.activeKitId == null) {
            return List.of();
        }
        return ItemUtil.removeUpToMatches(drops, kitService.templateItems(stateData.activeKitId));
    }

    public void stashRespawnRestore(UUID uuid, List<ItemStack> items) {
        if (activeMatch == null || uuid == null || items == null || items.isEmpty()) {
            return;
        }
        activeMatch.pendingRespawnRestore.computeIfAbsent(uuid, ignored -> new ArrayList<>()).addAll(ItemUtil.cloneAll(items));
    }

    public void recordPathSample() {
        if (activeMatch == null) {
            return;
        }
        for (Map.Entry<UUID, Role> entry : activeMatch.roles.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            reportService.recordPath(player.getUniqueId(), player.getName(), lifeIndex(player.getUniqueId()), player.getLocation(), (float) player.getHealth(), player.getFoodLevel(), player.getSaturation());
        }
    }

    public Collection<Player> onlineHunters() {
        if (activeMatch == null) {
            return resolveNextHunterUuids().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        }
        return activeMatch.hunterIds.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
    }

    public void surroundHunters(double minRadius, Double maxRadius) {
        Player runner = requireOnlineRunner();
        surroundService.surround(runner, new ArrayList<>(onlineHunters()), minRadius, maxRadius);
    }

    public Optional<Location> consumeEndPortalPrompt(UUID uuid) {
        if (activeMatch == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeMatch.pendingPortalPrompt.remove(uuid));
    }

    @Override
    public Location resolveCompassTarget(Player holder) {
        UUID runnerUuid = stateData.runnerUuid;
        if (runnerUuid == null) {
            return null;
        }
        Player onlineRunner = Bukkit.getPlayer(runnerUuid);
        Location current = null;
        if (onlineRunner != null) {
            current = onlineRunner.getLocation();
        } else if (activeMatch != null) {
            current = activeMatch.currentRunnerLocation == null ? null : activeMatch.currentRunnerLocation.clone();
        }
        if (current == null) {
            return null;
        }
        if (holder.getWorld().getUID().equals(current.getWorld().getUID())) {
            return current;
        }
        if (config.compassDimensionMode() == CompassDimensionMode.LAST_KNOWN && activeMatch != null) {
            Location lastKnown = activeMatch.lastKnownRunnerLocations.get(holder.getWorld().getUID());
            if (lastKnown != null) {
                return lastKnown.clone();
            }
        }
        return null;
    }

    public Component buildStatusComponent() {
        if (activeMatch != null) {
            return Text.lines(List.of(
                    Component.text("PeopleHunt status", NamedTextColor.GOLD),
                    Component.text("State: active", NamedTextColor.YELLOW),
                    Component.text("Runner: " + nameOf(stateData.runnerUuid), NamedTextColor.GRAY),
                    Component.text("Hunters: " + namesOf(activeMatch.hunterIds), NamedTextColor.GRAY),
                    Component.text("Spectators: " + namesOf(activeMatch.spectatorIds), NamedTextColor.GRAY),
                    Component.text("Started: " + Text.formatTimestamp(activeMatch.startedAtEpochMillis), NamedTextColor.GRAY),
                    Component.text("Elapsed: " + Text.formatDurationMillis(System.currentTimeMillis() - activeMatch.startedAtEpochMillis), NamedTextColor.GRAY),
                    Component.text("Keep inventory: " + activeMatch.keepInventoryMode, NamedTextColor.GRAY),
                    Component.text("Kit: " + (activeMatch.activeKitId == null ? "none" : activeMatch.activeKitId), NamedTextColor.GRAY)
            ));
        }
        if (primeContext != null) {
            return Text.lines(List.of(
                    Component.text("PeopleHunt status", NamedTextColor.GOLD),
                    Component.text("State: primed", NamedTextColor.YELLOW),
                    Component.text("Runner: " + nameOf(stateData.runnerUuid), NamedTextColor.GRAY),
                    Component.text("Hunters: " + namesOf(resolveNextHunterUuids()), NamedTextColor.GRAY),
                    Component.text("Primed at: " + Text.formatTimestamp(primeContext.primedAtEpochMillis), NamedTextColor.GRAY),
                    Component.text("Keep full: " + primeContext.keepPlayersFull, NamedTextColor.GRAY)
            ));
        }
        if (stateData.lastStatusSnapshot != null && stateData.lastStatusSnapshot.selectionGeneration() == stateData.selectionGeneration) {
            return Text.mm(stateData.lastStatusSnapshot.summaryText());
        }
        return Text.lines(List.of(
                Component.text("PeopleHunt status", NamedTextColor.GOLD),
                Component.text("State: idle", NamedTextColor.YELLOW),
                Component.text("Pending runner: " + nameOf(stateData.runnerUuid), NamedTextColor.GRAY),
                Component.text("Pending hunters: " + (stateData.explicitHunters.isEmpty() ? "all online except runner" : namesOf(stateData.explicitHunters)), NamedTextColor.GRAY),
                Component.text("Keep inventory: " + stateData.keepInventoryMode, NamedTextColor.GRAY),
                Component.text("Kit: " + (stateData.activeKitId == null ? "none" : stateData.activeKitId), NamedTextColor.GRAY)
        ));
    }

    public void addHunterToActiveMatch(Player player) {
        if (activeMatch == null || player == null || player.getUniqueId().equals(activeMatch.runnerUuid)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        activeMatch.hunterIds.add(uuid);
        activeMatch.spectatorIds.remove(uuid);
        activeMatch.roles.put(uuid, Role.HUNTER);
        activeMatch.lifeIndex.putIfAbsent(uuid, 1);
        activeMatch.deathstreaks.putIfAbsent(uuid, new DeathstreakState());
        player.setGameMode(GameMode.SURVIVAL);
        compassService.giveCompass(List.of(player));
        if (stateData.activeKitId != null) {
            kitService.applyMissingKit(player, stateData.activeKitId);
        }
        reportService.registerParticipant(uuid, player.getName(), Role.HUNTER.name(), true, false);
        reportService.recordTimeline(uuid, player.getName(), "participant", "joined as hunter");
    }

    public void removeHunterFromActiveMatch(UUID uuid) {
        if (activeMatch == null || uuid == null || !activeMatch.hunterIds.contains(uuid)) {
            return;
        }
        activeMatch.hunterIds.remove(uuid);
        activeMatch.spectatorIds.add(uuid);
        activeMatch.roles.put(uuid, Role.SPECTATOR);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && config.autoSpectateNewJoins()) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        if (player != null) {
            reportService.registerParticipant(uuid, player.getName(), Role.SPECTATOR.name(), true, false);
            reportService.recordTimeline(uuid, player.getName(), "participant", "changed to spectator");
        }
    }

    private void maybeOfferEndPortalTeleport(Player player) {
        if (activeMatch == null || !config.endPortalRespawnEnabled()) {
            return;
        }
        if (!activeMatch.diedInEnd.remove(player.getUniqueId())) {
            return;
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        Location portal = activeMatch.lastRunnerOverworldEndPortal;
        if (portal == null || portal.getWorld() == null || !portal.getWorld().getUID().equals(player.getWorld().getUID())) {
            return;
        }
        if (player.getLocation().distance(portal) <= config.endPortalRespawnRadius()) {
            return;
        }
        activeMatch.pendingPortalPrompt.put(player.getUniqueId(), portal.clone());
        Component prompt = Component.text("Teleport to the runner's last End Portal", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/peoplehunt portal"))
                .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + Text.coord(portal.getX(), portal.getY(), portal.getZ()), NamedTextColor.GRAY)));
        player.sendMessage(prompt);
    }

    private void applyDeathstreakRewards(Player player) {
        if (!config.deathstreaksEnabled() || activeMatch == null) {
            return;
        }
        DeathstreakTier tier = activeDeathstreakTier(player.getUniqueId());
        if (tier == null) {
            return;
        }
        player.setFoodLevel(Math.max(player.getFoodLevel(), tier.saturationBoost().foodLevel()));
        player.setSaturation(Math.max(player.getSaturation(), tier.saturationBoost().saturation()));
        for (var potion : tier.potionGrants()) {
            player.addPotionEffect(new PotionEffect(potion.type(), potion.durationSeconds() * 20, potion.amplifier(), true, true, true));
        }
        for (var item : tier.itemGrants()) {
            int existing = ItemUtil.countSimilar(player.getInventory(), new ItemStack(item.material(), item.amount()));
            if (existing >= item.amount()) {
                continue;
            }
            ItemStack stack = new ItemStack(item.material(), item.amount() - existing);
            ItemUtil.giveOrDrop(player, stack);
        }
    }

    private DeathstreakTier activeDeathstreakTier(UUID hunterUuid) {
        if (activeMatch == null) {
            return null;
        }
        DeathstreakState state = activeMatch.deathstreaks.get(hunterUuid);
        if (state == null) {
            return null;
        }
        return config.deathstreakTiers().stream()
                .filter(tier -> state.streakDeaths >= tier.deaths())
                .max(Comparator.comparingInt(DeathstreakTier::deaths))
                .orElse(null);
    }

    private void startRuntimeTasks() {
        stopRuntimeTasks();
        pathTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::recordPathSample, config.playerPathSampleIntervalTicks(), config.playerPathSampleIntervalTicks());
        elapsedTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (activeMatch == null) {
                return;
            }
            long elapsedMinutes = (System.currentTimeMillis() - activeMatch.startedAtEpochMillis) / 60000L;
            if (elapsedMinutes >= activeMatch.nextElapsedAnnouncementMinutes) {
                broadcast(Text.mm("<yellow>Manhunt time elapsed: " + elapsedMinutes + " minutes"));
                activeMatch.nextElapsedAnnouncementMinutes += Math.max(1, config.elapsedAnnouncementMinutes());
            }
        }, 20L, 20L);
    }

    private void stopRuntimeTasks() {
        if (pathTaskId != -1) {
            Bukkit.getScheduler().cancelTask(pathTaskId);
            pathTaskId = -1;
        }
        if (elapsedTaskId != -1) {
            Bukkit.getScheduler().cancelTask(elapsedTaskId);
            elapsedTaskId = -1;
        }
    }

    private void startPrimeTask() {
        cancelPrimeTask();
        primeTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (primeContext == null) {
                return;
            }
            if (!primeContext.keepPlayersFull) {
                return;
            }
            for (UUID uuid : primeContext.participantIds) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    continue;
                }
                player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
            }
        }, 20L, 20L);
    }

    private void cancelPrimeTask() {
        if (primeTaskId != -1) {
            Bukkit.getScheduler().cancelTask(primeTaskId);
            primeTaskId = -1;
        }
    }

    private void cancelPrimeInternal() {
        primeContext = null;
        cancelPrimeTask();
    }

    private Set<UUID> resolveNextParticipantUuids(boolean includeRunner) {
        Set<UUID> out = new LinkedHashSet<>();
        if (includeRunner && stateData.runnerUuid != null) {
            out.add(stateData.runnerUuid);
        }
        out.addAll(resolveNextHunterUuids());
        return out;
    }

    private Set<UUID> resolveNextHunterUuids() {
        if (!stateData.explicitHunters.isEmpty()) {
            return stateData.explicitHunters.stream()
                    .filter(uuid -> !Objects.equals(uuid, stateData.runnerUuid))
                    .filter(uuid -> Bukkit.getPlayer(uuid) != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .filter(uuid -> !Objects.equals(uuid, stateData.runnerUuid))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Player> resolvePrepareParticipants() {
        Set<Player> players = new LinkedHashSet<>();
        if (stateData.runnerUuid != null) {
            Player runner = Bukkit.getPlayer(stateData.runnerUuid);
            if (runner != null) {
                players.add(runner);
            }
        }
        if (!stateData.explicitHunters.isEmpty()) {
            for (UUID uuid : stateData.explicitHunters) {
                Player hunter = Bukkit.getPlayer(uuid);
                if (hunter != null) {
                    players.add(hunter);
                }
            }
        } else {
            players.addAll(Bukkit.getOnlinePlayers());
        }
        return new ArrayList<>(players);
    }

    private void preparePlayer(Player player, boolean wipeEffects) {
        if (wipeEffects) {
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        }
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0f);
    }

    private void ensureNotActive() {
        if (activeMatch != null || primeContext != null) {
            throw new IllegalStateException("Stop or finish the current session first.");
        }
    }

    private Player requireOnlineRunner() {
        if (stateData.runnerUuid == null) {
            throw new IllegalStateException("A runner must be selected first.");
        }
        Player runner = Bukkit.getPlayer(stateData.runnerUuid);
        if (runner == null) {
            throw new IllegalStateException("The selected runner must be online.");
        }
        return runner;
    }

    private void touchSelections() {
        stateData.selectionGeneration++;
        stateData.lastStatusSnapshot = null;
        persistQuietly();
    }

    private boolean movedPosition(Location from, Location to) {
        if (from == null || to == null) {
            return false;
        }
        return from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ();
    }

    private void broadcast(Component component) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(component);
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    private String namesOf(Collection<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return "none";
        }
        return uuids.stream().map(this::nameOf).collect(Collectors.joining(", "));
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "unset";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        return Bukkit.getOfflinePlayer(uuid).getName() == null ? uuid.toString() : Bukkit.getOfflinePlayer(uuid).getName();
    }

    private void persistQuietly() {
        try {
            stateStore.save(stateData);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to persist PeopleHunt state: " + exception.getMessage());
        }
    }

    private Component buildFinishedStatsComponent(ReportModels.ViewerSnapshot snapshot) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Match statistics", NamedTextColor.GOLD));
        lines.add(Component.text("Victory: " + snapshot.metadata().outcome(), NamedTextColor.YELLOW));
        lines.add(Component.text("Runner: " + snapshot.metadata().runnerName(), NamedTextColor.GRAY));
        lines.add(Component.text("Match time: " + Text.formatDurationMillis(snapshot.metadata().endedAtEpochMillis() - snapshot.metadata().startedAtEpochMillis()), NamedTextColor.GRAY));
        for (ReportModels.ParticipantStats stat : snapshot.stats()) {
            String name = nameOf(stat.uuid());
            lines.add(Component.text(name + " - deaths: " + stat.deaths() + ", player kills: " + stat.playerKills(), NamedTextColor.GRAY));
        }
        return Text.lines(lines);
    }

    private String buildFinishedStatsMiniMessage(ReportModels.ViewerSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append("<gold>PeopleHunt status</gold>\n");
        builder.append("<yellow>State: last session</yellow>\n");
        builder.append("<gray>Victory: ").append(snapshot.metadata().outcome()).append("</gray>\n");
        builder.append("<gray>Runner: ").append(snapshot.metadata().runnerName()).append("</gray>\n");
        builder.append("<gray>Started: ").append(Text.formatTimestamp(snapshot.metadata().startedAtEpochMillis())).append("</gray>\n");
        builder.append("<gray>Elapsed: ").append(Text.formatDurationMillis(snapshot.metadata().endedAtEpochMillis() - snapshot.metadata().startedAtEpochMillis())).append("</gray>");
        for (ReportModels.ParticipantStats stat : snapshot.stats()) {
            builder.append("\n<gray>").append(nameOf(stat.uuid())).append(" - deaths: ").append(stat.deaths()).append(", player kills: ").append(stat.playerKills()).append("</gray>");
        }
        return builder.toString();
    }

    private static final class PrimeContext {
        private final Location initialRunnerLocation;
        private final boolean keepPlayersFull;
        private final Set<UUID> participantIds;
        private final long primedAtEpochMillis = System.currentTimeMillis();

        private PrimeContext(Location initialRunnerLocation, boolean keepPlayersFull, Set<UUID> participantIds) {
            this.initialRunnerLocation = initialRunnerLocation;
            this.keepPlayersFull = keepPlayersFull;
            this.participantIds = participantIds;
        }
    }

    private static final class ActiveMatch {
        private final UUID reportId;
        private final long startedAtEpochMillis;
        private final UUID runnerUuid;
        private final Set<UUID> hunterIds;
        private final Set<UUID> spectatorIds;
        private String activeKitId;
        private KeepInventoryMode keepInventoryMode;
        private final Map<UUID, Role> roles = new LinkedHashMap<>();
        private final Map<UUID, Integer> lifeIndex = new HashMap<>();
        private final Map<UUID, DeathstreakState> deathstreaks = new HashMap<>();
        private final Map<UUID, List<ItemStack>> pendingRespawnRestore = new HashMap<>();
        private final Map<UUID, Location> pendingPortalPrompt = new HashMap<>();
        private final Map<UUID, Location> lastKnownRunnerLocations = new HashMap<>();
        private final Set<UUID> diedInEnd = new LinkedHashSet<>();
        private long nextElapsedAnnouncementMinutes;
        private Location currentRunnerLocation;
        private Location lastRunnerOverworldEndPortal;

        private ActiveMatch(UUID reportId, long startedAtEpochMillis, UUID runnerUuid, Set<UUID> hunterIds, Set<UUID> spectatorIds, String activeKitId, KeepInventoryMode keepInventoryMode) {
            this.reportId = reportId;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.runnerUuid = runnerUuid;
            this.hunterIds = new LinkedHashSet<>(hunterIds);
            this.spectatorIds = new LinkedHashSet<>(spectatorIds);
            this.activeKitId = activeKitId;
            this.keepInventoryMode = keepInventoryMode;
            this.nextElapsedAnnouncementMinutes = 1L;
        }
    }

    private static final class DeathstreakState {
        private int streakDeaths;
        private double damageThisLife;
    }
}
