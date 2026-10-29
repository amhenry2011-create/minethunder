package com.minethunder.network;

import com.minethunder.missile.IrTargeting;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.MissileLaunchService;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.ServerRadarState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.UUID;

/** 客户端请求：从背包扣除对应导弹烟花并直接发射。BVR 锁定时附带远程目标 UUID。 */
public record MissileFirePacket(int guidance, int targetId,
                                @Nullable UUID bvrTargetUuid) implements CustomPacketPayload {
    public static final Type<MissileFirePacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "fire_missile"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissileFirePacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeVarInt(packet.guidance);
                buf.writeVarInt(packet.targetId);
                buf.writeBoolean(packet.bvrTargetUuid != null);
                if (packet.bvrTargetUuid != null) buf.writeUUID(packet.bvrTargetUuid);
            }, MissileFirePacket::new);

    public MissileFirePacket(int guidance, int targetId) {
        this(guidance, targetId, null);
    }

    public MissileFirePacket(RegistryFriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean() ? buf.readUUID() : null);
    }

    @Override
    public Type<MissileFirePacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        MissileSpec spec = switch (guidance) {
            case 0 -> MissileSpecs.ARH;
            case 1 -> MissileSpecs.SARH;
            case 2 -> MissileSpecs.IR;
            default -> null;
        };
        if (spec == null) return;

        int slot = findAmmo(player, spec);
        if (slot < 0) {
            reject(player, "无可用导弹");
            return;
        }

        Entity target;
        if (spec.guidance == MissileSpec.Guidance.IR) {
            target = player.serverLevel().getEntity(targetId);
            if (target == null || !IrTargeting.valid(player.serverLevel(), player, target, spec)) {
                reject(player, "红外导引头未锁定热源");
                return;
            }
        } else {
            ServerRadarState.LockState state = ServerRadarState.get(player);
            target = ServerRadarState.getTarget(player);
            var mode = state != null && state.radarMode() >= 0
                    && state.radarMode() < com.minethunder.radar.RadarMode.values().length
                    ? com.minethunder.radar.RadarMode.values()[state.radarMode()]
                    : com.minethunder.radar.RadarMode.SRC_PD;
            var bvrUuid = ServerRadarState.getBvrTargetUuid(player);
            boolean bvrLock = bvrUuid != null && bvrUuid.equals(bvrTargetUuid);
            if (bvrLock) {
                // BVR 远程锁定：直接按 UUID 解析目标，跳过本地回波扫描。
                target = player.serverLevel().getEntity(bvrUuid);
            }
            if (state == null || !state.radarOn()) {
                reject(player, "雷达未开启");
                return;
            }
            if (target == null || !target.isAlive() || target == player) {
                reject(player, "雷达目标丢失");
                return;
            }
            var targetClass = RadarCore.classify(target);
            // BVR 远程锁定：服务端 validateBvrTarget 已按航迹校验；本地分类
            // （如落地幻翼被判 GROUND）与航迹显示类别可能不同，不做二次门槛。
            if (!bvrLock) {
                if (targetClass == null) {
                    reject(player, "目标类型无效");
                    return;
                }
                if (!mode.accepts(targetClass)) {
                    reject(player, "目标不适用当前雷达模式");
                    return;
                }
            }
            if (player.distanceTo(target) > spec.maxDistance) {
                reject(player, "目标超出导弹射程");
                return;
            }
            if (mode.usesNotch() && targetClass == com.minethunder.radar.RadarTargetClass.AIR
                    && !RadarCore.hasValidDoppler(player, target)) {
                reject(player, "目标处于多普勒盲区");
                return;
            }
            if (RadarCore.isOccludedForTarget(player.level(), player.getEyePosition(),
                    target.getBoundingBox().getCenter(), targetClass)) {
                reject(player, "目标被地形遮挡");
                return;
            }
            if (spec.guidance == MissileSpec.Guidance.ARH
                    && !ServerRadarState.canLaunchArh(player)) {
                reject(player, "ARH 数据链未就绪");
                return;
            }
            if (spec.guidance == MissileSpec.Guidance.SARH
                    && !ServerRadarState.canLaunchSarh(player)) {
                reject(player, "SARH 照射未就绪");
                return;
            }
        }

        if (MissileLaunchService.launch(player.serverLevel(), player, spec, target) == null) {
            reject(player, "发射失败");
            return;
        }
        if (slot < player.getInventory().items.size()) {
            player.getInventory().items.get(slot).shrink(1);
        } else {
            player.getInventory().offhand.get(slot - player.getInventory().items.size()).shrink(1);
        }
    }

    /** 发射被服务端拒绝时的 actionbar 反馈，避免客户端静默失败。 */
    private static void reject(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.literal(reason), true);
    }

    private static int findAmmo(ServerPlayer player, MissileSpec spec) {
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.getItem() instanceof MissileFireworkItem firework
                    && firework.getSpec().guidance == spec.guidance
                    && !stack.isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < player.getInventory().offhand.size(); i++) {
            ItemStack stack = player.getInventory().offhand.get(i);
            if (stack.getItem() instanceof MissileFireworkItem firework
                    && firework.getSpec().guidance == spec.guidance
                    && !stack.isEmpty()) {
                return player.getInventory().items.size() + i;
            }
        }
        return -1;
    }
}
