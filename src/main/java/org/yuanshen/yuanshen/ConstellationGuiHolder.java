package org.yuanshen.yuanshen;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class ConstellationGuiHolder implements InventoryHolder {

    private final UUID ownerId;
    private final UUID viewerId;
    private final int slot;
    private Inventory inventory;

    public ConstellationGuiHolder(UUID ownerId, UUID viewerId, int slot) {
        this.ownerId = ownerId;
        this.viewerId = viewerId;
        this.slot = slot;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getViewerId() {
        return viewerId;
    }

    public int getSlot() {
        return slot;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
