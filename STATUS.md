# MineThunder 开发状态文档

> 更新时间：2026-10-21
> 项目位置：`/Users/henry/code/minethunder`
> 平台：Minecraft Java 1.21.1 / NeoForge 21.1.236

---

## 一、当前进度总览

| 项目 | 状态 |
|------|------|
| 设计文档 SPEC.md | ✅ 完成（含雷达/导弹/对抗/杂波/键位） |
| NeoForge 骨架 | ✅ 完成，可正常解析配置 |
| 核心逻辑代码 | ✅ 完成（雷达/导弹/对抗/HUD 骨架） |
| Gradle 构建 | ✅ 已通过，产物 `build/libs/minethunder-0.1.0.jar`（约 115 KB） |

---

## 二、已完成的代码文件

### 雷达系统（`com.minethunder.radar`）
| 文件 | 说明 |
|------|------|
| `RadarMode.java` | 雷达模式枚举（SRC/SRC PD/SRC PD HDN/SRC PDV HDN/SRC MTI/TWS/TWS HDN/ACM/STT），对齐战雷官方模式 |
| `RadarContact.java` | 一次探测结果（距离/方位/仰角/径向速度/遮挡/多普勒盲区） |
| `RadarTrack.java` | 目标航迹（含距离门关联 `withinRangeGate`） |
| `RadarCore.java` | 探测核心：波束张角、LOS 遮挡、多普勒盲区、抬头过滤、地面/海面杂波掩蔽 |
| `AircraftRadar.java` | 机载雷达：模式切换、锁定（STT）、发射条件校验 |

### 导弹系统（`com.minethunder.missile`）
| 文件 | 说明 |
|------|------|
| `MissileSpec.java` | 导弹参数模型，**参数名对齐战雷 CDK Missile Variables** |
| `MissileSpecs.java` | 三种典型弹默认参数（SARH/ARH/IR） |
| `MissileArrow.java` | 导弹箭实体基类：比例导引制导、动力段/滑翔、近炸引信、导引头视场角限制 |
| `SarhMissileArrow.java` | 半主动弹（依赖发射方 STT 照射） |
| `ArhMissileArrow.java` | 主动弹（末段导引头开机 pitbull） |
| `IrMissileArrow.java` | 红外弹（全程追热源） |
| `MissileLauncher.java` | 发射校验（锁定/遮挡/多普勒盲区/射程/红外热源） |

### 对抗措施（`com.minethunder.countermeasure`）
| 文件 | 说明 |
|------|------|
| `Countermeasure.java` | 类型枚举（Chaff/Flare + 威胁类型） |
| `CountermeasureEntity.java` | 假目标实体（欺骗来袭导弹） |

### 客户端（`com.minethunder.client`）
| 文件 | 说明 |
|------|------|
| `RadarHud.java` | HUD 状态（面板/锁定状态/弹种/导引头角度） |
| `RadarHudRenderer.java` | 方形面板 + 波束扫描 + 导引头圆环 + 锁定环渲染 |
| `KeyBindings.java` | 键位绑定（战雷默认键，注册进 MC 设置可改） |
| `KeyInputHandler.java` | 键位处理 |
| `ClientHudEvents.java` | 手持弓时渲染雷达 HUD |

### 注册
| 文件 | 说明 |
|------|------|
| `MineThunder.java` | 模组主类 |
| `Registration.java` | 物品 + 导弹箭实体注册 |

---

## 三、战雷机制调研结论（已从官方 wiki 核对）

### 3.1 雷达显示（Airborne radars wiki）
- **B-Scope（方形面板）**：横轴=方位，纵轴=距离。用户要的"方形雷达面板"就是这个。
- **PPI**：扇形顶视。**C-scope**：前视矩形（方位×仰角）。
- **杂波**：战雷里地面/海面杂波显示为**绿色 haze**覆盖 PPI 区域，越严重越浓，足以遮蔽目标。
- **Notch（39线）**：脉冲多普勒雷达下，目标 3/9 点钟垂直机动使径向速度趋近 0，锁丢失。
- **下视**：SRC 模式向下看时地面杂波淹没目标；SRC PD/MTI 可下视。

### 3.2 导弹 HUD 元素（CDK Missile Variables wiki）
- **红外弹两个白环**：小环 = `fov`（视场角），大环 = `lockAngleMax`（发射前离轴角）。
- **雷达制导弹锁定环**：仅锁定期间显示，发射后消失（用户确认）。
- **雷达锁定框**：目标周围方框 + 距离/接近速度读数。

### 3.3 导弹关键参数（战雷 CDK 变量名）
`fov`、`lockAngleMax`、`angleMax`、`rateMax`、`gateWidth`（距离门）、`prolongationTimeMax`（丢失延续）、`startSpeed`/`endSpeed`、`maxDistance`、`timeLife`（制导时长）、`force`/`timeFire`（动力段）、`mass`/`massEnd`、`CxK`（阻力）、`reqAccelMax`/`loadFactorMax`（最大过载）、`timeOut`（直飞时间）。

### 3.4 当前 Demo 风格键位
- `B`：雷达模式循环
- `R` / `Alt+R`：雷达开关
- `右Shift`：扫描中心复位
- 方向键：扫描方位/俯仰
- `Alt+F`：锁定/解锁
- `Alt+T`：选择/切换目标
- `X`：解除锁定
- `Alt+Q`：距离档位（40/100/300/600/1000 b，10 b 已移除）
- `Alt+D`：扫描宽度 40x20/120x10/120x20（默认 120x20；240x10 已移除）
- `Alt+W`：ACM 切换
- `Alt+X`：雷达导引头开关
- `Alt+E`：同时释放箔条+热焰弹
- `I`：红外模式
- `C`：切换导弹类型（额外保留）
- `CapsLock`：挂架选择
- `H`：按键帮助
- `M`：雷达菜单显示
- `Ctrl+Alt`：发射提示（实际发射仍为拉满弓松手）
- 雷达默认开启，不要求手持弓；弓只作为发射架。

---

## 四、构建环境（已解决）

构建已通过。当前镜像与缓存配置：

- `settings.gradle` / `build.gradle`：优先使用 CreeperHost Forge 镜像，其次 BMCLAPI、Aliyun Public。
- `gradle.properties`：关闭 ForgeGradle 仓库附加、放宽 HTTP 超时。
- 手动缓存了 `installertools-1.2.0-fatjar.jar`（CreeperHost 下载）与 lwjgl-freetype macOS native。
- MCP 配置、Minecraft client/server、Forge userdev 已缓存；后续 `./gradlew build` 只需十余秒。

验证命令：

```bash
cd /Users/henry/code/minethunder
./gradlew build --console=plain
ls build/libs
```

构建日志：`/tmp/mt-build70.log`。

## 五、下一步计划

1. **验证编译与构建**：✅ `compileJava` 和 `build` 均已通过。
2. **接入雷达 HUD**：`SeekerLockModel` 红/白稳定环、`SeekerRingSlew` 抛放、惯性重投影与导弹引导线均已接入；`AircraftRadar` 尚未作为独立实例驱动 HUD（当前由客户端扫描 + 同步状态）。
3. **导弹发射链**：✅ 弓发射 → 导弹箭 → 目标写入 → 制导 → 近炸/自爆；SARH 失锁惯性/自爆、ARH pitbull、IR 导引头已接。
4. **对抗**：✅ Chaff/Flare 服务端实体、Alt+E 组合键一次释放两种、导弹诱饵。ECM 已按用户要求删除。
5. **键位→逻辑**：✅ 锁定/模式/弹种/雷达/菜单/挂架/对抗；发射由弓完成。
6. **运行验证**：构建通过；`/tmp/SeekerLockTest.java`、`/tmp/SeekerRingTest.java`、Demo 测试通过；尚未做 `runClient` 游戏内实测。
7. **OP 调试工具**：`/minethunder sensor` 中文传感器视觉已接入，待游戏内检查标签布局与遮挡。

## 六、需要用户确认/待补充的细节

1. **跟踪稳定环与发射授权**：已确认环不是倒计时。白色单圈表示导引头搜索/未建立有效导引头锁；对 **SARH 半主动雷达弹禁止发射**。红色稳定环（亚稳定）和红色双圈可发射并命中；环仅表示跟踪稳定度/包线状态，命中率随品质变化。
2. **导弹 HUD 元素最终视觉**：需要战雷截图对照（wiki 文字描述有限）。
3. **数据链/GMI/SEA 模式的具体实现范围**：首版是否全做。

---

## 七、Mod 真实性待办（Demo 不模拟）

1. **TWS 惯性导航**：惯性外推会产生“位置看似可发射、实际命中率下降”的发射解算；Mod 中需要航迹不确定度、惯导漂移和发射授权判定，不能把惯性框当作精确目标位置。
2. **雷达探测**：RCS/信噪比、扫描时序、距离/角度门限、地面/海面杂波、地形 LOS、多普勒 Notch。
3. **TWS 航迹管理**：航迹起始/关联/滤波/超时、扫描调度、自动扫描边界。
4. **ACM**：视场/离轴限制、距离相关锁定概率与耗时、失败条件、取消后复锁。
5. **导弹**：SARH 照射、ARH 末段开机、IR 热源/Flare、FOV/万向角、比例导引、能量模型、近炸引信、发射包线。
6. **HUD**：白/红锁定圈时序、发射后显示、横向发射评估线与不可逃逸区、地平线/陀螺仪稳定参考线。
7. **RWR**：多辐射源威胁库、地面搜索雷达模型、搜索/锁定/发射告警。
8. **对抗措施**：Chaff/Flare、39 机动、能量规避。ECM 已删除。
9. **多人同步**：服务器权威的雷达/锁定/导弹/对抗状态与延迟处理。
10. **其他模式**：数据链、GMI/SEA、空对地/对海、键位配置、性能与平衡。
11. **导弹提示线（发射包线）**：最大有效射程、不可逃逸区上下边界、最小可控/转弯距离必须由当前导弹的 MissileSpec 与目标状态（高度/速度/接近率）实时计算，不能写死；Demo 目前用导弹 profile 示意随弹种切换，Mod 需接入真实包线/动力/导引头数据。

12. **跟踪稳定环逻辑**：白/红与稳定度由目标相对机头的离轴角、距离、接近率、导引头 FOV/万向角、Notch、导弹包线共同决定；转向导致目标出视场时退回单白圈，指回后按品质重建稳定环。SARH 在单白圈禁止发射，红色稳定环/双圈可发射；Mod 必须用真实几何实时计算，并加进入/退出滞回避免闪烁。
13. **Mod 导引头模型进度**：`SeekerLockModel` 已接入 `RadarHudClientTicker` 与 `RadarHudRenderer`：锁定目标后由 `SeekerRingSlew` 抛环，未对准前不进入截获/稳定；HUD 按 SEARCHING/ACQUIRING/TRACKING/HIGH 显示白单圈、白建立弧、红稳定环、红双圈。SARH 在 SEARCHING 时禁止发射；服务端已通过 `RadarSyncPacket` + `MissileEvents` 做最终校验。
14. **导引头环抛放动画**：`SeekerRingSlew` 已接入 ticker/renderer，目标切换或解锁时回到屏幕中心；环对准后 `SeekerLockModel` 才开始累积。已验证环最终能到达目标并置 `aligned=true`。
15. **雷达弹外圈**：Demo 与 Mod HUD 均不再绘制雷达导弹的最外层大环（IR 模式保留）；中间锁定环已由 `SeekerLockModel`/`SeekerRingSlew` 驱动；服务端发射判定已接入同步锁定状态。
16. **导引头惯性小圈与导弹自爆**：绿色小圈在导引头进入惯性时记录最后目标世界位置/速度；随后每 tick 按当前机头姿态重新投影，机头转动时小圈会移动（不再固定屏幕点）。雷达拖锁时若导弹仍在飞行也保持该点，导弹命中或自爆后删除。Demo 已实现（命中/自爆提示 + 4 秒弹道结算）；Mod HUD 与 `MissileArrow` 已接入自爆/实体结束检测。

17. **服务端锁定同步与发射校验**：新增 `com.minethunder.network`（`RadarSyncPacket`、`CountermeasurePacket`）与 `ServerRadarState`。客户端每 20 tick 或状态变化时同步锁定目标、导引头状态、雷达模式；服务端在 `MissileEvents` 中校验锁定目标、SARH 白单圈、射程、扫描角、地形遮挡、PD 多普勒盲区，再允许弓发射。`MissileItem` 在生成导弹时写入服务端锁定目标与 SARH 发射者。

18. **对抗措施**：`Alt+E` 同时释放箔条与热焰弹，不占用单独快捷键；服务端生成两个 `CountermeasureEntity`（箔条=雪球外观，热焰弹=火焰弹外观；寿命 100 tick，组合键 10 tick 冷却）。导弹在导引头视场内优先追踪能欺骗自己的假目标；箔条对雷达弹、热焰弹对红外弹有效。ECM 已按用户要求移除（旧存档火药诱饵按箔条读取）。

19. **SARH 失锁**：`SarhMissileArrow` 读取服务端锁定状态；失锁后不再修正航向，超过 `prolongationTimeMax` 自爆。锁定恢复则重新制导。

20. **ACM 与 RWR**：ACM 模式自动截获机头前方 12 km、±10° 内最近目标；新增简化 RWR，敌方导弹以本机为目标时在屏幕上方显示红色 `MSL`。

21. **导弹引导线**：导弹飞行期间雷达面板从中心到目标/惯性预测点绘制虚线；ARH 按 `seekerActivationRange` 在线上绘制小横杠（pitbull 点）。引导线终点与惯性小圈共用同一世界位置投影，机头转动时一起移动。

22. **仍待完善**：数据链/GMI/SEA 模式、TWS 多目标航迹管理、真实服务端雷达扫描权威（目前服务端校验锁定目标但雷达扫描仍在客户端）、更多游戏内实测。

23. **导弹制导时间自爆**：所有 `MissileArrow` 在 `age / 20.0 > spec.timeLife` 时立即 `detonate(null)`，不做无控惯性坠落；该规则已在代码与本文档中固定。

24. **OP 传感器视觉**：命令 `/minethunder sensor`（权限等级 2）切换。开启后客户端渲染雷达波束扇形与回波连线（距离/方位/仰角/径向速度/遮挡/Notch）、导弹（弹种/速度/高度/剩余制导/飞行距离/近炸半径/目标）、干扰物（类型/剩余时间/速度/距离/欺骗类型），全部中文标注。仅 OP 可开启，状态通过 `SensorDebugPacket` 同步，退出登录自动清除。

25. **HUD 层注册修复**：根层 `minecraft:vanilla_root` 不能直接以 `minecraft:hotbar` 作为 `addAbove` 锚点（hotbar 位于 `PRE_SLEEP_STACK` 子层）。最终改为 `ForgeLayeredDraw.add(命名层, layer)` 把雷达/挂架/传感器层追加到根层末尾；此前日志中的 `Expected layer ... was not found` 警告已消除。

26. **Demo 键位与雷达开关**：已切换为 Demo 风格键位（见 §3.4）。`Q/D/W/T` 与移动/丢弃/聊天/背包冲突，因此使用 Alt 组合；`Alt+Q/Alt+F/Alt+T` 会清掉对应原版点击，避免丢物品、换手或打开聊天。雷达不再被“手持弓”状态每 tick 覆盖，默认开启并持续扫描，`R` 切换开关。挂架键从 CapsLock 改为 Enter，避免切换输入法。

27. **HUD 缩放与整理**：雷达面板/锁定圈/武器条按 1920×1080 设计稿随窗口缩放（`HudScale`）；移除左侧模式列表和文字控制条，只保留雷达面板、中心环、目标框、武器条、RWR、底部提示。雷达面板在用户 854×480 窗口下约为 135px。

28. **交互修复**：Alt 单独按下释放鼠标、松开重新捕获；干扰弹服务端冷却从 10 tick 降到 2 tick，客户端连发间隔 80ms；`Alt+E` 仍一次释放两种干扰物。

29. **创造模式物品**：导弹物品额外加入原版“战斗”创造页，并把模组创造页排到“战斗”页之前，避免自定义页翻页/搜索时无法取出。若仍异常，下一步需要检查客户端/服务端注册同步。

30. **HUD 对齐 Demo**：雷达面板/主视图按 Demo 结构补上扫描带、扫描拖尾、光栅、仰角标尺、本机图标、扫描光标、发射提示线、完整发射评估线、顶部信息、罗盘、RWR、TWS 提示、结束提示、帮助面板、控制按钮和按钮显示模式；按需求不绘制发射动画和地面搜索雷达站。面板设计尺寸改为 360，缩放下限 0.30，小窗口隐藏武器条，底部只保留单行提示。

31. **实体搜索**：`RadarCore.scan` 从只检测 `LivingEntity` 改为检测所有存活实体（排除雷达载体自身），因此物品、导弹、干扰物、船、矿车等也会进入雷达/锁定流程。

32. **挂架键与导引头修正**：挂架键使用新的 key id `key.minethunder.rack_select_enter`，避免旧 `options.txt` 里保存的 CapsLock 覆盖新默认值；现在默认 Enter。导引头默认关闭，雷达导引头未开启时不绘制中心环/锁定环；IR 导引头只在拉弓开机后绘制。导引头环尺寸下限提高，避免过小。Alt+X 增加轮询兜底，X 在无锁定时也可开关导引头。

33. **按反馈精简**：删除主屏幕扫描网格和雷达面板上的可点击按钮；雷达面板只保留一条左右往返的竖线扫描。雷达检测所有存活实体并取消地面杂波过滤，羊、物品、导弹、船、矿车等都能出现在雷达中。按此前要求仍不绘制发射动画和地面搜索雷达站。

34. **扫描线与面板数字位置**：横/竖扫描光栅改到主画面（`renderScanOverlay`），雷达面板只保留一条左右往返的竖线；面板内不再画横向扫描线。模式/距离移到面板上方，仰角数字移到面板左侧外，0 km 移到面板下方外。
35. **锁定框与测试用实体**：导引头关闭时仍显示锁定方框/目标信息，但不画稳定环；稳定环只在导引头开启后绘制。地面杂波已恢复，贴地羊会被杂波遮住；幻翼是飞行生物，通常不受地面杂波影响，适合作为雷达测试目标。
