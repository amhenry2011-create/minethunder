package com.minethunder.client;

import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import com.minethunder.missile.IrHeat;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileSpec;
import com.minethunder.radar.RadarContact;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

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
            drawRadarBeams(g, mc, screenW, screenH);
        }

        drawContacts(g, mc, screenW, screenH);
        drawMissiles(g, mc, screenW, screenH);
        drawCountermeasures(g, mc, screenW, screenH);
    }

    private static void drawHeader(GuiGraphics g, Minecraft mc, int screenW, int screenH) {
        Entity irTarget = RadarHud.seekerLockedEntity != null
                ? RadarHud.seekerLockedEntity : RadarHud.irCandidate;
        String irTargetLine = irTarget == null ? "无"
                : irTarget.getName().getString()
                + String.format("  热量 %.0f / 阈值 %.0f", IrHeat.heat(irTarget),
                IrHeat.THRESHOLD);
        String irStatus = RadarHud.selectedMissile == MissileSpec.Guidance.IR
                ? (RadarHud.seekerEnabled ? "开启" : "关闭") : "未选择";
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
                        RadarHud.guidanceLineActive ? "开" : "关"),
                "IR：" + irStatus + "  状态：" + (RadarHud.irStatus == null ? "" : RadarHud.irStatus)
                        + "  锁定：" + (RadarHud.irLocked ? "是" : "否")
                        + "  tick：" + RadarHud.irLockTicks,
                "IR 候选：" + irTargetLine + String.format("  容错半径：%d×UI",
                        RadarHud.IR_LOCK_RING_BASE),
                "IR FOV/离轴：" + (RadarHud.selectedSpec == null ? "-"
                        : String.format("%.0f° / %.0f°", RadarHud.selectedSpec.seekerFovDeg,
                        RadarHud.selectedSpec.lockAngleMaxDeg))
        };
        drawTag(g, 8, 8, lines, GREEN, screenW, screenH);
    }

    /** 所有雷达模式共用的四棱锥波束；填充面为淡绿色半透明。 */
    private static void drawRadarBeams(GuiGraphics g, Minecraft mc, int screenW, int screenH) {
        if (mc.player == null) return;
        Player player = mc.player;
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = look.cross(worldUp);
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();
        Vec3 origin = player.getEyePosition().add(look.scale(0.65));
        Vec3[] near = corners(origin, right, up, 0.24);

        RadarMode mode = RadarHud.currentMode;
        boolean tracking = mode == RadarMode.STT;
        boolean acq = RadarHud.lockAcquiring;
        boolean useSearch = !tracking && !(acq && RadarHud.lockedEntity != null);

        if (tracking) {
            Entity target = RadarHud.lockedEntity;
            if (target != null && target.isAlive()) {
                drawBeamToTarget(g, player, near, target, screenW, screenH, 0x32A8FFB0, 0xFF9FFF9F);
            }
            return;
        }

        if (useSearch) {
            double yaw = player.getYRot() + RadarHud.sweepAzimuthDeg;
            double pitch = player.getXRot() - RadarHud.sweepElevationDeg;
            Vec3 dir = Vec3.directionFromRotation((float) pitch, (float) yaw).normalize();
            double range = Math.max(1.0, Math.min(RadarHud.effectiveRange(), 220.0));
            Vec3 farCenter = origin.add(dir.scale(range));
            double half = Math.max(0.45, Math.min(5.0, range * 0.012));
            float[] farScreen = RadarHudRenderer.worldToScreen(farCenter, player, screenW, screenH);
            boolean visible = farScreen != null
                    && farScreen[0] >= 0 && farScreen[0] <= screenW
                    && farScreen[1] >= 0 && farScreen[1] <= screenH;
            if (!visible || !drawBeamToPoint(g, player, near, farCenter, dir, half,
                    screenW, screenH, 0x4066FF66, 0xB066FF66)) {
                drawScanEdgeMarker(g, screenW, screenH, RadarHud.sweepAzimuthDeg);
            }
        }

        drawContactBeams(g, mc, player, near, screenW, screenH, mode);

        if (acq && RadarHud.lockedEntity != null && RadarHud.lockedEntity.isAlive()) {
            drawBeamToTarget(g, player, near, RadarHud.lockedEntity, screenW, screenH,
                    0x3448FF88, 0xFF9FFF9F);
        }
    }

    private static void drawContactBeams(GuiGraphics g, Minecraft mc, Player player, Vec3[] near,
                                         int screenW, int screenH, RadarMode mode) {
        if (mc.level == null || mc.player == null) return;
        int tick = mc.player.tickCount;
        int count = 0;
        for (RadarEcho echo : RadarHud.echoes.values()) {
            if (count >= 6) break;
            if (echo.expired(tick)) continue;
            Entity target = mc.level.getEntity(echo.entityId);
            if (target == null || !target.isAlive()) continue;
            var targetClass = RadarCore.classify(target);
            if (targetClass == null || !mode.accepts(targetClass)) continue;
            boolean selected = echo.entityId == RadarHud.selectedEchoId;
            drawBeamToTarget(g, player, near, target, screenW, screenH,
                    selected ? 0x7048FF88 : 0x4048FF88,
                    selected ? 0xFFFFD070 : 0xB07CFF9E);
            count++;
        }
    }

    private static void drawScanEdgeMarker(GuiGraphics g, int screenW, int screenH,
                                           double sweepAzimuthDeg) {
        int side = sweepAzimuthDeg >= 0 ? 1 : -1;
        int baseX = side > 0 ? screenW - 24 : 24;
        int tipX = side > 0 ? screenW - 6 : 6;
        int cy = screenH / 2;
        drawLine(g, baseX, cy - 8, tipX, cy, 0xFFFFC040);
        drawLine(g, baseX, cy + 8, tipX, cy, 0xFFFFC040);
        drawLine(g, screenW / 2, cy, baseX, cy, 0x4066FF66);
    }

    private static Vec3[] corners(Vec3 center, Vec3 right, Vec3 up, double half) {
        Vec3 r = right.scale(half);
        Vec3 u = up.scale(half);
        return new Vec3[]{
                center.subtract(r).subtract(u),
                center.add(r).subtract(u),
                center.add(r).add(u),
                center.subtract(r).add(u)
        };
    }

    private static boolean drawBeamToTarget(GuiGraphics g, Player player, Vec3[] near,
                                            Entity target, int screenW, int screenH,
                                            int fill, int edge) {
        Vec3 center = target.getBoundingBox().getCenter();
        Vec3 dir = center.subtract(player.getEyePosition()).normalize();
        double half = Math.max(0.5, Math.max(target.getBbWidth(), target.getBbHeight()) * 0.5);
        return drawBeamToPoint(g, player, near, center, dir, half, screenW, screenH, fill, edge);
    }

    private static boolean drawBeamToPoint(GuiGraphics g, Player player, Vec3[] near,
                                           Vec3 farCenter, Vec3 dir, double farHalf,
                                           int screenW, int screenH, int fill, int edge) {
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = dir.cross(worldUp);
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(dir).normalize();
        Vec3[] far = corners(farCenter, right, up, farHalf);

        float[][] n = projectAll(player, near, screenW, screenH);
        float[][] f = projectAll(player, far, screenW, screenH);
        if (n == null || f == null) return false;

        // 四个侧面 + 远端面，使用半透明填充。
        fillQuad(g, n[0], n[1], f[1], f[0], fill);
        fillQuad(g, n[1], n[2], f[2], f[1], fill);
        fillQuad(g, n[2], n[3], f[3], f[2], fill);
        fillQuad(g, n[3], n[0], f[0], f[3], fill);
        fillQuad(g, f[0], f[1], f[2], f[3], fill);

        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            drawLine(g, (int) n[i][0], (int) n[i][1], (int) n[j][0], (int) n[j][1], edge);
            drawLine(g, (int) f[i][0], (int) f[i][1], (int) f[j][0], (int) f[j][1], edge);
            drawLine(g, (int) n[i][0], (int) n[i][1], (int) f[i][0], (int) f[i][1], edge);
        }
        return true;
    }

    @Nullable
    private static float[][] projectAll(Player player, Vec3[] points, int screenW, int screenH) {
        float[][] out = new float[points.length][];
        for (int i = 0; i < points.length; i++) {
            out[i] = RadarHudRenderer.worldToScreen(points[i], player, screenW, screenH);
            if (out[i] == null) return null;
        }
        return out;
    }

    private static void fillQuad(GuiGraphics g, float[] a, float[] b, float[] c, float[] d, int color) {
        fillTriangle(g, a, b, c, color);
        fillTriangle(g, a, c, d, color);
    }

    private static void fillTriangle(GuiGraphics g, float[] a, float[] b, float[] c, int color) {
        int minY = (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])));
        int maxY = (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1])));
        for (int y = minY; y < maxY; y++) {
            double sy = y + 0.5;
            double x1 = edgeX(a, b, sy);
            double x2 = edgeX(b, c, sy);
            double x3 = edgeX(c, a, sy);
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            if (!Double.isNaN(x1)) { lo = Math.min(lo, x1); hi = Math.max(hi, x1); }
            if (!Double.isNaN(x2)) { lo = Math.min(lo, x2); hi = Math.max(hi, x2); }
            if (!Double.isNaN(x3)) { lo = Math.min(lo, x3); hi = Math.max(hi, x3); }
            if (lo > hi) continue;
            g.fill((int) Math.round(lo), y, (int) Math.round(hi) + 1, y + 1, color);
        }
    }

    private static double edgeX(float[] a, float[] b, double y) {
        double y0 = a[1], y1 = b[1];
        if (Math.abs(y1 - y0) < 1.0E-6) return Double.NaN;
        if (y < Math.min(y0, y1) || y > Math.max(y0, y1)) return Double.NaN;
        double t = (y - y0) / (y1 - y0);
        return a[0] + (b[0] - a[0]) * t;
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
