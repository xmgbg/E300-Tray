package com.ezhan.amr.ui;

import static com.ezhan.amr.ui.MainActivity.DEFAULT_CONFIDENCE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.CruiseTaskScheduler;
import com.ezhan.amr.navigation.ExternalTaskContext;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.ezhan.amr.utils.AndroidBug5497Workaround;
import com.ezhan.amr.utils.GifTypeConstants;
import com.ezhan.amr.utils.MusicPlayer;
import com.ezhan.amr.utils.TextToSpeechHelper;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.android.material.button.MaterialButton;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 巡航任务管理Activity
 */
public class CruiseActivity extends BaseNavigationActivity
        implements MusicPlayer.MusicPlayerListener, TextToSpeechHelper.OnTTSEventListener {

    private String TAG = "CruiseActivity";
    private SharedViewModel sharedViewModel;
    private CruiseViewModel cruiseViewModel;
    private JackViewModel jackViewModel;
    private MapViewModel mapViewModel;
    private BasicViewModel basicViewModel;
    private Map<Integer, Boolean> taskEnableStatus = new HashMap<>(); // 存储任务启用状态
    // 添加SharedPreferences用于保存开关状态
    private SharedPreferences switchPreferences;
    private static final String SWITCH_PREFS_NAME = "cruise_task_switches";
    private static final int INFINITE_CRUISE_COUNT = -1;
    private static final int MIN_CRUISE_COUNT = 1;
    private static final int MAX_CRUISE_COUNT = 99;
    private int currentCruiseLoopIndex = 0;  // 当前巡航循环次数（0表示未开始）

    // 视图组件
    private LinearLayout taskListContainer;   // 任务列表容器
    private EditText taskInput;               // 任务名称输入框
    private EditText defaultStayValue;        // 默认停留时间输入框
    private EditText cruiseCountValue;        // 巡航次数输入框
    private Button btnNormal;                 // 普通模式按钮
    private Button btnAuto;                   // 自动模式按钮
    private MaterialButton btnStartTime;      // 开始时间按钮
    private boolean isFirstLoadButtons = true;
    private ImageView ivNoDataBackground;     // 背景图片视图
    private ImageView ivNoDataBackground1;    // 背景图片视图

    private TextView tvNoData,tvNoData1;
    private Map<String, Integer> stationOrderMap = new HashMap<>();
    // 数据集合
    private final List<View> taskCards = new ArrayList<>();          // 任务卡片列表
    private final boolean[] modeSelected = new boolean[3];           // 模式选择状态
    // 当前选中项
    private View selectedCard = null;            // 当前选中的任务卡片
    private Map<Integer, View> taskCardMap = new HashMap<>();
    private List<String> promptNames = new ArrayList<>(); // 提示词名称列表

    // ================ 巡航任务下发变量 ================ //
    private CruiseTask currentCruiseTask;
    // 任务到点自动开始执行功能相关变量
    private Handler autoTaskHandler;
    private Runnable autoTaskRunnable;
    private CruiseTask scheduledAutoTask;      // 当前已安排的自动任务
    private boolean isAutoModeActive = false;  // 自动模式是否激活，默认是不激活状态
    private boolean suppressAutoSwitchListener = false; // 程序自动关闭开关时抑制监听器，避免误触发提示
    private static final long SCHEDULED_TASK_RETRY_DELAY_MS = 10_000L;
    private Integer pendingScheduledTaskId = null;
    private Runnable pendingScheduledTaskRunnable;
    private String currentExternalTaskId = "";
    private String currentExternalTaskName = "";
    // 添加变量跟踪编辑状态
    private Integer currentEditTaskId = null;
    private Button btnSaveRoute;
    private Map<Integer, JackTask> jackTaskMap = new HashMap<>();
    private Map<Integer, List<Position>> floorPositionsMap = new HashMap<>();
    private boolean isFirstLoadPositions = true;
    private LinearLayout stationContainer; // 替换原来的stationGrid
    private Set<String> selectedStationIndices = new LinkedHashSet<>(); // 改用Set存储多个选中索引
    private GeneralNavigationHandler navigationHandler;
    private long lastTouchTime = 0;
    private NavigationStateDebugger debugger = NavigationStateDebugger.getInstance();


    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置窗口背景为白色，避免Splash背景闪烁
        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(android.R.color.white);
        }
        if (savedInstanceState != null) {
            isAutoModeActive = savedInstanceState.getBoolean("AUTO_MODE_STATE", false);
        }
        setContentView(R.layout.activity_cruise);
        bindViews();
        setupViews();
        navigationHandler = getNavigationHandler();
        setupObservers();
        setupUIListeners();

        // ADD THIS: Initial data load
        refreshStationData();

        // 检查是否有传递的任务ID
        int taskId = getIntent().getIntExtra("task_id", -1);
        String externalTaskId = getIntent().getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_ID);
        String externalTaskName = getIntent().getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_NAME);
        if (taskId != -1) {
            // 根据任务ID执行任务
            executeCruiseByTaskId(taskId, externalTaskId, externalTaskName);
        }

        List<Position> cruiseTask = (List<Position>) getIntent().getSerializableExtra("task_object");
        if (cruiseTask != null) {
            // 根据任务ID执行任务
            executeCruiseByTaskObject(cruiseTask);
        }

        handleScheduledTaskFromIntent();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("AUTO_MODE_STATE", isAutoModeActive);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 确保在Activity恢复时刷新数据
        refreshStationData();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 确保我们有最新的数据
        refreshStationData();
    }

    @Override
    protected void onDestroy() {
        checkActiveAutoTasks();

        // 1. Cancel all handlers specific to this activity
        if (autoTaskHandler != null) {
            autoTaskHandler.removeCallbacksAndMessages(null);
        }
        pendingScheduledTaskRunnable = null;
        pendingScheduledTaskId = null;

        // Then do our own cleanup
        onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL,"");

        // Clean up dialogs
        if (pauseDialog != null && pauseDialog.isShowing()) {
            pauseDialog.dismiss();
        }

        // Clean up any remaining navigation
        if (navigationHandler != null) {
            navigationHandler.cleanup(false);
            isAutoModeActive = false;
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

        // 在调用 Glide 前检查 Activity 是否已销毁
        if (!isFinishing() && !isDestroyed()) {
            if (gifView != null) {
                Glide.with(this).clear(gifView);
            }
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
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        cruiseViewModel = new ViewModelProvider(this).get(CruiseViewModel.class);
        jackViewModel = MyApplication.getInstance().getJackViewModel();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        basicViewModel = MyApplication.getInstance().getBasicViewModel();
        autoTaskHandler = new Handler(Looper.getMainLooper());

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

        tvNavigationStatus = findViewById(R.id.tvNavigationStatus);

        AndroidBug5497Workaround.assistActivity(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // 默认隐藏输入法
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        // 初始化背景图片视图
        ivNoDataBackground = findViewById(R.id.ivNoDataBackground);
        ivNoDataBackground1 = findViewById(R.id.ivNoDataBackground1);
        tvNoData= findViewById(R.id.tvNoData);
        tvNoData1=findViewById(R.id.tvNoData1);

        // 初始化SharedPreferences
        switchPreferences = getSharedPreferences(SWITCH_PREFS_NAME, MODE_PRIVATE);

        // 默认选择普通模式
        toggleMode(0); // 0代表普通模式
        btnNormal.setSelected(true); // 确保视觉状态正确

        // 如果还需要更新其他相关状态
        updateModeButtons();

        // 初始检查背景状态
        checkBackgroundVisibility();
        checkStationsBackgroundVisibility();

        //拿到保存数据的id
        btnSaveRoute = findViewById(R.id.btnSaveRoute);
    }

    private boolean shouldContinueCruiseLoop(CruiseTask task) {
        return task != null && task.getLoopCount() == INFINITE_CRUISE_COUNT;
    }

    private void handleCruiseLoopCompletion() {
        if (currentCruiseTask != null && currentCruiseTask.getLoopCount() == INFINITE_CRUISE_COUNT) {
            currentCruiseLoopIndex++;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    startCruise();
                }
            });
        }
    }

    /**
     * 绑定视图组件
     */
    @SuppressLint("WrongViewCast")
    private void bindViews() {
        stationContainer = findViewById(R.id.stationContainer);
        taskListContainer = findViewById(R.id.taskListContainer);
        taskInput = findViewById(R.id.taskInput);
        defaultStayValue = findViewById(R.id.defaultStayValue);
        cruiseCountValue = findViewById(R.id.cruiseCountValue);
        setupCruiseCountInput();
        btnNormal = findViewById(R.id.radioNormal);
        btnAuto = findViewById(R.id.radioAuto);
        btnStartTime = findViewById(R.id.btnStartTime);
        btnStartTime.setEnabled(false); // 初始状态禁用
    }

    private void setupCruiseCountInput() {
        if (cruiseCountValue == null) {
            return;
        }

        cruiseCountValue.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            StringBuilder proposedValue = new StringBuilder(dest);
            proposedValue.replace(dstart, dend, source.subSequence(start, end).toString());
            return isAllowedCruiseCountInput(proposedValue.toString()) ? null : "";
        }});
        cruiseCountValue.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeCruiseCountInput();
            }
        });
    }

    private boolean isAllowedCruiseCountInput(String value) {
        if (value.isEmpty() || "-".equals(value)) {
            return true;
        }
        if (String.valueOf(INFINITE_CRUISE_COUNT).equals(value)) {
            return true;
        }
        if (value.startsWith("-") || value.length() > 3) {
            return false;
        }
        try {
            int count = Integer.parseInt(value);
            return count >= MIN_CRUISE_COUNT && count <= MAX_CRUISE_COUNT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int normalizeCruiseCountInput() {
        int count = getValidCruiseCount(cruiseCountValue == null ? "" : cruiseCountValue.getText().toString());
        if (cruiseCountValue != null) {
            String normalizedValue = String.valueOf(count);
            if (!normalizedValue.contentEquals(cruiseCountValue.getText())) {
                cruiseCountValue.setText(normalizedValue);
                cruiseCountValue.setSelection(normalizedValue.length());
            }
        }
        return count;
    }

    private int getValidCruiseCount(String value) {
        try {
            int count = Integer.parseInt(value.trim());
            if (count == INFINITE_CRUISE_COUNT || (count >= MIN_CRUISE_COUNT && count <= MAX_CRUISE_COUNT)) {
                return count;
            }
        } catch (NumberFormatException ignored) {
        }
        return MIN_CRUISE_COUNT;
    }

    private void setupObservers() {
        mapViewModel.getCurrentMapPoints().observe(this, mapPoints -> {
            if (mapPoints != null) {
                Map<Integer, List<Position>> latestData = mapViewModel.getAllFloorPositionsFromMapPoints();
                if (latestData != null && !latestData.isEmpty()) {
                    floorPositionsMap = latestData;
                    createStationButtons();
                    checkStationsBackgroundVisibility();
                } else {
                    floorPositionsMap.clear();
                    createStationButtons();
                    checkStationsBackgroundVisibility();
                    showNoData();
                }
            } else {
                showNoData();
            }
        });

        cruiseViewModel.getCruiseTaskMap().observe(this, cruiseTaskMap ->
                initCruiseTaskList(cruiseTaskMap != null ? cruiseTaskMap : new HashMap<>()));

        jackViewModel.getJackTaskMap().observe(this, jackTaskMap ->
                this.jackTaskMap = jackTaskMap != null ? jackTaskMap : new HashMap<>());

    }

    // Add this helper method
    private void refreshStationData() {
        Map<Integer, List<Position>> latestData = mapViewModel.getAllFloorPositionsFromMapPoints();
        if (latestData != null && !latestData.isEmpty()) {
            floorPositionsMap = latestData;
            createStationButtons();
            checkStationsBackgroundVisibility();
        } else {
            floorPositionsMap.clear();
            createStationButtons();
            checkStationsBackgroundVisibility();
            showNoData();
        }
    }

    /**
     * 设置UI交互事件,事件发生链接
     */
    private void setupUIListeners() {
        btnNormal.setOnClickListener(v -> toggleMode(0));
        btnAuto.setOnClickListener(v -> toggleMode(1));
        btnStartTime.setOnClickListener(v -> showTimePicker());

        findViewById(R.id.btnSaveRoute).setOnClickListener(v -> saveRoute());
        findViewById(R.id.btnStartCruise).setOnClickListener(v -> startCruise());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        int currentMinute = now.get(Calendar.MINUTE);

        // 检查是否为自动任务
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap != null) {
            for (CruiseTask task : taskMap.values()) {
                // 检查自动任务已打开
                Boolean enabled = taskEnableStatus.get(task.getId());
                if (task.isAuto() && enabled != null && enabled) {
                    // 检查时间是否在5s以内
                    if (Math.abs(currentHour - task.getHour()) == 0 &&
                            Math.abs(currentMinute - task.getMinute()) == 0) {

                        // Check confidence - 添加空值检查
                        if (latestStatusResponse != null && 
                                latestStatusResponse.data != null &&
                                navigationHandler != null &&
                                latestStatusResponse.data.poseProbability >= DEFAULT_CONFIDENCE) {
                            // 根据任务ID生成route, 调用导航模块
                            List<Position> fullPositionList =
                                    generateFullPositionList(currentPosition, generateCruisePositionList(task));
                            if (fullPositionList == null) {
                                return;
                            }
                            int loopCount = currentCruiseTask.getLoopCount();
                            navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);
                            Log.d(TAG, "start cruise task in setupUIListeners");
                            if (!checkBatteryCanAcceptTask()) {
                                continue;
                            }
                            onVoicePrompt(VoiceKeyConstants.START_EXECUTING_CRUISE_TASK);
                            markCruiseTaskExecuting();
                            navigationHandler.startNavigation(fullPositionList, () -> {
                                // Completion callback
                                //runOnUiThread(() -> onShowToast(R.string.task_completed));
                            });
                            return; // Start only one task per check
                        }
                    }
                }
            }
        }
//        Button btnVisualMode = findViewById(R.id.btnVisualModel);
//        btnVisualMode.setOnClickListener(v -> {
//            Intent intent = new Intent(this, VisualPromptActivity.class);
//            startActivityForResult(intent, REQUEST_EDIT_TASK);
//        });
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
            if (position != null && position.getTaskType() == 2) {
                position.setTaskId(taskId);
            }
        }
    }

    private void applyExternalTaskContext() {
        if (!hasExternalTaskId() || !hasExternalTaskName()) {
            return;
        }
        navigationHandler.setExternalTaskContext(new ExternalTaskContext(
                "cruise",
                currentExternalTaskId,
                currentExternalTaskName,
                "HTTP"
        ));
    }

    private void clearExternalTaskContext() {
        currentExternalTaskId = "";
        currentExternalTaskName = "";
    }

    private void markCruiseTaskExecuting() {
        String taskName = hasExternalTaskName()
                ? currentExternalTaskName.trim()
                : (currentCruiseTask != null ? currentCruiseTask.getName() : "cruise");
        if (hasExternalTaskId()) {
            MyApplication.getInstance().getTaskViewModel()
                    .markTaskExecuting("cruise", taskName, currentExternalTaskId.trim());
        } else {
            MyApplication.getInstance().getTaskViewModel()
                    .markTaskExecutingIfUnset("cruise", taskName);
        }
    }

    void executeCruiseByTaskId(int taskId) {
        executeCruiseByTaskId(taskId, null, null);
    }

    void executeCruiseByTaskId(int taskId, String externalTaskId) {
        executeCruiseByTaskId(taskId, externalTaskId, null);
    }

    void executeCruiseByTaskId(int taskId, String externalTaskId, String externalTaskName) {
        currentExternalTaskId = normalizeExternalTaskId(externalTaskId);
        currentExternalTaskName = normalizeExternalTaskName(externalTaskName);
        // 先检查当前是否已有数据
        Map<Integer, CruiseTask> currentMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (currentMap != null && currentMap.containsKey(taskId)) {
            currentCruiseTask = currentMap.get(taskId);          // 设置当前执行的任务
            isAutoModeActive = true;

            // Reset loop index for new task
            currentCruiseLoopIndex = 1;

            // 根据任务ID生成route, 调用导航模块
            List<Position> fullPositionList =
                    generateFullPositionList(currentPosition, generateCruisePositionList(currentMap.get(taskId)));
            if (fullPositionList == null) {
                return;
            }
            Log.d(TAG, "start cruise task in executeCruiseTaskById");
            if (!checkBatteryCanAcceptTask()) {
                return;
            }
            int loopCount = currentCruiseTask.getLoopCount();
            navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);
            onVoicePrompt(VoiceKeyConstants.START_EXECUTING_CRUISE_TASK);
            applyCurrentExternalTaskId(fullPositionList);
            applyExternalTaskContext();
            markCruiseTaskExecuting();
            navigationHandler.startNavigation(fullPositionList,
                    () -> handleCruiseLoopCompletion(currentCruiseTask, currentCruiseLoopIndex));
            return;
        }
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.cruise_loading_task));
        progress.setCancelable(false);
        progress.show();

        // 添加观察者等待数据加载
        final Observer<Map<Integer, CruiseTask>>[] taskObserver = new Observer[1];
        taskObserver[0] = cruiseTaskMap -> {
            progress.dismiss();
            if (cruiseTaskMap == null) return;

            if (cruiseTaskMap.containsKey(taskId)) {
                currentCruiseTask = cruiseTaskMap.get(taskId);          // 设置当前执行的任务
                isAutoModeActive = true;

                // Reset loop index for new task
                currentCruiseLoopIndex = 1;

                // 根据任务ID生成route, 调用导航模块
                List<Position> fullPositionList =
                        generateFullPositionList(currentPosition, generateCruisePositionList(cruiseTaskMap.get(taskId)));
                if (fullPositionList == null) {
                    return;
                }
                if (!checkBatteryCanAcceptTask()) {
                    return;
                }
                int loopCount = currentCruiseTask.getLoopCount();
                navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);
                Log.d(TAG, "start cruise task in executeCruiseByTaskId when observing cruise map");
                onVoicePrompt(VoiceKeyConstants.START_EXECUTING_CRUISE_TASK);
                applyCurrentExternalTaskId(fullPositionList);
                applyExternalTaskContext();
                markCruiseTaskExecuting();
                navigationHandler.startNavigation(fullPositionList,
                        () -> handleCruiseLoopCompletion(currentCruiseTask, currentCruiseLoopIndex));
            } else {
                Log.d(TAG, "未找到任务ID: " + taskId);
            }

            // 移除观察者避免重复触发
            cruiseViewModel.getCruiseTaskMap().removeObserver(taskObserver[0]);
        };
        cruiseViewModel.getCruiseTaskMap().observe(this, taskObserver[0]);
    }

    void executeCruiseByTaskObject(List<Position> selectedPosition) {
        clearExternalTaskContext();
        currentCruiseTask = null;
        // 根据任务ID生成route, 调用导航模块
        List<Position> fullPositionList =
                generateFullPositionList(currentPosition, selectedPosition);
        if (fullPositionList == null) {
            return;
        }
        if (!checkBatteryCanAcceptTask()) {
            return;
        }
        int loopCount = currentCruiseTask != null ? currentCruiseTask.getLoopCount() : 1;
        if (currentCruiseLoopIndex == 0) {
            currentCruiseLoopIndex = 1;
        }
        navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);
        Log.d(TAG, "start cruise task in executeCruiseByTaskObject");
        onVoicePrompt(VoiceKeyConstants.START_EXECUTING_CRUISE_TASK);
        markCruiseTaskExecuting();
        navigationHandler.startNavigation(fullPositionList, () -> {
            // Completion callback
            //runOnUiThread(() -> onShowToast(R.string.task_completed));
        });
    }

    /**
     * 显示删除确认对话框
     * @param task 要删除的任务
     * @param card 对应的任务卡片视图
     */
    private void showDeleteConfirmationDialog(CruiseTask task, View card) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_delete);
        builder.setMessage(getString(R.string.delete_task_prompt, task.getName()));

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            // 检查删除的是否是当前正在编辑的任务
            boolean isEditingCurrentTask = (currentEditTaskId != null && currentEditTaskId.equals(task.getId()));

            // 从ViewModel删除任务数据
            Integer taskId = (Integer) card.getTag();
            if (taskId != null) {
                cruiseViewModel.deleteCruiseTaskMap(taskId);
            }

            // 更新UI
            taskListContainer.removeView(card);
            taskCards.remove(card);
            taskCardMap.remove(taskId);

            // 清除选中状态
            if (card == selectedCard) {
                selectedCard = null;
            }

            // 如果删除的是正在编辑的任务，退出编辑模式
            if (isEditingCurrentTask) {
                resetEditState();
            }

            onShowToast(getString(R.string.task_deleted));
            // 更新背景可见性
            checkBackgroundVisibility();
        });

        builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        // 自定义按钮颜色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED);
    }

    /**
     * 开始巡航
     */
    @SuppressLint("SetTextI18n")
    private void startCruise() {
        // 按钮防抖
        if (isButtonClickDebounced()) {
            return;
        }
        clearExternalTaskContext();
        // ======================== 首先隐藏键盘 ========================
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }
        // 使用Handler延迟100ms执行后续任务
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isActivityValid()) {
                Log.w(TAG, "Activity destroyed, skipping delayed task");
                return;
            }
            // ===================================每次开始执行任务前检查置信度================================
            // 无有效数据
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
                Toast.makeText(this, R.string.low_confidence, Toast.LENGTH_LONG).show();
                ttsHelper.speak(getString(R.string.low_confidence));
                return;
            }
            // ======================================原来的代码保持不变=====================================
            if (selectedCard == null) {
                Toast.makeText(this, R.string.select_task_first, Toast.LENGTH_SHORT).show();
                return;
            }
            // 通过卡片tag获取任务ID，数据继承，全局变量
            Integer taskId = (Integer) selectedCard.getTag();
            if (taskId == null) {
                Toast.makeText(this, R.string.task_data_error, Toast.LENGTH_SHORT).show();
                return;
            }

            // 获取任务数据
            Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
            if (taskMap == null || !taskMap.containsKey(taskId)) {
                onShowToast(getString(R.string.task_not_exist));
                return;
            }

            // 获取当前任务对象
            currentCruiseTask = taskMap.get(taskId);

            // Reset loop index when starting a new task (not continuing from previous)
            if (currentCruiseTask.getLoopCount() == INFINITE_CRUISE_COUNT) {
                currentCruiseLoopIndex = 1;
            } else {
                currentCruiseLoopIndex = 1;
            }

            // 在任务模式检查后添加启用状态检查
            if (currentCruiseTask.isAuto()) {
                // 检查任务是否启用
                Boolean enabled = taskEnableStatus.get(currentCruiseTask.getId());
                if (enabled == null || !enabled) {
                    onShowToast(getString(R.string.auto_task_disabled));
                    return;
                }
                onVoicePrompt(VoiceKeyConstants.SCHEDULED_TASK_SET);
                scheduleAutoTask();
            } else {
                onVoicePrompt(VoiceKeyConstants.START_EXECUTING_CRUISE_TASK);
                // 根据任务ID生成route, 调用导航模块
                List<Position> fullPositionList =
                        generateFullPositionList(currentPosition, generateCruisePositionList(currentCruiseTask));
                if (fullPositionList == null) {
                    return;
                }
                if (!checkBatteryCanAcceptTask()) {
                    return;
                }
                int loopCount = currentCruiseTask.getLoopCount();
                navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);
                Log.d(TAG, "start cruise task in startCruise");
                markCruiseTaskExecuting();
                navigationHandler.startNavigation(fullPositionList,
                        () -> handleCruiseLoopCompletion(currentCruiseTask, currentCruiseLoopIndex));
            }
        }, 600); // 延迟600毫秒，确保键盘退出和语音播报
    }

    /**
     * 安排自动巡航任务
     */
    @SuppressLint("SetTextI18n")
    private void scheduleAutoTask() {
        isAutoModeActive = true;
        Log.d("AUTO_MODE", "Activated auto mode. Current state: " + isAutoModeActive);

        Log.d("AUTO_TASK", "Scheduling new task...");

        long delay = calculateDelay(currentCruiseTask.getHour(), currentCruiseTask.getMinute());
        Log.d("AUTO_TASK", "Calculated delay: " + delay + "ms");

        if (delay <= 0) {
            Log.e("AUTO_TASK", "Invalid delay - task time may be in the past");
            return;
        }

        // Cancel previous task
        if (autoTaskRunnable != null) {
            Log.d("AUTO_TASK", "Cancelling previous task");
            autoTaskHandler.removeCallbacks(autoTaskRunnable);
        }

        autoTaskRunnable = () -> {
            Log.d("AUTO_TASK", "--- Task execution started ---");
            Log.d("AUTO_MODE", "Runnable triggered. isAutoModeActive=" + isAutoModeActive);
            if (!isAutoModeActive) {
                Log.e("AUTO_MODE", "Aborting because auto mode is inactive");
                return;
            }
            if (navigationHandler != null && navigationHandler.isTaskRunning()) {
                Log.d("AUTO_TASK", "Robot is busy, delaying auto cruise task");
                autoTaskHandler.postDelayed(autoTaskRunnable, SCHEDULED_TASK_RETRY_DELAY_MS);
                return;
            }

            // Reset loop index for auto task
            currentCruiseLoopIndex = 1;

            List<Position> fullPositionList =
                    generateFullPositionList(currentPosition, generateCruisePositionList(currentCruiseTask));
            if (fullPositionList == null) {
                return;
            }
            if (!checkBatteryCanAcceptTask()) {
                return;
            }
            int loopCount = currentCruiseTask.getLoopCount();
            navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);
            Log.d(TAG, "start cruise task in scheduleAutoTask");
            onVoicePrompt(VoiceKeyConstants.START_EXECUTING_CRUISE_TASK);
            markCruiseTaskExecuting();
            navigationHandler.startNavigation(fullPositionList,
                    () -> handleCruiseLoopCompletion(currentCruiseTask, currentCruiseLoopIndex));
        };

        autoTaskHandler.postDelayed(autoTaskRunnable, delay);
        Log.d("AUTO_TASK", "Task scheduled to run in " + delay + "ms");
    }

    private long calculateDelay(int hour, int minute) {
        Calendar taskTime = Calendar.getInstance();
        taskTime.set(Calendar.HOUR_OF_DAY, hour);
        taskTime.set(Calendar.MINUTE, minute);
        taskTime.set(Calendar.SECOND, 0);

        long delay = taskTime.getTimeInMillis() - System.currentTimeMillis();
        return delay > 0 ? delay : 0;  // Prevent negative delays
    }

    private List<Position> generateCruisePositionList(CruiseTask cruiseTask) {
        // Get NavigationStateDebugger instance
        List<Position> positions = new ArrayList<>();
        int stationNum = cruiseTask.getStationIds().size();

        // Log start of position generation
        if (debugger != null) {
            debugger.logInfo("========== generateCruisePositionList ==========");
            debugger.logInfo(String.format("CruiseTask: id=%d, name='%s', loopCount=%d (%s), stationCount=%d, stayTime=%d",
                    cruiseTask.getId(),
                    cruiseTask.getName(),
                    cruiseTask.getLoopCount(),
                    cruiseTask.getLoopCount() == INFINITE_CRUISE_COUNT ? "INFINITE" : "finite",
                    stationNum,
                    cruiseTask.getStayTime()));

            // Log station keys/IDs
            if (cruiseTask.getStationKeys() != null && !cruiseTask.getStationKeys().isEmpty()) {
                debugger.logInfo(String.format("Station keys: %s", cruiseTask.getStationKeys()));
            } else if (cruiseTask.getStationIds() != null && !cruiseTask.getStationIds().isEmpty()) {
                debugger.logInfo(String.format("Station names (legacy): %s", cruiseTask.getStationIds()));
            }
        }

        // Get current orbit mode setting (fixed path / free navigation)
        boolean isVirtualOrbitMode = basicViewModel != null &&
                basicViewModel.getIsVirtualOrbitMode().getValue() != null &&
                basicViewModel.getIsVirtualOrbitMode().getValue();

        if (debugger != null) {
            debugger.logInfo(String.format("Configuration: isVirtualOrbitMode=%b", isVirtualOrbitMode));
            if (basicViewModel != null && basicViewModel.getVirtualOrbitObstacleTime().getValue() != null) {
                debugger.logInfo(String.format("Virtual orbit obstacle time: %d",
                        basicViewModel.getVirtualOrbitObstacleTime().getValue()));
            }
        }

        // Step 1: Get base positions
        List<Position> basePositions = new ArrayList<>();
        if (debugger != null) {
            debugger.logInfo("\n📋 STEP 1: Retrieving base positions from station keys/names");
        }

        for (String key : cruiseTask.getStationKeys()) {
            Position pos = getPositionByKey(key);
            if (pos == null) {
                if (debugger != null) {
                    debugger.logWarning("generateCruisePositionList",
                            String.format("Position not found for key: '%s' - SKIPPING", key));
                }
                continue;
            }
            basePositions.add(pos);
            if (debugger != null) {
                debugger.logInfo(String.format("  ✅ Found position for key '%s': id=%d, name='%s', type=%d, floor='%s', coordinates=(%.2f, %.2f)",
                        key, pos.getId(), pos.getName(), pos.getType(), pos.getFloor(), pos.getPosX(), pos.getPosY()));
            }
        }

        if (basePositions.isEmpty()) {
            if (debugger != null) {
                debugger.logError("generateCruisePositionList", "No valid base positions found - returning empty list");
            }
            return positions;
        }

        if (debugger != null) {
            debugger.logInfo(String.format("Total base positions retrieved: %d", basePositions.size()));
        }

        // Step 2: Create positions with proper messages for CURRENT LOOP only
        int loopCount = cruiseTask.getLoopCount();

        // ⭐ CRITICAL: Only generate positions for the CURRENT loop (currentCruiseLoopIndex)
        // Not for all loops from 1 to loopCount
        int currentLoop = currentCruiseLoopIndex;

        // For infinite loop, ensure we have a valid current loop index (starts at 1)
        if (loopCount == INFINITE_CRUISE_COUNT && currentLoop == 0) {
            currentLoop = 1;
            currentCruiseLoopIndex = 1;
        }

        if (debugger != null) {
            debugger.logInfo("\n🔄 STEP 2: Generating positions for CURRENT LOOP only");
            debugger.logInfo(String.format("  loopCount: %d, currentCruiseLoopIndex: %d", loopCount, currentLoop));
            debugger.logInfo(String.format("⭐ Generating ONLY loop %d of %s",
                    currentLoop,
                    loopCount == INFINITE_CRUISE_COUNT ? "∞" : String.valueOf(loopCount)));
        }

        for (int stationIndex = 0; stationIndex < basePositions.size(); stationIndex++) {
            Position originalPos = basePositions.get(stationIndex);

            if (debugger != null) {
                debugger.logInfo(String.format("    📍 Station %d/%d: name='%s', type=%d, floor='%s'",
                        stationIndex + 1, basePositions.size(),
                        originalPos.getName(), originalPos.getType(), originalPos.getFloor()));
            }

            // Create a new Position object with the same properties
            Position newPos = new Position(originalPos.getId(), originalPos.getName(),
                    originalPos.getPosX(), originalPos.getPosY(), originalPos.getYaw());

            // Copy all properties from original position
            newPos.setType(originalPos.getType());
            newPos.setSpeed(originalPos.getSpeed());
            newPos.setRecognizeDistance(originalPos.getRecognizeDistance());

            // Build the message with progress info - Use current loop index
            String message;
            if (loopCount == INFINITE_CRUISE_COUNT) {
                message = getString(R.string.resuming_format,
                        newPos.getName(),
                        stationIndex + 1,
                        stationNum,
                        currentLoop,  // Use current loop index
                        getCruiseCountDisplay(loopCount));
            } else {
                message = getString(R.string.resuming_format,
                        newPos.getName(),
                        stationIndex + 1,
                        stationNum,
                        currentLoop,  // Use current loop index
                        getCruiseCountDisplay(loopCount));
            }
            newPos.setMessage(message);

            if (debugger != null) {
                debugger.logInfo(String.format("      📝 MESSAGE set: '%s'", message));
            }

            newPos.setTaskType(2); // Cruise task type
            if (hasExternalTaskId()) {
                newPos.setTaskId(currentExternalTaskId.trim());
            } else if (originalPos.getTaskId() != null && !originalPos.getTaskId().trim().isEmpty()) {
                newPos.setTaskId(originalPos.getTaskId().trim());
            }
            newPos.setMapName(originalPos.getMapName());
            newPos.setFloor(originalPos.getFloor());

            // Set orbit mode for cruise task to support both fixed path and free navigation
            newPos.setVirtualOrbit(isVirtualOrbitMode);
            if (debugger != null) {
                debugger.logInfo(String.format("      ⚙️ virtualOrbit: %b", isVirtualOrbitMode));
            }

            // Set virtual orbit obstacle time
            if (basicViewModel != null && basicViewModel.getVirtualOrbitObstacleTime().getValue() != null) {
                int obstacleTime = basicViewModel.getVirtualOrbitObstacleTime().getValue();
                newPos.setVirtualOrbitObstacleTime(obstacleTime);
                if (debugger != null) {
                    debugger.logInfo(String.format("      ⚙️ virtualOrbitObstacleTime: %d", obstacleTime));
                }
            }

            // Set stay duration from cruise task
            int stayTime = cruiseTask.getStayTime();
            newPos.setStayDuration(stayTime);
            if (debugger != null) {
                debugger.logInfo(String.format("      ⏱️ stayDuration: %d seconds", stayTime));
            }

            positions.add(newPos);

            if (debugger != null) {
                debugger.logInfo(String.format("      ✅ Position added to route (total positions now: %d)", positions.size()));
            }
        }

        // Log final summary
        if (debugger != null) {
            debugger.logInfo("\n========== generateCruisePositionList SUMMARY ==========");
            debugger.logInfo(String.format("Total positions generated for loop %d: %d", currentLoop, positions.size()));
            debugger.logInfo("\n📋 FINAL POSITION LIST:");
            for (int i = 0; i < positions.size(); i++) {
                Position pos = positions.get(i);
                debugger.logInfo(String.format("  [%d] name='%s', type=%d, taskType=%d, floor='%s', message='%s', speed=%.2f, virtualOrbit=%b",
                        i,
                        pos.getName(),
                        pos.getType(),
                        pos.getTaskType(),
                        pos.getFloor() != null ? pos.getFloor() : "null",
                        pos.getMessage() != null ? pos.getMessage() : "null",
                        pos.getSpeed(),
                        pos.isVirtualOrbit()));
            }
            debugger.logInfo("========================================================");
        }

        return positions;
    }

    /**
     * Handle cruise loop completion - called when a cruise task loop finishes
     * @param cruiseTask The cruise task being executed
     * @param currentLoopIndex The current loop index that just completed
     */
    private void handleCruiseLoopCompletion(CruiseTask cruiseTask, int currentLoopIndex) {
        int loopCount = cruiseTask.getLoopCount();

        if (debugger != null) {
            debugger.logInfo(String.format("handleCruiseLoopCompletion: task=%s, loopIndex=%d, loopCount=%d",
                    cruiseTask.getName(), currentLoopIndex, loopCount));
        }

        // Check if we need to continue to next loop
        if (loopCount == INFINITE_CRUISE_COUNT || currentLoopIndex < loopCount) {
            int nextLoopIndex = currentLoopIndex + 1;

            // Update the current loop index BEFORE generating next route
            currentCruiseLoopIndex = nextLoopIndex;

            // Update navigation handler with new loop info
            navigationHandler.setCruiseLoopInfo(currentCruiseLoopIndex, loopCount);

            if (debugger != null) {
                debugger.logInfo(String.format("Continuing to loop %d of %s",
                        nextLoopIndex,
                        loopCount == INFINITE_CRUISE_COUNT ? "∞" : String.valueOf(loopCount)));
            }

            runOnUiThread(() -> {
                if (!isActivityValid()) {
                    Log.w(TAG, "Activity destroyed, skipping cruise loop continuation");
                    return;
                }

                // Generate route for the next loop
                List<Position> fullPositionList =
                        generateFullPositionList(currentPosition, generateCruisePositionList(cruiseTask));
                if (fullPositionList == null) {
                    Log.e(TAG, "Failed to generate cruise position list for loop " + nextLoopIndex);
                    return;
                }

                Log.d(TAG, "Starting cruise task loop " + nextLoopIndex + " for task " + cruiseTask.getId());

                // Start navigation for the next loop
                applyCurrentExternalTaskId(fullPositionList);
                applyExternalTaskContext();
                markCruiseTaskExecuting();
                navigationHandler.startNavigation(fullPositionList, () -> handleCruiseLoopCompletion(cruiseTask, nextLoopIndex));
            });
        } else {
            // All loops completed
            if (debugger != null) {
                debugger.logInfo(String.format("✅ All loops completed for cruise task: %s", cruiseTask.getName()));
            }
            Log.d(TAG, "Cruise task completed all loops: " + cruiseTask.getName());

            // Reset loop index for next time
            currentCruiseLoopIndex = 0;
            clearExternalTaskContext();

            // 自动巡航任务执行完成：自动关闭开关、取消闹钟，下次执行需用户手动开启
            if (cruiseTask != null && cruiseTask.isAuto()) {
                int completedTaskId = cruiseTask.getId();
                taskEnableStatus.put(completedTaskId, false);
                switchPreferences.edit().putBoolean(String.valueOf(completedTaskId), false).apply();
                CruiseTaskScheduler.getInstance().cancelTask(completedTaskId);

                runOnUiThread(() -> {
                    if (isActivityValid()) {
                        View card = taskCardMap.get(completedTaskId);
                        if (card != null) {
                            SwitchCompat autoSwitch = card.findViewById(R.id.autoTaskSwitch);
                            if (autoSwitch != null) {
                                suppressAutoSwitchListener = true;
                                try {
                                    autoSwitch.setChecked(false);
                                } finally {
                                    suppressAutoSwitchListener = false;
                                }
                            }
                        }
                    }
                    checkActiveAutoTasks();
                });
            }
        }
    }

    private void handleScheduledTaskFromIntent() {
        int scheduledTaskId = getIntent().getIntExtra("scheduled_task_id", -1);
        if (scheduledTaskId != -1) {
            Log.d(TAG, "Received scheduled task: " + scheduledTaskId);

            // Clear the flag so it doesn't trigger again on configuration changes
            getIntent().removeExtra("scheduled_task_id");

            // Execute the scheduled task after a short delay to ensure UI is ready
            new Handler().postDelayed(() -> {
                executeScheduledCruiseWhenIdle(scheduledTaskId);
            }, 1000);
        }
    }

    private void executeScheduledCruiseWhenIdle(int taskId) {
        if (!isActivityValid()) {
            return;
        }

        pendingScheduledTaskId = taskId;
        if (pendingScheduledTaskRunnable != null) {
            autoTaskHandler.removeCallbacks(pendingScheduledTaskRunnable);
        }

        pendingScheduledTaskRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isActivityValid() || pendingScheduledTaskId == null) {
                    return;
                }

                if (!isScheduledCruiseTaskStillEnabled(pendingScheduledTaskId)) {
                    Log.d(TAG, "Scheduled cruise task is disabled or missing: " + pendingScheduledTaskId);
                    pendingScheduledTaskId = null;
                    pendingScheduledTaskRunnable = null;
                    return;
                }

                if (navigationHandler != null && navigationHandler.isTaskRunning()) {
                    Log.d(TAG, "Robot is busy, delaying scheduled cruise task: " + pendingScheduledTaskId);
                    autoTaskHandler.postDelayed(this, SCHEDULED_TASK_RETRY_DELAY_MS);
                    return;
                }

                int taskToExecute = pendingScheduledTaskId;
                pendingScheduledTaskId = null;
                pendingScheduledTaskRunnable = null;
                executeCruiseByTaskId(taskToExecute);
            }
        };
        pendingScheduledTaskRunnable.run();
    }

    private boolean isScheduledCruiseTaskStillEnabled(int taskId) {
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        CruiseTask task = taskMap != null ? taskMap.get(taskId) : null;
        if (task == null || !task.isAuto()) {
            return false;
        }
        return switchPreferences.getBoolean(String.valueOf(taskId), false);
    }

    // --------------------------------------------------------------------------------------------

    // 导航相关UI
    private void initCruiseTaskList(Map<Integer, CruiseTask> cruiseTaskMap) {
        // 先记录已经被选中的任务的id（在清空之前）
        Integer selectedTaskId = null;
        if (selectedCard != null) {
            selectedTaskId = (Integer) selectedCard.getTag();
            // 清除任何待执行的选中动画
            selectedCard.removeCallbacks(null);
            selectedCard = null;
        }

        // 标记是否需要恢复选中状态
        final boolean needRestoreSelection = (selectedTaskId != null);
        final Integer finalSelectedTaskId = selectedTaskId;

        // 清除现有UI
        taskListContainer.removeAllViews();
        taskCards.clear();
        taskCardMap.clear();
        selectedCard = null;

        // 按ID排序任务列表
        List<CruiseTask> sortedTasks = new ArrayList<>(cruiseTaskMap.values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sortedTasks.sort(Comparator.comparingInt(CruiseTask::getId));
        }

        // 为每个任务创建UI卡片
        for (CruiseTask task : sortedTasks) {
            createTaskCard(task);
            // 添加：加载开关状态
            Boolean enabled = switchPreferences.getBoolean(
                    String.valueOf(task.getId()),
                    task.isAuto() ? false : true // 默认值
            );
            taskEnableStatus.put(task.getId(), enabled);
        }
        // 延迟恢复选中状态，确保所有卡片已渲染完成
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!isActivityValid()) {
                Log.w(TAG, "Activity destroyed, skipping delayed task");
                return;
            }

            if (needRestoreSelection && taskCardMap.containsKey(finalSelectedTaskId)) {
                View cardToSelect = taskCardMap.get(finalSelectedTaskId);
                RadioButton radioTask = cardToSelect.findViewById(R.id.radioTask);
                radioTask.setChecked(true);
                updateSelectedCard(cardToSelect, radioTask);
            }
        });

        // 更新背景可见性
        checkBackgroundVisibility();
    }

    private void saveRoute() {
        // 按钮防抖
        if (isButtonClickDebounced()) {
            return;
        }
        floorPositionsMap = mapViewModel.getAllFloorPositionsFromMapPoints();

        if (selectedStationIndices.isEmpty()) {
            onShowToast(R.string.select_at_least_one_station);
            return;
        }

        // 检查自动模式下是否设置了时间
        if (modeSelected[1]) {
            if (btnStartTime.getText().toString().equals(getString(R.string.start_time))) {
                onShowToast(R.string.select_start_time_first);
                return;
            }

            // 检查是否与已有自动任务时间冲突
            String[] timeParts = btnStartTime.getText().toString().split(":");
            if (timeParts.length == 2) {
                int hour = Integer.parseInt(timeParts[0]);
                int minute = Integer.parseInt(timeParts[1]);
                if (isAutoTaskTimeConflict(hour, minute, null)) {
                    onShowToast(R.string.auto_task_time_conflict);
                    return;
                }
            }
        }

        String taskName = taskInput.getText().toString().trim();
        if (taskName.isEmpty()) {
            taskName = generateDefaultTaskName();
        }

        if (isTaskNameExists(taskName, currentEditTaskId)) {
            onShowToast(getString(R.string.task_name_exists));
            return;
        }

        // 收集选中的站点名称和键
        List<String> selectedStations = new ArrayList<>();
        List<String> selectedStationKeys = new ArrayList<>(selectedStationIndices); // 存储键

        for (String key : selectedStationIndices) {
            Position position = getPositionByKey(key);
            if (position != null) {
                selectedStations.add(position.getName());
            } else {
                Log.e(TAG, "Position not found for key: " + key);
            }
        }

        if (selectedStations.isEmpty()) {
            onShowToast(R.string.select_at_least_one_station);
            return;
        }

        // 创建任务对象
        CruiseTask newTask = new CruiseTask();
        newTask.setId((int) System.currentTimeMillis());
        newTask.setName(taskName);
        newTask.setStayTime(getDefaultStayTime());
        newTask.setLoopCount(getCruiseCount());
        newTask.setStationIds(selectedStations); // 存储站点名称（兼容旧版本）
        newTask.setStationKeys(selectedStationKeys); // 存储唯一键
        newTask.setIsAuto(modeSelected[1]);


        if (modeSelected[1]) {
            String[] parts = btnStartTime.getText().toString().split(":");
            newTask.setHour(Integer.parseInt(parts[0]));
            newTask.setMinute(Integer.parseInt(parts[1]));
        }

        // 自动巡航任务保存后默认开启；普通任务开关隐藏，状态不影响调度
        boolean defaultEnabled = newTask.isAuto();
        taskEnableStatus.put(newTask.getId(), defaultEnabled);
        switchPreferences.edit().putBoolean(String.valueOf(newTask.getId()), defaultEnabled).apply();

        // 保存到ViewModel
        cruiseViewModel.saveCruiseTaskMap(newTask);

        if (newTask.isAuto() && Boolean.TRUE.equals(taskEnableStatus.get(newTask.getId()))) {
            CruiseTaskScheduler.getInstance().scheduleTask(newTask);
        } else {
            CruiseTaskScheduler.getInstance().cancelTask(newTask.getId());
        }

        resetInputFieldsExceptMode();
        clearStationButtonAppearance();

        if (modeSelected[1]) {
            btnAuto.setSelected(true);
            btnStartTime.setEnabled(true);
        }

        //清除软键盘
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }

        initCruiseTaskList(cruiseViewModel.getCruiseTaskMap().getValue());
        onShowToast(getString(R.string.route_saved));
    }

    // 只清空某些字段
    private void resetInputFieldsExceptMode() {
        if (taskInput != null) taskInput.setText("");
        if (defaultStayValue != null) defaultStayValue.setText("10");
        if (cruiseCountValue != null) cruiseCountValue.setText("1");
        selectedStationIndices.clear();
        stationOrderMap.clear();

        // 复位开始时间按钮
        if (btnStartTime != null) {
            btnStartTime.setText(R.string.start_time);
        }

    }

    /**
     * 生成默认任务名称
     * @return 默认任务名称 天时分秒
     */
    private String generateDefaultTaskName() {
        return getString(R.string.cruise_name) + "-" + new SimpleDateFormat("MMddHHmmss", Locale.getDefault()).format(new Date());
    }

    /**
     * 创建任务卡片
     * @param task 任务
     */
    private void createTaskCard(CruiseTask task) {
        if (taskListContainer == null) return;

        // 加载任务卡片布局
        View card = LayoutInflater.from(this).inflate(R.layout.item_task_card, taskListContainer, false);
        TextView nameView = card.findViewById(R.id.tvTaskName);
        TextView paramsView = card.findViewById(R.id.tvTaskParams);
        ImageButton deleteBtn = card.findViewById(R.id.btnDeleteTask);
        RadioButton radioTask = card.findViewById(R.id.radioTask);
        LinearLayout cardLayout = card.findViewById(R.id.cardLayout);
        ImageButton editBtn = card.findViewById(R.id.btnEditTask);
        editBtn.setOnClickListener(v -> editTask(task));

        // 设置开关控件
        SwitchCompat autoSwitch = card.findViewById(R.id.autoTaskSwitch);
        // 只有自动任务才显示开关
        autoSwitch.setVisibility(task.isAuto() ? View.VISIBLE : View.GONE);

        // 设置开关状态
        // 从SharedPreferences加载开关状态
        Boolean enabled = switchPreferences.getBoolean(String.valueOf(task.getId()), false);
        autoSwitch.setChecked(enabled);
        taskEnableStatus.put(task.getId(), enabled);

        // 设置开关监听器
        autoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressAutoSwitchListener) {
                return;
            }
            resetEditState();
            // 保存开关状态到SharedPreferences
            SharedPreferences.Editor editor = switchPreferences.edit();
            editor.putBoolean(String.valueOf(task.getId()), isChecked);
            editor.apply();

            // 更新任务启用状态
            taskEnableStatus.put(task.getId(), isChecked);
            boolean anyAutoTaskEnabled = false;
            Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
            if (taskMap != null) {
                for (CruiseTask t : taskMap.values()) {
                    Boolean enabled1 = taskEnableStatus.get(t.getId());
                    if (t.isAuto() && enabled1 != null && enabled1) {
                        anyAutoTaskEnabled = true;
                        break;
                    }
                }
            }

            // 更新全局状态
            basicViewModel.setAutoTaskEnabled(anyAutoTaskEnabled);
            // 根据状态更新提示
            String status = isChecked ? getString(R.string.status_enabled) : getString(R.string.status_disabled);
            onShowToast(getString(R.string.auto_task_status_toggle, task.getName(), status));

            // 使用调度器安排或取消任务
            CruiseTaskScheduler scheduler = CruiseTaskScheduler.getInstance();
            if (isChecked) {
                scheduler.scheduleTask(task);
            } else {
                scheduler.cancelTask(task.getId());
            }

            // 更新计划：如果任务被禁用且已安排，则取消计划
            if (!isChecked && scheduledAutoTask != null && scheduledAutoTask.getId() == task.getId()) {
                cancelScheduledTask();
            }

            // 更新卡片文本
            updateTaskCardStatus(card, task);
        });

        // 更新卡片文本（添加启用状态信息）
        updateTaskCardStatus(card, task);

        // 根据任务模式设置不同样式
        if (task.isAuto()) {
            // 自动任务样式
            cardLayout.setBackgroundResource(R.drawable.bg_card_auto); // 自动任务背景
            nameView.setTextColor(getResources().getColor(R.color.card_auto_text));
            paramsView.setTextColor(getResources().getColor(R.color.card_auto_text));
            radioTask.setButtonTintList(ColorStateList.valueOf(getResources().getColor(R.color.blue_500)));
        } else {
            // 普通任务样式
            cardLayout.setBackgroundResource(R.drawable.bg_card_normal); // 普通任务背景
            nameView.setTextColor(getResources().getColor(R.color.card_normal_text));
            paramsView.setTextColor(getResources().getColor(R.color.card_normal_text));
            radioTask.setButtonTintList(ColorStateList.valueOf(getResources().getColor(R.color.blue_500)));
        }

        // 设置卡片数据
        nameView.setText(task.getName());
        paramsView.setText(buildTaskParametersText(task));

        // 存储任务ID
        card.setTag(task.getId());

        // 设置删除按钮点击事件
        deleteBtn.setOnClickListener(v -> {
            // 显示确认对话框
            showDeleteConfirmationDialog(task, card);
        });

        // 设置单选按钮点击事件
        radioTask.setOnClickListener(v -> {
            if (radioTask.isChecked() && card != selectedCard) {
                // 仅在状态从未勾选变为勾选时触发更新
                updateSelectedCard(card, radioTask);
            } else if (!radioTask.isChecked() && card == selectedCard) {
                // 取消勾选时清空选中状态
                selectedCard = null;
            }
        });

        // 设置卡片点击事件
        card.setOnClickListener(v -> {
            //清除编辑区域
            resetEditState();
            // 只有实际点击时才触发动画
            RadioButton radio = card.findViewById(R.id.radioTask);
            if (!radio.isChecked()) {
                radio.setChecked(true);
                updateSelectedCard(card, radio);
            }
        });

        // 添加卡片到列表
        taskListContainer.addView(card);
        taskCards.add(card);
        taskCardMap.put(task.getId(), card);
    }

    private void editTask(CruiseTask task) {
        floorPositionsMap = mapViewModel.getAllFloorPositionsFromMapPoints();

        currentEditTaskId = task.getId();
        taskInput.setText(task.getName());
        defaultStayValue.setText(String.valueOf(task.getStayTime()));
        cruiseCountValue.setText(String.valueOf(getValidCruiseCount(String.valueOf(task.getLoopCount()))));

        if (task.isAuto()) {
            toggleMode(1);
            updateStartTime(task.getHour(), task.getMinute());
        } else {
            toggleMode(0);
        }

        selectedStationIndices.clear();

        // 优先使用 stationKey
        if (task.getStationKeys() != null && !task.getStationKeys().isEmpty()) {
            // 使用新的键列表
            restoreTaskStationSelection(task);
        } else {
            // 使用站点名称匹配（兼容旧版本任务）
            Log.d(TAG, "Using fallback matching for old task");
            List<String> taskStationNames = task.getStationIds();

            if (taskStationNames != null) {
                for (Map.Entry<Integer, List<Position>> entry : floorPositionsMap.entrySet()) {
                    int floor = entry.getKey();
                    List<Position> positions = entry.getValue();

                    for (Position position : positions) {
                        if (taskStationNames.contains(position.getName())) {
                            String key = floor + "_" + position.getId();
                            selectedStationIndices.add(key);
                            break;
                        }
                    }
                }
            }
        }

        // 更新序号和按钮显示
        recalculateStationOrder();
        updateStationButtonsAppearance();

        btnSaveRoute.setText(R.string.update_task);
        btnSaveRoute.setOnClickListener(v -> updateTask());

        Log.d(TAG, "Edit task - selected stations: " + selectedStationIndices.size());
    }

    private void restoreTaskStationSelection(CruiseTask task) {
        List<String> stationKeys = task.getStationKeys();
        List<String> stationNames = task.getStationIds();
        if (stationKeys == null) {
            return;
        }

        for (int i = 0; i < stationKeys.size(); i++) {
            String key = stationKeys.get(i);
            if (getPositionByKey(key) != null) {
                selectedStationIndices.add(key);
                continue;
            }

            if (stationNames != null && i < stationNames.size()) {
                String fallbackKey = findPositionKeyByName(stationNames.get(i));
                if (fallbackKey != null) {
                    selectedStationIndices.add(fallbackKey);
                }
            }
        }
    }

    private String findPositionKeyByName(String stationName) {
        if (stationName == null || floorPositionsMap == null || floorPositionsMap.isEmpty()) {
            return null;
        }

        for (Map.Entry<Integer, List<Position>> entry : floorPositionsMap.entrySet()) {
            List<Position> positions = entry.getValue();
            if (positions == null) {
                continue;
            }

            for (Position position : positions) {
                if (stationName.equals(position.getName())) {
                    return entry.getKey() + "_" + position.getId();
                }
            }
        }
        return null;
    }

    private void updateStationButtonsAppearance() {
        // 重新计算序号
        recalculateStationOrder();

        // 遍历所有楼层网格
        for (int i = 0; i < stationContainer.getChildCount(); i++) {
            View child = stationContainer.getChildAt(i);
            if (child instanceof GridLayout) {
                GridLayout grid = (GridLayout) child;
                // 遍历网格中的按钮
                for (int j = 0; j < grid.getChildCount(); j++) {
                    View view = grid.getChildAt(j);
                    if (view instanceof Button) {
                        Button button = (Button) view;
                        // 获取按钮对应的tag
                        Object tag = button.getTag();
                        if (tag instanceof String) {
                            String key = (String) tag;
                            Position position = getPositionByKey(key);

                            if (position != null) {
                                // 根据选中状态更新UI和序号显示
                                if (selectedStationIndices.contains(key)) {
                                    // 获取序号
                                    Integer order = stationOrderMap.get(key);
                                    if (order != null) {
                                        // 创建带样式的文本（添加序号）
                                        SpannableStringBuilder builder = new SpannableStringBuilder();

                                        // 添加序号（橙色）
                                        String orderText = order + "、"; // 使用中文顿号作为间隔
                                        SpannableString orderSpan = new SpannableString(orderText);
                                        orderSpan.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.battery_medium)),
                                                0, orderText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                        orderSpan.setSpan(new StyleSpan(Typeface.BOLD), 0, orderText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                                        builder.append(orderSpan);
                                        builder.append(position.getName());

                                        button.setText(builder);
                                    }
                                    button.setBackgroundResource(R.drawable.bg_button_station_selected);
                                    button.setTextColor(Color.WHITE);
                                } else {
                                    button.setText(position.getName()); // 不选中时只显示名称
                                    button.setBackgroundResource(R.drawable.bg_button_station_normal);
                                    button.setTextColor(Color.LTGRAY);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Position getPositionByKey(String key) {
        if (floorPositionsMap == null || floorPositionsMap.isEmpty()) {
            Log.e(TAG, "floorPositionsMap is null or empty");
            return null;
        }

        String[] parts = key.split("_");
        if (parts.length == 2) {
            try {
                int floor = Integer.parseInt(parts[0]);
                int positionId = Integer.parseInt(parts[1]);

                List<Position> positions = floorPositionsMap.get(floor);
                if (positions != null) {
                    for (Position pos : positions) {
                        if (pos.getId() == positionId) {
                            return pos;
                        }
                    }
                    Log.e(TAG, "Position not found for ID: " + positionId + " on floor: " + floor);
                } else {
                    Log.e(TAG, "No positions found for floor: " + floor);
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid key format: " + key);
            }
        } else {
            Log.e(TAG, "Invalid key format, expected 'floor_id': " + key);
        }
        return null;
    }

    private void updateTask() {
        // 按钮防抖
        if (isButtonClickDebounced()) {
            return;
        }
        floorPositionsMap = mapViewModel.getAllFloorPositionsFromMapPoints();

        if (currentEditTaskId == null) return;

        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap == null || !taskMap.containsKey(currentEditTaskId)) {
            onShowToast(getString(R.string.task_not_exist));
            return;
        }

        CruiseTask task = taskMap.get(currentEditTaskId);

        String newTaskName = taskInput.getText().toString().trim();

        if (!task.getName().equals(newTaskName) && isTaskNameExists(newTaskName, currentEditTaskId)) {
            onShowToast(getString(R.string.task_name_exists));
            return;
        }

        // 更新任务属性
        boolean wasAuto = task.isAuto();
        task.setName(newTaskName);
        task.setStayTime(getDefaultStayTime());
        task.setLoopCount(getCruiseCount());
        task.setIsAuto(modeSelected[1]);
        if (!wasAuto && task.isAuto()) {
            // 由普通任务改为自动任务时，默认开启
            taskEnableStatus.put(task.getId(), true);
            switchPreferences.edit().putBoolean(String.valueOf(task.getId()), true).apply();
        }

        // 更新站点信息
        List<String> selectedStations = new ArrayList<>();
        List<String> selectedStationKeys = new ArrayList<>(selectedStationIndices);

        for (String key : selectedStationIndices) {
            Position position = getPositionByKey(key);
            if (position != null) {
                selectedStations.add(position.getName());
            }
        }

        if (selectedStations.isEmpty()) {
            onShowToast(R.string.select_at_least_one_station);
            return;
        }

        task.setStationIds(selectedStations);
        task.setStationKeys(selectedStationKeys); // 更新键列表

        // 更新时间（如果是自动模式）
        if (task.isAuto()) {
            String[] parts = btnStartTime.getText().toString().split(":");
            if (parts.length == 2) {
                int hour = Integer.parseInt(parts[0]);
                int minute = Integer.parseInt(parts[1]);

                // 检查是否与已有自动任务时间冲突（排除当前编辑的任务）
                if (isAutoTaskTimeConflict(hour, minute, currentEditTaskId)) {
                    onShowToast(R.string.auto_task_time_conflict);
                    return;
                }

                task.setHour(hour);
                task.setMinute(minute);
            }
        }

        boolean enabled = Boolean.TRUE.equals(taskEnableStatus.get(task.getId()));
        if (task.isAuto() && enabled) {
            CruiseTaskScheduler.getInstance().scheduleTask(task);
        } else {
            CruiseTaskScheduler.getInstance().cancelTask(task.getId());
        }

        // 保存更改
        cruiseViewModel.updateCruiseTaskMap(task);
        initCruiseTaskList(cruiseViewModel.getCruiseTaskMap().getValue());

        resetEditState();

        //清除软键盘
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }

        onShowToast(getString(R.string.task_updated));
    }

    /**
     * 检查任务名称是否已存在
     * @param taskName 要检查的任务名称
     * @param excludeTaskId 要排除的任务ID（用于编辑模式）
     * @return true=名称已存在，false=名称可用
     */
    private boolean isTaskNameExists(String taskName, Integer excludeTaskId) {
        String normalizedTaskName = taskName == null ? "" : taskName.trim();
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap != null && !taskMap.isEmpty()) {
        for (CruiseTask existingTask : taskMap.values()) {
            if (existingTask == null) {
                continue;
            }
            // 如果是编辑模式，排除当前正在编辑的任务
            if (excludeTaskId != null && existingTask.getId() == excludeTaskId) {
                continue;
            }

            if (existingTask.getName() != null
                    && existingTask.getName().trim().equalsIgnoreCase(normalizedTaskName)) {
                return true;
            }
        }
        }

        for (JackTask existingTask : jackTaskMap.values()) {
            if (existingTask != null
                    && existingTask.getTaskName() != null
                    && existingTask.getTaskName().trim().equalsIgnoreCase(normalizedTaskName)) {
                return true;
            }
        }

        return false;
    }
    /**
     * 重置编辑状态
     */
    private void resetEditState() {
        currentEditTaskId = null;
        btnSaveRoute.setText(R.string.save_route);
        btnSaveRoute.setOnClickListener(v -> saveRoute());

        //清除所有输入和选择
        taskInput.setText("");
        defaultStayValue.setText("10");
        cruiseCountValue.setText("1");
        selectedStationIndices.clear();
        stationOrderMap.clear();

        //复位时间按钮
        if (btnStartTime != null){
            btnStartTime.setText(R.string.start_time);
        }

        //复位模式选择
        toggleMode(0); // 0代表普通模式
        btnNormal.setSelected(true); // 确保视觉状态正确

        //清除站点选择
        clearStationButtonAppearance();

        // 清除开关状态缓存
        taskEnableStatus.clear();

        // 重新加载开关状态
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap != null) {
            for (CruiseTask task : taskMap.values()) {
                Boolean enabled = switchPreferences.getBoolean(
                        String.valueOf(task.getId()),
                        task.isAuto() ? false : true
                );
                taskEnableStatus.put(task.getId(), enabled);
            }
        }
    }

    private void cancelScheduledTask() {
        if (autoTaskRunnable != null) {
            autoTaskHandler.removeCallbacks(autoTaskRunnable);
            isAutoModeActive = false;
            scheduledAutoTask = null;
            onShowToast(getString(R.string.auto_task_cancelled));
        }
    }

    private void updateTaskCardStatus(View card, CruiseTask task) {
        TextView paramsView = card.findViewById(R.id.tvTaskParams);
        SwitchCompat autoSwitch = card.findViewById(R.id.autoTaskSwitch);

        // 获取启用状态
        Boolean enabled = taskEnableStatus.get(task.getId());
        boolean isEnabled = enabled != null ? enabled : (task.isAuto() ? false : true);

        // 构建状态文本
        String statusText;
        if (task.isAuto()) {
            String timeText = String.format(getString(R.string.scheduled_time_format), task.getHour(), task.getMinute());
            statusText = String.format(Locale.getDefault(),
                    getString(R.string.task_params_format),
                    getString(R.string.auto_mode_active),
                    timeText,
                    task.getStayTime(),
                    getCruiseCountWithUnit(task.getLoopCount()),
                    task.getStationIds().size());
        } else {
            statusText = String.format(Locale.getDefault(),
                    getString(R.string.task_params_format),
                    getString(R.string.normal_mode_active),
                    "", // 普通模式没有时间
                    task.getStayTime(),
                    getCruiseCountWithUnit(task.getLoopCount()),
                    task.getStationIds().size());
        }

        paramsView.setText(statusText);
    }

    private void checkActiveAutoTasks() {
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap != null) {
            boolean anyEnabled = false;
            for (CruiseTask task : taskMap.values()) {
                Boolean enabled = taskEnableStatus.get(task.getId());
                if (task.isAuto() && enabled != null && enabled) {
                    anyEnabled = true;
                    break;
                }
            }
            basicViewModel.setAutoTaskEnabled(anyEnabled);
        }
    }

    private void updateSelectedCard(View card, RadioButton radioTask) {
        if (selectedCard == card) return; // 避免重复操作
        // 清除编辑区域（当点击任务卡片时）
        resetEditState();
        // 取消之前选中的卡片
        if (selectedCard != null) {
            RadioButton previousRadio = selectedCard.findViewById(R.id.radioTask);
            previousRadio.setChecked(false);
            // 旧卡片恢复原始大小动画
            selectedCard.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(50)
                    .start();
            // 恢复原始背景色
            LinearLayout cardLayout = selectedCard.findViewById(R.id.cardLayout);
            if (cardLayout != null) {
                CruiseTask task = cruiseViewModel.getCruiseTaskMap().getValue().get(selectedCard.getTag());
                if (task != null) {
                    cardLayout.setBackgroundResource(task.isAuto() ?
                            R.drawable.bg_card_auto : R.drawable.bg_card_normal);
                }
            }
            CruiseTask task = cruiseViewModel.getCruiseTaskMap().getValue().get(selectedCard.getTag());
            if (task != null) {
                cardLayout.setBackgroundResource(task.isAuto() ?
                        R.drawable.bg_card_auto : R.drawable.bg_card_normal);
            }
        }

        // 设置新选中卡片（仅用户交互时触发动画）
        selectedCard = card;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!isActivityValid()) {
                Log.w(TAG, "Activity destroyed, skipping delayed task");
                return;
            }
            card.postDelayed(() -> {
                // 增加安全判断：selectedCard 未被移除且仍有效
                if (selectedCard == null || selectedCard.getParent() == null) {
                    return;
                }
                card.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(50)
                        .start();

                // 设置选中状态的高亮边框
//                LinearLayout cardLayout = card.findViewById(R.id.cardLayout);
//                cardLayout.setBackgroundResource(R.drawable.bg_card_selected);
                // 恢复原始背景色
                LinearLayout cardLayout = selectedCard.findViewById(R.id.cardLayout);
                if (cardLayout == null) {
                    return;  // 防止 cardLayout 为 null
                }
                CruiseTask task = cruiseViewModel.getCruiseTaskMap().getValue().get(selectedCard.getTag());
                if (task != null) {
                    cardLayout.setBackgroundResource(task.isAuto() ?
                            R.drawable.bg_card_auto : R.drawable.bg_card_normal);
                }
            },50);
        }
    }

    /**
     * 构建任务参数文本
     * 参数描述字符串
     */
    private String buildTaskParametersText(CruiseTask task) {
        String modeText = task.isAuto() ? getString(R.string.auto_mode_active) : getString(R.string.normal_mode_active);
        String timeText = task.isAuto() ?
                String.format(" (%02d:%02d)", task.getHour(), task.getMinute()) : "";

        return String.format(Locale.getDefault(),
                getString(R.string.task_params_format),
                modeText, timeText,
                task.getStayTime(),
                getCruiseCountWithUnit(task.getLoopCount()),
                task.getStationIds().size());
    }

    private String getCruiseCountDisplay(int loopCount) {
        return loopCount == INFINITE_CRUISE_COUNT ? getString(R.string.infinite_loop) : String.valueOf(loopCount);
    }

    private String getCruiseCountWithUnit(int loopCount) {
        if (loopCount == INFINITE_CRUISE_COUNT) {
            return getString(R.string.infinite_loop);
        }
        return getString(R.string.cruise_count_value_format, loopCount);
    }

    /**
     * 获取默认停留时间
     * @return 停留时间(秒)
     */
    int getDefaultStayTime() {

        // 如果没有选中的任务卡片，从输入框获取
        try {
            return Integer.parseInt(defaultStayValue.getText().toString());
        } catch (NumberFormatException e) {
            return 10; // 默认10秒
        }
    }
    int getTime(){
        // 如果当前有正在执行的自动任务，优先使用该任务的停留时间
        if (currentCruiseTask != null && isAutoModeActive) {
            return currentCruiseTask.getStayTime();
        }
        // 如果当前有选中的任务卡片，从卡片对应的任务中获取停留时间
        if (currentCruiseTask != null) {
            Integer taskId = (Integer) selectedCard.getTag();
            if (taskId != null) {
                Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
                if (taskMap != null && taskMap.containsKey(taskId)) {
                    CruiseTask task = taskMap.get(taskId);
                    return task.getStayTime();
                }
            }
        }
        return  10;
    }


    /**
     * 获取巡航次数
     * @return 巡航次数，-1表示无限循环
     */
    private int getCruiseCount() {
        if (cruiseCountValue == null) {
            return MIN_CRUISE_COUNT;
        }
        if (cruiseCountValue != null) {
            return normalizeCruiseCountInput();
        }
        try {
            int count = Integer.parseInt(cruiseCountValue.getText().toString());
            // -1表示无限循环，1及以上表示具体次数
            if (count == INFINITE_CRUISE_COUNT || (count >= MIN_CRUISE_COUNT && count <= MAX_CRUISE_COUNT)) {
                return count;
            }
            return 1; // 无效值，返回默认值1
        } catch (NumberFormatException e) {
            return 1; // 默认1次
        }
    }

    /**
     * 获取任务参数
     * @return 参数Map
     */
    private Map<String, Object> getTaskParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("stay_time", getDefaultStayTime());
        params.put("cruise_count", getCruiseCount());
        params.put("stations", new ArrayList<>(selectedStationIndices));
        return params;
    }

    /**
     * 清除输入字段
     */
    private void clearInputFields() {
        if (taskInput != null) taskInput.setText("");
        if (defaultStayValue != null) defaultStayValue.setText("10");
        if (cruiseCountValue != null) cruiseCountValue.setText("1");
        selectedStationIndices.clear();

        // 复位开始时间按钮
        if (btnStartTime != null) {
            btnStartTime.setText(R.string.start_time);
        }

        resetInputFieldsExceptMode(); // 重用新方法
        Arrays.fill(modeSelected, false);
        updateModeButtons();

    }

    // --------------------------------------------------------------------------------------------

    // 布局相关UI
    private void createStationButtons() {
        // Always use the latest data from the ViewModel
        Map<Integer, List<Position>> currentPositions = mapViewModel.getAllFloorPositionsFromMapPoints();
        if (currentPositions != null) {
            floorPositionsMap = currentPositions;
        } else {
            floorPositionsMap.clear();
        }

        stationContainer.removeAllViews();

        // 检查是否有站点
        boolean hasStations = false;
        if (floorPositionsMap != null && !floorPositionsMap.isEmpty()) {
            for (List<Position> positions : floorPositionsMap.values()) {
                if (positions != null && !positions.isEmpty()) {
                    hasStations = true;
                    break;
                }
            }
        }

        // 更新背景图可见性
        if (ivNoDataBackground1 != null && tvNoData1 != null) {
            ivNoDataBackground1.setVisibility(hasStations ? View.GONE : View.VISIBLE);
            tvNoData1.setVisibility(hasStations ? View.GONE : View.VISIBLE);
        }

        if (!hasStations) {
            if (currentEditTaskId != null) {
                return;
            }
            selectedStationIndices.clear();
            stationOrderMap.clear();
            return;
        }

        // 按楼层排序
        List<Integer> sortedFloors = new ArrayList<>(floorPositionsMap.keySet());
        Collections.sort(sortedFloors);

        for (int floor : sortedFloors) {
            List<Position> positions = floorPositionsMap.get(floor);
            if (positions == null || positions.isEmpty()) continue;

            // 添加楼层标题
            addFloorTitle(floor);

            // 创建该楼层的站点网格
            GridLayout floorGrid = createFloorGrid(positions, floor);
            stationContainer.addView(floorGrid);
        }
        updateStationButtonsAppearance();
    }

    private void addFloorTitle(int floor) {
        TextView title = new TextView(this);
        title.setText(getString(R.string.floor_title, floor));
        title.setTextSize(18);
        title.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        title.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(8));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        title.setLayoutParams(params);

        stationContainer.addView(title);
    }
    private GridLayout createFloorGrid(List<Position> positions, int floor) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);

        for (Position position : positions) {
            Button button = createStationButton(position);
            String key = floor + "_" + position.getId(); // 使用楼层和站点ID作为唯一标识
            button.setTag(key);

            boolean isSelected = selectedStationIndices.contains(key);
            if (isSelected) {
                button.setBackgroundResource(R.drawable.bg_button_station_selected);
                button.setTextColor(Color.WHITE);
            }

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dpToPx(250); // 固定宽度为120dp
            params.height = dpToPx(48);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
            params.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
            button.setLayoutParams(params);

            button.setOnClickListener(v -> {
                toggleStationSelection(floor, position, button);
            });

            grid.addView(button);
        }

        return grid;
    }

    /**
     * 重新计算站点序号
     */
    private void recalculateStationOrder() {
        stationOrderMap.clear();
        int order = 1;
        for (String key : selectedStationIndices) {
            stationOrderMap.put(key, order++);
        }
    }

    // 修改 clearStationButtonAppearance 方法，清空时也清除序号
    private void clearStationButtonAppearance() {
        selectedStationIndices.clear();
        stationOrderMap.clear();
        updateStationButtonsAppearance();
    }
    /**
     * 创建1个站点按钮
     * @param position 站点信息
     * @return 返回按钮对象
     */
    private Button createStationButton(Position position) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(position.getName());
        button.setTag(position.getName()); // 保存原始名称
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.bg_button_station);
        button.setMinWidth(dpToPx(120));
        return button;
    }

    // 转换
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // 更新选中状态
    private void toggleStationSelection(int floor, Position position, Button button) {
        String key = floor + "_" + position.getId();

        // 检查是否已选中
        boolean isNowSelected = !selectedStationIndices.contains(key);

        if (isNowSelected) {
            selectedStationIndices.add(key);
        } else {
            selectedStationIndices.remove(key);
        }

        // 更新按钮显示（包括序号）
        updateStationButtonsAppearance();
    }

    // 检查并更新背景可见性
    private void checkBackgroundVisibility() {
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap == null || taskMap.isEmpty()) {
            ivNoDataBackground.setVisibility(View.VISIBLE);
            tvNoData.setVisibility(View.VISIBLE);
        } else {
            ivNoDataBackground.setVisibility(View.GONE);
            tvNoData.setVisibility(View.GONE);
        }
    }

    private void checkStationsBackgroundVisibility() {
        boolean hasStations = false;
        if (floorPositionsMap != null && !floorPositionsMap.isEmpty()) {
            for (List<Position> positions : floorPositionsMap.values()) {
                if (positions != null && !positions.isEmpty()) {
                    hasStations = true;
                    break;
                }
            }
        }

        if (ivNoDataBackground1 != null && tvNoData1 != null) {
            ivNoDataBackground1.setVisibility(hasStations ? View.GONE : View.VISIBLE);
            tvNoData1.setVisibility(hasStations ? View.GONE : View.VISIBLE);
        }

        // Also update the station buttons if needed
        if (hasStations && stationContainer.getChildCount() == 0) {
            createStationButtons();
        }
    }

    /**
     * 切换巡航模式
     * @param modeIndex 模式索引
     */
    private void toggleMode(int modeIndex) {
        Arrays.fill(modeSelected, false);
        modeSelected[modeIndex] = true;
        updateModeButtons(); // 触发按钮状态更新
    }

    /**
     * 更新模式按钮状态，按键状态直接决定巡航的模式#
     */
    private void updateModeButtons() {
        if (btnNormal == null || btnAuto == null || btnStartTime == null) return;

        btnNormal.setSelected(modeSelected[0]);
        btnAuto.setSelected(modeSelected[1]);

        // 关键控制逻辑：只有自动模式选中时才启用开始时间按钮
        boolean isEnabled = modeSelected[1];
        btnStartTime.setEnabled(isEnabled);

        // 增加禁用状态下的提示文本
        if (!isEnabled) {
            btnStartTime.setContentDescription(getString(R.string.select_auto_mode_first));
        } else {
            btnStartTime.setContentDescription(null);
        }

        // 禁用状态下的视觉提示
        if (!isEnabled) {
            btnStartTime.setAlpha(0.5f);
            btnStartTime.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_time, 0, 0, 0);
            btnStartTime.setTextColor(Color.GRAY);
            // 添加提示文本
            btnStartTime.setContentDescription(getString(R.string.select_auto_mode_first));
        } else  {
            btnStartTime.setAlpha(1.0f); // 完全可见
            btnStartTime.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_time_view, 0, 0, 0); // 使用正常图标

            // 自动模式选中但未设置时间时显示警告色
            if (btnStartTime.getText().toString().equals(getString(R.string.start_time))) {
                btnStartTime.setTextColor(Color.parseColor("#FFFFFF"));
            } else {
                btnStartTime.setTextColor(Color.WHITE); // 恢复默认颜色
            }
        }
    }

    /**
     * 显示时间选择器
     */
    private void showTimePicker() {
        // 创建自定义布局
        View view = LayoutInflater.from(this).inflate(R.layout.time_picker_dialog, null);
        NumberPicker hourPicker = view.findViewById(R.id.hourPicker);
        NumberPicker minutePicker = view.findViewById(R.id.minutePicker);

        // 设置字体大小（确保生效）
        setNumberPickerTextSize(hourPicker, 18); // 28sp
        setNumberPickerTextSize(minutePicker, 18); // 28sp

        // 设置小时范围 (0-23)
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);

        // 设置分钟范围 (0-59)
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);

        // 设置当前时间
        Calendar calendar = Calendar.getInstance();
        hourPicker.setValue(calendar.get(Calendar.HOUR_OF_DAY));
        minutePicker.setValue(calendar.get(Calendar.MINUTE));

        // 格式化分钟显示 (00, 01, ...)
        minutePicker.setFormatter(value -> String.format(Locale.getDefault(), getString(R.string.minute_format), value));

        // 创建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(view);
        builder.setTitle(R.string.time_picker_title);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            int hour = hourPicker.getValue();
            int minute = minutePicker.getValue();
            updateStartTime(hour, minute);

            // 添加背景检查
            checkStationsBackgroundVisibility();
            checkBackgroundVisibility();
        });
        builder.setNegativeButton(R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // 设置NumberPicker字体大小的方法
    private void setNumberPickerTextSize(NumberPicker numberPicker, float size) {
        try {
            // 获取NumberPicker内部的EditText
            Field field = NumberPicker.class.getDeclaredField("mInputText");
            field.setAccessible(true);
            EditText inputText = (EditText) field.get(numberPicker);
            inputText.setTextSize(size);

            // 获取选择器文本视图
            for (int i = 0; i < numberPicker.getChildCount(); i++) {
                View child = numberPicker.getChildAt(i);
                if (child instanceof EditText) {
                    ((EditText) child).setTextSize(size);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 更新开始时间显示
     * @param hour 小时
     * @param minute 分钟
     */
    private void updateStartTime(int hour, int minute) {
        if (btnStartTime != null) {
            String time = String.format(Locale.getDefault(), getString(R.string.scheduled_time_format), hour, minute);
            btnStartTime.setText(time);
        }

        // 自动模式下更新时间后恢复按钮颜色
        if (modeSelected[1]) {
            btnStartTime.setTextColor(Color.WHITE);
        }
    }

    /**
     * 检查自动巡航任务时间是否与已有任务冲突
     * @param hour 小时
     * @param minute 分钟
     * @param excludeTaskId 要排除的任务ID（编辑模式下排除当前任务）
     * @return true=存在冲突，false=无冲突
     */
    private boolean isAutoTaskTimeConflict(int hour, int minute, Integer excludeTaskId) {
        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap == null || taskMap.isEmpty()) {
            return false;
        }

        for (CruiseTask existingTask : taskMap.values()) {
            if (existingTask == null || !existingTask.isAuto()) {
                continue;
            }

            // 编辑模式下排除当前正在编辑的任务
            if (excludeTaskId != null && existingTask.getId() == excludeTaskId) {
                continue;
            }

            // 检查时间是否相同
            if (existingTask.getHour() == hour && existingTask.getMinute() == minute) {
                return true;
            }
        }

        return false;
    }

    private void showNoData() {
        runOnUiThread(() -> {
            if (ivNoDataBackground1 != null && tvNoData1 != null) {
                ivNoDataBackground1.setVisibility(View.VISIBLE);
                tvNoData1.setVisibility(View.VISIBLE);
            }

            // Clear any existing buttons and selections
            stationContainer.removeAllViews();
            selectedStationIndices.clear();
            stationOrderMap.clear();

            onShowToast(getString(R.string.no_position_data));
        });
    }
}
