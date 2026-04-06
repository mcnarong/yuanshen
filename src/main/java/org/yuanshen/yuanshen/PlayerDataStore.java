package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerDataStore {

    private static final long FLUSH_DELAY_TICKS = 20L;

    private final Yuanshen plugin;
    private final File dataFile;
    private final FileConfiguration dataConfig;
    private final Object flushLock = new Object();
    private final AtomicInteger latestFlushId = new AtomicInteger();
    private BukkitTask pendingFlushTask;

    public PlayerDataStore(Yuanshen plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.dataFile = new File(plugin.getDataFolder(), "playerdata.yml");
        this.dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void loadPlayer(Player player) {
        if (player == null) {
            return;
        }

        String path = basePath(player);
        plugin.clearPlayerRuntimeState(player);
        plugin.getCharacterSlotManager().clearPlayer(player);

        PlayerSkillData skillData = PlayerSkillData.get(player.getUniqueId());
        skillData.reset();
        loadSkillState(path, skillData, CharacterType.HUTAO, true);
        loadSkillState(path, skillData, CharacterType.XIANGLING, false);
        loadSkillState(path, skillData, CharacterType.DILUC, false);
        loadSkillState(path, skillData, CharacterType.KEQING, false);
        loadSkillState(path, skillData, CharacterType.NINGGUANG, false);
        loadSkillState(path, skillData, CharacterType.TRAVELER_ANEMO, false);
        loadSkillState(path, skillData, CharacterType.YELAN, false);

        plugin.setDamageDisplayEnabled(player, dataConfig.getBoolean(path + ".settings.damage-display", true));
        plugin.setActiveElement(player, dataConfig.getString(path + ".settings.active-element", null));

        ItemStack[] slots = new ItemStack[CharacterSlotManager.MAX_SLOTS];
        for (int i = 0; i < CharacterSlotManager.MAX_SLOTS; i++) {
            slots[i] = dataConfig.getItemStack(path + ".characters.slots." + (i + 1));
        }
        int selectedSlot = dataConfig.getInt(path + ".characters.selected-slot", 1);
        if (shouldAssignDefaultTraveler(path, slots)) {
            int starterSlot = resolveStarterSlot();
            slots[starterSlot - 1] = createConfiguredStarterItem();
            selectedSlot = starterSlot;
        }
        plugin.getCharacterSlotManager().loadPlayer(player, slots, selectedSlot);

        plugin.getHuTaoStateManager().clear(player);
        plugin.getHuTaoSkillHandler().clearRuntimeState(player);
        plugin.getXianglingGuobaManager().clearPlayer(player);
        plugin.getXianglingPyronadoManager().clearPlayer(player);
        plugin.getXianglingPepperManager().clear(player);
        plugin.getDilucSkillHandler().clearRuntimeState(player);
        plugin.getKeqingSkillHandler().clearRuntimeState(player);
        plugin.getNingguangSkillHandler().clearRuntimeState(player);
        plugin.getTravelerAnemoSkillHandler().clearRuntimeState(player);
        plugin.getYelanSkillHandler().clearRuntimeState(player);
        plugin.getWeaponManager().clearRuntimeState(player);
        plugin.getCharacterManager().clearRuntimeState(player);
        plugin.getWeaponManager().loadRememberedWeapons(
                player,
                dataConfig.getConfigurationSection(path + ".characters.weapon-memory")
        );

        long hutaoStateEnd = dataConfig.getLong(path + ".hutao.state-end", 0L);
        double hutaoBonusDamage = dataConfig.getDouble(path + ".hutao.bonus-damage", 0.0);
        if (hutaoStateEnd > System.currentTimeMillis()) {
            plugin.getHuTaoStateManager().restore(player, hutaoBonusDamage, hutaoStateEnd);
        }

        long hutaoSupportBuffEnd = dataConfig.getLong(path + ".hutao.support-buff-end", 0L);
        if (hutaoSupportBuffEnd > System.currentTimeMillis()) {
            plugin.getHuTaoStateManager().restoreSupportBuff(player, hutaoSupportBuffEnd);
        }

        long chargedCooldownEnd = dataConfig.getLong(path + ".hutao.charged-cooldown-end", 0L);
        if (chargedCooldownEnd > System.currentTimeMillis()) {
            plugin.getHuTaoSkillHandler().restoreChargedCooldown(player, chargedCooldownEnd);
        }

        long xianglingPepperBuffEnd = dataConfig.getLong(path + ".xiangling.pepper-buff-end", 0L);
        if (xianglingPepperBuffEnd > System.currentTimeMillis()) {
            plugin.getXianglingPepperManager().restoreBuff(player, xianglingPepperBuffEnd);
        }

        plugin.refreshPlayerStats(player);
        plugin.setSidebarEnabled(
                player,
                dataConfig.getBoolean(
                        path + ".settings.sidebar-enabled",
                        plugin.isSidebarDefaultEnabled()
                )
        );
    }

    public void savePlayer(Player player) {
        if (player == null) {
            return;
        }
        persistPlayer(player);
        scheduleFlush();
    }

    private void persistPlayer(Player player) {
        String path = basePath(player);
        PlayerSkillData skillData = PlayerSkillData.get(player.getUniqueId());

        dataConfig.set(path + ".name", player.getName());
        saveSkillState(path, skillData, CharacterType.HUTAO, true);
        saveSkillState(path, skillData, CharacterType.XIANGLING, false);
        saveSkillState(path, skillData, CharacterType.DILUC, false);
        saveSkillState(path, skillData, CharacterType.KEQING, false);
        saveSkillState(path, skillData, CharacterType.NINGGUANG, false);
        saveSkillState(path, skillData, CharacterType.TRAVELER_ANEMO, false);
        saveSkillState(path, skillData, CharacterType.YELAN, false);
        dataConfig.set(path + ".settings.damage-display", plugin.isDamageDisplayEnabled(player));
        dataConfig.set(path + ".settings.sidebar-enabled", plugin.isSidebarEnabled(player));
        dataConfig.set(path + ".settings.active-element", plugin.getActiveElement(player));

        dataConfig.set(path + ".characters.selected-slot", plugin.getCharacterSlotManager().getSelectedSlot(player));
        ItemStack[] slots = plugin.getCharacterSlotManager().getSlotItems(player);
        for (int i = 0; i < CharacterSlotManager.MAX_SLOTS; i++) {
            dataConfig.set(path + ".characters.slots." + (i + 1), slots[i]);
        }
        dataConfig.set(path + ".characters.weapons", null);
        plugin.getWeaponManager().saveRememberedWeapons(player, dataConfig, path + ".characters.weapon-memory");

        dataConfig.set(path + ".hutao.state-end", plugin.getHuTaoStateManager().getStateEndMillis(player));
        dataConfig.set(path + ".hutao.bonus-damage", plugin.getHuTaoStateManager().getBonusDamage(player));
        dataConfig.set(path + ".hutao.support-buff-end", plugin.getHuTaoStateManager().getSupportBuffEndMillis(player));
        dataConfig.set(path + ".hutao.charged-cooldown-end", plugin.getHuTaoSkillHandler().getChargedCooldownEnd(player));
        dataConfig.set(path + ".xiangling.pepper-buff-end", plugin.getXianglingPepperManager().getBuffEndMillis(player));
    }

    public void unloadPlayer(Player player) {
        if (player == null) {
            return;
        }

        plugin.getHuTaoStateManager().clear(player);
        plugin.getHuTaoSkillHandler().clearRuntimeState(player);
        plugin.getXianglingGuobaManager().clearPlayer(player);
        plugin.getXianglingPyronadoManager().clearPlayer(player);
        plugin.getXianglingPepperManager().clear(player);
        plugin.getDilucSkillHandler().clearRuntimeState(player);
        plugin.getKeqingSkillHandler().clearRuntimeState(player);
        plugin.getNingguangSkillHandler().clearRuntimeState(player);
        plugin.getTravelerAnemoSkillHandler().clearRuntimeState(player);
        plugin.getYelanSkillHandler().clearRuntimeState(player);
        plugin.getWeaponManager().clearRuntimeState(player);
        plugin.getCharacterManager().clearRuntimeState(player);
        plugin.getSidebarDisplayManager().detachPlayer(player);
        plugin.getCharacterSlotManager().clearPlayer(player);
        plugin.clearPlayerRuntimeState(player);
        PlayerSkillData.unload(player.getUniqueId());
    }

    public void saveOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            persistPlayer(player);
        }
        scheduleFlush();
    }

    public void shutdown() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            persistPlayer(player);
        }
        cancelPendingFlush();
        flushNow();
    }

    private String basePath(Player player) {
        return "players." + player.getUniqueId();
    }

    private boolean shouldAssignDefaultTraveler(String path, ItemStack[] slots) {
        if (!isStarterCharacterEnabled()) {
            return false;
        }
        if (dataConfig.contains(path + ".characters") || dataConfig.contains(path + ".name")) {
            return false;
        }
        if (slots == null || slots.length == 0) {
            return true;
        }
        for (ItemStack slot : slots) {
            if (slot != null && !slot.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private boolean isStarterCharacterEnabled() {
        FileConfiguration starterConfig = plugin.getStarterCharactersConfig();
        if (starterConfig == null) {
            return true;
        }
        if (starterConfig.contains("starter.assign-on-first-join")) {
            return starterConfig.getBoolean("starter.assign-on-first-join", true);
        }
        return starterConfig.getBoolean("starter.enabled", true);
    }

    private int resolveStarterSlot() {
        int configured = plugin.getStarterCharactersConfig().getInt("starter.slot", 1);
        if (configured < 1 || configured > CharacterSlotManager.MAX_SLOTS) {
            return 1;
        }
        return configured;
    }

    private ItemStack createConfiguredStarterItem() {
        String characterId = plugin.getStarterCharactersConfig().getString("starter.character-id", "");
        if (plugin.getCharacterManager() != null && characterId != null && !characterId.isBlank()) {
            ItemStack starter = plugin.getCharacterManager().createCharacterItem(characterId);
            if (starter != null) {
                return starter;
            }
        }

        String materialName = plugin.getStarterCharactersConfig().getString("starter.item.material", "NETHER_STAR");
        Material material = Material.matchMaterial(materialName == null ? "" : materialName);
        if (material == null) {
            material = Material.NETHER_STAR;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(plugin.getStarterCharactersConfig().getString("starter.item.name", "&a旅行者·风")));
            meta.setLore(colorList(plugin.getStarterCharactersConfig().getStringList("starter.item.lore")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private List<String> colorList(List<String> lines) {
        return lines.stream().map(this::color).toList();
    }

    private void loadSkillState(String path, PlayerSkillData skillData, CharacterType characterType, boolean allowLegacyFallback) {
        String skillPath = path + ".skill." + characterType.getId();
        if (allowLegacyFallback && !dataConfig.contains(skillPath)) {
            skillData.setEnergy(characterType, dataConfig.getDouble(path + ".skill.energy", 0.0));
            skillData.setLastE(characterType, dataConfig.getLong(path + ".skill.last-e", 0L));
            skillData.setLastQ(characterType, dataConfig.getLong(path + ".skill.last-q", 0L));
            return;
        }

        skillData.setEnergy(characterType, dataConfig.getDouble(skillPath + ".energy", 0.0));
        skillData.setLastE(characterType, dataConfig.getLong(skillPath + ".last-e", 0L));
        skillData.setLastQ(characterType, dataConfig.getLong(skillPath + ".last-q", 0L));
    }

    private void saveSkillState(String path, PlayerSkillData skillData, CharacterType characterType, boolean writeLegacyMirror) {
        String skillPath = path + ".skill." + characterType.getId();
        dataConfig.set(skillPath + ".energy", skillData.getEnergyExact(characterType));
        dataConfig.set(skillPath + ".last-e", skillData.getLastE(characterType));
        dataConfig.set(skillPath + ".last-q", skillData.getLastQ(characterType));

        if (writeLegacyMirror) {
            dataConfig.set(path + ".skill.energy", skillData.getEnergyExact(characterType));
            dataConfig.set(path + ".skill.last-e", skillData.getLastE(characterType));
            dataConfig.set(path + ".skill.last-q", skillData.getLastQ(characterType));
        }
    }

    private void scheduleFlush() {
        if (!plugin.isEnabled()) {
            flushNow();
            return;
        }
        if (pendingFlushTask != null) {
            return;
        }
        pendingFlushTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingFlushTask = null;
            flushAsync();
        }, FLUSH_DELAY_TICKS);
    }

    private void flushAsync() {
        String snapshot = serializeConfig();
        if (snapshot == null) {
            return;
        }
        int flushId = latestFlushId.incrementAndGet();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> writeSnapshot(snapshot, flushId));
    }

    private void flushNow() {
        String snapshot = serializeConfig();
        if (snapshot == null) {
            return;
        }
        int flushId = latestFlushId.incrementAndGet();
        writeSnapshot(snapshot, flushId);
    }

    private String serializeConfig() {
        try {
            return dataConfig.saveToString();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to serialize playerdata.yml: " + throwable.getMessage());
            return null;
        }
    }

    private void writeSnapshot(String snapshot, int flushId) {
        synchronized (flushLock) {
            if (flushId < latestFlushId.get()) {
                return;
            }
            try {
                Files.writeString(dataFile.toPath(), snapshot, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save playerdata.yml: " + e.getMessage());
            }
        }
    }

    private void cancelPendingFlush() {
        if (pendingFlushTask == null) {
            return;
        }
        pendingFlushTask.cancel();
        pendingFlushTask = null;
    }
}
