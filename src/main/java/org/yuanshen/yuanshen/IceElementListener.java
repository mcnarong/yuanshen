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
 * 冰元素监听器 - 实现原神冰元素反应：融化、冻结、超导、碎冰
 */
public class IceElementListener implements Listener {
    // 配置化数值
    private double iceBaseDamage;           // 冰基础伤害
    private double meltMultiplier;          // 融化倍率（冰打火）
    private double superconductMultiplier;  // 超导倍率
    private double shatterMultiplier;       // 碎冰倍率
    private int slowDuration;               // 冰缓速时长
    private int slowLevel;                  // 冰缓速等级
    private int freezeDuration;             // 冻结时长
    private int freezeLevel;                // 冻结等级
    private int elementDuration;            // 冰元素附着时长

    // 配置化文本
    private String msgMelt;                 // 融化提示
    private String msgFreeze;               // 冻结提示
    private String msgSuperconduct;         // 超导提示
    private String msgShatter;              // 碎冰提示
    private String msgNormalDamage;         // 普通冰伤提示
    private String msgAlreadyHasIce;        // 已附着冰提示
    private String msgNoLore;               // 无Lore提示

    private String logMelt;                 // 融化日志
    private String logFreeze;               // 冻结日志
    private String logSuperconduct;         // 超导日志
    private String logShatter;              // 碎冰日志
    private String logRemoveMark;           // 移除标记日志
    private String logExpireMark;           // 过期日志
    private String logConfigLoaded;         // 配置加载日志
    private String logConfigBaseDamage;     // 基础伤害配置日志
    private String logConfigMelt;           // 融化倍率配置日志
    private String logConfigSuperconduct;   // 超导倍率配置日志
    private String logConfigElementDuration;// 元素时长配置日志

    private final Yuanshen plugin;

    public IceElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadIceConfig();
    }

    /**
     * 加载冰元素配置 - 所有参数可配置，带默认值
     */
    private void loadIceConfig() {
        FileConfiguration config = plugin.getConfig();

        // ========== 数值配置项 ==========
        iceBaseDamage = config.getDouble("ice_element.base_damage", 5.0);          // 冰基础伤害，默认5点
        meltMultiplier = config.getDouble("ice_element.melt_multiplier", 1.5);     // 冰打火融化倍率，默认1.5倍
        superconductMultiplier = config.getDouble("ice_element.superconduct_multiplier", 1.0); // 超导倍率，默认1倍
        shatterMultiplier = config.getDouble("ice_element.shatter_multiplier", 2.5); // 碎冰倍率，默认2.5倍
        slowDuration = config.getInt("ice_element.slow_duration", 120);           // 冰缓速时长，默认120tick(6秒)
        slowLevel = config.getInt("ice_element.slow_level", 2);                   // 冰缓速等级，默认2级
        freezeDuration = config.getInt("ice_element.freeze_duration", 80);        // 冻结时长，默认80tick(4秒)
        freezeLevel = config.getInt("ice_element.freeze_level", 10);              // 冻结等级，默认10级
        elementDuration = config.getInt("ice_element.element_duration", 200);     // 冰元素附着时长，默认200tick

        // ========== 文本配置项 ==========
        msgMelt = ChatColor.translateAlternateColorCodes('&',
                config.getString("ice_element.messages.melt", "&9【融化】&f触发！额外造成%s点伤害，消耗敌方火元素！"));
        msgFreeze = ChatColor.translateAlternateColorCodes('&',
                config.getString("ice_element.messages.freeze", "&9【冻结】&f触发！敌方被冻结%s秒，额外造成%s点伤害！"));
        msgSuperconduct = ChatColor.translateAlternateColorCodes('&',
                config.getString("ice_element.messages.superconduct", "&9【超导】&f触发！降低敌方防御，额外造成%s点伤害！"));
        msgShatter = ChatColor.translateAlternateColorCodes('&',
                config.getString("ice_element.messages.shatter", "&9【碎冰】&f触发！冻结目标受到%s点额外伤害！"));
        msgNormalDamage = ChatColor.translateAlternateColorCodes('&',
                config.getString("ice_element.messages.normal_damage", "&9触发！额外造成%s点伤害并给敌方附着冰元素"));
        msgAlreadyHasIce = ChatColor.translateAlternateColorCodes('&',
                config.getString("ice_element.messages.already_has_ice", "&9敌方已附着冰元素，无额外伤害！"));
        msgNoLore = config.getString("ice_element.messages.no_lore", "[冰元素] 玩家%s手持物品无「冰元素」Lore，不触发效果");

        // 日志配置
        logMelt = config.getString("ice_element.logs.melt", "[冰元素-融化] 目标%s火元素已消耗，最终伤害：%s");
        logFreeze = config.getString("ice_element.logs.freeze", "[冰元素-冻结] 目标%s水元素已消耗，最终伤害：%s");
        logSuperconduct = config.getString("ice_element.logs.superconduct", "[冰元素-超导] 目标%s雷元素已消耗，最终伤害：%s");
        logShatter = config.getString("ice_element.logs.shatter", "[冰元素-碎冰] 目标%s被碎冰，额外伤害：%s");
        logRemoveMark = config.getString("ice_element.logs.remove_mark", "[冰元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("ice_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("ice_element.logs.config_loaded", "冰元素配置加载完成：");
        logConfigBaseDamage = config.getString("ice_element.logs.config_base_damage", "基础伤害：%s");
        logConfigMelt = config.getString("ice_element.logs.config_melt_multiplier", "融化倍率：%s");
        logConfigSuperconduct = config.getString("ice_element.logs.config_superconduct_multiplier", "超导倍率：%s");
        logConfigElementDuration = config.getString("ice_element.logs.config_element_duration", "元素过期时长：%s tick");

        // 打印配置日志
        plugin.getLogger().info(logConfigLoaded);
        plugin.getLogger().info(String.format(logConfigBaseDamage, iceBaseDamage));
        plugin.getLogger().info(String.format(logConfigMelt, meltMultiplier));
        plugin.getLogger().info(String.format(logConfigSuperconduct, superconductMultiplier));
        plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    @EventHandler
    public void onPlayerAttackWithIce(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) {
            return;
        }

        // 检测冰元素Lore
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasIceLore = checkItemLore(handItem, ICE_LORE);
        if (!hasIceLore) {
            plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        // 检测目标元素
        boolean hasFire = hasElement(target, FIRE_KEY);
        boolean hasWater = hasElement(target, WATER_KEY);
        boolean hasElectro = hasElement(target, ELECTRO_KEY);
        boolean hasIce = hasElement(target, ICE_KEY);
        // 检测是否被冻结（有Slowness 10级效果）
        boolean isFrozen = target.hasPotionEffect(PotionEffectType.SLOWNESS) 
                && target.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier() >= 10;

        double totalDamage = e.getDamage();
        boolean needAddIceMark = true;

        // ========== 元素反应逻辑 ==========
        // 1. 碎冰（攻击冻结目标）
        if (isFrozen) {
            double extraDamage = iceBaseDamage * shatterMultiplier;
            totalDamage += extraDamage;
            // 解除冻结
            target.removePotionEffect(PotionEffectType.SLOWNESS);

            attacker.sendMessage(ICE_TAG + String.format(msgShatter, extraDamage));
            plugin.getLogger().info(String.format(logShatter, target.getName(), extraDamage));
            needAddIceMark = false;
        }
        // 2. 融化（冰打火）
        else if (hasFire) {
            double extraDamage = iceBaseDamage * meltMultiplier;
            totalDamage += extraDamage;
            removeElement(target, FIRE_KEY);

            attacker.sendMessage(ICE_TAG + String.format(msgMelt, extraDamage));
            plugin.getLogger().info(String.format(logMelt, target.getName(), totalDamage));
            needAddIceMark = false;
        }
        // 3. 冻结（冰打水）
        else if (hasWater) {
            double extraDamage = iceBaseDamage;
            totalDamage += extraDamage;
            removeElement(target, WATER_KEY);

            // 冻结效果
            PotionEffect freezeEffect = new PotionEffect(PotionEffectType.SLOWNESS, freezeDuration, freezeLevel);
            target.addPotionEffect(freezeEffect);

            attacker.sendMessage(ICE_TAG + String.format(msgFreeze, freezeDuration / 20.0, extraDamage));
            plugin.getLogger().info(String.format(logFreeze, target.getName(), totalDamage));
            needAddIceMark = false;
        }
        // 4. 超导（冰打雷）
        else if (hasElectro) {
            double extraDamage = iceBaseDamage * superconductMultiplier;
            totalDamage += extraDamage;
            removeElement(target, ELECTRO_KEY);
            // 超导降低防御（模拟：增加后续伤害，这里简化为附加缓速）
            PotionEffect slowEffect = new PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowLevel + 1);
            target.addPotionEffect(slowEffect);

            attacker.sendMessage(ICE_TAG + String.format(msgSuperconduct, extraDamage));
            plugin.getLogger().info(String.format(logSuperconduct, target.getName(), totalDamage));
            needAddIceMark = false;
        }
        // 5. 无冰元素 → 普通冰伤+缓速
        else if (!hasIce) {
            totalDamage += iceBaseDamage;
            PotionEffect slowEffect = new PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowLevel);
            target.addPotionEffect(slowEffect);

            attacker.sendMessage(ICE_TAG + String.format(msgNormalDamage, iceBaseDamage));
        }
        // 6. 已有冰元素 → 无额外伤害
        else {
            attacker.sendMessage(ICE_TAG + msgAlreadyHasIce);
            needAddIceMark = false;
        }

        // 设置最终伤害
        e.setDamage(totalDamage);

        // 附着冰元素
        if (needAddIceMark) {
            addElementWithExpire(target, ICE_KEY, elementDuration);
        }

        // 通用日志
        plugin.getLogger().info(String.format(
                "[冰元素] 目标：%s | 火：%s | 水：%s | 雷：%s | 冰：%s | 冻结：%s | 最终伤害：%.1f",
                target.getName(), hasFire, hasWater, hasElectro, hasIce, isFrozen, totalDamage
        ));
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
            plugin.getLogger().warning("[冰元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
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