package com.minethunder.network;

import com.minethunder.radar.RwrManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端 SRC PD 扫描线命中玩家时，把目标实体 id 上报给服务端 RWR。 */
public record RwrPaintPacket(int targetId) implements CustomPacketPayload {
    public static final Type<RwrPaintPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "minethunder", "rwr_paint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RwrPaintPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> buf.writeVarInt(packet.targetId),
                    buf -> new RwrPaintPacket(buf.readVarInt()));

    @Override
    public Type<RwrPaintPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (targetId >= 0 && context.player() instanceof ServerPlayer player) {
            RwrManager.recordPaint(player, targetId);
        }
    }
}
