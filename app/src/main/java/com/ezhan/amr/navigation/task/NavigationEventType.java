package com.ezhan.amr.navigation.task;

import com.ezhan.amr.R;

public enum NavigationEventType {
    DELIVERY_TASK(R.string.operation_navigating_to, R.string.error_task_running),
    JACK_TASK(R.string.operation_executing_task, R.string.error_task_running),
    CHARGE(R.string.operation_charging, R.string.error_task_running),
    PARK(R.string.operation_parking, R.string.error_task_running),
    CANCEL(R.string.operation_canceled, R.string.no_error),
    PAUSE(R.string.operation_pausing, R.string.no_error),
    RESUME(R.string.operation_resuming, R.string.no_error);

    public final int successResId;
    public final int errorResId;

    NavigationEventType(int successResId, int errorResId) {
        this.successResId = successResId;
        this.errorResId = errorResId;
    }
}
