package org.yuanshen.yuanshen;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class CharacterConfigDirectoryLoader {

    private static final String ACTIVE_CONFIG_ROOT = "juese/";
    private static final String ACTIVE_CONFIG_VERSION = "2.1-character-directory";

    private final Yuanshen plugin;
    private final ResourceConfigManager resourceConfigManager;

    public CharacterConfigDirectoryLoader(Yuanshen plugin, ResourceConfigManager resourceConfigManager) {
        this.plugin = plugin;
        this.resourceConfigManager = resourceConfigManager;
    }

    public FileConfiguration load() {
        File characterDirectory = new File(plugin.getDataFolder(), "juese");
        ensureDirectory(characterDirectory);
        seedBundledDefaults(characterDirectory);
        migrateLegacyDirectory(new File(plugin.getDataFolder(), "characters"), characterDirectory);

        YamlConfiguration merged = new YamlConfiguration();
        Map<String, File> loadedCharacters = new LinkedHashMap<>();
        List<String> loadedFiles = new ArrayList<>();

        File[] files = characterDirectory.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            List<File> sortedFiles = new ArrayList<>(List.of(files));
            sortedFiles.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
            for (File file : sortedFiles) {
                FileConfiguration config = loadYaml(file);
                String characterId = resolveCharacterId(file, config);
                if (characterId == null) {
                    plugin.getLogger().warning("已跳过角色配置 " + file.getName() + "：无法识别角色 ID，请补充 meta.character-id。");
                    continue;
                }

                File previous = loadedCharacters.putIfAbsent(characterId, file);
                if (previous != null) {
                    plugin.getLogger().warning("检测到重复角色配置 ID '" + characterId + "'："
                            + previous.getName() + " 与 " + file.getName() + "。已保留前者，忽略后者。");
                    continue;
                }

                mergeCharacterConfig(merged, characterId, file, config);
                loadedFiles.add(ACTIVE_CONFIG_ROOT + file.getName());
            }
        }

        merged.set("config-version", ACTIVE_CONFIG_VERSION);
        merged.set("active-config-root", ACTIVE_CONFIG_ROOT);
        merged.set("files", loadedFiles);
        return merged;
    }

    private void ensureDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建角色配置目录：" + directory.getAbsolutePath());
        }
    }

    private void seedBundledDefaults(File targetDirectory) {
        for (CharacterResourceEntry entry : bundledEntries()) {
            File targetFile = new File(targetDirectory, entry.fileName());
            if (targetFile.exists()) {
                continue;
            }
            String resourceName = ACTIVE_CONFIG_ROOT + entry.fileName();
            if (plugin.getResource(resourceName) == null) {
                plugin.getLogger().warning("缺少内置角色配置资源：" + resourceName);
                continue;
            }
            plugin.saveResource(resourceName, false);
        }
    }

    private void migrateLegacyDirectory(File legacyDirectory, File targetDirectory) {
        if (!legacyDirectory.exists() || !legacyDirectory.isDirectory()) {
            return;
        }

        File[] legacyFiles = legacyDirectory.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (legacyFiles == null || legacyFiles.length == 0) {
            return;
        }

        int migratedCount = 0;
        for (File legacyFile : legacyFiles) {
            File targetFile = new File(targetDirectory, resolveTargetFileName(legacyFile.getName()));
            if (targetFile.exists()) {
                continue;
            }
            try {
                Files.copy(legacyFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                migratedCount++;
            } catch (IOException ex) {
                throw new IllegalStateException("迁移旧角色配置失败：" + legacyFile.getAbsolutePath(), ex);
            }
        }

        if (migratedCount > 0) {
            plugin.getLogger().info("已将 " + migratedCount + " 个旧版 characters 角色配置迁移到 juese 目录。");
        }
    }

    private String resolveTargetFileName(String legacyName) {
        for (CharacterResourceEntry entry : bundledEntries()) {
            if (entry.matchesFileName(legacyName)) {
                return entry.fileName();
            }
        }
        return legacyName;
    }

    private FileConfiguration loadYaml(File file) {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("读取角色配置失败：" + file.getAbsolutePath(), ex);
        }
    }

    private String resolveCharacterId(File file, FileConfiguration config) {
        String configuredId = firstNonBlank(
                config.getString("meta.character-id"),
                config.getString("meta.id"),
                config.getString("character-id")
        );
        Optional<CharacterType> configuredType = CharacterType.fromId(configuredId);
        if (configuredType.isPresent()) {
            return configuredType.get().getId();
        }

        String fileName = file.getName();
        for (CharacterResourceEntry entry : bundledEntries()) {
            if (entry.matchesFileName(fileName)) {
                return entry.characterType().getId();
            }
        }

        CharacterType byAlias = resolveByAlias(stripExtension(fileName));
        if (byAlias != null) {
            return byAlias.getId();
        }

        byAlias = resolveByAlias(config.getString("meta.display-name"));
        if (byAlias != null) {
            return byAlias.getId();
        }

        byAlias = resolveByAlias(config.getString("lore-keyword"));
        if (byAlias != null) {
            return byAlias.getId();
        }

        for (String tag : config.getStringList("meta.tags")) {
            CharacterType type = resolveByAlias(tag);
            if (type != null) {
                return type.getId();
            }
        }
        return null;
    }

    private CharacterType resolveByAlias(String input) {
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return null;
        }

        for (CharacterResourceEntry entry : bundledEntries()) {
            if (entry.matchesAlias(normalized)) {
                return entry.characterType();
            }
        }

        for (CharacterType type : CharacterType.values()) {
            if (normalized.equals(normalize(type.getId()))
                    || normalized.equals(normalize(type.getDisplayName()))) {
                return type;
            }
            for (String tag : type.getTags()) {
                if (normalized.equals(normalize(tag))) {
                    return type;
                }
            }
        }
        return null;
    }

    private void mergeCharacterConfig(YamlConfiguration merged, String characterId, File sourceFile, FileConfiguration source) {
        String basePath = "skills." + characterId;
        for (String key : source.getKeys(true)) {
            if ("config-version".equals(key) || source.isConfigurationSection(key)) {
                continue;
            }
            merged.set(basePath + "." + key, source.get(key));
        }
        merged.set(basePath + ".meta.character-id", characterId);
        merged.set(basePath + ".meta.source-file", sourceFile.getName());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        return index < 0 ? fileName : fileName.substring(0, index);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<CharacterResourceEntry> bundledEntries() {
        return List.of(
                new CharacterResourceEntry(CharacterType.HUTAO, "胡桃.yml", List.of("hutao", "胡桃")),
                new CharacterResourceEntry(CharacterType.XIANGLING, "香菱.yml", List.of("xiangling", "香菱")),
                new CharacterResourceEntry(CharacterType.DILUC, "diluc.yml", List.of("diluc", "迪卢克")),
                new CharacterResourceEntry(CharacterType.KEQING, "keqing.yml", List.of("keqing", "刻晴")),
                new CharacterResourceEntry(CharacterType.NINGGUANG, "ningguang.yml", List.of("ningguang", "凝光")),
                new CharacterResourceEntry(CharacterType.TRAVELER_ANEMO, "旅行者-风.yml", List.of("traveler_anemo", "traveler-anemo", "旅行者", "旅行者-风")),
                new CharacterResourceEntry(CharacterType.YELAN, "夜兰.yml", List.of("yelan", "夜兰"))
        );
    }

    private record CharacterResourceEntry(CharacterType characterType, String fileName, List<String> aliases) {

        private boolean matchesFileName(String otherFileName) {
            return normalizeFile(fileName).equals(normalizeFile(otherFileName));
        }

        private boolean matchesAlias(String normalizedAlias) {
            if (normalizedAlias == null || normalizedAlias.isBlank()) {
                return false;
            }
            if (normalizedAlias.equals(normalizeFile(fileName))) {
                return true;
            }
            for (String alias : aliases) {
                if (normalizedAlias.equals(alias.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        private String normalizeFile(String value) {
            String stripped = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (stripped.endsWith(".yml")) {
                return stripped.substring(0, stripped.length() - 4);
            }
            return stripped;
        }
    }
}
