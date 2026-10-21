package com.minethunder.client;

import com.minethunder.radar.RadarTargetClass;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 客户端 TWS：保留多个回波，但同一时间只有一个当前跟踪目标。 */
public final class TwsTrackManager {
    private TwsTrackManager() {}

    /** 当前唯一被跟踪的目标 */
    public static TwsTrack current;
    /** 当前目标实体 ID；没有目标时为 -1 */
    public static int selectedId = -1;

    public static void update(int tick) {
        if (current != null) {
            RadarEcho echo = RadarHud.echoes.get(current.uuid);
            if (echo == null || echo.expired(tick)
                    || echo.targetClass != RadarTargetClass.AIR
                    || !isAlive(current.entityId)) {
                clear();
            } else {
                current.lastPosition = echo.position;
                current.velocity = echo.velocity;
                current.range = echo.range;
                current.azimuthDeg = echo.azimuthDeg;
                current.elevationDeg = echo.elevationDeg;
                current.closure = echo.closure;
                current.lastPaintTick = echo.lastHitTick;
                current.lastUpdateTick = tick;
                current.confirmed = true;
                selectedId = current.entityId;
            }
        }
        if (current == null) {
            selectNearest();
        }
    }

    private static void selectNearest() {
        RadarEcho best = null;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(currentTick())
                    || echo.targetClass != RadarTargetClass.AIR) continue;
            if (best == null || echo.range < best.range) best = echo;
        }
        if (best != null) setCurrent(best);
    }

    private static void setCurrent(RadarEcho echo) {
        current = new TwsTrack(echo.uuid, echo.entityId, echo.position, echo.velocity,
                echo.range, echo.azimuthDeg, echo.elevationDeg, echo.closure, echo.lastHitTick);
        current.confirmed = true;
        selectedId = current.entityId;
    }

    private static int currentTick() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player == null ? 0 : mc.player.tickCount;
    }

    public static TwsTrack find(UUID uuid) {
        return current != null && current.uuid.equals(uuid) ? current : null;
    }

    public static TwsTrack selected() {
        return current;
    }

    /** 在当前已扫描回波中循环切换唯一跟踪目标。 */
    public static void cycle() {
        List<RadarEcho> valid = new ArrayList<>();
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (!echo.expired(currentTick())
                    && echo.targetClass == RadarTargetClass.AIR) valid.add(echo);
        }
        valid.sort(Comparator.comparingDouble(e -> e.range));
        if (valid.isEmpty()) {
            clear();
            return;
        }
        int idx = -1;
        for (int i = 0; i < valid.size(); i++) {
            if (valid.get(i).entityId == selectedId) {
                idx = i;
                break;
            }
        }
        setCurrent(valid.get((idx + 1) % valid.size()));
    }

    public static void clear() {
        current = null;
        selectedId = -1;
    }

    public static Entity entity(int entityId) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getEntity(entityId);
    }

    private static boolean isAlive(int entityId) {
        Entity entity = entity(entityId);
        return entity != null && entity.isAlive();
    }
}
