package org.yuanshen.yuanshen;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

public class CharacterSkillEngine {

    private final Yuanshen plugin;
    private final Map<CharacterType, CharacterSkillHandler> handlers = new EnumMap<>(CharacterType.class);

    public CharacterSkillEngine(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void register(CharacterSkillHandler handler) {
        if (handler == null) {
            return;
        }
        handlers.put(handler.getCharacterType(), handler);
    }

    public CharacterSkillHandler get(CharacterType type) {
        return type == null ? null : handlers.get(type);
    }

    public CharacterSkillHandler resolveHandler(Player player) {
        return resolveHandler(player, player == null ? null : player.getInventory().getItemInMainHand());
    }

    public CharacterSkillHandler resolveHandler(Player player, ItemStack item) {
        CharacterType type = resolveCharacter(player, item);
        if (player != null && type != null && !plugin.isCharacterModeActive(player)) {
            return null;
        }
        return get(type);
    }

    public CharacterType resolveCharacter(Player player, ItemStack item) {
        if (player != null) {
            return plugin.getCharacterResolver().resolveCharacter(player);
        }
        return item == null ? null : plugin.getCharacterResolver().resolveCharacter(item);
    }

    public boolean tryCastSkill(Player player, CharacterActionType actionType, LivingEntity directTarget) {
        if (player == null || !plugin.isCharacterModeActive(player)) {
            return false;
        }
        CharacterSkillHandler handler = resolveHandler(player);
        if (handler == null) {
            return false;
        }
        return handler.tryCastSkill(player, actionType, directTarget);
    }
}
