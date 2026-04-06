package org.yuanshen.yuanshen;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Map;

public class MobResistanceProfile {
    public static final String ALL_ELEMENT_RESISTANCE_KEY = "all_element_resistance";

    private final String fileName;
    private final EntityType entityType;
    private final String mythicMobId;
    private final String displayName;
    private final boolean enabled;
    private final double allElementResistance;
    private final double physicalResistance;
    private final Map<String, Double> elementResistances;
    private final boolean allowNegativeResistance;
    private final double minFinalResistance;
    private final double maxFinalResistance;
    private final double maxAllElementReduction;
    private final double maxSingleElementReduction;
    private final double maxPhysicalReduction;

    private MobResistanceProfile(String fileName,
                                 EntityType entityType,
                                 String mythicMobId,
                                 String displayName,
                                 boolean enabled,
                                 double allElementResistance,
                                 double physicalResistance,
                                 Map<String, Double> elementResistances,
                                 boolean allowNegativeResistance,
                                 double minFinalResistance,
                                 double maxFinalResistance,
                                 double maxAllElementReduction,
                                 double maxSingleElementReduction,
                                 double maxPhysicalReduction) {
        this.fileName = fileName;
        this.entityType = entityType;
        this.mythicMobId = mythicMobId;
        this.displayName = displayName;
        this.enabled = enabled;
        this.allElementResistance = allElementResistance;
        this.physicalResistance = physicalResistance;
        this.elementResistances = elementResistances;
        this.allowNegativeResistance = allowNegativeResistance;
        this.minFinalResistance = minFinalResistance;
        this.maxFinalResistance = maxFinalResistance;
        this.maxAllElementReduction = maxAllElementReduction;
        this.maxSingleElementReduction = maxSingleElementReduction;
        this.maxPhysicalReduction = maxPhysicalReduction;
    }

    public static MobResistanceProfile fromConfig(String fileName, FileConfiguration config) {
        String entityTypeName = trimToNull(config.getString("entity-type", ""));
        EntityType entityType = entityTypeName == null ? null : EntityType.valueOf(entityTypeName.trim().toUpperCase());
        String mythicMobId = trimToNull(config.getString("mythic-mob-id",
                config.getString("mythic-id",
                        config.getString("mob-id", ""))));
        if (entityType == null && mythicMobId == null) {
            throw new IllegalArgumentException("missing entity-type or mythic-mob-id");
        }

        Map<String, Double> elementResistances = new HashMap<>();
        elementResistances.put(ElementConstant.FIRE_KEY, config.getDouble("resistances.fire_resistance", 0.0));
        elementResistances.put(ElementConstant.WATER_KEY, config.getDouble("resistances.water_resistance", 0.0));
        elementResistances.put(ElementConstant.ICE_KEY, config.getDouble("resistances.ice_resistance", 0.0));
        elementResistances.put(ElementConstant.ELECTRO_KEY, config.getDouble("resistances.electro_resistance", 0.0));
        elementResistances.put(ElementConstant.ANEMO_KEY, config.getDouble("resistances.anemo_resistance", 0.0));
        elementResistances.put(ElementConstant.GEO_KEY, config.getDouble("resistances.geo_resistance", 0.0));
        elementResistances.put(ElementConstant.DENDRO_KEY, config.getDouble("resistances.dendro_resistance", 0.0));

        return new MobResistanceProfile(
                fileName,
                entityType,
                mythicMobId,
                config.getString("display-name", entityType != null ? entityType.name() : mythicMobId),
                config.getBoolean("enabled", true),
                config.getDouble("resistances.all_element_resistance", 0.0),
                config.getDouble("resistances.physical_resistance", 0.0),
                elementResistances,
                config.getBoolean("limits.allow_negative_resistance", false),
                config.getDouble("limits.min_final_resistance", 0.0),
                config.getDouble("limits.max_final_resistance", 0.9),
                config.getDouble("reduction-limits.max_all_element_reduction", 0.9),
                config.getDouble("reduction-limits.max_single_element_reduction", 0.9),
                config.getDouble("reduction-limits.max_physical_reduction", 0.9)
        );
    }

    public String getFileName() {
        return fileName;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public String getMythicMobId() {
        return mythicMobId;
    }

    public boolean matchesMythicMob() {
        return mythicMobId != null && !mythicMobId.isBlank();
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getAllElementResistance() {
        return allElementResistance;
    }

    public double getPhysicalResistance() {
        return physicalResistance;
    }

    public double getElementResistance(String elementKey) {
        return elementResistances.getOrDefault(elementKey, 0.0);
    }

    public boolean isAllowNegativeResistance() {
        return allowNegativeResistance;
    }

    public double getMinFinalResistance() {
        return minFinalResistance;
    }

    public double getMaxFinalResistance() {
        return maxFinalResistance;
    }

    public double getMaxAllElementReduction() {
        return maxAllElementReduction;
    }

    public double getMaxSingleElementReduction() {
        return maxSingleElementReduction;
    }

    public double getMaxPhysicalReduction() {
        return maxPhysicalReduction;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
