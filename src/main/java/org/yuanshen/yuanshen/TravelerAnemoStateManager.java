package org.yuanshen.yuanshen;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TravelerAnemoStateManager implements CharacterStateHandler {

    private final Map<UUID, BukkitTask> burstTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> passiveHealTasks = new ConcurrentHashMap<>();

    @Override
    public CharacterType getCharacterType() {
        return CharacterType.TRAVELER_ANEMO;
    }

    public void trackBurstTask(Player player, BukkitTask task) {
        replaceTask(player, task, burstTasks);
    }

    public void clearBurst(Player player) {
        clearTask(player, burstTasks);
    }

    public void trackPassiveHealTask(Player player, BukkitTask task) {
        replaceTask(player, task, passiveHealTasks);
    }

    public void clearPassiveHeal(Player player) {
        clearTask(player, passiveHealTasks);
    }

    @Override
    public void clear(Player player) {
        clearBurst(player);
        clearPassiveHeal(player);
    }

    @Override
    public void clearAll() {
        clearAllTasks(burstTasks);
        clearAllTasks(passiveHealTasks);
    }

    private void replaceTask(Player player, BukkitTask task, Map<UUID, BukkitTask> store) {
        if (player == null) {
            return;
        }
        BukkitTask previous = store.put(player.getUniqueId(), task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void clearTask(Player player, Map<UUID, BukkitTask> store) {
        if (player == null) {
            return;
        }
        BukkitTask task = store.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void clearAllTasks(Map<UUID, BukkitTask> store) {
        for (BukkitTask task : store.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        store.clear();
    }
}
