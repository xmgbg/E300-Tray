package com.ezhan.amr.data.datatype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JackTask implements Serializable {
    private final int taskId;
    private String taskName;
    private int pointCount;
    private int loopCount;
    private List<JackOperation> operations;

    public JackTask(int taskId, String taskName, int pointCount, int loopCount) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.pointCount = pointCount;
        this.loopCount = loopCount;
        this.operations = new ArrayList<>();
    }


    // region Getters
    public String getTaskName() {
        return taskName;
    }

    public int getPointCount() {
        return pointCount;
    }

    public int getLoopCount() {
        return loopCount;
    }

    /**
     * 返回操作列表的不可修改视图
     */
    public List<JackOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }
    // endregion

    // region Setters
    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public void setPointCount(int pointCount) {
        if (pointCount < 0) {
            throw new IllegalArgumentException("点位数量不能为负");
        }
        this.pointCount = pointCount;
    }

    public void setLoopCount(int loopCount) {
        if (loopCount != -1 && loopCount < 1) {
            throw new IllegalArgumentException("循环次数至少为1");
        }
        this.loopCount = loopCount;
    }

    /**
     * 替换整个操作集合（深拷贝）
     */
    public void setOperations(List<JackOperation> operations) {
        this.operations = new ArrayList<>(operations);
    }
    // endregion

    // region 集合操作增强
    /**
     * 添加单个操作到列表末尾
     */
    public void addOperation(JackOperation operation) {
        operations.add(operation);
    }

    /**
     * 批量添加操作
     */
    public void addAllOperations(List<JackOperation> operations) {
        this.operations.addAll(operations);
    }

    /**
     * 清空操作列表
     */
    public void clearOperations() {
        operations.clear();
    }

    /**
     * 获取操作数量
     */
    public int operationCount() {
        return operations.size();
    }

    public int getTaskId() {
        return taskId;
    }
}
