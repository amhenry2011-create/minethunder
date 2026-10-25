package com.minethunder.client;

import com.minethunder.plane.PlaneEntity;
import com.minethunder.plane.PlaneTypeSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * 简单飞机（Simple Planes）主画面专属飞行 HUD 与微软模拟飞行（MSFS）冷舱启动检查单渲染器。
 *
 * 严格遵循用户确认规范：
 * - 所有飞行仪表（俯仰梯、速度矢量 FPV、空速带、高度带、迎角/G 值）与冷舱检查单仅绘制在【主画面】；
 * - 绝不触碰或遮挡左下角雷达面板。
 */
public final class PlaneHudRenderer {
    private PlaneHudRenderer() {}

    private static final int HUD_GREEN  = 0xDD00FF66;
    private static final int HUD_DIM    = 0x9900CC52;
    private static final int HUD_AMBER  = 0xEEFFAA00;
    private static final int HUD_RED    = 0xEEFF3333;
    private static final int HUD_WHITE  = 0xDDFFFFFF;
    private static final int PANEL_BG   = 0x8808120C;

    public static void render(GuiGraphics g, int screenW, int screenH, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof PlaneEntity plane)) {
            return;
        }

        Font font = mc.font;
        float pitch = Mth.lerp(partialTick, plane.prevPitchDeg, plane.getPitchDeg());
        float roll = Mth.rotLerp(partialTick, plane.prevRollDeg, plane.getRollDeg());
        float yaw = Mth.rotLerp(partialTick, plane.prevYawDeg, plane.getYawDeg());

        int cx = screenW / 2;
        int cy = screenH / 2;

        // 1. 大过载黑视/红视渐晕 (G-LOC Vignette，全机乘员共享机体过载)
        renderGLocOverlay(g, screenW, screenH, plane);

        // 后座乘员（WSO/乘客）专注独立操作雷达与武器，不显示前座飞行员专属平显与冷舱开关面板
        if (plane.getControllingPassenger() != mc.player) {
            return;
        }

        // 2. 右上角冷舱启动状态面板 & 机务构型栏 (即使电瓶未开也显示机械开关状态)
        renderCockpitSystemsPanel(g, font, screenW, screenH, plane);

        // 3. 当主电瓶未接通时，主画面中央平显不亮，仅提示按 [B] 接通电瓶
        if (!plane.isMasterBatteryOn()) {
            String msg = "AVIONICS / HUD OFF — 按 [B] 接通机载主电瓶 (MASTER BATTERY)";
            int tw = font.width(msg);
            g.fill(cx - tw / 2 - 6, cy - 38, cx + tw / 2 + 6, cy - 22, PANEL_BG);
            g.drawString(font, msg, cx - tw / 2, cy - 34, HUD_AMBER, true);
            return;
        }

        // 4. 主画面中央衍射平显（Waterline、滚转刻度、俯仰刻度梯、速度矢量 FPV）
        renderCenterAvionicsHud(g, font, cx, cy, pitch, roll, yaw, plane);

        // 5. 左侧空速带 (IAS Tape) + 节流阀/转速/排气温度
        renderAirspeedAndEngineTape(g, font, cx - 108, cy, plane);

        // 6. 右侧高度带 (ALT & AGL Tape) + 升降率/航向/迎角/过载
        renderAltitudeAndAeroTape(g, font, cx + 80, cy, yaw, plane);

        // 7. 失速 (STALL) 与超 G (OVER-G) / 近地告警
        renderCriticalWarnings(g, font, cx, cy, plane);
    }

    private static void renderGLocOverlay(GuiGraphics g, int w, int h, PlaneEntity plane) {
        float gLoad = plane.getGLoad();
        PlaneTypeSpec spec = plane.getPlaneType();
        if (gLoad > spec.maxPositiveG() * 0.85f) {
            float severity = Mth.clamp((gLoad - spec.maxPositiveG() * 0.85f) / 3.0f, 0.0f, 0.72f);
            int alpha = (int) (severity * 255.0f);
            g.fill(0, 0, w, h, (alpha << 24));
        } else if (gLoad < spec.maxNegativeG() * 0.8f) {
            float severity = Mth.clamp((spec.maxNegativeG() * 0.8f - gLoad) / 2.2f, 0.0f, 0.60f);
            int alpha = (int) (severity * 255.0f);
            g.fill(0, 0, w, h, (alpha << 24) | 0x550000);
        }
    }

    private static void renderCenterAvionicsHud(GuiGraphics g, Font font, int cx, int cy,
                                                float pitch, float roll, float yaw,
                                                PlaneEntity plane) {
        // 固定机头基准符号 (Waterline 'W')
        g.fill(cx - 14, cy, cx - 5, cy + 1, HUD_GREEN);
        g.fill(cx + 5, cy, cx + 14, cy + 1, HUD_GREEN);
        g.fill(cx - 5, cy, cx - 3, cy + 4, HUD_GREEN);
        g.fill(cx + 3, cy, cx + 5, cy + 4, HUD_GREEN);
        g.fill(cx - 3, cy + 3, cx + 3, cy + 4, HUD_GREEN);

        // 俯仰刻度梯 (Pitch Ladder)：随滚转角旋转，随俯仰角平移
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(-roll));

        float pxPerDeg = 2.8f;
        for (int deg = -30; deg <= 30; deg += 5) {
            float dy = (pitch - deg) * pxPerDeg;
            if (Math.abs(dy) > 54.0f) continue;
            int y = Math.round(dy);

            if (deg == 0) {
                // 人工地平线 (Horizon Line)
                g.fill(-42, y, -12, y + 1, HUD_GREEN);
                g.fill(12, y, 42, y + 1, HUD_GREEN);
            } else if (deg > 0) {
                // 正俯仰实线梯
                g.fill(-26, y, -10, y + 1, HUD_GREEN);
                g.fill(10, y, 26, y + 1, HUD_GREEN);
                g.fill(-26, y, -25, y + 4, HUD_GREEN);
                g.fill(25, y, 26, y + 4, HUD_GREEN);
                if (deg % 10 == 0) {
                    String s = Integer.toString(deg);
                    g.drawString(font, s, -26 - font.width(s) - 2, y - 3, HUD_GREEN, false);
                }
            } else {
                // 负俯仰分段虚线梯
                g.fill(-26, y, -19, y + 1, HUD_DIM);
                g.fill(-16, y, -10, y + 1, HUD_DIM);
                g.fill(10, y, 16, y + 1, HUD_DIM);
                g.fill(19, y, 26, y + 1, HUD_DIM);
                g.fill(-26, y - 4, -25, y + 1, HUD_DIM);
                g.fill(25, y - 4, 26, y + 1, HUD_DIM);
                if (deg % 10 == 0) {
                    String s = Integer.toString(deg);
                    g.drawString(font, s, -26 - font.width(s) - 2, y - 3, HUD_DIM, false);
                }
            }
        }
        pose.popPose();

        // 真实飞行路径矢量标 (FPV / Velocity Vector)：根据迎角 AoA 下沉偏移
        if (plane.getAirspeedMps() > 2.5) {
            int fpvY = cy + Math.round(Mth.clamp(plane.getAoaDeg() * pxPerDeg, -48.0f, 48.0f));
            int fpvColor = plane.isStalling() ? HUD_RED : HUD_GREEN;
            // FPV 小圆与左右小翼、垂直尾线
            g.renderOutline(cx - 4, fpvY - 4, 9, 9, fpvColor);
            g.fill(cx - 10, fpvY, cx - 4, fpvY + 1, fpvColor);
            g.fill(cx + 5, fpvY, cx + 11, fpvY + 1, fpvColor);
            g.fill(cx, fpvY - 8, cx + 1, fpvY - 4, fpvColor);
        }

        // 顶部磁航向标尺 (Heading Tape)
        int compassHeading = ((Math.round(yaw) % 360) + 360) % 360;
        String hdgText = String.format(Locale.ROOT, "HDG %03d°", compassHeading);
        int hw = font.width(hdgText);
        g.fill(cx - hw / 2 - 5, cy - 76, cx + hw / 2 + 5, cy - 63, PANEL_BG);
        g.renderOutline(cx - hw / 2 - 5, cy - 76, hw + 10, 13, HUD_GREEN);
        g.drawString(font, hdgText, cx - hw / 2, cy - 73, HUD_GREEN, false);
    }

    private static void renderAirspeedAndEngineTape(GuiGraphics g, Font font, int x, int cy,
                                                    PlaneEntity plane) {
        double iasMps = plane.getAirspeedMps();
        double iasKnots = iasMps * 1.94384;
        double stallMps = plane.getCurrentStallSpeedMps();
        int throttlePct = Math.round(plane.getThrottle() * 100.0f);
        int rpmPct = Math.round(plane.getRpmNormalized() * 100.0f);
        int egt = Math.round(plane.getEgtCelsius());

        // 空速框
        g.fill(x - 42, cy - 52, x + 20, cy + 52, PANEL_BG);
        g.renderOutline(x - 42, cy - 52, 62, 104, HUD_GREEN);

        int speedColor = (iasMps < stallMps && !plane.onGround()) ? HUD_RED : HUD_GREEN;
        g.drawString(font, String.format(Locale.ROOT, "IAS %.0f m/s", iasMps), x - 38, cy - 46, speedColor, false);
        g.drawString(font, String.format(Locale.ROOT, "%.0f kt", iasKnots), x - 38, cy - 35, HUD_DIM, false);
        g.drawString(font, String.format(Locale.ROOT, "Vs  %.0f m/s", stallMps), x - 38, cy - 22, HUD_AMBER, false);

        // 节流阀与加力显示
        String thrLabel = plane.isAfterburnerActive()
                ? "THR AB " + throttlePct + "%"
                : "THR " + throttlePct + "%";
        int thrColor = plane.isAfterburnerActive() ? HUD_AMBER : HUD_GREEN;
        g.drawString(font, thrLabel, x - 38, cy - 6, thrColor, false);

        // 节流阀竖条
        int barH = 38;
        int fillH = Mth.clamp(Math.round((plane.getThrottle() / 1.10f) * barH), 0, barH);
        g.renderOutline(x + 10, cy - 4, 6, barH + 2, HUD_DIM);
        g.fill(x + 11, cy - 3 + (barH - fillH), x + 15, cy - 3 + barH, thrColor);

        g.drawString(font, "RPM " + rpmPct + "%", x - 38, cy + 10, HUD_GREEN, false);
        g.drawString(font, "EGT " + egt + "°C", x - 38, cy + 22,
                egt > plane.getEngineType().nominalMaxEgt() ? HUD_RED : HUD_DIM, false);
        g.drawString(font, String.format(Locale.ROOT, "IR  %.0f", plane.getIrSignatureHeat()),
                x - 38, cy + 35, plane.isAfterburnerActive() ? HUD_RED : HUD_DIM, false);
    }

    private static void renderAltitudeAndAeroTape(GuiGraphics g, Font font, int x, int cy,
                                                  float yaw, PlaneEntity plane) {
        double asl = plane.getY();
        double agl = plane.getRadarAltitudeAgl();
        double vsi = plane.getDeltaMovement().y * 20.0;
        float aoa = plane.getAoaDeg();
        float gLoad = plane.getGLoad();

        g.fill(x, cy - 52, x + 68, cy + 52, PANEL_BG);
        g.renderOutline(x, cy - 52, 68, 104, HUD_GREEN);

        g.drawString(font, String.format(Locale.ROOT, "ALT %.0f m", asl), x + 4, cy - 46, HUD_GREEN, false);
        int aglColor = (!plane.isGearDown() && agl < 12.0 && vsi < -2.0) ? HUD_RED : HUD_DIM;
        g.drawString(font, String.format(Locale.ROOT, "AGL %.0f m", agl), x + 4, cy - 35, aglColor, false);
        g.drawString(font, String.format(Locale.ROOT, "VSI %+.1f", vsi), x + 4, cy - 22, HUD_GREEN, false);

        int aoaColor = Math.abs(aoa) > 14.0f ? HUD_RED : (Math.abs(aoa) > 10.0f ? HUD_AMBER : HUD_GREEN);
        g.drawString(font, String.format(Locale.ROOT, "AoA %+.1f°", aoa), x + 4, cy - 6, aoaColor, false);

        int gColor = (gLoad > plane.getPlaneType().maxPositiveG() * 0.9f
                || gLoad < plane.getPlaneType().maxNegativeG() * 0.9f) ? HUD_RED : HUD_GREEN;
        g.drawString(font, String.format(Locale.ROOT, "G   %+.1fG", gLoad), x + 4, cy + 8, gColor, false);
        g.drawString(font, String.format(Locale.ROOT, "TRM %+.1f°", plane.getElevatorTrimDeg()),
                x + 4, cy + 22, HUD_DIM, false);
        g.drawString(font, String.format(Locale.ROOT, "HP  %.0f%%",
                        (plane.getStructuralHealth() / plane.getPlaneType().maxHealth()) * 100.0f),
                x + 4, cy + 35, HUD_GREEN, false);
    }

    private static void renderCockpitSystemsPanel(GuiGraphics g, Font font, int screenW, int screenH,
                                                  PlaneEntity plane) {
        int boxW = 192;
        int boxH = plane.isEngineRunning() ? 66 : 94;
        int x = screenW - boxW - 8;
        int y = 8;

        g.fill(x, y, x + boxW, y + boxH, PANEL_BG);
        g.renderOutline(x, y, boxW, boxH, plane.isEngineRunning() ? HUD_GREEN : HUD_AMBER);

        String title = plane.getPlaneType().displayName();
        g.drawString(font, title, x + 6, y + 5, HUD_WHITE, false);

        // 第一行：冷舱四电门状态
        String bat = "[B]BAT:" + (plane.isMasterBatteryOn() ? "ON" : "OFF");
        String fuel = "[F]FUEL:" + (plane.isFuelPumpOn() ? "ON" : "CUT");
        String mag = "[G]MAG:" + plane.getMagnetoLabel();
        g.drawString(font, bat, x + 6, y + 18, plane.isMasterBatteryOn() ? HUD_GREEN : HUD_RED, false);
        g.drawString(font, fuel, x + 64, y + 18, plane.isFuelPumpOn() ? HUD_GREEN : HUD_RED, false);
        g.drawString(font, mag, x + 126, y + 18, plane.getMagnetoState() > 0 ? HUD_GREEN : HUD_RED, false);

        // 第二行：起落架、襟翼、驻车刹车
        String gearStatus = plane.getGearProgress() > 0.95f ? "DN"
                : (plane.getGearProgress() < 0.05f ? "UP" : "MOV");
        int gearColor = "MOV".equals(gearStatus) ? HUD_AMBER : (plane.isGearDown() ? HUD_GREEN : HUD_DIM);
        String flaps = "[U/V]FLAP:" + plane.getFlapDegrees() + "°";
        String park = "[P]BRK:" + (plane.isParkingBrakeOn() ? "SET" : "REL");

        g.drawString(font, "[K]GEAR:" + gearStatus, x + 6, y + 31, gearColor, false);
        g.drawString(font, flaps, x + 68, y + 31, plane.getFlapStage() > 0 ? HUD_AMBER : HUD_GREEN, false);
        g.drawString(font, park, x + 134, y + 31, plane.isParkingBrakeOn() ? HUD_AMBER : HUD_GREEN, false);

        // 第三行：发动机运行状态或冷舱启动下一步引导
        if (plane.isEngineRunning()) {
            String engLine = "ENG: RUNNING (" + plane.getEngineType().id().toUpperCase(Locale.ROOT) + ") | [/]配平";
            g.drawString(font, engLine, x + 6, y + 46, HUD_GREEN, false);
        } else {
            g.drawString(font, "COLD & DARK 冷舱启动检查单:", x + 6, y + 46, HUD_AMBER, false);
            String stepHint;
            if (!plane.isMasterBatteryOn()) {
                stepHint = "▶ 1. 按 [B] 开启主电瓶总开关 (Master Bat)";
            } else if (!plane.isFuelPumpOn()) {
                stepHint = "▶ 2. 按 [F] 开启燃油增压泵与供油阀";
            } else if (plane.getMagnetoState() < 3) {
                stepHint = "▶ 3. 按 [G] 将磁电机切至 BOTH";
            } else if (plane.getThrottle() > 0.45f) {
                stepHint = "▶ 4. 按 [Y/PgDn] 将节流阀收至慢车 (<40%)";
            } else {
                int crankPct = Math.round((plane.getRpmNormalized() / 0.21f) * 100.0f);
                stepHint = String.format(Locale.ROOT, "▶ 4. 按住 [I] 起动机点火 (%d%%)", Math.min(100, crankPct));
            }
            g.drawString(font, stepHint, x + 6, y + 59, HUD_WHITE, false);
            g.drawString(font, "起飞前：按 [P] 松驻车，[V] 放15°起飞襟翼", x + 6, y + 73, HUD_DIM, false);
        }
    }

    private static void renderCriticalWarnings(GuiGraphics g, Font font, int cx, int cy,
                                               PlaneEntity plane) {
        boolean blink = (System.currentTimeMillis() / 220L) % 2L == 0L;
        if (plane.isStalling() && blink) {
            String stallMsg = "⚠ STALL WARNING / 临界迎角失速！请推杆低头 [W] 并加推力 ⚠";
            int sw = font.width(stallMsg);
            g.fill(cx - sw / 2 - 6, cy + 56, cx + sw / 2 + 6, cy + 70, 0xCC330000);
            g.renderOutline(cx - sw / 2 - 6, cy + 56, sw + 12, 14, HUD_RED);
            g.drawString(font, stallMsg, cx - sw / 2, cy + 59, HUD_RED, true);
        } else if ((plane.getGLoad() > plane.getPlaneType().maxPositiveG()
                || plane.getGLoad() < plane.getPlaneType().maxNegativeG()) && blink) {
            String gMsg = String.format(Locale.ROOT, "⚠ OVER-G LIMIT (%.1fG) 结构过载告警 ⚠", plane.getGLoad());
            int gw = font.width(gMsg);
            g.fill(cx - gw / 2 - 6, cy + 56, cx + gw / 2 + 6, cy + 70, 0xCC330000);
            g.drawString(font, gMsg, cx - gw / 2, cy + 59, HUD_RED, true);
        } else if (!plane.onGround() && !plane.isGearDown()
                && plane.getRadarAltitudeAgl() < 10.0
                && plane.getDeltaMovement().y * 20.0 < -3.0 && blink) {
            String gpws = "⚠ PULL UP / 地形接近告警 (GEAR UP) [K] ⚠";
            int pw = font.width(gpws);
            g.fill(cx - pw / 2 - 6, cy + 56, cx + pw / 2 + 6, cy + 70, 0xCC331800);
            g.drawString(font, gpws, cx - pw / 2, cy + 59, HUD_AMBER, true);
        }
    }
}
