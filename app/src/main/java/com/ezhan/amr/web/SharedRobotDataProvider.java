package com.ezhan.amr.web;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MarkerData;
import com.ezhan.amr.data.datatype.MarkerPoint;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.NavigationPathData;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.SafetyArea;
import com.ezhan.amr.data.datatype.SafetyAreasData;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.TaskExecutor;
import com.ezhan.amr.utils.PositionDisplayNameHelper;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.TaskViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 共享数据层：从 RcsHttpService 抽取的核心数据获取与任务派发逻辑。
 * HTTP / WebSocket / Modbus 三个 Service 共同复用，保证数据源一致。
 * 方法返回与 HTTP 响应结构一致的 JSONObject，便于各协议层包装。
 */
public class SharedRobotDataProvider {

    private static final String TAG = "SharedRobotDataProvider";
    private static final long MAP_TIMEOUT_SECONDS = 15;
    private static final long TASK_TIMEOUT_SECONDS = 5;

    private static volatile SharedRobotDataProvider instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 依赖（延迟获取）
    private SharedViewModel sharedViewModel;
    private MapViewModel mapViewModel;
    private TaskViewModel taskViewModel;
    private CruiseViewModel cruiseViewModel;
    private JackViewModel jackViewModel;
    private TaskExecutor taskExecutor;
    private CommandWebSocketClient commandClient;
    private StatusWebSocketClient statusClient;

    private SharedRobotDataProvider(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static SharedRobotDataProvider getInstance(Context context) {
        if (instance == null) {
            synchronized (SharedRobotDataProvider.class) {
                if (instance == null) {
                    instance = new SharedRobotDataProvider(context);
                }
            }
        }
        return instance;
    }

    /** 每次调用前确保依赖就绪（Service 启动顺序可能导致首次为空） */
    private void ensureDependencies() {
        MyApplication app = MyApplication.getInstance();
        if (app == null) return;
        if (sharedViewModel == null) {
            sharedViewModel = app.getSharedViewModel();
            if (sharedViewModel != null) {
                commandClient = sharedViewModel.getCommandClient();
                statusClient = sharedViewModel.getStatusClient();
            }
        }
        if (mapViewModel == null) mapViewModel = app.getMapViewModel();
        if (taskViewModel == null) taskViewModel = app.getTaskViewModel();
        if (cruiseViewModel == null) cruiseViewModel = app.getCruiseViewModel();
        if (jackViewModel == null) jackViewModel = app.getJackViewModel();
        if (taskExecutor == null) taskExecutor = app.getTaskExecutor();
    }

    private JSONObject error(String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("status", "error");
            json.put("message", message);
            json.put("timestamp", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        return json;
    }

    // ======================== 机器人状态 ========================

    /** 等价 HTTP /robotStatus，返回完整响应结构 {status,taskType,data,timestamp} */
    public JSONObject getRobotStatus() {
        try {
            ensureDependencies();
            if (statusClient == null || !statusClient.isConnected()) {
                return error("Status WebSocket not connected");
            }
            AgvStatusResponse statusResponse = statusClient.getLastStatusResponse();
            if (statusResponse == null) {
                return error("No status data available yet");
            }
            int jackState = commandClient != null ? commandClient.getCachedJackState() : 0;
            JSONObject dataJson = convertStatusDataToJson(statusResponse, jackState);

            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("status", "success");
            jsonResponse.put("taskType", "status");
            jsonResponse.put("data", dataJson);
            jsonResponse.put("timestamp", System.currentTimeMillis());
            return jsonResponse;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching status", e);
            return error("Error fetching status: " + e.getMessage());
        }
    }

    private JSONObject convertStatusDataToJson(AgvStatusResponse statusResponse, int jackState)
            throws JSONException {
        JSONObject dataJson = new JSONObject();
        if (statusResponse == null || statusResponse.data == null) return dataJson;

        AgvStatusResponse.Data data = statusResponse.data;
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

        dataJson.put("agvStop", data.agvStop);
        dataJson.put("movement", data.movement);
        dataJson.put("goalFinish", data.goalFinish);
        dataJson.put("emgStop", data.emgStop);
        dataJson.put("wheelLock", data.wheelLock);
        dataJson.put("isSoftPause", data.isSoftPause);
        dataJson.put("collisionWarning", data.collisionWarning);

        dataJson.put("power", data.power);
        dataJson.put("powerQuantity", data.powerQuantity);
        dataJson.put("electricCurrentIn", data.electricCurrentIn);
        dataJson.put("electricCurrentOut", data.electricCurrentOut);
        dataJson.put("currentBv", data.currentBv);
        dataJson.put("currentFv", data.currentFv);

        dataJson.put("inNavMap", data.inNavMap);
        dataJson.put("inBuildMap", data.inBuildMap);
        dataJson.put("currentGlobalId", data.currentGlobalId);
        dataJson.put("poseProbability", data.poseProbability);

        dataJson.put("robotId", data.robotId != null ? data.robotId : "");
        dataJson.put("version", data.version != null ? data.version : "");
        dataJson.put("odomHz", data.odomHz);
        dataJson.put("scanHz", data.scanHz);

        if (data.errorCode != null && !data.errorCode.isEmpty()) {
            JSONArray errorArray = new JSONArray();
            for (Integer err : data.errorCode) errorArray.put(err);
            dataJson.put("errorCode", errorArray);
        } else {
            dataJson.put("errorCode", new JSONArray());
        }
        dataJson.put("errCode", data.errCode);

        dataJson.put("chargeStep", data.chargeStep);
        dataJson.put("inManualCharge", data.inManualCharge);

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

        if (data.chargeStatus != null) {
            AgvStatusResponse.ChargeStatus charge = data.chargeStatus;
            JSONObject chargeJson = new JSONObject();
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

        if (statusClient != null) {
            StatusWebSocketClient.ConnectionHealth health = statusClient.getConnectionHealth();
            JSONObject healthJson = new JSONObject();
            healthJson.put("isConnected", health.isConnected);
            healthJson.put("hasReceivedStatus", health.hasReceivedStatus);
            healthJson.put("timeSinceLastMessage", health.timeSinceLastMessage);
            healthJson.put("timeSinceLastStatus", health.timeSinceLastStatus);
            dataJson.put("connectionHealth", healthJson);
        }
        return dataJson;
    }

    private void putTaskId(JSONObject dataJson, String taskId) throws JSONException {
        String normalized = taskId == null ? "" : taskId.trim();
        dataJson.put("taskId", normalized.isEmpty() ? TaskViewModel.TASK_INFO_NONE : normalized);
    }

    private String resolveCurrentLocationText(AgvStatusResponse.DataPosition currentPos) {
        if (currentPos == null) return "";
        Position nearbyPoint = findNearbyPoint(currentPos);
        if (nearbyPoint != null) {
            String displayName = PositionDisplayNameHelper.getDisplayName(appContext, nearbyPoint);
            if (!displayName.trim().isEmpty()) return displayName.trim();
        }
        return String.format(Locale.US, "x=%.2f, y=%.2f", currentPos.x, currentPos.y);
    }

    private Position findNearbyPoint(AgvStatusResponse.DataPosition currentPos) {
        if (currentPos == null || mapViewModel == null) return null;
        String currentMap = currentPos.mapName;
        if (currentMap == null || currentMap.trim().isEmpty()) return null;
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) return null;
        String buildingId = mapViewModel.extractBuildingId(currentMap);
        int currentFloor = mapViewModel.extractMapFloor(currentMap);
        Building building = mapPoints.getBuilding(buildingId);
        FloorPoints floorPoints = building != null ? building.getFloorPoints(currentFloor) : null;
        if (floorPoints == null) return null;

        Position nearestPoint = null;
        double nearestDistance = Double.MAX_VALUE;
        final double threshold = 0.5;
        for (Position point : floorPoints.getAllPoints()) {
            double distance = calculateDistanceToRobot(currentPos, point, currentMap);
            if (distance <= threshold && distance < nearestDistance) {
                nearestDistance = distance;
                nearestPoint = point;
            }
        }
        return nearestPoint;
    }

    private double calculateDistanceToRobot(AgvStatusResponse.DataPosition currentPos,
                                             Position point, String currentMap) {
        if (currentPos == null || point == null || point.getName() == null
                || point.getName().trim().isEmpty()) return Double.MAX_VALUE;
        if (!isSameMapForCurrentLocation(currentMap, point.getMapName())) return Double.MAX_VALUE;
        double dx = currentPos.x - point.getPosX();
        double dy = currentPos.y - point.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean isSameMapForCurrentLocation(String currentMap, String pointMap) {
        if (currentMap == null || currentMap.trim().isEmpty()
                || pointMap == null || pointMap.trim().isEmpty()) return true;
        return normalizeMapNameForCurrentLocation(currentMap)
                .equals(normalizeMapNameForCurrentLocation(pointMap));
    }

    private String normalizeMapNameForCurrentLocation(String mapName) {
        return mapName == null ? "" : mapName.trim().replaceFirst("\\.[^.]+$", "");
    }

    // ======================== 地图数据 ========================

    /** 等价 HTTP /allMapPoints */
    public JSONObject getAllMapPoints() {
        try {
            ensureDependencies();
            if (commandClient == null || !commandClient.isConnected()) {
                return error("Command WebSocket not connected");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final MarkerData[] resultHolder = new MarkerData[1];
            final String[] errorHolder = new String[1];
            mainHandler.post(() -> commandClient.getAllMarkersAsync(new CommandWebSocketClient.MarkerDataCallback() {
                @Override
                public void onSuccess(MarkerData markerData) {
                    resultHolder[0] = markerData;
                    latch.countDown();
                }

                @Override
                public void onError(String e) {
                    errorHolder[0] = e;
                    latch.countDown();
                }
            }));
            if (!latch.await(MAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return error("Timeout waiting for points data");
            }
            if (errorHolder[0] != null) return error("Error fetching points: " + errorHolder[0]);

            JSONObject json = new JSONObject();
            json.put("status", "success");
            json.put("taskType", "points");
            json.put("data", convertMarkerDataToJson(resultHolder[0]));
            json.put("timestamp", System.currentTimeMillis());
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching points", e);
            return error("Error fetching points: " + e.getMessage());
        }
    }

    private JSONObject convertMarkerDataToJson(MarkerData markerData) throws JSONException {
        JSONObject dataJson = new JSONObject();
        if (markerData != null && markerData.getData() != null) {
            JSONArray pointsArray = new JSONArray();
            for (MarkerPoint point : markerData.getData()) {
                JSONObject p = new JSONObject();
                p.put("name", point.getName());
                p.put("x", point.getX());
                p.put("y", point.getY());
                p.put("yaw", point.getYaw());
                p.put("type", point.getType());
                p.put("mapName", point.getMapName());
                p.put("floor", point.getFloor());
                pointsArray.put(p);
            }
            dataJson.put("points", pointsArray);
        }
        return dataJson;
    }

    /** 等价 HTTP /currentMapInfo */
    public JSONObject getCurrentMapInfo() {
        try {
            ensureDependencies();
            if (commandClient == null || !commandClient.isConnected()) {
                return error("Command WebSocket not connected");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] mapName = new String[1];
            final Double[] resolution = new Double[1];
            final Integer[] width = new Integer[1];
            final Integer[] height = new Integer[1];
            final double[][] mapPosition = new double[1][3];
            final String[] format = new String[1];
            final String[] time = new String[1];
            final String[] mapImage = new String[1];
            final String[] err = new String[1];
            mainHandler.post(() -> commandClient.getCurrentMapInfoAsync(new CommandWebSocketClient.CurrentMapInfoCallback() {
                @Override
                public void onSuccess(String n, double r, int w, int h, double[] pos, String f, String t, String img) {
                    mapName[0] = n; resolution[0] = r; width[0] = w; height[0] = h;
                    mapPosition[0] = pos != null ? pos : new double[3];
                    format[0] = f; time[0] = t; mapImage[0] = img;
                    latch.countDown();
                }

                @Override
                public void onError(String e) {
                    err[0] = e;
                    latch.countDown();
                }
            }));
            if (!latch.await(MAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return error("Timeout waiting for current map info");
            }
            if (err[0] != null) return error("Error fetching current map info: " + err[0]);
            if (mapName[0] == null || mapName[0].trim().isEmpty()) return error("Current map name is empty");
            if (mapImage[0] == null || mapImage[0].isEmpty()) return error("Current map image data is empty");

            JSONObject json = new JSONObject();
            json.put("status", "success");
            json.put("taskType", "currentMapInfo");
            json.put("timestamp", System.currentTimeMillis());
            JSONObject data = new JSONObject();
            data.put("name", mapName[0]);
            if (resolution[0] != null) data.put("resolution", resolution[0]);
            if (width[0] != null) data.put("width", width[0]);
            if (height[0] != null) data.put("height", height[0]);
            if (mapPosition[0] != null) {
                JSONArray posArray = new JSONArray();
                for (double p : mapPosition[0]) posArray.put(p);
                data.put("mappos", posArray);
            }
            if (format[0] != null) data.put("format", format[0]);
            if (time[0] != null) data.put("time", time[0]);
            data.put("map", mapImage[0]);
            json.put("data", data);
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching current map info", e);
            return error("Error fetching current map info: " + e.getMessage());
        }
    }

    /** 等价 HTTP /safetyAreas */
    public JSONObject getSafetyAreas() {
        try {
            ensureDependencies();
            if (commandClient == null || !commandClient.isConnected()) {
                return error("Command WebSocket not connected");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final SafetyAreasData[] resultHolder = new SafetyAreasData[1];
            final String[] err = new String[1];
            mainHandler.post(() -> commandClient.getSafetyAreasAsync(new CommandWebSocketClient.SafetyAreasCallback() {
                @Override
                public void onSuccess(SafetyAreasData safetyAreasData) {
                    resultHolder[0] = safetyAreasData;
                    latch.countDown();
                }

                @Override
                public void onError(String e) {
                    err[0] = e;
                    latch.countDown();
                }
            }));
            if (!latch.await(MAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return error("Timeout waiting for safety areas data");
            }
            if (err[0] != null) return error("Error fetching safety areas: " + err[0]);

            JSONObject json = new JSONObject();
            json.put("status", "success");
            json.put("taskType", "safetyAreas");
            json.put("timestamp", System.currentTimeMillis());
            json.put("data", convertSafetyAreasDataToJson(resultHolder[0]));
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching safety areas", e);
            return error("Error fetching safety areas: " + e.getMessage());
        }
    }

    private JSONArray convertSafetyAreasDataToJson(SafetyAreasData safetyAreasData) throws JSONException {
        JSONArray allTypesArray = new JSONArray();
        for (int typeId = 0; typeId <= 5; typeId++) {
            JSONObject typeEntry = new JSONObject();
            typeEntry.put("id", typeId);
            SafetyArea area = findSafetyAreaById(safetyAreasData, typeId);
            if (area != null && area.getPolygons() != null && !area.getPolygons().isEmpty()) {
                JSONArray dataArray = new JSONArray();
                JSONObject speedInfo = new JSONObject();
                speedInfo.put("speed", area.getSpeed());
                dataArray.put(speedInfo);
                for (List<Position> polygon : area.getPolygons()) {
                    JSONArray polygonArray = new JSONArray();
                    for (Position vertex : polygon) {
                        JSONObject v = new JSONObject();
                        v.put("x", vertex.getPosX());
                        v.put("y", vertex.getPosY());
                        polygonArray.put(v);
                    }
                    if (polygonArray.length() > 0) dataArray.put(polygonArray);
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
        return allTypesArray;
    }

    private SafetyArea findSafetyAreaById(SafetyAreasData safetyAreasData, int id) {
        if (safetyAreasData == null || safetyAreasData.getSafetyAreas() == null) return null;
        for (SafetyArea area : safetyAreasData.getSafetyAreas()) {
            if (area.getId() == id) return area;
        }
        return null;
    }

    /** 等价 HTTP /navigatePath */
    public JSONObject getNavigatePath() {
        try {
            ensureDependencies();
            if (commandClient == null || !commandClient.isConnected()) {
                return error("Command WebSocket not connected");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final NavigationPathData[] resultHolder = new NavigationPathData[1];
            final String[] err = new String[1];
            mainHandler.post(() -> commandClient.getNavigatePathAsync(new CommandWebSocketClient.NavigationPathCallback() {
                @Override
                public void onSuccess(NavigationPathData pathData) {
                    resultHolder[0] = pathData;
                    latch.countDown();
                }

                @Override
                public void onError(String e) {
                    err[0] = e;
                    latch.countDown();
                }
            }));
            if (!latch.await(MAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return error("Timeout waiting for path data");
            if (err[0] != null) return error("Error fetching path: " + err[0]);

            String mapName = "";
            if (statusClient != null) {
                AgvStatusResponse statusResponse = statusClient.getLastStatusResponse();
                if (statusResponse != null && statusResponse.data != null && statusResponse.data.pos != null) {
                    mapName = statusResponse.data.pos.mapName != null ? statusResponse.data.pos.mapName : "";
                }
            }
            JSONObject json = new JSONObject();
            json.put("mapName", mapName);
            if (resultHolder[0] != null && resultHolder[0].getLine() != null) {
                JSONArray lineArray = new JSONArray();
                for (Position position : resultHolder[0].getLine()) {
                    JSONObject p = new JSONObject();
                    p.put("x", position.getPosX());
                    p.put("y", position.getPosY());
                    p.put("speed", position.getSpeed());
                    lineArray.put(p);
                }
                json.put("line", lineArray);
            }
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching path", e);
            return error("Error fetching path: " + e.getMessage());
        }
    }

    // ======================== 任务查询 ========================

    /** 等价 HTTP /rcsTaskStatus?taskId= */
    public JSONObject getRcsTaskStatus(final String taskId) {
        try {
            ensureDependencies();
            if (taskViewModel == null) return error("Task ViewModel not available");
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] taskStatus = new String[1];
            final String[] err = new String[1];
            mainHandler.post(() -> {
                try {
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();
                    TaskViewModel.TaskState currentTaskState = taskViewModel.getCurrentTaskStateSnapshot();
                    if (currentTaskState != null
                            && taskId.equals(currentTaskState.taskId)
                            && !TaskViewModel.TASK_INFO_NONE.equals(currentTaskState.status)) {
                        taskStatus[0] = currentTaskState.status;
                    }
                    if (taskRecords != null && !taskRecords.isEmpty()) {
                        for (TaskRecord taskRecord : taskRecords) {
                            String recordTaskId = taskRecord.getTaskId();
                            String recordTaskName = taskRecord.getTaskName();
                            if ((recordTaskId != null && recordTaskId.trim().equals(taskId))
                                    || (recordTaskName != null && recordTaskName.equals("RCS-" + taskId))) {
                                taskStatus[0] = taskRecord.getStatus();
                                break;
                            }
                        }
                    }
                    if (taskStatus[0] == null) err[0] = "Task with ID '" + taskId + "' not found";
                    latch.countDown();
                } catch (Exception e) {
                    err[0] = "Error searching for task: " + e.getMessage();
                    latch.countDown();
                }
            });
            if (!latch.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return error("Timeout searching for task");
            if (err[0] != null) return error(err[0]);

            JSONObject json = new JSONObject();
            json.put("status", "success");
            json.put("taskType", "rcsTaskStatus");
            json.put("timestamp", System.currentTimeMillis());
            JSONObject data = new JSONObject();
            data.put(taskId, taskStatus[0]);
            json.put("data", data);
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching RCS task status", e);
            return error("Error fetching task status: " + e.getMessage());
        }
    }

    /** 等价 HTTP /hmiTaskStatus?dateTime= */
    public JSONObject getHmiTaskStatus(final String dateTime) {
        try {
            ensureDependencies();
            if (taskViewModel == null) return error("Task ViewModel not available");
            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            final Date targetDate;
            try {
                targetDate = dateFormat.parse(dateTime);
            } catch (ParseException e) {
                return error("Invalid date format. Expected: yyyy-MM-dd");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final List<TaskRecord> matchingTasks = new ArrayList<>();
            final String[] err = new String[1];
            mainHandler.post(() -> {
                try {
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();
                    if (taskRecords == null || taskRecords.isEmpty()) {
                        err[0] = "No task records found";
                        latch.countDown();
                        return;
                    }
                    Calendar targetCalendar = Calendar.getInstance();
                    targetCalendar.setTime(targetDate);
                    Calendar taskCalendar = Calendar.getInstance();
                    for (TaskRecord taskRecord : taskRecords) {
                        if (taskRecord.getStartTime() == 0) continue;
                        String taskName = taskRecord.getTaskName();
                        if (taskName == null || !taskName.toUpperCase().contains("HMI")) continue;
                        taskCalendar.setTimeInMillis(taskRecord.getStartTime());
                        if (taskCalendar.get(Calendar.YEAR) == targetCalendar.get(Calendar.YEAR)
                                && taskCalendar.get(Calendar.MONTH) == targetCalendar.get(Calendar.MONTH)
                                && taskCalendar.get(Calendar.DAY_OF_MONTH) == targetCalendar.get(Calendar.DAY_OF_MONTH)) {
                            matchingTasks.add(taskRecord);
                        }
                    }
                    if (matchingTasks.isEmpty()) err[0] = "No HMI tasks found for date: " + dateTime;
                    latch.countDown();
                } catch (Exception e) {
                    err[0] = "Error searching for tasks: " + e.getMessage();
                    latch.countDown();
                }
            });
            if (!latch.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return error("Timeout searching for tasks");
            if (err[0] != null) return error(err[0]);

            JSONObject json = new JSONObject();
            json.put("status", "success");
            json.put("taskType", "hmiTaskStatus");
            json.put("timestamp", System.currentTimeMillis());
            json.put("date", dateTime);
            json.put("count", matchingTasks.size());
            JSONObject data = new JSONObject();
            for (TaskRecord task : matchingTasks) {
                JSONObject details = new JSONObject();
                details.put("status", task.getStatus());
                details.put("type", task.getType());
                details.put("startTime", task.getStartTime());
                details.put("endTime", task.getEndTime());
                details.put("duration", task.getDuration());
                data.put(task.getTaskName(), details);
            }
            json.put("data", data);
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching HMI task status", e);
            return error("Error fetching tasks: " + e.getMessage());
        }
    }

    /**
     * 等价 HTTP /hmiTaskStatusDetails。
     * 简化版：返回匹配任务的基础信息（完整 stationDetails 分支逻辑复杂，后续按需补全）。
     */
    public JSONObject getHmiTaskStatusDetails(final String taskId, final String dateTime,
                                              final String startTime, final String endTime) {
        try {
            ensureDependencies();
            if (taskViewModel == null) return error("Task ViewModel not available");
            final CountDownLatch latch = new CountDownLatch(1);
            final TaskRecord[] matched = new TaskRecord[1];
            final String[] err = new String[1];
            mainHandler.post(() -> {
                try {
                    List<TaskRecord> taskRecords = taskViewModel.getTaskRecords().getValue();
                    if (taskRecords == null || taskRecords.isEmpty()) {
                        err[0] = "No task records found";
                        latch.countDown();
                        return;
                    }
                    TaskRecord best = null;
                    long bestTime = -1;
                    for (TaskRecord r : taskRecords) {
                        String rid = r.getTaskId();
                        if (rid == null || !rid.trim().equals(taskId)) continue;
                        long ct = r.getCreateTime();
                        if (best == null || ct > bestTime) {
                            best = r;
                            bestTime = ct;
                        }
                    }
                    if (best == null) err[0] = "Task not found: " + taskId;
                    matched[0] = best;
                    latch.countDown();
                } catch (Exception e) {
                    err[0] = "Error: " + e.getMessage();
                    latch.countDown();
                }
            });
            if (!latch.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return error("Timeout");
            if (err[0] != null) return error(err[0]);

            JSONObject json = new JSONObject();
            json.put("type", "hmiTaskStatusDetails");
            JSONObject data = new JSONObject();
            data.put("requestId", "");
            data.put("type", matched[0].getType());
            data.put("date", dateTime != null ? dateTime : "");
            data.put("taskStatus", matched[0].getStatus());
            data.put("taskName", matched[0].getTaskName() != null ? matched[0].getTaskName() : "");
            data.put("createTime", matched[0].getCreateTime());
            data.put("taskId", matched[0].getTaskId() != null ? matched[0].getTaskId() : "");
            data.put("stationDetails", new JSONArray());
            json.put("data", data);
            return json;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching task details", e);
            return error("Error fetching task details: " + e.getMessage());
        }
    }

    // ======================== 任务派发 ========================

    /**
     * 派发任务，复用 TaskExecutor（含电量检查）与 BroadcastHelper。
     * @param taskType 任务类型：delivery/cruise/jack/charge/park/cancel/pause/resume/release/areaset
     * @param taskName 任务名/点位名（用于 delivery/cruise/jack/charge/park）
     * @param params   附加参数（areaset 使用 JSONObject）
     * @return {status, message}
     */
    public JSONObject dispatchTask(final String taskType, final String taskName, final JSONObject params) {
        try {
            ensureDependencies();
            String type = taskType == null ? "" : taskType.toLowerCase();
            switch (type) {
                case "cancel":
                case "pause":
                case "resume":
                case "release": {
                    BroadcastHelper helper = MyApplication.getInstance().getBroadcastHelper();
                    if (helper == null) return error("BroadcastHelper not available");
                    if ("cancel".equals(type)) helper.sendCancelCommand();
                    else if ("pause".equals(type)) helper.sendPauseCommand();
                    else if ("resume".equals(type)) helper.sendResumeCommand();
                    else helper.sendReleaseCommand();
                    JSONObject json = new JSONObject();
                    json.put("status", "success");
                    json.put("message", type + " command sent");
                    return json;
                }
                case "areaset": {
                    if (commandClient == null || !commandClient.isConnected())
                        return error("Command WebSocket not connected");
                    commandClient.setArea(params);
                    JSONObject json = new JSONObject();
                    json.put("status", "success");
                    json.put("message", "areaset command sent");
                    return json;
                }
                case "delivery":
                case "cruise":
                case "jack":
                case "charge":
                case "park": {
                    if (taskExecutor == null) return error("TaskExecutor not available");
                    final CountDownLatch latch = new CountDownLatch(1);
                    final JSONObject[] result = new JSONObject[1];
                    // 使用 executeByTaskTypeAndName 确保按指定类型匹配，避免类型混淆
                    taskExecutor.executeByTaskTypeAndName(type, taskName, new TaskExecutor.TaskExecutionCallback() {
                        @Override
                        public void onTaskMatched(String t, String n) {
                            markSuccess("task matched: " + n);
                        }

                        @Override
                        public void onTaskStarted(String t, String n) {
                            markSuccess("task started: " + n);
                        }

                        @Override
                        public void onTaskCompleted(String t, String n) {
                            // 不等待任务完成（可能耗时很长），仅记录
                        }

                        @Override
                        public void onTaskFailed(String t, String n, String reason) {
                            result[0] = error(reason);
                            latch.countDown();
                        }

                        private void markSuccess(String msg) {
                            if (result[0] == null) {
                                try {
                                    result[0] = new JSONObject();
                                    result[0].put("status", "success");
                                    result[0].put("message", msg);
                                } catch (JSONException ignored) {
                                }
                                latch.countDown();
                            }
                        }
                    });
                    if (!latch.await(TASK_TIMEOUT_SECONDS * 6, TimeUnit.SECONDS)) {
                        // 任务派发通常立即返回，超时视为已接受（异步执行）
                        JSONObject json = new JSONObject();
                        json.put("status", "accepted");
                        json.put("message", "task dispatched (no callback within timeout)");
                        return json;
                    }
                    return result[0] != null ? result[0] : error("Unknown task result");
                }
                default:
                    return error("Unsupported task type: " + taskType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching task", e);
            return error("Error dispatching task: " + e.getMessage());
        }
    }
}
