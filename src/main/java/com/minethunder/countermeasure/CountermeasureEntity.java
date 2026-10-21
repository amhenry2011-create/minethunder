package com.minethunder.countermeasure;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/**
 * 箔条/热焰弹假目标。用投掷物承载，寿命结束后消失；
 * 导弹在导引头视场内优先追踪有效的假目标。
 */
public class CountermeasureEntity extends ThrowableItemProjectile {
    private static final int LIFETIME_TICKS = 100;

    public CountermeasureEntity(EntityType<? extends CountermeasureEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.SNOWBALL;
    }

    public int getRemainingTicks() {
        return Math.max(0, LIFETIME_TICKS - tickCount);
    }

    public Countermeasure type() {
        if (getItem().is(Items.FIRE_CHARGE)) return Countermeasure.FLARE;
        if (getItem().is(Items.GUNPOWDER)) return Countermeasure.ECM;
        return Countermeasure.CHAFF;
    }

    public boolean deceives(Countermeasure.MissileThreat threat) {
        return switch (threat) {
            case RADAR -> type() == Countermeasure.CHAFF || type() == Countermeasure.ECM;
            case INFRARED -> type() == Countermeasure.FLARE;
        };
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount > LIFETIME_TICKS) {
            discard();
        }
    }

    @Override
    protected void onHit(HitResult result) {
        if (!level().isClientSide) {
            discard();
        }
    }
}
