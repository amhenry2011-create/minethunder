package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.client.model.*;
import com.minethunder.plane.PlaneEngineType;
import com.minethunder.plane.PlaneEntity;
import com.minethunder.plane.PlaneTypeSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * 直接移植自 Simple Planes (xyz.przemyk.simpleplanes.client.render.PlaneRenderer) 的原版模型渲染器：
 * 使用 Simple Planes 原版机身模型、金属结构件模型、螺旋桨模型、座椅模型及三型航空发动机/助推器模型与贴图。
 */
public class PlaneRenderer extends EntityRenderer<PlaneEntity> {
    private static final ResourceLocation OAK_PLANKS_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png");
    private static final ResourceLocation SPRUCE_PLANKS_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/spruce_planks.png");
    private static final ResourceLocation DARK_OAK_PLANKS_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/dark_oak_planks.png");

    private static final ResourceLocation PLANE_METAL_TEX = upgradeTex("plane_metal.png");
    private static final ResourceLocation LARGE_PLANE_METAL_TEX = upgradeTex("large_plane_metal.png");
    private static final ResourceLocation CARGO_PLANE_METAL_TEX = upgradeTex("cargo_plane_metal.png");

    private static final ResourceLocation PROPELLER_TEX = upgradeTex("iron_propeller.png");
    private static final ResourceLocation LARGE_PROPELLER_TEX = upgradeTex("iron_large_propeller.png");
    private static final ResourceLocation CARGO_PROPELLER_TEX = upgradeTex("iron_cargo_propeller.png");

    private static final ResourceLocation FURNACE_TEX = upgradeTex("furnace_engine.png");
    private static final ResourceLocation LARGE_FURNACE_TEX = upgradeTex("furnace_engine_large.png");
    private static final ResourceLocation CARGO_FURNACE_TEX = upgradeTex("furnace_engine_cargo.png");

    private static final ResourceLocation LIQUID_TEX = upgradeTex("liquid_engine.png");
    private static final ResourceLocation LARGE_LIQUID_TEX = upgradeTex("liquid_engine_large.png");
    private static final ResourceLocation CARGO_LIQUID_TEX = upgradeTex("liquid_engine_cargo.png");

    private static final ResourceLocation ELECTRIC_TEX = upgradeTex("electric_engine.png");
    private static final ResourceLocation LARGE_ELECTRIC_TEX = upgradeTex("electric_engine_large.png");
    private static final ResourceLocation CARGO_ELECTRIC_TEX = upgradeTex("electric_engine_cargo.png");

    private static final ResourceLocation BOOSTER_TEX = upgradeTex("booster.png");
    private static final ResourceLocation LARGE_BOOSTER_TEX = upgradeTex("booster_large.png");
    private static final ResourceLocation CARGO_BOOSTER_TEX = upgradeTex("booster_cargo.png");

    private static final ResourceLocation SEATS_TEX = upgradeTex("seats.png");
    private static final ResourceLocation LARGE_SEATS_TEX = upgradeTex("seats_large.png");

    // 机身主模型
    private final EntityModel<PlaneEntity> smallPlaneModel;
    private final EntityModel<PlaneEntity> smallMetalModel;
    private final EntityModel<PlaneEntity> smallPropellerModel;

    private final EntityModel<PlaneEntity> largePlaneModel;
    private final EntityModel<PlaneEntity> largeMetalModel;
    private final EntityModel<PlaneEntity> largePropellerModel;

    private final EntityModel<PlaneEntity> cargoPlaneModel;
    private final EntityModel<PlaneEntity> cargoMetalModel;
    private final EntityModel<PlaneEntity> cargoPropellerModel;

    // 发动机与喷气助推器模型
    private final EntityModel<PlaneEntity> furnaceEngineModel;
    private final EntityModel<PlaneEntity> largeFurnaceEngineModel;
    private final EntityModel<PlaneEntity> cargoFurnaceEngineModel;

    private final EntityModel<PlaneEntity> liquidEngineModel;
    private final EntityModel<PlaneEntity> largeLiquidEngineModel;
    private final EntityModel<PlaneEntity> cargoLiquidEngineModel;

    private final EntityModel<PlaneEntity> electricEngineModel;
    private final EntityModel<PlaneEntity> largeElectricEngineModel;
    private final EntityModel<PlaneEntity> cargoElectricEngineModel;

    private final EntityModel<PlaneEntity> boosterModel;
    private final EntityModel<PlaneEntity> largeBoosterModel;
    private final EntityModel<PlaneEntity> cargoBoosterModel;

    // 座椅模型
    private final EntityModel<PlaneEntity> seatsModel;
    private final EntityModel<PlaneEntity> woodenSeatsModel;
    private final EntityModel<PlaneEntity> largeSeatsModel;
    private final EntityModel<PlaneEntity> cargoSeatsModel;
    private final EntityModel<PlaneEntity> woodenCargoSeatsModel;

    public PlaneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.8F;
        EntityModelSet modelSet = context.getModelSet();

        this.smallPlaneModel = new PlaneModel(modelSet.bakeLayer(PlanesModelLayers.PLANE_LAYER));
        this.smallMetalModel = new PlaneMetalModel(modelSet.bakeLayer(PlanesModelLayers.PLANE_METAL_LAYER));
        this.smallPropellerModel = new PropellerModel(modelSet.bakeLayer(PlanesModelLayers.PROPELLER_LAYER));

        this.largePlaneModel = new LargePlaneModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_PLANE_LAYER));
        this.largeMetalModel = new LargePlaneMetalModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_PLANE_METAL_LAYER));
        this.largePropellerModel = new LargePropellerModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_PROPELLER_LAYER));

        this.cargoPlaneModel = new CargoPlaneModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_PLANE_LAYER));
        this.cargoMetalModel = new CargoPlaneMetalModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_PLANE_METAL_LAYER));
        this.cargoPropellerModel = new CargoPropellerModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_PROPELLER_LAYER));

        this.furnaceEngineModel = new FurnaceEngineModel(modelSet.bakeLayer(PlanesModelLayers.FURNACE_ENGINE));
        this.largeFurnaceEngineModel = new LargeFurnaceEngineModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_FURNACE_ENGINE));
        this.cargoFurnaceEngineModel = new CargoFurnaceEngineModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_FURNACE_ENGINE));

        this.liquidEngineModel = new LiquidEngineModel(modelSet.bakeLayer(PlanesModelLayers.LIQUID_ENGINE));
        this.largeLiquidEngineModel = new LargeLiquidEngineModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_LIQUID_ENGINE));
        this.cargoLiquidEngineModel = new CargoLiquidEngineModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_LIQUID_ENGINE));

        this.electricEngineModel = new ElectricEngineModel(modelSet.bakeLayer(PlanesModelLayers.ELECTRIC_ENGINE));
        this.largeElectricEngineModel = new LargeElectricEngineModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_ELECTRIC_ENGINE));
        this.cargoElectricEngineModel = new CargoElectricEngineModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_ELECTRIC_ENGINE));

        this.boosterModel = new BoosterModel(modelSet.bakeLayer(PlanesModelLayers.BOOSTER));
        this.largeBoosterModel = new LargeBoosterModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_BOOSTER));
        this.cargoBoosterModel = new CargoBoosterModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_BOOSTER));

        this.seatsModel = new SeatsModel(modelSet.bakeLayer(PlanesModelLayers.SEATS));
        this.woodenSeatsModel = new WoodenSeatsModel(modelSet.bakeLayer(PlanesModelLayers.WOODEN_SEATS));
        this.largeSeatsModel = new LargeSeatsModel(modelSet.bakeLayer(PlanesModelLayers.LARGE_SEATS));
        this.cargoSeatsModel = new CargoSeatsModel(modelSet.bakeLayer(PlanesModelLayers.CARGO_SEATS));
        this.woodenCargoSeatsModel = new WoodenCargoSeatsModel(modelSet.bakeLayer(PlanesModelLayers.WOODEN_CARGO_SEATS));
    }

    private static ResourceLocation upgradeTex(String fileName) {
        return ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, "textures/plane_upgrades/" + fileName);
    }

    /**
     * 供 PropellerModel / LargePropellerModel / CargoPropellerModel 调用的螺旋桨旋转弧度。
     */
    public static float getPropellerRotation(PlaneEntity entity, float partialTicks) {
        float deg = Mth.rotLerp(partialTicks, entity.prevPropAngleDeg, entity.propAngleDeg);
        return (float) Math.toRadians(deg);
    }

    @Override
    public void render(PlaneEntity planeEntity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.375D, 0.0D);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        float yaw = Mth.rotLerp(partialTicks, planeEntity.prevYawDeg, planeEntity.getYawDeg());
        float pitch = Mth.lerp(partialTicks, planeEntity.prevPitchDeg, planeEntity.getPitchDeg());
        float roll = Mth.rotLerp(partialTicks, planeEntity.prevRollDeg, planeEntity.getRollDeg());
        Quaternionf q = toSimplePlanesQuaternion(yaw, pitch, roll);
        poseStack.mulPose(q);

        PlaneTypeSpec spec = planeEntity.getPlaneType();
        EntityModel<PlaneEntity> bodyModel;
        EntityModel<PlaneEntity> metalModel;
        EntityModel<PlaneEntity> propModel;
        ResourceLocation materialTex = getMaterialTexture(planeEntity);
        ResourceLocation metalTex;
        ResourceLocation propTex;

        if (spec == PlaneTypeSpec.SMALL_PLANE) {
            poseStack.translate(0.0D, -0.5D, -0.5D);
            bodyModel = smallPlaneModel;
            metalModel = smallMetalModel;
            propModel = smallPropellerModel;
            metalTex = PLANE_METAL_TEX;
            propTex = PROPELLER_TEX;
        } else if (spec == PlaneTypeSpec.LARGE_PLANE) {
            poseStack.translate(0.0D, -0.3D, -1.0D);
            bodyModel = largePlaneModel;
            metalModel = largeMetalModel;
            propModel = largePropellerModel;
            metalTex = LARGE_PLANE_METAL_TEX;
            propTex = LARGE_PROPELLER_TEX;
        } else {
            poseStack.translate(0.0D, -0.8D, -1.0D);
            bodyModel = cargoPlaneModel;
            metalModel = cargoMetalModel;
            propModel = cargoPropellerModel;
            metalTex = CARGO_PLANE_METAL_TEX;
            propTex = CARGO_PROPELLER_TEX;
        }

        poseStack.translate(0.0D, -1.1D, 0.0D);

        // 1. 渲染 Simple Planes 原版木质/材质机身
        VertexConsumer bodyVc = buffer.getBuffer(bodyModel.renderType(materialTex));
        bodyModel.setupAnim(planeEntity, partialTicks, 0, 0, 0, 0);
        bodyModel.renderToBuffer(poseStack, bodyVc, packedLight, OverlayTexture.NO_OVERLAY);

        // 2. 渲染 Simple Planes 原版旋转螺旋桨
        VertexConsumer propVc = ItemRenderer.getArmorFoilBuffer(buffer, bodyModel.renderType(propTex), false);
        propModel.setupAnim(planeEntity, partialTicks, 0, 0, 0, 0);
        propModel.renderToBuffer(poseStack, propVc, packedLight, OverlayTexture.NO_OVERLAY);

        // 3. 渲染 Simple Planes 原版金属翼梁、垂尾与起落架结构
        VertexConsumer metalVc = buffer.getBuffer(metalModel.renderType(metalTex));
        metalModel.setupAnim(planeEntity, partialTicks, 0, 0, 0, 0);
        metalModel.renderToBuffer(poseStack, metalVc, packedLight, OverlayTexture.NO_OVERLAY);

        // 4. 渲染 Simple Planes 原版座舱座椅
        renderSeats(planeEntity, spec, materialTex, poseStack, buffer, packedLight);

        // 5. 渲染 Simple Planes 原版发动机与喷气推进器配件
        renderEngineUpgrade(planeEntity, spec, poseStack, buffer, packedLight);

        poseStack.popPose();
        super.render(planeEntity, 0, partialTicks, poseStack, buffer, packedLight);
    }

    private void renderSeats(PlaneEntity planeEntity, PlaneTypeSpec spec, ResourceLocation materialTex,
                             PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (spec == PlaneTypeSpec.SMALL_PLANE) {
            VertexConsumer vc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(SEATS_TEX), false);
            seatsModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
            woodenSeatsModel.renderToBuffer(poseStack, buffer.getBuffer(seatsModel.renderType(materialTex)),
                    packedLight, OverlayTexture.NO_OVERLAY);
        } else if (spec == PlaneTypeSpec.LARGE_PLANE) {
            VertexConsumer vc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(LARGE_SEATS_TEX), false);
            largeSeatsModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
        } else {
            VertexConsumer vc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(CARGO_PLANE_METAL_TEX), false);
            cargoSeatsModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
            woodenCargoSeatsModel.renderToBuffer(poseStack, buffer.getBuffer(seatsModel.renderType(materialTex)),
                    packedLight, OverlayTexture.NO_OVERLAY);
        }
    }

    private void renderEngineUpgrade(PlaneEntity planeEntity, PlaneTypeSpec spec,
                                     PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        PlaneEngineType engineType = planeEntity.getEngineType();
        if (engineType == PlaneEngineType.FURNACE) {
            EntityModel<PlaneEntity> model = switch (spec) {
                case SMALL_PLANE -> furnaceEngineModel;
                case LARGE_PLANE -> largeFurnaceEngineModel;
                case CARGO_PLANE -> cargoFurnaceEngineModel;
            };
            ResourceLocation tex = switch (spec) {
                case SMALL_PLANE -> FURNACE_TEX;
                case LARGE_PLANE -> LARGE_FURNACE_TEX;
                case CARGO_PLANE -> CARGO_FURNACE_TEX;
            };
            VertexConsumer vc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(tex), false);
            model.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
        } else if (engineType == PlaneEngineType.LIQUID) {
            EntityModel<PlaneEntity> model = switch (spec) {
                case SMALL_PLANE -> liquidEngineModel;
                case LARGE_PLANE -> largeLiquidEngineModel;
                case CARGO_PLANE -> cargoLiquidEngineModel;
            };
            ResourceLocation tex = switch (spec) {
                case SMALL_PLANE -> LIQUID_TEX;
                case LARGE_PLANE -> LARGE_LIQUID_TEX;
                case CARGO_PLANE -> CARGO_LIQUID_TEX;
            };
            VertexConsumer vc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(tex), false);
            model.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
        } else {
            // JET：同时渲染电动/涡轮主机舱与 Simple Planes 原版双联喷气助推器 (Booster)
            EntityModel<PlaneEntity> engModel = switch (spec) {
                case SMALL_PLANE -> electricEngineModel;
                case LARGE_PLANE -> largeElectricEngineModel;
                case CARGO_PLANE -> cargoElectricEngineModel;
            };
            ResourceLocation engTex = switch (spec) {
                case SMALL_PLANE -> ELECTRIC_TEX;
                case LARGE_PLANE -> LARGE_ELECTRIC_TEX;
                case CARGO_PLANE -> CARGO_ELECTRIC_TEX;
            };
            VertexConsumer engVc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(engTex), false);
            engModel.renderToBuffer(poseStack, engVc, packedLight, OverlayTexture.NO_OVERLAY);

            EntityModel<PlaneEntity> boostModel = switch (spec) {
                case SMALL_PLANE -> boosterModel;
                case LARGE_PLANE -> largeBoosterModel;
                case CARGO_PLANE -> cargoBoosterModel;
            };
            ResourceLocation boostTex = switch (spec) {
                case SMALL_PLANE -> BOOSTER_TEX;
                case LARGE_PLANE -> LARGE_BOOSTER_TEX;
                case CARGO_PLANE -> CARGO_BOOSTER_TEX;
            };
            VertexConsumer boostVc = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(boostTex), false);
            boostModel.renderToBuffer(poseStack, boostVc, packedLight, OverlayTexture.NO_OVERLAY);
        }
    }

    /**
     * 与 Simple Planes (xyz.przemyk.simpleplanes.misc.MathUtil.toQuaternionf) 完全一致的四元数姿态变换。
     */
    public static Quaternionf toSimplePlanesQuaternion(double yaw, double pitch, double roll) {
        yaw = Math.toRadians(yaw);
        pitch = -Math.toRadians(pitch);
        roll = Math.toRadians(roll);

        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);
        double cp = Math.cos(pitch * 0.5);
        double sp = Math.sin(pitch * 0.5);
        double cr = Math.cos(roll * 0.5);
        double sr = Math.sin(roll * 0.5);

        float w = (float) (cr * cp * cy + sr * sp * sy);
        float z = (float) (sr * cp * cy - cr * sp * sy);
        float x = (float) (cr * sp * cy + sr * cp * sy);
        float y = (float) (cr * cp * sy - sr * sp * cy);

        return new Quaternionf(x, y, z, w);
    }

    public static ResourceLocation getMaterialTexture(PlaneEntity entity) {
        return switch (entity.getPlaneType()) {
            case SMALL_PLANE -> OAK_PLANKS_TEXTURE;
            case LARGE_PLANE -> SPRUCE_PLANKS_TEXTURE;
            case CARGO_PLANE -> DARK_OAK_PLANKS_TEXTURE;
        };
    }

    @Override
    public ResourceLocation getTextureLocation(PlaneEntity entity) {
        return getMaterialTexture(entity);
    }
}
