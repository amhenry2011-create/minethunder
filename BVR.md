# BVR（超视距）系统

2026-10-22 落地的完整 BVR 管线：服务端远程航迹 → 客户端 HUD → SARH/ARH 雷达弹远程锁定/数据链发射。

## 设计约束

- **客户端视距是硬上限**：模型/地形渲染不会在 32 区块外出现。BVR 只提供“航迹符号 + 数据链”，不追求 1000 b 内实体可见。
- **不依赖任何第三方视距 mod**（FarSight 不兼容 NeoForge 且闭源；Distant Horizons 只做地形 LOD）。
- **远程航迹仅发给开雷达的玩家**，避免全图天眼。
- AI 目标必须带 `minethunder_bvr` 标签（由区块 ticket 保活）才会作为 BVR 航迹发布；未加载的任意 AI 无法凭空检测。

## 组件

| 文件 | 作用 |
|------|------|
| `radar/BvrTicketService.java` | 服务端区块 ticket：`TicketType.FORCED`、radius=2（5×5 实体 ticking）、forceTicks。所有导弹（`MissileArrow` 及子类）一律持票、不设上限；带 `minethunder_bvr` 标签的实体每 5 tick 维护一个 ticket（上限 64），退出/离线时回收。 |
| `radar/BvrTrackManager.java` | 每 2 tick 采样快照：玩家（PLAYER）、带标签实体（AIR/SURFACE）、飞行中导弹（MISSILE，含 ownerUuid）。距离过滤：总范围 1000 b；非本机导弹仅 600 b 内；导弹 owner 是 viewer 时全程保留。非导弹航迹按观察者做 LOS 遮挡剔除（山体/地面后的目标不发布）。发送 `BvrSyncPacket`。 |
| `network/BvrSyncPacket.java` | 服务端→客户端全量航迹：UUID、kind、ownerUuid、name、pos、vel、entityId。 |
| `radar/TargetSource.java` + `client/TrackedTarget.java` + `client/TargetRegistry.java` | 统一目标抽象：本地回波与 BVR 航迹合成同一份候选/选择/锁定列表；本地目标多一个已加载实体，其余（范围/新鲜度/拖锁/FOV 规则）完全一致。BVR 目标在 ACQ/TRK 期间由 `refreshBvrPaint` 每 tick 续期涂抹，等同数据链连续跟踪。 |
| `network/RadarSyncPacket.java` | 客户端→服务端唯一锁定同步：`targetId`（本地）+ `targetUuid/targetSource`（BVR）。`BvrLockPacket` 已删除，BVR 锁不再是独立包，无“设置/解除”双状态。 |
| `radar/ServerRadarState.java` | `LockState.bvrTargetUuid`；`update(...targetUuid, targetSource)` 统一入口，`validateBvrTarget`（同维度、不限目标类别（地面/海面也可）、无 LOS 遮挡、16..1000 b、须活着）后写入；`canLaunchArh` 在 BVR 锁时放行，`canLaunchSarh` 在 BVR 锁时要求导引头达到 TRACKING 以上（与本地同规）；`getBvrTarget` 按 UUID 解析实体。 |
| `client/BvrState.java` | 缓存航迹，30 tick 未更新过期；`hasOwnMissile`/`ownMissile` 用于发射线延长；`Paint` 涂抹记忆（波束扫过才建立/刷新、180 tick 过期，同本地回波），未扫到的航迹面板不显示也不更新。 |
| `client/RadarHudClientTicker.java` | 统一锁定生命周期：`beginLockAcquisition(TrackedTarget)` → `updateLockAcquisition`（BVR 跳过扫描新鲜度、持续续涂抹）→ `completeLock`（切 STT，`lastMode` 同步防止下一 tick 清锁）→ `updateContinuousTrack`（本地/BVR 共用 ±120°/±60° 门限与拖锁宽限）；Alt+F 走 `TargetRegistry.bestLockCandidate`；发射线 UUID 延长、导弹在飞判定合并 BVR 航迹；`updateBvrPaints` 用与本地扫描相同的波束窗口判定 BVR 航迹是否被扫过。 |
| `client/RadarHudRenderer.java` | 无专属 BVR 视觉：`trackingView = RadarHud.isTrackingView()`；ACQ/TRK 框、模式字、面板高亮全部走统一 `lockedTargetUuid/lockedTargetSource`；主屏远程锁定画普通绿色锁定框+数据（`TargetRegistry.find` 实时位置）。`ACQ BVR`/`TRK BVR` 模式字样删除，复用 `ACQ PD`/`TRK PD`。 |
| `missile/MissileArrow.java` | 数据链支持 BVR：`datalinkValid` 在 `state.targetId()<0 且 bvrTargetUuid==target` 时有效（ARH 专用）；`updateDatalinkTarget` 按 UUID 切目标；数据链测量噪声沿用。 |
| `missile/SarhMissileArrow.java` | 照射条件 = STT 硬锁定 或 BVR 远程软锁 uuid 命中飞行中目标；射程/视线遮挡照常，否则摆尾失照。 |
| `command/SensorDebugCommand.java` | `/minethunder test bvr start|clear`：600 格（钳制已加载区块）带标签的 BVR-TEST-7 幻影，缓慢接近玩家。 |

## 流程

### 航迹显示
1. 服务端每 2 tick 对每个开雷达的玩家过滤并发送 `BvrSyncPacket`。
2. 客户端 `BvrState` 缓存；**显示与普通雷达同规**：只有扫描波束实际扫过航迹的方位/仰角时才建立/刷新涂抹快照（与本地回波同一窗口判定、180 tick 记忆），**涂抹快照同时冻结命中瞬间的面板方位/距离（相对当时视轴）**——转视角时未锁定的 BVR 点不动，直到波束再次扫到（与本地点完全一致的「扫到才更新」）；同规还有两条本地扫描过滤器——目标已可本地追踪时做视线遮挡剔除（地形后不点亮）、GMI 模式要求目标水平速度达 `GMI_MIN_HORIZONTAL_SPEED`；已锁定的 BVR 目标例外，保持 TRK 连续实时更新。面板点亮度与本地接触同公式按 notch（相对径向速度）调制。
3. 面板按普通目标样式绘制没有本地回波（`RadarHud.echoes` 命中）的航迹，颜色/符号与本地目标一致，被本地回波覆盖的不重画。不显示超当前量程的目标（量程档 600/1000 覆盖）。
4. 航迹位置在涂抹时刻取 `lagCompensatedPosition`（服务端 2 tick 延迟 + 客户端 tick 外推）快照。
5. **ACM 不做超视距搜索**：ACM 模式内不涂抹、不显示 BVR 航迹（`updateBvrPaints` 与渲染循环都直接跳过）；进入 ACM（Alt+W）自动解除远程锁并丢弃涂抹记忆。
6. 测试目标若落在客户端追踪距离内（≤ 视距区块数），会被本地扫描管线覆盖为普通接触（TWS 持续预测、每扫描行更新）——这是本地优先的正确行为；要观察 BVR 涂抹冻结需用 >追踪距离的靶（如 400-600 b）。
7. **Alt+T 循环候选 = 本地回波 ∪ 已涂抹 BVR 航迹**（跳过自己/本机导弹/被本地回波覆盖/未涂抹/模式不收的种类，按涂抹距离排序）；选中 BVR 点样式与本地一致（SRC 线模式白高亮+竖线、TWS 白圆点+航向线）；TWS 段内 BVR 不参与循环（TwsTrackManager 结构原因，维持本批决议）。

### BVR 远程锁定（弹种不限）
1. **Alt+F**：本地无有效回波（或 TWS 航迹无效）时回退到 BVR：雷达锁定属雷达功能，**不区分弹种**（发射时才按弹种校验）；只能选**已被波束涂抹且处于新鲜度内**（`scanFrameTicks()+20`，与本地手动锁定同规）的 AIR 航迹，**16..1000 b**（与服务端校验一致），优先扫描中心 ±60° 且更近者，进入 `bvrAcquiring` 20 tick。
2. 雷达未开提示「雷达未开启」；ACM 内不提供远程锁定，提示「无有效回波」；不再有静默失败路径。
3. ACQ 阶段与本地锁定插画一致：四角闪烁框 +「ACQ PD」字样，锚定被扫到的涂抹位置；尝试期间 BVR 目标由 `refreshBvrPaint` 每 tick 续涂抹（等同数据链连续跟踪，不再要求物理波束持续命中），否则提示「尝试锁定失败」解除；`completeLock` 后 `currentMode=STT` 与本地 TRK 完全一致，锁定的目标 UUID+来源随 `RadarSyncPacket` 同步（服务端校验 AIR、16..1000 b、同维度后写入 `ServerRadarState`）。TRK 阶段与本地锁定同款绿框+参数，面板目标白色高亮。
4. 本地硬锁/TWS 建立时静默放弃 BVR 锁；BVR 航迹过期/失活由统一 `updateContinuousTrack` 拖锁（宽限 20 tick）。Alt+F 再按解除。
5. 与导引头的分工：**雷达弹（SARH/ARH）导引头与 BVR 软锁/TWS 软锁通用；IR 弹与是否 BVR 无关**——IR 只看热源/距离/视线，雷达锁定仅辅助瞄准，不保证成锁。
6. 被锁目标的 RWR 显示 `T`（跟踪）威胁。

### SARH/ARH 发射 & 数据链
1. 客户端发射门限统一走导引头模型：ARH 需白/红倒计时环（`seekerModel.canLaunchArh`）、SARH 需红色双环（`canLaunchSarh`）、IR 需热源锁定；本地/BVR/TWS 软跟踪不再有单独的 ≥10 tick 门控。
2. `MissileFirePacket` 携带 `bvrTargetUuid`；服务端跳过本地回波“扫描命中”校验（BVR 目标本无本地回波），同时跳过本地类别/模式与方位包线校验（服务端 `validateBvrTarget` 已按航迹校验，避免落地目标本地判类不一致误拒），射程/遮挡照常；`canLaunchSarh` 对 BVR 远程锁要求导引头 TRACKING 以上（远程 TRK = 持续照射）。服务端所有拒绝路径都通过 actionbar 返回原因，不再静默失败。
3. 导弹出膛即有目标实体（服务端按 UUID 解析）；ARH 中段按 `datalinkValid` 维持数据链；SARH 中段照射 = 发射方 STT 硬锁 或 BVR 远程软锁 uuid 持续命中（搜扫失涂后超延长计数才失照爆散）。
4. 目标丢失（死亡/尾流失效）后按 IOG 惯性飞行、ARH 末段 `seekerActivationRange` 自主搜索。

### 引导线延长
- `RadarHud.guidanceMissileUuid` 在发射时记录（本地无实体时取 BVR 自机导弹航迹）。
- 导弹超出客户端实体范围后用 BVR 航迹（ownerUuid==自己）更新引导线起点 az/dist；目标端用 BVR 锁定航迹。
- `missileInFlight = 本地实体在飞 || BvrState.hasOwnMissile(自己)`，命中/自爆后两端航迹同时消失。

## 测试

```
/minethunder test bvr start   # 前方 ~600 格生成 BVR-TEST-7（区块保活 + 手动登记）
/minethunder test bvr status  # 打印枚举标签靶/注册表/ticket/快照/可见/雷达 计数
/minethunder test bvr clear
```

- 测试靶生成时登记进 `BvrTicketService` 手动注册表：sweep 按 UUID 直接续 ticket，
  连续 10 轮（约 2.5 s）解析不到才除名撤票，不再因单次实体枚举遗漏而丢目标。
- `BvrTrackManager.collect` 快照 = 实体枚举 ∪ 注册表目标（UUID 去重）；
  飞行生物（幻翼等）强制 `BvrKind.AI`，不受低空支撑扫描分类影响。

1. 切雷达弹（SARH 或 ARH）、开雷达、开导引头；Alt+F 锁定远端目标（模式字 ACQ PD → TRK PD）。
2. 面板 600/1000 档出现普通样式目标（同本地回波符号）；锁定后主屏出现普通绿色锁定框 + 名称/距离/接近率。
3. 发射雷达弹（ARH 数据链 / SARH 照射）：引导线沿导弹航迹扩展，pitbull/照射命中后正常打靶。
4. 对方（被锁玩家）RWR 出现 `T`。
