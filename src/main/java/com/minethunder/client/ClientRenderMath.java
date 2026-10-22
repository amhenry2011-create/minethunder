package com.minethunder.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** 渲染帧内的玩家/扫描线插值，避免世界空间波束使用 tick 值产生抖动。 */
public final class ClientRenderMath {
    private ClientRenderMath() {}

    public static float yaw(Player player, float partialTick) {
        return Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
    }

    public static float pitch(Player player, float partialTick) {
        return Mth.lerp(partialTick, player.xRotO, player.getXRot());
    }

    public static Vec3 eye(Player player, float partialTick) {
        return player.getEyePosition(partialTick);
    }

    public static Vec3 look(Player player, float partialTick) {
        return Vec3.directionFromRotation(pitch(player, partialTick),
                yaw(player, partialTick)).normalize();
    }

    public static float sweepAzimuth(float partialTick) {
        return Mth.rotLerp(partialTick, RadarHud.prevSweepAzimuthDeg,
                RadarHud.sweepAzimuthDeg);
    }

    public static float sweepElevation(float partialTick) {
        return Mth.lerp(partialTick, RadarHud.prevSweepElevationDeg,
                RadarHud.sweepElevationDeg);
    }

    public static Vec3 entityCenter(Entity entity, float partialTick) {
        return entity.getPosition(partialTick)
                .add(0.0, entity.getBbHeight() * 0.5, 0.0);
    }
}
