package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.TaskRecord;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class TaskViewModel extends AndroidViewModel {
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<List<TaskRecord>> taskRecords = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Integer> taskRecordRetentionDays = new MutableLiveData<>(30);
    public static final String TASK_STATUS_IDLE = "IDLE";
    public static final String TASK_STATUS_EXECUTING = "EXECUTING";
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";
    public static final String TASK_INFO_NONE = "None";
    private static final long TASK_COMPLETE_COOLDOWN_MS = 2000;
    private final MutableLiveData<String> currentTaskStatus = new MutableLiveData<>(TASK_STATUS_IDLE);
    private final Handler taskStatusHandler = new Handler(Looper.getMainLooper());
    private volatile String currentTaskStatusSnapshot = TASK_STATUS_IDLE;
    private volatile String currentTaskTypeSnapshot = TASK_INFO_NONE;
    private volatile String currentTaskNameSnapshot = TASK_INFO_NONE;
    private volatile String currentTaskIdSnapshot = TASK_INFO_NONE;
    private Runnable pendingCompleteRunnable;
    private long taskStatusVersion = 0;

    public static class TaskState {
        public final String status;
        public final String taskType;
        public final String taskName;
        public final String taskId;
        public final long version;
        public final long updatedAt;

        private TaskState(String status, String taskType, String taskName,
                          String taskId, long version, long updatedAt) {
            this.status = status;
            this.taskType = taskType;
            this.taskName = taskName;
            this.taskId = taskId;
            this.version = version;
            this.updatedAt = updatedAt;
        }
    }

    public TaskViewModel(@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        disposables.add(dataStoreManager.getTaskRecords()
                .subscribe(records -> {
                    taskRecords.postValue(records != null ? records : new ArrayList<>());
                    removeExpiredRecords(); // 加载时清理过期记录
                    reconcileStaleRunningTaskRecords(false);
                }, throwable -> {
                    Log.e("DataLoad", "加载历史任务记录失败", throwable);
                }));

        // 加载保存时长
        disposables.add(dataStoreManager.getTaskRecordRetentionDays()
                .subscribe(days -> {
                    taskRecordRetentionDays.postValue(days);
                }, throwable -> {
                    Log.e("DataLoad", "加载任务记录保存时长失败", throwable);
                }));

    }

    public LiveData<List<TaskRecord>> getTaskRecords() {
        return taskRecords;
    }

    public LiveData<String> getCurrentTaskStatus() {
        return currentTaskStatus;
    }

    public synchronized String getCurrentTaskStatusValue() {
        return currentTaskStatusSnapshot != null ? currentTaskStatusSnapshot : TASK_STATUS_IDLE;
    }

    public synchronized String getCurrentTaskTypeValue() {
        return normalizeTaskInfo(currentTaskTypeSnapshot);
    }

    public synchronized String getCurrentTaskNameValue() {
        return normalizeTaskInfo(currentTaskNameSnapshot);
    }

    public synchronized String getCurrentTaskIdValue() {
        return normalizeTaskInfo(currentTaskIdSnapshot);
    }

    public synchronized TaskState getCurrentTaskStateSnapshot() {
        return new TaskState(
                getCurrentTaskStatusValue(),
                getCurrentTaskTypeValue(),
                getCurrentTaskNameValue(),
                getCurrentTaskIdValue(),
                taskStatusVersion,
                System.currentTimeMillis());
    }

    public synchronized void setCurrentTaskStatus(String status) {
        Log.d("TaskStatus", "设置机器人任务状态为 " + status);

        if (status == null || status.isEmpty()) {
            status = TASK_STATUS_IDLE;
        }
        if (!status.equals(currentTaskStatusSnapshot)) {
            currentTaskStatusSnapshot = status;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                currentTaskStatus.setValue(status);
            } else {
                currentTaskStatus.postValue(status);
            }
        }
    }

    public synchronized void markTaskExecuting() {
        cancelPendingComplete();
        ensureExecutingTaskInfo();
        setCurrentTaskStatus(TASK_STATUS_EXECUTING);
    }

    public synchronized void markTaskExecuting(String taskType, String taskName) {
        markTaskExecuting(taskType, taskName, TASK_INFO_NONE);
    }

    public synchronized void markTaskExecuting(String taskType, String taskName, String taskId) {
        cancelPendingComplete();
        currentTaskTypeSnapshot = normalizeTaskInfo(taskType);
        currentTaskNameSnapshot = normalizeTaskInfo(taskName);
        String normalizedTaskId = normalizeTaskInfo(taskId);
        currentTaskIdSnapshot = TASK_INFO_NONE.equals(normalizedTaskId)
                ? createLocalTaskId()
                : normalizedTaskId;
        setCurrentTaskStatus(TASK_STATUS_EXECUTING);
    }

    public synchronized void markTaskExecutingIfUnset(String taskType, String taskName) {
        if (!TASK_STATUS_EXECUTING.equals(getCurrentTaskStatusValue())
                || TASK_INFO_NONE.equals(getCurrentTaskTypeValue())
                || TASK_INFO_NONE.equals(getCurrentTaskNameValue())
                || TASK_INFO_NONE.equals(getCurrentTaskIdValue())) {
            markTaskExecuting(taskType, taskName);
        }
    }

    public synchronized void markTaskCompleted() {
        if (TASK_STATUS_IDLE.equals(currentTaskStatusSnapshot)
                || TASK_STATUS_COMPLETED.equals(currentTaskStatusSnapshot)
                || TASK_INFO_NONE.equals(normalizeTaskInfo(currentTaskTypeSnapshot))) {
            return;
        }

        if (shouldReturnToIdleAfterCompletion(currentTaskTypeSnapshot)) {
            markTaskIdle();
            return;
        }

        long version = ++taskStatusVersion;
        if (pendingCompleteRunnable != null) {
            taskStatusHandler.removeCallbacks(pendingCompleteRunnable);
        }

        pendingCompleteRunnable = () -> {
            synchronized (TaskViewModel.this) {
                if (version != taskStatusVersion) {
                    return;
                }
                setCurrentTaskStatus(TASK_STATUS_COMPLETED);
                pendingCompleteRunnable = null;
            }
        };
        taskStatusHandler.postDelayed(pendingCompleteRunnable, TASK_COMPLETE_COOLDOWN_MS);
    }

    private boolean shouldReturnToIdleAfterCompletion(String taskType) {
        String normalizedTaskType = normalizeTaskInfo(taskType);
        return "charge".equalsIgnoreCase(normalizedTaskType)
                || "park".equalsIgnoreCase(normalizedTaskType);
    }

    public synchronized void markTaskIdle() {
        if (TASK_STATUS_COMPLETED.equals(currentTaskStatusSnapshot)) {
            return;
        }
        cancelPendingComplete();
        reconcileStaleRunningTaskRecords(true);
        currentTaskTypeSnapshot = TASK_INFO_NONE;
        currentTaskNameSnapshot = TASK_INFO_NONE;
        currentTaskIdSnapshot = TASK_INFO_NONE;
        setCurrentTaskStatus(TASK_STATUS_IDLE);
    }

    private synchronized void cancelPendingComplete() {
        taskStatusVersion++;
        if (pendingCompleteRunnable != null) {
            taskStatusHandler.removeCallbacks(pendingCompleteRunnable);
            pendingCompleteRunnable = null;
        }
    }

    private String normalizeTaskInfo(String value) {
        return value == null || value.trim().isEmpty() ? TASK_INFO_NONE : value.trim();
    }

    private void ensureExecutingTaskInfo() {
        if (TASK_INFO_NONE.equals(normalizeTaskInfo(currentTaskTypeSnapshot))) {
            currentTaskTypeSnapshot = "unknown";
        }
        if (TASK_INFO_NONE.equals(normalizeTaskInfo(currentTaskNameSnapshot))) {
            currentTaskNameSnapshot = "unknown";
        }
        if (TASK_INFO_NONE.equals(normalizeTaskInfo(currentTaskIdSnapshot))) {
            currentTaskIdSnapshot = createLocalTaskId();
        }
    }

    private String createLocalTaskId() {
        return "HMI-" + System.currentTimeMillis();
    }

    private void markTaskExecuting(TaskRecord record) {
        if (TASK_STATUS_EXECUTING.equals(getCurrentTaskStatusValue()) && hasCompleteTaskInfo()) {
            cancelPendingComplete();
            return;
        }
        if (record == null) {
            markTaskExecuting();
            return;
        }
        String recordTaskId = normalizeTaskInfo(record.getTaskId());
        markTaskExecuting(taskTypeToString(record.getType()), record.getTaskName(),
                TASK_INFO_NONE.equals(recordTaskId)
                        ? (record.getId() > 0 ? String.valueOf(record.getId()) : createLocalTaskId())
                        : recordTaskId);
    }

    private boolean hasCompleteTaskInfo() {
        return !TASK_INFO_NONE.equals(getCurrentTaskTypeValue())
                && !TASK_INFO_NONE.equals(getCurrentTaskNameValue())
                && !TASK_INFO_NONE.equals(getCurrentTaskIdValue());
    }

    private String taskTypeToString(int type) {
        switch (type) {
            case TaskRecord.TYPE_DELIVERY:
                return "delivery";
            case TaskRecord.TYPE_CRUISE:
                return "cruise";
            case TaskRecord.TYPE_JACK:
                return "jack";
            case TaskRecord.TYPE_CHARGE:
                return "charge";
            case TaskRecord.TYPE_PARKING:
                return "park";
            case TaskRecord.TYPE_STOP_CHARGE:
                return "stop_charge";
            case TaskRecord.TYPE_CALL:
                return "call";
            default:
                return "unknown";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 添加新任务记录
    public void addTaskRecord(TaskRecord record) {
        Log.d("DATA", "Saving record: " + record.getTaskName());
        List<TaskRecord> currentRecords = new ArrayList<>(taskRecords.getValue());

        // 为记录生成ID（查找最大ID+1）
        long maxId = 0;
        for (TaskRecord r : currentRecords) {
            if (r.getId() > maxId) maxId = r.getId();
        }
        record.setId(maxId + 1);
        markTaskExecuting(record);

        // 更新LiveData
        List<TaskRecord> newRecords = new ArrayList<>(taskRecords.getValue());
        newRecords.add(0, record);
        taskRecords.setValue(newRecords); // 关键更新

        currentRecords.add(0, record);
        saveTaskRecords(currentRecords);
        removeExpiredRecords(); // 添加新记录时清理过期记录
    }

    // 保存任务记录到DataStore
    private void saveTaskRecords(List<TaskRecord> records) {
        taskRecords.setValue(records);
        dataStoreManager.saveTaskRecords(records);
    }
    public void updateTaskRecord(TaskRecord updatedRecord) {
        List<TaskRecord> currentRecords = taskRecords.getValue();
        if (currentRecords == null) return;

        Log.d("TaskViewModel", "更新任务记录，ID: " + updatedRecord.getId() + ", 新状态: " + updatedRecord.getStatus());

        List<TaskRecord> newRecords = new ArrayList<>();
        boolean found = false;

        for (TaskRecord record : currentRecords) {
            if (record.getId() == updatedRecord.getId()) {
                // 记录找到并更新
                Log.d("TaskViewModel", "找到并更新任务: " + record.getId());
                newRecords.add(updatedRecord);  // 替换为更新后的记录
                found = true;
            } else {
                newRecords.add(record);
            }
        }

        if (found) {
            // 更新 LiveData
            taskRecords.setValue(newRecords);
            Log.d("TaskViewModel", "LiveData 已更新，记录数量: " + newRecords.size());

            // 持久化到 DataStore
            saveTaskRecords(newRecords);
            if (TaskRecord.STATUS_COMPLETED.equals(updatedRecord.getStatus())) {
                markTaskCompleted();
            } else if (isTerminalTaskStatus(updatedRecord.getStatus())) {
                markTaskIdle();
            } else {
                markTaskExecuting(updatedRecord);
            }
            Log.d("TaskViewModel", "数据已保存到 DataStore");
        } else {
            Log.w("TaskViewModel", "未找到ID为" + updatedRecord.getId() + "的任务记录");
        }
    }

    // 在 TaskViewModel.java 中添加
    public void cancelTaskRecord(long id) {
        List<TaskRecord> currentRecords = taskRecords.getValue();
        if (currentRecords == null) return;

        List<TaskRecord> newRecords = new ArrayList<>();
        boolean found = false;

        for (TaskRecord record : currentRecords) {
            if (record.getId() == id) {
                // 只创建需要更新的记录的副本
                TaskRecord cancelledRecord = new TaskRecord();
                cancelledRecord.setId(record.getId());
                cancelledRecord.setTaskId(record.getTaskId());
                cancelledRecord.setTaskName(record.getTaskName());
                cancelledRecord.setType(record.getType());
                cancelledRecord.setStatus(TaskRecord.STATUS_CANCELLED);
                cancelledRecord.setCreateTime(record.getCreateTime());
                cancelledRecord.setEndTime(System.currentTimeMillis());

                newRecords.add(cancelledRecord);
                found = true;
            } else {
                newRecords.add(record); // 其他记录保持不变
            }
        }

        if (found) {
            taskRecords.setValue(newRecords);
            saveTaskRecords(newRecords);
            Log.d("TaskViewModel", "任务 " + id + " 已取消");
        }
    }

    //根据任务id查找任务记录
    private TaskRecord findTaskRecord(long id) {
        List<TaskRecord> records = taskRecords.getValue();
        if (records == null) return null;
        for (TaskRecord record : records) {
            if (record.getId() == id) {
                return record;
            }
        }
        return null;
    }
    public void clearAllTaskRecords() {
        saveTaskRecords(new ArrayList<>());
        markTaskIdle();
    }
    // 删除过期记录
    private void removeExpiredRecords() {
        int retentionDays = taskRecordRetentionDays.getValue() != null ?
                taskRecordRetentionDays.getValue() : 30;

        long cutoffTime = System.currentTimeMillis() - retentionDays * 24 * 60 * 60 * 1000L;

        List<TaskRecord> currentRecords = taskRecords.getValue();
        if (currentRecords == null) return;

        List<TaskRecord> validRecords = new ArrayList<>();
        for (TaskRecord record : currentRecords) {
            if (record.getCreateTime() >= cutoffTime) {
                validRecords.add(record);
            }
        }

        if (validRecords.size() < currentRecords.size()) {
            saveTaskRecords(validRecords);
        }
    }

    public void setTaskRecordRetentionDays(int days) {
        taskRecordRetentionDays.setValue(days);
        dataStoreManager.setTaskRecordRetentionDays(days);
    }

    // 更新任务状态
    public void updateTaskStatus(long id, String status) {
        TaskRecord record = findTaskRecord(id);
        if (record != null) {
            record.setStatus(status);
            if (status.equals(TaskRecord.STATUS_COMPLETED)){
                record.setEndTime(System.currentTimeMillis());
            }
            saveTaskRecords(taskRecords.getValue());
            if (TaskRecord.STATUS_COMPLETED.equals(status)) {
                markTaskCompleted();
            } else if (isTerminalTaskStatus(status)) {
                markTaskIdle();
            } else {
                markTaskExecuting(record);
            }
        }
    }

    // 在任务结束时添加记录
    public void onTaskCompleted(int taskId, boolean success) {
        TaskRecord record = findTaskRecord(taskId);
        if (record != null) {
            record.setStatus(success ? TaskRecord.STATUS_COMPLETED : TaskRecord.STATUS_FAILED);
            record.setEndTime(System.currentTimeMillis());
            saveTaskRecords(taskRecords.getValue());
            if (success) {
                markTaskCompleted();
            } else {
                markTaskIdle();
            }
        }
    }

    private boolean isTerminalTaskStatus(String status) {
        return TaskRecord.STATUS_COMPLETED.equals(status)
                || TaskRecord.STATUS_CANCELLED.equals(status)
                || TaskRecord.STATUS_FAILED.equals(status);
    }

    /**
     * Sync persisted history entries still marked "运行中" to "取消" when no task is active.
     */
    public synchronized void reconcileStaleRunningTaskRecords() {
        reconcileStaleRunningTaskRecords(false);
    }

    public synchronized void reconcileStaleRunningTaskRecords(boolean force) {
        if (!force && TASK_STATUS_EXECUTING.equals(getCurrentTaskStatusValue())) {
            return;
        }

        List<TaskRecord> currentRecords = taskRecords.getValue();
        if (currentRecords == null || currentRecords.isEmpty()) {
            return;
        }

        boolean changed = false;
        List<TaskRecord> updatedRecords = new ArrayList<>();
        for (TaskRecord record : currentRecords) {
            if (TaskRecord.STATUS_RUNNING.equals(record.getStatus())) {
                record.finish(TaskRecord.STATUS_CANCELLED);
                changed = true;
                Log.d("TaskViewModel", "Reconciled stale running record to cancelled: id="
                        + record.getId() + ", name=" + record.getTaskName());
            }
            updatedRecords.add(record);
        }

        if (changed) {
            saveTaskRecords(updatedRecords);
        }
    }
}
