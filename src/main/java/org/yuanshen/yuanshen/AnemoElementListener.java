package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import static org.yuanshen.yuanshen.ElementConstant.*;

/**
 * 风元素监听器 - 实现原神风元素反应：扩散（所有元素）、聚怪
 */
public class AnemoElementListener implements Listener {
    // 配置化数值
    private double anemoBaseDamage;         // 风基础伤害
    private double swirlMultiplier;         // 扩散倍率
    private int pullRange;                  // 聚怪范围（方块）
    private int pullStrength;               // 聚怪强度
    private int elementDuration;            // 风元素附着时长
    private int swirlDuration;              // 扩散效果持续时长

    // 配置化文本
    private String msgSwirlFire;            // 扩散火提示
    private String msgSwirlWater;           // 扩散水提示
    private String msgSwirlIce;             // 扩散冰提示
    private String msgSwirlElectro;         // 扩散雷提示
    private String msgNormalDamage;         // 普通风伤提示
    private String msgAlreadyHasAnemo;      // 已附着风提示
    private String msgNoLore;               // 无Lore提示

    private String logSwirlFire;            // 扩散火日志
    private String logSwirlWater;           // 扩散水日志
    private String logSwirlIce;             // 扩散冰日志
    private String logSwirlElectro;         // 扩散雷日志
    private String logRemoveMark;           // 移除标记日志
    private String logExpireMark;           // 过期日志
    private String logConfigLoaded;         // 配置加载日志
    private String logConfigBaseDamage;     // 基础伤害配置日志
    private String logConfigSwirl;          // 扩散倍率配置日志
    private String logConfigElementDuration;// 元素时长配置日志

    private final Yuanshen plugin;

    public AnemoElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadAnemoConfig();
    }

    /**
     * 加载风元素配置 - 所有参数可配置，带默认值
     */
    private void loadAnemoConfig() {
        FileConfiguration config = plugin.getConfig();

        // ========== 数值配置项 ==========
        anemoBaseDamage = config.getDouble("anemo_element.base_damage", 5.0);        // 风基础伤害，默认5点
        swirlMultiplier = config.getDouble("anemo_element.swirl_multiplier", 1.2);   // 扩散倍率，默认1.2倍
        pullRange = config.getInt("anemo_element.pull_range", 5);                    // 聚怪范围，默认5格
        pullStrength = config.getInt("anemo_element.pull_strength", 1);              // 聚怪强度，默认1
        elementDuration = config.getInt("anemo_element.element_duration", 200);     // 风元素附着时长，默认200tick
        swirlDuration = config.getInt("anemo_element.swirl_duration", 100);         // 扩散效果时长，默认100tick

        // ========== 文本配置项 ==========
        msgSwirlFire = ChatColor.translateAlternateColorCodes('&',
                config.getString("anemo_element.messages.swirl_fire", "&7【扩散-火】&f触发！造成%s点范围伤害，附加燃烧效果！"));
        msgSwirlWater = ChatColor.translateAlternateColorCodes('&',
                config.getString("anemo_element.messages.swirl_water", "&7【扩散-水】&f触发！造成%s点范围伤害，附加潮湿效果！"));
        msgSwirlIce = ChatColor.translateAlternateColorCodes('&',
                config.getString("anemo_element.messages.swirl_ice", "&7【扩散-冰】&f触发！造成%s点范围伤害，附加缓速效果！"));
        msgSwirlElectro = ChatColor.translateAlternateColorCodes('&',
                config.getString("anemo_element.messages.swirl_electro", "&7【扩散-雷】&f触发！造成%s点范围伤害，附加感电效果！"));
        msgNormalDamage = ChatColor.translateAlternateColorCodes('&',
                config.getString("anemo_element.messages.normal_damage", "&7触发！额外造成%s点伤害并给敌方附着风元素（聚怪）"));
        msgAlreadyHasAnemo = ChatColor.translateAlternateColorCodes('&',
                config.getString("anemo_element.messages.already_has_anemo", "&7敌方已附着风元素，无额外伤害！"));
        msgNoLore = config.getString("anemo_element.messages.no_lore", "[风元素] 玩家%s手持物品无「风元素」Lore，不触发效果");

        // 日志配置
        logSwirlFire = config.getString("anemo_element.logs.swirl_fire", "[风元素-扩散火] 目标%s火元素已扩散，范围伤害：%s");
        logSwirlWater = config.getString("anemo_element.logs.swirl_water", "[风元素-扩散水] 目标%s水元素已扩散，范围伤害：%s");
        logSwirlIce = config.getString("anemo_element.logs.swirl_ice", "[风元素-扩散冰] 目标%s冰元素已扩散，范围伤害：%s");
        logSwirlElectro = config.getString("anemo_element.logs.swirl_electro", "[风元素-扩散雷] 目标%s雷元素已扩散，范围伤害：%s");
        logRemoveMark = config.getString("anemo_element.logs.remove_mark", "[风元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("anemo_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("anemo_element.logs.config_loaded", "风元素配置加载完成：");
        logConfigBaseDamage = config.getString("anemo_element.logs.config_base_damage", "基础伤害：%s");
        logConfigSwirl = config.getString("anemo_element.logs.config_swirl_multiplier", "扩散倍率：%s");
        logConfigElementDuration = config.getString("anemo_element.logs.config_element_duration", "元素过期时长：%s tick");

        // 打印配置日志
        plugin.getLogger().info(logConfigLoaded);
        plugin.getLogger().info(String.format(logConfigBaseDamage, anemoBaseDamage));
        plugin.getLogger().info(String.format(logConfigSwirl, swirlMultiplier));
        plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    @EventHandler
    public void onPlayerAttackWithAnemo(EntityDamageByEntityEvent e) {
        // 1. 基础校验：攻击者是玩家、被攻击者是活体生物
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) {
            return;
        }

        // 2. 检测风元素Lore（和雷元素逻辑对齐）
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasAnemoLore = checkItemLore(handItem, ANEMO_LORE);
        if (!hasAnemoLore) {
            plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        // 3. 检测目标已附着的元素
        boolean hasFire = hasElement(target, FIRE_KEY);
        boolean hasWater = hasElement(target, WATER_KEY);
        boolean hasIce = hasElement(target, ICE_KEY);
        boolean hasElectro = hasElement(target, ELECTRO_KEY);
        boolean hasAnemo = hasElement(target, ANEMO_KEY);

        double totalDamage = e.getDamage();
        boolean needAddAnemoMark = true;

        // ========== 核心：风元素扩散反应逻辑 ==========
        // 1. 扩散-火
        if (hasFire) {
            double swirlDamage = anemoBaseDamage * swirlMultiplier;
            totalDamage += swirlDamage;
            removeElement(target, FIRE_KEY);

            // 附加燃烧效果（模拟扩散）
            target.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, swirlDuration, 0)); // 反向模拟燃烧（实际应扣血，这里简化）
            // 范围扩散伤害（遍历范围内实体）
            applySwirlAreaDamage(target.getLocation(), swirlDamage / 2, FIRE_KEY);

            attacker.sendMessage(ANEMO_TAG + String.format(msgSwirlFire, swirlDamage));
            plugin.getLogger().info(String.format(logSwirlFire, target.getName(), swirlDamage));
            needAddAnemoMark = false;
        }
        // 2. 扩散-水
        else if (hasWater) {
            double swirlDamage = anemoBaseDamage * swirlMultiplier;
            totalDamage += swirlDamage;
            removeElement(target, WATER_KEY);

            // 附加潮湿效果（简化：缓速）
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, swirlDuration, 1));
            // 范围扩散伤害
            applySwirlAreaDamage(target.getLocation(), swirlDamage / 2, WATER_KEY);

            attacker.sendMessage(ANEMO_TAG + String.format(msgSwirlWater, swirlDamage));
            plugin.getLogger().info(String.format(logSwirlWater, target.getName(), swirlDamage));
            needAddAnemoMark = false;
        }
        // 3. 扩散-冰
        else if (hasIce) {
            double swirlDamage = anemoBaseDamage * swirlMultiplier;
            totalDamage += swirlDamage;
            removeElement(target, ICE_KEY);

            // 附加缓速效果
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, swirlDuration, 2));
            // 范围扩散伤害
            applySwirlAreaDamage(target.getLocation(), swirlDamage / 2, ICE_KEY);

            attacker.sendMessage(ANEMO_TAG + String.format(msgSwirlIce, swirlDamage));
            plugin.getLogger().info(String.format(logSwirlIce, target.getName(), swirlDamage));
            needAddAnemoMark = false;
        }
        // 4. 扩散-雷
        else if (hasElectro) {
            double swirlDamage = anemoBaseDamage * swirlMultiplier;
            totalDamage += swirlDamage;
            removeElement(target, ELECTRO_KEY);

            // 附加感电效果（简化：短暂伤害）
            startElectroSwirlTask(target, swirlDuration, 20, swirlDamage / 5);
            // 范围扩散伤害
            applySwirlAreaDamage(target.getLocation(), swirlDamage / 2, ELECTRO_KEY);

            attacker.sendMessage(ANEMO_TAG + String.format(msgSwirlElectro, swirlDamage));
            plugin.getLogger().info(String.format(logSwirlElectro, target.getName(), swirlDamage));
            needAddAnemoMark = false;
        }
        // 5. 无其他元素 + 无风元素 → 普通风伤 + 聚怪
        else if (!hasAnemo) {
            totalDamage += anemoBaseDamage;
            // 触发聚怪效果
            pullNearbyEntities(target.getLocation(), pullRange, pullStrength);

            attacker.sendMessage(ANEMO_TAG + String.format(msgNormalDamage, anemoBaseDamage));
        }
        // 6. 已有风元素 → 无额外伤害
        else {
            attacker.sendMessage(ANEMO_TAG + msgAlreadyHasAnemo);
            needAddAnemoMark = false;
        }

        // 7. 设置最终伤害
        e.setDamage(totalDamage);

        // 8. 附着风元素（如果需要）
        if (needAddAnemoMark) {
            addElementWithExpire(target, ANEMO_KEY, elementDuration);
        }

        // 通用日志（和雷元素对齐）
        plugin.getLogger().info(String.format(
                "[风元素] 目标：%s | 火：%s | 水：%s | 冰：%s | 雷：%s | 风：%s | 最终伤害：%.1f",
                target.getName(), hasFire, hasWater, hasIce, hasElectro, hasAnemo, totalDamage
        ));
    }

    // ========== 风元素特有工具方法 ==========
    /**
     * 聚怪效果：将范围内的实体拉向目标位置
     */
    private void pullNearbyEntities(Location center, int range, int strength) {
        for (LivingEntity entity : center.getWorld().getLivingEntities()) {
            if (entity.getLocation().distance(center) <= range && !entity.isDead() && entity.isValid()) {
                // 计算拉向中心的向量
                Vector pullVector = center.toVector().subtract(entity.getLocation().toVector()).normalize().multiply(strength / 10.0);
                entity.setVelocity(pullVector);
            }
        }
    }

    /**
     * 扩散范围伤害：对目标位置周围的实体造成扩散伤害
     */
    private void applySwirlAreaDamage(Location center, double damage, String elementKey) {
        for (LivingEntity entity : center.getWorld().getLivingEntities()) {
            if (entity.getLocation().distance(center) <= 3 && !entity.isDead() && entity.isValid()) {
                entity.damage(damage);
                // 给范围实体附加对应元素效果
                if (elementKey.equals(FIRE_KEY)) {
                    entity.setFireTicks(20);
                } else if (elementKey.equals(ICE_KEY)) {
                    entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1));
                } else if (elementKey.equals(ELECTRO_KEY)) {
                    entity.damage(damage / 2); // 额外感电伤害
                }
            }
        }
    }

    /**
     * 扩散雷的持续伤害任务（参考雷元素感电逻辑）
     */
    private void startElectroSwirlTask(LivingEntity target, int duration, int interval, double damagePerTick) {
        new BukkitRunnable() {
            int remainingTicks = duration;

            @Override
            public void run() {
                if (remainingTicks <= 0 || target.isDead() || !target.isValid()) {
                    this.cancel();
                    return;
                }
                if (remainingTicks % interval == 0) {
                    target.damage(damagePerTick);
                }
                remainingTicks -= 1;
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    // ========== 通用工具方法（和雷元素对齐） ==========
    private boolean checkItemLore(ItemStack item, String keyword) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        for (String loreLine : meta.getLore()) {
            if (ChatColor.stripColor(loreLine).contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasElement(LivingEntity target, String elementKey) {
        try {
            return target.hasMetadata(elementKey) && target.getMetadata(elementKey).get(0).asBoolean();
        } catch (Exception ex) {
            plugin.getLogger().warning("[风元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
            return false;
        }
    }

    private void removeElement(LivingEntity target, String elementKey) {
        if (target.hasMetadata(elementKey)) {
            target.removeMetadata(elementKey, plugin);
            plugin.getLogger().info(String.format(logRemoveMark, target.getName(), elementKey));
        }
    }

    private void addElementWithExpire(LivingEntity target, String elementKey, int durationTicks) {
        FixedMetadataValue elementMark = new FixedMetadataValue(plugin, true);
        target.setMetadata(elementKey, elementMark);

        new BukkitRunnable() {
            @Override
            public void run() {
                removeElement(target, elementKey);
                plugin.getLogger().info(String.format(logExpireMark, target.getName(), elementKey));
            }
        }.runTaskLater(plugin, durationTicks);
    }
}