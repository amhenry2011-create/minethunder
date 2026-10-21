package com.minethunder.client;

import com.minethunder.radar.RadarContact;
import com.minethunder.radar.RadarIconType;
import com.minethunder.radar.RadarTargetClass;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** 一次扫描命中的回波记忆；未再次命中时按速度惯性外推。 */
public final class RadarEcho {
    public final UUID uuid;
    public final int entityId;
    public Vec3 position;
    public Vec3 velocity;
    public double range;
    public double azimuthDeg;
    public double elevationDeg;
    public double closure;
    public int lastHitTick;
    public boolean occluded;
    public boolean inDopplerNotch;
    public double notchSeverity;
    public RadarTargetClass targetClass;
    public RadarIconType iconType;

    public RadarEcho(RadarContact contact, int tick) {
        this.uuid = contact.entity.getUUID();
        this.entityId = contact.entity.getId();
        update(contact, tick);
    }

    public void update(RadarContact contact, int tick) {
        this.position = contact.position;
        this.velocity = contact.entity.getDeltaMovement();
        this.range = contact.distance;
        this.azimuthDeg = contact.azimuthDeg;
        this.elevationDeg = contact.elevationDeg;
        this.closure = contact.radialVelocity;
        this.occluded = contact.occluded;
        this.inDopplerNotch = contact.inDopplerNotch;
        this.notchSeverity = contact.notchSeverity;
        this.targetClass = contact.targetClass;
        this.iconType = RadarIconType.from(contact.entity);
        this.lastHitTick = tick;
    }

    public Vec3 predictedPosition(int tick) {
        double seconds = Math.max(0, tick - lastHitTick) / 20.0;
        return position.add(velocity.scale(seconds));
    }

    public boolean expired(int tick) {
        return tick - lastHitTick > RadarHud.CONTACT_MEMORY_TICKS;
    }
}
