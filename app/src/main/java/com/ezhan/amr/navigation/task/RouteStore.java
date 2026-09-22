package com.ezhan.amr.navigation.task;

import com.ezhan.amr.data.datatype.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// RouteStore.java - Similar to TaskGroupStore
public class RouteStore {
    private static final Map<Long, RouteStore> routeStoreMap = new ConcurrentHashMap<>();
    private static AtomicLong nextRouteId = new AtomicLong(1);

    private long routeId;                    // Unique ID for the route
    private String routeName;                 // Name/description
    private long robotId;                      // Robot ID
    private NavigationOrderStatus routeStatus; // Status of entire route
    private long createTime;
    private long startTime = 0L;
    private long completeTime = 0L;
    private List<Long> positionIds;            // IDs of positions in this route
    private Position currentTarget;             // Current target position
    private int currentPositionIndex;           // Current index in route
    private boolean waitingForConfirmation = false;

    // ========== Static Methods ==========

    public static RouteStore createRoute(String name, long robotId) {
        RouteStore route = new RouteStore();
        route.routeId = nextRouteId.getAndIncrement();
        route.routeName = name;
        route.robotId = robotId;
        route.routeStatus = NavigationOrderStatus.ORDER_RAW;
        route.createTime = System.currentTimeMillis();
        route.positionIds = new ArrayList<>();
        routeStoreMap.put(route.routeId, route);
        return route;
    }

    /**
     * Create a route with a specific ID (useful for recovery routes)
     */
    public static RouteStore createRouteWithId(long routeId, String name, long robotId) {
        RouteStore route = new RouteStore();
        route.routeId = routeId;
        route.routeName = name;
        route.robotId = robotId;
        route.routeStatus = NavigationOrderStatus.ORDER_RAW;
        route.createTime = System.currentTimeMillis();
        route.positionIds = new ArrayList<>();
        routeStoreMap.put(route.routeId, route);
        return route;
    }

    /**
     * Add a route to the store (updates if exists, adds if new)
     */
    public static void addRoute(RouteStore route) {
        if (route != null) {
            routeStoreMap.put(route.getRouteId(), route);
        }
    }

    public static RouteStore getRoute(long routeId) {
        return routeStoreMap.get(routeId);
    }

    public static Map<Long, RouteStore> getAllRoutes() {
        return Collections.unmodifiableMap(routeStoreMap);
    }

    public static void clearAllRoutes() {
        routeStoreMap.clear();
        nextRouteId = new AtomicLong(1);
    }

    public static void updateRoute(RouteStore route) {
        if (route != null) {
            routeStoreMap.put(route.getRouteId(), route);
        }
    }

    /**
     * Delete a route (cleanup)
     */
    public static void deleteRoute(long routeId) {
        routeStoreMap.remove(routeId);
    }

    /**
     * Get the next available route ID without creating a route
     */
    public static long getNextRouteId() {
        return nextRouteId.getAndIncrement();
    }

    /**
     * Set the next route ID (useful for testing or recovery)
     */
    public static void setNextRouteId(long nextId) {
        nextRouteId = new AtomicLong(nextId);
    }

    /**
     * Check if a route exists
     */
    public static boolean routeExists(long routeId) {
        return routeStoreMap.containsKey(routeId);
    }

    /**
     * Get all routes with a specific status
     */
    public static List<RouteStore> getRoutesByStatus(NavigationOrderStatus status) {
        List<RouteStore> result = new ArrayList<>();
        for (RouteStore route : routeStoreMap.values()) {
            if (route.getRouteStatus() == status) {
                result.add(route);
            }
        }
        return result;
    }

    /**
     * Get all routes for a specific robot
     */
    public static List<RouteStore> getRoutesByRobotId(long robotId) {
        List<RouteStore> result = new ArrayList<>();
        for (RouteStore route : routeStoreMap.values()) {
            if (route.getRobotId() == robotId) {
                result.add(route);
            }
        }
        return result;
    }

    // ========== Instance Methods ==========

    public void addPositionId(long positionId) {
        if (positionIds == null) {
            positionIds = new ArrayList<>();
        }
        positionIds.add(positionId);
    }

    /**
     * Remove a position ID from the route
     */
    public void removePositionId(long positionId) {
        if (positionIds != null) {
            positionIds.remove(positionId);
        }
    }

    /**
     * Get the current position (the one being executed)
     */
    public PositionMonitor getCurrentPosition() {
        if (currentPositionIndex >= 0 && currentPositionIndex < positionIds.size()) {
            long positionId = positionIds.get(currentPositionIndex);
            return PositionMonitor.getPosition(positionId);
        }
        return null;
    }

    /**
     * Get the next position after the current one
     */
    public PositionMonitor getNextPosition() {
        int nextIndex = currentPositionIndex + 1;
        if (nextIndex >= 0 && nextIndex < positionIds.size()) {
            long positionId = positionIds.get(nextIndex);
            return PositionMonitor.getPosition(positionId);
        }
        return null;
    }

    /**
     * Check if this is the last position in the route
     */
    public boolean isLastPosition() {
        return currentPositionIndex == positionIds.size() - 1;
    }

    /**
     * Get the number of completed positions
     */
    public int getCompletedPositionCount() {
        int completed = 0;
        for (Long posId : positionIds) {
            PositionMonitor pos = PositionMonitor.getPosition(posId);
            if (pos != null && pos.getPositionStatus() == NavigationOrderStatus.ORDER_COMPLETED) {
                completed++;
            }
        }
        return completed;
    }

    /**
     * Check if all positions in the route are completed
     */
    public boolean areAllPositionsCompleted() {
        for (Long posId : positionIds) {
            PositionMonitor pos = PositionMonitor.getPosition(posId);
            if (pos == null || pos.getPositionStatus() != NavigationOrderStatus.ORDER_COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the status of the route as a string (for debugging)
     */
    public String getStatusString() {
        return String.format("Route[%d]: status=%s, positions=%d, currentIndex=%d, waiting=%s",
                routeId, routeStatus, positionIds != null ? positionIds.size() : 0,
                currentPositionIndex, waitingForConfirmation);
    }

//    /**
//     * Reset the route to its initial state (for retry)
//     */
//    public void reset() {
//        currentPositionIndex = 0;
//        waitingForConfirmation = false;
//        startTime = 0;
//        completeTime = 0;
//        routeStatus = NavigationOrderStatus.ORDER_RAW;
//
//        // Reset all positions
//        for (Long posId : positionIds) {
//            PositionMonitor pos = PositionMonitor.getPosition(posId);
//            if (pos != null) {
//                pos.reset();
//            }
//        }
//    }

    /**
     * Get the progress percentage of the route
     */
    public float getProgressPercentage() {
        if (positionIds == null || positionIds.isEmpty()) {
            return 0f;
        }
        return (float) getCompletedPositionCount() / positionIds.size() * 100;
    }

    // ========== Getters and Setters ==========

    public NavigationOrderStatus getRouteStatus() {
        return routeStatus;
    }

    public void setRouteStatus(NavigationOrderStatus routeStatus) {
        this.routeStatus = routeStatus;
    }

    public long getRouteId() {
        return routeId;
    }

    public void setRouteId(long routeId) {
        this.routeId = routeId;
    }

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }

    public void setCurrentPositionIndex(int currentPositionIndex) {
        this.currentPositionIndex = currentPositionIndex;
    }

    public int getCurrentPositionIndex() {
        return this.currentPositionIndex;
    }

    /**
     * Increment the current position index (move to next position)
     */
    public void incrementCurrentPositionIndex() {
        this.currentPositionIndex++;
    }

    public void setCurrentTarget(Position currentTarget) {
        this.currentTarget = currentTarget;
    }

    public Position getCurrentTarget() {
        return currentTarget;
    }

    public List<Long> getPositionIds() {
        return positionIds != null ? positionIds : new ArrayList<>();
    }

    public void setPositionIds(List<Long> positionIds) {
        this.positionIds = positionIds;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public boolean isWaitingForConfirmation() {
        return waitingForConfirmation;
    }

    public void setWaitingForConfirmation(boolean waiting) {
        this.waitingForConfirmation = waiting;
    }

    public String getRouteName() {
        return routeName;
    }

    public void setRouteName(String routeName) {
        this.routeName = routeName;
    }

    public long getRobotId() {
        return robotId;
    }

    public void setRobotId(long robotId) {
        this.robotId = robotId;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return getStatusString();
    }
}