package com.minethunder.missile;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 导弹烟花：存放在背包中，直接消耗发射；外观仍是原版烟花火箭。 */
public final class MissileFireworkItem extends FireworkRocketItem {
    private final MissileSpec spec;

    public MissileFireworkItem(Properties properties, MissileSpec spec) {
        super(properties);
        this.spec = spec;
    }

    public MissileSpec getSpec() {
        return spec;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 不作为普通烟花使用；由 Ctrl+Alt 发射。
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }
}
