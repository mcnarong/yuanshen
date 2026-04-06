package org.yuanshen.yuanshen;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class AdminGuiHolder implements InventoryHolder {

    public enum ViewType {
        MAIN,
        PLAYER_LIST,
        PLAYER_ACTIONS,
        PLAYER_INFO,
        WEAPON_LIST,
        WEAPON_CONFIG
    }

    private final ViewType viewType;
    private final UUID viewerId;
    private final UUID targetId;
    private final String weaponId;
    private final int level;
    private final int refinement;
    private final int page;
    private Inventory inventory;

    public AdminGuiHolder(ViewType viewType, UUID viewerId, UUID targetId, String weaponId,
                          int level, int refinement, int page) {
        this.viewType = viewType;
        this.viewerId = viewerId;
        this.targetId = targetId;
        this.weaponId = weaponId;
        this.level = level;
        this.refinement = refinement;
        this.page = page;
    }

    public ViewType getViewType() {
        return viewType;
    }

    public UUID getViewerId() {
        return viewerId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getWeaponId() {
        return weaponId;
    }

    public int getLevel() {
        return level;
    }

    public int getRefinement() {
        return refinement;
    }

    public int getPage() {
        return page;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
