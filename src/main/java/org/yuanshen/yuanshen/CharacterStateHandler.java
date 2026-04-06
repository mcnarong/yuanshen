package org.yuanshen.yuanshen;

import org.bukkit.entity.Player;

public interface CharacterStateHandler {

    CharacterType getCharacterType();

    void clear(Player player);

    default void clearAll() {
        // 默认无全局状态需要清理
    }
}
