package org.yuanshen.yuanshen;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * 原神元素反应插件主类
 * 注册所有元素监听器，加载配置
 */
public final class Yuanshen extends JavaPlugin {

    @Override
    public void onEnable() {
        // 1. 保存默认配置文件（如果不存在）
        saveDefaultConfig();
        
        // 2. 注册所有元素监听器
        getServer().getPluginManager().registerEvents(new FireElementListener(this), this);
        getServer().getPluginManager().registerEvents(new WaterElementListener(this), this);
        getServer().getPluginManager().registerEvents(new IceElementListener(this), this);
        getServer().getPluginManager().registerEvents(new ElectroElementListener(this), this);
        getServer().getPluginManager().registerEvents(new AnemoElementListener(this), this);
        getServer().getPluginManager().registerEvents(new GeoElementListener(this), this);
        getServer().getPluginManager().registerEvents(new DendroElementListener(this), this);
        new reload(this);
        
        // 3. 打印启用日志
        getLogger().info("=== 原神元素反应插件已启用 ===");
        getLogger().info("已加载元素：火、水、冰、雷、风、岩、草");
        getLogger().info("配置文件路径：plugins/Yuanshen/config.yml");
    }

    @Override
    public void onDisable() {
        // 打印禁用日志
        getLogger().info("=== 原神元素反应插件已禁用 ===");
    }
}