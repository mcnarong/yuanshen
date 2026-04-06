package org.yuanshen.yuanshen;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

import static org.yuanshen.yuanshen.ElementConstant.*;

public class ElementReactionManager {
    private static final String INCOMING_DAMAGE_ELEMENT_META = "ys_incoming_damage_element";
    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";
    private static final String BLOOM_SEED_DAMAGE_META = "ys_bloom_seed_damage";
    private static final String BLOOM_SEED_OWNER_META = "ys_bloom_seed_owner";
    private static final String SEED_EXPLOSION_IMMUNITY_META = "ys_seed_explosion_immunity";
    private static final String GENERIC_SHIELD_KEY = "__generic__";
    private static final double MIN_AURA_THRESHOLD = 0.05;
    private static final int DEFAULT_MAX_TRIGGERED_SEEDS = 5;
    private static final long DEFAULT_TRANSFORMATIVE_DAMAGE_WINDOW_MS = 500L;

    private final Yuanshen plugin;
    private final ElementUtils utils;
    private final ConfigParser configParser;
    private final ElementDamageCalculator damageCalculator;

    // 存储草种子运行时数据
    private final Map<UUID, BloomSeedData> bloomSeeds = new HashMap<>();
    private final Map<UUID, BukkitTask> bloomSeedTasks = new HashMap<>();
    // 存储护盾
    private final Map<UUID, Map<String, Double>> playerShields = new HashMap<>();
    // 存储晶片实体和对应数据
    private final Map<UUID, CrystalChipData> crystalChipMap = new HashMap<>();
    // 存储过期任务
    private final Map<UUID, BukkitTask> expireTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> shieldExpireTasks = new HashMap<>();
    private final Map<String, Long> reactionCooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> electroChargeTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> burningTasks = new HashMap<>();
    private final Map<UUID, FrozenDecayState> frozenDecayStates = new HashMap<>();
    private final Map<String, AttachmentIcdState> attachmentIcdStates = new HashMap<>();
    private final Map<String, Deque<Long>> reactionDamageWindows = new HashMap<>();
    private long lastReactionDamageWindowPruneAt = 0L;

    private static final class AttachmentIcdState {
        private long windowStartedAt;
        private long windowDurationMs;
        private int hitsInWindow;
    }

    public static final class AttachmentProfile {
        private final String sourceKey;
        private final double auraAmount;
        private final Boolean standardIcdEnabled;
        private final long standardIcdWindowMs;
        private final int standardIcdHits;
        private final int standardIcdOffset;

        public AttachmentProfile(String sourceKey, double auraAmount) {
            this(sourceKey, auraAmount, null, -1L, -1, -1);
        }

        public AttachmentProfile(String sourceKey, double auraAmount, Boolean standardIcdEnabled,
                                 long standardIcdWindowMs, int standardIcdHits, int standardIcdOffset) {
            this.sourceKey = sourceKey == null || sourceKey.isBlank() ? "default" : sourceKey;
            this.auraAmount = Math.max(0.0, auraAmount);
            this.standardIcdEnabled = standardIcdEnabled;
            this.standardIcdWindowMs = standardIcdWindowMs;
            this.standardIcdHits = standardIcdHits;
            this.standardIcdOffset = standardIcdOffset;
        }

        public String getSourceKey() {
            return sourceKey;
        }

        public double getAuraAmount() {
            return auraAmount;
        }

        public boolean resolveStandardIcdEnabled(boolean fallbackValue) {
            return standardIcdEnabled == null ? fallbackValue : standardIcdEnabled;
        }

        public long resolveStandardIcdWindowMs(long fallbackValue) {
            return standardIcdWindowMs > 0L ? standardIcdWindowMs : fallbackValue;
        }

        public int resolveStandardIcdHits(int fallbackValue) {
            return standardIcdHits > 0 ? standardIcdHits : fallbackValue;
        }

        public int getStandardIcdOffset() {
            return standardIcdOffset;
        }

        public int resolveStandardIcdOffset(int fallbackValue) {
            return standardIcdOffset >= 0 ? standardIcdOffset : fallbackValue;
        }
    }

    private static final class ReactionPlan {
        private final String reactionType;
        private final String consumedAura;
        private final boolean strongVariant;

        private ReactionPlan(String reactionType, String consumedAura, boolean strongVariant) {
            this.reactionType = reactionType;
            this.consumedAura = consumedAura;
            this.strongVariant = strongVariant;
        }
    }

    private static final class PlanningState {
        private final Map<String, Double> targetElements;
        private final Set<String> processedAuras = new HashSet<>();
        private boolean frozenActive;
        private boolean quickenActive;
        private boolean quickenBonusProcessed;

        private PlanningState(Map<String, Double> targetElements, boolean frozenActive, boolean quickenActive) {
            this.targetElements = new LinkedHashMap<>(targetElements);
            this.frozenActive = frozenActive;
            this.quickenActive = quickenActive;
        }
    }

    private static final class BloomSeedData {
        private final UUID ownerId;
        private final double baseDamage;
        private final long spawnedAtMillis;

        private BloomSeedData(UUID ownerId, double baseDamage) {
            this.ownerId = ownerId;
            this.baseDamage = baseDamage;
            this.spawnedAtMillis = System.currentTimeMillis();
        }
    }

    private static final class FrozenDecayState {
        private double waterAmount;
        private double iceAmount;
        private double decayRatePerSecond;
        private long lastUpdatedAtMillis;
        private boolean active;

        private double getTotalAmount() {
            return Math.max(0.0, waterAmount) + Math.max(0.0, iceAmount);
        }
    }

    private static class CrystalChipData {
        String elementKey;
        double shieldValue;
        long spawnTime;
        CrystalChipData(String elementKey, double shieldValue) {
            this.elementKey = elementKey;
            this.shieldValue = shieldValue;
            this.spawnTime = System.currentTimeMillis();
        }
    }

    public ElementReactionManager(Yuanshen plugin, ElementUtils utils, ConfigParser configParser,
                                  ElementDamageCalculator damageCalculator) {
        this.plugin = plugin;
        this.utils = utils;
        this.configParser = configParser;
        this.damageCalculator = damageCalculator;
    }

    private String formatMessage(String template, Object... params) {
        if (template == null) return "";
        String msg = template;
        for (int i = 0; i + 1 < params.length; i += 2) {
            String key = String.valueOf(params[i]);
            String value = String.valueOf(params[i + 1]);
            msg = msg.replace("{" + key + "}", value);
        }
        return msg;
    }

    public ReactionResult handleReaction(Player attacker, LivingEntity target,
                                         String attackerElement, DamageResult damageResult) {
        return handleReaction(attacker, target, attackerElement, damageResult, new AttachmentProfile("legacy", 1.0));
    }

    public ReactionResult handleReaction(Player attacker, LivingEntity target,
                                         String attackerElement, DamageResult damageResult,
                                         String attackSourceKey, double desiredTriggerAuraAmount) {
        return handleReaction(
                attacker,
                target,
                attackerElement,
                damageResult,
                new AttachmentProfile(attackSourceKey, desiredTriggerAuraAmount)
        );
    }

    public ReactionResult handleReaction(Player attacker, LivingEntity target,
                                         String attackerElement, DamageResult damageResult,
                                         AttachmentProfile attachmentProfile) {
        ReactionResult result = new ReactionResult();
        result.setDamageElement(attackerElement);
        if (attacker == null || target == null || damageResult == null
                || attackerElement == null || attackerElement.isBlank()) {
            return result;
        }

        ElementAura aura = new ElementAura(target, plugin);
        Map<String, Double> targetElements = getTargetElementAmounts(target);
        syncReactionStatuses(target);
        targetElements = getTargetElementAmounts(target);
        boolean isFrozen = aura.hasStatus(FROZEN_KEY);
        boolean isQuicken = aura.hasStatus(QUICKEN_KEY);

        if (plugin.shouldLogReactionDebug()) {
            logDebug(attacker, target, attackerElement, targetElements, isFrozen);
        }

        double displayedDamage = PlayerStats.PHYSICAL_KEY.equals(attackerElement)
                ? damageResult.getPhysicalDamage()
                : damageResult.getElementalDamage();
        PlayerStats stats = damageResult.getPlayerStats();

        // ========== 第一优先级：碎冰 ==========
        if (isFrozen && canTriggerShatter(attackerElement)) {
            if (!canCurrentFrozenStateTriggerShatter(target)) {
                clearFrozenState(target, aura);
                return handleNormalDamage(attacker, target, result, attackerElement, displayedDamage);
            }
            String shatterCooldownKey = getReactionCooldownKey(attackerElement, "shatter", true);
            if (shouldThrottleReaction(target, attackerElement, "shatter", true)
                    && isReactionOnCooldown(target, attacker, shatterCooldownKey)) {
                return handleNormalDamage(attacker, target, result, attackerElement, displayedDamage);
            }
            executeReactionPlan(attacker, target, damageResult, result, new ReactionPlan("shatter", null, false));
            if (result.hasReaction()) {
                markReactionTriggered(attacker, target, result);
                return result;
            }
            return handleNormalDamage(attacker, target, result, attackerElement, displayedDamage);
        }

        double triggerAuraAmount = PlayerStats.PHYSICAL_KEY.equals(attackerElement)
                ? 0.0
                : resolveTriggerAuraAmount(attacker, target, attackerElement, attachmentProfile);
        result.setTriggerAuraAmount(triggerAuraAmount);
        if (!PlayerStats.PHYSICAL_KEY.equals(attackerElement) && triggerAuraAmount <= 0.0) {
            result.setApplyTriggerAura(false);
            result.setTriggerAuraAmount(0.0);
            return handleNormalDamage(attacker, target, result, attackerElement, displayedDamage);
        }

        List<ReactionPlan> reactionPlans = resolveReactionPipeline(
                attacker,
                target,
                attackerElement,
                targetElements,
                isFrozen,
                isQuicken
        );
        if (reactionPlans.isEmpty()) {
            return handleNormalDamage(attacker, target, result, attackerElement, displayedDamage);
        }

        for (ReactionPlan reactionPlan : reactionPlans) {
            executeReactionPlan(attacker, target, damageResult, result, reactionPlan);
        }
        if (result.hasReaction()) {
            markReactionTriggered(attacker, target, result);
            return result;
        }
        return handleNormalDamage(attacker, target, result, attackerElement, displayedDamage);

    }

    // ---------- 各反应具体实现（所有 baseDamage 参数均代表元素部分伤害） ----------

    private ReactionResult handleVaporize(Player attacker, LivingEntity target,
                                          ReactionResult result, double elementalDamage,
                                          double masteryBonus, String consumedAura, boolean hydroTrigger) {
        double consumeAmount = getReactionConsumeAmount(
                attacker,
                hydroTrigger ? "vaporize_forward" : "vaporize_reverse",
                hydroTrigger ? 1.0 : 0.5
        );
        double configuredMultiplier = getReactionMultiplier(attacker, "vaporize");
        double actualMultiplier = hydroTrigger
                ? Math.max(configuredMultiplier, 2.0)
                : Math.min(configuredMultiplier, 1.5);
        double bonusMultiplier = Math.max(0.0, actualMultiplier - 1.0);
        double extraDamage = Math.max(0.0, elementalDamage) * bonusMultiplier * (1 + masteryBonus * 1.15);

        result.addScalingDamage(extraDamage);
        result.setReactionName("蒸发");
        result.setReactionTag(hydroTrigger ? WATER_TAG : FIRE_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);

        String path = hydroTrigger ? "messages.reactions.vaporize_forward" : "messages.reactions.vaporize_reverse";
        String def = hydroTrigger
                ? "§9【正向蒸发】触发！额外造成§c{damage}§e点伤害"
                : "§6【反向蒸发】触发！额外造成§c{damage}§e点伤害";
        String tpl = configParser.parseString(attacker, path, def);
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(extraDamage)));

        if (target.getFireTicks() > 0) {
            target.setFireTicks(0);
        }
        spawnEffect(target, Particle.CLOUD, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE);
        return result;
    }

    private ReactionResult handleMelt(Player attacker, LivingEntity target,
                                      ReactionResult result, double elementalDamage,
                                      double masteryBonus, String consumedAura, boolean fireTrigger) {
        double consumeAmount = getReactionConsumeAmount(
                attacker,
                fireTrigger ? "melt_forward" : "melt_reverse",
                fireTrigger ? 1.0 : 0.5
        );
        double configuredMultiplier = getReactionMultiplier(attacker, "melt");
        double actualMultiplier = fireTrigger
                ? Math.max(configuredMultiplier, 2.0)
                : Math.min(configuredMultiplier, 1.5);
        double bonusMultiplier = Math.max(0.0, actualMultiplier - 1.0);
        double extraDamage = Math.max(0.0, elementalDamage) * bonusMultiplier * (1 + masteryBonus * 1.15);

        result.addScalingDamage(extraDamage);
        result.setReactionName("融化");
        result.setReactionTag(fireTrigger ? FIRE_TAG : ICE_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);

        String path = fireTrigger ? "messages.reactions.melt_forward" : "messages.reactions.melt_reverse";
        String def = fireTrigger
                ? "§6【正向融化】触发！造成§c{damage}§e点额外伤害"
                : "§b【反向融化】触发！造成§c{damage}§e点额外伤害";
        String tpl = configParser.parseString(attacker, path, def);
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(extraDamage)));

        spawnEffect(target, Particle.LAVA, Sound.BLOCK_FIRE_EXTINGUISH);
        return result;
    }

    private ReactionResult handleOverload(Player attacker, LivingEntity target,
                                          ReactionResult result, double masteryBonus,
                                          String consumedAura) {
        double consumeAmount = getReactionConsumeAmount(attacker, "overload", 1.0);
        double reactionDamage = getTransformativeReactionDamage(attacker, "overload", FIRE_KEY, masteryBonus, 2.75);
        result.addTransformativeDamage(reactionDamage, FIRE_KEY, "overload");
        result.setReactionName("超载");
        result.setReactionTag(FIRE_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.overload",
                "§d超载触发！爆炸造成§5{damage}§d点伤害");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(reactionDamage)));

        applyOverloadAreaEffect(attacker, target, reactionDamage);
        Vector knockback = target.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize().multiply(1.5);
        knockback.setY(0.3);
        target.setVelocity(knockback);
        spawnEffect(target, Particle.EXPLOSION, Sound.ENTITY_GENERIC_EXPLODE);
        return result;
    }

    private ReactionResult handleElectroCharge(Player attacker, LivingEntity target,
                                               ReactionResult result, double masteryBonus,
                                               String consumedAura) {
        int duration = configParser.parseInt("effects.electro_charge_duration", attacker, 100);
        int interval = Math.max(1, configParser.parseInt("effects.electro_charge_interval", attacker, 20));
        double consumeAmount = getReactionConsumeAmount(attacker, "electro_charge", 0.4);
        double initialDamage = getTransformativeReactionDamage(attacker, "electro_charge", ELECTRO_KEY, masteryBonus, 2.0);
        result.addTransformativeDamage(initialDamage, ELECTRO_KEY, "electro_charge");
        result.setReactionName("感电");
        result.setReactionTag(ELECTRO_TAG);
        result.setApplyTriggerAura(true);
        result.setTriggerAuraAmount(Math.min(result.getTriggerAuraAmount(), 0.4));
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.electro_charge",
                "§d感电触发！立即造成§5{damage}§d点伤害，并持续放电");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(initialDamage)));

        ElementAura aura = new ElementAura(target, plugin);
        aura.setStatus(ELECTRO_CHARGED_KEY, duration);
        startElectroChargeTask(attacker, target, duration, interval,
                getTransformativeReactionDamage(attacker, "electro_charge", ELECTRO_KEY, masteryBonus, 2.0) / 2.0,
                true);
        spawnEffect(target, Particle.ELECTRIC_SPARK, Sound.ENTITY_LIGHTNING_BOLT_IMPACT);
        return result;
    }

    private ReactionResult handleSuperconduct(Player attacker, LivingEntity target,
                                              ReactionResult result, double masteryBonus,
                                              String consumedAura) {
        double consumeAmount = getReactionConsumeAmount(attacker, "superconduct", 1.0);
        double reactionDamage = getTransformativeReactionDamage(attacker, "superconduct", ICE_KEY, masteryBonus, 1.5);
        result.addTransformativeDamage(reactionDamage, ICE_KEY, "superconduct");
        result.setReactionName("超导");
        result.setReactionTag(ICE_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.superconduct",
                "§d超导触发！造成§5{damage}§d点伤害，并降低物理抗性");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(reactionDamage)));

        applySuperconductAreaEffect(attacker, target, reactionDamage);
        if (SLOWNESS != null) {
            target.addPotionEffect(new PotionEffect(SLOWNESS, 100, 2));
        }
        spawnEffect(target, Particle.SNOWFLAKE, Sound.BLOCK_GLASS_BREAK);
        return result;
    }

    private ReactionResult handleFreeze(Player attacker, LivingEntity target,
                                        ReactionResult result, String consumedAura) {
        int duration = estimateFreezeDurationTicks(attacker, target, result, consumedAura);
        double consumeAmount = getReactionConsumeAmount(attacker, "freeze", 0.6);
        result.setReactionName("冻结");
        result.setReactionTag(ICE_TAG);
        result.setApplyTriggerAura(true);
        result.setTriggerAuraAmount(Math.min(result.getTriggerAuraAmount(), 0.4));
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        result.setDisplayNote("冻结 " + String.format(java.util.Locale.US, "%.1f", duration / 20.0) + " 秒");
        String tpl = configParser.parseString(attacker,
                "messages.reactions.freeze",
                "§b冻结触发！目标被冻结§3{seconds}§b秒");
        result.addMessage(formatMessage(tpl,
                "damage", "0.0",
                "seconds", String.format("%.1f", duration / 20.0)));

        ElementAura aura = new ElementAura(target, plugin);
        aura.replaceStatus(FROZEN_KEY, duration);
        if (SLOWNESS != null) {
            target.addPotionEffect(new PotionEffect(SLOWNESS, duration, 9));
        }
        spawnEffect(target, Particle.SNOWFLAKE, Sound.BLOCK_GLASS_PLACE);
        return result;
    }

    private ReactionResult handleShatter(Player attacker, LivingEntity target,
                                         ReactionResult result, double masteryBonus) {
        double reactionDamage = getTransformativeReactionDamage(attacker, "shatter", PlayerStats.PHYSICAL_KEY, masteryBonus, 3.0);
        result.addTransformativeDamage(reactionDamage, PlayerStats.PHYSICAL_KEY, "shatter");
        result.setReactionName("碎冰");
        result.setReactionTag("§7【物理】");
        result.setApplyTriggerAura(false);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.shatter",
                "§7碎冰触发！造成§f{damage}§7点物理伤害");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(reactionDamage)));

        ElementAura aura = new ElementAura(target, plugin);
        clearFrozenState(target, aura);
        spawnEffect(target, Particle.ITEM_SNOWBALL, Sound.BLOCK_GLASS_BREAK);
        return result;
    }

    private ReactionResult handleQuicken(Player attacker, LivingEntity target,
                                         ReactionResult result, String consumedAura,
                                         String attackerElement) {
        double consumeAmount = getReactionConsumeAmount(attacker, "quicken", 0.3);
        result.setReactionName("原激化");
        result.setReactionTag(DENDRO_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.quicken",
                "§e原激化触发！目标进入激化状态");
        result.addMessage(tpl);

        ElementAura aura = new ElementAura(target, plugin);
        Map<String, Double> quickenProfile = new LinkedHashMap<>();
        double triggerAuraAmount = Math.max(result.getTriggerAuraAmount(), consumeAmount);
        quickenProfile.put(
                DENDRO_KEY,
                Math.max(getEffectiveElementAmount(aura, DENDRO_KEY), DENDRO_KEY.equals(attackerElement) ? triggerAuraAmount : 0.0)
        );
        quickenProfile.put(
                ELECTRO_KEY,
                Math.max(getEffectiveElementAmount(aura, ELECTRO_KEY), ELECTRO_KEY.equals(attackerElement) ? triggerAuraAmount : 0.0)
        );
        aura.setStatusProfile(QUICKEN_KEY, getQuickenDurationTicks(attacker), quickenProfile);
        spawnEffect(target, Particle.HAPPY_VILLAGER, Sound.BLOCK_AMETHYST_BLOCK_HIT);
        return result;
    }

    private ReactionResult handleAggravate(Player attacker, LivingEntity target,
                                           ReactionResult result, double masteryBonus) {
        double bonusDamage = getAdditiveReactionDamage(attacker, "aggravate", ELECTRO_KEY, masteryBonus, 1.15);
        result.addAdditiveDamage(bonusDamage);
        result.setReactionName("超激化");
        result.setReactionTag(ELECTRO_TAG);
        result.setApplyTriggerAura(false);
        result.setNoConsume(true);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.aggravate",
                "§a超激化触发！造成§2{damage}§a点额外伤害");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(bonusDamage)));

        ElementAura aura = new ElementAura(target, plugin);
        aura.setStatus(QUICKEN_KEY, getQuickenDurationTicks(attacker));
        spawnEffect(target, Particle.ELECTRIC_SPARK, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        return result;
    }

    private ReactionResult handleSpread(Player attacker, LivingEntity target,
                                        ReactionResult result, double masteryBonus) {
        double bonusDamage = getAdditiveReactionDamage(attacker, "spread", DENDRO_KEY, masteryBonus, 1.25);
        result.addAdditiveDamage(bonusDamage);
        result.setReactionName("蔓激化");
        result.setReactionTag(DENDRO_TAG);
        result.setApplyTriggerAura(false);
        result.setNoConsume(true);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.spread",
                "§e蔓激化触发！造成§a{damage}§e点额外伤害");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(bonusDamage)));

        ElementAura aura = new ElementAura(target, plugin);
        aura.setStatus(QUICKEN_KEY, getQuickenDurationTicks(attacker));
        spawnEffect(target, Particle.HAPPY_VILLAGER, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        return result;
    }

    private ReactionResult handleBloom(Player attacker, LivingEntity target,
                                       ReactionResult result, double masteryBonus,
                                       String consumedAura) {
        double consumeAmount = getReactionConsumeAmount(attacker, "bloom", 0.5);
        double seedDamage = getTransformativeReactionDamage(attacker, "bloom", DENDRO_KEY, masteryBonus, 2.0);
        result.setReactionName("绽放");
        result.setReactionTag(DENDRO_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        result.addMessage(configParser.parseString(attacker,
                "messages.reactions.bloom",
                "§a绽放触发！生成了草原核。"));

        double chance = configParser.parseDouble("effects.seed_spawn_chance", attacker, 1.0);
        if (Math.random() <= chance) {
            spawnBloomSeed(target.getLocation(), attacker, seedDamage);
        }
        spawnEffect(target, Particle.COMPOSTER, Sound.BLOCK_GRASS_PLACE);
        return result;
    }

    private ReactionResult handleBurning(Player attacker, LivingEntity target,
                                         ReactionResult result, double masteryBonus,
                                         String consumedAura) {
        int duration = configParser.parseInt("effects.burning_duration", attacker, 100);
        int interval = Math.max(1, configParser.parseInt("effects.burning_interval", attacker, 5));
        double consumeAmount = getReactionConsumeAmount(attacker, "burning", 0.4);
        result.setReactionName("燃烧");
        result.setReactionTag(FIRE_TAG);
        result.setApplyTriggerAura(true);
        result.setTriggerAuraAmount(Math.min(result.getTriggerAuraAmount(), 0.4));
        result.addConsumedElement(consumedAura);
        result.setCustomConsumeAmount(consumeAmount);
        result.addMessage(configParser.parseString(attacker,
                "messages.reactions.burning",
                "§c燃烧触发！目标进入持续燃烧状态。"));

        double fallbackPerTick = getTransformativeReactionDamage(attacker, "burning", FIRE_KEY, masteryBonus, 0.25);
        double perTick = Math.max(0.0, configParser.parseDouble("effects.burning_damage_per_tick", attacker, fallbackPerTick));
        ElementAura aura = new ElementAura(target, plugin);
        aura.setStatus(BURNING_KEY, duration);
        startBurningTask(attacker, target, duration, interval, perTick);
        target.setFireTicks(duration);
        spawnEffect(target, Particle.FLAME, Sound.ENTITY_BLAZE_SHOOT);
        return result;
    }

    private ReactionResult handleCrystal(Player attacker, LivingEntity target,
                                         ReactionResult result, String elementKey,
                                         double masteryBonus) {
        double consumeAmount = getReactionConsumeAmount(attacker, "crystal", 0.5);
        double shieldValue = getBaseDamage(attacker, GEO_KEY)
                * configParser.parseDouble("reactions.crystal_shield", attacker, 3.0)
                * (1.0 + masteryBonus);
        result.setReactionName("结晶");
        result.setReactionTag(GEO_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(elementKey);
        result.setCustomConsumeAmount(consumeAmount);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.crystal",
                "§6结晶触发！生成{elementName}元素晶片，护盾值§e{shield}");
        result.addMessage(formatMessage(tpl,
                "elementName", getElementName(elementKey),
                "shield", utils.formatDamage(shieldValue)));

        spawnCrystalChip(target.getLocation(), elementKey, shieldValue);
        return result;
    }

    private ReactionResult handleSwirl(Player attacker, LivingEntity target,
                                       ReactionResult result, String elementKey,
                                       double masteryBonus) {
        double consumeAmount = getReactionConsumeAmount(attacker, "swirl", 0.5);
        double swirlDamage = getTransformativeReactionDamage(attacker, "swirl", elementKey, masteryBonus, 0.6);

        result.addTransformativeDamage(swirlDamage, elementKey, buildSwirlReactionDamageKey(elementKey));
        result.setReactionName("扩散");
        result.setReactionTag(ANEMO_TAG);
        result.setApplyTriggerAura(false);
        result.addConsumedElement(elementKey);
        result.setCustomConsumeAmount(consumeAmount);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.swirl",
                "§7扩散 {elementName} 触发！造成§f{damage}§7点范围伤害");
        result.addMessage(formatMessage(tpl,
                "elementName", getElementName(elementKey),
                "damage", utils.formatDamage(swirlDamage)));

        applySwirlAreaDamage(attacker, target, target.getLocation(), swirlDamage, elementKey);
        return result;
    }

    private ReactionResult handleHyperbloom(Player attacker, LivingEntity target,
                                            ReactionResult result, double elementalDamage,
                                            double masteryBonus) {
        double hyperbloomDamage = getTransformativeReactionDamage(attacker, "hyperbloom", DENDRO_KEY, masteryBonus, 3.0);
        result.addTransformativeDamage(hyperbloomDamage, DENDRO_KEY, "hyperbloom");
        result.setReactionName("超绽放");
        result.setReactionTag(ELECTRO_TAG);
        result.setApplyTriggerAura(false);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.hyperbloom",
                "§d超绽放触发！造成§5{damage}§d点追踪伤害");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(hyperbloomDamage)));

        triggerSeedReaction(attacker, target.getLocation(), ELECTRO_KEY);
        return result;
    }

    private ReactionResult handleBurgeon(Player attacker, LivingEntity target,
                                         ReactionResult result, double elementalDamage,
                                         double masteryBonus) {
        double burgeonDamage = getTransformativeReactionDamage(attacker, "burgeon", DENDRO_KEY, masteryBonus, 3.0);
        result.addTransformativeDamage(burgeonDamage, DENDRO_KEY, "burgeon");
        result.setReactionName("烈绽放");
        result.setReactionTag(FIRE_TAG);
        result.setApplyTriggerAura(false);
        String tpl = configParser.parseString(attacker,
                "messages.reactions.burgeon",
                "§c烈绽放触发！造成§6{damage}§c点范围爆炸伤害");
        result.addMessage(formatMessage(tpl, "damage", utils.formatDamage(burgeonDamage)));

        triggerSeedReaction(attacker, target.getLocation(), FIRE_KEY);
        return result;
    }

    private ReactionResult handleNormalDamage(Player attacker, LivingEntity target,
                                              ReactionResult result, String element, double damageAmount) {
        result.setNormalDamage(true);
        result.setNormalDamageElement(element);
        result.setReactionTag(getElementTag(element));
        String tpl = configParser.parseString(attacker,
                "messages.reactions.normal_element",
                "造成§f{damage}§7点{elementName}元素伤害");
        result.addMessage(formatMessage(tpl,
                "damage", utils.formatDamage(damageAmount),
                "elementName", getElementName(element)));

        switch (element) {
            case FIRE_KEY -> {
                // 普通火元素伤害只保留元素特效，不再附带原版燃烧，避免持续掉血影响手感与测试
                spawnEffect(target, Particle.FLAME, Sound.ENTITY_BLAZE_HURT);
            }
            case ICE_KEY -> {
                if (SLOWNESS != null) target.addPotionEffect(new PotionEffect(SLOWNESS, 100, 1));
                spawnEffect(target, Particle.SNOWFLAKE, Sound.BLOCK_POWDER_SNOW_FALL);
            }
            case ANEMO_KEY -> {
                int range = configParser.parseInt("effects.pull_range", attacker, 5);
                pullNearbyEntities(target.getLocation(), range, 1);
                spawnEffect(target, Particle.CLOUD, Sound.ENTITY_BREEZE_WIND_BURST);
            }
            case ELECTRO_KEY -> spawnEffect(target, Particle.ELECTRIC_SPARK, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            case GEO_KEY -> spawnEffect(target, Particle.CRIT, Sound.BLOCK_STONE_HIT);
            case DENDRO_KEY -> spawnEffect(target, Particle.COMPOSTER, Sound.BLOCK_GRASS_HIT);
            case PlayerStats.PHYSICAL_KEY -> {
                target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 8, 0.25, 0.35, 0.25, 0.02);
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f);
            }
        }
        return result;
    }

    private void executeReactionPlan(Player attacker, LivingEntity target, DamageResult damageResult,
                                     ReactionResult result, ReactionPlan reactionPlan) {
        if (attacker == null || target == null || damageResult == null || result == null || reactionPlan == null) {
            return;
        }
        PlayerStats stats = damageResult.getPlayerStats();
        double masteryBonus = getMasteryBonus(attacker, stats, reactionPlan.reactionType);
        switch (reactionPlan.reactionType) {
            case "vaporize" -> handleVaporize(
                    attacker,
                    target,
                    result,
                    damageResult.getElementalDamage(),
                    masteryBonus,
                    reactionPlan.consumedAura,
                    reactionPlan.strongVariant
            );
            case "melt" -> handleMelt(
                    attacker,
                    target,
                    result,
                    damageResult.getElementalDamage(),
                    masteryBonus,
                    reactionPlan.consumedAura,
                    reactionPlan.strongVariant
            );
            case "overload" -> handleOverload(
                    attacker,
                    target,
                    result,
                    masteryBonus,
                    reactionPlan.consumedAura
            );
            case "electro_charge" -> handleElectroCharge(
                    attacker,
                    target,
                    result,
                    masteryBonus,
                    reactionPlan.consumedAura
            );
            case "freeze" -> handleFreeze(
                    attacker,
                    target,
                    result,
                    reactionPlan.consumedAura
            );
            case "superconduct" -> handleSuperconduct(
                    attacker,
                    target,
                    result,
                    masteryBonus,
                    reactionPlan.consumedAura
            );
            case "shatter" -> handleShatter(attacker, target, result, masteryBonus);
            case "quicken" -> handleQuicken(
                    attacker,
                    target,
                    result,
                    reactionPlan.consumedAura,
                    result.getDamageElement()
            );
            case "aggravate" -> handleAggravate(
                    attacker,
                    target,
                    result,
                    masteryBonus
            );
            case "spread" -> handleSpread(
                    attacker,
                    target,
                    result,
                    masteryBonus
            );
            case "bloom" -> handleBloom(
                    attacker,
                    target,
                    result,
                    masteryBonus,
                    reactionPlan.consumedAura
            );
            case "burning" -> handleBurning(
                    attacker,
                    target,
                    result,
                    masteryBonus,
                    reactionPlan.consumedAura
            );
            case "crystal_shield" -> handleCrystal(
                    attacker,
                    target,
                    result,
                    reactionPlan.consumedAura,
                    masteryBonus
            );
            case "swirl" -> handleSwirl(
                    attacker,
                    target,
                    result,
                    reactionPlan.consumedAura,
                    masteryBonus
            );
            default -> {
            }
        }
    }

    private List<ReactionPlan> resolveReactionPipeline(Player attacker, LivingEntity target, String attackerElement,
                                                       Map<String, Double> targetElements,
                                                       boolean isFrozen, boolean isQuicken) {
        if (attackerElement == null || attackerElement.isBlank() || targetElements == null || targetElements.isEmpty()) {
            return List.of();
        }

        PlanningState planningState = new PlanningState(targetElements, isFrozen, isQuicken);
        List<ReactionPlan> plans = new ArrayList<>();
        int maxStages = Math.max(1, configParser.parseInt("reactions.pipeline.max-stages", attacker, 4));
        for (int stageIndex = 0; stageIndex < maxStages; stageIndex++) {
            ReactionPlan reactionPlan = resolveNextReactionPlan(attackerElement, planningState);
            if (reactionPlan == null || "default".equals(reactionPlan.reactionType)) {
                break;
            }

            String reactionCooldownKey = getReactionCooldownKey(attackerElement, reactionPlan.reactionType, planningState.frozenActive);
            boolean onCooldown = shouldThrottleReaction(target, attackerElement, reactionPlan.reactionType, planningState.frozenActive)
                    && isReactionOnCooldown(target, attacker, reactionCooldownKey);
            if (onCooldown) {
                skipPlannedReactionState(reactionPlan, planningState);
                continue;
            }
            applyPlannedReactionState(attacker, reactionPlan, planningState);
            plans.add(reactionPlan);
        }
        return plans;
    }

    private ReactionPlan resolveNextReactionPlan(String attackerElement, PlanningState planningState) {
        if (attackerElement == null || attackerElement.isBlank() || planningState == null) {
            return new ReactionPlan("default", null, false);
        }

        if (planningState.quickenActive && !planningState.quickenBonusProcessed) {
            if (ELECTRO_KEY.equals(attackerElement)) {
                return new ReactionPlan("aggravate", null, false);
            }
            if (DENDRO_KEY.equals(attackerElement)) {
                return new ReactionPlan("spread", null, false);
            }
        }

        boolean electroChargedCarriersPresent = hasAura(planningState.targetElements, WATER_KEY)
                && hasAura(planningState.targetElements, ELECTRO_KEY);
        if (electroChargedCarriersPresent && !planningState.processedAuras.contains(ELECTRO_KEY)) {
            if (FIRE_KEY.equals(attackerElement)) {
                return new ReactionPlan("overload", ELECTRO_KEY, false);
            }
            if (ICE_KEY.equals(attackerElement)) {
                return new ReactionPlan("superconduct", ELECTRO_KEY, false);
            }
            if (DENDRO_KEY.equals(attackerElement)) {
                return new ReactionPlan("quicken", ELECTRO_KEY, false);
            }
            if (ANEMO_KEY.equals(attackerElement)) {
                return new ReactionPlan("swirl", ELECTRO_KEY, false);
            }
            if (GEO_KEY.equals(attackerElement)) {
                return new ReactionPlan("crystal_shield", ELECTRO_KEY, false);
            }
        }

        if (planningState.frozenActive
                && FIRE_KEY.equals(attackerElement)
                && !planningState.processedAuras.contains(
                shouldFireTriggerMeltFirstAgainstFrozen(planningState.targetElements) ? ICE_KEY : WATER_KEY
        )) {
            if (shouldFireTriggerMeltFirstAgainstFrozen(planningState.targetElements)
                    && hasAura(planningState.targetElements, ICE_KEY)) {
                return new ReactionPlan("melt", ICE_KEY, true);
            }
            if (hasAura(planningState.targetElements, WATER_KEY)) {
                return new ReactionPlan("vaporize", WATER_KEY, false);
            }
            if (hasAura(planningState.targetElements, ICE_KEY)) {
                return new ReactionPlan("melt", ICE_KEY, true);
            }
        }
        if (planningState.frozenActive
                && ELECTRO_KEY.equals(attackerElement)
                && hasAura(planningState.targetElements, ICE_KEY)
                && !planningState.processedAuras.contains(ICE_KEY)) {
            return new ReactionPlan("superconduct", ICE_KEY, false);
        }

        return switch (attackerElement) {
            case FIRE_KEY -> {
                String dominantAura = selectDominantAura(
                        planningState.targetElements,
                        planningState.processedAuras,
                        WATER_KEY, ICE_KEY, ELECTRO_KEY, DENDRO_KEY
                );
                if (WATER_KEY.equals(dominantAura)) yield new ReactionPlan("vaporize", WATER_KEY, false);
                if (ICE_KEY.equals(dominantAura)) yield new ReactionPlan("melt", ICE_KEY, true);
                if (ELECTRO_KEY.equals(dominantAura)) yield new ReactionPlan("overload", ELECTRO_KEY, false);
                if (DENDRO_KEY.equals(dominantAura)) yield new ReactionPlan("burning", DENDRO_KEY, false);
                yield new ReactionPlan("default", null, false);
            }
            case WATER_KEY -> {
                String dominantAura = selectDominantAura(
                        planningState.targetElements,
                        planningState.processedAuras,
                        FIRE_KEY, ELECTRO_KEY, ICE_KEY, DENDRO_KEY
                );
                if (FIRE_KEY.equals(dominantAura)) yield new ReactionPlan("vaporize", FIRE_KEY, true);
                if (ELECTRO_KEY.equals(dominantAura)) yield new ReactionPlan("electro_charge", ELECTRO_KEY, false);
                if (ICE_KEY.equals(dominantAura)) yield new ReactionPlan("freeze", ICE_KEY, false);
                if (DENDRO_KEY.equals(dominantAura)) yield new ReactionPlan("bloom", DENDRO_KEY, false);
                yield new ReactionPlan("default", null, false);
            }
            case ICE_KEY -> {
                String dominantAura = selectDominantAura(
                        planningState.targetElements,
                        planningState.processedAuras,
                        FIRE_KEY, WATER_KEY, ELECTRO_KEY
                );
                if (FIRE_KEY.equals(dominantAura)) yield new ReactionPlan("melt", FIRE_KEY, false);
                if (WATER_KEY.equals(dominantAura)) yield new ReactionPlan("freeze", WATER_KEY, false);
                if (ELECTRO_KEY.equals(dominantAura)) yield new ReactionPlan("superconduct", ELECTRO_KEY, false);
                yield new ReactionPlan("default", null, false);
            }
            case ELECTRO_KEY -> {
                Set<String> excludedAuras = new HashSet<>(planningState.processedAuras);
                if (planningState.quickenBonusProcessed) {
                    excludedAuras.add(DENDRO_KEY);
                }
                String dominantAura = selectDominantAura(
                        planningState.targetElements,
                        excludedAuras,
                        FIRE_KEY, WATER_KEY, ICE_KEY, DENDRO_KEY
                );
                if (FIRE_KEY.equals(dominantAura)) yield new ReactionPlan("overload", FIRE_KEY, false);
                if (WATER_KEY.equals(dominantAura)) yield new ReactionPlan("electro_charge", WATER_KEY, false);
                if (ICE_KEY.equals(dominantAura)) yield new ReactionPlan("superconduct", ICE_KEY, false);
                if (DENDRO_KEY.equals(dominantAura)) yield new ReactionPlan("quicken", DENDRO_KEY, false);
                yield new ReactionPlan("default", null, false);
            }
            case DENDRO_KEY -> {
                Set<String> excludedAuras = new HashSet<>(planningState.processedAuras);
                if (planningState.quickenBonusProcessed) {
                    excludedAuras.add(ELECTRO_KEY);
                }
                String dominantAura = selectDominantAura(
                        planningState.targetElements,
                        excludedAuras,
                        ELECTRO_KEY, WATER_KEY, FIRE_KEY
                );
                if (ELECTRO_KEY.equals(dominantAura)) yield new ReactionPlan("quicken", ELECTRO_KEY, false);
                if (WATER_KEY.equals(dominantAura)) yield new ReactionPlan("bloom", WATER_KEY, false);
                if (FIRE_KEY.equals(dominantAura)) yield new ReactionPlan("burning", FIRE_KEY, false);
                yield new ReactionPlan("default", null, false);
            }
            case ANEMO_KEY -> {
                String swirlElement = selectDominantAura(
                        planningState.targetElements,
                        planningState.processedAuras,
                        FIRE_KEY, WATER_KEY, ELECTRO_KEY, ICE_KEY
                );
                yield swirlElement == null
                        ? new ReactionPlan("default", null, false)
                        : new ReactionPlan("swirl", swirlElement, false);
            }
            case GEO_KEY -> {
                String crystalElement = selectDominantAura(
                        planningState.targetElements,
                        planningState.processedAuras,
                        FIRE_KEY, WATER_KEY, ELECTRO_KEY, ICE_KEY
                );
                yield crystalElement == null
                        ? new ReactionPlan("default", null, false)
                        : new ReactionPlan("crystal_shield", crystalElement, false);
            }
            default -> new ReactionPlan("default", null, false);
        };
    }

    private void applyPlannedReactionState(Player attacker, ReactionPlan reactionPlan, PlanningState planningState) {
        if (reactionPlan == null || planningState == null) {
            return;
        }
        switch (reactionPlan.reactionType) {
            case "aggravate", "spread" -> {
                planningState.quickenBonusProcessed = true;
                return;
            }
            case "shatter" -> {
                planningState.frozenActive = false;
                planningState.processedAuras.add(WATER_KEY);
                planningState.processedAuras.add(ICE_KEY);
                return;
            }
            default -> {
            }
        }

        if (reactionPlan.consumedAura != null && !reactionPlan.consumedAura.isBlank()) {
            planningState.processedAuras.add(reactionPlan.consumedAura);
            double currentAmount = planningState.targetElements.getOrDefault(reactionPlan.consumedAura, 0.0);
            double consumeAmount = resolvePlannedConsumeAmount(attacker, reactionPlan);
            planningState.targetElements.put(reactionPlan.consumedAura, Math.max(0.0, currentAmount - consumeAmount));
        }

        if (planningState.frozenActive
                && (!hasAura(planningState.targetElements, WATER_KEY) || !hasAura(planningState.targetElements, ICE_KEY))) {
            planningState.frozenActive = false;
        }
        if (planningState.quickenActive
                && (!hasAura(planningState.targetElements, DENDRO_KEY) || !hasAura(planningState.targetElements, ELECTRO_KEY))) {
            planningState.quickenActive = false;
        }
    }

    private void skipPlannedReactionState(ReactionPlan reactionPlan, PlanningState planningState) {
        if (reactionPlan == null || planningState == null) {
            return;
        }
        switch (reactionPlan.reactionType) {
            case "aggravate", "spread" -> {
                planningState.quickenBonusProcessed = true;
                return;
            }
            case "shatter" -> {
                planningState.processedAuras.add(WATER_KEY);
                planningState.processedAuras.add(ICE_KEY);
                return;
            }
            default -> {
            }
        }
        if (reactionPlan.consumedAura != null && !reactionPlan.consumedAura.isBlank()) {
            planningState.processedAuras.add(reactionPlan.consumedAura);
        }
    }

    private double resolvePlannedConsumeAmount(Player attacker, ReactionPlan reactionPlan) {
        if (reactionPlan == null || reactionPlan.reactionType == null || reactionPlan.reactionType.isBlank()) {
            return 0.0;
        }
        return switch (reactionPlan.reactionType) {
            case "vaporize" -> getReactionConsumeAmount(
                    attacker,
                    reactionPlan.strongVariant ? "vaporize_forward" : "vaporize_reverse",
                    reactionPlan.strongVariant ? 1.0 : 0.5
            );
            case "melt" -> getReactionConsumeAmount(
                    attacker,
                    reactionPlan.strongVariant ? "melt_forward" : "melt_reverse",
                    reactionPlan.strongVariant ? 1.0 : 0.5
            );
            case "overload" -> getReactionConsumeAmount(attacker, "overload", 1.0);
            case "electro_charge" -> getReactionConsumeAmount(attacker, "electro_charge", 0.4);
            case "freeze" -> getReactionConsumeAmount(attacker, "freeze", 0.6);
            case "superconduct" -> getReactionConsumeAmount(attacker, "superconduct", 1.0);
            case "quicken" -> getReactionConsumeAmount(attacker, "quicken", 0.3);
            case "bloom" -> getReactionConsumeAmount(attacker, "bloom", 0.5);
            case "burning" -> getReactionConsumeAmount(attacker, "burning", 0.4);
            case "crystal_shield" -> getReactionConsumeAmount(attacker, "crystal", 0.5);
            case "swirl" -> getReactionConsumeAmount(attacker, "swirl", 0.5);
            default -> 0.0;
        };
    }

    // ---------- 辅助方法（保持不变） ----------

    private Map<String, Double> getTargetElementAmounts(LivingEntity target) {
        ElementAura aura = new ElementAura(target, plugin);
        Map<String, Double> elements = new LinkedHashMap<>();
        elements.put(FIRE_KEY, getEffectiveElementAmount(aura, FIRE_KEY));
        elements.put(WATER_KEY, getEffectiveElementAmount(aura, WATER_KEY));
        elements.put(ICE_KEY, getEffectiveElementAmount(aura, ICE_KEY));
        elements.put(ELECTRO_KEY, getEffectiveElementAmount(aura, ELECTRO_KEY));
        elements.put(ANEMO_KEY, getEffectiveElementAmount(aura, ANEMO_KEY));
        elements.put(GEO_KEY, getEffectiveElementAmount(aura, GEO_KEY));
        elements.put(DENDRO_KEY, getEffectiveElementAmount(aura, DENDRO_KEY));
        return elements;
    }

    private boolean hasAura(Map<String, Double> targetElements, String elementKey) {
        if (targetElements == null || elementKey == null || elementKey.isBlank()) {
            return false;
        }
        return targetElements.getOrDefault(elementKey, 0.0) > MIN_AURA_THRESHOLD;
    }

    private boolean canTriggerShatter(String attackerElement) {
        return PlayerStats.PHYSICAL_KEY.equals(attackerElement) || GEO_KEY.equals(attackerElement);
    }

    private List<String> getCompositeStatusKeys() {
        return List.of(FROZEN_KEY, ELECTRO_CHARGED_KEY, BURNING_KEY, QUICKEN_KEY);
    }

    private String[] getCompositeStatusCarrierElements(String statusKey) {
        return switch (statusKey) {
            case FROZEN_KEY -> new String[]{WATER_KEY, ICE_KEY};
            case ELECTRO_CHARGED_KEY -> new String[]{WATER_KEY, ELECTRO_KEY};
            case BURNING_KEY -> new String[]{DENDRO_KEY, FIRE_KEY};
            case QUICKEN_KEY -> new String[]{DENDRO_KEY, ELECTRO_KEY};
            default -> new String[0];
        };
    }

    private boolean compositeStatusCarriesElement(String statusKey, String elementKey) {
        if (statusKey == null || elementKey == null || elementKey.isBlank()) {
            return false;
        }
        for (String carrierElement : getCompositeStatusCarrierElements(statusKey)) {
            if (Objects.equals(carrierElement, elementKey)) {
                return true;
            }
        }
        return false;
    }

    private double getEffectiveElementAmount(ElementAura aura, String elementKey) {
        if (aura == null || elementKey == null || elementKey.isBlank()) {
            return 0.0;
        }
        double amount = aura.getElementAmount(elementKey);
        for (String statusKey : getCompositeStatusKeys()) {
            if (aura.hasStatus(statusKey) && compositeStatusCarriesElement(statusKey, elementKey)) {
                amount = Math.max(amount, aura.getStatusProfileAmount(statusKey, elementKey));
            }
        }
        return amount;
    }

    public double getEffectiveAuraAmount(LivingEntity target, String elementKey) {
        if (target == null || elementKey == null || elementKey.isBlank()) {
            return 0.0;
        }
        return getEffectiveElementAmount(new ElementAura(target, plugin), elementKey);
    }

    public void addExternalAura(LivingEntity target, String elementKey, double amount, int durationTicks) {
        addElementAura(target, elementKey, amount, durationTicks);
        syncReactionStatuses(target);
    }

    public void removeEffectiveAura(LivingEntity target, String elementKey) {
        if (target == null || elementKey == null || elementKey.isBlank()) {
            return;
        }
        ElementAura aura = new ElementAura(target, plugin);
        aura.removeElement(elementKey);
        updateCompositeStatusElementProfiles(aura, elementKey, 0.0);
        syncReactionStatuses(target);
    }

    private double getBaseDamage(Player player, String element) {
        String elementName = element.replace("element_", "");
        String path = "elements." + elementName + "_base_damage";
        return configParser.parseDouble(path, player, 5.0);
    }

    private double getReactionMultiplier(Player player, String reaction) {
        String path = "reactions." + reaction;
        return configParser.parseDouble(path, player, 1.0);
    }

    private double getReactionConsumeAmount(Player player, String consumeKey, double fallback) {
        return Math.max(0.0, configParser.parseDouble("reactions.consume." + consumeKey, player, fallback));
    }

    private ReactionPlan resolveReactionPlan(String attackerElement, Map<String, Double> targetElements,
                                             boolean isFrozen, boolean isQuicken) {
        return resolveNextReactionPlan(attackerElement, new PlanningState(targetElements, isFrozen, isQuicken));
    }

    private String selectDominantAura(Map<String, Double> targetElements, String... candidateElements) {
        return selectDominantAura(targetElements, Collections.emptySet(), candidateElements);
    }

    private String selectDominantAura(Map<String, Double> targetElements, Set<String> excludedElements,
                                      String... candidateElements) {
        if (targetElements == null || candidateElements == null || candidateElements.length == 0) {
            return null;
        }

        String bestElement = null;
        double bestAmount = MIN_AURA_THRESHOLD;
        for (String elementKey : candidateElements) {
            if (elementKey == null || (excludedElements != null && excludedElements.contains(elementKey))) {
                continue;
            }
            double amount = targetElements.getOrDefault(elementKey, 0.0);
            if (amount > bestAmount) {
                bestAmount = amount;
                bestElement = elementKey;
            }
        }
        return bestElement;
    }

    private String selectDominantSwirlableAura(Map<String, Double> targetElements) {
        return selectDominantAura(targetElements, FIRE_KEY, WATER_KEY, ELECTRO_KEY, ICE_KEY);
    }

    private int getFreezeDuration(Player player) {
        return Math.max(1, configParser.parseInt("effects.freeze_duration", player, 80));
    }

    private int estimateFreezeDurationTicks(Player attacker, LivingEntity target, ReactionResult result, String consumedAura) {
        if (target == null || result == null || consumedAura == null || consumedAura.isBlank()) {
            return getFreezeDuration(attacker);
        }

        long now = System.currentTimeMillis();
        FrozenDecayState state = resolveFrozenDecayState(target.getUniqueId(), now);
        double startRate = state == null
                ? getFreezeBaseDecayPerSecond(attacker)
                : Math.max(getFreezeBaseDecayPerSecond(attacker), state.decayRatePerSecond);
        double existingAmount = Math.max(0.0, getEffectiveAuraAmount(target, consumedAura));
        double triggerAmount = Math.max(0.0, result.getTriggerAuraAmount());
        double estimatedAmount = Math.max(0.0, existingAmount + triggerAmount);
        return computeFreezeDurationTicks(attacker, estimatedAmount, startRate);
    }

    private boolean shouldFireTriggerMeltFirstAgainstFrozen(Map<String, Double> targetElements) {
        if (targetElements == null) {
            return true;
        }
        double waterAmount = targetElements.getOrDefault(WATER_KEY, 0.0);
        double iceAmount = targetElements.getOrDefault(ICE_KEY, 0.0);
        if (iceAmount <= MIN_AURA_THRESHOLD) {
            return false;
        }
        if (waterAmount <= MIN_AURA_THRESHOLD) {
            return true;
        }
        return iceAmount <= waterAmount;
    }

    private boolean canCurrentFrozenStateTriggerShatter(LivingEntity target) {
        if (target == null) {
            return false;
        }
        FrozenDecayState state = resolveFrozenDecayState(target.getUniqueId(), System.currentTimeMillis());
        if (state == null || !state.active) {
            return false;
        }
        return state.getTotalAmount() >= getFreezeShatterThreshold(null);
    }

    private boolean containsReactionStage(ReactionResult reactionResult, String reactionName) {
        if (reactionResult == null || reactionName == null || reactionName.isBlank()) {
            return false;
        }
        if (reactionName.equals(reactionResult.getPrimaryReactionName())) {
            return true;
        }
        for (ReactionResult.ReactionStage stage : reactionResult.getReactionStages()) {
            if (stage != null && reactionName.equals(stage.getReactionName())) {
                return true;
            }
        }
        return false;
    }

    private double getFreezeBaseDecayPerSecond(Player player) {
        return Math.max(0.01, configParser.parseDouble("effects.freeze.base_decay_per_second", player, 0.4));
    }

    private double getFreezeDecayGrowthPerSecond(Player player) {
        return Math.max(0.0, configParser.parseDouble("effects.freeze.decay_growth_per_second", player, 0.1));
    }

    private double getFreezeDecayRecoveryPerSecond(Player player) {
        return Math.max(0.0, configParser.parseDouble("effects.freeze.decay_recovery_per_second", player, 0.2));
    }

    private double getFreezeShatterThreshold(Player player) {
        return Math.max(MIN_AURA_THRESHOLD, configParser.parseDouble("effects.freeze.shatter_threshold", player, 0.55));
    }

    private int computeFreezeDurationTicks(Player player, double totalAmount, double startRatePerSecond) {
        double safeAmount = Math.max(0.0, totalAmount);
        double safeRate = Math.max(getFreezeBaseDecayPerSecond(player), startRatePerSecond);
        if (safeAmount <= MIN_AURA_THRESHOLD) {
            return getFreezeDuration(player);
        }
        double durationSeconds = solveFreezeDecayDurationSeconds(
                safeAmount,
                safeRate,
                getFreezeDecayGrowthPerSecond(player)
        );
        return Math.max(1, (int) Math.ceil(durationSeconds * 20.0));
    }

    private double solveFreezeDecayDurationSeconds(double totalAmount, double startRatePerSecond, double growthPerSecond) {
        double safeAmount = Math.max(0.0, totalAmount);
        double safeRate = Math.max(0.0, startRatePerSecond);
        if (safeAmount <= MIN_AURA_THRESHOLD) {
            return 0.0;
        }
        if (growthPerSecond <= 0.000001) {
            return safeRate <= 0.000001 ? 0.0 : (safeAmount / safeRate);
        }
        double discriminant = (safeRate * safeRate) + (2.0 * growthPerSecond * safeAmount);
        return Math.max(0.0, (-safeRate + Math.sqrt(Math.max(0.0, discriminant))) / growthPerSecond);
    }

    private int getQuickenDurationTicks(Player player) {
        int fallback = configParser.parseInt("effects.element_duration", player, 200);
        return configParser.parseInt("effects.quicken_duration", player, fallback);
    }

    private String getElementTag(String element) {
        return switch (element) {
            case FIRE_KEY -> FIRE_TAG;
            case WATER_KEY -> WATER_TAG;
            case ICE_KEY -> ICE_TAG;
            case ELECTRO_KEY -> ELECTRO_TAG;
            case ANEMO_KEY -> ANEMO_TAG;
            case GEO_KEY -> GEO_TAG;
            case DENDRO_KEY -> DENDRO_TAG;
            case PlayerStats.PHYSICAL_KEY -> "§7【物理】";
            default -> "";
        };
    }

    private String getLegacyElementName(String element) {
        return switch (element) {
            case FIRE_KEY -> "火";
            case WATER_KEY -> "水";
            case ICE_KEY -> "冰";
            case ELECTRO_KEY -> "雷";
            case ANEMO_KEY -> "风";
            case GEO_KEY -> "岩";
            case DENDRO_KEY -> "草";
            default -> "未知";
        };
    }

    private String getElementName(String element) {
        return switch (element) {
            case FIRE_KEY -> "火";
            case WATER_KEY -> "水";
            case ICE_KEY -> "冰";
            case ELECTRO_KEY -> "雷";
            case ANEMO_KEY -> "风";
            case GEO_KEY -> "岩";
            case DENDRO_KEY -> "草";
            case PlayerStats.PHYSICAL_KEY -> "物理";
            default -> "未知";
        };
    }

    private double getMasteryBonus(Player player, PlayerStats stats) {
        if (!plugin.getConfig().getBoolean("element_mastery.enabled", true)) return 0;
        double mastery = stats.getElementMastery();
        double coefficient = configParser.parseDouble("element_mastery.coefficient", player, 2.78);
        double constant = configParser.parseDouble("element_mastery.constant", player, 1400);
        return (mastery * coefficient) / (mastery + constant);
    }

    /**
     * 新增：带反应类型的精通加成计算
     */
    private double getMasteryBonus(Player player, PlayerStats stats, String reactionType) {
        if (!plugin.getConfig().getBoolean("element_mastery.enabled", true)) return 0;

        double mastery = stats.getElementMastery();
        double coefficient = configParser.parseDouble("element_mastery.base_coefficient", player, 2.78);
        double constant = configParser.parseDouble("element_mastery.base_constant", player, 1400);

        // 基础精通加成
        double baseBonus = (mastery * coefficient) / (mastery + constant);

        // 如果配置了反应类型倍率，则应用
        String multiplierPath = "element_mastery.reaction_multipliers." + reactionType;
        if (plugin.getConfig().contains(multiplierPath)) {
            double reactionMultiplier = configParser.parseDouble(multiplierPath, player, 1.0);
            return baseBonus * reactionMultiplier;
        }

        return baseBonus;
    }

    /**
     * 获取反应类型 Key
     */
    private String getReactionTypeKey(String attackerElement, Map<String, Double> targetElements,
                                      boolean isFrozen, boolean isQuicken) {
        if (isFrozen && canTriggerShatter(attackerElement)) {
            return "shatter";
        }
        return resolveReactionPlan(attackerElement, targetElements, isFrozen, isQuicken).reactionType;
    }

    private double getTransformativeReactionDamage(Player attacker, String reactionKey,
                                                   String damageElement, double masteryBonus,
                                                   double fallbackBaseDamage) {
        double baseDamage = Math.max(0.0,
                configParser.parseDouble("reactions." + reactionKey, attacker, fallbackBaseDamage));
        double reactionDamage = baseDamage * (1.0 + Math.max(0.0, masteryBonus));

        if (plugin.shouldLogReactionDebug()) {
            plugin.getLogger().info(String.format(Locale.US,
                    "[反应伤害] %s -> base=%.3f, masteryBonus=%.3f, final=%.3f, element=%s",
                    reactionKey, baseDamage, masteryBonus, reactionDamage, damageElement));
        }
        return reactionDamage;
    }

    private double getAdditiveReactionDamage(Player attacker, String reactionKey,
                                             String damageElement, double masteryBonus,
                                             double fallbackBaseDamage) {
        double baseDamage = Math.max(0.0,
                configParser.parseDouble("reactions." + reactionKey, attacker, fallbackBaseDamage));
        double additiveDamage = baseDamage * (1.0 + Math.max(0.0, masteryBonus));

        if (plugin.shouldLogReactionDebug()) {
            plugin.getLogger().info(String.format(Locale.US,
                    "[反应附加] %s -> base=%.3f, masteryBonus=%.3f, final=%.3f, element=%s",
                    reactionKey, baseDamage, masteryBonus, additiveDamage, damageElement));
        }
        return additiveDamage;
    }

    private ReactionResult finalizeReaction(Player attacker, LivingEntity target, ReactionResult result) {
        if (target != null && result != null && result.hasReaction()) {
            markReactionTriggered(attacker, target, result);
        }
        return result;
    }

    public double applyTransformativeReactionDamage(Player attacker, LivingEntity target, ReactionResult reactionResult) {
        if (attacker == null || target == null || reactionResult == null || !reactionResult.hasTransformativeDamage()) {
            return 0.0;
        }
        return processTransformativeReactionDamage(attacker, target, reactionResult, true);
    }

    public double estimateAppliedTransformativeReactionDamage(Player attacker, LivingEntity target, ReactionResult reactionResult) {
        if (target == null || reactionResult == null || !reactionResult.hasTransformativeDamage()) {
            return 0.0;
        }
        return processTransformativeReactionDamage(attacker, target, reactionResult, false);
    }

    private String resolveTransformativeDamageElement(ReactionResult reactionResult, String preferredElement) {
        String damageElement = preferredElement;
        if ((damageElement == null || damageElement.isBlank()) && reactionResult != null) {
            damageElement = reactionResult.getTransformativeDamageElement();
        }
        if ((damageElement == null || damageElement.isBlank()) && reactionResult != null) {
            damageElement = reactionResult.getDamageElement();
        }
        if (damageElement == null || damageElement.isBlank()) {
            damageElement = PlayerStats.PHYSICAL_KEY;
        }
        return damageElement;
    }

    private double processTransformativeReactionDamage(Player attacker, LivingEntity target,
                                                       ReactionResult reactionResult, boolean applyDamage) {
        long now = System.currentTimeMillis();
        maybePruneReactionDamageWindows(now);
        Map<String, Integer> pendingHits = new HashMap<>();
        double totalDamage = 0.0;
        List<ReactionResult.TransformativeDamageEntry> damageEntries = reactionResult.getTransformativeDamageEntries();
        if (damageEntries.isEmpty()) {
            String damageElement = resolveTransformativeDamageElement(
                    reactionResult,
                    reactionResult.getTransformativeDamageElement()
            );
            String reactionDamageKey = resolveTransformativeReactionDamageKey(reactionResult, null);
            return processReactionDamageApplication(
                    attacker,
                    target,
                    reactionResult.getTransformativeDamage(),
                    damageElement,
                    reactionDamageKey,
                    now,
                    pendingHits,
                    applyDamage
            );
        }

        for (ReactionResult.TransformativeDamageEntry damageEntry : damageEntries) {
            if (damageEntry == null || damageEntry.getDamage() <= 0.0) {
                continue;
            }
            String damageElement = resolveTransformativeDamageElement(reactionResult, damageEntry.getElement());
            String reactionDamageKey = resolveTransformativeReactionDamageKey(reactionResult, damageEntry);
            totalDamage += processReactionDamageApplication(
                    attacker,
                    target,
                    damageEntry.getDamage(),
                    damageElement,
                    reactionDamageKey,
                    now,
                    pendingHits,
                    applyDamage
            );
        }
        return totalDamage;
    }

    private String resolveTransformativeReactionDamageKey(ReactionResult reactionResult,
                                                          ReactionResult.TransformativeDamageEntry damageEntry) {
        if (damageEntry != null && damageEntry.getReactionKey() != null && !damageEntry.getReactionKey().isBlank()) {
            return damageEntry.getReactionKey();
        }
        String reactionKey = getReactionCooldownKey(reactionResult);
        if ("swirl".equals(reactionKey)) {
            String swirlElement = damageEntry != null ? damageEntry.getElement() : reactionResult.getTransformativeDamageElement();
            return buildSwirlReactionDamageKey(swirlElement);
        }
        return reactionKey;
    }

    private double processReactionDamageApplication(Player attacker, LivingEntity target,
                                                    double damage, String damageElement,
                                                    String reactionDamageKey, long now,
                                                    Map<String, Integer> pendingHits,
                                                    boolean applyDamage) {
        if (target == null || damage <= 0.0) {
            return 0.0;
        }
        if (!reserveReactionDamageWindow(attacker, target, reactionDamageKey, now, pendingHits, applyDamage)) {
            return 0.0;
        }
        if (!applyDamage) {
            return plugin.applyMobResistance(target, damage, damageElement);
        }
        return dealStandaloneReactionDamage(attacker, target, damage, damageElement);
    }

    private boolean reserveReactionDamageWindow(Player attacker, LivingEntity target, String reactionDamageKey,
                                                long now, Map<String, Integer> pendingHits, boolean commit) {
        int maxHits = resolveReactionDamageWindowMaxHits(reactionDamageKey);
        if (maxHits == Integer.MAX_VALUE || target == null) {
            return true;
        }

        String normalizedKey = normalizeReactionDamageKey(reactionDamageKey);
        if (normalizedKey == null) {
            return true;
        }

        String windowId = buildReactionDamageWindowId(target, attacker, normalizedKey);
        int activeHits = pruneReactionDamageWindow(windowId, now);
        int reservedHits = pendingHits.getOrDefault(windowId, 0);
        if (activeHits + reservedHits >= maxHits) {
            return false;
        }

        pendingHits.put(windowId, reservedHits + 1);
        if (commit) {
            reactionDamageWindows.computeIfAbsent(windowId, ignored -> new ArrayDeque<>()).addLast(now);
        }
        return true;
    }

    private void maybePruneReactionDamageWindows(long now) {
        if (reactionDamageWindows.isEmpty()) {
            return;
        }
        if (now - lastReactionDamageWindowPruneAt < DEFAULT_TRANSFORMATIVE_DAMAGE_WINDOW_MS) {
            return;
        }
        lastReactionDamageWindowPruneAt = now;
        Iterator<Map.Entry<String, Deque<Long>>> iterator = reactionDamageWindows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = iterator.next();
            Deque<Long> timestamps = entry.getValue();
            pruneExpiredReactionDamageTimestamps(timestamps, now);
            if (timestamps == null || timestamps.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private int pruneReactionDamageWindow(String windowId, long now) {
        if (windowId == null || reactionDamageWindows.isEmpty()) {
            return 0;
        }
        Deque<Long> timestamps = reactionDamageWindows.get(windowId);
        if (timestamps == null || timestamps.isEmpty()) {
            reactionDamageWindows.remove(windowId);
            return 0;
        }
        pruneExpiredReactionDamageTimestamps(timestamps, now);
        if (timestamps.isEmpty()) {
            reactionDamageWindows.remove(windowId);
            return 0;
        }
        return timestamps.size();
    }

    private void pruneExpiredReactionDamageTimestamps(Deque<Long> timestamps, long now) {
        if (timestamps == null || timestamps.isEmpty()) {
            return;
        }
        long cutoff = now - DEFAULT_TRANSFORMATIVE_DAMAGE_WINDOW_MS;
        while (!timestamps.isEmpty()) {
            Long timestamp = timestamps.peekFirst();
            if (timestamp == null || timestamp <= cutoff) {
                timestamps.removeFirst();
                continue;
            }
            break;
        }
    }

    private int resolveReactionDamageWindowMaxHits(String reactionDamageKey) {
        String normalizedKey = normalizeReactionDamageKey(reactionDamageKey);
        if (normalizedKey == null) {
            return Integer.MAX_VALUE;
        }
        return switch (normalizedKey) {
            case "overload", "electro_charge" -> 1;
            case "superconduct", "shatter", "bloom", "hyperbloom", "burgeon", "swirl" -> 2;
            default -> normalizedKey.startsWith("swirl:") ? 2 : Integer.MAX_VALUE;
        };
    }

    private String normalizeReactionDamageKey(String reactionDamageKey) {
        if (reactionDamageKey == null || reactionDamageKey.isBlank()) {
            return null;
        }
        return reactionDamageKey.trim().toLowerCase(Locale.ROOT);
    }

    private String buildReactionDamageWindowId(LivingEntity target, Player attacker, String reactionDamageKey) {
        String attackerId = attacker == null ? "system" : attacker.getUniqueId().toString();
        return attackerId + ":" + target.getUniqueId() + ":" + reactionDamageKey;
    }

    private String buildSwirlReactionDamageKey(String elementKey) {
        String normalizedElement = elementKey == null || elementKey.isBlank()
                ? "unknown"
                : elementKey.trim().toLowerCase(Locale.ROOT);
        return "swirl:" + normalizedElement;
    }

    public double dealStandaloneReactionDamage(Player attacker, LivingEntity target, double damage, String damageElement) {
        if (target == null || damage <= 0.0) {
            return 0.0;
        }
        String resolvedElement = damageElement == null || damageElement.isBlank()
                ? PlayerStats.PHYSICAL_KEY
                : damageElement;
        return attacker == null
                ? dealElementalDamage(target, damage, resolvedElement)
                : dealElementalDamage(target, damage, attacker, resolvedElement);
    }

    public double dealStandaloneReactionDamage(Player attacker, LivingEntity target,
                                               double damage, String damageElement,
                                               String reactionDamageKey) {
        long now = System.currentTimeMillis();
        maybePruneReactionDamageWindows(now);
        return processReactionDamageApplication(
                attacker,
                target,
                damage,
                damageElement,
                reactionDamageKey,
                now,
                new HashMap<>(),
                true
        );
    }

    public double calculateDisplayedReactionDamage(ReactionResult reactionResult,
                                                   double preResistanceMainDamage,
                                                   double finalMainDamage,
                                                   double appliedTransformativeDamage) {
        if (reactionResult == null) {
            return 0.0;
        }

        double mainReactionDamage = reactionResult.getScalingDamageBonus() + reactionResult.getAdditiveDamageBonus();
        double displayedMainReaction = preResistanceMainDamage <= 0.0
                ? mainReactionDamage
                : mainReactionDamage * (finalMainDamage / preResistanceMainDamage);
        return Math.max(0.0, displayedMainReaction) + Math.max(0.0, appliedTransformativeDamage);
    }

    public void consumeReactionAuras(LivingEntity target, ReactionResult reactionResult, String attackerElement) {
        if (target == null || reactionResult == null || !reactionResult.hasReaction()) {
            return;
        }

        if (!reactionResult.getReactionStages().isEmpty()) {
            for (ReactionResult.ReactionStage stage : reactionResult.getReactionStages()) {
                if (stage == null || stage.isNoConsume()) {
                    continue;
                }
                for (String consumedElement : stage.getConsumedElements()) {
                    double consumeAmount = stage.getCustomConsumeAmount() >= 0.0
                            ? stage.getCustomConsumeAmount()
                            : resolveConsumeAmount(stage.getReactionName(), consumedElement, attackerElement);
                    if (consumeAmount > 0.0) {
                        consumeElementAura(target, consumedElement, consumeAmount);
                    }
                }
            }
            syncReactionStatuses(target);
            return;
        }

        if (reactionResult.isNoConsume()) {
            return;
        }
        for (String consumedElement : reactionResult.getConsumedElements()) {
            double consumeAmount = reactionResult.getCustomConsumeAmount() >= 0.0
                    ? reactionResult.getCustomConsumeAmount()
                    : resolveConsumeAmount(reactionResult, consumedElement, attackerElement);
            if (consumeAmount > 0.0) {
                consumeElementAura(target, consumedElement, consumeAmount);
            }
        }
        syncReactionStatuses(target);
    }

    public void applyTriggerAura(LivingEntity target, String attackerElement, ReactionResult reactionResult,
                                 int durationTicks, double defaultAmount) {
        if (target == null
                || attackerElement == null
                || attackerElement.isBlank()
                || PlayerStats.PHYSICAL_KEY.equals(attackerElement)) {
            return;
        }

        if (reactionResult != null && !reactionResult.shouldApplyTriggerAura()) {
            return;
        }

        double amount = reactionResult != null ? reactionResult.getTriggerAuraAmount() : defaultAmount;
        if (amount <= 0.0) {
            amount = defaultAmount;
        }
        addElementAura(target, attackerElement, amount, getScaledAuraDuration(durationTicks, amount));
    }

    public boolean shouldTriggerSeedReaction(LivingEntity target, String attackerElement, ReactionResult reactionResult) {
        if (target == null || reactionResult == null || attackerElement == null || attackerElement.isBlank()) {
            return false;
        }
        if (!ELECTRO_KEY.equals(attackerElement) && !FIRE_KEY.equals(attackerElement)) {
            return false;
        }
        return reactionResult.shouldApplyTriggerAura()
                && reactionResult.getTriggerAuraAmount() > 0.0
                && !isSeedExplosionImmune(target);
    }

    public double resolveConsumeAmount(ReactionResult reactionResult, String consumedElement, String attackerElement) {
        if (reactionResult == null) {
            return 0.0;
        }
        return resolveConsumeAmount(reactionResult.getPrimaryReactionName(), consumedElement, attackerElement);
    }

    public double resolveConsumeAmount(String reactionName, String consumedElement, String attackerElement) {
        if (reactionName == null || reactionName.isBlank()) {
            return 0.0;
        }
        return switch (reactionName) {
            case "蒸发", "融化" -> Objects.equals(consumedElement, attackerElement) ? 1.0 : 0.5;
            case "冻结" -> 0.6;
            case "感电", "燃烧" -> 0.4;
            case "原激化" -> 0.3;
            case "结晶", "扩散", "绽放" -> 0.5;
            case "超载", "超导", "碎冰" -> 1.0;
            default -> 0.5;
        };
    }

    public void clearAllRuntimeState() {
        cancelTasks(bloomSeedTasks.values());
        cancelTasks(expireTasks.values());
        cancelTasks(shieldExpireTasks.values());
        cancelTasks(electroChargeTasks.values());
        cancelTasks(burningTasks.values());

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
                    armorStand.remove();
                    continue;
                }
                if (entity instanceof Item item && crystalChipMap.containsKey(item.getUniqueId())) {
                    item.remove();
                }
            }
        }

        bloomSeeds.clear();
        bloomSeedTasks.clear();
        playerShields.clear();
        crystalChipMap.clear();
        expireTasks.clear();
        shieldExpireTasks.clear();
        reactionCooldowns.clear();
        electroChargeTasks.clear();
        burningTasks.clear();
        frozenDecayStates.clear();
        attachmentIcdStates.clear();
        reactionDamageWindows.clear();
        lastReactionDamageWindowPruneAt = 0L;
        ElementAura.clearAll();
    }

    private boolean shouldThrottleReaction(LivingEntity target, String attackerElement, String reactionType, boolean isFrozen) {
        if (target == null || attackerElement == null) {
            return false;
        }
        if (isFrozen && canTriggerShatter(attackerElement)) {
            return true;
        }
        if (PlayerStats.PHYSICAL_KEY.equals(attackerElement)) {
            return false;
        }
        return (isFrozen && !ICE_KEY.equals(attackerElement))
                || !"default".equals(reactionType);
    }

    private boolean isReactionOnCooldown(LivingEntity target, Player attacker, String reactionCooldownKey) {
        if (target == null || reactionCooldownKey == null) {
            return false;
        }
        long cooldownMs = resolveReactionCooldownMs(attacker, reactionCooldownKey);
        if (cooldownMs <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        pruneExpiredReactionCooldowns(now);
        String cooldownId = buildReactionCooldownId(target, attacker, reactionCooldownKey);
        Long nextAllowedAt = reactionCooldowns.get(cooldownId);
        if (nextAllowedAt == null) {
            return false;
        }
        if (nextAllowedAt <= now) {
            reactionCooldowns.remove(cooldownId);
            return false;
        }
        return true;
    }

    private void pruneExpiredReactionCooldowns(long now) {
        if (reactionCooldowns.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Long>> iterator = reactionCooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            Long expiresAt = entry.getValue();
            if (expiresAt == null || expiresAt <= now) {
                iterator.remove();
            }
        }
    }

    private void markReactionTriggered(Player attacker, LivingEntity target, ReactionResult result) {
        if (target == null || result == null || !result.hasReaction()) {
            return;
        }
        Set<String> cooldownKeys = new LinkedHashSet<>();
        if (!result.getReactionStages().isEmpty()) {
            for (ReactionResult.ReactionStage stage : result.getReactionStages()) {
                String reactionCooldownKey = getReactionCooldownKeyFromName(stage.getReactionName());
                if (reactionCooldownKey != null) {
                    cooldownKeys.add(reactionCooldownKey);
                }
            }
        } else {
            String reactionCooldownKey = getReactionCooldownKey(result);
            if (reactionCooldownKey != null) {
                cooldownKeys.add(reactionCooldownKey);
            }
        }

        for (String reactionCooldownKey : cooldownKeys) {
            long cooldownMs = resolveReactionCooldownMs(attacker, reactionCooldownKey);
            String cooldownId = buildReactionCooldownId(target, attacker, reactionCooldownKey);
            if (cooldownMs <= 0L) {
                reactionCooldowns.remove(cooldownId);
                continue;
            }
            reactionCooldowns.put(cooldownId, System.currentTimeMillis() + cooldownMs);
        }
    }

    private long resolveReactionCooldownMs(Player attacker, String reactionCooldownKey) {
        int globalCooldownMs = Math.max(0, configParser.parseInt("reactions.cooldown-ms", attacker, 500));
        if (reactionCooldownKey == null || reactionCooldownKey.isBlank()) {
            return globalCooldownMs;
        }
        return Math.max(0L,
                configParser.parseInt("reactions.cooldown-overrides." + reactionCooldownKey, attacker, globalCooldownMs));
    }

    private String getReactionCooldownKey(String attackerElement, String reactionType, boolean isFrozen) {
        if (isFrozen && canTriggerShatter(attackerElement)) {
            return "shatter";
        }
        if ("default".equals(reactionType)) {
            return null;
        }
        return reactionType;
    }

    private String getReactionCooldownKey(ReactionResult result) {
        if (result == null || !result.hasReaction()) {
            return null;
        }
        String reactionName = result.getPrimaryReactionName();
        if (reactionName == null || reactionName.isBlank()) {
            return null;
        }
        return getReactionCooldownKeyFromName(reactionName);
    }

    private String getReactionCooldownKeyFromName(String reactionName) {
        if (reactionName == null || reactionName.isBlank()) {
            return null;
        }
        return switch (reactionName) {
            case "蒸发" -> "vaporize";
            case "融化" -> "melt";
            case "超载" -> "overload";
            case "感电" -> "electro_charge";
            case "超导" -> "superconduct";
            case "冻结" -> "freeze";
            case "碎冰" -> "shatter";
            case "原激化" -> "quicken";
            case "超激化" -> "aggravate";
            case "蔓激化" -> "spread";
            case "绽放" -> "bloom";
            case "燃烧" -> "burning";
            case "结晶" -> "crystal_shield";
            case "扩散" -> "swirl";
            default -> null;
        };
    }

    private String buildReactionCooldownId(LivingEntity target, Player attacker, String reactionCooldownKey) {
        String attackerKey = attacker == null ? "global" : attacker.getUniqueId().toString();
        return target.getUniqueId() + ":" + attackerKey + ":" + reactionCooldownKey;
    }

    /**
     * 消耗元素量（使用 ElementAura）
     */
    private void consumeElementAura(LivingEntity target, String element, double amount) {
        if (target == null || element == null || element.isBlank() || amount <= 0.0) {
            return;
        }
        ElementAura aura = new ElementAura(target, plugin);
        double previousEffectiveAmount = getEffectiveElementAmount(aura, element);
        aura.consumeElement(element, amount);
        updateCompositeStatusElementProfiles(aura, element, Math.max(0.0, previousEffectiveAmount - amount));
    }

    private void syncReactionStatuses(LivingEntity target) {
        if (target == null) {
            return;
        }

        ElementAura aura = new ElementAura(target, plugin);
        syncFrozenProfileWithState(target, aura);
        if (aura.hasStatus(FROZEN_KEY) && !isCompositeStatusStillValid(aura, FROZEN_KEY)) {
            clearFrozenState(target, aura);
        }
        if (aura.hasStatus(ELECTRO_CHARGED_KEY) && !isCompositeStatusStillValid(aura, ELECTRO_CHARGED_KEY)) {
            clearCompositeStatus(target, aura, ELECTRO_CHARGED_KEY);
        }
        if (aura.hasStatus(BURNING_KEY) && !isCompositeStatusStillValid(aura, BURNING_KEY)) {
            clearCompositeStatus(target, aura, BURNING_KEY);
        }
        if (aura.hasStatus(QUICKEN_KEY) && !isCompositeStatusStillValid(aura, QUICKEN_KEY)) {
            clearCompositeStatus(target, aura, QUICKEN_KEY);
        }
    }

    private void clearFrozenState(LivingEntity target, ElementAura aura) {
        transitionFrozenStateToThawing(target == null ? null : target.getUniqueId());
        clearCompositeStatus(target, aura, FROZEN_KEY);
    }

    private void syncFrozenProfileWithState(LivingEntity target, ElementAura aura) {
        if (target == null || aura == null || !aura.hasStatus(FROZEN_KEY)) {
            return;
        }

        long now = System.currentTimeMillis();
        FrozenDecayState state = resolveFrozenDecayState(target.getUniqueId(), now);
        if (state == null) {
            state = rebuildFrozenStateFromCurrentAuras(target, aura, now);
        }
        if (state == null || !state.active
                || state.waterAmount <= MIN_AURA_THRESHOLD
                || state.iceAmount <= MIN_AURA_THRESHOLD) {
            clearFrozenState(target, aura);
            return;
        }

        Map<String, Double> profile = new LinkedHashMap<>();
        profile.put(WATER_KEY, state.waterAmount);
        profile.put(ICE_KEY, state.iceAmount);
        int durationTicks = computeFreezeDurationTicks(null, state.getTotalAmount(), state.decayRatePerSecond);
        aura.replaceStatusProfile(FROZEN_KEY, durationTicks, profile);
        aura.removeElement(WATER_KEY);
        aura.removeElement(ICE_KEY);
    }

    private FrozenDecayState rebuildFrozenStateFromCurrentAuras(LivingEntity target, ElementAura aura, long now) {
        if (target == null || aura == null || !aura.hasStatus(FROZEN_KEY)) {
            return null;
        }

        FrozenDecayState existing = resolveFrozenDecayState(target.getUniqueId(), now);
        double baseRate = getFreezeBaseDecayPerSecond(null);
        double startRate = existing == null ? baseRate : Math.max(baseRate, existing.decayRatePerSecond);
        double waterAmount = Math.max(0.0, getEffectiveElementAmount(aura, WATER_KEY));
        double iceAmount = Math.max(0.0, getEffectiveElementAmount(aura, ICE_KEY));
        if (waterAmount <= MIN_AURA_THRESHOLD || iceAmount <= MIN_AURA_THRESHOLD) {
            return null;
        }

        FrozenDecayState updated = existing == null ? new FrozenDecayState() : existing;
        updated.waterAmount = waterAmount;
        updated.iceAmount = iceAmount;
        updated.decayRatePerSecond = startRate;
        updated.lastUpdatedAtMillis = now;
        updated.active = true;
        frozenDecayStates.put(target.getUniqueId(), updated);
        return updated;
    }

    private FrozenDecayState resolveFrozenDecayState(UUID targetId, long now) {
        if (targetId == null) {
            return null;
        }
        FrozenDecayState state = frozenDecayStates.get(targetId);
        if (state == null) {
            return null;
        }
        if (state.lastUpdatedAtMillis <= 0L) {
            state.lastUpdatedAtMillis = now;
            return state;
        }

        double elapsedSeconds = Math.max(0.0, (now - state.lastUpdatedAtMillis) / 1000.0);
        if (elapsedSeconds <= 0.0) {
            return state;
        }

        double baseRate = getFreezeBaseDecayPerSecond(null);
        double growthPerSecond = getFreezeDecayGrowthPerSecond(null);
        double recoveryPerSecond = getFreezeDecayRecoveryPerSecond(null);
        if (state.active && state.getTotalAmount() > MIN_AURA_THRESHOLD) {
            double totalAmount = state.getTotalAmount();
            double startRate = Math.max(baseRate, state.decayRatePerSecond);
            double depletionTime = solveFreezeDecayDurationSeconds(totalAmount, startRate, growthPerSecond);
            if (elapsedSeconds < depletionTime) {
                double consumedAmount = (startRate * elapsedSeconds) + (0.5 * growthPerSecond * elapsedSeconds * elapsedSeconds);
                scaleFrozenCarrierAmounts(state, Math.max(0.0, totalAmount - consumedAmount));
                state.decayRatePerSecond = startRate + (growthPerSecond * elapsedSeconds);
                state.lastUpdatedAtMillis = now;
                return state;
            }

            double rateAtDepletion = startRate + (growthPerSecond * depletionTime);
            scaleFrozenCarrierAmounts(state, 0.0);
            state.active = false;
            state.decayRatePerSecond = Math.max(baseRate, rateAtDepletion - (recoveryPerSecond * (elapsedSeconds - depletionTime)));
        } else {
            state.active = false;
            scaleFrozenCarrierAmounts(state, 0.0);
            state.decayRatePerSecond = Math.max(baseRate, state.decayRatePerSecond - (recoveryPerSecond * elapsedSeconds));
        }
        state.lastUpdatedAtMillis = now;
        return removeFrozenStateIfRecovered(targetId, state, baseRate);
    }

    private void transitionFrozenStateToThawing(UUID targetId) {
        if (targetId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        FrozenDecayState state = resolveFrozenDecayState(targetId, now);
        if (state == null) {
            return;
        }
        state.active = false;
        scaleFrozenCarrierAmounts(state, 0.0);
        state.lastUpdatedAtMillis = now;
        removeFrozenStateIfRecovered(targetId, state, getFreezeBaseDecayPerSecond(null));
    }

    private FrozenDecayState removeFrozenStateIfRecovered(UUID targetId, FrozenDecayState state, double baseRate) {
        if (targetId == null || state == null) {
            return null;
        }
        if (!state.active
                && state.getTotalAmount() <= MIN_AURA_THRESHOLD
                && state.decayRatePerSecond <= baseRate + 0.000001) {
            frozenDecayStates.remove(targetId);
            return null;
        }
        return state;
    }

    private void scaleFrozenCarrierAmounts(FrozenDecayState state, double newTotalAmount) {
        if (state == null) {
            return;
        }
        double oldTotal = state.getTotalAmount();
        double safeNewTotal = Math.max(0.0, newTotalAmount);
        if (safeNewTotal <= MIN_AURA_THRESHOLD || oldTotal <= MIN_AURA_THRESHOLD) {
            state.waterAmount = 0.0;
            state.iceAmount = 0.0;
            return;
        }
        double scale = safeNewTotal / oldTotal;
        state.waterAmount = Math.max(0.0, state.waterAmount * scale);
        state.iceAmount = Math.max(0.0, state.iceAmount * scale);
    }

    /**
     * 添加元素量（使用 ElementAura）
     */
    private void addElementAura(LivingEntity target, String element, double amount, int duration) {
        if (target == null || element == null || element.isBlank() || amount <= 0.0 || duration <= 0) {
            return;
        }
        ElementAura aura = new ElementAura(target, plugin);
        double previousEffectiveAmount = getEffectiveElementAmount(aura, element);
        aura.addElement(element, amount, duration);
        updateCompositeStatusElementProfiles(aura, element, Math.min(4.0, previousEffectiveAmount + amount));
    }

    public void postProcessReactionState(LivingEntity target, ReactionResult reactionResult) {
        if (target == null || reactionResult == null || !reactionResult.hasReaction()) {
            return;
        }

        hydrateCompositeStatusProfiles(target, reactionResult);
        if (containsReactionStage(reactionResult, "鍐荤粨")) {
            rebuildFrozenStateFromCurrentAuras(target, new ElementAura(target, plugin), System.currentTimeMillis());
        }

        Set<String> statusKeys = new LinkedHashSet<>();
        if (!reactionResult.getReactionStages().isEmpty()) {
            for (ReactionResult.ReactionStage stage : reactionResult.getReactionStages()) {
                String statusKey = getCompositeStatusKeyForReaction(stage.getReactionName());
                if (statusKey != null) {
                    statusKeys.add(statusKey);
                }
            }
        } else {
            String statusKey = getCompositeStatusKeyForReaction(reactionResult.getPrimaryReactionName());
            if (statusKey != null) {
                statusKeys.add(statusKey);
            }
        }

        for (String statusKey : statusKeys) {
            refreshCompositeStatusProfile(target, statusKey);
        }
        syncReactionStatuses(target);
    }

    private void hydrateCompositeStatusProfiles(LivingEntity target, ReactionResult reactionResult) {
        if (target == null || reactionResult == null) {
            return;
        }

        if (!reactionResult.getReactionStages().isEmpty()) {
            for (ReactionResult.ReactionStage stage : reactionResult.getReactionStages()) {
                hydrateCompositeStatusProfile(target, reactionResult, stage);
            }
            return;
        }

        String statusKey = getCompositeStatusKeyForReaction(reactionResult.getPrimaryReactionName());
        if (statusKey == null) {
            return;
        }
        hydrateCompositeStatusProfile(
                target,
                statusKey,
                reactionResult.getDamageElement(),
                firstConsumedElement(reactionResult.getConsumedElements()),
                reactionResult.getTriggerAuraAmount(),
                reactionResult.getCustomConsumeAmount()
        );
    }

    private void hydrateCompositeStatusProfile(LivingEntity target, ReactionResult reactionResult,
                                               ReactionResult.ReactionStage stage) {
        if (target == null || reactionResult == null || stage == null) {
            return;
        }
        String statusKey = getCompositeStatusKeyForReaction(stage.getReactionName());
        if (statusKey == null) {
            return;
        }
        hydrateCompositeStatusProfile(
                target,
                statusKey,
                reactionResult.getDamageElement(),
                firstConsumedElement(stage.getConsumedElements()),
                reactionResult.getTriggerAuraAmount(),
                stage.getCustomConsumeAmount()
        );
    }

    private void hydrateCompositeStatusProfile(LivingEntity target, String statusKey,
                                               String attackerElement, String consumedElement,
                                               double triggerAuraAmount, double consumeAmount) {
        if (target == null || statusKey == null || statusKey.isBlank()) {
            return;
        }

        ElementAura aura = new ElementAura(target, plugin);
        if (!aura.hasStatus(statusKey)) {
            return;
        }

        String[] carriers = getCompositeStatusCarrierElements(statusKey);
        if (carriers.length == 0) {
            return;
        }

        double fallbackAmount = Math.max(
                MIN_AURA_THRESHOLD * 2.0,
                Math.max(Math.max(0.0, triggerAuraAmount), Math.min(Math.max(0.0, consumeAmount), 0.4))
        );

        Map<String, Double> profile = new LinkedHashMap<>();
        for (String carrierElement : carriers) {
            double amount = Math.max(
                    aura.getStatusProfileAmount(statusKey, carrierElement),
                    getEffectiveElementAmount(aura, carrierElement)
            );
            if (amount <= MIN_AURA_THRESHOLD
                    && (Objects.equals(carrierElement, attackerElement) || Objects.equals(carrierElement, consumedElement))) {
                amount = fallbackAmount;
            }
            if (amount > MIN_AURA_THRESHOLD) {
                profile.put(carrierElement, amount);
            }
        }

        if (profile.size() < carriers.length) {
            return;
        }

        int durationTicks = Math.max(1, (int) Math.ceil(aura.getRemainingStatusMillis(statusKey) / 50.0));
        aura.setStatusProfile(statusKey, durationTicks, profile);
    }

    private String firstConsumedElement(List<String> consumedElements) {
        if (consumedElements == null) {
            return null;
        }
        for (String consumedElement : consumedElements) {
            if (consumedElement != null && !consumedElement.isBlank()) {
                return consumedElement;
            }
        }
        return null;
    }

    private String getCompositeStatusKeyForReaction(String reactionName) {
        if (reactionName == null || reactionName.isBlank()) {
            return null;
        }
        return switch (reactionName) {
            case "冻结" -> FROZEN_KEY;
            case "感电" -> ELECTRO_CHARGED_KEY;
            case "燃烧" -> BURNING_KEY;
            case "原激化", "超激化", "蔓激化" -> QUICKEN_KEY;
            default -> null;
        };
    }

    private boolean isCompositeStatusStillValid(ElementAura aura, String statusKey) {
        if (aura == null || statusKey == null || statusKey.isBlank() || !aura.hasStatus(statusKey)) {
            return false;
        }
        for (String carrierElement : getCompositeStatusCarrierElements(statusKey)) {
            if (getEffectiveElementAmount(aura, carrierElement) <= MIN_AURA_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    private void refreshCompositeStatusProfile(LivingEntity target, String statusKey) {
        if (target == null || statusKey == null || statusKey.isBlank()) {
            return;
        }
        ElementAura aura = new ElementAura(target, plugin);
        if (!aura.hasStatus(statusKey)) {
            return;
        }

        Map<String, Double> profile = new LinkedHashMap<>();
        for (String carrierElement : getCompositeStatusCarrierElements(statusKey)) {
            double amount = Math.max(
                    aura.getStatusProfileAmount(statusKey, carrierElement),
                    getEffectiveElementAmount(aura, carrierElement)
            );
            if (amount > MIN_AURA_THRESHOLD) {
                profile.put(carrierElement, amount);
            }
        }

        if (profile.size() < getCompositeStatusCarrierElements(statusKey).length) {
            clearCompositeStatus(target, aura, statusKey);
            return;
        }

        int durationTicks = Math.max(1, (int) Math.ceil(aura.getRemainingStatusMillis(statusKey) / 50.0));
        aura.setStatusProfile(statusKey, durationTicks, profile);
    }

    private void updateCompositeStatusElementProfiles(ElementAura aura, String elementKey, double newAmount) {
        if (aura == null || elementKey == null || elementKey.isBlank()) {
            return;
        }
        for (String statusKey : getCompositeStatusKeys()) {
            if (aura.hasStatus(statusKey) && compositeStatusCarriesElement(statusKey, elementKey)) {
                aura.setStatusProfileAmount(statusKey, elementKey, newAmount);
            }
        }
    }

    private void clearCompositeStatus(LivingEntity target, ElementAura aura, String statusKey) {
        if (statusKey == null || statusKey.isBlank()) {
            return;
        }
        ElementAura resolvedAura = aura != null ? aura : new ElementAura(target, plugin);
        Map<String, Double> profile = resolvedAura.getStatusProfile(statusKey);
        long remainingStatusMillis = resolvedAura.getRemainingStatusMillis(statusKey);
        resolvedAura.removeStatus(statusKey);

        if (!profile.isEmpty()) {
            int spillBaseDuration = Math.max(1, (int) Math.ceil(Math.max(remainingStatusMillis, 0L) / 50.0));
            for (Map.Entry<String, Double> entry : profile.entrySet()) {
                String elementKey = entry.getKey();
                double hiddenAmount = Math.max(0.0, entry.getValue());
                double visibleAmount = resolvedAura.getElementAmount(elementKey);
                double topUpAmount = hiddenAmount - visibleAmount;
                if (topUpAmount > MIN_AURA_THRESHOLD) {
                    resolvedAura.addElement(elementKey, topUpAmount, getScaledAuraDuration(spillBaseDuration, topUpAmount));
                }
            }
        }

        if (Objects.equals(statusKey, FROZEN_KEY) && SLOWNESS != null && target != null) {
            target.removePotionEffect(SLOWNESS);
        }
    }

    private void cancelTasks(Collection<BukkitTask> tasks) {
        if (tasks == null) {
            return;
        }
        for (BukkitTask task : new ArrayList<>(tasks)) {
            if (task != null) {
                task.cancel();
            }
        }
    }

    private double resolveTriggerAuraAmount(Player attacker, LivingEntity target, String attackerElement,
                                            AttachmentProfile attachmentProfile) {
        AttachmentProfile resolvedProfile = attachmentProfile == null
                ? new AttachmentProfile("default", 0.0)
                : attachmentProfile;
        double desiredTriggerAuraAmount = resolvedProfile.getAuraAmount();
        if (PlayerStats.PHYSICAL_KEY.equals(attackerElement) || desiredTriggerAuraAmount <= 0.0) {
            return 0.0;
        }

        boolean icdEnabledFallback = plugin.getConfig().getBoolean("attachment.standard-icd.enabled", true);
        if (!resolvedProfile.resolveStandardIcdEnabled(icdEnabledFallback)) {
            return desiredTriggerAuraAmount;
        }

        long defaultWindowMs = Math.max(1L, configParser.parseInt("attachment.standard-icd.window-ms", attacker, 2500));
        int defaultHitsPerCycle = Math.max(1, configParser.parseInt("attachment.standard-icd.hits", attacker, 3));
        int defaultHitOffset = Math.max(0, configParser.parseInt("attachment.standard-icd.offset", attacker, 0));
        long windowMs = Math.max(1L, resolvedProfile.resolveStandardIcdWindowMs(defaultWindowMs));
        int hitsPerCycle = Math.max(1, resolvedProfile.resolveStandardIcdHits(defaultHitsPerCycle));
        int hitOffset = Math.floorMod(resolvedProfile.resolveStandardIcdOffset(defaultHitOffset), hitsPerCycle);
        String sourceKey = resolvedProfile.getSourceKey();
        String icdKey = attacker.getUniqueId() + ":" + target.getUniqueId() + ":" + attackerElement + ":" + sourceKey;

        long now = System.currentTimeMillis();
        pruneExpiredAttachmentIcdStates(now, windowMs);
        AttachmentIcdState state = attachmentIcdStates.computeIfAbsent(icdKey, ignored -> new AttachmentIcdState());
        if (state.windowStartedAt <= 0L || now - state.windowStartedAt >= windowMs) {
            state.windowStartedAt = now;
            state.windowDurationMs = windowMs;
            state.hitsInWindow = 0;
        }
        state.windowDurationMs = windowMs;

        boolean appliesAura = Math.floorMod(state.hitsInWindow + hitOffset, hitsPerCycle) == 0;
        state.hitsInWindow++;

        if (plugin.shouldLogReactionDebug()) {
            plugin.getLogger().info(String.format(Locale.US,
                    "[附着ICD] source=%s element=%s count=%d/%d offset=%d applied=%s amount=%.2f",
                    sourceKey, attackerElement, state.hitsInWindow, hitsPerCycle, hitOffset, appliesAura, desiredTriggerAuraAmount));
        }

        return appliesAura ? desiredTriggerAuraAmount : 0.0;
    }

    private void pruneExpiredAttachmentIcdStates(long now, long windowMs) {
        if (attachmentIcdStates.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, AttachmentIcdState>> iterator = attachmentIcdStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AttachmentIcdState> entry = iterator.next();
            AttachmentIcdState state = entry.getValue();
            long effectiveWindowMs = state == null ? 0L : Math.max(1L, state.windowDurationMs);
            if (state == null || state.windowStartedAt <= 0L || now - state.windowStartedAt >= effectiveWindowMs) {
                iterator.remove();
            }
        }
    }

    private int getScaledAuraDuration(int baseDurationTicks, double auraAmount) {
        int safeBaseDuration = Math.max(1, baseDurationTicks);
        double multiplier = resolveAuraDurationMultiplier(auraAmount);
        return Math.max(1, (int) Math.round(safeBaseDuration * multiplier));
    }

    private double resolveAuraDurationMultiplier(double auraAmount) {
        double safeAuraAmount = Math.max(0.0, auraAmount);
        double weakMax = Math.max(0.1, configParser.parseDouble("attachment.aura.duration-tier.weak-max", null, 0.5));
        double normalMax = Math.max(weakMax, configParser.parseDouble("attachment.aura.duration-tier.normal-max", null, 1.0));
        double strongMax = Math.max(normalMax, configParser.parseDouble("attachment.aura.duration-tier.strong-max", null, 2.0));

        if (safeAuraAmount <= weakMax) {
            return Math.max(0.25, configParser.parseDouble("attachment.aura.duration-tier.weak-multiplier", null, 0.8));
        }
        if (safeAuraAmount <= normalMax) {
            return Math.max(0.25, configParser.parseDouble("attachment.aura.duration-tier.normal-multiplier", null, 1.0));
        }
        if (safeAuraAmount <= strongMax) {
            return Math.max(0.25, configParser.parseDouble("attachment.aura.duration-tier.strong-multiplier", null, 1.65));
        }
        return Math.max(0.25, configParser.parseDouble("attachment.aura.duration-tier.massive-multiplier", null, 2.4));
    }

    private void spawnEffect(LivingEntity target, Particle particle, Sound sound) {
        target.getWorld().spawnParticle(particle, target.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
        target.getWorld().playSound(target.getLocation(), sound, 1.0f, 1.0f);
    }

    private void startElectroChargeTask(Player attacker, LivingEntity target, int duration, int interval,
                                        double damagePerTick, boolean allowPropagation) {
        UUID targetId = target.getUniqueId();
        BukkitTask existingTask = electroChargeTasks.remove(targetId);
        if (existingTask != null) {
            existingTask.cancel();
        }

        int safeInterval = Math.max(1, interval);
        double waterConsumePerTick = Math.max(0.0, configParser.parseDouble("effects.electro_charge_tick_consume_water", attacker, 0.4));
        double electroConsumePerTick = Math.max(0.0, configParser.parseDouble("effects.electro_charge_tick_consume_electro", attacker, 0.4));
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (target.isDead() || !target.isValid()) {
                    clearCompositeStatus(target, new ElementAura(target, plugin), ELECTRO_CHARGED_KEY);
                    electroChargeTasks.remove(targetId);
                    cancel();
                    return;
                }

                ElementAura aura = new ElementAura(target, plugin);
                if (!aura.hasStatus(ELECTRO_CHARGED_KEY)
                        || !isCompositeStatusStillValid(aura, ELECTRO_CHARGED_KEY)) {
                    clearCompositeStatus(target, aura, ELECTRO_CHARGED_KEY);
                    electroChargeTasks.remove(targetId);
                    cancel();
                    return;
                }

                dealStandaloneReactionDamage(attacker, target, damagePerTick, ELECTRO_KEY, "electro_charge");
                consumeElementAura(target, WATER_KEY, waterConsumePerTick);
                consumeElementAura(target, ELECTRO_KEY, electroConsumePerTick);
                if (allowPropagation) {
                    propagateElectroCharge(attacker, target, safeInterval, damagePerTick);
                }
                syncReactionStatuses(target);
                target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        target.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.05);
            }
        }.runTaskTimer(plugin, safeInterval, safeInterval);
        electroChargeTasks.put(targetId, task);
    }

    private void propagateElectroCharge(Player attacker, LivingEntity primaryTarget, int interval, double primaryDamagePerTick) {
        if (attacker == null || primaryTarget == null || !primaryTarget.isValid() || primaryTarget.isDead()) {
            return;
        }

        double jumpRange = Math.max(0.0, configParser.parseDouble("effects.electro_charge_jump_range", attacker, 13.0));
        if (jumpRange <= 0.0) {
            return;
        }
        double jumpHeight = Math.max(0.0, configParser.parseDouble("effects.electro_charge_jump_height", attacker, 3.0));

        int maxSecondaryTargets = Math.max(0, configParser.parseInt("effects.electro_charge_max_secondary_targets", attacker, 0));

        double secondaryDamageMultiplier = Math.max(0.0, configParser.parseDouble("effects.electro_charge_secondary_damage_multiplier", attacker, 1.0));
        double transferAuraAmount = Math.max(0.0, configParser.parseDouble("effects.electro_charge_transfer_aura", attacker, 0.2));
        int secondaryDuration = Math.max(interval, configParser.parseInt("effects.electro_charge_secondary_duration", attacker, 60));
        double secondaryDamage = Math.max(0.0, primaryDamagePerTick * secondaryDamageMultiplier);
        if (secondaryDamage <= 0.0) {
            return;
        }

        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity entity : primaryTarget.getWorld().getNearbyEntities(primaryTarget.getLocation(), jumpRange, jumpHeight, jumpRange)) {
            if (!(entity instanceof LivingEntity living)
                    || !isValidElectroChargeSecondaryTarget(attacker, primaryTarget, living, jumpRange, jumpHeight)) {
                continue;
            }
            candidates.add(living);
        }
        candidates.sort(Comparator.comparingDouble(living -> living.getLocation().distanceSquared(primaryTarget.getLocation())));

        int appliedTargets = 0;
        for (LivingEntity living : candidates) {
            if (maxSecondaryTargets > 0 && appliedTargets >= maxSecondaryTargets) {
                break;
            }

            ElementAura secondaryAura = new ElementAura(living, plugin);
            boolean hasWetAura = getEffectiveElementAmount(secondaryAura, WATER_KEY) > MIN_AURA_THRESHOLD;
            boolean hasElectroCharge = secondaryAura.hasStatus(ELECTRO_CHARGED_KEY);
            boolean hasElectroAura = getEffectiveElementAmount(secondaryAura, ELECTRO_KEY) > MIN_AURA_THRESHOLD;
            boolean canSustainElectroCharge = hasWetAura || hasElectroCharge;
            if (!canSustainElectroCharge) {
                continue;
            }

            dealStandaloneReactionDamage(attacker, living, secondaryDamage, ELECTRO_KEY, "electro_charge");
            if (hasWetAura) {
                addElementAura(living, ELECTRO_KEY, transferAuraAmount, getScaledAuraDuration(secondaryDuration, transferAuraAmount));
            }
            secondaryAura.setStatus(ELECTRO_CHARGED_KEY, secondaryDuration);
            refreshCompositeStatusProfile(living, ELECTRO_CHARGED_KEY);
            if (!electroChargeTasks.containsKey(living.getUniqueId())
                    && (hasWetAura || hasElectroAura || hasElectroCharge)
                    && isCompositeStatusStillValid(secondaryAura, ELECTRO_CHARGED_KEY)) {
                startElectroChargeTask(attacker, living, secondaryDuration, interval, secondaryDamage, false);
            }
            living.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                    living.getLocation().add(0, 1, 0), 4, 0.25, 0.25, 0.25, 0.03);
            appliedTargets++;
        }
    }

    private boolean isValidElectroChargeSecondaryTarget(Player attacker, LivingEntity primaryTarget,
                                                        LivingEntity target, double horizontalRange, double verticalRange) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target.equals(primaryTarget) || target.equals(attacker)) {
            return false;
        }
        if (target instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
            return false;
        }
        if (primaryTarget == null || target.getWorld() != primaryTarget.getWorld()) {
            return false;
        }
        Location primaryLoc = primaryTarget.getLocation();
        Location targetLoc = target.getLocation();
        if (Math.abs(targetLoc.getY() - primaryLoc.getY()) > verticalRange) {
            return false;
        }
        double dx = targetLoc.getX() - primaryLoc.getX();
        double dz = targetLoc.getZ() - primaryLoc.getZ();
        return dx * dx + dz * dz <= horizontalRange * horizontalRange;
    }

    private void startBurningTask(Player attacker, LivingEntity target, int duration, int interval, double damagePerTick) {
        UUID targetId = target.getUniqueId();
        BukkitTask existingTask = burningTasks.remove(targetId);
        if (existingTask != null) {
            existingTask.cancel();
        }

        int safeInterval = Math.max(1, interval);
        int maxTicks = Math.max(safeInterval, duration);
        double dendroConsumePerTick = Math.max(0.0, configParser.parseDouble("effects.burning_tick_consume_dendro", attacker, 0.08));
        double pyroRefreshPerTick = Math.max(0.0, configParser.parseDouble("effects.burning_tick_refresh_pyro", attacker, 0.10));
        double spreadRange = Math.max(0.0, configParser.parseDouble("effects.burning_damage_range", attacker, 1.0));
        double spreadAuraAmount = Math.max(0.0, configParser.parseDouble("effects.burning_spread_aura", attacker, 0.4));
        int spreadAuraDuration = Math.max(1, configParser.parseInt("effects.element_duration", attacker, 200));
        BukkitTask task = new BukkitRunnable() {
            int elapsedTicks = 0;

            @Override
            public void run() {
                if (target.isDead() || !target.isValid() || elapsedTicks >= maxTicks) {
                    clearCompositeStatus(target, new ElementAura(target, plugin), BURNING_KEY);
                    burningTasks.remove(targetId);
                    cancel();
                    return;
                }

                ElementAura aura = new ElementAura(target, plugin);
                if (!aura.hasStatus(BURNING_KEY) || !isCompositeStatusStillValid(aura, BURNING_KEY)) {
                    clearCompositeStatus(target, aura, BURNING_KEY);
                    burningTasks.remove(targetId);
                    cancel();
                    return;
                }

                dealElementalDamage(target, damagePerTick, attacker, FIRE_KEY);
                consumeElementAura(target, DENDRO_KEY, dendroConsumePerTick);
                if (pyroRefreshPerTick > 0.0) {
                    addElementAura(target, FIRE_KEY, pyroRefreshPerTick, safeInterval * 2);
                }
                spreadBurningAura(attacker, target, damagePerTick, spreadRange, spreadAuraAmount, spreadAuraDuration);
                syncReactionStatuses(target);
                target.getWorld().spawnParticle(Particle.FLAME,
                        target.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.05);
                elapsedTicks += safeInterval;
            }
        }.runTaskTimer(plugin, safeInterval, safeInterval);
        burningTasks.put(targetId, task);
    }

    private void spreadBurningAura(Player attacker, LivingEntity primaryTarget, double spreadDamage,
                                   double range, double auraAmount, int auraDuration) {
        if (attacker == null || primaryTarget == null || range <= 0.0 || auraAmount <= 0.0) {
            return;
        }
        Location center = primaryTarget.getLocation();
        for (Entity entity : primaryTarget.getWorld().getNearbyEntities(center, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || !isValidBurningSpreadTarget(primaryTarget, living, range)) {
                continue;
            }
            if (spreadDamage > 0.0) {
                dealElementalDamage(living, spreadDamage, attacker, FIRE_KEY);
            }
            applySecondaryElementalHit(
                    attacker,
                    living,
                    FIRE_KEY,
                    0.0,
                    auraAmount,
                    auraDuration,
                    "burning_spread",
                    false
            );
        }
    }

    private boolean isValidBurningSpreadTarget(LivingEntity primaryTarget, LivingEntity candidate, double radius) {
        if (candidate == null || !candidate.isValid() || candidate.isDead()) {
            return false;
        }
        if (primaryTarget != null && candidate.equals(primaryTarget)) {
            return false;
        }
        if (candidate instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
            return false;
        }
        if (primaryTarget != null && candidate.getWorld() != primaryTarget.getWorld()) {
            return false;
        }
        return primaryTarget == null || candidate.getLocation().distanceSquared(primaryTarget.getLocation()) <= radius * radius;
    }

    private void pullNearbyEntities(Location center, int range, int strength) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, range, range, range)) {
            if (entity instanceof LivingEntity living && !living.isDead()) {
                Vector toCenter = center.toVector().subtract(living.getLocation().toVector());
                if (toCenter.lengthSquared() < 0.01) continue;
                Vector pull = toCenter.normalize().multiply(strength / 10.0);
                living.setVelocity(pull);
            }
        }
    }

    private void applySuperconductAreaEffect(Player attacker, LivingEntity primaryTarget, double damage) {
        if (primaryTarget == null || primaryTarget.getWorld() == null) {
            return;
        }

        double radius = Math.max(0.0, configParser.parseDouble("effects.superconduct_radius", attacker, 5.0));
        double physicalResistanceReduction = Math.max(0.0,
                configParser.parseDouble("effects.superconduct_physical_resistance_reduction", attacker, 0.40));
        int reductionDuration = Math.max(1,
                configParser.parseInt("effects.superconduct_physical_resistance_duration", attacker, 240));

        if (plugin.getMobResistanceManager() != null && !(primaryTarget instanceof Player)) {
            plugin.getMobResistanceManager().applyPhysicalResistanceReduction(
                    primaryTarget,
                    physicalResistanceReduction,
                    reductionDuration
            );
        }
        if (radius <= 0.0 || damage <= 0.0) {
            return;
        }

        for (Entity entity : primaryTarget.getWorld().getNearbyEntities(primaryTarget.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !isValidReactionAreaTarget(attacker, primaryTarget, living, radius)) {
                continue;
            }

            dealStandaloneReactionDamage(attacker, living, damage, ICE_KEY, "superconduct");
            if (plugin.getMobResistanceManager() != null && !(living instanceof Player)) {
                plugin.getMobResistanceManager().applyPhysicalResistanceReduction(
                        living,
                        physicalResistanceReduction,
                        reductionDuration
                );
            }
            if (SLOWNESS != null) {
                living.addPotionEffect(new PotionEffect(SLOWNESS, 60, 1));
            }
            living.getWorld().spawnParticle(Particle.SNOWFLAKE,
                    living.getLocation().add(0, 1, 0), 8, 0.25, 0.25, 0.25, 0.03);
        }
    }

    private boolean isValidReactionAreaTarget(Player attacker, LivingEntity primaryTarget, LivingEntity candidate, double radius) {
        if (candidate == null || !candidate.isValid() || candidate.isDead()) {
            return false;
        }
        if (attacker != null && candidate.equals(attacker)) {
            return false;
        }
        if (primaryTarget != null && candidate.equals(primaryTarget)) {
            return false;
        }
        if (candidate instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
            return false;
        }
        if (primaryTarget != null && candidate.getWorld() != primaryTarget.getWorld()) {
            return false;
        }
        return primaryTarget == null || candidate.getLocation().distanceSquared(primaryTarget.getLocation()) <= radius * radius;
    }

    private void applyOverloadAreaEffect(Player attacker, LivingEntity primaryTarget, double damage) {
        if (primaryTarget == null || primaryTarget.getWorld() == null || damage <= 0.0) {
            return;
        }

        double radius = Math.max(0.0, configParser.parseDouble("effects.overload_radius", attacker, 5.0));
        if (radius <= 0.0) {
            return;
        }

        Location center = primaryTarget.getLocation();
        for (Entity entity : primaryTarget.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !isValidReactionAreaTarget(attacker, primaryTarget, living, radius)) {
                continue;
            }

            double actualDamage = dealStandaloneReactionDamage(attacker, living, damage, FIRE_KEY, "overload");
            if (actualDamage <= 0.0) {
                continue;
            }

            Vector offset = living.getLocation().toVector().subtract(center.toVector());
            if (offset.lengthSquared() > 0.0001) {
                Vector knockback = offset.normalize().multiply(1.1);
                knockback.setY(0.25);
                living.setVelocity(knockback);
            }
            living.getWorld().spawnParticle(Particle.EXPLOSION, living.getLocation().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0.01);
        }
    }

    private void applySwirlAreaDamage(Player attacker, LivingEntity primaryTarget, Location center, double damage, String elementKey) {
        double swirlRange = configParser.parseDouble("swirl.range", attacker, 6.0);
        int duration = (int) configParser.parseDouble("swirl.element_duration", attacker, 200);
        double auraAmount = configParser.parseDouble("swirl.aura_amount", attacker, 1.0);
        String sourceKey = "swirl_spread:" + elementKey;
        boolean shouldTriggerSeed = false;

        for (Entity entity : center.getWorld().getNearbyEntities(center, swirlRange, swirlRange, swirlRange)) {
            if (!(entity instanceof LivingEntity living) || !isValidReactionAreaTarget(attacker, primaryTarget, living, swirlRange)) {
                continue;
            }
            shouldTriggerSeed = applySecondaryElementalHit(
                    attacker,
                    living,
                    elementKey,
                    damage,
                    auraAmount,
                    duration,
                    sourceKey,
                    true,
                    buildSwirlReactionDamageKey(elementKey)
            )
                    || shouldTriggerSeed;

            switch (elementKey) {
                case FIRE_KEY -> {
                    // 火元素：只附着元素，不施加燃烧效果
                    living.getWorld().spawnParticle(Particle.FLAME, living.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.08);
                }
                case ICE_KEY -> {
                    living.getWorld().spawnParticle(Particle.SNOWFLAKE, living.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.05);
                }
                case ELECTRO_KEY -> {
                    living.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, living.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.08);
                }
                case WATER_KEY -> {
                    living.getWorld().spawnParticle(Particle.SPLASH, living.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.1);
                    living.getWorld().spawnParticle(Particle.BUBBLE_POP, living.getLocation().add(0, 1, 0), 10, 0.4, 0.4, 0.4, 0.05);
                }
                default -> {
                }
            }
        }

        if (shouldTriggerSeed) {
            triggerSeedReaction(attacker, center, elementKey);
        }

        center.getWorld().spawnParticle(Particle.CLOUD, center.clone().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
        center.getWorld().playSound(center, Sound.ENTITY_GHAST_SHOOT, 0.8f, 1.2f);
    }

    private boolean applySecondaryElementalHit(Player attacker, LivingEntity target, String attackerElement,
                                               double baseDamage, double triggerAuraAmount, int auraDurationTicks,
                                               String sourceKey, boolean triggerSeed) {
        return applySecondaryElementalHit(
                attacker,
                target,
                attackerElement,
                baseDamage,
                triggerAuraAmount,
                auraDurationTicks,
                sourceKey,
                triggerSeed,
                null
        );
    }

    private boolean applySecondaryElementalHit(Player attacker, LivingEntity target, String attackerElement,
                                               double baseDamage, double triggerAuraAmount, int auraDurationTicks,
                                               String sourceKey, boolean triggerSeed,
                                               String reactionDamageKey) {
        if (attacker == null || target == null || attackerElement == null || attackerElement.isBlank()) {
            return false;
        }

        DamageResult damageResult = new DamageResult();
        damageResult.setPhysicalDamage(0.0);
        damageResult.setElementalDamage(Math.max(0.0, baseDamage));
        damageResult.setCrit(false);
        damageResult.setElement(attackerElement);
        damageResult.setPlayerStats(plugin.getPlayerStats(attacker));

        ReactionResult reactionResult = handleReaction(
                attacker,
                target,
                attackerElement,
                damageResult,
                sourceKey,
                triggerAuraAmount
        );
        if (reactionResult == null) {
            return false;
        }

        double preResistanceMainDamage = damageResult.getElementalDamage()
                + reactionResult.getScalingDamageBonus()
                + reactionResult.getAdditiveDamageBonus();
        if (preResistanceMainDamage > 0.0) {
            dealStandaloneReactionDamage(attacker, target, preResistanceMainDamage, attackerElement, reactionDamageKey);
        }
        if (reactionResult.hasTransformativeDamage()) {
            applyTransformativeReactionDamage(attacker, target, reactionResult);
        }

        consumeReactionAuras(target, reactionResult, attackerElement);
        applyTriggerAura(target, attackerElement, reactionResult, auraDurationTicks, triggerAuraAmount);
        postProcessReactionState(target, reactionResult);

        return triggerSeed && shouldTriggerSeedReaction(target, attackerElement, reactionResult);
    }

    /**
     * 为扩散的敌人附着元素
     */
    private void attachSwirlElement(LivingEntity living, String elementKey, double amount, int duration) {
        ElementAura aura = new ElementAura(living, plugin);
        aura.addElement(elementKey, amount, duration);

        if (plugin.shouldLogReactionDebug()) {
            plugin.getLogger().info("§7扩散附着：" + getElementName(elementKey) + " " + amount + "U，持续 " + duration + " tick");
        }
    }

    public void spawnBloomSeed(Location loc, Player owner, double damage) {
        if (loc == null || damage <= 0.0) {
            return;
        }

        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        int maxNearbySeeds = Math.max(1, configParser.parseInt("bloom_seed.max_nearby", owner, DEFAULT_MAX_TRIGGERED_SEEDS));
        List<ArmorStand> nearbySeeds = collectNearbyBloomSeeds(loc, 3.0, Integer.MAX_VALUE);
        nearbySeeds.sort(Comparator.comparingLong(seed -> {
            BloomSeedData data = resolveBloomSeedData(seed);
            return data == null ? Long.MAX_VALUE : data.spawnedAtMillis;
        }));
        while (nearbySeeds.size() >= maxNearbySeeds) {
            ArmorStand oldestSeed = nearbySeeds.remove(0);
            removeBloomSeed(oldestSeed);
        }

        float floatOffset = (float) configParser.parseDouble("bloom_core.float_offset", owner, 0.8);
        boolean isSmall = configParser.parseBoolean("bloom_core.is_small", owner, true);
        boolean invulnerable = configParser.parseBoolean("bloom_core.invulnerable", owner, true);
        boolean noGravity = configParser.parseBoolean("bloom_core.no_gravity", owner, true);
        boolean visible = configParser.parseBoolean("bloom_seed.visual.visible", owner, false);
        String customName = configParser.parseString(owner, "bloom_seed.visual.custom_name", "§a●");
        int duration = (int) configParser.parseDouble("bloom_seed.duration", owner, 200);
        String particleTypeStr = configParser.parseString(owner, "bloom_seed.visual.particle_type", "HAPPY_VILLAGER");
        String spreadStr = configParser.parseString(owner, "bloom_seed.visual.particle_spread", "0.3,0.3,0.3");
        double particleSpeed = configParser.parseDouble("bloom_seed.visual.particle_speed", owner, 0.02);
        int particleCount = (int) configParser.parseDouble("bloom_seed.visual.particle_count", owner, 3);

        Particle particleType = parseBloomParticleType(particleTypeStr);
        double[] spread = parseBloomParticleSpread(spreadStr);
        Location spawnLocation = loc.clone().add(0.0, floatOffset, 0.0);

        ArmorStand seed = world.spawn(spawnLocation, ArmorStand.class);
        seed.setVisible(visible);
        seed.setInvulnerable(invulnerable);
        seed.setGravity(!noGravity);
        seed.setSmall(isSmall);
        seed.setMarker(true);
        seed.setBasePlate(false);
        seed.setSilent(true);
        seed.setCustomName(customName);
        seed.setCustomNameVisible(visible);
        seed.setMetadata(BLOOM_SEED_KEY, new FixedMetadataValue(plugin, true));
        seed.setMetadata(BLOOM_SEED_DAMAGE_META, new FixedMetadataValue(plugin, damage));
        if (owner != null) {
            seed.setMetadata(BLOOM_SEED_OWNER_META, new FixedMetadataValue(plugin, owner.getUniqueId().toString()));
        }

        bloomSeeds.put(seed.getUniqueId(), new BloomSeedData(owner == null ? null : owner.getUniqueId(), damage));
        startBloomSeedVisualTask(seed, duration, particleType, spread[0], spread[1], spread[2], particleSpeed, particleCount);
    }

    private Particle parseBloomParticleType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return Particle.HAPPY_VILLAGER;
        }
        try {
            Particle particle = Particle.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
            if (particle.getDataType() != Void.class) {
                plugin.warnOnce(
                        "bloom-seed:particle-type-data:" + rawType,
                        "bloom_seed.visual.particle_type 配置 " + rawType
                                + " 需要额外粒子数据，当前草种子效果不支持，已回退到 HAPPY_VILLAGER。"
                );
                return Particle.HAPPY_VILLAGER;
            }
            return particle;
        } catch (IllegalArgumentException ex) {
            plugin.warnOnce(
                    "bloom-seed:particle-type:" + rawType,
                    "无效的 bloom_seed.visual.particle_type 配置：" + rawType + "，已回退到 HAPPY_VILLAGER。"
            );
            return Particle.HAPPY_VILLAGER;
        }
    }

    private double[] parseBloomParticleSpread(String rawSpread) {
        double[] fallback = new double[]{0.3, 0.3, 0.3};
        if (rawSpread == null || rawSpread.isBlank()) {
            return fallback;
        }
        String[] parts = rawSpread.split(",");
        if (parts.length != 3) {
            plugin.warnOnce(
                    "bloom-seed:particle-spread:" + rawSpread,
                    "无效的 bloom_seed.visual.particle_spread 配置：" + rawSpread + "，已回退到 0.3,0.3,0.3。"
            );
            return fallback;
        }
        double[] parsed = new double[3];
        for (int i = 0; i < parts.length; i++) {
            try {
                parsed[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ex) {
                plugin.warnOnce(
                        "bloom-seed:particle-spread:" + rawSpread,
                        "无效的 bloom_seed.visual.particle_spread 配置：" + rawSpread + "，已回退到 0.3,0.3,0.3。"
                );
                return fallback;
            }
        }
        return parsed;
    }

    public boolean triggerSeedReaction(Player attacker, Location location, String element) {
        if (attacker == null || location == null || element == null) {
            return false;
        }

        boolean isElectro = ELECTRO_KEY.equals(element);
        boolean isFire = FIRE_KEY.equals(element);
        if (!isElectro && !isFire) {
            return false;
        }

        double triggerRange = configParser.parseDouble(
                isElectro ? "hyperbloom.trigger_range" : "burgeon.trigger_range",
                attacker,
                6.0
        );
        int maxSeeds = Math.max(1, configParser.parseInt(
                "bloom_seed.max_triggered_per_attack",
                attacker,
                DEFAULT_MAX_TRIGGERED_SEEDS
        ));

        boolean triggered = false;
        for (ArmorStand seed : collectNearbyBloomSeeds(location, triggerRange, maxSeeds)) {
            BloomSeedData seedData = resolveBloomSeedData(seed);
            if (seedData == null) {
                removeBloomSeed(seed);
                continue;
            }

            if (isElectro) {
                triggerHyperbloomSeed(attacker, seed, seedData);
            } else {
                triggerBurgeonSeed(attacker, seed, seedData);
            }
            triggered = true;
        }
        return triggered;
    }

    public boolean isSeedExplosionImmune(LivingEntity target) {
        if (target == null || !target.hasMetadata(SEED_EXPLOSION_IMMUNITY_META)) {
            return false;
        }

        long now = System.currentTimeMillis();
        boolean foundMetadata = false;
        for (org.bukkit.metadata.MetadataValue metadataValue : target.getMetadata(SEED_EXPLOSION_IMMUNITY_META)) {
            if (metadataValue.getOwningPlugin() != plugin) {
                continue;
            }
            foundMetadata = true;
            if (metadataValue.asLong() > now) {
                return true;
            }
        }

        if (foundMetadata) {
            target.removeMetadata(SEED_EXPLOSION_IMMUNITY_META, plugin);
        }
        return false;
    }

    private List<ArmorStand> collectNearbyBloomSeeds(Location location, double range, int maxSeeds) {
        World world = location.getWorld();
        if (world == null) {
            return Collections.emptyList();
        }

        List<ArmorStand> seeds = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(location, range, range, range)) {
            if (!(entity instanceof ArmorStand seed)) {
                continue;
            }
            if (!seed.hasMetadata(BLOOM_SEED_KEY) || !seed.isValid() || seed.isDead()) {
                continue;
            }
            if (seed.getLocation().distanceSquared(location) > range * range) {
                continue;
            }
            seeds.add(seed);
        }

        seeds.sort(Comparator.comparingDouble(seed -> seed.getLocation().distanceSquared(location)));
        if (seeds.size() <= maxSeeds) {
            return seeds;
        }
        return new ArrayList<>(seeds.subList(0, maxSeeds));
    }

    private BloomSeedData resolveBloomSeedData(ArmorStand seed) {
        if (seed == null) {
            return null;
        }

        BloomSeedData cached = bloomSeeds.get(seed.getUniqueId());
        if (cached != null) {
            return cached;
        }

        double baseDamage = 0.0;
        if (seed.hasMetadata(BLOOM_SEED_DAMAGE_META)) {
            for (org.bukkit.metadata.MetadataValue metadataValue : seed.getMetadata(BLOOM_SEED_DAMAGE_META)) {
                if (metadataValue.getOwningPlugin() == plugin) {
                    baseDamage = metadataValue.asDouble();
                    break;
                }
            }
        }

        UUID ownerId = null;
        if (seed.hasMetadata(BLOOM_SEED_OWNER_META)) {
            for (org.bukkit.metadata.MetadataValue metadataValue : seed.getMetadata(BLOOM_SEED_OWNER_META)) {
                if (metadataValue.getOwningPlugin() != plugin) {
                    continue;
                }
                try {
                    ownerId = UUID.fromString(metadataValue.asString());
                } catch (IllegalArgumentException ignored) {
                    ownerId = null;
                }
                break;
            }
        }

        if (baseDamage <= 0.0) {
            return null;
        }

        BloomSeedData loaded = new BloomSeedData(ownerId, baseDamage);
        bloomSeeds.put(seed.getUniqueId(), loaded);
        return loaded;
    }

    private void startBloomSeedVisualTask(ArmorStand seed, int duration, Particle particleType,
                                          double spreadX, double spreadY, double spreadZ,
                                          double particleSpeed, int particleCount) {
        UUID seedId = seed.getUniqueId();
        cancelBloomSeedVisualTask(seedId);

        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!seed.isValid() || seed.isDead()) {
                    cleanupBloomSeed(seedId);
                    cancel();
                    return;
                }
                if (ticks >= duration) {
                    BloomSeedData seedData = bloomSeeds.get(seedId);
                    if (seedData != null) {
                        detonateBloomSeed(seed, seedData);
                    } else {
                        removeBloomSeed(seed);
                    }
                    cancel();
                    return;
                }

                if (particleType != null) {
                    seed.getLocation().getWorld().spawnParticle(
                            particleType,
                            seed.getLocation().add(0, 0.6, 0),
                            particleCount,
                            spreadX,
                            spreadY,
                            spreadZ,
                            particleSpeed
                    );
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        bloomSeedTasks.put(seedId, task);
    }

    private void cancelBloomSeedVisualTask(UUID seedId) {
        BukkitTask existingTask = bloomSeedTasks.remove(seedId);
        if (existingTask != null) {
            existingTask.cancel();
        }
    }

    private void cleanupBloomSeed(UUID seedId) {
        bloomSeeds.remove(seedId);
        cancelBloomSeedVisualTask(seedId);
    }

    private void removeBloomSeed(ArmorStand seed) {
        if (seed == null) {
            return;
        }
        cleanupBloomSeed(seed.getUniqueId());
        if (seed.isValid() && !seed.isDead()) {
            seed.remove();
        }
    }

    private void detonateBloomSeed(ArmorStand seed, BloomSeedData seedData) {
        if (seed == null || seedData == null) {
            return;
        }

        Player owner = seedData.ownerId == null ? null : Bukkit.getPlayer(seedData.ownerId);
        Location location = seed.getLocation().clone();
        double radius = configParser.parseDouble("bloom_seed.explosion_radius", owner, 5.0);
        double damageMultiplier = configParser.parseDouble("bloom_seed.natural_damage_multiplier", owner, 1.0);
        double reactionDamage = Math.max(0.0, seedData.baseDamage * Math.max(0.0, damageMultiplier));

        int hitCount = 0;
        double totalDamage = 0.0;
        List<LivingEntity> affectedTargets = new ArrayList<>();
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !isValidSeedExplosionTarget(living, location, radius)) {
                continue;
            }

            double appliedDamage = resolveSeedReactionDamage(
                    owner,
                    living,
                    reactionDamage,
                    "bloom_seed",
                    0.02
            );
            if (appliedDamage <= 0.0) {
                continue;
            }

            double actualDamage = dealStandaloneReactionDamage(owner, living, appliedDamage, DENDRO_KEY, "bloom");
            if (actualDamage <= 0.0) {
                continue;
            }

            hitCount++;
            totalDamage += actualDamage;
            affectedTargets.add(living);
        }

        markSeedExplosionImmunity(affectedTargets);
        location.getWorld().spawnParticle(Particle.COMPOSTER, location, 25, radius * 0.3, 0.6, radius * 0.3, 0.08);
        location.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 20, radius * 0.25, 0.5, radius * 0.25, 0.02);
        location.getWorld().playSound(location, Sound.BLOCK_GRASS_BREAK, 0.8f, 0.8f);

        if (owner != null && hitCount > 0) {
            utils.sendMessage(owner, "§a[绽放]", String.format("§a草原核自然爆炸，造成 §c%.1f§a 点草元素伤害，命中 §e%d§a 个目标", totalDamage, hitCount));
        }
        if (plugin.shouldLogCombatSummary()) {
            plugin.getLogger().info(String.format("[绽放-自然爆炸] 伤害：%.1f，命中：%d 个目标", reactionDamage, hitCount));
        }

        removeBloomSeed(seed);
    }

    private void triggerHyperbloomSeed(Player attacker, ArmorStand seed, BloomSeedData seedData) {
        utils.sendMessage(attacker, "§e[超绽放]", "§e触发了超绽放反应！草种子正在追踪敌人...");

        double homingRange = configParser.parseDouble("hyperbloom.homming_range", attacker, 15.0);
        LivingEntity target = findNearestEnemy(attacker, seed.getLocation(), homingRange);
        if (target == null) {
            utils.sendMessage(attacker, "§c[超绽放]", "§c未找到可追踪的目标，草种子消散。");
            removeBloomSeed(seed);
            return;
        }

        cancelBloomSeedVisualTask(seed.getUniqueId());
        startHomingSeedTask(attacker, seed, target, seedData.baseDamage, true);
    }

    private void triggerBurgeonSeed(Player attacker, ArmorStand seed, BloomSeedData seedData) {
        utils.sendMessage(attacker, "§c[烈绽放]", "§c触发了烈绽放反应！草种子立即爆炸。");
        explodeSeed(attacker, seed.getLocation(), seedData.baseDamage, false, null);
        removeBloomSeed(seed);
    }

    private void startHomingSeedTask(Player attacker, ArmorStand seed, LivingEntity target,
                                     double damage, boolean isHyperbloom) {
        if (seed.getWorld() == null || target.getWorld() == null) {
            removeBloomSeed(seed);
            return;
        }

        Location seedLoc = seed.getLocation().clone().add(0, 0.6, 0);
        double speed = configParser.parseDouble("hyperbloom.homing_speed", attacker, 0.3);
        int maxTicks = (int) configParser.parseDouble("hyperbloom.max_homming_ticks", attacker, 100);
        Particle particle = Particle.ELECTRIC_SPARK;

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!seed.isValid() || seed.isDead() || seed.getWorld() == null
                        || !target.isValid() || target.isDead() || target.getWorld() == null) {
                    removeBloomSeed(seed);
                    cancel();
                    return;
                }

                Location targetLoc = target.getLocation().clone().add(0, 1, 0);
                double distance = seedLoc.distance(targetLoc);
                if (Double.isNaN(distance) || Double.isInfinite(distance)) {
                    explodeSeed(attacker, seedLoc, damage, isHyperbloom, target);
                    removeBloomSeed(seed);
                    cancel();
                    return;
                }

                if (distance < 1.0) {
                    explodeSeed(attacker, seedLoc, damage, isHyperbloom, target);
                    removeBloomSeed(seed);
                    cancel();
                    return;
                }

                Vector direction = distance > 0.1
                        ? targetLoc.toVector().subtract(seedLoc.toVector()).normalize()
                        : null;
                if (direction == null) {
                    explodeSeed(attacker, seedLoc, damage, isHyperbloom, target);
                    removeBloomSeed(seed);
                    cancel();
                    return;
                }

                seedLoc.add(direction.multiply(speed));
                if (!isValidLocation(seedLoc)) {
                    explodeSeed(attacker, seedLoc, damage, isHyperbloom, target);
                    removeBloomSeed(seed);
                    cancel();
                    return;
                }

                seed.teleport(seedLoc);
                if (particle != null) {
                    seedLoc.getWorld().spawnParticle(particle, seedLoc, 5, 0.2, 0.2, 0.2, 0.05);
                }

                ticks++;
                if (ticks >= maxTicks) {
                    explodeSeed(attacker, seedLoc, damage, isHyperbloom, target);
                    removeBloomSeed(seed);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private boolean isValidLocation(Location loc) {
        if (loc == null) {
            return false;
        }
        return !Double.isNaN(loc.getX()) && !Double.isInfinite(loc.getX())
                && !Double.isNaN(loc.getY()) && !Double.isInfinite(loc.getY())
                && !Double.isNaN(loc.getZ()) && !Double.isInfinite(loc.getZ());
    }

    private void explodeSeed(Player attacker, Location location, double baseDamage,
                             boolean isHyperbloom, LivingEntity preferredTarget) {
        String configPrefix = isHyperbloom ? "hyperbloom" : "burgeon";
        double radius = configParser.parseDouble(
                configPrefix + ".explosion_radius",
                attacker,
                isHyperbloom ? 1.0 : 5.0
        );
        double multiplier = configParser.parseDouble(configPrefix + ".damage_multiplier", attacker, 3.0);
        double masteryCoeff = configParser.parseDouble(configPrefix + ".mastery_coefficient", attacker, 1.8);

        double elementalMastery = configParser.parseDouble("default_stats.element_mastery", attacker, 0);
        PlayerStats playerStats = plugin.getPlayerStats(attacker);
        if (playerStats != null) {
            elementalMastery = Math.max(elementalMastery, playerStats.getElementMastery());
        }

        double reactionDamage = baseDamage * multiplier + elementalMastery * masteryCoeff;
        int hitCount = 0;
        double totalDamageDealt = 0.0;
        List<LivingEntity> affectedTargets = new ArrayList<>();

        if (isHyperbloom) {
            LivingEntity target = preferredTarget;
            if (!isValidSeedExplosionTarget(target, location, radius)) {
                target = findNearestEnemy(attacker, location, radius);
            }

            if (isValidSeedExplosionTarget(target, location, radius)) {
                Location impactCenter = target.getLocation().clone();
                for (Entity entity : impactCenter.getWorld().getNearbyEntities(impactCenter, radius, radius, radius)) {
                    if (!(entity instanceof LivingEntity living) || !isValidSeedExplosionTarget(living, impactCenter, radius)) {
                        continue;
                    }

                    double appliedDamage = resolveSeedReactionDamage(
                            attacker,
                            living,
                            reactionDamage,
                            "hyperbloom",
                            0.05
                    );
                    if (appliedDamage <= 0.0) {
                        continue;
                    }

                    double actualDamage = dealStandaloneReactionDamage(attacker, living, appliedDamage, DENDRO_KEY, "hyperbloom");
                    if (actualDamage <= 0.0) {
                        continue;
                    }

                    hitCount++;
                    totalDamageDealt += actualDamage;
                    affectedTargets.add(living);
                }
            }

            location.getWorld().strikeLightningEffect(location);
            location.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 20, radius, radius, radius, 0.1);
        } else {
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (!(entity instanceof LivingEntity living) || !isValidSeedExplosionTarget(living, location, radius)) {
                    continue;
                }

                double appliedDamage = resolveSeedReactionDamage(
                        attacker,
                        living,
                        reactionDamage,
                        "burgeon",
                        0.05
                );
                if (appliedDamage <= 0.0) {
                    continue;
                }

                double actualDamage = dealStandaloneReactionDamage(attacker, living, appliedDamage, DENDRO_KEY, "burgeon");
                if (actualDamage <= 0.0) {
                    continue;
                }

                hitCount++;
                totalDamageDealt += actualDamage;
                affectedTargets.add(living);

                Vector offset = living.getLocation().toVector().subtract(location.toVector());
                if (offset.lengthSquared() > 0.0001) {
                    Vector knockback = offset.normalize().multiply(0.8);
                    knockback.setY(0.3);
                    living.setVelocity(knockback);
                }
            }

            location.getWorld().spawnParticle(Particle.FLAME, location, 15, radius, radius, radius, 0.05);
            location.getWorld().spawnParticle(Particle.LARGE_SMOKE, location, 10, radius, radius, radius, 0.08);
            location.getWorld().spawnParticle(Particle.ASH, location, 20, radius * 0.5, radius * 0.5, radius * 0.5, 0.02);
            location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.2f);
        }

        markSeedExplosionImmunity(affectedTargets);

        if (hitCount > 0) {
            String reactionName = isHyperbloom ? "超绽放" : "烈绽放";
            String colorCode = isHyperbloom ? "§e" : "§c";
            utils.sendMessage(attacker, colorCode + "[" + reactionName + "]",
                    String.format("%s§r 造成 §c%.1f§r 点草元素伤害，命中 §e%d§r 个目标",
                            colorCode, totalDamageDealt, hitCount));

            if (elementalMastery > 0.0 && isHyperbloom) {
                utils.sendMessage(attacker, colorCode + "[精通加成]",
                        String.format("元素精通提供 §a+%.1f§r 额外伤害", elementalMastery * masteryCoeff));
            }
        }

        if (plugin.shouldLogCombatSummary()) {
            plugin.getLogger().info(String.format("[%s] 伤害：%.1f，命中：%d 个目标",
                    isHyperbloom ? "超绽放" : "烈绽放", reactionDamage, hitCount));
        }

        if (isHyperbloom) {
            location.getWorld().playSound(location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.0f);
        }
    }

    private double resolveSeedReactionDamage(Player attacker, LivingEntity target,
                                             double baseDamage, String configPrefix,
                                             double defaultPlayerMultiplier) {
        if (target == null || baseDamage <= 0.0) {
            return 0.0;
        }
        if (!(target instanceof Player)) {
            return baseDamage;
        }
        double playerDamageMultiplier = Math.max(0.0,
                configParser.parseDouble(configPrefix + ".player_damage_multiplier", attacker, defaultPlayerMultiplier));
        return baseDamage * playerDamageMultiplier;
    }

    private void markSeedExplosionImmunity(Collection<LivingEntity> affectedTargets) {
        if (affectedTargets == null || affectedTargets.isEmpty()) {
            return;
        }

        long expiresAt = System.currentTimeMillis() + 250L;
        for (LivingEntity living : affectedTargets) {
            if (living != null && living.isValid() && !living.isDead()) {
                living.setMetadata(SEED_EXPLOSION_IMMUNITY_META, new FixedMetadataValue(plugin, expiresAt));
            }
        }

        long delayTicks = Math.max(1L, (long) Math.ceil(Math.max(0L, expiresAt - System.currentTimeMillis()) / 50.0));
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (LivingEntity living : affectedTargets) {
                    if (living == null || !living.isValid()) {
                        continue;
                    }

                    boolean foundMetadata = false;
                    boolean stillImmune = false;
                    for (org.bukkit.metadata.MetadataValue metadataValue : living.getMetadata(SEED_EXPLOSION_IMMUNITY_META)) {
                        if (metadataValue.getOwningPlugin() != plugin) {
                            continue;
                        }
                        foundMetadata = true;
                        if (metadataValue.asLong() > now) {
                            stillImmune = true;
                            break;
                        }
                    }

                    if (foundMetadata && !stillImmune) {
                        living.removeMetadata(SEED_EXPLOSION_IMMUNITY_META, plugin);
                    }
                }
            }
        }.runTaskLater(plugin, delayTicks);
    }

    private boolean isValidSeedExplosionTarget(LivingEntity target, Location center, double radius) {
        if (target == null || center == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
            return false;
        }
        if (target.getWorld() != center.getWorld()) {
            return false;
        }
        return target.getLocation().distanceSquared(center) <= radius * radius;
    }

    private LivingEntity findNearestEnemy(Player attacker, Location loc, double range) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }

        LivingEntity nearestPreferred = null;
        LivingEntity nearestFallback = null;
        double minPreferredDistance = Double.MAX_VALUE;
        double minFallbackDistance = Double.MAX_VALUE;
        double rangeSquared = range * range;
        for (Entity entity : world.getNearbyEntities(loc, range, range, range)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living.equals(attacker) || !living.isValid() || living.isDead()) {
                continue;
            }
            if (living instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
                continue;
            }

            double distance = loc.distanceSquared(living.getLocation());
            if (distance > rangeSquared) {
                continue;
            }

            if (isPreferredEnemyTarget(living)) {
                if (distance < minPreferredDistance) {
                    minPreferredDistance = distance;
                    nearestPreferred = living;
                }
                continue;
            }

            if (distance < minFallbackDistance) {
                minFallbackDistance = distance;
                nearestFallback = living;
            }
        }
        return nearestPreferred != null ? nearestPreferred : nearestFallback;
    }

    private boolean isPreferredEnemyTarget(LivingEntity target) {
        return target instanceof Monster || target instanceof Player;
    }

    private void spawnCrystalChip(Location location, String elementKey, double shieldValue) {
        if (location == null || elementKey == null || shieldValue <= 0) return;

        Material chipMaterial = switch (elementKey) {
            case FIRE_KEY -> Material.RED_STAINED_GLASS;
            case WATER_KEY -> Material.BLUE_STAINED_GLASS;
            case ICE_KEY -> Material.LIGHT_BLUE_STAINED_GLASS;
            case ELECTRO_KEY -> Material.PURPLE_STAINED_GLASS;
            default -> Material.WHITE_STAINED_GLASS;
        };

        ItemStack chipItem = new ItemStack(chipMaterial, 1);
        ItemMeta meta = chipItem.getItemMeta();
        if (meta != null) {
            String elementName = switch (elementKey) {
                case FIRE_KEY -> "火元素晶片";
                case WATER_KEY -> "水元素晶片";
                case ICE_KEY -> "冰元素晶片";
                case ELECTRO_KEY -> "雷元素晶片";
                default -> "结晶晶片";
            };
            meta.setDisplayName(ChatColor.GOLD + elementName);
            meta.setLore(List.of(
                    ChatColor.GRAY + "拾取后直接获得护盾",
                    ChatColor.YELLOW + "护盾值: " + String.format("%.1f", shieldValue),
                    ChatColor.RED + "不会获得物品"
            ));
            chipItem.setItemMeta(meta);
        }

        Location spawnLoc = location.clone().add(0, 1, 0);
        Item chipEntity = location.getWorld().dropItem(spawnLoc, chipItem);
        chipEntity.setPickupDelay(0);
        chipEntity.setInvulnerable(true);
        chipEntity.setGlowing(true);
        chipEntity.setCustomName(ChatColor.GOLD + getElementName(elementKey) + "元素晶片");
        chipEntity.setCustomNameVisible(true);
        chipEntity.setCanMobPickup(false);

        UUID chipId = chipEntity.getUniqueId();

        location.getWorld().spawnParticle(Particle.CRIT, spawnLoc, 15, 0.5, 0.5, 0.5, 0.1);
        location.getWorld().playSound(spawnLoc, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);

        int expireTime = configParser.parseInt("effects.crystal_chip_expire_time", null, 300);
        BukkitTask expireTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (chipEntity.isValid() && !chipEntity.isDead()) {
                    chipEntity.remove();
                    crystalChipMap.remove(chipId);
                    expireTasks.remove(chipId);
                }
            }
        }.runTaskLater(plugin, expireTime);

        crystalChipMap.put(chipId, new CrystalChipData(elementKey, shieldValue));
        expireTasks.put(chipId, expireTask);
    }

    public boolean handleCrystalPickup(Player player, Item item) {
        UUID itemId = item.getUniqueId();
        if (crystalChipMap.containsKey(itemId)) {
            CrystalChipData data = crystalChipMap.get(itemId);
            if (expireTasks.containsKey(itemId)) {
                expireTasks.get(itemId).cancel();
                expireTasks.remove(itemId);
            }
            applyShieldToPlayer(player, data.elementKey, data.shieldValue);
            item.remove();
            crystalChipMap.remove(itemId);

            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
            player.sendMessage(GEO_TAG + "§f拾取" + getElementName(data.elementKey) + "元素晶片，获得§e" +
                    String.format("%.1f", data.shieldValue) + "§f点护盾");
            return true;
        }
        return false;
    }

    private void applyShieldToPlayer(Player player, String elementKey, double shieldValue) {
        if (shieldValue <= 0) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Map<String, Double> shields = playerShields.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        String shieldKey = normalizeShieldKey(elementKey);
        shields.put(shieldKey, shields.getOrDefault(shieldKey, 0.0) + shieldValue);

        int duration = configParser.parseInt("effects.shield_duration", player, 300);
        if (RESISTANCE != null) {
            player.removePotionEffect(RESISTANCE);
            player.addPotionEffect(new PotionEffect(RESISTANCE, duration, 0));
        }

        BukkitTask oldTask = shieldExpireTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }

        BukkitTask expireTask = new BukkitRunnable() {
            @Override
            public void run() {
                Map<String, Double> remaining = playerShields.remove(playerId);
                shieldExpireTasks.remove(playerId);
                if (remaining != null && !remaining.isEmpty()) {
                    player.sendMessage(GEO_TAG + "§c你的护盾消失了");
                }
            }
        }.runTaskLater(plugin, duration);
        shieldExpireTasks.put(playerId, expireTask);
    }

    // ========== 【公开 API】供外部插件调用 ==========

    /**
     * 为玩家添加护盾（公开方法）
     * @param player 玩家
     * @param shieldValue 护盾值
     */
    public void applyShield(Player player, double shieldValue) {
        applyShieldToPlayer(player, null, shieldValue);
    }

    /**
     * 获取玩家当前护盾值
     * @param player 玩家
     * @return 护盾值
     */
    public double getPlayerShield(Player player) {
        return getShieldTotal(player.getUniqueId());
    }

    /**
     * 移除玩家的护盾
     * @param player 玩家
     */
    public void removePlayerShield(Player player) {
        clearPlayerShield(player.getUniqueId());
    }

    /**
     * 获取所有玩家的护盾数据（只读）
     * @return 不可修改的护盾映射表
     */
    public Map<UUID, Double> getAllShields() {
        Map<UUID, Double> totals = new HashMap<>();
        for (UUID playerId : playerShields.keySet()) {
            totals.put(playerId, getShieldTotal(playerId));
        }
        return Collections.unmodifiableMap(totals);
    }

    /**
     * 检查玩家是否有护盾
     * @param player 玩家
     * @return 是否有护盾
     */
    public boolean hasShield(Player player) {
        return getPlayerShield(player) > 0;
    }

    public double onPlayerDamage(Player player, double damage) {
        return onPlayerDamage(player, damage, null);
    }

    public double onPlayerDamage(Player player, double damage, String damageElement) {
        if (damage <= 0) {
            return damage;
        }

        UUID playerId = player.getUniqueId();
        Map<String, Double> shields = playerShields.get(playerId);
        double remainingDamage = damage;
        String normalizedDamageElement = normalizeDamageElement(damageElement);

        if (shields != null && !shields.isEmpty()) {
            double matchingMultiplier = Math.max(1.0,
                    configParser.parseDouble("effects.crystallize_matching_element_absorption_multiplier", player, 2.5));

            if (normalizedDamageElement != null && shields.containsKey(normalizedDamageElement)) {
                remainingDamage = absorbShieldDamage(shields, normalizedDamageElement, remainingDamage, matchingMultiplier);
            }

            if (remainingDamage > 0) {
                for (String shieldKey : new ArrayList<>(shields.keySet())) {
                    if (shieldKey.equals(normalizedDamageElement)) {
                        continue;
                    }
                    remainingDamage = absorbShieldDamage(shields, shieldKey, remainingDamage, 1.0);
                    if (remainingDamage <= 0) {
                        break;
                    }
                }
            }

            if (shields.isEmpty()) {
                clearPlayerShield(playerId);
                if (remainingDamage < damage) {
                    player.getWorld().spawnParticle(Particle.POOF, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
                }
            } else if (remainingDamage < damage) {
                player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);
            }
        }

        return applyResistanceToDamage(player, remainingDamage, normalizedDamageElement);
    }

    // ========== 【公开 API】伤害减免计算 ==========

    /**
     * 计算护盾减免后的最终伤害（公开方法）
     * @param player 玩家
     * @param damage 原始伤害
     * @return 最终伤害
     */
    public double calculateShieldDamage(Player player, double damage) {
        return onPlayerDamage(player, damage);
    }

    public void markIncomingDamageElement(LivingEntity target, String elementKey) {
        if (!(target instanceof Player player) || elementKey == null || elementKey.isBlank()) {
            return;
        }
        player.setMetadata(INCOMING_DAMAGE_ELEMENT_META, new FixedMetadataValue(plugin, elementKey));
    }

    public String consumeIncomingDamageElement(Player player) {
        if (!player.hasMetadata(INCOMING_DAMAGE_ELEMENT_META)) {
            return null;
        }

        String elementKey = null;
        for (org.bukkit.metadata.MetadataValue value : player.getMetadata(INCOMING_DAMAGE_ELEMENT_META)) {
            if (value.getOwningPlugin() == plugin) {
                elementKey = value.asString();
            }
        }
        player.removeMetadata(INCOMING_DAMAGE_ELEMENT_META, plugin);
        return elementKey;
    }

    /**
     * 根据名称获取粒子效果
     */
    private double dealElementalDamage(LivingEntity target, double damage, String elementKey) {
        if (damage <= 0) {
            return 0.0;
        }
        double finalDamage = plugin.applyMobResistance(target, damage, elementKey);
        markIncomingDamageElement(target, elementKey);
        target.damage(finalDamage);
        return finalDamage;
    }

    private double dealElementalDamage(LivingEntity target, double damage, Entity source, String elementKey) {
        if (damage <= 0) {
            return 0.0;
        }
        double finalDamage = plugin.applyMobResistance(target, damage, elementKey);
        markIncomingDamageElement(target, elementKey);
        if (source instanceof Player playerSource) {
            target.setMetadata(MANUAL_DAMAGE_BYPASS_META,
                    new FixedMetadataValue(plugin, playerSource.getUniqueId().toString()));
            target.setNoDamageTicks(0);
        }
        if (source != null) {
            target.damage(finalDamage, source);
        } else {
            target.damage(finalDamage);
        }
        return finalDamage;
    }

    private String normalizeShieldKey(String elementKey) {
        return elementKey == null || elementKey.isBlank() ? GENERIC_SHIELD_KEY : elementKey;
    }

    private String normalizeDamageElement(String damageElement) {
        if (damageElement == null || damageElement.isBlank()) {
            return null;
        }
        return damageElement;
    }

    private double getShieldTotal(UUID playerId) {
        Map<String, Double> shields = playerShields.get(playerId);
        if (shields == null || shields.isEmpty()) {
            return 0.0;
        }

        double total = 0.0;
        for (double value : shields.values()) {
            total += value;
        }
        return total;
    }

    private void clearPlayerShield(UUID playerId) {
        playerShields.remove(playerId);
        BukkitTask expireTask = shieldExpireTasks.remove(playerId);
        if (expireTask != null) {
            expireTask.cancel();
        }
    }

    private double absorbShieldDamage(Map<String, Double> shields, String shieldKey, double damage, double multiplier) {
        Double shieldValue = shields.get(shieldKey);
        if (shieldValue == null || shieldValue <= 0 || damage <= 0) {
            shields.remove(shieldKey);
            return damage;
        }

        double effectiveShield = shieldValue * Math.max(1.0, multiplier);
        if (effectiveShield >= damage) {
            double consumedShield = damage / Math.max(1.0, multiplier);
            double remainingShield = shieldValue - consumedShield;
            if (remainingShield <= 0.0001) {
                shields.remove(shieldKey);
            } else {
                shields.put(shieldKey, remainingShield);
            }
            return 0.0;
        }

        shields.remove(shieldKey);
        return damage - effectiveShield;
    }

    private double applyResistanceToDamage(Player player, double damage, String damageElement) {
        if (damage <= 0) {
            return 0.0;
        }

        PlayerStats stats = damageCalculator.getPlayerStats(player);
        if (stats == null) {
            return damage;
        }

        String resistanceKey = damageElement == null || damageElement.isBlank()
                ? PlayerStats.PHYSICAL_KEY
                : damageElement;
        double resistance = stats.getResistance(resistanceKey);
        double multiplier = Math.max(0.0, 1.0 - resistance);
        return damage * multiplier;
    }

    private Particle getParticle(String particleName) {
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无效的粒子类型：" + particleName);
            return Particle.HAPPY_VILLAGER;
        }
    }

    private void logDebug(Player attacker, LivingEntity target, String attackerElement,
                          Map<String, Double> targetElements, boolean isFrozen) {
        if (plugin.shouldLogReactionDebug()) {
            plugin.getLogger().info("=== 元素反应检测 ===");
            plugin.getLogger().info("攻击者：" + attacker.getName() + ", 元素：" + attackerElement);
            plugin.getLogger().info("目标：" + target.getName());
            plugin.getLogger().info(String.format(Locale.US,
                    "目标元素状态 - 火:%.2f 水:%.2f 冰:%.2f 雷:%.2f 草:%.2f 冻结:%s",
                    targetElements.getOrDefault(FIRE_KEY, 0.0),
                    targetElements.getOrDefault(WATER_KEY, 0.0),
                    targetElements.getOrDefault(ICE_KEY, 0.0),
                    targetElements.getOrDefault(ELECTRO_KEY, 0.0),
                    targetElements.getOrDefault(DENDRO_KEY, 0.0),
                    String.valueOf(isFrozen)));
        }
    }
}
