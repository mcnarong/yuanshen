package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class HuTaoBloodBlossomManager {

    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";
    private final Yuanshen plugin;
    private final Map<UUID, BloodBlossomData> activeBlossoms = new HashMap<>();

    public HuTaoBloodBlossomManager(Yuanshen plugin) {
        this.plugin = plugin;
        startTicker();
    }

    public void applyOrRefresh(Player owner, LivingEntity target) {
        if (owner == null || target == null || !owner.isOnline() || target.isDead() || !target.isValid()) {
            return;
        }

        CharacterSkillConfig config = hutaoConfig();
        if (!config.getBoolean("blood-blossom.enabled", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        long durationMs = config.getLong("blood-blossom.duration-ticks", 160) * 50L;
        long intervalMs = config.getLong("blood-blossom.interval-ticks", 80) * 50L;

        BloodBlossomData old = activeBlossoms.get(target.getUniqueId());
        BloodBlossomData data = new BloodBlossomData(
                owner.getUniqueId(),
                target.getUniqueId(),
                now + durationMs,
                now + intervalMs
        );
        if (old != null) {
            data.visualAngle = old.visualAngle;
        }
        activeBlossoms.put(target.getUniqueId(), data);

        boolean refreshed = old != null && old.ownerId.equals(owner.getUniqueId());
        String path = refreshed
                ? hutaoConfig().messagePath("blood-blossom-refresh")
                : hutaoConfig().messagePath("blood-blossom-apply");

        String notificationKey = refreshed ? "blood-blossom-refresh" : "blood-blossom-apply";
        sendConfiguredMessage(owner, path,
                "&c【胡桃】对 {target} 施加了血梅香！",
                notificationKey,
                refreshed ? false : true,
                target.getName(),
                null);
        playApplyEffects(target, refreshed);
    }

    public void clearAll() {
        activeBlossoms.clear();
    }

    private void startTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickBloodBlossoms();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    private void tickBloodBlossoms() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, BloodBlossomData>> iterator = activeBlossoms.entrySet().iterator();

        while (iterator.hasNext()) {
            BloodBlossomData data = iterator.next().getValue();

            Player owner = Bukkit.getPlayer(data.ownerId);
            Entity entity = Bukkit.getEntity(data.targetId);
            if (!(entity instanceof LivingEntity target) || owner == null || !owner.isOnline()) {
                iterator.remove();
                continue;
            }

            if (!target.isValid() || target.isDead()) {
                iterator.remove();
                continue;
            }

            if (now > data.endTimeMs) {
                iterator.remove();
                continue;
            }

            showActiveIndicator(target, data, now);

            if (now >= data.nextTriggerTimeMs) {
                triggerDamage(owner, target);
                data.nextTriggerTimeMs += hutaoConfig().getLong("blood-blossom.interval-ticks", 80) * 50L;
            }
        }
    }

    private void triggerDamage(Player owner, LivingEntity target) {
        CharacterSkillConfig config = hutaoConfig();
        String element = config.getString("blood-blossom.element", ElementConstant.FIRE_KEY);
        int auraDuration = config.getInt("blood-blossom.aura-duration", 120);
        double damageMultiplier = config.getDouble("blood-blossom.damage-multiplier", 0.64);
        double extraFlatDamage = config.getDouble("blood-blossom.extra-flat-damage", 0.0);
        double extraMaxHealthRatio = config.getDouble("blood-blossom.extra-max-health-ratio", 0.0);
        double extraDamageMultiplier = config.getDouble("blood-blossom.extra-damage-multiplier", 1.0);
        if (plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(owner, CharacterType.HUTAO, 2)) {
            damageMultiplier *= 1.30;
            extraMaxHealthRatio += 0.04;
        }
        String reactionSourceKey = "HuTaoBloodBlossom:" + config.getAttachmentGroup("blood-blossom", "blood_blossom");
        double triggerAuraAmount = config.getAttachmentAuraAmount(
                "blood-blossom",
                plugin.parseConfigDouble("attachment.aura.skill", owner, 1.0)
        );
        ElementReactionManager.AttachmentProfile attachmentProfile = new ElementReactionManager.AttachmentProfile(
                reactionSourceKey,
                triggerAuraAmount,
                config.getAttachmentIcdEnabled("blood-blossom", plugin.getConfig().getBoolean("attachment.standard-icd.enabled", true)),
                config.getAttachmentIcdWindowMs("blood-blossom", plugin.parseConfigInt("attachment.standard-icd.window-ms", owner, 2500)),
                config.getAttachmentIcdHits("blood-blossom", plugin.parseConfigInt("attachment.standard-icd.hits", owner, 3)),
                config.getAttachmentIcdOffset("blood-blossom", plugin.parseConfigInt("attachment.standard-icd.offset", owner, 0))
        );

        ItemStack weapon = owner.getInventory().getItemInMainHand();
        DamageResult damageResult = plugin.getDamageCalculator().calculateElementDamage(owner, target, element, weapon);
        if (damageResult == null) {
            return;
        }

        ReactionResult reactionResult = plugin.getReactionManager().handleReaction(
                owner,
                target,
                element,
                damageResult,
                attachmentProfile
        );
        if (reactionResult == null) {
            return;
        }

        double baseDamage = (damageResult.getRawTotalDamage() + reactionResult.getScalingDamageBonus()) * damageMultiplier;
        double extraDamage = extraFlatDamage + plugin.getHuTaoStateManager().getMaxHealth(owner) * extraMaxHealthRatio;
        double preResistanceMainDamage = (baseDamage + extraDamage) * extraDamageMultiplier;
        preResistanceMainDamage += reactionResult.getAdditiveDamageBonus();
        preResistanceMainDamage *= plugin.getHuTaoStateManager().getHuTaoDamageBonusMultiplier(owner, element);
        preResistanceMainDamage *= plugin.getGlobalDamageBonusMultiplier(owner, element);

        if (damageResult.isCrit() && damageResult.getPlayerStats() != null) {
            preResistanceMainDamage *= (1.0 + damageResult.getPlayerStats().getCritDamage());
        }

        double finalMainDamage = plugin.applyMobResistance(target, preResistanceMainDamage, element);
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, owner.getUniqueId().toString()));
        plugin.getReactionManager().markIncomingDamageElement(target, element);
        target.setNoDamageTicks(0);
        target.damage(finalMainDamage, owner);
        double appliedTransformativeDamage = plugin.getReactionManager()
                .applyTransformativeReactionDamage(owner, target, reactionResult);

        plugin.getReactionManager().consumeReactionAuras(target, reactionResult, element);
        plugin.getReactionManager().applyTriggerAura(target, element, reactionResult, auraDuration, triggerAuraAmount);
        plugin.getReactionManager().postProcessReactionState(target, reactionResult);
        if (plugin.getReactionManager().shouldTriggerSeedReaction(target, element, reactionResult)) {
            plugin.getReactionManager().triggerSeedReaction(owner, target.getLocation(), element);
        }
        playTickEffects(target);

        double totalDamage = finalMainDamage + appliedTransformativeDamage;

        if (plugin.isDamageDisplayEnabled(owner)) {
            sendConfiguredMessage(owner,
                hutaoConfig().messagePath("blood-blossom-tick"),
                "&6【血梅香】&f{target} &e受到 &c{damage} &e点火元素伤害！",
                "blood-blossom-tick",
                false,
                target.getName(),
                ElementUtils.formatDamage(totalDamage));
        }

        double displayedReactionDamage = plugin.getReactionManager().calculateDisplayedReactionDamage(
                reactionResult,
                preResistanceMainDamage,
                finalMainDamage,
                appliedTransformativeDamage
        );
        plugin.getElementUtils().sendDamageResult(owner, reactionResult, totalDamage, displayedReactionDamage);
    }

    private void playApplyEffects(LivingEntity target, boolean refreshed) {
        if (!hutaoConfig().getBoolean("blood-blossom.visual.apply.enabled", true)) {
            return;
        }

        Location center = target.getLocation().clone().add(0, 1.0, 0);
        Particle mainParticle = getParticle(hutaoConfig().getString("blood-blossom.visual.apply.particle", "SOUL_FIRE_FLAME"));
        Particle secondaryParticle = getParticle(hutaoConfig().getString("blood-blossom.visual.apply.secondary-particle", "CRIMSON_SPORE"));

        int mainCount = hutaoConfig().getInt("blood-blossom.visual.apply.count", refreshed ? 16 : 12);
        int secondaryCount = hutaoConfig().getInt("blood-blossom.visual.apply.secondary-count", refreshed ? 12 : 8);

        if (mainParticle != null) {
            target.getWorld().spawnParticle(mainParticle, center, mainCount, 0.30, 0.35, 0.30, 0.02);
        }
        if (secondaryParticle != null) {
            target.getWorld().spawnParticle(secondaryParticle, center, secondaryCount, 0.35, 0.40, 0.35, 0.0);
        }

        Sound sound = getSound(hutaoConfig().getString("blood-blossom.visual.apply.sound", "BLOCK_FIRE_EXTINGUISH"));
        if (sound != null) {
            target.getWorld().playSound(center, sound,
                    (float) hutaoConfig().getDouble("blood-blossom.visual.apply.sound-volume", 0.65),
                    (float) hutaoConfig().getDouble("blood-blossom.visual.apply.sound-pitch", refreshed ? 1.35 : 1.15));
        }
    }

    private void showActiveIndicator(LivingEntity target, BloodBlossomData data, long now) {
        if (!hutaoConfig().getBoolean("blood-blossom.visual.indicator.enabled", true)) {
            return;
        }

        long periodMs = hutaoConfig().getLong("blood-blossom.visual.indicator.period-ms", 350L);
        if (now - data.lastVisualTimeMs < periodMs) {
            return;
        }
        data.lastVisualTimeMs = now;

        Particle mainParticle = getParticle(hutaoConfig().getString("blood-blossom.visual.indicator.particle", "SOUL_FIRE_FLAME"));
        Particle secondaryParticle = getParticle(hutaoConfig().getString("blood-blossom.visual.indicator.secondary-particle", "CRIMSON_SPORE"));
        double radius = hutaoConfig().getDouble("blood-blossom.visual.indicator.radius", 0.65);
        double yOffset = hutaoConfig().getDouble("blood-blossom.visual.indicator.y-offset", 1.0);
        int secondaryCount = hutaoConfig().getInt("blood-blossom.visual.indicator.secondary-count", 2);

        Location center = target.getLocation().clone().add(0, yOffset, 0);
        data.visualAngle += Math.PI / 4.0;
        for (int i = 0; i < 2; i++) {
            double angle = data.visualAngle + (Math.PI * i);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, 0.08 * i, z);
            if (mainParticle != null) {
                target.getWorld().spawnParticle(mainParticle, point, 1, 0.01, 0.01, 0.01, 0.0);
            }
            if (secondaryParticle != null) {
                target.getWorld().spawnParticle(secondaryParticle, point, secondaryCount, 0.03, 0.03, 0.03, 0.0);
            }
        }
    }

    private void playTickEffects(LivingEntity target) {
        Location loc = target.getLocation().clone().add(0, 1.0, 0);
        Particle mainParticle = getParticle(hutaoConfig().getString("blood-blossom.visual.tick.particle", "FLAME"));
        Particle secondaryParticle = getParticle(hutaoConfig().getString("blood-blossom.visual.tick.secondary-particle", "SMOKE"));
        int mainCount = hutaoConfig().getInt("blood-blossom.visual.tick.count", 10);
        int secondaryCount = hutaoConfig().getInt("blood-blossom.visual.tick.secondary-count", 6);

        if (mainParticle != null) {
            target.getWorld().spawnParticle(mainParticle, loc, mainCount, 0.25, 0.35, 0.25, 0.01);
        }
        if (secondaryParticle != null) {
            target.getWorld().spawnParticle(secondaryParticle, loc, secondaryCount, 0.25, 0.35, 0.25, 0.01);
        }

        Sound sound = getSound(hutaoConfig().getString("blood-blossom.visual.tick.sound", "BLOCK_FIRE_AMBIENT"));
        if (sound != null) {
            target.getWorld().playSound(target.getLocation(), sound,
                    (float) hutaoConfig().getDouble("blood-blossom.visual.tick.sound-volume", 0.7),
                    (float) hutaoConfig().getDouble("blood-blossom.visual.tick.sound-pitch", 1.3));
        }
    }

    private Particle getParticle(String particleName) {
        if (particleName == null || particleName.isEmpty()) {
            return null;
        }
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Sound getSound(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        }
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void sendConfiguredMessage(Player owner, String path, String fallback,
                                       String notificationKey, boolean defaultEnabled,
                                       String targetName, String damageText) {
        if (!hutaoConfig().getBoolean("notifications." + notificationKey, defaultEnabled)) {
            return;
        }

        String text = plugin.getSkillsConfig().getString(path, fallback);
        if (text == null || text.isBlank()) {
            return;
        }

        if (targetName != null) {
            text = text.replace("{target}", targetName);
        }
        if (damageText != null) {
            text = text.replace("{damage}", damageText);
        }
        owner.sendMessage(color(text));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private CharacterSkillConfig hutaoConfig() {
        return plugin.getCharacterConfig(CharacterType.HUTAO);
    }

    private static class BloodBlossomData {
        private final UUID ownerId;
        private final UUID targetId;
        private final long endTimeMs;
        private long nextTriggerTimeMs;
        private long lastVisualTimeMs;
        private double visualAngle;

        private BloodBlossomData(UUID ownerId, UUID targetId, long endTimeMs, long nextTriggerTimeMs) {
            this.ownerId = ownerId;
            this.targetId = targetId;
            this.endTimeMs = endTimeMs;
            this.nextTriggerTimeMs = nextTriggerTimeMs;
            this.lastVisualTimeMs = 0L;
            this.visualAngle = 0.0;
        }
    }
}
