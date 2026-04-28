package io.github.ganyuke.peoplehunt.game;

import com.google.gson.Gson;
import io.github.ganyuke.peoplehunt.util.BukkitSerialization;
import io.github.ganyuke.peoplehunt.util.ItemUtil;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Persists reusable hunter loadouts and reapplies missing kit pieces when a match starts or a
 * hunter respawns in KIT inventory-control mode.
 */
public final class KitService {
    private static final Logger LOGGER = Logger.getLogger(KitService.class.getName());
    private static final long SAVE_DEBOUNCE_MILLIS = 250L;

    private final Path file;
    private final Gson gson;
    private final Map<String, KitDefinition> kits = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final Object saveLock = new Object();
    private Map<String, KitDefinition> pendingSnapshot;
    private ScheduledFuture<?> pendingSave;

    public KitService(Path file, Gson gson) {
        this.file = file;
        this.gson = gson;
        this.executor = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("PeopleHunt-KitStore"));
    }

    public Path path() {
        return file;
    }

    public void load() throws IOException {
        kits.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            KitFile data = gson.fromJson(reader, KitFile.class);
            if (data != null && data.kits != null) {
                kits.putAll(data.kits);
            }
        }
    }

    public void save() throws IOException {
        writeSnapshot(snapshotOfKits());
    }

    public void shutdown() {
        Map<String, KitDefinition> snapshot;
        synchronized (saveLock) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
            snapshot = pendingSnapshot;
            pendingSnapshot = null;
        }
        if (snapshot != null) {
            try {
                writeSnapshot(snapshot);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Failed to flush pending kit data during shutdown", exception);
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public Collection<String> identifiers() {
        return snapshotOfKits().keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }

    public Optional<KitDefinition> get(String identifier) {
        return Optional.ofNullable(kits.get(identifier));
    }

    public KitDefinition saveKit(String identifier, Player player) {
        KitDefinition definition = fromPlayer(identifier, player);
        kits.put(identifier, definition);
        saveAsync();
        return definition;
    }

    public boolean deleteKit(String identifier) {
        KitDefinition removed = kits.remove(identifier);
        saveAsync();
        return removed != null;
    }

    public List<ItemStack> applyMissingKit(Player player, String identifier) {
        KitDefinition definition = kits.get(identifier);
        if (definition == null) {
            return List.of();
        }
        return applyMissingKit(player, definition);
    }

    public List<ItemStack> applyMissingKit(Player player, KitDefinition definition) {
        List<ItemStack> applied = new ArrayList<>();
        if (player == null || definition == null) {
            return applied;
        }
        PlayerInventory inventory = player.getInventory();
        Map<String, Integer> alreadyCounted = new HashMap<>();

        // Reapplication is deliberately idempotent. The method only fills missing kit pieces so it
        // can be safely called both on match start and on later respawns without duplicating items
        // a player already retained.
        for (KitSlot slot : definition.slots()) {
            ItemStack template = slot.toItem();
            if (template == null || template.getType().isAir()) {
                continue;
            }
            String key = BukkitSerialization.serializeItem(template);
            int existing = ItemUtil.countSimilar(inventory, template) - alreadyCounted.getOrDefault(key, 0);
            if (existing >= template.getAmount()) {
                alreadyCounted.put(key, alreadyCounted.getOrDefault(key, 0) + template.getAmount());
                continue;
            }
            int remaining = template.getAmount() - Math.max(existing, 0);
            alreadyCounted.put(key, alreadyCounted.getOrDefault(key, 0) + Math.max(existing, 0));
            ItemStack give = template.clone();
            give.setAmount(remaining);
            if (slot.tryPlace(inventory, give.clone())) {
                applied.add(give);
            } else if (!slot.isDedicatedEquipmentSlot()) {
                ItemUtil.giveOrDrop(player, give.clone());
                applied.add(give);
            }
        }
        player.updateInventory();
        return applied;
    }

    public List<ItemStack> templateItems(String identifier) {
        KitDefinition definition = kits.get(identifier);
        return definition == null ? List.of() : definition.allItems();
    }

    private void saveAsync() {
        synchronized (saveLock) {
            pendingSnapshot = snapshotOfKits();
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = executor.schedule(this::flushPendingAsync, SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushPendingAsync() {
        Map<String, KitDefinition> snapshot;
        synchronized (saveLock) {
            snapshot = pendingSnapshot;
            pendingSnapshot = null;
            pendingSave = null;
        }
        if (snapshot == null) {
            return;
        }
        try {
            writeSnapshot(snapshot);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to persist kit data asynchronously", exception);
        }
    }

    private Map<String, KitDefinition> snapshotOfKits() {
        return new LinkedHashMap<>(kits);
    }

    private void writeSnapshot(Map<String, KitDefinition> snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(new KitFile(snapshot), writer);
        }
    }

    private static ThreadFactory namedThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static KitDefinition fromPlayer(String identifier, Player player) {
        // A saved kit is a full snapshot of visible inventory layout, including armor and offhand,
        // so later reapplication can preserve the intended slot arrangement.
        List<KitSlot> slots = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                slots.add(KitSlot.storage(i, contents[i]));
            }
        }
        if (inventory.getHelmet() != null && !inventory.getHelmet().getType().isAir()) {
            slots.add(KitSlot.armor("HEAD", inventory.getHelmet()));
        }
        if (inventory.getChestplate() != null && !inventory.getChestplate().getType().isAir()) {
            slots.add(KitSlot.armor("CHEST", inventory.getChestplate()));
        }
        if (inventory.getLeggings() != null && !inventory.getLeggings().getType().isAir()) {
            slots.add(KitSlot.armor("LEGS", inventory.getLeggings()));
        }
        if (inventory.getBoots() != null && !inventory.getBoots().getType().isAir()) {
            slots.add(KitSlot.armor("FEET", inventory.getBoots()));
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (!offHand.getType().isAir()) {
            slots.add(KitSlot.offhand(offHand));
        }
        return new KitDefinition(identifier, List.copyOf(slots));
    }

    private record KitFile(Map<String, KitDefinition> kits) {}

    public record KitDefinition(String identifier, List<KitSlot> slots) {
        public List<ItemStack> allItems() {
            List<ItemStack> items = new ArrayList<>();
            for (KitSlot slot : slots) {
                ItemStack item = slot.toItem();
                if (item != null && !item.getType().isAir()) {
                    items.add(item);
                }
            }
            return items;
        }
    }

    public record KitSlot(String kind, int slotIndex, String itemData) {
        public static KitSlot storage(int slotIndex, ItemStack item) {
            return new KitSlot("STORAGE", slotIndex, BukkitSerialization.serializeItem(item));
        }

        public static KitSlot armor(String armorKind, ItemStack item) {
            return new KitSlot("ARMOR_" + armorKind, -1, BukkitSerialization.serializeItem(item));
        }

        public static KitSlot offhand(ItemStack item) {
            return new KitSlot("OFFHAND", -1, BukkitSerialization.serializeItem(item));
        }

        public ItemStack toItem() {
            return BukkitSerialization.deserializeItem(itemData);
        }

        public boolean isDedicatedEquipmentSlot() {
            return kind.startsWith("ARMOR_") || kind.equals("OFFHAND");
        }

        public boolean tryPlace(PlayerInventory inventory, ItemStack stack) {
            if (stack == null || stack.getType() == Material.AIR) {
                return true;
            }
            return switch (kind) {
                case "STORAGE" -> placeStorage(inventory, stack);
                case "ARMOR_HEAD" -> placeArmor(inventory::getHelmet, inventory::setHelmet, stack);
                case "ARMOR_CHEST" -> placeArmor(inventory::getChestplate, inventory::setChestplate, stack);
                case "ARMOR_LEGS" -> placeArmor(inventory::getLeggings, inventory::setLeggings, stack);
                case "ARMOR_FEET" -> placeArmor(inventory::getBoots, inventory::setBoots, stack);
                case "OFFHAND" -> placeArmor(inventory::getItemInOffHand, inventory::setItemInOffHand, stack);
                default -> false;
            };
        }

        private boolean placeStorage(PlayerInventory inventory, ItemStack stack) {
            ItemStack existing = inventory.getStorageContents()[slotIndex];
            if (existing == null || existing.getType().isAir()) {
                inventory.setItem(slotIndex, stack);
                return true;
            }
            return false;
        }

        private boolean placeArmor(java.util.function.Supplier<ItemStack> getter,
                                   java.util.function.Consumer<ItemStack> setter,
                                   ItemStack stack) {
            ItemStack existing = getter.get();
            if (existing == null || existing.getType().isAir()) {
                setter.accept(stack);
                return true;
            }
            return false;
        }
    }
}
