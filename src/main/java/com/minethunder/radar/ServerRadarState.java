package com.minethunder.radar;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端保存客户端同步来的雷达锁定与导引头状态，供发射校验使用。
 */
public final class ServerRadarState {
    private ServerRadarState() {}

    private static final long EXPIRE_TICKS = 100;

    public record LockState(int targetId, java.util.UUID targetUuid,
                            ResourceKey<Level> dimension, int seekerState,
                            int radarMode, boolean hardLock, boolean radarOn,
                            long updatedAt, float trackQuality) {}

    private static final Map<UUID, LockState> LOCKS = new ConcurrentHashMap<>();

    public static void update(ServerPlayer player, int targetId, int seekerState,
                              int radarMode, boolean hardLock, boolean radarOn,
                              float trackQuality) {
        if (!radarOn) {
            LOCKS.remove(player.getUUID());
            return;
        }
        int safeTarget = Math.max(-1, targetId);
        int safeSeeker = Math.max(-1, Math.min(3, seekerState));
        int safeMode = Math.max(0,
                Math.min(RadarMode.values().length - 1, radarMode));
        float safeQuality = Float.isFinite(trackQuality)
                ? Math.max(0.0f, Math.min(1.0f, trackQuality)) : 0.0f;
        Entity target = player.serverLevel().getEntity(safeTarget);
        java.util.UUID targetUuid = target == null ? null : target.getUUID();
        ResourceKey<Level> dimension = target == null ? null : player.serverLevel().dimension();
        LOCKS.put(player.getUUID(), new LockState(safeTarget, targetUuid, dimension,
                safeSeeker, safeMode, hardLock, true,
                player.serverLevel().getGameTime(), safeQuality));
    }

    public static LockState get(Player player) {
        LockState state = LOCKS.get(player.getUUID());
        if (state == null) return null;
        if (player.level().getGameTime() - state.updatedAt() > EXPIRE_TICKS) {
            LOCKS.remove(player.getUUID());
            return null;
        }
        return state;
    }

    public static Entity getTarget(Player player) {
        LockState state = get(player);
        if (state == null || !state.radarOn() || state.targetId() < 0) return null;
        if (!(player.level() instanceof ServerLevel serverLevel)
                || state.dimension() == null
                || !state.dimension().equals(serverLevel.dimension())) {
            return null;
        }
        return state.targetUuid() == null ? null : serverLevel.getEntity(state.targetUuid());
    }

    public static boolean isRadarOn(Player player) {
        LockState state = get(player);
        return state != null && state.radarOn();
    }

    /** 硬锁定或 TWS 软跟踪目标，用于中段数据链。 */
    public static boolean hasTrack(Player player, int targetId) {
        LockState state = get(player);
        return state != null && state.radarOn() && state.targetId() == targetId
                && state.dimension() != null
                && state.dimension().equals(player.level().dimension())
                && (state.hardLock() || state.radarMode() == RadarMode.TWS.ordinal());
    }

    public static boolean isLocked(Player player, int targetId) {
        LockState state = get(player);
        return state != null && state.radarOn() && state.hardLock()
                && state.targetId() == targetId
                && state.dimension() != null
                && state.dimension().equals(player.level().dimension());
    }

    /** 主动雷达弹：TWS 软跟踪目标或硬锁定目标即可发射；导引头对准只作视觉。 */
    public static boolean canLaunchArh(Player player) {
        LockState state = get(player);
        if (state == null || !state.radarOn() || state.targetId() < 0) return false;
        if (state.dimension() == null
                || !state.dimension().equals(player.level().dimension())) return false;
        return state.radarMode() == RadarMode.TWS.ordinal() || state.hardLock();
    }

    /** 半主动雷达弹：必须硬锁定，并到红色倒计时环（TRACKING）或红色双环（HIGH）。 */
    public static boolean canLaunchSarh(Player player) {
        LockState state = get(player);
        return state != null
                && state.radarOn()
                && state.hardLock()
                && state.dimension() != null
                && state.dimension().equals(player.level().dimension())
                && state.seekerState() >= SeekerLockModel.State.TRACKING.ordinal();
    }

    public static void clear(Player player) {
        LOCKS.remove(player.getUUID());
    }
}
