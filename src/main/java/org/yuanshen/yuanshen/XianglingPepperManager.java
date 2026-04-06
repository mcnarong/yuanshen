package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class XianglingPepperManager implements CharacterStateHandler {

    private static final String ATTACK_BONUS_KEY = "pepper_attack_bonus";
    private static final String PEPPER_META_KEY = "ys_xiangling_pepper";

    private final Yuanshen plugin;
    private final CharacterStateContext buffContext = new CharacterStateContext(CharacterType.XIANGLING);
    private final List<UUID> droppedPepperIds = new ArrayList<>();

    public XianglingPepperManager(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void dropPepper(Location location, Player owner) {
        if (location == null || owner == null) {
            return;
        }
        if (!plugin.getCharacterConfig(CharacterType.XIANGLING).getBoolean("passive.pepper.enabled", true)) {
            return;
        }

        Material material = resolveMaterial(plugin.getCharacterConfig(CharacterType.XIANGLING)
                .getString("passive.pepper.item.material", "RED_DYE"));
        ItemStack stack = new ItemStack(material == null ? Material.RED_DYE : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(plugin.getCharacterConfig(CharacterType.XIANGLING)
                    .getString("passive.pepper.item.name", "&c绝云朝天椒")));
            List<String> lore = plugin.getSkillsConfig().getStringList("skills.xiangling.passive.pepper.item.lore");
            if (!lore.isEmpty()) {
                List<String> translated = new ArrayList<>();
                for (String line : lore) {
                    translated.add(color(line));
                }
                meta.setLore(translated);
            }
            stack.setItemMeta(meta);
        }

        Item item = location.getWorld().dropItem(location.clone().add(0, 0.35, 0), stack);
        item.setMetadata(PEPPER_META_KEY, new FixedMetadataValue(plugin, owner.getUniqueId().toString()));
        item.setVelocity(new Vector(0.0, 0.15, 0.0));
        droppedPepperIds.add(item.getUniqueId());

        int despawnTicks = plugin.getCharacterConfig(CharacterType.XIANGLING)
                .getInt("passive.pepper.item.despawn-ticks", 160);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (item.isValid() && !item.isDead()) {
                item.remove();
            }
            droppedPepperIds.remove(item.getUniqueId());
        }, Math.max(20L, despawnTicks));
    }

    public boolean handlePickup(Player player, Item item) {
        if (player == null || item == null || !item.isValid() || !item.hasMetadata(PEPPER_META_KEY)) {
            return false;
        }

        droppedPepperIds.remove(item.getUniqueId());
        item.remove();

        double attackBonus = plugin.getCharacterConfig(CharacterType.XIANGLING)
                .getDouble("passive.pepper.attack-bonus", 0.10);
        long durationMs = plugin.getCharacterConfig(CharacterType.XIANGLING)
                .getLong("passive.pepper.duration-ticks", 200L) * 50L;
        buffContext.setNumber(player, ATTACK_BONUS_KEY, attackBonus);
        buffContext.setStateEnd(player, System.currentTimeMillis() + durationMs);
        plugin.refreshPlayerStats(player);

        if (plugin.getCharacterConfig(CharacterType.XIANGLING).getBoolean("notifications.pepper-picked", true)) {
            String text = plugin.getSkillsConfig().getString(
                    "skills.xiangling.messages.pepper-picked",
                    "&6[香菱] 拾取绝云朝天椒，攻击力提升 &f{attack_bonus}&6，持续 &f{duration}&6 秒。"
            );
            player.sendMessage(color(text
                    .replace("{attack_bonus}", String.format("%.0f%%", attackBonus * 100.0))
                    .replace("{duration}", String.format("%.1f", durationMs / 1000.0))));
        }
        return true;
    }

    public double getAttackBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        if (!buffContext.hasActiveState(player.getUniqueId())) {
            if (buffContext.hasState(player.getUniqueId())) {
                buffContext.clear(player);
                plugin.refreshPlayerStats(player);
            }
            return 0.0;
        }
        return buffContext.getNumber(player, ATTACK_BONUS_KEY, 0.0);
    }

    public long getBuffEndMillis(Player player) {
        return player == null ? 0L : buffContext.getStateEnd(player.getUniqueId());
    }

    public void restoreBuff(Player player, long endTimeMillis) {
        if (player == null || endTimeMillis <= System.currentTimeMillis()) {
            buffContext.clear(player);
            return;
        }
        buffContext.setNumber(player, ATTACK_BONUS_KEY, plugin.getCharacterConfig(CharacterType.XIANGLING)
                .getDouble("passive.pepper.attack-bonus", 0.10));
        buffContext.setStateEnd(player, endTimeMillis);
        plugin.refreshPlayerStats(player);
    }

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.XIANGLING;
    }

    @Override
    public void clear(Player player) {
        buffContext.clear(player);
    }

    @Override
    public void clearAll() {
        buffContext.clearAll();
        Iterator<UUID> iterator = droppedPepperIds.iterator();
        while (iterator.hasNext()) {
            UUID itemId = iterator.next();
            org.bukkit.entity.Entity entity = plugin.getServer().getEntity(itemId);
            if (entity instanceof Item item && item.isValid()) {
                item.remove();
            }
            iterator.remove();
        }
    }

    private Material resolveMaterial(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
