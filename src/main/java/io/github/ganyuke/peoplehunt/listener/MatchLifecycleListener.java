package io.github.ganyuke.peoplehunt.listener;

import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig;
import io.github.ganyuke.peoplehunt.config.PeopleHuntConfig.DeathstreakTier;
import io.github.ganyuke.peoplehunt.game.compass.CompassService;
import io.github.ganyuke.peoplehunt.game.KeepInventoryMode;
import io.github.ganyuke.peoplehunt.game.KitService;
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

public class MatchLifecycleListener implements Listener {
    private final JavaPlugin plugin;
    private final PeopleHuntConfig config;
    private final MatchManager matchManager;
    private final KitService kitService;
    private final CompassService compassService;
    private final ReportService reportService;

    public MatchLifecycleListener(JavaPlugin plugin, PeopleHuntConfig config, MatchManager matchManager, KitService kitService, CompassService compassService, ReportService reportService) {
        this.plugin = plugin;
        this.config = config;
        this.matchManager = matchManager;
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
                List<ItemStack> restore = session.pendingRespawnRestore.remove(player.getUniqueId());
                if (restore != null) {
                    for (ItemStack item : restore) ItemUtil.giveOrDrop(player, item);
                }
                if (session.activeKitId != null) kitService.applyMissingKit(player, session.activeKitId);
                applyDeathstreakRewards(session, player);
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
        if (matchManager.roleOf(player.getUniqueId()) == Role.HUNTER) {
            KeepInventoryMode mode = effectiveKeepInventoryMode(session, player.getUniqueId());
            if (mode == KeepInventoryMode.ALL) {
                event.setKeepInventory(true);
                event.getDrops().clear();
                event.setKeepLevel(true);
                event.setDroppedExp(0);
            } else if (mode == KeepInventoryMode.KIT && session.activeKitId != null) {
                List<ItemStack> preserved = ItemUtil.removeUpToMatches(event.getDrops(), kitService.templateItems(session.activeKitId));
                if (!preserved.isEmpty()) {
                    session.pendingRespawnRestore.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).addAll(ItemUtil.cloneAll(preserved));
                }
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
            MatchSession.DeathstreakState state = session.deathstreaks.computeIfAbsent(player.getUniqueId(), ignored -> new MatchSession.DeathstreakState());
            state.streakDeaths++;
            state.damageThisLife = 0.0;
        }
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

        session.pendingPortalPrompt.put(player.getUniqueId(), portal.clone());
        Component prompt = Component.text("Teleport to the runner's last End Portal", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/peoplehunt portal"))
                .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + io.github.ganyuke.peoplehunt.util.Text.coord(portal.getX(), portal.getY(), portal.getZ()), NamedTextColor.GRAY)));
        player.sendMessage(prompt);
    }

    private void applyDeathstreakRewards(MatchSession session, Player player) {
        if (!config.deathstreaksEnabled()) return;
        DeathstreakTier tier = activeDeathstreakTier(session, player.getUniqueId());
        if (tier == null) return;

        player.setFoodLevel(Math.max(player.getFoodLevel(), tier.saturationBoost().foodLevel()));
        player.setSaturation(Math.max(player.getSaturation(), tier.saturationBoost().saturation()));
        for (var potion : tier.potionGrants()) player.addPotionEffect(new PotionEffect(potion.type(), potion.durationSeconds() * 20, potion.amplifier(), true, true, true));
        for (var item : tier.itemGrants()) {
            int existing = ItemUtil.countSimilar(player.getInventory(), new ItemStack(item.material(), item.amount()));
            if (existing >= item.amount()) continue;
            ItemUtil.giveOrDrop(player, new ItemStack(item.material(), item.amount() - existing));
        }
    }

    private DeathstreakTier activeDeathstreakTier(MatchSession session, UUID hunterUuid) {
        MatchSession.DeathstreakState state = session.deathstreaks.get(hunterUuid);
        if (state == null) return null;
        return config.deathstreakTiers().stream()
                .filter(tier -> state.streakDeaths >= tier.deaths())
                .max(Comparator.comparingInt(DeathstreakTier::deaths))
                .orElse(null);
    }

    public KeepInventoryMode effectiveKeepInventoryMode(MatchSession session, UUID hunterUuid) {
        DeathstreakTier tier = activeDeathstreakTier(session, hunterUuid);
        return tier != null ? tier.keepInventoryMode().resolve(session.keepInventoryMode) : session.keepInventoryMode;
    }
}