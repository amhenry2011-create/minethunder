package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.IrTargeting;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.network.Network;
import com.minethunder.network.BvrLockPacket;
import com.minethunder.network.RadarSyncPacket;
import com.minethunder.network.RwrPaintPacket;
import com.minethunder.radar.BvrTrack;
import com.minethunder.radar.RadarContact;
import com.minethunder.radar.RadarMode;
import com.minethunder.radar.RadarTargetClass;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.ServerRadarState;
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
                    && RadarHud.locked && RadarHud.lockedEntity != null;
            if (RadarHud.lockedEntity != null && !keepLockedTarget) {
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
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof MissileArrow arrow) {
                if (arrow.getOwner() == player) {
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
            RadarHud.bvrAcquiring = false;
            RadarHud.bvrLockedUuid = null;
            RadarHud.bvrLockedName = "";
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
        boolean sttLocked = RadarHud.currentMode == RadarMode.STT
                && RadarHud.locked && RadarHud.lockedEntity != null;
        List<RadarContact> rawContacts;
        double contactOffsetAz;
        double contactOffsetEl;
        if (sttLocked) {
            // TRK PD 只跟踪锁定目标，不再跑通用扇区扫描。
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
                RadarHud.selectedEchoId = current.entityId;
                RadarHud.selectionInitialized = true;
            }
            followTwsTarget(player, nowTick);
        } else {
            TwsTrackManager.clear();
        }
        validatePanelSelection();
        if (RadarHud.currentMode == RadarMode.STT) {
            // TRK 只管当前锁定目标：清掉通用扫描产生的其它/地面回波。
            RadarHud.contacts.clear();
            RadarHud.contactPaintTicks.clear();
            Entity lock = RadarHud.lockedEntity;
            RadarHud.echoes.entrySet().removeIf(e -> lock == null
                    || !RadarHud.locked
                    || e.getValue().entityId != lock.getId());
        }
        if (RadarHud.currentMode == RadarMode.STT && RadarHud.locked
                && !RadarHud.lockAcquiring) {
            followLockedTarget(player, nowTick);
        }

        // ACM 格斗模式：自动截获机头前方最近目标；解锁后短暂冷却，避免瞬间复锁。
        if (RadarHud.acmActive && RadarHud.currentMode == RadarMode.ACM
                && RadarHud.lockedEntity == null && RadarHud.acmCooldownTicks <= 0) {
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

        // 手动锁定建立阶段
        if (RadarHud.lockAcquiring) {
            updateLockAcquisition(player, nowTick);
        }

        // BVR 远程锁定：建立/维护数据链软锁；本地硬锁优先。
        if (RadarHud.bvrAcquiring) {
            tickBvrAcquisition(player, nowTick);
        } else if (RadarHud.bvrLockedUuid != null) {
            maintainBvrLock(player, nowTick);
        }

        // TRK PD 是连续跟踪模式：直接读目标实时状态，不依赖扫描线命中。
        Entity locked = RadarHud.lockedEntity;
        if (locked != null && RadarHud.locked && !RadarHud.lockAcquiring) {
            updateContinuousTrack(player, locked, nowTick);
        }

        if (RadarHud.lockedEntity == null && !RadarHud.missileInFlight) {
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
        if (mc.getConnection() == null) return;
        // RWR/雷达状态与当前选择的弹种无关；IR 模式下仍要同步雷达跟踪/锁定。
        boolean hardLock = RadarHud.locked && RadarHud.lockedEntity != null;
        Entity target = null;
        if (hardLock) {
            target = RadarHud.lockedEntity;
        } else if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            if (track != null) target = TwsTrackManager.entity(track.entityId);
        }
        int targetId = target == null ? -1 : target.getId();
        int state = hardLock && RadarHud.seekerEnabled
                ? RadarHud.seekerModel.state().ordinal() : -1;
        int mode = RadarHud.currentMode.ordinal();
        float quality = 0f;
        if (target != null) {
            RadarEcho echo = RadarHud.echoes.get(target.getUUID());
            quality = echo == null ? 0.3f
                    : (float) Math.max(0.15, 1.0 - echo.notchSeverity * 0.65);
            if (hardLock) quality *= Math.max(0.15f, RadarHud.lockAlpha);
            quality = Math.max(0.05f, Math.min(1.0f, quality));
        }
        boolean changed = targetId != lastSentTargetId || state != lastSentSeekerState
                || mode != lastSentMode || hardLock != lastSentHardLock
                || RadarHud.radarOn != lastSentRadarOn
                || Math.abs(quality - lastSentTrackQuality) > 0.02f;
        boolean refresh = mc.player != null && mc.player.tickCount - lastSentTick >= 20;
        java.util.UUID bvr = RadarHud.bvrLockedUuid;
        boolean bvrChanged = !java.util.Objects.equals(bvr, lastSentBvrUuid);
        if (changed || refresh || bvrChanged) {
            Network.sendToServer(new RadarSyncPacket(targetId, state, mode, hardLock,
                    RadarHud.radarOn, quality));
            if (bvrChanged) {
                Network.sendToServer(new BvrLockPacket(bvr));
                lastSentBvrUuid = bvr;
            }
            lastSentTargetId = targetId;
            lastSentSeekerState = state;
            lastSentMode = mode;
            lastSentHardLock = hardLock;
            lastSentRadarOn = RadarHud.radarOn;
            lastSentTrackQuality = quality;
            lastSentTick = mc.player == null ? 0 : mc.player.tickCount;
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

        // 硬锁定 / TWS 软跟踪（本地实体）优先，BVR 远程 TRK 无本地实体，用数据链位置。
        Entity entityTarget = RadarHud.locked ? RadarHud.lockedEntity : null;
        TwsTrack twsTrack = entityTarget == null && RadarHud.currentMode == RadarMode.TWS
                ? TwsTrackManager.selected() : null;
        if (entityTarget == null && twsTrack != null) {
            entityTarget = TwsTrackManager.entity(twsTrack.entityId);
            if (entityTarget != null && !entityTarget.isAlive()) entityTarget = null;
        }
        BvrTrack bvrTrack = entityTarget == null && RadarHud.bvrLockedUuid != null
                ? BvrState.get(RadarHud.bvrLockedUuid) : null;

        int sourceId;
        Vec3 targetPos;
        double closure;
        if (entityTarget != null) {
            sourceId = entityTarget.getId();
            targetPos = entityTarget.getBoundingBox().getCenter();
            closure = RadarHud.locked ? RadarHud.lockedClosure
                    : (twsTrack == null ? 0.0 : twsTrack.closure);
        } else if (bvrTrack != null
                && !mc.player.getUUID().equals(bvrTrack.uuid())) {
            sourceId = -(int) Math.abs((long) bvrTrack.uuid().hashCode());
            targetPos = BvrState.lagCompensatedPosition(bvrTrack);
            closure = RadarHud.bvrLockedClosure;
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

        Entity radarAim = radarAssistTarget(player);

        Entity lockedTarget = RadarHud.irLocked ? RadarHud.seekerLockedEntity : null;
        if (lockedTarget == null && RadarHud.irLocked) {
            clearIrLock();
        }
        if (lockedTarget != null) {
            boolean sameAsRadar = radarAim == null
                    || radarAim.getId() == lockedTarget.getId();
            if (!sameAsRadar || !lockedTarget.isAlive()
                    || !IrTargeting.valid(mc.level, player, lockedTarget,
                    RadarHud.selectedSpec)) {
                clearIrLock();
                lockedTarget = null;
            }
        }

        Entity target = lockedTarget != null ? lockedTarget
                : (radarAim != null ? radarAim
                : IrTargeting.findTarget(mc.level, player, null,
                RadarHud.selectedSpec));
        RadarHud.irCandidate = target;

        Entity aimTarget = radarAim != null ? radarAim : lockedTarget;
        float ui = HudScale.factor(sw, sh);
        double smallRadius = Math.max(16, Math.round(RadarHud.IR_LOCK_RING_BASE * ui));

        if (aimTarget != null) {
            float[] aimScreen = RadarHudRenderer.worldToScreen(
                    aimTarget.getBoundingBox().getCenter(), player, sw, sh);
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
            if (radarAim == null) {
                RadarHud.irAimX = sw / 2.0f;
                RadarHud.irAimY = sh / 2.0f;
            }
            RadarHud.irStatus = radarAim != null ? "雷达辅助" : "搜索";
            return;
        }

        float[] targetScreen = RadarHudRenderer.worldToScreen(
                target.getBoundingBox().getCenter(), player, sw, sh);
        if (targetScreen == null) {
            clearIrLock();
            if (radarAim == null) {
                RadarHud.irAimX = sw / 2.0f;
                RadarHud.irAimY = sh / 2.0f;
            }
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

    @Nullable
    private static Entity radarAssistTarget(Player player) {
        if (RadarHud.locked && RadarHud.lockedEntity != null
                && RadarHud.lockedEntity.isAlive()) {
            return RadarHud.lockedEntity;
        }
        if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            Entity entity = track == null ? null : TwsTrackManager.entity(track.entityId);
            if (entity != null && entity.isAlive()) return entity;
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
        Entity target = RadarHud.lockedEntity;
        if (target == null && RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            target = track == null ? null : TwsTrackManager.entity(track.entityId);
        }
        RadarHud.guidanceLineActive = target != null
                || RadarHud.bvrLockedUuid != null;
        if (target != null) {
            RadarHud.guidanceTargetId = target.getId();
            RadarHud.guidanceLastWorldPosition = target.getBoundingBox().getCenter();
        } else {
            RadarHud.guidanceTargetId = -1;
            // BVR 锁定：目标不在客户端实体范围内，端点由 BVR 航迹维护。
            if (RadarHud.bvrLockedUuid != null) {
                BvrTrack bvr = BvrState.get(RadarHud.bvrLockedUuid);
                if (bvr != null) {
                    RadarHud.guidanceLastWorldPosition =
                            BvrState.lagCompensatedPosition(bvr);
                }
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
        double best = Double.MAX_VALUE;
        int id = -1;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof MissileArrow arrow)) continue;
            Entity owner = arrow.getOwner();
            if (owner != mc.player) continue;
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
        } else if (RadarHud.bvrLockedUuid != null) {
            BvrTrack bvr = BvrState.get(RadarHud.bvrLockedUuid);
            if (bvr != null) {
                pos = BvrState.lagCompensatedPosition(bvr);
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

    /** TRK PD 连续跟踪：逐 tick 从目标实体读取位置，不依赖扫描线。 */
    private static void updateContinuousTrack(Player player, Entity target, int tick) {
        Vec3 eye = player.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        Vec3 to = targetPos.subtract(eye);
        double range = to.length();
        Vec3 look = player.getViewVector(1.0F).normalize();
        double azimuth = Math.toDegrees(RadarCore.azimuthBetween(look, to));
        double elevation = Math.toDegrees(RadarCore.elevationBetween(look, to));
        RadarTargetClass targetClass = RadarCore.classify(target);
        boolean occluded = RadarCore.isOccludedForTarget(
                player.level(), eye, targetPos, targetClass);
        boolean valid = target.isAlive()
                && targetClass != null
                && !occluded
                && range <= RadarHud.effectiveRange()
                && Math.abs(azimuth) <= RadarHud.effectiveScanHalfDeg()
                && Math.abs(elevation) <= RadarHud.effectiveScanElevationHalfDeg();

        if (valid) {
            lockTicks = LOCK_HOLD_TICKS;
            inertialTicks = 0;
            RadarHud.lockLostTicks = 0;
            RadarHud.lockAlpha = 1f;
            RadarHud.seekerInertial = false;
            suppressLockInertial = false;
            updateLockInfo(player, target);

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
            return;
        }

        lockTicks--;
        inertialTicks++;
        RadarHud.lockLostTicks++;
        RadarHud.lockAlpha = Math.min(RadarHud.lockAlpha,
                Math.max(0f, 1f - RadarHud.lockLostTicks / (float) TRK_LOST_GRACE_TICKS));
        RadarHud.seekerInertial = false;
        if (RadarHud.lockLostTicks >= TRK_LOST_GRACE_TICKS) {
            dropLock("目标拖锁");
        }
    }

    /** 更新锁定目标信息（名称/距离/接近速度） */
    private static void updateLockInfo(Player player, Entity target) {
        var targetPos = target.getBoundingBox().getCenter();
        var eye = player.getEyePosition();
        RadarHud.lockedDistance = eye.distanceTo(targetPos);
        var rel = target.getDeltaMovement().subtract(player.getDeltaMovement()).scale(20.0);
        var dir = targetPos.subtract(eye);
        if (dir.lengthSqr() > 1e-6) {
            RadarHud.lockedClosure = -rel.dot(dir.normalize()); // 正 = 接近
        }
        RadarHud.lockedName = target.getName().getString();
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
        // ACM 是纯视距内自动截获：断开远程数据链软锁并丢弃涂抹记忆。
        if (RadarHud.bvrLockedUuid != null) {
            cancelBvrLock("ACM 截断远程锁定");
            Network.sendToServer(new BvrLockPacket((java.util.UUID) null));
        }
        BvrState.clearPaints();
        RadarHud.acmReturnMode = returnMode;
        RadarHud.acmActive = true;
        RadarHud.currentMode = RadarMode.ACM;
        RadarHud.scanAzimuthOffsetDeg = 0;
        RadarHud.scanElevationOffsetDeg = 0;
        RadarHud.acmCooldownTicks = 0;
        RadarHud.selectedEchoId = -1;
        RadarHud.selectionInitialized = false;
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
        RadarHud.selectedEchoId = -1;
        RadarHud.selectionInitialized = false;
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
        if (entity instanceof MissileArrow missile && missile.getOwner() == player) {
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

    /** 切换锁定：开始 TRK PD 尝试锁定，或解除已有锁定。 */
    public static void toggleLock() {
        if (RadarHud.lockAcquiring) {
            cancelLockAttempt("取消尝试锁定");
            return;
        }
        if (RadarHud.bvrAcquiring) {
            cancelBvrLock("取消远程尝试锁定");
            return;
        }
        if (RadarHud.locked) {
            dropLock("解除锁定");
            return;
        }
        if (RadarHud.bvrLockedUuid != null) {
            cancelBvrLock("解除 BVR 远程锁定");
            Network.sendToServer(new BvrLockPacket((java.util.UUID) null));
            return;
        }

        if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrack track = TwsTrackManager.selected();
            RadarEcho echo = track == null ? null : RadarHud.echoes.get(track.uuid);
            if (echo == null && RadarHud.selectedEchoId >= 0) {
                for (RadarEcho candidate : RadarHud.echoes.values()) {
                    if (candidate.entityId == RadarHud.selectedEchoId) {
                        echo = candidate;
                        break;
                    }
                }
            }
            Entity entity = echo == null ? null : TwsTrackManager.entity(echo.entityId);
            if (entity == null || !entity.isAlive() || echo == null) {
                beginBvrLockAcquisition();
                return;
            }
            beginLockAcquisition(entity, echo.azimuthDeg, echo.range, echo.position);
            return;
        }

        RadarEcho best = null;
        int now = Minecraft.getInstance().player == null
                ? 0 : Minecraft.getInstance().player.tickCount;
        int maxEchoAge = RadarHud.scanFrameTicks() + 20;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(now) || !echoAllowed(echo)) continue;
            // 手动尝试锁定必须基于当前扫描周期内实际扫描到的回波，而不是旧记忆。
            if (now - echo.lastHitTick > maxEchoAge) continue;
            if (echo.entityId == RadarHud.selectedEchoId) {
                best = echo;
                break;
            }
            if (best == null || echo.range < best.range) best = echo;
        }
        if (best == null) {
            beginBvrLockAcquisition();
            return;
        }
        Entity entity = TwsTrackManager.entity(best.entityId);
        if (entity == null || !entity.isAlive()) {
            // 本地回波实体失效时继续尝试 BVR 远程锁定。
            beginBvrLockAcquisition();
            return;
        }
        beginLockAcquisition(entity, best.azimuthDeg, best.range, best.position);
    }

    /** 写入锁定反馈并打时间戳，供消息行按新鲜度渲染。 */
    private static void lockMessage(String text) {
        RadarHud.lastLockMessage = text;
        RadarHud.lastLockMessageAt = System.currentTimeMillis();
    }

    /**
     * 无本地回波时回退：对 BVR 远程航迹发起雷达数据链软锁。
     * 雷达锁定属雷达功能，不区分弹种（发射时才按弹种校验）；
     * 仅空中目标、16..1000 格（与服务端校验一致）；
     * 候选必须已被扫描波束涂抹过（BvrState.painted），与普通雷达锁定同规。
     */
    private static boolean beginBvrLockAcquisition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (!RadarHud.radarOn) {
            lockMessage("雷达未开启");
            return false;
        }
        if (RadarHud.acmActive || RadarHud.currentMode == RadarMode.ACM) {
            // ACM 是纯近距自动截获，不提供远程锁定。
            lockMessage("无有效回波");
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        // 与本地手动锁定同款新鲜度：只认本扫描周期内被波束扫中的涂抹。
        int maxPaintAge = RadarHud.scanFrameTicks() + 20;
        int now = mc.player.tickCount;
        BvrTrack best = null;
        BvrState.Paint bestPaint = null;
        double bestScore = Double.MAX_VALUE;
        for (BvrTrack track : BvrState.active()) {
            if (!track.kind().isAir()) continue;
            if (track.uuid().equals(mc.player.getUUID())) continue;
            BvrState.Paint paint = BvrState.painted(track.uuid());
            if (paint == null || now - paint.lastPaintTick() > maxPaintAge) continue;
            Vec3 predicted = BvrState.lagCompensatedPosition(track);
            double distance = eye.distanceTo(predicted);
            if (distance < ServerRadarState.BVR_LOCK_MIN_RANGE
                    || distance > ServerRadarState.BVR_LOCK_RANGE) continue;
            double az = Math.toDegrees(RadarCore.azimuthBetween(look,
                    predicted.subtract(eye)));
            double azScore = Math.abs(az - RadarHud.scanAzimuthOffsetDeg);
            double score = (azScore <= 60.0 ? azScore : 1000.0 + azScore) + distance * 0.001;
            if (score < bestScore) {
                bestScore = score;
                best = track;
                bestPaint = paint;
            }
        }
        if (best == null) {
            lockMessage("无具备扫描记忆的远程目标");
            return false;
        }
        RadarHud.bvrAcquiring = true;
        RadarHud.bvrAcquireTicks = 0;
        RadarHud.bvrLockedUuid = best.uuid();
        RadarHud.bvrLockedName = best.name();
        // ACQ 阶段四角框锚定在被扫到的涂抹位置，与本地 ACQ 视觉一致。
        RadarHud.lockAnchorPosition = bestPaint.position();
        updateBvrInfo(mc.player, best);
        lockMessage("ACQ PD 远程尝试锁定");
        return true;
    }

    private static void tickBvrAcquisition(Player player, int tick) {
        BvrTrack track = RadarHud.bvrLockedUuid == null
                ? null : BvrState.get(RadarHud.bvrLockedUuid);
        if (!RadarHud.radarOn || track == null || !track.kind().isAir()
                || player.getUUID().equals(track.uuid())) {
            cancelBvrLock("尝试锁定失败");
            return;
        }
        if (RadarHud.locked || RadarHud.lockAcquiring) {
            // 本地扫描锁定优先，静默放弃远程软锁。
            cancelBvrLock(null);
            return;
        }
        if (RadarHud.bvrAcquiring) {
            // 与本地 ACQ 同规：尝试期间目标必须被扫描波束持续刷新。
            BvrState.Paint paint = BvrState.painted(track.uuid());
            int freshness = RadarHud.scanFrameTicks()
                    + RadarHud.LOCK_ACQUIRE_TICKS + 20;
            if (paint == null || tick - paint.lastPaintTick() > freshness) {
                cancelBvrLock("远程尝试锁定失败");
                return;
            }
        }
        updateBvrInfo(player, track);
        RadarHud.bvrAcquireTicks++;
        if (RadarHud.bvrAcquireTicks >= RadarHud.LOCK_ACQUIRE_TICKS) {
            RadarHud.bvrAcquiring = false;
            Network.sendToServer(new BvrLockPacket(RadarHud.bvrLockedUuid));
            lockMessage("TRK PD 远程锁定");
        }
    }

    private static void maintainBvrLock(Player player, int tick) {
        BvrTrack track = RadarHud.bvrLockedUuid == null
                ? null : BvrState.get(RadarHud.bvrLockedUuid);
        if (!RadarHud.radarOn || RadarHud.locked || RadarHud.lockAcquiring
                || track == null || !track.kind().isAir()) {
            Network.sendToServer(new BvrLockPacket((java.util.UUID) null));
            cancelBvrLock("BVR 远程失锁");
            return;
        }
        updateBvrInfo(player, track);
    }

    private static void updateBvrInfo(Player player, BvrTrack track) {
        Vec3 predicted = BvrState.lagCompensatedPosition(track);
        double distance = player.getEyePosition().distanceTo(predicted);
        Vec3 velocity = track.velocity();
        Vec3 to = predicted.subtract(player.getEyePosition()).normalize();
        RadarHud.bvrLockedDistance = distance;
        RadarHud.bvrLockedClosure = -velocity.dot(to);
        // 涂抹年龄：锁定/尝试锁定的目标如被波束持续刷新则读数恒小，
        // 增大表示该航迹正在依赖 180 tick 涂抹记忆维持显示。
        BvrState.Paint paint = BvrState.painted(track.uuid());
        RadarHud.bvrLockedPaintAge = paint == null ? -1
                : player.tickCount - paint.lastPaintTick();
    }

    private static void cancelBvrLock(@Nullable String message) {
        RadarHud.bvrAcquiring = false;
        RadarHud.bvrAcquireTicks = 0;
        RadarHud.bvrLockedUuid = null;
        RadarHud.bvrLockedName = "";
        RadarHud.bvrLockedDistance = 0;
        RadarHud.bvrLockedClosure = 0;
        if (message != null) lockMessage(message);
    }

    private static void beginLockAcquisition(Entity entity, double az, double range, Vec3 anchor) {
        if (!lockAllowed(entity)) {
            lockMessage("地面/海面目标需在 GMI/SEA 中锁定");
            return;
        }
        RadarHud.preLockMode = RadarHud.currentMode == RadarMode.STT
                ? RadarHud.preLockMode : RadarHud.currentMode;
        RadarHud.preLockRangeIndex = RadarHud.rangeIndex;
        RadarHud.preLockScanPatternIndex = RadarHud.scanPatternIndex;
        RadarHud.preLockScanAzimuthOffsetDeg = RadarHud.scanAzimuthOffsetDeg;
        RadarHud.preLockScanElevationOffsetDeg = RadarHud.scanElevationOffsetDeg;
        RadarHud.lockAnchorPosition = anchor;
        RadarHud.lockedEntity = entity;
        RadarHud.locked = false;
        RadarHud.lockAcquiring = true;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockAnchorAzimuthDeg = az;
        RadarHud.lockAnchorRange = range;
        RadarHud.lockProgress = 0f;
        RadarHud.lockAlpha = 1f;
        RadarHud.lockLostTicks = 0;
        RadarHud.seekerModel.reset();
        lastSeekerLockId = -1;
        lockMessage("ACQ PD 尝试锁定");
    }

    private static void updateLockAcquisition(Player player, int tick) {
        Entity target = RadarHud.lockedEntity;
        if (target == null || !target.isAlive() || !lockAllowed(target)) {
            cancelLockAttempt("尝试锁定失败");
            return;
        }
        RadarEcho echo = RadarHud.echoes.get(target.getUUID());
        if (echo == null || echo.expired(tick)) {
            cancelLockAttempt("尝试锁定失败");
            return;
        }
        if (RadarHud.preLockMode != RadarMode.TWS) {
            Vec3 eye = player.getEyePosition();
            Vec3 targetPos = target.getBoundingBox().getCenter();
            RadarTargetClass targetClass = RadarCore.classify(target);
            if (eye.distanceTo(targetPos) > RadarHud.effectiveRange()
                    || targetClass == null
                    || RadarCore.isOccludedForTarget(player.level(), eye, targetPos, targetClass)) {
                cancelLockAttempt("尝试锁定失败");
                return;
            }
        }
        // 目标必须在上一轮扫描周期内被实际命中，并给 20 tick 锁定建立留出时间；
        // 按波束逐行扫描后不再要求本 tick 仍在 contacts 里。TWS 使用已建立软航迹。
        if (RadarHud.preLockMode != RadarMode.TWS
                && tick - echo.lastHitTick
                > RadarHud.scanFrameTicks() + RadarHud.LOCK_ACQUIRE_TICKS + 20) {
            cancelLockAttempt("尝试锁定失败");
            return;
        }
        RadarHud.lockAcquireTicks++;
        RadarHud.lockProgress = Math.min(1f,
                RadarHud.lockAcquireTicks / (float) RadarHud.LOCK_ACQUIRE_TICKS);
        if (RadarHud.lockAcquireTicks >= RadarHud.LOCK_ACQUIRE_TICKS) {
            completeLock(target);
            updateLockInfo(player, target);
        }
    }

    private static void completeLock(Entity entity) {
        if (entity == null || !lockAllowed(entity)) return;
        if (RadarHud.preLockMode == null) RadarHud.preLockMode = RadarHud.currentMode;
        // ACM 自动截获可能尚未经过扫描线，补齐一份回波，避免下一 tick 立刻失锁。
        RadarContact contact = findContact(entity);
        if (contact != null) {
            int tick = Minecraft.getInstance().player == null
                    ? 0 : Minecraft.getInstance().player.tickCount;
            RadarEcho echo = RadarHud.echoes.get(entity.getUUID());
            if (echo == null) {
                RadarHud.echoes.put(entity.getUUID(), new RadarEcho(contact, tick));
            } else {
                echo.update(contact, tick);
            }
        }
        RadarHud.lockedEntity = entity;
        RadarHud.lockAcquiring = false;
        RadarHud.locked = true;
        RadarHud.currentMode = RadarMode.STT;
        RadarHud.lockProgress = 1f;
        RadarHud.lockAlpha = 1f;
        RadarHud.lockLostTicks = 0;
        RadarHud.seekerModel.reset();
        lastSeekerLockId = -1;
        lockTicks = LOCK_HOLD_TICKS;
        lockMessage("TRK PD 锁定");
    }

    private static void cancelLockAttempt(String message) {
        RadarHud.lockAcquiring = false;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockedEntity = null;
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
        if (keepLockedTarget) {
            int keepId = RadarHud.lockedEntity.getId();
            RadarHud.echoes.entrySet().removeIf(e -> e.getValue().entityId != keepId);
            RadarHud.selectedEchoId = keepId;
            RadarHud.selectionInitialized = true;
        } else {
            RadarHud.echoes.clear();
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
        RadarHud.locked = false;
        RadarHud.lockAcquiring = false;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockProgress = 0f;
        RadarHud.lockAlpha = 0f;
        RadarHud.lockLostTicks = 0;
        RadarHud.lockAnchorPosition = Vec3.ZERO;
        RadarHud.bvrAcquiring = false;
        RadarHud.bvrAcquireTicks = 0;
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

        if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrackManager.cycle();
            TwsTrack selected = TwsTrackManager.selected();
            RadarHud.selectedEchoId = selected == null ? -1 : selected.entityId;
            RadarHud.selectionInitialized = selected != null;
            lockMessage(selected == null ? "无有效航迹" : "切换面板目标");
            return;
        }

        List<RadarEcho> valid = new ArrayList<>();
        int tick = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.tickCount;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (!echo.expired(tick) && echoAllowed(echo)) valid.add(echo);
        }
        valid.sort(java.util.Comparator.comparingDouble(e -> e.range));
        if (valid.isEmpty()) {
            RadarHud.selectedEchoId = -1;
            lockMessage("无有效回波");
            return;
        }
        int index = -1;
        for (int i = 0; i < valid.size(); i++) {
            if (valid.get(i).entityId == RadarHud.selectedEchoId) {
                index = i;
                break;
            }
        }
        RadarEcho next = valid.get((index + 1) % valid.size());
        RadarHud.selectedEchoId = next.entityId;
        RadarHud.selectionInitialized = true;
        lockMessage("切换面板目标");
    }

    /** 面板选择只由 Alt+T/扫描首次回波维护，不因视角移动自动换目标。 */
    private static void validatePanelSelection() {
        if (RadarHud.selectedEchoId < 0) {
            if (!RadarHud.selectionInitialized && !RadarHud.echoes.isEmpty()) {
                RadarEcho first = null;
                for (RadarEcho echo : RadarHud.echoes.values()) {
                    if (!echoAllowed(echo)) continue;
                    if (first == null || echo.range < first.range) first = echo;
                }
                if (first != null) {
                    RadarHud.selectedEchoId = first.entityId;
                    RadarHud.selectionInitialized = true;
                }
            }
            return;
        }
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.entityId == RadarHud.selectedEchoId) {
                if (echoAllowed(echo)) return;
                break;
            }
        }
        RadarHud.selectedEchoId = -1;
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

    /** STT/TRK PD 让扫描中心跟随硬锁目标，保持连续照射。 */
    private static void followLockedTarget(Player player, int tick) {
        Entity target = RadarHud.lockedEntity;
        if (target == null) return;
        Vec3 to = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
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
                || (RadarHud.locked && RadarHud.lockedEntity != null)
                || RadarHud.bvrLockedUuid != null
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
        RadarHud.bvrAcquiring = false;
        RadarHud.bvrAcquireTicks = 0;
        RadarHud.bvrLockedUuid = null;
        RadarHud.bvrLockedName = "";
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
        lastSentTick = -100;
        lockTicks = 0;
        inertialTicks = 0;
    }
}
