package org.yuanshen.yuanshen;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ElementDamageCalculator {
    private final Yuanshen plugin;
    private final ConfigParser configParser;
    private final Map<UUID, PlayerStats> statsCache = new HashMap<>();

    public ElementDamageCalculator(Yuanshen plugin, ConfigParser configParser) {
        this.plugin = plugin;
        this.configParser = configParser;
    }

    public DamageResult calculateElementDamage(Player attacker, LivingEntity target,
                                               String element, ItemStack weapon) {
        return calculateElementDamage(attacker, target, element, weapon, null);
    }

    public DamageResult calculateElementDamage(Player attacker, LivingEntity target,
                                               String element, ItemStack weapon, PlayerStats statsOverride) {
        DamageResult result = new DamageResult();

        PlayerStats stats = statsOverride != null ? statsOverride : getPlayerStats(attacker);
        result.setPlayerStats(stats);
        if (PlayerStats.PHYSICAL_KEY.equals(element)) {
            double physicalDamage = stats.getAttackDamage() * (1 + stats.getElementBonus(PlayerStats.PHYSICAL_KEY));
            result.setPhysicalDamage(physicalDamage);
            result.setElementalDamage(0.0);
        } else {
            result.setPhysicalDamage(stats.getAttackDamage());
            double elementBase = getBaseDamageFromConfig(attacker, element);
            double elementBonus = stats.getElementBonus(element);
            double elementalDamage = elementBase * (1 + elementBonus);
            result.setElementalDamage(elementalDamage);
        }
        result.setCrit(checkCrit(stats.getCritRate()));
        result.setElement(element);
        return result;
    }

    private double getBaseDamageFromConfig(Player player, String element) {
        if (element == null || PlayerStats.PHYSICAL_KEY.equals(element)) {
            return 0.0;
        }
        String elementName = element.replace("element_", "");
        String path = "elements." + elementName + "_base_damage";
        return configParser.parseDouble(path, player, 5.0);
    }

    public PlayerStats getPlayerStats(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerStats cached = statsCache.get(uuid);
        if (cached != null && System.currentTimeMillis() - cached.getTimestamp() < 5000) {
            return cached;
        }

        PlayerStats stats = new PlayerStats();
        stats.setTimestamp(System.currentTimeMillis());
        loadDefaultStats(player, stats);
        boolean characterMode = plugin.isCharacterModeActive(player);

        if (plugin.getLoreStatParser() != null) {
            plugin.getLoreStatParser().applyLoreStats(player, stats);
        }
        if (characterMode && plugin.getCharacterManager() != null) {
            plugin.getCharacterManager().applyStats(player, stats);
        }
        if (characterMode && plugin.getWeaponManager() != null) {
            plugin.getWeaponManager().applyStats(player, stats);
        }

        statsCache.put(uuid, stats);
        return stats;
    }

    private void loadDefaultStats(Player player, PlayerStats stats) {
        boolean characterMode = plugin.isCharacterModeActive(player);

        stats.setAttackDamage(characterMode
                ? configParser.parseDouble("default_stats.attack_damage", player, 0)
                : 0.0);
        XianglingPepperManager xianglingPepperManager = plugin.getXianglingPepperManager();
        if (characterMode && xianglingPepperManager != null) {
            stats.setAttackDamage(stats.getAttackDamage() * (1.0 + xianglingPepperManager.getAttackBonus(player)));
        }
        stats.setCritRate(configParser.parseDouble("default_stats.crit_rate", player, 0.05));
        HuTaoStateManager huTaoStateManager = plugin.getHuTaoStateManager();
        if (characterMode && huTaoStateManager != null) {
            stats.setCritRate(stats.getCritRate() + huTaoStateManager.getOtherCharacterCritRateBonus(player));
            if (plugin.getConstellationManager() != null
                    && plugin.getConstellationManager().hasConstellation(player, CharacterType.HUTAO, 6)
                    && plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.HUTAO)
                    && huTaoStateManager.isLowHp(player)) {
                stats.setCritRate(stats.getCritRate() + 0.25);
            }
        }
        KeqingSkillHandler keqingSkillHandler = plugin.getKeqingSkillHandler();
        if (characterMode && keqingSkillHandler != null) {
            stats.setCritRate(stats.getCritRate() + keqingSkillHandler.getBurstCritRateBonus(player));
        }
        stats.setCritDamage(configParser.parseDouble("default_stats.crit_damage", player, 0.5));
        if (characterMode
                && huTaoStateManager != null
                && plugin.getConstellationManager() != null
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.HUTAO, 6)
                && plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.HUTAO)
                && huTaoStateManager.isLowHp(player)) {
            stats.setCritDamage(stats.getCritDamage() + 0.50);
        }
        stats.setElementMastery(configParser.parseDouble("default_stats.element_mastery", player, 0));
        stats.setEnergyRecharge(configParser.parseDouble("default_stats.energy_recharge", player, 0));
        if (characterMode && keqingSkillHandler != null) {
            stats.setEnergyRecharge(stats.getEnergyRecharge() + keqingSkillHandler.getBurstEnergyRechargeBonus(player));
        }
        stats.setHealingBonus(configParser.parseDouble("default_stats.healing_bonus", player, 0));
        stats.setDefense(configParser.parseDouble("default_stats.defense", player, 0));

        double configuredHealth = configParser.parseDouble("default_stats.health", player, 20);
        stats.setHealth(Math.max(configuredHealth, plugin.getVanillaMaxHealthBaseline(player)));
        YelanSkillHandler yelanSkillHandler = plugin.getYelanSkillHandler();
        if (characterMode
                && yelanSkillHandler != null
                && plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.YELAN)) {
            stats.setHealth(stats.getHealth() * (1.0 + yelanSkillHandler.getPassiveMaxHealthBonus(player)));
            stats.setHealth(stats.getHealth() * (1.0 + yelanSkillHandler.getConstellationHealthBonus(player)));
        }

        stats.setElementBonus(ElementConstant.FIRE_KEY, configParser.parseDouble("default_stats.fire_damage", player, 0));
        stats.setElementBonus(ElementConstant.WATER_KEY, configParser.parseDouble("default_stats.water_damage", player, 0));
        stats.setElementBonus(ElementConstant.ICE_KEY, configParser.parseDouble("default_stats.ice_damage", player, 0));
        stats.setElementBonus(ElementConstant.ELECTRO_KEY, configParser.parseDouble("default_stats.electro_damage", player, 0));
        stats.setElementBonus(ElementConstant.ANEMO_KEY, configParser.parseDouble("default_stats.anemo_damage", player, 0));
        stats.setElementBonus(ElementConstant.GEO_KEY, configParser.parseDouble("default_stats.geo_damage", player, 0));
        stats.setElementBonus(ElementConstant.DENDRO_KEY, configParser.parseDouble("default_stats.dendro_damage", player, 0));

        if (characterMode
                && plugin.getConstellationManager() != null
                && plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.XIANGLING)) {
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.XIANGLING, 2)) {
                stats.setElementBonus(ElementConstant.FIRE_KEY,
                        stats.getElementBonus(ElementConstant.FIRE_KEY) + 0.10);
            }
            if (plugin.getConstellationManager().hasConstellation(player, CharacterType.XIANGLING, 6)
                    && plugin.getXianglingPyronadoManager() != null
                    && plugin.getXianglingPyronadoManager().isActive(player)) {
                stats.setElementBonus(ElementConstant.FIRE_KEY,
                        stats.getElementBonus(ElementConstant.FIRE_KEY) + 0.15);
            }
        }
        if (characterMode
                && plugin.getConstellationManager() != null
                && plugin.getCharacterResolver().isSelectedCharacter(player, CharacterType.NINGGUANG)
                && plugin.getConstellationManager().hasConstellation(player, CharacterType.NINGGUANG, 4)
                && plugin.getNingguangSkillHandler() != null
                && plugin.getNingguangSkillHandler().hasActiveJadeScreen(player)) {
            stats.setElementBonus(ElementConstant.GEO_KEY,
                    stats.getElementBonus(ElementConstant.GEO_KEY) + 0.15);
        }

        stats.setResistance(ElementConstant.FIRE_KEY, configParser.parseDouble("default_stats.fire_resistance", player, 0));
        stats.setResistance(ElementConstant.WATER_KEY, configParser.parseDouble("default_stats.water_resistance", player, 0));
        stats.setResistance(ElementConstant.ICE_KEY, configParser.parseDouble("default_stats.ice_resistance", player, 0));
        stats.setResistance(ElementConstant.ELECTRO_KEY, configParser.parseDouble("default_stats.electro_resistance", player, 0));
        stats.setResistance(ElementConstant.ANEMO_KEY, configParser.parseDouble("default_stats.anemo_resistance", player, 0));
        stats.setResistance(ElementConstant.GEO_KEY, configParser.parseDouble("default_stats.geo_resistance", player, 0));
        stats.setResistance(ElementConstant.DENDRO_KEY, configParser.parseDouble("default_stats.dendro_resistance", player, 0));
        stats.setResistance(PlayerStats.PHYSICAL_KEY, configParser.parseDouble("default_stats.physical_resistance", player, 0));
    }

    private boolean checkCrit(double critRate) {
        return Math.random() < critRate;
    }

    public void clearCache() {
        statsCache.clear();
    }

    public void invalidatePlayerStats(Player player) {
        if (player != null) {
            boolean removedStats = statsCache.remove(player.getUniqueId()) != null;
            int removedExpressions = configParser.invalidatePlayer(player);
            if ((removedStats || removedExpressions > 0) && plugin.shouldLogCacheInvalidation()) {
                plugin.getLogger().info("Cleared cache for player " + player.getName()
                        + ": stats=" + (removedStats ? 1 : 0)
                        + ", expressions=" + removedExpressions);
            }
        }
    }
}
