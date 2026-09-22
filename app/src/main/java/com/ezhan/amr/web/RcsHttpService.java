package com.ezhan.amr.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.JackOperation;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.MapPoints;
import com.ezhan.amr.data.datatype.MarkerData;
import com.ezhan.amr.data.datatype.MarkerPoint;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.NavigationPathData;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.SafetyArea;
import com.ezhan.amr.data.datatype.SafetyAreasData;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.navigation.TaskExecutor;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.TaskViewModel;
import com.ezhan.amr.utils.PositionDisplayNameHelper;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import fi.iki.elonen.NanoHTTPD;

public class RcsHttpService extends Service {
    private static final String TAG = "RcsHttpService";
    private NanoHTTPD httpServer;
    private TaskExecutor taskExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int port = 8068; // Different port from WebService
    private String bindIp = "0.0.0.0"; // Bind to all interfaces
    private boolean isServerStarted = false;
    private MapViewModel mapViewModel;
    private CommandWebSocketClient commandClient;
    private StatusWebSocketClient statusClient;
    private SharedViewModel sharedViewModel;
    private TaskViewModel taskViewModel;
    private CruiseViewModel cruiseViewModel;
    private JackViewModel jackViewModel;

    // Add network state tracking
    private ConnectivityManager connectivityManager;
    private BroadcastReceiver networkReceiver;
    private String lastNetworkState = "UNKNOWN";
    private AtomicBoolean isNetworkAvailable = new AtomicBoolean(true);
    private long lastNetworkChangeTime = 0;

    // Add service lifecycle tracking
    private static final AtomicInteger serviceInstanceCounter = new AtomicInteger(0);
    private final int serviceInstanceId;
    private long serviceCreateTime;
    private long serviceStartTime;
    private long lastRequestTime = 0;
    private int totalRequestsHandled = 0;
    private int successfulRequests = 0;
    private int failedRequests = 0;

    // Add a heartbeat mechanism to monitor service health
    private static final long HEARTBEAT_INTERVAL = 30000; // 30 seconds
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            logServiceHeartbeat();
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };


    // Request model class
    public static class TaskRequest {
        public int id;
        public String taskType;
        public String station;
        public String action;
        public String message;
        public int waitTime;
        public String waitTimeValues;
        public String taskId;
        public String taskName;
        public String mapName;
        public JSONObject params;

        public TaskRequest(int id, String taskType, String station, String action, String message,
                           int waitTime, String waitTimeValues, String taskId, String taskName, String mapName, JSONObject params) {
            this.id = id;
            this.taskType = taskType;
            this.station = station;
            this.action = action;
            this.message = message;
            this.waitTime = waitTime;
            this.waitTimeValues = waitTimeValues;
            this.taskId = taskId;
            this.taskName = taskName;
            this.mapName = mapName;
            this.params = params;
        }
    }

    public RcsHttpService() {
        // Generate unique instance ID for this service
        serviceInstanceId = serviceInstanceCounter.incrementAndGet();
        Log.i(TAG, String.format("Service instance %d created (Total instances: %d)",
                serviceInstanceId, serviceInstanceCounter.get()));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceCreateTime = System.currentTimeMillis();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService();
        }

        Log.d(TAG, String.format("RcsHttptService instance %d creating...", serviceInstanceId));

        // Initialize dependencies
        MyApplication.getInstance().bindRcsHttpService(this);
        taskExecutor = MyApplication.getInstance().getTaskExecutor();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        taskViewModel = MyApplication.getInstance().getTaskViewModel();
        cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
        jackViewModel = MyApplication.getInstance().getJackViewModel();
        commandClient = sharedViewModel.getCommandClient();
        statusClient = sharedViewModel.getStatusClient();

        // Initialize network monitoring
        initNetworkMonitoring();

        // Log initial network state
        logNetworkState("Service onCreate");

        // Start the HTTP server
        if (!isServerStarted) {
            startHttpServer();
        }

        // Start heartbeat monitoring
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);

        Log.i(TAG, String.format("Service instance %d fully created in %d ms",
                serviceInstanceId, System.currentTimeMillis() - serviceCreateTime));
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
        Log.d(TAG, String.format("Service instance %d onDestroy() called", serviceInstanceId));
        logServiceLifecycle("Service onDestroy");

        // Stop heartbeat
        mainHandler.removeCallbacks(heartbeatRunnable);

        // Unregister network receiver
        if (networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
                Log.d(TAG, String.format("Service instance %d network receiver unregistered", serviceInstanceId));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, String.format("Service instance %d network receiver already unregistered", serviceInstanceId));
            }
        }

        MyApplication.getInstance().bindRcsHttpService(null);

        // Stop the HTTP server
        if (httpServer != null) {
            try {
                httpServer.stop();
                Log.d(TAG, String.format("Service instance %d HTTP server stopped", serviceInstanceId));
            } catch (Exception e) {
                Log.e(TAG, String.format("Service instance %d Error stopping HTTP server", serviceInstanceId), e);
            }
            httpServer = null;
        }
        isServerStarted = false;

        // Decrement instance counter
        int remainingInstances = serviceInstanceCounter.decrementAndGet();
        Log.i(TAG, String.format("Service instance %d destroyed. Remaining instances: %d",
                serviceInstanceId, remainingInstances));

        // Log service lifetime
        long lifetime = System.currentTimeMillis() - serviceCreateTime;
        Log.i(TAG, String.format("Service instance %d total lifetime: %d ms", serviceInstanceId, lifetime));
        Log.i(TAG, String.format("Service instance %d request statistics: Total: %d, Success: %d, Failed: %d",
                serviceInstanceId, totalRequestsHandled, successfulRequests, failedRequests));

        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, String.format("Service instance %d onTaskRemoved() called", serviceInstanceId));
        logServiceLifecycle("Service task removed - app may have been swiped away");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG, String.format("Service instance %d onLowMemory() called", serviceInstanceId));
        logServiceLifecycle("System low memory warning");
        super.onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        String levelName = getTrimMemoryLevelName(level);
        Log.w(TAG, String.format("Service instance %d onTrimMemory() called, level: %s (%d)",
                serviceInstanceId, levelName, level));
        logServiceLifecycle("System trimming memory, level: " + levelName);
        super.onTrimMemory(level);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, String.format("Service instance %d onBind() called", serviceInstanceId));
        return null;
    }

    private String getTrimMemoryLevelName(int level) {
        switch (level) {
            case TRIM_MEMORY_COMPLETE:
                return "TRIM_MEMORY_COMPLETE";
            case TRIM_MEMORY_MODERATE:
                return "TRIM_MEMORY_MODERATE";
            case TRIM_MEMORY_BACKGROUND:
                return "TRIM_MEMORY_BACKGROUND";
            case TRIM_MEMORY_UI_HIDDEN:
                return "TRIM_MEMORY_UI_HIDDEN";
            case TRIM_MEMORY_RUNNING_CRITICAL:
                return "TRIM_MEMORY_RUNNING_CRITICAL";
            case TRIM_MEMORY_RUNNING_LOW:
                return "TRIM_MEMORY_RUNNING_LOW";
            case TRIM_MEMORY_RUNNING_MODERATE:
                return "TRIM_MEMORY_RUNNING_MODERATE";
            default:
                return "UNKNOWN";
        }
    }

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
            Log.d(TAG, String.format("Service instance %d network receiver registered", serviceInstanceId));
        } catch (Exception e) {
            Log.e(TAG, String.format("Service instance %d failed to register network receiver", serviceInstanceId), e);
        }

        // Initial network state check
        updateNetworkState();
    }

    private void updateNetworkState() {
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        String newState = isConnected ? "CONNECTED" : "DISCONNECTED";
        String networkType = activeNetwork != null ? activeNetwork.getTypeName() : "NONE";

        if (!newState.equals(lastNetworkState)) {
            lastNetworkChangeTime = System.currentTimeMillis();
            Log.w(TAG, String.format("Service instance %d network state changed from %s to %s (Type: %s)",
                    serviceInstanceId, lastNetworkState, newState, networkType));
            lastNetworkState = newState;

            // Log service status when network changes
            logServiceLifecycle("Network state changed to: " + newState);
        }

        isNetworkAvailable.set(isConnected);
    }

    private void logNetworkState(String context) {
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        String networkType = activeNetwork != null ? activeNetwork.getTypeName() : "NONE";

        Log.i(TAG, String.format("Service instance %d - %s - Network: %s, Type: %s",
                serviceInstanceId, context, (isConnected ? "CONNECTED" : "DISCONNECTED"), networkType));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startForegroundService() {
        String channelId = "rcs_service_channel";
        NotificationChannel channel = new NotificationChannel(
                channelId,
                "Rcs Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Service Running")
                .setContentText("Your service is running in foreground")
                .build();

        startForeground(3, notification); // Unique ID for each service
    }

    private void startHttpServer() {
        int tryPort = port;
        int maxPort = 8068; // Different range from WebService

        Log.d(TAG, String.format("Service instance %d starting HTTP server, initial port: %d",
                serviceInstanceId, tryPort));

        while (tryPort <= maxPort && !isServerStarted) {
            final int currentPort = tryPort;
            httpServer = createHttpServer(currentPort);

            try {
                httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                isServerStarted = true;
                port = currentPort;
                Log.i(TAG, String.format("Service instance %d HTTP server started successfully on port: %d",
                        serviceInstanceId, currentPort));
                logServerStatus();
                break;
            } catch (IOException e) {
                Log.e(TAG, String.format("Service instance %d port %d occupied: %s",
                        serviceInstanceId, currentPort, e.getMessage()));

                if (httpServer != null) {
                    try {
                        httpServer.stop();
                    } catch (Exception ex) {
                        Log.w(TAG, String.format("Service instance %d error stopping failed server", serviceInstanceId), ex);
                    }
                    httpServer = null;
                }

                tryPort++;

                if (tryPort > maxPort) {
                    Log.e(TAG, String.format("Service instance %d all ports from %d to %d are occupied",
                            serviceInstanceId, port, maxPort));
                }
            }
        }

        if (!isServerStarted) {
            Log.e(TAG, String.format("Service instance %d failed to start HTTP server on any port!",
                    serviceInstanceId));
            logServiceLifecycle("HTTP server failed to start");
        } else {
            logServiceLifecycle("HTTP server started successfully");
        }
    }

    private void logServerStatus() {
        Log.i(TAG, String.format("=== Service instance %d HTTP SERVER STATUS ===", serviceInstanceId));
        Log.i(TAG, String.format("Server started: %s", isServerStarted));
        Log.i(TAG, String.format("Port: %d", port));
        Log.i(TAG, String.format("Bind IP: %s", bindIp));
        Log.i(TAG, String.format("HTTP Server instance: %s", (httpServer != null ? "NOT NULL" : "NULL")));

        if (httpServer != null) {
            Log.i(TAG, String.format("HTTP Server is alive: %s", httpServer.isAlive()));
            Log.i(TAG, String.format("HTTP Server was started: %s", httpServer.wasStarted()));
        }
        Log.i(TAG, String.format("=========================="));
    }

    private void logServiceLifecycle(String event) {
        long uptime = System.currentTimeMillis() - serviceCreateTime;
        Log.i(TAG, String.format("Service instance %d LIFECYCLE - %s (Uptime: %d ms)",
                serviceInstanceId, event, uptime));

        // Also log detailed status
        Log.i(TAG, String.format("Service instance %d STATUS - Requests: Total=%d, Success=%d, Failed=%d, LastRequest=%d ms ago",
                serviceInstanceId, totalRequestsHandled, successfulRequests, failedRequests,
                lastRequestTime > 0 ? System.currentTimeMillis() - lastRequestTime : -1));
    }

    private void logServiceHeartbeat() {
        long uptime = System.currentTimeMillis() - serviceCreateTime;
        long timeSinceLastRequest = lastRequestTime > 0 ? System.currentTimeMillis() - lastRequestTime : -1;

        Log.i(TAG, String.format("Service instance %d HEARTBEAT - Uptime: %d ms, Last request: %d ms ago",
                serviceInstanceId, uptime, timeSinceLastRequest));

        // Log detailed status every 5 heartbeats (every 2.5 minutes)
        if ((uptime / HEARTBEAT_INTERVAL) % 5 == 0) {
            logServiceLifecycle("Periodic heartbeat check");
            logServerStatus();
            logNetworkState("Heartbeat check");
        }
    }

    private NanoHTTPD createHttpServer(int port) {
        return new NanoHTTPD(bindIp, port) {
            @Override
            public Response serve(IHTTPSession session) {
                String clientIp = session.getRemoteIpAddress();
                String method = session.getMethod().name();
                String uri = session.getUri();
                long requestTime = System.currentTimeMillis();
                lastRequestTime = requestTime;
                totalRequestsHandled++;

                Log.d(TAG, String.format("Service instance %d request #%d from %s: %s %s",
                        serviceInstanceId, totalRequestsHandled, clientIp, method, uri));

                try {
                    // Log network state for this request
                    logNetworkState("HTTP Request - " + method + " " + uri);

                    // Log WebSocket connection status

                    // Handle CORS preflight requests
                    if (session.getMethod() == NanoHTTPD.Method.OPTIONS) {
                        Log.d(TAG, String.format("Service instance %d handling CORS preflight request", serviceInstanceId));
                        successfulRequests++;
                        return createCorsResponse();
                    }

                    String path = normalizePath(session.getUri());
                    NanoHTTPD.Response response = null;

                    // Handle GET requests for status
                    if (session.getMethod() == NanoHTTPD.Method.GET) {
                        Log.d(TAG, String.format("Service instance %d GET request for path: %s", serviceInstanceId, path));

                        NanoHTTPD.Response bodyErrorResponse = consumeUnexpectedGetBody(session);
                        if (bodyErrorResponse != null) {
                            failedRequests++;
                            return bodyErrorResponse;
                        }

                        // Get parameters (NanoHTTPD automatically parses them)
                        Map<String, String> params = session.getParms();

                        if ("/robotStatus".equals(path) || "/robotstatus".equals(path)) {
                            response = fetchRobotStatusObjectResponse();
                        } else if ("/allMapPoints".equals(path)) {
                            response = fetchAllMapPointsObjectResponse();
                        } else if ("/currentMapInfo".equals(path)) {
                            response = fetchCurrentMapInfoObjectResponse();
                        } else if ("/safetyAreas".equals(path)) {
                            response = fetchSafetyAreasObjectResponse();
                        } else if ("/navigatePath".equals(path)) {
                            response = fetchNavigatePathObjectResponse();
                        } else if ("/rcsTaskStatus".equals(path)) {
                            // Direct path check works!
                            String taskId = params.get("taskId");
                            if (taskId != null && !taskId.isEmpty()) {
                                response = fetchRcsTaskStatusObjectResponse(taskId);
                            } else {
                                Log.w(TAG, String.format("Service instance %d missing taskId parameter for /rcsTaskStatus", serviceInstanceId));
                                failedRequests++;
                                return createErrorResponse(Response.Status.BAD_REQUEST,
                                        "Missing required parameter: taskId");
                            }
                        } else if ("/hmiTaskStatus".equals(path)) {
                            // Direct path check works!
                            String dateTime = params.get("dateTime");
                            if (dateTime != null && !dateTime.isEmpty()) {
                                response = fetchHmiTaskStatusObjectResponse(dateTime);
                            } else {
                                Log.w(TAG, String.format("Service instance %d missing dateTime parameter for /hmiTaskStatus", serviceInstanceId));
                                failedRequests++;
                                return createErrorResponse(Response.Status.BAD_REQUEST,
                                        "Missing required parameter: dateTime");
                            }
                        } else if ("/hmiTaskStatusDetails".equals(path)) {
                            String taskId = params.get("taskId");
                            String dateTime2 = params.get("dateTime");
                            String startTime = params.get("startTime");
                            String endTime = params.get("endTime");
                            response = fetchHmiTaskStatusDetailsObjectResponse(
                                    taskId != null ? taskId : "",
                                    dateTime2 != null ? dateTime2 : "",
                                    startTime != null ? startTime : "",
                                    endTime != null ? endTime : "");
                        } else if ("/config".equals(path)) {
                            response = handleGetConfig();
                        } else {
                            Log.w(TAG, String.format("Service instance %d unknown GET endpoint: %s", serviceInstanceId, path));
                            failedRequests++;
                            return createErrorResponse(Response.Status.NOT_FOUND,
                                    "GET endpoint not found. Available: " +
                                            "/robotStatus, /allMapPoints, /currentMapInfo, /safetyAreas, " +
                                            "/navigatePath, /rcsTaskStatus, /hmiTaskStatus, /hmiTaskStatusDetails, /config");
                        }

                        if (response != null && response.getStatus() != null &&
                                response.getStatus().getRequestStatus() >= 200 &&
                                response.getStatus().getRequestStatus() < 300) {
                            successfulRequests++;
                        } else {
                            failedRequests++;
                        }

                        long responseTime = System.currentTimeMillis() - requestTime;
                        Log.d(TAG, String.format("Service instance %d request completed in %d ms",
                                serviceInstanceId, responseTime));
                        return response;
                    }

                    // Handle POST requests for other endpoints
                    if (session.getMethod() != NanoHTTPD.Method.POST) {
                        Log.w(TAG, String.format("Service instance %d method not allowed: %s",
                                serviceInstanceId, session.getMethod()));
                        failedRequests++;
                        return createErrorResponse(Response.Status.METHOD_NOT_ALLOWED,
                                "Only GET and POST methods are allowed");
                    }

                    if (!isAllowedPostPath(path)) {
                        Log.w(TAG, String.format("Service instance %d unknown POST endpoint: %s",
                                serviceInstanceId, path));
                        failedRequests++;
                        return createErrorResponse(Response.Status.NOT_FOUND,
                                "POST endpoint not found: " + path);
                    }

                    // Parse JSON body for POST requests
                    String body = parseRequestBody(session);
                    if (body == null || body.isEmpty()) {
                        Log.w(TAG, String.format("Service instance %d empty request body", serviceInstanceId));
                        failedRequests++;
                        return createErrorResponse(Response.Status.BAD_REQUEST,
                                "Empty request body");
                    }

                    Log.d(TAG, String.format("Service instance %d request body: %s",
                            serviceInstanceId, (body.length() > 200 ? body.substring(0, 200) + "..." : body)));

                    if ("/rcsTaskStatus".equals(path)) {
                        String taskId = extractStringField(body, "taskId");
                        if (taskId == null || taskId.trim().isEmpty()) {
                            failedRequests++;
                            return createErrorResponse(Response.Status.BAD_REQUEST,
                                    "Missing required field: taskId");
                        }

                        response = fetchRcsTaskStatusObjectResponse(taskId.trim());
                        if (response != null && response.getStatus() != null &&
                                response.getStatus().getRequestStatus() >= 200 &&
                                response.getStatus().getRequestStatus() < 300) {
                            successfulRequests++;
                        } else {
                            failedRequests++;
                        }
                        return response;
                    }

                    // Handle config endpoints
                    if ("/config/load".equals(path)) {
                        response = handleConfigLoad();
                        if (response != null && response.getStatus() != null &&
                                response.getStatus().getRequestStatus() >= 200 &&
                                response.getStatus().getRequestStatus() < 300) {
                            successfulRequests++;
                        } else {
                            failedRequests++;
                        }
                        return response;
                    }

                    if ("/config/save".equals(path)) {
                        response = handleConfigSave();
                        if (response != null && response.getStatus() != null &&
                                response.getStatus().getRequestStatus() >= 200 &&
                                response.getStatus().getRequestStatus() < 300) {
                            successfulRequests++;
                        } else {
                            failedRequests++;
                        }
                        return response;
                    }

                    if ("/config/autoLoad".equals(path)) {
                        Boolean enabled = extractBooleanField(body, "enabled");
                        if (enabled == null) {
                            failedRequests++;
                            return createErrorResponse(Response.Status.BAD_REQUEST,
                                    "Missing required field: enabled (boolean)");
                        }
                        response = handleConfigAutoLoad(enabled);
                        if (response != null && response.getStatus() != null &&
                                response.getStatus().getRequestStatus() >= 200 &&
                                response.getStatus().getRequestStatus() < 300) {
                            successfulRequests++;
                        } else {
                            failedRequests++;
                        }
                        return response;
                    }

                    // Parse JSON
                    TaskRequest taskRequest = parseTaskRequest(body);
                    if (taskRequest == null) {
                        Log.w(TAG, String.format("Service instance %d invalid JSON format in request body",
                                serviceInstanceId));
                        failedRequests++;
                        return createErrorResponse(Response.Status.BAD_REQUEST,
                                "Invalid JSON format");
                    }

                    NanoHTTPD.Response validationError = validateTaskRequestForPath(path, taskRequest);
                    if (validationError != null) {
                        failedRequests++;
                        return validationError;
                    }

                    // Process the task
                    if ("/createCruiseTask".equals(path) || isNamedTaskRequest(taskRequest, "cruise")) {
                        response = handleCreateCruiseTask(taskRequest);
                    } else if ("/createJackTask".equals(path) || isNamedTaskRequest(taskRequest, "jack")) {
                        response = handleCreateJackTask(taskRequest);
                    } else {
                        response = processTaskRequest(taskRequest);
                    }
                    if (response != null && response.getStatus() != null &&
                            response.getStatus().getRequestStatus() >= 200 &&
                            response.getStatus().getRequestStatus() < 300) {
                        successfulRequests++;
                    } else {
                        failedRequests++;
                    }
                    return response;

                } catch (Exception e) {
                    failedRequests++;
                    Log.e(TAG, String.format("Service instance %d error processing request %s %s",
                            serviceInstanceId, method, uri), e);
                    long responseTime = System.currentTimeMillis() - requestTime;
                    Log.e(TAG, String.format("Service instance %d request failed after %d ms",
                            serviceInstanceId, responseTime));
                    return createErrorResponse(Response.Status.INTERNAL_ERROR,
                            "Internal server error: " + e.getMessage());
                }
            }
        };
    }

    private String parseRequestBody(NanoHTTPD.IHTTPSession session) throws IOException, NanoHTTPD.ResponseException {
        Map<String, String> files = new HashMap<>();
        // NanoHTTPD 2.3.1 decodes the request body as US-ASCII when the Content-Type
        // header is present but has no charset, which corrupts non-ASCII characters
        // (e.g. Chinese). Ensure charset=UTF-8 so the body is decoded correctly.
        String contentType = session.getHeaders().get("content-type");
        if (contentType == null || contentType.trim().isEmpty()) {
            session.getHeaders().put("content-type", "application/json; charset=UTF-8");
        } else if (!contentType.toLowerCase().contains("charset")) {
            session.getHeaders().put("content-type", contentType + "; charset=UTF-8");
        }
        session.parseBody(files);
        return files.get("postData");
    }

    private NanoHTTPD.Response consumeUnexpectedGetBody(NanoHTTPD.IHTTPSession session) {
        String contentLength = session.getHeaders().get("content-length");
        if (contentLength == null || contentLength.trim().isEmpty()) {
            return null;
        }

        try {
            long bodyLength = Long.parseLong(contentLength.trim());
            if (bodyLength <= 0) {
                return null;
            }

            session.parseBody(new HashMap<>());
            Log.w(TAG, "Consumed unexpected GET request body, contentLength=" + bodyLength);
            return null;
        } catch (NumberFormatException e) {
            NanoHTTPD.Response response = createErrorResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            response.closeConnection(true);
            return response;
        } catch (IOException | NanoHTTPD.ResponseException e) {
            Log.w(TAG, "Failed to consume unexpected GET request body", e);
            NanoHTTPD.Response response = createErrorResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid GET request body");
            response.closeConnection(true);
            return response;
        }
    }

    private String normalizePath(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return "/";
        }

        String normalized = uri.trim();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }

        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private boolean isNamedTaskRequest(TaskRequest request, String taskType) {
        return request != null &&
                request.taskType != null &&
                request.taskType.trim().equalsIgnoreCase(taskType) &&
                request.taskName != null &&
                !request.taskName.trim().isEmpty() &&
                (request.station == null || request.station.trim().isEmpty());
    }

    private String extractStringField(String jsonBody, String fieldName) {
        try {
            JSONObject json = new JSONObject(jsonBody);
            return json.optString(fieldName, "");
        } catch (JSONException e) {
            Log.e(TAG, "JSON parsing error", e);
            return null;
        }
    }

    private Boolean extractBooleanField(String jsonBody, String fieldName) {
        try {
            JSONObject json = new JSONObject(jsonBody);
            if (json.has(fieldName)) {
                return json.getBoolean(fieldName);
            }
            return null;
        } catch (JSONException e) {
            Log.e(TAG, "JSON parsing error", e);
            return null;
        }
    }

    private String getGlobalTaskStatus() {
        return taskViewModel != null
                ? taskViewModel.getCurrentTaskStatusValue()
                : TaskViewModel.TASK_STATUS_IDLE;
    }

    private void markGlobalTaskExecuting(String taskType, String taskName) {
        markGlobalTaskExecuting(taskType, taskName, TaskViewModel.TASK_INFO_NONE);
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

    private boolean isControlTask(String taskType) {
        if (taskType == null) {
            return false;
        }

        switch (taskType.trim().toLowerCase()) {
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

    private boolean isGlobalTaskExecuting() {
        return TaskViewModel.TASK_STATUS_EXECUTING.equals(getGlobalTaskStatus());
    }

    private NanoHTTPD.Response createTaskBusyResponse(TaskRequest request) {
        String taskId = request != null && request.taskId != null ? request.taskId.trim() : "";
        String taskType = request != null && request.taskType != null ? request.taskType.trim() : "";
        Log.w(TAG, "Rejecting RCS task while another task is executing, taskType: " + taskType + ", taskId: " + taskId);
        return createErrorResponse(NanoHTTPD.Response.Status.CONFLICT,
                "Robot is executing a task, reject new task: " + taskType);
    }

    private NanoHTTPD.Response rejectIfTaskBusy(TaskRequest request) {
        if (request != null && !isControlTask(request.taskType) && isGlobalTaskExecuting()) {
            return createTaskBusyResponse(request);
        }
        return null;
    }

    // Update the status method to handle both GET and POST
    private NanoHTTPD.Response fetchRobotStatusObjectResponse() {
        try {
            if (statusClient == null || !statusClient.isConnected()) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Status WebSocket not connected");
            }

            // Get the last status response directly
            AgvStatusResponse statusResponse = statusClient.getLastStatusResponse();
            if (statusResponse == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.NO_CONTENT,
                        "No status data available yet");
            }

            int jackState = commandClient != null
                    ? commandClient.getCachedJackState()
                    : 0;
            JSONObject statusData = convertStatusDataToJson(statusResponse, jackState);

            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "status");
            jsonResponse.put("data", statusData);
            jsonResponse.put("timestamp", System.currentTimeMillis());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching status", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Error fetching status: " + e.getMessage());
        }
    }

    private JSONObject convertStatusDataToJson(AgvStatusResponse statusResponse, int jackState)
            throws JSONException {
        JSONObject dataJson = new JSONObject();

        if (statusResponse != null && statusResponse.data != null) {
            AgvStatusResponse.Data data = statusResponse.data;

            // Basic AGV information
            dataJson.put("cmd", statusResponse.cmd != null ? statusResponse.cmd : "");
            dataJson.put("talk", statusResponse.talk != null ? statusResponse.talk : "");
            dataJson.put("time", statusResponse.time != null ? statusResponse.time : "");

            if (taskViewModel != null) {
                TaskViewModel.TaskState taskState = taskViewModel.getCurrentTaskStateSnapshot();
                dataJson.put("task_status", taskState.status);
                dataJson.put("taskType", taskState.taskType);
                putTaskId(dataJson, taskState.taskId);
                dataJson.put("taskName", taskState.taskName);
            } else {
                dataJson.put("task_status", TaskViewModel.TASK_STATUS_IDLE);
                dataJson.put("taskType", TaskViewModel.TASK_INFO_NONE);
                putTaskId(dataJson, TaskViewModel.TASK_INFO_NONE);
                dataJson.put("taskName", TaskViewModel.TASK_INFO_NONE);
            }
            dataJson.put("jackState", jackState);

            // Movement and state
            dataJson.put("agvStop", data.agvStop);
            dataJson.put("movement", data.movement);
            dataJson.put("goalFinish", data.goalFinish);
            dataJson.put("emgStop", data.emgStop);
            dataJson.put("wheelLock", data.wheelLock);
            dataJson.put("isSoftPause", data.isSoftPause);
            dataJson.put("collisionWarning", data.collisionWarning);

            // Power and battery
            dataJson.put("power", data.power);
            dataJson.put("powerQuantity", data.powerQuantity);
            dataJson.put("electricCurrentIn", data.electricCurrentIn);
            dataJson.put("electricCurrentOut", data.electricCurrentOut);
            dataJson.put("currentBv", data.currentBv);
            dataJson.put("currentFv", data.currentFv);

            // Navigation and mapping
            dataJson.put("inNavMap", data.inNavMap);
            dataJson.put("inBuildMap", data.inBuildMap);
            dataJson.put("currentGlobalId", data.currentGlobalId);
            dataJson.put("poseProbability", data.poseProbability);

            // System information
            dataJson.put("robotId", data.robotId != null ? data.robotId : "");
            dataJson.put("version", data.version != null ? data.version : "");
            dataJson.put("odomHz", data.odomHz);
            dataJson.put("scanHz", data.scanHz);

            // Error codes
            if (data.errorCode != null && !data.errorCode.isEmpty()) {
                JSONArray errorArray = new JSONArray();
                for (Integer error : data.errorCode) {
                    errorArray.put(error);
                }
                dataJson.put("errorCode", errorArray);
            } else {
                dataJson.put("errorCode", new JSONArray());
            }
            dataJson.put("errCode", data.errCode);

            // Charge information
            dataJson.put("chargeStep", data.chargeStep);
            dataJson.put("inManualCharge", data.inManualCharge);

            // Position data
            if (data.pos != null) {
                JSONObject posJson = new JSONObject();
                posJson.put("mapName", data.pos.mapName != null ? data.pos.mapName : "");
                posJson.put("x", data.pos.x);
                posJson.put("y", data.pos.y);
                posJson.put("z", data.pos.z);
                posJson.put("theta", data.pos.theta);
                dataJson.put("position", posJson);
                dataJson.put("currentLocation", resolveCurrentLocationText(data.pos));
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

                dataJson.put("chargeStatus", chargeJson);
            }

            // Connection health information (custom field)
            if (statusClient != null) {
                StatusWebSocketClient.ConnectionHealth health = statusClient.getConnectionHealth();
                JSONObject healthJson = new JSONObject();
                healthJson.put("isConnected", health.isConnected);
                healthJson.put("hasReceivedStatus", health.hasReceivedStatus);
                healthJson.put("timeSinceLastMessage", health.timeSinceLastMessage);
                healthJson.put("timeSinceLastStatus", health.timeSinceLastStatus);
                dataJson.put("connectionHealth", healthJson);
            }
        }

        return dataJson;
    }

    private String resolveCurrentLocationText(AgvStatusResponse.DataPosition currentPos) {
        if (currentPos == null) {
            return "";
        }

        Position nearbyPoint = findNearbyPoint(currentPos);
        if (nearbyPoint != null) {
            String displayName = PositionDisplayNameHelper.getDisplayName(this, nearbyPoint);
            if (!displayName.trim().isEmpty()) {
                return displayName.trim();
            }
        }

        return String.format(Locale.US, "x=%.2f, y=%.2f", currentPos.x, currentPos.y);
    }

    private Position findNearbyPoint(AgvStatusResponse.DataPosition currentPos) {
        if (currentPos == null || mapViewModel == null) {
            return null;
        }

        String currentMap = currentPos.mapName;
        if (currentMap == null || currentMap.trim().isEmpty()) {
            return null;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            return null;
        }

        String buildingId = mapViewModel.extractBuildingId(currentMap);
        int currentFloor = mapViewModel.extractMapFloor(currentMap);
        Building building = mapPoints.getBuilding(buildingId);
        FloorPoints floorPoints = building != null ? building.getFloorPoints(currentFloor) : null;
        if (floorPoints == null) {
            return null;
        }

        Position nearestPoint = null;
        double nearestDistance = Double.MAX_VALUE;
        final double pointThresholdMeters = 0.5;

        for (Position point : floorPoints.getAllPoints()) {
            double distance = calculateDistanceToRobot(currentPos, point, currentMap);
            if (distance <= pointThresholdMeters && distance < nearestDistance) {
                nearestDistance = distance;
                nearestPoint = point;
            }
        }

        return nearestPoint;
    }

    private double calculateDistanceToRobot(AgvStatusResponse.DataPosition currentPos,
                                            Position point,
                                            String currentMap) {
        if (currentPos == null || point == null || point.getName() == null
                || point.getName().trim().isEmpty()) {
            return Double.MAX_VALUE;
        }
        if (!isSameMapForCurrentLocation(currentMap, point.getMapName())) {
            return Double.MAX_VALUE;
        }

        double dx = currentPos.x - point.getPosX();
        double dy = currentPos.y - point.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean isSameMapForCurrentLocation(String currentMap, String pointMap) {
        if (currentMap == null || currentMap.trim().isEmpty()
                || pointMap == null || pointMap.trim().isEmpty()) {
            return true;
        }
        return normalizeMapNameForCurrentLocation(currentMap)
                .equals(normalizeMapNameForCurrentLocation(pointMap));
    }

    private String normalizeMapNameForCurrentLocation(String mapName) {
        return mapName == null ? "" : mapName.trim().replaceFirst("\\.[^.]+$", "");
    }

    private void putTaskId(JSONObject dataJson, String taskId) throws JSONException {
        String normalizedTaskId = taskId == null ? "" : taskId.trim();
        if (normalizedTaskId.isEmpty()) {
            dataJson.put("taskId", TaskViewModel.TASK_INFO_NONE);
            return;
        }

        dataJson.put("taskId", normalizedTaskId);
    }

    private TaskRequest parseTaskRequest(String jsonBody) {
        try {
            JSONObject json = new JSONObject(jsonBody);
            String taskType = json.optString("taskType", "");
            if (taskType.trim().isEmpty()) {
                taskType = json.optString("tasktype", "");
            }
            return new TaskRequest(
                    json.optInt("id", 0),
                    taskType,
                    json.optString("station", ""),
                    json.optString("action", ""),
                    json.optString("message", ""),
                    json.optInt("waitTime", 0),
                    json.optString("waitTime", String.valueOf(json.optInt("waitTime", 0))),
                    json.optString("taskId", ""),
                    json.optString("taskName", ""),
                    json.optString("mapName", ""),
                    json.optJSONObject("params")
            );
        } catch (JSONException e) {
            Log.e(TAG, "JSON parsing error", e);
            return null;
        }
    }

    private NanoHTTPD.Response validateTaskRequestForPath(String path, TaskRequest request) {
        String taskType = request.taskType == null ? "" : request.taskType.trim().toLowerCase();
        if (!isSupportedTaskType(taskType)) {
            return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Unsupported or missing taskType: " + taskType);
        }
        request.taskType = taskType;

        String expectedTaskType = getControlTaskTypeForPath(path);
        if (expectedTaskType != null && !expectedTaskType.equals(taskType)) {
            return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Path " + path + " requires taskType " + expectedTaskType
                            + ", but received " + taskType);
        }
        return null;
    }

    private String getControlTaskTypeForPath(String path) {
        if (path == null) {
            return null;
        }
        switch (path.toLowerCase()) {
            case "/cancel":
                return "cancel";
            case "/pause":
                return "pause";
            case "/resume":
                return "resume";
            case "/release":
                return "release";
            case "/areaset":
                return "areaset";
            case "/park":
                return "park";
            case "/charge":
                return "charge";
            case "/delivery":
                return "delivery";
            case "/cruise":
                return "cruise";
            case "/jack":
                return "jack";
            default:
                return null;
        }
    }

    private boolean isAllowedPostPath(String path) {
        if (path == null) {
            return false;
        }
        switch (path) {
            case "/cancel":
            case "/pause":
            case "/resume":
            case "/release":
            case "/areaset":
            case "/park":
            case "/charge":
            case "/delivery":
            case "/cruise":
            case "/jack":
            case "/createCruiseTask":
            case "/createJackTask":
            case "/rcsTaskStatus":
            case "/config/load":
            case "/config/save":
            case "/config/autoLoad":
                return true;
            default:
                return false;
        }
    }

    private boolean isSupportedTaskType(String taskType) {
        switch (taskType) {
            case "cancel":
            case "pause":
            case "resume":
            case "release":
            case "areaset":
            case "park":
            case "charge":
            case "delivery":
            case "cruise":
            case "jack":
                return true;
            default:
                return false;
        }
    }

    private boolean isValidTaskRequest(TaskRequest request) {
        return request.id != 0 &&
                request.taskType != null && !request.taskType.isEmpty() &&
                request.action != null && !request.action.isEmpty();
    }

    private NanoHTTPD.Response handleCreateCruiseTask(TaskRequest request) {
        try {
            if (request == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid request body");
            }

            if (request.taskType == null || !"cruise".equalsIgnoreCase(request.taskType.trim())) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "taskType must be cruise");
            }

            NanoHTTPD.Response busyResponse = rejectIfTaskBusy(request);
            if (busyResponse != null) {
                return busyResponse;
            }

            if (request.taskId == null || request.taskId.trim().isEmpty()) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Missing required field: taskId");
            }

            if (request.taskName == null || request.taskName.trim().isEmpty()) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Missing required field: taskName");
            }

            String taskName = request.taskName.trim();
            CruiseTask cruiseTask = findCruiseTaskByName(taskName);
            if (cruiseTask == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                        "Cruise task not found: " + taskName);
            }

            String validationError = validateCruiseTaskPoints(cruiseTask);
            if (validationError != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST, validationError);
            }

            markGlobalTaskExecuting(request.taskType, taskName, request.taskId);
            taskExecutor.executeByTaskName(cruiseTask.getName(), request.taskId, request.taskName, createTaskCallback(request));

            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "createCruiseTask");
            jsonResponse.put("taskId", request.taskId.trim());
            jsonResponse.put("taskName", taskName);
            jsonResponse.put("message", "Cruise task accepted and processing");
            jsonResponse.put("timestamp", System.currentTimeMillis());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error creating cruise task", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error creating cruise task: " + e.getMessage());
        }
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

    private NanoHTTPD.Response handleCreateJackTask(TaskRequest request) {
        try {
            if (request == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid request body");
            }

            if (request.taskType == null || !"jack".equalsIgnoreCase(request.taskType.trim())) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "taskType must be jack");
            }

            NanoHTTPD.Response busyResponse = rejectIfTaskBusy(request);
            if (busyResponse != null) {
                return busyResponse;
            }

            if (request.taskId == null || request.taskId.trim().isEmpty()) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Missing required field: taskId");
            }

            if (request.taskName == null || request.taskName.trim().isEmpty()) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Missing required field: taskName");
            }

            String taskName = request.taskName.trim();
            JackTask jackTask = findJackTaskByName(taskName);
            if (jackTask == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                        "Jack task not found: " + taskName);
            }

            String validationError = validateJackTaskPoints(jackTask);
            if (validationError != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST, validationError);
            }

            markGlobalTaskExecuting(request.taskType, taskName, request.taskId);
            taskExecutor.executeByTaskName(jackTask.getTaskName(), request.taskId, request.taskName, createTaskCallback(request));

            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "createJackTask");
            jsonResponse.put("taskId", request.taskId.trim());
            jsonResponse.put("taskName", taskName);
            jsonResponse.put("message", "Jack task accepted and processing");
            jsonResponse.put("timestamp", System.currentTimeMillis());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error creating jack task", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error creating jack task: " + e.getMessage());
        }
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
            return null;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        Building currentBuilding = mapPoints != null
                ? mapPoints.getBuilding(mapViewModel.getCurrentBuilding())
                : null;
        if (currentBuilding == null) {
            return null;
        }

        FloorPoints floorPoints = currentBuilding.getFloorPoints(floor);
        if (floorPoints == null) {
            return null;
        }

        List<Position> workPoints = floorPoints.getWorkPoints();
        if (workPoints == null) {
            return null;
        }

        for (Position position : workPoints) {
            if (position != null && position.getId() == pointId) {
                return position;
            }
        }
        return null;
    }

    private NanoHTTPD.Response processTaskRequest(TaskRequest request) {
        Log.d(TAG, "Processing task request: " + request.taskType + ", ID: " + request.id);
        BroadcastHelper broadcastHelper = MyApplication.getBroadcastHelper();

        // For simple tasks that don't require position lookup, we can process immediately
        switch (request.taskType.toLowerCase()) {
            case "cancel":
            case "pause":
            case "resume":
            case "release":
            case "areaset":
                // Execute simple tasks immediately on main thread
                mainHandler.post(() -> {
                    try {
                        switch (request.taskType.toLowerCase()) {
                            case "cancel":
                                markGlobalTaskIdle();
                                broadcastHelper.sendCancelCommand();
                                break;
                            case "pause":
                                Log.i(TAG, "rcs pause " + request.params);
                                broadcastHelper.sendPauseCommand();
                                break;
                            case "resume":
                                Log.i(TAG, "rcs resume " + request.params);
                                broadcastHelper.sendResumeCommand();
                                break;
                            case "release":
                                Log.i(TAG, "rcs release " + request.params);
                                broadcastHelper.sendReleaseCommand();
                                break;
                            case "areaset":
                                Log.i(TAG, "rcs set area " + request.params);
                                commandClient.setArea(request.params);
                                break;
                        }
                        Log.i(TAG, "Simple task executed: " + request.taskType);
                    } catch (Exception e) {
                        Log.e(TAG, "Error executing simple task: " + request.taskType, e);
                    }
                });
                return createSuccessResponse("Task accepted and processing");
        }

        NanoHTTPD.Response busyResponse = rejectIfTaskBusy(request);
        if (busyResponse != null) {
            return busyResponse;
        }

        AtomicReference<List<Position>> taskObject = new AtomicReference<>();

        if ("park".equalsIgnoreCase(request.taskType) || "charge".equalsIgnoreCase(request.taskType)) {
            List<Position> mainTaskObject = createMainTaskObject(request);
            if (mainTaskObject == null || mainTaskObject.isEmpty()) {
                int currentFloor = mapViewModel != null ? mapViewModel.getCurrentFloor() : 0;
                Log.e(TAG, "Position not found for main task: " + request.taskType + ", currentFloor: " + currentFloor);
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Position not found for " + request.taskType + " on current floor: " + currentFloor);
            }

            mainHandler.post(() -> {
                try {
                    markGlobalTaskExecuting(request.taskType,
                            resolveTaskName(request, mainTaskObject),
                            request.taskId);
                    taskExecutor.executeByTaskObject(request.taskType, mainTaskObject, createTaskCallback(request));
                    Log.i(TAG, "Task executed successfully: " + request.taskType + " - " + request.action);
                } catch (Exception e) {
                    markGlobalTaskIdle();
                    Log.e(TAG, "Error executing task: " + request.taskType, e);
                }
            });
            return createSuccessResponse("Task accepted and processing");
        }

        // for regular task, e.g., delivery, cruise, jack
        if (checkWorkType(request.taskType.toLowerCase())) {
            if ("delivery".equalsIgnoreCase(request.taskType)) {
                NanoHTTPD.Response mapValidationResponse = validateDeliveryMapPrefix(request);
                if (mapValidationResponse != null) {
                    return mapValidationResponse;
                }

                List<Position> deliveryTaskObject = createDeliveryTaskObject(request);
                if (deliveryTaskObject == null || deliveryTaskObject.isEmpty()) {
                    Log.e(TAG, "Position not found for station: " + request.station + ", task type: " + request.taskType);
                    return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                            "Position not found for station: " + request.station);
                }
                taskObject.set(deliveryTaskObject);
            } else {
                Position taskPosition = getPositionByStationAndMap(request.station, request.mapName);
                if (taskPosition == null) {
                    Log.e(TAG, "Position not found for station: " + request.station + ", task type: " + request.taskType);
                    return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                            "Position not found for station: " + request.station);
                }
            }
        }

        mainHandler.post(() -> {
            try {
                switch (request.taskType.toLowerCase()) {
                    case "delivery":
                        if (taskObject.get() == null) {
                            taskObject.set(createDeliveryTaskObject(request));
                        }
                        break;
                    case "cruise":
                        taskObject.set(createCruiseTaskObject(request));
                        break;
                    case "jack":
                        taskObject.set(createJackTaskObject(request));
                        break;
                    default:
                        Log.w(TAG, "Unknown task type: " + request.taskType);
                }

                if (taskObject.get() != null && !taskObject.get().isEmpty()) {
                    markGlobalTaskExecuting(request.taskType,
                            resolveTaskName(request, taskObject.get()),
                            request.taskId);
                    taskExecutor.executeByTaskObject(request.taskType, taskObject.get(), createTaskCallback(request));
                    Log.i(TAG, "Task executed successfully: " + request.taskType + " - " + request.action);
                } else {
                    Log.e(TAG, "Failed to create task object for: " + request.taskType);
                    TaskExecutor.TaskExecutionCallback callback = createTaskCallback(request);
                    callback.onTaskFailed(request.taskType, request.station, "Failed to create task object");
                }
            } catch (Exception e) {
                markGlobalTaskIdle();
                Log.e(TAG, "Error executing task: " + request.taskType, e);
            }
        });

        return createSuccessResponse("Task accepted and processing");
    }

    private NanoHTTPD.Response fetchAllMapPointsObjectResponse() {
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Command WebSocket not connected");
            }

            // Use CountDownLatch to wait for async response
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

            // Wait for response with timeout
            boolean completed = latch.await(15, TimeUnit.SECONDS);

            if (!completed) {
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT, "Timeout waiting for points data");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Error fetching points: " + errorHolder[0]);
            }

            // Convert MarkerData to JSON response
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "points");
            jsonResponse.put("data", convertMarkerDataToJson(resultHolder[0]));
            jsonResponse.put("timestamp", System.currentTimeMillis());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching points", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Error fetching points: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response fetchCurrentMapInfoObjectResponse() {
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Command WebSocket not connected");
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
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT,
                        "Timeout waiting for current map info");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "Error fetching current map info: " + errorHolder[0]);
            }

            if (mapNameHolder[0] == null || mapNameHolder[0].trim().isEmpty()) {
                return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "Current map name is empty");
            }

            if (mapImageHolder[0] == null || mapImageHolder[0].isEmpty()) {
                return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "Current map image data is empty");
            }

            // Build JSON response with all map information
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "currentMapInfo");
            jsonResponse.put("timestamp", System.currentTimeMillis());

            JSONObject dataObject = new JSONObject();
            if (mapNameHolder[0] != null) {
                dataObject.put("name", mapNameHolder[0]);
            }
            if (resolutionHolder[0] != null) {
                dataObject.put("resolution", resolutionHolder[0]);
            }
            if (widthHolder[0] != null) {
                dataObject.put("width", widthHolder[0]);
            }
            if (heightHolder[0] != null) {
                dataObject.put("height", heightHolder[0]);
            }

            // Add map position array
            if (mapPositionHolder[0] != null) {
                JSONArray posArray = new JSONArray();
                for (double pos : mapPositionHolder[0]) {
                    posArray.put(pos);
                }
                dataObject.put("mappos", posArray);
            }

            if (formatHolder[0] != null) {
                dataObject.put("format", formatHolder[0]);
            }
            if (timeHolder[0] != null) {
                dataObject.put("time", timeHolder[0]);
            }
            dataObject.put("map", mapImageHolder[0]);

            jsonResponse.put("data", dataObject);
            Log.d(TAG, "Current map image loaded: name=" + mapNameHolder[0]
                    + ", base64Length=" + mapImageHolder[0].length());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching current map info", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error fetching current map info: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response fetchSafetyAreasObjectResponse() {
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Command WebSocket not connected");
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
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT,
                        "Timeout waiting for safety areas data");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "Error fetching safety areas: " + errorHolder[0]);
            }

            // Build JSON response
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "safetyAreas");
            jsonResponse.put("timestamp", System.currentTimeMillis());
            jsonResponse.put("data", convertSafetyAreasDataToJson(resultHolder[0]));

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching safety areas", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error fetching safety areas: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response fetchNavigatePathObjectResponse() {
        try {
            if (commandClient == null || !commandClient.isConnected()) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Command WebSocket not connected");
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
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT, "Timeout waiting for path data");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Error fetching path: " + errorHolder[0]);
            }

            // Get current map name from status data
            String mapName = "";
            if (statusClient != null) {
                AgvStatusResponse statusResponse = statusClient.getLastStatusResponse();
                if (statusResponse != null && statusResponse.data != null && statusResponse.data.pos != null) {
                    mapName = statusResponse.data.pos.mapName != null ? statusResponse.data.pos.mapName : "";
                }
            }

            // Build response: only mapName and line (x, y only)
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("mapName", mapName);

            if (resultHolder[0] != null && resultHolder[0].getLine() != null) {
                JSONArray lineArray = new JSONArray();
                for (Position position : resultHolder[0].getLine()) {
                    JSONObject pointJson = new JSONObject();
                    pointJson.put("x", position.getPosX());
                    pointJson.put("y", position.getPosY());
                    pointJson.put("speed", position.getSpeed());
                    lineArray.put(pointJson);
                }
                jsonResponse.put("line", lineArray);
            }

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching path", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Error fetching path: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response fetchRcsTaskStatusObjectResponse(String taskId) {
        try {
            if (taskViewModel == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Task ViewModel not available");
            }

            // Use CountDownLatch to wait for async LiveData response
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] taskStatus = new String[1];
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                try {
                    // Get the current value from LiveData
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();

                    TaskViewModel.TaskState currentTaskState = taskViewModel.getCurrentTaskStateSnapshot();
                    if (currentTaskState != null
                            && taskId.equals(currentTaskState.taskId)
                            && !TaskViewModel.TASK_INFO_NONE.equals(currentTaskState.status)) {
                        taskStatus[0] = currentTaskState.status;
                    }

                    // Look for taskRecord with matching external taskId, keep old RCS-name fallback.
                    if (taskRecords != null && !taskRecords.isEmpty()) {
                        for (TaskRecord taskRecord : taskRecords) {
                            String recordTaskId = taskRecord.getTaskId();
                            String recordTaskName = taskRecord.getTaskName();
                            if ((recordTaskId != null && recordTaskId.trim().equals(taskId))
                                    || (recordTaskName != null && recordTaskName.equals("RCS" + "-" + taskId))) {
                                taskStatus[0] = taskRecord.getStatus();
                                break;
                            }
                        }
                    }

                    if (taskStatus[0] == null) {
                        errorHolder[0] = "Task with ID '" + taskId + "' not found";
                    }

                    latch.countDown();

                } catch (Exception e) {
                    errorHolder[0] = "Error searching for task: " + e.getMessage();
                    latch.countDown();
                }
            });

            // Wait for response with timeout
            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (!completed) {
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT,
                        "Timeout searching for task");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                        errorHolder[0]);
            }

            // Create JSON response
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "rcsTaskStatus");
            jsonResponse.put("timestamp", System.currentTimeMillis());

            // Create data object with taskId as key and status as value
            JSONObject dataObject = new JSONObject();
            dataObject.put(taskId, taskStatus[0]);
            jsonResponse.put("data", dataObject);

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching RCS task status for taskId: " + taskId, e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error fetching task status: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response fetchHmiTaskStatusObjectResponse(String dateTime) {
        try {
            if (taskViewModel == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Task ViewModel not available");
            }

            // Parse the dateTime parameter (expected format: "yyyy-MM-dd")
            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            final Date targetDate;

            try {
                targetDate = dateFormat.parse(dateTime);
            } catch (ParseException e) {
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid date format. Expected: yyyy-MM-dd");
            }

            // Use CountDownLatch to wait for async LiveData response
            final CountDownLatch latch = new CountDownLatch(1);
            final List<TaskRecord> matchingTasks = new ArrayList<>();
            final String[] errorHolder = new String[1];

            mainHandler.post(() -> {
                try {
                    // Get the current value from LiveData
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();

                    if (taskRecords == null || taskRecords.isEmpty()) {
                        errorHolder[0] = "No task records found";
                        latch.countDown();
                        return;
                    }

                    // Create calendar instances for date comparison
                    Calendar targetCalendar = Calendar.getInstance();
                    targetCalendar.setTime(targetDate);

                    Calendar taskCalendar = Calendar.getInstance();

                    // Filter tasks that started on the same date AND contain "HMI" in task name
                    for (TaskRecord taskRecord : taskRecords) {
                        // Skip tasks that haven't started yet
                        if (taskRecord.getStartTime() == 0) {
                            continue;
                        }

                        // Check if task name contains "HMI" (case-insensitive)
                        String taskName = taskRecord.getTaskName();
                        if (taskName == null || !taskName.toUpperCase().contains("HMI")) {
                            continue; // Skip tasks without HMI in name
                        }

                        // Set calendar to task's start time
                        taskCalendar.setTimeInMillis(taskRecord.getStartTime());

                        // Compare year, month, and day
                        if (taskCalendar.get(Calendar.YEAR) == targetCalendar.get(Calendar.YEAR) &&
                                taskCalendar.get(Calendar.MONTH) == targetCalendar.get(Calendar.MONTH) &&
                                taskCalendar.get(Calendar.DAY_OF_MONTH) == targetCalendar.get(Calendar.DAY_OF_MONTH)) {
                            matchingTasks.add(taskRecord);
                        }
                    }

                    if (matchingTasks.isEmpty()) {
                        errorHolder[0] = "No HMI tasks found for date: " + dateTime;
                    }

                    latch.countDown();

                } catch (Exception e) {
                    errorHolder[0] = "Error searching for tasks: " + e.getMessage();
                    latch.countDown();
                }
            });

            // Wait for response with timeout
            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (!completed) {
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT,
                        "Timeout searching for tasks");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                        errorHolder[0]);
            }

            // Create JSON response
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "hmiTaskStatus");
            jsonResponse.put("timestamp", System.currentTimeMillis());
            jsonResponse.put("date", dateTime);
            jsonResponse.put("count", matchingTasks.size());

            // Create data object with task names as keys and status as values
            JSONObject dataObject = new JSONObject();

            for (TaskRecord task : matchingTasks) {
                // If you want just the status as value
                // dataObject.put(task.getTaskName(), task.getStatus());

                // If you want full task details as value
                JSONObject taskDetails = new JSONObject();
                taskDetails.put("status", task.getStatus());
                taskDetails.put("type", task.getType());
                taskDetails.put("startTime", task.getStartTime());
                taskDetails.put("endTime", task.getEndTime());
                taskDetails.put("duration", task.getDuration());

                dataObject.put(task.getTaskName(), taskDetails);
            }

            jsonResponse.put("data", dataObject);

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching HMI task status for date: " + dateTime, e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error fetching tasks: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response fetchHmiTaskStatusDetailsObjectResponse(String taskId, String dateTime,
                                                                        String startTime, String endTime) {
        try {
            if (taskViewModel == null) {
                return createErrorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "Task ViewModel not available");
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
                        } catch (ParseException e) {
                            errorHolder[0] = "Invalid dateTime format. Expected: yyyy-MM-dd";
                            latch.countDown();
                            return;
                        }
                    }

                    for (TaskRecord taskRecord : taskRecords) {
                        if (hasTaskIdFilter) {
                            String recordTaskId = taskRecord.getTaskId();
                            if (recordTaskId == null || !recordTaskId.equals(taskId)) {
                                continue;
                            }
                        }

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

                    TaskRecord selectedTask = matchingTasks.get(0);
                    for (int i = 1; i < matchingTasks.size(); i++) {
                        if (matchingTasks.get(i).getCreateTime() > selectedTask.getCreateTime()) {
                            selectedTask = matchingTasks.get(i);
                        }
                    }

                    JSONObject data = new JSONObject();
                    data.put("requestId", "");
                    data.put("type", "hmiTaskStatusDetails");
                    data.put("date", selectedTask.getCreateTime() > 0
                            ? dateFormat.format(new Date(selectedTask.getCreateTime())) : "");

                    // Get station details and loop count based on task type
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
                            data.put("stationCount", operations != null ? operations.size() : 0);

                            if (operations != null && !operations.isEmpty()) {
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                for (int i = 0; i < operations.size(); i++) {
                                    JackOperation op = operations.get(i);
                                    String pointName = op.getPointName();
                                    String action = getJackActionDescription(op.getActionType());
                                    int duration = op.getDuration();
                                    stationDetail.put(String.valueOf(i + 1),
                                            pointName + "," + duration + "s," + action);
                                }
                                // Target station: use current task target if running
                                if (isRunning && sharedViewModel != null && sharedViewModel.getNavigationHandler() != null) {
                                    Position currentTarget = sharedViewModel.getNavigationHandler().getCurrentTaskTarget();
                                    if (currentTarget != null) {
                                        stationDetail.put("targetStation", currentTarget.getName());
                                    }
                                } else {
                                    // For completed tasks, use last operation point
                                    if (operations.size() > 0) {
                                        stationDetail.put("targetStation", operations.get(operations.size() - 1).getPointName());
                                    }
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
                            data.put("stationCount", stationIds != null ? stationIds.size() : 0);

                            if (stationIds != null && !stationIds.isEmpty()) {
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                for (int i = 0; i < stationIds.size(); i++) {
                                    String stationId = stationIds.get(i);
                                    stationDetail.put(String.valueOf(i + 1),
                                            stationId + "," + stayTime + "s,move");
                                }
                                // Target station: use current task target if running
                                if (isRunning && sharedViewModel != null && sharedViewModel.getNavigationHandler() != null) {
                                    Position currentTarget = sharedViewModel.getNavigationHandler().getCurrentTaskTarget();
                                    if (currentTarget != null) {
                                        stationDetail.put("targetStation", currentTarget.getName());
                                    }
                                } else {
                                    // For completed tasks, use last station
                                    if (stationIds.size() > 0) {
                                        stationDetail.put("targetStation", stationIds.get(stationIds.size() - 1));
                                    }
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
                                data.put("stationCount", routePositions.size());
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                for (int i = 0; i < routePositions.size(); i++) {
                                    Position pos = routePositions.get(i);
                                    String pointName = pos.getName();
                                    int stayDuration = pos.getStayDuration() / 1000;
                                    stationDetail.put(String.valueOf(i + 1),
                                            pointName + "," + stayDuration + "s,move");
                                }
                                // Use current task target (the point robot is going to)
                                if (currentTarget != null) {
                                    stationDetail.put("targetStation", currentTarget.getName());
                                }
                                stationDetailsArray.put(stationDetail);
                            } else {
                                data.put("stationCount", 0);
                            }
                        } else {
                            // Use recorded station details
                            data.put("stationCount", recordedDetails != null ? recordedDetails.size() : 0);

                            if (recordedDetails != null && !recordedDetails.isEmpty()) {
                                JSONObject stationDetail = new JSONObject();
                                stationDetail.put("currentLoop", currentLoopIndex);
                                for (int i = 0; i < recordedDetails.size(); i++) {
                                    TaskRecord.StationDetail detail = recordedDetails.get(i);
                                    String stationName = detail.getStationName();
                                    long stayDuration = 0;
                                    if (detail.getArrivalTime() > 0 && detail.getDepartureTime() > 0) {
                                        stayDuration = (detail.getDepartureTime() - detail.getArrivalTime()) / 1000;
                                    }
                                    String action = "move";
                                    stationDetail.put(String.valueOf(i + 1),
                                            stationName + "," + stayDuration + "s," + action);
                                }
                                if (recordedDetails.size() > 0) {
                                    stationDetail.put("targetStation", recordedDetails.get(recordedDetails.size() - 1).getStationName());
                                }
                                stationDetailsArray.put(stationDetail);
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
                return createErrorResponse(NanoHTTPD.Response.Status.REQUEST_TIMEOUT,
                        "Timeout fetching HMI task status details");
            }

            if (errorHolder[0] != null) {
                return createErrorResponse(NanoHTTPD.Response.Status.NOT_FOUND, errorHolder[0]);
            }

            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("type", "hmiTaskStatusDetails");
            jsonResponse.put("data", resultData[0] != null ? resultData[0] : new JSONObject());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching HMI task status details", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Error fetching tasks: " + e.getMessage());
        }
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
        } catch (ParseException e) {
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
        }
        return dataJson;
    }

    private JSONObject convertPathDataToJson(NavigationPathData pathData) throws JSONException {
        JSONObject dataJson = new JSONObject();
        if (pathData != null) {
            dataJson.put("talk", pathData.getTalk());
            dataJson.put("time", pathData.getTime());

            if (pathData.getLine() != null) {
                JSONArray lineArray = new JSONArray();
                for (Position position : pathData.getLine()) {
                    JSONObject pointJson = new JSONObject();
                    pointJson.put("x", position.getPosX());
                    pointJson.put("y", position.getPosY());
                    pointJson.put("id", position.getId());
                    pointJson.put("name", position.getName());
                    lineArray.put(pointJson);
                }
                dataJson.put("line", lineArray);
            }
        }
        return dataJson;
    }

    /**
     * Simplified: Convert SafetyAreasData to JSON format
     * Returns only the data array with all 6 types (0-5)
     */
    private JSONArray convertSafetyAreasDataToJson(SafetyAreasData safetyAreasData) throws JSONException {
        JSONArray allTypesArray = new JSONArray();

        // Create entries for all 6 types (0-5)
        for (int typeId = 0; typeId <= 5; typeId++) {
            JSONObject typeEntry = new JSONObject();
            typeEntry.put("id", typeId);

            // Find SafetyArea for this type
            SafetyArea area = findSafetyAreaById(safetyAreasData, typeId);

            if (area != null && area.getPolygons() != null && !area.getPolygons().isEmpty()) {
                // This type has polygons
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

    /**
     * Helper method to find SafetyArea by id
     */
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

    private NanoHTTPD.Response createCorsResponse() {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "");
        addCorsHeaders(response);
        return response;
    }

    private NanoHTTPD.Response createSuccessResponse(String message) {
        try {
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "200");
            jsonResponse.put("message", message);
            jsonResponse.put("timestamp", System.currentTimeMillis());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (JSONException e) {
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Error creating response");
        }
    }

    private NanoHTTPD.Response createErrorResponse(NanoHTTPD.Response.Status status, String message) {
        try {
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "400");
            jsonResponse.put("message", message);
            jsonResponse.put("timestamp", System.currentTimeMillis());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    status,
                    "application/json; charset=UTF-8",
                    jsonResponse.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (JSONException e) {
            return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=UTF-8", message);
        }
    }

    private void addCorsHeaders(NanoHTTPD.Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        response.addHeader("Access-Control-Max-Age", "86400");
    }

    // Getters for service information
    public int getPort() {
        return port;
    }

    public String getBindIp() {
        return bindIp;
    }

    public boolean isRunning() {
        return isServerStarted && httpServer != null;
    }

    private NanoHTTPD.Response validateDeliveryMapPrefix(TaskRequest request) {
        if (request == null || request.mapName == null || request.mapName.trim().isEmpty()) {
            Log.w(TAG, "Delivery task rejected: mapName is empty");
            return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Current map unreachable, please check destination map");
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
            return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Current map unreachable, please check destination map");
        }

        List<String> mapNames = splitCsv(request.mapName);
        if (mapNames.isEmpty()) {
            Log.w(TAG, "Delivery task rejected: no destination map names, requestMap=" + request.mapName);
            return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "Current map unreachable, please check destination map");
        }

        for (String destinationMapName : mapNames) {
            String destinationPrefix = extractReachabilityMapPrefix(destinationMapName);
            if (destinationPrefix.isEmpty() || !currentPrefix.equals(destinationPrefix)) {
                Log.w(TAG, "Delivery task rejected: map prefix mismatch, currentMap="
                        + currentMapName + ", currentPrefix=" + currentPrefix
                        + ", destinationMap=" + destinationMapName
                        + ", destinationPrefix=" + destinationPrefix);
                return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Current map unreachable, please check destination map");
            }
        }

        List<String> stations = splitCsv(request.station);
        String mapPointValidationError = validateDeliveryMapsAndStations(stations, mapNames);
        if (mapPointValidationError != null) {
            return createErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST, mapPointValidationError);
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

    private List<Position> createDeliveryTaskObject(TaskRequest request) {
        // For delivery tasks, the task object is the station ID (Integer)
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
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid station ID for delivery task: " + request.station);
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
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid station ID for cruise task: " + request.station);
        }
        return null;
    }

    private List<Position> createJackTaskObject(TaskRequest request) {
        try {
            if (request.station != null && !request.station.isEmpty()) {
                List<Position> taskObject = new ArrayList<>();
                Position taskPosition = getPositionByStationAndMap(request.station, request.mapName);
                String positionName = taskPosition.getName() != null ? taskPosition.getName() : "null";
                taskPosition.setMessage(getString(R.string.navigating_to_task_point, positionName));
                taskPosition.setTaskType(3);
                taskPosition.setTaskId(request.taskId);

                // 3. Update type based on actionType
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
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid station ID for cruise task: " + request.station);
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
            if (request.taskType.equals("park")) {
                Position parkPosition = mapViewModel.getNearestParkPoint(buildingId, currentFloor);
                if (parkPosition != null) {
                    taskPosition = parkPosition;
                    taskPosition.setTaskType(5);
                    taskPosition.setType(12);
                    taskPosition.setTaskId(resolveTaskId(request));
                    taskPosition.setMessage(getString(R.string.navigating_to_task_point,
                            taskPosition.getName() != null ? taskPosition.getName() : "null"));
                }
            } else {
                Position chargePosition = mapViewModel.getNearestChargePoint(buildingId, currentFloor);
                if (chargePosition != null) {
                    taskPosition = chargePosition;
                    taskPosition.setTaskType(4);
                    taskPosition.setType(10);
                    taskPosition.setTaskId(resolveTaskId(request));
                    taskPosition.setMessage(getString(R.string.navigating_to_task_point,
                            taskPosition.getName() != null ? taskPosition.getName() : "null"));
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

    private TaskExecutor.TaskExecutionCallback createTaskCallback(TaskRequest request) {
        return new TaskExecutor.TaskExecutionCallback() {
            @Override
            public void onTaskMatched(String taskType, String taskName) {
                Log.d(TAG, "Task matched: " + taskType + " - " + taskName);
            }

            @Override
            public void onTaskStarted(String taskType, String taskName) {
                TaskViewModel.TaskState taskState = taskViewModel != null
                        ? taskViewModel.getCurrentTaskStateSnapshot()
                        : null;
                if (taskState == null
                        || TaskViewModel.TASK_INFO_NONE.equals(taskState.taskType)
                        || TaskViewModel.TASK_INFO_NONE.equals(taskState.taskName)
                        || TaskViewModel.TASK_INFO_NONE.equals(taskState.taskId)) {
                    markGlobalTaskExecuting(request.taskType,
                            firstNonEmpty(request.taskName, taskName, request.station),
                            request.taskId);
                }
                Log.i(TAG, "Task started: " + taskType + " - " + taskName);
            }

            @Override
            public void onTaskCompleted(String taskType, String taskName) {
                markGlobalTaskCompleted();
                Log.i(TAG, "Task completed: " + taskType + " - " + taskName);

                // Handle wait time if specified
                if (request.waitTime > 0) {
                    Log.d(TAG, "Waiting " + request.waitTime + "ms before completing");
                    mainHandler.postDelayed(() -> {
                        Log.i(TAG, "Wait time completed for task: " + request.id);
                    }, request.waitTime);
                }
            }

            @Override
            public void onTaskFailed(String taskType, String taskName, String reason) {
                markGlobalTaskIdle();
                Log.e(TAG, "Task failed: " + taskType + " - " + taskName + ", reason: " + reason);
            }
        };
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

    /**
     * 通过名称获取位置信息
     */
    private Position getPositionByStationAndMap(String station, String mapName) {
        if (station == null || station.trim().isEmpty() || mapViewModel == null) {
            return null;
        }

        String stationName = station.trim();
        int floor = extractMapFloor(mapName);
        String buildingId = MultiBuildingMapPoints.extractBuildingId(mapName);
        Position exactMapPosition = findPositionInFloor(findFloorPointsByMapName(mapName), stationName);
        if (exactMapPosition != null) {
            return exactMapPosition;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            Log.e(TAG, "map points are null");
            return null;
        }

        Building targetBuilding = mapPoints.getBuilding(buildingId);
        Position position = findPositionInBuildingFloor(targetBuilding, floor, stationName);
        if (position != null) {
            return position;
        }

        for (Building building : mapPoints.getAllBuildings().values()) {
            if (building == targetBuilding) {
                continue;
            }
            position = findPositionInBuildingFloor(building, floor, stationName);
            if (position != null) {
                Log.w(TAG, "Position found on same floor but different building, station="
                        + stationName + ", requestMap=" + mapName + ", requestedBuilding=" + buildingId
                        + ", actualBuilding=" + building.getBuildingId());
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
                    Log.w(TAG, "Position found on fallback full-map search, station="
                            + stationName + ", requestMap=" + mapName + ", actualMap=" + position.getMapName());
                    return position;
                }
            }
        }

        Log.e(TAG, "Position not found, station=" + stationName + ", mapName=" + mapName
                + ", buildingId=" + buildingId + ", floor=" + floor);
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

    // ==================== Config Management Endpoints ====================

    /**
     * GET /config - Get current config status
     * Returns: { autoLoadConfigOnStartup: bool, configFilePath: string }
     */
    private NanoHTTPD.Response handleGetConfig() {
        try {
            com.ezhan.amr.utils.AppSettingsConfig cfg = com.ezhan.amr.utils.AppSettingsConfig.getInstance();
            cfg.loadConfig(MyApplication.getInstance());

            JSONObject result = new JSONObject();
            result.put("autoLoadConfigOnStartup", cfg.isAutoLoadConfigOnStartup());
            result.put("configFilePath", cfg.getConfigFilePath());

            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    result.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get config status", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Failed to get config status: " + e.getMessage());
        }
    }

    /**
     * POST /config/load - Load config from file and apply to ViewModels
     */
    private NanoHTTPD.Response handleConfigLoad() {
        try {
            com.ezhan.amr.utils.AppSettingsConfig cfg = com.ezhan.amr.utils.AppSettingsConfig.getInstance();
            cfg.loadConfig(MyApplication.getInstance(), true); // Force reload

            // Apply config to ViewModels via MyApplication
            MyApplication.getInstance().applyConfigFromFile(cfg);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Config loaded and applied successfully");

            Log.i(TAG, "Config loaded from file and applied to ViewModels");
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    result.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load config", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Failed to load config: " + e.getMessage());
        }
    }

    /**
     * POST /config/save - Save current ViewModel state to config file
     */
    private NanoHTTPD.Response handleConfigSave() {
        try {
            com.ezhan.amr.utils.AppSettingsConfig cfg = com.ezhan.amr.utils.AppSettingsConfig.getInstance();
            cfg.loadConfig(MyApplication.getInstance());

            // Save current ViewModel state to config (same logic as MainActivity.saveConfigFromDialog)
            // Basic settings
            com.ezhan.amr.viewmodels.BasicViewModel bvm = MyApplication.getInstance().getBasicViewModel();
            if (bvm != null) {
                cfg.setVolumeLevel(bvm.getVolumeLevel().getValue() != null ? bvm.getVolumeLevel().getValue() : 6);
                cfg.setSpeed(bvm.getSpeed().getValue() != null ? bvm.getSpeed().getValue() : 0.4);
                cfg.setDeliveryWaitDuration(bvm.getDeliveryWaitDuration().getValue() != null ? bvm.getDeliveryWaitDuration().getValue() : 30);
                cfg.setQueuedTaskCooldown(bvm.getQueuedTaskCooldown().getValue() != null ? bvm.getQueuedTaskCooldown().getValue() : 6);
                cfg.setLowPower(bvm.getLowPower().getValue() != null ? bvm.getLowPower().getValue() : 20);
                cfg.setRecognizeDistance(bvm.getRecognizeDistance().getValue() != null ? bvm.getRecognizeDistance().getValue() : 1.0);
                cfg.setObstacleTime(bvm.getVirtualOrbitObstacleTime().getValue() != null ? bvm.getVirtualOrbitObstacleTime().getValue() : 0);
                cfg.setAutoChargeEnabled(bvm.getIsAutoCharge().getValue() != null ? bvm.getIsAutoCharge().getValue() : false);
                cfg.setIdleChargeEnabled(bvm.getIsIdleCharge().getValue() != null ? bvm.getIsIdleCharge().getValue() : true);
                cfg.setAutoChargeStartMinute(bvm.getAutoChargeStartMinute().getValue() != null ? bvm.getAutoChargeStartMinute().getValue() : 0);
                cfg.setAutoChargeEndMinute(bvm.getAutoChargeEndMinute().getValue() != null ? bvm.getAutoChargeEndMinute().getValue() : 0);
                cfg.setIdleChargeWaitMinutes(bvm.getIdleChargeWaitMinutes().getValue() != null ? bvm.getIdleChargeWaitMinutes().getValue() : 10);
                cfg.setVirtualOrbitMode(bvm.getIsVirtualOrbitMode().getValue() != null ? bvm.getIsVirtualOrbitMode().getValue() : true);
                cfg.setVirtualOrbitOneWay(bvm.getIsVirtualOrbitOneWayMode().getValue() != null ? bvm.getIsVirtualOrbitOneWayMode().getValue() : false);
                cfg.setRobotId(bvm.getRobotId().getValue() != null ? bvm.getRobotId().getValue() : 1);
                cfg.setLoraChannel(bvm.getLoraChannel().getValue() != null ? bvm.getLoraChannel().getValue() : 0);
                cfg.setLoraAddress(bvm.getLoraAddress().getValue() != null ? bvm.getLoraAddress().getValue() : 0);
                cfg.setRunningMusic(bvm.getRunningMusic().getValue() != null ? bvm.getRunningMusic().getValue() : "wa");
                cfg.setUsePassword(bvm.getIsUsePassword().getValue() != null ? bvm.getIsUsePassword().getValue() : false);
                cfg.setPassword(bvm.getPassword().getValue() != null ? bvm.getPassword().getValue() : "0000");
                cfg.setVisualCruiseEnabled(bvm.getIsVisualCruise().getValue() != null ? bvm.getIsVisualCruise().getValue() : false);
            }

            // Elevator configs
            try {
                com.ezhan.amr.viewmodels.ElevatorViewModel evm = MyApplication.getInstance().getElevatorViewModel();
                java.util.List<com.ezhan.amr.viewmodels.ElevatorViewModel.ElevatorConfig> elevatorConfigs =
                        evm.elevatorConfigs.getValue();
                if (elevatorConfigs != null) {
                    cfg.setElevatorConfigsJson(new com.google.gson.Gson().toJson(elevatorConfigs,
                            com.ezhan.amr.data.datastore.DataStoreKeys.ELEVATOR_CONFIGS_TYPE));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save elevator configs", e);
            }

            // Call box configs
            try {
                com.ezhan.amr.viewmodels.CallboxViewModel cvm = MyApplication.getInstance().getCallboxViewModel();
                java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox> callBoxes =
                        cvm.getCallBoxes().getValue();
                if (callBoxes != null) {
                    cfg.setCallBoxConfigsJson(new com.google.gson.Gson().toJson(callBoxes,
                            new com.google.gson.reflect.TypeToken<java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox>>() {}.getType()));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save call box configs", e);
            }

            // Map area configs
            try {
                String mapAreaJson = com.ezhan.amr.data.datastore.DataStoreManager.getInstance(MyApplication.getInstance())
                        .getString(com.ezhan.amr.data.datastore.DataStoreKeys.MAP_AREAS, "[]")
                        .blockingFirst("[]");
                if (mapAreaJson != null && !mapAreaJson.equals("[]")) {
                    cfg.setMapAreaConfigsJson(mapAreaJson);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save map area configs", e);
            }

            // Position map
            try {
                com.ezhan.amr.viewmodels.CruiseViewModel cruiseVm = MyApplication.getInstance().getCruiseViewModel();
                java.util.Map<String, com.ezhan.amr.data.datatype.Position> positionMap =
                        cruiseVm.getPositionMap().getValue();
                if (positionMap != null) {
                    cfg.setPositionMapJson(new com.google.gson.Gson().toJson(positionMap,
                            com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP_TYPE));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save position map", e);
            }

            // Cruise task map
            try {
                com.ezhan.amr.viewmodels.CruiseViewModel cruiseVm = MyApplication.getInstance().getCruiseViewModel();
                java.util.Map<Integer, com.ezhan.amr.data.datatype.CruiseTask> cruiseTaskMap =
                        cruiseVm.getCruiseTaskMap().getValue();
                if (cruiseTaskMap != null) {
                    cfg.setCruiseTaskMapJson(new com.google.gson.Gson().toJson(cruiseTaskMap,
                            com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save cruise task map", e);
            }

            // Jack task map
            try {
                com.ezhan.amr.viewmodels.JackViewModel jackVm = MyApplication.getInstance().getJackViewModel();
                java.util.Map<Integer, com.ezhan.amr.data.datatype.JackTask> jackTaskMap =
                        jackVm.getJackTaskMap().getValue();
                if (jackTaskMap != null) {
                    cfg.setJackTaskMapJson(new com.google.gson.Gson().toJson(jackTaskMap,
                            com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save jack task map", e);
            }

            // Current map points (点位设置)
            try {
                com.ezhan.amr.viewmodels.MapViewModel mapVm = MyApplication.getInstance().getMapViewModel();
                com.ezhan.amr.data.datatype.MultiBuildingMapPoints mapPoints =
                        mapVm.getCurrentMapPoints().getValue();
                if (mapPoints != null && !mapPoints.getAllBuildings().isEmpty()) {
                    cfg.setCurrentMapPointsJson(new com.google.gson.Gson().toJson(mapPoints,
                            com.ezhan.amr.data.datastore.DataStoreKeys.MAP_POINTS_TYPE));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to save current map points", e);
            }

            cfg.saveConfig();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Config saved successfully");
            result.put("configFilePath", cfg.getConfigFilePath());

            Log.i(TAG, "Config saved to file: " + cfg.getConfigFilePath());
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    result.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save config", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Failed to save config: " + e.getMessage());
        }
    }

    /**
     * POST /config/autoLoad - Enable or disable auto-load config on startup
     * Body: { "enabled": true/false }
     */
    private NanoHTTPD.Response handleConfigAutoLoad(boolean enabled) {
        try {
            com.ezhan.amr.utils.AppSettingsConfig cfg = com.ezhan.amr.utils.AppSettingsConfig.getInstance();
            cfg.loadConfig(MyApplication.getInstance());
            cfg.setAutoLoadConfigOnStartup(enabled);
            cfg.saveConfig();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("autoLoadConfigOnStartup", enabled);
            result.put("message", "Auto-load config on startup " + (enabled ? "enabled" : "disabled"));

            Log.i(TAG, "Auto-load config on startup set to: " + enabled);
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json; charset=UTF-8",
                    result.toString()
            );
            addCorsHeaders(response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set auto-load config", e);
            return createErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "Failed to set auto-load config: " + e.getMessage());
        }
    }

    private boolean checkWorkType(String taskType) {
        HashSet<String> workTypes = new HashSet<>();
        workTypes.add("delivery");
        workTypes.add("jack");
        workTypes.add("cruise");
        return workTypes.contains(taskType);
    }
}
