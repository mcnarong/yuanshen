package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ConstellationManager {

    private static final String CONSTELLATION_STAR_MARKER = "ys_constellation_star";

    private final Yuanshen plugin;

    public ConstellationManager(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public int getConstellation(Player player, CharacterType type) {
        if (player == null || type == null || plugin.getCharacterManager() == null) {
            return 0;
        }
        CharacterInstance instance = plugin.getCharacterManager().getSelectedCharacterInstance(player);
        if (instance == null || instance.definition() == null || instance.definition().characterType() != type) {
            return 0;
        }
        return instance.constellation();
    }

    public int getConstellationAtSlot(Player player, int slot) {
        if (player == null || plugin.getCharacterManager() == null) {
            return 0;
        }
        ItemStack item = plugin.getCharacterSlotManager().getSlotItem(player, slot);
        CharacterInstance instance = plugin.getCharacterManager().resolveCharacterInstance(item);
        return instance == null ? 0 : instance.constellation();
    }

    public boolean hasConstellation(Player player, CharacterType type, int constellation) {
        return getConstellation(player, type) >= Math.max(1, constellation);
    }

    public ItemStack createConstellationStarItem(int amount) {
        Material material = resolveConstellationStarMaterial();
        ItemStack item = new ItemStack(material == null ? Material.DIAMOND : material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String configuredDisplayName = plugin.getConfig().getString("constellation_star.display-name", "&b命星");
        List<String> configuredLore = plugin.getConfig().getStringList("constellation_star.lore");
        if (configuredLore == null || configuredLore.isEmpty()) {
            configuredLore = List.of(
                    "&7用于提升角色命座。",
                    "&7每次升级消耗 1 个命星。"
            );
        }
        meta.setDisplayName(color(configuredDisplayName.replace("{amount}", String.valueOf(item.getAmount()))));
        meta.setLore(configuredLore.stream()
                .map(line -> color(line.replace("{amount}", String.valueOf(item.getAmount()))))
                .toList());
        if (plugin.getConfig().contains("constellation_star.custom-model-data")) {
            int customModelData = plugin.getConfig().getInt("constellation_star.custom-model-data", -1);
            if (customModelData >= 0) {
                meta.setCustomModelData(customModelData);
            }
        }
        plugin.getItemIdentity().applyConstellationStar(meta);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isConstellationStar(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (plugin.getItemIdentity().isConstellationStar(meta)) {
            return true;
        }
        if (item.getType() != Material.DIAMOND || !meta.hasLore() || meta.getLore() == null) {
            return false;
        }
        for (String line : meta.getLore()) {
            String clean = sanitize(line);
            if (CONSTELLATION_STAR_MARKER.equals(clean)) {
                return true;
            }
        }
        return false;
    }

    public int countConstellationStars(Player player) {
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isConstellationStar(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public boolean consumeConstellationStars(Player player, int amount) {
        if (player == null || amount <= 0) {
            return false;
        }
        if (countConstellationStars(player) < amount) {
            return false;
        }

        PlayerInventory inventory = player.getInventory();
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isConstellationStar(item)) {
                continue;
            }

            int consume = Math.min(remaining, item.getAmount());
            int nextAmount = item.getAmount() - consume;
            if (nextAmount <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack clone = item.clone();
                clone.setAmount(nextAmount);
                inventory.setItem(slot, clone);
            }
            remaining -= consume;
        }
        return remaining <= 0;
    }

    public UpgradeResult upgradeCharacterSlot(Player owner, int slot, Player viewer) {
        if (owner == null || viewer == null || plugin.getCharacterManager() == null) {
            return UpgradeResult.failure("无法读取角色数据。");
        }

        ItemStack currentItem = plugin.getCharacterSlotManager().getSlotItem(owner, slot);
        CharacterInstance current = plugin.getCharacterManager().resolveCharacterInstance(currentItem);
        if (current == null) {
            return UpgradeResult.failure("该槽位没有可升级的角色。");
        }

        CharacterDefinition definition = current.definition();
        if (current.constellation() >= definition.maxConstellation()) {
            return UpgradeResult.failure(definition.displayName() + " 已经满命。");
        }
        if (!consumeConstellationStars(viewer, 1)) {
            return UpgradeResult.failure("背包缺少命星。");
        }

        int nextConstellation = current.constellation() + 1;
        ItemStack upgraded = plugin.getCharacterManager().createCharacterItem(
                definition.id(),
                current.level(),
                nextConstellation
        );
        CharacterInstance upgradedInstance = plugin.getCharacterManager().resolveCharacterInstance(upgraded);
        if (upgraded == null || upgradedInstance == null) {
            return UpgradeResult.failure("命座升级失败，请稍后重试。");
        }

        plugin.getCharacterSlotManager().setSlotItem(owner, slot, upgraded);
        plugin.refreshPlayerStats(owner);
        plugin.getPlayerDataStore().savePlayer(owner);

        return UpgradeResult.success(
                upgradedInstance,
                definition.displayName() + " 命座已提升至 " + nextConstellation + " 命。"
        );
    }

    public String getConstellationDescription(CharacterType type, int constellation) {
        if (type == null || constellation < 1 || constellation > 6) {
            return "暂无效果";
        }
        CharacterSkillConfig config = plugin.getCharacterConfig(type);
        String configured = config.getString("constellations." + constellation + ".description", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return switch (type) {
            case HUTAO -> switch (constellation) {
                case 1 -> "处于蝶引来生施加的彼岸蝶舞状态下时，胡桃的重击不会消耗体力。";
                case 2 -> "血梅香造成的伤害提高，提高值相当于效果附加时胡桃生命值上限的10%；安神秘法会为命中的敌人施加血梅香。";
                case 3 -> "蝶引来生的技能等级提高3级。";
                case 4 -> "处于胡桃自己施加的血梅香状态影响下的敌人被击败时，附近队伍中其他角色暴击率提高12%，持续15秒。";
                case 5 -> "安神秘法的技能等级提高3级。";
                default -> "生命值降至25%以下或承受致命伤害时触发保命效果，10秒内抗性、暴击率与抗打断能力大幅提升，每60秒最多触发一次。";
            };
            case XIANGLING -> switch (constellation) {
                case 1 -> "受到锅巴攻击的敌人，火元素抗性降低15%，持续6秒。";
                case 2 -> "普通攻击的最后一击会施加2秒内爆效果，结束时造成75%攻击力的火元素范围伤害。";
                case 3 -> "旋火轮的技能等级提高3级。";
                case 4 -> "旋火轮的持续时间延长40%。";
                case 5 -> "锅巴出击的技能等级提高3级。";
                default -> "旋火轮持续期间，队伍中所有角色获得15%火元素伤害加成。";
            };
            case DILUC -> switch (constellation) {
                case 1 -> "对生命值高于50%的敌人，迪卢克造成的伤害提高15%。";
                case 2 -> "迪卢克受到伤害时，攻击力提高10%、攻击速度提高5%，持续10秒，至多叠加3层。";
                case 3 -> "逆焰之刃的技能等级提高3级。";
                case 4 -> "有节奏地释放逆焰之刃时，下一段逆焰之刃的伤害提高40%。";
                case 5 -> "黎明的技能等级提高3级。";
                default -> "施放逆焰之刃后，接下来6秒内的2次普通攻击攻击速度提升30%、造成伤害提高30%，且不再重置普攻连击段数。";
            };
            case KEQING -> switch (constellation) {
                case 1 -> "雷楔存在期间再次施放星斗归位时，在刻晴消失与出现的位置造成50%攻击力的雷元素范围伤害。";
                case 2 -> "普通攻击与重击命中受到雷元素影响的敌人时，有50%几率产生一个元素微粒。该效果每5秒只能触发一次。";
                case 3 -> "天街巡游的技能等级提高3级。";
                case 4 -> "刻晴触发雷元素相关反应后的10秒内，攻击力提升25%。";
                case 5 -> "星斗归位的技能等级提高3级。";
                default -> "进行普攻、重击、施放元素战技或元素爆发时，获得6%雷元素伤害加成，持续8秒，各来源独立存在。";
            };
            case NINGGUANG -> switch (constellation) {
                case 1 -> "普通攻击命中时，会造成范围伤害。";
                case 2 -> "璇玑屏碎裂时，会清除冷却时间；该效果每6秒只能触发一次。";
                case 3 -> "天权崩玉的技能等级提高3级。";
                case 4 -> "璇玑屏会使周围角色的所有元素抗性提升10%。";
                case 5 -> "璇玑屏的技能等级提高3级。";
                default -> "施放天权崩玉时，会为凝光生成七枚星璇。";
            };
            case TRAVELER_ANEMO -> switch (constellation) {
                case 1 -> "风涡剑可将周围5米内的敌人缓慢牵引到角色面前。";
                case 2 -> "元素充能效率提升16%。";
                case 3 -> "风息激荡的技能等级提高3级。";
                case 4 -> "风涡剑持续期间，受到的伤害降低10%。";
                case 5 -> "风涡剑的技能等级提高3级。";
                default -> "受到风息激荡伤害的目标，风元素抗性下降20%；若产生元素转化，对应元素抗性也下降20%。";
            };
            case YELAN -> switch (constellation) {
                case 1 -> "萦络纵命索的可使用次数增加1次。";
                case 2 -> "玄掷玲珑协同攻击时，会额外发射一枚水箭，造成相当于夜兰生命值上限14%的水元素伤害。";
                case 3 -> "渊图玲珑骰的技能等级提高3级。";
                case 4 -> "依照络命丝标记敌人的数量，每次标记都会让队伍中所有角色生命值上限提升10%，持续25秒，至多提升40%。";
                case 5 -> "萦络纵命索的技能等级提高3级。";
                default -> "施放渊图玲珑骰后进入运筹帷幄状态，普通攻击转为特殊破局矢，最多持续20秒或发射5枚箭矢。";
            };
        };
    }

    public String getConstellationName(CharacterType type, int constellation) {
        if (type == null || constellation < 1 || constellation > 6) {
            return "未解锁命座";
        }
        CharacterSkillConfig config = plugin.getCharacterConfig(type);
        String configured = config.getString("constellations." + constellation + ".name", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return switch (type) {
            case HUTAO -> switch (constellation) {
                case 1 -> "赤团开时斜飞去";
                case 2 -> "最不安神晴又复雨";
                case 3 -> "逗留采血色";
                case 4 -> "伴君眠花房";
                case 5 -> "无可奈何燃花作香";
                default -> "幽蝶能留一缕芳";
            };
            case XIANGLING -> switch (constellation) {
                case 1 -> "外酥里嫩";
                case 2 -> "大火宽油";
                case 3 -> "武火急烹";
                case 4 -> "文火慢煨";
                case 5 -> "锅巴凶猛";
                default -> "大龙卷旋火轮";
            };
            case DILUC -> switch (constellation) {
                case 1 -> "罪罚裁断";
                case 2 -> "炙热余烬";
                case 3 -> "钢铁炽焰";
                case 4 -> "流火焦灼";
                case 5 -> "昭告黎明的火之鸟";
                default -> "清算黑暗的炎之剑";
            };
            case KEQING -> switch (constellation) {
                case 1 -> "雷厉";
                case 2 -> "苛捐";
                case 3 -> "登楼";
                case 4 -> "调律";
                case 5 -> "移灯";
                default -> "廉贞";
            };
            case NINGGUANG -> switch (constellation) {
                case 1 -> "悬星尽散击云碎";
                case 2 -> "璇玑合璧镇昆仑";
                case 3 -> "星罗宿列天权临";
                case 4 -> "攻守易形著神机";
                case 5 -> "琼屏千扇正天衡";
                default -> "七星璨璨凝流光";
            };
            case TRAVELER_ANEMO -> switch (constellation) {
                case 1 -> "回转的怒风";
                case 2 -> "革新的旋风";
                case 3 -> "天地的刚风";
                case 4 -> "眷护的和风";
                case 5 -> "群星的涡风";
                default -> "纠缠的信风";
            };
            case YELAN -> switch (constellation) {
                case 1 -> "与谋者，以局入局";
                case 2 -> "入彀者，多多益善";
                case 3 -> "晃盅者，琼畟药骰";
                case 4 -> "诓惑者，接树移花";
                case 5 -> "坐庄者，三仙戏法";
                default -> "取胜者，大小通吃";
            };
        };
    }

    public List<String> buildConstellationLore(CharacterType type, int currentConstellation) {
        List<String> lore = new ArrayList<>();
        int safeConstellation = Math.max(0, Math.min(6, currentConstellation));
        lore.add(color("&7当前命座: &f" + safeConstellation + "&7/6"));
        for (int i = 1; i <= 6; i++) {
            String prefix = i <= safeConstellation ? "&a" : "&7";
            lore.add(color(prefix + "C" + i + " &f" + getConstellationName(type, i)));
            lore.add(color((i <= safeConstellation ? "&f" : "&8") + getConstellationDescription(type, i)));
        }
        return lore;
    }

    private String sanitize(String text) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text == null ? "" : text))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private Material resolveConstellationStarMaterial() {
        String materialName = plugin.getConfig().getString("constellation_star.material", "DIAMOND");
        return Material.matchMaterial(materialName == null ? "" : materialName);
    }

    public record UpgradeResult(boolean success, CharacterInstance instance, String message) {
        public static UpgradeResult success(CharacterInstance instance, String message) {
            return new UpgradeResult(true, instance, message);
        }

        public static UpgradeResult failure(String message) {
            return new UpgradeResult(false, null, message);
        }
    }
}
