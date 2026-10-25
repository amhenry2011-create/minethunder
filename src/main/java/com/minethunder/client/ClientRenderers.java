package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.Registration;
import com.minethunder.countermeasure.CountermeasureEntity;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * 客户端渲染器、Simple Planes 模型层与物品染色注册。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientRenderers {
    private ClientRenderers() {}

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        PlanesModelLayers.registerLayers(event);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Registration.SARH_MISSILE.get(), MissileArrowRenderer::new);
        event.registerEntityRenderer(Registration.ARH_MISSILE.get(), MissileArrowRenderer::new);
        event.registerEntityRenderer(Registration.IR_MISSILE.get(), MissileArrowRenderer::new);
        event.registerEntityRenderer(Registration.COUNTERMEASURE.get(),
                context -> new ThrownItemRenderer<CountermeasureEntity>(context));
        event.registerEntityRenderer(Registration.SUN_HEAT_MARKER.get(), NoopRenderer::new);
        event.registerEntityRenderer(Registration.SMALL_PLANE.get(), PlaneRenderer::new);
        event.registerEntityRenderer(Registration.LARGE_PLANE.get(), PlaneRenderer::new);
        event.registerEntityRenderer(Registration.CARGO_PLANE.get(), PlaneRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tintIndex == 0 ? 0xFFB88C5A : 0xFFFFFFFF,
                Registration.SMALL_PLANE_ITEM.get());
        event.register((stack, tintIndex) -> tintIndex == 0 ? 0xFF8C6239 : 0xFFFFFFFF,
                Registration.LARGE_PLANE_ITEM.get());
        event.register((stack, tintIndex) -> tintIndex == 0 ? 0xFF6B4C2B : 0xFFFFFFFF,
                Registration.CARGO_PLANE_ITEM.get());
    }
}
