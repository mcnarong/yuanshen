package org.yuanshen.yuanshen;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TravelerAnemoSkillHandler extends AbstractPolearmSkillHandler {

    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";
    private static final List<String> ABSORPTION_PRIORITY = List.of(
            ElementConstant.ICE_KEY,
            ElementConstant.FIRE_KEY,
            ElementConstant.WATER_KEY,
            ElementConstant.ELECTRO_KEY
    );

    private final TravelerAnemoStateManager stateManager;
    private final Map<UUID, Integer> normalAttackCombos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> normalAttackTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> passiveHealCooldowns = new ConcurrentHashMap<>();

    public TravelerAnemoSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.TRAVELER_ANEMO);
        this.stateManager = plugin.getTravelerAnemoStateManager();
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.TRAVELER_ANEMO;
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
    public boolean isChargedAttack(Player attacker, org.bukkit.inventory.ItemStack handItem) {
        if (attacker == null || !plugin.hasActiveCharacter(attacker)) {
            return false;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.TRAVELER_ANEMO)) {
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
        return super.tryConsumeChargedAttack(attacker);
    }

    @Override
    public LivingEntity findChargedAttackTarget(Player attacker) {
        return super.findChargedAttackTarget(attacker);
    }

    @Override
    public void beginChargedAttack(Player attacker, LivingEntity target) {
        castChargedSwordCombo(attacker, target);
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

    public boolean tryCastHeldE(Player player, LivingEntity directTarget, int ticksHeldFor) {
        if (player == null) {
            return false;
        }
        PlayerSkillData data = PlayerSkillData.get(player.getUniqueId());
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        boolean heldCast = config.getBoolean("e.hold.enabled", true)
                && ticksHeldFor >= getHeldMinimumTicks();
        if (!heldCast) {
            return tryCastE(player, data, directTarget);
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 8000L);
        if (!data.canUseE(CharacterType.TRAVELER_ANEMO, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.TRAVELER_ANEMO, cooldownMs);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[旅行者-风] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.TRAVELER_ANEMO);
        data.addEnergy(CharacterType.TRAVELER_ANEMO, config.getInt("e.energy-gain", 20));
        castHeldPalmVortex(player, directTarget);

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&a[旅行者-风] 长按施放风涡剑，正在持续牵引并切割前方敌人。",
                    messagePlaceholders(
                            "mode", "长按",
                            "held_ticks", String.valueOf(Math.max(0, ticksHeldFor))
                    ));
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[旅行者-风] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.TRAVELER_ANEMO)),
                            "max_energy", String.valueOf(config.getInt("q.energy-cost", 60))
                    ));
        }
        return true;
    }

    @Override
    public void onAttackResolved(Player attacker, LivingEntity target, boolean chargedAttack,
                                 boolean plungeAttack, String attackElement) {
        if (attacker == null || target == null || chargedAttack || plungeAttack) {
            return;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.TRAVELER_ANEMO)) {
            return;
        }

        UUID playerId = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        long resetWindow = Math.max(300L, config.getLong("passive.slash.combo-reset-ms", 1400L));
        long lastAttack = normalAttackTimes.getOrDefault(playerId, 0L);
        int combo = lastAttack > 0L && now - lastAttack <= resetWindow
                ? normalAttackCombos.getOrDefault(playerId, 0) + 1
                : 1;

        normalAttackTimes.put(playerId, now);
        if (combo >= Math.max(1, config.getInt("passive.slash.trigger-hit", 5))) {
            normalAttackCombos.put(playerId, 0);
            castGaleBlade(attacker);
            return;
        }
        normalAttackCombos.put(playerId, combo);
    }

    public void clearRuntimeState(Player player) {
        super.clearRuntimeState(player);
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        normalAttackCombos.remove(playerId);
        normalAttackTimes.remove(playerId);
        passiveHealCooldowns.remove(playerId);
        stateManager.clear(player);
    }

    public void clearAllRuntimeState() {
        super.clearAllRuntimeState();
        normalAttackCombos.clear();
        normalAttackTimes.clear();
        passiveHealCooldowns.clear();
        stateManager.clearAll();
    }

    private boolean tryCastE(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 8000L);
        if (!data.canUseE(CharacterType.TRAVELER_ANEMO, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.TRAVELER_ANEMO, cooldownMs);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[旅行者-风] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.TRAVELER_ANEMO);
        data.addEnergy(CharacterType.TRAVELER_ANEMO, config.getInt("e.energy-gain", 20));
        castPalmVortex(player, directTarget);

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&a[旅行者-风] 风涡剑已展开，正在牵引前方敌人。",
                    messagePlaceholders());
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[旅行者-风] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.TRAVELER_ANEMO)),
                            "max_energy", String.valueOf(config.getInt("q.energy-cost", 60))
                    ));
        }
        return true;
    }

    private boolean tryCastQ(Player player, PlayerSkillData data) {
        if (!config.getBoolean("q.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("q.cooldown-ms", 15000L);
        int energyCost = config.getInt("q.energy-cost", 60);
        if (!data.canUseQ(CharacterType.TRAVELER_ANEMO, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.TRAVELER_ANEMO, cooldownMs);
            if (remain > 0L) {
                player.sendMessage(translateColors(config.getString(
                        "messages.q-cooldown",
                        "&c[旅行者-风] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatDurationSeconds(remain))));
            } else {
                player.sendMessage(translateColors(config.getString(
                                "messages.q-no-energy",
                                "&c[旅行者-风] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.TRAVELER_ANEMO)))
                        .replace("{max_energy}", String.valueOf(energyCost))));
            }
            return true;
        }

        data.useQ(CharacterType.TRAVELER_ANEMO, energyCost);
        castTornadoBurst(player);
        if (notificationEnabled("q-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("q-cast"),
                    "&b[旅行者-风] 风息激荡已释放，龙卷正在前进。",
                    messagePlaceholders());
        }
        return true;
    }

    private void castPalmVortex(Player player, LivingEntity directTarget) {
        Vector forward = horizontalLookDirection(player);
        if (forward.lengthSquared() <= 0.0001) {
            return;
        }

        int windupTicks = Math.max(4, config.getInt("e.windup-ticks", 10));
        int pulseInterval = Math.max(1, config.getInt("e.pull-interval-ticks", 2));
        double pullRadius = config.getDouble("e.pull-radius", 4.5);
        double pullStrength = config.getDouble("e.pull-strength", 0.16);
        double hitRadius = config.getDouble("e.hit-radius", 3.0);
        double sliceDamageMultiplierValue = config.getDouble("e.slice-damage-multiplier", 0.28);
        double releaseDamageMultiplierValue = config.getDouble("e.release-damage-multiplier", 0.82);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 3)) {
            sliceDamageMultiplierValue *= 1.25;
            releaseDamageMultiplierValue *= 1.25;
        }
        final double sliceDamageMultiplier = sliceDamageMultiplierValue;
        final double releaseDamageMultiplier = releaseDamageMultiplierValue;
        int auraDuration = config.getInt("e.aura-duration", 120);
        double knockbackStrength = config.getDouble("e.release-knockback-strength", 0.75);
        double knockbackVertical = config.getDouble("e.release-knockback-vertical", 0.22);

        final boolean[] particlesGranted = {false};
        new BukkitRunnable() {
            int tick = 0;
            String absorbedElement = null;
            boolean absorbNotified = false;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                Location center = skillCenter(player, forward, config.getDouble("e.cast-distance", 2.1), 1.05);
                if (absorbedElement == null) {
                    absorbedElement = null;
                }
                if (!absorbNotified && absorbedElement != null && notificationEnabled("e-absorb", true)) {
                    sendSkillMessage(player,
                            config.messagePath("e-absorb"),
                            "&e[风涡剑] 已转化为 {element} 元素。",
                            messagePlaceholders("element", elementDisplayName(absorbedElement)));
                    absorbNotified = true;
                }

                spawnPalmVortexChannelEffects(center, tick, null);
                pullElementalSkillTargets(player, center, pullRadius, pullStrength, 0.04);

                if (tick > 0 && tick < windupTicks && tick % pulseInterval == 0) {
                    int hitCount = applyPulseDamage(player, center, hitRadius, sliceDamageMultiplier,
                            0.0, auraDuration, null, false, 0.0, 0.0,
                            "e.slice", "e_slice", null, null);
                    if (!particlesGranted[0] && hitCount > 0 && grantTapParticles(player)) {
                        particlesGranted[0] = true;
                    }
                }

                tick++;
                if (tick < windupTicks) {
                    return;
                }

                spawnPalmVortexReleaseEffects(center, null);
                int hitCount = applyPulseDamage(player, center, hitRadius, releaseDamageMultiplier,
                        0.0, auraDuration, null,
                        true, knockbackStrength, knockbackVertical,
                        "e.release", "e_release", null, null);
                if (!particlesGranted[0] && hitCount > 0 && grantTapParticles(player)) {
                    particlesGranted[0] = true;
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void castHeldPalmVortex(Player player, LivingEntity directTarget) {
        Vector forward = horizontalLookDirection(player);
        if (forward.lengthSquared() <= 0.0001) {
            return;
        }

        double pullRadius = config.getDouble("e.pull-radius", 4.5);
        double pullStrengthValue = config.getDouble("e.hold.pull-strength",
                config.getDouble("e.pull-strength", 0.16) * 1.18);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 2)) {
            pullStrengthValue *= 1.20;
        }
        final double pullStrength = pullStrengthValue;
        double pullVertical = config.getDouble("e.pull-vertical", 0.07);
        double hitRadius = config.getDouble("e.hit-radius", 3.0);
        double castDistance = config.getDouble("e.cast-distance", 2.1);
        int auraDuration = config.getInt("e.aura-duration", 120);
        List<Integer> initialHitTicks = getConfiguredHitTicks("e.hold.initial-hit-ticks", List.of(7, 10));
        List<Integer> maxHitTicks = getConfiguredHitTicks("e.hold.max-hit-ticks", List.of(17, 20, 27, 30));
        Set<Integer> initialHitSet = new HashSet<>(initialHitTicks);
        Set<Integer> maxHitSet = new HashSet<>(maxHitTicks);
        int totalTicks = 1;
        for (int tick : initialHitTicks) {
            totalTicks = Math.max(totalTicks, tick);
        }
        for (int tick : maxHitTicks) {
            totalTicks = Math.max(totalTicks, tick);
        }
        final int finalTotalTicks = totalTicks;

        double initialDamageMultiplierValue = config.getDouble(
                "e.hold.initial-slice-damage-multiplier",
                config.getDouble("e.slice-damage-multiplier", 0.28)
        );
        double maxDamageMultiplierValue = config.getDouble(
                "e.hold.max-slice-damage-multiplier",
                Math.max(initialDamageMultiplierValue, config.getDouble("e.release-damage-multiplier", 0.82))
        );
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 2)) {
                maxDamageMultiplierValue *= 1.20;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 3)) {
                initialDamageMultiplierValue *= 1.25;
                maxDamageMultiplierValue *= 1.25;
            }
        }
        final double initialDamageMultiplier = initialDamageMultiplierValue;
        final double maxDamageMultiplier = maxDamageMultiplierValue;
        double finishKnockbackStrength = config.getDouble("e.hold.finish-knockback-strength", 0.34);
        double finishKnockbackVertical = config.getDouble("e.hold.finish-knockback-vertical", 0.16);

        final boolean[] particlesGranted = {false};
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                Location center = skillCenter(player, forward, castDistance, 1.05);
                spawnPalmVortexChannelEffects(center, tick, "held:anemo");
                pullElementalSkillTargets(player, center, pullRadius, pullStrength, pullVertical);

                int currentTick = tick + 1;
                if (initialHitSet.contains(currentTick)) {
                    int hitCount = applyPulseDamage(player, center, hitRadius, initialDamageMultiplier,
                            0.0, auraDuration, null, false, 0.0, 0.0,
                            "e.hold.initial-slice", "e_hold_initial_slice", null, null);
                    if (!particlesGranted[0] && hitCount > 0 && grantHeldParticles(player)) {
                        particlesGranted[0] = true;
                    }
                }
                if (maxHitSet.contains(currentTick)) {
                    int hitCount = applyPulseDamage(player, center, hitRadius, maxDamageMultiplier,
                            0.0, auraDuration, null, false, 0.0, 0.0,
                            "e.hold.max-slice", "e_hold_max_slice", null, null);
                    if (!particlesGranted[0] && hitCount > 0 && grantHeldParticles(player)) {
                        particlesGranted[0] = true;
                    }
                }

                if (currentTick < finalTotalTicks) {
                    tick++;
                    return;
                }

                spawnPalmVortexReleaseEffects(center, "held:anemo");
                if (finishKnockbackStrength > 0.0) {
                    releaseElementalSkillTargets(player, center, hitRadius,
                            finishKnockbackStrength, finishKnockbackVertical);
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private int getHeldMinimumTicks() {
        return Math.max(1, config.getInt("e.hold.minimum-held-ticks", 6));
    }

    private List<Integer> getConfiguredHitTicks(String relativePath, List<Integer> fallback) {
        List<Integer> raw = plugin.getSkillsConfig().getIntegerList(config.path(relativePath));
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }

        List<Integer> filtered = new ArrayList<>();
        for (Integer tick : raw) {
            if (tick != null && tick > 0) {
                filtered.add(tick);
            }
        }
        if (filtered.isEmpty()) {
            return fallback;
        }
        filtered.sort(Integer::compareTo);
        return filtered;
    }

    private void releaseElementalSkillTargets(Player player, Location center, double radius,
                                              double knockbackStrength, double knockbackVertical) {
        for (Entity target : nearbyElementalSkillTargets(center, radius, player)) {
            applyDirectionalPush(center, target, knockbackStrength, knockbackVertical);
        }
    }

    private void castChargedSwordCombo(Player attacker, LivingEntity preferredTarget) {
        int slashCount = Math.max(1, config.getInt("charged.slash-count", 3));
        int intervalTicks = Math.max(1, config.getInt("charged.interval-ticks", 3));
        double range = config.getDouble("charged.range", 3.6);
        double radius = config.getDouble("charged.radius", 1.6);
        double coneDegrees = config.getDouble("charged.cone-degrees", 95.0);
        int maxTargets = Math.max(1, config.getInt("charged.max-targets", 3));
        int auraDuration = Math.max(1, config.getInt("charged.aura-duration", 1));

        new BukkitRunnable() {
            int slashIndex = 0;

            @Override
            public void run() {
                if (!attacker.isOnline() || attacker.isDead()) {
                    cancel();
                    return;
                }

                slashIndex++;
                double multiplier = config.getDouble("charged.slash-" + slashIndex + "-damage-multiplier", 0.55);
                if (plugin.getConstellationManager() != null
                        && plugin.getConstellationManager().hasConstellation(attacker, CharacterType.TRAVELER_ANEMO, 1)) {
                    multiplier *= 1.20;
                }
                Location center = attacker.getLocation().clone().add(0, 1.0, 0);
                spawnChargedSlashEffects(center, slashIndex);

                List<LivingEntity> targets = collectConeTargets(attacker, preferredTarget, range, radius, coneDegrees, maxTargets);
                for (LivingEntity target : targets) {
                    applyChargedPhysicalDamage(attacker, target, multiplier, auraDuration);
                }

                if (slashIndex >= slashCount) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, intervalTicks);
    }

    private void castTornadoBurst(Player player) {
        stateManager.clearBurst(player);

        Vector forward = horizontalLookDirection(player);
        if (forward.lengthSquared() <= 0.0001) {
            return;
        }

        Location start = player.getLocation().clone().add(0, 1.1, 0).add(forward.clone().multiply(1.2));
        double speedPerTick = config.getDouble("q.speed-per-tick", 0.62);
        int durationTicks = Math.max(20, config.getInt("q.duration-ticks", 80));
        double radius = config.getDouble("q.radius", 2.25);
        double pullStrength = config.getDouble("q.pull-strength", 0.18);
        long hitCooldownMs = Math.max(100L, config.getLong("q.hit-cooldown-ms", 450L));
        int auraDuration = config.getInt("q.aura-duration", 120);
        double damageMultiplierValue = config.getDouble("q.damage-multiplier", 0.94);
        double convertedDamageMultiplierValue = config.getDouble("q.converted-damage-multiplier", 0.30);
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 5)) {
                damageMultiplierValue *= 1.25;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 6)) {
                convertedDamageMultiplierValue *= 1.25;
            }
        }
        final double damageMultiplier = damageMultiplierValue;
        final double convertedDamageMultiplier = convertedDamageMultiplierValue;

        BukkitRunnable runnable = new BukkitRunnable() {
            int tick = 0;
            final Location current = start.clone();
            final Map<UUID, Long> hitCooldowns = new HashMap<>();
            String absorbedElement = null;
            boolean absorbNotified = false;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    stateManager.clearBurst(player);
                    cancel();
                    return;
                }

                current.add(forward.clone().multiply(speedPerTick));
                spawnBurstEffects(current, absorbedElement, tick);

                if (absorbedElement == null) {
                    absorbedElement = resolveAbsorbedElement(player, null, current, radius + 1.0);
                }
                if (!absorbNotified && absorbedElement != null && notificationEnabled("q-absorb", true)) {
                    sendSkillMessage(player,
                            config.messagePath("q-absorb"),
                            "&b[风息激荡] 龙卷已吸收 {element} 元素。",
                            messagePlaceholders("element", elementDisplayName(absorbedElement)));
                    absorbNotified = true;
                }

                pullNearbyTargets(player, current, radius + 0.8, pullStrength, 0.05);
                applyBurstHit(current, player, hitCooldowns, hitCooldownMs, auraDuration,
                        damageMultiplier, convertedDamageMultiplier, absorbedElement,
                        "q.anemo", "q_anemo", "q.converted", "q_converted");

                tick++;
                if (tick < durationTicks) {
                    return;
                }

                stateManager.clearBurst(player);
                cancel();
            }
        };

        BukkitTask task = runnable.runTaskTimer(plugin, 0L, 1L);
        stateManager.trackBurstTask(player, task);
    }

    private void castGaleBlade(Player player) {
        if (!config.getBoolean("passive.slash.enabled", true)) {
            return;
        }

        Location start = player.getLocation().clone().add(0, 1.0, 0);
        Vector forward = horizontalLookDirection(player);
        double pathLength = config.getDouble("passive.slash.path-length", 5.0);
        double pathRadius = config.getDouble("passive.slash.path-radius", 1.0);
        double damageMultiplier = config.getDouble("passive.slash.damage-multiplier", 0.6);
        int auraDuration = config.getInt("passive.slash.aura-duration", 80);
        int maxTargets = Math.max(1, config.getInt("passive.slash.max-targets", 3));
        Location end = start.clone().add(forward.clone().multiply(pathLength));

        spawnGaleBladeEffects(start, end);
        List<LivingEntity> targets = collectLineTargets(player, start, end, pathRadius, maxTargets);
        for (LivingEntity target : targets) {
            double damage = applySkillDamage(
                    player,
                    target,
                    ElementConstant.ANEMO_KEY,
                    auraDuration,
                    damageMultiplier,
                    0.0,
                    WeaponAttackType.NORMAL,
                    "passive.slash",
                    "passive_slash"
            );
            if (damage > 0.0) {
                applyDirectionalPush(start, target, 0.38, 0.08);
            }
        }

        if (notificationEnabled("passive-slash", true)) {
            sendSkillMessage(player,
                    config.messagePath("passive-slash"),
                    "&7[裂空之风] 第五段攻击斩出风刃。",
                    messagePlaceholders("count", String.valueOf(targets.size())));
        }
    }

    private int applyPulseDamage(Player player, Location center, double radius,
                                 double damageMultiplier, double convertedDamageMultiplier,
                                 int auraDuration, String absorbedElement, boolean release,
                                 double knockbackStrength, double knockbackVertical,
                                 String anemoAttachmentSection, String anemoReactionGroup,
                                 String convertedAttachmentSection, String convertedReactionGroup) {
        boolean triggeredHeal = false;
        int hitCount = 0;
        for (LivingEntity target : nearbyTargets(center, radius, player)) {
            double totalDamage = applySkillDamage(
                    player,
                    target,
                    ElementConstant.ANEMO_KEY,
                    auraDuration,
                    damageMultiplier,
                    0.0,
                    WeaponAttackType.SKILL,
                    anemoAttachmentSection,
                    anemoReactionGroup
            );
            if (absorbedElement != null && convertedDamageMultiplier > 0.0 && !wasDefeated(target)) {
                totalDamage += applySkillDamage(
                        player,
                        target,
                        absorbedElement,
                        auraDuration,
                        convertedDamageMultiplier,
                        0.0,
                        WeaponAttackType.SKILL,
                        convertedAttachmentSection,
                        convertedReactionGroup
                );
            }
            if (totalDamage > 0.0) {
                hitCount++;
            }
            if (release && totalDamage > 0.0) {
                applyDirectionalPush(center, target, knockbackStrength, knockbackVertical);
            }
            if (!triggeredHeal && wasDefeated(target)) {
                triggerRevivalWind(player);
                triggeredHeal = true;
            }
        }
        return hitCount;
    }

    private boolean grantTapParticles(Player player) {
        if (player == null || plugin.getEnergyManager() == null) {
            return false;
        }
        return plugin.getEnergyManager().grantElementalParticles(player, ElementConstant.ANEMO_KEY,
                2, 2, 1.0, 600L, "traveler-anemo:e-tap");
    }

    private boolean grantHeldParticles(Player player) {
        if (player == null || plugin.getEnergyManager() == null) {
            return false;
        }
        return plugin.getEnergyManager().grantElementalParticles(player, ElementConstant.ANEMO_KEY,
                3, 4, 0.33, 600L, "traveler-anemo:e-hold");
    }

    private void triggerRevivalWind(Player player) {
        if (!config.getBoolean("passive.revival-wind.enabled", true)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(1000L, config.getLong("passive.revival-wind.cooldown-ms", 5000L));
        if (passiveHealCooldowns.getOrDefault(playerId, 0L) > now) {
            return;
        }
        passiveHealCooldowns.put(playerId, now + cooldownMs);
        stateManager.clearPassiveHeal(player);

        int durationTicks = Math.max(20, config.getInt("passive.revival-wind.duration-ticks", 100));
        int intervalTicks = Math.max(10, config.getInt("passive.revival-wind.interval-ticks", 20));
        double healRatioValue = config.getDouble("passive.revival-wind.heal-max-health-ratio-per-tick", 0.02);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.TRAVELER_ANEMO, 4)) {
            healRatioValue *= 1.50;
        }
        final double healRatio = healRatioValue;

        BukkitRunnable runnable = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    stateManager.clearPassiveHeal(player);
                    cancel();
                    return;
                }

                double maxHealth = resolveMaxHealth(player);
                double before = player.getHealth();
                double after = Math.min(maxHealth, before + (maxHealth * healRatio));
                plugin.setPlayerHealthSafely(player, after, true);

                Location center = player.getLocation().clone().add(0, 1.0, 0);
                player.getWorld().spawnParticle(Particle.HEART, center, 2, 0.25, 0.3, 0.25, 0.0);
                player.getWorld().spawnParticle(Particle.CLOUD, center, 6, 0.28, 0.28, 0.28, 0.02);

                elapsed += intervalTicks;
                if (elapsed < durationTicks) {
                    return;
                }

                stateManager.clearPassiveHeal(player);
                cancel();
            }
        };

        BukkitTask task = runnable.runTaskTimer(plugin, 0L, intervalTicks);
        stateManager.trackPassiveHealTask(player, task);

        if (notificationEnabled("passive-heal", true)) {
            sendSkillMessage(player,
                    config.messagePath("passive-heal"),
                    "&a[复苏之风] 接下来 {duration} 秒内持续恢复生命。",
                    messagePlaceholders(
                            "duration", String.format(Locale.US, "%.1f", durationTicks / 20.0),
                            "heal", ElementUtils.formatDamage(resolveMaxHealth(player) * healRatio)
                    ));
        }
    }

    private String resolveAbsorbedElement(Player player, LivingEntity directTarget, Location center, double radius) {
        String selfElement = detectAbsorbableElement(player);
        if (selfElement != null) {
            return selfElement;
        }

        String directElement = detectAbsorbableElement(directTarget);
        if (directElement != null) {
            return directElement;
        }

        for (String element : ABSORPTION_PRIORITY) {
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                    continue;
                }
                if (matchesAbsorbableElement(living, element)) {
                    return element;
                }
            }
        }
        return null;
    }

    private void pullElementalSkillTargets(Player player, Location center, double radius, double strength, double vertical) {
        for (Entity target : nearbyElementalSkillTargets(center, radius, player)) {
            Vector toCenter = center.toVector().subtract(targetCenter(target));
            double distance = Math.max(0.35, toCenter.length());
            if (distance <= 0.0001) {
                continue;
            }

            Vector retainedVelocity = target.getVelocity().clone().multiply(0.35);
            double distanceFactor = Math.min(2.35, 0.85 + (radius / distance));
            Vector pull = retainedVelocity.add(toCenter.normalize().multiply(strength * distanceFactor));
            if (distance <= 1.15) {
                pull.setX(pull.getX() * 0.18);
                pull.setZ(pull.getZ() * 0.18);
            }
            double lift = Math.max(vertical, vertical + ((radius - distance) / Math.max(radius, 0.001)) * 0.05);
            pull.setY(Math.max(target.getVelocity().getY() * 0.35, lift));
            target.setVelocity(pull);
        }
    }

    private void pullNearbyTargets(Player player, Location center, double radius, double strength, double vertical) {
        for (LivingEntity target : nearbyTargets(center, radius, player)) {
            Vector toCenter = center.toVector().subtract(targetCenter(target));
            double distance = Math.max(0.35, toCenter.length());
            if (distance <= 0.0001) {
                continue;
            }

            Vector retainedVelocity = target.getVelocity().clone().multiply(0.35);
            double distanceFactor = Math.min(2.35, 0.85 + (radius / distance));
            Vector pull = retainedVelocity.add(toCenter.normalize().multiply(strength * distanceFactor));
            if (distance <= 1.15) {
                pull.setX(pull.getX() * 0.18);
                pull.setZ(pull.getZ() * 0.18);
            }
            double lift = Math.max(vertical, vertical + ((radius - distance) / Math.max(radius, 0.001)) * 0.05);
            pull.setY(Math.max(target.getVelocity().getY() * 0.35, lift));
            target.setVelocity(pull);
        }
    }

    private void spawnPalmVortexChannelEffects(Location center, int tick, String absorbedElement) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        boolean heldCast = absorbedElement != null && absorbedElement.startsWith("held:");
        double radius = 1.05 + Math.min(heldCast ? 0.95 : 0.6, tick * (heldCast ? 0.04 : 0.03));
        double baseAngle = tick * (heldCast ? 0.62 : 0.45);
        int points = heldCast ? 14 : 10;
        for (int i = 0; i < points; i++) {
            double angle = baseAngle + (Math.PI * 2 * i / points);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, 0.1 + (i % 2 == 0 ? 0.15 : -0.05), z);
            world.spawnParticle(Particle.WHITE_ASH, point, 1, 0.02, 0.04, 0.02, 0.0);
            world.spawnParticle(Particle.CLOUD, point, 1, 0.03, 0.03, 0.03, 0.01);
        }
        for (int i = 0; i < (heldCast ? 6 : 4); i++) {
            double angle = -baseAngle + (Math.PI * 2 * i / (heldCast ? 6.0 : 4.0));
            double x = Math.cos(angle) * radius * 0.55;
            double z = Math.sin(angle) * radius * 0.55;
            Location point = center.clone().add(x, 0.5 + (i * 0.08), z);
            world.spawnParticle(Particle.WHITE_ASH, point, 1, 0.01, 0.03, 0.01, 0.0);
        }
        world.spawnParticle(Particle.SWEEP_ATTACK, center, heldCast ? 4 : 2, 0.24, 0.24, 0.24, 0.0);
        if (tick % (heldCast ? 3 : 4) == 0) {
            world.playSound(center, Sound.ENTITY_BREEZE_SHOOT, heldCast ? 0.55f : 0.4f, heldCast ? 0.85f : 1.1f);
        }
    }

    private void spawnPalmVortexReleaseEffects(Location center, String absorbedElement) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        boolean heldCast = absorbedElement != null && absorbedElement.startsWith("held:");
        world.spawnParticle(Particle.CLOUD, center, heldCast ? 42 : 28, 0.75, 0.4, 0.75, 0.08);
        world.spawnParticle(Particle.WHITE_ASH, center, heldCast ? 36 : 20, 0.65, 0.45, 0.65, 0.02);
        world.spawnParticle(Particle.SWEEP_ATTACK, center, heldCast ? 14 : 10, 0.45, 0.3, 0.45, 0.0);
        world.playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, heldCast ? 0.95f : 0.8f, heldCast ? 0.8f : 1.05f);
    }

    private void applyBurstHit(Location center, Player player, Map<UUID, Long> hitCooldowns,
                               long hitCooldownMs, int auraDuration, double damageMultiplier,
                               double convertedDamageMultiplier, String absorbedElement,
                               String anemoAttachmentSection, String anemoReactionGroup,
                               String convertedAttachmentSection, String convertedReactionGroup) {
        long now = System.currentTimeMillis();
        for (LivingEntity target : nearbyTargets(center, config.getDouble("q.radius", 2.25), player)) {
            long lastHit = hitCooldowns.getOrDefault(target.getUniqueId(), 0L);
            if (now - lastHit < hitCooldownMs) {
                continue;
            }
            hitCooldowns.put(target.getUniqueId(), now);

            double damage = applySkillDamage(
                    player,
                    target,
                    ElementConstant.ANEMO_KEY,
                    auraDuration,
                    damageMultiplier,
                    0.0,
                    WeaponAttackType.BURST,
                    anemoAttachmentSection,
                    anemoReactionGroup
            );
            if (absorbedElement != null && !wasDefeated(target)) {
                damage += applySkillDamage(
                        player,
                        target,
                        absorbedElement,
                        auraDuration,
                        convertedDamageMultiplier,
                        0.0,
                        WeaponAttackType.BURST,
                        convertedAttachmentSection,
                        convertedReactionGroup
                );
            }

            if (damage > 0.0 && notificationEnabled("q-hit", false)) {
                sendSkillMessage(player,
                        config.messagePath("q-hit"),
                        "&b[风息激荡] 命中 {target}，造成 {damage} 点伤害。",
                        messagePlaceholders(
                                "target", target.getName(),
                                "damage", ElementUtils.formatDamage(damage),
                                "element", absorbedElement == null ? "风" : elementDisplayName(absorbedElement)
                        ));
            }
        }
    }

    private List<LivingEntity> collectLineTargets(Player player, Location start, Location end,
                                                  double radius, int maxTargets) {
        double length = start.distance(end);
        Map<LivingEntity, Double> candidates = new HashMap<>();
        Collection<Entity> nearby = player.getWorld().getNearbyEntities(start, length + radius, 2.0, length + radius);
        for (Entity entity : nearby) {
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

        return candidates.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(maxTargets)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<LivingEntity> collectConeTargets(Player attacker, LivingEntity preferredTarget, double range,
                                                  double radius, double coneDegrees, int maxTargets) {
        Map<LivingEntity, Double> candidates = new HashMap<>();
        Vector look = horizontalLookDirection(attacker);
        double requiredDot = Math.cos(Math.toRadians(coneDegrees / 2.0));
        Location origin = attacker.getLocation().clone().add(0, 1.0, 0);

        for (Entity entity : attacker.getWorld().getNearbyEntities(attacker.getLocation(), range, 2.0, range)) {
            if (!(entity instanceof LivingEntity living) || living.equals(attacker) || !living.isValid() || living.isDead()) {
                continue;
            }

            Vector toTarget = targetCenter(living).subtract(origin.toVector());
            double distance = toTarget.length();
            if (distance > range || distance <= 0.0001) {
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

        return candidates.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(maxTargets)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Iterable<LivingEntity> nearbyTargets(Location center, double radius, Player owner) {
        Set<LivingEntity> targets = new HashSet<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof LivingEntity living
                    && !living.equals(owner)
                    && living.isValid()
                    && !living.isDead()) {
                targets.add(living);
            }
        }
        return targets;
    }

    private Iterable<Entity> nearbyElementalSkillTargets(Location center, double radius, Player owner) {
        Set<Entity> targets = new HashSet<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (isElementalSkillPullTarget(entity, owner)) {
                targets.add(entity);
            }
        }
        return targets;
    }

    private boolean isElementalSkillPullTarget(Entity entity, Player owner) {
        if (entity == null || entity.equals(owner) || !entity.isValid()) {
            return false;
        }
        if (entity instanceof Item) {
            return true;
        }
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return false;
        }
        return !isBossPullImmune(living);
    }

    private boolean isBossPullImmune(LivingEntity target) {
        EntityType type = target.getType();
        return type == EntityType.ENDER_DRAGON || type == EntityType.WITHER;
    }

    private void applyChargedPhysicalDamage(Player attacker, LivingEntity target, double damageMultiplier, int auraDuration) {
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
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new org.bukkit.metadata.FixedMetadataValue(plugin, attacker.getUniqueId().toString()));
        plugin.getReactionManager().markIncomingDamageElement(target, PlayerStats.PHYSICAL_KEY);
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

        if (auraDuration > 0) {
            target.setNoDamageTicks(0);
        }
    }

    private String detectAbsorbableElement(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        for (String element : ABSORPTION_PRIORITY) {
            if (matchesAbsorbableElement(entity, element)) {
                return element;
            }
        }
        return null;
    }

    private boolean matchesAbsorbableElement(LivingEntity entity, String element) {
        if (entity == null || element == null) {
            return false;
        }
        if (ElementConstant.ICE_KEY.equals(element) && new ElementAura(entity, plugin).hasStatus(ElementConstant.FROZEN_KEY)) {
            return true;
        }
        return plugin.hasElement(entity, element);
    }

    private void spawnChargedSlashEffects(Location center, int slashIndex) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double radius = 0.8 + (slashIndex * 0.08);
        for (int i = 0; i < 6; i++) {
            double angle = (Math.PI * 2 * i / 6.0) + (slashIndex * 0.35);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, (i % 2 == 0 ? 0.1 : -0.1), z);
            world.spawnParticle(Particle.SWEEP_ATTACK, point, 1, 0.02, 0.02, 0.02, 0.0);
            world.spawnParticle(Particle.CRIT, point, 1, 0.01, 0.01, 0.01, 0.0);
        }
        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.15f + (slashIndex * 0.08f));
    }

    private void applyDirectionalPush(Location origin, LivingEntity target, double strength, double vertical) {
        Vector direction = target.getLocation().toVector().subtract(origin.toVector());
        if (direction.lengthSquared() <= 0.0001) {
            direction = target.getLocation().getDirection();
        }
        Vector velocity = direction.normalize().multiply(strength);
        velocity.setY(Math.max(vertical, target.getVelocity().getY()));
        target.setVelocity(velocity);
    }

    private void applyDirectionalPush(Location origin, Entity target, double strength, double vertical) {
        if (target instanceof LivingEntity living) {
            applyDirectionalPush(origin, living, strength, vertical);
            return;
        }

        Vector direction = target.getLocation().toVector().subtract(origin.toVector());
        if (direction.lengthSquared() <= 0.0001) {
            direction = new Vector(0, 0, 1);
        }
        Vector velocity = direction.normalize().multiply(strength);
        velocity.setY(Math.max(vertical, target.getVelocity().getY()));
        target.setVelocity(velocity);
    }

    private void spawnBurstEffects(Location center, String absorbedElement, int tick) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        Particle converted = absorbedElement == null ? Particle.WHITE_ASH : particleFor(absorbedElement);
        double radius = 1.05 + (Math.sin(tick * 0.35) * 0.12);
        for (int layer = 0; layer < 3; layer++) {
            double y = -0.2 + (layer * 0.55);
            double angleOffset = tick * 0.38 + (layer * 0.75);
            for (int i = 0; i < 7; i++) {
                double angle = angleOffset + (Math.PI * 2 * i / 7.0);
                double x = Math.cos(angle) * radius;
                double z = Math.sin(angle) * radius;
                world.spawnParticle(Particle.CLOUD, center.clone().add(x, y, z), 1, 0.02, 0.02, 0.02, 0.0);
                if (absorbedElement != null && i % 2 == 0) {
                    world.spawnParticle(converted, center.clone().add(x * 0.7, y + 0.15, z * 0.7), 1, 0.02, 0.02, 0.02, 0.0);
                }
            }
        }

        world.spawnParticle(Particle.SWEEP_ATTACK, center, 2, 0.25, 0.45, 0.25, 0.0);
        if (tick % 6 == 0) {
            world.playSound(center, Sound.ENTITY_ALLAY_ITEM_TAKEN, 0.5f, 0.6f);
        }
    }

    private void spawnGaleBladeEffects(Location start, Location end) {
        World world = start.getWorld();
        if (world == null) {
            return;
        }

        Vector line = end.toVector().subtract(start.toVector());
        for (int i = 0; i <= 10; i++) {
            double factor = i / 10.0;
            Location point = start.clone().add(line.clone().multiply(factor));
            world.spawnParticle(Particle.SWEEP_ATTACK, point, 1, 0.02, 0.02, 0.02, 0.0);
            world.spawnParticle(Particle.CLOUD, point, 1, 0.05, 0.05, 0.05, 0.0);
        }
        world.playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.4f);
    }

    private Particle particleFor(String element) {
        if (element == null) {
            return Particle.CLOUD;
        }
        return switch (element) {
            case ElementConstant.FIRE_KEY -> Particle.FLAME;
            case ElementConstant.WATER_KEY -> Particle.SPLASH;
            case ElementConstant.ICE_KEY -> Particle.SNOWFLAKE;
            case ElementConstant.ELECTRO_KEY -> Particle.ELECTRIC_SPARK;
            default -> Particle.CLOUD;
        };
    }

    private boolean wasDefeated(LivingEntity target) {
        return target == null || !target.isValid() || target.isDead() || target.getHealth() <= 0.0;
    }

    private double resolveMaxHealth(Player player) {
        return plugin.getActualMaxHealth(player);
    }

    private double projectionAlongSegment(Location start, Location end, LivingEntity target) {
        Vector segment = end.toVector().subtract(start.toVector());
        double lengthSquared = segment.lengthSquared();
        if (lengthSquared <= 0.0001) {
            return 0.0;
        }

        Vector point = targetCenter(target).subtract(start.toVector());
        double t = point.dot(segment) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.sqrt(lengthSquared) * t;
    }

    private double distanceSquaredToSegment(Location start, Location end, LivingEntity target) {
        Vector startVector = start.toVector();
        Vector segment = end.toVector().subtract(startVector);
        double lengthSquared = segment.lengthSquared();
        if (lengthSquared <= 0.0001) {
            return targetCenter(target).distanceSquared(startVector);
        }

        Vector point = targetCenter(target).subtract(startVector);
        double t = point.dot(segment) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        Vector closest = startVector.clone().add(segment.multiply(t));
        return targetCenter(target).distanceSquared(closest);
    }

    private double distanceSquaredToRay(Location origin, Vector direction, LivingEntity target) {
        Vector dir = direction.clone();
        if (dir.lengthSquared() <= 0.0001) {
            return targetCenter(target).distanceSquared(origin.toVector());
        }
        dir.normalize();

        Vector point = targetCenter(target).subtract(origin.toVector());
        double projection = Math.max(0.0, point.dot(dir));
        Vector closest = origin.toVector().clone().add(dir.multiply(projection));
        return targetCenter(target).distanceSquared(closest);
    }

    private Vector targetCenter(LivingEntity target) {
        return target.getLocation().clone()
                .add(0, Math.min(1.0, target.getHeight() * 0.5), 0)
                .toVector();
    }

    private Vector targetCenter(Entity target) {
        if (target instanceof LivingEntity living) {
            return targetCenter(living);
        }
        return target.getLocation().clone()
                .add(0, Math.max(0.1, target.getHeight() * 0.5), 0)
                .toVector();
    }

    private String elementDisplayName(String element) {
        return switch (element) {
            case ElementConstant.FIRE_KEY -> "火";
            case ElementConstant.WATER_KEY -> "水";
            case ElementConstant.ICE_KEY -> "冰";
            case ElementConstant.ELECTRO_KEY -> "雷";
            default -> "风";
        };
    }

    private Vector horizontalLookDirection(Player player) {
        Vector direction = player.getLocation().getDirection().clone().setY(0);
        if (direction.lengthSquared() <= 0.0001) {
            return new Vector(0, 0, 1);
        }
        return direction.normalize();
    }

    private Location skillCenter(Player player, Vector forward, double distance, double yOffset) {
        return player.getLocation().clone().add(0, yOffset, 0).add(forward.clone().multiply(distance));
    }
}
