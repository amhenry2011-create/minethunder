package com.minethunder.plane;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 简单飞机发动机物品：右键飞机实体可直接换装对应发动机。
 */
public class PlaneEngineItem extends Item {
    private final PlaneEngineType engineType;

    public PlaneEngineItem(Properties properties, PlaneEngineType engineType) {
        super(properties);
        this.engineType = engineType;
    }

    public PlaneEngineType getEngineType() {
        return engineType;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(engineType.displayName()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal(String.format("推力倍率: %.0f%% | 加力燃烧: %s",
                        engineType.thrustMultiplier() * 100.0,
                        engineType.afterburnerCapable() ? "支持 (WEP/AB)" : "无"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("对着飞机潜行+右键可换装此航空发动机")
                .withStyle(ChatFormatting.DARK_AQUA));
    }
}
