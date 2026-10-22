package com.minethunder.missile;

import com.minethunder.countermeasure.Countermeasure;
import com.minethunder.countermeasure.CountermeasureEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.minethunder.radar.RadarCore;
import com.minethunder.radar.ServerRadarState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * 导弹箭实体基类：比例导引制导（Proportional Navigation）。
 * 按弹种区分导引行为（见 {@link MissileSpec.Guidance}）。
 *
 * 寻的模式：
 * - IR：发射即锁热源，全程追目标。
 * - SARH：依赖发射方 STT，目标由发射方雷达提供；失锁惯性飞行，延续时间后自爆。
 * - ARH：中段惯性/指令飞行，末段（超过导引头开机距离）自开雷达寻的。
 */
public abstract class MissileArrow extends AbstractArrow {
    private static final EntityDataAccessor<Optional<UUID>> DATA_TARGET =
            SynchedEntityData.defineId(MissileArrow.class, EntityDataSerializers.OPTIONAL_UUID);

    protected final MissileSpec spec;
    @Nullable
    protected UUID targetId;
    protected int age = 0;
    /** 发射后累计飞行距离（用于制导包线/失去目标后的 pitbull 兜底判定） */
    protected double distanceFlown = 0;
    protected Vec3 lastPos;
    protected Vec3 launchPos;
    @Nullable protected Vec3 launchVelocity;
    /** 防止导弹拦截/近炸递归引爆。 */
    protected boolean detonating = false;
    /** 测试弹不造成伤害，并允许测试弹之间互相拦截。 */
    protected boolean testMissile = false;
    /** 中段数据链/IOG 最后航迹。 */
    @Nullable protected Vec3 lastKnownTargetPos;
    protected Vec3 lastKnownTargetVelocity = Vec3.ZERO;
    protected int lastKnownTargetTick = -1;
    /** ARH 是否已进入末段主动雷达开机（pitbull）。由弹目距离触发，不再只看飞行距离。 */
    protected boolean pitbullActive = false;

    /** 数据链测量：带延迟/噪声/丢帧的航迹，不等同于精确实体位置。 */
    @Nullable protected Vec3 measuredTargetPos;
    protected Vec3 measuredTargetVelocity = Vec3.ZERO;
    protected Vec3 measuredTargetAcceleration = Vec3.ZERO;
    protected int measuredTargetTick = -100;
    protected int datalinkUpdateCooldown = 0;
    protected float trackQuality = 1.0f;
    /** 制导指令低通，模拟舵机/弹体响应延迟。 */
    protected Vec3 servoAcceleration = Vec3.ZERO;
    /** 导引头 LOS 的上一帧方向，用于 rateMax 跟踪速率限制。 */
    @Nullable protected Vec3 guidanceLosDir;
    /** 低速看门狗：非测试弹低速悬停计数（滑翔段阻力清零属于异常）。 */
    protected int slowSpeedTicks;

    private static final int DATALINK_UPDATE_TICKS = 4;
    private static final double SERVO_TAU = 0.08;
    private static final double CONTROL_RAMP_SECONDS = 0.25;

    protected MissileArrow(EntityType<? extends AbstractArrow> type, Level level, MissileSpec spec) {
        super(type, level);
        this.spec = spec;
        this.lastPos = position();
        this.launchPos = position();
        // 原版箭每 tick 有约 20 m/s² 重力；导弹由比例导引自行配平，必须禁用。
        this.setNoGravity(true);
        this.pickup = Pickup.DISALLOWED;
    }

    public MissileSpec getSpec() {
        return spec;
    }

    public int getAgeTicks() {
        return age;
    }

    public double getDistanceFlown() {
        return distanceFlown;
    }

    public boolean isTestMissile() {
        return testMissile;
    }

    public void setTestMissile(boolean testMissile) {
        this.testMissile = testMissile;
        this.lastPos = position();
        this.launchPos = position();
        this.distanceFlown = 0.0;
    }

    public boolean isDetonating() {
        return detonating;
    }

    /** 当前导弹是否有可用的载机雷达数据链（硬锁定、TWS 跟踪或 BVR 远程软锁）。 */
    protected boolean datalinkValid() {
        if (spec.guidance == MissileSpec.Guidance.IR) return false;
        if (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive) return false;
        if (spec.guidance == MissileSpec.Guidance.SARH) return datalinkValidLocal();
        if (!(getOwner() instanceof ServerPlayer player)) return false;
        ServerRadarState.LockState state = ServerRadarState.get(player);
        if (state == null || targetId == null) return false;
        Entity target = getTarget();
        if (target == null || !target.isAlive()) return false;
        UUID bvr = state.bvrTargetUuid();
        if (state.targetId() < 0 && bvr != null && bvr.equals(targetId)) return true;
        return datalinkValidLocal();
    }

    private boolean datalinkValidLocal() {
        if (!(getOwner() instanceof ServerPlayer player)) return false;
        ServerRadarState.LockState state = ServerRadarState.get(player);
        if (state == null || state.targetId() < 0 || targetId == null) return false;
        Entity target = getTarget();
        if (target == null || !target.isAlive()) return false;
        if (state.targetId() != target.getId()) return false;
        return state.hardLock() || state.radarMode() == com.minethunder.radar.RadarMode.TWS.ordinal();
    }

    /** 用载机速度 + 导弹初速初始化发射速度；有目标时直接朝预测拦截点发射。 */
    public void initializeLaunch(LivingEntity shooter) {
        Vec3 carrier = shooter.getDeltaMovement();
        double carrierMps = carrier.length() * 20.0;
        double relativeMps = spec.startSpeed > 0 ? spec.startSpeed : 20.0;
        Vec3 look = shooter.getViewVector(1.0F).normalize();
        Vec3 direction = look;

        Entity target = getTarget();
        if (target != null && target.isAlive()) {
            Vec3 eye = shooter.getEyePosition();
            Vec3 targetCenter = target.getBoundingBox().getCenter();
            Vec3 targetVel = target.getDeltaMovement().scale(20.0);
            Vec3 aim = targetCenter;
            double speed = Math.max(carrierMps + relativeMps, 1.0);
            // 两次迭代足以修正高速横穿目标的提前量。
            for (int i = 0; i < 2; i++) {
                double time = eye.distanceTo(aim) / speed;
                aim = targetCenter.add(targetVel.scale(time));
            }
            Vec3 lead = aim.subtract(eye);
            if (lead.lengthSqr() > 1.0E-6) {
                lead = lead.normalize();
                // 预测点不能超出可用发射离轴角太多，否则近距离横穿会向侧面甩飞。
                double maxAngle = spec.guidance == MissileSpec.Guidance.IR
                        ? IrTargeting.effectiveLockAngleDeg(spec) : spec.lockAngleMaxDeg;
                if (lead.dot(look) >= Math.cos(Math.toRadians(maxAngle))) {
                    direction = lead;
                }
            }
        }

        Vec3 initialVelocity = carrier.add(direction.scale(relativeMps / 20.0));
        setDeltaMovement(initialVelocity);
        this.launchVelocity = initialVelocity;
        // 构造时实体可能还没有被 setPos；初始化后同步碰撞检测起点。
        this.lastPos = position();
        this.launchPos = position();
    }

    @Nullable
    public UUID getTargetUuid() {
        if (level().isClientSide) {
            return entityData.get(DATA_TARGET).orElse(null);
        }
        return targetId;
    }

    /** 当前导弹是否以该实体为目标（用于 RWR 来袭告警）。 */
    public boolean isTargeting(Entity entity) {
        UUID id = getTargetUuid();
        return id != null && id.equals(entity.getUUID());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TARGET, Optional.empty());
    }

    /** 设置初始目标（发射时锁定的实体），并同步 UUID 给客户端用于 RWR/调试。 */
    public void setTarget(@Nullable Entity target) {
        this.targetId = target == null ? null : target.getUUID();
        this.measuredTargetPos = null;
        this.measuredTargetVelocity = Vec3.ZERO;
        this.measuredTargetAcceleration = Vec3.ZERO;
        this.measuredTargetTick = -100;
        this.lastKnownTargetPos = null;
        this.lastKnownTargetVelocity = Vec3.ZERO;
        this.lastKnownTargetTick = -1;
        this.guidanceLosDir = null;
        if (!level().isClientSide) {
            this.entityData.set(DATA_TARGET, Optional.ofNullable(this.targetId));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (targetId != null) tag.putString("MTTarget", targetId.toString());
        tag.putInt("MTAge", age);
        tag.putDouble("MTDistanceFlown", distanceFlown);
        tag.putBoolean("MTPitbull", pitbullActive);
        tag.putBoolean("MTTest", testMissile);
        tag.putInt("MTDatalinkCooldown", datalinkUpdateCooldown);
        tag.putFloat("MTTrackQuality", trackQuality);
        tag.putInt("MTMeasuredTick", measuredTargetTick);
        tag.putInt("MTLastKnownTick", lastKnownTargetTick);
        tag.putInt("MTSlowTicks", slowSpeedTicks);
        putVec(tag, "MTMeasuredPos", measuredTargetPos);
        putVec(tag, "MTMeasuredVel", measuredTargetVelocity);
        putVec(tag, "MTMeasuredAccel", measuredTargetAcceleration);
        putVec(tag, "MTLastKnownPos", lastKnownTargetPos);
        putVec(tag, "MTLastKnownVel", lastKnownTargetVelocity);
        putVec(tag, "MTGuidanceLos", guidanceLosDir);
        putVec(tag, "MTServoAccel", servoAcceleration);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        targetId = tag.contains("MTTarget") ? parseUuid(tag.getString("MTTarget")) : null;
        if (!level().isClientSide) {
            entityData.set(DATA_TARGET, Optional.ofNullable(targetId));
        }
        age = tag.getInt("MTAge");
        distanceFlown = tag.getDouble("MTDistanceFlown");
        pitbullActive = tag.getBoolean("MTPitbull");
        testMissile = tag.getBoolean("MTTest");
        datalinkUpdateCooldown = tag.getInt("MTDatalinkCooldown");
        trackQuality = tag.getFloat("MTTrackQuality");
        measuredTargetTick = tag.getInt("MTMeasuredTick");
        lastKnownTargetTick = tag.getInt("MTLastKnownTick");
        slowSpeedTicks = tag.getInt("MTSlowTicks");
        measuredTargetPos = readVec(tag, "MTMeasuredPos");
        measuredTargetVelocity = readVecOrZero(tag, "MTMeasuredVel");
        measuredTargetAcceleration = readVecOrZero(tag, "MTMeasuredAccel");
        lastKnownTargetPos = readVec(tag, "MTLastKnownPos");
        lastKnownTargetVelocity = readVecOrZero(tag, "MTLastKnownVel");
        guidanceLosDir = readVec(tag, "MTGuidanceLos");
        servoAcceleration = readVecOrZero(tag, "MTServoAccel");
        lastPos = position();
        launchPos = position();
    }

    @Nullable
    protected static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void putVec(CompoundTag tag, String key, @Nullable Vec3 value) {
        if (value == null) return;
        tag.putDouble(key + "X", value.x);
        tag.putDouble(key + "Y", value.y);
        tag.putDouble(key + "Z", value.z);
    }

    @Nullable
    private static Vec3 readVec(CompoundTag tag, String key) {
        if (!tag.contains(key + "X")) return null;
        return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"),
                tag.getDouble(key + "Z"));
    }

    private static Vec3 readVecOrZero(CompoundTag tag, String key) {
        Vec3 value = readVec(tag, key);
        return value == null ? Vec3.ZERO : value;
    }

    @Nullable
    protected Entity getTarget() {
        if (targetId == null) return null;
        if (level() instanceof ServerLevel serverLevel) {
            return serverLevel.getEntity(targetId);
        }
        return null;
    }

    @Override
    public void tick() {
        super.tick();
        age++;

        Vec3 pos = position();
        Vec3 previous = lastPos;
        boolean initialPosition = lastPos == null || age <= 1
                || lastPos.distanceToSqr(pos) > 4096.0;
        if (initialPosition) {
            // 客户端实体从生成包获得位置前 lastPos 可能在 (0,0,0)；
            // 第一 tick 不能拿原点做地形碰撞，否则导弹会在客户端瞬间自毁。
            previous = null;
            distanceFlown = 0;
            lastPos = pos;
            launchPos = pos;
        } else {
            distanceFlown += pos.distanceTo(lastPos);
            lastPos = pos;
        }

        // 地形碰撞只由服务端裁决；客户端提前删除会导致弹体和烟迹消失。
        if (!level().isClientSide && previous != null && previous.distanceToSqr(pos) > 1.0E-6) {
            var hit = level().clip(new net.minecraft.world.level.ClipContext(
                    previous, pos, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                detonate(null);
                return;
            }
        }

        if (!level().isClientSide && previous != null) {
            checkMissileIntercept(previous, pos);
            if (isRemoved() || detonating) return;
        }

        if (level().isClientSide) {
            spawnEngineParticles(pos);
        }

        // ARH 离架后 0.5 秒无动力下飘；SARH/IR 直接点火。
        if (spec.guidance == MissileSpec.Guidance.ARH
                && age / 20.0 < spec.ignitionDelay()) {
            setDeltaMovement(getDeltaMovement().add(0.0, -0.05, 0.0));
        }

        // 发射瞬间目标捕获（服务端）：按发射者视线方向找目标
        if (!level().isClientSide && targetId == null && age <= 2) {
            if (getOwner() instanceof LivingEntity shooter) {
                Entity acquired = spec.guidance == MissileSpec.Guidance.IR
                        && shooter instanceof net.minecraft.world.entity.player.Player player
                        ? IrTargeting.findTarget(level(), player, null, spec)
                        : MissileTargeting.findLookTarget(
                        level(), shooter, spec.acquireRange(), spec.lockAngleMaxDeg);
                if (acquired != null) {
                    setTarget(acquired);
                }
            }
        }

        // 制导时长/最大飞行距离耗尽：仅由服务端裁决，客户端等待移除包。
        if (!level().isClientSide
                && (age / 20.0 > spec.timeLife || distanceFlown > spec.maxDistance)) {
            detonate(null);
            return;
        }

        updatePitbullState();
        updateDatalinkTarget();

        Entity target = resolveTarget();
        updateDatalinkMeasurement(target);
        Entity decoy = findCountermeasure(target);
        Vec3 guidancePos = null;
        Vec3 guidanceVel = Vec3.ZERO;
        if (decoy != null && decoy.isAlive()) {
            guidancePos = decoy.getBoundingBox().getCenter();
            guidanceVel = decoy.getDeltaMovement().scale(20.0);
        } else {
            updateTargetTrack(target);
            guidancePos = guidancePosition(target);
            guidanceVel = guidanceVelocity(target);
        }
        if (guidancePos != null) {
            applyGuidance(guidancePos, guidanceVel, pos);
        }

        // 近炸引信：靠近目标或有效假目标一定距离即引爆
        Entity fuseTarget = decoy != null ? decoy : getTarget();
        if (!level().isClientSide && fuseTarget != null && fuseTarget.isAlive()
                && !IrTargeting.isSunMarker(fuseTarget)) {
            double d = pos.distanceTo(fuseTarget.getBoundingBox().getCenter());
            boolean fuseHit = d <= spec.proximityFuseRadius;
            if (!fuseHit && previous != null) {
                fuseHit = fuseTarget.getBoundingBox()
                        .inflate(spec.proximityFuseRadius)
                        .clip(previous, pos).isPresent();
            }
            if (fuseHit) {
                detonate(fuseTarget);
            }
        }

        // 地面/水面接触由服务端销毁。
        if (!level().isClientSide && (onGround() || isInWater())) {
            detonate(null);
        }

        // 低速看门狗：制导/阻力异常导致悬停时提前引爆，避免导弹永久悬浮卡空。
        if (!level().isClientSide && !testMissile) {
            boolean enginePre = age / 20.0 < spec.ignitionDelay();
            double mps = getDeltaMovement().length() * 20.0;
            if (!enginePre && age > 40 && mps < STALL_SPEED_MPS) {
                if (++slowSpeedTicks >= SLOW_TICKS_BEFORE_DETONATE) {
                    detonate(null);
                    return;
                }
            } else {
                slowSpeedTicks = 0;
            }
        }
    }

    @Override
    public void shoot(double x, double y, double z, float velocity, float inaccuracy) {
        Vec3 saved = launchVelocity;
        super.shoot(x, y, z, velocity, inaccuracy);
        if (saved != null) {
            setDeltaMovement(saved);
        }
    }

    @Override
    protected void onHitEntity(net.minecraft.world.phys.EntityHitResult result) {
        if (!level().isClientSide) {
            detonate(result.getEntity());
        }
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
        if (!level().isClientSide) {
            detonate(null);
        }
    }

    /** 发动机是否正在工作。 */
    public boolean isEngineOn() {
        double t = age / 20.0;
        return t >= spec.ignitionDelay() && t <= spec.ignitionDelay() + spec.timeFire;
    }

    private void spawnEngineParticles(Vec3 pos) {
        if (!isEngineOn()) return;
        Vec3 velocity = getDeltaMovement();
        Vec3 tail = velocity.lengthSqr() > 1.0E-6
                ? pos.subtract(velocity.normalize().scale(0.35))
                : pos;
        level().addParticle(ParticleTypes.FLAME,
                tail.x, tail.y, tail.z, 0.0, 0.0, 0.0);
        if (age % 2 != 0) return;

        double spread = 0.14;
        for (int i = 0; i < 2; i++) {
            double dx = (level().random.nextDouble() - 0.5) * spread;
            double dy = (level().random.nextDouble() - 0.5) * spread;
            double dz = (level().random.nextDouble() - 0.5) * spread;
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    tail.x + dx, tail.y + dy, tail.z + dz,
                    -velocity.x * 0.05 + dx, 0.012, -velocity.z * 0.05 + dz);
        }
        level().addParticle(ParticleTypes.SMOKE,
                tail.x, tail.y, tail.z, 0.0, 0.01, 0.0);
    }

    /** 在导引头视场内寻找能欺骗本弹的箔条/热焰弹。 */
    @Nullable
    protected Entity findCountermeasure(@Nullable Entity currentTarget) {
        if (spec.guidance == MissileSpec.Guidance.ARH && !pitbullActive) {
            return null;
        }
        Countermeasure.MissileThreat threat = spec.guidance == MissileSpec.Guidance.IR
                ? Countermeasure.MissileThreat.INFRARED : Countermeasure.MissileThreat.RADAR;
        Vec3 pos = position();
        Vec3 vel = getDeltaMovement();
        if (vel.lengthSqr() < 0.01) return null;
        Vec3 velDir = vel.normalize();

        double currentDist = currentTarget == null ? Double.MAX_VALUE
                : pos.distanceTo(currentTarget.getBoundingBox().getCenter());
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (CountermeasureEntity decoy : level().getEntitiesOfClass(
                CountermeasureEntity.class, getBoundingBox().inflate(48.0))) {
            if (!decoy.isAlive() || !decoy.deceives(threat)) continue;
            Vec3 decoyCenter = decoy.getBoundingBox().getCenter();
            if (RadarCore.isOccluded(level(), pos, decoyCenter)) continue;
            Vec3 to = decoyCenter.subtract(pos);
            double dist = to.length();
            if (dist < 0.5 || dist > 48.0 || dist > currentDist * 1.2 + 8.0) continue;
            double angle = Math.toDegrees(Math.acos(clamp(velDir.dot(to.normalize()), -1, 1)));
            // 有当前目标时使用距离门内视场；只有重新搜索时才用完整 FOV。
            double gate = currentTarget != null
                    ? Math.max(1.0, spec.gateWidthDeg) : spec.seekerFovDeg;
            if (angle > gate) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = decoy;
            }
        }
        return best;
    }

    /**
     * 决定当前追踪目标。子类按弹种覆盖：
     * - IR/SARH：返回发射时锁定的目标。
     * - ARH：超过导引头开机距离后自导，否则不追踪（中段靠发射方指令）。
     */
    @Nullable
    protected Entity resolveTarget() {
        return getTarget();
    }

    /**
     * 真比例导引（PN），内部速度/加速度单位统一为 SI 后再换算到 blocks/tick。
     * 公式：a = N * Vc * (omega × r_hat)
     */
    protected void applyGuidance(Vec3 rawTargetPos, Vec3 targetVelMps, Vec3 pos) {
        double elapsed = age / 20.0;
        // 发动机点火前也允许弹翼修正航向；0.5s 只是无推力段，不是完全失控。
        if (elapsed < spec.timeOut) return;

        Vec3 targetPos = loftAimPoint(rawTargetPos, pos);
        Vec3 los = targetPos.subtract(pos);
        double dist = los.length();
        if (dist < 0.5) return;
        Vec3 losDir = los.normalize();

        Vec3 velocity = getDeltaMovement();
        double speedMps = velocity.length() * 20.0;
        if (speedMps < 1.0) return;
        Vec3 velDir = velocity.normalize();

        // IR/SARH 以及 ARH 末段都必须受导引头视场和角跟踪速率限制。
        boolean seekerLimited = spec.guidance == MissileSpec.Guidance.IR
                || spec.guidance == MissileSpec.Guidance.SARH
                || (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive);
        if (seekerLimited) {
            if (guidanceLosDir == null) {
                guidanceLosDir = losDir;
            } else {
                double maxSlew = Math.toRadians(spec.rateMaxDegPerSec) / 20.0;
                guidanceLosDir = slewTowards(guidanceLosDir, losDir, maxSlew);
            }
            losDir = guidanceLosDir;
            los = losDir.scale(dist);
        }

        // 制导视场限制：雷达弹中段使用弹体可用转向角，ARH 末段才收紧到导引头 FOV。
        double angle = Math.toDegrees(Math.acos(clamp(velDir.dot(losDir), -1, 1)));
        double allowedFov = seekerLimited
                ? spec.guidanceFovDeg(pitbullActive) : spec.angleMaxDeg;
        if (angle > allowedFov) {
            return;
        }

        Vec3 missileVelMps = velocity.scale(20.0);
        Vec3 relVel = targetVelMps.subtract(missileVelMps);
        double closing = -relVel.dot(losDir);
        Vec3 omega = los.cross(relVel).scale(1.0 / Math.max(dist * dist, 1.0));
        Vec3 accelMps2 = omega.cross(losDir).scale(NAV_GAIN * Math.max(closing, 0.0));

        // 增强比例导引：叠加目标横向加速度，抑制目标持续机动造成的偏差。
        Vec3 targetAccelPerp = measuredTargetAcceleration.subtract(
                losDir.scale(measuredTargetAcceleration.dot(losDir)));
        accelMps2 = accelMps2.add(targetAccelPerp.scale(NAV_GAIN * 0.5));

        // 发射初始控制权限逐渐建立，避免刚离架瞬间满舵。
        double authority = Math.min(1.0, elapsed / CONTROL_RAMP_SECONDS);
        double maxAccel = spec.lateralAcceleration() * authority;
        if (accelMps2.length() > maxAccel && accelMps2.lengthSqr() > 1e-6) {
            accelMps2 = accelMps2.normalize().scale(maxAccel);
        }

        // 一阶舵机/弹体响应延迟。
        double dt = 1.0 / 20.0;
        double alpha = dt / (SERVO_TAU + dt);
        servoAcceleration = servoAcceleration.add(
                accelMps2.subtract(servoAcceleration).scale(alpha));

        // m/s² -> blocks/tick²（1 block/tick = 20 m/s，1 tick = 1/20 s）
        Vec3 newVelocity = velocity.add(servoAcceleration.scale(1.0 / 400.0));

        double currentMass = spec.massAt(elapsed);
        // 动力段/滑翔段速度
        if (spec.isBoosting(elapsed)) {
            speedMps += spec.boostAcceleration(elapsed) / 20.0;
        } else {
            speedMps -= spec.dragDeceleration(speedMps, currentMass) / 20.0;
            speedMps = Math.min(speedMps, spec.endSpeed);
        }
        speedMps = Math.max(0, speedMps);
        // 大过载转弯消耗动能，体现 4/8 点方向能量耗弹。
        double inducedDrag = servoAcceleration.length() / Math.max(speedMps, 1.0)
                * 0.4 * dt;
        speedMps = Math.max(0.0, speedMps - inducedDrag);
        // 爬升需要付出势能，避免 loft 免费获得高度。
        speedMps = Math.max(0.0, speedMps
                - 9.81 * (newVelocity.y * 20.0) / Math.max(speedMps, 1.0) * dt);

        if (newVelocity.lengthSqr() > 1e-8) {
            newVelocity = newVelocity.normalize().scale(speedMps / 20.0);
        }
        setDeltaMovement(newVelocity);
    }

    private static Vec3 slewTowards(Vec3 current, Vec3 desired, double maxRadians) {
        double dot = clamp(current.dot(desired), -1.0, 1.0);
        double angle = Math.acos(dot);
        if (angle <= maxRadians || angle < 1.0E-6) return desired;
        Vec3 axis = current.cross(desired);
        if (axis.lengthSqr() < 1.0E-8) {
            axis = current.cross(new Vec3(0, 1, 0));
            if (axis.lengthSqr() < 1.0E-8) axis = current.cross(new Vec3(1, 0, 0));
        }
        axis = axis.normalize();
        return current.scale(Math.cos(maxRadians))
                .add(axis.cross(current).scale(Math.sin(maxRadians)))
                .normalize();
    }

    /**
     * ARH 导引头开机：目标进入导引头作用距离即 pitbull；
     * 若目标航迹已丢失，则飞满开机距离后开机搜索。
     */
    protected void updatePitbullState() {
        if (pitbullActive || spec.guidance != MissileSpec.Guidance.ARH) return;
        if (age / 20.0 < spec.ignitionDelay()) return;

        double range = Double.MAX_VALUE;
        Entity target = getTarget();
        if (target != null && target.isAlive()) {
            range = position().distanceTo(target.getBoundingBox().getCenter());
        } else if (lastKnownTargetPos != null) {
            range = position().distanceTo(lastKnownTargetPos);
        }
        if (range <= spec.seekerActivationRange
                || distanceFlown >= spec.seekerActivationRange) {
            pitbullActive = true;
        }
    }

    /** 中段数据链更新：载机锁定/TWS/BVR 目标变化时切换导弹目标。 */
    protected void updateDatalinkTarget() {
        if (level().isClientSide || spec.guidance == MissileSpec.Guidance.IR) return;
        if (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive) return;
        if (!(getOwner() instanceof ServerPlayer player)) return;
        ServerRadarState.LockState state = ServerRadarState.get(player);
        if (state == null) return;
        if (!(level() instanceof ServerLevel serverLevel)) return;
        if (state.targetId() < 0) {
            // BVR 远程锁定：ARH 专用数据链。
            if (spec.guidance != MissileSpec.Guidance.ARH) return;
            UUID bvr = state.bvrTargetUuid();
            if (bvr == null || bvr.equals(targetId)) return;
            Entity next = serverLevel.getEntity(bvr);
            if (next == null || !next.isAlive()) return;
            setTarget(next);
            return;
        }
        Entity next = serverLevel.getEntity(state.targetId());
        if (next == null || !next.isAlive()) return;
        if (spec.guidance == MissileSpec.Guidance.SARH
                && !ServerRadarState.isLocked(player, next.getId())) return;
        if (targetId == null || !targetId.equals(next.getUUID())) {
            setTarget(next);
        }
    }

    /** 生成带延迟/噪声/丢帧的数据链测量；IR 和 ARH 末段直接使用导引头测量。 */
    protected void updateDatalinkMeasurement(@Nullable Entity target) {
        if (level().isClientSide || target == null || !target.isAlive()) return;

        if (spec.guidance == MissileSpec.Guidance.IR
                || (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive)) {
            trackQuality = 1.0f;
            recordMeasurement(target.getBoundingBox().getCenter(),
                    target.getDeltaMovement().scale(20.0));
            return;
        }

        if (!datalinkValid()) return;
        if (datalinkUpdateCooldown-- > 0) return;
        datalinkUpdateCooldown = DATALINK_UPDATE_TICKS;
        if (!(getOwner() instanceof ServerPlayer player)) return;
        ServerRadarState.LockState state = ServerRadarState.get(player);
        if (state == null) return;
        UUID bvr = state.bvrTargetUuid();
        boolean bvrMatch = state.targetId() < 0 && bvr != null && bvr.equals(targetId);
        if (!bvrMatch && state.targetId() != target.getId()) return;
        trackQuality = bvrMatch ? Math.max(0.55f, state.trackQuality())
                : state.trackQuality();

        // 低质量航迹有概率丢帧；丢帧期间导弹继续使用旧测量做 IOG。
        if (!testMissile && level().random.nextFloat() < (1.0f - trackQuality) * 0.10f) {
            return;
        }

        double distance = position().distanceTo(target.getBoundingBox().getCenter());
        double sigma = (1.0f - trackQuality) * Math.min(1.5, 0.2 + distance * 0.008);
        Vec3 posNoise = new Vec3(level().random.nextGaussian() * sigma,
                level().random.nextGaussian() * sigma,
                level().random.nextGaussian() * sigma);
        double velSigma = (1.0f - trackQuality) * 2.0;
        Vec3 velNoise = new Vec3(level().random.nextGaussian() * velSigma,
                level().random.nextGaussian() * velSigma,
                level().random.nextGaussian() * velSigma);
        recordMeasurement(target.getBoundingBox().getCenter().add(posNoise),
                target.getDeltaMovement().scale(20.0).add(velNoise));
    }

    private void recordMeasurement(Vec3 pos, Vec3 vel) {
        if (measuredTargetTick >= 0) {
            double dt = Math.max(0.05, (age - measuredTargetTick) / 20.0);
            Vec3 accel = vel.subtract(measuredTargetVelocity).scale(1.0 / dt);
            measuredTargetAcceleration = measuredTargetAcceleration
                    .add(accel.subtract(measuredTargetAcceleration).scale(0.35));
        }
        measuredTargetPos = pos;
        measuredTargetVelocity = vel;
        measuredTargetTick = age;
    }

    /** 记录目标最后已知位置；数据链失效后按 IOG 继续飞行。 */
    protected void updateTargetTrack(@Nullable Entity target) {
        if (target == null || !target.isAlive()) return;
        boolean canTrack = spec.guidance == MissileSpec.Guidance.IR
                || datalinkValid()
                || (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive);
        if (!canTrack) return;
        lastKnownTargetPos = target.getBoundingBox().getCenter();
        lastKnownTargetVelocity = target.getDeltaMovement().scale(20.0);
        lastKnownTargetTick = age;
    }

    @Nullable
    protected Vec3 guidancePosition(@Nullable Entity target) {
        // 导引头直接测量：IR 全程、ARH 末段。
        if (target != null && target.isAlive()
                && (spec.guidance == MissileSpec.Guidance.IR
                || (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive))) {
            return target.getBoundingBox().getCenter();
        }
        // 雷达中段使用带噪声/延迟的数据链测量，而不是精确实体位置。
        if (measuredTargetPos != null) {
            double seconds = Math.max(0, age - measuredTargetTick) / 20.0;
            return measuredTargetPos
                    .add(measuredTargetVelocity.scale(seconds))
                    .add(measuredTargetAcceleration.scale(0.5 * seconds * seconds));
        }
        if (target != null && target.isAlive() && datalinkValid()) {
            return target.getBoundingBox().getCenter();
        }
        if (lastKnownTargetPos != null) {
            double seconds = Math.max(0, age - lastKnownTargetTick) / 20.0;
            return lastKnownTargetPos.add(lastKnownTargetVelocity.scale(seconds));
        }
        return null;
    }

    protected Vec3 guidanceVelocity(@Nullable Entity target) {
        if (target != null && target.isAlive()
                && (spec.guidance == MissileSpec.Guidance.IR
                || (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive))) {
            return target.getDeltaMovement().scale(20.0);
        }
        if (measuredTargetPos != null) {
            return measuredTargetVelocity;
        }
        if (target != null && target.isAlive() && datalinkValid()) {
            return target.getDeltaMovement().scale(20.0);
        }
        return lastKnownTargetVelocity;
    }

    /** ARH/SARH 中段 loft：远距离先爬升，终端取消。IR 不 loft。 */
    private Vec3 loftAimPoint(Vec3 targetPos, Vec3 pos) {
        if (spec.guidance == MissileSpec.Guidance.IR) return targetPos;
        double dist = targetPos.distanceTo(pos);
        if (dist < 100.0 || age / 20.0 < spec.ignitionDelay() + 0.35) return targetPos;
        if (spec.guidance == MissileSpec.Guidance.ARH && pitbullActive) {
            return targetPos;
        }
        double loft = Math.min(70.0, dist * 0.12);
        if (targetPos.y > pos.y + 25.0) loft *= 0.35;
        return targetPos.add(0.0, loft, 0.0);
    }

    /** 高速段与其他导弹的扫掠拦截；敌我导弹都可被近炸/碰撞引爆。 */
    protected void checkMissileIntercept(Vec3 previous, Vec3 pos) {
        if (age <= 2) return;
        for (MissileArrow other : level().getEntitiesOfClass(
                MissileArrow.class, getBoundingBox().inflate(8.0))) {
            if (other == this || other.isRemoved() || other.detonating) continue;
            if (!canIntercept(other)) continue;
            Vec3 otherPrevious = other.lastPos == null ? other.position() : other.lastPos;
            double fuse = Math.max(2.2, spec.proximityFuseRadius);
            if (segmentDistance(previous, pos, otherPrevious, other.position()) <= fuse) {
                intercept(other);
                return;
            }
        }
    }

    protected boolean canIntercept(MissileArrow other) {
        if (testMissile || other.testMissile) return true;
        Entity a = getOwner();
        Entity b = other.getOwner();
        return a == null || b == null || !a.getUUID().equals(b.getUUID());
    }

    protected void intercept(MissileArrow other) {
        if (detonating) return;
        detonate(other);
    }

    private static double segmentDistance(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1) {
        Vec3 u = a1.subtract(a0);
        Vec3 v = b1.subtract(b0);
        Vec3 w = a0.subtract(b0);
        double a = u.dot(u), b = u.dot(v), c = v.dot(v), d = u.dot(w), e = v.dot(w);
        double denom = a * c - b * b;
        double sc, tc;
        if (denom < 1.0E-9) {
            sc = 0.0;
            tc = (b > c ? d / b : e / c);
        } else {
            sc = (b * e - c * d) / denom;
            tc = (a * e - b * d) / denom;
        }
        sc = clamp(sc, 0.0, 1.0);
        tc = clamp(tc, 0.0, 1.0);
        Vec3 p = a0.add(u.scale(sc));
        Vec3 q = b0.add(v.scale(tc));
        return p.distanceTo(q);
    }

    /**
     * 引爆（近炸/触地）。
     * @param target 触发近炸的目标（可为 null，表示触地引爆）
     */
    protected void detonate(@Nullable Entity target) {
        if (level().isClientSide) return;
        if (detonating) return;
        detonating = true;
        if (!level().isClientSide) {
            if (target instanceof MissileArrow other && other != this && !other.detonating) {
                other.detonate(null);
            }
            if (!testMissile) {
                if (target instanceof LivingEntity living) {
                    living.hurt(level().damageSources().explosion(this, getOwner()),
                            EXPLOSION_DAMAGE);
                }
                level().explode(this,
                        level().damageSources().explosion(this, getOwner()),
                        new net.minecraft.world.level.ExplosionDamageCalculator() {
                            @Override
                            public boolean shouldDamageEntity(
                                    net.minecraft.world.level.Explosion explosion,
                                    Entity entity) {
                                return false;
                            }
                        },
                        getX(), getY(), getZ(), EXPLOSION_RADIUS, false,
                        Level.ExplosionInteraction.NONE);
            }
            if (level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.FIREWORK,
                        getX(), getY(), getZ(), 18, 1.2, 1.2, 1.2, 0.08);
            }
            destroyFragileBlocks();
        }
        discard();
    }

    /** TNT 风格爆炸只破坏极脆弱方块，不破坏普通地形。 */
    private void destroyFragileBlocks() {
        BlockPos center = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-4, -4, -4), center.offset(4, 4, 4))) {
            BlockState state = level().getBlockState(pos);
            if (state.is(Blocks.SLIME_BLOCK)
                    || state.is(Blocks.HONEY_BLOCK)
                    || state.is(Blocks.TNT)) {
                level().destroyBlock(pos, false, this);
            }
        }
    }

    /** 爆炸伤害 */
    private static final float EXPLOSION_DAMAGE = 25.0F;
    /** 爆炸半径 */
    private static final float EXPLOSION_RADIUS = 1.5F;

    /** 低于该速度（m/s）视为抛锚/悬停异常。 */
    private static final double STALL_SPEED_MPS = 15.0;
    /** 异常低速持续 tick 数，超过即引爆。 */
    private static final int SLOW_TICKS_BEFORE_DETONATE = 40;

    @Override
    protected ItemStack getDefaultPickupItem() {
        // AbstractArrow 在存档时会把 pickupItemStack 写入 NBT；不能用空 ItemStack，
        // 否则区块保存会抛 "Cannot encode empty ItemStack"。导弹本身禁止拾取。
        return new ItemStack(net.minecraft.world.item.Items.ARROW);
    }

    private static final double NAV_GAIN = 4.5;

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
