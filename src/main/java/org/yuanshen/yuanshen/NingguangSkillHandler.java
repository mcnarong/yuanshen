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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NingguangSkillHandler extends AbstractPolearmSkillHandler {

    private final Map<UUID, Integer> starJades = new ConcurrentHashMap<>();
    private final Map<UUID, Long> normalAttackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> geoBuffEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, JadeScreenData> jadeScreens = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> screenTouchState = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> screenResetReady = new ConcurrentHashMap<>();
    private final BukkitTask screenTicker;

    public NingguangSkillHandler(Yuanshen plugin) {
        super(plugin, CharacterType.NINGGUANG);
        this.screenTicker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickScreens, 0L, 5L);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.NINGGUANG;
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
    public boolean handlesNormalAttackInternally() {
        return true;
    }

    @Override
    public boolean isChargedAttack(Player attacker, ItemStack handItem) {
        if (attacker == null || !plugin.hasActiveCharacter(attacker)) {
            return false;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.NINGGUANG)) {
            return false;
        }
        return supportsChargedAttack() && (!config.getBoolean("charged.require-sneaking", true) || attacker.isSneaking());
    }

    @Override
    public boolean tryCastNormalAttack(Player attacker) {
        if (attacker == null || !plugin.hasActiveCharacter(attacker)) {
            return false;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.NINGGUANG)) {
            return false;
        }
        if (attacker.isSneaking()) {
            return false;
        }

        long cooldownMs = Math.max(0L, config.getLong("normal.cooldown-ms", 1000L));
        long remain = getRemainingNormalAttackCooldown(attacker);
        if (remain > 0L) {
            attacker.sendMessage(translateColors(config.getString(
                    "messages.normal-cooldown",
                    "&c[凝光] 普通攻击冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }
        if (cooldownMs > 0L) {
            normalAttackCooldowns.put(attacker.getUniqueId(), System.currentTimeMillis() + cooldownMs);
        }

        double range = config.getDouble("normal.range", 14.0);
        double hitboxExpand = config.getDouble("normal.hitbox-expand", 0.4);
        double damageMultiplier = plugin.getCharacterManager() == null
                ? config.getDouble("normal.damage-multiplier", 1.0)
                : plugin.getCharacterManager().getAndAdvanceNormalAttackMultiplier(
                        attacker,
                        CharacterType.NINGGUANG,
                        config.getDouble("normal.damage-multiplier", 1.0)
                );
        int auraDuration = config.getInt("normal.aura-duration", 90);

        Location origin = attacker.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone();
        RayTraceResult result = attacker.getWorld().rayTrace(
                origin,
                direction,
                range,
                FluidCollisionMode.NEVER,
                true,
                hitboxExpand,
                entity -> entity instanceof LivingEntity living
                        && !living.equals(attacker)
                        && living.isValid()
                        && !living.isDead()
        );

        LivingEntity target = null;
        Location end = origin.clone().add(direction.clone().multiply(range));
        if (result != null) {
            if (result.getHitEntity() instanceof LivingEntity living) {
                target = living;
                end = living.getLocation().clone().add(0, Math.min(1.0, living.getHeight() * 0.55), 0);
            } else if (result.getHitPosition() != null) {
                end = result.getHitPosition().toLocation(attacker.getWorld());
            }
        }

        plugin.suppressNormalAttack(attacker, Math.max(350L, cooldownMs));
        spawnGemTrail(origin, end);
        attacker.getWorld().spawnParticle(Particle.WAX_ON, origin, 6, 0.08, 0.08, 0.08, 0.01);

        if (target != null) {
            applySkillDamage(attacker, target, ElementConstant.GEO_KEY, auraDuration, damageMultiplier, 0.0,
                    WeaponAttackType.NORMAL, "combat.normal", "normal");
            if (plugin.getConstellationManager() != null
                    && plugin.getConstellationManager().hasConstellation(attacker, CharacterType.NINGGUANG, 1)) {
                for (Entity entity : attacker.getWorld().getNearbyEntities(target.getLocation(), 2.0, 1.6, 2.0)) {
                    if (!(entity instanceof LivingEntity splashTarget)
                            || splashTarget.equals(attacker)
                            || splashTarget.equals(target)
                            || !splashTarget.isValid()
                            || splashTarget.isDead()) {
                        continue;
                    }
                    applySkillDamage(attacker, splashTarget, ElementConstant.GEO_KEY, auraDuration, 0.35, 0.0,
                            WeaponAttackType.NORMAL, "combat.normal", "normal");
                }
            }
            target.getWorld().spawnParticle(Particle.END_ROD, end, 6, 0.12, 0.12, 0.12, 0.01);
            addStarJade(attacker);
        }
        return true;
    }

    @Override
    public String resolveAttackElement(Player attacker, ItemStack handItem, String defaultElement) {
        return ElementConstant.GEO_KEY;
    }

    @Override
    public double getElementDamageBonusMultiplier(Player attacker, String elementKey) {
        if (!ElementConstant.GEO_KEY.equals(elementKey) || attacker == null) {
            return 1.0;
        }
        long end = geoBuffEndTimes.getOrDefault(attacker.getUniqueId(), 0L);
        if (end <= System.currentTimeMillis()) {
            geoBuffEndTimes.remove(attacker.getUniqueId());
            return 1.0;
        }
        return 1.0 + config.getDouble("passive.jade-screen.geo-bonus", 0.12);
    }

    @Override
    public void onAttackResolved(Player attacker, LivingEntity target, boolean chargedAttack,
                                 boolean plungeAttack, String attackElement) {
        if (attacker == null || chargedAttack || plungeAttack) {
            return;
        }
        if (!plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.NINGGUANG)) {
            return;
        }
    }

    @Override
    public void beginChargedAttack(Player attacker, LivingEntity target) {
        LivingEntity resolvedTarget = target != null ? target : findChargedTarget(attacker);
        if (resolvedTarget == null) {
            return;
        }

        int jadeCount = starJades.getOrDefault(attacker.getUniqueId(), 0);
        double damageMultiplier = config.getDouble("charged.damage-multiplier", 1.74)
                + (jadeCount * config.getDouble("charged.per-star-jade-damage-multiplier", 0.5));
        int auraDuration = config.getInt("charged.aura-duration", 120);

        Location origin = attacker.getEyeLocation().clone();
        Location targetCenter = resolvedTarget.getLocation().clone().add(0, Math.min(1.0, resolvedTarget.getHeight() * 0.55), 0);
        spawnGemTrail(origin, targetCenter);
        attacker.getWorld().spawnParticle(Particle.END_ROD, targetCenter, 10 + (jadeCount * 4), 0.35, 0.3, 0.35, 0.04);
        attacker.getWorld().spawnParticle(Particle.CRIT, targetCenter, 6 + (jadeCount * 2), 0.25, 0.25, 0.25, 0.02);
        attacker.getWorld().playSound(targetCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.85f, 1.45f);

        if (jadeCount > 0) {
            launchStarJades(attacker, targetCenter, jadeCount);
        }

        applySkillDamage(attacker, resolvedTarget, ElementConstant.GEO_KEY, auraDuration, damageMultiplier, 0.0,
                WeaponAttackType.CHARGED, "charged", "charged");
        starJades.remove(attacker.getUniqueId());
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

    public void clearRuntimeState(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        starJades.remove(playerId);
        normalAttackCooldowns.remove(playerId);
        geoBuffEndTimes.remove(playerId);
        jadeScreens.remove(playerId);
        screenTouchState.remove(playerId);
        screenResetReady.remove(playerId);
    }

    public void clearAllRuntimeState() {
        starJades.clear();
        normalAttackCooldowns.clear();
        geoBuffEndTimes.clear();
        jadeScreens.clear();
        screenTouchState.clear();
        screenResetReady.clear();
        if (screenTicker != null) {
            screenTicker.cancel();
        }
    }

    private boolean tryCastE(Player player, PlayerSkillData data, LivingEntity directTarget) {
        if (!config.getBoolean("e.enabled", true)) {
            return false;
        }

        long cooldownMs = config.getLong("e.cooldown-ms", 12000L);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.NINGGUANG, 2)
                && hasActiveScreen(player)
                && screenResetReady.getOrDefault(player.getUniqueId(), false)) {
            cooldownMs = 0L;
            screenResetReady.put(player.getUniqueId(), false);
        }
        if (!data.canUseE(CharacterType.NINGGUANG, cooldownMs)) {
            long remain = data.getRemainingECooldown(CharacterType.NINGGUANG, cooldownMs);
            player.sendMessage(translateColors(config.getString(
                    "messages.e-cooldown",
                    "&c[凝光] E 冷却中，还剩 {time} 秒。"
            ).replace("{time}", formatDurationSeconds(remain))));
            return true;
        }

        data.useE(CharacterType.NINGGUANG);
        data.addEnergy(CharacterType.NINGGUANG, config.getInt("e.energy-gain", 12));

        Location screenLocation = resolveScreenLocation(player, directTarget);
        jadeScreens.put(player.getUniqueId(), new JadeScreenData(
                screenLocation.clone(),
                System.currentTimeMillis() + (config.getLong("e.duration-ticks", 180L) * 50L)
        ));
        screenTouchState.put(player.getUniqueId(), false);
        screenResetReady.put(player.getUniqueId(),
                plugin.getConstellationManager() != null
                        && plugin.getConstellationManager().hasConstellation(player, CharacterType.NINGGUANG, 2));

        dealScreenDamage(player, screenLocation);
        World world = screenLocation.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.END_ROD, screenLocation.clone().add(0, 1.0, 0), 24, 0.35, 0.7, 0.1, 0.03);
            world.spawnParticle(Particle.WAX_OFF, screenLocation.clone().add(0, 1.0, 0), 18, 0.45, 0.75, 0.12, 0.01);
            world.playSound(screenLocation, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.25f);
        }

        if (notificationEnabled("e-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("e-cast"),
                    "&6[凝光] 璇玑屏已经立起。",
                    messagePlaceholders());
        }
        if (notificationEnabled("energy-after-e", true)) {
            sendSkillMessage(player,
                    config.messagePath("energy"),
                    "&7[凝光] 当前能量：&f{energy}&7/&f{max_energy}",
                    messagePlaceholders(
                            "energy", String.valueOf(data.getEnergy(CharacterType.NINGGUANG)),
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
        if (!data.canUseQ(CharacterType.NINGGUANG, cooldownMs, energyCost)) {
            long remain = data.getRemainingQCooldown(CharacterType.NINGGUANG, cooldownMs);
            if (remain > 0L) {
                player.sendMessage(translateColors(config.getString(
                        "messages.q-cooldown",
                        "&c[凝光] Q 冷却中，还剩 {time} 秒。"
                ).replace("{time}", formatDurationSeconds(remain))));
            } else {
                player.sendMessage(translateColors(config.getString(
                                "messages.q-no-energy",
                                "&c[凝光] Q 所需能量不足，当前 {energy}/{max_energy}。"
                        ).replace("{energy}", String.valueOf(data.getEnergy(CharacterType.NINGGUANG)))
                        .replace("{max_energy}", String.valueOf(energyCost))));
            }
            return true;
        }

        data.useQ(CharacterType.NINGGUANG, energyCost);
        castStarshatter(player);
        if (notificationEnabled("q-cast", true)) {
            sendSkillMessage(player,
                    config.messagePath("q-cast"),
                    "&6[凝光] 天权崩玉发动，宝石齐射。",
                    messagePlaceholders());
        }
        return true;
    }

    private void castStarshatter(Player player) {
        int baseGems = Math.max(1, config.getInt("q.base-gem-count", 6));
        int extraGems = hasActiveScreen(player) ? Math.max(0, config.getInt("q.extra-gems-from-screen", 6)) : 0;
        int gemCount = baseGems + extraGems;
        double gemDamageMultiplierValue = config.getDouble("q.gem-damage-multiplier", 0.9);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.NINGGUANG, 5)) {
            gemDamageMultiplierValue *= 1.20;
        }
        final double gemDamageMultiplier = gemDamageMultiplierValue;
        int auraDuration = config.getInt("q.aura-duration", 120);

        List<LivingEntity> targets = findBurstTargets(player, Math.max(1, config.getInt("q.max-targets", 3)));
        if (targets.isEmpty()) {
            return;
        }

        World world = player.getWorld();
        Location center = player.getLocation().clone().add(0, 1.1, 0);
        world.spawnParticle(Particle.END_ROD, center, 20, 0.45, 0.45, 0.45, 0.03);
        world.spawnParticle(Particle.WAX_ON, center, 16, 0.32, 0.32, 0.32, 0.02);
        world.playSound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.85f, 1.3f);

        for (int i = 0; i < gemCount; i++) {
            final int gemIndex = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                LivingEntity target = targets.get(gemIndex % targets.size());
                if (!target.isValid() || target.isDead()) {
                    return;
                }
                Location targetLoc = target.getLocation().clone().add(0, 1.0, 0);
                spawnGemTrail(center, targetLoc);
                applySkillDamage(player, target, ElementConstant.GEO_KEY, auraDuration, gemDamageMultiplier, 0.0,
                        WeaponAttackType.BURST, "q", "q");
            }, i);
        }

        if (extraGems > 0) {
            jadeScreens.remove(player.getUniqueId());
            screenTouchState.remove(player.getUniqueId());
        }
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.NINGGUANG, 6)) {
            starJades.put(player.getUniqueId(), Math.max(3, config.getInt("normal.max-star-jades", 3)));
        }
    }

    private void tickScreens() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, JadeScreenData> entry : new ArrayList<>(jadeScreens.entrySet())) {
            UUID playerId = entry.getKey();
            JadeScreenData data = entry.getValue();
            if (data.expireAtMillis() <= now) {
                jadeScreens.remove(playerId);
                screenTouchState.remove(playerId);
                continue;
            }

            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }

            World world = data.location().getWorld();
            if (world == null) {
                continue;
            }

            Location visualCenter = data.location().clone().add(0, 1.0, 0);
            world.spawnParticle(Particle.END_ROD, visualCenter, 4, 0.22, 0.75, 0.06, 0.0);
            world.spawnParticle(Particle.WAX_OFF, visualCenter, 3, 0.28, 0.8, 0.08, 0.0);

            double touchRadius = config.getDouble("passive.jade-screen.touch-radius", 1.6);
            boolean nearScreen = player.getLocation().distanceSquared(data.location()) <= (touchRadius * touchRadius);
            boolean wasNear = screenTouchState.getOrDefault(playerId, false);
            if (nearScreen && !wasNear) {
                geoBuffEndTimes.put(playerId, now + (config.getLong("passive.jade-screen.buff-duration-ticks", 200L) * 50L));
                screenTouchState.put(playerId, true);
                if (notificationEnabled("screen-bonus", false)) {
                    sendSkillMessage(player,
                            config.messagePath("screen-bonus"),
                            "&6[凝光] 穿过璇玑屏，岩元素伤害提高。",
                            messagePlaceholders());
                }
            } else if (!nearScreen) {
                screenTouchState.put(playerId, false);
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            if (!plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.NINGGUANG)) {
                continue;
            }
            renderStarJades(player, now);
        }
    }

    private void dealScreenDamage(Player player, Location center) {
        double radius = config.getDouble("e.radius", 2.6);
        double damageMultiplier = config.getDouble("e.damage-multiplier", 2.3);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.NINGGUANG, 3)) {
            damageMultiplier *= 1.25;
        }
        int auraDuration = config.getInt("e.aura-duration", 120);
        int hitCount = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !living.equals(player) && living.isValid() && !living.isDead()) {
                double damage = applySkillDamage(player, living, ElementConstant.GEO_KEY, auraDuration, damageMultiplier, 0.0,
                        WeaponAttackType.SKILL, "e", "e");
                if (damage > 0.0) {
                    hitCount++;
                }
            }
        }
        if (hitCount > 0 && plugin.getEnergyManager() != null) {
            plugin.getEnergyManager().grantElementalParticles(player, ElementConstant.GEO_KEY,
                    3, 4, 0.33, 6000L, "ningguang:jade-screen");
        }
    }

    private LivingEntity findChargedTarget(Player player) {
        double range = config.getDouble("charged.range", 12.0);
        RayTraceResult result = player.getWorld().rayTrace(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                range,
                FluidCollisionMode.NEVER,
                true,
                0.35,
                entity -> entity instanceof LivingEntity living && !living.equals(player) && living.isValid() && !living.isDead()
        );
        if (result != null && result.getHitEntity() instanceof LivingEntity living) {
            return living;
        }

        List<LivingEntity> targets = findBurstTargets(player, 1);
        return targets.isEmpty() ? null : targets.get(0);
    }

    private Location resolveScreenLocation(Player player, LivingEntity directTarget) {
        if (directTarget != null && directTarget.isValid() && !directTarget.isDead()) {
            return directTarget.getLocation().clone();
        }
        double distance = config.getDouble("e.cast-distance", 3.2);
        Vector forward = player.getLocation().getDirection().clone().setY(0);
        if (forward.lengthSquared() <= 0.0001) {
            forward = new Vector(0, 0, 1);
        }
        return player.getLocation().clone().add(forward.normalize().multiply(distance));
    }

    private boolean hasActiveScreen(Player player) {
        JadeScreenData data = jadeScreens.get(player.getUniqueId());
        if (data == null) {
            return false;
        }
        if (data.expireAtMillis() <= System.currentTimeMillis()) {
            jadeScreens.remove(player.getUniqueId());
            screenTouchState.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean hasActiveJadeScreen(Player player) {
        return player != null && hasActiveScreen(player);
    }

    private List<LivingEntity> findBurstTargets(Player player, int maxTargets) {
        List<LivingEntity> candidates = new ArrayList<>();
        double searchRadius = config.getDouble("q.search-radius", 12.0);
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), searchRadius, searchRadius, searchRadius)) {
            if (entity instanceof LivingEntity living && !living.equals(player) && living.isValid() && !living.isDead()) {
                candidates.add(living);
            }
        }
        candidates.sort(Comparator.comparingDouble(living -> living.getLocation().distanceSquared(player.getLocation())));
        if (candidates.size() > maxTargets) {
            return new ArrayList<>(candidates.subList(0, maxTargets));
        }
        return candidates;
    }

    private void addStarJade(Player attacker) {
        int maxStarJades = Math.max(1, config.getInt("normal.max-star-jades", 3));
        starJades.put(attacker.getUniqueId(),
                Math.min(maxStarJades, starJades.getOrDefault(attacker.getUniqueId(), 0) + 1));
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

    private void renderStarJades(Player player, long now) {
        int jadeCount = starJades.getOrDefault(player.getUniqueId(), 0);
        if (jadeCount <= 0) {
            return;
        }
        World world = player.getWorld();
        double spin = (now % 1200L) / 1200.0 * (Math.PI * 2.0);
        int index = 0;
        for (Location jadeLocation : getStarJadePositions(player, jadeCount)) {
            double bob = Math.sin(spin + (index * 0.8)) * 0.06;
            Location point = jadeLocation.clone().add(0, bob, 0);
            world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.02, 0.02, 0.0);
            world.spawnParticle(Particle.WAX_ON, point, 2, 0.05, 0.05, 0.05, 0.0);
            index++;
        }
    }

    private void launchStarJades(Player attacker, Location targetCenter, int jadeCount) {
        List<Location> launchPositions = getStarJadePositions(attacker, jadeCount);
        for (int i = 0; i < launchPositions.size(); i++) {
            final Location from = launchPositions.get(i).clone();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!attacker.isOnline() || attacker.isDead()) {
                    return;
                }
                spawnGemTrail(from, targetCenter);
                attacker.getWorld().spawnParticle(Particle.WAX_OFF, targetCenter, 3, 0.12, 0.12, 0.12, 0.0);
            }, i * 2L);
        }
    }

    private List<Location> getStarJadePositions(Player player, int jadeCount) {
        int count = Math.max(0, Math.min(3, jadeCount));
        List<Location> positions = new ArrayList<>(count);
        if (count <= 0) {
            return positions;
        }

        Vector forward = player.getLocation().getDirection().clone().setY(0);
        if (forward.lengthSquared() <= 0.0001) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX()).normalize();
        Location base = player.getLocation().clone().add(0, 1.55, 0).add(forward.clone().multiply(-0.55));

        if (count == 1) {
            positions.add(base.clone().add(0, 0.02, 0));
            return positions;
        }
        if (count == 2) {
            positions.add(base.clone().add(right.clone().multiply(-0.22)).add(0, -0.02, 0));
            positions.add(base.clone().add(right.clone().multiply(0.22)).add(0, 0.06, 0));
            return positions;
        }

        positions.add(base.clone().add(right.clone().multiply(-0.34)).add(0, 0.00, 0));
        positions.add(base.clone().add(0, 0.14, 0));
        positions.add(base.clone().add(right.clone().multiply(0.34)).add(0, 0.00, 0));
        return positions;
    }

    private void spawnGemTrail(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) {
            return;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        double length = Math.max(0.5, delta.length());
        Vector step = delta.normalize().multiply(0.8);
        Location point = from.clone();
        for (double traveled = 0.0; traveled < length; traveled += 0.8) {
            point.add(step);
            world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.02, 0.02, 0.0);
            world.spawnParticle(Particle.WAX_ON, point, 1, 0.01, 0.01, 0.01, 0.0);
        }
        world.playSound(to, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.6f);
    }

    private record JadeScreenData(Location location, long expireAtMillis) {
    }
}
