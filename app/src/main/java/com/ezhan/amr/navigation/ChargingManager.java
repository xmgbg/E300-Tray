// ChargingManager.java - Modified version with multi-floor support

package com.ezhan.amr.navigation;

import static com.ezhan.amr.data.datatype.MultiBuildingMapPoints.generateMapName;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.task.CommandUtils;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ChargingManager {
    private static final String TAG = "ChargingManager";
    private static final long CHARGE_ANIMATION_RESTORE_DELAY_MS = 15_000L;
    private static boolean chargingAnimationSuppressedForSession = false;
    private static ScheduledExecutorService restoreScheduler;
    private static ScheduledFuture<?> restoreAnimationTask;
    private static final Object restoreLock = new Object();

    private WeakReference<Context> contextRef;
    private Dialog chargingDialog;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> delayResetTask;
    private TextView chargingProgressText;
    private ProgressBar chargingProgressBar;
    private ImageView chargingGif;
    private boolean isAnimationShowing = false;
    private boolean isActivityValid = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ChargingStatusListener listener;
    private boolean isCharging = false;
    private boolean isAutoCharging = false;
    private boolean isManualCharging = false;

    // Auto-charge related variables
    private boolean isChargeCompleteWaiting = false;
    private long chargeCompleteTime = 0;
    private BasicViewModel basicViewModel;
    private MapViewModel mapViewModel;
    private ElevatorViewModel elevatorViewModel;
    private GeneralNavigationHandler navigationHandler;
    public static int DEFAULT_AUTO_CHARGE_LEVEL = 20;
    private static final long CHARGE_COMPLETE_WAIT_TIME = 20000; // 20 seconds
    public static int DEFAULT_CONFIDENCE = 20;
    private Position currentPosition;
    // Add these with your other instance variables
    private long autoChargeTriggerTime = 0;
    private boolean isAutoChargePending = false;
    private long lastVoicePromptTime = 0;
    private static final long VOICE_PROMPT_COOLDOWN = 10000; // 10 seconds

    // New variables for cross-floor charging
    private Position targetChargePoint;
    private int targetChargeFloor;
    private boolean isCrossFloorCharging = false;
    private NavigationStateDebugger debugger;

    public interface ChargingStatusListener {
        void onChargingStatusChanged(boolean isCharging);
        boolean dispatchTouchEvent(MotionEvent event);
        boolean isActivityValid();
    }

    public ChargingManager(Context context,
                           GeneralNavigationHandler navigationHandler) {
        this.contextRef = new WeakReference<>(context);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.navigationHandler = navigationHandler;
        this.basicViewModel = navigationHandler.getBasicViewModel();
        this.mapViewModel = navigationHandler.getMapViewModel();
        this.elevatorViewModel = navigationHandler.getElevatorViewModel();
        this.debugger = NavigationStateDebugger.getInstance();
    }

    public void setListener(ChargingStatusListener listener) {
        this.listener = listener;
    }

    public void updateStatus(AgvStatusResponse response) {
        if (response != null && response.data != null) {
            boolean newChargingStatus = response.data.electricCurrentIn > 3;
            int batteryLevel = response.data.powerQuantity;
            double current = response.data.electricCurrentIn;

            // Only update if status changed
            if (newChargingStatus != isCharging) {
                isCharging = newChargingStatus;
                if (!isCharging) {
                    cancelAnimationRestore();
                    chargingAnimationSuppressedForSession = false;
                }
                if (listener != null) {
                    listener.onChargingStatusChanged(isCharging);
                }
            }

            handler.post(() -> {
                // Check if activity is still valid before showing dialog
                if (listener != null && !listener.isActivityValid()) {
                    dismissChargingAnimation();
                    return;
                }

                if (newChargingStatus && !chargingAnimationSuppressedForSession) {
                    showChargingAnimation(batteryLevel, current);
                } else if (!newChargingStatus) {
                    dismissChargingAnimation();
                }
            });

            // Handle auto-charge logic
            currentPosition = new Position(1, "current",
                    response.data.pos.x,
                    response.data.pos.y,
                    response.data.pos.theta);

            handleAutoCharge(response);
        }
    }

    public void handleAutoCharge(AgvStatusResponse response) {
        if (navigationHandler == null || basicViewModel == null) {
            return;
        }

//        currentPosition = navigationHandler.getCurrentPosition();

        int batteryLevel = response.data.powerQuantity;
        float batteryCurrent = response.data.electricCurrentIn;
        // Use BatteryLevelManager threshold instead of lowPower setting
        int autoChargeLevel = BatteryLevelManager.getInstance().getMinWorkingLevel();
        boolean isAutoChargeEnabled = Boolean.TRUE.equals(basicViewModel.getIsAutoCharge().getValue());
        boolean isEStop = response.data.emgStop;
        int confidence = (int) (response.data.poseProbability * 100);
        int workStatus = response.data.goalFinish;
        int chargeState = response.data.chargeStatus.state;
        boolean isIoTouch = response.data.chargeStatus.ioTouch;
        boolean isAutoCharging = chargeState == 100;
        boolean isManualCharging = chargeState == 300;
        boolean hasAutoChargeTask = navigationHandler.getAutoChargeStatus();
        boolean isTaskRunning = navigationHandler.isTaskRunning();

        String currentBuilding = mapViewModel.getCurrentBuilding();

        // Get all charge points across floors
        Map<Integer, Position> allChargePoints = mapViewModel.getAllChargePoints(currentBuilding);
        Map<Integer, Position> allParkPoints = mapViewModel.getAllParkPoints(currentBuilding);
        int currentFloor = mapViewModel.getCurrentFloor();

        // Check if there are any charge points configured
        if (allChargePoints.isEmpty()) {
            Log.w(TAG, "No charge points configured in any floor");

            return;
        }

        // Check if current floor has a charge point
        boolean currentFloorHasCharge = allChargePoints.containsKey(currentFloor);

        // Find nearest charge point if current floor doesn't have one
        int targetFloor = currentFloorHasCharge ? currentFloor :
                findNearestFloorWithPoint(currentFloor, allChargePoints.keySet());

        // Check auto-charge conditions
        boolean shouldAutoCharge = isAutoChargeEnabled
                && (!isAutoCharging && !isManualCharging) &&
                batteryLevel < autoChargeLevel &&
                workStatus != 0 &&
                confidence > DEFAULT_CONFIDENCE &&
                !isEStop &&
                batteryCurrent < 0.01 &&
                !hasAutoChargeTask &&
                !isTaskRunning;

        // Handle auto-charge delay logic
        if (shouldAutoCharge) {
            if (!isAutoChargePending) {
                // First time detecting low battery - start the delay period
                isAutoChargePending = true;
                autoChargeTriggerTime = System.currentTimeMillis();
                lastVoicePromptTime = 0; // Reset voice prompt timer

                String floorMessage = currentFloorHasCharge ?
                        "" : "，将前往" + targetFloor + "楼充电";
                Log.d(TAG, "Low battery detected. Starting 30-second delay before auto-charge. " +
                        "Battery level: " + batteryLevel + "%, Target floor: " + targetFloor +
                        floorMessage);

                // Play first voice prompt immediately
                playLowBatteryVoicePrompt();
            }

            long timeSinceTrigger = System.currentTimeMillis() - autoChargeTriggerTime;

            // Continuous voice prompts every 5 seconds during the delay period
            if (timeSinceTrigger <= 30000) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastVoicePromptTime >= 5000) { // 5 second cooldown
                    playLowBatteryVoicePrompt();
                    lastVoicePromptTime = currentTime;
                }
            }

            // Start auto-charge after 30 seconds
            if (timeSinceTrigger >= 30000) {
                Log.d(TAG, "Auto-charge triggered after 30-second delay. Battery level: " +
                        batteryLevel + "%, Target floor: " + targetFloor);

                // Reset delay tracking
                isAutoChargePending = false;
                autoChargeTriggerTime = 0;
                lastVoicePromptTime = 0;

                // Get the target charge point
                Position chargePosition = allChargePoints.get(targetFloor);
                Position prechargePosition = mapViewModel.getPreChargePosition(targetFloor);

                // Validate charge points
                if (!isPositionValid(chargePosition)) {
                    Log.e(TAG, "Charge position on floor " + targetFloor + " is invalid");
                    navigationHandler.playVoicePrompt(VoiceKeyConstants.CHARGE_FAILED);
                    return;
                }

                if (prechargePosition != null && !isPositionValid(prechargePosition)) {
                    Log.e(TAG, "Pre-charge position on floor " + targetFloor + " is invalid");
                    navigationHandler.playVoicePrompt(VoiceKeyConstants.CHARGE_FAILED);
                    return;
                }

                // Start navigation to charge position (handles cross-floor automatically)
                startAutoChargeNavigation(targetFloor, chargePosition);
            }
        } else {
            // Conditions no longer met - reset delay tracking
            if (isAutoChargePending) {
                Log.d(TAG, "Auto-charge conditions no longer met, resetting delay timer");
                isAutoChargePending = false;
                autoChargeTriggerTime = 0;
                lastVoicePromptTime = 0;
            }
        }
        if (isAutoCharging &&
                batteryLevel >= BatteryLevelManager.getInstance().getFullLevel() &&
                workStatus != 0 &&
                confidence > DEFAULT_CONFIDENCE &&
                !isEStop &&
                batteryCurrent > 0.01 &&
                isIoTouch) {

            if (!isChargeCompleteWaiting) {
                isChargeCompleteWaiting = true;
                chargeCompleteTime = System.currentTimeMillis();
                Log.d(TAG, "Charge complete detected, waiting " + CHARGE_COMPLETE_WAIT_TIME/1000 +
                        " seconds before returning to park");
            }

            if (isChargeCompleteWaiting &&
                    (System.currentTimeMillis() - chargeCompleteTime) >= CHARGE_COMPLETE_WAIT_TIME) {
                // Reset waiting status
                isChargeCompleteWaiting = false;
                chargeCompleteTime = 0;

                // Get current floor (where charging completed)
                int currentFloorAfterCharge = mapViewModel.getCurrentFloor();

                // Get all park points
                Map<Integer, Position> parkPoints = allParkPoints;

                if (parkPoints.isEmpty()) {
                    Log.w(TAG, "No park points configured in any floor");
                    //navigationHandler.playVoicePrompt(VoiceKeyConstants.PARK_FAILED);
                    return;
                }

                // Check if current floor has a park point
                boolean currentFloorHasPark = parkPoints.containsKey(currentFloorAfterCharge);

                // Find nearest park point
                int targetParkFloor = currentFloorHasPark ? currentFloorAfterCharge :
                        findNearestFloorWithPoint(currentFloorAfterCharge, parkPoints.keySet());

                Position parkPosition = parkPoints.get(targetParkFloor);

                if (!isPositionValid(parkPosition)) {
                    Log.e(TAG, "Park position on floor " + targetParkFloor + " is invalid");
                    //navigationHandler.playVoicePrompt(VoiceKeyConstants.PARK_FAILED);
                    return;
                }

//                Log.d(TAG, "Returning to park point on floor " + targetParkFloor +
//                        (targetParkFloor != currentFloorAfterCharge ? " (different floor)" : ""));
//
//                // Navigate to park position
//                navigateToParkPosition(parkPosition, targetParkFloor);
            }
        }
    }

    /**
     * Find the nearest floor number from a set of available floors
     */
    private int findNearestFloorWithPoint(int currentFloor, java.util.Set<Integer> availableFloors) {
        int nearestFloor = currentFloor;
        int minDistance = Integer.MAX_VALUE;

        for (int floor : availableFloors) {
            int distance = Math.abs(floor - currentFloor);
            if (distance < minDistance) {
                minDistance = distance;
                nearestFloor = floor;
            }
        }

        Log.d(TAG, "findNearestFloorWithPoint: Current floor=" + currentFloor +
                ", nearest floor with point=" + nearestFloor + ", distance=" + minDistance);

        return nearestFloor;
    }

    /**
     * Start auto-charge navigation to specified floor
     */
    public void startAutoChargeNavigation(int targetFloor, Position chargePosition) {
        if (navigationHandler == null || chargePosition == null) {
            return;
        }

        if (currentPosition == null) {
            currentPosition = navigationHandler.getCurrentPosition();
        }

        int currentFloor = mapViewModel.getCurrentFloor();

        // Set navigation task flag
        navigationHandler.setAutoChargeTask(true);

        // Prepare charge position for navigation
        chargePosition.setName("Charge");
        chargePosition.setMessage(contextRef.get().getString(R.string.navigating_to_task_point, "Charge"));
        chargePosition.setFloor(String.valueOf(targetFloor));
        chargePosition.setTaskType(4);

        List<Position> chargeRoute = new ArrayList<>();
        chargeRoute.add(chargePosition);

        // Generate full path (handles cross-floor automatically)
        chargeRoute = generateFullPositionList(currentPosition, chargeRoute);

        if (chargeRoute == null) {
            Log.e(TAG, "Failed to generate path to charge point on floor " + targetFloor);
            return;
        }

        // Show message if going to different floor
        if (targetFloor != currentFloor) {
            Context context = contextRef.get();
            String message = context != null
                    ? context.getString(R.string.no_charge_point_go_floor, targetFloor)
                    : String.valueOf(targetFloor);
            handler.post(() -> {
                if (contextRef.get() != null) {
                    android.widget.Toast.makeText(contextRef.get(), message,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
            Log.d(TAG, message);
        }

        MyApplication.getInstance().getTaskViewModel()
                .markTaskExecuting("charge", chargePosition.getName());
        navigationHandler.startNavigation(chargeRoute, () -> {
            Log.d(TAG, "Auto-charge navigation completed to floor " + targetFloor);
        });
    }

//    /**
//     * Navigate to park position (handles cross-floor)
//     */
//    private void navigateToParkPosition(Position parkPosition, int targetFloor) {
//        if (navigationHandler == null || parkPosition == null) {
//            return;
//        }
//
//        int currentFloor = mapViewModel.getCurrentFloor();
//
//        // Prepare park position for navigation
//        parkPosition.setName("Park");
//        parkPosition.setMessage(contextRef.get().getString(R.string.navigating_to_task_point, "Park"));
//        parkPosition.setFloor(String.valueOf(targetFloor));
//        parkPosition.setTaskType(5);
//
//        List<Position> parkRoute = new ArrayList<>();
//        parkRoute.add(parkPosition);
//
//        // Generate full path (handles cross-floor automatically)
//        parkRoute = generateFullPositionList(currentPosition, parkRoute);
//
//        if (parkRoute == null) {
//            Log.e(TAG, "Failed to generate path to park point on floor " + targetFloor);
//            return;
//        }
//
//        // Show message if going to different floor
//        if (targetFloor != currentFloor) {
//            String message = "充电完成，将返回" + targetFloor + "楼驻车点";
//            handler.post(() -> {
//                if (contextRef.get() != null) {
//                    android.widget.Toast.makeText(contextRef.get(), message,
//                            android.widget.Toast.LENGTH_LONG).show();
//                }
//            });
//            Log.d(TAG, message);
//        }
//
//        navigationHandler.startNavigation(parkRoute, () -> {
//            Log.d(TAG, "Returned to park on floor " + targetFloor + " after charging complete");
//            navigationHandler.setAutoChargeTask(false);
//        });
//    }

    /**
     * Original method for backward compatibility - uses nearest floor logic
     */
    public void startAutoChargeNavigation() {
        if (mapViewModel == null) return;

        int currentFloor = mapViewModel.getCurrentFloor();
        String currentBuilding = mapViewModel.getCurrentBuilding();
        Map<Integer, Position> allChargePoints = mapViewModel.getAllChargePoints(currentBuilding);

        if (allChargePoints.isEmpty()) {
            Log.e(TAG, "No charge points configured");
            navigationHandler.playVoicePrompt(VoiceKeyConstants.CHARGE_FAILED);
            return;
        }

        // Check if current floor has charge point
        boolean currentFloorHasCharge = allChargePoints.containsKey(currentFloor);

        // Find nearest charge point
        int targetFloor = currentFloorHasCharge ? currentFloor :
                findNearestFloorWithPoint(currentFloor, allChargePoints.keySet());

        Position chargePosition = allChargePoints.get(targetFloor);

        if (chargePosition != null && isPositionValid(chargePosition)) {
            startAutoChargeNavigation(targetFloor, chargePosition);
        } else {
            Log.e(TAG, "Invalid charge position on floor " + targetFloor);
            navigationHandler.playVoicePrompt(VoiceKeyConstants.CHARGE_FAILED);
        }
    }

    // Add this helper method to play the low battery voice prompt
    private void playLowBatteryVoicePrompt() {
        handler.post(() -> {
            if (navigationHandler != null) {
                // Use the navigation handler's voice prompt system
                navigationHandler.playVoicePrompt(VoiceKeyConstants.LOW_BATTERY);
                Log.d(TAG, "Playing low battery voice prompt - Battery level: " +
                        (basicViewModel != null ? basicViewModel.getLowPower().getValue() : "unknown") + "%");
            } else {
                Log.w(TAG, "Navigation handler is null, cannot play voice prompt");
            }
        });
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
                navigationHandler.playVoicePrompt(VoiceKeyConstants.INVALID_ELEVATOR_TASK);
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

    private void initChargingDialog() {
        if (chargingDialog != null) {
            return;
        }

        Context context = contextRef.get();
        if (context == null) {
            Log.w(TAG, "Context is null, cannot create dialog");
            return;
        }

        // Check if the context is still valid (activity not destroyed)
        if (!isContextValid(context)) {
            Log.w(TAG, "Context is not valid for dialog creation");
            return;
        }

        try {
            chargingDialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            chargingDialog.setContentView(R.layout.dialog_charging);

            View dialogBackground = chargingDialog.findViewById(R.id.dialogBackground);
            if (dialogBackground != null) {
                dialogBackground.setOnClickListener(v -> dismissChargingAnimation());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating charging dialog", e);
            chargingDialog = null;
        }
    }

    private boolean isContextValid(Context context) {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            return !activity.isFinishing() && !activity.isDestroyed();
        }
        return true;
    }

    private void showChargingAnimation(int batteryLevel, double current) {
        handler.post(() -> {
            synchronized (ChargingManager.this) {
                if (chargingAnimationSuppressedForSession) {
                    return;
                }

                // Check if activity is still valid
                if (listener != null && !listener.isActivityValid()) {
                    return;
                }

                try {
                    if (chargingDialog == null) {
                        initChargingDialog();
                    }

                    if (chargingDialog == null) {
                        Log.w(TAG, "Charging dialog is null, cannot show");
                        return;
                    }

                    // Check if context is still valid before showing
                    Context context = contextRef.get();
                    if (context == null || !isContextValid(context)) {
                        Log.w(TAG, "Context not valid for showing dialog");
                        return;
                    }

                    if (!isAnimationShowing && !chargingDialog.isShowing()) {
                        chargingDialog.show();
                        isAnimationShowing = true;
                        initChargingViews();
                        Log.d(TAG, "充电动画显示 - 电量: " + batteryLevel + "%, 电流: " + current + "mA");
                    } else if (isAnimationShowing) {
                        updateChargingViews(batteryLevel);
                        Log.d(TAG, "充电动画更新 - 电量: " + batteryLevel + "%");
                    }

                    updateChargingViews(batteryLevel);
                    loadChargingGif();

                } catch (Exception e) {
                    Log.e(TAG, "显示充电动画错误", e);
                    isAnimationShowing = false;
                }
            }
        });
    }

    private void initChargingViews() {
        if (chargingDialog == null) return;

        if (chargingProgressText == null) {
            chargingProgressText = chargingDialog.findViewById(R.id.charging_progress_text);
        }
        if (chargingProgressBar == null) {
            chargingProgressBar = chargingDialog.findViewById(R.id.charging_progress_bar);
        }
        if (chargingGif == null) {
            chargingGif = chargingDialog.findViewById(R.id.charging_gif);
        }
    }

    private void updateChargingViews(int batteryLevel) {
        if (chargingProgressText != null) {
            chargingProgressText.setText(batteryLevel + "%");
        }
        if (chargingProgressBar != null) {
            chargingProgressBar.setProgress(batteryLevel);
        }
    }

    /**
     * Check if a position is properly set up (not the default values)
     */
    private boolean isPositionValid(Position position) {
        if (position == null) {
            return false;
        }

        // Check if it's the default position (x=1.0, y=1.0, yaw=0.0)
        boolean isDefaultPosition = Math.abs(position.getPosX() - 1.0) < 0.001 &&
                Math.abs(position.getPosY() - 1.0) < 0.001 &&
                Math.abs(position.getYaw()) < 0.001;

        // Also check if map name is not null/empty
        boolean hasValidMap = position.getMapName() != null && !position.getMapName().isEmpty();

        return !isDefaultPosition && hasValidMap;
    }

    private void loadChargingGif() {
        if (chargingGif != null) {
            try {
                Context context = contextRef.get();
                if (context != null && isContextValid(context)) {
                    Glide.with(context)
                            .asGif()
                            .load(R.drawable.robot_charging)
                            .override(300, 300)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .into(chargingGif);
                }
            } catch (Exception e) {
                Log.e(TAG, "加载充电动画GIF错误", e);
            }
        }
    }

    public void dismissChargingAnimation() {
        markChargingAnimationSuppressedForSession();
        if (!isAnimationShowing) return;

        handler.post(() -> {
            try {
                if (chargingGif != null) {
                    Context context = contextRef.get();
                    if (context != null) {
                        Glide.with(context).clear(chargingGif);
                    }
                }

                if (chargingDialog != null && chargingDialog.isShowing()) {
                    // Check if context is still valid before dismissing
                    Context context = contextRef.get();
                    if (context != null && isContextValid(context)) {
                        chargingDialog.dismiss();
                    }
                }
                isAnimationShowing = false;
                Log.d(TAG, "充电动画关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭充电动画错误", e);
                isAnimationShowing = false;
            }
        });
    }

    public static void suppressAnimationForActiveCharging(AgvStatusResponse status) {
        if (status != null && status.data != null && status.data.electricCurrentIn > 3) {
            suppressAndScheduleRestore();
        }
    }

    private void markChargingAnimationSuppressedForSession() {
        if (isCharging) {
            suppressAndScheduleRestore();
        }
    }

    private static void suppressAndScheduleRestore() {
        synchronized (restoreLock) {
            chargingAnimationSuppressedForSession = true;
            scheduleAnimationRestore();
        }
    }

    private static void scheduleAnimationRestore() {
        synchronized (restoreLock) {
            cancelAnimationRestoreLocked();
            if (restoreScheduler == null || restoreScheduler.isShutdown()) {
                restoreScheduler = Executors.newSingleThreadScheduledExecutor();
            }
            restoreAnimationTask = restoreScheduler.schedule(() -> {
                synchronized (restoreLock) {
                    chargingAnimationSuppressedForSession = false;
                    restoreAnimationTask = null;
                }
                Log.d(TAG, "Charging animation restore allowed after 15s idle");
            }, CHARGE_ANIMATION_RESTORE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void cancelAnimationRestore() {
        synchronized (restoreLock) {
            cancelAnimationRestoreLocked();
        }
    }

    private static void cancelAnimationRestoreLocked() {
        if (restoreAnimationTask != null) {
            restoreAnimationTask.cancel(false);
            restoreAnimationTask = null;
        }
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (isCharging && event.getAction() == MotionEvent.ACTION_DOWN) {
            dismissChargingAnimation();
            return true;
        }
        return false;
    }

    private void cancelDelayTask() {
        synchronized (this) {
            if (delayResetTask != null) {
                delayResetTask.cancel(false);
                delayResetTask = null;
            }
        }
    }

    public void cleanup() {
        handler.post(() -> {
            cancelDelayTask();
            dismissChargingAnimation();

            if (scheduler != null) {
                scheduler.shutdownNow();
            }

            // Properly nullify references
            chargingProgressText = null;
            chargingProgressBar = null;
            chargingGif = null;

            if (chargingDialog != null) {
                try {
                    if (chargingDialog.isShowing()) {
                        chargingDialog.dismiss();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error dismissing dialog during cleanup", e);
                }
                chargingDialog = null;
            }

            contextRef.clear();
            listener = null;
        });
    }

    public boolean isCharging() {
        return isCharging;
    }

    public boolean isAnimationShowing() {
        return isAnimationShowing;
    }
}
