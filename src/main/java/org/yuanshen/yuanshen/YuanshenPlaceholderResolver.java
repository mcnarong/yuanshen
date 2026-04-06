package org.yuanshen.yuanshen;

import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YuanshenPlaceholderResolver {

    private static final Pattern YUANSHEN_PLACEHOLDER = Pattern.compile("%yuanshen_([a-z0-9_]+)%");

    private final Yuanshen plugin;
    private final DecimalFormat numberFormat;

    public YuanshenPlaceholderResolver(Yuanshen plugin) {
        this.plugin = plugin;
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        this.numberFormat = new DecimalFormat("0.##", symbols);
    }

    public String applyPlaceholders(Player player, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        Matcher matcher = YUANSHEN_PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = resolve(player, matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public String resolve(Player player, String identifier) {
        if (player == null || identifier == null || identifier.isBlank()) {
            return "";
        }

        String key = identifier.trim().toLowerCase(Locale.ROOT);
        PlayerStats stats = plugin.getPlayerStats(player);
        CharacterType character = plugin.getCharacterResolver().resolveCharacter(player);
        PlayerSkillData skillData = PlayerSkillData.get(player.getUniqueId());
        CharacterSkillConfig characterConfig = character == null ? null : plugin.getCharacterConfig(character);

        return switch (key) {
            case "damage", "attack", "attack_damage" -> formatNumber(stats == null ? 0.0 : stats.getAttackDamage());
            case "crit_rate" -> formatPercent(stats == null ? 0.0 : stats.getCritRate());
            case "crit_rate_raw" -> formatNumber(stats == null ? 0.0 : stats.getCritRate());
            case "crit_damage" -> formatPercent(stats == null ? 0.0 : stats.getCritDamage());
            case "crit_damage_raw" -> formatNumber(stats == null ? 0.0 : stats.getCritDamage());
            case "element_mastery", "em" -> formatNumber(stats == null ? 0.0 : stats.getElementMastery());
            case "energy_recharge" -> formatPercent(stats == null ? 0.0 : stats.getEnergyRecharge());
            case "energy_recharge_raw" -> formatNumber(stats == null ? 0.0 : stats.getEnergyRecharge());
            case "healing_bonus" -> formatPercent(stats == null ? 0.0 : stats.getHealingBonus());
            case "healing_bonus_raw" -> formatNumber(stats == null ? 0.0 : stats.getHealingBonus());
            case "defense" -> formatNumber(stats == null ? 0.0 : stats.getDefense());
            case "health", "max_health" -> formatNumber(resolveMaxHealth(player, stats));
            case "current_health" -> formatNumber(player.getHealth());
            case "current_health_percent" -> formatPercent(resolveCurrentHealthPercent(player, stats));
            case "character" -> character == null ? "无" : character.getDisplayName();
            case "character_id" -> character == null ? "" : character.getId();
            case "character_level" -> String.valueOf(plugin.getCharacterSlotManager().getSelectedCharacterLevel(player));
            case "character_constellation", "character_mingzuo" -> String.valueOf(plugin.getCharacterSlotManager().getSelectedCharacterConstellation(player));
            case "character_slot", "slot" -> String.valueOf(plugin.getCharacterSlotManager().getSelectedSlot(player));
            case "element" -> formatElementName(resolveElementKey(player, character));
            case "element_key" -> blankToEmpty(resolveElementKey(player, character));
            case "weapon" -> plugin.getWeaponManager().getSelectedWeaponName(player);
            case "weapon_id" -> plugin.getWeaponManager().getSelectedWeaponId(player);
            case "weapon_type" -> plugin.getWeaponManager().getSelectedWeaponTypeName(player);
            case "weapon_level" -> String.valueOf(plugin.getWeaponManager().getSelectedWeaponLevel(player));
            case "weapon_refinement", "weapon_refine" -> String.valueOf(plugin.getWeaponManager().getSelectedWeaponRefinement(player));
            case "current_energy", "energy", "skill_energy" -> formatEnergy(skillData, character);
            case "max_energy", "skill_energy_max" -> formatMaxEnergy(characterConfig);
            case "e_cooldown", "skill_e_cooldown" -> formatCooldown(skillData, character, characterConfig, "e.cooldown-ms");
            case "q_cooldown", "skill_q_cooldown" -> formatCooldown(skillData, character, characterConfig, "q.cooldown-ms");
            case "e_cooldown_seconds", "skill_e_cooldown_seconds" -> formatCooldownSeconds(skillData, character, characterConfig, "e.cooldown-ms");
            case "q_cooldown_seconds", "skill_q_cooldown_seconds" -> formatCooldownSeconds(skillData, character, characterConfig, "q.cooldown-ms");
            case "e_cooldown_ms", "skill_e_cooldown_ms" -> formatCooldownMillis(skillData, character, characterConfig, "e.cooldown-ms");
            case "q_cooldown_ms", "skill_q_cooldown_ms" -> formatCooldownMillis(skillData, character, characterConfig, "q.cooldown-ms");
            case "particle_status" -> formatParticleStatus(player);
            case "particle_source" -> formatParticleSource(player);
            case "particle_energy" -> formatParticleEnergy(player);
            case "particle_count", "particle_particles" -> formatParticleCount(player);
            case "particle_element" -> formatParticleElement(player);
            case "particle_seconds" -> formatParticleSeconds(player);
            case "damage_display" -> String.valueOf(plugin.isDamageDisplayEnabled(player));
            case "sidebar_enabled" -> String.valueOf(plugin.isSidebarEnabled(player));
            case "fire_damage", "pyro_damage" -> formatElementBonus(stats, ElementConstant.FIRE_KEY);
            case "water_damage", "hydro_damage" -> formatElementBonus(stats, ElementConstant.WATER_KEY);
            case "ice_damage", "cryo_damage" -> formatElementBonus(stats, ElementConstant.ICE_KEY);
            case "electro_damage" -> formatElementBonus(stats, ElementConstant.ELECTRO_KEY);
            case "anemo_damage" -> formatElementBonus(stats, ElementConstant.ANEMO_KEY);
            case "geo_damage" -> formatElementBonus(stats, ElementConstant.GEO_KEY);
            case "dendro_damage" -> formatElementBonus(stats, ElementConstant.DENDRO_KEY);
            case "physical_damage" -> formatElementBonus(stats, PlayerStats.PHYSICAL_KEY);
            case "fire_resistance", "pyro_resistance" -> formatResistance(stats, ElementConstant.FIRE_KEY);
            case "water_resistance", "hydro_resistance" -> formatResistance(stats, ElementConstant.WATER_KEY);
            case "ice_resistance", "cryo_resistance" -> formatResistance(stats, ElementConstant.ICE_KEY);
            case "electro_resistance" -> formatResistance(stats, ElementConstant.ELECTRO_KEY);
            case "anemo_resistance" -> formatResistance(stats, ElementConstant.ANEMO_KEY);
            case "geo_resistance" -> formatResistance(stats, ElementConstant.GEO_KEY);
            case "dendro_resistance" -> formatResistance(stats, ElementConstant.DENDRO_KEY);
            case "physical_resistance" -> formatResistance(stats, PlayerStats.PHYSICAL_KEY);
            default -> "";
        };
    }

    private String formatElementBonus(PlayerStats stats, String elementKey) {
        return formatPercent(stats == null ? 0.0 : stats.getElementBonus(elementKey));
    }

    private String formatEnergy(PlayerSkillData skillData, CharacterType character) {
        if (skillData == null || character == null) {
            return "0";
        }
        return String.valueOf(skillData.getEnergy(character));
    }

    private String formatMaxEnergy(CharacterSkillConfig config) {
        if (config == null) {
            return "0";
        }
        return String.valueOf(Math.max(0, config.getInt("q.energy-cost", 0)));
    }

    private String formatCooldown(PlayerSkillData skillData, CharacterType character, CharacterSkillConfig config, String relativePath) {
        long remainMs = resolveCooldownMillis(skillData, character, config, relativePath);
        if (remainMs <= 0L) {
            return "就绪";
        }
        return formatCooldownValue(remainMs);
    }

    private String formatCooldownSeconds(PlayerSkillData skillData, CharacterType character, CharacterSkillConfig config, String relativePath) {
        long remainMs = resolveCooldownMillis(skillData, character, config, relativePath);
        return formatNumber(remainMs / 1000.0);
    }

    private String formatCooldownMillis(PlayerSkillData skillData, CharacterType character, CharacterSkillConfig config, String relativePath) {
        return String.valueOf(resolveCooldownMillis(skillData, character, config, relativePath));
    }

    private long resolveCooldownMillis(PlayerSkillData skillData, CharacterType character, CharacterSkillConfig config, String relativePath) {
        if (skillData == null || character == null || config == null) {
            return 0L;
        }

        long cooldownMs = Math.max(0L, config.getLong(relativePath, 0L));
        if (cooldownMs <= 0L) {
            return 0L;
        }

        return switch (relativePath) {
            case "e.cooldown-ms" -> skillData.getRemainingECooldown(character, cooldownMs);
            case "q.cooldown-ms" -> skillData.getRemainingQCooldown(character, cooldownMs);
            default -> 0L;
        };
    }

    private String formatCooldownValue(long remainMs) {
        return formatNumber(remainMs / 1000.0) + "s";
    }

    private String formatResistance(PlayerStats stats, String elementKey) {
        return formatPercent(stats == null ? 0.0 : stats.getResistance(elementKey));
    }

    private String formatParticleStatus(Player player) {
        EnergyManager.RecentParticleGain gain = resolveRecentParticleGain(player);
        if (gain == null) {
            return "等待产球";
        }
        return gain.getSourceLabel() + " x" + gain.getParticles() + " +" + formatNumber(gain.getActiveEnergyGain());
    }

    private String formatParticleSource(Player player) {
        EnergyManager.RecentParticleGain gain = resolveRecentParticleGain(player);
        return gain == null ? "无" : gain.getSourceLabel();
    }

    private String formatParticleEnergy(Player player) {
        EnergyManager.RecentParticleGain gain = resolveRecentParticleGain(player);
        return gain == null ? "0" : formatNumber(gain.getActiveEnergyGain());
    }

    private String formatParticleCount(Player player) {
        EnergyManager.RecentParticleGain gain = resolveRecentParticleGain(player);
        return gain == null ? "0" : String.valueOf(gain.getParticles());
    }

    private String formatParticleElement(Player player) {
        EnergyManager.RecentParticleGain gain = resolveRecentParticleGain(player);
        return gain == null ? "无" : gain.getElementLabel();
    }

    private String formatParticleSeconds(Player player) {
        EnergyManager.RecentParticleGain gain = resolveRecentParticleGain(player);
        if (gain == null) {
            return "0";
        }
        long remainMs = Math.max(0L, gain.getExpireAtMillis() - System.currentTimeMillis());
        return formatNumber(remainMs / 1000.0);
    }

    private EnergyManager.RecentParticleGain resolveRecentParticleGain(Player player) {
        if (plugin.getEnergyManager() == null) {
            return null;
        }
        return plugin.getEnergyManager().getRecentParticleGain(player);
    }

    private double resolveMaxHealth(Player player, PlayerStats stats) {
        return plugin.getActualMaxHealth(player);
    }

    private double resolveCurrentHealthPercent(Player player, PlayerStats stats) {
        double maxHealth = resolveMaxHealth(player, stats);
        if (maxHealth <= 0.0) {
            return 0.0;
        }
        return player.getHealth() / maxHealth;
    }

    private String resolveElementKey(Player player, CharacterType character) {
        String activeElement = plugin.getActiveElement(player);
        if (activeElement != null && !activeElement.isBlank()) {
            return activeElement;
        }
        return character == null ? null : character.getElementKey();
    }

    private String formatElementName(String elementKey) {
        if (elementKey == null || elementKey.isBlank()) {
            return "无";
        }
        return switch (elementKey) {
            case ElementConstant.FIRE_KEY -> "火";
            case ElementConstant.WATER_KEY -> "水";
            case ElementConstant.ICE_KEY -> "冰";
            case ElementConstant.ELECTRO_KEY -> "雷";
            case ElementConstant.ANEMO_KEY -> "风";
            case ElementConstant.GEO_KEY -> "岩";
            case ElementConstant.DENDRO_KEY -> "草";
            default -> elementKey;
        };
    }

    private String formatPercent(double value) {
        return formatNumber(value * 100.0);
    }

    private String formatNumber(double value) {
        synchronized (numberFormat) {
            return numberFormat.format(value);
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
