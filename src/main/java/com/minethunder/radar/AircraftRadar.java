package com.minethunder.radar;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 机载雷达：持有当前模式、朝向与航迹，驱动探测与锁定。
 * 每个玩家（或每架"飞机"）持有一份实例。
 */
public final class AircraftRadar {
    private RadarMode mode = RadarMode.SRC_PD;
    private List<RadarTrack> tracks = new ArrayList<>();
    /** 当前硬锁定目标（STT） */
    private Entity lockedTarget = null;
    /** 硬锁定（STT）剩余时间，半主动弹照射中断则失锁 */
    private int sttHoldTicks = 0;

    /** 最大探测距离 */
    private double maxRange = 80000;
    /** 波束半张角（弧度） */
    private double halfAzimuth = Math.toRadians(60);
    private double halfElevation = Math.toRadians(15);

    public RadarMode getMode() {
        return mode;
    }

    public void setMode(RadarMode mode) {
        this.mode = mode;
        if (mode != RadarMode.STT) {
            lockedTarget = null;
            sttHoldTicks = 0;
        }
    }

    public Entity getLockedTarget() {
        return lockedTarget;
    }

    public boolean hasLock() {
        return lockedTarget != null;
    }

    /**
     * 每 tick 执行探测。返回本 tick 的新回波列表。
     */
    public List<RadarContact> tick(Entity radarEntity, Vec3 origin, Vec3 lookDir) {
        List<RadarContact> contacts = RadarCore.scan(
                radarEntity.level(), origin, lookDir, mode, maxRange, halfAzimuth, halfElevation,
                radarEntity.getDeltaMovement(), radarEntity);

        if (mode == RadarMode.STT && lockedTarget != null) {
            sttHoldTicks--;
            if (sttHoldTicks <= 0) {
                lockedTarget = null;
            }
        }
        return contacts;
    }

    /**
     * 尝试锁定一个回波。STT 需要目标可见、未被遮挡、且不在多普勒盲区（PD 模式）。
     * 这是"发射半主动/主动雷达弹"的前置条件。
     */
    public boolean tryLock(RadarContact contact) {
        if (contact.occluded) return false;
        // 39 只降低质量，不作为硬性禁止锁定条件。
        lockedTarget = contact.entity;
        sttHoldTicks = 40; // 锁定保持 2 秒，之后需重新确认
        mode = RadarMode.STT;
        return true;
    }

    /**
     * 判断当前能否发射雷达制导导弹（半主动/主动弹）。
     * 硬性规则（SPEC §4.1）：目标被遮挡或掉进多普勒盲区时禁止发射。
     */
    public boolean canLaunchRadarMissile() {
        return mode == RadarMode.STT && lockedTarget != null && lockedTarget.isAlive();
    }

    /** 更新航迹（TWS 关联距离门，简化实现） */
    public void updateTracks(List<RadarContact> contacts, long tick) {
        for (RadarContact c : contacts) {
            boolean matched = false;
            for (RadarTrack t : tracks) {
                if (t.entity == c.entity) {
                    t.distance = c.distance;
                    t.azimuthDeg = c.azimuthDeg;
                    t.elevationDeg = c.elevationDeg;
                    t.radialVelocity = c.radialVelocity;
                    t.lastUpdateTick = tick;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                tracks.add(new RadarTrack(c.entity, c.distance, c.azimuthDeg, c.elevationDeg,
                        c.radialVelocity, tick));
            }
        }
    }

    public List<RadarTrack> getTracks() {
        return tracks;
    }
}
