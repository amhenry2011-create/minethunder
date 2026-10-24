package com.minethunder;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** 把导弹也放进原版“战斗”页，避免自定义页翻页/搜索时拿不出来。 */
@EventBusSubscriber(modid = MineThunder.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeTabEvents {
    private CreativeTabEvents() {}

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.COMBAT) return;
        event.accept(Registration.SMALL_PLANE_ITEM.get());
        event.accept(Registration.LARGE_PLANE_ITEM.get());
        event.accept(Registration.CARGO_PLANE_ITEM.get());
        event.accept(Registration.FURNACE_ENGINE.get());
        event.accept(Registration.LIQUID_ENGINE.get());
        event.accept(Registration.JET_ENGINE.get());
        event.accept(Registration.SARH_MISSILE_FIREWORK.get());
        event.accept(Registration.ARH_MISSILE_FIREWORK.get());
        event.accept(Registration.IR_MISSILE_FIREWORK.get());
    }
}
