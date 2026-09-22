package com.ezhan.amr.navigation;

import android.content.Context;
import android.content.Intent;

import com.ezhan.amr.data.datatype.Position;

import java.io.Serializable;
import java.util.List;

// BroadcastHelper.java
public class BroadcastHelper {
    public static final String ACTION_PAUSE = "com.ezhan.amr.ACTION_PAUSE";
    public static final String ACTION_RESUME = "com.ezhan.amr.ACTION_RESUME";
    public static final String ACTION_CANCEL = "com.ezhan.amr.ACTION_CANCEL";
    /** 放行：提前结束站点停留倒计时，继续前往下一点位 */
    public static final String ACTION_RELEASE = "com.ezhan.amr.ACTION_RELEASE";

    public static final String ACTION_DELIVERY_TASK_NAME = "com.ezhan.amr.ACTION_DELIVERY_TASK_NAME";
    public static final String ACTION_JACK_TASK_NAME = "com.ezhan.amr.ACTION_JACK_TASK_NAME";
    public static final String ACTION_CRUISE_TASK_NAME = "com.ezhan.amr.ACTION_CRUISE_TASK_NAME";
    public static final String ACTION_MAIN_TASK_NAME = "com.ezhan.amr.ACTION_MAIN_TASK_NAME";
    public static final String ACTION_DELIVERY_TASK_OBJECT = "com.ezhan.amr.ACTION_DELIVERY_TASK_OBJECT";
    public static final String ACTION_JACK_TASK_OBJECT = "com.ezhan.amr.ACTION_JACK_TASK_OBJECT";
    public static final String ACTION_CRUISE_TASK_OBJECT = "com.ezhan.amr.ACTION_CRUISE_TASK_OBJECT";
    public static final String ACTION_MAIN_TASK_OBJECT = "com.ezhan.amr.ACTION_MAIN_TASK_OBJECT";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_EXTERNAL_TASK_ID = "external_task_id";
    public static final String EXTRA_EXTERNAL_TASK_NAME = "external_task_name";
    public static final String EXTRA_TASK_NAME = "task_name";
    public static final String EXTRA_TASK_OBJECT = "task_object"; // New extra for task objects

    private static BroadcastHelper instance;
    private final Context context;

    private BroadcastHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new BroadcastHelper(context);
        }
    }

    public static BroadcastHelper getInstance() {
        if (instance == null) {
            throw new IllegalStateException("BroadcastHelper not initialized");
        }
        return instance;
    }

    public void sendPauseCommand() {
        Intent intent = new Intent(ACTION_PAUSE);
        context.sendBroadcast(intent);
    }

    public void sendResumeCommand() {
        Intent intent = new Intent(ACTION_RESUME);
        context.sendBroadcast(intent);
    }

    public void sendCancelCommand() {
        Intent intent = new Intent(ACTION_CANCEL);
        context.sendBroadcast(intent);
    }

    /** 发送放行命令：提前结束当前站点停留倒计时 */
    public void sendReleaseCommand() {
        Intent intent = new Intent(ACTION_RELEASE);
        context.sendBroadcast(intent);
    }

    public void sendDeliveryByTaskName(int taskId, String taskName) {
        Intent intent = new Intent(ACTION_DELIVERY_TASK_NAME);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_TASK_NAME, taskName);
        context.sendBroadcast(intent);
    }

    public void sendJackByTaskName(int taskId, String taskName) {
        sendJackByTaskName(taskId, taskName, null, null);
    }

    public void sendJackByTaskName(int taskId, String taskName, String externalTaskId) {
        sendJackByTaskName(taskId, taskName, externalTaskId, null);
    }

    public void sendJackByTaskName(int taskId, String taskName, String externalTaskId, String externalTaskName) {
        Intent intent = new Intent(ACTION_JACK_TASK_NAME);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_TASK_NAME, taskName);
        if (externalTaskId != null && !externalTaskId.trim().isEmpty()) {
            intent.putExtra(EXTRA_EXTERNAL_TASK_ID, externalTaskId.trim());
        }
        if (externalTaskName != null && !externalTaskName.trim().isEmpty()) {
            intent.putExtra(EXTRA_EXTERNAL_TASK_NAME, externalTaskName.trim());
        }
        context.sendBroadcast(intent);
    }

    public void sendCruiseByTaskName(int taskId, String taskName) {
        sendCruiseByTaskName(taskId, taskName, null, null);
    }

    public void sendCruiseByTaskName(int taskId, String taskName, String externalTaskId) {
        sendCruiseByTaskName(taskId, taskName, externalTaskId, null);
    }

    public void sendCruiseByTaskName(int taskId, String taskName, String externalTaskId, String externalTaskName) {
        Intent intent = new Intent(ACTION_CRUISE_TASK_NAME);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_TASK_NAME, taskName);
        if (externalTaskId != null && !externalTaskId.trim().isEmpty()) {
            intent.putExtra(EXTRA_EXTERNAL_TASK_ID, externalTaskId.trim());
        }
        if (externalTaskName != null && !externalTaskName.trim().isEmpty()) {
            intent.putExtra(EXTRA_EXTERNAL_TASK_NAME, externalTaskName.trim());
        }
        context.sendBroadcast(intent);
    }

    public void sendMainByTaskName(int taskId, String taskName) {
        Intent intent = new Intent(ACTION_MAIN_TASK_NAME);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_TASK_NAME, taskName);
        context.sendBroadcast(intent);
    }

    // New methods - by task objects
    public void sendDeliveryByTaskObject(List<Position> selectedPosition) {
        Intent intent = new Intent(ACTION_DELIVERY_TASK_OBJECT);
        intent.putExtra(EXTRA_TASK_OBJECT, (Serializable) selectedPosition);
        // For delivery tasks, we only have ID, so we don't put an object
        context.sendBroadcast(intent);
    }

    public void sendJackByTaskObject(List<Position> selectedPosition) {
        Intent intent = new Intent(ACTION_JACK_TASK_OBJECT);
        intent.putExtra(EXTRA_TASK_OBJECT, (Serializable) selectedPosition); // Send the actual JackTask object
        context.sendBroadcast(intent);
    }

    public void sendCruiseByTaskObject(List<Position> selectedPosition) {
        Intent intent = new Intent(ACTION_CRUISE_TASK_OBJECT);
        intent.putExtra(EXTRA_TASK_OBJECT, (Serializable) selectedPosition); // Send the actual CruiseTask object
        context.sendBroadcast(intent);
    }

    public void sendMainByTaskObject(List<Position> selectedPosition) {
        Intent intent = new Intent(ACTION_MAIN_TASK_OBJECT);
        intent.putExtra(EXTRA_TASK_OBJECT, (Serializable) selectedPosition); // Send the actual MainTask object
        context.sendBroadcast(intent);
    }
}
