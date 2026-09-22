package com.ezhan.amr.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.SimpleDateFormat;
import android.icu.util.Calendar;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.ParseException;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MapPoints;
import com.ezhan.amr.data.datatype.MarkerData;
import com.ezhan.amr.data.datatype.MarkerPoint;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.NavigationPathData;
import com.ezhan.amr.data.datatype.SafetyArea;
import com.ezhan.amr.data.datatype.SafetyAreasData;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackOperation;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.navigation.TaskExecutor;
import com.ezhan.amr.navigation.task.NavigationElevatorManager;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.TaskViewModel;
import com.ezhan.amr.utils.ConfigManager;
import com.ezhan.amr.utils.TaskRunningStatusHolder;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * WebSocket Server for upper system (MES, WMS, etc.)
 * This service acts as a server that:
 * - Listens for incoming WebSocket connections from upper system
 * - Sends robot status updates to connected clients
 * - Receives tasks from upper system
 * - Sends task results back to upper system
 */
public class RcsWebSocketService extends Service {
    private static final String TAG = "RcsWebSocketService";

    // WebSocket Server
    private WebSocketServer webSocketServer;
    private final ConcurrentHashMap<WebSocket, String> connectedClients = new ConcurrentHashMap<>();

    // Server configuration
    // Update these fields in the class
    private int serverPort = 8070;
    private int actualPort = -1;  // Store the actual port that was successfully bound
    private boolean isServerStarted = false;
    private String bindIp = "0.0.0.0";
    private static final int MAX_START_RETRY_COUNT = 10;
    private static final long START_RETRY_DELAY_MS = 2000;
    private static final long START_TIMEOUT_MS = 10000;
    private int serverStartRetryCount = 0;
    private boolean isServerStarting = false;
    private boolean isServiceDestroying = false;

    // Core components
    private TaskExecutor taskExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable websocketStartRetryRunnable = new Runnable() {
        @Override
        public void run() {
            attemptStartConfiguredServer();
        }
    };
    private final Runnable websocketStartTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isServerStarting && !isServerStarted) {
                Log.e(TAG, String.format("WebSocket server start timed out after %d ms", START_TIMEOUT_MS));
                cleanupFailedServer();
                scheduleServerStartRetry("server start timeout");
            }
        }
    };
    private MapViewModel mapViewModel;
    private CommandWebSocketClient commandClient;
    private StatusWebSocketClient statusClient;
    private SharedViewModel sharedViewModel;
    private TaskViewModel taskViewModel;
    private CruiseViewModel cruiseViewModel;
    private JackViewModel jackViewModel;
    private ElevatorViewModel elevatorViewModel;
    private NavigationElevatorManager elevatorManager;
    private Map<Integer, List<Position>> floorPositionsMap = new HashMap<>();

    // Network state tracking
    private ConnectivityManager connectivityManager;
    private BroadcastReceiver networkReceiver;
    private String lastNetworkState = "UNKNOWN";
    private AtomicBoolean isNetworkAvailable = new AtomicBoolean(true);

    // Service lifecycle tracking
    private static final AtomicInteger serviceInstanceCounter = new AtomicInteger(0);
    private final int serviceInstanceId;
    private long serviceCreateTime;
    private long serviceStartTime;

    // Status broadcast interval
    private static final long STATUS_BROADCAST_INTERVAL = 2000;
    private final Runnable statusBroadcastRunnable = new Runnable() {
        @Override
        public void run() {
//            broadcastStatusToAllClients();
            if (shouldRun()) {
                mainHandler.postDelayed(this, STATUS_BROADCAST_INTERVAL);
            }
        }
    };

    // Heartbeat for service health
    private static final long HEARTBEAT_INTERVAL = 30000;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            logServiceHeartbeat();
            if (shouldRun()) {
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        }
    };

    public RcsWebSocketService() {
        serviceInstanceId = serviceInstanceCounter.incrementAndGet();
        Log.i(TAG, String.format("RcsWebSocketService instance %d created", serviceInstanceId));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceCreateTime = System.currentTimeMillis();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService();
        }

        Log.d(TAG, String.format("RcsWebSocketService instance %d creating...", serviceInstanceId));
        serverPort = ConfigManager.getInstance().getServicePort("rcsWebSocketService", serverPort);
        Log.i(TAG, String.format("RCS WebSocket configured bind address: %s:%d", bindIp, serverPort));

        // Initialize dependencies
        MyApplication.getInstance().bindRcsWebSocketService(this);
        taskExecutor = MyApplication.getInstance().getTaskExecutor();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        taskViewModel = MyApplication.getInstance().getTaskViewModel();
        cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
        jackViewModel = MyApplication.getInstance().getJackViewModel();
        elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        elevatorManager = NavigationElevatorManager.getInstance();
        commandClient = sharedViewModel.getCommandClient();
        statusClient = sharedViewModel.getStatusClient();

        // Initialize network monitoring
        initNetworkMonitoring();

        // Start WebSocket server
        startWebSocketServer();

        // Start heartbeat
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);

        // Start status broadcasting
        mainHandler.postDelayed(statusBroadcastRunnable, STATUS_BROADCAST_INTERVAL);

        Log.i(TAG, String.format("RcsWebSocketService instance %d fully created", serviceInstanceId));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        serviceStartTime = System.currentTimeMillis();
        Log.i(TAG, String.format("Service instance %d onStartCommand() called, startId: %d, flags: %d",
                serviceInstanceId, startId, flags));

        // Log if this is a restart
        if ((flags & START_FLAG_REDELIVERY) != 0 || (flags & START_FLAG_RETRY) != 0) {
            Log.w(TAG, String.format("Service instance %d is being restarted/redelivered", serviceInstanceId));
        }

        // Start as sticky to ensure service restarts if killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, String.format("RcsWebSocketService instance %d onDestroy() called", serviceInstanceId));
        isServiceDestroying = true;

        // Stop all runnables
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(statusBroadcastRunnable);
        mainHandler.removeCallbacks(websocketStartRetryRunnable);
        mainHandler.removeCallbacks(websocketStartTimeoutRunnable);

        // Unregister network receiver
        if (networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Network receiver already unregistered");
            }
        }

        // Stop WebSocket server with proper cleanup
        if (webSocketServer != null) {
            try {
                // Close all connected clients gracefully
                for (WebSocket client : connectedClients.keySet()) {
                    try {
                        client.close(1000, "Service shutting down");
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing client connection", e);
                    }
                }
                connectedClients.clear();

                // Stop the server with timeout (5 seconds)
                webSocketServer.stop(5000);
                Log.d(TAG, "WebSocket server stopped successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping WebSocket server", e);
            }
            webSocketServer = null;
        }
        isServerStarted = false;
        isServerStarting = false;

        // Unbind from application
        MyApplication.getInstance().bindRcsWebSocketService(null);

        long lifetime = System.currentTimeMillis() - serviceCreateTime;
        Log.i(TAG, String.format("RcsWebSocketService instance %d destroyed. Lifetime: %d ms",
                serviceInstanceId, lifetime));

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startForegroundService() {
        String channelId = "rcs_websocket_service_channel";
        String channelName = "RCS WebSocket Service";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("RCS WebSocket Server")
                .setContentText("Waiting for connections...")
                .setSmallIcon(android.R.drawable.ic_menu_upload)  // Use system icon or your own
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(4, notification);
    }

    // ==================================================================
    // WebSocket Server Setup
    // ==================================================================

    private void startWebSocketServer() {
        mainHandler.removeCallbacks(websocketStartRetryRunnable);
        mainHandler.removeCallbacks(websocketStartTimeoutRunnable);
        serverStartRetryCount = 0;
        actualPort = -1;
        isServerStarted = false;
        isServerStarting = false;

        Log.d(TAG, String.format("Starting WebSocket server on configured address %s:%d", bindIp, serverPort));
        attemptStartConfiguredServer();
    }

    private void attemptStartConfiguredServer() {
        if (isServerRunning()) {
            Log.d(TAG, String.format("WebSocket server is already running on port %d", actualPort));
            return;
        }
        if (isServerStarting) {
            Log.d(TAG, String.format("WebSocket server is already starting on port %d", serverPort));
            return;
        }

        actualPort = -1;
        isServerStarted = false;

        // 直接尝试启动服务器，不再预检查端口可用性
        // 因为启用 SO_REUSEADDR 后，即使端口处于 TIME_WAIT 状态也可以绑定
        if (tryStartServer(serverPort)) {
            Log.i(TAG, String.format("WebSocket server start requested on configured port: %d", serverPort));
        } else {
            Log.e(TAG, String.format("Failed to start WebSocket server on configured port: %d", serverPort));
            scheduleServerStartRetry("server start failed");
        }
    }

    private void scheduleServerStartRetry(String reason) {
        if (serverStartRetryCount >= MAX_START_RETRY_COUNT) {
            Log.e(TAG, String.format("WebSocket server failed to start after %d retries. Last reason: %s",
                    MAX_START_RETRY_COUNT, reason));
            return;
        }

        serverStartRetryCount++;
        Log.w(TAG, String.format("Retrying WebSocket server start in %d ms (%d/%d), reason: %s",
                START_RETRY_DELAY_MS, serverStartRetryCount, MAX_START_RETRY_COUNT, reason));
        mainHandler.removeCallbacks(websocketStartRetryRunnable);
        mainHandler.postDelayed(websocketStartRetryRunnable, START_RETRY_DELAY_MS);
    }

    private void startWebSocketServerLegacy() {
        int startPort = serverPort;
        int maxPort = serverPort + 20;  // Try up to 20 ports

        Log.d(TAG, String.format("Looking for available port starting from %d", startPort));

        for (int tryPort = startPort; tryPort <= maxPort; tryPort++) {
            if (isPortAvailable(tryPort)) {
                Log.d(TAG, String.format("Port %d is available, attempting to start server...", tryPort));
                if (tryStartServer(tryPort)) {
                    actualPort = tryPort;
                    isServerStarted = true;
                    Log.i(TAG, String.format("✅ WebSocket server started successfully on port: %d", actualPort));
                    logServerAccessInfo();
                    return;
                }
            } else {
                Log.w(TAG, String.format("Port %d is already in use, trying next port...", tryPort));
            }
        }

        // If we get here, no ports were available
        Log.e(TAG, String.format("Failed to start WebSocket server on any port from %d to %d", startPort, maxPort));

        // Last resort: try to use port 0 (system assigned) - but this requires server to support it
        tryLastResort();
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindIp, port));
            return true;
        } catch (IOException e) {
            Log.e(TAG, String.format("Port %d is unavailable on %s: %s", port, bindIp, e.getMessage()));
            return false;
        }
    }

    private boolean isPortAvailableLegacy(int port) {
        try {
            java.net.ServerSocket serverSocket = new java.net.ServerSocket(port);
            serverSocket.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean tryStartServer(int port) {
        isServerStarted = false;
        isServerStarting = false;
        actualPort = -1;
        try {
            // Stop existing server if any
            if (webSocketServer != null) {
                try {
                    webSocketServer.stop(3000);  // Stop with 3s timeout
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping existing server", e);
                }
                webSocketServer = null;
            }

            // Create and start new server
            webSocketServer = createWebSocketServer(port);
            // Enable SO_REUSEADDR to allow immediate port reuse after restart (fixes TIME_WAIT issue)
            webSocketServer.setReuseAddr(true);
            isServerStarting = true;
            webSocketServer.start();
            mainHandler.removeCallbacks(websocketStartTimeoutRunnable);
            mainHandler.postDelayed(websocketStartTimeoutRunnable, START_TIMEOUT_MS);

            return true;
        } catch (Exception e) {
            Log.e(TAG, String.format("Failed to start server on port %d: %s", port, e.getMessage()));
            cleanupFailedServer();
            return false;
        }
    }

    private void cleanupFailedServer() {
        mainHandler.removeCallbacks(websocketStartTimeoutRunnable);
        isServerStarting = false;
        isServerStarted = false;
        actualPort = -1;

        if (webSocketServer != null) {
            try {
                webSocketServer.stop();
            } catch (Exception stopError) {
                Log.w(TAG, "Error stopping failed WebSocket server", stopError);
            }
            webSocketServer = null;
        }
    }

    private void tryLastResort() {
        try {
            // Try to use a random available port (port 0 lets the system assign one)
            Log.i(TAG, "Attempting to use system-assigned port as last resort...");
            webSocketServer = createWebSocketServer(0);  // Port 0 = system assigns any available port
            webSocketServer.setReuseAddr(true);  // Enable SO_REUSEADDR for port reuse
            webSocketServer.start();
            actualPort = webSocketServer.getPort();
            isServerStarted = true;
            Log.i(TAG, String.format("✅ WebSocket server started on system-assigned port: %d", actualPort));
            logServerAccessInfo();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server even on system-assigned port: " + e.getMessage());
        }
    }

    private WebSocketServer createWebSocketServer(int port) throws UnknownHostException {
        return new WebSocketServer(new InetSocketAddress(bindIp, port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                String clientAddress = conn.getRemoteSocketAddress().toString();
                connectedClients.put(conn, clientAddress);
                Log.i(TAG, String.format("✅ New connection opened: %s, Total clients: %d",
                        clientAddress, connectedClients.size()));

                // Send welcome message
                sendWelcomeMessage(conn);

                // Send initial status
//                sendCurrentStatus(conn);
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                String clientAddress = connectedClients.remove(conn);
                Log.i(TAG, String.format("❌ Connection closed: %s, Code: %d, Reason: %s, Total clients: %d",
                        clientAddress, code, reason, connectedClients.size()));
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                Log.d(TAG, String.format("📨 Received message from %s: %s",
                        conn.getRemoteSocketAddress(),
                        message.length() > 200 ? message.substring(0, 200) + "..." : message));

                try {
                    JSONObject jsonMessage = new JSONObject(message);
                    String messageType = jsonMessage.optString("type", "");

                    JSONObject response = processMessage(conn, jsonMessage);

                    if (response != null) {
                        conn.send(response.toString());
                        Log.d(TAG, "📤 Sent response: " + response.toString());
                    }

                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing message", e);
                    sendErrorResponse(conn, "Invalid JSON format: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG, "Error processing message", e);
                    sendErrorResponse(conn, "Server error: " + e.getMessage());
                }
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                Log.e(TAG, "WebSocket error", ex);
                if (conn != null) {
                    sendErrorResponse(conn, "Server error: " + ex.getMessage());
                } else {
                    final WebSocketServer failedServer = this;
                    mainHandler.post(() -> {
                        if (isServiceDestroying || webSocketServer != failedServer) {
                            return;
                        }
                        cleanupFailedServer();
                        scheduleServerStartRetry("server error: " + ex.getMessage());
                    });
                }
            }

            @Override
            public void onStart() {
                final WebSocketServer startedServer = this;
                mainHandler.post(() -> {
                    if (isServiceDestroying || webSocketServer != startedServer) {
                        return;
                    }
                    mainHandler.removeCallbacks(websocketStartTimeoutRunnable);
                    isServerStarting = false;
                    actualPort = getPort();
                    isServerStarted = true;
                    serverStartRetryCount = 0;
                    Log.i(TAG, String.format("WebSocket server confirmed started on port: %d", actualPort));
                    logServerAccessInfo();
                });
                Log.i(TAG, String.format("🚀 WebSocket server started on port %d", getPort()));
            }
        };
    }

    private JSONObject processMessage(WebSocket conn, JSONObject message) throws JSONException {
        String type = message.optString("type");
        JSONObject response = new JSONObject();
        response.put("requestId", message.optString("requestId", ""));
        response.put("timestamp", System.currentTimeMillis());

        if (!"task".equals(type) && isTypeOptionalControlTask(message.optString("taskType", ""))) {
            handleTaskMessage(conn, message, response);
            return response;
        }

        switch (type) {
            case "getRobotStatus":
            case "robotStatus":
                response.put("type", "robotStatus");
                response.put("data", getRobotStatus());
                Log.d(TAG, "Status requested, sending current robot status");
                break;

            case "getAllMapPoints":
            case "allMapPoints":
                response.put("type", "allMapPoints");
                response.put("data", getAllMapPoints());
                Log.d(TAG, "All map points requested");
                break;

            case "getCurrentMapInfo":
            case "currentMapInfo":
                response.put("type", "currentMapInfo");
                response.put("data", getCurrentMapInfo());
                Log.d(TAG, "Current map info requested");
                break;

            case "getSafetyAreas":
            case "safetyAreas":
                response.put("type", "safetyAreas");
                response.put("data", getSafetyAreas());
                Log.d(TAG, "Safety areas requested");
                break;

            case "getNavigatePath":
            case "navigatePath":
                response.put("type", "navigatePath");
                response.put("data", getNavigatePath());
                Log.d(TAG, "Navigate path requested");
                break;

            case "getRcsTaskStatus":
            case "rcsTaskStatus":
                String taskId = message.optString("taskId");
                if (taskId.isEmpty()) {
                    response.put("type", "error");
                    response.put("message", "Missing required parameter: taskId");
                    Log.w(TAG, "Missing taskId parameter for rcsTaskStatus");
                } else {
                    response.put("type", "rcsTaskStatus");
                    response.put("data", getRcsTaskStatus(taskId));
                    Log.d(TAG, "RCS task status requested for taskId: " + taskId);
                }
                break;

            case "getHmiTaskStatus":
            case "hmiTaskStatus":
                String dateTime = message.optString("dateTime");
                if (dateTime.isEmpty()) {
                    response.put("type", "error");
                    response.put("message", "Missing required parameter: dateTime");
                    Log.w(TAG, "Missing dateTime parameter for hmiTaskStatus");
                } else {
                    response.put("type", "hmiTaskStatus");
                    response.put("data", getHmiTaskStatus(dateTime));
                    Log.d(TAG, "HMI task status requested for date: " + dateTime);
                }
                break;

            case "hmiTaskStatusDetails":
                String detailTaskId = message.optString("taskId", "");
                String detailDateTime = message.optString("dateTime", "");
                String startTime = message.optString("startTime", "");
                String endTime = message.optString("endTime", "");
                response.put("type", "hmiTaskStatusDetails");
                response.put("data", getHmiTaskStatusDetails(detailTaskId, detailDateTime, startTime, endTime));
                Log.d(TAG, "HMI task status details requested, taskId: " + detailTaskId + ", dateTime: " + detailDateTime
                        + ", startTime: " + startTime + ", endTime: " + endTime);
                break;

            case "elevatorList":
                response.put("type", "elevatorList");
                response.put("data", getElevatorList());
                response.put("status", "success");
                Log.d(TAG, "Elevator list requested");
                break;

            case "task":
                handleTaskMessage(conn, message, response);
                break;

            case "ping":
                response.put("type", "pong");
                Log.v(TAG, "Ping received, sending pong");
                break;

            case "heartbeat":
                response.put("type", "heartbeatAck");
                Log.v(TAG, "Heartbeat received, sending ack");
                break;

            default:
                throw new JSONException("Unknown message type: " + type);
        }

        return response;
    }

    private boolean isTypeOptionalControlTask(String taskType) {
        if (taskType == null) {
            return false;
        }

        switch (taskType.trim().toLowerCase(Locale.ROOT)) {
            case "cancel":
            case "pause":
            case "resume":
            case "release":
                return true;
            default:
                return false;
        }
    }

    // ==================================================================
    // Task Data Structure
    // ==================================================================

    public static class TaskRequest {
        public String taskId;
        public String taskType;
        public String station;
        public String action;
        public String message;
        public int waitTime;
        public String waitTimeValues;
        public String taskName;
        public String mapName;
        public JSONObject params;
        public long timestamp;

        public TaskRequest(JSONObject json) throws JSONException {
            this.taskId = json.optString("taskId", "");
            this.taskType = json.optString("taskType", "");
            this.station = json.optString("station", "");
            this.action = json.optString("action", "");
            this.message = json.optString("message", "");
            this.waitTime = json.optInt("waitTime", 0);
            this.waitTimeValues = json.optString("waitTime", String.valueOf(this.waitTime));
            this.taskName = json.optString("taskName", "");
            this.mapName = json.optString("mapName", "");
            this.params = json.optJSONObject("params");
            this.timestamp = System.currentTimeMillis();
        }
    }

    private void handleTaskMessage(WebSocket conn, JSONObject message, JSONObject response) throws JSONException {
        TaskRequest task = new TaskRequest(message);
        Log.i(TAG, "📋 Task received from upper system: " + task.taskType + " - " + task.taskId);

        response.put("taskType", task.taskType);
        response.put("taskId", task.taskId);

        String validationError = validateTaskRequest(task);
        if (validationError != null) {
            response.put("type", "error");
            response.put("accepted", false);
            response.put("message", validationError);
            Log.w(TAG, "Rejecting task request: " + validationError);
            return;
        }

        if (shouldRejectTaskBusy(task)) {
            writeTaskBusyResponse(response, task);
            return;
        }

        if (handleNamedTask(task, response)) {
            return;
        }

        validationError = validatePointTaskRequest(task);
        if (validationError != null) {
            response.put("type", "error");
            response.put("accepted", false);
            response.put("message", validationError);
            Log.w(TAG, "Rejecting point task request: " + validationError);
            return;
        }

        executeTask(task);
        response.put("accepted", true);
        response.put("message", "Task accepted and processing");
    }

    // ==================================================================
    // Task Execution
    // ==================================================================

    private String validateTaskRequest(TaskRequest request) {
        if (request.taskType == null || request.taskType.trim().isEmpty()) {
            return "Missing required field: taskType";
        }
        if (!isControlTask(request.taskType)
                && (request.taskId == null || request.taskId.trim().isEmpty())) {
            return "Missing required field: taskId";
        }
        return null;
    }

    private boolean shouldRejectTaskBusy(TaskRequest request) {
        return request != null
                && !isControlTask(request.taskType)
                && TaskViewModel.TASK_STATUS_EXECUTING.equals(getGlobalTaskStatus());
    }

    private boolean isControlTask(String taskType) {
        if (taskType == null) {
            return false;
        }

        switch (taskType.trim().toLowerCase(Locale.ROOT)) {
            case "cancel":
            case "pause":
            case "resume":
            case "release":
            case "areaset":
                return true;
            default:
                return false;
        }
    }

    private void writeTaskBusyResponse(JSONObject response, TaskRequest request) throws JSONException {
        String taskType = request != null && request.taskType != null ? request.taskType.trim() : "";
        response.remove("requestId");
        response.remove("taskType");
        response.remove("taskId");
        response.remove("type");
        response.remove("accepted");
        response.put("status", "400");
        response.put("message", "Robot is executing a task, reject new task: " + taskType);
        response.put("timestamp", System.currentTimeMillis());
        Log.w(TAG, "Rejecting WebSocket task while another task is executing, taskType: " + taskType);
    }

    private String validatePointTaskRequest(TaskRequest request) {
        String taskType = request.taskType.trim().toLowerCase(Locale.ROOT);
        if (!checkWorkType(taskType)) {
            return null;
        }

        if (request.station == null || request.station.trim().isEmpty()) {
            return "Missing required field: station, or provide taskName for cruise/jack tasks";
        }
        if ("delivery".equals(taskType)) {
            String mapValidationError = validateDeliveryMapPrefix(request);
            if (mapValidationError != null) {
                return mapValidationError;
            }
        }
        if ("cruise".equals(taskType) && (request.message == null || request.message.trim().isEmpty())) {
            return "Missing required field: message for cruise station task";
        }
        return null;
    }

    private boolean handleNamedTask(TaskRequest request, JSONObject response) throws JSONException {
        if (request.taskName == null || request.taskName.trim().isEmpty()) {
            return false;
        }

        String taskType = request.taskType.trim().toLowerCase(Locale.ROOT);
        switch (taskType) {
            case "cruise":
                CruiseTask cruiseTask = findCruiseTaskByName(request.taskName);
                if (cruiseTask == null) {
                    rejectNamedTask(response, "Cruise task not found: " + request.taskName);
                    return true;
                }
                String cruiseValidationError = validateCruiseTaskPoints(cruiseTask);
                if (cruiseValidationError != null) {
                    rejectNamedTask(response, cruiseValidationError);
                    return true;
                }
                markGlobalTaskExecuting(request.taskType, cruiseTask.getName(), request.taskId);
                taskExecutor.executeByTaskName(cruiseTask.getName(), request.taskId, request.taskName, createNamedTaskCallback(request));
                acceptNamedTask(response, cruiseTask.getName());
                return true;
            case "jack":
                JackTask jackTask = findJackTaskByName(request.taskName);
                if (jackTask == null) {
                    rejectNamedTask(response, "Jack task not found: " + request.taskName);
                    return true;
                }
                String jackValidationError = validateJackTaskPoints(jackTask);
                if (jackValidationError != null) {
                    rejectNamedTask(response, jackValidationError);
                    return true;
                }
                markGlobalTaskExecuting(request.taskType, jackTask.getTaskName(), request.taskId);
                taskExecutor.executeByTaskName(jackTask.getTaskName(), request.taskId, request.taskName, createNamedTaskCallback(request));
                acceptNamedTask(response, jackTask.getTaskName());
                return true;
            default:
                rejectNamedTask(response, "taskName is only supported for cruise and jack tasks");
                return true;
        }
    }

    private void acceptNamedTask(JSONObject response, String taskName) throws JSONException {
        response.put("accepted", true);
        response.put("taskName", taskName);
        response.put("message", "Task accepted and processing");
    }

    private void rejectNamedTask(JSONObject response, String message) throws JSONException {
        response.put("type", "error");
        response.put("accepted", false);
        response.put("message", message);
        Log.w(TAG, "Rejecting named task request: " + message);
    }

    private CruiseTask findCruiseTaskByName(String taskName) {
        if (cruiseViewModel == null || taskName == null) {
            return null;
        }

        Map<Integer, CruiseTask> cruiseTasks = cruiseViewModel.getCruiseTaskMap().getValue();
        if (cruiseTasks == null || cruiseTasks.isEmpty()) {
            return null;
        }

        for (CruiseTask task : cruiseTasks.values()) {
            if (task != null && task.getName() != null &&
                    task.getName().trim().equalsIgnoreCase(taskName.trim())) {
                return task;
            }
        }
        return null;
    }

    private JackTask findJackTaskByName(String taskName) {
        if (jackViewModel == null || taskName == null) {
            return null;
        }

        Map<Integer, JackTask> jackTasks = jackViewModel.getJackTaskMap().getValue();
        if (jackTasks == null || jackTasks.isEmpty()) {
            return null;
        }

        for (JackTask task : jackTasks.values()) {
            if (task != null && task.getTaskName() != null &&
                    task.getTaskName().trim().equalsIgnoreCase(taskName.trim())) {
                return task;
            }
        }
        return null;
    }

    private String validateCruiseTaskPoints(CruiseTask task) {
        if (task == null) {
            return "Cruise task is null";
        }
        String taskName = task.getName() != null ? task.getName() : "";
        List<String> stationKeys = task.getStationKeys();
        if (stationKeys == null || stationKeys.isEmpty()) {
            return "Cruise task location list is empty: " + taskName;
        }

        for (String key : stationKeys) {
            if (key == null || key.trim().isEmpty()) {
                return "Cruise task point key is empty: task=" + taskName;
            }
            String[] parts = key.trim().split("_");
            if (parts.length != 2) {
                return "Invalid cruise task point key: task=" + taskName + ", key=" + key;
            }
            try {
                int floor = Integer.parseInt(parts[0]);
                int pointId = Integer.parseInt(parts[1]);
                if (getPositionByIdAndFloor(pointId, floor) == null) {
                    return "Cruise task point not found: task=" + taskName
                            + ", key=" + key + ", floor=" + floor + ", pointId=" + pointId;
                }
            } catch (NumberFormatException e) {
                return "Invalid cruise task point key: task=" + taskName + ", key=" + key;
            }
        }
        return null;
    }

    private String validateJackTaskPoints(JackTask task) {
        if (task == null) {
            return "Jack task is null";
        }
        String taskName = task.getTaskName() != null ? task.getTaskName() : "";
        List<JackOperation> operations = task.getOperations();
        if (operations == null || operations.isEmpty()) {
            return "Jack task location list is empty: " + taskName;
        }

        for (JackOperation operation : operations) {
            if (operation == null) {
                return "Jack task operation is null: task=" + taskName;
            }
            Integer pointId = operation.getPointId();
            int floor = operation.getFloor();
            if (getPositionByIdAndFloor(pointId, floor) == null) {
                return "Jack task point not found: task=" + taskName
                        + ", pointName=" + operation.getPointName()
                        + ", pointId=" + pointId
                        + ", floor=" + floor;
            }
        }
        return null;
    }

    private Position getPositionByIdAndFloor(Integer pointId, int floor) {
        if (pointId == null || mapViewModel == null) {
            Log.w("taskDebug7", "[LOOKUP] getPositionByIdAndFloor: pointId or mapViewModel is null");
            return null;
        }

        String currentBuilding = mapViewModel.getCurrentBuilding();
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        int buildings = mapPoints != null ? mapPoints.getAllBuildings().size() : 0;
        int totalPoints = mapPoints != null ? mapPoints.getTotalPositionCount() : 0;
        Log.i("taskDebug7", "[LOOKUP] getPositionByIdAndFloor: pointId=" + pointId +
                ", floor=" + floor + ", currentBuilding='" + currentBuilding + "'" +
                ", mapPoints buildings=" + buildings + ", totalPoints=" + totalPoints);

        Building currentBld = mapPoints != null
                ? mapPoints.getBuilding(currentBuilding)
                : null;
        if (currentBld == null) {
            Log.w("taskDebug7", "[LOOKUP] FAIL: currentBuilding '" + currentBuilding + "' NOT in mapPoints");
            return null;
        }

        FloorPoints floorPoints = currentBld.getFloorPoints(floor);
        if (floorPoints == null) {
            Log.w("taskDebug7", "[LOOKUP] FAIL: floor " + floor + " NOT in building " + currentBuilding);
            return null;
        }

        List<Position> workPoints = floorPoints.getWorkPoints();
        if (workPoints == null) {
            Log.w("taskDebug7", "[LOOKUP] FAIL: workPoints is null");
            return null;
        }

        Log.i("taskDebug7", "[LOOKUP] workPoints count=" + workPoints.size());
        for (Position position : workPoints) {
            if (position != null && position.getId() == pointId) {
                Log.i("taskDebug7", "[LOOKUP] FOUND: name=" + position.getName() + ", id=" + position.getId());
                return position;
            }
        }
        Log.w("taskDebug7", "[LOOKUP] FAIL: pointId " + pointId + " not in workPoints");
        return null;
    }

    private void executeTask(TaskRequest request) {
        BroadcastHelper broadcastHelper = MyApplication.getBroadcastHelper();

        switch (request.taskType.toLowerCase()) {
            case "cancel":
            case "pause":
            case "resume":
            case "release":
            case "areaset":
                mainHandler.post(() -> {
                    try {
                        switch (request.taskType.toLowerCase()) {
                            case "cancel":
                                markGlobalTaskIdle();
                                broadcastHelper.sendCancelCommand();
                                break;
                            case "pause":
                                Log.i(TAG, "⏸️ Pause command from upper system");
                                broadcastHelper.sendPauseCommand();
                                break;
                            case "resume":
                                Log.i(TAG, "▶️ Resume command from upper system");
                                broadcastHelper.sendResumeCommand();
                                break;
                            case "release":
                                Log.i(TAG, "⏭️ Release command from upper system");
                                broadcastHelper.sendReleaseCommand();
                                break;
                            case "areaset":
                                Log.i(TAG, "📍 Set area command from upper system");
                                commandClient.setArea(request.params);
                                break;
                        }
                        Log.i(TAG, "Simple task executed: " + request.taskType);
                        sendTaskResult(request.taskId, true,
                                "Task " + request.taskType + " executed successfully", null);
                    } catch (Exception e) {
                        Log.e(TAG, "Error executing simple task: " + request.taskType, e);
                        sendTaskResult(request.taskId, false,
                                "Failed to execute task: " + e.getMessage(), null);
                    }
                });
                return;
        }

        if ("park".equalsIgnoreCase(request.taskType) || "charge".equalsIgnoreCase(request.taskType)) {
            mainHandler.post(() -> {
                try {
                    List<Position> taskObject = createMainTaskObject(request);
                    if (taskObject != null && !taskObject.isEmpty()) {
                        markGlobalTaskExecuting(request.taskType, resolveTaskName(request, taskObject), request.taskId);
                        taskExecutor.executeByTaskObject(request.taskType, taskObject,
                                createTaskCallback(request));
                        Log.i(TAG, "Task executed: " + request.taskType + " - " + request.action);
                    } else {
                        markGlobalTaskIdle();
                        int currentFloor = mapViewModel != null ? mapViewModel.getCurrentFloor() : 0;
                        String currentBuilding = mapViewModel != null ? mapViewModel.getCurrentBuilding() : "null";
                        MultiBuildingMapPoints mp = mapViewModel != null ? mapViewModel.getCurrentMapPoints().getValue() : null;
                        int buildings = mp != null ? mp.getAllBuildings().size() : 0;
                        int totalPoints = mp != null ? mp.getTotalPositionCount() : 0;
                        Log.e("taskDebug7", "[EXEC-FAIL] main task '" + request.taskType +
                                "' NOT FOUND. currentBuilding='" + currentBuilding + "', floor=" + currentFloor +
                                ", mapPoints buildings=" + buildings + ", totalPoints=" + totalPoints);
                        sendTaskResult(request.taskId, false,
                                "Position not found for " + request.taskType + " on current floor: " + currentFloor,
                                null);
                    }
                } catch (Exception e) {
                    markGlobalTaskIdle();
                    Log.e(TAG, "Error executing task: " + request.taskType, e);
                    sendTaskResult(request.taskId, false, "Error executing task: " + e.getMessage(), null);
                }
            });
            return;
        }

        // Handle elevator task type first
        if ("elevator".equalsIgnoreCase(request.taskType)) {
            mainHandler.post(() -> {
                try {
                    // Parse elevator availability from request
                    boolean available = false;
                    String elevatorId = null;

                    if (request.params != null) {
                        elevatorId = request.params.optString("elevatorId", null);
                        available = request.params.optBoolean("availability", false);
                    }

                    if (elevatorId == null || elevatorId.isEmpty()) {
                        Log.e(TAG, "Elevator task missing elevatorId");
                        sendTaskResult(request.taskId, false, "Missing elevatorId parameter", null);
                        return;
                    }

                    // Update elevator availability in SharedViewModel
                    if (sharedViewModel != null) {
                        Map<String, Boolean> availability = sharedViewModel.getElevatorAvailabilityValue();
                        if (availability == null) {
                            availability = new HashMap<>();
                        }
                        availability.put(elevatorId, available);
                        sharedViewModel.setElevatorAvailability(availability);

                        Log.i(TAG, "✅ Elevator availability updated - ID: " + elevatorId + ", available: " + available);

                        // Send success response
                        JSONObject details = new JSONObject();
                        details.put("elevatorId", elevatorId);
                        details.put("availability", available);
                        sendTaskResult(request.taskId, true, "Elevator availability updated", details);
                    } else {
                        Log.e(TAG, "SharedViewModel is null, cannot update elevator availability");
                        sendTaskResult(request.taskId, false, "SharedViewModel not available", null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing elevator task", e);
                    sendTaskResult(request.taskId, false, "Error: " + e.getMessage(), null);
                }
            });
            return;
        }

        // For tasks that require position lookup
        if (checkWorkType(request.taskType.toLowerCase())) {
            if ("delivery".equalsIgnoreCase(request.taskType)) {
                List<Position> deliveryTaskObject = createDeliveryTaskObject(request);
                if (deliveryTaskObject == null || deliveryTaskObject.isEmpty()) {
                    Log.e("taskDebug7", "[EXEC-FAIL] delivery: station='" + request.station +
                            "', mapName='" + request.mapName + "'");
                    sendTaskResult(request.taskId, false,
                            "Position not found for station: " + request.station, null);
                    return;
                }
            } else {
                Position taskPosition = getPositionByStationAndMap(request.station, request.mapName);
                if (taskPosition == null) {
                    MultiBuildingMapPoints mp = mapViewModel != null ? mapViewModel.getCurrentMapPoints().getValue() : null;
                    int buildings = mp != null ? mp.getAllBuildings().size() : 0;
                    int totalPoints = mp != null ? mp.getTotalPositionCount() : 0;
                    Log.e("taskDebug7", "[EXEC-FAIL] " + request.taskType + ": station='" + request.station +
                            "', mapName='" + request.mapName + "', mapPoints buildings=" + buildings +
                            ", totalPoints=" + totalPoints);
                    sendTaskResult(request.taskId, false,
                            "Position not found for station: " + request.station, null);
                    return;
                }
            }
        }

        // Execute complex task
        mainHandler.post(() -> {
            try {
                List<Position> taskObject = null;
                switch (request.taskType.toLowerCase()) {
                    case "delivery":
                        taskObject = createDeliveryTaskObject(request);
                        break;
                    case "cruise":
                        taskObject = createCruiseTaskObject(request);
                        break;
                    case "jack":
                        taskObject = createJackTaskObject(request);
                        break;
                    default:
                        Log.w(TAG, "Unknown task type: " + request.taskType);
                }

                if (taskObject != null && !taskObject.isEmpty()) {
                    markGlobalTaskExecuting(request.taskType, resolveTaskName(request, taskObject), request.taskId);
                    taskExecutor.executeByTaskObject(request.taskType, taskObject,
                            createTaskCallback(request));
                    Log.i(TAG, "Task executed: " + request.taskType + " - " + request.action);
                } else {
                    markGlobalTaskIdle();
                    Log.e(TAG, "Failed to create task object for: " + request.taskType);
                    sendTaskResult(request.taskId, false, "Failed to create task object", null);
                }
            } catch (Exception e) {
                markGlobalTaskIdle();
                Log.e(TAG, "Error executing task: " + request.taskType, e);
                sendTaskResult(request.taskId, false, "Error executing task: " + e.getMessage(), null);
            }
        });
    }

    private TaskExecutor.TaskExecutionCallback createTaskCallback(TaskRequest request) {
        return new TaskExecutor.TaskExecutionCallback() {
            @Override
            public void onTaskStarted(String taskType, String taskName) {
                markGlobalTaskExecuting(request.taskType,
                        firstNonEmpty(request.taskName, taskName, request.station),
                        request.taskId);
                Log.i(TAG, "Task started: " + taskType + " - " + taskName);
                JSONObject details = new JSONObject();
                try {
                    details.put("taskType", request.taskType);
                    details.put("taskName", taskName);
                } catch (JSONException e) {}
                sendTaskResult(request.taskId, true, "Task started", details);
            }

            @Override
            public void onTaskCompleted(String taskType, String taskName) {
                markGlobalTaskCompleted();
                Log.i(TAG, "Task completed: " + taskType + " - " + taskName);
                JSONObject details = new JSONObject();
                try {
                    details.put("taskType", request.taskType);
                    details.put("taskName", taskName);
                    details.put("duration", System.currentTimeMillis() - serviceStartTime);
                } catch (JSONException e) {}
                sendTaskResult(request.taskId, true, "Task completed successfully", details);
            }

            @Override
            public void onTaskFailed(String taskType, String taskName, String reason) {
                markGlobalTaskIdle();
                Log.e(TAG, "Task failed: " + taskType + " - " + taskName + ", reason: " + reason);
                JSONObject details = new JSONObject();
                try {
                    details.put("taskType", request.taskType);
                    details.put("taskName", taskName);
                    details.put("reason", reason);
                } catch (JSONException e) {}
                sendTaskResult(request.taskId, false, reason, details);
            }

            @Override
            public void onTaskMatched(String taskType, String taskName) {
                Log.d(TAG, "Task matched: " + taskType + " - " + taskName);
            }
        };
    }

    private TaskExecutor.TaskExecutionCallback createNamedTaskCallback(TaskRequest request) {
        return new TaskExecutor.TaskExecutionCallback() {
            @Override
            public void onTaskStarted(String taskType, String taskName) {
                markGlobalTaskExecuting(request.taskType,
                        firstNonEmpty(request.taskName, taskName, request.station),
                        request.taskId);
                Log.i(TAG, "Named task activity started: " + taskType + " - " + taskName);
            }

            @Override
            public void onTaskCompleted(String taskType, String taskName) {
                markGlobalTaskCompleted();
                Log.i(TAG, "Named task completed: " + taskType + " - " + taskName);
            }

            @Override
            public void onTaskFailed(String taskType, String taskName, String reason) {
                markGlobalTaskIdle();
                Log.e(TAG, "Named task failed: " + taskType + " - " + taskName + ", reason: " + reason);
                sendTaskResult(request.taskId, false, reason, null);
            }

            @Override
            public void onTaskMatched(String taskType, String taskName) {
                Log.d(TAG, "Named task matched: " + taskType + " - " + taskName);
            }
        };
    }

    private String getGlobalTaskStatus() {
        return taskViewModel != null
                ? taskViewModel.getCurrentTaskStatusValue()
                : TaskViewModel.TASK_STATUS_IDLE;
    }

    private void markGlobalTaskExecuting(String taskType, String taskName, String taskId) {
        if (taskViewModel != null) {
            taskViewModel.markTaskExecuting(taskType, taskName, taskId);
        }
    }

    private void markGlobalTaskIdle() {
        if (taskViewModel != null) {
            taskViewModel.markTaskIdle();
        }
    }

    private void markGlobalTaskCompleted() {
        if (taskViewModel != null) {
            taskViewModel.markTaskCompleted();
        }
    }

    private String resolveTaskName(TaskRequest request, List<Position> positions) {
        if (request != null && request.taskName != null && !request.taskName.trim().isEmpty()) {
            return request.taskName.trim();
        }

        List<String> positionNames = new ArrayList<>();
        if (positions != null) {
            for (Position position : positions) {
                if (position != null && position.getName() != null
                        && !position.getName().trim().isEmpty()
                        && !positionNames.contains(position.getName().trim())) {
                    positionNames.add(position.getName().trim());
                }
            }
        }
        if (!positionNames.isEmpty()) {
            return String.join(",", positionNames);
        }
        return firstNonEmpty(request != null ? request.station : null,
                request != null ? request.taskType : null);
    }

    private String firstNonEmpty(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return TaskViewModel.TASK_INFO_NONE;
    }

    private String resolveTaskId(TaskRequest request) {
        return firstNonEmpty(request != null ? request.taskId : null,
                "HMI-" + System.currentTimeMillis());
    }

    private void sendTaskResult(String taskId, boolean success, String result, JSONObject details) {
        try {
            JSONObject taskResult = new JSONObject();
            taskResult.put("type", "taskResult");
            taskResult.put("taskId", taskId);
            taskResult.put("success", success);
            taskResult.put("result", result);
            taskResult.put("timestamp", System.currentTimeMillis());
            if (details != null) {
                taskResult.put("details", details);
            }

            for (WebSocket client : connectedClients.keySet()) {
                if (client.isOpen()) {
                    client.send(taskResult.toString());
                }
            }
            Log.d(TAG, "Task result sent: " + taskId + " - " + (success ? "SUCCESS" : "FAILED"));
        } catch (JSONException e) {
            Log.e(TAG, "Error sending task result", e);
        }
    }

    private String validateDeliveryMapPrefix(TaskRequest request) {
        if (request == null || request.mapName == null || request.mapName.trim().isEmpty()) {
            Log.w(TAG, "Delivery task rejected: mapName is empty");
            return "Current map unreachable, please check destination map";
        }

        AgvStatusResponse statusResponse = statusClient != null ? statusClient.getLastStatusResponse() : null;
        String currentMapName = statusResponse != null
                && statusResponse.data != null
                && statusResponse.data.pos != null
                ? statusResponse.data.pos.mapName
                : "";
        String currentPrefix = extractReachabilityMapPrefix(currentMapName);
        if (currentPrefix.isEmpty()) {
            Log.w(TAG, "Delivery task rejected: current map is empty or invalid, currentMap=" + currentMapName);
            return "Current map unreachable, please check destination map";
        }

        List<String> mapNames = splitCsv(request.mapName);
        if (mapNames.isEmpty()) {
            Log.w(TAG, "Delivery task rejected: no destination map names, requestMap=" + request.mapName);
            return "Current map unreachable, please check destination map";
        }

        for (String destinationMapName : mapNames) {
            String destinationPrefix = extractReachabilityMapPrefix(destinationMapName);
            if (destinationPrefix.isEmpty() || !currentPrefix.equals(destinationPrefix)) {
                Log.w(TAG, "Delivery task rejected: map prefix mismatch, currentMap="
                        + currentMapName + ", currentPrefix=" + currentPrefix
                        + ", destinationMap=" + destinationMapName
                        + ", destinationPrefix=" + destinationPrefix);
                return "Current map unreachable, please check destination map";
            }
        }

        String mapPointValidationError = validateDeliveryMapsAndStations(
                splitCsv(request.station), mapNames);
        if (mapPointValidationError != null) {
            return mapPointValidationError;
        }

        return null;
    }

    private String extractReachabilityMapPrefix(String mapName) {
        if (mapName == null) {
            return "";
        }

        String normalized = mapName.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        normalized = normalized.replaceFirst("\\.[^.]+$", "");
        int lastUnderscore = normalized.lastIndexOf('_');
        if (lastUnderscore > 0) {
            normalized = normalized.substring(0, lastUnderscore);
        }

        normalized = normalized.replaceFirst("\\d+$", "");
        return normalized.trim();
    }

    private String validateDeliveryMapsAndStations(List<String> stations, List<String> mapNames) {
        for (String mapName : mapNames) {
            if (findFloorPointsByMapName(mapName) == null) {
                Log.w(TAG, "Delivery task rejected: destination map does not exist, mapName=" + mapName);
                return "Destination map does not exist: " + mapName;
            }
        }

        if (stations == null || stations.isEmpty()) {
            return "Missing required field: station";
        }

        for (int i = 0; i < stations.size(); i++) {
            String station = stations.get(i);
            String mapName = valueAtOrLast(mapNames, i);
            FloorPoints floorPoints = findFloorPointsByMapName(mapName);
            if (floorPoints == null) {
                Log.w(TAG, "Delivery task rejected: destination map does not exist, station="
                        + station + ", mapName=" + mapName);
                return "Destination map does not exist: " + mapName;
            }

            if (findPositionInFloor(floorPoints, station) == null) {
                Log.w(TAG, "Delivery task rejected: station is not on destination map, station="
                        + station + ", mapName=" + mapName);
                return "Station does not exist in destination map: station=" + station + ", mapName=" + mapName;
            }
        }

        return null;
    }

    private FloorPoints findFloorPointsByMapName(String mapName) {
        if (mapName == null || mapName.trim().isEmpty() || mapViewModel == null) {
            return null;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            return null;
        }

        String normalizedMapName = normalizeMapNameForValidation(mapName);
        for (Building building : mapPoints.getAllBuildings().values()) {
            if (building == null) {
                continue;
            }

            for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
                FloorPoints floorPoints = entry.getValue();
                if (floorPoints == null) {
                    continue;
                }

                if (isSameValidationMap(normalizedMapName, floorPoints.getMapPrefix())) {
                    return floorPoints;
                }

                String generatedMapName = MultiBuildingMapPoints.generateMapName(
                        building.getMapPrefix(), building.getBuildingId(), entry.getKey());
                if (isSameValidationMap(normalizedMapName, generatedMapName)) {
                    return floorPoints;
                }

                for (Position position : floorPoints.getAllPoints()) {
                    if (position != null && isSameValidationMap(normalizedMapName, position.getMapName())) {
                        return floorPoints;
                    }
                }
            }
        }

        return null;
    }

    private boolean isSameValidationMap(String normalizedMapName, String candidateMapName) {
        return normalizedMapName != null
                && !normalizedMapName.isEmpty()
                && normalizedMapName.equals(normalizeMapNameForValidation(candidateMapName));
    }

    private String normalizeMapNameForValidation(String mapName) {
        if (mapName == null) {
            return "";
        }
        return mapName.trim().replaceFirst("\\.[^.]+$", "");
    }

    // ==================================================================
    // Task Object Creation Helpers
    // ==================================================================

    private List<Position> createDeliveryTaskObject(TaskRequest request) {
        try {
            if (request.station != null && !request.station.isEmpty()) {
                List<Position> taskObject = new ArrayList<>();
                List<String> stations = splitCsv(request.station);
                List<String> mapNames = splitCsv(request.mapName);
                List<String> waitTimes = splitCsv(request.waitTimeValues);

                for (int i = 0; i < stations.size(); i++) {
                    String station = stations.get(i);
                    String mapName = valueAtOrLast(mapNames, i);
                    Position taskPosition = getPositionByStationAndMap(station, mapName);
                    if (taskPosition == null) {
                        Log.e(TAG, "Position not found for delivery station: " + station + ", mapName: " + mapName);
                        return null;
                    }

                    String positionName = taskPosition.getName() != null ? taskPosition.getName() : "null";
                    taskPosition.setMessage(getString(R.string.navigating_to_task_point, positionName));
                    taskPosition.setTaskType(1);
                    taskPosition.setTaskId(request.taskId);
                    taskPosition.setStayDuration(parseIntOrDefault(valueAtOrLast(waitTimes, i), 0));
                    taskObject.add(taskPosition);
                }
                return taskObject;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating delivery task", e);
        }
        return null;
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return result;
        }

        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String valueAtOrLast(List<String> values, int index) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (index < values.size()) {
            return values.get(index);
        }
        return values.get(values.size() - 1);
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private List<Position> createCruiseTaskObject(TaskRequest request) {
        try {
            if (request.station != null && !request.station.isEmpty()) {
                List<Position> taskObject = new ArrayList<>();
                Position taskPosition = getPositionByStationAndMap(request.station, request.mapName);
                if (taskPosition == null) return null;
                String positionName = taskPosition.getName() != null ? taskPosition.getName() : "null";
                List<Integer> message = Arrays.stream(request.message.replaceAll("\\s+", "").split(","))
                        .map(Integer::parseInt)
                        .collect(Collectors.toList());
                taskPosition.setMessage(getString(R.string.resuming_format,
                        positionName,
                        message.get(0),
                        message.get(1),
                        message.get(2),
                        message.get(3)));
                taskPosition.setTaskType(2);
                taskPosition.setTaskId(request.taskId);
                taskObject.add(taskPosition);
                return taskObject;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating cruise task", e);
        }
        return null;
    }

    private List<Position> createJackTaskObject(TaskRequest request) {
        try {
            if (request.station != null && !request.station.isEmpty()) {
                List<Position> taskObject = new ArrayList<>();
                Position taskPosition = getPositionByStationAndMap(request.station, request.mapName);
                if (taskPosition == null) return null;
                String positionName = taskPosition.getName() != null ? taskPosition.getName() : "null";
                taskPosition.setMessage(getString(R.string.navigating_to_task_point, positionName));
                taskPosition.setTaskType(3);
                taskPosition.setTaskId(request.taskId);

                switch (request.action) {
                    case "NonRecognizeAndLoad":
                        taskPosition.setType(8);
                        break;
                    case "NonRecognizeAndUnload":
                        taskPosition.setType(9);
                        break;
                    case "RecognizeAndLoad":
                        taskPosition.setType(5);
                        break;
                    case "RecognizeAndUnload":
                        taskPosition.setType(6);
                        break;
                    case "Backout":
                        taskPosition.setType(7);
                        break;
                    default:
                        taskPosition.setType(1);
                        break;
                }
                taskObject.add(taskPosition);
                return taskObject;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating jack task", e);
        }
        return null;
    }

    private List<Position> createMainTaskObject(TaskRequest request) {
        try {
            Position taskPosition = null;
            List<Position> taskObject = new ArrayList<>();
            if (mapViewModel == null) {
                return taskObject;
            }

            MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
            Building building = null;
            String currentBuildingId = mapViewModel.getCurrentBuilding();
            if (mapPoints != null && currentBuildingId != null) {
                building = mapPoints.getBuilding(currentBuildingId);
            }
            if (building == null && mapPoints != null) {
                building = mapPoints.getFirstNonZeroBuildingOrDefault();
            }
            if (building == null) {
                return taskObject;
            }

            int currentFloor = mapViewModel.getCurrentFloor();
            String buildingId = building.getBuildingId();
            if (request.taskType.equals("charge")) {
                // Retrieve charge position from mapViewModel
                Position chargePosition = mapViewModel.getNearestChargePoint(buildingId, currentFloor);
                if (chargePosition != null) {
                    taskPosition = chargePosition;
                    taskPosition.setTaskType(4);
                    taskPosition.setType(10);
                    taskPosition.setTaskId(resolveTaskId(request));
                    taskPosition.setMessage(getString(R.string.navigating_to_task_point,
                            chargePosition.getName() != null ? chargePosition.getName() : "null"));
                }
            } else {
                // Retrieve home position from mapViewModel
                Position homePosition = mapViewModel.getNearestParkPoint(buildingId, currentFloor);
                if (homePosition != null) {
                    taskPosition = homePosition;
                    taskPosition.setTaskType(5);
                    taskPosition.setType(12);
                    taskPosition.setTaskId(resolveTaskId(request));
                    taskPosition.setMessage(getString(R.string.navigating_to_task_point,
                            homePosition.getName() != null ? homePosition.getName() : "null"));
                }
            }
            if (taskPosition != null) {
                taskObject.add(taskPosition);
            }
            return taskObject;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid station ID for main task: " + request.station);
        }
        return null;
    }

    /**
     * 通过名称获取位置信息
     */
    private Position getPositionByStationAndMap(String station, String mapName) {
        if (station == null || station.trim().isEmpty() || mapViewModel == null) {
            Log.w("taskDebug7", "[LOOKUP2] getPositionByStationAndMap: station or mapViewModel null");
            return null;
        }

        String stationName = station.trim();
        int floor = extractMapFloor(mapName);
        String buildingId = MultiBuildingMapPoints.extractBuildingId(mapName);
        Log.i("taskDebug7", "[LOOKUP2] getPositionByStationAndMap: station='" + stationName +
                "', mapName='" + mapName + "', buildingId='" + buildingId + "', floor=" + floor);

        Position exactMapPosition = findPositionInFloor(findFloorPointsByMapName(mapName), stationName);
        if (exactMapPosition != null) {
            Log.i("taskDebug7", "[LOOKUP2] FOUND via exact map match: name=" + exactMapPosition.getName());
            return exactMapPosition;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            Log.e("taskDebug7", "[LOOKUP2] FAIL: mapPoints is null");
            return null;
        }

        Building targetBuilding = mapPoints.getBuilding(buildingId);
        if (targetBuilding == null) {
            Log.w("taskDebug7", "[LOOKUP2] targetBuilding '" + buildingId + "' NOT in mapPoints (buildings=" +
                    mapPoints.getAllBuildings().size() + ")");
        }
        Position position = findPositionInBuildingFloor(targetBuilding, floor, stationName);
        if (position != null) {
            Log.i("taskDebug7", "[LOOKUP2] FOUND in target building/floor: name=" + position.getName());
            return position;
        }

        for (Building building : mapPoints.getAllBuildings().values()) {
            if (building == targetBuilding) {
                continue;
            }
            position = findPositionInBuildingFloor(building, floor, stationName);
            if (position != null) {
                Log.w("taskDebug7", "[LOOKUP2] FOUND in OTHER building same floor: station=" + stationName +
                        ", requestedBuilding=" + buildingId + ", actualBuilding=" + building.getBuildingId());
                return position;
            }
        }

        for (Building building : mapPoints.getAllBuildings().values()) {
            if (building == null) {
                continue;
            }
            for (FloorPoints floorPoints : building.getAllFloorPoints().values()) {
                position = findPositionInFloor(floorPoints, stationName);
                if (position != null) {
                    Log.w("taskDebug7", "[LOOKUP2] FOUND via full-map fallback: station=" + stationName +
                            ", actualMap=" + position.getMapName());
                    return position;
                }
            }
        }

        Log.e("taskDebug7", "[LOOKUP2] FAIL: Position NOT FOUND, station=" + stationName +
                ", mapName=" + mapName + ", buildingId=" + buildingId + ", floor=" + floor +
                ", mapPoints buildings=" + mapPoints.getAllBuildings().size() +
                ", totalPoints=" + mapPoints.getTotalPositionCount());
        return null;
    }

    private Position findPositionInBuildingFloor(Building building, int floor, String stationName) {
        if (building == null) {
            return null;
        }

        return findPositionInFloor(building.getFloorPoints(floor), stationName);
    }

    private Position findPositionInFloor(FloorPoints floorPoints, String stationName) {
        if (floorPoints == null || stationName == null || stationName.trim().isEmpty()) {
            return null;
        }

        for (Position position : floorPoints.getAllPoints()) {
            if (position != null && stationName.equals(position.getName())) {
                return position;
            }
        }

        return null;
    }

    private Integer extractMapFloor(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return 0;
        }

        int lastUnderscore = fullMapName.lastIndexOf('_');
        if (lastUnderscore > 0) {
            try {
                String floorPart = fullMapName.substring(lastUnderscore + 1);
                return Integer.parseInt(floorPart);
            } catch (NumberFormatException e) {
                Log.w("ChargingDebug", "无法解析楼层号从地图名: " + fullMapName);
                return 0;
            }
        }

        return 0;
    }

    private boolean checkWorkType(String taskType) {
        HashSet<String> workTypes = new HashSet<>();
        workTypes.add("delivery");
        workTypes.add("jack");
        workTypes.add("cruise");
        return workTypes.contains(taskType);
    }

    // ==================================================================
    // Status Broadcasting
    // ==================================================================

    private void broadcastStatusToAllClients() {
        if (connectedClients.isEmpty()) return;

        try {
            JSONObject status = new JSONObject();
            status.put("type", "robotStatus");
            status.put("data", getCurrentRobotStatus());
            status.put("timestamp", System.currentTimeMillis());

            String statusMessage = status.toString();
            for (WebSocket client : connectedClients.keySet()) {
                if (client.isOpen()) {
                    client.send(statusMessage);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error broadcasting status", e);
        }
    }

    private void sendCurrentStatus(WebSocket conn) {
        try {
            JSONObject status = new JSONObject();
            status.put("type", "robotStatus");
            status.put("data", getCurrentRobotStatus());
            status.put("timestamp", System.currentTimeMillis());
            conn.send(status.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error sending status", e);
        }
    }

    private JSONObject getCurrentRobotStatus() {
        JSONObject json = new JSONObject();
        try {
            AgvStatusResponse status = statusClient != null ? statusClient.getLastStatusResponse() : null;
            if (status != null && status.data != null) {
                json.put("movement", status.data.movement);
                json.put("power", status.data.power);
                json.put("powerQuantity", status.data.powerQuantity);
                json.put("agvStop", status.data.agvStop);
                json.put("emgStop", status.data.emgStop);
                json.put("isSoftPause", status.data.isSoftPause);
                json.put("collisionWarning", status.data.collisionWarning);
                json.put("inNavMap", status.data.inNavMap);
                json.put("currentGlobalId", status.data.currentGlobalId);
                json.put("robotId", status.data.robotId != null ? status.data.robotId : "");

                if (status.data.pos != null) {
                    JSONObject pos = new JSONObject();
                    pos.put("x", status.data.pos.x);
                    pos.put("y", status.data.pos.y);
                    pos.put("theta", status.data.pos.theta);
                    pos.put("mapName", status.data.pos.mapName != null ? status.data.pos.mapName : "");
                    json.put("position", pos);
                }

                if (status.data.chargeStatus != null) {
                    JSONObject charge = new JSONObject();
                    charge.put("chargeState", status.data.chargeStatus.chargeState != null ?
                            status.data.chargeStatus.chargeState : "");
                    charge.put("powerQuantity", status.data.chargeStatus.powerQuantity);
                    charge.put("ele", status.data.chargeStatus.ele);
                    json.put("chargeStatus", charge);
                }
                putTaskState(json);
                json.put("isTaskRunning", resolveReportedIsTaskRunning());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating status JSON", e);
        }
        return json;
    }

    private JSONObject getRobotStatus() {
        JSONObject jsonResponse = new JSONObject();
        try {
            AgvStatusResponse status = statusClient != null ? statusClient.getLastStatusResponse() : null;
            if (status != null && status.data != null) {
                AgvStatusResponse.Data data = status.data;

                // Basic AGV information
                jsonResponse.put("cmd", status.cmd != null ? status.cmd : "");
                jsonResponse.put("talk", status.talk != null ? status.talk : "");
                jsonResponse.put("time", status.time != null ? status.time : "");

                // Movement and state
                jsonResponse.put("agvStop", data.agvStop);
                jsonResponse.put("movement", data.movement);
                jsonResponse.put("goalFinish", data.goalFinish);
                jsonResponse.put("emgStop", data.emgStop);
                jsonResponse.put("wheelLock", data.wheelLock);
                jsonResponse.put("isSoftPause", data.isSoftPause);
                jsonResponse.put("collisionWarning", data.collisionWarning);

                // Task running status
                jsonResponse.put("isTaskRunning", resolveReportedIsTaskRunning());

                // Power and battery
                jsonResponse.put("power", data.power);
                jsonResponse.put("powerQuantity", data.powerQuantity);
                jsonResponse.put("electricCurrentIn", data.electricCurrentIn);
                jsonResponse.put("electricCurrentOut", data.electricCurrentOut);
                jsonResponse.put("currentBv", data.currentBv);
                jsonResponse.put("currentFv", data.currentFv);

                // Navigation and mapping
                jsonResponse.put("inNavMap", data.inNavMap);
                jsonResponse.put("inBuildMap", data.inBuildMap);
                jsonResponse.put("currentGlobalId", data.currentGlobalId);
                jsonResponse.put("poseProbability", data.poseProbability);

                // System information
                jsonResponse.put("robotId", data.robotId != null ? data.robotId : "");
                jsonResponse.put("version", data.version != null ? data.version : "");
                jsonResponse.put("odomHz", data.odomHz);
                jsonResponse.put("scanHz", data.scanHz);

                // Error codes
                if (data.errorCode != null && !data.errorCode.isEmpty()) {
                    JSONArray errorArray = new JSONArray();
                    for (Integer error : data.errorCode) {
                        errorArray.put(error);
                    }
                    jsonResponse.put("errorCode", errorArray);
                } else {
                    jsonResponse.put("errorCode", new JSONArray());
                }
                jsonResponse.put("errCode", data.errCode);

                // Charge information
                jsonResponse.put("chargeStep", data.chargeStep);
                jsonResponse.put("inManualCharge", data.inManualCharge);

                // Current position name (before position field)
                String currentPositionName = "none";
                if (data.pos != null && mapViewModel != null) {
                    try {
                        // Get current map points
                        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
                        String currentBuildingId = mapViewModel.getCurrentBuilding();
                        int currentFloor = mapViewModel.getCurrentFloor();

                        if (mapPoints != null && currentBuildingId != null) {
                            // Get all positions on current floor
                            FloorPoints floorPoints = mapPoints.getFloorPoints(currentBuildingId, currentFloor);
                            List<Position> allPositions = floorPoints != null ? floorPoints.getAllPoints() : null;

                            if (allPositions != null) {
                                // Compare current position with all station positions
                                for (Position pos : allPositions) {
                                    if (pos != null) {
                                        // Allow ±0.5 meter tolerance for position matching
                                        if (Math.abs(data.pos.x - pos.getPosX()) < 0.5 &&
                                            Math.abs(data.pos.y - pos.getPosY()) < 0.5) {
                                            // Found matching station
                                            String mapName = pos.getMapName() != null ? pos.getMapName() : data.pos.mapName;
                                            String stationName = pos.getName() != null ? pos.getName() : "";
                                            currentPositionName = mapName + "_" + stationName;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting current position name", e);
                    }
                }
                jsonResponse.put("currentPositionName", currentPositionName);

                // Position data
                if (data.pos != null) {
                    JSONObject posJson = new JSONObject();
                    posJson.put("mapName", data.pos.mapName != null ? data.pos.mapName : "");
                    posJson.put("x", data.pos.x);
                    posJson.put("y", data.pos.y);
                    posJson.put("z", data.pos.z);
                    posJson.put("theta", data.pos.theta);
                    jsonResponse.put("position", posJson);
                }

                // Charge status
                if (data.chargeStatus != null) {
                    JSONObject chargeJson = new JSONObject();
                    AgvStatusResponse.ChargeStatus charge = data.chargeStatus;

                    chargeJson.put("actionResult", charge.actionResult);
                    chargeJson.put("againTime", charge.againTime);
                    chargeJson.put("chargeIo", charge.chargeIo);
                    chargeJson.put("chargeState", charge.chargeState != null ? charge.chargeState : "");
                    chargeJson.put("ele", charge.ele);
                    chargeJson.put("failedChargeTime", charge.failedChargeTime);
                    chargeJson.put("frontPowerDistance", charge.frontPowerDistance);
                    chargeJson.put("ioTouch", charge.ioTouch);
                    chargeJson.put("isAutoInit", charge.isAutoInit);
                    chargeJson.put("isManualInit", charge.isManualInit);
                    chargeJson.put("manualChargeIo", charge.manualChargeIo);
                    chargeJson.put("manualTriggerIo", charge.manualTriggerIo);
                    chargeJson.put("powerQuantity", charge.powerQuantity);
                    chargeJson.put("state", charge.state);
                    chargeJson.put("touchIo", charge.touchIo);
                    chargeJson.put("touchIoStepTime", charge.touchIoStepTime);
                    chargeJson.put("touchIoTime", charge.touchIoTime);

                    if (charge.pos != null) {
                        JSONObject chargePosJson = new JSONObject();
                        chargePosJson.put("mapName", charge.pos.mapName != null ? charge.pos.mapName : "");
                        chargePosJson.put("x", charge.pos.x);
                        chargePosJson.put("y", charge.pos.y);
                        chargePosJson.put("theta", charge.pos.theta);
                        chargeJson.put("position", chargePosJson);
                    }

                    jsonResponse.put("chargeStatus", chargeJson);
                }

                // Connection health information (custom field)
                if (statusClient != null) {
                    StatusWebSocketClient.ConnectionHealth health = statusClient.getConnectionHealth();
                    JSONObject healthJson = new JSONObject();
                    healthJson.put("isConnected", health.isConnected);
                    healthJson.put("hasReceivedStatus", health.hasReceivedStatus);
                    healthJson.put("timeSinceLastMessage", health.timeSinceLastMessage);
                    healthJson.put("timeSinceLastStatus", health.timeSinceLastStatus);
                    jsonResponse.put("connectionHealth", healthJson);
                }

                String currentElevator = elevatorManager != null ? elevatorManager.getRobotCurrentElevator() : null;
                jsonResponse.put("useElevator", currentElevator != null ? currentElevator : Integer.valueOf(0));
                Log.i(TAG, "Add useElevator id " + currentElevator);

                putTaskState(jsonResponse);

            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating status JSON", e);
        }
        return jsonResponse;
    }

    private void putTaskState(JSONObject json) throws JSONException {
        if (taskViewModel != null) {
            TaskViewModel.TaskState taskState = taskViewModel.getCurrentTaskStateSnapshot();
            json.put("task_status", taskState.status);
            json.put("taskType", taskState.taskType);
            json.put("taskId", taskState.taskId);
            json.put("taskName", taskState.taskName);
        } else {
            json.put("task_status", TaskViewModel.TASK_STATUS_IDLE);
            json.put("taskType", TaskViewModel.TASK_INFO_NONE);
            json.put("taskId", TaskViewModel.TASK_INFO_NONE);
            json.put("taskName", TaskViewModel.TASK_INFO_NONE);
        }

        int jackState = commandClient != null ? commandClient.getCachedJackState() : 0;
        json.put("jackState", jackState);
    }

    private boolean resolveReportedIsTaskRunning() {
        boolean realIsTaskRunning = false;
        if (sharedViewModel != null && sharedViewModel.getNavigationHandler() != null) {
            realIsTaskRunning = sharedViewModel.getNavigationHandler().isTaskRunning();
        }
        return TaskRunningStatusHolder.getInstance().resolveIsTaskRunning(realIsTaskRunning);
    }


    private JSONObject getCurrentMapInfo() {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                jsonResponse.put("error", "Command WebSocket not connected");
                return jsonResponse;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final String[] mapNameHolder = new String[1];
            final Double[] resolutionHolder = new Double[1];
            final Integer[] widthHolder = new Integer[1];
            final Integer[] heightHolder = new Integer[1];
            final double[][] mapPositionHolder = new double[1][3];
            final String[] formatHolder = new String[1];
            final String[] timeHolder = new String[1];
            final String[] mapImageHolder = new String[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                commandClient.getCurrentMapInfoAsync(new CommandWebSocketClient.CurrentMapInfoCallback() {
                    @Override
                    public void onSuccess(String mapName, double resolution, int width, int height,
                                          double[] mapPosition, String format, String time, String mapImage) {
                        mapNameHolder[0] = mapName;
                        resolutionHolder[0] = resolution;
                        widthHolder[0] = width;
                        heightHolder[0] = height;
                        mapPositionHolder[0] = mapPosition != null ? mapPosition : new double[3];
                        formatHolder[0] = format;
                        timeHolder[0] = time;
                        mapImageHolder[0] = mapImage;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        errorHolder[0] = error;
                        latch.countDown();
                    }
                });
            });

            boolean completed = latch.await(15, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout waiting for current map info");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            // Build response directly without extra "data" wrapper
            if (mapNameHolder[0] != null) {
                jsonResponse.put("name", mapNameHolder[0]);
            }
            if (resolutionHolder[0] != null) {
                jsonResponse.put("resolution", resolutionHolder[0]);
            }
            if (widthHolder[0] != null) {
                jsonResponse.put("width", widthHolder[0]);
            }
            if (heightHolder[0] != null) {
                jsonResponse.put("height", heightHolder[0]);
            }

            if (mapPositionHolder[0] != null) {
                JSONArray posArray = new JSONArray();
                for (double pos : mapPositionHolder[0]) {
                    posArray.put(pos);
                }
                jsonResponse.put("mappos", posArray);
            }

            if (formatHolder[0] != null) {
                jsonResponse.put("format", formatHolder[0]);
            }
            if (timeHolder[0] != null) {
                jsonResponse.put("time", timeHolder[0]);
            }
            if (mapImageHolder[0] != null) {
                jsonResponse.put("map", mapImageHolder[0]);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error fetching current map info", e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    private JSONObject getAllMapPoints() {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                jsonResponse.put("error", "Command WebSocket not connected");
                return jsonResponse;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final MarkerData[] resultHolder = new MarkerData[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                commandClient.getAllMarkersAsync(new CommandWebSocketClient.MarkerDataCallback() {
                    @Override
                    public void onSuccess(MarkerData markerData) {
                        resultHolder[0] = markerData;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        errorHolder[0] = error;
                        latch.countDown();
                    }
                });
            });

            boolean completed = latch.await(15, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout waiting for points data");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            // Convert marker data directly to JSON without extra wrapper
            if (resultHolder[0] != null && resultHolder[0].getData() != null) {
                JSONArray pointsArray = new JSONArray();
                for (MarkerPoint point : resultHolder[0].getData()) {
                    JSONObject pointJson = new JSONObject();
                    pointJson.put("name", point.getName());
                    pointJson.put("x", point.getX());
                    pointJson.put("y", point.getY());
                    pointJson.put("yaw", point.getYaw());
                    pointJson.put("type", point.getType());
                    pointJson.put("mapName", point.getMapName());
                    pointJson.put("floor", point.getFloor());
                    pointsArray.put(pointJson);
                }
                jsonResponse.put("points", pointsArray);
                jsonResponse.put("count", pointsArray.length());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error fetching all map points", e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    private JSONObject getSafetyAreas() {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                jsonResponse.put("error", "Command WebSocket not connected");
                return jsonResponse;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final SafetyAreasData[] resultHolder = new SafetyAreasData[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                commandClient.getSafetyAreasAsync(new CommandWebSocketClient.SafetyAreasCallback() {
                    @Override
                    public void onSuccess(SafetyAreasData safetyAreasData) {
                        resultHolder[0] = safetyAreasData;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        errorHolder[0] = error;
                        latch.countDown();
                    }
                });
            });

            boolean completed = latch.await(15, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout waiting for safety areas data");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            // Convert safety areas directly to JSON without extra wrapper
            JSONArray allTypesArray = new JSONArray();
            for (int typeId = 0; typeId <= 5; typeId++) {
                JSONObject typeEntry = new JSONObject();
                typeEntry.put("id", typeId);

                SafetyArea area = findSafetyAreaById(resultHolder[0], typeId);

                if (area != null && area.getPolygons() != null && !area.getPolygons().isEmpty()) {
                    JSONArray dataArray = new JSONArray();

                    JSONObject speedInfo = new JSONObject();
                    speedInfo.put("speed", area.getSpeed());
                    dataArray.put(speedInfo);

                    for (List<Position> polygon : area.getPolygons()) {
                        JSONArray polygonArray = new JSONArray();
                        for (Position vertex : polygon) {
                            JSONObject vertexObject = new JSONObject();
                            vertexObject.put("x", vertex.getPosX());
                            vertexObject.put("y", vertex.getPosY());
                            polygonArray.put(vertexObject);
                        }
                        if (polygonArray.length() > 0) {
                            dataArray.put(polygonArray);
                        }
                    }
                    typeEntry.put("data", dataArray);
                } else {
                    JSONArray emptyArray = new JSONArray();
                    JSONObject speedInfo = new JSONObject();
                    speedInfo.put("speed", area != null ? area.getSpeed() : 0.0);
                    emptyArray.put(speedInfo);
                    typeEntry.put("data", emptyArray);
                }
                allTypesArray.put(typeEntry);
            }
            jsonResponse.put("areas", allTypesArray);

        } catch (Exception e) {
            Log.e(TAG, "Error fetching safety areas", e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    private JSONObject getNavigatePath() {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                jsonResponse.put("error", "Command WebSocket not connected");
                return jsonResponse;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final NavigationPathData[] resultHolder = new NavigationPathData[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                commandClient.getNavigatePathAsync(new CommandWebSocketClient.NavigationPathCallback() {
                    @Override
                    public void onSuccess(NavigationPathData pathData) {
                        resultHolder[0] = pathData;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        errorHolder[0] = error;
                        latch.countDown();
                    }
                });
            });

            boolean completed = latch.await(15, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout waiting for path data");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            // Convert path data directly to JSON without extra wrapper
            if (resultHolder[0] != null) {
                jsonResponse.put("talk", resultHolder[0].getTalk() != null ? resultHolder[0].getTalk() : "");
                jsonResponse.put("time", resultHolder[0].getTime() != null ? resultHolder[0].getTime() : "");

                if (resultHolder[0].getLine() != null) {
                    JSONArray lineArray = new JSONArray();
                    for (Position position : resultHolder[0].getLine()) {
                        JSONObject pointJson = new JSONObject();
                        pointJson.put("x", position.getPosX());
                        pointJson.put("y", position.getPosY());
                        pointJson.put("id", position.getId());
                        pointJson.put("name", position.getName() != null ? position.getName() : "");
                        lineArray.put(pointJson);
                    }
                    jsonResponse.put("line", lineArray);
                    jsonResponse.put("pathLength", lineArray.length());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error fetching navigate path", e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    private JSONObject getRcsTaskStatus(String taskId) {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (taskViewModel == null) {
                jsonResponse.put("error", "Task ViewModel not available");
                return jsonResponse;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final JSONObject[] resultData = new JSONObject[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                try {
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();

                    TaskViewModel.TaskState currentTaskState = taskViewModel.getCurrentTaskStateSnapshot();
                    if (currentTaskState != null
                            && taskId.equals(currentTaskState.taskId)
                            && !TaskViewModel.TASK_INFO_NONE.equals(currentTaskState.status)) {
                        JSONObject taskInfo = new JSONObject();
                        taskInfo.put("status", currentTaskState.status);
                        taskInfo.put("type", currentTaskState.taskType);
                        taskInfo.put("taskName", currentTaskState.taskName);
                        resultData[0] = taskInfo;
                    }

                    if (taskRecords != null && !taskRecords.isEmpty()) {
                        boolean found = false;
                        for (TaskRecord taskRecord : taskRecords) {
                            String recordTaskId = taskRecord.getTaskId();
                            String taskName = taskRecord.getTaskName();
                            if ((recordTaskId != null && recordTaskId.trim().equals(taskId))
                                    || (taskName != null && taskName.equals("RCS-" + taskId))) {
                                JSONObject taskInfo = new JSONObject();
                                taskInfo.put("status", taskRecord.getStatus());
                                taskInfo.put("type", taskRecord.getType());
                                taskInfo.put("taskName", taskName);
                                taskInfo.put("startTime", taskRecord.getStartTime());
                                taskInfo.put("endTime", taskRecord.getEndTime());
                                taskInfo.put("duration", taskRecord.getDuration());

                                if (taskRecord.getStartTime() > 0) {
                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                    taskInfo.put("startTimeFormatted", sdf.format(new Date(taskRecord.getStartTime())));
                                }
                                if (taskRecord.getEndTime() > 0) {
                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                    taskInfo.put("endTimeFormatted", sdf.format(new Date(taskRecord.getEndTime())));
                                }

                                resultData[0] = taskInfo;
                                found = true;
                                break;
                            }
                        }

                        if (!found && resultData[0] == null) {
                            errorHolder[0] = "Task with ID '" + taskId + "' not found";
                        }
                    } else if (resultData[0] == null) {
                        errorHolder[0] = "No task records found";
                    }

                    latch.countDown();

                } catch (Exception e) {
                    errorHolder[0] = "Error fetching RCS tasks: " + e.getMessage();
                    latch.countDown();
                }
            });

            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout fetching RCS tasks");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            // Put task data directly into response
            if (resultData[0] != null) {
                jsonResponse.put("taskId", taskId);
                jsonResponse.put("status", resultData[0].optString("status"));
                jsonResponse.put("type", resultData[0].optString("type"));
                jsonResponse.put("taskName", resultData[0].optString("taskName"));
                jsonResponse.put("startTime", resultData[0].optLong("startTime"));
                jsonResponse.put("endTime", resultData[0].optLong("endTime"));
                jsonResponse.put("duration", resultData[0].optLong("duration"));
                if (resultData[0].has("startTimeFormatted")) {
                    jsonResponse.put("startTimeFormatted", resultData[0].getString("startTimeFormatted"));
                }
                if (resultData[0].has("endTimeFormatted")) {
                    jsonResponse.put("endTimeFormatted", resultData[0].getString("endTimeFormatted"));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error fetching RCS task status for taskId: " + taskId, e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    private JSONObject getHmiTaskStatus(String dateTime) {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (taskViewModel == null) {
                jsonResponse.put("error", "Task ViewModel not available");
                return jsonResponse;
            }

            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            final Date targetDate;

            try {
                targetDate = dateFormat.parse(dateTime);
            } catch (ParseException e) {
                jsonResponse.put("error", "Invalid date format. Expected: yyyy-MM-dd");
                return jsonResponse;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final JSONArray[] resultData = new JSONArray[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                try {
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();
                    JSONArray tasksArray = new JSONArray();
                    int count = 0;

                    if (taskRecords != null && !taskRecords.isEmpty()) {
                        Calendar targetCalendar = Calendar.getInstance();
                        targetCalendar.setTime(targetDate);
                        Calendar taskCalendar = Calendar.getInstance();

                        for (TaskRecord taskRecord : taskRecords) {
                            if (taskRecord.getStartTime() == 0) {
                                continue;
                            }

                            String taskName = taskRecord.getTaskName();
                            if (taskName == null || !taskName.toUpperCase().contains("HMI")) {
                                continue;
                            }

                            taskCalendar.setTimeInMillis(taskRecord.getStartTime());

                            if (taskCalendar.get(Calendar.YEAR) == targetCalendar.get(Calendar.YEAR) &&
                                    taskCalendar.get(Calendar.MONTH) == targetCalendar.get(Calendar.MONTH) &&
                                    taskCalendar.get(Calendar.DAY_OF_MONTH) == targetCalendar.get(Calendar.DAY_OF_MONTH)) {

                                JSONObject taskInfo = new JSONObject();
                                taskInfo.put("taskName", taskName);
                                taskInfo.put("status", taskRecord.getStatus());
                                taskInfo.put("type", taskRecord.getType());
                                taskInfo.put("startTime", taskRecord.getStartTime());
                                taskInfo.put("endTime", taskRecord.getEndTime());
                                taskInfo.put("duration", taskRecord.getDuration());

                                if (taskRecord.getStartTime() > 0) {
                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                    taskInfo.put("startTimeFormatted", sdf.format(new Date(taskRecord.getStartTime())));
                                }
                                if (taskRecord.getEndTime() > 0) {
                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                    taskInfo.put("endTimeFormatted", sdf.format(new Date(taskRecord.getEndTime())));
                                }

                                tasksArray.put(taskInfo);
                                count++;
                            }
                        }
                    }

                    if (count == 0) {
                        errorHolder[0] = "No HMI tasks found for date: " + dateTime;
                    }

                    resultData[0] = tasksArray;
                    latch.countDown();

                } catch (Exception e) {
                    errorHolder[0] = "Error fetching HMI tasks: " + e.getMessage();
                    latch.countDown();
                }
            });

            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout fetching HMI tasks");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            // Put tasks array directly into response
            jsonResponse.put("date", dateTime);
            jsonResponse.put("count", resultData[0] != null ? resultData[0].length() : 0);
            jsonResponse.put("tasks", resultData[0]);

        } catch (Exception e) {
            Log.e(TAG, "Error fetching HMI task status for date: " + dateTime, e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    /**
     * 获取 HMI 任务状态详情
     * 过滤逻辑:
     * 1. 如果 taskId 非空且非"0", 则按任务ID过滤
     * 2. 如果 dateTime 非空且非"0", 则按日期过滤
     * 3. 如果 startTime/endTime 非空, 则按起止时间范围过滤
     * 4. 如果都未提供或为"0", 则返回最近的任务详情
     * 5. 多个任务匹配时, 返回创建时间最新的任务
     */
    private JSONObject getHmiTaskStatusDetails(String taskId, String dateTime, String startTime, String endTime) {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (taskViewModel == null) {
                jsonResponse.put("error", "Task ViewModel not available");
                return jsonResponse;
            }

            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            // 解析起止时间
            final Date startDate = (startTime != null && !startTime.isEmpty() && !"0".equals(startTime))
                    ? parseDateTime(startTime) : null;
            final Date endDate = (endTime != null && !endTime.isEmpty() && !"0".equals(endTime))
                    ? parseDateTime(endTime) : null;

            final CountDownLatch latch = new CountDownLatch(1);
            final JSONObject[] resultData = new JSONObject[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                try {
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();

                    if (taskRecords == null || taskRecords.isEmpty()) {
                        errorHolder[0] = "No task records found";
                        latch.countDown();
                        return;
                    }

                    List<TaskRecord> matchingTasks = new ArrayList<>();

                    boolean hasTaskIdFilter = taskId != null && !taskId.isEmpty() && !"0".equals(taskId);
                    boolean hasDateFilter = dateTime != null && !dateTime.isEmpty() && !"0".equals(dateTime);

                    Date targetDate = null;
                    if (hasDateFilter) {
                        try {
                            targetDate = dateFormat.parse(dateTime);
                        } catch (java.text.ParseException e) {
                            errorHolder[0] = "Invalid dateTime format. Expected: yyyy-MM-dd";
                            latch.countDown();
                            return;
                        }
                    }

                    for (TaskRecord taskRecord : taskRecords) {
                        // 按 taskId 过滤
                        if (hasTaskIdFilter) {
                            String recordTaskId = taskRecord.getTaskId();
                            if (recordTaskId == null || !recordTaskId.equals(taskId)) {
                                continue;
                            }
                        }

                        // 按日期过滤
                        if (hasDateFilter && targetDate != null) {
                            long taskTime = taskRecord.getCreateTime();
                            if (taskTime == 0) {
                                continue;
                            }
                            Calendar taskCal = Calendar.getInstance();
                            taskCal.setTimeInMillis(taskTime);
                            Calendar targetCal = Calendar.getInstance();
                            targetCal.setTime(targetDate);
                            if (taskCal.get(Calendar.YEAR) != targetCal.get(Calendar.YEAR)
                                    || taskCal.get(Calendar.MONTH) != targetCal.get(Calendar.MONTH)
                                    || taskCal.get(Calendar.DAY_OF_MONTH) != targetCal.get(Calendar.DAY_OF_MONTH)) {
                                continue;
                            }
                        }

                        // 按起止时间范围过滤
                        if (startDate != null) {
                            if (taskRecord.getCreateTime() < startDate.getTime()) {
                                continue;
                            }
                        }
                        if (endDate != null) {
                            if (taskRecord.getCreateTime() > endDate.getTime()) {
                                continue;
                            }
                        }

                        matchingTasks.add(taskRecord);
                    }

                    if (matchingTasks.isEmpty()) {
                        errorHolder[0] = "No matching tasks found";
                        latch.countDown();
                        return;
                    }

                    // 多个任务匹配时, 返回创建时间最新的任务
                    TaskRecord selectedTask = matchingTasks.get(0);
                    for (int i = 1; i < matchingTasks.size(); i++) {
                        if (matchingTasks.get(i).getCreateTime() > selectedTask.getCreateTime()) {
                            selectedTask = matchingTasks.get(i);
                        }
                    }

                    // 构建返回数据
                    JSONObject data = new JSONObject();
                    data.put("requestId", "");
                    data.put("type", "hmiTaskStatusDetails");
                    data.put("date", selectedTask.getCreateTime() > 0
                            ? dateFormat.format(new Date(selectedTask.getCreateTime())) : "");

                    // 根据任务类型获取站点详情和循环次数
                    int taskType = selectedTask.getType();
                    int loopCount = 1;
                    int currentLoopIndex = selectedTask.getCurrentLoopIndex();
                    JSONArray stationDetailsArray = new JSONArray();

                    // For running tasks, get current loop from navigation handler
                    boolean isRunning = TaskRecord.STATUS_RUNNING.equals(selectedTask.getStatus());
                    if (isRunning && sharedViewModel != null && sharedViewModel.getNavigationHandler() != null) {
                        GeneralNavigationHandler navHandler = sharedViewModel.getNavigationHandler();
                        if (taskType == TaskRecord.TYPE_JACK) {
                            currentLoopIndex = navHandler.getCurrentJackLoopIndex();
                        } else if (taskType == TaskRecord.TYPE_CRUISE) {
                            currentLoopIndex = navHandler.getCurrentCruiseLoopIndex();
                        }
                    }

                    if (taskType == TaskRecord.TYPE_JACK) {
                        // Jack task - get details from JackTask
                        JackTask jackTask = findJackTaskByName(selectedTask.getTaskName());
                        if (jackTask != null) {
                            loopCount = jackTask.getLoopCount();
                            List<JackOperation> operations = jackTask.getOperations();

                            // Filter out elevator points
                            List<JackOperation> filteredOps = new ArrayList<>();
                            if (operations != null) {
                                for (JackOperation op : operations) {
                                    if (!isElevatorPointByName(op.getPointName())) {
                                        filteredOps.add(op);
                                    }
                                }
                            }

                            data.put("stationCount", filteredOps.size());

                            if (!filteredOps.isEmpty()) {
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                int stationIndex = 1;
                                for (JackOperation op : filteredOps) {
                                    String pointName = op.getPointName();
                                    String action = getJackActionDescription(op.getActionType());
                                    int duration = op.getDuration();
                                    stationDetail.put(String.valueOf(stationIndex),
                                            pointName + "," + duration + "s," + action);
                                    stationIndex++;
                                }
                                // Target station: use current task target if running
                                if (isRunning && sharedViewModel != null && sharedViewModel.getNavigationHandler() != null) {
                                    Position currentTarget = sharedViewModel.getNavigationHandler().getCurrentTaskTarget();
                                    if (currentTarget != null && !isElevatorPoint(currentTarget)) {
                                        stationDetail.put("targetStation", currentTarget.getName());
                                    } else {
                                        // Find next non-elevator target
                                        stationDetail.put("targetStation", filteredOps.get(filteredOps.size() - 1).getPointName());
                                    }
                                } else {
                                    // For completed tasks, use last operation point
                                    stationDetail.put("targetStation", filteredOps.get(filteredOps.size() - 1).getPointName());
                                }
                                stationDetailsArray.put(stationDetail);
                            }
                        } else {
                            data.put("stationCount", 0);
                        }
                    } else if (taskType == TaskRecord.TYPE_CRUISE) {
                        // Cruise task - get details from CruiseTask
                        CruiseTask cruiseTask = findCruiseTaskByName(selectedTask.getTaskName());
                        if (cruiseTask != null) {
                            loopCount = cruiseTask.getLoopCount();
                            List<String> stationIds = cruiseTask.getStationIds();
                            int stayTime = cruiseTask.getStayTime();

                            // Filter out elevator points
                            List<String> filteredStations = new ArrayList<>();
                            if (stationIds != null) {
                                for (String stationId : stationIds) {
                                    if (!isElevatorPointByName(stationId)) {
                                        filteredStations.add(stationId);
                                    }
                                }
                            }

                            data.put("stationCount", filteredStations.size());

                            if (!filteredStations.isEmpty()) {
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                int stationIndex = 1;
                                for (String stationId : filteredStations) {
                                    stationDetail.put(String.valueOf(stationIndex),
                                            stationId + "," + stayTime + "s,move");
                                    stationIndex++;
                                }
                                // Target station: use current task target if running
                                if (isRunning && sharedViewModel != null && sharedViewModel.getNavigationHandler() != null) {
                                    Position currentTarget = sharedViewModel.getNavigationHandler().getCurrentTaskTarget();
                                    if (currentTarget != null && !isElevatorPoint(currentTarget)) {
                                        stationDetail.put("targetStation", currentTarget.getName());
                                    } else {
                                        // Find next non-elevator target
                                        stationDetail.put("targetStation", filteredStations.get(filteredStations.size() - 1));
                                    }
                                } else {
                                    // For completed tasks, use last station
                                    stationDetail.put("targetStation", filteredStations.get(filteredStations.size() - 1));
                                }
                                stationDetailsArray.put(stationDetail);
                            }
                        } else {
                            data.put("stationCount", 0);
                        }
                    } else {
                        // Delivery task or others - get station data
                        List<TaskRecord.StationDetail> recordedDetails = selectedTask.getStationDetails();

                        // For running delivery tasks, get route from navigation handler
                        if (isRunning && sharedViewModel != null && sharedViewModel.getNavigationHandler() != null
                                && (recordedDetails == null || recordedDetails.isEmpty())) {
                            GeneralNavigationHandler navHandler = sharedViewModel.getNavigationHandler();
                            List<Position> routePositions = navHandler.getCurrentTaskRoute();
                            Position currentTarget = navHandler.getCurrentTaskTarget();
                            if (routePositions != null && !routePositions.isEmpty()) {
                                // Filter out elevator points (type: 2-preride, 3-wait, 4-ride, 14-transition)
                                List<Position> filteredPositions = new ArrayList<>();
                                for (Position pos : routePositions) {
                                    if (!isElevatorPoint(pos)) {
                                        filteredPositions.add(pos);
                                    }
                                }

                                data.put("stationCount", filteredPositions.size());
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                int stationIndex = 1;
                                for (Position pos : filteredPositions) {
                                    String pointName = pos.getName();
                                    int stayDuration = pos.getStayDuration() / 1000;
                                    stationDetail.put(String.valueOf(stationIndex),
                                            pointName + "," + stayDuration + "s,move");
                                    stationIndex++;
                                }
                                // Use current task target (the point robot is going to)
                                if (currentTarget != null && !isElevatorPoint(currentTarget)) {
                                    stationDetail.put("targetStation", currentTarget.getName());
                                } else {
                                    // If current target is elevator point, find next non-elevator point
                                    for (int i = 0; i < routePositions.size(); i++) {
                                        if (routePositions.get(i).equals(currentTarget) && i + 1 < routePositions.size()) {
                                            Position nextPos = routePositions.get(i + 1);
                                            if (!isElevatorPoint(nextPos)) {
                                                stationDetail.put("targetStation", nextPos.getName());
                                                break;
                                            }
                                        }
                                    }
                                }
                                stationDetailsArray.put(stationDetail);
                            } else {
                                data.put("stationCount", 0);
                            }
                        } else {
                            // Use recorded station details - filter out elevator points by name
                            if (recordedDetails != null && !recordedDetails.isEmpty()) {
                                List<TaskRecord.StationDetail> filteredDetails = new ArrayList<>();
                                for (TaskRecord.StationDetail detail : recordedDetails) {
                                    if (!isElevatorPointByName(detail.getStationName())) {
                                        filteredDetails.add(detail);
                                    }
                                }

                                data.put("stationCount", filteredDetails.size());
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                int stationIndex = 1;
                                for (TaskRecord.StationDetail detail : filteredDetails) {
                                    String stationName = detail.getStationName();
                                    long stayDuration = 0;
                                    if (detail.getArrivalTime() > 0 && detail.getDepartureTime() > 0) {
                                        stayDuration = (detail.getDepartureTime() - detail.getArrivalTime()) / 1000;
                                    }
                                    String action = "move";
                                    stationDetail.put(String.valueOf(stationIndex),
                                            stationName + "," + stayDuration + "s," + action);
                                    stationIndex++;
                                }
                                // Target station
                                if (filteredDetails.size() > 0) {
                                    stationDetail.put("targetStation", filteredDetails.get(filteredDetails.size() - 1).getStationName());
                                }
                                stationDetailsArray.put(stationDetail);
                            } else {
                                data.put("stationCount", 0);
                            }
                        }
                    }

                    data.put("loopCount", loopCount);
                    data.put("taskStatus", selectedTask.getStatus());
                    data.put("taskType", getTaskTypeDescription(taskType));

                    // Task name: delivery for delivery tasks, actual name for cruise/jack
                    String taskName;
                    if (taskType == TaskRecord.TYPE_DELIVERY) {
                        taskName = "delivery";
                    } else {
                        taskName = selectedTask.getTaskName();
                    }
                    data.put("taskName", taskName != null ? taskName : "");

                    data.put("createTime", selectedTask.getCreateTime() > 0
                            ? dateTimeFormat.format(new Date(selectedTask.getCreateTime())) : "");
                    data.put("taskId", selectedTask.getTaskId() != null && !selectedTask.getTaskId().isEmpty()
                            ? selectedTask.getTaskId() : String.valueOf(selectedTask.getId()));
                    data.put("stationDetails", stationDetailsArray);

                    resultData[0] = data;
                    latch.countDown();

                } catch (Exception e) {
                    errorHolder[0] = "Error fetching HMI task status details: " + e.getMessage();
                    latch.countDown();
                }
            });

            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (!completed) {
                jsonResponse.put("error", "Timeout fetching HMI task status details");
                return jsonResponse;
            }

            if (errorHolder[0] != null) {
                jsonResponse.put("error", errorHolder[0]);
                return jsonResponse;
            }

            return resultData[0] != null ? resultData[0] : jsonResponse;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching HMI task status details", e);
            try {
                jsonResponse.put("error", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    /**
     * Get elevator configuration list from ElevatorViewModel
     */
    private JSONObject getElevatorList() {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (elevatorViewModel == null) {
                jsonResponse.put("status", "error");
                jsonResponse.put("message", "Elevator ViewModel not available");
                return jsonResponse;
            }

            List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
            if (configs == null || configs.isEmpty()) {
                jsonResponse.put("status", "success");
                jsonResponse.put("count", 0);
                jsonResponse.put("data", new JSONArray());
                jsonResponse.put("timestamp", System.currentTimeMillis());
                return jsonResponse;
            }

            JSONArray dataArray = new JSONArray();

            for (ElevatorViewModel.ElevatorConfig config : configs) {
                JSONObject configJson = new JSONObject();
                configJson.put("elevatorId", config.getId() != null ? config.getId() : "");
                configJson.put("buildingId", config.getBuildingId() != null ? config.getBuildingId() : "default");
                configJson.put("robotId", -1); // Default value as per format
                configJson.put("channel", config.getChannel());
                configJson.put("address", config.getAddress());

                // Build floors array
                JSONArray floorsArray = new JSONArray();
                List<ElevatorViewModel.ElevatorFloor> floors = config.getFloors();
                if (floors != null) {
                    for (ElevatorViewModel.ElevatorFloor floor : floors) {
                        JSONObject floorJson = new JSONObject();
                        floorJson.put("floor", floor.getFloor());
                        floorJson.put("alias", floor.getAlias() != null ? floor.getAlias() : String.valueOf(floor.getFloor()));
                        floorJson.put("buildingId", floor.getBuildingId() != null ? floor.getBuildingId() : config.getBuildingId());

                        // Generate mapName based on buildingId and floor
                        String mapName = generateMapName(floor.getBuildingId(), floor.getFloor());
                        floorJson.put("mapName", mapName != null ? mapName : "");

                        floorsArray.put(floorJson);
                    }
                }
                configJson.put("floors", floorsArray);
                dataArray.put(configJson);
            }

            jsonResponse.put("status", "success");
            jsonResponse.put("count", dataArray.length());
            jsonResponse.put("data", dataArray);
            jsonResponse.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            Log.e(TAG, "Error getting elevator list", e);
            try {
                jsonResponse.put("status", "error");
                jsonResponse.put("message", e.getMessage());
            } catch (JSONException je) {
                Log.e(TAG, "Error creating error response", je);
            }
        }
        return jsonResponse;
    }

    /**
     * Generate map name from building ID and floor number
     * Format: e{buildingId}_{floor}
     */
    private String generateMapName(String buildingId, int floor) {
        if (buildingId == null || buildingId.isEmpty()) {
            return "e0_" + floor;
        }
        return "e" + buildingId + "_" + floor;
    }

    /**
     * 解析日期时间字符串, 支持 "yyyy-MM-dd" 和 "yyyy-MM-dd HH:mm:ss" 两种格式
     */
    private Date parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        try {
            if (dateTimeStr.length() <= 10) {
                return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateTimeStr);
            } else {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(dateTimeStr);
            }
        } catch (java.text.ParseException e) {
            Log.w(TAG, "Failed to parse dateTime: " + dateTimeStr, e);
            return null;
        }
    }

    /**
     * Convert JackTask actionType to action description (English)
     * Mapping based on UI definition:
     * 0=识别举升(jack), 1=识别放下(place), 2=驶离库位(exit_shelf), 3=移动(move),
     * 4=顶升举起(jack_lift), 5=顶升放下(jack_lower), 6=前进(forward), 7=后退(backward),
     * 8=识别进入(recognize_entry)
     */
    private String getJackActionDescription(int actionType) {
        switch (actionType) {
            case 0:
                return "jack";           // 识别举升 (RECOGNIZE_LOAD, type 5)
            case 1:
                return "place";          // 识别放下 (RECOGNIZE_UNLOAD, type 6)
            case 2:
                return "exit_shelf";     // 驶离库位 (OP_EXIT_SHELF, type 7)
            case 3:
                return "move";           // 移动 (DELIVERY, type 1)
            case 4:
                return "jack_lift";      // 顶升举起 (NON_RECOGNIZE_LOAD, type 8)
            case 5:
                return "jack_lower";     // 顶升放下 (NON_RECOGNIZE_UNLOAD, type 9)
            case 6:
                return "forward";        // 前进 (OP_FORWARD, type 17)
            case 7:
                return "backward";       // 后退 (OP_BACKWARD, type 18)
            case 8:
                return "recognize_entry"; // 识别进入不顶升 (OP_RECOGNIZE_ENTRY_ONLY, type 19)
            default:
                return "unknown";
        }
    }

    /**
     * Convert task type to description (English)
     */
    private String getTaskTypeDescription(int taskType) {
        switch (taskType) {
            case TaskRecord.TYPE_DELIVERY:
                return "delivery";
            case TaskRecord.TYPE_CRUISE:
                return "cruise";
            case TaskRecord.TYPE_JACK:
                return "jack";
            case TaskRecord.TYPE_CHARGE:
                return "charge";
            case TaskRecord.TYPE_PARKING:
                return "parking";
            case TaskRecord.TYPE_STOP_CHARGE:
                return "stop_charge";
            case TaskRecord.TYPE_CALL:
                return "call";
            default:
                return "unknown";
        }
    }

    // Helper methods to convert data to JSON
    private JSONObject convertMarkerDataToJson(MarkerData markerData) throws JSONException {
        JSONObject dataJson = new JSONObject();
        if (markerData != null && markerData.getData() != null) {
            JSONArray pointsArray = new JSONArray();
            for (MarkerPoint point : markerData.getData()) {
                JSONObject pointJson = new JSONObject();
                pointJson.put("name", point.getName());
                pointJson.put("x", point.getX());
                pointJson.put("y", point.getY());
                pointJson.put("yaw", point.getYaw());
                pointJson.put("type", point.getType());
                pointJson.put("mapName", point.getMapName());
                pointJson.put("floor", point.getFloor());
                pointsArray.put(pointJson);
            }
            dataJson.put("points", pointsArray);
            dataJson.put("count", pointsArray.length());
        }
        return dataJson;
    }

    private JSONObject convertPathDataToJson(NavigationPathData pathData) throws JSONException {
        JSONObject dataJson = new JSONObject();
        if (pathData != null) {
            dataJson.put("talk", pathData.getTalk() != null ? pathData.getTalk() : "");
            dataJson.put("time", pathData.getTime() != null ? pathData.getTime() : "");

            if (pathData.getLine() != null) {
                JSONArray lineArray = new JSONArray();
                for (Position position : pathData.getLine()) {
                    JSONObject pointJson = new JSONObject();
                    pointJson.put("x", position.getPosX());
                    pointJson.put("y", position.getPosY());
                    pointJson.put("id", position.getId());
                    pointJson.put("name", position.getName() != null ? position.getName() : "");
                    lineArray.put(pointJson);
                }
                dataJson.put("line", lineArray);
                dataJson.put("pathLength", lineArray.length());
            }
        }
        return dataJson;
    }

    private JSONArray convertSafetyAreasDataToJson(SafetyAreasData safetyAreasData) throws JSONException {
        JSONArray allTypesArray = new JSONArray();

        // Create entries for all 6 types (0-5)
        for (int typeId = 0; typeId <= 5; typeId++) {
            JSONObject typeEntry = new JSONObject();
            typeEntry.put("id", typeId);

            // Find SafetyArea for this type
            SafetyArea area = findSafetyAreaById(safetyAreasData, typeId);

            if (area != null && area.getPolygons() != null && !area.getPolygons().isEmpty()) {
                JSONArray dataArray = new JSONArray();

                // First element: speed information
                JSONObject speedInfo = new JSONObject();
                speedInfo.put("speed", area.getSpeed());
                dataArray.put(speedInfo);

                // Remaining elements: polygons
                for (List<Position> polygon : area.getPolygons()) {
                    JSONArray polygonArray = new JSONArray();

                    for (Position vertex : polygon) {
                        JSONObject vertexObject = new JSONObject();
                        vertexObject.put("x", vertex.getPosX());
                        vertexObject.put("y", vertex.getPosY());
                        polygonArray.put(vertexObject);
                    }

                    if (polygonArray.length() > 0) {
                        dataArray.put(polygonArray);
                    }
                }

                typeEntry.put("data", dataArray);
            } else {
                // Empty type
                JSONArray emptyArray = new JSONArray();
                JSONObject speedInfo = new JSONObject();
                speedInfo.put("speed", area != null ? area.getSpeed() : 0.0);
                emptyArray.put(speedInfo);
                typeEntry.put("data", emptyArray);
            }

            allTypesArray.put(typeEntry);
        }

        return allTypesArray;
    }

    private SafetyArea findSafetyAreaById(SafetyAreasData safetyAreasData, int id) {
        if (safetyAreasData == null || safetyAreasData.getSafetyAreas() == null) {
            return null;
        }

        for (SafetyArea area : safetyAreasData.getSafetyAreas()) {
            if (area.getId() == id) {
                return area;
            }
        }

        return null;
    }
    // ==================================================================
    // WebSocket Message Helpers
    // ==================================================================

    private void sendWelcomeMessage(WebSocket conn) {
        try {
            JSONObject welcome = new JSONObject();
            welcome.put("type", "welcome");
            welcome.put("message", "Connected to RCS WebSocket Server");
            welcome.put("version", "1.0");
            welcome.put("timestamp", System.currentTimeMillis());
            welcome.put("serverInfo", getServerInfo());
            conn.send(welcome.toString());
            Log.d(TAG, "Welcome message sent to client");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating welcome message", e);
        }
    }

    private void sendErrorResponse(WebSocket conn, String errorMessage) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", "error");
            error.put("message", errorMessage);
            error.put("timestamp", System.currentTimeMillis());
            conn.send(error.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating error response", e);
        }
    }

    // ==================================================================
    // Network & Server Info
    // ==================================================================

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return "127.0.0.1";
    }

    private JSONObject getServerInfo() throws JSONException {
        JSONObject info = new JSONObject();
        info.put("port", actualPort > 0 ? actualPort : serverPort);
        info.put("localIp", getLocalIpAddress());
        return info;
    }

    private void logServerAccessInfo() {
        String localIp = getLocalIpAddress();
        int portToShow = actualPort > 0 ? actualPort : serverPort;

        Log.i(TAG, "========================================");
        Log.i(TAG, "🚀 RCS WebSocket Server Started");
        Log.i(TAG, "========================================");
        Log.i(TAG, "📍 Local IP: " + localIp);
        Log.i(TAG, "🔌 WebSocket Server: ws://" + localIp + ":" + portToShow);
        Log.i(TAG, "========================================");
        Log.i(TAG, "📡 Upper system should connect to:");
        Log.i(TAG, "   ws://" + localIp + ":" + portToShow);
        Log.i(TAG, "========================================");
    }

    // ==================================================================
    // Network Monitoring
    // ==================================================================

    private void initNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateNetworkState();
            }
        };

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            registerReceiver(networkReceiver, filter);
            Log.d(TAG, "Network receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network receiver", e);
        }

        updateNetworkState();
    }

    private void updateNetworkState() {
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        String newState = isConnected ? "CONNECTED" : "DISCONNECTED";
        String networkType = activeNetwork != null ? activeNetwork.getTypeName() : "NONE";

        if (!newState.equals(lastNetworkState)) {
            lastNetworkState = newState;
            isNetworkAvailable.set(isConnected);

            if (isConnected && isServerRunning()) {
                logServerAccessInfo();
            }
        }
    }

    // ==================================================================
    // Utility Methods
    // ==================================================================

    /**
     * Check if a position is an elevator-related point
     * Elevator point types: 2-preride, 3-wait, 4-ride, 14-transition
     */
    private boolean isElevatorPoint(Position pos) {
        if (pos == null) return false;
        int type = pos.getType();
        return type == 2 || type == 3 || type == 4 || type == 14;
    }

    /**
     * Check if a station name indicates an elevator-related point
     * Used for filtering recorded station details (which don't have type info)
     */
    private boolean isElevatorPointByName(String name) {
        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("preride") ||
               lowerName.contains("wait") ||
               lowerName.contains("ride") ||
               lowerName.contains("候梯") ||
               lowerName.contains("乘梯") ||
               lowerName.contains("前置") ||
               lowerName.contains("transition") ||
               lowerName.contains("切换") ||
               lowerName.contains("elevator");
    }

    private void logServiceHeartbeat() {
        long uptime = System.currentTimeMillis() - serviceCreateTime;
        Log.i(TAG, String.format("💓 RcsWebSocketService %d HEARTBEAT - Uptime: %d ms, Connected clients: %d, Network: %s",
                serviceInstanceId, uptime, connectedClients.size(),
                isNetworkAvailable.get() ? "AVAILABLE" : "UNAVAILABLE"));
    }

    private boolean shouldRun() {
        return true;
    }

    // ==================================================================
    // Public API
    // ==================================================================

    public boolean isServerRunning() {
        return isServerStarted && !isServerStarting && webSocketServer != null;
    }

    public int getConnectedClientCount() {
        return connectedClients.size();
    }

    public String getServerAddress() {
        int portToShow = actualPort > 0 ? actualPort : serverPort;
        return getLocalIpAddress() + ":" + portToShow;
    }
}
