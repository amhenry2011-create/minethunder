package com.minethunder.radar;

/**
 * 雷达模式枚举。
 */
public enum RadarMode {
    /** 脉冲多普勒搜索（SRC PD）：正常空中搜索模式 */
    SRC_PD("脉冲多普勒搜索"),
    /** 尝试锁定（ACQ PD）：临时锁定尝试状态，不参与模式循环 */
    ACQ_PD("截获 PD"),
    /** 边搜索边跟踪（Track While Scan） */
    TWS("边搜索边跟踪"),
    /** 地面移动目标指示，也可用于海上移动目标 */
    GMI("地面移动目标"),
    /** 海面搜索：只处理海上船实体 */
    SEA("海面搜索"),
    /** 格斗模式（ACM） */
    ACM("格斗"),
    /** 单目标跟踪（STT，硬锁定） */
    STT("单目标跟踪");

    private final String displayName;

    RadarMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String code() {
        return switch (this) {
            case SRC_PD -> "SRC PD";
            case ACQ_PD -> "ACQ PD";
            case TWS -> "TWS";
            case GMI -> "GMI";
            case SEA -> "SEA";
            case ACM -> "ACM";
            case STT -> "TRK PD";
        };
    }

    /** 是否为地面/海面搜索模式 */
    public boolean isSurfaceSearch() {
        return this == GMI || this == SEA;
    }

    /** 空对空 39/Notch 只作用于空中模式。 */
    public boolean usesNotch() {
        return this == SRC_PD || this == ACQ_PD || this == TWS || this == ACM || this == STT;
    }

    public double notchSusceptibility() {
        return switch (this) {
            case GMI, SEA -> 0.0;
            case ACQ_PD -> 0.55;
            case ACM -> 0.85;
            default -> 1.0;
        };
    }

    /** 是否可以通过 Alt+B / 快捷菜单进入的普通搜索模式。 */
    public boolean isSelectable() {
        return this != ACQ_PD && this != ACM && this != STT;
    }

    public boolean isTrackWhileScan() {
        return this == TWS;
    }

    /** 当前模式允许的目标类别。 */
    public boolean accepts(RadarTargetClass targetClass) {
        if (targetClass == null) return false;
        return switch (this) {
            case GMI -> targetClass == RadarTargetClass.GROUND || targetClass == RadarTargetClass.SEA;
            case SEA -> targetClass == RadarTargetClass.SEA;
            case STT -> true; // TRK PD 跟踪已锁定目标所属类别
            case TWS -> targetClass == RadarTargetClass.AIR;
            default -> targetClass == RadarTargetClass.AIR;
        };
    }
}
