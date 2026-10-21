package com.minethunder.client;

import com.minethunder.MineThunder;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * 雷达功能快捷菜单的鼠标点击处理（鼠标被 Alt 释放时可用）。
 * 点击菜单行切换雷达模式；点击菜单外不拦截（交给游戏重新捕获鼠标）。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class RadarMenuInput {
    private RadarMenuInput() {}

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!RadarQuickMenu.visible) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler.isMouseGrabbed()) return; // 鼠标未释放时不处理

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        double mx = com.minethunder.client.rack.RackSelectRenderer.mouseGuiX(mc);
        double my = com.minethunder.client.rack.RackSelectRenderer.mouseGuiY(mc);

        if (RadarQuickMenu.handleClick(mx, my, w, h)) {
            event.setCanceled(true);
        }
    }
}
