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
import net.minecraft.world.phys.Vec3;
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
            return;
        }

        Entity target;
        if (spec.guidance == MissileSpec.Guidance.IR) {
            target = player.serverLevel().getEntity(targetId);
            if (target == null || !IrTargeting.valid(player.serverLevel(), player, target, spec)) {
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
            if (state == null || !state.radarOn()
                    || target == null || !target.isAlive() || target == player) {
                return;
            }
            var targetClass = RadarCore.classify(target);
            if (targetClass == null) return;
            if (!mode.accepts(targetClass)) return;
            if (player.distanceTo(target) > spec.maxDistance) return;
            Vec3 to = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
            Vec3 look = player.getViewVector(1.0F).normalize();
            double az = Math.toDegrees(RadarCore.azimuthBetween(look, to));
            double el = Math.toDegrees(RadarCore.elevationBetween(look, to));
            if (Math.abs(az) > 120.0 || Math.abs(el) > 60.0) return;
            if (!bvrLock) {
                final Entity scannedTarget = target;
                boolean scanned = RadarCore.scan(player.level(), player.getEyePosition(),
                        look, mode, spec.maxDistance, Math.toRadians(120.0),
                        Math.toRadians(60.0), player.getDeltaMovement(), player)
                        .stream().anyMatch(c -> c.entity.getId() == scannedTarget.getId());
                if (!scanned) return;
            }
            if (RadarCore.isOccludedForTarget(player.level(), player.getEyePosition(),
                    target.getBoundingBox().getCenter(), targetClass)) {
                return;
            }
            if (spec.guidance == MissileSpec.Guidance.ARH
                    && !ServerRadarState.canLaunchArh(player)) {
                return;
            }
            if (spec.guidance == MissileSpec.Guidance.SARH
                    && !ServerRadarState.canLaunchSarh(player)) {
                return;
            }
        }

        if (MissileLaunchService.launch(player.serverLevel(), player, spec, target) == null) {
            return;
        }
        if (slot < player.getInventory().items.size()) {
            player.getInventory().items.get(slot).shrink(1);
        } else {
            player.getInventory().offhand.get(slot - player.getInventory().items.size()).shrink(1);
        }
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
