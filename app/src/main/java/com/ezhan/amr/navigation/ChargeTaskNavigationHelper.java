package com.ezhan.amr.navigation;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;

import java.util.List;

/**
 * Filters charge-task navigation when the robot is already at the charge point.
 */
public final class ChargeTaskNavigationHelper {

    private static final double CHARGE_POINT_DISTANCE_THRESHOLD = 0.3;

    private ChargeTaskNavigationHelper() {
    }

    /**
     * Returns true when the robot is actively charging.
     * Requires both chargeStatus.state (100/300) and actual charging current,
     * so a stale state after disconnect near the charger does not trigger a false reject.
     */
    public static boolean isRobotCharging(AgvStatusResponse status) {
        if (status == null || status.data == null || status.data.chargeStatus == null) {
            return false;
        }

        AgvStatusResponse.ChargeStatus chargeStatus = status.data.chargeStatus;
        if (chargeStatus.state == -1) {
            return false;
        }

        int state = chargeStatus.state;
        if (state != 100 && state != 300) {
            return false;
        }

        return status.data.electricCurrentIn > 3;
    }

    public static AgvStatusResponse getLatestRobotStatus() {
        SharedViewModel sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        if (sharedViewModel == null) {
            return null;
        }

        AgvStatusResponse status = sharedViewModel.getLastStatusResponse();
        if (status != null && status.data != null) {
            return status;
        }

        GeneralNavigationHandler navigationHandler = sharedViewModel.getNavigationHandler();
        if (navigationHandler != null) {
            AgvStatusResponse handlerStatus = navigationHandler.getLatestStatus();
            if (handlerStatus != null && handlerStatus.data != null) {
                return handlerStatus;
            }
        }

        CommandWebSocketClient commandClient = sharedViewModel.getCommandClient();
        if (commandClient != null) {
            AgvStatusResponse cachedStatus = commandClient.getCachedStatus();
            if (cachedStatus != null && cachedStatus.data != null) {
                return cachedStatus;
            }
        }

        return null;
    }

    public static boolean shouldRejectChargeTaskDispatch() {
        return isRobotCharging(getLatestRobotStatus());
    }

    /**
     * Returns true when a charge task should start in place instead of inserting
     * a precharge waypoint that would drive the robot away from the charger first.
     */
    public static boolean shouldSkipPrechargeInsertion(List<Position> route,
                                                       Position currentPosition,
                                                       MapViewModel mapViewModel,
                                                       boolean autoChargeTask) {
        if (route == null || route.isEmpty() || currentPosition == null || mapViewModel == null) {
            return false;
        }
        if (!autoChargeTask && !isChargeDestinationRoute(route)) {
            return false;
        }

        Position chargeTarget = findChargeTarget(route);
        if (chargeTarget == null) {
            return false;
        }

        int currentFloor = mapViewModel.getCurrentFloor();
        if (!isOnSameFloor(currentFloor, chargeTarget)) {
            return false;
        }

        return isAtChargeTarget(currentPosition, chargeTarget, mapViewModel);
    }

    public static boolean isChargeDestinationRoute(List<Position> route) {
        return findChargeTarget(route) != null;
    }

    private static Position findChargeTarget(List<Position> route) {
        for (int i = route.size() - 1; i >= 0; i--) {
            Position position = route.get(i);
            if (position == null) {
                continue;
            }
            if (position.getType() == 10 || position.getTaskType() == 4) {
                return position;
            }
        }
        return null;
    }

    private static boolean isOnSameFloor(int currentFloor, Position chargeTarget) {
        if (chargeTarget.getFloor() == null) {
            return false;
        }
        try {
            return currentFloor == Integer.parseInt(chargeTarget.getFloor());
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isAtChargeTarget(Position currentPosition,
                                              Position chargeTarget,
                                              MapViewModel mapViewModel) {
        if (mapViewModel.checkIfAtChargePosition(currentPosition)) {
            return true;
        }
        return distanceBetween(currentPosition, chargeTarget) < CHARGE_POINT_DISTANCE_THRESHOLD;
    }

    private static double distanceBetween(Position first, Position second) {
        double dx = first.getPosX() - second.getPosX();
        double dy = first.getPosY() - second.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
