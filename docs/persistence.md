# Player Data Persistence

This project now persists the parts of the character system that should survive reconnects and plugin reloads.

## Stored data

- Hu Tao energy
- Hu Tao E cooldown timestamp
- Hu Tao Q cooldown timestamp
- Hu Tao E state end time
- Hu Tao E bonus damage value
- Hu Tao charged attack cooldown end time

## Storage file

- Runtime data is saved to `plugins/Yuanshen/playerdata.yml`

## Lifecycle

- On `PlayerJoinEvent`, the plugin loads the player's stored data.
- On `PlayerQuitEvent`, the plugin saves the player's data and unloads in-memory state.
- On plugin disable, the plugin saves all online players before clearing caches.

## Current scope

- Temporary combat state such as elemental aura on entities is still runtime-only.
- Blood Blossom target tracking is still runtime-only.
- Legacy third-party integration hooks have been removed from the active plugin setup.
