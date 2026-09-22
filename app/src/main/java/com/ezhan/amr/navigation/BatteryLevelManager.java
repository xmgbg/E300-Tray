package com.ezhan.amr.navigation;

import android.util.Log;

import com.ezhan.amr.data.datatype.AgvStatusResponse;

/**
 * BatteryLevelManager - Singleton state machine for battery level management.
 */
public class BatteryLevelManager {
    private static final String TAG = "BatteryLevelManager";

    private static volatile BatteryLevelManager instance;

    private int criticalLevel = 5;
    private int minWorkingLevel = 20;
    private int idleLevel = 60;
    private int fullLevel = 100;

    private BatteryState currentState = BatteryState.NORMAL;
    private int currentBatteryLevel = 100;
    private boolean isCurrentlyCharging = false;

    private BatteryLevelManager() {}

    public static BatteryLevelManager getInstance() {
        if (instance == null) {
            synchronized (BatteryLevelManager.class) {
                if (instance == null) {
                    instance = new BatteryLevelManager();
                }
            }
        }
        return instance;
    }

    /**
     * Update thresholds from persisted settings.
     * lowLevelLegacy is ignored; kept for datastore migration compatibility.
     */
    public void updateThresholds(int critical, int lowLevelLegacy, int minWorking, int idle, int full) {
        int effectiveMinWorking = Math.max(minWorking, lowLevelLegacy);
        this.criticalLevel = critical;
        this.minWorkingLevel = effectiveMinWorking;
        this.idleLevel = idle;
        this.fullLevel = full;
        Log.d(TAG, String.format(
                "Thresholds updated: critical=%d, minWorking=%d, idleChargeUpper=%d, full=%d",
                critical, effectiveMinWorking, idle, full));
    }

    public BatteryState updateStatus(AgvStatusResponse response) {
        if (response == null || response.data == null) return null;

        int battery = response.data.powerQuantity;
        boolean charging = response.data.electricCurrentIn > 3;

        BatteryState newState = computeState(battery, charging);

        if (newState != currentState) {
            Log.d(TAG, String.format("Battery state transition: %s -> %s (battery=%d%%, charging=%b)",
                    currentState, newState, battery, charging));
            currentState = newState;
        }

        currentBatteryLevel = battery;
        isCurrentlyCharging = charging;
        return newState;
    }

    private BatteryState computeState(int battery, boolean charging) {
        if (charging && battery >= fullLevel) return BatteryState.FULL;
        if (charging) return BatteryState.CHARGING;
        if (battery <= criticalLevel) return BatteryState.CRITICAL;
        if (battery <= minWorkingLevel) return BatteryState.LOW;
        if (battery <= idleLevel) return BatteryState.IDLE;
        return BatteryState.NORMAL;
    }

    /**
     * Whether the robot can accept a new task (non-charge, non-park).
     * Charging state does not block tasks; only actual battery level vs min working threshold matters.
     */
    public boolean canAcceptNewTask() {
        return currentBatteryLevel > minWorkingLevel;
    }

    public boolean isCritical() {
        return currentState == BatteryState.CRITICAL;
    }

    public boolean isCharging() {
        return currentState == BatteryState.CHARGING || currentState == BatteryState.FULL;
    }

    public boolean isFull() {
        return currentState == BatteryState.FULL;
    }

    public BatteryState getCurrentState() { return currentState; }
    public int getCurrentBatteryLevel() { return currentBatteryLevel; }
    public int getCriticalLevel() { return criticalLevel; }

    /** Minimum working level: reject new tasks and trigger low-battery auto charge below this. */
    public int getMinWorkingLevel() { return minWorkingLevel; }

    /** @deprecated Use {@link #getMinWorkingLevel()} */
    @Deprecated
    public int getSafeLevel() { return minWorkingLevel; }

    /** @deprecated Merged into {@link #getMinWorkingLevel()} */
    @Deprecated
    public int getLowLevel() { return minWorkingLevel; }

    public int getIdleLevel() { return idleLevel; }
    public int getFullLevel() { return fullLevel; }
}
