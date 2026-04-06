package org.yuanshen.yuanshen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.FluidCollisionMode;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KeqingSkillHandler extends AbstractPolearmSkillHandler {

    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";

    private final Map<UUID, StilettoData> stilettos = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> stilettoTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> stilettoFlightTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> infusionEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> burstBuffEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> burstBuffTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> electroBonusEndTimes = new ConcurrentHashMap<>();

    public KeqingSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.KEQING);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.KEQING;
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
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.KEQING)) {
            return false;
        }
        return supportsChargedAttack() && (!config.getBoolean("charged.require-sneaking", true) || attacker.isSneaking());
    }

    @Override
    public void beginChargedAttack(Player attacker, LivingEntity target) {
        if (consumeStiletto(attacker, false)) {
            return;
        }

        double damageMultiplier = config.getDouble("charged.damage-multiplier", 1.28);
        double secondSlashMultiplier = config.getDouble("charged.second-slash-damage-multiplier", 1.12);
        double range = config.getDouble("charged.range", 3.8);
        double radius = config.getDouble("charged.radius", 1.6);
        int maxTargets = Math.max(1, config.getInt("charged.max-targets", 3));
        List<LivingEntity> targets = collectConeTargets(attacker, target, range, radius, 90.0, maxTargets);
        if (targets.isEmpty() && target != null && target.isValid() && !target.isDead()) {
            targets.add(target);
        }
        if (targets.isEmpty()) {
            return;
        }

        Location center = attacker.getLocation().clone().add(0, 1.0, 0);
        attacker.getWorld().spawnParticle(Particle.CRIT, center, 12, 0.45, 0.2, 0.45, 0.03);
        attacker.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, center, 8, 0.35, 0.18, 0.35, 0.02);
        attacker.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.75f, 1.35f);
        for (LivingEntity living : targets) {
            applyPhysicalChargedHit(attacker, living, damageMultiplier);
            applyPhysicalChargedHit(attacker, living, secondSlashMultiplier);
        }
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
    public String resolveAttackElement(Player attacker, ItemStack handItem, String defaultElement) {
        return isElectroInfusionActive(attacker) ? ElementConstant.ELECTRO_KEY : defaultElement;
    }

    @Override
    public double getElementDamageBonusMultiplier(Player attacker, String elementKey) {
        if (attacker == null || !ElementConstant.ELECTRO_KEY.equals(elementKey)) {
            return 1.0;
        }

        double bonus = 0.0;
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(attacker, CharacterType.KEQING, 6)
                && isElectroInfusionActive(attacker)) {
            bonus += 0.15;
        }
        long extraEnd = electroBonusEndTimes.getOrDefault(attacker.getUniqueId(), 0L);
        if (extraEnd > System.currentTimeMillis()) {
            bonus += 0.20;
        } else {
            electroBonusEndTimes.remove(attacker.getUniqueId());
        }
        return 1.0 + bonus;
    }

    public double getBurstCritRateBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        long end = burstBuffEndTimes.getOrDefault(player.getUniqueId(), 0L);
        if (end <= System.currentTimeMillis()) {
            burstBuffEndTimes.remove(player.getUniqueId());
            return 0.0;
        }
        return config.getDouble("passive.q.crit-rate-bonus", 0.15);
    }

    public double getBurstEnergyRechargeBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        long end = burstBuffEndTimes.getOrDefault(player.getUniqueId(), 0L);
        if (end <= System.currentTimeMillis()) {
            burstBuffEndTimes.remove(player.getUniqueId());
            return 0.0;
        }
        return config.getDouble("passive.q.energy-recharge-bonus", 0.15);
    }

    public void clearRuntimeState(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        clearActiveStiletto(playerId);
        infusionEndTimes.remove(playerId);
        burstBuffEndTimes.remove(playerId);
        electroBonusEndTimes.remove(playerId);
        BukkitTask stilettoTask = stilettoTasks.remove(playerId);
        if (stilettoTask != null) {
            stilettoTask.cancel();
        }
        BukkitTask stilettoFlightTask = stilettoFlightTasks.remove(playerId);
        if (stilettoFlightTask != null) {
            stilettoFlightTask.cancel();
        }
        BukkitTask burstBuffTask = burstBuffTasks.remove(playerId);
        if (burstBuffTask != null) {
            burstBuffTask.cancel();
        }
    }

    public void clearAllRuntimeState() {
        for (UUID playerId : new ArrayList<>(stilettos.keySet())) {
            clearActiveStiletto(playerId);
        }
        infusionEndTimes.clear();
        burstBuffEndTimes.clear();
        electroBonusEndTimes.clear();
        for (BukkitTask task : stilettoTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        for (BukkitTask task : stilettoFlightTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        for (BukkitTask task : burstBuffTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        stilettoTasks.clear();
        stilettoFlightTasks.clear();
        burstBuffTasks.clear();
    }

    private boolean tryCastE(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        StilettoData active = getActiveStiletto(player);
        if (active != null) {
            logKeqingEDebug(player, "recast", active.location(), directTarget);
            consumeStiletto(player, true);
            return true;
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 7500L);
        if (!data.canUseE(CharacterType.KEQING, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.KEQING, cooldownMs);
            logKeqingEDebug(player, "cooldown:" + remain, null, directTarget);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[刻晴] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.KEQING);
        data.addEnergy(CharacterType.KEQING, config.getInt("e.energy-gain", 20));

        Location stilettoLocation = resolveStilettoLocation(player, directTarget);
        logKeqingEDebug(player, "cast", stilettoLocation, directTarget);
        double throwMultiplier = config.getDouble("e.throw-damage-multiplier", 1.68);
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 1)) {
                throwMultiplier *= 1.20;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 3)) {
                throwMultiplier *= 1.25;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 4)) {
                electroBonusEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + 8_000L);
                plugin.refreshPlayerStats(player);
            }
        }
        launchStiletto(player, stilettoLocation, throwMultiplier);

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&d[刻晴] 雷楔已投出，等待再次归位。",
                    messagePlaceholders());
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[刻晴] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.KEQING)),
                            "max_energy", String.valueOf(config.getInt("q.energy-cost", 40))
                    ));
        }
        return true;
    }

    private boolean tryCastQ(Player player, PlayerSkillData data) {
        if (!config.getBoolean("q.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("q.cooldown-ms", 12000L);
        int energyCost = config.getInt("q.energy-cost", 40);
        if (!data.canUseQ(CharacterType.KEQING, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.KEQING, cooldownMs);
            if (remain > 0L) {
                player.sendMessage(translateColors(config.getString(
                        "messages.q-cooldown",
                        "&c[刻晴] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatDurationSeconds(remain))));
            } else {
                player.sendMessage(translateColors(config.getString(
                                "messages.q-no-energy",
                                "&c[刻晴] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.KEQING)))
                        .replace("{max_energy}", String.valueOf(energyCost))));
            }
            return true;
        }

        data.useQ(CharacterType.KEQING, energyCost);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 4)) {
            electroBonusEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + 8_000L);
            plugin.refreshPlayerStats(player);
        }
        castStarwardSword(player);
        if (notificationEnabled("q-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("q-cast"),
                    "&d[刻晴] 天街巡游已展开，雷光连斩覆盖周围。",
                    messagePlaceholders());
        }
        return true;
    }

    private Location resolveStilettoLocation(Player player, LivingEntity directTarget) {
        double throwRange = Math.min(6.0, Math.max(1.0, config.getDouble("e.throw-range", 6.0)));
        if (directTarget != null && directTarget.isValid() && !directTarget.isDead()) {
            return directTarget.getLocation().clone().add(0, Math.min(1.1, directTarget.getHeight() * 0.55), 0);
        }

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().clone();
        if (direction.lengthSquared() <= 0.0001) {
            return eye.clone();
        }
        direction.normalize();

        RayTraceResult result = player.getWorld().rayTrace(
                eye,
                direction,
                throwRange,
                FluidCollisionMode.NEVER,
                true,
                0.35,
                entity -> entity instanceof LivingEntity living
                        && !living.equals(player)
                        && living.isValid()
                        && !living.isDead()
        );
        if (result != null) {
            if (result.getHitEntity() instanceof LivingEntity living) {
                return living.getLocation().clone().add(0, Math.min(1.1, living.getHeight() * 0.55), 0);
            }

            Vector hitPosition = result.getHitPosition();
            if (hitPosition != null) {
                return hitPosition.toLocation(player.getWorld()).subtract(direction.clone().multiply(0.2));
            }
        }

        return eye.clone().add(direction.multiply(throwRange));
    }

    private void launchStiletto(Player player, Location destination, double throwMultiplier) {
        Location start = player.getEyeLocation().clone();
        World world = start.getWorld();
        if (world == null || destination == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        BukkitTask previousFlight = stilettoFlightTasks.remove(playerId);
        if (previousFlight != null) {
            previousFlight.cancel();
        }

        Vector delta = destination.toVector().subtract(start.toVector());
        world.playSound(start, Sound.ENTITY_ENDER_PEARL_THROW, 0.65f, 1.8f);
        spawnImmediateStilettoTrail(world, start, destination);
        stilettoFlightTasks.remove(playerId);
        placeStiletto(player, destination);
        dealStilettoAreaDamage(player, destination, throwMultiplier, "e.throw", "e_throw");
    }

    private void placeStiletto(Player player, Location location) {
        UUID playerId = player.getUniqueId();
        clearActiveStiletto(playerId);

        World world = location.getWorld();
        if (world == null) {
            return;
        }

        Location markerLocation = location.clone().add(0, 0.65, 0);
        ArmorStand marker = world.spawn(markerLocation, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setBasePlate(false);
            stand.setSilent(true);
            stand.setCustomName("§d✦ 雷楔 ✦");
            stand.setCustomNameVisible(true);
            if (stand.getEquipment() != null) {
                stand.getEquipment().setHelmet(new ItemStack(Material.AMETHYST_CLUSTER));
            }
        });

        stilettos.put(playerId, new StilettoData(
                location.clone(),
                System.currentTimeMillis() + (config.getLong("e.stiletto-duration-ticks", 100L) * 50L),
                marker
        ));
        logKeqingEDebug(player, "placed", location, null);

        BukkitTask oldTask = stilettoTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            StilettoData data = getActiveStiletto(player);
            if (data == null) {
                BukkitTask running = stilettoTasks.remove(playerId);
                if (running != null) {
                    running.cancel();
                }
                return;
            }
            World markerWorld = data.location().getWorld();
            if (markerWorld == null) {
                return;
            }
            Location markerBase = data.location().clone().add(0, 0.45, 0);
            double bobOffset = 0.2 + (Math.sin(System.currentTimeMillis() / 180.0) * 0.18);
            if (data.marker() != null && data.marker().isValid()) {
                data.marker().teleport(markerBase.clone().add(0, bobOffset, 0));
            }
            markerWorld.spawnParticle(Particle.ELECTRIC_SPARK, markerBase, 14, 0.24, 0.3, 0.24, 0.03);
            markerWorld.spawnParticle(Particle.END_ROD, markerBase, 6, 0.14, 0.25, 0.14, 0.0);
            markerWorld.spawnParticle(Particle.ENCHANT, markerBase.clone().add(0, 0.35, 0), 8, 0.18, 0.28, 0.18, 0.01);
            markerWorld.spawnParticle(Particle.WITCH, markerBase, 4, 0.18, 0.2, 0.18, 0.01);
            markerWorld.spawnParticle(Particle.DUST, markerBase.clone().add(0, 0.2, 0), 8, 0.1, 0.2, 0.1, 1.0,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 120, 255), 1.25f));
        }, 0L, 4L);
        stilettoTasks.put(playerId, task);
        world.playSound(markerLocation, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.8f, 1.4f);
    }

    private boolean consumeStiletto(Player player, boolean teleport) {
        StilettoData data = getActiveStiletto(player);
        if (data == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        clearActiveStiletto(playerId);
        BukkitTask task = stilettoTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }

        Location location = data.location().clone();
        if (teleport) {
            Location destination = location.clone().add(0, 0.2, 0);
            destination.setYaw(player.getLocation().getYaw());
            destination.setPitch(player.getLocation().getPitch());
            player.teleport(destination);
            grantElectroInfusion(player);
            logKeqingEDebug(player, "teleport", location, null);
        }

        double recastMultiplier = teleport
                ? config.getDouble("e.recast-damage-multiplier", 1.68)
                : config.getDouble("charged.thunderclap-damage-multiplier", 1.46);
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 1) && teleport) {
                recastMultiplier *= 1.20;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 2) && !teleport) {
                recastMultiplier *= 1.25;
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 3)) {
                recastMultiplier *= 1.25;
            }
        }
        int hitCount = dealStilettoAreaDamage(player, location, recastMultiplier,
                teleport ? "e.recast" : "charged.thunderclap",
                teleport ? "e_recast" : "charged_thunderclap");
        if (plugin.getConstellationManager() != null) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 4)) {
                electroBonusEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + 8_000L);
                plugin.refreshPlayerStats(player);
            }
            if (teleport && plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 1)) {
                hitCount += dealStilettoAreaDamage(player, location, 0.45, "e.recast", "e_recast");
            }
        }
        if (hitCount > 0 && plugin.getEnergyManager() != null) {
            plugin.getEnergyManager().grantElementalParticles(player, ElementConstant.ELECTRO_KEY,
                    2, 3, 0.5, 600L, "keqing:stiletto");
        }
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, location.clone().add(0, 0.5, 0), 24, 0.45, 0.35, 0.45, 0.05);
            world.spawnParticle(Particle.ENCHANT, location.clone().add(0, 0.5, 0), 16, 0.35, 0.25, 0.35, 0.04);
            world.playSound(location, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.85f, teleport ? 1.35f : 1.15f);
        }
        if (teleport && notificationEnabled("e-recast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-recast"),
                    "&d[刻晴] 星斗归位，进入雷元素附魔状态。",
                    messagePlaceholders());
        }
        return true;
    }

    private int dealStilettoAreaDamage(Player player, Location center, double damageMultiplier,
                                       String attachmentSection, String reactionGroup) {
        double radius = config.getDouble("e.radius", 2.6);
        int auraDuration = config.getInt("e.aura-duration", 120);
        int hitCount = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !living.equals(player) && living.isValid() && !living.isDead()) {
                double damage = applySkillDamage(player, living, ElementConstant.ELECTRO_KEY, auraDuration, damageMultiplier, 0.0,
                        WeaponAttackType.SKILL, attachmentSection, reactionGroup);
                if (damage > 0.0) {
                    hitCount++;
                }
            }
        }
        return hitCount;
    }

    private void castStarwardSword(Player player) {
        double range = config.getDouble("q.range", config.getDouble("q.radius", 5.0) + 2.5);
        double pathRadius = config.getDouble("q.path-radius", 1.15);
        double fanAngle = config.getDouble("q.fan-angle-degrees", 46.0);
        int slashCount = Math.max(1, config.getInt("q.slash-count", 6));
        double slashMultiplierValue = config.getDouble("q.slash-damage-multiplier", 0.66);
        double finishMultiplierValue = config.getDouble("q.finish-damage-multiplier", 1.89);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.KEQING, 5)) {
            slashMultiplierValue *= 1.25;
            finishMultiplierValue *= 1.25;
        }
        final double slashMultiplier = slashMultiplierValue;
        final double finishMultiplier = finishMultiplierValue;
        double finishRange = config.getDouble("q.finish-range", range + 1.5);
        double finishPathRadius = config.getDouble("q.finish-path-radius", pathRadius + 0.2);
        int maxTargets = Math.max(1, config.getInt("q.max-targets", 4));
        int auraDuration = config.getInt("q.aura-duration", 140);

        World world = player.getWorld();
        Location center = player.getLocation().clone().add(0, 1.0, 0);
        Vector baseDirection = player.getLocation().getDirection().clone().setY(0);
        if (baseDirection.lengthSquared() <= 0.0001) {
            baseDirection = new Vector(0, 0, 1);
        }
        baseDirection.normalize();
        final Vector slashBaseDirection = baseDirection.clone();
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.4f);

        for (int i = 0; i < slashCount; i++) {
            final int slashIndex = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double offset = slashCount == 1
                        ? 0.0
                        : (-fanAngle * 0.5) + (fanAngle * slashIndex / (slashCount - 1.0));
                Vector slashDirection = rotateAroundY(slashBaseDirection, offset);
                Location end = center.clone().add(slashDirection.clone().multiply(range));
                spawnSlashTrail(world, center, end, slashIndex % 2 == 0);
                if (slashIndex % 2 == 0) {
                    world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.65f, 1.55f);
                }
                for (LivingEntity living : collectLineTargets(player, center, end, pathRadius, maxTargets)) {
                    applySkillDamage(player, living, ElementConstant.ELECTRO_KEY, auraDuration, slashMultiplier, 0.0,
                            WeaponAttackType.BURST, "q.slash", "q_slash");
                }
            }, i * 2L);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Location end = center.clone().add(slashBaseDirection.clone().multiply(finishRange));
            spawnSlashTrail(world, center, end, true);
            world.spawnParticle(Particle.ELECTRIC_SPARK, center, 18, 0.45, 0.4, 0.45, 0.05);
            world.spawnParticle(Particle.END_ROD, end, 10, 0.2, 0.25, 0.2, 0.02);
            world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.9f, 1.1f);
            for (LivingEntity living : collectLineTargets(player, center, end, finishPathRadius, maxTargets + 1)) {
                applySkillDamage(player, living, ElementConstant.ELECTRO_KEY, auraDuration, finishMultiplier, 0.0,
                        WeaponAttackType.BURST, "q.finish", "q_finish");
            }
        }, slashCount * 2L + 2L);

        long buffDurationMs = config.getLong("passive.q.buff-duration-ticks", 160L) * 50L;
        UUID playerId = player.getUniqueId();
        burstBuffEndTimes.put(playerId, System.currentTimeMillis() + buffDurationMs);
        plugin.refreshPlayerStats(player);

        BukkitTask oldTask = burstBuffTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            burstBuffEndTimes.remove(playerId);
            burstBuffTasks.remove(playerId);
            plugin.refreshPlayerStats(player);
        }, config.getLong("passive.q.buff-duration-ticks", 160L));
        burstBuffTasks.put(playerId, task);
    }

    private boolean isElectroInfusionActive(Player player) {
        if (player == null) {
            return false;
        }
        long end = infusionEndTimes.getOrDefault(player.getUniqueId(), 0L);
        if (end <= System.currentTimeMillis()) {
            infusionEndTimes.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void grantElectroInfusion(Player player) {
        if (player == null) {
            return;
        }
        infusionEndTimes.put(player.getUniqueId(), System.currentTimeMillis()
                + (config.getLong("e.infusion-duration-ticks", 100L) * 50L));
    }

    private void logKeqingEDebug(Player player, String stage, Location location, LivingEntity directTarget) {
        if (!plugin.getConfig().getBoolean("logging.charged-debug", false) || player == null) {
            return;
        }
        String locationText = location == null
                ? "null"
                : String.format(java.util.Locale.US, "%.2f,%.2f,%.2f",
                location.getX(), location.getY(), location.getZ());
        plugin.getLogger().info(String.format(
                "[Keqing-E][%s] player=%s directTarget=%s location=%s infusion=%s stiletto=%s",
                stage,
                player.getName(),
                directTarget == null ? "air" : directTarget.getType().name(),
                locationText,
                isElectroInfusionActive(player),
                getActiveStiletto(player) != null
        ));
    }

    private StilettoData getActiveStiletto(Player player) {
        if (player == null) {
            return null;
        }
        StilettoData data = stilettos.get(player.getUniqueId());
        if (data == null) {
            return null;
        }
        if (data.marker() != null && !data.marker().isValid()) {
            clearActiveStiletto(player.getUniqueId());
            return null;
        }
        if (data.expireAtMillis() <= System.currentTimeMillis()) {
            clearActiveStiletto(player.getUniqueId());
            BukkitTask task = stilettoTasks.remove(player.getUniqueId());
            if (task != null) {
                task.cancel();
            }
            return null;
        }
        return data;
    }

    private void clearActiveStiletto(UUID playerId) {
        if (playerId == null) {
            return;
        }
        StilettoData removed = stilettos.remove(playerId);
        if (removed != null && removed.marker() != null && removed.marker().isValid()) {
            removed.marker().remove();
        }
    }

    private void applyPhysicalChargedHit(Player attacker, LivingEntity target, double damageMultiplier) {
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
        preResistanceMainDamage *= plugin.getWeaponManager().getGlobalDamageMultiplier(attacker);
        if (damageResult.isCrit() && damageResult.getPlayerStats() != null) {
            preResistanceMainDamage *= (1.0 + damageResult.getPlayerStats().getCritDamage());
        }
        double finalMainDamage = plugin.applyMobResistance(target, preResistanceMainDamage, PlayerStats.PHYSICAL_KEY);

        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, attacker.getUniqueId().toString()));
        plugin.getReactionManager().markIncomingDamageElement(target, PlayerStats.PHYSICAL_KEY);
        target.setNoDamageTicks(0);
        target.damage(finalMainDamage, attacker);
        double appliedTransformativeDamage = plugin.getReactionManager()
                .applyTransformativeReactionDamage(attacker, target, reactionResult);
        plugin.getWeaponManager().onAttackHit(attacker, target, WeaponAttackType.CHARGED, PlayerStats.PHYSICAL_KEY);
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
        List<Map.Entry<LivingEntity, Double>> candidates = new ArrayList<>();
        Vector look = attacker.getLocation().getDirection().clone().setY(0);
        if (look.lengthSquared() <= 0.0001) {
            look = new Vector(0, 0, 1);
        }
        look.normalize();
        double requiredDot = Math.cos(Math.toRadians(coneDegrees / 2.0));
        Location origin = attacker.getLocation().clone().add(0, 1.0, 0);

        for (Entity entity : attacker.getWorld().getNearbyEntities(attacker.getLocation(), range, 2.5, range)) {
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
            candidates.add(Map.entry(living, distance));
        }

        if (preferredTarget != null && preferredTarget.isValid() && !preferredTarget.isDead()) {
            candidates.add(Map.entry(preferredTarget, attacker.getLocation().distance(preferredTarget.getLocation())));
        }

        candidates.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<LivingEntity> result = new ArrayList<>();
        for (Map.Entry<LivingEntity, Double> entry : candidates) {
            if (!result.contains(entry.getKey())) {
                result.add(entry.getKey());
            }
            if (result.size() >= maxTargets) {
                break;
            }
        }
        return result;
    }

    private List<LivingEntity> collectLineTargets(Player player, Location start, Location end,
                                                  double radius, int maxTargets) {
        List<Map.Entry<LivingEntity, Double>> candidates = new ArrayList<>();
        double reach = start.distance(end);
        BoundingBox hitBox = BoundingBox.of(start, end).expand(radius, 1.8, radius);

        for (Entity entity : player.getWorld().getNearbyEntities(hitBox)) {
            if (!(entity instanceof LivingEntity living) || living.equals(player) || !living.isValid() || living.isDead()) {
                continue;
            }
            double projection = projectionAlongSegment(start, end, living);
            if (projection < 0.0 || projection > reach) {
                continue;
            }
            if (distanceSquaredToSegment(start, end, living) > radius * radius) {
                continue;
            }
            candidates.add(Map.entry(living, projection));
        }

        candidates.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<LivingEntity> result = new ArrayList<>();
        for (Map.Entry<LivingEntity, Double> entry : candidates) {
            if (!result.contains(entry.getKey())) {
                result.add(entry.getKey());
            }
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

    private void spawnSlashTrail(World world, Location start, Location end, boolean finisher) {
        Vector delta = end.toVector().subtract(start.toVector());
        double length = Math.max(0.1, delta.length());
        Vector step = delta.normalize().multiply(0.8);
        Location point = start.clone();
        for (double traveled = 0.0; traveled < length; traveled += 0.8) {
            point.add(step);
            world.spawnParticle(Particle.ELECTRIC_SPARK, point, finisher ? 3 : 2, 0.06, 0.06, 0.06, 0.02);
            world.spawnParticle(Particle.ENCHANT, point, 1, 0.03, 0.03, 0.03, 0.0);
        }
    }

    private void spawnStilettoFlightParticles(World world, Location point, boolean finished) {
        world.spawnParticle(Particle.ELECTRIC_SPARK, point, finished ? 12 : 6, 0.08, 0.08, 0.08, 0.03);
        world.spawnParticle(Particle.END_ROD, point, finished ? 8 : 3, 0.05, 0.05, 0.05, 0.0);
        world.spawnParticle(Particle.ENCHANT, point, finished ? 12 : 4, 0.10, 0.10, 0.10, 0.02);
        world.spawnParticle(Particle.WITCH, point, finished ? 8 : 2, 0.12, 0.12, 0.12, 0.01);
        world.spawnParticle(Particle.DUST, point, finished ? 10 : 4, 0.08, 0.08, 0.08, 1.0,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 120, 255), finished ? 1.4f : 1.0f));
    }

    private void spawnImmediateStilettoTrail(World world, Location start, Location destination) {
        if (world == null || start == null || destination == null) {
            return;
        }

        Vector delta = destination.toVector().subtract(start.toVector());
        double length = Math.max(0.1, delta.length());
        Vector step = delta.normalize().multiply(0.75);
        Location point = start.clone();
        spawnStilettoFlightParticles(world, start, false);
        for (double traveled = 0.0; traveled < length; traveled += 0.75) {
            point.add(step);
            spawnStilettoFlightParticles(world, point, false);
        }
        spawnStilettoFlightParticles(world, destination, true);
    }

    private Vector rotateAroundY(Vector input, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vector(
                (input.getX() * cos) - (input.getZ() * sin),
                input.getY(),
                (input.getX() * sin) + (input.getZ() * cos)
        );
    }

    private record StilettoData(Location location, long expireAtMillis, ArmorStand marker) {
    }
}
