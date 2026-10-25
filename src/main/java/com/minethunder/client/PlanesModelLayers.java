package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.client.model.*;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Simple Planes 原版实体与配件模型层定义与注册。
 */
public final class PlanesModelLayers {
    private PlanesModelLayers() {}

    public static final ModelLayerLocation PLANE_LAYER = layer("plane");
    public static final ModelLayerLocation PLANE_METAL_LAYER = layer("plane_metal");
    public static final ModelLayerLocation PROPELLER_LAYER = layer("propeller");

    public static final ModelLayerLocation LARGE_PLANE_LAYER = layer("large_plane");
    public static final ModelLayerLocation LARGE_PLANE_METAL_LAYER = layer("large_plane_metal");
    public static final ModelLayerLocation LARGE_PROPELLER_LAYER = layer("large_propeller");

    public static final ModelLayerLocation CARGO_PLANE_LAYER = layer("cargo_plane");
    public static final ModelLayerLocation CARGO_PLANE_METAL_LAYER = layer("cargo_plane_metal");
    public static final ModelLayerLocation CARGO_PROPELLER_LAYER = layer("cargo_propeller");

    public static final ModelLayerLocation FURNACE_ENGINE = layer("furnace_engine");
    public static final ModelLayerLocation LARGE_FURNACE_ENGINE = layer("large_furnace_engine");
    public static final ModelLayerLocation CARGO_FURNACE_ENGINE = layer("cargo_furnace_engine");

    public static final ModelLayerLocation LIQUID_ENGINE = layer("liquid_engine");
    public static final ModelLayerLocation LARGE_LIQUID_ENGINE = layer("large_liquid_engine");
    public static final ModelLayerLocation CARGO_LIQUID_ENGINE = layer("cargo_liquid_engine");

    public static final ModelLayerLocation ELECTRIC_ENGINE = layer("electric_engine");
    public static final ModelLayerLocation LARGE_ELECTRIC_ENGINE = layer("large_electric_engine");
    public static final ModelLayerLocation CARGO_ELECTRIC_ENGINE = layer("cargo_electric_engine");

    public static final ModelLayerLocation BOOSTER = layer("booster");
    public static final ModelLayerLocation LARGE_BOOSTER = layer("large_booster");
    public static final ModelLayerLocation CARGO_BOOSTER = layer("cargo_booster");

    public static final ModelLayerLocation SEATS = layer("seats");
    public static final ModelLayerLocation WOODEN_SEATS = layer("wooden_seats");
    public static final ModelLayerLocation LARGE_SEATS = layer("large_seats");
    public static final ModelLayerLocation CARGO_SEATS = layer("cargo_seats");
    public static final ModelLayerLocation WOODEN_CARGO_SEATS = layer("wooden_cargo_seats");

    public static final ModelLayerLocation ARMOR = layer("armor");
    public static final ModelLayerLocation ARMOR_WINDOW = layer("armor_window");
    public static final ModelLayerLocation LARGE_ARMOR = layer("large_armor");
    public static final ModelLayerLocation CARGO_ARMOR = layer("cargo_armor");

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, name), "main");
    }

    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PLANE_LAYER, PlaneModel::createBodyLayer);
        event.registerLayerDefinition(PLANE_METAL_LAYER, PlaneMetalModel::createBodyLayer);
        event.registerLayerDefinition(PROPELLER_LAYER, PropellerModel::createBodyLayer);

        event.registerLayerDefinition(LARGE_PLANE_LAYER, LargePlaneModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_PLANE_METAL_LAYER, LargePlaneMetalModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_PROPELLER_LAYER, LargePropellerModel::createBodyLayer);

        event.registerLayerDefinition(CARGO_PLANE_LAYER, CargoPlaneModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_PLANE_METAL_LAYER, CargoPlaneMetalModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_PROPELLER_LAYER, CargoPropellerModel::createBodyLayer);

        event.registerLayerDefinition(FURNACE_ENGINE, FurnaceEngineModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_FURNACE_ENGINE, LargeFurnaceEngineModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_FURNACE_ENGINE, CargoFurnaceEngineModel::createBodyLayer);

        event.registerLayerDefinition(LIQUID_ENGINE, LiquidEngineModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_LIQUID_ENGINE, LargeLiquidEngineModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_LIQUID_ENGINE, CargoLiquidEngineModel::createBodyLayer);

        event.registerLayerDefinition(ELECTRIC_ENGINE, ElectricEngineModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_ELECTRIC_ENGINE, LargeElectricEngineModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_ELECTRIC_ENGINE, CargoElectricEngineModel::createBodyLayer);

        event.registerLayerDefinition(BOOSTER, BoosterModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_BOOSTER, LargeBoosterModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_BOOSTER, CargoBoosterModel::createBodyLayer);

        event.registerLayerDefinition(SEATS, SeatsModel::createBodyLayer);
        event.registerLayerDefinition(WOODEN_SEATS, WoodenSeatsModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_SEATS, LargeSeatsModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_SEATS, CargoSeatsModel::createBodyLayer);
        event.registerLayerDefinition(WOODEN_CARGO_SEATS, WoodenCargoSeatsModel::createBodyLayer);

        event.registerLayerDefinition(ARMOR, ArmorModel::createBodyLayer);
        event.registerLayerDefinition(ARMOR_WINDOW, ArmorWindowModel::createBodyLayer);
        event.registerLayerDefinition(LARGE_ARMOR, LargeArmorModel::createBodyLayer);
        event.registerLayerDefinition(CARGO_ARMOR, CargoArmorModel::createBodyLayer);
    }
}
