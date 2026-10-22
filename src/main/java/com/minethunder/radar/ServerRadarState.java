package com.minethunder.radar;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端保存客户端同步来的雷达锁定与导引头状态，供发射校验使用。
 */
public final class ServerRadarState {
    private ServerRadarState() {}

    private static final long EXPIRE_TICKS = 100;

    /** BVR 远程锁定最大距离（格）。 */
    public static final double BVR_LOCK_RANGE = 1000.0;

    /**
     * 设置/解除 BVR 远程目标。来自 BvrLockPacket；实体必须已加载且为 AIR 类。
     */
    public static void setBvrTarget(ServerPlayer player, @Nullable UUID targetUuid) {
        LockState state = LOCKS.get(player.getUUID());
        if (state == null) return;
        if (!state.radarOn()) return;
        long now = player.serverLevel().getGameTime();
        if (now - state.updatedAt() > EXPIRE_TICKS) {
            LOCKS.remove(player.getUUID());
            return;
        }
        if (state.targetId() >= 0 && targetUuid != null) {
            // 已有本地硬锁/TWS 目标时忽略 BVR 锁定。
            return;
        }
        LOCKS.put(player.getUUID(), new LockState(state.targetId(), state.targetUuid(),
                state.dimension(), state.seekerState(), state.radarMode(),
                state.hardLock(), state.radarOn(), now, state.trackQuality(),
                validateBvrTarget(player, targetUuid, state.dimension())
                        ? targetUuid : null));
    }

    @Nullable
    public static UUID getBvrTargetUuid(Player player) {
        LockState state = get(player);
        return state == null ? null : state.bvrTargetUuid();
    }

    @Nullable
    public static Entity getBvrTarget(Player player) {
        UUID uuid = getBvrTargetUuid(player);
        if (uuid == null) return null;
        if (!(player.level() instanceof ServerLevel serverLevel)) return null;
        Entity entity = serverLevel.getEntity(uuid);
        if (entity == null || !entity.isAlive()) return null;
        return entity;
    }

    public record LockState(int targetId, java.util.UUID targetUuid,
                            ResourceKey<Level> dimension, int seekerState,
                            int radarMode, boolean hardLock, boolean radarOn,
                            long updatedAt, float trackQuality,
                            java.util.UUID bvrTargetUuid) {}

    private static final Map<UUID, LockState> LOCKS = new ConcurrentHashMap<>();

    public static void update(ServerPlayer player, int targetId, int seekerState,
                              int radarMode, boolean hardLock, boolean radarOn,
                              float trackQuality) {
        update(player, targetId, seekerState, radarMode, hardLock, radarOn,
                trackQuality, null);
    }

    public static void update(ServerPlayer player, int targetId, int seekerState,
                              int radarMode, boolean hardLock, boolean radarOn,
                              float trackQuality, @Nullable UUID bvrTargetUuid) {
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
        ResourceKey<Level> dimension = player.serverLevel().dimension();
        if (!validateBvrTarget(player, bvrTargetUuid, dimension)) {
            bvrTargetUuid = null;
        }
        LOCKS.put(player.getUUID(), new LockState(safeTarget, targetUuid, dimension,
                safeSeeker, safeMode, hardLock, true,
                player.serverLevel().getGameTime(), safeQuality, bvrTargetUuid));
    }

    /**
     * 从 {@link RadarSyncPacket} 快照解析本地雷达状态；保持已有 BVR 锁定引用。
     */
    public static void refreshLockState(ServerPlayer player, int targetId, int seekerState,
                                        int radarMode, boolean hardLock, boolean radarOn,
                                        float trackQuality) {
        UUID keepBvr = null;
        LockState old = LOCKS.get(player.getUUID());
        if (old != null && player.serverLevel().getGameTime() - old.updatedAt() <= EXPIRE_TICKS) {
            keepBvr = old.bvrTargetUuid();
        }
        update(player, targetId, seekerState, radarMode, hardLock, radarOn,
                trackQuality, keepBvr);
    }

    /** BVR 远程锁定：仅 AIR 类别，同一维度，全员可锁定距离内。 */
    private static boolean validateBvrTarget(ServerPlayer player, @Nullable UUID bvrTargetUuid,
                                             ResourceKey<Level> dimension) {
        if (bvrTargetUuid == null) return false;
        if (player.getUUID().equals(bvrTargetUuid)) return false;
        Entity entity = player.serverLevel().getEntity(bvrTargetUuid);
        if (entity == null || !entity.isAlive()) return false;
        if (!dimension.equals(player.serverLevel().dimension())) return false;
        if (RadarCore.classify(entity) != RadarTargetClass.AIR) return false;
        double distance = player.distanceTo(entity);
        return distance > 16.0 && distance <= BVR_LOCK_RANGE;
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

    /** 硬锁定或 TWS 软跟踪目标，用于中段数据链。BVR 远程软锁也允许数据链。 */
    public static boolean hasTrack(Player player, int targetId) {
        LockState state = get(player);
        if (state == null || !state.radarOn()) return false;
        if (state.dimension() == null
                || !state.dimension().equals(player.level().dimension())) return false;
        if (state.targetId() == targetId
                && (state.hardLock() || state.radarMode() == RadarMode.TWS.ordinal())) {
            return true;
        }
        if (state.bvrTargetUuid() != null && state.targetId() < 0) return true;
        return false;
    }

    public static boolean isLocked(Player player, int targetId) {
        LockState state = get(player);
        return state != null && state.radarOn() && state.hardLock()
                && state.targetId() == targetId
                && state.dimension() != null
                && state.dimension().equals(player.level().dimension());
    }

    /** 主动雷达弹：TWS 软跟踪目标或硬锁定目标即可发射；BVR 远程锁定也可发射 ARH。导引头对准只作视觉。 */
    public static boolean canLaunchArh(Player player) {
        LockState state = get(player);
        if (state == null || !state.radarOn()) return false;
        if (state.dimension() == null
                || !state.dimension().equals(player.level().dimension())) return false;
        if (state.targetId() < 0) return state.bvrTargetUuid() != null;
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
