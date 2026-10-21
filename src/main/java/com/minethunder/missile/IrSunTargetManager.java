package com.minethunder.missile;

import com.minethunder.MineThunder;
import com.minethunder.Registration;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 为每位玩家维护一个太阳 IR 热源标记。 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class IrSunTargetManager {
    private IrSunTargetManager() {}

    private static final String PREFIX = "mt_heat_sun_";
    private static final double DISTANCE = 64.0;
    private static final Map<UUID, SunHeatMarker> MARKERS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.getGameTime() % 10 != 0) continue;
            for (ServerPlayer player : level.players()) {
                SunHeatMarker marker = MARKERS.get(player.getUUID());
                if (marker != null && (marker.isRemoved() || marker.level() != level)) {
                    marker.discard();
                    MARKERS.remove(player.getUUID());
                    marker = null;
                }
                if (marker == null) {
                    marker = Registration.SUN_HEAT_MARKER.get().create(level);
                    if (marker == null) continue;
                    marker.setCustomName(Component.literal(PREFIX + player.getUUID()));
                    marker.setCustomNameVisible(false);
                    marker.setInvisible(true);
                    marker.setNoGravity(true);
                    marker.setInvulnerable(true);
                    marker.setSilent(true);
                    level.addFreshEntity(marker);
                    MARKERS.put(player.getUUID(), marker);
                }
                float angle = level.getSunAngle(1.0F);
                Vec3 direction = new Vec3(-Math.sin(angle), Math.cos(angle), 0.0).normalize();
                Vec3 pos = player.getEyePosition().add(direction.scale(DISTANCE));
                marker.setPos(pos.x, pos.y, pos.z);
                marker.setDeltaMovement(Vec3.ZERO);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SunHeatMarker marker = MARKERS.remove(event.getEntity().getUUID());
        if (marker != null) marker.discard();
    }
}
