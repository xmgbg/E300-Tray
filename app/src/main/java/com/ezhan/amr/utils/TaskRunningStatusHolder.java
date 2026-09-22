package com.ezhan.amr.utils;

import androidx.annotation.Nullable;

/**
 * Holds the manual isTaskRunning value for status reporting.
 * When no manual override is set, callers should fall back to the real navigation state.
 */
public final class TaskRunningStatusHolder {

    private static final TaskRunningStatusHolder INSTANCE = new TaskRunningStatusHolder();

    @Nullable
    private Boolean manualIsTaskRunning;

    private TaskRunningStatusHolder() {
    }

    public static TaskRunningStatusHolder getInstance() {
        return INSTANCE;
    }

    public boolean isManualOverrideActive() {
        return manualIsTaskRunning != null;
    }

    public boolean getManualIsTaskRunning() {
        return Boolean.TRUE.equals(manualIsTaskRunning);
    }

    /**
     * Resolve the value that should be reported in status parameters.
     */
    public boolean resolveIsTaskRunning(boolean realIsTaskRunning) {
        if (manualIsTaskRunning != null) {
            return manualIsTaskRunning;
        }
        return realIsTaskRunning;
    }

    /**
     * Toggle the reported isTaskRunning value. The first toggle switches to manual mode.
     *
     * @return the new reported value
     */
    public boolean toggle(boolean realIsTaskRunning) {
        boolean current = resolveIsTaskRunning(realIsTaskRunning);
        manualIsTaskRunning = !current;
        return manualIsTaskRunning;
    }

    public void clearManualOverride() {
        manualIsTaskRunning = null;
    }
}
