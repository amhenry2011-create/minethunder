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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = MineThunder.MODID)
public final class SensorDebugCommand {
    private SensorDebugCommand() {}

    /** 测试弹虚拟敌机 UUID：不是玩家，仅用于 RWR 方向/威胁判定。 */
    private static final java.util.UUID BVR_TEST_OWNER =
            java.util.UUID.fromString("00000000-0000-0000-0000-00000000b7e5");

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
                                        .executes(context -> bvrStatus(context.getSource())))
                                .then(Commands.literal("incoming")
                                        .executes(context -> spawnBvrIncoming(context.getSource(),
                                                MissileSpec.Guidance.ARH, null))
                                        .then(Commands.literal("arh")
                                                .executes(context -> spawnBvrIncoming(context.getSource(),
                                                        MissileSpec.Guidance.ARH, null))
                                                .then(Commands.argument("target", EntityArgument.player())
                                                        .executes(context -> spawnBvrIncoming(
                                                                context.getSource(),
                                                                MissileSpec.Guidance.ARH,
                                                                EntityArgument.getPlayer(context, "target")))))
                                        .then(Commands.literal("sarh")
                                                .executes(context -> spawnBvrIncoming(context.getSource(),
                                                        MissileSpec.Guidance.SARH, null))
                                                .then(Commands.argument("target", EntityArgument.player())
                                                        .executes(context -> spawnBvrIncoming(
                                                                context.getSource(),
                                                                MissileSpec.Guidance.SARH,
                                                                EntityArgument.getPlayer(context, "target"))))))
                                .then(Commands.literal("strike")
                                        .executes(context -> spawnBvrStrike(context.getSource(), null))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(context -> spawnBvrStrike(context.getSource(),
                                                        EntityArgument.getPlayer(context, "target"))))))
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

    /**
     * 800 格外来袭测试：默认目标是执行命令的玩家，沿目标视线前方生成，
     * 测试弹无伤害；ARH/SARH 都使用 testGuided 绕过载机数据链和照射。
     */
    private static int spawnBvrIncoming(CommandSourceStack source,
                                        MissileSpec.Guidance guidance,
                                        @Nullable ServerPlayer targetArg) {
        if (!(source.getEntity() instanceof ServerPlayer shooter)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "只有玩家可以执行 BVR 来袭测试"));
            return 0;
        }
        ServerPlayer target = targetArg == null ? shooter : targetArg;
        MissileSpec spec = guidance == MissileSpec.Guidance.SARH
                ? MissileSpecs.SARH : MissileSpecs.ARH;
        Vec3 spawn = bvrSpawnPoint(target, 800.0);
        Vec3 motion = target.getEyePosition().subtract(spawn).normalize().scale(1.0);
        MissileArrow missile = createMissile(target.serverLevel(), spec,
                spawn, motion, target);
        if (missile == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("无法生成 BVR 测试弹"));
            return 0;
        }
        missile.setVirtualOwner(BVR_TEST_OWNER);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "已生成 800 格外无伤害 " + spec.name + " 来袭测试，目标："
                            + target.getName().getString()
                            + "（雷达切到 1000 b，Alt+Q）"), false);
        return 1;
    }

    /** 800 格实弹 ARH 打击测试；默认打自己，生成时带区块加载并保持制导。 */
    private static int spawnBvrStrike(CommandSourceStack source,
                                      @Nullable ServerPlayer targetArg) {
        if (!(source.getEntity() instanceof ServerPlayer shooter)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "只有玩家可以执行 BVR 实弹测试"));
            return 0;
        }
        ServerPlayer target = targetArg == null ? shooter : targetArg;
        Vec3 spawn = bvrSpawnPoint(target, 800.0);
        Vec3 motion = target.getEyePosition().subtract(spawn).normalize().scale(1.0);
        MissileArrow missile = createMissile(target.serverLevel(), MissileSpecs.ARH,
                spawn, motion, target);
        if (missile == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("无法生成 BVR 实弹"));
            return 0;
        }
        missile.setTestMissile(false);
        missile.setTestGuided(true);
        missile.setVirtualOwner(BVR_TEST_OWNER);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "已发射 800 格实弹 ARH，目标：" + target.getName().getString()
                            + "（直接命中 25，6 b 内溅射 8；雷达切到 1000 b，Alt+Q）"), false);
        return 1;
    }

    /** 在目标视线水平前方 distance 格、地形上方 60~80 b 取点并加载区块。 */
    private static Vec3 bvrSpawnPoint(ServerPlayer target, double distance) {
        ServerLevel level = target.serverLevel();
        Vec3 eye = target.getEyePosition();
        Vec3 look = target.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0E-6) flat = new Vec3(0, 0, 1);
        flat = flat.normalize();
        Vec3 horizontal = eye.add(flat.scale(distance));
        BlockPos column = BlockPos.containing(horizontal);
        level.getChunkAt(column);
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                column.getX(), column.getZ());
        double y = Math.max(eye.y + 60.0, surface + 80.0);
        return new Vec3(horizontal.x, y, horizontal.z);
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
            if (entity.getTags().contains(com.minethunder.radar.BvrTicketService.BVR_TAG)
                    || entity instanceof MissileArrow missile
                    && (missile.isTestBvrTrack() || missile.isTestGuided())) {
                entity.discard();
                removed++;
            }
        }
        final int count = removed;
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "已清除 " + count + " 个 BVR 测试目标/测试弹"), false);
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
        missile.setTestBvrTrack(true);
        if (target != null) {
            missile.setTestGuided(true);
            missile.setTarget(target);
        }
        level.addFreshEntity(missile);
        missile.setDeltaMovement(motion);
        com.minethunder.radar.BvrTicketService.trackNow(missile);
        return missile;
    }
}
