package com.ezhan.amr.navigation.task;

import android.os.Handler;
import android.os.Looper;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.LoraCommand;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Navigation Cancellation Manager
 * Handles cancellation of routes, positions, and commands
 * Similar to TaskCancellationManager in the server code
 */
public class NavigationCancellationManager extends AbstractNavigationManager {
    private static final Logger LOG = LoggerFactory.getLogger(NavigationCancellationManager.class);
    private static NavigationCancellationManager instance;

    // Dependencies
    private ElevatorViewModel elevatorViewModel;
    private SharedViewModel sharedViewModel;
    private Handler mainHandler;
    private NavigationStateDebugger stateDebugger;

    // Track active cancellations
    private static final AtomicBoolean isCancelling = new AtomicBoolean(false);

    public static NavigationCancellationManager getInstance() {
        if (instance == null) {
            instance = new NavigationCancellationManager();
        }
        return instance;
    }
    private final CommandSender commandSender = new CommandSender();

    private NavigationCancellationManager() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.stateDebugger = NavigationStateDebugger.getInstance();
    }

    @Override
    public void initManager() {
        this.elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        this.sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        LOG.info("NavigationCancellationManager initialized");
        if (stateDebugger != null) {
            stateDebugger.logInfo("NavigationCancellationManager initialized");
        }
    }

    @Override
    public void processSingleRoute(long routeId) {
        // Cancellation manager doesn't process routes
    }

    @Override
    public void updateSingleRouteStatus(long routeId) {
        // Cancellation manager doesn't update status
    }

    /**
     * Cancel tasks by route ID
     * Similar to cancelTasksByGroup in TaskCancellationManager
     *
     * @param routeId The route ID to cancel
     * @param finishStatus The status to set (ORDER_CANCELLED, ORDER_FAILED)
     */
    public void cancelTasksByRoute(long routeId, NavigationOrderStatus finishStatus) {
        // Prevent concurrent cancellations of the same route
        if (!isCancelling.compareAndSet(false, true)) {
            LOG.warn("Cancellation already in progress, skipping route {}", routeId);
            if (stateDebugger != null) {
                stateDebugger.logWarning("cancelTasksByRoute",
                        String.format("Cancellation already in progress for route %d, skipping", routeId));
            }
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            // ===== SIMPLIFIED: Clean up exception manager completely =====
            if (stateDebugger != null) {
                stateDebugger.logInfo("Cleaning up exception manager state before cancellation");
            }
            NavigationExceptionManager.getInstance().cancelAllRecoveryAndCleanup();

            // Small delay to ensure cleanup is complete
            Thread.sleep(50);

            RouteStore cancelRoute = RouteStore.getRoute(routeId);
            if (cancelRoute == null) {
                LOG.warn("Route {} not found for cancellation", routeId);
                if (stateDebugger != null) {
                    stateDebugger.logWarning("cancelTasksByRoute",
                            String.format("Route %d not found for cancellation", routeId));
                }
                return;
            }

            LOG.info("Cancelling route {} with status {}", routeId, finishStatus);
            if (stateDebugger != null) {
                stateDebugger.logInfo(String.format("========== CANCELLING ROUTE %d ==========", routeId));
                stateDebugger.logInfo(String.format("Route: %s, Current Status: %s, Finish Status: %s",
                        cancelRoute.getRouteName() != null ? cancelRoute.getRouteName() : "unnamed",
                        cancelRoute.getRouteStatus(), finishStatus));
            }

            // Check if route is in a state that can be cancelled
            boolean isRouteExecuting = cancelRoute.getRouteStatus() == NavigationOrderStatus.ORDER_EXECUTING;
            boolean isRoutePreready = cancelRoute.getRouteStatus() == NavigationOrderStatus.ORDER_PREREADY;
            boolean isRouteReady = cancelRoute.getRouteStatus() == NavigationOrderStatus.ORDER_READY;
            boolean isRouteSuspended = cancelRoute.getRouteStatus() == NavigationOrderStatus.ORDER_SUSPENDED;

            // Cancel the route and all its positions/commands
            cancelAllTasks(routeId, finishStatus);

            // Only send physical cancellation commands if the route was actually executing
            if (isRouteExecuting || isRoutePreready || isRouteReady || isRouteSuspended) {

                // ===== 1. CANCEL ROBOT NAVIGATION =====
                boolean robotCancelSent = sendRobotCancelCommand(cancelRoute);

                // ===== 2. ELEVATOR RELEASE ACCESS =====
                boolean elevatorReleaseAccessSent = sendElevatorReleaseAndCancelOpenCommands(cancelRoute);

                LOG.info("Cancellation commands sent - RobotCancel: {}, ElevatorReleaseAccess: {}",
                        robotCancelSent, elevatorReleaseAccessSent);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Cancellation commands sent - RobotCancel: %s, ElevatorReleaseAccess: %s",
                            robotCancelSent ? "SUCCESS" : "FAILED",
                            elevatorReleaseAccessSent ? "SUCCESS" : "FAILED"));
                }
            } else {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Route %d not in executable state (%s), skipping physical commands",
                            routeId, cancelRoute.getRouteStatus()));
                }
            }

            // ===== VERIFY exception manager is cleaned up =====
            if (NavigationExceptionManager.getInstance().isActive()) {
                LOG.warn("Exception manager still active after cleanup, forcing full reset");
                NavigationExceptionManager.getInstance().cancelAllRecoveryAndCleanup();
                if (stateDebugger != null) {
                    stateDebugger.logWarning("cancelTasksByRoute",
                            "Forced second cleanup of exception manager");
                }
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            LOG.info("Route {} cancellation completed with status {} in {}ms", routeId, finishStatus, elapsedMs);
            if (stateDebugger != null) {
                stateDebugger.logInfo(String.format("✅ Route %d cancellation completed in %dms with status %s",
                        routeId, elapsedMs, finishStatus));
                stateDebugger.log("========== CANCELLATION COMPLETE ==========");
            }

        } catch (Exception e) {
            LOG.error("Failed to cancel route {}: {}", routeId, e.getMessage(), e);
            if (stateDebugger != null) {
                stateDebugger.logError("cancelTasksByRoute",
                        String.format("Failed to cancel route %d: %s", routeId, e.getMessage()));
            }
        } finally {
            isCancelling.set(false);
        }
    }

    /**
     * Send robot navigation cancellation command (OP_CANCEL)
     *
     * @param route The route being cancelled
     * @return true if command was sent successfully
     */
    private boolean sendRobotCancelCommand(RouteStore route) {
        long startTime = System.currentTimeMillis();

        try {
            LOG.info("Sending robot navigation cancel command for route {}", route.getRouteId());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("Sending ROBOT_CANCEL command for route %d", route.getRouteId()));
            }

            // Get the current executing position for context
            PositionMonitor currentPos = getCurrentPositionForRoute(route.getRouteId());
            long positionId = currentPos != null ? currentPos.getPositionId() : -1;

            if (stateDebugger != null && currentPos != null) {
                stateDebugger.log(String.format("Current position for cancellation: posId=%d, target='%s'",
                        positionId, currentPos.getPosition() != null ? currentPos.getPosition().getName() : "null"));
            }

            // Create a cancel command monitor
            int nextIndex = 0;
            if (currentPos != null) {
                nextIndex = getNextCommandIndex(currentPos.getPositionId());
            }

            CommandMonitor cancelCommand = CommandMonitor.createCommand(
                    NavigationOrderType.OP_CANCEL,
                    (Position) null,  // No target needed for cancel
                    positionId,
                    route.getRouteId(),
                    nextIndex
            );

            // Set status to READY so it will be processed
            cancelCommand.setCommandStatus(NavigationOrderStatus.ORDER_READY);
            CommandMonitor.updateCommand(cancelCommand);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Created cancel command %d for route %d",
                        cancelCommand.getCommandId(), route.getRouteId()));
            }

            // Send the command using CommandSender
            int sendResult = commandSender.sendCommand(cancelCommand);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (sendResult == 1) {
                LOG.info("Robot navigation cancel command sent successfully for route {} in {}ms",
                        route.getRouteId(), elapsedMs);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ ROBOT_CANCEL command %d sent successfully in %dms",
                            cancelCommand.getCommandId(), elapsedMs));
                }

                // Update command status
                cancelCommand.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
                cancelCommand.setCompleteTime(System.currentTimeMillis());
                CommandMonitor.updateCommand(cancelCommand);

                return true;
            } else {
                LOG.warn("Failed to send robot navigation cancel command for route {}",
                        route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.logError("sendRobotCancelCommand",
                            String.format("Failed to send cancel command for route %d, sendResult=%d",
                                    route.getRouteId(), sendResult));
                }

                cancelCommand.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
                CommandMonitor.updateCommand(cancelCommand);

                return false;
            }

        } catch (Exception e) {
            LOG.error("Error sending robot cancel command: {}", e.getMessage(), e);
            if (stateDebugger != null) {
                stateDebugger.logError("sendRobotCancelCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return false;
        }
    }

    /**
     * Send elevator cancel call, cancel open, and release access commands by finding elevator commands in all positions of the route
     * Looks for OP_ELEVATOR_CLAIM_ACCESS or OP_ELEVATOR_RELEASE_ACCESS commands in the route's positions
     * and extracts hexParams to send:
     * 1. OP_ELEVATOR_CANCEL_CALL - Cancel elevator call
     * 2. OP_ELEVATOR_CANCEL_OPEN - Cancel open / Close door
     * 3. OP_ELEVATOR_RELEASE_ACCESS - Release elevator access
     *
     * @param route The route being cancelled
     * @return true if commands were sent successfully (or not needed), false if failed when needed
     */
    private boolean sendElevatorReleaseAndCancelOpenCommands(RouteStore route) {
        long startTime = System.currentTimeMillis();

        try {
            if (route == null) {
                LOG.warn("Route is null, cannot send elevator commands");
                if (stateDebugger != null) {
                    stateDebugger.logWarning("sendElevatorReleaseAndCancelOpenCommands", "Route is null");
                }
                return false;
            }

            LOG.info("Looking for elevator commands in route {} to send release access and cancel open", route.getRouteId());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("Searching for elevator commands in route %d", route.getRouteId()));
            }

            // Get all positions for this route
            List<PositionMonitor> positions = getPositionsForRoute(route.getRouteId());
            if (positions == null || positions.isEmpty()) {
                LOG.debug("No positions found for route {}, skipping elevator commands", route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("No positions found for route %d, no elevator commands needed",
                            route.getRouteId()));
                }
                return true; // No positions = no elevator to release
            }

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Checking %d positions for elevator commands", positions.size()));
            }

            // Variables to store elevator parameters from found command
            String loraChannel = null;
            String loraAddress = null;
            String robotChannel = null;
            String robotAddress = null;
            String robotId = null;
            String floor = null;
            boolean foundElevatorCommand = false;
            Long sourceCommandId = null;
            String sourceCommandType = null;

            // Iterate through all positions to find elevator commands
            for (PositionMonitor position : positions) {
                List<CommandMonitor> commands = getCommandsForPosition(position.getPositionId());
                if (commands == null || commands.isEmpty()) {
                    continue;
                }

                for (CommandMonitor command : commands) {
                    NavigationOrderType cmdType = command.getCommandType();
                    // Look for elevator claim or release commands (they contain elevator config)
                    if (cmdType == NavigationOrderType.OP_ELEVATOR_CLAIM_ACCESS ||
                            cmdType == NavigationOrderType.OP_ELEVATOR_RELEASE_ACCESS) {

                        foundElevatorCommand = true;
                        sourceCommandId = command.getCommandId();
                        sourceCommandType = cmdType.toString();

                        if (stateDebugger != null) {
                            stateDebugger.log(String.format("Found elevator command %d (type=%s) in position %d",
                                    sourceCommandId, sourceCommandType, position.getPositionId()));
                        }

                        // Parse elevator parameters from command
                        ElevatorParams params = parseElevatorParamsFromCommand(command);
                        if (params != null) {
                            loraChannel = params.loraChannel;
                            loraAddress = params.loraAddress;
                            robotChannel = params.robotChannel;
                            robotAddress = params.robotAddress;
                            robotId = params.robotId;
                            floor = params.currentFloor;

                            if (stateDebugger != null) {
                                stateDebugger.log(String.format("Parsed elevator params: channel=%s, address=%s, " +
                                                "floor=%s, robotChannel=%s, robotAddress=%s, robotId=%s",
                                        loraChannel, loraAddress, floor,
                                        robotChannel, robotAddress, robotId));
                            }
                            break; // Found parameters, exit command loop
                        }
                    }
                }
                if (foundElevatorCommand && loraChannel != null) {
                    break; // Found valid params, exit position loop
                }
            }

            // If no elevator command found, no need to send elevator commands
            if (!foundElevatorCommand) {
                LOG.debug("No elevator commands found in route {}, skipping elevator commands",
                        route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("No elevator commands found in route %d, no elevator commands needed",
                            route.getRouteId()));
                }
                return true;
            }

            // If found command but couldn't parse parameters, log warning
            if (loraChannel == null || loraAddress == null) {
                LOG.warn("Found elevator command {} but could not parse channel/address parameters for route {}",
                        sourceCommandId, route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.logWarning("sendElevatorReleaseAndCancelOpenCommands",
                            String.format("Could not parse channel/address from command %d (type=%s)",
                                    sourceCommandId, sourceCommandType));
                }
                return false;
            }

            if (robotChannel == null || robotAddress == null || robotId == null) {
                LOG.warn("Found elevator command {} but could not parse robotChannel/robotAddress/robotId parameters for route {}",
                        sourceCommandId, route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.logWarning("sendElevatorReleaseAndCancelOpenCommands",
                            String.format("Could not parse robotChannel/robotAddress/robotId from command %d (type=%s)",
                                    sourceCommandId, sourceCommandType));
                }
                return false;
            }

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Creating elevator commands using channel=%s, address=%s",
                        loraChannel, loraAddress));
            }

            // Get current position for command context
            PositionMonitor currentPos = getCurrentPositionForRoute(route.getRouteId());
            long positionId = 1;
            int nextIndex = 0;
            if (currentPos != null) {
                nextIndex = getNextCommandIndex(currentPos.getPositionId());
            }

            Position currentPosition = sharedViewModel.getCurrentPosition();
            currentPosition.setName("current position");

            boolean allCommandsSent = true;

            // ===== 1. Send CANCEL CALL (cancel elevator call) command =====
            LoraCommand cancelCallLora = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_CALL, currentPosition.getName())
                    .withHexParam("channel", loraChannel)
                    .withHexParam("address", loraAddress)
                    .withHexParam("robotId", robotId)
                    .withHexParam("robotChannel", robotChannel)
                    .withHexParam("robotAddress", robotAddress)
                    .withDescription("Cancel elevator call")
                    .withTarget(currentPosition)
                    .build();

            CommandMonitor cancelCallCommand = CommandMonitor.createCommand(
                    NavigationOrderType.OP_ELEVATOR_CANCEL_CALL,
                    cancelCallLora,
                    positionId,
                    route.getRouteId(),
                    nextIndex
            );

            cancelCallCommand.setCommandStatus(NavigationOrderStatus.ORDER_READY);
            CommandMonitor.updateCommand(cancelCallCommand);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Created cancel call command %d for route %d",
                        cancelCallCommand.getCommandId(), route.getRouteId()));
            }

            // Send the cancel call command with fast cleanup timeout
            int cancelCallResult = commandSender.sendCancelCleanupCommand(cancelCallCommand);
            long cancelCallElapsedMs = System.currentTimeMillis() - startTime;

            if (cancelCallResult == 1) {
                LOG.info("Elevator cancel call command sent successfully for route {} in {}ms",
                        route.getRouteId(), cancelCallElapsedMs);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ ELEVATOR_CANCEL_CALL command %d sent successfully in %dms",
                            cancelCallCommand.getCommandId(), cancelCallElapsedMs));
                }
                cancelCallCommand.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
                cancelCallCommand.setCompleteTime(System.currentTimeMillis());
                CommandMonitor.updateCommand(cancelCallCommand);
            } else {
                LOG.warn("Failed to send elevator cancel call command for route {}", route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.logError("sendElevatorReleaseAndCancelOpenCommands",
                            String.format("Failed to send cancel call command for route %d, sendResult=%d",
                                    route.getRouteId(), cancelCallResult));
                }
                cancelCallCommand.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
                CommandMonitor.updateCommand(cancelCallCommand);
                allCommandsSent = false;
            }

            // ===== 2. Send CANCEL OPEN (close door) command =====
            LoraCommand cancelOpenLora = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, currentPosition.getName())
                    .withHexParam("channel", loraChannel)
                    .withHexParam("address", loraAddress)
                    .withHexParam("robotId", robotId)
                    .withHexParam("robotChannel", robotChannel)
                    .withHexParam("robotAddress", robotAddress)
                    .withDescription("Cancel open / Close door")
                    .withTarget(currentPosition)
                    .build();

            CommandMonitor cancelOpenCommand = CommandMonitor.createCommand(
                    NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN,
                    cancelOpenLora,
                    positionId,
                    route.getRouteId(),
                    nextIndex + 1  // Use next index after cancel call
            );

            cancelOpenCommand.setCommandStatus(NavigationOrderStatus.ORDER_READY);
            CommandMonitor.updateCommand(cancelOpenCommand);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Created cancel open command %d for route %d",
                        cancelOpenCommand.getCommandId(), route.getRouteId()));
            }

            // Send the cancel open command with fast cleanup timeout
            int cancelOpenResult = commandSender.sendCancelCleanupCommand(cancelOpenCommand);
            long cancelOpenElapsedMs = System.currentTimeMillis() - startTime;

            if (cancelOpenResult == 1) {
                LOG.info("Elevator cancel open command sent successfully for route {} in {}ms",
                        route.getRouteId(), cancelOpenElapsedMs);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ ELEVATOR_CANCEL_OPEN command %d sent successfully in %dms",
                            cancelOpenCommand.getCommandId(), cancelOpenElapsedMs));
                }
                cancelOpenCommand.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
                cancelOpenCommand.setCompleteTime(System.currentTimeMillis());
                CommandMonitor.updateCommand(cancelOpenCommand);
            } else {
                LOG.warn("Failed to send elevator cancel open command for route {}", route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.logError("sendElevatorReleaseAndCancelOpenCommands",
                            String.format("Failed to send cancel open command for route %d, sendResult=%d",
                                    route.getRouteId(), cancelOpenResult));
                }
                cancelOpenCommand.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
                CommandMonitor.updateCommand(cancelOpenCommand);
                allCommandsSent = false;
            }

            // ===== 2. Send RELEASE ACCESS command =====
            LoraCommand releaseAccessLora = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_RELEASE_ACCESS, currentPosition.getName())
                    .withHexParam("channel", loraChannel)
                    .withHexParam("address", loraAddress)
                    .withHexParam("robotId", robotId)
                    .withHexParam("robotChannel", robotChannel)
                    .withHexParam("robotAddress", robotAddress)
                    .withDescription("Release access")
                    .withTarget(currentPosition)
                    .build();

            CommandMonitor releaseCommand = CommandMonitor.createCommand(
                    NavigationOrderType.OP_ELEVATOR_RELEASE_ACCESS,
                    releaseAccessLora,
                    positionId,
                    route.getRouteId(),
                    nextIndex + 2  // Use next index after cancel open
            );

            releaseCommand.setCommandStatus(NavigationOrderStatus.ORDER_READY);
            CommandMonitor.updateCommand(releaseCommand);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Created release access command %d for route %d",
                        releaseCommand.getCommandId(), route.getRouteId()));
            }

            // Send the release access command with fast cleanup timeout
            int releaseResult = commandSender.sendCancelCleanupCommand(releaseCommand);
            long releaseElapsedMs = System.currentTimeMillis() - startTime;

            if (releaseResult == 1) {
                LOG.info("Elevator release access command sent successfully for route {} in {}ms",
                        route.getRouteId(), releaseElapsedMs);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ ELEVATOR_RELEASE_ACCESS command %d sent successfully in %dms",
                            releaseCommand.getCommandId(), releaseElapsedMs));
                }
                releaseCommand.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
                releaseCommand.setCompleteTime(System.currentTimeMillis());
                CommandMonitor.updateCommand(releaseCommand);
            } else {
                LOG.warn("Failed to send elevator release access command for route {}", route.getRouteId());
                if (stateDebugger != null) {
                    stateDebugger.logError("sendElevatorReleaseAndCancelOpenCommands",
                            String.format("Failed to send release access command for route %d, sendResult=%d",
                                    route.getRouteId(), releaseResult));
                }
                releaseCommand.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
                CommandMonitor.updateCommand(releaseCommand);
                allCommandsSent = false;
            }

            return allCommandsSent;

        } catch (Exception e) {
            LOG.error("Error sending elevator commands: {}", e.getMessage(), e);
            if (stateDebugger != null) {
                stateDebugger.logError("sendElevatorReleaseAndCancelOpenCommands",
                        String.format("Exception: %s", e.getMessage()));
            }
            return false;
        }
    }

    /**
     * Parse elevator parameters from CommandMonitor's operationParams JSON
     * Similar to the method in NavigationExceptionManager
     */
    private ElevatorParams parseElevatorParamsFromCommand(CommandMonitor command) {
        if (command == null) {
            return null;
        }

        String operationParams = command.getOperationParams();
        if (operationParams == null || operationParams.isEmpty()) {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("No operationParams for command %d", command.getCommandId()));
            }
            return null;
        }

        try {
            com.google.gson.JsonObject jsonParams = com.google.gson.JsonParser.parseString(operationParams).getAsJsonObject();

            if (!jsonParams.has("hexParams")) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("No hexParams for command %d", command.getCommandId()));
                }
                return null;
            }

            com.google.gson.JsonObject hexParams = jsonParams.getAsJsonObject("hexParams");
            ElevatorParams params = new ElevatorParams();

            // Parse channel
            if (hexParams.has("channel")) {
                params.loraChannel = hexParams.get("channel").getAsString();
            }

            // Parse address
            if (hexParams.has("address")) {
                params.loraAddress = hexParams.get("address").getAsString();
            }

            if (hexParams.has("robotChannel")) {
                params.robotChannel = hexParams.get("robotChannel").getAsString();
            }

            // Parse address
            if (hexParams.has("robotAddress")) {
                params.robotAddress = hexParams.get("robotAddress").getAsString();
            }

            if (hexParams.has("robotId")) {
                params.robotId = hexParams.get("robotId").getAsString();
            }

            return params;

        } catch (Exception e) {
            LOG.error("Failed to parse elevator parameters from command {}: {}", command.getCommandId(), e.getMessage());
            if (stateDebugger != null) {
                stateDebugger.logError("parseElevatorParamsFromCommand",
                        String.format("Failed for command %d: %s", command.getCommandId(), e.getMessage()));
            }
            return null;
        }
    }

    /**
     * Get the current executing position for a route
     */
    private PositionMonitor getCurrentPositionForRoute(long routeId) {
        List<PositionMonitor> positions = getPositionsForRoute(routeId);

        for (PositionMonitor pos : positions) {
            if (pos.getPositionStatus() == NavigationOrderStatus.ORDER_EXECUTING ||
                    pos.getPositionStatus() == NavigationOrderStatus.ORDER_READY) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Get the next available command index for a position
     */
    private int getNextCommandIndex(long positionId) {
        List<CommandMonitor> commands = getCommandsForPosition(positionId);
        if (commands.isEmpty()) return 0;

        // Find the highest index and add 1
        int maxIndex = commands.stream()
                .mapToInt(CommandMonitor::getSequenceIndex)
                .max()
                .orElse(0);
        return maxIndex + 1;
    }

    /**
     * Check if a route should be considered for cancellation based on its status
     * Similar to checkTaskEndStatus in TaskCancellationManager
     */
    private boolean checkRouteEndStatus(RouteStore route) {
        NavigationOrderStatus status = route.getRouteStatus();
        return status == NavigationOrderStatus.ORDER_CANCELLED ||
                status == NavigationOrderStatus.ORDER_COMPLETED ||
                status == NavigationOrderStatus.ORDER_FAILED;
    }

    // Inner class for elevator parameters
    private static class ElevatorParams {
        String currentFloor = "";
        String currentMap = "";
        Position waitPoint = null;
        Position ridePoint = null;
        String loraChannel = null;
        String loraAddress = null;
        String robotChannel = null;
        String robotAddress = null;
        String robotId = null;
    }
}
