package com.ezhan.amr.ui;

import static com.ezhan.amr.data.datatype.MultiBuildingMapPoints.generateMapName;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.JackOperation;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.navigation.BatteryLevelManager;
import com.ezhan.amr.navigation.BatteryState;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.ChargingManager;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.navigation.task.CommandUtils;
import com.ezhan.amr.navigation.task.NavigationState;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.ezhan.amr.utils.GifTypeConstants;
import com.ezhan.amr.utils.MusicPlayer;
import com.ezhan.amr.utils.RawMusicUtils;
import com.ezhan.amr.utils.TextToSpeechHelper;
import com.ezhan.amr.utils.TaskDebug2;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseNavigationActivity extends BaseActivity implements
        GeneralNavigationHandler.NavigationUICallback,
        MusicPlayer.MusicPlayerListener,
        TextToSpeechHelper.OnTTSEventListener {

    protected static final String TAG = "BaseNavigationActivity";
    protected View rootView;
    protected TextToSpeechHelper ttsHelper;
    protected MusicPlayer musicPlayer;
    protected ImageView gifView;
    protected TextView tvNavigationStatus;
    protected Map<String, String> voiceList = new HashMap<>();
    protected Dialog pauseDialog;
    private Dialog continueDialog;
    /** 终点完成对话框（onShowCompleteDialog 使用） */
    private Dialog completeDialog;
    /** completeDialog 的确认按钮（用于远程放行触发 performClick） */
    private MaterialButton btnConfirm;
    private CountDownTimer timer;
    private MaterialButton btnContinue;
    protected ImageView ivNoDataBackground;
    protected ImageView ivNoDataBackground1;
    protected int countDownTime = 10;
    protected  SharedViewModel sharedViewModel;
    protected MapViewModel mapViewModel;
    protected ElevatorViewModel elevatorViewModel;
    protected BasicViewModel basicViewModel;
    protected GeneralNavigationHandler navigationHandler;
    protected Position currentPosition;

    private StatusWebSocketClient.StatusListener statusListener;
    private boolean isActivityDestroyed = false;
    private RequestManager glideRequestManager;
    private Position currentTargetPosition;
    private Runnable currentContinueAction;
    private int remainingCountdownSeconds = 0;
    private boolean isCountdownRunning = false;
    private boolean isCountdownPausedByNavigationStop = false;
    // 在类变量中添加
    private boolean isPaused = false;
    private BroadcastReceiver controlReceiver;
    protected AgvStatusResponse latestStatusResponse;
    private ChargingManager chargingManager;
    private String currentGifType = "";
    private String currentGifDescription = "";
    private Queue<String> ttsQueue = new LinkedList<>();
    private boolean isTTSSpeaking = false;
    private boolean shouldPlayMusicDuringCountdown = false;
    private boolean isInCountdownPeriod = false;
    private boolean wasMusicPlayingBeforeDialog = false;
    private boolean isCountdownFinished = false;
    NavigationStateDebugger debugger = NavigationStateDebugger.getInstance();
    private long lastStatusUpdateTime = 0;
    private String runningMusic = "wa";
    protected int musicResId = 0;

    // --------------------------------------------------------------------------------------------
    // extend base activity
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        glideRequestManager = Glide.with(this);
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        basicViewModel = MyApplication.getInstance().getBasicViewModel();

        initializeNavigationHandler();
        initializeChargingManager();
        initializeView();
        initializeTTSHelper();
        initializeMusicPlayer();
        startStatusMonitoring();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (navigationHandler != null) {
            navigationHandler.setUICallback(this);
            // Ensure we're still registered as a listener
            Log.d(TAG, "✅ Re-registered as internal status listener in onResume");
        }
        if (sharedViewModel != null) {
            sharedViewModel.registerStatusListener(statusListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Dismiss charging dialog when activity goes to background
        if (chargingManager != null) {
            ChargingManager.suppressAnimationForActiveCharging(latestStatusResponse);
            chargingManager.dismissChargingAnimation();
        }
        if (sharedViewModel != null) {
            sharedViewModel.unregisterStatusListener(statusListener);
        }
    }

    @Override
    protected void onDestroy() {
        // Mark activity as destroyed
        isActivityDestroyed = true;

        // Cancel any timers
        cancelTimer();
        resetCountdownState(); // Add this line

        // Unregister listener before destroying the activity
        if (sharedViewModel != null) {
            sharedViewModel.unregisterStatusListener(statusListener);
        }

        // Clean up charging manager
        if (chargingManager != null) {
            chargingManager.cleanup();
            chargingManager = null;
        }

        // Clean up navigation first
        if (navigationHandler != null) {
            navigationHandler.cleanup(this instanceof MainActivity);
        }

        // Dismiss dialogs
        if (continueDialog != null && continueDialog.isShowing()) {
            continueDialog.dismiss();
        }

        if (controlReceiver != null) {
            unregisterReceiver(controlReceiver);
        }


        // 5. Clear Glide with Application context
        if (gifView != null) {
            try {
                Glide.with(getApplicationContext()).clear(gifView);
                gifView.setImageDrawable(null);
                gifView = null;
            } catch (Exception e) {
                Log.e(TAG, "Error clearing Glide", e);
            }
        }

        // Call super last
        super.onDestroy();
    }

    private void startStatusMonitoring() {
        statusListener = new StatusWebSocketClient.StatusListener() {
            @Override
            public void onStatusUpdate(AgvStatusResponse response) {
                if (!isActivityValid()) {
                    Log.w(TAG, "Activity destroyed, skipping delayed task");
                    return;
                }
                if (response != null && response.data != null) {
                    if (chargingManager != null) {
                        chargingManager.updateStatus(response);
                    } else {
                        // Log or handle the case when chargingManager is null
                        Log.w(TAG, "chargingManager is null, skipping status update");
                    }

                    long now = System.currentTimeMillis();
                    long timeSinceLastUpdate = lastStatusUpdateTime > 0 ? now - lastStatusUpdateTime : -1;

                    // Log status update using debugger
                    if (debugger != null) {
                        debugger.logStatusUpdate(
                                "BaseNavigationActivity",
                                true,
                                timeSinceLastUpdate
                        );

                        // Safely convert values that might be different types
                        String goalFinishStr = String.valueOf(response.data.goalFinish);

                        // Log detailed status data
                        String statusDetails = String.format(
                                "STATUS DATA:\n" +
                                        "  ├─ Position: (%s, %s), Theta: %s\n" +
                                        "  ├─ Battery: %s%%\n" +
                                        "  ├─ Goal Finish: %s\n" +
                                        "  ├─ Error Code: %s\n" +
                                        "  ├─ Pose Probability: %s\n" +
                                        "  └─ Map Name: %s",
                                String.valueOf(response.data.pos.x),
                                String.valueOf(response.data.pos.y),
                                String.valueOf(response.data.pos.theta),
                                String.valueOf(response.data.power),
                                String.valueOf(response.data.goalFinish),
                                response.data.errorCode != null ? response.data.errorCode : "none",
                                String.valueOf(response.data.poseProbability),
                                response.data.pos.mapName != null ? response.data.pos.mapName : "unknown"
                        );
                        debugger.log(statusDetails);

                        // Log warning if status data seems stale or invalid
                        if (response.data.poseProbability < 0.5) {
                            debugger.logWarning(
                                    "Low pose confidence",
                                    String.format("Pose probability: %.2f", response.data.poseProbability)
                            );
                        }

                        if (response.data.power < 20.0) {
                            debugger.logWarning(
                                    "Low battery",
                                    String.format("Battery level: %.1f%%", response.data.power)
                            );
                        }
                    }

                    latestStatusResponse = response;
                    lastStatusUpdateTime = now;
                    currentPosition = createPositionFromStatus(response);
                }

                if (navigationHandler != null && !isActivityDestroyed) {

                    boolean isConnected = navigationHandler.isWebSocketConnected();
                    Log.d("ConnectionMonitor", "WebSocket connected: " + isConnected);

                    if (!isConnected) {
                        Log.w("ConnectionMonitor", "WebSocket disconnected, attempting to reconnect");
                    }
                }

                runningMusic = basicViewModel.getRunningMusic().getValue();
                if (runningMusic == null) runningMusic = "ezhan1";
                musicResId = getResources().getIdentifier(runningMusic, "raw", getPackageName());
                if (musicResId == 0) musicResId = R.raw.ezhan1;
            }
        };
        sharedViewModel.registerStatusListener(statusListener);
        Log.d(TAG, "Registering listener with SharedViewModel");
    }

    private Position createPositionFromStatus(AgvStatusResponse status) {
        Position currentPosition = new Position(1, "current",
                status.data.pos.x,
                status.data.pos.y,
                status.data.pos.theta);
        int currentFloor = mapViewModel.getCurrentFloor();
        currentPosition.setFloor(String.valueOf(currentFloor));
        String currentMap = mapViewModel.getCurrentMap();
        String currentBuilding = mapViewModel.getCurrentBuilding();
        String fullMapName = generateMapName(currentMap, currentBuilding, currentFloor);
        currentPosition.setMapName(fullMapName);
        return currentPosition;
    }
    // --------------------------------------------------------------------------------------------
    // implement navigation UI callback

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    public void onTaskRecordAddition(TaskRecord record) {
        // Update task record in database
        MyApplication.getInstance().getTaskViewModel().addTaskRecord(record);
    }

    @Override
    public void onTaskRecordUpdate(TaskRecord record) {
        // Update task record in database
        MyApplication.getInstance().getTaskViewModel().updateTaskRecord(record);
    }

    // 防抖相关变量
    private String lastVoicePromptKey = null;
    private long lastVoicePromptTime = 0L;
    private static final long VOICE_PROMPT_DEBOUNCE_MS = 1000L; // 1秒防抖
    private long lastButtonClickTime = 0L;
    private static final long BUTTON_CLICK_DEBOUNCE_MS = 1500L; // 按钮点击1.5秒防抖

    /**
     * 检查按钮点击是否在防抖时间内
     * @return true=应该忽略本次点击，false=可以执行
     */
    protected boolean isButtonClickDebounced() {
        long now = System.currentTimeMillis();
        if (now - lastButtonClickTime < BUTTON_CLICK_DEBOUNCE_MS) {
            Log.d(TAG, "Button click debounced");
            return true;
        }
        lastButtonClickTime = now;
        return false;
    }

    @Override
    public void onVoicePrompt(String key) {
        runOnUiThread(() -> {
            // Add this check to filter out empty or null keys
            if (key == null || key.trim().isEmpty()) {
                return;
            }

            // 防抖：1秒内相同key不重复播报
            long now = System.currentTimeMillis();
            if (key.equals(lastVoicePromptKey) && now - lastVoicePromptTime < VOICE_PROMPT_DEBOUNCE_MS) {
                Log.d(TAG, "Voice prompt debounced: " + key);
                return;
            }
            lastVoicePromptKey = key;
            lastVoicePromptTime = now;

            // 1. Get the voice text from your resources
            String voiceText = getVoiceTextFromKey(key);

            // 2. Play the voice prompt using TTS
            if (voiceText != null && !voiceText.isEmpty()) {
                if (ttsHelper != null) {
                    //ttsHelper.speak(voiceText);
                    speakWithQueue(voiceText);
                } else {
                    Log.w(TAG, "TTS helper not initialized when voice prompt requested");
                }
            }

            Log.d(TAG, "Playing voice prompt: " + key);
        });
    }

    @Override
    public void onArrivalVoicePrompt(Position target) {
        runOnUiThread(() -> {
            String voiceText = buildArrivalVoiceText(target);
            if (voiceText != null && !voiceText.isEmpty()) {
                if (ttsHelper != null) {
                    speakWithQueue(voiceText);
                } else {
                    Log.w(TAG, "TTS helper not initialized when arrival voice requested");
                }
            }
            Log.d(TAG, "Playing arrival voice prompt: " + voiceText);
        });
    }

    private String buildArrivalVoiceText(Position target) {
        String baseText = getVoiceTextFromKey(VoiceKeyConstants.ARRIVAL);
        if (baseText == null || baseText.trim().isEmpty()) {
            baseText = getString(R.string.default_arrival_text);
        }

        String stationName;
        if (target != null && target.getTaskType() == 4) {
            // 回桩充电任务：站点名固定播报英文 charge
            stationName = "charge";
        } else {
            stationName = target != null && target.getName() != null
                    ? target.getName().trim()
                    : "";
        }
        if (stationName.isEmpty()) {
            return baseText;
        }

        if (baseText.contains("%s")) {
            try {
                return String.format(baseText, stationName);
            } catch (Exception e) {
                Log.w(TAG, "Arrival voice format failed, appending station name", e);
            }
        }

        if (baseText.contains(stationName)) {
            return baseText;
        }

        return baseText + "：" + stationName;
    }

    @Override
    public void onGlideResume() {
        Glide.with(this).resumeRequests();
    }

    @Override
    public void onPlaySound(int soundResource) {
        if (musicPlayer != null) {
            musicPlayer.start(soundResource);
        }
    }

    @Override
    public void onPlayRunningMusicIfNeeded(int soundResource) {
        playRunningMusicIfNeeded(soundResource);
    }

    protected void playRunningMusicIfNeeded(int soundResource) {
        if (musicPlayer == null) {
            return;
        }
        if (musicPlayer.isPlayingResource(soundResource)) {
            return;
        }
        if (musicPlayer.isCurrentResource(soundResource)) {
            musicPlayer.resume();
        } else {
            musicPlayer.start(soundResource);
        }
    }

    @Override
    public void onStopSound() {
        if (musicPlayer != null) {
            musicPlayer.stop();
        }
    }

    @Override
    public void onPauseSound() {
        if (musicPlayer != null) {
            musicPlayer.pause();
        }
    }

    @Override
    public void onGifPlayback(boolean shouldPlay, String gifType, String description) {
        TaskDebug1.log(String.format("[UI] onGifPlayback shouldPlay=%b gifType=%s activity=%s desc=%s",
                shouldPlay, gifType, getClass().getSimpleName(),
                description != null ? description.substring(0, Math.min(40, description.length())) : ""));
        runOnUiThread(() -> {
            if (isActivityDestroyed) return;

            if (gifView == null) {
                gifView = findViewById(R.id.gifView);
                if (gifView == null) {
                    Log.e(TAG, "gifView not found in layout");
                    return;
                }
            }
            if (tvNavigationStatus == null) {
                tvNavigationStatus = findViewById(R.id.tvNavigationStatus);
            }

            // 获取返回按钮引用
            ImageButton btnBack = null;
            TextView titleText = null;

            try {
                btnBack = findViewById(R.id.btnBack);
            } catch (Exception e) {
                Log.d(TAG, "Back button not found in current layout");
            }

            try {
                titleText = findViewById(R.id.titleText);
            } catch (Exception e) {
                Log.d(TAG, "Title text not found in current layout");
            }

            try {
                if (shouldPlay) {
                    // 隐藏返回按钮
                    if (btnBack != null) {
                        btnBack.setVisibility(View.GONE);
                    }
                    // 隐藏标题文本（如果存在）
                    if (titleText != null) {
                        titleText.setVisibility(View.GONE);
                    }

                    // Store current GIF type and description
                    currentGifType = gifType != null ? gifType : "";
                    currentGifDescription = description != null ? description : "";

                    // Update status text (skip while countdown dialog is showing)
                    if (tvNavigationStatus != null
                            && (continueDialog == null || !continueDialog.isShowing())) {
                        String localizedDescription = getLocalizedDescription(gifType, description);
                        tvNavigationStatus.setText(appendJackLoopProgress(localizedDescription));
                        tvNavigationStatus.setVisibility(View.VISIBLE);
                        tvNavigationStatus.setAlpha(1f);
                    }

                    // Load appropriate GIF based on type
                    loadGifForType(currentGifType, currentGifDescription);

                } else {
                    // Stop GIF playback
                    currentGifType = "";
                    currentGifDescription = "";

                    // 显示返回按钮
                    if (btnBack != null) {
                        btnBack.setVisibility(View.VISIBLE);
                    }
                    // 显示标题文本
                    if (titleText != null) {
                        titleText.setVisibility(View.VISIBLE);
                    }

                    forceClearGifPlayback();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in GIF playback", e);
            }
        });
    }

    private void forceClearGifPlayback() {
        if (gifView != null) {
            gifView.animate().cancel();
            glideRequestManager.clear(gifView);
            gifView.setImageDrawable(null);
            gifView.setVisibility(View.GONE);
            gifView.setAlpha(1f);
        }

        if (tvNavigationStatus != null) {
            tvNavigationStatus.animate().cancel();
            tvNavigationStatus.setText("");
            tvNavigationStatus.setVisibility(View.GONE);
            tvNavigationStatus.setAlpha(1f);
        }
    }

    @Override
    public void onShowToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onShowToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onNoShowContinueDialog(boolean isLastStation, Position target,
                                       Runnable onContinue, Runnable onCancel) {
        // Log entry
        if (debugger != null) {
            debugger.log("┌─────────────────────────────────────────────────────────────────────────────┐");
            debugger.log("│                    onNoShowContinueDialog CALLED                           │");
            debugger.log("├─────────────────────────────────────────────────────────────────────────────┤");
            debugger.log(String.format("│ isLastStation: %b", isLastStation));
            debugger.log(String.format("│ target: %s", target != null ? target.getName() : "null"));
            debugger.log(String.format("│ target taskType: %d", target != null ? target.getTaskType() : -1));
            debugger.log(String.format("│ onContinue: %s", onContinue != null ? "present" : "null"));
            debugger.log(String.format("│ onCancel: %s", onCancel != null ? "present" : "null"));
        }

        if (target == null) {
            if (debugger != null) {
                debugger.logWarning("onNoShowContinueDialog", "target is null, continuing immediately");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            if (onContinue != null) {
                onContinue.run();
            }
            resetCountdownState();
            return;
        }
        int taskType = target.getTaskType();
        String stationName = getSafePositionName(target);

        // Clean up any existing dialog first
        if (continueDialog != null && continueDialog.isShowing()) {
            if (debugger != null) {
                debugger.log("Cleaning up existing continueDialog before creating new one");
            }
            continueDialog.dismiss();
        }
        cancelTimer(); // Cancel any existing timer

        // Store for later resumption
        this.currentTargetPosition = target;
        this.currentContinueAction = onContinue;

        // Task type 0 or 6: continue immediately (非任务途径点和离开充电桩)
        if (taskType == 0 || taskType == 6) {
            TaskDebug1.log(String.format("[SKIP1ST] immediateContinue station=%s taskType=%d type=%d stayDuration=%d",
                    stationName, taskType, target.getType(), target.getStayDuration()));
            if (debugger != null) {
                debugger.log(String.format("TaskType %d is immediate continue (no dialog)", taskType));
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            if (onContinue != null) {
                onContinue.run();
            }
            resetCountdownState();
            return;
        }

        // Task type 2 (巡航) and 3 (顶升): show countdown with a release dialog
        if (taskType == 2 || taskType == 3) {
            if (debugger != null) {
                debugger.log(String.format("TaskType %d -> showing countdown with release dialog", taskType));
            }

            // Ensure tvNavigationStatus is available
            if (tvNavigationStatus == null) {
                tvNavigationStatus = findViewById(R.id.tvNavigationStatus);
                if (tvNavigationStatus == null) {
                    if (debugger != null) {
                        debugger.logError("onNoShowContinueDialog", "tvNavigationStatus not found! Falling back to immediate continue");
                        debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                    }
                    Log.e(TAG, "tvNavigationStatus not found!");
                    // Fallback: continue immediately if TextView not found
                    if (onContinue != null) {
                        onContinue.run();
                    }
                    resetCountdownState();
                    return;
                }
            }

            // Check if navigation is paused - if so, don't start countdown
            if (navigationHandler != null && navigationHandler.isNavigationStopped()) {
                isCountdownPausedByNavigationStop = true;
                if (debugger != null) {
                    debugger.logWarning("onNoShowContinueDialog",
                            String.format("Navigation is paused (stopped=%b), delaying countdown start for station: %s",
                                    navigationHandler.isNavigationStopped(), stationName));
                    debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                }
                Log.d(TAG, "Navigation paused, delaying countdown start");
                // Store the target and action for later when navigation resumes
                return;
            }

            // Hide background status text; countdown is shown only in the dialog.
            tvNavigationStatus.setText("");
            tvNavigationStatus.setVisibility(View.GONE);

            // Set countdown period state
            isInCountdownPeriod = true;
            if (navigationHandler != null) {
                navigationHandler.setInStationStay(true);
            }

            // Save music state if playing
            if (musicPlayer != null && musicPlayer.isPlaying()) {
                wasMusicPlayingBeforeDialog = true;
                if (debugger != null) {
                    debugger.log("Music was playing before countdown, saved state");
                }
            }

            // Pause music during countdown
            controlMusicDuringCountdown(false);

            int startSeconds;
            if (taskType == 2) {
                // Cruise task: use default stay duration
                startSeconds = getDefaultStayDuration();
                if (debugger != null) {
                    debugger.log(String.format("Cruise task: default stay duration = %d seconds", startSeconds));
                }
            } else if (taskType == 3) {
                // Jack task: use each station's own duration
                startSeconds = target.getStayDuration();
                if (debugger != null) {
                    debugger.log(String.format("Jack task: duration from target stayDuration = %d seconds", startSeconds));
                }
            } else {
                startSeconds = getDurationFromCurrentOperation(target);
                if (debugger != null) {
                    debugger.log(String.format("Other task: duration from operation = %d seconds", startSeconds));
                }
            }

            // Use remaining time if we're resuming, otherwise start from configured value
            final int actualStartSeconds = remainingCountdownSeconds > 0 ? remainingCountdownSeconds : startSeconds;
            TaskDebug1.log(String.format("[JACK] countdown START station=%s taskType=%d seconds=%d isInStationStay=true taskRunning=%b",
                    stationName, taskType, actualStartSeconds,
                    navigationHandler != null && navigationHandler.isTaskRunning()));
            if (debugger != null) {
                debugger.log(String.format("Countdown start seconds: %d (remaining=%d, configured=%d)",
                        actualStartSeconds, remainingCountdownSeconds, startSeconds));
            }

            if (actualStartSeconds <= 0) {
                TaskDebug1.log(String.format("[SKIP1ST] zeroCountdown station=%s taskType=%d stayDuration=%d -> immediate next",
                        stationName, taskType, startSeconds));
                if (debugger != null) {
                    debugger.log("actualStartSeconds <= 0, skipping countdown and continuing immediately");
                    debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                }
                tvNavigationStatus.setText("");
                tvNavigationStatus.setVisibility(View.GONE);
                isInCountdownPeriod = false;
                controlMusicDuringCountdown(true);
                if (onContinue != null) {
                    onContinue.run();
                }
                resetCountdownState();
                return;
            }

            continueDialog = new Dialog(this);
            continueDialog.setContentView(R.layout.dialog_continue_to_next);
            continueDialog.setCancelable(false);

            final TextView countdownMessage = continueDialog.findViewById(R.id.tvMessage);
            final MaterialButton countdownRelease = continueDialog.findViewById(R.id.btnContinue);
            MaterialButton countdownCancel = continueDialog.findViewById(R.id.btnCancel);

            if (countdownMessage == null || countdownRelease == null || countdownCancel == null) {
                Log.e(TAG, "Countdown dialog components missing");
                continueDialog.dismiss();
                continueDialog = null;
                tvNavigationStatus.setText("");
                tvNavigationStatus.setVisibility(View.GONE);
                controlMusicDuringCountdown(true);
                if (onContinue != null) {
                    onContinue.run();
                }
                resetCountdownState();
                return;
            }

            countdownCancel.setVisibility(View.GONE);
            countdownRelease.setText(R.string.dialog_button_continue);
            String initialMessage = appendJackLoopProgress(
                    getString(R.string.staying_format, stationName, actualStartSeconds));
            countdownMessage.setText(initialMessage);
            continueDialog.show();

            AtomicInteger countdownSeconds = new AtomicInteger(actualStartSeconds);

            isCountdownRunning = true;
            final boolean[] isCountdownFinished = {false};
            this.isCountdownFinished = false;

            if (debugger != null) {
                debugger.log(String.format("Starting countdown dialog for station '%s' with %d seconds",
                        stationName, actualStartSeconds));
            }

            timer = new CountDownTimer(actualStartSeconds * 1000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (!isActivityValid()) {
                        if (debugger != null) {
                            debugger.logWarning("onNoShowContinueDialog", "Activity invalid during countdown tick, cancelling timer");
                        }
                        cancel();
                        return;
                    }
                    // Check if navigation got paused during countdown
                    if (navigationHandler != null && navigationHandler.isNavigationStopped()) {
                        if (debugger != null) {
                            debugger.logWarning("onNoShowContinueDialog",
                                    String.format("Navigation paused during countdown tick (stopped=%b), pausing countdown at %d seconds",
                                            navigationHandler.isNavigationStopped(), countdownSeconds.get()));
                        }
                        Log.d(TAG, "Countdown paused due to navigation pause");
                        int currentValue = countdownSeconds.get();
                        remainingCountdownSeconds = currentValue > 0 ? currentValue : 1;
                        isCountdownPausedByNavigationStop = true;
                        cancel(); // Stop the timer
                        return;
                    }

                    int secondsLeft = countdownSeconds.getAndDecrement();
                    remainingCountdownSeconds = secondsLeft; // Store remaining time

                    // Show countdown message with Jack loop progress if applicable
                    String message = appendJackLoopProgress(getString(R.string.staying_format, stationName, secondsLeft));
                    countdownMessage.setText(message);

                    if (debugger != null && secondsLeft % 5 == 0 && secondsLeft > 0) {
                        debugger.log(String.format("Countdown tick: %d seconds remaining for station '%s'", secondsLeft, stationName));
                    }
                }

                @Override
                public void onFinish() {
                    if (isCountdownFinished[0]) {
                        if (debugger != null) {
                            debugger.log("Countdown finished but already marked as finished - skipping");
                        }
                        return;
                    }
                    isCountdownFinished[0] = true;
                    BaseNavigationActivity.this.isCountdownFinished = true;

                    if (debugger != null) {
                        debugger.log(String.format("Countdown FINISHED for station '%s'", stationName));
                    }

                    if (!isActivityValid()) {
                        if (debugger != null) {
                            debugger.logWarning("onNoShowContinueDialog", "Activity invalid at countdown finish, cancelling");
                        }
                        cancel();
                        return;
                    }

                    if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                        TaskDebug1.log(String.format("[JACK] countdown FINISH ABORT task not running station=%s navHandlerNull=%b",
                                stationName, navigationHandler == null));
                        Log.d(TAG, "Ignoring countdown finish because the task is no longer running");
                        // Still need to dismiss the countdown dialog and clean up UI
                        if (tvNavigationStatus != null) {
                            tvNavigationStatus.setText("");
                            tvNavigationStatus.setVisibility(View.GONE);
                        }
                        if (continueDialog != null && continueDialog.isShowing()) {
                            continueDialog.dismiss();
                        }
                        isInCountdownPeriod = false;
                        resetCountdownState();
                        return;
                    }

                    // Check if navigation got paused during countdown
                    if (navigationHandler != null && navigationHandler.isNavigationStopped()) {
                        if (debugger != null) {
                            debugger.logWarning("onNoShowContinueDialog",
                                    String.format("Countdown finished but navigation is paused (stopped=%b), delaying continuation",
                                            navigationHandler.isNavigationStopped()));
                        }
                        Log.d(TAG, "Countdown finished but navigation is paused, delaying continuation");
                        remainingCountdownSeconds = 1;
                        isCountdownPausedByNavigationStop = true;
                        return;
                    }

                    // Clear the navigation status text
                    tvNavigationStatus.setText("");
                    tvNavigationStatus.setVisibility(View.GONE);

                    // Dismiss continue dialog if it exists (for any residual dialog)
                    if (continueDialog != null && continueDialog.isShowing()) {
                        continueDialog.dismiss();
                    }

                    // Reset countdown period state
                    isInCountdownPeriod = false;

                    // Resume music if it was playing before
                    if (wasMusicPlayingBeforeDialog && musicPlayer != null) {
                        musicPlayer.resume();
                        wasMusicPlayingBeforeDialog = false;
                    }

                    // Play completion sound
                    if (musicPlayer != null) {
                        playRunningMusicIfNeeded(musicResId);
                    }

                    // If navigation was paused, auto-resume
                    if (isPaused) {
                        if (debugger != null) {
                            debugger.log("Navigation was paused, auto-resuming after countdown finish");
                        }
                        if (pauseDialog != null && pauseDialog.isShowing()) {
                            pauseDialog.dismiss();
                        }
                        navigationHandler.resumeNavigation();
                        isPaused = false;
                        navigationHandler.setNavigateState(NavigationState.IDLE);
                    }

                    if (onContinue != null) {
                        TaskDebug1.log(String.format("[JACK] countdown FINISH -> onContinue station=%s", stationName));
                        if (debugger != null) {
                            debugger.logStationDeparture(-1, stationName, System.currentTimeMillis());
                        }
                        onContinue.run();
                    }
                    resetCountdownState();

                    if (debugger != null) {
                        debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                    }
                }
            }.start();

            countdownRelease.setOnClickListener(v -> {
                if (isCountdownFinished[0] ||
                        navigationHandler == null ||
                        !navigationHandler.isTaskRunning() ||
                        navigationHandler.isNavigationStopped()) {
                    return;
                }
                CountDownTimer activeTimer = timer;
                if (activeTimer != null) {
                    activeTimer.cancel();
                    activeTimer.onFinish();
                }
            });

            if (debugger != null) {
                debugger.recordVerificationStart(-1, stationName, System.currentTimeMillis());
            }
            return;
        }

        // For any other task type, continue immediately as fallback
        if (debugger != null) {
            debugger.log(String.format("TaskType %d not handled, continuing immediately as fallback", taskType));
            debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
        }
        if (onContinue != null) {
            onContinue.run();
        }
        resetCountdownState();
    }

    @Override
    public void onShowContinueDialog(boolean isLastStation, Position target,
                                     Runnable onContinue, Runnable onCancel) {
        // Log entry
        if (debugger != null) {
            debugger.log("┌─────────────────────────────────────────────────────────────────────────────┐");
            debugger.log("│                    onShowContinueDialog CALLED                             │");
            debugger.log("├─────────────────────────────────────────────────────────────────────────────┤");
            debugger.log(String.format("│ isLastStation: %b", isLastStation));
            debugger.log(String.format("│ target: %s", target != null ? target.getName() : "null"));
            debugger.log(String.format("│ target ID: %d", target != null ? target.getId() : -1));
            debugger.log(String.format("│ onContinue: %s", onContinue != null ? "present" : "null"));
            debugger.log(String.format("│ onCancel: %s", onCancel != null ? "present" : "null"));
        }

        // Always store target and action for emergency stop recovery
        this.currentTargetPosition = target;
        this.currentContinueAction = onContinue;

        // Check if navigation is paused - if so, don't show dialog yet
        if (navigationHandler != null && navigationHandler.isNavigationStopped()) {
            if (debugger != null) {
                debugger.logWarning("onShowContinueDialog",
                        String.format("Navigation paused (stopped=%b), delaying continue dialog for station: %s",
                                navigationHandler.isNavigationStopped(), target != null ? target.getName() : "null"));
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.d(TAG, "Navigation paused, delaying continue dialog");
            return;
        }

        // ===== 驻车点（type==12）不显示弹窗，直接继续 =====
        final boolean isParkPoint = target != null && target.getType() == 12;
        if (isParkPoint) {
            if (debugger != null) {
                debugger.log(String.format("Continue dialog skipped for park point at '%s' (type=12)",
                        getSafePositionName(target)));
                debugger.log("   → Executing onContinue callback directly without showing dialog");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            if (onContinue != null) {
                onContinue.run();
            }
            return;
        }

        // ===== FIX: Store music state before pausing =====
        if (musicPlayer != null && musicPlayer.isPlaying()) {
            wasMusicPlayingBeforeDialog = true;
            if (debugger != null) {
                debugger.log("Music was playing before dialog, pausing and saving state");
            }
            onPauseSound();
        } else {
            wasMusicPlayingBeforeDialog = false;
            if (debugger != null) {
                debugger.log("Music was not playing before dialog");
            }
        }

        // Clear previous dialog if exists
        if (continueDialog != null && continueDialog.isShowing()) {
            if (debugger != null) {
                debugger.log("Cleaning up existing continueDialog before creating new one");
            }
            continueDialog.dismiss();
        }

        continueDialog = new Dialog(this);
        continueDialog.setContentView(R.layout.dialog_continue_to_next);

        // Initialize UI components
        btnContinue = continueDialog.findViewById(R.id.btnContinue);
        MaterialButton btnCancel = continueDialog.findViewById(R.id.btnCancel);
        TextView tvMessage = continueDialog.findViewById(R.id.tvMessage);

        // Validate all views
        if (btnContinue == null || btnCancel == null || tvMessage == null) {
            if (debugger != null) {
                debugger.logError("onShowContinueDialog",
                        String.format("Critical dialog components missing! btnContinue=%s, btnCancel=%s, tvMessage=%s",
                                btnContinue != null ? "ok" : "null",
                                btnCancel != null ? "ok" : "null",
                                tvMessage != null ? "ok" : "null"));
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.e(TAG, "Critical dialog components missing!");
            return;
        }

        String stationName = getSafePositionName(target);

        // Set initial content
        tvMessage.setText(getString(R.string.arrived_at_station_continue, stationName));
        btnContinue.setText(getString(R.string.dialog_button_continue));

        if (debugger != null) {
            debugger.log(String.format("Dialog created for station '%s', isLastStation=%b", stationName, isLastStation));
            debugger.recordDialogShown(-1, stationName, System.currentTimeMillis());
        }

        // Only setup timer for intermediate waypoints
        if (!isLastStation) {
            if (debugger != null) {
                debugger.log("Not last station, setting up countdown timer");
            }
            setupCountdownTimer(onContinue);
        } else {
            if (debugger != null) {
                debugger.log("Last station, no countdown timer (waiting for user button press)");
            }
        }

        // Set button actions
        btnContinue.setOnClickListener(v -> {
            if (debugger != null) {
                debugger.log(String.format("User clicked CONTINUE button for station '%s' (isLastStation=%b)",
                        stationName, isLastStation));
            }
            cancelTimer();
            isCountdownFinished = true;  // Mark as finished to prevent double execution
            continueDialog.dismiss();

            // Resume music
            if (musicPlayer != null) {
                musicPlayer.resume();
            }

            if (debugger != null) {
                debugger.logStationDeparture(-1, stationName, System.currentTimeMillis());
            }
            onContinue.run();
        });

        btnCancel.setOnClickListener(v -> {
            if (debugger != null) {
                debugger.logWarning("onShowContinueDialog",
                        String.format("User clicked CANCEL button for station '%s'", stationName));
            }
            cancelTimer();
            continueDialog.dismiss();
            if (onCancel != null) onCancel.run();
            if (debugger != null) {
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
        });

        continueDialog.show();
    }

    @Override
    public void onShowCompleteDialog(boolean isLastStation, Position target,
                                     Runnable onContinue, Runnable onCancel) {
        TaskDebug1.log(String.format("[UI] onShowCompleteDialog activity=%s target=%s type=%d taskType=%d isLast=%b navRunning=%b gifPlaying=%b",
                getClass().getSimpleName(),
                target != null ? target.getName() : "null",
                target != null ? target.getType() : -1,
                target != null ? target.getTaskType() : -1,
                isLastStation,
                navigationHandler != null && navigationHandler.isTaskRunning(),
                navigationHandler != null && navigationHandler.isGifPlaying()));
        // Log entry
        if (debugger != null) {
            debugger.log("┌─────────────────────────────────────────────────────────────────────────────┐");
            debugger.log("│                    onShowCompleteDialog CALLED                             │");
            debugger.log("├─────────────────────────────────────────────────────────────────────────────┤");
            debugger.log(String.format("│ isLastStation: %b", isLastStation));
            debugger.log(String.format("│ target: %s", target != null ? target.getName() : "null"));
            debugger.log(String.format("│ target ID: %d", target != null ? target.getId() : -1));
            debugger.log(String.format("│ target taskType: %d", target != null ? target.getTaskType() : -1));
            debugger.log(String.format("│ onContinue: %s", onContinue != null ? "present" : "null"));
            debugger.log(String.format("│ onCancel: %s", onCancel != null ? "present" : "null"));
        }

        // ===== NEW LOGIC: Check if we should suppress the completion dialog for intermediate loops =====
        boolean suppressDialog = false;
        String suppressReason = "";

        if (target != null && navigationHandler != null) {
            int taskType = target.getTaskType();

            // Cruise task (taskType == 2)
            if (taskType == 2) {
                int totalLoops = navigationHandler.getCurrentCruiseTotalLoops();
                int currentLoopIndex = navigationHandler.getCurrentCruiseLoopIndex();

                if (debugger != null) {
                    debugger.log(String.format("Cruise task: totalLoops=%d, currentLoopIndex=%d",
                            totalLoops, currentLoopIndex));
                }

                // If infinite loop (totalLoops == -1) - never show completion dialog
                if (totalLoops == -1) {
                    suppressDialog = true;
                    suppressReason = "Cruise task with infinite loop (-1) - never show completion dialog";
                }
                // If not the final loop (currentLoopIndex < totalLoops)
                else if (currentLoopIndex < totalLoops) {
                    suppressDialog = true;
                    suppressReason = String.format("Cruise task intermediate loop %d of %d - suppressing completion dialog",
                            currentLoopIndex, totalLoops);
                }
            }

            // Jack task (taskType == 3)
            else if (taskType == 3) {
                int totalLoops = navigationHandler.getCurrentJackTotalLoops();
                int currentLoopIndex = navigationHandler.getCurrentJackLoopIndex();

                if (debugger != null) {
                    debugger.log(String.format("Jack task: totalLoops=%d, currentLoopIndex=%d",
                            totalLoops, currentLoopIndex));
                }

                // If infinite loop (totalLoops == -1) - never show completion dialog
                if (totalLoops == -1) {
                    suppressDialog = true;
                    suppressReason = "Jack task with infinite loop (-1) - never show completion dialog";
                }
                // If not the final loop (currentLoopIndex < totalLoops)
                else if (currentLoopIndex < totalLoops) {
                    suppressDialog = true;
                    suppressReason = String.format("Jack task intermediate loop %d of %d - suppressing completion dialog",
                            currentLoopIndex, totalLoops);
                }
            }
        }

        // If we should suppress the dialog, just execute the onContinue callback directly
        if (suppressDialog) {
            if (debugger != null) {
                debugger.log(String.format("⚠️ Suppressing completion dialog: %s", suppressReason));
                debugger.log("   → Executing onContinue callback directly without showing dialog");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            // Do NOT stop music for intermediate loops
            if (onContinue != null) {
                onContinue.run();
            }
            return;
        }

        // ===== 驻车点（type==12）显示到达待命点完成动画，3秒自动消失 =====
        final boolean isParkPoint = target != null && target.getType() == 12;
        if (isParkPoint) {
            TaskDebug1.log(String.format("[PARK] onShowCompleteDialog show arrival dialog station=%s type=12",
                    getSafePositionName(target)));
            if (debugger != null) {
                debugger.log(String.format("Park point arrival dialog for '%s' (type=12)",
                        getSafePositionName(target)));
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }

            // 清理已有弹窗
            if (completeDialog != null && completeDialog.isShowing()) {
                completeDialog.dismiss();
            }

            completeDialog = new Dialog(this);
            completeDialog.setContentView(R.layout.dialog_task_complete);
            completeDialog.setCancelable(false);

            // 设置弹窗大小
            Window window = completeDialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
                DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
                layoutParams.width = (int) (displayMetrics.widthPixels * 0.45);
                layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
                layoutParams.gravity = Gravity.CENTER;
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setAttributes(layoutParams);
            }

            TextView tvMessage = completeDialog.findViewById(R.id.tvMessage);
            MaterialButton btnParkConfirm = completeDialog.findViewById(R.id.btnConfirm);

            if (btnParkConfirm == null || tvMessage == null) {
                Log.e(TAG, "Park complete dialog components missing!");
                resetSelectedStations();
                if (onContinue != null) onContinue.run();
                onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");
                return;
            }

            tvMessage.setText(getString(R.string.arrived_at_park_point));
            btnParkConfirm.setText(getString(R.string.end_task_with_time, 3));

            // 确保 GIF 动画在弹窗期间保持显示，与弹窗同时消失
            onGifPlayback(true, GifTypeConstants.NAVIGATING_PARK,
                    getString(R.string.arrived_at_park_point));

            final boolean[] isDialogFinished = {false};
            final int parkDismissSeconds = 3;

            CountDownTimer parkDismissTimer = new CountDownTimer(parkDismissSeconds * 1000L, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (!isActivityValid()) { cancel(); return; }
                    int secondsLeft = (int) (millisUntilFinished / 1000);
                    if (secondsLeft == 0) secondsLeft = 1;
                    if (btnParkConfirm != null) {
                        btnParkConfirm.setText(getString(R.string.end_task_with_time, secondsLeft));
                    }
                }

                @Override
                public void onFinish() {
                    if (isDialogFinished[0]) return;
                    isDialogFinished[0] = true;
                    if (!isActivityValid()) return;
                    if (completeDialog != null && completeDialog.isShowing()) {
                        completeDialog.dismiss();
                    }
                    onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");
                    resetSelectedStations();
                    if (musicPlayer != null) musicPlayer.stop();
                    if (onContinue != null) onContinue.run();
                }
            }.start();

            // 用户点击确认按钮提前关闭
            btnParkConfirm.setOnClickListener(v -> {
                if (isDialogFinished[0]) return;
                isDialogFinished[0] = true;
                parkDismissTimer.cancel();
                if (completeDialog != null && completeDialog.isShowing()) {
                    completeDialog.dismiss();
                }
                onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");
                resetSelectedStations();
                if (musicPlayer != null) musicPlayer.stop();
                if (onContinue != null) onContinue.run();
            });

            // 弹窗关闭时仅做定时器清理
            completeDialog.setOnDismissListener(dialog -> {
                parkDismissTimer.cancel();
            });

            completeDialog.show();
            return;
        }

        // ===== Original dialog logic for final loops or non-cruise/jack tasks =====

        // Clear previous dialog if exists
        if (completeDialog != null && completeDialog.isShowing()) {
            if (debugger != null) {
                debugger.log("Cleaning up existing completeDialog before creating new one");
            }
            completeDialog.dismiss();
        }

        completeDialog = new Dialog(this);
        completeDialog.setContentView(R.layout.dialog_task_complete);
        completeDialog.setCancelable(false);

        //Initialize Dialog size
        Window window = completeDialog.getWindow();
        if (window != null){
            // window size
            WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();

            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            int dialogWidth = (int) (displayMetrics.widthPixels * 0.45);
            int dialogHeight = WindowManager.LayoutParams.WRAP_CONTENT;

            layoutParams.width = dialogWidth;
            layoutParams.height = dialogHeight;

            // window location
            layoutParams.gravity = Gravity.CENTER;

            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            window.setAttributes(layoutParams);

            if (debugger != null) {
                debugger.log(String.format("Dialog configured: width=%dpx (45%% of screen)", dialogWidth));
            }
        }

        // Initialize UI components
        btnConfirm = completeDialog.findViewById(R.id.btnConfirm);
        TextView tvMessage = completeDialog.findViewById(R.id.tvMessage);

        // Validate views
        if (btnConfirm == null || tvMessage == null) {
            if (debugger != null) {
                debugger.logError("onShowCompleteDialog",
                        String.format("Complete dialog components missing! btnConfirm=%s, tvMessage=%s",
                                btnConfirm != null ? "ok" : "null",
                                tvMessage != null ? "ok" : "null"));
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.e(TAG, "Complete dialog components missing!");
            return;
        }

        String stationName = getSafePositionName(target);

        // Set initial content
        tvMessage.setText(getString(R.string.arrived_final_station, stationName));
        btnConfirm.setText(R.string.ok);

        if (debugger != null) {
            debugger.log(String.format("Complete dialog created for final station '%s'", stationName));
            debugger.recordDialogShown(-1, stationName, System.currentTimeMillis());
        }

        // Set up auto-dismiss timer
        // 背负模式（taskType==1）最后一个点也进行停留倒计时，与中间途径点行为一致
        final boolean isDeliveryStay = target != null && target.getTaskType() == 1;
        final int stayDurationSeconds = isDeliveryStay ? getDeliveryWaitDuration() : 2;
        final boolean[] isDialogFinished = {false};

        // 背负模式停留倒计时需要设置站点停留标志
        if (isDeliveryStay && navigationHandler != null) {
            navigationHandler.setInStationStay(true);
            if (debugger != null) {
                debugger.log("Backpack mode (taskType==1) stay countdown enabled, duration="
                        + stayDurationSeconds + "s, isInStationStay=true");
            }
            // 显示带倒计时的初始消息
            tvMessage.setText(getString(R.string.arrived_final_station, stationName));
            btnConfirm.setText(getString(R.string.end_task_with_time, stayDurationSeconds));
        }

        CountDownTimer autoDismissTimer = new CountDownTimer(stayDurationSeconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (!isActivityValid()) {
                    if (debugger != null) {
                        debugger.logWarning("onShowCompleteDialog", "Activity invalid during auto-dismiss tick, cancelling");
                    }
                    cancel();
                    return;
                }
                int secondsLeft = (int) (millisUntilFinished / 1000);
                if (secondsLeft == 0) secondsLeft = 1;
                // 背负模式实时更新倒计时按钮文本
                if (isDeliveryStay && btnConfirm != null) {
                    btnConfirm.setText(getString(R.string.end_task_with_time, secondsLeft));
                }
                if (debugger != null && secondsLeft == 1) {
                    debugger.log("Auto-dismiss in 1 second...");
                }
            }

            @Override
            public void onFinish() {
                if (isDialogFinished[0]) {
                    if (debugger != null) {
                        debugger.log("Auto-dismiss finished but dialog already handled - skipping");
                    }
                    return;
                }
                isDialogFinished[0] = true;
                // 清除站点停留标志
                if (navigationHandler != null) {
                    navigationHandler.setInStationStay(false);
                }

                if (!isActivityValid()) {
                    if (debugger != null) {
                        debugger.logWarning("onShowCompleteDialog", "Activity invalid at auto-dismiss finish, cancelling");
                    }
                    cancel();
                    return;
                }
                if (completeDialog != null && completeDialog.isShowing()) {
                    if (debugger != null) {
                        debugger.log("Auto-dismiss timer finished, closing complete dialog");
                    }
                    completeDialog.dismiss();
                    //reset selected button
                    resetSelectedStations();
                    if (onContinue != null) {
                        if (debugger != null) {
                            debugger.logStationDeparture(-1, stationName, System.currentTimeMillis());
                            debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
                        }
                        onContinue.run();
                    }
                }
            }
        }.start();

        if (debugger != null) {
            debugger.log("Auto-dismiss timer started (" + stayDurationSeconds + " seconds, isDeliveryStay=" + isDeliveryStay + ")");
        }

        // Manual confirmation button
        btnConfirm.setOnClickListener(v -> {
            if (isDialogFinished[0]) {
                if (debugger != null) {
                    debugger.log("Confirm button clicked but dialog already finished - ignoring");
                }
                return;
            }
            isDialogFinished[0] = true;
            // 清除站点停留标志
            if (navigationHandler != null) {
                navigationHandler.setInStationStay(false);
            }

            if (debugger != null) {
                debugger.log(String.format("User clicked CONFIRM button for final station '%s'", stationName));
            }
            autoDismissTimer.cancel();
            completeDialog.dismiss();
            //reset selected button
            resetSelectedStations();
            if (onContinue != null) {
                if (debugger != null) {
                    debugger.logStationDeparture(-1, stationName, System.currentTimeMillis());
                }
                onContinue.run();
            }
            if (debugger != null) {
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
        });

        // Cleanup on dismiss - Only stop music for FINAL loops
        completeDialog.setOnDismissListener(dialog -> {
            if (debugger != null) {
                debugger.log("Complete dialog dismissed");
            }
            autoDismissTimer.cancel();
            // 清除站点停留标志
            if (navigationHandler != null) {
                navigationHandler.setInStationStay(false);
            }
            // Only stop music and GIF for final loops (not for suppressed intermediate loops)
            if (musicPlayer != null) {
                musicPlayer.stop();
            }
            onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");
            //reset selected button
            resetSelectedStations();
        });

        completeDialog.show();
    }
    // --------------------------------------------------------------------------------------------
    // initialize navigation handler and others

    protected void initializeNavigationHandler() {
        sharedViewModel.initNavigationHandler();
        navigationHandler = sharedViewModel.getNavigationHandler();
        if (navigationHandler != null) {
            navigationHandler.setUICallback(this);
            Log.d(TAG, "✅ Added BaseNavigationActivity as internal status listener");
        } else {
            Log.e(TAG, "❌ Navigation handler is null");
        }
        setupControlReceiver();
    }

    // Add this getter for child activities
    protected GeneralNavigationHandler getNavigationHandler() {
        if (navigationHandler == null) {
            initializeNavigationHandler();
        }
        return navigationHandler;
    }

    private void initializeView() {
        // 1. Set orientation (e.g., portrait-only)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        EdgeToEdge.enable(this);
        setupFullScreen();

        rootView = findViewById(R.id.main);
        if (rootView != null) {
            rootView.setOnTouchListener((v, event) -> {
                hideKeyboardAndClearFocus();
                return false;
            });
        }
    }

    private void initializeMusicPlayer() {
        musicPlayer = MusicPlayer.getInstance(this);
    }

    private void initializeTTSHelper() {
        ttsHelper = new TextToSpeechHelper(this, new TextToSpeechHelper.OnTTSEventListener() {
            @Override
            public void onTTSInitialized(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS failed: " + status);
                }
            }

            @Override
            public void onStart(String utteranceId) {
                // Required but empty implementation
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(() -> {
                    isTTSSpeaking = false;
                    processTTSQueue();
                });
            }

            @Override
            public void onDone(String utteranceId) {
                // Required but empty implementation
                runOnUiThread(() -> {
                    isTTSSpeaking = false;
                    processTTSQueue();
                });
            }
        });
    }

    private void initializeChargingManager() {
        chargingManager = new ChargingManager(this, navigationHandler);
        chargingManager.setListener(new ChargingManager.ChargingStatusListener() {
            @Override
            public void onChargingStatusChanged(boolean isCharging) {
                Log.d(TAG, "Charging status changed: " + isCharging);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                return BaseNavigationActivity.super.dispatchTouchEvent(event);
            }

            @Override
            public boolean isActivityValid() {
                // Check if activity is still valid
                return !isActivityDestroyed && !isFinishing();
            }
        });
    }

    // Add this method to check activity state
    protected boolean isActivityValid() {
        return !isActivityDestroyed && !isFinishing() && !isDestroyed();
    }

    /**
     * 检查电池电量是否允许接受新任务。
     * 电量低于最低工作电量时返回 false 并弹 Toast 提示；充电中但电量充足时不拦截。
     * @return true 表示可以接受新任务，false 表示被拦截
     */
    protected boolean checkBatteryCanAcceptTask() {
        if (!BatteryLevelManager.getInstance().canAcceptNewTask()) {
            BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
            int level = BatteryLevelManager.getInstance().getCurrentBatteryLevel();
            Log.w(TAG, "Task rejected: battery too low (" + level + "%, state=" + state + ")");
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    getString(R.string.base_battery_too_low_for_task, level), android.widget.Toast.LENGTH_SHORT).show());
            return false;
        }
        return true;
    }

    private void setupControlReceiver() {
        controlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case BroadcastHelper.ACTION_PAUSE:
                            handlePauseFromWeb();
                            break;
                        case BroadcastHelper.ACTION_RESUME:
                            handleResumeFromWeb();
                            break;
                        case BroadcastHelper.ACTION_CANCEL:
                            handleCancelFromWeb();
                            break;
                        case BroadcastHelper.ACTION_RELEASE:
                            handleReleaseFromWeb();
                            break;
                        case BroadcastHelper.ACTION_DELIVERY_TASK_NAME:
                            handleDeliveryTaskFromWeb(intent);
                            break;
                        case BroadcastHelper.ACTION_JACK_TASK_NAME:
                            handleJackTaskFromWeb(intent);
                            break;
                        case BroadcastHelper.ACTION_CRUISE_TASK_NAME:
                            handleCruiseTaskFromWeb(intent);
                            break;
                        case BroadcastHelper.ACTION_MAIN_TASK_NAME:
                            handleMainTaskFromWeb(intent);
                            break;
                        case BroadcastHelper.ACTION_DELIVERY_TASK_OBJECT:
                            handleDeliveryTaskFromRcs(intent);
                            break;
                        case BroadcastHelper.ACTION_JACK_TASK_OBJECT:
                            handleJackTaskFromRcs(intent);
                            break;
                        case BroadcastHelper.ACTION_CRUISE_TASK_OBJECT:
                            handleCruiseTaskFromRcs(intent);
                            break;
                        case BroadcastHelper.ACTION_MAIN_TASK_OBJECT:
                            handleMainTaskFromRcs(intent);
                            break;
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BroadcastHelper.ACTION_PAUSE);
        filter.addAction(BroadcastHelper.ACTION_RESUME);
        filter.addAction(BroadcastHelper.ACTION_CANCEL);
        filter.addAction(BroadcastHelper.ACTION_RELEASE);
        filter.addAction(BroadcastHelper.ACTION_DELIVERY_TASK_NAME);
        filter.addAction(BroadcastHelper.ACTION_JACK_TASK_NAME);
        filter.addAction(BroadcastHelper.ACTION_CRUISE_TASK_NAME);
        filter.addAction(BroadcastHelper.ACTION_MAIN_TASK_NAME);
        filter.addAction(BroadcastHelper.ACTION_DELIVERY_TASK_OBJECT);
        filter.addAction(BroadcastHelper.ACTION_JACK_TASK_OBJECT);
        filter.addAction(BroadcastHelper.ACTION_CRUISE_TASK_OBJECT);
        filter.addAction(BroadcastHelper.ACTION_MAIN_TASK_OBJECT);

        // Use RECEIVER_NOT_EXPORTED for internal app communication only
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter);
        }
        Log.d(TAG, "Control receiver registered with appropriate export flags");
    }

    private void handlePauseFromWeb() {
        if (navigationHandler != null && navigationHandler.isTaskRunning()) {
            runOnUiThread(() -> {
                pauseAndShowDialog();
                onShowToast(getString(R.string.pause_command_received));
            });
        }
    }

    private void handleResumeFromWeb() {
        runOnUiThread(() -> {
            if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                Log.d(TAG, "Ignoring resume command because no task is running");
                return;
            }

            MaterialButton resumeButton = getPauseDialogResumeButton();
            if (resumeButton != null && resumeButton.isEnabled()) {
                resumeButton.setSoundEffectsEnabled(false);
                resumeButton.performClick();
                // Re-enable if needed (optional)
                resumeButton.setSoundEffectsEnabled(true);
                onShowToast(getString(R.string.resume_command_received));
            } else if (navigationHandler != null && navigationHandler.isNavigationPaused()) {
                navigationHandler.resumeNavigation();
                onShowToast(getString(R.string.navigation_resumed));
            }
        });
    }

    private void handleCancelFromWeb() {
        runOnUiThread(() -> {
            cancelTimer();
            resetCountdownState();

            if (continueDialog != null && continueDialog.isShowing()) {
                continueDialog.dismiss();
            }
            if (tvNavigationStatus != null) {
                tvNavigationStatus.setText("");
                tvNavigationStatus.setVisibility(View.GONE);
            }

            boolean wasTaskRunning = navigationHandler != null && navigationHandler.isTaskRunning();

            // Always cancel chassis navigation, including orphaned tasks after reboot.
            if (navigationHandler != null) {
                navigationHandler.cancelNavigation(true);
                if (wasTaskRunning) {
                    navigationHandler.cleanup();
                }
                onShowToast(getString(R.string.task_cancelled_toast));
            }

            // The task may already be idle before the cancel broadcast is handled.
            // Always clear page-level pause state so it cannot leak into the next task.
            if (musicPlayer != null) {
                musicPlayer.stop();
            }
            isPaused = false;
            wasMusicPlayingBeforeDialog = false;
            if (pauseDialog != null && pauseDialog.isShowing()) {
                pauseDialog.dismiss();
            }
        });
    }

    /**
     * 处理远程放行命令：提前结束当前站点停留倒计时。
     * 三级放行策略：
     *   1) continueDialog 显示中 → 点击 btnContinue 放行（途径点停留）
     *   2) completeDialog 显示中 → 点击 btnConfirm 结束（终点停留）
     *   3) 对话框未显示但倒计时进行中（isCountdownRunning=true）→ 直接执行 currentContinueAction
     *      覆盖 onNoShowContinueDialog 中对话框被 dismiss 但 timer 仍在运行的场景
     */
    private void handleReleaseFromWeb() {
        runOnUiThread(() -> {
            // 1) 途径点停留对话框
            if (continueDialog != null && continueDialog.isShowing()
                    && btnContinue != null && btnContinue.isEnabled()) {
                Log.i(TAG, "Release command from web: clicking continue button (intermediate station)");
                btnContinue.setSoundEffectsEnabled(false);
                btnContinue.performClick();
                btnContinue.setSoundEffectsEnabled(true);
                onShowToast(getString(R.string.resume_command_received));
                return;
            }
            // 2) 终点完成对话框（背负任务 taskType=1 的终点停留）
            if (completeDialog != null && completeDialog.isShowing()
                    && btnConfirm != null && btnConfirm.isEnabled()) {
                Log.i(TAG, "Release command from web: clicking confirm button (final station)");
                btnConfirm.setSoundEffectsEnabled(false);
                btnConfirm.performClick();
                btnConfirm.setSoundEffectsEnabled(true);
                onShowToast(getString(R.string.resume_command_received));
                return;
            }
            // 3) 倒计时进行中但对话框未显示（如 onNoShowContinueDialog 的对话框被 dismiss）
            if (isCountdownRunning && currentContinueAction != null) {
                Log.i(TAG, "Release command from web: direct release (countdown running, dialog not showing)");
                isCountdownFinished = true;
                cancelTimer();
                if (continueDialog != null && continueDialog.isShowing()) {
                    continueDialog.dismiss();
                }
                if (tvNavigationStatus != null) {
                    tvNavigationStatus.setText("");
                    tvNavigationStatus.setVisibility(View.GONE);
                }
                isInCountdownPeriod = false;
                if (wasMusicPlayingBeforeDialog && musicPlayer != null) {
                    musicPlayer.resume();
                    wasMusicPlayingBeforeDialog = false;
                }
                Runnable toRun = currentContinueAction;
                resetCountdownState();
                toRun.run();
                onShowToast(getString(R.string.resume_command_received));
                return;
            }
            Log.d(TAG, "Release command ignored: no station stay dialog showing");
        });
    }

    // In BaseNavigationActivity.java
    private void handleDeliveryTaskFromWeb(Intent intent) {
        int taskId = intent.getIntExtra(BroadcastHelper.EXTRA_TASK_ID, -1);
        String taskName = intent.getStringExtra(BroadcastHelper.EXTRA_TASK_NAME);

        if (taskId != -1 && this instanceof DeliveryActivity) {
            Log.d(TAG, "Received delivery task in DeliveryActivity: " + taskName);
            ((DeliveryActivity) this).executeDeliveryByTaskId(taskId);
        }
    }

    private void handleJackTaskFromWeb(Intent intent) {
        int taskId = intent.getIntExtra(BroadcastHelper.EXTRA_TASK_ID, -1);
        String taskName = intent.getStringExtra(BroadcastHelper.EXTRA_TASK_NAME);
        String externalTaskId = intent.getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_ID);
        String externalTaskName = intent.getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_NAME);

        if (taskId != -1 && this instanceof JackActivity) {
            Log.d(TAG, "Received jack task in JackActivity: " + taskName);
            // Call JackActivity's method to handle the task
            ((JackActivity) this).executeJackByTaskId(taskId, externalTaskId, externalTaskName);
        }
    }

    private void handleCruiseTaskFromWeb(Intent intent) {
        int taskId = intent.getIntExtra(BroadcastHelper.EXTRA_TASK_ID, -1);
        String taskName = intent.getStringExtra(BroadcastHelper.EXTRA_TASK_NAME);
        String externalTaskId = intent.getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_ID);
        String externalTaskName = intent.getStringExtra(BroadcastHelper.EXTRA_EXTERNAL_TASK_NAME);

        if (taskId != -1 && this instanceof CruiseActivity) {
            Log.d(TAG, "Received cruise task in CruiseActivity: " + taskName);
            // Call CruiseActivity's method to handle the task
            ((CruiseActivity) this).executeCruiseByTaskId(taskId, externalTaskId, externalTaskName);
        }
    }

    private void handleMainTaskFromWeb(Intent intent) {
        int taskId = intent.getIntExtra(BroadcastHelper.EXTRA_TASK_ID, -1);
        String taskName = intent.getStringExtra(BroadcastHelper.EXTRA_TASK_NAME);

        if (taskId != -1 && this instanceof MainActivity) {
            Log.d(TAG, "Received main task in MainActivity: " + taskName);
            // Call MainActivity's method to handle the task
            ((MainActivity) this).executeMainByTaskId(taskId);
        }
    }

    private void handleDeliveryTaskFromRcs(Intent intent) {
        List<Position> selectedPosition = (List<Position>) intent.getSerializableExtra(BroadcastHelper.EXTRA_TASK_OBJECT);

        if (selectedPosition != null && this instanceof DeliveryActivity) {
            Log.d(TAG, "Received delivery task in DeliveryActivity: ");
            ((DeliveryActivity) this).executeDeliveryByTaskObject(selectedPosition);
        }
    }

    private void handleJackTaskFromRcs(Intent intent) {
        List<Position> selectedPosition = (List<Position>) intent.getSerializableExtra(BroadcastHelper.EXTRA_TASK_OBJECT);
        if (selectedPosition != null && this instanceof JackActivity) {
            Log.d(TAG, "Received jack task in JackActivity: ");
            // Call JackActivity's method to handle the task
            ((JackActivity) this).executeJackByTaskObject(selectedPosition);
        }
    }

    private void handleCruiseTaskFromRcs(Intent intent) {
        List<Position> selectedPosition = (List<Position>) intent.getSerializableExtra(BroadcastHelper.EXTRA_TASK_OBJECT);

        if (selectedPosition != null && this instanceof CruiseActivity) {
            Log.d(TAG, "Received cruise task in CruiseActivity: ");
            // Call CruiseActivity's method to handle the task
            ((CruiseActivity) this).executeCruiseByTaskObject(selectedPosition);
        }
    }

    private void handleMainTaskFromRcs(Intent intent) {
        List<Position> selectedPosition = (List<Position>) intent.getSerializableExtra(BroadcastHelper.EXTRA_TASK_OBJECT);

        if (selectedPosition != null && this instanceof MainActivity) {
            Log.d(TAG, "Received main task in MainActivity: ");
            // Call CruiseActivity's method to handle the task
            ((MainActivity) this).executeMainByTaskObject(selectedPosition);
        }
    }

    //get the defaultTime
    private int getDefaultStayDuration() {
        try {
            //  CruiseActivity 获取 defaultStayValue
            if (this instanceof CruiseActivity) {
                CruiseActivity cruiseActivity = (CruiseActivity) this;
                int stayTime = cruiseActivity.getTime();
                return stayTime;
            }
            return 10;
        } catch (Exception e) {
            return 10; // 默认10秒
        }
    }
    //get the etDuration

    private int getDurationFromCurrentOperation(Position target) {
        try {
            if (this instanceof JackActivity) {
                JackActivity jackActivity = (JackActivity) this;
                List<JackOperation> operations = jackActivity.getCurrentOperations();
                if (operations != null) {
                    for (JackOperation operation : operations) {
                        if (operation.getPointName().equals(target.getName())) {
                            return operation.getDuration();
                        }
                    }
                }
            }

            // 如果找不到对应的操作，返回默认值
            return 0;
        } catch (Exception e) {
            return 0; // 默认10秒
        }
    }

    // Add this method to load different GIFs based on type
    private void loadGifForType(String gifType, String description) {
        if (!isActivityValid() || gifView == null) return;

        int gifResource = getGifResourceForType(gifType);

        // Ensure GIF view is visible
        if (gifView.getVisibility() != View.VISIBLE) {
            gifView.setAlpha(0f);
            gifView.setVisibility(View.VISIBLE);
        }

        glideRequestManager
                .asGif()
                .load(gifResource)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .listener(new RequestListener<GifDrawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<GifDrawable> target, boolean isFirstResource) {
                        Log.e(TAG, "Failed to load GIF for type: " + gifType, e);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(GifDrawable resource, Object model,
                                                   Target<GifDrawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        if (gifView != null) {
                            gifView.animate().alpha(1f).setDuration(300).start();
                        }
                        // Start GIF animation
                        resource.start();
                        return false;
                    }
                })
                .into(gifView);
    }

    // Add this method to map GIF types to resources
    private int getGifResourceForType(String gifType) {
        if (gifType == null) {
            return R.drawable.navigation_gif; // Default navigation GIF
        }

        switch (gifType) {
            case GifTypeConstants.NAVIGATING_NORMAL:
                return R.drawable.navigation_gif;
            case GifTypeConstants.NAVIGATING_RIDE:
                return R.drawable.navigation_gif;
            case GifTypeConstants.NAVIGATING_WAIT:
                return R.drawable.navigation_gif;
            case GifTypeConstants.CALLING_ELEVATOR:
                return R.drawable.navigation_gif;
            case GifTypeConstants.WAITING_FOR_ELEVATOR:
                return R.drawable.navigation_gif;
            case GifTypeConstants.OPENING_DOOR:
                return R.drawable.navigation_gif;
            case GifTypeConstants.CLOSING_DOOR:
                return R.drawable.navigation_gif;
            case GifTypeConstants.CHANGING_MAP:
                return R.drawable.navigation_gif;
            case GifTypeConstants.CHECKING_ACCESS:
            case GifTypeConstants.REQUESTING_ACCESS:
            case GifTypeConstants.RELEASING_ACCESS:
                return R.drawable.navigation_gif;
            default:
                return R.drawable.navigation_gif; // Fallback to default
        }
    }

    // Add method to get current GIF state (useful for debugging)
    public String getCurrentGifState() {
        return "Type: " + currentGifType + ", Desc: " + currentGifDescription;
    }

    private String getSafePositionName(Position position) {
        if (position == null || position.getName() == null || position.getName().trim().isEmpty()
                || "null".equalsIgnoreCase(position.getName().trim())) {
            return getString(R.string.unknown_position);
        }
        return position.getName();
    }

    private String getLocalizedDescription(String gifType, String description) {
        if (description == null || "null".equalsIgnoreCase(description.trim())) {
            description = "";
        }
        // Split description if it contains target location information
        String mainDescription = "";
        String targetLocation = "";

        if (description != null && description.contains("|")) {
            // Format: "main description|target location"
            String[] parts = description.split("\\|", 2);
            mainDescription = parts[0];
            targetLocation = parts.length > 1 ? parts[1] : "";
        } else {
            mainDescription = description != null ? description : "";
        }

        // Map GIF types to string resources
        String baseDescription;
        switch (gifType) {
            case GifTypeConstants.NAVIGATING_NORMAL:
                baseDescription = getString(R.string.navigation_normal_description, mainDescription);
                break;
            case GifTypeConstants.NAVIGATING_RIDE:
                baseDescription = getString(R.string.navigation_ride_description, mainDescription);
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.NAVIGATING_WAIT:
                baseDescription = getString(R.string.navigation_wait_description, mainDescription);
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.NAVIGATING_CHARGE:
                baseDescription = getString(R.string.navigation_charge_description);
                break;
            case GifTypeConstants.NAVIGATING_PRECHARGE:
                baseDescription = getString(R.string.navigation_precharge_description);
                break;
            case GifTypeConstants.NAVIGATING_PARK:
                baseDescription = getString(R.string.navigation_park_description);
                break;
            case GifTypeConstants.CALLING_ELEVATOR:
                baseDescription = getString(R.string.calling_elevator_description, mainDescription);
                // Add target location for opening door
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.WAITING_FOR_ELEVATOR:
                baseDescription = getString(R.string.waiting_elevator_description, mainDescription);
                // Add target location for opening door
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.OPENING_DOOR:
                baseDescription = getString(R.string.opening_door_description);
                // Add target location for opening door
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.CLOSING_DOOR:
                baseDescription = getString(R.string.closing_door_description);
                // Add target location for opening door
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.CHANGING_MAP:
                baseDescription = getString(R.string.changing_map_description, mainDescription);
                // Add target location for changing map
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            case GifTypeConstants.CHECKING_ACCESS:
                baseDescription = getString(R.string.checking_access_description);
                // Add target location for opening door
                if (!targetLocation.isEmpty()) {
                    baseDescription += "\n" + getString(R.string.target_location_prefix, targetLocation);
                }
                break;
            default:
                baseDescription = mainDescription != null ? mainDescription :
                        getString(R.string.navigation_normal_description, mainDescription);
        }

        return baseDescription;
    }

    private String appendJackLoopProgress(String message) {
        if (!(this instanceof JackActivity)) {
            return message;
        }

        String progressText = ((JackActivity) this).getCurrentJackLoopProgressText();
        if (progressText == null || progressText.trim().isEmpty()) {
            return message;
        }

        String baseMessage = message == null ? "" : message;
        if (baseMessage.contains(progressText)) {
            return baseMessage;
        }
        return baseMessage.isEmpty() ? progressText : baseMessage + "\n" + progressText;
    }

    private void speakWithQueue(String text) {
        runOnUiThread(() -> {
            ttsQueue.offer(text);
            processTTSQueue();
        });
    }

    private void processTTSQueue() {
        if (isTTSSpeaking || ttsQueue.isEmpty() || ttsHelper == null) {
            return;
        }

        String nextText = ttsQueue.poll();
        if (nextText != null && !nextText.trim().isEmpty()) {
            isTTSSpeaking = true;
            ttsHelper.speak(nextText);
        }
    }
    // --------------------------------------------------------------------------------------------
    // helper methods

    public void setupFullScreen() {
        // 确保内容延伸到系统栏区域
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 获取窗口控制器
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        // 配置沉浸式行为
        if (windowInsetsController != null) {
            // 同时隐藏状态栏和导航栏
            windowInsetsController.hide(
                    WindowInsetsCompat.Type.systemBars() |
                            WindowInsetsCompat.Type.navigationBars()
            );

            // 设置沉浸式粘性行为（自动隐藏）
            windowInsetsController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }

        // 兼容旧版本实现（API 30以下）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }

        // 添加窗口焦点监听保持隐藏状态
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                setupFullScreen();
            }
        });
    }

    /**
     * 暂停倒计时
     */
    public void pauseCountdown() {
        runOnUiThread(() -> {
            if (timer != null && isCountdownRunning) {
                timer.cancel();
                Log.d(TAG, "倒计时已暂停");
            }
        });
    }

    /**
     * 检查是否在倒计时期间
     */
    public boolean isInCountdownPeriod() {
        return isCountdownRunning && remainingCountdownSeconds > 0;
    }

    public List<Position> generateFullPositionList(Position currentPosition, List<Position> selectedPositions) {
        TaskDebug8.log(String.format("[ROUTE] generateFullPositionList ENTER currentPos=%s selectedCount=%d",
                currentPosition != null ? String.format("%s(%.3f,%.3f)", currentPosition.getName(), currentPosition.getPosX(), currentPosition.getPosY()) : "null",
                selectedPositions != null ? selectedPositions.size() : -1));
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
            TaskDebug8.log("[ROUTE] generateFullPositionList FAIL arePositionsValid=false");
            if (debugger != null) {
                debugger.logWarning("generateFullPositionList",
                        "❌ One or more selected positions not found in current floor map");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.d("StatusVerification", "One or more selected positions not found in current floor map");
            onVoicePrompt(VoiceKeyConstants.MAP_INCORRECT);
            return null;
        }

        if (debugger != null) {
            debugger.log("│ ✅ Position validation passed");
        }

        // 1. Check if the selectedPositions have the same map prefix
        if (!isTaskOnSameMap(currentPosition, selectedPositions)) {
            TaskDebug8.log(String.format("[ROUTE] generateFullPositionList FAIL isTaskOnSameMap=false current=%s",
                    currentPosition != null ? currentPosition.getMapName() : "null"));
            if (debugger != null) {
                debugger.logWarning("generateFullPositionList",
                        "❌ Task stations not on same map");
                debugger.log("└─────────────────────────────────────────────────────────────────────────────┘");
            }
            Log.d("StatusVerification", "Task stations not on same map");
            onVoicePrompt(VoiceKeyConstants.MAP_INCORRECT);
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
                onVoicePrompt(VoiceKeyConstants.INVALID_ELEVATOR_TASK);
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
                onVoicePrompt(VoiceKeyConstants.ELEVATOR_EMPTY);
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

        TaskDebug8.log(String.format("[ROUTE] generateFullPositionList OK pathSize=%d", interpolatedPath != null ? interpolatedPath.size() : 0));
        if (interpolatedPath != null) {
            StringBuilder sb = new StringBuilder("[ROUTE] path:");
            for (int i = 0; i < interpolatedPath.size(); i++) {
                Position pos = interpolatedPath.get(i);
                sb.append(String.format(" [%d]%s(t=%d,tt=%d)", i,
                        pos != null ? pos.getName() : "null",
                        pos != null ? pos.getType() : -1,
                        pos != null ? pos.getTaskType() : -1));
            }
            TaskDebug8.log(sb.toString());
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
     * Check if wait points and ride points exist for the involved floors in the building.
     * Supports multiple elevator configurations across different floors.
     *
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

        // Build a map of floor -> elevator config that covers it
        Map<Integer, ElevatorViewModel.ElevatorConfig> floorToConfigMap = new HashMap<>();

        for (ElevatorViewModel.ElevatorConfig config : buildingConfigs) {
            if (debugger != null) {
                debugger.log(String.format("      │ Config (ID: %s, Channel: %d, Address: %d) covers floors:",
                        config.getId(), config.getChannel(), config.getAddress()));
            }

            for (ElevatorViewModel.ElevatorFloor floor : config.getFloors()) {
                int floorNum = floor.getFloor();

                // Only consider floors that are involved in the task
                if (involvedFloors.contains(floorNum)) {
                    // Check if this floor has valid wait point and ride point
                    Position waitPoint = floor.getWaitPoint();
                    Position ridePoint = floor.getRidePoint();

                    // ===== ADD LOGGING FOR FULL POINT NAMES =====
                    if (debugger != null) {
                        debugger.log(String.format("      │   Checking floor %d:", floorNum));
                        if (waitPoint != null) {
                            debugger.log(String.format("      │     Wait point name: '%s' (pos: %.2f, %.2f)",
                                    waitPoint.getName(), waitPoint.getPosX(), waitPoint.getPosY()));
                        } else {
                            debugger.log("      │     Wait point: null");
                        }
                        if (ridePoint != null) {
                            debugger.log(String.format("      │     Ride point name: '%s' (pos: %.2f, %.2f)",
                                    ridePoint.getName(), ridePoint.getPosX(), ridePoint.getPosY()));
                        } else {
                            debugger.log("      │     Ride point: null");
                        }
                    }

                    boolean hasValidWaitPoint = waitPoint != null &&
                            (waitPoint.getPosX() != 0.0 || waitPoint.getPosY() != 0.0);
                    boolean hasValidRidePoint = ridePoint != null &&
                            (ridePoint.getPosX() != 0.0 || ridePoint.getPosY() != 0.0);

                    if (hasValidWaitPoint && hasValidRidePoint) {
                        // This floor is covered by this config
                        floorToConfigMap.put(floorNum, config);
                        if (debugger != null) {
                            debugger.log(String.format("      │   ✅ Floor %d covered by config %s",
                                    floorNum, config.getId()));
                            debugger.log(String.format("      │       Wait: '%s' (%.2f, %.2f), Ride: '%s' (%.2f, %.2f)",
                                    waitPoint.getName(), waitPoint.getPosX(), waitPoint.getPosY(),
                                    ridePoint.getName(), ridePoint.getPosX(), ridePoint.getPosY()));
                        }
                    } else {
                        if (debugger != null) {
                            debugger.logWarning("checkElevatorPointsForFloors",
                                    String.format("      │   ❌ Floor %d has invalid points in config %s (wait valid: %b, ride valid: %b)",
                                            floorNum, config.getId(), hasValidWaitPoint, hasValidRidePoint));
                            if (!hasValidWaitPoint && waitPoint != null) {
                                debugger.log(String.format("      │       Wait point '%s' has zero coordinates",
                                        waitPoint.getName()));
                            }
                            if (!hasValidRidePoint && ridePoint != null) {
                                debugger.log(String.format("      │       Ride point '%s' has zero coordinates",
                                        ridePoint.getName()));
                            }
                        }
                    }
                }
            }
        }

        // Check if all involved floors are covered
        boolean allFloorsCovered = true;
        Set<Integer> missingFloors = new HashSet<>();

        for (int floor : involvedFloors) {
            if (!floorToConfigMap.containsKey(floor)) {
                allFloorsCovered = false;
                missingFloors.add(floor);
            }
        }

        if (!allFloorsCovered) {
            if (debugger != null) {
                debugger.logError("checkElevatorPointsForFloors",
                        String.format("Missing elevator points for floors: %s", missingFloors));
                debugger.log("      └───────────────────────────────────────────────────────────┘");
            }
            Log.e(TAG, "Missing elevator points for floors: " + missingFloors);
            return false;
        }

        // Log the floor-to-config mapping
        if (debugger != null) {
            debugger.log("      │ ✅ All floors have valid elevator points");
            debugger.log("      │ Floor to Config mapping:");
            for (Map.Entry<Integer, ElevatorViewModel.ElevatorConfig> entry : floorToConfigMap.entrySet()) {
                ElevatorViewModel.ElevatorConfig config = entry.getValue();
                debugger.log(String.format("      │   Floor %d -> Config ID: %s (Channel: %d, Address: %d)",
                        entry.getKey(), config.getId(), config.getChannel(), config.getAddress()));
            }
            debugger.log("      └───────────────────────────────────────────────────────────┘");
        }

        return true;
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

    private void controlMusicDuringCountdown(boolean shouldPlay) {
        if (musicPlayer != null) {
            if (shouldPlay && !isInCountdownPeriod) {
                if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                    return;
                }
                // Resume previous music, don't force WA sound
                if (wasMusicPlayingBeforeDialog) {
                    musicPlayer.resume();
                } else {
                    playRunningMusicIfNeeded(musicResId); // Only play music if nothing was playing
                }
            } else {
                if (musicPlayer.isPlaying()) {
                    onPauseSound();
                }
            }
        }
    }

    protected void pauseAndShowDialog() {
        if (navigationHandler == null || !navigationHandler.isTaskRunning()) return;
        TaskDebug2.log(String.format("[PAUSE] pauseAndShowDialog ENTER uiPaused=%b taskRunning=%b navPaused=%b navStopped=%b activity=%s",
                isPaused,
                navigationHandler.isTaskRunning(),
                navigationHandler.isNavigationPaused(),
                navigationHandler.isNavigationStopped(),
                getClass().getSimpleName()));
        isPaused = true; // 标记为暂停状态
        // Cancel the timer when pausing
        cancelTimer();

        // ===== FIX: Save music state BEFORE pausing =====
        if (musicPlayer != null && musicPlayer.isPlaying()) {
            wasMusicPlayingBeforeDialog = true;
        } else {
            wasMusicPlayingBeforeDialog = false;
        }

        // 暂停时停止音乐
        controlMusicDuringCountdown(false);

        // Dismiss existing dialog if showing
        if (pauseDialog != null && pauseDialog.isShowing()) {
            pauseDialog.dismiss();
        }

        // Pause navigation immediately
        navigationHandler.pauseNavigation();
        TaskDebug2.log(String.format("[PAUSE] pauseAndShowDialog AFTER handler.pause uiPaused=%b handlerNavPaused=%b",
                isPaused, navigationHandler.isNavigationPaused()));

        // Create new dialog
        pauseDialog = new Dialog(this);
        pauseDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        pauseDialog.setContentView(R.layout.dialog_robot_pause);
        pauseDialog.setCancelable(false);

        Window window = pauseDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setElevation(20f);
            }

            window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
        }

        MaterialButton btnResume = pauseDialog.findViewById(R.id.btn_resume);
        MaterialButton btnCancel = pauseDialog.findViewById(R.id.btn_cancel);

        // Store a local final reference to avoid race conditions
        final Dialog currentPauseDialog = pauseDialog;

        btnResume.setOnClickListener(v -> {
            TaskDebug2.log(String.format("[PAUSE] btnResume CLICK uiPaused=%b handlerNull=%b taskRunning=%b handlerNavPaused=%b handlerNavStopped=%b",
                    isPaused,
                    navigationHandler == null,
                    navigationHandler != null && navigationHandler.isTaskRunning(),
                    navigationHandler != null && navigationHandler.isNavigationPaused(),
                    navigationHandler != null && navigationHandler.isNavigationStopped()));
            if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                if (currentPauseDialog.isShowing()) {
                    currentPauseDialog.dismiss();
                }
                wasMusicPlayingBeforeDialog = false;
                TaskDebug2.log("[PAUSE] btnResume IGNORED no task running");
                Log.d(TAG, "Ignoring resume button because no task is running");
                return;
            }

            // Use local reference instead of class field
            if (currentPauseDialog != null && currentPauseDialog.isShowing()) {
                currentPauseDialog.dismiss();
            }
            navigationHandler.resumeNavigation();
            TaskDebug2.log(String.format("[PAUSE] btnResume AFTER handler.resume handlerNavPaused=%b handlerNavStopped=%b",
                    navigationHandler.isNavigationPaused(), navigationHandler.isNavigationStopped()));
            resumeFromScreenPauseUi();
        });

        btnCancel.setOnClickListener(v -> {
            // Use local reference instead of class field
            if (currentPauseDialog != null && currentPauseDialog.isShowing()) {
                currentPauseDialog.dismiss();
            }
            resetCountdownState(); // Clear countdown state when canceling
            cancelAndShowConfirmationDialog();
        });

        pauseDialog.setOnDismissListener(dialog -> {
            // Re-enable GIF view touches when dialog disappears
            if (gifView != null) {
                gifView.setClickable(true);
            }
            // Only nullify if it's the same dialog instance
            if (pauseDialog == currentPauseDialog) {
                pauseDialog = null;
            }
        });

        pauseDialog.show();
    }

    private void resumeFromScreenPauseUi() {
        isPaused = false;
        TaskDebug2.log(String.format("[PAUSE] resumeFromScreenPauseUi uiPaused=false handlerNavPaused=%b handlerNavStopped=%b taskRunning=%b countdownRunning=%b",
                navigationHandler != null && navigationHandler.isNavigationPaused(),
                navigationHandler != null && navigationHandler.isNavigationStopped(),
                navigationHandler != null && navigationHandler.isTaskRunning(),
                isCountdownRunning));

        if (wasMusicPlayingBeforeDialog && musicPlayer != null) {
            musicPlayer.resume();
            wasMusicPlayingBeforeDialog = false;
        }

        if (navigationHandler != null && !navigationHandler.isNavigationPaused()) {
            if (navigationHandler.isTaskRunning()
                    && isCountdownRunning
                    && currentTargetPosition != null
                    && currentContinueAction != null) {
                isInCountdownPeriod = true;
                controlMusicDuringCountdown(false);
                TaskDebug2.log("[PAUSE] resumeFromScreenPauseUi -> restore countdown");
                onNoShowContinueDialog(false, currentTargetPosition, currentContinueAction, null);
            } else {
                TaskDebug2.log("[PAUSE] resumeFromScreenPauseUi -> controlMusicDuringCountdown(true)");
                controlMusicDuringCountdown(true);
            }
        } else {
            TaskDebug2.log("[PAUSE] resumeFromScreenPauseUi STILL navPaused after resume");
        }
    }

    protected void cancelAndShowConfirmationDialog() {
        Dialog confirmDialog = new Dialog(this);
        confirmDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        confirmDialog.setContentView(R.layout.dialog_confirm_cancel);
        confirmDialog.setCancelable(true);

        Window window = confirmDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        confirmDialog.findViewById(R.id.btnDialogCancel).setOnClickListener(v -> {
            confirmDialog.dismiss();
            // Show pause dialog again if user cancels the cancellation
            pauseAndShowDialog();
        });

        confirmDialog.findViewById(R.id.btnDialogConfirm).setOnClickListener(v -> {
            confirmDialog.dismiss();

            //reset selected button status
            resetSelectedStations();

            // Properly cancel the navigation and clean up
            if (navigationHandler != null) {
                navigationHandler.cancelNavigation(true);
            }

            isPaused = false;
            wasMusicPlayingBeforeDialog = false;
            if (pauseDialog != null && pauseDialog.isShowing()) {
                pauseDialog.dismiss();
            }
            if (musicPlayer != null) {
                musicPlayer.stop();
            }

            // Hide the navigation views
            if (gifView != null) {
                gifView.setVisibility(View.GONE);
                gifView.setImageDrawable(null);
            }

            if (tvNavigationStatus != null) {
                tvNavigationStatus.setVisibility(View.GONE);
                tvNavigationStatus.setText("");
            }

            // Force layout refresh
            findViewById(android.R.id.content).requestLayout();

            resetCountdownState(); // Reset countdown state
        });

        confirmDialog.show();
    }

    private void resetSelectedStations() {
        if (this instanceof DeliveryActivity) {
            ((DeliveryActivity) this).clearAllSelections();
        }
    }

    // 添加这个方法到 BaseNavigationActivity
    public MaterialButton getPauseDialogResumeButton() {
        if (pauseDialog != null && pauseDialog.isShowing()) {
            return pauseDialog.findViewById(R.id.btn_resume);
        }

        // 尝试通过窗口查找
        try {
            ViewGroup rootView = (ViewGroup) getWindow().getDecorView();
            for (int i = 0; i < rootView.getChildCount(); i++) {
                View child = rootView.getChildAt(i);
                if (child != null && child.findViewById(R.id.btn_resume) != null) {
                    return child.findViewById(R.id.btn_resume);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding resume button", e);
        }

        return null;
    }

    private void setupCountdownTimer(Runnable onContinue) {
        final int waitDuration = getDeliveryWaitDuration();
        AtomicInteger countdown = new AtomicInteger(waitDuration);
        isCountdownFinished = false;  // Reset flag
        isCountdownRunning = true;    // Mark countdown as running for emergency stop recovery
        // 设置站点停留标志，阻止闲时回待命在站点停留期间触发
        if (navigationHandler != null) {
            navigationHandler.setInStationStay(true);
        }

        // Save music state
        if (musicPlayer != null && musicPlayer.isPlaying()) {
            wasMusicPlayingBeforeDialog = true;
            onPauseSound();
        } else {
            wasMusicPlayingBeforeDialog = false;
        }

        timer = new CountDownTimer(waitDuration*1000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (!isActivityValid()) {
                    cancel();
                    return;
                }
                if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                    Log.d(TAG, "Ignoring dialog countdown finish because the task is no longer running");
                    resetCountdownState();
                    return;
                }
                if (navigationHandler != null && navigationHandler.isNavigationStopped()) {
                    Log.d(TAG, "Dialog countdown paused due to navigation pause");
                    int currentValue = countdown.get();
                    // 如果countdown为0，说明上一次已经显示了"1秒"，应该使用1秒
                    remainingCountdownSeconds = currentValue > 0 ? currentValue : 1;
                    cancel();
                    return;
                }

                int secondsLeft = countdown.getAndDecrement();
                remainingCountdownSeconds = secondsLeft; // Update remaining time

                if (btnContinue != null) {
                    btnContinue.setText(getString(R.string.dialog_button_continue_with_time,
                            secondsLeft));
                }
            }

            @Override
            public void onFinish() {
                if (isCountdownFinished) {
                    return;  // Already handled by user click
                }
                isCountdownFinished = true;

                if (!isActivityValid()) {
                    cancel();
                    return;
                }
                if (navigationHandler != null && navigationHandler.isNavigationStopped()) {
                    Log.d(TAG, "Dialog countdown finished but navigation is paused");
                    remainingCountdownSeconds = 1; // 刚刚显示完"1秒"，应该使用1秒
                    return;
                }

                if (wasMusicPlayingBeforeDialog && musicPlayer != null) {
                    musicPlayer.resume();
                    wasMusicPlayingBeforeDialog = false;
                }

                if (musicPlayer != null){
                    playRunningMusicIfNeeded(musicResId);
                }

                if (continueDialog != null && continueDialog.isShowing()) {
                    continueDialog.dismiss();
                    onContinue.run();
                }
                resetCountdownState();
            }
        }.start();
    }

    //get the deliveryWaitDuration

    private int getDeliveryWaitDuration(){
        if (basicViewModel !=null && basicViewModel.getDeliveryWaitDuration().getValue() != null){
            return basicViewModel.getDeliveryWaitDuration().getValue();
        }
        return 10;
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        // 清除站点停留标志
        if (navigationHandler != null) {
            navigationHandler.setInStationStay(false);
        }
    }

    @Override
    public void onNavigationStopCleared() {
        runOnUiThread(() -> {
            if (navigationHandler == null || !navigationHandler.isTaskRunning()) {
                resetCountdownState();
                return;
            }
            resumeCountdownAfterNavigationStop();
        });
    }

    @Override
    public void onTaskRecoveryCancelled() {
        runOnUiThread(() -> {
            cancelTimer();
            resetCountdownState();
            isPaused = false;
            wasMusicPlayingBeforeDialog = false;
            if (continueDialog != null && continueDialog.isShowing()) {
                continueDialog.dismiss();
            }
            if (pauseDialog != null && pauseDialog.isShowing()) {
                pauseDialog.dismiss();
            }
            if (tvNavigationStatus != null) {
                tvNavigationStatus.setVisibility(View.GONE);
                tvNavigationStatus.setText("");
            }
            if (gifView != null) {
                gifView.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onScreenPauseInvalidated() {
        runOnUiThread(() -> {
            TaskDebug2.log(String.format("[PAUSE] onScreenPauseInvalidated uiPaused=%b pauseDialogShowing=%b handlerNavPaused=%b",
                    isPaused,
                    pauseDialog != null && pauseDialog.isShowing(),
                    navigationHandler != null && navigationHandler.isNavigationPaused()));
            TaskDebug1.log("[UI] onScreenPauseInvalidated: dismiss pause dialog after soft e-stop release");
            if (pauseDialog != null && pauseDialog.isShowing()) {
                pauseDialog.dismiss();
            }
            isPaused = false;

            if (navigationHandler != null && navigationHandler.isTaskRunning()
                    && !navigationHandler.isNavigationPaused()) {
                if (wasMusicPlayingBeforeDialog && musicPlayer != null) {
                    musicPlayer.resume();
                    wasMusicPlayingBeforeDialog = false;
                } else {
                    controlMusicDuringCountdown(true);
                }
            }
            resumeCountdownAfterNavigationStop();
        });
    }

    private void resumeCountdownAfterNavigationStop() {
        if (!isCountdownPausedByNavigationStop
                || !isActivityValid()
                || navigationHandler == null
                || !navigationHandler.isTaskRunning()
                || navigationHandler.isNavigationStopped()
                || currentTargetPosition == null
                || currentContinueAction == null) {
            return;
        }

        Position target = currentTargetPosition;
        Runnable continueAction = currentContinueAction;
        isCountdownPausedByNavigationStop = false;

        if (debugger != null) {
            debugger.log(String.format(
                    "Navigation stop cleared, resuming countdown for station '%s' at %d seconds",
                    getSafePositionName(target), remainingCountdownSeconds));
        }

        onNoShowContinueDialog(false, target, continueAction, null);
    }

    private void resetCountdownState() {
        TaskDebug1.log("[JACK] resetCountdownState isInStationStay=false");
        currentTargetPosition = null;
        currentContinueAction = null;
        remainingCountdownSeconds = 0;
        isCountdownRunning = false;
        isInCountdownPeriod = false;
        isCountdownPausedByNavigationStop = false;
        // 清除站点停留标志
        if (navigationHandler != null) {
            navigationHandler.setInStationStay(false);
        }
    }

    // 安全获取语音文本方法
    private String getVoiceTextFromKey(String key) {
        // 优先从 ViewModel 的实时数据中获取（确保最新）
        Map<String, String> currentVoiceList = basicViewModel.getVoicePromptList().getValue();
        if (currentVoiceList != null) {
            String text = currentVoiceList.get(key);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }

        // 如果 ViewModel 中没有，则尝试从本地 voiceList 获取（兼容旧逻辑）
        if (voiceList != null) {
            String text = voiceList.get(key);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        // 优先从用户配置中获取

        Log.w(TAG, "Voice key not found in config, using default: " + key);
        Log.d(TAG, "key: "+key);
        switch (key) {
            case VoiceKeyConstants.DEPARTURE_BROADCAST:
                return getString(R.string.default_departure_text);
            case VoiceKeyConstants.ARRIVAL:
                return getString(R.string.default_arrival_text);
            case VoiceKeyConstants.TASK_INTERRUPTED:
                return getString(R.string.default_task_break_text);
            case VoiceKeyConstants.PEDESTRIAN_ALERT:
                return getString(R.string.default_pedestrian_text);
            case VoiceKeyConstants.LOW_CONFIDENCE_ERROR:
                return getString(R.string.default_low_confidence_error_text);
            case VoiceKeyConstants.CHARGING_TASK_ERROR:
                return getString(R.string.default_charging_task_error_text);
            case VoiceKeyConstants.NAVIGATION_ERROR:
                return getString(R.string.default_navigation_error_text);
            case VoiceKeyConstants.EMERGENCY_STOP:
                return getString(R.string.default_emergency_stop_text);
            case VoiceKeyConstants.COLLISION_ALERT:
                return getString(R.string.default_collision_alert_text);
            case VoiceKeyConstants.ROBOT_ERROR:
                return getString(R.string.default_robot_error_text);
            case VoiceKeyConstants.SCHEDULED_TASK_SET:
                return getString(R.string.scheduled_task_set);
            case VoiceKeyConstants.ARRIVAl_ANNOUNCEMENT:
                return getString(R.string.arrival_announcement);
            case VoiceKeyConstants.START_EXECUTING_CRUISE_TASK:
                return getString(R.string.start_executing_cruise_task);
            case VoiceKeyConstants.LOW_BATTERY:
                return getString(R.string.low_battery);
            case VoiceKeyConstants.RETURN_ARRIVAL:
                return getString(R.string.return_arrival);
            case VoiceKeyConstants.DEFAULT_GOING_TO_NEXT_POINT_TEXT:
                return getString(R.string.default_going_to_point_text);
            case VoiceKeyConstants.OPERATION_ERROR_PROMPT:
                return getString(R.string.operation_error_prompt);
            case VoiceKeyConstants.RETURNING_HOME:
                return getString(R.string.default_returning_home_text);
            case VoiceKeyConstants.START_EXECUTING_JACK_TASK:
                return getString(R.string.start_lifting_task);
            case VoiceKeyConstants.DEFAULT_RETURNING_HOME_TEXT:
                return getString(R.string.start_to_home);
            case VoiceKeyConstants.GO_CHARGING:
                return getString(R.string.default_go_charge_text);
            case VoiceKeyConstants.CHARGE_FAILED:
                return getString(R.string.charge_failed);
            case VoiceKeyConstants.CHARGE_MISSING:
                return getString(R.string.charge_missing);
            case VoiceKeyConstants.PARK_FAILED:
                return getString(R.string.park_failed);
            case VoiceKeyConstants.INVALID_ELEVATOR_TASK:
                return getString(R.string.invalid_elevator_task);
            case VoiceKeyConstants.ELEVATOR_EMPTY:
                return getString(R.string.elevator_empty);
            case VoiceKeyConstants.MAP_INCORRECT:
                return getString(R.string.map_incorrect);
            case VoiceKeyConstants.TASK_COMPLETED:
                return getString(R.string.default_task_done_text);
            default:
                return getString(R.string.select_prompt_hint);
        }
    }

    private void hideKeyboardAndClearFocus() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && rootView != null) {
            imm.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
        }
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            currentFocus.clearFocus();
        }
    }

    private boolean isTaskOnSameFloor(Position currentPosition, List<Position> selectedPositions) {
        boolean isSameFloor = true;
        for (Position targetPosition: selectedPositions) {
            if (!currentPosition.getFloor().equals(targetPosition.getFloor())) {
                isSameFloor = false;
                return isSameFloor;
            }
        }
        return isSameFloor;
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

    // -----------------------------------------------------------------------------------------
    // MusicPlayerListener 接口实现

    @Override
    public void onPreparing() {
        runOnUiThread(() -> {

        });
    }

    @Override
    public void onPlaying() {
        runOnUiThread(() -> {
        });
    }

    @Override
    public void onPaused() {
        runOnUiThread(() -> {
        });
    }

    @Override
    public void onCompleted() {
        runOnUiThread(() -> {
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            Log.e("MusicPlayer", getString(R.string.music_error, error));
        });
    }

    // -----------------------------------------------------------------------------------------
    // TextToSpeechHelper 接口实现

    @Override
    public void onTTSInitialized(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS failed: " + status);
        }
    }

    @Override
    public void onStart(String utteranceId) {
        if (ttsHelper == null) {
            ttsHelper = new TextToSpeechHelper(this, this);
        }
    }

    @Override
    public void onDone(String utteranceId) {
        runOnUiThread(() -> {
            isTTSSpeaking = false;
            processTTSQueue();
        });
    }
}
