package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.network.Network;
import com.minethunder.network.PlaneControlPacket;
import com.minethunder.plane.PlaneEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 飞机驾驶舱客户端输入处理器：
 * 仅在玩家骑乘 {@link PlaneEntity} 时生效，且自动避开所有 Alt/Ctrl 雷达与导弹组合键。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class PlaneClientInput {
    private PlaneClientInput() {}

    private static int pendingActions = 0;

    @SubscribeEvent
    public static void onKeyPress(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (!(mc.player.getVehicle() instanceof PlaneEntity)) return;

        // 任何带 Alt 或 Ctrl 的组合键全部让给雷达/导弹/干扰弹系统，绝不冲突
        int mods = event.getModifiers();
        if ((mods & (GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_CONTROL)) != 0) {
            return;
        }

        int key = event.getKey();
        if (matches(KeyBindings.PLANE_BATTERY, key)) {
            pendingActions |= PlaneControlPacket.ACT_TOGGLE_BATTERY;
        } else if (matches(KeyBindings.PLANE_FUEL_PUMP, key)) {
            pendingActions |= PlaneControlPacket.ACT_TOGGLE_FUEL_PUMP;
        } else if (matches(KeyBindings.PLANE_MAGNETO, key)) {
            pendingActions |= PlaneControlPacket.ACT_CYCLE_MAGNETO;
        } else if (matches(KeyBindings.PLANE_FLAPS_UP, key)) {
            pendingActions |= PlaneControlPacket.ACT_FLAPS_UP;
        } else if (matches(KeyBindings.PLANE_FLAPS_DOWN, key)) {
            pendingActions |= PlaneControlPacket.ACT_FLAPS_DOWN;
        } else if (matches(KeyBindings.PLANE_GEAR, key)) {
            pendingActions |= PlaneControlPacket.ACT_TOGGLE_GEAR;
        } else if (matches(KeyBindings.PLANE_PARK_BRAKE, key)) {
            pendingActions |= PlaneControlPacket.ACT_TOGGLE_PARK_BRK;
        } else if (matches(KeyBindings.PLANE_TRIM_DOWN, key)) {
            pendingActions |= PlaneControlPacket.ACT_TRIM_DOWN;
        } else if (matches(KeyBindings.PLANE_TRIM_UP, key)) {
            pendingActions |= PlaneControlPacket.ACT_TRIM_UP;
        } else if (key == GLFW.GLFW_KEY_BACKSLASH) {
            pendingActions |= PlaneControlPacket.ACT_TRIM_RESET;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof PlaneEntity plane)) {
            pendingActions = 0;
            return;
        }
        if (mc.screen != null) {
            Network.sendToServer(new PlaneControlPacket(0, 0, 0, 0, false, false, 0));
            pendingActions = 0;
            return;
        }

        long win = mc.getWindow().getWindow();
        boolean altDown = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean ctrlDown = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);

        float pitchIn = 0.0f;
        float rollIn = 0.0f;
        float yawIn = 0.0f;
        float throttleDelta = 0.0f;
        boolean wheelBrake = false;
        boolean starterHold = false;

        if (!altDown && !ctrlDown) {
            // 俯仰：S 拉杆抬头 (+1)，W 推杆低头 (-1)
            boolean pullUp = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_S);
            boolean pushDown = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_W);
            pitchIn = (pullUp ? 1.0f : 0.0f) - (pushDown ? 1.0f : 0.0f);

            // 滚转：D 右滚 (+1)，A 左滚 (-1)
            boolean rollRight = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_D);
            boolean rollLeft = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_A);
            rollIn = (rollRight ? 1.0f : 0.0f) - (rollLeft ? 1.0f : 0.0f);

            // 偏航/前轮转向：C 左偏航？注意 C 是切导弹，因此偏航默认用 Z（左舵）与 N（右舵）
            boolean yawLeft = isDown(win, KeyBindings.PLANE_YAW_LEFT);
            boolean yawRight = isDown(win, KeyBindings.PLANE_YAW_RIGHT);
            yawIn = (yawRight ? 1.0f : 0.0f) - (yawLeft ? 1.0f : 0.0f);

            // 节流阀加减油门：默认 R 推油门 / Y 收油门（或 PageUp / PageDown），不占方向键（方向键用于雷达波束扫描）
            boolean thrUp = isDown(win, KeyBindings.PLANE_THROTTLE_UP)
                    || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_PAGE_UP);
            boolean thrDown = isDown(win, KeyBindings.PLANE_THROTTLE_DOWN)
                    || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_PAGE_DOWN);
            if (thrUp) throttleDelta += 0.018f;
            if (thrDown) throttleDelta -= 0.018f;

            // 机轮点刹：Space
            wheelBrake = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_SPACE);

            // 起动机点火长按：I（当发动机未启动时按住 I 起动；发动机已运行时 I 仍保留切红外弹兼容）
            starterHold = isDown(win, KeyBindings.PLANE_STARTER);
        }

        int actions = pendingActions;
        pendingActions = 0;

        // 主电瓶未通电时，自动保持机载雷达处于断电待机状态
        if (!plane.isMasterBatteryOn() && RadarHud.radarOn) {
            RadarHud.radarOn = false;
        }

        Network.sendToServer(new PlaneControlPacket(
                pitchIn, rollIn, yawIn, throttleDelta, wheelBrake, starterHold, actions));
    }

    private static boolean matches(net.minecraft.client.KeyMapping mapping, int key) {
        return mapping.getKey().getType() == InputConstants.Type.KEYSYM
                && mapping.getKey().getValue() == key;
    }

    private static boolean isDown(long window, net.minecraft.client.KeyMapping mapping) {
        if (mapping.getKey().getType() != InputConstants.Type.KEYSYM) return false;
        int code = mapping.getKey().getValue();
        return code > 0 && InputConstants.isKeyDown(window, code);
    }
}
