package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.radar.RadarMode;
import com.minethunder.sensor.SensorDebugState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** OP 传感器视觉：在世界空间绘制雷达扫描/跟踪四棱柱。 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class SensorDebugWorldRenderer {
    private SensorDebugWorldRenderer() {}

    private static final double NEAR_DISTANCE = 0.65;
    private static final double NEAR_HALF = 0.24;
    private static final double SCAN_RANGE_CAP = 220.0;
    private static final int SCAN_FILL = 0x4066FF66;
    private static final int SCAN_EDGE = 0xB066FF66;
    private static final int TRACK_FILL = 0x4048FF88;
    private static final int TRACK_EDGE = 0xB07CFF9E;
    private static final int SELECTED_FILL = 0x7048FF88;
    private static final int SELECTED_EDGE = 0xFFFFD070;
    private static final int LOCK_FILL = 0x32A8FFB0;
    private static final int LOCK_EDGE = 0xFF9FFF9F;

    private record Beam(Vec3 nearCenter, Vec3 farCenter, double nearHalf,
                        double farHalf, int fill, int edge) {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!SensorDebugState.isClientEnabled() || !RadarHud.radarOn) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        List<Beam> beams = collectBeams(player, partialTick);
        if (beams.isEmpty()) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer fill = buffer.getBuffer(RenderType.debugQuads());
        for (Beam beam : beams) {
            drawBeamFaces(fill, matrix, beam);
        }
        buffer.endBatch(RenderType.debugQuads());

        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        for (Beam beam : beams) {
            drawBeamEdges(lines, matrix, beam);
        }
        buffer.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static List<Beam> collectBeams(Player player, float partialTick) {
        List<Beam> beams = new ArrayList<>();
        Vec3 eye = ClientRenderMath.eye(player, partialTick);
        if (RadarHud.currentMode == RadarMode.STT) {
            TrackedTarget tracked = TargetRegistry.find(
                    RadarHud.lockedTargetUuid, player, player.tickCount);
            if (tracked != null && tracked.alive(player)) {
                addTrackedBeam(beams, eye, tracked, partialTick, LOCK_FILL, LOCK_EDGE);
            }
            return beams;
        }

        if (RadarHud.lockAcquiring) {
            TrackedTarget tracked = TargetRegistry.find(
                    RadarHud.lockedTargetUuid, player, player.tickCount);
            if (tracked != null && tracked.alive(player)) {
                addTrackedBeam(beams, eye, tracked, partialTick, LOCK_FILL, LOCK_EDGE);
                return beams;
            }
        }

        if (RadarHud.currentMode == RadarMode.TWS) {
            addScanBeam(beams, player, eye, partialTick, SCAN_FILL, SCAN_EDGE);
            for (TwsTrack track : TwsTrackManager.tracks()) {
                Entity target = TwsTrackManager.entity(track.entityId);
                if (target == null || !target.isAlive()) continue;
                boolean selected = RadarHud.isSelected(track.uuid, track.entityId);
                addTargetBeam(beams, eye, target, partialTick,
                        selected ? SELECTED_FILL : TRACK_FILL,
                        selected ? SELECTED_EDGE : TRACK_EDGE);
            }
            return beams;
        }

        addScanBeam(beams, player, eye, partialTick, SCAN_FILL, SCAN_EDGE);
        return beams;
    }

    private static void addScanBeam(List<Beam> beams, Player player, Vec3 eye,
                                    float partialTick, int fill, int edge) {
        double yaw = ClientRenderMath.yaw(player, partialTick)
                + ClientRenderMath.sweepAzimuth(partialTick);
        double pitch = ClientRenderMath.pitch(player, partialTick)
                - ClientRenderMath.sweepElevation(partialTick);
        Vec3 dir = Vec3.directionFromRotation((float) pitch, (float) yaw).normalize();
        double range = Math.max(1.0, Math.min(RadarHud.effectiveRange(), SCAN_RANGE_CAP));
        Vec3 near = eye.add(dir.scale(NEAR_DISTANCE));
        Vec3 far = near.add(dir.scale(range));
        double farHalf = Math.max(0.45, Math.min(5.0, range * 0.012));
        beams.add(new Beam(near, far, NEAR_HALF, farHalf, fill, edge));
    }

    /** 统一目标波束：本地实体走包围盒，BVR 用数据链实时位置。 */
    private static void addTrackedBeam(List<Beam> beams, Vec3 eye, TrackedTarget target,
                                       float partialTick, int fill, int edge) {
        if (target.entity != null) {
            addTargetBeam(beams, eye, target.entity, partialTick, fill, edge);
            return;
        }
        Vec3 far = target.livePosition();
        Vec3 to = far.subtract(eye);
        if (to.lengthSqr() < 0.01) return;
        Vec3 dir = to.normalize();
        Vec3 near = eye.add(dir.scale(NEAR_DISTANCE));
        if (far.distanceTo(near) < 0.1) return;
        beams.add(new Beam(near, far, NEAR_HALF, 0.8, fill, edge));
    }

    private static void addTargetBeam(List<Beam> beams, Vec3 eye, Entity target,
                                      float partialTick, int fill, int edge) {
        Vec3 far = ClientRenderMath.entityCenter(target, partialTick);
        Vec3 to = far.subtract(eye);
        if (to.lengthSqr() < 0.01) return;
        Vec3 dir = to.normalize();
        Vec3 near = eye.add(dir.scale(NEAR_DISTANCE));
        if (far.distanceTo(near) < 0.1) return;
        double farHalf = Math.max(0.5,
                Math.max(target.getBbWidth(), target.getBbHeight()) * 0.5);
        beams.add(new Beam(near, far, NEAR_HALF, farHalf, fill, edge));
    }

    private static void drawBeamFaces(VertexConsumer out, Matrix4f matrix, Beam beam) {
        Vec3 forward = beam.farCenter.subtract(beam.nearCenter);
        double distance = forward.length();
        if (distance < 0.01) return;
        forward = forward.scale(1.0 / distance);
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3[] near = corners(beam.nearCenter, right, up, beam.nearHalf);
        Vec3[] far = corners(beam.farCenter, right, up, beam.farHalf);

        quad(out, matrix, near[0], near[1], far[1], far[0], beam.fill);
        quad(out, matrix, near[1], near[2], far[2], far[1], beam.fill);
        quad(out, matrix, near[2], near[3], far[3], far[2], beam.fill);
        quad(out, matrix, near[3], near[0], far[0], far[3], beam.fill);
        quad(out, matrix, far[0], far[1], far[2], far[3], beam.fill);
    }

    private static void drawBeamEdges(VertexConsumer out, Matrix4f matrix, Beam beam) {
        Vec3 forward = beam.farCenter.subtract(beam.nearCenter);
        double distance = forward.length();
        if (distance < 0.01) return;
        forward = forward.scale(1.0 / distance);
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3[] near = corners(beam.nearCenter, right, up, beam.nearHalf);
        Vec3[] far = corners(beam.farCenter, right, up, beam.farHalf);

        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            line(out, matrix, near[i], near[j], beam.edge);
            line(out, matrix, far[i], far[j], beam.edge);
            line(out, matrix, near[i], far[i], beam.edge);
        }
        line(out, matrix, near[0], near[2], beam.edge);
        line(out, matrix, near[1], near[3], beam.edge);
        line(out, matrix, far[0], far[2], beam.edge);
        line(out, matrix, far[1], far[3], beam.edge);
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

    private static void quad(VertexConsumer out, Matrix4f matrix,
                             Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
        vertex(out, matrix, a, argb);
        vertex(out, matrix, b, argb);
        vertex(out, matrix, c, argb);
        vertex(out, matrix, d, argb);
    }

    private static void line(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, int argb) {
        lineVertex(out, matrix, a, argb);
        lineVertex(out, matrix, b, argb);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 pos, int argb) {
        out.addVertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z);
        out.setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
                (argb >>> 24) & 0xFF);
    }

    private static void lineVertex(VertexConsumer out, Matrix4f matrix, Vec3 pos, int argb) {
        vertex(out, matrix, pos, argb);
        out.setNormal(0.0F, 1.0F, 0.0F);
    }
}
