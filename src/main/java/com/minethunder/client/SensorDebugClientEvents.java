package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.sensor.SensorDebugState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class SensorDebugClientEvents {
    private SensorDebugClientEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SensorDebugState.setClientEnabled(false);
    }
}
