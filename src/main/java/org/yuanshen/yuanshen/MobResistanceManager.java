package org.yuanshen.yuanshen;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MobResistanceManager {
    private static final String FILE_VERSION = "1.0-beta";
    private static final String PHYSICAL_REDUCTION_KEY = PlayerStats.PHYSICAL_KEY;

    private final Yuanshen plugin;
    private final ResourceConfigManager resourceConfigManager;
    private final File mobDirectory;
    private final Map<EntityType, MobResistanceProfile> entityProfiles = new EnumMap<>(EntityType.class);
    private final Map<String, MobResistanceProfile> mythicProfiles = new HashMap<>();
    private final Map<UUID, Map<String, Double>> activeReductions = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> activeReductionTasks = new ConcurrentHashMap<>();

    public MobResistanceManager(Yuanshen plugin, ResourceConfigManager resourceConfigManager) {
        this.plugin = plugin;
        this.resourceConfigManager = resourceConfigManager;
        this.mobDirectory = new File(plugin.getDataFolder(), "mobs");
    }

    public void reloadConfigs() {
        entityProfiles.clear();
        mythicProfiles.clear();
        ensureMobDirectory();
        bootstrapDefaultsIfDirectoryEmpty();
        refreshBundledDefaultsIfPresent();
        loadProfilesFromDirectory();
    }

    public void clearAllRuntimeState() {
        for (List<BukkitTask> tasks : activeReductionTasks.values()) {
            for (BukkitTask task : tasks) {
                task.cancel();
            }
        }
        activeReductionTasks.clear();
        activeReductions.clear();
    }

    public double applyResistance(LivingEntity target, double damage, String damageElement) {
        if (target == null || damage <= 0 || target instanceof Player) {
            return damage;
        }

        MobResistanceProfile profile = resolveProfile(target);
        if (profile == null || !profile.isEnabled()) {
            return damage;
        }

        String normalizedKey = normalizeResistanceKey(damageElement);
        double finalResistance = getFinalResistance(profile, target.getUniqueId(), normalizedKey);
        return damage * Math.max(0.0, 1.0 - finalResistance);
    }

    public void applyAllElementResistanceReduction(LivingEntity target, double amount, int durationTicks) {
        applyResistanceReduction(target, MobResistanceProfile.ALL_ELEMENT_RESISTANCE_KEY, amount, durationTicks);
    }

    public void applyPhysicalResistanceReduction(LivingEntity target, double amount, int durationTicks) {
        applyResistanceReduction(target, PHYSICAL_REDUCTION_KEY, amount, durationTicks);
    }

    public void applyElementResistanceReduction(LivingEntity target, String elementKey, double amount, int durationTicks) {
        applyResistanceReduction(target, elementKey, amount, durationTicks);
    }

    public void applyResistanceReduction(LivingEntity target, String resistanceKey, double amount, int durationTicks) {
        if (target == null || amount <= 0) {
            return;
        }

        MobResistanceProfile profile = resolveProfile(target);
        if (profile == null || !profile.isEnabled()) {
            return;
        }

        String normalizedKey = normalizeReductionKey(resistanceKey);
        if (normalizedKey == null) {
            plugin.getLogger().warning("Ignored invalid resistance reduction key: " + resistanceKey);
            return;
        }

        UUID targetId = target.getUniqueId();
        activeReductions
                .computeIfAbsent(targetId, ignored -> new ConcurrentHashMap<>())
                .merge(normalizedKey, amount, Double::sum);

        if (durationTicks > 0) {
            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = new BukkitRunnable() {
                @Override
                public void run() {
                    removeReduction(targetId, normalizedKey, amount);
                    unregisterReductionTask(targetId, holder[0]);
                }
            }.runTaskLater(plugin, durationTicks);
            registerReductionTask(targetId, holder[0]);
        }
    }

    public void clearResistanceReductions(LivingEntity target) {
        if (target == null) {
            return;
        }
        UUID targetId = target.getUniqueId();
        List<BukkitTask> tasks = activeReductionTasks.remove(targetId);
        if (tasks != null) {
            for (BukkitTask task : tasks) {
                task.cancel();
            }
        }
        activeReductions.remove(targetId);
    }

    private void ensureMobDirectory() {
        if (!mobDirectory.exists()) {
            mobDirectory.mkdirs();
        }
    }

    private void bootstrapDefaultsIfDirectoryEmpty() {
        File[] existingFiles = mobDirectory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (existingFiles != null && existingFiles.length > 0) {
            return;
        }

        loadBundledDefault("zombie.yml");
        loadBundledDefault("skeleton.yml");
    }

    private void refreshBundledDefaultsIfPresent() {
        refreshBundledDefaultIfPresent("zombie.yml");
        refreshBundledDefaultIfPresent("skeleton.yml");
    }

    private void loadBundledDefault(String fileName) {
        File targetFile = new File(mobDirectory, fileName);
        resourceConfigManager.loadAuxiliaryConfig(
                "mobs/" + fileName,
                targetFile,
                List.of("config-version", "enabled", "entity-type", "resistances", "limits", "reduction-limits")
        );
    }

    private void refreshBundledDefaultIfPresent(String fileName) {
        File targetFile = new File(mobDirectory, fileName);
        if (!targetFile.exists()) {
            return;
        }
        loadBundledDefault(fileName);
    }

    private void loadProfilesFromDirectory() {
        File[] files = mobDirectory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }

        List<File> sortedFiles = new ArrayList<>(List.of(files));
        sortedFiles.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        for (File file : sortedFiles) {
            try {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                MobResistanceProfile profile = MobResistanceProfile.fromConfig(file.getName(), config);
                if (!profile.isEnabled()) {
                    continue;
                }

                if (profile.matchesMythicMob()) {
                    String normalizedMythicId = normalizeMythicMobId(profile.getMythicMobId());
                    MobResistanceProfile previous = mythicProfiles.put(normalizedMythicId, profile);
                    if (previous != null) {
                        plugin.getLogger().warning("MythicMobs resistance config override for " + normalizedMythicId
                                + ": " + previous.getFileName() + " -> " + profile.getFileName());
                    }
                    continue;
                }

                if (profile.getEntityType() != null) {
                    MobResistanceProfile previous = entityProfiles.put(profile.getEntityType(), profile);
                    if (previous != null) {
                        plugin.getLogger().warning("Mob resistance config override for " + profile.getEntityType()
                                + ": " + previous.getFileName() + " -> " + profile.getFileName());
                    }
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipped invalid mob config " + file.getName() + ": " + ex.getMessage());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load mob config " + file.getName() + ": " + ex.getMessage());
            }
        }
    }

    private MobResistanceProfile resolveProfile(LivingEntity target) {
        if (target == null) {
            return null;
        }

        MythicMobsBridge mythicBridge = plugin.getMythicMobsBridge();
        if (mythicBridge != null) {
            String mythicMobId = mythicBridge.getMythicMobId(target);
            if (mythicMobId != null) {
                MobResistanceProfile mythicProfile = mythicProfiles.get(normalizeMythicMobId(mythicMobId));
                if (mythicProfile != null) {
                    return mythicProfile;
                }
            }
        }
        return entityProfiles.get(target.getType());
    }

    private String normalizeMythicMobId(String mythicMobId) {
        return mythicMobId == null ? "" : mythicMobId.trim().toLowerCase(Locale.ROOT);
    }

    private double getFinalResistance(MobResistanceProfile profile, UUID targetId, String resistanceKey) {
        Map<String, Double> reductions = activeReductions.getOrDefault(targetId, Map.of());
        double finalResistance;

        if (PHYSICAL_REDUCTION_KEY.equals(resistanceKey)) {
            double physicalReduction = Math.min(
                    profile.getMaxPhysicalReduction(),
                    reductions.getOrDefault(PHYSICAL_REDUCTION_KEY, 0.0)
            );
            finalResistance = profile.getPhysicalResistance() - physicalReduction;
        } else {
            double allElementReduction = Math.min(
                    profile.getMaxAllElementReduction(),
                    reductions.getOrDefault(MobResistanceProfile.ALL_ELEMENT_RESISTANCE_KEY, 0.0)
            );
            double singleElementReduction = Math.min(
                    profile.getMaxSingleElementReduction(),
                    reductions.getOrDefault(resistanceKey, 0.0)
            );
            finalResistance = profile.getAllElementResistance()
                    + profile.getElementResistance(resistanceKey)
                    - allElementReduction
                    - singleElementReduction;
        }

        double minResistance = profile.isAllowNegativeResistance()
                ? profile.getMinFinalResistance()
                : Math.max(0.0, profile.getMinFinalResistance());
        double maxResistance = Math.max(minResistance, profile.getMaxFinalResistance());
        return clamp(finalResistance, minResistance, maxResistance);
    }

    private String normalizeReductionKey(String resistanceKey) {
        if (resistanceKey == null || resistanceKey.isBlank()) {
            return null;
        }
        String normalized = resistanceKey.trim();
        if (MobResistanceProfile.ALL_ELEMENT_RESISTANCE_KEY.equals(normalized)
                || PHYSICAL_REDUCTION_KEY.equals(normalized)
                || PlayerStats.PHYSICAL_KEY.equals(normalized)
                || isElementKey(normalized)) {
            return PlayerStats.PHYSICAL_KEY.equals(normalized) ? PHYSICAL_REDUCTION_KEY : normalized;
        }
        return null;
    }

    private String normalizeResistanceKey(String damageElement) {
        if (damageElement == null || damageElement.isBlank() || PlayerStats.PHYSICAL_KEY.equals(damageElement)) {
            return PHYSICAL_REDUCTION_KEY;
        }
        return damageElement;
    }

    private boolean isElementKey(String key) {
        return ElementConstant.FIRE_KEY.equals(key)
                || ElementConstant.WATER_KEY.equals(key)
                || ElementConstant.ICE_KEY.equals(key)
                || ElementConstant.ELECTRO_KEY.equals(key)
                || ElementConstant.ANEMO_KEY.equals(key)
                || ElementConstant.GEO_KEY.equals(key)
                || ElementConstant.DENDRO_KEY.equals(key);
    }

    private void removeReduction(UUID targetId, String reductionKey, double amount) {
        Map<String, Double> reductions = activeReductions.get(targetId);
        if (reductions == null) {
            return;
        }

        double remaining = reductions.getOrDefault(reductionKey, 0.0) - amount;
        if (remaining <= 0.0001) {
            reductions.remove(reductionKey);
        } else {
            reductions.put(reductionKey, remaining);
        }

        if (reductions.isEmpty()) {
            activeReductions.remove(targetId);
        }
    }

    private void registerReductionTask(UUID targetId, BukkitTask task) {
        activeReductionTasks.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(task);
    }

    private void unregisterReductionTask(UUID targetId, BukkitTask task) {
        List<BukkitTask> tasks = activeReductionTasks.get(targetId);
        if (tasks == null) {
            return;
        }
        tasks.remove(task);
        if (tasks.isEmpty()) {
            activeReductionTasks.remove(targetId);
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
