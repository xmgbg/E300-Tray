package com.ezhan.amr.data.datatype;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;

import kotlinx.serialization.KSerializer;
import kotlinx.serialization.Serializable;

public class CruiseTask implements Serializable {

    private Integer id;
    private String name;
    private Boolean isAuto; // 模式：手动 自动
    private List<String> stationIds;
    private int stayTime;   // 停留时间（秒）
    private int loopCount;  // 循环次数
    private int taskType;   // 开启/关闭自动任务：0=关闭, 1=打开
    private int hour;
    private int minute;

    public int getTaskType() {
        return taskType;
    }

    public void setTaskType(int taskType) {
        this.taskType = taskType;
    }
    public boolean isAutoMode() {
        return taskType == 1; // 1 表示自动模式
    }

    private List<String> stationKeys; // 存储"楼层_站点ID"格式

    public List<String> getStationKeys() {
        return stationKeys;
    }

    public void setStationKeys(List<String> stationKeys) {
        this.stationKeys = stationKeys;
    }

    // 无参构造（用于序列化/反序列化）
    public CruiseTask() {
        this.isAuto = false;
        this.stayTime = 10;
        this.loopCount = 1;
        this.taskType = 0;
        this.hour = 0;
        this.minute = 0;
    }

    // 全参数构造（包含所有基础字段）
    public CruiseTask(Integer id, String name, List<String> stationIds, Boolean isAuto,
                      int stayTime, int loopCount) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.stationIds = Objects.requireNonNull(stationIds, "Stations cannot be null");
        this.isAuto = isAuto != null ? isAuto : false;
        this.stayTime = stayTime;
        this.loopCount = loopCount;
        this.taskType = 0;
        this.hour = 0;
        this.minute = 0;
    }

    // 兼容旧版本构造（自动转换参数类型）
    public CruiseTask(Integer id, String name, List<String> stations, Boolean isAuto,
                      Integer stayTime, Integer loopCount) {
        this(id, name, stations, isAuto,
                stayTime != null ? stayTime : 10,
                loopCount != null ? loopCount : 1);
    }

    // 带任务类型和时间设置的构造函数
    public CruiseTask(Integer id, String name, List<String> stationIds, Boolean isAuto,
                      int stayTime, int loopCount, int taskType) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.stationIds = Objects.requireNonNull(stationIds, "Stations cannot be null");
        this.isAuto = isAuto != null ? isAuto : false;
        this.stayTime = stayTime;
        this.loopCount = loopCount;
        this.taskType = taskType;
        this.hour = 0;
        this.minute = 0;
    }

    // 包含所有字段的完整构造函数
    public CruiseTask(Integer id, String name, List<String> stationIds, Boolean isAuto,
                      int stayTime, int loopCount, int taskType,
                      int hour, int minute) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.stationIds = Objects.requireNonNull(stationIds, "Stations cannot be null");
        this.isAuto = isAuto != null ? isAuto : false;
        this.stayTime = stayTime;
        this.loopCount = loopCount;
        this.taskType = taskType;
        this.hour = hour;
        this.minute = minute;
    }

    // Getters and Setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getIsAuto() {
        return isAuto;
    }

    public void setIsAuto(Boolean isAuto) {
        this.isAuto = isAuto;
    }

    // ===添加 isAuto() 方法 ===
    public boolean isAuto() {
        return isAuto != null && isAuto;
    }

    public List<String> getStationIds() {
        return stationIds;
    }

    public void setStationIds(List<String> stationIds) {
        this.stationIds = stationIds;
    }

    public int getStayTime() {
        return stayTime;
    }

    public void setStayTime(int stayTime) {
        this.stayTime = stayTime;
    }

    public int getLoopCount() {
        return loopCount;
    }

    public void setLoopCount(int loopCount) {
        this.loopCount = loopCount;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    // 辅助方法
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CruiseTask that = (CruiseTask) o;
        return stayTime == that.stayTime &&
                loopCount == that.loopCount &&
                taskType == that.taskType &&
                hour == that.hour &&
                minute == that.minute &&
                Objects.equals(id, that.id) &&
                Objects.equals(name, that.name) &&
                Objects.equals(isAuto, that.isAuto) &&
                Objects.equals(stationIds, that.stationIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, isAuto, stationIds, stayTime, loopCount,
                taskType, hour, minute);
    }

    @Override
    public String toString() {
        return "CruiseTask{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", isAuto=" + isAuto +
                ", stationIds=" + stationIds +
                ", stayTime=" + stayTime +
                ", loopCount=" + loopCount +
                ", taskType=" + taskType +
                ", hour=" + hour +
                ", minute=" + minute +
                '}';
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return null;
    }

    @Override
    public Class<? extends KSerializer<?>> with() {
        return null;
    }
}