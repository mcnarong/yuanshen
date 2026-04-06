package org.yuanshen.yuanshen;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EnergyManager {

    private static final double PARTICLE_BASE_ENERGY = 2.0;
    private static final double ACTIVE_FACTOR = 1.0;
    private static final double INACTIVE_FACTOR = 0.6;
    private static final long DEFAULT_PARTICLE_UI_DURATION_MS = 4000L;

    private final Yuanshen plugin;
    private final Map<String, Long> particleCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, RecentParticleGain> recentParticleGains = new ConcurrentHashMap<>();

    public EnergyManager(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        String prefix = player.getUniqueId().toString() + ":";
        particleCooldowns.keySet().removeIf(key -> key.startsWith(prefix));
        recentParticleGains.remove(player.getUniqueId());
    }

    public void clearAll() {
        particleCooldowns.clear();
        recentParticleGains.clear();
    }

    public RecentParticleGain getRecentParticleGain(Player player) {
        if (player == null) {
            return null;
        }
        RecentParticleGain gain = recentParticleGains.get(player.getUniqueId());
        if (gain == null) {
            return null;
        }
        if (gain.expireAtMillis <= System.currentTimeMillis()) {
            recentParticleGains.remove(player.getUniqueId());
            return null;
        }
        return gain;
    }

    public boolean grantElementalParticles(Player owner, String particleElement, int minParticles, int maxParticles,
                                           double maxChance, long icdMs, String icdKey) {
        if (owner == null || particleElement == null || particleElement.isBlank()) {
            return false;
        }
        if (!checkCooldown(owner.getUniqueId(), icdKey, icdMs)) {
            return false;
        }

        int particles = rollParticleCount(minParticles, maxParticles, maxChance);
        if (particles <= 0) {
            return false;
        }

        PlayerSkillData skillData = PlayerSkillData.get(owner.getUniqueId());
        int selectedSlot = plugin.getCharacterSlotManager().getSelectedSlot(owner);
        double activeEnergyGain = 0.0;
        double teamEnergyGain = 0.0;
        for (int slot = 1; slot <= CharacterSlotManager.MAX_SLOTS; slot++) {
            CharacterType character = plugin.getCharacterResolver()
                    .resolveCharacter(plugin.getCharacterSlotManager().getSlotItem(owner, slot));
            if (character == null) {
                continue;
            }

            double roleFactor = slot == selectedSlot ? ACTIVE_FACTOR : INACTIVE_FACTOR;
            double elementFactor = resolveElementFactor(character.getElementKey(), particleElement);
            double energyRecharge = resolveEnergyRecharge(owner, slot, character, selectedSlot);
            double energy = PARTICLE_BASE_ENERGY * particles * roleFactor * elementFactor * (1.0 + Math.max(0.0, energyRecharge));
            skillData.addEnergy(character, energy);
            teamEnergyGain += energy;
            if (slot == selectedSlot) {
                activeEnergyGain += energy;
            }
        }

        CharacterType activeCharacter = plugin.getCharacterResolver().resolveCharacter(owner);
        if (activeCharacter != null && activeEnergyGain > 0.0) {
            rememberRecentGain(owner, activeCharacter, particleElement, particles, activeEnergyGain, teamEnergyGain, icdKey);
            sendActionBar(owner, activeCharacter, particles, activeEnergyGain, icdKey);
        }

        if (plugin.getSidebarDisplayManager() != null) {
            plugin.getSidebarDisplayManager().refreshPlayer(owner);
        }
        return true;
    }

    private boolean checkCooldown(UUID playerId, String icdKey, long icdMs) {
        long now = System.currentTimeMillis();
        String key = playerId + ":" + (icdKey == null ? "" : icdKey.toLowerCase(Locale.ROOT));
        long nextAllowed = particleCooldowns.getOrDefault(key, 0L);
        if (nextAllowed > now) {
            return false;
        }
        if (icdMs > 0L) {
            particleCooldowns.put(key, now + icdMs);
        }
        return true;
    }

    private int rollParticleCount(int minParticles, int maxParticles, double maxChance) {
        int safeMin = Math.max(0, minParticles);
        int safeMax = Math.max(safeMin, maxParticles);
        if (safeMax == safeMin) {
            return safeMin;
        }
        return Math.random() < Math.max(0.0, Math.min(1.0, maxChance)) ? safeMax : safeMin;
    }

    private double resolveElementFactor(String characterElement, String particleElement) {
        if (particleElement == null || particleElement.isBlank()) {
            return 1.0;
        }
        if (particleElement.equals(characterElement)) {
            return 1.5;
        }
        return 0.5;
    }

    private double resolveEnergyRecharge(Player owner, int slot, CharacterType character, int selectedSlot) {
        if (slot == selectedSlot) {
            PlayerStats stats = plugin.getPlayerStats(owner);
            if (stats != null) {
                return stats.getEnergyRecharge();
            }
        }

        double energyRecharge = plugin.getConfigParser().parseDouble("default_stats.energy_recharge", owner, 0.0);
        if (character == CharacterType.KEQING && plugin.getKeqingSkillHandler() != null) {
            energyRecharge += plugin.getKeqingSkillHandler().getBurstEnergyRechargeBonus(owner);
        }
        return energyRecharge;
    }

    private void rememberRecentGain(Player owner, CharacterType activeCharacter, String particleElement,
                                    int particles, double activeEnergyGain, double teamEnergyGain, String icdKey) {
        long expireAt = System.currentTimeMillis() + resolveUiDurationMillis();
        recentParticleGains.put(owner.getUniqueId(), new RecentParticleGain(
                activeCharacter,
                resolveParticleSourceLabel(icdKey),
                resolveElementLabel(particleElement),
                particles,
                activeEnergyGain,
                teamEnergyGain,
                expireAt
        ));
    }

    private void sendActionBar(Player owner, CharacterType activeCharacter, int particles,
                               double activeEnergyGain, String icdKey) {
        if (!isActionBarEnabled()) {
            return;
        }
        String template = "&b" + activeCharacter.getDisplayName() + " &7- &f{source} &7| &e{particles}球 &7| &a+{energy}能量";
        String message = template
                .replace("{source}", resolveParticleSourceLabel(icdKey))
                .replace("{particles}", String.valueOf(particles))
                .replace("{energy}", formatEnergy(activeEnergyGain));
        owner.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(color(message)));
    }

    private long resolveUiDurationMillis() {
        if (plugin.getSidebarConfig() == null) {
            return DEFAULT_PARTICLE_UI_DURATION_MS;
        }
        return Math.max(1000L, plugin.getSidebarConfig()
                .getLong("sidebar.particle-ui.display-duration-ms", DEFAULT_PARTICLE_UI_DURATION_MS));
    }

    private boolean isActionBarEnabled() {
        return plugin.getSidebarConfig() == null
                || plugin.getSidebarConfig().getBoolean("sidebar.particle-ui.actionbar-enabled", true);
    }

    private String resolveParticleSourceLabel(String icdKey) {
        if (icdKey == null || icdKey.isBlank()) {
            return "元素微粒";
        }
        return switch (icdKey.toLowerCase(Locale.ROOT)) {
            case "hutao:e-state-hit" -> "蝶引来生";
            case "xiangling:guoba" -> "锅巴喷火";
            case "diluc:searing-onslaught" -> "逆焰之刃";
            case "keqing:stiletto" -> "星斗归位";
            case "ningguang:jade-screen" -> "璇玑屏";
            case "traveler-anemo:e-tap" -> "风涡剑 点按";
            case "traveler-anemo:e-hold" -> "风涡剑 长按";
            case "yelan:lifeline" -> "萦络纵命索";
            default -> icdKey;
        };
    }

    private String resolveElementLabel(String particleElement) {
        if (particleElement == null || particleElement.isBlank()) {
            return "无";
        }
        return switch (particleElement) {
            case ElementConstant.FIRE_KEY -> "火";
            case ElementConstant.WATER_KEY -> "水";
            case ElementConstant.ICE_KEY -> "冰";
            case ElementConstant.ELECTRO_KEY -> "雷";
            case ElementConstant.ANEMO_KEY -> "风";
            case ElementConstant.GEO_KEY -> "岩";
            case ElementConstant.DENDRO_KEY -> "草";
            default -> particleElement;
        };
    }

    private String formatEnergy(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private String color(String text) {
        return text == null ? "" : text.replace('&', '\u00A7');
    }

    public static final class RecentParticleGain {
        private final CharacterType activeCharacter;
        private final String sourceLabel;
        private final String elementLabel;
        private final int particles;
        private final double activeEnergyGain;
        private final double teamEnergyGain;
        private final long expireAtMillis;

        private RecentParticleGain(CharacterType activeCharacter, String sourceLabel, String elementLabel,
                                   int particles, double activeEnergyGain, double teamEnergyGain,
                                   long expireAtMillis) {
            this.activeCharacter = activeCharacter;
            this.sourceLabel = sourceLabel;
            this.elementLabel = elementLabel;
            this.particles = particles;
            this.activeEnergyGain = activeEnergyGain;
            this.teamEnergyGain = teamEnergyGain;
            this.expireAtMillis = expireAtMillis;
        }

        public CharacterType getActiveCharacter() {
            return activeCharacter;
        }

        public String getSourceLabel() {
            return sourceLabel;
        }

        public String getElementLabel() {
            return elementLabel;
        }

        public int getParticles() {
            return particles;
        }

        public double getActiveEnergyGain() {
            return activeEnergyGain;
        }

        public double getTeamEnergyGain() {
            return teamEnergyGain;
        }

        public long getExpireAtMillis() {
            return expireAtMillis;
        }
    }
}
