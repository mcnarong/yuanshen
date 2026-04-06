package org.yuanshen.yuanshen;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface CharacterSkillHandler {

    CharacterType getCharacterType();

    CharacterSkillConfig getConfig();

    boolean tryCastSkill(Player player, CharacterActionType actionType, LivingEntity directTarget);

    default boolean supportsChargedAttack() {
        return false;
    }

    default boolean isChargedAttack(Player attacker, ItemStack handItem) {
        return false;
    }

    default boolean tryConsumeChargedAttack(Player attacker) {
        return false;
    }

    default LivingEntity findChargedAttackTarget(Player attacker) {
        return null;
    }

    default void beginChargedAttack(Player attacker, LivingEntity target) {
        // Default no-op.
    }

    default int getChargedHitWindowTicks() {
        return 1;
    }

    default double getChargedAutoHitRange() {
        return 2.6;
    }

    default double getChargedAutoHitConeDegrees() {
        return 100.0;
    }

    default double getChargedDamageMultiplier(Player attacker) {
        return 1.0;
    }

    default boolean usesChargedPathAttack(Player attacker) {
        return false;
    }

    default boolean handlesChargedAttackInternally() {
        return false;
    }

    default boolean handlesNormalAttackInternally() {
        return false;
    }

    default boolean tryCastNormalAttack(Player attacker) {
        return false;
    }

    default double getChargedPathLength(Player attacker) {
        return getChargedAutoHitRange();
    }

    default double getChargedPathRadius(Player attacker) {
        return 0.9;
    }

    default int getChargedMaxTargets(Player attacker) {
        return 1;
    }

    default double getPlungeDamageBonusMultiplier(Player attacker) {
        return 1.0;
    }

    default String resolveAttackElement(Player attacker, ItemStack handItem, String defaultElement) {
        return defaultElement;
    }

    default double getElementDamageBonusMultiplier(Player attacker, String elementKey) {
        return 1.0;
    }

    default void onChargedAttackHit(Player attacker, LivingEntity target) {
        // Default no-op.
    }

    default void onAttackResolved(Player attacker, LivingEntity target, boolean chargedAttack,
                                  boolean plungeAttack, String attackElement) {
        // Default no-op.
    }
}
