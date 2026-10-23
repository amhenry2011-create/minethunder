package com.minethunder.missile;

import com.minethunder.radar.RadarCore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 红外弹（类似 AIM-9）：发射即锁热源，全程追目标，可被热焰弹欺骗。
 */
public class IrMissileArrow extends MissileArrow {
    private int seekerLostTicks;

    public IrMissileArrow(EntityType<? extends MissileArrow> type, Level level, MissileSpec spec) {
        super(type, level, spec);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("MTSeekerLostTicks", seekerLostTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        seekerLostTicks = tag.getInt("MTSeekerLostTicks");
    }

    @Override
    protected Entity resolveTarget() {
        Entity target = getTarget();
        boolean sun = target != null && IrTargeting.isSunMarker(target);
        if (target == null || !target.isAlive()
                || IrHeat.heat(target) < IrTargeting.HEAT_THRESHOLD
                || (!sun && RadarCore.isOccluded(level(), position(),
                target.getBoundingBox().getCenter()))) {
            return loseSeeker();
        }
        Vec3 forward = getDeltaMovement().lengthSqr() > 1.0E-6
                ? getDeltaMovement().normalize() : getViewVector(1.0F).normalize();
        Vec3 to = target.getBoundingBox().getCenter().subtract(position());
        if (to.lengthSqr() < 1.0E-6) return loseSeeker();
        double angle = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, forward.dot(to.normalize())))));
        if (angle > spec.angleMaxDeg) return loseSeeker();
        seekerLostTicks = 0;
        return target;
    }

    /**
     * 导引头丢失热源：不自爆，转 IOG（最后已知航迹）继续飞行；
     * 制导时长/最大距离由基类统一裁决。
     */
    private Entity loseSeeker() {
        seekerLostTicks++;
        return null;
    }
}
