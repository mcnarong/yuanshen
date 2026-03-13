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
 * 雷元素监听器 - 实现原神雷元素反应：感电、超载、超导、激化
 */
public class ElectroElementListener implements Listener {
    // 配置化数值
    private double electroBaseDamage;       // 雷基础伤害
    private double electroChargeMultiplier; // 感电倍率
    private double overloadMultiplier;      // 超载倍率
    private double superconductMultiplier;  // 超导倍率
    private double aggravateMultiplier;     // 激化倍率
    private int electroTickInterval;        // 感电触发间隔
    private int electroDuration;            // 感电持续时长
    private int elementDuration;            // 雷元素附着时长

    // 配置化文本
    private String msgElectroCharge;        // 感电提示
    private String msgOverload;             // 超载提示
    private String msgSuperconduct;         // 超导提示
    private String msgAggravate;            // 激化提示
    private String msgNormalDamage;         // 普通雷伤提示
    private String msgAlreadyHasElectro;    // 已附着雷提示
    private String msgNoLore;               // 无Lore提示

    private String logElectroCharge;        // 感电日志
    private String logOverload;             // 超载日志
    private String logSuperconduct;         // 超导日志
    private String logAggravate;            // 激化日志
    private String logRemoveMark;           // 移除标记日志
    private String logExpireMark;           // 过期日志
    private String logConfigLoaded;         // 配置加载日志
    private String logConfigBaseDamage;     // 基础伤害配置日志
    private String logConfigElectroCharge;  // 感电倍率配置日志
    private String logConfigOverload;       // 超载倍率配置日志
    private String logConfigElementDuration;// 元素时长配置日志

    private final Yuanshen plugin;

    public ElectroElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadElectroConfig();
    }

    /**
     * 加载雷元素配置 - 所有参数可配置，带默认值
     */
    private void loadElectroConfig() {
        FileConfiguration config = plugin.getConfig();

        // ========== 数值配置项 ==========
        electroBaseDamage = config.getDouble("electro_element.base_damage", 5.0);    // 雷基础伤害，默认5点
        electroChargeMultiplier = config.getDouble("electro_element.electro_charge_multiplier", 1.2); // 感电倍率，默认1.2倍
        overloadMultiplier = config.getDouble("electro_element.overload_multiplier", 2.5); // 超载倍率，默认2.5倍
        superconductMultiplier = config.getDouble("electro_element.superconduct_multiplier", 1.0); // 超导倍率，默认1倍
        aggravateMultiplier = config.getDouble("electro_element.aggravate_multiplier", 1.8); // 激化倍率，默认1.8倍
        electroTickInterval = config.getInt("electro_element.electro_tick_interval", 20); // 感电间隔，默认20tick(1秒)
        electroDuration = config.getInt("electro_element.electro_duration", 100);   // 感电时长，默认100tick
        elementDuration = config.getInt("electro_element.element_duration", 200);   // 雷元素附着时长，默认200tick

        // ========== 文本配置项 ==========
        msgElectroCharge = ChatColor.translateAlternateColorCodes('&',
                config.getString("electro_element.messages.electro_charge", "&5【感电】&f触发！敌方每%s秒受到%s点伤害，持续%s秒！"));
        msgOverload = ChatColor.translateAlternateColorCodes('&',
                config.getString("electro_element.messages.overload", "&5【超载】&f触发！爆炸造成%s点伤害，消耗敌方火元素！"));
        msgSuperconduct = ChatColor.translateAlternateColorCodes('&',
                config.getString("electro_element.messages.superconduct", "&5【超导】&f触发！降低敌方防御，额外造成%s点伤害！"));
        msgAggravate = ChatColor.translateAlternateColorCodes('&',
                config.getString("electro_element.messages.aggravate", "&5【激化】&f触发！草元素强化雷伤，额外造成%s点伤害！"));
        msgNormalDamage = ChatColor.translateAlternateColorCodes('&',
                config.getString("electro_element.messages.normal_damage", "&5触发！额外造成%s点伤害并给敌方附着雷元素"));
        msgAlreadyHasElectro = ChatColor.translateAlternateColorCodes('&',
                config.getString("electro_element.messages.already_has_electro", "&5敌方已附着雷元素，无额外伤害！"));
        msgNoLore = config.getString("electro_element.messages.no_lore", "[雷元素] 玩家%s手持物品无「雷元素」Lore，不触发效果");

        // 日志配置
        logElectroCharge = config.getString("electro_element.logs.electro_charge", "[雷元素-感电] 目标%s水元素触发感电，每%s秒伤害%s，持续%s秒");
        logOverload = config.getString("electro_element.logs.overload", "[雷元素-超载] 目标%s火元素已消耗，爆炸伤害：%s");
        logSuperconduct = config.getString("electro_element.logs.superconduct", "[雷元素-超导] 目标%s冰元素已消耗，最终伤害：%s");
        logAggravate = config.getString("electro_element.logs.aggravate", "[雷元素-激化] 目标%s草元素已消耗，激化伤害：%s");
        logRemoveMark = config.getString("electro_element.logs.remove_mark", "[雷元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("electro_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("electro_element.logs.config_loaded", "雷元素配置加载完成：");
        logConfigBaseDamage = config.getString("electro_element.logs.config_base_damage", "基础伤害：%s");
        logConfigElectroCharge = config.getString("electro_element.logs.config_electro_charge_multiplier", "感电倍率：%s");
        logConfigOverload = config.getString("electro_element.logs.config_overload_multiplier", "超载倍率：%s");
        logConfigElementDuration = config.getString("electro_element.logs.config_element_duration", "元素过期时长：%s tick");

        // 打印配置日志
        plugin.getLogger().info(logConfigLoaded);
        plugin.getLogger().info(String.format(logConfigBaseDamage, electroBaseDamage));
        plugin.getLogger().info(String.format(logConfigElectroCharge, electroChargeMultiplier));
        plugin.getLogger().info(String.format(logConfigOverload, overloadMultiplier));
        plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    @EventHandler
    public void onPlayerAttackWithElectro(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) {
            return;
        }

        // 检测雷元素Lore
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasElectroLore = checkItemLore(handItem, ELECTRO_LORE);
        if (!hasElectroLore) {
            plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        // 检测目标元素
        boolean hasWater = hasElement(target, WATER_KEY);
        boolean hasFire = hasElement(target, FIRE_KEY);
        boolean hasIce = hasElement(target, ICE_KEY);
        boolean hasDendro = hasElement(target, DENDRO_KEY);
        boolean hasElectro = hasElement(target, ELECTRO_KEY);

        double totalDamage = e.getDamage();
        boolean needAddElectroMark = true;

        // ========== 元素反应逻辑 ==========
        // 1. 超载（雷打火）
        if (hasFire) {
            double extraDamage = electroBaseDamage * overloadMultiplier;
            totalDamage += extraDamage;
            removeElement(target, FIRE_KEY);
            // 超载击退
            target.setVelocity(target.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize().multiply(1.5));

            attacker.sendMessage(ELECTRO_TAG + String.format(msgOverload, extraDamage));
            plugin.getLogger().info(String.format(logOverload, target.getName(), extraDamage));
            needAddElectroMark = false;
        }
        // 2. 超导（雷打冰）
        else if (hasIce) {
            double extraDamage = electroBaseDamage * superconductMultiplier;
            totalDamage += extraDamage;
            removeElement(target, ICE_KEY);
            // 超导降低防御（模拟：附加缓速）
            PotionEffect slowEffect = new PotionEffect(PotionEffectType.SLOWNESS, 100, 2);
            target.addPotionEffect(slowEffect);

            attacker.sendMessage(ELECTRO_TAG + String.format(msgSuperconduct, extraDamage));
            plugin.getLogger().info(String.format(logSuperconduct, target.getName(), totalDamage));
            needAddElectroMark = false;
        }
        // 3. 感电（雷打水）
        else if (hasWater) {
            double extraDamage = electroBaseDamage * electroChargeMultiplier;
            totalDamage += extraDamage;
            // 感电持续伤害
            startElectroChargeTask(target, electroDuration, electroTickInterval, extraDamage / 5);

            attacker.sendMessage(ELECTRO_TAG + String.format(msgElectroCharge, electroTickInterval / 20.0, extraDamage / 5, electroDuration / 20.0));
            plugin.getLogger().info(String.format(logElectroCharge, target.getName(), electroTickInterval / 20.0, extraDamage / 5, electroDuration / 20.0));
            needAddElectroMark = false;
        }
        // 4. 激化（雷打草）
        else if (hasDendro) {
            double extraDamage = electroBaseDamage * aggravateMultiplier;
            totalDamage += extraDamage;
            removeElement(target, DENDRO_KEY);

            attacker.sendMessage(ELECTRO_TAG + String.format(msgAggravate, extraDamage));
            plugin.getLogger().info(String.format(logAggravate, target.getName(), extraDamage));
            needAddElectroMark = false;
        }
        // 5. 无雷元素 → 普通雷伤
        else if (!hasElectro) {
            totalDamage += electroBaseDamage;
            attacker.sendMessage(ELECTRO_TAG + String.format(msgNormalDamage, electroBaseDamage));
        }
        // 6. 已有雷元素 → 无额外伤害
        else {
            attacker.sendMessage(ELECTRO_TAG + msgAlreadyHasElectro);
            needAddElectroMark = false;
        }

        // 设置最终伤害
        e.setDamage(totalDamage);

        // 附着雷元素
        if (needAddElectroMark) {
            addElementWithExpire(target, ELECTRO_KEY, elementDuration);
        }

        // 通用日志
        plugin.getLogger().info(String.format(
                "[雷元素] 目标：%s | 水：%s | 火：%s | 冰：%s | 草：%s | 雷：%s | 最终伤害：%.1f",
                target.getName(), hasWater, hasFire, hasIce, hasDendro, hasElectro, totalDamage
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
            plugin.getLogger().warning("[雷元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
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