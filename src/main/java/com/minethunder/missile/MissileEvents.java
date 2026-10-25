package com.minethunder.missile;

import com.minethunder.MineThunder;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RadarMode;
import com.minethunder.radar.SeekerLockModel;
import com.minethunder.radar.ServerRadarState;
import com.minethunder.sensor.SensorDebugState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 导弹发射规则（服务端权威）：
 * - 红外弹：拉弓必须拉满（20 ticks，导引头开机），且视线内有可锁定目标，放手才发射。
 * - 雷达弹：使用客户端同步的锁定/导引头状态做服务端权威校验。
 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class MissileEvents {
    private MissileEvents() {}

    /** 拉满弓所需的拉弓 ticks（原版满弓） */
    private static final int FULL_DRAW_TICKS = 20;

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerRadarState.clear(player);
            SensorDebugState.clear(player);
            com.minethunder.network.CountermeasurePacket.clearCooldown(player);
            IrHeat.clearPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onArrowLoose(ArrowLooseEvent event) {
        Player player = event.getEntity();
        ItemStack launcher = event.getBow();
        if (!(launcher.getItem() instanceof BowItem)) return;

        MissileSpec spec = findBowMissile(player, launcher);
        if (spec == null) return;

        if (spec.guidance == MissileSpec.Guidance.IR) {
            // 旧弓兼容路径：仍要求满弓。
            if (event.getCharge() < FULL_DRAW_TICKS) {
                cancel(event, player, "红外弹需拉满弓以完成导引头预热");
                return;
            }
            var target = IrTargeting.findTarget(
                    player.level(), player, null, spec);
            if (target == null) {
                cancel(event, player, "导引头视场内无有效热源目标");
            }
            return;
        }

        // 雷达弹：服务端使用客户端同步的锁定/导引头状态做权威校验（含 BVR 远程锁）
        ServerRadarState.LockState lock = ServerRadarState.get(player);
        Entity target = ServerRadarState.getTarget(player);
        if (target == null) {
            target = ServerRadarState.getBvrTarget(player);
        }
        if (lock == null || !lock.radarOn() || target == null || !target.isAlive()) {
            cancel(event, player, "无有效雷达锁定，发射取消");
            return;
        }
        if (spec.guidance == MissileSpec.Guidance.ARH
                && !ServerRadarState.canLaunchArh(player)) {
            cancel(event, player, "ARH 需雷达跟踪或硬锁定目标");
            return;
        }
        if (spec.guidance == MissileSpec.Guidance.SARH
                && !ServerRadarState.canLaunchSarh(player)) {
            cancel(event, player, "SARH 需等待红色锁定环");
            return;
        }
        if (player.distanceTo(target) > spec.maxDistance) {
            cancel(event, player, "超出导弹最大射程");
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 to = targetPos.subtract(eye);
        if (to.lengthSqr() > 1.0E-6) {
            double angleDeg = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, look.dot(to.normalize())))));
            if (angleDeg > spec.lockAngleMaxDeg) {
                cancel(event, player, "目标超出导弹导引头最大离轴角");
                return;
            }
        }
        if (RadarCore.isOccludedForTarget(player.level(), eye, targetPos,
                RadarCore.classify(target))) {
            cancel(event, player, "目标被地形遮挡，无法照射");
            return;
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ServerRadarState.clearAll();
    }

    private static MissileSpec findBowMissile(Player player, ItemStack bow) {
        ItemStack ammo = player.getProjectile(bow);
        return ammo.getItem() instanceof MissileItem missile ? missile.getSpec() : null;
    }

    private static void cancel(ArrowLooseEvent event, Player player, String message) {
        event.setCanceled(true);
        if (message != null && !message.isEmpty()) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }
}
