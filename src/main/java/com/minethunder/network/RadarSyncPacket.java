package com.minethunder.network;

import com.minethunder.radar.ServerRadarState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RadarSyncPacket(int targetId, int seekerState, int radarMode,
                              boolean hardLock, boolean radarOn, float trackQuality)
        implements CustomPacketPayload {
    public static final Type<RadarSyncPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "radar_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarSyncPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), RadarSyncPacket::new);

    public RadarSyncPacket(RegistryFriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readFloat());
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(targetId);
        buf.writeVarInt(seekerState);
        buf.writeVarInt(radarMode);
        buf.writeBoolean(hardLock);
        buf.writeBoolean(radarOn);
        buf.writeFloat(trackQuality);
    }

    @Override
    public Type<RadarSyncPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // BVR 远程锁定不随雷达快照被清空，由服务端自行续期/校验。
            ServerRadarState.refreshLockState(player, targetId, seekerState, radarMode,
                    hardLock, radarOn, trackQuality);
        }
    }
}
