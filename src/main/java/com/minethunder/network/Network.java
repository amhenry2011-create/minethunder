package com.minethunder.network;

import com.minethunder.MineThunder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    private Network() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(RadarSyncPacket.TYPE, RadarSyncPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
        registrar.playToServer(RwrPaintPacket.TYPE, RwrPaintPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
        registrar.playToServer(CountermeasurePacket.TYPE, CountermeasurePacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
        registrar.playToServer(MissileFirePacket.TYPE, MissileFirePacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
        registrar.playToClient(SensorDebugPacket.TYPE, SensorDebugPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
        registrar.playToClient(RwrSyncPacket.TYPE, RwrSyncPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
        registrar.playToClient(BvrSyncPacket.TYPE, BvrSyncPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> packet.handle(context)));
    }

    public static void sendToServer(CustomPacketPayload message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendToPlayer(CustomPacketPayload message, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, message);
    }
}
