package com.minethunder.network;

import com.minethunder.radar.RwrContact;
import com.minethunder.radar.RwrThreat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 服务端 RWR 接触同步。 */
public record RwrSyncPacket(List<RwrContact> contacts) implements CustomPacketPayload {
    public static final Type<RwrSyncPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "rwr_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RwrSyncPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), RwrSyncPacket::new);

    public RwrSyncPacket(RegistryFriendlyByteBuf buf) {
        this(decode(buf));
    }

    private static List<RwrContact> decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<RwrContact> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new RwrContact(
                    buf.readVarInt(),
                    RwrThreat.byCode(buf.readVarInt()),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readUtf(48),
                    buf.readVarInt()));
        }
        return list;
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(contacts.size());
        for (RwrContact c : contacts) {
            buf.writeVarInt(c.sourceId());
            buf.writeVarInt(c.threat().code());
            buf.writeFloat((float) c.bearingDeg());
            buf.writeFloat((float) c.elevationDeg());
            buf.writeFloat((float) c.range());
            buf.writeUtf(c.tag(), 48);
            buf.writeVarInt(c.ageTicks());
        }
    }

    @Override
    public Type<RwrSyncPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        com.minethunder.client.RwrState.setContacts(contacts);
    }
}
