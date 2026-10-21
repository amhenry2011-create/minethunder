package com.minethunder.client;

import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileSpec;
import com.minethunder.radar.RadarContact;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * OP 专用传感器调试视觉：雷达波束、回波、导弹、干扰物及其实时参数。
 * 用中文标签，便于调试。
 */
public final class SensorDebugRenderer {
    private SensorDebugRenderer() {}

    private static final int PANEL_BG = 0xB0000000;
    private static final int GREEN = 0xFF66FF66;
    private static final int DIM_GREEN = 0x8066FF66;
    private static final int AMBER = 0xFFFFC040;
    private static final int RED = 0xFFFF5555;
    private static final int CYAN = 0xFF66FFFF;
    private static final int BLUE = 0xFF6699FF;

    public static void render(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        drawHeader(g, mc, screenW, screenH);

        if (RadarHud.radarOn) {
            drawRadarFan(g, screenW, screenH);
        }

        drawContacts(g, mc, screenW, screenH);
        drawMissiles(g, mc, screenW, screenH);
        drawCountermeasures(g, mc, screenW, screenH);
    }

    private static void drawHeader(GuiGraphics g, Minecraft mc, int screenW, int screenH) {
        String[] lines = {
                "传感器视觉（仅 OP）",
                "雷达：" + (RadarHud.radarOn ? "开启" : "关闭")
                        + "  模式：" + RadarHud.currentMode.code()
                        + " " + RadarHud.currentMode.getDisplayName(),
                String.format("波束：方位 ±%.0f° / 仰角 ±%.0f°  最大距离：%.0f b",
                        RadarHud.effectiveScanHalfDeg(), RadarHud.effectiveScanElevationHalfDeg(),
                        RadarHud.maxRange),
                String.format("回波：%d  显示距离：%.0f b  引导线：%s",
                        RadarHud.contacts.size(), RadarHud.displayRange,
                        RadarHud.guidanceLineActive ? "开" : "关")
        };
        drawTag(g, 8, 8, lines, GREEN, screenW, screenH);
    }

    private static void drawRadarFan(GuiGraphics g, int screenW, int screenH) {
        int cx = screenW / 2;
        int cy = screenH / 2;
        int radius = Math.min(screenW, screenH) / 3;
        for (int deg = -60; deg <= 60; deg += 15) {
            int ex = cx + (int) (Math.sin(Math.toRadians(deg)) * radius);
            int ey = cy - (int) (Math.cos(Math.toRadians(deg)) * radius);
            drawLine(g, cx, cy, ex, ey, 0x3066FF66);
        }
        int prevX = 0;
        int prevY = 0;
        for (int deg = -60; deg <= 60; deg += 5) {
            int x = cx + (int) (Math.sin(Math.toRadians(deg)) * radius);
            int y = cy - (int) (Math.cos(Math.toRadians(deg)) * radius);
            if (deg > -60) drawLine(g, prevX, prevY, x, y, 0x5066FF66);
            prevX = x;
            prevY = y;
        }
        drawText(g, "雷达波束（方位）", cx - 54, cy - radius - 14, DIM_GREEN);
    }

    private static void drawContacts(GuiGraphics g, Minecraft mc, int screenW, int screenH) {
        int cx = screenW / 2;
        int cy = screenH / 2;
        int count = 0;
        for (RadarContact contact : RadarHud.contacts) {
            if (count++ >= 12) break;
            float[] p = RadarHudRenderer.worldToScreen(contact.position, mc.player, screenW, screenH);
            if (p == null || p[0] < 0 || p[0] > screenW || p[1] < 0 || p[1] > screenH) continue;
            int x = (int) p[0];
            int y = (int) p[1];
            int color = contact.occluded || contact.inDopplerNotch ? AMBER : GREEN;
            drawLine(g, cx, cy, x, y, contact.occluded || contact.inDopplerNotch ? 0x50FFC040 : 0x5066FF66);
            drawBox(g, x, y, 5, color);
            String[] lines = {
                    "回波：" + contact.entity.getName().getString(),
                    String.format("距离：%.1f b", contact.distance),
                    String.format("方位：%.1f°  仰角：%.1f°", contact.azimuthDeg, contact.elevationDeg),
                    String.format("径向速度：%.1f b/s", contact.radialVelocity),
                    "遮挡：" + (contact.occluded ? "是" : "否")
                            + "  Notch：" + (contact.inDopplerNotch ? "是" : "否")
            };
            drawTag(g, x + 9, y + 9, lines, color, screenW, screenH);
        }
    }

    private static void drawMissiles(GuiGraphics g, Minecraft mc, int screenW, int screenH) {
        int count = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof MissileArrow missile)) continue;
            if (mc.player.distanceTo(entity) > 512.0) continue;
            if (count++ >= 8) break;

            float[] p = RadarHudRenderer.worldToScreen(entity.getBoundingBox().getCenter(),
                    mc.player, screenW, screenH);
            if (p == null) continue;
            int x = (int) p[0];
            int y = (int) p[1];
            Entity target = findTarget(mc, missile);
            if (target != null) {
                float[] tp = RadarHudRenderer.worldToScreen(target.getBoundingBox().getCenter(),
                        mc.player, screenW, screenH);
                if (tp != null) {
                    drawLine(g, x, y, (int) tp[0], (int) tp[1], 0x80FFC040);
                    drawBox(g, (int) tp[0], (int) tp[1], 4, AMBER);
                }
            }
            drawBox(g, x, y, 6, RED);
            drawLine(g, x - 8, y, x + 8, y, RED);
            drawLine(g, x, y - 8, x, y + 8, RED);

            MissileSpec spec = missile.getSpec();
            double speed = missile.getDeltaMovement().length();
            double remaining = Math.max(0.0, spec.timeLife - missile.getAgeTicks() / 20.0);
            String kind = switch (spec.guidance) {
                case IR -> "红外";
                case SARH -> "半主动雷达";
                case ARH -> "主动雷达";
            };
            String[] lines = {
                    "导弹：" + kind + " " + spec.name,
                    String.format("速度：%.0f b/s  高度：%.0f b", speed, entity.getY()),
                    String.format("剩余制导：%.1f s / %.1f s", remaining, spec.timeLife),
                    String.format("飞行距离：%.1f b", missile.getDistanceFlown()),
                    String.format("近炸引信：%.1f b", spec.proximityFuseRadius),
                    "目标：" + (target == null ? "无" : target.getName().getString())
            };
            drawTag(g, x + 10, y + 10, lines, RED, screenW, screenH);
        }
    }

    private static void drawCountermeasures(GuiGraphics g, Minecraft mc, int screenW, int screenH) {
        int count = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof CountermeasureEntity cm)) continue;
            if (mc.player.distanceTo(entity) > 512.0) continue;
            if (count++ >= 8) break;

            float[] p = RadarHudRenderer.worldToScreen(entity.getBoundingBox().getCenter(),
                    mc.player, screenW, screenH);
            if (p == null) continue;
            int x = (int) p[0];
            int y = (int) p[1];
            Countermeasure kind = cm.type();
            int color = switch (kind) {
                case CHAFF -> CYAN;
                case FLARE -> AMBER;
                case ECM -> BLUE;
            };
            drawDiamond(g, x, y, 6, color);
            String[] lines = {
                    "干扰物：" + switch (kind) {
                        case CHAFF -> "箔条";
                        case FLARE -> "热焰弹";
                        case ECM -> "ECM";
                    },
                    String.format("剩余：%.1f s", cm.getRemainingTicks() / 20.0),
                    String.format("速度：%.1f b/s", entity.getDeltaMovement().length()),
                    String.format("距本机：%.1f b", mc.player.distanceTo(entity)),
                    "欺骗：" + (kind == Countermeasure.FLARE ? "红外" : "雷达")
            };
            drawTag(g, x + 10, y + 10, lines, color, screenW, screenH);
        }
    }

    @Nullable
    private static Entity findTarget(Minecraft mc, MissileArrow missile) {
        UUID id = missile.getTargetUuid();
        if (id == null) return null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) return entity;
        }
        return null;
    }

    private static void drawDiamond(GuiGraphics g, int x, int y, int r, int color) {
        drawLine(g, x - r, y, x, y - r, color);
        drawLine(g, x, y - r, x + r, y, color);
        drawLine(g, x + r, y, x, y + r, color);
        drawLine(g, x, y + r, x - r, y, color);
    }

    private static void drawBox(GuiGraphics g, int x, int y, int r, int color) {
        g.fill(x - r, y - r, x + r, y - r + 1, color);
        g.fill(x - r, y + r - 1, x + r, y + r, color);
        g.fill(x - r, y - r, x - r + 1, y + r, color);
        g.fill(x + r - 1, y - r, x + r, y + r, color);
    }

    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) return;
        for (int i = 0; i <= steps; i++) {
            int x = x1 + dx * i / steps;
            int y = y1 + dy * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void drawTag(GuiGraphics g, int x, int y, String[] lines,
                                int color, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        float scale = 0.7f;
        int textWidth = 0;
        for (String line : lines) textWidth = Math.max(textWidth, mc.font.width(line));
        int boxW = (int) (textWidth * scale) + 6;
        int boxH = (int) (lines.length * 9 * scale) + 5;
        x = clamp(x, 2, screenW - boxW - 2);
        y = clamp(y, 2, screenH - boxH - 2);
        g.fill(x, y, x + boxW, y + boxH, PANEL_BG);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], (int) ((x + 3) / scale),
                    (int) ((y + 3 + i * 9) / scale), color, false);
        }
        g.pose().popPose();
    }

    private static void drawText(GuiGraphics g, String text, int x, int y, int color) {
        g.drawString(Minecraft.getInstance().font, text, x, y, color, false);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
