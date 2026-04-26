package io.github.ganyuke.peoplehunt.util;

import io.github.ganyuke.peoplehunt.report.ReportModels.EffectState;
import io.github.ganyuke.peoplehunt.report.ReportModels.InventoryEnchant;
import io.github.ganyuke.peoplehunt.report.ReportModels.InventoryItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public final class SnapshotUtil {
    private SnapshotUtil() {}

    public static List<InventoryItem> inventory(Player player) {
        List<InventoryItem> items = new ArrayList<>();
        if (player == null) {
            return items;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            appendItem(items, slot, stack);
        }
        appendItem(items, 100, player.getInventory().getHelmet());
        appendItem(items, 101, player.getInventory().getChestplate());
        appendItem(items, 102, player.getInventory().getLeggings());
        appendItem(items, 103, player.getInventory().getBoots());
        appendItem(items, 150, player.getInventory().getItemInOffHand());
        items.sort(Comparator.comparingInt(InventoryItem::slot));
        return items;
    }

    private static void appendItem(List<InventoryItem> items, int slot, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        Material material = stack.getType();
        String rawId = material.getKey().asString();
        String prettyName = itemPrettyName(stack);
        Map<?, ?> rawEnchantments = stack.getEnchantments();
        boolean enchanted = rawEnchantments != null && !rawEnchantments.isEmpty();
        String textColor = enchanted ? "#ff55ff" : materialTextColor(material);
        List<InventoryEnchant> enchantments = stack.getEnchantments().entrySet().stream()
                .map(entry -> new InventoryEnchant(
                        entry.getKey().getKey().asString(),
                        PrettyNames.key(entry.getKey().getKey().asString()),
                        entry.getValue()
                ))
                .sorted(Comparator.comparing(InventoryEnchant::prettyName))
                .toList();
        items.add(new InventoryItem(
                slot,
                rawId,
                prettyName,
                stack.getAmount(),
                enchanted,
                textColor,
                BukkitSerialization.serializeItem(stack),
                enchantments
        ));
    }

    public static List<EffectState> effects(Player player) {
        List<EffectState> effects = new ArrayList<>();
        if (player == null) {
            return effects;
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            String raw = effect.getType().getKey().asString();
            effects.add(new EffectState(
                    raw,
                    PrettyNames.key(raw),
                    effect.getAmplifier(),
                    effect.getDuration(),
                    effect.isAmbient()
            ));
        }
        effects.sort(Comparator.comparing(EffectState::prettyName));
        return effects;
    }

    public static String itemPrettyName(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "Air";
        }
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return Text.plain(stack.getItemMeta().displayName());
        }
        return PrettyNames.key(stack.getType().getKey().asString());
    }

    public static String materialTextColor(Material material) {
        String name = material == null ? "" : material.name();
        if (name.contains("NETHERITE")) return "#5c6b73";
        if (name.contains("DIAMOND")) return "#55ffff";
        if (name.contains("GOLD") || name.contains("GOLDEN")) return "#ffaa00";
        if (name.contains("IRON")) return "#d8d8d8";
        if (name.contains("CHAINMAIL")) return "#aaaaaa";
        if (name.contains("LEATHER")) return "#b67c52";
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK")) return "#c88b3a";
        if (name.contains("ENCHANTED")) return "#ff55ff";
        return "#ffffff";
    }
}
