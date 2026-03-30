package io.github.ganyuke.peoplehunt.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class BukkitSerialization {
    private BukkitSerialization() {}

    public static String serializeItem(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(byteOut)) {
            out.writeObject(itemStack);
            out.flush();
            return Base64.getEncoder().encodeToString(byteOut.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize item stack", exception);
        }
    }

    public static ItemStack deserializeItem(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream in = new BukkitObjectInputStream(byteIn)) {
            Object value = in.readObject();
            return value instanceof ItemStack itemStack ? itemStack : null;
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Unable to deserialize item stack", exception);
        }
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
