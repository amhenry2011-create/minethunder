package com.minethunder.radar;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 雷达检测结果：一次目标探测的描述。
 */
public final class RadarContact {
    public final Entity entity;
    /** 相对雷达的距离（米） */
    public final double distance;
    /** 相对雷达的方位角（度，水平） */
    public final double azimuthDeg;
    /** 相对雷达的仰角（度） */
    public final double elevationDeg;
    /** 目标径向速度（m/s，接近为负） */
    public final double radialVelocity;
    /** 探测时目标位置 */
    public final Vec3 position;
    /** 是否被地形遮挡（LOS 不通） */
    public final boolean occluded;
    /** 是否落入多普勒盲区（径向速度接近 0） */
    public final boolean inDopplerNotch;
    /** 0=无干扰，1=完全落在零多普勒门内；用于渐隐/质量降级 */
    public final double notchSeverity;
    /** 目标类别 */
    public final RadarTargetClass targetClass;

    public RadarContact(Entity entity, double distance, double azimuthDeg, double elevationDeg,
                        double radialVelocity, Vec3 position, boolean occluded, boolean inDopplerNotch) {
        this(entity, distance, azimuthDeg, elevationDeg, radialVelocity, position, occluded,
                inDopplerNotch, inDopplerNotch ? 1.0 : 0.0, RadarTargetClass.AIR);
    }

    public RadarContact(Entity entity, double distance, double azimuthDeg, double elevationDeg,
                        double radialVelocity, Vec3 position, boolean occluded,
                        boolean inDopplerNotch, double notchSeverity) {
        this(entity, distance, azimuthDeg, elevationDeg, radialVelocity, position, occluded,
                inDopplerNotch, notchSeverity, RadarTargetClass.AIR);
    }

    public RadarContact(Entity entity, double distance, double azimuthDeg, double elevationDeg,
                        double radialVelocity, Vec3 position, boolean occluded,
                        boolean inDopplerNotch, double notchSeverity, RadarTargetClass targetClass) {
        this.entity = entity;
        this.distance = distance;
        this.azimuthDeg = azimuthDeg;
        this.elevationDeg = elevationDeg;
        this.radialVelocity = radialVelocity;
        this.position = position;
        this.occluded = occluded;
        this.inDopplerNotch = inDopplerNotch;
        this.notchSeverity = notchSeverity;
        this.targetClass = targetClass;
    }
}
