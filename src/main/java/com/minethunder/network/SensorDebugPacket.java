package com.minethunder.network;

import com.minethunder.sensor.SensorDebugState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SensorDebugPacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<SensorDebugPacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "sensor_debug"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SensorDebugPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), SensorDebugPacket::new);

    public SensorDebugPacket(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    @Override
    public Type<SensorDebugPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        SensorDebugState.setClientEnabled(enabled);
    }
}
