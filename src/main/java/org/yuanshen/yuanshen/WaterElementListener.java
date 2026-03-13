package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
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

import static org.yuanshen.yuanshen.ElementConstant.*;

/**
 * 水元素监听器 - 实现原神水元素反应：蒸发、冻结、感电、绽放、潮湿
 */
public class WaterElementListener implements Listener {
    // 配置化数值
    private double waterBaseDamage;         // 水元素基础伤害
    private double evaporationMultiplier;   // 蒸发倍率（水打火）
    private double electroChargeMultiplier; // 感电倍率
    private double bloomMultiplier;         // 绽放倍率
    private int slowDuration;               // 潮湿减速时长
    private int slowLevel;                  // 潮湿减速等级
    private int freezeDuration;             // 冻结时长
    private int freezeLevel;                // 冻结等级（SLowness等级10=完全冻结）
    private int electroTickInterval;        // 感电触发间隔
    private int electroDuration;            // 感电持续时长
    private int elementDuration;            // 水元素附着时长

    // 配置化文本
    private String msgEvaporation;          // 蒸发提示
    private String msgFreeze;               // 冻结提示
    private String msgElectroCharge;        // 感电提示
    private String msgBloom;                // 绽放提示
    private String msgNormalDamage;         // 普通水伤提示
    private String msgAlreadyHasWater;      // 已附着水提示
    private String msgExtinguishFire;       // 灭火提示
    private String msgNoLore;               // 无Lore提示

    private String logEvaporation;          // 蒸发日志
    private String logFreeze;               // 冻结日志
    private String logElectroCharge;        // 感电日志
    private String logBloom;                // 绽放日志
    private String logRemoveMark;           // 移除标记日志
    private String logExpireMark;           // 过期日志
    private String logConfigLoaded;         // 配置加载日志
    private String logConfigBaseDamage;     // 基础伤害配置日志
    private String logConfigEvaporation;    // 蒸发倍率配置日志
    private String logConfigElectroCharge;  // 感电倍率配置日志
    private String logConfigElementDuration;// 元素时长配置日志

    private final Yuanshen plugin;

    public WaterElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadWaterConfig();
    }

    /**
     * 加载水元素配置 - 所有参数可通过config.yml配置，带默认值
     */
    private void loadWaterConfig() {
        FileConfiguration config = plugin.getConfig();

        // ========== 数值配置项 ==========
        waterBaseDamage = config.getDouble("water_element.base_damage", 5.0);          // 水基础伤害，默认5点
        evaporationMultiplier = config.getDouble("water_element.evaporation_multiplier", 2.0); // 水打火蒸发倍率，默认2倍
        electroChargeMultiplier = config.getDouble("water_element.electro_charge_multiplier", 1.2); // 感电倍率，默认1.2倍
        bloomMultiplier = config.getDouble("water_element.bloom_multiplier", 1.8);    // 绽放倍率，默认1.8倍
        slowDuration = config.getInt("water_element.slow_duration", 100);             // 潮湿减速时长，默认100tick
        slowLevel = config.getInt("water_element.slow_level", 1);                     // 潮湿减速等级，默认1级
        freezeDuration = config.getInt("water_element.freeze_duration", 80);          // 冻结时长，默认80tick(4秒)
        freezeLevel = config.getInt("water_element.freeze_level", 10);                // 冻结等级，默认10级（完全冻结）
        electroTickInterval = config.getInt("water_element.electro_tick_interval", 20); // 感电触发间隔，默认20tick(1秒)
        electroDuration = config.getInt("water_element.electro_duration", 100);       // 感电持续时长，默认100tick
        elementDuration = config.getInt("water_element.element_duration", 200);       // 水元素附着时长，默认200tick

        // ========== 文本配置项 ==========
        // 玩家提示（转换颜色码）
        msgEvaporation = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.evaporation", "&b【蒸发】&f触发！额外造成%s点伤害，消耗敌方火元素！"));
        msgFreeze = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.freeze", "&b【冻结】&f触发！敌方被冻结%s秒，额外造成%s点伤害！"));
        msgElectroCharge = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.electro_charge", "&b【感电】&f触发！敌方每%s秒受到%s点伤害，持续%s秒！"));
        msgBloom = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.bloom", "&b【绽放】&f触发！生成草原核，造成%s点伤害！"));
        msgNormalDamage = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.normal_damage", "&b触发！额外造成%s点伤害并给敌方附着水元素（潮湿）"));
        msgAlreadyHasWater = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.already_has_water", "&b敌方已附着水元素，无额外伤害！"));
        msgExtinguishFire = ChatColor.translateAlternateColorCodes('&',
                config.getString("water_element.messages.extinguish_fire", "&b熄灭了目标的火焰！"));
        msgNoLore = config.getString("water_element.messages.no_lore", "[水元素] 玩家%s手持物品无「水元素」Lore，不触发效果");

        // 日志配置
        logEvaporation = config.getString("water_element.logs.evaporation", "[水元素-蒸发] 目标%s火元素已消耗，最终伤害：%s");
        logFreeze = config.getString("water_element.logs.freeze", "[水元素-冻结] 目标%s冰元素已消耗，最终伤害：%s");
        logElectroCharge = config.getString("water_element.logs.electro_charge", "[水元素-感电] 目标%s雷元素触发感电，每%s秒伤害%s，持续%s秒");
        logBloom = config.getString("water_element.logs.bloom", "[水元素-绽放] 目标%s草元素已消耗，绽放伤害：%s");
        logRemoveMark = config.getString("water_element.logs.remove_mark", "[水元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("water_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("water_element.logs.config_loaded", "水元素配置加载完成：");
        logConfigBaseDamage = config.getString("water_element.logs.config_base_damage", "基础伤害：%s");
        logConfigEvaporation = config.getString("water_element.logs.config_evaporation_multiplier", "蒸发倍率：%s");
        logConfigElectroCharge = config.getString("water_element.logs.config_electro_charge_multiplier", "感电倍率：%s");
        logConfigElementDuration = config.getString("water_element.logs.config_element_duration", "元素过期时长：%s tick");

        // 打印配置日志
        plugin.getLogger().info(logConfigLoaded);
        plugin.getLogger().info(String.format(logConfigBaseDamage, waterBaseDamage));
        plugin.getLogger().info(String.format(logConfigEvaporation, evaporationMultiplier));
        plugin.getLogger().info(String.format(logConfigElectroCharge, electroChargeMultiplier));
        plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    @EventHandler
    public void onPlayerAttackWithWater(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) {
            return;
        }

        // 检测水元素Lore
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasWaterLore = checkItemLore(handItem, WATER_LORE);
        if (!hasWaterLore) {
            plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        // 检测目标元素附着
        boolean hasFire = hasElement(target, FIRE_KEY);
        boolean hasIce = hasElement(target, ICE_KEY);
        boolean hasElectro = hasElement(target, ELECTRO_KEY);
        boolean hasDendro = hasElement(target, DENDRO_KEY);
        boolean hasWater = hasElement(target, WATER_KEY);

        double totalDamage = e.getDamage();
        boolean needAddWaterMark = true;

        // ========== 元素反应逻辑 ==========
        // 1. 蒸发（水打火）
        if (hasFire) {
            double extraDamage = waterBaseDamage * evaporationMultiplier;
            totalDamage += extraDamage;
            removeElement(target, FIRE_KEY);

            // 灭火效果
            if (target.getFireTicks() > 0) {
                target.setFireTicks(0);
                attacker.sendMessage(WATER_TAG + msgExtinguishFire);
            }

            attacker.sendMessage(WATER_TAG + String.format(msgEvaporation, extraDamage));
            plugin.getLogger().info(String.format(logEvaporation, target.getName(), totalDamage));
            needAddWaterMark = false;
        }
        // 2. 冻结（水打冰）
        else if (hasIce) {
            double extraDamage = waterBaseDamage;
            totalDamage += extraDamage;
            removeElement(target, ICE_KEY);

            // 冻结效果（Slowness 10级=完全无法移动）
            PotionEffect freezeEffect = new PotionEffect(PotionEffectType.SLOWNESS, freezeDuration, freezeLevel);
            target.addPotionEffect(freezeEffect);

            attacker.sendMessage(WATER_TAG + String.format(msgFreeze, freezeDuration / 20.0, extraDamage));
            plugin.getLogger().info(String.format(logFreeze, target.getName(), totalDamage));
            needAddWaterMark = false;
        }
        // 3. 感电（水打雷）
        else if (hasElectro) {
            double extraDamage = waterBaseDamage * electroChargeMultiplier;
            totalDamage += extraDamage;
            // 感电持续伤害
            startElectroChargeTask(target, electroDuration, electroTickInterval, extraDamage / 5);

            attacker.sendMessage(WATER_TAG + String.format(msgElectroCharge, electroTickInterval / 20.0, extraDamage / 5, electroDuration / 20.0));
            plugin.getLogger().info(String.format(logElectroCharge, target.getName(), electroTickInterval / 20.0, extraDamage / 5, electroDuration / 20.0));
            needAddWaterMark = false;
        }
        // 4. 绽放（水打草）
        else if (hasDendro) {
            double extraDamage = waterBaseDamage * bloomMultiplier;
            totalDamage += extraDamage;
            removeElement(target, DENDRO_KEY);

            attacker.sendMessage(WATER_TAG + String.format(msgBloom, extraDamage));
            plugin.getLogger().info(String.format(logBloom, target.getName(), totalDamage));
            needAddWaterMark = false;
        }
        // 5. 无水分 → 普通水伤+潮湿减速
        else if (!hasWater) {
            totalDamage += waterBaseDamage;
            // 潮湿减速效果
            PotionEffect slowEffect = new PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowLevel);
            target.addPotionEffect(slowEffect);

            attacker.sendMessage(WATER_TAG + String.format(msgNormalDamage, waterBaseDamage));
        }
        // 6. 已有水元素 → 无额外伤害
        else {
            attacker.sendMessage(WATER_TAG + msgAlreadyHasWater);
            needAddWaterMark = false;
        }

        // 设置最终伤害
        e.setDamage(totalDamage);

        // 附着水元素
        if (needAddWaterMark) {
            addElementWithExpire(target, WATER_KEY, elementDuration);
        }

        // 通用日志
        plugin.getLogger().info(String.format(
                "[水元素] 目标：%s | 火：%s | 冰：%s | 雷：%s | 草：%s | 水：%s | 最终伤害：%.1f",
                target.getName(), hasFire, hasIce, hasElectro, hasDendro, hasWater, totalDamage
        ));
    }

    /**
     * 启动感电持续伤害任务
     */
    private void startElectroChargeTask(LivingEntity target, int duration, int interval, double damagePerTick) {
        new BukkitRunnable() {
            int remainingTicks = duration;

            @Override
            public void run() {
                if (remainingTicks <= 0 || target.isDead() || !target.isValid()) {
                    this.cancel();
                    return;
                }
                // 每隔interval tick造成一次伤害
                if (remainingTicks % interval == 0) {
                    target.damage(damagePerTick);
                }
                remainingTicks -= 1;
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    // 通用工具方法
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
            plugin.getLogger().warning("[水元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
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