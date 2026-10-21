package com.minethunder.client.rack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 挂架选择界面渲染。
 * 物品过滤：TNT、箭（含导弹箭）、烟花火箭 = 可选挂架；其余灰色不可选。
 */
public final class RackSelectRenderer {
    private RackSelectRenderer() {}

    private static final int BG = 0xC0101010;
    private static final int BORDER = 0xFF808080;
    private static final int SLOT_BG = 0x40FFFFFF;
    private static final int GRAY_MASK = 0xA0000000;
    private static final int SELECTED = 0xFFFFD700;
    private static final int HOVER = 0x80FFFFFF;

    public static void render(GuiGraphics g, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int pw = RackSelectState.panelW();
        int ph = RackSelectState.panelH();
        int px = (screenW - pw) / 2;
        int py = (screenH - ph) / 2;

        // 面板背景 + 边框
        g.fill(px, py, px + pw, py + ph, BG);
        g.fill(px, py, px + pw, py + 1, BORDER);
        g.fill(px, py + ph - 1, px + pw, py + ph, BORDER);
        g.fill(px, py, px + 1, py + ph, BORDER);
        g.fill(px + pw - 1, py, px + pw, py + ph, BORDER);

        // 标题
        g.drawString(mc.font, "挂架选择", px + RackSelectState.PAD, py + 4, 0xFFFFFFFF);

        // 更新悬停槽位
        int hovered = hoveredSlot(mc, px, py);
        RackSelectState.hoveredSlot = hovered;

        var inv = mc.player.getInventory();

        for (int idx = 0; idx < 36; idx++) {
            int row = RackSelectState.rowOf(idx);
            int col = RackSelectState.colOf(idx);
            int sx = RackSelectState.slotX(px, col);
            int sy = RackSelectState.slotY(py, row);

            // 槽底
            g.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);

            ItemStack stack = inv.getItem(idx);
            boolean selectable = isRackItem(stack);

            if (!stack.isEmpty()) {
                g.renderItem(stack, sx, sy);
                g.renderItemDecorations(mc.font, stack, sx, sy);
            }

            // 不可选：灰色遮罩
            if (!selectable) {
                g.fill(sx, sy, sx + 16, sy + 16, GRAY_MASK);
            }

            // 选中：金色边框
            if (idx == RackSelectState.selectedSlot) {
                drawSlotBorder(g, sx - 1, sy - 1, SELECTED);
            }
            // 悬停：白色边框
            else if (idx == hovered) {
                drawSlotBorder(g, sx - 1, sy - 1, HOVER);
            }
        }

        // 底部提示
        String hint = "数字键 1-9 / 点击选择 · Enter 或点击面板外关闭";
        g.drawString(mc.font, hint, px + RackSelectState.PAD, py + ph - 12, 0xFFAAAAAA);

        // 选中物品名称
        if (RackSelectState.selectedSlot >= 0) {
            ItemStack sel = inv.getItem(RackSelectState.selectedSlot);
            if (!sel.isEmpty()) {
                String name = sel.getHoverName().getString();
                g.drawString(mc.font, "当前挂架：" + name, px + RackSelectState.PAD, py + 14, 0xFFFFD700);
            }
        }
    }

    private static void drawSlotBorder(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 18, y + 1, color);
        g.fill(x, y + 17, x + 18, y + 18, color);
        g.fill(x, y, x + 1, y + 18, color);
        g.fill(x + 17, y, x + 18, y + 18, color);
    }

    /** 当前鼠标悬停的背包槽位（无则 -1） */
    private static int hoveredSlot(Minecraft mc, int px, int py) {
        double mx = mouseGuiX(mc);
        double my = mouseGuiY(mc);
        for (int idx = 0; idx < 36; idx++) {
            int sx = RackSelectState.slotX(px, RackSelectState.colOf(idx));
            int sy = RackSelectState.slotY(py, RackSelectState.rowOf(idx));
            if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                return idx;
            }
        }
        return -1;
    }

    /** 鼠标 GUI 坐标 X */
    public static double mouseGuiX(Minecraft mc) {
        return mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth()
                / (double) mc.getWindow().getScreenWidth();
    }

    /** 鼠标 GUI 坐标 Y */
    public static double mouseGuiY(Minecraft mc) {
        return mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight()
                / (double) mc.getWindow().getScreenHeight();
    }

    /** 鼠标是否在面板内 */
    public static boolean isOverPanel(Minecraft mc, int screenW, int screenH) {
        int pw = RackSelectState.panelW();
        int ph = RackSelectState.panelH();
        int px = (screenW - pw) / 2;
        int py = (screenH - ph) / 2;
        double mx = mouseGuiX(mc);
        double my = mouseGuiY(mc);
        return mx >= px && mx < px + pw && my >= py && my < py + ph;
    }

    /** 判断物品是否为可用挂架：TNT、箭（含导弹箭）、烟花火箭 */
    public static boolean isRackItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item == Items.TNT) return true;
        if (item instanceof ArrowItem) return true;
        if (item instanceof FireworkRocketItem || item == Items.FIREWORK_ROCKET) return true;
        return false;
    }
}
