package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.KitService;
import io.github.ganyuke.peoplehunt.game.match.AttributionManager;
import io.github.ganyuke.peoplehunt.game.match.MatchManager;
import io.github.ganyuke.peoplehunt.game.match.MatchSession;
import io.github.ganyuke.peoplehunt.game.Role;
import io.github.ganyuke.peoplehunt.report.ReportService;
import io.github.ganyuke.peoplehunt.util.ItemUtil;
import java.io.IOException;
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
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final AttributionManager attributionManager;
    private final KitService kitService;
    private final CompassService compassService;
    private final ReportService reportService;

    public MatchLifecycleListener(JavaPlugin plugin, PeopleHuntConfig config, MatchManager matchManager, AttributionManager attributionManager, KitService kitService, CompassService compassService, ReportService reportService) {
        this.plugin = plugin;
        this.config = config;
        this.matchManager = matchManager;
        this.attributionManager = attributionManager;
        this.kitService = kitService;
        this.compassService = compassService;
        this.reportService = reportService;
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
                else if (config.autoSpectateNewJoins()) player.setGameMode(GameMode.SPECTATOR);

                if (role == Role.HUNTER) compassService.giveCompass(List.of(player));
            });
            return;
        }

        session.roles.put(uuid, Role.SPECTATOR);
        session.spectatorIds.add(uuid);
        session.lifeIndex.put(uuid, 1);
        reportService.registerParticipant(uuid, player.getName(), Role.SPECTATOR.name(), true, true);
        reportService.recordTimeline(uuid, player.getName(), "participant", "joined as spectator");
        if (config.autoSpectateNewJoins()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.setGameMode(GameMode.SPECTATOR));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            MatchSession session = matchManager.getSession();
            if (session == null || !matchManager.isParticipant(player.getUniqueId())) return;

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
            } else if (config.autoSpectateNewJoins()) {
                player.setGameMode(GameMode.SPECTATOR);
            }
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
            try {
                matchManager.endHunterVictory();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
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

        return switch (config.deathstreakAttributionMode()) {
            case UUID_STRICT    -> byUuid;
            case MESSAGE_STRICT -> deathMessageContainsRunner(session, event);
            case EITHER         -> byUuid || deathMessageContainsRunner(session, event);
        };
    }

    private boolean deathMessageContainsRunner(MatchSession session, PlayerDeathEvent event) {
        if (event.deathMessage() == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        String runnerName = matchManager.nameOf(session.runnerUuid);
        return plain.contains(runnerName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (matchManager.getSession() != null && event.getEntity() instanceof EnderDragon) {
            try {
                matchManager.endRunnerVictory();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
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
        if (!config.deathstreaksEnabled()) return;
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

            // Attempt to place armor into the correct slot first
            if (tryPlaceArmor(inv, stack)) continue;

            // For non-armor (tools, etc.) only give if none already present
            int existing = ItemUtil.countSimilar(inv, stack);
            if (existing >= grant.amount()) continue;

            int needed = grant.amount() - existing;
            ItemStack give = new ItemStack(grant.material(), needed);
            ItemUtil.giveOrDrop(player, give);
        }
        player.updateInventory();
    }

    /**
     * If the given item is an armor piece and its corresponding armor slot is empty,
     * places it there and returns {@code true}. Returns {@code false} for non-armor
     * items or when the slot is already occupied.
     */
    private boolean tryPlaceArmor(org.bukkit.inventory.PlayerInventory inv, ItemStack stack) {
        org.bukkit.Material mat = stack.getType();
        String name = mat.name();

        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET")) {
            if (inv.getHelmet() == null || inv.getHelmet().getType().isAir()) {
                inv.setHelmet(stack);
                return true;
            }
            return true; // slot occupied — still consumed (don't double-give)
        }
        if (name.endsWith("_CHESTPLATE") || mat == org.bukkit.Material.ELYTRA) {
            if (inv.getChestplate() == null || inv.getChestplate().getType().isAir()) {
                inv.setChestplate(stack);
                return true;
            }
            return true;
        }
        if (name.endsWith("_LEGGINGS")) {
            if (inv.getLeggings() == null || inv.getLeggings().getType().isAir()) {
                inv.setLeggings(stack);
                return true;
            }
            return true;
        }
        if (name.endsWith("_BOOTS")) {
            if (inv.getBoots() == null || inv.getBoots().getType().isAir()) {
                inv.setBoots(stack);
                return true;
            }
            return true;
        }
        return false; // not an armor piece
    }

    private DeathstreakTier activeDeathstreakTier(MatchSession session, UUID hunterUuid) {
        MatchSession.DeathstreakState state = session.deathstreaks.get(hunterUuid);
        if (state == null) return null;
        return config.deathstreakTiers().stream()
                .filter(tier -> state.streakDeaths >= tier.deaths())
                .max(Comparator.comparingInt(DeathstreakTier::deaths))
                .orElse(null);
    }
}
