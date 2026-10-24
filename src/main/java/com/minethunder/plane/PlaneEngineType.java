package com.minethunder.plane;

/**
 * 简单飞机（Simple Planes）移植航空发动机类型：
 * 決定最大推力、慢車轉速、是否有加力燃燒室（WEP/AB）、排氣溫度（EGT）與紅外線熱特徵。
 */
public enum PlaneEngineType {
    FURNACE("furnace", "活塞航空发动机 (Furnace Engine)", 1.00, 0.22f, 620.0f, false, 180.0),
    LIQUID("liquid", "涡桨液体燃料发动机 (Liquid Engine)", 1.32, 0.24f, 740.0f, false, 230.0),
    JET("jet", "加力涡扇航空发动机 (Afterburning Jet)", 1.75, 0.26f, 920.0f, true, 260.0);

    private final String id;
    private final String displayName;
    private final double thrustMultiplier;
    private final float idleRpm;
    private final float nominalMaxEgt;
    private final boolean afterburnerCapable;
    private final double milIrHeat;

    PlaneEngineType(String id, String displayName, double thrustMultiplier,
                    float idleRpm, float nominalMaxEgt, boolean afterburnerCapable,
                    double milIrHeat) {
        this.id = id;
        this.displayName = displayName;
        this.thrustMultiplier = thrustMultiplier;
        this.idleRpm = idleRpm;
        this.nominalMaxEgt = nominalMaxEgt;
        this.afterburnerCapable = afterburnerCapable;
        this.milIrHeat = milIrHeat;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public double thrustMultiplier() {
        return thrustMultiplier;
    }

    public float idleRpm() {
        return idleRpm;
    }

    public float nominalMaxEgt() {
        return nominalMaxEgt;
    }

    public boolean afterburnerCapable() {
        return afterburnerCapable;
    }

    public double milIrHeat() {
        return milIrHeat;
    }

    public static PlaneEngineType byOrdinal(int index) {
        PlaneEngineType[] values = values();
        if (index < 0 || index >= values.length) {
            return LIQUID;
        }
        return values[index];
    }
}
