
package org.yuanshen.yuanshen;

import java.util.HashMap;
import java.util.Map;

public class PlayerStats {
    public static final String PHYSICAL_KEY = "physical";

    private double attackDamage;         // 攻击力
    private double critRate;             // 暴击率 (0.05 = 5%)
    private double critDamage;           // 暴击伤害 (0.5 = 50%)
    private double elementMastery;       // 元素精通
    private double energyRecharge;       // 元素充能效率
    private double healingBonus;         // 治疗加成
    private double defense;              // 防御力
    private double health;               // 生命力（最大生命值）
    private long timestamp;              // 时间戳

    private final Map<String, Double> elementBonus = new HashMap<>();
    private final Map<String, Double> resistances = new HashMap<>();

    public PlayerStats() {
        elementBonus.put(ElementConstant.FIRE_KEY, 0.0);
        elementBonus.put(ElementConstant.WATER_KEY, 0.0);
        elementBonus.put(ElementConstant.ICE_KEY, 0.0);
        elementBonus.put(ElementConstant.ELECTRO_KEY, 0.0);
        elementBonus.put(ElementConstant.ANEMO_KEY, 0.0);
        elementBonus.put(ElementConstant.GEO_KEY, 0.0);
        elementBonus.put(ElementConstant.DENDRO_KEY, 0.0);
        elementBonus.put(PHYSICAL_KEY, 0.0);

        resistances.put(ElementConstant.FIRE_KEY, 0.0);
        resistances.put(ElementConstant.WATER_KEY, 0.0);
        resistances.put(ElementConstant.ICE_KEY, 0.0);
        resistances.put(ElementConstant.ELECTRO_KEY, 0.0);
        resistances.put(ElementConstant.ANEMO_KEY, 0.0);
        resistances.put(ElementConstant.GEO_KEY, 0.0);
        resistances.put(ElementConstant.DENDRO_KEY, 0.0);
        resistances.put(PHYSICAL_KEY, 0.0);
    }

    public double getAttackDamage() { return attackDamage; }
    public void setAttackDamage(double attackDamage) { this.attackDamage = attackDamage; }

    public double getCritRate() { return critRate; }
    public void setCritRate(double critRate) { this.critRate = Math.min(critRate, 1.0); }

    public double getCritDamage() { return critDamage; }
    public void setCritDamage(double critDamage) { this.critDamage = critDamage; }

    public double getElementMastery() { return elementMastery; }
    public void setElementMastery(double elementMastery) { this.elementMastery = elementMastery; }

    public double getEnergyRecharge() { return energyRecharge; }
    public void setEnergyRecharge(double energyRecharge) { this.energyRecharge = energyRecharge; }

    public double getHealingBonus() { return healingBonus; }
    public void setHealingBonus(double healingBonus) { this.healingBonus = healingBonus; }

    public double getDefense() { return defense; }
    public void setDefense(double defense) { this.defense = defense; }

    public double getHealth() { return health; }
    public void setHealth(double health) { this.health = health; }

    public double getElementBonus(String element) { return elementBonus.getOrDefault(element, 0.0); }
    public void setElementBonus(String element, double bonus) { elementBonus.put(element, bonus); }

    public double getResistance(String element) { return resistances.getOrDefault(element, 0.0); }
    public void setResistance(String element, double value) { resistances.put(element, value); }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public PlayerStats copy() {
        PlayerStats copy = new PlayerStats();
        copy.attackDamage = attackDamage;
        copy.critRate = critRate;
        copy.critDamage = critDamage;
        copy.elementMastery = elementMastery;
        copy.energyRecharge = energyRecharge;
        copy.healingBonus = healingBonus;
        copy.defense = defense;
        copy.health = health;
        copy.timestamp = timestamp;
        copy.elementBonus.putAll(elementBonus);
        copy.resistances.putAll(resistances);
        return copy;
    }
}
