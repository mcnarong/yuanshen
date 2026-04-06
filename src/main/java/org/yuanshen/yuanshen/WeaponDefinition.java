package org.yuanshen.yuanshen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record WeaponDefinition(
        String id,
        String displayName,
        CharacterWeaponType weaponType,
        String material,
        int rarity,
        int defaultLevel,
        int maxLevel,
        int defaultRefinement,
        int maxRefinement,
        double baseAttack,
        double attackPercent,
        double critRate,
        double critDamage,
        double energyRecharge,
        double elementalMastery,
        double allElementDamageBonus,
        String mainStatKey,
        String subStatKey,
        Map<Integer, Map<String, Double>> levelStats,
        Map<String, Double> bonusStats,
        String passiveId,
        double hpThreshold,
        double procChance,
        long procCooldownMs,
        double procRadius,
        int procDurationTicks,
        int procHits,
        int maxStacks,
        long stackDurationMs,
        long stackIntervalMs,
        Map<Integer, WeaponRefinementStats> refinementLevels,
        String itemMaterial,
        String itemDisplayName,
        int itemCustomModelData,
        List<String> itemLore
) {

    public WeaponDefinition {
        itemLore = itemLore == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(itemLore));
    }

    public int clampLevel(int level) {
        return Math.max(1, Math.min(level, Math.max(1, maxLevel)));
    }

    public int clampRefinement(int refinement) {
        return Math.max(1, Math.min(refinement, Math.max(1, maxRefinement)));
    }

    public WeaponRefinementStats getRefinementStats(int refinement) {
        if (refinementLevels == null || refinementLevels.isEmpty()) {
            return WeaponRefinementStats.EMPTY;
        }

        int clamped = clampRefinement(refinement);
        for (int value = clamped; value >= 1; value--) {
            WeaponRefinementStats stats = refinementLevels.get(value);
            if (stats != null) {
                return stats;
            }
        }
        for (int value = clamped + 1; value <= maxRefinement; value++) {
            WeaponRefinementStats stats = refinementLevels.get(value);
            if (stats != null) {
                return stats;
            }
        }
        return WeaponRefinementStats.EMPTY;
    }
}
