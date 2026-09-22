package com.ezhan.amr.navigation;

import static com.ezhan.amr.data.datatype.MultiBuildingMapPoints.generateMapName;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.navigation.task.CommandChecker;
import com.ezhan.amr.navigation.task.CommandMonitor;
import com.ezhan.amr.navigation.task.CommandSender;
import com.ezhan.amr.navigation.task.NavigationCancellationManager;
import com.ezhan.amr.navigation.task.NavigationCommandManager;
import com.ezhan.amr.navigation.task.NavigationExceptionManager;
import com.ezhan.amr.navigation.task.NavigationOrderStatus;
import com.ezhan.amr.navigation.task.NavigationOrderType;
import com.ezhan.amr.navigation.task.NavigationPositionManager;
import com.ezhan.amr.navigation.task.NavigationElevatorManager;
import com.ezhan.amr.navigation.task.NavigationRouteManager;
import com.ezhan.amr.navigation.task.NavigationState;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.ezhan.amr.navigation.task.PositionMonitor;
import com.ezhan.amr.navigation.task.RouteStore;
import com.ezhan.amr.utils.GifTypeConstants;
import com.ezhan.amr.utils.TaskDebug2;
import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.TaskViewModel;
import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeneralNavigationHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GeneralNavigationHandler.class);
    private String TAG = "GeneralNavigationHandler";
    // Dependencies
    private final SharedViewModel sharedViewModel;
    private final ElevatorViewModel elevatorViewModel;
    private final MapViewModel mapViewModel;
    private final BasicViewModel basicViewModel;
    private final TaskViewModel taskViewModel;
    private final Handler mainHandler;
    private NavigationUICallback uiCallback;
    private Runnable completionCallback;
    /** 导航代号：每次 startNavigation 自增。完成弹窗携带创建时的代号，
     *  onComplete 校验代号未变才执行，防止旧任务遗留弹窗误触发新任务的完成回调 */
    private volatile long navigationEpoch = 0;
    /** 当前导航路线创建时的代号，随 currentRouteId 一起更新，用于完成弹窗的过期校验 */
    private volatile long currentNavigationEpoch = 0;

    // Navigation state
    private final Object navigationLock = new Object();
    private NavigationState currentState = NavigationState.IDLE;
    private boolean isPaused = false;
    private boolean isEmergencyStop = false;
    private boolean isSafeEdgeTriggered = false;
    private boolean isSoftEmergencyStopTriggered = false;
    private boolean isConnectionFailed = false;
    /** After screen-pause resume, ignore lingering 10003 until chassis clears it once. */
    private boolean ignore10003AfterScreenResumeUntilClear = false;
    /** Tracks 10003 seen while screen-paused (screen hold or layered physical soft e-stop). */
    private boolean had10003WhileScreenPaused = false;
    /** Previous status tick had error 10003 (edge detection for soft-e-stop release). */
    private boolean lastStatusHad10003 = false;

    // Current route info
    private long currentRouteId = -1;
    private RouteStore currentRoute;
    private List<Position> currentTaskRoute = new ArrayList<>();
    private Position currentTaskTarget; // For UI display
    private NavigationOrderType currentCommandType;

    private boolean isGifPlaying = false;
    private boolean isTaskRunning = false;
    private TaskRecord currentRecord = null;
    private TaskRecord preCreatedTaskRecord;
    private ExternalTaskContext pendingExternalTaskContext;
    private boolean hasAutoChargeTask = false;
    // 站点停留倒计时标志，用于阻止闲时回待命在站点停留期间触发
    private boolean isInStationStay = false;

    // Confirmation state
    private final ConfirmationStateManager confirmationManager;
    private NavigationStateDebugger stateDebugger;
    private boolean isWaitingForUserConfirmation = true;
    private long waitingPositionId = -1;

    // Status tracking
    private long lastStatusUpdateTime = 0;
    private AgvStatusResponse latestStatusResponse;
    private final StatusWebSocketClient.StatusListener statusListener;

    // Managers
    private final NavigationRouteManager routeManager;
    private final NavigationPositionManager positionManager;
    private final NavigationCommandManager commandManager;
    private final NavigationCancellationManager cancellationManager;
    private final NavigationExceptionManager exceptionManager;
    private final CommandSender commandSender;
    private final CommandChecker commandChecker;
    private final NavigationElevatorManager elevatorManager;
    private boolean isElevatorManagerInitialized = false;

    // Progress monitoring
    private ScheduledExecutorService scheduler;
    private boolean schedulerStarted = false;
    private final ProgressListener progressListener;
    private ScheduledFuture<?> progressMonitorFuture;
    private ScheduledFuture<?> processingFuture;
    private ScheduledFuture<?> statusUpdateFuture;
    private ScheduledFuture<?> autoSaveFuture;
    private final AtomicBoolean isSchedulerRunning = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // Voice prompt cooldown
    private long lastEmergencyVoiceTime = 0;
    private long lastSafeEdgeVoiceTime = 0;
    private long lastNavigationErrorTime = 0;
    private long lastPedestrianVoiceTime = 0;
    private static final long VOICE_PROMPT_COOLDOWN = 5000;
    private static final long EMERGENCY_VOICE_PROMPT_COOLDOWN = 4000;
    private long taskStartTime = 0L;
    private final double DEFAULT_SPEED = 0.8;
    private double setSpeed = 0.8;
    private boolean isVirtualOrbit = false;
    private double recognizeDistance = 1.0;
    private int virtualOrbitObstacleTime = 0;
    private static final long AUTO_SAVE_INTERVAL_MS = 10000; // Save every 10 seconds
    private static final long ORPHAN_CHASSIS_CANCEL_COOLDOWN_MS = 2000;
    private boolean autoSaveEnabled = true;
    private long lastAutoSaveTime = 0;
    private long lastOrphanChassisCancelTime = 0;
    /** Set when user cancels or orphaned chassis navigation is cleared; blocks e-stop auto-resume. */
    private boolean taskCancelledByUser = false;
    private boolean navigationTransitionInProgress = false;
    private String currentSessionId;
    private boolean isMonitoringStarted = false;

    // WebSocket clients
    private StatusWebSocketClient statusClient;
    private CommandWebSocketClient commandClient;

    private int currentWaypointIndex = -1;
    private int currentEffectiveIndex = -1;
    private int currentJackLoopIndex = 1;
    private int currentJackTotalLoops = 1;
    private int currentCruiseLoopIndex = 1;
    private int currentCruiseTotalLoops = 1;

    private final List<TaskCompletionListener> taskCompletionListeners = new ArrayList<>();
    private final List<WaypointArrivedListener> waypointArrivedListeners = new ArrayList<>();
    private final List<EffectiveProgressListener> effectiveProgressListeners = new ArrayList<>();
    Gson gson = new Gson();

    String runningMusic = "wa";
    int musicResId = 0;

    public interface TaskCompletionListener {
        void onTaskCompleted();
        void onTaskCancelled();
        void onTaskFailed();
    }

    public interface WaypointArrivedListener {
        void onWaypointArrived(int waypointIndex, Position position);
    }

    public interface EffectiveProgressListener {
        void onEffectiveProgressChanged(int currentEffectiveIndex, Position currentTarget);
    }

    public interface NavigationUICallback {
        Context getContext();
        void onTaskRecordAddition(TaskRecord record);
        void onTaskRecordUpdate(TaskRecord record);
        void onVoicePrompt(String key);  // Request specific voice prompt
        void onArrivalVoicePrompt(Position target);
        void onGlideResume();
        void onPlaySound(int soundResource);
        void onPlayRunningMusicIfNeeded(int soundResource);
        void onStopSound();
        void onPauseSound();
        void onGifPlayback(boolean shouldPlay, String gifType, String description);
        void onShowToast(String message);
        void onShowToast(int resId);
        void onNoShowContinueDialog(boolean isLastStation, Position target,
                                    Runnable onContinue, Runnable onCancel);
        void onShowContinueDialog(boolean isLastStation, Position target,
                                  Runnable onContinue, Runnable onCancel);
        void onShowCompleteDialog(boolean isLastStation, Position target,
                                  Runnable onContinue, Runnable onCancel);
        void onNavigationStopCleared();
        void onScreenPauseInvalidated();
        default void onTaskRecoveryCancelled() {}
    }

    // ---------------------------------------------------------------------------------------------
    public GeneralNavigationHandler(SharedViewModel sharedViewModel) {
        this.sharedViewModel = sharedViewModel;
        this.mapViewModel = MyApplication.getInstance().getMapViewModel();
        this.elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        this.basicViewModel = MyApplication.getInstance().getBasicViewModel();
        this.taskViewModel = MyApplication.getInstance().getTaskViewModel();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Get WebSocket clients
        this.statusClient = sharedViewModel.getStatusClient();
        this.commandClient = sharedViewModel.getCommandClient();

        // Initialize managers
        this.routeManager = NavigationRouteManager.getInstance();
        this.positionManager = NavigationPositionManager.getInstance();
        this.commandManager = NavigationCommandManager.getInstance();
        this.cancellationManager = NavigationCancellationManager.getInstance();
        this.exceptionManager = NavigationExceptionManager.getInstance();
        this.elevatorManager = NavigationElevatorManager.getInstance();

        this.commandSender = new CommandSender();
        this.commandChecker = new CommandChecker();

        // Initialize confirmation manager (singleton)
        this.confirmationManager = ConfirmationStateManager.getInstance();

        this.stateDebugger = NavigationStateDebugger.getInstance();
        this.currentSessionId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        // Initialize managers
        this.routeManager.initManager();
        this.positionManager.initManager();
        this.commandManager.initManager();
        this.cancellationManager.initManager();
        this.exceptionManager.initManager();
        this.elevatorManager.initElevatorManager();
        this.isElevatorManagerInitialized = true;

        // Create progress listener
        this.progressListener = new ProgressListener();

        // Setup status listener
        this.statusListener = createStatusListener();
        Log.d(TAG, "Registering listener with SharedViewModel");
    }

    public void setUICallback(NavigationUICallback callback) {
        boolean callbackChanged = this.uiCallback != callback;
        this.uiCallback = callback;

        // Start monitoring when UI callback is set (activity is ready)
        if (callback != null && !isMonitoringStarted) {
            startMonitoring();
        }
        replayEmergencyStopForNewCallbackIfNeeded(callbackChanged);

        // Only start scheduler if not already running, not shutting down, AND a task is running or about to run
        if (!isSchedulerRunning.get() && !isShuttingDown.get() && isTaskRunning) {
            startScheduledProcessing();
            startProgressMonitoring();
            schedulerStarted = true;
            LOG.info("Scheduler started in setUICallback because task is running");
        } else if (!isSchedulerRunning.get() && !isShuttingDown.get()) {
            LOG.info("Scheduler not started in setUICallback - task not running yet, will start when navigation begins");
        } else {
            LOG.info("Scheduler already running or shutting down - status: running={}, shuttingDown={}",
                    isSchedulerRunning.get(), isShuttingDown.get());
        }

        diagnoseSchedulerStatus();
    }

    private void replayEmergencyStopForNewCallbackIfNeeded(boolean callbackChanged) {
        if (uiCallback == null || !isEmergencyStop || latestStatusResponse == null ||
                latestStatusResponse.data == null || !isEmergencyStatus(latestStatusResponse)) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (!callbackChanged && currentTime - lastEmergencyVoiceTime <= EMERGENCY_VOICE_PROMPT_COOLDOWN) {
            return;
        }

        lastEmergencyVoiceTime = currentTime;
        playVoicePrompt(VoiceKeyConstants.EMERGENCY_STOP);
    }

    public void startMonitoring() {
        if (isMonitoringStarted) {
            Log.d(TAG, "Monitoring already started");
            return;
        }

        synchronized (navigationLock) {
            Log.d(TAG, "Starting monitoring...");

            // Register status listener
            if (statusListener != null) {
                sharedViewModel.registerStatusListener(statusListener);
                Log.d(TAG, "Status listener registered");
                isMonitoringStarted = true;
            } else {
                Log.e(TAG, "Cannot start monitoring - statusListener is null");
            }

            // Start periodic health check
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isMonitoringStarted) {
                        checkStatusHealth();
                        checkWebSocketHealth();
                        mainHandler.postDelayed(this, 30000); // Check every 30 seconds
                    }
                }
            }, 30000);
        }
    }

    /**
     * Check status health and log issues
     */
    private void checkStatusHealth() {
        if (stateDebugger == null) return;

        long timeSinceLastUpdate = System.currentTimeMillis() - lastStatusUpdateTime;

        if (timeSinceLastUpdate > 5000) {
            stateDebugger.logWarning(
                    "Status updates stalled",
                    String.format("No status update for %d ms", timeSinceLastUpdate)
            );
        }

        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            if (latestStatusResponse.data.power < 15.0) {
                stateDebugger.logWarning(
                        "Critical battery level",
                        String.format("Battery: %.1f%%", latestStatusResponse.data.power)
                );
            }

            if (latestStatusResponse.data.poseProbability < 0.3) {
                stateDebugger.logWarning(
                        "Very low pose confidence",
                        String.format("Confidence: %.2f", latestStatusResponse.data.poseProbability)
                );
            }
        } else {
            stateDebugger.logWarning(
                    "No status data available",
                    "latestStatusResponse is null or has no data"
            );
        }
    }

    /**
     * Stop monitoring (unregister status listener)
     * Call this when the activity is destroyed or when monitoring is no longer needed
     */
    public void stopMonitoring() {
        if (!isMonitoringStarted) {
            return;
        }

        synchronized (navigationLock) {
            Log.d(TAG, "Stopping monitoring...");

            // Unregister status listener
            if (statusListener != null) {
                sharedViewModel.unregisterStatusListener(statusListener);
                Log.d(TAG, "Status listener unregistered");
            }

            isMonitoringStarted = false;

            // Don't shutdown scheduler here - it will be managed by startNavigation/cleanup
        }
    }

    /**
     * Create the status listener (separate method for clarity)
     */
    private StatusWebSocketClient.StatusListener createStatusListener() {
        return new StatusWebSocketClient.StatusListener() {
            @Override
            public void onStatusUpdate(AgvStatusResponse response) {
                if (response != null && response.data != null) {
                    // Calculate time since last update
                    long now = System.currentTimeMillis();
                    long timeSinceLastUpdate = lastStatusUpdateTime > 0 ? now - lastStatusUpdateTime : -1;

                    // Log status update using debugger
                    if (stateDebugger != null) {
                        stateDebugger.logStatusUpdate(
                                "GeneralNavigationHandler",
                                true,
                                timeSinceLastUpdate
                        );

                        // Safely convert values that might be different types
                        String goalFinishStr = String.valueOf(response.data.goalFinish);

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
                        stateDebugger.log(statusDetails);

                        // Log warning if status data seems stale or invalid
                        if (response.data.poseProbability < 0.5) {
                            stateDebugger.logWarning(
                                    "Low pose confidence",
                                    String.format("Pose probability: %.2f", response.data.poseProbability)
                            );
                        }

                        if (response.data.power < 20.0) {
                            stateDebugger.logWarning(
                                    "Low battery",
                                    String.format("Battery level: %.1f%%", response.data.power)
                            );
                        }
                    }

                    latestStatusResponse = response;
                    lastStatusUpdateTime = now;
                    handleGeneralStatus(response);
                } else {
                    // Log invalid status response
                    if (stateDebugger != null) {
                        stateDebugger.logWarning(
                                "Invalid status response",
                                "Response or response.data is null"
                        );
                    }
                }
                refreshBasicSettings();
            }
        };
    }

    private void refreshBasicSettings() {
        Double latestSpeed = basicViewModel.getSetSpeed().getValue();
        setSpeed = latestSpeed != null ? latestSpeed : DEFAULT_SPEED;

        if (uiCallback != null && uiCallback.getContext() != null) {
            runningMusic = basicViewModel.getRunningMusic().getValue();
            if (runningMusic == null) runningMusic = "ezhan1";
            musicResId = uiCallback.getContext().getResources().getIdentifier(runningMusic, "raw", uiCallback.getContext().getPackageName());
            if (musicResId == 0) musicResId = R.raw.ezhan1;
        }

        Boolean latestOrbitMode = basicViewModel.getIsVirtualOrbitMode().getValue();
        isVirtualOrbit = latestOrbitMode != null ? latestOrbitMode : false;

        Integer latestOrbitObstacleTime = basicViewModel.getVirtualOrbitObstacleTime().getValue();
        virtualOrbitObstacleTime = latestOrbitObstacleTime != null ? latestOrbitObstacleTime : 0;

        Double latestRecognizeDistance = basicViewModel.getRecognizeDistance().getValue();
        recognizeDistance = latestRecognizeDistance != null ? latestRecognizeDistance : 1.33f;
    }

    /**
     * Log current position analysis (charge point, elevator, etc.)
     */
    private void logPositionAnalysis(Position currentPosition, int currentFloor, boolean isAtCharge, boolean isInElevator) {
        if (stateDebugger == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(60));
        sb.append("\n📍 START NAVIGATION - POSITION ANALYSIS");
        sb.append("\n").append("=".repeat(60));

        // Current position details
        if (currentPosition != null) {
            sb.append(String.format("\n Current Position:\n" +
                            "   ├─ Name: %s\n" +
                            "   ├─ Coordinates: (%.3f, %.3f)\n" +
                            "   ├─ Theta: %.3f\n" +
                            "   ├─ Floor: %s\n" +
                            "   ├─ Type: %d\n" +
                            "   ├─ Task Type: %d\n" +
                            "   └─ Map: %s",
                    currentPosition.getName() != null ? currentPosition.getName() : "unknown",
                    currentPosition.getPosX(),
                    currentPosition.getPosY(),
                    currentPosition.getYaw(),
                    currentPosition.getFloor() != null ? currentPosition.getFloor() : String.valueOf(currentFloor),
                    currentPosition.getType(),
                    currentPosition.getTaskType(),
                    currentPosition.getMapName() != null ? currentPosition.getMapName() : "unknown"
            ));
        } else {
            sb.append("\n Current Position: NULL");
        }

        // Charge point detection
        sb.append("\n\n Charge Point Analysis:");
        sb.append(String.format("\n   ├─ At Charge Position: %s", isAtCharge));
        if (isAtCharge) {
            sb.append("\n   ├─ Action: Adding pre-charge position to route");
            Position prechargePos = mapViewModel.getPreChargePosition(currentFloor);
            if (prechargePos != null) {
                sb.append(String.format("\n   └─ Pre-charge Position: (%.3f, %.3f), Floor: %s",
                        prechargePos.getPosX(), prechargePos.getPosY(), prechargePos.getFloor()));
            } else {
                sb.append("\n   └─ Pre-charge Position: NOT FOUND for floor " + currentFloor);
            }
        } else {
            sb.append("\n   └─ Action: No pre-charge position needed");
        }

        // Elevator detection
        sb.append("\n\n Elevator Analysis:");
        Position updatedPosition = elevatorViewModel.updatePositionTypeIfNearSpecialPoints(currentPosition);
        isInElevator = updatedPosition != null && updatedPosition.getType() == 4;
        sb.append(String.format("\n   ├─ Position Type after elevator check: %d",
                updatedPosition != null ? updatedPosition.getType() : -1));
        sb.append(String.format("\n   ├─ Is Inside Elevator (Type 4): %s", isInElevator));
        if (isInElevator) {
            sb.append("\n   └─ Action: Task cannot start from inside elevator - WILL REJECT");
        } else {
            sb.append("\n   └─ Action: Position is safe for navigation");
        }

        sb.append("\n").append("=".repeat(60));

        stateDebugger.log(sb.toString());
    }

    /**
     * Handle general status (emergency, safe edge, etc.) from original
     */
    private void handleGeneralStatus(AgvStatusResponse response) {
        long currentTime = System.currentTimeMillis();

        handleOrphanedChassisNavigation(response);

        // Emergency stop detection
        boolean emergencyStatus = isEmergencyStatus(response);
        if (emergencyStatus && !isEmergencyStop) {
            triggerEmergencyStop();
            lastEmergencyVoiceTime = currentTime;
        } else if (!emergencyStatus && isEmergencyStop) {
            clearEmergencyStop();
        }

        // Safe edge detection
        if (response.data.errorCode != null && response.data.errorCode.contains(10002) && !isSafeEdgeTriggered) {
            triggerSafeEdge();
            lastSafeEdgeVoiceTime = currentTime;
        } else if ((response.data.errorCode == null || !response.data.errorCode.contains(10002)) && isSafeEdgeTriggered) {
            clearSafeEdge();
        }

        // Soft emergency stop detection (physical soft e-stop button, error 10003)
        boolean has10003 = containsErrorCode(response.data.errorCode, 10003);
        if (isPaused && has10003) {
            had10003WhileScreenPaused = true;
        }

        if (has10003 && !isSoftEmergencyStopTriggered && !isPaused) {
            if (ignore10003AfterScreenResumeUntilClear) {
                TaskDebug2.logThrottled("pause_10003_post_resume", 1000,
                        "[PAUSE] status 10003 ignored (post screen-resume, awaiting clear) "
                                + pauseDebugSnapshot());
            } else if (taskCancelledByUser) {
                TaskDebug2.logThrottled("pause_10003_task_cancelled", 1000,
                        "[PAUSE] status 10003 ignored (task cancelled) " + pauseDebugSnapshot());
            } else {
                TaskDebug2.log("[PAUSE] status 10003 -> triggerSoftEmergencyStop " + pauseDebugSnapshot());
                triggerSoftEmergencyStop();
            }
        } else if (has10003 && !isSoftEmergencyStopTriggered && isPaused) {
            TaskDebug2.logThrottled("pause_10003_skip", 2000,
                    "[PAUSE] status 10003 skipped (screen paused) " + pauseDebugSnapshot());
        } else if (!has10003 && isSoftEmergencyStopTriggered) {
            TaskDebug2.log("[PAUSE] status 10003 cleared -> clearSoftEmergencyStop " + pauseDebugSnapshot());
            clearSoftEmergencyStop();
        } else if (isPaused && had10003WhileScreenPaused && lastStatusHad10003 && !has10003
                && !isSoftEmergencyStopTriggered) {
            had10003WhileScreenPaused = false;
            TaskDebug2.log("[PAUSE] status 10003 edge-clear during screen pause -> resume+invalidateUi "
                    + pauseDebugSnapshot());
            if (shouldResumeAfterNavigationStopCleared()) {
                resumeNavigation();
                notifyScreenPauseInvalidated();
            } else {
                synchronized (navigationLock) {
                    isPaused = false;
                    currentState = NavigationState.IDLE;
                    commandClient.setSoftScram(false);
                }
                notifyScreenPauseInvalidated();
            }
        }

        lastStatusHad10003 = has10003;

        if (!has10003 && ignore10003AfterScreenResumeUntilClear) {
            ignore10003AfterScreenResumeUntilClear = false;
            TaskDebug2.log("[PAUSE] post screen-resume 10003 cleared, suppress lifted "
                    + pauseDebugSnapshot());
        }

        if (isEmergencyStop && isNavigationPaused() &&
                (currentTime - lastEmergencyVoiceTime) > EMERGENCY_VOICE_PROMPT_COOLDOWN) {
            lastEmergencyVoiceTime = currentTime;
            playVoicePrompt(VoiceKeyConstants.EMERGENCY_STOP);
        } else if (isSafeEdgeTriggered && isNavigationPaused() && !isEmergencyStop &&
                (currentTime - lastSafeEdgeVoiceTime) > EMERGENCY_VOICE_PROMPT_COOLDOWN) {
            lastSafeEdgeVoiceTime = currentTime;
            playVoicePrompt(VoiceKeyConstants.COLLISION_ALERT);
        }

        // Navigation errors
        if (isTaskRunning && !isNavigationPaused() &&
                response.data.goalFinish == 0 &&
                response.data.errorCode != null && response.data.errorCode.contains(60003) &&
                (currentTime - lastNavigationErrorTime) > VOICE_PROMPT_COOLDOWN) {
            lastNavigationErrorTime = currentTime;
            playVoicePrompt(VoiceKeyConstants.NAVIGATION_ERROR);
        }

        // Pedestrian alerts
        if (isTaskRunning && !isNavigationPaused() &&
                response.data.goalFinish == 0 &&
                response.data.errorCode != null && !response.data.errorCode.contains(60003) &&
                (currentTime - lastPedestrianVoiceTime) > 30000) {
            lastPedestrianVoiceTime = currentTime;
            playVoicePrompt(VoiceKeyConstants.PEDESTRIAN_ALERT);
        }
    }

    private boolean isEmergencyStatus(AgvStatusResponse response) {
        return response != null && response.data != null &&
                (response.data.emgStop || containsErrorCode(response.data.errorCode, 10001));
    }

    private boolean containsErrorCode(List<Integer> errorCodes, int targetCode) {
        if (errorCodes == null) {
            return false;
        }
        for (Integer code : errorCodes) {
            if (code != null && code == targetCode) {
                return true;
            }
        }
        return false;
    }

    private boolean isChassisNavigating(AgvStatusResponse response) {
        return response != null && response.data != null && response.data.goalFinish == 0;
    }

    private boolean hasActiveAppNavigation() {
        return isTaskRunning || currentRouteId != -1 || !RouteStore.getAllRoutes().isEmpty();
    }

    private boolean shouldResumeAfterNavigationStopCleared() {
        return isTaskRunning && !taskCancelledByUser;
    }

    /**
     * After reboot or process death the app loses in-memory route state while the chassis
     * may still be executing the previous navigation. Detect and cancel that orphaned task.
     */
    private void handleOrphanedChassisNavigation(AgvStatusResponse response) {
        synchronized (navigationLock) {
            if (navigationTransitionInProgress) {
                return;
            }
            if (!isChassisNavigating(response)) {
                return;
            }
            if (hasActiveAppNavigation()) {
                return;
            }
            if (hasAutoChargeTask || pendingExternalTaskContext != null) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastOrphanChassisCancelTime < ORPHAN_CHASSIS_CANCEL_COOLDOWN_MS) {
                return;
            }
            lastOrphanChassisCancelTime = now;
            taskCancelledByUser = true;

            LOG.warn("Orphaned chassis navigation detected (goalFinish=0, no app task). Sending clearTask.");
            TaskDebug1.log("[NAV] ORPHAN_CHASSIS_CANCEL goalFinish=0 noAppTask=true");
            commandClient.cancelTask();
            commandClient.setSoftScram(false);
            suppressLingering10003AfterAppReleaseAndClearSoftEstop();
            stopSound();
            taskViewModel.reconcileStaleRunningTaskRecords(true);
            executeOnMainThread(this::notifyTaskRecoveryCancelled);
        }
    }

    private void notifyTaskRecoveryCancelled() {
        if (uiCallback != null) {
            uiCallback.onTaskRecoveryCancelled();
        }
    }

    /**
     * Initialize the scheduler - must be called on UI thread or with proper synchronization
     */
    private synchronized void initScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NavigationScheduler");
                t.setDaemon(true); // Make daemon so it doesn't prevent app shutdown
                return t;
            });
            isSchedulerRunning.set(true);
            LOG.info("Navigation scheduler initialized");
        }
    }

    /**
     * Shutdown the scheduler safely
     */
    private synchronized void shutdownScheduler() {
        if (isShuttingDown.compareAndSet(false, true)) {
            try {
                // Cancel auto-save future first
                if (autoSaveFuture != null) {
                    autoSaveFuture.cancel(false);
                    autoSaveFuture = null;
                }
                if (processingFuture != null) {
                    processingFuture.cancel(false);
                    processingFuture = null;
                }
                if (statusUpdateFuture != null) {
                    statusUpdateFuture.cancel(false);
                    statusUpdateFuture = null;
                }
                if (progressMonitorFuture != null) {
                    progressMonitorFuture.cancel(false);
                    progressMonitorFuture = null;
                }

                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.shutdown();
                    try {
                        if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                            scheduler.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                isSchedulerRunning.set(false);
                isShuttingDown.set(false);
                LOG.info("Navigation scheduler shut down");
            }
        }
    }

    private void startScheduledProcessing() {
        synchronized (this) {
            LOG.info("========== START SCHEDULED PROCESSING ==========");
            LOG.info("Current state before start:");
            LOG.info("  scheduler == null: {}", scheduler == null);
            LOG.info("  isSchedulerRunning: {}", isSchedulerRunning.get());
            LOG.info("  isShuttingDown: {}", isShuttingDown.get());
            LOG.info("  currentRouteId: {}", currentRouteId);
            LOG.info("  isTaskRunning: {}", isTaskRunning);

            // Don't start if already shutting down
            if (isShuttingDown.get()) {
                LOG.warn("Cannot start scheduled processing - system is shutting down");
                return;
            }

            initScheduler();

            if (scheduler != null && !scheduler.isShutdown()) {
                try {
                    // Cancel any existing futures first
                    if (processingFuture != null && !processingFuture.isDone()) {
                        LOG.info("Cancelling existing processingFuture");
                        processingFuture.cancel(false);
                    }
                    if (statusUpdateFuture != null && !statusUpdateFuture.isDone()) {
                        LOG.info("Cancelling existing statusUpdateFuture");
                        statusUpdateFuture.cancel(false);
                    }

                    // Process tasks every 400ms
                    processingFuture = scheduler.scheduleWithFixedDelay(
                            this::executeCoordinatedProcessing, 100, 400, TimeUnit.MILLISECONDS);
                    LOG.info("Processing future scheduled: {}", processingFuture);

                    // Update status every 300ms
                    statusUpdateFuture = scheduler.scheduleWithFixedDelay(
                            this::executeCoordinatedStatusUpdates, 200, 300, TimeUnit.MILLISECONDS);
                    LOG.info("Status update future scheduled: {}", statusUpdateFuture);

                    LOG.info("Scheduled processing started successfully");

                    // Verify futures are not cancelled
                    if (processingFuture.isCancelled() || processingFuture.isDone()) {
                        LOG.error("Processing future was cancelled or done immediately after scheduling!");
                    }
                    if (statusUpdateFuture.isCancelled() || statusUpdateFuture.isDone()) {
                        LOG.error("Status update future was cancelled or done immediately after scheduling!");
                    }

                } catch (RejectedExecutionException e) {
                    LOG.error("Failed to start scheduled processing: {}", e.getMessage(), e);
                    // Reset and try to reinitialize
                    shutdownScheduler();
                }
            } else {
                LOG.error("Scheduler is null or shutdown - cannot start processing");
            }
            LOG.info("========== END START SCHEDULED PROCESSING ==========");
        }
    }

    private void startProgressMonitoring() {
        synchronized (this) {
            LOG.info("========== START PROGRESS MONITORING ==========");
            LOG.info("Current state before start:");
            LOG.info("  scheduler == null: {}", scheduler == null);
            LOG.info("  isSchedulerRunning: {}", isSchedulerRunning.get());
            LOG.info("  isShuttingDown: {}", isShuttingDown.get());

            if (isShuttingDown.get()) {
                LOG.warn("Cannot start progress monitoring - system is shutting down");
                return;
            }

            initScheduler();

            if (scheduler != null && !scheduler.isShutdown()) {
                try {
                    if (progressMonitorFuture != null && !progressMonitorFuture.isDone()) {
                        LOG.info("Cancelling existing progressMonitorFuture");
                        progressMonitorFuture.cancel(false);
                    }

                    // Monitor progress every 200ms
                    progressMonitorFuture = scheduler.scheduleWithFixedDelay(
                            this::checkProgressAndUpdateUI, 300, 200, TimeUnit.MILLISECONDS);
                    LOG.info("Progress monitor future scheduled: {}", progressMonitorFuture);

                    // Verify future is not cancelled
                    if (progressMonitorFuture.isCancelled() || progressMonitorFuture.isDone()) {
                        LOG.error("Progress monitor future was cancelled or done immediately after scheduling!");
                    }

                    LOG.info("Progress monitoring started successfully");
                } catch (RejectedExecutionException e) {
                    LOG.error("Failed to start progress monitoring: {}", e.getMessage(), e);
                    shutdownScheduler();
                }
            } else {
                LOG.error("Scheduler is null or shutdown - cannot start progress monitoring");
            }
            LOG.info("========== END START PROGRESS MONITORING ==========");
        }
    }

    private void executeCoordinatedProcessing() {
        // Check if we should still be running
        if (isShuttingDown.get() || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        synchronized (navigationLock) {
            if (currentRouteId == -1 || !isTaskRunning) {
                return;  // Just return immediately, no heavy processing
            }

            if (isNavigationPaused() || currentRouteId == -1) {
                return;
            }
            try {
                routeManager.executeCoordinatedRouteProcessing();
            } catch (Exception e) {
                LOG.error("Error in coordinated processing: {}", e.getMessage());
            }
        }
    }

    private void executeCoordinatedStatusUpdates() {
        if (isShuttingDown.get() || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        synchronized (navigationLock) {
            if (currentRouteId == -1) return;
            try {
                commandManager.executeCoordinatedStatusUpdates();
            } catch (Exception e) {
                LOG.error("Error in status updates: {}", e.getMessage());
            }
        }
    }

    public void diagnoseSchedulerStatus() {
        LOG.info("========== SCHEDULER DIAGNOSTICS ==========");
        LOG.info("scheduler == null: {}", scheduler == null);
        LOG.info("scheduler.isShutdown(): {}", scheduler != null && scheduler.isShutdown());
        LOG.info("isSchedulerRunning.get(): {}", isSchedulerRunning.get());
        LOG.info("isShuttingDown.get(): {}", isShuttingDown.get());
        LOG.info("schedulerStarted: {}", schedulerStarted);
        LOG.info("processingFuture == null: {}", processingFuture == null);
        LOG.info("processingFuture.isDone(): {}", processingFuture != null && processingFuture.isDone());
        LOG.info("processingFuture.isCancelled(): {}", processingFuture != null && processingFuture.isCancelled());
        LOG.info("statusUpdateFuture == null: {}", statusUpdateFuture == null);
        LOG.info("statusUpdateFuture.isDone(): {}", statusUpdateFuture != null && statusUpdateFuture.isDone());
        LOG.info("progressMonitorFuture == null: {}", progressMonitorFuture == null);
        LOG.info("progressMonitorFuture.isDone(): {}", progressMonitorFuture != null && progressMonitorFuture.isDone());
        LOG.info("currentRouteId: {}", currentRouteId);
        LOG.info("isTaskRunning: {}", isTaskRunning);
        LOG.info("==========================================");
    }

    /**
     * Auto-save current navigation state for debugging
     * Called periodically by the scheduler
     */
    private void autoSaveDebugState() {
        if (!autoSaveEnabled || stateDebugger == null) return;
        if (isShuttingDown.get() || scheduler == null || scheduler.isShutdown()) return;

        synchronized (navigationLock) {
            // Only auto-save when a task is running or has meaningful state
            if (currentRouteId == -1 && !isTaskRunning) {
                return;
            }

            long currentTime = System.currentTimeMillis();

            // Rate limit auto-save to avoid excessive file I/O
            if (currentTime - lastAutoSaveTime < AUTO_SAVE_INTERVAL_MS / 2) {
                return;
            }

            lastAutoSaveTime = currentTime;

            try {
                // Build comprehensive state dump
                String stateDump = buildAutoSaveStateDump();

                // Write to debugger
                if (stateDebugger != null) {
                    // This will write to the log file if configured
                    stateDebugger.dumpFullState("AUTO_SAVE_" + currentTime);
                }

                // Also log summary for quick monitoring
                logStateSummary();

            } catch (Exception e) {
                Log.e(TAG, "Error in auto-save: " + e.getMessage());
            }
        }
    }

    /**
     * Build detailed state dump for auto-save
     */
    private String buildAutoSaveStateDump() {
        StringBuilder sb = new StringBuilder();

        sb.append("\n").append("=".repeat(80)).append("\n");
        sb.append("AUTO-SAVE STATE DUMP\n");
        sb.append("Session: ").append(currentSessionId).append("\n");
        sb.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date())).append("\n");
        sb.append("Task Running: ").append(isTaskRunning).append("\n");
        sb.append("Route ID: ").append(currentRouteId).append("\n");
        sb.append("Navigation State: ").append(currentState).append("\n");
        sb.append("Paused: ").append(isPaused).append("\n");
        sb.append("Emergency Stop: ").append(isEmergencyStop).append("\n");
        sb.append("Safe Edge: ").append(isSafeEdgeTriggered).append("\n");
        sb.append("Soft Emergency Stop: ").append(isSoftEmergencyStopTriggered).append("\n");
        sb.append("Waiting Confirmation: ").append(isWaitingForUserConfirmation).append("\n");
        sb.append("=".repeat(80)).append("\n");

        return sb.toString();
    }

    /**
     * Log summary of current state (less verbose than full dump)
     */
    private void logStateSummary() {
        if (!autoSaveEnabled) return;

        int routeCount = RouteStore.getAllRoutes().size();
        int positionCount = PositionMonitor.getAllPositions().size();
        int commandCount = CommandMonitor.getAllCommands().size();

        Log.d(TAG, String.format("📊 State Summary: Route=%d, Position=%d, Command=%d, Waiting=%b",
                routeCount, positionCount, commandCount, isWaitingForUserConfirmation));

        // Log current position status if available
        if (currentRouteId != -1) {
            RouteStore route = RouteStore.getRoute(currentRouteId);
            if (route != null) {
                List<PositionMonitor> positions = getPositionsForRoute(currentRouteId);
                for (PositionMonitor pos : positions) {
                    if (pos.getPositionStatus() == NavigationOrderStatus.ORDER_EXECUTING ||
                            pos.getPositionStatus() == NavigationOrderStatus.ORDER_WAITING_CONFIRMATION) {
                        Log.d(TAG, String.format("  Active Position[%d]: status=%s, waiting=%b",
                                pos.getPositionId(), pos.getPositionStatus(),
                                confirmationManager.isPositionWaitingForConfirmation(pos.getPositionId())));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Log navigation start for session tracking
     */
    private void logNavigationStart() {
        if (stateDebugger != null) {
            String startMsg = "\n" + "=".repeat(80) + "\n" +
                    "NAVIGATION SESSION STARTED\n" +
                    "Session ID: " + currentSessionId + "\n" +
                    "Start Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n" +
                    "=".repeat(80) + "\n";

            // Write to debug log
            if (autoSaveEnabled) {
                stateDebugger.dumpFullState("SESSION_START");
            }

            Log.i(TAG, "Navigation session started: " + currentSessionId);
        }
    }

    /**
     * Extract elevator ID from a position name.
     * Position names follow pattern: elevator_wait_floor_elevatorId or elevator_ride_floor_elevatorId etc.
     *
     * @param position The position to check
     * @return The elevator ID if found, null otherwise
     */
    private String extractElevatorIdFromPosition(Position position) {
        if (position == null || position.getName() == null) {
            return null;
        }

        String name = position.getName();
        // Match elevator point patterns: elevator_wait_2_1, elevator_ride_3_2, elevator_preride_1_1, elevator_transition_2_1
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^elevator_(?:wait|ride|preride|transition)_\\d+_(\\d+)$");
        java.util.regex.Matcher matcher = pattern.matcher(name);

        if (matcher.matches()) {
            String elevatorId = matcher.group(1);
            if (stateDebugger != null) {
                stateDebugger.logInfo("Extracted elevator ID: '" + elevatorId + "' from position: " + name);
            }
            return elevatorId;
        }

        if (stateDebugger != null) {
            stateDebugger.logInfo("Position name '" + name + "' is not an elevator point, no elevator ID extracted");
        }
        return null;
    }

    /**
     * Update the in-use elevator in SharedViewModel based on position
     */
    private void updateInUseElevator(Position position) {
        if (position == null) {
            return;
        }

        String elevatorId = extractElevatorIdFromPosition(position);
        if (elevatorId != null) {
            // Store in SharedViewModel
            sharedViewModel.setInUseElevator(elevatorId);
            if (stateDebugger != null) {
                stateDebugger.logInfo("✅ Set inUseElevator to: " + elevatorId + " from position: " + position.getName());
            }
        } else {
            // Clear if position is not an elevator point
            sharedViewModel.clearInUseElevator();
            if (stateDebugger != null) {
                stateDebugger.logInfo("Position is not an elevator point, inUseElevator unchanged");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    /**
     * Progress Listener Inner Class - Monitors route, position, and command completion
     */
    private class ProgressListener {
        private long lastCompletedPositionId = -1;
        private Set<Long> completedCommandIds = new HashSet<>();  // Track all completed commands
        private Set<Long> executedCommandIds = new HashSet<>();   // Track all executed commands
        private NavigationOrderStatus lastRouteStatus = NavigationOrderStatus.ORDER_RAW;
        private String currentGifType = GifTypeConstants.NAVIGATING_NORMAL;
        private String currentDescription = "";

        /**
         * Check and handle route-level completion
         */
        /**
         * Check and handle route-level completion
         */
        public void checkRouteCompletion() {
            if (currentRouteId == -1) return;

            // ⭐ Add this check - if maps are empty, reset state
            if (RouteStore.getAllRoutes().isEmpty()) {
                LOG.warn("Route maps are empty but currentRouteId is {}. Resetting state.", currentRouteId);
                synchronized (navigationLock) {
                    currentRouteId = -1;
                    currentRoute = null;
                    isTaskRunning = false;
                    currentTaskTarget = null;
                    currentCommandType = null;
                }
                return;
            }

            // Log current state before checking
            stateDebugger.dumpFullState("checkRouteCompletion - BEFORE");

            RouteStore route = RouteStore.getRoute(currentRouteId);
            if (route == null) {
                LOG.warn("Route {} not found in maps. Resetting state.", currentRouteId);
                synchronized (navigationLock) {
                    currentRouteId = -1;
                    currentRoute = null;
                    isTaskRunning = false;
                    currentTaskTarget = null;
                    currentCommandType = null;
                }
                return;
            }

            NavigationOrderStatus currentStatus = route.getRouteStatus();

            // ⭐ ADD DIAGNOSTIC LOGGING
            LOG.info("========== checkRouteCompletion DIAGNOSTICS ==========");
            LOG.info("Route ID: {}", currentRouteId);
            LOG.info("currentStatus: {}", currentStatus);
            LOG.info("lastRouteStatus: {}", lastRouteStatus);
            LOG.info("isTaskRunning: {}", isTaskRunning);
            LOG.info("currentStatus == ORDER_CANCELLED: {}",
                    currentStatus == NavigationOrderStatus.ORDER_CANCELLED);
            LOG.info("lastRouteStatus != currentStatus: {}",
                    lastRouteStatus != currentStatus);
            LOG.info("Condition for cancelled/failed: {}",
                    (currentStatus == NavigationOrderStatus.ORDER_FAILED ||
                            currentStatus == NavigationOrderStatus.ORDER_CANCELLED) &&
                            lastRouteStatus != currentStatus);
            LOG.info("====================================================");

            // Route just completed
            if (currentStatus == NavigationOrderStatus.ORDER_COMPLETED &&
                    lastRouteStatus != NavigationOrderStatus.ORDER_COMPLETED) {

                LOG.info("Route {} completed successfully", currentRouteId);
                // ⭐ Clear in-use elevator
                sharedViewModel.clearInUseElevator();
                if (stateDebugger != null) {
                    stateDebugger.logInfo("Route completed - cleared inUseElevator");
                }
                boolean finalTaskCompletion = isFinalTaskLoop();
                Position lastPos = !currentTaskRoute.isEmpty()
                        ? currentTaskRoute.get(currentTaskRoute.size() - 1) : null;
                TaskDebug8.log(String.format("[NAV] ROUTE_COMPLETED routeId=%d finalLoop=%b lastPos=%s type=%d taskType=%d",
                        currentRouteId, finalTaskCompletion,
                        lastPos != null ? lastPos.getName() : "null",
                        lastPos != null ? lastPos.getType() : -1,
                        lastPos != null ? lastPos.getTaskType() : -1));
                TaskDebug1.log(String.format("[NAV] ROUTE_COMPLETED routeId=%d finalLoop=%b lastPos=%s type=%d taskType=%d isTaskRunning->false",
                        currentRouteId, finalTaskCompletion,
                        lastPos != null ? lastPos.getName() : "null",
                        lastPos != null ? lastPos.getType() : -1,
                        lastPos != null ? lastPos.getTaskType() : -1));

                stateDebugger.logRouteStatusChange(route, lastRouteStatus, currentStatus);

                // 捕获当前导航代号（本导航创建时的代号），弹窗 onComplete 校验代号未变才执行完成回调
                final long completedEpoch = currentNavigationEpoch;

                executeOnMainThread(() -> {
                    // Stop GIF and play completion sound
//                    stopGifPlayback();
                    stopSound();
                    RouteStore.clearAllRoutes();
                    PositionMonitor.clearAllPositions();
                    CommandMonitor.clearAllCommands();
                    confirmationManager.clearAllStates();

                    LOG.info("Task completed, clear route/position/command, " +
                                    "current route map {}, position map {}, command map {} ",
                            gson.toJson(RouteStore.getAllRoutes()),
                            gson.toJson(PositionMonitor.getAllPositions()),
                            gson.toJson(CommandMonitor.getAllCommands()));
                    resetMonitor();

                    // Show completion dialog
                    if (!currentTaskRoute.isEmpty()) {
                        Position lastPosition = currentTaskRoute.get(currentTaskRoute.size() - 1);
                        showCompleteDialog(lastPosition, completedEpoch);
                    }
                });

                if (finalTaskCompletion && currentRecord != null) {
                    currentRecord.setStatus(TaskRecord.STATUS_COMPLETED);
                    notifyTaskRecordUpdate(currentRecord);
                }

                // Now safe to clear route ID
                synchronized (navigationLock) {
                    currentRouteId = -1;
                    currentRoute = null;
//                    currentTaskTarget = null;
                    currentCommandType = null;
                    isTaskRunning = false;
                }

                if (finalTaskCompletion) {
                    notifyTaskCompleted();
                }
            }

            // Route failed or cancelled
            else if ((currentStatus == NavigationOrderStatus.ORDER_FAILED ||
                    currentStatus == NavigationOrderStatus.ORDER_CANCELLED) &&
                    lastRouteStatus != currentStatus) {

                LOG.info("Route {} ended with status {}", currentRouteId, currentStatus);
                TaskDebug8.log(String.format("[NAV] ROUTE_END routeId=%d status=%s isTaskRunning->false", currentRouteId, currentStatus));
                TaskDebug1.log(String.format("[NAV] ROUTE_END routeId=%d status=%s isTaskRunning->false",
                        currentRouteId, currentStatus));

                // ⭐ Clear in-use elevator
                sharedViewModel.clearInUseElevator();
                if (stateDebugger != null) {
                    stateDebugger.logInfo("Route ended - cleared inUseElevator");
                }

                stateDebugger.logRouteStatusChange(route, lastRouteStatus, currentStatus);

                executeOnMainThread(() -> {
//                    stopGifPlayback();
                    stopSound();
                    RouteStore.clearAllRoutes();
                    PositionMonitor.clearAllPositions();
                    CommandMonitor.clearAllCommands();
                    confirmationManager.clearAllStates();
                    LOG.info("Task cancelled, clear route/position/command, " +
                                    "current route map {}, position map {}, command map {} ",
                            gson.toJson(RouteStore.getAllRoutes()),
                            gson.toJson(PositionMonitor.getAllPositions()),
                            gson.toJson(CommandMonitor.getAllCommands()));
                    resetMonitor();

                    if (currentStatus == NavigationOrderStatus.ORDER_FAILED) {
                        playVoicePrompt(VoiceKeyConstants.NAVIGATION_ERROR);
                        showToast(R.string.command_sequence_failed);
                        if (currentRecord != null) {
                            currentRecord.setStatus(TaskRecord.STATUS_FAILED);
                        }
                    } else if (currentStatus == NavigationOrderStatus.ORDER_CANCELLED) {
                        playVoicePrompt(VoiceKeyConstants.TASK_INTERRUPTED);
                        if (currentRecord != null) {
                            currentRecord.setStatus(TaskRecord.STATUS_CANCELLED);
                        }
                    }
                });

                if (currentRecord != null) {
                    notifyTaskRecordUpdate(currentRecord);
                }

                // Now safe to clear route ID
                synchronized (navigationLock) {
                    currentRouteId = -1;
                    currentRoute = null;
                    currentTaskTarget = null;
                    isTaskRunning = false;
                    currentCommandType = null;
                }

                if (currentStatus == NavigationOrderStatus.ORDER_FAILED) {
                    commandClient.cancelTask();
                    commandClient.setSoftScram(false);
                    notifyTaskFailed();
                } else {
                    notifyTaskCancelled();
                }
            }

            // ⭐ ADD ELSE CONDITION FOR DIAGNOSTICS - Log why neither condition was triggered
            else {
                LOG.warn("========== checkRouteCompletion - NO CONDITION TRIGGERED ==========");
                LOG.warn("Route ID: {}", currentRouteId);
                LOG.warn("currentStatus: {}", currentStatus);
                LOG.warn("lastRouteStatus: {}", lastRouteStatus);
                LOG.warn("isTaskRunning: {}", isTaskRunning);
                AgvStatusResponse statusSnapshot = latestStatusResponse;
                int gf = statusSnapshot != null && statusSnapshot.data != null ? statusSnapshot.data.goalFinish : -999;
                TaskDebug1.logThrottled("route_stuck_" + currentRouteId, 5000,
                        String.format("[NAV] route not complete routeId=%d status=%s lastStatus=%s taskRunning=%b goalFinish=%d routeSize=%d",
                                currentRouteId, currentStatus, lastRouteStatus, isTaskRunning, gf,
                                currentTaskRoute != null ? currentTaskRoute.size() : 0));
                // taskDebug8: 卡住时输出每个位置的状态，用于定位卡在哪个位置
                StringBuilder posDump = new StringBuilder("[NAV] STUCK routeId=" + currentRouteId
                        + " status=" + currentStatus + " lastStatus=" + lastRouteStatus
                        + " taskRunning=" + isTaskRunning + " goalFinish=" + gf);
                List<PositionMonitor> routePositions = getPositionsForRoute(currentRouteId);
                for (PositionMonitor pm : routePositions) {
                    Position p = pm.getPosition();
                    posDump.append(String.format(" | posId=%d name=%s st=%s type=%d tt=%d",
                            pm.getPositionId(),
                            p != null ? p.getName() : "null",
                            pm.getPositionStatus(),
                            p != null ? p.getType() : -1,
                            p != null ? p.getTaskType() : -1));
                    List<CommandMonitor> cmds = getCommandsForPosition(pm.getPositionId());
                    for (CommandMonitor cm : cmds) {
                        posDump.append(String.format(" cmd=%s st=%s",
                                cm.getCommandType(), cm.getCommandStatus()));
                    }
                }
                TaskDebug8.logThrottled("route_stuck_dump_" + currentRouteId, 5000, posDump.toString());

                // Check each condition individually
                boolean isCompleted = (currentStatus == NavigationOrderStatus.ORDER_COMPLETED);
                boolean isFailedOrCancelled = (currentStatus == NavigationOrderStatus.ORDER_FAILED ||
                        currentStatus == NavigationOrderStatus.ORDER_CANCELLED);
                boolean statusChanged = (lastRouteStatus != currentStatus);

                LOG.warn("Condition analysis:");
                LOG.warn("  - isCompleted: {}", isCompleted);
                LOG.warn("  - isFailedOrCancelled: {}", isFailedOrCancelled);
                LOG.warn("  - statusChanged: {}", statusChanged);
                LOG.warn("  - Would trigger COMPLETED: {}", isCompleted && statusChanged);
                LOG.warn("  - Would trigger CANCELLED/FAILED: {}", isFailedOrCancelled && statusChanged);

                // Special case: if route is cancelled but status didn't change (already was cancelled)
                if (isFailedOrCancelled && !statusChanged) {
                    LOG.warn("⚠️ Route is {} but lastRouteStatus is also {}. Status didn't change!",
                            currentStatus, lastRouteStatus);
                    LOG.warn("   This means the route was already in this state from before.");
                    LOG.warn("   Check if resetMonitor() is being called properly between navigations.");
                }

                // Check if task is not running but route has status
                if (!isTaskRunning && currentRouteId != -1) {
                    LOG.warn("⚠️ isTaskRunning is false but currentRouteId is {}. State inconsistency!",
                            currentRouteId);
                }

                LOG.warn("==================================================================");
            }

            lastRouteStatus = currentStatus;
            stateDebugger.dumpFullState("checkRouteCompletion - AFTER");
        }

        /**
         * Check and handle position-level completion
         */
        public void checkPositionCompletion() {
            if (currentRouteId == -1) return;

            List<PositionMonitor> positions = getPositionsForRoute(currentRouteId);

            for (int i = 0; i < positions.size(); i++) {
                PositionMonitor position = positions.get(i);
                NavigationOrderStatus oldStatus = position.getPositionStatus();

                // Position just completed
                if (position.getPositionStatus() == NavigationOrderStatus.ORDER_WAITING_CONFIRMATION &&
                        position.getPositionId() != lastCompletedPositionId) {

                    LOG.info("Position {} waiting for confirmation (index {})",
                            position.getPositionId(), position.getSequenceIndex());

                    stateDebugger.logPositionStatusChange(position, oldStatus,
                            NavigationOrderStatus.ORDER_WAITING_CONFIRMATION);

                    lastCompletedPositionId = position.getPositionId();

                    currentWaypointIndex = i;

                    Position arrivedPos = position.getPosition();
                    TaskDebug8.log(String.format("[NAV] POSITION_ARRIVED idx=%d/%d posId=%d name=%s type=%d taskType=%d floor=%s map=%s",
                            i, positions.size() - 1, position.getPositionId(),
                            arrivedPos != null ? arrivedPos.getName() : "null",
                            arrivedPos != null ? arrivedPos.getType() : -1,
                            arrivedPos != null ? arrivedPos.getTaskType() : -1,
                            arrivedPos != null ? arrivedPos.getFloor() : "null",
                            arrivedPos != null ? arrivedPos.getMapName() : "null"));

                    // Update task target for UI
                    updateCurrentTaskTarget(positions, i);

                    notifyWaypointArrived(i, position.getPosition());

                    // ⭐ CHECK THE NEXT POSITION (if exists) for elevator type
                    if (i + 1 < positions.size()) {
                        PositionMonitor nextPosition = positions.get(i + 1);
                        Position nextPos = nextPosition.getPosition();

                        // Update in-use elevator based on the next position
                        if (nextPos != null) {
                            String elevatorId = extractElevatorIdFromPosition(nextPos);
                            if (elevatorId != null) {
                                sharedViewModel.setInUseElevator(elevatorId);
                                if (stateDebugger != null) {
                                    stateDebugger.logInfo("✅ NEXT POSITION is elevator point - set inUseElevator to: " +
                                            elevatorId + " (position: " + nextPos.getName() + ")");
                                }
                                LOG.info("✅ NEXT POSITION is elevator point - set inUseElevator to: {} (position: {})",
                                        elevatorId, nextPos.getName());
                            } else {
                                // 下一位置不是电梯点位，说明已离开电梯（出电梯），清空 inUseElevator
                                sharedViewModel.clearInUseElevator();
                                if (stateDebugger != null) {
                                    stateDebugger.logInfo("🚪 NEXT POSITION is NOT elevator point - cleared inUseElevator (position: " +
                                            nextPos.getName() + ")");
                                }
                                LOG.info("🚪 NEXT POSITION is NOT elevator point - cleared inUseElevator (position: {})",
                                        nextPos.getName());
                            }
                        }
                    }

                    // ✅ 乘梯点音乐控制
                    Position currentPosition = position.getPosition();
                    if (currentPosition != null) {
                        // 到达乘梯点（type == 4）时停止音乐
                        if (currentPosition.getType() == 4) {
                            LOG.info("Arrived at elevator ride point, stopping music");
                            stopSound();
                            // 发送广播通知关闭电梯进入提示页面
                            Intent intent = new Intent("com.ezhan.amr.ACTION_ARRIVED_AT_ELEVATOR_RIDE_POINT");
                            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                            MyApplication.getInstance().getApplicationContext().sendBroadcast(intent);
                        }
                        // 离开乘梯点时恢复音乐（当前位置不是乘梯点，且上一个位置是乘梯点）
                        else if (i > 0) {
                            Position prevPosition = positions.get(i - 1).getPosition();
                            if (prevPosition != null && prevPosition.getType() == 4) {
                                LOG.info("Leaving elevator ride point, resuming music");
                                playRunningMusicIfNeeded(musicResId);
                            }
                        }
                    }

                    // Check if this is the last position
                    boolean isLastPosition = (i == positions.size() - 1);

                    // For cruise tasks (taskType=2), even the last position should show
                    // the continue dialog like other stations
                    boolean isCruiseTask = currentPosition != null && currentPosition.getTaskType() == 2;

                    if (!isLastPosition || isCruiseTask) {
                        TaskDebug1.log(String.format("[JACK] station arrived -> countdown posId=%d name=%s idx=%d/%d type=%d taskType=%d isLast=%b",
                                position.getPositionId(),
                                currentPosition != null ? currentPosition.getName() : "?",
                                i, positions.size() - 1,
                                currentPosition != null ? currentPosition.getType() : -1,
                                currentPosition != null ? currentPosition.getTaskType() : -1,
                                isLastPosition));
                        executeOnMainThread(() -> {
                            if (shouldPlayWaypointArrivalVoice(currentPosition)) {
                                playArrivalVoicePrompt(currentPosition);
                            }

                            // Determine dialog type based on task type
                            if (currentPosition.getTaskType() == 0 ||
                                    currentPosition.getTaskType() == 2 ||
                                    currentPosition.getTaskType() == 3 ||
                                    currentPosition.getTaskType() == 6) {
                                noShowContinueDialog(currentPosition);
                            } else {
                                showContinueDialog(currentPosition);
                            }
                        });
                    }
                }
            }
        }

        /**
         * Check and handle command-level completion
         */
        public void checkCommandCompletion() {
            if (currentRouteId == -1) return;

            // Get current executing position
            PositionMonitor currentPosition = getCurrentPositionForRoute(currentRouteId);
            if (currentPosition == null) return;

            List<CommandMonitor> commands = getCommandsForPosition(currentPosition.getPositionId());

            for (CommandMonitor command : commands) {
                NavigationOrderStatus oldStatus = command.getCommandStatus();

                // Command just completed
                if (command.getCommandStatus() == NavigationOrderStatus.ORDER_COMPLETED &&
                        !completedCommandIds.contains(command.getCommandId())) {

                    LOG.info("Command {} completed (type: {})",
                            command.getCommandId(), command.getCommandType());

                    stateDebugger.logCommandStatusChange(command, oldStatus,
                            NavigationOrderStatus.ORDER_COMPLETED);

                    // Add to completed set to prevent re-processing
                    completedCommandIds.add(command.getCommandId());

                    // Update GIF based on command type
//                    updateGifForCommand(command);

                    // Play sound if command requires it
                    if (shouldPlaySoundForCommand(command)) {
                        playRunningMusicIfNeeded(musicResId);
                    }
                }

                // Command just started executing
                else if (command.getCommandStatus() == NavigationOrderStatus.ORDER_EXECUTING &&
                        !executedCommandIds.contains(command.getCommandId())) {

                    LOG.info("Command {} started executing (type: {})",
                            command.getCommandId(), command.getCommandType());

                    stateDebugger.logCommandStatusChange(command, oldStatus,
                            NavigationOrderStatus.ORDER_EXECUTING);

                    currentCommandType = command.getCommandType();

                    // Add to executed set to prevent re-processing
                    executedCommandIds.add(command.getCommandId());

                    // ✅ 电梯移动命令的语音播报
                    if (command.getCommandType() == NavigationOrderType.OP_IN_ELEVATOR_MOVE) {
                        // TODO:从前置点去乘梯点 - 进电梯
//                        playVoicePrompt(VoiceKeyConstants.ENTERING_ELEVATOR);
                        // TODO: 启动电梯进入提示页面
//                        executeOnMainThread(() -> {
//                            Context context = MyApplication.getInstance().getApplicationContext();
//                            Intent intent = new Intent(context, com.ezhan.amr.ui.ElevatorEntryActivity.class);
//                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                            context.startActivity(intent);
//                        });
                    } else if (command.getCommandType() == NavigationOrderType.OP_OUT_ELEVATOR_MOVE) {
                        // TODO:从乘梯点去前置点 - 出电梯
//                        playVoicePrompt(VoiceKeyConstants.EXITING_ELEVATOR);
                    }

                    // Always start GIF when any command starts executing (including first)
                    Position targetPos = command.getTarget();
                    String gifType = getGifType(command.getCommandType(), targetPos != null ? targetPos.getType() : 0);
                    String description = buildCommandDescription(command);

                    executeOnMainThread(() -> {
                        startGifPlayback(gifType, description);
                    });

                    currentGifType = gifType;
                    currentDescription = description;
                }
            }
        }

        /**
         * Update the current task target for UI display
         * @param positions All positions in the route
         * @param currentIndex The index of the position that just completed (could be navigation point or logical station)
         */
        private void updateCurrentTaskTarget(List<PositionMonitor> positions, int currentIndex) {
            Set<Integer> excludedTypes = new HashSet<>(Arrays.asList(2, 3, 4, 10, 11, 12, 13, 14));

            // Use debugger for structured logging
            if (stateDebugger != null) {
                stateDebugger.log("========== updateCurrentTaskTarget START ==========");
                stateDebugger.log(String.format("currentIndex (position just completed): %d", currentIndex));
                stateDebugger.log(String.format("Current position type: %d",
                        positions.get(currentIndex).getPosition().getType()));
                stateDebugger.log(String.format("currentWaypointIndex (actual position index): %d", currentWaypointIndex));
                stateDebugger.log(String.format("currentEffectiveIndex (before): %d", currentEffectiveIndex));
            } else {
                LOG.info("========== updateCurrentTaskTarget START ==========");
                LOG.info("currentIndex (position just completed): {}", currentIndex);
                LOG.info("Current position type: {}", positions.get(currentIndex).getPosition().getType());
                LOG.info("currentWaypointIndex (actual position index): {}", currentWaypointIndex);
                LOG.info("currentEffectiveIndex (before): {}", currentEffectiveIndex);
            }

            // Find the NEXT logical station (delivery point) after currentIndex
            Position nextTaskTarget = null;
            int nextLogicalStationIndex = -1;

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Searching for next logical station starting from index: %d", currentIndex));
            } else {
                LOG.info("Searching for next logical station starting from index: {}", currentIndex);
            }

            for (int i = currentIndex; i < positions.size(); i++) {
                Position pos = positions.get(i).getPosition();
                boolean isExcluded = excludedTypes.contains(pos.getType());

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Position[%d]: '%s' (type=%d, excluded=%b)",
                            i, pos.getName(), pos.getType(), isExcluded));
                } else {
                    LOG.info("  Position[{}]: '{}' (type={}, excluded={})",
                            i, pos.getName(), pos.getType(), isExcluded);
                }

                if (!isExcluded) {
                    nextTaskTarget = pos;
                    nextLogicalStationIndex = i;
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("✅ Found next logical station at index %d: '%s'", i, pos.getName()));
                    } else {
                        LOG.info("✅ Found next logical station at index {}: '{}'", i, pos.getName());
                    }
                    break;
                }
            }

            if (nextTaskTarget != null && nextLogicalStationIndex != -1) {
                int previousEffectiveIndex = currentEffectiveIndex;

                // Update UI target (for display only)
                currentTaskTarget = nextTaskTarget;

                // ⭐ CRITICAL: Update effectiveIndex based on logical stations
                // We need to count how many logical stations we have completed to determine the new effectiveIndex
                int completedLogicalStationCount = 0;
                for (int i = 0; i <= currentIndex; i++) {
                    Position pos = positions.get(i).getPosition();
                    if (!excludedTypes.contains(pos.getType())) {
                        completedLogicalStationCount++;
                    }
                }

                // effectiveIndex = number of completed logical stations (0-indexed)
                // So if we've completed 1 logical station, effectiveIndex = 0 (we ARE at station 0)
                // If we've completed 2 logical stations, effectiveIndex = 1 (we ARE at station 1)
                int newEffectiveIndex = completedLogicalStationCount - 1;

                if (newEffectiveIndex != currentEffectiveIndex) {
                    currentEffectiveIndex = newEffectiveIndex;

                    String progressMsg = String.format(
                            "📊 EFFECTIVE INDEX UPDATED:\n" +
                                    "   Completed logical stations count: %d\n" +
                                    "   Old effectiveIndex: %d\n" +
                                    "   New effectiveIndex: %d\n" +
                                    "   Current station: %s",
                            completedLogicalStationCount, previousEffectiveIndex,
                            currentEffectiveIndex, nextTaskTarget.getName());

                    if (stateDebugger != null) {
                        stateDebugger.log(progressMsg);
                    } else {
                        LOG.info(progressMsg);
                    }

                    // Notify listeners of progress change
                    notifyEffectiveProgress(currentEffectiveIndex, currentTaskTarget);
                } else {
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("Effective index unchanged: %d", currentEffectiveIndex));
                    } else {
                        LOG.info("Effective index unchanged: {}", currentEffectiveIndex);
                    }
                }

                // Calculate total logical stations for display
                int totalLogicalStations = 0;
                for (int i = 0; i < positions.size(); i++) {
                    Position pos = positions.get(i).getPosition();
                    if (!excludedTypes.contains(pos.getType())) {
                        totalLogicalStations++;
                    }
                }

                String summaryMsg = String.format(
                        "Progress summary:\n" +
                                "  - Logical stations completed: %d/%d\n" +
                                "  - currentEffectiveIndex: %d/%d (0-indexed)\n" +
                                "  - Display progress: %d/%d\n" +
                                "  - Next task target: '%s'",
                        completedLogicalStationCount, totalLogicalStations,
                        currentEffectiveIndex, totalLogicalStations - 1,
                        currentEffectiveIndex + 1, totalLogicalStations,
                        nextTaskTarget.getName());

                if (stateDebugger != null) {
                    stateDebugger.log(summaryMsg);
                } else {
                    LOG.info(summaryMsg);
                }

            } else {
                String warningMsg = "No next logical station found - end of route reached";
                if (stateDebugger != null) {
                    stateDebugger.logWarning("updateCurrentTaskTarget", warningMsg);
                } else {
                    LOG.warn(warningMsg);
                }
            }

            String endMsg = String.format("updateCurrentTaskTarget END: effectiveIndex=%d, target='%s'",
                    currentEffectiveIndex, currentTaskTarget != null ? currentTaskTarget.getName() : "null");

            if (stateDebugger != null) {
                stateDebugger.log(endMsg);
                stateDebugger.log("================================================");
            } else {
                LOG.info(endMsg);
                LOG.info("================================================");
            }
        }

        /**
         * Update GIF based on command type
         */
        private void updateGifForCommand(CommandMonitor command) {
//            String gifType = getGifTypeForCommand(command.getCommandType());
            Position targetPos = command.getTarget();
            String gifType = getGifType(command.getCommandType(), targetPos.getType());;
            String description = buildCommandDescription(command);

            executeOnMainThread(() -> {
                startGifPlayback(gifType, description);
            });
        }

        /**
         * Build description for command GIF
         */
        private String buildCommandDescription(CommandMonitor command) {
            Set<Integer> excludedTypes = new HashSet<>(Arrays.asList(2, 3, 4, 10, 11, 12, 13, 14));

            Position targetPos = command.getTarget();
            if (command.getCommandType() == NavigationOrderType.OP_ELEVATOR_CALL
                    && targetPos != null && targetPos.getFloor() != null) {
                return targetPos.getFloor();
            }
            if (targetPos == null) {
                return "";
            }

            String navigateDescription = "";

            // Check if position type is excluded
            if (excludedTypes.contains(targetPos.getType())) {
                // For excluded types, use floor as messageTask
                if (targetPos.getTaskType() == 4 || targetPos.getTaskType() == 5
                        || targetPos.getTaskType() == 6) {
                    navigateDescription = targetPos.getMessage();
                } else {
                    targetPos.setMessage(targetPos.getFloor());
                    navigateDescription = targetPos.getMessage() + "|" +
                            (currentTaskTarget != null ? currentTaskTarget.getName() : "");
                }
                Log.d(TAG, "buildCommandDescription - excluded type " + targetPos.getType() +
                        " with description " + navigateDescription);
            } else {
                // For non-excluded types
                if (targetPos.getTaskType() != 2) {
                    navigateDescription = targetPos.getName();
                    Log.d(TAG, "buildCommandDescription - non-type 2: " + navigateDescription);
                } else {
                    navigateDescription = targetPos.getName() + targetPos.getMessage();
                    Log.d(TAG, "buildCommandDescription - type 2: " + navigateDescription);
                }
            }

            return navigateDescription;
        }

        /**
         * Determine if sound should play for command
         */
        private boolean shouldPlaySoundForCommand(CommandMonitor command) {
            // Play sound for navigation commands, but not for elevator/accessory commands
            return command.getCommandType() == NavigationOrderType.OP_FREE_MOVE ||
                    command.getCommandType() == NavigationOrderType.OP_FIXED_MOVE ||
                    command.getCommandType() == NavigationOrderType.OP_FORWARD ||
                    command.getCommandType() == NavigationOrderType.OP_BACKWARD ||
                    command.getCommandType() == NavigationOrderType.OP_IN_ELEVATOR_MOVE ||
                    command.getCommandType() == NavigationOrderType.OP_OUT_ELEVATOR_MOVE ||
                    command.getCommandType() == NavigationOrderType.OP_RECOGNIZE_LOAD ||
                    command.getCommandType() == NavigationOrderType.OP_RECOGNIZE_UNLOAD;
        }

        private void resetMonitor() {
            lastCompletedPositionId = -1;
            completedCommandIds.clear();  // Clear the set
            executedCommandIds.clear();   // Clear the set
            lastRouteStatus = NavigationOrderStatus.ORDER_RAW;
            currentGifType = GifTypeConstants.NAVIGATING_NORMAL;
            currentDescription = "";
        }

        /**
         * Remove a command ID from executedCommandIds
         * Used when recovery sequence completes and original command needs to be re-executed
         */
        public void removeFromExecutedCommandIds(long commandId) {
            executedCommandIds.remove(commandId);
            LOG.info("Removed command {} from executedCommandIds for re-execution", commandId);
        }
    }

    /**
     * Remove a command ID from executedCommandIds (public wrapper)
     * Used when recovery sequence completes and original command needs to be re-executed
     */
    public void removeFromExecutedCommandIds(long commandId) {
        if (progressListener != null) {
            progressListener.removeFromExecutedCommandIds(commandId);
        }
    }

    /**
     * Check progress and update UI
     */
    private void checkProgressAndUpdateUI() {
        if (isShuttingDown.get() || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        synchronized (navigationLock) {
            if (currentRouteId == -1 || !isTaskRunning) return;

            try {
                // Check all levels of completion
                progressListener.checkCommandCompletion();
                progressListener.checkPositionCompletion();
                progressListener.checkRouteCompletion();
            } catch (Exception e) {
                LOG.error("Error in progress monitoring: {}", e.getMessage());
            }
        }
    }
    // ---------------------------------------------------------------------------------------------
    public void startNavigation(List<Position> route, Runnable completionCallback) {
        synchronized (navigationLock) {
            navigationTransitionInProgress = true;
            // 新导航开始，代号自增，使上一个任务遗留的完成弹窗失效（onComplete 校验代号不匹配直接跳过）
            navigationEpoch++;
            try {
                returnStartNavigation(route, completionCallback);
            } finally {
                navigationTransitionInProgress = false;
            }
        }
    }

    private void returnStartNavigation(List<Position> route, Runnable completionCallback) {
            TaskDebug8.log(String.format("[NAV] startNavigation ENTER routeSize=%d isTaskRunning=%b currentRouteId=%d",
                    route != null ? route.size() : -1, isTaskRunning, currentRouteId));
            if (route != null) {
                StringBuilder sb = new StringBuilder("[NAV] input route:");
                for (int i = 0; i < route.size(); i++) {
                    Position p = route.get(i);
                    sb.append(String.format(" [%d]%s(t=%d,tt=%d,f=%s,m=%s)", i,
                            p != null ? p.getName() : "null",
                            p != null ? p.getType() : -1,
                            p != null ? p.getTaskType() : -1,
                            p != null ? p.getFloor() : "null",
                            p != null ? p.getMapName() : "null"));
                }
                TaskDebug8.log(sb.toString());
            }
            // ⭐ START OF NAVIGATION - LOG ALL CONFIGURATION VALUES USING DEBUGGER
            if (stateDebugger != null) {
                stateDebugger.logInfo("========== NAVIGATION START ==========");
                stateDebugger.logInfo("Timestamp: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date()));
            }

            refreshBasicSettings();

            // ⭐ LOG ALL BASIC SETTINGS AFTER REFRESH USING DEBUGGER
            if (stateDebugger != null) {
                stateDebugger.logInfo("📋 BASIC SETTINGS LOADED:");
                stateDebugger.logInfo("  ├─ setSpeed: " + setSpeed + " (DEFAULT: " + DEFAULT_SPEED + ")");
                stateDebugger.logInfo("  ├─ isVirtualOrbit: " + isVirtualOrbit);
                stateDebugger.logInfo("  ├─ virtualObstacleTime: " + virtualOrbitObstacleTime);
                stateDebugger.logInfo("  ├─ recognizeDistance: " + recognizeDistance + "m");
                stateDebugger.logInfo("  ├─ runningMusic: " + (runningMusic != null ? runningMusic : "null"));
                stateDebugger.logInfo("  └─ musicResId: " + musicResId + " (0 means not found)");
            }

            // Log if musicResId is 0 (resource not found)
            if (musicResId == 0 && stateDebugger != null) {
                stateDebugger.logWarning("Music resource not found", "Sound file '" + runningMusic + "' not found in resources");
            }

            // Cancel any existing navigation
            if (currentRouteId != -1) {
                TaskDebug1.log(String.format("[NAV] startNavigation cancel existing routeId=%d routeSize=%d",
                        currentRouteId, route.size()));
                if (stateDebugger != null) {
                    stateDebugger.logInfo("Cancelling existing navigation (routeId: " + currentRouteId + ")");
                }
                cancelNavigation(false);
            }

            taskCancelledByUser = false;
            lastOrphanChassisCancelTime = 0;

            // ✅ 确保重置异常管理器，防止旧的恢复序列影响新任务
            if (exceptionManager != null) {
                exceptionManager.reset();
            }

            diagnoseSchedulerStatus();

            // Only start scheduler if it's not already running and not shutting down
            if (!isSchedulerRunning.get() && !isShuttingDown.get()) {
                if (stateDebugger != null) {
                    stateDebugger.logInfo("Starting scheduler and progress monitoring");
                }
                startScheduledProcessing();
                startProgressMonitoring();
                schedulerStarted = true;
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logInfo("Scheduler already running or shutting down - running=" + isSchedulerRunning.get() +
                            ", shuttingDown=" + isShuttingDown.get());
                }
            }

            // Reset state
            resetState();
            taskStartTime = System.currentTimeMillis();
            if (stateDebugger != null) {
                stateDebugger.logInfo("Task start time recorded: " + taskStartTime);
            }

            // Validate route
            if (route.isEmpty()) {
                if (stateDebugger != null) {
                    stateDebugger.logError("Route is empty! Cannot start navigation");
                }
                showToast(R.string.task_location_empty);
                if (schedulerStarted) {
                    shutdownScheduler();
                    schedulerStarted = false;
                }
                return;
            }

            if (stateDebugger != null) {
                stateDebugger.logInfo("📋 ROUTE INFORMATION:");
                stateDebugger.logInfo("  ├─ Total positions in route: " + route.size());
                for (int i = 0; i < route.size(); i++) {
                    Position pos = route.get(i);
                    stateDebugger.logInfo(String.format("  │  Position[%d]: name='%s', type=%d, taskType=%d, taskId='%s'",
                            i, pos.getName() != null ? pos.getName() : "unnamed",
                            pos.getType(), pos.getTaskType(),
                            pos.getTaskId() != null ? pos.getTaskId() : "null"));
                }
            }

            // check if at charge position, if so, add precharge position into route;
            Position currentPosition = getCurrentPosition();
            int currentFloor = mapViewModel.getCurrentFloor();
            Context context = uiCallback.getContext();

            if (stateDebugger != null) {
                stateDebugger.logInfo("=== Charge Position Check ===");
                stateDebugger.logInfo("Current position: " + (currentPosition != null ?
                        String.format("x=%.2f, y=%.2f, floor=%s, name='%s'",
                                currentPosition.getPosX(),
                                currentPosition.getPosY(),
                                currentPosition.getFloor(),
                                currentPosition.getName()) : "null"));
                stateDebugger.logInfo("Current floor from ViewModel: " + currentFloor);
                stateDebugger.logInfo("Context: " + (context != null ? "available" : "null"));
            }

            boolean isAtCharge = false;
            boolean isInElevator = false;
            boolean isCharging = false;  // ⭐ NEW VARIABLE

            if (context != null && currentPosition != null) {
                if (stateDebugger != null) {
                    stateDebugger.logInfo("Calling checkIfAtChargePosition...");
                }
                isAtCharge = mapViewModel.checkIfAtChargePosition(currentPosition);
                if (stateDebugger != null) {
                    stateDebugger.logInfo("checkIfAtChargePosition returned: " + isAtCharge);
                }

                // ⭐ NEW: Check if robot is currently charging
                isCharging = isRobotCharging();
                if (stateDebugger != null) {
                    stateDebugger.logInfo("isRobotCharging returned: " + isCharging);
                    if (isCharging && latestStatusResponse != null && latestStatusResponse.data.chargeStatus != null) {
                        stateDebugger.logInfo("  └─ Charge state: " + latestStatusResponse.data.chargeStatus.state);
                        stateDebugger.logInfo("  └─ Power quantity: " + latestStatusResponse.data.chargeStatus.powerQuantity);
                    }
                }

                // Log position analysis
                Position updatedForElevatorCheck = elevatorViewModel.updatePositionTypeIfNearSpecialPoints(currentPosition);
                isInElevator = updatedForElevatorCheck != null && updatedForElevatorCheck.getType() == 4;
                logPositionAnalysis(currentPosition, currentFloor, isAtCharge, isInElevator);

                // ⭐ MODIFIED CONDITION: Check if at charge position OR currently charging
                if ((isAtCharge || isCharging) && !route.isEmpty() && route.get(0).getTaskType() != 5) {
                    String reason = isAtCharge ? "at charge position" : "currently charging";
                    int goalFinish = latestStatusResponse != null && latestStatusResponse.data != null
                            ? latestStatusResponse.data.goalFinish : -999;
                    double currentIn = latestStatusResponse != null && latestStatusResponse.data != null
                            ? latestStatusResponse.data.electricCurrentIn : -1;
                    int chargeState = latestStatusResponse != null && latestStatusResponse.data != null
                            && latestStatusResponse.data.chargeStatus != null
                            ? latestStatusResponse.data.chargeStatus.state : -999;
                    TaskDebug1.log(String.format(
                            "[SKIP1ST] precharge CHECK isAtCharge=%b isCharging=%b reason=%s goalFinish=%d chargeState=%d currentIn=%.2f routeSizeBefore=%d",
                            isAtCharge, isCharging, reason, goalFinish, chargeState, currentIn, route.size()));
                    if (stateDebugger != null) {
                        stateDebugger.logInfo("✅ Robot is " + reason + " - adding precharge position");
                        stateDebugger.logInfo("  ├─ isAtCharge: " + isAtCharge);
                        stateDebugger.logInfo("  └─ isCharging: " + isCharging);
                    }

                    Position prechargePosition = mapViewModel.getPreChargePosition(currentFloor);

                    if (prechargePosition != null) {
                        TaskDebug8.log(String.format("[PRECHARGE] FOUND floor=%d reason=%s pos=name=%s (%.3f,%.3f) floor=%s map=%s",
                                currentFloor, reason,
                                prechargePosition.getName(), prechargePosition.getPosX(), prechargePosition.getPosY(),
                                prechargePosition.getFloor(), prechargePosition.getMapName()));
                        if (stateDebugger != null) {
                            stateDebugger.logInfo("Precharge position found: x=" + prechargePosition.getPosX() +
                                    ", y=" + prechargePosition.getPosY() + ", floor=" + prechargePosition.getFloor());
                        }
                        prechargePosition.setName("PreCharge");
                        prechargePosition.setMessage(context.getString(R.string.navigating_to_task_point, "PreCharge"));
                        prechargePosition.setType(11);
                        prechargePosition.setTaskType(6);
                        route.add(0, prechargePosition);
                        TaskDebug1.log(String.format(
                                "[SKIP1ST] precharge INSERTED idx=0 name=PreCharge tt=6 firstJackIdx=1 firstJack=%s",
                                route.size() > 1 ? route.get(1).getName() : "none"));
                        if (stateDebugger != null) {
                            stateDebugger.logInfo("✅ Precharge position added to route at index 0");
                            stateDebugger.logInfo("📋 ROUTE AFTER PRECHARGE ADDITION:");
                            for (int i = 0; i < route.size(); i++) {
                                Position pos = route.get(i);
                                stateDebugger.logInfo(String.format("  Position[%d]: name='%s', type=%d, taskType=%d",
                                        i, pos.getName(), pos.getType(), pos.getTaskType()));
                            }
                            stateDebugger.log("Pre-charge position added to route: " + prechargePosition.getName() +
                                    " (reason: " + reason + ")");
                        }
                    } else {
                        TaskDebug8.log(String.format("[PRECHARGE] MISSING floor=%d reason=%s routeSize=%d",
                                currentFloor, reason, route.size()));
                        TaskDebug1.log(String.format(
                                "[SKIP1ST] precharge MISSING floor=%d reason=%s routeSize=%d",
                                currentFloor, reason, route.size()));
                        if (stateDebugger != null) {
                            stateDebugger.logWarning("Pre-charge position missing",
                                    "Floor: " + currentFloor + ", reason: " + reason);
                        }
                    }
                } else if (isCharging && !route.isEmpty() && route.get(0).getTaskType() == 5) {
                    // 充电状态下前往待命点：插入前进30cm过渡点（type=17），NavigationPositionManager会自动跳过确认
                    String reason = "currently charging";
                    TaskDebug8.log(String.format(
                            "[CHARGE_PARK] forward 30cm CHECK isAtCharge=%b isCharging=%b reason=%s routeSize=%d",
                            isAtCharge, isCharging, reason, route.size()));
                    if (stateDebugger != null) {
                        stateDebugger.logInfo("✅ Robot is " + reason + " heading to park - inserting forward 30cm waypoint");
                    }

                    if (currentPosition != null) {
                        Position standbyPoint = route.get(0);
                        String standbyName = standbyPoint.getName();
                        String standbyMessage = standbyPoint.getMessage();

                        double yaw = currentPosition.getYaw();
                        double forwardX = currentPosition.getPosX() + 0.3 * Math.cos(yaw);
                        double forwardY = currentPosition.getPosY() + 0.3 * Math.sin(yaw);

                        Position forwardPosition = new Position();
                        forwardPosition.setName(standbyName);
                        forwardPosition.setMessage(standbyMessage);
                        forwardPosition.setPosX(forwardX);
                        forwardPosition.setPosY(forwardY);
                        forwardPosition.setYaw(yaw);
                        forwardPosition.setFloor(String.valueOf(currentFloor));
                        forwardPosition.setMapName(currentPosition.getMapName());
                        forwardPosition.setType(17); // OP_FORWARD - NavigationPositionManager自动跳过确认
                        forwardPosition.setTaskType(0); // 非任务途径点 - UI立即继续无弹窗
                        route.add(0, forwardPosition);

                        TaskDebug8.log(String.format(
                                "[CHARGE_PARK] forward 30cm INSERTED idx=0 (%.3f,%.3f) -> (%.3f,%.3f) yaw=%.3f",
                                currentPosition.getPosX(), currentPosition.getPosY(), forwardX, forwardY, yaw));
                        if (stateDebugger != null) {
                            stateDebugger.logInfo("Forward 30cm inserted: (" +
                                    currentPosition.getPosX() + "," + currentPosition.getPosY() + ") -> (" +
                                    forwardX + "," + forwardY + ") yaw=" + yaw);
                        }
                    } else {
                        TaskDebug8.log("[CHARGE_PARK] forward 30cm SKIPPED - currentPosition is null");
                        if (stateDebugger != null) {
                            stateDebugger.logWarning("Forward 30cm skipped", "Current position is null");
                        }
                    }
                } else {
                    int gf8 = latestStatusResponse != null && latestStatusResponse.data != null
                            ? latestStatusResponse.data.goalFinish : -999;
                    int cs8 = latestStatusResponse != null && latestStatusResponse.data != null
                            && latestStatusResponse.data.chargeStatus != null
                            ? latestStatusResponse.data.chargeStatus.state : -999;
                    double ci8 = latestStatusResponse != null && latestStatusResponse.data != null
                            ? latestStatusResponse.data.electricCurrentIn : -1;
                    TaskDebug8.log(String.format(
                            "[PRECHARGE] SKIPPED isAtCharge=%b isCharging=%b routeSize=%d firstPos=%s tt=%d goalFinish=%d chargeState=%d currentIn=%.2f",
                            isAtCharge, isCharging, route.size(),
                            route.isEmpty() ? "none" : route.get(0).getName(),
                            route.isEmpty() ? -1 : route.get(0).getTaskType(),
                            gf8, cs8, ci8));
                    TaskDebug1.log(String.format(
                            "[SKIP1ST] precharge SKIPPED isAtCharge=%b isCharging=%b routeSize=%d firstPos=%s tt=%d",
                            isAtCharge, isCharging, route.size(),
                            route.isEmpty() ? "none" : route.get(0).getName(),
                            route.isEmpty() ? -1 : route.get(0).getTaskType()));
                    if (stateDebugger != null) {
                        stateDebugger.logInfo("❌ Robot NOT at charge position and NOT charging - no precharge added");
                        stateDebugger.logInfo("  ├─ isAtCharge: " + isAtCharge);
                        stateDebugger.logInfo("  └─ isCharging: " + isCharging);
                    }
                }
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning(
                            "Cannot check charge position",
                            String.format("Context null: %b, CurrentPosition null: %b",
                                    context == null, currentPosition == null)
                    );
                }
            }

            // check if start task from inside elevator
            currentPosition = elevatorViewModel.updatePositionTypeIfNearSpecialPoints(currentPosition);
            if (currentPosition != null && currentPosition.getType() == 4) {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("Task rejected - inside elevator",
                            String.format("Position type: %d, Position name: %s",
                                    currentPosition.getType(),
                                    currentPosition.getName() != null ? currentPosition.getName() : "unknown"));
                }
                playVoicePrompt(VoiceKeyConstants.INVALID_ELEVATOR_TASK);

                if (schedulerStarted) {
                    shutdownScheduler();
                    schedulerStarted = false;
                }
                return;
            }

            // ⭐ LOG ASSIGNED VALUES BEFORE APPLYING TO ROUTE
            if (stateDebugger != null) {
                stateDebugger.logInfo("⚙️ APPLYING CONFIGURATION TO ROUTE:");
                stateDebugger.logInfo("  ├─ Speed: " + setSpeed + " (will be applied to all positions)");
                stateDebugger.logInfo("  ├─ Virtual Orbit Mode: " + isVirtualOrbit);
                stateDebugger.logInfo("  └─ Recognize Distance: " + recognizeDistance + "m (for position type=5)");
            }

            // assign configured speed to each position in route
            if (stateDebugger != null) {
                stateDebugger.logInfo("⚙️ ASSIGNING SPEED TO EACH POSITION:");
            }
            for (int i = 0; i < route.size(); i++) {
                Position position = route.get(i);
                double oldSpeed = position.getSpeed();
                position.setSpeed(setSpeed);
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("  Position[%d]: name='%s', type=%d, speed: %.2f -> %.2f",
                            i,
                            position.getName() != null ? position.getName() : "unnamed",
                            position.getType(),
                            oldSpeed,
                            setSpeed));
                }
            }

            // assign configured navigation mode
            if (stateDebugger != null) {
                stateDebugger.logInfo("⚙️ ASSIGNING VIRTUAL ORBIT MODE TO EACH POSITION:");
            }
            for (int i = 0; i < route.size(); i++) {
                Position position = route.get(i);
                boolean oldVirtualOrbit = position.isVirtualOrbit();
                position.setVirtualOrbit(isVirtualOrbit);
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("  Position[%d]: name='%s', type=%d, virtualOrbit: %b -> %b",
                            i,
                            position.getName() != null ? position.getName() : "unnamed",
                            position.getType(),
                            oldVirtualOrbit,
                            isVirtualOrbit));
                }
            }

            // assign configured obstacle avoidance time
            if (stateDebugger != null) {
                stateDebugger.logInfo("⚙️ ASSIGNING VIRTUAL ORBIT OBSTACLE TIME TO EACH POSITION:");
            }
            for (int i = 0; i < route.size(); i++) {
                Position position = route.get(i);
                int oldVirtualObstacleTime = position.getVirtualOrbitObstacleTime();
                position.setVirtualOrbitObstacleTime(virtualOrbitObstacleTime);
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("  Position[%d]: name='%s', type=%d, virtualOrbitObstacleTime: %b -> %b",
                            i,
                            position.getName() != null ? position.getName() : "unnamed",
                            position.getType(),
                            oldVirtualObstacleTime,
                            virtualOrbitObstacleTime));
                }
            }

            // assign recognize distance for position of type 5
            if (stateDebugger != null) {
                stateDebugger.logInfo("⚙️ ASSIGNING RECOGNIZE DISTANCE (for type=5 positions):");
            }
            for (int i = 0; i < route.size(); i++) {
                Position position = route.get(i);
                if (position.getType() == 5 || position.getType() == 19) {
                    double oldRecognizeDistance = position.getRecognizeDistance();
                    position.setRecognizeDistance(recognizeDistance);
                    if (stateDebugger != null) {
                        stateDebugger.logInfo(String.format("  Position[%d]: name='%s', type=5, recognizeDistance: %.2f -> %.2f",
                                i,
                                position.getName() != null ? position.getName() : "unnamed",
                                oldRecognizeDistance,
                                recognizeDistance));
                    }
                }
            }

            // assign recognize distance for position of type 5
            route.forEach(position -> {
                if (position.getType() == 5 || position.getType() == 19) {
                    position.setRecognizeDistance(recognizeDistance);
                    if (stateDebugger != null) {
                        stateDebugger.logInfo(String.format("  ✓ Set recognizeDistance=%.2f for position '%s' (type=5/19)",
                                recognizeDistance, position.getName()));
                    }
                }
            });

            // ⭐ VERIFY VALUES WERE APPLIED CORRECTLY
            if (stateDebugger != null) {
                stateDebugger.logInfo("✅ CONFIGURATION VERIFICATION:");
                for (int i = 0; i < Math.min(route.size(), 5); i++) {
                    Position pos = route.get(i);
                    stateDebugger.logInfo(String.format("  Position[%d]: name='%s', speed=%.2f, virtualOrbit=%b, recognizeDistance=%.2f",
                            i, pos.getName(), pos.getSpeed(), pos.isVirtualOrbit(),
                            pos.getType() == 5 ? pos.getRecognizeDistance() : 0));
                }
                if (route.size() > 5) {
                    stateDebugger.logInfo("  ... and " + (route.size() - 5) + " more positions");
                }
            }

            // Create route in the manager system
            long robotId = 1; // Get from somewhere
            if (stateDebugger != null) {
                stateDebugger.logInfo("Creating route with robotId=" + robotId);
            }
            currentRouteId = routeManager.createRouteFromPositionList(currentPosition, route, robotId);
            currentNavigationEpoch = navigationEpoch; // 记录本导航代号，供完成弹窗过期校验
            currentRoute = RouteStore.getRoute(currentRouteId);
            currentTaskRoute = new ArrayList<>(route);
            // ⭐ Check if first position is an elevator point and update inUseElevator
            if (!currentTaskRoute.isEmpty()) {
                Position firstPosition = currentTaskRoute.get(0);
                updateInUseElevator(firstPosition);
                if (stateDebugger != null) {
                    stateDebugger.logInfo("First position processed for elevator: " +
                            (firstPosition != null ? firstPosition.getName() : "null"));
                }
            }
            this.completionCallback = completionCallback;
            isTaskRunning = true;

            StringBuilder routeSummary = new StringBuilder();
            for (int i = 0; i < route.size(); i++) {
                Position p = route.get(i);
                routeSummary.append(String.format("[%d]%s t=%d tt=%d ", i,
                        p.getName(), p.getType(), p.getTaskType()));
            }
            TaskDebug1.log(String.format("[NAV] startNavigation routeId=%d size=%d isTaskRunning=true %s",
                    currentRouteId, route.size(), routeSummary.toString().trim()));
            TaskDebug8.log(String.format("[NAV] route CREATED routeId=%d size=%d isTaskRunning=true %s",
                    currentRouteId, route.size(), routeSummary.toString().trim()));

            // ⭐ Initialize currentTaskTarget for UI display (fix: target showing "none" issue)
            // Find the first logical station in the route
            Set<Integer> excludedTypes = new HashSet<>(Arrays.asList(2, 3, 4, 10, 11, 12, 13, 14));
            for (Position pos : route) {
                if (!excludedTypes.contains(pos.getType())) {
                    currentTaskTarget = pos;
                    if (stateDebugger != null) {
                        stateDebugger.logInfo("✅ Initial currentTaskTarget set to: '" + pos.getName() + "'");
                    } else {
                        LOG.info("Initial currentTaskTarget set to: '{}'", pos.getName());
                    }
                    break;
                }
            }

            if (stateDebugger != null) {
                stateDebugger.logInfo("✅ Route created successfully:");
                stateDebugger.logInfo("  ├─ Route ID: " + currentRouteId);
                stateDebugger.logInfo("  ├─ Route status: " + (currentRoute != null ? currentRoute.getRouteStatus() : "null"));
                stateDebugger.logInfo("  ├─ Total positions in route: " + currentTaskRoute.size());
                stateDebugger.logInfo("  └─ Task running flag: " + isTaskRunning);
                stateDebugger.log(String.format(
                        "Route created: ID=%d, Positions=%d, StartPosition=%s",
                        currentRouteId, route.size(),
                        currentPosition != null ? currentPosition.getName() : "null"
                ));
            }

            // Create task record
            createTaskRecord(route);

            LOG.info("Navigation started with route ID: {}, {} positions",
                    currentRouteId, route.size());

            // ⭐ LOG FINAL SUMMARY BEFORE STARTING
            if (stateDebugger != null) {
                stateDebugger.logInfo("🎯 NAVIGATION START SUMMARY:");
                stateDebugger.logInfo(String.format("  ├─ Configuration: Speed=%.2f, VirtualOrbit=%b, RecognizeDistance=%.2fm",
                        setSpeed, isVirtualOrbit, recognizeDistance));
                stateDebugger.logInfo(String.format("  ├─ Audio: runningMusic='%s', musicResId=%d", runningMusic, musicResId));
                stateDebugger.logInfo(String.format("  ├─ Route: %d positions, RouteID=%d", route.size(), currentRouteId));
                stateDebugger.logInfo("  └─ Music will " + (musicResId != 0 ? "play" : "NOT play (resource missing)"));
                stateDebugger.logInfo("========== NAVIGATION START COMPLETE ==========");
            }

            // Notify UI
            playSound(musicResId);

            // Get the first position and its first command to start GIF
            Position firstPosition = route.get(0);
            List<PositionMonitor> positions = getPositionsForRoute(currentRouteId);
            if (!positions.isEmpty()) {
                PositionMonitor firstPositionMonitor = positions.get(0);
                List<CommandMonitor> commands = getCommandsForPosition(firstPositionMonitor.getPositionId());
                if (!commands.isEmpty()) {
                    CommandMonitor firstCommand = commands.get(0);
                    String gifType = getGifType(firstCommand.getCommandType(), firstPosition.getType());
                    String description = progressListener.buildCommandDescription(firstCommand);
                    startGifPlayback(gifType, description);
                } else {
                    // Fallback if no commands found
                    String gifType = getGifType(NavigationOrderType.OP_FREE_MOVE, firstPosition.getType());
                    String description = buildNavigateDescription(firstPosition);
                    startGifPlayback(gifType, description);
                }
            }
    }

    /**
     * Check if the robot is currently charging based on status data
     * @return true if robot is charging (chargestatu.state == 100)
     */
    /**
     * Check if the robot is currently charging based on status data
     * @return true if robot is charging (chargeStatus.state == 100)
     */
    private boolean isRobotCharging() {
        // Check from latestStatusResponse
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            if (latestStatusResponse.data.chargeStatus != null) {
                int chargeState = latestStatusResponse.data.chargeStatus.state;
                if (stateDebugger != null) {
                    stateDebugger.logInfo("isRobotCharging: chargeStatus.state = " + chargeState);
                }
                return chargeState == 100; // 100 means charging
            }
        }

        // Also check from command client cached status if available
        if (commandClient != null) {
            AgvStatusResponse cachedStatus = commandClient.getCachedStatus();
            if (cachedStatus != null && cachedStatus.data != null && cachedStatus.data.chargeStatus != null) {
                int chargeState = cachedStatus.data.chargeStatus.state;
                if (stateDebugger != null) {
                    stateDebugger.logInfo("isRobotCharging (cached): chargeStatus.state = " + chargeState);
                }
                return chargeState == 100;
            }
        }

        return false;
    }

    private void createTaskRecord(List<Position> selectedPositions) {
        Position firstPosition = selectTaskRecordPosition(selectedPositions);
        ExternalTaskContext externalTaskContext = pendingExternalTaskContext;
        pendingExternalTaskContext = null;
        int taskTypeType = firstPosition.getTaskType();
        int taskRecordType;
        String globalTaskType;

        switch (taskTypeType) {
            case 1: taskRecordType = TaskRecord.TYPE_DELIVERY; globalTaskType = "delivery"; break;
            case 2: taskRecordType = TaskRecord.TYPE_CRUISE; globalTaskType = "cruise"; break;
            case 3: taskRecordType = TaskRecord.TYPE_JACK; globalTaskType = "jack"; break;
            case 4: taskRecordType = TaskRecord.TYPE_CHARGE; globalTaskType = "charge"; break;
            case 5: taskRecordType = TaskRecord.TYPE_PARKING; globalTaskType = "park"; break;
            case 6: taskRecordType = TaskRecord.TYPE_STOP_CHARGE; globalTaskType = "stop_charge"; break;
            case 7: taskRecordType = TaskRecord.TYPE_CALL; globalTaskType = "call"; break;
            default: taskRecordType = TaskRecord.TYPE_DELIVERY; globalTaskType = "delivery"; break;
        }

        TaskViewModel.TaskState existingTaskState = taskViewModel != null
                ? taskViewModel.getCurrentTaskStateSnapshot()
                : null;
        String existingTaskId = existingTaskState != null ? existingTaskState.taskId : null;
        String existingTaskName = existingTaskState != null ? existingTaskState.taskName : null;
        String existingTaskType = existingTaskState != null ? existingTaskState.taskType : null;
        boolean hasExternalTaskInfo = existingTaskState != null
                && TaskViewModel.TASK_STATUS_EXECUTING.equals(existingTaskState.status)
                && globalTaskType.equals(existingTaskType)
                && existingTaskId != null
                && !existingTaskId.trim().isEmpty()
                && !TaskViewModel.TASK_INFO_NONE.equals(existingTaskId)
                && existingTaskName != null
                && !existingTaskName.trim().isEmpty()
                && !TaskViewModel.TASK_INFO_NONE.equals(existingTaskName);

        String taskId;
        String taskName;
        if (isUsableExternalTaskContext(externalTaskContext, globalTaskType)) {
            taskId = externalTaskContext.getTaskId();
            taskName = externalTaskContext.getTaskName();
        } else if (hasExternalTaskInfo) {
            taskId = existingTaskId.trim();
            taskName = existingTaskName.trim();
        } else if (hasUsableTaskRecordInfo(preCreatedTaskRecord)) {
            taskId = preCreatedTaskRecord.getTaskId().trim();
            taskName = preCreatedTaskRecord.getTaskName().trim();
        } else {
            taskId = firstPosition.getTaskId();
            if (taskId != null && !taskId.trim().isEmpty()) {
                taskId = taskId.trim();
                taskName = "RCS" + "-" + taskId.trim();
            } else {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                taskId = "HMI-" + timestamp;
                taskName = taskId;
            }
        }
        if (taskViewModel != null) {
            taskViewModel.markTaskExecuting(globalTaskType, taskName, taskId);
        }

        // ⭐ Log taskId for each position in the route
        NavigationStateDebugger debugger = NavigationStateDebugger.getInstance();
        debugger.logInfo("Creating task record for route with " + selectedPositions.size() + " positions:");
        for (int i = 0; i < selectedPositions.size(); i++) {
            Position pos = selectedPositions.get(i);
            String posTaskId = pos.getTaskId();
            String posName = pos.getName() != null ? pos.getName() : "Unnamed";
            debugger.logInfo(String.format("  Position[%d]: name='%s', taskId='%s', taskType=%d",
                    i, posName,
                    posTaskId != null ? posTaskId : "null",
                    pos.getTaskType()));
        }

        // Log the determined task name and type
        debugger.logInfo(String.format("Task record determined: taskName='%s', taskType=%d (from first position taskType=%d)",
                taskName, taskRecordType, taskTypeType));

        if (preCreatedTaskRecord != null) {
            // Use the pre-created task record
            this.currentRecord = preCreatedTaskRecord;
            this.currentRecord.start(); // Set start time
            this.currentRecord.setType(taskRecordType);
            this.currentRecord.setTaskId(taskId);
            this.currentRecord.setTaskName(taskName);
            preCreatedTaskRecord = null; // Clear after use
            debugger.logInfo("Using pre-created task record: " + currentRecord.getTaskName());
            Log.d(TAG, "Using pre-created task record: " + currentRecord.getTaskName());
        } else {
            this.currentRecord = new TaskRecord(taskRecordType, taskName);
            this.currentRecord.setTaskId(taskId);
            debugger.logInfo("Created new task record: " + currentRecord.getTaskName());
        }

        currentRecord.start();
        notifyTaskRecordAddition(currentRecord);

        // Log final confirmation
        debugger.logInfo("Task record started and notified: " + currentRecord.getTaskName());
    }

    private boolean isUsableExternalTaskContext(ExternalTaskContext context, String expectedTaskType) {
        return context != null
                && context.hasCompleteTaskInfo()
                && expectedTaskType != null
                && expectedTaskType.equalsIgnoreCase(context.getTaskType())
                && !TaskViewModel.TASK_INFO_NONE.equals(context.getTaskId())
                && !TaskViewModel.TASK_INFO_NONE.equals(context.getTaskName());
    }

    private boolean hasUsableTaskRecordInfo(TaskRecord taskRecord) {
        return taskRecord != null
                && taskRecord.getTaskId() != null
                && !taskRecord.getTaskId().trim().isEmpty()
                && taskRecord.getTaskName() != null
                && !taskRecord.getTaskName().trim().isEmpty()
                && !TaskViewModel.TASK_INFO_NONE.equals(taskRecord.getTaskId().trim())
                && !TaskViewModel.TASK_INFO_NONE.equals(taskRecord.getTaskName().trim());
    }

    private Position selectTaskRecordPosition(List<Position> positions) {
        Position fallbackPosition = positions.get(0);

        for (Position position : positions) {
            if (position != null
                    && position.getTaskType() != 0
                    && position.getTaskId() != null
                    && !position.getTaskId().trim().isEmpty()) {
                return position;
            }
        }

        for (Position position : positions) {
            if (position != null
                    && position.getTaskType() != 0
                    && position.getTaskType() != 6) {
                return position;
            }
        }

        for (Position position : positions) {
            if (position != null && position.getTaskType() != 0) {
                return position;
            }
        }

        return fallbackPosition;
    }

    /**
     * Build navigation description for GIF
     */
    /**
     * Build navigation description for GIF
     */
    private String buildNavigateDescription(Position position) {
        Set<Integer> excludedTypes = new HashSet<>(Arrays.asList(2, 3, 4, 10, 11, 12, 13, 14));

        Position targetPos = position;
        if (targetPos == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("buildNavigateDescription", "targetPos is null, returning empty string");
            }
            return "";
        }

        String navigateDescription = "";

        // Log input parameters
        if (stateDebugger != null) {
            stateDebugger.logInfo("========== buildNavigateDescription ==========");
            stateDebugger.logInfo(String.format("Position: name='%s', type=%d, taskType=%d, floor='%s'",
                    targetPos.getName() != null ? targetPos.getName() : "null",
                    targetPos.getType(),
                    targetPos.getTaskType(),
                    targetPos.getFloor() != null ? targetPos.getFloor() : "null"));
            stateDebugger.logInfo(String.format("Excluded types: %s", excludedTypes));
            if (currentTaskTarget != null) {
                stateDebugger.logInfo(String.format("currentTaskTarget: name='%s'", currentTaskTarget.getName()));
            } else {
                stateDebugger.logInfo("currentTaskTarget: null");
            }
        }

        // Check if position type is excluded
        if (excludedTypes.contains(targetPos.getType())) {
            // For excluded types, use floor as message
            if (stateDebugger != null) {
                stateDebugger.logInfo(String.format("Position type %d is EXCLUDED", targetPos.getType()));
            }

            if (targetPos.getTaskType() == 4 || targetPos.getTaskType() == 5 || targetPos.getTaskType() == 6) {
                navigateDescription = targetPos.getMessage();
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("TaskType %d (special) - using message: '%s'",
                            targetPos.getTaskType(), navigateDescription));
                }
            } else {
                targetPos.setMessage(targetPos.getFloor());
                navigateDescription = targetPos.getMessage() + "|" +
                        (currentTaskTarget != null ? currentTaskTarget.getName() : "");
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("Non-special taskType %d - using floor: '%s' + currentTaskTarget: '%s'",
                            targetPos.getTaskType(),
                            targetPos.getMessage(),
                            currentTaskTarget != null ? currentTaskTarget.getName() : "null"));
                    stateDebugger.logInfo(String.format("Result description: '%s'", navigateDescription));
                }
            }

            if (stateDebugger != null) {
                stateDebugger.logInfo(String.format("Excluded type description: '%s'", navigateDescription));
            }
        } else {
            // For non-excluded types
            if (stateDebugger != null) {
                stateDebugger.logInfo(String.format("Position type %d is NOT excluded", targetPos.getType()));
            }

            if (targetPos.getTaskType() != 2 && targetPos.getTaskType() != 3) {
                navigateDescription = targetPos.getName();
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("TaskType %d (non-2/3) - using name: '%s'",
                            targetPos.getTaskType(), navigateDescription));
                }
            } else {
                navigateDescription = targetPos.getName() + targetPos.getMessage();
                if (stateDebugger != null) {
                    stateDebugger.logInfo(String.format("TaskType %d (2 or 3) - using name+message: '%s' + '%s' = '%s'",
                            targetPos.getTaskType(),
                            targetPos.getName(),
                            targetPos.getMessage(),
                            navigateDescription));
                }
            }
        }

        return navigateDescription;
    }

    // ---------------------------------------------------------------------------------------------
    // ========== UI Helper Methods (from original) ==========

    public void playVoicePrompt(String key) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onVoicePrompt(key);
        });
    }

    private void playArrivalVoicePrompt(Position target) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onArrivalVoicePrompt(target);
        });
    }

    private void playSound(int resource) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onPlaySound(resource);
        });
    }

    private void playRunningMusicIfNeeded(int resource) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onPlayRunningMusicIfNeeded(resource);
        });
    }

    private void stopSound() {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onStopSound();
        });
    }

    private void pauseSound() {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onPauseSound();
        });
    }

    private void startGifPlayback(String gifType, String description) {
        executeOnMainThread(() -> {
            if (uiCallback != null) {
                isGifPlaying = true;
                uiCallback.onGifPlayback(true, gifType, description);
            }
        });
    }

    public void startGifPlaybackPublic(String gifType, String navigateDescription) {
        startGifPlayback(gifType, navigateDescription);
    }

    public void stopGifPlayback() {
        executeOnMainThread(() -> {
            isGifPlaying = false;
            if (uiCallback != null) {
                uiCallback.onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");
            }
        });
    }

    private void showToast(int resId) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onShowToast(resId);
        });
    }

    private void showToast(String message) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onShowToast(message);
        });
    }

    private void notifyTaskRecordAddition(TaskRecord record) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onTaskRecordAddition(record);
        });
    }

    private void notifyTaskRecordUpdate(TaskRecord record) {
        executeOnMainThread(() -> {
            if (uiCallback != null) uiCallback.onTaskRecordUpdate(record);
        });
    }

    private void executeOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    /**
     * Show continue dialog (from original)
     */
    private void showContinueDialog(Position target) {
        long positionId = getCurrentPositionId();

        if (stateDebugger != null) {
            stateDebugger.log("========== showContinueDialog (Handler) ==========");
            stateDebugger.log(String.format("positionId: %d", positionId));
            stateDebugger.log(String.format("target: %s", target != null ? target.getName() : "null"));
            stateDebugger.log(String.format("target taskType: %d", target != null ? target.getTaskType() : -1));
        }

        executeOnMainThread(() -> {
            if (uiCallback != null) {
                // ⭐ CRITICAL FIX: Capture position name before creating callback
                final String targetName = target != null ? target.getName() : "unknown";
                final long capturedPositionId = positionId;

                if (stateDebugger != null) {
                    stateDebugger.log("Calling uiCallback.onShowContinueDialog");
                }

                uiCallback.onShowContinueDialog(
                        false,
                        target,
                        () -> {
                            synchronized (navigationLock) {
                                setWaitingForUserConfirmation(false);
                                if (stateDebugger != null) {
                                    stateDebugger.log(String.format("Position %d confirmed, moving to next position", capturedPositionId));
                                }
                                if (currentRecord != null) {
                                    if (stateDebugger != null) {
                                        stateDebugger.log("Marking position as COMPLETED");
                                        // Use captured name
                                        stateDebugger.log("✅ Position completed: " + targetName);
                                        printStationDetails();
                                    }
                                }
                                playRunningMusicIfNeeded(musicResId);
                            }
                        },
                        () -> {
                            setWaitingForUserConfirmation(false);
                            cancelCurrentTask();
                            if (stateDebugger != null) {
                                stateDebugger.log(String.format("Position %d confirmation cancelled", capturedPositionId));
                            }
                        }
                );
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("showContinueDialog", "uiCallback is null, cannot show dialog");
                }
            }
        });
    }

    /**
     * Show no-confirmation continue dialog (from original)
     */
    private void noShowContinueDialog(Position target) {
        long positionId = getCurrentPositionId();

        TaskDebug1.log(String.format("[JACK] noShowContinueDialog posId=%d name=%s taskType=%d type=%d",
                positionId, target != null ? target.getName() : "null",
                target != null ? target.getTaskType() : -1,
                target != null ? target.getType() : -1));

        if (stateDebugger != null) {
            stateDebugger.log("========== noShowContinueDialog (Handler) ==========");
            stateDebugger.log(String.format("positionId: %d", positionId));
            stateDebugger.log(String.format("target: %s", target != null ? target.getName() : "null"));
            stateDebugger.log(String.format("target taskType: %d", target != null ? target.getTaskType() : -1));
        }

        executeOnMainThread(() -> {
            if (uiCallback != null) {
                // ⭐ CRITICAL FIX: Capture position name and task type BEFORE creating callback
                final String targetName = target != null ? target.getName() : "unknown";
                final int targetTaskType = target != null ? target.getTaskType() : -1;
                final long capturedPositionId = positionId;

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Calling uiCallback.onNoShowContinueDialog with target: %s", targetName));
                }

                uiCallback.onNoShowContinueDialog(
                        false,
                        target,  // Pass the target as is, but don't rely on it in the callback
                        () -> {
                            synchronized (navigationLock) {
                                setWaitingForUserConfirmation(false);
                                TaskDebug1.log(String.format("[JACK] countdown confirmed posId=%d name=%s -> next position",
                                        capturedPositionId, targetName));
                                if (stateDebugger != null) {
                                    stateDebugger.log(String.format("Position %d auto-confirmed", capturedPositionId));
                                }

                                if (currentRecord != null) {
                                    if (stateDebugger != null) {
                                        stateDebugger.log("Marking position as COMPLETED");
                                        // Use captured name instead of accessing target
                                        stateDebugger.log("✅ Position completed: " + targetName);
                                        printStationDetails();
                                    }
                                }
                            }
                        },
                        () -> {
                            setWaitingForUserConfirmation(false);
                            cancelCurrentTask();
                            if (stateDebugger != null) {
                                stateDebugger.log(String.format("Position %d auto-confirmation cancelled", capturedPositionId));
                            }
                        }
                );
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("noShowContinueDialog", "uiCallback is null, cannot auto-continue");
                }
            }
        });
    }

    private void showCompleteDialog(Position lastPosition, long completedEpoch) {
        TaskDebug1.log(String.format("[NAV] showCompleteDialog lastPos=%s type=%d taskType=%d activity=%s epoch=%d",
                lastPosition != null ? lastPosition.getName() : "null",
                lastPosition != null ? lastPosition.getType() : -1,
                lastPosition != null ? lastPosition.getTaskType() : -1,
                uiCallback != null ? uiCallback.getClass().getSimpleName() : "null",
                completedEpoch));

        if (stateDebugger != null) {
            stateDebugger.log("========== showCompleteDialog (Handler) ==========");
            stateDebugger.log(String.format("Last position: %s, TaskType: %d",
                    lastPosition != null ? lastPosition.getName() : "null",
                    lastPosition != null ? lastPosition.getTaskType() : -1));
        }

        // Check if this is a Jack task or Cruise task
        boolean isJackTask = lastPosition != null && lastPosition.getTaskType() == 3;
        boolean isCruiseTask = lastPosition != null && lastPosition.getTaskType() == 2;

        // Compute whether to suppress voice prompts
        boolean shouldSuppressVoice = false;

        if (isJackTask) {
            boolean isFinalLoop = isFinalJackLoop();
            shouldSuppressVoice = !isFinalLoop;

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Jack task: loopIndex=%d, totalLoops=%d, isFinalLoop=%b, shouldSuppressVoice=%b",
                        currentJackLoopIndex, currentJackTotalLoops, isFinalLoop, shouldSuppressVoice));
            }
        } else if (isCruiseTask) {
            boolean isFinalLoop = isFinalCruiseLoop();
            shouldSuppressVoice = !isFinalLoop;

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Cruise task: loopIndex=%d, totalLoops=%d, isFinalLoop=%b, shouldSuppressVoice=%b",
                        currentCruiseLoopIndex, currentCruiseTotalLoops, isFinalLoop, shouldSuppressVoice));
            }
        }

        final boolean suppressVoice = shouldSuppressVoice;
        final boolean isParkPoint = lastPosition != null && lastPosition.getType() == 12;

        // 驻车点完成时，先等待机器人停稳（movement==0）再弹窗
        if (isParkPoint) {
            waitForRobotStoppedThenShowDialog(lastPosition, completedEpoch, suppressVoice, isCruiseTask);
            return;
        }

        executeOnMainThread(() -> {
            if (uiCallback == null) {
                if (stateDebugger != null) {
                    stateDebugger.logError("showCompleteDialog", "uiCallback is null");
                }
                return;
            }

            boolean shouldPlayArrivalVoice = !suppressVoice || shouldPlayWaypointArrivalVoice(lastPosition);

            // For cruise tasks, arrival voice was already played during checkPositionCompletion
            // and the countdown dialog. Skip it here to avoid duplicate.
            if (isCruiseTask) {
                shouldPlayArrivalVoice = false;
            }

            if (shouldPlayArrivalVoice) {
                playArrivalVoicePrompt(lastPosition);
            } else if (stateDebugger != null) {
                String taskType = isJackTask ? "Jack" : (isCruiseTask ? "Cruise" : "unknown");
                stateDebugger.log(String.format("Suppressing ARRIVAL voice prompt for %s task intermediate loop", taskType));
            }

            uiCallback.onShowCompleteDialog(
                    true,
                    lastPosition,
                    () -> {
                        // 代号校验：若期间有新的导航开始（代号已变），说明本弹窗属于已完成的旧任务，
                        // 此时 completionCallback 已被新任务覆盖，必须跳过，避免误触发新任务的完成回调
                        if (currentNavigationEpoch != completedEpoch) {
                            TaskDebug8.log(String.format("[NAV] STALE completion dialog epoch=%d current=%d -> skip",
                                    completedEpoch, currentNavigationEpoch));
                            if (stateDebugger != null) {
                                stateDebugger.logWarning("showCompleteDialog",
                                        String.format("Stale completion dialog (epoch %d != current %d), skipping",
                                                completedEpoch, currentNavigationEpoch));
                            }
                            return;
                        }
                        if (stateDebugger != null) {
                            stateDebugger.log("showCompleteDialog: ON COMPLETE CALLBACK STARTED");
                        }

                        synchronized (navigationLock) {
                            if (!suppressVoice && currentRecord != null) {
                                if (stateDebugger != null) {
                                    stateDebugger.log("Marking task as COMPLETED");
                                }
                                currentRecord.finish(TaskRecord.STATUS_COMPLETED);
                                notifyTaskRecordUpdate(currentRecord);
                                if (stateDebugger != null) {
                                    stateDebugger.log("✅ Task completed: " + currentRecord.getTaskName());
                                    printStationDetails();
                                }
                            }

                            if (!suppressVoice && currentTaskRoute != null) {
                                currentTaskRoute.clear();
                            }

                            if (!suppressVoice) {
                                currentWaypointIndex = -1;
                                currentEffectiveIndex = -1;
                                currentRecord = null;
                            }
                        }

                        if (!suppressVoice) {
                            currentTaskTarget = null;
                            notifyTaskCompleted();
                        }
                        currentCommandType = null;
                        // Only play task completed voice prompt if not suppressed
                        if (!suppressVoice) {
                            playVoicePrompt(VoiceKeyConstants.TASK_COMPLETED);
                        } else if (stateDebugger != null) {
                            String taskType = isJackTask ? "Jack" : (isCruiseTask ? "Cruise" : "unknown");
                            stateDebugger.log(String.format("Suppressing TASK_COMPLETED voice prompt for %s task intermediate loop", taskType));
                        }

                        if (completionCallback != null) {
                            completionCallback.run();
                        }

                        if (stateDebugger != null) {
                            stateDebugger.log("showCompleteDialog: ON COMPLETE CALLBACK FINISHED");
                        }
                    },
                    () -> {
                        if (stateDebugger != null) {
                            stateDebugger.log("showCompleteDialog: Cancel callback triggered");
                        }
                    }
            );
        });
    }

    /**
     * 驻车点导航完成后，等待机器人真正到达待命点并停稳，再弹出完成对话框。
     * 条件：movement==0 + 线速度/角速度接近0 + 位置在待命点附近(0.3m)
     * 满足条件后还需持续稳定3秒才弹窗，防止机器人短暂停顿后继续移动。
     */
    private void waitForRobotStoppedThenShowDialog(Position lastPosition, long completedEpoch,
                                                    boolean suppressVoice, boolean isCruiseTask) {
        final int POLL_INTERVAL_MS = 200;
        final int MAX_WAIT_MS = 15000;
        final double SPEED_THRESHOLD = 0.02;
        final double DIST_THRESHOLD = 0.3;
        final long[] startTime = {0};
        final Handler pollHandler = new Handler(Looper.getMainLooper());

        final double parkX = lastPosition != null ? lastPosition.getPosX() : 0;
        final double parkY = lastPosition != null ? lastPosition.getPosY() : 0;

        TaskDebug1.log(String.format("[PARK] waitForRobotStoppedThenShowDialog: start polling park=(%.3f,%.3f)", parkX, parkY));

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (startTime[0] == 0) {
                    startTime[0] = System.currentTimeMillis();
                }

                long elapsed = System.currentTimeMillis() - startTime[0];

                if (latestStatusResponse == null || latestStatusResponse.data == null) {
                    if (elapsed < MAX_WAIT_MS) {
                        pollHandler.postDelayed(this, POLL_INTERVAL_MS);
                        return;
                    }
                    TaskDebug1.log("[PARK] TIMEOUT: no status data, showing dialog anyway");
                    showCompleteDialogInternal(lastPosition, completedEpoch, suppressVoice, isCruiseTask);
                    return;
                }

                int movement = latestStatusResponse.data.movement;
                float linearSpeed = latestStatusResponse.data.agvSpeed != null
                        ? Math.abs(latestStatusResponse.data.agvSpeed.x) : 999;
                float angularSpeed = latestStatusResponse.data.agvSpeed != null
                        ? Math.abs(latestStatusResponse.data.agvSpeed.t) : 999;

                double robotX = 0, robotY = 0;
                if (latestStatusResponse.data.pos != null) {
                    robotX = latestStatusResponse.data.pos.x;
                    robotY = latestStatusResponse.data.pos.y;
                }
                double distToPark = Math.sqrt((robotX - parkX) * (robotX - parkX)
                        + (robotY - parkY) * (robotY - parkY));

                boolean stopped = movement == 0 && linearSpeed < SPEED_THRESHOLD && angularSpeed < SPEED_THRESHOLD;
                boolean atPark = distToPark < DIST_THRESHOLD;
                boolean allConditionsMet = stopped && atPark;

                // 超时：无论条件如何都弹窗
                if (elapsed >= MAX_WAIT_MS) {
                    TaskDebug1.log(String.format("[PARK] TIMEOUT: movement=%d speed=(%.3f,%.3f) dist=%.3f elapsed=%dms",
                            movement, linearSpeed, angularSpeed, distToPark, elapsed));
                    showCompleteDialogInternal(lastPosition, completedEpoch, suppressVoice, isCruiseTask);
                    return;
                }

                // 条件不满足：继续轮询
                if (!allConditionsMet) {
                    TaskDebug1.log(String.format("[PARK] waiting: movement=%d speed=(%.3f,%.3f) dist=%.3f stopped=%b atPark=%b elapsed=%dms",
                            movement, linearSpeed, angularSpeed, distToPark, stopped, atPark, elapsed));
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS);
                    return;
                }

                // 条件满足，立即弹出完成对话框
                TaskDebug1.log(String.format("[PARK] READY: movement=%d speed=(%.3f,%.3f) dist=%.3f elapsed=%dms",
                        movement, linearSpeed, angularSpeed, distToPark, elapsed));
                showCompleteDialogInternal(lastPosition, completedEpoch, suppressVoice, isCruiseTask);
            }
        };

        pollHandler.post(pollRunnable);
    }

    /**
     * showCompleteDialog 的内部实现，被 showCompleteDialog 和 waitForRobotStoppedThenShowDialog 共用
     */
    private void showCompleteDialogInternal(Position lastPosition, long completedEpoch,
                                             boolean suppressVoice, boolean isCruiseTask) {
        boolean isJackTask = lastPosition != null && lastPosition.getTaskType() == 3;

        executeOnMainThread(() -> {
            if (uiCallback == null) {
                if (stateDebugger != null) {
                    stateDebugger.logError("showCompleteDialogInternal", "uiCallback is null");
                }
                return;
            }

            boolean shouldPlayArrivalVoice = !suppressVoice || shouldPlayWaypointArrivalVoice(lastPosition);

            if (isCruiseTask) {
                shouldPlayArrivalVoice = false;
            }

            if (shouldPlayArrivalVoice) {
                playArrivalVoicePrompt(lastPosition);
            }

            uiCallback.onShowCompleteDialog(
                    true,
                    lastPosition,
                    () -> {
                        if (currentNavigationEpoch != completedEpoch) {
                            TaskDebug8.log(String.format("[NAV] STALE completion dialog epoch=%d current=%d -> skip",
                                    completedEpoch, currentNavigationEpoch));
                            return;
                        }

                        synchronized (navigationLock) {
                            if (!suppressVoice && currentRecord != null) {
                                currentRecord.finish(TaskRecord.STATUS_COMPLETED);
                                notifyTaskRecordUpdate(currentRecord);
                            }

                            if (!suppressVoice && currentTaskRoute != null) {
                                currentTaskRoute.clear();
                            }

                            if (!suppressVoice) {
                                currentWaypointIndex = -1;
                                currentEffectiveIndex = -1;
                                currentRecord = null;
                            }
                        }

                        if (!suppressVoice) {
                            currentTaskTarget = null;
                            notifyTaskCompleted();
                        }
                        currentCommandType = null;

                        if (!suppressVoice) {
                            playVoicePrompt(VoiceKeyConstants.TASK_COMPLETED);
                        }

                        if (completionCallback != null) {
                            completionCallback.run();
                        }
                    },
                    () -> {}
            );
        });
    }

    /**
     * 已在待命点时直接弹出完成对话框，不经过导航流程
     */
    public void showParkCompletionDialog(Position parkPoint, Runnable completionCallback) {
        TaskDebug1.log(String.format("[PARK] showParkCompletionDialog point=%s type=%d",
                parkPoint != null ? parkPoint.getName() : "null",
                parkPoint != null ? parkPoint.getType() : -1));
        executeOnMainThread(() -> {
            if (uiCallback == null) {
                TaskDebug1.log("[PARK] showParkCompletionDialog uiCallback=null, abort");
                return;
            }
            uiCallback.onShowCompleteDialog(true, parkPoint, () -> {
                if (completionCallback != null) {
                    completionCallback.run();
                }
            }, () -> {});
        });
    }

    private boolean shouldPlayWaypointArrivalVoice(Position position) {
        if (position == null) {
            return false;
        }
        int taskType = position.getTaskType();
        return taskType == 1 || taskType == 2 || taskType == 3;
    }

    private boolean isFinalTaskLoop() {
        Position lastPosition = currentTaskRoute != null && !currentTaskRoute.isEmpty()
                ? currentTaskRoute.get(currentTaskRoute.size() - 1)
                : null;
        if (lastPosition == null) {
            return true;
        }
        if (lastPosition.getTaskType() == 2) {
            return isFinalCruiseLoop();
        }
        if (lastPosition.getTaskType() == 3) {
            return isFinalJackLoop();
        }
        return true;
    }

    private boolean isFinalCruiseLoop() {
        return currentCruiseTotalLoops != -1
                && currentCruiseLoopIndex >= currentCruiseTotalLoops;
    }

    private boolean isFinalJackLoop() {
        return currentJackTotalLoops != -1
                && currentJackLoopIndex >= currentJackTotalLoops;
    }

    // ⭐⭐⭐ CORRECTED HELPER METHOD TO PRINT STATION DETAILS WITH NEW FIELDS ⭐⭐⭐
    private void printStationDetails() {
        if (currentRecord == null) {
            String msg = "No current task record to print";
            Log.d("TaskRecord", msg);
            stateDebugger.log(msg);
            return;
        }

        // Use the debugger to print full details
        stateDebugger.printStationDetails(currentRecord);
    }

    // ⭐⭐⭐ ADD HELPER METHODS FOR FORMATTING ⭐⭐⭐
    private String formatTimestamp(long timestamp) {
        if (timestamp == 0) {
            return "Not recorded";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * Cancel current task (from original)
     */
    private void cancelCurrentTask() {
        if (isTaskRunning) {
            cancellationManager.cancelTasksByRoute(currentRouteId,
                    NavigationOrderStatus.ORDER_CANCELLED);

            commandClient.setSoftScram(false);
            if (uiCallback != null) {
                uiCallback.onGlideResume();
            }
            stopSound();
            commandClient.cancelTask();
            stopGifPlayback();
            playVoicePrompt(VoiceKeyConstants.TASK_INTERRUPTED);

            if (currentRecord != null) {
                currentRecord.finish(TaskRecord.STATUS_CANCELLED);
                notifyTaskRecordUpdate(currentRecord);
            }

            LOG.info("Task cancellation requested - waiting for scheduler to process");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ========== Helper Methods ==========

    private String getGifType(NavigationOrderType commandType, int positionType) {
        // For move commands, use positionType to distinguish
        if (commandType == NavigationOrderType.OP_FREE_MOVE ||
                commandType == NavigationOrderType.OP_FIXED_MOVE ||
                commandType == NavigationOrderType.OP_FORWARD ||
                commandType == NavigationOrderType.OP_BACKWARD ||
                commandType == NavigationOrderType.OP_IN_ELEVATOR_MOVE ||
                commandType == NavigationOrderType.OP_OUT_ELEVATOR_MOVE ||
                commandType == NavigationOrderType.OP_CHARGE_START ||
                commandType == NavigationOrderType.OP_CHARGE_STOP) {
            switch (positionType) {
                case 3: return GifTypeConstants.NAVIGATING_WAIT;
                case 4: return GifTypeConstants.NAVIGATING_RIDE;
                case 10: return GifTypeConstants.NAVIGATING_CHARGE;
                case 11: return GifTypeConstants.NAVIGATING_PRECHARGE;
                case 12: return GifTypeConstants.NAVIGATING_PARK;
                default: return GifTypeConstants.NAVIGATING_NORMAL;
            }
        }

        // For non-move commands, use commandType to distinguish
        switch (commandType) {
            case OP_ELEVATOR_CALL:
                return GifTypeConstants.CALLING_ELEVATOR;
            case OP_ELEVATOR_CHECK:
            case OP_ELEVATOR_DOOR_CHECK:
            case OP_ELEVATOR_ARRIVAL_DOOR_CHECK:
                return GifTypeConstants.WAITING_FOR_ELEVATOR;
            case OP_ELEVATOR_OPEN:
                return GifTypeConstants.OPENING_DOOR;
            case OP_ELEVATOR_CANCEL_OPEN:
                return GifTypeConstants.CLOSING_DOOR;
            case OP_CHANGE_MAP:
                return GifTypeConstants.CHANGING_MAP;
            case OP_ELEVATOR_QUERY_ACCESS:
                return GifTypeConstants.CHECKING_ACCESS;
            case OP_ELEVATOR_CLAIM_ACCESS:
                return GifTypeConstants.REQUESTING_ACCESS;
            case OP_ELEVATOR_RELEASE_ACCESS:
                return GifTypeConstants.RELEASING_ACCESS;
            default:
                return GifTypeConstants.NAVIGATING_NORMAL;
        }
    }

    private List<PositionMonitor> getPositionsForRoute(long routeId) {
        RouteStore route = RouteStore.getRoute(routeId);
        if (route == null) return Collections.emptyList();

        List<PositionMonitor> positions = new ArrayList<>();
        for (Long posId : route.getPositionIds()) {
            PositionMonitor pos = PositionMonitor.getPosition(posId);
            if (pos != null) {
                positions.add(pos);
            }
        }
        positions.sort(Comparator.comparingInt(PositionMonitor::getSequenceIndex));
        return positions;
    }

    private List<CommandMonitor> getCommandsForPosition(long positionId) {
        PositionMonitor pos = PositionMonitor.getPosition(positionId);
        if (pos == null) return Collections.emptyList();

        List<CommandMonitor> commands = new ArrayList<>();
        for (Long cmdId : pos.getCommandIds()) {
            CommandMonitor cmd = CommandMonitor.getCommand(cmdId);
            if (cmd != null) {
                commands.add(cmd);
            }
        }
        commands.sort(Comparator.comparingLong(CommandMonitor::getCommandId));
        return commands;
    }

    private PositionMonitor getCurrentPositionForRoute(long routeId) {
        List<PositionMonitor> positions = getPositionsForRoute(routeId);
        for (PositionMonitor pos : positions) {
            if (pos.getPositionStatus() == NavigationOrderStatus.ORDER_EXECUTING ||
                    pos.getPositionStatus() == NavigationOrderStatus.ORDER_READY) {
                return pos;
            }
        }
        return null;
    }

    private Context getContext() {
        return uiCallback != null ? uiCallback.getContext() : null;
    }

    public Position getCurrentPosition() {
        diagnoseWebSocketIssues();

        // Use cached status from command client if available and recent
        if (commandClient != null && commandClient.isStatusRecent(500)) {
            AgvStatusResponse status = commandClient.getCachedStatus();
            if (status != null && status.data != null) {
                return createPositionFromStatus(status);
            }
        }

        // Use cached status from status client if available
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            long timeSinceLastUpdate = System.currentTimeMillis() - lastStatusUpdateTime;
            if (timeSinceLastUpdate < 1000) {
                return createPositionFromStatus(latestStatusResponse);
            }
        }

        // Trigger async refresh using command client (non-blocking)
        if (commandClient != null && commandClient.isConnected()) {
            commandClient.queryStatus(new CommandWebSocketClient.StatusCallback() {
                @Override
                public void onSuccess(AgvStatusResponse status) {
                    if (status != null && status.data != null) {
                        latestStatusResponse = status;
                        lastStatusUpdateTime = System.currentTimeMillis();
                        Log.d(TAG, "Async status refresh completed");
                    }
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "Async status refresh failed: " + error);
                }
            });
        }

        // Return cached position if available, otherwise null
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            return createPositionFromStatus(latestStatusResponse);
        }
        return null;
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

    public Boolean getIsMoving() {
        // ⭐ START - Log entry with state debugger
        if (stateDebugger != null) {
            stateDebugger.log("========== getIsMoving ENTER ==========");
            stateDebugger.log(String.format("Timestamp: %s",
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date())));
            stateDebugger.log(String.format("Thread: %s", Thread.currentThread().getName()));
        }

        // ⭐ LOG - Check command client status
        if (stateDebugger != null) {
            stateDebugger.log("📡 Checking Command Client:");
            stateDebugger.log(String.format("  ├─ commandClient: %s", commandClient != null ? "exists" : "NULL"));
            if (commandClient != null) {
                stateDebugger.log(String.format("  ├─ isConnected: %b", commandClient.isConnected()));
                stateDebugger.log(String.format("  ├─ isStatusRecent(500): %b", commandClient.isStatusRecent(500)));
                stateDebugger.log(String.format("  └─ hasCachedStatus: %b", commandClient.getCachedStatus() != null));
            }
        }

        // ⭐ ALWAYS fetch fresh data from commandClient
        if (commandClient != null && commandClient.isConnected()) {
            if (stateDebugger != null) {
                stateDebugger.log("📤 Querying fresh status from commandClient...");
            }

            final AgvStatusResponse[] freshStatus = new AgvStatusResponse[1];
            final boolean[] completed = {false};
            final boolean[] success = {false};
            final Object lock = new Object();
            final long queryStartTime = System.currentTimeMillis();

            // Execute query on main thread to ensure callback is processed
            mainHandler.post(() -> {
                commandClient.queryStatus(new CommandWebSocketClient.StatusCallback() {
                    @Override
                    public void onSuccess(AgvStatusResponse status) {
                        synchronized (lock) {
                            freshStatus[0] = status;
                            success[0] = true;
                            completed[0] = true;
                            lock.notify();

                            // Update cached status
                            if (status != null && status.data != null) {
                                latestStatusResponse = status;
                                lastStatusUpdateTime = System.currentTimeMillis();
                            }
                        }
                        long responseTime = System.currentTimeMillis() - queryStartTime;
                        if (stateDebugger != null) {
                            stateDebugger.log(String.format("✅ Status query SUCCESS in %d ms", responseTime));
                            if (status != null && status.data != null) {
                                stateDebugger.log(String.format("  ├─ Movement: %d", status.data.movement));
                                stateDebugger.log(String.format("  ├─ IsMoving: %b", status.data.movement != 0));
                                stateDebugger.log(String.format("  └─ Position: (%.3f, %.3f)",
                                        status.data.pos.x, status.data.pos.y));
                            }
                        }
                        Log.d(TAG, "Status query completed in " + responseTime + "ms");
                    }

                    @Override
                    public void onError(String error) {
                        synchronized (lock) {
                            completed[0] = true;
                            lock.notify();
                        }
                        long responseTime = System.currentTimeMillis() - queryStartTime;
                        if (stateDebugger != null) {
                            stateDebugger.logError("STATUS_QUERY_FAILED",
                                    String.format("Error after %d ms: %s", responseTime, error));
                        }
                        Log.w(TAG, "Status query failed after " + responseTime + "ms: " + error);
                    }
                });
            });

            // Wait for response (max 1000ms)
            synchronized (lock) {
                try {
                    long waitStart = System.currentTimeMillis();
                    if (!completed[0]) {
                        lock.wait(1000);
                    }
                    long waitTime = System.currentTimeMillis() - waitStart;
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("⏱️ Wait time: %d ms", waitTime));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (stateDebugger != null) {
                        stateDebugger.logWarning("getIsMoving wait interrupted", e.getMessage());
                    }
                    Log.w(TAG, "Interrupted while waiting for status");
                }
            }

            // If we got fresh data, use it
            if (success[0] && freshStatus[0] != null && freshStatus[0].data != null) {
                boolean isMoving = freshStatus[0].data.movement != 0;
                if (stateDebugger != null) {
                    stateDebugger.log("✅ SUCCESS - Fresh data from commandClient:");
                    stateDebugger.log(String.format("  ├─ Movement: %d", freshStatus[0].data.movement));
                    stateDebugger.log(String.format("  ├─ IsMoving: %b", isMoving));
                    stateDebugger.log(String.format("  └─ Position: (%.3f, %.3f)",
                            freshStatus[0].data.pos.x, freshStatus[0].data.pos.y));
                    stateDebugger.log("========== getIsMoving EXIT (fresh) ==========");
                }
                return isMoving;
            }

            // If query failed or timed out, fall back to cached command client data
            if (stateDebugger != null) {
                stateDebugger.logWarning("getIsMoving",
                        String.format("Fresh query %s - falling back to cached data",
                                completed[0] ? "failed" : "timed out"));
            }

            if (commandClient.isStatusRecent(1000)) {
                AgvStatusResponse cached = commandClient.getCachedStatus();
                if (cached != null && cached.data != null) {
                    boolean isMoving = cached.data.movement != 0;
                    if (stateDebugger != null) {
                        stateDebugger.log("⚠️ FALLBACK - Using cached commandClient data:");
                        stateDebugger.log(String.format("  ├─ Movement: %d", cached.data.movement));
                        stateDebugger.log(String.format("  ├─ IsMoving: %b", isMoving));
                        stateDebugger.log(String.format("  └─ Cache age: %d ms",
                                System.currentTimeMillis() - lastStatusUpdateTime));
                        stateDebugger.log("========== getIsMoving EXIT (cached) ==========");
                    }
                    return isMoving;
                }
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logError("getIsMoving",
                        String.format("commandClient is %s - cannot fetch fresh data",
                                commandClient == null ? "NULL" : "DISCONNECTED"));
            }
        }

        // ⭐ FINAL FALLBACK - Use latestStatusResponse if available (even if stale)
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            boolean isMoving = latestStatusResponse.data.movement != 0;
            long cacheAge = System.currentTimeMillis() - lastStatusUpdateTime;
            if (stateDebugger != null) {
                stateDebugger.log("⚠️ FINAL FALLBACK - Using latestStatusResponse:");
                stateDebugger.log(String.format("  ├─ Movement: %d", latestStatusResponse.data.movement));
                stateDebugger.log(String.format("  ├─ IsMoving: %b", isMoving));
                stateDebugger.log(String.format("  └─ Cache age: %d ms (STALE!)", cacheAge));
                stateDebugger.log("========== getIsMoving EXIT (stale) ==========");
            }
            return isMoving;
        }

        // ⭐ NO DATA AVAILABLE
        if (stateDebugger != null) {
            stateDebugger.logError("getIsMoving",
                    "❌ NO STATUS DATA AVAILABLE - returning false");
            stateDebugger.log("  └─ latestStatusResponse: " +
                    (latestStatusResponse != null ? "exists" : "NULL"));
            stateDebugger.log("========== getIsMoving EXIT (NULL) ==========");
        }
        return false;
    }

    /**
     * Get current goal finish status from the robot
     * Always fetches fresh data from commandClient
     *
     * @return goalFinish value:
     *         - 0: Navigation in progress / not finished
     *         - 1: Navigation completed successfully
     *         - -2: Navigation failed / error
     *         - Other negative values: Various error conditions
     */
    public int getGoalFinish() {
        // ⭐ START - Log entry with state debugger
        if (stateDebugger != null) {
            stateDebugger.log("========== getGoalFinish ENTER ==========");
            stateDebugger.log(String.format("Timestamp: %s",
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date())));
            stateDebugger.log(String.format("Thread: %s", Thread.currentThread().getName()));
        }

        // ⭐ LOG - Check command client status
        if (stateDebugger != null) {
            stateDebugger.log("📡 Checking Command Client:");
            stateDebugger.log(String.format("  ├─ commandClient: %s", commandClient != null ? "exists" : "NULL"));
            if (commandClient != null) {
                stateDebugger.log(String.format("  ├─ isConnected: %b", commandClient.isConnected()));
                stateDebugger.log(String.format("  ├─ isStatusRecent(500): %b", commandClient.isStatusRecent(500)));
                stateDebugger.log(String.format("  └─ hasCachedStatus: %b", commandClient.getCachedStatus() != null));
            }
        }

        // ⭐ ALWAYS fetch fresh data from commandClient
        if (commandClient != null && commandClient.isConnected()) {
            if (stateDebugger != null) {
                stateDebugger.log("📤 Querying fresh status from commandClient...");
            }

            final AgvStatusResponse[] freshStatus = new AgvStatusResponse[1];
            final boolean[] completed = {false};
            final boolean[] success = {false};
            final Object lock = new Object();
            final long queryStartTime = System.currentTimeMillis();

            // Execute query on main thread to ensure callback is processed
            mainHandler.post(() -> {
                commandClient.queryStatus(new CommandWebSocketClient.StatusCallback() {
                    @Override
                    public void onSuccess(AgvStatusResponse status) {
                        synchronized (lock) {
                            freshStatus[0] = status;
                            success[0] = true;
                            completed[0] = true;
                            lock.notify();

                            // Update cached status
                            if (status != null && status.data != null) {
                                latestStatusResponse = status;
                                lastStatusUpdateTime = System.currentTimeMillis();
                            }
                        }
                        long responseTime = System.currentTimeMillis() - queryStartTime;
                        if (stateDebugger != null) {
                            stateDebugger.log(String.format("✅ Status query SUCCESS in %d ms", responseTime));
                            if (status != null && status.data != null) {
                                stateDebugger.log(String.format("  ├─ goalFinish: %d", status.data.goalFinish));
                                stateDebugger.log(String.format("  ├─ movement: %d", status.data.movement));
                                stateDebugger.log(String.format("  └─ Position: (%.3f, %.3f)",
                                        status.data.pos.x, status.data.pos.y));
                            }
                        }
                        Log.d(TAG, "Status query completed in " + responseTime + "ms");
                    }

                    @Override
                    public void onError(String error) {
                        synchronized (lock) {
                            completed[0] = true;
                            lock.notify();
                        }
                        long responseTime = System.currentTimeMillis() - queryStartTime;
                        if (stateDebugger != null) {
                            stateDebugger.logError("STATUS_QUERY_FAILED",
                                    String.format("Error after %d ms: %s", responseTime, error));
                        }
                        Log.w(TAG, "Status query failed after " + responseTime + "ms: " + error);
                    }
                });
            });

            // Wait for response (max 1000ms)
            synchronized (lock) {
                try {
                    long waitStart = System.currentTimeMillis();
                    if (!completed[0]) {
                        lock.wait(1000);
                    }
                    long waitTime = System.currentTimeMillis() - waitStart;
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("⏱️ Wait time: %d ms", waitTime));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (stateDebugger != null) {
                        stateDebugger.logWarning("getGoalFinish wait interrupted", e.getMessage());
                    }
                    Log.w(TAG, "Interrupted while waiting for status");
                }
            }

            // If we got fresh data, use it
            if (success[0] && freshStatus[0] != null && freshStatus[0].data != null) {
                int goalFinish = freshStatus[0].data.goalFinish;

                // ⭐ Log with context based on goalFinish value
                if (stateDebugger != null) {
                    String statusDescription;
                    switch (goalFinish) {
                        case 0:
                            statusDescription = "IN PROGRESS";
                            break;
                        case 1:
                            statusDescription = "COMPLETED";
                            break;
                        case -2:
                            statusDescription = "FAILED / ERROR";
                            break;
                        default:
                            statusDescription = "UNKNOWN (" + goalFinish + ")";
                            break;
                    }

                    stateDebugger.log("✅ SUCCESS - Fresh data from commandClient:");
                    stateDebugger.log(String.format("  ├─ goalFinish: %d (%s)", goalFinish, statusDescription));
                    stateDebugger.log(String.format("  ├─ movement: %d", freshStatus[0].data.movement));
                    stateDebugger.log(String.format("  ├─ errorCode: %s",
                            freshStatus[0].data.errorCode != null ? freshStatus[0].data.errorCode : "none"));
                    stateDebugger.log(String.format("  └─ Position: (%.3f, %.3f)",
                            freshStatus[0].data.pos.x, freshStatus[0].data.pos.y));
                    stateDebugger.log("========== getGoalFinish EXIT (fresh) ==========");
                }
                return goalFinish;
            }

            // If query failed or timed out, fall back to cached command client data
            if (stateDebugger != null) {
                stateDebugger.logWarning("getGoalFinish",
                        String.format("Fresh query %s - falling back to cached data",
                                completed[0] ? "failed" : "timed out"));
            }

            if (commandClient.isStatusRecent(1000)) {
                AgvStatusResponse cached = commandClient.getCachedStatus();
                if (cached != null && cached.data != null) {
                    int goalFinish = cached.data.goalFinish;
                    long cacheAge = System.currentTimeMillis() - lastStatusUpdateTime;

                    if (stateDebugger != null) {
                        String statusDescription;
                        switch (goalFinish) {
                            case 0:
                                statusDescription = "IN PROGRESS";
                                break;
                            case 1:
                                statusDescription = "COMPLETED";
                                break;
                            case -2:
                                statusDescription = "FAILED / ERROR";
                                break;
                            default:
                                statusDescription = "UNKNOWN (" + goalFinish + ")";
                                break;
                        }

                        stateDebugger.log("⚠️ FALLBACK - Using cached commandClient data:");
                        stateDebugger.log(String.format("  ├─ goalFinish: %d (%s)", goalFinish, statusDescription));
                        stateDebugger.log(String.format("  ├─ Cache age: %d ms", cacheAge));
                        stateDebugger.log(String.format("  └─ movement: %d", cached.data.movement));
                        stateDebugger.log("========== getGoalFinish EXIT (cached) ==========");
                    }
                    return goalFinish;
                }
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logError("getGoalFinish",
                        String.format("commandClient is %s - cannot fetch fresh data",
                                commandClient == null ? "NULL" : "DISCONNECTED"));
            }
        }

        // ⭐ FINAL FALLBACK - Use latestStatusResponse if available (even if stale)
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            int goalFinish = latestStatusResponse.data.goalFinish;
            long cacheAge = System.currentTimeMillis() - lastStatusUpdateTime;

            if (stateDebugger != null) {
                String statusDescription;
                switch (goalFinish) {
                    case 0:
                        statusDescription = "IN PROGRESS";
                        break;
                    case 1:
                        statusDescription = "COMPLETED";
                        break;
                    case -2:
                        statusDescription = "FAILED / ERROR";
                        break;
                    default:
                        statusDescription = "UNKNOWN (" + goalFinish + ")";
                        break;
                }

                stateDebugger.log("⚠️ FINAL FALLBACK - Using latestStatusResponse:");
                stateDebugger.log(String.format("  ├─ goalFinish: %d (%s)", goalFinish, statusDescription));
                stateDebugger.log(String.format("  ├─ Cache age: %d ms (STALE!)", cacheAge));
                stateDebugger.log(String.format("  └─ movement: %d", latestStatusResponse.data.movement));
                stateDebugger.log("========== getGoalFinish EXIT (stale) ==========");
            }
            return goalFinish;
        }

        // ⭐ NO DATA AVAILABLE - return 0 as default (assume in progress)
        if (stateDebugger != null) {
            stateDebugger.logError("getGoalFinish",
                    "❌ NO STATUS DATA AVAILABLE - returning 0 (default)");
            stateDebugger.log("  └─ latestStatusResponse: " +
                    (latestStatusResponse != null ? "exists" : "NULL"));
            stateDebugger.log("========== getGoalFinish EXIT (NULL) ==========");
        }
        return 0; // Default: assuming navigation in progress
    }

    /**
     * Get current error codes from the latest AGV status response
     * Uses commandClient.queryStatus() to ensure error data is up-to-date
     * Returns a list of distinct error codes from both errorCode and errcode fields
     *
     * @return List of distinct error codes as strings, empty list if no errors
     */
    public List<String> getCurrentError() {
        synchronized (navigationLock) {
            // First check if we have recent cached data from command client (within 200ms)
            if (commandClient != null && commandClient.isStatusRecent(200)) {
                AgvStatusResponse status = commandClient.getCachedStatus();
                if (status != null && status.data != null) {
                    return extractErrorsFromStatus(status);
                }
            }

            // If status is stale or not available, query fresh data synchronously
            final AgvStatusResponse[] freshStatus = new AgvStatusResponse[1];
            final boolean[] completed = {false};
            final boolean[] success = {false};
            final Object lock = new Object();

            if (commandClient != null && commandClient.isConnected()) {
                if (stateDebugger != null) {
                    stateDebugger.log("Querying fresh status for error data...");
                }

                commandClient.queryStatus(new CommandWebSocketClient.StatusCallback() {
                    @Override
                    public void onSuccess(AgvStatusResponse status) {
                        synchronized (lock) {
                            freshStatus[0] = status;
                            success[0] = true;
                            completed[0] = true;
                            lock.notify();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        synchronized (lock) {
                            completed[0] = true;
                            lock.notify();
                        }
                        Log.w(TAG, "Status query for errors failed: " + error);
                        if (stateDebugger != null) {
                            stateDebugger.logWarning("Error query failed", error);
                        }
                    }
                });

                // Wait for response (max 500ms)
                synchronized (lock) {
                    try {
                        if (!completed[0]) {
                            lock.wait(500);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Interrupted while waiting for error status");
                    }
                }

                // If we got fresh data, use it
                if (success[0] && freshStatus[0] != null && freshStatus[0].data != null) {
                    // Update cached status
                    latestStatusResponse = freshStatus[0];
                    lastStatusUpdateTime = System.currentTimeMillis();
                    return extractErrorsFromStatus(freshStatus[0]);
                }
            }

            // Fallback to cached status from status client (may be stale)
            if (latestStatusResponse != null && latestStatusResponse.data != null) {
                long timeSinceLastUpdate = System.currentTimeMillis() - lastStatusUpdateTime;
                if (stateDebugger != null && timeSinceLastUpdate > 1000) {
                    stateDebugger.logWarning("Using stale error data",
                            String.format("Last update was %d ms ago", timeSinceLastUpdate));
                }
                return extractErrorsFromStatus(latestStatusResponse);
            }

            // No data available
            if (stateDebugger != null) {
                stateDebugger.logWarning("getCurrentError", "No status data available");
            }
            return new ArrayList<>();
        }
    }

    /**
     * Extract errors from AgvStatusResponse
     * Handles both errorCode (List<Integer>) and errcode (Integer) fields
     */
    private List<String> extractErrorsFromStatus(AgvStatusResponse status) {
        List<String> errors = new ArrayList<>();

        if (status == null || status.data == null) {
            return errors;
        }

        AgvStatusResponse.Data data = status.data;

        // Check errorCode list (List<Integer>)
        if (data.errorCode != null && !data.errorCode.isEmpty()) {
            for (Integer error : data.errorCode) {
                if (error != null) {
                    String errorStr = String.valueOf(error);
                    if (!errorStr.isEmpty() && !errors.contains(errorStr)) {
                        errors.add(errorStr);
                    }
                }
            }
        }

        // Check errcode (Integer field, non-zero indicates error)
        if (data.errcode != null && data.errcode != 0) {
            String errCodeStr = String.valueOf(data.errcode);
            if (!errors.contains(errCodeStr)) {
                errors.add(errCodeStr);
            }
        }

        // Also check the legacy errCode field (int, non-zero indicates error)
        if (data.errCode != 0) {
            String errCodeStr = String.valueOf(data.errCode);
            if (!errors.contains(errCodeStr)) {
                errors.add(errCodeStr);
            }
        }

        // Log if errors found
        if (!errors.isEmpty() && stateDebugger != null) {
            stateDebugger.logWarning("extractErrorsFromStatus",
                    String.format("Current errors: %s", String.join(", ", errors)));
        }

        return errors;
    }

    // ---------------------------------------------------------------------------------------------
    // ========== Public API Methods ==========
    public void diagnoseWebSocketIssues() {
        Log.d(TAG, "========== WEBSOCKET DIAGNOSTICS ==========");

        // Print debugger summary
        if (stateDebugger != null) {
            String summary = stateDebugger.getWebSocketDiagnosticSummary();
            Log.d(TAG, summary);
        }

        // Check current connection state
        Log.d(TAG, "Status Client Connected: " + (statusClient != null && statusClient.isConnected()));
        Log.d(TAG, "Command Client Connected: " + (commandClient != null && commandClient.isConnected()));

        // Check last status update time
        long timeSinceLastStatus = System.currentTimeMillis() - lastStatusUpdateTime;
        Log.d(TAG, "Time since last status update: " + timeSinceLastStatus + "ms");

        // Check if we've sent any suspicious commands recently
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            Log.d(TAG, "Last status data valid: YES");
            Log.d(TAG, "  - Position: (" + latestStatusResponse.data.pos.x + ", " +
                    latestStatusResponse.data.pos.y + ")");
            Log.d(TAG, "  - Battery: " + latestStatusResponse.data.power);
            Log.d(TAG, "  - Error codes: " + latestStatusResponse.data.errorCode);
        } else {
            Log.d(TAG, "Last status data valid: NO");
        }

        // Check for command failures
        Log.d(TAG, "============================================");
    }

    // Call this periodically or when issues are suspected
    public void checkWebSocketHealth() {
        if (statusClient != null && !statusClient.isConnected()) {
            Log.w(TAG, "Status WebSocket is DISCONNECTED!");
            if (stateDebugger != null) {
                stateDebugger.logWarning("Status WebSocket disconnected",
                        "Last status update was " + (System.currentTimeMillis() - lastStatusUpdateTime) + "ms ago");
            }
        }

        if (commandClient != null && !commandClient.isConnected()) {
            Log.w(TAG, "Command WebSocket is DISCONNECTED!");
            if (stateDebugger != null) {
                stateDebugger.logWarning("Command WebSocket disconnected", "Cannot send commands");
            }
        }
    }

    public void setPreCreatedTaskRecord(TaskRecord taskRecord) {
        this.preCreatedTaskRecord = taskRecord;
        Log.d(TAG, "Pre-created task record received: " + (taskRecord != null ? taskRecord.getTaskName() : "null"));
    }

    public void setExternalTaskContext(ExternalTaskContext taskContext) {
        this.pendingExternalTaskContext = taskContext;
        Log.d(TAG, "External task context received: "
                + (taskContext != null ? taskContext.getTaskType() + "/" + taskContext.getTaskName() : "null"));
    }

    public TaskRecord getCurrentTaskRecord() {
        return currentRecord;
    }

    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }

    public int getCurrentEffectiveIndex() {
        return currentEffectiveIndex;
    }

    public NavigationOrderType getCurrentCommandType() {return currentCommandType;}

    public void pauseNavigation() {
        synchronized (navigationLock) {
            TaskDebug2.log("[PAUSE] pauseNavigation ENTER " + pauseDebugSnapshot());
            if (!isPaused) {
                isPaused = true;
                currentState = NavigationState.PAUSED;
                ignore10003AfterScreenResumeUntilClear = false;
                had10003WhileScreenPaused = false;
                lastStatusHad10003 = false;
                boolean softScramOk = commandClient.setSoftScram(true);
                stopSound();
                pauseSound();
                TaskDebug2.log("[PAUSE] pauseNavigation APPLIED setSoftScram(true) ok=" + softScramOk
                        + " " + pauseDebugSnapshot());
                LOG.info("Navigation paused");
            } else {
                TaskDebug2.log("[PAUSE] pauseNavigation NOOP already paused " + pauseDebugSnapshot());
            }
        }
    }

    public void resumeNavigation() {
        synchronized (navigationLock) {
            TaskDebug2.log("[PAUSE] resumeNavigation ENTER " + pauseDebugSnapshot());
            boolean releasedSoftScram = false;
            if (!isPaused) {
                // Handler may already be un-paused while softScram is still held (e.g. prior
                // isSoftEmergencyStopTriggered blocked release). Release when safe to resume.
                if (!isEmergencyStop && !isSafeEdgeTriggered && !isSoftEmergencyStopTriggered
                        && isTaskRunning) {
                    boolean softScramOk = commandClient.setSoftScram(false);
                    releasedSoftScram = softScramOk;
                    if (currentState == NavigationState.PAUSED) {
                        currentState = NavigationState.IDLE;
                        playRunningMusicIfNeeded(musicResId);
                    }
                    TaskDebug2.log("[PAUSE] resumeNavigation softScram RECOVERY setSoftScram(false) ok="
                            + softScramOk + " " + pauseDebugSnapshot());
                    LOG.info("Navigation resumed (softScram recovery)");
                } else {
                    TaskDebug2.log("[PAUSE] resumeNavigation NOOP handler not paused, recovery blocked "
                            + pauseDebugSnapshot());
                }
                if (releasedSoftScram) {
                    suppressLingering10003AfterAppRelease();
                }
                return;
            }

            isPaused = false;
            String branch;

            if (isEmergencyStop) {
                branch = "emergencyStop";
                currentState = NavigationState.EMERGENCY_STOPPED;
            } else if (isSafeEdgeTriggered) {
                branch = "safeEdge";
                currentState = NavigationState.BUMPED;
            } else if (isSoftEmergencyStopTriggered) {
                branch = "softEstop";
                currentState = NavigationState.PAUSED;
                boolean softScramOk = commandClient.setSoftScram(false);
                releasedSoftScram = softScramOk;
                TaskDebug2.log("[PAUSE] resumeNavigation branch=" + branch
                        + " setSoftScram(false) ok=" + softScramOk);
            } else {
                branch = "normal";
                currentState = NavigationState.IDLE;
                boolean softScramOk = commandClient.setSoftScram(false);
                releasedSoftScram = softScramOk;
                if (isTaskRunning) {
                    playRunningMusicIfNeeded(musicResId);
                }
                TaskDebug2.log("[PAUSE] resumeNavigation branch=" + branch
                        + " setSoftScram(false) ok=" + softScramOk);
            }

            if (releasedSoftScram) {
                suppressLingering10003AfterAppRelease();
            }
            TaskDebug2.log("[PAUSE] resumeNavigation DONE branch=" + branch + " " + pauseDebugSnapshot());
            LOG.info("Navigation resumed");
        }
    }

    /** Ignore chassis 10003 left over from app-initiated softScram release (resume/cancel). */
    private void suppressLingering10003AfterAppRelease() {
        ignore10003AfterScreenResumeUntilClear = true;
        had10003WhileScreenPaused = false;
        TaskDebug2.log("[PAUSE] suppressLingering10003AfterAppRelease suppress10003UntilClear=true");
    }

    /** Cancel/task-end path: also clear a false soft-e-stop layer from lingering 10003. */
    private void suppressLingering10003AfterAppReleaseAndClearSoftEstop() {
        suppressLingering10003AfterAppRelease();
        if (isSoftEmergencyStopTriggered && !isEmergencyStop && !isSafeEdgeTriggered) {
            isSoftEmergencyStopTriggered = false;
            if (!isPaused) {
                currentState = NavigationState.IDLE;
            }
            TaskDebug2.log("[PAUSE] cleared false softEstop after app softScram release");
        }
    }

    /** Call only while holding navigationLock. */
    private String pauseDebugSnapshot() {
        String errorCodes = "none";
        int goalFinish = -1;
        if (latestStatusResponse != null && latestStatusResponse.data != null) {
            if (latestStatusResponse.data.errorCode != null) {
                errorCodes = latestStatusResponse.data.errorCode.toString();
            }
            goalFinish = latestStatusResponse.data.goalFinish;
        }
        return String.format(
                "handlerPaused=%b softEstop=%b emg=%b safeEdge=%b connFail=%b navPaused=%b navStopped=%b "
                        + "taskRunning=%b taskCancelled=%b suppress10003=%b had10003WhilePaused=%b lastHad10003=%b "
                        + "state=%s routeId=%d goalFinish=%d errorCodes=%s",
                isPaused, isSoftEmergencyStopTriggered, isEmergencyStop, isSafeEdgeTriggered,
                isConnectionFailed, isNavigationPaused(), isNavigationStopped(), isTaskRunning,
                taskCancelledByUser, ignore10003AfterScreenResumeUntilClear, had10003WhileScreenPaused,
                lastStatusHad10003,
                currentState, currentRouteId, goalFinish, errorCodes);
    }

    public void cancelNavigation(boolean notifyTaskTermination) {
        boolean shouldNotifyCancellation = false;
        synchronized (navigationLock) {
            if (notifyTaskTermination) {
                taskCancelledByUser = true;
            }

            stopGifPlayback();
            boolean wasScreenPaused = isPaused;
            isPaused = false;
            if (wasScreenPaused || had10003WhileScreenPaused) {
                suppressLingering10003AfterAppReleaseAndClearSoftEstop();
                TaskDebug2.log("[PAUSE] cancelNavigation early suppress (wasScreenPaused="
                        + wasScreenPaused + ") " + pauseDebugSnapshot());
            }
            currentState = isEmergencyStop
                    ? NavigationState.EMERGENCY_STOPPED
                    : (isSafeEdgeTriggered ? NavigationState.BUMPED : NavigationState.IDLE);

            if (currentRouteId != -1) {
                boolean taskWasRunning = isTaskRunning;
                TaskDebug1.log(String.format("[NAV] cancelNavigation routeId=%d taskWasRunning=%b notify=%b routeSize=%d",
                        currentRouteId, taskWasRunning, notifyTaskTermination,
                        currentTaskRoute != null ? currentTaskRoute.size() : 0));
                // Mark that we're cancelling
                cancellationManager.cancelTasksByRoute(currentRouteId,
                        NavigationOrderStatus.ORDER_CANCELLED);

                // ⭐ Clear in-use elevator
                sharedViewModel.clearInUseElevator();
                if (stateDebugger != null) {
                    stateDebugger.logInfo("cancelNavigation - cleared inUseElevator");
                }

                // ⭐ Clear ALL navigation data immediately
                executeOnMainThread(() -> {
                    stopSound();
                    RouteStore.clearAllRoutes();
                    PositionMonitor.clearAllPositions();
                    CommandMonitor.clearAllCommands();
                    confirmationManager.clearAllStates();
                    LOG.info("Task cancelled, clear route/position/command, " +
                                    "current route map {}, position map {}, command map {} ",
                            gson.toJson(RouteStore.getAllRoutes()),
                            gson.toJson(PositionMonitor.getAllPositions()),
                            gson.toJson(CommandMonitor.getAllCommands()));
                    progressListener.resetMonitor();
                });

                LOG.info("Cleared all navigation data during cancel - routeId: {}", currentRouteId);

                commandClient.cancelTask();
                commandClient.setSoftScram(false);
                suppressLingering10003AfterAppReleaseAndClearSoftEstop();
                TaskDebug2.log("[PAUSE] cancelNavigation released softScram " + pauseDebugSnapshot());
                stopSound();

                if (currentRecord != null) {
                    currentRecord.finish(TaskRecord.STATUS_CANCELLED);
                    notifyTaskRecordUpdate(currentRecord);
                }

                if (isWaitingForUserConfirmation) {
                    setWaitingForUserConfirmation(false);
                }

                // ⭐ Reset state AFTER clearing maps
                isTaskRunning = false;
                currentRouteId = -1;
                currentRoute = null;
                currentTaskTarget = null;
                currentTaskRoute.clear();
                currentWaypointIndex = -1;
                currentEffectiveIndex = -1;
                currentCommandType = null;

                // ✅ 重置异常管理器，确保取消导航时也清除恢复序列
                if (exceptionManager != null) {
                    exceptionManager.reset();
                }
                shouldNotifyCancellation = notifyTaskTermination && taskWasRunning;
                LOG.info("Navigation cancellation requested - maps cleared");
            } else if (isChassisNavigating(latestStatusResponse)) {
                TaskDebug1.log(String.format("[NAV] cancelNavigation orphanChassis notify=%b", notifyTaskTermination));
                commandClient.cancelTask();
                commandClient.setSoftScram(false);
                suppressLingering10003AfterAppReleaseAndClearSoftEstop();
                TaskDebug2.log("[PAUSE] cancelNavigation orphan released softScram " + pauseDebugSnapshot());
                stopSound();
                if (exceptionManager != null) {
                    exceptionManager.reset();
                }
                shouldNotifyCancellation = notifyTaskTermination;
                LOG.info("Navigation cancellation requested - orphaned chassis task cleared");
            }
        }

        if (shouldNotifyCancellation) {
            notifyTaskCancelled();
        }
        if (taskCancelledByUser) {
            taskViewModel.reconcileStaleRunningTaskRecords(true);
            executeOnMainThread(this::notifyTaskRecoveryCancelled);
        }
    }

    public void triggerEmergencyStop() {
        synchronized (navigationLock) {
            if (!isEmergencyStop) {
                isEmergencyStop = true;
                currentState = NavigationState.EMERGENCY_STOPPED;
                // commandClient.setSoftScram(true);
                stopSound();
                playVoicePrompt(VoiceKeyConstants.EMERGENCY_STOP);
                LOG.warn("Emergency stop triggered");
            }
        }
    }

    public void clearEmergencyStop() {
        synchronized (navigationLock) {
            if (isEmergencyStop) {
                isEmergencyStop = false;

                if (isSafeEdgeTriggered) {
                    currentState = NavigationState.BUMPED;
                } else if (isPaused) {
                    currentState = NavigationState.PAUSED;
                } else {
                    currentState = NavigationState.IDLE;
                    commandClient.setSoftScram(false);
                    if (isTaskRunning) {
                        playRunningMusicIfNeeded(musicResId);
                    }
                }

                LOG.info("Emergency stop cleared");
                if (shouldResumeAfterNavigationStopCleared()) {
                    notifyNavigationStopCleared();
                }
            }
        }
    }

    public void triggerSafeEdge() {
        synchronized (navigationLock) {
            if (!isSafeEdgeTriggered) {
                isSafeEdgeTriggered = true;
                currentState = NavigationState.BUMPED;
                commandClient.setSoftScram(true);
                stopSound();
                playVoicePrompt(VoiceKeyConstants.COLLISION_ALERT);
                LOG.warn("Safe edge triggered");
            }
        }
    }

    public void clearSafeEdge() {
        synchronized (navigationLock) {
            if (isSafeEdgeTriggered) {
                isSafeEdgeTriggered = false;

                if (isEmergencyStop) {
                    currentState = NavigationState.EMERGENCY_STOPPED;
                } else if (isPaused) {
                    currentState = NavigationState.PAUSED;
                } else {
                    currentState = NavigationState.IDLE;
                    commandClient.setSoftScram(false);
                    if (isTaskRunning) {
                        playRunningMusicIfNeeded(musicResId);
                    }
                }

                LOG.info("Safe edge cleared");
                if (shouldResumeAfterNavigationStopCleared()) {
                    notifyNavigationStopCleared();
                }
            }
        }
    }

    public void triggerSoftEmergencyStop() {
        synchronized (navigationLock) {
            TaskDebug2.log("[PAUSE] triggerSoftEmergencyStop ENTER " + pauseDebugSnapshot());
            if (!isSoftEmergencyStopTriggered) {
                isSoftEmergencyStopTriggered = true;
                if (isEmergencyStop) {
                    currentState = NavigationState.EMERGENCY_STOPPED;
                } else if (isSafeEdgeTriggered) {
                    currentState = NavigationState.BUMPED;
                } else if (!isPaused) {
                    currentState = NavigationState.PAUSED;
                }
                boolean softScramOk = commandClient.setSoftScram(true);
                stopSound();
                TaskDebug2.log("[PAUSE] triggerSoftEmergencyStop APPLIED setSoftScram(true) ok="
                        + softScramOk + " " + pauseDebugSnapshot());
                LOG.warn("Soft emergency stop triggered");
            } else {
                TaskDebug2.log("[PAUSE] triggerSoftEmergencyStop NOOP already triggered "
                        + pauseDebugSnapshot());
            }
        }
    }

    public void clearSoftEmergencyStop() {
        synchronized (navigationLock) {
            TaskDebug2.log("[PAUSE] clearSoftEmergencyStop ENTER " + pauseDebugSnapshot());
            if (isSoftEmergencyStopTriggered) {
                isSoftEmergencyStopTriggered = false;
                boolean wasUserPaused = isPaused;
                boolean invalidatedScreenPause = false;
                String branch;

                if (isEmergencyStop) {
                    branch = "emergencyStop";
                    currentState = NavigationState.EMERGENCY_STOPPED;
                } else if (isSafeEdgeTriggered) {
                    branch = "safeEdge";
                    currentState = NavigationState.BUMPED;
                } else if (wasUserPaused) {
                    if (shouldResumeAfterNavigationStopCleared()) {
                        branch = "wasUserPaused->resume+invalidateUi";
                        resumeNavigation();
                        notifyScreenPauseInvalidated();
                        invalidatedScreenPause = true;
                    } else {
                        branch = "wasUserPaused->clearOnly";
                        isPaused = false;
                        currentState = NavigationState.IDLE;
                        boolean softScramOk = commandClient.setSoftScram(false);
                        TaskDebug2.log("[PAUSE] clearSoftEmergencyStop setSoftScram(false) ok="
                                + softScramOk);
                    }
                } else {
                    branch = "normal";
                    currentState = NavigationState.IDLE;
                    boolean softScramOk = commandClient.setSoftScram(false);
                    if (isTaskRunning) {
                        playRunningMusicIfNeeded(musicResId);
                    }
                    TaskDebug2.log("[PAUSE] clearSoftEmergencyStop setSoftScram(false) ok="
                            + softScramOk);
                }

                TaskDebug2.log("[PAUSE] clearSoftEmergencyStop DONE branch=" + branch
                        + " wasUserPaused=" + wasUserPaused
                        + " invalidatedScreenPause=" + invalidatedScreenPause
                        + " " + pauseDebugSnapshot());
                LOG.info("Soft emergency stop cleared, wasUserPaused=" + wasUserPaused
                        + ", taskCancelledByUser=" + taskCancelledByUser);
                if (shouldResumeAfterNavigationStopCleared() && !invalidatedScreenPause) {
                    notifyNavigationStopCleared();
                }
            } else {
                TaskDebug2.log("[PAUSE] clearSoftEmergencyStop NOOP not triggered "
                        + pauseDebugSnapshot());
            }
        }
    }

    private void notifyScreenPauseInvalidated() {
        if (uiCallback != null) {
            executeOnMainThread(uiCallback::onScreenPauseInvalidated);
        }
    }

    private void notifyNavigationStopCleared() {
        if (!shouldResumeAfterNavigationStopCleared()) {
            return;
        }
        if (!isNavigationStopped() && uiCallback != null) {
            executeOnMainThread(uiCallback::onNavigationStopCleared);
        }
    }

    private void resetState() {
        isPaused = false;
        isEmergencyStop = false;
        isSafeEdgeTriggered = false;
        isSoftEmergencyStopTriggered = false;
        isConnectionFailed = false;
        ignore10003AfterScreenResumeUntilClear = false;
        had10003WhileScreenPaused = false;
        lastStatusHad10003 = false;
        isWaitingForUserConfirmation = false;
        isGifPlaying = false;
        currentState = NavigationState.IDLE;
        currentWaypointIndex = -1;      // Reset to -1
        currentEffectiveIndex = -1;     // Reset to -1
    }

    public boolean isNavigationPaused() {
        return isPaused || isEmergencyStop || isSafeEdgeTriggered
                || isSoftEmergencyStopTriggered || isConnectionFailed;
    }

    public boolean isNavigationStopped() {
        return (isEmergencyStop || isSafeEdgeTriggered || isSoftEmergencyStopTriggered);
    }

    public boolean isTaskRunning() {
        return isTaskRunning;
    }

    public boolean isInStationStay() {
        return isInStationStay;
    }

    public void setInStationStay(boolean inStationStay) {
        isInStationStay = inStationStay;
    }

    public boolean isGifPlaying() {
        return isGifPlaying;
    }

    /**
     * Clean up resources - call when activity is destroyed
     */
    public void cleanup() {
        cleanup(true);
    }

    public void cleanup(boolean stopStatusMonitoring) {
        LOG.info("Cleaning up GeneralNavigationHandler");

        if (stopStatusMonitoring) {
            // Stop monitoring first
            stopMonitoring();
        }

        // Perform final auto-save before cleanup
        if (autoSaveEnabled && stateDebugger != null && isTaskRunning) {
            Log.i(TAG, "Performing final auto-save before cleanup");
            stateDebugger.dumpFullState("FINAL_STATE_BEFORE_CLEANUP");
        }

        if (isWaitingForUserConfirmation) {
            setWaitingForUserConfirmation(false);
        }

        // Stop all scheduled tasks
//        shutdownScheduler();

        // Cancel any ongoing navigation
        cancelNavigation(false);

        LOG.info("GeneralNavigationHandler cleaned up");
    }

    public void setNavigateState(NavigationState state) {
        currentState = state;
    }

    public Position getCurrentTaskTarget() {
        return currentTaskTarget;
    }

    /**
     * Get current task route (list of positions)
     */
    public List<Position> getCurrentTaskRoute() {
        synchronized (navigationLock) {
            return currentTaskRoute != null ? new ArrayList<>(currentTaskRoute) : new ArrayList<>();
        }
    }

    public BasicViewModel getBasicViewModel() {
        return basicViewModel;
    }

    public ElevatorViewModel getElevatorViewModel() {
        return elevatorViewModel;
    }

    public MapViewModel getMapViewModel() {
        return mapViewModel;
    }

    public AgvStatusResponse getLatestStatus() {
        synchronized (navigationLock) {
            return latestStatusResponse;
        }
    }

    public boolean isWebSocketConnected() {
        return statusClient != null && statusClient.isConnected();
    }

//    public void checkStatusConnection() {
//        //Log.d("checkStatusConnection", "🔍 Status Connection Check:");
//        if (statusClient != null) {
//            Log.d("checkStatusConnection", "   - WebSocket connected: " + statusClient.isConnected());
//            // Call the new stats method if available
//            try {
//                java.lang.reflect.Method method = statusClient.getClass().getMethod("printStats");
//                method.invoke(statusClient);
//            } catch (Exception e) {
//                Log.d("checkStatusConnection", "   - Stats method not available");
//            }
//        } else {
//            Log.d("checkStatusConnection", "   - Status fetcher is null");
//        }
//
//        synchronized (navigationLock) {
//            Log.d("checkStatusConnection", "   - Latest update time: " + lastStatusUpdateTime);
//            Log.d("checkStatusConnection", "   - Time since last update: " +
//                    (System.currentTimeMillis() - lastStatusUpdateTime) + "ms");
//        }
//    }

    public void jackControl(int state) {
        synchronized(navigationLock) {
            if (state == 0) {
                commandClient.jackUnload();
            } else if (state == 1) {
                commandClient.jackLoad();
            }
        }
    }

    /**
     * Set current Jack task loop information
     */
    public void setJackLoopInfo(int currentLoopIndex, int totalLoops) {
        this.currentJackLoopIndex = currentLoopIndex;
        this.currentJackTotalLoops = totalLoops;
        if (stateDebugger != null) {
            stateDebugger.logInfo(String.format("Jack loop info set: currentLoop=%d, totalLoops=%d",
                    currentLoopIndex, totalLoops));
        }
    }

    /**
     * Get current Jack task loop index
     */
    public int getCurrentJackLoopIndex() {
        return currentJackLoopIndex;
    }

    /**
     * Get current Jack task total loops
     */
    public int getCurrentJackTotalLoops() {
        return currentJackTotalLoops;
    }

    /**
     * Set current Cruise task loop information
     */
    public void setCruiseLoopInfo(int currentLoopIndex, int totalLoops) {
        this.currentCruiseLoopIndex = currentLoopIndex;
        this.currentCruiseTotalLoops = totalLoops;
        if (stateDebugger != null) {
            stateDebugger.logInfo(String.format("Cruise loop info set: currentLoop=%d, totalLoops=%d",
                    currentLoopIndex, totalLoops));
        }
    }

    /**
     * Get current Cruise task loop index
     */
    public int getCurrentCruiseLoopIndex() {
        return currentCruiseLoopIndex;
    }

    /**
     * Get current Cruise task total loops
     */
    public int getCurrentCruiseTotalLoops() {
        return currentCruiseTotalLoops;
    }

    public void setAutoChargeTask(boolean state) {
        synchronized (navigationLock) {
            hasAutoChargeTask = state;
        }
    }

    public boolean getAutoChargeStatus() {
        synchronized (navigationLock) {
            return hasAutoChargeTask;
        }
    }

    public void addTaskCompletionListener(TaskCompletionListener listener) {
        synchronized (taskCompletionListeners) {
            if (!taskCompletionListeners.contains(listener)) {
                taskCompletionListeners.add(listener);
            }
        }
    }

    public void removeTaskCompletionListener(TaskCompletionListener listener) {
        synchronized (taskCompletionListeners) {
            taskCompletionListeners.remove(listener);
        }
    }

    public void addWaypointArrivedListener(WaypointArrivedListener listener) {
        synchronized (waypointArrivedListeners) {
            if (!waypointArrivedListeners.contains(listener)) {
                waypointArrivedListeners.add(listener);
            }
        }
    }

    public void removeWaypointArrivedListener(WaypointArrivedListener listener) {
        synchronized (waypointArrivedListeners) {
            waypointArrivedListeners.remove(listener);
        }
    }

    public void addEffectiveProgressListener(EffectiveProgressListener listener) {
        synchronized (effectiveProgressListeners) {
            if (!effectiveProgressListeners.contains(listener)) {
                effectiveProgressListeners.add(listener);
            }
        }
    }

    public void removeEffectiveProgressListener(EffectiveProgressListener listener) {
        synchronized (effectiveProgressListeners) {
            effectiveProgressListeners.remove(listener);
        }
    }

    // Add notification methods
    private void notifyTaskCompleted() {
        taskViewModel.markTaskCompleted();
        synchronized (taskCompletionListeners) {
            for (TaskCompletionListener listener : taskCompletionListeners) {
                try {
                    listener.onTaskCompleted();
                } catch (Exception e) {
                    LOG.error("Error notifying task completion listener: {}", e.getMessage());
                }
            }
        }
    }

    private void notifyTaskCancelled() {
        taskViewModel.markTaskIdle();
        synchronized (taskCompletionListeners) {
            for (TaskCompletionListener listener : taskCompletionListeners) {
                try {
                    listener.onTaskCancelled();
                } catch (Exception e) {
                    LOG.error("Error notifying task cancellation listener: {}", e.getMessage());
                }
            }
        }
    }

    private void notifyTaskFailed() {
        taskViewModel.markTaskIdle();
        synchronized (taskCompletionListeners) {
            for (TaskCompletionListener listener : taskCompletionListeners) {
                try {
                    listener.onTaskFailed();
                } catch (Exception e) {
                    LOG.error("Error notifying task failure listener: {}", e.getMessage());
                }
            }
        }
    }

    private void notifyWaypointArrived(int waypointIndex, Position position) {
        synchronized (waypointArrivedListeners) {
            for (WaypointArrivedListener listener : waypointArrivedListeners) {
                try {
                    listener.onWaypointArrived(waypointIndex, position);
                } catch (Exception e) {
                    LOG.error("Error notifying waypoint arrived listener: {}", e.getMessage());
                }
            }
        }
    }

    private void notifyEffectiveProgress(int currentEffectiveIndex, Position currentTarget) {
        synchronized (effectiveProgressListeners) {
            for (EffectiveProgressListener listener : effectiveProgressListeners) {
                try {
                    listener.onEffectiveProgressChanged(currentEffectiveIndex, currentTarget);
                } catch (Exception e) {
                    LOG.error("Error notifying effective progress listener: {}", e.getMessage());
                }
            }
        }
    }

    // ========== CONFIRMATION STATE METHODS ==========

    /**
     * Set waiting for user confirmation for the current position
     */
    public void setWaitingForUserConfirmation(boolean waiting) {
        synchronized (navigationLock) {
            this.isWaitingForUserConfirmation = waiting;

            // Get current position ID from the route
            long positionId = getCurrentPositionId();

            if (positionId != -1) {
                this.waitingPositionId = waiting ? positionId : -1;

                // Update central state manager
                confirmationManager.setPositionWaitingForConfirmation(positionId, waiting);

                // Also update in PositionMonitor for persistence
                updatePositionConfirmationState(positionId, waiting);

                Log.i(TAG, "Position " + positionId + " confirmation state set to " + waiting);
                TaskDebug1.log(String.format("[POS] setWaitingForUserConfirmation posId=%d waiting=%b routeId=%d",
                        positionId, waiting, currentRouteId));
            } else {
                // No current position - use global state
                confirmationManager.setGlobalConfirmationState(waiting);
                Log.i(TAG, "Global confirmation state set to " + waiting);
            }
        }
    }

    /**
     * Check if waiting for user confirmation (for current position)
     */
    public boolean isWaitingForUserConfirmation() {
        // First check instance cache
        if (isWaitingForUserConfirmation) {
            return true;
        }

        // Then check current position via state manager
        long positionId = getCurrentPositionId();
        if (positionId != -1 && confirmationManager.isPositionWaitingForConfirmation(positionId)) {
            // Sync instance cache
            this.isWaitingForUserConfirmation = true;
            this.waitingPositionId = positionId;
            return true;
        }

        return false;
    }

    /**
     * Check if a specific position is waiting for confirmation
     */
    public boolean isPositionWaitingForConfirmation(long positionId) {
        return confirmationManager.isPositionWaitingForConfirmation(positionId);
    }

    /**
     * Check if ANY position is waiting for confirmation
     */
    public boolean isAnyPositionWaitingForConfirmation() {
        return confirmationManager.isAnyPositionWaitingForConfirmation();
    }

    /**
     * Get current position ID from the active route
     */
    private long getCurrentPositionId() {
        if (currentRouteId == -1 || currentRoute == null) {
            return -1;
        }

        int currentIndex = currentRoute.getCurrentPositionIndex();
        List<Long> positionIds = currentRoute.getPositionIds();

        if (currentIndex >= 0 && currentIndex < positionIds.size()) {
            return positionIds.get(currentIndex);
        }

        return -1;
    }

    /**
     * Update confirmation state in PositionMonitor
     */
    private void updatePositionConfirmationState(long positionId, boolean waiting) {
        PositionMonitor position = PositionMonitor.getPosition(positionId);
        if (position != null) {
            position.setWaitingForConfirmation(waiting);
            PositionMonitor.updatePosition(position);
        }
    }
}
