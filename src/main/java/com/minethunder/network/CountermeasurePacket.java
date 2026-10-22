package com.minethunder.network;

import com.minethunder.Registration;
import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

        boolean deployed = false;
        if (kind == 3) {
            int chaff = consume(player, Items.SNOWBALL, 3);
            int flares = consume(player, Items.FIRE_CHARGE, 3);
            if (chaff > 0) {
                spawnBurst(player, Countermeasure.CHAFF, chaff);
                deployed = true;
            }
            if (flares > 0) {
                spawnBurst(player, Countermeasure.FLARE, flares);
                deployed = true;
            }
        } else {
            Countermeasure countermeasure = kind == 1
                    ? Countermeasure.FLARE : Countermeasure.CHAFF;
            Item required = countermeasure == Countermeasure.FLARE
                    ? Items.FIRE_CHARGE : Items.SNOWBALL;
            int count = consume(player, required, 3);
            if (count > 0) {
                spawnBurst(player, countermeasure, count);
                deployed = true;
            }
        }
        if (deployed) COOLDOWNS.put(player.getUUID(), now + 2);
    }

    public static void clearCooldown(ServerPlayer player) {
        COOLDOWNS.remove(player.getUUID());
    }

    private static void spawnBurst(ServerPlayer player, Countermeasure kind, int count) {
        for (int i = 0; i < count; i++) {
            spawn(player, kind);
        }
    }

    private static int consume(ServerPlayer player, Item item, int requested) {
        int remaining = requested;
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!stack.is(item) || stack.isEmpty()) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        for (int i = 0; i < player.getInventory().offhand.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().offhand.get(i);
            if (!stack.is(item) || stack.isEmpty()) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return requested - remaining;
    }

    private static void spawn(ServerPlayer player, Countermeasure kind) {
        CountermeasureEntity entity = new CountermeasureEntity(
                Registration.COUNTERMEASURE.get(), player.serverLevel());
        entity.setPos(player.getX() + player.getRandom().nextDouble() * 0.8 - 0.4,
                player.getY() + 0.2,
                player.getZ() + player.getRandom().nextDouble() * 0.8 - 0.4);
        entity.setOwner(player);
        entity.setItem(new ItemStack(kind == Countermeasure.FLARE
                ? Items.FIRE_CHARGE : Items.SNOWBALL));
        entity.setDeltaMovement(
                player.getRandom().nextDouble() * 0.28 - 0.14,
                0.35 + player.getRandom().nextDouble() * 0.2,
                player.getRandom().nextDouble() * 0.28 - 0.14);
        player.serverLevel().addFreshEntity(entity);
    }
}
