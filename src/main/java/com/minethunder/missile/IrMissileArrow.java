package com.minethunder.missile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 红外弹（类似 AIM-9）：发射即锁热源，全程追目标，可被热焰弹欺骗。
 */
public class IrMissileArrow extends MissileArrow {
    public IrMissileArrow(EntityType<? extends MissileArrow> type, Level level, MissileSpec spec) {
        super(type, level, spec);
    }

    @Override
    protected Entity resolveTarget() {
        return getTarget();
    }
}
