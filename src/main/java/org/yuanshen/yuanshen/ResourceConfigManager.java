package org.yuanshen.yuanshen;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ResourceConfigManager {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter RECORD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RECORDS = 7;

    private final JavaPlugin plugin;
    private final File migrationFile;
    private final YamlConfiguration migrationConfig;

    public ResourceConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.migrationFile = new File(plugin.getDataFolder(), "config-migrations.yml");
        this.migrationConfig = loadMigrationConfig();
    }

    public void loadMainConfig(String resourceName, List<String> requiredPaths) {
        File targetFile = new File(plugin.getDataFolder(), resourceName);
        prepareConfigFile(resourceName, targetFile, requiredPaths);
        plugin.reloadConfig();
    }

    public FileConfiguration loadAuxiliaryConfig(String resourceName, File targetFile, List<String> requiredPaths) {
        PreparedConfig prepared = prepareConfigFile(resourceName, targetFile, requiredPaths);
        return prepared.configuration();
    }

    private PreparedConfig prepareConfigFile(String resourceName, File targetFile, List<String> requiredPaths) {
        ensureDataFolder();

        YamlConfiguration defaults = loadBundledDefaults(resourceName);
        String bundledVersion = readConfigVersion(defaults);

        if (!targetFile.exists()) {
            plugin.saveResource(resourceName, false);
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(targetFile);
            configuration.setDefaults(defaults);
            recordMigration(resourceName, "bootstrap", "none", bundledVersion, "首次生成默认配置。", null);
            return new PreparedConfig(configuration, bundledVersion);
        }

        RawConfig rawConfig = readRawConfig(targetFile);
        YamlConfiguration current = parseExistingConfig(resourceName, targetFile, rawConfig.text(), defaults);
        if (current == null) {
            return restoreFromBundledResource(resourceName, targetFile, defaults, bundledVersion,
                    "repaired-invalid", "配置文件不是合法 YAML，已恢复为默认版本。", null);
        }

        String currentVersion = readConfigVersion(current);
        List<String> missingRequiredPaths = findMissingPaths(current, requiredPaths);
        boolean defaultsAdded = hasMissingDefaultPaths(current, defaults);
        boolean versionChanged = bundledVersion != null && !bundledVersion.equals(currentVersion);

        String backupName = null;
        if (versionChanged || !missingRequiredPaths.isEmpty()) {
            backupName = backupConfig(targetFile, versionChanged ? "version" : "missing");
        }

        current.setDefaults(defaults);
        current.options().copyDefaults(true);

        if (bundledVersion != null) {
            current.set("config-version", bundledVersion);
        }

        if (rawConfig.hadBom() || defaultsAdded || versionChanged || !missingRequiredPaths.isEmpty()) {
            saveConfiguration(targetFile, current);
        }

        if (rawConfig.hadBom()) {
            plugin.getLogger().info("已移除 " + resourceName + " 的 UTF-8 BOM 头。");
        }
        if (defaultsAdded) {
            plugin.getLogger().info("已为 " + resourceName + " 自动补齐缺失的默认配置项。");
        }
        if (!missingRequiredPaths.isEmpty()) {
            String message = "检测到 " + resourceName + " 缺少关键节点："
                    + String.join(", ", missingRequiredPaths) + "，已自动补齐并保留原文件备份。";
            plugin.getLogger().warning(message);
            recordMigration(resourceName, "repaired-missing", currentVersion, bundledVersion, message, backupName);
        } else if (versionChanged) {
            String message = resourceName + " 配置版本从 " + safeVersion(currentVersion)
                    + " 升级到 " + safeVersion(bundledVersion) + "，已补齐新版本默认项。";
            plugin.getLogger().info(message);
            recordMigration(resourceName, "version-sync", currentVersion, bundledVersion, message, backupName);
        }

        return new PreparedConfig(current, bundledVersion);
    }

    private void ensureDataFolder() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
    }

    private YamlConfiguration loadBundledDefaults(String resourceName) {
        InputStream resourceStream = plugin.getResource(resourceName);
        if (resourceStream == null) {
            throw new IllegalStateException("缺少内置资源文件：" + resourceName);
        }

        try (Reader reader = new InputStreamReader(resourceStream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("读取内置资源失败：" + resourceName, e);
        }
    }

    private RawConfig readRawConfig(File targetFile) {
        try {
            String text = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);
            boolean hadBom = !text.isEmpty() && text.charAt(0) == '\uFEFF';
            if (hadBom) {
                text = text.substring(1);
            }
            return new RawConfig(text, hadBom);
        } catch (IOException e) {
            throw new IllegalStateException("读取配置文件失败：" + targetFile.getAbsolutePath(), e);
        }
    }

    private YamlConfiguration parseExistingConfig(String resourceName, File targetFile,
                                                  String rawText, YamlConfiguration defaults) {
        YamlConfiguration current = new YamlConfiguration();
        current.setDefaults(defaults);
        try {
            current.loadFromString(rawText);
            return current;
        } catch (InvalidConfigurationException e) {
            String backupName = backupConfig(targetFile, "invalid");
            String message = resourceName + " 不是合法的 YAML，已恢复默认配置。";
            plugin.getLogger().warning(message);
            recordMigration(resourceName, "backup-invalid", "unknown", readConfigVersion(defaults), message, backupName);
            return null;
        }
    }

    private PreparedConfig restoreFromBundledResource(String resourceName, File targetFile, YamlConfiguration defaults,
                                                      String bundledVersion, String action, String note, String backupFile) {
        plugin.saveResource(resourceName, true);
        YamlConfiguration restored = YamlConfiguration.loadConfiguration(targetFile);
        restored.setDefaults(defaults);
        if (bundledVersion != null) {
            restored.set("config-version", bundledVersion);
            saveConfiguration(targetFile, restored);
        }
        recordMigration(resourceName, action, "unknown", bundledVersion, note, backupFile);
        return new PreparedConfig(restored, bundledVersion);
    }

    private List<String> findMissingPaths(FileConfiguration configuration, List<String> paths) {
        List<String> missing = new ArrayList<>();
        for (String path : paths) {
            if (!configuration.contains(path)) {
                missing.add(path);
            }
        }
        return missing;
    }

    private boolean hasMissingDefaultPaths(FileConfiguration configuration, FileConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (!configuration.isSet(key)) {
                return true;
            }
        }
        return false;
    }

    private String readConfigVersion(FileConfiguration configuration) {
        String version = configuration.getString("config-version");
        if (version == null || version.isBlank()) {
            return "legacy";
        }
        return version.trim();
    }

    private void saveConfiguration(File targetFile, YamlConfiguration configuration) {
        try {
            configuration.save(targetFile);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置文件失败：" + targetFile.getAbsolutePath(), e);
        }
    }

    private String backupConfig(File sourceFile, String reason) {
        String backupName = sourceFile.getName() + "." + reason + "." + BACKUP_TIME.format(LocalDateTime.now()) + ".bak";
        File backupFile = new File(sourceFile.getParentFile(), backupName);
        try {
            Files.copy(sourceFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("已备份 " + sourceFile.getName() + " -> " + backupFile.getName());
            return backupFile.getName();
        } catch (IOException e) {
            throw new IllegalStateException("备份配置文件失败：" + sourceFile.getAbsolutePath(), e);
        }
    }

    private YamlConfiguration loadMigrationConfig() {
        ensureDataFolder();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(migrationFile);
        if (!migrationFile.exists()) {
            config.set("schema-version", 1);
            saveMigrationConfig(config);
        }
        return config;
    }

    private void saveMigrationConfig(YamlConfiguration config) {
        try {
            config.save(migrationFile);
        } catch (IOException e) {
            throw new IllegalStateException("保存迁移记录失败：" + migrationFile.getAbsolutePath(), e);
        }
    }

    private void recordMigration(String resourceName, String action, String fromVersion,
                                 String toVersion, String note, String backupFile) {
        String time = RECORD_TIME.format(LocalDateTime.now());
        String key = buildRecordKey();
        String base = "records." + key;

        migrationConfig.set("schema-version", 1);
        migrationConfig.set(base + ".time", time);
        migrationConfig.set(base + ".resource", resourceName);
        migrationConfig.set(base + ".action", action);
        migrationConfig.set(base + ".from-version", safeVersion(fromVersion));
        migrationConfig.set(base + ".to-version", safeVersion(toVersion));
        migrationConfig.set(base + ".note", note);
        migrationConfig.set(base + ".backup-file", backupFile);

        trimMigrationRecords(MAX_RECORDS);
        saveMigrationConfig(migrationConfig);
    }

    private String buildRecordKey() {
        String base = String.valueOf(System.currentTimeMillis());
        String candidate = base;
        int suffix = 1;
        while (migrationConfig.contains("records." + candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private void trimMigrationRecords(int maxRecords) {
        if (!migrationConfig.contains("records") || migrationConfig.getConfigurationSection("records") == null) {
            return;
        }

        List<String> keys = new ArrayList<>(migrationConfig.getConfigurationSection("records").getKeys(false));
        keys.sort(Comparator.reverseOrder());
        for (int i = maxRecords; i < keys.size(); i++) {
            migrationConfig.set("records." + keys.get(i), null);
        }
    }

    private String safeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        return version;
    }

    private record RawConfig(String text, boolean hadBom) {
    }

    private record PreparedConfig(YamlConfiguration configuration, String version) {
    }
}