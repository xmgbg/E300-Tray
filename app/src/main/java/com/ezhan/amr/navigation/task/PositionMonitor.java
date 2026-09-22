package com.ezhan.amr.navigation.task;

import com.ezhan.amr.data.datatype.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// PositionMonitor.java - Similar to TaskOrderMonitor
public class PositionMonitor {
    private static final Map<Long, PositionMonitor> positionMonitorMap = new ConcurrentHashMap<>();
    private static AtomicLong nextPositionId = new AtomicLong(1);

    private long positionId;                    // Unique ID for this position
    private long routeId;                        // Parent route ID
    private int sequenceIndex;                    // Order in route
    private Position position;                     // The actual position data
    private NavigationOrderStatus positionStatus;  // Status of this position
    private String startOperation;                  // Operation at start
    private String finishOperation;                 // Operation at finish
    private List<Long> commandIds;                  // IDs of commands for this position
    private long startTime = 0L;
    private long completeTime = 0L;
    private boolean waitingForConfirmation = false;

    public static PositionMonitor createPosition(Position position, long routeId, int index) {
        PositionMonitor monitor = new PositionMonitor();
        monitor.positionId = nextPositionId.getAndIncrement();
        monitor.routeId = routeId;
        monitor.sequenceIndex = index;
        monitor.position = position;
        monitor.positionStatus = NavigationOrderStatus.ORDER_RAW;
        monitor.commandIds = new ArrayList<>();
        positionMonitorMap.put(monitor.positionId, monitor);
        return monitor;
    }

    public static PositionMonitor createRecoveryPosition(long routeId, int index) {
        PositionMonitor monitor = new PositionMonitor();
        monitor.positionId = nextPositionId.getAndIncrement();
        monitor.routeId = routeId;
        monitor.sequenceIndex = index;
        monitor.position = null;  // Recovery positions don't need a target position
        monitor.positionStatus = NavigationOrderStatus.ORDER_RAW;
        monitor.commandIds = new ArrayList<>();
        positionMonitorMap.put(monitor.positionId, monitor);
        return monitor;
    }

    public static Map<Long, PositionMonitor> getAllPositions() {
        return Collections.unmodifiableMap(positionMonitorMap);
    }

    public static void clearAllPositions() {
        positionMonitorMap.clear();
        nextPositionId = new AtomicLong(1);
    }

    public static PositionMonitor getPosition(long positionId) {
        return positionMonitorMap.get(positionId);
    }

    public static void updatePosition(PositionMonitor monitor) {
        positionMonitorMap.put(monitor.getPositionId(), monitor);
    }

    public NavigationOrderStatus getPositionStatus() {
        return positionStatus;
    }

    public void setPositionStatus(NavigationOrderStatus positionStatus) {
        this.positionStatus = positionStatus;
    }

    public int getSequenceIndex() {
        return sequenceIndex;
    }

    public List<Long> getCommandIds() {
        return commandIds;
    }

    public long getPositionId() {
        return positionId;
    }

    public long getRouteId() {
        return routeId;
    }

    public Position getPosition() {
        return position;
    }

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }
    public boolean isWaitingForConfirmation() {
        return waitingForConfirmation;
    }

    public void setWaitingForConfirmation(boolean waiting) {
        this.waitingForConfirmation = waiting;
    }
}
