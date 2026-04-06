package org.yuanshen.yuanshen;

import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元素附着状态容器。
 * 保留旧接口，底层改为统一状态表，避免 metadata + 定时任务的状态漂移。
 */
public class ElementAura {
    private static final double MAX_AURA_AMOUNT = 4.0;
    private static final double MIN_VISIBLE_AURA = 0.05;
    private static final double MIN_DECAY_WINDOW_SECONDS = 0.05;

    private static final Map<UUID, AuraState> STATES = new ConcurrentHashMap<>();

    private final LivingEntity entity;
    private final Plugin plugin;

    private static final class AuraEntry {
        private double amount;
        private long expiresAtMillis;
        private long lastUpdatedAtMillis;
        private double decayPerSecond;
    }

    private static final class StatusEntry {
        private long expiresAtMillis;
        private final Map<String, Double> auraProfile = new ConcurrentHashMap<>();
    }

    private static final class AuraState {
        private final Map<String, AuraEntry> elements = new ConcurrentHashMap<>();
        private final Map<String, StatusEntry> statuses = new ConcurrentHashMap<>();

        private boolean isEmpty() {
            return elements.isEmpty() && statuses.isEmpty();
        }
    }

    public ElementAura(LivingEntity entity, Plugin plugin) {
        this.entity = entity;
        this.plugin = plugin;
    }

    public void addElement(String element, double amount, int durationTicks) {
        if (entity == null || element == null || element.isBlank() || amount <= 0.0 || durationTicks <= 0) {
            return;
        }
        AuraState state = getOrCreateState();
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            AuraEntry entry = state.elements.computeIfAbsent(element, ignored -> new AuraEntry());
            double currentAmount = resolveAmount(entry, now);
            entry.amount = Math.min(MAX_AURA_AMOUNT, currentAmount + amount);
            entry.lastUpdatedAtMillis = now;
            long requestedExpireAt = now + (durationTicks * 50L);
            entry.expiresAtMillis = Math.max(entry.expiresAtMillis, requestedExpireAt);
            refreshDecayRate(entry, now);
        }
    }

    public boolean consumeElement(String element, double consumeAmount) {
        if (entity == null || element == null || element.isBlank()) {
            return true;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            AuraEntry entry = state.elements.get(element);
            if (entry == null) {
                logReactionDebug("消耗失败：元素 " + element + " 不存在");
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return true;
            }

            double currentAmount = resolveAmount(entry, now);
            double safeConsume = Math.max(0.0, consumeAmount);
            double remaining = currentAmount - safeConsume;
            logReactionDebug("消耗元素：" + element
                    + "，当前：" + String.format("%.2f", currentAmount)
                    + "U，消耗：" + String.format("%.2f", safeConsume)
                    + "U，剩余：" + String.format("%.2f", remaining) + "U");

            if (remaining <= MIN_VISIBLE_AURA) {
                state.elements.remove(element);
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return true;
            }

            entry.amount = remaining;
            entry.lastUpdatedAtMillis = now;
            refreshDecayRate(entry, now);
            cleanupStateIfEmpty(entity.getUniqueId(), state);
            return false;
        }
    }

    public double getElementAmount(String element) {
        if (entity == null || element == null || element.isBlank()) {
            return 0.0;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            AuraEntry entry = state.elements.get(element);
            if (entry == null) {
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return 0.0;
            }
            double amount = resolveAmount(entry, now);
            if (amount <= MIN_VISIBLE_AURA) {
                state.elements.remove(element);
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return 0.0;
            }
            cleanupStateIfEmpty(entity.getUniqueId(), state);
            return amount;
        }
    }

    public boolean hasEnoughElement(String element, double required) {
        return getElementAmount(element) >= Math.max(0.0, required);
    }

    public boolean hasElement(String element) {
        return getElementAmount(element) > MIN_VISIBLE_AURA;
    }

    public void removeElement(String element) {
        if (entity == null || element == null || element.isBlank()) {
            return;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.elements.remove(element);
            cleanupStateIfEmpty(entity.getUniqueId(), state);
        }
    }

    public void setStatus(String statusKey, int durationTicks) {
        upsertStatus(statusKey, durationTicks, null, false);
    }

    public void setStatusProfile(String statusKey, int durationTicks, Map<String, Double> auraProfile) {
        upsertStatus(statusKey, durationTicks, auraProfile, false);
    }

    public void replaceStatus(String statusKey, int durationTicks) {
        upsertStatus(statusKey, durationTicks, null, true);
    }

    public void replaceStatusProfile(String statusKey, int durationTicks, Map<String, Double> auraProfile) {
        upsertStatus(statusKey, durationTicks, auraProfile, true);
    }

    public Map<String, Double> getStatusProfile(String statusKey) {
        if (entity == null || statusKey == null || statusKey.isBlank()) {
            return Map.of();
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            StatusEntry entry = state.statuses.get(statusKey);
            if (entry == null || entry.auraProfile.isEmpty()) {
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return Map.of();
            }
            Map<String, Double> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Double> profileEntry : entry.auraProfile.entrySet()) {
                String elementKey = profileEntry.getKey();
                double amount = Math.max(0.0, profileEntry.getValue() == null ? 0.0 : profileEntry.getValue());
                if (elementKey != null && !elementKey.isBlank() && amount > MIN_VISIBLE_AURA) {
                    copy.put(elementKey, amount);
                }
            }
            cleanupStateIfEmpty(entity.getUniqueId(), state);
            return copy;
        }
    }

    public double getStatusProfileAmount(String statusKey, String elementKey) {
        if (entity == null || statusKey == null || statusKey.isBlank() || elementKey == null || elementKey.isBlank()) {
            return 0.0;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            StatusEntry entry = state.statuses.get(statusKey);
            if (entry == null) {
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return 0.0;
            }
            double amount = Math.max(0.0, entry.auraProfile.getOrDefault(elementKey, 0.0));
            if (amount <= MIN_VISIBLE_AURA) {
                entry.auraProfile.remove(elementKey);
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return 0.0;
            }
            cleanupStateIfEmpty(entity.getUniqueId(), state);
            return amount;
        }
    }

    public void setStatusProfileAmount(String statusKey, String elementKey, double amount) {
        if (entity == null || statusKey == null || statusKey.isBlank()) {
            return;
        }
        if (elementKey == null || elementKey.isBlank()) {
            return;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            StatusEntry entry = state.statuses.get(statusKey);
            if (entry == null) {
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return;
            }
            double safeAmount = Math.max(0.0, amount);
            if (safeAmount <= MIN_VISIBLE_AURA) {
                entry.auraProfile.remove(elementKey);
            } else {
                entry.auraProfile.put(elementKey, safeAmount);
            }
            cleanupStateIfEmpty(entity.getUniqueId(), state);
        }
    }

    public boolean hasStatus(String statusKey) {
        if (entity == null || statusKey == null || statusKey.isBlank()) {
            return false;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            boolean active = state.statuses.containsKey(statusKey);
            cleanupStateIfEmpty(entity.getUniqueId(), state);
            return active;
        }
    }

    public long getRemainingStatusMillis(String statusKey) {
        if (entity == null || statusKey == null || statusKey.isBlank()) {
            return 0L;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            StatusEntry entry = state.statuses.get(statusKey);
            if (entry == null) {
                cleanupStateIfEmpty(entity.getUniqueId(), state);
                return 0L;
            }
            long remaining = Math.max(0L, entry.expiresAtMillis - now);
            cleanupStateIfEmpty(entity.getUniqueId(), state);
            return remaining;
        }
    }

    public void removeStatus(String statusKey) {
        if (entity == null || statusKey == null || statusKey.isBlank()) {
            return;
        }
        AuraState state = STATES.get(entity.getUniqueId());
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.statuses.remove(statusKey);
            cleanupStateIfEmpty(entity.getUniqueId(), state);
        }
    }

    public static void clearEntity(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        STATES.remove(entity.getUniqueId());
    }

    public static void clearAll() {
        STATES.clear();
    }

    private AuraState getOrCreateState() {
        return STATES.computeIfAbsent(entity.getUniqueId(), ignored -> new AuraState());
    }

    private void upsertStatus(String statusKey, int durationTicks, Map<String, Double> auraProfile, boolean replaceExpireAt) {
        if (entity == null || statusKey == null || statusKey.isBlank() || durationTicks <= 0) {
            return;
        }
        AuraState state = getOrCreateState();
        long now = System.currentTimeMillis();
        synchronized (state) {
            cleanupExpiredEntries(state, now);
            StatusEntry entry = state.statuses.computeIfAbsent(statusKey, ignored -> new StatusEntry());
            long requestedExpireAt = now + (durationTicks * 50L);
            entry.expiresAtMillis = replaceExpireAt
                    ? requestedExpireAt
                    : Math.max(entry.expiresAtMillis, requestedExpireAt);
            if (auraProfile != null) {
                entry.auraProfile.clear();
                for (Map.Entry<String, Double> profileEntry : auraProfile.entrySet()) {
                    String elementKey = profileEntry.getKey();
                    double amount = Math.max(0.0, profileEntry.getValue() == null ? 0.0 : profileEntry.getValue());
                    if (elementKey != null && !elementKey.isBlank() && amount > MIN_VISIBLE_AURA) {
                        entry.auraProfile.put(elementKey, amount);
                    }
                }
            }
        }
    }

    private double resolveAmount(AuraEntry entry, long now) {
        if (entry == null) {
            return 0.0;
        }
        if (entry.expiresAtMillis > 0L && entry.expiresAtMillis <= now) {
            entry.amount = 0.0;
            entry.lastUpdatedAtMillis = now;
            entry.decayPerSecond = 0.0;
            return 0.0;
        }
        if (entry.lastUpdatedAtMillis <= 0L) {
            entry.lastUpdatedAtMillis = now;
        }
        long elapsedMillis = Math.max(0L, now - entry.lastUpdatedAtMillis);
        if (elapsedMillis > 0L) {
            double decay = (elapsedMillis / 1000.0) * resolveDecayPerSecond(entry, now);
            entry.amount = Math.max(0.0, entry.amount - decay);
            entry.lastUpdatedAtMillis = now;
        }
        return entry.amount;
    }

    private double resolveDecayPerSecond(AuraEntry entry, long now) {
        if (entry == null) {
            return 0.0;
        }
        if (entry.decayPerSecond > 0.0) {
            return entry.decayPerSecond;
        }
        long remainingMillis = Math.max(1L, entry.expiresAtMillis - now);
        return entry.amount / Math.max(MIN_DECAY_WINDOW_SECONDS, remainingMillis / 1000.0);
    }

    private void refreshDecayRate(AuraEntry entry, long now) {
        if (entry == null) {
            return;
        }
        double safeAmount = Math.max(0.0, entry.amount);
        if (safeAmount <= MIN_VISIBLE_AURA || entry.expiresAtMillis <= now) {
            entry.decayPerSecond = 0.0;
            return;
        }
        long remainingMillis = Math.max(1L, entry.expiresAtMillis - now);
        entry.decayPerSecond = safeAmount / Math.max(MIN_DECAY_WINDOW_SECONDS, remainingMillis / 1000.0);
    }

    private void cleanupExpiredEntries(AuraState state, long now) {
        state.elements.entrySet().removeIf(entry -> {
            AuraEntry aura = entry.getValue();
            if (aura == null) {
                return true;
            }
            double amount = resolveAmount(aura, now);
            return amount <= MIN_VISIBLE_AURA || aura.expiresAtMillis <= now;
        });
        state.statuses.entrySet().removeIf(entry -> {
            StatusEntry status = entry.getValue();
            return status == null || status.expiresAtMillis <= now;
        });
    }

    private void cleanupStateIfEmpty(UUID entityId, AuraState state) {
        if (state != null && state.isEmpty()) {
            STATES.remove(entityId, state);
        }
    }

    private void logReactionDebug(String message) {
        if (plugin instanceof Yuanshen ys && ys.shouldLogReactionDebug()) {
            plugin.getLogger().info(message);
        }
    }
}
