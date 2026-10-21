package com.minethunder.missile;

import com.minethunder.MineThunder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 玩家在鞘翅飞行时使用烟花，短暂提高自身 IR 热源。 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class IrHeatEvents {
    private IrHeatEvents() {}

    @SubscribeEvent
    public static void onUseFirework(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (player.isFallFlying() && event.getItemStack().is(Items.FIREWORK_ROCKET)) {
            IrHeat.boostPlayer(player);
        }
    }
}
