package com.minethunder.radar;

import com.minethunder.missile.MissileSpec;

/**
 * 导引头锁定/跟踪稳定环模型。
 *
 * 状态对应 HUD：
 * - SEARCHING：中心白色单圈，雷达导引头尚未对准。
 * - ACQUIRING：白色倒计时环；ARH 可发射，SARH 不可发射。
 * - TRACKING：红色倒计时环；ARH/SARH 均可发射。
 * - HIGH：红色双环；高置信稳定锁定。
 *
 * 离轴角只影响信号质量（填充速度），不阻断推进或发射：
 * 只要存在雷达锁定（硬锁/ TWS / BVR 远程锁）就能走完白/红环阶段并发射，
 * 大离轴也可发射；发射许可由 canLaunchArh/canLaunchSarh 控制。
 */
public final class SeekerLockModel {

    public enum State {
        SEARCHING,
        ACQUIRING,
        TRACKING,
        HIGH
    }

    private static final double NOTCH_CLOSURE = RadarCore.DOPPLER_NOTCH_THRESHOLD;
    private static final double ACQUIRE_SECONDS = 1.6;
    private static final double ACQUIRE_QUALITY = 0.12;
    private static final double LOSE_QUALITY = 0.04;
    private static final double ENTER_QUALITY = 0.60;
    private static final double EXIT_QUALITY = 0.40;

    /**
     * 极高信号质量：白弧/红弧可在一两 tick 内直接填满（不做阈值跳段，
     * 依旧按 白弧→红弧→红双 的链条推进，只是每阶段瞬时到底）。
     */
    private static final double INSTANT_QUALITY = 0.90;

    /**
     * 锁定成立后的信号质量下限（保底）：离轴/距离/接近率只影响填充速度，
     * 任何锁定（硬锁 / TWS / BVR）都能走完白/红环阶段并发射，不会被卡在 SEARCHING。
     */
    private static final double LOCKED_QUALITY_FLOOR = 0.5;

    private State state = State.SEARCHING;
    private double progress;
    private double stability;
    private double quality;
    private boolean inEnvelope;
    private boolean radarLocked;

    public State state() {
        return state;
    }

    public double progress() {
        return progress;
    }

    public double stability() {
        return stability;
    }

    public double quality() {
        return quality;
    }

    public boolean inEnvelope() {
        return inEnvelope;
    }

    public boolean radarLocked() {
        return radarLocked;
    }

    /** 红色倒计时/红色双环阶段。 */
    public boolean isRed() {
        return state == State.TRACKING || state == State.HIGH;
    }

    /** 中心白圈阶段禁止发射；导引头瞬移锁位后 ARH 可发射。 */
    public boolean canLaunchArh() {
        return state != State.SEARCHING;
    }

    /** SARH 需要红色倒计时环（TRACKING）或红色双环（HIGH）。 */
    public boolean canLaunchSarh() {
        return state == State.TRACKING || state == State.HIGH;
    }

    public void reset() {
        state = State.SEARCHING;
        progress = 0;
        stability = 0;
        quality = 0;
        inEnvelope = false;
        radarLocked = false;
    }

    /**
     * 每 tick 更新。
     *
     * @param dt              秒
     * @param seekerOn        导引头是否开启
     * @param radarLocked     是否有有效雷达锁定
     * @param offBoresightDeg 目标相对机头/导引头轴线的离轴角
     * @param range           目标距离（米）
     * @param closure         接近速度（m/s，正=接近）
     * @param spec            当前导弹参数
     */
    public void update(double dt, boolean seekerOn, boolean radarLocked,
                       double offBoresightDeg, double range, double closure,
                       MissileSpec spec) {
        this.radarLocked = radarLocked;

        if (!seekerOn || !radarLocked || spec == null) {
            reset();
            return;
        }

        // 离轴角只压低信号质量（万向角内为准，超出仍有基础质量），不阻断推进。
        double gimbal = Math.max(spec.lockAngleMaxDeg, 1.0);
        double angleMargin = clamp(1.0 - offBoresightDeg / gimbal, 0.0, 1.0);
        double rangeMargin = clamp(1.0 - range / Math.max(spec.acquireRange(), 1.0), 0.0, 1.0);
        double closureQuality = clamp(Math.abs(closure) / 300.0, 0.0, 1.0);
        double notchQuality = Math.abs(closure) < NOTCH_CLOSURE ? 0.1 : 1.0;

        quality = clamp(0.45 * angleMargin + 0.25 * rangeMargin
                + 0.15 * closureQuality + 0.15 * notchQuality, 0.0, 1.0);
        // 锁定即可推进：保底质量保证大离轴/远距离/低接近率也能走完白/红环。
        quality = Math.max(quality, LOCKED_QUALITY_FLOOR);

        inEnvelope = range <= spec.maxDistance && quality >= EXIT_QUALITY;

        switch (state) {
            case SEARCHING -> {
                if (quality >= INSTANT_QUALITY) {
                    // 信号极好：白弧瞬时填满，下一 tick 进红弧。
                    state = State.ACQUIRING;
                    progress = 1.0;
                } else if (quality > ACQUIRE_QUALITY) {
                    state = State.ACQUIRING;
                    progress = seedProgress(quality);
                }
            }
            case ACQUIRING -> {
                progress += dt * fillRate(quality);
                if (quality >= INSTANT_QUALITY) {
                    progress = 1.0;
                }
                if (progress >= 1.0) {
                    progress = 1.0;
                    state = State.TRACKING;
                    stability = 0.0;
                } else if (progress <= 0.0) {
                    progress = 0.0;
                    state = State.SEARCHING;
                } else if (quality < LOSE_QUALITY) {
                    progress -= dt * 1.2;
                    if (progress <= 0.0) {
                        progress = 0.0;
                        state = State.SEARCHING;
                    }
                }
            }
            case TRACKING -> {
                stability = clamp(stability + dt * fillRate(quality), 0.0, 1.0);
                if (quality >= INSTANT_QUALITY) {
                    stability = 1.0;
                }
                if (stability >= 1.0 && inEnvelope && quality >= ENTER_QUALITY) {
                    state = State.HIGH;
                } else if (quality < EXIT_QUALITY) {
                    // 信号掉出包线：红色倒计时退回白色截获段。
                    state = State.ACQUIRING;
                    progress = Math.max(0.3, progress);
                }
            }
            case HIGH -> {
                if (!inEnvelope || quality < EXIT_QUALITY) {
                    state = State.TRACKING;
                    stability = 0.5;
                }
            }
        }
    }

    /** 每阶段填充速率（/秒）：质量越高填得越快，条件一般仍需约 1.6 s。 */
    private static double fillRate(double quality) {
        return (0.35 + quality * 0.65) / ACQUIRE_SECONDS;
    }

    /** 进入 ACQUIRING 时按当前质量播种白弧进度，避免从 0 重新爬。 */
    private static double seedProgress(double quality) {
        double span = INSTANT_QUALITY - ACQUIRE_QUALITY;
        if (span <= 0.0) {
            return 0.0;
        }
        return clamp((quality - ACQUIRE_QUALITY) / span, 0.0, 0.98);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
