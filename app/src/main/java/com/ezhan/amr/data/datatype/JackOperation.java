package com.ezhan.amr.data.datatype;

import java.io.Serializable;

public class JackOperation implements Serializable {
    private String pointName;
    private Integer pointId;
    private int actionType; // 举升/放置
    private int duration;
    private int floor; // 新增楼层字段

    public JackOperation(String pointName, int actionType, int duration, int floor) {
        this(pointName, null, actionType, duration, floor);
    }

    public JackOperation(String pointName, Integer pointId, int actionType, int duration, int floor) {
        this.pointName = pointName;
        this.pointId = pointId;
        this.actionType = actionType;
        this.duration = duration;
        this.floor = floor;
    }

    // region Getters
    public String getPointName() {
        return pointName;
    }

    public Integer getPointId() {
        return pointId;
    }

    public int getActionType() {
        return actionType;
    }

    public int getDuration() {
        return duration;
    }

    public int getFloor() {
        return floor;
    }
    // endregion

    // region Setters
    public void setPointName(String pointName) {
        this.pointName = pointName;
    }

    public void setPointId(Integer pointId) {
        this.pointId = pointId;
    }

    public void setActionType(int actionType) {
        this.actionType = actionType;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public void setFloor(int floor) {
        this.floor = floor;
    }
    // endregion
}
