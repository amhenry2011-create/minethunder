package com.minethunder.plane;

import com.minethunder.Registration;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 简单飞机（Simple Planes）移植核心实体：
 * 具备微软模拟飞行（MSFS）级冷舱启动流程（电瓶/燃油泵/磁电机/起动机点火）与六自由度空气动力学，
 * 原生接入 MineThunder 雷达（AIR/GROUND/BVR）、RWR 告警、动态红外热源与导弹发射初速叠加。
 */
public class PlaneEntity extends Entity {
    // 同步字段
    private static final EntityDataAccessor<Integer> DATA_PLANE_TYPE =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ENGINE_TYPE =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_MASTER_BATTERY =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_FUEL_PUMP =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_MAGNETO =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_STARTER_HOLD =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ENGINE_RUNNING =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_RPM =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_EGT =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_THROTTLE =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_PITCH_DEG =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_YAW_DEG =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ROLL_DEG =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_FLAP_STAGE =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_GEAR_DOWN =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_GEAR_PROGRESS =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_PARKING_BRAKE =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_ELEVATOR_TRIM =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AOA_DEG =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_G_LOAD =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_STALLING =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_VEL_X =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_VEL_Y =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_VEL_Z =
            SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);

    // 渲染平滑插值与舵面偏角
    public float prevPitchDeg = 0.0f;
    public float prevYawDeg = 0.0f;
    public float prevRollDeg = 0.0f;
    public float prevGearProgress = 1.0f;
    public float propAngleDeg = 0.0f;
    public float prevPropAngleDeg = 0.0f;

    // 实时控制杆输入 (-1..+1)
    private float inputPitch = 0.0f;
    private float inputRoll = 0.0f;
    private float inputYaw = 0.0f;
    private boolean inputWheelBrake = false;
    private float pitchRateDegPerSec = 0.0f;
    private float rollRateDegPerSec = 0.0f;
    private float yawRateDegPerSec = 0.0f;

    // 服务端插值目标（无乘客时平滑网络同步）
    private int lerpSteps = 0;
    private double lerpX, lerpY, lerpZ;

    // 自动驾驶靶机模式（供 /minethunder test plane 使用）
    private boolean autopilotOrbit = false;
    private Vec3 orbitCenter = Vec3.ZERO;
    private double orbitRadius = 140.0;
    private double orbitTargetY = 120.0;
    private double orbitSpeedMps = 32.0;

    // 物理常量
    private static final double GRAVITY_MPS2 = 9.81;
    private static final float AMBIENT_TEMP_C = 18.0f;

    public PlaneEntity(EntityType<? extends PlaneEntity> type, Level level) {
        this(type, level, PlaneTypeSpec.SMALL_PLANE);
    }

    public PlaneEntity(EntityType<? extends PlaneEntity> type, Level level, PlaneTypeSpec spec) {
        super(type, level);
        this.blocksBuilding = true;
        setPlaneType(spec);
        setEngineType(spec.defaultEngine());
        this.entityData.set(DATA_HEALTH, spec.maxHealth());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PLANE_TYPE, PlaneTypeSpec.SMALL_PLANE.ordinal());
        builder.define(DATA_ENGINE_TYPE, PlaneEngineType.LIQUID.ordinal());
        builder.define(DATA_MASTER_BATTERY, false);
        builder.define(DATA_FUEL_PUMP, false);
        builder.define(DATA_MAGNETO, 0); // 0=OFF, 1=L, 2=R, 3=BOTH
        builder.define(DATA_STARTER_HOLD, false);
        builder.define(DATA_ENGINE_RUNNING, false);
        builder.define(DATA_RPM, 0.0f);
        builder.define(DATA_EGT, AMBIENT_TEMP_C);
        builder.define(DATA_THROTTLE, 0.0f);
        builder.define(DATA_PITCH_DEG, 0.0f);
        builder.define(DATA_YAW_DEG, 0.0f);
        builder.define(DATA_ROLL_DEG, 0.0f);
        builder.define(DATA_FLAP_STAGE, 0);
        builder.define(DATA_GEAR_DOWN, true);
        builder.define(DATA_GEAR_PROGRESS, 1.0f);
        builder.define(DATA_PARKING_BRAKE, true);
        builder.define(DATA_ELEVATOR_TRIM, 0.0f);
        builder.define(DATA_AOA_DEG, 0.0f);
        builder.define(DATA_G_LOAD, 1.0f);
        builder.define(DATA_STALLING, false);
        builder.define(DATA_HEALTH, 80.0f);
        builder.define(DATA_VEL_X, 0.0f);
        builder.define(DATA_VEL_Y, 0.0f);
        builder.define(DATA_VEL_Z, 0.0f);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_PLANE_TYPE.equals(key)) {
            this.refreshDimensions();
        }
    }

    public PlaneTypeSpec getPlaneType() {
        return PlaneTypeSpec.byOrdinal(this.entityData.get(DATA_PLANE_TYPE));
    }

    public void setPlaneType(PlaneTypeSpec spec) {
        this.entityData.set(DATA_PLANE_TYPE, spec.ordinal());
        this.refreshDimensions();
    }

    public PlaneEngineType getEngineType() {
        return PlaneEngineType.byOrdinal(this.entityData.get(DATA_ENGINE_TYPE));
    }

    public void setEngineType(PlaneEngineType engineType) {
        this.entityData.set(DATA_ENGINE_TYPE, engineType.ordinal());
    }

    public boolean isMasterBatteryOn() {
        return this.entityData.get(DATA_MASTER_BATTERY);
    }

    public void setMasterBattery(boolean on) {
        this.entityData.set(DATA_MASTER_BATTERY, on);
    }

    public boolean isFuelPumpOn() {
        return this.entityData.get(DATA_FUEL_PUMP);
    }

    public void setFuelPump(boolean on) {
        this.entityData.set(DATA_FUEL_PUMP, on);
    }

    public int getMagnetoState() {
        return this.entityData.get(DATA_MAGNETO);
    }

    public String getMagnetoLabel() {
        return switch (getMagnetoState()) {
            case 1 -> "LEFT";
            case 2 -> "RIGHT";
            case 3 -> "BOTH";
            default -> "OFF";
        };
    }

    public void setMagnetoState(int state) {
        this.entityData.set(DATA_MAGNETO, Mth.clamp(state, 0, 3));
    }

    public boolean isStarterHolding() {
        return this.entityData.get(DATA_STARTER_HOLD);
    }

    public void setStarterHolding(boolean holding) {
        this.entityData.set(DATA_STARTER_HOLD, holding);
    }

    public boolean isEngineRunning() {
        return this.entityData.get(DATA_ENGINE_RUNNING);
    }

    public void setEngineRunning(boolean running) {
        this.entityData.set(DATA_ENGINE_RUNNING, running);
    }

    public float getRpmNormalized() {
        return this.entityData.get(DATA_RPM);
    }

    public float getEgtCelsius() {
        return this.entityData.get(DATA_EGT);
    }

    public float getThrottle() {
        return this.entityData.get(DATA_THROTTLE);
    }

    public void setThrottle(float value) {
        float max = getEngineType().afterburnerCapable() ? 1.10f : 1.00f;
        this.entityData.set(DATA_THROTTLE, Mth.clamp(value, 0.0f, max));
    }

    public boolean isAfterburnerActive() {
        return isEngineRunning()
                && getEngineType().afterburnerCapable()
                && getThrottle() > 1.005f
                && getRpmNormalized() > 0.92f;
    }

    public float getPitchDeg() {
        return this.entityData.get(DATA_PITCH_DEG);
    }

    public void setPitchDeg(float pitchDeg) {
        float clamped = Mth.clamp(pitchDeg, -88.0f, 88.0f);
        this.entityData.set(DATA_PITCH_DEG, clamped);
        this.setXRot(clamped);
    }

    public float getYawDeg() {
        return this.entityData.get(DATA_YAW_DEG);
    }

    public void setYawDeg(float yawDeg) {
        float wrapped = Mth.wrapDegrees(yawDeg);
        this.entityData.set(DATA_YAW_DEG, wrapped);
        this.setYRot(wrapped);
    }

    public float getRollDeg() {
        return this.entityData.get(DATA_ROLL_DEG);
    }

    public void setRollDeg(float rollDeg) {
        this.entityData.set(DATA_ROLL_DEG, Mth.wrapDegrees(rollDeg));
    }

    public int getFlapStage() {
        return this.entityData.get(DATA_FLAP_STAGE);
    }

    public int getFlapDegrees() {
        return switch (getFlapStage()) {
            case 1 -> 15;
            case 2 -> 40;
            default -> 0;
        };
    }

    public void setFlapStage(int stage) {
        this.entityData.set(DATA_FLAP_STAGE, Mth.clamp(stage, 0, 2));
    }

    public boolean isGearDown() {
        return this.entityData.get(DATA_GEAR_DOWN);
    }

    public void setGearDown(boolean down) {
        this.entityData.set(DATA_GEAR_DOWN, down);
    }

    public float getGearProgress() {
        return this.entityData.get(DATA_GEAR_PROGRESS);
    }

    public boolean isParkingBrakeOn() {
        return this.entityData.get(DATA_PARKING_BRAKE);
    }

    public void setParkingBrake(boolean on) {
        this.entityData.set(DATA_PARKING_BRAKE, on);
    }

    public float getElevatorTrimDeg() {
        return this.entityData.get(DATA_ELEVATOR_TRIM);
    }

    public void setElevatorTrimDeg(float trimDeg) {
        this.entityData.set(DATA_ELEVATOR_TRIM, Mth.clamp(trimDeg, -10.0f, 10.0f));
    }

    public float getAoaDeg() {
        return this.entityData.get(DATA_AOA_DEG);
    }

    public float getGLoad() {
        return this.entityData.get(DATA_G_LOAD);
    }

    public boolean isStalling() {
        return this.entityData.get(DATA_STALLING);
    }

    public float getStructuralHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    public void setStructuralHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0f, getPlaneType().maxHealth()));
    }

    public float getInputPitch() {
        return inputPitch;
    }

    public float getInputRoll() {
        return inputRoll;
    }

    public float getInputYaw() {
        return inputYaw;
    }

    /** 指示空速 IAS (m/s) */
    public double getAirspeedMps() {
        return getDeltaMovement().length() * 20.0;
    }

    /** 离地无线电高度 AGL (m) */
    public double getRadarAltitudeAgl() {
        Vec3 start = position();
        Vec3 end = start.add(0.0, -256.0, 0.0);
        var hit = level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, this));
        if (hit.getType() == HitResult.Type.MISS) {
            return Math.max(0.0, getY() - level().getMinBuildHeight());
        }
        return Math.max(0.0, getY() - hit.getLocation().y);
    }

    /** 当前构型下的失速速度 Vs (m/s) */
    public double getCurrentStallSpeedMps() {
        double base = getPlaneType().cleanStallSpeedMps();
        return switch (getFlapStage()) {
            case 1 -> base * 0.86;
            case 2 -> base * 0.74;
            default -> base;
        };
    }

    /**
     * 供 RadarCore 判断该飞机属于空中目标（AIR）还是跑道地面目标（GROUND）。
     */
    public boolean isAirborneTarget() {
        if (!onGround() && getRadarAltitudeAgl() > 1.2) {
            return true;
        }
        return getAirspeedMps() > 12.0;
    }

    /**
     * 供 IrHeat 计算该飞机的实时红外热特征：
     * - 冷舱且已冷却：0 热量（不可被红外弹锁定）
     * - 慢车/常规推力：110 ~ 260 热量
     * - 涡扇加力燃烧（WEP/AB）：480 热量（超越常规热焰弹 200，需先收油门退加力再放热焰弹才能骗锁）
     */
    public double getIrSignatureHeat() {
        float egt = getEgtCelsius();
        if (!isEngineRunning() && egt < 85.0f) {
            return 0.0;
        }
        if (isAfterburnerActive()) {
            return 480.0;
        }
        if (isEngineRunning()) {
            double normThrottle = Math.min(1.0, getThrottle());
            double idleHeat = 110.0;
            double milHeat = getEngineType().milIrHeat();
            return idleHeat + (milHeat - idleHeat) * normThrottle;
        }
        // 刚关车但余温尚存
        return Math.max(0.0, (egt - 65.0) * 0.32);
    }

    /**
     * 配置为自动巡航盘旋靶机（供 /minethunder test plane 调用）。
     */
    public void enableAutopilotOrbit(Vec3 center, double radius, double targetY, double speedMps) {
        this.autopilotOrbit = true;
        this.orbitCenter = center;
        this.orbitRadius = Math.max(40.0, radius);
        this.orbitTargetY = targetY;
        this.orbitSpeedMps = Mth.clamp(speedMps, 18.0, 55.0);
        // 自动完成热舱开机与收起落架
        setMasterBattery(true);
        setFuelPump(true);
        setMagnetoState(3);
        setEngineRunning(true);
        this.entityData.set(DATA_RPM, 0.85f);
        this.entityData.set(DATA_EGT, getEngineType().nominalMaxEgt() * 0.78f);
        setThrottle(0.78f);
        setParkingBrake(false);
        setGearDown(false);
        this.entityData.set(DATA_GEAR_PROGRESS, 0.0f);
        setFlapStage(0);
    }

    /**
     * 配置为指定发动机与推力档位的自动盘旋测试靶机（供 /minethunder test plane 调用）。
     */
    public void configureRunningForTest(PlaneEngineType engineType, float throttle, boolean afterburner) {
        configureRunningForTest(engineType, throttle, afterburner, position(), 60.0);
    }

    public void configureRunningForTest(PlaneEngineType engineType, float throttle,
                                        boolean afterburner, Vec3 center, double radius) {
        setEngineType(engineType);
        enableAutopilotOrbit(center, radius, getY(), afterburner ? 36.0 : 24.0);
        setThrottle(afterburner ? 1.10f : throttle);
        if (afterburner) {
            this.entityData.set(DATA_RPM, 1.08f);
            this.entityData.set(DATA_EGT, engineType.nominalMaxEgt() + 150.0f);
        }
    }

    /**
     * 一键热舱启动或冷舱关车（供测试或快捷操作调用）。
     */
    public void quickHotStart() {
        setMasterBattery(true);
        setFuelPump(true);
        setMagnetoState(3);
        setEngineRunning(true);
        this.entityData.set(DATA_RPM, getEngineType().idleRpm());
        this.entityData.set(DATA_EGT, 490.0f);
        if (getThrottle() < 0.08f) {
            setThrottle(0.10f);
        }
    }

    /**
     * 接收客户端发来的操纵面与冷舱开关指令。
     */
    public void applyPilotPacket(Player pilot, float pitchIn, float rollIn, float yawIn,
                                 float throttleDelta, boolean wheelBrakeHold,
                                 boolean starterHold, int actionMask) {
        if (!isPilot(pilot)) return;

        this.inputPitch = Mth.clamp(pitchIn, -1.0f, 1.0f);
        this.inputRoll = Mth.clamp(rollIn, -1.0f, 1.0f);
        this.inputYaw = Mth.clamp(yawIn, -1.0f, 1.0f);
        this.inputWheelBrake = wheelBrakeHold;
        setStarterHolding(starterHold);

        if (Math.abs(throttleDelta) > 1.0E-4f) {
            setThrottle(getThrottle() + throttleDelta);
            if (throttleDelta > 0.0f && isEngineRunning() && isParkingBrakeOn() && getThrottle() >= 0.20f) {
                setParkingBrake(false);
                notifyPilot(pilot, "推油门滑跑：驻车制动 (PARK BRK) 已自动解除");
            }
        }

        // 解码离散开关位
        if ((actionMask & (1 << 0)) != 0) {
            boolean next = !isMasterBatteryOn();
            setMasterBattery(next);
            notifyPilot(pilot, "主电瓶总开关 (BAT): " + (next ? "ON (28V DC 已通电)" : "OFF (断电)"));
        }
        if ((actionMask & (1 << 1)) != 0) {
            boolean next = !isFuelPumpOn();
            setFuelPump(next);
            notifyPilot(pilot, "主燃油增压泵与供油阀 (FUEL): " + (next ? "OPEN (已供油)" : "CUTOFF (断油)"));
        }
        if ((actionMask & (1 << 2)) != 0) {
            int next = switch (getMagnetoState()) {
                case 0 -> 3; // OFF -> BOTH（冷舱启动按一次即可到位）
                case 3 -> 1; // BOTH -> LEFT
                case 1 -> 2; // LEFT -> RIGHT
                default -> 0; // RIGHT -> OFF
            };
            setMagnetoState(next);
            notifyPilot(pilot, "磁电机/点火电门 (MAG): " + getMagnetoLabel());
        }
        if ((actionMask & (1 << 3)) != 0) {
            // 襟翼收一档 (U)
            int next = Math.max(0, getFlapStage() - 1);
            setFlapStage(next);
            notifyPilot(pilot, "襟翼 (FLAPS): " + getFlapDegrees() + "°");
        }
        if ((actionMask & (1 << 4)) != 0) {
            // 襟翼放一档 (V)
            int next = Math.min(2, getFlapStage() + 1);
            setFlapStage(next);
            notifyPilot(pilot, "襟翼 (FLAPS): " + getFlapDegrees() + "°");
        }
        if ((actionMask & (1 << 5)) != 0) {
            // 起落架切换 (K)
            if (onGround() && isGearDown()) {
                notifyPilot(pilot, "地面滑行中安全锁止：禁止收起起落架！");
            } else {
                boolean next = !isGearDown();
                setGearDown(next);
                notifyPilot(pilot, "起落架 (GEAR): " + (next ? "放下 (DOWN)" : "收起 (UP)"));
            }
        }
        if ((actionMask & (1 << 6)) != 0) {
            // 驻车刹车切换 (P)
            boolean next = !isParkingBrakeOn();
            setParkingBrake(next);
            notifyPilot(pilot, "驻车制动 (PARK BRK): " + (next ? "SET (已锁死)" : "RELEASED (已解除)"));
        }
        if ((actionMask & (1 << 7)) != 0) {
            // 低头配平 [
            setElevatorTrimDeg(getElevatorTrimDeg() - 0.5f);
        }
        if ((actionMask & (1 << 8)) != 0) {
            // 抬头配平 ]
            setElevatorTrimDeg(getElevatorTrimDeg() + 0.5f);
        }
        if ((actionMask & (1 << 9)) != 0) {
            // 配平归中
            setElevatorTrimDeg(0.0f);
            notifyPilot(pilot, "俯仰配平 (TRIM) 已归零");
        }
    }

    private void notifyPilot(Player pilot, String message) {
        pilot.displayClientMessage(Component.literal(message), true);
    }

    @Override
    public void tick() {
        super.tick();

        this.prevPitchDeg = getPitchDeg();
        this.prevYawDeg = getYawDeg();
        this.prevRollDeg = getRollDeg();
        this.prevGearProgress = getGearProgress();
        this.prevPropAngleDeg = this.propAngleDeg;

        // 螺旋桨/风扇旋转角推进
        float rpm = getRpmNormalized();
        if (rpm > 0.005f) {
            this.propAngleDeg = (this.propAngleDeg + rpm * 58.0f) % 360.0f;
        }

        if (level().isClientSide) {
            this.xRotO = this.prevPitchDeg;
            this.yRotO = this.prevYawDeg;
            this.setXRot(getPitchDeg());
            this.setYRot(getYawDeg());
            setDeltaMovement(
                    this.entityData.get(DATA_VEL_X),
                    this.entityData.get(DATA_VEL_Y),
                    this.entityData.get(DATA_VEL_Z));
            if (lerpSteps > 0) {
                double nx = getX() + (lerpX - getX()) / lerpSteps;
                double ny = getY() + (lerpY - getY()) / lerpSteps;
                double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
                lerpSteps--;
                setPos(nx, ny, nz);
            }
            spawnClientParticles();
            return;
        }

        // 服务端权威物理与发动机状态机
        if (getPilot() == null && !autopilotOrbit) {
            this.inputPitch = 0.0f;
            this.inputRoll = 0.0f;
            this.inputYaw = 0.0f;
            this.inputWheelBrake = false;
            setStarterHolding(false);
        }

        if (autopilotOrbit && getPilot() == null) {
            updateAutopilotInputs();
        }

        updateGearHydraulics();
        updateEngineAndElectrical();
        updateAerodynamicsAndMotion();
    }

    private void updateGearHydraulics() {
        float target = isGearDown() ? 1.0f : 0.0f;
        float current = getGearProgress();
        if (Math.abs(target - current) > 1.0E-3f) {
            float step = 1.0f / 40.0f; // 2.0 秒完成收放
            float next = current < target
                    ? Math.min(target, current + step)
                    : Math.max(target, current - step);
            this.entityData.set(DATA_GEAR_PROGRESS, next);
        }
    }

    /**
     * MSFS 级冷舱发动机状态机：
     * - 熄火条件：燃油泵关闭 或 磁电机置于 OFF
     * - 点火条件：主电瓶 ON + 起动机长按 + 转速爬升 >= 22% + 燃油泵 ON + 磁电机 != OFF + 油门处于 0..40% 慢车门限内
     */
    private void updateEngineAndElectrical() {
        PlaneEngineType engine = getEngineType();
        boolean battery = isMasterBatteryOn();
        boolean fuel = isFuelPumpOn();
        int mag = getMagnetoState();
        boolean starter = battery && isStarterHolding();
        boolean running = isEngineRunning();
        float rpm = getRpmNormalized();
        float egt = getEgtCelsius();
        float throttle = getThrottle();

        // 断油或切断磁电机立刻导致发动机熄火（Flameout）
        if (running && (!fuel || mag == 0)) {
            running = false;
            setEngineRunning(false);
            level().playSound(null, blockPosition(), SoundEvents.FIRE_EXTINGUISH,
                    SoundSource.PLAYERS, 0.9F, 0.8F);
            if (getPilot() instanceof Player pilot) {
                notifyPilot(pilot, !fuel ? "发动机断油停车 (FUEL CUTOFF)" : "磁电机切断停车 (MAG OFF)");
            }
        }

        if (!running) {
            if (isStarterHolding() && !battery && tickCount % 20 == 0 && getPilot() instanceof Player pilot) {
                notifyPilot(pilot, "无法起动：请先按 [B] 开启机载主电瓶 (MASTER BAT)");
            }
            if (starter) {
                // 起动机带动曲轴/高压压气机转子升转
                float crankTarget = 0.25f;
                rpm = Mth.approach(rpm, crankTarget, 0.0085f);
                if (tickCount % 6 == 0) {
                    level().playSound(null, blockPosition(), SoundEvents.MINECART_RIDING,
                            SoundSource.PLAYERS, 0.5F, 0.65F + rpm * 1.2F);
                }
                if (tickCount % 20 == 0 && getPilot() instanceof Player pilot) {
                    if (!fuel) {
                        notifyPilot(pilot, "起动机空转：请按 [F] 开启燃油增压泵与供油阀");
                    } else if (mag == 0) {
                        notifyPilot(pilot, "起动机空转：请按 [G] 开启磁电机点火电门 (BOTH)");
                    }
                }
                // 满足全部冷舱点火条件且转速达 21% 时成功点火
                if (rpm >= 0.21f && fuel && mag > 0) {
                    if (throttle <= 0.45f) {
                        running = true;
                        setEngineRunning(true);
                        if (throttle < 0.05f) {
                            setThrottle(0.08f);
                        }
                        egt = Math.max(egt, 420.0f);
                        level().playSound(null, blockPosition(), SoundEvents.BLAZE_SHOOT,
                                SoundSource.PLAYERS, 1.0F, 1.15F);
                        if (getPilot() instanceof Player pilot) {
                            notifyPilot(pilot, "发动机点火成功 (ENGINE RUNNING - IDLE)");
                        }
                    } else if (tickCount % 20 == 0 && getPilot() instanceof Player pilot) {
                        notifyPilot(pilot, "油门过高导致富油淹缸！请将油门收至 40% 以下再点火");
                    }
                }
            } else {
                // 自转风车效应或自然停转
                float windmilling = (float) Mth.clamp(getAirspeedMps() / 160.0, 0.0, 0.14);
                rpm = Mth.approach(rpm, windmilling, 0.012f);
            }
            // 熄火冷却
            egt = Mth.approach(egt, AMBIENT_TEMP_C, 3.2f);
        } else {
            // 正常运行：计算目标转速与排气温度
            float magEfficiency = (mag == 3) ? 1.0f : 0.91f;
            float idle = engine.idleRpm();
            float targetRpm;
            if (throttle <= 1.0f) {
                targetRpm = (idle + (1.0f - idle) * throttle) * magEfficiency;
            } else {
                targetRpm = (1.0f + (throttle - 1.0f) * 1.1f) * magEfficiency;
            }
            float spoolRate = engine == PlaneEngineType.JET ? 0.022f : 0.035f;
            rpm = Mth.approach(rpm, targetRpm, spoolRate);

            float targetEgt = 360.0f + rpm * (engine.nominalMaxEgt() - 360.0f);
            if (isAfterburnerActive()) {
                targetEgt += 165.0f;
            }
            egt = Mth.approach(egt, targetEgt, 6.5f);

            // 发动机运转音效
            if (tickCount % 5 == 0) {
                float pitch = 0.65F + rpm * 1.15F;
                level().playSound(null, blockPosition(),
                        isAfterburnerActive() ? SoundEvents.GENERIC_EXPLODE.value() : SoundEvents.ELYTRA_FLYING,
                        SoundSource.PLAYERS,
                        isAfterburnerActive() ? 0.30F : 0.22F,
                        pitch);
            }
        }

        this.entityData.set(DATA_RPM, rpm);
        this.entityData.set(DATA_EGT, egt);
    }

    /**
     * 六自由度（6-DoF）真实空气动力学解算：
     * - 机体坐标系分解（Forward, Right, Up）
     * - 迎角 AoA (α) 与升力极曲线 C_L(α, Flaps)
     * - 诱导阻力 + 零升寄生阻力 + 起落架阻力 + 襟翼阻力
     * - 临界迎角失速抖振与自动低头恢复力矩
     * - 坡度转弯向心力与法向过载 G-Load
     */
    private void updateAerodynamicsAndMotion() {
        PlaneTypeSpec spec = getPlaneType();
        PlaneEngineType engine = getEngineType();
        double dt = 0.05; // 1 tick = 0.05s

        Vec3 velMps = getDeltaMovement().scale(20.0);
        double airspeed = velMps.length();

        float pitch = getPitchDeg();
        float yaw = getYawDeg();
        float roll = getRollDeg();

        // 1. 地面与空中控制权限（Control Authority）随动压变化
        double controlEffectiveness = Mth.clamp(airspeed / (spec.cleanStallSpeedMps() * 1.15), 0.18, 1.25);
        float trimNormalized = getElevatorTrimDeg() / 15.0f;
        float effectivePitchCmd = Mth.clamp(inputPitch + trimNormalized, -1.0f, 1.0f);

        float targetPitchRate = (float) (effectivePitchCmd * spec.maxPitchRateDegPerSec() * controlEffectiveness);
        float targetRollRate = (float) (inputRoll * spec.maxRollRateDegPerSec() * controlEffectiveness);
        float targetYawRate = (float) (inputYaw * spec.maxYawRateDegPerSec() * Math.max(0.35, controlEffectiveness));

        // 地面滑行时前轮转向增强，同时限制地面倾角与擦翼滚转
        if (onGround()) {
            double groundSteerFactor = Mth.clamp(1.2 - airspeed / 25.0, 0.25, 1.0);
            targetYawRate += (float) (inputYaw * 42.0 * groundSteerFactor);
            roll = Mth.approach(roll, 0.0f, 4.0f);
            if (airspeed < spec.cleanStallSpeedMps() * 0.65 && effectivePitchCmd > 0.0f) {
                // 空速未达抬前轮速度 Vr 时无法强行拉起机头
                targetPitchRate = Math.min(targetPitchRate, 4.0f);
            }
            if (pitch < -2.0f) {
                pitch = Mth.approach(pitch, 0.0f, 3.5f);
            }
        }

        // 2. 计算当前机体三轴正交基向量
        Basis basis = computeBasis(pitch, yaw, roll);

        // 3. 计算真实迎角 AoA (α)：速度矢量在机体纵对称面 (Forward-Up) 上的夹角
        float aoaDeg = 0.0f;
        if (airspeed > 1.5) {
            double vForward = velMps.dot(basis.forward);
            double vUp = velMps.dot(basis.up);
            aoaDeg = (float) Math.toDegrees(Math.atan2(-vUp, Math.max(0.1, vForward)));
            aoaDeg = Mth.clamp(aoaDeg, -45.0f, 45.0f);
        }
        this.entityData.set(DATA_AOA_DEG, aoaDeg);

        // 4. 失速判定与失速力矩（Stall Physics）
        float flapStage = getFlapStage();
        float criticalAoa = 15.5f + flapStage * 1.5f;
        boolean stalling = !onGround()
                && (Math.abs(aoaDeg) > criticalAoa
                || (airspeed < getCurrentStallSpeedMps() && getRadarAltitudeAgl() > 1.5));
        this.entityData.set(DATA_STALLING, stalling);

        if (stalling) {
            // 失速时升力突降、机头因气动中心后移自然下俯，并伴随非对称翼尖失速抖振
            targetPitchRate -= 32.0f;
            float buffetRoll = (float) (Math.sin(tickCount * 1.7) * 28.0);
            targetRollRate += buffetRoll;
        }

        // 5. 角速度一阶惯性响应与欧拉角更新
        this.pitchRateDegPerSec = Mth.approach(this.pitchRateDegPerSec, targetPitchRate, 14.0f);
        this.rollRateDegPerSec = Mth.approach(this.rollRateDegPerSec, targetRollRate, 22.0f);
        this.yawRateDegPerSec = Mth.approach(this.yawRateDegPerSec, targetYawRate, 10.0f);

        // 坡度转弯（Coordinated Banked Turn）：机翼滚转角产生水平偏航角速度
        if (!onGround() && airspeed > 6.0) {
            double bankRad = Math.toRadians(roll);
            double pitchRad = Math.toRadians(pitch);
            // 滚转时拉杆的分量会转化为水平转弯，同时重力分解产生自然协调转弯率
            double turnRateRad = (GRAVITY_MPS2 * Math.tan(Mth.clamp(bankRad, -1.35, 1.35)))
                    / Math.max(10.0, airspeed);
            float bankYawDegPerSec = (float) Mth.clamp(Math.toDegrees(turnRateRad), -55.0, 55.0);
            yaw += (this.yawRateDegPerSec + bankYawDegPerSec) * (float) dt;
            // 大坡度盘旋时拉杆同时贡献俯仰与航向变化
            pitch += (float) (this.pitchRateDegPerSec * Math.cos(bankRad) * dt);
        } else {
            yaw += this.yawRateDegPerSec * (float) dt;
            pitch += this.pitchRateDegPerSec * (float) dt;
        }
        roll += this.rollRateDegPerSec * (float) dt;

        // 机翼自动微弱正安定度（松杆且坡度较小时缓慢回平）
        if (!onGround() && Math.abs(inputRoll) < 0.02f && Math.abs(roll) < 12.0f) {
            roll = Mth.approach(roll, 0.0f, 0.45f);
        }

        setPitchDeg(pitch);
        setYawDeg(yaw);
        setRollDeg(roll);
        basis = computeBasis(getPitchDeg(), getYawDeg(), getRollDeg());

        // 6. 四力合成：推力 (Thrust) + 升力 (Lift) + 阻力 (Drag) + 重力 (Gravity)
        double rpm = getRpmNormalized();
        double thrustAccel = 0.0;
        if (isEngineRunning() && rpm > engine.idleRpm() * 0.8f) {
            double powerFrac = Math.max(0.0, (rpm - engine.idleRpm() * 0.75) / (1.0 - engine.idleRpm() * 0.75));
            thrustAccel = spec.baseThrustAccelMps2() * engine.thrustMultiplier() * powerFrac;
            if (isAfterburnerActive()) {
                thrustAccel *= 1.38;
            }
        }
        Vec3 thrustVec = basis.forward.scale(thrustAccel);

        // 升力计算：由空速平方与迎角升力系数 C_L(α) 决定
        double cl0 = 0.18 + flapStage * 0.22; // 零迎角基准升力 + 襟翼增升
        double clAlpha = 0.085 * aoaDeg;
        double cl = cl0 + clAlpha;
        if (stalling) {
            // 超过临界迎角后流场分离，升力系数跌落
            cl *= 0.36;
        }
        cl = Mth.clamp(cl, -1.2, 2.1);

        // 归一化动压因子：当空速等于干净构型巡航速度 (~1.35 * Vs) 时，1G 平飞升力恰好抵消重力
        double refSpeed = spec.cleanStallSpeedMps() * 1.22;
        double dynamicPressureNorm = (airspeed * airspeed) / Math.max(1.0, refSpeed * refSpeed);
        double liftAccelMag = GRAVITY_MPS2 * Math.min(dynamicPressureNorm, 4.2) * (cl / 0.52);

        // 额外拉杆法向机动升力（允许高速拉出高 G 机动）
        if (!onGround() && !stalling && airspeed > getCurrentStallSpeedMps()) {
            double speedRatio = Mth.clamp(airspeed / (spec.cleanStallSpeedMps() * 1.8), 0.0, 2.2);
            liftAccelMag += effectivePitchCmd * GRAVITY_MPS2 * (spec.maxPositiveG() - 1.0) * 0.55 * speedRatio;
        }
        Vec3 liftVec = basis.up.scale(liftAccelMag);

        // 阻力计算：寄生阻力 + 起落架阻力 + 襟翼阻力 + 迎角诱导阻力
        double cd0 = 0.016;
        double cdGear = getGearProgress() * 0.024;
        double cdFlap = flapStage * 0.018;
        double cdInduced = 0.025 * cl * cl + (stalling ? 0.085 : 0.0);
        double totalCd = cd0 + cdGear + cdFlap + cdInduced;
        double dragDecel = totalCd * airspeed * airspeed * 0.35;
        Vec3 dragDir = airspeed > 0.05 ? velMps.normalize().scale(-1.0) : Vec3.ZERO;
        Vec3 dragVec = dragDir.scale(dragDecel);

        // 气动侧向稳定与速度矢量对齐（消除过多侧滑，但保留真实高迎角沉降惯性）
        Vec3 newVelMps = velMps.add(thrustVec.add(liftVec).add(dragVec).scale(dt));
        if (!onGround() && newVelMps.length() > 2.0) {
            double speed = newVelMps.length();
            double alignFactor = stalling ? 0.04 : 0.16;
            Vec3 blendedDir = newVelMps.normalize().lerp(basis.forward, alignFactor).normalize();
            newVelMps = blendedDir.scale(speed);
        }

        // 重力
        newVelMps = newVelMps.add(0.0, -GRAVITY_MPS2 * dt, 0.0);

        // 7. 地面滑跑、驻车制动与机轮摩擦
        if (onGround()) {
            if (newVelMps.y < 0.0) {
                // 保留微弱下压分量使 Entity.move 持续判定 verticalCollision && movement.y < 0，避免 onGround 奇偶帧抖动
                newVelMps = new Vec3(newVelMps.x, -0.15, newVelMps.z);
            }
            // 将水平速度约束向机头方向（机轮侧向防滑）
            Vec3 horizForward = new Vec3(basis.forward.x, 0.0, basis.forward.z);
            if (horizForward.lengthSqr() > 1.0E-4) {
                horizForward = horizForward.normalize();
                double forwardSpeed = newVelMps.x * horizForward.x + newVelMps.z * horizForward.z;
                Vec3 alignedHoriz = horizForward.scale(Math.max(0.0, forwardSpeed));
                newVelMps = new Vec3(
                        Mth.lerp(0.55, newVelMps.x, alignedHoriz.x),
                        newVelMps.y,
                        Mth.lerp(0.55, newVelMps.z, alignedHoriz.z)
                );
            }

            // 驻车刹车或点刹或未放起落架机腹擦地
            double brakeDecel = 0.015; // 正常机轮滚动阻力
            if (isParkingBrakeOn()) {
                brakeDecel = 0.35;
            } else if (inputWheelBrake) {
                brakeDecel = 0.22;
            }
            if (getGearProgress() < 0.5f) {
                // 未放起落架机腹着陆摩擦 + 擦地损伤
                brakeDecel = 0.45;
                if (airspeed > 8.0) {
                    hurt(level().damageSources().flyIntoWall(), (float) (airspeed * 0.08));
                }
            }
            double hSpeed = Math.hypot(newVelMps.x, newVelMps.z);
            if (hSpeed > 0.0) {
                double nextHSpeed = Math.max(0.0, hSpeed - brakeDecel * 20.0 * dt);
                double scale = hSpeed > 1.0E-5 ? nextHSpeed / hSpeed : 0.0;
                newVelMps = new Vec3(newVelMps.x * scale, newVelMps.y, newVelMps.z * scale);
            }
        }

        // 8. 计算真实法向过载 G-Load 并检测超速/超 G 结构损伤
        float currentG = 1.0f;
        if (!onGround()) {
            double normalAccel = (newVelMps.subtract(velMps).scale(1.0 / dt)).dot(basis.up)
                    + GRAVITY_MPS2 * basis.up.y;
            currentG = (float) Mth.clamp(normalAccel / GRAVITY_MPS2, -6.0, 12.0);
        }
        this.entityData.set(DATA_G_LOAD, Mth.lerp(0.35f, getGLoad(), currentG));

        checkStructuralLimits(airspeed, getGLoad());

        // 转换回 blocks/tick 并执行碰撞移动
        Vec3 stepVel = newVelMps.scale(1.0 / 20.0);
        setDeltaMovement(stepVel);
        move(MoverType.SELF, getDeltaMovement());
        Vec3 finalVel = getDeltaMovement();
        this.entityData.set(DATA_VEL_X, (float) finalVel.x);
        this.entityData.set(DATA_VEL_Y, (float) (onGround() && finalVel.y < 0.0 ? 0.0 : finalVel.y));
        this.entityData.set(DATA_VEL_Z, (float) finalVel.z);

        // 9. 高速撞墙/硬着陆冲击检测
        if (horizontalCollision && airspeed > 18.0) {
            float impactDamage = (float) ((airspeed - 15.0) * 2.2);
            hurt(level().damageSources().flyIntoWall(), impactDamage);
            setDeltaMovement(getDeltaMovement().scale(0.25));
        }
        if (verticalCollision && onGround() && velMps.y < -9.5) {
            float hardLandingDamage = (float) ((-velMps.y - 8.5) * 3.0);
            hurt(level().damageSources().fall(), hardLandingDamage);
        }
    }

    private void checkStructuralLimits(double airspeedMps, float gLoad) {
        PlaneTypeSpec spec = getPlaneType();
        if (gLoad > spec.maxPositiveG() + 1.0f || gLoad < spec.maxNegativeG() - 0.8f) {
            if (tickCount % 20 == 0) {
                hurt(level().damageSources().generic(), 2.5f);
                if (getPilot() instanceof Player pilot) {
                    notifyPilot(pilot, String.format("警告：超过机体结构极限过载 (%.1fG)！", gLoad));
                }
            }
        }
        if (getFlapStage() == 2 && airspeedMps > 38.0 && tickCount % 40 == 0) {
            if (getPilot() instanceof Player pilot) {
                notifyPilot(pilot, "警告：着陆襟翼超速！请收起襟翼 [U] 或减速");
            }
        }
    }

    private void updateAutopilotInputs() {
        Vec3 pos = position();
        Vec3 toCenter = new Vec3(orbitCenter.x - pos.x, 0.0, orbitCenter.z - pos.z);
        double dist = Math.max(1.0, toCenter.length());
        Vec3 radialIn = toCenter.scale(1.0 / dist);
        // 逆时针切向方向
        Vec3 tangent = new Vec3(-radialIn.z, 0.0, radialIn.x);
        double radialError = Mth.clamp((dist - orbitRadius) / 40.0, -0.65, 0.65);
        Vec3 desiredDir = tangent.add(radialIn.scale(radialError)).normalize();

        double desiredYaw = Math.toDegrees(Math.atan2(-desiredDir.x, desiredDir.z));
        float yawError = Mth.wrapDegrees((float) desiredYaw - getYawDeg());
        float desiredBank = Mth.clamp(yawError * 0.85f, -32.0f, 32.0f);
        this.inputRoll = Mth.clamp((desiredBank - getRollDeg()) * 0.045f, -0.7f, 0.7f);
        this.inputYaw = Mth.clamp(yawError * 0.015f, -0.4f, 0.4f);

        double altError = orbitTargetY - pos.y;
        float desiredPitch = (float) Mth.clamp(altError * 0.65, -12.0, 15.0);
        this.inputPitch = Mth.clamp((desiredPitch - getPitchDeg()) * 0.06f, -0.6f, 0.6f);

        double speedError = orbitSpeedMps - getAirspeedMps();
        setThrottle((float) Mth.clamp(getThrottle() + speedError * 0.01, 0.55, 0.95));
    }

    private void spawnClientParticles() {
        if (!isEngineRunning()) return;
        Basis basis = computeBasis(getPitchDeg(), getYawDeg(), getRollDeg());
        double tailOffset = getPlaneType().width() * 0.75 + 0.8;
        Vec3 exhaustPos = position().add(0.0, getPlaneType().height() * 0.45, 0.0)
                .subtract(basis.forward.scale(tailOffset));
        Vec3 exhaustVel = getDeltaMovement().subtract(basis.forward.scale(0.35 + getRpmNormalized() * 0.45));

        if (isAfterburnerActive()) {
            level().addParticle(ParticleTypes.FLAME,
                    exhaustPos.x, exhaustPos.y, exhaustPos.z,
                    exhaustVel.x, exhaustVel.y, exhaustVel.z);
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    exhaustPos.x, exhaustPos.y, exhaustPos.z,
                    exhaustVel.x * 0.5, exhaustVel.y * 0.5, exhaustVel.z * 0.5);
        } else if (tickCount % 2 == 0) {
            level().addParticle(ParticleTypes.SMOKE,
                    exhaustPos.x, exhaustPos.y, exhaustPos.z,
                    exhaustVel.x * 0.4, exhaustVel.y * 0.4, exhaustVel.z * 0.4);
        }
        // 大过载翼尖涡流拉烟
        if (getGLoad() > 4.2f && getAirspeedMps() > 22.0) {
            double halfSpan = getPlaneType().width() * 0.85;
            Vec3 leftTip = position().add(0.0, 0.6, 0.0).subtract(basis.right.scale(halfSpan));
            Vec3 rightTip = position().add(0.0, 0.6, 0.0).add(basis.right.scale(halfSpan));
            level().addParticle(ParticleTypes.CLOUD, leftTip.x, leftTip.y, leftTip.z, 0.0, 0.0, 0.0);
            level().addParticle(ParticleTypes.CLOUD, rightTip.x, rightTip.y, rightTip.z, 0.0, 0.0, 0.0);
        }
    }

    public record Basis(Vec3 forward, Vec3 right, Vec3 up) {}

    /**
     * 根据飞机俯仰角（Pitch，抬头为正）、偏航角（Yaw，MC 标准）和滚转角（Roll，右倾为正）
     * 计算三维正交坐标系基向量。
     */
    public static Basis computeBasis(float pitchDeg, float yawDeg, float rollDeg) {
        double yawRad = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);
        double rollRad = Math.toRadians(rollDeg);

        double sinY = Math.sin(yawRad);
        double cosY = Math.cos(yawRad);
        double sinP = Math.sin(pitchRad);
        double cosP = Math.cos(pitchRad);
        double sinR = Math.sin(rollRad);
        double cosR = Math.cos(rollRad);

        // Forward: 抬头时 y > 0
        Vec3 forward = new Vec3(-sinY * cosP, sinP, cosY * cosP).normalize();
        // 水平右轴（未滚转）
        Vec3 right0 = new Vec3(-cosY, 0.0, -sinY).normalize();
        // 未滚转上轴
        Vec3 up0 = right0.cross(forward).normalize();

        Vec3 right = right0.scale(cosR).subtract(up0.scale(sinR)).normalize();
        Vec3 up = up0.scale(cosR).add(right0.scale(sinR)).normalize();
        return new Basis(forward, right, up);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 手持航空发动机（潜行或直接右键）：换装航空发动机
        if (stack.getItem() instanceof PlaneEngineItem engineItem) {
            if (!level().isClientSide) {
                PlaneEngineType oldType = getEngineType();
                PlaneEngineType newType = engineItem.getEngineType();
                setEngineType(newType);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                    Item oldItem = switch (oldType) {
                        case FURNACE -> Registration.FURNACE_ENGINE.get();
                        case LIQUID -> Registration.LIQUID_ENGINE.get();
                        case JET -> Registration.JET_ENGINE.get();
                    };
                    player.getInventory().placeItemBackInInventory(new ItemStack(oldItem));
                }
                level().playSound(null, blockPosition(), SoundEvents.ANVIL_USE,
                        SoundSource.PLAYERS, 0.8F, 1.15F);
                player.displayClientMessage(Component.literal("已换装发动机：" + newType.displayName())
                        .withStyle(ChatFormatting.GOLD), true);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        // 手持铁锭：维修机体结构
        if (stack.is(Items.IRON_INGOT) && getStructuralHealth() < getPlaneType().maxHealth()) {
            if (!level().isClientSide) {
                setStructuralHealth(getStructuralHealth() + 30.0f);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_REPAIR,
                        SoundSource.PLAYERS, 0.9F, 1.1F);
                player.displayClientMessage(Component.literal(
                        String.format("机体维修完成 (%.0f / %.0f HP)",
                                getStructuralHealth(), getPlaneType().maxHealth())), true);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        // 正常右键：进入座舱
        if (!level().isClientSide) {
            if (canAddPassenger(player)) {
                player.startRiding(this);
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) return false;
        if (level().isClientSide || isRemoved()) return true;

        // 潜行左键空飞机：直接回收为物品
        if (source.getEntity() instanceof Player player
                && player.isSecondaryUseActive()
                && getPassengers().isEmpty()) {
            dropPlaneItem();
            discard();
            return true;
        }

        float nextHealth = getStructuralHealth() - amount;
        setStructuralHealth(nextHealth);
        markHurt();

        if (nextHealth <= 0.0f) {
            if (level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                        getX(), getY() + 0.5, getZ(), 2, 0.5, 0.5, 0.5, 0.0);
            }
            level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(),
                    SoundSource.PLAYERS, 1.2F, 0.9F);
            dropPlaneItem();
            ejectPassengers();
            discard();
        }
        return true;
    }

    private void dropPlaneItem() {
        Item item = switch (getPlaneType()) {
            case SMALL_PLANE -> Registration.SMALL_PLANE_ITEM.get();
            case LARGE_PLANE -> Registration.LARGE_PLANE_ITEM.get();
            case CARGO_PLANE -> Registration.CARGO_PLANE_ITEM.get();
        };
        spawnAtLocation(new ItemStack(item));
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < getPlaneType().maxPassengers();
    }

    /**
     * 获取前座主驾驶员（第一位乘员）。
     */
    @Nullable
    public LivingEntity getPilot() {
        Entity first = getFirstPassenger();
        return first instanceof LivingEntity living ? living : null;
    }

    public boolean isPilot(@Nullable Entity entity) {
        return entity != null && entity == getPilot();
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        // 保持服务端权威气动与位移解算（与 AbstractMinecart 同理）：
        // 若返回本地玩家，原版 Entity.isControlledByLocalInstance() 在客户端为 true，
        // 会导致 ClientPacketListener 忽略服务端所有位移同步包，且 LocalPlayer 每 tick 发送
        // ServerboundMoveVehiclePacket 将服务端飞机强制拉回客户端原地导致无法移动。
        // 前座飞行员权限统一通过 getPilot() / isPilot() 判定。
        return null;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction callback) {
        if (!hasPassenger(passenger)) return;
        int index = getPassengers().indexOf(passenger);
        Basis basis = computeBasis(getPitchDeg(), getYawDeg(), getRollDeg());
        double forwardOffset = switch (getPlaneType()) {
            case SMALL_PLANE -> 0.15;
            case LARGE_PLANE -> index == 0 ? 0.55 : -0.65;
            case CARGO_PLANE -> 1.10 - index * 0.85;
        };
        double verticalOffset = getPlaneType().height() * 0.28;
        Vec3 seatPos = position()
                .add(basis.forward.scale(forwardOffset))
                .add(basis.up.scale(verticalOffset));
        callback.accept(passenger, seatPos.x, seatPos.y, seatPos.z);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Basis basis = computeBasis(0.0f, getYawDeg(), 0.0f);
        double side = getPlaneType().width() * 0.75 + 0.8;
        return position().add(basis.right.scale(side)).add(0.0, 0.5, 0.0);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = steps;
    }

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : this.getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : this.getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        super.lerpMotion(x, y, z);
    }

    @Override
    public float maxUpStep() {
        return 0.6F;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        PlaneTypeSpec spec = getPlaneType();
        return EntityDimensions.scalable(spec.width(), spec.height());
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean canCollideWith(Entity other) {
        // 仅与其他不可穿透载具实体发生硬碰撞；站在机身/机翼上或周围的玩家与生物不得作为刚体墙阻挡飞机移动
        return other instanceof PlaneEntity && !this.isPassengerOfSameVehicle(other);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("PlaneType")) {
            setPlaneType(PlaneTypeSpec.byOrdinal(tag.getInt("PlaneType")));
        }
        if (tag.contains("EngineType")) {
            setEngineType(PlaneEngineType.byOrdinal(tag.getInt("EngineType")));
        }
        setMasterBattery(tag.getBoolean("MasterBattery"));
        setFuelPump(tag.getBoolean("FuelPump"));
        setMagnetoState(tag.getInt("MagnetoState"));
        setEngineRunning(tag.getBoolean("EngineRunning"));
        this.entityData.set(DATA_RPM, tag.getFloat("Rpm"));
        this.entityData.set(DATA_EGT, tag.contains("Egt") ? tag.getFloat("Egt") : AMBIENT_TEMP_C);
        setThrottle(tag.getFloat("Throttle"));
        setPitchDeg(tag.getFloat("PitchDeg"));
        setYawDeg(tag.getFloat("YawDeg"));
        setRollDeg(tag.getFloat("RollDeg"));
        setFlapStage(tag.getInt("FlapStage"));
        setGearDown(!tag.contains("GearDown") || tag.getBoolean("GearDown"));
        this.entityData.set(DATA_GEAR_PROGRESS,
                tag.contains("GearProgress") ? tag.getFloat("GearProgress") : 1.0f);
        setParkingBrake(!tag.contains("ParkingBrake") || tag.getBoolean("ParkingBrake"));
        setElevatorTrimDeg(tag.getFloat("ElevatorTrim"));
        if (tag.contains("StructuralHealth")) {
            setStructuralHealth(tag.getFloat("StructuralHealth"));
        }
        this.autopilotOrbit = tag.getBoolean("AutopilotOrbit");
        if (this.autopilotOrbit) {
            this.orbitCenter = new Vec3(tag.getDouble("OrbitX"), tag.getDouble("OrbitY"), tag.getDouble("OrbitZ"));
            this.orbitRadius = tag.getDouble("OrbitRadius");
            this.orbitTargetY = tag.getDouble("OrbitTargetY");
            this.orbitSpeedMps = tag.getDouble("OrbitSpeedMps");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("PlaneType", getPlaneType().ordinal());
        tag.putInt("EngineType", getEngineType().ordinal());
        tag.putBoolean("MasterBattery", isMasterBatteryOn());
        tag.putBoolean("FuelPump", isFuelPumpOn());
        tag.putInt("MagnetoState", getMagnetoState());
        tag.putBoolean("EngineRunning", isEngineRunning());
        tag.putFloat("Rpm", getRpmNormalized());
        tag.putFloat("Egt", getEgtCelsius());
        tag.putFloat("Throttle", getThrottle());
        tag.putFloat("PitchDeg", getPitchDeg());
        tag.putFloat("YawDeg", getYawDeg());
        tag.putFloat("RollDeg", getRollDeg());
        tag.putInt("FlapStage", getFlapStage());
        tag.putBoolean("GearDown", isGearDown());
        tag.putFloat("GearProgress", getGearProgress());
        tag.putBoolean("ParkingBrake", isParkingBrakeOn());
        tag.putFloat("ElevatorTrim", getElevatorTrimDeg());
        tag.putFloat("StructuralHealth", getStructuralHealth());
        tag.putBoolean("AutopilotOrbit", autopilotOrbit);
        if (autopilotOrbit) {
            tag.putDouble("OrbitX", orbitCenter.x);
            tag.putDouble("OrbitY", orbitCenter.y);
            tag.putDouble("OrbitZ", orbitCenter.z);
            tag.putDouble("OrbitRadius", orbitRadius);
            tag.putDouble("OrbitTargetY", orbitTargetY);
            tag.putDouble("OrbitSpeedMps", orbitSpeedMps);
        }
    }
}
