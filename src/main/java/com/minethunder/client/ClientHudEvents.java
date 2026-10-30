package com.minethunder.client;

import com.minethunder.MineThunder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * 客户端渲染事件：在 HUD 层绘制雷达面板（雷达开启时）。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientHudEvents {
    private ClientHudEvents() {}

    @SubscribeEvent
    public static void onAddGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, "plane_hud"),
                ClientHudEvents::renderPlaneHud);
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, "radar_hud"),
                ClientHudEvents::renderRadar);
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, "rack_hud"),
                ClientHudEvents::renderRack);
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(MineThunder.MODID, "sensor_debug"),
                ClientHudEvents::renderSensorDebug);
    }

    private static void renderPlaneHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        PlaneHudRenderer.render(g, w, h, delta.getGameTimeDeltaPartialTick(true));
    }

    private static void renderRadar(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (!RadarHud.radarOn) {
            RadarHudRenderer.renderRwrOnly(g, w, h);
            return;
        }
        RadarHudRenderer.render(g, w, h);
    }

    private static void renderRack(GuiGraphics g, DeltaTracker delta) {
        if (!com.minethunder.client.rack.RackSelectState.open) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        com.minethunder.client.rack.RackSelectRenderer.render(g, w, h);
    }

    private static void renderSensorDebug(GuiGraphics g, DeltaTracker delta) {
        if (!com.minethunder.sensor.SensorDebugState.isClientEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        SensorDebugRenderer.render(g, w, h, delta.getGameTimeDeltaPartialTick(true));
    }
}
