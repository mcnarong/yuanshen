package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoreStatParser {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[:\\uFF1A]?\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*%?");
    private static final List<String> ALL_ELEMENT_KEYS = List.of(
            ElementConstant.FIRE_KEY,
            ElementConstant.WATER_KEY,
            ElementConstant.ICE_KEY,
            ElementConstant.ELECTRO_KEY,
            ElementConstant.ANEMO_KEY,
            ElementConstant.GEO_KEY,
            ElementConstant.DENDRO_KEY
    );

    private final Yuanshen plugin;

    public LoreStatParser(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void applyLoreStats(Player player, PlayerStats stats) {
        FileConfiguration cfg = plugin.getAttributesConfig();
        if (cfg == null || !cfg.getBoolean("lore-stats.enabled", true)) {
            return;
        }

        for (ItemStack item : collectItems(player, cfg)) {
            applyItemLoreStats(player, item, stats, cfg);
        }
    }

    private List<ItemStack> collectItems(Player player, FileConfiguration cfg) {
        List<ItemStack> items = new ArrayList<>();
        PlayerInventory inv = player.getInventory();

        if (cfg.getBoolean("lore-stats.sources.main-hand", true)) {
            items.add(inv.getItemInMainHand());
        }
        if (cfg.getBoolean("lore-stats.sources.off-hand", false)) {
            items.add(inv.getItemInOffHand());
        }
        if (cfg.getBoolean("lore-stats.sources.armor", true)) {
            for (ItemStack armor : inv.getArmorContents()) {
                items.add(armor);
            }
        }

        return items;
    }

    private void applyItemLoreStats(Player player, ItemStack item, PlayerStats stats, FileConfiguration cfg) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }
        if (item.getItemMeta() == null || !item.getItemMeta().hasLore()) {
            return;
        }

        List<String> lore = item.getItemMeta().getLore();
        if (lore == null || lore.isEmpty()) {
            return;
        }

        ConfigurationSection attrSection = cfg.getConfigurationSection("lore-stats.attributes");
        if (attrSection == null) {
            return;
        }

        boolean debug = plugin.shouldLogLoreDebug();
        boolean canApplyAttackDamage = plugin.getCharacterResolver().resolveCharacter(player) != null;

        for (String rawLine : lore) {
            String line = sanitize(rawLine, cfg);
            if (line.isEmpty()) {
                continue;
            }

            for (String key : attrSection.getKeys(false)) {
                String basePath = "lore-stats.attributes." + key;
                List<String> aliases = cfg.getStringList(basePath + ".aliases");
                boolean percent = cfg.getBoolean(basePath + ".percent", false);

                for (String alias : aliases) {
                    String cleanAlias = sanitize(alias, cfg);
                    if (!matchesAlias(line, cleanAlias)) {
                        continue;
                    }

                    double value = extractNumber(line);
                    if (Double.isNaN(value)) {
                        continue;
                    }

                    if (percent) {
                        value = value / 100.0;
                    }

                    applyStat(key, value, stats, canApplyAttackDamage);

                    if (debug) {
                        plugin.getLogger().info("[Lore属性] 匹配到 " + key + " = " + value + " 来源: " + line);
                    }
                    break;
                }
            }
        }
    }

    private boolean matchesAlias(String line, String alias) {
        if (alias == null || alias.isEmpty() || !line.startsWith(alias)) {
            return false;
        }
        if (line.length() == alias.length()) {
            return true;
        }

        char next = line.charAt(alias.length());
        return next == ':'
                || next == '\uFF1A'
                || Character.isWhitespace(next)
                || next == '+'
                || next == '-'
                || Character.isDigit(next);
    }

    private double extractNumber(String line) {
        Matcher matcher = NUMBER_PATTERN.matcher(line);
        if (!matcher.find()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void applyStat(String key, double value, PlayerStats stats, boolean canApplyAttackDamage) {
        switch (key) {
            case "attack_damage" -> {
                if (canApplyAttackDamage) {
                    stats.setAttackDamage(stats.getAttackDamage() + value);
                }
            }
            case "defense" -> stats.setDefense(stats.getDefense() + value);
            case "health" -> stats.setHealth(stats.getHealth() + value);
            case "crit_rate" -> stats.setCritRate(stats.getCritRate() + value);
            case "crit_damage" -> stats.setCritDamage(stats.getCritDamage() + value);
            case "element_mastery" -> stats.setElementMastery(stats.getElementMastery() + value);
            case "energy_recharge" -> stats.setEnergyRecharge(stats.getEnergyRecharge() + value);
            case "healing_bonus" -> stats.setHealingBonus(stats.getHealingBonus() + value);
            case "fire_damage" -> stats.setElementBonus(ElementConstant.FIRE_KEY, stats.getElementBonus(ElementConstant.FIRE_KEY) + value);
            case "water_damage" -> stats.setElementBonus(ElementConstant.WATER_KEY, stats.getElementBonus(ElementConstant.WATER_KEY) + value);
            case "ice_damage" -> stats.setElementBonus(ElementConstant.ICE_KEY, stats.getElementBonus(ElementConstant.ICE_KEY) + value);
            case "electro_damage" -> stats.setElementBonus(ElementConstant.ELECTRO_KEY, stats.getElementBonus(ElementConstant.ELECTRO_KEY) + value);
            case "anemo_damage" -> stats.setElementBonus(ElementConstant.ANEMO_KEY, stats.getElementBonus(ElementConstant.ANEMO_KEY) + value);
            case "geo_damage" -> stats.setElementBonus(ElementConstant.GEO_KEY, stats.getElementBonus(ElementConstant.GEO_KEY) + value);
            case "dendro_damage" -> stats.setElementBonus(ElementConstant.DENDRO_KEY, stats.getElementBonus(ElementConstant.DENDRO_KEY) + value);
            case "all_element_damage" -> applyElementBonus(stats, value, ALL_ELEMENT_KEYS);
            case "physical_damage" -> stats.setElementBonus(PlayerStats.PHYSICAL_KEY, stats.getElementBonus(PlayerStats.PHYSICAL_KEY) + value);
            case "fire_resistance" -> stats.setResistance(ElementConstant.FIRE_KEY, stats.getResistance(ElementConstant.FIRE_KEY) + value);
            case "water_resistance" -> stats.setResistance(ElementConstant.WATER_KEY, stats.getResistance(ElementConstant.WATER_KEY) + value);
            case "ice_resistance" -> stats.setResistance(ElementConstant.ICE_KEY, stats.getResistance(ElementConstant.ICE_KEY) + value);
            case "electro_resistance" -> stats.setResistance(ElementConstant.ELECTRO_KEY, stats.getResistance(ElementConstant.ELECTRO_KEY) + value);
            case "anemo_resistance" -> stats.setResistance(ElementConstant.ANEMO_KEY, stats.getResistance(ElementConstant.ANEMO_KEY) + value);
            case "geo_resistance" -> stats.setResistance(ElementConstant.GEO_KEY, stats.getResistance(ElementConstant.GEO_KEY) + value);
            case "dendro_resistance" -> stats.setResistance(ElementConstant.DENDRO_KEY, stats.getResistance(ElementConstant.DENDRO_KEY) + value);
            case "all_element_resistance" -> applyResistance(stats, value, ALL_ELEMENT_KEYS);
            case "physical_resistance" -> stats.setResistance(PlayerStats.PHYSICAL_KEY, stats.getResistance(PlayerStats.PHYSICAL_KEY) + value);
            default -> {
            }
        }
    }

    private void applyElementBonus(PlayerStats stats, double value, List<String> elementKeys) {
        for (String elementKey : elementKeys) {
            stats.setElementBonus(elementKey, stats.getElementBonus(elementKey) + value);
        }
    }

    private void applyResistance(PlayerStats stats, double value, List<String> elementKeys) {
        for (String elementKey : elementKeys) {
            stats.setResistance(elementKey, stats.getResistance(elementKey) + value);
        }
    }

    private String sanitize(String text, FileConfiguration cfg) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (cfg.getBoolean("lore-stats.strip-color", true)) {
            out = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', out));
        }
        return out == null ? "" : out.trim();
    }
}
