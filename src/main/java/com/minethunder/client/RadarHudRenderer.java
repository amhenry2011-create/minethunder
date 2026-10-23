package com.minethunder.client;

import com.minethunder.missile.IrTargeting;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.MissileSpec;
import com.minethunder.radar.BvrKind;
import com.minethunder.radar.BvrTrack;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarIconType;
import com.minethunder.radar.RadarMode;
import com.minethunder.radar.RadarTargetClass;
import com.minethunder.radar.RwrContact;
import com.minethunder.radar.RwrManager;
import com.minethunder.radar.RwrThreat;
import com.minethunder.radar.SeekerLockModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 战雷风格 HUD（按用户截图还原）：
 * - 右侧绿色 B-scope 雷达面板 + 左侧控制条
 * - 屏幕中心：大导引头环（白=搜索，红=锁定）、小绿视场环
 * - 目标锁定：缩小的绿色目标框 + 名称/距离/接近速度
 * - 雷达导引头：中心白环 → 白/红倒计时 → 红色双环
 * - 右下：导弹发射包线指示竖线
 * - 底部：武器快捷条（动态显示实际键位）
 */
public final class RadarHudRenderer {
    private RadarHudRenderer() {}

    // WT 配色调
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF4040;
    private static final int GREEN = 0xFF44FF44;
    private static final int GRID = 0xCC22BB22;
    private static final int PANEL_BG = 0xB0022A02;
    private static final int AMBER = 0xFFFFC040;
    private static final int BAR_BG = 0x90002000;
    /** 主画面锁定框半边长（UI 缩放前） */
    private static final int LOCK_BOX_HALF = 18;
    /** 导引头倒计时外环比内环大的像素（UI 缩放前） */
    private static final int SEEKER_ARC_GAP = 4;
    /** 导引头圆环基础线宽（UI 缩放前） */
    private static final int SEEKER_RING_WIDTH = 2;

    public static void render(GuiGraphics g, int screenW, int screenH) {
        renderScanOverlay(g, screenW, screenH);
        renderTopInfo(g, screenW, screenH);
        renderCompass(g, screenW, screenH);
        renderRwr(g, screenW, screenH);
        renderRadarPanel(g, screenW, screenH);
        renderCenterRings(g, screenW, screenH);
        renderTargetLock(g, screenW, screenH);
        renderTwsOverlay(g, screenW, screenH);
        renderSeekerMessage(g, screenW, screenH);
    }

    // ===================== 雷达面板（右侧绿色 B-scope） =====================

    private static void renderRadarPanel(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        float scale = HudScale.factor(screenW, screenH);
        int size = Math.round(RadarHud.PANEL_SIZE * scale);
        int px = screenW - size - Math.round(RadarHud.PANEL_MARGIN_RIGHT * scale);
        int py = screenH - size - Math.round(RadarHud.PANEL_BOTTOM * scale);
        int right = px + size;
        int bottom = py + size;

        g.fill(px, py, right, bottom, PANEL_BG);
        drawRectBorder(g, px, py, size, size, GREEN);
        int cx = px + size / 2;

        // 扫描宽度：两侧暗透明填充，放在网格/目标下层
        int bandLeft = cx + Math.round((float) ((RadarHud.scanAzimuthOffsetDeg - RadarHud.effectiveScanHalfDeg()) / RadarHud.PANEL_AZIMUTH_DEG * (size / 2.0)));
        int bandRight = cx + Math.round((float) ((RadarHud.scanAzimuthOffsetDeg + RadarHud.effectiveScanHalfDeg()) / RadarHud.PANEL_AZIMUTH_DEG * (size / 2.0)));
        bandLeft = Math.max(px + 1, Math.min(right - 1, bandLeft));
        bandRight = Math.max(px + 1, Math.min(right - 1, bandRight));
        g.fill(px + 1, py + 1, bandLeft, bottom - 1, 0xCC2FAF2F);
        g.fill(bandRight, py + 1, right - 1, bottom - 1, 0xCC2FAF2F);

        // 网格：竖线（方位）+ 横线（距离）
        for (int i = 1; i <= 3; i++) {
            int x = px + size * i / 4;
            g.fill(x, py + 1, x + 1, bottom - 1, GRID);
        }
        for (int i = 1; i <= 4; i++) {
            int y = py + size * i / 5;
            g.fill(px + 1, y, right - 1, y + 1, GRID);
        }
        // 扫描指示：与主画面共用同一条方位扫描线
        int sweepX = cx + Math.round((float) (RadarHud.sweepAzimuthDeg / RadarHud.PANEL_AZIMUTH_DEG * (size / 2.0)));
        sweepX = Math.max(px + 1, Math.min(right - 2, sweepX));
        boolean trackingView = RadarHud.currentMode == RadarMode.TWS
                || RadarHud.currentMode == RadarMode.STT;
        if (!trackingView) {
            g.fill(sweepX, py + 1, sweepX + 2, bottom - 1, 0xFFB0FFB0);
        }

        // 数字移到面板外：模式/距离在面板上方

        String modeText = RadarHud.currentMode.code();
        if (RadarHud.lockAcquiring) {
            modeText = "ACQ PD";
        } else if (RadarHud.locked) {
            modeText = "TRK PD";
        }
        int outsideY = py - 11;
        g.drawString(mc.font, modeText, px, outsideY, GREEN, false);
        String width = "±" + (int) RadarHud.effectiveScanHalfDeg() + "°";
        g.drawString(mc.font, width, px + mc.font.width(modeText) + 6, outsideY, GREEN, false);
        String range = (int) RadarHud.effectiveRange() + " b";
        g.drawString(mc.font, range, right - mc.font.width(range), outsideY, GREEN, false);

        // 左右边缘方位标签不再放在框内；仰角刻度移到框外。

        // 目标回波
        drawContacts(g, px, py, size);

        // 导弹引导线：从面板中心指向目标/惯性预测点
        drawGuidanceLine(g, px, py, size);

        // 导引头惯性导航：在雷达面板目标点画小圈
        if (RadarHud.seekerInertial) {
            float[] ip = RadarHud.project((float) RadarHud.inertialAzimuthDeg,
                    RadarHud.inertialDistance, RadarHud.effectiveRange(), size);
            int ix = px + (int) ip[0];
            int iy = py + (int) ip[1];
            if (ix > px + 3 && ix < right - 3 && iy > py + 12 && iy < bottom - 3) {
                drawCircle(g, ix, iy, Math.max(3, Math.round(5 * scale)), GREEN);
            }
        }

        drawElevationRuler(g, px, py, size, mc);

        // 导弹发射包线指示线（右侧竖线 + 标记）
        drawLaunchAssessment(g, px, py, size);
        // 底部距离 0（面板外）
        g.drawString(mc.font, "0 b", px, bottom + 2, GREEN, false);
    }

    private static void drawRectBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void drawContacts(GuiGraphics g, int px, int py, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int nowTick = mc.player.tickCount;
        double range = Math.max(RadarHud.effectiveRange(), 1.0);
        if (RadarHud.currentMode == RadarMode.TWS) {
            drawTwsContacts(g, mc, px, py, size, range, nowTick);
            return;
        }

        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (echo.expired(nowTick)) continue;
            Entity entity = mc.level == null ? null : mc.level.getEntity(echo.entityId);
            com.minethunder.radar.RadarTargetClass currentClass =
                    entity == null ? null : RadarCore.classify(entity);
            if (currentClass == null || !RadarHud.currentMode.accepts(currentClass)) continue;
            if (RadarHud.currentMode == RadarMode.TWS
                    && currentClass != com.minethunder.radar.RadarTargetClass.AIR) continue;
            // 面板只显示最后一次扫描命中的位置，扫描线未命中时不继续预测/刷新。
            double az = echo.azimuthDeg;
            double dist = echo.range;
            if (dist > range || dist < 0.1) continue;
            int bx = px + size / 2 + (int) (az / RadarHud.PANEL_AZIMUTH_DEG * (size / 2));
            int by = py + size - (int) (dist / range * size);
            if (bx < px + 2 || bx >= px + size - 2 || by < py + 12 || by >= py + size - 2) continue;

            boolean isLocked = RadarHud.locked && RadarHud.lockedEntity != null
                    && RadarHud.lockedEntity.getId() == echo.entityId;
            if (RadarHud.currentMode == RadarMode.STT && !isLocked) continue;

            boolean twsMode = RadarHud.currentMode == RadarMode.TWS;
            boolean iconMode = !twsMode
                    && RadarHud.currentMode != RadarMode.SRC_PD
                    && RadarHud.currentMode != RadarMode.ACQ_PD
                    && RadarHud.currentMode != RadarMode.GMI
                    && RadarHud.currentMode != RadarMode.SEA;
            float alpha = Math.max(0.25f, 1f - (float) echo.notchSeverity * 0.65f);
            if (twsMode) {
                int color = alphaColor(isLocked ? WHITE : GREEN, alpha);
                drawFilledCircle(g, bx, by, 2, color);
                drawTwsHeadingLine(g, mc, echo, bx, by, color);
                if (!isLocked && echo.entityId == RadarHud.selectedEchoId) {
                    int vLen = 10;
                    int dx = 7;
                    g.fill(bx - dx, by - vLen / 2, bx - dx + 2, by + vLen / 2, GREEN);
                    g.fill(bx + dx, by - vLen / 2, bx + dx + 2, by + vLen / 2, GREEN);
                }
            } else if (iconMode) {
                int color = alphaColor(isLocked ? WHITE : GREEN, alpha);
                RadarIconType icon = echo.iconType == null ? RadarIconType.LIVING : echo.iconType;
                drawContactIcon(g, bx, by, icon, color);
                drawMotionVector(g, mc, echo, bx, by,
                        Math.max(6, (int) Math.round(size * 0.03)), color);

                // 图标模式不再额外绘制白色选中/锁定框，仅用图标颜色表示状态。
            } else {
                // GMI / SEA 保持旧面板样式：横线目标 + 选中竖线 + 锁定亮点。
                int shortLen = Math.max(6, (int) Math.round(size * 0.028));
                int thick = Math.max(1, (int) Math.round(size * 0.007));
                if (isLocked) {
                    int color = alphaColor(GREEN, RadarHud.lockAlpha);
                    g.fill(bx - 2, by - 2, bx + 3, by + 3, color);
                    g.fill(bx - 1, by - 3, bx + 2, by + 4, color);
                } else {
                    int color = alphaColor(GREEN, alpha);
                    g.fill(bx - shortLen / 2, by - thick / 2,
                            bx + shortLen / 2, by + thick / 2 + 1, color);
                }
                if (!isLocked && !RadarHud.lockAcquiring
                        && echo.entityId == RadarHud.selectedEchoId) {
                    int vLen = Math.round(shortLen * 1.5f);
                    int dx = shortLen / 2 + Math.max(3, (int) Math.round(size * 0.012));
                    g.fill(bx - dx, by - vLen / 2, bx - dx + 2, by + vLen / 2, GREEN);
                    g.fill(bx + dx, by - vLen / 2, bx + dx + 2, by + vLen / 2, GREEN);
                }
            }
        }

        // BVR 远程航迹：与普通目标完全相同的样式/颜色（无专属符号），数据来自服务端同步。
        // ACM 是纯视距内自动截获，不显示超视距数据链目标。
        if (RadarHud.currentMode == RadarMode.ACM) return;
        Vec3 bvrEye = mc.player.getEyePosition();
        Vec3 bvrLook = mc.player.getViewVector(1.0F).normalize();
        Vec3 bvrRight = bvrLook.cross(new Vec3(0, 1, 0));
        if (bvrRight.lengthSqr() < 1.0E-6) bvrRight = new Vec3(1, 0, 0);
        bvrRight = bvrRight.normalize();
        for (BvrTrack track : BvrState.active()) {
            if (mc.player.getUUID().equals(track.uuid())) continue;
            if (track.kind() == BvrKind.MISSILE
                    && mc.player.getUUID().equals(track.ownerUuid())) continue;
            RadarEcho bvrShadow = RadarHud.echoes.get(track.uuid());
            if (bvrShadow != null && !bvrShadow.expired(nowTick)) continue;
            RadarTargetClass bvrClass = switch (track.kind()) {
                case PLAYER, AI, MISSILE -> RadarTargetClass.AIR;
                case SURFACE -> RadarTargetClass.SEA;
            };
            if (!RadarHud.currentMode.accepts(bvrClass)) continue;
            boolean isLockedBvr = RadarHud.isLockedTarget(track.uuid());
            if (RadarHud.currentMode == RadarMode.STT && !isLockedBvr) continue;
            // 波束涂抹记忆：只有被扫描波束实际扫过的航迹才显示；
            // 已锁定的 BVR 目标除外（同 TRK PD 连续跟踪，保持实时位置）。
            BvrState.Paint paint = BvrState.painted(track.uuid());
            Vec3 predicted;
            Vec3 velocity;
            float panelAzBvr;
            double distBvr;
            if (isLockedBvr) {
                predicted = BvrState.lagCompensatedPosition(track);
                velocity = track.velocity();
                Vec3 toLock = predicted.subtract(bvrEye);
                panelAzBvr = (float) Math.toDegrees(Math.atan2(
                        toLock.dot(bvrRight), toLock.dot(bvrLook)));
                distBvr = bvrEye.distanceTo(predicted);
            } else {
                if (paint == null) continue;
                predicted = paint.position();
                velocity = paint.velocity();
                panelAzBvr = paint.azDeg();
                distBvr = paint.range();
            }
            double dist = distBvr;
            if (dist > range || dist < 0.1) continue;
            Vec3 toBvr = predicted.subtract(bvrEye);
            double az = panelAzBvr;
            int bx = px + size / 2 + (int) (az / RadarHud.PANEL_AZIMUTH_DEG * (size / 2));
            int by = py + size - (int) (dist / range * size);
            if (bx < px + 2 || bx >= px + size - 2 || by < py + 12 || by >= py + size - 2) continue;
            boolean iconModeBvr = RadarHud.currentMode != RadarMode.SRC_PD
                    && RadarHud.currentMode != RadarMode.ACQ_PD
                    && RadarHud.currentMode != RadarMode.GMI
                    && RadarHud.currentMode != RadarMode.SEA;
            if (iconModeBvr) {
                RadarIconType icon = switch (track.kind()) {
                    case PLAYER -> RadarIconType.PLAYER;
                    case AI -> RadarIconType.LIVING;
                    case MISSILE -> RadarIconType.MISSILE;
                    case SURFACE -> RadarIconType.BLOCK;
                };
                float bvrAlpha = isLockedBvr ? 1f : bvrEchoAlpha(velocity, toBvr, dist);
                int color = alphaColor(isLockedBvr ? WHITE : GREEN, bvrAlpha);
                drawContactIcon(g, bx, by, icon, color);
                drawMotionVector(g, mc, velocity, predicted, bx, by,
                        Math.max(6, (int) Math.round(size * 0.03)), color);
            } else {
                int shortLen = Math.max(6, (int) Math.round(size * 0.028));
                int thick = Math.max(1, (int) Math.round(size * 0.007));
                if (isLockedBvr) {
                    int color = alphaColor(GREEN, 1f);
                    g.fill(bx - 2, by - 2, bx + 3, by + 3, color);
                    g.fill(bx - 1, by - 3, bx + 2, by + 4, color);
                } else {
                    float bvrAlpha = bvrEchoAlpha(velocity, toBvr, dist);
                    g.fill(bx - shortLen / 2, by - thick / 2,
                            bx + shortLen / 2, by + thick / 2 + 1, alphaColor(GREEN, bvrAlpha));
                    if (RadarHud.selectedEchoId == track.entityId()) {
                        int vLen = Math.round(shortLen * 1.5f);
                        int dx = shortLen / 2 + Math.max(3, (int) Math.round(size * 0.012));
                        g.fill(bx - dx, by - vLen / 2, bx - dx + 2, by + vLen / 2, GREEN);
                        g.fill(bx + dx, by - vLen / 2, bx + dx + 2, by + vLen / 2, GREEN);
                    }
                }
            }
        }
    }

    private static void drawTwsContacts(GuiGraphics g, Minecraft mc, int px, int py,
                                        int size, double range, int nowTick) {
        for (TwsTrack track : TwsTrackManager.tracks()) {
            if (track.range > range || track.range < 0.1) continue;
            int bx = px + size / 2 + (int) (track.azimuthDeg / RadarHud.PANEL_AZIMUTH_DEG
                    * (size / 2));
            int by = py + size - (int) (track.range / range * size);
            if (bx < px + 2 || bx >= px + size - 2 || by < py + 12
                    || by >= py + size - 2) continue;
            RadarEcho echo = RadarHud.echoes.get(track.uuid);
            float alpha = echo == null ? 0.7f
                    : Math.max(0.25f, 1f - (float) echo.notchSeverity * 0.65f);
            boolean selected = track.entityId == RadarHud.selectedEchoId;
            int color = alphaColor(selected ? WHITE : GREEN, alpha);
            drawFilledCircle(g, bx, by, selected ? 3 : 2, color);
            Entity entity = mc.level == null ? null : mc.level.getEntity(track.entityId);
            Vec3 heading = entity != null ? entity.getViewVector(1.0F) : track.velocity;
            drawHeadingLine(g, mc, heading, bx, by, color);
            if (selected) {
                int vLen = 10;
                int dx = 7;
                g.fill(bx - dx, by - vLen / 2, bx - dx + 2, by + vLen / 2, GREEN);
                g.fill(bx + dx, by - vLen / 2, bx + dx + 2, by + vLen / 2, GREEN);
            }
        }

        // BVR 远程空中航迹：与 TWS 接触同款圆点/航向线，不参与选中；本地回波优先。
        Vec3 bvrEye = mc.player.getEyePosition();
        Vec3 bvrLook = mc.player.getViewVector(1.0F).normalize();
        Vec3 bvrRight = bvrLook.cross(new Vec3(0, 1, 0));
        if (bvrRight.lengthSqr() < 1.0E-6) bvrRight = new Vec3(1, 0, 0);
        bvrRight = bvrRight.normalize();
        for (BvrTrack track : BvrState.active()) {
            if (mc.player.getUUID().equals(track.uuid())) continue;
            if (track.kind() != BvrKind.PLAYER && track.kind() != BvrKind.AI) continue;
            RadarEcho bvrShadow = RadarHud.echoes.get(track.uuid());
            if (bvrShadow != null && !bvrShadow.expired(nowTick)) continue;
            // 波束涂抹记忆：只显示被扫描波束扫过的航迹；锁定目标保持实时位置。
            boolean lockedBvr = RadarHud.isLockedTarget(track.uuid());
            BvrState.Paint paint = BvrState.painted(track.uuid());
            Vec3 predicted;
            Vec3 velocity;
            float panelAzTws;
            double distTws;
            if (lockedBvr) {
                predicted = BvrState.lagCompensatedPosition(track);
                velocity = track.velocity();
                Vec3 toLock = predicted.subtract(bvrEye);
                panelAzTws = (float) Math.toDegrees(Math.atan2(
                        toLock.dot(bvrRight), toLock.dot(bvrLook)));
                distTws = bvrEye.distanceTo(predicted);
            } else {
                if (paint == null) continue;
                predicted = paint.position();
                velocity = paint.velocity();
                panelAzTws = paint.azDeg();
                distTws = paint.range();
            }
            double dist = distTws;
            if (dist > range || dist < 0.1) continue;
            Vec3 toBvr = predicted.subtract(bvrEye);
            double az = panelAzTws;
            int bx = px + size / 2 + (int) (az / RadarHud.PANEL_AZIMUTH_DEG * (size / 2));
            int by = py + size - (int) (dist / range * size);
            if (bx < px + 2 || bx >= px + size - 2 || by < py + 12
                    || by >= py + size - 2) continue;
            boolean selectedBvr = !lockedBvr && RadarHud.selectedEchoId == track.entityId();
            float bvrAlpha = lockedBvr ? 1f : bvrEchoAlpha(velocity, toBvr, dist);
            int color = alphaColor((lockedBvr || selectedBvr) ? WHITE : GREEN, bvrAlpha);
            drawFilledCircle(g, bx, by, lockedBvr ? 3 : 2, color);
            drawHeadingLine(g, mc, velocity, bx, by, color);
            if (selectedBvr) {
                int vLen = 10;
                int dx = 7;
                g.fill(bx - dx, by - vLen / 2, bx - dx + 2, by + vLen / 2, GREEN);
                g.fill(bx + dx, by - vLen / 2, bx + dx + 2, by + vLen / 2, GREEN);
            }
        }
    }

    /** BVR 点亮度与本地接触同规：按相对径向速度（notch）调制，无盲区时保持原基础亮度。 */
    private static float bvrEchoAlpha(Vec3 velocity, Vec3 toBvr, double dist) {
        double radial = velocity.subtract(Minecraft.getInstance().player.getDeltaMovement())
                .scale(20.0).dot(toBvr.normalize());
        double severity = RadarCore.notchSeverity(radial, dist, RadarHud.currentMode);
        if (severity == 0.0) return 0.7f;
        return Math.max(0.25f, 1f - (float) severity * 0.65f);
    }

    private static void drawHeadingLine(GuiGraphics g, Minecraft mc, Vec3 heading,
                                        int x, int y, int color) {
        if (heading.lengthSqr() < 1.0E-6) return;
        heading = heading.normalize();
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) return;
        right = right.normalize();
        double lateral = heading.dot(right);
        double forward = heading.dot(look);
        double magnitude = Math.hypot(lateral, forward);
        if (magnitude < 1.0E-4) return;
        int len = 8;
        int ex = x + (int) Math.round(lateral / magnitude * len);
        int ey = y - (int) Math.round(forward / magnitude * len);
        drawLinePixels(g, x, y, ex, ey, color);
    }

    private static void drawTwsHeadingLine(GuiGraphics g, Minecraft mc, RadarEcho echo,
                                           int x, int y, int color) {
        Entity entity = mc.level == null ? null : mc.level.getEntity(echo.entityId);
        Vec3 heading = entity != null ? entity.getViewVector(1.0F) : echo.velocity;
        if (heading.lengthSqr() < 1.0E-6) return;
        heading = heading.normalize();
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) return;
        right = right.normalize();
        double lateral = heading.dot(right);
        double forward = heading.dot(look);
        double magnitude = Math.hypot(lateral, forward);
        if (magnitude < 1.0E-4) return;
        int len = 8;
        int ex = x + (int) Math.round(lateral / magnitude * len);
        int ey = y - (int) Math.round(forward / magnitude * len);
        drawLinePixels(g, x, y, ex, ey, color);
    }

    private static void drawContactIcon(GuiGraphics g, int x, int y,
                                        RadarIconType icon, int color) {
        int r = 3;
        switch (icon) {
            case PLAYER -> {
                for (int dy = -r; dy <= r; dy++) {
                    int dx = r - Math.abs(dy);
                    g.fill(x - dx, y + dy, x + dx + 1, y + dy + 1, color);
                }
            }
            case LIVING -> drawFilledCircle(g, x, y, r, color);
            case MISSILE -> {
                g.fill(x - 1, y - 4, x + 1, y + 3, color);
                g.fill(x - 2, y - 2, x + 2, y - 1, color);
                g.fill(x - 2, y + 2, x - 1, y + 4, color);
                g.fill(x + 1, y + 2, x + 2, y + 4, color);
            }
            case BLOCK -> g.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        }
    }

    private static void drawFilledCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void drawMotionVector(GuiGraphics g, Minecraft mc, RadarEcho echo,
                                         int x, int y, int length, int color) {
        drawMotionVector(g, mc, echo.velocity, echo.position, x, y, length, color);
    }

    /** 运动方向短线：BVR 航迹版本（无本地实体，直接用同步速度/预测位置）。 */
    private static void drawMotionVector(GuiGraphics g, Minecraft mc, Vec3 velocity,
                                         Vec3 targetPos, int x, int y, int length, int color) {
        if (velocity.lengthSqr() < 1.0E-5) return;
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) return;
        right = right.normalize();
        Vec3 to = targetPos.subtract(mc.player.getEyePosition());
        if (to.lengthSqr() < 1.0E-6) return;
        double lateral = velocity.dot(right);
        double rangeRate = velocity.dot(to.normalize());
        double magnitude = Math.hypot(lateral, rangeRate);
        if (magnitude < 1.0E-4) return;
        int ex = x + (int) Math.round(lateral / magnitude * length);
        int ey = y - (int) Math.round(rangeRate / magnitude * length);
        drawLinePixels(g, x, y, ex, ey, color);
    }

    private static void drawLinePixels(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private static int alphaColor(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static void drawGuidanceLine(GuiGraphics g, int px, int py, int size) {
        if (!RadarHud.guidanceLineActive) return;
        double range = Math.max(RadarHud.effectiveRange(), 1.0);
        boolean lineVisible = RadarHud.locked
                || RadarHud.currentMode == RadarMode.TWS
                || RadarHud.hasActiveLockTarget();
        float[] target = RadarHud.project((float) RadarHud.guidanceTargetAzimuthDeg,
                RadarHud.guidanceTargetDistance, range, size);
        int tx = px + (int) target[0];
        int ty = py + (int) target[1];
        tx = Math.max(px + 3, Math.min(px + size - 4, tx));
        ty = Math.max(py + 12, Math.min(py + size - 4, ty));

        if (!lineVisible) {
            // 取消锁定/回到搜索模式：只显示惯性导航目标圆圈，不主动复锁。
            drawCircle(g, tx, ty, Math.max(4, (int) Math.round(size * 0.017)), AMBER);
            g.fill(tx - 2, ty, tx + 3, ty + 1, AMBER);
            g.fill(tx, ty - 2, tx + 1, ty + 3, AMBER);
            return;
        }

        float[] missile = RadarHud.project((float) RadarHud.guidanceMissileAzimuthDeg,
                RadarHud.guidanceMissileDistance, range, size);
        int ox = px + (int) missile[0];
        int oy = py + (int) missile[1];
        if (ox < px + 2 || ox > px + size - 3 || oy < py + 12 || oy > py + size - 3) {
            return;
        }
        double gapFraction = -1;
        if (RadarHud.guidanceIsArh && RadarHud.guidancePitbullRange > 0
                && RadarHud.guidanceTargetDistance > RadarHud.guidancePitbullRange) {
            gapFraction = (RadarHud.guidanceTargetDistance - RadarHud.guidancePitbullRange)
                    / RadarHud.guidanceTargetDistance;
        }
        for (int i = 0; i <= 50; i++) {
            double frac = i / 50.0;
            if (gapFraction >= 0 && Math.abs(frac - gapFraction) < 0.035) continue;
            int x = (int) Math.round(ox + (tx - ox) * frac);
            int y = (int) Math.round(oy + (ty - oy) * frac);
            g.fill(x, y, x + 1, y + 1, 0xAA44FF44);
        }

        // 实时导弹位置（本地实体或 BVR 数据链航迹）
        if (RadarHud.guidanceMissileId >= 0 || RadarHud.guidanceMissileUuid != null) {
            float[] m = RadarHud.project((float) RadarHud.guidanceMissileAzimuthDeg,
                    RadarHud.guidanceMissileDistance, range, size);
            int mx = px + (int) m[0];
            int my = py + (int) m[1];
            if (mx >= px + 2 && mx <= px + size - 3 && my >= py + 12 && my <= py + size - 3) {
                g.fill(mx - 2, my - 2, mx + 3, my + 3, WHITE);
                g.fill(mx - 1, my - 3, mx + 2, my + 4, WHITE);
            }
        }
        drawCircle(g, tx, ty, Math.max(3, (int) Math.round(size * 0.012)), GREEN);
    }

    private static void drawDashedLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) return;
        for (int i = 0; i <= steps; i++) {
            if (i % 4 >= 2) continue;
            int x = x1 + dx * i / steps;
            int y = y1 + dy * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** 右侧竖线 = 导弹最大发射距离在距离轴上的位置（发射包线） */
    private static void drawLaunchCue(GuiGraphics g, int px, int py, int size) {
        double range = Math.max(RadarHud.effectiveRange(), 1.0);
        int lineX = px + size - 14;
        int top = py + 16;
        int bottom = py + size - 6;
        g.fill(lineX, top, lineX + 1, bottom, 0xCC44FF44);

        double eng = RadarHud.selectedSpec == null ? 0 : RadarHud.selectedSpec.maxDistance;
        int markerY = bottom - (int) Math.min(1.0, eng / range * (bottom - top));
        boolean inEnvelope = RadarHud.locked && RadarHud.lockedDistance > 0 && RadarHud.lockedDistance <= eng;
        int color = inEnvelope ? GREEN : AMBER;
        g.fill(lineX - 4, markerY - 1, lineX + 5, markerY + 1, color);
        g.fill(lineX - 4, markerY - 4, lineX - 3, markerY + 4, color);
        g.fill(lineX + 4, markerY - 4, lineX + 5, markerY + 4, color);
    }

    private static void drawDashedLineV(GuiGraphics g, int x, int yFrom, int yTo, int color) {
        int step = 6;
        for (int y = yFrom; y > yTo; y -= step) {
            g.fill(x, Math.max(y - 3, yTo), x + 1, y, color);
        }
    }

    // ===================== 雷达控制条（面板左侧） =====================

    private static void renderControlStrip(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        float scale = HudScale.factor(screenW, screenH);
        int size = Math.round(RadarHud.PANEL_SIZE * scale);
        int px = screenW - size - Math.round(RadarHud.PANEL_MARGIN_RIGHT * scale);
        int py = screenH - size - Math.round(RadarHud.PANEL_BOTTOM * scale);
        int bw = 52;
        int bh = 16;
        int x = px - bw - 4;

        // 上方 4 个：雷达开关、模式、帮助、挂架
        drawKeyBox(g, x, py, bw, bh, "Alt+R", GREEN);
        drawKeyBox(g, x, py + bh + 3, bw, bh, "Alt+B", GREEN);
        drawKeyBox(g, x, py + (bh + 3) * 2, bw, bh, "H 帮助", GREEN);
        drawKeyBox(g, x, py + (bh + 3) * 3, bw, bh, keyName(KeyBindings.RACK_SELECT), GREEN);

        // 下方：Alt+E 同时释放箔条与热焰弹
        int by = py + size - bh - 3;
        drawKeyBox(g, x, by, bw, bh, "Alt+E 干扰", GREEN);
    }

    private static void drawKeyBox(GuiGraphics g, int x, int y, int w, int h, String text, int color) {
        Minecraft mc = Minecraft.getInstance();
        g.fill(x, y, x + w, y + h, BAR_BG);
        drawRectBorder(g, x, y, w, h, color);
        float scale = 0.75f;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        g.drawString(mc.font, text, (int) ((x + (w - mc.font.width(text) * scale) / 2) / scale),
                (int) ((y + (h - 8 * scale) / 2) / scale), color);
        g.pose().popPose();
    }

    private static String keyName(net.minecraft.client.KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    // ===================== 屏幕中心导引头环 =====================

    private static void renderCenterRings(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        int cx = screenW / 2;
        int cy = screenH / 2;

        MissileSpec spec = RadarHud.selectedSpec;
        if (spec == null) return;
        // 导引头统一由 Alt+X 开关；关闭时不显示任何环。
        if (!RadarHud.seekerEnabled) return;

        boolean radarGuided = RadarHud.selectedMissile == MissileSpec.Guidance.SARH
                || RadarHud.selectedMissile == MissileSpec.Guidance.ARH;
        // TRK/TWS 通用：无硬锁定但有 TWS 软跟踪或 BVR 远程 TRK 时，
        // 导引头环画在当前跟踪目标上，同样走白/红倒计时流程。
        TwsTrack softTrack = radarGuided && !RadarHud.locked
                && RadarHud.currentMode == RadarMode.TWS
                && TwsTrackManager.selected() != null ? TwsTrackManager.selected() : null;
        if (radarGuided && !RadarHud.locked) {
            if (softTrack != null || RadarHud.hasActiveLockTarget()) {
                drawRadarLockLayer(g, mc, screenW, screenH,
                        Math.max(0.55f, HudScale.factor(screenW, screenH)));
                return;
            }
            float ui = Math.max(0.55f, HudScale.factor(screenW, screenH));
            int radius = Math.max(12, Math.round(LOCK_BOX_HALF * ui));
            int width = Math.max(2, Math.round(SEEKER_RING_WIDTH * ui));
            drawRing(g, cx, cy, radius, width, WHITE);
            return;
        }

        float ui = Math.max(0.55f, HudScale.factor(screenW, screenH));
        double fovY = Math.toRadians(Math.max(30, mc.options.fov().get()));
        double tanHalf = Math.tan(fovY / 2);
        double halfH = screenH / 2.0;

        int outerR = (int) (Math.tan(Math.toRadians(spec.lockAngleMaxDeg)) / tanHalf * halfH * ui);
        int innerR = (int) (Math.tan(Math.toRadians(spec.seekerFovDeg)) / tanHalf * halfH * ui);
        // 红外弹外环恢复原视觉倍率；该外环同时决定实际锁定/发射边界。
        if (!radarGuided) {
            outerR = Math.max(144, (int) Math.round(outerR * IrTargeting.OUTER_RING_SCALE));
            innerR = Math.max(16, Math.round(RadarHud.IR_LOCK_RING_BASE * ui));
        } else {
            innerR = Math.max(8, Math.min(innerR, Math.max(24, screenH / 2 - 8)));
        }

        // 红外弹在未锁定阶段两环每 0.5 秒闪烁；锁定时恒亮红色。
        boolean irFlashOn = RadarHud.irLocked
                || (mc.player != null && (mc.player.tickCount / 10) % 2 == 0);

        // 雷达弹不绘制最外层大圈；红外弹保留，且允许被屏幕裁切。
        if (!radarGuided && irFlashOn) {
            int ringColor = RadarHud.irLocked ? RED : WHITE;
            drawRing(g, cx, cy, outerR, Math.max(2, Math.round(2 * ui)), ringColor);
        }

        if (radarGuided) {
            drawRadarLockLayer(g, mc, screenW, screenH, ui);
        } else if (irFlashOn) {
            // 红外弹：锁定后小圈随目标移动，未锁定时保持在屏幕中央。
            // 超出可视范围时沿目标方向径向钳制（不再按横/纵轴独立钳制），避免环滑离太阳方向。
            double aimX = RadarHud.irAimInitialized ? RadarHud.irAimX : cx;
            double aimY = RadarHud.irAimInitialized ? RadarHud.irAimY : cy;
            double dx = aimX - cx;
            double dy = aimY - cy;
            double dist = Math.hypot(dx, dy);
            int sx = (int) Math.round(aimX);
            int sy = (int) Math.round(aimY);
            double limit = Math.max(0.0, outerR - innerR);
            double availW = Math.max(0.0, screenW / 2.0 - innerR - 4);
            double availH = Math.max(0.0, screenH / 2.0 - innerR - 4);
            if (dist > 1.0E-6) {
                double dirX = dx / dist;
                double dirY = dy / dist;
                if (Math.abs(dirX) > 1.0E-6) limit = Math.min(limit, availW / Math.abs(dirX));
                if (Math.abs(dirY) > 1.0E-6) limit = Math.min(limit, availH / Math.abs(dirY));
                if (dist > limit) {
                    sx = (int) Math.round(cx + dirX * limit);
                    sy = (int) Math.round(cy + dirY * limit);
                }
            }
            int innerColor = RadarHud.irLocked ? RED : WHITE;
            drawRing(g, sx, sy, innerR, Math.max(2, Math.round(2 * ui)), innerColor);
        }

    }

    /** 雷达导引头：中心白环 → 瞬移锁位 → 白/红倒计时 → 红色双环；硬锁/TWS/BVR 源通用。 */
    private static void drawRadarLockLayer(GuiGraphics g, Minecraft mc, int screenW, int screenH, float ui) {
        boolean hasTarget = RadarHud.lockedEntity != null
                || (RadarHud.currentMode == RadarMode.TWS
                && TwsTrackManager.selected() != null)
                || RadarHud.hasActiveLockTarget();
        if (!hasTarget) return;
        double rx = RadarHud.seekerRing.x();
        double ry = RadarHud.seekerRing.y();
        int cx = (int) Math.max(24, Math.min(screenW - 24, rx > 0 ? rx : screenW / 2.0));
        int cy = (int) Math.max(24, Math.min(screenH - 24, ry > 0 ? ry : screenH / 2.0));
        int radius = Math.max(12, Math.round(LOCK_BOX_HALF * ui));
        int gap = Math.max(3, Math.round(SEEKER_ARC_GAP * ui));
        int width = Math.max(2, Math.round(SEEKER_RING_WIDTH * ui));
        SeekerLockModel.State state = RadarHud.seekerModel.state();

        switch (state) {
            case SEARCHING -> drawRing(g, cx, cy, radius, width, WHITE);
            case ACQUIRING -> {
                drawRing(g, cx, cy, radius, width, WHITE);
                drawProgressArc(g, cx, cy, radius + gap,
                        (float) RadarHud.seekerModel.progress(), WHITE, width);
            }
            case TRACKING -> {
                drawRing(g, cx, cy, radius, width, RED);
                drawProgressArc(g, cx, cy, radius + gap,
                        (float) RadarHud.seekerModel.stability(), RED, width);
            }
            case HIGH -> {
                drawRing(g, cx, cy, radius, width, RED);
                drawRing(g, cx, cy, radius + gap, width, RED);
            }
        }
    }

    // ===================== 目标锁定（红色刻圈 + 目标框） =====================

    private static void renderTargetLock(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        Entity target = RadarHud.lockedEntity;
        if (target == null && !RadarHud.hasActiveLockTarget()) return;

        // ACQ PD 尝试锁定：四角闪烁框，锚定发起时的扫描点；本地/BVR 同规。
        if (RadarHud.lockAcquiring) {
            Vec3 anchor = RadarHud.lockAnchorPosition;
            if (anchor == null || anchor.lengthSqr() < 1.0E-6) {
                TrackedTarget tracked = TargetRegistry.find(
                        RadarHud.lockedTargetUuid, mc.player, mc.player.tickCount);
                if (tracked == null) return;
                anchor = tracked.livePosition();
            }
            float[] ap = worldToScreen(anchor, mc.player, screenW, screenH);
            if (ap == null) return;
            int ax = (int) ap[0];
            int ay = (int) ap[1];
            float ui = HudScale.factor(screenW, screenH);
            float pulse = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(
                    System.currentTimeMillis() / 180.0));
            int half = Math.max(16, Math.round(24 * ui));
            drawCornerBox(g, ax, ay, half, Math.max(6, Math.round(8 * ui)), GREEN, pulse);
            String label = "ACQ PD";
            g.drawString(mc.font, label, ax - mc.font.width(label) / 2,
                    ay - half - 13, GREEN, false);
            return;
        }

        // BVR 远程 TRK：与本地锁定完全相同的绿色框样式，数据来自统一目标。
        if (target == null) {
            TrackedTarget tracked = TargetRegistry.find(
                    RadarHud.lockedTargetUuid, mc.player, mc.player.tickCount);
            if (tracked == null) return;
            Vec3 bvrPos = tracked.livePosition();
            float[] p = worldToScreen(bvrPos, mc.player, screenW, screenH);
            if (p == null) return;
            int tx = (int) p[0];
            int ty = (int) p[1];
            float ui = Math.max(0.65f, HudScale.factor(screenW, screenH));
            int b = Math.max(12, Math.round(LOCK_BOX_HALF * ui));
            drawRectBorder(g, tx - b, ty - b, b * 2, b * 2, GREEN);

            String name = tracked.displayName();
            String info = String.format("%.1f b",
                    mc.player.getEyePosition().distanceTo(bvrPos));
            String closure = String.format("%.0f b/s", RadarHud.lockedClosure);
            int off = Math.max(20, Math.round(40 * ui));
            Vec3 velocity = tracked.velocity();
            double speed = velocity.horizontalDistance() * 20.0;
            int heading = Math.round(((float) (Math.toDegrees(
                    Math.atan2(-velocity.x, velocity.z)) % 360) + 360) % 360);
            String data = String.format("%.0f b/s  %d°", speed, heading);
            g.drawString(mc.font, data, tx - mc.font.width(data) / 2, ty - off - 12, GREEN, false);
            g.drawString(mc.font, name, tx - mc.font.width(name) / 2, ty - off, GREEN, false);
            g.drawString(mc.font, info, tx - mc.font.width(info) / 2, ty + b + 4, GREEN, false);
            g.drawString(mc.font, closure, tx - mc.font.width(closure) / 2, ty + b + 14, GREEN, false);
            return;
        }

        // TRK 绿色锁定框与当前弹种无关；IR 模式下同样显示。
        if (!RadarHud.locked) return;

        float[] p = worldToScreen(target.getBoundingBox().getCenter(), mc.player, screenW, screenH);
        if (p == null) return;
        int tx = (int) p[0];
        int ty = (int) p[1];
        float ui = Math.max(0.65f, HudScale.factor(screenW, screenH));
        float alpha = RadarHud.locked ? RadarHud.lockAlpha : 1f;

        // 雷达目标只画缩小的绿色方框和参数；导引头圆环由阶段环单独绘制。
        int b = Math.max(12, Math.round(LOCK_BOX_HALF * ui));
        drawRectBorder(g, tx - b, ty - b, b * 2, b * 2, alphaColor(GREEN, alpha));

        String name = RadarHud.lockedName;
        if (name == null || name.isEmpty()) name = target.getName().getString();
        String info = String.format("%.1f b", RadarHud.lockedDistance);
        String closure = String.format("%.0f b/s", RadarHud.lockedClosure);
        int off = Math.max(20, Math.round(40 * ui));
        double speed = target.getDeltaMovement().horizontalDistance() * 20.0;
        int heading = Math.round(((target.getYRot() % 360) + 360) % 360);
        String data = String.format("%.0f b/s  %d°", speed, heading);
        g.drawString(mc.font, data, tx - mc.font.width(data) / 2, ty - off - 12, GREEN, false);
        g.drawString(mc.font, name, tx - mc.font.width(name) / 2, ty - off, GREEN, false);
        g.drawString(mc.font, info, tx - mc.font.width(info) / 2, ty + b + 4, GREEN, false);
        g.drawString(mc.font, closure, tx - mc.font.width(closure) / 2, ty + b + 14, GREEN, false);

    }

    /** TWS 当前软跟踪目标框：只显示当前目标，不覆盖显示全部回波。 */
    private static void renderTwsOverlay(GuiGraphics g, int screenW, int screenH) {
        if (!RadarHud.radarOn || RadarHud.currentMode != RadarMode.TWS
                || RadarHud.lockedEntity != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        TwsTrack track = TwsTrackManager.selected();
        if (track == null || track.lastPaintTick < 0) return;

        int tick = mc.player.tickCount;
        Vec3 pos = track.predictedPosition(tick);
        float[] p = worldToScreen(pos, mc.player, screenW, screenH);
        if (p == null) return;

        float ui = HudScale.factor(screenW, screenH);
        int half = Math.max(12, Math.round(LOCK_BOX_HALF * ui));
        int len = Math.max(6, Math.round(7 * ui));
        int tx = (int) p[0];
        int ty = (int) p[1];
        drawCornerBox(g, tx, ty, half, len, GREEN, 1f);

        String name = "TWS";
        Entity entity = TwsTrackManager.entity(track.entityId);
        if (entity != null) name = entity.getName().getString();
        g.drawString(mc.font, name, tx - mc.font.width(name) / 2, ty - half - 18, GREEN, false);
        String range = String.format("%.1f b", track.range);
        g.drawString(mc.font, range, tx - mc.font.width(range) / 2,
                ty + half + 6, GREEN, false);
        if (track.coasting(tick)) {
            g.drawString(mc.font, "INER", tx - mc.font.width("INER") / 2,
                    ty - half - 30, AMBER, false);
        }
        if (RadarHud.seekerEnabled
                && RadarHud.selectedMissile == MissileSpec.Guidance.ARH) {
            int width = Math.max(2, Math.round(SEEKER_RING_WIDTH * ui));
            drawRing(g, tx, ty, half + 2, width, WHITE);
        }
    }

    private static String radarStatus() {
        if (RadarHud.missileInFlight) return "导弹已发射";
        if (!RadarHud.seekerRing.aligned()) return "导引头: 中心白环保持 1 秒（禁止发射）";
        return switch (RadarHud.seekerModel.state()) {
            case SEARCHING -> "导引头: 白色单环搜索（禁止发射）";
            case ACQUIRING -> "导引头: 白色倒计时 "
                    + Math.round(RadarHud.seekerModel.progress() * 100) + "%（ARH 可发射）";
            case TRACKING -> "导引头: 红色倒计时 "
                    + Math.round(RadarHud.seekerModel.stability() * 100) + "%（可发射）";
            case HIGH -> "导引头: 红色双环锁定（可发射）";
        };
    }

    // ===================== 底部武器条 =====================

    private static void renderWeaponBar(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        float ui = HudScale.factor(screenW, screenH);
        if (ui < 0.55f) return;
        int slot = Math.max(22, Math.round(40 * ui));
        int gap = Math.max(2, Math.round(4 * ui));
        String[] labels = {
                keyName(KeyBindings.CYCLE_MISSILE_TYPE),
                "Alt+F",
                "H",
                keyName(KeyBindings.RACK_SELECT),
                "B"
        };
        String[] icons = {"武", "锁", "雷", "架", "模"};
        int total = labels.length * slot + (labels.length - 1) * gap;
        int x0 = (screenW - total) / 2;
        int y0 = screenH - slot - Math.max(8, Math.round(12 * ui));

        for (int i = 0; i < labels.length; i++) {
            int x = x0 + i * (slot + gap);
            g.fill(x, y0, x + slot, y0 + slot, BAR_BG);
            drawRectBorder(g, x, y0, x + slot, y0 + slot, GREEN);
            g.drawString(mc.font, icons[i], x + (slot - mc.font.width(icons[i])) / 2,
                    y0 + slot / 2 - 4, GREEN);
            if (ui >= 0.9f) {
                g.drawString(mc.font, labels[i], x - 4, y0 - 10, GREEN);
            }
        }
    }

    private static void renderHint(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        String hint = "Alt+B 模式  Alt+R 雷达  右Shift 回中  方向键扫描  Alt+F 锁定  Alt+T 目标  Alt+X 导引头  Alt+E 干扰";
        g.drawString(mc.font, hint, 8, screenH - 12, 0x8066FF66, false);
    }


    // ===================== Demo 风格主视图 / 辅助 UI =====================

    private static void renderScanOverlay(GuiGraphics g, int w, int h) {
        if (!RadarHud.radarOn) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // TWS / TRK PD 是自动跟踪视图，不显示扫描横线/竖线。
        if (RadarHud.currentMode == RadarMode.TWS || RadarHud.currentMode == RadarMode.STT) {
            return;
        }
        float ui = HudScale.factor(w, h);
        if (RadarHud.currentMode == RadarMode.ACM) {
            if (RadarHud.lockedEntity == null && mc.player != null) {
                drawAcmBeamOverlay(g, mc, w, h, ui);
            }
            return;
        }

        double half = RadarHud.effectiveScanHalfDeg();
        int x0 = scanScreenX(RadarHud.scanAzimuthOffsetDeg - half, w);
        int x1 = scanScreenX(RadarHud.scanAzimuthOffsetDeg + half, w);
        int yTop = elToScreenY(RadarHud.scanElevationOffsetDeg
                + RadarHud.effectiveScanElevationHalfDeg(), w, h);
        int yBot = elToScreenY(RadarHud.scanElevationOffsetDeg
                - RadarHud.effectiveScanElevationHalfDeg(), w, h);
        x0 = Math.max(2, Math.min(w - 4, x0));
        x1 = Math.max(2, Math.min(w - 4, x1));
        yTop = Math.max(2, Math.min(h - 4, yTop));
        yBot = Math.max(2, Math.min(h - 4, yBot));

        int hx = scanScreenX(RadarHud.sweepAzimuthDeg, w);
        int hy = elToScreenY(RadarHud.sweepElevationDeg, w, h);
        hx = Math.max(x0, Math.min(x1, hx));
        hy = Math.max(yTop, Math.min(yBot, hy));
        int len = Math.max(12, Math.round(28 * ui));

        // 固定在扫描范围最顶端的短横线；不随俯仰行上下移动。
        g.fill(Math.max(x0, hx - len / 2), yTop,
                Math.min(x1, hx + len / 2), yTop + 1, 0xCCB0FFB0);
        // 右侧扫描极限处的一小段竖线，仍随当前扫描行上下移动。
        g.fill(x1, Math.max(yTop, hy - len / 2), x1 + 2,
                Math.min(yBot, hy + len / 2), 0xCCB0FFB0);
        g.drawString(mc.font, "扫描 ±" + (int) half + "°",
                x0, Math.max(4, yTop - 12), 0x806BFF6B, false);
    }

    private static void renderTopInfo(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float ui = HudScale.factor(w, h);
        int x = Math.max(4, Math.round(18 * ui));
        int y = Math.max(4, Math.round(10 * ui));
        double speed = mc.player.getDeltaMovement().horizontalDistance() * 20.0;

        int ir = 0, sarh = 0, arh = 0;
        int flares = 0, chaff = 0;
        for (var stack : mc.player.getInventory().items) {
            if (stack.getItem() instanceof MissileFireworkItem firework) {
                switch (firework.getSpec().guidance) {
                    case IR -> ir += stack.getCount();
                    case SARH -> sarh += stack.getCount();
                    case ARH -> arh += stack.getCount();
                }
            }
            if (stack.is(net.minecraft.world.item.Items.FIRE_CHARGE)) flares += stack.getCount();
            if (stack.is(net.minecraft.world.item.Items.SNOWBALL)) chaff += stack.getCount();
        }
        for (var stack : mc.player.getInventory().offhand) {
            if (stack.getItem() instanceof MissileFireworkItem firework) {
                switch (firework.getSpec().guidance) {
                    case IR -> ir += stack.getCount();
                    case SARH -> sarh += stack.getCount();
                    case ARH -> arh += stack.getCount();
                }
            }
            if (stack.is(net.minecraft.world.item.Items.FIRE_CHARGE)) flares += stack.getCount();
            if (stack.is(net.minecraft.world.item.Items.SNOWBALL)) chaff += stack.getCount();
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"速度", String.format("%.0f b/s", speed)});
        rows.add(new String[]{"海拔高度", String.format("%.0f b", mc.player.getY())});
        rows.add(new String[]{"空对空 IR", "x" + ir + statusSuffix(MissileSpec.Guidance.IR)});
        rows.add(new String[]{"空对空 SARH", "x" + sarh + statusSuffix(MissileSpec.Guidance.SARH)});
        rows.add(new String[]{"空对空 ARH", "x" + arh + statusSuffix(MissileSpec.Guidance.ARH)});
        rows.add(new String[]{"热焰弹", "x" + flares});
        rows.add(new String[]{"干扰箔", "x" + chaff});

        for (int i = 0; i < rows.size(); i++) {
            int yy = y + i * 12;
            g.drawString(mc.font, rows.get(i)[0], x, yy, 0xFF8F9F8F, false);
            g.drawString(mc.font, rows.get(i)[1], x + Math.max(62, Math.round(128 * ui)), yy,
                    i < 2 ? 0xFFFF8A4A : 0xFFCFD8CF, false);
        }
    }

    private static String statusSuffix(MissileSpec.Guidance guidance) {
        if (!RadarHud.seekerEnabled || RadarHud.selectedMissile != guidance) return "";
        if (guidance == MissileSpec.Guidance.IR) {
            return RadarHud.irStatus.isEmpty() ? "" : "  " + RadarHud.irStatus;
        }
        return switch (RadarHud.seekerModel.state()) {
            case SEARCHING -> "  搜索";
            case ACQUIRING -> "  截获";
            case TRACKING -> "  追踪";
            case HIGH -> "  锁定";
        };
    }

    private static void renderCompass(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float ui = HudScale.factor(w, h);
        int cx = w / 2;
        int y = Math.max(18, Math.round(40 * ui));
        int halfW = Math.max(80, Math.round(340 * ui));
        float heading = mc.player.getYRot();
        for (int d = -30; d <= 30; d += 5) {
            int val = Math.round((heading + d + 360) % 360);
            int x = cx + Math.round(d / 30.0f * halfW);
            int tick = d % 10 == 0 ? 7 : 4;
            g.fill(x, y, x + 1, y + tick, 0xFF9AA59A);
            if (d % 10 == 0) {
                String label = val == 0 ? "N" : val == 90 ? "E" : val == 180 ? "S" : val == 270 ? "W" : String.valueOf(val);
                g.drawString(mc.font, label, x - mc.font.width(label) / 2, y - 10, 0xFF9AA59A, false);
            }
        }
        g.fill(cx, y + 8, cx + 1, y + 14, 0xFF9AA59A);
    }

    /** 雷达关闭时仍绘制 RWR 和独立的 IR 导引头环。 */
    public static void renderRwrOnly(GuiGraphics g, int w, int h) {
        renderRwr(g, w, h);
        if (RadarHud.seekerEnabled
                && RadarHud.selectedMissile == MissileSpec.Guidance.IR) {
            renderCenterRings(g, w, h);
        }
    }

    private static void renderRwr(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float ui = HudScale.factor(w, h);
        int x = Math.max(60, (int) Math.round(w * 0.14));
        int y = Math.max(80, (int) Math.round(h * 0.62));
        int r = Math.max(24, Math.round(96 * ui));
        int inner = Math.max(10, Math.round(r * 0.17f));
        int green = 0xFF49B849;

        drawRing(g, x, y, r, 2, green);
        drawRing(g, x, y, inner, 1, green);
        g.drawString(mc.font, "RWR", x - mc.font.width("RWR") / 2, y - r - 14, green, false);

        // 外圈内部 12 条短线，代表 12 个方向；12 点方向在正上方。
        int tick = Math.max(4, Math.round(r * 0.075f));
        for (int i = 0; i < 12; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 6.0;
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            int x0 = (int) Math.round(x + sin * (r - 2));
            int y0 = (int) Math.round(y + cos * (r - 2));
            int x1 = (int) Math.round(x + sin * (r - 2 - tick));
            int y1 = (int) Math.round(y + cos * (r - 2 - tick));
            drawLine(g, x0, y0, x1, y1, green);
        }

        // 圆心小十字代表本机。
        int cross = Math.max(3, inner / 2);
        drawLine(g, x - cross, y, x + cross, y, 0xFFB8FFB8);
        drawLine(g, x, y - cross, x, y + cross, 0xFFB8FFB8);

        for (RwrContact c : RwrState.contacts()) {
            double fraction = Math.max(0.0, Math.min(1.0, c.range() / RwrManager.RANGE));
            double rr = inner + fraction * Math.max(1.0, r - inner - 3);
            double angle = Math.toRadians(c.bearingDeg());
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            int cx = (int) Math.round(x + sin * rr);
            int cy = (int) Math.round(y - cos * rr);

            if (c.threat() == RwrThreat.MISSILE) {
                int ox = (int) Math.round(x + sin * (r - 2));
                int oy = (int) Math.round(y - cos * (r - 2));
                drawLine(g, x, y, ox, oy, 0xAAFF4040);
                drawMslLabel(g, mc, cx, cy);
                continue;
            }

            int color = switch (c.threat()) {
                case SEARCH -> 0xFF65D66A;
                case TRACK -> 0xFFFFC040;
                case LOCK -> 0xFFFF4040;
                default -> 0xFFFFFFFF;
            };
            float alpha = c.threat() == RwrThreat.SEARCH
                    ? Math.max(0.15f, 1f - c.ageTicks() / 40f) : 1f;
            int col = alphaColor(color, alpha);
            int dotR = Math.max(5, Math.round(7 * ui));
            drawCircle(g, cx, cy, dotR, col);
            String tag = c.tag() == null || c.tag().isEmpty() ? "?" : c.tag();
            // 浮点坐标 + 按 lineHeight 垂直居中，避免整像素截断导致字母不居中。
            g.drawString(mc.font, tag, cx + 0.5f - mc.font.width(tag) / 2f,
                    cy - mc.font.lineHeight / 2f, col, false);
        }

        drawRwrStatus(g, mc, x, y, r, ui, green, h);
    }

    private static void drawMslLabel(GuiGraphics g, Minecraft mc, int cx, int cy) {
        String label = "MSL";
        int tw = mc.font.width(label);
        int left = cx - tw / 2;
        int top = cy - mc.font.lineHeight / 2;
        g.fill(left - 2, top - 1, left + tw + 2, top + mc.font.lineHeight + 1, 0xB0000000);
        g.drawString(mc.font, label, left + 0.5f, top, 0xFFFF5A4A, false);
    }

    private static void drawRwrStatus(GuiGraphics g, Minecraft mc, int x, int y, int r,
                                      float ui, int green, int screenH) {
        String[] labels = {"敌跟踪", "敌锁定", "敌导弹"};
        boolean[] active = {RwrState.hasTrack(), RwrState.hasLock(), RwrState.hasMissile()};
        // 导弹状态快速闪烁；只有正常范围内的导弹接触才点亮。
        boolean missileBlink = (System.currentTimeMillis() / 110L) % 2L == 0L;
        active[2] = active[2] && missileBlink;

        int boxW = Math.max(38, Math.round(48 * ui));
        int boxH = Math.max(12, Math.round(14 * ui));
        int gap = Math.max(3, Math.round(4 * ui));
        int total = boxW * 3 + gap * 2;
        int x0 = x - total / 2;
        int y0 = y + r + Math.max(6, Math.round(8 * ui));
        if (y0 + boxH > screenH - 4) y0 = screenH - 4 - boxH;

        for (int i = 0; i < 3; i++) {
            int bx = x0 + i * (boxW + gap);
            boolean on = active[i];
            g.fill(bx, y0, bx + boxW, y0 + boxH, on ? 0x7000A000 : 0x60002000);
            drawRectBorder(g, bx, y0, boxW, boxH, on ? green : 0xFF1E6A1E);
            int tw = mc.font.width(labels[i]);
            g.drawString(mc.font, labels[i], bx + (boxW - tw) / 2, y0 + 3,
                    on ? green : 0xFF2F8A2F, false);
        }
    }

    private static void renderSeekerMessage(GuiGraphics g, int w, int h) {
        String text;
        long now = System.currentTimeMillis();
        if (RadarHud.lastLockMessage != null && !RadarHud.lastLockMessage.isEmpty()
                && now - RadarHud.lastLockMessageAt <= 3000L) {
            text = RadarHud.lastLockMessage;
        } else if (RadarHud.seekerMessage != null && !RadarHud.seekerMessage.isEmpty()) {
            text = RadarHud.seekerMessage;
        } else {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float ui = Math.max(0.55f, HudScale.factor(w, h));
        g.drawString(mc.font, text,
                Math.round(24 * ui),
                h - Math.round(22 * ui), 0xFFFFFFFF, false);
    }

    private static void renderMissileOutcome(GuiGraphics g, int w, int h) {
        if (RadarHud.missileOutcome == null || RadarHud.missileOutcome.isEmpty()) return;
        if (System.currentTimeMillis() - RadarHud.missileOutcomeAt > 2500L) return;
        Minecraft mc = Minecraft.getInstance();
        g.drawString(mc.font, RadarHud.missileOutcome,
                w / 2 - mc.font.width(RadarHud.missileOutcome) / 2,
                h - Math.max(32, Math.round(116 * HudScale.factor(w, h))), AMBER, false);
    }

    private static void renderModeText(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float ui = HudScale.factor(w, h);
        int x = w - Math.max(100, Math.round(180 * ui));
        if (!mc.mouseHandler.isMouseGrabbed()) {
            g.drawString(mc.font, "鼠标模式 (Alt)", x, Math.max(4, Math.round(10 * ui)), AMBER, false);
        }
        String[] modes = {"雷达按钮: 按钮+标签", "雷达按钮: 仅按钮", "雷达按钮: 隐藏"};
        g.drawString(mc.font, modes[Math.floorMod(RadarHud.panelDisplay, 3)],
                x, Math.max(16, Math.round(24 * ui)), 0xFF49B849, false);
    }

    private static void renderHelp(GuiGraphics g, int w, int h) {
        if (!RadarHud.helpOpen) return;
        Minecraft mc = Minecraft.getInstance();
        g.fill(0, 0, w, h, 0xC0000800);
        String title = "MineThunder 雷达 · 按键帮助";
        g.drawString(mc.font, title, w / 2 - mc.font.width(title) / 2, 12, GREEN, false);
        String[][] rows = {
                {"B", "雷达模式"}, {"R", "雷达开关"}, {"Q", "距离档位"}, {"D", "扫描宽度"},
                {"W", "ACM"}, {"右Shift", "扫描复位"}, {"Alt+F", "锁定/解锁"}, {"Alt+T", "选目标"},
                {"X", "解除锁定"}, {"Alt+X", "导引头"}, {"Alt+E", "箔条+热焰"}, {"I", "红外模式"},
                {"Enter", "挂架选择"}, {"H", "帮助"}, {"方向键", "扫描方位/俯仰"}, {"Ctrl+Alt", "发射提示"}
        };
        int colW = Math.max(120, w / 2);
        int y0 = 38;
        for (int i = 0; i < rows.length; i++) {
            int col = i / 8;
            int row = i % 8;
            int x = 16 + col * colW;
            int y = y0 + row * 12;
            g.drawString(mc.font, rows[i][0], x, y, AMBER, false);
            g.drawString(mc.font, rows[i][1], x + 56, y, WHITE, false);
        }
        g.drawString(mc.font, "H 关闭", w - 60, 12, 0xFF49B849, false);
    }

    /** 面板左侧外部的俯仰角标尺：固定 ±60° 两条短线，扫描上下限随模式/中心移动。 */
    private static void drawElevationRuler(GuiGraphics g, int px, int py, int size, Minecraft mc) {
        int top = py + 6;
        int bottom = py + size - 6;
        // 参考线画在雷达面板内部左侧，不再占用面板外空间。
        int x = px + 6;
        int dim = 0xAA2FAF2F;
        int bright = 0xFFB8FFB8;

        // 固定标尺：只保留 ±60° 两条短线。
        int yFixedTop = elevationRulerY(60.0, top, bottom);
        int yFixedBottom = elevationRulerY(-60.0, top, bottom);
        g.fill(x - 2, yFixedTop, x + 8, yFixedTop + 1, dim);
        g.fill(x - 2, yFixedBottom, x + 8, yFixedBottom + 1, dim);

        // STT 没有搜索扫描，不显示扫描上下限。
        if (RadarHud.currentMode == RadarMode.STT) return;

        double center = RadarHud.scanElevationOffsetDeg;
        double half = RadarHud.effectiveScanElevationHalfDeg();
        int yUpper = elevationRulerY(center + half, top, bottom);
        int yLower = elevationRulerY(center - half, top, bottom);
        g.fill(x - 4, yUpper, x + 12, yUpper + 1, bright);
        g.fill(x - 4, yLower, x + 12, yLower + 1, bright);

        // 当前扫描线给一个更短的白色刻度，和上下限区分。
        int ySweep = elevationRulerY(RadarHud.sweepElevationDeg, top, bottom);
        g.fill(x + 12, ySweep, x + 18, ySweep + 1, 0xFFFFFFFF);
    }

    private static int elevationRulerY(double angle, int top, int bottom) {
        double clamped = Math.max(-60.0, Math.min(60.0, angle));
        return top + (int) Math.round((60.0 - clamped) / 120.0 * (bottom - top));
    }

    private static void drawOwnShip(GuiGraphics g, int cx, int cy, float ui) {
        int r = Math.max(3, Math.round(6 * ui));
        g.fill(cx, cy - r, cx + 1, cy + 1, GREEN);
        g.fill(cx - r, cy + 1, cx + r + 1, cy + 2, GREEN);
        g.fill(cx - 1, cy + 1, cx + 2, cy + r, GREEN);
    }

    private static void drawStrobe(GuiGraphics g, int px, int py, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double range = Math.max(RadarHud.effectiveRange(), 1.0);
        double dist;
        double az;
        if (RadarHud.lockedEntity != null) {
            RadarEcho echo = RadarHud.echoes.get(RadarHud.lockedEntity.getUUID());
            if (echo == null || echo.expired(mc.player.tickCount)) return;
            dist = echo.range;
            az = echo.azimuthDeg;
        } else if (RadarHud.lockedTargetUuid != null) {
            // BVR 远程锁定同样给面板白光条，作用与本地 TRK 一致。
            TrackedTarget tracked = TargetRegistry.find(
                    RadarHud.lockedTargetUuid, mc.player, mc.player.tickCount);
            if (tracked == null) return;
            Vec3 pos = tracked.livePosition();
            Vec3 eye = mc.player.getEyePosition();
            Vec3 to = pos.subtract(eye);
            dist = to.length();
            Vec3 look = mc.player.getViewVector(1.0F).normalize();
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
            right = right.normalize();
            az = Math.toDegrees(Math.atan2(to.dot(right), to.dot(look)));
        } else {
            return;
        }
        int bx = px + size / 2 + (int) (az / RadarHud.PANEL_AZIMUTH_DEG * (size / 2.0));
        int by = py + size - (int) (dist / range * size);
        g.fill(bx - 4, by, bx + 5, by + 1, WHITE);
        g.fill(bx, by - 4, bx + 1, by + 5, WHITE);
        drawCircle(g, bx, by, Math.max(5, (int) Math.round(size * 0.04)), 0x60F0FFF0);
    }

    private static void drawLaunchAssessment(GuiGraphics g, int px, int py, int size) {
        if (!RadarHud.radarOn || !RadarHud.locked
                || RadarHud.selectedSpec == null) return;
        double range = Math.max(RadarHud.effectiveRange(), 1.0);
        int right = px + size - 4;
        int top = py + 14;
        int bottom = py + size - 4;
        int yTarget = bottom - (int) Math.min(1.0, RadarHud.lockedDistance / range * (bottom - top));
        double specMax = Math.max(RadarHud.selectedSpec.maxDistance, 1.0);
        double scale = Math.min(1.0, range / specMax);
        double max = specMax * scale;
        double nezMin;
        double nezMax;
        double minManeuver;
        switch (RadarHud.selectedSpec.guidance) {
            case IR -> { nezMin = 800; nezMax = 4500; minManeuver = 300; }
            case SARH -> { nezMin = 4000; nezMax = 16000; minManeuver = 1500; }
            default -> { nezMin = 3000; nezMax = 22000; minManeuver = 800; }
        }
        nezMin *= scale;
        nezMax *= scale;
        minManeuver *= scale;
        int yMax = bottom - (int) Math.min(1.0, max / range * (bottom - top));
        int yNezTop = bottom - (int) Math.min(1.0, nezMax / range * (bottom - top));
        int yNezBottom = bottom - (int) Math.min(1.0, nezMin / range * (bottom - top));
        int yMin = bottom - (int) Math.min(1.0, minManeuver / range * (bottom - top));
        int color = RadarHud.lockedDistance <= max ? GREEN : AMBER;
        g.fill(right - Math.max(30, size / 4), yTarget, right, yTarget + 1, color);
        g.fill(right - Math.max(18, size / 6), yMax, right, yMax + 1, GREEN);
        g.fill(right, yNezTop, right + 1, yNezBottom, 0xFF6BFF6B);
        g.fill(right - Math.max(12, size / 8), yNezTop, right + 2, yNezTop + 1, 0xFF6BFF6B);
        g.fill(right - Math.max(12, size / 8), yNezBottom, right + 2, yNezBottom + 1, 0xFF6BFF6B);
        g.fill(right - Math.max(16, size / 6), yMin, right, yMin + 1, 0xFF6BFF6B);
    }

    private static void drawControlButtons(GuiGraphics g, int px, int py, int size, float ui) {
        if (RadarHud.panelDisplay == 2) return;
        int bw = Math.max(14, Math.round(52 * ui));
        int bh = Math.max(12, Math.round(40 * ui));
        int gap = 2;
        String[] left = RadarHud.panelDisplay == 0 ? new String[]{"", "D", "W", "B"}
                : new String[]{"", "", "", ""};
        for (int i = 0; i < left.length; i++) {
            drawSmallButton(g, px - bw - 4, py + i * (bh + gap), bw, bh, left[i]);
        }
        String[] bottom = RadarHud.panelDisplay == 0
                ? new String[]{"←", "→", "↑", "↓", "E", "Q", "R"}
                : new String[]{"", "", "", "", "", "", ""};
        int total = bottom.length * bw + (bottom.length - 1) * gap;
        int sx = px + (size - total) / 2;
        for (int i = 0; i < bottom.length; i++) {
            drawSmallButton(g, sx + i * (bw + gap), py + size + 4, bw, bh, bottom[i]);
        }
    }

    private static void drawSmallButton(GuiGraphics g, int x, int y, int w, int h, String label) {
        g.fill(x, y, x + w, y + h, 0xE0002300);
        drawRectBorder(g, x, y, w, h, GREEN);
        if (!label.isEmpty()) {
            g.drawString(Minecraft.getInstance().font, label,
                    x + (w - Minecraft.getInstance().font.width(label)) / 2,
                    y + (h - 8) / 2, GREEN, false);
        }
    }

    private static void drawRasterScan(GuiGraphics g, int x0, int x1, int top, int bottom, int ticks) {
        int rows = 5;
        int step = ticks / 26;
        int cycle = rows * 2 - 2;
        int p = step % cycle;
        int row = p < rows ? p : cycle - p;
        int dir = step % 2 == 0 ? 1 : -1;
        int yV = top + (int) ((row + 0.5) * (bottom - top) / rows);
        float frac = (ticks % 26) / 26.0f;
        int xS = dir > 0 ? x0 + (int) (frac * (x1 - x0)) : x1 - (int) (frac * (x1 - x0));
        g.fill(x1, Math.max(top, yV - 14), x1 + 1, Math.min(bottom, yV + 14), 0x996BFF6B);
        g.fill(Math.max(x0, xS - 20), yV, Math.min(x1, xS + 20), yV + 1, 0x996BFF6B);
    }

    private static void drawCornerBox(GuiGraphics g, int cx, int cy, int r, int len, int color, float alpha) {
        drawCornerBoxRect(g, cx, cy, r, r, len, color, alpha);
    }

    /** 宽高独立的四角框。 */
    private static void drawCornerBoxRect(GuiGraphics g, int cx, int cy, int hw, int hh,
                                          int len, int color, float alpha) {
        int col = ((int) (alpha * 255) << 24) | (color & 0x00FFFFFF);
        g.fill(cx - hw, cy - hh, cx - hw + len, cy - hh + 1, col);
        g.fill(cx - hw, cy - hh, cx - hw + 1, cy - hh + len, col);
        g.fill(cx + hw - len, cy - hh, cx + hw, cy - hh + 1, col);
        g.fill(cx + hw - 1, cy - hh, cx + hw, cy - hh + len, col);
        g.fill(cx - hw, cy + hh - 1, cx - hw + len, cy + hh, col);
        g.fill(cx - hw, cy + hh - len, cx - hw + 1, cy + hh, col);
        g.fill(cx + hw - len, cy + hh - 1, cx + hw, cy + hh, col);
        g.fill(cx + hw - 1, cy + hh - len, cx + hw, cy + hh, col);
    }

    /**
     * ACM 三波束模式的框与波束绘制：
     * 0 正前静止（小角框 + 中央短横线）；1 上下往复（±60° 竖带 + 旋转 [ 端帽）；
     * 2 3×3 光栅（±9° 网格 + 逐行扫掠短横线）。超出屏幕的部分按屏幕裁剪。
     */
    private static void drawAcmBeamOverlay(GuiGraphics g, Minecraft mc, int w, int h, float ui) {
        int cx = w / 2;
        int cy = h / 2;
        float pulse = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(mc.player.tickCount * 0.35));
        int col = ((int) (pulse * 255) << 24) | (GREEN & 0x00FFFFFF);
        String label = "ACM " + (int) RadarHud.effectiveRange() + " b";
        int beamLen = Math.max(12, Math.round(20 * ui));

        if (RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_VERTICAL) {
            int halfWpx = Math.max(10, Math.abs(azToScreenX(RadarHud.ACM_VERTICAL_HALF_AZ_DEG, w, h) - cx));
            int yA = elToScreenY(RadarHud.SCAN_ELEVATION_HALF_DEG, w, h);
            int yB = elToScreenY(-RadarHud.SCAN_ELEVATION_HALF_DEG, w, h);
            int top = Math.min(yA, yB);
            int bot = Math.max(yA, yB);
            int x0 = cx - halfWpx;
            int x1 = cx + halfWpx;
            int lineTop = Math.max(0, top);
            int lineBot = Math.min(h, bot);
            // 只保留上下四个方角端帽，不再画贯穿屏幕的长竖线。
            if (lineBot > lineTop) {
                drawRotatedBracket(g, x0, x1, lineTop, Math.max(8, Math.round(10 * ui)), true, col);
                drawRotatedBracket(g, x0, x1, lineBot, Math.max(8, Math.round(10 * ui)), false, col);
            }
            int hy = Math.max(0, Math.min(h - 1, elToScreenY(RadarHud.sweepElevationDeg, w, h)));
            g.fill(cx - beamLen, hy, cx + beamLen, hy + 1, col);
            g.drawString(mc.font, label, Math.max(4, x0),
                    Math.max(4, Math.min(h - 12, lineTop - 12)), GREEN, false);
            return;
        }

        double half = RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_RASTER
                ? RadarHud.ACM_RASTER_CELL_HALF_DEG * RadarHud.ACM_RASTER_ROWS
                : RadarHud.ACM_FIXED_HALF_DEG;
        int hw = Math.max(18, Math.abs(azToScreenX(half, w, h) - cx));
        int hh = Math.max(18, Math.abs(elToScreenY(half, w, h) - cy));
        drawCornerBoxRect(g, cx, cy, hw, hh,
                Math.max(6, Math.round(8 * ui)), GREEN, pulse);
        if (RadarHud.acmBeamIndex == RadarHud.ACM_BEAM_RASTER) {
            int bx = Math.max(0, Math.min(w - 1, azToScreenX(RadarHud.sweepAzimuthDeg, w, h)));
            int by = Math.max(0, Math.min(h - 1, elToScreenY(RadarHud.sweepElevationDeg, w, h)));
            g.fill(bx - beamLen, by, bx + beamLen, by + 1, col);
        }
        g.drawString(mc.font, label, cx - hw, Math.max(4, cy - hh - 14), GREEN, false);
    }

    /** 竖带端帽：上端 = "[" 顺时针旋转 90°（横杆 + 向下短脚）；下端为镜像。 */
    private static void drawRotatedBracket(GuiGraphics g, int x0, int x1, int y,
                                           int len, boolean top, int color) {
        int barTop = Math.max(0, Math.min(g.guiHeight() - 1, y));
        g.fill(Math.max(0, x0), barTop, Math.min(g.guiWidth(), x1), barTop + 1, color);
        int stubA = top ? barTop : Math.max(0, barTop - len);
        int stubB = top ? Math.min(g.guiHeight(), barTop + len) : barTop;
        if (stubB > stubA) {
            g.fill(Math.max(0, x0), stubA, Math.min(g.guiWidth(), x0 + 1), stubB, color);
            g.fill(Math.max(0, x1 - 1), stubA, Math.min(g.guiWidth(), x1), stubB, color);
        }
    }

    /** 扫描线使用线性方位映射，保证 ±120° 以内不会因 tan() 翻转。 */
    private static int scanScreenX(double az, int screenW) {
        double clamped = Math.max(-RadarHud.PANEL_AZIMUTH_DEG,
                Math.min(RadarHud.PANEL_AZIMUTH_DEG, az));
        return (int) Math.round(screenW / 2.0 + clamped / RadarHud.PANEL_AZIMUTH_DEG * screenW / 2.0);
    }

    private static int azToScreenX(double az, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        double fovY = Math.toRadians(Math.max(30, mc.options.fov().get()));
        double tanV = Math.tan(fovY / 2);
        double tanH = tanV * (double) screenW / screenH;
        return (int) (screenW / 2.0 + Math.tan(Math.toRadians(az)) / tanH * (screenW / 2.0));
    }

    private static int elToScreenY(double el, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        double fovY = Math.toRadians(Math.max(30, mc.options.fov().get()));
        double tanV = Math.tan(fovY / 2);
        return (int) (screenH / 2.0 - Math.tan(Math.toRadians(el)) / tanV * (screenH / 2.0));
    }

    // ===================== 投影与几何工具 =====================

    /** 世界坐标 → 屏幕坐标（近似，基于垂直 FOV） */
    public static float[] worldToScreen(Vec3 targetPos, Player player, int screenW, int screenH) {
        return worldToScreen(targetPos, player, screenW, screenH, 1.0F);
    }

    /** 带渲染帧插值的世界坐标 → 屏幕坐标。 */
    public static float[] worldToScreen(Vec3 targetPos, Player player,
                                        int screenW, int screenH, float partialTick) {
        if (player == null) return null;
        Vec3 eye = ClientRenderMath.eye(player, partialTick);
        Vec3 to = targetPos.subtract(eye);
        if (to.lengthSqr() < 1.0) return null;
        Vec3 dir = to.normalize();

        Vec3 look = ClientRenderMath.look(player, partialTick).normalize();
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = look.cross(worldUp);
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();

        double x = dir.dot(right);
        double y = dir.dot(up);
        double z = dir.dot(look);
        if (z <= 0.01) return null;

        Minecraft mc = Minecraft.getInstance();
        double fovY = Math.toRadians(Math.max(30, mc.options.fov().get()));
        double tanHalf = Math.tan(fovY / 2);
        double aspect = (double) screenW / screenH;
        double ndcX = (x / z) / (tanHalf * aspect);
        double ndcY = (y / z) / tanHalf;
        return new float[]{
                (float) ((ndcX * 0.5 + 0.5) * screenW),
                (float) ((0.5 - ndcY * 0.5) * screenH)
        };
    }

    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    public static void drawCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        int x = r, y = 0, err = 1 - r;
        while (x >= y) {
            plot8(g, cx, cy, x, y, color);
            y++;
            if (err < 0) { err += 2 * y + 1; }
            else { x--; err += 2 * (y - x) + 1; }
        }
    }

    private static void plot8(GuiGraphics g, int cx, int cy, int x, int y, int color) {
        g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
        g.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
        g.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
        g.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
        g.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
        g.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
        g.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
        g.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);
    }

    /** 绘制指定线宽的圆环。 */
    private static void drawRing(GuiGraphics g, int cx, int cy, int r, int width, int color) {
        width = Math.max(1, width);
        int lo = r - (width - 1) / 2;
        int hi = r + width / 2;
        for (int rr = lo; rr <= hi; rr++) {
            if (rr > 0) drawCircle(g, cx, cy, rr, color);
        }
    }

    /** 从 12 点方向顺时针填充的倒计时弧。 */
    private static void drawProgressArc(GuiGraphics g, int cx, int cy, int r,
                                        float progress, int color, int width) {
        progress = Math.max(0f, Math.min(1f, progress));
        int steps = 96;
        int filled = Math.round(steps * progress);
        width = Math.max(1, width);
        int lo = r - (width - 1) / 2;
        int hi = r + width / 2;
        for (int i = 0; i < filled; i++) {
            double angle = -Math.PI / 2 + (i / (double) steps) * 2 * Math.PI;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            for (int rr = lo; rr <= hi; rr++) {
                int x = (int) Math.round(cx + cos * rr);
                int y = (int) Math.round(cy + sin * rr);
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }
}
