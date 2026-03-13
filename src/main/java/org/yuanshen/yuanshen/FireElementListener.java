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
import org.bukkit.scheduler.BukkitRunnable;

import static org.yuanshen.yuanshen.ElementConstant.*;

/**
 * 火元素监听器 - 实现原神火元素反应：蒸发、融化、燃烧、超载、烈绽放
 */
public class FireElementListener implements Listener {
    // 配置化数值（成员变量）
    private double fireBaseDamage;          // 基础附加伤害
    private double evaporationMultiplier;   // 蒸发倍率（火打水文）
    private double meltMultiplier;          // 融化倍率（火打冰）
    private double burningDamage;           // 燃烧每秒伤害
    private int burningDuration;            // 燃烧持续时长(tick)
    private double overloadMultiplier;      // 超载伤害倍率
    private double burstMultiplier;         // 烈绽放倍率
    private int fireTicks;                  // 燃烧火焰tick
    private int elementDuration;            // 元素附着持续时长

    // 配置化文本（成员变量）
    private String msgEvaporation;          // 蒸发提示
    private String msgMelt;                 // 融化提示
    private String msgBurning;              // 燃烧提示
    private String msgOverload;             // 超载提示
    private String msgBurst;                // 烈绽放提示
    private String msgNormalDamage;         // 普通火伤提示
    private String msgAlreadyHasFire;       // 已附着火元素提示
    private String msgNoLore;               // 无火元素Lore提示

    private String logEvaporation;          // 蒸发日志
    private String logMelt;                 // 融化日志
    private String logBurning;              // 燃烧日志
    private String logOverload;             // 超载日志
    private String logBurst;                // 烈绽放日志
    private String logRemoveMark;           // 移除元素标记日志
    private String logExpireMark;           // 元素过期日志
    private String logConfigLoaded;         // 配置加载完成日志
    private String logConfigBaseDamage;     // 基础伤害配置日志
    private String logConfigEvaporation;    // 蒸发倍率配置日志
    private String logConfigMelt;           // 融化倍率配置日志
    private String logConfigBurning;        // 燃烧伤害配置日志
    private String logConfigElementDuration;// 元素时长配置日志

    private final Yuanshen plugin;

    // 构造方法：初始化加载配置
    public FireElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadFireConfig();
    }

    /**
     * 加载火元素所有配置项
     * 从config.yml读取，带默认值，支持颜色码转换
     */
    private void loadFireConfig() {
        FileConfiguration config = plugin.getConfig();

        // ========== 数值配置项 ==========
        fireBaseDamage = config.getDouble("fire_element.base_damage", 5.0);          // 火元素基础附加伤害，默认5点
        evaporationMultiplier = config.getDouble("fire_element.evaporation_multiplier", 1.5); // 蒸发倍率（火打水），默认1.5倍
        meltMultiplier = config.getDouble("fire_element.melt_multiplier", 2.0);     // 融化倍率（火打冰），默认2.0倍
        burningDamage = config.getDouble("fire_element.burning_damage_per_tick", 0.5); // 燃烧每tick伤害，默认0.5点
        burningDuration = config.getInt("fire_element.burning_duration", 100);      // 燃烧持续时长，默认100tick(5秒)
        overloadMultiplier = config.getDouble("fire_element.overload_multiplier", 2.5); // 超载倍率，默认2.5倍
        burstMultiplier = config.getDouble("fire_element.burst_multiplier", 3.0);   // 烈绽放倍率，默认3.0倍
        fireTicks = config.getInt("fire_element.fire_ticks", 100);                  // 火焰燃烧tick，默认100tick
        elementDuration = config.getInt("fire_element.element_duration", 200);      // 火元素附着时长，默认200tick(10秒)

        // ========== 文本配置项（自动转换颜色码） ==========
        // 玩家提示消息
        msgEvaporation = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.evaporation", "&c【蒸发】&f触发！额外造成%s点伤害，消耗敌方水元素！"));
        msgMelt = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.melt", "&c【融化】&f触发！额外造成%s点伤害，消耗敌方冰元素！"));
        msgBurning = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.burning", "&c【燃烧】&f触发！敌方每tick受到%s点伤害，持续%s秒！"));
        msgOverload = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.overload", "&c【超载】&f触发！爆炸造成%s点伤害，消耗敌方雷元素！"));
        msgBurst = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.burst", "&c【烈绽放】&f触发！造成%s点范围伤害，消耗敌方草元素！"));
        msgNormalDamage = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.normal_damage", "&c触发！额外造成%s点伤害并给敌方附着火元素"));
        msgAlreadyHasFire = ChatColor.translateAlternateColorCodes('&',
                config.getString("fire_element.messages.already_has_fire", "&c敌方已附着火元素，无额外伤害！"));
        msgNoLore = config.getString("fire_element.messages.no_lore", "[火元素] 玩家%s手持物品无「火元素」Lore，不触发效果");

        // 日志消息
        logEvaporation = config.getString("fire_element.logs.evaporation", "[火元素-蒸发] 目标%s水元素已消耗，最终伤害：%s");
        logMelt = config.getString("fire_element.logs.melt", "[火元素-融化] 目标%s冰元素已消耗，最终伤害：%s");
        logBurning = config.getString("fire_element.logs.burning", "[火元素-燃烧] 目标%s被点燃，每tick伤害%s，持续%s秒");
        logOverload = config.getString("fire_element.logs.overload", "[火元素-超载] 目标%s雷元素已消耗，爆炸伤害：%s");
        logBurst = config.getString("fire_element.logs.burst", "[火元素-烈绽放] 目标%s草元素已消耗，范围伤害：%s");
        logRemoveMark = config.getString("fire_element.logs.remove_mark", "[火元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("fire_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("fire_element.logs.config_loaded", "火元素配置加载完成：");
        logConfigBaseDamage = config.getString("fire_element.logs.config_base_damage", "基础伤害：%s");
        logConfigEvaporation = config.getString("fire_element.logs.config_evaporation_multiplier", "蒸发倍率：%s");
        logConfigMelt = config.getString("fire_element.logs.config_melt_multiplier", "融化倍率：%s");
        logConfigBurning = config.getString("fire_element.logs.config_burning_damage", "燃烧每tick伤害：%s");
        logConfigElementDuration = config.getString("fire_element.logs.config_element_duration", "元素过期时长：%s tick");

        // 打印配置加载日志
        plugin.getLogger().info(logConfigLoaded);
        plugin.getLogger().info(String.format(logConfigBaseDamage, fireBaseDamage));
        plugin.getLogger().info(String.format(logConfigEvaporation, evaporationMultiplier));
        plugin.getLogger().info(String.format(logConfigMelt, meltMultiplier));
        plugin.getLogger().info(String.format(logConfigBurning, burningDamage));
        plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    @EventHandler
    public void onPlayerAttackWithFire(EntityDamageByEntityEvent e) {
        // 过滤无效攻击：仅处理玩家攻击生物
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) {
            return;
        }

        // 检测手持物品是否有火元素Lore
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasFireLore = checkItemLore(handItem, FIRE_LORE);
        if (!hasFireLore) {
            plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        // 检测目标当前附着的元素
        boolean hasWater = hasElement(target, WATER_KEY);
        boolean hasIce = hasElement(target, ICE_KEY);
        boolean hasElectro = hasElement(target, ELECTRO_KEY);
        boolean hasDendro = hasElement(target, DENDRO_KEY);
        boolean hasFire = hasElement(target, FIRE_KEY);

        double totalDamage = e.getDamage();
        boolean needAddFireMark = true;

        // ========== 元素反应逻辑（按原神优先级） ==========
        // 1. 蒸发（火打水）
        if (hasWater) {
            double extraDamage = fireBaseDamage * evaporationMultiplier;
            totalDamage += extraDamage;
            removeElement(target, WATER_KEY);

            // 发送提示和日志
            attacker.sendMessage(FIRE_TAG + String.format(msgEvaporation, extraDamage));
            plugin.getLogger().info(String.format(logEvaporation, target.getName(), totalDamage));
            needAddFireMark = false;
        }
        // 2. 融化（火打冰）
        else if (hasIce) {
            double extraDamage = fireBaseDamage * meltMultiplier;
            totalDamage += extraDamage;
            removeElement(target, ICE_KEY);

            attacker.sendMessage(FIRE_TAG + String.format(msgMelt, extraDamage));
            plugin.getLogger().info(String.format(logMelt, target.getName(), totalDamage));
            needAddFireMark = false;
        }
        // 3. 超载（火打雷）
        else if (hasElectro) {
            double extraDamage = fireBaseDamage * overloadMultiplier;
            totalDamage += extraDamage;
            removeElement(target, ELECTRO_KEY);
            // 超载附加击退效果（模拟爆炸）
            target.setVelocity(target.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize().multiply(1.5));

            attacker.sendMessage(FIRE_TAG + String.format(msgOverload, extraDamage));
            plugin.getLogger().info(String.format(logOverload, target.getName(), totalDamage));
            needAddFireMark = false;
        }
        // 4. 烈绽放（火打草）
        else if (hasDendro) {
            double extraDamage = fireBaseDamage * burstMultiplier;
            totalDamage += extraDamage;
            removeElement(target, DENDRO_KEY);

            attacker.sendMessage(FIRE_TAG + String.format(msgBurst, extraDamage));
            plugin.getLogger().info(String.format(logBurst, target.getName(), totalDamage));
            needAddFireMark = false;
        }
        // 5. 燃烧（火打草 - 原神中草打火也会燃烧，这里简化为火打草触发持续燃烧）
        // 注：上面烈绽放优先级更高，这里实际不会触发，如需调整可交换顺序
        // 6. 无火元素 → 普通火伤+燃烧
        else if (!hasFire) {
            totalDamage += fireBaseDamage;
            target.setFireTicks(fireTicks);
            // 附加持续燃烧伤害
            startBurningTask(target, burningDuration, burningDamage);

            attacker.sendMessage(FIRE_TAG + String.format(msgNormalDamage, fireBaseDamage));
            plugin.getLogger().info(String.format(logBurning, target.getName(), burningDamage, burningDuration / 20.0));
        }
        // 7. 已有火元素 → 无额外伤害
        else {
            attacker.sendMessage(FIRE_TAG + msgAlreadyHasFire);
            needAddFireMark = false;
        }

        // 设置最终伤害
        e.setDamage(totalDamage);

        // 附着火元素（仅当未触发反应时）
        if (needAddFireMark) {
            addElementWithExpire(target, FIRE_KEY, elementDuration);
        }

        // 通用日志
        plugin.getLogger().info(String.format(
                "[火元素] 目标：%s | 水：%s | 冰：%s | 雷：%s | 草：%s | 火：%s | 最终伤害：%.1f",
                target.getName(), hasWater, hasIce, hasElectro, hasDendro, hasFire, totalDamage
        ));
    }

    /**
     * 启动燃烧持续伤害任务
     * @param target 目标生物
     * @param duration 持续时长(tick)
     * @param damagePerTick 每tick伤害
     */
    private void startBurningTask(LivingEntity target, int duration, double damagePerTick) {
        new BukkitRunnable() {
            int remainingTicks = duration;

            @Override
            public void run() {
                if (remainingTicks <= 0 || target.isDead() || !target.isValid()) {
                    this.cancel();
                    return;
                }
                // 每tick造成燃烧伤害
                target.damage(damagePerTick);
                remainingTicks -= 1;
            }
        }.runTaskTimer(plugin, 0, 1); // 每1tick执行一次
    }

    /**
     * 检查物品Lore是否包含指定关键词（去除颜色码）
     * @param item 物品栈
     * @param keyword 关键词
     * @return 是否包含
     */
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

    /**
     * 检测目标是否附着指定元素
     * @param target 目标生物
     * @param elementKey 元素标记Key
     * @return 是否附着
     */
    private boolean hasElement(LivingEntity target, String elementKey) {
        try {
            return target.hasMetadata(elementKey) && target.getMetadata(elementKey).get(0).asBoolean();
        } catch (Exception ex) {
            plugin.getLogger().warning("[火元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
            return false;
        }
    }

    /**
     * 移除目标的元素标记
     * @param target 目标生物
     * @param elementKey 元素标记Key
     */
    private void removeElement(LivingEntity target, String elementKey) {
        if (target.hasMetadata(elementKey)) {
            target.removeMetadata(elementKey, plugin);
            plugin.getLogger().info(String.format(logRemoveMark, target.getName(), elementKey));
        }
    }

    /**
     * 给目标添加元素标记并设置过期移除
     * @param target 目标生物
     * @param elementKey 元素标记Key
     * @param durationTicks 持续时长(tick)
     */
    private void addElementWithExpire(LivingEntity target, String elementKey, int durationTicks) {
        FixedMetadataValue elementMark = new FixedMetadataValue(plugin, true);
        target.setMetadata(elementKey, elementMark);

        // 定时移除元素标记
        new BukkitRunnable() {
            @Override
            public void run() {
                removeElement(target, elementKey);
                plugin.getLogger().info(String.format(logExpireMark, target.getName(), elementKey));
            }
        }.runTaskLater(plugin, durationTicks);
    }
}