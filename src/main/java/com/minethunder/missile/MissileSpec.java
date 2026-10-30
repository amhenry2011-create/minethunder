package com.minethunder.missile;

/**
 * 导弹关键参数模型。每枚导弹（箭）独立一份，决定其性能与可规避性。
 * 参数名对齐战雷 CDK Missile Variables（见 SPEC §2.1）。
 */
public final class MissileSpec {
    /** 弹种类型 */
    public enum Guidance {
        /** 主动雷达弹：末段自开雷达寻的 */
        ARH,
        /** 半主动雷达弹：依赖发射方持续照射 */
        SARH,
        /** 红外弹：追踪热源 */
        IR
    }

    public final Guidance guidance;
    public final String name;

    /** 近炸引信引爆半径（米） */
    public final double proximityFuseRadius;

    // ---- 红外导引头（CDK 变量） ----
    /** 后半球最大锁定距离（rangeBand0，米） */
    public final double rearAspectLockRange;
    /** 全向最大锁定距离（rangeBand1，米） */
    public final double allAspectLockRange;
    /** 导引头视场角 fov（度）= HUD 小圆环 */
    public final double seekerFovDeg;
    /** 发射前离轴角 lockAngleMax（度）= HUD 大圆环 */
    public final double lockAngleMaxDeg;
    /** 发射后偏转角 angleMax（度） */
    public final double angleMaxDeg;
    /** 最大角跟踪速率 rateMax（°/s） */
    public final double rateMaxDegPerSec;
    /** 距离门 gateWidth（度）：内视场，忽略其外新热源 */
    public final double gateWidthDeg;
    /** 丢失目标后继续追踪最长时间 prolongationTimeMax（秒） */
    public final double prolongationTimeMax;

    // ---- 飞行性能（CDK 变量） ----
    /** 初始相对速度 startSpeed（m/s），0 = 载机速度 + 20 m/s 弹射 */
    public final double startSpeed;
    /** 气动最大速度 endSpeed（m/s） */
    public final double endSpeed;
    /** 最大飞行距离 maxDistance（米） */
    public final double maxDistance;
    /** 总飞行时间 timeLife（秒） */
    public final double timeLife;
    /** 发动机推力 force（N） */
    public final double force;
    /** 发动机工作时间 timeFire（秒） */
    public final double timeFire;
    /** 发射前质量 mass（kg） */
    public final double mass;
    /** 燃尽后质量 massEnd（kg） */
    public final double massEnd;
    /** 阻力系数 CxK */
    public final double cxK;
    /** 最大过载 reqAccelMax（G） */
    public final double maxG;
    /** 发射后直飞时间 timeOut（秒） */
    public final double timeOut;
    /** 导引头开机距离（米）：主动弹末段雷达开机自主搜索（pitbull） */
    public final double seekerActivationRange;

    public MissileSpec(Guidance guidance, String name,
                       double proximityFuseRadius,
                       double rearAspectLockRange, double allAspectLockRange,
                       double seekerFovDeg, double lockAngleMaxDeg, double angleMaxDeg,
                       double rateMaxDegPerSec, double gateWidthDeg, double prolongationTimeMax,
                       double startSpeed, double endSpeed, double maxDistance, double timeLife,
                       double force, double timeFire, double mass, double massEnd,
                       double cxK, double maxG, double timeOut, double seekerActivationRange) {
        this.guidance = guidance;
        this.name = name;
        this.proximityFuseRadius = proximityFuseRadius;
        this.rearAspectLockRange = rearAspectLockRange;
        this.allAspectLockRange = allAspectLockRange;
        this.seekerFovDeg = seekerFovDeg;
        this.lockAngleMaxDeg = lockAngleMaxDeg;
        this.angleMaxDeg = angleMaxDeg;
        this.rateMaxDegPerSec = rateMaxDegPerSec;
        this.gateWidthDeg = gateWidthDeg;
        this.prolongationTimeMax = prolongationTimeMax;
        this.startSpeed = startSpeed;
        this.endSpeed = endSpeed;
        this.maxDistance = maxDistance;
        this.timeLife = timeLife;
        this.force = force;
        this.timeFire = timeFire;
        this.mass = mass;
        this.massEnd = massEnd;
        this.cxK = cxK;
        this.maxG = maxG;
        this.timeOut = timeOut;
        this.seekerActivationRange = seekerActivationRange;
    }

    /** 离架后到发动机点火的时间（ARH 先无动力下飘 0.5 秒）。 */
    public double ignitionDelay() {
        return guidance == Guidance.ARH ? 0.5 : 0.0;
    }

    /** 是否在动力段（t 为发射后经过的秒数）。 */
    public boolean isBoosting(double t) {
        return t >= ignitionDelay() && t <= ignitionDelay() + timeFire;
    }

    /**
     * 当前制导视场：全弹种使用弹体可用转向角 angleMax。
     * seekerFov 只用于导引头捕获判断与 HUD 内环展示，不再作为制导硬限，
     * 避免发射允许角（lockAngleMax）大于制导角导致末段直接失控。
     */
    public double guidanceFovDeg(boolean pitbullActive) {
        return angleMaxDeg;
    }

    /** 发射时导引头可捕获目标的距离：红外用全向锁定距离，雷达弹用锁定距离 */
    public double acquireRange() {
        if (guidance == Guidance.IR) {
            return allAspectLockRange;
        }
        return allAspectLockRange > 0 ? allAspectLockRange : 50000.0;
    }

    /** 当前质量（kg）：动力段按时间从 mass 线性降到 massEnd。 */
    public double massAt(double t) {
        if (timeFire <= 0.0) return massEnd;
        double fraction = Math.max(0.0, Math.min(1.0,
                (t - ignitionDelay()) / timeFire));
        return mass + (massEnd - mass) * fraction;
    }

    /** 动力段推力加速度（m/s²）：F / 当前质量。 */
    public double boostAcceleration(double t) {
        return force / Math.max(massAt(t), 1.0);
    }

    /** 滑翔段阻力减速度（m/s²），随速度平方增长（简化模型）。 */
    public double dragDeceleration(double speed, double mass) {
        return Math.min(80.0, cxK * speed * speed / Math.max(mass, 1.0) * 10.0);
    }

    /** 发动机理想末速（真空 Δv，m/s）：ve · ln(m0/m1)，用于包线/NEZ 估算。 */
    public double idealBurnoutSpeed() {
        if (timeFire <= 0.0 || mass <= massEnd || massEnd <= 0.0) {
            return Math.max(endSpeed, 1.0);
        }
        double massFlow = (mass - massEnd) / timeFire;
        return Math.max(endSpeed, force / massFlow * Math.log(mass / massEnd));
    }

    /** 最大可用侧向加速度（m/s²）：G 限制 */
    public double lateralAcceleration() {
        return maxG * 9.81;
    }

    /** 高度储能/滑翔阶段：IR 保持直扑，不参与远程能量管理。 */
    public boolean energyEnabled() {
        return guidance != Guidance.IR;
    }

    /** 超过该距离开始考虑加速爬升。 */
    public double climbStartRange() {
        return 300.0;
    }

    /** 进入末段俯冲的距离。 */
    public double terminalRange() {
        return 120.0;
    }

    /** 最大爬升角，用于限制 loft 瞄准点高度。 */
    public double maxClimbAngleDeg() {
        return 25.0;
    }

    /** 相对目标点的爬升瞄准高度。 */
    public double climbAltitude() {
        return 80.0;
    }

    /** 能量不足时允许的最低滑翔速度。 */
    public double minEnergySpeed() {
        return 5.0;
    }
}
