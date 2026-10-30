package com.minethunder.radar;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** BVR 远程航迹快照：服务端每 2 tick 生成，客户端按 UUID 维护缓存。 */
public record BvrTrack(java.util.UUID uuid, BvrKind kind,
                       @Nullable java.util.UUID ownerUuid,
                       String name,
                       Vec3 position, Vec3 velocity,
                       int entityId,
                       boolean test) {
}
