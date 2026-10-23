# MineThunder 缺陷排查报告

| 项目 | 内容 |
|---|---|
| 审查对象 | `minethunder-main.zip`（解压后 `minethunder-main/`，62 个 Java 文件） |
| 目标版本 | Minecraft 1.21.1 / NeoForge 21.1.236 / mod 0.1.0 |
| 审查日期 | 2026-10-21 |
| 审查方式 | 全量静态代码阅读 + 跨文件调用链追踪；**未编译、未实机运行**（审查环境无 JDK） |
| 行号说明 | 均指压缩包内源码的行号，格式 `相对路径:行` |
| 结论 | 发现 **4 个 P0、6 个 P1、8 个 P2**；仓库自带 `AUDIT.md` 的 14 项中，6 项已修复、5 项部分修复、3 项仍存在 |

## 严重度定义

| 级别 | 含义 |
|---|---|
| **P0** | 功能直接失效、误操作、或多人环境可被利用；不修则核心玩法不成立 |
| **P1** | 特定场景下明显错误 / 可被滥用 / 资源泄漏；影响体验或公平性 |
| **P2** | 数值、边界、性能、健壮性问题；不阻塞主流程 |

---

## 一、P0

### P0-1 红外/半主动弹把「导引头视场」当作「发射后偏转极限」，离轴发射不制导

**状态：已修复（2026-10-23）**
- `MissileSpec.guidanceFovDeg` 统一返回 `angleMaxDeg`：`seekerFovDeg` 只保留给 HUD 内环与导引头捕获判断，不再作为制导硬限。
- `ArhMissileArrow.inSeekerCone`/`findRadarTarget` 改用 `angleMaxDeg`，pitbull 重捕不再因 12° 锥体+10t 冷却丢目标（冷却 10→3）。

**位置**
- `src/main/java/com/minethunder/missile/MissileSpec.java:110-115`
- `src/main/java/com/minethunder/missile/MissileArrow.java:489-493`
- `src/main/java/com/minethunder/missile/IrMissileArrow.java:45-47`
- 参数：`src/main/java/com/minethunder/missile/MissileSpecs.java:73-75`

**代码**

```java
// MissileSpec.java:110
public double guidanceFovDeg(boolean pitbullActive) {
    return switch (guidance) {
        case SARH -> seekerFovDeg;                 // 10°
        case ARH -> pitbullActive ? seekerFovDeg : angleMaxDeg;
        case IR -> seekerFovDeg;                   // 8°  <-- 错误
    };
}
```

```java
// MissileArrow.java:489
double angle = Math.toDegrees(Math.acos(clamp(velDir.dot(losDir), -1, 1)));
double allowedFov = seekerLimited ? spec.guidanceFovDeg(pitbullActive) : spec.angleMaxDeg;
if (angle > allowedFov) {
    return;   // 超出视场：本 tick 完全不产生转向
}
```

```java
// IrMissileArrow.java:45
double angle = Math.toDegrees(Math.acos(
        Math.max(-1.0, Math.min(1.0, forward.dot(to.normalize())))));
if (angle > spec.seekerFovDeg) return loseSeeker();
```

**成因**
`MissileSpec.java:31-32` 明确定义 `angleMaxDeg` 是「发射后偏转角」，而 `seekerFovDeg` 是 HUD 小圈 / 导引头瞬时视场。IR 参数为 `fov=8 / lockAngleMax=30 / angleMax=35`，允许 30° 离轴发射，却用 8° 做制导判决。

**后果**
目标偏离弹轴超过 8° 时：`resolveTarget()` 返回 `null` → 失去目标航迹 → `applyGuidance` 直接 `return`，不再修正。导弹只能沿发射瞬间的提前量直线飞行；目标一机动即脱靶，5 秒（`prolongationTimeMax`）后自爆。近距离横穿、侧向离轴发射几乎必失。SARH 同理（10°）。

**复现**
1. 选择 IR 弹，用导引头锁定一个约 20° 离轴、正在横穿的目标；
2. 发射后让目标做一次转向；
3. 观察导弹不做任何修正，5 秒后空中自爆。

**修复建议**
- 制导视场改用 `angleMaxDeg`；`seekerFovDeg` 仅用于 HUD 内圈与 ARH 末段；
- 或把判决对象从「弹轴 ↔ 真实 LOS」改为「弹轴 ↔ `guidanceLosDir`」，并给 `guidanceLosDir` 加上 `rateMax` 限速（已有实现）。

---

### P0-2 Ctrl+Alt+任意键都会发射导弹

**位置**：`src/main/java/com/minethunder/client/KeyInputHandler.java:99-105`

**代码**

```java
// Alt 组合键统一放在 ClientTick 轮询，避免同一次按键触发两次。
// Ctrl+Alt 就是导引头入口（雷达导引头 Alt+X、红外导引头……
if (alt && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
    fireMissile(mc);
    return;
}
if (alt) return;
switch (key) { ... }
```

**成因**
只判断了修饰键，没有判断 `key` 是哪一个；且 Ctrl 自身的 `GLFW_PRESS` 事件就带 `ALT` 修饰位。

**后果**
- 按住 Alt 再按 Ctrl（或反向）即发射；
- `Ctrl+Alt+S`、`Ctrl+Alt+F` 等任意组合都会发射；
- 与 `STATUS.md` 中「Ctrl+Alt：仅发射提示」的设计不一致，会误耗弹药（服务端会真实扣除）。

**修复建议**
绑定明确的 `KeyMapping`（或至少限定 `GLFW_KEY_SPACE` 等具体键），并排除 `LEFT_CONTROL / RIGHT_CONTROL / LEFT_ALT / RIGHT_ALT` 自身的 PRESS 事件。

---

### P0-3 导弹实体未设置客户端追踪距离，飞出 80 格客户端即「消失」

**位置**
- `src/main/java/com/minethunder/Registration.java:37-53`（三种导弹）
- 对照 `src/main/java/com/minethunder/Registration.java:55-61`（干扰弹显式设了 `clientTrackingRange(8)`）

**代码**

```java
// Registration.java:43
public static final DeferredHolder<EntityType<?>, EntityType<ArhMissileArrow>> ARH_MISSILE =
        ENTITIES.register("arh_missile_entity", () -> EntityType.Builder
                .<ArhMissileArrow>of((type, level) -> new ArhMissileArrow(type, level, MissileSpecs.ARH), MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .build("minethunder:arh_missile_entity"));   // 缺 clientTrackingRange / updateInterval
```

`EntityType.Builder` 默认 `clientTrackingRange = 5`（区块，即 80 格）、`updateInterval = 3` tick。三种弹的 `maxDistance` 分别为 300 / 1000 / 1200 格。

**后果**
超过 80 格后客户端不再收到该实体：

1. 导弹在玩家画面中凭空消失；
2. `RadarHudClientTicker.java:116-135` 通过 `entitiesForRendering()` 判断在飞，于是 `missileInFlight` 变 `false` → 制导线、惯性点被清空，并弹出「导弹已结束」；
3. 服务端导弹仍在正常飞行并命中，形成客户端与服务端表现分裂。

**修复建议**

```java
.sized(0.5f, 0.5f)
.clientTrackingRange(32)   // 或按 maxDistance/16 计算
.updateInterval(1)         // 高速弹默认 3 tick 同步一次会明显抖动
```

---

### P0-4 雷达锁定状态完全由客户端决定，多人服可伪造

**位置**
- `src/main/java/com/minethunder/network/RadarSyncPacket.java:37-41`
- `src/main/java/com/minethunder/radar/ServerRadarState.java:26-41`
- 发射校验：`src/main/java/com/minethunder/network/MissileFirePacket.java:61-92`

**代码**

```java
// RadarSyncPacket.java:37
public void handle(IPayloadContext context) {
    if (context.player() instanceof ServerPlayer player) {
        ServerRadarState.update(player, targetId, seekerState, radarMode,
                hardLock, radarOn, trackQuality);   // 全盘接受客户端字段
    }
}
```

```java
// ServerRadarState.java:26 —— 只做范围清洗，不校验事实
int safeTarget = Math.max(-1, targetId);
int safeSeeker = Math.max(-1, Math.min(3, seekerState));
int safeMode   = Math.max(0, Math.min(RadarMode.values().length - 1, radarMode));
float safeQuality = Float.isFinite(trackQuality)
        ? Math.max(0.0f, Math.min(1.0f, trackQuality)) : 0.0f;
```

**成因**
`MissileFirePacket` 后来补了距离 / 方位 120° / 俯仰 60° / LOS / 目标类别校验，但：

- 没有校验目标「刚才是否真的出现在本机雷达扫描结果里」；
- `trackQuality` 直接采信客户端（传 0.99 即可获得几乎不丢帧的数据链）；
- `radarOn / hardLock / seekerState` 也由客户端决定。

**后果**
改包即可在雷达关闭、未扫描、目标从未上屏的情况下完成锁定并发射；数据链精度可被拉满。单人游戏无影响，多人对抗服属于硬伤。

**修复建议**
- 服务端执行一次 `RadarCore.scan(...)`（或缓存服务端扫描结果）复核目标是否在扫描扇区内；
- `trackQuality` 由服务端按扫描命中数与 Notch 严重度计算；
- `RadarSyncPacket` 仅作为「意向」，不作为事实。

---

## 二、P1

### P1-1 RWR 涂鸦包可伪造并向全服广播

**位置**：`src/main/java/com/minethunder/network/RwrPaintPacket.java:24-27`、`src/main/java/com/minethunder/radar/RwrManager.java:93-115`

**成因**：只校验「请求者处于 SRC_PD + 目标在 600 格 / 120° / 60° 内 + 非垂直盲区」，未校验目标是否真被雷达扫到，且没有任何冷却。

**后果**：客户端可对任意玩家刷涂鸦，经 `RwrSyncPacket` 广播给其他玩家（TTL 40 tick），可制造假 RWR 接触或刷屏。

**修复建议**：要求该目标在本机最近一次扫描结果中（或 `paintSentTicks` 的 10 tick 冷却前移服务端），并加服务端冷却。

---

### P1-2 弓发射路径下，`initializeLaunch` 的初速与提前量会被原版 `shoot()` 覆盖

**位置**：`src/main/java/com/minethunder/missile/MissileItem.java:36-56`、`src/main/java/com/minethunder/missile/MissileArrow.java:128-152`

**代码**

```java
// MissileItem.java:36
@Override
public AbstractArrow createArrow(Level level, ItemStack ammoStack, LivingEntity shooter,
                                 @Nullable ItemStack weaponStack) {
    MissileArrow arrow = entityType.get().create(level);
    ...
    arrow.initializeLaunch(shooter);   // 设置 载机速度 + 相对速度 + 提前量
    return arrow;
}
```

`MissileArrow` 没有重写 `shoot(double,double,double,float,float)`。1.21 的 `BowItem` 在 `createArrow` 之后仍会调用 `projectile.shoot(x,y,z, f*3.0F, 1.0F)`，而 `AbstractArrow.shoot` 内部执行 `setDeltaMovement(vec)`，直接覆盖上一行设置的速度。

**后果**：走弓的那条发射路径，载机速度、提前量方向全部作废，初速退化为弓的 `power * 3`。`MissileEvents.onArrowLoose` 仍在为这条路径做门禁，说明它仍被当作可用发射方式。

**修复建议**：重写 `shoot(...)` 保存初速并在父类调用后回填，或让弓路径统一走 `MissileLaunchService.launch(...)`。

---

### P1-3 玩家在水中对所有雷达模式完全隐身

**位置**：`src/main/java/com/minethunder/radar/RadarCore.java:139`

**代码**

```java
if (!(entity instanceof LivingEntity living) || !living.isAlive()) return null;
if (living.isPassenger() && living.getVehicle() instanceof Boat) return null;
if (living.isInWater()) return null;          // 不分模式，直接丢弃
```

**后果**：游泳 / 落水的玩家与生物不会出现在 SRC PD、TWS、ACM、GMI、SEA 任何一个模式中（SEA 只收船）。空战掉进水里即彻底脱锁、隐身。

**修复建议**：AIR 模式保留水面目标（仅对水下做信号衰减），把水体判据移入 GMI/SEA 的分类分支。

---

### P1-4 静态 `MARKERS` 跨存档不释放（内存泄漏 + 幽灵实体）

**位置**：`src/main/java/com/minethunder/radar/BlockRadarTargetManager.java:39`、`112-119`

**代码**

```java
private static final Map<UUID, Marker> MARKERS = new ConcurrentHashMap<>();
...
for (var entry : MARKERS.entrySet()) {
    UUID id = entry.getKey();
    Marker marker = entry.getValue();
    if (marker.level() == level && !used.contains(id)) {
        marker.discard();
        MARKERS.remove(id);
    }
}
```

**成因**：全项目没有 `ServerStoppedEvent` / `LevelEvent.Unload` 监听（已 grep 确认）。清理条件要求 `marker.level() == 当前 level`，旧世界的条目永远不会被遍历到。

**后果**：退出单人存档后，`Marker` 强引用旧 `ServerLevel`，整个旧世界对象无法回收；换维度后旧维度 marker 永久残留。

**修复建议**：`ServerStoppedEvent` 中 `MARKERS.clear()`；或改为 `Map<ResourceKey<Level>, Map<UUID, Marker>>` 按维度分桶清理。

---

### P1-5 `ServerRadarState` 跨维度复用数字 entityId，会串目标

**位置**：`src/main/java/com/minethunder/radar/ServerRadarState.java:53-59`

**代码**

```java
public static Entity getTarget(Player player) {
    LockState state = get(player);
    if (state == null || !state.radarOn() || state.targetId() < 0) return null;
    if (player.level() instanceof ServerLevel serverLevel) {
        return serverLevel.getEntity(state.targetId());   // 数字 id 只在单 level 内唯一
    }
    return null;
}
```

**后果**：玩家传送维度后，`LOCKS` 最多仍存活 100 tick（5 秒）；新维度里同 id 的实体会被当作锁定目标。`MissileFirePacket` 的距离 / 角度 / LOS 校验只能保证「附近有个实体」，不能保证是原来那个。

**修复建议**：`LockState` 保存目标 UUID 与维度 key，`getTarget` 用 UUID 查找并校验维度一致。

---

### P1-6 导弹初速被 `endSpeed` 硬截断，与 `initializeLaunch` 的载机速度叠加互相矛盾

**位置**：`src/main/java/com/minethunder/missile/MissileArrow.java:149`、`:526`

**代码**

```java
// 149：载机速度 + 相对离架速度
setDeltaMovement(carrier.add(direction.scale(relativeMps / 20.0)));

// 526：紧接着的制导 tick 就被上限截断
speedMps = Math.max(0, Math.min(speedMps, spec.endSpeed));
```

**后果**：高速载机（鞘翅 30+ b/t ≈ 600+ m/s）发射后，第一个制导 tick 速度即从 600+ m/s 掉到 `endSpeed`（48 m/s），相对载机等于「急停」，与 149 行的设计意图直接冲突。

**修复建议**：动力段不设上限，或上限取 `max(endSpeed, 发射初速)`；把「气动极速」限制只作用于滑翔段。

---

## 三、P2

| 编号 | 位置 | 问题 | 建议 |
|---|---|---|---|
| P2-1 | `missile/MissileArrow.java:530-531` | 爬升能量项 `9.81 * newVelocity.y / speedMps` 量纲错误：`newVelocity.y` 单位是 b/t，`speedMps` 是 m/s，差 20 倍；且俯冲（y<0）反而加速 | `speedMps - 9.81 * (newVelocity.y * 20.0) / speedMps * dt` |
| P2-2 | `missile/MissileArrow.java:214`、`missile/SarhMissileArrow.java:47-48` | `UUID.fromString(tag.getString(...))` 无 try/catch，坏档或旧档会让区块加载抛 `IllegalArgumentException` | 解析失败返回 `null` 并记录日志 |
| P2-3 | `client/RadarHudClientTicker.java:308-313` | 无回波时 `trackQuality` 直接给 0.65，TWS 再保底 0.7；新接触即为「高质量航迹」，中段数据链几乎不丢帧 | 无回波给 0.2–0.3，按存活 tick 数递增 |
| P2-4 | `client/RadarHudClientTicker.java:164-167` + `radar/RadarCore.java:57-58` | 每 tick 对 600³ AABB 做 `getEntities`（默认 300 格档），上千区块的实体段遍历 | 2–4 tick 扫一次，或按扫描带 / 扫描行分帧 |
| P2-5 | `radar/RadarCore.java:131-133` + `client/RadarHudClientTicker.java:759-769` | 自己的导弹也被 `classify` 成 AIR，可被自己锁定并对着它发射 | `lockAllowed` 排除 `owner == player` 的 `MissileArrow` |
| P2-6 | `missile/MissileArrow.java:762-767` | 先 `living.hurt(25)` 再 `level().explode(半径 1.5)`，中心伤害约 25 + 50；近炸命中玩家基本秒杀，也会自伤载机 | 二者取一，或把直接伤害降到 8–10 |
| P2-7 | `network/CountermeasurePacket.java:104-118` | 干扰弹投放无 LOS / 朝向校验；`kind==3` 时先写冷却再查物品，失败也吃 2 tick | 投放前做一次短距离 raycast；把冷却写入移到校验之后 |
| P2-8 | `network/RwrSyncPacket.java:24-27` | `readVarInt` 直接当数组长度 `new ArrayList<>(count)`，无上限 | 夹取到 `MAX_CONTACTS`（现为 8） |

---

## 四、`AUDIT.md` 复核表

| 编号 | 审计项 | 现状 | 证据 |
|---|---|---|---|
| 1 | 径向速度单位错误、SRC PD 误判 Notch | **已修复** | `radar/RadarCore.java:80-81` 统一 `scale(20.0)`；`RadarHudClientTicker.java:672-677` 同步 |
| 2 | IR 绕过导引头 FOV / rateMax | **已修复** | IR 不享数据链；制导视场统一 `angleMaxDeg`（P0-1 修复），`seekerFovDeg` 只做 HUD/捕获判断 |
| 3 | 导弹撞实体不触发战斗部 | **已修复** | `MissileArrow.java:370-382` 重写 `onHitEntity / onHitBlock` |
| 4 | 发射初速重复计算载机速度 | **已修复，但被两处抵消** | `MissileArrow.java:128-149` 逻辑正确；弓路径被原版 `shoot()` 覆盖（P1-2），又被 `endSpeed` 截断（P1-6） |
| 5 | 雷达弹服务端权威不足 | **部分修复** | `MissileFirePacket.java:74-92` 增加距离 / 角度 / LOS / 类别校验；`RadarSyncPacket` 仍全盘信任客户端（P0-4） |
| 6 | 导弹制导状态不持久化 | **已修复** | `MissileArrow.java:190-235` + `SarhMissileArrow.java:36-52` + `ArhMissileArrow.java:25-37` |
| 7 | 地面杂波是死代码 | **已删除** | `RadarCore` 已无 `applyClutterMask / updateClutter / drawClutter` |
| 8 | 失锁 / 末段视场不完整 | **部分修复** | IR/SARH 有 `lostLockTicks` 计时与自爆；ARH 有 `inSeekerCone` 重捕获；但制导视场参数用错 |
| 9 | 雷达目标同步可被伪造 | **仍存在** | 见 P0-4、P1-1 |
| 10 | 能量模型问题 | **部分修复** | `massAt()` 已用于推力（`MissileSpec.java:127-137`）；诱导阻力已乘 `dt`；爬升项量纲仍错（P2-1） |
| 11 | 发射提前量与导引头视场冲突 | **已修复** | 制导统一 `angleMaxDeg`；ARH pitbull 捕获锥 12°→30°、重捕冷却 10t→3t（P0-1） |
| 12 | 近炸与干扰弹 | **部分修复** | 近炸已改为扫掠线段判定（`MissileArrow.java:353-359`）；干扰弹仍无 LOS 校验（P2-7） |
| 13 | 热源阈值与文档不一致 | **未改（设计如此）** | `IrHeat.java:22` 阈值 100；怪物 90 / 动物 70 / 亡灵 30 |
| 14 | TWS 质量虚高、回波分类残留 | **部分修复** | `TwsTrackManager.java:38` 的 `confirmed = hits >= 2` 已改；质量保底仍在（P2-3） |

---

## 五、建议修复顺序

1. **P0-1、P0-2、P0-3**：三条都是「玩起来明显不对」的问题——离轴弹不制导、误触发射、导弹 80 格后消失，改动量都不大。
2. **P0-4、P1-1**：只玩单人可以延后；一旦开多人服必须先补服务端扫描复核。
3. **P1-3、P1-4、P1-5**：改动小、收益直接（水中隐身、静态泄漏、跨维度串目标）。
4. **P1-2、P1-6**：需要先确定发射路径以弓为准还是以 `MissileFirePacket` 为准，再统一速度模型。
5. **P2** 按需，建议与下一次数值调整一起做。

---

## 六、审查局限

- 环境无 JDK，**未执行 `gradlew build`**，因此不排除存在编译期 / NeoForge API 兼容性问题（尤其 `@EventBusSubscriber(bus = ...)` 在 21.1 的可用性）；
- 未实机运行，P0-1、P0-3 的表现基于代码路径推导，建议按「复现」小节做一次游戏内确认；
- 未审查资源文件（`assets/`、`data/`、`mixins`）、`build.gradle` 依赖版本与打包配置。
