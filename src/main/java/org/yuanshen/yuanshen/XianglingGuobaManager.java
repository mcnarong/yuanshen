package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class XianglingGuobaManager {

    private static final String GUOBA_META_KEY = "ys_xiangling_guoba";

    private final Yuanshen plugin;
    private final Map<UUID, GuobaData> activeGuobas = new HashMap<>();

    public XianglingGuobaManager(Yuanshen plugin) {
        this.plugin = plugin;
        startTicker();
    }

    public void spawn(Player owner, LivingEntity preferredTarget, PlayerStats statSnapshot) {
        if (owner == null || !owner.isOnline()) {
            return;
        }

        clearPlayer(owner);

        Location spawnLocation = resolveSpawnLocation(owner, preferredTarget);
        Pig pig = owner.getWorld().spawn(spawnLocation, Pig.class, entity -> {
            entity.setCustomName("锅巴");
            entity.setCustomNameVisible(false);
            entity.setAI(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setCollidable(false);
            entity.setMetadata(GUOBA_META_KEY, new FixedMetadataValue(plugin, owner.getUniqueId().toString()));
        });

        CharacterSkillConfig config = plugin.getCharacterConfig(CharacterType.XIANGLING);
        long intervalMs = config.getLong("e.attack-interval-ticks", 35L) * 50L;
        int shots = Math.max(1, config.getInt("e.shots", 4));
        Vector initialDirection = resolveAttackDirection(owner, pig.getLocation(), preferredTarget);
        activeGuobas.put(owner.getUniqueId(), new GuobaData(
                owner.getUniqueId(),
                pig.getUniqueId(),
                System.currentTimeMillis() + Math.max(300L, intervalMs / 2L),
                shots,
                initialDirection,
                statSnapshot != null ? statSnapshot.copy() : null
        ));

        playSpawnEffects(pig.getLocation());
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        GuobaData removed = activeGuobas.remove(player.getUniqueId());
        removeGuoba(removed);
    }

    public boolean hasActiveGuoba(Player player) {
        if (player == null) {
            return false;
        }
        GuobaData data = activeGuobas.get(player.getUniqueId());
        if (data == null) {
            return false;
        }
        Pig pig = resolvePig(data.pigId);
        if (pig == null || pig.isDead()) {
            activeGuobas.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void clearAll() {
        for (GuobaData data : activeGuobas.values()) {
            removeGuoba(data);
        }
        activeGuobas.clear();
    }

    private void startTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickGuobas();
            }
        }.runTaskTimer(plugin, 10L, 2L);
    }

    private void tickGuobas() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, GuobaData>> iterator = activeGuobas.entrySet().iterator();
        while (iterator.hasNext()) {
            GuobaData data = iterator.next().getValue();
            Player owner = Bukkit.getPlayer(data.ownerId);
            Pig pig = resolvePig(data.pigId);
            if (owner == null || !owner.isOnline() || pig == null || pig.isDead()) {
                removeGuoba(data);
                iterator.remove();
                continue;
            }

            showIdleEffects(pig.getLocation(), data);
            if (now < data.nextAttackAtMillis) {
                continue;
            }

            executeShot(owner, pig, data);
            data.remainingShots--;
            if (data.remainingShots <= 0) {
                playEndEffects(pig.getLocation());
                plugin.getXianglingPepperManager().dropPepper(pig.getLocation(), owner);
                removeGuoba(data);
                iterator.remove();
            } else {
                data.nextAttackAtMillis = now + plugin.getCharacterConfig(CharacterType.XIANGLING)
                        .getLong("e.attack-interval-ticks", 35L) * 50L;
            }
        }
    }

    private void executeShot(Player owner, Pig pig, GuobaData data) {
        XianglingSkillHandler handler = plugin.getXianglingSkillHandler();
        if (handler == null) {
            return;
        }

        LivingEntity nearestTarget = findNearestTarget(owner, pig);
        Vector direction = resolveAttackDirection(owner, pig.getLocation(), nearestTarget);
        if (direction.lengthSquared() > 0.0001) {
            data.lastDirection = direction.clone().normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-data.lastDirection.getX(), data.lastDirection.getZ()));
            Location location = pig.getLocation();
            location.setYaw(yaw);
            pig.teleport(location);
        }

        Location start = pig.getLocation().clone().add(0, 0.85, 0);
        double flameRange = getEffectiveFlameRange();
        double hitRadius = plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("e.flame-radius", 1.25);
        double halfAngle = plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("e.flame-angle-degrees", 55.0) / 2.0;
        double requiredDot = Math.cos(Math.toRadians(halfAngle));

        Vector step = data.lastDirection.clone().multiply(0.35);
        Location point = start.clone();
        for (double traveled = 0.0; traveled <= flameRange; traveled += 0.35) {
            pig.getWorld().spawnParticle(Particle.FLAME, point, 3, 0.06, 0.06, 0.06, 0.01);
            pig.getWorld().spawnParticle(Particle.SMOKE, point, 1, 0.04, 0.04, 0.04, 0.0);
            point.add(step);
        }
        pig.getWorld().playSound(pig.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.35f);

        Collection<Entity> nearby = pig.getWorld().getNearbyEntities(pig.getLocation(), flameRange + 0.8, 2.2, flameRange + 0.8);
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner) || target.equals(pig)) {
                continue;
            }
            if (!target.isValid() || target.isDead()) {
                continue;
            }

            Vector toTarget = target.getLocation().clone().add(0, Math.min(1.0, target.getHeight() * 0.5), 0)
                    .toVector().subtract(start.toVector());
            double distance = toTarget.length();
            if (distance <= 0.0001 || distance > flameRange) {
                continue;
            }
            Vector horizontal = toTarget.clone().setY(0);
            if (horizontal.lengthSquared() > 0.0001) {
                double dot = data.lastDirection.clone().setY(0).normalize().dot(horizontal.normalize());
                if (dot < requiredDot) {
                    continue;
                }
            }

            double lineDistance = distanceToRay(start, data.lastDirection, target);
            if (lineDistance > hitRadius) {
                continue;
            }

            double finalDamage = handler.applyGuobaDamage(owner, target, data.statSnapshot);
            if (finalDamage > 0.0) {
                plugin.getElementAura(target).addElement(
                        ElementConstant.FIRE_KEY,
                        1.5,
                        handler.getConfig().getInt("e.aura-duration", 120)
                );
                handler.tryGrantGuobaParticles(owner);
            }
            if (finalDamage > 0.0 && handler.notificationEnabled("e-hit", false)) {
                handler.sendSkillMessage(owner,
                        handler.getConfig().messagePath("e-hit"),
                        "&6[锅巴] 命中 &f{target}&6，造成 &c{damage}&6 点火元素伤害。",
                        handler.messagePlaceholders(
                                "target", target.getName(),
                                "damage", ElementUtils.formatDamage(finalDamage)
                        ));
            }
        }
    }

    private LivingEntity findNearestTarget(Player owner, Pig pig) {
        double radius = getEffectiveFlameRange();
        Collection<Entity> nearby = pig.getWorld().getNearbyEntities(pig.getLocation(), radius, 2.5, radius);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity living) || living.equals(owner) || living.equals(pig)) {
                continue;
            }
            if (!living.isValid() || living.isDead()) {
                continue;
            }
            double distance = pig.getLocation().distanceSquared(living.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    private Vector resolveAttackDirection(Player owner, Location pigLocation, LivingEntity target) {
        if (target != null && target.isValid() && !target.isDead()) {
            Vector vector = target.getLocation().toVector().subtract(pigLocation.toVector()).setY(0);
            if (vector.lengthSquared() > 0.0001) {
                return vector.normalize();
            }
        }
        Vector forward = owner.getLocation().getDirection().clone().setY(0);
        if (forward.lengthSquared() > 0.0001) {
            return forward.normalize();
        }
        return new Vector(1, 0, 0);
    }

    private double getEffectiveFlameRange() {
        CharacterSkillConfig config = plugin.getCharacterConfig(CharacterType.XIANGLING);
        double baseRange = config.getDouble("e.flame-range", 4.5);
        boolean crossfireEnabled = config.getBoolean("passive.crossfire.enabled", true);
        if (!crossfireEnabled) {
            return baseRange;
        }
        return baseRange * (1.0 + config.getDouble("passive.crossfire.distance-bonus", 0.20));
    }

    private double distanceToRay(Location start, Vector direction, LivingEntity target) {
        Vector origin = start.toVector();
        Vector toTarget = target.getLocation().clone().add(0, Math.min(1.0, target.getHeight() * 0.5), 0)
                .toVector().subtract(origin);
        double projection = toTarget.dot(direction);
        if (projection < 0.0) {
            return Double.MAX_VALUE;
        }
        Vector closest = origin.clone().add(direction.clone().multiply(projection));
        return closest.distance(target.getLocation().clone().add(0, Math.min(1.0, target.getHeight() * 0.5), 0).toVector());
    }

    private Pig resolvePig(UUID pigId) {
        if (pigId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(pigId);
        return entity instanceof Pig pig && pig.isValid() ? pig : null;
    }

    private Location resolveSpawnLocation(Player owner, LivingEntity preferredTarget) {
        Location base = owner.getLocation().clone();
        Vector direction = resolveAttackDirection(owner, base, preferredTarget);
        direction.multiply(plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("e.spawn-distance", 1.6));
        base.add(direction);
        base.setYaw(owner.getLocation().getYaw());
        return base;
    }

    private void showIdleEffects(Location location, GuobaData data) {
        Particle idleParticle = resolveParticle(plugin.getCharacterConfig(CharacterType.XIANGLING)
                .getString("e.visual.idle-particle", "FLAME"));
        if (idleParticle != null) {
            location.getWorld().spawnParticle(idleParticle, location.clone().add(0, 0.9, 0), 2, 0.18, 0.08, 0.18, 0.0);
        }
        if ((data.tickCounter++ % 8) == 0) {
            location.getWorld().spawnParticle(Particle.SMOKE, location.clone().add(0, 1.0, 0), 1, 0.12, 0.03, 0.12, 0.0);
        }
    }

    private void playSpawnEffects(Location location) {
        location.getWorld().spawnParticle(Particle.CLOUD, location.clone().add(0, 0.4, 0), 10, 0.35, 0.12, 0.35, 0.01);
        location.getWorld().spawnParticle(Particle.FLAME, location.clone().add(0, 0.7, 0), 12, 0.28, 0.16, 0.28, 0.02);
        location.getWorld().playSound(location, Sound.ENTITY_PIG_AMBIENT, 0.7f, 1.4f);
    }

    private void playEndEffects(Location location) {
        location.getWorld().spawnParticle(Particle.SMOKE, location.clone().add(0, 0.8, 0), 10, 0.28, 0.18, 0.28, 0.02);
        location.getWorld().playSound(location, Sound.ENTITY_PIG_DEATH, 0.45f, 1.8f);
    }

    private void removeGuoba(GuobaData data) {
        if (data == null) {
            return;
        }
        Pig pig = resolvePig(data.pigId);
        if (pig != null && pig.isValid()) {
            pig.remove();
        }
    }

    private Particle resolveParticle(String particleName) {
        if (particleName == null || particleName.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static final class GuobaData {
        private final UUID ownerId;
        private final UUID pigId;
        private long nextAttackAtMillis;
        private int remainingShots;
        private Vector lastDirection;
        private final PlayerStats statSnapshot;
        private int tickCounter;

        private GuobaData(UUID ownerId, UUID pigId, long nextAttackAtMillis, int remainingShots,
                          Vector lastDirection, PlayerStats statSnapshot) {
            this.ownerId = ownerId;
            this.pigId = pigId;
            this.nextAttackAtMillis = nextAttackAtMillis;
            this.remainingShots = remainingShots;
            this.lastDirection = lastDirection.lengthSquared() > 0.0001 ? lastDirection.normalize() : new Vector(1, 0, 0);
            this.statSnapshot = statSnapshot;
        }
    }
}
