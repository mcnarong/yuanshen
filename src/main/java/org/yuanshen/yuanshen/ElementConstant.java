package org.yuanshen.yuanshen;

import org.bukkit.ChatColor;

/**
 * 元素常量统一管理类
 */
public class ElementConstant {
    // 元素标记Key
    public static final String FIRE_KEY = "fire_attribute_mark";
    public static final String WATER_KEY = "shui";
    public static final String ICE_KEY = "bing";
    public static final String ELECTRO_KEY = "electro";
    public static final String ANEMO_KEY = "anemo";
    public static final String GEO_KEY = "geo";
    public static final String DENDRO_KEY = "dendro";

    // 元素Lore关键词
    public static final String FIRE_LORE = "火元素";
    public static final String WATER_LORE = "水元素";
    public static final String ICE_LORE = "冰元素";
    public static final String ELECTRO_LORE = "雷元素";
    public static final String ANEMO_LORE = "风元素";
    public static final String GEO_LORE = "岩元素";
    public static final String DENDRO_LORE = "草元素";

    // 元素聊天前缀
    public static final String FIRE_TAG = ChatColor.RED + "【火元素】";
    public static final String WATER_TAG = ChatColor.BLUE + "【水元素】";
    public static final String ICE_TAG = ChatColor.AQUA + "【冰元素】";
    public static final String ELECTRO_TAG = ChatColor.DARK_PURPLE + "【雷元素】";
    public static final String ANEMO_TAG = ChatColor.GRAY + "【风元素】";
    public static final String GEO_TAG = ChatColor.GOLD + "【岩元素】";
    public static final String DENDRO_TAG = ChatColor.GREEN + "【草元素】";
}