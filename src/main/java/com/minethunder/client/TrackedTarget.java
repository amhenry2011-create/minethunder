package com.minethunder.client;

import com.minethunder.radar.BvrTrack;
import com.minethunder.radar.RadarTargetClass;
import com.minethunder.radar.TargetSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 统一目标抽象：本地扫描回波与 BVR 数据链航迹共用同一套选择/锁定/跟踪/发射逻辑。
 * 本地目标多一个已加载实体；BVR 目标只有服务端快照，除“是否本地加载”外行为一致。
 */
public final class TrackedTarget {
    public final java.util.UUID uuid;
    public final TargetSource source;
    public final RadarTargetClass targetClass;
    public final int entityId;
    /** 最近一次扫描/涂抹的面板快照位置（本地回波或 BVR Paint）。 */
    public final Vec3 scanPosition;
    public final double scanRange;
    public final int lastPaintTick;
    @Nullable
    public final Entity entity;
    @Nullable
    public final RadarEcho echo;
    @Nullable
    public final BvrTrack bvr;
    @Nullable
    public final BvrState.Paint paint;

    private TrackedTarget(java.util.UUID uuid, TargetSource source,
                          RadarTargetClass targetClass, int entityId,
                          Vec3 scanPosition, double scanRange, int lastPaintTick,
                          @Nullable Entity entity, @Nullable RadarEcho echo,
                          @Nullable BvrTrack bvr, @Nullable BvrState.Paint paint) {
        this.uuid = uuid;
        this.source = source;
        this.targetClass = targetClass;
        this.entityId = entityId;
        this.scanPosition = scanPosition;
        this.scanRange = scanRange;
        this.lastPaintTick = lastPaintTick;
        this.entity = entity;
        this.echo = echo;
        this.bvr = bvr;
        this.paint = paint;
    }

    public static TrackedTarget local(RadarEcho echo, Entity entity) {
        return new TrackedTarget(echo.uuid, TargetSource.LOCAL, echo.targetClass,
                echo.entityId, echo.position, echo.range, echo.lastHitTick,
                entity, echo, null, null);
    }

    public static TrackedTarget bvr(BvrTrack track, @Nullable BvrState.Paint paint,
                                    RadarTargetClass targetClass) {
        Vec3 scanPos = paint == null ? BvrState.lagCompensatedPosition(track)
                : paint.position();
        double scanRange = paint == null
                ? scanPos.distanceTo(Minecraft.getInstance().player == null
                ? scanPos : Minecraft.getInstance().player.getEyePosition())
                : paint.range();
        int lastPaint = paint == null ? -1 : paint.lastPaintTick();
        return new TrackedTarget(track.uuid(), TargetSource.BVR, targetClass,
                track.entityId(), scanPos, scanRange, lastPaint,
                null, null, track, paint);
    }

    public boolean isLocal() {
        return source == TargetSource.LOCAL;
    }

    public boolean locallyLoaded() {
        return entity != null;
    }

    public boolean isOwnedBy(Player player) {
        if (player.getUUID().equals(uuid)) return true;
        return bvr != null && player.getUUID().equals(bvr.ownerUuid());
    }

    /** 实时位置：本地读实体，BVR 读带滞后的数据链预测。 */
    public Vec3 livePosition() {
        if (entity != null) return entity.getBoundingBox().getCenter();
        if (bvr != null) return BvrState.lagCompensatedPosition(bvr);
        return scanPosition;
    }

    public Vec3 velocity() {
        if (entity != null) return entity.getDeltaMovement();
        if (bvr != null) return bvr.velocity();
        if (echo != null) return echo.velocity;
        return Vec3.ZERO;
    }

    public double liveRange(Player player) {
        return player.getEyePosition().distanceTo(livePosition());
    }

    public double panelAzimuthDeg() {
        if (paint != null) return paint.azDeg();
        return echo == null ? 0.0 : echo.azimuthDeg;
    }

    public double panelRange() {
        return scanRange;
    }

    public String displayName() {
        if (entity != null) return entity.getName().getString();
        if (bvr != null && bvr.name() != null && !bvr.name().isEmpty()) {
            return bvr.name();
        }
        return "?";
    }

    public boolean alive(Player player) {
        if (isOwnedBy(player)) return false;
        if (entity != null) return entity.isAlive();
        return bvr != null;
    }

    /** 扫描记忆是否仍在允许的锁定建立窗口内。 */
    public boolean fresh(int tick, int maxAge) {
        return lastPaintTick >= 0 && tick - lastPaintTick <= maxAge;
    }
}
