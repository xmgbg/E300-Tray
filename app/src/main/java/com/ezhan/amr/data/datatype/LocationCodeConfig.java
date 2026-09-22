package com.ezhan.amr.data.datatype;

import java.io.Serializable;

public class LocationCodeConfig implements Serializable {
    private int positionId;
    private int departmentId;
    private String departmentCode;
    private int stationNo;

    public LocationCodeConfig() {
    }

    public LocationCodeConfig(int positionId, int departmentId, int stationNo) {
        this.positionId = positionId;
        this.departmentId = departmentId;
        this.stationNo = stationNo;
    }

    public LocationCodeConfig(int positionId, int departmentId, String departmentCode, int stationNo) {
        this.positionId = positionId;
        this.departmentId = departmentId;
        this.departmentCode = departmentCode;
        this.stationNo = stationNo;
    }

    public int getPositionId() {
        return positionId;
    }

    public void setPositionId(int positionId) {
        this.positionId = positionId;
    }

    public int getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(int departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public int getStationNo() {
        return stationNo;
    }

    public void setStationNo(int stationNo) {
        this.stationNo = stationNo;
    }
}
