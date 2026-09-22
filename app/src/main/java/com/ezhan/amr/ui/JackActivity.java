package com.ezhan.amr.ui;

import static com.ezhan.amr.ui.MainActivity.DEFAULT_CONFIDENCE;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.JackOperation;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.MapPoints;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.ExternalTaskContext;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.utils.GifTypeConstants;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class JackActivity extends BaseNavigationActivity {
    private String TAG = "JackActivity";
    private static final int INFINITE_JACK_LOOP_COUNT = -1;
    private static final int MIN_JACK_LOOP_COUNT = 1;
    private static final int MAX_JACK_LOOP_COUNT = 999;
    private JackViewModel jackViewModel;
    private CruiseViewModel cruiseViewModel;
    private MapViewModel mapViewModel;
    private GeneralNavigationHandler navigationHandler;
    private LinearLayout taskContainer;
    private ImageView ivNoDataBackground; // 背景图视图
    private TextView tvNoData;
    private MaterialButton btnCreateTask, btnLiftDown, btnLiftUp;
    private Map<Integer, JackTask> jackTaskMap = new LinkedHashMap<>();
    private Map<Integer, CruiseTask> cruiseTaskMap = new LinkedHashMap<>();
    private AtomicInteger currentWaypointIndex = new AtomicInteger(0);
    private static final int REQUEST_EDIT_TASK = 1001;
    private int currentExecutingTaskId = -1;
    private int currentJackLoopIndex = 1;
    private long lastTouchTime = 0;
    private final Handler jackAnimationHandler = new Handler(Looper.getMainLooper());
    private final Handler jackStartHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingJackStartRunnable;
    /**
     * 防重复启动（跨 Activity 实例共享）。仅依据实时状态判断，无时间窗口：
     * - sPendingStartTaskId：已排定 600ms 延迟启动、尚未执行的任务，防 onCreate+广播双触发
     * - sRunningJackTaskId：最近一次 startNavigation 的顶升任务，配合 isTaskRunning() 防运行中重复下发
     * 两者都会随导航结束自动失效，不会永久拦截点击。
     */
    private static final Object JACK_START_LOCK = new Object();
    private static int sPendingStartTaskId = -1;
    private static int sRunningJackTaskId = -1;
    /** 任务结束（完成/取消/失败）时清除运行标记，避免残留导致后续同名任务被误拦 */
    private final GeneralNavigationHandler.TaskCompletionListener jackTaskEndListener =
            new GeneralNavigationHandler.TaskCompletionListener() {
                @Override
                public void onTaskCompleted() {
                    clearRunningJackTaskId();
                }

                @Override
                public void onTaskCancelled() {
                    clearRunningJackTaskId();
                }

                @Override
                public void onTaskFailed() {
                    clearRunningJackTaskId();
                }
            };
    private Runnable jackAnimationRunnable;

    private static void clearRunningJackTaskId() {
        synchronized (JACK_START_LOCK) {
            sRunningJackTaskId = -1;
        }
    }
    private String currentJackAnimationDescription = "";
    private String currentExternalTaskId = "";
    private String currentExternalTaskName = "";
    private ImageView ivNoDataBackground1; // 声明变量

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jack);
        navigationHandler = getNavigationHandler();
        if (navigationHandler != null) {
            navigationHandler.addTaskCompletionListener(jackTaskEndListener);
        }
        setupViews();
        initTaskViews();
        setupObservers();

        dispatchJackTaskFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatchJackTaskFromIntent(intent);
    }

    private void dispatchJackTaskFromIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        int taskId = intent.getIntExtra("task_id", -1);
        String externalTaskId = intent.getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_ID);
        String externalTaskName = intent.getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_NAME);
        if (taskId != -1) {
            currentExecutingTaskId = taskId;
            executeJackByTaskId(taskId, externalTaskId, externalTaskName);
            return;
        }
        List<Position> jackTask = (List<Position>) intent.getSerializableExtra("task_object");
        if (jackTask != null) {
            executeJackByTaskObject(jackTask);
        }
    }

    private void setupJackLoopCountInput(EditText editText) {
        if (editText == null) {
            return;
        }
        editText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            StringBuilder proposedValue = new StringBuilder(dest);
            proposedValue.replace(dstart, dend, source.subSequence(start, end).toString());
            return isAllowedJackLoopCountInput(proposedValue.toString()) ? null : "";
        }});
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeJackLoopCountInput(editText);
            }
        });
    }

    private boolean isAllowedJackLoopCountInput(String value) {
        if (value.isEmpty() || "-".equals(value)) {
            return true;
        }
        if (String.valueOf(INFINITE_JACK_LOOP_COUNT).equals(value)) {
            return true;
        }
        if (value.startsWith("-") || value.length() > 3) {
            return false;
        }
        try {
            int count = Integer.parseInt(value);
            return count >= MIN_JACK_LOOP_COUNT && count <= MAX_JACK_LOOP_COUNT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int normalizeJackLoopCountInput(EditText editText) {
        int count = getValidJackLoopCount(editText == null ? "" : editText.getText().toString());
        if (editText != null) {
            String normalizedValue = String.valueOf(count);
            if (!normalizedValue.contentEquals(editText.getText())) {
                editText.setText(normalizedValue);
                editText.setSelection(normalizedValue.length());
            }
        }
        return count;
    }

    private int getValidJackLoopCount(String value) {
        try {
            int count = Integer.parseInt(value.trim());
            if (count == INFINITE_JACK_LOOP_COUNT || (count >= MIN_JACK_LOOP_COUNT && count <= MAX_JACK_LOOP_COUNT)) {
                return count;
            }
        } catch (NumberFormatException ignored) {
        }
        return MIN_JACK_LOOP_COUNT;
    }

    private String getJackLoopCountDisplay(int loopCount) {
        return loopCount == INFINITE_JACK_LOOP_COUNT ? getString(R.string.infinite_loop) : String.valueOf(loopCount);
    }

    public String getCurrentJackLoopProgressText() {
        if (currentExecutingTaskId == -1 || jackTaskMap == null) {
            return "";
        }

        JackTask task = jackTaskMap.get(currentExecutingTaskId);
        if (task == null) {
            return "";
        }

        return getString(
                R.string.jack_loop_format,
                currentJackLoopIndex + "/" + getJackLoopCountDisplay(task.getLoopCount()));
    }

    /**
     * Activity销毁时清理资源
     */
    @Override
    protected void onDestroy() {
        // Then do our own cleanup
        stopJackAnimationGuard();
        cancelPendingJackStart();
        if (navigationHandler != null) {
            navigationHandler.removeTaskCompletionListener(jackTaskEndListener);
        }
        onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");

        // Clean up dialogs
        if (pauseDialog != null && pauseDialog.isShowing()) {
            pauseDialog.dismiss();
        }

        // Clean up navigation
        if (navigationHandler != null) {
            navigationHandler.cleanup(false);
        }

        // Stop music and clean up player
        if (musicPlayer != null) {
            musicPlayer.stop();
            musicPlayer.setListener(null);
        }

        // Clean up TTS
        if (ttsHelper != null) {
            ttsHelper.shutdown();
        }

        // Clear GIF view (after parent's cleanup)
        if (gifView != null) {
            gifView.setImageDrawable(null);
            gifView = null;
        }

        // First call parent's cleanup which handles Glide
        super.onDestroy();
    }
    private void setupViews() {
        jackViewModel = MyApplication.getInstance().getJackViewModel();
        cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        // 初始化背景图视图
        ivNoDataBackground = findViewById(R.id.ivNoDataBackground);
        tvNoData = findViewById(R.id.tvNoData);
        ivNoDataBackground1 = findViewById(R.id.ivNoDataBackground1);
        // 初始化GIF视图
        gifView = findViewById(R.id.gifView);
        gifView.setVisibility(View.GONE);
        gifView.setScaleType(ImageView.ScaleType.CENTER_CROP);  // 修改自适应类型

        gifView.setOnTouchListener((v, event) -> {
            // Debounce rapid touches
            if (System.currentTimeMillis() - lastTouchTime < 300) {
                return true;
            }
            lastTouchTime = System.currentTimeMillis();
            if (navigationHandler.isTaskRunning() && navigationHandler.isGifPlaying()) {
                // Disable further touches while dialog is showing
                gifView.setClickable(false);
                pauseAndShowDialog();
                return true;
            }
            return false;
        });

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        tvNavigationStatus = findViewById(R.id.tvNavigationStatus);
    }

    private void setupObservers() {
        // 观察任务数据变化
        jackViewModel.getJackTaskMap().observe(this, jackTaskMap -> {
            this.jackTaskMap = jackTaskMap != null ? jackTaskMap : new LinkedHashMap<>();

            // 根据任务数据更新背景图可见性
            updateBackgroundVisibility();

            if (jackTaskMap != null && !jackTaskMap.isEmpty()) {
                taskContainer.removeAllViews(); // 清空旧视图
                for (Map.Entry<Integer, JackTask> entry : jackTaskMap.entrySet()) {
                    JackTask task = entry.getValue();
                    addTaskToView(task);
                }
            } else {
                taskContainer.removeAllViews(); // 没有任务时清空容器
            }
        });
        cruiseViewModel.getCruiseTaskMap().observe(this, cruiseTaskMap -> {
            this.cruiseTaskMap = cruiseTaskMap != null ? cruiseTaskMap : new LinkedHashMap<>();
        });
    }
    // --------------------------------------------------------------------------------------------

    // 导航逻辑

    private String normalizeExternalTaskId(String externalTaskId) {
        return externalTaskId == null ? "" : externalTaskId.trim();
    }

    private String normalizeExternalTaskName(String externalTaskName) {
        return externalTaskName == null ? "" : externalTaskName.trim();
    }

    private boolean hasExternalTaskId() {
        return currentExternalTaskId != null && !currentExternalTaskId.trim().isEmpty();
    }

    private boolean hasExternalTaskName() {
        return currentExternalTaskName != null && !currentExternalTaskName.trim().isEmpty();
    }

    private void applyCurrentExternalTaskId(List<Position> positions) {
        if (!hasExternalTaskId() || positions == null) {
            return;
        }
        String taskId = currentExternalTaskId.trim();
        for (Position position : positions) {
            if (position != null && position.getTaskType() == 3) {
                position.setTaskId(taskId);
            }
        }
    }

    private void markJackTaskExecuting(JackTask task) {
        String taskName = hasExternalTaskName()
                ? currentExternalTaskName.trim()
                : (task != null ? task.getTaskName() : "jack");
        if (hasExternalTaskId()) {
            MyApplication.getInstance().getTaskViewModel()
                    .markTaskExecuting("jack", taskName, currentExternalTaskId.trim());
        } else {
            MyApplication.getInstance().getTaskViewModel()
                    .markTaskExecutingIfUnset("jack", taskName);
        }
    }

    private void applyExternalTaskContext() {
        if (!hasExternalTaskId() || !hasExternalTaskName()) {
            return;
        }
        navigationHandler.setExternalTaskContext(new ExternalTaskContext(
                "jack",
                currentExternalTaskId,
                currentExternalTaskName,
                "HTTP"
        ));
    }

    void executeJackByTaskId(int taskId) {
        executeJackByTaskId(taskId, null);
    }

    void executeJackByTaskId(int taskId, String externalTaskId) {
        executeJackByTaskId(taskId, externalTaskId, null);
    }

    void executeJackByTaskId(int taskId, String externalTaskId, String externalTaskName) {
        synchronized (JACK_START_LOCK) {
            if (taskId == sPendingStartTaskId) {
                TaskDebug1.log(String.format(
                        "[JACK] duplicate executeJackByTaskId ignored taskId=%d reason=startPending", taskId));
                return;
            }
            boolean navRunning = navigationHandler != null && navigationHandler.isTaskRunning();
            if (navRunning && taskId == sRunningJackTaskId) {
                TaskDebug1.log(String.format(
                        "[JACK] duplicate executeJackByTaskId ignored taskId=%d reason=alreadyRunning", taskId));
                return;
            }
        }
        cancelPendingJackStart();
        currentExecutingTaskId = taskId;
        currentExternalTaskId = normalizeExternalTaskId(externalTaskId);
        currentExternalTaskName = normalizeExternalTaskName(externalTaskName);
        jackTaskMap = jackViewModel.getJackTaskMap().getValue();

        if (jackTaskMap != null && jackTaskMap.containsKey(taskId)) {
            JackTask task = jackTaskMap.get(taskId);
            assert task != null;
            if (task.getOperations() == null || task.getOperations().isEmpty()) {
                onShowToast(R.string.task_list_empty);
                return;
            }
            List<Position> fullPositionList =
                    generateFullPositionList(currentPosition, generateJackPositionList(Objects.requireNonNull(jackTaskMap.get(taskId))));
            if (fullPositionList == null) {
                TaskDebug1.log(String.format("[JACK] executeJackByTaskId taskId=%d fullPositionList=null ABORT", taskId));
                return;
            }
            StringBuilder routeInfo = new StringBuilder();
            for (int i = 0; i < fullPositionList.size(); i++) {
                Position p = fullPositionList.get(i);
                routeInfo.append(String.format("[%d]%s t=%d tt=%d ", i, p.getName(), p.getType(), p.getTaskType()));
            }
            TaskDebug1.log(String.format("[JACK] executeJackByTaskId taskId=%d name=%s ops=%d routeSize=%d %s",
                    taskId, task.getTaskName(), task.getOperations().size(),
                    fullPositionList.size(), routeInfo.toString().trim()));
            if (!task.getOperations().isEmpty()) {
                JackOperation firstOp = task.getOperations().get(0);
                TaskDebug1.log(String.format("[SKIP1ST] jackTask firstOp point=%s actionType=%d stayDuration=%d",
                        firstOp.getPointName(), firstOp.getActionType(), firstOp.getDuration()));
            }
            applyCurrentExternalTaskId(fullPositionList);
            currentJackLoopIndex = 1;
            updateJackAnimationDescription(fullPositionList);
            onVoicePrompt(VoiceKeyConstants.START_EXECUTING_JACK_TASK);
            synchronized (JACK_START_LOCK) {
                sPendingStartTaskId = taskId;
            }
            pendingJackStartRunnable = new Runnable() {
                @Override
                public void run() {
                    pendingJackStartRunnable = null;
                    synchronized (JACK_START_LOCK) {
                        sPendingStartTaskId = -1;
                    }
                    if (!isActivityValid()) {
                        Log.w(TAG, "Activity destroyed, skipping delayed task");
                        return;
                    }
                    Log.d(TAG, "start jack task in executeJackByTaskId");
                    if (!checkBatteryCanAcceptTask()) {
                        return;
                    }
                    startJackAnimationGuard();

                    // Set loop info before starting navigation
                    int loopCount = task.getLoopCount();
                    navigationHandler.setJackLoopInfo(currentJackLoopIndex, loopCount);
                    applyExternalTaskContext();
                    markJackTaskExecuting(task);
                    synchronized (JACK_START_LOCK) {
                        sRunningJackTaskId = taskId;
                    }
                    TaskDebug1.log(String.format("[JACK] startNavigation taskId=%d loop=%d/%d",
                            taskId, currentJackLoopIndex, loopCount));
                    navigationHandler.startNavigation(fullPositionList, () -> handleJackLoopCompletion(task, currentJackLoopIndex));
                }
            };
            jackStartHandler.postDelayed(pendingJackStartRunnable, 600);
        } else {
            handleTaskNotFound(taskId);
        }
    }

    /** 取消尚未执行的 600ms 延迟启动，并同步清除跨实例的待启动标记 */
    private void cancelPendingJackStart() {
        if (pendingJackStartRunnable != null) {
            jackStartHandler.removeCallbacks(pendingJackStartRunnable);
            pendingJackStartRunnable = null;
            synchronized (JACK_START_LOCK) {
                sPendingStartTaskId = -1;
            }
        }
    }

    void executeJackByTaskObject(List<Position> selectedPosition) {
        currentExternalTaskId = "";
        currentExternalTaskName = "";
        // 根据任务ID生成route, 调用导航模块
        List<Position> fullPositionList =
                generateFullPositionList(currentPosition, selectedPosition);
        if (fullPositionList == null) {
            return;
        }
        updateJackAnimationDescription(fullPositionList);
        onVoicePrompt(VoiceKeyConstants.START_EXECUTING_JACK_TASK);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isActivityValid()) {
                    Log.w(TAG, "Activity destroyed, skipping delayed task");
                    return;
                }
                Log.d(TAG, "start jack task in executeJackByTaskObject");
                if (!checkBatteryCanAcceptTask()) {
                    return;
                }
                startJackAnimationGuard();
                navigationHandler.setJackLoopInfo(1, 1);
                markJackTaskExecuting(null);
                navigationHandler.startNavigation(fullPositionList, () -> {
                    // Completion callback
                    //runOnUiThread(() -> onShowToast(R.string.task_completed));
                    stopJackAnimationGuard();
                });
            }
        },600);//延迟600ms,等待语音播报完
    }

    private void handleJackLoopCompletion(JackTask task, int currentLoopIndex) {
        int loopCount = task.getLoopCount();

        if (loopCount == INFINITE_JACK_LOOP_COUNT || currentLoopIndex < loopCount) {
            int nextLoopIndex = currentLoopIndex + 1;
            runOnUiThread(() -> {
                if (isActivityValid()) {
                    // Update current loop index BEFORE generating new route
                    currentJackLoopIndex = nextLoopIndex;

                    List<Position> fullPositionList =
                            generateFullPositionList(currentPosition, generateJackPositionList(task));
                    if (fullPositionList == null) {
                        return;
                    }
                    applyCurrentExternalTaskId(fullPositionList);
                    updateJackAnimationDescription(fullPositionList);
                    Log.d(TAG, "start jack task loop " + nextLoopIndex + " for task " + task.getTaskId());
                    startJackAnimationGuard();

                    // Set loop info before starting next loop
                    navigationHandler.setJackLoopInfo(currentJackLoopIndex, loopCount);
                    applyExternalTaskContext();
                    markJackTaskExecuting(task);
                    navigationHandler.startNavigation(fullPositionList, () -> handleJackLoopCompletion(task, nextLoopIndex));
                }
            });
        } else {
            // Final loop completed - stop animation
            stopJackAnimationGuard();
            synchronized (JACK_START_LOCK) {
                sRunningJackTaskId = -1;
            }
        }
    }

    private boolean shouldContinueJackLoop(JackTask task) {
        if (task == null) {
            return false;
        }
        int loopCount = task.getLoopCount();
        return loopCount == INFINITE_JACK_LOOP_COUNT || currentJackLoopIndex < loopCount;
    }

    private void startJackAnimationGuard() {
        showJackTaskAnimationIfNeeded();
        if (jackAnimationRunnable != null) {
            jackAnimationHandler.removeCallbacks(jackAnimationRunnable);
        }

        jackAnimationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isActivityValid()) {
                    return;
                }
                if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                    return;
                }
                if (!navigationHandler.isNavigationStopped()) {
                    showJackTaskAnimationIfNeeded();
                }
                jackAnimationHandler.postDelayed(this, 1500);
            }
        };
        jackAnimationHandler.postDelayed(jackAnimationRunnable, 1500);
    }

    private void stopJackAnimationGuard() {
        if (jackAnimationRunnable != null) {
            jackAnimationHandler.removeCallbacks(jackAnimationRunnable);
            jackAnimationRunnable = null;
        }
    }

    private void showJackTaskAnimationIfNeeded() {
        boolean handlerNotPlaying = navigationHandler == null || !navigationHandler.isGifPlaying();
        if (handlerNotPlaying) {
            onGifPlayback(true, GifTypeConstants.NAVIGATING_NORMAL, getJackAnimationDescription());
        }
    }

    private void updateJackAnimationDescription(List<Position> route) {
        currentJackAnimationDescription = "";
        if (route == null || route.isEmpty()) {
            return;
        }

        for (Position position : route) {
            if (position != null && position.getTaskType() == 3 && hasPositionName(position)) {
                currentJackAnimationDescription = position.getName();
                return;
            }
        }

        for (Position position : route) {
            if (position != null && hasPositionName(position)) {
                currentJackAnimationDescription = position.getName();
                return;
            }
        }
    }

    private boolean hasPositionName(Position position) {
        String name = position.getName();
        return name != null && !name.trim().isEmpty() && !"null".equalsIgnoreCase(name.trim());
    }

    private String getJackAnimationDescription() {
        if (currentJackAnimationDescription == null || currentJackAnimationDescription.trim().isEmpty()) {
            return getString(R.string.start_lifting_task);
        }
        return currentJackAnimationDescription;
    }

    private void handleTaskNotFound(int taskId) {
        Log.w(TAG, "Jack task not found for taskId: " + taskId);
        finish();
    }

    private List<Position> generateJackPositionList(JackTask jackTask) {
        List<Position> positions = new ArrayList<>();

        // Get current orbit mode setting (fixed path / free navigation)
        boolean isVirtualOrbitMode = basicViewModel != null &&
                basicViewModel.getIsVirtualOrbitMode().getValue() != null &&
                basicViewModel.getIsVirtualOrbitMode().getValue();

        int operationNum = jackTask.getOperations().size();

        // Get loop count
        int loopCount = jackTask.getLoopCount();

        // ⭐ CRITICAL: Only generate positions for the CURRENT loop (currentJackLoopIndex)
        // Not for all loops from 1 to loopCount
        int currentLoop = currentJackLoopIndex;

        // Log start of position generation
        if (debugger != null) {
            debugger.logInfo("========== generateJackPositionList ==========");
            debugger.logInfo(String.format("JackTask: id=%d, name='%s', loopCount=%d (%s), operationCount=%d",
                    jackTask.getTaskId(),
                    jackTask.getTaskName(),
                    loopCount,
                    loopCount == INFINITE_JACK_LOOP_COUNT ? "INFINITE" : "finite",
                    operationNum));
            debugger.logInfo(String.format("Configuration: isVirtualOrbitMode=%b", isVirtualOrbitMode));
            debugger.logInfo(String.format("⭐ CURRENT LOOP INDEX: %d (only generating this loop)", currentLoop));
            if (basicViewModel != null && basicViewModel.getVirtualOrbitObstacleTime().getValue() != null) {
                debugger.logInfo(String.format("Virtual orbit obstacle time: %d",
                        basicViewModel.getVirtualOrbitObstacleTime().getValue()));
            }
        }

        // ⭐ Generate positions ONLY for the current loop, not all loops
        if (debugger != null) {
            debugger.logInfo(String.format("\n🔄 Generating LOOP %d of %s",
                    currentLoop,
                    loopCount == INFINITE_JACK_LOOP_COUNT ? "∞" : String.valueOf(loopCount)));
        }

        for (int operationIndex = 0; operationIndex < jackTask.getOperations().size(); operationIndex++) {
            JackOperation operation = jackTask.getOperations().get(operationIndex);

            if (debugger != null) {
                debugger.logInfo(String.format("  📍 Operation %d/%d: pointName='%s', pointId=%s, floor=%d, actionType=%d, duration=%d",
                        operationIndex + 1, operationNum,
                        operation.getPointName(),
                        operation.getPointId(),
                        operation.getFloor(),
                        operation.getActionType(),
                        operation.getDuration()));
            }

            Position position = getPositionByIdAndFloor(operation.getPointId(), operation.getFloor());
            if (position == null) {
                if (debugger != null) {
                    debugger.logWarning("generateJackPositionList",
                            String.format("Position not found: name='%s', id=%s, floor=%d - ABORTING TASK",
                                    operation.getPointName(), operation.getPointId(), operation.getFloor()));
                }
                return new ArrayList<>();
            }

            if (debugger != null) {
                debugger.logInfo(String.format("    ✅ Found position: id=%d, name='%s', type=%d, floor='%s', coordinates=(%.2f, %.2f)",
                        position.getId(),
                        position.getName(),
                        position.getType(),
                        position.getFloor(),
                        position.getPosX(),
                        position.getPosY()));
            }

            // Create a copy to avoid modifying original position
            Position modifiedPosition = new Position(position.getId(), position.getName(),
                    position.getPosX(), position.getPosY(), position.getYaw());
            modifiedPosition.setFloor(position.getFloor());
            modifiedPosition.setType(position.getType());

            // Build the message with progress info - Use current loop index
            String message;
            if (loopCount == INFINITE_JACK_LOOP_COUNT) {
                message = getString(R.string.resuming_format,
                        modifiedPosition.getName(),
                        operationIndex + 1,
                        operationNum,
                        currentLoop,  // Use current loop index
                        getJackLoopCountDisplay(loopCount));
            } else {
                message = getString(R.string.resuming_format,
                        modifiedPosition.getName(),
                        operationIndex + 1,
                        operationNum,
                        currentLoop,  // Use current loop index
                        getJackLoopCountDisplay(loopCount));
            }
            modifiedPosition.setMessage(message);

            if (debugger != null) {
                debugger.logInfo(String.format("    📝 MESSAGE set: '%s'", message));
            }

            modifiedPosition.setTaskType(3);
            modifiedPosition.setMapName(position.getMapName());
            modifiedPosition.setVirtualOrbit(isVirtualOrbitMode);
            modifiedPosition.setStayDuration(operation.getDuration());

            // Set virtual orbit obstacle time
            if (basicViewModel != null && basicViewModel.getVirtualOrbitObstacleTime().getValue() != null) {
                int obstacleTime = basicViewModel.getVirtualOrbitObstacleTime().getValue();
                modifiedPosition.setVirtualOrbitObstacleTime(obstacleTime);
                if (debugger != null) {
                    debugger.logInfo(String.format("    ⚙️ Virtual orbit obstacle time: %d", obstacleTime));
                }
            }

            // Update type based on actionType
            int originalActionType = operation.getActionType();
            switch (originalActionType) {
                case 0:
                    modifiedPosition.setType(5);
                    if (debugger != null) {
                        debugger.logInfo("    🎬 ActionType 0 -> Position type 5 (RECOGNIZE_LOAD)");
                    }
                    break;
                case 1:
                    modifiedPosition.setType(6);
                    if (debugger != null) {
                        debugger.logInfo("    🎬 ActionType 1 -> Position type 6 (RECOGNIZE_UNLOAD)");
                    }
                    break;
                case 2:
                    modifiedPosition.setType(7);
                    if (debugger != null) {
                        debugger.logInfo("    🎬 ActionType 2 -> Position type 7 (RECOGNIZE_NONE)");
                    }
                    break;
                case 3:
                    modifiedPosition.setType(1);
                    if (debugger != null) {
                        debugger.logInfo("    🎬 ActionType 3 -> Position type 1 (DELIVERY)");
                    }
                    break;
                case 4:
                    modifiedPosition.setType(8);
                    if (debugger != null) {
                        debugger.logInfo("    🎬 ActionType 4 -> Position type 8 (NON_RECOGNIZE_LOAD - 顶升举起)");
                    }
                    break;
                case 5:
                    modifiedPosition.setType(9);
                    if (debugger != null) {
                        debugger.logInfo("    🎬 ActionType 5 -> Position type 9 (NON_RECOGNIZE_UNLOAD - 顶升放下)");
                    }
                    break;
                case 6:
                    modifiedPosition.setType(17);
                    if (debugger != null) {
                        debugger.logInfo("    ActionType 6 -> Fixed move forward");
                    }
                    break;
                case 7:
                    modifiedPosition.setType(18);
                    if (debugger != null) {
                        debugger.logInfo("    ActionType 7 -> Fixed move backward");
                    }
                    break;
                case 8:
                    modifiedPosition.setType(19);
                    if (debugger != null) {
                        debugger.logInfo("    ActionType 8 -> Position type 19 (RECOGNIZE_ENTRY_ONLY)");
                    }
                    break;
                default:
                    if (debugger != null) {
                        debugger.logWarning("generateJackPositionList",
                                String.format("Unknown actionType: %d - SKIPPING", originalActionType));
                    }
                    continue;
            }

            // Log stay duration
            if (debugger != null && operation.getDuration() > 0) {
                debugger.logInfo(String.format("    ⏱️ Stay duration: %d ms", operation.getDuration()));
            }

            positions.add(modifiedPosition);

            if (debugger != null) {
                debugger.logInfo(String.format("    ✅ Position added to route (total positions now: %d)", positions.size()));
            }
        }

        // Log final summary
        if (debugger != null) {
            debugger.logInfo("\n========== generateJackPositionList SUMMARY ==========");
            debugger.logInfo(String.format("Total positions generated for loop %d: %d", currentLoop, positions.size()));
            debugger.logInfo("\n📋 FINAL POSITION LIST:");
            for (int i = 0; i < positions.size(); i++) {
                Position pos = positions.get(i);
                debugger.logInfo(String.format("  [%d] name='%s', type=%d, taskType=%d, message='%s', stayDuration=%d",
                        i,
                        pos.getName(),
                        pos.getType(),
                        pos.getTaskType(),
                        pos.getMessage() != null ? pos.getMessage() : "null",
                        pos.getStayDuration()));
            }
            debugger.logInfo("==================================================");
        }
        if (!positions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < positions.size(); i++) {
                Position pos = positions.get(i);
                sb.append(String.format("[%d]%s t=%d ", i, pos.getName(), pos.getType()));
            }
            TaskDebug1.log(String.format("[JACK] generateJackPositionList loop=%d count=%d %s",
                    currentLoop, positions.size(), sb.toString().trim()));
        } else {
            TaskDebug1.log(String.format("[JACK] generateJackPositionList loop=%d EMPTY", currentLoop));
        }

        return positions;
    }

    private Position getPositionByIdAndFloor(Integer pointId, int floor) {
        if (pointId == null) {
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

        for (Position position: workPoints) {
            if (position.getId() == pointId) {
                return position;
            }
        }

        return null;
    }

    // --------------------------------------------------------------------------------------------

    // 导航相关UI
    private void initTaskViews() {
        taskContainer = findViewById(R.id.taskContainer);
        btnCreateTask = findViewById(R.id.btnCreateTask);
        btnCreateTask.setOnClickListener(v -> showCreateDialog());

        btnLiftDown = findViewById(R.id.btnLiftDown);
        btnLiftDown.setOnClickListener(v -> {
            ttsHelper.speak(getString(R.string.descending));
            navigationHandler.jackControl(0);
        });

        btnLiftUp = findViewById(R.id.btnLiftUp);
        btnLiftUp.setOnClickListener(v -> {
            ttsHelper.speak(getString(R.string.ascending));
            navigationHandler.jackControl(1);
        });

        // 初始检查背景状态
        updateBackgroundVisibility();
    }

    // 弹窗创建逻辑
    private void showCreateDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_jack_task, null);

        EditText etTaskName = dialogView.findViewById(R.id.etTaskName);
        EditText etJackLoopCount = dialogView.findViewById(R.id.etJackLoopCount);
        setupJackLoopCountInput(etJackLoopCount);
        Button btnConfirm = dialogView.findViewById(R.id.btnConfirm);

        AlertDialog dialog = builder.setView(dialogView).create();

        btnConfirm.setOnClickListener(v -> {
            String taskName = etTaskName.getText().toString().trim();
            if (taskName.isEmpty()) {
                etTaskName.setError(getString(R.string.input_task_name));
                return;
            }

            if (isDuplicateTask(taskName)) {
                etTaskName.setError(getString(R.string.task_name_duplicate));
                return;
            }

            int loopCount = normalizeJackLoopCountInput(etJackLoopCount);
            if (loopCount != INFINITE_JACK_LOOP_COUNT && loopCount < MIN_JACK_LOOP_COUNT) {
                etJackLoopCount.setError(getString(R.string.invalid_jack_loop_count));
                return;
            }

            JackTask newTask = new JackTask((int) System.currentTimeMillis(), taskName, 0, loopCount);
            jackViewModel.saveJackTaskMap(newTask);
            //addTaskToView(newTask);
            dialog.dismiss();

            // 新建任务后自动跳转到编辑界面
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isActivityValid()) {
                    Log.w(TAG, "Activity destroyed, skipping delayed task");
                    return;
                }
                Intent intent = new Intent(this, EditJackActivity.class);
                intent.putExtra("TASK_DATA", newTask);
                startActivityForResult(intent, REQUEST_EDIT_TASK);
            }, 50);
        });

        dialog.show();
    }

    private boolean isDuplicateTask(String taskName) {
       /* for (int i = 0; i < taskContainer.getChildCount(); i++) {
            View taskView = taskContainer.getChildAt(i);
            TextView tvTaskName = taskView.findViewById(R.id.tvPointName);
            if (tvTaskName.getText().toString().equalsIgnoreCase(taskName)) {
                return true;
            }
        }*/
        // 检查内存中的任务映射而非UI视图
        String normalizedTaskName = taskName == null ? "" : taskName.trim();
        for (JackTask task : jackTaskMap.values()) {
            if (task != null
                    && task.getTaskName() != null
                    && task.getTaskName().trim().equalsIgnoreCase(normalizedTaskName)) {
                return true;
            }
        }
        for (CruiseTask task : cruiseTaskMap.values()) {
            if (task != null
                    && task.getName() != null
                    && task.getName().trim().equalsIgnoreCase(normalizedTaskName)) {
                return true;
            }
        }
        return false;
    }

    // 动态添加任务视图
    private void addTaskToView(JackTask task) {
        //Log.d("TaskDebug", "添加任务 - ID:" + task.getTaskId() + " 名称:" + task.getTaskName());
        View taskView = LayoutInflater.from(this).inflate(R.layout.item_jack_task, taskContainer, false);

        // 设置布局参数（关键修复点）
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.task_item_spacing);
        taskView.setLayoutParams(params);

        int taskId = task.getTaskId();
        taskView.setTag(taskId);

        TextView tvTaskName = taskView.findViewById(R.id.tvPointName);
        TextView tvOperation = taskView.findViewById(R.id.tvOperation);
        Button btnStart = taskView.findViewById(R.id.btnStartJack);
        Button btnDelete = taskView.findViewById(R.id.btnDelete);
        Button btnEdit = taskView.findViewById(R.id.btnEdit);

        tvTaskName.setText(task.getTaskName());
        tvOperation.setText(getString(R.string.jack_loop_format, getJackLoopCountDisplay(task.getLoopCount())));

        btnStart.setOnClickListener(v -> {
            // 按钮防抖
            if (isButtonClickDebounced()) {
                return;
            }
            List<JackOperation> taskList = Objects.requireNonNull(jackTaskMap.get(taskId)).getOperations();
            // 检查任务列表是否为空
            if (taskList == null || taskList.isEmpty()) {
                onShowToast(R.string.task_list_empty);
                return;
            }
            if (latestStatusResponse == null ||
                    latestStatusResponse.data == null) {
                onShowToast(getString(R.string.waiting_robot_status));
                return;
            }

            if (latestStatusResponse.data.chargeStatus != null) {
                int chargeState = latestStatusResponse.data.chargeStatus.state;
                if (chargeState == 300) {
                    onVoicePrompt(VoiceKeyConstants.CHARGING_TASK_ERROR);
                    return;
                }
            }
            // 检查置信度状态
            if (latestStatusResponse.data.poseProbability < DEFAULT_CONFIDENCE) {
                onVoicePrompt(VoiceKeyConstants.LOW_CONFIDENCE_ERROR);
                return; // 直接返回，不执行后续逻辑
            }
            currentWaypointIndex.set(0);
            // 统一走带防重复保护的启动入口（600ms 延迟 + 待启动/运行中去重）
            executeJackByTaskId(taskId);
        });

        btnDelete.setOnClickListener(v -> {
            // 创建自定义布局的对话框
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_jack_delete, null);
            builder.setView(dialogView);
            AlertDialog dialog = builder.create();

            // 获取自定义布局中的组件
            TextView tvTitle = dialogView.findViewById(R.id.tvTitle);
            TextView tvMessage = dialogView.findViewById(R.id.tvMessage);
            Button btnPositive = dialogView.findViewById(R.id.btnPositive);
            Button btnNegative = dialogView.findViewById(R.id.btnNegative);

            tvTitle.setText(R.string.confirm_delete_title);
            tvMessage.setText(R.string.confirm_delete_message);
            btnPositive.setText(R.string.delete);
            btnNegative.setText(R.string.cancel);

            // 设置按钮点击事件
            btnPositive.setOnClickListener(innerV -> {
                taskContainer.removeView(taskView);
                jackTaskMap.remove(taskId);
                jackViewModel.deleteJackTaskMap(taskId);
                onShowToast(R.string.task_deleted);
                updateBackgroundVisibility();
                dialog.dismiss();
            });

            btnNegative.setOnClickListener(innerV -> dialog.dismiss());

            dialog.show();
        });

        btnEdit.setOnClickListener(v -> {
            Intent intent = new Intent(this, EditJackActivity.class);
            intent.putExtra("TASK_DATA", task);
            startActivityForResult(intent, REQUEST_EDIT_TASK);
        });

        jackTaskMap.put(taskId, task);
        taskContainer.addView(taskView); // 现在会添加到列表末尾
        updateBackgroundVisibility(); // 添加任务后更新背景状态
    }

    // 显示无数据状态
    private void showNoData() {
        runOnUiThread(() -> {
            View noDataBackground = findViewById(R.id.ivNoDataBackground1);
            View scrollView = findViewById(R.id.scrollView);

            if (noDataBackground != null) {
                noDataBackground.setVisibility(View.VISIBLE);
            }
            if (scrollView != null) {
                scrollView.setVisibility(View.GONE);
            }
            onShowToast(R.string.no_position_data);
        });
    }
    // --------------------------------------------------------------------------------------------

    // 布局相关UI
    /**
     * 更新背景图可见性
     */
    private void updateBackgroundVisibility() {
        if (ivNoDataBackground != null) {
            // 当任务列表为空时显示背景图，否则隐藏
            if (jackTaskMap == null || jackTaskMap.isEmpty()) {
                ivNoDataBackground.setVisibility(View.VISIBLE);
                tvNoData.setVisibility(View.VISIBLE);
            } else {
                ivNoDataBackground.setVisibility(View.GONE);
                tvNoData.setVisibility(View.GONE);
            }
        }
    }

    public List<JackOperation> getCurrentOperations() {
        if (currentExecutingTaskId != -1 && jackTaskMap != null) {
            JackTask task = jackTaskMap.get(currentExecutingTaskId);
            if (task != null) {
                return task.getOperations();
            }
        }
        return null;
    }
}
