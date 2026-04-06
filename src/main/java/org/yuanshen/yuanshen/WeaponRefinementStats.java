package org.yuanshen.yuanshen;

public record WeaponRefinementStats(
        double allElementDamageBonus,
        double critRateBonus,
        double normalDamageBonus,
        double chargedDamageBonus,
        double procDamageMultiplier,
        double stackAttackBonus,
        double fullStackDamageBonus
) {

    public static final WeaponRefinementStats EMPTY = new WeaponRefinementStats(
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0
    );
}
