# MineThunder 全量审核与修复计划 (Full Codebase Audit & Remediation Plan)

## 1. 审核总结与确认决策

通过对当前代码库（含雷达/TWS/ACM/BVR/RWR、IR/SARH/ARH 导弹制导、烟花与弓箭发射链路，以及 Simple Planes 6-DoF 飞机与冷舱航电系统）的全量静态与逻辑走查，已确认以下核心修复策略：

1. **飞机目标空/地分类规则**：按 `PlaneEntity.isAirborneTarget()` 动态区分——离地或地速 `> 4.5 m/s` 归为空中目标（`AIR` / `BvrKind.AI`），停机或低速滑行归为地面目标（`GROUND` / `BvrKind.GROUND`），从而在 `GMI` 对地模式与 `SRC PD / TWS / ACM` 对空模式间准确切换。
2. **双座/多座飞机乘员权限**：仅前座飞行员（`getControllingPassenger()`）控制飞行舵面、油门与冷舱航电开关（且其航电受电瓶总开关 `batteryOn` 制约）；后座乘员（WSO/乘客）不发送飞行控制指令、不被强制接管本地雷达电源，可独立操作自己的雷达与武器系统。
3. **导弹发射快捷键防误触**：保持 `Ctrl + Alt` 组合键触发导弹发射，但新增非修饰键排他检测——当同时按下任何其他常用功能键（如 `B/R/Q/D/W/F/T/X/E` 等）时不触发导弹发射。
4. **修复覆盖范围**：全量修复（包含 P0/P1 核心战斗、雷达与飞机联动缺陷、传统弓箭发射路径对齐，以及服务端生命周期清理与网络包安全校验）。
5. **预览服务（Port 3000）**：已启动 3000 端口预览守护进程，直接托管 `/demo/radar-demo.html` 雷达/HUD 原型参考页面。

---

## 2. 详细问题清单与修复方案

### 2.1 [P0] SARH 半主动雷达弹对 BVR 远程目标失锁/不更新航迹

- **根因定位**：
  - `MissileArrow.datalinkValid()`（第 125-128 行）对 `SARH` 强制调用 `datalinkValidLocal()`，而 `datalinkValidLocal()` 要求 `state.targetId() >= 0`。
  - 当玩家在 BVR 模式下锁定远程目标发射 SARH 时，`ServerRadarState` 中 `targetId == -1`（仅 `targetUuid` 有效）。这导致：
    1. `MissileArrow.updateDatalinkTarget()`（第 732 行）在 `state.targetId() < 0` 分支中写了 `if (spec.guidance != MissileSpec.Guidance.ARH) return;`，直接跳过了 SARH 的 BVR 目标解析。
    2. `SarhMissileArrow.datalinkValid()` 调用 `super.datalinkValid()` 返回 `false`，导致 `updateDatalinkMeasurement()` 与 `updateTargetTrack()` 全程不更新 `measuredTargetPos` 和 `lastKnownTargetPos`，SARH 只能飞向发射瞬间的初始快照点。
- **修复方案**：
  - **修改 `MissileArrow.java`**：
    - 新增 `datalinkValidBvr()` 公共判断：校验 `state.locked() && state.targetId() < 0 && state.targetUuid() != null`，且 `BvrTrackManager.find(level, state.targetUuid()) != null` 并在射手雷达有效跟踪扇区（`±PANEL_AZIMUTH_DEG` / `±SCAN_ELEVATION_HALF_DEG`）及无地形遮挡范围内。
    - `MissileArrow.datalinkValid()` 对 `SARH` 与 `ARH` 统一支持 `datalinkValidLocal() || datalinkValidBvr()`。
    - `MissileArrow.updateDatalinkTarget()` 的 `state.targetId() < 0` 分支允许 `ARH` 与 `SARH` 共同通过 `BvrTrackManager` 更新 `datalinkPos`、`datalinkVel` 及同维度已加载实体的 `target`。
  - **检查 `SarhMissileArrow.java`**：
    - 确保 `resolveTarget()` 与 `bvrIllumination()` 在 BVR 照射有效时保持半主动连续追踪，不再因 `targetId < 0` 提前断链。

---

### 2.2 [P0] `PlaneEntity` 在 `RadarCore` 与 `BvrTrackManager` 中的空/地动态分类

- **根因定位**：
  - `RadarCore.classify(Entity)`（第 176 行）对 `PlaneEntity` 直接返回了 `RadarTargetClass.AIR`，未调用 `plane.isAirborneTarget()`。
  - `BvrTrackManager.classify(Entity)`（第 128 行）对 `PlaneEntity` 直接返回了 `BvrKind.AI`，且 `BvrKind` 枚举缺少代表地面移动/停放载具并能映射为 `RadarTargetClass.GROUND` 的分支。
- **修复方案**：
  - **修改 `RadarCore.java`**：
    - 将 `if (entity instanceof PlaneEntity) return RadarTargetClass.AIR;` 改为：`if (entity instanceof PlaneEntity plane) return plane.isAirborneTarget() ? RadarTargetClass.AIR : RadarTargetClass.GROUND;`。
  - **修改 `BvrKind.java`、`BvrTrackManager.java`、`TargetRegistry.java`、`RadarHudRenderer.java`**：
    - 在 `BvrKind` 中补充 `GROUND`，并在 `BvrTrackManager.classify(Entity)` 中对 `PlaneEntity` 按 `plane.isAirborneTarget() ? BvrKind.AI : BvrKind.GROUND` 分类。
    - 在 `TargetRegistry.classOf(BvrKind)` 中将 `BvrKind.GROUND` 映射为 `RadarTargetClass.GROUND`，确保地面滑行/停放的飞机在 `GMI` 对地模式下可被远程/本地探测，而在对空 PD 模式下不混入空中目标。

---

### 2.3 [P1] 载机速度未计入雷达动态发射包线（NEZ）与 BVR 多普勒切向衰减

- **根因定位**：
  - `RadarHudRenderer.drawLaunchAssessment()`（第 1390-1397 行）计算导弹理想末速 `vBurn = spec.idealBurnoutSpeed()` 时未叠加发射载机（`mc.player.getRootVehicle()`）的当前速度，导致驾驶高速飞机时雷达面板右侧的动态 Rmax / NEZ 标尺不会随载机速度延伸。
  - `RadarHudRenderer.bvrEchoAlpha()`（第 420 行）使用 `mc.player.getDeltaMovement()` 而非 `mc.player.getRootVehicle().getDeltaMovement()` 计算相对速度，导致乘机时自身速度取到接近 0，BVR 回波多普勒切向凹口（Notch）亮度计算失真。
- **修复方案**：
  - **修改 `RadarHudRenderer.java`**：
    - 在 `drawLaunchAssessment()` 中获取载机前向/合成速度 `double shooterSpeed = mc.player != null ? mc.player.getRootVehicle().getDeltaMovement().length() * 20.0 : 0.0;`，将其叠加到 `vBurn = spec.idealBurnoutSpeed() + shooterSpeed`。
    - 在 `bvrEchoAlpha()` 中改为使用 `mc.player.getRootVehicle().getDeltaMovement()` 计算 `relVel`。

---

### 2.4 [P1] 多座飞机（Large / Cargo Plane）前后座权限分离与防自锁

- **根因定位**：
  - `PlaneClientInput.onClientTick()`（第 48-55 行）仅检查 `mc.player.getVehicle() instanceof PlaneEntity plane`，未区分前座飞行员（`plane.getControllingPassenger() == mc.player`）与后座乘员。后座乘员不仅每 tick 向服务端发送 `PlaneControlPacket`，还会被飞行员的电瓶状态强制关闭自身 `RadarHud.radarOn`。
  - `TrackedTarget.isOwnedBy(Player)`（第 91-94 行）未排除 `player.getRootVehicle().getUUID()`，当玩家乘坐飞机且飞机有 BVR 航迹时，客户端 `TargetRegistry` 可能将自身座机纳入候选目标。
- **修复方案**：
  - **修改 `PlaneClientInput.java`**：
    - 仅当 `plane.getControllingPassenger() == mc.player` 时才接管飞行控制按键、发送 `PlaneControlPacket` 以及执行冷舱电瓶断电对 `RadarHud.radarOn / seekerEnabled / seekerActive` 的联动限制；后座乘员可独立开关并使用个人雷达与导弹导引头。
  - **修改 `TrackedTarget.java`**：
    - 在 `isOwnedBy(Player player)` 中增加对 `player.getRootVehicle().getUUID().equals(uuid)` 的排除判断，彻底杜绝 BVR 扫到或锁定自身所乘飞机。

---

### 2.5 [P1] `Ctrl + Alt` 发射快捷键防误触与传统弓箭发射逻辑对齐

- **根因定位**：
  - `KeyInputHandler.onClientTick()`（第 294-298 行）在检测到 `altDown && isCtrlDown()` 时立即触发 `fireMissile(mc)`，未检查是否同时按下了其他按键（如 `Ctrl+Alt+B` 等组合误触）。
  - `MissileEvents.onArrowLoose()`（传统弓箭发射路径，第 72-116 行）仍使用旧版硬编码 `±90°/±40°` 扫描限制与 `RadarCore.isOccluded`，未与 `MissileLaunchService` 的导引头最大离轴角（`spec.lockAngleMax`）及 `RadarCore.isOccludedForTarget` 对齐，且 `cancel(event, message)` 忽略了 `message` 参数导致发射失败无提示。
- **修复方案**：
  - **修改 `KeyInputHandler.java`**：
    - 新增辅助方法 `hasOtherActionKeyDown(long window)`，当检测到常用字母/数字/方向键同时按下时，不触发 `Ctrl + Alt` 导弹发射。
  - **修改 `MissileEvents.java`**：
    - 将 `onArrowLoose()` 的雷达弹/红外弹发射校验与 `MissileLaunchService` 对齐（使用 `spec.lockAngleMax` 离轴角、`RadarCore.isOccludedForTarget`，并支持 BVR 远程锁发射），且在 `cancel()` 时通过 `player.displayClientMessage(Component.literal(message), true)` 向玩家显示取消原因。

---

### 2.6 [P2] 服务端状态生命周期清理与网络包安全校验

- **根因定位**：
  - `ServerRadarState.LOCKS` 在服务器停止或重启时缺少清理钩子，跨存档可能残留旧 `Player UUID` 锁定状态。
  - `RwrPaintPacket.handle()` 未校验目标玩家与发送者处于同一 `Level` 且距离在合理雷达探测上限（如 `<= 1500m`）内。
- **修复方案**：
  - **修改 `ServerRadarState.java`**：新增 `clearAll()` 并在服务器停止事件中调用。
  - **修改 `RwrPaintPacket.java`**：在 `handle()` 中校验 `sender.level() == targetPlayer.level()` 且距离 `<= 1500.0`。

---

## 3. 涉及修改的文件列表

1. `/src/main/java/com/minethunder/missile/MissileArrow.java`
2. `/src/main/java/com/minethunder/missile/SarhMissileArrow.java`
3. `/src/main/java/com/minethunder/missile/MissileEvents.java`
4. `/src/main/java/com/minethunder/radar/RadarCore.java`
5. `/src/main/java/com/minethunder/radar/BvrKind.java`
6. `/src/main/java/com/minethunder/radar/BvrTrackManager.java`
7. `/src/main/java/com/minethunder/radar/ServerRadarState.java`
8. `/src/main/java/com/minethunder/client/TargetRegistry.java`
9. `/src/main/java/com/minethunder/client/TrackedTarget.java`
10. `/src/main/java/com/minethunder/client/RadarHudRenderer.java`
11. `/src/main/java/com/minethunder/client/PlaneClientInput.java`
12. `/src/main/java/com/minethunder/client/KeyInputHandler.java`
13. `/src/main/java/com/minethunder/network/RwrPaintPacket.java`

---

## 4. 验证与发布流程

1. 执行 `./gradlew build --no-daemon --console=plain` 验证全量编译与类型检查零报错。
2. 升级版本号并更新 `HANDOFF.md` / `STATUS.md` 记录本次全量审核修复项。
3. 按照 `minethunder-auto-release` 规范打包、复制产物并提交推送至仓库。
