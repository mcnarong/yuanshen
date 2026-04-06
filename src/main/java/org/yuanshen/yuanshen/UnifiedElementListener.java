package org.yuanshen.yuanshen;

import static org.yuanshen.yuanshen.ElementConstant.*;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

public class UnifiedElementListener implements Listener {
    private final Yuanshen plugin;
    private final ElementUtils utils;
    private final ElementReactionManager reactionManager;
    private final ElementDamageCalculator damageCalculator;
    private final ConfigParser configParser;
    private final Map<UUID, Long> chargedAttackCooldowns = new HashMap<>();
    private final Map<UUID, PendingChargedAttack> pendingChargedAttacks = new HashMap<>();
    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";

    public UnifiedElementListener(Yuanshen plugin, ElementUtils utils,
                                  ElementReactionManager reactionManager,
                                  ElementDamageCalculator damageCalculator,
                                  ConfigParser configParser) {
        this.plugin = plugin;
        this.utils = utils;
        this.reactionManager = reactionManager;
        this.damageCalculator = damageCalculator;
        this.configParser = configParser;

        new BukkitRunnable() {
            @Override
            public void run() {
                tickPendingChargedAttacks();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player attacker = event.getPlayer();
        if (!plugin.isCharacterModeActive(attacker)) {
            pendingChargedAttacks.remove(attacker.getUniqueId());
            return;
        }
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        CharacterSkillHandler chargedHandler = plugin.getCharacterSkillEngine().resolveHandler(attacker, handItem);
        if (chargedHandler == null) {
            return;
        }

        if (!plugin.isChargedAttackSuppressed(attacker)
                && chargedHandler.supportsChargedAttack()
                && chargedHandler.isChargedAttack(attacker, handItem)) {
            String attackerElement = getAttackElement(attacker, handItem);
            if (attackerElement == null) {
                return;
            }

            if (!chargedHandler.tryConsumeChargedAttack(attacker)) {
                return;
            }

            LivingEntity target = chargedHandler.findChargedAttackTarget(attacker);
            if (chargedHandler.handlesChargedAttackInternally()) {
                chargedHandler.beginChargedAttack(attacker, target);
                return;
            }
            beginPendingChargedAttack(attacker, handItem, attackerElement, target, chargedHandler);
            return;
        }

        if (plugin.isNormalAttackSuppressed(attacker)) {
            return;
        }
        if (chargedHandler.handlesNormalAttackInternally()) {
            chargedHandler.tryCastNormalAttack(attacker);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker;
        Projectile projectile = null;
        YelanSkillHandler.BowShotContext yelanShotContext = null;
        if (event.getDamager() instanceof Player directAttacker) {
            attacker = directAttacker;
        } else if (event.getDamager() instanceof Projectile shotProjectile
                && shotProjectile.getShooter() instanceof Player projectileAttacker) {
            attacker = projectileAttacker;
            projectile = shotProjectile;
            if (plugin.getYelanSkillHandler() == null) {
                return;
            }
            yelanShotContext = plugin.getYelanSkillHandler().consumeBowShotContext(shotProjectile);
            if (yelanShotContext == null) {
                return;
            }
        } else {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        if (projectile == null && shouldBypassManualDamage(attacker, target)) {
            return;
        }
        if (projectile == null && plugin.isNormalAttackSuppressed(attacker)) {
            event.setCancelled(true);
            return;
        }
        if (projectile == null && isYelanMeleeAttack(attacker)) {
            event.setCancelled(true);
            return;
        }
        if (projectile == null && !plugin.isCharacterModeActive(attacker)) {
            pendingChargedAttacks.remove(attacker.getUniqueId());
            return;
        }

        // 排除草种子（盔甲架）作为攻击目标
        if (target instanceof ArmorStand && target.hasMetadata(BLOOM_SEED_KEY)) {
            return;
        }

        boolean triggerSeed = true;
        if (reactionManager.isSeedExplosionImmune(target)) {
            ItemStack currentHandItem = attacker.getInventory().getItemInMainHand();
            String attackerElement = yelanShotContext != null
                    ? yelanShotContext.attackElement()
                    : getAttackElement(attacker, currentHandItem);
            if (ELECTRO_KEY.equals(attackerElement) || FIRE_KEY.equals(attackerElement)) {
                if (plugin.shouldLogReactionDebug()) {
                    plugin.getLogger().info("§7[阻止连锁] 防止草种子无限循环 - " + target.getName());
                }
                triggerSeed = false;
            }
        }

        PendingChargedAttack pendingChargedAttack = projectile == null ? consumePendingChargedHit(attacker, target) : null;
        CharacterSkillHandler chargedHandler = yelanShotContext != null
                ? plugin.getYelanSkillHandler()
                : (pendingChargedAttack != null
                ? plugin.getCharacterSkillEngine().get(pendingChargedAttack.characterType())
                : plugin.getCharacterSkillEngine().resolveHandler(attacker, attacker.getInventory().getItemInMainHand()));
        ItemStack handItem = yelanShotContext != null && yelanShotContext.handItemSnapshot() != null
                ? yelanShotContext.handItemSnapshot().clone()
                : (pendingChargedAttack != null && pendingChargedAttack.handItemSnapshot() != null
                ? pendingChargedAttack.handItemSnapshot().clone()
                : attacker.getInventory().getItemInMainHand());

        if (projectile == null
                && chargedHandler != null
                && chargedHandler.getCharacterType() == CharacterType.NINGGUANG
                && plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.NINGGUANG)) {
            event.setCancelled(true);
            return;
        }

        String attackerElement = yelanShotContext != null
                ? yelanShotContext.attackElement()
                : (pendingChargedAttack != null
                ? pendingChargedAttack.attackerElement()
                : getAttackElement(attacker, handItem));
        if (attackerElement == null) return;

        boolean chargedAttack = yelanShotContext != null
                ? yelanShotContext.chargedAttack()
                : pendingChargedAttack != null;
        if (projectile == null && !chargedAttack && chargedHandler != null && chargedHandler.supportsChargedAttack()) {
            chargedAttack = chargedHandler.isChargedAttack(attacker, handItem);
            if (chargedAttack && !chargedHandler.tryConsumeChargedAttack(attacker)) {
                event.setCancelled(true);
                return;
            }
        }

        if (projectile == null && chargedAttack && pendingChargedAttack == null && chargedHandler != null) {
            chargedHandler.beginChargedAttack(attacker, target);
        }

        boolean plungeAttack = projectile == null && !chargedAttack && isPlungeAttack(attacker, target);
        if (projectile == null && chargedAttack && chargedHandler != null && chargedHandler.usesChargedPathAttack(attacker)) {
            executeChargedPathAttack(event, attacker, target, handItem, attackerElement, triggerSeed, chargedHandler);
            return;
        }
        executeElementAttack(
                event,
                attacker,
                target,
                handItem,
                attackerElement,
                triggerSeed,
                chargedAttack,
                plungeAttack,
                chargedHandler,
                projectile == null,
                yelanShotContext == null ? null : yelanShotContext.chargedMultiplier(),
                projectile == null,
                resolveProjectileAttachmentSection(yelanShotContext),
                resolveProjectileAttachmentGroup(yelanShotContext)
        );
        if (projectile != null && yelanShotContext != null && plugin.getYelanSkillHandler() != null) {
            plugin.getYelanSkillHandler().onBowShotResolved(attacker, target, projectile, yelanShotContext);
        }
    }

    /**
     * 处理元素伤害逻辑，兼容原版命中和手动冲刺命中。
     * @param triggerSeed 是否触发草种子反应
     */
    private void executeElementAttack(EntityDamageByEntityEvent event, Player attacker,
                                      LivingEntity target, ItemStack handItem,
                                      String attackerElement, boolean triggerSeed, boolean chargedAttack,
                                      boolean plungeAttack, CharacterSkillHandler chargedHandler) {
        executeElementAttack(event, attacker, target, handItem, attackerElement, triggerSeed, chargedAttack,
                plungeAttack, chargedHandler, true, null, true, null, null);
    }

    private void executeElementAttack(EntityDamageByEntityEvent event, Player attacker,
                                      LivingEntity target, ItemStack handItem,
                                      String attackerElement, boolean triggerSeed, boolean chargedAttack,
                                      boolean plungeAttack, CharacterSkillHandler chargedHandler,
                                      boolean emitChargedNotice) {
        executeElementAttack(event, attacker, target, handItem, attackerElement, triggerSeed, chargedAttack,
                plungeAttack, chargedHandler, emitChargedNotice, null, true, null, null);
    }

    private void executeElementAttack(EntityDamageByEntityEvent event, Player attacker,
                                      LivingEntity target, ItemStack handItem,
                                      String attackerElement, boolean triggerSeed, boolean chargedAttack,
                                      boolean plungeAttack, CharacterSkillHandler chargedHandler,
                                      boolean emitChargedNotice, Double chargedMultiplierOverride,
                                      boolean invokeChargedHitHook,
                                      String attachmentSectionOverride,
                                      String reactionGroupOverride) {
        if (plugin.getYelanSkillHandler() != null) {
            plugin.getYelanSkillHandler().markCombat(attacker);
        }

        DamageResult damageResult = damageCalculator.calculateElementDamage(attacker, target, attackerElement, handItem);
        if (damageResult == null) {
            return;
        }

        ElementReactionManager.AttachmentProfile attachmentProfile = resolveAttackAttachmentProfile(
                attacker,
                chargedAttack,
                plungeAttack,
                chargedHandler,
                attachmentSectionOverride,
                reactionGroupOverride
        );
        double triggerAuraAmount = attachmentProfile.getAuraAmount();
        ReactionResult reactionResult = reactionManager.handleReaction(
                attacker,
                target,
                attackerElement,
                damageResult,
                attachmentProfile
        );
        if (reactionResult == null) {
            return;
        }

        double baseDamage = damageResult.getRawTotalDamage();
        double scalingReactionDamage = reactionResult.getScalingDamageBonus();
        double additiveReactionDamage = reactionResult.getAdditiveDamageBonus();

        // 应用额外加成（如生命值加成、饥饿值加成）
        double extraBonus = getExtraBonuses(attacker, attackerElement);
        double chargedMultiplier = 1.0;
        if (chargedAttack) {
            chargedMultiplier = chargedMultiplierOverride != null
                    ? chargedMultiplierOverride
                    : (chargedHandler != null ? chargedHandler.getChargedDamageMultiplier(attacker) : 1.0);
        }
        double plungeMultiplier = plungeAttack ? getPlungeDamageMultiplier(attacker, chargedHandler) : 1.0;
        double weaponAttackMultiplier = chargedAttack
                ? plugin.getWeaponManager().getChargedAttackDamageMultiplier(attacker)
                : (plungeAttack ? 1.0 : plugin.getWeaponManager().getNormalAttackDamageMultiplier(attacker));
        double weaponGlobalMultiplier = plugin.getWeaponManager().getGlobalDamageMultiplier(attacker);
        double characterNormalMultiplier = 1.0;
        if (!chargedAttack && !plungeAttack && plugin.getCharacterManager() != null) {
            CharacterType activeCharacter = plugin.getCharacterResolver().resolveCharacter(attacker);
            characterNormalMultiplier = plugin.getCharacterManager().getAndAdvanceNormalAttackMultiplier(
                    attacker,
                    activeCharacter,
                    1.0
            );
        } else if (plugin.getCharacterManager() != null) {
            plugin.getCharacterManager().resetNormalAttackCombo(attacker);
        }
        double attackMultiplier = chargedMultiplier * plungeMultiplier * weaponAttackMultiplier * characterNormalMultiplier;
        double damageBonusMultiplier = extraBonus * weaponGlobalMultiplier;
        double talentScaledDamage = (baseDamage + scalingReactionDamage) * attackMultiplier;
        double boostedDamage = (talentScaledDamage + additiveReactionDamage) * damageBonusMultiplier;

        // 应用暴击
        double critMultiplier = 1.0;
        double preResistanceMainDamage = boostedDamage;
        double finalMainDamage = boostedDamage;
        if (damageResult.isCrit()) {
            PlayerStats stats = damageResult.getPlayerStats();
            if (stats != null) {
                critMultiplier = 1 + stats.getCritDamage();
                preResistanceMainDamage = boostedDamage * critMultiplier;
                finalMainDamage = preResistanceMainDamage;
            }
        }

        if (target instanceof Player playerTarget) {
            reactionManager.markIncomingDamageElement(playerTarget, attackerElement);
        }

        finalMainDamage = plugin.applyMobResistance(target, finalMainDamage, attackerElement);

        if (event != null) {
            event.setDamage(finalMainDamage);
        } else {
            markManualDamageBypass(attacker, target);
            target.setNoDamageTicks(0);
            target.damage(finalMainDamage, attacker);
        }

        double appliedTransformativeDamage = 0.0;
        if (reactionResult.hasTransformativeDamage()) {
            if (event != null) {
                appliedTransformativeDamage = reactionManager.estimateAppliedTransformativeReactionDamage(attacker, target, reactionResult);
                Bukkit.getScheduler().runTask(plugin,
                        () -> reactionManager.applyTransformativeReactionDamage(attacker, target, reactionResult));
            } else {
                appliedTransformativeDamage = reactionManager.applyTransformativeReactionDamage(attacker, target, reactionResult);
            }
        }

        double totalDisplayedDamage = finalMainDamage + appliedTransformativeDamage;

        // 发送消息
        for (String msg : reactionResult.getMessages()) {
            // handled below by unified final-damage display
        }

        double displayedReactionDamage = reactionManager.calculateDisplayedReactionDamage(
                reactionResult,
                preResistanceMainDamage,
                finalMainDamage,
                appliedTransformativeDamage
        );
        utils.sendDamageResult(attacker, reactionResult, totalDisplayedDamage, displayedReactionDamage);

        if (damageResult.isCrit()) {
            utils.sendMessage(attacker, "§6【暴击】", String.format("§e造成暴击！伤害：§c%s",
                    ElementUtils.formatDamage(totalDisplayedDamage)));
        }

        if (chargedAttack && chargedHandler != null) {
            if (invokeChargedHitHook) {
                chargedHandler.onChargedAttackHit(attacker, target);
            }
            logChargedHit(attacker, target, attackerElement, baseDamage, displayedReactionDamage, extraBonus,
                    chargedMultiplier, damageResult.isCrit(), critMultiplier, boostedDamage, totalDisplayedDamage);
            utils.sendMessage(attacker, "§6【重击】", String.format("§e重击触发！倍率：§c%.1fx", chargedHandler.getChargedDamageMultiplier(attacker)));
        }

        // 消耗元素（根据反应类型调整消耗量）
        if (chargedHandler != null) {
            chargedHandler.onAttackResolved(attacker, target, chargedAttack, plungeAttack, attackerElement);
        }
        if (plugin.getYelanSkillHandler() != null && chargedHandler != plugin.getYelanSkillHandler()) {
            plugin.getYelanSkillHandler().onAttackResolved(attacker, target, chargedAttack, plungeAttack, attackerElement);
        }
        plugin.getWeaponManager().onAttackHit(
                attacker,
                target,
                plungeAttack ? WeaponAttackType.PLUNGE : (chargedAttack ? WeaponAttackType.CHARGED : WeaponAttackType.NORMAL),
                attackerElement
        );
        reactionManager.consumeReactionAuras(target, reactionResult, attackerElement);
        int duration = configParser.parseInt("effects.element_duration", attacker, 200);
        reactionManager.applyTriggerAura(target, attackerElement, reactionResult, duration, triggerAuraAmount);
        reactionManager.postProcessReactionState(target, reactionResult);

        if (plugin.shouldLogCombatSummary()) {
            plugin.getLogger().info(String.format("[%s] %s 攻击 %s - 总伤：%.1f%s",
                    attackerElement.replace("element_", ""), attacker.getName(), target.getName(), totalDisplayedDamage,
                    chargedAttack ? " [重击]" : ""));
        }

        // ========== 只有在非连锁情况下才触发草种子 ==========
        if (triggerSeed && reactionManager.shouldTriggerSeedReaction(target, attackerElement, reactionResult)) {
            reactionManager.triggerSeedReaction(attacker, target.getLocation(), attackerElement);
        }
    }

    private ElementReactionManager.AttachmentProfile resolveAttackAttachmentProfile(Player attacker, boolean chargedAttack,
                                                                                    boolean plungeAttack, CharacterSkillHandler chargedHandler,
                                                                                    String attachmentSectionOverride,
                                                                                    String reactionGroupOverride) {
        WeaponAttackType attackType = plungeAttack
                ? WeaponAttackType.PLUNGE
                : (chargedAttack ? WeaponAttackType.CHARGED : WeaponAttackType.NORMAL);
        CharacterType activeCharacter = null;
        if (plugin.getCharacterResolver() != null && attacker != null) {
            activeCharacter = plugin.getCharacterResolver().resolveCharacter(attacker);
        }
        if (activeCharacter == null && chargedHandler != null) {
            activeCharacter = chargedHandler.getCharacterType();
        }

        CharacterSkillConfig characterConfig = activeCharacter != null
                ? plugin.getCharacterConfig(activeCharacter)
                : (chargedHandler != null ? chargedHandler.getConfig() : null);
        String actorKey = activeCharacter != null
                ? activeCharacter.getId()
                : (chargedHandler != null ? chargedHandler.getCharacterType().getId() : "generic");
        String sectionPath = (attachmentSectionOverride != null && !attachmentSectionOverride.isBlank())
                ? attachmentSectionOverride
                : resolveAttackAttachmentSection(characterConfig, chargedAttack, plungeAttack);
        String fallbackGroup = (reactionGroupOverride != null && !reactionGroupOverride.isBlank())
                ? reactionGroupOverride
                : (chargedAttack ? "charged" : (plungeAttack ? "plunge" : "normal"));
        double fallbackAuraAmount = getDefaultAttackReactionAuraAmount(attackType);
        String reactionGroup = characterConfig != null
                ? characterConfig.getAttachmentGroup(sectionPath, fallbackGroup)
                : fallbackGroup;
        double auraAmount = characterConfig != null
                ? characterConfig.getAttachmentAuraAmount(sectionPath, fallbackAuraAmount)
                : fallbackAuraAmount;
        boolean icdEnabled = characterConfig != null
                ? characterConfig.getAttachmentIcdEnabled(
                sectionPath,
                configParser.parseBoolean("attachment.standard-icd.enabled", attacker, true)
        )
                : configParser.parseBoolean("attachment.standard-icd.enabled", attacker, true);
        long icdWindowMs = characterConfig != null
                ? characterConfig.getAttachmentIcdWindowMs(
                sectionPath,
                configParser.parseInt("attachment.standard-icd.window-ms", attacker, 2500)
        )
                : configParser.parseInt("attachment.standard-icd.window-ms", attacker, 2500);
        int icdHits = characterConfig != null
                ? characterConfig.getAttachmentIcdHits(
                sectionPath,
                configParser.parseInt("attachment.standard-icd.hits", attacker, 3)
        )
                : configParser.parseInt("attachment.standard-icd.hits", attacker, 3);
        int icdOffset = characterConfig != null
                ? characterConfig.getAttachmentIcdOffset(
                sectionPath,
                configParser.parseInt("attachment.standard-icd.offset", attacker, 0)
        )
                : configParser.parseInt("attachment.standard-icd.offset", attacker, 0);
        return new ElementReactionManager.AttachmentProfile(
                actorKey + ":" + reactionGroup,
                auraAmount,
                icdEnabled,
                icdWindowMs,
                icdHits,
                icdOffset
        );
    }

    private String resolveAttackAttachmentSection(CharacterSkillConfig config, boolean chargedAttack, boolean plungeAttack) {
        if (chargedAttack) {
            return "charged";
        }
        if (plungeAttack) {
            if (config != null) {
                if (config.contains("combat.plunge")) {
                    return "combat.plunge";
                }
                if (config.contains("plunge")) {
                    return "plunge";
                }
            }
            return "plunge";
        }
        return "combat.normal";
    }

    private double getDefaultAttackReactionAuraAmount(WeaponAttackType attackType) {
        String path = switch (attackType) {
            case NORMAL -> "attachment.aura.normal";
            case CHARGED -> "attachment.aura.charged";
            case SKILL -> "attachment.aura.skill";
            case BURST -> "attachment.aura.burst";
            case PLUNGE -> "attachment.aura.plunge";
        };
        return plugin.parseConfigDouble(path, null, 1.0);
    }

    private String resolveProjectileAttachmentSection(YelanSkillHandler.BowShotContext yelanShotContext) {
        if (yelanShotContext == null || !yelanShotContext.chargedAttack()) {
            return null;
        }
        return yelanShotContext.breakthroughShot() ? "breakthrough" : "charged";
    }

    private String resolveProjectileAttachmentGroup(YelanSkillHandler.BowShotContext yelanShotContext) {
        if (yelanShotContext == null || !yelanShotContext.chargedAttack()) {
            return null;
        }
        return yelanShotContext.breakthroughShot() ? "breakthrough" : "charged";
    }

    private void executeChargedPathAttack(EntityDamageByEntityEvent event, Player attacker,
                                          LivingEntity primaryTarget, ItemStack handItem,
                                          String attackerElement, boolean triggerSeed,
                                          CharacterSkillHandler chargedHandler) {
        if (event != null) {
            event.setCancelled(true);
        }

        List<LivingEntity> targets = collectChargedPathTargets(attacker, primaryTarget, chargedHandler);
        if (targets.isEmpty() && primaryTarget != null && isValidChargedTarget(attacker, primaryTarget)) {
            targets.add(primaryTarget);
        }
        if (targets.isEmpty()) {
            return;
        }

        boolean firstHit = true;
        for (LivingEntity target : targets) {
            executeElementAttack(null, attacker, target, handItem, attackerElement, triggerSeed, true, false, chargedHandler, firstHit);
            firstHit = false;
        }
    }

    private List<LivingEntity> collectChargedPathTargets(Player attacker, LivingEntity primaryTarget,
                                                         CharacterSkillHandler chargedHandler) {
        double pathLength = Math.max(0.5, chargedHandler.getChargedPathLength(attacker));
        double pathRadius = Math.max(0.3, chargedHandler.getChargedPathRadius(attacker));
        int maxTargets = Math.max(1, chargedHandler.getChargedMaxTargets(attacker));

        Location start = attacker.getLocation().clone().add(0, 0.9, 0);
        Vector direction = attacker.getLocation().getDirection().clone().setY(0);
        if ((direction.lengthSquared() <= 0.0001) && primaryTarget != null) {
            direction = getHorizontalDirection(attacker, primaryTarget);
        } else if (primaryTarget != null) {
            Vector toTarget = getHorizontalDirection(attacker, primaryTarget);
            if (toTarget.lengthSquared() > 0.0001) {
                direction = toTarget;
            }
        }
        if (direction.lengthSquared() <= 0.0001) {
            return new ArrayList<>();
        }

        Vector forward = direction.normalize();
        Location end = start.clone().add(forward.clone().multiply(pathLength));
        Map<LivingEntity, Double> candidates = new HashMap<>();
        Collection<Entity> nearby = attacker.getWorld().getNearbyEntities(
                attacker.getLocation(),
                pathLength + pathRadius,
                Math.max(2.0, pathRadius + 1.0),
                pathLength + pathRadius
        );

        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity living) || !isValidChargedTarget(attacker, living)) {
                continue;
            }

            double projection = projectionAlongSegment(start, end, living);
            if (projection < 0.0 || projection > pathLength) {
                continue;
            }
            if (distanceSquaredToSegment(start, end, living) > (pathRadius * pathRadius)) {
                continue;
            }
            candidates.put(living, projection);
        }

        if (primaryTarget != null && isValidChargedTarget(attacker, primaryTarget)) {
            candidates.putIfAbsent(primaryTarget, Math.max(0.0, Math.min(pathLength, projectionAlongSegment(start, end, primaryTarget))));
        }

        List<Map.Entry<LivingEntity, Double>> sorted = new ArrayList<>(candidates.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        List<LivingEntity> targets = new ArrayList<>();
        for (Map.Entry<LivingEntity, Double> entry : sorted) {
            targets.add(entry.getKey());
            if (targets.size() >= maxTargets) {
                break;
            }
        }
        return targets;
    }

    private double projectionAlongSegment(Location start, Location end, LivingEntity target) {
        Vector segment = end.toVector().subtract(start.toVector());
        double segmentLengthSquared = segment.lengthSquared();
        if (segmentLengthSquared <= 0.0001) {
            return 0.0;
        }

        Vector point = getTargetCenter(target).subtract(start.toVector());
        double t = point.dot(segment) / segmentLengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.sqrt(segmentLengthSquared) * t;
    }

    private double distanceSquaredToSegment(Location start, Location end, LivingEntity target) {
        Vector startVec = start.toVector();
        Vector segment = end.toVector().subtract(startVec);
        double segmentLengthSquared = segment.lengthSquared();
        if (segmentLengthSquared <= 0.0001) {
            return getTargetCenter(target).distanceSquared(startVec);
        }

        Vector point = getTargetCenter(target).subtract(startVec);
        double t = point.dot(segment) / segmentLengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        Vector closest = startVec.clone().add(segment.multiply(t));
        return getTargetCenter(target).distanceSquared(closest);
    }

    private Vector getTargetCenter(LivingEntity target) {
        return target.getLocation().clone()
                .add(0, Math.min(1.0, target.getHeight() * 0.5), 0)
                .toVector();
    }

    private boolean shouldBypassManualDamage(Player attacker, LivingEntity target) {
        if (!target.hasMetadata(MANUAL_DAMAGE_BYPASS_META)) {
            return false;
        }

        String attackerId = attacker.getUniqueId().toString();
        boolean bypass = target.getMetadata(MANUAL_DAMAGE_BYPASS_META).stream()
                .filter(meta -> meta.getOwningPlugin() == plugin)
                .anyMatch(meta -> attackerId.equals(meta.asString()));

        if (bypass) {
            target.removeMetadata(MANUAL_DAMAGE_BYPASS_META, plugin);
        }

        return bypass;
    }

    private void markManualDamageBypass(Player attacker, LivingEntity target) {
        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, attacker.getUniqueId().toString()));
    }

    private boolean isPlungeAttack(Player attacker, LivingEntity target) {
        if (attacker == null) {
            return false;
        }
        if (target == null) {
            return false;
        }
        if (attacker.isOnGround()) {
            return false;
        }
        if (attacker.isInWater()) {
            return false;
        }
        if (attacker.isGliding()) {
            return false;
        }
        if (hasSupportBlockBelow(attacker)) {
            return false;
        }
        double attackerY = attacker.getLocation().getY();
        double targetY = target.getLocation().getY();
        return attackerY - targetY >= 2.0;
    }

    private double getPlungeDamageMultiplier(Player attacker, CharacterSkillHandler handler) {
        double baseMultiplier = 3.0;
        double bonusMultiplier = handler == null ? 1.0 : Math.max(0.0, handler.getPlungeDamageBonusMultiplier(attacker));
        return baseMultiplier * bonusMultiplier;
    }

    private boolean hasSupportBlockBelow(Player attacker) {
        Location below = attacker.getLocation().clone().subtract(0.0, 0.2, 0.0);
        Material material = below.getBlock().getType();
        return material.isSolid();
    }

private boolean isChargedAttack(Player attacker, ItemStack handItem) {
    HuTaoStateManager huTao = plugin.getHuTaoStateManager();
    if (huTao == null || !plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.HUTAO)) {
        return false;
    }
    if (!huTaoConfig().getBoolean("charged.enabled", true)) {
        return false;
    }
    boolean requireSneaking = huTaoConfig().getBoolean("charged.require-sneaking", true);
    return !requireSneaking || attacker.isSneaking();
}

private LivingEntity findChargedAttackTarget(Player attacker) {
    if (!huTaoConfig().getBoolean("charged.targeting.enabled", true)) {
        return null;
    }

    double maxDistance = huTaoConfig().getDouble("charged.targeting.max-distance", 5.0);
    double minDistance = huTaoConfig().getDouble("charged.targeting.min-distance-for-raycast", 3.2);
    double hitboxExpand = huTaoConfig().getDouble("charged.targeting.hitbox-expand", 0.45);

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
                    && (!(living instanceof ArmorStand armorStand) || !armorStand.hasMetadata(BLOOM_SEED_KEY))
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

private boolean tryConsumeChargedAttack(Player attacker) {
    long remain = getRemainingChargedCooldown(attacker);
    if (remain > 0) {
        String msg = plugin.getSkillsConfig().getString(
                huTaoConfig().messagePath("charged-cooldown"),
                "&c【胡桃】重击冷却中，还剩 {time} 秒！"
        );
        attacker.sendMessage(ChatColor.translateAlternateColorCodes('&',
                msg.replace("{time}", String.format("%.1f", remain / 1000.0))));
        return false;
    }

    int hungerCost = huTaoConfig().getInt("charged.hunger-cost", 2);
    if (attacker.getFoodLevel() < hungerCost) {
        String msg = plugin.getSkillsConfig().getString(
                huTaoConfig().messagePath("charged-no-hunger"),
                "&c【胡桃】饥饿值不足，无法施放重击！"
        );
        attacker.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        return false;
    }

    attacker.setFoodLevel(Math.max(0, attacker.getFoodLevel() - hungerCost));
    chargedAttackCooldowns.put(attacker.getUniqueId(),
            System.currentTimeMillis() + huTaoConfig().getLong("charged.cooldown-ms", 1000L));
    return true;
}

private long getRemainingChargedCooldown(Player attacker) {
    Long end = chargedAttackCooldowns.get(attacker.getUniqueId());
    if (end == null) {
        return 0L;
    }
    long remain = end - System.currentTimeMillis();
    if (remain <= 0L) {
        chargedAttackCooldowns.remove(attacker.getUniqueId());
        return 0L;
    }
    return remain;
}

private double getChargedDamageMultiplier() {
    return huTaoConfig().getDouble("charged.damage-multiplier", 2.0);
}


private void applyChargedAttackEffects(Player attacker, LivingEntity target) {
    attacker.getWorld().spawnParticle(Particle.CRIT, target.getLocation().clone().add(0, 1.0, 0), 8, 0.25, 0.3, 0.25, 0.02);
    attacker.getWorld().spawnParticle(Particle.FLAME, target.getLocation().clone().add(0, 1.0, 0), 12, 0.25, 0.35, 0.25, 0.02);
    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.9f);

    HuTaoStateManager huTao = plugin.getHuTaoStateManager();
    if (huTao != null && huTao.isActive(attacker) && plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.HUTAO)) {
        plugin.getHuTaoBloodBlossomManager().applyOrRefresh(attacker, target);
    }
}

private void dashChargedAttack(Player attacker, LivingEntity target) {
    if (!huTaoConfig().getBoolean("charged.dash.enabled", true)) {
        return;
    }

    Vector direction = null;
    double dashStrength = huTaoConfig().getDouble("charged.dash.strength", 0.65);

    if (target != null
            && huTaoConfig().getBoolean("charged.dash.direct-to-target", true)
            && target.isValid()
            && !target.isDead()) {
        double directRange = huTaoConfig().getDouble("charged.dash.direct-range", 5.0);
        double stopDistance = huTaoConfig().getDouble("charged.dash.stop-distance", 1.1);
        double distanceMultiplier = huTaoConfig().getDouble("charged.dash.distance-multiplier", 0.42);

        Vector toTarget = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
        double distance = toTarget.length();
        if (distance > 0.0001 && distance <= directRange && attacker.hasLineOfSight(target)) {
            direction = toTarget.clone().setY(0);
            double scaledStrength = Math.max(dashStrength, Math.max(0.1, distance - stopDistance) * distanceMultiplier);
            dashStrength = Math.min(huTaoConfig().getDouble("charged.dash.max-strength", 1.6), scaledStrength);
        }
    }

    if (direction == null || direction.lengthSquared() <= 0.0001) {
        direction = attacker.getLocation().getDirection().clone().setY(0);
    }

    if (direction.lengthSquared() <= 0.0001) {
        return;
    }

    Vector velocity = direction.normalize().multiply(dashStrength);
    velocity.setY(Math.max(attacker.getVelocity().getY(), huTaoConfig().getDouble("charged.dash.vertical", 0.08)));

    attacker.setVelocity(velocity);
    attacker.getWorld().spawnParticle(Particle.FLAME, attacker.getLocation().clone().add(0, 1.0, 0), 8, 0.2, 0.2, 0.2, 0.01);
}

private void beginPendingChargedAttack(Player attacker, ItemStack handItem, String attackerElement, LivingEntity target,
                                      CharacterSkillHandler chargedHandler) {
    int windowTicks = chargedHandler.getChargedHitWindowTicks();
    long expireAt = System.currentTimeMillis() + (windowTicks * 50L);
    PendingChargedAttack pending = new PendingChargedAttack(
            attacker.getUniqueId(),
            handItem == null ? null : handItem.clone(),
            attackerElement,
            target == null ? null : target.getUniqueId(),
            chargedHandler.getCharacterType(),
            expireAt
    );
    pendingChargedAttacks.put(attacker.getUniqueId(), pending);
    chargedHandler.beginChargedAttack(attacker, target);
    logChargedStart(attacker, target, windowTicks, chargedHandler);
}

private void tickPendingChargedAttacks() {
    if (pendingChargedAttacks.isEmpty()) {
        return;
    }

    long now = System.currentTimeMillis();
    Iterator<Map.Entry<UUID, PendingChargedAttack>> iterator = pendingChargedAttacks.entrySet().iterator();
    while (iterator.hasNext()) {
        Map.Entry<UUID, PendingChargedAttack> entry = iterator.next();
        UUID attackerId = entry.getKey();
        PendingChargedAttack pending = entry.getValue();
        Player attacker = Bukkit.getPlayer(attackerId);

        if (attacker == null || !attacker.isOnline() || attacker.isDead()) {
            iterator.remove();
            continue;
        }

        if (pending.expireAtMillis() < now) {
            iterator.remove();
            logChargedMiss(attacker, "window_expired");
            continue;
        }
        if (!plugin.isCharacterModeActive(attacker)) {
            iterator.remove();
            logChargedMiss(attacker, "weapon_mode_changed");
            continue;
        }
        if (plugin.getCharacterResolver().resolveCharacter(attacker) != pending.characterType()) {
            iterator.remove();
            logChargedMiss(attacker, "character_changed");
            continue;
        }

        CharacterSkillHandler chargedHandler = plugin.getCharacterSkillEngine().get(pending.characterType());
        if (chargedHandler == null) {
            iterator.remove();
            continue;
        }

        LivingEntity target = resolvePendingChargedTarget(attacker, pending);
        if (target == null) {
            continue;
        }

        ItemStack handItem = pending.handItemSnapshot() != null ? pending.handItemSnapshot().clone() : attacker.getInventory().getItemInMainHand();
        String attackerElement = pending.attackerElement() != null ? pending.attackerElement() : getAttackElement(attacker, handItem);

        // 关键修复：先把这次重击窗口从 map 里移除，再手动结算伤害。
        // 这样同 tick 后续触发的原版伤害事件，就不会再把同一窗口消费第二次。
        iterator.remove();

        if (attackerElement == null) {
            continue;
        }

        if (chargedHandler.usesChargedPathAttack(attacker)) {
            executeChargedPathAttack(null, attacker, target, handItem, attackerElement, true, chargedHandler);
        } else {
            executeElementAttack(null, attacker, target, handItem, attackerElement, true, true, false, chargedHandler);
        }
    }
}

    private PendingChargedAttack consumePendingChargedHit(Player attacker, LivingEntity target) {
        PendingChargedAttack pending = pendingChargedAttacks.get(attacker.getUniqueId());
        if (pending == null) {
            return null;
        }
        if (!plugin.isCharacterModeActive(attacker)) {
            pendingChargedAttacks.remove(attacker.getUniqueId());
            logChargedMiss(attacker, "weapon_mode_changed");
            return null;
        }
        if (plugin.getCharacterResolver().resolveCharacter(attacker) != pending.characterType()) {
            pendingChargedAttacks.remove(attacker.getUniqueId());
            logChargedMiss(attacker, "character_changed");
            return null;
        }

        if (pending.expireAtMillis() < System.currentTimeMillis()) {
            pendingChargedAttacks.remove(attacker.getUniqueId());
        logChargedMiss(attacker, "window_expired");
        return null;
    }

    if (!isChargedHitCandidate(attacker, target, pending.lockedTargetId(), pending.characterType())) {
        return null;
    }

    pendingChargedAttacks.remove(attacker.getUniqueId());
    return pending;
}

private LivingEntity resolvePendingChargedTarget(Player attacker, PendingChargedAttack pending) {
    LivingEntity lockedTarget = getLockedChargedTarget(pending.lockedTargetId());
    if (lockedTarget != null && isChargedHitCandidate(attacker, lockedTarget, pending.lockedTargetId(), pending.characterType())) {
        return lockedTarget;
    }

    return findAutoChargedTarget(attacker, pending.characterType());
}

private LivingEntity getLockedChargedTarget(UUID targetId) {
    if (targetId == null) {
        return null;
    }
    Entity entity = Bukkit.getEntity(targetId);
    if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
        return living;
    }
    return null;
}

private LivingEntity findAutoChargedTarget(Player attacker, CharacterType characterType) {
    CharacterSkillHandler chargedHandler = plugin.getCharacterSkillEngine().get(characterType);
    CharacterSkillConfig config = plugin.getCharacterConfig(characterType);
    double range = chargedHandler != null ? chargedHandler.getChargedAutoHitRange() : config.getDouble("charged.auto-hit-range", 2.6);
    double maxAngle = chargedHandler != null ? chargedHandler.getChargedAutoHitConeDegrees() : config.getDouble("charged.auto-hit-cone-degrees", 100.0);
    double requiredDot = Math.cos(Math.toRadians(maxAngle / 2.0));
    Vector forward = attacker.getLocation().getDirection().clone().setY(0).normalize();

    LivingEntity best = null;
    double bestScore = Double.MAX_VALUE;
    Collection<Entity> nearby = attacker.getWorld().getNearbyEntities(attacker.getLocation(), range, 1.75, range);
    for (Entity entity : nearby) {
        if (!(entity instanceof LivingEntity living)) {
            continue;
        }
        if (!isValidChargedTarget(attacker, living)) {
            continue;
        }

        Vector toTarget = getHorizontalDirection(attacker, living);
        if (toTarget.lengthSquared() <= 0.0001) {
            continue;
        }
        double dot = forward.dot(toTarget.clone().normalize());
        if (dot < requiredDot) {
            continue;
        }

        double distance = attacker.getLocation().distance(living.getLocation());
        double score = distance - (dot * 0.35);
        if (score < bestScore) {
            bestScore = score;
            best = living;
        }
    }
    return best;
}

private boolean isChargedHitCandidate(Player attacker, LivingEntity target, UUID lockedTargetId, CharacterType characterType) {
    if (!isValidChargedTarget(attacker, target)) {
        return false;
    }

    CharacterSkillHandler chargedHandler = plugin.getCharacterSkillEngine().get(characterType);
    CharacterSkillConfig config = plugin.getCharacterConfig(characterType);
    double range = chargedHandler != null ? chargedHandler.getChargedAutoHitRange() : config.getDouble("charged.auto-hit-range", 2.6);
    if (attacker.getLocation().distance(target.getLocation()) > range) {
        return false;
    }

    if (lockedTargetId != null && lockedTargetId.equals(target.getUniqueId())) {
        return true;
    }

    double maxAngle = chargedHandler != null ? chargedHandler.getChargedAutoHitConeDegrees() : config.getDouble("charged.auto-hit-cone-degrees", 100.0);
    double requiredDot = Math.cos(Math.toRadians(maxAngle / 2.0));
    Vector forward = attacker.getLocation().getDirection().clone().setY(0);
    Vector toTarget = getHorizontalDirection(attacker, target);
    if (forward.lengthSquared() <= 0.0001 || toTarget.lengthSquared() <= 0.0001) {
        return false;
    }

    return forward.normalize().dot(toTarget.normalize()) >= requiredDot;
}

private boolean isValidChargedTarget(Player attacker, LivingEntity target) {
    if (target == null || target.equals(attacker) || !target.isValid() || target.isDead()) {
        return false;
    }
    if (target instanceof ArmorStand armorStand && armorStand.hasMetadata(BLOOM_SEED_KEY)) {
        return false;
    }
    return attacker.hasLineOfSight(target);
}

private Vector getHorizontalDirection(Player attacker, LivingEntity target) {
    Location attackerLoc = attacker.getLocation().clone().add(0, 0.9, 0);
    Location targetLoc = target.getLocation().clone().add(0, Math.min(1.0, target.getHeight() * 0.5), 0);
    return targetLoc.toVector().subtract(attackerLoc.toVector()).setY(0);
}

private int getChargedHitWindowTicks() {
    return Math.max(1, huTaoConfig().getInt("charged.hit-window-ticks", 14));
}

private void logChargedStart(Player attacker, LivingEntity target, int windowTicks, CharacterSkillHandler chargedHandler) {
    if (!shouldLogChargedDebug()) {
        return;
    }
    CharacterSkillConfig config = chargedHandler.getConfig();
    plugin.getLogger().info(String.format(
            "[HuTao-Charged][START] player=%s window=%dt target=%s cooldown=%dms hunger=%d character=%s",
            attacker.getName(),
            windowTicks,
            target == null ? "none" : target.getName(),
            config.getLong("charged.cooldown-ms", 1000L),
            config.getInt("charged.hunger-cost", 2),
            chargedHandler.getCharacterType().getId()
    ));
}

private void logChargedHit(Player attacker, LivingEntity target, String attackerElement, double baseDamage,
                           double reactionExtraDamage, double extraBonus, double chargedMultiplier,
                           boolean crit, double critMultiplier, double boostedDamage, double finalDamage) {
    if (!shouldLogChargedDebug()) {
        return;
    }
    plugin.getLogger().info(String.format(
            "[HuTao-Charged][HIT] player=%s target=%s element=%s base=%.2f reaction=%.2f extra=%.3f charged=%.3f crit=%s critMul=%.3f boosted=%.2f final=%.2f",
            attacker.getName(),
            target.getName(),
            attackerElement,
            baseDamage,
            reactionExtraDamage,
            extraBonus,
            chargedMultiplier,
            crit,
            critMultiplier,
            boostedDamage,
            finalDamage
    ));
}

private void logChargedMiss(Player attacker, String reason) {
    if (!shouldLogChargedDebug()) {
        return;
    }
    plugin.getLogger().info(String.format("[HuTao-Charged][MISS] player=%s reason=%s", attacker.getName(), reason));
}

private boolean shouldLogChargedDebug() {
    return plugin.getConfig().getBoolean("logging.charged-debug", false);
}

private record PendingChargedAttack(UUID attackerId, ItemStack handItemSnapshot, String attackerElement,
                                    UUID lockedTargetId, CharacterType characterType, long expireAtMillis) {
}

    private CharacterSkillConfig huTaoConfig() {
        return plugin.getCharacterConfig(CharacterType.HUTAO);
    }

    private double getExtraBonuses(Player player, String attackerElement) {
        double bonus = 1.0;
        if (plugin.getConfig().getBoolean("damage_bonus.health_bonus.enabled", false)) {
            String formula = plugin.getConfig().getString("damage_bonus.health_bonus.formula", "1.0");
            bonus *= (1 + configParser.parseExpression(formula, player, 0));
        }
        if (plugin.getConfig().getBoolean("damage_bonus.food_bonus.enabled", false)) {
            String formula = plugin.getConfig().getString("damage_bonus.food_bonus.formula", "1.0");
            bonus *= (1 + configParser.parseExpression(formula, player, 0));
        }

        CharacterSkillHandler handler = plugin.getCharacterSkillEngine().resolveHandler(player, player.getInventory().getItemInMainHand());
        if (handler != null) {
            bonus *= handler.getElementDamageBonusMultiplier(player, attackerElement);
        }
        bonus *= plugin.getGlobalDamageBonusMultiplier(player, attackerElement);

        return bonus;
    }

    private String getAttackElement(Player attacker, ItemStack handItem) {
        String defaultElement = plugin.getActiveElement(attacker);
        CharacterSkillHandler handler = plugin.getCharacterSkillEngine().resolveHandler(attacker, handItem);
        if (handler == null) {
            return defaultElement != null ? defaultElement : (plugin.isCharacterModeActive(attacker) ? PlayerStats.PHYSICAL_KEY : null);
        }
        String resolvedElement = handler.resolveAttackElement(attacker, handItem, defaultElement);
        if (resolvedElement != null) {
            return resolvedElement;
        }
        return plugin.isCharacterModeActive(attacker) ? PlayerStats.PHYSICAL_KEY : null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (plugin.getYelanSkillHandler() != null) {
            plugin.getYelanSkillHandler().handleBowShoot(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (plugin.getYelanSkillHandler() != null) {
            if (event.getHitEntity() == null) {
                plugin.getYelanSkillHandler().clearBowShotContext(event.getEntity());
            } else {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getYelanSkillHandler().clearBowShotContext(event.getEntity()));
            }
        }
    }

    private boolean isYelanMeleeAttack(Player attacker) {
        return attacker != null
                && plugin.isCharacterModeActive(attacker)
                && plugin.getCharacterResolver().isSelectedCharacter(attacker, CharacterType.YELAN);
    }

    private String resolveIncomingDamageElement(EntityDamageEvent event, Player player) {
        String markedElement = reactionManager.consumeIncomingDamageElement(player);
        if (markedElement != null && !markedElement.isBlank()) {
            return markedElement;
        }

        if (event instanceof EntityDamageByEntityEvent damageByEntityEvent) {
            Entity damager = damageByEntityEvent.getDamager();
            if (damager instanceof Player attacker) {
                return getAttackElement(attacker, attacker.getInventory().getItemInMainHand());
            }
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player attacker) {
                return getAttackElement(attacker, attacker.getInventory().getItemInMainHand());
            }
        }

        return switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> FIRE_KEY;
            case LIGHTNING -> ELECTRO_KEY;
            case DROWNING -> WATER_KEY;
            case FREEZE -> ICE_KEY;
            default -> null;
        };
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player player) {
            Item item = e.getItem();

            if (plugin.getXianglingPepperManager() != null && plugin.getXianglingPepperManager().handlePickup(player, item)) {
                e.setCancelled(true);
                damageCalculator.invalidatePlayerStats(player);
                return;
            }

            boolean isCrystalChip = reactionManager.handleCrystalPickup(player, item);
            if (isCrystalChip) {
                e.setCancelled(true);
            }

            damageCalculator.invalidatePlayerStats(player);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        damageCalculator.invalidatePlayerStats(e.getPlayer());
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent e) {
        damageCalculator.invalidatePlayerStats(e.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getWeaponManager().rememberEquippedWeaponForSelectedCharacter(e.getPlayer()));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player player) {
            damageCalculator.invalidatePlayerStats(player);
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getWeaponManager().rememberEquippedWeaponForSelectedCharacter(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player) {
            if (plugin.getYelanSkillHandler() != null) {
                plugin.getYelanSkillHandler().markCombat(player);
            }
            if (plugin.getHuTaoStateManager() != null) {
                plugin.getHuTaoStateManager().applyInterruptionResistance(player, e);
            }
            String damageElement = resolveIncomingDamageElement(e, player);
            double finalDamage = reactionManager.onPlayerDamage(player, e.getDamage(), damageElement);
            e.setDamage(finalDamage);
        }
    }

}
