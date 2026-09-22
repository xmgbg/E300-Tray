package com.ezhan.amr.navigation.task;

import com.ezhan.amr.data.datatype.Position;
import java.util.Map;

/**
 * Independent elevator manager interface with its own lifecycle
 * Manages elevator occupancy status queries
 */
public interface AbstractElevatorManager {

    /** Initialize elevator manager */
    void initElevatorManager();

    /** Query elevator occupied status for all elevators */
    void queryAllElevatorsOccupiedStatus();

    /** Trigger an immediate query of elevator statuses (on-demand) */
    void triggerImmediateQuery();

    /** Get occupied status for a specific elevator */
    boolean isElevatorOccupied(String elevatorConfigId);

    /** Get occupied status for all elevators */
    Map<String, Boolean> getAllElevatorOccupiedStatus();

    /** Get the elevator ID that a position belongs to */
    String getElevatorIdForPosition(Position position);

    /** Check if an elevator is available for use */
    boolean isElevatorAvailable(String elevatorConfigId);

    /** Get elevator availability for a specific building and floor */
    Map<String, Boolean> getElevatorAvailabilityForFloor(String buildingId, int floor);

    /** Find an available elevator for a specific floor */
    String findAvailableElevator(String buildingId, int floor);

    /**
     * Check which elevator the robot is currently using from cached data.
     * @return The elevator identifier string in format: "mapPrefix_buildingId_elevatorAddress_elevatorChannel",
     *         or null if the robot is not using any elevator
     */
    String getRobotCurrentElevator();

    /** Get detailed state for debugging */
    String getDetailedStateInfo();

    /** Check if elevator manager is running */
    boolean isSchedulerRunning();

    /** Stop the elevator manager scheduler */
    void stopElevatorScheduler(String reason);
}
