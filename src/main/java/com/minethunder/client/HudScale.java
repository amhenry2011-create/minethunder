package com.minethunder.client;

/** 按 1920x1080 设计稿缩放 HUD，适配不同窗口大小。 */
public final class HudScale {
    private HudScale() {}

    public static float factor(int screenW, int screenH) {
        float scale = Math.min(screenW / 1920.0f, screenH / 1080.0f);
        return Math.max(0.30f, Math.min(1.0f, scale));
    }
}
