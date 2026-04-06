package org.yuanshen.yuanshen;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class YelanSkillHandler extends AbstractPolearmSkillHandler {

    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";

    private final Map<UUID, Long> lastCombatTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> breakthroughCooldownEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> normalAttackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BowChargeState> bowChargeStates = new ConcurrentHashMap<>();
    private final Map<UUID, BowShotContext> bowShotContexts = new ConcurrentHashMap<>();
    private final Map<UUID, ShotTravelData> trackedProjectiles = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> chargeReadyTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> burstStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> burstEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> burstNextCoordTimes = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> burstTasks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> burstStatSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> constellationHealthBonusEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Double> constellationHealthBonuses = new ConcurrentHashMap<>();
    private final BukkitTask projectileTicker;

    public YelanSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.YELAN);
        this.projectileTicker = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            tickTrackedProjectiles();
            tickBowChargeState();
        }, 1L, 1L);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.YELAN;
    }

    @Override
    public boolean supportsChargedAttack() {
        return config.getBoolean("charged.enabled", true);
    }

    @Override
    public boolean handlesNormalAttackInternally() {
        return true;
    }

    @Override
    public boolean isChargedAttack(Player attacker, ItemStack handItem) {
        // Yelan charged shots are now driven by vanilla bow draw / release,
        // not by swing-based charged-attack detection.
        return false;
    }

    @Override
    public boolean tryCastNormalAttack(Player attacker) {
        if (!isSelectedYelan(attacker)) {
            return false;
        }
        if (!plugin.getWeaponManager().canUseSelectedWeapon(attacker)) {
            return false;
        }

        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        if (handItem == null || handItem.getType() != Material.BOW) {
            return false;
        }

        long remain = getRemainingNormalAttackCooldown(attacker);
        if (remain > 0L) {
            return true;
        }

        long cooldownMs = Math.max(150L, config.getLong("normal.cooldown-ms", 450L));
        normalAttackCooldowns.put(attacker.getUniqueId(), System.currentTimeMillis() + cooldownMs);

        Arrow arrow = attacker.launchProjectile(Arrow.class);
        arrow.setCritical(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setVelocity(attacker.getEyeLocation().getDirection().normalize()
                .multiply(config.getDouble("normal.arrow-speed", 2.8)));

        trackedProjectiles.put(arrow.getUniqueId(), new ShotTravelData(
                attacker.getUniqueId(),
                attacker.getEyeLocation().clone(),
                Math.max(1.0, config.getDouble("normal.range", 10.0)),
                PlayerStats.PHYSICAL_KEY,
                false
        ));
        bowShotContexts.put(arrow.getUniqueId(), new BowShotContext(
                attacker.getUniqueId(),
                handItem.clone(),
                PlayerStats.PHYSICAL_KEY,
                false,
                false,
                1.0
        ));

        plugin.suppressNormalAttack(attacker, Math.max(250L, cooldownMs));
        markCombat(attacker);
        Location origin = attacker.getEyeLocation();
        attacker.getWorld().spawnParticle(Particle.CRIT, origin, 4, 0.08, 0.08, 0.08, 0.01);
        attacker.getWorld().playSound(origin, Sound.ENTITY_ARROW_SHOOT, 0.9f, 1.2f);
        return true;
    }

    @Override
    public LivingEntity findChargedAttackTarget(Player attacker) {
        double maxDistance = config.getDouble("charged.targeting.max-distance", 18.0);
        double minDistance = config.getDouble("charged.targeting.min-distance-for-raycast", 2.5);
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
        playChargedShotCastEffect(attacker, false);
    }

    private void playChargedShotCastEffect(Player attacker, boolean breakthroughShot) {
        if (attacker == null) {
            return;
        }
        Location center = attacker.getEyeLocation();
        attacker.getWorld().spawnParticle(Particle.SPLASH, center, breakthroughShot ? 14 : 8, 0.08, 0.08, 0.08, 0.01);
        attacker.getWorld().spawnParticle(Particle.BUBBLE_POP, center, breakthroughShot ? 10 : 5, 0.08, 0.08, 0.08, 0.01);
        attacker.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_WEAK, breakthroughShot ? 0.7f : 0.55f, breakthroughShot ? 1.55f : 1.35f);
    }

    @Override
    public int getChargedHitWindowTicks() {
        return Math.max(1, config.getInt("charged.hit-window-ticks", 16));
    }

    @Override
    public double getChargedAutoHitRange() {
        return config.getDouble("charged.auto-hit-range", 18.0);
    }

    @Override
    public double getChargedAutoHitConeDegrees() {
        return config.getDouble("charged.auto-hit-cone-degrees", 24.0);
    }

    @Override
    public double getChargedDamageMultiplier(Player attacker) {
        return config.getDouble("charged.damage-multiplier", 1.8);
    }

    @Override
    public boolean tryConsumeChargedAttack(Player attacker) {
        if (!super.tryConsumeChargedAttack(attacker)) {
            return false;
        }
        if (attacker == null) {
            return false;
        }
        markCombat(attacker);
        return true;
    }

    @Override
    public String resolveAttackElement(Player attacker, ItemStack handItem, String defaultElement) {
        return PlayerStats.PHYSICAL_KEY;
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

    @Override
    public void onChargedAttackHit(Player attacker, LivingEntity target) {
        markCombat(attacker);
        Location center = target.getLocation().clone().add(0, 1.0, 0);
        attacker.getWorld().spawnParticle(Particle.SPLASH, center, 16, 0.25, 0.25, 0.25, 0.06);
        attacker.getWorld().spawnParticle(Particle.BUBBLE_POP, center, 12, 0.22, 0.22, 0.22, 0.04);
        attacker.getWorld().playSound(center, Sound.ENTITY_DOLPHIN_SPLASH, 0.7f, 1.3f);
    }

    @Override
    public void onAttackResolved(Player attacker, LivingEntity target, boolean chargedAttack,
                                 boolean plungeAttack, String attackElement) {
        if (attacker == null) {
            return;
        }
        markCombat(attacker);
        if (target == null || chargedAttack || plungeAttack) {
            return;
        }
        if (!isBurstActive(attacker)) {
            return;
        }

        long now = System.currentTimeMillis();
        long nextAllowed = burstNextCoordTimes.getOrDefault(attacker.getUniqueId(), 0L);
        if (nextAllowed > now) {
            return;
        }

        long cooldownMs = Math.max(150L, config.getLong("q.coordinate-cooldown-ms", 1000L));
        burstNextCoordTimes.put(attacker.getUniqueId(), now + cooldownMs);
        fireBurstCoordinatedAttack(attacker, target);
    }

    @Override
    public void clearRuntimeState(Player player) {
        super.clearRuntimeState(player);
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        lastCombatTimes.remove(playerId);
        breakthroughCooldownEndTimes.remove(playerId);
        normalAttackCooldowns.remove(playerId);
        bowChargeStates.remove(playerId);
        burstStartTimes.remove(playerId);
        burstEndTimes.remove(playerId);
        burstNextCoordTimes.remove(playerId);
        burstStatSnapshots.remove(playerId);
        constellationHealthBonusEndTimes.remove(playerId);
        constellationHealthBonuses.remove(playerId);
        bowShotContexts.entrySet().removeIf(entry -> playerId.equals(entry.getValue().playerId()));
        trackedProjectiles.entrySet().removeIf(entry -> playerId.equals(entry.getValue().playerId()));
        BukkitTask readyTask = chargeReadyTasks.remove(playerId);
        if (readyTask != null) {
            readyTask.cancel();
        }
        BukkitTask task = burstTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    public void clearAllRuntimeState() {
        super.clearAllRuntimeState();
        for (BukkitTask task : burstTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        lastCombatTimes.clear();
        breakthroughCooldownEndTimes.clear();
        normalAttackCooldowns.clear();
        bowChargeStates.clear();
        bowShotContexts.clear();
        trackedProjectiles.clear();
        for (BukkitTask readyTask : chargeReadyTasks.values()) {
            if (readyTask != null) {
                readyTask.cancel();
            }
        }
        chargeReadyTasks.clear();
        burstStartTimes.clear();
        burstTasks.clear();
        burstEndTimes.clear();
        burstNextCoordTimes.clear();
        burstStatSnapshots.clear();
        constellationHealthBonusEndTimes.clear();
        constellationHealthBonuses.clear();
        if (projectileTicker != null) {
            projectileTicker.cancel();
        }
    }

    public void beginBowCharge(Player player, ItemStack handItem) {
        if (!isYelanBowCombat(player, handItem)) {
            return;
        }
        getOrCreateBowChargeState(player, System.currentTimeMillis());
    }

    public boolean usesBowCombatInput(Player player, ItemStack handItem) {
        return isYelanBowCombat(player, handItem);
    }

    public void clearBowCharge(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        bowChargeStates.remove(playerId);
        BukkitTask readyTask = chargeReadyTasks.remove(playerId);
        if (readyTask != null) {
            readyTask.cancel();
        }
    }

    public boolean handleBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return false;
        }
        ItemStack bow = event.getBow();
        if (!isSelectedYelan(player)) {
            return false;
        }
        if (!plugin.getWeaponManager().canUseSelectedWeapon(player)) {
            clearBowCharge(player);
            return false;
        }
        if (!isYelanBowCombat(player, bow)) {
            clearBowCharge(player);
            return false;
        }
        if (!(event.getProjectile() instanceof Projectile projectile)) {
            clearBowCharge(player);
            return false;
        }

        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        BowChargeState chargeState = bowChargeStates.remove(playerId);
        clearChargeReadyEffect(playerId);
        long drawDurationMs = chargeState == null ? 0L : Math.max(0L, now - chargeState.startTime());
        boolean breakthroughShot = chargeState != null && chargeState.breakthroughShot();
        long requiredChargeMs = breakthroughShot
                ? Math.max(1L, config.getLong("breakthrough.charge-ms", 1000L))
                : Math.max(1L, config.getLong("charged.charge-ms", 3000L));
        boolean chargedAttack = drawDurationMs >= requiredChargeMs;
        if (chargedAttack && !tryConsumeChargedAttack(player)) {
            event.setCancelled(true);
            return true;
        }
        if (chargedAttack && breakthroughShot) {
            startBreakthroughCooldown(player, now);
        }

        if (projectile instanceof AbstractArrow arrow) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }

        String attackElement = chargedAttack ? getChargedBowElement() : PlayerStats.PHYSICAL_KEY;
        double chargedMultiplier = chargedAttack
                ? (breakthroughShot
                ? config.getDouble("breakthrough.damage-multiplier", 2.4)
                : config.getDouble("charged.damage-multiplier", 1.8))
                : 1.0;
        if (chargedAttack
                && plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.YELAN, 6)
                && isBurstActive(player)) {
            chargedMultiplier *= breakthroughShot ? 1.60 : 1.35;
        }
        bowShotContexts.put(projectile.getUniqueId(), new BowShotContext(
                player.getUniqueId(),
                bow == null ? null : bow.clone(),
                attackElement,
                chargedAttack,
                breakthroughShot,
                chargedMultiplier
        ));
        trackedProjectiles.put(projectile.getUniqueId(), new ShotTravelData(
                playerId,
                player.getEyeLocation().clone(),
                resolveBowProjectileRange(chargedAttack),
                attackElement,
                breakthroughShot
        ));

        if (chargedAttack) {
            playChargedShotCastEffect(player, breakthroughShot);
            sendChargedShotMessage(player, breakthroughShot);
        }
        return true;
    }

    public BowShotContext consumeBowShotContext(Projectile projectile) {
        if (projectile == null) {
            return null;
        }
        return bowShotContexts.get(projectile.getUniqueId());
    }

    public void clearBowShotContext(Projectile projectile) {
        if (projectile == null) {
            return;
        }
        bowShotContexts.remove(projectile.getUniqueId());
        trackedProjectiles.remove(projectile.getUniqueId());
    }

    public void onBowShotResolved(Player attacker, LivingEntity target, Projectile projectile, BowShotContext shotContext) {
        if (attacker == null || target == null || shotContext == null) {
            return;
        }
        markCombat(attacker);
        if (!shotContext.chargedAttack()) {
            return;
        }

        Location center = target.getLocation().clone().add(0, 1.0, 0);
        boolean breakthroughShot = shotContext.breakthroughShot();
        attacker.getWorld().spawnParticle(Particle.SPLASH, center, breakthroughShot ? 26 : 16, 0.25, 0.25, 0.25, 0.06);
        attacker.getWorld().spawnParticle(Particle.BUBBLE_POP, center, breakthroughShot ? 18 : 12, 0.22, 0.22, 0.22, 0.04);
        attacker.getWorld().playSound(center, Sound.ENTITY_DOLPHIN_SPLASH, breakthroughShot ? 0.95f : 0.7f, breakthroughShot ? 1.45f : 1.3f);
        clearBowShotContext(projectile);
    }

    private boolean isSelectedYelan(Player player) {
        return player != null
                && plugin.hasActiveCharacter(player)
                && plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.YELAN);
    }

    private boolean isYelanBowCombat(Player player, ItemStack handItem) {
        return isSelectedYelan(player)
                && plugin.getWeaponManager().canUseSelectedWeapon(player)
                && handItem != null
                && handItem.getType() == Material.BOW;
    }

    public void markCombat(Player player) {
        if (player == null) {
            return;
        }
        lastCombatTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public double getPassiveMaxHealthBonus(Player player) {
        if (player == null || !config.getBoolean("passive.hp-bonus.enabled", true)) {
            return 0.0;
        }
        double perUniqueElement = config.getDouble("passive.hp-bonus.per-unique-element", 0.06);
        Set<String> distinctElements = new LinkedHashSet<>();
        for (ItemStack slotItem : plugin.getCharacterSlotManager().getSlotItems(player)) {
            CharacterType characterType = plugin.getCharacterResolver().resolveCharacter(slotItem);
            if (characterType != null && characterType.getElementKey() != null && !characterType.getElementKey().isBlank()) {
                distinctElements.add(characterType.getElementKey());
            }
        }
        return Math.max(0.0, distinctElements.size() * perUniqueElement);
    }

    public double getBurstTeamDamageBonusMultiplier(Player player) {
        if (player == null || !isBurstActive(player) || !config.getBoolean("passive.burst-ramp.enabled", true)) {
            return 1.0;
        }
        long start = burstStartTimes.getOrDefault(player.getUniqueId(), System.currentTimeMillis());
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - start);
        double elapsedSeconds = elapsedMs / 1000.0;
        double initialBonus = config.getDouble("passive.burst-ramp.initial-damage-bonus", 0.01);
        double perSecondBonus = config.getDouble("passive.burst-ramp.per-second-damage-bonus", 0.035);
        double maxBonus = config.getDouble("passive.burst-ramp.max-damage-bonus", 0.50);
        double bonus = Math.min(maxBonus, initialBonus + (elapsedSeconds * perSecondBonus));
        return 1.0 + Math.max(0.0, bonus);
    }

    public double getConstellationHealthBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        long end = constellationHealthBonusEndTimes.getOrDefault(player.getUniqueId(), 0L);
        if (end <= System.currentTimeMillis()) {
            constellationHealthBonusEndTimes.remove(player.getUniqueId());
            constellationHealthBonuses.remove(player.getUniqueId());
            return 0.0;
        }
        return constellationHealthBonuses.getOrDefault(player.getUniqueId(), 0.0);
    }

    private boolean tryCastE(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 10000L);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.YELAN, 1)) {
            cooldownMs = Math.max(1000L, Math.round(cooldownMs * 0.65));
        }
        if (!data.canUseE(CharacterType.YELAN, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.YELAN, cooldownMs);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[夜兰] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.YELAN);
        data.addEnergy(CharacterType.YELAN, config.getInt("e.energy-gain", 20));
        markCombat(player);
        castLingeringLifeline(player, directTarget);

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&b[夜兰] 萦络纵命索展开，开始高速缠络目标。",
                    messagePlaceholders());
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[夜兰] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.YELAN)),
                            "max_energy", String.valueOf(config.getInt("q.energy-cost", 70))
                    ));
        }
        return true;
    }

    private boolean tryCastQ(Player player, PlayerSkillData data) {
        if (!config.getBoolean("q.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("q.cooldown-ms", 18000L);
        int energyCost = config.getInt("q.energy-cost", 70);
        if (!data.canUseQ(CharacterType.YELAN, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.YELAN, cooldownMs);
            if (remain > 0L) {
                player.sendMessage(translateColors(config.getString(
                        "messages.q-cooldown",
                        "&c[夜兰] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatDurationSeconds(remain))));
            } else {
                player.sendMessage(translateColors(config.getString(
                                "messages.q-no-energy",
                                "&c[夜兰] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.YELAN)))
                        .replace("{max_energy}", String.valueOf(energyCost))));
            }
            return true;
        }

        data.useQ(CharacterType.YELAN, energyCost);
        markCombat(player);
        activateBurst(player);
        if (notificationEnabled("q-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("q-cast"),
                    "&b[夜兰] 渊图玲珑骰已释放，开始协同水箭攻击。",
                    messagePlaceholders());
        }
        return true;
    }

    private void castLingeringLifeline(Player player, LivingEntity directTarget) {
        Vector forward = resolveDashDirection(player, directTarget);
        int dashTicks = Math.max(4, config.getInt("e.dash-ticks", 10));
        double dashSpeed = config.getDouble("e.dash-speed", 1.08);
        double markRadius = config.getDouble("e.mark-radius", 1.6);
        double detonateMultiplierValue = config.getDouble("e.max-health-damage-multiplier", 0.11);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.YELAN, 3)) {
            detonateMultiplierValue *= 1.25;
        }
        final double detonateMultiplier = detonateMultiplierValue;
        int auraDuration = config.getInt("e.aura-duration", 120);
        Set<UUID> markedTargets = new LinkedHashSet<>();

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                Vector velocity = forward.clone().multiply(dashSpeed);
                velocity.setY(Math.max(0.05, player.getVelocity().getY()));
                player.setVelocity(velocity);

                Location center = player.getLocation().clone().add(0, 1.0, 0);
                World world = center.getWorld();
                if (world != null) {
                    world.spawnParticle(Particle.SPLASH, center, 8, 0.18, 0.12, 0.18, 0.02);
                    world.spawnParticle(Particle.BUBBLE_POP, center, 6, 0.16, 0.12, 0.16, 0.02);
                    if (tick % 2 == 0) {
                        world.playSound(center, Sound.ENTITY_DOLPHIN_SWIM, 0.45f, 1.6f);
                    }
                }

                for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), markRadius, 1.4, markRadius)) {
                    if (entity instanceof LivingEntity living
                            && !living.equals(player)
                            && living.isValid()
                            && !living.isDead()) {
                        markedTargets.add(living.getUniqueId());
                    }
                }

                tick++;
                if (tick < dashTicks) {
                    return;
                }

                player.setVelocity(player.getVelocity().multiply(0.35));
                detonateLifeline(player, markedTargets, directTarget, detonateMultiplier, auraDuration);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void detonateLifeline(Player player, Set<UUID> markedTargets, LivingEntity directTarget,
                                  double damageMultiplier, int auraDuration) {
        List<LivingEntity> targets = new ArrayList<>();
        for (UUID targetId : markedTargets) {
            Entity entity = plugin.getServer().getEntity(targetId);
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                targets.add(living);
            }
        }
        if (targets.isEmpty() && directTarget != null && directTarget.isValid() && !directTarget.isDead()) {
            targets.add(directTarget);
        }

        Location burstCenter = player.getLocation().clone().add(0, 1.0, 0);
        World world = burstCenter.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.SPLASH, burstCenter, 24, 0.45, 0.25, 0.45, 0.08);
            world.spawnParticle(Particle.BUBBLE_POP, burstCenter, 18, 0.42, 0.22, 0.42, 0.06);
            world.playSound(burstCenter, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.85f, 1.2f);
        }

        int hitCount = 0;
        for (LivingEntity target : targets) {
            double damage = applyMaxHealthSkillDamage(
                    player,
                    target,
                    ElementConstant.WATER_KEY,
                    auraDuration,
                    damageMultiplier,
                    true,
                    WeaponAttackType.SKILL,
                    "e",
                    "e"
            );
            if (damage > 0.0) {
                hitCount++;
                spawnHydroImpact(player.getLocation().clone().add(0, 1.0, 0), target.getLocation().clone().add(0, 1.0, 0));
            }
        }

        if (hitCount > 0 && plugin.getEnergyManager() != null) {
            plugin.getEnergyManager().grantElementalParticles(player, ElementConstant.WATER_KEY,
                    4, 4, 1.0, 300L, "yelan:lifeline");
        }

        if (hitCount > 0
                && plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.YELAN, 4)) {
            double hpBonus = Math.min(0.40, hitCount * 0.10);
            constellationHealthBonuses.put(player.getUniqueId(), hpBonus);
            constellationHealthBonusEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + 25_000L);
            plugin.refreshPlayerStats(player);
        }

        if (hitCount > 0 && notificationEnabled("e-hit", false)) {
            sendSkillMessage(player,
                    config.messagePath("e-hit"),
                    "&b[夜兰] 络命丝引爆，命中 {count} 个目标。",
                    messagePlaceholders("count", String.valueOf(hitCount)));
        }
    }

    private void activateBurst(Player player) {
        clearBurst(player);

        long durationMs = Math.max(1000L, config.getLong("q.duration-ticks", 300L) * 50L);
        UUID playerId = player.getUniqueId();
        burstStartTimes.put(playerId, System.currentTimeMillis());
        burstEndTimes.put(playerId, System.currentTimeMillis() + durationMs);
        burstNextCoordTimes.put(playerId, 0L);
        PlayerStats currentStats = plugin.getPlayerStats(player);
        if (currentStats != null) {
            burstStatSnapshots.put(playerId, currentStats.copy());
        }

        BukkitTask task = new BukkitRunnable() {
            double angle = 0.0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || !isBurstActive(player)) {
                    clearBurst(player);
                    cancel();
                    return;
                }

                Location center = player.getLocation().clone().add(0, 1.1, 0);
                World world = center.getWorld();
                if (world == null) {
                    return;
                }

                for (int i = 0; i < 3; i++) {
                    double current = angle + (Math.PI * 2 * i / 3.0);
                    double x = Math.cos(current) * 0.65;
                    double z = Math.sin(current) * 0.65;
                    Location point = center.clone().add(x, 0.15 * i, z);
                    world.spawnParticle(Particle.SPLASH, point, 1, 0.02, 0.02, 0.02, 0.0);
                    world.spawnParticle(Particle.BUBBLE_POP, point, 1, 0.02, 0.02, 0.02, 0.0);
                }
                if (((int) angle) % 3 == 0) {
                    world.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.25f, 1.45f);
                }
                angle += 0.5;
            }
        }.runTaskTimer(plugin, 0L, 4L);
        burstTasks.put(playerId, task);
    }

    private void fireBurstCoordinatedAttack(Player player, LivingEntity primaryTarget) {
        List<LivingEntity> targets = collectBurstTargets(player, primaryTarget);
        if (targets.isEmpty()) {
            return;
        }

        double damageMultiplier = config.getDouble("q.coordinate-max-health-damage-multiplier", 0.07);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.YELAN, 5)) {
            damageMultiplier *= 1.25;
        }
        int auraDuration = config.getInt("q.aura-duration", 120);
        PlayerStats statSnapshot = burstStatSnapshots.get(player.getUniqueId());
        for (LivingEntity target : targets) {
            spawnHydroImpact(player.getEyeLocation(), target.getLocation().clone().add(0, 1.0, 0));
            double damage = applyMaxHealthSkillDamage(
                    player,
                    target,
                    ElementConstant.WATER_KEY,
                    auraDuration,
                    damageMultiplier,
                    false,
                    WeaponAttackType.BURST,
                    "q.coordinate",
                    "q_coordinate",
                    statSnapshot
            );
            if (damage > 0.0 && notificationEnabled("q-hit", false)) {
                sendSkillMessage(player,
                        config.messagePath("q-hit"),
                        "&b[夜兰] 渊图玲珑骰命中 {target}，造成 {damage} 点水元素伤害。",
                        messagePlaceholders(
                                "target", target.getName(),
                                "damage", ElementUtils.formatDamage(damage)
                        ));
            }
        }

        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.YELAN, 2)
                && primaryTarget != null
                && primaryTarget.isValid()
                && !primaryTarget.isDead()) {
            spawnHydroImpact(player.getEyeLocation(), primaryTarget.getLocation().clone().add(0, 1.0, 0));
            applyMaxHealthSkillDamage(
                    player,
                    primaryTarget,
                    ElementConstant.WATER_KEY,
                    auraDuration,
                    damageMultiplier * 0.85,
                    false,
                    WeaponAttackType.BURST,
                    "q.coordinate",
                    "q_coordinate",
                    statSnapshot
            );
        }
    }

    private List<LivingEntity> collectBurstTargets(Player player, LivingEntity primaryTarget) {
        LinkedHashSet<LivingEntity> targets = new LinkedHashSet<>();
        if (primaryTarget != null && primaryTarget.isValid() && !primaryTarget.isDead()) {
            targets.add(primaryTarget);
        }

        double radius = config.getDouble("q.coordinate-search-radius", 4.5);
        int maxTargets = Math.max(1, config.getInt("q.coordinate-max-targets", 3));
        Location center = primaryTarget != null ? primaryTarget.getLocation() : player.getLocation();
        for (Entity entity : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)
                    || living.equals(player)
                    || living.isDead()
                    || !living.isValid()) {
                continue;
            }
            targets.add(living);
            if (targets.size() >= maxTargets) {
                break;
            }
        }
        return new ArrayList<>(targets);
    }

    private double applyMaxHealthSkillDamage(Player caster, LivingEntity target, String element,
                                             int auraDuration, double healthRatio, boolean applyGlobalBonus,
                                             WeaponAttackType attackType, String attachmentSection,
                                             String reactionGroupFallback) {
        return applyMaxHealthSkillDamage(caster, target, element, auraDuration, healthRatio, applyGlobalBonus,
                attackType, attachmentSection, reactionGroupFallback, null);
    }

    private double applyMaxHealthSkillDamage(Player caster, LivingEntity target, String element,
                                             int auraDuration, double healthRatio, boolean applyGlobalBonus,
                                             WeaponAttackType attackType, String attachmentSection,
                                             String reactionGroupFallback, PlayerStats statSnapshot) {
        PlayerStats stats = statSnapshot != null ? statSnapshot : plugin.getPlayerStats(caster);
        if (stats == null) {
            return 0.0;
        }

        DamageResult damageResult = new DamageResult();
        damageResult.setPlayerStats(stats);
        damageResult.setPhysicalDamage(0.0);
        damageResult.setElement(element);

        double elementalDamage = Math.max(0.0, resolveMaxHealth(caster, stats) * healthRatio);
        elementalDamage *= (1.0 + stats.getElementBonus(element));
        damageResult.setElementalDamage(elementalDamage);
        damageResult.setCrit(Math.random() < Math.max(0.0, Math.min(1.0, stats.getCritRate())));

        ElementReactionManager.AttachmentProfile attachmentProfile = buildReactionAttachmentProfile(
                caster,
                attachmentSection,
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

        double preResistanceMainDamage = damageResult.getElementalDamage() + reactionResult.getScalingDamageBonus();
        preResistanceMainDamage += reactionResult.getAdditiveDamageBonus();
        if (applyGlobalBonus) {
            preResistanceMainDamage *= plugin.getGlobalDamageBonusMultiplier(caster, element);
        }
        if (damageResult.isCrit()) {
            preResistanceMainDamage *= (1.0 + stats.getCritDamage());
        }

        double finalMainDamage = plugin.applyMobResistance(target, preResistanceMainDamage, element);
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, caster.getUniqueId().toString()));
        plugin.getReactionManager().markIncomingDamageElement(target, element);
        target.setNoDamageTicks(0);
        target.damage(finalMainDamage, caster);
        double appliedTransformativeDamage = plugin.getReactionManager()
                .applyTransformativeReactionDamage(caster, target, reactionResult);

        plugin.getReactionManager().consumeReactionAuras(target, reactionResult, element);
        plugin.getReactionManager().applyTriggerAura(target, element, reactionResult, auraDuration,
                getReactionAuraAmount(attachmentSection, attackType));
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

    private double resolveMaxHealth(Player caster, PlayerStats stats) {
        if (stats != null && stats.getHealth() > 0.0) {
            return stats.getHealth();
        }
        return caster.getMaxHealth();
    }

    private boolean isBurstActive(Player player) {
        if (player == null) {
            return false;
        }
        Long end = burstEndTimes.get(player.getUniqueId());
        if (end == null) {
            return false;
        }
        if (end <= System.currentTimeMillis()) {
            clearBurst(player);
            return false;
        }
        return true;
    }

    private void clearBurst(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        burstEndTimes.remove(playerId);
        burstNextCoordTimes.remove(playerId);
        burstStartTimes.remove(playerId);
        burstStatSnapshots.remove(playerId);
        BukkitTask task = burstTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private boolean isBreakthroughAvailable(Player player) {
        if (player == null || !config.getBoolean("breakthrough.enabled", true)) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        long cooldownEnd = breakthroughCooldownEndTimes.getOrDefault(playerId, 0L);
        if (cooldownEnd <= System.currentTimeMillis()) {
            breakthroughCooldownEndTimes.remove(playerId);
            return !hasNearbyBreakthroughBlocker(player);
        }
        return false;
    }

    private boolean hasNearbyBreakthroughBlocker(Player player) {
        if (player == null || player.getWorld() == null) {
            return false;
        }
        double radius = Math.max(0.0, config.getDouble("breakthrough.enemy-check-radius", 10.0));
        if (radius <= 0.0) {
            return false;
        }

        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius)) {
            if (entity.equals(player)) {
                continue;
            }
            if (entity instanceof Player otherPlayer && otherPlayer.isOnline() && !otherPlayer.isDead()) {
                return true;
            }
            if (entity instanceof Monster monster && monster.isValid() && !monster.isDead()) {
                return true;
            }
        }
        return false;
    }

    private void startBreakthroughCooldown(Player player, long now) {
        if (player == null) {
            return;
        }
        long cooldownMs = Math.max(0L, config.getLong("breakthrough.cooldown-ms", 5000L));
        breakthroughCooldownEndTimes.put(player.getUniqueId(), now + cooldownMs);
    }

    private BowChargeState getOrCreateBowChargeState(Player player, long now) {
        UUID playerId = player.getUniqueId();
        BowChargeState state = bowChargeStates.get(playerId);
        if (state != null) {
            return state;
        }
        BowChargeState created = new BowChargeState(now, isBreakthroughAvailable(player));
        bowChargeStates.put(playerId, created);
        return created;
    }

    private boolean hasMatchingChargeState(UUID playerId, long startTime, boolean breakthroughShot) {
        BowChargeState currentState = bowChargeStates.get(playerId);
        return currentState != null
                && currentState.startTime() == startTime
                && currentState.breakthroughShot() == breakthroughShot;
    }

    private String getChargedBowElement() {
        String elementKey = getCharacterType().getElementKey();
        if (elementKey == null || elementKey.isBlank()) {
            return ElementConstant.WATER_KEY;
        }
        return elementKey;
    }

    private double resolveBowProjectileRange(boolean chargedAttack) {
        if (chargedAttack) {
            return Math.max(
                    config.getDouble("charged.path-length", 18.0),
                    config.getDouble("charged.auto-hit-range", 18.0)
            );
        }
        return Math.max(1.0, config.getDouble("normal.range", 10.0));
    }

    private void sendChargedShotMessage(Player player, boolean breakthroughShot) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String messagePath = breakthroughShot ? "messages.breakthrough-fired" : "messages.charged-fired";
        String fallback = breakthroughShot
                ? "&b[夜兰] 破局矢已射出，造成水元素伤害，5 秒后可再次触发破局。"
                : "&b[夜兰] 重击已射出，造成水元素伤害。";
        player.sendMessage(translateColors(config.getString(messagePath, fallback)));
    }

    private void triggerBreakthroughSplash(Player attacker, LivingEntity primaryTarget) {
        if (attacker == null || primaryTarget == null || !config.getBoolean("breakthrough.enabled", true)) {
            return;
        }
        double radius = config.getDouble("breakthrough.radius", 2.8);
        int auraDuration = config.getInt("breakthrough.aura-duration", config.getInt("charged.aura-duration", 120));
        double multiplier = config.getDouble("breakthrough.max-health-damage-multiplier", 0.12);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(attacker, CharacterType.YELAN, 6)
                && isBurstActive(attacker)) {
            multiplier *= 1.40;
        }
        Location center = primaryTarget.getLocation().clone().add(0, 1.0, 0);

        int hitCount = 0;
        for (Entity entity : primaryTarget.getWorld().getNearbyEntities(primaryTarget.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || living.equals(attacker) || living.isDead() || !living.isValid()) {
                continue;
            }
            double damage = applyMaxHealthSkillDamage(attacker, living, ElementConstant.WATER_KEY, auraDuration, multiplier, true,
                    WeaponAttackType.CHARGED, "breakthrough", "breakthrough");
            if (damage > 0.0) {
                hitCount++;
            }
        }

        primaryTarget.getWorld().spawnParticle(Particle.SPLASH, center, 20, radius * 0.25, 0.28, radius * 0.25, 0.08);
        primaryTarget.getWorld().spawnParticle(Particle.BUBBLE_POP, center, 16, radius * 0.22, 0.24, radius * 0.22, 0.05);
        primaryTarget.getWorld().playSound(center, Sound.ENTITY_GENERIC_SPLASH, 0.9f, 1.45f);

        if (hitCount > 0 && notificationEnabled("breakthrough-hit", false)) {
            sendSkillMessage(attacker,
                    config.messagePath("breakthrough-hit"),
                    "&b[夜兰] 破局矢在目标周围炸开，额外命中 {count} 个目标。",
                    messagePlaceholders("count", String.valueOf(hitCount)));
        }
    }

    private Vector resolveDashDirection(Player player, LivingEntity directTarget) {
        if (directTarget != null && directTarget.isValid() && !directTarget.isDead()) {
            Vector toTarget = directTarget.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
            if (toTarget.lengthSquared() > 0.0001) {
                return toTarget.normalize();
            }
        }
        Vector look = player.getLocation().getDirection().clone().setY(0);
        if (look.lengthSquared() <= 0.0001) {
            return new Vector(0, 0, 1);
        }
        return look.normalize();
    }

    private void spawnHydroImpact(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) {
            return;
        }

        Vector line = to.toVector().subtract(from.toVector());
        int points = 6;
        for (int i = 0; i <= points; i++) {
            double factor = i / (double) points;
            Location point = from.clone().add(line.clone().multiply(factor));
            world.spawnParticle(Particle.SPLASH, point, 1, 0.02, 0.02, 0.02, 0.0);
            world.spawnParticle(Particle.BUBBLE_POP, point, 1, 0.02, 0.02, 0.02, 0.0);
        }
        world.playSound(to, Sound.ENTITY_DOLPHIN_SPLASH, 0.5f, 1.5f);
    }

    private void notifyChargeReady(UUID playerId, long startTime, boolean breakthroughShot) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }

        if (!hasMatchingChargeState(playerId, startTime, breakthroughShot)) {
            return;
        }
        if (!isYelanBowCombat(player, player.getInventory().getItemInMainHand())) {
            return;
        }

        String messagePath = breakthroughShot ? "messages.breakthrough-ready" : "messages.charged-ready";
        String fallback = breakthroughShot
                ? "&b[夜兰] 破局矢已蓄满，可发射水元素破局矢。"
                : "&b[夜兰] 重击已蓄满，可发射水元素箭。";
        player.sendMessage(translateColors(config.getString(messagePath, fallback)));
        player.getWorld().playSound(player.getEyeLocation(), Sound.BLOCK_AMETHYST_CLUSTER_HIT,
                breakthroughShot ? 1.0f : 0.85f, breakthroughShot ? 1.9f : 1.7f);
        startChargeReadyEffect(playerId, startTime, breakthroughShot);
    }

    private void startChargeReadyEffect(UUID playerId, long startTime, boolean breakthroughShot) {
        clearChargeReadyEffect(playerId);

        BukkitTask readyTask = new BukkitRunnable() {
            @Override
            public void run() {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player == null || !player.isOnline() || player.isDead()) {
                    stop();
                    return;
                }

                if (!hasMatchingChargeState(playerId, startTime, breakthroughShot)) {
                    stop();
                    return;
                }
                if (!isYelanBowCombat(player, player.getInventory().getItemInMainHand())) {
                    stop();
                    return;
                }

                renderChargeReadyEffect(player, breakthroughShot);
            }

            private void stop() {
                clearChargeReadyEffect(playerId);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        chargeReadyTasks.put(playerId, readyTask);
    }

    private void clearChargeReadyEffect(UUID playerId) {
        BukkitTask task = chargeReadyTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void renderChargeReadyEffect(Player player, boolean breakthroughShot) {
        Location bowCenter = player.getEyeLocation().clone()
                .add(player.getLocation().getDirection().normalize().multiply(0.55))
                .add(0.0, -0.18, 0.0);
        World world = bowCenter.getWorld();
        if (world == null) {
            return;
        }

        int splashCount = breakthroughShot ? 10 : 6;
        int bubbleCount = breakthroughShot ? 8 : 4;
        double spread = breakthroughShot ? 0.12 : 0.08;
        world.spawnParticle(Particle.SPLASH, bowCenter, splashCount, spread, spread, spread, 0.01);
        world.spawnParticle(Particle.BUBBLE_POP, bowCenter, bubbleCount, spread, spread, spread, 0.01);
    }

    private long getRemainingNormalAttackCooldown(Player attacker) {
        if (attacker == null) {
            return 0L;
        }
        long end = normalAttackCooldowns.getOrDefault(attacker.getUniqueId(), 0L);
        long remain = end - System.currentTimeMillis();
        if (remain <= 0L) {
            normalAttackCooldowns.remove(attacker.getUniqueId());
            return 0L;
        }
        return remain;
    }

    private void tickTrackedProjectiles() {
        for (Map.Entry<UUID, ShotTravelData> entry : new ArrayList<>(trackedProjectiles.entrySet())) {
            UUID projectileId = entry.getKey();
            Entity entity = plugin.getServer().getEntity(projectileId);
            if (!(entity instanceof Projectile projectile) || !projectile.isValid() || projectile.isDead()) {
                trackedProjectiles.remove(projectileId);
                bowShotContexts.remove(projectileId);
                continue;
            }

            ShotTravelData data = entry.getValue();
            Location currentLocation = projectile.getLocation();
            if (currentLocation.getWorld() == null
                    || data.origin().getWorld() == null
                    || currentLocation.getWorld() != data.origin().getWorld()) {
                projectile.remove();
                trackedProjectiles.remove(projectileId);
                bowShotContexts.remove(projectileId);
                continue;
            }

            renderProjectileTrail(projectile, data);
            if (currentLocation.distanceSquared(data.origin()) >= data.maxDistance() * data.maxDistance()) {
                projectile.remove();
                trackedProjectiles.remove(projectileId);
                bowShotContexts.remove(projectileId);
            }
        }
    }

    private void tickBowChargeState() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isSelectedYelan(player)) {
                clearBowCharge(player);
                continue;
            }

            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (!isYelanBowCombat(player, handItem) || !player.isHandRaised()) {
                clearBowCharge(player);
                continue;
            }

            UUID playerId = player.getUniqueId();
            BowChargeState state = getOrCreateBowChargeState(player, now);
            if (chargeReadyTasks.containsKey(playerId)) {
                continue;
            }

            boolean breakthroughShot = state.breakthroughShot();
            long requiredChargeMs = breakthroughShot
                    ? Math.max(1L, config.getLong("breakthrough.charge-ms", 1000L))
                    : Math.max(1L, config.getLong("charged.charge-ms", 3000L));
            if (now - state.startTime() >= requiredChargeMs) {
                notifyChargeReady(playerId, state.startTime(), breakthroughShot);
            }
        }
    }

    private void renderProjectileTrail(Projectile projectile, ShotTravelData data) {
        if (projectile == null || data == null) {
            return;
        }
        if (PlayerStats.PHYSICAL_KEY.equals(data.attackElement())) {
            return;
        }

        Location location = projectile.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        if (ElementConstant.WATER_KEY.equals(data.attackElement())) {
            int splashCount = data.breakthroughShot() ? 5 : 3;
            int bubbleCount = data.breakthroughShot() ? 4 : 2;
            double spread = data.breakthroughShot() ? 0.12 : 0.08;
            world.spawnParticle(Particle.SPLASH, location, splashCount, spread, spread, spread, 0.01);
            world.spawnParticle(Particle.BUBBLE_POP, location, bubbleCount, spread, spread, spread, 0.01);
        }
    }

    public record BowShotContext(UUID playerId, ItemStack handItemSnapshot, String attackElement,
                                 boolean chargedAttack, boolean breakthroughShot, double chargedMultiplier) {
    }

    private record ShotTravelData(UUID playerId, Location origin, double maxDistance,
                                  String attackElement, boolean breakthroughShot) {
    }

    private record BowChargeState(long startTime, boolean breakthroughShot) {
    }
}
