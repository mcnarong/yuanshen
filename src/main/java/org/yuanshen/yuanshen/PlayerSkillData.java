package org.yuanshen.yuanshen;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerSkillData {

    private static final double MAX_ENERGY = 100.0;
    private static final Map<UUID, PlayerSkillData> dataMap = new HashMap<>();

    public static PlayerSkillData get(UUID uuid) {
        return dataMap.computeIfAbsent(uuid, ignored -> new PlayerSkillData());
    }

    public static void unload(UUID uuid) {
        if (uuid != null) {
            dataMap.remove(uuid);
        }
    }

    public static void clearAll() {
        dataMap.clear();
    }

    private final Map<CharacterType, CharacterSkillState> states = new EnumMap<>(CharacterType.class);

    public boolean canUseE(CharacterType characterType, long cooldownMs) {
        return System.currentTimeMillis() - state(characterType).lastE >= cooldownMs;
    }

    public void useE(CharacterType characterType) {
        state(characterType).lastE = System.currentTimeMillis();
    }

    public long getLastE(CharacterType characterType) {
        return state(characterType).lastE;
    }

    public void setLastE(CharacterType characterType, long lastE) {
        state(characterType).lastE = Math.max(0L, lastE);
    }

    public long getRemainingECooldown(CharacterType characterType, long cooldownMs) {
        long remain = cooldownMs - (System.currentTimeMillis() - state(characterType).lastE);
        return Math.max(remain, 0L);
    }

    public boolean canUseQ(CharacterType characterType, long cooldownMs, int requiredEnergy) {
        CharacterSkillState state = state(characterType);
        return System.currentTimeMillis() - state.lastQ >= cooldownMs && state.energy >= requiredEnergy;
    }

    public void useQ(CharacterType characterType, int cost) {
        CharacterSkillState state = state(characterType);
        state.lastQ = System.currentTimeMillis();
        state.energy = clampEnergy(state.energy - cost);
    }

    public long getLastQ(CharacterType characterType) {
        return state(characterType).lastQ;
    }

    public void setLastQ(CharacterType characterType, long lastQ) {
        state(characterType).lastQ = Math.max(0L, lastQ);
    }

    public long getRemainingQCooldown(CharacterType characterType, long cooldownMs) {
        long remain = cooldownMs - (System.currentTimeMillis() - state(characterType).lastQ);
        return Math.max(remain, 0L);
    }

    public void addEnergy(CharacterType characterType, int amount) {
        addEnergy(characterType, (double) amount);
    }

    public void addEnergy(CharacterType characterType, double amount) {
        CharacterSkillState state = state(characterType);
        state.energy = clampEnergy(state.energy + amount);
    }

    public int getEnergy(CharacterType characterType) {
        return (int) Math.floor(state(characterType).energy + 0.0001);
    }

    public double getEnergyExact(CharacterType characterType) {
        return state(characterType).energy;
    }

    public void setEnergy(CharacterType characterType, int energy) {
        setEnergy(characterType, (double) energy);
    }

    public void setEnergy(CharacterType characterType, double energy) {
        state(characterType).energy = clampEnergy(energy);
    }

    public void reset() {
        states.clear();
    }

    private CharacterSkillState state(CharacterType characterType) {
        return states.computeIfAbsent(characterType, ignored -> new CharacterSkillState());
    }

    private double clampEnergy(double energy) {
        return Math.max(0.0, Math.min(MAX_ENERGY, energy));
    }

    private static final class CharacterSkillState {
        private long lastE;
        private long lastQ;
        private double energy;
    }
}
