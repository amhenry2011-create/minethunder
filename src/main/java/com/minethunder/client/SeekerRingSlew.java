package com.minethunder.client;

/**
 * 雷达导引头锁定环位置：先在屏幕中央保持 1 秒，然后瞬间移动到雷达锁定目标。
 * 到达目标后才允许进入截获/稳定度判定。
 */
public final class SeekerRingSlew {

    private static final double DEFAULT_HOLD_SECONDS = 1.0;
    private double x;
    private double y;
    private boolean aligned;
    private double holdRemaining = DEFAULT_HOLD_SECONDS;

    public void resetToCenter(double centerX, double centerY) {
        resetToCenter(centerX, centerY, DEFAULT_HOLD_SECONDS);
    }

    public void resetToCenter(double centerX, double centerY, double holdSeconds) {
        this.x = centerX;
        this.y = centerY;
        this.aligned = false;
        this.holdRemaining = Math.max(0.0, holdSeconds);
    }

    /**
     * @param targetX 目标屏幕 X
     * @param targetY 目标屏幕 Y
     * @param dt 秒
     */
    public void update(double targetX, double targetY, double dt) {
        if (dt <= 0.0) return;
        if (!aligned) {
            holdRemaining -= dt;
            if (holdRemaining > 0.0) return;
            x = targetX;
            y = targetY;
            aligned = true;
            return;
        }
        // 锁位后环跟随雷达锁定目标，不再平滑漂移。
        x = targetX;
        y = targetY;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public boolean aligned() {
        return aligned;
    }
}
