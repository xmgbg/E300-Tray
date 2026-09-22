package com.ezhan.amr.navigation.task;

import android.content.Context;
import android.util.Log;

import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.navigation.ConfirmationStateManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class NavigationStateDebugger {
    private static final String TAG = "NavStateDebugger";
    private static NavigationStateDebugger instance;
    private Gson gson;
    private boolean enabled = true;
    private File debugDir;
    private Context appContext;
    private boolean writeToFile = true;

    // Separate log files for different levels
    private File infoLogFile;
    private File warnLogFile;
    private File errorLogFile;

    // Rolling policy constants
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100MB
    private static final long TOTAL_SIZE_CAP_BYTES = 5L * 1024 * 1024 * 1024; // 5GB
    private static final int MAX_HISTORY_DAYS = 20;

    // Locks for thread-safe file writing
    private final ReentrantLock infoLock = new ReentrantLock();
    private final ReentrantLock warnLock = new ReentrantLock();
    private final ReentrantLock errorLock = new ReentrantLock();

    // Track current file sizes
    private long currentInfoFileSize = 0;
    private long currentWarnFileSize = 0;
    private long currentErrorFileSize = 0;

    // Current log file names (with date)
    private String currentInfoFileName;
    private String currentWarnFileName;
    private String currentErrorFileName;

    // Date format for daily rotation
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // Current date for rotation check
    private String currentDate;

    // ⭐⭐⭐ TIMING TRACKING MAPS ⭐⭐⭐
    private final Map<Integer, StationTiming> stationTimings = new ConcurrentHashMap<>();

    // Timing constants
    private static final long TIMING_THRESHOLD_WARNING_MS = 500;
    private static final long TIMING_THRESHOLD_CRITICAL_MS = 2000;

    private long lastWebSocketConnectAttempt = 0;
    private long lastWebSocketSuccessTime = 0;
    private int webSocketFailureCount = 0;
    private String lastWebSocketError = null;
    private final Map<String, Integer> commandFailureStats = new ConcurrentHashMap<>();

    public static NavigationStateDebugger getInstance() {
        if (instance == null) {
            instance = new NavigationStateDebugger();
        }
        return instance;
    }

    private NavigationStateDebugger() {
        gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();
        currentDate = getCurrentDate();
    }

    /**
     * Initialize with context - call this from Application or MainActivity
     */
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        setupDebugLogDirectory();
        cleanupLegacyLogFiles();
        initializeLogFiles();
    }

    /**
     * Clean up legacy log files (nav_state_*.log)
     */
    private void cleanupLegacyLogFiles() {
        if (debugDir == null) return;

        File[] legacyFiles = debugDir.listFiles((dir, name) ->
                name.startsWith("nav_state_") && name.endsWith(".log"));

        if (legacyFiles != null) {
            int deletedCount = 0;
            for (File file : legacyFiles) {
                if (file.delete()) {
                    deletedCount++;
                    Log.d(TAG, "Deleted legacy log file: " + file.getName());
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "Cleaned up " + deletedCount + " legacy nav_state_*.log files");
            }
        }
    }

    /**
     * Setup debug log directory
     */
    private void setupDebugLogDirectory() {
        if (appContext == null) return;

        try {
            // Use external files directory (no permission needed for app's own directory)
            debugDir = new File(appContext.getExternalFilesDir(null), "navigation_debug");

            // Fallback to internal files directory if external is not available
            if (!debugDir.exists() && !debugDir.mkdirs()) {
                debugDir = new File(appContext.getFilesDir(), "navigation_debug");
                debugDir.mkdirs();
            }

            Log.i(TAG, "Debug log directory: " + debugDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup debug log directory: " + e.getMessage());
        }
    }

    /**
     * Initialize log files with rolling strategy
     */
    private void initializeLogFiles() {
        if (debugDir == null) return;

        String today = fileDateFormat.format(new Date());
        currentInfoFileName = "nav_info_" + today + ".log";
        currentWarnFileName = "nav_warn_" + today + ".log";
        currentErrorFileName = "nav_error_" + today + ".log";

        infoLogFile = new File(debugDir, currentInfoFileName);
        warnLogFile = new File(debugDir, currentWarnFileName);
        errorLogFile = new File(debugDir, currentErrorFileName);

        // Initialize file sizes
        currentInfoFileSize = infoLogFile.exists() ? infoLogFile.length() : 0;
        currentWarnFileSize = warnLogFile.exists() ? warnLogFile.length() : 0;
        currentErrorFileSize = errorLogFile.exists() ? errorLogFile.length() : 0;

        // Perform cleanup on old logs
        cleanupOldLogs();
    }

    /**
     * Check and rotate log file if needed
     */
    private synchronized void rotateLogFileIfNeeded(String level) {
        String newDate = getCurrentDate();

        // Check if date changed (daily rotation)
        if (!newDate.equals(currentDate)) {
            currentDate = newDate;
            rotateAllLogFilesByDate();
        }

        // Check file sizes
        switch (level) {
            case "INFO":
                if (currentInfoFileSize >= MAX_FILE_SIZE_BYTES) {
                    rotateSingleLogFile("INFO");
                }
                break;
            case "WARN":
                if (currentWarnFileSize >= MAX_FILE_SIZE_BYTES) {
                    rotateSingleLogFile("WARN");
                }
                break;
            case "ERROR":
                if (currentErrorFileSize >= MAX_FILE_SIZE_BYTES) {
                    rotateSingleLogFile("ERROR");
                }
                break;
        }
    }

    /**
     * Rotate all log files when date changes
     */
    private void rotateAllLogFilesByDate() {
        String today = fileDateFormat.format(new Date());

        // Close current files by updating references
        currentInfoFileName = "nav_info_" + today + ".log";
        currentWarnFileName = "nav_warn_" + today + ".log";
        currentErrorFileName = "nav_error_" + today + ".log";

        infoLogFile = new File(debugDir, currentInfoFileName);
        warnLogFile = new File(debugDir, currentWarnFileName);
        errorLogFile = new File(debugDir, currentErrorFileName);

        // Reset file sizes
        currentInfoFileSize = infoLogFile.exists() ? infoLogFile.length() : 0;
        currentWarnFileSize = warnLogFile.exists() ? warnLogFile.length() : 0;
        currentErrorFileSize = errorLogFile.exists() ? errorLogFile.length() : 0;

        Log.i(TAG, "Daily log rotation completed. New files: " + currentInfoFileName +
                ", " + currentWarnFileName + ", " + currentErrorFileName);

        // Clean up old logs
        cleanupOldLogs();
    }

    /**
     * Rotate a single log file when it exceeds size limit
     */
    private void rotateSingleLogFile(String level) {
        String timestamp = new SimpleDateFormat("HHmmssSSS", Locale.getDefault()).format(new Date());
        File archiveFile;

        switch (level) {
            case "INFO":
                archiveFile = new File(debugDir, "nav_info_" + currentDate + "_" + timestamp + ".log");
                if (infoLogFile.exists() && infoLogFile.renameTo(archiveFile)) {
                    infoLogFile = new File(debugDir, currentInfoFileName);
                    currentInfoFileSize = 0;
                    Log.i(TAG, "INFO log rotated to: " + archiveFile.getName());
                }
                break;
            case "WARN":
                archiveFile = new File(debugDir, "nav_warn_" + currentDate + "_" + timestamp + ".log");
                if (warnLogFile.exists() && warnLogFile.renameTo(archiveFile)) {
                    warnLogFile = new File(debugDir, currentWarnFileName);
                    currentWarnFileSize = 0;
                    Log.i(TAG, "WARN log rotated to: " + archiveFile.getName());
                }
                break;
            case "ERROR":
                archiveFile = new File(debugDir, "nav_error_" + currentDate + "_" + timestamp + ".log");
                if (errorLogFile.exists() && errorLogFile.renameTo(archiveFile)) {
                    errorLogFile = new File(debugDir, currentErrorFileName);
                    currentErrorFileSize = 0;
                    Log.i(TAG, "ERROR log rotated to: " + archiveFile.getName());
                }
                break;
        }
    }

    /**
     * Clean up old log files based on age and total size
     */
    private void cleanupOldLogs() {
        if (debugDir == null) return;

        // Only consider info, warn, error log files (exclude legacy nav_state files)
        File[] logFiles = debugDir.listFiles((dir, name) ->
                (name.startsWith("nav_info_") || name.startsWith("nav_warn_") || name.startsWith("nav_error_"))
                        && name.endsWith(".log"));

        if (logFiles == null) return;

        long currentTime = System.currentTimeMillis();
        long maxAgeMillis = (long) MAX_HISTORY_DAYS * 24 * 60 * 60 * 1000;

        // Sort files by last modified (oldest first) for size-based cleanup
        List<File> sortedFiles = new ArrayList<>(Arrays.asList(logFiles));
        sortedFiles.sort((f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

        // Delete files older than MAX_HISTORY_DAYS
        for (File file : sortedFiles) {
            if (currentTime - file.lastModified() > maxAgeMillis) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old log file (age > " + MAX_HISTORY_DAYS + " days): " + file.getName());
                }
            }
        }

        // Refresh list after age-based deletion
        File[] remainingFiles = debugDir.listFiles((dir, name) ->
                (name.startsWith("nav_info_") || name.startsWith("nav_warn_") || name.startsWith("nav_error_"))
                        && name.endsWith(".log"));

        if (remainingFiles == null) return;

        // Check total size and delete oldest files if over limit
        long totalSize = 0;
        List<File> sizeSortedFiles = new ArrayList<>();
        for (File file : remainingFiles) {
            if (file.exists()) {
                totalSize += file.length();
                sizeSortedFiles.add(file);
            }
        }

        if (totalSize > TOTAL_SIZE_CAP_BYTES) {
            // Sort by last modified (oldest first)
            sizeSortedFiles.sort((f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

            long sizeToFree = totalSize - TOTAL_SIZE_CAP_BYTES;
            long freedSize = 0;

            for (File file : sizeSortedFiles) {
                if (freedSize >= sizeToFree) break;
                freedSize += file.length();
                if (file.delete()) {
                    Log.d(TAG, "Deleted log file to free space: " + file.getName() +
                            " (freed " + file.length() / (1024 * 1024) + "MB)");
                }
            }
        }
    }

    /**
     * Get current date string for rotation check
     */
    private String getCurrentDate() {
        return dateFormat.format(new Date());
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setWriteToFile(boolean writeToFile) {
        this.writeToFile = writeToFile;
        if (writeToFile && debugDir == null && appContext != null) {
            setupDebugLogDirectory();
            cleanupLegacyLogFiles();
            initializeLogFiles();
        }
    }

    public File getDebugLogDirectory() {
        return debugDir;
    }

    /**
     * Write to file with rotation strategy
     */
    private void writeToFileWithRotation(String level, String content) {
        if (debugDir == null || !writeToFile) return;

        // Check and rotate before writing
        rotateLogFileIfNeeded(level);

        ReentrantLock lock;
        File targetFile;

        switch (level) {
            case "INFO":
                lock = infoLock;
                targetFile = infoLogFile;
                break;
            case "WARN":
                lock = warnLock;
                targetFile = warnLogFile;
                break;
            case "ERROR":
                lock = errorLock;
                targetFile = errorLogFile;
                break;
            default:
                return;
        }

        lock.lock();
        try {
            if (targetFile == null) return;

            // Ensure parent directory exists
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (FileWriter fw = new FileWriter(targetFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(content);
                pw.flush();

                // Update file size
                long newSize = targetFile.length();
                switch (level) {
                    case "INFO":
                        currentInfoFileSize = newSize;
                        break;
                    case "WARN":
                        currentWarnFileSize = newSize;
                        break;
                    case "ERROR":
                        currentErrorFileSize = newSize;
                        break;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to write debug log: " + e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    //----------------------------------------------------------------------------------------------
    // Basic Logging Methods

    public void log(String message) {
        if (!enabled) return;

        String logLine = String.format("[%s] %s", timestampFormat.format(new Date()), message);
        Log.d(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("INFO", logLine);
        }
    }

    public void logInfo(String message) {
        if (!enabled) return;

        String logLine = String.format("[%s] %s", timestampFormat.format(new Date()), message);
        Log.i(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("INFO", logLine);
        }
    }

    public void logError(String error, String details) {
        if (!enabled) return;

        String logLine = String.format("[%s] ❌ ERROR: %s | Details: %s",
                timestampFormat.format(new Date()), error, details);
        Log.e(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("ERROR", logLine);
        }
    }

    public void logError(String message) {
        if (!enabled) return;

        String logLine = String.format("[%s] ❌ ERROR: %s", timestampFormat.format(new Date()), message);
        Log.e(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("ERROR", logLine);
        }
    }

    public void logWarning(String warning, String details) {
        if (!enabled) return;

        String logLine = String.format("[%s] ⚠️ WARNING: %s | Details: %s",
                timestampFormat.format(new Date()), warning, details);
        Log.w(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("WARN", logLine);
        }
    }

    public void logWarning(String message) {
        if (!enabled) return;

        String logLine = String.format("[%s] ⚠️ WARNING: %s", timestampFormat.format(new Date()), message);
        Log.w(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("WARN", logLine);
        }
    }

    public void logTransition(String from, String to, String details) {
        if (!enabled) return;

        String logLine = String.format("[%s] 🔄 TRANSITION: %s -> %s | %s",
                timestampFormat.format(new Date()), from, to, details);
        Log.d(TAG, logLine);

        if (writeToFile) {
            writeToFileWithRotation("INFO", logLine);
        }
    }

    public void logStatusUpdate(String source, boolean hasValidData, long timeSinceLastUpdate) {
        if (!enabled) return;

        String status = hasValidData ? "VALID" : "INVALID";
        String logLine = String.format("[%s] 📊 [STATUS_UPDATE] %s: %s, last update: %dms ago",
                timestampFormat.format(new Date()), source, status, timeSinceLastUpdate);
        Log.d(TAG, logLine);

        if (writeToFile) {
            if (timeSinceLastUpdate > 5000) {
                writeToFileWithRotation("WARN", "⚠️ [STATUS_STALE] " + logLine);
            } else {
                writeToFileWithRotation("INFO", logLine);
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // State Dump Methods

    public void dumpFullState(String caller) {
        if (!enabled) return;

        String content = buildStateDump(caller);
        Log.d(TAG, content);

        if (writeToFile) {
            writeToFileWithRotation("INFO", content);
        }
    }

    public String buildStateDump(String caller) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(80)).append("\n");
        sb.append("NAVIGATION STATE DUMP - ").append(caller).append("\n");
        sb.append("Timestamp: ").append(timestampFormat.format(new Date())).append("\n");
        sb.append("=".repeat(80)).append("\n\n");

        // Dump Routes
        sb.append("📋 ROUTES (").append(RouteStore.getAllRoutes().size()).append("):\n");
        sb.append("-".repeat(60)).append("\n");
        for (Map.Entry<Long, RouteStore> entry : RouteStore.getAllRoutes().entrySet()) {
            RouteStore route = entry.getValue();
            sb.append(String.format("  Route[%d]: status=%s, positions=%d, currentIndex=%d\n",
                    route.getRouteId(),
                    route.getRouteStatus(),
                    route.getPositionIds().size(),
                    route.getCurrentPositionIndex()));
            sb.append("    Position IDs: ").append(route.getPositionIds()).append("\n");
        }

        // Dump Positions
        sb.append("\n📍 POSITIONS (").append(PositionMonitor.getAllPositions().size()).append("):\n");
        sb.append("-".repeat(60)).append("\n");
        for (Map.Entry<Long, PositionMonitor> entry : PositionMonitor.getAllPositions().entrySet()) {
            PositionMonitor pos = entry.getValue();
            sb.append(String.format("  Position[%d]: routeId=%d, seq=%d, status=%s, target='%s'\n",
                    pos.getPositionId(),
                    pos.getRouteId(),
                    pos.getSequenceIndex(),
                    pos.getPositionStatus(),
                    pos.getPosition() != null ? pos.getPosition().getName() : "null"));
            sb.append("    Command IDs: ").append(pos.getCommandIds()).append("\n");
        }

        // Dump Commands
        sb.append("\n⚙️ COMMANDS (").append(CommandMonitor.getAllCommands().size()).append("):\n");
        sb.append("-".repeat(60)).append("\n");
        for (Map.Entry<Long, CommandMonitor> entry : CommandMonitor.getAllCommands().entrySet()) {
            CommandMonitor cmd = entry.getValue();
            sb.append(String.format("  Command[%d]: routeId=%d, positionId=%d, type=%s, status=%s\n",
                    cmd.getCommandId(),
                    cmd.getRouteId(),
                    cmd.getPositionId(),
                    cmd.getCommandType(),
                    cmd.getCommandStatus()));
        }

        // Dump Confirmation Manager state
        ConfirmationStateManager confirmMgr = ConfirmationStateManager.getInstance();
        sb.append("\n✅ CONFIRMATION MANAGER:\n");
        sb.append("-".repeat(60)).append("\n");
        sb.append(String.format("  Any position waiting: %s\n", confirmMgr.isAnyPositionWaitingForConfirmation()));

        Map<Long, Boolean> confirmationStates = confirmMgr.getAllPositionConfirmationStates();
        sb.append("\n  Position Confirmation States:\n");
        if (confirmationStates.isEmpty()) {
            sb.append("    (no positions waiting for confirmation)\n");
        } else {
            for (Map.Entry<Long, Boolean> entry : confirmationStates.entrySet()) {
                long posId = entry.getKey();
                boolean isWaiting = entry.getValue();
                String posName = getPositionName(posId);
                if (posName != null) {
                    sb.append(String.format("    Position[%d] (%s): waiting=%b\n",
                            posId, posName, isWaiting));
                } else {
                    sb.append(String.format("    Position[%d]: waiting=%b\n",
                            posId, isWaiting));
                }
            }
        }

        sb.append("\n").append("=".repeat(80)).append("\n");
        return sb.toString();
    }

    private String getPositionName(long positionId) {
        PositionMonitor pos = PositionMonitor.getPosition(positionId);
        if (pos != null && pos.getPosition() != null) {
            return pos.getPosition().getName();
        }
        return null;
    }

    public void dumpRoutes() {
        if (!enabled) return;
        String msg = "=== ROUTES (" + RouteStore.getAllRoutes().size() + ") ===";
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
        for (Map.Entry<Long, RouteStore> entry : RouteStore.getAllRoutes().entrySet()) {
            RouteStore route = entry.getValue();
            String routeMsg = String.format("Route[%d]: status=%s, posCount=%d, currentIndex=%d",
                    route.getRouteId(), route.getRouteStatus(),
                    route.getPositionIds().size(), route.getCurrentPositionIndex());
            Log.d(TAG, routeMsg);
            if (writeToFile) {
                writeToFileWithRotation("INFO", routeMsg);
            }
        }
    }

    public void dumpPositionsForRoute(long routeId) {
        if (!enabled) return;
        String msg = "=== POSITIONS for Route " + routeId + " ===";
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
        for (Map.Entry<Long, PositionMonitor> entry : PositionMonitor.getAllPositions().entrySet()) {
            PositionMonitor pos = entry.getValue();
            if (pos.getRouteId() == routeId) {
                String posMsg = String.format("  Pos[%d]: seq=%d, status=%s, waiting=%s, target='%s'",
                        pos.getPositionId(), pos.getSequenceIndex(),
                        pos.getPositionStatus(), pos.isWaitingForConfirmation(),
                        pos.getPosition() != null ? pos.getPosition().getName() : "null");
                Log.d(TAG, posMsg);
                if (writeToFile) {
                    writeToFileWithRotation("INFO", posMsg);
                }
            }
        }
    }

    public void dumpCommandsForPosition(long positionId) {
        if (!enabled) return;
        String msg = "=== COMMANDS for Position " + positionId + " ===";
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
        for (Map.Entry<Long, CommandMonitor> entry : CommandMonitor.getAllCommands().entrySet()) {
            CommandMonitor cmd = entry.getValue();
            if (cmd.getPositionId() == positionId) {
                String cmdMsg = String.format("  Cmd[%d]: type=%s, status=%s, waiting=%s",
                        cmd.getCommandId(), cmd.getCommandType(),
                        cmd.getCommandStatus(), cmd.isWaitingForConfirmation());
                Log.d(TAG, cmdMsg);
                if (writeToFile) {
                    writeToFileWithRotation("INFO", cmdMsg);
                }
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // Status Change Logging Methods

    public void logPositionStatusChange(PositionMonitor position,
                                        NavigationOrderStatus oldStatus,
                                        NavigationOrderStatus newStatus) {
        if (!enabled) return;
        String msg = String.format("📍 Position[%d] status: %s -> %s (route=%d, seq=%d, target='%s')",
                position.getPositionId(), oldStatus, newStatus,
                position.getRouteId(), position.getSequenceIndex(),
                position.getPosition() != null ? position.getPosition().getName() : "null");
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public void logCommandStatusChange(CommandMonitor command,
                                       NavigationOrderStatus oldStatus,
                                       NavigationOrderStatus newStatus) {
        if (!enabled) return;
        String msg = String.format("⚙️ Command[%d] status: %s -> %s (type=%s, position=%d)",
                command.getCommandId(), oldStatus, newStatus,
                command.getCommandType(), command.getPositionId());
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public void logRouteStatusChange(RouteStore route,
                                     NavigationOrderStatus oldStatus,
                                     NavigationOrderStatus newStatus) {
        if (!enabled) return;
        String msg = String.format("📋 Route[%d] status: %s -> %s (posCount=%d, currentIndex=%d)",
                route.getRouteId(), oldStatus, newStatus,
                route.getPositionIds().size(), route.getCurrentPositionIndex());
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public void logConfirmationChange(long positionId, boolean waiting, String source) {
        if (!enabled) return;
        String msg = String.format("✅ Confirmation[%d]: waiting=%b (source=%s)", positionId, waiting, source);
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    //----------------------------------------------------------------------------------------------
    // Timing/Record Tracking Methods

    public void recordCurrentPositionFetch(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.stationName = stationName;
        timing.currentPositionFetchTIme = timestamp;

        String logMsg = String.format("⏱️ [TIMING] Robot current position FETCHED at Station[%d]: '%s' at %s",
                stationIndex, stationName, formatTime(timestamp));
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordRobotArrival(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.stationName = stationName;
        timing.robotArrivalTime = timestamp;

        String logMsg = String.format("⏱️ [TIMING] Robot ARRIVED at Station[%d]: '%s' at %s",
                stationIndex, stationName, formatTime(timestamp));
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordDialogRequest(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.dialogRequestTime = timestamp;

        String logMsg = String.format("⏱️ [TIMING] Dialog REQUESTED for Station[%d]: '%s' at %s",
                stationIndex, stationName, formatTime(timestamp));
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordDialogShown(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.dialogShownTime = timestamp;

        long delay = timing.dialogRequestTime > 0 ? timestamp - timing.dialogRequestTime : -1;

        String logMsg = String.format("⏱️ [TIMING] Dialog SHOWN for Station[%d]: '%s' at %s (delay: %dms)",
                stationIndex, stationName, formatTime(timestamp), delay);
        Log.d(TAG, logMsg);

        if (writeToFile) {
            if (delay > TIMING_THRESHOLD_WARNING_MS) {
                String warning = String.format("⚠️ [PERFORMANCE] Slow dialog show: %dms for station '%s'",
                        delay, stationName);
                writeToFileWithRotation("WARN", warning);
            }
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordVerificationStart(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.verificationStartTime = timestamp;

        long totalDelay = timing.robotArrivalTime > 0 ? timestamp - timing.robotArrivalTime : -1;

        String logMsg = String.format("⏱️ [TIMING] Verification STARTED for Station[%d]: '%s' at %s (total: %dms from arrival)",
                stationIndex, stationName, formatTime(timestamp), totalDelay);
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordRfidDetection(int stationIndex, String stationName, long timestamp, String userId) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.rfidDetectionTime = timestamp;

        long delay = timing.verificationStartTime > 0 ? timestamp - timing.verificationStartTime : -1;

        String logMsg = String.format("⏱️ [TIMING] RFID DETECTED for Station[%d]: '%s' at %s (delay: %dms, user: %s)",
                stationIndex, stationName, formatTime(timestamp), delay, userId);
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordUserPasswordClick(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.userPasswordClickTime = timestamp;

        long delay = timing.verificationStartTime > 0 ? timestamp - timing.verificationStartTime : -1;

        String logMsg = String.format("⏱️ [TIMING] User clicked password button for Station[%d]: '%s' at %s (delay: %dms from verification start)",
                stationIndex, stationName, formatTime(timestamp), delay);
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordDoorOpenStart(int stationIndex, String stationName, long timestamp, List<Integer> doorIds) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.doorOpenStartTime = timestamp;

        for (Integer doorId : doorIds) {
            timing.doorOpenTimes.put(doorId, timestamp);
        }

        String logMsg = String.format("⏱️ [TIMING] Door OPEN START for Station[%d]: '%s' at %s (doors: %s)",
                stationIndex, stationName, formatTime(timestamp), doorIds);
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordDoorOpenSuccess(int stationIndex, String stationName, long timestamp, List<Integer> doorIds, long durationMs) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.doorOpenSuccessTime = timestamp;
        timing.doorOpenDurationMs = durationMs;

        String logMsg = String.format("⏱️ [TIMING] Door OPEN SUCCESS for Station[%d]: '%s' at %s (duration: %dms, doors: %s)",
                stationIndex, stationName, formatTime(timestamp), durationMs, doorIds);
        Log.d(TAG, logMsg);

        if (writeToFile) {
            if (durationMs > TIMING_THRESHOLD_WARNING_MS) {
                String warning = String.format("⚠️ [PERFORMANCE] Slow door open: %dms for station '%s'",
                        durationMs, stationName);
                writeToFileWithRotation("WARN", warning);
            }
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordDoorOpenFailure(int stationIndex, String stationName, long timestamp, String error) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.doorOpenFailureTime = timestamp;
        timing.isFailed = true;
        timing.failureReason = "Door open failed: " + error;

        String logMsg = String.format("❌ [TIMING] Door OPEN FAILED for Station[%d]: '%s' at %s (error: %s)",
                stationIndex, stationName, formatTime(timestamp), error);
        Log.e(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("ERROR", logMsg);
        }
    }

    public void recordDoorClosed(int stationIndex, int doorId, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.get(stationIndex);
        if (timing != null) {
            timing.doorCloseTimes.put(doorId, timestamp);

            String logMsg = String.format("⏱️ [TIMING] Door %d CLOSED for Station[%d]: '%s' at %s",
                    doorId, stationIndex, timing.stationName, formatTime(timestamp));
            Log.d(TAG, logMsg);
            if (writeToFile) {
                writeToFileWithRotation("INFO", logMsg);
            }
        }
    }

    public void recordAllDoorsClosed(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.allDoorsClosedTime = timestamp;

        long doorOpenToCloseDuration = timing.doorOpenSuccessTime > 0 ? timestamp - timing.doorOpenSuccessTime : -1;

        String logMsg = String.format("⏱️ [TIMING] All doors CLOSED for Station[%d]: '%s' at %s (door open to close: %dms)",
                stationIndex, stationName, formatTime(timestamp), doorOpenToCloseDuration);
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    public void recordStationDeparture(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        StationTiming timing = stationTimings.computeIfAbsent(stationIndex,
                k -> new StationTiming(stationName, stationIndex));
        timing.stationDepartureTime = timestamp;
        timing.isCompleted = true;
        timing.calculateMetrics();

        String summary = timing.getFormattedSummary();
        Log.d(TAG, summary);
        if (writeToFile) {
            writeToFileWithRotation("INFO", summary);
        }

        timing.checkWarnings();
    }

    public StationTiming getStationTiming(int stationIndex) {
        return stationTimings.get(stationIndex);
    }

    public void printAllStationTimings() {
        if (!enabled || stationTimings.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(80));
        sb.append("\n📊 COMPLETE TIMING SUMMARY FOR ALL STATIONS");
        sb.append("\n").append("=".repeat(80));

        for (StationTiming timing : stationTimings.values()) {
            sb.append(timing.getFormattedSummary());
            sb.append("\n");
        }

        // Calculate total task time
        long taskStartTime = Long.MAX_VALUE;
        long taskEndTime = Long.MIN_VALUE;
        for (StationTiming timing : stationTimings.values()) {
            if (timing.robotArrivalTime > 0 && timing.robotArrivalTime < taskStartTime) {
                taskStartTime = timing.robotArrivalTime;
            }
            if (timing.stationDepartureTime > 0 && timing.stationDepartureTime > taskEndTime) {
                taskEndTime = timing.stationDepartureTime;
            }
        }

        if (taskStartTime < Long.MAX_VALUE && taskEndTime > Long.MIN_VALUE) {
            long totalTaskDuration = taskEndTime - taskStartTime;
            sb.append("\n").append("-".repeat(80));
            sb.append(String.format("\n📈 TOTAL TASK DURATION: %dms (%.2f seconds)",
                    totalTaskDuration, totalTaskDuration / 1000.0));
        }

        sb.append("\n").append("=".repeat(80));

        String output = sb.toString();
        Log.d(TAG, output);
        if (writeToFile) {
            writeToFileWithRotation("INFO", output);
        }
    }

    public void clearTimings() {
        if (!stationTimings.isEmpty()) {
            Log.d(TAG, "Clearing timing data for " + stationTimings.size() + " stations");
            stationTimings.clear();
        }
    }

    public void printStationDetails(TaskRecord taskRecord) {
        if (!enabled) return;

        if (taskRecord == null) {
            String msg = "No task record to print (taskRecord is null)";
            Log.d("TaskRecord", msg);
            if (writeToFile) {
                writeToFileWithRotation("INFO", msg);
            }
            return;
        }

        List<TaskRecord.StationDetail> stationDetails = taskRecord.getStationDetails();
        if (stationDetails == null || stationDetails.isEmpty()) {
            String msg = String.format("No station details available for task: %s", taskRecord.getTaskName());
            Log.d("TaskRecord", msg);
            if (writeToFile) {
                writeToFileWithRotation("INFO", msg);
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(80)).append("\n");
        sb.append("TASK RECORD DETAILS\n");
        sb.append("Timestamp: ").append(timestampFormat.format(new Date())).append("\n");
        sb.append("=".repeat(80)).append("\n");
        sb.append("Task Name: ").append(taskRecord.getTaskName()).append("\n");
        sb.append("Task Type: ").append(taskRecord.getType()).append("\n");
        sb.append("Task Status: ").append(taskRecord.getStatus()).append("\n");
        sb.append("Creator: ").append(taskRecord.getCreator() != null ? taskRecord.getCreator() : "N/A").append("\n");
        sb.append("Creator User ID: ").append(taskRecord.getCreatorUserId() != null ? taskRecord.getCreatorUserId() : "N/A").append("\n");
        sb.append("Create Time: ").append(formatTimestamp(taskRecord.getCreateTime())).append("\n");
        sb.append("Start Time: ").append(formatTimestamp(taskRecord.getStartTime())).append("\n");
        sb.append("End Time: ").append(formatTimestamp(taskRecord.getEndTime())).append("\n");
        sb.append("=".repeat(80)).append("\n");
        sb.append("STATION DETAILS (").append(stationDetails.size()).append(" stations):\n");
        sb.append("=".repeat(80)).append("\n");

        for (int i = 0; i < stationDetails.size(); i++) {
            TaskRecord.StationDetail detail = stationDetails.get(i);

            long doorOpenDurationMs = calculateDurationMs(detail.getDoorOpenTime(), detail.getDoorCloseTime());
            long stationDwellDurationMs = calculateDurationMs(detail.getArrivalTime(), detail.getDepartureTime());

            sb.append("Station #").append(i + 1).append(" (Index: ").append(detail.getIndex()).append("):\n");
            sb.append("  - Station Name: ").append(detail.getStationName()).append("\n");
            sb.append("  - Arrival Time: ").append(formatTimestamp(detail.getArrivalTime())).append("\n");
            sb.append("  - Door Open Time: ").append(formatTimestamp(detail.getDoorOpenTime())).append("\n");
            sb.append("  - Open Door ID: ").append(detail.getOpenDoorId()).append("\n");
            sb.append("  - Open Multiple Door IDs: ").append(detail.getOpenDoorIds()).append("\n");
            sb.append("  - Door Opener User ID: ").append(detail.getDoorOpenerUserId() != null ? detail.getDoorOpenerUserId() : "N/A").append("\n");
            sb.append("  - Door Opener Name: ").append(detail.getDoorOpenerName() != null ? detail.getDoorOpenerName() : "N/A").append("\n");
            sb.append("  - Door Close Time: ").append(formatTimestamp(detail.getDoorCloseTime())).append("\n");
            sb.append("  - Close Door ID: ").append(detail.getCloseDoorId()).append("\n");
            sb.append("  - Close Multiple Door IDs: ").append(detail.getCloseDoorIds()).append("\n");
            sb.append("  - Door Closer User ID: ").append(detail.getDoorCloserUserId() != null ? detail.getDoorCloserUserId() : "N/A").append("\n");
            sb.append("  - Door Closer Name: ").append(detail.getDoorCloserName() != null ? detail.getDoorCloserName() : "N/A").append("\n");
            sb.append("  - Departure Time: ").append(formatTimestamp(detail.getDepartureTime())).append("\n");
            sb.append("  - Door open duration: ").append(formatElapsedTime(doorOpenDurationMs)).append("\n");
            sb.append("  - Station dwell duration: ").append(formatElapsedTime(stationDwellDurationMs)).append("\n");
            sb.append("  ").append("-".repeat(60)).append("\n");
        }

        long totalDuration = calculateDurationMs(taskRecord.getStartTime(), taskRecord.getEndTime());
        sb.append("TOTAL TASK DURATION: ").append(formatElapsedTime(totalDuration)).append("\n");
        sb.append("=".repeat(80)).append("\n");

        String output = sb.toString();
        Log.d("TaskRecord", output);

        if (writeToFile) {
            writeToFileWithRotation("INFO", output);
        }

        // Also print timing summary if available
        printAllStationTimings();
    }

    //----------------------------------------------------------------------------------------------
    // Helper Methods

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "N/A";
        }
        return timestampFormat.format(new Date(timestamp));
    }

    private long calculateDurationMs(long startTime, long endTime) {
        if (startTime <= 0 || endTime <= 0) {
            return 0;
        }
        return endTime - startTime;
    }

    private String formatElapsedTime(long elapsedMs) {
        if (elapsedMs <= 0) {
            return "N/A";
        }
        long seconds = elapsedMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d hr %d min %d sec", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d min %d sec", minutes, seconds % 60);
        } else {
            return String.format("%d.%d sec", seconds, (elapsedMs % 1000) / 100);
        }
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0) return "N/A";
        return timeFormat.format(new Date(timestamp));
    }

    //----------------------------------------------------------------------------------------------
    // WebSocket Diagnostics

    public void logWebSocketConnectAttempt(String clientType, String host, int port) {
        if (!enabled) return;

        lastWebSocketConnectAttempt = System.currentTimeMillis();
        String msg = String.format("🔌 [WEBSOCKET] %s connecting to ws://%s:%d at %s",
                clientType, host, port, formatTime(lastWebSocketConnectAttempt));
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public void logWebSocketConnected(String clientType) {
        if (!enabled) return;

        lastWebSocketSuccessTime = System.currentTimeMillis();
        webSocketFailureCount = 0;
        String msg = String.format("✅ [WEBSOCKET] %s CONNECTED successfully at %s (after %d failures)",
                clientType, formatTime(lastWebSocketSuccessTime), webSocketFailureCount);
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public void logWebSocketFailure(String clientType, String error, int attemptCount) {
        if (!enabled) return;

        webSocketFailureCount++;
        lastWebSocketError = error;
        String msg = String.format("❌ [WEBSOCKET] %s connection FAILED (attempt %d): %s at %s",
                clientType, attemptCount, error, formatTime(System.currentTimeMillis()));
        Log.e(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("ERROR", msg);
        }

        // Also log if this is a recurring issue
        if (webSocketFailureCount > 5 && webSocketFailureCount % 5 == 0) {
            String warning = String.format("⚠️ [WEBSOCKET] %s has failed %d times consecutively. Check network connectivity.",
                    clientType, webSocketFailureCount);
            Log.w(TAG, warning);
            if (writeToFile) {
                writeToFileWithRotation("WARN", warning);
            }
        }
    }

    public void logWebSocketDisconnected(String clientType, String reason) {
        if (!enabled) return;

        String msg = String.format("🔌 [WEBSOCKET] %s DISCONNECTED: %s at %s",
                clientType, reason, formatTime(System.currentTimeMillis()));
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("WARN", msg);
        }
    }

    public void logCommandSent(String clientType, String command, String requestId) {
        if (!enabled) return;

        String shortCommand = command.length() > 200 ? command.substring(0, 200) + "..." : command;
        String msg = String.format("📤 [COMMAND] %s sending: %s (id: %s) at %s",
                clientType, shortCommand, requestId, formatTime(System.currentTimeMillis()));
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public void logCommandResponse(String clientType, String responseCmd, String requestId, long elapsedMs) {
        if (!enabled) return;

        commandFailureStats.merge(responseCmd, 0, (old, val) -> val + 1);

        String msg = String.format("📥 [RESPONSE] %s received '%s' (id: %s) in %dms",
                clientType, responseCmd, requestId, elapsedMs);
        Log.d(TAG, msg);
        if (writeToFile) {
            if (elapsedMs > 1000) {
                writeToFileWithRotation("WARN", msg + " ⚠️ SLOW RESPONSE");
            } else {
                writeToFileWithRotation("INFO", msg);
            }
        }
    }

    public void logCommandTimeout(String clientType, String command, String requestId, long timeoutMs) {
        if (!enabled) return;

        String msg = String.format("⏰ [TIMEOUT] %s command '%s' (id: %s) timed out after %dms at %s",
                clientType, command, requestId, timeoutMs, formatTime(System.currentTimeMillis()));
        Log.w(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("WARN", msg);
        }

        commandFailureStats.merge("TIMEOUT_" + command, 1, Integer::sum);
    }

    public void logInvalidMessage(String clientType, String message, String error) {
        if (!enabled) return;

        String shortMsg = message.length() > 300 ? message.substring(0, 300) + "..." : message;
        String msg = String.format("⚠️ [INVALID] %s received malformed message: %s\n   Error: %s",
                clientType, shortMsg, error);
        Log.e(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("ERROR", msg);
        }
    }

    public void logStatusQuery(String source, String requestId) {
        if (!enabled) return;

        String msg = String.format("🔍 [STATUS_QUERY] %s requested status (id: %s) at %s",
                source, requestId, formatTime(System.currentTimeMillis()));
        Log.d(TAG, msg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", msg);
        }
    }

    public String getWebSocketDiagnosticSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(60));
        sb.append("\n🔌 WEBSOCKET DIAGNOSTIC SUMMARY");
        sb.append("\n").append("=".repeat(60));
        sb.append(String.format("\n  Last connect attempt: %s", formatTime(lastWebSocketConnectAttempt)));
        sb.append(String.format("\n  Last successful connect: %s", formatTime(lastWebSocketSuccessTime)));
        sb.append(String.format("\n  Failure count: %d", webSocketFailureCount));
        sb.append(String.format("\n  Last error: %s", lastWebSocketError != null ? lastWebSocketError : "None"));

        long timeSinceLastSuccess = System.currentTimeMillis() - lastWebSocketSuccessTime;
        if (lastWebSocketSuccessTime > 0 && timeSinceLastSuccess > 10000) {
            sb.append(String.format("\n  ⚠️ Last successful connection was %dms ago", timeSinceLastSuccess));
        }

        if (!commandFailureStats.isEmpty()) {
            sb.append("\n\n  Command Failure Statistics:");
            for (Map.Entry<String, Integer> entry : commandFailureStats.entrySet()) {
                sb.append(String.format("\n    - %s: %d failures", entry.getKey(), entry.getValue()));
            }
        }

        sb.append("\n").append("=".repeat(60));
        return sb.toString();
    }

    public void resetWebSocketDiagnostics() {
        lastWebSocketConnectAttempt = 0;
        lastWebSocketSuccessTime = 0;
        webSocketFailureCount = 0;
        lastWebSocketError = null;
        commandFailureStats.clear();
        log("WebSocket diagnostics reset");
    }

    public void startNewLogSession() {
        if (appContext != null) {
            setupDebugLogDirectory();
            cleanupLegacyLogFiles();
            initializeLogFiles();
            String sessionStart = "\n\n" + "=".repeat(80) + "\n" +
                    "NEW SESSION STARTED: " + timestampFormat.format(new Date()) + "\n" +
                    "=".repeat(80) + "\n\n";
            if (writeToFile) {
                writeToFileWithRotation("INFO", sessionStart);
            }
        }
    }

    public String getDebugLogPath() {
        return debugDir != null ? debugDir.getAbsolutePath() : null;
    }

    /**
     * Record dialog shown with station index
     */
    public void logDialogShown(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        String logMsg = String.format("⏱️ [TIMING] Dialog SHOWN for Station[%d]: '%s' at %s",
                stationIndex, stationName, formatTime(timestamp));
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    /**
     * Record station departure for timing summary
     */
    public void logStationDeparture(int stationIndex, String stationName, long timestamp) {
        if (!enabled) return;

        String logMsg = String.format("⏱️ [TIMING] Station DEPARTURE for Station[%d]: '%s' at %s",
                stationIndex, stationName, formatTime(timestamp));
        Log.d(TAG, logMsg);
        if (writeToFile) {
            writeToFileWithRotation("INFO", logMsg);
        }
    }

    //----------------------------------------------------------------------------------------------
    // Station Timing Data Structure

    public static class StationTiming {
        public String stationName;
        public int stationIndex;
        public long robotArrivalTime;
        public long dialogRequestTime;
        public long dialogShownTime;
        public long verificationStartTime;
        public long rfidDetectionTime;
        public long userPasswordClickTime;
        public long doorOpenStartTime;
        public long doorOpenSuccessTime;
        public long doorOpenFailureTime;
        public long receiptDialogShownTime;
        public long allDoorsClosedTime;
        public long stationDepartureTime;
        public long currentPositionFetchTIme;

        public Map<Integer, Long> doorOpenTimes = new HashMap<>();
        public Map<Integer, Long> doorCloseTimes = new HashMap<>();

        public long timeToShowDialogMs;
        public long timeToVerificationMs;
        public long userResponseTimeMs;
        public long rfidDetectionDelayMs;
        public long doorOpenDurationMs;
        public long doorCloseDurationMs;
        public long totalStationTimeMs;

        public boolean isCompleted = false;
        public boolean isFailed = false;
        public String failureReason = null;

        public StationTiming(String stationName, int stationIndex) {
            this.stationName = stationName;
            this.stationIndex = stationIndex;
        }

        public void calculateMetrics() {
            if (dialogRequestTime > 0 && dialogShownTime > 0) {
                timeToShowDialogMs = dialogShownTime - dialogRequestTime;
            }
            if (robotArrivalTime > 0 && verificationStartTime > 0) {
                timeToVerificationMs = verificationStartTime - robotArrivalTime;
            }
            if (dialogShownTime > 0 && verificationStartTime > 0) {
                userResponseTimeMs = verificationStartTime - dialogShownTime;
            }
            if (verificationStartTime > 0 && rfidDetectionTime > 0) {
                rfidDetectionDelayMs = rfidDetectionTime - verificationStartTime;
            }
            if (doorOpenStartTime > 0 && doorOpenSuccessTime > 0) {
                doorOpenDurationMs = doorOpenSuccessTime - doorOpenStartTime;
            }
            if (robotArrivalTime > 0 && stationDepartureTime > 0) {
                totalStationTimeMs = stationDepartureTime - robotArrivalTime;
            }
        }

        public String getFormattedSummary() {
            calculateMetrics();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("\n  📊 TIMING SUMMARY for Station[%d]: '%s'", stationIndex, stationName));
            sb.append(String.format("\n    ├─ Robot arrival: %s", formatTime(robotArrivalTime)));
            sb.append(String.format("\n    ├─ Dialog request: %s", formatTime(dialogRequestTime)));
            sb.append(String.format("\n    ├─ Dialog shown: %s (+%dms)",
                    formatTime(dialogShownTime), timeToShowDialogMs));
            sb.append(String.format("\n    ├─ Verification start: %s (+%dms total)",
                    formatTime(verificationStartTime), timeToVerificationMs));
            if (userResponseTimeMs > 0) {
                sb.append(String.format("\n    ├─ User response: %dms (dialog to verification)", userResponseTimeMs));
            }
            if (rfidDetectionDelayMs > 0) {
                sb.append(String.format("\n    ├─ RFID detection: %dms", rfidDetectionDelayMs));
            }
            if (doorOpenStartTime > 0 && doorOpenSuccessTime > 0) {
                sb.append(String.format("\n    ├─ Door open: %dms", doorOpenDurationMs));
            }
            sb.append(String.format("\n    └─ Total station time: %dms (%.2f seconds)",
                    totalStationTimeMs, totalStationTimeMs / 1000.0));
            return sb.toString();
        }

        private String formatTime(long timestamp) {
            if (timestamp <= 0) return "N/A";
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(timestamp));
        }

        public void checkWarnings() {
            if (timeToShowDialogMs > TIMING_THRESHOLD_WARNING_MS) {
                Log.w(TAG, String.format("⚠️ [PERFORMANCE] Station '%s': Dialog took %dms to show (threshold: %dms)",
                        stationName, timeToShowDialogMs, TIMING_THRESHOLD_WARNING_MS));
            }
            if (userResponseTimeMs > TIMING_THRESHOLD_CRITICAL_MS) {
                Log.w(TAG, String.format("⚠️ [PERFORMANCE] Station '%s': User response took %dms (%.1f seconds)",
                        stationName, userResponseTimeMs, userResponseTimeMs / 1000.0));
            }
            if (doorOpenDurationMs > TIMING_THRESHOLD_WARNING_MS) {
                Log.w(TAG, String.format("⚠️ [PERFORMANCE] Station '%s': Door opening took %dms",
                        stationName, doorOpenDurationMs));
            }
        }
    }
}