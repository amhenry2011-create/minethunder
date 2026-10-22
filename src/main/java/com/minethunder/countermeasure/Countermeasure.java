package com.minethunder.countermeasure;

/**
 * 对抗措施类型。
 */
public enum Countermeasure {
    /** 箔条：雷达假目标，欺骗 ARH/SARH 雷达头 */
    CHAFF,
    /** 热焰弹：热源，欺骗红外弹 */
    FLARE;

    /** 来袭导弹的威胁类型，用于判断某类对抗措施是否对其有效 */
    public enum MissileThreat {
        /** 雷达制导（主动/半主动） */
        RADAR,
        /** 红外制导 */
        INFRARED
    }
}
