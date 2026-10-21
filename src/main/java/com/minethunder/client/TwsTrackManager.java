package com.minethunder.client;

import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarTargetClass;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 客户端 TWS：同时维护多条空中航迹，但只软锁一个当前目标。 */
public final class TwsTrackManager {
    private TwsTrackManager() {}

    private static final int MAX_TRACKS = 6;
    private static final Map<Integer, TwsTrack> TRACKS = new LinkedHashMap<>();
    /** 当前唯一软锁目标 */
    public static TwsTrack current;
    /** 当前目标实体 ID；没有目标时为 -1 */
    public static int selectedId = -1;

    public static void update(int tick) {
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(tick) || !isAir(echo.entityId)) continue;
            TwsTrack track = TRACKS.get(echo.entityId);
            if (track == null) {
                track = new TwsTrack(echo.uuid, echo.entityId, echo.position,
                        echo.velocity, echo.range, echo.azimuthDeg, echo.elevationDeg,
                        echo.closure, echo.lastHitTick);
                TRACKS.put(echo.entityId, track);
            } else {
                if (echo.lastHitTick > track.lastPaintTick) {
                    track.hits++;
                }
                track.lastPosition = echo.position;
                track.velocity = echo.velocity;
                track.range = echo.range;
                track.azimuthDeg = echo.azimuthDeg;
                track.elevationDeg = echo.elevationDeg;
                track.closure = echo.closure;
                track.lastPaintTick = echo.lastHitTick;
                track.lastUpdateTick = tick;
            }
            track.confirmed = track.hits >= 2;
        }

        TRACKS.entrySet().removeIf(e -> {
            Entity entity = entity(e.getKey());
            RadarEcho echo = RadarHud.echoes.get(e.getValue().uuid);
            return entity == null || !entity.isAlive()
                    || echo == null || echo.expired(tick)
                    || RadarCore.classify(entity) != RadarTargetClass.AIR;
        });
        while (TRACKS.size() > MAX_TRACKS) {
            TwsTrack farthest = null;
            for (TwsTrack track : TRACKS.values()) {
                if (track == current) continue;
                if (farthest == null || track.range > farthest.range) {
                    farthest = track;
                }
            }
            if (farthest == null) break;
            TRACKS.remove(farthest.entityId);
        }

        if (current == null || TRACKS.get(current.entityId) != current) {
            current = null;
            selectNearest();
        }
        if (current != null) {
            current.lastUpdateTick = tick;
            selectedId = current.entityId;
        } else {
            selectedId = -1;
        }
    }

    private static void selectNearest() {
        TwsTrack best = null;
        for (TwsTrack track : TRACKS.values()) {
            if (best == null || track.range < best.range) best = track;
        }
        if (best != null) setCurrent(best);
    }

    private static void setCurrent(TwsTrack track) {
        current = track;
        selectedId = track.entityId;
    }

    public static TwsTrack find(UUID uuid) {
        for (TwsTrack track : TRACKS.values()) {
            if (track.uuid.equals(uuid)) return track;
        }
        return null;
    }

    public static TwsTrack selected() {
        return current;
    }

    public static List<TwsTrack> tracks() {
        return new ArrayList<>(TRACKS.values());
    }

    /** 在所有 TWS 航迹间循环唯一软锁目标。 */
    public static void cycle() {
        List<TwsTrack> valid = tracks();
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
        TRACKS.clear();
        current = null;
        selectedId = -1;
    }

    public static Entity entity(int entityId) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getEntity(entityId);
    }

    private static boolean isAir(int entityId) {
        Entity entity = entity(entityId);
        return entity != null && entity.isAlive()
                && RadarCore.classify(entity) == RadarTargetClass.AIR;
    }
}
