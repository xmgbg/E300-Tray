package com.ezhan.amr.navigation.area;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 交管专用日志工具 (独立文件, 便于两台设备日志对比分析)。
 *
 * 特性:
 *   - 独立目录: {appExternal}/traffic_debug/traffic_{date}.log
 *   - 单文件上限 200MB, 超过自动轮转归档
 *   - 总上限 2GB, 超过自动删除最旧日志
 *   - 保留最近 7 天日志
 *   - 所有日志同时输出到 Logcat (tag: TrafficLog)
 *   - 线程安全 (ReentrantLock)
 *
 * 用法:
 *   TrafficLog.init(context);          // 在 Application onCreate 初始化
 *   TrafficLog.log("...");             // 记录日志
 *   TrafficLog.logHex("RECV", hex);    // 记录收发帧
 */
public final class TrafficLog {
    private static final String TAG = "TrafficLog";
    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024; // 200MB
    private static final long MAX_TOTAL_SIZE_BYTES = 2L * 1024 * 1024 * 1024; // 2GB
    private static final int MAX_HISTORY_DAYS = 7;

    private static File logDir;
    private static File logFile;
    private static long currentFileSize = 0;
    private static String currentDate;
    private static final ReentrantLock lock = new ReentrantLock();
    private static final SimpleDateFormat timestampFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat fileDateFormat =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static volatile boolean initialized = false;

    private TrafficLog() {}

    /** 初始化, 在 Application onCreate 调用 */
    public static synchronized void init(Context context) {
        if (initialized || context == null) return;
        try {
            logDir = new File(context.getApplicationContext().getExternalFilesDir(null), "traffic_debug");
            if (logDir == null || (!logDir.exists() && !logDir.mkdirs())) {
                logDir = new File(context.getApplicationContext().getFilesDir(), "traffic_debug");
                logDir.mkdirs();
            }
            currentDate = fileDateFormat.format(new Date());
            logFile = new File(logDir, "traffic_" + currentDate + ".log");
            currentFileSize = logFile.exists() ? logFile.length() : 0;
            cleanupOldLogs();
            initialized = true;
            Log.i(TAG, "TrafficLog initialized: " + (logFile != null ? logFile.getAbsolutePath() : "null"));
        } catch (Exception e) {
            Log.e(TAG, "TrafficLog init failed", e);
        }
    }

    /** 记录普通日志 */
    public static void log(String message) {
        String line = String.format("[%s] %s", timestampFormat.format(new Date()), message);
        Log.d(TAG, line);
        writeToFile(line);
    }

    /** 记录收发 LoRa 帧 hex (带方向标签) */
    public static void logHex(String direction, String hex) {
        String line = String.format("[%s] %s HEX> %s", timestampFormat.format(new Date()), direction, hex);
        Log.d(TAG, line);
        writeToFile(line);
    }

    /** 记录带区域上下文的日志 */
    public static void log(String areaName, String message) {
        String line = String.format("[%s] [%s] %s", timestampFormat.format(new Date()), areaName, message);
        Log.d(TAG, line);
        writeToFile(line);
    }

    private static void writeToFile(String line) {
        if (!initialized || logFile == null) return;
        lock.lock();
        try {
            // 日期变更检查
            String today = fileDateFormat.format(new Date());
            if (!today.equals(currentDate)) {
                currentDate = today;
                logFile = new File(logDir, "traffic_" + currentDate + ".log");
                currentFileSize = logFile.exists() ? logFile.length() : 0;
                cleanupOldLogs();
            }
            // 超过 200MB 轮转
            if (currentFileSize >= MAX_FILE_SIZE_BYTES) {
                String ts = new SimpleDateFormat("HHmmssSSS", Locale.getDefault()).format(new Date());
                File archive = new File(logDir, "traffic_" + currentDate + "_" + ts + ".log");
                if (logFile.renameTo(archive)) {
                    logFile = new File(logDir, "traffic_" + currentDate + ".log");
                    currentFileSize = 0;
                }
            }
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(line);
                pw.flush();
                currentFileSize = logFile.length();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write traffic log: " + e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    /** 清理超过 MAX_HISTORY_DAYS 天的旧日志，并确保总大小不超过 MAX_TOTAL_SIZE_BYTES */
    private static void cleanupOldLogs() {
        if (logDir == null) return;
        File[] files = logDir.listFiles((dir, name) -> name.startsWith("traffic_") && name.endsWith(".log"));
        if (files == null) return;

        // 按修改时间排序（旧的在前）
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        // 1. 先清理超过 MAX_HISTORY_DAYS 天的旧日志
        long cutoff = System.currentTimeMillis() - (long) MAX_HISTORY_DAYS * 24 * 60 * 60 * 1000;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    Log.d(TAG, "Deleted old traffic log (over " + MAX_HISTORY_DAYS + " days): " + f.getName());
                }
            }
        }

        // 2. 检查总大小，超过 2GB 时删除最旧的日志
        files = logDir.listFiles((dir, name) -> name.startsWith("traffic_") && name.endsWith(".log"));
        if (files == null) return;

        // 再次排序（旧的在前）
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        long totalSize = 0;
        for (File f : files) {
            totalSize += f.length();
        }

        // 如果总大小超过 2GB，删除最旧的文件直到总大小低于限制
        while (totalSize > MAX_TOTAL_SIZE_BYTES && files.length > 1) {
            File oldest = files[0];
            long fileSize = oldest.length();
            if (oldest.delete()) {
                totalSize -= fileSize;
                Log.d(TAG, "Deleted oldest traffic log (total over 2GB): " + oldest.getName());
                files = logDir.listFiles((dir, name) -> name.startsWith("traffic_") && name.endsWith(".log"));
                if (files == null || files.length <= 1) break;
                java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            } else {
                break;
            }
        }
    }

    /** 获取日志目录路径 (便于用户找文件) */
    public static String getLogDirPath() {
        return logDir != null ? logDir.getAbsolutePath() : null;
    }
}
