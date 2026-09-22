package com.ezhan.amr.utils;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 屏幕暂停 / 恢复调试日志，logcat 过滤 Tag: TaskDebug2
 */
public final class TaskDebug2 {

    public static final String TAG = "TaskDebug2";

    private static final ConcurrentHashMap<String, Long> LAST_LOG_AT = new ConcurrentHashMap<>();

    private TaskDebug2() {
    }

    public static void log(String message) {
        Log.d(TAG, message);
    }

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
