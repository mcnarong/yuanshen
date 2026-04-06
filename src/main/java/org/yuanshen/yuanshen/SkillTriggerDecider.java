package org.yuanshen.yuanshen;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class SkillTriggerDecider {

    private SkillTriggerDecider() {
    }

    static boolean shouldPreserveVanillaItemUse(ItemStack item) {
        if (item == null) {
            return false;
        }
        Material type = item.getType();
        String name = type.name();
        if (type.isEdible()) {
            return true;
        }
        return name.endsWith("_SPAWN_EGG")
                || name.endsWith("_BUCKET")
                || name.endsWith("_POTION")
                || name.endsWith("_BOAT")
                || name.contains("MINECART")
                || type == Material.EGG
                || type == Material.SNOWBALL
                || type == Material.ENDER_PEARL
                || type == Material.EXPERIENCE_BOTTLE
                || type == Material.BOW
                || type == Material.CROSSBOW
                || type == Material.TRIDENT
                || type == Material.SHIELD
                || type == Material.FLINT_AND_STEEL
                || type == Material.FIRE_CHARGE
                || type == Material.BRUSH
                || type == Material.SPYGLASS
                || type == Material.GOAT_HORN
                || type == Material.WRITABLE_BOOK
                || type == Material.WRITTEN_BOOK
                || type == Material.FIREWORK_ROCKET
                || type == Material.ARMOR_STAND
                || type == Material.ITEM_FRAME
                || type == Material.GLOW_ITEM_FRAME
                || type == Material.PAINTING
                || type == Material.END_CRYSTAL
                || type == Material.NAME_TAG
                || type == Material.LEAD
                || type == Material.SADDLE
                || type == Material.SHEARS;
    }

    static boolean canTriggerSkills(Yuanshen plugin, Player player, ItemStack item) {
        return player != null
                && plugin.isCharacterModeActive(player)
                && canTriggerSkillsWithItem(plugin, item);
    }

    static boolean canTriggerSkillsWithItem(Yuanshen plugin, ItemStack item) {
        return plugin != null
                && plugin.getWeaponManager() != null
                && plugin.getWeaponManager().isRegisteredWeapon(item);
    }
}
