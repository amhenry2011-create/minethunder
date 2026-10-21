package com.minethunder.sensor;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OP 专用传感器调试视觉的开关状态。
 * 服务端记录哪些玩家开启；客户端只保存当前连接是否开启。
 */
public final class SensorDebugState {
    private SensorDebugState() {}

    private static final Set<UUID> ENABLED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean clientEnabled = false;

    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (ENABLED_PLAYERS.remove(id)) {
            return false;
        }
        ENABLED_PLAYERS.add(id);
        return true;
    }

    public static boolean isEnabled(ServerPlayer player) {
        return ENABLED_PLAYERS.contains(player.getUUID());
    }

    public static void clear(ServerPlayer player) {
        ENABLED_PLAYERS.remove(player.getUUID());
    }

    public static boolean isClientEnabled() {
        return clientEnabled;
    }

    public static void setClientEnabled(boolean enabled) {
        clientEnabled = enabled;
    }
}
