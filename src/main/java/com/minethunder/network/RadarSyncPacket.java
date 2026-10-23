package com.minethunder.network;

import com.minethunder.radar.ServerRadarState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 统一雷达同步：本地锁定用 targetId，BVR 远程锁定用 targetUuid + targetSource。
 * 服务端只保存一份锁定目标语义，不再维护客户端/服务端双状态。
 */
public record RadarSyncPacket(int targetId, int seekerState, int radarMode,
                              boolean hardLock, boolean radarOn, float trackQuality,
                              @Nullable UUID targetUuid, int targetSource)
        implements CustomPacketPayload {
    public static final Type<RadarSyncPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "radar_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarSyncPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), RadarSyncPacket::new);

    public RadarSyncPacket(RegistryFriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readFloat(),
                buf.readBoolean() ? buf.readUUID() : null, buf.readVarInt());
    }

    public RadarSyncPacket(int targetId, int seekerState, int radarMode,
                           boolean hardLock, boolean radarOn, float trackQuality) {
        this(targetId, seekerState, radarMode, hardLock, radarOn, trackQuality,
                null, 0);
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(targetId);
        buf.writeVarInt(seekerState);
        buf.writeVarInt(radarMode);
        buf.writeBoolean(hardLock);
        buf.writeBoolean(radarOn);
        buf.writeFloat(trackQuality);
        buf.writeBoolean(targetUuid != null);
        if (targetUuid != null) buf.writeUUID(targetUuid);
        buf.writeVarInt(targetSource);
    }

    @Override
    public Type<RadarSyncPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ServerRadarState.update(player, targetId, seekerState, radarMode,
                    hardLock, radarOn, trackQuality, targetUuid, targetSource);
        }
    }
}
