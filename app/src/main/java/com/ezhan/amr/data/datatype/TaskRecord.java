package com.ezhan.amr.data.datatype;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TaskRecord implements Serializable {
    public static final int TYPE_CRUISE = 2;
    public static final int TYPE_JACK = 3;
    public static final int TYPE_DELIVERY = 1;
    public static final int TYPE_CHARGE = 4;
    public static final int TYPE_PARKING = 5;
    public static final int TYPE_STOP_CHARGE = 6;
    public static final int TYPE_CALL = 7;

    private long id;
    private int type;
    private long createTime;
    private long startTime;
    private long endTime;
    private String status;
    @SerializedName("taskId")
    private String taskId;
    @SerializedName("taskName")
    private String taskName;
    private String creator;           // User who created/sent the task
    private String creatorUserId;     // User ID of task creator
    private List<StationDetail> stationDetails;
    private int currentLoopIndex;     // Current loop index (1-based)

    public static final String STATUS_COMPLETED = "完成";
    public static final String STATUS_FAILED = "失败";
    public static final String STATUS_CANCELLED = "取消";
    public static final String STATUS_RUNNING = "运行中";

    public TaskRecord() {
        this.taskName = "";
        this.taskId = "";
        this.status = STATUS_RUNNING;
        this.createTime = System.currentTimeMillis();
        this.creator = "";
        this.creatorUserId = "";
        this.stationDetails = new ArrayList<>();
        this.currentLoopIndex = 1;
    }

    public TaskRecord(long id, int type, String taskName) {
        this.id = id;
        this.type = type;
        this.taskName = taskName;
        this.taskId = "";
        this.createTime = System.currentTimeMillis();
        this.status = STATUS_RUNNING;
        this.creator = "";
        this.creatorUserId = "";
        this.stationDetails = new ArrayList<>();
        this.currentLoopIndex = 1;
    }

    public TaskRecord(int type, String taskName) {
        this.type = type;
        this.taskName = taskName;
        this.taskId = "";
        this.createTime = System.currentTimeMillis();
        this.status = STATUS_RUNNING;
        this.creator = "";
        this.creatorUserId = "";
        this.stationDetails = new ArrayList<>();
        this.currentLoopIndex = 1;
    }

    public void start() {
        this.startTime = System.currentTimeMillis();
    }

    public void finish(String status) {
        this.endTime = System.currentTimeMillis();
        this.status = status;
    }

    public long getDuration() {
        if (endTime > startTime) {
            return (endTime - startTime) / 1000;
        }
        return 0;
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public String getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(String creatorUserId) { this.creatorUserId = creatorUserId; }
    public List<StationDetail> getStationDetails() { return stationDetails; }
    public void setStationDetails(List<StationDetail> stationDetails) { this.stationDetails = stationDetails; }
    public int getCurrentLoopIndex() { return currentLoopIndex; }
    public void setCurrentLoopIndex(int currentLoopIndex) { this.currentLoopIndex = currentLoopIndex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskRecord that = (TaskRecord) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /// Update TaskRecord.java - StationDetail class to support multiple door IDs
    public static class StationDetail implements Serializable {
        private int id;
        private String stationName;        // 站点名称
        private int index;                 // 站点索引 (waypoint index)
        private long arrivalTime;          // 到达时间
        private long doorOpenTime;         // 开门时间
        private String openDoorIds;        // 打开的门锁ID列表 (comma-separated: "2,3,4")
        private String doorOpenerUserId;   // 开门人用户ID
        private String doorOpenerName;     // 开门人姓名
        private long doorCloseTime;        // 关门时间
        private String closeDoorIds;       // 关闭的门锁ID列表 (comma-separated)
        private String doorCloserUserId;   // 关门人用户ID (if manually closed)
        private String doorCloserName;     // 关门人姓名
        private long departureTime;        // 离开时间
        private String floor;
        private String mapName;
        private double posX;
        private double posY;
        private double yaw;
        private int openDoorId;
        private int closeDoorId;


        public StationDetail() {
        }

        public StationDetail(String stationName) {
            this.stationName = stationName;
        }

        // Getters and Setters


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

        public String getMapName() {
            return mapName;
        }

        public void setMapName(String mapName) {
            this.mapName = mapName;
        }

        public String getFloor() {
            return floor;
        }

        public void setFloor(String floor) {
            this.floor = floor;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getStationName() { return stationName; }
        public void setStationName(String stationName) { this.stationName = stationName; }

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public long getArrivalTime() { return arrivalTime; }
        public void setArrivalTime(long arrivalTime) { this.arrivalTime = arrivalTime; }

        public long getDoorOpenTime() { return doorOpenTime; }
        public void setDoorOpenTime(long doorOpenTime) { this.doorOpenTime = doorOpenTime; }

        public String getOpenDoorIds() { return openDoorIds; }
        public void setOpenDoorIds(String openDoorIds) { this.openDoorIds = openDoorIds; }

        // Convenience method for single door (backward compatibility)
        public int getOpenDoorId() {
            return this.openDoorId;
        }

        // Convenience method for single door (backward compatibility)
        public void setOpenDoorId(int openDoorId) {
            this.openDoorId = openDoorId;
        }

        public String getDoorOpenerUserId() { return doorOpenerUserId; }
        public void setDoorOpenerUserId(String doorOpenerUserId) { this.doorOpenerUserId = doorOpenerUserId; }

        public String getDoorOpenerName() { return doorOpenerName; }
        public void setDoorOpenerName(String doorOpenerName) { this.doorOpenerName = doorOpenerName; }

        public long getDoorCloseTime() { return doorCloseTime; }
        public void setDoorCloseTime(long doorCloseTime) { this.doorCloseTime = doorCloseTime; }

        public String getCloseDoorIds() { return closeDoorIds; }
        public void setCloseDoorIds(String closeDoorIds) { this.closeDoorIds = closeDoorIds; }

        // Convenience method for single door (backward compatibility)
        public int getCloseDoorId() {
            return this.closeDoorId;
        }

        // Convenience method for single door (backward compatibility)
        public void setCloseDoorId(int closeDoorId) {
            this.closeDoorId = closeDoorId;
        }

        public String getDoorCloserUserId() { return doorCloserUserId; }
        public void setDoorCloserUserId(String doorCloserUserId) { this.doorCloserUserId = doorCloserUserId; }

        public String getDoorCloserName() { return doorCloserName; }
        public void setDoorCloserName(String doorCloserName) { this.doorCloserName = doorCloserName; }

        public long getDepartureTime() { return departureTime; }
        public void setDepartureTime(long departureTime) { this.departureTime = departureTime; }

        // Helper method to get door number (lock ID - 1) for first door
        public int getDoorNumber() {
            int doorId = getOpenDoorId();
            return doorId - 1; // lock2→door1, lock3→door2, etc.
        }

        // Get all door IDs as list
        public List<Integer> getAllOpenDoorIds() {
            List<Integer> ids = new ArrayList<>();
            if (openDoorIds != null && !openDoorIds.isEmpty()) {
                for (String id : openDoorIds.split(",")) {
                    try {
                        ids.add(Integer.parseInt(id.trim()));
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }
            return ids;
        }

        // Get all close door IDs as list
        public List<Integer> getAllCloseDoorIds() {
            List<Integer> ids = new ArrayList<>();
            if (closeDoorIds != null && !closeDoorIds.isEmpty()) {
                for (String id : closeDoorIds.split(",")) {
                    try {
                        ids.add(Integer.parseInt(id.trim()));
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }
            return ids;
        }

        // Add a door ID to the open list
        public void addOpenDoorId(int doorId) {
            if (openDoorIds == null || openDoorIds.isEmpty()) {
                openDoorIds = String.valueOf(doorId);
            } else {
                openDoorIds += "," + doorId;
            }
        }

        // Add a door ID to the close list
        public void addCloseDoorId(int doorId) {
            if (closeDoorIds == null || closeDoorIds.isEmpty()) {
                closeDoorIds = String.valueOf(doorId);
            } else {
                closeDoorIds += "," + doorId;
            }
        }
    }
}
