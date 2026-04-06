package org.yuanshen.yuanshen;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Yuanshen extends JavaPlugin {

    private ConfigParser configParser;
    private ElementDamageCalculator damageCalculator;
    private ElementReactionManager reactionManager;
    private ElementUtils utils;
    private HuTaoStateManager huTaoStateManager;
    private HuTaoBloodBlossomManager huTaoBloodBlossomManager;
    private HuTaoSkillHandler huTaoSkillHandler;
    private XianglingGuobaManager xianglingGuobaManager;
    private XianglingPepperManager xianglingPepperManager;
    private XianglingPyronadoManager xianglingPyronadoManager;
    private XianglingSkillHandler xianglingSkillHandler;
    private DilucSkillHandler dilucSkillHandler;
    private KeqingSkillHandler keqingSkillHandler;
    private NingguangSkillHandler ningguangSkillHandler;
    private TravelerAnemoStateManager travelerAnemoStateManager;
    private TravelerAnemoSkillHandler travelerAnemoSkillHandler;
    private YelanSkillHandler yelanSkillHandler;
    private LoreStatParser loreStatParser;
    private PlayerDataStore playerDataStore;
    private ResourceConfigManager resourceConfigManager;
    private MobResistanceManager mobResistanceManager;
    private YuanshenPlaceholderResolver placeholderResolver;
    private SidebarDisplayManager sidebarDisplayManager;
    private Object placeholderExpansion;
    private WeaponManager weaponManager;
    private CharacterManager characterManager;
    private ConstellationManager constellationManager;
    private ConstellationGuiManager constellationGuiManager;
    private AdminGuiManager adminGuiManager;
    private EnergyManager energyManager;
    private MythicMobsBridge mythicMobsBridge;
    private YuanshenItemIdentity itemIdentity;
    private ModKeybindBridge modKeybindBridge;

    private CharacterResolver characterResolver;
    private CharacterSkillEngine characterSkillEngine;
    private CharacterStateRegistry characterStateRegistry;
    private CharacterSlotManager characterSlotManager;

    private boolean hasPlaceholderAPI = false;
    private boolean hasMythicMobs = false;

    private File skillsFile;
    private FileConfiguration skillsConfig;
    private File starterCharactersFile;
    private FileConfiguration starterCharactersConfig;
    private File attributesFile;
    private FileConfiguration attributesConfig;
    private File sidebarFile;
    private FileConfiguration sidebarConfig;
    private File characterGuiFile;
    private FileConfiguration characterGuiConfig;
    private File modKeybindFile;
    private FileConfiguration modKeybindConfig;

    private final Set<String> warnedKeys = new HashSet<>();
    private final Map<UUID, Long> chargedAttackSuppressions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> normalAttackSuppressions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> damageDisplaySettings = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> sidebarDisplaySettings = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeElements = new ConcurrentHashMap<>();
    private final Map<UUID, AttributeOverrideState> attributeOverrideStates = new ConcurrentHashMap<>();

    private static final class AttributeOverrideState {
        private Double originalMaxHealthBase;
        private Double originalMaxHealthBaseline;
        private Double originalAttackDamageBase;
        private Double originalArmorBase;

        private boolean isEmpty() {
            return originalMaxHealthBase == null
                    && originalMaxHealthBaseline == null
                    && originalAttackDamageBase == null
                    && originalArmorBase == null;
        }
    }

    @Override
    public void onEnable() {
        this.resourceConfigManager = new ResourceConfigManager(this);
        loadAllConfigs();
        warnDeprecatedConfigKeys();
        this.mobResistanceManager = new MobResistanceManager(this, resourceConfigManager);
        this.mobResistanceManager.reloadConfigs();
        checkDependencies();
        this.mythicMobsBridge = new MythicMobsBridge(this);
        this.itemIdentity = new YuanshenItemIdentity(this);

        this.configParser = new ConfigParser(this);
        this.utils = new ElementUtils(this);
        this.loreStatParser = new LoreStatParser(this);
        this.characterSlotManager = new CharacterSlotManager(this);
        this.characterResolver = new CharacterResolver(this);
        this.characterManager = new CharacterManager(this);
        this.constellationManager = new ConstellationManager(this);
        this.constellationGuiManager = new ConstellationGuiManager(this);
        this.weaponManager = new WeaponManager(this, resourceConfigManager);
        this.adminGuiManager = new AdminGuiManager(this);
        this.energyManager = new EnergyManager(this);
        this.characterSkillEngine = new CharacterSkillEngine(this);
        this.characterStateRegistry = new CharacterStateRegistry();
        this.placeholderResolver = new YuanshenPlaceholderResolver(this);
        this.sidebarDisplayManager = new SidebarDisplayManager(this, placeholderResolver);
        this.playerDataStore = new PlayerDataStore(this);

        this.damageCalculator = new ElementDamageCalculator(this, configParser);
        this.reactionManager = new ElementReactionManager(this, utils, configParser, damageCalculator);
        this.huTaoStateManager = new HuTaoStateManager(this);
        this.huTaoBloodBlossomManager = new HuTaoBloodBlossomManager(this);
        this.huTaoSkillHandler = new HuTaoSkillHandler(this);
        this.xianglingGuobaManager = new XianglingGuobaManager(this);
        this.xianglingPepperManager = new XianglingPepperManager(this);
        this.xianglingPyronadoManager = new XianglingPyronadoManager(this);
        this.xianglingSkillHandler = new XianglingSkillHandler(this);
        this.dilucSkillHandler = new DilucSkillHandler(this);
        this.keqingSkillHandler = new KeqingSkillHandler(this);
        this.ningguangSkillHandler = new NingguangSkillHandler(this);
        this.travelerAnemoStateManager = new TravelerAnemoStateManager();
        this.travelerAnemoSkillHandler = new TravelerAnemoSkillHandler(this);
        this.yelanSkillHandler = new YelanSkillHandler(this);

        this.characterStateRegistry.register(huTaoStateManager);
        this.characterStateRegistry.register(xianglingPepperManager);
        this.characterStateRegistry.register(travelerAnemoStateManager);
        this.characterSkillEngine.register(huTaoSkillHandler);
        this.characterSkillEngine.register(xianglingSkillHandler);
        this.characterSkillEngine.register(dilucSkillHandler);
        this.characterSkillEngine.register(keqingSkillHandler);
        this.characterSkillEngine.register(ningguangSkillHandler);
        this.characterSkillEngine.register(travelerAnemoSkillHandler);
        this.characterSkillEngine.register(yelanSkillHandler);

        getServer().getPluginManager().registerEvents(
                new UnifiedElementListener(this, utils, reactionManager, damageCalculator, configParser),
                this
        );
        getServer().getPluginManager().registerEvents(new YelanBowChargeListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDataListener(this), this);
        getServer().getPluginManager().registerEvents(new CharacterGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new ConstellationGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new AdminGuiListener(this), this);

        this.modKeybindBridge = new ModKeybindBridge(this);
        this.modKeybindBridge.register();
        new ReloadCommand(this);
        registerPlaceholderExpansion();
        getServer().getOnlinePlayers().forEach(playerDataStore::loadPlayer);
        sidebarDisplayManager.reload();

        getLogger().info("Yuanshen 插件已启用");
        getLogger().info("依赖检测: PlaceholderAPI=" + (hasPlaceholderAPI ? "已检测到" : "未检测到"));
        getLogger().info("依赖检测: MythicMobs=" + (hasMythicMobs ? "已检测到" : "未检测到"));
        getLogger().info("配置版本: 主配置 " + getMainConfigVersion()
                + ", 技能配置 " + getSkillsConfigVersion()
                + ", 属性配置 " + getAttributesConfigVersion());
    }

    @Override
    public void onDisable() {
        if (modKeybindBridge != null) {
            modKeybindBridge.unregister();
            modKeybindBridge = null;
        }
        getServer().getOnlinePlayers().forEach(this::restorePlayerAttributes);
        if (playerDataStore != null) {
            playerDataStore.shutdown();
        }
        if (sidebarDisplayManager != null) {
            sidebarDisplayManager.shutdown();
        }
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
            } catch (ReflectiveOperationException ignored) {
                // Best-effort cleanup for PlaceholderAPI expansion.
            }
            placeholderExpansion = null;
        }
        if (configParser != null) {
            configParser.clearCache();
        }
        if (damageCalculator != null) {
            damageCalculator.clearCache();
        }
        if (characterStateRegistry != null) {
            characterStateRegistry.clearAll();
        }
        if (huTaoBloodBlossomManager != null) {
            huTaoBloodBlossomManager.clearAll();
        }
        if (xianglingGuobaManager != null) {
            xianglingGuobaManager.clearAll();
        }
        if (xianglingPepperManager != null) {
            xianglingPepperManager.clearAll();
        }
        if (xianglingPyronadoManager != null) {
            xianglingPyronadoManager.clearAll();
        }
        if (dilucSkillHandler != null) {
            dilucSkillHandler.clearAllRuntimeState();
        }
        if (keqingSkillHandler != null) {
            keqingSkillHandler.clearAllRuntimeState();
        }
        if (ningguangSkillHandler != null) {
            ningguangSkillHandler.clearAllRuntimeState();
        }
        if (travelerAnemoSkillHandler != null) {
            travelerAnemoSkillHandler.clearAllRuntimeState();
        }
        if (yelanSkillHandler != null) {
            yelanSkillHandler.clearAllRuntimeState();
        }
        if (weaponManager != null) {
            weaponManager.clearAllRuntimeState();
        }
        if (characterManager != null) {
            characterManager.clearAllRuntimeState();
        }
        if (energyManager != null) {
            energyManager.clearAll();
        }
        if (mobResistanceManager != null) {
            mobResistanceManager.clearAllRuntimeState();
        }
        if (reactionManager != null) {
            reactionManager.clearAllRuntimeState();
        }
        PlayerSkillData.clearAll();
        sidebarDisplaySettings.clear();
        activeElements.clear();
        attributeOverrideStates.clear();
        normalAttackSuppressions.clear();
        getLogger().info("Yuanshen 插件已关闭");
    }

    private void checkDependencies() {
        hasPlaceholderAPI = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        hasMythicMobs = getServer().getPluginManager().getPlugin("MythicMobs") != null;
    }

    private void loadAllConfigs() {
        resourceConfigManager.loadMainConfig("config.yml", List.of(
                "logging",
                "skill_trigger",
                "character_switch",
                "character_switch.auto_swap_weapon",
                "character_switch.auto_swap_weapon.enabled",
                "character_switch.auto_swap_weapon.remember_last_weapon",
                "constellation_star",
                "elements",
                "reactions",
                "effects",
                "messages.reactions",
                "default_stats",
                "attribute_override",
                "attribute_override.enabled",
                "attribute_override.only-active-character-mode",
                "attribute_override.max_health",
                "attribute_override.attack_damage",
                "attribute_override.defense_to_armor",
                "attribute_override.armor-scale",
                "attribute_override.armor-max",
                "attachment",
                "attachment.standard-icd",
                "attachment.standard-icd.enabled",
                "attachment.standard-icd.window-ms",
                "attachment.standard-icd.hits",
                "attachment.standard-icd.offset",
                "attachment.aura",
                "attachment.aura.normal",
                "attachment.aura.charged",
                "attachment.aura.skill",
                "attachment.aura.burst",
                "attachment.aura.plunge",
                "attachment.aura.duration-tier",
                "element_mastery",
                "bloom_seed.visual",
                "hyperbloom",
                "burgeon",
                "swirl",
                "bloom_core"
        ));

        skillsFile = new File(getDataFolder(), "juese");
        skillsConfig = new CharacterConfigDirectoryLoader(this, resourceConfigManager).load();

        starterCharactersFile = new File(getDataFolder(), "starter-characters.yml");
        starterCharactersConfig = resourceConfigManager.loadAuxiliaryConfig("starter-characters.yml", starterCharactersFile, List.of(
                "starter",
                "starter.assign-on-first-join",
                "starter.enabled",
                "starter.slot",
                "starter.character-id",
                "starter.item",
                "starter.item.material",
                "starter.item.name",
                "starter.item.lore"
        ));

        attributesFile = new File(getDataFolder(), "attributes.yml");
        attributesConfig = resourceConfigManager.loadAuxiliaryConfig("attributes.yml", attributesFile, List.of(
                "lore-stats",
                "lore-stats.sources",
                "lore-stats.attributes",
                "lore-stats.attributes.attack_damage",
                "lore-stats.attributes.crit_rate"
        ));

        sidebarFile = new File(getDataFolder(), "sidebar.yml");
        sidebarConfig = resourceConfigManager.loadAuxiliaryConfig("sidebar.yml", sidebarFile, List.of(
                "sidebar",
                "sidebar.layouts",
                "sidebar.layouts.default",
                "sidebar.layouts.default.lines"
        ));

        characterGuiFile = new File(getDataFolder(), "character-gui.yml");
        characterGuiConfig = resourceConfigManager.loadAuxiliaryConfig("character-gui.yml", characterGuiFile, List.of(
                "character-gui",
                "character-gui.title",
                "character-gui.size",
                "character-gui.layout.character-slots",
                "character-gui.layout.indicator-slots",
                "character-gui.items.frame.material",
                "character-gui.items.help.material",
                "character-gui.items.indicator.selected.material",
                "character-gui.messages.offline-managed-player"
        ));

        modKeybindFile = new File(getDataFolder(), "mod-keybind.yml");
        modKeybindConfig = resourceConfigManager.loadAuxiliaryConfig("mod-keybind.yml", modKeybindFile, List.of(
                "mod_keybind_bridge",
                "mod_keybind_bridge.enabled",
                "mod_keybind_bridge.default-cooldown-ms",
                "mod_keybind_bridge.bindings",
                "mod_keybind_bridge.bindings.slot_1",
                "mod_keybind_bridge.bindings.slot_1.enabled",
                "mod_keybind_bridge.bindings.slot_1.key",
                "mod_keybind_bridge.bindings.slot_1.command",
                "mod_keybind_bridge.bindings.slot_1.activation-mode",
                "mod_keybind_bridge.bindings.elemental_skill",
                "mod_keybind_bridge.bindings.elemental_skill.enabled",
                "mod_keybind_bridge.bindings.elemental_skill.key",
                "mod_keybind_bridge.bindings.elemental_skill.command",
                "mod_keybind_bridge.bindings.elemental_skill.activation-mode",
                "mod_keybind_bridge.bindings.elemental_skill.cooldown-ms"
        ));
    }

    public void reloadAllConfigs() {
        warnedKeys.clear();
        loadAllConfigs();
        warnDeprecatedConfigKeys();

        if (configParser != null) {
            configParser.reload();
        }
        if (damageCalculator != null) {
            damageCalculator.clearCache();
        }
        if (sidebarDisplayManager != null) {
            sidebarDisplayManager.reload();
        }
        if (mobResistanceManager != null) {
            mobResistanceManager.reloadConfigs();
        }
        if (weaponManager != null) {
            weaponManager.reloadDefinitions();
        }
        if (characterManager != null) {
            characterManager.reloadDefinitions();
        }
        getServer().getOnlinePlayers().forEach(this::refreshPlayerStats);
        if (modKeybindBridge != null) {
            modKeybindBridge.broadcastConfig();
            modKeybindBridge.broadcastStates();
        }
    }

    private void registerPlaceholderExpansion() {
        if (!hasPlaceholderAPI || placeholderResolver == null) {
            return;
        }
        try {
            YuanshenPlaceholderExpansion expansion = new YuanshenPlaceholderExpansion(this, placeholderResolver);
            if (!expansion.register()) {
                warnOnce("dependency:papi-expansion-register", "Failed to register Yuanshen PlaceholderAPI expansion.");
                return;
            }
            placeholderExpansion = expansion;
        } catch (Throwable throwable) {
            warnOnce("dependency:papi-expansion-error", "Failed to initialize Yuanshen PlaceholderAPI expansion: " + throwable.getMessage());
        }
    }

    public FileConfiguration getSkillsConfig() {
        return skillsConfig;
    }

    public FileConfiguration getAttributesConfig() {
        return attributesConfig;
    }

    public FileConfiguration getStarterCharactersConfig() {
        return starterCharactersConfig;
    }

    public FileConfiguration getSidebarConfig() {
        return sidebarConfig;
    }

    public FileConfiguration getCharacterGuiConfig() {
        return characterGuiConfig;
    }

    public FileConfiguration getModKeybindConfig() {
        return modKeybindConfig;
    }

    public String getMainConfigVersion() {
        return getConfig().getString("config-version", "legacy");
    }

    public String getSkillsConfigVersion() {
        return skillsConfig != null ? skillsConfig.getString("config-version", "legacy") : "legacy";
    }

    public String getAttributesConfigVersion() {
        return attributesConfig != null ? attributesConfig.getString("config-version", "legacy") : "legacy";
    }

    public String getSidebarConfigVersion() {
        return sidebarConfig != null ? sidebarConfig.getString("config-version", "legacy") : "legacy";
    }

    public boolean isDebugEnabled() {
        return getConfig().getBoolean("logging.debug", false);
    }

    public boolean shouldLogCacheInvalidation() {
        return getConfig().getBoolean("logging.cache-invalidation", false) || isDebugEnabled();
    }

    public boolean shouldLogCombatSummary() {
        return getConfig().getBoolean("logging.combat-summary", false) || isDebugEnabled();
    }

    public boolean shouldLogReactionDebug() {
        return getConfig().getBoolean("logging.reaction-debug", false) || isDebugEnabled();
    }

    public boolean shouldLogLoreDebug() {
        return getConfig().getBoolean("logging.lore-debug", false) || isDebugEnabled();
    }

    public boolean shouldLogDependencyFallbacks() {
        return getConfig().getBoolean("logging.dependency-fallbacks", true);
    }

    public boolean shouldWarnMissingConfig() {
        return getConfig().getBoolean("logging.missing-config-warnings", true);
    }

    public boolean isAttributeOverrideEnabled() {
        return getConfig().getBoolean("attribute_override.enabled", false);
    }

    public boolean isAttributeOverrideOnlyActiveCharacterMode() {
        return getConfig().getBoolean("attribute_override.only-active-character-mode", true);
    }

    public boolean isAttributeOverrideMaxHealthEnabled() {
        return getConfig().getBoolean("attribute_override.max_health", true);
    }

    public boolean isAttributeOverrideAttackDamageEnabled() {
        return getConfig().getBoolean("attribute_override.attack_damage", true);
    }

    public boolean isAttributeOverrideDefenseToArmorEnabled() {
        return getConfig().getBoolean("attribute_override.defense_to_armor", false);
    }

    public boolean shouldApplyAttributeOverride(Player player) {
        if (player == null || !isAttributeOverrideEnabled()) {
            return false;
        }
        return !isAttributeOverrideOnlyActiveCharacterMode() || isCharacterModeActive(player);
    }

    private void warnDeprecatedConfigKeys() {
        warnDeprecatedConfigKey(
                "skill_trigger.allow_empty_hand",
                "配置项 skill_trigger.allow_empty_hand 已废弃；当前版本技能触发不再依赖空手判定。"
        );
        warnDeprecatedConfigKey(
                "skill_trigger.weapon_lore_keyword",
                "配置项 skill_trigger.weapon_lore_keyword 已废弃；当前版本物品识别已改为内部标识，不再依赖 Lore 关键字。"
        );
    }

    private void warnDeprecatedConfigKey(String path, String message) {
        if (getConfig().contains(path)) {
            warnOnce("deprecated-config:" + path, message);
        }
    }

    public void warnOnce(String key, String message) {
        if (warnedKeys.add(key)) {
            getLogger().warning(message);
        }
    }

    public void infoOnce(String key, String message) {
        if (warnedKeys.add(key)) {
            getLogger().info(message);
        }
    }

    public boolean hasPlaceholderAPI() {
        return hasPlaceholderAPI;
    }

    public boolean hasMythicMobs() {
        return hasMythicMobs;
    }

    public ConfigParser getConfigParser() {
        return configParser;
    }

    public ElementDamageCalculator getDamageCalculator() {
        return damageCalculator;
    }

    public ElementReactionManager getReactionManager() {
        return reactionManager;
    }

    public ElementUtils getElementUtils() {
        return utils;
    }

    public HuTaoStateManager getHuTaoStateManager() {
        return huTaoStateManager;
    }

    public HuTaoBloodBlossomManager getHuTaoBloodBlossomManager() {
        return huTaoBloodBlossomManager;
    }

    public HuTaoSkillHandler getHuTaoSkillHandler() {
        return huTaoSkillHandler;
    }

    public XianglingGuobaManager getXianglingGuobaManager() {
        return xianglingGuobaManager;
    }

    public XianglingPepperManager getXianglingPepperManager() {
        return xianglingPepperManager;
    }

    public XianglingPyronadoManager getXianglingPyronadoManager() {
        return xianglingPyronadoManager;
    }

    public XianglingSkillHandler getXianglingSkillHandler() {
        return xianglingSkillHandler;
    }

    public DilucSkillHandler getDilucSkillHandler() {
        return dilucSkillHandler;
    }

    public KeqingSkillHandler getKeqingSkillHandler() {
        return keqingSkillHandler;
    }

    public NingguangSkillHandler getNingguangSkillHandler() {
        return ningguangSkillHandler;
    }

    public TravelerAnemoStateManager getTravelerAnemoStateManager() {
        return travelerAnemoStateManager;
    }

    public TravelerAnemoSkillHandler getTravelerAnemoSkillHandler() {
        return travelerAnemoSkillHandler;
    }

    public YelanSkillHandler getYelanSkillHandler() {
        return yelanSkillHandler;
    }

    public LoreStatParser getLoreStatParser() {
        return loreStatParser;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }

    public MobResistanceManager getMobResistanceManager() {
        return mobResistanceManager;
    }

    public CharacterResolver getCharacterResolver() {
        return characterResolver;
    }

    public WeaponManager getWeaponManager() {
        return weaponManager;
    }

    public CharacterManager getCharacterManager() {
        return characterManager;
    }

    public AdminGuiManager getAdminGuiManager() {
        return adminGuiManager;
    }

    public ConstellationManager getConstellationManager() {
        return constellationManager;
    }

    public ConstellationGuiManager getConstellationGuiManager() {
        return constellationGuiManager;
    }

    public MythicMobsBridge getMythicMobsBridge() {
        return mythicMobsBridge;
    }

    public EnergyManager getEnergyManager() {
        return energyManager;
    }

    public YuanshenItemIdentity getItemIdentity() {
        return itemIdentity;
    }

    public CharacterSkillConfig getCharacterConfig(CharacterType characterType) {
        return new CharacterSkillConfig(this, characterType);
    }

    public CharacterSkillEngine getCharacterSkillEngine() {
        return characterSkillEngine;
    }

    public CharacterStateRegistry getCharacterStateRegistry() {
        return characterStateRegistry;
    }

    public CharacterSlotManager getCharacterSlotManager() {
        return characterSlotManager;
    }

    public void suppressChargedAttack(Player player, long durationMs) {
        if (player == null || durationMs <= 0L) {
            return;
        }
        chargedAttackSuppressions.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
    }

    public boolean isChargedAttackSuppressed(Player player) {
        if (player == null) {
            return false;
        }
        Long until = chargedAttackSuppressions.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            chargedAttackSuppressions.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void suppressNormalAttack(Player player, long durationMs) {
        if (player == null || durationMs <= 0L) {
            return;
        }
        normalAttackSuppressions.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
    }

    public boolean isNormalAttackSuppressed(Player player) {
        if (player == null) {
            return false;
        }
        Long until = normalAttackSuppressions.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            normalAttackSuppressions.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean isDamageDisplayEnabled(Player player) {
        if (player == null) {
            return true;
        }
        return damageDisplaySettings.getOrDefault(player.getUniqueId(), true);
    }

    public void setDamageDisplayEnabled(Player player, boolean enabled) {
        if (player == null) {
            return;
        }
        damageDisplaySettings.put(player.getUniqueId(), enabled);
    }

    public boolean isSidebarEnabled(Player player) {
        if (player == null) {
            return isSidebarDefaultEnabled();
        }
        return sidebarDisplaySettings.getOrDefault(
                player.getUniqueId(),
                isSidebarDefaultEnabled()
        );
    }

    public void setSidebarEnabled(Player player, boolean enabled) {
        if (player == null) {
            return;
        }
        sidebarDisplaySettings.put(player.getUniqueId(), enabled);
        if (sidebarDisplayManager != null) {
            sidebarDisplayManager.refreshPlayer(player);
        }
    }

    public String getActiveElement(Player player) {
        if (player == null) {
            return null;
        }
        return activeElements.get(player.getUniqueId());
    }

    public void setActiveElement(Player player, String elementKey) {
        if (player == null) {
            return;
        }
        if (elementKey == null || elementKey.isBlank()) {
            activeElements.remove(player.getUniqueId());
            if (sidebarDisplayManager != null) {
                sidebarDisplayManager.refreshPlayer(player);
            }
            return;
        }
        activeElements.put(player.getUniqueId(), elementKey);
        if (sidebarDisplayManager != null) {
            sidebarDisplayManager.refreshPlayer(player);
        }
    }

    public void clearActiveElement(Player player) {
        if (player == null) {
            return;
        }
        activeElements.remove(player.getUniqueId());
        if (sidebarDisplayManager != null) {
            sidebarDisplayManager.refreshPlayer(player);
        }
    }

    public boolean hasActiveCharacter(Player player) {
        return characterResolver != null && characterResolver.resolveCharacter(player) != null;
    }

    public boolean isCharacterModeActive(Player player) {
        return player != null
                && hasActiveCharacter(player)
                && weaponManager != null
                && weaponManager.canUseSelectedWeapon(player);
    }

    public void clearPlayerRuntimeState(Player player) {
        if (player == null) {
            return;
        }
        restorePlayerAttributes(player);
        damageDisplaySettings.remove(player.getUniqueId());
        sidebarDisplaySettings.remove(player.getUniqueId());
        chargedAttackSuppressions.remove(player.getUniqueId());
        normalAttackSuppressions.remove(player.getUniqueId());
        activeElements.remove(player.getUniqueId());
        if (energyManager != null) {
            energyManager.clearPlayer(player);
        }
    }

    public double getActualMaxHealth(Player player) {
        if (player == null) {
            return 20.0;
        }
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = attribute != null ? attribute.getValue() : player.getMaxHealth();
        if (!Double.isFinite(maxHealth) || maxHealth <= 0.0) {
            return 20.0;
        }
        return maxHealth;
    }

    public double getVanillaMaxHealthBaseline(Player player) {
        if (player == null) {
            return 20.0;
        }
        AttributeOverrideState state = attributeOverrideStates.get(player.getUniqueId());
        if (state != null
                && state.originalMaxHealthBaseline != null
                && Double.isFinite(state.originalMaxHealthBaseline)
                && state.originalMaxHealthBaseline > 0.0) {
            return state.originalMaxHealthBaseline;
        }
        return getActualMaxHealth(player);
    }

    public double setPlayerHealthSafely(Player player, double desiredHealth, boolean keepAlive) {
        if (player == null) {
            return 0.0;
        }
        double maxHealth = getActualMaxHealth(player);
        double minHealth = keepAlive ? Math.min(1.0, maxHealth) : 0.0;
        double fallback = Math.max(minHealth, Math.min(maxHealth, player.getHealth()));
        double safeHealth = Double.isFinite(desiredHealth)
                ? Math.max(minHealth, Math.min(maxHealth, desiredHealth))
                : fallback;
        player.setHealth(safeHealth);
        return safeHealth;
    }

    public ElementAura getElementAura(LivingEntity entity) {
        return new ElementAura(entity, this);
    }

    public DamageResult calculateElementDamage(Player attacker, LivingEntity target, String element) {
        if (damageCalculator == null) {
            return null;
        }
        ItemStack handItem = attacker.getInventory().getItemInMainHand();
        return damageCalculator.calculateElementDamage(attacker, target, element, handItem);
    }

    public DamageResult calculateElementDamage(Player attacker, LivingEntity target, String element, ItemStack weapon) {
        if (damageCalculator == null) {
            return null;
        }
        return damageCalculator.calculateElementDamage(attacker, target, element, weapon);
    }

    public ReactionResult triggerElementReaction(Player attacker, LivingEntity target, String element, DamageResult damageResult) {
        if (reactionManager == null) {
            return null;
        }
        return reactionManager.handleReaction(attacker, target, element, damageResult);
    }

    public void addElementToEntity(LivingEntity entity, String element, int durationTicks) {
        if (utils != null) {
            utils.addElementWithExpire(entity, element, durationTicks);
        }
    }

    public void removeElementFromEntity(LivingEntity entity, String element) {
        if (utils != null) {
            utils.removeElement(entity, element);
        }
    }

    public boolean hasElement(LivingEntity entity, String element) {
        return utils != null && utils.hasElement(entity, element);
    }

    public PlayerStats getPlayerStats(Player player) {
        return damageCalculator != null ? damageCalculator.getPlayerStats(player) : null;
    }

    public double getGlobalDamageBonusMultiplier(Player player, String elementKey) {
        double bonus = 1.0;
        if (yelanSkillHandler != null) {
            bonus *= yelanSkillHandler.getBurstTeamDamageBonusMultiplier(player);
        }
        return bonus;
    }

    public void refreshPlayerStats(Player player) {
        if (player == null) {
            return;
        }
        PlayerStats stats = null;
        if (damageCalculator != null) {
            damageCalculator.invalidatePlayerStats(player);
            stats = damageCalculator.getPlayerStats(player);
        }
        if (shouldApplyAttributeOverride(player) && stats != null) {
            applyPlayerAttributeOverrides(player, stats);
        } else {
            restorePlayerAttributes(player);
        }
        if (sidebarDisplayManager != null) {
            sidebarDisplayManager.refreshPlayer(player);
        }
    }

    public void applyShieldToPlayer(Player player, double shieldValue) {
        if (reactionManager != null) {
            reactionManager.applyShield(player, shieldValue);
        }
    }

    public double onPlayerTakeDamage(Player player, double damage) {
        return reactionManager != null ? reactionManager.onPlayerDamage(player, damage) : damage;
    }

    public double applyMobResistance(LivingEntity target, double damage, String damageElement) {
        return mobResistanceManager != null
                ? mobResistanceManager.applyResistance(target, damage, damageElement)
                : damage;
    }

    public double parseConfigDouble(String path, Player player, double defaultValue) {
        return configParser != null ? configParser.parseDouble(path, player, defaultValue) : defaultValue;
    }

    public int parseConfigInt(String path, Player player, int defaultValue) {
        return configParser != null ? configParser.parseInt(path, player, defaultValue) : defaultValue;
    }

    public String detectElement(ItemStack item) {
        return null;
    }

    public String formatDamage(double damage) {
        return ElementUtils.formatDamage(damage);
    }

    public void handleSelectedCharacterChange(Player player, CharacterType previous, CharacterType current) {
        if (player == null) {
            return;
        }
        if (weaponManager != null) {
            weaponManager.handleSelectedCharacterChange(player, previous, current);
        }
        if (huTaoStateManager != null) {
            huTaoStateManager.handleCharacterSwitch(player, previous, current);
        }
        if (characterManager != null) {
            characterManager.resetNormalAttackCombo(player);
        }
        refreshPlayerStats(player);
    }

    public YuanshenPlaceholderResolver getPlaceholderResolver() {
        return placeholderResolver;
    }

    public SidebarDisplayManager getSidebarDisplayManager() {
        return sidebarDisplayManager;
    }

    public boolean isSidebarDefaultEnabled() {
        return sidebarConfig != null && sidebarConfig.getBoolean("sidebar.default-enabled", false);
    }

    public void restorePlayerAttributes(Player player) {
        if (player == null) {
            return;
        }
        AttributeOverrideState state = attributeOverrideStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        restoreMaxHealthOverride(player, state);
        restoreAttributeBase(player, Attribute.ATTACK_DAMAGE, state.originalAttackDamageBase);
        state.originalAttackDamageBase = null;
        restoreAttributeBase(player, Attribute.ARMOR, state.originalArmorBase);
        state.originalArmorBase = null;
        if (state.isEmpty()) {
            attributeOverrideStates.remove(player.getUniqueId());
        }
    }

    private void applyPlayerAttributeOverrides(Player player, PlayerStats stats) {
        AttributeOverrideState state = attributeOverrideStates.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new AttributeOverrideState()
        );

        if (isAttributeOverrideMaxHealthEnabled()) {
            applyMaxHealthOverride(player, stats.getHealth(), state);
        } else {
            restoreMaxHealthOverride(player, state);
        }

        if (isAttributeOverrideAttackDamageEnabled()) {
            applyAttributeBase(player, Attribute.ATTACK_DAMAGE, stats.getAttackDamage(),
                    0.0, 2048.0, state, true);
        } else {
            restoreAttributeBase(player, Attribute.ATTACK_DAMAGE, state.originalAttackDamageBase);
            state.originalAttackDamageBase = null;
        }

        if (isAttributeOverrideDefenseToArmorEnabled()) {
            double armorScale = Math.max(0.0, getConfig().getDouble("attribute_override.armor-scale", 0.05));
            double armorMax = getConfig().getDouble("attribute_override.armor-max", 30.0);
            double targetArmor = stats.getDefense() * armorScale;
            if (Double.isFinite(armorMax) && armorMax > 0.0) {
                targetArmor = Math.min(targetArmor, armorMax);
            }
            applyAttributeBase(player, Attribute.ARMOR, targetArmor,
                    0.0, 1024.0, state, false);
        } else {
            restoreAttributeBase(player, Attribute.ARMOR, state.originalArmorBase);
            state.originalArmorBase = null;
        }

        if (state.isEmpty()) {
            attributeOverrideStates.remove(player.getUniqueId());
        }
    }

    private void applyMaxHealthOverride(Player player, double targetMaxHealth, AttributeOverrideState state) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            state.originalMaxHealthBase = null;
            state.originalMaxHealthBaseline = null;
            return;
        }

        if (state.originalMaxHealthBase == null) {
            state.originalMaxHealthBase = attribute.getBaseValue();
        }
        if (state.originalMaxHealthBaseline == null) {
            state.originalMaxHealthBaseline = getActualMaxHealth(player);
        }

        double previousMaxHealth = getActualMaxHealth(player);
        double currentHealth = player.getHealth();
        double safeTarget = sanitizeAttributeValue(targetMaxHealth, previousMaxHealth, 1.0, 2048.0);
        if (Math.abs(attribute.getBaseValue() - safeTarget) > 1.0E-6) {
            attribute.setBaseValue(safeTarget);
        }
        syncCurrentHealthRatio(player, currentHealth, previousMaxHealth);
    }

    private void restoreMaxHealthOverride(Player player, AttributeOverrideState state) {
        if (state.originalMaxHealthBase == null) {
            state.originalMaxHealthBaseline = null;
            return;
        }
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) {
            double previousMaxHealth = getActualMaxHealth(player);
            double currentHealth = player.getHealth();
            double safeOriginal = sanitizeAttributeValue(state.originalMaxHealthBase, 20.0, 1.0, 2048.0);
            if (Math.abs(attribute.getBaseValue() - safeOriginal) > 1.0E-6) {
                attribute.setBaseValue(safeOriginal);
            }
            syncCurrentHealthRatio(player, currentHealth, previousMaxHealth);
        }
        state.originalMaxHealthBase = null;
        state.originalMaxHealthBaseline = null;
    }

    private void applyAttributeBase(Player player, Attribute attributeType, double targetValue,
                                    double minValue, double maxValue,
                                    AttributeOverrideState state, boolean attackDamage) {
        AttributeInstance attribute = player.getAttribute(attributeType);
        if (attribute == null) {
            if (attackDamage) {
                state.originalAttackDamageBase = null;
            } else {
                state.originalArmorBase = null;
            }
            return;
        }

        if (attackDamage) {
            if (state.originalAttackDamageBase == null) {
                state.originalAttackDamageBase = attribute.getBaseValue();
            }
        } else if (state.originalArmorBase == null) {
            state.originalArmorBase = attribute.getBaseValue();
        }

        double safeTarget = sanitizeAttributeValue(targetValue, attribute.getBaseValue(), minValue, maxValue);
        if (Math.abs(attribute.getBaseValue() - safeTarget) > 1.0E-6) {
            attribute.setBaseValue(safeTarget);
        }
    }

    private void restoreAttributeBase(Player player, Attribute attributeType, Double originalBaseValue) {
        if (player == null || originalBaseValue == null) {
            return;
        }
        AttributeInstance attribute = player.getAttribute(attributeType);
        if (attribute == null) {
            return;
        }
        double minValue = attributeType == Attribute.ATTACK_DAMAGE || attributeType == Attribute.ARMOR ? 0.0 : 1.0;
        double maxValue = attributeType == Attribute.ARMOR ? 1024.0 : 2048.0;
        double safeOriginal = sanitizeAttributeValue(originalBaseValue, attribute.getBaseValue(), minValue, maxValue);
        if (Math.abs(attribute.getBaseValue() - safeOriginal) > 1.0E-6) {
            attribute.setBaseValue(safeOriginal);
        }
    }

    private void syncCurrentHealthRatio(Player player, double currentHealth, double previousMaxHealth) {
        if (player == null || player.isDead()) {
            return;
        }
        double currentMaxHealth = getActualMaxHealth(player);
        double safePreviousMax = Double.isFinite(previousMaxHealth) && previousMaxHealth > 0.0 ? previousMaxHealth : currentMaxHealth;
        double safeCurrentHealth = Double.isFinite(currentHealth) ? currentHealth : player.getHealth();
        double ratio = safePreviousMax > 0.0 ? safeCurrentHealth / safePreviousMax : 1.0;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        setPlayerHealthSafely(player, currentMaxHealth * ratio, true);
    }

    private double sanitizeAttributeValue(double value, double fallback, double minValue, double maxValue) {
        double safeValue = Double.isFinite(value) ? value : fallback;
        if (!Double.isFinite(safeValue)) {
            safeValue = minValue;
        }
        safeValue = Math.max(minValue, safeValue);
        if (Double.isFinite(maxValue) && maxValue > minValue) {
            safeValue = Math.min(maxValue, safeValue);
        }
        return safeValue;
    }
}
