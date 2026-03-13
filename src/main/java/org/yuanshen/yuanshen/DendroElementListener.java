package org.yuanshen.yuanshen;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.yuanshen.yuanshen.ElementConstant.*;

/**
 * 草元素监听器 - 兼容旧版本 Spigot/Bukkit
 */
public class DendroElementListener implements Listener {
    // 配置化数值
    private double dendroBaseDamage;
    private double burningDamagePerTick;
    private int burningDuration;
    private double bloomSeedDamage;
    private int bloomSeedExpireTime;
    private double aggravateMultiplier;
    private int aggravateDuration;
    private double hyperbloomMultiplier;
    private double burgeonMultiplier;
    private int elementDuration;
    private double seedSpawnChance;

    // 配置化文本
    private String msgBurning;
    private String msgBloom;
    private String msgAggravate;
    private String msgHyperbloom;
    private String msgBurgeon;
    private String msgNormalDamage;
    private String msgAlreadyHasDendro;
    private String msgNoLore;

    // 日志配置
    private String logBurning;
    private String logBloom;
    private String logAggravate;
    private String logHyperbloom;
    private String logBurgeon;
    private String logRemoveMark;
    private String logExpireMark;
    private String logConfigLoaded;
    private String logConfigBaseDamage;
    private String logConfigBurning;
    private String logConfigElementDuration;

    // ✅ 直接使用旧版 Spigot 必定存在的粒子常量
    private static final Particle HYPERBLOOM_PARTICLE = Particle.ENTITY_EFFECT;
    private static final Particle BURGEON_PARTICLE = Particle.POOF;
    private static final Particle AGGRAVATE_PARTICLE = Particle.HAPPY_VILLAGER;

    // 存储草种子实体
    private final Map<UUID, Double> dendroSeedMap = new HashMap<>();
    // 存储玩家激化状态
    private final Map<UUID, Double> aggravateMap = new HashMap<>();
    private final Yuanshen plugin;

    public DendroElementListener(Yuanshen plugin) {
        this.plugin = plugin;
        loadDendroConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadDendroConfig() {
        FileConfiguration config = plugin.getConfig();
        if (config == null) {
            plugin.getLogger().severe("草元素配置加载失败：配置文件为空！");
            setDefaultConfigValues();
            return;
        }

        dendroBaseDamage = Math.max(0, config.getDouble("dendro_element.base_damage", 4.0));
        burningDamagePerTick = Math.max(0, config.getDouble("dendro_element.burning_damage_per_tick", 0.5));
        burningDuration = Math.max(20, config.getInt("dendro_element.burning_duration", 100));
        bloomSeedDamage = Math.max(0, config.getDouble("dendro_element.bloom_seed_damage", 8.0));
        bloomSeedExpireTime = Math.max(20, config.getInt("dendro_element.bloom_seed_expire_time", 400));
        aggravateMultiplier = Math.max(1.0, config.getDouble("dendro_element.aggravate_multiplier", 1.5));
        hyperbloomMultiplier = Math.max(1.0, config.getDouble("dendro_element.hyperbloom_multiplier", 2.0));
        burgeonMultiplier = Math.max(1.0, config.getDouble("dendro_element.burgeon_multiplier", 2.5));
        aggravateDuration = Math.max(20, config.getInt("dendro_element.aggravate_duration", 200));
        elementDuration = Math.max(20, config.getInt("dendro_element.element_duration", 200));
        seedSpawnChance = Math.max(0.0, Math.min(1.0, config.getDouble("dendro_element.seed_spawn_chance", 1.0)));

        msgBurning = getConfigMessage(config, "dendro_element.messages.burning",
                "&2【燃烧】&f触发！敌方每%s秒受到%s点火伤，持续%s秒！");
        msgBloom = getConfigMessage(config, "dendro_element.messages.bloom",
                "&2【绽放】&f触发！生成草种子，火/雷触发可造成额外伤害！");
        msgAggravate = getConfigMessage(config, "dendro_element.messages.aggravate",
                "&2【激化】&f触发！雷元素伤害提升%s%%，持续%s秒！");
        msgHyperbloom = getConfigMessage(config, "dendro_element.messages.hyperbloom",
                "&2【超激化】&f触发！草种子被雷触发，雷伤提升%s%%，造成%s点额外伤害！");
        msgBurgeon = getConfigMessage(config, "dendro_element.messages.burgeon",
                "&2【烈绽放】&f触发！草种子被火触发，造成%s点范围爆炸伤害！");
        msgNormalDamage = getConfigMessage(config, "dendro_element.messages.normal_damage",
                "&2触发！额外造成%s点伤害并给敌方附着草元素");
        msgAlreadyHasDendro = getConfigMessage(config, "dendro_element.messages.already_has_dendro",
                "&2敌方已附着草元素，无额外伤害！");
        msgNoLore = config.getString("dendro_element.messages.no_lore", "[草元素] 玩家%s手持物品无「草元素」Lore，不触发效果");

        logBurning = config.getString("dendro_element.logs.burning", "[草元素-燃烧] 目标%s火元素触发燃烧，每tick伤害：%s，持续%s秒");
        logBloom = config.getString("dendro_element.logs.bloom", "[草元素-绽放] 目标%s水元素触发绽放，生成草种子，基础伤害：%s");
        logAggravate = config.getString("dendro_element.logs.aggravate", "[草元素-激化] 玩家%s雷伤提升%s%%，持续%s秒");
        logHyperbloom = config.getString("dendro_element.logs.hyperbloom", "[草元素-超激化] 草种子触发，玩家%s雷伤提升%s%%，额外伤害：%s");
        logBurgeon = config.getString("dendro_element.logs.burgeon", "[草元素-烈绽放] 草种子触发，范围爆炸伤害：%s");
        logRemoveMark = config.getString("dendro_element.logs.remove_mark", "[草元素] 已移除目标%s的%s标记");
        logExpireMark = config.getString("dendro_element.logs.expire_mark", "[元素过期] 目标%s的%s标记已移除");
        logConfigLoaded = config.getString("dendro_element.logs.config_loaded", "草元素配置加载完成：");
        logConfigBaseDamage = config.getString("dendro_element.logs.config_base_damage", "基础伤害：%s");
        logConfigBurning = config.getString("dendro_element.logs.config_burning_damage", "燃烧每tick伤害：%s");
        logConfigElementDuration = config.getString("dendro_element.logs.config_element_duration", "元素过期时长：%s tick");

        if (logConfigLoaded != null) plugin.getLogger().info(logConfigLoaded);
        if (logConfigBaseDamage != null) plugin.getLogger().info(String.format(logConfigBaseDamage, dendroBaseDamage));
        if (logConfigBurning != null) plugin.getLogger().info(String.format(logConfigBurning, burningDamagePerTick));
        if (logConfigElementDuration != null) plugin.getLogger().info(String.format(logConfigElementDuration, elementDuration));
    }

    private void setDefaultConfigValues() {
        dendroBaseDamage = 4.0;
        burningDamagePerTick = 0.5;
        burningDuration = 100;
        bloomSeedDamage = 8.0;
        bloomSeedExpireTime = 400;
        aggravateMultiplier = 1.5;
        hyperbloomMultiplier = 2.0;
        burgeonMultiplier = 2.5;
        aggravateDuration = 200;
        elementDuration = 200;
        seedSpawnChance = 1.0;

        msgBurning = "&2【燃烧】&f触发！敌方每%s秒受到%s点火伤，持续%s秒！";
        msgBloom = "&2【绽放】&f触发！生成草种子，火/雷触发可造成额外伤害！";
        msgAggravate = "&2【激化】&f触发！雷元素伤害提升%s%%，持续%s秒！";
        msgHyperbloom = "&2【超激化】&f触发！草种子被雷触发，雷伤提升%s%%，造成%s点额外伤害！";
        msgBurgeon = "&2【烈绽放】&f触发！草种子被火触发，造成%s点范围爆炸伤害！";
        msgNormalDamage = "&2触发！额外造成%s点伤害并给敌方附着草元素";
        msgAlreadyHasDendro = "&2敌方已附着草元素，无额外伤害！";
        msgNoLore = "[草元素] 玩家%s手持物品无「草元素」Lore，不触发效果";
    }

    private String getConfigMessage(FileConfiguration config, String path, String defaultValue) {
        String raw = config.getString(path, defaultValue);
        return raw == null ? defaultValue : ChatColor.translateAlternateColorCodes('&', raw);
    }

    @EventHandler
    public void onPlayerAttackWithDendro(EntityDamageByEntityEvent e) {
        if (e == null || e.isCancelled()) return;
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity target)) return;
        if (attacker.getUniqueId().equals(target.getUniqueId())) return;

        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        boolean hasDendroLore = checkItemLore(handItem, DENDRO_LORE);
        if (!hasDendroLore) {
            if (msgNoLore != null) plugin.getLogger().info(String.format(msgNoLore, attacker.getName()));
            return;
        }

        String attackerElement = getAttackerElement(handItem);
        boolean hasFire = hasElement(target, FIRE_KEY);
        boolean hasWater = hasElement(target, WATER_KEY);
        boolean hasElectro = hasElement(target, ELECTRO_KEY);
        boolean hasDendro = hasElement(target, DENDRO_KEY);

        double totalDamage = e.getDamage();
        boolean needAddDendroMark = true;

        try {
            // 1. 草+火 → 燃烧
            if (attackerElement.equals(DENDRO_LORE) && hasFire) {
                startBurningTask(target, burningDuration, burningDamagePerTick);
                removeElement(target, FIRE_KEY);

                double damagePerSecond = burningDamagePerTick * 20;
                double durationSecond = burningDuration / 20.0;
                if (msgBurning != null) attacker.sendMessage(DENDRO_TAG + String.format(msgBurning,
                        String.format("%.1f", 1.0),
                        String.format("%.1f", damagePerSecond),
                        String.format("%.1f", durationSecond)));
                if (logBurning != null) plugin.getLogger().info(String.format(logBurning,
                        target.getName(),
                        String.format("%.1f", burningDamagePerTick),
                        String.format("%.1f", durationSecond)));
                needAddDendroMark = false;
            }
            // 2. 草+水 → 绽放（生成草种子）
            else if (attackerElement.equals(DENDRO_LORE) && hasWater) {
                if (Math.random() <= seedSpawnChance) spawnBloomSeed(attacker.getLocation());
                removeElement(target, WATER_KEY);

                if (msgBloom != null) attacker.sendMessage(DENDRO_TAG + msgBloom);
                if (logBloom != null) plugin.getLogger().info(String.format(logBloom, target.getName(), String.format("%.1f", bloomSeedDamage)));
                needAddDendroMark = false;
            }
            // 3. 草+雷 → 激化（提升雷伤）
            else if (attackerElement.equals(DENDRO_LORE) && hasElectro) {
                applyAggravateToPlayer(attacker, aggravateMultiplier, aggravateDuration);
                removeElement(target, ELECTRO_KEY);

                double multiplierPercent = (aggravateMultiplier - 1) * 100;
                double durationSecond = aggravateDuration / 20.0;
                if (msgAggravate != null) attacker.sendMessage(DENDRO_TAG + String.format(msgAggravate,
                        String.format("%.0f", multiplierPercent),
                        String.format("%.1f", durationSecond)));
                if (logAggravate != null) plugin.getLogger().info(String.format(logAggravate,
                        attacker.getName(),
                        String.format("%.0f", multiplierPercent),
                        String.format("%.1f", durationSecond)));
                needAddDendroMark = false;
            }
            // 4. 雷+草种子 → 超激化
            else if (attackerElement.equals(ELECTRO_LORE) && hasNearbyDendroSeed(attacker.getLocation())) {
                triggerHyperbloom(attacker, attacker.getLocation());
                needAddDendroMark = false;
            }
            // 5. 火+草种子 → 烈绽放
            else if (attackerElement.equals(FIRE_LORE) && hasNearbyDendroSeed(attacker.getLocation())) {
                triggerBurgeon(attacker.getLocation());
                needAddDendroMark = false;
            }
            // 6. 普通草伤
            else if (attackerElement.equals(DENDRO_LORE) && !hasDendro) {
                double finalDamage = dendroBaseDamage;
                if (aggravateMap.containsKey(attacker.getUniqueId())) finalDamage *= aggravateMap.get(attacker.getUniqueId());
                totalDamage += finalDamage;

                if (msgNormalDamage != null) attacker.sendMessage(DENDRO_TAG + String.format(msgNormalDamage, String.format("%.1f", finalDamage)));
            }
            // 7. 已有草元素
            else if (attackerElement.equals(DENDRO_LORE) && hasDendro) {
                if (msgAlreadyHasDendro != null) attacker.sendMessage(DENDRO_TAG + msgAlreadyHasDendro);
                needAddDendroMark = false;
            }

            e.setDamage(Math.max(0, totalDamage));

            if (needAddDendroMark && attackerElement.equals(DENDRO_LORE) && !target.isDead() && target.isValid()) {
                addElementWithExpire(target, DENDRO_KEY, elementDuration);
            }

            plugin.getLogger().info(String.format(
                    "[草元素] 攻击者元素：%s | 目标：%s | 火：%s | 水：%s | 雷：%s | 草：%s | 最终伤害：%.1f",
                    attackerElement, target.getName(), hasFire, hasWater, hasElectro, hasDendro,
                    String.format("%.1f", totalDamage)));
        } catch (Exception ex) {
            plugin.getLogger().severe("[草元素] 反应逻辑执行出错：" + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private String getAttackerElement(ItemStack item) {
        if (checkItemLore(item, FIRE_LORE)) return FIRE_LORE;
        if (checkItemLore(item, WATER_LORE)) return WATER_LORE;
        if (checkItemLore(item, ELECTRO_LORE)) return ELECTRO_LORE;
        if (checkItemLore(item, DENDRO_LORE)) return DENDRO_LORE;
        return "NONE";
    }

    private void startBurningTask(LivingEntity target, int duration, double damagePerTick) {
        new BukkitRunnable() {
            int remainingTicks = duration;

            @Override
            public void run() {
                if (remainingTicks <= 0 || target == null || target.isDead() || !target.isValid()) {
                    this.cancel();
                    return;
                }

                if (remainingTicks % 20 == 0) {
                    target.damage(damagePerTick * 20);
                    target.getWorld().spawnParticle(Particle.FLAME, target.getLocation(), 5, 0.5, 0.5, 0.5, 0.1);
                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.5f, 1.0f);
                }

                remainingTicks--;
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private void spawnBloomSeed(Location playerLoc) {
        if (playerLoc == null) return;

        double randomX = playerLoc.getX() + (Math.random() * 5 - 2.5);
        double randomZ = playerLoc.getZ() + (Math.random() * 5 - 2.5);
        Location spawnLoc = new Location(playerLoc.getWorld(), randomX, playerLoc.getY(), randomZ);

        while (spawnLoc.getBlock().getType() == Material.AIR && spawnLoc.getY() > 0) spawnLoc.subtract(0, 1, 0);
        spawnLoc.add(0, 1, 0);

        ItemStack seedItem = new ItemStack(Material.GREEN_STAINED_GLASS, 1);
        ItemMeta meta = seedItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "草种子");
            meta.setCustomModelData(2000);
            seedItem.setItemMeta(meta);
        }

        Item seedEntity = spawnLoc.getWorld().dropItem(spawnLoc, seedItem);
        seedEntity.setPickupDelay(0);
        seedEntity.setInvulnerable(true);
        seedEntity.setGlowing(true);
        seedEntity.setVelocity(new Vector(0, 0.2, 0));

        UUID seedId = seedEntity.getUniqueId();
        dendroSeedMap.put(seedId, bloomSeedDamage);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (seedEntity.isValid()) {
                    seedEntity.remove();
                    dendroSeedMap.remove(seedId);
                    plugin.getLogger().info("[草元素] 草种子已过期移除");
                }
            }
        }.runTaskLater(plugin, bloomSeedExpireTime);
    }

    private boolean hasNearbyDendroSeed(Location loc) {
        if (loc == null) return false;

        for (Item seed : loc.getWorld().getEntitiesByClass(Item.class)) {
            if (seed.getLocation().distance(loc) <= 3 && dendroSeedMap.containsKey(seed.getUniqueId())) return true;
        }
        return false;
    }

    private void triggerHyperbloom(Player player, Location loc) {
        if (player == null || loc == null) return;

        Item nearestSeed = null;
        double minDistance = Double.MAX_VALUE;

        for (Item seed : loc.getWorld().getEntitiesByClass(Item.class)) {
            double distance = seed.getLocation().distance(loc);
            if (distance <= 3 && dendroSeedMap.containsKey(seed.getUniqueId()) && distance < minDistance) {
                nearestSeed = seed;
                minDistance = distance;
            }
        }

        if (nearestSeed == null) return;

        double seedDamage = dendroSeedMap.get(nearestSeed.getUniqueId());
        double hyperbloomDamage = seedDamage * hyperbloomMultiplier;

        double newMultiplier = aggravateMultiplier * 1.2;
        applyAggravateToPlayer(player, newMultiplier, aggravateDuration);

        for (LivingEntity entity : loc.getWorld().getLivingEntities()) {
            if (entity.getLocation().distance(loc) <= 3 && !(entity instanceof Player) && entity.isValid()) {
                entity.damage(hyperbloomDamage);
            }
        }

        nearestSeed.remove();
        dendroSeedMap.remove(nearestSeed.getUniqueId());

        double multiplierPercent = (newMultiplier - 1) * 100;
        if (msgHyperbloom != null) player.sendMessage(DENDRO_TAG + String.format(msgHyperbloom,
                String.format("%.0f", multiplierPercent),
                String.format("%.1f", hyperbloomDamage)));
        if (logHyperbloom != null) plugin.getLogger().info(String.format(logHyperbloom,
                player.getName(),
                String.format("%.0f", multiplierPercent),
                String.format("%.1f", hyperbloomDamage)));

        loc.getWorld().spawnParticle(HYPERBLOOM_PARTICLE, loc, 30, 1, 1, 1, 0.2);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
    }

    private void triggerBurgeon(Location loc) {
        if (loc == null) return;

        Item nearestSeed = null;
        double minDistance = Double.MAX_VALUE;

        for (Item seed : loc.getWorld().getEntitiesByClass(Item.class)) {
            double distance = seed.getLocation().distance(loc);
            if (distance <= 3 && dendroSeedMap.containsKey(seed.getUniqueId()) && distance < minDistance) {
                nearestSeed = seed;
                minDistance = distance;
            }
        }

        if (nearestSeed == null) return;

        double seedDamage = dendroSeedMap.get(nearestSeed.getUniqueId());
        double burgeonDamage = seedDamage * burgeonMultiplier;

        for (LivingEntity entity : loc.getWorld().getLivingEntities()) {
            if (entity.getLocation().distance(loc) <= 4 && entity.isValid()) {
                entity.damage(burgeonDamage);
                Vector knockback = entity.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(1.0);
                knockback.setY(0.3);
                entity.setVelocity(knockback);
            }
        }

        nearestSeed.remove();
        dendroSeedMap.remove(nearestSeed.getUniqueId());

        if (logBurgeon != null) plugin.getLogger().info(String.format(logBurgeon, String.format("%.1f", burgeonDamage)));

        loc.getWorld().spawnParticle(BURGEON_PARTICLE, loc, 10, 1, 1, 1, 0.1);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.0f);
    }

    private void applyAggravateToPlayer(Player player, double multiplier, int duration) {
        if (player == null || !player.isOnline() || multiplier <= 1 || duration <= 0) return;

        UUID playerId = player.getUniqueId();
        aggravateMap.put(playerId, multiplier);

        new BukkitRunnable() {
            @Override
            public void run() {
                aggravateMap.remove(playerId);
                player.sendMessage(DENDRO_TAG + ChatColor.GREEN + "【激化效果】已结束！");
            }
        }.runTaskLater(plugin, duration);

        player.getWorld().spawnParticle(AGGRAVATE_PARTICLE, player.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
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
            plugin.getLogger().warning("[草元素检测] 检测" + elementKey + "时出错：" + ex.getMessage());
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