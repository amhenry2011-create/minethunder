package com.minethunder.missile;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;

/**
 * 导弹发射判定：负责校验发射条件（雷达锁定、距离门、遮挡、多普勒盲区等）。
 * 半主动/主动弹需要 STT 锁定（发射方雷达），红外弹需要热源目标在视场内。
 */
public final class MissileLauncher {
    private MissileLauncher() {}

    /** 发射校验结果 */
    public enum LaunchResult {
        OK,
        NO_LOCK,          // 未锁定（雷达弹）
        OCCLUDED,         // 目标被遮挡
        IN_DOPPLER_NOTCH, // 目标在多普勒盲区
        OUT_OF_RANGE,     // 超出作战距离
        NO_SEEKER_TRACK  // 红外弹无有效热源
    }

    /**
     * 校验能否发射雷达制导导弹（SARH/ARH）。
     * @param spec 导弹参数
     * @param distanceToTarget 当前与目标距离
     * @param occluded 是否被遮挡
     * @param inNotch 是否在多普勒盲区
     * @param hasLock 雷达是否 STT 锁定
     */
    public static LaunchResult canLaunchRadar(MissileSpec spec, double distanceToTarget,
                                              boolean occluded, boolean inNotch, boolean hasLock) {
        if (!hasLock) return LaunchResult.NO_LOCK;
        if (occluded) return LaunchResult.OCCLUDED;
        if (inNotch) return LaunchResult.IN_DOPPLER_NOTCH;
        if (distanceToTarget > spec.maxDistance) return LaunchResult.OUT_OF_RANGE;
        return LaunchResult.OK;
    }

    /**
     * 校验能否发射红外弹（IR）：目标需在视场角内且在锁定距离内。
     */
    public static LaunchResult canLaunchIr(MissileSpec spec, Entity target, Player shooter) {
        if (target == null || !target.isAlive()) return LaunchResult.NO_SEEKER_TRACK;
        double dist = shooter.distanceTo(target);
        if (dist > spec.acquireRange()) return LaunchResult.OUT_OF_RANGE;
        return LaunchResult.OK;
    }
}
