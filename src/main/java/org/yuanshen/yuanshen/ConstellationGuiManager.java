package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class ConstellationGuiManager {

    public static final String GUI_TITLE = ChatColor.DARK_GRAY + "角色命座";

    private final Yuanshen plugin;

    public ConstellationGuiManager(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void openInventory(Player viewer, Player owner, int slot) {
        if (viewer == null || owner == null) {
            return;
        }
        viewer.openInventory(createInventory(viewer, owner, slot));
    }

    public Inventory createInventory(Player viewer, Player owner, int slot) {
        ConstellationGuiHolder holder = new ConstellationGuiHolder(owner.getUniqueId(), viewer.getUniqueId(), slot);
        Inventory inventory = Bukkit.createInventory(holder, 27, GUI_TITLE);
        holder.setInventory(inventory);
        populateInventory(inventory, viewer, owner, slot);
        return inventory;
    }

    public void populateInventory(Inventory inventory, Player viewer, Player owner, int slot) {
        if (inventory == null) {
            return;
        }

        ItemStack filler = createFiller();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(10, createButton(Material.SPECTRAL_ARROW, "&e返回角色界面",
                List.of("&7回到角色与武器配置界面。")));

        ItemStack characterItem = plugin.getCharacterSlotManager().getSlotItem(owner, slot);
        CharacterInstance instance = plugin.getCharacterManager().resolveCharacterInstance(characterItem);
        if (instance == null) {
            inventory.setItem(13, createButton(Material.BARRIER, "&c该槽位没有角色",
                    List.of("&7请先放入角色物品。")));
            inventory.setItem(16, createButton(Material.BARRIER, "&c无法升级",
                    List.of("&7没有可用的角色命座数据。")));
            return;
        }

        inventory.setItem(13, buildCharacterPreview(instance));
        inventory.setItem(15, buildUpgradeButton(viewer, instance));
        inventory.setItem(16, buildInfoBook(instance));
    }

    public boolean isConstellationGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ConstellationGuiHolder;
    }

    private ItemStack buildCharacterPreview(CharacterInstance instance) {
        ItemStack item = plugin.getCharacterManager().createCharacterItem(
                instance.definition().id(),
                instance.level(),
                instance.constellation()
        );
        if (item == null) {
            item = new ItemStack(Material.NETHER_STAR);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null
                    ? new ArrayList<>(meta.getLore())
                    : new ArrayList<>();
            lore.add(color("&6命座效果"));
            lore.addAll(plugin.getConstellationManager().buildConstellationLore(
                    instance.definition().characterType(),
                    instance.constellation()
            ));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildUpgradeButton(Player viewer, CharacterInstance instance) {
        int current = instance.constellation();
        int max = instance.definition().maxConstellation();
        int stars = plugin.getConstellationManager().countConstellationStars(viewer);
        if (current >= max) {
            return createButton(Material.BARRIER, "&c已满命",
                    List.of("&7当前角色已经达到 6 命。"));
        }

        List<String> lore = new ArrayList<>();
        lore.add(color("&7当前命座: &f" + current + "&7/6"));
        lore.add(color("&7下一级: &fC" + (current + 1) + " &7- &f"
                + plugin.getConstellationManager().getConstellationName(instance.definition().characterType(), current + 1)));
        lore.add(color("&7效果: &f" + plugin.getConstellationManager()
                .getConstellationDescription(instance.definition().characterType(), current + 1)));
        lore.add(color("&7消耗: &b1 个命星"));
        lore.add(color("&7你当前拥有: &f" + stars + " 个"));
        lore.add(color(stars > 0 ? "&a点击升级" : "&c命星不足"));
        return createButton(Material.DIAMOND, "&b升级命座", lore);
    }

    private ItemStack buildInfoBook(CharacterInstance instance) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7角色: &f" + instance.definition().displayName()));
        lore.add(color("&7等级: &f" + instance.level()));
        lore.add(color("&7当前命座: &f" + instance.constellation() + "&7/6"));
        lore.addAll(plugin.getConstellationManager().buildConstellationLore(
                instance.definition().characterType(),
                instance.constellation()
        ));
        return createButton(Material.BOOK, "&6命座总览", lore);
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_GRAY + " ");
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createButton(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore == null ? List.of() : lore.stream().map(this::color).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
