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
 * - Alt 组合：Alt+B 模式、Alt+R 雷达、Alt+V 回中、Alt+Q 距离、Alt+D 扫描宽度、Alt+W ACM
 * - 组合键：Alt+F 锁定、Alt+T 选目标、Alt+X 导引头、Alt+E 干扰弹
 * - 单键：X 解锁、方向键扫描、H 帮助、Enter 挂架
 * - 切换导弹类型：C（额外保留）
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class KeyBindings {
    private KeyBindings() {}

    public static final String CATEGORY = "key.categories.minethunder";

    /** 切换导弹类型（SARH / ARH / IR） */
    public static final KeyMapping CYCLE_MISSILE_TYPE = new KeyMapping(
            "key.minethunder.missile_type", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);

    /** 挂架选择（Enter）：呼出鼠标、不暂停 */
    public static final KeyMapping RACK_SELECT = new KeyMapping(
            "key.minethunder.rack_select_enter", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CYCLE_MISSILE_TYPE);
        event.register(RACK_SELECT);
    }
}
