package com.minethunder.client;

import com.minethunder.radar.BvrKind;
import com.minethunder.radar.BvrTrack;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarTargetClass;
import com.minethunder.radar.ServerRadarState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一目标注册表：每 tick 从本地回波与 BVR 航迹合成同一份候选列表，
 * 选择/锁定/TRK/发射全部只认这里的目标，不再区分本地/BVR 两套流程。
 */
public final class TargetRegistry {
    private TargetRegistry() {}

    /** 普通选择记忆窗口：与本地回波/BVR 涂抹记忆一致（9 秒）。 */
    private static final int SELECTION_MEMORY_TICKS = RadarHud.CONTACT_MEMORY_TICKS;
    /** 锁定建立窗口：必须本扫描周期内被波束命中，与旧手动锁定规则一致。 */
    public static int lockFreshnessTicks() {
        return RadarHud.scanFrameTicks() + 20;
    }

    public static List<TrackedTarget> all(Player player, int tick) {
        Map<UUID, TrackedTarget> merged = new LinkedHashMap<>();

        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(tick) || echo.targetClass == null) continue;
            if (!RadarHud.currentMode.accepts(echo.targetClass)) continue;
            if (RadarHud.currentMode == com.minethunder.radar.RadarMode.TWS
                    && echo.targetClass != RadarTargetClass.AIR) continue;
            Entity entity = entity(echo.entityId);
            if (entity == null || !entity.isAlive()) continue;
            RadarTargetClass liveClass = RadarCore.classify(entity);
            if (liveClass == null || !RadarHud.currentMode.accepts(liveClass)) continue;
            merged.put(echo.uuid, TrackedTarget.local(echo, entity));
        }

        for (BvrTrack track : BvrState.active()) {
            if (player.getUUID().equals(track.uuid())
                    || player.getRootVehicle().getUUID().equals(track.uuid())) continue;
            if (track.kind() == BvrKind.MISSILE
                    && player.getUUID().equals(track.ownerUuid())) continue;
            RadarTargetClass targetClass = classOf(track);
            if (!RadarHud.currentMode.accepts(targetClass)) continue;
            if (RadarHud.currentMode == com.minethunder.radar.RadarMode.TWS
                    && targetClass != RadarTargetClass.AIR) continue;
            BvrState.Paint paint = BvrState.painted(track.uuid());
            if (paint == null && !RadarHud.hasActiveLockTarget()) continue;
            if (merged.containsKey(track.uuid())) continue;
            merged.put(track.uuid(), TrackedTarget.bvr(track, paint, targetClass));
        }

        List<TrackedTarget> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingDouble(TrackedTarget::panelRange));
        return list;
    }

    @Nullable
    public static TrackedTarget find(@Nullable UUID uuid, Player player, int tick) {
        if (uuid == null) return null;
        RadarEcho echo = RadarHud.echoes.get(uuid);
        if (echo != null && !echo.expired(tick) && echo.targetClass != null) {
            Entity entity = entity(echo.entityId);
            if (entity != null && entity.isAlive()) {
                RadarTargetClass liveClass = RadarCore.classify(entity);
                if (liveClass != null) {
                    return TrackedTarget.local(echo, entity);
                }
            }
        }
        BvrTrack track = BvrState.get(uuid);
        if (track != null) {
            return TrackedTarget.bvr(track, BvrState.painted(uuid), classOf(track));
        }
        return null;
    }

    @Nullable
    public static TrackedTarget selected(Player player, int tick) {
        if (RadarHud.selectedTargetUuid != null) {
            TrackedTarget target = find(RadarHud.selectedTargetUuid, player, tick);
            if (target != null) return target;
        }
        if (RadarHud.selectedEchoId >= 0) {
            for (RadarEcho echo : RadarHud.echoes.values()) {
                if (echo.entityId == RadarHud.selectedEchoId
                        && !echo.expired(tick)) {
                    Entity entity = entity(echo.entityId);
                    if (entity != null && entity.isAlive()) {
                        return TrackedTarget.local(echo, entity);
                    }
                }
            }
        }
        return null;
    }

    public static void setSelected(@Nullable TrackedTarget target) {
        RadarHud.selectedTargetUuid = target == null ? null : target.uuid;
        RadarHud.selectedEchoId = target == null ? -1 : target.entityId;
        RadarHud.selectionInitialized = target != null;
    }

    public static void clearSelection() {
        RadarHud.selectedTargetUuid = null;
        RadarHud.selectedEchoId = -1;
        RadarHud.selectionInitialized = false;
    }

    /** Alt+T：普通模式下循环当前所有统一目标。 */
    public static void selectNext(Player player, int tick) {
        List<TrackedTarget> valid = all(player, tick);
        if (valid.isEmpty()) {
            clearSelection();
            RadarHudClientTicker.lockMessage("无有效回波");
            return;
        }
        int index = -1;
        for (int i = 0; i < valid.size(); i++) {
            if (valid.get(i).uuid.equals(RadarHud.selectedTargetUuid)) {
                index = i;
                break;
            }
        }
        setSelected(valid.get((index + 1) % valid.size()));
        RadarHudClientTicker.lockMessage("切换面板目标");
    }

    /**
     * 面板选中维护：选择只由 Alt+T/首次回波维护，不因视角移动自动换目标。
     * 锁定/ACQ 期间不改变选中。
     */
    public static void validateSelection(Player player, int tick) {
        if (RadarHud.hasActiveLockTarget()) {
            RadarHud.selectionInitialized = true;
            return;
        }
        if (RadarHud.selectedTargetUuid == null && RadarHud.selectedEchoId >= 0) {
            TrackedTarget legacy = selected(player, tick);
            if (legacy != null) {
                RadarHud.selectedTargetUuid = legacy.uuid;
            }
        }
        if (RadarHud.selectedTargetUuid != null) {
            TrackedTarget current = find(RadarHud.selectedTargetUuid, player, tick);
            if (current != null) {
                RadarHud.selectedEchoId = current.entityId;
                return;
            }
            clearSelection();
        }
        if (!RadarHud.selectionInitialized) {
            List<TrackedTarget> valid = all(player, tick);
            if (!valid.isEmpty()) {
                setSelected(valid.get(0));
            }
        }
    }

    /**
     * Alt+F：优先锁当前选中目标；没有选中时选方位偏差最小、距离最近的候选。
     * 只接受本扫描周期内命中/涂抹过的目标，与本地/BVR 旧锁定建立规则一致。
     */
    @Nullable
    public static TrackedTarget bestLockCandidate(Player player, int tick) {
        int maxAge = lockFreshnessTicks();
        TrackedTarget selected = selected(player, tick);
        if (selected != null && eligibleForLock(selected, player, tick, maxAge)) {
            return selected;
        }
        TrackedTarget best = null;
        double bestScore = Double.MAX_VALUE;
        for (TrackedTarget target : all(player, tick)) {
            if (!eligibleForLock(target, player, tick, maxAge)) continue;
            double distance = target.liveRange(player);
            double az = Math.toDegrees(RadarCore.azimuthBetween(
                    player.getViewVector(1.0F).normalize(),
                    target.livePosition().subtract(player.getEyePosition())));
            double azScore = Math.abs(az - RadarHud.scanAzimuthOffsetDeg);
            double score = (azScore <= 60.0 ? azScore : 1000.0 + azScore)
                    + distance * 0.001;
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }
        return best;
    }

    public static boolean eligibleForLock(TrackedTarget target, Player player,
                                          int tick, int maxAge) {
        return eligibleForLock(target, player, tick, maxAge, true);
    }

    /**
     * BVR 数据链在 ACQ/TRK 期间持续刷新，不要求客户端扫描涂抹仍在记忆窗口内；
     * 本地目标仍必须本扫描周期内被波束命中。
     */
    public static boolean eligibleForLock(TrackedTarget target, Player player,
                                          int tick, int maxAge, boolean requireFresh) {
        if (!target.alive(player)) return false;
        if (requireFresh && !target.fresh(tick, maxAge)) return false;
        RadarTargetClass targetClass = target.targetClass;
        if (targetClass == null || !RadarHud.currentMode.accepts(targetClass)) return false;
        double distance = target.liveRange(player);
        if (distance <= 0.1 || distance > RadarHud.effectiveRange()) return false;
        if (target.source == com.minethunder.radar.TargetSource.BVR
                && (distance < ServerRadarState.BVR_LOCK_MIN_RANGE
                || distance > ServerRadarState.BVR_LOCK_RANGE)) {
            return false;
        }
        return true;
    }

    @Nullable
    private static Entity entity(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getEntity(entityId);
    }

    private static RadarTargetClass classOf(BvrTrack track) {
        return switch (track.kind()) {
            case PLAYER, AI, MISSILE -> RadarTargetClass.AIR;
            case SURFACE -> RadarTargetClass.SEA;
        };
    }
}
