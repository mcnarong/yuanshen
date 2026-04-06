package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class CharacterGuiListener implements Listener {

    private final Yuanshen plugin;

    public CharacterGuiListener(Yuanshen plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getCharacterSlotManager().isCharacterGui(top)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player editor)) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < top.getSize()) {
            event.setCancelled(true);
            if (plugin.getCharacterSlotManager().isIndicatorGuiSlot(rawSlot)) {
                handleIndicatorSlotClick(editor, top, rawSlot);
                return;
            }
            if (!plugin.getCharacterSlotManager().isEditableGuiSlot(rawSlot)) {
                return;
            }
            handleTopSlotClick(event, editor, top, rawSlot);
            return;
        }

        if (event.isShiftClick() && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
            event.setCancelled(true);
            handleShiftMoveFromPlayerInventory(editor, top, event.getCurrentItem(), event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getCharacterSlotManager().isCharacterGui(top)) {
            return;
        }

        int topSize = top.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getCharacterSlotManager().isCharacterGui(top)) {
            return;
        }

        if (event.getPlayer() instanceof Player editor) {
            Player owner = resolveManagedPlayer(top, editor);
            if (owner == null) {
                return;
            }
            plugin.getCharacterSlotManager().syncFromInventory(owner, top);
            plugin.getPlayerDataStore().savePlayer(owner);
        }
    }

    private void handleTopSlotClick(InventoryClickEvent event, Player editor, Inventory top, int rawSlot) {
        if (event.getClick().isKeyboardClick()
                || event.getClick().isCreativeAction()
                || event.getClick().name().contains("DROP")
                || "DOUBLE_CLICK".equals(event.getClick().name())) {
            return;
        }

        Player managedPlayer = resolveManagedPlayer(top, editor);
        if (managedPlayer == null) {
            editor.closeInventory();
            sendGuiMessage(editor, "offline-managed-player",
                    "&c当前被管理的玩家已离线。");
            return;
        }

        ItemStack current = top.getItem(rawSlot);
        ItemStack cursor = event.getCursor();

        if (event.isShiftClick()) {
            moveStoredItemBackToPlayer(editor, managedPlayer, top, rawSlot, current);
            return;
        }

        if (plugin.getCharacterSlotManager().isCharacterGuiSlot(rawSlot)) {
            handleCharacterSlotClick(editor, managedPlayer, top, rawSlot, current, cursor);
        }
    }

    private void handleIndicatorSlotClick(Player editor, Inventory top, int rawSlot) {
        Player managedPlayer = resolveManagedPlayer(top, editor);
        if (managedPlayer == null) {
            editor.closeInventory();
            sendGuiMessage(editor, "offline-viewed-player",
                    "&c当前被查看的玩家已离线。");
            return;
        }

        int characterSlot = plugin.getCharacterSlotManager().toIndicatorSlot(rawSlot);
        if (characterSlot < 1) {
            return;
        }

        plugin.getCharacterSlotManager().setSelectedSlot(managedPlayer, characterSlot);
        plugin.getPlayerDataStore().savePlayer(managedPlayer);

        ItemStack characterItem = plugin.getCharacterSlotManager().getSlotItem(managedPlayer, characterSlot);
        CharacterInstance instance = plugin.getCharacterManager().resolveCharacterInstance(characterItem);
        if (instance == null) {
            plugin.getCharacterSlotManager().populateInventory(managedPlayer, top);
            sendGuiMessage(editor, "selected-empty-slot",
                    "&e已切换到槽位 &f{slot}&e，该槽位没有角色。",
                    "slot", String.valueOf(characterSlot));
            return;
        }

        if (!guiConfig().getBoolean("character-gui.behavior.open-constellation-on-select", true)) {
            plugin.getCharacterSlotManager().populateInventory(managedPlayer, top);
            sendGuiMessage(editor, "selected-slot",
                    "&a已切换到槽位 &f{slot}&a，当前角色为 &f{character}&a。",
                    "slot", String.valueOf(characterSlot),
                    "character", instance.definition().displayName());
            return;
        }

        plugin.getConstellationGuiManager().openInventory(editor, managedPlayer, characterSlot);
    }

    private void handleCharacterSlotClick(Player editor, Player managedPlayer, Inventory top,
                                          int rawSlot, ItemStack current, ItemStack cursor) {
        int characterSlot = plugin.getCharacterSlotManager().toCharacterSlot(rawSlot);

        if (cursor == null || cursor.getType().isAir()) {
            if (current == null || current.getType().isAir()) {
                return;
            }
            top.setItem(rawSlot, null);
            editor.setItemOnCursor(current.clone());
            plugin.getCharacterSlotManager().syncFromInventory(managedPlayer, top);
            return;
        }

        CharacterType type = plugin.getCharacterResolver().resolveCharacter(cursor);
        if (type == null) {
            if (plugin.getWeaponManager().resolveWeapon(cursor) != null) {
                sendGuiMessage(editor, "weapon-main-hand-hint",
                        "&e角色界面不再存武器。请把武器直接拿在主手使用。");
            } else {
                sendGuiMessage(editor, "only-character-items",
                        "&c这里现在只允许放角色物品。");
            }
            return;
        }

        top.setItem(rawSlot, cursor.clone());
        editor.setItemOnCursor(current == null || current.getType().isAir() ? null : current.clone());
        plugin.getCharacterSlotManager().syncFromInventory(managedPlayer, top);
        sendGuiMessage(editor, "character-added",
                "&a已将角色 &f{character}&a 放入槽位 &f{slot}&a。",
                "slot", String.valueOf(characterSlot),
                "character", type.getDisplayName());
    }

    private void handleShiftMoveFromPlayerInventory(Player editor, Inventory top, ItemStack currentItem, int playerInventorySlot) {
        Player managedPlayer = resolveManagedPlayer(top, editor);
        if (managedPlayer == null) {
            editor.closeInventory();
            sendGuiMessage(editor, "offline-managed-player",
                    "&c当前被管理的玩家已离线。");
            return;
        }

        CharacterType type = plugin.getCharacterResolver().resolveCharacter(currentItem);
        if (type == null) {
            if (plugin.getWeaponManager().resolveWeapon(currentItem) != null) {
                sendGuiMessage(editor, "weapon-main-hand-hint",
                        "&e角色界面不再存武器。请把武器直接拿在主手使用。");
            } else {
                sendGuiMessage(editor, "only-character-items",
                        "&c这里只能放角色物品。");
            }
            return;
        }

        int targetSlot = plugin.getCharacterSlotManager().findFirstEmptySlot(managedPlayer);
        if (targetSlot < 1) {
            sendGuiMessage(editor, "characters-full",
                    "&c角色槽已满，最多只能放 4 位角色。");
            return;
        }

        top.setItem(plugin.getCharacterSlotManager().toGuiSlot(targetSlot), currentItem.clone());
        editor.getInventory().setItem(playerInventorySlot, null);
        plugin.getCharacterSlotManager().syncFromInventory(managedPlayer, top);
        sendGuiMessage(editor, "character-added",
                "&a已将角色 &f{character}&a 放入槽位 &f{slot}&a。",
                "slot", String.valueOf(targetSlot),
                "character", type.getDisplayName());
    }

    private void moveStoredItemBackToPlayer(Player editor, Player managedPlayer, Inventory top, int rawSlot, ItemStack current) {
        if (current == null || current.getType().isAir()) {
            return;
        }

        Map<Integer, ItemStack> leftover = editor.getInventory().addItem(current.clone());
        if (!leftover.isEmpty()) {
            sendGuiMessage(editor, "backpack-full",
                    "&c背包空间不足，无法取出槽位中的物品。");
            return;
        }

        top.setItem(rawSlot, null);
        plugin.getCharacterSlotManager().syncFromInventory(managedPlayer, top);
    }

    private Player resolveManagedPlayer(Inventory top, Player editor) {
        if (!(top.getHolder() instanceof CharacterGuiHolder holder)) {
            return editor;
        }
        Player owner = Bukkit.getPlayer(holder.getOwnerId());
        if (owner != null) {
            return owner;
        }
        return editor.getUniqueId().equals(holder.getOwnerId()) ? editor : null;
    }

    private void sendGuiMessage(Player player, String key, String defaultMessage, String... replacements) {
        if (player == null) {
            return;
        }
        String text = guiConfig().getString("character-gui.messages." + key, defaultMessage);
        player.sendMessage(color(applyReplacements(text, replacements)));
    }

    private String applyReplacements(String text, String... replacements) {
        String value = text == null ? "" : text;
        if (replacements == null) {
            return value;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace("{" + replacements[i] + "}", replacements[i + 1] == null ? "" : replacements[i + 1]);
        }
        return value;
    }

    private FileConfiguration guiConfig() {
        FileConfiguration config = plugin.getCharacterGuiConfig();
        return config != null ? config : plugin.getConfig();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
