package com.ezhan.amr.utils;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 充满电回待命（PARK 回待命点）链路调试日志，logcat 过滤 Tag: taskDebug8
 * 覆盖链路：充满判定 -> PARK事件 -> TaskExecutor -> MainActivity.startReturnToHome
 *          -> generateFullPositionList -> startNavigation(前置点插入)
 *          -> 位置/命令推进 -> 移动到达检测
 */
public final class TaskDebug8 {

    public static final String TAG = "taskDebug8";

    private static final ConcurrentHashMap<String, Long> LAST_LOG_AT = new ConcurrentHashMap<>();

    private TaskDebug8() {
    }

    public static void log(String message) {
        Log.d(TAG, message);
    }

    /**
     * 同一 key 在 intervalMs 内只打一条，避免状态轮询刷屏。
     */
    public static void logThrottled(String key, long intervalMs, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_LOG_AT.get(key);
        if (last != null && now - last < intervalMs) {
            return;
        }
        LAST_LOG_AT.put(key, now);
        Log.d(TAG, message);
    }
}
