package com.minethunder;

import com.minethunder.countermeasure.CountermeasureEntity;
import com.minethunder.missile.ArhMissileArrow;
import com.minethunder.missile.IrMissileArrow;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.missile.SarhMissileArrow;
import com.minethunder.missile.SunHeatMarker;
import com.minethunder.plane.PlaneEngineItem;
import com.minethunder.plane.PlaneEngineType;
import com.minethunder.plane.PlaneEntity;
import com.minethunder.plane.PlaneItem;
import com.minethunder.plane.PlaneTypeSpec;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组物品与实体注册。
 */
public final class Registration {
    private Registration() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MineThunder.MODID);

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MineThunder.MODID);

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineThunder.MODID);

    // 导弹箭实体（必须先声明，物品 lambda 会引用它们）
    public static final DeferredHolder<EntityType<?>, EntityType<SarhMissileArrow>> SARH_MISSILE =
            ENTITIES.register("sarh_missile_entity", () -> EntityType.Builder
                    .<SarhMissileArrow>of((type, level) -> new SarhMissileArrow(type, level, MissileSpecs.SARH), MobCategory.MISC)
                    .sized(0.5f, 0.5f)
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .build("minethunder:sarh_missile_entity"));

    public static final DeferredHolder<EntityType<?>, EntityType<ArhMissileArrow>> ARH_MISSILE =
            ENTITIES.register("arh_missile_entity", () -> EntityType.Builder
                    .<ArhMissileArrow>of((type, level) -> new ArhMissileArrow(type, level, MissileSpecs.ARH), MobCategory.MISC)
                    .sized(0.5f, 0.5f)
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .build("minethunder:arh_missile_entity"));

    public static final DeferredHolder<EntityType<?>, EntityType<IrMissileArrow>> IR_MISSILE =
            ENTITIES.register("ir_missile_entity", () -> EntityType.Builder
                    .<IrMissileArrow>of((type, level) -> new IrMissileArrow(type, level, MissileSpecs.IR), MobCategory.MISC)
                    .sized(0.5f, 0.5f)
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .build("minethunder:ir_missile_entity"));

    public static final DeferredHolder<EntityType<?>, EntityType<CountermeasureEntity>> COUNTERMEASURE =
            ENTITIES.register("countermeasure", () -> EntityType.Builder
                    .<CountermeasureEntity>of((type, level) -> new CountermeasureEntity(type, level), MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .clientTrackingRange(8)
                    .updateInterval(10)
                    .build("minethunder:countermeasure"));

    public static final DeferredHolder<EntityType<?>, EntityType<SunHeatMarker>> SUN_HEAT_MARKER =
            ENTITIES.register("sun_heat_marker", () -> EntityType.Builder
                    .<SunHeatMarker>of(SunHeatMarker::new, MobCategory.MISC)
                    .sized(0.0f, 0.0f)
                    .clientTrackingRange(10)
                    .updateInterval(10)
                    .build("minethunder:sun_heat_marker"));

    // 简单飞机（Simple Planes）移植固定翼飞机实体
    public static final DeferredHolder<EntityType<?>, EntityType<PlaneEntity>> SMALL_PLANE =
            ENTITIES.register("small_plane", () -> EntityType.Builder
                    .<PlaneEntity>of((type, level) -> new PlaneEntity(type, level, PlaneTypeSpec.SMALL_PLANE), MobCategory.MISC)
                    .sized(PlaneTypeSpec.SMALL_PLANE.width(), PlaneTypeSpec.SMALL_PLANE.height())
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .build("minethunder:small_plane"));

    public static final DeferredHolder<EntityType<?>, EntityType<PlaneEntity>> LARGE_PLANE =
            ENTITIES.register("large_plane", () -> EntityType.Builder
                    .<PlaneEntity>of((type, level) -> new PlaneEntity(type, level, PlaneTypeSpec.LARGE_PLANE), MobCategory.MISC)
                    .sized(PlaneTypeSpec.LARGE_PLANE.width(), PlaneTypeSpec.LARGE_PLANE.height())
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .build("minethunder:large_plane"));

    public static final DeferredHolder<EntityType<?>, EntityType<PlaneEntity>> CARGO_PLANE =
            ENTITIES.register("cargo_plane", () -> EntityType.Builder
                    .<PlaneEntity>of((type, level) -> new PlaneEntity(type, level, PlaneTypeSpec.CARGO_PLANE), MobCategory.MISC)
                    .sized(PlaneTypeSpec.CARGO_PLANE.width(), PlaneTypeSpec.CARGO_PLANE.height())
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .build("minethunder:cargo_plane"));

    // 导弹物品：用弓发射时生成对应导弹箭实体（箭 = 导弹）
    public static final DeferredHolder<Item, Item> SARH_MISSILE_ITEM =
            ITEMS.register("sarh_missile", () -> new com.minethunder.missile.MissileItem(
                    new Item.Properties(), MissileSpecs.SARH, SARH_MISSILE));

    public static final DeferredHolder<Item, Item> ARH_MISSILE_ITEM =
            ITEMS.register("arh_missile", () -> new com.minethunder.missile.MissileItem(
                    new Item.Properties(), MissileSpecs.ARH, ARH_MISSILE));

    public static final DeferredHolder<Item, Item> IR_MISSILE_ITEM =
            ITEMS.register("ir_missile", () -> new com.minethunder.missile.MissileItem(
                    new Item.Properties(), MissileSpecs.IR, IR_MISSILE));

    // 导弹烟花：从背包直接扣除并发射
    public static final DeferredHolder<Item, Item> SARH_MISSILE_FIREWORK =
            ITEMS.register("sarh_missile_firework", () -> new MissileFireworkItem(
                    new Item.Properties().stacksTo(16), MissileSpecs.SARH));

    public static final DeferredHolder<Item, Item> ARH_MISSILE_FIREWORK =
            ITEMS.register("arh_missile_firework", () -> new MissileFireworkItem(
                    new Item.Properties().stacksTo(16), MissileSpecs.ARH));

    public static final DeferredHolder<Item, Item> IR_MISSILE_FIREWORK =
            ITEMS.register("ir_missile_firework", () -> new MissileFireworkItem(
                    new Item.Properties().stacksTo(16), MissileSpecs.IR));

    // 简单飞机（Simple Planes）固定翼飞机与航空发动机物品
    public static final DeferredHolder<Item, Item> SMALL_PLANE_ITEM =
            ITEMS.register("small_plane", () -> new PlaneItem(
                    new Item.Properties().stacksTo(1), PlaneTypeSpec.SMALL_PLANE));

    public static final DeferredHolder<Item, Item> LARGE_PLANE_ITEM =
            ITEMS.register("large_plane", () -> new PlaneItem(
                    new Item.Properties().stacksTo(1), PlaneTypeSpec.LARGE_PLANE));

    public static final DeferredHolder<Item, Item> CARGO_PLANE_ITEM =
            ITEMS.register("cargo_plane", () -> new PlaneItem(
                    new Item.Properties().stacksTo(1), PlaneTypeSpec.CARGO_PLANE));

    public static final DeferredHolder<Item, Item> FURNACE_ENGINE =
            ITEMS.register("furnace_engine", () -> new PlaneEngineItem(
                    new Item.Properties().stacksTo(16), PlaneEngineType.FURNACE));

    public static final DeferredHolder<Item, Item> LIQUID_ENGINE =
            ITEMS.register("liquid_engine", () -> new PlaneEngineItem(
                    new Item.Properties().stacksTo(16), PlaneEngineType.LIQUID));

    public static final DeferredHolder<Item, Item> JET_ENGINE =
            ITEMS.register("jet_engine", () -> new PlaneEngineItem(
                    new Item.Properties().stacksTo(16), PlaneEngineType.JET));

    /** 创造模式物品栏 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.minethunder"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> SMALL_PLANE_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(SMALL_PLANE_ITEM.get());
                output.accept(LARGE_PLANE_ITEM.get());
                output.accept(CARGO_PLANE_ITEM.get());
                output.accept(FURNACE_ENGINE.get());
                output.accept(LIQUID_ENGINE.get());
                output.accept(JET_ENGINE.get());
                output.accept(SARH_MISSILE_FIREWORK.get());
                output.accept(ARH_MISSILE_FIREWORK.get());
                output.accept(IR_MISSILE_FIREWORK.get());
            })
            .build());

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, path);
    }
}
