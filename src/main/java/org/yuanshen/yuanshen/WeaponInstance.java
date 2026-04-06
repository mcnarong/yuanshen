package org.yuanshen.yuanshen;

public record WeaponInstance(
        WeaponDefinition definition,
        int level,
        int refinement
) {
}
