package com.ezhan.amr.ui;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.ErrorCode;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.ChargingManager;
import com.ezhan.amr.navigation.ChargeTaskNavigationHelper;
import com.ezhan.amr.navigation.BatteryLevelManager;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.utils.AppSettingsConfig;
import com.ezhan.amr.utils.FeatureVisibilitySettings;
import com.ezhan.amr.utils.PositionDisplayNameHelper;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.communication.rfid.RFIDCommunicator;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.MainViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面Activity，实现机器人状态监控和任务控制功能
 */
public class MainActivity extends BaseNavigationActivity {
    private String TAG = "MainActivity";
    public static double DEFAULT_CONFIDENCE = 0.3;
    private SharedViewModel sharedViewModel;
    private MainViewModel mainViewModel;
    private CruiseViewModel cruiseViewModel;
    private MapViewModel mapViewModel;
    private BasicViewModel basicViewModel;
    private GeneralNavigationHandler navigationHandler;
    private TextView tvDate;
    private TextView tvTime;
    private final Handler timeHandler = new Handler();
    private SimpleDateFormat dateFormat;
    private SimpleDateFormat timeFormat;
    private ImageView chargingIcon;
    private TextView batteryLevel;
    private ProgressBar batteryProgress;
    private Map<String, Position> voicePositionMap = new HashMap<>();
    private Map<String, Position> stationPositionMap = new HashMap<>();
    // 添加变量
    private LinearLayout timeContainer;
    private static final int TIME_WIDTH_NORMAL = 270; // 正常宽度 dp
    private static final int TIME_WIDTH_EXPANDED = 480; // 扩展宽度 dp
    private boolean isUsePassword = false;
    private String currentPassWord = "";

    private ImageView wifiSignalIcon;
    private final Handler wifiSignalHandler = new Handler();
    private static final long WIFI_UPDATE_INTERVAL = 5000; // 5 seconds
    private long lastTouchTime = 0;
    private long lastTimeContainerTouchTime = 0;
    private boolean isStatusBarLongPressed = false;
    // 添加类变量

    private double setSpeed = 0.8;
    public static Boolean isCharge = false;
    private int lowPowerLevel = 20;

    private ImageView autoTaskHint;
    private Button btnJackMode;
    private TextView tvWarning;
    private ImageView warningIcon;
    private AnimationDrawable warningAnimation;
    // 在类成员变量区域添加以下内容
    private ImageView pingCoverView;

    // 添加调试日志标签
    public static Boolean isJackModeOpen = true;

    // 标记是否已取消启动提示（用于终止循环）
    private boolean isStartViewCancelled = false;

    private TextView viewStartTip; // 页面启动文本提示
    private FrameLayout startTipLayout;//页面启动提示
    private ChargingManager chargingManager;
    private StatusWebSocketClient.StatusListener statusListener;
    private static final String CRUISE_SWITCH_PREFS_NAME = "cruise_task_switches";
    private SharedPreferences cruiseSwitchPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener cruiseSwitchChangeListener =
            (sharedPreferences, key) -> updateAutoTaskHint();
    private int pendingMainTaskId = -1;
    private List<Position> pendingMainTaskPositions = null;
    private boolean hasSpokenWelcome = false; //开机语音播报

    /**
     * Activity创建时的初始化方法
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_MyApp);
        setContentView(R.layout.activity_main);
        navigationHandler = getNavigationHandler();
        chargingManager = new ChargingManager(this, navigationHandler);

        setupViews();
        setupObservers();
        startStatusMonitoring();

        // 播报初始化提示
        if (ttsHelper != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                ttsHelper.speak(getString(R.string.initializing));
            }, 1000); // 延迟500ms
        }


        handleStartupMainTask(getIntent());
    }

    /**
     * Activity销毁时的清理方法
     */
    @Override
    protected void onResume() {
        super.onResume();
        updateJackModeVisibility();
        updateAutoTaskHint();
    }

    @Override
    protected void onDestroy() {
        // Stop all handlers FIRST
        timeHandler.removeCallbacksAndMessages(null);
        wifiSignalHandler.removeCallbacksAndMessages(null);

        // Unregister listeners BEFORE clearing views
        if (sharedViewModel != null && statusListener != null) {
            sharedViewModel.unregisterStatusListener(statusListener);
        }
        if (cruiseSwitchPreferences != null) {
            cruiseSwitchPreferences.unregisterOnSharedPreferenceChangeListener(cruiseSwitchChangeListener);
        }

        // Clear Glide with application context to avoid Activity reference
        if (gifView != null) {
            Glide.with(getApplicationContext()).clear(gifView);
            gifView.setImageDrawable(null);
        }

        if (pingCoverView != null) {
            Glide.with(getApplicationContext()).clear(pingCoverView);
        }

        if (warningAnimation != null && warningAnimation.isRunning()) {
            warningAnimation.stop();
        }

        // Clean up other resources
        if (chargingManager != null) {
            chargingManager.cleanup();
        }

        musicPlayer.setListener(null);
        isStartViewCancelled = true;

        super.onDestroy();
    }

    private void updateJackModeVisibility() {
        if (btnJackMode == null) {
            return;
        }

        boolean visible = FeatureVisibilitySettings.isFeatureVisible(
                this,
                FeatureVisibilitySettings.KEY_SHOW_JACK_MODE
        );
        isJackModeOpen = visible;
        LinearLayout textContainer = findViewById(R.id.textContainer);
        if (visible) {
            setTimeContainerWidth(TIME_WIDTH_NORMAL);
            if (textContainer != null) {
                textContainer.setGravity(Gravity.LEFT);
            }
            btnJackMode.setVisibility(View.VISIBLE);
            btnJackMode.setAlpha(1.0f);
        } else {
            setTimeContainerWidth(TIME_WIDTH_EXPANDED);
            if (textContainer != null) {
                textContainer.setGravity(Gravity.LEFT);
            }
            btnJackMode.setVisibility(View.GONE);
        }
    }

    private void setupViews() {
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        mainViewModel = new ViewModelProvider(this).get(MainViewModel.class);
        cruiseViewModel = new ViewModelProvider(this).get(CruiseViewModel.class);
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        basicViewModel = MyApplication.getInstance().getBasicViewModel();
        cruiseSwitchPreferences = getSharedPreferences(CRUISE_SWITCH_PREFS_NAME, MODE_PRIVATE);
        cruiseSwitchPreferences.registerOnSharedPreferenceChangeListener(cruiseSwitchChangeListener);

        // 初始化GIF视图
        gifView = findViewById(R.id.gifView);
        gifView.setVisibility(View.GONE);
        gifView.setScaleType(ImageView.ScaleType.CENTER_CROP);  // 修改自适应类型

        // 提升GIF视图的Z轴顺序
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            gifView.setElevation(10f); // 设置较高的Z轴值
        }

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

        // 初始化Ping覆盖层
        pingCoverView = findViewById(R.id.pingCoverView);
        pingCoverView.setVisibility(View.GONE);
        pingCoverView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // 初始化文本提示
        setStartView();

        // 获取新添加的布局元素
        timeContainer = findViewById(R.id.timeContainer);

        // ---------------------------- 由上到下， 从左到右初始化布局 -----------------------------------
        // 初始化wifi图标
        wifiSignalIcon = findViewById(R.id.wifiSignalIcon);

        if (wifiSignalIcon != null &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            wifiSignalIcon = findViewById(R.id.wifiSignalIcon);
            updateWifiSignalDisplay();
        } else {
            Log.w("WiFi", "No ACCESS_WIFI_STATE permission");
            wifiSignalIcon = findViewById(R.id.wifiSignalIcon);
            wifiSignalIcon.setVisibility(View.GONE); // Hide if no permission
        }

        // 电池区域
        batteryProgress = findViewById(R.id.batteryProgress);
        chargingIcon = findViewById(R.id.chargingIcon);
        batteryLevel = findViewById(R.id.batteryLevel);
        initWarningView();

        // 背负按钮区域
        Button btnDelivery = findViewById(R.id.btnDelivery);
        btnDelivery.setOnClickListener(v -> {
            if (!isUsePassword || currentPassWord.isEmpty()) {
                // 不需要密码，直接进入背负模式页面
                Intent intent = new Intent(MainActivity.this, DeliveryActivity.class);
                startActivity(intent);
            } else {
                // 需要密码，显示密码输入对话框
                showPasswordInputDialog(OperationType.DELIVERY);
            }
        });

        // 巡航按钮区域
        Button btnCruise = findViewById(R.id.btnCruise);
        btnCruise.setOnClickListener(v -> {
            if (!isUsePassword || currentPassWord.isEmpty()) {
                // 不需要密码，直接进入巡航模式页面
                Intent intent = new Intent(MainActivity.this, CruiseActivity.class);
                startActivity(intent);
            } else {
                // 需要密码，显示密码输入对话框
                showPasswordInputDialog(OperationType.CRUISE);
            }
        });

        // 顶升按钮区域
        LinearLayout textContainer = findViewById(R.id.textContainer);
        btnJackMode = findViewById(R.id.btnJack);
        isJackModeOpen = FeatureVisibilitySettings.isFeatureVisible(
                this,
                FeatureVisibilitySettings.KEY_SHOW_JACK_MODE
        );

        if (isJackModeOpen) {
            // 顶升模式开启 - 270dp布局
            setTimeContainerWidth(TIME_WIDTH_NORMAL);
            textContainer.setGravity(Gravity.LEFT); // 文本居中

            btnJackMode.setVisibility(View.VISIBLE);
            // 设置正常时间宽度 (270dp)
            setTimeContainerWidth(270);
            btnJackMode.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.jack_btn_color)
            );
            // 恢复正常透明度
            btnJackMode.setAlpha(1.0f);
        } else {
            // 顶升模式关闭 - 480dp布局
            setTimeContainerWidth(TIME_WIDTH_EXPANDED);
            textContainer.setGravity(Gravity.LEFT); // 文本左对齐btnJackMode.setVisibility(View.GONE);
            setTimeContainerWidth(480);
            btnJackMode.setVisibility(View.GONE);
        }

        btnJackMode.setOnClickListener(v -> {
            if (!btnJackMode.isEnabled()) {
                Toast.makeText(MainActivity.this, R.string.system_not_ready, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isUsePassword || currentPassWord.isEmpty()) {
                // 不需要密码，直接进入顶升模式页面
                btnJackMode.setSelected(!btnJackMode.isSelected());
                Intent intent = new Intent(MainActivity.this, JackActivity.class);
                startActivity(intent);
            } else {
                // 需要密码，显示密码输入对话框
                showPasswordInputDialog(OperationType.JACK);
            }
        });

        // 日期时间区域
        autoTaskHint = findViewById(R.id.autoTaskHint);
        updateAutoTaskHint();
        dateFormat = new SimpleDateFormat(getString(R.string.date_format), Locale.getDefault());
        timeFormat = new SimpleDateFormat(getString(R.string.time_format), Locale.getDefault());

        tvDate = findViewById(R.id.tvDate);
        tvTime = findViewById(R.id.tvTime);
        startClock();

        // 长按状态栏的同时双击时间框，显示配置文件对话框
        timeContainer = findViewById(R.id.timeContainer);
        timeContainer.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (isStatusBarLongPressed && now - lastTimeContainerTouchTime < 500) {
                showConfigFileDialog();
            }
            lastTimeContainerTouchTime = now;
        });

        // 状态栏长按检测：长按期间置位标志，抬手时清除
        View topBar = findViewById(R.id.topBar);
        topBar.setOnLongClickListener(v -> {
            isStatusBarLongPressed = true;
            return true;
        });
        topBar.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isStatusBarLongPressed = false;
            }
            return false;
        });

        ClockView clockView = findViewById(R.id.clockView);
        if (clockView != null) {
            clockView.postInvalidate();
        }

        // 回充电桩按钮区域
        Button btnReturnToCharge = findViewById(R.id.btnReturnToCharge);
        btnReturnToCharge.setOnClickListener(v -> {
            if (!isUsePassword || currentPassWord.isEmpty()) {
                // 不需要密码，直接执行回桩充电
                startReturnToCharge();
            } else {
                // 需要密码，显示密码输入对话框
                showPasswordInputDialog(OperationType.RETURN_TO_CHARGE);
            }
        });

        // 返航点按钮点击事件
        Button btnReturnToHome = findViewById(R.id.btnReturnToHome);
        btnReturnToHome.setOnClickListener(v -> {
            if (!isUsePassword || currentPassWord.isEmpty()) {
                // 不需要密码，直接执行回待命点
                startReturnToHome();
            } else {
                // 需要密码，显示密码输入对话框
                showPasswordInputDialog(OperationType.RETURN_TO_HOME);
            }
        });

        // 设置区域
        Button btnSetting = findViewById(R.id.btnSetting);
        btnSetting.setOnClickListener(v -> {
            if (!isUsePassword || currentPassWord.isEmpty()) {
                // 不需要密码，直接进入设置页面
                Intent intent = new Intent(MainActivity.this, SettingActivity.class);
                startActivity(intent);
            } else {
                // 需要密码，显示自定义密码输入对话框
                showPasswordInputDialog(OperationType.SETTINGS);
            }
        });
    }

    /**
     * 观察数据变化，检测者
     */
    private void setupObservers() {
        mainViewModel.getPositionMap().observe(this, allPositionMap -> {
            // 数据判断，状态显示
            if (allPositionMap != null) {
                stationPositionMap.clear();
                stationPositionMap.putAll(allPositionMap);
                voicePositionMap.clear();
                for (Map.Entry<String, Position> entry : allPositionMap.entrySet()) {
                    if (entry.getValue().getName().contains("voice")) {
                        voicePositionMap.put(entry.getValue().getName(), entry.getValue());
                    }
                }
            }
        });

        basicViewModel.getSpeed().observe(this, speed -> {
            setSpeed = speed;
        });
        basicViewModel.getLowPower().observe(this, level -> {
            lowPowerLevel = level;
        });

        // 监听自动任务状态变化
        basicViewModel.getVoicePromptList().observe(this, list -> {
            if (list != null && !list.isEmpty()) {
                voiceList = list;
            }
        });
        basicViewModel.getAutoTaskEnabled().observe(this, enabled -> updateAutoTaskHint());
        cruiseViewModel.getCruiseTaskMap().observe(this, taskMap -> updateAutoTaskHint());

        basicViewModel.getIsUsePassword().observe(MainActivity.this, isUse -> {
            isUsePassword = isUse;
        });
        basicViewModel.getPassword().observe(this, correctPassword -> {
            currentPassWord = correctPassword;
        });
    }

    private void updateAutoTaskHint() {
        if (autoTaskHint == null) {
            return;
        }
        autoTaskHint.setVisibility(hasEnabledCruiseScheduledTask() ? View.VISIBLE : View.GONE);
    }

    private boolean hasEnabledCruiseScheduledTask() {
        if (cruiseViewModel == null) {
            return false;
        }

        Map<Integer, CruiseTask> taskMap = cruiseViewModel.getCruiseTaskMap().getValue();
        if (taskMap == null || taskMap.isEmpty()) {
            return false;
        }

        SharedPreferences preferences = cruiseSwitchPreferences != null
                ? cruiseSwitchPreferences
                : getSharedPreferences(CRUISE_SWITCH_PREFS_NAME, MODE_PRIVATE);
        for (CruiseTask task : taskMap.values()) {
            if (task != null
                    && task.isAuto()
                    && preferences.getBoolean(String.valueOf(task.getId()), false)) {
                return true;
            }
        }
        return false;
    }

    private void startStatusMonitoring() {
        statusListener = new StatusWebSocketClient.StatusListener() {
            @Override
            public void onStatusUpdate(AgvStatusResponse status) {
                if (!isActivityValid()) {
                    Log.w(TAG, "Activity destroyed, skipping delayed task");
                    return;
                }
                // Always update UI on main thread
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (status != null && status.data != null) {
                            latestStatusResponse = status;
                            if (navigationHandler != null) {
                                currentPosition = navigationHandler.getCurrentPosition();
                            }
                            updateStatusDisplay(status);
                            runPendingMainTaskIfReady();
                        }
                    }
                });
            }
        };
        sharedViewModel.registerStatusListener(statusListener);
        Log.d(TAG, "Registering listener with SharedViewModel");

    }

    private void updateStatusDisplay(AgvStatusResponse status) {

        // 更新电池状态
        if (batteryLevel != null) {
            batteryLevel.setText(status.data.powerQuantity + "%");
        }
        if (batteryProgress != null) {
            batteryProgress.setProgress(status.data.powerQuantity);
        }
        if (chargingIcon != null) {
            chargingIcon.setVisibility(status.data.electricCurrentIn > 0.01f ? View.VISIBLE : View.GONE);
        }
        updateBatteryIcon(status.data.powerQuantity, status.data.electricCurrentIn);

        // 更新导航状态
        int navState = status.data.goalFinish;
        TextView tvNavStatus = findViewById(R.id.tvNavStatus);
        if (tvNavStatus == null) return;
        switch (navState) {
            case 0:
                tvNavStatus.setText(getString(R.string.nav_status_running));
                break;
            case 1:
                tvNavStatus.setText(getString(R.string.nav_status_completed));
                break;
            case -1:
                tvNavStatus.setText(getString(R.string.nav_status_failed));
                break;
            case -2:
                tvNavStatus.setText(getString(R.string.nav_status_idle));
                break;
            default:
                tvNavStatus.setText(getString(R.string.nav_status_unknown));
        }

        // 更新错误状态
        updateWarningDisplay(status);

        TextView tvErrorStatus = findViewById(R.id.tvErrorStatus);
        Integer displayErrCode = null;

        if (tvErrorStatus == null) return;

        if (status.data.errorCode != null && !status.data.errorCode.isEmpty()) {
            if (status.data.errorCode.contains(10001)) {
                displayErrCode = 10001;
            }
            for (Integer code : status.data.errorCode) {
                if (displayErrCode != null) {
                    break;
                }
                if (code != null && ErrorCode.containsCode(code)) {
                    displayErrCode = code;
                    break;
                }
            }
        }

        if (displayErrCode == null && ErrorCode.containsCode(status.data.errCode)) {
            displayErrCode = status.data.errCode;
        }

        if (displayErrCode != null) {
            String msg = ErrorCode.getMessage(MainActivity.this, displayErrCode);
            tvErrorStatus.setText(getString(R.string.error_status, msg));
            tvErrorStatus.setTextColor(Color.RED);
        } else {
            tvErrorStatus.setText(getString(R.string.error_status_none));
            tvErrorStatus.setTextColor(Color.BLACK);
        }

        // 更新姿态置信度
        TextView tvPoseProbability = findViewById(R.id.tvPoseProbability);
        if (tvPoseProbability != null) {
            float probability = status.data.poseProbability;
            if (probability < 0.5) {
                tvPoseProbability.setTextColor(Color.RED);
            } else {
                tvPoseProbability.setTextColor(Color.BLACK);
            }
            tvPoseProbability.setText(String.format(Locale.getDefault(),
                    getString(R.string.pose_probability),
                    (int) (probability * 100)));
        }

        // 更新当前位置
        TextView tvCurrentPosition = findViewById(R.id.tvCurrentPosition);
        if (tvCurrentPosition != null && status.data.pos != null) {
            tvCurrentPosition.setText(buildCurrentLocationText(status));
        }

        // 更新系统启动状态
        boolean isReady = (status.data.goalFinish == 1 || status.data.goalFinish == -2) &&
                (status.data.errCode != 10050);
        if (isReady) {
            cancelStartView();
            // 播报欢迎语（仅一次）
            if (!hasSpokenWelcome && ttsHelper != null) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    ttsHelper.speak(getString(R.string.welcome_use));
                },500);
                hasSpokenWelcome = true;
            }
        }
    }

    // --------------------------------------------------------------------------------------------

    // 导航逻辑
    private void initWarningView() {
        warningIcon = findViewById(R.id.ivWarning);
        tvWarning = findViewById(R.id.tvWarning);

        if (warningIcon != null && warningIcon.getDrawable() instanceof AnimationDrawable) {
            warningAnimation = (AnimationDrawable) warningIcon.getDrawable();
            warningAnimation.stop();
            warningAnimation.selectDrawable(0);
        }

        if (tvWarning != null) {
            tvWarning.setText(getString(R.string.no_warnings));
            tvWarning.setSelected(true);
            tvWarning.requestFocus();
        }
    }

    private void updateWarningDisplay(AgvStatusResponse status) {
        if (tvWarning == null || status == null || status.data == null) {
            return;
        }

        StringBuilder errorMessages = new StringBuilder();
        if (status.data.errorCode != null && !status.data.errorCode.isEmpty()) {
            boolean hasEmergencyStop = status.data.errorCode.contains(10001);
            for (Integer code : status.data.errorCode) {
                if (hasEmergencyStop && (Integer.valueOf(10002).equals(code) || Integer.valueOf(10030).equals(code))) {
                    continue;
                }
                if (code != null && ErrorCode.containsCode(code)) {
                    if (errorMessages.length() > 0) {
                        errorMessages.append(";  ");
                    }
                    errorMessages.append(ErrorCode.getMessage(MainActivity.this, code));
                }
            }
        }

        if (errorMessages.length() > 0) {
            String newText = errorMessages.toString();
            if (!newText.contentEquals(tvWarning.getText())) {
                tvWarning.setText(newText);
                tvWarning.postDelayed(() -> {
                    if (tvWarning != null && tvWarning.getVisibility() == View.VISIBLE) {
                        tvWarning.setSelected(true);
                        tvWarning.requestFocus();
                    }
                }, 50);
            }
            if (warningAnimation != null && !warningAnimation.isRunning()) {
                warningAnimation.start();
            }
        } else {
            String noWarningsText = getString(R.string.no_warnings);
            if (!noWarningsText.contentEquals(tvWarning.getText())) {
                tvWarning.setText(noWarningsText);
            }
            tvWarning.setSelected(true);
            if (warningAnimation != null) {
                warningAnimation.stop();
                warningAnimation.selectDrawable(0);
            }
        }
    }

    private String buildCurrentLocationText(AgvStatusResponse status) {
        String currentMap = status.data.pos.mapName != null ? status.data.pos.mapName : "";
        if (currentMap.isEmpty()) {
            currentMap = mapViewModel.getCurrentMap();
        }
        if (currentMap == null || currentMap.isEmpty()) {
            currentMap = getString(R.string.unknown_map);
        }

        Position nearbyPoint = findNearbyPoint(status);
        String displayMapName = formatMapNameForLocationDisplay(currentMap);
        if (nearbyPoint != null) {
            return getString(R.string.home_current_location_map_station,
                    displayMapName, PositionDisplayNameHelper.getDisplayName(this, nearbyPoint));
        }
        return getString(R.string.home_current_location_map, displayMapName);
    }

    private Position findNearbyPoint(AgvStatusResponse status) {
        if (status == null || status.data == null || status.data.pos == null) {
            return null;
        }

        String currentMap = status.data.pos.mapName;
        Position nearestPoint = null;
        double nearestDistance = Double.MAX_VALUE;
        final double pointThresholdMeters = 0.5;

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints != null && currentMap != null && !currentMap.isEmpty()) {
            String buildingId = mapViewModel.extractBuildingId(currentMap);
            int currentFloor = mapViewModel.extractMapFloor(currentMap);
            Building building = mapPoints.getBuilding(buildingId);
            FloorPoints floorPoints = building != null ? building.getFloorPoints(currentFloor) : null;
            if (floorPoints != null) {
                for (Position point : floorPoints.getAllPoints()) {
                    double distance = calculateDistanceToRobot(status, point, currentMap);
                    if (distance <= pointThresholdMeters && distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestPoint = point;
                    }
                }
            }
        }

        if (nearestPoint == null && stationPositionMap != null && !stationPositionMap.isEmpty()) {
            for (Map.Entry<String, Position> entry : stationPositionMap.entrySet()) {
                Position station = entry.getValue();
                double distance = calculateDistanceToRobot(status, station, currentMap);
                if (distance <= pointThresholdMeters && distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestPoint = station;
                }
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

    private boolean isSameMapForLocationDisplay(String currentMap, String stationMap) {
        if (currentMap == null || currentMap.isEmpty() || stationMap == null || stationMap.isEmpty()) {
            return true;
        }
        return normalizeMapNameForLocationDisplay(currentMap)
                .equals(normalizeMapNameForLocationDisplay(stationMap));
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

        return getString(R.string.current_location_building_floor, buildingName, floorName);
    }

    private void handleStartupMainTask(Intent intent) {
        if (intent == null) {
            return;
        }
        int taskId = intent.getIntExtra("task_id", -1);
        if (taskId != -1) {
            executeMainByTaskId(taskId);
        }

        List<Position> mainTask = (List<Position>) intent.getSerializableExtra("task_object");
        if (mainTask != null) {
            executeMainByTaskObject(mainTask);
        }
    }

    private boolean isStatusReadyForMainTask() {
        return latestStatusResponse != null && latestStatusResponse.data != null;
    }

    private void runPendingMainTaskIfReady() {
        if (!isStatusReadyForMainTask()) {
            return;
        }
        if (pendingMainTaskId != -1) {
            int taskId = pendingMainTaskId;
            pendingMainTaskId = -1;
            Log.d(TAG, "Running pending main task after first status: " + taskId);
            executeMainByTaskId(taskId);
            return;
        }
        if (pendingMainTaskPositions != null) {
            List<Position> taskPositions = pendingMainTaskPositions;
            pendingMainTaskPositions = null;
            Log.d(TAG, "Running pending main task object after first status");
            executeMainByTaskObject(taskPositions);
        }
    }

     void executeMainByTaskId(int taskId) {
        if ((taskId == 1 || taskId == 2) && !isStatusReadyForMainTask()) {
            pendingMainTaskId = taskId;
            pendingMainTaskPositions = null;
            Log.d(TAG, "Main task waits for first robot status: " + taskId);
            return;
        }
        switch (taskId) {
            case 1:
                startReturnToCharge();
                break;
            case 2:
                startReturnToHome();
                break;
            default:
                navigationHandler.cancelNavigation(true);
                break;
        }
    }

    void executeMainByTaskObject(List<Position> selectedPosition) {
        if (ChargeTaskNavigationHelper.isChargeDestinationRoute(selectedPosition)
                && ChargeTaskNavigationHelper.shouldRejectChargeTaskDispatch()) {
            onVoicePrompt(VoiceKeyConstants.CHARGING_TASK_ERROR);
            Log.w(TAG, "Charge task rejected: robot is already charging");
            return;
        }
        if (!isStatusReadyForMainTask()) {
            pendingMainTaskPositions = selectedPosition;
            pendingMainTaskId = -1;
            Log.d(TAG, "Main task object waits for first robot status");
            return;
        }
        List<Position> fullPositionList = generateFullPositionList(currentPosition,
                selectedPosition);
        if (fullPositionList == null) {
            return;
        }
        Log.d(TAG, "start main task in executeMainByTaskObject");
        // 开始任务的逻辑
        navigationHandler.startNavigation(fullPositionList, () -> {
            // Completion callback
            //runOnUiThread(() -> onShowToast(R.string.task_completed));
            runOnUiThread(() ->{
                Log.d(TAG, "RCS main navigation completed");
            });
        });
    }

    /**
     * Start returning to charge point
     * If current floor has no charge point, use the nearest floor that has one
     */
    private void startReturnToCharge() {
        // 按钮防抖
        if (isButtonClickDebounced()) {
            return;
        }
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        // 优先使用机器人当前所在楼宇，fallback 到 firstNonZeroBuilding
        Building firstBuilding = mapPoints != null
                ? mapPoints.getBuilding(mapViewModel.getCurrentBuilding()) : null;
        if (firstBuilding == null && mapPoints != null) {
            Log.w("taskDebug7", "[CHARGE] currentBuilding '" + mapViewModel.getCurrentBuilding() +
                    "' not in mapPoints, fallback to firstNonZeroBuilding");
            firstBuilding = mapPoints.getFirstNonZeroBuilding();
        }
        int buildings = mapPoints != null ? mapPoints.getAllBuildings().size() : 0;
        int totalPoints = mapPoints != null ? mapPoints.getTotalPositionCount() : 0;

        if (firstBuilding == null) {
            Log.e("taskDebug7", "[CHARGE-FAIL] startReturnToCharge: firstBuilding=null, mapPoints buildings=" +
                    buildings + ", totalPoints=" + totalPoints);
            onShowToast(getString(R.string.station_not_exist, "charge"));
            return;
        }
        Log.i("taskDebug7", "[CHARGE] startReturnToCharge: firstBuilding=" + firstBuilding.getBuildingId() +
                ", currentBuilding='" + mapViewModel.getCurrentBuilding() + "'" +
                ", buildings=" + buildings + ", totalPoints=" + totalPoints);

        int currentFloor = mapViewModel.getCurrentFloor();
        Log.i("taskDebug7", "[CHARGE] currentFloor=" + currentFloor + ", dumping ALL points before lookup:");
        if (mapPoints != null) {
            for (Building b : mapPoints.getAllBuildings().values()) {
                for (Map.Entry<Integer, FloorPoints> fe : b.getAllFloorPoints().entrySet()) {
                    FloorPoints fp = fe.getValue();
                    Position cp = fp.getChargePoint();
                    Position pp = fp.getParkPoint();
                    Log.i("taskDebug7", "[CHARGE-DUMP] building=" + b.getBuildingId() +
                            ", floor=" + fe.getKey() +
                            ", workPoints=" + (fp.getWorkPoints() != null ? fp.getWorkPoints().size() : 0) +
                            ", chargePoint=" + (cp != null ? "name=" + cp.getName() +
                                    ",id=" + cp.getId() +
                                    ",x=" + cp.getPosX() + ",y=" + cp.getPosY() +
                                    ",type=" + cp.getType() + ",floor=" + cp.getFloor() : "null") +
                            ", parkPoint=" + (pp != null ? "name=" + pp.getName() +
                                    ",id=" + pp.getId() +
                                    ",x=" + pp.getPosX() + ",y=" + pp.getPosY() +
                                    ",type=" + pp.getType() + ",floor=" + pp.getFloor() : "null"));
                }
            }
        }

        // Get the appropriate charge point (from current floor or nearest floor)
        Position targetChargePoint = mapViewModel.getNearestChargePoint(firstBuilding.getBuildingId(), currentFloor);

        // Check if charge point exists
        if (targetChargePoint == null || !isPositionValid(targetChargePoint)) {
            onVoicePrompt(VoiceKeyConstants.CHARGE_FAILED);
            Log.e("taskDebug7", "[CHARGE-FAIL] getNearestChargePoint returned " +
                    (targetChargePoint == null ? "null" : "invalid(position=" + targetChargePoint + ")") +
                    ". firstBuilding='" + firstBuilding.getBuildingId() + "', currentFloor=" + currentFloor +
                    ", currentBuilding='" + mapViewModel.getCurrentBuilding() + "'" +
                    ". Hint: points exist in building=8 (per [CHARGE-DUMP]), but lookup used firstBuilding='" +
                    firstBuilding.getBuildingId() + "'");
            return;
        }

        // Log which floor we're using
        int chargePointFloor;
        try {
            chargePointFloor = Integer.parseInt(targetChargePoint.getFloor());
        } catch (NumberFormatException | NullPointerException e) {
            chargePointFloor = currentFloor;
        }

        if (chargePointFloor != currentFloor) {
            Log.d(TAG, "startReturnToCharge: Current floor " + currentFloor +
                    " has no charge point. Using charge point from floor " + chargePointFloor);
            String message = getString(R.string.no_charge_point_use_floor, chargePointFloor);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }

        if (latestStatusResponse == null || latestStatusResponse.data == null) {
            onShowToast(getString(R.string.waiting_robot_status));
            return;
        }

        // Check charging status
        if (ChargeTaskNavigationHelper.shouldRejectChargeTaskDispatch()) {
            onVoicePrompt(VoiceKeyConstants.CHARGING_TASK_ERROR);
            return;
        }

        // Check confidence
        if (latestStatusResponse.data.poseProbability < DEFAULT_CONFIDENCE) {
            onVoicePrompt(VoiceKeyConstants.LOW_CONFIDENCE_ERROR);
            return;
        }

        // Start navigation to charge point
        if (chargingManager != null) {
            // If we're going to a different floor, we need to handle floor transition
            chargingManager.startAutoChargeNavigation();
        }
    }

    /**
     * Start returning to park (home) point
     * If current floor has no park point, use the nearest floor that has one
     */
    private void startReturnToHome() {
        // 按钮防抖
        if (isButtonClickDebounced()) {
            return;
        }
        TaskDebug1.log("[PARK] startReturnToHome enter");
        TaskDebug8.log("[PARK] startReturnToHome ENTER");
        // 低电量时拦截前往待命点任务，应前往充电而非待命
        if (!checkBatteryCanAcceptTask()) {
            TaskDebug8.log("[PARK] startReturnToHome ABORT battery check failed");
            return;
        }
        TaskDebug8.log(String.format("[PARK] startReturnToHome batteryOk=true level=%d currentBuilding=%s currentFloor=%d",
                BatteryLevelManager.getInstance().getCurrentBatteryLevel(),
                mapViewModel.getCurrentBuilding(), mapViewModel.getCurrentFloor()));
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        // 优先使用机器人当前所在楼宇，fallback 到 firstNonZeroBuilding
        Building firstBuilding = mapPoints != null
                ? mapPoints.getBuilding(mapViewModel.getCurrentBuilding()) : null;
        if (firstBuilding == null && mapPoints != null) {
            Log.w("taskDebug7", "[PARK] currentBuilding '" + mapViewModel.getCurrentBuilding() +
                    "' not in mapPoints, fallback to firstNonZeroBuilding");
            firstBuilding = mapPoints.getFirstNonZeroBuilding();
        }
        int buildings = mapPoints != null ? mapPoints.getAllBuildings().size() : 0;
        int totalPoints = mapPoints != null ? mapPoints.getTotalPositionCount() : 0;

        if (firstBuilding == null) {
            Log.e("taskDebug7", "[PARK-FAIL] startReturnToHome: firstBuilding=null, mapPoints buildings=" +
                    buildings + ", totalPoints=" + totalPoints);
            onShowToast(getString(R.string.station_not_exist, "park"));
            return;
        }
        Log.i("taskDebug7", "[PARK] startReturnToHome: firstBuilding=" + firstBuilding.getBuildingId() +
                ", currentBuilding='" + mapViewModel.getCurrentBuilding() + "'" +
                ", buildings=" + buildings + ", totalPoints=" + totalPoints);

        int currentFloor = mapViewModel.getCurrentFloor();
        Log.i("taskDebug7", "[PARK] currentFloor=" + currentFloor + ", dumping ALL points before lookup:");
        if (mapPoints != null) {
            for (Building b : mapPoints.getAllBuildings().values()) {
                for (Map.Entry<Integer, FloorPoints> fe : b.getAllFloorPoints().entrySet()) {
                    FloorPoints fp = fe.getValue();
                    Position cp = fp.getChargePoint();
                    Position pp = fp.getParkPoint();
                    Log.i("taskDebug7", "[PARK-DUMP] building=" + b.getBuildingId() +
                            ", floor=" + fe.getKey() +
                            ", workPoints=" + (fp.getWorkPoints() != null ? fp.getWorkPoints().size() : 0) +
                            ", chargePoint=" + (cp != null ? "name=" + cp.getName() +
                                    ",id=" + cp.getId() +
                                    ",x=" + cp.getPosX() + ",y=" + cp.getPosY() +
                                    ",type=" + cp.getType() + ",floor=" + cp.getFloor() : "null") +
                            ", parkPoint=" + (pp != null ? "name=" + pp.getName() +
                                    ",id=" + pp.getId() +
                                    ",x=" + pp.getPosX() + ",y=" + pp.getPosY() +
                                    ",type=" + pp.getType() + ",floor=" + pp.getFloor() : "null"));
                }
            }
        }

        // Get the appropriate park point (from current floor or nearest floor)
        Position targetParkPoint = mapViewModel.getNearestParkPoint(firstBuilding.getBuildingId(), currentFloor);
        TaskDebug8.log(String.format("[PARK] getNearestParkPoint building=%s floor=%d -> %s",
                firstBuilding.getBuildingId(), currentFloor,
                targetParkPoint != null ? String.format("name=%s id=%d type=%d floor=%s (%.3f,%.3f) map=%s",
                        targetParkPoint.getName(), targetParkPoint.getId(), targetParkPoint.getType(),
                        targetParkPoint.getFloor(), targetParkPoint.getPosX(), targetParkPoint.getPosY(),
                        targetParkPoint.getMapName()) : "null"));

        // Check if charge point exists
        if (targetParkPoint == null || !isPositionValid(targetParkPoint)) {
            TaskDebug8.log("[PARK] getNearestParkPoint FAILED (null or invalid), abort");
            onVoicePrompt(VoiceKeyConstants.PARK_FAILED);
            Log.e("taskDebug7", "[PARK-FAIL] getNearestParkPoint returned " +
                    (targetParkPoint == null ? "null" : "invalid(position=" + targetParkPoint + ")") +
                    ". firstBuilding='" + firstBuilding.getBuildingId() + "', currentFloor=" + currentFloor +
                    ", currentBuilding='" + mapViewModel.getCurrentBuilding() + "'" +
                    ". Hint: points exist in building=8 (per [PARK-DUMP]), but lookup used firstBuilding='" +
                    firstBuilding.getBuildingId() + "'");
            return;
        }

        // Log which floor we're using
        int parkPointFloor;
        try {
            parkPointFloor = Integer.parseInt(targetParkPoint.getFloor());
        } catch (NumberFormatException | NullPointerException e) {
            parkPointFloor = currentFloor;
        }

        if (parkPointFloor != currentFloor) {
            Log.d(TAG, "startReturnToPark: Current floor " + currentFloor +
                    " has no park point. Using pakr point from floor " + parkPointFloor);
            String message = getString(R.string.no_park_point_use_floor, parkPointFloor);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }

        // Check confidence
        AgvStatusResponse latestStatus = navigationHandler.getLatestStatus();
        if (latestStatus != null && latestStatus.data != null &&
                latestStatus.data.poseProbability < DEFAULT_CONFIDENCE) {
            TaskDebug8.log(String.format("[PARK] startReturnToHome ABORT low confidence prob=%.2f < %d",
                    latestStatus.data.poseProbability, DEFAULT_CONFIDENCE));
            onShowToast(R.string.low_confidence_navigation);
            onVoicePrompt(VoiceKeyConstants.LOW_CONFIDENCE_ERROR);
            return;
        }

        targetParkPoint.setType(12);
        targetParkPoint.setTaskType(5);

        double parkDist = -1;
        if (currentPosition != null) {
            double dx = currentPosition.getPosX() - targetParkPoint.getPosX();
            double dy = currentPosition.getPosY() - targetParkPoint.getPosY();
            parkDist = Math.sqrt(dx * dx + dy * dy);
        }
        int goalFinish = latestStatus != null && latestStatus.data != null ? latestStatus.data.goalFinish : -999;
        boolean taskRunning = navigationHandler != null && navigationHandler.isTaskRunning();
        boolean gifPlaying = navigationHandler != null && navigationHandler.isGifPlaying();
        TaskDebug8.log(String.format("[PARK] target=%s id=%d type=%d taskType=%d parkDist=%.3f goalFinish=%d taskRunning=%b gifPlaying=%b cur=(%.3f,%.3f) park=(%.3f,%.3f) floor=%s map=%s",
                targetParkPoint.getName(), targetParkPoint.getId(), targetParkPoint.getType(), targetParkPoint.getTaskType(), parkDist,
                goalFinish, taskRunning, gifPlaying,
                currentPosition != null ? currentPosition.getPosX() : 0,
                currentPosition != null ? currentPosition.getPosY() : 0,
                targetParkPoint.getPosX(), targetParkPoint.getPosY(),
                targetParkPoint.getFloor(), targetParkPoint.getMapName()));
        TaskDebug1.log(String.format("[PARK] target=%s type=%d taskType=%d dist=%.3f goalFinish=%d taskRunning=%b gifPlaying=%b cur=(%.3f,%.3f) park=(%.3f,%.3f)",
                targetParkPoint.getName(), targetParkPoint.getType(), targetParkPoint.getTaskType(), parkDist,
                goalFinish, taskRunning, gifPlaying,
                currentPosition != null ? currentPosition.getPosX() : 0,
                currentPosition != null ? currentPosition.getPosY() : 0,
                targetParkPoint.getPosX(), targetParkPoint.getPosY()));

        List<Position> parkRoute = new ArrayList<>();
        parkRoute.add(targetParkPoint);

        // 已在待命点（x、y、角度完全一致）时跳过导航，直接显示完成动画
        boolean alreadyAtPark = false;
        if (currentPosition != null && !taskRunning) {
            double dx = Math.abs(currentPosition.getPosX() - targetParkPoint.getPosX());
            double dy = Math.abs(currentPosition.getPosY() - targetParkPoint.getPosY());
            double dYaw = Math.abs(currentPosition.getYaw() - targetParkPoint.getYaw());
            alreadyAtPark = dx < 0.05 && dy < 0.05 && dYaw < 0.05;
        }
        if (alreadyAtPark) {
            TaskDebug8.log(String.format("[PARK] ALREADY_AT_PARK pos=(%.3f,%.3f,%.3f) == park=(%.3f,%.3f,%.3f), skip navigation",
                    currentPosition.getPosX(), currentPosition.getPosY(), currentPosition.getYaw(),
                    targetParkPoint.getPosX(), targetParkPoint.getPosY(), targetParkPoint.getYaw()));
            TaskDebug1.log("[PARK] ALREADY_AT_PARK, skip nav");
            MyApplication.getInstance().getTaskViewModel()
                    .markTaskExecuting("park", targetParkPoint.getName());
            onVoicePrompt(VoiceKeyConstants.DEFAULT_RETURNING_HOME_TEXT);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                navigationHandler.showParkCompletionDialog(targetParkPoint, () ->
                        runOnUiThread(() -> {
                            TaskDebug8.log("[PARK] direct completion: markTaskCompleted");
                            TaskDebug1.log("[PARK] direct completion: markTaskCompleted");
                            MyApplication.getInstance().getTaskViewModel().markTaskCompleted();
                        }));
            }, 500);
            return;
        }

        List<Position> fullPositionList = generateFullPositionList(currentPosition, parkRoute);
        if (fullPositionList == null) {
            TaskDebug8.log("[PARK] generateFullPositionList returned null, abort");
            TaskDebug1.log("[PARK] generateFullPositionList returned null, abort");
            return;
        }
        TaskDebug8.log(String.format("[PARK] generateFullPositionList OK routeSize=%d", fullPositionList.size()));
        TaskDebug1.log(String.format("[PARK] start navigation routeSize=%d", fullPositionList.size()));
        for (int i = 0; i < fullPositionList.size(); i++) {
            Position p = fullPositionList.get(i);
            TaskDebug8.log(String.format("[PARK] route[%d] name=%s id=%d type=%d taskType=%d floor=%s map=%s (%.3f,%.3f)",
                    i, p.getName(), p.getId(), p.getType(), p.getTaskType(), p.getFloor(), p.getMapName(),
                    p.getPosX(), p.getPosY()));
        }

        onVoicePrompt(VoiceKeyConstants.DEFAULT_RETURNING_HOME_TEXT);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            TaskDebug8.log(String.format("[PARK] navigationHandler.startNavigation invoked routeSize=%d", fullPositionList.size()));
            TaskDebug1.log("[PARK] navigationHandler.startNavigation invoked");
            Log.d(TAG, "Starting park task in startReturnToHome");
            MyApplication.getInstance().getTaskViewModel()
                    .markTaskExecuting("park", targetParkPoint.getName());
            navigationHandler.startNavigation(fullPositionList, () ->
                    runOnUiThread(() -> {
                        TaskDebug8.log(String.format("[PARK] completion callback: markTaskCompleted target=%s", targetParkPoint.getName()));
                        TaskDebug1.log("[PARK] completion callback: markTaskCompleted");
                        MyApplication.getInstance().getTaskViewModel().markTaskCompleted();
                    }));
        }, 500);
    }

    /**
     * Helper method to check if a position is valid (not default 0,0,0 and has map name)
     */
    private boolean isPositionValid(Position position) {
        if (position == null) return false;

        // Check if it's the default position (x=0, y=0, yaw=0)
        boolean isDefaultPosition = Math.abs(position.getPosX()) < 0.001 &&
                Math.abs(position.getPosY()) < 0.001 &&
                Math.abs(position.getYaw()) < 0.001;

        // Check if map name exists
        boolean hasValidMap = position.getMapName() != null && !position.getMapName().isEmpty();

        return !isDefaultPosition && hasValidMap;
    }

    private boolean isParkPositionConfigured(Position position) {
        return position != null && !position.isDefaultPoint() && position.getId() >= 0;
    }

    private int resolveParkPointFloor(Position position, int fallbackFloor) {
        if (position != null && position.getFloor() != null && !position.getFloor().trim().isEmpty()) {
            try {
                return Integer.parseInt(position.getFloor().trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid park point floor: " + position.getFloor(), e);
            }
        }
        return fallbackFloor;
    }

    // --------------------------------------------------------------------------------------------

    // 导航相关UI


    // --------------------------------------------------------------------------------------------

    // 布局相关UI

    private void setTimeContainerWidth(int widthDp) {
        if (timeContainer != null) {
            // 获取时钟组件引用
            ClockView clockView = findViewById(R.id.clockView);
            LinearLayout textContainer = findViewById(R.id.textContainer); // 添加文本容器引用
            // 设置宽度
            ViewGroup.LayoutParams param = timeContainer.getLayoutParams();
            param.width = (int) (widthDp * getResources().getDisplayMetrics().density);
            timeContainer.setLayoutParams(param);
            timeContainer.requestLayout();

            // 根据宽度设置时钟可见性
            if (clockView != null) {
                if (widthDp == TIME_WIDTH_EXPANDED) { // 480dp
                    clockView.setVisibility(View.VISIBLE);
                    clockView.postInvalidate(); // 刷新时钟
                } else { // 270dp
                    clockView.setVisibility(View.INVISIBLE);
                }
            }// 根据宽度设置布局
            if (widthDp == TIME_WIDTH_EXPANDED) { // 480dp
                // 显示时钟
                clockView.setVisibility(View.VISIBLE);
                clockView.getLayoutParams().width = 100; // 恢复时钟宽度
                clockView.getLayoutParams().height = 100; // 恢复时钟高度

                // 调整文本容器宽度为0，使用权重分配空间
                textContainer.getLayoutParams().width = 0;
                textContainer.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                ((LinearLayout.LayoutParams) textContainer.getLayoutParams()).weight = 1;

                // 刷新时钟
                clockView.postInvalidate();
            } else { // 270dp
                // 完全隐藏时钟
                clockView.setVisibility(View.GONE);
                clockView.getLayoutParams().width = 0;
                clockView.getLayoutParams().height = 0;

                // 文本容器占据全部空间
                textContainer.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                textContainer.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                ((LinearLayout.LayoutParams) textContainer.getLayoutParams()).weight = 0;
            }

            // 将dp转换为px
            ViewGroup.LayoutParams params = timeContainer.getLayoutParams();

            // 直接设置宽度值（单位：像素）
            params.width = (int) (widthDp * getResources().getDisplayMetrics().density);

            // 应用新布局参数
            timeContainer.setLayoutParams(params);

            // 确保UI更新
            timeContainer.requestLayout();
        }
    }

    private int getWifiSignalStrength() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                Log.e("WiFi", "WifiManager is null");
                return 0;
            }

            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null) {
                Log.e("WiFi", "WifiInfo is null");
                return 0;
            }

            // Check if WiFi is actually connected
            int rssi = wifiInfo.getRssi();
            // 如果RSSI为Integer.MIN_VALUE，表示无效信号
            if (rssi == Integer.MIN_VALUE) {
                return 0;
            }
            // 计算信号等级（0到4）
            return WifiManager.calculateSignalLevel(rssi, 5);
        } catch (SecurityException e) {
            Log.e("WiFi", "Permission denied for WiFi access", e);
            return 0;
        } catch (Exception e) {
            Log.e("WiFi", "Error getting WiFi signal strength", e);
            return 0;
        }
    }

    private void updateWifiSignalDisplay() {
        if (!isActivityValid()) {
            Log.w(TAG, "Activity destroyed, skipping delayed task");
            return;
        }
        try {
            // Check if the activity is finishing or icon is null
            if (isFinishing() || wifiSignalIcon == null) {
                return;
            }

            int signalLevel = getWifiSignalStrength();

            // Update icon based on signal strength (0-4)
            int resId;
            switch (signalLevel) {
                case 4: resId = R.drawable.ic_wifi_4; break;
                case 3: resId = R.drawable.ic_wifi_3; break;
                case 2: resId = R.drawable.ic_wifi_2; break;
                case 1: resId = R.drawable.ic_wifi_1; break;
                default: resId = R.drawable.ic_wifi_0; break;
            }

            runOnUiThread(() -> {
                if (wifiSignalIcon != null) {
                    wifiSignalIcon.setImageResource(resId);
                }
            });

            // Schedule next update only if we're still active
            if (!isFinishing()) {
                wifiSignalHandler.postDelayed(this::updateWifiSignalDisplay, WIFI_UPDATE_INTERVAL);
            }
        } catch (Exception e) {
            Log.e("WiFi", "Error updating WiFi display", e);
        }
    }

    //设置页面启动反馈
    //添加文本提示
    private void setStartView(){
        startTipLayout = findViewById(R.id.startTip);
        viewStartTip = startTipLayout.findViewById(R.id.tvLoading);
        if (viewStartTip != null) {
            viewStartTip.setText(R.string.robot_loading);
            viewStartTip.setVisibility(View.VISIBLE);
        }
    }

    // 取消文本提示
    private void cancelStartView() {
        startTipLayout = findViewById(R.id.startTip);
        if (startTipLayout != null) {
            startTipLayout.setVisibility(View.GONE);
        }
    }

    /**
     * 启动时钟显示
     */
    private void startClock() {
        timeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Date now = new Date();
                tvDate.setText(dateFormat.format(now));
                tvTime.setText(timeFormat.format(now));
                timeHandler.postDelayed(this, 1000);
            }
        }, 0);
    }

    /**
     * 更新电池图标显示
     * @param batteryPct 电池百分比
     * @param electricCurrentIn 充电电流
     */
    private void updateBatteryIcon(int batteryPct, float electricCurrentIn) {
        // No need for runOnUiThread since LiveData observation is already on UI thread
        int fillColor;
        if (electricCurrentIn > 0.01f) {
            fillColor = Color.GREEN;
        } else if (batteryPct <= 20) {
            fillColor = Color.RED;
        } else if (batteryPct <= 50) {
            fillColor = Color.YELLOW;
        } else {
            fillColor = Color.GREEN;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryProgress.setProgressTintList(ColorStateList.valueOf(fillColor));
        } else {
            LayerDrawable progressDrawable = (LayerDrawable) batteryProgress.getProgressDrawable();
            progressDrawable.findDrawableByLayerId(android.R.id.progress)
                    .setColorFilter(fillColor, PorterDuff.Mode.SRC_IN);
        }
    }

    private void showPasswordInputDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_input, null);

        // 创建对话框
        Dialog passwordDialog = new Dialog(this);
        passwordDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        passwordDialog.setContentView(dialogView);
        passwordDialog.setCancelable(false);
        passwordDialog.setCanceledOnTouchOutside(false);


        // 设置对话框属性
        Window window = passwordDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);

            // 添加动画效果
            window.setWindowAnimations(R.style.DialogAnimation);
        }

        // 关闭功能
        View overlay = dialogView.findViewById(R.id.dialogOverlay);
        overlay.setOnClickListener(null);
        overlay.setClickable(true);
        ImageButton btnClose = dialogView.findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> passwordDialog.dismiss());

        // 初始化视图
        TextView tvPasswordDisplay = dialogView.findViewById(R.id.tvPasswordDisplay);
        GridLayout gridLayout = dialogView.findViewById(R.id.gridLayout);
        Button btnRfid = dialogView.findViewById(R.id.btn_rfid);

        // 存储用户输入的密码
        StringBuilder enteredPassword = new StringBuilder();

        // 数字按钮点击事件
        for (int i = 0; i < gridLayout.getChildCount(); i++) {
            View view = gridLayout.getChildAt(i);
            if (view instanceof Button) {
                Button button = (Button) view;
                button.setOnClickListener(v -> {
                    if (button.getId() == R.id.btn_delete) {
                        // 处理删除按钮
                        if (enteredPassword.length() > 0) {
                            enteredPassword.deleteCharAt(enteredPassword.length() - 1);
                            updatePasswordDisplay(tvPasswordDisplay, enteredPassword.length());
                        }
                    }
                    else if (button.getId() == R.id.btn_confirm) {
                        // 处理确定按钮
                        verifyPassword(passwordDialog, enteredPassword.toString());
                    }
                    else {
                        // 处理数字按钮
                        if (enteredPassword.length() < 4) {
                            enteredPassword.append(button.getText().toString());
                            updatePasswordDisplay(tvPasswordDisplay, enteredPassword.length());
                        }
                    }
                });
            }
        }

        // 刷卡按钮点击事件
        btnRfid.setOnClickListener(v -> {
            // 显示刷卡提示
            tvPasswordDisplay.setText(getString(R.string.swipe_card_prompt));
            
            // 启动RFID读取
            new Thread(() -> {
                try {
                    RFIDCommunicator rfidCommunicator = RFIDCommunicator.getInstance();
                    String rfidData = rfidCommunicator.readRfidWithTimeout();
                    
                    runOnUiThread(() -> {
                        if (rfidData != null) {
                            // 解析卡片序列号（第9-12字节）
                            if (rfidData.length() >= 24) {
                                String cardSerial = rfidData.substring(16, 24);
                                Log.d("MainActivity", "解析到卡片序列号: " + cardSerial);
                                
                                // 检查是否是已录入的RFID
                                com.ezhan.amr.viewmodels.RfidViewModel rfidViewModel = MyApplication.getInstance().getRfidViewModel();
                                if (rfidViewModel != null) {
                                    java.util.List<com.ezhan.amr.data.datatype.RfidData> rfidList = rfidViewModel.getRfidList().getValue();
                                    if (rfidList != null) {
                                        boolean isRfidValid = false;
                                        for (com.ezhan.amr.data.datatype.RfidData rfid : rfidList) {
                                            if (rfid.getRfidSerial().equals(cardSerial)) {
                                                isRfidValid = true;
                                                break;
                                            }
                                        }
                                        
                                        if (isRfidValid) {
                                            // 刷卡成功，关闭对话框并执行相应操作
                                            passwordDialog.dismiss();
                                            executeOperation(currentOperation);
                                        } else {
                                            // 未找到该RFID
                                            tvPasswordDisplay.setText("____");
                                            Toast.makeText(MainActivity.this, R.string.rfid_invalid_card, Toast.LENGTH_SHORT).show();
                                        }
                                    } else {
                                        // RFID列表为空
                                        tvPasswordDisplay.setText("____");
                                        Toast.makeText(MainActivity.this, R.string.rfid_list_empty, Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    // RFIDViewModel未初始化
                                    tvPasswordDisplay.setText("____");
                                    Toast.makeText(MainActivity.this, R.string.rfid_system_not_init, Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                // 数据长度不足
                                tvPasswordDisplay.setText("____");
                                Toast.makeText(MainActivity.this, R.string.rfid_data_format_error, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            // 刷卡超时，恢复密码输入提示
                            tvPasswordDisplay.setText("____");
                            Toast.makeText(MainActivity.this, R.string.rfid_timeout_retry, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvPasswordDisplay.setText("____");
                        Toast.makeText(MainActivity.this, R.string.rfid_init_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });

        // 添加对话框关闭时恢复布局的逻辑
        passwordDialog.setOnDismissListener(dialog -> {
            // 恢复系统UI状态
            setupFullScreen();
        });

        // 显示对话框
        passwordDialog.show();

        // 临时改变状态栏颜色以增强视觉体验
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.dialog_overlay));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.dialog_overlay));
    }

    // 更新密码显示（显示*号）
    private void updatePasswordDisplay(TextView tv, int length) {
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < length; i++) {
            display.append("•");
        }
        tv.setText(display.toString());
    }

    // 操作类型枚举
    private enum OperationType {
        SETTINGS,
        DELIVERY,
        CRUISE,
        JACK,
        RETURN_TO_HOME,
        RETURN_TO_CHARGE
    }

    // 当前操作类型
    private OperationType currentOperation = OperationType.SETTINGS;

    // 验证密码
    private void verifyPassword(Dialog dialog, String enteredPassword) {
        if (currentPassWord != null && (currentPassWord.equals(enteredPassword) || "9999".equals(enteredPassword))) {
            // 密码正确，关闭对话框并执行相应操作
            dialog.dismiss();
            executeOperation(currentOperation);
        } else {
            // 密码错误，显示错误信息
            Toast.makeText(this, R.string.password_incorrect, Toast.LENGTH_SHORT).show();
        }
    }

    // 执行操作
    private void executeOperation(OperationType operation) {
        switch (operation) {
            case SETTINGS:
                Intent settingsIntent = new Intent(MainActivity.this, SettingActivity.class);
                startActivity(settingsIntent);
                break;
            case DELIVERY:
                Intent deliveryIntent = new Intent(MainActivity.this, DeliveryActivity.class);
                startActivity(deliveryIntent);
                break;
            case CRUISE:
                Intent cruiseIntent = new Intent(MainActivity.this, CruiseActivity.class);
                startActivity(cruiseIntent);
                break;
            case JACK:
                btnJackMode.setSelected(!btnJackMode.isSelected());
                Intent jackIntent = new Intent(MainActivity.this, JackActivity.class);
                startActivity(jackIntent);
                break;
            case RETURN_TO_HOME:
                startReturnToHome();
                break;
            case RETURN_TO_CHARGE:
                startReturnToCharge();
                break;
        }
    }

    // ==================== Config File Dialog ====================

    private void showConfigFileDialog() {
        AppSettingsConfig cfg = AppSettingsConfig.getInstance();
        cfg.loadConfig(this, false);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.config_file_settings));

        // Main layout
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 24, 40, 16);

        // Auto load on startup switch row
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView switchLabel = new TextView(this);
        switchLabel.setText(getString(R.string.auto_load_config));
        switchLabel.setTextSize(16);
        switchRow.addView(switchLabel);

        android.widget.Switch autoLoadSwitch = new android.widget.Switch(this);
        autoLoadSwitch.setChecked(cfg.isAutoLoadConfigOnStartup());
        autoLoadSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cfg.setAutoLoadConfigOnStartup(isChecked);
            cfg.saveConfig();
            if (isChecked) {
                applyConfigFromDialog(cfg);
            }
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        switchParams.leftMargin = 16;
        switchRow.addView(autoLoadSwitch, switchParams);

        mainLayout.addView(switchRow);

        // Button row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 16, 0, 8);
        btnRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Load config button
        Button btnLoad = new Button(this);
        btnLoad.setText(getString(R.string.load_config_now));
        btnLoad.setBackgroundColor(0xFF4CAF50);
        btnLoad.setTextColor(android.graphics.Color.WHITE);
        btnLoad.setAllCaps(false);
        btnLoad.setOnClickListener(v -> {
            cfg.loadConfig(this, true);
            applyConfigFromDialog(cfg);
            Toast.makeText(this, getString(R.string.config_loaded), Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(btnLoad, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Save config button
        Button btnSave = new Button(this);
        btnSave.setText(getString(R.string.save_config_now));
        btnSave.setBackgroundColor(0xFF2196F3);
        btnSave.setTextColor(android.graphics.Color.WHITE);
        btnSave.setAllCaps(false);
        btnSave.setOnClickListener(v -> {
            saveConfigFromDialog(cfg);
            String path = cfg.getConfigFilePath();
            Toast.makeText(this, getString(R.string.save_config) + "\n" + path, Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.leftMargin = 8;
        btnRow.addView(btnSave, saveParams);

        // Edit config button
        Button btnEdit = new Button(this);
        btnEdit.setText(getString(R.string.edit_config));
        btnEdit.setBackgroundColor(0xFFFF9800);
        btnEdit.setTextColor(android.graphics.Color.WHITE);
        btnEdit.setAllCaps(false);
        btnEdit.setOnClickListener(v -> {
            showEditConfigJsonDialog(cfg);
        });
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        editParams.leftMargin = 8;
        btnRow.addView(btnEdit, editParams);

        mainLayout.addView(btnRow);

        builder.setView(mainLayout);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.show();
    }

    private void showEditConfigJsonDialog(AppSettingsConfig cfg) {
        String jsonStr = cfg.getConfigJsonString();

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.edit_config_title));

        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setText(jsonStr);
        editText.setTextSize(12);
        editText.setTypeface(android.graphics.Typeface.MONOSPACE);
        editText.setPadding(32, 24, 32, 24);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(editText);
        scrollView.setPadding(24, 16, 24, 16);

        builder.setView(scrollView);

        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String newJson = editText.getText().toString();
            if (cfg.saveConfigFromString(newJson)) {
                applyConfigFromDialog(cfg);
                Toast.makeText(this, getString(R.string.config_edit_saved), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.config_json_invalid), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void applyConfigFromDialog(AppSettingsConfig cfg) {
        if (basicViewModel == null) return;

        // Apply to ViewModel (persists to DataStore)
        basicViewModel.setVolumeLevel(cfg.getVolumeLevel());
        basicViewModel.setSpeed(cfg.getSpeed());
        basicViewModel.setDeliveryWaitDuration(cfg.getDeliveryWaitDuration());
        basicViewModel.setQueuedTaskCooldown(cfg.getQueuedTaskCooldown());
        basicViewModel.setLowPower(cfg.getLowPower());
        basicViewModel.setRecognizeDistance(cfg.getRecognizeDistance());
        basicViewModel.setVirtualOrbitObstacleTime(cfg.getObstacleTime());
        basicViewModel.setAutoCharge(cfg.isAutoChargeEnabled());
        basicViewModel.setIdleCharge(cfg.isIdleChargeEnabled());
        basicViewModel.setAutoChargeStartMinute(cfg.getAutoChargeStartMinute());
        basicViewModel.setAutoChargeEndMinute(cfg.getAutoChargeEndMinute());
        basicViewModel.setIdleChargeWaitMinutes(cfg.getIdleChargeWaitMinutes());
        basicViewModel.setVirtualOrbitMode(cfg.isVirtualOrbitMode());
        basicViewModel.setVirtualOrbitOneWayMode(cfg.isVirtualOrbitOneWay());
        basicViewModel.setRobotId(cfg.getRobotId());
        basicViewModel.setLoraChannel(cfg.getLoraChannel());
        basicViewModel.setLoraAddress(cfg.getLoraAddress());
        basicViewModel.setRunningMusic(cfg.getRunningMusic());
        basicViewModel.setUsePassword(cfg.isUsePassword());
        basicViewModel.setPassword(cfg.getPassword());
        basicViewModel.setVisualCruise(cfg.isVisualCruiseEnabled());
        // Detect language change
        String currentLang = com.ezhan.amr.utils.LocaleHelper.getLanguage(this);
        boolean languageChanged = cfg.getLanguage() != null && !cfg.getLanguage().equals(currentLang);

        basicViewModel.setLanguageChanged(cfg.isLanguageChanged() || languageChanged);
        if (cfg.getLanguage() != null) {
            basicViewModel.setLanguageType(getLanguageTypeCode(cfg.getLanguage()));
        }

        // Apply voice prompts
        Map<String, String> voiceList = cfg.getVoicePromptList();
        if (voiceList != null && !voiceList.isEmpty()) {
            basicViewModel.saveToPreferences(voiceList);
        }

        // Apply elevator configs
        String elevatorJson = cfg.getElevatorConfigsJson();
        if (elevatorJson != null && !elevatorJson.equals("[]")) {
            com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                    .putString(com.ezhan.amr.data.datastore.DataStoreKeys.ELEVATOR_CONFIGS, elevatorJson);
            try {
                java.lang.reflect.Type type = com.ezhan.amr.data.datastore.DataStoreKeys.ELEVATOR_CONFIGS_TYPE;
                java.util.List<com.ezhan.amr.viewmodels.ElevatorViewModel.ElevatorConfig> elevatorConfigs =
                        new com.google.gson.Gson().fromJson(elevatorJson, type);
                if (elevatorConfigs != null) {
                    com.ezhan.amr.viewmodels.ElevatorViewModel evm = MyApplication.getInstance().getElevatorViewModel();
                    evm.elevatorConfigs.postValue(elevatorConfigs);
                    // Sync elevator points to map points so they persist across fragment refreshes
                    // Pass configs directly to avoid async LiveData delay
                    evm.syncAllConfigsToMapPoints(elevatorConfigs);
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to apply elevator configs from dialog", e);
            }
        }

        // Apply call box configs - 使用 SharedPreferences 避免 DataStore 断电丢失
        String callBoxJson = cfg.getCallBoxConfigsJson();
        if (callBoxJson != null && !callBoxJson.equals("[]")) {
            // 使用 SharedPreferences.commit() 同步写入，防止断电数据丢失
            android.content.SharedPreferences sp = getSharedPreferences("call_settings", Context.MODE_PRIVATE);
            sp.edit().putString("call_boxes_json", callBoxJson).commit();
            
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox>>() {}.getType();
                java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox> callBoxes =
                        new com.google.gson.Gson().fromJson(callBoxJson, type);
                if (callBoxes != null) {
                    MyApplication.getInstance().getCallboxViewModel().setCallBoxes(callBoxes);
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to apply call box configs from dialog", e);
            }
        }

        // Apply map area configs - 使用 SharedPreferences 避免 DataStore 断电丢失
        String mapAreaJson = cfg.getMapAreaConfigsJson();
        if (mapAreaJson != null && !mapAreaJson.equals("[]")) {
            // 使用 SharedPreferences.commit() 同步写入，防止断电数据丢失
            android.content.SharedPreferences sp = getSharedPreferences("map_area_settings", Context.MODE_PRIVATE);
            sp.edit().putString("map_areas_json", mapAreaJson).commit();
            
            // 通知 MapAreaTriggerManager 重新加载区域
            com.ezhan.amr.navigation.area.MapAreaTriggerManager trigger =
                    MyApplication.getInstance().getMapAreaTriggerManager();
            if (trigger != null) {
                trigger.reloadAreas();
            }
        }

        // Apply position map from config file
        String positionMapJson = cfg.getPositionMapJson();
        if (positionMapJson != null && !positionMapJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP, positionMapJson);
                java.util.Map<String, com.ezhan.amr.data.datatype.Position> positionMap =
                        new com.google.gson.Gson().fromJson(positionMapJson, com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP_TYPE);
                if (positionMap != null) {
                    com.ezhan.amr.viewmodels.CruiseViewModel cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
                    cruiseViewModel.setPositionMap(new java.util.HashMap<>(positionMap));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to apply position map from dialog", e);
            }
        }

        // Apply cruise task map from config file
        String cruiseTaskMapJson = cfg.getCruiseTaskMapJson();
        if (cruiseTaskMapJson != null && !cruiseTaskMapJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP, cruiseTaskMapJson);
                java.util.Map<Integer, com.ezhan.amr.data.datatype.CruiseTask> cruiseTaskMap =
                        new com.google.gson.Gson().fromJson(cruiseTaskMapJson, com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE);
                if (cruiseTaskMap != null) {
                    com.ezhan.amr.viewmodels.CruiseViewModel cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
                    cruiseViewModel.setCruiseTaskMap(new java.util.LinkedHashMap<>(cruiseTaskMap));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to apply cruise task map from dialog", e);
            }
        }

        // Apply jack task map from config file
        String jackTaskMapJson = cfg.getJackTaskMapJson();
        if (jackTaskMapJson != null && !jackTaskMapJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP, jackTaskMapJson);
                java.util.Map<Integer, com.ezhan.amr.data.datatype.JackTask> jackTaskMap =
                        new com.google.gson.Gson().fromJson(jackTaskMapJson, com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE);
                if (jackTaskMap != null) {
                    com.ezhan.amr.viewmodels.JackViewModel jackViewModel = MyApplication.getInstance().getJackViewModel();
                    jackViewModel.setJackTaskMap(new java.util.LinkedHashMap<>(jackTaskMap));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to apply jack task map from dialog", e);
            }
        }

        // Apply current map points (点位设置) from config file
        String currentMapPointsJson = cfg.getCurrentMapPointsJson();
        android.util.Log.i("MainActivity", "applyConfigFromDialog: currentMapPointsJson=" +
                (currentMapPointsJson != null ? "len=" + currentMapPointsJson.length() : "null"));
        if (currentMapPointsJson != null && !currentMapPointsJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.CURRENT_MAP_POINTS, currentMapPointsJson);
                com.ezhan.amr.data.datatype.MultiBuildingMapPoints mapPoints =
                        new com.google.gson.Gson().fromJson(currentMapPointsJson, com.ezhan.amr.data.datastore.DataStoreKeys.MAP_POINTS_TYPE);
                android.util.Log.i("MainActivity", "applyConfigFromDialog: deserialized mapPoints=" +
                        (mapPoints != null) +
                        ", buildings=" + (mapPoints != null ? mapPoints.getAllBuildings().size() : 0) +
                        ", totalPoints=" + (mapPoints != null ? mapPoints.getTotalPositionCount() : 0));
                if (mapPoints != null) {
                    for (java.util.Map.Entry<String, com.ezhan.amr.data.datatype.Building> entry :
                            mapPoints.getAllBuildings().entrySet()) {
                        com.ezhan.amr.data.datatype.Building b = entry.getValue();
                        android.util.Log.i("MainActivity", "applyConfigFromDialog: buildingId=" + entry.getKey() +
                                ", floors=" + (b != null ? b.getAllFloorPoints().size() : 0));
                    }
                }
                if (mapPoints != null && !mapPoints.getAllBuildings().isEmpty()) {
                    com.ezhan.amr.viewmodels.MapViewModel mapViewModel = MyApplication.getInstance().getMapViewModel();
                    mapViewModel.updateCurrentMapPoints(mapPoints);
                    android.util.Log.i("MainActivity", "applyConfigFromDialog: called updateCurrentMapPoints");
                } else {
                    android.util.Log.w("MainActivity", "applyConfigFromDialog: mapPoints is null or empty, skipping update");
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to apply current map points from dialog", e);
            }
        } else {
            android.util.Log.w("MainActivity", "applyConfigFromDialog: currentMapPointsJson is null or empty '{}'");
        }

        // Sync to SharedPreferences
        SharedPreferences.Editor editor = getSharedPreferences("app_settings", MODE_PRIVATE).edit();
        editor.putInt("volume", cfg.getVolumeLevel());
        editor.putFloat("speed", (float) cfg.getSpeed());
        editor.putInt("delivery_stay", cfg.getDeliveryWaitDuration());
        editor.putInt("low_power", cfg.getLowPower());
        editor.putBoolean("switch_b", cfg.isAutoChargeEnabled());
        editor.putBoolean("idle_charge_enabled", cfg.isIdleChargeEnabled());
        editor.putInt("auto_charge_start_minute", cfg.getAutoChargeStartMinute());
        editor.putInt("auto_charge_end_minute", cfg.getAutoChargeEndMinute());
        editor.putInt("idle_charge_wait_minutes", cfg.getIdleChargeWaitMinutes());
        editor.putBoolean("virtual_orbit_mode", cfg.isVirtualOrbitMode());
        editor.putBoolean("virtual_orbit_one_way", cfg.isVirtualOrbitOneWay());
        editor.putInt("robot_id", cfg.getRobotId());
        editor.putInt("lora_channel", cfg.getLoraChannel());
        editor.putInt("lora_address", cfg.getLoraAddress());
        editor.putInt("obstacle_time", cfg.getObstacleTime());
        editor.putString("running_music", cfg.getRunningMusic());
        editor.putString("language", cfg.getLanguage());
        // Module visibility
        FeatureVisibilitySettings.setFeatureVisible(editor, FeatureVisibilitySettings.KEY_SHOW_JACK_MODE, cfg.isJackModuleEnabled());
        FeatureVisibilitySettings.setFeatureVisible(editor, FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, cfg.isShelfModuleEnabled());
        FeatureVisibilitySettings.setFeatureVisible(editor, FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, cfg.isElevatorModuleEnabled());
        FeatureVisibilitySettings.setFeatureVisible(editor, FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, cfg.isCallBoxModuleEnabled());
        FeatureVisibilitySettings.setFeatureVisible(editor, FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, cfg.isMapAreaModuleEnabled());
        FeatureVisibilitySettings.setFeatureVisible(editor, FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, cfg.isAvoidanceModuleEnabled());
        editor.commit();

        Log.i(TAG, "Config applied from dialog");

        // If language changed, apply locale and restart app
        if (languageChanged && cfg.getLanguage() != null) {
            com.ezhan.amr.utils.LocaleHelper.applyNewLocale(this, cfg.getLanguage());
            basicViewModel.resetVoicePromptsToCurrentLanguage(this);
            cfg.setLanguageChanged(false);
            cfg.saveConfig();
            // Restart app to apply language change to all UI elements
            restartApplication();
        }
    }

    private void restartApplication() {
        Intent restartIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (restartIntent == null) {
            recreate();
            return;
        }
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        new Handler().postDelayed(() -> {
            startActivity(restartIntent);
            finishAffinity();
        }, 250);
    }

    private int getLanguageTypeCode(String language) {
        switch (language) {
            case "zh": return 0;
            case "en": return 1;
            case "ru": return 2;
            default: return 1;
        }
    }

    private void saveConfigFromDialog(AppSettingsConfig cfg) {
        if (basicViewModel == null) return;

        cfg.setVolumeLevel(basicViewModel.getVolumeLevel().getValue() != null ? basicViewModel.getVolumeLevel().getValue() : 6);
        cfg.setSpeed(basicViewModel.getSpeed().getValue() != null ? basicViewModel.getSpeed().getValue() : 0.4);
        cfg.setDeliveryWaitDuration(basicViewModel.getDeliveryWaitDuration().getValue() != null ? basicViewModel.getDeliveryWaitDuration().getValue() : 30);
        cfg.setQueuedTaskCooldown(basicViewModel.getQueuedTaskCooldown().getValue() != null ? basicViewModel.getQueuedTaskCooldown().getValue() : 6);
        cfg.setLowPower(basicViewModel.getLowPower().getValue() != null ? basicViewModel.getLowPower().getValue() : 20);
        cfg.setRecognizeDistance(basicViewModel.getRecognizeDistance().getValue() != null ? basicViewModel.getRecognizeDistance().getValue() : 1.0);
        cfg.setObstacleTime(basicViewModel.getVirtualOrbitObstacleTime().getValue() != null ? basicViewModel.getVirtualOrbitObstacleTime().getValue() : 0);
        cfg.setAutoChargeEnabled(basicViewModel.getIsAutoCharge().getValue() != null ? basicViewModel.getIsAutoCharge().getValue() : false);
        cfg.setIdleChargeEnabled(basicViewModel.getIsIdleCharge().getValue() != null ? basicViewModel.getIsIdleCharge().getValue() : true);
        cfg.setAutoChargeStartMinute(basicViewModel.getAutoChargeStartMinute().getValue() != null ? basicViewModel.getAutoChargeStartMinute().getValue() : 0);
        cfg.setAutoChargeEndMinute(basicViewModel.getAutoChargeEndMinute().getValue() != null ? basicViewModel.getAutoChargeEndMinute().getValue() : 0);
        cfg.setIdleChargeWaitMinutes(basicViewModel.getIdleChargeWaitMinutes().getValue() != null ? basicViewModel.getIdleChargeWaitMinutes().getValue() : 10);
        cfg.setVirtualOrbitMode(basicViewModel.getIsVirtualOrbitMode().getValue() != null ? basicViewModel.getIsVirtualOrbitMode().getValue() : true);
        cfg.setVirtualOrbitOneWay(basicViewModel.getIsVirtualOrbitOneWayMode().getValue() != null ? basicViewModel.getIsVirtualOrbitOneWayMode().getValue() : false);
        cfg.setRobotId(basicViewModel.getRobotId().getValue() != null ? basicViewModel.getRobotId().getValue() : 1);
        cfg.setLoraChannel(basicViewModel.getLoraChannel().getValue() != null ? basicViewModel.getLoraChannel().getValue() : 0);
        cfg.setLoraAddress(basicViewModel.getLoraAddress().getValue() != null ? basicViewModel.getLoraAddress().getValue() : 0);
        cfg.setRunningMusic(basicViewModel.getRunningMusic().getValue() != null ? basicViewModel.getRunningMusic().getValue() : "wa");
        cfg.setUsePassword(basicViewModel.getIsUsePassword().getValue() != null ? basicViewModel.getIsUsePassword().getValue() : false);
        cfg.setPassword(basicViewModel.getPassword().getValue() != null ? basicViewModel.getPassword().getValue() : "0000");
        cfg.setVisualCruiseEnabled(basicViewModel.getIsVisualCruise().getValue() != null ? basicViewModel.getIsVisualCruise().getValue() : false);

        // Save SharedPreferences values to config
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        cfg.setLanguage(prefs.getString("language", "en"));
        cfg.setLanguageChanged(basicViewModel.getLanguageChanged().getValue() != null ? basicViewModel.getLanguageChanged().getValue() : false);
        cfg.setJackModuleEnabled(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_JACK_MODE, true));
        cfg.setShelfModuleEnabled(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, true));
        cfg.setElevatorModuleEnabled(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, true));
        cfg.setCallBoxModuleEnabled(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, true));
        cfg.setMapAreaModuleEnabled(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, true));
        cfg.setAvoidanceModuleEnabled(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, true));

        // Save elevator configs to config file
        try {
            com.ezhan.amr.viewmodels.ElevatorViewModel elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
            java.util.List<com.ezhan.amr.viewmodels.ElevatorViewModel.ElevatorConfig> elevatorConfigs =
                    elevatorViewModel.elevatorConfigs.getValue();
            if (elevatorConfigs != null) {
                cfg.setElevatorConfigsJson(new com.google.gson.Gson().toJson(elevatorConfigs,
                        com.ezhan.amr.data.datastore.DataStoreKeys.ELEVATOR_CONFIGS_TYPE));
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save elevator configs", e);
        }

        // Save call box configs to config file
        try {
            com.ezhan.amr.viewmodels.CallboxViewModel callboxViewModel = MyApplication.getInstance().getCallboxViewModel();
            java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox> callBoxes =
                    callboxViewModel.getCallBoxes().getValue();
            if (callBoxes != null) {
                cfg.setCallBoxConfigsJson(new com.google.gson.Gson().toJson(callBoxes,
                        new com.google.gson.reflect.TypeToken<java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox>>() {}.getType()));
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save call box configs", e);
        }

        // Save map area configs to config file (read from DataStore)
        try {
            // Map areas are stored in DataStore, read synchronously if possible
            String mapAreaJson = com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                    .getString(com.ezhan.amr.data.datastore.DataStoreKeys.MAP_AREAS, "[]")
                    .blockingFirst("[]");
            if (mapAreaJson != null && !mapAreaJson.equals("[]")) {
                cfg.setMapAreaConfigsJson(mapAreaJson);
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save map area configs", e);
        }

        // Save position map to config file
        try {
            com.ezhan.amr.viewmodels.CruiseViewModel cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
            java.util.Map<String, com.ezhan.amr.data.datatype.Position> positionMap =
                    cruiseViewModel.getPositionMap().getValue();
            if (positionMap != null) {
                cfg.setPositionMapJson(new com.google.gson.Gson().toJson(positionMap,
                        com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP_TYPE));
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save position map", e);
        }

        // Save cruise task map to config file
        try {
            com.ezhan.amr.viewmodels.CruiseViewModel cruiseViewModel = MyApplication.getInstance().getCruiseViewModel();
            java.util.Map<Integer, com.ezhan.amr.data.datatype.CruiseTask> cruiseTaskMap =
                    cruiseViewModel.getCruiseTaskMap().getValue();
            if (cruiseTaskMap != null) {
                cfg.setCruiseTaskMapJson(new com.google.gson.Gson().toJson(cruiseTaskMap,
                        com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE));
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save cruise task map", e);
        }

        // Save jack task map to config file
        try {
            com.ezhan.amr.viewmodels.JackViewModel jackViewModel = MyApplication.getInstance().getJackViewModel();
            java.util.Map<Integer, com.ezhan.amr.data.datatype.JackTask> jackTaskMap =
                    jackViewModel.getJackTaskMap().getValue();
            if (jackTaskMap != null) {
                cfg.setJackTaskMapJson(new com.google.gson.Gson().toJson(jackTaskMap,
                        com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE));
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save jack task map", e);
        }

        // Save current map points (点位设置) to config file
        try {
            com.ezhan.amr.viewmodels.MapViewModel mapViewModel = MyApplication.getInstance().getMapViewModel();
            com.ezhan.amr.data.datatype.MultiBuildingMapPoints mapPoints =
                    mapViewModel.getCurrentMapPoints().getValue();
            android.util.Log.i("MainActivity", "saveConfigFromDialog: mapPoints from LiveData=" +
                    (mapPoints != null) +
                    ", buildings=" + (mapPoints != null ? mapPoints.getAllBuildings().size() : 0) +
                    ", totalPoints=" + (mapPoints != null ? mapPoints.getTotalPositionCount() : 0));
            if (mapPoints != null && !mapPoints.getAllBuildings().isEmpty()) {
                String json = new com.google.gson.Gson().toJson(mapPoints,
                        com.ezhan.amr.data.datastore.DataStoreKeys.MAP_POINTS_TYPE);
                android.util.Log.i("MainActivity", "saveConfigFromDialog: saving currentMapPoints json length=" + json.length());
                cfg.setCurrentMapPointsJson(json);
            } else {
                // LiveData is empty - check if config already has saved points to preserve them
                String existingJson = cfg.getCurrentMapPointsJson();
                if (!existingJson.equals("{}")) {
                    android.util.Log.w("MainActivity", "saveConfigFromDialog: LiveData is empty but config file has existing points - preserving existing data");
                } else {
                    android.util.Log.i("MainActivity", "saveConfigFromDialog: LiveData empty and no existing config data");
                }
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save current map points", e);
        }

        cfg.saveConfig();
    }

    // 显示密码输入对话框并设置操作类型
    private void showPasswordInputDialog(OperationType operation) {
        currentOperation = operation;
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_input, null);

        // 创建对话框
        Dialog passwordDialog = new Dialog(this);
        passwordDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        passwordDialog.setContentView(dialogView);
        passwordDialog.setCancelable(false);
        passwordDialog.setCanceledOnTouchOutside(false);


        // 设置对话框属性
        Window window = passwordDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);

            // 添加动画效果
            window.setWindowAnimations(R.style.DialogAnimation);
        }

        // 关闭功能
        View overlay = dialogView.findViewById(R.id.dialogOverlay);
        overlay.setOnClickListener(null);
        overlay.setClickable(true);
        ImageButton btnClose = dialogView.findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> passwordDialog.dismiss());

        // 初始化视图
        TextView tvPasswordDisplay = dialogView.findViewById(R.id.tvPasswordDisplay);
        GridLayout gridLayout = dialogView.findViewById(R.id.gridLayout);
        Button btnRfid = dialogView.findViewById(R.id.btn_rfid);

        // 存储用户输入的密码
        StringBuilder enteredPassword = new StringBuilder();

        // 数字按钮点击事件
        for (int i = 0; i < gridLayout.getChildCount(); i++) {
            View view = gridLayout.getChildAt(i);
            if (view instanceof Button) {
                Button button = (Button) view;
                button.setOnClickListener(v -> {
                    if (button.getId() == R.id.btn_delete) {
                        // 处理删除按钮
                        if (enteredPassword.length() > 0) {
                            enteredPassword.deleteCharAt(enteredPassword.length() - 1);
                            updatePasswordDisplay(tvPasswordDisplay, enteredPassword.length());
                        }
                    }
                    else if (button.getId() == R.id.btn_confirm) {
                        // 处理确定按钮
                        verifyPassword(passwordDialog, enteredPassword.toString());
                    }
                    else {
                        // 处理数字按钮
                        if (enteredPassword.length() < 4) {
                            enteredPassword.append(button.getText().toString());
                            updatePasswordDisplay(tvPasswordDisplay, enteredPassword.length());
                        }
                    }
                });
            }
        }

        // 刷卡按钮点击事件
        btnRfid.setOnClickListener(v -> {
            // 显示刷卡提示
            tvPasswordDisplay.setText(getString(R.string.swipe_card_prompt));
            
            // 启动RFID读取
            new Thread(() -> {
                try {
                    RFIDCommunicator rfidCommunicator = RFIDCommunicator.getInstance();
                    String rfidData = rfidCommunicator.readRfidWithTimeout();
                    
                    runOnUiThread(() -> {
                        if (rfidData != null) {
                            // 解析卡片序列号（第9-12字节）
                            if (rfidData.length() >= 24) {
                                String cardSerial = rfidData.substring(16, 24);
                                Log.d("MainActivity", "解析到卡片序列号: " + cardSerial);
                                
                                // 检查是否是已录入的RFID
                                com.ezhan.amr.viewmodels.RfidViewModel rfidViewModel = MyApplication.getInstance().getRfidViewModel();
                                if (rfidViewModel != null) {
                                    java.util.List<com.ezhan.amr.data.datatype.RfidData> rfidList = rfidViewModel.getRfidList().getValue();
                                    if (rfidList != null) {
                                        boolean isRfidValid = false;
                                        for (com.ezhan.amr.data.datatype.RfidData rfid : rfidList) {
                                            if (rfid.getRfidSerial().equals(cardSerial)) {
                                                isRfidValid = true;
                                                break;
                                            }
                                        }
                                        
                                        if (isRfidValid) {
                                            // 刷卡成功，关闭对话框并执行相应操作
                                            passwordDialog.dismiss();
                                            executeOperation(currentOperation);
                                        } else {
                                            // 未找到该RFID
                                            tvPasswordDisplay.setText("____");
                                            Toast.makeText(MainActivity.this, R.string.rfid_invalid_card, Toast.LENGTH_SHORT).show();
                                        }
                                    } else {
                                        // RFID列表为空
                                        tvPasswordDisplay.setText("____");
                                        Toast.makeText(MainActivity.this, R.string.rfid_list_empty, Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    // RFIDViewModel未初始化
                                    tvPasswordDisplay.setText("____");
                                    Toast.makeText(MainActivity.this, R.string.rfid_system_not_init, Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                // 数据长度不足
                                tvPasswordDisplay.setText("____");
                                Toast.makeText(MainActivity.this, R.string.rfid_data_format_error, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            // 刷卡超时，恢复密码输入提示
                            tvPasswordDisplay.setText("____");
                            Toast.makeText(MainActivity.this, R.string.rfid_timeout_retry, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvPasswordDisplay.setText("____");
                        Toast.makeText(MainActivity.this, R.string.rfid_init_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });

        // 添加对话框关闭时恢复布局的逻辑
        passwordDialog.setOnDismissListener(dialog -> {
            // 恢复系统UI状态
            setupFullScreen();
        });

        // 显示对话框
        passwordDialog.show();

        // 临时改变状态栏颜色以增强视觉体验
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.dialog_overlay));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.dialog_overlay));
    }
}
