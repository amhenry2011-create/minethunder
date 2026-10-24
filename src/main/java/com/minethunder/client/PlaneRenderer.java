package com.minethunder.client;

import com.minethunder.plane.PlaneEngineType;
import com.minethunder.plane.PlaneEntity;
import com.minethunder.plane.PlaneTypeSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 简单飞机（Simple Planes）程序化三维多部件实体渲染器：
 * 支持连续偏航/俯仰/滚转姿态插值、活动副翼/升降舵/方向舵/襟翼、可收放三点式起落架、
 * 旋转螺旋桨或加力涡扇尾喷口火焰锥，以及通电后的翼尖航行灯。
 */
public class PlaneRenderer extends EntityRenderer<PlaneEntity> {
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public PlaneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 1.2F;
    }

    @Override
    public void render(PlaneEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);

        PlaneTypeSpec spec = entity.getPlaneType();
        PlaneEngineType engine = entity.getEngineType();

        float yaw = Mth.rotLerp(partialTick, entity.prevYawDeg, entity.getYawDeg());
        float pitch = Mth.lerp(partialTick, entity.prevPitchDeg, entity.getPitchDeg());
        float roll = Mth.rotLerp(partialTick, entity.prevRollDeg, entity.getRollDeg());
        float gearProg = Mth.lerp(partialTick, entity.prevGearProgress, entity.getGearProgress());
        float propAngle = Mth.lerp(partialTick, entity.prevPropAngleDeg, entity.propAngleDeg);

        float scale = switch (spec) {
            case SMALL_PLANE -> 1.0f;
            case LARGE_PLANE -> 1.32f;
            case CARGO_PLANE -> 1.75f;
        };

        // 机身主色调区分三款机型
        int bodyColor = switch (spec) {
            case SMALL_PLANE -> 0xFF3E5C76; // 海军灰蓝
            case LARGE_PLANE -> 0xFF4A5568; // 制空战术深灰
            case CARGO_PLANE -> 0xFF4F5D4E; // 军用橄榄绿
        };
        int wingColor = switch (spec) {
            case SMALL_PLANE -> 0xFF52799A;
            case LARGE_PLANE -> 0xFF626E82;
            case CARGO_PLANE -> 0xFF637362;
        };
        int accentColor = 0xFFD97706; // 警示条纹琥珀色
        int darkMetal = 0xFF22272E;
        int glassColor = 0xAA63B3ED;

        poseStack.pushPose();
        poseStack.translate(0.0, 0.55 * scale, 0.0);

        // MC 坐标系旋转：先偏航 (-yaw)，再俯仰 (-pitch)，最后滚转 (roll)
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
        poseStack.scale(scale, scale, scale);

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(WHITE_TEXTURE));

        // 1. 机身主段 (Fuselage)
        drawBox(poseStack, vc, -0.38f, -0.25f, -1.65f, 0.38f, 0.32f, 1.55f, bodyColor, packedLight);
        // 机头整流罩 (Nose Cone)
        drawBox(poseStack, vc, -0.28f, -0.20f, 1.55f, 0.28f, 0.22f, 2.25f, accentColor, packedLight);
        // 机尾收窄段 (Tail Boom)
        drawBox(poseStack, vc, -0.26f, -0.18f, -2.55f, 0.26f, 0.26f, -1.65f, bodyColor, packedLight);

        // 2. 座舱透明风挡 (Canopy)
        drawBox(poseStack, vc, -0.30f, 0.30f, -0.55f, 0.30f, 0.68f, 0.85f, glassColor, packedLight);

        // 3. 主翼 (Main Wings)
        float wingSpan = spec == PlaneTypeSpec.CARGO_PLANE ? 3.2f : 2.6f;
        drawBox(poseStack, vc, -wingSpan, 0.02f, -0.35f, wingSpan, 0.12f, 0.55f, wingColor, packedLight);

        // 4. 活动襟翼 (Flaps: 0° / 15° / 40°)
        float flapAngle = entity.getFlapDegrees();
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.05f, -0.35f);
        poseStack.mulPose(Axis.XP.rotationDegrees(flapAngle));
        drawBox(poseStack, vc, -1.35f, -0.04f, -0.28f, -0.42f, 0.04f, 0.0f, accentColor, packedLight);
        drawBox(poseStack, vc, 0.42f, -0.04f, -0.28f, 1.35f, 0.04f, 0.0f, accentColor, packedLight);
        poseStack.popPose();

        // 5. 活动副翼 (Ailerons：随滚转输入差动偏转)
        float aileronDeflect = entity.getInputRoll() * 20.0f;
        poseStack.pushPose();
        poseStack.translate(-1.95f, 0.06f, -0.35f);
        poseStack.mulPose(Axis.XP.rotationDegrees(aileronDeflect));
        drawBox(poseStack, vc, -0.55f, -0.03f, -0.24f, 0.55f, 0.03f, 0.0f, darkMetal, packedLight);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(1.95f, 0.06f, -0.35f);
        poseStack.mulPose(Axis.XP.rotationDegrees(-aileronDeflect));
        drawBox(poseStack, vc, -0.55f, -0.03f, -0.24f, 0.55f, 0.03f, 0.0f, darkMetal, packedLight);
        poseStack.popPose();

        // 6. 水平尾翼与活动升降舵 (Horizontal Stabilizer & Elevator)
        drawBox(poseStack, vc, -1.05f, 0.12f, -2.45f, 1.05f, 0.18f, -1.95f, wingColor, packedLight);
        float elevatorDeflect = -entity.getInputPitch() * 22.0f - entity.getElevatorTrimDeg() * 1.2f;
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.15f, -2.45f);
        poseStack.mulPose(Axis.XP.rotationDegrees(elevatorDeflect));
        drawBox(poseStack, vc, -0.95f, -0.03f, -0.24f, 0.95f, 0.03f, 0.0f, accentColor, packedLight);
        poseStack.popPose();

        // 7. 垂直尾翼与活动方向舵 (Vertical Fin & Rudder)
        drawBox(poseStack, vc, -0.06f, 0.24f, -2.50f, 0.06f, 1.05f, -1.80f, wingColor, packedLight);
        float rudderDeflect = entity.getInputYaw() * 24.0f;
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.62f, -2.50f);
        poseStack.mulPose(Axis.YP.rotationDegrees(rudderDeflect));
        drawBox(poseStack, vc, -0.04f, -0.36f, -0.25f, 0.04f, 0.36f, 0.0f, accentColor, packedLight);
        poseStack.popPose();

        // 8. 动力装置：螺旋桨 或 涡扇尾喷口与加力尾焰
        if (engine == PlaneEngineType.JET) {
            // 涡扇尾喷管
            drawBox(poseStack, vc, -0.22f, -0.12f, -2.75f, 0.22f, 0.20f, -2.52f, darkMetal, packedLight);
            if (entity.isAfterburnerActive()) {
                drawBox(poseStack, vc, -0.16f, -0.06f, -3.55f, 0.16f, 0.14f, -2.75f, 0xCCFF6B00, 0xF000F0);
                drawBox(poseStack, vc, -0.09f, -0.01f, -3.95f, 0.09f, 0.09f, -2.75f, 0xEEFFF3B0, 0xF000F0);
            }
        } else {
            // 机头旋转螺旋桨
            poseStack.pushPose();
            poseStack.translate(0.0f, 0.02f, 2.30f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(propAngle));
            drawBox(poseStack, vc, -0.82f, -0.06f, -0.03f, 0.82f, 0.06f, 0.03f, darkMetal, packedLight);
            drawBox(poseStack, vc, -0.06f, -0.82f, -0.03f, 0.06f, 0.82f, 0.03f, darkMetal, packedLight);
            poseStack.popPose();
        }

        // 9. 可收放前三点式起落架 (Retractable Tricycle Landing Gear)
        if (gearProg > 0.02f) {
            float foldAngle = (1.0f - gearProg) * 85.0f;
            // 前起落架
            poseStack.pushPose();
            poseStack.translate(0.0f, -0.22f, 1.35f);
            poseStack.mulPose(Axis.XP.rotationDegrees(-foldAngle));
            drawBox(poseStack, vc, -0.04f, -0.35f, -0.04f, 0.04f, 0.0f, 0.04f, darkMetal, packedLight);
            drawBox(poseStack, vc, -0.09f, -0.45f, -0.12f, 0.09f, -0.27f, 0.12f, 0xFF111111, packedLight);
            poseStack.popPose();

            // 左主起落架
            poseStack.pushPose();
            poseStack.translate(-0.78f, -0.18f, -0.10f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(foldAngle));
            drawBox(poseStack, vc, -0.05f, -0.38f, -0.05f, 0.05f, 0.0f, 0.05f, darkMetal, packedLight);
            drawBox(poseStack, vc, -0.11f, -0.48f, -0.15f, 0.11f, -0.28f, 0.15f, 0xFF111111, packedLight);
            poseStack.popPose();

            // 右主起落架
            poseStack.pushPose();
            poseStack.translate(0.78f, -0.18f, -0.10f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-foldAngle));
            drawBox(poseStack, vc, -0.05f, -0.38f, -0.05f, 0.05f, 0.0f, 0.05f, darkMetal, packedLight);
            drawBox(poseStack, vc, -0.11f, -0.48f, -0.15f, 0.11f, -0.28f, 0.15f, 0xFF111111, packedLight);
            poseStack.popPose();
        }

        // 10. 主电瓶通电时的左红右绿翼尖航行灯 (Navigation Lights)
        if (entity.isMasterBatteryOn()) {
            int fullBright = 0xF000F0;
            // 右翼尖（星舷绿灯）
            drawBox(poseStack, vc, -wingSpan - 0.06f, 0.03f, 0.10f, -wingSpan + 0.04f, 0.13f, 0.25f,
                    0xFF00FF55, fullBright);
            // 左翼尖（左舷红灯）
            drawBox(poseStack, vc, wingSpan - 0.04f, 0.03f, 0.10f, wingSpan + 0.06f, 0.13f, 0.25f,
                    0xFFFF2222, fullBright);
        }

        poseStack.popPose();
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer vc,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                int argb, int light) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        // Top (+Y)
        quad(vc, mat, pose, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a, light, 0, 1, 0);
        // Bottom (-Y)
        quad(vc, mat, pose, x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2,
                (int) (r * 0.7f), (int) (g * 0.7f), (int) (b * 0.7f), a, light, 0, -1, 0);
        // Front (+Z)
        quad(vc, mat, pose, x2, y2, z2, x1, y2, z2, x1, y1, z2, x2, y1, z2,
                (int) (r * 0.9f), (int) (g * 0.9f), (int) (b * 0.9f), a, light, 0, 0, 1);
        // Back (-Z)
        quad(vc, mat, pose, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1,
                (int) (r * 0.85f), (int) (g * 0.85f), (int) (b * 0.85f), a, light, 0, 0, -1);
        // Left (-X)
        quad(vc, mat, pose, x1, y2, z2, x1, y2, z1, x1, y1, z1, x1, y1, z2,
                (int) (r * 0.8f), (int) (g * 0.8f), (int) (b * 0.8f), a, light, -1, 0, 0);
        // Right (+X)
        quad(vc, mat, pose, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1,
                (int) (r * 0.8f), (int) (g * 0.8f), (int) (b * 0.8f), a, light, 1, 0, 0);
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, PoseStack.Pose pose,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             int r, int g, int b, int a, int light,
                             float nx, float ny, float nz) {
        vc.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(r, g, b, a).setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x4, y4, z4).setColor(r, g, b, a).setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    @Override
    public ResourceLocation getTextureLocation(PlaneEntity entity) {
        return WHITE_TEXTURE;
    }
}
