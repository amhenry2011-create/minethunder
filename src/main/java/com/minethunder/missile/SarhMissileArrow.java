package com.minethunder.missile;

import com.minethunder.radar.RadarCore;
import com.minethunder.radar.ServerRadarState;
import net.minecraft.nbt.CompoundTag;
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
    /** 本 tick 是否仍有有效的载机照射。 */
    private boolean illuminated;

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
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (shooterId != null) tag.putString("MTShooter", shooterId.toString());
        tag.putInt("MTLostLockTicks", lostLockTicks);
        tag.putBoolean("MTTestGuided", testGuided);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        shooterId = tag.contains("MTShooter")
                ? parseUuid(tag.getString("MTShooter")) : null;
        lostLockTicks = tag.getInt("MTLostLockTicks");
        testGuided = tag.getBoolean("MTTestGuided");
        illuminated = false;
    }

    @Override
    protected Entity resolveTarget() {
        if (testGuided && testMissile) {
            Entity testTarget = getTarget();
            if (testTarget != null && testTarget.isAlive()) {
                lostLockTicks = 0;
                illuminated = true;
                return testTarget;
            }
            return loseIllumination();
        }
        // SARH：发射方须保持 STT 照射，或以 BVR 远程软锁当作持续照射，否则失锁
        if (shooterId == null) return loseIllumination();
        Player shooter = level().getPlayerByUUID(shooterId);
        if (shooter == null) return loseIllumination();

        Entity target = getTarget();
        if (target == null || !target.isAlive()) return loseIllumination();

        Vec3 eye = shooter.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        boolean sttIllumination = ServerRadarState.isLocked(shooter, target.getId());
        boolean bvrIllumination = bvrSoftIlluminated(shooter, target);
        boolean nowIlluminated = (sttIllumination || bvrIllumination)
                && shooter.level() == target.level()
                && shooter.distanceTo(target) <= spec.maxDistance
                && !RadarCore.isOccludedForTarget(shooter.level(), eye, targetPos,
                        RadarCore.classify(target));
        this.illuminated = nowIlluminated;
        if (nowIlluminated) {
            lostLockTicks = 0;
            return target;
        }
        return loseIllumination();
    }

    /** BVR 远程软锁充当照射：当前锁目标 uuid 与目标一致即视为载机波束持续照射。 */
    private boolean bvrSoftIlluminated(Player shooter, Entity target) {
        java.util.UUID bvrUuid = ServerRadarState.getBvrTargetUuid(shooter);
        return bvrUuid != null && bvrUuid.equals(target.getUUID());
    }

    private Entity loseIllumination() {
        illuminated = false;
        lostLockTicks++;
        if (lostLockTicks > Math.max(1, spec.prolongationTimeMax * 20)) {
            detonate(null);
            return null;
        }
        return null;
    }

    @Override
    protected boolean datalinkValid() {
        if (testGuided && testMissile) return true;
        return illuminated && super.datalinkValid();
    }
}
