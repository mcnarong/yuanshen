package org.yuanshen.yuanshen;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

public class CharacterSkillConfig {

    private final Yuanshen plugin;
    private final CharacterType characterType;
    private final String basePath;

    public CharacterSkillConfig(Yuanshen plugin, CharacterType characterType) {
        this.plugin = plugin;
        this.characterType = characterType;
        this.basePath = "skills." + characterType.getId();
    }

    public CharacterType getCharacterType() {
        return characterType;
    }

    public String path(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return basePath;
        }
        return basePath + "." + relativePath;
    }

    public boolean getBoolean(String relativePath, boolean defaultValue) {
        FileConfiguration config = currentConfig();
        return config != null ? config.getBoolean(path(relativePath), defaultValue) : defaultValue;
    }

    public int getInt(String relativePath, int defaultValue) {
        FileConfiguration config = currentConfig();
        return config != null ? config.getInt(path(relativePath), defaultValue) : defaultValue;
    }

    public long getLong(String relativePath, long defaultValue) {
        FileConfiguration config = currentConfig();
        return config != null ? config.getLong(path(relativePath), defaultValue) : defaultValue;
    }

    public double getDouble(String relativePath, double defaultValue) {
        FileConfiguration config = currentConfig();
        return config != null ? config.getDouble(path(relativePath), defaultValue) : defaultValue;
    }

    public String getString(String relativePath, String defaultValue) {
        FileConfiguration config = currentConfig();
        return config != null ? config.getString(path(relativePath), defaultValue) : defaultValue;
    }

    public boolean contains(String relativePath) {
        FileConfiguration config = currentConfig();
        return config != null && config.contains(path(relativePath));
    }

    public String getAttachmentGroup(String sectionPath, String fallbackGroup) {
        String safeFallback = sanitizeAttachmentKey(fallbackGroup);
        if (sectionPath == null || sectionPath.isBlank()) {
            return safeFallback;
        }
        String configured = getString(sectionPath + ".attachment.group", null);
        return configured == null || configured.isBlank() ? safeFallback : sanitizeAttachmentKey(configured);
    }

    public double getAttachmentAuraAmount(String sectionPath, double fallbackValue) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return fallbackValue;
        }
        String auraPath = sectionPath + ".attachment.aura";
        if (!contains(auraPath)) {
            return fallbackValue;
        }
        return getDouble(auraPath, fallbackValue);
    }

    public boolean getAttachmentIcdEnabled(String sectionPath, boolean fallbackValue) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return fallbackValue;
        }
        String path = sectionPath + ".attachment.icd.enabled";
        return contains(path) ? getBoolean(path, fallbackValue) : fallbackValue;
    }

    public long getAttachmentIcdWindowMs(String sectionPath, long fallbackValue) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return fallbackValue;
        }
        String path = sectionPath + ".attachment.icd.window-ms";
        return contains(path) ? getLong(path, fallbackValue) : fallbackValue;
    }

    public int getAttachmentIcdHits(String sectionPath, int fallbackValue) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return fallbackValue;
        }
        String path = sectionPath + ".attachment.icd.hits";
        return contains(path) ? getInt(path, fallbackValue) : fallbackValue;
    }

    public int getAttachmentIcdOffset(String sectionPath, int fallbackValue) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return fallbackValue;
        }
        String path = sectionPath + ".attachment.icd.offset";
        return contains(path) ? getInt(path, fallbackValue) : fallbackValue;
    }

    public String messagePath(String messageKey) {
        return path("messages." + messageKey);
    }

    public String notificationPath(String notificationKey) {
        return path("notifications." + notificationKey);
    }

    private String sanitizeAttachmentKey(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        String sanitized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private FileConfiguration currentConfig() {
        return plugin.getSkillsConfig();
    }
}
