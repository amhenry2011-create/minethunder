package com.minethunder.radar;

import com.minethunder.missile.MissileArrow;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/**
 * 雷达核心：探测与锁定，包含多普勒盲区、地形遮挡等物理约束（SPEC §4）。
 * 当前所有可选模式均为 PD/面搜索；纯逻辑，无渲染，服务端与客户端共用。
 */
public final class RadarCore {
    private RadarCore() {}

    /** 多普勒盲区速度阈值（m/s）：目标相对载机的径向速度绝对值小于此值视为进入 Notch */
    public static final double DOPPLER_NOTCH_THRESHOLD = 5.0;
    /** 近距离烧穿距离（格）：0-50 b 内的 39 干扰显著降低 */
    public static final double NOTCH_BURN_THROUGH_RANGE = 50.0;
    /** GMI 最小可检测径向速度（m/s） */
    public static final double GMI_MIN_HORIZONTAL_SPEED = 0.5;
    private static final double AIR_CLEARANCE = 1.5;
    private static final int SUPPORT_SCAN_DEPTH = 8;

    /**
     * 在给定雷达位置与朝向，对周围实体执行一次探测。
     *
     * @param level       世界
     * @param origin      雷达位置（发射机）
     * @param lookDir     雷达朝向（单位向量）
     * @param mode        雷达模式
     * @param maxRange    探测距离上限
     * @param halfAzimuth 方位半张角（弧度）
     * @param halfElevation 仰角半张角（弧度）
     * @param radarVelocity 雷达载机速度；用于计算相对径向速度
     * @param exclude     排除的实体（发射方自身等）
     * @return 满足检测条件的目标列表（未过滤距离门/锁定，仅物理检测）
     */
    public static List<RadarContact> scan(Level level, Vec3 origin, Vec3 lookDir, RadarMode mode,
                                          double maxRange, double halfAzimuth, double halfElevation,
                                          Vec3 radarVelocity, Entity exclude) {
        List<RadarContact> contacts = new ArrayList<>();
        AABB area = new AABB(origin, origin).inflate(maxRange);
        List<Entity> entities = level.getEntities(exclude, area, Entity::isAlive);

        for (Entity e : entities) {
            Vec3 pos = e.getBoundingBox().getCenter();
            Vec3 to = pos.subtract(origin);
            double dist = to.length();
            if (dist > maxRange || dist < 0.1) continue;

            Vec3 dir = to.normalize();
            double az = azimuthBetween(lookDir, dir);
            double el = elevationBetween(lookDir, dir);

            // 波束张角内
            if (Math.abs(az) > halfAzimuth || Math.abs(el) > halfElevation) continue;

            // 静止悬空方块结构在 SRC PD/ACQ PD 可见；TWS/ACM 只收运动目标。
            if (e instanceof Marker marker
                    && RadarBlockTargets.isStatic(marker)
                    && mode != RadarMode.ACQ_PD
                    && mode != RadarMode.SRC_PD) {
                continue;
            }

            RadarTargetClass targetClass = classify(e);
            if (!mode.accepts(targetClass)) continue;

            // 视线遮挡检测（地形/方块）：遮挡目标不进入雷达面板；海面目标忽略水体衰减。
            if (isOccludedForTarget(level, origin, pos, targetClass)) {
                continue;
            }
            boolean occluded = false;

            // 相对径向速度：目标速度减去载机速度后投影到视线方向，统一使用 m/s。
            Vec3 relativeVelocity = e.getDeltaMovement().subtract(radarVelocity).scale(20.0);
            double radialVel = relativeVelocity.dot(dir);

            // GMI 是动目标指示：按目标实际水平移动速度判定，不要求径向速度。
            if (mode == RadarMode.GMI
                    && e.getDeltaMovement().horizontalDistance() * 20.0 < GMI_MIN_HORIZONTAL_SPEED) {
                continue;
            }

            double notchSeverity = notchSeverity(radialVel, dist, mode);
            boolean notch = notchSeverity > 0.4;

            contacts.add(new RadarContact(e, dist, Math.toDegrees(az), Math.toDegrees(el),
                    radialVel, pos, occluded, notch, notchSeverity, targetClass));
        }
        return contacts;
    }

    /** 计算 0..1 的 Notch 严重度；径向速度必须使用 m/s。 */
    public static double notchSeverity(double radialVelocityMps, double distance,
                                       RadarMode mode) {
        if (!mode.usesNotch()
                || Math.abs(radialVelocityMps) >= DOPPLER_NOTCH_THRESHOLD) {
            return 0.0;
        }
        double depth = 1.0 - Math.abs(radialVelocityMps) / DOPPLER_NOTCH_THRESHOLD;
        double close = Math.max(0.0, Math.min(1.0,
                1.0 - distance / NOTCH_BURN_THROUGH_RANGE));
        return depth * mode.notchSusceptibility() * (1.0 - 0.85 * close);
    }

    /** 目标是否贴近地面或海面（下方方块为地表/水） */
    public static boolean isNearSurface(Level level, Vec3 pos) {
        for (int dy = 0; dy <= 3; dy++) {
            var blockPos = new net.minecraft.core.BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y) - dy, (int) Math.floor(pos.z));
            BlockState state = level.getBlockState(blockPos);
            if (!state.isAir()) {
                return true;
            }
            FluidState fluid = level.getFluidState(blockPos);
            if (!fluid.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 目标分类：船、地面活体、空中活体；其他实体不进入雷达目标列表。 */
    public static RadarTargetClass classify(Entity entity) {
        if (entity instanceof Marker marker) {
            return RadarBlockTargets.targetClass(marker);
        }
        if (entity instanceof MissileArrow) {
            return RadarTargetClass.AIR;
        }
        if (entity instanceof Boat boat) {
            return boat.isInWater() ? RadarTargetClass.SEA : null;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return null;
        if (living.isPassenger() && living.getVehicle() instanceof Boat) return null;
        if (living.isInWater()) return null;

        if (living.onGround()) return RadarTargetClass.GROUND;

        Level level = living.level();
        double footY = living.getBoundingBox().minY;
        int startY = (int) Math.floor(footY - 0.01);
        for (int dy = 0; dy <= SUPPORT_SCAN_DEPTH; dy++) {
            BlockPos below = BlockPos.containing(living.getX(), startY - dy, living.getZ());
            BlockState support = level.getBlockState(below);
            var shape = support.getCollisionShape(level, below, CollisionContext.empty());
            FluidState fluid = level.getFluidState(below);
            boolean leaf = support.is(BlockTags.LEAVES);
            if (shape.isEmpty() && fluid.isEmpty() && !leaf) continue;

            double supportTop = below.getY();
            if (!shape.isEmpty()) {
                supportTop += shape.max(net.minecraft.core.Direction.Axis.Y);
            } else if (!fluid.isEmpty()) {
                supportTop += fluid.getHeight(level, below);
            } else {
                supportTop += 1.0;
            }
            return footY - supportTop > AIR_CLEARANCE
                    ? RadarTargetClass.AIR : RadarTargetClass.GROUND;
        }
        return RadarTargetClass.AIR;
    }

    /**
     * 视线遮挡/衰减检测：
     * - 山体、地面等实心碰撞方块：完全遮挡。
     * - 树叶：逐层衰减；单层树冠仍可能探测到，密集树林会被挡住。
     * - 水/岩浆：逐层强衰减；浅水可能透过，深水被挡住。
     * - 玻璃、冰：雷达可穿透。
     * - 草丛、花、作物等无碰撞植被：不遮挡。
     */
    public static boolean isOccludedForTarget(Level level, Vec3 origin, Vec3 target,
                                                 RadarTargetClass targetClass) {
        return isOccluded(level, origin, target, targetClass == RadarTargetClass.SEA);
    }

    public static boolean isOccluded(Level level, Vec3 origin, Vec3 target) {
        return isOccluded(level, origin, target, false);
    }

    public static boolean isOccluded(Level level, Vec3 origin, Vec3 target, boolean ignoreWater) {
        Vec3 delta = target.subtract(origin);
        double distance = delta.length();
        if (distance < 0.01) return false;
        Vec3 dir = delta.scale(1.0 / distance);

        int x = (int) Math.floor(origin.x);
        int y = (int) Math.floor(origin.y);
        int z = (int) Math.floor(origin.z);
        int stepX = dir.x > 0 ? 1 : dir.x < 0 ? -1 : 0;
        int stepY = dir.y > 0 ? 1 : dir.y < 0 ? -1 : 0;
        int stepZ = dir.z > 0 ? 1 : dir.z < 0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dir.x);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dir.y);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dir.z);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : (stepX > 0 ? (x + 1 - origin.x) : (origin.x - x)) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY
                : (stepY > 0 ? (y + 1 - origin.y) : (origin.y - y)) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : (stepZ > 0 ? (z + 1 - origin.z) : (origin.z - z)) * tDeltaZ;

        double signal = 1.0;
        double t = 0.0;
        for (int steps = 0; steps < 2048 && t <= distance; steps++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                if (state.is(BlockTags.LEAVES)) {
                    signal *= 0.78;
                } else if (!state.getFluidState().isEmpty()) {
                    boolean water = !state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
                    if (!(ignoreWater && water)) {
                        signal *= water ? 0.5 : 0.3;
                    }
                } else if (!isRadarTransparent(state)) {
                    if (!state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty()) {
                        return true;
                    }
                }
                if (signal < 0.2) return true;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return signal < 0.2;
    }

    private static boolean isRadarTransparent(BlockState state) {
        return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
                || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE) || state.is(Blocks.SNOW);
    }

    /** 方位角（水平）差值，返回范围 [-PI, PI] */
    public static double azimuthBetween(Vec3 lookDir, Vec3 toDir) {
        Vec3 l = new Vec3(lookDir.x, 0, lookDir.z).normalize();
        Vec3 t = new Vec3(toDir.x, 0, toDir.z).normalize();
        if (l.lengthSqr() < 1e-6 || t.lengthSqr() < 1e-6) return 0;
        double cross = l.x * t.z - l.z * t.x;
        double dot = l.x * t.x + l.z * t.z;
        return Math.atan2(cross, dot);
    }

    /** 仰角差值（弧度） */
    public static double elevationBetween(Vec3 lookDir, Vec3 toDir) {
        double lookEl = Math.asin(clamp(lookDir.normalize().y, -1, 1));
        double toEl = Math.asin(clamp(toDir.normalize().y, -1, 1));
        return toEl - lookEl;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
