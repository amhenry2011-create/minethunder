package com.minethunder.client;

import com.minethunder.radar.BvrKind;
import com.minethunder.radar.BvrTrack;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarTargetClass;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
    /** TWS 失去更新后的惯性保持时间：7 秒。 */
    private static final int TWS_TRACK_MEMORY_TICKS = 140;
    private static final Map<Integer, TwsTrack> TRACKS = new LinkedHashMap<>();
    private static final Map<UUID, TwsTrack> BVR_TRACKS = new LinkedHashMap<>();
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
            Entity entity = entity(track.entityId);
            track.displayName = entity == null ? track.displayName : entity.getName().getString();
            track.confirmed = track.hits >= 2;
            boolean notching = echo.inDopplerNotch && echo.notchSeverity > 0.4
                    && !RadarCore.isChaff(entity);
            track.notchTicks = notching ? track.notchTicks + 1 : 0;
        }

        updateBvrTracks(tick);

        TRACKS.entrySet().removeIf(e -> {
            TwsTrack track = e.getValue();
            Entity entity = entity(e.getKey());
            if (entity != null) {
                if (!entity.isAlive()) return true;
                if (RadarCore.classify(entity) != RadarTargetClass.AIR) return true;
            }
            return tick - track.lastPaintTick > TWS_TRACK_MEMORY_TICKS;
        });
        BVR_TRACKS.entrySet().removeIf(e ->
                tick - e.getValue().lastPaintTick > TWS_TRACK_MEMORY_TICKS);

        while (tracks().size() > MAX_TRACKS * 2) {
            TwsTrack farthest = null;
            for (TwsTrack track : tracks()) {
                if (track == current) continue;
                if (farthest == null || track.range > farthest.range) {
                    farthest = track;
                }
            }
            if (farthest == null) break;
            if (farthest.bvr) {
                BVR_TRACKS.remove(farthest.uuid);
            } else {
                TRACKS.remove(farthest.entityId);
            }
        }

        if (current == null || find(current.uuid) != current) {
            current = null;
            selectNearest();
        }
        if (current != null) {
            selectedId = current.entityId;
        } else {
            selectedId = -1;
        }
    }

    private static void updateBvrTracks(int tick) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        for (BvrTrack bvr : BvrState.active()) {
            RadarTargetClass cls = switch (bvr.kind()) {
                case PLAYER, AI, MISSILE -> RadarTargetClass.AIR;
                case SURFACE -> RadarTargetClass.SEA;
                case GROUND -> RadarTargetClass.GROUND;
            };
            if (cls != RadarTargetClass.AIR
                    || !RadarHud.currentMode.accepts(RadarTargetClass.AIR)) continue;
            if (entity(bvr.entityId()) != null) continue;
            BvrState.Paint paint = BvrState.painted(bvr.uuid());
            if (paint == null) continue;

            Vec3 pos = BvrState.lagCompensatedPosition(bvr);
            Vec3 to = pos.subtract(eye);
            double range = to.length();
            if (range <= 0.1 || range > RadarHud.effectiveRange()) continue;
            TwsTrack track = BVR_TRACKS.get(bvr.uuid());
            if (track == null) {
                track = new TwsTrack(bvr.uuid(), bvr.entityId(), pos, bvr.velocity(),
                        range, Math.toDegrees(RadarCore.azimuthBetween(look, to)),
                        Math.toDegrees(RadarCore.elevationBetween(look, to)),
                        closureOf(mc.player, bvr, to), paint.lastPaintTick());
                track.bvr = true;
                BVR_TRACKS.put(bvr.uuid(), track);
            } else {
                track.lastPosition = pos;
                track.velocity = bvr.velocity();
                track.range = range;
                track.azimuthDeg = Math.toDegrees(RadarCore.azimuthBetween(look, to));
                track.elevationDeg = Math.toDegrees(RadarCore.elevationBetween(look, to));
                track.closure = closureOf(mc.player, bvr, to);
                track.lastPaintTick = paint.lastPaintTick();
                track.lastUpdateTick = tick;
            }
            track.displayName = bvr.name();
            track.confirmed = true;
        }
    }

    private static double closureOf(net.minecraft.world.entity.player.Player player,
                                    BvrTrack track, Vec3 to) {
        if (to.lengthSqr() < 1.0E-6) return 0.0;
        Vec3 rel = track.velocity().scale(20.0)
                .subtract(player.getRootVehicle().getDeltaMovement().scale(20.0));
        return -rel.dot(to.normalize());
    }

    private static void selectNearest() {
        TwsTrack best = null;
        for (TwsTrack track : tracks()) {
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
        return BVR_TRACKS.get(uuid);
    }

    public static TwsTrack selected() {
        return current;
    }

    public static List<TwsTrack> tracks() {
        List<TwsTrack> result = new ArrayList<>(TRACKS.values());
        result.addAll(BVR_TRACKS.values());
        return result;
    }

    /** 在所有 TWS 航迹间循环唯一软锁目标。 */
    public static void cycle() {
        List<TwsTrack> valid = tracks();
        valid.sort(Comparator.comparingDouble(e -> e.range));
        if (valid.isEmpty()) {
            clear();
            return;
        }
        java.util.UUID selectedUuid = current == null ? null : current.uuid;
        int idx = -1;
        for (int i = 0; i < valid.size(); i++) {
            if (valid.get(i).uuid.equals(selectedUuid)) {
                idx = i;
                break;
            }
        }
        setCurrent(valid.get((idx + 1) % valid.size()));
    }

    public static void clear() {
        TRACKS.clear();
        BVR_TRACKS.clear();
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
