package com.ezhan.amr.navigation;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.ezhan.amr.data.datatype.CruiseTask;
import java.util.Calendar;
import java.util.Map;

/**
 * 巡航任务后台调度器 - 只负责安排定时任务
 */
public class CruiseTaskScheduler {
    private static final String TAG = "CruiseTaskScheduler";
    private static final int REQUEST_CODE_BASE = 2000; // Different from other request codes

    private static CruiseTaskScheduler instance;
    private final Context context;
    private final AlarmManager alarmManager;

    private CruiseTaskScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new CruiseTaskScheduler(context);
            Log.d(TAG, "CruiseTaskScheduler initialized");
        }
    }

    public static CruiseTaskScheduler getInstance() {
        if (instance == null) {
            throw new IllegalStateException("CruiseTaskScheduler not initialized");
        }
        return instance;
    }

    /**
     * 安排所有启用的自动巡航任务
     */
    public void scheduleAllTasks(Map<Integer, CruiseTask> taskMap, Map<Integer, Boolean> enableStatus) {
        if (taskMap == null || enableStatus == null) {
            Log.w(TAG, "No tasks to schedule");
            return;
        }

        // 先取消所有现有任务
        cancelAllScheduledTasks();

        for (CruiseTask task : taskMap.values()) {
            Boolean enabled = enableStatus.get(task.getId());
            if (task.isAuto() && enabled != null && enabled) {
                scheduleTask(task);
            }
        }

        Log.d(TAG, "Scheduled " + taskMap.size() + " auto tasks");
    }

    /**
     * 安排单个巡航任务
     */
    public void scheduleTask(CruiseTask task) {
        if (!task.isAuto()) {
            return;
        }

        Calendar taskTime = Calendar.getInstance();
        taskTime.set(Calendar.HOUR_OF_DAY, task.getHour());
        taskTime.set(Calendar.MINUTE, task.getMinute());
        taskTime.set(Calendar.SECOND, 0);
        taskTime.set(Calendar.MILLISECOND, 0);

        // 如果时间已经过去，安排到明天
        if (taskTime.getTimeInMillis() <= System.currentTimeMillis()) {
            taskTime.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Trigger the receiver first so it can skip the cruise while another task is running.
        Intent intent = ScheduledCruiseReceiver.createIntent(context, task.getId());

        int requestCode = REQUEST_CODE_BASE + task.getId();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 设置精确的闹钟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    taskTime.getTimeInMillis(),
                    pendingIntent
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    taskTime.getTimeInMillis(),
                    pendingIntent
            );
        } else {
            alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    taskTime.getTimeInMillis(),
                    pendingIntent
            );
        }

        Log.d(TAG, "Scheduled task: " + task.getName() + " at " +
                task.getHour() + ":" + task.getMinute());
    }

    /**
     * 取消单个任务的安排
     */
    public void cancelTask(int taskId) {
        Intent intent = ScheduledCruiseReceiver.createIntent(context, taskId);

        int requestCode = REQUEST_CODE_BASE + taskId;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "Cancelled task: " + taskId);
        }
    }

    /**
     * 取消所有已安排的任务
     */
    public void cancelAllScheduledTasks() {
        // 这里需要知道所有任务ID，可以从ViewModel获取或使用其他方式
        Log.d(TAG, "Cancel all scheduled tasks called");
        // 实际实现需要遍历所有已知任务ID并调用cancelTask
    }
}
