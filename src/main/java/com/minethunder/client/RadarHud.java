package com.minethunder.client;

import com.minethunder.missile.MissileSpec;
import com.minethunder.radar.RadarContact;
import com.minethunder.radar.RadarMode;
import com.minethunder.radar.SeekerLockModel;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 雷达/导弹 HUD 渲染状态（客户端）。
 *
 * 显示规则（SPEC §2.2）：
 * - 导引头圆环：红外弹两个白环（内=fov，外=lockAngleMax）；雷达弹锁定环为红白分层。
 * - 锁定环：仅锁定期间显示，发射后消失。
 */
public final class RadarHud {
    private RadarHud() {}

    /** 设计稿中的方形雷达面板边长（实际渲染按窗口缩放） */
    public static final int PANEL_SIZE = 360;
    /** 设计稿中的面板右侧留白 */
    public static final int PANEL_MARGIN_RIGHT = 24;
    /** 设计稿中的面板底部留白 */
    public static final int PANEL_BOTTOM = 100;

    /** 当前雷达模式 */
    public static RadarMode currentMode = RadarMode.SRC_PD;
    /** 雷达是否开启（Alt+R 切换，默认开启） */
    public static boolean radarOn = true;
    /** 雷达导引头是否开启（Alt+X 切换） */
    public static boolean seekerEnabled = false;
    /** 面板方位显示范围（±120°），不再等于最大搜索宽度 */
    public static final double PANEL_AZIMUTH_DEG = 120.0;
    /** 距离档位：Q/Alt+Q 循环 */
    public static final double[] RANGES = {40, 100, 300, 600, 1000};
    public static int rangeIndex = 2;
    /** 真实雷达扫描模式：方位总宽度 × 仰角总覆盖。 */
    public record ScanPattern(String label, double halfAzimuthDeg,
                              double halfElevationDeg, int rows) {}
    public static final ScanPattern[] SCAN_PATTERNS = {
            new ScanPattern("40x20", 20.0, 10.0, 4),
            new ScanPattern("120x10", 60.0, 5.0, 2),
            new ScanPattern("120x20", 60.0, 10.0, 4)
    };
    public static int scanPatternIndex = 2;
    /** 单次波束宽度：方位 3°、仰角 5°。搜索行高也是 5°，相邻行刚好铺满。 */
    public static final double SCAN_BEAM_HALF_AZ_DEG = 1.5;
    public static final double SCAN_BEAM_HALF_EL_DEG = 2.5;
    /** 单行扫描内允许的最大方位跨步；超过视为手动平移，只扫当前波束。 */
    public static final double MAX_SWEEP_STEP_DEG = 15.0;
    /** ACM/TRK 的固定仰角范围与行数。 */
    public static final double SCAN_ELEVATION_HALF_DEG = 60.0;
    public static final int SCAN_ELEVATION_ROWS = 7;
    /** 非 ACM 单行扫描 tick 数；7 行全帧约 8.4 秒。 */
    public static final int SCAN_ROW_TICKS = 14;

    /** ACM 独立搜索参数：默认贴近 Demo，并允许在 ACM 内调整。 */
    public static final double[] ACM_RANGES = {10, 20, 40, 100, 300};
    public static final double[] ACM_SCAN_WIDTHS = {6, 20, 40, 60, 120};
    public static int acmRangeIndex = 3;
    public static int acmScanWidthIndex = 0;
    public static double acmRange = 100;
    public static double acmScanHalfDeg = 6;

    /** ACM 自动截获后虽然切到 STT，仍保持 ACM 的近距离参数。 */
    public static boolean isAcmContext() {
        return acmActive && (currentMode == RadarMode.ACM || currentMode == RadarMode.STT);
    }

    public static ScanPattern scanPattern() {
        return SCAN_PATTERNS[Math.floorMod(scanPatternIndex, SCAN_PATTERNS.length)];
    }

    /** TRK PD 使用 ±120° 跟踪门限；ACM 固定；其他模式使用当前搜索档位。 */
    public static double effectiveScanHalfDeg() {
        if (currentMode == RadarMode.STT) return PANEL_AZIMUTH_DEG;
        if (currentMode == RadarMode.ACM) return acmScanHalfDeg;
        return scanPattern().halfAzimuthDeg();
    }

    public static double effectiveScanElevationHalfDeg() {
        return currentMode == RadarMode.STT || currentMode == RadarMode.ACM
                ? SCAN_ELEVATION_HALF_DEG : scanPattern().halfElevationDeg();
    }

    public static int effectiveScanRows() {
        return currentMode == RadarMode.STT || currentMode == RadarMode.ACM
                ? SCAN_ELEVATION_ROWS : scanPattern().rows();
    }

    /** 一整帧（含上下往复）所需 tick；用于判断回波是否仍在当前扫描周期内。 */
    public static int scanFrameTicks() {
        int rows = Math.max(2, effectiveScanRows());
        return (rows * 2 - 2) * SCAN_ROW_TICKS;
    }

    /** TRK PD 与 ACQ PD 共用当前距离档；ACM 仅在 ACM 搜索阶段使用独立距离。 */
    public static double effectiveRange() {
        if (currentMode == RadarMode.STT) {
            return maxRange;
        }
        return isAcmContext() ? acmRange : maxRange;
    }
    /** 扫描中心偏移：方向键平滑调整，右 Shift 复位；俯仰最大 ±60° */
    public static double scanAzimuthOffsetDeg = 0;
    public static double scanElevationOffsetDeg = 0;
    /** 当前面板选中的回波实体 ID（只影响面板） */
    public static int selectedEchoId = -1;
    public static boolean selectionInitialized = false;
    /** 是否处于锁定状态（STT / TRK PD） */
    public static boolean locked = false;
    /** 正在尝试建立 TRK PD 锁定 */
    public static boolean lockAcquiring = false;
    public static int lockAcquireTicks = 0;
    /** 尝试锁定框固定在发起时的面板近似位置 */
    public static double lockAnchorAzimuthDeg = 0;
    public static double lockAnchorRange = 0;
    /** 退出 TRK PD 后恢复的搜索模式 */
    public static RadarMode preLockMode = RadarMode.SRC_PD;
    /** 尝试锁定前的扫描参数快照，失败时恢复。 */
    public static int preLockRangeIndex = 2;
    public static int preLockScanPatternIndex = 2;
    public static double preLockScanAzimuthOffsetDeg = 0;
    public static double preLockScanElevationOffsetDeg = 0;
    /** 尝试锁定框固定的扫描点位置。 */
    public static Vec3 lockAnchorPosition = Vec3.ZERO;
    /** 是否处于 ACM 模式上下文（自动截获后切 STT 仍保持） */
    public static boolean acmActive = false;
    /** 退出 ACM 后恢复的模式 */
    public static RadarMode acmReturnMode = RadarMode.SRC_PD;
    /** ACM 解锁后的复锁冷却 tick */
    public static int acmCooldownTicks = 0;
    /** 拖锁渐隐透明度与无更新计时 */
    public static float lockAlpha = 1f;
    public static int lockLostTicks = 0;
    public static final int LOCK_ACQUIRE_TICKS = 20;
    public static final double NOTCH_BURN_THROUGH_RANGE =
            com.minethunder.radar.RadarCore.NOTCH_BURN_THROUGH_RANGE;
    /** 当前锁定的目标 */
    @Nullable
    public static net.minecraft.world.entity.Entity lockedEntity = null;
    /** BVR 远程锁定目标（ARH 数据链专用，可能超出客户端实体跟踪范围） */
    @Nullable
    public static UUID bvrLockedUuid = null;
    public static String bvrLockedName = "";
    public static double bvrLockedDistance = 0;
    public static double bvrLockedClosure = 0;
    /** 正在尝试建立 BVR 远程锁定 */
    public static boolean bvrAcquiring = false;
    public static int bvrAcquireTicks = 0;
    /** 当前选中的弹种（决定 HUD 显示红外圆环还是雷达锁定环） */
    public static MissileSpec.Guidance selectedMissile = MissileSpec.Guidance.IR;
    /** 当前选中导弹的完整参数（决定 HUD 圆环尺寸与发射包线） */
    public static MissileSpec selectedSpec = com.minethunder.missile.MissileSpecs.IR;
    /** 锁定目标信息（HUD 文字） */
    public static double lockedDistance = 0;
    public static double lockedClosure = 0;
    public static String lockedName = "";
    /** 导引头方位/仰角（用于绘制白色圆环位置，度） */
    public static float seekerAzimuthDeg = 0f;
    public static float seekerElevationDeg = 0f;
    /** 锁定进度环 0..1（锁定期间会变进度） */
    public static float lockProgress = 0f;
    /** 是否有导弹在飞行（发射后锁定环消失） */
    public static boolean missileInFlight = false;

    /** 雷达弹导引头稳定环模型（白单圈/建立/稳定/高置信） */
    public static final SeekerLockModel seekerModel = new SeekerLockModel();
    /** 导引头中间环从屏幕中心抛向目标的动画 */
    public static final SeekerRingSlew seekerRing = new SeekerRingSlew();

    /** Demo 风格帮助面板 / 按钮显示模式 */
    public static boolean helpOpen = false;
    public static int panelDisplay = 0;
    /** 导弹结束提示（客户端近似） */
    public static String missileOutcome = "";
    public static long missileOutcomeAt = 0L;
    public static String lastLockMessage = "";
    /** 导引头操作反馈（无弹药/弹种不符/已开启等） */
    public static String seekerMessage = "";

    /** 导引头开启后的 tick 数，用于主动弹 0.5 秒对准判断 */
    public static int seekerEnabledTicks = 0;
    /** 导引头是否已进入惯性导航（雷达面板目标点画小圈） */
    public static boolean seekerInertial = false;
    /** 惯性导航预测目标在雷达面板上的方位/距离 */
    public static double inertialAzimuthDeg = 0;
    public static double inertialDistance = 0;
    /** 失锁瞬间保存的目标世界位置/速度，惯性导航按机头姿态重新投影 */
    public static Vec3 inertialWorldPosition = Vec3.ZERO;
    public static Vec3 inertialVelocity = Vec3.ZERO;

    /** 当前扫描线在画面/面板上的方位和俯仰（度） */
    public static float sweepAzimuthDeg = 0f;
    public static float sweepElevationDeg = 0f;
    /** 上一 tick 的扫描线，用于渲染插值。 */
    public static float prevSweepAzimuthDeg = 0f;
    public static float prevSweepElevationDeg = 0f;
    public static int scanStartTick = -1;
    /** 当前扫描线命中的目标缓存（UUID -> tick） */
    public static final Map<UUID, Integer> contactPaintTicks = new HashMap<>();
    /** 扫描命中后目标回波保留 tick 数（9 秒） */
    public static final int CONTACT_MEMORY_TICKS = 180;
    /** 已扫描命中的回波记忆（用于点亮、惯性外推与切换） */
    public static final Map<UUID, RadarEcho> echoes = new HashMap<>();
    /** 导弹发射后的雷达面板引导线 */
    public static boolean guidanceLineActive = false;
    public static int guidanceTargetId = -1;
    public static int guidanceMissileId = -1;
    @Nullable
    public static UUID guidanceMissileUuid = null;
    public static double guidanceMissileAzimuthDeg = 0;
    public static double guidanceMissileDistance = 0;
    public static double guidanceTargetAzimuthDeg = 0;
    public static double guidanceTargetDistance = 0;
    public static double guidancePitbullRange = 0;
    public static boolean guidanceIsArh = false;
    public static Vec3 guidanceLastWorldPosition = Vec3.ZERO;

    /** 红外锁定内圈的逻辑/绘制基准半径（UI 缩放前）。 */
    public static final int IR_LOCK_RING_BASE = 28;
    /** 红外导引头是否开机。 */
    public static boolean seekerActive = false;
    /** 红外导引头当前候选/锁定目标。 */
    @Nullable
    public static net.minecraft.world.entity.Entity irCandidate = null;
    @Nullable
    public static net.minecraft.world.entity.Entity seekerLockedEntity = null;
    public static boolean irLocked = false;
    public static int irLockTicks = 0;
    public static int irLockTargetId = -1;
    public static float irAimX = 0f;
    public static float irAimY = 0f;
    public static boolean irAimInitialized = false;
    public static String irStatus = "";

    /** 本 tick 的雷达回波（客户端显示用） */
    public static final List<RadarContact> contacts = new ArrayList<>();
    /** 雷达显示距离刻度（米） */
    public static double displayRange = 300;
    /** 雷达最大探测距离（格） */
    public static double maxRange = 300;

    /**
     * 将雷达接触点从球坐标映射到面板二维坐标。
     * @param azimuthDeg 方位角（度，相对机头）
     * @param distance 距离（米）
     * @param maxRange 最大显示距离（米）
     * @return [x, y] 面板内坐标（相对面板左上角）
     */
    public static float[] project(float azimuthDeg, double distance, double maxRange) {
        return project(azimuthDeg, distance, maxRange, PANEL_SIZE);
    }

    public static float[] project(float azimuthDeg, double distance, double maxRange, int panelSize) {
        float half = panelSize / 2f;
        float x = half + (float) (azimuthDeg / PANEL_AZIMUTH_DEG) * half;
        // B-scope：距离 0 在面板底部，最大距离在顶部，与 drawContacts 保持一致。
        float y = panelSize - (float) ((distance / Math.max(maxRange, 1.0)) * panelSize);
        return new float[]{x, y};
    }
}
