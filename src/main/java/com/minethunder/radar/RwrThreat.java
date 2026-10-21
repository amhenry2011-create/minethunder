package com.minethunder.radar;

/** RWR 威胁等级。 */
public enum RwrThreat {
    SEARCH(0, "S"),
    TRACK(1, "T"),
    LOCK(2, "L"),
    MISSILE(3, "M");

    private final int code;
    private final String symbol;

    RwrThreat(int code, String symbol) {
        this.code = code;
        this.symbol = symbol;
    }

    public int code() {
        return code;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isMissile() {
        return this == MISSILE;
    }

    public boolean isLockLike() {
        return this == LOCK || this == MISSILE;
    }

    public String displayName() {
        return switch (this) {
            case SEARCH -> "搜索";
            case TRACK -> "跟踪";
            case LOCK -> "锁定";
            case MISSILE -> "导弹";
        };
    }

    public static RwrThreat byCode(int code) {
        for (RwrThreat value : values()) if (value.code == code) return value;
        return SEARCH;
    }
}
