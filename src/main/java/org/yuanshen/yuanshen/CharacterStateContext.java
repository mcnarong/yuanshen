package org.yuanshen.yuanshen;

import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CharacterStateContext {

    private final CharacterType characterType;
    private final Map<UUID, StateEntry> states = new HashMap<>();

    public CharacterStateContext(CharacterType characterType) {
        this.characterType = characterType;
    }

    public CharacterType getCharacterType() {
        return characterType;
    }

    public void setStateEnd(Player player, long endTimeMillis) {
        setStateEnd(player.getUniqueId(), endTimeMillis);
    }

    public void setStateEnd(UUID playerId, long endTimeMillis) {
        state(playerId).endTimeMillis = endTimeMillis;
    }

    public long getStateEnd(UUID playerId) {
        StateEntry entry = states.get(playerId);
        return entry == null ? 0L : entry.endTimeMillis;
    }

    public long getRemainingMillis(Player player) {
        return getRemainingMillis(player.getUniqueId());
    }

    public long getRemainingMillis(UUID playerId) {
        StateEntry entry = states.get(playerId);
        if (entry == null) {
            return 0L;
        }
        return Math.max(0L, entry.endTimeMillis - System.currentTimeMillis());
    }

    public boolean hasActiveState(Player player) {
        return hasActiveState(player.getUniqueId());
    }

    public boolean hasActiveState(UUID playerId) {
        StateEntry entry = states.get(playerId);
        return entry != null && entry.endTimeMillis > System.currentTimeMillis();
    }

    public boolean hasState(UUID playerId) {
        return states.containsKey(playerId);
    }

    public void setNumber(Player player, String key, double value) {
        setNumber(player.getUniqueId(), key, value);
    }

    public void setNumber(UUID playerId, String key, double value) {
        state(playerId).numbers.put(key, value);
    }

    public double getNumber(Player player, String key, double defaultValue) {
        return getNumber(player.getUniqueId(), key, defaultValue);
    }

    public double getNumber(UUID playerId, String key, double defaultValue) {
        StateEntry entry = states.get(playerId);
        if (entry == null) {
            return defaultValue;
        }
        return entry.numbers.getOrDefault(key, defaultValue);
    }

    public void clear(Player player) {
        clear(player.getUniqueId());
    }

    public void clear(UUID playerId) {
        states.remove(playerId);
    }

    public void clearAll() {
        states.clear();
    }

    private StateEntry state(UUID playerId) {
        return states.computeIfAbsent(playerId, ignored -> new StateEntry());
    }

    private static final class StateEntry {
        private long endTimeMillis;
        private final Map<String, Double> numbers = new HashMap<>();
    }
}
