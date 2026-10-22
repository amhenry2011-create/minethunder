package com.minethunder.command;

import com.minethunder.MineThunder;
import com.minethunder.Registration;
import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import com.minethunder.missile.MissileArrow;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.network.Network;
import com.minethunder.network.SensorDebugPacket;
import com.minethunder.radar.RadarBlockTargets;
import com.minethunder.radar.RwrManager;
import com.minethunder.radar.RwrThreat;
import com.minethunder.sensor.SensorDebugState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 分阶段全功能测试：负责布置场景、给出预期结果和清理测试资产。 */
@EventBusSubscriber(modid = MineThunder.MODID)
public final class FullFeatureTest {
    private FullFeatureTest() {}

    private static final int STAGES = 8;
    private static final Map<UUID, Integer> CURRENT_STAGE = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, BlockState>> BLOCK_SNAPSHOTS =
            new ConcurrentHashMap<>();

    private static final String[] TITLES = {
            "传感器平滑",
            "全目标与扫描",
            "TWS 与锁定",
            "ACM",
            "RWR 全功能",
            "躲弹",
            "干扰效果",
            "按键与 HUD"
    };

    private static final String[][] DETAILS = {
            {
                    "操作：保持雷达开启，前后走、横移、转视角、第三人称，观察 3D 和 2D 棱锥。",
                    "预期：棱锥平滑跟随机头，不游泳、不抽搐；扫描换行连续。",
                    "失败：移动时端点滞后/跳动，或换行出现大跨度抽搐。"
            },
            {
                    "已生成：空中幻翼、地面猪、水面船、静态方块标记、测试导弹、着火幻翼。",
                    "操作：切换 SRC PD/GMI/SEA/TWS/ACM，Alt+Q/Alt+D 调距离和宽度，尝试锁定。",
                    "预期：只有波束扫过才出现/刷新；地面/海面/静态目标只在对应模式可见；静态目标只在 SRC PD。"
            },
            {
                    "操作：Alt+B 切 TWS；等待航迹建立；Alt+T 切换当前目标；Alt+F 锁当前 TWS。",
                    "预期：航迹按扫描帧逐步出现，未命中显示 INER；不会每 tick 全扇区刷新；最多 6 条。",
                    "失败：所有目标同一 tick 全部出现，或航迹不刷新/不衰减。"
            },
            {
                    "操作：目标在机头前约 40 b；Alt+W 进入 ACM，观察自动截获；Alt+W 退出。",
                    "预期：自动截获并切 TRK PD；解锁后约 2 秒冷却；ACM 扫描位置固定。",
                    "失败：不自动截获、解锁后瞬间复锁，或扫描中心可移动。"
            },
            {
                    "已设置 SEARCH/TRACK/LOCK/MISSILE/SWEEP 测试源。",
                    "操作：观察 RWR 图标、方位、距离；再逐项运行 test rwr search/track/lock/missile/missile2/sweep/blind/off。",
                    "预期：搜索只淡出、跟踪/锁定常亮、导弹显示 MSL；blind/off 清空。"
            },
            {
                    "已生成 ARH/SARH/IR 无伤害来袭弹。",
                    "操作：机动、俯冲、地形遮挡、Notch，观察 RWR、导弹轨迹、失锁和近炸。",
                    "预期：测试弹不造成伤害；制导、失锁、近炸按规则工作。"
            },
            {
                    "已生成雷达/红外来袭弹和箔条/热焰假目标。",
                    "操作：观察导弹是否转向假目标；另可用 test cm chaff/flare/all 补充。",
                    "预期：箔条欺骗雷达弹，热焰弹欺骗红外弹；假目标约 5 秒消失。"
            },
            {
                    "操作：方向键偏移扫描中心后按右 Shift；H 帮助；Alt+E 干扰；Ctrl+Alt 发射；Alt+X 导引头。",
                    "预期：右 Shift 瞬间回中；HUD/帮助显示新键位；干扰和发射正常。"
            }
    };

    public static int start(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        clearAssets(player);
        CURRENT_STAGE.put(player.getUUID(), 0);
        enableSensor(player);
        setupStage(source, player, 0);
        return 1;
    }

    public static int next(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        int stage = CURRENT_STAGE.getOrDefault(player.getUUID(), -1);
        if (stage < 0) {
            source.sendFailure(Component.literal("先运行 /minethunder test full start"));
            return 0;
        }
        if (stage >= STAGES - 1) {
            return stop(source);
        }
        clearAssets(player);
        int next = stage + 1;
        CURRENT_STAGE.put(player.getUUID(), next);
        setupStage(source, player, next);
        return 1;
    }

    public static int stop(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        clearAssets(player);
        CURRENT_STAGE.remove(player.getUUID());
        source.sendSuccess(() -> Component.literal("全功能测试已结束，测试实体和临时方块已清理。"), false);
        return 1;
    }

    public static int status(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        Integer stage = CURRENT_STAGE.get(player.getUUID());
        if (stage == null) {
            source.sendSuccess(() -> Component.literal("当前没有运行中的全功能测试。"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "当前阶段 " + (stage + 1) + "/" + STAGES + "：" + TITLES[stage]), false);
        }
        return 1;
    }

    public static int targets(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        clearAssets(player);
        int count = spawnAllTargets(player);
        source.sendSuccess(() -> Component.literal(
                "已生成 " + count + " 个可锁定/热源测试目标。"), false);
        sendDetails(source, 1);
        return 1;
    }

    public static int dodge(CommandSourceStack source, @Nullable MissileSpec.Guidance guidance) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        clearAssets(player);
        if (guidance == null) {
            spawnIncoming(player, MissileSpec.Guidance.ARH, -24.0);
            spawnIncoming(player, MissileSpec.Guidance.SARH, 0.0);
            spawnIncoming(player, MissileSpec.Guidance.IR, 24.0);
        } else {
            spawnIncoming(player, guidance, 0.0);
        }
        sendDetails(source, 5);
        return 1;
    }

    public static int rwrAll(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        setRwrAll(player);
        sendDetails(source, 4);
        return 1;
    }

    public static int countermeasures(CommandSourceStack source,
                                      @Nullable Countermeasure kind) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        if (kind == null) {
            spawnCountermeasures(player, Countermeasure.CHAFF, 3);
            spawnCountermeasures(player, Countermeasure.FLARE, 3);
            source.sendSuccess(() -> Component.literal("已释放 3 箔条 / 3 热焰弹。"), false);
        } else {
            spawnCountermeasures(player, kind, 3);
            source.sendSuccess(() -> Component.literal(
                    "已释放 3 个 " + kind.name() + "。"), false);
        }
        return 1;
    }

    public static int kit(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        giveKit(player);
        source.sendSuccess(() -> Component.literal(
                "测试包已发放：弓、三种导弹烟花、雪球、火焰弹。"), false);
        return 1;
    }

    private static void setupStage(CommandSourceStack source, ServerPlayer player, int stage) {
        switch (stage) {
            case 0 -> {
                enableSensor(player);
                source.sendSuccess(() -> Component.literal("传感器视觉已开启。"), false);
            }
            case 1 -> {
                int count = spawnAllTargets(player);
                source.sendSuccess(() -> Component.literal(
                        "已生成 " + count + " 个目标。"), false);
            }
            case 2 -> spawnTwsTargets(player);
            case 3 -> spawnPhantom(player, "TEST-ACM", 40.0, 0.0, 2.0, false);
            case 4 -> {
                setRwrAll(player);
                source.sendSuccess(() -> Component.literal("RWR 测试源已设置。"), false);
            }
            case 5 -> {
                spawnIncoming(player, MissileSpec.Guidance.ARH, -24.0);
                spawnIncoming(player, MissileSpec.Guidance.SARH, 0.0);
                spawnIncoming(player, MissileSpec.Guidance.IR, 24.0);
                source.sendSuccess(() -> Component.literal("三枚无伤害来袭弹已生成。"), false);
            }
            case 6 -> {
                spawnIncoming(player, MissileSpec.Guidance.ARH, 0.0);
                spawnIncoming(player, MissileSpec.Guidance.IR, 18.0);
                spawnCountermeasures(player, Countermeasure.CHAFF, 3);
                spawnCountermeasures(player, Countermeasure.FLARE, 3);
                source.sendSuccess(() -> Component.literal("干扰测试场景已生成。"), false);
            }
            case 7 -> {
                giveKit(player);
                source.sendSuccess(() -> Component.literal("测试包已发放。"), false);
            }
            default -> { }
        }
        source.sendSuccess(() -> Component.literal(
                "测试 " + (stage + 1) + "/" + STAGES + "：" + TITLES[stage]), false);
        for (String line : DETAILS[stage]) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        sendActions(source, stage);
    }

    private static void sendDetails(CommandSourceStack source, int stage) {
        for (String line : DETAILS[stage]) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private static void sendActions(CommandSourceStack source, int stage) {
        Component next = Component.literal("[下一阶段]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/minethunder test full next")));
        Component stop = Component.literal("[结束测试]").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/minethunder test full stop")));
        Component line = Component.literal("").append(next).append(Component.literal("  ")).append(stop);
        source.sendSuccess(() -> line, false);
    }

    private static void enableSensor(ServerPlayer player) {
        if (SensorDebugState.isEnabled(player)) return;
        SensorDebugState.toggle(player);
        Network.sendToPlayer(new SensorDebugPacket(true), player);
    }

    private static int spawnAllTargets(ServerPlayer player) {
        int count = 0;
        if (spawnPhantom(player, "TEST-AIR-20", 20.0, 0.0, 2.0, false)) count++;
        if (spawnPhantom(player, "TEST-AIR-60", 60.0, 30.0, 6.0, false)) count++;
        if (spawnPhantom(player, "TEST-IR-HOT", 28.0, -20.0, 8.0, true)) count++;
        if (spawnGroundTarget(player)) count++;
        if (spawnSeaTarget(player)) count++;
        if (spawnStaticMarker(player)) count++;
        if (spawnTargetMissile(player)) count++;
        return count;
    }

    private static void spawnTwsTargets(ServerPlayer player) {
        spawnPhantom(player, "TEST-TWS-1", 30.0, -24.0, 4.0, false);
        spawnPhantom(player, "TEST-TWS-2", 60.0, -8.0, 8.0, false);
        spawnPhantom(player, "TEST-TWS-3", 100.0, 8.0, 12.0, false);
        spawnPhantom(player, "TEST-TWS-4", 150.0, 24.0, 16.0, false);
    }

    private static boolean spawnPhantom(ServerPlayer player, String name, double range,
                                        double lateral, double height, boolean burning) {
        ServerLevel level = player.serverLevel();
        Phantom phantom = EntityType.PHANTOM.create(level);
        if (phantom == null) return false;
        Vec3 forward = horizontalLook(player);
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 pos = player.getEyePosition()
                .add(forward.scale(range))
                .add(right.scale(lateral))
                .add(0, height, 0);
        phantom.moveTo(pos.x, pos.y, pos.z, player.getYRot(), 0);
        phantom.setNoAi(true);
        phantom.setNoGravity(true);
        phantom.setSilent(true);
        phantom.setInvulnerable(true);
        phantom.setGlowingTag(true);
        phantom.setCustomName(Component.literal(name));
        phantom.setCustomNameVisible(true);
        phantom.setDeltaMovement(forward.scale(0.08));
        phantom.addTag(testTag(player));
        if (burning) {
            phantom.setRemainingFireTicks(2000);
        }
        level.addFreshEntity(phantom);
        return true;
    }

    private static boolean spawnGroundTarget(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 forward = horizontalLook(player);
        BlockPos center = BlockPos.containing(player.getEyePosition().add(forward.scale(24.0)));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                center.getX(), center.getZ());
        if (y <= level.getMinBuildHeight() + 1) return false;
        Pig pig = EntityType.PIG.create(level);
        if (pig == null) return false;
        pig.moveTo(center.getX() + 0.5, y, center.getZ() + 0.5, 0, 0);
        pig.setNoAi(true);
        pig.setInvulnerable(true);
        pig.setSilent(true);
        pig.setGlowingTag(true);
        pig.setPersistenceRequired();
        pig.setCustomName(Component.literal("TEST-GROUND"));
        pig.setCustomNameVisible(true);
        pig.setDeltaMovement(0.08, 0, 0);
        pig.addTag(testTag(player));
        level.addFreshEntity(pig);
        return true;
    }

    private static boolean spawnSeaTarget(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos water = findWater(player);
        if (water != null) {
            return spawnBoat(player, water.above());
        }
        BlockPos center = BlockPos.containing(player.getEyePosition()
                .add(horizontalLook(player).scale(24.0))).below(2);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos pos = center.offset(dx, 0, dz);
                setBlockTracked(player, pos.below(), Blocks.STONE.defaultBlockState());
                setBlockTracked(player, pos, Blocks.WATER.defaultBlockState());
                if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                    setBlockTracked(player, pos.above(), Blocks.STONE.defaultBlockState());
                }
            }
        }
        return spawnBoat(player, center.above());
    }

    private static boolean spawnBoat(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        Boat boat = EntityType.BOAT.create(level);
        if (boat == null) return false;
        boat.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        boat.setCustomName(Component.literal("TEST-SEA"));
        boat.setCustomNameVisible(true);
        boat.setGlowingTag(true);
        boat.setDeltaMovement(0.02, 0, 0);
        boat.addTag(testTag(player));
        level.addFreshEntity(boat);
        return true;
    }

    private static boolean spawnStaticMarker(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Marker marker = EntityType.MARKER.create(level);
        if (marker == null) return false;
        Vec3 pos = player.getEyePosition()
                .add(horizontalLook(player).scale(30.0))
                .add(0, 6, 0);
        marker.moveTo(pos.x, pos.y, pos.z, 0, 0);
        marker.setCustomName(Component.literal(RadarBlockTargets.AIR_STATIC));
        marker.setNoGravity(true);
        marker.setInvisible(true);
        marker.addTag(testTag(player));
        level.addFreshEntity(marker);
        return true;
    }

    private static boolean spawnTargetMissile(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 forward = horizontalLook(player);
        Vec3 pos = player.getEyePosition()
                .add(forward.scale(40.0)).add(0, 3, 0);
        // 给测试弹一个缓慢的前向初速，避免静止目标在阻力段悬停。
        MissileArrow missile = SensorDebugCommand.createMissile(
                level, MissileSpecs.ARH, pos, forward.scale(0.3), null);
        if (missile == null) return false;
        missile.addTag(testTag(player));
        return true;
    }

    private static void spawnIncoming(ServerPlayer player, MissileSpec.Guidance guidance,
                                      double lateral) {
        MissileSpec spec = switch (guidance) {
            case ARH -> MissileSpecs.ARH;
            case SARH -> MissileSpecs.SARH;
            case IR -> MissileSpecs.IR;
        };
        ServerLevel level = player.serverLevel();
        Vec3 forward = horizontalLook(player);
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 pos = player.getEyePosition()
                .add(forward.scale(140.0))
                .add(right.scale(lateral))
                .add(0, 12, 0);
        MissileArrow missile = SensorDebugCommand.createMissile(
                level, spec, pos,
                player.getEyePosition().subtract(pos).normalize().scale(1.0), player);
        if (missile != null) {
            missile.addTag(testTag(player));
        }
    }

    private static void spawnCountermeasures(ServerPlayer player, Countermeasure kind, int count) {
        ServerLevel level = player.serverLevel();
        Item item = kind == Countermeasure.FLARE ? Items.FIRE_CHARGE : Items.SNOWBALL;
        for (int i = 0; i < count; i++) {
            CountermeasureEntity entity = new CountermeasureEntity(
                    Registration.COUNTERMEASURE.get(), level);
            entity.setPos(player.getX() + player.getRandom().nextDouble() * 0.8 - 0.4,
                    player.getY() + 0.4,
                    player.getZ() + player.getRandom().nextDouble() * 0.8 - 0.4);
            entity.setOwner(player);
            entity.setItem(new ItemStack(item));
            entity.setDeltaMovement(
                    player.getRandom().nextDouble() * 0.28 - 0.14,
                    0.35 + player.getRandom().nextDouble() * 0.2,
                    player.getRandom().nextDouble() * 0.28 - 0.14);
            entity.addTag(testTag(player));
            level.addFreshEntity(entity);
        }
    }

    private static void setRwrAll(ServerPlayer player) {
        RwrManager.clearTestSource(player);
        RwrManager.addTestSource(player, RwrThreat.SEARCH, 20.0, 80.0, false);
        RwrManager.addTestSource(player, RwrThreat.TRACK, 80.0, 120.0, false);
        RwrManager.addTestSource(player, RwrThreat.LOCK, 140.0, 160.0, false);
        RwrManager.addTestSource(player, RwrThreat.MISSILE, 200.0, 220.0, false);
        RwrManager.addTestSource(player, RwrThreat.MISSILE, 260.0, 300.0, false, true);
    }

    private static void giveKit(ServerPlayer player) {
        give(player, Items.BOW, 1);
        give(player, Registration.IR_MISSILE_FIREWORK.get(), 16);
        give(player, Registration.SARH_MISSILE_FIREWORK.get(), 16);
        give(player, Registration.ARH_MISSILE_FIREWORK.get(), 16);
        give(player, Items.SNOWBALL, 16);
        give(player, Items.FIRE_CHARGE, 16);
    }

    private static void give(ServerPlayer player, Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void clearAssets(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        String tag = testTag(player);
        List<Entity> remove = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getTags().contains(tag)) {
                remove.add(entity);
            }
        }
        for (Entity entity : remove) {
            entity.discard();
        }
        RwrManager.clearTestSource(player);
        restoreBlocks(player);
    }

    private static void restoreBlocks(ServerPlayer player) {
        Map<BlockPos, BlockState> snapshots = BLOCK_SNAPSHOTS.remove(player.getUUID());
        if (snapshots == null) return;
        ServerLevel level = player.serverLevel();
        for (Map.Entry<BlockPos, BlockState> entry : snapshots.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
        }
    }

    private static void setBlockTracked(ServerPlayer player, BlockPos pos, BlockState state) {
        ServerLevel level = player.serverLevel();
        BlockPos key = pos.immutable();
        BLOCK_SNAPSHOTS.computeIfAbsent(player.getUUID(), ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, state, Block.UPDATE_ALL);
    }

    @Nullable
    private static BlockPos findWater(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        for (int dx = -32; dx <= 32; dx += 2) {
            for (int dz = -32; dz <= 32; dz += 2) {
                for (int dy = -8; dy <= 4; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(Blocks.WATER)
                            && level.getBlockState(pos.above()).isAir()) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static Vec3 horizontalLook(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    private static String testTag(ServerPlayer player) {
        return "minethunder_test_" + player.getUUID();
    }

    @Nullable
    private static ServerPlayer player(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(Component.literal("只有玩家可以运行测试"));
        return null;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearAssets(player);
            CURRENT_STAGE.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            clearAssets(player);
        }
        CURRENT_STAGE.clear();
    }
}
