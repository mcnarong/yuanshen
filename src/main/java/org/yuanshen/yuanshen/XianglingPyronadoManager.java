package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class XianglingPyronadoManager {

    private final Yuanshen plugin;
    private final Map<UUID, BurstData> activeBursts = new HashMap<>();

    public XianglingPyronadoManager(Yuanshen plugin) {
        this.plugin = plugin;
        startTicker();
    }

    public void activate(Player owner, PlayerStats statSnapshot) {
        if (owner == null || !owner.isOnline()) {
            return;
        }

        PlayerStats snapshot = statSnapshot != null ? statSnapshot.copy() : null;
        runOpeningSwings(owner, snapshot);

        long durationMs = plugin.getCharacterConfig(CharacterType.XIANGLING).getLong("q.duration-ticks", 200L) * 50L;
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(owner, CharacterType.XIANGLING, 4)) {
            durationMs = Math.round(durationMs * 1.40);
        }
        BurstData data = new BurstData(owner.getUniqueId(), System.currentTimeMillis() + durationMs, snapshot);
        activeBursts.put(owner.getUniqueId(), data);

        Location center = owner.getLocation().clone().add(0, 1.0, 0);
        owner.getWorld().spawnParticle(Particle.FLAME, center, 20, 0.45, 0.35, 0.45, 0.03);
        owner.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 6, 0.35, 0.15, 0.35, 0.0);
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.9f, 1.15f);
    }

    public void clearPlayer(Player player) {
        if (player != null) {
            activeBursts.remove(player.getUniqueId());
        }
    }

    public boolean isActive(Player player) {
        if (player == null) {
            return false;
        }
        BurstData data = activeBursts.get(player.getUniqueId());
        if (data == null) {
            return false;
        }
        if (data.endAtMillis <= System.currentTimeMillis()) {
            activeBursts.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void clearAll() {
        activeBursts.clear();
    }

    private void startTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickBursts();
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void tickBursts() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, BurstData>> iterator = activeBursts.entrySet().iterator();
        while (iterator.hasNext()) {
            BurstData data = iterator.next().getValue();
            Player owner = Bukkit.getPlayer(data.ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()) {
                iterator.remove();
                continue;
            }
            if (now >= data.endAtMillis) {
                iterator.remove();
                continue;
            }

            render(owner, data);
            hitTargets(owner, data, now);
        }
    }

    private void runOpeningSwings(Player owner, PlayerStats statSnapshot) {
        CharacterSkillConfig config = plugin.getCharacterConfig(CharacterType.XIANGLING);
        if (!config.getBoolean("q.opening.enabled", true)) {
            return;
        }

        int intervalTicks = Math.max(1, config.getInt("q.opening.swing-interval-ticks", 4));
        for (int swingIndex = 1; swingIndex <= 3; swingIndex++) {
            final int currentSwing = swingIndex;
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> executeOpeningSwing(owner, currentSwing, statSnapshot),
                    (long) intervalTicks * (swingIndex - 1));
        }
    }

    private void executeOpeningSwing(Player owner, int swingIndex, PlayerStats statSnapshot) {
        if (owner == null || !owner.isOnline() || owner.isDead()) {
            return;
        }

        XianglingSkillHandler handler = plugin.getXianglingSkillHandler();
        if (handler == null) {
            return;
        }

        double radius = plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("q.opening.radius", 4.0);
        Location center = owner.getLocation().clone().add(0, 1.0, 0);
        owner.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 5 + swingIndex, 0.45, 0.20, 0.45, 0.0);
        owner.getWorld().spawnParticle(Particle.FLAME, center, 10 + (swingIndex * 3), 0.55, 0.25, 0.55, 0.02);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.95f + (swingIndex * 0.1f));

        Collection<Entity> nearby = owner.getWorld().getNearbyEntities(owner.getLocation(), radius, 2.2, radius);
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner) || !target.isValid() || target.isDead()) {
                continue;
            }
            if (owner.getLocation().distanceSquared(target.getLocation()) > radius * radius) {
                continue;
            }
            handler.applyPyronadoOpeningDamage(owner, target, swingIndex, statSnapshot);
        }
    }

    private void render(Player owner, BurstData data) {
        double radius = plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("q.radius", 2.2);
        Location center = owner.getLocation().clone().add(0, 1.0, 0);
        data.angle += plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("q.rotation-step", 0.42);

        for (int i = 0; i < 2; i++) {
            double angle = data.angle + (Math.PI * i);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, 0.05, z);
            owner.getWorld().spawnParticle(Particle.FLAME, point, 3, 0.06, 0.06, 0.06, 0.01);
            owner.getWorld().spawnParticle(Particle.SMOKE, point, 1, 0.03, 0.03, 0.03, 0.0);
        }

        if ((data.soundTick++ % 8) == 0) {
            owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.35f, 1.45f);
        }
    }

    private void hitTargets(Player owner, BurstData data, long now) {
        XianglingSkillHandler handler = plugin.getXianglingSkillHandler();
        if (handler == null) {
            return;
        }

        double radius = plugin.getCharacterConfig(CharacterType.XIANGLING).getDouble("q.radius", 2.2);
        double hitCooldownMs = plugin.getCharacterConfig(CharacterType.XIANGLING).getLong("q.hit-cooldown-ms", 350L);
        Collection<Entity> nearby = owner.getWorld().getNearbyEntities(owner.getLocation(), radius + 0.8, 1.8, radius + 0.8);
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner) || !target.isValid() || target.isDead()) {
                continue;
            }
            if (owner.getLocation().distanceSquared(target.getLocation()) > (radius + 0.65) * (radius + 0.65)) {
                continue;
            }

            Long lastHit = data.lastHitTimes.get(target.getUniqueId());
            if (lastHit != null && (now - lastHit) < hitCooldownMs) {
                continue;
            }

            data.lastHitTimes.put(target.getUniqueId(), now);
            double finalDamage = handler.applyPyronadoDamage(owner, target, data.statSnapshot);
            if (finalDamage > 0.0 && handler.notificationEnabled("q-hit", false)) {
                handler.sendSkillMessage(owner,
                        handler.getConfig().messagePath("q-hit"),
                        "&c[旋火轮] 命中 &f{target}&c，造成 &6{damage}&c 点火元素伤害。",
                        handler.messagePlaceholders(
                                "target", target.getName(),
                                "damage", ElementUtils.formatDamage(finalDamage)
                        ));
            }
        }
    }

    private static final class BurstData {
        private final UUID ownerId;
        private final long endAtMillis;
        private final PlayerStats statSnapshot;
        private final Map<UUID, Long> lastHitTimes = new HashMap<>();
        private double angle;
        private int soundTick;

        private BurstData(UUID ownerId, long endAtMillis, PlayerStats statSnapshot) {
            this.ownerId = ownerId;
            this.endAtMillis = endAtMillis;
            this.statSnapshot = statSnapshot;
        }
    }
}
