package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CharacterManager {

    private static final String CHARACTER_ID_MARKER_PREFIX = "ys_character_id:";
    private static final String CHARACTER_LEVEL_MARKER_PREFIX = "ys_character_level:";
    private static final String CHARACTER_CONSTELLATION_MARKER_PREFIX = "ys_character_constellation:";

    private final Yuanshen plugin;
    private final Map<String, CharacterDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, CharacterDefinition> aliases = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> normalComboSteps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> normalComboExpireAt = new ConcurrentHashMap<>();

    public CharacterManager(Yuanshen plugin) {
        this.plugin = plugin;
        reloadDefinitions();
    }

    public void reloadDefinitions() {
        definitions.clear();
        aliases.clear();
        for (CharacterType type : CharacterType.values()) {
            CharacterDefinition definition = buildDefinition(type);
            if (definition == null) {
                continue;
            }
            definitions.put(definition.id(), definition);
            indexAlias(definition.id(), definition);
            indexAlias(definition.displayName(), definition);
            for (String alias : definition.aliases()) {
                indexAlias(alias, definition);
            }
        }
    }

    public CharacterDefinition get(String characterId) {
        if (characterId == null || characterId.isBlank()) {
            return null;
        }
        return definitions.get(characterId.trim().toLowerCase(Locale.ROOT));
    }

    public CharacterDefinition findByInput(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        CharacterDefinition byId = get(input);
        if (byId != null) {
            return byId;
        }
        return aliases.get(normalizeKey(input));
    }

    public List<String> getCharacterIds() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(CharacterDefinition::id))
                .map(CharacterDefinition::id)
                .toList();
    }

    public List<String> getCharacterCommandOptions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(CharacterDefinition::displayName))
                .map(CharacterDefinition::displayName)
                .toList();
    }

    public List<String> getCharacterListEntries() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(CharacterDefinition::displayName))
                .map(definition -> definition.displayName() + "(" + definition.id() + ")")
                .toList();
    }

    public ItemStack createCharacterItem(String characterInput) {
        CharacterDefinition definition = findByInput(characterInput);
        if (definition == null) {
            return null;
        }
        return createCharacterItem(definition.id(), definition.defaultLevel(), definition.defaultConstellation());
    }

    public ItemStack createCharacterItem(String characterInput, int level, int constellation) {
        CharacterDefinition definition = findByInput(characterInput);
        if (definition == null) {
            return null;
        }

        CharacterInstance instance = new CharacterInstance(
                definition,
                definition.clampLevel(level),
                definition.clampConstellation(constellation)
        );

        String materialName = definition.itemMaterial().isBlank() ? definition.material() : definition.itemMaterial();
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.NETHER_STAR;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        Map<String, String> placeholders = buildItemPlaceholders(instance);
        String displayName = definition.itemDisplayName().isBlank()
                ? rarityColor(definition.rarity()) + definition.displayName()
                : definition.itemDisplayName();
        meta.setDisplayName(color(applyPlaceholders(displayName, placeholders)));

        List<String> lore = new ArrayList<>();
        lore.add(color("&8原神角色"));
        lore.add(color("&7元素: &f" + elementName(definition.elementKey())));
        lore.add(color("&7武器: &f" + definition.weaponType().getDisplayName()));
        lore.add(color("&7等级: &f" + instance.level()));
        lore.add(color("&7命座: &f" + instance.constellation()));
        lore.add(color("&7基础攻击: &f" + trim(scaleByLevel(definition.baseAttack(), instance.level(), definition.maxLevel()))));
        lore.add(color("&7基础生命: &f" + trim(scaleByLevel(definition.baseHealth(), instance.level(), definition.maxLevel()))));
        lore.add(color("&7基础防御: &f" + trim(scaleByLevel(definition.baseDefense(), instance.level(), definition.maxLevel()))));
        addStatLine(lore, "&7攻击加成: &f+", percent(scaleByLevel(definition.attackPercent(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7生命加成: &f+", percent(scaleByLevel(definition.healthPercent(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7防御加成: &f+", percent(scaleByLevel(definition.defensePercent(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7暴击率: &f+", percent(scaleByLevel(definition.critRate(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7暴击伤害: &f+", percent(scaleByLevel(definition.critDamage(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7元素充能效率: &f+", percent(scaleByLevel(definition.energyRecharge(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7元素精通: &f+", trim(scaleByLevel(definition.elementalMastery(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7治疗加成: &f+", percent(scaleByLevel(definition.healingBonus(), instance.level(), definition.maxLevel())));
        addStatLine(lore, "&7元素伤害加成: &f+", percent(scaleByLevel(definition.elementBonus(), instance.level(), definition.maxLevel())));

        List<Double> segments = getNormalAttackSegments(definition.characterType());
        if (!segments.isEmpty()) {
            lore.add(color("&6普通攻击段数倍率"));
            for (int i = 0; i < segments.size(); i++) {
                lore.add(color("&7第" + (i + 1) + "段: &f" + percent(segments.get(i))));
            }
        }

        lore.removeIf(line -> {
            String clean = sanitize(line);
            return clean.startsWith(CHARACTER_ID_MARKER_PREFIX)
                    || clean.startsWith(CHARACTER_LEVEL_MARKER_PREFIX)
                    || clean.startsWith(CHARACTER_CONSTELLATION_MARKER_PREFIX);
        });
        if (!definition.itemLore().isEmpty()) {
            lore = definition.itemLore().stream()
                    .map(line -> color(applyPlaceholders(line, placeholders)))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        meta.setLore(lore);
        if (definition.itemCustomModelData() >= 0) {
            meta.setCustomModelData(definition.itemCustomModelData());
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        plugin.getItemIdentity().applyCharacter(meta, instance);
        item.setItemMeta(meta);
        return item;
    }

    public CharacterInstance resolveCharacterInstance(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        YuanshenItemIdentity.CharacterData pdcData = plugin.getItemIdentity().readCharacter(meta);
        if (pdcData != null) {
            CharacterDefinition definition = get(pdcData.id());
            if (definition == null) {
                return null;
            }
            return new CharacterInstance(
                    definition,
                    definition.clampLevel(pdcData.level()),
                    definition.clampConstellation(pdcData.constellation())
            );
        }

        String characterId = null;
        Integer level = null;
        Integer constellation = null;
        if (meta.hasLore() && meta.getLore() != null) {
            for (String rawLine : meta.getLore()) {
                String clean = sanitize(rawLine);
                if (clean.startsWith(CHARACTER_ID_MARKER_PREFIX)) {
                    characterId = clean.substring(CHARACTER_ID_MARKER_PREFIX.length());
                    continue;
                }
                if (clean.startsWith(CHARACTER_LEVEL_MARKER_PREFIX)) {
                    level = parseInt(clean.substring(CHARACTER_LEVEL_MARKER_PREFIX.length()));
                    continue;
                }
                if (clean.startsWith(CHARACTER_CONSTELLATION_MARKER_PREFIX)) {
                    constellation = parseInt(clean.substring(CHARACTER_CONSTELLATION_MARKER_PREFIX.length()));
                }
            }
        }

        CharacterDefinition definition = characterId == null ? null : get(characterId);
        if (definition == null) {
            return null;
        }

        int resolvedLevel = definition.clampLevel(level == null ? definition.defaultLevel() : level);
        int resolvedConstellation = definition.clampConstellation(
                constellation == null ? definition.defaultConstellation() : constellation
        );
        return new CharacterInstance(definition, resolvedLevel, resolvedConstellation);
    }

    public CharacterDefinition resolveCharacter(ItemStack item) {
        CharacterInstance instance = resolveCharacterInstance(item);
        return instance == null ? null : instance.definition();
    }

    public CharacterInstance getSelectedCharacterInstance(Player player) {
        if (player == null) {
            return null;
        }
        return resolveCharacterInstance(plugin.getCharacterSlotManager().getSlotItem(player, plugin.getCharacterSlotManager().getSelectedSlot(player)));
    }

    public CharacterDefinition getSelectedCharacterDefinition(Player player) {
        CharacterInstance instance = getSelectedCharacterInstance(player);
        return instance == null ? null : instance.definition();
    }

    public int getSelectedCharacterLevel(Player player) {
        CharacterInstance instance = getSelectedCharacterInstance(player);
        return instance == null ? 0 : instance.level();
    }

    public int getSelectedCharacterConstellation(Player player) {
        CharacterInstance instance = getSelectedCharacterInstance(player);
        return instance == null ? 0 : instance.constellation();
    }

    public void applyStats(Player player, PlayerStats stats) {
        CharacterInstance instance = getSelectedCharacterInstance(player);
        if (instance == null || stats == null) {
            return;
        }

        CharacterDefinition definition = instance.definition();
        int level = instance.level();
        int constellation = instance.constellation();

        double attack = scaleByLevel(definition.baseAttack(), level, definition.maxLevel());
        double health = scaleByLevel(definition.baseHealth(), level, definition.maxLevel());
        double defense = scaleByLevel(definition.baseDefense(), level, definition.maxLevel());

        double attackPercent = scaleByLevel(definition.attackPercent(), level, definition.maxLevel())
                + (definition.perConstellationAttackPercent() * constellation);
        double healthPercent = scaleByLevel(definition.healthPercent(), level, definition.maxLevel())
                + (definition.perConstellationHealthPercent() * constellation);
        double defensePercent = scaleByLevel(definition.defensePercent(), level, definition.maxLevel())
                + (definition.perConstellationDefensePercent() * constellation);

        stats.setAttackDamage(stats.getAttackDamage() + attack);
        if (attackPercent != 0.0) {
            stats.setAttackDamage(stats.getAttackDamage() * (1.0 + attackPercent));
        }

        stats.setHealth(stats.getHealth() + health);
        if (healthPercent != 0.0) {
            stats.setHealth(stats.getHealth() * (1.0 + healthPercent));
        }

        stats.setDefense(stats.getDefense() + defense);
        if (defensePercent != 0.0) {
            stats.setDefense(stats.getDefense() * (1.0 + defensePercent));
        }

        stats.setCritRate(stats.getCritRate()
                + scaleByLevel(definition.critRate(), level, definition.maxLevel())
                + (definition.perConstellationCritRate() * constellation));
        stats.setCritDamage(stats.getCritDamage()
                + scaleByLevel(definition.critDamage(), level, definition.maxLevel())
                + (definition.perConstellationCritDamage() * constellation));
        stats.setEnergyRecharge(stats.getEnergyRecharge()
                + scaleByLevel(definition.energyRecharge(), level, definition.maxLevel()));
        stats.setElementMastery(stats.getElementMastery()
                + scaleByLevel(definition.elementalMastery(), level, definition.maxLevel()));
        stats.setHealingBonus(stats.getHealingBonus()
                + scaleByLevel(definition.healingBonus(), level, definition.maxLevel()));

        double elementBonus = scaleByLevel(definition.elementBonus(), level, definition.maxLevel())
                + (definition.perConstellationElementBonus() * constellation);
        if (elementBonus != 0.0 && definition.elementKey() != null && !definition.elementKey().isBlank()) {
            stats.setElementBonus(definition.elementKey(),
                    stats.getElementBonus(definition.elementKey()) + elementBonus);
        }
    }

    public List<Double> getNormalAttackSegments(CharacterType type) {
        if (type == null) {
            return Collections.emptyList();
        }
        List<?> raw = plugin.getSkillsConfig().getList("skills." + type.getId() + ".combat.normal.segments");
        if (raw == null || raw.isEmpty()) {
            return fallbackNormalAttackSegments(type);
        }

        List<Double> values = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Number number) {
                values.add(number.doubleValue());
                continue;
            }
            if (value instanceof String text) {
                try {
                    values.add(Double.parseDouble(text));
                } catch (NumberFormatException ignored) {
                    // Ignore invalid values.
                }
            }
        }
        return values;
    }

    private List<Double> fallbackNormalAttackSegments(CharacterType type) {
        return switch (type) {
            case HUTAO -> List.of(0.47, 0.49, 0.61, 0.67, 0.36, 0.79);
            case XIANGLING -> List.of(0.42, 0.42, 0.26, 0.14, 0.71);
            case DILUC -> List.of(0.89, 0.88, 1.09, 1.34);
            case KEQING -> List.of(0.41, 0.41, 0.54, 0.31, 0.67);
            case NINGGUANG -> List.of(1.00);
            case TRAVELER_ANEMO -> List.of(0.45, 0.44, 0.55, 0.60, 0.72);
            case YELAN -> List.of(0.41, 0.39, 0.52, 0.68);
        };
    }

    public double getAndAdvanceNormalAttackMultiplier(Player player, CharacterType type, double fallbackMultiplier) {
        if (player == null || type == null) {
            return fallbackMultiplier;
        }

        CharacterDefinition definition = get(type.getId());
        List<Double> segments = getNormalAttackSegments(type);
        if (segments.isEmpty()) {
            resetNormalAttackCombo(player);
            return fallbackMultiplier;
        }

        long now = System.currentTimeMillis();
        long resetWindow = definition == null ? 1500L : Math.max(300L, definition.normalComboResetMs());
        long expireAt = normalComboExpireAt.getOrDefault(player.getUniqueId(), 0L);
        int nextIndex = expireAt > now ? normalComboSteps.getOrDefault(player.getUniqueId(), 0) : 0;
        if (nextIndex >= segments.size()) {
            nextIndex = 0;
        }

        normalComboSteps.put(player.getUniqueId(), nextIndex + 1 >= segments.size() ? 0 : nextIndex + 1);
        normalComboExpireAt.put(player.getUniqueId(), now + resetWindow);
        return segments.get(nextIndex);
    }

    public void resetNormalAttackCombo(Player player) {
        if (player == null) {
            return;
        }
        normalComboSteps.remove(player.getUniqueId());
        normalComboExpireAt.remove(player.getUniqueId());
    }

    public void clearRuntimeState(Player player) {
        resetNormalAttackCombo(player);
    }

    public void clearAllRuntimeState() {
        normalComboSteps.clear();
        normalComboExpireAt.clear();
    }

    private CharacterDefinition buildDefinition(CharacterType type) {
        if (type == null) {
            return null;
        }

        CharacterSkillConfig config = plugin.getCharacterConfig(type);
        if (!config.getBoolean("enabled", true)) {
            return null;
        }

        String displayName = config.getString("meta.display-name", type.getDisplayName());
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(type.getId());
        aliases.add(displayName);
        addAlias(aliases, config.getString("lore-keyword", ""));
        aliases.addAll(plugin.getSkillsConfig().getStringList("skills." + type.getId() + ".meta.aliases"));

        ConfigurationSection tagsSection = plugin.getSkillsConfig().getConfigurationSection("skills." + type.getId() + ".meta");
        if (tagsSection != null) {
            List<String> tags = tagsSection.getStringList("tags");
            aliases.addAll(tags);
        }

        FallbackCharacterPreset fallback = fallbackPreset(type);

        return new CharacterDefinition(
                type.getId(),
                displayName,
                type,
                type.getElementKey(),
                type.getWeaponType(),
                config.getString("meta.material", fallback.material()),
                config.getInt("meta.rarity", fallback.rarity()),
                config.getInt("meta.default-level", fallback.defaultLevel()),
                config.getInt("meta.max-level", fallback.maxLevel()),
                config.getInt("meta.default-constellation", 0),
                config.getInt("meta.max-constellation", 6),
                config.getDouble("stats.base-attack", fallback.baseAttack()),
                config.getDouble("stats.base-health", fallback.baseHealth()),
                config.getDouble("stats.base-defense", fallback.baseDefense()),
                config.getDouble("stats.attack-percent", 0.0),
                config.getDouble("stats.health-percent", 0.0),
                config.getDouble("stats.defense-percent", 0.0),
                config.getDouble("stats.crit-rate", fallback.critRate()),
                config.getDouble("stats.crit-damage", fallback.critDamage()),
                config.getDouble("stats.energy-recharge", fallback.energyRecharge()),
                config.getDouble("stats.elemental-mastery", fallback.elementalMastery()),
                config.getDouble("stats.healing-bonus", 0.0),
                config.getDouble("stats.element-bonus", fallback.elementBonus()),
                config.getDouble("progression.per-constellation.attack-percent", 0.0),
                config.getDouble("progression.per-constellation.health-percent", 0.0),
                config.getDouble("progression.per-constellation.defense-percent", 0.0),
                config.getDouble("progression.per-constellation.crit-rate", 0.0),
                config.getDouble("progression.per-constellation.crit-damage", 0.0),
                config.getDouble("progression.per-constellation.element-bonus", 0.0),
                config.getLong("combat.normal.combo-reset-ms", 1500L),
                config.getString("item.material", ""),
                config.getString("item.display-name", ""),
                config.getInt("item.custom-model-data", -1),
                plugin.getSkillsConfig().getStringList(config.path("item.lore")),
                aliases
        );
    }

    private FallbackCharacterPreset fallbackPreset(CharacterType type) {
        return switch (type) {
            case HUTAO -> new FallbackCharacterPreset("NETHER_STAR", 5, 90, 90, 106, 15552, 876, 0.0, 0.384, 0.0, 0.0, 0.0);
            case XIANGLING -> new FallbackCharacterPreset("NETHER_STAR", 4, 90, 90, 225, 10875, 669, 0.0, 0.0, 0.0, 96.0, 0.0);
            case DILUC -> new FallbackCharacterPreset("NETHER_STAR", 5, 90, 90, 335, 12981, 784, 0.192, 0.0, 0.0, 0.0, 0.0);
            case KEQING -> new FallbackCharacterPreset("NETHER_STAR", 5, 90, 90, 323, 13103, 799, 0.0, 0.384, 0.0, 0.0, 0.0);
            case NINGGUANG -> new FallbackCharacterPreset("NETHER_STAR", 4, 90, 90, 212, 9787, 573, 0.0, 0.0, 0.0, 0.0, 0.24);
            case TRAVELER_ANEMO -> new FallbackCharacterPreset("NETHER_STAR", 5, 90, 90, 212, 10875, 683, 0.0, 0.0, 0.0, 0.0, 0.24);
            case YELAN -> new FallbackCharacterPreset("NETHER_STAR", 5, 90, 90, 244, 14450, 548, 0.192, 0.0, 0.0, 0.0, 0.0);
        };
    }

    private void indexAlias(String alias, CharacterDefinition definition) {
        if (alias == null || alias.isBlank() || definition == null) {
            return;
        }
        aliases.put(normalizeKey(alias), definition);
    }

    private void addAlias(Collection<String> aliases, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        aliases.add(value);
    }

    private void addStatLine(List<String> lore, String prefix, String value) {
        if (value == null || value.isBlank() || "0".equals(value) || "0%".equals(value)) {
            return;
        }
        lore.add(color(prefix + value));
    }

    private double scaleByLevel(double maxValue, int level, int maxLevel) {
        if (maxValue == 0.0) {
            return 0.0;
        }
        if (maxLevel <= 1) {
            return maxValue;
        }
        double progress = Math.max(1, Math.min(level, maxLevel)) / (double) maxLevel;
        return maxValue * progress;
    }

    private String normalizeKey(String text) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text == null ? "" : text))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String sanitize(String text) {
        return normalizeKey(text);
    }

    private int parseInt(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Map<String, String> buildItemPlaceholders(CharacterInstance instance) {
        CharacterDefinition definition = instance.definition();
        int level = instance.level();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("character_id", definition.id());
        placeholders.put("character_name", definition.displayName());
        placeholders.put("level", String.valueOf(level));
        placeholders.put("constellation", String.valueOf(instance.constellation()));
        placeholders.put("rarity", String.valueOf(definition.rarity()));
        placeholders.put("element", elementName(definition.elementKey()));
        placeholders.put("weapon_type", definition.weaponType().getDisplayName());
        placeholders.put("base_attack", trim(scaleByLevel(definition.baseAttack(), level, definition.maxLevel())));
        placeholders.put("base_health", trim(scaleByLevel(definition.baseHealth(), level, definition.maxLevel())));
        placeholders.put("base_defense", trim(scaleByLevel(definition.baseDefense(), level, definition.maxLevel())));
        placeholders.put("attack_percent", percent(scaleByLevel(definition.attackPercent(), level, definition.maxLevel())));
        placeholders.put("health_percent", percent(scaleByLevel(definition.healthPercent(), level, definition.maxLevel())));
        placeholders.put("defense_percent", percent(scaleByLevel(definition.defensePercent(), level, definition.maxLevel())));
        placeholders.put("crit_rate", percent(scaleByLevel(definition.critRate(), level, definition.maxLevel())));
        placeholders.put("crit_damage", percent(scaleByLevel(definition.critDamage(), level, definition.maxLevel())));
        placeholders.put("energy_recharge", percent(scaleByLevel(definition.energyRecharge(), level, definition.maxLevel())));
        placeholders.put("elemental_mastery", trim(scaleByLevel(definition.elementalMastery(), level, definition.maxLevel())));
        placeholders.put("healing_bonus", percent(scaleByLevel(definition.healingBonus(), level, definition.maxLevel())));
        placeholders.put("element_bonus", percent(scaleByLevel(definition.elementBonus(), level, definition.maxLevel())));
        return placeholders;
    }

    private String applyPlaceholders(String template, Map<String, String> placeholders) {
        String resolved = template == null ? "" : template;
        if (placeholders == null || placeholders.isEmpty()) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    private String rarityColor(int rarity) {
        return switch (rarity) {
            case 5 -> ChatColor.GOLD.toString();
            case 4 -> ChatColor.DARK_PURPLE.toString();
            case 3 -> ChatColor.AQUA.toString();
            default -> ChatColor.WHITE.toString();
        };
    }

    private String elementName(String elementKey) {
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

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String percent(double value) {
        return trim(value * 100.0) + "%";
    }

    private String trim(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private record FallbackCharacterPreset(
            String material,
            int rarity,
            int defaultLevel,
            int maxLevel,
            double baseAttack,
            double baseHealth,
            double baseDefense,
            double critRate,
            double critDamage,
            double energyRecharge,
            double elementalMastery,
            double elementBonus
    ) {
    }
}
