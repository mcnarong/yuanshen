package org.yuanshen.yuanshen;

import java.util.Locale;
import java.util.Optional;

public enum CharacterWeaponType {
    POLEARM("polearm", "长枪"),
    SWORD("sword", "单手剑"),
    CLAYMORE("claymore", "双手剑"),
    CATALYST("catalyst", "法器"),
    BOW("bow", "弓");

    private final String id;
    private final String displayName;

    CharacterWeaponType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Optional<CharacterWeaponType> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (CharacterWeaponType type : values()) {
            if (type.id.equals(normalized)
                    || type.displayName.equals(id.trim())
                    || matchesAlias(type, normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesAlias(CharacterWeaponType type, String normalized) {
        return switch (type) {
            case POLEARM -> "长柄武器".equals(normalized) || "长枪".equals(normalized) || "枪".equals(normalized);
            case SWORD -> "单手剑".equals(normalized) || "剑".equals(normalized);
            case CLAYMORE -> "双手剑".equals(normalized) || "大剑".equals(normalized);
            case CATALYST -> "法器".equals(normalized);
            case BOW -> "弓".equals(normalized) || "弓箭".equals(normalized);
        };
    }
}
