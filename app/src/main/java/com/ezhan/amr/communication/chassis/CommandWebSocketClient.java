package com.ezhan.amr.communication.chassis;

import static com.alibaba.dashscope.utils.JsonUtils.gson;

import android.content.Context;
import android.util.Log;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.MapArea;
import com.ezhan.amr.data.datatype.MarkerData;
import com.ezhan.amr.data.datatype.MarkerPoint;
import com.ezhan.amr.data.datatype.NavigationPathData;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.SafetyArea;
import com.ezhan.amr.utils.TaskDebug2;
import com.ezhan.amr.data.datatype.SafetyAreasData;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandWebSocketClient extends BaseWebSocketClient {
    private static final String TAG = "CommandWebSocketClient";
    private static final long COMMAND_TIMEOUT_MS = 10000;
    private static final long JACK_POLL_ACTIVE_MS = 500;
    private static final long JACK_POLL_IDLE_MS = 2000;
    private static final long JACK_STATE_STALE_MS = 5000;
    private static final int JACK_STATE_UNKNOWN = 0;

    // Command-specific interfaces
    public interface Callback {
        void onSuccess(String response);
        void onError(String error);
    }

    public interface MapListCallback {
        void onSuccess(List<String> mapList);
        void onError(String error);
    }

    public interface MarkerDataCallback {
        void onSuccess(MarkerData markerData);
        void onError(String error);
    }


    // Update the callback interface and data class to use Position
    public interface NavigationPathCallback {
        void onSuccess(NavigationPathData pathData);
        void onError(String error);
    }

    public interface CurrentMapInfoCallback {
        void onSuccess(String mapName, double resolution, int width, int height,
                       double[] mapPosition, String format, String time, String mapImage);
        void onError(String error);
    }

    public interface SafetyAreasCallback {
        void onSuccess(SafetyAreasData safetyAreasData);
        void onError(String error);
    }

    /** 从底盘控制器拉取命名区域 (region id=6 中带 name 的条目) 的回调 */
    public interface NamedAreasCallback {
        void onSuccess(List<MapArea> namedAreas);
        void onError(String error);
    }

    public interface MapDataCallback {
        void onSuccess(JSONObject mapData);
        void onError(String error);
    }

    public interface StatusCallback {
        void onSuccess(AgvStatusResponse status);
        void onError(String error);
    }

    public interface JackHeightCallback {
        void onSuccess(float height);
        void onError(String error);
    }

    // Command tracking
    private final Map<String, CommandRequest> pendingCallbacks = new ConcurrentHashMap<>();
    private final Map<String, MapListCallback> pendingMapCallbacks = new ConcurrentHashMap<>();
    private final Map<String, MarkerDataCallback> pendingMarkerCallbacks = new ConcurrentHashMap<>();
    private final Map<String, NavigationPathCallback> pendingNavPathCallbacks = new ConcurrentHashMap<>();
    private final Map<String, CurrentMapInfoCallback> pendingMapInfoCallbacks = new ConcurrentHashMap<>();
    private final Map<String, SafetyAreasCallback> pendingSafetyAreasCallbacks = new ConcurrentHashMap<>();
    private final Map<String, NamedAreasCallback> pendingNamedAreasCallbacks = new ConcurrentHashMap<>();
    private final Map<String, MapDataCallback> pendingMapDataCallbacks = new ConcurrentHashMap<>();
    private final Map<String, StatusCallback> pendingStatusCallbacks = new ConcurrentHashMap<>();
    private volatile float cachedJackHeight = Float.NaN;
    private volatile int cachedJackState = JACK_STATE_UNKNOWN;
    private volatile long lastJackStateUpdateTime;
    private volatile boolean jackHeightRequestInFlight;

    private final Runnable jackHeightPollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected()) {
                return;
            }

            if (!jackHeightRequestInFlight) {
                jackHeightRequestInFlight = true;
                getJackHeightAsync(new JackHeightCallback() {
                    @Override
                    public void onSuccess(float height) {
                        cachedJackHeight = height;
                        cachedJackState = mapJackHeightToState(height);
                        lastJackStateUpdateTime = System.currentTimeMillis();
                        jackHeightRequestInFlight = false;
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Failed to refresh jack height: " + error);
                        jackHeightRequestInFlight = false;
                    }
                });
            }

            mainHandler.postDelayed(this, getJackHeightPollingInterval());
        }
    };

    public CommandWebSocketClient(Context context, String host, int port) {
        super(context, host, port);
    }

    @Override
    protected void handleIncomingMessage(String text) {
        Log.d(TAG, "Command client received: " + text);

        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            String responseCmd = json.get("cmd").getAsString();

            if ("agvStatus_result".equals(responseCmd)) {
                handleStatusResponse(text);
                return;
            }

            handleCommandResponse(responseCmd, text);
        } catch (Exception e) {
            Log.e(TAG, "Error processing command message", e);
        }
    }

    @Override
    protected void onWebSocketConnected() {
        super.onWebSocketConnected();
        Log.d(TAG, "Command client connected and ready for commands");
        startJackHeightPolling();
    }

    @Override
    protected void onWebSocketDisconnected() {
        super.onWebSocketDisconnected();
        Log.d(TAG, "Command client disconnected, clearing pending commands");
        stopJackHeightPolling();
        clearPendingCallbacks();
    }

    private void handleStatusResponse(String response) {
        long receiveTime = System.currentTimeMillis();

        try {
            // Parse and cache the status
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
            JsonElement dataElement = jsonObject.get("data");

            AgvStatusResponse status;
            if (dataElement.isJsonObject()) {
                status = new Gson().fromJson(response, AgvStatusResponse.class);
            } else if (dataElement.isJsonPrimitive() && dataElement.getAsJsonPrimitive().isString()) {
                String dataString = dataElement.getAsString();
                JsonObject reconstructedJson = new JsonObject();
                reconstructedJson.add("cmd", jsonObject.get("cmd"));
                JsonObject parsedData = JsonParser.parseString(dataString).getAsJsonObject();
                reconstructedJson.add("data", parsedData);
                if (jsonObject.has("talk")) reconstructedJson.add("talk", jsonObject.get("talk"));
                if (jsonObject.has("time")) reconstructedJson.add("time", jsonObject.get("time"));
                status = new Gson().fromJson(reconstructedJson, AgvStatusResponse.class);
            } else {
                Log.e(TAG, "Unexpected data format in agvStatus_result");
                if (debugger != null) {
                    debugger.logInvalidMessage(getClass().getSimpleName(), response, "Unexpected data format");
                }
                return;
            }

            if (status != null && status.data != null) {
                // Cache the status
                cachedStatus = status;
                lastStatusReceiveTime = receiveTime;

                // Calculate time since last status update
                long timeSinceLastUpdate = lastStatusReceiveTime > 0 ?
                        receiveTime - lastStatusReceiveTime : 0;

                // Log status update
                if (debugger != null) {
                    // Check if this is a response to a query or just periodic
                    boolean isQueryResponse = !pendingStatusCallbacks.isEmpty();
                    String source = isQueryResponse ? "QUERY_RESPONSE" : "PERIODIC_UPDATE";

                    debugger.logStatusUpdate(getClass().getSimpleName() + "[" + source + "]",
                            true, timeSinceLastUpdate);

                    // Log additional status details
                    String statusDetails = String.format(
                            "Position: (%.2f, %.2f), Battery: %.1f%%, Error: %s",
                            status.data.pos.x, status.data.pos.y,
                            status.data.power, status.data.errorCode != null ? status.data.errorCode : "none"
                    );
                    debugger.log("Status details: " + statusDetails);
                }

                // Notify pending callbacks (query responses)
                notifyPendingStatusCallbacks(status);

                Log.d(TAG, String.format("Status received - Position: (%.2f, %.2f), Battery: %.1f%%, Time since last: %dms",
                        status.data.pos.x, status.data.pos.y, status.data.power, timeSinceLastUpdate));
            } else {
                Log.w(TAG, "Received status response with null data");
                if (debugger != null) {
                    debugger.logWarning("Null status data", "Response: " + response);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing agvStatus_result", e);
            if (debugger != null) {
                debugger.logInvalidMessage(getClass().getSimpleName(), response, e.getMessage());
            }
        }
    }

    private void notifyPendingStatusCallbacks(AgvStatusResponse status) {
        if (pendingStatusCallbacks.isEmpty()) {
            return;
        }

        // Create a copy of the keys to avoid ConcurrentModificationException
        List<String> keysToRemove = new ArrayList<>(pendingStatusCallbacks.keySet());

        for (String requestId : keysToRemove) {
            StatusCallback callback = pendingStatusCallbacks.remove(requestId);
            if (callback != null) {
                Log.d(TAG, "Notifying pending status callback: " + requestId);
                callback.onSuccess(status);
            }
        }
    }

    // ==================================================================
    // Core Async Methods
    // ==================================================================

    public void queryStatus(StatusCallback callback) {
        if (!isConnected()) {
            if (callback != null) {
                callback.onError("Command WebSocket not connected");
            }
            if (debugger != null) {
                debugger.logWarning("Status query failed", "Command WebSocket not connected");
            }
            return;
        }

        String requestId = "query_status_" + System.currentTimeMillis();
        pendingStatusCallbacks.put(requestId, callback);

        // Log status query
        if (debugger != null) {
            debugger.logStatusQuery(getClass().getSimpleName(), requestId);
        }

        Log.d(TAG, "Registered status query callback: " + requestId);

        // Set timeout
        mainHandler.postDelayed(() -> {
            StatusCallback timedOut = pendingStatusCallbacks.remove(requestId);
            if (timedOut != null) {
                long elapsedMs = System.currentTimeMillis() - extractStartTimeFromRequestId(requestId);
                Log.w(TAG, "Status query timeout: " + requestId + " after " + elapsedMs + "ms");

                if (debugger != null) {
                    debugger.logCommandTimeout(getClass().getSimpleName(), "getAgvStatus", requestId, COMMAND_TIMEOUT_MS);
                }
                timedOut.onError("Timeout waiting for status response");
            }
        }, COMMAND_TIMEOUT_MS);

        // Send the status query command
        String command = "{\"cmd\":\"getAgvStatus\"}";
        boolean sent = sendCommand(command);
        if (!sent) {
            pendingStatusCallbacks.remove(requestId);
            if (callback != null) {
                callback.onError("Failed to send status query command");
            }
            Log.e(TAG, "Failed to send status query command");
            if (debugger != null) {
                debugger.logWarning("Status query failed", "Failed to send command");
            }
        } else {
            Log.d(TAG, "Status query command sent");
        }
    }

    // Helper to extract start time from requestId
    private long extractStartTimeFromRequestId(String requestId) {
        try {
            int lastUnderscore = requestId.lastIndexOf('_');
            if (lastUnderscore > 0) {
                return Long.parseLong(requestId.substring(lastUnderscore + 1));
            }
        } catch (NumberFormatException e) {
            // Ignore
        }
        return 0;
    }

    // Add a non-blocking method to get cached status (if you want to cache)
    private AgvStatusResponse cachedStatus;
    private long lastStatusReceiveTime = 0;

    public AgvStatusResponse getCachedStatus() {
        return cachedStatus;
    }

    public boolean isStatusRecent(long maxAgeMs) {
        return cachedStatus != null &&
                (System.currentTimeMillis() - lastStatusReceiveTime) < maxAgeMs;
    }

    public void navigateToPositionAsync(double x, double y, double t, double speed, Callback callback) throws JSONException {
        // Using a JSON library that respects float types
        JSONObject command = new JSONObject();
        command.put("cmd", "navigation");

        JSONArray data = new JSONArray();
        JSONObject navData = new JSONObject();
        navData.put("id", -1);
        navData.put("x", (float) x);
        navData.put("y", (float) y);
        navData.put("t", (float) t);
        navData.put("speed", (float) speed);
        data.put(navData);

        command.put("data", data);
        sendCommandAsync(command.toString(), "navigation_result", callback);
    }

    public void mapSwitchAsync(String mapName, double x, double y, double t, Callback callback) throws JSONException {
        JSONObject command = new JSONObject();
        command.put("cmd", "mapSwitching");

        JSONObject data = new JSONObject();
        data.put("mapName", mapName);
        data.put("x", (float) x);
        data.put("y", (float) y);
        data.put("t", (float) t);

        command.put("data", data);
        sendCommandAsync(command.toString(), "mapSwitching_result", callback);
    }

    public void getAllMapsAsync(MapListCallback callback) {
        String requestId = "getAllMaps_result" + "_" + System.currentTimeMillis();
        pendingMapCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            MapListCallback timedOut = pendingMapCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for map list");
            }
        }, COMMAND_TIMEOUT_MS);

        boolean sent = sendCommand("{\"cmd\":\"getAllMaps\"}");
        if (!sent) {
            pendingMapCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void getMapDataAsync(String mapName, MapDataCallback callback) {
        String requestId = "getMapData_result" + "_" + System.currentTimeMillis();
        pendingMapDataCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            MapDataCallback timedOut = pendingMapDataCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for map data");
            }
        }, COMMAND_TIMEOUT_MS);

        String command = String.format("{\"cmd\":\"getMapData\",\"mapName\":\"%s\"}", mapName);
        boolean sent = sendCommand(command);
        if (!sent) {
            pendingMapDataCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void getNavigatePathAsync(NavigationPathCallback callback) {
        String requestId = "getNavigation_result" + "_" + System.currentTimeMillis();
        pendingNavPathCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            NavigationPathCallback timedOut = pendingNavPathCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for navigation path");
            }
        }, COMMAND_TIMEOUT_MS);

        boolean sent = sendCommand("{\"cmd\":\"getNavigation\"}");
        if (!sent) {
            pendingNavPathCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void getAllChargePointsAsync(MarkerDataCallback callback) {
        String requestId = "getChargingInfo_result" + "_" + System.currentTimeMillis();
        pendingMarkerCallbacks.put(requestId, callback);

        Log.d(TAG, "Registered charging info callback: " + requestId +
                " - Total pending marker callbacks: " + pendingMarkerCallbacks.size());

        mainHandler.postDelayed(() -> {
            MarkerDataCallback timedOut = pendingMarkerCallbacks.remove(requestId);
            if (timedOut != null) {
                Log.w(TAG, "Command timeout: " + requestId);
                timedOut.onError("Timeout waiting for charge point list");
            }
        }, COMMAND_TIMEOUT_MS);

        // Create the command with leadDistance
        JSONObject command = new JSONObject();
        try {
            command.put("cmd", "getChargingInfo");
            command.put("leadDistance", 1.0);
            Log.d(TAG, "Sending getChargingInfo command: " + command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating getChargingInfo command", e);
            pendingMarkerCallbacks.remove(requestId);
            callback.onError("Failed to create command");
            return;
        }

        boolean sent = sendCommand(command.toString());
        if (!sent) {
            pendingMarkerCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    /**
     * Navigate through a list of waypoints (fixed path with multiple points)
     * @param waypoints List of Position objects representing the path (including current position and all target points)
     * @param speed Movement speed
     * @param callback Callback for success/error
     * @throws JSONException if JSON construction fails
     */
    public void fixedPathNavigateAsync(List<Position> waypoints, double speed, Callback callback) throws JSONException {
        if (waypoints == null || waypoints.isEmpty()) {
            if (callback != null) {
                callback.onError("Waypoints list is null or empty");
            }
            return;
        }

        JSONObject command = new JSONObject();
        command.put("cmd", "backwardNavigation");

        JSONArray data = new JSONArray();

        // Add all waypoints to the data array
        for (int i = 0; i < waypoints.size(); i++) {
            Position point = waypoints.get(i);
            JSONObject waypoint = new JSONObject();
            waypoint.put("id", i + 1);  // IDs start from 1
            waypoint.put("x", (float) point.getPosX());
            waypoint.put("y", (float) point.getPosY());
            waypoint.put("t", (float) point.getYaw());
            waypoint.put("speed", (float) speed);
            data.put(waypoint);
        }

        command.put("data", data);
        sendCommandAsync(command.toString(), "backwardNavigation_result", callback);
    }

    /**
     * Convenience method for two-point navigation (current position to target)
     * @param currentX Current X coordinate
     * @param currentY Current Y coordinate
     * @param currentT Current orientation
     * @param targetX Target X coordinate
     * @param targetY Target Y coordinate
     * @param targetT Target orientation
     * @param speed Movement speed
     * @param callback Callback for success/error
     * @throws JSONException if JSON construction fails
     */
    public void fixedPathNavigateAsync(double currentX, double currentY, double currentT,
                                       double targetX, double targetY, double targetT, double speed, Callback callback) throws JSONException {
        // Create position objects for current and target
        Position currentPos = new Position(1, "current", currentX, currentY, currentT);
        Position targetPos = new Position(2, "target", targetX, targetY, targetT);

        List<Position> waypoints = new ArrayList<>();
        waypoints.add(currentPos);
        waypoints.add(targetPos);

        fixedPathNavigateAsync(waypoints, speed, callback);
    }

    public void getMapMarkersAsync(String mapName, MarkerDataCallback callback) {
        String requestId = "marker_getMapAll_result" + "_" + System.currentTimeMillis();
        pendingMarkerCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            MarkerDataCallback timedOut = pendingMarkerCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for current map marker data");
            }
        }, COMMAND_TIMEOUT_MS);

        String command = String.format(
                "{\"cmd\":\"marker\",\"command\":\"getMapAll\",\"data\":{\"mapName\":\"%s\"}}", mapName);

        boolean sent = sendCommand(command);
        if (!sent) {
            pendingMarkerCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void getAllMarkersAsync(MarkerDataCallback callback) {
        String requestId = "marker_getAll_result" + "_" + System.currentTimeMillis();
        pendingMarkerCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            MarkerDataCallback timedOut = pendingMarkerCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for all map marker data");
            }
        }, COMMAND_TIMEOUT_MS);

        String command = "{\"cmd\":\"marker\",\"command\":\"getAll\"}";

        boolean sent = sendCommand(command);
        if (!sent) {
            pendingMarkerCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void getCurrentMapInfoAsync(CurrentMapInfoCallback callback) {
        String requestId = "getMapDataRedis_result" + "_" + System.currentTimeMillis();
        pendingMapInfoCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            CurrentMapInfoCallback timedOut = pendingMapInfoCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for current map info");
            }
        }, COMMAND_TIMEOUT_MS);

        String command = "{\"cmd\":\"getMapDataRedis\"}";
        boolean sent = sendCommand(command);
        if (!sent) {
            pendingMapInfoCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void getSafetyAreasAsync(SafetyAreasCallback callback) {
        String requestId = "region_get_result" + "_" + System.currentTimeMillis();
        pendingSafetyAreasCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            SafetyAreasCallback timedOut = pendingSafetyAreasCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for safety areas");
            }
        }, COMMAND_TIMEOUT_MS);

        String command = "{\"cmd\":\"region\",\"command\":\"get\"}";
        boolean sent = sendCommand(command);
        if (!sent) {
            pendingSafetyAreasCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    /**
     * 从底盘控制器拉取命名区域 (region id=6 中带 name 的条目)。
     * 命名规范: {mapName}_{traffic|door|info}{number}
     * 不符合规范的条目 (如 "Ultrasound") 会被过滤。
     *
     * @param mapName  目标地图名 (如 "HNRenming1_4"); 为空则不带 mapName 参数
     * @param callback 结果回调
     */
    public void getNamedAreasAsync(String mapName, NamedAreasCallback callback) {
        String requestId = "region_get_result" + "_" + System.currentTimeMillis();
        pendingNamedAreasCallbacks.put(requestId, callback);

        mainHandler.postDelayed(() -> {
            NamedAreasCallback timedOut = pendingNamedAreasCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.onError("Timeout waiting for named areas");
            }
        }, COMMAND_TIMEOUT_MS);

        String command;
        if (mapName != null && !mapName.trim().isEmpty()) {
            command = String.format(
                    "{\"cmd\":\"region\",\"command\":\"get\",\"mapName\":\"%s\"}", mapName);
        } else {
            command = "{\"cmd\":\"region\",\"command\":\"get\"}";
        }
        boolean sent = sendCommand(command);
        if (!sent) {
            pendingNamedAreasCallbacks.remove(requestId);
            callback.onError("Failed to send command");
        }
    }

    public void addMarkerAsync(String mapName, String name, String floor,
                               double x, double y, double t, int type, Position.ElevatorInfo elevatorInfo, Callback callback) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "marker");
            command.put("command", "add");

            JSONObject data = new JSONObject();
            data.put("mapName", mapName);
            data.put("name", name);
            data.put("floor", floor);
            data.put("x", (float) x);
            data.put("y", (float) y);
            data.put("t", (float) t);
            data.put("type", type);
            data.put("elevatorInfo", elevatorInfo);

            command.put("data", data);
            sendCommandAsync(command.toString(), "marker_add_result", callback);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating navigation command", e);
        }
    }

    public void deleteMarkerAsync(String mapName, String name, String floor, Callback callback) {
        String command = String.format(
                "{\"cmd\":\"marker\",\"command\":\"delFind\",\"data\":{" +
                        "\"floor\":\"%s\",\"name\":\"%s\",\"mapName\":\"%s\"}}",
                floor, name, mapName);

        sendCommandAsync(command, "marker_delFind_result", callback);
    }

    // ==================================================================
    // Fire-and-Forget Commands (no response needed)
    // ==================================================================

    public boolean setSoftScram(boolean enable) {
        boolean ok = sendCommand(String.format("{\"cmd\":\"softScram\",\"data\":%b}", enable));
        TaskDebug2.log(String.format("[PAUSE] setSoftScram(%b) sendOk=%b", enable, ok));
        return ok;
    }

    public boolean cancelTask() {
        MyApplication.getInstance().getTaskViewModel().markTaskIdle();
        return sendCommand("{\"cmd\":\"clearTask\"}");
    }

    public boolean setArea(JSONObject rectangle) {
        try {
            // 检查输入的JSON对象
            if (rectangle == null) {
                Log.e(TAG, "Rectangle JSON object is null");
                return false;
            }

            // 创建命令对象
            JSONObject command = new JSONObject();
            command.put("cmd", "region");
            command.put("command", "set");

            // 检查矩形是否为四个零的情况
            boolean isZeroRectangle = isZeroRectangle(rectangle);

            JSONObject data = new JSONObject();
            data.put("id", 1);

            if (isZeroRectangle) {
                // 情况1：矩形为四个零，发送空区域
                data.put("data", new JSONArray());
                Log.i(TAG, "设置空区域（清除现有区域）");
            } else {
                // 情况2：正常矩形，使用输入的多边形数据
                // 从输入矩形中提取多边形数据
                // 假设rectangle已经是多边形格式：{"data": [[{"x":..., "y":...}, ...]]}
                if (rectangle.has("data")) {
                    // 如果rectangle已经包含data字段，直接使用
                    JSONArray polygonArray = rectangle.getJSONArray("data");
                    data.put("data", polygonArray);
                    Log.i(TAG, "使用提供的多边形数据 " + polygonArray);
                } else {
                    // 否则，假设rectangle是矩形坐标格式：{"left":..., "right":..., "bottom":..., "top":...}
                    JSONArray polygonArray = convertRectToPolygon(rectangle);
                    data.put("data", polygonArray);
                    Log.i(TAG, "将矩形坐标转换为多边形 " + polygonArray);
                }
            }

            command.put("data", data);

            Log.d(TAG, "发送区域命令: " + command.toString());
            return sendCommand(command.toString());

        } catch (JSONException e) {
            Log.e(TAG, "Error creating region command", e);
            return false;
        }
    }

    /**
     * 检查矩形是否为四个零
     */
    private boolean isZeroRectangle(JSONObject rectangle) {
        try {
            // 方法1：检查是否有data字段且为空数组
            if (rectangle.has("data")) {
                JSONArray dataArray = rectangle.getJSONArray("data");
                if (dataArray.length() == 0) {
                    return true;
                }
            }

            // 方法2：检查是否为零矩形坐标
            if (rectangle.has("left") && rectangle.has("right") &&
                    rectangle.has("bottom") && rectangle.has("top")) {
                double left = rectangle.getDouble("left");
                double right = rectangle.getDouble("right");
                double bottom = rectangle.getDouble("bottom");
                double top = rectangle.getDouble("top");

                return (Math.abs(left) < 1e-10 && Math.abs(right) < 1e-10 &&
                        Math.abs(bottom) < 1e-10 && Math.abs(top) < 1e-10);
            }

            // 方法3：检查是否标记为零矩形
            if (rectangle.has("isZero") && rectangle.getBoolean("isZero")) {
                return true;
            }

            return false;

        } catch (JSONException e) {
            Log.e(TAG, "Error checking zero rectangle", e);
            return false;
        }
    }

    /**
     * 将矩形坐标JSON转换为多边形格式
     */
    private JSONArray convertRectToPolygon(JSONObject rectJson) throws JSONException {
        JSONArray polygonArray = new JSONArray();

        // 尝试获取矩形坐标
        double left = rectJson.optDouble("left", 0);
        double right = rectJson.optDouble("right", 0);
        double bottom = rectJson.optDouble("bottom", 0);
        double top = rectJson.optDouble("top", 0);

        // 如果坐标无效，返回空数组
        if (left == 0 && right == 0 && bottom == 0 && top == 0) {
            return polygonArray;
        }

        // 创建顶点数组
        JSONArray verticesArray = new JSONArray();

        // 添加矩形四个顶点（顺时针）
        verticesArray.put(createVertex(left, top));      // 左上
        verticesArray.put(createVertex(right, top));     // 右上
        verticesArray.put(createVertex(right, bottom));  // 右下
        verticesArray.put(createVertex(left, bottom));   // 左下

        polygonArray.put(verticesArray);
        return polygonArray;
    }

    /**
     * 创建顶点JSON对象
     */
    private JSONObject createVertex(double x, double y) throws JSONException {
        JSONObject vertex = new JSONObject();
        vertex.put("x", (float) x);  // 转换为float
        vertex.put("y", (float) y);  // 转换为float
        return vertex;
    }

    public boolean navigateToPosition(double x, double y, double t, double speed) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "navigation");

            JSONArray data = new JSONArray();
            JSONObject navData = new JSONObject();
            navData.put("id", -1);
            navData.put("x", (float) x);
            navData.put("y", (float) y);
            navData.put("t", (float) t);
            navData.put("speed", (float) speed);
            data.put(navData);

            command.put("data", data);

            Log.d(TAG, "导航到位置: " + command.toString());
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating navigation command", e);
            return false;
        }
    }

    public void routeNavigateToPositionAsync(double x, double y, double t, double speed, int obstacleTime, Callback callback) throws JSONException {
        // 使用 JsonObject 构造命令，与同步方法保持一致
        JsonObject command = new JsonObject();
        String navigationCommand = getVirtualOrbitNavigationCommand();
        command.addProperty("cmd", navigationCommand);

        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("t", t);
        command.add("pos", pos);
        command.addProperty("speed", speed);
        command.addProperty("time", obstacleTime);  // 绕障时间：0立即绕障，-1不绕障，>0为延迟秒数

        sendCommandAsync(command.toString(), navigationCommand + "_result", callback);
    }
    
    // 兼容旧版本的方法
    public void routeNavigateToPositionAsync(double x, double y, double t, double speed, Callback callback) throws JSONException {
        routeNavigateToPositionAsync(x, y, t, speed, -1, callback);
    }
    
    public boolean routeNavigateToPosition(double x, double y, double t, double speed, int obstacleTime) {
        try {
            // Create the JSON structure using JsonObject for better readability
            JsonObject position = new JsonObject();
            position.addProperty("x", x);
            position.addProperty("y", y);
            position.addProperty("t", t);

            JsonObject command = new JsonObject();
            command.addProperty("cmd", getVirtualOrbitNavigationCommand());
            command.add("pos", position);
            command.addProperty("speed", speed);
            command.addProperty("time", obstacleTime);  // 绕障时间：0立即绕障，-1不绕障，>0为延迟秒数

            String json = gson.toJson(command);
            Log.d("OrbitalNavigate", "发送路径导航命令: " + json);
            return sendCommand(json);
        } catch (Exception e) {
            Log.e("OrbitalNavigate", "创建路径导航命令失败", e);
            return false;
        }
    }
    
    // 兼容旧版本的方法
    public boolean routeNavigateToPosition(double x, double y, double t, double speed) {
        return routeNavigateToPosition(x, y, t, speed, -1);
    }

    private String getVirtualOrbitNavigationCommand() {
        Boolean isOneWay = MyApplication.getInstance()
                .getBasicViewModel()
                .getIsVirtualOrbitOneWayMode()
                .getValue();
        return Boolean.TRUE.equals(isOneWay) ? "oneWayRoute" : "routeNavigation";
    }

    public boolean sendMapSwitch(String mapName, double x, double y, double t) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "mapSwitching");

            JSONObject data = new JSONObject();
            data.put("mapName", mapName);
            data.put("x", (float) x);
            data.put("y", (float) y);
            data.put("t", (float) t);

            command.put("data", data);
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating map switch command", e);
            return false;
        }
    }

    public boolean sendJackModeNav(double x, double y, double t, double speed, String action, double fd, double td, Integer id) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "shelfDynamicPoint");
            command.put("target_x", (float) x);
            command.put("target_y", (float) y);
            command.put("target_t", (float) t);
            command.put("action", action);
            command.put("autoNav", 1);
            command.put("fd", (float) fd);
            command.put("td", (float) td);
            command.put("speed", (float) speed);
            command.put("id", id);

            // 打印 td 数据和完整命令
            Log.d(TAG, "发送顶升模式导航命令 - td值: " + td + ", 完整命令: " + command.toString());
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating jack mode navigation command", e);
            return false;
        }
    }

    public boolean navigateToEventPosition(double x, double y, double t, double speed, int id, String action) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "eventNavigation");
            command.put("autoNav", 1);

            JSONArray data = new JSONArray();
            JSONObject navData = new JSONObject();
            navData.put("x", (float) x);
            navData.put("y", (float) y);
            navData.put("t", (float) t);
            navData.put("speed", (float) speed);
            navData.put("id", id);
            navData.put("action", action);
            data.put(navData);

            command.put("data", data);

            Log.d(TAG, "事件导航到位置: " + command.toString());
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating event navigation command", e);
            return false;
        }
    }

    /**
     * Start charging (Async version using existing Callback)
     * @param callback Callback for success/error
     */
    public void startChargeAsync(Callback callback) {
        if (!isConnected()) {
            if (callback != null) {
                callback.onError("Command WebSocket not connected");
            }
            return;
        }

        try {
//            JSONObject command = new JSONObject();
//            command.put("cmd", "chargeNav");
//            command.put("autoNav", 2);
//            command.put("dir", 0);
//            command.put("speed", 0.7);
//            command.put("frontOffset", 1.0);
//            command.put("time", 0);
//
//            sendCommandAsync(command.toString(), "chargeNav_result", callback);
            JSONObject command = new JSONObject();
            command.put("cmd", "charge");
            command.put("value", true);

            sendCommandAsync(command.toString(), "charge_result", callback);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating chargeNav command", e);
            if (callback != null) {
                callback.onError("Error creating command: " + e.getMessage());
            }
        }
    }

    /**
     * Stop charging (Async version using existing Callback)
     * @param callback Callback for success/error
     */
    public void stopChargeAsync(Callback callback) {
        if (!isConnected()) {
            if (callback != null) {
                callback.onError("Command WebSocket not connected");
            }
            return;
        }

        String command = "{\"cmd\":\"charge\", \"value\":false}";
        sendCommandAsync("{\"cmd\":\"clearStatus\"}", "clearStatus_result", callback);
    }

    /**
     * 删除充电点
     * @param mapName 地图名
     * @return 是否发送成功
     */
    public boolean removeChargePoint(String mapName) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "rmCharge");
            command.put("mapName", mapName);
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating rmCharge command", e);
            return false;
        }
    }

    public boolean jackLoad() {
        return sendCommand(
                "{\"cmd\":\"table\",\"command\":\"set\",\"writes\":[" +
                        "{\"address\":\"680\",\"len\":1,\"type\":\"float\",\"cmd\":\"write\",\"data\":[1]}" +
                        "]}");
    }

    public boolean jackUnload() {
        return sendCommand(
                "{\"cmd\":\"table\",\"command\":\"set\",\"writes\":[" +
                        "{\"address\":\"680\",\"len\":1,\"type\":\"float\",\"cmd\":\"write\",\"data\":[-1]}" +
                        "]}");
    }

    /**
     * Reads the current jack height from chassis table address 690.
     */
    public void getJackHeightAsync(JackHeightCallback callback) {
        if (callback == null) {
            return;
        }

        readTableData("690", 4, "float", new Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject result = new JSONObject(response);
                    int code = result.optInt("code", -1);
                    if (code != 0) {
                        callback.onError("Failed to get jack height, code: " + code);
                        return;
                    }

                    JSONArray data = result.optJSONArray("data");
                    if (data == null) {
                        callback.onError("Jack height response missing data");
                        return;
                    }

                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item == null || !"690".equals(item.optString("address"))) {
                            continue;
                        }

                        JSONArray values = item.optJSONArray("value");
                        if (values == null || values.length() == 0) {
                            callback.onError("Jack height value is empty");
                            return;
                        }

                        Object value = values.opt(0);
                        if (value instanceof Number) {
                            callback.onSuccess(((Number) value).floatValue());
                        } else {
                            callback.onSuccess(Float.parseFloat(String.valueOf(value)));
                        }
                        return;
                    }

                    callback.onError("Jack height address 690 not found");
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing jack height response", e);
                    callback.onError("Error parsing jack height response: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    public int getCachedJackState() {
        return isJackStateFresh() ? cachedJackState : JACK_STATE_UNKNOWN;
    }

    public float getCachedJackHeight() {
        return isJackStateFresh() ? cachedJackHeight : Float.NaN;
    }

    public boolean isJackStateFresh() {
        return lastJackStateUpdateTime > 0
                && System.currentTimeMillis() - lastJackStateUpdateTime <= JACK_STATE_STALE_MS;
    }

    private void startJackHeightPolling() {
        mainHandler.removeCallbacks(jackHeightPollingRunnable);
        mainHandler.post(jackHeightPollingRunnable);
    }

    private void stopJackHeightPolling() {
        mainHandler.removeCallbacks(jackHeightPollingRunnable);
        jackHeightRequestInFlight = false;
        cachedJackHeight = Float.NaN;
        cachedJackState = JACK_STATE_UNKNOWN;
        lastJackStateUpdateTime = 0;
    }

    private long getJackHeightPollingInterval() {
        try {
            String taskType = MyApplication.getInstance()
                    .getTaskViewModel()
                    .getCurrentTaskTypeValue();
            return "jack".equalsIgnoreCase(taskType)
                    ? JACK_POLL_ACTIVE_MS
                    : JACK_POLL_IDLE_MS;
        } catch (Exception e) {
            return JACK_POLL_IDLE_MS;
        }
    }

    private int mapJackHeightToState(float height) {
        if (Float.isNaN(height)) {
            return JACK_STATE_UNKNOWN;
        }
        if (height > 40.0f) {
            return 2;
        }
        if (height < 1.0f) {
            return 1;
        }
        return 3;
    }

    /**
     * Reads a value from a chassis table address.
     */
    public void readTableData(String address, int len, String type, Callback callback) {
        String requestId = "table_get_" + address + "_" + System.currentTimeMillis();
        pendingCallbacks.put(requestId, new CommandRequest(callback, System.currentTimeMillis(), requestId));

        mainHandler.postDelayed(() -> {
            CommandRequest timedOut = pendingCallbacks.remove(requestId);
            if (timedOut != null) {
                timedOut.callback.onError("Timeout waiting for table read response");
            }
        }, COMMAND_TIMEOUT_MS);

        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "table");
            command.put("command", "get");

            JSONArray reads = new JSONArray();
            JSONObject readItem = new JSONObject();
            readItem.put("address", address);
            readItem.put("len", len);
            readItem.put("type", type);
            readItem.put("cmd", "read");
            reads.put(readItem);

            command.put("reads", reads);

            boolean sent = sendCommand(command.toString());
            if (!sent) {
                pendingCallbacks.remove(requestId);
                if (callback != null) {
                    callback.onError("Failed to send table read command");
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating table read command", e);
            if (callback != null) {
                callback.onError("Error creating table read command: " + e.getMessage());
            }
        }
    }

    /**
     * 写入table地址的值
     * @param address 地址（如 "141"）
     * @param len 长度
     * @param type 类型（如 "uint8"）
     * @param data 数据数组
     * @return 是否发送成功
     */
    public boolean writeTableData(String address, int len, String type, int[] data) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "table");
            command.put("command", "set");

            JSONArray writes = new JSONArray();
            JSONObject writeItem = new JSONObject();
            writeItem.put("address", address);
            writeItem.put("len", len);
            writeItem.put("type", type);
            writeItem.put("cmd", "write");

            JSONArray dataArray = new JSONArray();
            for (int value : data) {
                dataArray.put(value);
            }
            writeItem.put("data", dataArray);
            writes.put(writeItem);

            command.put("writes", writes);

            Log.d(TAG, "发送table写入命令: " + command.toString());
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating table write command", e);
            return false;
        }
    }

    public boolean saveCharge() {
        Log.d(TAG, "保存充电点");
        return sendCommand("{\"cmd\":\"saveCharge\"}");
    }

    public boolean saveTheStartPoint(String mapName, double x, double y, double t) {
        try {
            JSONObject command = new JSONObject();
            command.put("cmd", "setTheStartPoint");

            JSONObject data = new JSONObject();
            data.put("mapName", mapName);
            data.put("x", (float) x);
            data.put("y", (float) y);
            data.put("t", (float) t);

            command.put("data", data);

            Log.d(TAG, "开机启动点设置: " + command.toString());
            return sendCommand(command.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating start point command", e);
            return false;
        }
    }

    // Add all your other command methods here...

    // ==================================================================
    // Core Command Handling
    // ==================================================================

    private void handleCommandResponse(String responseCmd, String response) {
        Log.d(TAG, "Handling command response: " + responseCmd);
        boolean handled = false;

        // Handle charging info callbacks
        if ("getChargingInfo_result".equals(responseCmd)) {
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, MarkerDataCallback> entry : pendingMarkerCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("getChargingInfo_result_")) {
                    MarkerDataCallback callback = pendingMarkerCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleChargingInfoResponse(response, callback);
                        handled = true;
                        keysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : keysToRemove) {
                pendingMarkerCallbacks.remove(key);
            }
        }

        // Handle regular callbacks (with timing!)
        List<String> regularKeysToRemove = new ArrayList<>();
        for (Map.Entry<String, CommandRequest> entry : pendingCallbacks.entrySet()) {
            String requestId = entry.getKey();
            if (requestId.startsWith(responseCmd + "_")) {
                CommandRequest request = pendingCallbacks.remove(requestId);
                if (request != null && request.callback != null) {
                    long elapsedMs = System.currentTimeMillis() - request.startTime;

                    // Log successful response with timing
                    if (debugger != null) {
                        debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                    }

                    Log.d(TAG, String.format("Command %s completed in %dms", requestId, elapsedMs));
                    request.callback.onSuccess(response);
                    handled = true;
                    regularKeysToRemove.add(requestId);
                    break;
                }
            }
        }
        for (String key : regularKeysToRemove) {
            pendingCallbacks.remove(key);
        }

        // Handle map list callbacks
        if ("getAllMaps_result".equals(responseCmd)) {
            List<String> mapKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, MapListCallback> entry : pendingMapCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("getAllMaps_result_")) {
                    MapListCallback callback = pendingMapCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleMapListResponse(response, callback);
                        handled = true;
                        mapKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : mapKeysToRemove) {
                pendingMapCallbacks.remove(key);
            }
        }

        // Handle marker data callbacks
        if ("marker_getMapAll_result".equals(responseCmd)) {
            List<String> markerKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, MarkerDataCallback> entry : pendingMarkerCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("marker_getMapAll_result_")) {
                    MarkerDataCallback callback = pendingMarkerCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleMarkerDataResponse(response, callback);
                        handled = true;
                        markerKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : markerKeysToRemove) {
                pendingMarkerCallbacks.remove(key);
            }
        }

        // Handle marker_getAll_result
        if ("marker_getAll_result".equals(responseCmd)) {
            List<String> markerKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, MarkerDataCallback> entry : pendingMarkerCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("marker_getAll_result_")) {
                    MarkerDataCallback callback = pendingMarkerCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleMarkerDataResponse(response, callback);
                        handled = true;
                        markerKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : markerKeysToRemove) {
                pendingMarkerCallbacks.remove(key);
            }
        }

        // Handle getNavigation_result
        if ("getNavigation_result".equals(responseCmd)) {
            List<String> navPathKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, NavigationPathCallback> entry : pendingNavPathCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("getNavigation_result_")) {
                    NavigationPathCallback callback = pendingNavPathCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleNavigationPathResponse(response, callback);
                        handled = true;
                        navPathKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : navPathKeysToRemove) {
                pendingNavPathCallbacks.remove(key);
            }
        }

        // Handle getMapDataRedis_result
        if ("getMapDataRedis_result".equals(responseCmd)) {
            List<String> mapInfoKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, CurrentMapInfoCallback> entry : pendingMapInfoCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("getMapDataRedis_result_")) {
                    CurrentMapInfoCallback callback = pendingMapInfoCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleCurrentMapInfoResponse(response, callback);
                        handled = true;
                        mapInfoKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : mapInfoKeysToRemove) {
                pendingMapInfoCallbacks.remove(key);
            }
        }

        // Handle region_get_result
        if ("region_get_result".equals(responseCmd)) {
            List<String> safetyAreaKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, SafetyAreasCallback> entry : pendingSafetyAreasCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("region_get_result_")) {
                    SafetyAreasCallback callback = pendingSafetyAreasCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleSafetyAreasResponse(response, callback);
                        handled = true;
                        safetyAreaKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : safetyAreaKeysToRemove) {
                pendingSafetyAreasCallbacks.remove(key);
            }
        }

        // Handle region_get_result for named areas (与 safety areas 共用同一响应, 独立分发)
        if ("region_get_result".equals(responseCmd)) {
            List<String> namedAreaKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, NamedAreasCallback> entry : pendingNamedAreasCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("region_get_result_")) {
                    NamedAreasCallback callback = pendingNamedAreasCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleNamedAreasResponse(response, callback);
                        handled = true;
                        namedAreaKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : namedAreaKeysToRemove) {
                pendingNamedAreasCallbacks.remove(key);
            }
        }

        // Handle getMapData_result
        if ("getMapData_result".equals(responseCmd)) {
            List<String> mapDataKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, MapDataCallback> entry : pendingMapDataCallbacks.entrySet()) {
                String requestId = entry.getKey();
                if (requestId.startsWith("getMapData_result_")) {
                    MapDataCallback callback = pendingMapDataCallbacks.remove(requestId);
                    if (callback != null) {
                        long elapsedMs = extractElapsedTimeFromRequestId(requestId);
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        handleMapDataResponse(response, callback);
                        handled = true;
                        mapDataKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : mapDataKeysToRemove) {
                pendingMapDataCallbacks.remove(key);
            }
        }

        // Handle table_reads_result
        if ("table_reads_result".equals(responseCmd)) {
            String responseAddress = extractTableResponseAddress(response);
            List<String> tableKeysToRemove = new ArrayList<>();
            for (Map.Entry<String, CommandRequest> entry : pendingCallbacks.entrySet()) {
                String requestId = entry.getKey();
                String expectedPrefix = responseAddress.isEmpty()
                        ? "table_get_"
                        : "table_get_" + responseAddress + "_";
                if (requestId.startsWith(expectedPrefix)) {
                    CommandRequest request = pendingCallbacks.remove(requestId);
                    if (request != null && request.callback != null) {
                        long elapsedMs = System.currentTimeMillis() - request.startTime;
                        if (debugger != null) {
                            debugger.logCommandResponse(getClass().getSimpleName(), responseCmd, requestId, elapsedMs);
                        }
                        request.callback.onSuccess(response);
                        handled = true;
                        tableKeysToRemove.add(requestId);
                        break;
                    }
                }
            }
            for (String key : tableKeysToRemove) {
                pendingCallbacks.remove(key);
            }
        }

        if (!handled) {
            Log.d(TAG, "Unhandled command response: " + responseCmd +
                    " - Pending regular callbacks: " + pendingCallbacks.keySet());
        }
    }

    private String extractTableResponseAddress(String response) {
        try {
            JSONObject result = new JSONObject(response);
            JSONArray data = result.optJSONArray("data");
            if (data != null && data.length() > 0) {
                JSONObject item = data.optJSONObject(0);
                if (item != null) {
                    return item.optString("address", "");
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Unable to parse table response address", e);
        }
        return "";
    }

    // Helper method to extract elapsed time from requestId (approximate)
    private long extractElapsedTimeFromRequestId(String requestId) {
        try {
            // RequestId format: "command_result_timestamp"
            int lastUnderscore = requestId.lastIndexOf('_');
            if (lastUnderscore > 0) {
                long startTime = Long.parseLong(requestId.substring(lastUnderscore + 1));
                return System.currentTimeMillis() - startTime;
            }
        } catch (NumberFormatException e) {
            // Ignore
        }
        return -1;
    }

    /**
     * Handle getChargingInfo response which has a different format than marker data
     */
    private void handleChargingInfoResponse(String response, MarkerDataCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }

            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            // Convert the charging info response to MarkerData format for consistency
            MarkerData markerData = convertChargingInfoToMarkerData(dataElement);
            callback.onSuccess(markerData);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing charging info response", e);
            callback.onError("Error parsing charging info: " + e.getMessage());
        }
    }

    private void handleMapListResponse(String response, MapListCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            if (dataElement.isJsonArray()) {
                JsonArray dataArray = dataElement.getAsJsonArray();
                List<String> mapList = new ArrayList<>();
                for (JsonElement element : dataArray) {
                    if (element.isJsonPrimitive()) {
                        mapList.add(element.getAsString());
                    }
                }
                callback.onSuccess(mapList);
            } else {
                callback.onError("Unexpected data format");
            }
        } catch (Exception e) {
            callback.onError("Error parsing response: " + e.getMessage());
        }
    }


    private void handleMarkerDataResponse(String response, MarkerDataCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }

            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            MarkerData markerData = new Gson().fromJson(dataElement, MarkerData.class);
            callback.onSuccess(markerData);

        } catch (Exception e) {
            callback.onError("Error parsing marker data: " + e.getMessage());
        }
    }

    private void handleNavigationPathResponse(String response, NavigationPathCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }

            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            // Custom parsing to convert the line array to Position objects
            NavigationPathData pathData = parseNavigationPathData(dataElement.getAsJsonObject());
            callback.onSuccess(pathData);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing navigation path response", e);
            callback.onError("Error parsing navigation path: " + e.getMessage());
        }
    }

    /**
     * Handle getMapDataRedis response
     */
    private void handleCurrentMapInfoResponse(String response, CurrentMapInfoCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }

            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            if (!dataElement.isJsonObject()) {
                callback.onError("Data field is not a JSON object");
                return;
            }

            JsonObject dataObject = dataElement.getAsJsonObject();

            // Extract map information
            String mapName = "";
            double resolution = 0.0;
            int width = 0;
            int height = 0;
            double[] mapPosition = new double[3]; // [x, y, t]
            String format = "";
            String time = "";
            String mapImage = "";

            if (dataObject.has("name")) {
                mapName = dataObject.get("name").getAsString();
            }

            if (dataObject.has("resolution")) {
                resolution = dataObject.get("resolution").getAsDouble();
            }

            if (dataObject.has("width")) {
                width = dataObject.get("width").getAsInt();
            }

            if (dataObject.has("height")) {
                height = dataObject.get("height").getAsInt();
            }

            if (dataObject.has("mappos") && dataObject.get("mappos").isJsonArray()) {
                JsonArray posArray = dataObject.get("mappos").getAsJsonArray();
                if (posArray.size() >= 3) {
                    mapPosition[0] = posArray.get(0).getAsDouble(); // x
                    mapPosition[1] = posArray.get(1).getAsDouble(); // y
                    mapPosition[2] = posArray.get(2).getAsDouble(); // t (orientation)
                }
            }

            if (dataObject.has("format")) {
                format = dataObject.get("format").getAsString();
            }

            if (dataObject.has("time")) {
                time = dataObject.get("time").getAsString();
            }

            if (dataObject.has("map") && !dataObject.get("map").isJsonNull()) {
                mapImage = dataObject.get("map").getAsString();
            }

            // Return success with extracted data
            callback.onSuccess(mapName, resolution, width, height, mapPosition, format, time, mapImage);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing current map info response", e);
            callback.onError("Error parsing current map info: " + e.getMessage());
        }
    }

    /**
     * Handle region_get_result response for safety areas
     */
    private void handleSafetyAreasResponse(String response, SafetyAreasCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }

            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            if (!dataElement.isJsonArray()) {
                callback.onError("Data field is not a JSON array");
                return;
            }

            // Parse safety areas data
            SafetyAreasData safetyAreasData = parseSafetyAreasData(dataElement.getAsJsonArray());
            callback.onSuccess(safetyAreasData);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing safety areas response", e);
            callback.onError("Error parsing safety areas: " + e.getMessage());
        }
    }

    /**
     * Handle region_get_result response for named areas (id=6 中带 name 的条目)。
     * 命名规范: {mapName}_{traffic|door|info}{number}
     * 不符合规范的条目会被 parseNamedAreas 过滤。
     */
    private void handleNamedAreasResponse(String response, NamedAreasCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }
            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }
            if (!jsonObject.has("data") || !jsonObject.get("data").isJsonArray()) {
                callback.onError("Response missing data array");
                return;
            }

            List<MapArea> namedAreas = parseNamedAreas(jsonObject.get("data").getAsJsonArray());
            callback.onSuccess(namedAreas);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing named areas response", e);
            callback.onError("Error parsing named areas: " + e.getMessage());
        }
    }

    /**
     * 解析 region_get_result 的 data 数组, 提取 id=6 中符合命名规范的命名区域。
     * 规范: {mapName}_{traffic|door|info}{number}
     * 示例: HNRenming1_4_traffic1 -> mapName=HNRenming1_4, type=交管区域, number=1
     *       Ultrasound -> 跳过 (不符合规范)
     */
    private List<MapArea> parseNamedAreas(JsonArray dataArray) {
        List<MapArea> result = new ArrayList<>();
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("^(.+?)_(traffic|door|info)(\\d+)$",
                        java.util.regex.Pattern.CASE_INSENSITIVE);

        for (int i = 0; i < dataArray.size(); i++) {
            JsonElement regionElement = dataArray.get(i);
            if (!regionElement.isJsonObject()) continue;
            JsonObject regionObj = regionElement.getAsJsonObject();
            if (!regionObj.has("id")) continue;
            if (regionObj.get("id").getAsInt() != 6) continue;  // 只取 id=6

            if (!regionObj.has("data") || !regionObj.get("data").isJsonArray()) continue;
            JsonArray regionDataArray = regionObj.get("data").getAsJsonArray();

            for (int j = 0; j < regionDataArray.size(); j++) {
                JsonElement itemElement = regionDataArray.get(j);
                if (!itemElement.isJsonObject()) continue;
                JsonObject item = itemElement.getAsJsonObject();
                if (!item.has("name")) continue;  // id=6 中无 name 的条目跳过
                String name = item.get("name").getAsString();

                // 校验命名规范
                java.util.regex.Matcher m = pattern.matcher(name);
                if (!m.matches()) {
                    Log.d(TAG, "parseNamedAreas: skip non-standard name: " + name);
                    continue;
                }
                String parsedMapName = m.group(1);
                String typeTag = m.group(2).toLowerCase();
                String typeLabel;
                switch (typeTag) {
                    case "traffic": typeLabel = "交管区域"; break;
                    case "door":    typeLabel = "门控区域"; break;
                    case "info":    typeLabel = "信息区域"; break;
                    default: continue;
                }

                // 解析 points
                if (!item.has("points") || !item.get("points").isJsonArray()) continue;
                JsonArray pointsArray = item.get("points").getAsJsonArray();
                List<Float> polygonPoints = new ArrayList<>();
                for (int k = 0; k < pointsArray.size(); k++) {
                    JsonElement ptEl = pointsArray.get(k);
                    if (!ptEl.isJsonObject()) continue;
                    JsonObject pt = ptEl.getAsJsonObject();
                    if (pt.has("x") && pt.has("y")) {
                        polygonPoints.add((float) pt.get("x").getAsDouble());
                        polygonPoints.add((float) pt.get("y").getAsDouble());
                    }
                }
                if (polygonPoints.size() < 6) continue;  // 至少 3 个点

                MapArea area = new MapArea(name, typeLabel, "", "",
                        parsedMapName, polygonPoints);
                result.add(area);
                Log.d(TAG, "parseNamedAreas: parsed " + name + " -> type=" + typeLabel
                        + ", points=" + polygonPoints.size() / 2);
            }
        }
        Log.d(TAG, "parseNamedAreas: total " + result.size() + " named areas (id=6)");
        return result;
    }

    private void handleMapDataResponse(String response, MapDataCallback callback) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();

            if (!jsonObject.has("code")) {
                callback.onError("Response missing code field");
                return;
            }

            int code = jsonObject.get("code").getAsInt();
            if (code != 0) {
                String errorMsg = jsonObject.has("message") ?
                        jsonObject.get("message").getAsString() : "Unknown error";
                callback.onError("Server error: " + errorMsg);
                return;
            }

            if (!jsonObject.has("data")) {
                callback.onError("Response missing data field");
                return;
            }

            JsonElement dataElement = jsonObject.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                callback.onError("Data field is null");
                return;
            }

            if (!dataElement.isJsonObject()) {
                callback.onError("Data field is not a JSON object");
                return;
            }

            JSONObject mapData = new JSONObject(dataElement.getAsJsonObject().toString());
            callback.onSuccess(mapData);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing map data response", e);
            callback.onError("Error parsing map data: " + e.getMessage());
        }
    }

    // Custom parser to convert the line coordinates to Position objects
    private NavigationPathData parseNavigationPathData(JsonObject dataObject) {
        NavigationPathData pathData = new NavigationPathData();

        // Parse basic fields
        if (dataObject.has("talk")) {
            pathData.setTalk(dataObject.get("talk").getAsString());
        }
        if (dataObject.has("time")) {
            pathData.setTime(dataObject.get("time").getAsString());
        }

        // Parse line coordinates and convert to Position objects
        if (dataObject.has("line") && dataObject.get("line").isJsonArray()) {
            JsonArray lineArray = dataObject.get("line").getAsJsonArray();
            List<Position> positions = new ArrayList<>();

            for (int i = 0; i < lineArray.size(); i++) {
                JsonObject pointObj = lineArray.get(i).getAsJsonObject();
                Position position = new Position();

                // Set ID as index
                position.setId(i);

                // Set coordinates
                if (pointObj.has("x")) {
                    position.setPosX(pointObj.get("x").getAsDouble());
                }
                if (pointObj.has("y")) {
                    position.setPosY(pointObj.get("y").getAsDouble());
                }

                // Set default values for other fields
                position.setName("PathPoint_" + i);
                position.setYaw(0.0); // Default yaw
                position.setType(1); // Default type as normal point
                position.setTaskType(2); // Default as cruise task

                positions.add(position);
            }

            pathData.setLine(positions);
        }

        return pathData;
    }

    /**
     * Parse the safety areas data from JSON array
     */
    private SafetyAreasData parseSafetyAreasData(JsonArray dataArray) {
        SafetyAreasData safetyAreasData = new SafetyAreasData();
        List<SafetyArea> safetyAreas = new ArrayList<>();

        for (int i = 0; i < dataArray.size(); i++) {
            JsonElement regionElement = dataArray.get(i);
            if (regionElement.isJsonObject()) {
                JsonObject regionObj = regionElement.getAsJsonObject();

                SafetyArea safetyArea = new SafetyArea();

                // Parse region ID
                if (regionObj.has("id")) {
                    safetyArea.setId(regionObj.get("id").getAsInt());
                }

                // Parse region data which contains speed and polygons
                if (regionObj.has("data") && regionObj.get("data").isJsonArray()) {
                    JsonArray regionDataArray = regionObj.get("data").getAsJsonArray();

                    if (regionDataArray.size() > 0) {
                        // The first element contains speed information
                        JsonElement firstElement = regionDataArray.get(0);
                        if (firstElement.isJsonObject()) {
                            JsonObject speedObj = firstElement.getAsJsonObject();
                            if (speedObj.has("speed")) {
                                safetyArea.setSpeed(speedObj.get("speed").getAsFloat());
                            }
                        }

                        // Parse polygons (if any)
                        List<List<Position>> polygons = new ArrayList<>();

                        for (int j = 0; j < regionDataArray.size(); j++) {
                            JsonElement polyElement = regionDataArray.get(j);

                            // Skip the first element if it's the speed object
                            if (j == 0 && polyElement.isJsonObject() &&
                                    polyElement.getAsJsonObject().has("speed")) {
                                continue;
                            }

                            if (polyElement.isJsonArray()) {
                                JsonArray polygonArray = polyElement.getAsJsonArray();
                                List<Position> vertices = new ArrayList<>();

                                for (int k = 0; k < polygonArray.size(); k++) {
                                    JsonElement vertexElement = polygonArray.get(k);
                                    if (vertexElement.isJsonObject()) {
                                        JsonObject vertexObj = vertexElement.getAsJsonObject();
                                        Position vertex = new Position();

                                        if (vertexObj.has("x")) {
                                            vertex.setPosX(vertexObj.get("x").getAsDouble());
                                        }
                                        if (vertexObj.has("y")) {
                                            vertex.setPosY(vertexObj.get("y").getAsDouble());
                                        }

                                        vertices.add(vertex);
                                    }
                                }

                                if (!vertices.isEmpty()) {
                                    polygons.add(vertices);
                                }
                            }
                        }

                        safetyArea.setPolygons(polygons);
                    }
                }

                safetyAreas.add(safetyArea);
            }
        }

        safetyAreasData.setSafetyAreas(safetyAreas);
        Log.d(TAG, "Parsed " + safetyAreas.size() + " safety areas");
        return safetyAreasData;
    }

    private void sendCommandAsync(String command, String expectedResponseCmd, Callback callback) {
        if (!isConnected()) {
            callback.onError("Command WebSocket not connected");
            return;
        }

        String requestId = expectedResponseCmd + "_" + System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        // Store with timing info
        pendingCallbacks.put(requestId, new CommandRequest(callback, startTime, command));

        Log.d(TAG, "Registered command callback: " + requestId);

        // Log command being sent
        if (debugger != null) {
            debugger.logCommandSent(getClass().getSimpleName(), command, requestId);
        }

        // Set timeout
        mainHandler.postDelayed(() -> {
            CommandRequest request = pendingCallbacks.remove(requestId);
            if (request != null) {
                long elapsedMs = System.currentTimeMillis() - request.startTime;
                Log.w(TAG, "Command timeout: " + requestId + " after " + elapsedMs + "ms");

                if (debugger != null) {
                    debugger.logCommandTimeout(getClass().getSimpleName(),
                            expectedResponseCmd, requestId, COMMAND_TIMEOUT_MS);
                }

                if (request.callback != null) {
                    request.callback.onError("Timeout waiting for " + expectedResponseCmd);
                }
            }
        }, COMMAND_TIMEOUT_MS);

        // Send command
        boolean sent = sendCommand(command);
        if (!sent) {
            CommandRequest request = pendingCallbacks.remove(requestId);
            if (request != null && request.callback != null) {
                request.callback.onError("Failed to send command");
            }
            Log.e(TAG, "Failed to send command: " + command);
        } else {
            Log.d(TAG, "Command sent: " + command);
        }
    }

    protected boolean sendCommand(String command) {
        if (!isConnected() || webSocket == null) {
            Log.w(TAG, "WebSocket not connected, command not sent");
            return false;
        }
        boolean sent = webSocket.send(command);
        Log.d(TAG, "Command " + (sent ? "sent" : "failed") + ": " + command);
        return sent;
    }

    private void clearPendingCallbacks() {
        // Log clearing of pending callbacks
        if (debugger != null && (!pendingCallbacks.isEmpty() || !pendingStatusCallbacks.isEmpty())) {
            debugger.logWarning("Clearing pending callbacks",
                    String.format("Regular: %d, Status: %d", pendingCallbacks.size(), pendingStatusCallbacks.size()));
        }

        // Notify all pending callbacks of disconnection
        for (CommandRequest request : pendingCallbacks.values()) {
            if (request.callback != null) {
                request.callback.onError("WebSocket disconnected");
            }
        }
        for (MapListCallback callback : pendingMapCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }
        for (MarkerDataCallback callback : pendingMarkerCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }
        for (NavigationPathCallback callback : pendingNavPathCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }
        for (CurrentMapInfoCallback callback : pendingMapInfoCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }
        for (SafetyAreasCallback callback : pendingSafetyAreasCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }
        for (MapDataCallback callback : pendingMapDataCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }
        for (StatusCallback callback : pendingStatusCallbacks.values()) {
            callback.onError("WebSocket disconnected");
        }

        pendingCallbacks.clear();
        pendingMapCallbacks.clear();
        pendingMarkerCallbacks.clear();
        pendingNavPathCallbacks.clear();
        pendingMapInfoCallbacks.clear();
        pendingSafetyAreasCallbacks.clear();
        pendingNamedAreasCallbacks.clear();
        pendingMapDataCallbacks.clear();
        pendingStatusCallbacks.clear();
    }

    // Add your existing handleMapListResponse, handleMarkerDataResponse methods here...

    public static String extractMapPrefix(String fullMapName) {
        if (fullMapName.contains("_")) {
            return fullMapName.substring(0, fullMapName.lastIndexOf('_'));
        }
        return fullMapName;
    }

    public static Integer extractFloorNumber(String fullMapName, String prefix) {
        if (fullMapName == null || prefix == null) return 0;
        if (fullMapName.equals(prefix)) return 0;

        String floorPart = fullMapName.substring(prefix.length());
        if (floorPart.startsWith("_")) {
            floorPart = floorPart.substring(1);
        }

        try {
            return Integer.parseInt(floorPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Convert getChargingInfo response to MarkerData format
     */
    private MarkerData convertChargingInfoToMarkerData(JsonElement dataElement) {
        MarkerData markerData = new MarkerData();
        List<MarkerPoint> markerPoints = new ArrayList<>();

        if (dataElement.isJsonArray()) {
            JsonArray dataArray = dataElement.getAsJsonArray();
            for (JsonElement element : dataArray) {
                if (element.isJsonObject()) {
                    JsonObject chargePoint = element.getAsJsonObject();
                    MarkerPoint markerPoint = new MarkerPoint();

                    // Extract charge point data - note the field names match the response
                    if (chargePoint.has("mapname")) {
                        String mapName = chargePoint.get("mapname").getAsString();
                        markerPoint.setMapName(mapName);
//                        Log.d(TAG, "Found charge point for map: " + mapName);
                    }
                    if (chargePoint.has("x")) {
                        markerPoint.setX(chargePoint.get("x").getAsFloat());
                    }
                    if (chargePoint.has("y")) {
                        markerPoint.setY(chargePoint.get("y").getAsFloat());
                    }
                    if (chargePoint.has("theta")) { // Note: "theta" not "yaw" in response
                        markerPoint.setYaw(chargePoint.get("theta").getAsFloat());
                    }

                    // Extract prefix point data if available
                    if (chargePoint.has("prefixPoint")) {
                        JsonObject prefixPoint = chargePoint.get("prefixPoint").getAsJsonObject();
                        if (prefixPoint.has("x")) {
                            markerPoint.setPrefixPointX(prefixPoint.get("x").getAsFloat());
                        }
                        if (prefixPoint.has("y")) {
                            markerPoint.setPrefixPointY(prefixPoint.get("y").getAsFloat());
                        }
                        if (prefixPoint.has("t")) { // Note: "t" not "yaw" in prefixPoint
                            markerPoint.setPrefixPointYaw(prefixPoint.get("t").getAsFloat());
                        }
                    }

                    // Set type as charge point
                    markerPoint.setType(1); // Assuming 1 represents charge point type

                    markerPoints.add(markerPoint);
//                    Log.d(TAG, "Added charge point: " + markerPoint.getMapName() +
//                            " at (" + markerPoint.getX() + ", " + markerPoint.getY() + ")");
                }
            }
        }

        markerData.setData(markerPoints);
        Log.d(TAG, "Converted " + markerPoints.size() + " charge points to MarkerData");
        return markerData;
    }

    private static class CommandRequest {
        final Callback callback;
        final long startTime;
        final String command;

        CommandRequest(Callback callback, long startTime, String command) {
            this.callback = callback;
            this.startTime = startTime;
            this.command = command;
        }
    }

    @Override
    protected String getRobotErrorSnapshot() {
        AgvStatusResponse status = cachedStatus;
        if (status == null || status.data == null) {
            return "no-cached-status";
        }
        return String.format("errorCode=%s, errcode=%d, goalFinish=%d, poseProb=%.2f",
                status.data.errorCode != null ? status.data.errorCode.toString() : "null",
                status.data.errcode != null ? status.data.errcode : 0,
                status.data.goalFinish,
                status.data.poseProbability);
    }
}
