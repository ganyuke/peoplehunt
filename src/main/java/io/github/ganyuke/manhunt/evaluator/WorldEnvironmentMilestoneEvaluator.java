package io.github.ganyuke.manhunt.evaluator;

import io.github.ganyuke.manhunt.analytics.MilestoneDefinition;
import io.github.ganyuke.manhunt.analytics.MilestoneEvaluator;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldEnvironmentMilestoneEvaluator implements MilestoneEvaluator {
    @Override
    public boolean isSatisfied(MilestoneDefinition definition, Player player) {
        return player.getWorld().getEnvironment() == definition.environment();
    }

    @Override
    public Map<String, Object> values(MilestoneDefinition definition, Player player) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("environment", player.getWorld().getEnvironment().name());
        return values;
    }
}
