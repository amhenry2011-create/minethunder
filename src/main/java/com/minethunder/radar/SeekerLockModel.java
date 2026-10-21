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
 * 环的颜色表示导引头锁定阶段；发射许可由 canLaunchArh/canLaunchSarh 控制。
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
    private static final double STABILITY_SECONDS = 1.6;
    private static final double ACQUIRE_QUALITY = 0.12;
    private static final double LOSE_QUALITY = 0.04;
    private static final double ENTER_QUALITY = 0.60;
    private static final double EXIT_QUALITY = 0.40;
    private static final double GIMBAL_EXIT_MARGIN_DEG = 3.0;

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

        double gimbal = Math.max(spec.lockAngleMaxDeg, 1.0);
        double angleMargin = clamp(1.0 - offBoresightDeg / gimbal, 0.0, 1.0);
        double rangeMargin = clamp(1.0 - range / Math.max(spec.acquireRange(), 1.0), 0.0, 1.0);
        double closureQuality = clamp(Math.abs(closure) / 300.0, 0.0, 1.0);
        double notchQuality = Math.abs(closure) < NOTCH_CLOSURE ? 0.1 : 1.0;

        quality = clamp(0.45 * angleMargin + 0.25 * rangeMargin
                + 0.15 * closureQuality + 0.15 * notchQuality, 0.0, 1.0);

        inEnvelope = offBoresightDeg <= gimbal
                && range <= spec.maxDistance
                && quality >= EXIT_QUALITY;

        // 离轴角超出万向角（带退出滞回）→ 导引头看不到目标，回到单白圈。
        if (offBoresightDeg > gimbal + GIMBAL_EXIT_MARGIN_DEG) {
            state = State.SEARCHING;
            progress = 0;
            stability = 0;
            return;
        }

        switch (state) {
            case SEARCHING -> {
                if (quality > ACQUIRE_QUALITY) {
                    state = State.ACQUIRING;
                }
            }
            case ACQUIRING -> {
                progress += dt * (0.35 + quality * 0.65) / ACQUIRE_SECONDS;
                if (progress >= 1.0) {
                    progress = 1.0;
                    state = State.TRACKING;
                    stability = 0.0;
                } else if (quality < LOSE_QUALITY) {
                    progress -= dt * 1.2;
                    if (progress <= 0.0) {
                        progress = 0.0;
                        state = State.SEARCHING;
                    }
                }
            }
            case TRACKING -> {
                stability = clamp(stability + dt / STABILITY_SECONDS, 0.0, 1.0);
                if (stability >= 1.0 && inEnvelope && quality >= ENTER_QUALITY) {
                    state = State.HIGH;
                } else if (quality < EXIT_QUALITY && !inEnvelope) {
                    state = State.ACQUIRING;
                    progress = clamp(progress - dt * 0.8, 0.15, 1.0);
                }
            }
            case HIGH -> {
                if (!inEnvelope || quality < EXIT_QUALITY) {
                    state = State.TRACKING;
                    stability = clamp(stability - dt * 0.5, 0.0, 1.0);
                }
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
