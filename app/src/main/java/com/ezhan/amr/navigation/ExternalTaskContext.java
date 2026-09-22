package com.ezhan.amr.navigation;

import java.io.Serializable;

public class ExternalTaskContext implements Serializable {
    private final String taskType;
    private final String taskId;
    private final String taskName;
    private final String source;

    public ExternalTaskContext(String taskType, String taskId, String taskName, String source) {
        this.taskType = normalize(taskType);
        this.taskId = normalize(taskId);
        this.taskName = normalize(taskName);
        this.source = normalize(source);
    }

    public String getTaskType() {
        return taskType;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getSource() {
        return source;
    }

    public boolean hasCompleteTaskInfo() {
        return isValid(taskType) && isValid(taskId) && isValid(taskName);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isValid(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
