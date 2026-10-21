package com.minethunder.client.rack;

/**
 * 挂架选择界面状态（客户端）。
 * Enter 打开：释放鼠标、游戏不暂停。
 * 数字键 1-9 选择快捷栏对应槽位，或鼠标点击槽位选择。
 * 再次 Enter 或点击面板外 → 重新捕获鼠标。
 */
public final class RackSelectState {
    private RackSelectState() {}

    /** 是否打开挂架选择 */
    public static boolean open = false;
    /** 当前选中的背包槽位（0-35，-1 未选） */
    public static int selectedSlot = -1;
    /** 当前悬停槽位（-1 无） */
    public static int hoveredSlot = -1;

    // ---- 面板几何（GUI 缩放坐标） ----
    public static final int COLS = 9;
    public static final int ROWS = 4;
    public static final int SLOT = 18;
    public static final int PAD = 8;
    public static final int ROW_GAP = 4;

    public static int panelW() {
        return COLS * SLOT + PAD * 2;
    }

    public static int panelH() {
        return ROWS * SLOT + ROW_GAP + PAD * 2 + 12;
    }

    /** 槽位左上角 x（槽内物品区） */
    public static int slotX(int panelX, int col) {
        return panelX + PAD + col * SLOT;
    }

    /** 槽位左上角 y（槽内物品区） */
    public static int slotY(int panelY, int row) {
        return panelY + PAD + 12 + row * SLOT + (row >= 3 ? ROW_GAP : 0);
    }

    /** 背包索引 → 网格行 */
    public static int rowOf(int inventoryIndex) {
        if (inventoryIndex < 9) return 3;       // 快捷栏在最下
        return (inventoryIndex - 9) / 9;        // 主背包 0-2 行
    }

    /** 背包索引 → 网格列 */
    public static int colOf(int inventoryIndex) {
        if (inventoryIndex < 9) return inventoryIndex;
        return (inventoryIndex - 9) % 9;
    }

    /** 由网格行列反查背包索引 */
    public static int inventoryIndexOf(int row, int col) {
        if (row == 3) return col;
        return 9 + row * 9 + col;
    }
}
