package com.minethunder.missile;

import com.minethunder.Registration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** 从背包中的导弹烟花直接创建制导弹体。 */
public final class MissileLaunchService {
    private MissileLaunchService() {}

    public static MissileArrow launch(ServerLevel level, ServerPlayer player,
                                      MissileSpec spec, @Nullable Entity target) {
        EntityType<? extends MissileArrow> type = switch (spec.guidance) {
            case SARH -> Registration.SARH_MISSILE.get();
            case ARH -> Registration.ARH_MISSILE.get();
            case IR -> Registration.IR_MISSILE.get();
        };
        MissileArrow missile = type.create(level);
        if (missile == null) return null;
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 spawn = player.getEyePosition().add(look.scale(0.8));
        missile.setPos(spawn.x, spawn.y - 0.1, spawn.z);
        missile.setOwner(player);
        if (missile instanceof SarhMissileArrow sarh) {
            sarh.setShooter(player);
        }
        if (target != null) {
            missile.setTarget(target);
        }
        missile.initializeLaunch(player);
        level.addFreshEntity(missile);
        return missile;
    }
}
