package org.yuanshen.yuanshen;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ConfigParser {
    private final Yuanshen plugin;
    private final Map<String, String> customVariables = new HashMap<>();
    private final Map<String, CachedValue> cache = new HashMap<>();

    private Class<?> placeholderAPIClass;
    private Method setPlaceholdersMethod;

    public ConfigParser(Yuanshen plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("exp4j 表达式解析器已就绪（兼容 Java 21）");

        loadCustomVariables();
        initPlaceholderAPI();
    }

    private void initPlaceholderAPI() {
        placeholderAPIClass = null;
        setPlaceholdersMethod = null;
        if (!plugin.hasPlaceholderAPI()) {
            plugin.warnOnce("dependency:papi-missing", "未检测到 PlaceholderAPI，相关占位符将不会被解析。");
            return;
        }
        try {
            placeholderAPIClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = placeholderAPIClass.getMethod("setPlaceholders", Player.class, String.class);
            plugin.getLogger().info("PlaceholderAPI 反射接口加载成功");
        } catch (Exception e) {
            plugin.warnOnce("dependency:papi-reflection-failed", "加载 PlaceholderAPI 反射接口失败: " + e.getMessage());
        }
    }

    private void loadCustomVariables() {
        customVariables.clear();
        if (plugin.getConfig().contains("custom_variables")
                && plugin.getConfig().getConfigurationSection("custom_variables") != null) {
            for (String key : plugin.getConfig().getConfigurationSection("custom_variables").getKeys(false)) {
                String value = plugin.getConfig().getString("custom_variables." + key);
                if (value != null) {
                    customVariables.put(key, value);
                }
            }
        }
    }

    public double parseDouble(String path, Player player, double defaultValue) {
        String value = plugin.getConfig().getString(path);
        if (value == null) {
            warnMissingConfig(path, String.valueOf(defaultValue));
            return defaultValue;
        }
        double result = parseExpression(value, player, defaultValue);
        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("解析配置 " + path + " = '" + value + "' -> " + result + "（默认值=" + defaultValue + "）");
        }
        return result;
    }

    public int parseInt(String path, Player player, int defaultValue) {
        String value = plugin.getConfig().getString(path);
        if (value == null) {
            warnMissingConfig(path, String.valueOf(defaultValue));
            return defaultValue;
        }
        return (int) Math.round(parseExpression(value, player, defaultValue));
    }

    public String parseString(Player player, String path, String defaultValue) {
        String value = plugin.getConfig().getString(path);
        if (value == null) {
            warnMissingConfig(path, defaultValue);
            return defaultValue;
        }

        if (player != null && plugin.hasPlaceholderAPI() && setPlaceholdersMethod != null) {
            try {
                value = (String) setPlaceholdersMethod.invoke(null, player, value);
            } catch (Exception e) {
                if (plugin.isDebugEnabled()) {
                    plugin.warnOnce("papi-replace:" + path, "替换 PAPI 占位符失败: " + e.getMessage());
                }
            }
        }

        value = replaceCustomVariables(value);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("解析配置 " + path + " = '" + value + "'");
        }
        return value;
    }

    public boolean parseBoolean(String path, Player player, boolean defaultValue) {
        String value = plugin.getConfig().getString(path);
        if (value == null) {
            warnMissingConfig(path, String.valueOf(defaultValue));
            return defaultValue;
        }

        try {
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equals("1")) {
                return true;
            }
            if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no") || value.equals("0")) {
                return false;
            }

            double result = parseExpression(value, player, defaultValue ? 1.0 : 0.0);
            return result != 0;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public double parseExpression(String expression, Player player, double defaultValue) {
        if (expression == null || expression.isEmpty()) {
            return defaultValue;
        }

        String cacheKey = expression + "_" + (player != null ? player.getUniqueId() : "null");
        CachedValue cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 1000) {
            return cached.value;
        }

        try {
            String processed = replaceCustomVariables(expression);

            if (player != null && plugin.hasPlaceholderAPI() && setPlaceholdersMethod != null) {
                try {
                    processed = (String) setPlaceholdersMethod.invoke(null, player, processed);
                } catch (Exception e) {
                    if (plugin.isDebugEnabled()) {
                        plugin.warnOnce("papi-expression:" + expression, "替换表达式中的 PAPI 占位符失败: " + e.getMessage());
                    }
                }
            }

            processed = processed.trim();

            if (processed.matches("-?\\d+(\\.\\d+)?")) {
                double value = Double.parseDouble(processed);
                cache.put(cacheKey, new CachedValue(value, System.currentTimeMillis()));
                return value;
            }

            Expression compiled = new ExpressionBuilder(processed).build();
            double result = compiled.evaluate();

            cache.put(cacheKey, new CachedValue(result, System.currentTimeMillis()));
            return result;
        } catch (Exception ex) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().warning("解析表达式失败：" + expression + " -> " + ex.getMessage());
            }
            return defaultValue;
        }
    }

    private void warnMissingConfig(String path, String defaultValue) {
        if (plugin.shouldWarnMissingConfig()) {
            plugin.warnOnce("missing-config:" + path, "配置路径 " + path + " 不存在，已使用默认值 " + defaultValue);
        }
    }

    private String replaceCustomVariables(String expression) {
        String result = expression;
        for (Map.Entry<String, String> entry : customVariables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public void clearCache() {
        cache.clear();
    }

    public void reload() {
        clearCache();
        loadCustomVariables();
        initPlaceholderAPI();
    }

    public int invalidatePlayer(Player player) {
        if (player == null) {
            return 0;
        }
        String playerSuffix = "_" + player.getUniqueId();
        int removed = 0;
        Iterator<Map.Entry<String, CachedValue>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedValue> entry = iterator.next();
            if (entry.getKey().endsWith(playerSuffix)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static class CachedValue {
        double value;
        long timestamp;

        CachedValue(double value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
}
