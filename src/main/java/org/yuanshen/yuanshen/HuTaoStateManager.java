package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class HuTaoStateManager implements CharacterStateHandler {

    private static final String BONUS_DAMAGE_KEY = "bonus_damage";
    private static final String OTHER_CHARACTER_CRIT_RATE_KEY = "other_character_crit_rate";

    private final Yuanshen plugin;
    private final CharacterStateContext stateContext = new CharacterStateContext(CharacterType.HUTAO);
    private final CharacterStateContext supportBuffContext = new CharacterStateContext(CharacterType.HUTAO);

    public HuTaoStateManager(Yuanshen plugin) {
        this.plugin = plugin;
        startStateTicker();
    }

    public void activate(Player player) {
        double currentHealth = player.getHealth();
        double maxHealth = getMaxHealth(player);

        double hpCostPercent = plugin.getSkillsConfig().getDouble("skills.hutao.e.hp-cost-current-percent", 0.30);
        int durationTicks = plugin.getSkillsConfig().getInt("skills.hutao.e.duration-ticks", 180);
        double hpToBonusDamageRatio = plugin.getSkillsConfig().getDouble("skills.hutao.e.hp-to-bonus-damage-ratio", 0.0384);
        double maxBonusCapRatio = plugin.getSkillsConfig().getDouble("skills.hutao.e.max-bonus-cap-ratio", 4.0);

        double hpCost = currentHealth * hpCostPercent;
        double newHealth = Math.max(1.0, currentHealth - hpCost);
        plugin.setPlayerHealthSafely(player, newHealth, true);

        double baseFireDamage = plugin.getConfig().getDouble("elements.fire_base_damage", 1.0);
        double bonusDamage = maxHealth * hpToBonusDamageRatio;
        double maxBonus = baseFireDamage * maxBonusCapRatio;
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.HUTAO, 3)) {
            bonusDamage *= 1.25;
        }
        bonusDamage = Math.min(bonusDamage, maxBonus);

        stateContext.setNumber(player, BONUS_DAMAGE_KEY, bonusDamage);
        stateContext.setStateEnd(player, System.currentTimeMillis() + (durationTicks * 50L));

        applyActivationKnockback(player);
        plugin.refreshPlayerStats(player);
        sendConfiguredMessage(player,
                "skills.hutao.messages.e-activated",
                "&6[胡桃] 已进入彼岸蝶舞状态，当前生命值 &f{health}&6/&f{max_health}&6，额外伤害 &c{bonus_damage}&6。",
                true,
                "e-activated",
                placeholders(
                        "health", ElementUtils.formatDamage(player.getHealth()),
                        "max_health", ElementUtils.formatDamage(maxHealth),
                        "bonus_damage", ElementUtils.formatDamage(bonusDamage)
                ));
    }

    public void deactivate(Player player) {
        endState(player, true);
    }

    public void handleCharacterSwitch(Player player, CharacterType previous, CharacterType current) {
        if (player == null) {
            return;
        }
        if (previous == CharacterType.HUTAO && current != CharacterType.HUTAO && isActive(player)) {
            endState(player, true);
            return;
        }
        if (previous != current || supportBuffContext.hasState(player.getUniqueId())) {
            plugin.refreshPlayerStats(player);
        }
    }

    public void applyInterruptionResistance(Player player, EntityDamageEvent event) {
        if (player == null || event == null || !isActive(player)
                || !plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.HUTAO)) {
            return;
        }
        if (!plugin.getSkillsConfig().getBoolean("skills.hutao.e.interruption-resistance.enabled", true)) {
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean attackLikeDamage = event instanceof EntityDamageByEntityEvent
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (!attackLikeDamage) {
            return;
        }

        Vector previousVelocity = player.getVelocity().clone();
        double horizontalFactor = plugin.getSkillsConfig().getDouble("skills.hutao.e.interruption-resistance.horizontal-factor", 0.25);
        double verticalFactor = plugin.getSkillsConfig().getDouble("skills.hutao.e.interruption-resistance.vertical-factor", 0.55);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }

            Vector currentVelocity = player.getVelocity().clone();
            Vector adjustedVelocity = new Vector(
                    previousVelocity.getX() + ((currentVelocity.getX() - previousVelocity.getX()) * horizontalFactor),
                    previousVelocity.getY() + ((currentVelocity.getY() - previousVelocity.getY()) * verticalFactor),
                    previousVelocity.getZ() + ((currentVelocity.getZ() - previousVelocity.getZ()) * horizontalFactor)
            );
            player.setVelocity(adjustedVelocity);
        });
    }

    public boolean isActive(Player player) {
        if (!stateContext.hasState(player.getUniqueId())) {
            return false;
        }
        if (!stateContext.hasActiveState(player.getUniqueId())) {
            endState(player, true);
            return false;
        }
        return true;
    }

    public double getBonusDamage(Player player) {
        if (!stateContext.hasActiveState(player.getUniqueId())) {
            return 0.0;
        }
        return stateContext.getNumber(player, BONUS_DAMAGE_KEY, 0.0);
    }

    public long getStateEndMillis(Player player) {
        if (player == null) {
            return 0L;
        }
        return stateContext.getStateEnd(player.getUniqueId());
    }

    public void restore(Player player, double bonusDamage, long endTimeMillis) {
        if (player == null || endTimeMillis <= System.currentTimeMillis()) {
            stateContext.clear(player);
            return;
        }

        stateContext.setNumber(player, BONUS_DAMAGE_KEY, Math.max(0.0, bonusDamage));
        stateContext.setStateEnd(player, endTimeMillis);
        plugin.refreshPlayerStats(player);
    }

    public long getSupportBuffEndMillis(Player player) {
        if (player == null) {
            return 0L;
        }
        return supportBuffContext.getStateEnd(player.getUniqueId());
    }

    public void restoreSupportBuff(Player player, long endTimeMillis) {
        if (player == null || endTimeMillis <= System.currentTimeMillis()) {
            supportBuffContext.clear(player);
            return;
        }
        double critRateBonus = plugin.getSkillsConfig().getDouble("skills.hutao.passive.team-crit-rate-bonus", 0.12);
        supportBuffContext.setNumber(player, OTHER_CHARACTER_CRIT_RATE_KEY, critRateBonus);
        supportBuffContext.setStateEnd(player, endTimeMillis);
        plugin.refreshPlayerStats(player);
    }

    public boolean isLowHp(Player player) {
        double maxHealth = getMaxHealth(player);
        if (maxHealth <= 0) {
            return false;
        }
        double threshold = plugin.getSkillsConfig().getDouble("skills.hutao.e.low-hp-threshold", 0.50);
        return (player.getHealth() / maxHealth) <= threshold;
    }

    public double getLowHpPyroBonus(Player player) {
        if (!isLowHp(player)) {
            return 0.0;
        }
        return plugin.getSkillsConfig().getDouble("skills.hutao.e.low-hp-pyro-bonus", 0.33);
    }

    public double getOtherCharacterCritRateBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        if (!supportBuffContext.hasActiveState(player.getUniqueId())) {
            if (supportBuffContext.hasState(player.getUniqueId())) {
                supportBuffContext.clear(player);
                plugin.refreshPlayerStats(player);
            }
            return 0.0;
        }
        if (plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.HUTAO)) {
            return 0.0;
        }
        return supportBuffContext.getNumber(player, OTHER_CHARACTER_CRIT_RATE_KEY, 0.0);
    }

    public double getHuTaoDamageBonusMultiplier(Player player, String elementKey) {
        if (!plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.HUTAO)) {
            return 1.0;
        }

        double multiplier = 1.0;
        if (isActive(player)) {
            double bonusDamage = getBonusDamage(player);
            double baseFireDamage = plugin.getConfig().getDouble("elements.fire_base_damage", 1.0);
            if (baseFireDamage <= 0) {
                baseFireDamage = 1.0;
            }
            multiplier *= (1.0 + (bonusDamage / baseFireDamage));
        }

        if (ElementConstant.FIRE_KEY.equals(elementKey)) {
            double lowHpPyroBonus = getLowHpPyroBonus(player);
            if (lowHpPyroBonus > 0) {
                multiplier *= (1.0 + lowHpPyroBonus);
            }
        }

        return multiplier;
    }

    private void startStateTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    boolean selectedHuTao = plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.HUTAO);
                    if (stateContext.hasActiveState(player.getUniqueId()) && !selectedHuTao) {
                        endState(player, true);
                        continue;
                    }
                    if (stateContext.hasState(player.getUniqueId()) && !stateContext.hasActiveState(player.getUniqueId())) {
                        endState(player, true);
                    }

                    if (supportBuffContext.hasState(player.getUniqueId()) && !supportBuffContext.hasActiveState(player.getUniqueId())) {
                        supportBuffContext.clear(player);
                        plugin.refreshPlayerStats(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 10L);
    }

    private void endState(Player player, boolean grantSupportBuff) {
        if (player == null || !stateContext.hasState(player.getUniqueId())) {
            return;
        }

        stateContext.clear(player);
        if (grantSupportBuff) {
            applySupportBuff(player);
        }
        plugin.refreshPlayerStats(player);
        sendConfiguredMessage(player,
                "skills.hutao.messages.e-ended",
                "&7[胡桃] 彼岸蝶舞状态已结束。",
                true,
                "e-ended",
                placeholders());
    }

    private void applySupportBuff(Player player) {
        double critRateBonus = plugin.getSkillsConfig().getDouble("skills.hutao.passive.team-crit-rate-bonus", 0.12);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.HUTAO, 4)) {
            critRateBonus += 0.08;
        }
        int durationTicks = plugin.getSkillsConfig().getInt("skills.hutao.passive.team-crit-duration-ticks", 160);
        if (critRateBonus <= 0.0 || durationTicks <= 0) {
            return;
        }

        supportBuffContext.setNumber(player, OTHER_CHARACTER_CRIT_RATE_KEY, critRateBonus);
        supportBuffContext.setStateEnd(player, System.currentTimeMillis() + (durationTicks * 50L));

        sendConfiguredMessage(player,
                "skills.hutao.messages.passive-team-crit",
                "&d[胡桃] 蝶隐之时触发，其他角色暴击率提升 &f{crit_rate}&d，持续 &f{duration}&d 秒。",
                true,
                "passive-team-crit",
                placeholders(
                        "crit_rate", String.format("%.0f%%", critRateBonus * 100.0),
                        "duration", String.format("%.1f", durationTicks / 20.0)
                ));
    }

    private void applyActivationKnockback(Player player) {
        FileConfiguration skills = plugin.getSkillsConfig();
        if (!skills.getBoolean("skills.hutao.e.knockback.enabled", true)) {
            return;
        }

        double radius = skills.getDouble("skills.hutao.e.knockback.radius", 3.5);
        double strength = skills.getDouble("skills.hutao.e.knockback.strength", 0.8);
        double vertical = skills.getDouble("skills.hutao.e.knockback.vertical", 0.25);

        Collection<Entity> nearby = player.getNearbyEntities(radius, radius, radius);
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity living) || living.equals(player)) {
                continue;
            }

            if (living.getLocation().distance(player.getLocation()) > radius) {
                continue;
            }

            Vector vector = living.getLocation().toVector().subtract(player.getLocation().toVector());
            if (vector.lengthSquared() <= 0.0001) {
                vector = player.getLocation().getDirection().clone();
            }

            vector.normalize().multiply(strength).setY(vertical);
            living.setVelocity(vector);
        }

        if (!skills.getBoolean("skills.hutao.e.visual.activate.enabled", true)) {
            return;
        }

        World world = player.getWorld();
        Location center = player.getLocation().clone().add(0, 1.0, 0);
        Particle ringParticle = getParticle(skills.getString("skills.hutao.e.visual.activate.ring-particle", "SOUL_FIRE_FLAME"));
        Particle burstParticle = getParticle(skills.getString("skills.hutao.e.visual.activate.burst-particle", "FLAME"));
        Particle accentParticle = getParticle(skills.getString("skills.hutao.e.visual.activate.accent-particle", "CRIMSON_SPORE"));
        Particle lowHpParticle = getParticle(skills.getString("skills.hutao.e.visual.activate.low-hp-particle", "LAVA"));

        int ringCount = skills.getInt("skills.hutao.e.visual.activate.ring-count", 18);
        int burstCount = skills.getInt("skills.hutao.e.visual.activate.burst-count", 22);
        int accentCount = skills.getInt("skills.hutao.e.visual.activate.accent-count", 10);
        int lowHpCount = skills.getInt("skills.hutao.e.visual.activate.low-hp-count", 6);
        double ringRadius = skills.getDouble("skills.hutao.e.visual.activate.ring-radius", 1.25);

        for (int i = 0; i < ringCount; i++) {
            double angle = (Math.PI * 2 * i) / Math.max(1, ringCount);
            double x = Math.cos(angle) * ringRadius;
            double z = Math.sin(angle) * ringRadius;
            if (ringParticle != null) {
                world.spawnParticle(ringParticle, center.clone().add(x, 0.08, z), 1, 0.02, 0.02, 0.02, 0.0);
            }
        }

        if (burstParticle != null) {
            world.spawnParticle(burstParticle, center, burstCount, 0.45, 0.30, 0.45, 0.03);
        }
        if (accentParticle != null) {
            world.spawnParticle(accentParticle, center, accentCount, 0.35, 0.20, 0.35, 0.0);
        }
        if (isLowHp(player) && lowHpParticle != null) {
            world.spawnParticle(lowHpParticle, center, lowHpCount, 0.30, 0.18, 0.30, 0.0);
        }

        Sound sound = getSound(skills.getString("skills.hutao.e.visual.activate.sound", "ENTITY_BLAZE_SHOOT"));
        if (sound != null) {
            world.playSound(player.getLocation(), sound,
                    (float) skills.getDouble("skills.hutao.e.visual.activate.sound-volume", 1.0),
                    (float) skills.getDouble("skills.hutao.e.visual.activate.sound-pitch", 0.8));
        }
    }

    public double getMaxHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return 20.0;
        }
        return attr.getValue();
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.HUTAO;
    }

    @Override
    public void clear(Player player) {
        stateContext.clear(player);
        supportBuffContext.clear(player);
    }

    @Override
    public void clearAll() {
        stateContext.clearAll();
        supportBuffContext.clearAll();
    }

    private void sendConfiguredMessage(Player player, String path, String fallback,
                                       boolean enabledDefault, String notificationKey,
                                       Map<String, String> placeholders) {
        if (!plugin.getSkillsConfig().getBoolean("skills.hutao.notifications." + notificationKey, enabledDefault)) {
            return;
        }

        String text = plugin.getSkillsConfig().getString(path, fallback);
        if (text == null || text.isBlank()) {
            return;
        }

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    private Map<String, String> placeholders(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private Particle getParticle(String particleName) {
        if (particleName == null || particleName.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Sound getSound(String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
