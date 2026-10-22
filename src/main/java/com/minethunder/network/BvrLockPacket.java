package com.minethunder.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.UUID;

/** 客户端发起/解除 BVR 远程锁定（ARH 专用软锁）。 */
public record BvrLockPacket(@Nullable UUID targetUuid) implements CustomPacketPayload {
    public static final Type<BvrLockPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "bvr_lock"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BvrLockPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), BvrLockPacket::new);

    public BvrLockPacket(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readUUID() : null);
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(targetUuid != null);
        if (targetUuid != null) buf.writeUUID(targetUuid);
    }

    @Override
    public Type<BvrLockPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (targetUuid == null) {
            com.minethunder.radar.ServerRadarState.setBvrTarget(player, null);
            return;
        }
        com.minethunder.radar.ServerRadarState.setBvrTarget(player, targetUuid);
    }
}
