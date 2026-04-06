package org.yuanshen.yuanshen;

public record CharacterInstance(
        CharacterDefinition definition,
        int level,
        int constellation
) {
}
