package com.minethunder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 雷达功能快捷菜单（战雷 Alt+Y 多功能菜单的简化版）。
 * 显示在雷达面板左侧，列出雷达模式，当前模式高亮；点击可切换。
 */
public final class RadarQuickMenu {
    private RadarQuickMenu() {}

    /** 是否展开（默认展开） */
    public static boolean visible = true;

    private static final int WIDTH = 92;
    private static final int ROW = 14;
    private static final int BG = 0xC0101010;
    private static final int BORDER = 0xFF808080;
    private static final int TEXT = 0xFFDDDDDD;
    private static final int ACTIVE = 0xFF44FF44;

    private static int menuX(int screenW) {
        return screenW - RadarHud.PANEL_SIZE - RadarHud.PANEL_MARGIN_RIGHT - 56 - WIDTH - 6;
    }

    private static int menuY(int screenH) {
        return screenH - RadarHud.PANEL_SIZE - RadarHud.PANEL_BOTTOM;
    }

    public static void render(GuiGraphics g, int screenW, int screenH) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();

        int x = menuX(screenW);
        int y = menuY(screenH);
        var modes = selectableModes();
        int h = modes.length * ROW + 18;

        g.fill(x, y, x + WIDTH, y + h, BG);
        g.fill(x, y, x + WIDTH, y + 1, BORDER);
        g.fill(x, y + h - 1, x + WIDTH, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + WIDTH - 1, y, x + WIDTH, y + h, BORDER);

        g.drawString(mc.font, "雷达功能", x + 4, y + 4, 0xFFFFFFFF);

        for (int i = 0; i < modes.length; i++) {
            var mode = modes[i];
            int ty = y + 16 + i * ROW;
            boolean active = mode == RadarHud.currentMode;
            g.drawString(mc.font, (i + 1) + ". " + mode.getDisplayName(),
                    x + 4, ty, active ? ACTIVE : TEXT);
        }
    }

    public static boolean contains(double mx, double my, int screenW, int screenH) {
        if (!visible) return false;
        int x = menuX(screenW);
        int y = menuY(screenH);
        int h = selectableModes().length * ROW + 18;
        return mx >= x && mx < x + WIDTH && my >= y && my < y + h;
    }

    public static boolean handleClick(double mx, double my, int screenW, int screenH) {
        if (RadarHud.lockAcquiring) return false;
        if (!contains(mx, my, screenW, screenH)) return false;
        int y = menuY(screenH);
        int rel = (int) (my - y - 16);
        if (rel < 0) return false;
        int row = rel / ROW;
        var modes = selectableModes();
        if (row >= 0 && row < modes.length) {
            RadarHud.currentMode = modes[row];
            return true;
        }
        return false;
    }

    private static com.minethunder.radar.RadarMode[] selectableModes() {
        return java.util.Arrays.stream(com.minethunder.radar.RadarMode.values())
                .filter(com.minethunder.radar.RadarMode::isSelectable)
                .toArray(com.minethunder.radar.RadarMode[]::new);
    }
}
