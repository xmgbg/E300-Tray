package com.ezhan.amr.utils;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一调试日志，logcat 过滤 Tag: TaskDebug1
 */
public final class TaskDebug1 {

    public static final String TAG = "TaskDebug1";

    private static final ConcurrentHashMap<String, Long> LAST_LOG_AT = new ConcurrentHashMap<>();

    private TaskDebug1() {
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

    /** 打印当前导航状态快照，便于定位卡住/任务链中断 */
    public static void logNavSnapshot(String scene, long routeId, boolean taskRunning,
                                      String routeStatus, int goalFinish, boolean gifPlaying,
                                      boolean inStationStay, String extra) {
        log(String.format("[SNAPSHOT] scene=%s routeId=%d taskRunning=%b routeStatus=%s goalFinish=%d gifPlaying=%b inStationStay=%b %s",
                scene, routeId, taskRunning, routeStatus, goalFinish, gifPlaying, inStationStay,
                extra != null ? extra : ""));
    }
}
