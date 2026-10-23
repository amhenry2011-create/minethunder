package com.minethunder.client;

import com.minethunder.MineThunder;
import com.minethunder.missile.MissileFireworkItem;
import com.minethunder.missile.MissileSpec;
import com.minethunder.missile.MissileSpecs;
import com.minethunder.network.CountermeasurePacket;
import com.minethunder.network.MissileFirePacket;
import com.minethunder.network.Network;
import com.minethunder.radar.RadarMode;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * Demo 风格按键处理。冲突的原版单键（Q/D/W/E/T/1-4）改用 Alt 组合，
 * 雷达本体不要求手持弓，R 直接切换开关。
 */
@EventBusSubscriber(modid = MineThunder.MODID, value = Dist.CLIENT)
public final class KeyInputHandler {
    private KeyInputHandler() {}

    private static long lastCountermeasureMs = 0L;
    private static boolean altWasDown = false;
    private static boolean ctrlComboWasDown = false;
    private static boolean altXWasDown = false;
    private static boolean altTWasDown = false;
    private static boolean altFWasDown = false;
    private static boolean altBWasDown = false;
    private static boolean altRWasDown = false;
    private static boolean altQWasDown = false;
    private static boolean altDWasDown = false;
    private static boolean altWWasDown = false;
    private static boolean altEWasDown = false;
    private static double scanVelocityAz = 0.0;
    private static double scanVelocityEl = 0.0;
    private static final double SCAN_SLEW_DEG_PER_SEC = 30.0;
    private static final double SCAN_ACCEL_DEG_PER_SEC2 = 120.0;

    /** Alt+E：同时释放箔条与热焰弹。 */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getKey() != GLFW.GLFW_KEY_E) return;
        if ((event.getModifiers() & GLFW.GLFW_MOD_ALT) == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        dispenseCountermeasures(mc);
    }

    /** 取消 Alt+E 触发的原版背包界面。 */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!isAltDown()) return;
        if (event.getNewScreen() instanceof InventoryScreen) {
            event.setCanceled(true);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) dispenseCountermeasures(mc);
        } else if (event.getNewScreen() instanceof ChatScreen) {
            // Alt+T 是切换目标，不能打开聊天
            event.setCanceled(true);
            Minecraft mc = Minecraft.getInstance();
            drain(mc.options.keyChat);
        }
    }

    private static boolean isAltDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private static boolean isCtrlDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static void dispenseCountermeasures(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastCountermeasureMs < 80L) return;
        lastCountermeasureMs = now;
        Network.sendToServer(new CountermeasurePacket(3));
        notifyClient(mc, Component.literal("释放箔条 + 热焰弹"));
    }

    /** Demo 风格单键与 Alt 组合键。 */
    @SubscribeEvent
    public static void onDemoKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        int key = event.getKey();
        int mods = event.getModifiers();
        boolean alt = (mods & GLFW.GLFW_MOD_ALT) != 0;

        // Alt 组合键统一交给 ClientTick 轮询，避免同一次按键触发两次。
        if (alt) return;

        switch (key) {
            case GLFW.GLFW_KEY_X -> {
                if (RadarHud.lockedEntity != null) {
                    RadarHudClientTicker.toggleLock();
                    notifyClient(mc, Component.literal(RadarHud.lastLockMessage));
                } else {
                    RadarHudClientTicker.toggleSeeker();
                    notifyClient(mc, Component.literal(RadarHud.seekerMessage));
                }
            }
            case GLFW.GLFW_KEY_I -> {
                RadarHud.selectedMissile = MissileSpec.Guidance.IR;
                RadarHud.selectedSpec = MissileSpecs.IR;
                notifyClient(mc, Component.literal("红外弹模式 / 红外导引头"));
            }
            case GLFW.GLFW_KEY_H -> {
                RadarHud.helpOpen = !RadarHud.helpOpen;
            }
            case GLFW.GLFW_KEY_M -> {
                RadarHud.panelDisplay = (RadarHud.panelDisplay + 1) % 3;
            }
            default -> { }
        }
    }

    private static void drain(KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // 组合键已由本处理器执行，清掉原版点击避免丢物品/换手/开聊天。
        }
    }

    private static void updateSmoothScan(Minecraft mc, boolean left, boolean right,
                                         boolean up, boolean down) {
        if (mc.screen != null || RadarHud.lockAcquiring
                || RadarHud.currentMode == RadarMode.ACM
                || RadarHud.currentMode == RadarMode.TWS
                || RadarHud.currentMode == RadarMode.STT) {
            scanVelocityAz = 0.0;
            scanVelocityEl = 0.0;
            return;
        }

        double targetAz = (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0);
        double targetEl = (up ? 1.0 : 0.0) - (down ? 1.0 : 0.0);
        double maxDelta = SCAN_ACCEL_DEG_PER_SEC2 / 20.0;
        scanVelocityAz = approach(scanVelocityAz,
                targetAz * SCAN_SLEW_DEG_PER_SEC, maxDelta);
        scanVelocityEl = approach(scanVelocityEl,
                targetEl * SCAN_SLEW_DEG_PER_SEC, maxDelta);

        double nextAz = RadarHud.scanAzimuthOffsetDeg + scanVelocityAz / 20.0;
        double nextEl = RadarHud.scanElevationOffsetDeg + scanVelocityEl / 20.0;
        if (nextAz <= -30.0 || nextAz >= 30.0) {
            nextAz = Math.max(-30.0, Math.min(30.0, nextAz));
            if ((nextAz <= -30.0 && scanVelocityAz < 0.0)
                    || (nextAz >= 30.0 && scanVelocityAz > 0.0)) {
                scanVelocityAz = 0.0;
            }
        }
        if (nextEl <= -60.0 || nextEl >= 60.0) {
            nextEl = Math.max(-60.0, Math.min(60.0, nextEl));
            if ((nextEl <= -60.0 && scanVelocityEl < 0.0)
                    || (nextEl >= 60.0 && scanVelocityEl > 0.0)) {
                scanVelocityEl = 0.0;
            }
        }
        RadarHud.scanAzimuthOffsetDeg = nextAz;
        RadarHud.scanElevationOffsetDeg = nextEl;
    }

    private static double approach(double value, double target, double maxDelta) {
        if (Math.abs(target - value) <= maxDelta) return target;
        return value + Math.signum(target - value) * maxDelta;
    }

    private static void cycleMode(Minecraft mc) {
        if (RadarHud.lockAcquiring) {
            notifyClient(mc, Component.literal("ACQ PD 尝试锁定中，模式已冻结"));
            return;
        }
        if (RadarHud.acmActive) {
            RadarHudClientTicker.exitAcm();
        }
        RadarHud.currentMode = nextMode(RadarHud.currentMode);
        if (RadarHud.currentMode == RadarMode.ACM) {
            RadarHud.scanAzimuthOffsetDeg = 0;
            RadarHud.scanElevationOffsetDeg = 0;
        }
        notifyClient(mc, Component.literal(
                "雷达模式：" + RadarHud.currentMode.getDisplayName()));
    }

    private static void toggleRadar(Minecraft mc) {
        if (RadarHud.lockAcquiring) {
            notifyClient(mc, Component.literal("ACQ PD 尝试锁定中，雷达开关已冻结"));
            return;
        }
        RadarHud.radarOn = !RadarHud.radarOn;
        notifyClient(mc, Component.literal(
                RadarHud.radarOn ? "雷达开启" : "雷达关闭"));
    }

    private static void cycleRange(Minecraft mc) {
        if (RadarHud.lockAcquiring) {
            notifyClient(mc, Component.literal("ACQ PD 尝试锁定中，距离档已冻结"));
            return;
        }
        if (RadarHud.isAcmContext()) {
            RadarHud.acmRangeIndex = (RadarHud.acmRangeIndex + 1) % RadarHud.ACM_RANGES.length;
            RadarHud.acmRange = RadarHud.ACM_RANGES[RadarHud.acmRangeIndex];
            notifyClient(mc, Component.literal(
                    "ACM 搜索距离：" + (int) RadarHud.acmRange + " b"));
            return;
        }
        RadarHud.rangeIndex = (RadarHud.rangeIndex + 1) % RadarHud.RANGES.length;
        RadarHud.displayRange = RadarHud.RANGES[RadarHud.rangeIndex];
        RadarHud.maxRange = RadarHud.displayRange;
        notifyClient(mc, Component.literal(String.format(
                "距离档位：%.0f b", RadarHud.displayRange)));
    }

    private static void cycleScanWidth(Minecraft mc) {
        if (RadarHud.lockAcquiring) {
            notifyClient(mc, Component.literal("ACQ PD 尝试锁定中，扫描宽度已冻结"));
            return;
        }
        if (RadarHud.isAcmContext()) {
            RadarHud.acmBeamIndex = (RadarHud.acmBeamIndex + 1) % 3;
            String label = switch (RadarHud.acmBeamIndex) {
                case RadarHud.ACM_BEAM_VERTICAL -> "上下往复";
                case RadarHud.ACM_BEAM_RASTER -> "3×3 光栅";
                default -> "正前静止";
            };
            notifyClient(mc, Component.literal("ACM 波束：" + label));
            return;
        }
        RadarHud.scanPatternIndex = (RadarHud.scanPatternIndex + 1)
                % RadarHud.SCAN_PATTERNS.length;
        notifyClient(mc, Component.literal(
                "扫描模式：" + RadarHud.scanPattern().label()));
    }

    private static void toggleAcm(Minecraft mc) {
        if (RadarHud.lockAcquiring) {
            notifyClient(mc, Component.literal("ACQ PD 尝试锁定中，模式已冻结"));
            return;
        }
        boolean active = RadarHudClientTicker.toggleAcm();
        String name = active ? RadarMode.ACM.getDisplayName()
                : RadarHud.currentMode.getDisplayName();
        notifyClient(mc, Component.literal("雷达模式：" + name));
    }

    private static void showHelp(Minecraft mc) {
        notifyClient(mc, Component.literal(
                "Alt+B 模式 · Alt+R 雷达 · 右Shift 回中 · 方向键扫描 · Alt+F 锁定 · Alt+T 选目标 · X 解锁"));
        notifyClient(mc, Component.literal(
                "Alt+Q 距离 · Alt+D 扫描宽/ACM 波束 · Alt+W ACM · Alt+X 雷达导引头 · 满弓红外 · Alt+E 干扰弹 · C 弹种 · Enter 挂架"));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean altDown = isAltDown();
        boolean ctrlCombo = altDown && isCtrlDown();
        if (ctrlCombo && !ctrlComboWasDown && mc.screen == null) {
            fireMissile(mc);
        }
        ctrlComboWasDown = ctrlCombo;
        if (altDown != altWasDown) {
            if (altDown) {
                if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
            } else if (mc.screen == null && !mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.grabMouse();
            }
            altWasDown = altDown;
        }

        long window = mc.getWindow().getWindow();
        boolean xDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_X);
        if (altDown && xDown && !altXWasDown) {
            RadarHudClientTicker.toggleSeeker();
            notifyClient(mc, Component.literal(RadarHud.seekerMessage));
        }
        altXWasDown = xDown;

        boolean tDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_T);
        if (altDown && tDown && !altTWasDown) {
            drain(mc.options.keyChat);
            if (mc.screen == null) {
                RadarHudClientTicker.selectNextTarget();
                notifyClient(mc, Component.literal(RadarHud.lastLockMessage));
            }
        }
        altTWasDown = tDown;

        boolean bDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_B);
        boolean rDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R);
        boolean qDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Q);
        boolean dDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_D);
        boolean wDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_W);
        boolean fDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_F);
        boolean eDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_E);
        if (altDown && mc.screen == null) {
            if (bDown && !altBWasDown) cycleMode(mc);
            if (rDown && !altRWasDown) toggleRadar(mc);
            if (qDown && !altQWasDown) {
                drain(mc.options.keyDrop);
                cycleRange(mc);
            }
            if (dDown && !altDWasDown) cycleScanWidth(mc);
            if (wDown && !altWWasDown) toggleAcm(mc);
            if (fDown && !altFWasDown) {
                drain(mc.options.keySwapOffhand);
                RadarHudClientTicker.toggleLock();
                notifyClient(mc, Component.literal(RadarHud.lastLockMessage));
            }
            if (eDown && !altEWasDown) dispenseCountermeasures(mc);
        }
        if (mc.screen == null && KeyBindings.RECENTER.consumeClick()) {
            if (RadarHud.lockAcquiring) {
                notifyClient(mc, Component.literal("ACQ PD 尝试锁定中，扫描位置已冻结"));
            } else {
                RadarHudClientTicker.recenterScan();
                scanVelocityAz = 0.0;
                scanVelocityEl = 0.0;
                notifyClient(mc, Component.literal("天线回中"));
            }
        }
        altBWasDown = bDown;
        altRWasDown = rDown;
        altQWasDown = qDown;
        altDWasDown = dDown;
        altWWasDown = wDown;
        altFWasDown = fDown;
        altEWasDown = eDown;

        boolean leftArrow = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT);
        boolean rightArrow = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT);
        boolean upArrow = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_UP);
        boolean downArrow = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_DOWN);
        updateSmoothScan(mc, leftArrow, rightArrow, upArrow, downArrow);

        if (KeyBindings.CYCLE_MISSILE_TYPE.consumeClick()) {
            RadarHud.selectedMissile = nextMissile(RadarHud.selectedMissile);
            RadarHud.selectedSpec = switch (RadarHud.selectedMissile) {
                case ARH -> MissileSpecs.ARH;
                case SARH -> MissileSpecs.SARH;
                case IR -> MissileSpecs.IR;
            };
            notifyClient(mc, Component.literal(
                    "导弹类型：" + RadarHud.selectedMissile));
        }

        boolean rackClick = KeyBindings.RACK_SELECT.consumeClick();
        if (rackClick && mc.player.tickCount > 20) {
            com.minethunder.client.rack.RackSelectInput.toggle();
        }
    }

    private static void fireMissile(Minecraft mc) {
        if (!RadarHud.seekerEnabled) {
            RadarHudClientTicker.toggleSeeker();
            notifyClient(mc, Component.literal(RadarHud.seekerMessage));
            return;
        }

        MissileSpec.Guidance chosen = chooseFireGuidance(mc);
        boolean twsSoft = RadarHud.currentMode == RadarMode.TWS
                && TwsTrackManager.selected() != null;
        boolean radarGuided = chosen == MissileSpec.Guidance.ARH
                || chosen == MissileSpec.Guidance.SARH;
        boolean bvrLock = radarGuided && !RadarHud.bvrAcquiring
                && RadarHud.bvrLockedUuid != null;
        boolean softSource = twsSoft
                || (RadarHud.locked && RadarHud.lockedEntity != null)
                || bvrLock;
        if (chosen == MissileSpec.Guidance.ARH && !softSource) {
            notifyClient(mc, Component.literal(
                    "没有有效的雷达锁定或 TWS 跟踪"));
            return;
        }
        if (chosen == MissileSpec.Guidance.ARH) {
            boolean ready = (twsSoft || bvrLock)
                    ? RadarHud.seekerEnabledTicks >= 10
                    : RadarHud.seekerRing.aligned();
            if (!ready) {
                notifyClient(mc, Component.literal(
                        "导引头未对准目标"));
                return;
            }
        }
        if (chosen == MissileSpec.Guidance.SARH) {
            if (bvrLock) {
                if (RadarHud.seekerEnabledTicks < 10) {
                    notifyClient(mc, Component.literal(
                            "导引头未对准目标"));
                    return;
                }
            } else if (!RadarHud.seekerModel.canLaunchSarh()) {
                notifyClient(mc, Component.literal(
                        "SARH 需硬锁定并等待红色倒计时环"));
                return;
            }
        }
        if (chosen == MissileSpec.Guidance.IR
                && RadarHud.seekerLockedEntity == null) {
            notifyClient(mc, Component.literal(
                    "红外导引头尚未锁定热源"));
            return;
        }

        RadarHud.selectedMissile = chosen;
        RadarHud.selectedSpec = specFor(chosen);
        int targetId = chosen == MissileSpec.Guidance.IR
                ? RadarHud.seekerLockedEntity.getId() : -1;
        java.util.UUID bvrUuid = radarGuided ? RadarHud.bvrLockedUuid : null;
        Network.sendToServer(new MissileFirePacket(chosen.ordinal(), targetId, bvrUuid));
        RadarHudClientTicker.closeSeekerAfterLaunch();
        notifyClient(mc, Component.literal("导引头：关闭"));
    }

    private static void notifyClient(Minecraft mc, Component message) {
        String text = message.getString();
        if (text.equals("需要雷达锁定")
                || text.equals("无可用导弹")
                || text.equals("导引头：开启")
                || text.equals("导引头：关闭")) {
            return;
        }
    }

    private static RadarMode nextMode(RadarMode mode) {
        var all = RadarMode.values();
        RadarMode next = mode;
        do {
            next = all[(next.ordinal() + 1) % all.length];
        } while (!next.isSelectable());
        return next;
    }

    private static MissileSpec.Guidance chooseFireGuidance(Minecraft mc) {
        if (mc.player == null) return RadarHud.selectedMissile;
        MissileSpec.Guidance preferred = RadarHud.selectedMissile;
        if (hasMissileAmmo(mc, preferred)) return preferred;
        for (MissileSpec.Guidance candidate : new MissileSpec.Guidance[]{
                MissileSpec.Guidance.ARH, MissileSpec.Guidance.SARH, MissileSpec.Guidance.IR}) {
            if (hasMissileAmmo(mc, candidate)) return candidate;
        }
        return preferred;
    }

    private static boolean hasMissileAmmo(Minecraft mc, MissileSpec.Guidance guidance) {
        if (mc.player == null) return false;
        for (ItemStack stack : mc.player.getInventory().items) {
            if (stack.getItem() instanceof MissileFireworkItem firework
                    && firework.getSpec().guidance == guidance) return true;
        }
        for (ItemStack stack : mc.player.getInventory().offhand) {
            if (stack.getItem() instanceof MissileFireworkItem firework
                    && firework.getSpec().guidance == guidance) return true;
        }
        return false;
    }

    private static MissileSpec specFor(MissileSpec.Guidance guidance) {
        return switch (guidance) {
            case ARH -> MissileSpecs.ARH;
            case SARH -> MissileSpecs.SARH;
            case IR -> MissileSpecs.IR;
        };
    }

    private static MissileSpec.Guidance nextMissile(MissileSpec.Guidance guidance) {
        var all = MissileSpec.Guidance.values();
        return all[(guidance.ordinal() + 1) % all.length];
    }
}
