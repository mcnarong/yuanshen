package org.yuanshen.yuanshen;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerDataListener implements Listener {

    private final Yuanshen plugin;

    public PlayerDataListener(Yuanshen plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getPlayerDataStore().loadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.restorePlayerAttributes(event.getPlayer());
        plugin.getPlayerDataStore().savePlayer(event.getPlayer());
        plugin.getPlayerDataStore().unloadPlayer(event.getPlayer());
    }
}
