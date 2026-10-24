package com.minethunder.radar;

import com.minethunder.missile.MissileArrow;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;

/** 雷达面板图标类型。 */
public enum RadarIconType {
    PLAYER,
    LIVING,
    MISSILE,
    BLOCK;

    public static RadarIconType from(Entity entity) {
        if (entity instanceof Player || entity instanceof com.minethunder.plane.PlaneEntity) return PLAYER;
        if (entity instanceof MissileArrow) return MISSILE;
        if (entity instanceof Marker) return BLOCK;
        return LIVING;
    }
}
