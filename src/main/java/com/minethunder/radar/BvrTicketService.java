package com.minethunder.radar;

import com.minethunder.MineThunder;
import com.minethunder.missile.MissileArrow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端区块 ticket：
 * - 导弹实体保持 5×5 实体 ticking 区块，飞出玩家加载范围后仍持续 tick，不再悬停卡空。
 * - 带 {@code minethunder_bvr} 标签的远程目标保持加载，作为 BVR 数据链航迹源。
 *
 * Ticket 使用 {@link TicketType#FORCED}、distance=2、forceTicks=true：
 * ticketLevel = 33 - 2 = 31，即 ENTITY_TICKING。
 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class BvrTicketService {
    private BvrTicketService() {}

    public static final String BVR_TAG = "minethunder_bvr";
    /** ticket 半径：2 → 5×5 区块，实体 tick 级别。 */
    private static final int RADIUS = 2;
    /** 每 tick 全服最多维护多少组 ticket（导弹优先）。 */
    private static final int MAX_TICKETS = 64;

    private static final int SWEEP_TICKS = 5;

    private record Entry(ServerLevel level, ChunkPos center) {}
    /** entity UUID -> 当前 ticket */
    private static final Map<UUID, Entry> TICKETS = new HashMap<>();

    private static boolean isTrackable(Entity entity) {
        return entity instanceof MissileArrow || entity.getTags().contains(BVR_TAG);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_TICKS != 0) return;

        List<Entity> candidates = new ArrayList<>();
        List<Entity> missiles = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!entity.isAlive() || !isTrackable(entity)) continue;
                (entity instanceof MissileArrow ? missiles : candidates).add(entity);
            }
        }

        List<Entity> seen = new ArrayList<>();
        seen.addAll(candidates);
        seen.addAll(missiles);
        List<UUID> stale = new ArrayList<>();
        for (UUID uuid : TICKETS.keySet()) {
            boolean alive = false;
            for (Entity entity : seen) {
                if (entity.getUUID().equals(uuid)) {
                    alive = true;
                    break;
                }
            }
            if (!alive) stale.add(uuid);
        }
        for (UUID uuid : stale) {
            removeTicket(uuid);
        }

        int i = 0;
        for (Entity missile : missiles) {
            ensure(missile, i++);
        }
        for (Entity target : candidates) {
            ensure(target, i++);
        }

        if (TICKETS.size() > MAX_TICKETS) {
            // 超限时从最旧的开始撤销；数量极少，可接受无序撤销。
            List<UUID> overflow = new ArrayList<>(TICKETS.keySet());
            for (UUID uuid : overflow) {
                if (TICKETS.size() <= MAX_TICKETS) break;
                removeTicket(uuid);
            }
        }
    }

    private static void ensure(Entity entity, int index) {
        if (index >= MAX_TICKETS) return;
        Entry current = TICKETS.get(entity.getUUID());
        ChunkPos now = entity.chunkPosition();
        if (current != null) {
            if (current.level() == entity.level() && current.center().equals(now)) return;
            removeTicketAt(current);
        }
        if (entity.level() instanceof ServerLevel level) {
            level.getChunkSource().addRegionTicket(TicketType.FORCED, now, RADIUS, now, true);
            TICKETS.put(entity.getUUID(), new Entry(level, now));
        }
    }

    public static void removeTicket(UUID uuid) {
        Entry entry = TICKETS.remove(uuid);
        if (entry != null) removeTicketAt(entry);
    }

    private static void removeTicketAt(Entry entry) {
        entry.level().getChunkSource().removeRegionTicket(TicketType.FORCED,
                entry.center(), RADIUS, entry.center(), true);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (UUID uuid : new ArrayList<>(TICKETS.keySet())) {
            removeTicket(uuid);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        removeTicket(event.getEntity().getUUID());
    }
}
