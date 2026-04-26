package io.github.ganyuke.peoplehunt.game.match;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.game.*;
import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.tools.SurroundService;
import io.github.ganyuke.peoplehunt.report.ReportModels;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the authoritative match state machine.
 *
 * <p>This class separates three operator-visible phases:
 * <ul>
 *   <li>idle: player selections and settings only</li>
 *   <li>primed: waiting for the runner's first movement to begin the run</li>
 *   <li>active: live match with reporting, compasses, and listener-driven game logic</li>
 * </ul>
 * All commands and listeners route through this class when they need to mutate the match model.
 */
public final class MatchManager {
    public enum PrepareMode {
        HEALTH_AND_HUNGER,
        STATUS_EFFECTS,
        EXPERIENCE,
        INVENTORY
    }

    private static final String HEADER_LINE = "<gray>———— [ <gold><b>%s</b></gold> ] ————</gray>";
    private static final String FOOTER_LINE = "<gray>———————————————————————</gray>";
    private static final String SECTION_PREFIX = "<gray><b>%s</b></gray>";
    private static final String LABEL_PREFIX = "<yellow>%s:</yellow> ";
    private static final String VALUE = "<white>%s</white>";
    private static final String BULLET = " <dark_gray>»</dark_gray> ";

    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final PersistentStateStore stateStore;
    private final PersistentStateStore.StateData stateData;
    private final KitService kitService;
    private final CompassService compassService;
    private final ReportService reportService;
    private final SurroundService surroundService;
    private final MatchTickService tickService;

    private PrimeContext primeContext;
    private MatchSession activeSession;

    public MatchManager(
            JavaPlugin plugin, PeopleHuntConfig config, PersistentStateStore stateStore,
            PersistentStateStore.StateData stateData, KitService kitService,
            CompassService compassService, ReportService reportService,
            SurroundService surroundService, MatchTickService tickService
    ) {
        this.plugin = plugin;
        this.config = config;
        this.stateStore = stateStore;
        this.stateData = stateData;
        this.kitService = kitService;
        this.compassService = compassService;
        this.reportService = reportService;
        this.surroundService = surroundService;
        this.tickService = tickService;
    }

    public MatchSession getSession() {
        return activeSession;
    }

    public PrimeContext getPrimeContext() {
        return primeContext;
    }

    public PersistentStateStore.StateData getStateData() {
        return stateData;
    }

    public boolean hasActiveMatch() {
        return activeSession != null;
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

    public KeepInventoryMode inventoryControlMode() {
        return stateData.inventoryControlMode;
    }

    public void setInventoryControlMode(KeepInventoryMode mode) {
        KeepInventoryMode resolved = (mode == null || mode == KeepInventoryMode.INHERIT)
                ? KeepInventoryMode.NONE : mode;
        stateData.inventoryControlMode = resolved;
        if (activeSession != null) {
            activeSession.keepInventoryMode = resolved;
            reportService.updateSessionSettings(resolved, activeSession.activeKitId);
            broadcast(Text.mm("<yellow>Inventory control changed to <white>" + resolved + "</white>.</yellow>"));
        }
        persistQuietly();
    }

    /**
     * Switches the active session's effective inventory control mode to the
     * configured end-dimension override. Called once when the runner first
     * enters the End. No-op if the session has already been switched or if
     * there is no active session.
     */
    public void activateEndInventoryControl(KeepInventoryMode endMode) {
        if (activeSession == null || activeSession.endInventoryControlActivated) return;
        activeSession.endInventoryControlActivated = true;
        activeSession.keepInventoryMode = endMode;
        reportService.updateSessionSettings(endMode, activeSession.activeKitId);
        broadcast(Text.mm("<yellow>Runner has entered the End. Inventory control switched to <white>"
                + endMode.name() + "</white>.</yellow>"));
    }

    public String activeKitId() {
        return stateData.activeKitId;
    }

    public void setActiveKitId(String activeKitId) {
        stateData.activeKitId = activeKitId;
        if (activeSession != null) {
            activeSession.activeKitId = activeKitId;
            reportService.updateSessionSettings(activeSession.keepInventoryMode, activeKitId);
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
        if (stateData.explicitHunters.contains(playerUuid)) {
            removeHunter(playerUuid);
            return false;
        }
        return addHunter(playerUuid);
    }

    public boolean addHunter(UUID playerUuid) {
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            return false;
        }
        boolean changed = stateData.explicitHunters.add(playerUuid);
        touchSelections();
        if (activeSession != null) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) addHunterToActiveMatch(player);
        }
        return changed;
    }

    public boolean removeHunter(UUID playerUuid) {
        if (Objects.equals(stateData.runnerUuid, playerUuid)) {
            return false;
        }
        boolean changed = stateData.explicitHunters.remove(playerUuid);
        touchSelections();
        if (activeSession != null) {
            removeHunterFromActiveMatch(playerUuid);
        }
        return changed;
    }

    public int clearExplicitHunters() {
        ensureNotActive();
        int removed = stateData.explicitHunters.size();
        if (removed > 0) {
            stateData.explicitHunters.clear();
            touchSelections();
        }
        return removed;
    }

    public void prepare(EnumSet<PrepareMode> modes) {
        EnumSet<PrepareMode> resolvedModes = modes == null || modes.isEmpty()
                ? EnumSet.of(PrepareMode.HEALTH_AND_HUNGER, PrepareMode.STATUS_EFFECTS, PrepareMode.EXPERIENCE)
                : EnumSet.copyOf(modes);

        // Prepare remains intentionally role-neutral: operators can choose which state buckets to
        // normalize, but this command never teleports players or changes match roles/game modes.
        for (Player player : resolvePrepareParticipants()) {
            if (resolvedModes.contains(PrepareMode.STATUS_EFFECTS)) {
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
                player.setFireTicks(0);
                player.setFreezeTicks(0);
            }
            if (resolvedModes.contains(PrepareMode.HEALTH_AND_HUNGER)) {
                player.setHealth(maxHealth(player));
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
            }
            if (resolvedModes.contains(PrepareMode.EXPERIENCE)) {
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
            if (resolvedModes.contains(PrepareMode.INVENTORY)) {
                player.getInventory().clear();
                player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                player.getInventory().setItemInOffHand(null);
                player.updateInventory();
            }
        }
    }

    public void prime(Boolean keepPlayersFullOverride) {
        if (activeSession != null) throw new IllegalStateException("A match is already active.");
        Player runner = requireOnlineRunner();
        Set<UUID> participants = resolveNextParticipantUuids(true);
        if (participants.isEmpty()) participants.add(runner.getUniqueId());

        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setHealth(maxHealth(player));
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
        }
        // Prime stores the runner's starting position and freezes the participant set that will be
        // kept full while waiting for first movement.
        primeContext = PrimeContext.create(
                runner.getLocation().clone(),
                keepPlayersFullOverride == null ? config.primeKeepPlayersFull() : keepPlayersFullOverride,
                participants
        );
        tickService.startPrimeTask();

        // Notify all participants that the match is primed.
        String runnerName = runner.getName();
        broadcast(Text.mm("<aqua><b>Match primed.</b></aqua> <gray>The match will start as soon as <white>"
                + runnerName + "</white> moves.</gray>"));

        // Give the runner a more prominent, personal heads-up.
        runner.sendMessage(Text.mm(
                "<gold><b>You are the runner.</b></gold> <yellow>Move to begin the manhunt!</yellow>"));
    }

    public UUID startNow() throws IOException {
        if (activeSession != null) throw new IllegalStateException("A match is already active.");
        Player runner = requireOnlineRunner();
        // Starting always clears the primed state first so the live session becomes the single
        // source of truth for runtime logic.
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
        participants.add(new ReportService.ParticipantSeed(
                runner.getUniqueId(), runner.getName(), Role.RUNNER.name(), false, false
        ));
        for (UUID hunterId : hunterIds) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null) {
                participants.add(new ReportService.ParticipantSeed(
                        hunter.getUniqueId(), hunter.getName(), Role.HUNTER.name(), false, false
                ));
            }
        }
        for (UUID spectatorId : spectatorIds) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null) {
                participants.add(new ReportService.ParticipantSeed(
                        spectator.getUniqueId(), spectator.getName(), Role.SPECTATOR.name(), false, true
                ));
            }
        }

        UUID reportId = reportService.startSession(
                runner.getUniqueId(), runner.getName(), stateData.inventoryControlMode,
                stateData.activeKitId, participants
        );

        // Active session state is kept entirely in memory; only operator selections/settings are
        // persisted across restarts.
        activeSession = new MatchSession(
                reportId,
                System.currentTimeMillis(),
                runner.getUniqueId(),
                hunterIds,
                spectatorIds,
                stateData.activeKitId,
                stateData.inventoryControlMode
        );
        activeSession.nextElapsedAnnouncementMinutes = Math.max(1, config.elapsedAnnouncementMinutes());
        activeSession.currentRunnerLocation = runner.getLocation().clone();

        activeSession.lifeIndex.put(runner.getUniqueId(), 1);
        for (UUID hunterId : hunterIds) {
            activeSession.lifeIndex.put(hunterId, 1);
            activeSession.deathstreaks.put(hunterId, new MatchSession.DeathstreakState());
        }
        for (UUID spectatorId : spectatorIds) {
            activeSession.lifeIndex.put(spectatorId, 1);
        }

        for (World world : Bukkit.getWorlds()) {
            Location spawn = world.getSpawnLocation();
            reportService.recordMarker("world_spawn", null, null, spawn, "World Spawn", "World spawn in " + io.github.ganyuke.peoplehunt.util.PrettyNames.key(world.getKey().asString()), "#9ca3af");
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(runner.getUniqueId())) {
                activeSession.roles.put(player.getUniqueId(), Role.RUNNER);
                player.setGameMode(GameMode.SURVIVAL);
            } else if (hunterIds.contains(player.getUniqueId())) {
                activeSession.roles.put(player.getUniqueId(), Role.HUNTER);
                player.setGameMode(GameMode.SURVIVAL);
                compassService.giveCompass(List.of(player));
                if (config.applyKitOnStart() && stateData.activeKitId != null) {
                    kitService.applyMissingKit(player, stateData.activeKitId);
                }
            } else {
                activeSession.roles.put(player.getUniqueId(), Role.SPECTATOR);
                if (config.autoSpectateNewJoins()) player.setGameMode(GameMode.SPECTATOR);
            }
        }

        tickService.startRuntimeTasks();
        for (UUID uuid : new ArrayList<>(activeSession.roles.keySet())) {
            Player sampled = Bukkit.getPlayer(uuid);
            if (sampled != null) {
                tickService.captureImmediateSample(sampled);
            }
        }
        broadcast(Text.mm("<green>Manhunt started."));
        reportService.recordTimeline(runner.getUniqueId(), runner.getName(), "match", "Manhunt started");
        persistQuietly();
        return reportId;
    }

    public Optional<ReportService.FinishResult> stopInconclusive() throws IOException {
        if (activeSession == null) {
            cancelPrimeInternal();
            return Optional.empty();
        }
        return finishMatch(MatchOutcome.INCONCLUSIVE);
    }

    public Optional<ReportService.FinishResult> endHunterVictory() throws IOException {
        return activeSession == null ? Optional.empty() : finishMatch(MatchOutcome.HUNTER_VICTORY);
    }

    public Optional<ReportService.FinishResult> endRunnerVictory() throws IOException {
        return activeSession == null ? Optional.empty() : finishMatch(MatchOutcome.RUNNER_VICTORY);
    }

    private void announceVictoryNextTick(String summary) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            broadcast(Text.mm(summary));
        }, 1L);
    }

    private Optional<ReportService.FinishResult> finishMatch(MatchOutcome outcome) throws IOException {
        tickService.stopRuntimeTasks();
        activeSession = null;

        Optional<ReportService.FinishResult> result = reportService.finish(outcome);
        broadcast(Text.mm("<green>Manhunt ended."));
        result.ifPresent(finish -> {
            String summary = buildFinishedStatsMessage(finish.snapshot());
            // The formatted summary is cached into persisted state so /peoplehunt status can show
            // the last finished match even after restart, as long as selections have not changed.
            announceVictoryNextTick(summary);
            stateData.lastStatusSnapshot = new PersistentStateStore.LastStatusSnapshot(
                    finish.indexEntry().reportId(),
                    stateData.selectionGeneration,
                    finish.indexEntry().startedAtEpochMillis(),
                    finish.indexEntry().endedAtEpochMillis(),
                    outcome,
                    finish.indexEntry().runnerUuid(),
                    finish.indexEntry().runnerName(),
                    Text.formatTimestamp(finish.indexEntry().startedAtEpochMillis()),
                    Text.formatDurationMillis(
                            finish.indexEntry().endedAtEpochMillis() - finish.indexEntry().startedAtEpochMillis()
                    ),
                    summary
            );
            persistQuietly();
        });
        return result;
    }

    public boolean isRunner(UUID uuid) {
        return activeSession != null
                ? activeSession.roles.get(uuid) == Role.RUNNER
                : Objects.equals(stateData.runnerUuid, uuid);
    }

    public boolean isHunter(UUID uuid) {
        return activeSession != null
                ? activeSession.roles.get(uuid) == Role.HUNTER
                : stateData.explicitHunters.contains(uuid);
    }

    public boolean isParticipant(UUID uuid) {
        return activeSession != null && activeSession.roles.containsKey(uuid);
    }

    public Role roleOf(UUID uuid) {
        return activeSession == null ? null : activeSession.roles.get(uuid);
    }

    public Collection<Player> onlineHunters() {
        if (activeSession == null) {
            return resolveNextHunterUuids().stream()
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return activeSession.hunterIds.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();
    }

    public void surroundHunters(double minRadius, Double maxRadius) {
        Player runner = requireOnlineRunner();
        surroundService.surround(runner, new ArrayList<>(onlineHunters()), minRadius, maxRadius);
    }

    public void cancelPrimeInternal() {
        primeContext = null;
        tickService.cancelPrimeTask();
    }

    public void broadcast(Component component) {
        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(component);
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) return "unset";
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        return Bukkit.getOfflinePlayer(uuid).getName() == null
                ? uuid.toString()
                : Bukkit.getOfflinePlayer(uuid).getName();
    }

    private void ensureNotActive() {
        if (activeSession != null || primeContext != null) {
            throw new IllegalStateException("Stop or finish the current session first.");
        }
    }

    private Player requireOnlineRunner() {
        if (stateData.runnerUuid == null) throw new IllegalStateException("A runner must be selected first.");
        Player runner = Bukkit.getPlayer(stateData.runnerUuid);
        if (runner == null) throw new IllegalStateException("The selected runner must be online.");
        return runner;
    }

    private void touchSelections() {
        stateData.selectionGeneration++;
        stateData.lastStatusSnapshot = null;
        persistQuietly();
    }

    private void persistQuietly() {
        try {
            stateStore.save(stateData);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to persist PeopleHunt state: " + exception.getMessage());
        }
    }

    private Set<UUID> resolveNextParticipantUuids(boolean includeRunner) {
        Set<UUID> out = new LinkedHashSet<>();
        if (includeRunner && stateData.runnerUuid != null) out.add(stateData.runnerUuid);
        out.addAll(resolveNextHunterUuids());
        return out;
    }

    private Set<UUID> resolveNextHunterUuids() {
        // Explicit hunter selections win. Otherwise the plugin falls back to the common manhunt
        // convenience rule: every online non-runner becomes a hunter.
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
            if (runner != null) players.add(runner);
        }
        if (!stateData.explicitHunters.isEmpty()) {
            for (UUID uuid : stateData.explicitHunters) {
                Player hunter = Bukkit.getPlayer(uuid);
                if (hunter != null) players.add(hunter);
            }
        } else {
            players.addAll(Bukkit.getOnlinePlayers());
        }
        return new ArrayList<>(players);
    }

    public void addHunterToActiveMatch(Player player) {
        if (activeSession == null || player == null || player.getUniqueId().equals(activeSession.runnerUuid)) return;
        UUID uuid = player.getUniqueId();

        // Mid-match role changes are applied immediately and also forwarded to report state so the
        // after-action report reflects late joins and manual admin intervention.
        activeSession.hunterIds.add(uuid);
        activeSession.spectatorIds.remove(uuid);
        activeSession.roles.put(uuid, Role.HUNTER);
        activeSession.lifeIndex.putIfAbsent(uuid, 1);
        activeSession.deathstreaks.putIfAbsent(uuid, new MatchSession.DeathstreakState());
        player.setGameMode(GameMode.SURVIVAL);
        compassService.giveCompass(List.of(player));
        if (stateData.activeKitId != null) kitService.applyMissingKit(player, stateData.activeKitId);
        reportService.registerParticipant(uuid, player.getName(), Role.HUNTER.name(), true, false);
        reportService.recordTimeline(uuid, player.getName(), "participant", "joined as hunter");
    }

    public void removeHunterFromActiveMatch(UUID uuid) {
        if (activeSession == null || uuid == null || !activeSession.hunterIds.contains(uuid)) return;

        // Removing a hunter demotes them to spectator rather than fully removing them from the
        // session so their historical activity remains represented in the report.
        activeSession.hunterIds.remove(uuid);
        activeSession.spectatorIds.add(uuid);
        activeSession.roles.put(uuid, Role.SPECTATOR);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && config.autoSpectateNewJoins()) player.setGameMode(GameMode.SPECTATOR);
        if (player != null) {
            reportService.registerParticipant(uuid, player.getName(), Role.SPECTATOR.name(), true, false);
            reportService.recordTimeline(uuid, player.getName(), "participant", "changed to spectator");
        }
    }

    public Component buildStatusComponent() {
        if (activeSession != null) {
            return Text.mm(buildActiveStatusMessage());
        }
        if (primeContext != null) {
            return Text.mm(buildPrimedStatusMessage());
        }
        if (stateData.lastStatusSnapshot != null
                && stateData.lastStatusSnapshot.selectionGeneration() == stateData.selectionGeneration) {
            return Text.mm(stateData.lastStatusSnapshot.summaryText());
        }
        return Text.mm(buildIdleStatusMessage());
    }

    private String buildActiveStatusMessage() {
        List<String> lines = new ArrayList<>(List.of(
                statusLine("State", "<green><b>ACTIVE</b></green>"),
                statusLine("Runner", white(nameOf(stateData.runnerUuid))),
                statusLine("Hunters", white(namesOf(activeSession.hunterIds))),
                statusLine("Spectators", white(namesOf(activeSession.spectatorIds))),
                statusLine("Started", white(Text.formatTimestamp(activeSession.startedAtEpochMillis))),
                statusLine("Elapsed", white(Text.formatDurationMillis(
                        System.currentTimeMillis() - activeSession.startedAtEpochMillis
                ))),
                statusLine("Inventory control", white(String.valueOf(activeSession.keepInventoryMode)))
        ));
        if (activeSession.keepInventoryMode == KeepInventoryMode.KIT) {
            lines.add(statusLine("Kit", white(displayKit(activeSession.activeKitId))));
        }
        return messageBlock("PEOPLEHUNT STATUS", lines);
    }

    private String buildPrimedStatusMessage() {
        return messageBlock("PEOPLEHUNT STATUS", List.of(
                statusLine("State", "<aqua><b>PRIMED</b></aqua>"),
                statusLine("Runner", white(nameOf(stateData.runnerUuid))),
                statusLine("Hunters", white(namesOf(resolveNextHunterUuids()))),
                statusLine("Primed at", white(Text.formatTimestamp(primeContext.primedAtEpochMillis()))),
                statusLine("Keep full", white(String.valueOf(primeContext.keepPlayersFull())))
        ));
    }

    private String buildIdleStatusMessage() {
        List<String> lines = new ArrayList<>(List.of(
                statusLine("State", "<gray><b>IDLE</b></gray>"),
                statusLine("Pending runner", white(nameOf(stateData.runnerUuid))),
                statusLine(
                        "Pending hunters",
                        white(stateData.explicitHunters.isEmpty()
                                ? "all online except runner"
                                : namesOf(stateData.explicitHunters))
                ),
                statusLine("Inventory control", white(String.valueOf(stateData.inventoryControlMode)))
        ));
        if (stateData.inventoryControlMode == KeepInventoryMode.KIT) {
            lines.add(statusLine("Kit", white(displayKit(stateData.activeKitId))));
        }
        return messageBlock("PEOPLEHUNT STATUS", lines);
    }

    private String buildFinishedStatsMessage(ReportModels.ViewerSnapshot snapshot) {
        long duration = snapshot.metadata().endedAtEpochMillis() - snapshot.metadata().startedAtEpochMillis();

        List<String> lines = new ArrayList<>();
        lines.add(statusLine("Result", coloredOutcome(snapshot.metadata().outcome())));
        lines.add(statusLine("Runner", white(snapshot.metadata().runnerName())));
        lines.add(
                statusLine(
                        "Time",
                        white(Text.formatDurationMillis(duration))
                                + " <dark_gray>(started: "
                                + Text.formatTimestamp(snapshot.metadata().startedAtEpochMillis())
                                + ")</dark_gray>"
                )
        );
        lines.add("");
        lines.add(section("Participant Performance"));
        lines.addAll(snapshot.stats().stream()
                .map(this::participantStatLine)
                .toList());

        return messageBlock("POST-MATCH STATS", lines);
    }

    private String participantStatLine(ReportModels.ParticipantStats stat) {
        return BULLET
                + white(nameOf(stat.uuid()))
                + " <dark_gray>|</dark_gray> <red>☠ "
                + stat.deaths()
                + "</red> <aqua>⚔ "
                + stat.playerKills()
                + "</aqua>";
    }

    private String messageBlock(String title, List<String> lines) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(HEADER_LINE, title)).append("\n");

        for (int i = 0; i < lines.size(); i++) {
            builder.append(lines.get(i));
            if (i < lines.size() - 1) {
                builder.append("\n");
            }
        }

        builder.append("\n").append(FOOTER_LINE);
        return builder.toString();
    }

    private String statusLine(String label, String value) {
        return String.format(LABEL_PREFIX, label) + value;
    }

    private String section(String title) {
        return String.format(SECTION_PREFIX, title + ":");
    }

    private String displayOutcome(String outcome) {
        return switch (outcome) {
            case "HUNTER_VICTORY" -> "Hunter Victory";
            case "RUNNER_VICTORY" -> "Runner Victory";
            case "INCONCLUSIVE" -> "Inconclusive";
            default -> outcome.replace('_', ' ');
        };
    }

    private String white(String value) {
        return String.format(VALUE, value);
    }

    private String coloredOutcome(String outcome) {
        String pretty = displayOutcome(outcome);

        if (outcome.contains("HUNTER")) {
            return "<red><b>" + pretty + "</b></red>";
        }
        if (outcome.contains("RUNNER")) {
            return "<green><b>" + pretty + "</b></green>";
        }
        return "<yellow><b>" + pretty + "</b></yellow>";
    }

    private String displayKit(String kitId) {
        return kitId == null ? "none" : kitId;
    }

    private String namesOf(Collection<UUID> uuids) {
        return (uuids == null || uuids.isEmpty())
                ? "none"
                : uuids.stream().map(this::nameOf).collect(Collectors.joining(", "));
    }

    public Optional<Location> consumeEndPortalPrompt(UUID uuid) {
        MatchSession session = this.getSession();
        return session == null ? Optional.empty() : Optional.ofNullable(session.pendingPortalPrompt.remove(uuid));
    }


    public int rollback(UUID targetUuid, long rewindMillis, boolean teleport, boolean restoreGameMode, boolean restoreEffects) {
        if (activeSession == null) {
            throw new IllegalStateException("No active match to roll back.");
        }
        long targetTime = System.currentTimeMillis() - Math.max(0L, rewindMillis);
        int restored = 0;
        for (UUID uuid : new ArrayList<>(activeSession.roles.keySet())) {
            if (targetUuid != null && !targetUuid.equals(uuid)) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            MatchSession.RollbackState state = chooseRollbackState(activeSession.rollbackBuffer.get(uuid), targetTime);
            if (state == null) continue;
            applyRollbackState(player, state, teleport, restoreGameMode, restoreEffects);
            reportService.recordTimeline(uuid, player.getName(), "rollback", "rolled back by operator to " + Text.formatDurationMillis(Math.max(0L, rewindMillis)) + " ago", Long.toString(rewindMillis), "#fca5a5");
            restored++;
        }
        return restored;
    }

    private MatchSession.RollbackState chooseRollbackState(java.util.Deque<MatchSession.RollbackState> buffer, long targetTime) {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        MatchSession.RollbackState candidate = buffer.peekFirst();
        for (MatchSession.RollbackState state : buffer) {
            if (state.capturedAtEpochMillis() <= targetTime) {
                candidate = state;
                continue;
            }
            break;
        }
        return candidate;
    }

    private double maxHealth(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? player.getHealth() : attribute.getValue();
    }

    private void applyRollbackState(Player player, MatchSession.RollbackState state, boolean teleport, boolean restoreGameMode, boolean restoreEffects) {
        if (teleport && state.location() != null && state.location().getWorld() != null) {
            player.teleport(state.location());
        }
        if (restoreGameMode && state.gameMode() != null) {
            player.setGameMode(state.gameMode());
        }
        player.getInventory().clear();
        ItemStack[] contents = new ItemStack[player.getInventory().getSize()];
        for (int i = 0; i < contents.length && i < state.contents().size(); i++) {
            ItemStack item = state.contents().get(i);
            contents[i] = item == null ? null : item.clone();
        }
        player.getInventory().setContents(contents);
        player.getInventory().setHelmet(state.helmet() == null ? null : state.helmet().clone());
        player.getInventory().setChestplate(state.chestplate() == null ? null : state.chestplate().clone());
        player.getInventory().setLeggings(state.leggings() == null ? null : state.leggings().clone());
        player.getInventory().setBoots(state.boots() == null ? null : state.boots().clone());
        player.getInventory().setItemInOffHand(state.offHand() == null ? null : state.offHand().clone());
        var maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? state.maxHealth() : maxHealthAttribute.getValue();
        player.setAbsorptionAmount(state.absorption());
        player.setHealth(Math.max(0.1, Math.min(maxHealth, state.health())));
        player.setFoodLevel(state.food());
        player.setSaturation(state.saturation());
        player.setLevel(state.level());
        player.setTotalExperience(state.totalExperience());
        if (restoreEffects) {
            for (var effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            for (var effect : state.effects()) {
                player.addPotionEffect(effect);
            }
        }
    }

}
