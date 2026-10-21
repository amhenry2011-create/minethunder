package com.minethunder.radar;

/**
 * 一个 RWR 接触：方位相对本机视线，距离单位为格。
 * tag 为雷达来源玩家首字母；导弹接触为空。
 * ageTicks 仅用于 SRC PD 搜索圆圈的 2 秒淡出。
 */
public record RwrContact(int sourceId, RwrThreat threat, double bearingDeg,
                         double elevationDeg, double range, String tag, int ageTicks) {
}
