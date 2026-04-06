package org.yuanshen.yuanshen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeaponConfigDirectoryLoader {

    private final Yuanshen plugin;
    private final ResourceConfigManager resourceConfigManager;

    public WeaponConfigDirectoryLoader(Yuanshen plugin, ResourceConfigManager resourceConfigManager) {
        this.plugin = plugin;
        this.resourceConfigManager = resourceConfigManager;
    }

    public List<WeaponDefinition> load() {
        File weaponDirectory = new File(plugin.getDataFolder(), "weapons");
        if (!weaponDirectory.exists()) {
            weaponDirectory.mkdirs();
        }
        ensureBundledDefaults(weaponDirectory);

        List<WeaponDefinition> definitions = new ArrayList<>();
        File[] files = weaponDirectory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            return definitions;
        }
        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));

        for (File targetFile : files) {
            FileConfiguration config = loadWeaponConfig(targetFile);
            if (!config.getBoolean("enabled", true)) {
                continue;
            }
            definitions.add(parseDefinition(resolveWeaponId(targetFile, config), config));
        }
        return definitions;
    }

    private WeaponDefinition parseDefinition(String id, FileConfiguration config) {
        CharacterWeaponType weaponType = CharacterWeaponType.fromId(
                config.getString("meta.weapon-type", "")
        ).orElse(CharacterWeaponType.SWORD);

        int defaultLevel = Math.max(1, config.getInt("meta.default-level", config.getInt("meta.level", 1)));
        int maxLevel = Math.max(defaultLevel, config.getInt("meta.max-level", config.getInt("meta.level", 90)));
        int defaultRefinement = Math.max(1, config.getInt("meta.default-refinement", config.getInt("meta.refinement", 1)));
        int maxRefinement = Math.max(defaultRefinement, config.getInt("meta.max-refinement", 5));
        String mainStatKey = normalizeStatKey(config.getString("main-stat.key", ""));
        String subStatKey = normalizeStatKey(config.getString("sub-stat.key", ""));
        Map<Integer, Map<String, Double>> levelStats = loadLevelStats(config, mainStatKey, subStatKey);
        Map<String, Double> bonusStats = loadBonusStats(config);
        if (mainStatKey.isBlank()) {
            mainStatKey = guessPrimaryStatKey(config, levelStats);
        }
        if (subStatKey.isBlank()) {
            subStatKey = guessSecondaryStatKey(config, levelStats, mainStatKey);
        }

        return new WeaponDefinition(
                id,
                config.getString("meta.name", id),
                weaponType,
                config.getString("meta.material", "STICK"),
                Math.max(1, config.getInt("meta.rarity", 1)),
                defaultLevel,
                maxLevel,
                defaultRefinement,
                maxRefinement,
                config.getDouble("stats.base-attack", 0.0),
                config.getDouble("stats.attack-percent", 0.0),
                config.getDouble("stats.crit-rate", 0.0),
                config.getDouble("stats.crit-damage", 0.0),
                config.getDouble("stats.energy-recharge", 0.0),
                config.getDouble("stats.elemental-mastery", 0.0),
                config.getDouble("stats.all-element-damage-bonus", 0.0),
                mainStatKey,
                subStatKey,
                levelStats,
                bonusStats,
                config.getString("passive.id", ""),
                config.getDouble("passive.hp-threshold", 0.0),
                config.getDouble("passive.proc-chance", 0.0),
                config.getLong("passive.proc-cooldown-ms", 0L),
                config.getDouble("passive.proc-radius", 0.0),
                Math.max(0, config.getInt("passive.proc-duration-ticks", 0)),
                Math.max(0, config.getInt("passive.proc-hits", 0)),
                Math.max(0, config.getInt("passive.max-stacks", 0)),
                config.getLong("passive.stack-duration-ms", 0L),
                config.getLong("passive.stack-interval-ms", 0L),
                loadRefinementLevels(config, maxRefinement),
                config.getString("item.material", ""),
                config.getString("item.display-name", ""),
                config.getInt("item.custom-model-data", -1),
                config.getStringList("item.lore")
        );
    }

    private FileConfiguration loadWeaponConfig(File targetFile) {
        String resourceName = "weapons/" + targetFile.getName();
        if (plugin.getResource(resourceName) != null) {
            return resourceConfigManager.loadAuxiliaryConfig(
                    resourceName,
                    targetFile,
                    List.of("enabled", "meta")
            );
        }
        return YamlConfiguration.loadConfiguration(targetFile);
    }

    private void ensureBundledDefaults(File weaponDirectory) {
        for (WeaponResourceEntry entry : defaultEntries()) {
            File targetFile = new File(weaponDirectory, entry.fileName());
            String resourceName = "weapons/" + entry.fileName();
            if (!targetFile.exists() && plugin.getResource(resourceName) != null) {
                plugin.saveResource(resourceName, false);
            }
        }
    }

    private String resolveWeaponId(File targetFile, FileConfiguration config) {
        String configuredId = config.getString("meta.id", "").trim();
        if (!configuredId.isBlank()) {
            return configuredId.toLowerCase(Locale.ROOT);
        }
        String fileName = targetFile.getName();
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName).toLowerCase(Locale.ROOT);
    }

    private Map<Integer, Map<String, Double>> loadLevelStats(FileConfiguration config,
                                                             String mainStatKey,
                                                             String subStatKey) {
        Map<Integer, Map<String, Double>> levelStats = new HashMap<>();
        ConfigurationSection levelStatsSection = config.getConfigurationSection("level-stats");
        if (levelStatsSection != null) {
            for (String levelKey : levelStatsSection.getKeys(false)) {
                int level = parsePositiveInt(levelKey);
                if (level <= 0) {
                    continue;
                }
                ConfigurationSection levelSection = levelStatsSection.getConfigurationSection(levelKey);
                if (levelSection == null) {
                    continue;
                }
                Map<String, Double> values = loadStatMap(levelSection);
                if (!values.isEmpty()) {
                    levelStats.put(level, values);
                }
            }
        }

        mergeStatSeries(levelStats, mainStatKey, config.getConfigurationSection("main-stat.values"));
        mergeStatSeries(levelStats, subStatKey, config.getConfigurationSection("sub-stat.values"));
        return levelStats;
    }

    private void mergeStatSeries(Map<Integer, Map<String, Double>> levelStats,
                                 String statKey,
                                 ConfigurationSection valuesSection) {
        if (statKey == null || statKey.isBlank() || valuesSection == null) {
            return;
        }
        for (String levelKey : valuesSection.getKeys(false)) {
            int level = parsePositiveInt(levelKey);
            if (level <= 0) {
                continue;
            }
            Object raw = valuesSection.get(levelKey);
            Double value = toDouble(raw);
            if (value == null) {
                continue;
            }
            levelStats.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(statKey, value);
        }
    }

    private Map<String, Double> loadBonusStats(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("bonus-stats");
        if (section == null) {
            return Map.of();
        }
        return loadStatMap(section);
    }

    private Map<String, Double> loadStatMap(ConfigurationSection section) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String rawKey : section.getKeys(false)) {
            String statKey = normalizeStatKey(rawKey);
            if (statKey.isBlank()) {
                continue;
            }
            Double value = toDouble(section.get(rawKey));
            if (value == null) {
                continue;
            }
            values.put(statKey, value);
        }
        return values;
    }

    private String guessPrimaryStatKey(FileConfiguration config, Map<Integer, Map<String, Double>> levelStats) {
        List<String> preferred = collectConfiguredStatKeys(config, levelStats);
        return preferred.isEmpty() ? "" : preferred.getFirst();
    }

    private String guessSecondaryStatKey(FileConfiguration config, Map<Integer, Map<String, Double>> levelStats,
                                         String primaryStatKey) {
        List<String> preferred = collectConfiguredStatKeys(config, levelStats);
        for (String statKey : preferred) {
            if (!statKey.equals(primaryStatKey)) {
                return statKey;
            }
        }
        return "";
    }

    private List<String> collectConfiguredStatKeys(FileConfiguration config, Map<Integer, Map<String, Double>> levelStats) {
        List<String> preferredOrder = List.of(
                "attack_damage",
                "attack_percent",
                "crit_rate",
                "crit_damage",
                "energy_recharge",
                "element_mastery",
                "health",
                "health_percent",
                "defense",
                "defense_percent",
                "healing_bonus",
                "physical_damage",
                "fire_damage",
                "water_damage",
                "ice_damage",
                "electro_damage",
                "anemo_damage",
                "geo_damage",
                "dendro_damage",
                "all_element_damage_bonus"
        );
        List<String> result = new ArrayList<>();
        for (String statKey : preferredOrder) {
            if (hasConfiguredStat(config, levelStats, statKey)) {
                result.add(statKey);
            }
        }
        return result;
    }

    private boolean hasConfiguredStat(FileConfiguration config, Map<Integer, Map<String, Double>> levelStats, String statKey) {
        for (Map<String, Double> levelValue : levelStats.values()) {
            if (levelValue.containsKey(statKey)) {
                return true;
            }
        }
        return switch (statKey) {
            case "attack_damage" -> config.getDouble("stats.base-attack", 0.0) != 0.0;
            case "attack_percent" -> config.getDouble("stats.attack-percent", 0.0) != 0.0;
            case "crit_rate" -> config.getDouble("stats.crit-rate", 0.0) != 0.0;
            case "crit_damage" -> config.getDouble("stats.crit-damage", 0.0) != 0.0;
            case "energy_recharge" -> config.getDouble("stats.energy-recharge", 0.0) != 0.0;
            case "element_mastery" -> config.getDouble("stats.elemental-mastery", 0.0) != 0.0;
            case "all_element_damage_bonus" -> config.getDouble("stats.all-element-damage-bonus", 0.0) != 0.0;
            default -> false;
        };
    }

    private int parsePositiveInt(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalizeStatKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "base_attack" -> "attack_damage";
            case "attack", "atk", "attack_damage" -> "attack_damage";
            case "attack_percent", "atk_percent" -> "attack_percent";
            case "hp", "health" -> "health";
            case "hp_percent", "health_percent" -> "health_percent";
            case "def", "defense" -> "defense";
            case "def_percent", "defense_percent" -> "defense_percent";
            case "crit_rate" -> "crit_rate";
            case "crit_damage" -> "crit_damage";
            case "energy_recharge" -> "energy_recharge";
            case "elemental_mastery", "element_mastery", "em" -> "element_mastery";
            case "healing_bonus" -> "healing_bonus";
            case "physical_damage" -> "physical_damage";
            case "fire_damage", "pyro_damage" -> "fire_damage";
            case "water_damage", "hydro_damage" -> "water_damage";
            case "ice_damage", "cryo_damage" -> "ice_damage";
            case "electro_damage" -> "electro_damage";
            case "anemo_damage" -> "anemo_damage";
            case "geo_damage" -> "geo_damage";
            case "dendro_damage" -> "dendro_damage";
            case "all_element_damage_bonus", "all_element_damage" -> "all_element_damage_bonus";
            case "fire_resistance", "pyro_resistance" -> "fire_resistance";
            case "water_resistance", "hydro_resistance" -> "water_resistance";
            case "ice_resistance", "cryo_resistance" -> "ice_resistance";
            case "electro_resistance" -> "electro_resistance";
            case "anemo_resistance" -> "anemo_resistance";
            case "geo_resistance" -> "geo_resistance";
            case "dendro_resistance" -> "dendro_resistance";
            case "all_element_resistance" -> "all_element_resistance";
            case "physical_resistance" -> "physical_resistance";
            default -> normalized;
        };
    }

    private Map<Integer, WeaponRefinementStats> loadRefinementLevels(FileConfiguration config, int maxRefinement) {
        Map<Integer, WeaponRefinementStats> values = new HashMap<>();
        ConfigurationSection refinements = config.getConfigurationSection("refinements");
        if (refinements != null) {
            for (String key : refinements.getKeys(false)) {
                int level;
                try {
                    level = Integer.parseInt(key);
                } catch (NumberFormatException ex) {
                    continue;
                }
                ConfigurationSection section = refinements.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                values.put(level, new WeaponRefinementStats(
                        section.getDouble("all-element-damage-bonus", 0.0),
                        section.getDouble("crit-rate-bonus", 0.0),
                        section.getDouble("normal-damage-bonus", 0.0),
                        section.getDouble("charged-damage-bonus", 0.0),
                        section.getDouble("proc-damage-multiplier", 0.0),
                        section.getDouble("stack-attack-bonus", 0.0),
                        section.getDouble("full-stack-damage-bonus", 0.0)
                ));
            }
        }

        if (!values.isEmpty()) {
            return values;
        }

        double allElementDamageBonus = config.getDouble("stats.all-element-damage-bonus", 0.0);
        double allElementDamageBonusPerRefinement = config.getDouble("stats.all-element-damage-bonus-per-refinement", 0.0);
        double critRateBonus = config.getDouble("passive.crit-rate-bonus", 0.0);
        double critRateBonusPerRefinement = config.getDouble("passive.crit-rate-bonus-per-refinement", 0.0);
        double normalDamageBonus = config.getDouble("passive.normal-damage-bonus", 0.0);
        double normalDamageBonusPerRefinement = config.getDouble("passive.normal-damage-bonus-per-refinement", 0.0);
        double chargedDamageBonus = config.getDouble("passive.charged-damage-bonus", 0.0);
        double chargedDamageBonusPerRefinement = config.getDouble("passive.charged-damage-bonus-per-refinement", 0.0);
        double procDamageMultiplier = config.getDouble("passive.proc-damage-multiplier", 0.0);
        double procDamageMultiplierPerRefinement = config.getDouble("passive.proc-damage-multiplier-per-refinement", 0.0);
        double stackAttackBonus = config.getDouble("passive.stack-attack-bonus", 0.0);
        double stackAttackBonusPerRefinement = config.getDouble("passive.stack-attack-bonus-per-refinement", 0.0);
        double fullStackDamageBonus = config.getDouble("passive.full-stack-damage-bonus", 0.0);
        double fullStackDamageBonusPerRefinement = config.getDouble("passive.full-stack-damage-bonus-per-refinement", 0.0);

        for (int refinement = 1; refinement <= maxRefinement; refinement++) {
            int offset = refinement - 1;
            values.put(refinement, new WeaponRefinementStats(
                    allElementDamageBonus + (allElementDamageBonusPerRefinement * offset),
                    critRateBonus + (critRateBonusPerRefinement * offset),
                    normalDamageBonus + (normalDamageBonusPerRefinement * offset),
                    chargedDamageBonus + (chargedDamageBonusPerRefinement * offset),
                    procDamageMultiplier + (procDamageMultiplierPerRefinement * offset),
                    stackAttackBonus + (stackAttackBonusPerRefinement * offset),
                    fullStackDamageBonus + (fullStackDamageBonusPerRefinement * offset)
            ));
        }
        return values;
    }

    private List<WeaponResourceEntry> defaultEntries() {
        return List.of(
                new WeaponResourceEntry("dawn-harbinger", "dawn-harbinger.yml"),
                new WeaponResourceEntry("prototype-archaic", "prototype-archaic.yml"),
                new WeaponResourceEntry("rust", "rust.yml"),
                new WeaponResourceEntry("skyward-atlas", "skyward-atlas.yml"),
                new WeaponResourceEntry("primordial-jade-winged-spear", "primordial-jade-winged-spear.yml")
        );
    }

    private record WeaponResourceEntry(String id, String fileName) {
    }
}
