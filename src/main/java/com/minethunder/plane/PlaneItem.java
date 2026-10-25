package com.minethunder.plane;

import com.minethunder.Registration;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 简单飞机部署物品：对准地面/跑道右键即可生成处于冷舱（Cold & Dark）状态的固定翼飞机。
 */
public class PlaneItem extends Item {
    private final PlaneTypeSpec spec;

    public PlaneItem(Properties properties, PlaneTypeSpec spec) {
        super(properties);
        this.spec = spec;
    }

    public PlaneTypeSpec getSpec() {
        return spec;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hitResult.getType() == HitResult.Type.MISS) {
            return InteractionResultHolder.pass(stack);
        }

        Vec3 view = player.getViewVector(1.0F);
        List<Entity> colliding = level.getEntities(player,
                player.getBoundingBox().expandTowards(view.scale(5.0)).inflate(1.0),
                EntitySelector.NO_SPECTATORS.and(Entity::isPickable));
        if (!colliding.isEmpty()) {
            Vec3 eye = player.getEyePosition();
            for (Entity entity : colliding) {
                AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
                if (box.contains(eye)) {
                    return InteractionResultHolder.pass(stack);
                }
            }
        }

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos blockPos = hitResult.getBlockPos();
            Vec3 spawnPos = new Vec3(
                    blockPos.getX() + 0.5,
                    blockPos.getY() + 1.05,
                    blockPos.getZ() + 0.5
            );
            EntityType<PlaneEntity> entityType = switch (spec) {
                case SMALL_PLANE -> Registration.SMALL_PLANE.get();
                case LARGE_PLANE -> Registration.LARGE_PLANE.get();
                case CARGO_PLANE -> Registration.CARGO_PLANE.get();
            };
            PlaneEntity plane = entityType.create(level);
            if (plane == null) {
                return InteractionResultHolder.fail(stack);
            }
            plane.setPlaneType(spec);
            plane.setEngineType(spec.defaultEngine());
            plane.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), 0.0F);
            plane.setYawDeg(player.getYRot());
            plane.setPitchDeg(0.0f);
            plane.setRollDeg(0.0f);

            if (!level.noCollision(plane, plane.getBoundingBox().deflate(0.1))) {
                spawnPos = spawnPos.add(0.0, 1.0, 0.0);
                plane.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
            }

            if (level instanceof ServerLevel serverLevel) {
                serverLevel.addFreshEntity(plane);
                level.gameEvent(player, GameEvent.ENTITY_PLACE, blockPos);
                level.playSound(null, blockPos, SoundEvents.IRON_GOLEM_REPAIR,
                        SoundSource.NEUTRAL, 0.8F, 1.1F);
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.awardStat(Stats.ITEM_USED.get(this));
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(spec.displayName()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal(String.format("乘员: %d人 | 限制过载: +%.1fG / %.1fG | 失速: %.0f m/s",
                        spec.maxPassengers(), spec.maxPositiveG(), spec.maxNegativeG(), spec.cleanStallSpeedMps()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("默认引擎: " + spec.defaultEngine().displayName())
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("冷舱启动: [B]电瓶 → [F]燃油泵 → [G]磁电机(BOTH) → 长按[I]点火 → [P]松驻车")
                .withStyle(ChatFormatting.GREEN));
    }
}
