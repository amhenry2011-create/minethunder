package com.minethunder.missile;

import com.minethunder.radar.RadarCore;
import com.minethunder.radar.ServerRadarState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 半主动雷达弹（类似 AIM-7）：依赖发射方持续雷达照射（STT）。
 * 目标由发射方雷达提供；照射中断后惯性飞行，超过延续时间自爆。
 */
public class SarhMissileArrow extends MissileArrow {
    /** 发射方玩家 UUID */
    private java.util.UUID shooterId;
    /** 连续失锁 tick，超过 prolongationTimeMax 后自爆 */
    private int lostLockTicks;
    /** 测试弹可绕过真实载机照射，直接追测试目标。 */
    private boolean testGuided;

    public SarhMissileArrow(EntityType<? extends MissileArrow> type, Level level, MissileSpec spec) {
        super(type, level, spec);
    }

    public void setShooter(Player shooter) {
        this.shooterId = shooter.getUUID();
    }

    public void setTestGuided(boolean testGuided) {
        this.testGuided = testGuided;
    }

    @Override
    protected Entity resolveTarget() {
        if (testGuided && testMissile) {
            Entity testTarget = getTarget();
            return testTarget != null && testTarget.isAlive() ? testTarget : null;
        }
        // SARH：发射方必须保持 STT 锁定，否则失锁
        if (shooterId == null) return null;
        Player shooter = level().getPlayerByUUID(shooterId);
        if (shooter == null) return null;

        Entity target = getTarget();
        if (target == null || !target.isAlive()) return null;

        Vec3 eye = shooter.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        boolean illuminated = ServerRadarState.isLocked(shooter, target.getId())
                && shooter.level() == target.level()
                && shooter.distanceTo(target) <= spec.maxDistance
                && !RadarCore.isOccludedForTarget(shooter.level(), eye, targetPos,
                        RadarCore.classify(target));
        if (illuminated) {
            lostLockTicks = 0;
            return target;
        }

        // 失锁后继续使用最后航迹做 IOG 修正；超过延续时间自爆。
        lostLockTicks++;
        if (lostLockTicks > Math.max(1, spec.prolongationTimeMax * 20)) {
            detonate(null);
            return null;
        }
        return target;
    }
}
