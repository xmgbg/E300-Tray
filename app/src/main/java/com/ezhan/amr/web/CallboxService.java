package com.ezhan.amr.web;

import static com.ezhan.amr.data.datatype.MultiBuildingMapPoints.generateMapName;
import static com.ezhan.amr.ui.MainActivity.DEFAULT_CONFIDENCE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.ezhan.amr.communication.lora.LoraPacketHandler;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.BatteryLevelManager;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.navigation.ChargeTaskNavigationHelper;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.navigation.TaskExecutor;
import com.ezhan.amr.navigation.task.CommandUtils;
import com.ezhan.amr.navigation.task.NavigationEventType;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.CallboxViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CallboxService extends Service {
    private String TAG = "CallboxService";
    private SharedViewModel sharedViewModel;
    private CallboxViewModel callboxViewModel;
    private MapViewModel mapViewModel;
    private ElevatorViewModel elevatorViewModel;
    private TaskExecutor taskExecutor;
    private LoraCommunicator loraCommunicator;
    private StatusWebSocketClient.StatusListener statusListener;
    private AgvStatusResponse latestStatusResponse;
    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "CallboxServiceChannel";
    private GeneralNavigationHandler navigationHandler;
    private NavigationStateDebugger debugger;

    // 线程池：多路并发处理指令，每个指令都有独立线程回复报文
    // 核心线程=4 最大线程=8，队列=100，60秒超时回收
    private ThreadPoolExecutor commandExecutor;
    // 主线程 Handler：导航等重操作
    private Handler mainHandler;
    private long lastStatusUpdateTime = 0;


    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize debugger
        debugger = NavigationStateDebugger.getInstance();
        debugger.init(getApplicationContext());

        if (debugger != null) {
            debugger.logInfo("========== CallboxService onCreate ==========");
            debugger.logInfo("Service initializing at " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    java.util.Locale.getDefault()).format(new java.util.Date()));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService();
        }
        MyApplication.getInstance().bindCallboxService(this);
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        callboxViewModel = MyApplication.getInstance().getCallboxViewModel();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        taskExecutor = MyApplication.getInstance().getTaskExecutor();
        sharedViewModel.initNavigationHandler();
        navigationHandler = sharedViewModel.getNavigationHandler();

        if (debugger != null) {
            debugger.logInfo("NavigationHandler initialized: " + (navigationHandler != null ? "success" : "FAILED"));
        }

        startStatusMonitoring();

        // 创建线程池：多路并发处理指令，每条指令都有独立线程回复报文
        commandExecutor = new ThreadPoolExecutor(
                4,                          // 核心线程数
                8,                          // 最大线程数
                60L, TimeUnit.SECONDS,      // 空闲线程回收时间
                new LinkedBlockingQueue<>(100), // 等待队列
                r -> {
                    Thread t = new Thread(r, "Callbox-Cmd");
                    t.setDaemon(true);      // 守护线程，不阻塞退出
                    return t;
                }
        );
        // 队列满时直接在当前线程执行（保证指令不丢失）
        commandExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        if (debugger != null) {
            debugger.logInfo(String.format("ThreadPoolExecutor created: corePoolSize=4, maxPoolSize=8, queueCapacity=100"));
        }

        mainHandler = new Handler(Looper.getMainLooper());

        if (navigationHandler != null) {
            Log.d(TAG, "Registering as internal status listener");
            if (debugger != null) {
                debugger.logInfo("Registering internal status listener with navigationHandler");
            }
            if (!navigationHandler.isWebSocketConnected()) {
                Log.w("WebService", "WebSocket not connected - status callback may not be set");
                if (debugger != null) {
                    debugger.logWarning("WebSocket not connected", "Status callback may not be set");
                }
            }
        }
        try {
            loraCommunicator = MyApplication.getInstance().getLoraCommunicator();
            loraCommunicator.setPacketListener(packetListener);
            loraCommunicator.startListening();
            if (debugger != null) {
                debugger.logInfo("LoraCommunicator initialized and listening started");
            }
        } catch (IOException e) {
            if (debugger != null) {
                debugger.logError("Failed to initialize LoraCommunicator", e.getMessage());
            }
            throw new RuntimeException(e);
        }

        if (debugger != null) {
            debugger.logInfo("========== CallboxService onCreate COMPLETE ==========");
        }
    }

    @Override
    public void onDestroy() {
        if (debugger != null) {
            debugger.logInfo("========== CallboxService onDestroy ==========");
            debugger.logInfo("Shutting down service...");
        }

        // 关闭线程池
        if (commandExecutor != null) {
            if (debugger != null) {
                debugger.logInfo("Shutting down ThreadPoolExecutor");
            }
            commandExecutor.shutdownNow();
            commandExecutor = null;
        }
        if (statusListener != null && sharedViewModel != null) {
            sharedViewModel.unregisterStatusListener(statusListener);
            statusListener = null;
        }

        if (loraCommunicator != null) {
            if (debugger != null) {
                debugger.logInfo("Unregistering CallboxService LoRa packet listener");
            }
            loraCommunicator.setPacketListener(null);
        }

        if (debugger != null) {
            debugger.logInfo("========== CallboxService onDestroy COMPLETE ==========");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startForegroundService() {
        String channelId = "callbox_service_channel";
        NotificationChannel channel = new NotificationChannel(
                channelId,
                "Callbox Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Service Running")
                .setContentText("Your service is running in foreground")
                .build();

        startForeground(2, notification);

        if (debugger != null) {
            debugger.logInfo("Foreground service started with notification ID: 2");
        }
    }

    private void startStatusMonitoring() {
        if (debugger != null) {
            debugger.logInfo("Starting status monitoring");
        }

        statusListener = new StatusWebSocketClient.StatusListener() {
            @Override
            public void onStatusUpdate(AgvStatusResponse response) {
                if (response != null && response.data != null) {
                    latestStatusResponse = response;
                    long now = System.currentTimeMillis();
                    if (debugger != null) {
                        long timeSinceLastUpdate = lastStatusUpdateTime > 0 ? now - lastStatusUpdateTime : -1;
                        debugger.logStatusUpdate("CallboxService", true,
                                timeSinceLastUpdate);
                    }
                    lastStatusUpdateTime = now;
                } else if (debugger != null) {
                    debugger.logWarning("Invalid status update", "Response or response.data is null");
                }
            }
        };
        sharedViewModel.registerStatusListener(statusListener);

        if (debugger != null) {
            debugger.logInfo("Status listener registered with SharedViewModel");
        }
        Log.d(TAG, "Registering listener with SharedViewModel");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Callbox Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Callbox Service")
                .setContentText("Listening for callbox buttons")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    //----------------------------------------------------------------------------------------------

    private final LoraCommunicator.PacketListener packetListener =
            new LoraCommunicator.PacketListener() {
                @Override
                public void onButtonPressed(int callBoxAddress, int callBoxChannel, int buttonId) {
                    if (commandExecutor == null) {
                        Log.e(TAG, "commandExecutor 未初始化，丢弃数据包");
                        if (debugger != null) {
                            debugger.logError("commandExecutor is null", "Dropping packet for callBox=" +
                                    callBoxAddress + ", channel=" + callBoxChannel + ", button=" + buttonId);
                        }
                        return;
                    }

                    if (debugger != null) {
                        debugger.logInfo(String.format("📡 [LORA] Button pressed: callBox=%d, channel=%d, buttonId=0x%02X (%d)",
                                callBoxAddress, callBoxChannel, buttonId, buttonId));
                    }

                    // 每条指令提交到线程池独立处理：按键02和查询FF都会得到回复
                    commandExecutor.execute(() ->
                            handleButtonPress(callBoxAddress, callBoxChannel, buttonId));
                }
            };

    private void handleButtonPress(int callBoxAddress, int callBoxChannel, int buttonId) {
        if (debugger != null) {
            debugger.logInfo(String.format("🔧 [HANDLER] Processing button event: callBox=%d, channel=%d, buttonId=0x%02X",
                    callBoxAddress, callBoxChannel, buttonId));
            debugger.logInfo(String.format("  Thread: %s", Thread.currentThread().getName()));
        }

        Log.d(TAG, "处理按钮事件: callBox=" + callBoxAddress + ", channel=" + callBoxChannel + ", button=" + buttonId);

        // ========== 状态查询指令 (0xFF) ==========
        if (buttonId == 0xFF) {
            if (debugger != null) {
                debugger.logInfo("📊 [QUERY] Status query command (0xFF) received");
            }

            boolean isBusy = isRobotBusy();
            if (isBusy) {
                if (debugger != null) {
                    debugger.logInfo("  Robot is BUSY - sending busy response");
                }
                sendBusyResponse(callBoxAddress, callBoxChannel, buttonId);
            } else {
                if (debugger != null) {
                    debugger.logInfo("  Robot is IDLE - sending idle response");
                }
                sendIdleResponse(callBoxAddress, callBoxChannel, buttonId);
            }
            return;
        }

        // ========== 按键指令 (01/02/03) ==========
        if (debugger != null) {
            debugger.logInfo("🔘 [CALL] Call command (buttonId=" + buttonId + ") received");
        }

        // 快速路径：立即发送成功/失败响应
        if (isRobotBusy()) {
            if (debugger != null) {
                debugger.logWarning("Robot is BUSY", "Sending call failed response");
            }
            sendCallFailedResponse(callBoxAddress, callBoxChannel, buttonId);
            return;
        }

        // 慢速路径：主线程执行任务（导航等重操作）
        CallboxViewModel.CallBox callBox = findCallBoxByAddressAndChannel(callBoxAddress, callBoxChannel);
        if (callBox == null) {
            Log.w(TAG, "未找到呼叫盒(地址=" + callBoxAddress + ",信道=" + callBoxChannel + ")，跳过任务执行");
            if (debugger != null) {
                debugger.logWarning("CallBox not found",
                        String.format("Address=%d, Channel=%d - Skipping task execution",
                                callBoxAddress, callBoxChannel));
            }
            sendCallFailedResponse(callBoxAddress, callBoxChannel, buttonId);
            return;
        }

        List<CallboxViewModel.CallButton> buttons = callBox.getButtons();
        int buttonIndex = buttonId - 1;
        if (buttonIndex < 0 || buttonIndex >= buttons.size()) {
            Log.w(TAG, "按钮ID " + buttonId + " 超出范围(共" + buttons.size() + "个)，跳过任务执行");
            if (debugger != null) {
                debugger.logWarning("Button ID out of range",
                        String.format("buttonId=%d, available buttons=%d", buttonId, buttons.size()));
            }
            sendCallFailedResponse(callBoxAddress, callBoxChannel, buttonId);
            return;
        }

        CallboxViewModel.CallButton button = buttons.get(buttonIndex);

        if (debugger != null) {
            debugger.logInfo(String.format("✅ [MATCH] Button matched: name='%s', position='%s', task='%s'",
                    button.getName(),
                    button.getPosition() != null ? button.getPosition() : "null",
                    button.getTask() != null ? button.getTask() : "null"));
            debugger.logInfo("  Posting task to main thread for execution");
        }

        Log.d(TAG, "匹配到按钮: " + button.getName() + "，投递到主线程执行");

        // 任务执行投递到主线程（导航等操作需要主线程环境）
        Runnable createTaskAndRespond = () -> {
            boolean taskCreated = createTaskFromButton(button);
            if (taskCreated) {
                if (debugger != null) {
                    debugger.logInfo("  Task creation accepted - sending call success response");
                }
                sendCallSuccessResponse(callBoxAddress, callBoxChannel, buttonId);
            } else {
                if (debugger != null) {
                    debugger.logWarning("Task creation rejected", "Sending call failed response");
                }
                sendCallFailedResponse(callBoxAddress, callBoxChannel, buttonId);
            }
        };

        if (mainHandler != null) {
            mainHandler.post(createTaskAndRespond);
        } else {
            createTaskAndRespond.run();
        }
    }

    /** 统一判断机器人是否繁忙 */
    private boolean isRobotBusy() {
        boolean isBusy = false;
        String reason = "";

        if (latestStatusResponse == null || latestStatusResponse.data == null) {
            if (debugger != null) {
                debugger.logWarning("isRobotBusy check", "No robot status data, defaulting to NOT busy");
            }
            return false;
        }

        if (latestStatusResponse.data.goalFinish == 0) {
            isBusy = true;
            reason = "Robot is moving (goalFinish=0)";
        } else if (latestStatusResponse.data.emgStop) {
            isBusy = true;
            reason = "Emergency stop active";
        } else if (latestStatusResponse.data.errCode == 10030) {
            isBusy = true;
            reason = "Safety edge triggered";
        } else if (latestStatusResponse.data.poseProbability < DEFAULT_CONFIDENCE) {
            isBusy = true;
            reason = String.format("Low pose confidence (%.2f < %.2f)",
                    latestStatusResponse.data.poseProbability, DEFAULT_CONFIDENCE);
        } else if (navigationHandler != null && navigationHandler.isTaskRunning()) {
            isBusy = true;
            reason = "Task is currently running";
        }

        if (debugger != null && isBusy) {
            debugger.logInfo(String.format("Robot is BUSY: %s", reason));
        } else if (debugger != null) {
            debugger.logInfo("Robot is IDLE - all conditions clear");
        }

        if (!isBusy && latestStatusResponse != null && latestStatusResponse.data != null) {
            boolean charging = ChargeTaskNavigationHelper.isRobotCharging(latestStatusResponse);
            TaskDebug1.log(String.format(
                    "[SKIP1ST] callbox idle goalFinish=%d taskRunning=%b charging=%b chargeState=%d currentIn=%.2f",
                    latestStatusResponse.data.goalFinish,
                    navigationHandler != null && navigationHandler.isTaskRunning(),
                    charging,
                    latestStatusResponse.data.chargeStatus != null
                            ? latestStatusResponse.data.chargeStatus.state : -999,
                    latestStatusResponse.data.electricCurrentIn));
        }

        return isBusy;
    }

    //发送空闲响应
    private void sendIdleResponse(int callBoxAddress, int callBoxChannel, int buttonId) {
        if (debugger != null) {
            debugger.logInfo(String.format("📤 [RESPONSE] Sending IDLE response: callBox=%d, channel=%d, buttonId=0x%02X",
                    callBoxAddress, callBoxChannel, buttonId));
        }

        try {
            byte[] response = LoraPacketHandler.createIdleResponsePacket(
                    (byte) callBoxAddress, (byte) callBoxChannel);
            loraCommunicator.sendBytes(response);

            if (debugger != null) {
                debugger.logInfo("  IDLE response sent successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送空闲响应失败", e);
            if (debugger != null) {
                debugger.logError("Failed to send IDLE response", e.getMessage());
            }
        }
    }

    //发送繁忙响应
    private void sendBusyResponse(int callBoxAddress, int callBoxChannel, int buttonId) {
        if (debugger != null) {
            debugger.logInfo(String.format("📤 [RESPONSE] Sending BUSY response: callBox=%d, channel=%d, buttonId=0x%02X",
                    callBoxAddress, callBoxChannel, buttonId));
        }

        try {
            byte[] response = LoraPacketHandler.createBusyResponsePacket(
                    (byte) callBoxAddress, (byte) callBoxChannel);
            loraCommunicator.sendBytes(response);

            if (debugger != null) {
                debugger.logInfo("  BUSY response sent successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送繁忙响应失败", e);
            if (debugger != null) {
                debugger.logError("Failed to send BUSY response", e.getMessage());
            }
        }
    }

    //发送呼叫成功响应
    private void sendCallSuccessResponse(int callBoxAddress, int callBoxChannel, int buttonId) {
        if (debugger != null) {
            debugger.logInfo(String.format("📤 [RESPONSE] Sending CALL SUCCESS response: callBox=%d, channel=%d, buttonId=0x%02X",
                    callBoxAddress, callBoxChannel, buttonId));
        }

        try {
            byte[] response = LoraPacketHandler.createCallSuccessPacket(
                    (byte) callBoxAddress, (byte) callBoxChannel);
            loraCommunicator.sendBytes(response);

            if (debugger != null) {
                debugger.logInfo("  CALL SUCCESS response sent successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送呼叫成功响应失败", e);
            if (debugger != null) {
                debugger.logError("Failed to send CALL SUCCESS response", e.getMessage());
            }
        }
    }

    //发送呼叫失败响应
    private void sendCallFailedResponse(int callBoxAddress, int callBoxChannel, int buttonId) {
        if (debugger != null) {
            debugger.logInfo(String.format("📤 [RESPONSE] Sending CALL FAILED response: callBox=%d, channel=%d, buttonId=0x%02X",
                    callBoxAddress, callBoxChannel, buttonId));
        }

        try {
            byte[] response = LoraPacketHandler.createCallFailedPacket(
                    (byte) callBoxAddress, (byte) callBoxChannel);
            loraCommunicator.sendBytes(response);

            if (debugger != null) {
                debugger.logInfo("  CALL FAILED response sent successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送呼叫失败响应失败", e);
            if (debugger != null) {
                debugger.logError("Failed to send CALL FAILED response", e.getMessage());
            }
        }
    }

    private CallboxViewModel.CallBox findCallBoxByAddressAndChannel(int callBoxAddress, int callBoxChannel) {
        if (debugger != null) {
            debugger.logInfo(String.format("🔍 [LOOKUP] Finding CallBox: address=%d, channel=%d",
                    callBoxAddress, callBoxChannel));
        }

        List<CallboxViewModel.CallBox> callBoxesList = callboxViewModel.getCallBoxes().getValue();
        if (callBoxesList == null) {
            if (debugger != null) {
                debugger.logWarning("CallBox lookup failed", "CallBoxes list is null");
            }
            return null;
        }

        if (debugger != null) {
            debugger.logInfo(String.format("  Searching through %d CallBoxes", callBoxesList.size()));
        }

        for (CallboxViewModel.CallBox callBox : callBoxesList) {
            if (debugger != null) {
                debugger.logInfo(String.format("  Comparing: address=%d vs %d, channel=%d vs %d",
                        callBox.getAddress(), callBoxAddress,
                        callBox.getChannel() & 0xFF, callBoxChannel));
            }

            if (callBox.getAddress() == callBoxAddress &&
                    (callBox.getChannel() & 0xFF) == callBoxChannel) {
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ [FOUND] CallBox: name='%s', address=%d, channel=%d",
                            callBox.getName(), callBox.getAddress(), callBox.getChannel() & 0xFF));
                    debugger.logInfo(String.format("  Buttons count: %d", callBox.getButtons().size()));
                }
                return callBox;
            }
        }

        if (debugger != null) {
            debugger.logWarning("CallBox not found",
                    String.format("Address=%d, Channel=%d - No matching CallBox in %d total",
                            callBoxAddress, callBoxChannel, callBoxesList.size()));
        }
        return null;
    }

    private boolean createTaskFromButton(CallboxViewModel.CallButton button) {
        if (debugger != null) {
            debugger.logInfo("========== createTaskFromButton ==========");
            debugger.logInfo(String.format("Button: name='%s', position='%s', task='%s'",
                    button.getName(),
                    button.getPosition() != null ? button.getPosition() : "null",
                    button.getTask() != null ? button.getTask() : "null"));
        }

        try {
            // 1. 顶升/巡航任务执行
            if (button.getTask() != null && !button.getTask().isEmpty()) {
                String taskName = button.getTask();

                TaskDebug1.log(String.format("[SKIP1ST] callbox dispatch task='%s' charging=%b",
                        taskName, ChargeTaskNavigationHelper.isRobotCharging(latestStatusResponse)));

                if (debugger != null) {
                    debugger.logInfo(String.format("📋 [TASK] Executing task by name: '%s'", taskName));
                }

                TaskExecutor.TaskExecutionCallback callback = new TaskExecutor.TaskExecutionCallback(){
                    @Override
                    public void onTaskMatched(String taskType, String taskName) {
                        if (debugger != null) {
                            debugger.logInfo(String.format("✅ Task matched: type='%s', name='%s'", taskType, taskName));
                        }
                    }

                    @Override
                    public void onTaskStarted(String taskType, String taskName) {
                        if (debugger != null) {
                            debugger.logInfo(String.format("▶️ Task started: type='%s', name='%s'", taskType, taskName));
                        }
                        isTaskExecuting.setValue(true);
                    }

                    @Override
                    public void onTaskCompleted(String taskType, String taskName) {
                        if (debugger != null) {
                            debugger.logInfo(String.format("✅ Task completed: type='%s', name='%s'", taskType, taskName));
                        }
                        isTaskExecuting.setValue(false);
                    }

                    @Override
                    public void onTaskFailed(String taskType, String taskName, String reason) {
                        if (debugger != null) {
                            debugger.logError(String.format("❌ Task failed: type='%s', name='%s'", taskType, taskName),
                                    "Reason: " + reason);
                        }
                        isTaskExecuting.setValue(false);
                    }
                };
                // executeByTaskName 内部通过 filterTaskName 自动去除"顶升：""巡行："前缀
                taskExecutor.executeByTaskName(taskName, callback);
                return true;
            }

            // 2. 站点导航
            if (button.getPosition() != null && !button.getPosition().isEmpty()) {
                String stationName = button.getPosition();

                if (debugger != null) {
                    debugger.logInfo(String.format("📍 [NAVIGATION] Navigating to station: '%s'", stationName));
                }

                // 通过站点名称查找导航点位置
                MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
                Building firstBuilding = mapPoints != null ? mapPoints.getFirstNonZeroBuilding() : null;

                if (firstBuilding == null) {
                    Log.e(TAG, "地图数据为空");
                    if (debugger != null) {
                        debugger.logError("Navigation failed", "Map data is null");
                    }
                    return false;
                }

                Position targetPos = firstBuilding.findPositionByName(stationName);
                if (targetPos == null) {
                    Log.e(TAG, "未找到站点: " + stationName);
                    if (debugger != null) {
                        debugger.logError("Navigation failed", String.format("Station not found: '%s'", stationName));
                    }
                    return false;
                }

                if (debugger != null) {
                    debugger.logInfo(String.format("  Target position found: id=%d, name='%s', type=%d, floor='%s', coordinates=(%.2f, %.2f)",
                            targetPos.getId(), targetPos.getName(), targetPos.getType(),
                            targetPos.getFloor(), targetPos.getPosX(), targetPos.getPosY()));
                }

                // 检查导航处理器
                if (navigationHandler == null) {
                    Log.e(TAG, "导航处理器未初始化，无法导航");
                    if (debugger != null) {
                        debugger.logError("Navigation failed", "NavigationHandler is null");
                    }
                    return false;
                }

                // 构建导航路线并开始导航
                List<Position> callRoute = new ArrayList<>();
                callRoute.add(targetPos);

                List<Position> fullPositionList =
                        generateFullPositionList(navigationHandler.getCurrentPosition(), callRoute);

                if (fullPositionList == null) return false;

                if (debugger != null) {
                    debugger.logInfo("  Starting navigation to station...");
                }

                // 低电量拦截：呼叫盒站点导航属于 call 类型任务，不接受新任务时拒绝
                if (!BatteryLevelManager.getInstance().canAcceptNewTask()) {
                    Log.w(TAG, "Callbox navigation rejected: battery too low ("
                            + BatteryLevelManager.getInstance().getCurrentBatteryLevel() + "%)");
                    if (debugger != null) {
                        debugger.logWarning("Task rejected - Low battery",
                                BatteryLevelManager.getInstance().getCurrentBatteryLevel() + "%");
                    }
                    return false;
                }

                navigationHandler.startNavigation(fullPositionList, () -> {
                    if (debugger != null) {
                        debugger.logInfo(String.format("✅ Navigation completed to station: '%s'", stationName));
                    }
                    isTaskExecuting.setValue(false);
                });
                isTaskExecuting.setValue(true);
                return true;
            }

            if (debugger != null) {
                debugger.logWarning("No action configured",
                        String.format("Button '%s' has no position or task configured", button.getName()));
            }
            Log.w(TAG, "按钮未配置任何站点或任务");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "任务创建/执行失败", e);
            if (debugger != null) {
                debugger.logError("Task creation/execution failed", e.getMessage());
            }
            return false;
        }
    }

    private final MutableLiveData<Boolean> isTaskExecuting = new MutableLiveData<>(false);

    private Position getPositionById(int id) {
        MultiBuildingMapPoints multiBuildingMapPoints = mapViewModel.getCurrentMapPoints().getValue();

        if (multiBuildingMapPoints == null) {
            return null;
        }

        Position position = multiBuildingMapPoints.findPositionById(id);
        return position;
    }

    private void handleNavigationEvent(NavigationEventType eventType, String stationId, Integer taskId) {
        if (debugger != null) {
            debugger.logInfo(String.format("Navigation event: type=%s, stationId=%s, taskId=%d",
                    eventType.name(), stationId, taskId != null ? taskId : -1));
        }

        if (taskExecutor == null) {
            Log.e("WebService", "TaskExecutor is null, cannot execute task");
            if (debugger != null) {
                debugger.logError("Cannot handle navigation event", "TaskExecutor is null");
            }
            return;
        }
        taskExecutor.executeByTaskName(stationId, createEventCallback(eventType, taskId));
    }

    private TaskExecutor.TaskExecutionCallback createEventCallback(NavigationEventType eventType, Integer taskId) {
        return new TaskExecutor.TaskExecutionCallback() {
            @Override
            public void onTaskMatched(String taskType, String taskName) {
                if (debugger != null) {
                    debugger.logInfo(String.format("%s task matched: type='%s', name='%s'",
                            eventType.name(), taskType, taskName));
                }
            }

            @Override
            public void onTaskStarted(String taskType, String taskName) {
                if (debugger != null) {
                    debugger.logInfo(String.format("%s task started: type='%s', name='%s'",
                            eventType.name(), taskType, taskName));
                }
            }

            @Override
            public void onTaskCompleted(String taskType, String taskName) {
                if (debugger != null) {
                    debugger.logInfo(String.format("%s task completed: type='%s', name='%s'",
                            eventType.name(), taskType, taskName));
                }
            }

            @Override
            public void onTaskFailed(String taskType, String taskName, String reason) {
                if (debugger != null) {
                    debugger.logError(String.format("%s task failed: type='%s', name='%s'",
                            eventType.name(), taskType, taskName), "Reason: " + reason);
                }
            }
        };
    }

    private List<Position> generateFullPositionList(Position currentPosition, List<Position> selectedPositions) {
        if (debugger != null) {
            debugger.log("┌─────────────────────────────────────────────────────────────────────────────┐");
            debugger.log("│                    generateFullPositionList CALLED                         │");
            debugger.log("├─────────────────────────────────────────────────────────────────────────────┤");
            debugger.log(String.format("│ Current Position: %s", currentPosition != null ? currentPosition.getName() : "null"));
            debugger.log(String.format("│ Selected Positions Count: %d", selectedPositions != null ? selectedPositions.size() : 0));
            if (selectedPositions != null) {
                for (int i = 0; i < selectedPositions.size(); i++) {
                    Position pos = selectedPositions.get(i);
                    debugger.log(String.format("│   Selected[%d]: %s (ID: %d, Type: %d, TaskType: %d)",
                            i, pos != null ? pos.getName() : "null",
                            pos != null ? pos.getId() : -1,
                            pos != null ? pos.getType() : -1,
                            pos != null ? pos.getTaskType() : -1));
                }
            }
        }

        int currentFloor = mapViewModel.getCurrentFloor();
        currentPosition.setFloor(String.valueOf(currentFloor));
        String currentMap = mapViewModel.getCurrentMap();
        String currentBuilding = mapViewModel.getCurrentBuilding();
        String fullMapName = generateMapName(currentMap, currentBuilding, currentFloor);
        currentPosition.setMapName(fullMapName);

        if (debugger != null) {
            debugger.log(String.format("│ Current Floor: %d", currentFloor));
            debugger.log(String.format("│ Current Map: %s", currentMap));
            debugger.log(String.format("│ Current Building: %s", currentBuilding));
            debugger.log(String.format("│ Full Map Name: %s", fullMapName));
        }

        // NEW: Check if selected positions exist in floorPositionsMap (excluding types 3,4,10,11,12)
        if (!arePositionsValid(selectedPositions)) {
            if (debugger != null) {
                debugger.logWarning("generateFullPositionList",
                        "❌ One or more selected positions not found in current floor map");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.d("StatusVerification", "One or more selected positions not found in current floor map");
            navigationHandler.playVoicePrompt(VoiceKeyConstants.MAP_INCORRECT);
            return null;
        }

        if (debugger != null) {
            debugger.log("│ ✅ Position validation passed");
        }

        // 1. Check if the selectedPositions have the same map prefix
        if (!isTaskOnSameMap(currentPosition, selectedPositions)) {
            if (debugger != null) {
                debugger.logWarning("generateFullPositionList",
                        "❌ Task stations not on same map");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.d("StatusVerification", "Task stations not on same map");
            navigationHandler.playVoicePrompt(VoiceKeyConstants.MAP_INCORRECT);
            return null;
        }

        if (debugger != null) {
            debugger.log("│ ✅ Same map validation passed");
        }

        // Get building IDs for current position and all selected positions
        String currentBuildingId = extractBuildingIdFromPosition(currentPosition);
        Set<String> targetBuildingIds = new HashSet<>();
        for (Position pos : selectedPositions) {
            targetBuildingIds.add(extractBuildingIdFromPosition(pos));
        }

        if (debugger != null) {
            debugger.log(String.format("│ Current Building ID: %s", currentBuildingId));
            debugger.log(String.format("│ Target Building IDs: %s", targetBuildingIds));
        }

        boolean isCrossBuilding = false;
        boolean isCrossFloor = false;

        // Check if task involves multiple buildings
        if (targetBuildingIds.size() > 1 ||
                (targetBuildingIds.size() == 1 && !targetBuildingIds.contains(currentBuildingId))) {
            isCrossBuilding = true;
            if (debugger != null) {
                debugger.log("│ 🔄 Task is CROSS-BUILDING: current=" + currentBuildingId +
                        ", targets=" + targetBuildingIds);
            }
            Log.d("StatusVerification", "Task is cross-building: current=" + currentBuildingId +
                    ", targets=" + targetBuildingIds);
        } else {
            // Check if task involves multiple floors within the same building
            Set<String> targetFloors = new HashSet<>();
            for (Position pos : selectedPositions) {
                targetFloors.add(pos.getFloor());
            }
            String currentFloorStr = String.valueOf(currentFloor);
            if (targetFloors.size() > 1 ||
                    (targetFloors.size() == 1 && !targetFloors.contains(currentFloorStr))) {
                isCrossFloor = true;
                if (debugger != null) {
                    debugger.log("│ 🔄 Task is CROSS-FLOOR within building " + currentBuildingId +
                            ": current floor=" + currentFloorStr + ", target floors=" + targetFloors);
                }
                Log.d("StatusVerification", "Task is cross-floor within building " + currentBuildingId +
                        ": current floor=" + currentFloorStr + ", target floors=" + targetFloors);
            }
        }

        // 2. If cross-building, check if transition points for the buildings exist and have non-zero coordinates
        if (isCrossBuilding) {
            if (debugger != null) {
                debugger.log("│ 🔍 Checking transition points for cross-building task...");
            }

            // Check all elevator configurations for required points
            boolean hasValidTransitionPoints = checkTransitionPointsForBuildings(targetBuildingIds);

            if (!hasValidTransitionPoints) {
                if (debugger != null) {
                    debugger.logError("generateFullPositionList",
                            "❌ Missing or invalid transition points for cross-building task");
                    debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                }
                Log.d("StatusVerification", "Missing or invalid transition points for cross-building task");
                navigationHandler.playVoicePrompt(VoiceKeyConstants.INVALID_ELEVATOR_TASK);
                return null;
            }

            if (debugger != null) {
                debugger.log("│ ✅ Cross-building verification passed");
            }
            Log.d("StatusVerification", "Cross-building verification passed: transition points exist and have valid coordinates");
        }

        // 3. If cross-floor in the same building, check if wait points and ride points for the building exist and have non-zero coordinates
        if (isCrossFloor && !isCrossBuilding) {
            if (debugger != null) {
                debugger.log("│ 🔍 Checking elevator points for cross-floor task...");
            }

            String buildingId = currentBuildingId;
            Set<Integer> involvedFloors = new HashSet<>();
            involvedFloors.add(currentFloor);
            for (Position pos : selectedPositions) {
                involvedFloors.add(Integer.parseInt(pos.getFloor()));
            }

            if (debugger != null) {
                debugger.log(String.format("│ Involved floors: %s", involvedFloors));
            }

            boolean hasValidElevatorPoints = checkElevatorPointsForFloors(buildingId, involvedFloors);

            if (!hasValidElevatorPoints) {
                if (debugger != null) {
                    debugger.logError("generateFullPositionList",
                            "❌ Missing or invalid elevator wait/ride points for cross-floor task in building " + buildingId);
                    debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                }
                Log.d("StatusVerification", "Missing or invalid elevator wait/ride points for cross-floor task in building " + buildingId);
                navigationHandler.playVoicePrompt(VoiceKeyConstants.ELEVATOR_EMPTY);
                return null;
            }

            if (debugger != null) {
                debugger.log("│ ✅ Cross-floor verification passed");
            }
            Log.d("StatusVerification", "Cross-floor verification passed: wait/ride points exist and have valid coordinates");
        }

        // Get all elevator points for path calculation
        List<Position> waitPointsList = mapViewModel.getAllElevatorWaitPointsAsList();
        List<Position> ridePointsList = mapViewModel.getAllElevatorRidePointsAsList();
        List<Position> transitionPointsList = mapViewModel.getAllElevatorTransitionPointsAsList();

        if (debugger != null) {
            debugger.log(String.format("│ Wait Points count: %d", waitPointsList != null ? waitPointsList.size() : 0));
            debugger.log(String.format("│ Ride Points count: %d", ridePointsList != null ? ridePointsList.size() : 0));
            debugger.log(String.format("│ Transition Points count: %d", transitionPointsList != null ? transitionPointsList.size() : 0));
        }

        currentPosition = elevatorViewModel.updatePositionTypeIfNearSpecialPoints(currentPosition);

        List<Position> interpolatedPath = CommandUtils.getInterpolatedPath(
                currentPosition, waitPointsList, ridePointsList, transitionPointsList, selectedPositions);

        if (debugger != null) {
            debugger.log(String.format("│ Generated interpolated path with %d positions",
                    interpolatedPath != null ? interpolatedPath.size() : 0));
            if (interpolatedPath != null) {
                for (int i = 0; i < Math.min(interpolatedPath.size(), 10); i++) {
                    Position pos = interpolatedPath.get(i);
                    debugger.log(String.format("│   Path[%d]: %s (Type: %d)",
                            i, pos != null ? pos.getName() : "null", pos != null ? pos.getType() : -1));
                }
                if (interpolatedPath.size() > 10) {
                    debugger.log(String.format("│   ... and %d more positions", interpolatedPath.size() - 10));
                }
            }
            debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
        }

        return interpolatedPath;
    }

    // UPDATED: Helper method to check if positions exist in floorPositionsMap (excluding types 3,4,10,11,12)
    private boolean arePositionsValid(List<Position> selectedPositions) {
        if (debugger != null) {
            debugger.log("  ┌─────────────────────────────────────────────────────────────────┐");
            debugger.log("  │                    arePositionsValid CALLED                     │");
            debugger.log("  ├─────────────────────────────────────────────────────────────────┤");
            debugger.log(String.format("  │ Selected Positions Count: %d",
                    selectedPositions != null ? selectedPositions.size() : 0));
        }

        if (selectedPositions == null || selectedPositions.isEmpty()) {
            if (debugger != null) {
                debugger.logWarning("arePositionsValid", "Selected positions list is null or empty");
                debugger.log("  └─────────────────────────────────────────────────────────────────┘");
            }
            return false;
        }

        // Using the MapViewModel helper method
        boolean result = mapViewModel.validatePositionsExist(selectedPositions);

//        if (debugger != null) {
//            debugger.log(String.format("  │ Validation result: %s", result ? "✅ PASSED" : "❌ FAILED"));
//            if (!result) {
//                for (Position pos : selectedPositions) {
//                    if (pos != null) {
//                        boolean exists = mapViewModel.doesPositionExistOnCurrentFloor(pos);
//                        debugger.log(String.format("  │   Position '%s' (ID: %d) exists: %s",
//                                pos.getName(), pos.getId(), exists));
//                    }
//                }
//            }
//            debugger.log("  └─────────────────────────────────────────────────────────────────┘");
//        }

        return result;
    }

    /**
     * Extract building ID from a Position object's map name
     * @param position The position to extract building ID from
     * @return Building ID, or "default" if not found
     */
    protected String extractBuildingIdFromPosition(Position position) {
        if (position == null || position.getMapName() == null) {
            return "default";
        }
        return mapViewModel.extractBuildingId(position.getMapName());
    }

    /**
     * Check if transition points exist for the involved buildings and have non-zero coordinates
     * @param buildingIds Set of building IDs involved in the task
     * @return true if all required transition points exist and have non-zero coordinates
     */
    private boolean checkTransitionPointsForBuildings(Set<String> buildingIds) {
        if (debugger != null) {
            debugger.log("    ┌─────────────────────────────────────────────────────────────┐");
            debugger.log("    │              checkTransitionPointsForBuildings              │");
            debugger.log("    ├─────────────────────────────────────────────────────────────┤");
            debugger.log(String.format("    │ Building IDs: %s", buildingIds));
        }

        // Get all elevator transition points from map points
        List<Position> allTransitionPoints = mapViewModel.getAllElevatorTransitionPointsAsList();

        if (debugger != null) {
            debugger.log(String.format("    │ Total transition points found: %d",
                    allTransitionPoints != null ? allTransitionPoints.size() : 0));
        }

        if (allTransitionPoints == null || allTransitionPoints.isEmpty()) {
            if (debugger != null) {
                debugger.logError("checkTransitionPointsForBuildings",
                        "No transition points found in map configuration");
                debugger.log("    └─────────────────────────────────────────────────────────────┘");
            }
            return false;
        }

        // Group transition points by building
        Map<String, List<Position>> transitionPointsByBuilding = new HashMap<>();
        for (Position point : allTransitionPoints) {
            String buildingId = extractBuildingIdFromPosition(point);
            transitionPointsByBuilding.computeIfAbsent(buildingId, k -> new ArrayList<>()).add(point);
        }

        if (debugger != null) {
            debugger.log("    │ Transition points by building:");
            for (Map.Entry<String, List<Position>> entry : transitionPointsByBuilding.entrySet()) {
                debugger.log(String.format("    │   Building '%s': %d points",
                        entry.getKey(), entry.getValue().size()));
            }
        }

        // Check each involved building
        for (String buildingId : buildingIds) {
            if (debugger != null) {
                debugger.log(String.format("    │ Checking building: '%s'", buildingId));
            }

            List<Position> buildingTransitionPoints = transitionPointsByBuilding.get(buildingId);

            if (buildingTransitionPoints == null || buildingTransitionPoints.isEmpty()) {
                if (debugger != null) {
                    debugger.logError("checkTransitionPointsForBuildings",
                            String.format("No transition points found for building: %s", buildingId));
                    debugger.log("    └─────────────────────────────────────────────────────────────┘");
                }
                Log.e(TAG, "No transition points found for building: " + buildingId);
                return false;
            }

            // Check if at least one transition point has non-zero coordinates
            boolean hasValidPoint = false;
            for (Position point : buildingTransitionPoints) {
                if (point.getPosX() != 0.0 || point.getPosY() != 0.0) {
                    hasValidPoint = true;
                    if (debugger != null) {
                        debugger.log(String.format("    │   ✅ Found valid transition point for building %s: '%s' at (%.2f, %.2f)",
                                buildingId, point.getName(), point.getPosX(), point.getPosY()));
                    }
                    Log.d(TAG, "Found valid transition point for building " + buildingId +
                            " at (" + point.getPosX() + ", " + point.getPosY() + ")");
                    break;
                } else {
                    if (debugger != null) {
                        debugger.log(String.format("    │   ⚠️ Transition point '%s' has zero coordinates (0,0)",
                                point.getName()));
                    }
                }
            }

            if (!hasValidPoint) {
                if (debugger != null) {
                    debugger.logError("checkTransitionPointsForBuildings",
                            String.format("All transition points for building %s have zero coordinates", buildingId));
                    debugger.log("    └─────────────────────────────────────────────────────────────┘");
                }
                Log.e(TAG, "All transition points for building " + buildingId + " have zero coordinates");
                return false;
            }
        }

        if (debugger != null) {
            debugger.log("    │ ✅ All buildings have valid transition points");
            debugger.log("    └─────────────────────────────────────────────────────────────┘");
        }

        return true;
    }

    /**
     * Check if wait points and ride points exist for the involved floors in the building
     * @param buildingId The building ID
     * @param involvedFloors Set of floor numbers involved in the task
     * @return true if all required points exist and have non-zero coordinates
     */
    private boolean checkElevatorPointsForFloors(String buildingId, Set<Integer> involvedFloors) {
        if (debugger != null) {
            debugger.log("      ┌───────────────────────────────────────────────────────────┐");
            debugger.log("      │              checkElevatorPointsForFloors                 │");
            debugger.log("      ├───────────────────────────────────────────────────────────┤");
            debugger.log(String.format("      │ Building ID: %s", buildingId));
            debugger.log(String.format("      │ Involved Floors: %s", involvedFloors));
        }

        // Get elevator configurations for this building
        List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
        if (configs == null || configs.isEmpty()) {
            if (debugger != null) {
                debugger.logError("checkElevatorPointsForFloors",
                        "No elevator configurations found");
                debugger.log("      └───────────────────────────────────────────────────────────┘");
            }
            Log.e(TAG, "No elevator configurations found");
            return false;
        }

        if (debugger != null) {
            debugger.log(String.format("      │ Total elevator configs: %d", configs.size()));
        }

        // Find configs for this building
        List<ElevatorViewModel.ElevatorConfig> buildingConfigs = new ArrayList<>();
        for (ElevatorViewModel.ElevatorConfig config : configs) {
            String configBuildingId = config.getBuildingId();
            if (configBuildingId == null) configBuildingId = "default";
            if (configBuildingId.equals(buildingId)) {
                buildingConfigs.add(config);
            }
        }

        if (buildingConfigs.isEmpty()) {
            if (debugger != null) {
                debugger.logError("checkElevatorPointsForFloors",
                        String.format("No elevator configurations found for building: %s", buildingId));
                debugger.log("      └───────────────────────────────────────────────────────────┘");
            }
            Log.e(TAG, "No elevator configurations found for building: " + buildingId);
            return false;
        }

        if (debugger != null) {
            debugger.log(String.format("      │ Building configs found: %d", buildingConfigs.size()));
        }

        // For cross-floor tasks, we need at least one elevator configuration that covers all floors
        // Check each elevator configuration
        boolean foundValidConfig = false;

        for (ElevatorViewModel.ElevatorConfig config : buildingConfigs) {
            if (debugger != null) {
                debugger.log(String.format("      │ Checking config (ID: %s)", config.getId()));
            }

            Set<Integer> configFloors = new HashSet<>();
            for (ElevatorViewModel.ElevatorFloor floor : config.getFloors()) {
                configFloors.add(floor.getFloor());
            }

            if (debugger != null) {
                debugger.log(String.format("      │   Config covers floors: %s", configFloors));
            }

            // Check if this config covers all involved floors
            boolean coversAllFloors = configFloors.containsAll(involvedFloors);

            if (coversAllFloors) {
                if (debugger != null) {
                    debugger.log("      │   ✅ Config covers all required floors");
                }

                // Verify that each involved floor has valid wait point and ride point
                boolean allPointsValid = true;

                for (int floor : involvedFloors) {
                    if (debugger != null) {
                        debugger.log(String.format("      │   Checking floor %d:", floor));
                    }

                    // Find the floor in this config
                    ElevatorViewModel.ElevatorFloor elevatorFloor = null;
                    for (ElevatorViewModel.ElevatorFloor ef : config.getFloors()) {
                        if (ef.getFloor() == floor) {
                            elevatorFloor = ef;
                            break;
                        }
                    }

                    if (elevatorFloor == null) {
                        allPointsValid = false;
                        if (debugger != null) {
                            debugger.logError("checkElevatorPointsForFloors",
                                    String.format("Floor %d not found in elevator config", floor));
                        }
                        Log.e(TAG, "Floor " + floor + " not found in elevator config for building " + buildingId);
                        break;
                    }

                    // Check wait point
                    Position waitPoint = elevatorFloor.getWaitPoint();
                    if (waitPoint == null || (waitPoint.getPosX() == 0.0 && waitPoint.getPosY() == 0.0)) {
                        allPointsValid = false;
                        if (debugger != null) {
                            debugger.logError("checkElevatorPointsForFloors",
                                    String.format("Wait point for floor %d is missing or has zero coordinates", floor));
                        }
                        Log.e(TAG, "Wait point for floor " + floor + " in building " + buildingId + " is missing or has zero coordinates");
                        break;
                    } else if (debugger != null) {
                        debugger.log(String.format("      │     ✅ Wait point: '%s' at (%.2f, %.2f)",
                                waitPoint.getName(), waitPoint.getPosX(), waitPoint.getPosY()));
                    }

                    // Check ride point
                    Position ridePoint = elevatorFloor.getRidePoint();
                    if (ridePoint == null || (ridePoint.getPosX() == 0.0 && ridePoint.getPosY() == 0.0)) {
                        allPointsValid = false;
                        if (debugger != null) {
                            debugger.logError("checkElevatorPointsForFloors",
                                    String.format("Ride point for floor %d is missing or has zero coordinates", floor));
                        }
                        Log.e(TAG, "Ride point for floor " + floor + " in building " + buildingId + " is missing or has zero coordinates");
                        break;
                    } else if (debugger != null) {
                        debugger.log(String.format("      │     ✅ Ride point: '%s' at (%.2f, %.2f)",
                                ridePoint.getName(), ridePoint.getPosX(), ridePoint.getPosY()));
                    }
                }

                if (allPointsValid) {
                    foundValidConfig = true;
                    if (debugger != null) {
                        debugger.log(String.format("      │ ✅ Found valid elevator config for building %s covering floors: %s",
                                buildingId, involvedFloors));
                    }
                    Log.d(TAG, "Found valid elevator config for building " + buildingId +
                            " covering floors: " + involvedFloors);
                    break;
                }
            } else if (debugger != null) {
                debugger.log(String.format("      │   ❌ Config does NOT cover all required floors"));
            }
        }

        if (!foundValidConfig) {
            if (debugger != null) {
                debugger.logError("checkElevatorPointsForFloors",
                        String.format("No elevator configuration with valid wait/ride points found for floors: %s", involvedFloors));
                debugger.log("      └───────────────────────────────────────────────────────────┘");
            }
            Log.e(TAG, "No elevator configuration with valid wait/ride points found for floors: " + involvedFloors);
        } else if (debugger != null) {
            debugger.log("      └───────────────────────────────────────────────────────────┘");
        }

        return foundValidConfig;
    }

    private boolean isTaskOnSameMap(Position currentPosition, List<Position> selectedPositions) {
        if (debugger != null) {
            debugger.log("        ┌─────────────────────────────────────────────────────────┐");
            debugger.log("        │                  isTaskOnSameMap CALLED                │");
            debugger.log("        ├─────────────────────────────────────────────────────────┤");
        }

        boolean isSameMap = true;
        String currentMap = mapViewModel.extractMapPrefix(currentPosition.getMapName());
        String normalizedCurrentMap = removeFileExtension(currentMap);

        if (debugger != null) {
            debugger.log(String.format("        │ Current Map: %s", currentMap));
            debugger.log(String.format("        │ Normalized Current Map: %s", normalizedCurrentMap));
        }

        for (Position targetPosition : selectedPositions) {
            String targetMap = mapViewModel.extractMapPrefix(targetPosition.getMapName());
            String normalizedTargetMap = removeFileExtension(targetMap);

            if (debugger != null) {
                debugger.log(String.format("        │   Target Map: %s -> Normalized: %s",
                        targetMap, normalizedTargetMap));
            }

            if (!normalizedCurrentMap.equals(normalizedTargetMap)) {
                isSameMap = false;
                if (debugger != null) {
                    debugger.logWarning("isTaskOnSameMap",
                            String.format("Map mismatch: '%s' vs '%s'", normalizedCurrentMap, normalizedTargetMap));
                    debugger.log("        └─────────────────────────────────────────────────────────┘");
                }
                return isSameMap;
            }
        }

        if (debugger != null) {
            debugger.log("        │ ✅ All stations are on the same map");
            debugger.log("        └─────────────────────────────────────────────────────────┘");
        }

        return isSameMap;
    }

    /**
     * Remove file extension using regex pattern
     * Handles: .yaml, .yml, .json, .map, .xml, and any other file extensions
     */
    private String removeFileExtension(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            return "";
        }

        // Remove any file extension (anything after last dot)
        return mapName.replaceFirst("\\.[^.]+$", "");
    }
}
