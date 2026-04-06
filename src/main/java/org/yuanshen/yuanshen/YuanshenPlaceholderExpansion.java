package org.yuanshen.yuanshen;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class YuanshenPlaceholderExpansion extends PlaceholderExpansion {

    private final Yuanshen plugin;
    private final YuanshenPlaceholderResolver resolver;

    public YuanshenPlaceholderExpansion(Yuanshen plugin, YuanshenPlaceholderResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    @Override
    public String getIdentifier() {
        return "yuanshen";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return resolver.resolve(player, params);
    }
}
