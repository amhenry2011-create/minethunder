package com.minethunder.radar;

import com.minethunder.MineThunder;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileSpec;
import com.minethunder.network.Network;
import com.minethunder.network.RwrSyncPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端 RWR：雷达扫描、跟踪、锁定与雷达制导导弹告警。 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class RwrManager {
    private RwrManager() {}

    public static final double RANGE = 600.0;
    /** 以本机水平面为基准的垂直盲区半角；天顶/天底各 30°。 */
    public static final double VERTICAL_BLIND_DEG = 60.0;
    private static final int UPDATE_TICKS = 2;
    private static final int MAX_CONTACTS = 8;
    private static final int SEARCH_TTL_TICKS = 40;

    private record Paint(int sourceId, String tag, long tick) {}
    /** target UUID -> source UUID -> 最近一次 SRC PD 扫描命中。 */
    private static final Map<UUID, Map<UUID, Paint>> PAINTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_PAINT = new ConcurrentHashMap<>();

    private record TestSource(int sourceId, RwrThreat threat, double bearingDeg,
                              double range, String tag, long createdAt, boolean timed,
                              boolean moving) {}
    private static final Map<UUID, List<TestSource>> TEST_SOURCES = new ConcurrentHashMap<>();
    private static int nextTestId = -10;

    public static void setTestSource(ServerPlayer player, RwrThreat threat) {
        TEST_SOURCES.remove(player.getUUID());
        if (threat != null) {
            addTestSource(player, threat, 32.0, 180.0, threat == RwrThreat.SEARCH);
        }
    }

    public static void addTestSource(ServerPlayer player, RwrThreat threat,
                                     double bearingDeg, double range, boolean timed) {
        addTestSource(player, threat, bearingDeg, range, timed, false);
    }

    public static void addTestSource(ServerPlayer player, RwrThreat threat,
                                     double bearingDeg, double range, boolean timed,
                                     boolean moving) {
        String tag = threat == RwrThreat.MISSILE ? "" : "T";
        TEST_SOURCES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
                .add(new TestSource(nextTestId--, threat, bearingDeg, range, tag,
                        player.getServer().getTickCount(), timed, moving));
    }

    /** 动态 MSL 测试：方位和距离随时间变化。 */
    public static void setSweepTestSource(ServerPlayer player) {
        TEST_SOURCES.remove(player.getUUID());
        addTestSource(player, RwrThreat.MISSILE, 32.0, 180.0, false, true);
    }

    public static void clearTestSource(ServerPlayer player) {
        TEST_SOURCES.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TEST_SOURCES.remove(player.getUUID());
            LAST_PAINT.remove(player.getUUID());
            PAINTS.remove(player.getUUID());
            for (Map<UUID, Paint> paints : PAINTS.values()) {
                paints.remove(player.getUUID());
            }
        }
    }

    /** 客户端 SRC PD 扫描线命中玩家时上报。 */
    public static void recordPaint(ServerPlayer source, int targetId) {
        ServerRadarState.LockState state = ServerRadarState.get(source);
        if (state == null || !state.radarOn()
                || state.radarMode() != RadarMode.SRC_PD.ordinal()) {
            return;
        }
        Entity entity = source.serverLevel().getEntity(targetId);
        if (!(entity instanceof ServerPlayer target) || target == source
                || target.level() != source.level()) {
            return;
        }
        Vec3 eye = source.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        if (eye.distanceTo(targetPos) > RANGE || isVerticalBlind(eye, targetPos)) return;
        Vec3 look = source.getViewVector(1.0F).normalize();
        double az = Math.toDegrees(RadarCore.azimuthBetween(look, targetPos.subtract(eye)));
        double el = Math.toDegrees(RadarCore.elevationBetween(look, targetPos.subtract(eye)));
        if (Math.abs(az) > 120.0 || Math.abs(el) > 60.0) return;
        long tick = source.getServer().getTickCount();
        long lastPaint = LAST_PAINT.getOrDefault(source.getUUID(), -100L);
        if (tick - lastPaint < 10) return;
        boolean seen = RadarCore.scan(source.level(), eye, look, RadarMode.SRC_PD,
                RANGE, Math.toRadians(120.0), Math.toRadians(60.0),
                source.getDeltaMovement(), source).stream()
                .anyMatch(c -> c.entity.getId() == target.getId());
        if (!seen) return;
        LAST_PAINT.put(source.getUUID(), tick);
        String tag = firstLetter(source);
        PAINTS.computeIfAbsent(target.getUUID(), k -> new ConcurrentHashMap<>())
                .put(source.getUUID(), new Paint(source.getId(), tag, tick));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % UPDATE_TICKS != 0) return;
        long now = server.getTickCount();
        purgeExpired(now);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            List<RwrContact> contacts = collect(viewer, now);
            Network.sendToPlayer(new RwrSyncPacket(contacts), viewer);
        }
    }

    private static void purgeExpired(long now) {
        for (Map<UUID, Paint> paints : PAINTS.values()) {
            paints.entrySet().removeIf(e -> now - e.getValue().tick() >= SEARCH_TTL_TICKS);
        }
        PAINTS.entrySet().removeIf(e -> e.getValue().isEmpty());
        for (List<TestSource> sources : TEST_SOURCES.values()) {
            sources.removeIf(s -> s.timed() && now - s.createdAt() >= SEARCH_TTL_TICKS);
        }
        TEST_SOURCES.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static List<RwrContact> collect(ServerPlayer viewer, long now) {
        List<RwrContact> contacts = new ArrayList<>();
        Set<Integer> trackedSources = new HashSet<>();
        Vec3 eye = viewer.getEyePosition();
        Vec3 look = viewer.getViewVector(1.0F).normalize();

        for (ServerPlayer source : viewer.server.getPlayerList().getPlayers()) {
            if (source == viewer || source.level() != viewer.level()) continue;
            ServerRadarState.LockState state = ServerRadarState.get(source);
            if (state == null || !state.radarOn()) continue;
            Vec3 sourcePos = source.getEyePosition();
            double distance = sourcePos.distanceTo(eye);
            if (distance < 0.5 || distance > RANGE || isVerticalBlind(eye, sourcePos)) continue;

            RwrThreat threat = null;
            if (state.targetId() == viewer.getId()) {
                if (state.hardLock()) threat = RwrThreat.LOCK;
                else if (state.radarMode() == RadarMode.TWS.ordinal()
                        || state.radarMode() == RadarMode.STT.ordinal()) {
                    threat = RwrThreat.TRACK;
                }
            } else if (viewer.getUUID().equals(state.bvrTargetUuid())) {
                // BVR 远程锁定在对方 RWR 上同样表现为跟踪威胁。
                threat = RwrThreat.TRACK;
            }
            if (threat != null) {
                contacts.add(contact(source.getId(), threat, eye, look,
                        sourcePos.subtract(eye), distance, firstLetter(source), 0));
                trackedSources.add(source.getId());
            }
        }

        Map<UUID, Paint> paints = PAINTS.get(viewer.getUUID());
        if (paints != null) {
            for (Map.Entry<UUID, Paint> entry : paints.entrySet()) {
                Paint paint = entry.getValue();
                int age = (int) Math.max(0, now - paint.tick());
                if (age >= SEARCH_TTL_TICKS || trackedSources.contains(paint.sourceId())) continue;
                ServerPlayer source = viewer.server.getPlayerList().getPlayer(entry.getKey());
                if (source == null || source.level() != viewer.level()
                        || !ServerRadarState.isRadarOn(source)) continue;
                Vec3 sourcePos = source.getEyePosition();
                double distance = sourcePos.distanceTo(eye);
                if (distance < 0.5 || distance > RANGE || isVerticalBlind(eye, sourcePos)) continue;
                contacts.add(contact(paint.sourceId(), RwrThreat.SEARCH, eye, look,
                        sourcePos.subtract(eye), distance, paint.tag(), age));
            }
        }

        AABB area = new AABB(eye, eye).inflate(RANGE);
        for (MissileArrow missile : viewer.serverLevel().getEntitiesOfClass(MissileArrow.class, area)) {
            if (missile.getSpec().guidance == MissileSpec.Guidance.IR) continue;
            Entity owner = missile.getOwner();
            if (owner == viewer || !missile.isTargeting(viewer)) continue;
            Vec3 missilePos = missile.getBoundingBox().getCenter();
            double distance = missilePos.distanceTo(eye);
            if (distance < 0.5 || distance > RANGE || isVerticalBlind(eye, missilePos)) continue;
            contacts.add(contact(missile.getId(), RwrThreat.MISSILE, eye, look,
                    missilePos.subtract(eye), distance, "", 0));
        }

        List<TestSource> tests = TEST_SOURCES.get(viewer.getUUID());
        if (tests != null) {
            for (TestSource test : List.copyOf(tests)) {
                int age = (int) Math.max(0, now - test.createdAt());
                if (test.timed() && age >= SEARCH_TTL_TICKS) continue;
                double bearing = test.bearingDeg();
                double range = test.range();
                if (test.moving()) {
                    double seconds = now / 20.0;
                    bearing += Math.sin(seconds * 1.15) * 70.0;
                    range += Math.cos(seconds * 0.72) * 120.0;
                    range = Math.max(40.0, Math.min(RANGE - 20.0, range));
                }
                if (test.threat() == RwrThreat.SEARCH) {
                    contacts.add(new RwrContact(test.sourceId(), RwrThreat.SEARCH,
                            bearing, 0.0, range, test.tag(), age));
                } else {
                    contacts.add(new RwrContact(test.sourceId(), test.threat(),
                            bearing, 0.0, range, test.tag(), 0));
                }
            }
        }

        contacts.sort(Comparator
                .comparingInt((RwrContact c) -> -c.threat().ordinal())
                .thenComparingDouble(RwrContact::range));
        if (contacts.size() > MAX_CONTACTS) {
            contacts = new ArrayList<>(contacts.subList(0, MAX_CONTACTS));
        }
        return contacts;
    }

    private static RwrContact contact(int sourceId, RwrThreat threat, Vec3 eye,
                                      Vec3 look, Vec3 to, double distance,
                                      String tag, int age) {
        Vec3 dir = to.normalize();
        return new RwrContact(sourceId, threat,
                Math.toDegrees(RadarCore.azimuthBetween(look, dir)),
                Math.toDegrees(RadarCore.elevationBetween(look, dir)),
                distance, tag == null ? "" : tag, age);
    }

    /** 世界坐标仰角超过垂直盲区半角：天顶/天底各 30°。 */
    private static boolean isVerticalBlind(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double elevation = Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 0.001)));
        return Math.abs(elevation) > VERTICAL_BLIND_DEG;
    }

    private static String firstLetter(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        if (name == null || name.isEmpty()) return "?";
        int codePoint = name.codePointAt(0);
        return new String(Character.toChars(codePoint)).toUpperCase(Locale.ROOT);
    }
}
