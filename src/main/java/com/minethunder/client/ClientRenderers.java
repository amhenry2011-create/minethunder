package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.Registration;
import com.minethunder.countermeasure.CountermeasureEntity;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 客户端渲染器注册。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientRenderers {
    private ClientRenderers() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Registration.SARH_MISSILE.get(), MissileArrowRenderer::new);
        event.registerEntityRenderer(Registration.ARH_MISSILE.get(), MissileArrowRenderer::new);
        event.registerEntityRenderer(Registration.IR_MISSILE.get(), MissileArrowRenderer::new);
        event.registerEntityRenderer(Registration.COUNTERMEASURE.get(),
                context -> new ThrownItemRenderer<CountermeasureEntity>(context));
    }
}
