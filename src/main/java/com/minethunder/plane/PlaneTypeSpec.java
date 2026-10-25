package com.minethunder.plane;

/**
 * 三款固定翼飞机的气动、质量、结构过载与尺寸参数。
 */
public enum PlaneTypeSpec {
    SMALL_PLANE(
            "small_plane",
            "轻型战斗教练机 (Small Plane)",
            1,
            1850.0,
            16.2,
            26.0,     // 基础推力加速度 (m/s^2)
            12.5,     // 干净构型失速速度 Vs1 (m/s)
            62.0,     // 结构最大限制空速 Vne (m/s)
            9.0f,     // 最大正过载 (+G)
            -3.0f,    // 最大负过载 (-G)
            145.0f,   // 滚转角速率 (deg/s)
            68.0f,    // 俯仰角速率 (deg/s)
            34.0f,    // 偏航角速率 (deg/s)
            80.0f,    // 机体结构生命值
            2.2f,     // 碰撞箱宽度
            1.35f,    // 碰撞箱高度
            PlaneEngineType.LIQUID
    ),
    LARGE_PLANE(
            "large_plane",
            "双座多用途战机 (Large Plane)",
            2,
            4200.0,
            28.0,
            32.0,     // 基础推力加速度 (m/s^2)
            14.0,     // 干净构型失速速度 Vs1 (m/s)
            78.0,     // 结构最大限制空速 Vne (m/s)
            7.5f,     // 最大正过载 (+G)
            -2.5f,    // 最大负过载 (-G)
            110.0f,   // 滚转角速率 (deg/s)
            54.0f,    // 俯仰角速率 (deg/s)
            26.0f,    // 偏航角速率 (deg/s)
            140.0f,   // 机体结构生命值
            2.8f,     // 碰撞箱宽度
            1.6f,     // 碰撞箱高度
            PlaneEngineType.JET
    ),
    CARGO_PLANE(
            "cargo_plane",
            "重型战术运输/武库机 (Cargo Plane)",
            4,
            9800.0,
            62.0,
            21.0,     // 基础推力加速度 (m/s^2)
            11.0,     // 干净构型失速速度 Vs1 (m/s)
            48.0,     // 结构最大限制空速 Vne (m/s)
            4.5f,     // 最大正过载 (+G)
            -1.5f,    // 最大负过载 (-G)
            52.0f,    // 滚转角速率 (deg/s)
            32.0f,    // 俯仰角速率 (deg/s)
            18.0f,    // 偏航角速率 (deg/s)
            240.0f,   // 机体结构生命值
            3.8f,     // 碰撞箱宽度
            2.1f,     // 碰撞箱高度
            PlaneEngineType.LIQUID
    );

    private final String id;
    private final String displayName;
    private final int maxPassengers;
    private final double massKg;
    private final double wingAreaM2;
    private final double baseThrustAccelMps2;
    private final double cleanStallSpeedMps;
    private final double maxSafeSpeedMps;
    private final float maxPositiveG;
    private final float maxNegativeG;
    private final float maxRollRateDegPerSec;
    private final float maxPitchRateDegPerSec;
    private final float maxYawRateDegPerSec;
    private final float maxHealth;
    private final float width;
    private final float height;
    private final PlaneEngineType defaultEngine;

    PlaneTypeSpec(String id, String displayName, int maxPassengers,
                  double massKg, double wingAreaM2, double baseThrustAccelMps2,
                  double cleanStallSpeedMps, double maxSafeSpeedMps,
                  float maxPositiveG, float maxNegativeG,
                  float maxRollRateDegPerSec, float maxPitchRateDegPerSec,
                  float maxYawRateDegPerSec, float maxHealth,
                  float width, float height, PlaneEngineType defaultEngine) {
        this.id = id;
        this.displayName = displayName;
        this.maxPassengers = maxPassengers;
        this.massKg = massKg;
        this.wingAreaM2 = wingAreaM2;
        this.baseThrustAccelMps2 = baseThrustAccelMps2;
        this.cleanStallSpeedMps = cleanStallSpeedMps;
        this.maxSafeSpeedMps = maxSafeSpeedMps;
        this.maxPositiveG = maxPositiveG;
        this.maxNegativeG = maxNegativeG;
        this.maxRollRateDegPerSec = maxRollRateDegPerSec;
        this.maxPitchRateDegPerSec = maxPitchRateDegPerSec;
        this.maxYawRateDegPerSec = maxYawRateDegPerSec;
        this.maxHealth = maxHealth;
        this.width = width;
        this.height = height;
        this.defaultEngine = defaultEngine;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int maxPassengers() {
        return maxPassengers;
    }

    public double massKg() {
        return massKg;
    }

    public double wingAreaM2() {
        return wingAreaM2;
    }

    public double baseThrustAccelMps2() {
        return baseThrustAccelMps2;
    }

    public double cleanStallSpeedMps() {
        return cleanStallSpeedMps;
    }

    public double maxSafeSpeedMps() {
        return maxSafeSpeedMps;
    }

    public float maxPositiveG() {
        return maxPositiveG;
    }

    public float maxNegativeG() {
        return maxNegativeG;
    }

    public float maxRollRateDegPerSec() {
        return maxRollRateDegPerSec;
    }

    public float maxPitchRateDegPerSec() {
        return maxPitchRateDegPerSec;
    }

    public float maxYawRateDegPerSec() {
        return maxYawRateDegPerSec;
    }

    public float maxHealth() {
        return maxHealth;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public PlaneEngineType defaultEngine() {
        return defaultEngine;
    }

    public static PlaneTypeSpec byOrdinal(int index) {
        PlaneTypeSpec[] values = values();
        if (index < 0 || index >= values.length) {
            return SMALL_PLANE;
        }
        return values[index];
    }
}
