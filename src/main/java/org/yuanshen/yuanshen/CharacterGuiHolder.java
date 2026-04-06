package org.yuanshen.yuanshen;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class CharacterGuiHolder implements InventoryHolder {

    private final UUID ownerId;
    private final UUID viewerId;
    private Inventory inventory;

    public CharacterGuiHolder(UUID ownerId) {
        this(ownerId, ownerId);
    }

    public CharacterGuiHolder(UUID ownerId, UUID viewerId) {
        this.ownerId = ownerId;
        this.viewerId = viewerId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getViewerId() {
        return viewerId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
