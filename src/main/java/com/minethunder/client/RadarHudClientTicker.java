package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.IrTargeting;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.network.Network;
import com.minethunder.network.RadarSyncPacket;
import com.minethunder.network.RwrPaintPacket;
import com.minethunder.radar.BvrTrack;
import com.minethunder.radar.RadarContact;
import com.minethunder.radar.RadarMode;
import com.minethunder.radar.RadarTargetClass;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.ServerRadarState;
import com.minethunder.radar.TargetSource;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端雷达/导引头 tick：
 * - 手持弓 → 雷达面板开启，扫描目标。
 * - 拉满弓 + 红外弹 → 导引头开机，锁定视线内热源。
 * - 雷达弹锁定（X）→ 显示锁定环。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class RadarHudClientTicker {
    private RadarHudClientTicker() {}

    /** 硬锁定/TRK PD 在无扫描更新时的保持帧数（9 秒） */
    private static final int LOCK_HOLD_TICKS = 180;
    /** TRK PD 连续跟踪失效后的拖锁宽限（1 秒） */
    private static final int TRK_LOST_GRACE_TICKS = 20;
    /** 满弓所需 ticks */
    private static final int FULL_DRAW_TICKS = 20;

    private static int lockTicks = 0;
    /** 连续多少 tick 没扫到锁定目标后进入导引头惯性导航 */
    private static final int INERTIAL_TICKS = 20;
    private static int inertialTicks = 0;
    /** 上一 tick 是否有己方导弹在飞；用于导弹结束瞬间清除惯性小圈 */
    private static boolean missileWasInFlight = false;
    /** 导弹结束后抑制“锁定保持惯性圈”，直到重新捕获目标或再次发射 */
    private static boolean suppressLockInertial = false;
    /** 当前驱动导引头环的目标 id，用于目标切换时重新从中心抛出 */
    private static int lastSeekerLockId = -1;
    /** 已同步到服务端的锁定目标/导引头状态 */
    private static int lastSentTargetId = -2;
    private static int lastSentSeekerState = -1;
    private static int lastSentTick = -100;
    private static int lastSentMode = -1;
    private static boolean lastSentHardLock = false;
    private static boolean lastSentRadarOn = true;
    private static float lastSentTrackQuality = 1f;
    private static java.util.UUID lastSentBvrUuid = null;
    private static int lastSentSource = -1;
    private static RadarMode lastMode = RadarMode.SRC_PD;
    /** 当前扫描行与上一 tick 的扫描位置，用于构造本 tick 扫过段。 */
    private static int scanRow = -1;
    private static int lastSweepRow = -1;
    private static double lastSweepAzimuth = 0;
    /** 限制同一目标的 SRC PD 扫描命中上报频率。 */
    private static final java.util.Map<Integer, Integer> paintSentTicks = new java.util.HashMap<>();

    /** 当前 tick 实际扫描的波束窗口，角度均相对玩家视线。 */
    private record ScanWindow(double azCenterDeg, double elCenterDeg,
                              double azHalfDeg, double elHalfDeg) {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        if (RadarHud.acmCooldownTicks > 0) {
            RadarHud.acmCooldownTicks--;
        }
        RadarHud.seekerEnabledTicks = RadarHud.seekerEnabled
                ? RadarHud.seekerEnabledTicks + 1 : 0;

        if (lastMode != RadarHud.currentMode) {
            boolean keepLockedTarget = RadarHud.currentMode == RadarMode.STT
                    && RadarHud.locked && RadarHud.lockedEntity != null
                    && RadarHud.lockedTargetSource == TargetSource.LOCAL;
            if (RadarHud.lockedTargetUuid != null && !keepLockedTarget) {
                clearLockForModeChange();
            }
            clearRadarPanelForModeChange(keepLockedTarget);
        }

        if ((lastMode == RadarMode.TWS || lastMode == RadarMode.STT)
                && RadarHud.currentMode != RadarMode.TWS
                && RadarHud.currentMode != RadarMode.STT) {
            RadarHud.scanAzimuthOffsetDeg = 0;
            RadarHud.scanElevationOffsetDeg = 0;
        }
        if (RadarHud.effectiveScanHalfDeg() >= RadarHud.PANEL_AZIMUTH_DEG - 0.01) {
            RadarHud.scanAzimuthOffsetDeg = 0;
        }
        lastMode = RadarHud.currentMode;
        if (RadarHud.seekerEnabled && !hasRadarSeekerSource()) {
            disableSeeker("导引头：关闭");
        }
        if (RadarHud.currentMode == RadarMode.ACM) {
            // ACM 搜索位置固定，不允许方向键平移；距离/宽度仍可调。
            RadarHud.scanAzimuthOffsetDeg = 0;
            RadarHud.scanElevationOffsetDeg = 0;
        }
        updateScanState(player);

        boolean anyMissileInFlight = false;
        java.util.UUID ownId = player.getUUID();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof MissileArrow arrow) {
                if (ownId.equals(arrow.getOwnerUuid())) {
                    anyMissileInFlight = true;
                }
            }
        }
        // 超视距发射的导弹超出客户端实体跟踪范围时，用服务端 BVR 航迹维持。
        boolean ownBvrMissile = BvrState.hasOwnMissile(player.getUUID());
        RadarHud.missileInFlight = anyMissileInFlight || ownBvrMissile;
        boolean missileStarted = !missileWasInFlight && RadarHud.missileInFlight;
        boolean missileEnded = missileWasInFlight && !RadarHud.missileInFlight;
        missileWasInFlight = RadarHud.missileInFlight;
        if (missileEnded) {
            // 命中/自爆（实体移除）后立刻删除惯性小圈与引导线
            RadarHud.seekerInertial = false;
            RadarHud.guidanceLineActive = false;
            RadarHud.missileOutcome = "导弹已结束";
            RadarHud.missileOutcomeAt = System.currentTimeMillis();
            inertialTicks = 0;
            suppressLockInertial = true;
        }


        if (missileStarted) {
            startGuidanceLine();
        }

        if (!RadarHud.radarOn) {
            RadarHud.contacts.clear();
            RadarHud.contactPaintTicks.clear();
            RadarHud.echoes.clear();
            RadarHud.lockedEntity = null;
            RadarHud.locked = false;
            RadarHud.lockAcquiring = false;
            RadarHud.lockedTargetUuid = null;
            RadarHud.lockedTargetSource = null;
            BvrState.clearPaints();
            RadarHud.seekerModel.reset();
            RadarHud.seekerInertial = false;
            RadarHud.guidanceLineActive = false;
            RadarHud.seekerRing.resetToCenter(0, 0);
            lastSeekerLockId = -1;
            TwsTrackManager.clear();
            updateIrSeeker(mc, player);
            syncRadarState(mc);
            return;
        }

        // 雷达扫描：搜索模式只探测当前扫描行扫过的窄波束；ACM 保留连续全框截获。
        Vec3 origin = player.getEyePosition();
        int nowTick = player.tickCount;
        boolean trackingView = RadarHud.isTrackingView();
        List<RadarContact> rawContacts;
        double contactOffsetAz;
        double contactOffsetEl;
        if (trackingView) {
            // TRK PD 只跟踪锁定目标（本地或 BVR），不再跑通用扇区扫描。
            rawContacts = List.of();
            contactOffsetAz = 0.0;
            contactOffsetEl = 0.0;
        } else if (RadarHud.currentMode == RadarMode.ACM) {
            // ACM 三波束模式：0=正前静止整框扫描；1/2=按当前波束窄窗扫描。
            if (RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_FIXED) {
                Vec3 look = scanDirection(player);
                rawContacts = RadarCore.scan(mc.level, origin, look, RadarHud.currentMode,
                        RadarHud.effectiveRange(), Math.toRadians(RadarHud.effectiveScanHalfDeg()),
                        Math.toRadians(RadarHud.effectiveScanElevationHalfDeg()),
                        player.getDeltaMovement(), player);
                contactOffsetAz = RadarHud.scanAzimuthOffsetDeg;
                contactOffsetEl = RadarHud.scanElevationOffsetDeg;
            } else {
                ScanWindow window = acmScanWindow();
                Vec3 beamDir = Vec3.directionFromRotation(
                        player.getXRot() - (float) window.elCenterDeg(),
                        player.getYRot() + (float) window.azCenterDeg()).normalize();
                rawContacts = RadarCore.scanBeam(mc.level, origin, beamDir, RadarHud.currentMode,
                        RadarHud.effectiveRange(), Math.toRadians(window.azHalfDeg()),
                        Math.toRadians(window.elHalfDeg()), player.getDeltaMovement(), player);
                contactOffsetAz = window.azCenterDeg();
                contactOffsetEl = window.elCenterDeg();
            }
        } else {
            ScanWindow window = currentScanWindow();
            Vec3 beamDir = Vec3.directionFromRotation(
                    player.getXRot() - (float) window.elCenterDeg(),
                    player.getYRot() + (float) window.azCenterDeg()).normalize();
            rawContacts = RadarCore.scanBeam(mc.level, origin, beamDir, RadarHud.currentMode,
                    RadarHud.effectiveRange(), Math.toRadians(window.azHalfDeg()),
                    Math.toRadians(window.elHalfDeg()), player.getDeltaMovement(), player);
            contactOffsetAz = window.azCenterDeg();
            contactOffsetEl = window.elCenterDeg();
        }
        List<RadarContact> contacts = new ArrayList<>();
        for (RadarContact c : rawContacts) {
            // TWS 只搜索空中目标，地面/海面/方块目标不得进入 TWS 航迹。
            if (RadarHud.currentMode == RadarMode.TWS
                    && c.targetClass != RadarTargetClass.AIR) continue;
            contacts.add(new RadarContact(c.entity, c.distance,
                    c.azimuthDeg + contactOffsetAz,
                    c.elevationDeg + contactOffsetEl,
                    c.radialVelocity, c.position, c.occluded, c.inDopplerNotch,
                    c.notchSeverity, c.targetClass));
        }
        RadarHud.contacts.clear();
        RadarHud.contacts.addAll(contacts);
        RadarHud.echoes.entrySet().removeIf(e -> nowTick - e.getValue().lastHitTick > RadarHud.CONTACT_MEMORY_TICKS);
        RadarHud.contactPaintTicks.entrySet().removeIf(e -> nowTick - e.getValue() > RadarHud.CONTACT_MEMORY_TICKS);
        for (RadarContact c : contacts) {
            // 当前 contacts 已经是本 tick 波束（或 ACM 全框）内的命中，不再做二次扫描线门控。
            RadarEcho echo = RadarHud.echoes.get(c.entity.getUUID());
            if (echo == null) {
                RadarHud.echoes.put(c.entity.getUUID(), new RadarEcho(c, nowTick));
            } else {
                echo.update(c, nowTick);
            }
            RadarHud.contactPaintTicks.put(c.entity.getUUID(), nowTick);
            if (RadarHud.currentMode == RadarMode.SRC_PD
                    && c.entity instanceof Player target && target != player) {
                int last = paintSentTicks.getOrDefault(target.getId(), -100);
                if (nowTick - last >= 10) {
                    Network.sendToServer(new RwrPaintPacket(target.getId()));
                    paintSentTicks.put(target.getId(), nowTick);
                }
            }
        }
        updateBvrPaints(mc, player, origin, nowTick);
        if (RadarHud.currentMode == RadarMode.TWS) {
            // TWS 与搜索雷达同一套逐行波束：航迹由命中回波刷新，未命中时由预测维持。
            // 清除切换模式残留的地面/海面回波，避免 TWS 面板继续显示。
            RadarHud.echoes.entrySet().removeIf(e ->
                    e.getValue().targetClass != RadarTargetClass.AIR);
            TwsTrackManager.update(nowTick);
            TwsTrack current = TwsTrackManager.selected();
            if (current != null) {
                RadarHud.selectedTargetUuid = current.uuid;
                RadarHud.selectedEchoId = current.entityId;
                RadarHud.selectionInitialized = true;
            }
            followTwsTarget(player, nowTick);
        } else {
            TwsTrackManager.clear();
        }
        TargetRegistry.validateSelection(player, nowTick);
        if (RadarHud.isTrackingView()) {
            // TRK 只管当前锁定目标：清掉通用扫描产生的其它/地面回波。
            RadarHud.contacts.clear();
            RadarHud.contactPaintTicks.clear();
            if (RadarHud.lockedTargetSource == TargetSource.LOCAL
                    && RadarHud.lockedEntity != null) {
                int lockId = RadarHud.lockedEntity.getId();
                RadarHud.echoes.entrySet().removeIf(e -> e.getValue().entityId != lockId);
            } else {
                RadarHud.echoes.clear();
            }
        }
        if (RadarHud.isTrackingView() && !RadarHud.lockAcquiring) {
            followLockedTarget(player, nowTick);
        }

        // ACM 格斗模式：自动截获机头前方最近目标；解锁后短暂冷却，避免瞬间复锁。
        if (RadarHud.acmActive && RadarHud.currentMode == RadarMode.ACM
                && !RadarHud.hasActiveLockTarget() && RadarHud.acmCooldownTicks <= 0) {
            RadarContact best = null;
            double bestDist = Double.MAX_VALUE;
            for (RadarContact c : contacts) {
                if (c.occluded) continue;
                if (c.distance > RadarHud.effectiveRange()
                        || Math.abs(c.azimuthDeg - RadarHud.scanAzimuthOffsetDeg)
                        > RadarHud.effectiveScanHalfDeg()
                        || Math.abs(c.elevationDeg - RadarHud.scanElevationOffsetDeg)
                        > Math.max(10.0, RadarHud.effectiveScanElevationHalfDeg())) continue;
                if (c.distance < bestDist) {
                    bestDist = c.distance;
                    best = c;
                }
            }
            if (best != null) {
                RadarHud.preLockMode = RadarHud.currentMode;
                completeLock(best.entity);
                lockMessage("ACM 自动截获 / TRK PD");
            }
        }

        // 手动锁定建立阶段：本地/BVR 共用同一 ACQ 流程。
        if (RadarHud.lockAcquiring) {
            updateLockAcquisition(player, nowTick);
        }

        // TRK PD 是连续跟踪模式：本地/BVR 共用同一连续跟踪流程。
        if (RadarHud.isTrackingView() && !RadarHud.lockAcquiring) {
            updateContinuousTrack(player, nowTick);
        }

        if (!RadarHud.hasActiveLockTarget() && !RadarHud.missileInFlight) {
            RadarHud.seekerInertial = false;
        }

        updateRadarSeeker(mc, player);
        updateIrSeeker(mc, player);
        updateInertialProjection(player);
        updateGuidanceLine(mc, player);
        syncRadarState(mc);
    }

    /** 锁定目标/导引头状态变化时同步给服务端，供发射校验使用。 */
    private static void syncRadarState(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return;
        Player player = mc.player;
        int tick = player.tickCount;
        // RWR/雷达状态与当前选择的弹种无关；IR 模式下仍要同步雷达跟踪/锁定。
        TrackedTarget lockTarget = RadarHud.lockedTargetUuid == null ? null
                : TargetRegistry.find(RadarHud.lockedTargetUuid, player, tick);
        boolean localHardLock = RadarHud.locked && lockTarget != null
                && lockTarget.source == TargetSource.LOCAL && lockTarget.entity != null;
        Entity target = null;
        if (localHardLock) {
            target = lockTarget.entity;
        } else if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            if (track != null) target = TwsTrackManager.entity(track.entityId);
        }
        boolean bvrLock = RadarHud.isBvrLock() && RadarHud.lockedTargetUuid != null;
        int targetId = target == null ? -1 : target.getId();
        int state = RadarHud.seekerEnabled && RadarHud.hasActiveLockTarget()
                ? RadarHud.seekerModel.state().ordinal() : -1;
        int mode = RadarHud.currentMode.ordinal();
        float quality = 0f;
        if (target != null) {
            RadarEcho echo = RadarHud.echoes.get(target.getUUID());
            quality = echo == null ? 0.3f
                    : (float) Math.max(0.15, 1.0 - echo.notchSeverity * 0.65);
            if (localHardLock) quality *= Math.max(0.15f, RadarHud.lockAlpha);
            quality = Math.max(0.05f, Math.min(1.0f, quality));
        } else if (bvrLock) {
            quality = 0.6f;
        }
        java.util.UUID sentUuid = bvrLock ? RadarHud.lockedTargetUuid
                : (target == null ? null : target.getUUID());
        int sentSource = bvrLock ? TargetSource.BVR.ordinal()
                : TargetSource.LOCAL.ordinal();
        boolean changed = targetId != lastSentTargetId || state != lastSentSeekerState
                || mode != lastSentMode || localHardLock != lastSentHardLock
                || RadarHud.radarOn != lastSentRadarOn
                || Math.abs(quality - lastSentTrackQuality) > 0.02f
                || !java.util.Objects.equals(sentUuid, lastSentBvrUuid)
                || sentSource != lastSentSource;
        boolean refresh = tick - lastSentTick >= 20;
        if (changed || refresh) {
            Network.sendToServer(new RadarSyncPacket(targetId, state, mode, localHardLock,
                    RadarHud.radarOn, quality, sentUuid, sentSource));
            lastSentTargetId = targetId;
            lastSentSeekerState = state;
            lastSentMode = mode;
            lastSentHardLock = localHardLock;
            lastSentRadarOn = RadarHud.radarOn;
            lastSentTrackQuality = quality;
            lastSentBvrUuid = sentUuid;
            lastSentSource = sentSource;
            lastSentTick = tick;
        }
    }

    /**
     * 雷达弹导引头：中间环从屏幕中心抛向目标；TRK 与 TWS 通用，只有硬/软锁定才能开启。
     * 目标来源优先级：硬锁定（TRK PD/STT）> TWS 当前软跟踪 > BVR 远程 TRK（数据链软锁）。
     * 稳定环状态由 SeekerLockModel 计算，HUD 直接读取。
     */
    private static void updateRadarSeeker(Minecraft mc, Player player) {
        boolean radarGuided = RadarHud.selectedMissile == MissileSpec.Guidance.SARH
                || RadarHud.selectedMissile == MissileSpec.Guidance.ARH;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        if (!radarGuided || !RadarHud.seekerEnabled) {
            if (lastSeekerLockId != -1) {
                RadarHud.seekerRing.resetToCenter(sw / 2.0, sh / 2.0);
                RadarHud.seekerModel.reset();
                RadarHud.lockProgress = 0f;
                lastSeekerLockId = -1;
            }
            return;
        }

        // 统一目标：硬锁定可以是本地实体或 BVR 数据链；TWS 软跟踪仍走本地实体。
        TrackedTarget lockTarget = RadarHud.lockedTargetUuid == null ? null
                : TargetRegistry.find(RadarHud.lockedTargetUuid, player, player.tickCount);
        Entity entityTarget = lockTarget != null && lockTarget.entity != null
                ? lockTarget.entity : null;
        TwsTrack twsTrack = entityTarget == null && RadarHud.currentMode == RadarMode.TWS
                ? TwsTrackManager.selected() : null;
        if (entityTarget == null && twsTrack != null) {
            entityTarget = TwsTrackManager.entity(twsTrack.entityId);
            if (entityTarget != null && !entityTarget.isAlive()) entityTarget = null;
        }

        int sourceId;
        Vec3 targetPos;
        double closure;
        if (entityTarget != null) {
            sourceId = entityTarget.getId();
            targetPos = entityTarget.getBoundingBox().getCenter();
            closure = RadarHud.locked ? RadarHud.lockedClosure
                    : (twsTrack == null ? 0.0 : twsTrack.closure);
        } else if (lockTarget != null && !lockTarget.isOwnedBy(player)) {
            sourceId = -(int) Math.abs((long) lockTarget.uuid.hashCode());
            targetPos = lockTarget.livePosition();
            closure = RadarHud.lockedClosure;
        } else {
            if (lastSeekerLockId != -1) {
                RadarHud.seekerRing.resetToCenter(sw / 2.0, sh / 2.0);
                RadarHud.seekerModel.reset();
                RadarHud.lockProgress = 0f;
                lastSeekerLockId = -1;
            }
            return;
        }

        Vec3 eye = player.getEyePosition();
        double range = player.getEyePosition().distanceTo(targetPos);
        if (sourceId != lastSeekerLockId) {
            double hold = RadarHud.selectedMissile == MissileSpec.Guidance.ARH ? 0.5 : 1.0;
            RadarHud.seekerRing.resetToCenter(sw / 2.0, sh / 2.0, hold);
            RadarHud.seekerModel.reset();
            RadarHud.lockProgress = 0f;
            lastSeekerLockId = sourceId;
        }

        Vec3 to = targetPos.subtract(eye);
        Vec3 look = player.getViewVector(1.0F).normalize();
        double offBoresight = to.lengthSqr() > 1e-6 ? Math.toDegrees(Math.acos(
                clamp(look.dot(to.normalize()), -1.0, 1.0))) : 0.0;

        float[] p = RadarHudRenderer.worldToScreen(targetPos, player, sw, sh);
        double ringX = p == null ? sw / 2.0 : clamp(p[0], 24.0, sw - 24.0);
        double ringY = p == null ? sh / 2.0 : clamp(p[1], 24.0, sh - 24.0);
        RadarHud.seekerRing.update(ringX, ringY, 0.05);

        boolean aligned = RadarHud.seekerRing.aligned();
        RadarHud.seekerModel.update(0.05, true, aligned, offBoresight, range,
                closure, RadarHud.selectedSpec);
        RadarHud.lockProgress = (float) RadarHud.seekerModel.progress();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 红外导引头视觉：由 Alt+X 统一开关。
     * 锁定前小圈居中，锁定后跟随目标；目标必须保持在 lockAngleMax 大圈内。
     * 雷达辅助：本地硬锁 / TWS 选中 / BVR 远程锁都会牵引小圈到目标实时位置；
     * BVR 目标未本地加载时只做数据链牵引（不能完成 IR 强锁）。
     */
    private static void updateIrSeeker(Minecraft mc, Player player) {
        RadarHud.seekerActive = false;
        RadarHud.irCandidate = null;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (!RadarHud.seekerEnabled
                || RadarHud.selectedMissile != MissileSpec.Guidance.IR
                || RadarHud.selectedSpec == null) {
            clearIrLock();
            RadarHud.irAimX = sw / 2.0f;
            RadarHud.irAimY = sh / 2.0f;
            RadarHud.irAimInitialized = true;
            RadarHud.irStatus = "";
            return;
        }

        RadarHud.seekerActive = true;
        if (!RadarHud.irAimInitialized) {
            RadarHud.irAimX = sw / 2.0f;
            RadarHud.irAimY = sh / 2.0f;
            RadarHud.irAimInitialized = true;
        }

        RadarAssist radarAim = radarAssistTarget(player);
        Entity assistEntity = radarAim == null ? null : radarAim.entity();

        Entity lockedTarget = RadarHud.irLocked ? RadarHud.seekerLockedEntity : null;
        if (lockedTarget == null && RadarHud.irLocked) {
            clearIrLock();
        }
        if (lockedTarget != null) {
            boolean sameAsRadar = radarAim == null
                    || radarAim.uuid().equals(lockedTarget.getUUID());
            if (!sameAsRadar || !lockedTarget.isAlive()
                    || !IrTargeting.valid(mc.level, player, lockedTarget,
                    RadarHud.selectedSpec)) {
                clearIrLock();
                lockedTarget = null;
            }
        }

        Entity target = lockedTarget != null ? lockedTarget
                : (assistEntity != null ? assistEntity
                : (radarAim == null ? IrTargeting.findTarget(mc.level, player, null,
                RadarHud.selectedSpec) : null));
        RadarHud.irCandidate = target;

        float ui = HudScale.factor(sw, sh);
        double smallRadius = Math.max(16, Math.round(RadarHud.IR_LOCK_RING_BASE * ui));

        // 牵引位置：雷达辅助优先（支持 BVR 数据链），否则自由搜索到的热源。
        Vec3 aimPos = radarAim != null ? radarAim.position()
                : (target != null ? target.getBoundingBox().getCenter() : null);
        if (aimPos != null) {
            float[] aimScreen = RadarHudRenderer.worldToScreen(aimPos, player, sw, sh);
            if (aimScreen != null) {
                double dx = aimScreen[0] - RadarHud.irAimX;
                double dy = aimScreen[1] - RadarHud.irAimY;
                double distance = Math.hypot(dx, dy);
                double step = 700.0 * 0.05;
                if (distance <= step + 0.5) {
                    RadarHud.irAimX = aimScreen[0];
                    RadarHud.irAimY = aimScreen[1];
                } else {
                    RadarHud.irAimX += (float) (dx / distance * step);
                    RadarHud.irAimY += (float) (dy / distance * step);
                }
            }
        } else {
            RadarHud.irAimX = sw / 2.0f;
            RadarHud.irAimY = sh / 2.0f;
        }

        if (target == null) {
            clearIrLock();
            RadarHud.irStatus = radarAim != null ? "雷达辅助" : "搜索";
            return;
        }

        float[] targetScreen = RadarHudRenderer.worldToScreen(
                target.getBoundingBox().getCenter(), player, sw, sh);
        if (targetScreen == null) {
            clearIrLock();
            RadarHud.irStatus = radarAim != null ? "雷达辅助" : "搜索";
            return;
        }

        int targetId = target.getId();
        if (lockedTarget != null) {
            RadarHud.irLocked = true;
            RadarHud.seekerLockedEntity = lockedTarget;
            RadarHud.irLockTargetId = targetId;
            RadarHud.irLockTicks = 20;
            RadarHud.irStatus = "锁定";
            return;
        }

        if (targetId != RadarHud.irLockTargetId) {
            RadarHud.irLockTargetId = targetId;
            RadarHud.irLockTicks = 0;
        }

        double targetDistance = Math.hypot(
                targetScreen[0] - RadarHud.irAimX,
                targetScreen[1] - RadarHud.irAimY);
        boolean validTarget = IrTargeting.valid(mc.level, player, target,
                RadarHud.selectedSpec);
        if (targetDistance <= smallRadius && validTarget) {
            RadarHud.irLockTicks++;
            RadarHud.irStatus = RadarHud.irLockTicks >= 20 ? "锁定" : "追踪";
            if (RadarHud.irLockTicks >= 20) {
                RadarHud.irLocked = true;
                RadarHud.seekerLockedEntity = target;
                RadarHud.irLockTargetId = targetId;
            }
        } else {
            RadarHud.irLockTicks = 0;
            RadarHud.irLocked = false;
            RadarHud.seekerLockedEntity = null;
            RadarHud.irStatus = radarAim != null ? "雷达辅助" : "截获";
        }
    }

    /** 雷达辅助目标：本地硬锁 / TWS 选中 / BVR 远程锁（可无本地实体）。 */
    private record RadarAssist(java.util.UUID uuid, @Nullable Entity entity,
                               Vec3 position, Vec3 velocity) {}

    @Nullable
    private static RadarAssist radarAssistTarget(Player player) {
        if (RadarHud.hasActiveLockTarget()) {
            TrackedTarget tracked = TargetRegistry.find(
                    RadarHud.lockedTargetUuid, player, player.tickCount);
            if (tracked != null) {
                return new RadarAssist(tracked.uuid, tracked.entity,
                        tracked.livePosition(), tracked.velocity());
            }
        }
        if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            Entity entity = track == null ? null : TwsTrackManager.entity(track.entityId);
            if (entity != null && entity.isAlive()) {
                return new RadarAssist(entity.getUUID(), entity,
                        entity.getBoundingBox().getCenter(), entity.getDeltaMovement());
            }
        }
        return null;
    }

    private static void clearIrLock() {
        RadarHud.irLocked = false;
        RadarHud.seekerLockedEntity = null;
        RadarHud.irLockTargetId = -1;
        RadarHud.irLockTicks = 0;
    }

    /** 锁定目标未被雷达刷新时，计算其在雷达面板上的预测点。 */
    private static void updateInertialPoint(Player player, Entity target) {
        RadarHud.inertialWorldPosition = target.getBoundingBox().getCenter();
        RadarHud.inertialVelocity = target.getDeltaMovement();
        updateInertialProjection(player);
    }

    /** 按当前机头姿态把惯性目标位置重新投影到雷达面板，机头转动时小圈会跟着移动。 */
    private static void updateInertialProjection(Player player) {
        if (!RadarHud.seekerInertial) return;
        Vec3 targetPos = RadarHud.inertialWorldPosition;
        if (targetPos.equals(Vec3.ZERO)) return;
        targetPos = targetPos.add(RadarHud.inertialVelocity.scale(0.05));
        RadarHud.inertialWorldPosition = targetPos;

        Vec3 to = targetPos.subtract(player.getEyePosition());
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        RadarHud.inertialDistance = to.length();
        RadarHud.inertialAzimuthDeg = Math.toDegrees(Math.atan2(to.dot(right), to.dot(look)));
    }

    /** 记录发射瞬间的引导目标与主动弹开机距离。 */
    private static void startGuidanceLine() {
        Player player = Minecraft.getInstance().player;
        int tick = player == null ? 0 : player.tickCount;
        TrackedTarget guided = player == null || RadarHud.lockedTargetUuid == null
                ? null : TargetRegistry.find(RadarHud.lockedTargetUuid, player, tick);
        Entity target = guided == null ? null : guided.entity;
        if (target == null && RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            target = track == null ? null : TwsTrackManager.entity(track.entityId);
        }
        // IR 发射：无雷达目标时用发射瞬间记录的热源实体。
        if (target == null && RadarHud.pendingGuidanceTargetId >= 0) {
            target = Minecraft.getInstance().level == null ? null
                    : Minecraft.getInstance().level.getEntity(RadarHud.pendingGuidanceTargetId);
        }
        RadarHud.pendingGuidanceTargetId = -1;
        RadarHud.guidanceLineActive = target != null || guided != null;
        if (target != null) {
            RadarHud.guidanceTargetId = target.getId();
            RadarHud.guidanceLastWorldPosition = target.getBoundingBox().getCenter();
        } else {
            RadarHud.guidanceTargetId = -1;
            // BVR 锁定：目标不在客户端实体范围内，端点由统一目标实时维护。
            if (guided != null) {
                RadarHud.guidanceLastWorldPosition = guided.livePosition();
            }
        }
        RadarHud.guidancePitbullRange = RadarHud.selectedSpec == null
                ? 0 : RadarHud.selectedSpec.seekerActivationRange;
        RadarHud.guidanceIsArh = RadarHud.selectedMissile == MissileSpec.Guidance.ARH;
        RadarHud.guidanceMissileId = findOwnMissileId();
        Minecraft mc = Minecraft.getInstance();
        if (RadarHud.guidanceMissileId < 0 && mc.player != null) {
            BvrTrack missile = BvrState.ownMissile(mc.player.getUUID());
            RadarHud.guidanceMissileUuid = missile == null ? null : missile.uuid();
        }
    }

    private static int findOwnMissileId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return -1;
        java.util.UUID ownId = mc.player.getUUID();
        double best = Double.MAX_VALUE;
        int id = -1;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof MissileArrow arrow)) continue;
            if (!ownId.equals(arrow.getOwnerUuid())) continue;
            double d = e.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                id = e.getId();
            }
        }
        BvrTrack bvr = BvrState.ownMissile(mc.player.getUUID());
        RadarHud.guidanceMissileUuid = id >= 0 ? null : (bvr == null ? null : bvr.uuid());
        return id;
    }

    /** 引导线终点：有雷达数据时跟目标，失锁后跟惯性预测点。 */
    private static void updateGuidanceLine(Minecraft mc, Player player) {
        if (!RadarHud.guidanceLineActive) return;
        if (!RadarHud.missileInFlight) {
            RadarHud.guidanceLineActive = false;
            RadarHud.guidanceMissileId = -1;
            RadarHud.guidanceMissileUuid = null;
            return;
        }
        Entity missile = RadarHud.guidanceMissileId >= 0
                ? mc.level.getEntity(RadarHud.guidanceMissileId) : null;
        if (missile == null) {
            // 发射瞬间实体可能还没到客户端：每 tick 重新认领本机导弹。
            int id = findOwnMissileId();
            if (id >= 0) {
                RadarHud.guidanceMissileId = id;
                missile = mc.level.getEntity(id);
            }
        }
        if (missile != null) {
            RadarHud.guidanceMissileUuid = null;
            Vec3 toMissile = missile.getBoundingBox().getCenter().subtract(player.getEyePosition());
            Vec3 view = player.getViewVector(1.0F).normalize();
            Vec3 right = view.cross(new Vec3(0, 1, 0));
            if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
            right = right.normalize();
            RadarHud.guidanceMissileDistance = toMissile.length();
            RadarHud.guidanceMissileAzimuthDeg = Math.toDegrees(
                    Math.atan2(toMissile.dot(right), toMissile.dot(view)));
        } else if (RadarHud.guidanceMissileUuid != null && mc.player != null) {
            BvrTrack bvrMissile = BvrState.get(RadarHud.guidanceMissileUuid);
            if (bvrMissile == null)
                bvrMissile = BvrState.ownMissile(mc.player.getUUID());
            if (bvrMissile != null) {
                Vec3 missilePos = BvrState.lagCompensatedPosition(bvrMissile);
                Vec3 toMissile = missilePos.subtract(player.getEyePosition());
                Vec3 view = player.getViewVector(1.0F).normalize();
                Vec3 right = view.cross(new Vec3(0, 1, 0));
                if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
                right = right.normalize();
                RadarHud.guidanceMissileDistance = toMissile.length();
                RadarHud.guidanceMissileAzimuthDeg = Math.toDegrees(
                        Math.atan2(toMissile.dot(right), toMissile.dot(view)));
            }
        }
        Vec3 pos;
        if (RadarHud.seekerInertial) {
            pos = RadarHud.inertialWorldPosition;
            RadarHud.guidanceTargetAzimuthDeg = RadarHud.inertialAzimuthDeg;
            RadarHud.guidanceTargetDistance = RadarHud.inertialDistance;
            return;
        }
        // 失锁后冻结惯性位置；只有玩家重新锁定才恢复实时目标更新。
        // 引导线端点跟目标实体；目标失锁后仍保留最后位置。
        Entity target = RadarHud.guidanceTargetId >= 0
                ? mc.level.getEntity(RadarHud.guidanceTargetId) : null;
        if (target != null) {
            pos = target.getBoundingBox().getCenter();
            RadarHud.guidanceLastWorldPosition = pos;
        } else if (RadarHud.lockedTargetUuid != null) {
            TrackedTarget guided = TargetRegistry.find(
                    RadarHud.lockedTargetUuid, player, player.tickCount);
            if (guided != null) {
                pos = guided.livePosition();
                RadarHud.guidanceLastWorldPosition = pos;
            } else {
                pos = RadarHud.guidanceLastWorldPosition;
            }
        } else {
            pos = RadarHud.guidanceLastWorldPosition;
        }
        if (pos.equals(Vec3.ZERO)) return;

        Vec3 to = pos.subtract(player.getEyePosition());
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        RadarHud.guidanceTargetDistance = to.length();
        RadarHud.guidanceTargetAzimuthDeg = Math.toDegrees(
                Math.atan2(to.dot(right), to.dot(look)));
    }

    /** TRK PD 连续跟踪：本地读实体、BVR 读数据链预测，同一套包线/拖锁规则。 */
    private static void updateContinuousTrack(Player player, int tick) {
        TrackedTarget tracked = TargetRegistry.find(RadarHud.lockedTargetUuid, player, tick);
        if (tracked == null || !tracked.alive(player)) {
            // BVR 数据链短暂断流（快照间隔/丢包）给宽限，避免一帧断源就掉锁。
            if (RadarHud.isBvrLock() && tracked == null) {
                lockTicks--;
                RadarHud.lockLostTicks++;
                RadarHud.lockAlpha = Math.max(0.35f,
                        1f - RadarHud.lockLostTicks / (float) TRK_LOST_GRACE_TICKS);
                if (RadarHud.lockLostTicks >= TRK_LOST_GRACE_TICKS) {
                    dropLock("BVR 远程失锁");
                }
                return;
            }
            dropLock(RadarHud.isBvrLock() ? "BVR 远程失锁" : "目标拖锁");
            return;
        }
        Entity target = tracked.entity;
        Vec3 eye = player.getEyePosition();
        Vec3 targetPos = tracked.livePosition();
        Vec3 to = targetPos.subtract(eye);
        double range = to.length();
        Vec3 look = player.getViewVector(1.0F).normalize();
        double azimuth = Math.toDegrees(RadarCore.azimuthBetween(look, to));
        double elevation = Math.toDegrees(RadarCore.elevationBetween(look, to));
        RadarTargetClass targetClass = tracked.targetClass == null
                ? RadarCore.classify(target) : tracked.targetClass;
        boolean occluded = target != null && targetClass != null
                && RadarCore.isOccludedForTarget(player.level(), eye, targetPos, targetClass);
        boolean valid = range > 0.1
                && range <= RadarHud.effectiveRange()
                && !occluded
                && Math.abs(azimuth) <= RadarHud.effectiveScanHalfDeg()
                && Math.abs(elevation) <= RadarHud.effectiveScanElevationHalfDeg();

        if (valid) {
            lockTicks = LOCK_HOLD_TICKS;
            inertialTicks = 0;
            RadarHud.lockLostTicks = 0;
            RadarHud.lockAlpha = 1f;
            RadarHud.seekerInertial = false;
            suppressLockInertial = false;
            refreshBvrPaint(player, tracked, tick);
            updateLockInfo(player, tracked);

            if (target != null && targetClass != null) {
                Vec3 relativeVelocity = target.getDeltaMovement()
                        .subtract(player.getDeltaMovement()).scale(20.0);
                double radialVelocity = to.lengthSqr() < 1.0E-6 ? 0.0
                        : relativeVelocity.dot(to.normalize());
                double notchSeverity = RadarCore.notchSeverity(
                        radialVelocity, range, RadarHud.currentMode);
                RadarContact contact = new RadarContact(target, range, azimuth, elevation,
                        radialVelocity, targetPos, false, notchSeverity > 0.4,
                        notchSeverity, targetClass);
                RadarEcho echo = RadarHud.echoes.get(target.getUUID());
                if (echo == null) {
                    RadarHud.echoes.put(target.getUUID(), new RadarEcho(contact, tick));
                } else {
                    echo.update(contact, tick);
                }
            }
            return;
        }

        lockTicks--;
        inertialTicks++;
        RadarHud.lockLostTicks++;
        RadarHud.lockAlpha = Math.min(RadarHud.lockAlpha,
                Math.max(0f, 1f - RadarHud.lockLostTicks / (float) TRK_LOST_GRACE_TICKS));
        RadarHud.seekerInertial = false;
        if (RadarHud.lockLostTicks >= TRK_LOST_GRACE_TICKS) {
            dropLock(RadarHud.isBvrLock() ? "BVR 远程失锁" : "目标拖锁");
        }
    }

    /**
     * ACQ/TRK 期间持续刷新 BVR 涂抹：远程目标无法靠物理扫描刷新，
     * 锁定时由数据链每 tick 续期，保证与本地 TRK 一样不会“忘记”目标。
     */
    private static void refreshBvrPaint(Player player, TrackedTarget target, int tick) {
        if (target == null || target.bvr == null) return;
        Vec3 eye = player.getEyePosition();
        Vec3 targetPos = target.livePosition();
        Vec3 to = targetPos.subtract(eye);
        double range = to.length();
        if (range <= 0.1) return;
        Vec3 view = player.getViewVector(1.0F).normalize();
        Vec3 right = view.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        float frozenAz = (float) (Math.toDegrees(
                Math.atan2(to.dot(right), to.dot(view)))
                + RadarHud.scanAzimuthOffsetDeg);
        BvrState.paint(target.uuid, new BvrState.Paint(
                targetPos, target.velocity(), frozenAz, range, tick));
    }

    /** 更新锁定目标信息（名称/距离/接近速度），本地/BVR 共用。 */
    private static void updateLockInfo(Player player, TrackedTarget target) {
        Vec3 targetPos = target.livePosition();
        Vec3 eye = player.getEyePosition();
        RadarHud.lockedName = target.displayName();
        RadarHud.lockedDistance = eye.distanceTo(targetPos);
        Vec3 rel = target.velocity().subtract(player.getDeltaMovement()).scale(20.0);
        Vec3 dir = targetPos.subtract(eye);
        if (dir.lengthSqr() > 1.0E-6) {
            RadarHud.lockedClosure = -rel.dot(dir.normalize()); // 正 = 接近
        }
    }

    /** 进入 ACM：清空旧锁定/TWS，记住返回模式。 */
    public static void enterAcm() {
        if (RadarHud.acmActive) return;
        RadarMode returnMode = RadarHud.currentMode == RadarMode.STT
                ? RadarHud.preLockMode : RadarHud.currentMode;
        if (returnMode == RadarMode.ACM || returnMode == RadarMode.STT) {
            returnMode = RadarMode.SRC_PD;
        }
        clearLockState();
        TwsTrackManager.clear();
        // ACM 是纯视距内自动截获：统一锁定状态已随 clearLockState 断开。
        BvrState.clearPaints();
        RadarHud.acmReturnMode = returnMode;
        RadarHud.acmActive = true;
        RadarHud.currentMode = RadarMode.ACM;
        RadarHud.scanAzimuthOffsetDeg = 0;
        RadarHud.scanElevationOffsetDeg = 0;
        RadarHud.acmCooldownTicks = 0;
        TargetRegistry.clearSelection();
    }

    /** 退出 ACM（包括自动截获后已切到 TRK PD 的情况）。 */
    public static void exitAcm() {
        if (!RadarHud.acmActive) return;
        clearLockState();
        TwsTrackManager.clear();
        RadarHud.currentMode = RadarHud.acmReturnMode;
        RadarHud.acmActive = false;
        RadarHud.acmCooldownTicks = 0;
        RadarHud.scanAzimuthOffsetDeg = 0;
        RadarHud.scanElevationOffsetDeg = 0;
        TargetRegistry.clearSelection();
    }

    /** Alt+W：进入/退出 ACM，返回操作后是否仍在 ACM。 */
    public static boolean toggleAcm() {
        if (RadarHud.acmActive) {
            exitAcm();
            return false;
        }
        enterAcm();
        return true;
    }

    /** 当前模式是否允许建立该目标的锁定。 */
    private static boolean lockAllowed(Entity entity) {
        Player player = Minecraft.getInstance().player;
        if (entity instanceof MissileArrow missile && player != null
                && player.getUUID().equals(missile.getOwnerUuid())) {
            return false;
        }
        RadarTargetClass targetClass = RadarCore.classify(entity);
        if (targetClass == null) return false;
        return switch (RadarHud.currentMode) {
            case GMI -> targetClass == RadarTargetClass.GROUND
                    || targetClass == RadarTargetClass.SEA;
            case SEA -> targetClass == RadarTargetClass.SEA;
            case STT -> true;
            default -> targetClass == RadarTargetClass.AIR;
        };
    }

    /** 切换锁定：统一目标流程，本地/BVR 只差“是否已加载实体”。 */
    public static void toggleLock() {
        if (RadarHud.lockAcquiring) {
            cancelLockAttempt("取消尝试锁定");
            return;
        }
        if (RadarHud.locked) {
            dropLock("解除锁定");
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!RadarHud.radarOn) {
            lockMessage("雷达未开启");
            return;
        }
        if (RadarHud.acmActive || RadarHud.currentMode == RadarMode.ACM) {
            // ACM 是纯近距自动截获，不提供手动远程锁定。
            lockMessage("无有效回波");
            return;
        }
        TrackedTarget target = TargetRegistry.bestLockCandidate(player, player.tickCount);
        if (target == null) {
            lockMessage("无具备扫描记忆的有效目标");
            return;
        }
        beginLockAcquisition(target);
    }

    /** 写入锁定反馈并打时间戳，供消息行按新鲜度渲染。 */
    public static void lockMessage(String text) {
        RadarHud.lastLockMessage = text;
        RadarHud.lastLockMessageAt = System.currentTimeMillis();
    }

    /** 统一 ACQ：锁定状态机不区分目标来源。 */
    private static void beginLockAcquisition(TrackedTarget target) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !target.alive(player)) {
            lockMessage("尝试锁定失败");
            return;
        }
        RadarHud.preLockMode = RadarHud.currentMode == RadarMode.STT
                ? RadarHud.preLockMode : RadarHud.currentMode;
        RadarHud.preLockRangeIndex = RadarHud.rangeIndex;
        RadarHud.preLockScanPatternIndex = RadarHud.scanPatternIndex;
        RadarHud.preLockScanAzimuthOffsetDeg = RadarHud.scanAzimuthOffsetDeg;
        RadarHud.preLockScanElevationOffsetDeg = RadarHud.scanElevationOffsetDeg;
        RadarHud.lockAnchorPosition = target.scanPosition;
        RadarHud.lockedEntity = target.entity;
        RadarHud.lockedTargetUuid = target.uuid;
        RadarHud.lockedTargetSource = target.source;
        RadarHud.locked = false;
        RadarHud.lockAcquiring = true;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockAnchorAzimuthDeg = target.panelAzimuthDeg();
        RadarHud.lockAnchorRange = target.panelRange();
        RadarHud.lockProgress = 0f;
        RadarHud.lockAlpha = 1f;
        RadarHud.lockLostTicks = 0;
        RadarHud.seekerModel.reset();
        lastSeekerLockId = -1;
        updateLockInfo(player, target);
        lockMessage(target.source == TargetSource.BVR
                ? "ACQ PD 远程尝试锁定" : "ACQ PD 尝试锁定");
    }

    /** 统一 ACQ 更新：本地校验实体/遮挡，BVR 校验数据链新鲜度，其余规则相同。 */
    private static void updateLockAcquisition(Player player, int tick) {
        TrackedTarget target = TargetRegistry.find(RadarHud.lockedTargetUuid, player, tick);
        boolean eligible = target != null && TargetRegistry.eligibleForLock(target,
                player, tick, TargetRegistry.lockFreshnessTicks(),
                target.source != TargetSource.BVR);
        if (!eligible || !target.alive(player)) {
            cancelLockAttempt("尝试锁定失败");
            return;
        }
        refreshBvrPaint(player, target, tick);
        if (target.entity != null) {
            Vec3 eye = player.getEyePosition();
            Vec3 targetPos = target.entity.getBoundingBox().getCenter();
            RadarTargetClass targetClass = RadarCore.classify(target.entity);
            if (targetClass == null
                    || RadarCore.isOccludedForTarget(player.level(), eye, targetPos, targetClass)) {
                cancelLockAttempt("尝试锁定失败");
                return;
            }
        }
        updateLockInfo(player, target);
        RadarHud.lockAcquireTicks++;
        RadarHud.lockProgress = Math.min(1f,
                RadarHud.lockAcquireTicks / (float) RadarHud.LOCK_ACQUIRE_TICKS);
        if (RadarHud.lockAcquireTicks >= RadarHud.LOCK_ACQUIRE_TICKS) {
            completeLock(target);
            updateLockInfo(player, target);
        }
    }

    /** ACM 自动截获入口：把本地实体包装成统一目标后走同一锁定完成流程。 */
    private static void completeLock(Entity entity) {
        if (entity == null || !lockAllowed(entity)) return;
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        int tick = player.tickCount;
        RadarEcho echo = RadarHud.echoes.get(entity.getUUID());
        if (echo == null) {
            RadarContact contact = findContact(entity);
            if (contact != null) {
                echo = new RadarEcho(contact, tick);
                RadarHud.echoes.put(entity.getUUID(), echo);
            }
        }
        if (echo == null) return;
        completeLock(TrackedTarget.local(echo, entity));
    }

    /** 统一锁定完成：本地/BVR 唯一区别是是否带本地实体。 */
    private static void completeLock(TrackedTarget target) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !target.alive(player)) return;
        if (RadarHud.preLockMode == null) RadarHud.preLockMode = RadarHud.currentMode;
        // ACM 自动截获可能尚未经过扫描线，补齐一份回波，避免下一 tick 立刻失锁。
        if (target.entity != null) {
            RadarContact contact = findContact(target.entity);
            if (contact != null) {
                int tick = player.tickCount;
                RadarEcho echo = RadarHud.echoes.get(target.uuid);
                if (echo == null) {
                    RadarHud.echoes.put(target.uuid, new RadarEcho(contact, tick));
                } else {
                    echo.update(contact, tick);
                }
            }
        }
        RadarHud.lockedEntity = target.entity;
        RadarHud.lockedTargetUuid = target.uuid;
        RadarHud.lockedTargetSource = target.source;
        RadarHud.lockAcquiring = false;
        RadarHud.locked = true;
        RadarHud.currentMode = RadarMode.STT;
        lastMode = RadarMode.STT;
        RadarHud.lockProgress = 1f;
        RadarHud.lockAlpha = 1f;
        RadarHud.lockLostTicks = 0;
        RadarHud.seekerModel.reset();
        lastSeekerLockId = -1;
        lockTicks = LOCK_HOLD_TICKS;
        updateLockInfo(player, target);
        lockMessage(target.source == TargetSource.BVR
                ? "TRK PD 远程锁定" : "TRK PD 锁定");
    }

    private static void cancelLockAttempt(String message) {
        RadarHud.lockAcquiring = false;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockedEntity = null;
        RadarHud.lockedTargetUuid = null;
        RadarHud.lockedTargetSource = null;
        RadarHud.locked = false;
        RadarHud.lockProgress = 0f;
        RadarHud.lockAlpha = 0f;
        restorePreLockSearch();
        lockMessage(message);
    }

    /** 尝试锁定失败：恢复原搜索模式、距离档和扫描中心/宽度。 */
    private static void restorePreLockSearch() {
        RadarHud.currentMode = RadarHud.preLockMode == null
                ? RadarMode.SRC_PD : RadarHud.preLockMode;
        RadarHud.rangeIndex = RadarHud.preLockRangeIndex;
        RadarHud.displayRange = RadarHud.RANGES[Math.floorMod(RadarHud.rangeIndex,
                RadarHud.RANGES.length)];
        RadarHud.maxRange = RadarHud.displayRange;
        RadarHud.scanPatternIndex = RadarHud.preLockScanPatternIndex;
        RadarHud.scanAzimuthOffsetDeg = RadarHud.preLockScanAzimuthOffsetDeg;
        RadarHud.scanElevationOffsetDeg = RadarHud.preLockScanElevationOffsetDeg;
        RadarHud.lockAnchorPosition = Vec3.ZERO;
    }

    /** 当前扫描线/面板接触按模式切换清空旧绘制。 */
    private static void clearRadarPanelForModeChange(boolean keepLockedTarget) {
        RadarHud.contacts.clear();
        RadarHud.contactPaintTicks.clear();
        TwsTrackManager.clear();
        if (keepLockedTarget && RadarHud.lockedEntity != null) {
            int keepId = RadarHud.lockedEntity.getId();
            RadarHud.echoes.entrySet().removeIf(e -> e.getValue().entityId != keepId);
            RadarHud.selectedTargetUuid = RadarHud.lockedEntity.getUUID();
            RadarHud.selectedEchoId = keepId;
            RadarHud.selectionInitialized = true;
        } else {
            RadarHud.echoes.clear();
            RadarHud.selectedTargetUuid = null;
            RadarHud.selectedEchoId = -1;
            RadarHud.selectionInitialized = false;
        }
    }

    /** 模式切换时解除当前模式不再允许的目标，不恢复到旧模式。 */
    public static void clearLockForModeChange() {
        clearLockState();
        lockMessage("模式切换解除锁定");
    }

    /** 只清理锁定相关状态，不恢复模式；供 ACM 进出使用。 */
    private static void clearLockState() {
        Entity target = RadarHud.lockedEntity;
        if (target != null) RadarHud.echoes.remove(target.getUUID());
        RadarHud.lockedEntity = null;
        RadarHud.lockedTargetUuid = null;
        RadarHud.lockedTargetSource = null;
        RadarHud.locked = false;
        RadarHud.lockAcquiring = false;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockProgress = 0f;
        RadarHud.lockAlpha = 0f;
        RadarHud.lockLostTicks = 0;
        RadarHud.lockAnchorPosition = Vec3.ZERO;
        RadarHud.seekerInertial = false;
        RadarHud.seekerModel.reset();
        RadarHud.seekerRing.resetToCenter(0, 0);
        lastSeekerLockId = -1;
    }

    private static void dropLock(String message) {
        boolean returnToAcm = RadarHud.acmActive
                && RadarHud.preLockMode == RadarMode.ACM;
        clearLockState();
        if (RadarHud.preLockMode != null) {
            RadarHud.currentMode = RadarHud.preLockMode;
        }
        if (returnToAcm) {
            RadarHud.acmCooldownTicks = 40;
        }
        lockMessage(message);
    }

    private static RadarContact findContact(Entity entity) {
        for (RadarContact c : RadarHud.contacts) {
            if (c.entity == entity) return c;
        }
        return null;
    }

    private static boolean echoAllowed(RadarEcho echo) {
        Entity entity = TwsTrackManager.entity(echo.entityId);
        if (entity == null) return false;
        RadarTargetClass targetClass = RadarCore.classify(entity);
        if (targetClass == null || !RadarHud.currentMode.accepts(targetClass)) return false;
        return RadarHud.currentMode != RadarMode.TWS
                || targetClass == RadarTargetClass.AIR;
    }

    /** Alt+T：只改变面板选中目标；不锁定、不触发屏幕动作。 */
    public static void selectNextTarget() {
        if (RadarHud.currentMode == RadarMode.STT) {
            lockMessage("TRK PD 为单目标跟踪，不能切换目标");
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrackManager.cycle();
            TwsTrack selected = TwsTrackManager.selected();
            if (selected == null) {
                TargetRegistry.clearSelection();
                lockMessage("无有效航迹");
                return;
            }
            RadarHud.selectedTargetUuid = selected.uuid;
            RadarHud.selectedEchoId = selected.entityId;
            RadarHud.selectionInitialized = true;
            lockMessage("切换面板目标");
            return;
        }

        TargetRegistry.selectNext(player, player.tickCount);
    }

    /** TWS 自动把扫描中心偏向当前唯一跟踪目标。 */
    private static void followTwsTarget(Player player, int tick) {
        TwsTrack track = TwsTrackManager.selected();
        if (track == null) return;
        Vec3 to = track.predictedPosition(tick).subtract(player.getEyePosition());
        if (to.lengthSqr() < 1e-6) return;
        Vec3 look = player.getViewVector(1.0F).normalize();
        double targetAz = Math.toDegrees(RadarCore.azimuthBetween(look, to));
        double targetEl = Math.toDegrees(RadarCore.elevationBetween(look, to));
        if (RadarHud.effectiveScanHalfDeg() >= RadarHud.PANEL_AZIMUTH_DEG - 0.01) {
            RadarHud.scanAzimuthOffsetDeg = 0;
        } else {
            float desiredAz = (float) Math.max(-30, Math.min(30, targetAz));
            RadarHud.scanAzimuthOffsetDeg += (desiredAz - RadarHud.scanAzimuthOffsetDeg) * 0.25;
        }
        float desiredEl = (float) Math.max(-60, Math.min(60, targetEl));
        RadarHud.scanElevationOffsetDeg += (desiredEl - RadarHud.scanElevationOffsetDeg) * 0.25;
    }

    /** STT/TRK PD 让扫描中心跟随硬锁目标，本地/BVR 都保持连续照射。 */
    private static void followLockedTarget(Player player, int tick) {
        TrackedTarget target = TargetRegistry.find(RadarHud.lockedTargetUuid, player, tick);
        if (target == null) return;
        Vec3 to = target.livePosition().subtract(player.getEyePosition());
        if (to.lengthSqr() < 1e-6) return;
        Vec3 look = player.getViewVector(1.0F).normalize();
        double targetAz = Math.toDegrees(RadarCore.azimuthBetween(look, to));
        double targetEl = Math.toDegrees(RadarCore.elevationBetween(look, to));
        if (RadarHud.effectiveScanHalfDeg() >= RadarHud.PANEL_AZIMUTH_DEG - 0.01) {
            RadarHud.scanAzimuthOffsetDeg = 0;
        } else {
            float desiredAz = (float) Math.max(-30, Math.min(30, targetAz));
            RadarHud.scanAzimuthOffsetDeg += (desiredAz - RadarHud.scanAzimuthOffsetDeg) * 0.3;
        }
        float desiredEl = (float) Math.max(-60, Math.min(60, targetEl));
        RadarHud.scanElevationOffsetDeg += (desiredEl - RadarHud.scanElevationOffsetDeg) * 0.3;
    }

    /** 主画面和雷达面板共用的横/竖扫描线状态。 */
    private static void updateScanState(Player player) {
        if (!RadarHud.radarOn) {
            RadarHud.scanStartTick = -1;
            scanRow = -1;
            lastSweepRow = -1;
            RadarHud.prevSweepAzimuthDeg = RadarHud.sweepAzimuthDeg;
            RadarHud.prevSweepElevationDeg = RadarHud.sweepElevationDeg;
            return;
        }
        if (RadarHud.isTrackingView()) {
            // TRK PD 不扫描：天线锁定目标方向，扫描线/扫描波束全部停用。
            lastSweepAzimuth = RadarHud.sweepAzimuthDeg;
            lastSweepRow = scanRow;
            RadarHud.prevSweepAzimuthDeg = RadarHud.sweepAzimuthDeg;
            RadarHud.prevSweepElevationDeg = RadarHud.sweepElevationDeg;
            RadarHud.scanStartTick = -1;
            scanRow = -1;
            lastSweepRow = -1;
            return;
        }
        if (RadarHud.scanStartTick < 0) {
            RadarHud.scanStartTick = player.tickCount;
        }
        int ticks = player.tickCount - RadarHud.scanStartTick;
        // 每行扫一遍后切换下一行；行数由扫描模式决定，行间方向交替。
        int period = RadarHud.isAcmContext() ? 5 : RadarHud.SCAN_ROW_TICKS;
        if (RadarHud.currentMode == RadarMode.ACM) {
            updateAcmBeamState(ticks, period);
            return;
        }
        int rows = RadarHud.effectiveScanRows();
        int step = ticks / period;
        int cycle = rows * 2 - 2;
        int p = step % cycle;
        int row = p < rows ? p : cycle - p;
        boolean forward = step % 2 == 0;
        float frac = (ticks % period) / (float) period;
        double half = RadarHud.effectiveScanHalfDeg();
        lastSweepAzimuth = RadarHud.sweepAzimuthDeg;
        lastSweepRow = scanRow;
        scanRow = row;
        RadarHud.prevSweepAzimuthDeg = RadarHud.sweepAzimuthDeg;
        RadarHud.prevSweepElevationDeg = RadarHud.sweepElevationDeg;
        RadarHud.sweepAzimuthDeg = (float) (RadarHud.scanAzimuthOffsetDeg
                + (forward ? -half + 2 * half * frac : half - 2 * half * frac));
        double elevationHalf = RadarHud.effectiveScanElevationHalfDeg();
        double elevationSpan = elevationHalf * 2.0;
        RadarHud.sweepElevationDeg = (float) (RadarHud.scanElevationOffsetDeg
                + elevationHalf
                - (row + 0.5) * elevationSpan / rows);
    }

    /**
     * BVR 远程航迹按普通雷达规则显示：只有当前扫描波束实际扫过其方位/仰角时
     * 建立/刷新涂抹快照；未扫到时面板保持冻结位置，超过回波记忆后消失。
     * 视线遮挡（本地可追踪目标）与 GMI 水平速度门与本地扫描同规。
     * ACM 只做视距内自动截获，不做超视距搜索（ACM 内不涂抹 BVR）。
     * 已锁定的 BVR 目标与本机导弹数据链例外，保持连续更新（同 TRK PD 规则）。
     */
    private static void updateBvrPaints(Minecraft mc, Player player,
                                        Vec3 origin, int nowTick) {
        if (RadarHud.currentMode == RadarMode.STT
                || RadarHud.currentMode == RadarMode.ACM) {
            return;
        }
        ScanWindow window;
        Vec3 beamDir;
        if (RadarHud.currentMode == RadarMode.ACM
                && RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_FIXED) {
            // 正前静止与本地 ACM 全框扫描一致：整框 ±3° 内直接涂抹。
            window = new ScanWindow(RadarHud.scanAzimuthOffsetDeg,
                    RadarHud.scanElevationOffsetDeg,
                    RadarHud.ACM_FIXED_HALF_DEG, RadarHud.ACM_FIXED_HALF_DEG);
            beamDir = scanDirection(player);
        } else if (RadarHud.currentMode == RadarMode.ACM) {
            window = acmScanWindow();
            beamDir = Vec3.directionFromRotation(
                    player.getXRot() - (float) window.elCenterDeg(),
                    player.getYRot() + (float) window.azCenterDeg()).normalize();
        } else {
            window = currentScanWindow();
            beamDir = Vec3.directionFromRotation(
                    player.getXRot() - (float) window.elCenterDeg(),
                    player.getYRot() + (float) window.azCenterDeg()).normalize();
        }
        double azHalf = Math.toRadians(window.azHalfDeg());
        double elHalf = Math.toRadians(window.elHalfDeg());
        double range = RadarHud.effectiveRange();
        for (BvrTrack track : BvrState.active()) {
            if (player.getUUID().equals(track.uuid())) continue;
            RadarTargetClass cls = switch (track.kind()) {
                case PLAYER, AI, MISSILE -> RadarTargetClass.AIR;
                case SURFACE -> RadarTargetClass.SEA;
            };
            if (!RadarHud.currentMode.accepts(cls)) continue;
            Vec3 pos = BvrState.lagCompensatedPosition(track);
            Vec3 to = pos.subtract(origin);
            double distance = to.length();
            if (distance <= 0.1 || distance > range) continue;
            if (RadarHud.currentMode == RadarMode.GMI
                    && track.velocity().horizontalDistance() * 20.0
                            < RadarCore.GMI_MIN_HORIZONTAL_SPEED) continue;
            Entity tracked = mc.level == null ? null : mc.level.getEntity(track.entityId());
            if (tracked != null
                    && RadarCore.isOccludedForTarget(mc.level, origin, pos,
                            RadarCore.classify(tracked))) {
                continue;
            }
            if (Math.abs(RadarCore.azimuthBetween(beamDir, to)) > azHalf
                    || Math.abs(RadarCore.elevationBetween(beamDir, to)) > elHalf) {
                continue;
            }
            // 面板坐标在涂抹时刻冻结（与本地回波同规）：方位相对当时视轴、右侧为正。
            Vec3 view = player.getViewVector(1.0F).normalize();
            Vec3 right = view.cross(new Vec3(0, 1, 0));
            if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
            right = right.normalize();
            float frozenAz = (float) (Math.toDegrees(
                    Math.atan2(to.dot(right), to.dot(view)))
                    + RadarHud.scanAzimuthOffsetDeg);
            BvrState.paint(track.uuid(), new BvrState.Paint(
                    pos, track.velocity(), frozenAz, distance, nowTick));
        }
    }

    /**
     * ACM 三波束模式：
     * 0 正前静止：波束钉死在机头方向；
     * 1 上下往复：波束沿 ±60° 俯仰带逐行扫（方位固定 0°）；
     * 2 3×3 光栅：波束在 ±9° 网格内按普通搜索逻辑逐行来回。
     */
    private static void updateAcmBeamState(int ticks, int period) {
        lastSweepAzimuth = RadarHud.sweepAzimuthDeg;
        lastSweepRow = scanRow;
        RadarHud.prevSweepAzimuthDeg = RadarHud.sweepAzimuthDeg;
        RadarHud.prevSweepElevationDeg = RadarHud.sweepElevationDeg;
        if (RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_FIXED) {
            scanRow = 0;
            RadarHud.sweepAzimuthDeg = (float) RadarHud.scanAzimuthOffsetDeg;
            RadarHud.sweepElevationDeg = (float) RadarHud.scanElevationOffsetDeg;
            return;
        }
        int rows = Math.max(2, RadarHud.effectiveScanRows());
        int step = ticks / Math.max(1, period);
        int cycle = rows * 2 - 2;
        int p = step % cycle;
        scanRow = p < rows ? p : cycle - p;
        boolean forward = step % 2 == 0;
        float frac = (ticks % Math.max(1, period)) / (float) Math.max(1, period);
        if (RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_VERTICAL) {
            double half = RadarHud.effectiveScanElevationHalfDeg();
            RadarHud.sweepAzimuthDeg = (float) RadarHud.scanAzimuthOffsetDeg;
            RadarHud.sweepElevationDeg = (float) (RadarHud.scanElevationOffsetDeg
                    + half - (scanRow + 0.5) * (half * 2.0 / rows));
        } else {
            double half = RadarHud.effectiveScanHalfDeg();
            RadarHud.sweepAzimuthDeg = (float) (RadarHud.scanAzimuthOffsetDeg
                    + (forward ? -half + 2 * half * frac : half - 2 * half * frac));
            double elHalf = RadarHud.effectiveScanElevationHalfDeg();
            RadarHud.sweepElevationDeg = (float) (RadarHud.scanElevationOffsetDeg
                    + elHalf - (scanRow + 0.5) * (elHalf * 2.0 / rows));
        }
    }

    /** ACM 模式 1/2 的当前波束窗口；模式 0 直接整框扫描。 */
    private static ScanWindow acmScanWindow() {
        if (RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_VERTICAL) {
            double rowHalf = RadarHud.effectiveScanElevationHalfDeg()
                    / Math.max(2, RadarHud.effectiveScanRows());
            return new ScanWindow(RadarHud.scanAzimuthOffsetDeg,
                    RadarHud.sweepElevationDeg,
                    RadarHud.ACM_VERTICAL_HALF_AZ_DEG,
                    Math.max(RadarHud.SCAN_BEAM_HALF_EL_DEG, rowHalf));
        }
        return new ScanWindow(RadarHud.sweepAzimuthDeg, RadarHud.sweepElevationDeg,
                RadarHud.SCAN_BEAM_HALF_AZ_DEG, RadarHud.ACM_RASTER_CELL_HALF_DEG);
    }

    /**
     * 当前 tick 的窄波束窗口：同一行内覆盖上一 tick 到当前 tick 扫过的方位段，
     * 换行时只扫当前方位，避免把上一行末尾和下一行开头连成一个大扇区。
     */
    private static ScanWindow currentScanWindow() {
        boolean rowChanged = lastSweepRow != scanRow;
        double sweepDelta = Math.abs(RadarHud.sweepAzimuthDeg - lastSweepAzimuth);
        if (!rowChanged && sweepDelta <= RadarHud.MAX_SWEEP_STEP_DEG) {
            return new ScanWindow(
                    (RadarHud.sweepAzimuthDeg + lastSweepAzimuth) * 0.5,
                    RadarHud.sweepElevationDeg,
                    sweepDelta * 0.5 + RadarHud.SCAN_BEAM_HALF_AZ_DEG,
                    RadarHud.SCAN_BEAM_HALF_EL_DEG);
        }
        return new ScanWindow(RadarHud.sweepAzimuthDeg, RadarHud.sweepElevationDeg,
                RadarHud.SCAN_BEAM_HALF_AZ_DEG, RadarHud.SCAN_BEAM_HALF_EL_DEG);
    }

    /** 扫描中心：玩家视线叠加方位/仰角偏移。 */
    private static Vec3 scanDirection(Player player) {
        return Vec3.directionFromRotation(
                player.getXRot() - (float) RadarHud.scanElevationOffsetDeg,
                player.getYRot() + (float) RadarHud.scanAzimuthOffsetDeg).normalize();
    }

    public static void recenterScan() {
        RadarHud.scanAzimuthOffsetDeg = 0;
        RadarHud.scanElevationOffsetDeg = 0;
    }

    private static boolean hasSeekerAmmo() {
        return hasAmmoFor(RadarHud.selectedMissile);
    }

    private static boolean hasAmmoFor(MissileSpec.Guidance guidance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        for (ItemStack stack : mc.player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof MissileFireworkItem firework
                    && firework.getSpec().guidance == guidance) {
                return true;
            }
        }
        for (ItemStack stack : mc.player.getInventory().offhand) {
            if (!stack.isEmpty() && stack.getItem() instanceof MissileFireworkItem firework
                    && firework.getSpec().guidance == guidance) {
                return true;
            }
        }
        return false;
    }

    /** 当前选中弹种打空后，自动切到背包里还有库存的弹种。 */
    private static boolean selectAvailableSeekerGuidance() {
        for (MissileSpec.Guidance guidance : new MissileSpec.Guidance[]{
                MissileSpec.Guidance.ARH, MissileSpec.Guidance.SARH, MissileSpec.Guidance.IR}) {
            if (!hasAmmoFor(guidance)) continue;
            RadarHud.selectedMissile = guidance;
            RadarHud.selectedSpec = switch (guidance) {
                case ARH -> MissileSpecs.ARH;
                case SARH -> MissileSpecs.SARH;
                case IR -> MissileSpecs.IR;
            };
            return true;
        }
        return false;
    }

    private static boolean hasRadarSeekerSource() {
        return RadarHud.selectedMissile == MissileSpec.Guidance.IR
                || RadarHud.hasActiveLockTarget()
                || (RadarHud.currentMode == RadarMode.TWS
                && TwsTrackManager.selected() != null);
    }

    public static void closeSeekerAfterLaunch() {
        disableSeeker("导引头：关闭");
    }

    private static void disableSeeker(String message) {
        RadarHud.seekerEnabled = false;
        RadarHud.seekerEnabledTicks = 0;
        RadarHud.seekerModel.reset();
        RadarHud.seekerRing.resetToCenter(0, 0);
        RadarHud.lockProgress = 0f;
        RadarHud.seekerMessage = message;
        RadarHud.seekerActive = false;
        RadarHud.irCandidate = null;
        RadarHud.irStatus = "";
        clearIrLock();
        lastSeekerLockId = -1;
    }

    public static void toggleSeeker() {
        if (!RadarHud.seekerEnabled) {
            // 打完当前弹种后，自动切到仍有库存的弹种，避免导引头无法重新开启。
            if (!hasSeekerAmmo() && !selectAvailableSeekerGuidance()) {
                RadarHud.seekerMessage = "无可用导弹";
                return;
            }
            if (!hasRadarSeekerSource()) {
                RadarHud.seekerMessage = "需要雷达锁定";
                return;
            }
        }
        RadarHud.seekerEnabled = !RadarHud.seekerEnabled;
        RadarHud.seekerMessage = RadarHud.seekerEnabled
                ? "导引头：开启" : "导引头：关闭";
        if (!RadarHud.seekerEnabled) {
            RadarHud.seekerEnabledTicks = 0;
            RadarHud.seekerModel.reset();
            RadarHud.seekerRing.resetToCenter(0, 0);
            lastSeekerLockId = -1;
            RadarHud.lockProgress = 0f;
        } else {
            RadarHud.seekerEnabledTicks = 0;
            RadarHud.seekerModel.reset();
            lastSeekerLockId = -1;
        }
    }

    private static void reset() {
        RwrState.clear();
        BvrState.clear();
        paintSentTicks.clear();
        RadarHud.contacts.clear();
        RadarHud.lockedEntity = null;
        RadarHud.locked = false;
        RadarHud.lockAcquiring = false;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockProgress = 0f;
        RadarHud.preLockMode = RadarMode.SRC_PD;
        RadarHud.preLockRangeIndex = RadarHud.rangeIndex;
        RadarHud.preLockScanPatternIndex = RadarHud.scanPatternIndex;
        RadarHud.preLockScanAzimuthOffsetDeg = 0;
        RadarHud.preLockScanElevationOffsetDeg = 0;
        RadarHud.lockAnchorPosition = Vec3.ZERO;
        RadarHud.seekerActive = false;
        RadarHud.seekerLockedEntity = null;
        clearIrLock();
        RadarHud.irStatus = "";
        RadarHud.seekerInertial = false;
        RadarHud.inertialAzimuthDeg = 0;
        RadarHud.inertialDistance = 0;
        RadarHud.inertialWorldPosition = Vec3.ZERO;
        RadarHud.inertialVelocity = Vec3.ZERO;
        RadarHud.guidanceLineActive = false;
        RadarHud.guidanceTargetId = -1;
        RadarHud.pendingGuidanceTargetId = -1;
        RadarHud.contactPaintTicks.clear();
        RadarHud.echoes.clear();
        TwsTrackManager.clear();
        lockMessage("");
        RadarHud.seekerMessage = "";
        RadarHud.selectedEchoId = -1;
        RadarHud.selectionInitialized = false;
        RadarHud.scanAzimuthOffsetDeg = 0;
        RadarHud.scanElevationOffsetDeg = 0;
        RadarHud.scanStartTick = -1;
        RadarHud.missileInFlight = false;
        RadarHud.acmActive = false;
        RadarHud.acmReturnMode = RadarMode.SRC_PD;
        RadarHud.acmCooldownTicks = 0;
        RadarHud.seekerEnabledTicks = 0;
        RadarHud.lockedTargetUuid = null;
        RadarHud.lockedTargetSource = null;
        TargetRegistry.clearSelection();
        RadarHud.seekerModel.reset();
        RadarHud.seekerRing.resetToCenter(0, 0);
        // 不在这里立刻发“解除锁定”：松手发射的同一 tick 可能还没被服务端处理。
        // 下次重新持弓时 syncRadarState 会补发清除包；服务端也有 5 秒过期。
        lastSeekerLockId = -1;
        lastSentTargetId = -2;
        lastSentSeekerState = -1;
        lastSentMode = -1;
        lastSentHardLock = false;
        lastSentRadarOn = true;
        lastSentTrackQuality = 1f;
        lastSentBvrUuid = null;
        lastSentSource = -1;
        lastSentTick = -100;
        lockTicks = 0;
        inertialTicks = 0;
    }
}
