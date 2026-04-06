package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ModKeybindBridge implements PluginMessageListener, Listener {
    private static final String ACTION_CHANNEL = "yuanshen:key_action";
    private static final String CONFIG_CHANNEL = "yuanshen:keybind_config";
    private static final String STATE_CHANNEL = "yuanshen:keybind_state";
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_ACTION_ID_LENGTH = 32;
    private static final int MAX_KEY_SPEC_LENGTH = 64;
    private static final Pattern ACTION_ID_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");
    private static final String ACTIVATION_MODE_COMPATIBLE_WEAPON = "compatible_weapon";
    private static final String ACTIVATION_MODE_YUANSHEN_WEAPON = "yuanshen_weapon";
    private static final String ACTIVATION_MODE_ACTIVE_CHARACTER = "active_character";
    private static final String ACTIVATION_MODE_ALWAYS = "always";

    private final Yuanshen plugin;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastKnownStateFingerprints = new ConcurrentHashMap<>();
    private BukkitTask stateSyncTask;

    public ModKeybindBridge(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ACTION_CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, ACTION_CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CONFIG_CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, STATE_CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        stateSyncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncStates, 1L, 5L);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        if (stateSyncTask != null) {
            stateSyncTask.cancel();
            stateSyncTask = null;
        }
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, ACTION_CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, ACTION_CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CONFIG_CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, STATE_CHANNEL);
        cooldowns.clear();
        lastKnownStateFingerprints.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ACTION_CHANNEL.equals(channel) || player == null || message == null) {
            return;
        }
        FileConfiguration config = plugin.getModKeybindConfig();
        if (config == null || !config.getBoolean("mod_keybind_bridge.enabled", true)) {
            return;
        }

        DecodedPayload payload;
        try {
            payload = decode(message);
        } catch (IllegalArgumentException ex) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().warning("Ignored invalid mod keybind payload from " + player.getName() + ": " + ex.getMessage());
            }
            return;
        }

        if (payload.protocolVersion() != PROTOCOL_VERSION) {
            plugin.warnOnce(
                    "mod-keybind:protocol:" + payload.protocolVersion(),
                    "Ignored unsupported mod keybind protocol version: " + payload.protocolVersion()
            );
            return;
        }

        String actionId = payload.actionId();
        String path = "mod_keybind_bridge.bindings." + actionId;
        if (!config.getBoolean(path + ".enabled", false)) {
            return;
        }
        if (!isBindingActive(player, config, actionId, path)) {
            return;
        }

        long cooldownMs = Math.max(
                0L,
                config.getLong(
                        path + ".cooldown-ms",
                        config.getLong("mod_keybind_bridge.default-cooldown-ms", 150L)
                )
        );
        if (!checkCooldown(player.getUniqueId(), actionId, cooldownMs)) {
            return;
        }

        String configuredCommand = config.getString(path + ".command", "");
        String command = normalizeCommand(configuredCommand);
        if (command == null) {
            plugin.warnOnce(
                    "mod-keybind:missing-command:" + actionId,
                    "mod_keybind_bridge binding '" + actionId + "' is enabled but has no command."
            );
            return;
        }

        dispatchAsPlayer(player, command);
    }

    public void broadcastConfig() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendConfig(player);
        }
    }

    public void broadcastStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendState(player, true);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            sendConfig(player);
            sendState(player, true);
        }, 10L);
    }

    private void dispatchAsPlayer(Player player, String command) {
        Runnable task = () -> {
            boolean handled = Bukkit.dispatchCommand(player, command);
            if (!handled && plugin.isDebugEnabled()) {
                plugin.getLogger().warning("Command dispatched by mod keybind returned false for player "
                        + player.getName() + ": " + command);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private boolean checkCooldown(UUID playerId, String actionId, long cooldownMs) {
        if (cooldownMs <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        String key = playerId + ":" + actionId;
        Long until = cooldowns.get(key);
        if (until != null && until > now) {
            return false;
        }
        cooldowns.put(key, now + cooldownMs);
        return true;
    }

    private void sendConfig(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        FileConfiguration config = plugin.getModKeybindConfig();
        if (config == null || !config.getBoolean("mod_keybind_bridge.enabled", true)) {
            return;
        }

        byte[] payload = encodeConfig(config);
        if (payload.length == 0) {
            return;
        }

        player.sendPluginMessage(plugin, CONFIG_CHANNEL, payload);
    }

    private void syncStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendState(player, false);
        }
    }

    private void sendState(Player player, boolean force) {
        if (player == null || !player.isOnline()) {
            return;
        }

        List<String> activeActionIds = collectActiveActionIds(player);
        String fingerprint = String.join("\u0000", activeActionIds);
        UUID playerId = player.getUniqueId();
        String previous = lastKnownStateFingerprints.get(playerId);
        if (!force && fingerprint.equals(previous)) {
            return;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, PROTOCOL_VERSION);
        writeVarInt(output, activeActionIds.size());
        for (String actionId : activeActionIds) {
            writeString(output, actionId, MAX_ACTION_ID_LENGTH);
        }
        player.sendPluginMessage(plugin, STATE_CHANNEL, output.toByteArray());
        lastKnownStateFingerprints.put(playerId, fingerprint);
    }

    private List<String> collectActiveActionIds(Player player) {
        List<String> activeActionIds = new ArrayList<>();
        FileConfiguration config = plugin.getModKeybindConfig();
        if (config == null || config.getConfigurationSection("mod_keybind_bridge.bindings") == null) {
            return activeActionIds;
        }

        for (String rawActionId : config.getConfigurationSection("mod_keybind_bridge.bindings").getKeys(false)) {
            String path = "mod_keybind_bridge.bindings." + rawActionId;
            if (!config.getBoolean(path + ".enabled", false)) {
                continue;
            }
            if (isBindingActive(player, config, rawActionId, path)) {
                activeActionIds.add(normalizeActionId(rawActionId));
            }
        }
        return activeActionIds;
    }

    private boolean isBindingActive(Player player, FileConfiguration config, String rawActionId, String path) {
        String activationMode = resolveActivationMode(config, rawActionId, path);
        return switch (activationMode) {
            case ACTIVATION_MODE_ALWAYS -> true;
            case ACTIVATION_MODE_ACTIVE_CHARACTER -> plugin.hasActiveCharacter(player);
            case ACTIVATION_MODE_YUANSHEN_WEAPON -> plugin.getWeaponManager() != null
                    && plugin.getWeaponManager().isRegisteredWeapon(player.getInventory().getItemInMainHand());
            case ACTIVATION_MODE_COMPATIBLE_WEAPON -> plugin.isCharacterModeActive(player);
            default -> false;
        };
    }

    private String resolveActivationMode(FileConfiguration config, String rawActionId, String path) {
        String configured = config.getString(path + ".activation-mode", "");
        String command = normalizeCommand(config.getString(path + ".command", ""));
        String normalizedCommand = command == null ? null : command.toLowerCase(Locale.ROOT);
        String normalized = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            if (ACTIVATION_MODE_YUANSHEN_WEAPON.equals(normalized)
                    && isCharacterSlotBindingCommand(rawActionId, normalizedCommand)) {
                plugin.infoOnce(
                        "mod-keybind:migrate-slot-activation:" + rawActionId,
                        "mod_keybind_bridge binding '" + rawActionId
                                + "' uses legacy activation-mode 'yuanshen_weapon'; treating it as 'active_character' to allow character switching before weapon auto-swap."
                );
                return ACTIVATION_MODE_ACTIVE_CHARACTER;
            }
            return switch (normalized) {
                case ACTIVATION_MODE_COMPATIBLE_WEAPON,
                        ACTIVATION_MODE_YUANSHEN_WEAPON,
                        ACTIVATION_MODE_ACTIVE_CHARACTER,
                        ACTIVATION_MODE_ALWAYS -> normalized;
                default -> ACTIVATION_MODE_COMPATIBLE_WEAPON;
            };
        }

        if (normalizedCommand != null
                && (matchesCommandPrefix(normalizedCommand, "ys player set")
                || matchesCommandPrefix(normalizedCommand, "yuanshen player set"))) {
            return ACTIVATION_MODE_ACTIVE_CHARACTER;
        }
        if (normalizedCommand != null
                && (matchesCommandPrefix(normalizedCommand, "ys open")
                || matchesCommandPrefix(normalizedCommand, "yuanshen open"))) {
            return ACTIVATION_MODE_ALWAYS;
        }
        if (isCharacterSlotAction(rawActionId)) {
            return ACTIVATION_MODE_ACTIVE_CHARACTER;
        }
        return ACTIVATION_MODE_COMPATIBLE_WEAPON;
    }

    private boolean isCharacterSlotBindingCommand(String rawActionId, String normalizedCommand) {
        return isCharacterSlotAction(rawActionId)
                || (normalizedCommand != null
                && (matchesCommandPrefix(normalizedCommand, "ys player set")
                || matchesCommandPrefix(normalizedCommand, "yuanshen player set")));
    }

    private boolean isCharacterSlotAction(String rawActionId) {
        return "slot_1".equals(rawActionId)
                || "slot_2".equals(rawActionId)
                || "slot_3".equals(rawActionId)
                || "slot_4".equals(rawActionId);
    }

    private boolean matchesCommandPrefix(String command, String prefix) {
        return command.equals(prefix) || command.startsWith(prefix + " ");
    }

    private byte[] encodeConfig(FileConfiguration config) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, PROTOCOL_VERSION);

        List<BindingDefinition> bindings = collectBindings(config);
        writeVarInt(output, bindings.size());
        for (BindingDefinition binding : bindings) {
            writeString(output, binding.actionId(), KeyActionPayloadConstraints.MAX_ACTION_ID_LENGTH);
            writeString(output, binding.keySpec(), MAX_KEY_SPEC_LENGTH);
            output.write(binding.consumeVanilla() ? 1 : 0);
        }

        return output.toByteArray();
    }

    private List<BindingDefinition> collectBindings(FileConfiguration config) {
        List<BindingDefinition> bindings = new ArrayList<>();
        if (config.getConfigurationSection("mod_keybind_bridge.bindings") == null) {
            return bindings;
        }

        for (String rawActionId : config.getConfigurationSection("mod_keybind_bridge.bindings").getKeys(false)) {
            String actionId = normalizeActionId(rawActionId);
            String path = "mod_keybind_bridge.bindings." + rawActionId;
            if (!config.getBoolean(path + ".enabled", false)) {
                continue;
            }

            String keySpec = normalizeKeySpec(config.getString(path + ".key", ""));
            if (keySpec == null) {
                plugin.warnOnce(
                        "mod-keybind:missing-key:" + actionId,
                        "mod_keybind_bridge binding '" + actionId + "' is enabled but has no key."
                );
                continue;
            }

            boolean consumeVanilla = config.getBoolean(path + ".consume-vanilla", false);
            bindings.add(new BindingDefinition(actionId, keySpec, consumeVanilla));
        }
        return bindings;
    }

    private DecodedPayload decode(byte[] message) {
        ByteArrayInputStream input = new ByteArrayInputStream(message);
        int protocolVersion = readVarInt(input);
        String actionId = readString(input, MAX_ACTION_ID_LENGTH);
        if (input.available() > 0) {
            throw new IllegalArgumentException("Payload contains unexpected trailing bytes.");
        }
        return new DecodedPayload(protocolVersion, normalizeActionId(actionId));
    }

    private int readVarInt(ByteArrayInputStream input) {
        int value = 0;
        int position = 0;

        while (position < 32) {
            int currentByte = input.read();
            if (currentByte < 0) {
                throw new IllegalArgumentException("Unexpected end of payload while reading VarInt.");
            }

            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) {
                return value;
            }

            position += 7;
        }

        throw new IllegalArgumentException("VarInt is too large.");
    }

    private String readString(ByteArrayInputStream input, int maxLength) {
        int byteLength = readVarInt(input);
        if (byteLength < 0 || byteLength > maxLength * 3) {
            throw new IllegalArgumentException("String length is out of bounds: " + byteLength);
        }

        byte[] bytes = new byte[byteLength];
        int read = input.read(bytes, 0, byteLength);
        if (read != byteLength) {
            throw new IllegalArgumentException("Unexpected end of payload while reading string.");
        }

        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("String is longer than the allowed maximum length.");
        }
        return value;
    }

    private String normalizeActionId(String actionId) {
        if (actionId == null) {
            throw new IllegalArgumentException("Action id is missing.");
        }

        String normalized = actionId.trim().toLowerCase(Locale.ROOT);
        if (!ACTION_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid action id: " + actionId);
        }
        return normalized;
    }

    private String normalizeKeySpec(String keySpec) {
        if (keySpec == null) {
            return null;
        }

        String normalized = keySpec.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_KEY_SPEC_LENGTH) {
            return null;
        }
        return normalized;
    }

    private String normalizeCommand(String configuredCommand) {
        if (configuredCommand == null) {
            return null;
        }

        String command = configuredCommand.trim();
        if (command.isEmpty()) {
            return null;
        }

        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }

        return command.isEmpty() ? null : command;
    }

    private void writeVarInt(ByteArrayOutputStream output, int value) {
        int current = value;
        while ((current & -128) != 0) {
            output.write(current & 127 | 128);
            current >>>= 7;
        }
        output.write(current);
    }

    private void writeString(ByteArrayOutputStream output, String value, int maxLength) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (value.length() > maxLength || bytes.length > maxLength * 3) {
            throw new IllegalArgumentException("String is longer than the allowed maximum length: " + value);
        }
        writeVarInt(output, bytes.length);
        try {
            output.write(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode plugin message.", e);
        }
    }

    private record DecodedPayload(int protocolVersion, String actionId) {
    }

    private record BindingDefinition(String actionId, String keySpec, boolean consumeVanilla) {
    }

    private static final class KeyActionPayloadConstraints {
        private static final int MAX_ACTION_ID_LENGTH = 32;

        private KeyActionPayloadConstraints() {
        }
    }
}
