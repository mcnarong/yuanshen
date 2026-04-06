package org.yuanshen.yuanshen;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DilucSkillHandler extends AbstractPolearmSkillHandler {

    private final Map<UUID, Integer> comboStages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> comboExpireTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> infusionEndTimes = new ConcurrentHashMap<>();

    public DilucSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.DILUC);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.DILUC;
    }

    @Override
    public boolean supportsChargedAttack() {
        return config.getBoolean("charged.enabled", true);
    }

    @Override
    public boolean handlesChargedAttackInternally() {
        return true;
    }

    @Override
    public boolean isChargedAttack(Player attacker, ItemStack handItem) {
        if (attacker == null || !plugin.hasActiveCharacter(attacker)) {
            return false;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.DILUC)) {
            return false;
        }
        return supportsChargedAttack() && (!config.getBoolean("charged.require-sneaking", true) || attacker.isSneaking());
    }

    @Override
    public void beginChargedAttack(Player attacker, LivingEntity target) {
        double radius = config.getDouble("charged.radius", 2.6);
        double range = config.getDouble("charged.range", 4.2);
        double damageMultiplier = config.getDouble("charged.damage-multiplier", 1.85);
        int maxTargets = Math.max(1, config.getInt("charged.max-targets", 4));

        List<LivingEntity> targets = collectConeTargets(attacker, target, range, radius, 105.0, maxTargets);
        if (targets.isEmpty() && target != null && target.isValid() && !target.isDead()) {
            targets.add(target);
        }
        if (targets.isEmpty()) {
            return;
        }

        Location center = attacker.getLocation().clone().add(0, 1.0, 0);
        attacker.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 10, 0.5, 0.2, 0.5, 0.0);
        attacker.getWorld().spawnParticle(Particle.FLAME, center, 16, 0.55, 0.25, 0.55, 0.03);
        attacker.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.95f, 0.8f);

        for (LivingEntity living : targets) {
            applyPhysicalChargedDamage(attacker, living, damageMultiplier);
        }
    }

    @Override
    public boolean tryCastSkill(Player player, CharacterActionType actionType, LivingEntity directTarget) {
        PlayerSkillData data = PlayerSkillData.get(player.getUniqueId());
        return switch (actionType) {
            case ELEMENTAL_SKILL -> tryCastE(player, data, directTarget);
            case ELEMENTAL_BURST -> tryCastQ(player, data, directTarget);
            default -> false;
        };
    }

    @Override
    public String resolveAttackElement(Player attacker, ItemStack handItem, String defaultElement) {
        return isBurstInfusionActive(attacker) ? ElementConstant.FIRE_KEY : defaultElement;
    }

    @Override
    public double getElementDamageBonusMultiplier(Player attacker, String elementKey) {
        if (!isBurstInfusionActive(attacker) || !ElementConstant.FIRE_KEY.equals(elementKey)) {
            return 1.0;
        }
        double bonus = config.getDouble("passive.dawn.pyro-bonus", 0.20);
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(attacker, CharacterType.DILUC, 2)) {
                bonus += 0.15;
            }
            if (plugin.getConstellationManager().hasConstellation(attacker, CharacterType.DILUC, 6)) {
                bonus += 0.20;
            }
        }
        return 1.0 + bonus;
    }

    public void clearRuntimeState(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        comboStages.remove(playerId);
        comboExpireTimes.remove(playerId);
        infusionEndTimes.remove(playerId);
    }

    public void clearAllRuntimeState() {
        comboStages.clear();
        comboExpireTimes.clear();
        infusionEndTimes.clear();
    }

    private boolean tryCastE(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long comboWindowMs = config.getLong("e.combo-window-ms", 4000L);
        boolean comboActive = comboExpireTimes.getOrDefault(playerId, 0L) > now;
        int nextStage = comboActive ? comboStages.getOrDefault(playerId, 0) + 1 : 1;

        long cooldownMs = config.getLong("e.cooldown-ms", 10000L);
        if (nextStage == 1 && !data.canUseE(CharacterType.DILUC, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.DILUC, cooldownMs);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[迪卢克] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        if (nextStage == 1) {
            data.useE(CharacterType.DILUC);
        }

        int stage = Math.max(1, Math.min(3, nextStage));
        executeSearingOnslaught(player, directTarget, stage);
        data.addEnergy(CharacterType.DILUC, config.getInt("e.energy-gain-per-slash", 10));

        if (stage >= 3) {
            comboStages.remove(playerId);
            comboExpireTimes.remove(playerId);
        } else {
            comboStages.put(playerId, stage);
            comboExpireTimes.put(playerId, now + comboWindowMs);
        }

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&6[迪卢克] 逆焰之刃第 {stage} 段命中。",
                    messagePlaceholders("stage", String.valueOf(stage)));
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[迪卢克] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.DILUC)),
                            "max_energy", String.valueOf(config.getInt("q.energy-cost", 40))
                    ));
        }
        return true;
    }

    private boolean tryCastQ(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("q.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("q.cooldown-ms", 12000L);
        int energyCost = config.getInt("q.energy-cost", 40);
        if (!data.canUseQ(CharacterType.DILUC, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.DILUC, cooldownMs);
            if (remain > 0L) {
                player.sendMessage(translateColors(config.getString(
                        "messages.q-cooldown",
                        "&c[迪卢克] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatDurationSeconds(remain))));
            } else {
                player.sendMessage(translateColors(config.getString(
                                "messages.q-no-energy",
                                "&c[迪卢克] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.DILUC)))
                        .replace("{max_energy}", String.valueOf(energyCost))));
            }
            return true;
        }

        data.useQ(CharacterType.DILUC, energyCost);
        castDawn(player, directTarget);
        if (notificationEnabled("q-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("q-cast"),
                    "&c[迪卢克] 黎明已释放，烈焰之鸟撕开前方。",
                    messagePlaceholders());
        }
        return true;
    }

    private void executeSearingOnslaught(Player player, LivingEntity directTarget, int stage) {
        double range = config.getDouble("e.range", 4.6);
        double radius = config.getDouble("e.radius", 1.8);
        double angle = config.getDouble("e.angle-degrees", 95.0);
        int auraDuration = config.getInt("e.aura-duration", 120);
        double damageMultiplier = config.getDouble("e.slash-" + stage + "-damage-multiplier",
                stage == 3 ? 1.29 : stage == 2 ? 0.976 : 0.944);
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.DILUC, 1)) {
                damageMultiplier *= 1.15;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.DILUC, 3)) {
                damageMultiplier *= 1.20;
            }
            if (stage == 3 && plugin.getConstellationManager().hasConstellation(player, CharacterType.DILUC, 4)) {
                damageMultiplier *= 1.25;
            }
        }
        int maxTargets = Math.max(1, config.getInt("e.max-targets", 5));

        List<LivingEntity> targets = collectConeTargets(player, directTarget, range, radius, angle, maxTargets);
        Location center = player.getLocation().clone().add(0, 1.0, 0);
        World world = center.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FLAME, center, 18 + (stage * 4), 0.65, 0.25, 0.65, 0.04);
            world.spawnParticle(Particle.SWEEP_ATTACK, center, 8, 0.35, 0.18, 0.35, 0.0);
            world.playSound(center, Sound.ENTITY_BLAZE_SHOOT, 0.9f, 0.9f + (stage * 0.08f));
        }

        int hitCount = 0;
        String attackSection = "e.slash-" + stage;
        String reactionGroup = "e_slash_" + stage;
        for (LivingEntity target : targets) {
            double damage = applySkillDamage(player, target, ElementConstant.FIRE_KEY, auraDuration, damageMultiplier, 0.0,
                    WeaponAttackType.SKILL, attackSection, reactionGroup);
            if (damage > 0.0) {
                hitCount++;
            }
            if (stage >= 2) {
                Vector push = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 0.0001) {
                    target.setVelocity(push.normalize().multiply(0.35 + (stage * 0.08)).setY(0.18));
                }
            }
        }
        if (hitCount > 0 && plugin.getEnergyManager() != null) {
            plugin.getEnergyManager().grantElementalParticles(player, ElementConstant.FIRE_KEY,
                    1, 2, 0.33, 300L, "diluc:searing-onslaught");
        }
    }

    private void castDawn(Player player, LivingEntity directTarget) {
        double burstRadius = config.getDouble("q.burst-radius", 4.0);
        int auraDuration = config.getInt("q.aura-duration", 160);
        double burstMultiplier = config.getDouble("q.burst-damage-multiplier", 2.04);
        double phoenixLength = config.getDouble("q.phoenix-length", 13.0);
        double phoenixRadius = config.getDouble("q.phoenix-radius", 2.0);
        double phoenixMultiplier = config.getDouble("q.phoenix-damage-multiplier", 2.04);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.DILUC, 5)) {
            burstMultiplier *= 1.25;
            phoenixMultiplier *= 1.25;
        }
        int maxTargets = Math.max(1, config.getInt("q.max-targets", 8));

        Location center = player.getLocation().clone().add(0, 1.0, 0);
        World world = center.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FLAME, center, 36, burstRadius * 0.35, 0.35, burstRadius * 0.35, 0.08);
            world.spawnParticle(Particle.LAVA, center, 12, 0.45, 0.2, 0.45, 0.01);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.85f, 0.8f);
        }

        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), burstRadius, burstRadius, burstRadius)) {
            if (entity instanceof LivingEntity living && !living.equals(player) && living.isValid() && !living.isDead()) {
                applySkillDamage(player, living, ElementConstant.FIRE_KEY, auraDuration, burstMultiplier, 0.0,
                        WeaponAttackType.BURST, "q.burst", "q_burst");
            }
        }

        List<LivingEntity> phoenixTargets = collectLineTargets(player, directTarget, phoenixLength, phoenixRadius, maxTargets);
        Location start = player.getLocation().clone().add(0, 1.0, 0);
        Vector direction = player.getLocation().getDirection().clone().setY(0);
        if (direction.lengthSquared() <= 0.0001) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();

        for (double traveled = 1.0; traveled <= phoenixLength; traveled += 1.2) {
            Location point = start.clone().add(direction.clone().multiply(traveled));
            if (world != null) {
                world.spawnParticle(Particle.FLAME, point, 10, 0.45, 0.25, 0.45, 0.02);
                world.spawnParticle(Particle.SMOKE, point, 4, 0.3, 0.12, 0.3, 0.01);
            }
        }
        if (world != null) {
            world.playSound(start, Sound.ENTITY_BLAZE_SHOOT, 1.1f, 0.6f);
        }

        for (LivingEntity target : phoenixTargets) {
            applySkillDamage(player, target, ElementConstant.FIRE_KEY, auraDuration, phoenixMultiplier, 0.0,
                    WeaponAttackType.BURST, "q.phoenix", "q_phoenix");
        }

        long infusionDurationMs = config.getLong("q.infusion-duration-ticks", 240L) * 50L;
        infusionEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + infusionDurationMs);
    }

    private boolean isBurstInfusionActive(Player player) {
        if (player == null) {
            return false;
        }
        long endTime = infusionEndTimes.getOrDefault(player.getUniqueId(), 0L);
        if (endTime <= System.currentTimeMillis()) {
            infusionEndTimes.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void applyPhysicalChargedDamage(Player attacker, LivingEntity target, double damageMultiplier) {
        DamageResult damageResult = plugin.getDamageCalculator()
                .calculateElementDamage(attacker, target, PlayerStats.PHYSICAL_KEY, attacker.getInventory().getItemInMainHand());
        if (damageResult == null) {
            return;
        }
        ReactionResult reactionResult = plugin.getReactionManager()
                .handleReaction(attacker, target, PlayerStats.PHYSICAL_KEY, damageResult,
                        buildReactionSourceKey("charged_physical"), 0.0);
        if (reactionResult == null) {
            return;
        }

        double preResistanceMainDamage = (damageResult.getRawTotalDamage() + reactionResult.getScalingDamageBonus()) * damageMultiplier;
        preResistanceMainDamage += reactionResult.getAdditiveDamageBonus();
        preResistanceMainDamage *= plugin.getGlobalDamageBonusMultiplier(attacker, PlayerStats.PHYSICAL_KEY);
        if (damageResult.isCrit() && damageResult.getPlayerStats() != null) {
            preResistanceMainDamage *= (1.0 + damageResult.getPlayerStats().getCritDamage());
        }
        double finalMainDamage = plugin.applyMobResistance(target, preResistanceMainDamage, PlayerStats.PHYSICAL_KEY);

        plugin.getReactionManager().markIncomingDamageElement(target, PlayerStats.PHYSICAL_KEY);
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META,
                new org.bukkit.metadata.FixedMetadataValue(plugin, attacker.getUniqueId().toString()));
        target.setNoDamageTicks(0);
        target.damage(finalMainDamage, attacker);
        double appliedTransformativeDamage = plugin.getReactionManager()
                .applyTransformativeReactionDamage(attacker, target, reactionResult);
        plugin.getReactionManager().consumeReactionAuras(target, reactionResult, PlayerStats.PHYSICAL_KEY);
        plugin.getReactionManager().postProcessReactionState(target, reactionResult);

        double totalDamage = finalMainDamage + appliedTransformativeDamage;
        double displayedReactionDamage = plugin.getReactionManager().calculateDisplayedReactionDamage(
                reactionResult,
                preResistanceMainDamage,
                finalMainDamage,
                appliedTransformativeDamage
        );
        plugin.getElementUtils().sendDamageResult(attacker, reactionResult, totalDamage, displayedReactionDamage);
    }

    private List<LivingEntity> collectConeTargets(Player attacker, LivingEntity preferredTarget, double range,
                                                  double radius, double coneDegrees, int maxTargets) {
        Map<LivingEntity, Double> candidates = new ConcurrentHashMap<>();
        Vector look = attacker.getLocation().getDirection().clone().setY(0);
        if (look.lengthSquared() <= 0.0001) {
            look = new Vector(0, 0, 1);
        }
        look.normalize();
        double requiredDot = Math.cos(Math.toRadians(coneDegrees / 2.0));
        Location origin = attacker.getLocation().clone().add(0, 1.0, 0);

        for (Entity entity : attacker.getWorld().getNearbyEntities(attacker.getLocation(), range, 2.4, range)) {
            if (!(entity instanceof LivingEntity living) || living.equals(attacker) || !living.isValid() || living.isDead()) {
                continue;
            }
            Vector toTarget = living.getLocation().clone().add(0, 1.0, 0).toVector().subtract(origin.toVector());
            double distance = toTarget.length();
            if (distance <= 0.0001 || distance > range) {
                continue;
            }
            Vector horizontal = toTarget.clone().setY(0);
            if (horizontal.lengthSquared() <= 0.0001) {
                continue;
            }
            if (look.dot(horizontal.normalize()) < requiredDot) {
                continue;
            }
            if (distanceSquaredToRay(origin, look, living) > radius * radius) {
                continue;
            }
            candidates.put(living, distance);
        }

        if (preferredTarget != null && preferredTarget.isValid() && !preferredTarget.isDead()) {
            candidates.putIfAbsent(preferredTarget, attacker.getLocation().distance(preferredTarget.getLocation()));
        }

        List<Map.Entry<LivingEntity, Double>> sorted = new ArrayList<>(candidates.entrySet());
        sorted.sort(Map.Entry.comparingByValue());
        List<LivingEntity> result = new ArrayList<>();
        for (Map.Entry<LivingEntity, Double> entry : sorted) {
            result.add(entry.getKey());
            if (result.size() >= maxTargets) {
                break;
            }
        }
        return result;
    }

    private List<LivingEntity> collectLineTargets(Player player, LivingEntity preferredTarget,
                                                  double length, double radius, int maxTargets) {
        Location start = player.getLocation().clone().add(0, 1.0, 0);
        Vector direction = player.getLocation().getDirection().clone().setY(0);
        if (direction.lengthSquared() <= 0.0001) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();
        Location end = start.clone().add(direction.clone().multiply(length));

        Map<LivingEntity, Double> candidates = new ConcurrentHashMap<>();
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), length + radius, 2.5, length + radius)) {
            if (!(entity instanceof LivingEntity living) || living.equals(player) || !living.isValid() || living.isDead()) {
                continue;
            }
            double projection = projectionAlongSegment(start, end, living);
            if (projection < 0.0 || projection > length) {
                continue;
            }
            if (distanceSquaredToSegment(start, end, living) > radius * radius) {
                continue;
            }
            candidates.put(living, projection);
        }

        if (preferredTarget != null && preferredTarget.isValid() && !preferredTarget.isDead()) {
            candidates.putIfAbsent(preferredTarget, player.getLocation().distance(preferredTarget.getLocation()));
        }

        List<Map.Entry<LivingEntity, Double>> sorted = new ArrayList<>(candidates.entrySet());
        sorted.sort(Map.Entry.comparingByValue());
        List<LivingEntity> result = new ArrayList<>();
        for (Map.Entry<LivingEntity, Double> entry : sorted) {
            result.add(entry.getKey());
            if (result.size() >= maxTargets) {
                break;
            }
        }
        return result;
    }

    private double projectionAlongSegment(Location start, Location end, LivingEntity target) {
        Vector segment = end.toVector().subtract(start.toVector());
        double segmentLengthSquared = segment.lengthSquared();
        if (segmentLengthSquared <= 0.0001) {
            return 0.0;
        }
        Vector point = target.getLocation().clone().add(0, 1.0, 0).toVector().subtract(start.toVector());
        double t = point.dot(segment) / segmentLengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.sqrt(segmentLengthSquared) * t;
    }

    private double distanceSquaredToSegment(Location start, Location end, LivingEntity target) {
        Vector startVec = start.toVector();
        Vector segment = end.toVector().subtract(startVec);
        double segmentLengthSquared = segment.lengthSquared();
        if (segmentLengthSquared <= 0.0001) {
            return target.getLocation().clone().add(0, 1.0, 0).toVector().distanceSquared(startVec);
        }

        Vector point = target.getLocation().clone().add(0, 1.0, 0).toVector().subtract(startVec);
        double t = point.dot(segment) / segmentLengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        Vector closest = startVec.clone().add(segment.multiply(t));
        return target.getLocation().clone().add(0, 1.0, 0).toVector().distanceSquared(closest);
    }

    private double distanceSquaredToRay(Location origin, Vector direction, LivingEntity target) {
        Vector point = target.getLocation().clone().add(0, 1.0, 0).toVector().subtract(origin.toVector());
        double t = Math.max(0.0, point.dot(direction));
        Vector closest = origin.toVector().add(direction.clone().multiply(t));
        return target.getLocation().clone().add(0, 1.0, 0).toVector().distanceSquared(closest);
    }
}
