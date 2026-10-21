package com.minethunder.missile;

import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** IR 热度值：烟花、玩家临时增热、热焰弹、太阳和普通热源。 */
public final class IrHeat {
    private IrHeat() {}

    public static final double THRESHOLD = 100.0;
    private static final double PLAYER_BASE = 120.0;
    private static final double FIREWORK_BOOST = 120.0;
    private static final long BOOST_DURATION_TICKS = 200L;
    private static final Map<UUID, Long> BOOST_START = new ConcurrentHashMap<>();

    public static void boostPlayer(Player player) {
        BOOST_START.put(player.getUUID(), player.level().getGameTime());
    }

    public static void clearPlayer(Player player) {
        BOOST_START.remove(player.getUUID());
    }

    public static double heat(Entity entity) {
        if (entity instanceof SunHeatMarker marker
                && marker.getCustomName() != null
                && marker.getCustomName().getString().startsWith("mt_heat_sun_")) {
            return 1000.0;
        }
        if (entity instanceof FireworkRocketEntity) return 200.0;
        if (entity instanceof CountermeasureEntity flare
                && flare.type() == Countermeasure.FLARE) {
            return 200.0;
        }
        if (entity instanceof LivingEntity living) {
            double heat;
            if (living instanceof Player) {
                heat = PLAYER_BASE;
            } else if (living.getType().is(EntityTypeTags.UNDEAD)) {
                heat = 30.0;
            } else if (living instanceof Monster) {
                heat = 90.0;
            } else if (living instanceof Animal) {
                heat = 70.0;
            } else {
                heat = 60.0;
            }
            if (living instanceof Player player) {
                Long start = BOOST_START.get(player.getUUID());
                if (start != null) {
                    long elapsed = player.level().getGameTime() - start;
                    if (elapsed >= 0 && elapsed < BOOST_DURATION_TICKS) {
                        heat += FIREWORK_BOOST
                                * (1.0 - elapsed / (double) BOOST_DURATION_TICKS);
                    } else if (elapsed >= BOOST_DURATION_TICKS) {
                        BOOST_START.remove(player.getUUID());
                    }
                }
            }
            if (living.isOnFire()) heat += 80.0;
            return heat;
        }
        return 0.0;
    }
}
