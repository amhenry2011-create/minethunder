package com.minethunder.missile;

import com.minethunder.countermeasure.CountermeasureEntity;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarTargetClass;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** IR 热源搜索与发射校验。当前先覆盖实体/热焰弹，方块热源后续接入同一入口。 */
public final class IrTargeting {
    private IrTargeting() {}

    public static final double HEAT_THRESHOLD = IrHeat.THRESHOLD;
    /** HUD 外环相对 lockAngleMax 的视觉倍率；该环同时是实际离轴边界。 */
    public static final double OUTER_RING_SCALE = 6.0;

    public static double effectiveLockAngleDeg(MissileSpec spec) {
        if (spec == null || spec.guidance != MissileSpec.Guidance.IR) {
            return spec == null ? 0.0 : spec.lockAngleMaxDeg;
        }
        return Math.toDegrees(Math.atan(
                OUTER_RING_SCALE * Math.tan(Math.toRadians(spec.lockAngleMaxDeg))));
    }

    @Nullable
    public static Entity findTarget(Level level, Player shooter, @Nullable Entity radarLock,
                                    MissileSpec spec) {
        List<Entity> candidates = new ArrayList<>();
        AABB area = shooter.getBoundingBox().inflate(
                Math.max(spec.acquireRange(), spec.rearAspectLockRange));
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e != shooter && e.isAlive())) {
            candidates.add(living);
        }
        for (CountermeasureEntity flare : level.getEntitiesOfClass(CountermeasureEntity.class, area,
                CountermeasureEntity::isAlive)) {
            candidates.add(flare);
        }
        for (FireworkRocketEntity firework : level.getEntitiesOfClass(FireworkRocketEntity.class, area,
                FireworkRocketEntity::isAlive)) {
            candidates.add(firework);
        }
        // 太阳 Marker 在 64 b，大于普通 IR 全向锁定距离，单独查询。
        for (SunHeatMarker marker : level.getEntitiesOfClass(SunHeatMarker.class,
                shooter.getBoundingBox().inflate(256.0),
                m -> m.isAlive() && isSunMarker(m))) {
            candidates.add(marker);
        }

        Entity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 look = shooter.getViewVector(1.0F).normalize();
        for (Entity candidate : candidates) {
            if (!valid(level, shooter, candidate, spec)) continue;
            Vec3 to = candidate.getBoundingBox().getCenter().subtract(shooter.getEyePosition());
            double angle = to.lengthSqr() < 1.0E-6 ? 0.0
                    : Math.toDegrees(Math.acos(clamp(look.dot(to.normalize()), -1.0, 1.0)));
            double score = heat(candidate) - shooter.distanceTo(candidate) * 0.01
                    - angle * 4.0;
            if (isRearAspect(shooter, candidate)) score += 25.0;
            if (candidate == radarLock) score += 10_000.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static boolean isRearAspect(Player shooter, Entity target) {
        Vec3 to = target.getBoundingBox().getCenter().subtract(shooter.getEyePosition());
        if (to.lengthSqr() < 1.0E-6) return false;
        return target.getDeltaMovement().dot(to.normalize()) > 0.02;
    }

    public static boolean valid(Level level, Player shooter, Entity target, MissileSpec spec) {
        if (target == null || !target.isAlive() || target == shooter) return false;
        if (IrHeat.heat(target) < HEAT_THRESHOLD) return false;

        boolean sun = isSunMarker(target);
        Vec3 eye = shooter.getEyePosition();
        Vec3 pos = target.getBoundingBox().getCenter();
        Vec3 to = pos.subtract(eye);
        double distance = to.length();
        if (distance < 0.1 || (!sun && distance > maxRange(shooter, target, spec))) return false;

        Vec3 look = shooter.getViewVector(1.0F).normalize();
        double angle = Math.toDegrees(Math.acos(clamp(look.dot(to.normalize()), -1.0, 1.0)));
        if (angle > effectiveLockAngleDeg(spec)) return false;

        RadarTargetClass cls = RadarCore.classify(target);
        return sun || !RadarCore.isOccludedForTarget(level, eye, pos, cls);
    }

    public static double heat(Entity entity) {
        return IrHeat.heat(entity);
    }

    public static boolean isSunMarker(Entity entity) {
        return entity instanceof SunHeatMarker marker
                && marker.getCustomName() != null
                && marker.getCustomName().getString().startsWith("mt_heat_sun_");
    }

    private static double maxRange(Player shooter, Entity target, MissileSpec spec) {
        Vec3 to = target.getBoundingBox().getCenter().subtract(shooter.getEyePosition());
        if (to.lengthSqr() < 1.0E-6) return spec.allAspectLockRange;
        double closing = target.getDeltaMovement().dot(to.normalize());
        // 高温目标（着火/烟花/热焰弹）使用后半球距离，避免近距离全向距离过短。
        if (IrHeat.heat(target) >= IrHeat.THRESHOLD + 40.0) {
            return spec.rearAspectLockRange;
        }
        // 目标正在远离时使用后半球距离，其余使用全向距离。
        return closing > 0.02 ? spec.rearAspectLockRange : spec.allAspectLockRange;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
