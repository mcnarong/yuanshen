package org.yuanshen.yuanshen;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public class XianglingSkillHandler extends AbstractPolearmSkillHandler {

    public XianglingSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.XIANGLING);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.XIANGLING;
    }

    @Override
    public boolean tryCastSkill(Player player, CharacterActionType actionType, LivingEntity directTarget) {
        PlayerSkillData data = PlayerSkillData.get(player.getUniqueId());
        return switch (actionType) {
            case ELEMENTAL_SKILL -> tryCastE(player, data, directTarget);
            case ELEMENTAL_BURST -> tryCastQ(player, data);
            default -> false;
        };
    }

    private boolean tryCastE(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 12000L);
        if (!data.canUseE(CharacterType.XIANGLING, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.XIANGLING, cooldownMs);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[香菱] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.XIANGLING);
        data.addEnergy(CharacterType.XIANGLING, config.getInt("e.energy-gain", 30));
        PlayerStats statSnapshot = plugin.getPlayerStats(player);
        plugin.getXianglingGuobaManager().spawn(player, directTarget, statSnapshot != null ? statSnapshot.copy() : null);

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&6[香菱] 锅巴出击！",
                    messagePlaceholders());
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[香菱] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.XIANGLING)),
                            "max_energy", "100"
                    ));
        }
        return true;
    }

    private boolean tryCastQ(Player player, PlayerSkillData data) {
        if (!config.getBoolean("q.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("q.cooldown-ms", 20000L);
        int energyCost = config.getInt("q.energy-cost", 80);
        if (!data.canUseQ(CharacterType.XIANGLING, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.XIANGLING, cooldownMs);
            if (remain > 0) {
                player.sendMessage(translateColors(config.getString(
                        "messages.q-cooldown",
                        "&c[香菱] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatDurationSeconds(remain))));
            } else {
                player.sendMessage(translateColors(config.getString(
                                "messages.q-no-energy",
                                "&c[香菱] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.XIANGLING)))
                        .replace("{max_energy}", "100")));
            }
            return true;
        }

        data.useQ(CharacterType.XIANGLING, energyCost);
        PlayerStats statSnapshot = plugin.getPlayerStats(player);
        plugin.getXianglingPyronadoManager().activate(player, statSnapshot != null ? statSnapshot.copy() : null);
        if (notificationEnabled("q-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("q-cast"),
                    "&c[香菱] 旋火轮已展开。",
                    messagePlaceholders());
        }
        return true;
    }

    public double applyGuobaDamage(Player owner, LivingEntity target) {
        return applyGuobaDamage(owner, target, null);
    }

    public double applyGuobaDamage(Player owner, LivingEntity target, PlayerStats statSnapshot) {
        double damageMultiplier = config.getDouble("e.damage-multiplier", 0.9);
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(owner, CharacterType.XIANGLING, 1)) {
                damageMultiplier *= 1.15;
            }
            if (plugin.getConstellationManager().hasConstellation(owner, CharacterType.XIANGLING, 3)) {
                damageMultiplier *= 1.20;
            }
        }
        return applySkillDamage(
                owner,
                target,
                config.getString("e.element", ElementConstant.FIRE_KEY),
                config.getInt("e.aura-duration", 120),
                damageMultiplier,
                0.0,
                WeaponAttackType.SKILL,
                "e",
                "e",
                statSnapshot
        );
    }

    public double applyPyronadoDamage(Player owner, LivingEntity target) {
        return applyPyronadoDamage(owner, target, null);
    }

    public double applyPyronadoDamage(Player owner, LivingEntity target, PlayerStats statSnapshot) {
        double damageMultiplier = config.getDouble("q.damage-multiplier", 0.72);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(owner, CharacterType.XIANGLING, 5)) {
            damageMultiplier *= 1.25;
        }
        return applySkillDamage(
                owner,
                target,
                config.getString("q.element", ElementConstant.FIRE_KEY),
                config.getInt("q.aura-duration", 120),
                damageMultiplier,
                0.0,
                WeaponAttackType.BURST,
                "q.spin",
                "q_spin",
                statSnapshot
        );
    }

    public double applyPyronadoOpeningDamage(Player owner, LivingEntity target, int swingIndex) {
        return applyPyronadoOpeningDamage(owner, target, swingIndex, null);
    }

    public double applyPyronadoOpeningDamage(Player owner, LivingEntity target, int swingIndex, PlayerStats statSnapshot) {
        String multiplierPath = switch (swingIndex) {
            case 1 -> "q.opening.swing-1-damage-multiplier";
            case 2 -> "q.opening.swing-2-damage-multiplier";
            default -> "q.opening.swing-3-damage-multiplier";
        };
        double damageMultiplier = config.getDouble(multiplierPath, swingIndex == 3 ? 1.15 : 0.75);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(owner, CharacterType.XIANGLING, 5)) {
            damageMultiplier *= 1.25;
        }
        return applySkillDamage(
                owner,
                target,
                config.getString("q.element", ElementConstant.FIRE_KEY),
                config.getInt("q.aura-duration", 120),
                damageMultiplier,
                0.0,
                WeaponAttackType.BURST,
                "q.opening",
                "q_opening",
                statSnapshot
        );
    }

    @Override
    public void onChargedAttackHit(Player attacker, LivingEntity target) {
        target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().clone().add(0, 1.0, 0), 4, 0.18, 0.18, 0.18, 0.0);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.75f, 1.15f);
    }

    public void tryGrantGuobaParticles(Player owner) {
        if (owner == null || plugin.getEnergyManager() == null) {
            return;
        }
        plugin.getEnergyManager().grantElementalParticles(owner, ElementConstant.FIRE_KEY,
                1, 1, 1.0, 1000L, "xiangling:guoba");
    }
}
