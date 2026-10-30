package com.minethunder.network;

import com.minethunder.radar.BvrKind;
import com.minethunder.radar.BvrTrack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端 BVR 远程航迹同步（每 2 tick 全量快照，只发给开雷达的玩家）。 */
public record BvrSyncPacket(List<BvrTrack> tracks) implements CustomPacketPayload {
    public static final Type<BvrSyncPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "bvr_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BvrSyncPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), BvrSyncPacket::new);

    public BvrSyncPacket(RegistryFriendlyByteBuf buf) {
        this(decode(buf));
    }

    private static List<BvrTrack> decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BvrTrack> list = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count && list.size() < 64; i++) {
            list.add(new BvrTrack(
                    buf.readUUID(),
                    BvrKind.values()[buf.readVarInt()],
                    buf.readBoolean() ? buf.readUUID() : null,
                    buf.readUtf(48),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    buf.readVarInt(),
                    buf.readBoolean()));
        }
        return list;
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(tracks.size());
        for (BvrTrack track : tracks) {
            buf.writeUUID(track.uuid());
            buf.writeVarInt(track.kind().ordinal());
            buf.writeBoolean(track.ownerUuid() != null);
            if (track.ownerUuid() != null) buf.writeUUID(track.ownerUuid());
            buf.writeUtf(track.name() == null ? "" : track.name(), 48);
            buf.writeDouble(track.position().x);
            buf.writeDouble(track.position().y);
            buf.writeDouble(track.position().z);
            buf.writeDouble(track.velocity().x);
            buf.writeDouble(track.velocity().y);
            buf.writeDouble(track.velocity().z);
            buf.writeVarInt(track.entityId());
            buf.writeBoolean(track.test());
        }
    }

    @Override
    public Type<BvrSyncPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        com.minethunder.client.BvrState.setTracks(tracks);
    }
}
