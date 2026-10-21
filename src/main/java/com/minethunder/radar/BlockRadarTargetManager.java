package com.minethunder.radar;

import com.minethunder.MineThunder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端扫描活塞/可动方块结构，生成供雷达锁定的隐形 Marker。 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class BlockRadarTargetManager {
    private BlockRadarTargetManager() {}

    private static final int SCAN_INTERVAL = 40;
    private static final int SCAN_CHUNK_RADIUS = 1;
    private static final int STATIC_Y_RADIUS = 6;
    private static final Map<UUID, Marker> MARKERS = new ConcurrentHashMap<>();

    private record BlockCluster(BlockPos center, boolean moving, RadarTargetClass targetClass,
                                String markerName, Direction direction) {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.getGameTime() % SCAN_INTERVAL != 0) continue;
            refresh(level);
        }
    }

    private static void refresh(ServerLevel level) {
        Set<UUID> used = new HashSet<>();
        List<ServerPlayer> players = new ArrayList<>(level.players());
        Set<Long> seenClusters = new HashSet<>();

        for (ServerPlayer player : players) {
            BlockPos origin = player.blockPosition();
            int pcx = origin.getX() >> 4;
            int pcz = origin.getZ() >> 4;

            List<BlockPos> movingBlocks = new ArrayList<>();
            List<BlockPos> staticPistons = new ArrayList<>();

            for (int dx = -SCAN_CHUNK_RADIUS; dx <= SCAN_CHUNK_RADIUS; dx++) {
                for (int dz = -SCAN_CHUNK_RADIUS; dz <= SCAN_CHUNK_RADIUS; dz++) {
                    ChunkAccess access = level.getChunkSource().getChunk(
                            pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                    if (!(access instanceof LevelChunk chunk)) continue;

                    for (var be : chunk.getBlockEntities().values()) {
                        if (be instanceof PistonMovingBlockEntity piston) {
                            movingBlocks.add(piston.getBlockPos().immutable());
                        }
                    }

                    // 静止悬空结构：扫描附近的活塞头/活塞基座。
                    int minX = chunk.getPos().getMinBlockX();
                    int minZ = chunk.getPos().getMinBlockZ();
                    for (int x = minX; x < minX + 16; x++) {
                        for (int z = minZ; z < minZ + 16; z++) {
                            for (int y = origin.getY() - STATIC_Y_RADIUS;
                                 y <= origin.getY() + STATIC_Y_RADIUS; y++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                BlockState state = level.getBlockState(pos);
                                if (state.is(Blocks.PISTON_HEAD)
                                        || state.is(Blocks.PISTON)
                                        || state.is(Blocks.STICKY_PISTON)) {
                                    staticPistons.add(pos);
                                }
                            }
                        }
                    }
                }
            }

            for (BlockCluster cluster : cluster(level, movingBlocks, true)) {
                if (seenClusters.add(clusterKey(cluster.center()))) {
                    used.add(upsertMarker(level, cluster));
                }
            }
            for (BlockCluster cluster : cluster(level, staticPistons, false)) {
                if (!cluster.moving()
                        && cluster.targetClass() == RadarTargetClass.AIR
                        && seenClusters.add(clusterKey(cluster.center()))) {
                    used.add(upsertMarker(level, cluster));
                }
            }
        }

        // 清理本轮不再存在的方块目标。
        for (var entry : MARKERS.entrySet()) {
            UUID id = entry.getKey();
            Marker marker = entry.getValue();
            if (marker.level() == level && !used.contains(id)) {
                marker.discard();
                MARKERS.remove(id);
            }
        }
    }

    private static List<BlockCluster> cluster(ServerLevel level, List<BlockPos> positions,
                                              boolean moving) {
        List<List<BlockPos>> groups = new ArrayList<>();
        for (BlockPos pos : positions) {
            List<BlockPos> target = null;
            for (List<BlockPos> group : groups) {
                for (BlockPos other : group) {
                    if (other.distSqr(pos) <= 16.0) {
                        target = group;
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(pos);
        }

        List<BlockCluster> result = new ArrayList<>();
        for (List<BlockPos> group : groups) {
            if (group.isEmpty()) continue;
            double x = 0, y = 0, z = 0;
            for (BlockPos pos : group) {
                x += pos.getX() + 0.5;
                y += pos.getY() + 0.5;
                z += pos.getZ() + 0.5;
            }
            int n = group.size();
            BlockPos center = BlockPos.containing(x / n, y / n, z / n);
            int minY = group.stream().mapToInt(BlockPos::getY).min().orElse(center.getY());
            BlockPos supportProbe = new BlockPos(center.getX(), minY, center.getZ());

            boolean water = isWaterBelow(level, supportProbe);
            boolean supported = hasSupport(level, supportProbe);
            RadarTargetClass cls = water ? RadarTargetClass.SEA
                    : supported ? RadarTargetClass.GROUND : RadarTargetClass.AIR;
            String name;
            if (moving) {
                name = switch (cls) {
                    case AIR -> RadarBlockTargets.AIR;
                    case GROUND -> RadarBlockTargets.GROUND;
                    case SEA -> RadarBlockTargets.SEA;
                };
            } else {
                if (cls != RadarTargetClass.AIR) continue;
                name = RadarBlockTargets.AIR_STATIC;
            }
            result.add(new BlockCluster(center, moving, cls, name, Direction.UP));
        }
        return result;
    }

    private static UUID upsertMarker(ServerLevel level, BlockCluster cluster) {
        AABB area = new AABB(cluster.center()).inflate(4.0);
        for (Marker marker : level.getEntitiesOfClass(Marker.class, area,
                m -> RadarBlockTargets.isBlockTarget(m)
                        && RadarBlockTargets.name(m).equals(cluster.markerName()))) {
            marker.setPos(cluster.center().getX() + 0.5,
                    cluster.center().getY() + 2.5, cluster.center().getZ() + 0.5);
            marker.setDeltaMovement(0.0, 0.0, 0.0);
            MARKERS.put(marker.getUUID(), marker);
            return marker.getUUID();
        }

        Marker marker = EntityType.MARKER.create(level);
        if (marker == null) return UUID.randomUUID();
        marker.moveTo(cluster.center().getX() + 0.5, cluster.center().getY() + 2.5,
                cluster.center().getZ() + 0.5, 0.0F, 0.0F);
        marker.setCustomName(Component.literal(cluster.markerName()));
        marker.setCustomNameVisible(false);
        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setInvulnerable(true);
        marker.setSilent(true);
        if (cluster.moving()) {
            marker.setDeltaMovement(0.3, 0.0, 0.3);
        }
        level.addFreshEntity(marker);
        MARKERS.put(marker.getUUID(), marker);
        return marker.getUUID();
    }

    private static long clusterKey(BlockPos pos) {
        return ((long) (pos.getX() >> 2) << 42)
                ^ ((long) (pos.getY() >> 2) << 21)
                ^ (pos.getZ() >> 2);
    }

    private static boolean hasSupport(ServerLevel level, BlockPos center) {
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos below = center.below(dy);
            BlockState state = level.getBlockState(below);
            if (!state.getCollisionShape(level, below, CollisionContext.empty()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWaterBelow(ServerLevel level, BlockPos center) {
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos below = center.below(dy);
            if (!level.getFluidState(below).isEmpty()) return true;
        }
        return false;
    }
}
