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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端区块 ticket：
 * - 所有导弹实体（MissileArrow 及子类）一律持票 5×5 实体 ticking 区块、不设上限，
 *   飞出玩家加载范围后仍持续 tick，不再悬停卡空。
 * - 带 {@code minethunder_bvr} 标签的远程目标保持加载（上限 {@link #MAX_TICKETS}），
 *   作为 BVR 数据链航迹源。
 * - 显式登记的目标（{@link #registerManualTarget}）按 UUID 直接续票，不依赖实体枚举结果。
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
    /** 每 tick 全服最多维护多少组标签远景目标 ticket；导弹一律持票、不受此上限。 */
    private static final int MAX_TICKETS = 64;

    private static final int SWEEP_TICKS = 5;
    /** 连续多少个 sweep 找不到实体后才撤票/除名（5 tick × 10 = 2.5 s）。 */
    private static final int STALE_SWEEPS = 10;

    private record Entry(ServerLevel level, ChunkPos center) {}
    /** entity UUID -> 当前 ticket */
    private static final Map<UUID, Entry> TICKETS = new HashMap<>();
    /** 当前持票导弹（不受 MAX_TICKETS 上限，一律强加载）。 */
    private static final Set<UUID> MISSILE_TICKETS = new HashSet<>();
    /** 显式登记的目标 UUID -> 连续未找到次数。 */
    private static final Map<UUID, Integer> MANUAL = new HashMap<>();
    /** 已持票实体连续未枚举到的次数；用于容忍偶发枚举失败。 */
    private static final Map<UUID, Integer> TICKET_MISSES = new HashMap<>();

    /** 生成测试目标时登记，保证之后即使实体枚举遗漏也不会撤票丢目标。 */
    public static void registerManualTarget(Entity entity) {
        if (entity == null) return;
        MANUAL.put(entity.getUUID(), 0);
    }

    public static int ticketCount() {
        return TICKETS.size();
    }

    public static int manualCount() {
        return MANUAL.size();
    }

    public static Set<UUID> manualTargets() {
        return new HashSet<>(MANUAL.keySet());
    }

    /** 跨维度按 UUID 解析实体；供注册表保活与 BVR 采集兜底使用。 */
    @Nullable
    public static Entity findTrackedEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    private static boolean isTrackable(Entity entity) {
        return entity instanceof MissileArrow || entity.getTags().contains(BVR_TAG);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_TICKS != 0) return;

        List<Entity> candidates = new ArrayList<>();
        List<Entity> missiles = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!entity.isAlive() || !isTrackable(entity)) continue;
                seen.add(entity.getUUID());
                (entity instanceof MissileArrow ? missiles : candidates).add(entity);
            }
        }

        // 显式登记的目标：按 UUID 直接解析，避免远区块枚举遗漏导致丢目标。
        for (UUID uuid : new ArrayList<>(MANUAL.keySet())) {
            if (seen.contains(uuid)) {
                MANUAL.put(uuid, 0);
                continue;
            }
            Entity entity = findTrackedEntity(server, uuid);
            if (entity != null && entity.isAlive()) {
                seen.add(uuid);
                (entity instanceof MissileArrow ? missiles : candidates).add(entity);
                MANUAL.put(uuid, 0);
            } else {
                int misses = MANUAL.getOrDefault(uuid, 0) + 1;
                if (misses >= STALE_SWEEPS) {
                    MANUAL.remove(uuid);
                } else {
                    MANUAL.put(uuid, misses);
                }
            }
        }

        List<Entity> tracked = new ArrayList<>();
        tracked.addAll(missiles);
        tracked.addAll(candidates);
        int candidateSlots = MAX_TICKETS;
        for (Entity entity : tracked) {
            if (entity instanceof MissileArrow) {
                ensure(entity);
                continue;
            }
            if (candidateSlots <= 0) continue;
            candidateSlots--;
            ensure(entity);
        }

        // 已持票但本轮未枚举到的实体：先按 UUID 兜底解析，连续 STALE_SWEEPS 次失败才撤票。
        for (UUID uuid : new ArrayList<>(TICKETS.keySet())) {
            if (seen.contains(uuid)) {
                TICKET_MISSES.remove(uuid);
                continue;
            }
            Entity entity = findTrackedEntity(server, uuid);
            if (entity != null && entity.isAlive() && isTrackable(entity)) {
                ensure(entity);
                TICKET_MISSES.remove(uuid);
                continue;
            }
            int misses = TICKET_MISSES.getOrDefault(uuid, 0) + 1;
            if (misses >= STALE_SWEEPS) {
                removeTicket(uuid);
                TICKET_MISSES.remove(uuid);
            } else {
                TICKET_MISSES.put(uuid, misses);
            }
        }

        if (TICKETS.size() > MAX_TICKETS + MISSILE_TICKETS.size()) {
            // 超限时从最旧的开始撤销，但永远不撤导弹票；数量极少，可接受无序撤销。
            List<UUID> overflow = new ArrayList<>(TICKETS.keySet());
            for (UUID uuid : overflow) {
                if (TICKETS.size() - MISSILE_TICKETS.size() <= MAX_TICKETS) break;
                if (MISSILE_TICKETS.contains(uuid)) continue;
                removeTicket(uuid);
            }
        }
    }

    private static void ensure(Entity entity) {
        Entry current = TICKETS.get(entity.getUUID());
        ChunkPos now = entity.chunkPosition();
        if (current != null) {
            if (current.level() == entity.level() && current.center().equals(now)) return;
            removeTicketAt(current);
        }
        if (entity.level() instanceof ServerLevel level) {
            level.getChunkSource().addRegionTicket(TicketType.FORCED, now, RADIUS, now, true);
            TICKETS.put(entity.getUUID(), new Entry(level, now));
            if (entity instanceof MissileArrow) {
                MISSILE_TICKETS.add(entity.getUUID());
            } else {
                MISSILE_TICKETS.remove(entity.getUUID());
            }
        }
    }

    public static void removeTicket(UUID uuid) {
        Entry entry = TICKETS.remove(uuid);
        if (entry != null) removeTicketAt(entry);
        MISSILE_TICKETS.remove(uuid);
        TICKET_MISSES.remove(uuid);
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
        MANUAL.clear();
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        removeTicket(event.getEntity().getUUID());
        MANUAL.remove(event.getEntity().getUUID());
    }
}
