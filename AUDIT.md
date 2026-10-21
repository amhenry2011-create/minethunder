# MineThunder 逻辑与科学性审计

日期：2026-10-21  
范围：当前 `main`（审计时提交 `087ddcf`）  
结论：构建通过，但存在影响命中、发射、存档与多人权威性的逻辑问题。  
已确认不修改：太阳热源优先锁定且导弹不对太阳近炸，这是预期设计。

## P0：直接影响命中/发射

1. **径向速度单位错误，SRC PD 几乎全部误判 Notch**
   - `RadarCore.scan` 中 `radialVel` 是 blocks/tick。
   - `DOPPLER_NOTCH_THRESHOLD = 5.0` 和 `RadarContact` 注释使用 m/s。
   - 40 m/s 接近率只有 2 b/tick，必然低于 5。
   - `RadarHudClientTicker.updateContinuousTrack` 又把锁定后的 Notch 硬编码为 false，扫描与硬锁状态不一致。
   - `lockedClosure`、`SeekerLockModel`、HUD/SensorDebug 的 closure 单位也混用。
   - 修复：扫描和连续跟踪统一乘 20 转 m/s，用共享 Notch 计算，HUD 不再硬编码。

2. **IR 绕过导引头 FOV 和 rateMax**
   - `MissileArrow.datalinkValid()` 对 IR 无条件返回 true。
   - `applyGuidance` 因此跳过 `rateMaxDegPerSec`，并使用 `angleMaxDeg=35°` 而不是 `seekerFovDeg=8°`。
   - ARH 进入 pitbull 后也会因雷达状态存在而绕过 12° 末段 FOV。
   - 修复：IR 永远不是数据链；ARH pitbull 后以导引头为准；SARH 也应用导引头 FOV/rate。

3. **导弹撞实体不触发战斗部**
   - `MissileArrow` 未重写 `onHitEntity`，`AbstractArrow` 先造成普通箭伤害并 discard。
   - 随后 `tick()` 因 `isRemoved()` 返回，`detonate()` 不执行。
   - 修复：重写 `onHitEntity/onHitBlock`，统一引爆。

4. **发射初速重复计算载机速度**
   - `startSpeed=0` 时 `initialSpeed()` 返回载机速度，随后又把载机速度向量与同速相对速度相加。
   - 40 b/s 载机会得到约 80 b/s 初速，提前量也按错误速度计算。
   - 修复：载机速度 + 相对离架速度，并用合成后的地速算提前量。

5. **雷达弹服务端权威不足**
   - `RadarSyncPacket` 直接信任客户端 `targetId/hardLock/seekerState/trackQuality`。
   - `ServerRadarState.isLocked/canLaunchArh/canLaunchSarh` 不要求 `radarOn`。
   - `MissileFirePacket` 雷达路径不检查最大距离、方位/俯仰、目标类别。
   - 修复：服务端清理/校验同步状态，所有发射判定要求雷达开启，增加目标类别、距离、角度与 LOS 校验。

6. **导弹制导状态不持久化**
   - 无 `addAdditionalSaveData/readAdditionalSaveData`。
   - 重载后丢失 `targetId`、SARH `shooterId`、age、distanceFlown、pitbull、last-known track、test 标记。
   - 修复：持久化关键制导、目标、SARH 照射者、ARH 模式与测试标记。

## P1：机制与科学一致性

7. **地面杂波实际是死代码**
   - 所有可进入模式要么 `isSurfaceSearch()`，要么 `isDoppler()`。
   - `RadarCore.applyClutterMask` 永远返回 false；`updateClutter` 和 `drawClutter` 永远清空/跳过。
   - 修复：删除死杂波系统，或明确当前全 PD 模式不产生杂波；保留 `isNearSurface` 给 ARH 下视使用。

8. **失锁/末段视场不完整**
   - ARH pitbull 后目标出视场仍可能返回原目标并被精确制导。
   - IR 没有 LOS/遮挡/`prolongationTimeMax` 失锁状态。
   - SARH 在发射者离线或目标卸载时提前返回，失锁计时不增加，3 秒自爆不执行。
   - 修复：统一 seeker lost-lock 状态；ARH pitbull 只使用导引头测量/IOG；SARH 任何照射失效都累计计时；IR 增加 LOS 与延续时间。

9. **雷达制导目标同步可被伪造**
   - `RwrPaintPacket` 与雷达同步包均来自客户端，服务端只做有限几何检查。
   - 修复：服务端校验目标存在、类别、距离、角度、LOS；同步包做范围/NaN 清洗。

10. **能量模型问题**
    - `mass` 未使用，推力按 `massEnd` 计算，初段加速度偏高。
    - 诱导阻力量纲错误，直接按加速度/速度扣速度。
    - 无重力且 loft 抬升不消耗能量。
    - 修复：按时间插值质量；推力用当前质量；诱导阻力乘 `dt`；加入爬升能量惩罚。

11. **发射提前量与导引头视场冲突**
    - 初始提前量最多允许 60° 偏转，IR seeker 仅 8°。
    - 修复：初始方向限制在 `lockAngleMax` 内，IR 不超过可用离轴角。

12. **近炸与干扰弹**
    - 近炸只检查 tick 末距离，可能高速穿过。
    - 干扰弹无库存消耗、无 LOS 检查。
    - 修复：用本 tick 扫掠线段/目标 AABB 判定；干扰弹保留无库存限制，假目标要求 LOS。

13. **热源阈值与文档不一致**
    - 阈值 100；普通怪物 90、动物 70、亡灵 30，文档中的多数热源无法锁定。
    - 保留现有阈值设计，但更新文档；玩家/燃烧目标仍可锁。
    - 太阳优先且不近炸保持现状。

## P2：状态、性能与维护

14. **TWS 质量虚高、回波分类残留**
    - `TwsTrack.confirmed` 永远 true，`hits` 未使用；质量保底 0.7。
    - 目标落地后最多 9 秒仍按旧 `targetClass` 显示。
    - 修复：按连续命中确认；按当前 `RadarCore.classify` 重新过滤。

15. **选择/锁定未按当前模式过滤**
    - `selectNextTarget/toggleLock/lockNearestContact` 可选中当前模式隐藏的地面回波。
    - 修复：统一 `echoAllowed` 过滤模式、类别和存活状态。

16. **IR HUD 雷达关闭时消失**
    - `ClientHudEvents` 雷达关闭只画 RWR，IR 锁定环一起隐藏。
    - 修复：雷达关闭时仍绘制 IR 导引头环/锁定框。

17. **静态方块目标不可见且扫描开销大**
    - 静态 Marker 只在 `ACQ_PD` 可见，但 `currentMode` 从不进入 ACQ_PD。
    - `BlockRadarTargetManager` 每 20 tick 每玩家扫描约 4 万方块。
    - 修复：SRC PD 允许静态目标；降低扫描频率/范围。

18. **RWR/NaN/静态 Map**
    - `RwrPaintPacket` 可伪造；`trackQuality` NaN 未过滤。
    - `COOLDOWNS/BOOST_START/MARKERS` 登出后不清理。
    - 修复：输入清洗、登出清理、太阳 Marker 换维度清理。

19. **代码与文档残留**
    - `AircraftRadar`、`MissileLauncher`、`RadarTrack`、旧弓路径基本未使用。
    - `STATUS.md` 仍写 Forge；部分 HUD 文档仍提 6 倍外环。
    - 修复：清理或标注旧路径，更新文档。

## 验证清单

- 构建：`./gradlew build --no-daemon --console=plain`
- SRC PD：正常接近率不再全部 Notch；硬锁后 Notch 状态与扫描一致。
- IR：8° FOV/rateMax 生效；失去 LOS 后按延续时间失效。
- SARH：失去照射/目标卸载后 3 秒自爆。
- ARH：pitbull 后目标出 FOV 会丢失，不再用精确位置。
- 导弹撞实体/方块触发战斗部。
- 雷达弹：雷达关闭/目标超距/目标类别不符/超角度拒绝发射。
- 存档重载后导弹仍保留目标和制导状态。
- 干扰弹不要求库存，保留服务端冷却；无 LOS 假目标不生效。
- TWS 质量只在连续命中后提升；落地兔子按 GROUND 过滤。
- IR HUD 在雷达关闭时仍显示（如果导引头开启）。
- 构建、复制 jar、commit、push。

## 本轮修复状态

- Notch/closure：扫描、连续跟踪、HUD、SeekerLockModel 统一为 m/s；锁定后重新计算 Notch。
- IR/SARH/ARH：IR 不再伪装数据链；SARH/ARH 末段使用导引头 FOV/rateMax；IR/SARH 增加失锁延续；ARH 出视场不再用精确位置。
- 导弹：重写实体/方块命中引爆；初速不再重复叠加；战斗部、制导目标、SARH 照射者、ARH 模式、IR 失锁计时写入 NBT。
- 能量：推力按当前质量积分；诱导阻力乘 dt；爬升加入势能惩罚；初始提前量限制在 lockAngleMax。
- 雷达：删除失效杂波系统；SRC PD 显示静态方块目标；降低方块扫描频率；TWS/面板/锁定选择按当前分类过滤。
- 服务端：雷达关闭清除状态；同步值清洗；发射校验目标类别、距离、角度、LOS；RWR 搜索上报增加视图锥检查。
- 干扰弹：不要求库存，保留服务端冷却；组合键在脚底抛射 3 箔条 + 3 热焰弹；假目标要求 LOS；寿命持久化。
- RWR：方位/仰角从弧度修正为度；NaN 质量清洗；登出/换维度清理。
- HUD：雷达关闭仍显示 IR 导引头环；TWS confirmed 基于连续命中；IR 外环恢复 6×视觉并按外环判定（有效约 73.9°）。
- 太阳：保持优先锁定、不近炸的既有设计；改用带客户端跟踪的自定义 `SunHeatMarker`，解决原版 Marker 不同步导致无法锁定。
- 清理：删除未使用的 `AircraftRadar`、`RadarTrack`、`MissileLauncher`。
- TWS：改为最多 6 条航迹，单软锁；面板圆点/朝向；调试显示扫描棱柱 + 每条航迹的跟踪棱柱。
- 传感器调试：改为世界空间 3D 棱柱，删除屏幕箭头；STT/ACQ/搜索/TWS 按模式选择棱柱。

## 已知架构边界

- 雷达扫描本身仍在客户端执行；服务端现在校验雷达开启、目标存在/类别、距离、角度和 LOS，但不做独立雷达扫描。若要多人反作弊，需要把扫描/航迹也迁到服务端。
- 太阳的热源优先与不近炸行为保持用户确认的设计，不作为缺陷处理。

