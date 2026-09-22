package com.ezhan.amr.data.datatype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Position implements Serializable {

    private int id;          // 唯一标识符
    private String name;     // 名称字段
    private double posX;
    private double posY;
    private double yaw;      // 航向角，单位通常为度或弧度
    private int type;       // 0/1 普通点；
    // 2: 乘梯前置点， 3： 候梯点； 4： 乘梯点； 5： 取货点； 6： 放货点； 7： 后退点；  8：顶升点；   9：下降点；
    // 10: 充电点； 11：充电前置点； 12： 驻车点； 13: 重定位点, 14: 切换点， 15：药房呼叫， 16：静配呼叫 17：固定点前进
    // 18: 固定点倒退
    private String floor;
    private String mapName;
    private String message;
    private int taskType;   // 0: 非任务途径点（乘梯/侯梯点）， 1： 背负任务， 2： 巡航任务， 3： 顶升任务，
    // 4: 充电任务， 5： 驻车任务,  6: 离开充电桩 (充电前置)， 7: 呼叫任务
    private String taskId;
    private boolean isDefaultPoint = false;

    // New fields for elevator Lora communication
    private ElevatorInfo elevatorInfo;      // Elevator communication parameters

    // New fields for auto door Lora communication
    private AutoDoorInfo autoDoorInfo;      // Auto door communication parameters
    private double speed;
    private boolean isVirtualOrbit = false;
    private double recognizeDistance = 1.0;
    private List<Integer> additionalDoorIds = new ArrayList<>();
    private boolean hasMultipleDoors = false;
    private int doorId;
    private String buildingId;
    private int virtualOrbitObstacleTime = 0;
    private int stayDuration = 0;

    // 无参构造
    public Position() {
    }

    // 全参构造
    public Position(int id, String name, double posX, double posY, double yaw) {
        this.id = id;
        this.name = name;
        this.posX = posX;
        this.posY = posY;
        this.yaw = yaw;
    }

    // Inner class for Elevator information
    public static class ElevatorInfo implements Serializable {
        private int loraChannel;        // Lora channel for elevator communication
        private int loraAddress;        // Lora address for elevator
        private String elevatorId;      // Elevator identifier
        private int floorNumber;        // Floor number associated with this position
        private int doorOpenTimeout;    // Door open timeout in seconds
        private int callTimeout;        // Elevator call timeout in seconds

        // Constructors
        public ElevatorInfo() {}

        public ElevatorInfo(int loraChannel, int loraAddress, String elevatorId) {
            this.loraChannel = loraChannel;
            this.loraAddress = loraAddress;
            this.elevatorId = elevatorId;
            this.floorNumber = 1;
            this.doorOpenTimeout = 30;
            this.callTimeout = 60;
        }

        // Getters and setters
        public int getLoraChannel() { return loraChannel; }
        public void setLoraChannel(int loraChannel) { this.loraChannel = loraChannel; }

        public int getLoraAddress() { return loraAddress; }
        public void setLoraAddress(int loraAddress) { this.loraAddress = loraAddress; }

        public String getElevatorId() { return elevatorId; }
        public void setElevatorId(String elevatorId) { this.elevatorId = elevatorId; }

        public int getFloorNumber() { return floorNumber; }
        public void setFloorNumber(int floorNumber) { this.floorNumber = floorNumber; }

        public int getDoorOpenTimeout() { return doorOpenTimeout; }
        public void setDoorOpenTimeout(int doorOpenTimeout) { this.doorOpenTimeout = doorOpenTimeout; }

        public int getCallTimeout() { return callTimeout; }
        public void setCallTimeout(int callTimeout) { this.callTimeout = callTimeout; }

        @Override
        public String toString() {
            return "ElevatorInfo{" +
                    "loraChannel=" + loraChannel +
                    ", loraAddress=" + loraAddress +
                    ", elevatorId='" + elevatorId + '\'' +
                    ", floorNumber=" + floorNumber +
                    '}';
        }
    }

    // Inner class for Auto Door information
    public static class AutoDoorInfo implements Serializable {
        private int loraChannel;        // Lora channel for door communication
        private int loraAddress;        // Lora address for door controller
        private String doorId;          // Door identifier
        private int openDuration;       // Door open duration in seconds
        private boolean isSlidingDoor;  // true for sliding door, false for swing door
        private int doorType;           // 1: automatic, 2: manual, 3: sensor

        // Constructors
        public AutoDoorInfo() {}

        public AutoDoorInfo(int loraChannel, int loraAddress, String doorId) {
            this.loraChannel = loraChannel;
            this.loraAddress = loraAddress;
            this.doorId = doorId;
            this.openDuration = 10;
            this.isSlidingDoor = true;
            this.doorType = 1;
        }

        // Getters and setters
        public int getLoraChannel() { return loraChannel; }
        public void setLoraChannel(int loraChannel) { this.loraChannel = loraChannel; }

        public int getLoraAddress() { return loraAddress; }
        public void setLoraAddress(int loraAddress) { this.loraAddress = loraAddress; }

        public String getDoorId() { return doorId; }
        public void setDoorId(String doorId) { this.doorId = doorId; }

        public int getOpenDuration() { return openDuration; }
        public void setOpenDuration(int openDuration) { this.openDuration = openDuration; }

        public boolean isSlidingDoor() { return isSlidingDoor; }
        public void setSlidingDoor(boolean slidingDoor) { isSlidingDoor = slidingDoor; }

        public int getDoorType() { return doorType; }
        public void setDoorType(int doorType) { this.doorType = doorType; }

        @Override
        public String toString() {
            return "AutoDoorInfo{" +
                    "loraChannel=" + loraChannel +
                    ", loraAddress=" + loraAddress +
                    ", doorId='" + doorId + '\'' +
                    ", doorType=" + doorType +
                    '}';
        }
    }

    public String getBuildingId() {
        return buildingId;
    }

    public void setBuildingId(String buildingId) {
        this.buildingId = buildingId;
    }

    // Getters and setters for existing fields
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getPosX() {
        return posX;
    }

    public void setPosX(double posX) {
        this.posX = posX;
    }

    public double getPosY() {
        return posY;
    }

    public void setPosY(double posY) {
        this.posY = posY;
    }

    public double getYaw() {
        return yaw;
    }

    public void setYaw(double yaw) {
        this.yaw = yaw;
    }

    private int specialType = -1; // -1表示普通位置

    public boolean isSpecial() {
        return specialType != -1;
    }

    public int getSpecialType() {
        return specialType;
    }

    public void setSpecialType(int specialType) {
        this.specialType = specialType;
    }

    public void setFloor(String floor) {
        this.floor = floor;
    }

    public String getFloor() {
        return floor;
    }

    public String getMapName() {
        return mapName;
    }
    public void setMapName(String mapName) {
        this.mapName = mapName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getTaskType() {
        return taskType;
    }

    public void setTaskType(int taskType) {
        this.taskType = taskType;
    }

    public boolean isDefaultPoint() {
        return isDefaultPoint;
    }

    public void setDefaultPoint(boolean defaultPoint) {
        isDefaultPoint = defaultPoint;
    }

    // Getters and setters for new fields
    public ElevatorInfo getElevatorInfo() {
        return elevatorInfo;
    }

    public void setElevatorInfo(ElevatorInfo elevatorInfo) {
        this.elevatorInfo = elevatorInfo;
    }

    public AutoDoorInfo getAutoDoorInfo() {
        return autoDoorInfo;
    }

    public void setAutoDoorInfo(AutoDoorInfo autoDoorInfo) {
        this.autoDoorInfo = autoDoorInfo;
    }

    // Helper methods to check if position has elevator/door capabilities
    public boolean hasElevator() {
        return elevatorInfo != null;
    }

    public boolean hasAutoDoor() {
        return autoDoorInfo != null;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public boolean isVirtualOrbit() {
        return isVirtualOrbit;
    }

    public void setVirtualOrbit(boolean virtualOrbit) {
        isVirtualOrbit = virtualOrbit;
    }

    public double getRecognizeDistance() {
        return recognizeDistance;
    }

    public void setRecognizeDistance(double recognizeDistance) {
        this.recognizeDistance = recognizeDistance;
    }

    public List<Integer> getAdditionalDoorIds() {
        return additionalDoorIds;
    }

    public void setAdditionalDoorIds(List<Integer> additionalDoorIds) {
        this.additionalDoorIds = additionalDoorIds;
    }

    public void addAdditionalDoorId(int doorId) {
        if (this.additionalDoorIds == null) {
            this.additionalDoorIds = new ArrayList<>();
        }
        this.additionalDoorIds.add(doorId);
        this.hasMultipleDoors = true;
    }

    public boolean hasMultipleDoors() {
        return hasMultipleDoors || (additionalDoorIds != null && !additionalDoorIds.isEmpty());
    }

    public List<Integer> getAllDoorIds() {
        List<Integer> allDoorIds = new ArrayList<>();
        if (doorId > 0) {
            allDoorIds.add(doorId);
        }
        if (additionalDoorIds != null) {
            allDoorIds.addAll(additionalDoorIds);
        }
        return allDoorIds;
    }

    public int getDoorId() {
        return doorId;
    }

    public void setDoorId(int doorId) {
        this.doorId = doorId;
    }

    public int getVirtualOrbitObstacleTime() {
        return virtualOrbitObstacleTime;
    }

    public void setVirtualOrbitObstacleTime(int virtualOrbitObstacleTime) {
        this.virtualOrbitObstacleTime = virtualOrbitObstacleTime;
    }

    public int getStayDuration() {
        return stayDuration;
    }

    public void setStayDuration(int stayDuration) {
        this.stayDuration = stayDuration;
    }

    // 更新 toString()
    @Override
    public String toString() {
        return "Position{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", posX=" + posX +
                ", posY=" + posY +
                ", yaw=" + yaw +
                ", hasElevator=" + (elevatorInfo != null) +
                ", hasAutoDoor=" + (autoDoorInfo != null) +
                '}';
    }
}