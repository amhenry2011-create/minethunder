package com.minethunder.command;

import com.minethunder.MineThunder;
import com.minethunder.missile.ArhMissileArrow;
import com.minethunder.missile.IrMissileArrow;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.missile.SarhMissileArrow;
import com.minethunder.network.Network;
import com.minethunder.network.SensorDebugPacket;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.RwrManager;
import com.minethunder.radar.RwrThreat;
import com.minethunder.sensor.SensorDebugState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = MineThunder.MODID)
public final class SensorDebugCommand {
    private SensorDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("minethunder")
                .then(Commands.literal("sensor")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> toggle(context.getSource())))
                .then(Commands.literal("testtarget")
                        .executes(context -> spawnTestTarget(context.getSource()))
                        .then(Commands.literal("missile")
                                .executes(context -> spawnTargetMissile(context.getSource())))
                        .then(Commands.literal("clear")
                                .executes(context -> clearTestTargets(context.getSource()))))
                .then(Commands.literal("test")
                        .then(Commands.literal("plane")
                                .executes(context -> spawnTestPlane(context.getSource(), false))
                                .then(Commands.literal("afterburner")
                                        .executes(context -> spawnTestPlane(context.getSource(), true)))
                                .then(Commands.literal("clear")
                                        .executes(context -> clearTestPlanes(context.getSource()))))
                        .then(Commands.literal("intercept")
                                .executes(context -> spawnInterceptTest(context.getSource())))
                        .then(Commands.literal("incoming")
                                .then(Commands.literal("arh")
                                        .executes(context -> spawnIncoming(context.getSource(),
                                                MissileSpec.Guidance.ARH)))
                                .then(Commands.literal("sarh")
                                        .executes(context -> spawnIncoming(context.getSource(),
                                                MissileSpec.Guidance.SARH)))
                                .then(Commands.literal("ir")
                                        .executes(context -> spawnIncoming(context.getSource(),
                                                MissileSpec.Guidance.IR))))
                        .then(Commands.literal("full")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> FullFeatureTest.start(context.getSource()))
                                .then(Commands.literal("start")
                                        .executes(context -> FullFeatureTest.start(context.getSource())))
                                .then(Commands.literal("next")
                                        .executes(context -> FullFeatureTest.next(context.getSource())))
                                .then(Commands.literal("stop")
                                        .executes(context -> FullFeatureTest.stop(context.getSource())))
                                .then(Commands.literal("status")
                                        .executes(context -> FullFeatureTest.status(context.getSource()))))
                        .then(Commands.literal("targets")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> FullFeatureTest.targets(context.getSource())))
                        .then(Commands.literal("bvr")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> spawnBvrTarget(context.getSource()))
                                .then(Commands.literal("start")
                                        .executes(context -> spawnBvrTarget(context.getSource())))
                                .then(Commands.literal("clear")
                                        .executes(context -> clearBvrTargets(context.getSource())))
                                .then(Commands.literal("status")
                                        .executes(context -> bvrStatus(context.getSource()))))
                        .then(Commands.literal("dodge")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> FullFeatureTest.dodge(context.getSource(), null))
                                .then(Commands.literal("arh")
                                        .executes(context -> FullFeatureTest.dodge(context.getSource(),
                                                MissileSpec.Guidance.ARH)))
                                .then(Commands.literal("sarh")
                                        .executes(context -> FullFeatureTest.dodge(context.getSource(),
                                                MissileSpec.Guidance.SARH)))
                                .then(Commands.literal("ir")
                                        .executes(context -> FullFeatureTest.dodge(context.getSource(),
                                                MissileSpec.Guidance.IR))))
                        .then(Commands.literal("cm")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> FullFeatureTest.countermeasures(context.getSource(), null))
                                .then(Commands.literal("chaff")
                                        .executes(context -> FullFeatureTest.countermeasures(context.getSource(),
                                                com.minethunder.countermeasure.Countermeasure.CHAFF)))
                                .then(Commands.literal("flare")
                                        .executes(context -> FullFeatureTest.countermeasures(context.getSource(),
                                                com.minethunder.countermeasure.Countermeasure.FLARE)))
                                .then(Commands.literal("all")
                                        .executes(context -> FullFeatureTest.countermeasures(context.getSource(), null))))
                        .then(Commands.literal("kit")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> FullFeatureTest.kit(context.getSource())))
                        .then(Commands.literal("rwr")
                                .then(Commands.literal("search")
                                        .executes(context -> setRwrTest(context.getSource(), RwrThreat.SEARCH)))
                                .then(Commands.literal("track")
                                        .executes(context -> setRwrTest(context.getSource(), RwrThreat.TRACK)))
                                .then(Commands.literal("lock")
                                        .executes(context -> setRwrTest(context.getSource(), RwrThreat.LOCK)))
                                .then(Commands.literal("missile")
                                        .executes(context -> setRwrTest(context.getSource(), RwrThreat.MISSILE)))
                                .then(Commands.literal("missile2")
                                        .executes(context -> setTwoMissileRwrTest(context.getSource())))
                                .then(Commands.literal("sweep")
                                        .executes(context -> setSweepRwrTest(context.getSource())))
                                .then(Commands.literal("all")
                                        .executes(context -> FullFeatureTest.rwrAll(context.getSource())))
                                .then(Commands.literal("launch")
                                        .executes(context -> setRwrTest(context.getSource(), RwrThreat.MISSILE)))
                                .then(Commands.literal("blind")
                                        .executes(context -> clearRwrTest(context.getSource())))
                                .then(Commands.literal("off")
                                        .executes(context -> clearRwrTest(context.getSource()))))));
    }

    private static int toggle(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("只有玩家可以开启传感器视觉"));
            return 0;
        }
        boolean enabled = SensorDebugState.toggle(player);
        Network.sendToPlayer(new SensorDebugPacket(enabled), player);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "传感器视觉已" + (enabled ? "开启" : "关闭")), false);
        return 1;
    }

    /** 在玩家前方生成一只必定进入雷达扫描范围的幻翼。 */
    private static int spawnTestTarget(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("只有玩家可以生成测试目标"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 eye = player.getEyePosition();
        Vec3 pos = eye.add(look.scale(8.0));
        for (double forward : new double[]{8.0, 10.0, 12.0, 6.0, 4.0}) {
            for (double up : new double[]{0.0, 1.0, 2.0, -1.0}) {
                Vec3 candidate = eye.add(look.scale(forward)).add(0, up, 0);
                if (!RadarCore.isOccluded(level, eye, candidate)) {
                    pos = candidate;
                    break;
                }
            }
        }
        Phantom phantom = EntityType.PHANTOM.create(level);
        if (phantom == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("无法生成幻翼"));
            return 0;
        }
        phantom.moveTo(pos.x, pos.y, pos.z, player.getYRot(), 0);
        phantom.setNoAi(true);
        phantom.setNoGravity(true);
        phantom.setDeltaMovement(look.scale(10.0));
        phantom.setSilent(true);
        phantom.setGlowingTag(true);
        phantom.setCustomName(net.minecraft.network.chat.Component.literal("RADAR TEST"));
        phantom.setCustomNameVisible(true);
        level.addFreshEntity(phantom);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已在正前方生成雷达测试幻翼（RADAR TEST）"), false);
        return 1;
    }

    /** 生成一枚不制导、不伤害的测试导弹，供雷达/ARH 目标测试。 */
    private static int spawnTargetMissile(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        Vec3 look = player.getLookAngle().normalize();
        Vec3 pos = player.getEyePosition().add(look.scale(40.0));
        MissileArrow missile = createMissile(player.serverLevel(), MissileSpecs.ARH, pos,
                look.scale(0.25), null);
        if (missile == null) return 0;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已生成测试导弹（RADAR TEST MSL）"), false);
        return 1;
    }

    private static int clearTestTargets(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        int removed = 0;
        for (MissileArrow missile : player.serverLevel().getEntitiesOfClass(
                MissileArrow.class, player.getBoundingBox().inflate(300.0))) {
            if (missile.isTestMissile()) {
                missile.discard();
                removed++;
            }
        }
        int count = removed;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已清除 " + count + " 枚测试导弹"), false);
        return 1;
    }

    /** 在玩家前方生成一架开机巡航的测试固定翼靶机，供雷达/TWS/ACM/BVR/红外测试。 */
    private static int spawnTestPlane(CommandSourceStack source, boolean afterburner) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("只有玩家可以生成测试靶机"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 pos = player.getEyePosition().add(look.scale(80.0)).add(0, 12.0, 0);
        com.minethunder.plane.PlaneEntity plane = new com.minethunder.plane.PlaneEntity(
                com.minethunder.Registration.LARGE_PLANE.get(), level,
                com.minethunder.plane.PlaneTypeSpec.LARGE_PLANE);
        plane.moveTo(pos.x, pos.y, pos.z, player.getYRot() + 90.0f, 0.0f);
        plane.configureRunningForTest(
                com.minethunder.plane.PlaneEngineType.JET,
                afterburner ? 1.10f : 0.78f,
                afterburner);
        Vec3 heading = Vec3.directionFromRotation(0.0f, player.getYRot() + 90.0f).normalize();
        plane.setDeltaMovement(heading.scale(afterburner ? 1.45 : 0.95));
        plane.setCustomName(net.minecraft.network.chat.Component.literal(
                afterburner ? "BOGEY-JET-AB" : "BOGEY-JET"));
        plane.setCustomNameVisible(true);
        plane.addTag(com.minethunder.radar.BvrTicketService.BVR_TAG);
        plane.addTag("minethunder_test_plane");
        level.addFreshEntity(plane);
        com.minethunder.radar.BvrTicketService.registerManualTarget(plane);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已在前方 80 格生成喷气靶机 " + (afterburner ? "BOGEY-JET-AB (开启加力 WEP)" : "BOGEY-JET (巡航推力)")), false);
        return 1;
    }

    private static int clearTestPlanes(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        int removed = 0;
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (entity instanceof com.minethunder.plane.PlaneEntity
                    && (entity.getTags().contains("minethunder_test_plane")
                    || entity.getPassengers().isEmpty())) {
                entity.discard();
                removed++;
            }
        }
        final int count = removed;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已清除 " + count + " 架无人飞机/测试靶机"), false);
        return 1;
    }

    /** 两枚无伤害测试弹相向飞行，验证导弹拦截/近炸。 */
    private static int spawnInterceptTest(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 center = player.getEyePosition().add(look.scale(40.0)).add(0, 3, 0);
        MissileArrow a = createMissile(level, MissileSpecs.ARH,
                center.add(right.scale(-4.0)), right.scale(1.6), null);
        MissileArrow b = createMissile(level, MissileSpecs.ARH,
                center.add(right.scale(4.0)), right.scale(-1.6), null);
        if (a == null || b == null) return 0;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已生成两枚相向测试弹；接近后应互相引爆"), false);
        return 1;
    }

    /** 生成一枚瞄准玩家的无伤害测试弹，用于 RWR 与拦截测试。 */
    private static int spawnIncoming(CommandSourceStack source, MissileSpec.Guidance guidance) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        MissileSpec spec = switch (guidance) {
            case ARH -> MissileSpecs.ARH;
            case SARH -> MissileSpecs.SARH;
            case IR -> MissileSpecs.IR;
        };
        Vec3 look = player.getLookAngle().normalize();
        Vec3 pos = player.getEyePosition().add(look.scale(140.0)).add(0, 12, 0);
        MissileArrow missile = createMissile(player.serverLevel(), spec, pos,
                player.getEyePosition().subtract(pos).normalize().scale(1.0), player);
        if (missile == null) return 0;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已生成无伤害来袭测试弹：" + spec.name), false);
        return 1;
    }

    /** BVR 测试：在玩家前方 ~600 格生成带区块保活的 AI 目标（缓慢接近）。 */
    private static int spawnBvrTarget(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("只有玩家可以生成 BVR 测试目标"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 eye = player.getEyePosition();
        Vec3 pos = null;
        double spawned = 600.0;
        for (double d = 600.0; d >= 24.0; d -= 8.0) {
            Vec3 candidate = eye.add(look.scale(d))
                    .add(0, Math.min(8.0, (600.0 - d) * 0.06), 0);
            var chunk = level.getChunkSource();
            int cx = net.minecraft.core.BlockPos.containing(candidate).getX() >> 4;
            int cz = net.minecraft.core.BlockPos.containing(candidate).getZ() >> 4;
            if (chunk.hasChunk(cx, cz)) {
                pos = candidate;
                spawned = d;
                break;
            }
        }
        if (pos == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "视线方向已加载区块内找不到 24..600 格位置，请先飞高/开远视距或朝已加载区块看"));
            return 0;
        }
        final double distance = spawned;
        final Vec3 spawnAt = pos;
        Phantom phantom = EntityType.PHANTOM.create(level);
        if (phantom == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("无法生成幻翼"));
            return 0;
        }
        phantom.moveTo(spawnAt.x, spawnAt.y, spawnAt.z, player.getYRot(), 0);
        phantom.setNoAi(true);
        phantom.setNoGravity(true);
        phantom.setInvulnerable(true);
        phantom.setPersistenceRequired();
        phantom.setDeltaMovement(eye.subtract(spawnAt).normalize().scale(0.35));
        phantom.setSilent(true);
        phantom.setCustomName(net.minecraft.network.chat.Component.literal("BVR-TEST-7"));
        phantom.setCustomNameVisible(true);
        phantom.addTag(com.minethunder.radar.BvrTicketService.BVR_TAG);
        level.addFreshEntity(phantom);
        com.minethunder.radar.BvrTicketService.registerManualTarget(phantom);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已生成 BVR 测试目标 BVR-TEST-7（" + (int) distance + " 格，区块保活已开启）"), false);
        return 1;
    }

    private static int bvrStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        String status = com.minethunder.radar.BvrTrackManager.status(player.server, player);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(status), false);
        return 1;
    }

    private static int clearBvrTargets(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        int removed = 0;
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (entity.getTags().contains(com.minethunder.radar.BvrTicketService.BVR_TAG)) {
                entity.discard();
                removed++;
            }
        }
        final int count = removed;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已清除 " + count + " 个 BVR 测试目标"), false);
        return 1;
    }

    private static int setRwrTest(CommandSourceStack source, RwrThreat threat) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        RwrManager.setTestSource(player, threat);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "RWR 测试源：" + threat.name()), false);
        return 1;
    }

    private static int setSweepRwrTest(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        RwrManager.setSweepTestSource(player);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "RWR 测试源：动态 MSL"), false);
        return 1;
    }

    private static int setTwoMissileRwrTest(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        RwrManager.setTestSource(player, RwrThreat.MISSILE);
        RwrManager.addTestSource(player, RwrThreat.MISSILE, 75.0, 320.0, false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "RWR 测试源：两枚雷达弹"), false);
        return 1;
    }

    private static int clearRwrTest(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        RwrManager.clearTestSource(player);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "RWR 测试源已关闭/导弹已进入盲区"), false);
        return 1;
    }

    @Nullable
    static MissileArrow createMissile(ServerLevel level, MissileSpec spec, Vec3 pos,
                                              Vec3 motion, @Nullable net.minecraft.world.entity.Entity target) {
        MissileArrow missile;
        if (spec.guidance == MissileSpec.Guidance.SARH) {
            missile = new SarhMissileArrow(com.minethunder.Registration.SARH_MISSILE.get(), level, spec);
        } else if (spec.guidance == MissileSpec.Guidance.IR) {
            missile = new IrMissileArrow(com.minethunder.Registration.IR_MISSILE.get(), level, spec);
        } else {
            missile = new ArhMissileArrow(com.minethunder.Registration.ARH_MISSILE.get(), level, spec);
        }
        missile.setPos(pos.x, pos.y, pos.z);
        missile.setTestMissile(true);
        if (missile instanceof SarhMissileArrow sarh && target != null) {
            sarh.setTestGuided(true);
        }
        if (target != null) missile.setTarget(target);
        level.addFreshEntity(missile);
        missile.setDeltaMovement(motion);
        return missile;
    }
}
