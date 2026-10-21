package com.minethunder.client.rack;

import com.minethunder.MineThunder;
import com.minethunder.client.RadarHud;
import com.minethunder.missile.MissileFireworkItem;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * 挂架选择输入处理：
 * - 打开时：数字键 1-9 选择快捷栏，鼠标点击槽位选择，点击面板外关闭。
 * - 关闭：重新捕获鼠标。
 * 打开期间游戏不暂停（不使用 Screen，仅释放鼠标）。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class RackSelectInput {
    private RackSelectInput() {}

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!RackSelectState.open) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        int key = event.getKey();
        // 数字键 1-9 → 快捷栏 0-8
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            int slot = key - GLFW.GLFW_KEY_1;
            selectSlot(slot);
        }
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!RackSelectState.open) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (RackSelectRenderer.isOverPanel(mc, w, h)) {
                int hovered = RackSelectState.hoveredSlot;
                if (hovered >= 0) {
                    selectSlot(hovered);
                }
            } else {
                // 点击面板外 → 关闭并重新捕获鼠标
                close();
            }
            event.setCanceled(true);
        }
    }

    private static void selectSlot(int inventoryIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var stack = mc.player.getInventory().getItem(inventoryIndex);
        if (RackSelectRenderer.isRackItem(stack)) {
            RackSelectState.selectedSlot = inventoryIndex;
            if (stack.getItem() instanceof MissileFireworkItem firework) {
                RadarHud.selectedMissile = firework.getSpec().guidance;
                RadarHud.selectedSpec = firework.getSpec();
            }
        }
    }

    public static void open() {
        RackSelectState.open = true;
        Minecraft.getInstance().mouseHandler.releaseMouse();
    }

    public static void close() {
        RackSelectState.open = false;
        RackSelectState.hoveredSlot = -1;
        Minecraft.getInstance().mouseHandler.grabMouse();
    }

    public static void toggle() {
        if (RackSelectState.open) {
            close();
        } else {
            open();
        }
    }
}
