package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WeaponManager {

    private static final String WEAPON_ID_MARKER_PREFIX = "ys_weapon_id:";
    private static final String WEAPON_LEVEL_MARKER_PREFIX = "ys_weapon_level:";
    private static final String WEAPON_REFINEMENT_MARKER_PREFIX = "ys_weapon_refinement:";
    private static final String MANUAL_DAMAGE_BYPASS_META = "ys_manual_damage_bypass";

    private final Yuanshen plugin;
    private final ResourceConfigManager resourceConfigManager;
    private final Map<String, WeaponDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, WeaponDefinition> displayNameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Long> notifyCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> archaicCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> atlasCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> atlasTasks = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> jadeStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastJadeStackGain = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, WeaponMemory>> rememberedWeapons = new ConcurrentHashMap<>();

    public WeaponManager(Yuanshen plugin, ResourceConfigManager resourceConfigManager) {
        this.plugin = plugin;
        this.resourceConfigManager = resourceConfigManager;
        reloadDefinitions();
    }

    public void reloadDefinitions() {
        definitions.clear();
        displayNameIndex.clear();
        for (WeaponDefinition definition : new WeaponConfigDirectoryLoader(plugin, resourceConfigManager).load()) {
            definitions.put(definition.id(), definition);
            displayNameIndex.put(normalizeKey(definition.displayName()), definition);
        }
    }

    public WeaponDefinition get(String weaponId) {
        if (weaponId == null || weaponId.isBlank()) {
            return null;
        }
        return definitions.get(weaponId.toLowerCase(Locale.ROOT));
    }

    public WeaponDefinition findByInput(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        WeaponDefinition byId = get(input);
        if (byId != null) {
            return byId;
        }
        return displayNameIndex.get(normalizeKey(input));
    }

    public List<String> getWeaponIds() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(WeaponDefinition::id))
                .map(WeaponDefinition::id)
                .toList();
    }

    public List<String> getWeaponCommandOptions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(WeaponDefinition::displayName))
                .map(WeaponDefinition::displayName)
                .toList();
    }

    public List<String> getWeaponListEntries() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(WeaponDefinition::displayName))
                .map(definition -> definition.displayName() + "(" + definition.id() + ")")
                .toList();
    }

    public ItemStack createWeaponItem(String weaponInput) {
        WeaponDefinition definition = findByInput(weaponInput);
        if (definition == null) {
            return null;
        }
        return createWeaponItem(definition.id(), definition.defaultLevel(), definition.defaultRefinement());
    }

    public ItemStack createWeaponItem(String weaponInput, int level, int refinement) {
        WeaponDefinition definition = findByInput(weaponInput);
        if (definition == null) {
            return null;
        }

        WeaponInstance instance = new WeaponInstance(
                definition,
                definition.clampLevel(level),
                definition.clampRefinement(refinement)
        );

        String materialName = definition.itemMaterial().isBlank() ? definition.material() : definition.itemMaterial();
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.STICK;
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
        lore.add(color("&8原神武器"));
        lore.add(color("&7类型: &f" + definition.weaponType().getDisplayName()));
        lore.add(color("&7等级: &f" + instance.level()));
        lore.add(color("&7精炼: &f" + instance.refinement()));
        for (Map.Entry<String, Double> entry : getDisplayStats(instance).entrySet()) {
            String line = formatDisplayStat(entry.getKey(), entry.getValue());
            if (line != null && !line.isBlank()) {
                lore.add(color(line));
            }
        }
        lore.addAll(passiveSummary(instance).stream().map(this::color).toList());
        lore.removeIf(line -> {
            String clean = sanitize(line);
            return clean.startsWith(WEAPON_ID_MARKER_PREFIX)
                    || clean.startsWith(WEAPON_LEVEL_MARKER_PREFIX)
                    || clean.startsWith(WEAPON_REFINEMENT_MARKER_PREFIX);
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
        plugin.getItemIdentity().applyWeapon(meta, instance);
        item.setItemMeta(meta);
        return item;
    }

    public WeaponInstance resolveWeaponInstance(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        YuanshenItemIdentity.WeaponData pdcData = plugin.getItemIdentity().readWeapon(meta);
        if (pdcData != null) {
            WeaponDefinition definition = get(pdcData.id());
            if (definition == null) {
                return null;
            }
            return new WeaponInstance(
                    definition,
                    definition.clampLevel(pdcData.level()),
                    definition.clampRefinement(pdcData.refinement())
            );
        }

        String weaponId = null;
        Integer level = null;
        Integer refinement = null;
        if (meta.hasLore() && meta.getLore() != null) {
            for (String rawLine : meta.getLore()) {
                String clean = sanitize(rawLine);
                if (clean.startsWith(WEAPON_ID_MARKER_PREFIX)) {
                    weaponId = clean.substring(WEAPON_ID_MARKER_PREFIX.length());
                    continue;
                }
                if (clean.startsWith(WEAPON_LEVEL_MARKER_PREFIX)) {
                    level = parseInt(clean.substring(WEAPON_LEVEL_MARKER_PREFIX.length()));
                    continue;
                }
                if (clean.startsWith(WEAPON_REFINEMENT_MARKER_PREFIX)) {
                    refinement = parseInt(clean.substring(WEAPON_REFINEMENT_MARKER_PREFIX.length()));
                }
            }
        }

        WeaponDefinition definition = weaponId == null ? null : get(weaponId);
        if (definition == null) {
            return null;
        }

        int resolvedLevel = definition.clampLevel(level == null ? definition.defaultLevel() : level);
        int resolvedRefinement = definition.clampRefinement(refinement == null ? definition.defaultRefinement() : refinement);
        return new WeaponInstance(definition, resolvedLevel, resolvedRefinement);
    }

    public WeaponDefinition resolveWeapon(ItemStack item) {
        WeaponInstance instance = resolveWeaponInstance(item);
        return instance == null ? null : instance.definition();
    }

    public ItemStack getEquippedWeaponItem(Player player) {
        if (player == null) {
            return null;
        }
        ItemStack handItem = player.getInventory().getItemInMainHand();
        return handItem == null || handItem.getType().isAir() ? null : handItem.clone();
    }

    public WeaponInstance getEquippedWeaponInstance(Player player) {
        return resolveWeaponInstance(getEquippedWeaponItem(player));
    }

    public WeaponDefinition getEquippedWeaponDefinition(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance == null ? null : instance.definition();
    }

    public void loadRememberedWeapons(Player player, ConfigurationSection section) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        rememberedWeapons.remove(playerId);
        if (section == null) {
            return;
        }

        Map<String, WeaponMemory> loaded = new LinkedHashMap<>();
        for (String rawCharacterId : section.getKeys(false)) {
            if (rawCharacterId == null || rawCharacterId.isBlank()) {
                continue;
            }
            ConfigurationSection memorySection = section.getConfigurationSection(rawCharacterId);
            if (memorySection == null) {
                continue;
            }

            String weaponId = normalizeStoredKey(memorySection.getString("weapon-id"));
            if (weaponId == null) {
                continue;
            }

            String weaponUuid = normalizeStoredUuid(memorySection.getString("weapon-uuid"));
            int level = Math.max(1, memorySection.getInt("level", 1));
            int refinement = Math.max(1, memorySection.getInt("refinement", 1));
            loaded.put(normalizeStoredKey(rawCharacterId), new WeaponMemory(weaponUuid, weaponId, level, refinement));
        }

        if (!loaded.isEmpty()) {
            rememberedWeapons.put(playerId, loaded);
        }
    }

    public void saveRememberedWeapons(Player player, FileConfiguration config, String path) {
        if (config == null || path == null || path.isBlank()) {
            return;
        }

        config.set(path, null);
        if (player == null) {
            return;
        }

        Map<String, WeaponMemory> memories = rememberedWeapons.get(player.getUniqueId());
        if (memories == null || memories.isEmpty()) {
            return;
        }

        for (Map.Entry<String, WeaponMemory> entry : memories.entrySet()) {
            String characterId = normalizeStoredKey(entry.getKey());
            WeaponMemory memory = entry.getValue();
            if (characterId == null || memory == null || memory.weaponId() == null || memory.weaponId().isBlank()) {
                continue;
            }

            String base = path + "." + characterId;
            config.set(base + ".weapon-id", memory.weaponId());
            if (memory.weaponUuid() != null && !memory.weaponUuid().isBlank()) {
                config.set(base + ".weapon-uuid", memory.weaponUuid());
            }
            config.set(base + ".level", memory.level());
            config.set(base + ".refinement", memory.refinement());
        }
    }

    public void rememberEquippedWeaponForSelectedCharacter(Player player) {
        if (player == null) {
            return;
        }
        rememberEquippedWeapon(player, plugin.getCharacterResolver().resolveCharacter(player));
    }

    public void rememberEquippedWeapon(Player player, CharacterType character) {
        if (!shouldRememberLastWeapon() || player == null || character == null) {
            return;
        }

        WeaponMemory memory = buildWeaponMemory(player.getInventory().getItemInMainHand(), character);
        if (memory == null) {
            return;
        }

        rememberedWeapons
                .computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(character.getId(), memory);
    }

    public void handleSelectedCharacterChange(Player player, CharacterType previous, CharacterType current) {
        if (player == null) {
            return;
        }

        rememberEquippedWeapon(player, previous);
        if (current == null) {
            return;
        }

        if (isCompatible(current, player.getInventory().getItemInMainHand())) {
            rememberEquippedWeapon(player, current);
            return;
        }

        if (!isAutoSwapOnCharacterSwitchEnabled()) {
            return;
        }

        int sourceSlot = findBestWeaponSlot(player, current);
        if (sourceSlot < 0 || !moveWeaponToMainHand(player, sourceSlot)) {
            return;
        }

        rememberEquippedWeapon(player, current);
    }

    public boolean canUseSelectedWeapon(Player player) {
        if (player == null) {
            return false;
        }
        CharacterType character = plugin.getCharacterResolver().resolveCharacter(player);
        if (character == null) {
            return false;
        }
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance != null && instance.definition().weaponType() == character.getWeaponType();
    }

    public boolean isRegisteredWeapon(ItemStack item) {
        return resolveWeaponInstance(item) != null;
    }

    public boolean isCompatible(CharacterType character, ItemStack item) {
        if (character == null) {
            return false;
        }
        WeaponDefinition definition = resolveWeapon(item);
        return definition != null && definition.weaponType() == character.getWeaponType();
    }

    public void notifyMissingOrInvalidWeapon(Player player) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long nextAllowed = notifyCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (nextAllowed > now) {
            return;
        }
        notifyCooldowns.put(player.getUniqueId(), now + 1500L);

        CharacterType character = plugin.getCharacterResolver().resolveCharacter(player);
        if (character == null) {
            return;
        }
        WeaponInstance instance = getEquippedWeaponInstance(player);
        if (instance == null) {
            player.sendMessage(color("&c当前主手没有拿着原神武器。请手持一把 "
                    + character.getWeaponType().getDisplayName() + "。"));
            return;
        }
        player.sendMessage(color("&c当前主手武器类型不匹配。&f" + character.getDisplayName()
                + "&c 需要 &f" + character.getWeaponType().getDisplayName()
                + "&c，当前是 &f" + instance.definition().weaponType().getDisplayName() + "&c。"));
    }

    public void applyStats(Player player, PlayerStats stats) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        if (instance == null || !canUseSelectedWeapon(player)) {
            return;
        }

        WeaponDefinition definition = instance.definition();
        WeaponRefinementStats refinement = definition.getRefinementStats(instance.refinement());
        int stacks = cleanupExpiredJadeStacks(player, definition);
        Map<String, Double> resolvedStats = getResolvedStats(instance);
        applyFlatStat(stats, "attack_damage", resolvedStats.getOrDefault("attack_damage", 0.0));
        applyFlatStat(stats, "health", resolvedStats.getOrDefault("health", 0.0));
        applyFlatStat(stats, "defense", resolvedStats.getOrDefault("defense", 0.0));

        double attackPercent = resolvedStats.getOrDefault("attack_percent", 0.0);
        if ("jade_spear_stacks".equals(definition.passiveId())) {
            attackPercent += (stacks * refinement.stackAttackBonus());
        }
        if (attackPercent != 0.0) {
            stats.setAttackDamage(stats.getAttackDamage() * (1.0 + attackPercent));
        }
        double healthPercent = resolvedStats.getOrDefault("health_percent", 0.0);
        if (healthPercent != 0.0) {
            stats.setHealth(stats.getHealth() * (1.0 + healthPercent));
        }
        double defensePercent = resolvedStats.getOrDefault("defense_percent", 0.0);
        if (defensePercent != 0.0) {
            stats.setDefense(stats.getDefense() * (1.0 + defensePercent));
        }

        for (String statKey : orderedStatKeys(definition, resolvedStats)) {
            if (isPrimaryScaleStat(statKey)) {
                continue;
            }
            double value = resolvedStats.getOrDefault(statKey, 0.0);
            if (value == 0.0) {
                continue;
            }
            applyResolvedStat(stats, statKey, value);
        }

        if ("hp_crit_rate".equals(definition.passiveId()) && player.getMaxHealth() > 0.0) {
            double hpPercent = player.getHealth() / player.getMaxHealth();
            if (hpPercent > definition.hpThreshold()) {
                stats.setCritRate(stats.getCritRate() + refinement.critRateBonus());
            }
        }
    }

    public double getNormalAttackDamageMultiplier(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        if (instance == null || !canUseSelectedWeapon(player)) {
            return 1.0;
        }
        return 1.0 + instance.definition().getRefinementStats(instance.refinement()).normalDamageBonus();
    }

    public double getChargedAttackDamageMultiplier(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        if (instance == null || !canUseSelectedWeapon(player)) {
            return 1.0;
        }
        return 1.0 + instance.definition().getRefinementStats(instance.refinement()).chargedDamageBonus();
    }

    public double getGlobalDamageMultiplier(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        if (instance == null || !canUseSelectedWeapon(player)) {
            return 1.0;
        }
        WeaponDefinition definition = instance.definition();
        WeaponRefinementStats refinement = definition.getRefinementStats(instance.refinement());
        if ("jade_spear_stacks".equals(definition.passiveId())
                && cleanupExpiredJadeStacks(player, definition) >= definition.maxStacks()) {
            return 1.0 + refinement.fullStackDamageBonus();
        }
        return 1.0;
    }

    public void onAttackHit(Player attacker, LivingEntity target, WeaponAttackType attackType, String attackElement) {
        WeaponInstance instance = getEquippedWeaponInstance(attacker);
        if (instance == null || !canUseSelectedWeapon(attacker) || target == null) {
            return;
        }

        WeaponDefinition definition = instance.definition();
        switch (definition.passiveId()) {
            case "aoe_physical_proc" -> tryTriggerArchaic(attacker, target, attackType, instance);
            case "atlas_orb_proc" -> tryTriggerAtlas(attacker, attackType, instance);
            case "jade_spear_stacks" -> addJadeStack(attacker, definition);
            default -> {
            }
        }
    }

    public String getSelectedWeaponName(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance == null ? "无武器" : instance.definition().displayName();
    }

    public String getSelectedWeaponId(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance == null ? "" : instance.definition().id();
    }

    public String getSelectedWeaponTypeName(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance == null ? "无类型" : instance.definition().weaponType().getDisplayName();
    }

    public int getSelectedWeaponLevel(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance == null ? 0 : instance.level();
    }

    public int getSelectedWeaponRefinement(Player player) {
        WeaponInstance instance = getEquippedWeaponInstance(player);
        return instance == null ? 0 : instance.refinement();
    }

    public double getEnergyRechargeBonus(ItemStack item) {
        WeaponInstance instance = resolveWeaponInstance(item);
        return instance == null ? 0.0 : getResolvedStat(instance, "energy_recharge");
    }

    public void clearRuntimeState(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        notifyCooldowns.remove(playerId);
        archaicCooldowns.remove(playerId);
        atlasCooldowns.remove(playerId);
        jadeStacks.remove(playerId);
        lastJadeStackGain.remove(playerId);
        rememberedWeapons.remove(playerId);
        BukkitTask atlasTask = atlasTasks.remove(playerId);
        if (atlasTask != null) {
            atlasTask.cancel();
        }
    }

    public void clearAllRuntimeState() {
        notifyCooldowns.clear();
        archaicCooldowns.clear();
        atlasCooldowns.clear();
        jadeStacks.clear();
        lastJadeStackGain.clear();
        rememberedWeapons.clear();
        for (BukkitTask task : atlasTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        atlasTasks.clear();
    }

    private boolean isAutoSwapOnCharacterSwitchEnabled() {
        return plugin.getConfig().getBoolean("character_switch.auto_swap_weapon.enabled", true);
    }

    private boolean shouldRememberLastWeapon() {
        return plugin.getConfig().getBoolean("character_switch.auto_swap_weapon.remember_last_weapon", true);
    }

    private int findBestWeaponSlot(Player player, CharacterType character) {
        int rememberedSlot = findRememberedWeaponSlot(player, character);
        if (rememberedSlot >= 0) {
            return rememberedSlot;
        }

        int hotbarSlot = findCompatibleWeaponSlot(player, character, 0, 8);
        if (hotbarSlot >= 0) {
            return hotbarSlot;
        }

        PlayerInventory inventory = player.getInventory();
        int maxStorageSlot = inventory.getStorageContents().length - 1;
        return findCompatibleWeaponSlot(player, character, 9, maxStorageSlot);
    }

    private int findRememberedWeaponSlot(Player player, CharacterType character) {
        if (player == null || character == null) {
            return -1;
        }

        WeaponMemory memory = rememberedWeapons
                .getOrDefault(player.getUniqueId(), Map.of())
                .get(character.getId());
        if (memory == null) {
            return -1;
        }

        PlayerInventory inventory = player.getInventory();
        int maxStorageSlot = inventory.getStorageContents().length - 1;
        if (memory.weaponUuid() != null && !memory.weaponUuid().isBlank()) {
            for (int slot = 0; slot <= maxStorageSlot; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (!isCompatible(character, item)) {
                    continue;
                }
                String weaponUuid = ensureWeaponIdentity(item);
                if (memory.weaponUuid().equals(weaponUuid)) {
                    return slot;
                }
            }
        }

        int rememberedHotbarSlot = findCompatibleWeaponSlotByMemory(player, character, memory, 0, 8);
        if (rememberedHotbarSlot >= 0) {
            return rememberedHotbarSlot;
        }
        return findCompatibleWeaponSlotByMemory(player, character, memory, 9, maxStorageSlot);
    }

    private int findCompatibleWeaponSlot(Player player, CharacterType character, int start, int end) {
        if (player == null || character == null) {
            return -1;
        }
        PlayerInventory inventory = player.getInventory();
        int maxStorageSlot = inventory.getStorageContents().length - 1;
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(end, maxStorageSlot);
        for (int slot = safeStart; slot <= safeEnd; slot++) {
            if (isCompatible(character, inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private int findCompatibleWeaponSlotByMemory(Player player, CharacterType character, WeaponMemory memory, int start, int end) {
        if (player == null || character == null || memory == null) {
            return -1;
        }
        PlayerInventory inventory = player.getInventory();
        int maxStorageSlot = inventory.getStorageContents().length - 1;
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(end, maxStorageSlot);
        for (int slot = safeStart; slot <= safeEnd; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isCompatible(character, item)) {
                continue;
            }
            WeaponMemory current = buildWeaponMemory(item, character);
            if (current != null && current.matchesFallback(memory)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean moveWeaponToMainHand(Player player, int sourceSlot) {
        if (player == null || sourceSlot < 0) {
            return false;
        }

        PlayerInventory inventory = player.getInventory();
        int heldSlot = inventory.getHeldItemSlot();
        if (sourceSlot == heldSlot) {
            return false;
        }

        if (sourceSlot >= 0 && sourceSlot <= 8) {
            inventory.setHeldItemSlot(sourceSlot);
            return true;
        }

        ItemStack sourceItem = inventory.getItem(sourceSlot);
        if (sourceItem == null || sourceItem.getType().isAir()) {
            return false;
        }

        ItemStack heldItem = inventory.getItem(heldSlot);
        inventory.setItem(sourceSlot, heldItem == null || heldItem.getType().isAir() ? null : heldItem);
        inventory.setItem(heldSlot, sourceItem);
        return true;
    }

    private WeaponMemory buildWeaponMemory(ItemStack item, CharacterType character) {
        if (character == null || !isCompatible(character, item)) {
            return null;
        }

        WeaponInstance instance = resolveWeaponInstance(item);
        if (instance == null) {
            return null;
        }

        String weaponUuid = ensureWeaponIdentity(item);
        return new WeaponMemory(
                normalizeStoredUuid(weaponUuid),
                instance.definition().id(),
                instance.level(),
                instance.refinement()
        );
    }

    private String ensureWeaponIdentity(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        WeaponInstance instance = resolveWeaponInstance(item);
        if (instance == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        plugin.getItemIdentity().applyWeapon(meta, instance);
        item.setItemMeta(meta);
        YuanshenItemIdentity.WeaponData weaponData = plugin.getItemIdentity().readWeapon(meta);
        return weaponData == null ? null : normalizeStoredUuid(weaponData.weaponUuid());
    }

    private String normalizeStoredKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStoredUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void tryTriggerArchaic(Player attacker, LivingEntity target, WeaponAttackType attackType,
                                   WeaponInstance instance) {
        WeaponDefinition definition = instance.definition();
        if (attackType != WeaponAttackType.NORMAL && attackType != WeaponAttackType.CHARGED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (archaicCooldowns.getOrDefault(attacker.getUniqueId(), 0L) > now) {
            return;
        }
        if (Math.random() > definition.procChance()) {
            return;
        }

        archaicCooldowns.put(attacker.getUniqueId(), now + definition.procCooldownMs());
        World world = target.getWorld();
        Location center = target.getLocation().clone().add(0, 0.6, 0);
        world.spawnParticle(Particle.CRIT, center, 14, 0.45, 0.25, 0.45, 0.04);
        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.9f);

        double multiplier = definition.getRefinementStats(instance.refinement()).procDamageMultiplier();
        for (Entity entity : world.getNearbyEntities(center, definition.procRadius(), definition.procRadius(), definition.procRadius())) {
            if (entity instanceof LivingEntity living && !living.equals(attacker) && living.isValid() && !living.isDead()) {
                applyManualPhysicalDamage(attacker, living, multiplier);
            }
        }
    }

    private void tryTriggerAtlas(Player attacker, WeaponAttackType attackType, WeaponInstance instance) {
        WeaponDefinition definition = instance.definition();
        if (attackType != WeaponAttackType.NORMAL) {
            return;
        }
        long now = System.currentTimeMillis();
        if (atlasCooldowns.getOrDefault(attacker.getUniqueId(), 0L) > now) {
            return;
        }
        if (Math.random() > definition.procChance()) {
            return;
        }

        atlasCooldowns.put(attacker.getUniqueId(), now + definition.procCooldownMs());
        UUID playerId = attacker.getUniqueId();
        BukkitTask oldTask = atlasTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }

        int totalHits = Math.max(1, definition.procHits());
        long interval = Math.max(5L, definition.procDurationTicks() / totalHits);
        double multiplier = definition.getRefinementStats(instance.refinement()).procDamageMultiplier();
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            private int firedHits;

            @Override
            public void run() {
                if (!attacker.isOnline() || attacker.isDead()) {
                    cancelTask(playerId);
                    return;
                }

                LivingEntity target = findNearestTarget(attacker, 8.0);
                if (target != null) {
                    Location from = attacker.getLocation().clone().add(0, 1.8, 0);
                    Location to = target.getLocation().clone().add(0, 1.0, 0);
                    spawnAtlasTrail(from, to);
                    applyManualPhysicalDamage(attacker, target, multiplier);
                }

                firedHits++;
                if (firedHits >= totalHits) {
                    cancelTask(playerId);
                }
            }

            private void cancelTask(UUID id) {
                BukkitTask running = atlasTasks.remove(id);
                if (running != null) {
                    running.cancel();
                }
            }
        }, 0L, interval);
        atlasTasks.put(playerId, task);
    }

    private void addJadeStack(Player attacker, WeaponDefinition definition) {
        long now = System.currentTimeMillis();
        UUID playerId = attacker.getUniqueId();
        long lastGain = lastJadeStackGain.getOrDefault(playerId, 0L);
        if ((now - lastGain) < definition.stackIntervalMs()) {
            return;
        }

        lastJadeStackGain.put(playerId, now);
        List<Long> stacks = jadeStacks.computeIfAbsent(playerId, ignored -> new ArrayList<>());
        stacks.add(now);
        while (stacks.size() > definition.maxStacks()) {
            stacks.remove(0);
        }
        cleanupExpiredJadeStacks(attacker, definition);
        plugin.refreshPlayerStats(attacker);
    }

    private int cleanupExpiredJadeStacks(Player player, WeaponDefinition definition) {
        if (player == null) {
            return 0;
        }
        List<Long> stacks = jadeStacks.get(player.getUniqueId());
        if (stacks == null || stacks.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        boolean changed = stacks.removeIf(time -> (now - time) > definition.stackDurationMs());
        if (stacks.isEmpty()) {
            jadeStacks.remove(player.getUniqueId());
        }
        if (changed) {
            plugin.refreshPlayerStats(player);
        }
        return stacks.size();
    }

    private LivingEntity findNearestTarget(Player attacker, double radius) {
        return attacker.getWorld().getNearbyEntities(attacker.getLocation(), radius, radius, radius).stream()
                .filter(entity -> entity instanceof LivingEntity)
                .map(entity -> (LivingEntity) entity)
                .filter(living -> !living.equals(attacker) && living.isValid() && !living.isDead())
                .min(Comparator.comparingDouble(living -> living.getLocation().distanceSquared(attacker.getLocation())))
                .orElse(null);
    }

    private void spawnAtlasTrail(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) {
            return;
        }

        double distance = Math.max(0.2, from.distance(to));
        org.bukkit.util.Vector step = to.toVector().subtract(from.toVector()).normalize().multiply(0.7);
        Location point = from.clone();
        for (double moved = 0.0; moved < distance; moved += 0.7) {
            point.add(step);
            world.spawnParticle(Particle.END_ROD, point, 1, 0.01, 0.01, 0.01, 0.0);
            world.spawnParticle(Particle.ENCHANT, point, 1, 0.02, 0.02, 0.02, 0.0);
        }
        world.playSound(to, Sound.ENTITY_ALLAY_ITEM_TAKEN, 0.6f, 1.3f);
    }

    private void applyManualPhysicalDamage(Player attacker, LivingEntity target, double damageMultiplier) {
        DamageResult damageResult = plugin.getDamageCalculator()
                .calculateElementDamage(attacker, target, PlayerStats.PHYSICAL_KEY, getEquippedWeaponItem(attacker));
        if (damageResult == null) {
            return;
        }

        double finalDamage = damageResult.getRawTotalDamage() * damageMultiplier;
        finalDamage *= getGlobalDamageMultiplier(attacker);
        if (damageResult.isCrit() && damageResult.getPlayerStats() != null) {
            finalDamage *= (1.0 + damageResult.getPlayerStats().getCritDamage());
        }
        finalDamage = plugin.applyMobResistance(target, finalDamage, PlayerStats.PHYSICAL_KEY);

        target.setMetadata(MANUAL_DAMAGE_BYPASS_META, new FixedMetadataValue(plugin, attacker.getUniqueId().toString()));
        target.setNoDamageTicks(0);
        target.damage(finalDamage, attacker);
    }

    private Map<String, Double> getDisplayStats(WeaponInstance instance) {
        Map<String, Double> resolvedStats = getResolvedStats(instance);
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (String statKey : orderedStatKeys(instance.definition(), resolvedStats)) {
            double value = resolvedStats.getOrDefault(statKey, 0.0);
            if (value != 0.0) {
                ordered.put(statKey, value);
            }
        }
        return ordered;
    }

    private Map<String, Double> getResolvedStats(WeaponInstance instance) {
        Map<String, Double> values = new LinkedHashMap<>();
        if (instance == null || instance.definition() == null) {
            return values;
        }

        WeaponDefinition definition = instance.definition();
        mergeStat(values, "attack_damage", scaleByLevel(definition.baseAttack(), instance.level(), definition.maxLevel()));
        mergeStat(values, "attack_percent", scaleByLevel(definition.attackPercent(), instance.level(), definition.maxLevel()));
        mergeStat(values, "crit_rate", scaleByLevel(definition.critRate(), instance.level(), definition.maxLevel()));
        mergeStat(values, "crit_damage", scaleByLevel(definition.critDamage(), instance.level(), definition.maxLevel()));
        mergeStat(values, "energy_recharge", scaleByLevel(definition.energyRecharge(), instance.level(), definition.maxLevel()));
        mergeStat(values, "element_mastery", scaleByLevel(definition.elementalMastery(), instance.level(), definition.maxLevel()));
        mergeStat(values, "all_element_damage_bonus",
                scaleByLevel(definition.allElementDamageBonus(), instance.level(), definition.maxLevel()));

        Map<String, Double> levelStats = resolveLevelStats(definition, instance.level());
        for (Map.Entry<String, Double> entry : levelStats.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }
        if (definition.bonusStats() != null) {
            for (Map.Entry<String, Double> entry : definition.bonusStats().entrySet()) {
                mergeStat(values, entry.getKey(), entry.getValue());
            }
        }

        WeaponRefinementStats refinement = definition.getRefinementStats(instance.refinement());
        mergeStat(values, "all_element_damage_bonus", refinement.allElementDamageBonus());
        return values;
    }

    private Map<String, Double> resolveLevelStats(WeaponDefinition definition, int level) {
        if (definition == null || definition.levelStats() == null || definition.levelStats().isEmpty()) {
            return Map.of();
        }
        Map<String, Double> exact = definition.levelStats().get(level);
        if (exact != null) {
            return exact;
        }

        int fallbackLevel = -1;
        for (Integer configuredLevel : definition.levelStats().keySet()) {
            if (configuredLevel == null) {
                continue;
            }
            if (configuredLevel <= level && configuredLevel > fallbackLevel) {
                fallbackLevel = configuredLevel;
            }
        }
        if (fallbackLevel > 0) {
            return definition.levelStats().getOrDefault(fallbackLevel, Map.of());
        }

        int nearestHigher = Integer.MAX_VALUE;
        for (Integer configuredLevel : definition.levelStats().keySet()) {
            if (configuredLevel != null && configuredLevel < nearestHigher) {
                nearestHigher = configuredLevel;
            }
        }
        return nearestHigher == Integer.MAX_VALUE
                ? Map.of()
                : definition.levelStats().getOrDefault(nearestHigher, Map.of());
    }

    private List<String> orderedStatKeys(WeaponDefinition definition, Map<String, Double> resolvedStats) {
        Set<String> ordered = new LinkedHashSet<>();
        addOrderedStatKey(ordered, definition == null ? null : definition.mainStatKey(), resolvedStats);
        addOrderedStatKey(ordered, definition == null ? null : definition.subStatKey(), resolvedStats);
        for (String statKey : List.of(
                "attack_damage",
                "attack_percent",
                "health",
                "health_percent",
                "defense",
                "defense_percent",
                "crit_rate",
                "crit_damage",
                "energy_recharge",
                "element_mastery",
                "healing_bonus",
                "physical_damage",
                "fire_damage",
                "water_damage",
                "ice_damage",
                "electro_damage",
                "anemo_damage",
                "geo_damage",
                "dendro_damage",
                "all_element_damage_bonus",
                "fire_resistance",
                "water_resistance",
                "ice_resistance",
                "electro_resistance",
                "anemo_resistance",
                "geo_resistance",
                "dendro_resistance",
                "all_element_resistance",
                "physical_resistance"
        )) {
            addOrderedStatKey(ordered, statKey, resolvedStats);
        }
        for (String statKey : resolvedStats.keySet()) {
            addOrderedStatKey(ordered, statKey, resolvedStats);
        }
        return new ArrayList<>(ordered);
    }

    private void addOrderedStatKey(Set<String> ordered, String statKey, Map<String, Double> resolvedStats) {
        if (statKey == null || statKey.isBlank() || resolvedStats == null) {
            return;
        }
        double value = resolvedStats.getOrDefault(statKey, 0.0);
        if (value != 0.0) {
            ordered.add(statKey);
        }
    }

    private void applyFlatStat(PlayerStats stats, String statKey, double value) {
        if (stats == null || value == 0.0) {
            return;
        }
        switch (statKey) {
            case "attack_damage" -> stats.setAttackDamage(stats.getAttackDamage() + value);
            case "health" -> stats.setHealth(stats.getHealth() + value);
            case "defense" -> stats.setDefense(stats.getDefense() + value);
            default -> {
            }
        }
    }

    private boolean isPrimaryScaleStat(String statKey) {
        return "attack_damage".equals(statKey)
                || "health".equals(statKey)
                || "defense".equals(statKey)
                || "attack_percent".equals(statKey)
                || "health_percent".equals(statKey)
                || "defense_percent".equals(statKey);
    }

    private void applyResolvedStat(PlayerStats stats, String statKey, double value) {
        if (stats == null || statKey == null || value == 0.0) {
            return;
        }
        switch (statKey) {
            case "crit_rate" -> stats.setCritRate(stats.getCritRate() + value);
            case "crit_damage" -> stats.setCritDamage(stats.getCritDamage() + value);
            case "energy_recharge" -> stats.setEnergyRecharge(stats.getEnergyRecharge() + value);
            case "element_mastery" -> stats.setElementMastery(stats.getElementMastery() + value);
            case "healing_bonus" -> stats.setHealingBonus(stats.getHealingBonus() + value);
            case "physical_damage" -> stats.setElementBonus(PlayerStats.PHYSICAL_KEY,
                    stats.getElementBonus(PlayerStats.PHYSICAL_KEY) + value);
            case "fire_damage" -> stats.setElementBonus(ElementConstant.FIRE_KEY,
                    stats.getElementBonus(ElementConstant.FIRE_KEY) + value);
            case "water_damage" -> stats.setElementBonus(ElementConstant.WATER_KEY,
                    stats.getElementBonus(ElementConstant.WATER_KEY) + value);
            case "ice_damage" -> stats.setElementBonus(ElementConstant.ICE_KEY,
                    stats.getElementBonus(ElementConstant.ICE_KEY) + value);
            case "electro_damage" -> stats.setElementBonus(ElementConstant.ELECTRO_KEY,
                    stats.getElementBonus(ElementConstant.ELECTRO_KEY) + value);
            case "anemo_damage" -> stats.setElementBonus(ElementConstant.ANEMO_KEY,
                    stats.getElementBonus(ElementConstant.ANEMO_KEY) + value);
            case "geo_damage" -> stats.setElementBonus(ElementConstant.GEO_KEY,
                    stats.getElementBonus(ElementConstant.GEO_KEY) + value);
            case "dendro_damage" -> stats.setElementBonus(ElementConstant.DENDRO_KEY,
                    stats.getElementBonus(ElementConstant.DENDRO_KEY) + value);
            case "all_element_damage_bonus" -> applyElementBonus(stats, value,
                    ElementConstant.FIRE_KEY,
                    ElementConstant.WATER_KEY,
                    ElementConstant.ICE_KEY,
                    ElementConstant.ELECTRO_KEY,
                    ElementConstant.ANEMO_KEY,
                    ElementConstant.GEO_KEY,
                    ElementConstant.DENDRO_KEY);
            case "fire_resistance" -> stats.setResistance(ElementConstant.FIRE_KEY,
                    stats.getResistance(ElementConstant.FIRE_KEY) + value);
            case "water_resistance" -> stats.setResistance(ElementConstant.WATER_KEY,
                    stats.getResistance(ElementConstant.WATER_KEY) + value);
            case "ice_resistance" -> stats.setResistance(ElementConstant.ICE_KEY,
                    stats.getResistance(ElementConstant.ICE_KEY) + value);
            case "electro_resistance" -> stats.setResistance(ElementConstant.ELECTRO_KEY,
                    stats.getResistance(ElementConstant.ELECTRO_KEY) + value);
            case "anemo_resistance" -> stats.setResistance(ElementConstant.ANEMO_KEY,
                    stats.getResistance(ElementConstant.ANEMO_KEY) + value);
            case "geo_resistance" -> stats.setResistance(ElementConstant.GEO_KEY,
                    stats.getResistance(ElementConstant.GEO_KEY) + value);
            case "dendro_resistance" -> stats.setResistance(ElementConstant.DENDRO_KEY,
                    stats.getResistance(ElementConstant.DENDRO_KEY) + value);
            case "all_element_resistance" -> applyResistance(stats, value,
                    ElementConstant.FIRE_KEY,
                    ElementConstant.WATER_KEY,
                    ElementConstant.ICE_KEY,
                    ElementConstant.ELECTRO_KEY,
                    ElementConstant.ANEMO_KEY,
                    ElementConstant.GEO_KEY,
                    ElementConstant.DENDRO_KEY);
            case "physical_resistance" -> stats.setResistance(PlayerStats.PHYSICAL_KEY,
                    stats.getResistance(PlayerStats.PHYSICAL_KEY) + value);
            default -> {
            }
        }
    }

    private void applyElementBonus(PlayerStats stats, double value, String... keys) {
        for (String key : keys) {
            stats.setElementBonus(key, stats.getElementBonus(key) + value);
        }
    }

    private void applyResistance(PlayerStats stats, double value, String... keys) {
        for (String key : keys) {
            stats.setResistance(key, stats.getResistance(key) + value);
        }
    }

    private double getResolvedStat(WeaponInstance instance, String statKey) {
        return getResolvedStats(instance).getOrDefault(statKey, 0.0);
    }

    private void mergeStat(Map<String, Double> stats, String statKey, double value) {
        if (stats == null || statKey == null || statKey.isBlank() || value == 0.0) {
            return;
        }
        stats.merge(statKey, value, Double::sum);
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

    private String formatDisplayStat(String statKey, double value) {
        if (value == 0.0) {
            return null;
        }
        return switch (statKey) {
            case "attack_damage" -> "&7基础攻击: &f" + trim(value);
            case "attack_percent" -> "&7攻击力: &f+" + percent(value);
            case "health" -> "&7生命值: &f+" + trim(value);
            case "health_percent" -> "&7生命值: &f+" + percent(value);
            case "defense" -> "&7防御力: &f+" + trim(value);
            case "defense_percent" -> "&7防御力: &f+" + percent(value);
            case "crit_rate" -> "&7暴击率: &f+" + percent(value);
            case "crit_damage" -> "&7暴击伤害: &f+" + percent(value);
            case "energy_recharge" -> "&7元素充能效率: &f+" + percent(value);
            case "element_mastery" -> "&7元素精通: &f+" + trim(value);
            case "healing_bonus" -> "&7治疗加成: &f+" + percent(value);
            case "physical_damage" -> "&7物理伤害加成: &f+" + percent(value);
            case "fire_damage" -> "&7火元素伤害加成: &f+" + percent(value);
            case "water_damage" -> "&7水元素伤害加成: &f+" + percent(value);
            case "ice_damage" -> "&7冰元素伤害加成: &f+" + percent(value);
            case "electro_damage" -> "&7雷元素伤害加成: &f+" + percent(value);
            case "anemo_damage" -> "&7风元素伤害加成: &f+" + percent(value);
            case "geo_damage" -> "&7岩元素伤害加成: &f+" + percent(value);
            case "dendro_damage" -> "&7草元素伤害加成: &f+" + percent(value);
            case "all_element_damage_bonus" -> "&7元素伤害加成: &f+" + percent(value);
            case "fire_resistance" -> "&7火元素抗性: &f+" + percent(value);
            case "water_resistance" -> "&7水元素抗性: &f+" + percent(value);
            case "ice_resistance" -> "&7冰元素抗性: &f+" + percent(value);
            case "electro_resistance" -> "&7雷元素抗性: &f+" + percent(value);
            case "anemo_resistance" -> "&7风元素抗性: &f+" + percent(value);
            case "geo_resistance" -> "&7岩元素抗性: &f+" + percent(value);
            case "dendro_resistance" -> "&7草元素抗性: &f+" + percent(value);
            case "all_element_resistance" -> "&7全元素抗性: &f+" + percent(value);
            case "physical_resistance" -> "&7物理抗性: &f+" + percent(value);
            default -> "&7" + statKey + ": &f" + trim(value);
        };
    }

    private List<String> passiveSummary(WeaponInstance instance) {
        WeaponDefinition definition = instance.definition();
        WeaponRefinementStats refinement = definition.getRefinementStats(instance.refinement());
        return switch (definition.passiveId()) {
            case "hp_crit_rate" -> List.of(
                    "&7生命值高于 " + percent(definition.hpThreshold()) + " 时，暴击率提升 "
                            + percent(refinement.critRateBonus())
            );
            case "aoe_physical_proc" -> List.of(
                    "&7普通攻击/重击命中时，" + percent(definition.procChance()) + " 概率造成 "
                            + percent(refinement.procDamageMultiplier()),
                    "&7的额外物理范围伤害，" + trim(definition.procCooldownMs() / 1000.0) + " 秒冷却"
            );
            case "normal_bonus_charged_penalty" -> List.of(
                    "&7普通攻击造成的伤害提升 " + percent(refinement.normalDamageBonus()),
                    "&7重击造成的伤害降低 " + percent(Math.abs(refinement.chargedDamageBonus()))
            );
            case "atlas_orb_proc" -> List.of(
                    "&7元素伤害加成提升 " + percent(refinement.allElementDamageBonus()),
                    "&7普通攻击命中时，" + percent(definition.procChance()) + " 概率召出云团攻击敌人",
                    "&7持续 " + trim(definition.procDurationTicks() / 20.0) + " 秒，共 " + definition.procHits()
                            + " 段，" + trim(definition.procCooldownMs() / 1000.0) + " 秒冷却",
                    "&7每段造成 " + percent(refinement.procDamageMultiplier()) + " 攻击力伤害"
            );
            case "jade_spear_stacks" -> List.of(
                    "&7命中敌人后攻击力提升 " + percent(refinement.stackAttackBonus()) + "，持续 "
                            + trim(definition.stackDurationMs() / 1000.0) + " 秒",
                    "&7最多 " + definition.maxStacks() + " 层，每 "
                            + trim(definition.stackIntervalMs() / 1000.0) + " 秒至多触发一次",
                    "&7满层时伤害提升 " + percent(refinement.fullStackDamageBonus())
            );
            default -> List.of();
        };
    }

    private Map<String, String> buildItemPlaceholders(WeaponInstance instance) {
        WeaponDefinition definition = instance.definition();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("weapon_id", definition.id());
        placeholders.put("weapon_name", definition.displayName());
        placeholders.put("weapon_type", definition.weaponType().getDisplayName());
        placeholders.put("level", String.valueOf(instance.level()));
        placeholders.put("refinement", String.valueOf(instance.refinement()));
        placeholders.put("rarity", String.valueOf(definition.rarity()));
        for (Map.Entry<String, Double> entry : getResolvedStats(instance).entrySet()) {
            placeholders.put(entry.getKey(), formatStatPlaceholder(entry.getKey(), entry.getValue()));
        }
        placeholders.put("passive_id", definition.passiveId() == null ? "" : definition.passiveId());
        placeholders.put("passive_summary", String.join(" | ", passiveSummary(instance).stream()
                .map(this::color)
                .toList()));
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

    private String formatStatPlaceholder(String statKey, double value) {
        return switch (statKey) {
            case "attack_percent",
                    "health_percent",
                    "defense_percent",
                    "crit_rate",
                    "crit_damage",
                    "energy_recharge",
                    "healing_bonus",
                    "physical_damage",
                    "fire_damage",
                    "water_damage",
                    "ice_damage",
                    "electro_damage",
                    "anemo_damage",
                    "geo_damage",
                    "dendro_damage",
                    "all_element_damage_bonus",
                    "fire_resistance",
                    "water_resistance",
                    "ice_resistance",
                    "electro_resistance",
                    "anemo_resistance",
                    "geo_resistance",
                    "dendro_resistance",
                    "all_element_resistance",
                    "physical_resistance" -> percent(value);
            default -> trim(value);
        };
    }

    private int parseInt(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String normalizeKey(String text) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text == null ? "" : text))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String rarityColor(int rarity) {
        return switch (rarity) {
            case 5 -> ChatColor.GOLD.toString();
            case 4 -> ChatColor.DARK_PURPLE.toString();
            case 3 -> ChatColor.AQUA.toString();
            default -> ChatColor.WHITE.toString();
        };
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String sanitize(String text) {
        return normalizeKey(text);
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

    public record WeaponMemory(String weaponUuid, String weaponId, int level, int refinement) {
        public boolean matchesFallback(WeaponMemory other) {
            if (other == null || weaponId == null || other.weaponId == null) {
                return false;
            }
            return weaponId.equalsIgnoreCase(other.weaponId)
                    && level == other.level
                    && refinement == other.refinement;
        }
    }
}
