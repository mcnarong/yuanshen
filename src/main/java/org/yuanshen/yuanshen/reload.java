package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件重载管理类（独立文件，不影响原有元素逻辑）
 * 提供指令 /yuanshen reload 实现插件重载
 */
public class reload implements CommandExecutor {
    // 插件主类实例（需替换为你的插件主类名）
    private final JavaPlugin mainPlugin;

    // 构造方法：传入插件主类实例
    public reload(JavaPlugin mainPlugin) {
        this.mainPlugin = mainPlugin;
        // 注册指令（无需修改plugin.yml，也可通过yml注册）
        registerReloadCommand();
    }

    /**
     * 注册重载指令
     */
    private void registerReloadCommand() {
        PluginCommand reloadCmd = mainPlugin.getCommand("yuanshen");
        if (reloadCmd != null) {
            reloadCmd.setExecutor(this);
        } else {
            mainPlugin.getLogger().warning("指令注册失败！请检查plugin.yml中是否配置了yuanshen指令");
        }
    }

    /**
     * 指令执行逻辑：/yuanshen reload
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // 权限检查：仅OP或有yuanshen.reload权限的玩家可执行
        if (!sender.isOp() && !sender.hasPermission("yuanshen.reload")) {
            sender.sendMessage("§c你没有权限执行此指令！");
            return true;
        }

        // 检测指令参数
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§e正确用法：/yuanshen reload");
            return true;
        }

        // 执行插件重载
        sender.sendMessage("§a开始重载原神元素插件...");
        boolean reloadSuccess = reloadPlugin(mainPlugin);

        if (reloadSuccess) {
            sender.sendMessage("§a插件重载成功！");
        } else {
            sender.sendMessage("§c插件重载失败！请查看控制台日志");
        }
        return true;
    }

    /**
     * 核心重载逻辑：卸载插件 → 重新加载插件
     * @param plugin 要重载的插件实例
     * @return 是否重载成功
     */
    private boolean reloadPlugin(JavaPlugin plugin) {
        PluginManager pluginManager = Bukkit.getPluginManager();
        String pluginName = plugin.getName();

        try {
            plugin.reloadConfig();
            // 1. 卸载当前插件
            pluginManager.disablePlugin(plugin);
            pluginManager.enablePlugin(plugin);

            // 2. 验证插件是否重新加载成功
            Plugin reloadedPlugin = pluginManager.getPlugin(pluginName);
            if (reloadedPlugin != null && reloadedPlugin.isEnabled()) {
                plugin.getLogger().info("插件 " + pluginName + " 重载成功");
                return true;
            } else {
                plugin.getLogger().severe("插件 " + pluginName + " 重载后未启用！");
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("插件重载异常：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}