package com.minethunder.missile;

import com.minethunder.radar.RadarCore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 主动雷达弹（类似 AIM-120）：中段追发射时目标，到达 pitbull 后由弹载雷达
 * 独立搜索/重捕获目标，不再依赖载机锁定。
 */
public class ArhMissileArrow extends MissileArrow {
    private int searchCooldown = 0;
    /** 简化双模式：HPRF 截获 -> MPRF 末段跟踪。 */
    private boolean mprfActive = false;

    public ArhMissileArrow(EntityType<? extends MissileArrow> type, Level level, MissileSpec spec) {
        super(type, level, spec);
    }

    @Override
    protected Entity resolveTarget() {
        Entity current = getTarget();
        if (level().isClientSide || !pitbullActive) {
            return current;
        }
        updateSeekerMode(current);

        // 原目标仍在导引头视场内：继续保持，不额外搜索。
        if (inSeekerCone(current)) {
            return current;
        }

        if (searchCooldown > 0) {
            searchCooldown--;
            return current;
        }
        searchCooldown = 10;

        Entity acquired = findRadarTarget();
        if (acquired != null) {
            setTarget(acquired);
            return acquired;
        }
        return current;
    }

    private boolean inSeekerCone(@Nullable Entity target) {
        if (target == null || !target.isAlive()) return false;
        Vec3 pos = position();
        Vec3 to = target.getBoundingBox().getCenter().subtract(pos);
        double distance = to.length();
        if (distance < 0.5 || distance > effectiveSeekerRange()) return false;

        Vec3 forward = getDeltaMovement();
        if (forward.lengthSqr() < 1.0E-6) {
            forward = getViewVector(1.0F);
        }
        if (forward.lengthSqr() < 1.0E-6) return false;
        forward = forward.normalize();

        double angle = Math.toDegrees(Math.acos(clamp(forward.dot(to.normalize()), -1.0, 1.0)));
        return angle <= spec.seekerFovDeg;
    }

    @Nullable
    private Entity findRadarTarget() {
        Vec3 pos = position();
        Vec3 forward = getDeltaMovement();
        if (forward.lengthSqr() < 1.0E-6) {
            forward = getViewVector(1.0F);
        }
        if (forward.lengthSqr() < 1.0E-6) return null;
        forward = forward.normalize();

        double range = effectiveSeekerRange();
        AABB area = getBoundingBox().inflate(range);
        Entity owner = getOwner();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity candidate : level().getEntities(this, area,
                e -> e.isAlive() && e != owner && RadarCore.classify(e) != null
                        && !isSameOwner(e, owner))) {
            Vec3 to = candidate.getBoundingBox().getCenter().subtract(pos);
            double distance = to.length();
            if (distance < 0.5 || distance > range) continue;

            double angle = Math.toDegrees(Math.acos(
                    clamp(forward.dot(to.normalize()), -1.0, 1.0)));
            if (angle > spec.seekerFovDeg) continue;
            Vec3 center = candidate.getBoundingBox().getCenter();
            if (RadarCore.isOccluded(level(), pos, center)) continue;

            // HPRF 下视时，贴近地面的目标容易被地杂波淹没；MPRF 才具备稳定下视能力。
            if (!mprfActive && center.y < pos.y - 12.0
                    && RadarCore.isNearSurface(level(), center)) continue;

            // HPRF 速度门：极低径向速度目标在远距先视作杂波。
            if (!mprfActive && distance > 50.0) {
                Vec3 rel = candidate.getDeltaMovement().subtract(getDeltaMovement()).scale(20.0);
                double closure = -rel.dot(to.normalize());
                if (Math.abs(closure) < 8.0) continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private double effectiveSeekerRange() {
        return mprfActive ? Math.min(80.0, spec.seekerActivationRange)
                : Math.max(64.0, spec.seekerActivationRange);
    }

    private void updateSeekerMode(@Nullable Entity current) {
        if (mprfActive) return;
        Vec3 pos = position();
        double distance = Double.MAX_VALUE;
        if (current != null && current.isAlive()) {
            distance = pos.distanceTo(current.getBoundingBox().getCenter());
        } else if (measuredTargetPos != null) {
            distance = pos.distanceTo(measuredTargetPos);
        }
        if (distance <= 80.0) mprfActive = true;
    }

    private static boolean isSameOwner(Entity candidate, Entity owner) {
        if (owner == null) return false;
        Entity candidateOwner = candidate instanceof MissileArrow missile
                ? missile.getOwner() : null;
        return candidateOwner != null && owner.getUUID().equals(candidateOwner.getUUID());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
