package com.ezhan.amr.web;

import static com.ezhan.amr.navigation.task.NavigationEventType.CHARGE;
import static com.ezhan.amr.navigation.task.NavigationEventType.DELIVERY_TASK;
import static com.ezhan.amr.navigation.task.NavigationEventType.JACK_TASK;
import static com.ezhan.amr.navigation.task.NavigationEventType.PARK;
import static com.ezhan.amr.ui.MainActivity.DEFAULT_CONFIDENCE;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.ErrorCode;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.MapPoints;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.BatteryLevelManager;
import com.ezhan.amr.navigation.BatteryState;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.navigation.TaskExecutor;
import com.ezhan.amr.navigation.task.NavigationEventType;
import com.ezhan.amr.ui.BaseActivity;
import com.ezhan.amr.utils.LocaleHelper;
import com.ezhan.amr.utils.PositionDisplayNameHelper;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.TaskViewModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;

public class WebService extends Service {
    private static final String TAG = "WebService";
    private static final String JACK_TASK_OPTION_PREFIX = "jack:";
    private static final String CRUISE_TASK_OPTION_PREFIX = "cruise:";
    private static final int DEFAULT_QUEUED_TASK_COOLDOWN_SECONDS = 6;
    /** 用户屏幕交互后多久内不触发闲时充电/闲时回待命（ms） */
    private static final long USER_INTERACTION_SUPPRESS_MS = 30_000L;
    private NanoHTTPD webServer;
    private SharedViewModel sharedViewModel;
    private MapViewModel mapViewModel;
    private JackViewModel jackViewModel;
    private CruiseViewModel cruiseViewModel;
    private BasicViewModel basicViewModel;
    private TaskViewModel taskViewModel;
    private GeneralNavigationHandler navigationHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebHtmlBuilder htmlBuilder;
    private TaskExecutor taskExecutor;
    AgvStatusResponse latestStatusResponse;
    private StatusWebSocketClient.StatusListener statusListener;
    private final GeneralNavigationHandler.TaskCompletionListener taskCompletionListener =
            new GeneralNavigationHandler.TaskCompletionListener() {
                @Override
                public void onTaskCompleted() {
                    scheduleNextQueuedTask("task completed");
                }

                @Override
                public void onTaskCancelled() {
                    handleCancelOperation();
                    scheduleNextQueuedTask("task cancelled");
                }

                @Override
                public void onTaskFailed() {
                    resetIdleReturnHomeState();
                    scheduleNextQueuedTask("task failed");
                }
            };

    private Map<Integer, JackTask> jackTaskMap = new LinkedHashMap<>();
    private Map<Integer, CruiseTask> cruiseTaskMap = new LinkedHashMap<>();
    private ElevatorViewModel elevatorViewModel;
    private int port = 8080;
    private String selectedStationId; // 存储用户选择的站点ID
    // 使用资源ID替代字符串成员变量
    private int currentErrorMsgResId = R.string.no_error;
    private boolean currentErrorMsgFromRobotStatus = false;
    private int currentOperationResId = R.string.no_operation;
    private Object[] operationFormatArgs = null; // 用于存储格式化参数
    private String robotInfo = "";
    private String targetId = "";
    private int confidence;
    private int batteryLevel;
    private Boolean emergency;
    private int workStatus;
    private int lastWorkStatus = -1;
    private String pos;
    private int stateVoiceTick = 0;
    private long idleChargeEligibleSinceMillis = 0L;
    private boolean idleChargeTriggeredForCurrentIdlePeriod = false;
    private boolean autoParkAfterFullChargeTriggered = false;
    private long lastIdleChargeBlockedLogMillis = 0L;
    // 闲时回待命功能状态
    private long idleReturnHomeEligibleSinceMillis = 0L;
    private boolean idleReturnHomeTriggeredForCurrentIdlePeriod = false;
    private long lastIdleReturnHomeBlockedLogMillis = 0L;
    private final int default_confidence = (int) (DEFAULT_CONFIDENCE * 100);
    private int currentExecutingTaskId = -1;
    private Boolean executeMode = false;
    private boolean isServerStarted = false; // 新增服务器状态标志
    private NavigationEventType currentEventType = null;//记录当前任务类型
    // Task queue related
    private LinkedHashMap<Integer, QueuedTask> taskQueue = new LinkedHashMap<>();
    private int queueCounter = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService();
        }
        // 强制更新语言环境
        String lang = LocaleHelper.getLanguage(this);
        LocaleHelper.applyNewLocale(this, lang);
        // 更新资源
        Resources res = getApplicationContext().getResources();
        LocaleHelper.updateServiceResources(getApplicationContext(), res);
        // 确保 Context 有效后再操作资源
        MyApplication.getInstance().bindWebService(this);
        Context appContext = getApplicationContext();
        if (appContext != null) {
            LocaleHelper.updateServiceResources(appContext, appContext.getResources());
        } else {
            Log.e(TAG, getString(R.string.error_application_context_null));
        }
        // 清理内存垃圾
        System.gc();
        System.runFinalization();
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        jackViewModel = MyApplication.getInstance().getJackViewModel();
        cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
        basicViewModel = MyApplication.getInstance().getBasicViewModel();
        taskViewModel = MyApplication.getInstance().getTaskViewModel();
        elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        startStatusMonitoring();

        Map<Integer, JackTask> currentJackTasks = jackViewModel.getJackTaskMap().getValue();
        if (currentJackTasks != null) {
            jackTaskMap = new LinkedHashMap<>(currentJackTasks);
        }
        Map<Integer, CruiseTask> currentCruiseTasks = cruiseViewModel.getCruiseTaskMap().getValue();
        if (currentCruiseTasks != null) {
            cruiseTaskMap = new LinkedHashMap<>(currentCruiseTasks);
        }

        // Periodic refresh instead of continuous observation
        setupPeriodicDataRefresh();

        sharedViewModel.initNavigationHandler();
        navigationHandler = sharedViewModel.getNavigationHandler();

        if (navigationHandler != null) {
            navigationHandler.addTaskCompletionListener(taskCompletionListener);
            navigationHandler.startMonitoring();
            // Check if status fetcher is initialized
            Log.d(TAG, "Registering as internal status listener");
            if (!navigationHandler.isWebSocketConnected()) {
                Log.w("WebService", "WebSocket not connected - status callback may not be set");
                // You might need to trigger setup elsewhere
            }
        }

        htmlBuilder = new WebHtmlBuilder(this, workStatus, batteryLevel, emergency,
                targetId, confidence, pos,
                getLocalizedErrorMsg(), getFormattedOperation());

        // 仅在首次创建时启动服务器
        if (!isServerStarted) {
            startWebServer();
        }

        taskExecutor = MyApplication.getInstance().getTaskExecutor();

        // 初始化资源ID
        currentErrorMsgResId = R.string.no_error;
        currentOperationResId = R.string.no_operation;
        operationFormatArgs = null;
    }

    @Override
    public void onDestroy() {
        if (navigationHandler != null) {
            navigationHandler.removeTaskCompletionListener(taskCompletionListener);
        }
        MyApplication.getInstance().bindWebService(null);
        // 重置服务器状态
        isServerStarted = false;
        port = 8080; // 重置端口
        // 安全停止Web服务器
        if (webServer != null) {
            webServer.stop();
            webServer = null; // 防止重复停止
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    public int getPort() {
        return port; // 返回实际使用的端口
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startForegroundService() {
        String channelId = "web_service_channel";
        NotificationChannel channel = new NotificationChannel(
                channelId,
                "Web Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Service Running")
                .setContentText("Your service is running in foreground")
                .build();

        startForeground(1, notification); // Unique ID for each service
    }
    // ---------------------------------------------------------------------------------------------
    private void startStatusMonitoring() {
        statusListener = new StatusWebSocketClient.StatusListener() {
            @Override
            public void onStatusUpdate(AgvStatusResponse response) {
                if (response != null && response.data != null) {
                    latestStatusResponse = response;
                    updateStatusFromResponse(response);
                    if (response.data.goalFinish == 1) {
                        // 任务完成
                        currentOperationResId = R.string.no_operation;
                        operationFormatArgs = null;
                    }
                }
            }
        };
        sharedViewModel.registerStatusListener(statusListener);
        Log.d(TAG, "Registering listener with SharedViewModel");
    }

    private void updateStatusFromResponse(AgvStatusResponse response) {
        lastWorkStatus = workStatus;
        workStatus = response.data.goalFinish;
        confidence = (int)(response.data.poseProbability * 100);
        emergency = response.data.emgStop;
        batteryLevel = response.data.powerQuantity;

        // Feed battery status to BatteryLevelManager state machine
        BatteryState prevBatteryState = BatteryLevelManager.getInstance().getCurrentState();
        BatteryState newBatteryState = BatteryLevelManager.getInstance().updateStatus(response);

        // Handle CRITICAL battery state transition: cancel task + go charge immediately
        if (newBatteryState == BatteryState.CRITICAL && prevBatteryState != BatteryState.CRITICAL) {
            Log.w("BatteryManager", "CRITICAL battery level detected (" + batteryLevel +
                    "%), cancelling current task and starting emergency charge");
            if (navigationHandler != null && navigationHandler.isTaskRunning()) {
                navigationHandler.cancelNavigation(true);
            }
            // Start charge navigation (skipBusyCheck=true to bypass task-running check)
            handleNavigationEvent(CHARGE, null, null, true);
        }

        if (response.data.isSoftPause) {
            currentOperationResId = R.string.operation_paused;
            operationFormatArgs = null;
        }

        // 检测导航状态变化并更新操作显示
        if (workStatus == 0 && lastWorkStatus != 0) {
            // 任务开始运行，显示"正在执行导航任务中"
            if (currentOperationResId == R.string.no_operation ||
                    currentOperationResId == R.string.operation_completed ||
                    currentOperationResId == R.string.operation_canceled ||
                    currentOperationResId == R.string.operation_paused) {
                currentOperationResId = getOperationResIdForCurrentTask();
                operationFormatArgs = null;
            }
        }

        pos = buildCurrentLocationText(response);
        robotInfo = getStateString(response.data.goalFinish) + "     " +
                confidence + "     " + targetId + "     " +
                batteryLevel + "     " + emergency + "\n" + pos;

        // 电量管理代码 - use BatteryLevelManager thresholds
        int low_power = BatteryLevelManager.getInstance().getMinWorkingLevel();

        // 低电量语音播报已由 ChargingManager 统一处理
        // 2 低电量、空闲、定位稳定、置信度正常、无报错、未充电
        evaluateIdleAutoCharge(response, low_power);
        evaluateIdleReturnHome(response);
        if (shouldRunLegacyAutoCharge(response, low_power)) {
            // 事件触发充电任务
            handleNavigationEvent(CHARGE, null, null);
        }
        // 3 充满电且充电中，返回待命点（需开启「充满电回待命」）
        boolean isCharging = response.data.electricCurrentIn >= 0.01f;
        int fullLevel = BatteryLevelManager.getInstance().getFullLevel();
        boolean fullChargeReturnHomeEnabled = Boolean.TRUE.equals(
                basicViewModel.getIsFullChargeReturnHome().getValue());
        boolean fullTriggerReset = (!fullChargeReturnHomeEnabled || !isCharging || batteryLevel < fullLevel);
        if (fullTriggerReset) {
            if (autoParkAfterFullChargeTriggered) {
                TaskDebug8.log(String.format("[FULL-PARK] RESET triggered=false reason=disabled=%b notCharging=%b batteryBelow=%d<%d",
                        !fullChargeReturnHomeEnabled, !isCharging, batteryLevel, fullLevel));
            }
            autoParkAfterFullChargeTriggered = false;
        }
        boolean fullTriggerCondition = fullChargeReturnHomeEnabled
                && !autoParkAfterFullChargeTriggered
                && batteryLevel >= fullLevel && isCharging && response.data.goalFinish != 0
                && confidence > default_confidence && !emergency && stateVoiceTick >= 19;
        TaskDebug8.logThrottled("full_park_check", 3000, String.format(
                "[FULL-PARK] check battery=%d fullLevel=%d isCharging=%b enabled=%b goalFinish=%d conf=%d emg=%b tick=%d cond=%b",
                batteryLevel, fullLevel, isCharging, fullChargeReturnHomeEnabled,
                response.data.goalFinish, confidence, emergency, stateVoiceTick, fullTriggerCondition));
        if (fullTriggerCondition) {
            // 事件触发驻车任务
            autoParkAfterFullChargeTriggered = true;
            TaskDebug8.log(String.format("[FULL-PARK] TRIGGER PARK battery=%d fullLevel=%d isCharging=%b goalFinish=%d tick=%d",
                    batteryLevel, fullLevel, isCharging, response.data.goalFinish, stateVoiceTick));
            handleNavigationEvent(PARK, null, null);
        }
        // 急停报警
        updateRobotStatusAlert(response);

        // 无报错时递增 tick 计数器，用于延迟触发类逻辑
        if (getRobotStatusAlertResId(response) == R.string.no_error) {
            stateVoiceTick++;
        }
    }

    private void updateRobotStatusAlert(AgvStatusResponse response) {
        int alertResId = getRobotStatusAlertResId(response);
        if (alertResId != R.string.no_error) {
            stateVoiceTick = 0;
            currentErrorMsgResId = alertResId;
            currentErrorMsgFromRobotStatus = true;
        } else if (currentErrorMsgFromRobotStatus) {
            currentErrorMsgResId = R.string.no_error;
            currentErrorMsgFromRobotStatus = false;
        }
    }

    private boolean shouldRunLegacyAutoCharge(AgvStatusResponse response, int lowPowerThreshold) {
        return false;
    }

    private void evaluateIdleAutoCharge(AgvStatusResponse response, int lowPowerThreshold) {
        if (response == null || response.data == null) {
            resetIdleChargeState();
            return;
        }

        boolean idleOrCompleted = response.data.goalFinish == -2 || response.data.goalFinish == 1;
        boolean chassisRunning = response.data.goalFinish == 0;
        boolean taskRunning = navigationHandler != null && navigationHandler.isTaskRunning() && chassisRunning;
        boolean charging = response.data.electricCurrentIn >= 0.01f;
        boolean statusEligible = Boolean.TRUE.equals(basicViewModel.getIsIdleCharge().getValue())
                && idleOrCompleted
                && !taskRunning
                && batteryLevel < BatteryLevelManager.getInstance().getIdleLevel()
                && batteryLevel >= BatteryLevelManager.getInstance().getMinWorkingLevel()
                && confidence > default_confidence
                && !Boolean.TRUE.equals(emergency)
                && !charging
                && response.data.inManualCharge <= 0
                && !response.data.isSoftPause
                && getRobotStatusAlertResId(response) == R.string.no_error
                && !isUserRecentlyInteracting();

        if (!statusEligible) {
            logIdleChargeBlockedIfNeeded(response, idleOrCompleted, taskRunning, charging);
            resetIdleChargeState();
            return;
        }

        long now = System.currentTimeMillis();
        if (idleChargeEligibleSinceMillis == 0L) {
            idleChargeEligibleSinceMillis = now;
            idleChargeTriggeredForCurrentIdlePeriod = false;
            return;
        }

        int waitMinutes = getIdleChargeWaitMinutes();
        long requiredIdleMillis = waitMinutes * 60_000L;
        if (!idleChargeTriggeredForCurrentIdlePeriod
                && isWithinAutoChargeWindow()
                && now - idleChargeEligibleSinceMillis >= requiredIdleMillis) {
            idleChargeTriggeredForCurrentIdlePeriod = true;
            Log.d(TAG, "Idle auto charge triggered, battery=" + batteryLevel
                    + ", lowPowerThreshold=" + lowPowerThreshold
                    + ", idleMinutes=" + ((now - idleChargeEligibleSinceMillis) / 60000)
                    + ", waitMinutes=" + waitMinutes
                    + ", window=" + getAutoChargeWindowLogText());
            handleNavigationEvent(CHARGE, null, null, true);
        } else {
            logIdleChargeWaitingIfNeeded(now);
        }
    }

    private void logIdleChargeWaitingIfNeeded(long now) {
        if (now - lastIdleChargeBlockedLogMillis < 60_000L) {
            return;
        }
        lastIdleChargeBlockedLogMillis = now;
        int waitMinutes = getIdleChargeWaitMinutes();
        long idleMillis = idleChargeEligibleSinceMillis == 0L ? 0L : now - idleChargeEligibleSinceMillis;
        Log.d(TAG, "Idle auto charge eligible, waiting: idleSeconds=" + (idleMillis / 1000L)
                + ", requiredSeconds=" + (waitMinutes * 60L)
                + ", inWindow=" + isWithinAutoChargeWindow()
                + ", window=" + getAutoChargeWindowLogText());
    }

    private void logIdleChargeBlockedIfNeeded(AgvStatusResponse response,
                                              boolean idleOrCompleted,
                                              boolean taskRunning,
                                              boolean charging) {
        if (!Boolean.TRUE.equals(basicViewModel.getIsIdleCharge().getValue())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastIdleChargeBlockedLogMillis < 60_000L) {
            return;
        }
        lastIdleChargeBlockedLogMillis = now;
        Log.d(TAG, "Idle auto charge waiting/blocked: battery=" + batteryLevel
                + ", idleOrCompleted=" + idleOrCompleted
                + ", taskRunning=" + taskRunning
                + ", confidence=" + confidence
                + ", requiredConfidence>" + default_confidence
                + ", emergency=" + emergency
                + ", charging=" + charging
                + ", inManualCharge=" + response.data.inManualCharge
                + ", softPause=" + response.data.isSoftPause
                + ", alertResId=" + getRobotStatusAlertResId(response));
    }

    private void resetIdleChargeState() {
        idleChargeEligibleSinceMillis = 0L;
        idleChargeTriggeredForCurrentIdlePeriod = false;
    }

    /**
     * 闲时自动返回待命点逻辑
     * 参考evaluateIdleAutoCharge实现，独立评估触发条件
     */
    private void evaluateIdleReturnHome(AgvStatusResponse response) {
        if (response == null || response.data == null) {
            resetIdleReturnHomeState();
            return;
        }

        boolean idleOrCompleted = response.data.goalFinish == -2 || response.data.goalFinish == 1;
        boolean taskRunning = navigationHandler != null && navigationHandler.isTaskRunning();
        boolean charging = response.data.electricCurrentIn >= 0.01f;
        boolean inStationStay = navigationHandler != null && navigationHandler.isInStationStay();
        boolean atParkPoint = isAtParkPoint(response);

        boolean statusEligible = Boolean.TRUE.equals(basicViewModel.getIsIdleReturnHome().getValue())
                && idleOrCompleted
                && !taskRunning
                && confidence > default_confidence
                && !Boolean.TRUE.equals(emergency)
                && !charging
                && response.data.inManualCharge <= 0
                && !response.data.isSoftPause
                && getRobotStatusAlertResId(response) == R.string.no_error
                && !inStationStay
                && !atParkPoint
                && !isUserRecentlyInteracting()
                && !idleChargeTriggeredForCurrentIdlePeriod;

        // 测试日志：输出闲时回待命检查状态
        logIdleReturnHomeCheck(response, idleOrCompleted, taskRunning, charging,
                inStationStay, atParkPoint, statusEligible);

        if (Boolean.TRUE.equals(basicViewModel.getIsIdleReturnHome().getValue())) {
            TaskDebug1.logThrottled("idle_park_check", 5000, String.format(
                    "[IDLE_PARK] check goalFinish=%d idle=%b taskRunning=%b inStationStay=%b atPark=%b eligible=%b navRunning=%b",
                    response.data.goalFinish, idleOrCompleted, taskRunning, inStationStay, atParkPoint, statusEligible,
                    navigationHandler != null && navigationHandler.isTaskRunning()));
        }

        if (!statusEligible) {
            resetIdleReturnHomeState();
            return;
        }

        long now = System.currentTimeMillis();
        if (idleReturnHomeEligibleSinceMillis == 0L) {
            idleReturnHomeEligibleSinceMillis = now;
            idleReturnHomeTriggeredForCurrentIdlePeriod = false;
            return;
        }

        int waitSeconds = getIdleReturnWaitSeconds();
        long requiredIdleMillis = waitSeconds * 1000L;
        if (!idleReturnHomeTriggeredForCurrentIdlePeriod
                && now - idleReturnHomeEligibleSinceMillis >= requiredIdleMillis) {
            idleReturnHomeTriggeredForCurrentIdlePeriod = true;
            TaskDebug1.log(String.format("[IDLE_PARK] TRIGGER goalFinish=%d taskRunning=%b inStationStay=%b atPark=%b waitSec=%d",
                    response.data.goalFinish, taskRunning, inStationStay, atParkPoint, waitSeconds));
            Log.d("测试", "闲时回待命触发: 等待秒数=" + ((now - idleReturnHomeEligibleSinceMillis) / 1000)
                    + ", 配置等待秒数=" + waitSeconds);
            handleNavigationEvent(PARK, null, null);
        }
    }

    /**
     * 判断用户最近是否操作过屏幕。
     * 用户操作屏幕时不应触发闲时充电/闲时回待命，避免在用户编辑任务、查看设置时打断。
     * 返回 true 表示用户在 USER_INTERACTION_SUPPRESS_MS 内有过交互，应推迟闲时触发。
     * app 启动后从未交互过（时间为 0）时不视为"最近交互"，不阻塞闲时逻辑。
     */
    private boolean isUserRecentlyInteracting() {
        long last = BaseActivity.getLastUserInteractionMillis();
        if (last <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - last < USER_INTERACTION_SUPPRESS_MS;
    }

    /**
     * 判断机器人是否已经在待命点（驻车点）附近，距离<1米视为在待命点
     */
    private boolean isAtParkPoint(AgvStatusResponse response) {
        if (response == null || response.data == null || response.data.pos == null || mapViewModel == null) {
            return false;
        }
        String currentMap = response.data.pos.mapName != null ? response.data.pos.mapName : "";
        if (currentMap.isEmpty()) {
            currentMap = mapViewModel.getCurrentMap();
        }
        if (currentMap == null || currentMap.isEmpty()) {
            return false;
        }
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            return false;
        }
        String buildingId = mapViewModel.extractBuildingId(currentMap);
        int currentFloor = mapViewModel.extractMapFloor(currentMap);
        Building building = mapPoints.getBuilding(buildingId);
        FloorPoints floorPoints = building != null ? building.getFloorPoints(currentFloor) : null;
        if (floorPoints == null || floorPoints.getParkPoint() == null) {
            return false;
        }
        Position parkPoint = floorPoints.getParkPoint();
        if (!isSameMapForLocationDisplay(currentMap, parkPoint.getMapName())) {
            return false;
        }
        double dx = response.data.pos.x - parkPoint.getPosX();
        double dy = response.data.pos.y - parkPoint.getPosY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        return distance < 1.0;
    }

    private int getIdleReturnWaitSeconds() {
        Integer waitSeconds = basicViewModel.getIdleReturnWaitSeconds().getValue();
        if (waitSeconds == null) {
            return 600;
        }
        return Math.max(1, Math.min(86400, waitSeconds));
    }

    private void resetIdleReturnHomeState() {
        idleReturnHomeEligibleSinceMillis = 0L;
        idleReturnHomeTriggeredForCurrentIdlePeriod = false;
    }

    /**
     * 闲时回待命检查的测试日志（用"测试"标签过滤）
     */
    private void logIdleReturnHomeCheck(AgvStatusResponse response,
                                        boolean idleOrCompleted,
                                        boolean taskRunning,
                                        boolean charging,
                                        boolean inStationStay,
                                        boolean atParkPoint,
                                        boolean statusEligible) {
        if (!Boolean.TRUE.equals(basicViewModel.getIsIdleReturnHome().getValue())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (statusEligible) {
            // 满足条件时每60秒输出一次等待日志
            if (now - lastIdleReturnHomeBlockedLogMillis < 60_000L) {
                return;
            }
            lastIdleReturnHomeBlockedLogMillis = now;
            long elapsed = idleReturnHomeEligibleSinceMillis == 0L ? 0L : (now - idleReturnHomeEligibleSinceMillis) / 1000L;
            Log.d("测试", "闲时回待命等待中: 已等待=" + elapsed + "s, 配置等待="
                    + getIdleReturnWaitSeconds() + "s");
        } else {
            // 不满足条件时每60秒输出一次阻塞日志
            if (now - lastIdleReturnHomeBlockedLogMillis < 60_000L) {
                return;
            }
            lastIdleReturnHomeBlockedLogMillis = now;
            List<String> reasons = new ArrayList<>();
            if (!idleOrCompleted) reasons.add("非空闲/完成状态");
            if (taskRunning) reasons.add("任务运行中");
            if (confidence <= default_confidence) reasons.add("置信度不足");
            if (Boolean.TRUE.equals(emergency)) reasons.add("急停");
            if (charging) reasons.add("充电中");
            if (response.data.inManualCharge > 0) reasons.add("手动充电");
            if (response.data.isSoftPause) reasons.add("软暂停");
            if (getRobotStatusAlertResId(response) != R.string.no_error) reasons.add("有报错");
            if (inStationStay) reasons.add("站点停留中");
            if (atParkPoint) reasons.add("在待命点");
            Log.d("测试", "闲时回待命检查: goalFinish=" + response.data.goalFinish
                    + ", 空闲/完成=" + idleOrCompleted
                    + ", 任务运行=" + taskRunning
                    + ", 充电=" + charging
                    + ", 置信度=" + confidence
                    + ", 急停=" + emergency
                    + ", 手动充电=" + response.data.inManualCharge
                    + ", 软暂停=" + response.data.isSoftPause
                    + ", 站点停留=" + inStationStay
                    + ", 在待命点=" + atParkPoint);
            Log.d("测试", "闲时回待命不满足条件: " + reasons);
        }
    }

    private int getIdleChargeWaitMinutes() {
        Integer waitMinutes = basicViewModel.getIdleChargeWaitMinutes().getValue();
        if (waitMinutes == null) {
            return 10;
        }
        return Math.max(1, Math.min(1440, waitMinutes));
    }

    private boolean isWithinAutoChargeWindow() {
        int start = normalizeMinuteOfDay(getLiveDataInt(basicViewModel.getAutoChargeStartMinute().getValue(), 0));
        int end = normalizeMinuteOfDay(getLiveDataInt(basicViewModel.getAutoChargeEndMinute().getValue(), 0));
        int now = getCurrentMinuteOfDay();

        if (start == end) {
            return true;
        }
        if (start < end) {
            return now >= start && now <= end;
        }
        return now >= start || now <= end;
    }

    private int getLiveDataInt(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private int normalizeMinuteOfDay(int minute) {
        return ((minute % 1440) + 1440) % 1440;
    }

    private int getCurrentMinuteOfDay() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    private String getAutoChargeWindowLogText() {
        int start = normalizeMinuteOfDay(getLiveDataInt(basicViewModel.getAutoChargeStartMinute().getValue(), 0));
        int end = normalizeMinuteOfDay(getLiveDataInt(basicViewModel.getAutoChargeEndMinute().getValue(), 0));
        return String.format(Locale.US, "%02d:%02d-%02d:%02d", start / 60, start % 60, end / 60, end % 60);
    }

    private int getRobotStatusAlertResId(AgvStatusResponse response) {
        if (response == null || response.data == null) {
            return R.string.no_error;
        }

        AgvStatusResponse.Data data = response.data;
        if (data.emgStop || containsErrorCode(data.errorCode, 10001)) {
            return R.string.error_emergency_stop;
        }
        if (data.isSoftPause) {
            return R.string.no_error;
        }

        Integer errorCode = findFirstKnownErrorCode(data.errorCode);
        if (errorCode == null && data.errcode != null && ErrorCode.containsCode(data.errcode)) {
            errorCode = data.errcode;
        }
        if (errorCode == null && ErrorCode.containsCode(data.errCode)) {
            errorCode = data.errCode;
        }

        if (errorCode != null) {
            Integer resId = ErrorCode.getAllErrorCodeResIds().get(errorCode);
            if (resId != null) {
                return resId;
            }
        }

        if (data.collisionWarning) {
            return R.string.error_collision;
        }

        return R.string.no_error;
    }

    private boolean containsErrorCode(List<Integer> errorCodes, int targetCode) {
        return errorCodes != null && errorCodes.contains(targetCode);
    }

    private Integer findFirstKnownErrorCode(List<Integer> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return null;
        }

        for (Integer code : errorCodes) {
            if (code != null && ErrorCode.containsCode(code)) {
                return code;
            }
        }
        return null;
    }

    private void setupPeriodicDataRefresh() {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refreshRunnable = new Runnable() {
            @Override
            public void run() {
                Map<Integer, JackTask> currentJackTasks = jackViewModel.getJackTaskMap().getValue();
                if (currentJackTasks != null) {
                    jackTaskMap = new LinkedHashMap<>(currentJackTasks);
                    Log.d(TAG, "Refreshed JackTaskMap: " + jackTaskMap.size() + " tasks");
                }
                Map<Integer, CruiseTask> currentCruiseTasks = cruiseViewModel.getCruiseTaskMap().getValue();
                if (currentCruiseTasks != null) {
                    cruiseTaskMap = new LinkedHashMap<>(currentCruiseTasks);
                    Log.d(TAG, "Refreshed CruiseTaskMap: " + cruiseTaskMap.size() + " tasks");
                }

                // Schedule next refresh
                handler.postDelayed(this, 30000); // Refresh every 30 seconds
            }
        };
        handler.postDelayed(refreshRunnable, 30000);
    }

    private void startWebServer() {
        // 清理内存垃圾
        System.gc();
        System.runFinalization();

        int tryPort = port;
        // 最大尝试端口
        int maxPort = 8090;
        while (tryPort <= maxPort && !isServerStarted) {
            final int currentPort = tryPort;
            webServer = createWebServer(currentPort);

            try {
                webServer.start();
                isServerStarted = true;
                port = currentPort;
                notifyServerStarted(currentPort);
                break;
            } catch (IOException e) {
                // handle current port being occupied
                Log.e(TAG, getString(R.string.error_port_occupied, currentPort, e.getMessage()));

                // 关闭失败的服务器实例
                if (webServer != null) {
                    webServer.stop();
                    webServer = null;
                }

                tryPort++;

                if (tryPort > maxPort) {
                    // handle all ports being occupied
                    Log.e(TAG, getString(R.string.error_all_ports_occupied, port, maxPort));
                    mainHandler.post(() -> {
                        Toast.makeText(WebService.this,
                                LocaleHelper.onServiceGetString(WebService.this, R.string.web_service_start_failed),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }
        }
    }

    private NanoHTTPD createWebServer(int port) {
        return new NanoHTTPD(port) {
            public Response serve(IHTTPSession session) {
                try {
                    if (isApiStatusRequest(session)) {
                        return handleApiStatusRequest();
                    }

                    if (isApiQueueRequest(session)) {
                        return handleApiQueueRequest();
                    }

                    if (isApiElevatorListRequest(session)) {
                        return handleApiElevatorListRequest();
                    }

                    Map<String, String> params = extractAllParameters(session);
                    Log.d(TAG, "Received parameters: " + params);

                    handleNavigationRequests(params, session);
                    handleControlCommands(params);

                    // Handle cancel queue request
                    if (params.containsKey("cancelQueue")) {
                        int queueId = Integer.parseInt(params.get("cancelQueue"));
                        boolean removed = removeFromQueue(queueId);
                        Log.d(TAG, "Cancel queue request, queueId: " + queueId + ", removed: " + removed);
                    }

                    String stationOptions = generateStationOptions();
                    String jackTaskOptions = generateJackTaskOptions();

                    // 获取当前错误信息和操作信息
                    //String errorMsgStr = getLocalizedErrorMsg();
                    //String operationStr = getFormattedOperation();

                    //String html = buildMainHtml(stationOptions, jackTaskOptions, errorMsgStr, operationStr);
                    //return newFixedLengthResponse(html);

                    return buildMainResponse(stationOptions, jackTaskOptions);

                } catch (Exception e) {
                    Log.e(TAG, LocaleHelper.onServiceGetString(WebService.this, R.string.error_request_processing), e);
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT,
                            LocaleHelper.onServiceGetString(WebService.this, R.string.server_request_error));
                }
            }
        };
    }

    private NanoHTTPD.Response handleApiStatusRequest() {
        Position currentTaskTarget = navigationHandler.getCurrentTaskTarget();
        JSONObject json = new JSONObject();
        try {
            json.put("state", getStateString(getDisplayWorkStatus()));
            json.put("batteryLevel", batteryLevel);
            json.put("emergency", emergency != null ? emergency : false);
            json.put("targetId", currentTaskTarget != null ?
                    currentTaskTarget.getName().replace(LocaleHelper.onServiceGetString(this, R.string.target_prefix), "") : LocaleHelper.onServiceGetString(this, R.string.no_target));
            json.put("confidence", confidence);
            json.put("pos", pos != null ? pos : LocaleHelper.onServiceGetString(this, R.string.unknown_position));
            json.put("operation", getFormattedOperation());
            json.put("errorMsg", getLocalizedErrorMsg());
            json.put("queuedTaskCount", taskQueue.size());

            NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString());
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET");
            return response;
        } catch (JSONException e) {
            Log.e(TAG, LocaleHelper.onServiceGetString(WebService.this, R.string.error_request_processing), e);
            return newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT,
                    LocaleHelper.onServiceGetString(WebService.this, R.string.server_request_error));
        }
    }

    private NanoHTTPD.Response handleApiElevatorListRequest() {
        JSONObject jsonResponse = new JSONObject();
        try {
            if (elevatorViewModel == null) {
                jsonResponse.put("status", "error");
                jsonResponse.put("message", "Elevator ViewModel not available");
                return newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
            }

            List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
            if (configs == null || configs.isEmpty()) {
                jsonResponse.put("status", "success");
                jsonResponse.put("count", 0);
                jsonResponse.put("data", new org.json.JSONArray());
                jsonResponse.put("timestamp", System.currentTimeMillis());
                NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", jsonResponse.toString());
                response.addHeader("Access-Control-Allow-Origin", "*");
                response.addHeader("Access-Control-Allow-Methods", "GET");
                return response;
            }

            org.json.JSONArray dataArray = new org.json.JSONArray();

            for (ElevatorViewModel.ElevatorConfig config : configs) {
                JSONObject configJson = new JSONObject();
                configJson.put("elevatorId", config.getId() != null ? config.getId() : "");
                configJson.put("buildingId", config.getBuildingId() != null ? config.getBuildingId() : "default");
                configJson.put("robotId", -1); // Default value as per format
                configJson.put("channel", config.getChannel());
                configJson.put("address", config.getAddress());

                // Build floors array
                org.json.JSONArray floorsArray = new org.json.JSONArray();
                List<ElevatorViewModel.ElevatorFloor> floors = config.getFloors();
                if (floors != null) {
                    for (ElevatorViewModel.ElevatorFloor floor : floors) {
                        JSONObject floorJson = new JSONObject();
                        floorJson.put("floor", floor.getFloor());
                        floorJson.put("alias", floor.getAlias() != null ? floor.getAlias() : String.valueOf(floor.getFloor()));
                        floorJson.put("buildingId", floor.getBuildingId() != null ? floor.getBuildingId() : config.getBuildingId());

                        // Generate mapName based on buildingId and floor
                        String mapName = generateMapNameForWebService(floor.getBuildingId(), floor.getFloor());
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

            NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", jsonResponse.toString());
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET");
            return response;

        } catch (JSONException e) {
            Log.e(TAG, LocaleHelper.onServiceGetString(WebService.this, R.string.error_request_processing), e);
            return newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT,
                    LocaleHelper.onServiceGetString(WebService.this, R.string.server_request_error));
        }
    }

    /**
     * Generate map name from building ID and floor number
     * Format: e{buildingId}_{floor}
     */
    private String generateMapNameForWebService(String buildingId, int floor) {
        if (buildingId == null || buildingId.isEmpty()) {
            return "e0_" + floor;
        }
        return "e" + buildingId + "_" + floor;
    }

    private void handleNavigationRequests(Map<String, String> params, NanoHTTPD.IHTTPSession session) {
        boolean hasStationRequest = params.containsKey("station") && isRootGetRequest(session);
        boolean hasTaskRequest = params.containsKey("jackTaskName") && isRootGetRequest(session);
        if (!hasStationRequest && !hasTaskRequest) {
            return;
        }

        // 如果机器人正在运行，将任务加入排队队列
        if (navigationHandler != null && navigationHandler.isTaskRunning()) {
            if (hasStationRequest) {
                String stationParam = params.get("station");
                if (!stationParam.isEmpty()) {
                    int queueId = addToQueue(NavigationEventType.DELIVERY_TASK, stationParam, null);
                    currentErrorMsgResId = R.string.task_queued;
                    operationFormatArgs = new Object[]{stationParam, queueId};
                    Log.d(TAG, "Delivery task queued, station: " + stationParam + ", queueId: " + queueId);
                }
                return;
            }
            if (hasTaskRequest) {
                String taskNameParam = params.get("jackTaskName");
                if (taskNameParam != null && !taskNameParam.isEmpty()) {
                    if (isCruiseTaskOption(taskNameParam)) {
                        String cruiseTaskName = getCruiseTaskNameFromOption(taskNameParam);
                        int queueId = addToQueue(NavigationEventType.DELIVERY_TASK, cruiseTaskName, null);
                        currentErrorMsgResId = R.string.task_queued;
                        operationFormatArgs = new Object[]{cruiseTaskName, queueId};
                        Log.d(TAG, "Cruise task queued, taskName: " + cruiseTaskName + ", queueId: " + queueId);
                    } else if (isJackTaskOption(taskNameParam)) {
                        String jackTaskName = getJackTaskNameFromOption(taskNameParam);
                        int queueId = addToQueue(NavigationEventType.JACK_TASK, jackTaskName, null);
                        currentErrorMsgResId = R.string.task_queued;
                        operationFormatArgs = new Object[]{jackTaskName, queueId};
                        Log.d(TAG, "Jack task queued, taskName: " + jackTaskName + ", queueId: " + queueId);
                    } else if (isInteger(taskNameParam)) {
                        int taskId = Integer.parseInt(taskNameParam);
                        int queueId = addToQueue(NavigationEventType.JACK_TASK, null, taskId);
                        JackTask task = jackTaskMap.get(taskId);
                        currentErrorMsgResId = R.string.task_queued;
                        operationFormatArgs = new Object[]{task != null ? task.getTaskName() : "Unknown", queueId};
                        Log.d(TAG, "Jack task queued, taskId: " + taskNameParam + ", queueId: " + queueId);
                    }
                }
                return;
            }
            currentErrorMsgResId = R.string.error_task_running;
            return;
        }

        if (hasStationRequest) {
            String stationParam = params.get("station");
            selectedStationId = stationParam;
            if (!selectedStationId.isEmpty()) {
                operationFormatArgs = new Object[]{selectedStationId};
                handleNavigationEvent(DELIVERY_TASK, stationParam, null);
            } else {
                currentErrorMsgResId = R.string.error_no_target;
            }
        }

        if (hasTaskRequest) {
            String taskNameParam = params.get("jackTaskName");
            assert taskNameParam != null;
            if (!taskNameParam.isEmpty()) {
                if (isCruiseTaskOption(taskNameParam)) {
                    String cruiseTaskName = getCruiseTaskNameFromOption(taskNameParam);
                    operationFormatArgs = new Object[]{cruiseTaskName};
                    handleNavigationEvent(DELIVERY_TASK, cruiseTaskName, null);
                } else if (isJackTaskOption(taskNameParam)) {
                    String jackTaskName = getJackTaskNameFromOption(taskNameParam);
                    operationFormatArgs = new Object[]{jackTaskName};
                    handleNavigationEvent(JACK_TASK, jackTaskName, null);
                } else if (isInteger(taskNameParam)) {
                    int taskId = Integer.parseInt(taskNameParam);
                    JackTask task = jackTaskMap.get(taskId);
                    operationFormatArgs = new Object[]{task != null ? task.getTaskName() : "Unknown"};
                    handleNavigationEvent(JACK_TASK, null, taskId);
                }
            } else {
                currentErrorMsgResId = R.string.error_no_target;
            }
        }
    }

    private void handleControlCommands(Map<String, String> params) {
        if (params.containsKey("call")) {
            String command = params.get("call");
            BroadcastHelper broadcastHelper = MyApplication.getBroadcastHelper();

            switch (Objects.requireNonNull(command)) {
                case "charge":
                    handleNavigationEvent(CHARGE, null, null);
                    break;
                case "park":
                    handleNavigationEvent(PARK, null, null);
                    break;
                case "cancel":
                    broadcastHelper.sendCancelCommand();
                    //处理任务已取消的操作
                    handleCancelOperation();
                    break;
                case "pause":
                    broadcastHelper.sendPauseCommand();
                    break;
                case "resume":
                    broadcastHelper.sendResumeCommand();
                    //处理任务已恢复的操作
                    handleResumeOperation();
                    break;
            }
        }
    }

    //处理取消任务的操作
    private void handleCancelOperation() {
        // 更新操作状态为"任务已取消"
        currentOperationResId = R.string.operation_canceled;
        // 重置错误信息
        currentErrorMsgResId = R.string.no_error;
        operationFormatArgs = null;
        currentEventType = null;
        currentExecutingTaskId = -1;
        executeMode = false;
        selectedStationId = null;

        // 5秒后自动重置为"无操作"（与任务完成逻辑保持一致）
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentOperationResId == R.string.operation_canceled) {
                    currentOperationResId = R.string.no_operation;
                }
            }
        }, 5000);
    }


    private NanoHTTPD.Response buildMainResponse(String stationOptions, String jackTaskOptions) {
        String errorMsgStr = getLocalizedErrorMsg();
        String operationStr = getFormattedOperation();

        // Update html builder with current state
        htmlBuilder = new WebHtmlBuilder(this, getDisplayWorkStatus(), batteryLevel, emergency,
                targetId, confidence, pos, errorMsgStr, operationStr);

        String html = htmlBuilder.buildMainHtml(stationOptions, jackTaskOptions);
        return newFixedLengthResponse(html);
    }

    private void handleNavigationEvent(NavigationEventType eventType, String stationId, Integer taskId) {
        handleNavigationEvent(eventType, stationId, taskId, false);
    }

    private void handleNavigationEvent(NavigationEventType eventType, String stationId, Integer taskId, boolean skipBusyCheck) {
        //记录当前任务类型
        currentEventType = eventType;

        if (eventType == PARK) {
            boolean navRunning = navigationHandler != null && navigationHandler.isTaskRunning();
            TaskDebug8.log(String.format("[PARK] handleNavigationEvent ENTER skipBusy=%b navRunning=%b executeMode=%b battery=%d goalFinish=%d",
                    skipBusyCheck, navRunning, executeMode, batteryLevel, workStatus));
            TaskDebug1.log(String.format("[PARK] handleNavigationEvent PARK skipBusy=%b navRunning=%b executeMode=%b",
                    skipBusyCheck, navRunning, executeMode));
        }

        // 如果机器人正在运行，不允许下发新任务（除非跳过检查）
        // 只有 CANCEL、PAUSE、RESUME 允许在任务运行时执行
        // PARK（待命点）任务在任务运行时也会被拒绝，避免取消正在执行的任务
        if (!skipBusyCheck &&
                navigationHandler != null &&
                navigationHandler.isTaskRunning() &&
                eventType != NavigationEventType.CANCEL &&
                eventType != NavigationEventType.PAUSE &&
                eventType != NavigationEventType.RESUME) {
            TaskDebug8.log(String.format("[PARK] handleNavigationEvent REJECTED busy event=%s navRunning=true", eventType.name()));
            TaskDebug1.log(String.format("[PARK] handleNavigationEvent REJECTED busy event=%s navRunning=true",
                    eventType.name()));
            currentErrorMsgResId = R.string.error_task_running;

            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (currentErrorMsgResId == R.string.error_task_running){
                        currentErrorMsgResId = R.string.no_error;
                    }
                }
            },5000);//5秒之后显示为，无报错
            return;
        }

        // Battery level check: reject non-charge/cancel/pause/resume tasks when battery is too low
        // park（前往待命点）在低电量时也拦截，应前往充电而非待命
        if (!skipBusyCheck && eventType != CHARGE
                && eventType != NavigationEventType.CANCEL
                && eventType != NavigationEventType.PAUSE
                && eventType != NavigationEventType.RESUME) {
            if (!BatteryLevelManager.getInstance().canAcceptNewTask()) {
                BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
                TaskDebug8.log(String.format("[PARK] handleNavigationEvent REJECTED low-battery event=%s state=%s level=%d",
                        eventType.name(), state, BatteryLevelManager.getInstance().getCurrentBatteryLevel()));
                Log.w("WebService", "Task rejected due to low battery: " + state +
                        " (" + BatteryLevelManager.getInstance().getCurrentBatteryLevel() + "%)");
                mainHandler.post(() -> Toast.makeText(MyApplication.getInstance(),
                        LocaleHelper.onServiceGetString(MyApplication.getInstance(), R.string.battery_too_low_for_task,
                                BatteryLevelManager.getInstance().getCurrentBatteryLevel()),
                        Toast.LENGTH_SHORT).show());
                currentErrorMsgResId = R.string.error_task_running;
                mainHandler.postDelayed(() -> {
                    if (currentErrorMsgResId == R.string.error_task_running) {
                        currentErrorMsgResId = R.string.no_error;
                    }
                }, 5000);
                return;
            }
        }


        if (taskExecutor == null) {
            Log.e("WebService", "TaskExecutor is null, cannot execute task");
            currentErrorMsgResId = R.string.error_request_processing;
            return;
        }

        if (executeMode && eventType != NavigationEventType.CANCEL) {
            TaskDebug8.log(String.format("[PARK] handleNavigationEvent REJECTED executeMode event=%s", eventType.name()));
            TaskDebug1.log(String.format("[PARK] handleNavigationEvent REJECTED executeMode event=%s", eventType.name()));
            currentErrorMsgResId = R.string.error_task_running;
            return;
        }

        if (eventType == PARK) {
            TaskDebug8.log("[PARK] handleNavigationEvent DISPATCH -> taskExecutor.executeByTaskName(park)");
            TaskDebug1.log("[PARK] handleNavigationEvent -> taskExecutor.executeByTaskName(park)");
        }

        currentOperationResId = eventType.successResId;
        currentErrorMsgResId = R.string.no_error;

        String taskName = buildTaskName(eventType, stationId, taskId);
        TaskDebug8.log(String.format("[PARK] handleNavigationEvent executeByTaskName taskName=%s", taskName));
        taskExecutor.executeByTaskName(taskName, createEventCallback(eventType, taskId));
    }

    private TaskExecutor.TaskExecutionCallback createEventCallback(NavigationEventType eventType, Integer taskId) {
        return new TaskExecutor.TaskExecutionCallback() {
            @Override
            public void onTaskMatched(String taskType, String taskName) {
                TaskDebug8.log(String.format("[PARK] callback MATCHED event=%s type=%s name=%s", eventType.name(), taskType, taskName));
                Log.d(TAG, eventType.name() + " task matched");
            }

            @Override
            public void onTaskStarted(String taskType, String taskName) {
                TaskDebug8.log(String.format("[PARK] callback STARTED event=%s type=%s name=%s", eventType.name(), taskType, taskName));
                Log.d(TAG, eventType.name() + " task started");
                if (eventType != NavigationEventType.CANCEL &&
                        eventType != NavigationEventType.PAUSE &&
                        eventType != NavigationEventType.RESUME) {
                    //executeMode = true;
                    currentExecutingTaskId = taskId != null ? taskId : -1;
                }
            }

            @Override
            public void onTaskCompleted(String taskType, String taskName) {
                TaskDebug8.log(String.format("[PARK] callback COMPLETED event=%s type=%s name=%s queueSize=%d",
                        eventType.name(), taskType, taskName, taskQueue.size()));
                Log.d(TAG, eventType.name() + " task completed, queue size: " + taskQueue.size());
                if (eventType == NavigationEventType.CANCEL ||
                        eventType == CHARGE ||
                        eventType == PARK) {
                    executeMode = false;
                    currentExecutingTaskId = -1;
                }
            }

            @Override
            public void onTaskFailed(String taskType, String taskName, String reason) {
                TaskDebug8.log(String.format("[PARK] callback FAILED event=%s type=%s name=%s reason=%s",
                        eventType.name(), taskType, taskName, reason));
                Log.e(TAG, eventType.name() + " task failed: " + reason);
                currentErrorMsgResId = eventType.errorResId;
                if (eventType == CHARGE) {
                    resetIdleChargeState();
                }
                executeMode = false;
                currentExecutingTaskId = -1;
                scheduleNextQueuedTask("task start failed");
            }
        };
    }

    //处理继续任务的操作
    private void handleResumeOperation() {
        // 当前操作设置为"任务已恢复"
        if (workStatus == 0) {
            currentOperationResId = R.string.operation_resumed;
            operationFormatArgs = null;

            // 2秒后根据任务类型更新操作描述
            mainHandler.postDelayed(() -> {
                if (currentEventType != null) {
                    switch (currentEventType) {
                        case PARK:
                            currentOperationResId = R.string.operation_parking;
                            break;
                        case CHARGE:
                            currentOperationResId = R.string.operation_charging;
                            break;
                        case DELIVERY_TASK:
                            currentOperationResId = R.string.operation_navigating_to;
                            operationFormatArgs = null;
                            break;
                        case JACK_TASK:
                            currentOperationResId = R.string.start_lifting_task;
                            JackTask task = jackTaskMap.get(currentExecutingTaskId);
                            operationFormatArgs = new Object[]{task != null ? task.getTaskName() : "Unknown"};
                            break;
                        default:
                            currentOperationResId = R.string.no_operation;
                    }
                } else {
                    // 如果没有当前任务类型，使用默认描述
                    currentOperationResId = R.string.no_operation;
                }
            }, 2000); // 2秒延迟
        }
    }

    // ------------------------------------------------------------------------------------------
    // 辅助方法

    private boolean isApiStatusRequest(NanoHTTPD.IHTTPSession session) {
        return session.getUri().equals("/api/status");
    }

    private boolean isApiElevatorListRequest(NanoHTTPD.IHTTPSession session) {
        return session.getUri().equals("/api/elevator/list");
    }

    private String buildTaskName(NavigationEventType eventType, String stationId, Integer taskId) {
        switch (eventType) {
            case DELIVERY_TASK:
                return stationId;
            case JACK_TASK:
                if (stationId != null && !stationId.isEmpty()) {
                    return stationId;
                }
                JackTask task = jackTaskMap.get(taskId);
                return (task != null ? task.getTaskName() : "unknown");
            case CHARGE:
                return "charge";
            case PARK:
                return "park";
            case CANCEL:
                return "cancel";
            case PAUSE:
                return "pause";
            case RESUME:
                return "resume";
            default:
                return eventType.name().toLowerCase();
        }
    }

    private Map<String, String> extractAllParameters(NanoHTTPD.IHTTPSession session) {
        Map<String, String> params = new HashMap<>();

        // Extract GET parameters
        Map<String, List<String>> queryParams = session.getParameters();
        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                params.put(entry.getKey(), entry.getValue().get(0));
            }
        }

        // Extract POST parameters
        try {
            session.parseBody(params);
        } catch (IOException | NanoHTTPD.ResponseException e) {
            Log.w(TAG, "Error parsing POST parameters", e);
        }

        // Extract URI parameters
        String uri = session.getUri();
        if (uri.contains("?")) {
            String query = uri.substring(uri.indexOf("?") + 1);
            for (String pair : query.split("&")) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                }
            }
        }

        return params;
    }

    private String generateStationOptions() {
        StringBuilder options = new StringBuilder();
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        Building firstBuilding = mapPoints != null ? mapPoints.getFirstNonZeroBuilding() : null;

        if (firstBuilding != null && !firstBuilding.getAllFloorPoints().isEmpty()) {
            String prefix = mapViewModel.getCurrentMap();
            if (prefix != null) {
                // Get sorted floors from currentMapPoints
                List<Integer> sortedFloors = new ArrayList<>(firstBuilding.getAllFloorPoints().keySet());
                Collections.sort(sortedFloors);

                for (int floor : sortedFloors) {
                    FloorPoints floorPoints = firstBuilding.getFloorPoints(floor);
                    if (floorPoints != null) {
                        options.append(buildFloorOptionGroup(floor, floorPoints));
                    }
                }
            }
        }

        if (options.length() == 0) {
            options.append("<option value=''>").append(LocaleHelper.onServiceGetString(this, R.string.no_position_data)).append("</option>");
        }

        return options.toString();
    }

    private String generateCruiseTaskOptions() {
        StringBuilder options = new StringBuilder();
        Map<Integer, CruiseTask> currentCruiseTaskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (currentCruiseTaskMap != null) {
            cruiseTaskMap = new LinkedHashMap<>(currentCruiseTaskMap);
        }
        if (cruiseTaskMap == null || cruiseTaskMap.isEmpty()) {
            return options.toString();
        }

        List<CruiseTask> sortedTasks = new ArrayList<>(cruiseTaskMap.values());
        Collections.sort(sortedTasks, Comparator.comparing(task -> task.getId() != null ? task.getId() : Integer.MAX_VALUE));

        boolean hasCruiseTaskOptions = false;
        StringBuilder cruiseOptions = new StringBuilder();
        for (CruiseTask task : sortedTasks) {
            if (task == null || task.getName() == null || task.getName().isEmpty()) {
                continue;
            }
            hasCruiseTaskOptions = true;
            cruiseOptions.append("<option value=\"").append(CRUISE_TASK_OPTION_PREFIX).append(task.getName()).append("\">")
                    .append(task.getName())
                    .append("</option>");
        }
        if (!hasCruiseTaskOptions) {
            return options.toString();
        }
        options.append("<optgroup label=\"").append(LocaleHelper.onServiceGetString(this, R.string.cruise_button)).append("\">");
        options.append(cruiseOptions);
        options.append("</optgroup>");
        return options.toString();
    }

    private String generateJackTaskOptions() {
        StringBuilder options = new StringBuilder();
        Map<Integer, JackTask> currentJackTaskMap = jackViewModel.getJackTaskMap().getValue();
        if (currentJackTaskMap != null) {
            jackTaskMap = new LinkedHashMap<>(currentJackTaskMap);
        }
        if (jackTaskMap != null && !jackTaskMap.isEmpty()) {
            List<JackTask> sortedTasks = new ArrayList<>(jackTaskMap.values());
            Collections.sort(sortedTasks, Comparator.comparingInt(JackTask::getTaskId));

            boolean hasJackTaskOptions = false;
            StringBuilder jackOptions = new StringBuilder();
            for (JackTask task : sortedTasks) {
                if (task == null || task.getTaskName() == null || task.getTaskName().isEmpty()) {
                    continue;
                }
                hasJackTaskOptions = true;
                jackOptions.append("<option value=\"").append(JACK_TASK_OPTION_PREFIX).append(task.getTaskName()).append("\">")
                        .append(task.getTaskName())
                        .append("</option>");
            }
            if (hasJackTaskOptions) {
                options.append("<optgroup label=\"").append(LocaleHelper.onServiceGetString(this, R.string.jack_button)).append("\">");
                options.append(jackOptions);
                options.append("</optgroup>");
            }
        }
        options.append(generateCruiseTaskOptions());
        return options.toString();
    }

    private boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isCruiseTaskOption(String value) {
        return value != null && value.startsWith(CRUISE_TASK_OPTION_PREFIX);
    }

    private String getCruiseTaskNameFromOption(String value) {
        return value.substring(CRUISE_TASK_OPTION_PREFIX.length());
    }

    private boolean isJackTaskOption(String value) {
        return value != null && value.startsWith(JACK_TASK_OPTION_PREFIX);
    }

    private String getJackTaskNameFromOption(String value) {
        return value.substring(JACK_TASK_OPTION_PREFIX.length());
    }

    private String buildFloorOptionGroup(int floor, FloorPoints floorPoints) {
        StringBuilder group = new StringBuilder();
        String floorLabel = String.format(Locale.getDefault(), LocaleHelper.onServiceGetString(this, R.string.floor_title), floor);

        group.append("<optgroup label=\"").append(floorLabel).append("\">");

        // Get all positions from FloorPoints
        List<Position> allPositions = new ArrayList<>();

        // Add work points
        allPositions.addAll(floorPoints.getWorkPoints());

        // Add elevator wait points
        allPositions.addAll(floorPoints.getElevatorWaitPoints());

        // Add elevator ride points
        allPositions.addAll(floorPoints.getElevatorRidePoints());

        // Add special points if they exist
        if (floorPoints.getChargePoint() != null) {
            allPositions.add(floorPoints.getChargePoint());
        }
        if (floorPoints.getPreChargePoint() != null) {
            allPositions.add(floorPoints.getPreChargePoint());
        }
        if (floorPoints.getParkPoint() != null) {
            allPositions.add(floorPoints.getParkPoint());
        }

        // Sort by ID
        Collections.sort(allPositions, Comparator.comparingInt(Position::getId));

        for (Position pos : allPositions) {
            if (!shouldShowOnMobileCallPage(pos)) {
                continue;
            }
            group.append("<option value=\"").append(pos.getId()).append("\">")
                    .append(pos.getName()).append("</option>");
        }

        group.append("</optgroup>");
        return group.toString();
    }

    private boolean shouldShowOnMobileCallPage(Position position) {
        if (position == null) {
            return false;
        }

        String name = position.getName();
        if (name != null && name.startsWith("elevator_")) {
            return false;
        }

        switch (position.getType()) {
            case 2:
            case 3:
            case 4:
            case 10:
            case 11:
            case 12:
            case 13:
            case 14:
                return false;
            default:
                return true;
        }
    }

    private void notifyServerStarted(int port) {
        mainHandler.post(() -> {
            Toast.makeText(WebService.this,
                    LocaleHelper.onServiceGetString(WebService.this, R.string.web_service_started, port),
                    Toast.LENGTH_LONG).show();
        });

        if (port != this.port) {
            Log.w(TAG, getString(R.string.warn_port_conflict, this.port, port));
        }
    }

    private boolean isRootGetRequest(NanoHTTPD.IHTTPSession session) {
        return session.getMethod() == NanoHTTPD.Method.GET &&
                session.getUri().equals("/");
    }

    private String getStateString(int goalFinish) {
        switch (goalFinish) {
            case 0: return LocaleHelper.onServiceGetString(this, R.string.state_running);
            case 1: return LocaleHelper.onServiceGetString(this, R.string.state_completed);
            case -1: return LocaleHelper.onServiceGetString(this, R.string.state_failed);
            case -2: return LocaleHelper.onServiceGetString(this, R.string.state_idle);
            default: return LocaleHelper.onServiceGetString(this, R.string.unknown);
        }
    }

    // 获取本地化错误信息
    private int getDisplayWorkStatus() {
        return navigationHandler != null && navigationHandler.isTaskRunning() ? 0 : -2;
    }

    private String getLocalizedErrorMsg() {
        return LocaleHelper.onServiceGetString(this, currentErrorMsgResId);
    }

    // 获取格式化后的操作信息
    private String getFormattedOperation() {
        String base = LocaleHelper.onServiceGetString(this, currentOperationResId);
        if (operationFormatArgs != null) {
            return String.format(base, operationFormatArgs);
        }
        return base;
    }

    private int getOperationResIdForCurrentTask() {
        if (taskViewModel == null) {
            return R.string.operation_navigating_to;
        }

        String taskType = taskViewModel.getCurrentTaskTypeValue();
        if (taskType == null || TaskViewModel.TASK_INFO_NONE.equals(taskType)) {
            return R.string.operation_navigating_to;
        }

        switch (taskType.trim().toLowerCase(Locale.ROOT)) {
            case "cruise":
                return R.string.start_executing_cruise_task;
            case "jack":
            case "delivery":
                return R.string.operation_executing_task;
            case "charge":
                return R.string.operation_charging;
            case "park":
                return R.string.operation_parking;
            default:
                return R.string.operation_navigating_to;
        }
    }

    private String buildCurrentLocationText(AgvStatusResponse status) {
        String currentMap = "";
        if (status != null && status.data != null && status.data.pos != null) {
            currentMap = status.data.pos.mapName != null ? status.data.pos.mapName : "";
        }
        if (currentMap.isEmpty() && mapViewModel != null) {
            currentMap = mapViewModel.getCurrentMap();
        }
        if (currentMap == null || currentMap.isEmpty()) {
            currentMap = LocaleHelper.onServiceGetString(this, R.string.unknown_map);
        }

        Position nearbyPoint = findNearbyPoint(status, currentMap);
        String displayMapName = formatMapNameForLocationDisplay(currentMap);
        if (nearbyPoint != null) {
            return String.format(Locale.getDefault(),
                    LocaleHelper.onServiceGetString(this, R.string.current_location_map_station),
                    displayMapName,
                    PositionDisplayNameHelper.getDisplayName(this, nearbyPoint));
        }
        return String.format(Locale.getDefault(),
                LocaleHelper.onServiceGetString(this, R.string.current_location_map),
                displayMapName);
    }

    private Position findNearbyPoint(AgvStatusResponse status, String currentMap) {
        if (status == null || status.data == null || status.data.pos == null || mapViewModel == null) {
            return null;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null || currentMap == null || currentMap.isEmpty()) {
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
            double distance = calculateDistanceToRobot(status, point, currentMap);
            if (distance <= pointThresholdMeters && distance < nearestDistance) {
                nearestDistance = distance;
                nearestPoint = point;
            }
        }
        return nearestPoint;
    }

    private double calculateDistanceToRobot(AgvStatusResponse status, Position point, String currentMap) {
        if (point == null || point.getName() == null || point.getName().isEmpty()) {
            return Double.MAX_VALUE;
        }
        if (!isSameMapForLocationDisplay(currentMap, point.getMapName())) {
            return Double.MAX_VALUE;
        }
        double dx = status.data.pos.x - point.getPosX();
        double dy = status.data.pos.y - point.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean isSameMapForLocationDisplay(String currentMap, String pointMap) {
        if (currentMap == null || currentMap.isEmpty() || pointMap == null || pointMap.isEmpty()) {
            return true;
        }
        return normalizeMapNameForLocationDisplay(currentMap)
                .equals(normalizeMapNameForLocationDisplay(pointMap));
    }

    private String normalizeMapNameForLocationDisplay(String mapName) {
        return mapName.replaceFirst("\\.[^.]+$", "");
    }

    private String formatMapNameForLocationDisplay(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            return mapName;
        }

        String normalizedMapName = normalizeMapNameForLocationDisplay(mapName);
        int separatorIndex = normalizedMapName.lastIndexOf('_');
        if (separatorIndex <= 0 || separatorIndex >= normalizedMapName.length() - 1) {
            return normalizedMapName;
        }

        String buildingName = normalizedMapName.substring(0, separatorIndex);
        String floorName = normalizedMapName.substring(separatorIndex + 1);
        if (!floorName.matches("-?\\d+")) {
            return normalizedMapName;
        }

        return String.format(Locale.getDefault(),
                LocaleHelper.onServiceGetString(this, R.string.current_location_building_floor),
                buildingName,
                floorName);
    }

    // ------------------------------------------------------------------------------------------
    // Task Queue Methods
    // ------------------------------------------------------------------------------------------

    private boolean isApiQueueRequest(IHTTPSession session) {
        return session.getMethod() == NanoHTTPD.Method.GET &&
                session.getUri().equals("/api/queue");
    }

    private NanoHTTPD.Response handleApiQueueRequest() {
        String queuedTasksJson = getQueuedTasksJson();
        NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", queuedTasksJson);
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET");
        return response;
    }

    private int addToQueue(NavigationEventType eventType, String stationId, Integer jackTaskId) {
        // 幂等：同一任务已在队列中时不重复入队，直接返回已有 queueId，防止连发堆积
        for (QueuedTask queued : taskQueue.values()) {
            if (queued.getEventType() == eventType
                    && Objects.equals(queued.getStationId(), stationId)
                    && Objects.equals(queued.getJackTaskId(), jackTaskId)) {
                TaskDebug1.log(String.format("[JACK] queue duplicate ignored queueId=%d eventType=%s station=%s jackTaskId=%s",
                        queued.getQueueId(), eventType, stationId, jackTaskId));
                Log.d(TAG, "Duplicate task already queued, reuse queueId: " + queued.getQueueId());
                return queued.getQueueId();
            }
        }
        int queueId = ++queueCounter;
        QueuedTask queuedTask = new QueuedTask(queueId, eventType, stationId, jackTaskId);
        taskQueue.put(queueId, queuedTask);
        Log.d(TAG, "Task added to queue, queueId: " + queueId + 
                ", eventType: " + eventType + 
                ", stationId: " + stationId + 
                ", jackTaskId: " + jackTaskId +
                ", total queue size: " + taskQueue.size());
        return queueId;
    }

    private boolean removeFromQueue(int queueId) {
        QueuedTask removed = taskQueue.remove(queueId);
        if (removed != null) {
            Log.d(TAG, "Task removed from queue, queueId: " + queueId);
            return true;
        }
        Log.d(TAG, "Task not found in queue, queueId: " + queueId);
        return false;
    }

    private void scheduleNextQueuedTask(String reason) {
        Integer configuredCooldown = basicViewModel.getQueuedTaskCooldown().getValue();
        int cooldownSeconds = configuredCooldown != null
                ? configuredCooldown
                : DEFAULT_QUEUED_TASK_COOLDOWN_SECONDS;
        Log.d(TAG, "Scheduling next queued task in " + cooldownSeconds + " seconds: " + reason);
        mainHandler.postDelayed(this::executeNextInQueue, cooldownSeconds * 1000L);
    }

    private void executeNextInQueue() {
        Log.d(TAG, "executeNextInQueue called, queue size: " + taskQueue.size());
        if (taskQueue.isEmpty()) {
            Log.d(TAG, "Task queue is empty, no task to execute");
            return;
        }
        if (navigationHandler != null && navigationHandler.isTaskRunning()) {
            Log.d(TAG, "Current task is still running, keeping queued task pending");
            return;
        }

        // Get first task in queue
        Integer firstKey = taskQueue.keySet().iterator().next();
        QueuedTask nextTask = taskQueue.remove(firstKey);
        Log.d(TAG, "Executing next queued task, queueId: " + nextTask.getQueueId() +
                ", eventType: " + nextTask.getEventType() +
                ", stationId: " + nextTask.getStationId() +
                ", jackTaskId: " + nextTask.getJackTaskId());

        // 使用与正常下发相同的逻辑，跳过忙碌检查
        NavigationEventType eventType = nextTask.getEventType();
        
        if (eventType == NavigationEventType.DELIVERY_TASK) {
            selectedStationId = nextTask.getStationId();
            operationFormatArgs = new Object[]{selectedStationId};
            handleNavigationEvent(eventType, nextTask.getStationId(), null, true);
        } else if (eventType == NavigationEventType.JACK_TASK) {
            if (nextTask.getStationId() != null && !nextTask.getStationId().isEmpty()) {
                operationFormatArgs = new Object[]{nextTask.getStationId()};
                handleNavigationEvent(eventType, nextTask.getStationId(), null, true);
            } else {
                JackTask task = jackTaskMap.get(nextTask.getJackTaskId());
                if (task != null) {
                    operationFormatArgs = new Object[]{task.getTaskName()};
                }
                handleNavigationEvent(eventType, null, nextTask.getJackTaskId(), true);
            }
        }
    }

    private String getQueuedTasksJson() {
        List<QueuedTask> taskList = new ArrayList<>(taskQueue.values());
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < taskList.size(); i++) {
            QueuedTask task = taskList.get(i);
            String taskName = getQueuedTaskDisplayName(task);
            jsonBuilder.append("{")
                    .append("\"queueId\":").append(task.getQueueId()).append(",")
                    .append("\"taskName\":").append(JSONObject.quote(taskName)).append(",")
                    .append("\"eventType\":\"").append(task.getEventType().name()).append("\"")
                    .append("}");
            if (i < taskList.size() - 1) {
                jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("]");
        return jsonBuilder.toString();
    }

    private String getQueuedTaskDisplayName(QueuedTask task) {
        if (task.getEventType() != NavigationEventType.DELIVERY_TASK ||
                task.getStationId() == null ||
                !isInteger(task.getStationId())) {
            return task.getTaskName();
        }

        int stationId = Integer.parseInt(task.getStationId());
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints != null) {
            for (Building building : mapPoints.getAllBuildings().values()) {
                for (FloorPoints floorPoints : building.getAllFloorPoints().values()) {
                    for (Position position : floorPoints.getAllPoints()) {
                        if (position.getId() == stationId &&
                                position.getName() != null &&
                                !position.getName().isEmpty()) {
                            return position.getName();
                        }
                    }
                }
            }
        }
        return task.getStationId();
    }
}
