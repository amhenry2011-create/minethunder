package com.minethunder.missile;

import com.minethunder.radar.RadarCore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 导弹目标捕获：发射瞬间按发射者视线方向寻找目标（后续由雷达锁定系统提供目标）。
 * 遵循 SPEC §4：被地形遮挡的目标不可捕获。
 */
public final class MissileTargeting {
    private MissileTargeting() {}

    /**
     * 在发射者视线方向上寻找最近的、未被遮挡的存活目标。
     * @param coneDeg 视线锥半张角（度）：红外弹用 lockAngleMax，雷达弹用锁定角
     */
    public static Entity findLookTarget(Level level, LivingEntity shooter, double maxRange, double coneDeg) {
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getViewVector(1.0F).normalize();
        double cosLimit = Math.cos(Math.toRadians(coneDeg));
        Entity shooterRoot = shooter.getRootVehicle();

        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        AABB area = shooter.getBoundingBox().inflate(maxRange);
        for (Entity e : level.getEntities(shooter, area,
                e -> e != shooter && e != shooterRoot && e.getRootVehicle() != shooterRoot
                        && e.isAlive()
                        && (e instanceof LivingEntity || e instanceof com.minethunder.plane.PlaneEntity))) {
            Vec3 targetPos = e.getBoundingBox().getCenter();
            Vec3 to = targetPos.subtract(eye);
            double dist = to.length();
            if (dist > maxRange || dist < 0.1) continue;

            double cos = to.normalize().dot(look);
            if (cos < cosLimit) continue;

            // 视线遮挡（地形/方块）则不可捕获
            if (RadarCore.isOccluded(level, eye, targetPos)) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }
}

