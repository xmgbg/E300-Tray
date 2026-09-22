package com.ezhan.amr.navigation.task;

import android.util.Log;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.RobotCommunicationParams;
import com.ezhan.amr.navigation.ConfirmationStateManager;
import com.ezhan.amr.navigation.LoraCommand;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.google.gson.Gson;

import java.util.List;
import java.util.Objects;

public class NavigationRouteManager extends AbstractNavigationManager {
    private String TAG = "NavigationRouteManager";
    private static NavigationRouteManager instance;
    private final ConfirmationStateManager confirmationManager;
    private final NavigationStateDebugger stateDebugger;

    private MapViewModel mapViewModel;
    private ElevatorViewModel elevatorViewModel;
    private BasicViewModel basicViewModel;
    Gson gson = new Gson();

    private NavigationRouteManager() {
        this.confirmationManager = ConfirmationStateManager.getInstance();
        this.stateDebugger = NavigationStateDebugger.getInstance();
    }

    public static NavigationRouteManager getInstance() {
        if (instance == null) {
            instance = new NavigationRouteManager();
        }
        return instance;
    }

    @Override
    public void initManager() {
        if (stateDebugger != null) {
            stateDebugger.log("========== NavigationRouteManager.initManager START ==========");
        }

        // Initialize - reset any incomplete routes to FAILED
        int resetCount = 0;
        for (RouteStore route : RouteStore.getAllRoutes().values()) {
            NavigationOrderStatus status = route.getRouteStatus();
            if (status == NavigationOrderStatus.ORDER_PREREADY ||
                    status == NavigationOrderStatus.ORDER_READY ||
                    status == NavigationOrderStatus.ORDER_EXECUTING) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Resetting incomplete route %d from %s to FAILED",
                            route.getRouteId(), status));
                }
                route.setRouteStatus(NavigationOrderStatus.ORDER_FAILED);
                RouteStore.updateRoute(route);
                resetCount++;
            }
        }

        if (stateDebugger != null && resetCount > 0) {
            stateDebugger.log(String.format("Reset %d incomplete routes to FAILED", resetCount));
        }

        this.mapViewModel = MyApplication.getInstance().getMapViewModel();
        this.elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        this.basicViewModel = MyApplication.getInstance().getBasicViewModel();

        LOG.info("NavigationRouteManager initialized");

        if (stateDebugger != null) {
            stateDebugger.log("NavigationRouteManager initialized successfully");
            stateDebugger.log("========== NavigationRouteManager.initManager END ==========");
        }
    }

    @Override
    public void processSingleRoute(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("========== processSingleRoute START: routeId=%d", routeId));
        }

        RouteStore route = RouteStore.getRoute(routeId);
        if (route == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("processSingleRoute", String.format("Route %d not found", routeId));
            }
            return;
        }

        // Only process routes in PREREADY state
        if (route.getRouteStatus() != NavigationOrderStatus.ORDER_PREREADY) {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("Route %d status is %s, not PREREADY - skipping",
                        routeId, route.getRouteStatus()));
            }
            return;
        }

        // Get all positions for this route
        List<PositionMonitor> positions = getPositionsForRoute(routeId);
        if (positions.isEmpty()) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("processSingleRoute", String.format("Route %d has no positions", routeId));
            }
            LOG.warn("Route {} has no positions", routeId);
            return;
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Route %d has %d positions", routeId, positions.size()));
            for (int i = 0; i < positions.size(); i++) {
                PositionMonitor pos = positions.get(i);
                stateDebugger.log(String.format("  Position[%d]: %s (type=%d, taskType=%d, status=%s)",
                        i, pos.getPosition().getName(), pos.getPosition().getType(),
                        pos.getPosition().getTaskType(), pos.getPositionStatus()));
            }
        }

        // Update route with first position
        PositionMonitor firstPosition = positions.get(0);
        synchronized (route) {
            route.setCurrentTarget(firstPosition.getPosition());
            route.setCurrentPositionIndex(0);
            route.setRouteStatus(NavigationOrderStatus.ORDER_READY);
            RouteStore.updateRoute(route);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Route %d activated - first position: %s, status: READY",
                        routeId, firstPosition.getPosition().getName()));
            }
        }

        LOG.info("Route {} activated with {} positions", routeId, positions.size());

        if (stateDebugger != null) {
            stateDebugger.log(String.format("processSingleRoute END: routeId=%d activated", routeId));
        }
    }

    @Override
    public void updateSingleRouteStatus(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("========== updateSingleRouteStatus START: routeId=%d", routeId));
        }

        RouteStore route = RouteStore.getRoute(routeId);
        if (route == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("updateSingleRouteStatus", String.format("Route %d not found", routeId));
            }
            return;
        }

        // Check if all positions are completed
        List<PositionMonitor> positions = getPositionsForRoute(routeId);

        boolean anyPositionWaiting = false;
        boolean allCompleted = true;
        int completedCount = 0;
        int waitingCount = 0;

        for (PositionMonitor position : positions) {
            if (position.getPositionStatus() == NavigationOrderStatus.ORDER_COMPLETED) {
                completedCount++;
            } else {
                allCompleted = false;
            }
        }

        for (PositionMonitor position : positions) {
            if (confirmationManager.isPositionWaitingForConfirmation(position.getPositionId())) {
                anyPositionWaiting = true;
                waitingCount++;
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Route %d has position %d (name='%s') waiting for confirmation",
                            routeId, position.getPositionId(), position.getPosition().getName()));
                }
                break;
            }
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Route %d status check: completed=%d/%d, waiting=%b, routeStatus=%s",
                    routeId, completedCount, positions.size(), anyPositionWaiting, route.getRouteStatus()));
        }

        Log.d(TAG, "Route " + routeId + " pending confirmations state " + anyPositionWaiting);

        if (allCompleted && route.getRouteStatus() != NavigationOrderStatus.ORDER_COMPLETED && !anyPositionWaiting) {
            synchronized (route) {
                route.setRouteStatus(NavigationOrderStatus.ORDER_COMPLETED);
                route.setCompleteTime(System.currentTimeMillis());
                RouteStore.updateRoute(route);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ Route %d completed successfully at %d",
                            routeId, route.getCompleteTime()));
                    stateDebugger.log(String.format("  Total positions: %d, All completed", positions.size()));
                }

                LOG.info("Route {} completed successfully", routeId);
            }
        } else if (stateDebugger != null && !allCompleted) {
            stateDebugger.log(String.format("Route %d not yet completed - still %d positions pending",
                    routeId, positions.size() - completedCount));
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("updateSingleRouteStatus END: routeId=%d", routeId));
        }
    }

    /**
     * Create a route from a list of positions
     * @param positions List of target positions to navigate to
     * @param robotId The robot ID
     * @return The created route ID
     */
    public long createRouteFromPositionList(Position currentPosition, List<Position> positions, long robotId) {
        if (stateDebugger != null) {
            stateDebugger.log("========== createRouteFromPositionList START ==========");
            stateDebugger.log(String.format("robotId: %d", robotId));
            stateDebugger.log(String.format("positions count: %d", positions != null ? positions.size() : 0));
            stateDebugger.log(String.format("currentPosition: %s (floor=%s, map=%s)",
                    currentPosition != null ? currentPosition.getName() : "null",
                    currentPosition != null ? currentPosition.getFloor() : "null",
                    currentPosition != null ? currentPosition.getMapName() : "null"));

            if (positions != null) {
                stateDebugger.log("Target positions list:");
                for (int i = 0; i < positions.size(); i++) {
                    Position p = positions.get(i);
                    stateDebugger.log(String.format("  Position[%d]: '%s' (type=%d, taskType=%d, floor=%s, doorIds=%s)",
                            i, p.getName(), p.getType(), p.getTaskType(), p.getFloor(), p.getAllDoorIds()));
                }
            }
        }

        String routeName = "ROUTE-" + System.currentTimeMillis();
        RouteStore route = RouteStore.createRoute(routeName, robotId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Created route: id=%d, name=%s", route.getRouteId(), routeName));
        }

        for (int i = 0; i < positions.size(); i++) {
            Position targetPosition = positions.get(i);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Processing position %d/%d: '%s'",
                        i + 1, positions.size(), targetPosition.getName()));
            }

            // Create position monitor for this target
            PositionMonitor posMonitor = PositionMonitor.createPosition(
                    targetPosition, route.getRouteId(), i);

            // Set stay duration for auto-confirmation
            int stayDuration = targetPosition.getStayDuration();
            if (stayDuration > 0) {
                confirmationManager.setPositionStayDuration(posMonitor.getPositionId(), stayDuration);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Position %d stay duration set to %d seconds",
                            posMonitor.getPositionId(), stayDuration));
                }
            }

            if (targetPosition.getTaskType() == 1 || targetPosition.getTaskType() == 2) {
                confirmationManager.setPositionWaitingForConfirmation(posMonitor.getPositionId(), true);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Position %d requires confirmation (taskType=%d)",
                            posMonitor.getPositionId(), targetPosition.getTaskType()));
                }
            } else {
                confirmationManager.setPositionWaitingForConfirmation(posMonitor.getPositionId(), false);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Position %d does NOT require confirmation (taskType=%d)",
                            posMonitor.getPositionId(), targetPosition.getTaskType()));
                }
            }
            route.addPositionId(posMonitor.getPositionId());

            // Determine previous position for transition calculation
            Position previousPosition;
            if (i == 0) {
                // First position - use current robot position as previous
                previousPosition = currentPosition;
                posMonitor.setPositionStatus(NavigationOrderStatus.ORDER_PREREADY);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  First position - using current robot position as previous: %s",
                            previousPosition != null ? previousPosition.getName() : "null"));
                }
                LOG.info("First position {} - using current robot position as previous",
                        targetPosition.getName());
            } else {
                // Subsequent positions - use previous target position
                previousPosition = positions.get(i - 1);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Position %d - using previous target: %s",
                            i, previousPosition != null ? previousPosition.getName() : "null"));
                }
            }

            // Generate commands for this position using the appropriate previous position
            generateCommandsForPosition(posMonitor, route.getRouteId(), previousPosition, i == 0);
        }

        route.setRouteStatus(NavigationOrderStatus.ORDER_PREREADY);
        RouteStore.updateRoute(route);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Route %d set to PREREADY status", route.getRouteId()));
            stateDebugger.log(String.format("Created route %d with %d positions", route.getRouteId(), positions.size()));
        }

        LOG.info("Created route {} with {} positions", route.getRouteId(), positions.size());

        // Debug output
        Log.d(TAG, "routeStoreMap: " + gson.toJson(RouteStore.getAllRoutes()));
        Log.d(TAG, "positionMonitorMap: " + gson.toJson(PositionMonitor.getAllPositions()));
        Log.d(TAG, "commandMonitorMap: " + gson.toJson(CommandMonitor.getAllCommands()));

        stateDebugger.dumpFullState("After createRouteFromPositionList");

        if (stateDebugger != null) {
            stateDebugger.log(String.format("createRouteFromPositionList END: routeId=%d", route.getRouteId()));
            stateDebugger.log("================================================================");
        }

        return route.getRouteId();
    }

    /**
     * Generate commands for a position based on transition from previous position
     * @param position The position monitor for the target position
     * @param routeId The route ID
     * @param previous The previous position (could be null for first position)
     * @param isFirstPosition Flag indicating if this is the first position in the route
     */
    private void generateCommandsForPosition(PositionMonitor position, long routeId,
                                             Position previous, boolean isFirstPosition) {
        Position current = position.getPosition();

        if (stateDebugger != null) {
            stateDebugger.log("========== generateCommandsForPosition START ==========");
            stateDebugger.log(String.format("positionId: %d, routeId: %d", position.getPositionId(), routeId));
            stateDebugger.log(String.format("Current: '%s' (type=%d, taskType=%d, floor=%s)",
                    current.getName(), current.getType(), current.getTaskType(), current.getFloor()));
            stateDebugger.log(String.format("isFirstPosition: %b", isFirstPosition));
        }

        LOG.info("Generating commands for position {} (isFirst: {})",
                current.getName(), isFirstPosition);

        // Get robot communication parameters from ViewModel
        RobotCommunicationParams robotCommParams = new RobotCommunicationParams(
                basicViewModel.getRobotId().getValue() != null ? basicViewModel.getRobotId().getValue() : 1,
                basicViewModel.getLoraChannel().getValue() != null ? basicViewModel.getLoraChannel().getValue() : 0,
                basicViewModel.getLoraAddress().getValue() != null ? basicViewModel.getLoraAddress().getValue() : 0
        );

        if (stateDebugger != null) {
            stateDebugger.log(String.format("RobotCommParams: robotId=%d, channel=%d, address=%d",
                    robotCommParams.getRobotId(), robotCommParams.getLoraChannel(), robotCommParams.getLoraAddress()));
        }

        // Log positions for debugging
        if (stateDebugger != null) {
            stateDebugger.log(String.format("Previous position: '%s' (type=%d, floor=%s, pos=(%.2f,%.2f))",
                    previous.getName(), previous.getType(), previous.getFloor(),
                    previous.getPosX(), previous.getPosY()));
            stateDebugger.log(String.format("Current position: '%s' (type=%d, floor=%s, pos=(%.2f,%.2f))",
                    current.getName(), current.getType(), current.getFloor(),
                    current.getPosX(), current.getPosY()));
        }

        LOG.info("Previous: {} ({}) at ({}, {}), Current: {} ({}) at ({}, {})",
                previous.getName(), previous.getType(),
                previous.getPosX(), previous.getPosY(),
                current.getName(), current.getType(),
                current.getPosX(), current.getPosY());

        // Get transition type between previous and current position
        int transitionType = CommandUtils.getTransitionType(previous, current);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Transition type between '%s' and '%s': %d",
                    previous.getName(), current.getName(), transitionType));
        }

        LOG.info("Transition type between {} and {}: {}",
                previous.getName(), current.getName(), transitionType);

        if (isFirstPosition && transitionType == 5 &&
                Objects.equals(previous.getFloor(), current.getFloor())) {
            if (stateDebugger != null) {
                stateDebugger.log("First command starts from an elevator wait point on the same floor; " +
                        "using direct navigation and skipping blocking elevator release.");
            }

            LoraCommand moveCommand = new LoraCommand.Builder(getNavigationType(current), current.getName())
                    .withDescription("Move from elevator wait point to target")
                    .withTarget(current)
                    .withPlayMusic(false)
                    .build();

            CommandMonitor cmdMonitor = CommandMonitor.createCommand(
                    moveCommand.type, moveCommand, position.getPositionId(), routeId, 0);
            CommandMonitor.updateCommand(cmdMonitor);
            position.getCommandIds().add(cmdMonitor.getCommandId());
            PositionMonitor.updatePosition(position);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Position %d updated with direct move command %d",
                        position.getPositionId(), cmdMonitor.getCommandId()));
                stateDebugger.log("========== generateCommandsForPosition END ==========");
            }
            return;
        }

        // Get elevator configuration from current position's elevatorInfo
        List<ElevatorViewModel.ElevatorConfig> elevatorConfigs =
                elevatorViewModel.getElevatorConfigs().getValue();

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Elevator configs: %s",
                    elevatorConfigs != null ? elevatorConfigs.size() + " configs found" : "null"));
        }

        if (elevatorConfigs == null && (transitionType >= 1 && transitionType <= 6)) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("generateCommandsForPosition",
                        String.format("No elevator info found for position %s despite transition type %d",
                                current.getName(), transitionType));
            }
            LOG.warn("No elevator info found for position {} despite transition type {}",
                    current.getName(), transitionType);
            return;
        }

        // Get elevator wait and ride points from MapViewModel for the current floor
        List<Position> preridePointsList = mapViewModel.getAllElevatorPreridePointsAsList();
        List<Position> waitPointsList = mapViewModel.getAllElevatorWaitPointsAsList();
        List<Position> ridePointsList = mapViewModel.getAllElevatorRidePointsAsList();
        List<Position> transitionPointsList = mapViewModel.getAllElevatorTransitionPointsAsList();

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Wait points count: %d", waitPointsList != null ? waitPointsList.size() : 0));
            stateDebugger.log(String.format("Ride points count: %d", ridePointsList != null ? ridePointsList.size() : 0));
            stateDebugger.log(String.format("Transition points count: %d", transitionPointsList != null ? transitionPointsList.size() : 0));
        }

        // Get command sequence based on transition type
        List<LoraCommand> loraCommands = CommandUtils.getCommandSequence(
                elevatorConfigs, previous, current,
                preridePointsList, waitPointsList, ridePointsList, transitionPointsList,
                robotCommParams);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Generated %d Lora commands for transition type %d",
                    loraCommands != null ? loraCommands.size() : 0, transitionType));
        }

        LOG.info("Generated {} Lora commands for transition type {}",
                loraCommands.size(), transitionType);

        int cmdIndex = 0;

        // Add Lora commands from the sequence
        if (loraCommands != null) {
            for (LoraCommand loraCmd : loraCommands) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Creating command %d: type=%s, description='%s'",
                            cmdIndex, loraCmd.type, loraCmd.getDescription()));
                }

                LOG.info("Creating command {}: {} -> {}",
                        cmdIndex, loraCmd.type, loraCmd.getDescription());

                CommandMonitor cmdMonitor = CommandMonitor.createCommand(
                        loraCmd.type, loraCmd, position.getPositionId(), routeId, cmdIndex++);
                CommandMonitor.updateCommand(cmdMonitor);
                position.getCommandIds().add(cmdMonitor.getCommandId());

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Command %d created with id=%d",
                            cmdIndex - 1, cmdMonitor.getCommandId()));
                }
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logWarning("generateCommandsForPosition", "loraCommands is null!");
            }
        }

        PositionMonitor.updatePosition(position);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Position %d updated with %d commands",
                    position.getPositionId(), position.getCommandIds().size()));
            stateDebugger.log("========== generateCommandsForPosition END ==========");
        }
    }

    private NavigationOrderType getNavigationType(Position position) {
        switch (position.getType()) {
            case 1:
                if (!position.isVirtualOrbit()) {
                    return NavigationOrderType.OP_FREE_MOVE;
                } else {
                    return NavigationOrderType.OP_FIXED_MOVE;
                }
            case 5:
                return NavigationOrderType.OP_RECOGNIZE_LOAD;
            case 6:
                return NavigationOrderType.OP_RECOGNIZE_UNLOAD;
            case 7:
                return NavigationOrderType.OP_EXIT_SHELF;
            case 8:
                return NavigationOrderType.OP_NON_RECOGNIZE_LOAD;
            case 9:
                return NavigationOrderType.OP_NON_RECOGNIZE_UNLOAD;
            case 10:
                return NavigationOrderType.OP_CHARGE_START;
            case 11:
                return NavigationOrderType.OP_CHARGE_STOP;
            case 17:
                return NavigationOrderType.OP_FORWARD;
            case 18:
                return NavigationOrderType.OP_BACKWARD;
            case 19:
                return NavigationOrderType.OP_RECOGNIZE_ENTRY_ONLY;
            default:
                return NavigationOrderType.OP_FREE_MOVE;
        }
    }
}