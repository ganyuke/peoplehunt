package io.github.ganyuke.peoplehunt.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ItemUtil {
    private ItemUtil() {}

    public static ItemStack clone(ItemStack item) {
        return item == null ? null : item.clone();
    }

    public static List<ItemStack> cloneAll(Collection<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                out.add(item.clone());
            }
        }
        return out;
    }

    public static int countSimilar(Inventory inventory, ItemStack reference) {
        if (reference == null || reference.getType().isAir()) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (isSimilarEnough(item, reference)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    public static boolean isSimilarEnough(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType().isAir() || b.getType().isAir()) {
            return false;
        }
        return a.isSimilar(b);
    }

    public static void giveOrDrop(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            Location location = player.getLocation();
            for (ItemStack leftover : overflow.values()) {
                location.getWorld().dropItemNaturally(location, leftover);
            }
        }
    }

    public static List<ItemStack> removeUpToMatches(List<ItemStack> drops, List<ItemStack> templates) {
        List<ItemStack> preserved = new ArrayList<>();
        for (ItemStack template : templates) {
            int remaining = template.getAmount();
            for (int i = 0; i < drops.size() && remaining > 0; i++) {
                ItemStack candidate = drops.get(i);
                if (!isSimilarEnough(candidate, template)) {
                    continue;
                }
                int taken = Math.min(candidate.getAmount(), remaining);
                ItemStack copy = candidate.clone();
                copy.setAmount(taken);
                preserved.add(copy);
                remaining -= taken;
                if (candidate.getAmount() == taken) {
                    drops.remove(i--);
                } else {
                    candidate.setAmount(candidate.getAmount() - taken);
                }
            }
        }
        return preserved;
    }
}
