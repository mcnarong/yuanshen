package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReloadCommand implements TabExecutor {

    private static final List<String> ROOT_SUBCOMMANDS = List.of(
            "reload", "stats", "damage", "sidebar", "skill",
            "open", "give", "list",
            "player", "admin"
    );
    private static final List<String> DAMAGE_MODES = List.of("toggle", "on", "off");
    private static final List<String> SIDEBAR_MODES = List.of("toggle", "on", "off");
    private static final List<String> SKILL_ACTIONS = List.of("e", "q");
    private static final List<String> PLAYER_ACTIONS = List.of("set");
    private static final List<String> GIVE_TYPES = List.of("character", "weapon", "constellation");
    private static final List<String> LIST_TYPES = List.of("character", "weapon");
    private static final List<String> SLOT_OPTIONS = List.of("1", "2", "3", "4");
    private static final List<String> ELEMENT_OPTIONS = List.of("火", "水", "冰", "雷", "风", "岩", "草", "清除");

    private final JavaPlugin plugin;

    public ReloadCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        if (plugin.getCommand("yuanshen") != null) {
            plugin.getCommand("yuanshen").setExecutor(this);
            plugin.getCommand("yuanshen").setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendCommandHelp(sender);
            return true;
        }

        if ("ys".equalsIgnoreCase(label) && looksLikeElementArg(args[0])) {
            return handleElement(sender, prependElementKeyword(args));
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "stats" -> handleStats(sender, args);
            case "damage" -> handleDamage(sender, args);
            case "sidebar" -> handleSidebar(sender, args);
            case "skill" -> handleSkill(sender, args);
            case "open" -> handleOpenRoot(sender, args);
            case "give" -> handleUnifiedGive(sender, args);
            case "list" -> handleUnifiedList(sender, args);
            case "player" -> handlePlayer(sender, args);
            case "admin" -> handleAdmin(sender);
            case "element" -> handleElement(sender, args);
            default -> {
                sendCommandHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("yuanshen.reload")) {
            sender.sendMessage(color("&c你没有权限执行该命令。"));
            return true;
        }

        if (plugin instanceof Yuanshen ys) {
            ys.reloadAllConfigs();
            sender.sendMessage(color("&a重载完成。主配置: &f" + ys.getMainConfigVersion()
                    + "&a，角色配置: &f" + ys.getSkillsConfigVersion()
                    + "&a，属性配置: &f" + ys.getAttributesConfigVersion()
                    + "&a，侧边栏配置: &f" + ys.getSidebarConfigVersion()));
            return true;
        }

        plugin.reloadConfig();
        sender.sendMessage(color("&a插件已重载。"));
        return true;
    }

    private boolean handleAdmin(CommandSender sender) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家可以打开腐竹管理 GUI。"));
            return true;
        }
        if (!sender.hasPermission("yuanshen.admin.gui") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限打开腐竹管理 GUI。"));
            return true;
        }

        ys.getAdminGuiManager().openMainMenu(player);
        return true;
    }

    private void sendCommandHelp(CommandSender sender) {
        sender.sendMessage(color("&6==== 原神插件命令帮助 ===="));
        sender.sendMessage(color("&e/ys reload &7- 重载插件配置"));
        sender.sendMessage(color("&e/ys stats [玩家] &7- 查看属性面板"));
        sender.sendMessage(color("&e/ys damage <toggle|on|off> [玩家] &7- 切换伤害显示"));
        sender.sendMessage(color("&e/ys sidebar <toggle|on|off> [玩家] &7- 切换侧边栏显示"));
        sender.sendMessage(color("&e/ys skill <e|q> &7- 通过指令释放元素战技或元素爆发"));
        sender.sendMessage(color("&e/ys open &7- 打开角色编队界面"));
        sender.sendMessage(color("&e/ys list <character|weapon> &7- 查看可发放物品列表"));
        sender.sendMessage(color("&e/ys give character <角色ID> [等级] [命座] [玩家] &7- 发放角色物品"));
        sender.sendMessage(color("&e/ys give weapon <武器ID> [等级] [精炼] [玩家] &7- 发放武器"));
        sender.sendMessage(color("&e/ys give constellation [数量] [玩家] &7- 发放命星"));
        sender.sendMessage(color("&e/ys player set <1|2|3|4> &7- 切换当前角色槽"));
        sender.sendMessage(color("&e/ys admin &7- 打开腐竹管理 GUI"));
        sender.sendMessage(color("&e/ys <火|水|冰|雷|风|岩|草|清除> &7- 设置当前元素"));
    }

    private boolean handleUnifiedGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color("&c用法: /ys give <character|weapon|constellation> ..."));
            sender.sendMessage(color("&7示例: /ys give character 刻晴 90 6"));
            return true;
        }
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (isCharacterType(args[1])) {
            return handleCharacterGive(sender, ys, args, 2);
        }
        if (isWeaponType(args[1])) {
            return handleWeaponGive(sender, ys, args, 2);
        }
        if (isStarType(args[1])) {
            return handleConstellationStarGive(sender, ys, args, 2);
        }
        sender.sendMessage(color("&c未知发放类型。可用值: character / weapon / constellation"));
        return true;
    }

    private boolean handleUnifiedList(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color("&c用法: /ys list <character|weapon>"));
            return true;
        }
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (isCharacterType(args[1])) {
            if (!sender.hasPermission("yuanshen.character.give") && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限查看角色列表。"));
                return true;
            }
            sender.sendMessage(color("&e可用角色: &f" + String.join(", ", ys.getCharacterManager().getCharacterListEntries())));
            return true;
        }
        if (isWeaponType(args[1])) {
            if (!sender.hasPermission("yuanshen.weapon.give") && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限查看武器列表。"));
                return true;
            }
            sender.sendMessage(color("&e可用武器: &f" + String.join(", ", ys.getWeaponManager().getWeaponListEntries())));
            return true;
        }
        sender.sendMessage(color("&c未知列表类型。可用值: character / weapon"));
        return true;
    }

    private boolean handleOpenRoot(CommandSender sender, String[] args) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(color("&c用法: /ys open"));
            return true;
        }
        return openJueseGui(sender, ys);
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("yuanshen.stats.others") && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限查看其他玩家属性。"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color("&c玩家 " + args[1] + " 不在线或不存在。"));
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&c控制台必须指定玩家。"));
                return true;
            }
            if (!sender.hasPermission("yuanshen.stats.self") && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限查看自己的属性。"));
                return true;
            }
            target = player;
        }

        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }

        PlayerStats stats = ys.getPlayerStats(target);
        if (stats == null) {
            sender.sendMessage(color("&c无法读取玩家属性。"));
            return true;
        }

        sender.sendMessage(color("&6========= " + target.getName() + " 的原神属性 ========="));
        sender.sendMessage(color("&e当前角色槽: &f" + ys.getCharacterSlotManager().getSelectedSlot(target)
                + "   &e当前角色: &f" + ys.getCharacterSlotManager().getSelectedCharacterName(target)
                + " &7Lv." + ys.getCharacterSlotManager().getSelectedCharacterLevel(target)
                + " &7C" + ys.getCharacterSlotManager().getSelectedCharacterConstellation(target)));
        sender.sendMessage(color("&e当前武器: &f" + ys.getWeaponManager().getSelectedWeaponName(target)
                + " &7Lv." + ys.getWeaponManager().getSelectedWeaponLevel(target)
                + " &7R" + ys.getWeaponManager().getSelectedWeaponRefinement(target)));
        sender.sendMessage(color("&e当前元素: &f" + formatElementName(ys.getActiveElement(target))));
        sender.sendMessage(color(String.format(Locale.US, "&e攻击力: &f%.1f   &e防御力: &f%.1f   &e生命值: &f%.1f",
                stats.getAttackDamage(), stats.getDefense(), stats.getHealth())));
        sender.sendMessage(color(String.format(Locale.US, "&e暴击率: &f%.2f%%   &e暴击伤害: &f%.2f%%   &e元素精通: &f%.1f",
                stats.getCritRate() * 100, stats.getCritDamage() * 100, stats.getElementMastery())));
        sender.sendMessage(color(String.format(Locale.US, "&e充能效率: &f%.2f%%   &e治疗加成: &f%.2f%%",
                stats.getEnergyRecharge() * 100, stats.getHealingBonus() * 100)));

        HuTaoStateManager huTao = ys.getHuTaoStateManager();
        if (huTao != null) {
            sender.sendMessage(color("&e胡桃状态:"));
            sender.sendMessage(color(String.format("  &6E状态: &f%s   &c低血强化: &f%s",
                    huTao.isActive(target) ? "开启" : "关闭",
                    huTao.isLowHp(target) ? "是" : "否")));
        }
        return true;
    }

    private boolean handleSkill(CommandSender sender, String[] args) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家可以释放技能。"));
            return true;
        }
        if (!sender.hasPermission("yuanshen.skill.cast") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限释放技能。"));
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(color("&c用法: /ys skill <e|q>"));
            return true;
        }

        CharacterActionType actionType = parseSkillAction(args[1]);
        if (actionType == null) {
            sender.sendMessage(color("&c用法: /ys skill <e|q>"));
            return true;
        }
        if (!ys.hasActiveCharacter(player)) {
            sender.sendMessage(color("&c当前没有选中的角色。"));
            return true;
        }
        if (!ys.isCharacterModeActive(player)) {
            sender.sendMessage(color("&c当前武器与所选角色不匹配，无法释放技能。"));
            return true;
        }

        LivingEntity directTarget = resolveDirectTarget(player, 24.0, 0.45);
        boolean used = ys.getCharacterSkillEngine().tryCastSkill(player, actionType, directTarget);
        if (used) {
            if (ys.getCharacterManager() != null) {
                ys.getCharacterManager().resetNormalAttackCombo(player);
            }
            return true;
        }

        sender.sendMessage(color("&c技能释放失败。请检查冷却、元素能量或当前状态。"));
        return true;
    }

    private boolean handleDamage(CommandSender sender, String[] args) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&c用法: /ys damage <toggle|on|off> [玩家]"));
                return true;
            }
            if (!sender.hasPermission("yuanshen.damage.self") && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限修改自己的伤害显示。"));
                return true;
            }
            return applyDamageDisplay(sender, ys, player, "toggle");
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!DAMAGE_MODES.contains(mode)) {
            sender.sendMessage(color("&c用法: /ys damage <toggle|on|off> [玩家]"));
            return true;
        }

        Player target = resolveTargetForToggle(sender, args, 2, "yuanshen.damage.self", "yuanshen.damage.others");
        if (target == null) {
            return true;
        }
        return applyDamageDisplay(sender, ys, target, mode);
    }

    private boolean handleSidebar(CommandSender sender, String[] args) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&c用法: /ys sidebar <toggle|on|off> [玩家]"));
                return true;
            }
            if (!sender.hasPermission("yuanshen.sidebar.self") && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限修改自己的侧边栏显示。"));
                return true;
            }
            return applySidebarDisplay(sender, ys, player, "toggle");
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!SIDEBAR_MODES.contains(mode)) {
            sender.sendMessage(color("&c用法: /ys sidebar <toggle|on|off> [玩家]"));
            return true;
        }

        Player target = resolveTargetForToggle(sender, args, 2, "yuanshen.sidebar.self", "yuanshen.sidebar.others");
        if (target == null) {
            return true;
        }
        return applySidebarDisplay(sender, ys, target, mode);
    }

    private boolean handleCharacterGive(CommandSender sender, Yuanshen ys, String[] args, int startIndex) {
        if (args.length <= startIndex) {
            sender.sendMessage(color("&c用法: /ys give character <角色ID> [等级] [命座] [玩家]"));
            return true;
        }
        if (!sender.hasPermission("yuanshen.character.give") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限发放角色物品。"));
            return true;
        }

        CharacterDefinition definition = ys.getCharacterManager().findByInput(args[startIndex]);
        if (definition == null) {
            sender.sendMessage(color("&c未知角色: &f" + args[startIndex]));
            return true;
        }

        int index = startIndex + 1;
        int level = definition.defaultLevel();
        int constellation = definition.defaultConstellation();

        if (args.length > index && isInteger(args[index])) {
            level = parsePositiveInt(args[index], definition.defaultLevel());
            index++;
        }
        if (args.length > index && isInteger(args[index])) {
            constellation = parseNonNegativeInt(args[index], definition.defaultConstellation());
            index++;
        }

        Player target;
        if (args.length > index) {
            target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage(color("&c玩家 " + args[index] + " 不在线或不存在。"));
                return true;
            }
            index++;
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(color("&c控制台必须指定玩家。"));
            return true;
        }

        if (args.length > index) {
            sender.sendMessage(color("&c用法: /ys give character <角色ID> [等级] [命座] [玩家]"));
            return true;
        }

        ItemStack characterItem = ys.getCharacterManager().createCharacterItem(definition.id(), level, constellation);
        CharacterInstance instance = ys.getCharacterManager().resolveCharacterInstance(characterItem);
        if (characterItem == null || instance == null) {
            sender.sendMessage(color("&c角色物品创建失败: &f" + definition.displayName()));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(characterItem);
        if (!leftover.isEmpty()) {
            sender.sendMessage(color("&c目标玩家背包空间不足，无法发放角色。"));
            return true;
        }

        sender.sendMessage(color("&a已发放角色 &f" + instance.definition().displayName() + "&a 给玩家 &f" + target.getName()
                + "&a，等级 &f" + instance.level() + "&a，命座 &f" + instance.constellation()));
        if (!sender.equals(target)) {
            target.sendMessage(color("&a你获得了角色 &f" + instance.definition().displayName()
                    + "&a，等级 &f" + instance.level() + "&a，命座 &f" + instance.constellation()));
        }
        return true;
    }

    private boolean openJueseGui(CommandSender sender, Yuanshen ys) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家可以打开角色界面。"));
            return true;
        }
        if (!sender.hasPermission("yuanshen.character.gui") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限打开角色界面。"));
            return true;
        }

        ys.getCharacterSlotManager().openInventory(player);
        sender.sendMessage(color("&a已打开角色编队界面。武器请直接拿在主手使用。"));
        return true;
    }

    private boolean handleConstellationStarGive(CommandSender sender, Yuanshen ys, String[] args, int startIndex) {
        if (!sender.hasPermission("yuanshen.character.give") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限发放命星。"));
            return true;
        }

        int amount = 1;
        int index = startIndex;
        if (args.length > index && isInteger(args[index])) {
            amount = parsePositiveInt(args[index], 1);
            index++;
        }

        Player target;
        if (args.length > index) {
            target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage(color("&c玩家 " + args[index] + " 不在线或不存在。"));
                return true;
            }
            index++;
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(color("&c控制台必须指定玩家。"));
            return true;
        }

        if (args.length > index) {
            sender.sendMessage(color("&c用法: /ys give constellation [数量] [玩家]"));
            return true;
        }

        ItemStack starItem = ys.getConstellationManager().createConstellationStarItem(amount);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(starItem);
        if (!leftover.isEmpty()) {
            sender.sendMessage(color("&c目标玩家背包空间不足，无法发放命星。"));
            return true;
        }

        sender.sendMessage(color("&a已发放 &f" + amount + "&a 个命星给玩家 &f" + target.getName()));
        if (!sender.equals(target)) {
            target.sendMessage(color("&a你收到了 &f" + amount + "&a 个命星。"));
        }
        return true;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家可以切换角色。"));
            return true;
        }
        if (!sender.hasPermission("yuanshen.player.set") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限切换角色槽。"));
            return true;
        }
        if (args.length < 3 || !"set".equalsIgnoreCase(args[1])) {
            sender.sendMessage(color("&c用法: /ys player set <1|2|3|4>"));
            return true;
        }

        int slot;
        try {
            slot = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(color("&c槽位必须是 1 到 4。"));
            return true;
        }

        if (slot < 1 || slot > CharacterSlotManager.MAX_SLOTS) {
            sender.sendMessage(color("&c槽位必须是 1 到 4。"));
            return true;
        }

        ItemStack item = ys.getCharacterSlotManager().getSlotItem(player, slot);
        CharacterType type = ys.getCharacterResolver().resolveCharacter(item);
        if (type == null) {
            sender.sendMessage(color("&c该槽位没有有效角色。请先用 /ys open 打开编队界面后放入角色物品。"));
            return true;
        }

        ys.getCharacterSlotManager().setSelectedSlot(player, slot);
        ys.refreshPlayerStats(player);
        ys.getPlayerDataStore().savePlayer(player);
        sender.sendMessage(color("&a已切换到角色槽 &f" + slot + "&a，当前角色 &f" + type.getDisplayName()));
        return true;
    }

    private boolean handleWeaponGive(CommandSender sender, Yuanshen ys, String[] args, int startIndex) {
        if (!sender.hasPermission("yuanshen.weapon.give") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限管理原神武器。"));
            return true;
        }
        if (args.length <= startIndex) {
            sender.sendMessage(color("&c用法: /ys give weapon <武器ID> [等级] [精炼] [玩家]"));
            return true;
        }

        WeaponDefinition definition = ys.getWeaponManager().findByInput(args[startIndex]);
        if (definition == null) {
            sender.sendMessage(color("&c未知武器: &f" + args[startIndex]));
            return true;
        }

        int index = startIndex + 1;
        int level = definition.defaultLevel();
        int refinement = definition.defaultRefinement();

        if (args.length > index && isInteger(args[index])) {
            level = parsePositiveInt(args[index], definition.defaultLevel());
            index++;
        }
        if (args.length > index && isInteger(args[index])) {
            refinement = parsePositiveInt(args[index], definition.defaultRefinement());
            index++;
        }

        Player target;
        if (args.length > index) {
            target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage(color("&c玩家 " + args[index] + " 不在线或不存在。"));
                return true;
            }
            index++;
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(color("&c控制台必须指定玩家。"));
            return true;
        }

        if (args.length > index) {
            sender.sendMessage(color("&c用法: /ys give weapon <武器ID> [等级] [精炼] [玩家]"));
            return true;
        }

        ItemStack weaponItem = ys.getWeaponManager().createWeaponItem(definition.id(), level, refinement);
        if (weaponItem == null) {
            sender.sendMessage(color("&c武器创建失败: &f" + definition.displayName()));
            return true;
        }

        WeaponInstance instance = ys.getWeaponManager().resolveWeaponInstance(weaponItem);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(weaponItem);
        if (!leftover.isEmpty()) {
            sender.sendMessage(color("&c目标玩家背包空间不足，无法发放武器。"));
            return true;
        }

        String weaponName = instance == null ? definition.displayName() : instance.definition().displayName();
        int finalLevel = instance == null ? definition.clampLevel(level) : instance.level();
        int finalRefinement = instance == null ? definition.clampRefinement(refinement) : instance.refinement();
        sender.sendMessage(color("&a已发放武器 &f" + weaponName + "&a 给玩家 &f" + target.getName()
                + "&a，等级 &f" + finalLevel + "&a，精炼 &f" + finalRefinement));
        if (!sender.equals(target)) {
            target.sendMessage(color("&a你获得了武器 &f" + weaponName + "&a，等级 &f" + finalLevel + "&a，精炼 &f" + finalRefinement));
        }
        return true;
    }

    private boolean handleElement(CommandSender sender, String[] args) {
        if (!(plugin instanceof Yuanshen ys)) {
            sender.sendMessage(color("&c当前插件实例不支持该命令。"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家可以设置元素。"));
            return true;
        }
        if (!sender.hasPermission("yuanshen.element.self") && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限设置元素。"));
            return true;
        }

        if (args.length <= 1) {
            sender.sendMessage(color("&e当前元素: &f" + formatElementName(ys.getActiveElement(player))));
            sender.sendMessage(color("&e用法: /ys <火|水|冰|雷|风|岩|草|清除>"));
            return true;
        }

        if (isClearArg(args[1])) {
            ys.clearActiveElement(player);
            ys.getPlayerDataStore().savePlayer(player);
            sender.sendMessage(color("&a已清除当前元素。"));
            return true;
        }

        String elementKey = normalizeElement(args[1]);
        if (elementKey == null) {
            sender.sendMessage(color("&c未知元素。可用值: 火 水 冰 雷 风 岩 草 清除"));
            return true;
        }

        ys.setActiveElement(player, elementKey);
        ys.getPlayerDataStore().savePlayer(player);
        sender.sendMessage(color("&a当前元素已设置为: &f" + formatElementName(elementKey)));
        return true;
    }

    private Player resolveTargetForToggle(CommandSender sender, String[] args, int playerArgIndex,
                                          String selfPermission, String othersPermission) {
        if (args.length > playerArgIndex) {
            if (!sender.hasPermission(othersPermission) && !sender.isOp()) {
                sender.sendMessage(color("&c你没有权限修改其他玩家。"));
                return null;
            }
            Player target = Bukkit.getPlayerExact(args[playerArgIndex]);
            if (target == null) {
                sender.sendMessage(color("&c玩家 " + args[playerArgIndex] + " 不在线或不存在。"));
            }
            return target;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c控制台必须指定玩家。"));
            return null;
        }
        if (!sender.hasPermission(selfPermission) && !sender.isOp()) {
            sender.sendMessage(color("&c你没有权限修改自己的设置。"));
            return null;
        }
        return player;
    }

    private boolean applyDamageDisplay(CommandSender sender, Yuanshen ys, Player target, String mode) {
        boolean enabled = switch (mode) {
            case "on" -> true;
            case "off" -> false;
            default -> !ys.isDamageDisplayEnabled(target);
        };

        ys.setDamageDisplayEnabled(target, enabled);
        ys.getPlayerDataStore().savePlayer(target);

        String stateText = enabled ? "&a开启" : "&c关闭";
        if (sender.equals(target)) {
            sender.sendMessage(color("&e伤害显示已设置为: " + stateText));
        } else {
            sender.sendMessage(color("&e已将玩家 &f" + target.getName() + "&e 的伤害显示设置为: " + stateText));
            target.sendMessage(color("&e你的伤害显示已被设置为: " + stateText));
        }
        return true;
    }

    private boolean applySidebarDisplay(CommandSender sender, Yuanshen ys, Player target, String mode) {
        boolean enabled = switch (mode) {
            case "on" -> true;
            case "off" -> false;
            default -> !ys.isSidebarEnabled(target);
        };

        ys.setSidebarEnabled(target, enabled);
        ys.getPlayerDataStore().savePlayer(target);

        String stateText = enabled ? "&a开启" : "&c关闭";
        if (sender.equals(target)) {
            sender.sendMessage(color("&e侧边栏显示已设置为: " + stateText));
        } else {
            sender.sendMessage(color("&e已将玩家 &f" + target.getName() + "&e 的侧边栏显示设置为: " + stateText));
            target.sendMessage(color("&e你的侧边栏显示已被设置为: " + stateText));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if ("ys".equalsIgnoreCase(alias) && args.length == 1) {
            return merge(filter(ELEMENT_OPTIONS, args[0]), filter(ROOT_SUBCOMMANDS, args[0]));
        }
        if (args.length == 1) {
            return filter(ROOT_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            return filter(getOnlinePlayerNames(), args[1]);
        }
        if (args[0].equalsIgnoreCase("damage")) {
            if (args.length == 2) {
                return filter(DAMAGE_MODES, args[1]);
            }
            if (args.length == 3) {
                return filter(getOnlinePlayerNames(), args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("sidebar")) {
            if (args.length == 2) {
                return filter(SIDEBAR_MODES, args[1]);
            }
            if (args.length == 3) {
                return filter(getOnlinePlayerNames(), args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("skill")) {
            if (args.length == 2) {
                return filter(SKILL_ACTIONS, args[1]);
            }
            return Collections.emptyList();
        }
        if (args[0].equalsIgnoreCase("player")) {
            if (args.length == 2) {
                return filter(PLAYER_ACTIONS, args[1]);
            }
            if (args.length == 3 && "set".equalsIgnoreCase(args[1])) {
                return filter(SLOT_OPTIONS, args[2]);
            }
        }
        if (isListRoot(args[0])) {
            if (args.length == 2) {
                return filter(LIST_TYPES, args[1]);
            }
            return Collections.emptyList();
        }
        if (isGiveRoot(args[0]) && plugin instanceof Yuanshen ys) {
            if (args.length == 2) {
                return filter(GIVE_TYPES, args[1]);
            }
            if (isCharacterType(args[1])) {
                if (args.length == 3) {
                    return filter(ys.getCharacterManager().getCharacterCommandOptions(), args[2]);
                }
                if (args.length == 4) {
                    CharacterDefinition definition = ys.getCharacterManager().findByInput(args[2]);
                    return definition == null ? Collections.emptyList()
                            : filter(buildRangeSuggestions(1, definition.maxLevel()), args[3]);
                }
                if (args.length == 5) {
                    CharacterDefinition definition = ys.getCharacterManager().findByInput(args[2]);
                    return definition == null ? filter(getOnlinePlayerNames(), args[4])
                            : merge(filter(buildRangeSuggestions(0, definition.maxConstellation()), args[4]), filter(getOnlinePlayerNames(), args[4]));
                }
                if (args.length == 6) {
                    return filter(getOnlinePlayerNames(), args[5]);
                }
            }
            if (isWeaponType(args[1])) {
                if (args.length == 3) {
                    return filter(ys.getWeaponManager().getWeaponCommandOptions(), args[2]);
                }
                if (args.length == 4) {
                    WeaponDefinition definition = ys.getWeaponManager().findByInput(args[2]);
                    return definition == null ? Collections.emptyList()
                            : filter(buildRangeSuggestions(1, definition.maxLevel()), args[3]);
                }
                if (args.length == 5) {
                    WeaponDefinition definition = ys.getWeaponManager().findByInput(args[2]);
                    return definition == null ? filter(getOnlinePlayerNames(), args[4])
                            : merge(filter(buildRangeSuggestions(1, definition.maxRefinement()), args[4]), filter(getOnlinePlayerNames(), args[4]));
                }
                if (args.length == 6) {
                    return filter(getOnlinePlayerNames(), args[5]);
                }
            }
            if (isStarType(args[1])) {
                if (args.length == 3) {
                    return merge(filter(buildRangeSuggestions(1, 64), args[2]), filter(getOnlinePlayerNames(), args[2]));
                }
                if (args.length == 4) {
                    return filter(getOnlinePlayerNames(), args[3]);
                }
            }
        }
        if (args[0].equalsIgnoreCase("element") && args.length == 2) {
            return filter(ELEMENT_OPTIONS, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> filter(List<String> values, String input) {
        String lowerInput = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerInput)) {
                matches.add(value);
            }
        }
        return matches;
    }

    private List<String> merge(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>(left);
        for (String value : right) {
            if (!merged.contains(value)) {
                merged.add(value);
            }
        }
        return merged;
    }

    private List<String> buildRangeSuggestions(int min, int max) {
        List<String> values = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            values.add(String.valueOf(i));
        }
        return values;
    }

    private CharacterActionType parseSkillAction(String input) {
        if (input == null) {
            return null;
        }
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "e" -> CharacterActionType.ELEMENTAL_SKILL;
            case "q" -> CharacterActionType.ELEMENTAL_BURST;
            default -> null;
        };
    }

    private boolean isGiveRoot(String input) {
        return "give".equalsIgnoreCase(input);
    }

    private boolean isListRoot(String input) {
        return "list".equalsIgnoreCase(input);
    }

    private boolean isCharacterType(String input) {
        return "character".equalsIgnoreCase(input);
    }

    private boolean isWeaponType(String input) {
        return "weapon".equalsIgnoreCase(input);
    }

    private boolean isStarType(String input) {
        return "constellation".equalsIgnoreCase(input);
    }

    private boolean isInteger(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private int parsePositiveInt(String input, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(input));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int parseNonNegativeInt(String input, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(input));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String normalizeElement(String input) {
        if (input == null) {
            return null;
        }
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "fire", "pyro", "火" -> ElementConstant.FIRE_KEY;
            case "water", "hydro", "水" -> ElementConstant.WATER_KEY;
            case "ice", "cryo", "冰" -> ElementConstant.ICE_KEY;
            case "electro", "雷" -> ElementConstant.ELECTRO_KEY;
            case "anemo", "wind", "风" -> ElementConstant.ANEMO_KEY;
            case "geo", "岩" -> ElementConstant.GEO_KEY;
            case "dendro", "草" -> ElementConstant.DENDRO_KEY;
            default -> null;
        };
    }

    private boolean looksLikeElementArg(String input) {
        return normalizeElement(input) != null || isClearArg(input);
    }

    private boolean isClearArg(String input) {
        if (input == null) {
            return false;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        return "clear".equals(lower) || "清除".equals(input) || "取消".equals(input) || "无".equals(input);
    }

    private String[] prependElementKeyword(String[] args) {
        return prependSubcommand(args, "element");
    }

    private String[] prependSubcommand(String[] args, String subcommand) {
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = subcommand;
        System.arraycopy(args, 0, newArgs, 1, args.length);
        return newArgs;
    }

    private LivingEntity resolveDirectTarget(Player player, double maxDistance, double hitboxExpand) {
        if (player == null) {
            return null;
        }
        RayTraceResult result = player.getWorld().rayTrace(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                Math.max(1.0, maxDistance),
                FluidCollisionMode.NEVER,
                true,
                Math.max(0.0, hitboxExpand),
                entity -> entity instanceof LivingEntity living
                        && !living.equals(player)
                        && living.isValid()
                        && !living.isDead()
        );
        if (result == null || !(result.getHitEntity() instanceof LivingEntity living)) {
            return null;
        }
        return living;
    }

    private String formatElementName(String elementKey) {
        if (elementKey == null || elementKey.isBlank()) {
            return "无";
        }
        return switch (elementKey) {
            case ElementConstant.FIRE_KEY -> "火";
            case ElementConstant.WATER_KEY -> "水";
            case ElementConstant.ICE_KEY -> "冰";
            case ElementConstant.ELECTRO_KEY -> "雷";
            case ElementConstant.ANEMO_KEY -> "风";
            case ElementConstant.GEO_KEY -> "岩";
            case ElementConstant.DENDRO_KEY -> "草";
            default -> elementKey;
        };
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
