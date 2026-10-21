package com.minethunder.network;

import com.minethunder.Registration;
import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record CountermeasurePacket(int kind) implements CustomPacketPayload {
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();
    public static final Type<CountermeasurePacket> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minethunder", "countermeasure"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CountermeasurePacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.encode(buf), CountermeasurePacket::new);

    public CountermeasurePacket(RegistryFriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(kind);
    }

    @Override
    public Type<CountermeasurePacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;

        long now = player.serverLevel().getGameTime();
        long until = COOLDOWNS.getOrDefault(player.getUUID(), 0L);
        if (now < until) return;
        COOLDOWNS.put(player.getUUID(), now + 2);

        if (kind == 3) {
            spawn(player, Countermeasure.CHAFF);
            spawn(player, Countermeasure.FLARE);
        } else {
            Countermeasure countermeasure = switch (kind) {
                case 1 -> Countermeasure.FLARE;
                case 2 -> Countermeasure.ECM;
                default -> Countermeasure.CHAFF;
            };
            spawn(player, countermeasure);
        }
    }

    private static void spawn(ServerPlayer player, Countermeasure kind) {
        Vec3 look = player.getViewVector(1.0F).normalize();
        CountermeasureEntity entity = new CountermeasureEntity(
                Registration.COUNTERMEASURE.get(), player.serverLevel());
        entity.setPos(player.getX() + look.x * 1.0, player.getEyeY() - 0.1,
                player.getZ() + look.z * 1.0);
        entity.setOwner(player);
        entity.setItem(new ItemStack(kind == Countermeasure.FLARE ? Items.FIRE_CHARGE
                : kind == Countermeasure.ECM ? Items.GUNPOWDER : Items.SNOWBALL));
        entity.setDeltaMovement(look.scale(0.7).add(
                player.getRandom().nextDouble() * 0.2 - 0.1,
                player.getRandom().nextDouble() * 0.12,
                player.getRandom().nextDouble() * 0.2 - 0.1));
        player.serverLevel().addFreshEntity(entity);
    }
}
