package com.ezhan.amr.navigation;

import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Central manager for user confirmation states across all navigation handlers
 * Tracks confirmation requirements at the position level
 */
public class ConfirmationStateManager {
    private static final String TAG = "ConfirmationManager";
    private static volatile ConfirmationStateManager instance;

    // Position-specific confirmation states
    private final ConcurrentHashMap<Long, Boolean> positionConfirmationStates = new ConcurrentHashMap<>();

    // Position-specific stay durations (in seconds)
    private final ConcurrentHashMap<Long, Integer> positionStayDurations = new ConcurrentHashMap<>();

    // Track which position is currently waiting for confirmation
    private final AtomicLong activeWaitingPositionId = new AtomicLong(-1);

    // Global state - true if ANY position is waiting for confirmation
    private final AtomicBoolean globalWaitingState = new AtomicBoolean(false);

    // Listeners for UI updates
    private final CopyOnWriteArrayList<ConfirmationListener> listeners = new CopyOnWriteArrayList<>();

    // Scheduler for auto-confirmation timeout
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> autoConfirmTasks = new ConcurrentHashMap<>();

    private ConfirmationStateManager() {
        Log.d(TAG, "ConfirmationStateManager initialized");
    }

    public static ConfirmationStateManager getInstance() {
        if (instance == null) {
            synchronized (ConfirmationStateManager.class) {
                if (instance == null) {
                    instance = new ConfirmationStateManager();
                }
            }
        }
        return instance;
    }

    // ========== POSITION-BASED CONFIRMATION METHODS ==========

    /**
     * Set stay duration for a position (in seconds)
     * @param positionId The position ID
     * @param stayDuration Stay duration in seconds (0 = no auto-confirm)
     */
    public void setPositionStayDuration(long positionId, int stayDuration) {
        if (stayDuration > 0) {
            positionStayDurations.put(positionId, stayDuration);
            Log.d(TAG, "Position " + positionId + " stay duration set to " + stayDuration + " seconds");
        } else {
            positionStayDurations.remove(positionId);
            Log.d(TAG, "Position " + positionId + " stay duration removed (no auto-confirm)");
        }
    }

    /**
     * Get stay duration for a position
     * @param positionId The position ID
     * @return Stay duration in seconds, or 0 if not set
     */
    public int getPositionStayDuration(long positionId) {
        return positionStayDurations.getOrDefault(positionId, 0);
    }

    /**
     * Set waiting for user confirmation for a specific position
     * @param positionId The position ID that requires confirmation
     * @param waiting True if waiting for confirmation, false if confirmed
     */
    public void setPositionWaitingForConfirmation(long positionId, boolean waiting) {
        boolean oldGlobalState = globalWaitingState.get();

        if (waiting) {
            // Start waiting for this position
            positionConfirmationStates.put(positionId, true);
            activeWaitingPositionId.set(positionId);
            globalWaitingState.set(true);

            Log.i(TAG, "Position " + positionId + " now waiting for user confirmation");

            // Schedule auto-confirmation if stay duration is set
            int stayDuration = positionStayDurations.getOrDefault(positionId, 0);
            if (stayDuration > 0) {
                scheduleAutoConfirm(positionId, stayDuration);
            }
        } else {
            // Cancel any pending auto-confirm task
            cancelAutoConfirm(positionId);

            // Confirmation received for this position
            positionConfirmationStates.remove(positionId);

            // Check if this was the active waiting position
            if (activeWaitingPositionId.get() == positionId) {
                // Find next waiting position if any
                long nextWaitingId = -1;
                for (Long pid : positionConfirmationStates.keySet()) {
                    if (Boolean.TRUE.equals(positionConfirmationStates.getOrDefault(pid, false))) {
                        nextWaitingId = pid;
                        break;
                    }
                }

                if (nextWaitingId != -1) {
                    activeWaitingPositionId.set(nextWaitingId);
                    Log.d(TAG, "Active waiting position changed to " + nextWaitingId);
                } else {
                    activeWaitingPositionId.set(-1);
                    globalWaitingState.set(false);
                    Log.d(TAG, "No positions waiting for confirmation - global state cleared");
                }
            }

            Log.i(TAG, "Position " + positionId + " confirmation received");
        }

        // Notify listeners
        notifyListeners(positionId, waiting, oldGlobalState, globalWaitingState.get());
    }

    /**
     * Schedule auto-confirmation after stay duration
     */
    private void scheduleAutoConfirm(long positionId, int stayDurationSeconds) {
        // Cancel any existing task for this position
        cancelAutoConfirm(positionId);

        Log.i(TAG, "Scheduling auto-confirm for position " + positionId + " after " + stayDurationSeconds + " seconds");

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            // Check if still waiting for this position
            if (isPositionWaitingForConfirmation(positionId)) {
                Log.i(TAG, "Auto-confirming position " + positionId + " after " + stayDurationSeconds + " seconds");
                setPositionWaitingForConfirmation(positionId, false);
            }
        }, stayDurationSeconds, TimeUnit.SECONDS);

        autoConfirmTasks.put(positionId, future);
    }

    /**
     * Cancel pending auto-confirm task for a position
     */
    private void cancelAutoConfirm(long positionId) {
        ScheduledFuture<?> future = autoConfirmTasks.remove(positionId);
        if (future != null) {
            future.cancel(false);
            Log.d(TAG, "Cancelled auto-confirm task for position " + positionId);
        }
    }

    /**
     * Set confirmation state independent of position (global override)
     * @param waiting True to set global waiting, false to clear all
     */
    public void setGlobalConfirmationState(boolean waiting) {
        boolean oldGlobalState = globalWaitingState.get();

        if (waiting) {
            // Global wait - but we don't associate with a specific position
            globalWaitingState.set(true);
            Log.i(TAG, "Global waiting state set to true (no specific position)");
        } else {
            // Clear all waiting states
            positionConfirmationStates.clear();
            activeWaitingPositionId.set(-1);
            globalWaitingState.set(false);
            Log.i(TAG, "All confirmation states cleared");
        }

        // Notify listeners with positionId = -1 for global changes
        notifyListeners(-1, waiting, oldGlobalState, globalWaitingState.get());
    }

    /**
     * Check if a specific position is waiting for confirmation
     */
    public boolean isPositionWaitingForConfirmation(long positionId) {
        return Boolean.TRUE.equals(positionConfirmationStates.getOrDefault(positionId, false));
    }

    /**
     * Check if ANY position is waiting for confirmation (global state)
     */
    public boolean isAnyPositionWaitingForConfirmation() {
        return globalWaitingState.get();
    }

    /**
     * Get the position ID that is currently waiting for confirmation
     */
    public long getActiveWaitingPositionId() {
        return activeWaitingPositionId.get();
    }

    /**
     * Get all position confirmation states as a map
     * @return Unmodifiable map of position ID to confirmation state
     */
    public Map<Long, Boolean> getAllPositionConfirmationStates() {
        return Collections.unmodifiableMap(new HashMap<>(positionConfirmationStates));
    }

    // ========== LISTENER MANAGEMENT ==========

    public void addListener(ConfirmationListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            Log.d(TAG, "Listener added, total: " + listeners.size());
        }
    }

    public void removeListener(ConfirmationListener listener) {
        listeners.remove(listener);
        Log.d(TAG, "Listener removed, total: " + listeners.size());
    }

    private void notifyListeners(long positionId, boolean newState,
                                 boolean oldGlobal, boolean newGlobal) {
        for (ConfirmationListener listener : listeners) {
            try {
                listener.onConfirmationStateChanged(positionId, newState, oldGlobal, newGlobal);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener: " + e.getMessage());
            }
        }
    }

    // ========== CLEANUP ==========

    /**
     * Clear all states for a specific position (when position is completed/cancelled)
     */
    public void clearPositionState(long positionId) {
        // Cancel auto-confirm task
        cancelAutoConfirm(positionId);

        // Remove stay duration
        positionStayDurations.remove(positionId);

        if (positionConfirmationStates.containsKey(positionId)) {
            positionConfirmationStates.remove(positionId);

            if (activeWaitingPositionId.get() == positionId) {
                // Find next waiting position
                long nextWaitingId = -1;
                for (Long pid : positionConfirmationStates.keySet()) {
                    if (Boolean.TRUE.equals(positionConfirmationStates.getOrDefault(pid, false))) {
                        nextWaitingId = pid;
                        break;
                    }
                }

                if (nextWaitingId != -1) {
                    activeWaitingPositionId.set(nextWaitingId);
                } else {
                    activeWaitingPositionId.set(-1);
                    globalWaitingState.set(false);
                }
            }

            Log.d(TAG, "Cleared confirmation state for position " + positionId);
        }
    }

    /**
     * Clear all states (for full reset)
     */
    public void clearAllStates() {
        // Cancel all auto-confirm tasks
        for (Long positionId : autoConfirmTasks.keySet()) {
            cancelAutoConfirm(positionId);
        }

        positionConfirmationStates.clear();
        positionStayDurations.clear();
        autoConfirmTasks.clear();
        activeWaitingPositionId.set(-1);
        globalWaitingState.set(false);
        Log.d(TAG, "All confirmation states cleared");
    }

    // ========== LISTENER INTERFACE ==========

    public interface ConfirmationListener {
        /**
         * Called when confirmation state changes
         * @param positionId The position ID that changed (-1 for global changes)
         * @param positionState The new state for this position
         * @param oldGlobalState Previous global state
         * @param newGlobalState New global state
         */
        void onConfirmationStateChanged(long positionId, boolean positionState,
                                        boolean oldGlobalState, boolean newGlobalState);
    }
}
