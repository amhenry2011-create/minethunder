# MineThunder 原始锁定逻辑（以 demo/radar-demo.html 为准）

> 历史文档：Demo 的 `SRC` 已删除，当前保留 `SRC PD`；`ACQ PD` 是 1 秒临时尝试锁定状态。当前实现以 `RADAR_MODES.md` 和 `TWS.md` 为准。

本文记录 Demo 里的锁定/TWS/ACM/Notch 规则，作为后续 Mod 实现的基线。Demo 使用 `modeList = ["SRC", "SRC PD", "TWS", "ACM"]`，以下逻辑按 Demo 源码整理。

## 1. 扫描命中与可见性

Demo 的 `inScanVolume(t)`：

- 雷达关闭时不可见。
- 目标距离超过当前距离档不可见。
- 目标方位必须落在 `scanAz ± effectiveScanHalf()` 内。
- 目标俯仰必须落在 `scanEl ± 20°` 内。
- **除 SRC 外，所有模式都执行 39/Notch：`abs(closure) < 20` 的目标不可见。**
  - 因此 SRC PD、TWS、ACM 都有 Notch 逻辑；不是只有 TWS。
- `contactVisible(t)` 还要求目标最近一次被扫描命中距当前小于 `CONTACT_MEMORY = 12s`。

扫描线命中：

- 目标必须满足 `abs(t.az - sweepAz) < 6°` 且 `abs(t.el - rowEl) < 4°`。
- 命中后更新 `lastSeen`，并产生 `paintEdges`。
- 只有扫描线实际扫过才建立/刷新回波，未扫到的目标不会因“在扫描范围内”而提前亮起。

## 2. 普通锁定（非 TWS）

- `attemptLock()` 从雷达面板中心的 `strobe`（游标/十字）开始，寻找 `contactVisible(t)` 的目标。
- 目标必须能在面板内投影，且与 strobe 的屏幕距离小于 30 px。
- ACM 下还要求 `inAcmBox(t)` 且在 `ACM_MAX_RANGE` 内。
- 找到最近目标后 `beginLock(t)`；`beginLock` 进入 `acquiring`，锁定进度从 0 开始。
- `updateModeLogic()` 在目标仍可见时，根据 `activeQuality(t)` 累积进度；进度满 1 后进入 `tracking`。
- 目标不可见且 TWS 惯性保持不成立时 `clearLock()`。
- `clearLock()` 会清空锁定、TWS 航迹、所有目标的 `lastSeen`，并设置 1.2 秒的雷达清除窗口；导引头回到 SEARCHING。

## 3. TWS

- Demo 只维护一条 `twsTrack`，不是多目标同时跟踪。
- `maintainTwsTrack(paintEdges)`：
  - 已有硬锁时，TWS 航迹跟随硬锁目标。
  - 没有硬锁时，用本次 `paintEdges` 中的第一个目标建立 TWS 航迹。
  - 航迹超过 `TWS_INERTIAL_TIMEOUT = 10s` 未测量则清空。
- `updateTwsScan(dt)` 自动把 `scanAz/scanEl` 以 22°/s 拉向 TWS 航迹的预测位置。
- `Alt+T`（`selectNextTarget`）：
  - 候选必须距离在档位内、`abs(closure) >= 20`、且最近 12 秒内被看到。
  - 按距离排序，从当前目标切换到下一个。
  - 如果切换前是硬锁状态，切换后保持硬锁并直接把 `lockedId` 更新到新目标；否则只切换 TWS 航迹。
- `Alt+F`（`toggleLock`）：
  - TWS 下已有硬锁 → 解锁。
  - TWS 下没有硬锁 → 锁定当前 TWS 航迹目标（`beginLock`）。
- TWS 航迹是软跟踪；只有 `beginLock` 后才是硬锁。

## 4. ACM

- `toggleAcm()` 进入/退出 ACM；进入时清空 TWS 航迹和硬锁，扫描中心复位。
- ACM 搜索框为屏幕中央的固定方形区域（Demo `ACM_BOX = 200px`），搜索距离固定 `ACM_MAX_RANGE = 12000`。
- `attemptLock()` 在 ACM 下只允许框内目标，但 `toggleLock()` 在 ACM 下直接返回，不能手动硬锁。
- `updateModeLogic()` 自动截获：
  - 目标必须可见、在 ACM 框内、距离不超过 ACM 最大距离。
  - 目标需要累计 `acmHits`：6 km 内 1 次，6-12 km 2 次，更远 3 次。
  - 距离机头轴线最近的目标被自动设为 `tracking` 硬锁。
  - `acmUnlockAt` 防止刚解锁的目标瞬间复锁。

> Demo 原始 ACM 是固定框/固定 12 km；当前 Mod 保留自动截获，位置和宽度固定，仅距离可调。
>
> 当前 Mod 的 ACM 上下文：进入时清空硬锁/TWS；自动截获后切 `STT/TRK PD`，但 `acmActive` 保留，ACM 距离/扫描宽度继续保持；`Alt+W` 在 ACM 或自动截获后的 TRK PD 都能退出。解锁后有 2 秒复锁冷却。ACM 纳入多普勒模式，避免贴地目标被错误按杂波过滤。中央 ACM 框按 ±3° 屏幕投影缩小。

## 5. 导引头与发射

- `toggleSeeker()`：必须有 `activeTarget()` 才能开关导引头。
- `armSeeker()`：导引头环回到屏幕中心，进入 SEARCHING。
- `updateSeeker()`：
  - 导引头环以 900 px/s 向目标屏幕位置滑动。
  - 对准后按 `activeQuality(t)` 进入 ACQUIRING，1.6 秒后 TRACKING；高质量再进入 HIGH。
  - Notch 会显著降低 `activeQuality`。
- `canLaunchNow()`：
  - 导引头必须开启且未发射。
  - 必须有活动目标。
  - 硬锁要求 `lockState == tracking`；TWS 软跟踪要求存在 TWS 目标。
  - SARH 单白圈（SEARCHING）禁止发射。
- `drawLockLayer` 画锁定环/进度弧；`drawTargetBox` 使用**完整方形框**（四边）。
- 目标框状态：
  - acquiring：四角脉冲框。
  - tracking：完整绿色方形框。

## 6. 当前 Mod 与 Demo 的差异

- Mod 回波记忆是 5 秒（Demo 12 秒），TWS 也是单当前目标。
- 面板 TRK PD 目标为亮绿色圆点；主画面目标框为完整绿色方形。
- 39/Notch 按现实模式能力处理：SRC 无速度门，不判 39；SRC PD / MTI / TWS / ACM / STT 走 39。ACM 是扫描模式，沿用其底层 PD 判定。
- ACM 搜索位置固定，方向键不能平移；ACM 距离通过 `Alt+Q` 调整，默认 100 b；`Alt+D` 在 ACM 中禁用，宽度固定 ±3°。
- 导引头视觉：
  - 雷达弹没有外环（导引头转动范围）。
  - 雷达弹/红外弹的目标锁定环只在导引头开启时显示；主画面绿色方形框始终显示。
  - 红外弹外环比旧版更大，不再为保证完整显示而缩小；超出屏幕被裁切是允许的。
- 红外弹导引头视觉不要求满弓/弹药/锁定；雷达弹导引头只在开启且有雷达锁定时显示中心环和锁定环。
- GMI/GMTI 在 Demo 和当前 Mod 都没有独立模式实现；如后续需要，需单独立项。

## 7. 本轮现实化的 TRK PD / 39 参数

为避免上下文丢失，以下参数和当前实现绑定。

### 39/Notch 模型

- 速度门：`|相对径向速度| < 5 m/s`。
- 近距离烧穿：`0–50 b`，距离小于 50 b 时逐步降低 39 影响。
- 模式敏感度：
  - SRC：0
  - MTI：0.7
  - ACM：0.85（底层 PD，纳入 Doppler 判定）
  - SRC PD / TWS / STT：1.0
- Notch 强度公式：
  `depth = 1 - |v_r| / 5`
  `close = clamp(1 - range / 50, 0, 1)`
  `severity = depth * modeSensitivity * (1 - 0.85 * close)`
- `severity` 不再作为硬不可见/不可锁条件，只影响：
  - 面板回波透明度（最低约 0.35）；
  - TRK PD 锁定后的亮度（`1 - severity * 0.65`）；
  - 导引头质量/包线。
- 雷达锁定/跟踪丢失后不立即消失：
  - 无扫描更新时做惯性跟踪；
  - 每 tick 降低透明度，最多保持 100 tick（5 秒）；
  - 5 秒内重新获得回波则恢复；
  - 超过 5 秒拖锁并移除目标回波。
- 服务端不再因 39 直接拒绝发射；发射由客户端锁定质量和雷达状态决定。

### TRK PD 锁定流程

- 手动 `Alt+F`：从已扫描回波中选最近目标，进入尝试锁定。
- 尝试锁定框固定在发起时的近似面板位置，不跟随目标移动。
- 尝试时间：20 tick（约 1 秒）。
- 成功后雷达实际切换 `RadarMode.STT`，HUD 显示 `TRK PD`。
- 退出/拖锁后恢复锁定前的搜索模式。
- 面板 TRK PD 目标样式：亮绿色圆点（不是方形框）。
- 主画面 TRK PD 目标样式：绿色方形框 + 速度/距离/接近率等参数。
- 非 TWS/非 ACM 自动截获之外，不自动显示尝试锁定框。
- ACM 保留自动截获，自动成功后直接进入 TRK PD。
- TWS 保留自动当前目标标记；软跟踪目标仍显示 TWS 标记。

### ACM

- 搜索位置固定，方向键不能平移。
- `Alt+D` 在 ACM 中禁用，扫描宽度固定 ±3°。
- `Alt+Q` 仍可调整 ACM 搜索距离。
