package org.yuanshen.yuanshen;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class CharacterResolver {

    private final Yuanshen plugin;

    public CharacterResolver(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public CharacterType resolveCharacter(Player player) {
        if (player == null) {
            return null;
        }
        int selectedSlot = plugin.getCharacterSlotManager().getSelectedSlot(player);
        return resolveCharacter(plugin.getCharacterSlotManager().getSlotItem(player, selectedSlot));
    }

    public CharacterType resolveCharacter(ItemStack item) {
        CharacterInstance instance = plugin.getCharacterManager() == null ? null
                : plugin.getCharacterManager().resolveCharacterInstance(item);
        return instance == null ? null : instance.definition().characterType();
    }

    public boolean isCharacterWeapon(ItemStack item, CharacterType type) {
        return type != null && type == resolveCharacter(item);
    }

    public boolean isSelectedCharacter(Player player, CharacterType type) {
        return player != null && type != null && type == resolveCharacter(player);
    }
}
