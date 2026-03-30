package io.github.ganyuke.manhunt.evaluator;

import io.github.ganyuke.manhunt.analytics.MilestoneDefinition;
import io.github.ganyuke.manhunt.analytics.MilestoneEvaluator;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InventoryAnyMilestoneEvaluator implements MilestoneEvaluator {
    @Override
    public boolean isSatisfied(MilestoneDefinition definition, Player player) {
        return total(player, definition) >= definition.amount();
    }

    @Override
    public Map<String, Object> values(MilestoneDefinition definition, Player player) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("matchedAmount", total(player, definition));
        values.put("requiredAmount", definition.amount());
        return values;
    }

    private int total(Player player, MilestoneDefinition definition) {
        int amount = 0;
        for (ItemStack itemStack : player.getInventory().getContents()) {
            if (itemStack == null) {
                continue;
            }
            Material material = itemStack.getType();
            if (definition.materials().contains(material)) {
                amount += itemStack.getAmount();
            }
        }
        return amount;
    }
}
