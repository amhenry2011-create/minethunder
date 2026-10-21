package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.IrTargeting;
import com.minethunder.missile.MissileItem;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.network.Network;
import com.minethunder.network.RadarSyncPacket;
import com.minethunder.network.RwrPaintPacket;
import com.minethunder.radar.RadarContact;
import com.minethunder.radar.RadarMode;
import com.minethunder.radar.RadarTargetClass;
import com.minethunder.radar.RadarCore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
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
    private static RadarMode lastMode = RadarMode.SRC_PD;
    /** 当前扫描行与上一 tick 的扫描线位置，用于按线段交叉点亮回波。 */
    private static int scanRow = -1;
    private static int lastSweepRow = -1;
    private static double lastSweepAzimuth = 0;
    /** 限制同一目标的 SRC PD 扫描命中上报频率。 */
    private static final java.util.Map<Integer, Integer> paintSentTicks = new java.util.HashMap<>();

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
        updateClutter(mc, player);

        boolean anyMissileInFlight = false;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof MissileArrow arrow) {
                if (arrow.getOwner() == player) {
                    anyMissileInFlight = true;
                }
            }
        }
        RadarHud.missileInFlight = anyMissileInFlight;
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
            RadarHud.lockProgress = 0f;
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

        // 雷达扫描：扫描中心可随方向键偏移。回波角度统一为相对玩家视线的绝对角度。
        Vec3 origin = player.getEyePosition();
        Vec3 look = scanDirection(player);
        var rawContacts = RadarCore.scan(mc.level, origin, look, RadarHud.currentMode,
                RadarHud.effectiveRange(), Math.toRadians(RadarHud.effectiveScanHalfDeg()),
                Math.toRadians(RadarHud.effectiveScanElevationHalfDeg()),
                player.getDeltaMovement(), player);
        int nowTick = player.tickCount;
        List<RadarContact> contacts = new ArrayList<>();
        for (RadarContact c : rawContacts) {
            // TWS 只搜索空中目标，地面/海面/方块目标不得进入 TWS 航迹。
            if (RadarHud.currentMode == RadarMode.TWS
                    && c.targetClass != RadarTargetClass.AIR) continue;
            contacts.add(new RadarContact(c.entity, c.distance,
                    c.azimuthDeg + RadarHud.scanAzimuthOffsetDeg,
                    c.elevationDeg + RadarHud.scanElevationOffsetDeg,
                    c.radialVelocity, c.position, c.occluded, c.inDopplerNotch,
                    c.notchSeverity, c.targetClass));
        }
        RadarHud.contacts.clear();
        RadarHud.contacts.addAll(contacts);
        RadarHud.echoes.entrySet().removeIf(e -> nowTick - e.getValue().lastHitTick > RadarHud.CONTACT_MEMORY_TICKS);
        RadarHud.contactPaintTicks.entrySet().removeIf(e -> nowTick - e.getValue() > RadarHud.CONTACT_MEMORY_TICKS);
        for (RadarContact c : contacts) {
            // 扫描线命中：同一扫描行内用上一 tick 到当前 tick 的线段交叉判定，避免高速扫描漏目标。
            boolean azHit;
            if (lastSweepRow == scanRow) {
                double lo = Math.min(lastSweepAzimuth, RadarHud.sweepAzimuthDeg);
                double hi = Math.max(lastSweepAzimuth, RadarHud.sweepAzimuthDeg);
                azHit = c.azimuthDeg >= lo - 3.0 && c.azimuthDeg <= hi + 3.0;
            } else {
                azHit = Math.abs(c.azimuthDeg - RadarHud.sweepAzimuthDeg) <= 3.0;
            }
            boolean hit = azHit
                    && Math.abs(c.elevationDeg - RadarHud.sweepElevationDeg) <= 4.5;
            if (hit) {
                // 39 只降低亮度/质量，不直接删除回波。
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
        }
        if (RadarHud.currentMode == RadarMode.TWS) {
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
            RadarHud.clutterCells.clear();
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
                        || Math.abs(c.elevationDeg - RadarHud.scanElevationOffsetDeg) > 10.0) continue;
                if (c.distance < bestDist) {
                    bestDist = c.distance;
                    best = c;
                }
            }
            if (best != null) {
                RadarHud.preLockMode = RadarHud.currentMode;
                completeLock(best.entity);
                RadarHud.lastLockMessage = "ACM 自动截获 / TRK PD";
            }
        }

        // 手动锁定建立阶段
        if (RadarHud.lockAcquiring) {
            updateLockAcquisition(player, nowTick);
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
            quality = echo == null ? 0.65f
                    : (float) Math.max(0.15, 1.0 - echo.notchSeverity * 0.65);
            if (hardLock) quality *= Math.max(0.15f, RadarHud.lockAlpha);
            if (RadarHud.currentMode == RadarMode.TWS) {
                TwsTrack track = TwsTrackManager.selected();
                if (track != null && track.confirmed) quality = Math.max(quality, 0.7f);
            }
            quality = Math.max(0.05f, Math.min(1.0f, quality));
        }
        boolean changed = targetId != lastSentTargetId || state != lastSentSeekerState
                || mode != lastSentMode || hardLock != lastSentHardLock
                || RadarHud.radarOn != lastSentRadarOn
                || Math.abs(quality - lastSentTrackQuality) > 0.02f;
        boolean refresh = mc.player != null && mc.player.tickCount - lastSentTick >= 20;
        if (changed || refresh) {
            Network.sendToServer(new RadarSyncPacket(targetId, state, mode, hardLock,
                    RadarHud.radarOn, quality));
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
     * 雷达弹导引头：中间环从屏幕中心抛向锁定目标；环未对准前不建立稳定跟踪。
     * 稳定环状态由 SeekerLockModel 计算，HUD 直接读取。
     */
    private static void updateRadarSeeker(Minecraft mc, Player player) {
        boolean radarGuided = RadarHud.selectedMissile == MissileSpec.Guidance.SARH
                || RadarHud.selectedMissile == MissileSpec.Guidance.ARH;
        Entity target = RadarHud.lockedEntity;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        if (!radarGuided || !RadarHud.seekerEnabled || target == null
                || !RadarHud.locked) {
            if (lastSeekerLockId != -1) {
                RadarHud.seekerRing.resetToCenter(sw / 2.0, sh / 2.0);
                RadarHud.seekerModel.reset();
                RadarHud.lockProgress = 0f;
                lastSeekerLockId = -1;
            }
            return;
        }

        int targetId = target.getId();
        if (targetId != lastSeekerLockId) {
            double hold = RadarHud.selectedMissile == MissileSpec.Guidance.ARH ? 0.5 : 1.0;
            RadarHud.seekerRing.resetToCenter(sw / 2.0, sh / 2.0, hold);
            RadarHud.seekerModel.reset();
            RadarHud.lockProgress = 0f;
            lastSeekerLockId = targetId;
        }

        Vec3 targetPos = target.getBoundingBox().getCenter();
        Vec3 eye = player.getEyePosition();
        Vec3 to = targetPos.subtract(eye);
        double range = to.length();
        Vec3 look = player.getViewVector(1.0F).normalize();
        double offBoresight = Math.toDegrees(Math.acos(
                clamp(look.dot(to.normalize()), -1.0, 1.0)));

        float[] p = RadarHudRenderer.worldToScreen(targetPos, player, sw, sh);
        double ringX = p == null ? sw / 2.0 : clamp(p[0], 24.0, sw - 24.0);
        double ringY = p == null ? sh / 2.0 : clamp(p[1], 24.0, sh - 24.0);
        RadarHud.seekerRing.update(ringX, ringY, 0.05);

        boolean aligned = RadarHud.seekerRing.aligned();
        RadarHud.seekerModel.update(0.05, true, aligned, offBoresight, range,
                RadarHud.lockedClosure, RadarHud.selectedSpec);
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

        Entity radarLock = RadarHud.locked && RadarHud.lockedEntity != null
                ? RadarHud.lockedEntity : null;
        if (radarLock != null && !IrTargeting.valid(mc.level, player, radarLock,
                RadarHud.selectedSpec)) {
            radarLock = null;
        }

        Entity lockedTarget = RadarHud.irLocked ? RadarHud.seekerLockedEntity : null;
        if (lockedTarget == null && RadarHud.irLocked) {
            clearIrLock();
        }
        if (lockedTarget != null) {
            boolean sameAsRadar = radarLock == null
                    || radarLock.getId() == lockedTarget.getId();
            if (!sameAsRadar || !lockedTarget.isAlive()
                    || !IrTargeting.valid(mc.level, player, lockedTarget,
                    RadarHud.selectedSpec)) {
                clearIrLock();
                lockedTarget = null;
            }
        }

        Entity target = lockedTarget != null ? lockedTarget
                : (radarLock != null ? radarLock
                : IrTargeting.findTarget(mc.level, player, null,
                RadarHud.selectedSpec));
        RadarHud.irCandidate = target;

        Entity aimTarget = radarLock != null ? radarLock : lockedTarget;
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
            if (radarLock == null) {
                RadarHud.irAimX = sw / 2.0f;
                RadarHud.irAimY = sh / 2.0f;
            }
            RadarHud.irStatus = radarLock != null ? "截获" : "搜索";
            return;
        }

        float[] targetScreen = RadarHudRenderer.worldToScreen(
                target.getBoundingBox().getCenter(), player, sw, sh);
        if (targetScreen == null) {
            clearIrLock();
            if (radarLock == null) {
                RadarHud.irAimX = sw / 2.0f;
                RadarHud.irAimY = sh / 2.0f;
            }
            RadarHud.irStatus = radarLock != null ? "截获" : "搜索";
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
        if (targetDistance <= smallRadius) {
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
            RadarHud.irStatus = "截获";
        }
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
        if (target == null) {
            RadarHud.guidanceLineActive = false;
            return;
        }
        RadarHud.guidanceLineActive = true;
        RadarHud.guidanceTargetId = target.getId();
        RadarHud.guidanceLastWorldPosition = target.getBoundingBox().getCenter();
        RadarHud.guidancePitbullRange = RadarHud.selectedSpec == null
                ? 0 : RadarHud.selectedSpec.seekerActivationRange;
        RadarHud.guidanceIsArh = RadarHud.selectedMissile == MissileSpec.Guidance.ARH;
        RadarHud.guidanceMissileId = findOwnMissileId();
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
        return id;
    }

    /** 引导线终点：有雷达数据时跟目标，失锁后跟惯性预测点。 */
    private static void updateGuidanceLine(Minecraft mc, Player player) {
        if (!RadarHud.guidanceLineActive) return;
        if (!RadarHud.missileInFlight) {
            RadarHud.guidanceLineActive = false;
            RadarHud.guidanceMissileId = -1;
            return;
        }
        Entity missile = RadarHud.guidanceMissileId >= 0
                ? mc.level.getEntity(RadarHud.guidanceMissileId) : null;
        if (missile != null) {
            Vec3 toMissile = missile.getBoundingBox().getCenter().subtract(player.getEyePosition());
            Vec3 view = player.getViewVector(1.0F).normalize();
            Vec3 right = view.cross(new Vec3(0, 1, 0));
            if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
            right = right.normalize();
            RadarHud.guidanceMissileDistance = toMissile.length();
            RadarHud.guidanceMissileAzimuthDeg = Math.toDegrees(
                    Math.atan2(toMissile.dot(right), toMissile.dot(view)));
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

            Vec3 relativeVelocity = target.getDeltaMovement().subtract(player.getDeltaMovement());
            double radialVelocity = to.lengthSqr() < 1.0E-6 ? 0.0
                    : relativeVelocity.dot(to.normalize());
            RadarContact contact = new RadarContact(target, range, azimuth, elevation,
                    radialVelocity, targetPos, false, false, 0.0, targetClass);
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
        var rel = target.getDeltaMovement().subtract(player.getDeltaMovement());
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
        if (RadarHud.locked) {
            dropLock("解除锁定");
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
            if (entity == null || !entity.isAlive()) {
                RadarHud.lastLockMessage = "无有效回波";
                return;
            }
            if (echo == null) {
                RadarHud.lastLockMessage = "无有效回波";
                return;
            }
            beginLockAcquisition(entity, echo.azimuthDeg, echo.range, echo.position);
            return;
        }

        RadarEcho best = null;
        int now = Minecraft.getInstance().player == null
                ? 0 : Minecraft.getInstance().player.tickCount;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(now)) continue;
            // 手动尝试锁定必须基于最近实际扫描到的回波，而不是 9 秒内的旧记忆。
            if (now - echo.lastHitTick > 40) continue;
            if (echo.entityId == RadarHud.selectedEchoId) {
                best = echo;
                break;
            }
            if (best == null || echo.range < best.range) best = echo;
        }
        if (best == null) {
            RadarHud.lastLockMessage = "无有效回波";
            return;
        }
        Entity entity = TwsTrackManager.entity(best.entityId);
        if (entity == null || !entity.isAlive()) {
            RadarHud.lastLockMessage = "无有效回波";
            return;
        }
        beginLockAcquisition(entity, best.azimuthDeg, best.range, best.position);
    }

    private static void beginLockAcquisition(Entity entity, double az, double range, Vec3 anchor) {
        if (!lockAllowed(entity)) {
            RadarHud.lastLockMessage = "地面/海面目标需在 GMI/SEA 中锁定";
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
        RadarHud.lastLockMessage = "ACQ PD 尝试锁定";
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
        // 普通搜索尝试期间目标必须仍在当前扫描体积内；TWS 使用已建立软航迹，不额外要求扫描命中。
        if (RadarHud.preLockMode != RadarMode.TWS && findContact(target) == null) {
            cancelLockAttempt("尝试锁定失败");
            return;
        }
        // 普通搜索必须在最近 2 秒内实际被扫描到。
        if (RadarHud.preLockMode != RadarMode.TWS && tick - echo.lastHitTick > 40) {
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
        RadarHud.lastLockMessage = "TRK PD 锁定";
    }

    private static void cancelLockAttempt(String message) {
        RadarHud.lockAcquiring = false;
        RadarHud.lockAcquireTicks = 0;
        RadarHud.lockedEntity = null;
        RadarHud.locked = false;
        RadarHud.lockProgress = 0f;
        RadarHud.lockAlpha = 0f;
        restorePreLockSearch();
        RadarHud.lastLockMessage = message;
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

    /** 只清理锁定相关状态，不恢复模式；供 ACM 进出使用。 */
    /** 切换模式时清空上一模式的面板接触、杂波、选中目标等旧绘制。 */
    private static void clearRadarPanelForModeChange(boolean keepLockedTarget) {
        RadarHud.contacts.clear();
        RadarHud.contactPaintTicks.clear();
        RadarHud.clutterCells.clear();
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
        RadarHud.lastLockMessage = "模式切换解除锁定";
    }

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
        RadarHud.lastLockMessage = message;
    }

    /** 从当前扫描回波中开始一次近似位置锁定。 */
    private static void lockNearestContact() {
        RadarEcho best = null;
        int tick = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.tickCount;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(tick)) continue;
            if (best == null || echo.range < best.range) best = echo;
        }
        if (best == null) {
            RadarHud.lastLockMessage = "无有效回波";
            return;
        }
        Entity entity = TwsTrackManager.entity(best.entityId);
        if (entity != null && entity.isAlive()) {
            beginLockAcquisition(entity, best.azimuthDeg, best.range, best.position);
        } else {
            RadarHud.lastLockMessage = "无有效回波";
        }
    }

    /** 兼容旧调用：开始尝试锁定，而不是直接硬锁。 */
    private static void lockContact(RadarContact contact) {
        beginLockAcquisition(contact.entity, contact.azimuthDeg, contact.distance, contact.position);
    }

    private static RadarContact findContact(Entity entity) {
        for (RadarContact c : RadarHud.contacts) {
            if (c.entity == entity) return c;
        }
        return null;
    }

    /** Alt+T：只改变面板选中目标；不锁定、不触发屏幕动作。 */
    public static void selectNextTarget() {
        if (RadarHud.currentMode == RadarMode.STT) {
            RadarHud.lastLockMessage = "TRK PD 为单目标跟踪，不能切换目标";
            return;
        }

        if (RadarHud.currentMode == RadarMode.TWS) {
            TwsTrackManager.cycle();
            TwsTrack selected = TwsTrackManager.selected();
            RadarHud.selectedEchoId = selected == null ? -1 : selected.entityId;
            RadarHud.selectionInitialized = selected != null;
            RadarHud.lastLockMessage = selected == null ? "无有效航迹" : "切换面板目标";
            return;
        }

        List<RadarEcho> valid = new ArrayList<>();
        int tick = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.tickCount;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (!echo.expired(tick)) valid.add(echo);
        }
        valid.sort(java.util.Comparator.comparingDouble(e -> e.range));
        if (valid.isEmpty()) {
            RadarHud.selectedEchoId = -1;
            RadarHud.lastLockMessage = "无有效回波";
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
        RadarHud.lastLockMessage = "切换面板目标";
    }

    /** 面板选择只由 Alt+T/扫描首次回波维护，不因视角移动自动换目标。 */
    private static void validatePanelSelection() {
        if (RadarHud.selectedEchoId < 0) {
            if (!RadarHud.selectionInitialized && !RadarHud.echoes.isEmpty()) {
                RadarEcho first = null;
                for (RadarEcho echo : RadarHud.echoes.values()) {
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
            if (echo.entityId == RadarHud.selectedEchoId) return;
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
            return;
        }
        if (RadarHud.scanStartTick < 0) {
            RadarHud.scanStartTick = player.tickCount;
        }
        int ticks = player.tickCount - RadarHud.scanStartTick;
        int rows = RadarHud.effectiveScanRows();
        // 每行扫一遍后切换下一行；行数由扫描模式决定，行间方向交替。
        int period = RadarHud.isAcmContext() ? 5 : RadarHud.SCAN_ROW_TICKS;
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
        RadarHud.sweepAzimuthDeg = (float) (RadarHud.scanAzimuthOffsetDeg
                + (forward ? -half + 2 * half * frac : half - 2 * half * frac));
        double elevationHalf = RadarHud.effectiveScanElevationHalfDeg();
        double elevationSpan = elevationHalf * 2.0;
        RadarHud.sweepElevationDeg = (float) (RadarHud.scanElevationOffsetDeg
                + elevationHalf
                - (row + 0.5) * elevationSpan / rows);
    }

    /** 仅当扫描线实际扫过地形时才留下杂波；短暂残留后消失。 */
    private static void updateClutter(Minecraft mc, Player player) {
        if (!RadarHud.radarOn || RadarHud.currentMode.isSurfaceSearch()
                || (RadarHud.currentMode.isDoppler() && !RadarHud.currentMode.isSurfaceSearch())) {
            RadarHud.clutterCells.clear();
            return;
        }

        int now = player.tickCount;
        RadarHud.clutterCells.removeIf(c ->
                now - c.lastHitTick() > RadarHud.CLUTTER_MEMORY_TICKS);

        Vec3 eye = player.getEyePosition();
        double maxRange = RadarHud.effectiveRange();
        double startAz = lastSweepRow == scanRow ? lastSweepAzimuth : RadarHud.sweepAzimuthDeg;
        double endAz = RadarHud.sweepAzimuthDeg;

        // 每条扫描线采样几个方位点，再在上下几度内采样，形成扫描拖尾。
        for (int ai = 0; ai < 5; ai++) {
            double az = startAz + (endAz - startAz) * ai / 4.0;
            for (int ei = -1; ei <= 1; ei++) {
                double el = RadarHud.sweepElevationDeg + ei * 4.0;
                Vec3 dir = Vec3.directionFromRotation(
                        player.getXRot() - (float) el,
                        player.getYRot() + (float) az).normalize();
                Vec3 end = eye.add(dir.scale(maxRange));
                var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                        eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;

                double distance = eye.distanceTo(hit.getLocation());
                boolean merged = false;
                for (int i = 0; i < RadarHud.clutterCells.size(); i++) {
                    RadarHud.ClutterCell c = RadarHud.clutterCells.get(i);
                    if (Math.abs(c.azimuthDeg() - az) < 2.0
                            && Math.abs(c.elevationDeg() - el) < 2.0) {
                        RadarHud.clutterCells.set(i, new RadarHud.ClutterCell(
                                (float) az, (float) el, distance, now));
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    RadarHud.clutterCells.add(new RadarHud.ClutterCell(
                            (float) az, (float) el, distance, now));
                }
            }
        }
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
        RadarHud.clutterCells.clear();
        TwsTrackManager.clear();
        RadarHud.lastLockMessage = "";
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
        lastSentTick = -100;
        lockTicks = 0;
        inertialTicks = 0;
    }
}
