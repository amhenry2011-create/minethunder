package com.minethunder.missile;

/**
 * 三种典型导弹的默认参数（参考战雷同类弹药 CDK 变量，单位：米 / 秒 / N / kg / G / 度）。
 * 后续可通过配置覆盖。
 */
public final class MissileSpecs {
    private MissileSpecs() {}

    /**
     * 半主动雷达弹（类似 AIM-7 麻雀）。
     * 构造顺序见 {@link MissileSpec} 构造器。
     */
    public static final MissileSpec SARH = new MissileSpec(
            MissileSpec.Guidance.SARH, "SARH",
            4.0,        // 近炸引信 4m
            0, 250.0,   // WT R-27ER 锁定距离 25 km，按 10 b/km 缩放
            10.0,       // fov 10°
            20.0,       // lockAngleMax 20°
            25.0,       // angleMax 25°
            12.0,       // rateMax 12°/s
            10.0,       // gateWidth 10°
            3.0,        // prolongationTimeMax 3s
            0,          // startSpeed 载机速度 + 20 m/s 弹射
            48.0,       // endSpeed ≈ 鞘翅极速 40 b/s × 1.2
            1000.0,     // WT R-27ER 发射距离 100 km，按 10 b/km 缩放
            60.0,       // WT R-27ER 制导时间 60 s
            12000.0,    // force 12kN
            15.0,       // timeFire 15s
            350.0,      // WT R-27ER 质量 350 kg
            220.0,      // 燃尽质量
            0.05,       // CxK
            70.0,       // 游戏化机动提升（WT 35 G 的两倍）
            0.0,        // 点火后立即制导
            0.0         // 无导引头开机距离（SARH 全程照射）
    );

    /**
     * 主动雷达弹（类似 AIM-120 AMRAAM）。
     */
    public static final MissileSpec ARH = new MissileSpec(
            MissileSpec.Guidance.ARH, "ARH",
            4.0,
            0, 160.0,   // WT R-77-1 锁定距离 16 km，按 10 b/km 缩放
            12.0,
            25.0,
            30.0,
            15.0,
            12.0,
            3.0,
            0,
            48.0,
            1200.0,    // WT R-77-1 发射距离 120 km，按 10 b/km 缩放
            120.0,      // WT R-77-1 制导时间 120 s
            15000.0,
            20.0,
            190.0,      // WT R-77-1 质量 190 kg
            120.0,      // 燃尽质量
            0.04,
            100.0,      // 游戏化机动提升（WT 50 G 的两倍）
            0.0,        // 0.5 秒下飘后立即制导
            160.0       // WT 导引头开机距离 16 km（按 10 b/km）
    );

    /**
     * 红外弹（类似 AIM-9 响尾蛇）。
     */
    public static final MissileSpec IR = new MissileSpec(
            MissileSpec.Guidance.IR, "IR",
            4.0,
            160.0,      // 后半球锁定距离 16 b（基线 11 b，按需求加远）
            50.0,       // 全向锁定距离 5 b（基线 3.4 b，按需求加远）
            8.0,        // fov 8°（小圆环）
            30.0,       // lockAngleMax 30°（大圆环）
            35.0,       // angleMax 35°
            20.0,       // rateMax 20°/s
            3.0,        // gateWidth 3°
            5.0,        // prolongationTimeMax 5s
            0,
            48.0,       // endSpeed ≈ 鞘翅极速 40 b/s × 1.2
            300.0,      // WT 发射距离 30 km（按 10 b/km）
            20.0,       // timeLife 20s
            6000.0,     // force 6kN
            5.0,        // timeFire 5s
            105.0,      // WT R-73 质量 105 kg
            70.0,       // 燃尽质量
            0.06,
            80.0,       // 游戏化机动提升（WT 40 G 的两倍）
            0.0,        // 点火后立即制导
            0.0         // 无导引头开机距离（IR 全程自导）
    );
}
