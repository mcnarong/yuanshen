package org.yuanshen.yuanshen;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class YuanshenItemIdentity {

    private static final int SCHEMA_VERSION = 1;
    private static final String KIND_CHARACTER = "character";
    private static final String KIND_WEAPON = "weapon";
    private static final String KIND_CONSTELLATION_STAR = "constellation_star";

    private final NamespacedKey schemaKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey idKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey refinementKey;
    private final NamespacedKey constellationKey;
    private final NamespacedKey weaponUuidKey;

    public YuanshenItemIdentity(Yuanshen plugin) {
        this.schemaKey = new NamespacedKey(plugin, "item_schema");
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.idKey = new NamespacedKey(plugin, "item_id");
        this.levelKey = new NamespacedKey(plugin, "item_level");
        this.refinementKey = new NamespacedKey(plugin, "item_refinement");
        this.constellationKey = new NamespacedKey(plugin, "item_constellation");
        this.weaponUuidKey = new NamespacedKey(plugin, "weapon_uuid");
    }

    public void applyCharacter(ItemMeta meta, CharacterInstance instance) {
        if (meta == null || instance == null || instance.definition() == null) {
            return;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(schemaKey, PersistentDataType.INTEGER, SCHEMA_VERSION);
        container.set(kindKey, PersistentDataType.STRING, KIND_CHARACTER);
        container.set(idKey, PersistentDataType.STRING, instance.definition().id());
        container.set(levelKey, PersistentDataType.INTEGER, instance.level());
        container.set(constellationKey, PersistentDataType.INTEGER, instance.constellation());
        container.remove(refinementKey);
        container.remove(weaponUuidKey);
    }

    public CharacterData readCharacter(ItemMeta meta) {
        if (!hasKind(meta, KIND_CHARACTER)) {
            return null;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String id = container.get(idKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) {
            return null;
        }
        return new CharacterData(
                id,
                readInt(container, levelKey, 1),
                readInt(container, constellationKey, 0)
        );
    }

    public void applyWeapon(ItemMeta meta, WeaponInstance instance) {
        if (meta == null || instance == null || instance.definition() == null) {
            return;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(schemaKey, PersistentDataType.INTEGER, SCHEMA_VERSION);
        container.set(kindKey, PersistentDataType.STRING, KIND_WEAPON);
        container.set(idKey, PersistentDataType.STRING, instance.definition().id());
        container.set(levelKey, PersistentDataType.INTEGER, instance.level());
        container.set(refinementKey, PersistentDataType.INTEGER, instance.refinement());
        container.remove(constellationKey);
        String weaponUuid = container.get(weaponUuidKey, PersistentDataType.STRING);
        if (weaponUuid == null || weaponUuid.isBlank()) {
            weaponUuid = UUID.randomUUID().toString();
        }
        container.set(weaponUuidKey, PersistentDataType.STRING, weaponUuid);
    }

    public WeaponData readWeapon(ItemMeta meta) {
        if (!hasKind(meta, KIND_WEAPON)) {
            return null;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String id = container.get(idKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) {
            return null;
        }
        return new WeaponData(
                id,
                readInt(container, levelKey, 1),
                readInt(container, refinementKey, 1),
                readString(container, weaponUuidKey, null)
        );
    }

    public void applyConstellationStar(ItemMeta meta) {
        if (meta == null) {
            return;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(schemaKey, PersistentDataType.INTEGER, SCHEMA_VERSION);
        container.set(kindKey, PersistentDataType.STRING, KIND_CONSTELLATION_STAR);
        container.remove(idKey);
        container.remove(levelKey);
        container.remove(refinementKey);
        container.remove(constellationKey);
        container.remove(weaponUuidKey);
    }

    public boolean isConstellationStar(ItemMeta meta) {
        return hasKind(meta, KIND_CONSTELLATION_STAR);
    }

    private boolean hasKind(ItemMeta meta, String expectedKind) {
        if (meta == null || expectedKind == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Integer schema = container.get(schemaKey, PersistentDataType.INTEGER);
        if (schema == null || schema != SCHEMA_VERSION) {
            return false;
        }
        String kind = container.get(kindKey, PersistentDataType.STRING);
        return expectedKind.equals(kind);
    }

    private int readInt(PersistentDataContainer container, NamespacedKey key, int fallback) {
        if (container == null || key == null) {
            return fallback;
        }
        Integer value = container.get(key, PersistentDataType.INTEGER);
        return value == null ? fallback : value;
    }

    private String readString(PersistentDataContainer container, NamespacedKey key, String fallback) {
        if (container == null || key == null) {
            return fallback;
        }
        String value = container.get(key, PersistentDataType.STRING);
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CharacterData(String id, int level, int constellation) {
    }

    public record WeaponData(String id, int level, int refinement, String weaponUuid) {
    }
}
