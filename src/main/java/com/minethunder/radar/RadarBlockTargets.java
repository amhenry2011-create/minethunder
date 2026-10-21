package com.minethunder.radar;

import net.minecraft.world.entity.Marker;

/** 可动方块结构使用的隐形雷达标记。 */
public final class RadarBlockTargets {
    private RadarBlockTargets() {}

    public static final String PREFIX = "mt_block_";
    public static final String AIR = PREFIX + "air";
    public static final String AIR_STATIC = PREFIX + "air_static";
    public static final String GROUND = PREFIX + "ground";
    public static final String SEA = PREFIX + "sea";

    public static String name(Marker marker) {
        return marker.getCustomName() == null ? ""
                : marker.getCustomName().getString();
    }

    public static boolean isBlockTarget(Marker marker) {
        return name(marker).startsWith(PREFIX);
    }

    public static boolean isStatic(Marker marker) {
        return name(marker).equals(AIR_STATIC);
    }

    public static RadarTargetClass targetClass(Marker marker) {
        return switch (name(marker)) {
            case AIR, AIR_STATIC -> RadarTargetClass.AIR;
            case GROUND -> RadarTargetClass.GROUND;
            case SEA -> RadarTargetClass.SEA;
            default -> null;
        };
    }
}
