package com.minethunder.radar;

import com.minethunder.network.BvrSyncPacket;
import com.minethunder.network.Network;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 BVR 航迹管理：
 * - 航迹源：其他玩家、带 minethunder_bvr 标签的实体（由 BvrTicketService 保持加载）、飞行中导弹。
 * - 每 2 tick 组装快照，按接收者维度/距离过滤后发送 {@link BvrSyncPacket}。
 * - 远程锁定（ARH 数据链）通过 ServerRadarState 的 bvrTargetUuid 完成，这里只提供采样。
 */
@EventBusSubscriber(modid = com.minethunder.MineThunder.MODID)
public final class BvrTrackManager {
    private BvrTrackManager() {}

    /** 目标航迹最大发布距离（60=600 格 → 1000 格档）。 */
    public static final double RANGE = 1000.0;
    /** 非本机导弹只发布到 600 格内。 */
    public static final double MISSILE_RANGE = 600.0;
    /** 每次快照最多发布的航迹数。 */
    private static final int MAX_TRACKS = 48;

    private static final int UPDATE_TICKS = 2;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % UPDATE_TICKS != 0) return;
        List<BvrTrack> snapshot = collect(server);
        if (snapshot.isEmpty()) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            // 只向开雷达的玩家发布远程航迹，避免关雷达时仍收到全图航迹。
            if (!ServerRadarState.isRadarOn(viewer)) continue;
            List<BvrTrack> visible = filter(viewer, snapshot);
            if (visible.isEmpty()) continue;
            Network.sendToPlayer(new BvrSyncPacket(visible), viewer);
        }
    }

    private static List<BvrTrack> collect(MinecraftServer server) {
        List<BvrTrack> tracks = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                tracks.add(new BvrTrack(player.getUUID(), BvrKind.PLAYER,
                        player.getUUID(), player.getGameProfile().getName(),
                        player.getBoundingBox().getCenter(), player.getDeltaMovement(),
                        player.getId()));
            }
            for (Entity entity : level.getAllEntities()) {
                UUID uuid = entity.getUUID();
                if (entity instanceof net.minecraft.world.entity.player.Player) continue;
                if (entity.getTags().contains(BvrTicketService.BVR_TAG)) {
                    tracks.add(new BvrTrack(uuid, classify(entity),
                            null, nameOf(entity),
                            entity.getBoundingBox().getCenter(), entity.getDeltaMovement(),
                            entity.getId()));
                } else if (entity instanceof com.minethunder.missile.MissileArrow missile) {
                    Entity owner = missile.getOwner();
                    tracks.add(new BvrTrack(uuid, BvrKind.MISSILE,
                            owner == null ? null : owner.getUUID(),
                            "MSL",
                            entity.getBoundingBox().getCenter(), entity.getDeltaMovement(),
                            entity.getId()));
                }
                if (tracks.size() >= MAX_TRACKS * 2) return tracks;
            }
        }
        return tracks;
    }

    private static BvrKind classify(Entity entity) {
        var targetClass = RadarCore.classify(entity);
        return targetClass == RadarTargetClass.AIR ? BvrKind.AI : BvrKind.SURFACE;
    }

    private static String nameOf(Entity entity) {
        if (entity.getCustomName() != null) return entity.getCustomName().getString();
        return entity.getName().getString();
    }

    private static List<BvrTrack> filter(ServerPlayer viewer, List<BvrTrack> tracks) {
        List<BvrTrack> visible = new ArrayList<>();
        Vec3 eye = viewer.getEyePosition();
        for (BvrTrack track : tracks) {
            double distance = track.position().distanceTo(eye);
            if (distance < 1.0 || distance > RANGE) continue;
            if (track.kind() == BvrKind.MISSILE) {
                boolean own = viewer.getUUID().equals(track.ownerUuid());
                if (!own && distance > MISSILE_RANGE) continue;
            }
            visible.add(track);
        }
        if (visible.size() > MAX_TRACKS) {
            visible = new ArrayList<>(visible.subList(0, MAX_TRACKS));
        }
        return visible;
    }

    /** 供导弹中段数据链按 UUID 解析目标实体。 */
    @Nullable
    public static Entity findEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }
}
