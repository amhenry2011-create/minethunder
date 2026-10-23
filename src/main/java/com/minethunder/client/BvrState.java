package com.minethunder.client;

import com.minethunder.radar.BvrKind;
import com.minethunder.radar.BvrTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 客户端 BVR 远程航迹缓存：来自 BvrSyncPacket，按最后更新时间过期。 */
public final class BvrState {
    private BvrState() {}

    /** 航迹在失去服务端更新后保留的时长（2 tick 快照 → 1.5 秒）。 */
    private static final int EXPIRE_TICKS = 30;

    private record Entry(BvrTrack track, int lastTick) {}

    private static final Map<UUID, Entry> TRACKS = new HashMap<>();

    /**
     * 波束涂抹记忆：只有扫描波束实际扫过的航迹才会建立/刷新快照，
     * 面板绘制使用快照中的冻结位置，与本地回波保持同一套规则。
     */
    public record Paint(Vec3 position, Vec3 velocity, int lastPaintTick) {}

    private static final Map<UUID, Paint> PAINTS = new HashMap<>();

    private static int nowTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0 : mc.player.tickCount;
    }

    /** 记录一次波束涂抹（位置/速度取涂抹时刻快照）。 */
    public static void paint(UUID uuid, Paint paint) {
        PAINTS.put(uuid, paint);
    }

    /** 涂抹快照；超过回波记忆未再次命中即过期（与本地回波 180 tick 一致）。 */
    @Nullable
    public static Paint painted(UUID uuid) {
        Paint paint = PAINTS.get(uuid);
        if (paint == null) return null;
        if (nowTick() - paint.lastPaintTick() > RadarHud.CONTACT_MEMORY_TICKS) {
            PAINTS.remove(uuid);
            return null;
        }
        return paint;
    }

    public static void clearPaints() {
        PAINTS.clear();
    }

    public static void setTracks(List<BvrTrack> tracks) {
        Minecraft mc = Minecraft.getInstance();
        int tick = mc.player == null ? 0 : mc.player.tickCount;
        for (BvrTrack track : tracks) {
            TRACKS.put(track.uuid(), new Entry(track, tick));
        }
    }

    /** 当前仍然有效的航迹。 */
    public static List<BvrTrack> active() {
        List<BvrTrack> list = new java.util.ArrayList<>(TRACKS.size());
        Minecraft mc = Minecraft.getInstance();
        int tick = mc.player == null ? 0 : mc.player.tickCount;
        TRACKS.values().removeIf(entry -> tick - entry.lastTick() > EXPIRE_TICKS);
        for (Entry entry : TRACKS.values()) {
            list.add(entry.track());
        }
        return list;
    }

    @Nullable
    public static BvrTrack get(UUID uuid) {
        Entry entry = TRACKS.get(uuid);
        if (entry == null) return null;
        Minecraft mc = Minecraft.getInstance();
        int tick = mc.player == null ? 0 : mc.player.tickCount;
        if (tick - entry.lastTick() > EXPIRE_TICKS) {
            TRACKS.remove(uuid);
            return null;
        }
        return entry.track();
    }

    /** 按外推秒数预测航迹位置。 */
    public static Vec3 predictedPosition(BvrTrack track) {
        Minecraft mc = Minecraft.getInstance();
        int tick = mc.player == null ? 0 : mc.player.tickCount;
        Entry entry = TRACKS.get(track.uuid());
        int last = entry == null ? tick : entry.lastTick();
        double seconds = Math.max(0, tick - last) / 20.0;
        return track.position().add(track.velocity().scale(seconds));
    }

    /** 服务端快照是 2 tick 前的，包含滞后补偿的预测位置。 */
    public static Vec3 lagCompensatedPosition(BvrTrack track) {
        return predictedPosition(track).add(track.velocity().scale(2 / 20.0));
    }

    public static boolean hasOwnMissile(UUID playerUuid) {
        for (Entry entry : TRACKS.values()) {
            if (entry.track().kind() == BvrKind.MISSILE
                    && playerUuid.equals(entry.track().ownerUuid())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static BvrTrack ownMissile(UUID playerUuid) {
        Entry best = null;
        double bestDist = -1;
        Minecraft mc = Minecraft.getInstance();
        for (Entry entry : TRACKS.values()) {
            BvrTrack track = entry.track();
            if (track.kind() != BvrKind.MISSILE
                    || !playerUuid.equals(track.ownerUuid())) continue;
            double distance = track.position().distanceToSqr(mc.player.getEyePosition());
            if (best == null || distance < bestDist) {
                best = entry;
                bestDist = distance;
            }
        }
        return best == null ? null : best.track();
    }

    public static void clear() {
        TRACKS.clear();
    }

    public static Collection<BvrTrack> all() {
        List<BvrTrack> list = new java.util.ArrayList<>(TRACKS.size());
        for (Entry entry : TRACKS.values()) list.add(entry.track());
        return list;
    }
}
