package com.minethunder.radar;

import net.minecraft.world.entity.Entity;

/**
 * 一个被雷达跟踪的目标航迹（TWS/STT 使用）。
 */
public final class RadarTrack {
    public final Entity entity;
    public double distance;
    public double azimuthDeg;
    public double elevationDeg;
    public double radialVelocity;
    public long lastUpdateTick;

    public RadarTrack(Entity entity, double distance, double azimuthDeg, double elevationDeg,
                      double radialVelocity, long tick) {
        this.entity = entity;
        this.distance = distance;
        this.azimuthDeg = azimuthDeg;
        this.elevationDeg = elevationDeg;
        this.radialVelocity = radialVelocity;
        this.lastUpdateTick = tick;
    }

    /**
     * 距离门关联：新回波是否落在该航迹的距离门内（抗箔条/抗干扰，SPEC §2.1 距离门）。
     */
    public boolean withinRangeGate(double newDistance, double gateWidth) {
        return Math.abs(newDistance - distance) <= gateWidth;
    }
}
