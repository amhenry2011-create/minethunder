# MineThunder 交接 / 下次继续

日期：2026-10-23
状态：BVR 面板坐标「扫到才冻结」+ 锁定不问弹种 + Alt+T 循环含 BVR 航迹修正。已构建待实测。

## 2026-10-23 四轮：锁定不问弹种 + BVR 面板坐标「扫到才冻结」+ Alt+T 循环含 BVR 航迹（最新）

### 用户反馈与决议
- "雷达锁定不需要弹种的区分" → `beginBvrLockAcquisition` 删除 SARH/ARH 弹种门控（发射时才按弹种校验），任何弹下 Alt+F 都可发起远程锁。
- "现在我要的逻辑是波束扫描到目标再更新！一转视觉雷达还没扫到目标就跟着动" → 实因：本地面板点用命中时刻冻结的 az/dist（转视角不动），而 BVR 点渲染时实时用当前视轴重算方位。修复：`BvrState.Paint` 增加 `azDeg/range`（涂抹时刻面板坐标，方位相对当时视轴、右侧为正），`updateBvrPaints` 快照时冻结，`drawContacts`/`drawTwsContacts` 未锁定 BVR 点直接用冻结坐标；锁定（TRK）仍连续实时。
- "涂龄又是啥，删掉" → `bvrLockedPaintAge` 字段 + 锁定框「涂龄 N」读数 + updateBvrInfo 更新点已整体删除。
- "alt+t 没法切换目标" → 实因：Alt+T 只循环本地回波；360b 靶在客户端追踪距离外无本地回波 → 候选列表恒空 →「无有效回波」。修复：`selectNextTarget` 候选合并 BVR 涂抹航迹（跳过自己/被本地回波覆盖/未涂抹/模式不收的种类），按涂抹距离排序循环；`validatePanelSelection` 对 BVR 选中态做存活校验（丢弃自己/未涂抹/模式不收/TWS 内 BVR）；渲染给选中 BVR 点补本地同款标记（SRC/横线模式竖线、TWS 白圆点样式一致）。

### 本轮改动
1. `BvrState.Paint(position, velocity, azDeg, range, lastPaintTick)`；`updateBvrPaints` 快照写冻结坐标。
2. 渲染两处 BVR 循环：未锁定点绘制用 `paint.azDeg()/range()`；锁定点实时（TRK 规则不变）。
3. Alt+T 选中链路：`selectNextTarget` 合并候选 + `validatePanelSelection` BVR 校验 + 渲染两处选中标记。
4. 删除「涂龄」诊断（字段/框绘制/更新点）。
5. 文档 `BVR.md`（弹种不限 + 面板坐标冻结 + Alt+T 判定）、`HANDOFF.md`。

### 待办/下次优先
- 实测转视角：未锁定 BVR 点应保持不动，波束扫到才跳位（与本地点一致）。
- 实测 Alt+T：360b 幻翼靶应能循环点选（白圆点/高亮）；SRC 行回访约 14t 刷新一次。
- 消息行为唯一反馈源（左下白字、3 秒新鲜度）：若 Alt+T 仍无切换，看行内是否出现「无有效回波」。

## 2026-10-23 三轮：消息行/SARH 入 BVR/涂抹同规/导弹强加载

### 用户反馈与决议
- “按 Alt+F 没反应，屏幕又出现两排相同文字” → 双排=外置白名单又走一遍 `displayClientMessage` 与 HUD 行重复渲染；按用户要求**不去白名单**，只去掉重复的 actionbar 渲染；消息行改白色、放左下角右移一点。
- “超视距空战半主动雷达弹也可以干啊” → BVR 弹种从仅 ARH 放宽为 SARH/ARH（IR 仍只走本地热源）。
- “令所有导弹强加载” → 导弹（MissileArrow 及子类）field ticket 一律持票不再受 64 上限，远离玩家的标签远景目标仍限 64。

### 本轮改动
1. **消息行**：`notifyClient` 白名单分支变 no-op（信息只出现在 HUD 行）；`renderSeekerMessage` 改白色（0xFFFFFFFF）、x=`24*ui`（左下角右移）、y=`h-22*ui`。
2. **SARH 参与 BVR**：`beginBvrLockAcquisition` 拒 IR 提示「远程锁定需雷达弹：请切换弹种」；`KeyInputHandler` 发射路径 `radarGuided`（SARH/ARH）共享 `bvrLock/bvrUuid`，SARH 有远程 TRK 时只需导引头开机 ≥10 tick（本地硬锁路径不变）；`ServerRadarState.canLaunchSarh` 增加 BVR 远程软锁放行分支；`SarhMissileArrow.resolveTarget` 照射条件加 BVR 软锁 uuid 命中（远程 TRK=持续照射），射程/遮挡照常。
3. **BVR 涂抹与锁定同规**：`updateBvrPaints` 加视线遮挡剔除（目标可本地追踪时）与 GMI 水平速度门（`GMI_MIN_HORIZONTAL_SPEED`）；渲染两处 BVR 点亮度按相对径向速度 notch 调制（`bvrEchoAlpha`，与本地接触同公式、无盲区基线 0.7）；`tickBvrAcquisition` ACQ 期要求涂抹保鲜（`scanFrameTicks()+LOCK_ACQUIRE_TICKS+20`，失败「远程尝试锁定失败」，与本地 ACQ 同规）。
4. **导弹强加载**：`BvrTicketService.sweep` 导弹无条件 `ensure`、AI/标签目标按剩余候选槽（≤64）；溢出撤销永不撤导弹票（`MISSILE_TICKETS` 集合）。
5. 文档：`BVR.md`（弹种/同规规则/组件表/测试）、`HANDOFF.md`。

### 待办/下次优先
- 实测：重启后 C 键切弹种 → `/minethunder test bvr start` 用 600 b 靶验证 SARH/ARH 两条 BVR 锁定与发射链路；注意 216 b 靶在客户端追踪距离内会本地优先（TWS 持续预测属于正确行为）。
- 若仍无锁定反馈，先确认是「弹种=IR」还是「无有效回波」（本轮所有提示走 HUD 单行白字）。

## 2026-10-23 BVR 闪断 + 投射物锁定 + ACM 三波束 + IR 环修正

### 用户反馈与决议
- "生成目标成功后 src pd 模式依然没有扫描到目标" → 进一步："生成出目标雷达有一条短横线但是一瞬间就消失了" → 判定为服务端快照在最初几拍后停止包含该靶（客户端 30 tick 过期把断源放大成闪现）。轻量确诊后修复：`BvrTicketService` 曾依赖 `getAllEntities()` 枚举做 stale 判定，一次枚举遗漏即撤票→区块落地→永久丢失。
- "雷达似乎无法锁定雪球" → `RadarCore.classify` 对非生物/非导弹返回 null，海选 `mode.accepts(null)` 静默过滤。
- "ACM 扫描范围与框的大小不符合" → 旧 ACM 检测是每 tick 全框扫描、框只画 ±3~6° 小角框，视觉/范围脱节。用户拍板三模式：0 正前静止、1 上下往复（旋转 `[` 端帽）、2 3×3 光栅（格=±3°、网格±9°），扫描 ±60° 俯仰极限、图标允许屏幕裁剪。
- "红外弹锁定太阳仰头到极限，小圈会移动（飘离太阳方向）" → 旧代码对 x/y 独立钳屏，方向被扭；改为沿目标方向径向钳制（大圈内缘/屏幕边界最近点），锁定判定不变。

### 本轮改动
1. **`BvrTicketService`**：新增手动注册表（`registerManualTarget`，`test bvr start` 调用）+ `TICKET_MISSES`；每 sweep 对注册表/持票 UUID 用 `findTrackedEntity`（跨维度 `level.getEntity`）兜底续票，连续 10 次解析失败才撤票/除名；`manualCount/ticketCount/manualTargets` 供诊断。
2. **`BvrTrackManager`**：快照 = 枚举 ∪ 注册表并集（UUID 去重）；飞行生物（`FlyingMob`/`Vex`/`Parrot`）强制 AI；新增 `status(server, viewer)`。
3. **`SensorDebugCommand`**：`test bvr start` 登记注册表，测试靶 `setInvulnerable(true)+setPersistenceRequired()`（防白天燃烧/清除导致闪现）；新增 `test bvr status`（枚举标签靶/注册表/ticket/快照/可见/雷达）。
4. **`RadarCore.classify`**：`Projectile → AIR`（雪球/蛋/箭/火球可被扫描、锁定、雷达弹锁定）。
5. **ACM 三波束**：`RadarHud.acmBeamIndex`（0/1/2）+ 每模式 effective 半角/行数；`updateScanState` ACM 分支 `updateAcmBeamState`；检测模式 0 整框 ±3°、模式 1 当前行窄窗（az ±3°、el 行高）、模式 2 当前格窄窗（az ±1.5°、el ±3°）；`KeyInputHandler.cycleScanWidth` ACM 内 Alt+D 循环波束；`renderScanOverlay` ACM 分支 `drawAcmBeamOverlay`（三模式框/端帽/格线/波束，屏幕裁剪）。
6. **IR 小环**：`renderCenterRings` IR 分支方向保持径向钳制（`min(大圈内缘, 屏幕边界/方向向量)`），不再 x/y 独立钳制。
7. 文档：`TWS.md` ACM 段、`BVR.md` 测试段、`SEEKER_HUD.md` IR 段同步。

### 待办/下次优先
- 实测 `test bvr start` 后 600/1000 档是否持续显示；若仍闪断跑 `test bvr status`（服务端 枚举/注册表/ticket/可见/雷达 数字）发回定位。
- 实测雪球锁定（ACQ/TRK、ACM 截获）、ACM 三模式框/波束一致性、IR 锁太阳仰极限小环贴边。
- ACM 检测真实波束化后截获更慢（尤其模式 1/2），如嫌慢可调 `updateAcmBeamState` 的 period=5 或行宽。

## 2026-10-22 BVR 面板/主屏显示 + 导引头修正（上一轮）

### 用户反馈与决议
- "开 bvr 验证雷达界面没有目标" → 根因：客户端本地扫描拉不到 600 格实体，BVR 面板绘制在上一轮裁剪中被整删，`BvrState` 航迹同步存在但无人绘制。→ 用户的“不要多余 UI”指**不要琥珀新区视觉**，不是不要目标显示。
- 新显示决议：面板 BVR 航迹全部按普通目标样式显示（未锁定 80% 透明度绿/锁定白亮，同当前 mode 符号，本地回波优先，TWS 面板同款圆点+航向线，不参与 Alt+T 选中）；BVR 远程锁定主屏画与本地 TRK **完全相同**的绿色框（名称来自 `BvrTrack.name()`，速度/航向由 `track.velocity()` 推导，格式与本地一致；ACQ 阶段不画框）。
- "雷达弹在 20 格内还是没触发双环" → 旧跳段阈值 0.85/80 在 MC 场景达不到（悬停低闭路时 quality≈0.72；ARH 死对轴≈0.83）。
- "摆动=朝向摆到非理想位置应减进度" → 用户澄清摆动指**把导引头/机头轴摆离目标**：进度需随之回退。

### 本轮改动
1. **`SeekerLockModel`**：跳段改为 `q≥0.60 且 ≤40 b → HIGH`、`q≥0.80 且 ≤80 b → HIGH`、`q≥0.55 且 ≤120 b → TRACKING`；离轴 >35% 万向角起 `progress/stability` 按 `(off/gimbal-0.35)*0.9/秒` 回退；TRACKING 离轴 >75% 或质量跌破回白圈；HIGH 离轴 >60% 退回红倒计时（stability=0.5）。ACQUIRING 进度≤0 直接回 SEARCHING。
2. **`RadarHudRenderer`**：
   - `drawContacts` 尾部加 BVR 航迹循环：跳过自己/本机导弹/本地新鲜回波重影；`mode.accepts(kind→AIR/SEA)`；量程内画普通符号（icon 模式 `RadarIconType` 同款+运动短线，横线模式同款横线），STT 只显示锁定者；无琥珀/无顶边刻度/无选中竖线。
   - `drawTwsContacts` 尾部：BVR 空中航迹同款圆点+`drawHeadingLine`（速度版）。
   - `renderTargetLock`：新增 BVR 分支（普通绿框+名称/距离/接近率/速度航向，`worldToScreen(null)` 时隐藏）；ACQ 分支要求本地实体（远程 ACQ 不画框）。
   - `drawMotionVector` 拆出 `(velocity, targetPos)` 重载，echo 版委托。
3. `BVR.md`/`SEEKER_HUD.md` 同步；后端未动。

### 待办/下次优先
- 实测：`/minethunder test bvr start` → 600/1000 档面板普通样式目标 → Alt+F → TRK PD + 主屏绿框 → ARH 发射全流程。
- 实测导引头：20 格悬停靶死对轴开导引头应直接红双环；把机头拉开（离轴 >35%）进度应肉眼倒退，>75% 万向角掉白圈。
- 若"近距双环"还嫌慢，可再把 `SKIP_TO_HIGH_QUALITY` 降到 0.5 或提高 rangeMargin 权重。



## 2026-10-22 BVR UI 裁剪 + 导引头通用化（上一轮）

### 用户决议
- “我不想要这些多出来的ui” → 回放 pre-BVR 渲染器基线（`74b5a8f`），但用户又明确“我需要超视距功能”→ **保留全部 BVR 后端管线，移除 BVR 专属视觉**。
- 面板/主屏 BVR 相关：不再画琥珀空心符号、主屏 1000b 标记、顶部刻度；`ACQ BVR`/`TRK BVR` 模式字样删除（复用 `ACQ PD`/`TRK PD`）。保留的功能性 HUD：`drawGuidanceLine` 支持 `bvrLockedUuid`、导弹点支持 `guidanceMissileUuid` 数据链航迹。
- 导引头新规（用户原话）：“导引头锁定逻辑 trk、tws 模式都是通用的，注意只有硬/软锁定才能打开导引头”。

### 本轮改动
1. **RadarHudRenderer 回到 pre-BVR 基线**（`git checkout 74b5a8f -- ...`），再补最小改动：RWR 字母/MSL 标签居中（移植）、BVR 模式字样复用 ACQ/TRK PD、`lineVisible`/导弹点两处功能性分支。
2. **`updateRadarSeeker` 通用目标源**（`RadarHudClientTicker`）：硬锁定 > TWS 当前软跟踪（`TwsTrack` 实体，含 alive 校验）> BVR 远程 TRK（`BvrState.lagCompensatedPosition`，无本地实体，sourceId 取 UUID 负哈希避免与实体 id 冲突）。`closure` 按源取：硬锁 `lockedClosure`、TWS `twsTrack.closure`、BVR `bvrLockedClosure`。换源/失源时重置 `seekerRing`/`seekerModel`。
3. **`SeekerLockModel` 近距离跳段**（战雷观感：信号强度=距离/对轴/接近率）：SEARCHING 时 `quality≥0.85 且 range≤80` 直接 `HIGH`（红双环）；`quality≥0.70 且 range≤150` 直接 `TRACKING`（红色倒计时常开）；否则白色截获段不变。
4. **渲染器软锁绘制**：`renderCenterRings` 在 TWS 软跟/BVR 锁时不再提前 return（旧缺陷：环根本不画），走 `drawRadarLockLayer`（环套在跟踪目标位置）；无任何源时仍中心白圈。`drawRadarLockLayer` 门限改为“硬锁/TWS 选中/BVR 锁任一存在”。
5. 发射校验不变：ARH=TWS 软跟/BVR 锁 `seekerEnabledTicks≥10`，本地硬锁 `seekerRing.aligned()`；SARH 服务端仍要求硬锁+TRACKING/HIGH。

### 待办/下次优先
- 实机验证：TWS 软跟开导引头应见环套跟踪目标（白色→红倒计时→红双环）；BVR 远程锁 ARH 开导引头同样有环与进度；近距离打开环可直接红倒计时/红双环。
- 构建后 jar 覆盖 `/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge/mods/`；测试命令 `/minethunder test bvr start` 可用（BVR 后端/测试命令均保留）。
- `BVR.md` 的 UI 描述段落已过时（琥珀符号被删），下次窗口可顺手改为“无专属 BVR UI，复用普通锁定 UI”。

---

### 本轮已完成（按用户要求“干所有的”）
1. **删除 ECM**：`Countermeasure` 只剩 CHAFF/FLARE；`CountermeasurePacket` 去掉 kind=2 映射与 gunpowder 分支（旧存档火药诱饵按箔条读取）；`SensorDebugCommand.test cm ecm`、`SensorDebugRenderer` ECM 文案、`FullFeatureTest` 相关 spawn/文案全部移除；文档（STATUS/SPEC/REQUIREMENTS）已同步。
2. **RWR/RWR 面板文字居中**：`RadarHudRenderer.renderRwr` 字母与 `drawMslLabel` 改浮点坐标 + `lineHeight` 垂直居中。
3. **导弹区块 ticket + 低速看门狗**：新建 `radar/BvrTicketService.java`（`TicketType.FORCED` radius=2 forceTicks；`MissileArrow` 与 `minethunder_bvr` 标签实体每 5 tick 维护，上限 64）；`MissileArrow` 低速看门狗：非测试弹、点火后、`age>40`、速度<15 m/s 持续 40 tick 自爆，`slowSpeedTicks` 已入 NBT（`MTSlowTicks`）。

4. **BVR 全管线**（详见 `BVR.md`）：
   - 服务端：`BvrTrackManager`（2 tick 快照：玩家/带标签实体/导弹，1000 b，非本机导弹 600 b）+ `BvrSyncPacket`；`ServerRadarState` 增 `bvrTargetUuid`（AIR、16..1000 b 校验）；`BvrLockPacket` 远程软锁；`canLaunchArh`/`MissileFirePacket`（新 `bvrTargetUuid` 字段）支持 BVR 发射；ARH 数据链 `updateDatalinkTarget`/`datalinkValid` 按 UUID；RWR 对方被 BVR 锁时显示 T。
   - 客户端：`BvrState` 缓存（30 tick 过期）；面板空心琥珀符号（菱形/圆/方框，超量程钳边+顶部刻度）、主屏 1000 b 内空中目标标记、`ACQ BVR`/`TRK BVR` 模式字样；Alt+F 无本地回波时回退 BVR 锁（20 tick，ARH-only，`seekerEnabledTicks>=10` 即可发射）；发射线用 `guidanceMissileUuid` + BVR 自机导弹航迹延长；`missileInFlight` 合并 BVR 航迹；被锁玩家 RWR 显示 T 威胁。
   - 测试命令：`/minethunder test bvr start|clear`（600 格 BVR-TEST-7 幻影，区块 ticket 保活，缓慢接近）。
   - 导弹/目标 ticket 保证数据链目标在服务端一直 tick。
5. **测试目标初速**：`FullFeatureTest.spawnTargetMissile` & `SensorDebugCommand.testtarget missile` 给 0.25~0.3 前向初速，不再 `Vec3.ZERO` 悬停。

### 已知限制 / 下次优先
- BVR 目标模型不可见（客户端视距硬上限），只有航迹符号与数据链；后续如需远景可评估 Distant Horizons（可选，不做成依赖）。
- `BVR.md` 的测试步骤未实机验证；下次窗口先开游戏跑 `/minethunder test bvr start` 全流程（锁定→发射→延长引导线→RWR T）。
- `BvrTrackManager.filter` 目前不基于方位门限，只按距离过滤；如出现面板混乱可加与 `scanAzimuthOffsetDeg` 相关的门限。
- `PR/样式`：主屏 BVR 标记统一在 `RadarHudRenderer.renderBvrOverlay`；若用户想换成面板-only需求，需在 AGENTS.md 规则下先询问归属（面板 or 主屏）。

### 构建状态
- 本轮构建后 jar：`build/libs/minethunder-0.1.0.jar`（见 git log 的 release 提交），已复制到 `/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge/mods/`。
- 分支 `main`，已推送 origin。

---

## 2026-10-22 窗口关闭交接（上一轮）

### 代码/构建状态
- 当前分支 `main`，HEAD `74b5a8f`，已推送 `origin/main`；工作区在写本文前是干净的。
- 构建：`./gradlew build --no-daemon --console=plain`，成功。
- Jar：`build/libs/minethunder-0.1.0.jar`，SHA-256 `4897eb010e0794a264d5ef2218d40dafb9d8aa1bb8f8d50e6113ed2181be3be7`。
- 安装路径：`/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge/mods/minethunder-0.1.0.jar`，已覆盖，需重启游戏加载。
- 当前游戏进程仍在运行（HMCL/Minecraft）；不要热替换已加载的 jar。

### 本轮已完成
- 雷达搜索改为逐行窄波束：3° 方位 × 5° 仰角；同一行覆盖扫过段，换行不跨行。
- 默认搜索档 `120x20`（±60°/±10°，4 行），移除 `240x10`，普通搜索移除 `10 b` 档。
- TWS 逐行刷新；STT 跳过通用搜索；ACM 保留每 tick 全 ACM 框自动截获。
- `RadarCore.scanBeam` 使用窄波束 AABB；服务端发射/RWR 继续全扇区 `scan`。
- 传感器 3D/2D 波束加入 partial tick 和扫描线插值，修复移动/转视角抽搐。
- 雷达归中改为单独右 Shift（`KeyBindings.RECENTER`），旧 `Alt+V` 移除。
- 新增测试命令：
  - `/minethunder test full start|next|stop|status`
  - `/minethunder test targets`
  - `/minethunder test dodge arh|sarh|ir`
  - `/minethunder test rwr all` 及原有 rwr 子命令
  - `/minethunder test cm chaff|flare|ecm|all`
  - `/minethunder test kit`
- `FullFeatureTest` 会临时搭建水域/方块测试场地，`stop` 恢复原方块状态。

### 未完成的用户需求
1. **删除 ECM**：用户明确不要 ECM。需删 `Countermeasure.ECM`、`CountermeasurePacket` kind=2、`CountermeasureEntity` 火药映射、`FullFeatureTest`/`SensorDebugCommand` 的 `cm ecm`、`SensorDebugRenderer` ECM 文案、相关文档。旧的 gunpowder 诱饵建议按箔条处理，避免存档实体报错。
2. **RWR 圆圈字母居中**：当前 `RadarHudRenderer.renderRwr` 用整数坐标 `cx - width/2`、`cy - 4`；改为浮点 `drawString`，水平 `cx + 0.5f - width/2f`，垂直按 `font.lineHeight` 居中；必要时加深色底圆。代码没有旋转字形。
3. **导弹卡在空中**：
   - `MissileArrow` 没有区块 ticket；服务端模拟距离/客户端视距外会停止 tick 或不同步。
   - 滑翔段阻力可把速度降到 0，导弹会悬停到 `timeLife`。
   - 全功能测试里的 `spawnTargetMissile` 使用 `Vec3.ZERO`，测试目标本身静止。
   - 计划：导弹/目标区块 ticket + 低速看门狗（非测试弹低速 20–40 tick 引爆）+ 测试目标给缓慢初速。
4. **BVR**：见下节。

### BVR 调研结论
- 客户端渲染距离/区块跟踪有硬限制：`ChunkMap.TrackedEntity.updatePlayer` 要求目标区块被客户端跟踪；`clientTrackingRange(80)` 只是上限，不能绕过客户端视距。服务端 simulation distance 外不 tick。
- 1000 b ≈ 62.5 个区块，常规客户端最大视距 32 区块；因此不能靠单纯加区块 ticket 实现“1000 b 完整可见”。
- **FarSight**（Modrinth `farsight.`，GitHub `dark-yoddha/FarSight`）：最接近“超视距”的现有实现，Paper 服务端插件，动态加载玩家之间区块 + ghost chunks，让原版客户端看更远。**不兼容 NeoForge，且 All Rights Reserved**；只能借鉴“只看必要走廊/幽灵区块”的思路，不能直接安装或复制代码。
- **Distant Horizons**（Modrinth `distanthorizons`，LGPL-3.0）：NeoForge 1.21.1 可用，提供远距离地形 LOD；**不加载/tick 实体，也不解决导弹和锁定**。可作为可选远景视觉层。
- **Bobby ReNeoForged**（Modrinth `bobby-reneoforged`，LGPL-3.0）：NeoForge 1.21.1 的 Bobby 非官方移植；缓存/显示服务器已发送区块，不能扩展服务端实体模拟，也不能单独实现 BVR。
- **Entity View Distance**：Fabric only，且只能调整实体渲染距离，不能绕过服务端模拟/视距。

### BVR 推荐方向
- 不要把任何“超视距模组”做成硬依赖；否则服务器/其他玩家没装就失效。
- 推荐自研 **BVR HUD/航迹层**：
  1. 服务端维护可 BVR 的目标（玩家、带 `minethunder_bvr` 标签的 AI/测试目标、飞行中的导弹）。
  2. 每 2 tick 发送远程航迹（UUID、位置、速度、类别、距离/方位）到客户端。
  3. 客户端雷达面板/主画面显示远程符号；600/1000 b 外不要求模型和地形。
  4. 导弹/目标用区块 ticket 保持服务端 tick；导弹可用自定义 HUD 图标显示，不要求客户端实体渲染。
  5. `RadarSyncPacket`/`ServerRadarState` 从 int targetId 扩展到 UUID，支持远程锁定；这是中大型重构。
- 可选接入 Distant Horizons 作为纯客户端远景视觉，但 BVR 功能不依赖它。
- 对未加载的任意 AI，不能凭空检测；AI 目标需要区块 ticket/标签/测试场地。若必须全图 AI，BVR 不现实或需大幅加载。

### 下一窗口优先顺序
1. 删 ECM（低风险）。
2. 修 RWR 字母居中（低风险）。
3. 导弹区块 ticket + 低速看门狗 + 测试目标初速。
4. BVR：先做“玩家/测试目标远程航迹 + 导弹 HUD”最小版本；再考虑远程锁定/发射。
5. 每次改动后构建、复制 jar、提交并推送（按 `minethunder-auto-release` skill）。
6. 实机测试需重启游戏；不要热替换运行中的 jar。

## 2026-10-22 现实扫描

- 普通搜索默认档改为 `120x20`（±60° 方位、±10° 仰角、4 行）；`240x10` 档位移除。
- 普通搜索删除 `10 b` 距离档；ACM 的独立距离档保留 `10/20/40/100/300 b`。
- 搜索检测改为逐行窄波束：3° 方位 × 5° 仰角，同一行内覆盖上一 tick 到当前 tick 的扫过段；换行不跨行连线。
- TWS 改为逐行刷新；航迹未命中时由速度和回波记忆预测，不再每 tick 全扇区刷新。
- STT 跳过通用搜索扫描，直接连续跟踪锁定目标；ACM 保留每 tick 全 ACM 框自动截获。
- 锁定候选/尝试时限改为一个扫描周期 + 20 tick，适配 4 行约 84 tick 的扫描周期。
- 客户端窄波束使用 `RadarCore.scanBeam` 的保守 AABB；服务端发射校验和 RWR 仍使用全扇区 `RadarCore.scan`。
- 雷达归中改为单独右 Shift（`KeyBindings.RECENTER`），旧 `Alt+V` 已移除；HUD/帮助/语言文件同步。
- 传感器视觉的 3D/2D 波束渲染加入 partial tick 和扫描线插值，修复移动/转视角时抽搐。
- 新增 `/minethunder test full start|next|stop|status`、`targets`、`dodge`、`cm`、`kit` 和 `rwr all`；测试场地临时方块在 stop 时恢复。

## 下次先读

1. `LOCK_LOGIC.md`：Demo 原始锁定逻辑 + 本轮现实化参数。
2. `TWS.md`：当前雷达/TWS/导引头实现。
3. `.opencode/skills/minethunder-context/SKILL.md`：项目上下文。
4. 本文件：当前状态、已知风险和待办。

## 当前关键行为

- 手动 `Alt+F`：
  - 从已扫描回波选最近目标，进入尝试锁定。
  - 在发起时的近似面板位置显示固定的四角闪烁框，不跟随目标移动。
  - 20 tick 后成功，实际切换 `RadarMode.STT`，HUD 显示 `TRK PD`。
  - 面板目标：亮绿色圆点；主画面：绿色方形框 + 速度/距离/接近率。
  - 解锁/拖锁恢复锁定前的搜索模式。
- 自动锁定：
  - TWS：保留自动当前软跟踪目标标记。
  - ACM：保留自动截获，成功后直接进入 TRK PD。
  - 除以上外，不再自动绘制尝试锁定框。
- 39/Notch：
  - 阈值 `5 m/s`。
  - 近距离烧穿 `0–50 b`。
  - 模式敏感度：SRC 0；MTI 0.7；ACM 0.85；SRC PD/TWS/STT 1.0。
  - 39 只降低回波亮度、锁定质量和刷新，不再硬隐身/硬拒绝发射。
  - 无更新时惯性跟踪、渐隐；5 秒无更新才拖锁。
- ACM：
  - 搜索位置固定，方向键不能平移。
  - `Alt+D` 禁用，扫描宽度固定 ±3°。
  - `Alt+Q` 可调整 ACM 搜索距离，默认 100 b。
- 导引头：
  - 目标锁定环只在导引头开启时显示。
  - 雷达弹没有外环（转动范围）。
  - 红外弹外环更大，允许超出屏幕裁切。
  - 雷达弹锁定环要求 `Alt+X` 开启 + 雷达锁定。
  - 红外视觉不要求满弓/弹药/锁定；锁定环仍需红外导引头开启。

## 构建与安装

- 加载器：**NeoForge 21.1.236**，ModDevGradle 2.0.148，Java 21。
- 构建：`./gradlew build --no-daemon --console=plain`
- NeoForged Maven 在当前网络不可达；构建使用 CreeperHost 镜像 + `local-maven`/本机 Minecraft libraries workaround，详见 `MIGRATION_NEOFORGE.md`。
- 偶发 Gradle 启动卡住：重试一次，必要时 `./gradlew --stop`。
- Jar：`build/libs/minethunder-0.1.0.jar`
- 安装：`/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge_21.1.236/mods/minethunder-0.1.0.jar`
- Forge 旧工程备份：`/Users/henry/code/minethunder-forge-backup-20261019`
- 最近构建大小：约 131 KB；以实际文件为准。
- 默认不自动启动游戏，用户自行重启验收。

## 下次实机验收清单

1. 重启游戏加载新 jar。
2. `/minethunder testtarget` 生成目标。
3. SRC/PD/TWS/ACM 观察扫描线命中才点亮。
4. `Alt+F`：确认固定四角框、20 tick 左右后 TRK PD、面板亮绿点、主画面方形框 + 参数。
5. 停止扫描更新：确认目标/锁定逐渐变暗、惯性保持、5 秒后拖锁。
6. 近距离 0–50 b 做 3/9：确认仍可探测/锁定，只是质量下降。
7. TWS：确认只保留一个当前跟踪目标，其他为普通回波；`Alt+T` 切换；自动标记仅 TWS。
8. ACM：确认自动截获保留；方向键不能移动；`Alt+D` 提示固定；`Alt+Q` 可改距离。
9. 导引头：雷达弹开启+锁定才显示锁定环、无外环；红外弹外环更大且可被裁切。
10. 解锁/拖锁后确认恢复原搜索模式。

## 已知风险 / 待办

- ACM 解锁后可能立即被自动截获重新锁定；考虑增加短冷却（类似 Demo 的 `acmUnlockAt`）。
- 尝试锁定成功后没有严格检查目标是否离开初始近似门限；如需要可增加“目标偏离锚点过多则失败”。
- 39 参数、0–50 b 烧穿、渐隐速度需要实机手感调整。
- 服务端仍不是权威 TWS；当前以客户端锁定/质量为准，服务端只做基础校验。TWS 服务端权威仍后置。
- GMI/GMTI 没有独立模式；当前只有 MTI。若用户要 GMI，需要单独立项。
- `RadarHudRenderer.drawStrobe` 仍保留但已不再调用；可在清理时删除。
- Verifying actual jar load after restart is still pending.

## 用户偏好

- 中文沟通。
- HUD 尽量贴近 Demo；不要随意加功能。
- 不自动启动游戏，除非明确要求。
- 用户会实机验证并反馈；改动后构建+复制 jar。
- 新增/修改 Skill 后需要重启 opencode 才能重新加载。

## 2026-10-19 新增待办（明天改）

1. **导引头未开启时不应显示导引头环。**
   - 当前红外弹外环/内环在待机状态也可见；明天改为：
     - 雷达弹：`seekerEnabled` 开启且雷达锁定后才显示雷达中心环/锁定环。
     - 红外弹：`seekerActive`（红外导引头开启）后才显示外环/内环/锁定环；待机时只有状态文字或什么都不显示。
   - 注意与“红外弹视觉取消前置条件”的旧要求冲突；以本条最新要求为准。

2. **雷达面板普通目标选择样式：一条横线 + 左右各一条竖线。**
   - 两侧竖线**只在雷达面板**显示，不属于目标焦点传感器；目标焦点传感器以后单独实现。
   - 正常探测到目标：面板目标画短粗横线 + 左右两条竖线。
   - 多个目标时，默认第一个目标带左右竖线；切换选中目标时，竖线移动到新目标。
   - 竖线长度约为横线的 1.5 倍，只在普通目标选择阶段使用。

3. **`Alt+B` 模式循环删除 TRK。**
   - STT/`TRK PD` 不再由 `Alt+B` 循环进入；只通过手动 `Alt+F` 尝试锁定成功后进入。
   - 检查 `KeyInputHandler.nextMode()`，确保不会从正常搜索模式循环到 STT。
   - 退出 TRK PD 后仍恢复锁定前的搜索模式。

4. **完整锁敌视觉流程（修正版）。**
   - 普通选择阶段（面板）：横线 + 左右竖线；多个目标默认第一个，Alt+T 切换后竖线跟随新目标。
   - 进入锁定：主画面显示类似 ACM 的四角闪烁框；尝试锁定框固定在发起位置。
   - 锁定成功：主画面变成绿色正方形边框；雷达面板目标变成亮绿色圆点。
   - 两侧竖线只在普通目标选择阶段、且只在面板上出现；不用于锁定阶段。
   - 39 逻辑一致：锁定/拖锁使用同一 Notch 质量模型，5 秒无更新拖锁。

## 视觉位置规则（2026-10-19 补充）

- **尝试锁定的四角闪烁框画在主画面/屏幕上的目标位置，不画在雷达面板。**
- 雷达面板只画搜索回波、目标选择（横线 + 左右竖线）；TRK PD 成功后面板为亮绿点。
- 以后用户说“绘制某种样式”时，必须先确认：
  - 画在雷达面板，还是主画面/屏幕；
  - 固定位置还是跟随目标。
  - 除非用户已经明确说明。

## 2026-10-19 实现进度（NeoForge / 烟花弩阶段）

已完成：

- NeoForge 21.1.236 工程迁移，构建成功。
- 新增 `MissileCrossbowItem`、`MissileFireworkItem`、三种导弹烟花与创造标签页。
- 烟花弩支持装填/发射自定义导弹烟花；旧弓从创造标签页移除。
- 导弹实体渲染改为烟花火箭；保留烟花爆炸粒子。
- 导弹初速改为载机速度 + 初速；速度/加速度单位改为 m/s、m/s² 到 blocks/tick 的换算。
- `MissileArrow` 改为真 PN 核心：相对速度、视线角速率、最大过载、重力补偿、动力段/阻力段。
- 雷达弹/红外弹的导引头显示统一由 `seekerEnabled` 控制；关闭时不显示任何环。
- 雷达面板发射线：本机图标到目标、实时导弹位置、ARH pitbull 断口；失锁显示冻结的惯性圆圈。
- 失锁后惯性位置冻结，重新锁定后恢复更新。

仍需完成：

- 服务端启动事件在 NeoForge 环境实机验证。
- 烟花弩装填/发射/弹药消耗实机验证。
- ARH 弹载主动导引头搜索/重捕获细化（当前只追原目标）。
- SARH 照射几何、IR 目标重捕获和热焰弹细节。
- 更严格的发射线断口/惯性圆圈视觉验收。
- 旧弓代码仍在源码中但不在创造页；后续清理。

## 2026-10-19 弹药发射方式调整

- 取消烟花弩。
- 三种导弹烟花存放在背包中；`Ctrl+Alt` 第一次开启导引头，第二次直接消耗背包中对应烟花并发射。
- 发射后导引头关闭，所有环消失。
- 三种导弹烟花增加简单合成：
  - IR：烟花火箭 + 铁锭 + 火药
  - SARH：烟花火箭 + 铁锭 + 红石 + 铜锭
  - ARH：烟花火箭 + 铁锭 + 红石 + 紫水晶碎片
- 旧弓/旧箭发射路径仍保留为兼容代码，但不再出现在创造页；后续可清理。

## 2026-10-19 第二轮问题修复

- 雷达距离档扩展为 10/40/100/300/600/1000 b，默认 300 b。
- 横向扫描宽度扩展为 ±120/60/30，默认 ±120；面板方位轴同步为 ±120。已取消 ±15°、±90° 档。
- 面板选中目标改为独立 `selectedEchoId`，所有搜索模式都画横线 + 左右竖线。
- `Alt+T` 只改面板选中；不锁定、不开始 acquisition、不产生屏幕动作。
- TRK PD 为单目标：只显示锁定目标，`Alt+T` 不允许切换。
- TWS `Alt+T` 切换当前目标并移动扫描中心；屏幕不再绘制 TWS 覆盖框。
- 取消屏幕全高锁定竖线。
- 挂架选择会同步弹种/参数；发射自动按 手动 → ARH → SARH → IR 选择可用烟花。
- 红外弹锁定逻辑暂时移除：IR 只显示导引头视觉，不自动捕获目标；服务端 IR 发射暂拒绝。
- 移除导引头中心十字准星。
- 雷达弹新增导引头锁定层进度弧；删除额外绿色中心环。
- 新增 `MISSILE_LOCK_LOGIC.md`、`SEEKER_HUD.md`、`HEAT_SOURCES.md`。

## 雷达遮挡修复

- `RadarCore.isOccluded` 改为 DDA 体素射线。
- 山体/实心方块完全遮挡；树叶逐层衰减；水/岩浆逐层强衰减；玻璃/冰穿透；草丛忽略。
- 详情见 `OCCLUSION.md`。

## 2026-10-19 雷达模式重构

- 删除 SRC_MTI / MTI。
- 模式：SRC / SRC PD / TWS / GMI / SEA / ACM / STT。
- 目标分类 AIR / GROUND / SEA，按是否离地动态判定；不按物种/体型过滤。
- 空中模式只收 AIR；GMI 收 GROUND+SEA（径向速度 >=5 m/s）；SEA 只收水中船实体。
- GMI/SEA 可锁定形成 TRK PD；导弹可攻击已锁定的地面/海面目标，SRC PD 不显示地面目标。
- 屏幕扫描线改为线性映射，±120° 不再因 tan 翻转。
- 详情见 `RADAR_MODES.md`。

## 2026-10-19 扫描/ACM/导引头/导弹修复

- 扫描为每行一遍后换行，竖直方向上下往复；仰角 ±30°、7 行，非 ACM 每遍 14 tick，全帧约 8.4 秒。
- 地形杂波改为扫描线命中后短暂拖尾，不再预生成整片杂波；杂波只保留 30 tick。
- 回波记忆/拖锁从 5 秒延长到 9 秒（180 tick）。
- TWS 恢复主画面当前目标拐角框，缩小绿色锁定框；TWS/TRK PD 隐藏扫描横线和竖线，雷达朝向自动跟随。
- ACM 中央视场框缩小；默认距离改为 100 b；ACM 自动截获后保留 ACM 上下文，`Alt+W` 可退出；解锁后 2 秒冷却。
- ACM 纳入多普勒模式；小型动物（鸡、蜜蜂、蝙蝠等）不再成为雷达目标。
- 雷达导引头：中心白环保持 1 秒 → 瞬移锁位 → 白色倒计时 → 红色倒计时 → 红色双环。
- ARH 在白色倒计时阶段可发射；SARH 从红色倒计时开始可发射。
- 删除旧红色大准星；新环半径等于缩小后的锁定框半边长。
- 导弹禁用原版箭重力，修正雷达弹制导视场，修复主动弹拍地。
- SARH/ARH/IR 近炸半径统一为 4 格。
- 更新 `SEEKER_HUD.md`、`MISSILE_LOCK_LOGIC.md`、`TWS.md`、`LOCK_LOGIC.md`、`RADAR_MODES.md`。

## 2026-10-19 雷达面板/模式过滤

- 雷达面板回波改为只显示最后一次扫描命中的位置，不再在扫描间隔内预测刷新。
- 切换 SRC/SRC PD 等模式时清除新模式下不允许的旧回波，并解除不兼容锁定；地面目标不能在 SRC/SRC PD 中锁定。
- SRC 非 PD 模式下，地面/海面杂波仍会掩蔽低空目标；地面目标需使用 GMI/SEA。

## 2026-10-19 最后修正

- TWS 下 ARH 可发射；导引头白环套在 TWS 跟踪框上，pitbull 后由弹载雷达完成锁定。
- TWS 发起 TRK 锁定失败会直接回到 TWS。
- 没有硬锁定且没有 TWS 跟踪时禁止开启导引头；来源丢失自动关闭。
- 从 TWS/TRK 返回 SRC 等模式时复位扫描中心，避免 ±120° 搜索留下未扫描边。
- 雷达面板回波只在扫描命中时更新，不再在扫描间隔外推。
- SRC/SRC PD 切换会清除地面旧回波并解除不兼容锁定。
- 导弹增加逐 tick 地形碰撞检测，撞地/入水立即爆炸。

## 2026-10-19 发射与 TRK 修正

- 修正导弹 `lastPos` 初始化，修复发射瞬间误判撞地爆炸。
- 移除 `Ctrl+Alt` 两段式导引头开关延迟；满足条件时每次按键直接发射，可连续发射导弹。
- TRK PD 强制 ±120° 全范围跟踪；ACM→TRK 不再继承 ACM 的 ±3° 小扫描范围。
- TWS/TRK 回 SRC 复位扫描中心，避免 120° 扫描留下未扫描边。

## 2026-10-19 ARH 与 TRK 最终修正

- ARH 的导引头对准/倒计时改为纯视觉，硬锁定或 TWS 当前目标存在时 Ctrl+Alt 直接发射。
- SARH 保留硬锁定 + 红色倒计时门限。
- TRK PD 改为连续跟踪，每 tick 从目标实体更新，不依赖扫描线；目标失效约 1 秒后拖锁。

## 2026-10-20 发射准备与面板清理

- 已撤销 0.5 秒发射准备/动画；满足条件时 Ctrl+Alt 立即发射。
- 撤销雷达目标外的绿色通用锁定圈；雷达只显示目标方框，导引头阶段环保留。
- 切换雷达模式清空面板旧回波、杂波、选中标记和旧锁定；进入 TRK 时只保留被锁定目标。

## 2026-10-20 两段式发射与地面锁定

- Ctrl+Alt 恢复两段式：第一次开导引头，第二次校验发射，发射后关闭导引头。
- ARH 开导引头后约 0.5 秒环套到目标后才可发射；SARH 保留红色倒计时门限。
- 地面/海面目标仅允许在 GMI/SEA 建立锁定；SRC/SRC PD/TWS/ACM 统一拒绝。
- 增加脚底支撑判定，避免客户端 onGround 不同步把地面目标误判成 AIR。

## 2026-10-20 TRK 距离

- TRK PD 的搜索/跟踪距离改为与 SRC 当前距离档一致；ACM 自动截获进入 TRK 后不再固定使用 ACM 的 100 b。

## 2026-10-20 文档

- 新增 `IR_MISSILE_LOGIC.md`，记录 IR 当前状态与目标设计。
- 记录地面目标显示规则：空中模式面板不显示/不锁定，GMI/SEA 才显示；后续需实机确认过滤没有漏洞。

## 2026-10-20 面板图标与方块目标

- 雷达面板改为玩家菱形、活体圆形、导弹火箭、方块方形，并绘制运动方向短线。
- TWS 屏幕目标框删除左右竖线，保留拐角框/名称/距离。
- 增加可动方块结构 Marker：SRC 可显示静止/移动空中结构，SRC PD/TWS/ACM 只显示移动结构，GMI/SEA 按地面/海面分类。
- 方块目标可被导弹攻击；导弹 TNT 风格爆炸只破坏黏液块/蜂蜜块/TNT 等脆弱方块。
- 地形遮挡目标不再进入面板、锁定或发射；矿洞目标必须有实际 LOS 才能出现。

## 2026-10-20 面板样式范围

- 图标目标样式仅用于 TWS/ACM/TRK PD；SRC/SRC PD/GMI/SEA 恢复旧横线目标样式。
- 删除图标模式下的白色选中/锁定框。

## 2026-10-20 GMI/SEA

- GMI 改为实际水平速度 > 0.5 m/s，不再要求 5 m/s 径向速度。
- GMI/SEA 清除地形杂波；SEA 允许静止/移动船。
- SEA 目标 LOS 忽略水体衰减，山体/建筑仍遮挡。
- 新增 `GMI_SEA_LOGIC.md`。

## 2026-10-20 IR 接入与导弹物理

- IR 两段式发射、活体/热焰弹自动热源搜索、服务端目标校验接入。
- IR 大环 `lockAngleMax`、单层小环、锁定双红；雷达硬锁定优先牵引。
- ARH 0.5 秒无动力下飘后点火；SARH/IR 直接点火；极速约 48 b/s。
- 加入转弯诱导阻力、尾焰粒子和 20 秒白色烟幕。
- 方块/太阳热源仍未接入。

## 2026-10-20 IR HUD/捕获与提示清理

- IR 大环放大到约 3 倍；无锁定/候选时小环居中，有候选时移动，套住并保持 1 秒后锁定。
- IR 目标框独立绘制，不依赖雷达锁定。
- 除“需要雷达锁定”和“导引头：开启/关闭”外，删除客户端提示文字、中央状态、底部提示、H 帮助和导弹结果提示。
- 左上角按 IR/SARH/ARH 列出背包导弹数量，当前导引头弹种显示搜索/截获/追踪/锁定。

## 2026-10-20 IR 热源与 HUD

- 大环放大到约 6 倍；小圈不再自动追目标，默认居中；雷达硬锁可牵引，套住 1 秒后锁定。
- IR 目标框删除，只保留双环。
- IR 热量阈值改为 130；玩家鞘翅使用烟花获得 10 秒 +120 热度并衰减；烟花实体高热量；太阳 Marker 可锁。
- 左上角列出背包 IR/SARH/ARH 数量与当前导引头状态。
- 除必要雷达锁定与导引头开关提示外，其他提示文字已隐藏。

## 2026-10-21 扫描中心

- 所有雷达模式的扫描中心俯仰偏移上限改为 ±60°。
- 左侧仰角标尺的白色短线表示 0°/机头基准，不是扫描线。

## 2026-10-21 ACQ PD / RWR / 导弹拦截

- 保留 `SRC PD` 并设为默认搜索模式；完全删除 `SRC`。`ACQ PD` 是 1 秒临时尝试锁定状态，不进入 `Alt+B` 循环。
- `SRC PD` 扫描到目标后 `Alt+F`：目标扫描点出现 ACM 风格四角框，冻结距离档/扫描中心/扫描宽度/模式 1 秒；成功进 `TRK PD`，失败恢复原模式和扫描参数。
- 尝试锁定框不依赖当前弹种；选中 IR 弹时也显示。
- RWR 改为两层环：外环、内环、圆心十字；外环内侧 12 条短刻度代表 12 个方向。
- RWR 只报告 TWS 跟踪、STT 锁定和雷达制导导弹（SARH/ARH）；IR 不上 RWR。
- SRC PD 扫描命中本机时，通过 `RwrPaintPacket` 上报，RWR 在对应方位/距离显示带来源首字母的圆圈，2 秒淡出，不点亮“敌跟踪”。
- 跟踪/锁定圆圈常亮，来源首字母在圈内。
- 导弹不画图标，显示 `MSL` 文字和穿过圆心到外环的径向线；每枚导弹独立显示。
- 导弹进入 `600 b` 外或垂直盲区（世界仰角绝对值 > 60°）后，`MSL` 和径向线消失，“敌导弹”熄灭。
- RWR 下方三格绿框：`敌跟踪`、`敌锁定`、`敌导弹`；导弹状态快速闪烁。
- 垂直盲区按本机水平面计算，天顶/天底各约 30°。
- 新增导弹拦截：高速导弹段对导弹做扫掠距离检测，使用 4 b 近炸半径；测试弹无伤害。
- ARH pitbull 按弹目距离/目标丢失后的飞行距离开机；搜索范围 160 b。
- SARH 测试来袭弹在测试模式下可绕过载机照射。

### 测试命令

- `/minethunder testtarget`：生成雷达测试幻翼。
- `/minethunder testtarget missile`：生成雷达/ARH 测试导弹。
- `/minethunder testtarget clear`：清除 300 b 内测试导弹。
- `/minethunder test intercept`：两枚无伤害测试弹相向飞行，验证互撞/近炸。
- `/minethunder test incoming arh|sarh|ir`：生成瞄准玩家的无伤害来袭弹（SARH 测试弹可自导；IR 不触发 RWR）。
- `/minethunder test rwr search`：2 秒淡出的搜索圆圈。
- `/minethunder test rwr track`：常亮跟踪圆圈 + 敌跟踪。
- `/minethunder test rwr lock`：常亮锁定圆圈 + 敌锁定。
- `/minethunder test rwr missile`：一个 MSL + 敌导弹闪烁。
- `/minethunder test rwr missile2`：两个 MSL，检查多个目标显示。
- `/minethunder test rwr blind`：清除测试接触，模拟 MSL 进入盲区消失。
- `/minethunder test rwr off`：关闭测试源。

## 2026-10-21 导弹/TRK/RWR 修正

- TRK 只保留当前锁定目标，通用扫描不再向面板写入地面/其它回波。
- TRK 绿色锁定框不再依赖当前弹种，选中 IR 弹时也显示。
- 修复客户端导弹首 tick 从 (0,0,0) 做地形碰撞导致瞬间消失；世界碰撞/销毁改为服务端裁决。
- 导弹尾焰和烟迹改为直接粒子；SARH/ARH/IR 共用。
- RWR 刷新提高到 2 tick，新增 `/minethunder test rwr sweep` 动态 MSL。
- 雷达搜索半角全部翻倍：±20/±60/±60/±120，仰角 ±10/±5/±10/±5；ACM 宽度 ±6 起。
- 雷达面板左侧外部新增绝对俯仰角标尺（固定 ±60° 两条短线，扫描上下限随 Alt+D/扫描中心移动），当前扫描俯仰线随扫描行移动。

## 2026-10-21 导弹机动提升

- ARH 离架僵直/无动力下飘 `0.7s → 0.5s`；SARH/IR 仍为 0。
- 最大过载：SARH `35G → 70G`，ARH `50G → 100G`，IR `40G → 80G`。
- PN 制导增益 `NAV_GAIN 4.0 → 4.5`。
- 速度、射程、质量、发动机、阻力与爆炸参数不变。

## 2026-10-21 发射提前量与初始控制

- 发射初始化读取目标位置/速度，计算预测拦截点后设定初速方向；无目标时回退视线方向。
- 预测方向限制在可用离轴角附近，避免近距离横穿时侧向甩飞。
- ARH 的 0.5s 仍为无推力段，但弹翼在点火前即可按 PN 修正航向；0.5s 后点火。
- SARH/IR 同样使用预测拦截点初始化，解决不手动拉提前量时 50 b 左右脱靶。

## 2026-10-21 IR 跟随、雷达锥体与空中分类

- IR 小圈锁定后跟随目标，目标可在大圈（`lockAngleMax` 30°）内离轴；出圈、遮挡、死亡或无法投影时脱锁。
- IR 大圈改为实际 30° 绘制，不再乘 6 倍；锁定目标 ID 保留，不再每 tick 重新选择最高热源。
- IR 候选按视线角度加权，优先小圈附近热源；服务端 30° 离轴发射校验不变。
- 传感器调试：扫描线四棱锥在屏幕外时显示边缘方向箭头；SRC PD/GMI/SEA/TWS 对合法回波绘制四棱锥，选中回波加亮。
- 空中目标改为按支撑面高度判定：地面、树叶、液体、可碰撞方块均算支撑面，脚底离支撑面超过 1.5 格才算 `AIR`；短暂跳跃的兔子仍为 `GROUND`。
- 待实机验证：IR 锁定后跟随/离轴发射、30° 脱锁、SRC PD 回波锥体、兔子与飞越树叶目标的分类。

## 2026-10-21 逻辑与科学性审计修复

- Notch/closure 单位统一为 m/s：修复 SRC PD 几乎所有目标误判 Notch；连续跟踪不再硬编码 Notch=false。
- IR 不再被误认为有数据链：恢复 8° FOV 和 rateMax；SARH/ARH 末段同样受导引头限制。
- 导弹实体/方块碰撞统一触发 `detonate`；初速不再重复叠加载机速度；发射提前量限制在 `lockAngleMax`。
- 导弹目标、SARH 照射者、ARH pitbull/MPRF、IR 失锁计时等写入 NBT。
- 推力按当前质量积分；诱导阻力乘 dt；爬升加入势能惩罚；近炸改为本 tick 扫掠 AABB。
- IR/SARH 增加失锁延续；ARH pitbull 出 FOV 后不再精确追踪原目标。
- 雷达删除失效地面杂波系统；SRC PD 显示静态方块目标；方块扫描降频；TWS/面板/锁定统一按当前目标分类过滤。
- 雷达同步值清洗，雷达关闭即清状态；雷达弹发射增加类别、距离、角度、LOS 校验。
- 干扰弹每次最多消耗 3 雪球 + 3 火焰弹，在玩家脚底抛射对应数量；左上角显示剩余数量；假目标要求 LOS；RWR 方位/仰角修正为度。
- 雷达关闭时仍显示 IR 导引头环；TWS confirmed 基于连续命中；RWR/NaN/登出残留清理。
- 太阳优先且不近炸保持预期设计。
- 审计全文见 `AUDIT.md`。

## 2026-10-21 IR 外环、TWS 多航迹与 3D 传感器棱柱

- IR 双环恢复原视觉参数：外环 6×、内环 28×UI；外环是唯一锁定/发射边界，有效离轴角约 73.9°，客户端与服务端共用。
- TWS 改为最多 6 条空中航迹；同一时间只有一个软锁目标；面板为每条航迹绘制圆点与朝向，当前软锁目标带左右竖线。
- 传感器调试棱柱改为世界空间 3D：近端/远端四边形、四个侧面、远端面和线框；删除屏幕边缘箭头。
- TWS 调试显示当前扫描棱柱 + 每条 TWS 航迹的窄跟踪棱柱（最多 6 条），当前软锁目标高亮。
- STT/ACQ 显示目标棱柱；SRC PD/GMI/SEA/ACM 显示当前扫描棱柱；雷达关闭或传感器调试关闭时不显示。
- 几何参数保持原先：近端 0.65/0.24、扫描距离上限 220、远端半宽 0.45..5、目标棱柱按目标碰撞箱。
- 太阳热源改为自定义 `SunHeatMarker`，设置客户端跟踪范围；原版 `EntityType.MARKER` 不发送客户端，导致太阳无法锁定。

## 2026-10-21 雷达快捷键与平滑扫描

- 雷达模式 `Alt+B`、雷达开关 `Alt+R`、天线回中 `Alt+V`；裸 `B/R/V` 不再操作雷达。
- 扫描中心仍用方向键，不再使用 5 tick 跳 1° 的冷却；改为每 tick 连续移动。
- 平滑参数：最大 `30°/s`，加速度 `120°/s²`；松开自动减速，方位限制 ±30°，俯仰限制 ±60°。
- `Alt+V` 为瞬间回中；TWS/STT/ACM/尝试锁定期间不手动移动扫描中心，避免与自动跟随冲突。


## 2026-10-21 雷达辅助 IR 与导弹伤害

- 雷达硬锁 STT 或 TWS 当前软锁重新牵引 IR 小圈；雷达只负责辅助瞄准，IR 仍需热源、距离和 LOS 才建立锁定。
- 导弹恢复直接实体伤害 25；爆炸只保留视觉效果，使用零实体伤害计算器，避免双倍伤害。

## 2026-10-23 BVR 波束涂抹、锁定修复与 ACM 视图

- BVR 航迹改为与普通雷达同规显示：新增 `BvrState.Paint` 涂抹记忆，`RadarHudClientTicker.updateBvrPaints` 用与本地扫描完全相同的窗口（ACM 三波束/搜索窗）判定航迹是否被波束扫过，未扫到不显示、超 180 tick 回波记忆消失；已锁定目标保持实时。三个 ACM 视图调整：1号删中央横线、2号删长竖线只留上下端帽、3号删 ±3° 内部格线。
- BVR 锁定修复：非 ARH 弹种提示「远程锁定仅 ARH：请切换弹种」；距离下限从 512 改为与服务端一致的 16（新增 `ServerRadarState.BVR_LOCK_MIN_RANGE`）；候选必须已被波束涂抹；失败提示「无具备扫描记忆的远程目标」；本地回波实体失效时回退 BVR。
- IR 锁距加远：全向 34→50 格、后半球 110→160 格。

## 2026-10-23 ACM 超视距禁入与 BVR 锁定对齐

- 明确设计：BVR 不是独立模式，＝除 ACM 外所有在「视距外走数据链涂抹/锁定」的普通搜索逻辑；ACM 完全不做超视距（不涂抹、不显示、不锁定 BVR）。进入 ACM（Alt+W）自动解除远程锁（提示「ACM 截断远程锁定」，发解链包）并清空涂抹记忆。
- BVR 锁定与本地同规：候选新鲜度 `scanFrameTicks()+20`（不再用 180t 旧记忆）；ACQ 阶段画与本地同款四角闪框 「ACQ PD」（锚定被扫到的涂抹位置，替代原先 "ACQ 阶段无框"）；TRK 白框/参数与本地一致；失败提示不再静默（雷达未开 → 「雷达未开启」）。
- ACM 距离档位 `{10,20,40,100,300}` → `{100,300}`，默认 100。
- IR 弹规则确认：与是否 BVR 无关——雷达硬锁只提供小圈辅助瞄准，IR 成锁仍需热源/距离/LOS，代码无需 BVR 耦合。
- 部署注意：测试前必须重启游戏（15:04 前会话跑的是旧代码）；旧包 `minethunder-0.1.0.jar.zip` 已移出 mods 至 `~/.hmcl/backup/`。
