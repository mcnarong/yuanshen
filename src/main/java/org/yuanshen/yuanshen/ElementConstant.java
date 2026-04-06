package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;
import org.bukkit.potion.PotionEffectType;

public class ElementConstant {
    public static final String FIRE_KEY = "element_fire";
    public static final String WATER_KEY = "element_water";
    public static final String ICE_KEY = "element_ice";
    public static final String ELECTRO_KEY = "element_electro";
    public static final String ANEMO_KEY = "element_anemo";
    public static final String GEO_KEY = "element_geo";
    public static final String DENDRO_KEY = "element_dendro";
    public static final String FROZEN_KEY = "frozen";

    public static final String BLOOM_SEED_KEY = "bloom_seed";
    public static final String QUICKEN_KEY = "quicken";
    public static final String AGGRAVATE_KEY = "aggravate";
    public static final String BURNING_KEY = "burning";
    public static final String ELECTRO_CHARGED_KEY = "electro_charged";

    public static final String FIRE_TAG = ChatColor.RED + "【火元素】";
    public static final String WATER_TAG = ChatColor.BLUE + "【水元素】";
    public static final String ICE_TAG = ChatColor.AQUA + "【冰元素】";
    public static final String ELECTRO_TAG = ChatColor.DARK_PURPLE + "【雷元素】";
    public static final String ANEMO_TAG = ChatColor.GRAY + "【风元素】";
    public static final String GEO_TAG = ChatColor.GOLD + "【岩元素】";
    public static final String DENDRO_TAG = ChatColor.GREEN + "【草元素】";

    public static final PotionEffectType SLOWNESS;
    public static final PotionEffectType RESISTANCE;

    static {
        PotionEffectType slowness = PotionEffectType.getByName("SLOWNESS");
        if (slowness == null) {
            slowness = PotionEffectType.getByName("SLOW");
        }
        SLOWNESS = slowness;

        PotionEffectType resistance = PotionEffectType.getByName("DAMAGE_RESISTANCE");
        if (resistance == null) {
            resistance = PotionEffectType.getByName("RESISTANCE");
        }
        RESISTANCE = resistance;
    }
}
