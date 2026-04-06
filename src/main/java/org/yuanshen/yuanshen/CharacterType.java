package org.yuanshen.yuanshen;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum CharacterType {
    HUTAO("hutao", "胡桃", ElementConstant.FIRE_KEY, CharacterWeaponType.POLEARM,
            "火元素", "长柄角色", "pyro", "polearm"),
    XIANGLING("xiangling", "香菱", ElementConstant.FIRE_KEY, CharacterWeaponType.POLEARM,
            "火元素", "长柄角色", "pyro", "polearm", "料理"),
    DILUC("diluc", "迪卢克", ElementConstant.FIRE_KEY, CharacterWeaponType.CLAYMORE,
            "火元素", "双手剑角色", "pyro", "claymore", "迪卢克"),
    KEQING("keqing", "刻晴", ElementConstant.ELECTRO_KEY, CharacterWeaponType.SWORD,
            "雷元素", "单手剑角色", "electro", "sword", "刻晴"),
    NINGGUANG("ningguang", "凝光", ElementConstant.GEO_KEY, CharacterWeaponType.CATALYST,
            "岩元素", "法器角色", "geo", "catalyst", "凝光"),
    TRAVELER_ANEMO("traveler_anemo", "旅行者-风", ElementConstant.ANEMO_KEY, CharacterWeaponType.SWORD,
            "风元素", "单手剑角色", "anemo", "sword", "旅行者", "旅行者-风"),
    YELAN("yelan", "夜兰", ElementConstant.WATER_KEY, CharacterWeaponType.BOW,
            "水元素", "弓角色", "hydro", "bow", "夜兰", "夜阑");

    private final String id;
    private final String displayName;
    private final String elementKey;
    private final CharacterWeaponType weaponType;
    private final Set<String> tags;

    CharacterType(String id, String displayName, String elementKey, CharacterWeaponType weaponType, String... tags) {
        this.id = id;
        this.displayName = displayName;
        this.elementKey = elementKey;
        this.weaponType = weaponType;
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(tags)));
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getElementKey() {
        return elementKey;
    }

    public CharacterWeaponType getWeaponType() {
        return weaponType;
    }

    public boolean isPolearm() {
        return weaponType == CharacterWeaponType.POLEARM;
    }

    public boolean isSword() {
        return weaponType == CharacterWeaponType.SWORD;
    }

    public boolean isBow() {
        return weaponType == CharacterWeaponType.BOW;
    }

    public boolean isClaymore() {
        return weaponType == CharacterWeaponType.CLAYMORE;
    }

    public boolean isCatalyst() {
        return weaponType == CharacterWeaponType.CATALYST;
    }

    public Set<String> getTags() {
        return tags;
    }

    public boolean hasTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        for (String value : tags) {
            if (value != null && value.trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static Optional<CharacterType> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (CharacterType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
