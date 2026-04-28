package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakOccupiedArmorMode;
import io.github.ganyuke.peoplehunt.config.SessionConfig;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.match.AttributionManager;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchSession;
import io.github.ganyuke.peoplehunt.game.match.MatchTickService;
import io.github.ganyuke.peoplehunt.game.Role;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.ItemUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

/**
 * Handles player lifecycle transitions that change match state: joins, deaths, respawns, and the
 * final dragon kill.
 *
 * <p>This listener is where role-specific post-death behavior lives, including inventory-control
 * policies, deathstreak progression, and the optional End portal return prompt.
 */
public class MatchLifecycleListener implements Listener {
    private enum ArmorGrantResult {
        NOT_ARMOR,
        PLACED,
        SLOT_OCCUPIED
    }

    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final AttributionManager attributionManager;
    private final KitService kitService;
    private final CompassService compassService;
    private final ReportService reportService;
    private final MatchTickService tickService;

    public MatchLifecycleListener(JavaPlugin plugin, PeopleHuntConfig config, MatchManager matchManager, AttributionManager attributionManager, KitService kitService, CompassService compassService, ReportService reportService, MatchTickService tickService) {
        this.plugin = plugin;
        this.config = config;
        this.matchManager = matchManager;
        this.attributionManager = attributionManager;
        this.kitService = kitService;
        this.compassService = compassService;
        this.reportService = reportService;
        this.tickService = tickService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        MatchSession session = matchManager.getSession();
        Player player = event.getPlayer();
        if (session == null) return;

        UUID uuid = player.getUniqueId();
        if (session.roles.containsKey(uuid)) {
            Role role = session.roles.get(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Role restoration is deferred one tick so Bukkit has finished constructing the
                // player's join state before the plugin overwrites game mode or inventory items.
                if (role == Role.RUNNER || role == Role.HUNTER) player.setGameMode(GameMode.SURVIVAL);
                else if (matchManager.getSessionConfig().autoSpectateNewJoins()) player.setGameMode(GameMode.SPECTATOR);

                if (role == Role.HUNTER) compassService.giveCompass(List.of(player));
                tickService.captureImmediateSample(player);
            });
            return;
        }

        session.roles.put(uuid, Role.SPECTATOR);
        session.spectatorIds.add(uuid);
        session.lifeIndex.put(uuid, 1);
        reportService.registerParticipant(uuid, player.getName(), Role.SPECTATOR.name(), true, true);
        reportService.recordTimeline(uuid, player.getName(), "participant", "joined as spectator");
        if (matchManager.getSessionConfig().autoSpectateNewJoins()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setGameMode(GameMode.SPECTATOR);
                tickService.captureImmediateSample(player);
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> tickService.captureImmediateSample(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            MatchSession session = matchManager.getSession();
            if (session == null) return;
            if (!matchManager.isParticipant(player.getUniqueId())) return;

            Role role = matchManager.roleOf(player.getUniqueId());
            if (role == Role.HUNTER) {
                player.setGameMode(GameMode.SURVIVAL);

                // Restore kit items preserved on death (KIT mode)
                List<ItemStack> restore = session.pendingRespawnRestore.remove(player.getUniqueId());
                if (restore != null) {
                    for (ItemStack item : restore) ItemUtil.giveOrDrop(player, item);
                }

                // Apply session kit in KIT mode
                if (session.keepInventoryMode == KeepInventoryMode.KIT && session.activeKitId != null) {
                    kitService.applyMissingKit(player, session.activeKitId);
                }

                applyDeathstreakKit(session, player);
                compassService.giveCompass(List.of(player));
                maybeOfferEndPortalTeleport(session, player);
            } else if (role == Role.RUNNER) {
                player.setGameMode(GameMode.SURVIVAL);
            } else if (matchManager.getSessionConfig().autoSpectateNewJoins()) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            tickService.captureImmediateSample(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHunterDeathPre(PlayerDeathEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        Player player = event.getPlayer();
        if (matchManager.roleOf(player.getUniqueId()) != Role.HUNTER) return;

        switch (session.keepInventoryMode) {
            case KEEP -> {
                event.setKeepInventory(true);
                event.getDrops().clear();
                event.setKeepLevel(true);
                event.setDroppedExp(0);
            }
            case KIT -> {
                if (session.activeKitId != null) {
                    List<ItemStack> preserved = ItemUtil.removeUpToMatches(
                            event.getDrops(), kitService.templateItems(session.activeKitId));
                    if (!preserved.isEmpty()) {
                        session.pendingRespawnRestore
                                .computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>())
                                .addAll(ItemUtil.cloneAll(preserved));
                    }
                }
            }
            case NONE, INHERIT -> {
                // full vanilla drop — nothing to do
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeathPost(PlayerDeathEvent event) {
        MatchSession session = matchManager.getSession();
        if (session == null) return;
        Player player = event.getPlayer();

        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            session.diedInEnd.add(player.getUniqueId());
        }

        Role role = matchManager.roleOf(player.getUniqueId());
        if (role == Role.RUNNER) {
            session.lifeIndex.compute(player.getUniqueId(), (ignored, current) -> (current == null ? 1 : current) + 1);
            matchManager.endHunterVictory();
        } else if (role == Role.HUNTER) {
            session.lifeIndex.compute(player.getUniqueId(), (ignored, current) -> (current == null ? 1 : current) + 1);
            MatchSession.DeathstreakState state = session.deathstreaks
                    .computeIfAbsent(player.getUniqueId(), ignored -> new MatchSession.DeathstreakState());

            if (isRunnerAttributedDeath(session, player, event)) {
                state.streakDeaths++;
            }
            if (!session.globalFirstHunterDeathRecorded) {
                session.globalFirstHunterDeathRecorded = true;
                reportService.recordMilestone(player.getUniqueId(), player.getName(), "first_hunter_death", "First hunter death");
            }
        }
    }

    /**
     * Returns true if this hunter death should be counted toward the deathstreak.
     * Deathstreaks only advance on hunter deaths attributed to the runner; hunter damage dealt
     * during the life does not reset or otherwise modify this counter.
     */
    private boolean isRunnerAttributedDeath(MatchSession session, Player hunter, PlayerDeathEvent event) {
        MatchSession.Attribution attribution = session.lastDeathAttribution.remove(hunter.getUniqueId());
        if (attribution == null) {
            attribution = attributionManager.resolveDeathAttribution(hunter);
        }
        boolean byUuid = attribution != null && session.runnerUuid.equals(attribution.playerUuid());

        return switch (matchManager.getSessionConfig().deathstreakAttributionMode()) {
            case UUID_STRICT    -> byUuid;
            case MESSAGE_STRICT -> deathMessageContainsRunner(session, event);
            case EITHER         -> byUuid || deathMessageContainsRunner(session, event);
        };
    }

    private boolean deathMessageContainsRunner(MatchSession session, PlayerDeathEvent event) {
        if (event.deathMessage() == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        String runnerName = matchManager.nameOf(session.runnerUuid);
        return runnerName != null && plain.contains(runnerName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (matchManager.getSession() != null && event.getEntity() instanceof EnderDragon) {
            matchManager.endRunnerVictory();
        }
    }

    private void maybeOfferEndPortalTeleport(MatchSession session, Player player) {
        if (!config.endPortalRespawnEnabled() || !session.diedInEnd.remove(player.getUniqueId()) || player.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        Location portal = session.lastRunnerOverworldEndPortal;
        if (portal == null || portal.getWorld() == null || !portal.getWorld().getUID().equals(player.getWorld().getUID())) return;
        if (player.getLocation().distance(portal) <= config.endPortalRespawnRadius()) return;

        // The teleport is a prompt rather than an automatic move so the hunter can decide whether
        // returning to the runner's portal is strategically useful.
        session.pendingPortalPrompt.put(player.getUniqueId(), portal.clone());
        Component prompt = Component.text("Teleport to the runner's last End Portal", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/peoplehunt portal"))
                .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + io.github.ganyuke.peoplehunt.util.Text.coord(portal.getX(), portal.getY(), portal.getZ()), NamedTextColor.GRAY)));
        player.sendMessage(prompt);
    }

    /**
     * Applies the active deathstreak tier's item grants to the hunter on respawn.
     * Tiers are keyed only by the number of times this hunter has died to the runner. Saturation
     * and potion effects are applied unconditionally when a tier is active.
     * Item grants are applied slot-aware:
     * <ul>
     *   <li>Armor materials go to the correct armor slot if that slot is empty.</li>
     *   <li>All other items fill the first available storage slot or are dropped.</li>
     *   <li>If the inventory control mode is KEEP the hunter already has items, so we
     *       only fill in missing pieces. For NONE/KIT the hunter has an empty inventory
     *       so every grant is given outright.</li>
     * </ul>
     */
    private void applyDeathstreakKit(MatchSession session, Player player) {
        if (!matchManager.getSessionConfig().deathstreaksEnabled()) return;
        DeathstreakTier tier = activeDeathstreakTier(session, player.getUniqueId());
        if (tier == null) return;

        player.setFoodLevel(Math.max(player.getFoodLevel(), tier.saturationBoost().foodLevel()));
        player.setSaturation(Math.max(player.getSaturation(), tier.saturationBoost().saturation()));
        for (var potion : tier.potionGrants()) {
            player.addPotionEffect(new PotionEffect(potion.type(), potion.durationSeconds() * 20,
                    potion.amplifier(), true, true, true));
        }

        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (var grant : tier.itemGrants()) {
            ItemStack stack = new ItemStack(grant.material(), grant.amount());

            ArmorGrantResult armorResult = tryPlaceArmor(inv, stack);
            if (armorResult == ArmorGrantResult.PLACED) {
                continue;
            }
            if (armorResult == ArmorGrantResult.SLOT_OCCUPIED
                    && matchManager.getSessionConfig().deathstreakOccupiedArmorMode() == DeathstreakOccupiedArmorMode.SKIP) {
                continue;
            }

            // For non-armor grants, and for armor when occupied slots are configured to spill over,
            // only add the missing amount so repeated tier applications stay idempotent.
            int existing = ItemUtil.countSimilar(inv, stack);
            if (existing >= grant.amount()) continue;

            int needed = grant.amount() - existing;
            ItemStack give = new ItemStack(grant.material(), needed);
            ItemUtil.giveOrDrop(player, give);
        }
        player.updateInventory();
    }

    /**
     * Attempts to equip a deathstreak armor grant into its dedicated slot.
     *
     * <p>The result is tri-state so the caller can distinguish between "equipped",
     * "not armor", and "armor slot already occupied". That last case is configurable:
     * servers can either skip the grant to avoid clutter or spill it into inventory.
     */
    private ArmorGrantResult tryPlaceArmor(org.bukkit.inventory.PlayerInventory inv, ItemStack stack) {
        org.bukkit.Material mat = stack.getType();
        String name = mat.name();

        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET")) {
            return placeArmor(inv.getHelmet(), inv::setHelmet, stack);
        }
        if (name.endsWith("_CHESTPLATE") || mat == org.bukkit.Material.ELYTRA) {
            return placeArmor(inv.getChestplate(), inv::setChestplate, stack);
        }
        if (name.endsWith("_LEGGINGS")) {
            return placeArmor(inv.getLeggings(), inv::setLeggings, stack);
        }
        if (name.endsWith("_BOOTS")) {
            return placeArmor(inv.getBoots(), inv::setBoots, stack);
        }
        return ArmorGrantResult.NOT_ARMOR;
    }

    private ArmorGrantResult placeArmor(
            ItemStack existing,
            java.util.function.Consumer<ItemStack> setter,
            ItemStack stack
    ) {
        if (existing == null || existing.getType().isAir()) {
            setter.accept(stack);
            return ArmorGrantResult.PLACED;
        }
        return ArmorGrantResult.SLOT_OCCUPIED;
    }

    private DeathstreakTier activeDeathstreakTier(MatchSession session, UUID hunterUuid) {
        MatchSession.DeathstreakState state = session.deathstreaks.get(hunterUuid);
        if (state == null) return null;
        return matchManager.getSessionConfig().deathstreakTiers().stream()
                .filter(tier -> state.streakDeaths >= tier.deaths())
                .max(Comparator.comparingInt(DeathstreakTier::deaths))
                .orElse(null);
    }
}
