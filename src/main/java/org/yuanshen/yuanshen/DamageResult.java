package org.yuanshen.yuanshen;

public class DamageResult {
    private double physicalDamage;        // 攻击力（物理部分）
    private double elementalDamage;       // 元素部分最终伤害（已包含元素伤害加成，不含反应）
    private boolean isCrit;                // 是否暴击
    private String element;                // 攻击元素
    private PlayerStats playerStats;

    public double getPhysicalDamage() { return physicalDamage; }
    public void setPhysicalDamage(double physicalDamage) { this.physicalDamage = physicalDamage; }

    public double getElementalDamage() { return elementalDamage; }
    public void setElementalDamage(double elementalDamage) { this.elementalDamage = elementalDamage; }

    public boolean isCrit() { return isCrit; }
    public void setCrit(boolean crit) { isCrit = crit; }

    public String getElement() { return element; }
    public void setElement(String element) { this.element = element; }

    public PlayerStats getPlayerStats() { return playerStats; }
    public void setPlayerStats(PlayerStats playerStats) { this.playerStats = playerStats; }

    // 辅助方法：获取未暴击的总伤害（物理+元素，不含反应）
    public double getRawTotalDamage() {
        return physicalDamage + elementalDamage;
    }
}