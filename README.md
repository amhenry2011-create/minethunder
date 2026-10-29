# MineThunder

MineThunder 是一个面向 Minecraft 1.21.1 NeoForge 的现代空战模组：脉冲多普勒雷达、TWS、BVR 数据链、ARH/SARH/IR 导弹、导引头 HUD、RWR 与箔条/热焰弹对抗。

## 主要功能

- 雷达搜索、ACM、STT 硬锁、TWS 多目标软跟踪与 BVR 远程锁定
- 多普勒 Notch / 39 盲区：50 b 内烧穿，50 b 外无径向速度时锁定与制导衰减
- ARH、SARH、IR 三类导弹，独立动力段、比例导引、末段导引头与 IOG
- 导引头白弧 → 红弧 → 红双环进度；ARH 白弧可发射，SARH 红弧可发射
- 雷达面板、主屏锁定框、导弹引导线、RWR 告警与发射包线
- 箔条雷达假目标、热焰弹红外欺骗
- 导弹直接命中 25 伤害，6 b 内最多 8 点溅射；测试弹无伤害

## 环境

- Minecraft 1.21.1
- NeoForge 21.1.236 或更高
- Java 21

## 构建

```powershell
.\gradlew build --no-daemon --console=plain
```

构建产物位于 `build/libs/minethunder-0.1.6.jar`。

## BVR 测试命令

需要权限等级 2：

```text
/minethunder test bvr incoming arh [目标玩家]
/minethunder test bvr incoming sarh [目标玩家]
/minethunder test bvr strike [目标玩家]
/minethunder test bvr start
/minethunder test bvr status
/minethunder test bvr clear
```

- `incoming`：在目标前方约 800 b 生成无伤害来袭弹，默认目标为自己。
- `strike`：在目标前方约 800 b 生成实弹 ARH，默认目标为自己；会造成真实爆炸伤害。
- `start`：生成用于 BVR 数据链/锁定练习的 `BVR-TEST-7` 目标。

## 许可证

本项目使用 [Apache License 2.0](LICENSE)。
