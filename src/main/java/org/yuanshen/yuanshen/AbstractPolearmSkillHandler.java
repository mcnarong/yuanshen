package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractPolearmSkillHandler implements CharacterSkillHandler {

    protected static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";

    protected final Yuanshen plugin;
    protected final CharacterSkillConfig config;

    private final Map<UUID, Long> chargedAttackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> dashTasks = new ConcurrentHashMap<>();

    protected AbstractPolearmSkillHandler(Yuanshen plugin, CharacterType characterType) {
        this.plugin = plugin;
        this.config = plugin.getCharacterConfig(characterType);
    }

    @Override
    public CharacterSkillConfig getConfig() {
        return config;
    }

    @Override
    public boolean supportsChargedAttack() {
        return getCharacterType().isPolearm() && config.getBoolean("charged.enabled", true);
    }

    @Override
    public boolean isChargedAttack(Player attacker, ItemStack handItem) {
        if (attacker == null || !plugin.hasActiveCharacter(attacker)) {
            return false;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, getCharacterType())) {
            return false;
        }
        if (!supportsChargedAttack()) {
            return false;
        }
        boolean requireSneaking = config.getBoolean("charged.require-sneaking", true);
        return !requireSneaking || attacker.isSneaking();
    }

    @Override
    public boolean tryConsumeChargedAttack(Player attacker) {
        long remain = getRemainingChargedCooldown(attacker);
        if (remain > 0) {
            String msg = config.getString("messages.charged-cooldown",
                    "&c[" + getCharacterType().getDisplayName() + "] 重击冷却中，还剩 {time} 秒。");
            attacker.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    msg.replace("{time}", String.format("%.1f", remain / 1000.0))));
            return false;
        }

        int hungerCost = config.getInt("charged.hunger-cost", 2);
        if (attacker.getFoodLevel() < hungerCost) {
            String msg = config.getString("messages.charged-no-hunger",
                    "&c[" + getCharacterType().getDisplayName() + "] 饱食度不足，无法施放重击。");
            attacker.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return false;
        }

        attacker.setFoodLevel(Math.max(0, attacker.getFoodLevel() - hungerCost));
        chargedAttackCooldowns.put(attacker.getUniqueId(),
                System.currentTimeMillis() + config.getLong("charged.cooldown-ms", 1000L));
        return true;
    }

    @Override
    public LivingEntity findChargedAttackTarget(Player attacker) {
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
    }

    @Override
    public void beginChargedAttack(Player attacker, LivingEntity target) {
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

        Particle startParticle = getParticle(config.getString("charged.dash.start-particle", "FLAME"));
        if (startParticle != null) {
            attacker.getWorld().spawnParticle(startParticle,
                    attacker.getLocation().clone().add(0, 1.0, 0),
                    8, 0.2, 0.2, 0.2, 0.01);
        }
    }

    @Override
    public int getChargedHitWindowTicks() {
        return Math.max(1, config.getInt("charged.hit-window-ticks", 14));
    }

    @Override
    public double getChargedAutoHitRange() {
        return config.getDouble("charged.auto-hit-range", config.getDouble("charged.path-length", 4.0));
    }

    @Override
    public double getChargedAutoHitConeDegrees() {
        return config.getDouble("charged.auto-hit-cone-degrees", 100.0);
    }

    @Override
    public double getChargedDamageMultiplier(Player attacker) {
        return config.getDouble("charged.damage-multiplier", 2.0);
    }

    @Override
    public boolean usesChargedPathAttack(Player attacker) {
        return true;
    }

    @Override
    public double getChargedPathLength(Player attacker) {
        return config.getDouble("charged.path-length", 4.0);
    }

    @Override
    public double getChargedPathRadius(Player attacker) {
        return config.getDouble("charged.path-radius", 1.15);
    }

    @Override
    public int getChargedMaxTargets(Player attacker) {
        return config.getInt("charged.max-targets", 6);
    }

    public long getChargedCooldownEnd(Player player) {
        if (player == null) {
            return 0L;
        }
        Long end = chargedAttackCooldowns.get(player.getUniqueId());
        if (end == null || end <= System.currentTimeMillis()) {
            chargedAttackCooldowns.remove(player.getUniqueId());
            return 0L;
        }
        return end;
    }

    public void restoreChargedCooldown(Player player, long endTimeMillis) {
        if (player == null) {
            return;
        }
        if (endTimeMillis <= System.currentTimeMillis()) {
            chargedAttackCooldowns.remove(player.getUniqueId());
            return;
        }
        chargedAttackCooldowns.put(player.getUniqueId(), endTimeMillis);
    }

    public void clearRuntimeState(Player player) {
        if (player == null) {
            return;
        }
        chargedAttackCooldowns.remove(player.getUniqueId());
        BukkitTask task = dashTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void clearAllRuntimeState() {
        chargedAttackCooldowns.clear();
        for (BukkitTask task : dashTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        dashTasks.clear();
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

    protected double applySkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                      double damageMultiplier, double extraBonusMultiplier) {
        return applySkillDamage(caster, target, element, auraDuration, damageMultiplier, extraBonusMultiplier,
                WeaponAttackType.SKILL, null, null, null);
    }

    protected double applySkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                      double damageMultiplier, double extraBonusMultiplier, PlayerStats sourceStats) {
        return applySkillDamage(caster, target, element, auraDuration, damageMultiplier, extraBonusMultiplier,
                WeaponAttackType.SKILL, null, null, sourceStats);
    }

    protected double applySkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                      double damageMultiplier, double extraBonusMultiplier,
                                      WeaponAttackType attackType) {
        return applySkillDamage(caster, target, element, auraDuration, damageMultiplier, extraBonusMultiplier,
                attackType, null, null, null);
    }

    protected double applySkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                      double damageMultiplier, double extraBonusMultiplier,
                                      WeaponAttackType attackType, PlayerStats sourceStats) {
        return applySkillDamage(caster, target, element, auraDuration, damageMultiplier, extraBonusMultiplier,
                attackType, null, null, sourceStats);
    }

    protected double applySkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                      double damageMultiplier, double extraBonusMultiplier,
                                      WeaponAttackType attackType, String configSection,
                                      String reactionGroupFallback) {
        return applySkillDamage(caster, target, element, auraDuration, damageMultiplier, extraBonusMultiplier,
                attackType, configSection, reactionGroupFallback, null);
    }

    protected double applySkillDamage(Player caster, LivingEntity target, String element, int auraDuration,
                                      double damageMultiplier, double extraBonusMultiplier,
                                      WeaponAttackType attackType, String configSection,
                                      String reactionGroupFallback, PlayerStats sourceStats) {
        DamageResult damageResult = plugin.getDamageCalculator()
                .calculateElementDamage(caster, target, element, caster.getInventory().getItemInMainHand(), sourceStats);
        if (damageResult == null) {
            return 0.0;
        }

        ElementReactionManager.AttachmentProfile attachmentProfile = buildReactionAttachmentProfile(
                caster,
                configSection,
                attackType,
                reactionGroupFallback
        );
        ReactionResult reactionResult = plugin.getReactionManager().handleReaction(
                caster,
                target,
                element,
                damageResult,
                attachmentProfile
        );
        if (reactionResult == null) {
            return 0.0;
        }

        double attackScaledDamage = damageResult.getRawTotalDamage() + reactionResult.getScalingDamageBonus();
        if (damageMultiplier != 1.0) {
            attackScaledDamage *= damageMultiplier;
        }
        if (extraBonusMultiplier > 0.0) {
            attackScaledDamage *= (1.0 + extraBonusMultiplier);
        }

        double preResistanceMainDamage = attackScaledDamage + reactionResult.getAdditiveDamageBonus();
        preResistanceMainDamage *= getElementDamageBonusMultiplier(caster, element);
        preResistanceMainDamage *= plugin.getGlobalDamageBonusMultiplier(caster, element);
        preResistanceMainDamage *= plugin.getWeaponManager().getGlobalDamageMultiplier(caster);

        if (damageResult.isCrit() && damageResult.getPlayerStats() != null) {
            preResistanceMainDamage *= (1.0 + damageResult.getPlayerStats().getCritDamage());
        }

        double finalMainDamage = plugin.applyMobResistance(target, preResistanceMainDamage, element);
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, caster.getUniqueId().toString()));
        plugin.getReactionManager().markIncomingDamageElement(target, element);
        target.setNoDamageTicks(0);
        target.damage(finalMainDamage, caster);
        double appliedTransformativeDamage = plugin.getReactionManager()
                .applyTransformativeReactionDamage(caster, target, reactionResult);
        plugin.getWeaponManager().onAttackHit(caster, target, attackType, element);
        plugin.getReactionManager().consumeReactionAuras(target, reactionResult, element);
        plugin.getReactionManager().applyTriggerAura(target, element, reactionResult, auraDuration, attachmentProfile.getAuraAmount());
        plugin.getReactionManager().postProcessReactionState(target, reactionResult);
        if (plugin.getReactionManager().shouldTriggerSeedReaction(target, element, reactionResult)) {
            plugin.getReactionManager().triggerSeedReaction(caster, target.getLocation(), element);
        }

        double totalDamage = finalMainDamage + appliedTransformativeDamage;
        double displayedReactionDamage = plugin.getReactionManager().calculateDisplayedReactionDamage(
                reactionResult,
                preResistanceMainDamage,
                finalMainDamage,
                appliedTransformativeDamage
        );
        plugin.getElementUtils().sendDamageResult(caster, reactionResult, totalDamage, displayedReactionDamage);
        return totalDamage;
    }

    protected boolean notificationEnabled(String key, boolean defaultValue) {
        return config.getBoolean("notifications." + key, defaultValue);
    }

    protected void sendSkillMessage(Player player, String path, String fallback, Map<String, String> placeholders) {
        String text = plugin.getSkillsConfig().getString(path, fallback);
        if (text == null || text.isBlank()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        player.sendMessage(translateColors(text));
    }

    protected Map<String, String> messagePlaceholders(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    protected String formatDurationSeconds(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }

    protected String translateColors(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    protected String buildReactionSourceKey(String suffix) {
        String safeSuffix = suffix == null || suffix.isBlank() ? "default" : suffix;
        return getClass().getSimpleName() + ":" + safeSuffix;
    }

    protected String buildReactionSourceKey(String sectionPath, String fallbackGroup) {
        return getClass().getSimpleName() + ":" + config.getAttachmentGroup(sectionPath, fallbackGroup);
    }

    protected ElementReactionManager.AttachmentProfile buildReactionAttachmentProfile(Player caster, String sectionPath,
                                                                                      WeaponAttackType attackType,
                                                                                      String fallbackGroup) {
        String resolvedFallbackGroup = resolveReactionGroupFallback(attackType, fallbackGroup);
        double defaultAuraAmount = getDefaultReactionAuraAmount(attackType);
        double auraAmount = config.getAttachmentAuraAmount(sectionPath, defaultAuraAmount);
        boolean icdEnabled = config.getAttachmentIcdEnabled(
                sectionPath,
                plugin.getConfig().getBoolean("attachment.standard-icd.enabled", true)
        );
        long icdWindowMs = config.getAttachmentIcdWindowMs(
                sectionPath,
                plugin.parseConfigInt("attachment.standard-icd.window-ms", caster, 2500)
        );
        int icdHits = config.getAttachmentIcdHits(
                sectionPath,
                plugin.parseConfigInt("attachment.standard-icd.hits", caster, 3)
        );
        int icdOffset = config.getAttachmentIcdOffset(
                sectionPath,
                plugin.parseConfigInt("attachment.standard-icd.offset", caster, 0)
        );
        return new ElementReactionManager.AttachmentProfile(
                buildReactionSourceKey(sectionPath, resolvedFallbackGroup),
                auraAmount,
                icdEnabled,
                icdWindowMs,
                icdHits,
                icdOffset
        );
    }

    protected double getReactionAuraAmount(String sectionPath, WeaponAttackType attackType) {
        return config.getAttachmentAuraAmount(sectionPath, getDefaultReactionAuraAmount(attackType));
    }

    protected double getReactionAuraAmount(WeaponAttackType attackType) {
        return getDefaultReactionAuraAmount(attackType);
    }

    protected double getDefaultReactionAuraAmount(WeaponAttackType attackType) {
        String path = switch (attackType) {
            case NORMAL -> "attachment.aura.normal";
            case CHARGED -> "attachment.aura.charged";
            case SKILL -> "attachment.aura.skill";
            case BURST -> "attachment.aura.burst";
            case PLUNGE -> "attachment.aura.plunge";
        };
        return plugin.parseConfigDouble(path, null, 1.0);
    }

    private String resolveReactionGroupFallback(WeaponAttackType attackType, String reactionGroupFallback) {
        if (reactionGroupFallback != null && !reactionGroupFallback.isBlank()) {
            return reactionGroupFallback;
        }
        return attackType.name().toLowerCase(Locale.ROOT);
    }

    private long getRemainingChargedCooldown(Player attacker) {
        Long end = chargedAttackCooldowns.get(attacker.getUniqueId());
        if (end == null) {
            return 0L;
        }
        long remain = end - System.currentTimeMillis();
        if (remain <= 0L) {
            chargedAttackCooldowns.remove(attacker.getUniqueId());
            return 0L;
        }
        return remain;
    }

    private void applyDashBurst(Player attacker, Vector forward, double dashStrength, double vertical) {
        if (forward.lengthSquared() <= 0.0001) {
            return;
        }

        UUID playerId = attacker.getUniqueId();
        BukkitTask oldTask = dashTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }

        int burstTicks = Math.max(1, config.getInt("charged.dash.burst-ticks", 3));
        double burstDecay = Math.max(0.1, Math.min(1.0, config.getDouble("charged.dash.burst-decay", 0.85)));
        boolean spinEffect = config.getBoolean("charged.dash.spin-effect.enabled", true);
        Sound spinSound = getSound(config.getString("charged.dash.spin-effect.sound", "ITEM_TRIDENT_RIPTIDE_1"));
        if (spinEffect && spinSound != null) {
            attacker.getWorld().playSound(attacker.getLocation(), spinSound,
                    (float) config.getDouble("charged.dash.spin-effect.sound-volume", 0.9),
                    (float) config.getDouble("charged.dash.spin-effect.sound-pitch", 1.15));
        }

        BukkitTask task = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!attacker.isOnline() || attacker.isDead()) {
                    dashTasks.remove(playerId);
                    cancel();
                    return;
                }

                double strength = dashStrength * Math.pow(burstDecay, tick);
                Vector velocity = forward.clone().multiply(strength);
                velocity.setY(Math.max(attacker.getVelocity().getY(), vertical));
                attacker.setVelocity(velocity);
                if (spinEffect) {
                    spawnChargedSpinEffect(attacker, tick);
                }

                tick++;
                if (tick >= burstTicks) {
                    dashTasks.remove(playerId);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        dashTasks.put(playerId, task);
    }

    private void spawnChargedSpinEffect(Player attacker, int tick) {
        Location center = attacker.getLocation().clone().add(0, 1.0, 0);
        double radius = config.getDouble("charged.dash.spin-effect.radius", 0.85);
        int points = Math.max(4, config.getInt("charged.dash.spin-effect.points", 6));
        double verticalStep = config.getDouble("charged.dash.spin-effect.vertical-step", 0.16);

        Particle mainParticle = getParticle(config.getString("charged.dash.spin-effect.particle", "SWEEP_ATTACK"));
        Particle secondaryParticle = getParticle(config.getString("charged.dash.spin-effect.secondary-particle", "CLOUD"));
        Particle accentParticle = getParticle(config.getString("charged.dash.spin-effect.accent-particle", "FLAME"));

        double baseAngle = tick * 1.35;
        for (int i = 0; i < points; i++) {
            double angle = baseAngle + ((Math.PI * 2) * i / points);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = (i % 2 == 0 ? verticalStep : -verticalStep);
            Location point = center.clone().add(x, y, z);

            if (mainParticle != null) {
                attacker.getWorld().spawnParticle(mainParticle, point, 1, 0.02, 0.02, 0.02, 0.0);
            }
            if (secondaryParticle != null) {
                attacker.getWorld().spawnParticle(secondaryParticle, point, 1, 0.03, 0.03, 0.03, 0.0);
            }
        }

        if (accentParticle != null) {
            attacker.getWorld().spawnParticle(accentParticle, center, 4, 0.25, 0.25, 0.25, 0.01);
        }
    }
}
