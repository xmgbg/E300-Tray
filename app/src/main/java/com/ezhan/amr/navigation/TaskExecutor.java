package com.ezhan.amr.navigation;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.ui.CruiseActivity;
import com.ezhan.amr.ui.DeliveryActivity;
import com.ezhan.amr.ui.JackActivity;
import com.ezhan.amr.ui.MainActivity;
import com.ezhan.amr.utils.AppLifecycleTracker;
import com.ezhan.amr.utils.LocaleHelper;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 任务执行器 - 处理任务匹配和执行逻辑
 */
public class TaskExecutor {

    private static final String TAG = "TaskExecutor";
    private static TaskExecutor instance;

    private final JackViewModel jackViewModel;
    private final CruiseViewModel cruiseViewModel;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 800ms 内同一顶升任务的重复下发视为突发连点，直接忽略。
     * 注意：不依据 isTaskRunning 拦截——任何任务运行期间的长期拦截曾导致「点击没反应」。
     * 运行中的同任务去重由 JackActivity 基于实时导航状态处理。
     */
    private static final long JACK_DISPATCH_BURST_MS = 800L;
    private final Object jackDispatchLock = new Object();
    private int lastJackDispatchTaskId = -1;
    private long lastJackDispatchAt = 0L;

    // 任务执行状态回调接口
    public interface TaskExecutionCallback {
        void onTaskMatched(String taskType, String taskName);
        void onTaskStarted(String taskType, String taskName);
        void onTaskCompleted(String taskType, String taskName);
        void onTaskFailed(String taskType, String taskName, String reason);
    }

    private TaskExecutor(Context context, JackViewModel jackViewModel, CruiseViewModel cruiseViewModel) {
        this.jackViewModel = jackViewModel;
        this.cruiseViewModel = cruiseViewModel;
        this.appContext = context.getApplicationContext();
    }

    public static synchronized TaskExecutor getInstance(Context context, JackViewModel jackViewModel, CruiseViewModel cruiseViewModel) {
        if (instance == null) {
            instance = new TaskExecutor(context, jackViewModel, cruiseViewModel);
        }
        return instance;
    }

    /**
     * 执行任务
     * @param taskName 任务名称
     * @param callback 任务执行状态回调
     */
    public void executeByTaskName(String taskName, TaskExecutionCallback callback) {
        executeByTaskName(taskName, null, null, callback);
    }

    /**
     * 按指定任务类型和名称执行（Modbus 派发专用，避免类型混淆）
     */
    public void executeByTaskTypeAndName(String taskType, String taskName, TaskExecutionCallback callback) {
        executeByTaskTypeAndName(taskType, taskName, null, null, callback);
    }

    public void executeByTaskTypeAndName(String taskType, String taskName, String externalTaskId, String externalTaskName, TaskExecutionCallback callback) {
        new Thread(() -> {
            String type = taskType == null ? "" : taskType.toLowerCase();
            Log.i(TAG, "executeByTaskTypeAndName: type=" + type + " name=" + taskName);

            // 低电量拦截
            MainCommand command = MainCommand.fromString(type);
            if ((command == MainCommand.UNKNOWN || command == MainCommand.PARK)
                    && !BatteryLevelManager.getInstance().canAcceptNewTask()) {
                BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
                String reason = LocaleHelper.onServiceGetString(appContext, R.string.task_executor_battery_too_low,
                        BatteryLevelManager.getInstance().getCurrentBatteryLevel(), state.name());
                notifyTaskFailed(type, taskName, reason, callback);
                return;
            }

            // 按指定类型优先匹配
            switch (type) {
                case "delivery": {
                    String deliveryTask = findDeliveryTask(taskName);
                    if (deliveryTask != null) {
                        executeDeliveryByTaskName(deliveryTask, callback);
                        return;
                    }
                    break;
                }
                case "cruise": {
                    CruiseTask cruiseTask = findCruiseTask(taskName);
                    if (cruiseTask != null) {
                        executeCruiseByTaskName(cruiseTask, externalTaskId, externalTaskName, callback);
                        return;
                    }
                    break;
                }
                case "jack": {
                    JackTask jackTask = findJackTask(taskName);
                    if (jackTask != null) {
                        executeJackByTaskName(jackTask, externalTaskId, externalTaskName, callback);
                        return;
                    }
                    break;
                }
                case "charge":
                case "park": {
                    if (rejectChargeTaskIfAlreadyCharging(type, taskName, callback)) {
                        return;
                    }
                    executeMainByTaskName(type, callback);
                    return;
                }
                default:
                    break;
            }

            // 指定类型匹配不到，回退到通用匹配
            Log.w(TAG, "executeByTaskTypeAndName: type=" + type + " not matched by name, fallback to executeByTaskName");
            executeByTaskName(taskName, externalTaskId, externalTaskName, callback);
        }).start();
    }

    public void executeByTaskName(String taskName, String externalTaskId, String externalTaskName, TaskExecutionCallback callback) {
        new Thread(() -> {
            // 低电量拦截：charge/cancel/pause/resume 允许通过，其余类型需检查电量
            // park（前往待命点）在低电量时也拦截，应前往充电而非待命
            MainCommand command = MainCommand.fromString(taskName);
            TaskDebug8.log(String.format("[PARK] TaskExecutor.executeByTaskName taskName=%s command=%s batteryOk=%b level=%d",
                    taskName, command, BatteryLevelManager.getInstance().canAcceptNewTask(),
                    BatteryLevelManager.getInstance().getCurrentBatteryLevel()));
            if ((command == MainCommand.UNKNOWN || command == MainCommand.PARK)
                    && !BatteryLevelManager.getInstance().canAcceptNewTask()) {
                BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
                String reason = LocaleHelper.onServiceGetString(appContext, R.string.task_executor_battery_too_low,
                        BatteryLevelManager.getInstance().getCurrentBatteryLevel(), state.name());
                TaskDebug8.log(String.format("[PARK] TaskExecutor REJECTED low-battery taskName=%s state=%s level=%d",
                        taskName, state, BatteryLevelManager.getInstance().getCurrentBatteryLevel()));
                Log.w(TAG, "Task rejected: " + reason + ", taskName=" + taskName);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_unknown), taskName, reason, callback);
                return;
            }

            // 1. 尝试匹配巡航任务
            CruiseTask cruiseTask = findCruiseTask(taskName);
            if (cruiseTask != null) {
                executeCruiseByTaskName(cruiseTask, externalTaskId, externalTaskName, callback);
                return;
            }

            // 2. 尝试匹配顶升任务
            JackTask jackTask = findJackTask(taskName);
            if (jackTask != null) {
                executeJackByTaskName(jackTask, externalTaskId, externalTaskName, callback);
                return;
            }

            // 3. 尝试匹配背负任务
            String deliveryTask = findDeliveryTask(taskName);
            if (deliveryTask != null) {
                executeDeliveryByTaskName(deliveryTask, callback);
                return;
            }

            // 4. 尝试匹配主页面任务
            String mainTask = findMainTask(taskName);
            if (mainTask != null) {
                TaskDebug8.log(String.format("[PARK] TaskExecutor matched MAIN task taskName=%s", taskName));
                if (rejectChargeTaskIfAlreadyCharging(mainTask, taskName, callback)) {
                    return;
                }
                executeMainByTaskName(mainTask, callback);
                return;
            }

            // 3. 未找到匹配任务
            notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_unknown), taskName,
                    LocaleHelper.onServiceGetString(appContext, R.string.task_executor_task_not_found), callback);
        }).start();
    }

    /**
     * 执行任务 - 通过明确的任务类型和任务对象（新增方法）
     */
    public void executeByTaskObject(String taskType, List<Position> taskObject, TaskExecutionCallback callback) {
        new Thread(() -> {
            // 低电量拦截：charge/cancel/pause/resume 允许通过，其余类型（含 park）需检查电量
            if (!canBypassBatteryCheck(taskType)
                    && !BatteryLevelManager.getInstance().canAcceptNewTask()) {
                BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
                String reason = LocaleHelper.onServiceGetString(appContext, R.string.task_executor_battery_too_low,
                        BatteryLevelManager.getInstance().getCurrentBatteryLevel(), state.name());
                Log.w(TAG, "Task rejected: " + reason + ", taskType=" + taskType);
                notifyTaskFailed(taskType, taskObject.toString(), reason, callback);
                return;
            }

            switch (taskType.toLowerCase()) {
                case "delivery":
                    executeDeliveryByTaskObject(taskObject, callback);
                    break;

                case "jack":
                    executeJackByTaskObject(taskObject, callback);
                    break;

                case "cruise":
                    executeCruiseByTaskObject(taskObject, callback);
                    break;

                case "park":
                case "call":
                    executeMainByTaskObject(taskObject, callback);
                    break;

                case "charge":
                    if (rejectChargeTaskIfAlreadyCharging("charge", taskObject.toString(), callback)) {
                        return;
                    }
                    executeMainByTaskObject(taskObject, callback);
                    break;

                default:
                    notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_unknown), taskObject.toString(),
                            LocaleHelper.onServiceGetString(appContext, R.string.task_executor_unsupported_task_type, taskType), callback);
            }
        }).start();
    }

    private String filterTaskName(String name) {
        if (name == null) return null;
        // 连续替换掉"巡行："和"顶升"
        return name.replace("巡行：", "").replace("顶升：", "");
    }


    private CruiseTask findCruiseTask(String taskName) {
        Map<Integer, CruiseTask> cruiseMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (cruiseMap == null) {
            Log.w(TAG, "findCruiseTask: cruiseMap is null");
            return null;
        }

        // 清洗输入的任务名称
        String filteredInput = filterTaskName(taskName);
        Log.d(TAG, "findCruiseTask: searching for '" + filteredInput + "', available tasks: " + cruiseMap.size());

        // 1. 先按任务名称匹配
        for (CruiseTask task : cruiseMap.values()) {
            String filteredMapName = filterTaskName(task.getName());
            Log.d(TAG, "findCruiseTask: comparing with '" + filteredMapName + "' (id=" + task.getId() + ", stations=" + (task.getStationIds() != null ? task.getStationIds().size() : 0) + ")");
            if (filteredInput.equals(filteredMapName)) {
                Log.d(TAG, "findCruiseTask: FOUND task by name '" + task.getName() + "' id=" + task.getId());
                return task;
            }
        }
        // 2. 按序号匹配（Modbus 通过地址1传入序号，从1开始）
        try {
            int index = Integer.parseInt(filteredInput);
            if (index >= 1) {
                int i = 1;
                for (CruiseTask task : cruiseMap.values()) {
                    if (i == index) {
                        Log.d(TAG, "findCruiseTask: FOUND task by index " + index + " name='" + task.getName() + "' id=" + task.getId());
                        return task;
                    }
                    i++;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        Log.w(TAG, "findCruiseTask: task '" + filteredInput + "' not found");
        return null;
    }

    private JackTask findJackTask(String taskName) {
        Map<Integer, JackTask> jackMap = jackViewModel.getJackTaskMap().getValue();
        if (jackMap == null) {
            Log.w(TAG, "findJackTask: jackMap is null");
            return null;
        }
        // 清洗输入的任务名称
        String filteredInput = filterTaskName(taskName);
        Log.d(TAG, "findJackTask: searching for '" + filteredInput + "', available tasks: " + jackMap.size());

        // 1. 先按任务名称匹配
        for (JackTask task : jackMap.values()) {
            String filteredMapName = filterTaskName(task.getTaskName());
            Log.d(TAG, "findJackTask: comparing with '" + filteredMapName + "' (id=" + task.getTaskId() + ", ops=" + (task.getOperations() != null ? task.getOperations().size() : 0) + ")");
            if (filteredInput.equals(filteredMapName)) {
                Log.d(TAG, "findJackTask: FOUND task by name '" + task.getTaskName() + "' id=" + task.getTaskId());
                return task;
            }
        }
        // 2. 按序号匹配（Modbus 通过地址1传入序号，从1开始）
        try {
            int index = Integer.parseInt(filteredInput);
            if (index >= 1) {
                int i = 1;
                for (JackTask task : jackMap.values()) {
                    if (i == index) {
                        Log.d(TAG, "findJackTask: FOUND task by index " + index + " name='" + task.getTaskName() + "' id=" + task.getTaskId());
                        return task;
                    }
                    i++;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        Log.w(TAG, "findJackTask: task '" + filteredInput + "' not found");
        return null;
    }

    private String findDeliveryTask(String taskName) {
        if (taskName == null || taskName.trim().isEmpty()) {
            return null;
        }

        try {
            // Try to parse as integer (removes any whitespace)
            Integer.parseInt(taskName.trim());
            return taskName.trim();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String findMainTask(String taskName) {
        return taskName;
    }

    private void executeCruiseByTaskName(CruiseTask task, String externalTaskId, String externalTaskName, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.getName(), callback);

            // Check if CruiseActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(CruiseActivity.class)) {
                Log.d(TAG, "CruiseActivity is already running, not starting again");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.getName(), callback);
                // Send task to existing activity
                BroadcastHelper.getInstance().sendCruiseByTaskName(task.getId(), task.getName(), externalTaskId, externalTaskName);
                return;
            }

            try {
                // 启动巡航Activity并传递任务ID
                Intent intent = new Intent(appContext, CruiseActivity.class);
                intent.putExtra("task_id", task.getId());
                if (externalTaskId != null && !externalTaskId.trim().isEmpty()) {
                    intent.putExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_ID, externalTaskId.trim());
                }
                if (externalTaskName != null && !externalTaskName.trim().isEmpty()) {
                    intent.putExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_NAME, externalTaskName.trim());
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
                mainHandler.postDelayed(() ->
                        BroadcastHelper.getInstance().sendCruiseByTaskName(task.getId(), task.getName(), externalTaskId, externalTaskName), 1000);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.getName(), callback);
            } catch (Exception e) {
                Log.e(TAG, "启动巡航任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.getName(),
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeJackByTaskName(JackTask task, String externalTaskId, String externalTaskName, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.getTaskName(), callback);

            if (isJackDispatchBurst(task.getTaskId())) {
                TaskDebug1.log(String.format("[JACK] duplicate dispatch ignored taskId=%d name=%s (burst<%dms)",
                        task.getTaskId(), task.getTaskName(), JACK_DISPATCH_BURST_MS));
                // 视为同一次下发成功，保持接口幂等
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.getTaskName(), callback);
                return;
            }

            // Check if JackActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(JackActivity.class)) {
                Log.d(TAG, "JackActivity is already running, not starting again");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.getTaskName(), callback);
                BroadcastHelper.getInstance().sendJackByTaskName(task.getTaskId(), task.getTaskName(), externalTaskId, externalTaskName);
                return;
            }

            try {
                // 启动顶升Activity并传递任务ID
                Intent intent = new Intent(appContext, JackActivity.class);
                intent.putExtra("task_id", task.getTaskId());
                if (externalTaskId != null && !externalTaskId.trim().isEmpty()) {
                    intent.putExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_ID, externalTaskId.trim());
                }
                if (externalTaskName != null && !externalTaskName.trim().isEmpty()) {
                    intent.putExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_NAME, externalTaskName.trim());
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
                // 不再延迟发送广播，因为 Activity 已在 onCreate 中从 Intent 获取任务信息并执行
                // 延迟广播会导致任务执行两次（一次 onCreate + 一次广播）

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.getTaskName(), callback);
            } catch (Exception e) {
                Log.e(TAG, "启动顶升任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.getTaskName(),
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeDeliveryByTaskName(String task, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task, callback);

            // Check if DeliveryActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(DeliveryActivity.class)) {
                Log.d(TAG, "DeliveryActivity is already running, not starting again");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task, callback);
                BroadcastHelper.getInstance().sendDeliveryByTaskName(Integer.parseInt(task), task);
                return;
            }

            try {
                // 启动顶升Activity并传递任务ID
                Intent intent = new Intent(appContext, DeliveryActivity.class);
                intent.putExtra("task_id", Integer.parseInt(task));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task, callback);
            } catch (Exception e) {
                Log.e(TAG, "启动背负任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task,
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeMainByTaskName(String task, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_homepage), task, callback);

            // Check if MainActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(MainActivity.class)) {
                Log.d(TAG, "MainActivity is already running, bringing it to front");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_homepage), task, callback);
                TaskDebug8.log(String.format("[PARK] MainActivity running -> sendMainByTaskName code=%d name=%s",
                        convertChargeParkTask(task), task));
                bringMainActivityToFront();
                mainHandler.postDelayed(() ->
                        BroadcastHelper.getInstance().sendMainByTaskName(convertChargeParkTask(task), task), 300);
                return;
            }

            try {
                // 启动顶升Activity并传递任务ID
                Intent intent = new Intent(appContext, MainActivity.class);
                intent.putExtra("task_id", MainCommand.fromString(task).getCode());
                TaskDebug8.log(String.format("[PARK] start MainActivity task_id=%d name=%s", MainCommand.fromString(task).getCode(), task));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_homepage), task, callback);
            } catch (Exception e) {
                Log.e(TAG, "启动主页任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_homepage), task,
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeCruiseByTaskObject(List<Position> task, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.toString(), callback);

            // Check if CruiseActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(CruiseActivity.class)) {
                Log.d(TAG, "CruiseActivity is already running, not starting again");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.toString(), callback);
                // Send task object to existing activity
                BroadcastHelper.getInstance().sendCruiseByTaskObject(task);
                return;
            }

            try {
                // 启动巡航Activity并传递任务对象
                Intent intent = new Intent(appContext, CruiseActivity.class);
                intent.putExtra("task_object", (Serializable) task); // Pass the task object
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.toString(), callback);
            } catch (Exception e) {
                Log.e(TAG, "启动巡航任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_cruise), task.toString(),
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeJackByTaskObject(List<Position> task, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.toString(), callback);

            // Check if JackActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(JackActivity.class)) {
                Log.d(TAG, "JackActivity is already running, not starting again");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.toString(), callback);
                // Send task object to existing activity
                BroadcastHelper.getInstance().sendJackByTaskObject(task);
                return;
            }

            try {
                // 启动顶升Activity并传递任务对象
                Intent intent = new Intent(appContext, JackActivity.class);
                intent.putExtra("task_object", (Serializable) task); // Pass the task object
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.toString(), callback);
            } catch (Exception e) {
                Log.e(TAG, "启动顶升任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_jack), task.toString(),
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeDeliveryByTaskObject(List<Position> task, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task.toString(), callback);

            // Check if DeliveryActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(DeliveryActivity.class)) {
                Log.d(TAG, "DeliveryActivity is already running, not starting again");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task.toString(), callback);
                BroadcastHelper.getInstance().sendDeliveryByTaskObject(task);
                return;
            }

            try {
                // 启动背负Activity并传递任务ID
                Intent intent = new Intent(appContext, DeliveryActivity.class);
                intent.putExtra("task_object", (Serializable) task); // Pass the task object
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task.toString(), callback);
            } catch (Exception e) {
                Log.e(TAG, "启动背负任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_backpack), task.toString(),
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    private void executeMainByTaskObject(List<Position> task, TaskExecutionCallback callback) {
        mainHandler.post(() -> {
            notifyTaskMatched(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_park_charge_call), task.toString(), callback);

            // Check if DeliveryActivity is already running
            if (AppLifecycleTracker.getInstance().isActivityRunning(MainActivity.class)) {
                Log.d(TAG, "MainActivity is already running, bringing it to front");
                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_park_charge_call), task.toString(), callback);
                bringMainActivityToFront();
                mainHandler.postDelayed(() ->
                        BroadcastHelper.getInstance().sendMainByTaskObject(task), 300);
                return;
            }

            try {
                // 启动背负Activity并传递任务ID
                Intent intent = new Intent(appContext, MainActivity.class);
                intent.putExtra("task_object", (Serializable) task); // Pass the task object
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                notifyTaskStarted(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_park_charge_call), task.toString(), callback);
            } catch (Exception e) {
                Log.e(TAG, "启动驻车/充电/呼叫任务失败", e);
                notifyTaskFailed(LocaleHelper.onServiceGetString(appContext, R.string.task_executor_park_charge_call), task.toString(),
                        LocaleHelper.onServiceGetString(appContext, R.string.task_executor_start_failed, e.getMessage()), callback);
            }
        });
    }

    // 回调通知方法
    private void bringMainActivityToFront() {
        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        appContext.startActivity(intent);
    }

    private void notifyTaskMatched(String taskType, String taskName, TaskExecutionCallback callback) {
        if (callback != null) {
            mainHandler.post(() -> callback.onTaskMatched(taskType, taskName));
        }
    }

    private void notifyTaskStarted(String taskType, String taskName, TaskExecutionCallback callback) {
        if (callback != null) {
            mainHandler.post(() -> callback.onTaskStarted(taskType, taskName));
        }
    }

    private void notifyTaskFailed(String taskType, String taskName, String reason, TaskExecutionCallback callback) {
        if (callback != null) {
            mainHandler.post(() -> callback.onTaskFailed(taskType, taskName, reason));
        }
    }

    private void notifyTaskCompleted(String taskType, String taskName, TaskExecutionCallback callback) {
        if (callback != null) {
            mainHandler.post(() -> callback.onTaskCompleted(taskType, taskName));
        }
    }

    /**
     * 充电中拒绝重复下发充电任务。
     */
    private boolean rejectChargeTaskIfAlreadyCharging(String taskType, String taskName,
                                                      TaskExecutionCallback callback) {
        if (!"charge".equalsIgnoreCase(taskType)
                && MainCommand.fromString(taskType) != MainCommand.CHARGE) {
            return false;
        }
        if (!ChargeTaskNavigationHelper.shouldRejectChargeTaskDispatch()) {
            return false;
        }
        String reason = LocaleHelper.onServiceGetString(appContext, R.string.charging_task_failed);
        Log.w(TAG, "Charge task rejected: robot is already charging, task=" + taskName);
        notifyTaskFailed(taskType, taskName, reason, callback);
        return true;
    }

    /**
     * 判断任务类型是否可以绕过电池检查。
     * charge/cancel/pause/resume 始终允许执行。
     * park（前往待命点）不绕过：低电量时应前往充电而非待命。
     */
    private boolean canBypassBatteryCheck(String taskType) {
        if (taskType == null) return false;
        switch (taskType.toLowerCase()) {
            case "charge":
            case "cancel":
            case "pause":
            case "resume":
                return true;
            default:
                return false;
        }
    }

    private int convertChargeParkTask(String taskName) {
        switch (taskName) {
            case "charge":
                return 1;
            case "park":
                return 2;
            default:
                return 0;
        }
    }

    /** 同一顶升任务在 800ms 内重复下发返回 true；时间戳始终更新，超窗后自动放行 */
    private boolean isJackDispatchBurst(int taskId) {
        synchronized (jackDispatchLock) {
            long now = System.currentTimeMillis();
            boolean burst = taskId == lastJackDispatchTaskId
                    && now - lastJackDispatchAt < JACK_DISPATCH_BURST_MS;
            lastJackDispatchTaskId = taskId;
            lastJackDispatchAt = now;
            return burst;
        }
    }
}
