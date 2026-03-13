package org.yuanshen.yuanshen;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.yuanshen.yuanshen.ElementConstant.*;

/**
 * 岩元素监听器 - 兼容旧版本 Spigot/Bukkit
 */
public class GeoElementListener implements Listener {
    // 配置化数值
    private double geoBaseDamage;
    private double crystalShieldMultiplier;
    private int shieldDuration;
    private int elementDuration;
    private int crystalChipExpireTime;
    private double crystalDropChance;
    private int crystalPickupRange;

    // 配置化文本
    private String msgCrystalFire;
    private String msgCrystalWater;
    private String msgCrystalIce;
    private String msgCrystalElectro;
    private String msgShieldApply;
    private String msgNormalDamage;
    private String msgAlreadyHasGeo;
    private String msgNoLore;

    // 日志配置
    private String logCrystalFire;
    private String logCrystalWater;
    private String logCrystalIce;
    private String logCrystalElectro;
    private String logShieldApply;
    private String logRemoveMark;
    private String logExpireMark;
    private String logConfigLoaded;
    private String logConfigBaseDamage;
    private String logConfigShield;
    private String logConfigElementDuration;

    // ✅ 版本兼容的抗性效果
    private static final PotionEffectType RESISTANCE_EFFECT;
    // ✅ 直接使用旧版 Spigot 必定存在的粒子常量（用 PORTAL 替代 SHIELD）
    private static final Particle SHIELD_PARTICLE = Particle.PORTAL;

    static {
        RESISTANCE_EFFECT = PotionEffectType.getByName("DAMAGE_RESISTANCE") != null
                ? PotionEffectType.getByName("DAMAGE_RESISTANCE")
                : PotionEffectType.getByName("RESISTANCE");
    }

    // 存储晶片实体和对应元素类型
    private final Map<UUID, String> crystalChipMap = new HashMap<>();
    private final Yuanshen plugin;

    public GeoElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadGeoConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadGeoConfig() {
        FileConfiguration config = plugin.getConfig();
        if (config == null) {
            plugin.getLogger().severe("岩元素配置加载失败：配置文件为空！");
            setDefaultConfigValues();
            return;
        }

        geoBaseDamage = Math.max(0, config.getDouble("geo_element.base_damage", 5.0));
        crystalShieldMultiplier = Math.max(1.0, config.getDouble("geo_element.crystal_shield_multiplier", 3.0));
        shieldDuration = Math.max(20, config.getInt("geo_element.shield_duration", 200));
        elementDuration = Math.max(20, config.getInt("geo_element.element_duration", 200));
        crystalChipExpireTime = Math.max(20, config.getInt("geo_element.crystal_chip_expire_time", 600));
        crystalDropChance = Math.max(0.0, Math.min(1.0, config.getDouble("geo_element.crystal_drop_chance", 1.0)));
        crystalPickupRange = Math.max(1, config.getInt("geo_element.crystal_pickup_range", 2));

        msgCrystalFire = getConfigMessage(config, "geo_element.messages.crystal_fire",
                "&6【结晶-火】&f触发！生成火元素晶片，拾取获得%s点护盾！");
        msgCrystalWater = getConfigMessage(config, "geo_element.messages.crystal_water",
                "&6【结晶-水】&f触发！生成水元素晶片，拾取获得%s点护盾！");
        msgCrystalIce = getConfigMessage(config, "geo_element.messages.crystal_ice",
                "&6【结晶-冰】&f触发！生成冰元素晶片，拾取获得%s点护盾！");
        msgCrystalElectro = getConfigMessage(config, "geo_element.messages.crystal_electro",
                "&6【结晶-雷】&f触发！生成雷元素晶片，拾取获得%s点护盾！");
        msgShieldApply = getConfigMessage(config, "geo_element.messages.shield_apply",
                "&6【岩元素护盾】&f已生效！吸收%s点伤害，持续%s秒！");
        msgNormalDamage = getConfigMessage(config, "geo_element.messages.normal_damage",
                "&6触发！额外造成%s点伤害并给敌方附着岩元素");
        msgAlreadyHasGeo = getConfigMessage(config, "geo_element.messages.already_has_geo",
                "&6敌方已附着岩元素，无额外伤害！");
        msgNoLore = config.getString("geo_element.messages.no_lore", "[岩元素] 玩家%s手持物品无「岩元素」Lore，不触发效果");

        logCrystalFire = config.getString("geo_element.logs.crystal_fire", "[岩元素-结晶火] 目标%s火元素触发结晶，生成火晶片，护盾值：%s");
        logCrystalWater = config.getString("geo_element.logs.crystal_water", "[岩元素-结晶水] 目标%s水元素触发结晶，生成水晶片，护盾值：%s");
        logCrystalIce = config.getString("geo_element.logs.crystal_ice", "[岩元素-结晶冰] 目标%s冰元素触发结晶，生成冰晶片，护盾值：%s");
        logCrystalElectro = config.getString("geo_element.logs.crystal_electro", "[岩元素-结晶雷] 目标%s雷元素触发结晶，生成雷晶片，护盾值：%s");
        logShieldApply = config.getString("geo_element.logs.shield_apply", "[岩元素护盾] 玩家%s获得%s点护盾，持续%s秒");
        logRemoveMark = config.getString("geo_element.logs.remove_mark", "[岩元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("geo_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("geo_element.logs.config_loaded", "岩元素配置加载完成：");
        logConfigBaseDamage = config.getString("geo_element.logs.config_base_damage", "基础伤害：%s");
        logConfigShield = config.getString("geo_element.logs.config_crystal_shield_multiplier", "结晶护盾倍率：%s");
        logConfigElementDuration = config.getString("geo_element.logs.config_element_duration", "元素过期时长：%s tick");

        if (logConfigLoaded != null) plugin.getLogger().info(logConfigLoaded);
        if (logConfigBaseDamage != null) plugin.getLogger().info(String.format(logConfigBaseDamage, geoBaseDamage));
        if (logConfigShield != null) plugin.getLogger().info(String.format(logConfigShield, crystalShieldMultiplier));
        if (logConfigElementDuration != null) plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    private void setDefaultConfigValues() {
        geoBaseDamage = 5.0;
        crystalShieldMultiplier = 3.0;
        shieldDuration = 200;
        elementDuration = 200;
        crystalChipExpireTime = 600;
        crystalDropChance = 1.0;
        crystalPickupRange = 2;

        msgCrystalFire = "&6【结晶-火】&f触发！生成火元素晶片，拾取获得%s点护盾！";
        msgCrystalWater = "&6【结晶-水】&f触发！生成水元素晶片，拾取获得%s点护盾！";
        msgCrystalIce = "&6【结晶-冰】&f触发！生成冰元素晶片，拾取获得%s点护盾！";
        msgCrystalElectro = "&6【结晶-雷】&f触发！生成雷元素晶片，拾取获得%s点护盾！";
        msgShieldApply = "&6【岩元素护盾】&f已生效！吸收%s点伤害，持续%s秒！";
        msgNormalDamage = "&6触发！额外造成%s点伤害并给敌方附着岩元素";
        msgAlreadyHasGeo = "&6敌方已附着岩元素，无额外伤害！";
        msgNoLore = "[岩元素] 玩家%s手持物品无「岩元素」Lore，不触发效果";
    }

    private String getConfigMessage(FileConfiguration config, String path, String defaultValue) {
        String raw = config.getString(path, defaultValue);
        return raw == null ? defaultValue : ChatColor.translateAlternateColorCodes('&', raw);
    }

    @EventHandler
    public void onPlayerAttackWithGeo(EntityDamageByEntityEvent e) {
        if (e == null || e.isCancelled()) return;
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) return;
        if (attacker.getUniqueId().equals(target.getUniqueId())) return;

        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasGeoLore = checkItemLore(handItem, GEO_LORE);
        if (!hasGeoLore) {
            if (msgNoLore != null) plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        boolean hasFire = false, hasWater = false, hasIce = false, hasElectro = false, hasGeo = false;
        try {
            hasFire = hasElement(target, FIRE_KEY);
            hasWater = hasElement(target, WATER_KEY);
            hasIce = hasElement(target, ICE_KEY);
            hasElectro = hasElement(target, ELECTRO_KEY);
            hasGeo = hasElement(target, GEO_KEY);
        } catch (Exception ex) {
            plugin.getLogger().severe("[岩元素] 检测目标元素时出错：" + ex.getMessage());
            ex.printStackTrace();
            return;
        }

        double totalDamage = e.getDamage();
        boolean needAddGeoMark = true;
        double shieldValue = geoBaseDamage * crystalShieldMultiplier;

        try {
            // 1. 火结晶
            if (hasFire) {
                if (Math.random() <= crystalDropChance) spawnCrystalChip(target.getLocation(), FIRE_KEY, shieldValue);
                removeElement(target, FIRE_KEY);
                if (msgCrystalFire != null) attacker.sendMessage(GEO_TAG + String.format(msgCrystalFire, String.format("%.1f", shieldValue)));
                if (logCrystalFire != null) plugin.getLogger().info(String.format(logCrystalFire, target.getName(), String.format("%.1f", shieldValue)));
                needAddGeoMark = false;
            }
            // 2. 水结晶
            else if (hasWater) {
                if (Math.random() <= crystalDropChance) spawnCrystalChip(target.getLocation(), WATER_KEY, shieldValue);
                removeElement(target, WATER_KEY);
                if (msgCrystalWater != null) attacker.sendMessage(GEO_TAG + String.format(msgCrystalWater, String.format("%.1f", shieldValue)));
                if (logCrystalWater != null) plugin.getLogger().info(String.format(logCrystalWater, target.getName(), String.format("%.1f", shieldValue)));
                needAddGeoMark = false;
            }
            // 3. 冰结晶
            else if (hasIce) {
                if (Math.random() <= crystalDropChance) spawnCrystalChip(target.getLocation(), ICE_KEY, shieldValue);
                removeElement(target, ICE_KEY);
                if (msgCrystalIce != null) attacker.sendMessage(GEO_TAG + String.format(msgCrystalIce, String.format("%.1f", shieldValue)));
                if (logCrystalIce != null) plugin.getLogger().info(String.format(logCrystalIce, target.getName(), String.format("%.1f", shieldValue)));
                needAddGeoMark = false;
            }
            // 4. 雷结晶
            else if (hasElectro) {
                if (Math.random() <= crystalDropChance) spawnCrystalChip(target.getLocation(), ELECTRO_KEY, shieldValue);
                removeElement(target, ELECTRO_KEY);
                if (msgCrystalElectro != null) attacker.sendMessage(GEO_TAG + String.format(msgCrystalElectro, String.format("%.1f", shieldValue)));
                if (logCrystalElectro != null) plugin.getLogger().info(String.format(logCrystalElectro, target.getName(), String.format("%.1f", shieldValue)));
                needAddGeoMark = false;
            }
            // 5. 普通岩伤
            else if (!hasGeo) {
                totalDamage += geoBaseDamage;
                if (msgNormalDamage != null) attacker.sendMessage(GEO_TAG + String.format(msgNormalDamage, String.format("%.1f", geoBaseDamage)));
            }
            // 6. 已有岩元素
            else {
                if (msgAlreadyHasGeo != null) attacker.sendMessage(GEO_TAG + msgAlreadyHasGeo);
                needAddGeoMark = false;
            }

            e.setDamage(Math.max(0, totalDamage));

            if (needAddGeoMark && !target.isDead() && target.isValid()) {
                addElementWithExpire(target, GEO_KEY, elementDuration);
            }

            String logTemplate = "[岩元素] 目标：%s | 火：%s | 水：%s | 冰：%s | 雷：%s | 岩：%s | 最终伤害：%.1f";
            if (logTemplate != null) plugin.getLogger().info(String.format(logTemplate,
                    target.getName(), hasFire, hasWater, hasIce, hasElectro, hasGeo,
                    String.format("%.1f", totalDamage)));
        } catch (Exception ex) {
            plugin.getLogger().severe("[岩元素] 结晶反应逻辑执行出错：" + ex.getMessage());
            ex.printStackTrace();
        }
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
            meta.setCustomModelData(1000 + elementKey.hashCode());
            chipItem.setItemMeta(meta);
        }

        Item chipEntity = location.getWorld().dropItem(location.add(0, 1, 0), chipItem);
        chipEntity.setPickupDelay(0);
        chipEntity.setInvulnerable(true);
        chipEntity.setGlowing(true);

        UUID chipId = chipEntity.getUniqueId();
        crystalChipMap.put(chipId, elementKey + "|" + shieldValue);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (chipEntity.isValid()) {
                    chipEntity.remove();
                    crystalChipMap.remove(chipId);
                    plugin.getLogger().info("[岩元素] 结晶晶片已过期移除：" + elementKey);
                }
            }
        }.runTaskLater(plugin, crystalChipExpireTime);
    }

    @EventHandler
    public void onCrystalChipPickup(PlayerPickupItemEvent e) {
        Player player = e.getPlayer();
        Item chipEntity = e.getItem();
        UUID chipId = chipEntity.getUniqueId();

        if (crystalChipMap.containsKey(chipId)) {
            String[] data = crystalChipMap.get(chipId).split("\\|");
            if (data.length != 2) return;

            double shieldValue = Double.parseDouble(data[1]);
            applyShieldToPlayer(player, shieldValue, shieldDuration);

            chipEntity.remove();
            crystalChipMap.remove(chipId);
            e.setCancelled(true);

            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            player.getWorld().spawnParticle(SHIELD_PARTICLE, player.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
        }
    }

    private void applyShieldToPlayer(Player player, double shieldValue, int duration) {
        if (player == null || !player.isOnline() || shieldValue <= 0 || duration <= 0 || RESISTANCE_EFFECT == null) {
            plugin.getLogger().warning("[岩元素护盾] 无法添加护盾：参数无效或抗性效果不存在");
            return;
        }

        int resistanceLevel = (int) (shieldValue / 5);
        if (resistanceLevel < 1) resistanceLevel = 1;

        player.removePotionEffect(RESISTANCE_EFFECT);
        PotionEffect shieldEffect = new PotionEffect(RESISTANCE_EFFECT, duration, resistanceLevel - 1);
        player.addPotionEffect(shieldEffect);

        double durationSecond = duration / 20.0;
        if (msgShieldApply != null) player.sendMessage(GEO_TAG + String.format(msgShieldApply,
                String.format("%.1f", shieldValue),
                String.format("%.1f", durationSecond)));
        if (logShieldApply != null) plugin.getLogger().info(String.format(logShieldApply,
                player.getName(),
                String.format("%.1f", shieldValue),
                String.format("%.1f", durationSecond)));
    }

    // ========== 通用工具方法 ==========
    private boolean checkItemLore(ItemStack item, String keyword) {
        if (item == null || item.getType() == Material.AIR || keyword == null || keyword.isEmpty()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        for (String loreLine : meta.getLore()) {
            if (loreLine != null && ChatColor.stripColor(loreLine).contains(keyword)) return true;
        }
        return false;
    }

    private boolean hasElement(LivingEntity target, String elementKey) {
        if (target == null || elementKey == null || elementKey.isEmpty()) return false;
        try {
            return target.hasMetadata(elementKey) && !target.getMetadata(elementKey).isEmpty()
                    && target.getMetadata(elementKey).get(0).asBoolean();
        } catch (Exception ex) {
            plugin.getLogger().warning("[岩元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
            return false;
        }
    }

    private void removeElement(LivingEntity target, String elementKey) {
        if (target == null || elementKey == null || elementKey.isEmpty()) return;
        if (target.hasMetadata(elementKey)) {
            target.removeMetadata(elementKey, plugin);
            if (logRemoveMark != null) plugin.getLogger().info(String.format(logRemoveMark, target.getName(), elementKey));
        }
    }

    private void addElementWithExpire(LivingEntity target, String elementKey, int durationTicks) {
        if (target == null || elementKey == null || elementKey.isEmpty() || durationTicks <= 0) return;
        removeElement(target, elementKey);

        FixedMetadataValue elementMark = new FixedMetadataValue(plugin, true);
        target.setMetadata(elementKey, elementMark);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (target != null && target.isValid()) {
                    removeElement(target, elementKey);
                    if (logExpireMark != null) plugin.getLogger().info(String.format(logExpireMark, target.getName(), elementKey));
                }
            }
        }.runTaskLater(plugin, durationTicks);
    }
}