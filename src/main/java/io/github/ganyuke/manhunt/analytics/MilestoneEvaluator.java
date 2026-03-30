package io.github.ganyuke.manhunt.analytics;

import org.bukkit.entity.Player;

import java.util.Map;

public interface MilestoneEvaluator {
    boolean isSatisfied(MilestoneDefinition definition, Player player);

    Map<String, Object> values(MilestoneDefinition definition, Player player);
}
