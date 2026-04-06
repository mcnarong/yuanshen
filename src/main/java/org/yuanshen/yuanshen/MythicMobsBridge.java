package org.yuanshen.yuanshen;

import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public class MythicMobsBridge {

    private final Yuanshen plugin;
    private final boolean available;
    private final Object apiHelper;
    private final Method getMythicMobInstanceMethod;
    private final Method getMobTypeMethod;

    public MythicMobsBridge(Yuanshen plugin) {
        this.plugin = plugin;

        Object resolvedHelper = null;
        Method resolvedGetMythicMobInstance = null;
        Method resolvedGetMobType = null;
        boolean resolvedAvailable = false;

        if (plugin.hasMythicMobs()) {
            try {
                Plugin mythicPlugin = plugin.getServer().getPluginManager().getPlugin("MythicMobs");
                if (mythicPlugin != null && mythicPlugin.isEnabled()) {
                    Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                    Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
                    resolvedHelper = mythicBukkitClass.getMethod("getAPIHelper").invoke(mythicBukkit);
                    resolvedGetMythicMobInstance = resolvedHelper.getClass().getMethod("getMythicMobInstance", org.bukkit.entity.Entity.class);

                    Class<?> activeMobClass = Class.forName("io.lumine.mythic.core.mobs.ActiveMob");
                    resolvedGetMobType = activeMobClass.getMethod("getMobType");
                    resolvedAvailable = true;
                }
            } catch (ReflectiveOperationException ex) {
                if (plugin.shouldLogDependencyFallbacks()) {
                    plugin.warnOnce("dependency:mythicmobs-bridge-failed",
                            "MythicMobs 已检测到，但桥接初始化失败，将回退到原版实体抗性匹配: " + ex.getMessage());
                }
            }
        }

        this.apiHelper = resolvedHelper;
        this.getMythicMobInstanceMethod = resolvedGetMythicMobInstance;
        this.getMobTypeMethod = resolvedGetMobType;
        this.available = resolvedAvailable;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getMythicMobId(LivingEntity entity) {
        if (!available || entity == null) {
            return null;
        }

        try {
            Object activeMob = getMythicMobInstanceMethod.invoke(apiHelper, entity);
            if (activeMob == null) {
                return null;
            }
            Object mobType = getMobTypeMethod.invoke(activeMob);
            if (!(mobType instanceof String text)) {
                return null;
            }
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (ReflectiveOperationException ex) {
            if (plugin.shouldLogDependencyFallbacks()) {
                plugin.warnOnce("dependency:mythicmobs-lookup-failed",
                        "MythicMobs 实体识别失败，将回退到原版实体类型匹配: " + ex.getMessage());
            }
            return null;
        }
    }
}
