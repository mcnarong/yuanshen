package org.yuanshen.yuanshen;

import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;

public class CharacterStateRegistry {

    private final Map<CharacterType, CharacterStateHandler> handlers = new EnumMap<>(CharacterType.class);

    public void register(CharacterStateHandler handler) {
        if (handler == null) {
            return;
        }
        handlers.put(handler.getCharacterType(), handler);
    }

    public CharacterStateHandler get(CharacterType type) {
        return handlers.get(type);
    }

    public <T extends CharacterStateHandler> T get(CharacterType type, Class<T> expectedType) {
        CharacterStateHandler handler = handlers.get(type);
        if (handler == null || !expectedType.isInstance(handler)) {
            return null;
        }
        return expectedType.cast(handler);
    }

    public void clear(Player player) {
        for (CharacterStateHandler handler : handlers.values()) {
            handler.clear(player);
        }
    }

    public void clearAll() {
        for (CharacterStateHandler handler : handlers.values()) {
            handler.clearAll();
        }
    }
}
