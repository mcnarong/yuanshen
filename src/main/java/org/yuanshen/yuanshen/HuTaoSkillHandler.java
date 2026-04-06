package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.LinkedHashMap;
import java.util.Map;

public class HuTaoSkillHandler extends AbstractPolearmSkillHandler {

    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";

    public HuTaoSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.HUTAO);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.HUTAO;
    }

    @Override
    public boolean tryCastSkill(Player player, CharacterActionType actionType, LivingEntity directTarget) {
        PlayerSkillData data = PlayerSkillData.get(player.getUniqueId());

        if (actionType == CharacterActionType.ELEMENTAL_BURST) {
            return tryCastQ(player, data);
        }
        if (actionType == CharacterActionType.ELEMENTAL_SKILL) {
            return tryCastE(player, data);
        }
        return false;
    }

    private boolean tryCastE(Player player, PlayerSkillData data) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 16000);
        if (!data.canUseE(CharacterType.HUTAO, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.HUTAO, cooldownMs);
            player.sendMessage(color(config.getString(
                    "messages.e-cooldown",
                    "&c[胡桃] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.HUTAO);
        plugin.getHuTaoStateManager().activate(player);

        int energyGain = config.getInt("e.energy-gain", 60);
        data.addEnergy(CharacterType.HUTAO, energyGain);

        if (isNotificationEnabled("energy-after-e", true)) {
            sendConfiguredMessage(player,
                    config.messagePath("energy"),
                    "&7[胡桃] 当前能量：&f{energy}&7/&f{max_energy}",
                    placeholders("energy", String.valueOf(data.getEnergy(CharacterType.HUTAO)), "max_energy", "100"));
        }
        return true;
    }

    private boolean tryCastQ(Player player, PlayerSkillData data) {
        if (!config.getBoolean("q.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("q.cooldown-ms", 20000);
        int energyCost = config.getInt("q.energy-cost", 100);

        if (!data.canUseQ(CharacterType.HUTAO, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.HUTAO, cooldownMs);
            if (remain > 0) {
                player.sendMessage(color(config.getString(
                        "messages.q-cooldown",
                        "&c[胡桃] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatSeconds(remain))));
            } else {
                player.sendMessage(color(config.getString(
                                "messages.q-no-energy",
                                "&c[胡桃] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.HUTAO)))
                        .replace("{max_energy}", "100")));
            }
            return true;
        }

        plugin.suppressChargedAttack(player, 400L);
        data.useQ(CharacterType.HUTAO, energyCost);
        castQ(player);
        return true;
    }

    private void castQ(Player player) {
        double radius = config.getDouble("q.radius", 5.0);
        String element = config.getString("q.element", ElementConstant.FIRE_KEY);
        int auraDuration = config.getInt("q.aura-duration", 200);
        double damageMultiplier = config.getDouble("q.damage-multiplier", 1.0);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.HUTAO, 5)) {
            damageMultiplier *= 1.25;
        }
        double lowHpDamageBonus = config.getDouble("q.low-hp-damage-bonus", 0.33);
        double healPercentPerTarget = config.getDouble("q.heal-percent-per-target", 0.04);
        double lowHpHealBonus = config.getDouble("q.low-hp-heal-bonus", 0.25);
        int maxHealTargets = config.getInt("q.max-heal-targets", 5);
        boolean lowHp = plugin.getHuTaoStateManager().isLowHp(player);

        playHuTaoQCastEffects(player, lowHp);
        if (isNotificationEnabled("q-cast", true)) {
            sendConfiguredMessage(player,
                    config.messagePath("q-cast"),
                    "&c[胡桃] 释放元素爆发。",
                    placeholders());
        }

        int hitCount = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(player)) {
                continue;
            }
            applyElementSkillDamage(player, target, element, auraDuration, damageMultiplier, lowHp ? lowHpDamageBonus : 0.0);
            playHuTaoQImpactEffects(player, target, lowHp);
            hitCount++;
        }

        int effectiveHealTargets = Math.min(hitCount, maxHealTargets);
        double actualHeal = 0.0;
        if (effectiveHealTargets > 0) {
            double maxHealth = plugin.getHuTaoStateManager().getMaxHealth(player);
            double totalHeal = maxHealth * healPercentPerTarget * effectiveHealTargets;
            if (lowHp) {
                totalHeal *= (1.0 + lowHpHealBonus);
            }

            double beforeHealth = player.getHealth();
            double newHealth = Math.min(maxHealth, beforeHealth + totalHeal);
            double appliedHealth = plugin.setPlayerHealthSafely(player, newHealth, true);
            actualHeal = Math.max(0.0, appliedHealth - beforeHealth);
            playHuTaoQHealEffects(player, actualHeal, lowHp);
        }

        if (isNotificationEnabled("q-summary", true)) {
            sendConfiguredMessage(player,
                    config.messagePath("q-summary"),
                    "&c[胡桃] 元素爆发命中 &f{count}&c 个目标，恢复了 &a{heal}&c 点生命值。",
                    placeholders("count", String.valueOf(hitCount), "heal", ElementUtils.formatDamage(actualHeal)));
        } else {
            if (isNotificationEnabled("q-hit-count", false)) {
                sendConfiguredMessage(player,
                        config.messagePath("q-hit-count"),
                        "&7[胡桃] 元素爆发命中目标数：&f{count}",
                        placeholders("count", String.valueOf(hitCount)));
            }
            if (actualHeal > 0 && isNotificationEnabled("q-heal", false)) {
                sendConfiguredMessage(player,
                        config.messagePath("q-heal"),
                        "&a[胡桃] 元素爆发恢复了 &f{heal}&a 点生命值。",
                        placeholders("heal", ElementUtils.formatDamage(actualHeal)));
            }
        }
    }

    private void applyElementSkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                         double damageMultiplier, double extraBonusMultiplier) {
        DamageResult damageResult = plugin.getDamageCalculator()
                .calculateElementDamage(caster, target, element, caster.getInventory().getItemInMainHand());
        if (damageResult == null) {
            caster.sendMessage(ChatColor.RED + "技能伤害计算失败。");
            return;
        }

        ElementReactionManager.AttachmentProfile attachmentProfile = buildReactionAttachmentProfile(
                caster,
                "q",
                WeaponAttackType.BURST,
                "q"
        );
        ReactionResult reactionResult = plugin.getReactionManager().handleReaction(
                caster,
                target,
                element,
                damageResult,
                attachmentProfile
        );
        if (reactionResult == null) {
            caster.sendMessage(ChatColor.RED + "元素反应处理失败。");
            return;
        }

        double attackScaledDamage = damageResult.getRawTotalDamage() + reactionResult.getScalingDamageBonus();
        if (damageMultiplier != 1.0) {
            attackScaledDamage *= damageMultiplier;
        }
        if (extraBonusMultiplier > 0.0) {
            attackScaledDamage *= (1.0 + extraBonusMultiplier);
        }

        double preResistanceMainDamage = attackScaledDamage + reactionResult.getAdditiveDamageBonus();
        preResistanceMainDamage *= plugin.getHuTaoStateManager().getHuTaoDamageBonusMultiplier(caster, element);
        preResistanceMainDamage *= plugin.getGlobalDamageBonusMultiplier(caster, element);

        if (damageResult.isCrit() && damageResult.getPlayerStats() != null) {
            PlayerStats stats = damageResult.getPlayerStats();
            preResistanceMainDamage *= (1 + stats.getCritDamage());
        }

        double finalMainDamage = plugin.applyMobResistance(target, preResistanceMainDamage, element);
        target.setNoDamageTicks(0);
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, caster.getUniqueId().toString()));
        plugin.getReactionManager().markIncomingDamageElement(target, element);
        target.damage(finalMainDamage, caster);
        double appliedTransformativeDamage = plugin.getReactionManager()
                .applyTransformativeReactionDamage(caster, target, reactionResult);

        if (plugin.isDamageDisplayEnabled(caster)) {
            double totalDamage = finalMainDamage + appliedTransformativeDamage;
            double displayedReactionDamage = plugin.getReactionManager().calculateDisplayedReactionDamage(
                    reactionResult,
                    preResistanceMainDamage,
                    finalMainDamage,
                    appliedTransformativeDamage
            );
            plugin.getElementUtils().sendDamageResult(caster, reactionResult, totalDamage, displayedReactionDamage);
            if (damageResult.isCrit()) {
                caster.sendMessage("§6【暴击】§e技能暴击！伤害：§c" + ElementUtils.formatDamage(totalDamage));
            }
        }

        plugin.getReactionManager().consumeReactionAuras(target, reactionResult, element);
        plugin.getReactionManager().applyTriggerAura(target, element, reactionResult, auraDuration,
                getReactionAuraAmount("q", WeaponAttackType.BURST));
        plugin.getReactionManager().postProcessReactionState(target, reactionResult);
        if (plugin.getReactionManager().shouldTriggerSeedReaction(target, element, reactionResult)) {
            plugin.getReactionManager().triggerSeedReaction(caster, target.getLocation(), element);
        }

        if (plugin.isDamageDisplayEnabled(caster)) {
            caster.sendMessage("§7已对 " + target.getName() + " 附着元素：" + element.replace("element_", ""));
        }
    }

    @Override
    public boolean supportsChargedAttack() {
        return super.supportsChargedAttack();
    }

    @Override
    public boolean isChargedAttack(Player attacker, ItemStack handItem) {
        return super.isChargedAttack(attacker, handItem);
    }

    @Override
    public boolean tryConsumeChargedAttack(Player attacker) {
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(attacker, CharacterType.HUTAO, 1)
                && plugin.getHuTaoStateManager().isActive(attacker)) {
            long remain = Math.max(0L, getChargedCooldownEnd(attacker) - System.currentTimeMillis());
            if (remain > 0L) {
                String msg = config.getString("messages.charged-cooldown", "&c[胡桃] 重击冷却中，还剩 {time} 秒。");
                attacker.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        msg.replace("{time}", String.format("%.1f", remain / 1000.0))));
                return false;
            }
            restoreChargedCooldown(attacker,
                    System.currentTimeMillis() + config.getLong("charged.cooldown-ms", 1000L));
            return true;
        }
        return super.tryConsumeChargedAttack(attacker);/*
        long remain = getRemainingChargedCooldown(attacker);
        if (remain > 0) {
            String msg = config.getString("messages.charged-cooldown", "&c[胡桃] 重击冷却中，还剩 {time} 秒。");
            attacker.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    msg.replace("{time}", String.format("%.1f", remain / 1000.0))));
            return false;
        }

        int hungerCost = config.getInt("charged.hunger-cost", 2);
        if (attacker.getFoodLevel() < hungerCost) {
            String msg = config.getString("messages.charged-no-hunger", "&c[胡桃] 饥饿值不足，无法施放重击。");
            attacker.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return false;
        }

        attacker.setFoodLevel(Math.max(0, attacker.getFoodLevel() - hungerCost));
        chargedAttackCooldowns.put(attacker.getUniqueId(),
                System.currentTimeMillis() + config.getLong("charged.cooldown-ms", 1000L));
        return true;
        */
    }

    @Override
    public LivingEntity findChargedAttackTarget(Player attacker) {
        return super.findChargedAttackTarget(attacker);/*
        if (!config.getBoolean("charged.targeting.enabled", true)) {
            return null;
        }

        double maxDistance = config.getDouble("charged.targeting.max-distance", 5.0);
        double minDistance = config.getDouble("charged.targeting.min-distance-for-raycast", 3.2);
        double hitboxExpand = config.getDouble("charged.targeting.hitbox-expand", 0.45);

        RayTraceResult result = attacker.getWorld().rayTrace(
                attacker.getEyeLocation(),
                attacker.getEyeLocation().getDirection(),
                maxDistance,
                FluidCollisionMode.NEVER,
                true,
                hitboxExpand,
                entity -> entity instanceof LivingEntity living
                        && !living.equals(attacker)
                        && living.isValid()
                        && !living.isDead()
        );

        if (result == null || !(result.getHitEntity() instanceof LivingEntity target)) {
            return null;
        }
        if (!attacker.hasLineOfSight(target)) {
            return null;
        }

        double distance = attacker.getLocation().distance(target.getLocation());
        if (distance <= minDistance || distance > maxDistance) {
            return null;
        }
        return target;
        */
    }

    @Override
    public void beginChargedAttack(Player attacker, LivingEntity target) {
        super.beginChargedAttack(attacker, target);/*
        if (!config.getBoolean("charged.dash.enabled", true)) {
            return;
        }

        Vector direction = null;
        double dashStrength = config.getDouble("charged.dash.strength", 0.65);

        if (target != null
                && config.getBoolean("charged.dash.direct-to-target", true)
                && target.isValid()
                && !target.isDead()) {
            double directRange = config.getDouble("charged.dash.direct-range", 5.0);
            double stopDistance = config.getDouble("charged.dash.stop-distance", 1.1);
            double distanceMultiplier = config.getDouble("charged.dash.distance-multiplier", 0.42);
            double targetAssistRatio = config.getDouble("charged.dash.target-assist-ratio", 0.35);

            Vector lookDirection = attacker.getLocation().getDirection().clone().setY(0);
            Vector toTarget = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
            double distance = toTarget.length();
            if (distance > 0.0001 && distance <= directRange && attacker.hasLineOfSight(target)) {
                Vector targetDirection = toTarget.clone().setY(0).normalize();
                if (lookDirection.lengthSquared() > 0.0001) {
                    direction = lookDirection.normalize().multiply(1.0 - targetAssistRatio)
                            .add(targetDirection.multiply(targetAssistRatio));
                } else {
                    direction = targetDirection;
                }
                double scaledStrength = Math.max(dashStrength, Math.max(0.1, distance - stopDistance) * distanceMultiplier);
                dashStrength = Math.min(config.getDouble("charged.dash.max-strength", 1.6), scaledStrength);
            }
        }

        if (direction == null || direction.lengthSquared() <= 0.0001) {
            direction = attacker.getLocation().getDirection().clone().setY(0);
        }
        if (direction.lengthSquared() <= 0.0001) {
            return;
        }

        Vector forward = direction.normalize();
        double vertical = config.getDouble("charged.dash.vertical", 0.08);
        applyDashBurst(attacker, forward, dashStrength, vertical);
        attacker.getWorld().spawnParticle(Particle.FLAME, attacker.getLocation().clone().add(0, 1.0, 0), 8, 0.2, 0.2, 0.2, 0.01);
        */
    }

    @Override
    public int getChargedHitWindowTicks() {
        return super.getChargedHitWindowTicks();
    }

    @Override
    public double getChargedAutoHitRange() {
        return super.getChargedAutoHitRange();
    }

    @Override
    public double getChargedAutoHitConeDegrees() {
        return super.getChargedAutoHitConeDegrees();
    }

    @Override
    public double getChargedDamageMultiplier(Player attacker) {
        return super.getChargedDamageMultiplier(attacker);
    }

    @Override
    public boolean usesChargedPathAttack(Player attacker) {
        return super.usesChargedPathAttack(attacker);
    }

    @Override
    public double getChargedPathLength(Player attacker) {
        return super.getChargedPathLength(attacker);
    }

    @Override
    public double getChargedPathRadius(Player attacker) {
        return super.getChargedPathRadius(attacker);
    }

    @Override
    public int getChargedMaxTargets(Player attacker) {
        return super.getChargedMaxTargets(attacker);
    }

    @Override
    public double getPlungeDamageBonusMultiplier(Player attacker) {
        return 1.0;
    }

    @Override
    public String resolveAttackElement(Player attacker, ItemStack handItem, String defaultElement) {
        if (plugin.getHuTaoStateManager().isActive(attacker)
                && plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.HUTAO)) {
            return ElementConstant.FIRE_KEY;
        }
        return defaultElement;
    }

    @Override
    public double getElementDamageBonusMultiplier(Player attacker, String elementKey) {
        return plugin.getHuTaoStateManager().getHuTaoDamageBonusMultiplier(attacker, elementKey);
    }

    @Override
    public void onChargedAttackHit(Player attacker, LivingEntity target) {
        attacker.getWorld().spawnParticle(Particle.CRIT, target.getLocation().clone().add(0, 1.0, 0), 8, 0.25, 0.3, 0.25, 0.02);
        attacker.getWorld().spawnParticle(Particle.FLAME, target.getLocation().clone().add(0, 1.0, 0), 12, 0.25, 0.35, 0.25, 0.02);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.9f);

        if (plugin.getHuTaoStateManager().isActive(attacker)
                && plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.HUTAO)) {
            plugin.getHuTaoBloodBlossomManager().applyOrRefresh(attacker, target);
        }
    }

    @Override
    public void onAttackResolved(Player attacker, LivingEntity target, boolean chargedAttack,
                                 boolean plungeAttack, String attackElement) {
        if (attacker == null || target == null || plungeAttack) {
            return;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.HUTAO)) {
            return;
        }
        if (!plugin.getHuTaoStateManager().isActive(attacker) || plugin.getEnergyManager() == null) {
            return;
        }
        plugin.getEnergyManager().grantElementalParticles(attacker, ElementConstant.FIRE_KEY,
                2, 3, 0.5, 5000L, "hutao:e-state-hit");
    }

    public long getChargedCooldownEnd(Player player) {
        return super.getChargedCooldownEnd(player);
    }

    public void restoreChargedCooldown(Player player, long endTimeMillis) {
        super.restoreChargedCooldown(player, endTimeMillis);
    }

    public void clearRuntimeState(Player player) {
        super.clearRuntimeState(player);
    }

    private void playHuTaoQCastEffects(Player player, boolean lowHp) {
        if (!config.getBoolean("q.visual.cast.enabled", true)) {
            return;
        }

        Location center = player.getLocation().clone().add(0, 1.0, 0);
        World world = player.getWorld();
        Particle ringParticle = getParticle(config.getString("q.visual.cast.ring-particle", lowHp ? "SOUL_FIRE_FLAME" : "FLAME"));
        Particle burstParticle = getParticle(config.getString("q.visual.cast.burst-particle", "FLAME"));
        int ringCount = config.getInt("q.visual.cast.ring-count", 20);
        int burstCount = config.getInt("q.visual.cast.burst-count", 24);
        double ringRadius = config.getDouble("q.visual.cast.ring-radius", 1.6);

        for (int i = 0; i < ringCount; i++) {
            double angle = (Math.PI * 2 * i) / Math.max(1, ringCount);
            double x = Math.cos(angle) * ringRadius;
            double z = Math.sin(angle) * ringRadius;
            if (ringParticle != null) {
                world.spawnParticle(ringParticle, center.clone().add(x, 0.05, z), 1, 0.02, 0.02, 0.02, 0.0);
            }
        }
        if (burstParticle != null) {
            world.spawnParticle(burstParticle, center, burstCount, 0.45, 0.30, 0.45, 0.04);
        }

        Sound sound = getSound(config.getString("q.visual.cast.sound", "ENTITY_BLAZE_SHOOT"));
        if (sound != null) {
            world.playSound(player.getLocation(), sound,
                    (float) config.getDouble("q.visual.cast.sound-volume", 1.0),
                    (float) config.getDouble("q.visual.cast.sound-pitch", lowHp ? 0.75 : 0.9));
        }
    }

    private void playHuTaoQImpactEffects(Player caster, LivingEntity target, boolean lowHp) {
        if (!config.getBoolean("q.visual.impact.enabled", true)) {
            return;
        }

        Location loc = target.getLocation().clone().add(0, 1.0, 0);
        Particle mainParticle = getParticle(config.getString("q.visual.impact.particle", "FLAME"));
        Particle secondaryParticle = getParticle(config.getString("q.visual.impact.secondary-particle", lowHp ? "SOUL_FIRE_FLAME" : "LAVA"));
        int mainCount = config.getInt("q.visual.impact.count", 14);
        int secondaryCount = config.getInt("q.visual.impact.secondary-count", 4);

        if (mainParticle != null) {
            target.getWorld().spawnParticle(mainParticle, loc, mainCount, 0.35, 0.35, 0.35, 0.02);
        }
        if (secondaryParticle != null) {
            target.getWorld().spawnParticle(secondaryParticle, loc, secondaryCount, 0.18, 0.28, 0.18, 0.0);
        }

        Sound sound = getSound(config.getString("q.visual.impact.sound", "ENTITY_GENERIC_EXPLODE"));
        if (sound != null) {
            target.getWorld().playSound(loc, sound,
                    (float) config.getDouble("q.visual.impact.sound-volume", 0.55),
                    (float) config.getDouble("q.visual.impact.sound-pitch", lowHp ? 0.85 : 1.15));
        }
    }

    private void playHuTaoQHealEffects(Player player, double actualHeal, boolean lowHp) {
        if (actualHeal <= 0 || !config.getBoolean("q.visual.heal.enabled", true)) {
            return;
        }

        Location loc = player.getLocation().clone().add(0, 1.0, 0);
        Particle mainParticle = getParticle(config.getString("q.visual.heal.particle", "HEART"));
        Particle secondaryParticle = getParticle(config.getString("q.visual.heal.secondary-particle", lowHp ? "SOUL_FIRE_FLAME" : "CHERRY_LEAVES"));
        int mainCount = config.getInt("q.visual.heal.count", 4);
        int secondaryCount = config.getInt("q.visual.heal.secondary-count", 8);

        if (mainParticle != null) {
            player.getWorld().spawnParticle(mainParticle, loc, mainCount, 0.35, 0.35, 0.35, 0.0);
        }
        if (secondaryParticle != null) {
            player.getWorld().spawnParticle(secondaryParticle, loc, secondaryCount, 0.4, 0.3, 0.4, 0.01);
        }

        Sound sound = getSound(config.getString("q.visual.heal.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
        if (sound != null) {
            player.getWorld().playSound(player.getLocation(), sound,
                    (float) config.getDouble("q.visual.heal.sound-volume", 0.8),
                    (float) config.getDouble("q.visual.heal.sound-pitch", lowHp ? 1.0 : 1.35));
        }
    }

    protected Particle getParticle(String particleName) {
        if (particleName == null || particleName.isEmpty()) {
            return null;
        }
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    protected Sound getSound(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        }
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private boolean isNotificationEnabled(String key, boolean defaultValue) {
        return config.getBoolean("notifications." + key, defaultValue);
    }

    private void sendConfiguredMessage(Player player, String path, String fallback, Map<String, String> placeholders) {
        String text = plugin.getSkillsConfig().getString(path, fallback);
        if (text == null || text.isBlank()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        player.sendMessage(color(text));
    }

    private Map<String, String> placeholders(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private String formatSeconds(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }
}
