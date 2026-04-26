package io.github.ganyuke.peoplehunt.util;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public final class BukkitSerialization {
    private BukkitSerialization() {}

    public static String serializeItem(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
    }

    public static ItemStack deserializeItem(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    public static List<String> serializeItems(List<ItemStack> items) {
        List<String> out = new ArrayList<>();
        for (ItemStack item : items) {
            out.add(serializeItem(item));
        }
        return out;
    }

    public static List<ItemStack> deserializeItems(List<String> items) {
        List<ItemStack> out = new ArrayList<>();
        for (String item : items) {
            ItemStack stack = deserializeItem(item);
            if (stack != null) {
                out.add(stack);
            }
        }
        return out;
    }
}
