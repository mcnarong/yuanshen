package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import java.util.Locale;

public class ElementUtils {
    private final Yuanshen plugin;

    public ElementUtils(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public boolean hasElement(LivingEntity target, String elementKey) {
        if (target == null || elementKey == null) {
            return false;
        }
        try {
            ElementReactionManager reactionManager = plugin.getReactionManager();
            if (reactionManager != null) {
                return reactionManager.getEffectiveAuraAmount(target, elementKey) > 0.05;
            }
            return new ElementAura(target, plugin).hasElement(elementKey);
        } catch (Exception ex) {
            plugin.getLogger().warning("[元素检测] 检查 " + elementKey + " 时出错: " + ex.getMessage());
            return false;
        }
    }

    public void removeElement(LivingEntity target, String elementKey) {
        if (target == null || elementKey == null) {
            return;
        }
        ElementReactionManager reactionManager = plugin.getReactionManager();
        if (reactionManager != null) {
            reactionManager.removeEffectiveAura(target, elementKey);
            return;
        }
        new ElementAura(target, plugin).removeElement(elementKey);
    }

    public void addElementWithExpire(LivingEntity target, String elementKey, int durationTicks) {
        if (target == null || elementKey == null || durationTicks <= 0) {
            return;
        }
        ElementReactionManager reactionManager = plugin.getReactionManager();
        if (reactionManager != null) {
            reactionManager.addExternalAura(target, elementKey, 1.0, durationTicks);
            return;
        }
        new ElementAura(target, plugin).addElement(elementKey, 1.0, durationTicks);
    }

    public void sendMessage(Player player, String elementTag, String message) {
        if (player == null || !player.isOnline() || message == null || !plugin.isDamageDisplayEnabled(player)) {
            return;
        }
        String prefix = elementTag == null ? "" : elementTag;
        player.sendMessage(color(prefix + message));
    }

    public void sendNormalDamageMessage(Player player, String elementKey, double damage) {
        if (player == null || !player.isOnline() || !plugin.isDamageDisplayEnabled(player)) {
            return;
        }
        player.sendMessage(color(getElementTag(elementKey) + " &f造成" + getElementName(elementKey) + "伤害：&c" + formatDamage(damage)));
    }

    public void sendDamageResult(Player player, ReactionResult reactionResult, double finalDamage) {
        sendDamageResult(player, reactionResult, finalDamage, reactionResult == null ? 0.0 : reactionResult.getAdditionalDamage());
    }

    public void sendDamageResult(Player player, ReactionResult reactionResult, double finalDamage, double displayedReactionDamage) {
        if (player == null || !player.isOnline() || reactionResult == null || !plugin.isDamageDisplayEnabled(player)) {
            return;
        }

        String damageElement = reactionResult.getDamageElement();
        if (damageElement == null || damageElement.isBlank()) {
            damageElement = reactionResult.getNormalDamageElement();
        }
        if (damageElement == null || damageElement.isBlank()) {
            damageElement = PlayerStats.PHYSICAL_KEY;
        }

        sendNormalDamageMessage(player, damageElement, finalDamage);

        if (!reactionResult.hasReaction()) {
            return;
        }

        String prefix = reactionResult.getReactionTag() == null ? "&6[元素反应]" : reactionResult.getReactionTag();
        StringBuilder message = new StringBuilder(" &f触发元素反应：&e").append(reactionResult.getReactionName());
        message.append(" &f最终伤害：&c").append(formatDamage(finalDamage));
        String note = reactionResult.getDisplayNote();
        if (note != null && !note.isBlank()) {
            message.append(" &8| &7").append(note);
        }
        player.sendMessage(color(prefix + message));
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String getElementTag(String elementKey) {
        return switch (elementKey) {
            case ElementConstant.FIRE_KEY -> ElementConstant.FIRE_TAG;
            case ElementConstant.WATER_KEY -> ElementConstant.WATER_TAG;
            case ElementConstant.ICE_KEY -> ElementConstant.ICE_TAG;
            case ElementConstant.ELECTRO_KEY -> ElementConstant.ELECTRO_TAG;
            case ElementConstant.ANEMO_KEY -> ElementConstant.ANEMO_TAG;
            case ElementConstant.GEO_KEY -> ElementConstant.GEO_TAG;
            case ElementConstant.DENDRO_KEY -> ElementConstant.DENDRO_TAG;
            case PlayerStats.PHYSICAL_KEY -> "&7[物理]";
            default -> "&7[元素]";
        };
    }

    private String getElementName(String elementKey) {
        return switch (elementKey) {
            case ElementConstant.FIRE_KEY -> "火元素";
            case ElementConstant.WATER_KEY -> "水元素";
            case ElementConstant.ICE_KEY -> "冰元素";
            case ElementConstant.ELECTRO_KEY -> "雷元素";
            case ElementConstant.ANEMO_KEY -> "风元素";
            case ElementConstant.GEO_KEY -> "岩元素";
            case ElementConstant.DENDRO_KEY -> "草元素";
            case PlayerStats.PHYSICAL_KEY -> "物理";
            default -> "元素";
        };
    }

    public static String formatDamage(double damage) {
        return String.format(Locale.US, "%.1f", damage);
    }
}
