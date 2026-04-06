package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SidebarDisplayManager {

    private static final String OBJECTIVE_NAME = "yuanshen_sidebar";
    private static final int MAX_LINES = 15;

    private final Yuanshen plugin;
    private final YuanshenPlaceholderResolver placeholderResolver;
    private final Map<UUID, Scoreboard> previousScoreboards = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> sidebarScoreboards = new ConcurrentHashMap<>();

    private int taskId = -1;

    public SidebarDisplayManager(Yuanshen plugin, YuanshenPlaceholderResolver placeholderResolver) {
        this.plugin = plugin;
        this.placeholderResolver = placeholderResolver;
    }

    public void reload() {
        stopUpdater();
        if (isGloballyEnabled()) {
            startUpdater();
            refreshOnlinePlayers();
            return;
        }
        clearOnlineSidebars();
    }

    public void shutdown() {
        stopUpdater();
        clearOnlineSidebars();
    }

    public void refreshPlayer(Player player) {
        if (player == null) {
            return;
        }
        if (!isGloballyEnabled() || !plugin.isSidebarEnabled(player)) {
            detachPlayer(player);
            return;
        }
        renderSidebar(player);
    }

    public void detachPlayer(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Scoreboard managed = sidebarScoreboards.remove(uuid);
        Scoreboard previous = previousScoreboards.remove(uuid);
        if (managed == null) {
            return;
        }

        if (player.isOnline() && player.getScoreboard() == managed) {
            Scoreboard target = previous;
            if (target == null && Bukkit.getScoreboardManager() != null) {
                target = Bukkit.getScoreboardManager().getMainScoreboard();
            }
            if (target != null) {
                player.setScoreboard(target);
            }
        }
    }

    private void startUpdater() {
        int interval = Math.max(10, getSidebarConfig().getInt("sidebar.update-interval-ticks", 20));
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::refreshOnlinePlayers,
                interval,
                interval
        );
    }

    private void stopUpdater() {
        if (taskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void refreshOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    private void clearOnlineSidebars() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            detachPlayer(player);
        }
    }

    private boolean isGloballyEnabled() {
        return getSidebarConfig().getBoolean("sidebar.enabled", true);
    }

    private void renderSidebar(Player player) {
        if (Bukkit.getScoreboardManager() == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Scoreboard current = player.getScoreboard();
        Scoreboard board = sidebarScoreboards.computeIfAbsent(uuid, ignored -> Bukkit.getScoreboardManager().getNewScoreboard());

        if (current != board) {
            previousScoreboards.put(uuid, current);
            player.setScoreboard(board);
        }

        Objective objective = board.getObjective(OBJECTIVE_NAME);
        String title = truncateForScoreboard(color(getSidebarConfig().getString("sidebar.title", "&6原神面板")), 32);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, "dummy", title);
        } else if (!objective.getDisplayName().equals(title)) {
            objective.setDisplayName(title);
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = buildLines(player);
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = lineEntry(i);
            Team team = board.getTeam(teamName(i));
            if (team == null) {
                team = board.registerNewTeam(teamName(i));
                team.addEntry(entry);
            }

            if (i < lines.size()) {
                String[] split = splitLine(lines.get(i));
                team.setPrefix(split[0]);
                team.setSuffix(split[1]);
                objective.getScore(entry).setScore(lines.size() - i);
            } else {
                board.resetScores(entry);
                team.setPrefix("");
                team.setSuffix("");
            }
        }
    }

    private List<String> buildLines(Player player) {
        FileConfiguration config = getSidebarConfig();
        String layout = config.getString("sidebar.active-layout", "default");
        String linesPath = "sidebar.layouts." + layout + ".lines";
        List<String> configured = config.getStringList(linesPath);
        List<String> source = configured == null || configured.isEmpty() ? defaultLines() : ensureUtilityLines(configured);
        List<String> rendered = new ArrayList<>();
        for (String line : source) {
            if (rendered.size() >= MAX_LINES) {
                break;
            }
            rendered.add(color(placeholderResolver.applyPlaceholders(player, line)));
        }
        return rendered;
    }

    private List<String> defaultLines() {
        return List.of(
                "&6当前角色 &f%yuanshen_character%",
                "&7武器 &f%yuanshen_weapon%",
                "&7等级 &f%yuanshen_weapon_level% &7精炼 &f%yuanshen_weapon_refinement%",
                "&7元素 &f%yuanshen_element%",
                "&8&m----------------",
                "&c攻击力 &f%yuanshen_damage%",
                "&b暴击率 &f%yuanshen_crit_rate%%",
                "&d暴击伤害 &f%yuanshen_crit_damage%%",
                "&6元素精通 &f%yuanshen_element_mastery%",
                "&a生命值 &f%yuanshen_current_health%&7/&f%yuanshen_max_health%",
                "&3防御力 &f%yuanshen_defense%",
                "&9充能效率 &f%yuanshen_energy_recharge%%"
        );
    }

    private List<String> ensureWeaponLines(List<String> configured) {
        List<String> lines = new ArrayList<>(configured);
        boolean hasWeapon = lines.stream().anyMatch(line -> line.contains("%yuanshen_weapon%"));
        boolean hasWeaponLevel = lines.stream().anyMatch(line -> line.contains("%yuanshen_weapon_level%"));
        if (!hasWeapon) {
            lines.add(Math.min(1, lines.size()), "&7武器 &f%yuanshen_weapon%");
        }
        if (!hasWeaponLevel) {
            lines.add(Math.min(2, lines.size()), "&7等级 &f%yuanshen_weapon_level% &7精炼 &f%yuanshen_weapon_refinement%");
        }
        return lines;
    }

    private List<String> ensureUtilityLines(List<String> configured) {
        List<String> lines = ensureWeaponLines(configured);
        boolean hasParticle = lines.stream().anyMatch(line -> line.contains("%yuanshen_particle_status%"));
        if (!hasParticle) {
            int energyIndex = findLineIndex(lines, "%yuanshen_current_energy%");
            int insertIndex = energyIndex >= 0 ? Math.min(energyIndex + 1, lines.size()) : Math.min(5, lines.size());
            lines.add(insertIndex, "&b最近产球 &f%yuanshen_particle_status%");
        }
        while (lines.size() > MAX_LINES) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private int findLineIndex(List<String> lines, String placeholder) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && line.contains(placeholder)) {
                return i;
            }
        }
        return -1;
    }

    private FileConfiguration getSidebarConfig() {
        FileConfiguration config = plugin.getSidebarConfig();
        return config != null ? config : plugin.getConfig();
    }

    private String[] splitLine(String input) {
        String text = truncateForScoreboard(input, 128);
        if (text.length() <= 64) {
            return new String[]{text, ""};
        }

        String prefix = text.substring(0, 64);
        if (prefix.charAt(prefix.length() - 1) == ChatColor.COLOR_CHAR) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        String remainder = text.substring(prefix.length());
        String suffix = ChatColor.getLastColors(prefix) + remainder;
        suffix = truncateForScoreboard(suffix, 64);
        return new String[]{prefix, suffix};
    }

    private String truncateForScoreboard(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }

        String truncated = text.substring(0, maxLength);
        if (!truncated.isEmpty() && truncated.charAt(truncated.length() - 1) == ChatColor.COLOR_CHAR) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated;
    }

    private String teamName(int index) {
        return "ysb_" + index;
    }

    private String lineEntry(int index) {
        ChatColor[] colors = ChatColor.values();
        return colors[index].toString() + ChatColor.RESET;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
