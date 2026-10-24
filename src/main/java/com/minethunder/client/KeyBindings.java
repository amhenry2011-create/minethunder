package com.minethunder.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

import com.minethunder.MineThunder;

/**
 * 键位绑定：还原战雷现代空战的默认快捷键，且注册进 MC 设置（选项 → 按键绑定）可自行修改。
 *
 * 战雷默认键（键盘默认，用户可在 MC 设置中改绑）：
 * - Alt 组合：Alt+B 模式、Alt+R 雷达、Alt+Q 距离、Alt+D 扫描宽度、Alt+W ACM
 * - 组合键：Alt+F 锁定、Alt+T 选目标、Alt+X 导引头、Alt+E 干扰弹
 * - 单键：X 解锁、方向键扫描、右 Shift 回中、H 帮助、Enter 挂架
 * - 切换导弹类型：C（额外保留）
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class KeyBindings {
    private KeyBindings() {}

    public static final String CATEGORY = "key.categories.minethunder";
    public static final String PLANE_CATEGORY = "key.categories.minethunder.plane";

    /** 切换导弹类型（SARH / ARH / IR） */
    public static final KeyMapping CYCLE_MISSILE_TYPE = new KeyMapping(
            "key.minethunder.missile_type", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);

    /** 挂架选择（Enter）：呼出鼠标、不暂停 */
    public static final KeyMapping RACK_SELECT = new KeyMapping(
            "key.minethunder.rack_select_enter", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);

    /** 雷达扫描中心回中（默认右 Shift）。 */
    public static final KeyMapping RECENTER = new KeyMapping(
            "key.minethunder.recenter", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY);

    // 简单飞机（Simple Planes）冷舱启动与飞行控制系统按键
    public static final KeyMapping PLANE_BATTERY = new KeyMapping(
            "key.minethunder.plane_battery", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, PLANE_CATEGORY);
    public static final KeyMapping PLANE_FUEL_PUMP = new KeyMapping(
            "key.minethunder.plane_fuel_pump", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, PLANE_CATEGORY);
    public static final KeyMapping PLANE_MAGNETO = new KeyMapping(
            "key.minethunder.plane_magneto", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, PLANE_CATEGORY);
    public static final KeyMapping PLANE_STARTER = new KeyMapping(
            "key.minethunder.plane_starter", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, PLANE_CATEGORY);
    public static final KeyMapping PLANE_THROTTLE_UP = new KeyMapping(
            "key.minethunder.plane_throttle_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, PLANE_CATEGORY);
    public static final KeyMapping PLANE_THROTTLE_DOWN = new KeyMapping(
            "key.minethunder.plane_throttle_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, PLANE_CATEGORY);
    public static final KeyMapping PLANE_YAW_LEFT = new KeyMapping(
            "key.minethunder.plane_yaw_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, PLANE_CATEGORY);
    public static final KeyMapping PLANE_YAW_RIGHT = new KeyMapping(
            "key.minethunder.plane_yaw_right", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, PLANE_CATEGORY);
    public static final KeyMapping PLANE_FLAPS_UP = new KeyMapping(
            "key.minethunder.plane_flaps_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, PLANE_CATEGORY);
    public static final KeyMapping PLANE_FLAPS_DOWN = new KeyMapping(
            "key.minethunder.plane_flaps_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, PLANE_CATEGORY);
    public static final KeyMapping PLANE_GEAR = new KeyMapping(
            "key.minethunder.plane_gear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, PLANE_CATEGORY);
    public static final KeyMapping PLANE_PARK_BRAKE = new KeyMapping(
            "key.minethunder.plane_park_brake", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, PLANE_CATEGORY);
    public static final KeyMapping PLANE_TRIM_DOWN = new KeyMapping(
            "key.minethunder.plane_trim_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, PLANE_CATEGORY);
    public static final KeyMapping PLANE_TRIM_UP = new KeyMapping(
            "key.minethunder.plane_trim_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, PLANE_CATEGORY);

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CYCLE_MISSILE_TYPE);
        event.register(RACK_SELECT);
        event.register(RECENTER);
        event.register(PLANE_BATTERY);
        event.register(PLANE_FUEL_PUMP);
        event.register(PLANE_MAGNETO);
        event.register(PLANE_STARTER);
        event.register(PLANE_THROTTLE_UP);
        event.register(PLANE_THROTTLE_DOWN);
        event.register(PLANE_YAW_LEFT);
        event.register(PLANE_YAW_RIGHT);
        event.register(PLANE_FLAPS_UP);
        event.register(PLANE_FLAPS_DOWN);
        event.register(PLANE_GEAR);
        event.register(PLANE_PARK_BRAKE);
        event.register(PLANE_TRIM_DOWN);
        event.register(PLANE_TRIM_UP);
    }
}
