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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MatchManager {
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

    public KeepInventoryMode keepInventoryMode() {
        return stateData.keepInventoryMode;
    }

    public void setKeepInventoryMode(KeepInventoryMode mode) {
        KeepInventoryMode resolved = mode == null ? KeepInventoryMode.NONE : mode;
        stateData.keepInventoryMode = resolved;
        if (activeSession != null) {
            activeSession.keepInventoryMode = resolved;
            reportService.updateSessionSettings(resolved, activeSession.activeKitId);
        }
        persistQuietly();
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
        if (activeSession != null) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (nowSelected && player != null) {
                addHunterToActiveMatch(player);
            } else {
                removeHunterFromActiveMatch(playerUuid);
            }
        }
        return nowSelected;
    }

    public void prepare(boolean wipeEffects) {
        for (Player player : resolvePrepareParticipants()) {
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
    }

    public void prime(Boolean keepPlayersFullOverride) {
        if (activeSession != null) throw new IllegalStateException("A match is already active.");
        Player runner = requireOnlineRunner();
        Set<UUID> participants = resolveNextParticipantUuids(true);
        if (participants.isEmpty()) participants.add(runner.getUniqueId());

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
        tickService.startPrimeTask();
    }

    public UUID startNow() throws IOException {
        if (activeSession != null) throw new IllegalStateException("A match is already active.");
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
            if (hunter != null) participants.add(new ReportService.ParticipantSeed(hunter.getUniqueId(), hunter.getName(), Role.HUNTER.name(), false, false));
        }
        for (UUID spectatorId : spectatorIds) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null) participants.add(new ReportService.ParticipantSeed(spectator.getUniqueId(), spectator.getName(), Role.SPECTATOR.name(), false, true));
        }

        UUID reportId = reportService.startSession(
                runner.getUniqueId(), runner.getName(), stateData.keepInventoryMode,
                stateData.activeKitId, config.reportStorageFormat(), participants
        );

        activeSession = new MatchSession(reportId, System.currentTimeMillis(), runner.getUniqueId(), hunterIds, spectatorIds, stateData.activeKitId, stateData.keepInventoryMode);
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

    private Optional<ReportService.FinishResult> finishMatch(MatchOutcome outcome) throws IOException {
        tickService.stopRuntimeTasks();
        activeSession = null; // Frees match state memory!

        Optional<ReportService.FinishResult> result = reportService.finish(outcome);
        broadcast(Text.mm("<green>Manhunt ended."));
        result.ifPresent(finish -> {
            Component stats = buildFinishedStatsComponent(finish.snapshot());
            broadcast(stats);
            stateData.lastStatusSnapshot = new PersistentStateStore.LastStatusSnapshot(
                    finish.indexEntry().reportId(), stateData.selectionGeneration,
                    finish.indexEntry().startedAtEpochMillis(), finish.indexEntry().endedAtEpochMillis(),
                    outcome, finish.indexEntry().runnerUuid(), finish.indexEntry().runnerName(),
                    Text.formatTimestamp(finish.indexEntry().startedAtEpochMillis()),
                    Text.formatDurationMillis(finish.indexEntry().endedAtEpochMillis() - finish.indexEntry().startedAtEpochMillis()),
                    buildFinishedStatsMiniMessage(finish.snapshot())
            );
            persistQuietly();
        });
        return result;
    }

    public boolean isRunner(UUID uuid) {
        return activeSession != null ? activeSession.roles.get(uuid) == Role.RUNNER : Objects.equals(stateData.runnerUuid, uuid);
    }

    public boolean isHunter(UUID uuid) {
        return activeSession != null ? activeSession.roles.get(uuid) == Role.HUNTER : stateData.explicitHunters.contains(uuid);
    }

    public boolean isParticipant(UUID uuid) {
        return activeSession != null && activeSession.roles.containsKey(uuid);
    }

    public Role roleOf(UUID uuid) {
        return activeSession == null ? null : activeSession.roles.get(uuid);
    }

    public Collection<Player> onlineHunters() {
        if (activeSession == null) return resolveNextHunterUuids().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        return activeSession.hunterIds.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
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
        return Bukkit.getOfflinePlayer(uuid).getName() == null ? uuid.toString() : Bukkit.getOfflinePlayer(uuid).getName();
    }

    private void ensureNotActive() {
        if (activeSession != null || primeContext != null) throw new IllegalStateException("Stop or finish the current session first.");
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

    // --- Add/Remove Participants Mid-Match ---

    public void addHunterToActiveMatch(Player player) {
        if (activeSession == null || player == null || player.getUniqueId().equals(activeSession.runnerUuid)) return;
        UUID uuid = player.getUniqueId();
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

    // --- Status Components ---

    public Component buildStatusComponent() {
        if (activeSession != null) {
            return Text.lines(List.of(
                    Component.text("PeopleHunt status", NamedTextColor.GOLD),
                    Component.text("State: active", NamedTextColor.YELLOW),
                    Component.text("Runner: " + nameOf(stateData.runnerUuid), NamedTextColor.GRAY),
                    Component.text("Hunters: " + namesOf(activeSession.hunterIds), NamedTextColor.GRAY),
                    Component.text("Spectators: " + namesOf(activeSession.spectatorIds), NamedTextColor.GRAY),
                    Component.text("Started: " + Text.formatTimestamp(activeSession.startedAtEpochMillis), NamedTextColor.GRAY),
                    Component.text("Elapsed: " + Text.formatDurationMillis(System.currentTimeMillis() - activeSession.startedAtEpochMillis), NamedTextColor.GRAY),
                    Component.text("Keep inventory: " + activeSession.keepInventoryMode, NamedTextColor.GRAY),
                    Component.text("Kit: " + (activeSession.activeKitId == null ? "none" : activeSession.activeKitId), NamedTextColor.GRAY)
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

    private Component buildFinishedStatsComponent(ReportModels.ViewerSnapshot snapshot) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Match statistics", NamedTextColor.GOLD));
        lines.add(Component.text("Victory: " + snapshot.metadata().outcome(), NamedTextColor.YELLOW));
        lines.add(Component.text("Runner: " + snapshot.metadata().runnerName(), NamedTextColor.GRAY));
        lines.add(Component.text("Match time: " + Text.formatDurationMillis(snapshot.metadata().endedAtEpochMillis() - snapshot.metadata().startedAtEpochMillis()), NamedTextColor.GRAY));
        for (ReportModels.ParticipantStats stat : snapshot.stats()) {
            lines.add(Component.text(nameOf(stat.uuid()) + " - deaths: " + stat.deaths() + ", player kills: " + stat.playerKills(), NamedTextColor.GRAY));
        }
        return Text.lines(lines);
    }

    private String buildFinishedStatsMiniMessage(ReportModels.ViewerSnapshot snapshot) {
        long duration = snapshot.metadata().endedAtEpochMillis() - snapshot.metadata().startedAtEpochMillis();
        String outcomeColor = snapshot.metadata().outcome().contains("HUNTER") ? "<red>" : "<green>";

        StringBuilder builder = new StringBuilder();

        // 1. Header with stylized separator
        builder.append("<gray>————————————————— [ <gold><b>POST-MATCH STATS</b></gold> ] —————————————————</gray>\n");

        // 2. Summary Section (Label: Value format)
        builder.append("<yellow>Result:</yellow> ").append(outcomeColor).append("<b>").append(snapshot.metadata().outcome()).append("</b></color>\n");
        builder.append("<yellow>Runner:</yellow> <white>").append(snapshot.metadata().runnerName()).append("</white>\n");
        builder.append("<yellow>Time:</yellow> <white>").append(Text.formatDurationMillis(duration)).append("</white> <dark_gray>(started: ").append(Text.formatTimestamp(snapshot.metadata().startedAtEpochMillis())).append(")</dark_gray>\n");

        // 3. Participant Section Header
        builder.append("\n<gray><b>Participant Performance:</b></gray>\n");

        // 4. Participant List with Icons
        for (ReportModels.ParticipantStats stat : snapshot.stats()) {
            String playerName = nameOf(stat.uuid());

            builder.append(" <dark_gray>»</dark_gray> <white>").append(playerName).append("</white>")
                    .append(" <dark_gray>|</dark_gray> <red>☠ ").append(stat.deaths()).append("</red>") // Deaths Icon
                    .append(" <aqua>⚔ ").append(stat.playerKills()).append("</aqua>")             // Kills Icon
                    .append("\n");
        }

        builder.append("<gray>————————————————————————————————————————————————————</gray>");

        return builder.toString();
    }

    private String namesOf(Collection<UUID> uuids) {
        return (uuids == null || uuids.isEmpty()) ? "none" : uuids.stream().map(this::nameOf).collect(Collectors.joining(", "));
    }

    public Optional<Location> consumeEndPortalPrompt(UUID uuid) {
        MatchSession session = this.getSession();
        return session == null ? Optional.empty() : Optional.ofNullable(session.pendingPortalPrompt.remove(uuid));
    }
}