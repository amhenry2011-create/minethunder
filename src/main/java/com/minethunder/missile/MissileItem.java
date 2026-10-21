package com.minethunder.missile;

import com.minethunder.radar.ServerRadarState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * 导弹物品（箭）：用弓发射时生成对应的导弹箭实体。
 * 这是"弓 = 发射挂架，箭 = 导弹"的实现（SPEC §0 / 需求 §3.2）。
 */
public class MissileItem extends ArrowItem {
    private final MissileSpec spec;
    private final Supplier<? extends EntityType<? extends MissileArrow>> entityType;

    public MissileItem(Properties properties, MissileSpec spec,
                       Supplier<? extends EntityType<? extends MissileArrow>> entityType) {
        super(properties);
        this.spec = spec;
        this.entityType = entityType;
    }

    public MissileSpec getSpec() {
        return spec;
    }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack ammoStack, LivingEntity shooter, @Nullable ItemStack weaponStack) {
        MissileArrow arrow = entityType.get().create(level);
        if (arrow == null) {
            return super.createArrow(level, ammoStack, shooter, weaponStack);
        }
        arrow.setPos(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
        arrow.setOwner(shooter);
        if (!level.isClientSide && shooter instanceof ServerPlayer serverPlayer) {
            if (arrow instanceof SarhMissileArrow sarh) {
                sarh.setShooter(serverPlayer);
            }
            if (spec.guidance != MissileSpec.Guidance.IR) {
                Entity locked = ServerRadarState.getTarget(serverPlayer);
                if (locked != null) {
                    arrow.setTarget(locked);
                }
            }
        }
        // 必须在设置目标后初始化，才能计算预测拦截点。
        arrow.initializeLaunch(shooter);
        return arrow;
    }
}
