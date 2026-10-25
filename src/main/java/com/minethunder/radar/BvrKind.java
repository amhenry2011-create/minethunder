package com.minethunder.radar;

/** BVR 远程航迹类别。 */
public enum BvrKind {
    /** 其他载机（玩家） */
    PLAYER,
    /** 带 minethunder_bvr 标签的 AI / 测试目标 */
    AI,
    /** 飞行中的导弹 */
    MISSILE,
    /** 海面 BVR 标签目标 */
    SURFACE,
    /** 地面 BVR 目标（含停机/滑行阶段的飞机与地面移动目标） */
    GROUND;

    public boolean isAir() {
        return this == PLAYER || this == AI;
    }

    public boolean isMissile() {
        return this == MISSILE;
    }
}
