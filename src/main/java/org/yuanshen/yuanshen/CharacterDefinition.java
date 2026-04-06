package org.yuanshen.yuanshen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record CharacterDefinition(
        String id,
        String displayName,
        CharacterType characterType,
        String elementKey,
        CharacterWeaponType weaponType,
        String material,
        int rarity,
        int defaultLevel,
        int maxLevel,
        int defaultConstellation,
        int maxConstellation,
        double baseAttack,
        double baseHealth,
        double baseDefense,
        double attackPercent,
        double healthPercent,
        double defensePercent,
        double critRate,
        double critDamage,
        double energyRecharge,
        double elementalMastery,
        double healingBonus,
        double elementBonus,
        double perConstellationAttackPercent,
        double perConstellationHealthPercent,
        double perConstellationDefensePercent,
        double perConstellationCritRate,
        double perConstellationCritDamage,
        double perConstellationElementBonus,
        long normalComboResetMs,
        String itemMaterial,
        String itemDisplayName,
        int itemCustomModelData,
        List<String> itemLore,
        Set<String> aliases
) {

    public CharacterDefinition {
        itemLore = itemLore == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(itemLore));
        aliases = aliases == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
    }

    public int clampLevel(int level) {
        return Math.max(1, Math.min(level, Math.max(1, maxLevel)));
    }

    public int clampConstellation(int constellation) {
        return Math.max(0, Math.min(constellation, Math.max(0, maxConstellation)));
    }
}
