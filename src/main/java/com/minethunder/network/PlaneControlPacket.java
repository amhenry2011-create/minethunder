package com.minethunder.network;

import com.minethunder.plane.PlaneEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端向服务端同步飞机舵面操纵杆输入、节流阀微调与冷舱启动电门动作。
 */
public record PlaneControlPacket(
        float pitchInput,
        float rollInput,
        float yawInput,
        float throttleDelta,
        boolean wheelBrakeHold,
        boolean starterHold,
        int actionMask
) implements CustomPacketPayload {

    public static final int ACT_TOGGLE_BATTERY   = 1 << 0;
    public static final int ACT_TOGGLE_FUEL_PUMP = 1 << 1;
    public static final int ACT_CYCLE_MAGNETO    = 1 << 2;
    public static final int ACT_FLAPS_UP         = 1 << 3;
    public static final int ACT_FLAPS_DOWN       = 1 << 4;
    public static final int ACT_TOGGLE_GEAR      = 1 << 5;
    public static final int ACT_TOGGLE_PARK_BRK  = 1 << 6;
    public static final int ACT_TRIM_DOWN        = 1 << 7;
    public static final int ACT_TRIM_UP          = 1 << 8;
    public static final int ACT_TRIM_RESET       = 1 << 9;

    public static final Type<PlaneControlPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minethunder", "plane_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaneControlPacket> STREAM_CODEC =
            StreamCodec.of((buf, pkt) -> {
                buf.writeFloat(pkt.pitchInput);
                buf.writeFloat(pkt.rollInput);
                buf.writeFloat(pkt.yawInput);
                buf.writeFloat(pkt.throttleDelta);
                buf.writeBoolean(pkt.wheelBrakeHold);
                buf.writeBoolean(pkt.starterHold);
                buf.writeVarInt(pkt.actionMask);
            }, PlaneControlPacket::new);

    public PlaneControlPacket(RegistryFriendlyByteBuf buf) {
        this(
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt()
        );
    }

    @Override
    public Type<PlaneControlPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (player.getVehicle() instanceof PlaneEntity plane) {
            plane.applyPilotPacket(
                    player,
                    pitchInput,
                    rollInput,
                    yawInput,
                    throttleDelta,
                    wheelBrakeHold,
                    starterHold,
                    actionMask
            );
        }
    }
}
