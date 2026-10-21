package com.minethunder.client;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** 客户端 TWS 航迹。由扫描命中维护，未命中时按速度惯性外推。 */
public final class TwsTrack {
    public final UUID uuid;
    public final int entityId;
    public Vec3 lastPosition;
    public Vec3 velocity;
    public double range;
    public double azimuthDeg;
    public double elevationDeg;
    public double closure;
    public int lastPaintTick;
    public int lastUpdateTick;
    public int hits;
    public boolean confirmed;

    public TwsTrack(UUID uuid, int entityId, Vec3 position, Vec3 velocity,
                    double range, double azimuthDeg, double elevationDeg, double closure, int tick) {
        this.uuid = uuid;
        this.entityId = entityId;
        this.lastPosition = position;
        this.velocity = velocity;
        this.range = range;
        this.azimuthDeg = azimuthDeg;
        this.elevationDeg = elevationDeg;
        this.closure = closure;
        this.lastPaintTick = tick;
        this.lastUpdateTick = tick;
        this.hits = 1;
    }

    public Vec3 predictedPosition(int tick) {
        double dt = Math.max(0, tick - lastPaintTick) / 20.0;
        return lastPosition.add(velocity.scale(dt));
    }

    public boolean coasting(int tick) {
        return tick - lastPaintTick > 3;
    }
}
