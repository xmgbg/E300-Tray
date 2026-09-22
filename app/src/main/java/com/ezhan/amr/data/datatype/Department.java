package com.ezhan.amr.data.datatype;

import java.io.Serializable;

/**
 * 科室/部门数据模型
 * 分类等级：栋 → 楼层 → 部门 → 科室
 */
public class Department implements Serializable {
    private int id; // 序号（从1开始，连续）
    private String code; // 科室编码（长期稳定，需唯一）
    private String building; // 栋
    private String floor; // 楼层
    private String departmentName; // 部门（如：门诊部、住院部、药房等）
    private String roomName; // 科室（如：内科、外科、儿科等）

    public Department() {
    }

    public Department(int id, String building, String floor,
                      String departmentName, String roomName) {
        this.id = id;
        this.building = building;
        this.floor = floor;
        this.departmentName = departmentName;
        this.roomName = roomName;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getBuilding() {
        return building;
    }

    public void setBuilding(String building) {
        this.building = building;
    }

    public String getFloor() {
        return floor;
    }

    public void setFloor(String floor) {
        this.floor = floor;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    /**
     * 获取完整路径（栋 → 楼层 → 部门 → 科室）
     */
    public String getFullPath() {
        return building + " → " + floor + " → " + departmentName + " → " + roomName;
    }

    @Override
    public String toString() {
        return "Department{" +
                "id=" + id +
                ", code='" + code + '\'' +
                ", building='" + building + '\'' +
                ", floor='" + floor + '\'' +
                ", departmentName='" + departmentName + '\'' +
                ", roomName='" + roomName + '\'' +
                '}';
    }
}
