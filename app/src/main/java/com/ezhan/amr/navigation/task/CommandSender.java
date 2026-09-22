package com.ezhan.amr.navigation.task;

import android.util.Log;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class CommandSender {
    private final String TAG = "CommandSender";
    private static final Logger LOG = LoggerFactory.getLogger(CommandSender.class);
    public static final int FAST_CANCEL_CLEANUP_TIMEOUT_MS = 500;

    // Reference to the actual command clients
    private CommandWebSocketClient commandClient;
    private SharedViewModel sharedViewModel;
    private ElevatorViewModel elevatorViewModel;
    private MapViewModel mapViewModel;
    private LoraCommunicator loraCommunicator;
    private AgvStatusResponse latestStatus;
    private StatusWebSocketClient.StatusListener statusListener;
    private NavigationStateDebugger debugger;

    public CommandSender() {
        this.sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        this.elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        this.mapViewModel = MyApplication.getInstance().getMapViewModel();
        this.commandClient = sharedViewModel.getCommandClient();
        this.loraCommunicator = sharedViewModel.loraCommunicator;
        this.debugger = NavigationStateDebugger.getInstance();

        if (debugger != null) {
            debugger.log("CommandSender initialized");
        }

        this.statusListener = new StatusWebSocketClient.StatusListener() {
            @Override
            public void onStatusUpdate(AgvStatusResponse response) {
                if (response != null && response.data != null) {
                    latestStatus = response;
                }
            }
        };
        Log.d(TAG, "Registering listener with SharedViewModel");
        this.sharedViewModel.registerStatusListener(statusListener);
    }

    /**
     * Send a command based on its type
     * @return 1 for success, 0 for failure
     */
    public int sendCommand(CommandMonitor command) {
        long startTime = System.currentTimeMillis();

        // Add logging at entry point
        LOG.info("sendCommand called - Command ID: {}, Type: {}, OperationParams: {}",
                command.getCommandId(),
                command.getCommandType(),
                command.getOperationParams() != null ? command.getOperationParams() : "null");

        if (debugger != null) {
            debugger.log(String.format("========== sendCommand START =========="));
            debugger.log(String.format("Command ID: %d, Type: %s",
                    command.getCommandId(), command.getCommandType()));
            debugger.log(String.format("Position ID: %d, Route ID: %d",
                    command.getPositionId(), command.getRouteId()));
            if (command.getTarget() != null) {
                debugger.log(String.format("Target: '%s' (pos=%.2f,%.2f, floor=%s)",
                        command.getTarget().getName(),
                        command.getTarget().getPosX(),
                        command.getTarget().getPosY(),
                        command.getTarget().getFloor()));
            }
        }

        int result;
        switch (command.getCommandType()) {
            case OP_FREE_MOVE:
                result = sendFreeMoveCommand(command);
                break;
            case OP_FIXED_MOVE:
                result = sendFixedMoveCommand(command);
                break;
            case OP_IN_ELEVATOR_MOVE:
                result = sendInElevatorMoveCommand(command);
                break;
            case OP_OUT_ELEVATOR_MOVE:
                result = sendOutElevatorMoveCommand(command);
                break;
            case OP_RECOGNIZE_LOAD:
                result = sendRecognizeLoadCommand(command);
                break;

            case OP_RECOGNIZE_UNLOAD:
                result = sendRecognizeUnloadCommand(command);
                break;

            case OP_NON_RECOGNIZE_LOAD:
                result = sendNonRecognizeLoadCommand(command);
                break;

            case OP_NON_RECOGNIZE_UNLOAD:
                result = sendNonRecognizeUnloadCommand(command);
                break;

            case OP_EXIT_SHELF:
                result = sendExitShelfCommand(command);
                break;

            case OP_RECOGNIZE_ENTRY_ONLY:
                result = sendRecognizeEntryOnlyCommand(command);
                break;

            case OP_CHARGE_START:
                result = sendChargeStartCommand(command);
                break;

            case OP_CHARGE_STOP:
                result = sendChargeStopCommand(command);
                break;

            case OP_ELEVATOR_CALL:
                result = sendElevatorCallCommand(command);
                break;

            case OP_ELEVATOR_CHECK:
                result = sendElevatorCheckCommand(command);
                break;

            case OP_ELEVATOR_DOOR_CHECK:
                result = sendElevatorDoorCheckCommand(command);
                break;

            case OP_ELEVATOR_ARRIVAL_DOOR_CHECK:
                result = sendElevatorArrivalDoorCheckCommand(command);
                break;

            case OP_ELEVATOR_OPEN:
                result = sendElevatorOpenCommand(command);
                break;

            case OP_ELEVATOR_CANCEL_OPEN:
                result = sendElevatorCancelOpenCommand(command);
                break;

            case OP_ELEVATOR_QUERY_ACCESS:
                result = sendElevatorQueryAccessCommand(command);
                break;

            case OP_ELEVATOR_CLAIM_ACCESS:
                result = sendElevatorClaimAccessCommand(command);
                break;

            case OP_ELEVATOR_RELEASE_ACCESS:
                result = sendElevatorReleaseAccessCommand(command);
                break;

            case OP_AUTO_DOOR_OPEN:
                result = sendAutoDoorOpenCommand(command);
                break;

            case OP_AUTO_DOOR_CANCEL_OPEN:
                result = sendAutoDoorCancelOpenCommand(command);
                break;

            case OP_AUTO_DOOR_CHECK:
                result = sendAutoDoorCheckCommand(command);
                break;

            case OP_CHANGE_MAP:
                result = sendChangeMapCommand(command);
                break;

            case OP_RELOCALIZE:
                result = sendRelocalizeCommand(command);
                break;

            case OP_CANCEL:
                result = sendCancelCommand(command);
                break;

            case OP_FORWARD:
                result = sendForwardCommand(command);
                break;

            case OP_BACKWARD:
                result = sendBackwardCommand(command);
                break;

            default:
                LOG.warn("Unhandled command type: {}", command.getCommandType());
                if (debugger != null) {
                    debugger.logWarning("sendCommand",
                            String.format("Unhandled command type: %s for command %d",
                                    command.getCommandType(), command.getCommandId()));
                }
                result = 0;
        }

        long elapsedMs = System.currentTimeMillis() - startTime;

        LOG.info("sendCommand result for Command ID {}: {}", command.getCommandId(), result);

        if (debugger != null) {
            debugger.log(String.format("Command %d %s in %d ms",
                    command.getCommandId(), result == 1 ? "SUCCEEDED" : "FAILED", elapsedMs));
            if (result == 1) {
                debugger.log(String.format("✅ [SEND_SUCCESS] Command[%d]: %s",
                        command.getCommandId(), command.getCommandType()));
            } else {
                debugger.logError("sendCommand",
                        String.format("❌ [SEND_FAILED] Command[%d]: %s",
                                command.getCommandId(), command.getCommandType()));
            }
            debugger.log("========== sendCommand END ==========");
        }

        return result;
    }

    public int sendCancelCleanupCommand(CommandMonitor command) {
        return sendLoraHexCommand(command, FAST_CANCEL_CLEANUP_TIMEOUT_MS);
    }

    private int sendFreeMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendFreeMoveCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendFreeMoveCommand - Command ID: {}", command.getCommandId());

        // ⭐ 检查 WebSocket 连接状态 - 方案一
        if (!commandClient.isConnected()) {
            LOG.error("sendFreeMoveCommand - Command WebSocket not connected, cannot send command {}",
                    command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendFreeMoveCommand",
                        String.format("WebSocket not connected - command %d will not be sent, will retry in next cycle",
                                command.getCommandId()));
            }
            return 0; // 返回失败，保持命令状态为 ORDER_READY，400ms 后自动重试
        }

        if (command.getTarget() == null) {
            LOG.error("sendFreeMoveCommand - Target is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendFreeMoveCommand",
                        String.format("Target is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        Position target = command.getTarget();
        try {
            if (debugger != null) {
                debugger.log(String.format("Sending FREE_MOVE to (%.2f, %.2f, %.2f) speed=%.2f",
                        target.getPosX(), target.getPosY(), target.getYaw(), target.getSpeed()));
            }

            // Send async command - result will be handled via status updates
            commandClient.navigateToPositionAsync(
                    target.getPosX(), target.getPosY(), target.getYaw(), target.getSpeed(),
                    new CommandWebSocketClient.Callback() {
                        @Override
                        public void onSuccess(String response) {
                            LOG.info("Free move successful for command {}, target {}, response {}",
                                    command.getCommandId(), target, response);
                            if (debugger != null) {
                                debugger.log(String.format("✅ FREE_MOVE success for command %d: %s",
                                        command.getCommandId(), response));
                            }
                        }

                        @Override
                        public void onError(String error) {
                            LOG.error("Free move failed for command {}: {}", command.getCommandId(), error);
                            if (debugger != null) {
                                debugger.logError("sendFreeMoveCommand",
                                        String.format("FREE_MOVE failed for command %d: %s",
                                                command.getCommandId(), error));
                            }
                        }
                    });

            return 1;
        } catch (Exception e) {
            LOG.error("Error sending free move command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendFreeMoveCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int sendFixedMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendFixedMoveCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendFixedMoveCommand - Command ID: {}", command.getCommandId());

        // ⭐ 检查 WebSocket 连接状态 - 方案一
        if (!commandClient.isConnected()) {
            LOG.error("sendFixedMoveCommand - Command WebSocket not connected, cannot send command {}",
                    command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendFixedMoveCommand",
                        String.format("WebSocket not connected - command %d will not be sent, will retry in next cycle",
                                command.getCommandId()));
            }
            return 0; // 返回失败，保持命令状态为 ORDER_READY，400ms 后自动重试
        }

        if (command.getTarget() == null) {
            LOG.error("sendFixedMoveCommand - Target is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendFixedMoveCommand",
                        String.format("Target is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        Position target = command.getTarget();
        try {
            BasicViewModel basicViewModel = MyApplication.getInstance().getBasicViewModel();
            Integer obstacleTime = basicViewModel.getVirtualOrbitObstacleTime().getValue();
            int time = obstacleTime != null ? obstacleTime : -1;

            if (debugger != null) {
                debugger.log(String.format("Sending FIXED_MOVE to (%.2f, %.2f, %.2f) speed=%.2f, obstacleTime=%d",
                        target.getPosX(), target.getPosY(), target.getYaw(), target.getSpeed(), time));
            }

            commandClient.routeNavigateToPositionAsync(
                    target.getPosX(), target.getPosY(), target.getYaw(), target.getSpeed(), time,
                    new CommandWebSocketClient.Callback() {
                        @Override
                        public void onSuccess(String response) {
                            LOG.info("Fixed move successful for command {}, target {}, response {}",
                                    command.getCommandId(), target, response);
                            if (debugger != null) {
                                debugger.log(String.format("✅ FIXED_MOVE success for command %d", command.getCommandId()));
                            }
                        }

                        @Override
                        public void onError(String error) {
                            LOG.error("Fixed move failed for command {}: {}", command.getCommandId(), error);
                            if (debugger != null) {
                                debugger.logError("sendFixedMoveCommand",
                                        String.format("FIXED_MOVE failed for command %d: %s",
                                                command.getCommandId(), error));
                            }
                        }
                    });

            return 1;
        } catch (Exception e) {
            LOG.error("Error sending fixed move command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendFixedMoveCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int sendInElevatorMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendInElevatorMoveCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendInElevatorMoveCommand - Command ID: {}", command.getCommandId());

        // ⭐ 检查 WebSocket 连接状态 - 方案一
        if (!commandClient.isConnected()) {
            LOG.error("sendInElevatorMoveCommand - Command WebSocket not connected, cannot send command {}",
                    command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendInElevatorMoveCommand",
                        String.format("WebSocket not connected - command %d will not be sent, will retry in next cycle",
                                command.getCommandId()));
            }
            return 0; // 返回失败，保持命令状态为 ORDER_READY，400ms 后自动重试
        }

        if (command.getTarget() == null) {
            LOG.error("sendInElevatorMoveCommand - Target is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendInElevatorMoveCommand",
                        String.format("Target is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        Position current = getCurrentPosition();
        Position target = command.getTarget();
        Position preride = command.getPreridePoint();
        Position ride = command.getRidePoint();

        if (current == null || target == null || preride == null || ride == null) {
            LOG.error("sendInElevatorMoveCommand - Current position, target, or waypoints is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendInElevatorMoveCommand",
                        String.format("Current position or waypoints is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        try {
            // Create waypoints list for the entire path
            java.util.List<Position> waypoints = new java.util.ArrayList<>();

            // 1. First position: current position
            Position firstWaypoint = current;
            waypoints.add(firstWaypoint);

            // 2. Second position: preride point with current orientation
            Position secondWaypoint = new Position(
                    preride.getId(),
                    preride.getName() + "_oriented",
                    preride.getPosX(),
                    preride.getPosY(),
                    firstWaypoint.getYaw()
            );
            waypoints.add(secondWaypoint);

            // 3. Third position: preride coordinates but with target's orientation
            Position thirdWaypoint = new Position(
                    preride.getId(),
                    preride.getName() + "_oriented",
                    preride.getPosX(),
                    preride.getPosY(),
                    target.getYaw()
            );
            waypoints.add(thirdWaypoint);

            // 4. Fourth position: target
            Position fourthWaypoint = target;
            waypoints.add(fourthWaypoint);

            if (debugger != null) {
                debugger.log(String.format("Sending IN_ELEVATOR_MOVE with %d waypoints:", waypoints.size()));
                debugger.log(String.format("  Waypoint 1 (Current): (%.2f, %.2f, %.2f) - %s",
                        firstWaypoint.getPosX(), firstWaypoint.getPosY(), firstWaypoint.getYaw(), firstWaypoint.getName()));
                debugger.log(String.format("  Waypoint 2 (Preride with current orientation): (%.2f, %.2f, %.2f) - %s",
                        secondWaypoint.getPosX(), secondWaypoint.getPosY(), secondWaypoint.getYaw(), secondWaypoint.getName()));
                debugger.log(String.format("  Waypoint 3 (Preride with target orientation): (%.2f, %.2f, %.2f)",
                        thirdWaypoint.getPosX(), thirdWaypoint.getPosY(), thirdWaypoint.getYaw()));
                debugger.log(String.format("  Waypoint 4 (Target): (%.2f, %.2f, %.2f) - %s",
                        fourthWaypoint.getPosX(), fourthWaypoint.getPosY(), fourthWaypoint.getYaw(), fourthWaypoint.getName()));
            }

            // Send the complete path in one command
            commandClient.fixedPathNavigateAsync(waypoints, 0.4f, new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("IN_ELEVATOR_MOVE (multi-waypoint) successful for command {}: {}",
                            command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ IN_ELEVATOR_MOVE success for command %d - completed all waypoints",
                                command.getCommandId()));
                    }
                }

                @Override
                public void onError(String error) {
                    LOG.error("IN_ELEVATOR_MOVE (multi-waypoint) failed for command {}: {}",
                            command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("sendInElevatorMoveCommand",
                                String.format("IN_ELEVATOR_MOVE failed for command %d: %s",
                                        command.getCommandId(), error));
                    }
                }
            });
            return 1;
        } catch (Exception e) {
            LOG.error("Error sending IN_ELEVATOR_MOVE command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendInElevatorMoveCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int sendOutElevatorMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendOutElevatorMoveCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendOutElevatorMoveCommand - Command ID: {}", command.getCommandId());

        // ⭐ 检查 WebSocket 连接状态 - 方案一
        if (!commandClient.isConnected()) {
            LOG.error("sendOutElevatorMoveCommand - Command WebSocket not connected, cannot send command {}",
                    command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendOutElevatorMoveCommand",
                        String.format("WebSocket not connected - command %d will not be sent, will retry in next cycle",
                                command.getCommandId()));
            }
            return 0; // 返回失败，保持命令状态为 ORDER_READY，400ms 后自动重试
        }

        if (command.getTarget() == null) {
            LOG.error("sendOutElevatorMoveCommand - Target is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendOutElevatorMoveCommand",
                        String.format("Target is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        Position preride = command.getPreridePoint();
        Position ride = command.getRidePoint();

        if (preride == null || ride == null) {
            LOG.error("sendOutElevatorMoveCommand - Preride or ride point is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendOutElevatorMoveCommand",
                        String.format("Preride or ride point is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        try {
            java.util.List<Position> waypoints = new java.util.ArrayList<>();

            // Check if this is an exception recovery command
            if (command.isExceptionRecovery()) {
                // Get current robot position for recovery navigation
                Position currentPos = getCurrentPosition();
                if (currentPos == null) {
                    LOG.error("Cannot get current position for exception recovery");
                    if (debugger != null) {
                        debugger.logError("sendOutElevatorMoveCommand",
                                "Cannot get current position for exception recovery");
                    }
                    return 0;
                }

                if (debugger != null) {
                    debugger.log(String.format("🔄 EXCEPTION RECOVERY MODE: Current position (%.2f, %.2f, %.2f°)",
                            currentPos.getPosX(), currentPos.getPosY(), Math.toDegrees(currentPos.getYaw())));
                    debugger.log(String.format("  Target preride point: (%.2f, %.2f, %.2f°)",
                            preride.getPosX(), preride.getPosY(), Math.toDegrees(preride.getYaw())));
                }

                // FIRST WAYPOINT: Current position (with current orientation)
                Position firstWaypoint = new Position(
                        currentPos.getId(),
                        "current_position",
                        currentPos.getPosX(),
                        currentPos.getPosY(),
                        currentPos.getYaw()
                );
                waypoints.add(firstWaypoint);

                if (debugger != null) {
                    debugger.log(String.format("  Waypoint 1: Current position (%.2f, %.2f, %.2f°)",
                            currentPos.getPosX(), currentPos.getPosY(), Math.toDegrees(currentPos.getYaw())));
                }

                // Calculate orientation difference in degrees
                double currentYawDeg = Math.toDegrees(currentPos.getYaw());
                double targetYawDeg = Math.toDegrees(preride.getYaw());
                double orientationDiff = Math.abs(currentYawDeg - targetYawDeg);
                orientationDiff = Math.min(orientationDiff, 360 - orientationDiff); // Normalize to 0-180

                if (debugger != null) {
                    debugger.log(String.format("  Orientation difference: %.2f°", orientationDiff));
                }

                // Check if orientation difference is more than 10 degrees
                if (orientationDiff > 10.0) {
                    // Second waypoint: preride coordinates but with current orientation
                    Position secondWaypoint = new Position(
                            preride.getId(),
                            preride.getName() + "_with_current_orientation",
                            preride.getPosX(),
                            preride.getPosY(),
                            currentPos.getYaw()  // Use current orientation
                    );
                    waypoints.add(secondWaypoint);

                    if (debugger != null) {
                        debugger.log(String.format("  Waypoint 2: Move to preride coordinates with current orientation (%.2f°)",
                                Math.toDegrees(currentPos.getYaw())));
                    }

                    // Third waypoint: preride coordinates with target orientation
                    Position thirdWaypoint = new Position(
                            preride.getId(),
                            preride.getName() + "_with_target_orientation",
                            preride.getPosX(),
                            preride.getPosY(),
                            preride.getYaw()  // Use target orientation
                    );
                    waypoints.add(thirdWaypoint);

                    if (debugger != null) {
                        debugger.log(String.format("  Waypoint 3: Rotate to target orientation at preride point (%.2f°)",
                                Math.toDegrees(preride.getYaw())));
                    }
                } else {
                    // Orientation difference is within 10 degrees, go directly to preride with target orientation
                    Position directWaypoint = new Position(
                            preride.getId(),
                            preride.getName() + "_oriented",
                            preride.getPosX(),
                            preride.getPosY(),
                            preride.getYaw()
                    );
                    waypoints.add(directWaypoint);

                    if (debugger != null) {
                        debugger.log("  Orientation within 10°, moving directly to preride point with target orientation");
                    }
                }
            } else {
                // Normal operation: use standard two-waypoint navigation via ride point
                if (debugger != null) {
                    debugger.log("📍 NORMAL MODE: Standard OUT_ELEVATOR_MOVE via ride point");
                }

                // First waypoint: ride point with preride orientation
                Position rideWithPrerideOrientation = new Position(
                        ride.getId(),
                        ride.getName() + "_with_preride_orientation",
                        ride.getPosX(),
                        ride.getPosY(),
                        preride.getYaw()
                );
                waypoints.add(rideWithPrerideOrientation);

                // Second waypoint: preride point with its own orientation
                Position prerideOriented = new Position(
                        preride.getId(),
                        preride.getName() + "_oriented",
                        preride.getPosX(),
                        preride.getPosY(),
                        preride.getYaw()
                );
                waypoints.add(prerideOriented);
            }

            if (debugger != null) {
                debugger.log(String.format("Sending OUT_ELEVATOR_MOVE with %d waypoints:", waypoints.size()));
                for (int i = 0; i < waypoints.size(); i++) {
                    Position wp = waypoints.get(i);
                    debugger.log(String.format("  Waypoint %d: (%.2f, %.2f, %.2f°) - %s",
                            i + 1, wp.getPosX(), wp.getPosY(), Math.toDegrees(wp.getYaw()), wp.getName()));
                }
            }

            // Send the path
            commandClient.fixedPathNavigateAsync(waypoints, 0.4f, new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("OUT_ELEVATOR_MOVE sent successfully for command {}: {}", command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ OUT_ELEVATOR_MOVE command %d sent, waiting for navigation completion",
                                command.getCommandId()));
                    }
                }

                @Override
                public void onError(String error) {
                    LOG.error("OUT_ELEVATOR_MOVE failed for command {}: {}", command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("sendOutElevatorMoveCommand",
                                String.format("OUT_ELEVATOR_MOVE failed for command %d: %s",
                                        command.getCommandId(), error));
                    }
                }
            });
            return 1;
        } catch (Exception e) {
            LOG.error("Error sending OUT_ELEVATOR_MOVE command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendOutElevatorMoveCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int sendRecognizeLoadCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendRecognizeLoadCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendRecognizeLoadCommand - Command ID: {}", command.getCommandId());
        if (command.getTarget() == null) {
            LOG.error("sendRecognizeLoadCommand - Target is null for Command ID: {}", command.getCommandId());
            return 0;
        }

        Position target = command.getTarget();
        boolean success = commandClient.sendJackModeNav(
                target.getPosX(), target.getPosY(), target.getYaw(),
                target.getSpeed(), "upFork", 0.00f, target.getRecognizeDistance(), target.getId());

        if (debugger != null) {
            debugger.log(String.format("RECOGNIZE_LOAD %s for command %d",
                    success ? "SUCCESS" : "FAILED", command.getCommandId()));
        }

        LOG.info("sendRecognizeLoadCommand result for Command ID {}: {}", command.getCommandId(), success);
        return success ? 1 : 0;
    }

    private int sendRecognizeEntryOnlyCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendRecognizeEntryOnlyCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendRecognizeEntryOnlyCommand - Command ID: {}", command.getCommandId());
        if (command.getTarget() == null) {
            LOG.error("sendRecognizeEntryOnlyCommand - Target is null for Command ID: {}", command.getCommandId());
            return 0;
        }

        Position target = command.getTarget();
        LOG.info("RECOGNIZE_ENTRY_ONLY params: action=\"upFork\", td(recognizeDistance)={}, fd=0.00, speed={}, id={}, pos=({},{},{})",
                target.getRecognizeDistance(), target.getSpeed(), target.getId(),
                target.getPosX(), target.getPosY(), target.getYaw());
        if (debugger != null) {
            debugger.log(String.format("  RECOGNIZE_ENTRY_ONLY params: action=\"upFork\", td=%.4f, fd=0.00, speed=%.2f, id=%d",
                    target.getRecognizeDistance(), target.getSpeed(), target.getId()));
        }
        boolean success = commandClient.sendJackModeNav(
                target.getPosX(), target.getPosY(), target.getYaw(),
                target.getSpeed(), "upFork", 0.00f, target.getRecognizeDistance(), target.getId());

        if (debugger != null) {
            debugger.log(String.format("RECOGNIZE_ENTRY_ONLY %s for command %d",
                    success ? "SUCCESS" : "FAILED", command.getCommandId()));
        }

        LOG.info("sendRecognizeEntryOnlyCommand result for Command ID {}: {}", command.getCommandId(), success);
        return success ? 1 : 0;
    }

    private int sendRecognizeUnloadCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendRecognizeUnloadCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendRecognizeUnloadCommand - Command ID: {}", command.getCommandId());
        if (command.getTarget() == null) {
            LOG.error("sendRecognizeUnloadCommand - Target is null for Command ID: {}", command.getCommandId());
            return 0;
        }

        Position target = command.getTarget();
        boolean success = commandClient.sendJackModeNav(
                target.getPosX(), target.getPosY(), target.getYaw(),
                target.getSpeed(), "downFork", 0.50f, 0.00f, 0);

        if (debugger != null) {
            debugger.log(String.format("RECOGNIZE_UNLOAD %s for command %d",
                    success ? "SUCCESS" : "FAILED", command.getCommandId()));
        }

        LOG.info("sendRecognizeUnloadCommand result for Command ID {}: {}", command.getCommandId(), success);
        return success ? 1 : 0;
    }

    private int sendNonRecognizeLoadCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendNonRecognizeLoadCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendNonRecognizeLoadCommand - Command ID: {}", command.getCommandId());
        if (command.getTarget() == null) {
            LOG.error("sendNonRecognizeLoadCommand - Target is null for Command ID: {}", command.getCommandId());
            return 0;
        }

        Position target = command.getTarget();
        boolean success = commandClient.navigateToEventPosition(
                target.getPosX(), target.getPosY(), target.getYaw(),
                target.getSpeed(), target.getId(), "upFork");

        if (debugger != null) {
            debugger.log(String.format("NON_RECOGNIZE_LOAD %s for command %d",
                    success ? "SUCCESS" : "FAILED", command.getCommandId()));
        }

        LOG.info("sendNonRecognizeLoadCommand result for Command ID {}: {}", command.getCommandId(), success);
        return success ? 1 : 0;
    }

    private int sendNonRecognizeUnloadCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendNonRecognizeUnloadCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendNonRecognizeUnloadCommand - Command ID: {}", command.getCommandId());
        if (command.getTarget() == null) {
            LOG.error("sendNonRecognizeUnloadCommand - Target is null for Command ID: {}", command.getCommandId());
            return 0;
        }

        Position target = command.getTarget();
        boolean success = commandClient.navigateToEventPosition(
                target.getPosX(), target.getPosY(), target.getYaw(),
                target.getSpeed(), target.getId(), "downFork");

        if (debugger != null) {
            debugger.log(String.format("NON_RECOGNIZE_UNLOAD %s for command %d",
                    success ? "SUCCESS" : "FAILED", command.getCommandId()));
        }

        LOG.info("sendNonRecognizeUnloadCommand result for Command ID {}: {}", command.getCommandId(), success);
        return success ? 1 : 0;
    }

    private int sendExitShelfCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendExitShelfCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendExitShelfCommand - Command ID: {}", command.getCommandId());
        if (command.getTarget() == null) {
            LOG.error("sendExitShelfCommand - Target is null for Command ID: {}", command.getCommandId());
            return 0;
        }

        Position target = command.getTarget();
        boolean success = commandClient.sendJackModeNav(
                target.getPosX(), target.getPosY(), target.getYaw(),
                target.getSpeed(), "", 0.65f, 0.00f, 0);

        if (debugger != null) {
            debugger.log(String.format("EXIT_SHELF %s for command %d",
                    success ? "SUCCESS" : "FAILED", command.getCommandId()));
        }

        LOG.info("sendExitShelfCommand result for Command ID {}: {}", command.getCommandId(), success);
        return success ? 1 : 0;
    }

    private int sendChargeStartCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendChargeStartCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendChargeStartCommand - Command ID: {}", command.getCommandId());

        try {
            commandClient.startChargeAsync(new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("CHARGE_START successful for command {}: {}", command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ CHARGE_START success for command %d", command.getCommandId()));
                    }
                    // Note: The actual success/failure will be tracked via navigation state
                }

                @Override
                public void onError(String error) {
                    LOG.error("CHARGE_START failed for command {}: {}", command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("sendChargeStartCommand",
                                String.format("CHARGE_START failed for command %d: %s", command.getCommandId(), error));
                    }
                    // Mark command as failed in the monitor
                }
            });
            return 1;
        } catch (Exception e) {
            LOG.error("Exception sending CHARGE_START command for command {}: {}", command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendChargeStartCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int sendChargeStopCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendChargeStopCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendChargeStopCommand - Command ID: {}", command.getCommandId());

        if (!commandClient.isConnected()) {
            LOG.error("sendChargeStopCommand - Command WebSocket not connected, cannot send command {}",
                    command.getCommandId());
            return 0;
        }

        try {
            Position target = command.getTarget();
            commandClient.stopChargeAsync(new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("CHARGE_STOP successful for command {}: {}", command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ CHARGE_STOP success for command %d", command.getCommandId()));
                    }
                    sendPrechargeNavigation(command, target);
                }

                @Override
                public void onError(String error) {
                    LOG.error("CHARGE_STOP failed for command {}: {}", command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("sendChargeStopCommand",
                                String.format("CHARGE_STOP failed for command %d: %s", command.getCommandId(), error));
                    }
                    // Still attempt to leave the charger even if stopCharge reports an error.
                    sendPrechargeNavigation(command, target);
                }
            });
            return 1;
        } catch (Exception e) {
            LOG.error("Exception sending CHARGE_STOP command for command {}: {}", command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendChargeStopCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private void sendPrechargeNavigation(CommandMonitor command, Position target) {
        if (target == null || commandClient == null || !commandClient.isConnected()) {
            return;
        }

        float speed = target.getSpeed() > 0 ? (float) target.getSpeed() : 0.7f;
        if (debugger != null) {
            debugger.log(String.format("Sending PRECHARGE navigation for command %d to (%.2f, %.2f, %.2f)",
                    command.getCommandId(), target.getPosX(), target.getPosY(), target.getYaw()));
        }
        try {
            commandClient.navigateToPositionAsync(
                    target.getPosX(), target.getPosY(), target.getYaw(), speed,
                    new CommandWebSocketClient.Callback() {
                        @Override
                        public void onSuccess(String response) {
                            LOG.info("Precharge navigation successful for command {}: {}",
                                    command.getCommandId(), response);
                        }

                        @Override
                        public void onError(String error) {
                            LOG.error("Precharge navigation failed for command {}: {}",
                                    command.getCommandId(), error);
                        }
                    });
        } catch (Exception e) {
            LOG.error("Exception sending precharge navigation for command {}: {}",
                    command.getCommandId(), e.getMessage(), e);
        }
    }

    private int sendElevatorCallCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorCallCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendElevatorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorCheckCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendElevatorDoorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorDoorCheckCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendElevatorArrivalDoorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorArrivalDoorCheckCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendElevatorOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorOpenCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendElevatorCancelOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorCancelOpenCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    /**
     * Send elevator query access command with cloud pre-check
     * First checks the elevator availability by querying the cloud API,
     * then sends the LoRa command if the elevator is available.
     * If cloud is not configured or request fails, proceed with LoRa command.
     */
    private int sendElevatorQueryAccessCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorQueryAccessCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendElevatorQueryAccessCommand - Command ID: {}", command.getCommandId());

        // Step 1: Extract elevator ID from the target position
        Position target = command.getTarget();
        if (target == null) {
            LOG.error("sendElevatorQueryAccessCommand - Target is null for Command ID: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendElevatorQueryAccessCommand",
                        String.format("Target is null for command %d", command.getCommandId()));
            }
            return 0;
        }

        String elevatorId = extractElevatorIdFromTarget(target);
        if (elevatorId == null || elevatorId.isEmpty()) {
            LOG.error("sendElevatorQueryAccessCommand - Could not extract elevator ID from target for Command ID: {}",
                    command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendElevatorQueryAccessCommand",
                        String.format("Could not extract elevator ID from target for command %d", command.getCommandId()));
            }
            return 0;
        }

        if (debugger != null) {
            debugger.log(String.format("🔍 [ELEVATOR_QUERY_ACCESS] Extracted elevator ID: %s from target: %s",
                    elevatorId, target.getName()));
        }

        // Step 2: Query cloud for elevator availability (similar to NavigationElevatorManager)
        if (!isElevatorAvailableFromCloud(elevatorId)) {
            LOG.warn("sendElevatorQueryAccessCommand - Elevator {} is not available (from cloud) for Command ID: {}",
                    elevatorId, command.getCommandId());
            if (debugger != null) {
                debugger.logWarning("sendElevatorQueryAccessCommand",
                        String.format("❌ Elevator %s is NOT available (from cloud) for command %d",
                                elevatorId, command.getCommandId()));
            }
            return 0;
        }

        if (debugger != null) {
            debugger.log(String.format("✅ [ELEVATOR_QUERY_ACCESS] Elevator %s is available (from cloud)", elevatorId));
        }

        // Step 3: Send the actual LoRa command
        if (debugger != null) {
            debugger.log(String.format("📡 [ELEVATOR_QUERY_ACCESS] Sending LoRa command for elevator %s", elevatorId));
        }
        return sendLoraHexCommand(command);
    }

    /**
     * Query the cloud server to check if a specific elevator is available
     * This mirrors the logic in NavigationElevatorManager's getElevatorFromCloud method
     *
     * @param elevatorId The elevator configuration ID to check
     * @return true if the elevator is available, false otherwise
     */
    private boolean isElevatorAvailableFromCloud(String elevatorId) {
        if (debugger != null) {
            debugger.log(String.format("🔍 [CLOUD_ELEVATOR_CHECK] Checking availability for elevator ID: %s", elevatorId));
        }

        try {
            String cloudIp = getCloudIp();
            Integer cloudPort = getCloudPort();

            if (cloudIp == null || cloudIp.isEmpty() || cloudPort == null || cloudPort <= 0) {
                if (debugger != null) {
                    debugger.logWarning("isElevatorAvailableFromCloud", "Cloud configuration not available - proceeding with LoRa command");
                }
                // If cloud is not configured, return true to allow the LoRa command to proceed
                return true;
            }

            // Get current map info
            String currentMap = getCurrentMapName();
            String targetMap = getTargetMapName(elevatorId);

            if (debugger != null) {
                debugger.log(String.format("🔍 [CLOUD_ELEVATOR_CHECK] currentMap: %s, targetMap: %s", currentMap, targetMap));
            }

            // Build the cloud API URL (same as NavigationElevatorManager)
            String url = "http://" + cloudIp + ":" + cloudPort + "/device/elevator/available";

            // Build request body (same as NavigationElevatorManager)
            org.json.JSONObject requestBody = new org.json.JSONObject();
            requestBody.put("deviceId", getRobotId());
            requestBody.put("currentMap", currentMap != null ? currentMap : "");
            requestBody.put("targetMap", targetMap != null ? targetMap : "");

            if (debugger != null) {
                debugger.log(String.format("🔍 [CLOUD_ELEVATOR_CHECK] URL: %s, Request body: %s", url, requestBody.toString()));
            }

            // Use a CountDownLatch to wait for the background thread (same as NavigationElevatorManager)
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final Boolean[] result = new Boolean[1];
            final Exception[] error = new Exception[1];

            // Run network request on background thread
            new Thread(() -> {
                try {
                    result[0] = doCloudAvailabilityRequest(url, requestBody, elevatorId);
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }, "ElevatorCloudCheck").start();

            // Wait for completion with timeout (5 seconds)
            boolean completed = latch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!completed) {
                if (debugger != null) {
                    debugger.logWarning("isElevatorAvailableFromCloud", "Cloud request timed out after 5 seconds");
                }
                // On timeout, return true to allow the LoRa command to proceed
                return true;
            }

            if (error[0] != null) {
                if (debugger != null) {
                    debugger.logError("isElevatorAvailableFromCloud", "Exception: " + error[0].getMessage());
                }
                LOG.error("Error checking elevator availability from cloud: {}", error[0].getMessage(), error[0]);
                // On error, return true to allow the LoRa command to proceed
                return true;
            }

            // result[0] will be true if the elevator is available
            return result[0] != null ? result[0] : true;

        } catch (Exception e) {
            if (debugger != null) {
                debugger.logError("isElevatorAvailableFromCloud", "Exception: " + e.getMessage());
            }
            LOG.error("Error checking elevator availability from cloud: {}", e.getMessage(), e);
            // On exception, return true to allow the LoRa command to proceed
            return true;
        }
    }

    /**
     * Perform the actual cloud HTTP request for elevator availability
     * This mirrors the logic in NavigationElevatorManager's doCloudRequest method
     */
    private Boolean doCloudAvailabilityRequest(String url, org.json.JSONObject requestBody, String elevatorId) throws Exception {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL urlObj = new java.net.URL(url);
            connection = (java.net.HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);

            // Write request body
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = connection.getResponseCode();
            if (debugger != null) {
                debugger.log(String.format("🔍 [CLOUD_ELEVATOR_CHECK] Response code: %d", responseCode));
            }

            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                try (java.io.InputStream is = connection.getInputStream()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    String responseStr = response.toString();
                    if (debugger != null) {
                        debugger.log(String.format("🔍 [CLOUD_ELEVATOR_CHECK] Response: %s", responseStr));
                    }

                    // Parse response (same as NavigationElevatorManager)
                    org.json.JSONObject jsonResponse = new org.json.JSONObject(responseStr);

                    // Check response code
                    int code = jsonResponse.optInt("code", -1);
                    if (code != 200) {
                        String msg = jsonResponse.optString("msg", "Unknown error");
                        if (debugger != null) {
                            debugger.logWarning("doCloudAvailabilityRequest", "Cloud returned error code: " + code + ", msg: " + msg);
                        }
                        return true; // On error, allow LoRa command to proceed
                    }

                    // Extract data object
                    org.json.JSONObject data = jsonResponse.optJSONObject("data");
                    if (data == null) {
                        if (debugger != null) {
                            debugger.logWarning("doCloudAvailabilityRequest", "No data object in cloud response");
                        }
                        return true; // On error, allow LoRa command to proceed
                    }

                    // Get elevatorId from data
                    String cloudElevatorId = data.optString("elevatorId", null);
                    boolean available = data.optBoolean("available", false);

                    if (debugger != null) {
                        debugger.logInfo(String.format("✅ [CLOUD_ELEVATOR_CHECK] Received elevator: %s, available: %b",
                                cloudElevatorId, available));
                    }

                    // Check if the elevator ID matches and is available
                    if (cloudElevatorId != null && cloudElevatorId.equals(elevatorId) && available) {
                        return true;
                    } else {
                        if (debugger != null) {
                            debugger.logWarning("doCloudAvailabilityRequest",
                                    String.format("Elevator mismatch or not available - expected: %s, got: %s, available: %b",
                                            elevatorId, cloudElevatorId, available));
                        }
                        return false;
                    }
                }
            } else {
                // Read error response
                try (java.io.InputStream es = connection.getErrorStream()) {
                    if (es != null) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(es, java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder error = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            error.append(line);
                        }
                        if (debugger != null) {
                            debugger.logWarning("doCloudAvailabilityRequest", "Error response: " + error.toString());
                        }
                    }
                }
                if (debugger != null) {
                    debugger.logWarning("doCloudAvailabilityRequest", "Cloud request failed with code: " + responseCode);
                }
                return true; // On error, allow LoRa command to proceed
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Get current map name for cloud request
     */
    private String getCurrentMapName() {
        try {
            if (sharedViewModel == null) {
                sharedViewModel = MyApplication.getInstance().getSharedViewModel();
            }
            if (sharedViewModel == null) {
                return null;
            }

            // Get map info from SharedViewModel or MapViewModel
            MapViewModel mapViewModel = MyApplication.getInstance().getMapViewModel();
            if (mapViewModel == null) {
                return null;
            }

            String currentMap = mapViewModel.getCurrentMap();
            String buildingId = mapViewModel.getCurrentBuilding();
            Integer floor = mapViewModel.getCurrentFloor();

            if (currentMap == null || buildingId == null || floor == null) {
                return null;
            }

            return MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floor);
        } catch (Exception e) {
            if (debugger != null) {
                debugger.logWarning("getCurrentMapName", "Error: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Get target map name for cloud request based on elevator ID
     */
    private String getTargetMapName(String elevatorId) {
        try {
            // Get elevator config to find its building and floors
            if (elevatorViewModel == null) {
                elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
            }
            if (elevatorViewModel == null) {
                return null;
            }

            List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
            if (configs == null || configs.isEmpty()) {
                return null;
            }

            ElevatorViewModel.ElevatorConfig matchingConfig = null;
            for (ElevatorViewModel.ElevatorConfig config : configs) {
                if (config.getId().equals(elevatorId)) {
                    matchingConfig = config;
                    break;
                }
            }

            if (matchingConfig == null) {
                return null;
            }

            // Get the first floor from the config (or use a default)
            Integer targetFloor = null;
            if (matchingConfig.getFloors() != null && !matchingConfig.getFloors().isEmpty()) {
                targetFloor = matchingConfig.getFloors().get(0).getFloor();
            }

            if (targetFloor == null) {
                return null;
            }

            String currentMap = MyApplication.getInstance().getMapViewModel().getCurrentMap();
            String buildingId = matchingConfig.getBuildingId();

            if (currentMap == null || buildingId == null) {
                return null;
            }

            return MultiBuildingMapPoints.generateMapName(currentMap, buildingId, targetFloor);
        } catch (Exception e) {
            if (debugger != null) {
                debugger.logWarning("getTargetMapName", "Error: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extract elevator ID from a position name
     * Position names follow pattern: elevator_wait_floor_elevatorId or elevator_ride_floor_elevatorId etc.
     *
     * @param position The position to check
     * @return The elevator ID if found, null otherwise
     */
    private String extractElevatorIdFromTarget(Position position) {
        if (position == null || position.getName() == null) {
            return null;
        }

        String name = position.getName();
        // Match elevator point patterns: elevator_wait_2_1, elevator_ride_3_2, elevator_preride_1_1, elevator_transition_2_1
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^elevator_(?:wait|ride|preride|transition)_\\d+_(\\d+)$");
        java.util.regex.Matcher matcher = pattern.matcher(name);

        if (matcher.matches()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Helper method to get cloud IP from BasicViewModel
     */
    private String getCloudIp() {
        try {
            BasicViewModel basicViewModel = MyApplication.getInstance().getBasicViewModel();
            if (basicViewModel == null) {
                return null;
            }
            return basicViewModel.getCloudIp().getValue();
        } catch (Exception e) {
            if (debugger != null) {
                debugger.logWarning("getCloudIp", "Error getting cloud IP: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Helper method to get cloud port from BasicViewModel
     */
    private Integer getCloudPort() {
        try {
            BasicViewModel basicViewModel = MyApplication.getInstance().getBasicViewModel();
            if (basicViewModel == null) {
                return null;
            }
            return basicViewModel.getCloudPort().getValue();
        } catch (Exception e) {
            if (debugger != null) {
                debugger.logWarning("getCloudPort", "Error getting cloud port: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Helper method to get robot ID
     */
    private int getRobotId() {
        try {
            BasicViewModel basicViewModel = MyApplication.getInstance().getBasicViewModel();
            if (basicViewModel == null) {
                return 1;
            }
            Integer robotId = basicViewModel.getRobotId().getValue();
            return robotId != null ? robotId : 1;
        } catch (Exception e) {
            if (debugger != null) {
                debugger.logWarning("getRobotId", "Error getting robot ID: " + e.getMessage());
            }
            return 1;
        }
    }

    private int sendElevatorClaimAccessCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorClaimAccessCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendElevatorReleaseAccessCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendElevatorReleaseAccessCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendAutoDoorOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendAutoDoorOpenCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendAutoDoorCancelOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendAutoDoorCancelOpenCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    private int sendAutoDoorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendAutoDoorCheckCommand: Command ID=%d", command.getCommandId()));
        }
        return sendLoraHexCommand(command);
    }

    /**
     * Asynchronous version that returns immediately and relies on
     * command status updates via the monitor system
     */
    private int sendChangeMapCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendChangeMapCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendChangeMapCommand - Command ID: {}, OperationParams: {}",
                command.getCommandId(), command.getOperationParams());

        String params = command.getOperationParams();
        if (params == null || params.isEmpty()) {
            LOG.error("No operation params found for change map command: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("sendChangeMapCommand",
                        String.format("No operation params for command %d", command.getCommandId()));
            }
            return 0;
        }

        try {
            LOG.debug("Attempting to parse JSON for change map command: {}", params);

            if (debugger != null) {
                debugger.log(String.format("Parsing CHANGE_MAP params: %s",
                        params.length() > 100 ? params.substring(0, 100) + "..." : params));
            }

            // Parse the JSON parameters
            JsonObject jsonParams = JsonParser.parseString(params).getAsJsonObject();
            LOG.debug("Successfully parsed JSON for change map command");

            if (!jsonParams.has("hexParams")) {
                LOG.error("No hexParams found in change map command. JSON keys: {}", jsonParams.keySet());
                if (debugger != null) {
                    debugger.logError("sendChangeMapCommand",
                            String.format("No hexParams in JSON for command %d", command.getCommandId()));
                }
                return 0;
            }

            JsonObject hexParams = jsonParams.getAsJsonObject("hexParams");

            if (!hexParams.has("mapName") || !hexParams.has("x") ||
                    !hexParams.has("y") || !hexParams.has("yaw")) {
                LOG.error("Missing required parameters for map switch. Need: mapName, x, y, yaw. Present keys: {}", hexParams.keySet());
                if (debugger != null) {
                    debugger.logError("sendChangeMapCommand",
                            String.format("Missing required params for command %d", command.getCommandId()));
                }
                return 0;
            }

            String mapName = hexParams.get("mapName").getAsString();
            double x = hexParams.get("x").getAsDouble();
            double y = hexParams.get("y").getAsDouble();
            double yaw = hexParams.get("yaw").getAsDouble();

            if (debugger != null) {
                debugger.log(String.format("Sending CHANGE_MAP to map='%s', pos=(%.2f,%.2f,%.2f)",
                        mapName, x, y, yaw));
            }

            LOG.info("Sending async map switch command: map={}, x={}, y={}, yaw={}", mapName, x, y, yaw);

            commandClient.mapSwitchAsync(mapName, x, y, yaw, new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("Map switch successful for command {}: {}", command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ CHANGE_MAP success for command %d: %s",
                                command.getCommandId(), response));
                    }
                }

                @Override
                public void onError(String error) {
                    LOG.error("Map switch failed for command {}: {}", command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("sendChangeMapCommand",
                                String.format("CHANGE_MAP failed for command %d: %s",
                                        command.getCommandId(), error));
                    }
                }
            });

            return 1;

        } catch (JsonSyntaxException e) {
            LOG.error("JSON Syntax error parsing change map command params for command {}: {}",
                    command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendChangeMapCommand",
                        String.format("JSON syntax error: %s", e.getMessage()));
            }
            return 0;
        } catch (IllegalStateException e) {
            LOG.error("Illegal state error parsing change map command params for command {}: {}",
                    command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendChangeMapCommand",
                        String.format("Illegal state: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Error sending change map command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendChangeMapCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int sendRelocalizeCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendRelocalizeCommand: Command ID=%d", command.getCommandId()));
        }
        LOG.debug("sendRelocalizeCommand - Command ID: {}", command.getCommandId());
        return 1;
    }

    /**
     * Parse Lora command parameters from operationParams JSON and send the hex command
     * For write commands: verify response matches expected response
     * For read commands: verify response is not null
     */
    private int sendLoraHexCommand(CommandMonitor command) {
        return sendLoraHexCommand(command, -1);
    }

    private int sendLoraHexCommand(CommandMonitor command, int responseTimeoutMs) {
        long startTime = System.currentTimeMillis();

        if (debugger != null) {
            debugger.log(String.format("sendLoraHexCommand: Command ID=%d, Type=%s",
                    command.getCommandId(), command.getCommandType()));
        }

        LOG.debug("sendLoraHexCommand - Command ID: {}, Type: {}, OperationParams: {}",
                command.getCommandId(), command.getCommandType(), command.getOperationParams());

        String params = command.getOperationParams();
        if (params == null || params.isEmpty()) {
            LOG.error("No operation params found for command: {} - OperationParams is null or empty", command.getCommandId());
            if (debugger != null) {
                debugger.logWarning("sendLoraHexCommand",
                        String.format("No operation params for command %d", command.getCommandId()));
            }
            return 0;
        }

        try {
            LOG.debug("Attempting to parse JSON for command {}: [{}]", command.getCommandId(), params);

            if (debugger != null) {
                debugger.log(String.format("Parsing Lora command JSON for command %d", command.getCommandId()));
            }

            // Parse the JSON parameters
            JsonObject jsonParams = JsonParser.parseString(params).getAsJsonObject();
            LOG.debug("Successfully parsed JSON for command {} - JSON keys: {}", command.getCommandId(), jsonParams.keySet());

            // Extract device command
            String deviceCommand = null;
            if (jsonParams.has("deviceCommand")) {
                deviceCommand = jsonParams.get("deviceCommand").getAsString();
                LOG.debug("Found deviceCommand in JSON for command {}: {}", command.getCommandId(), deviceCommand);
            } else if (jsonParams.has("hexParams")) {
                LOG.error("deviceCommand not found in params for command {}, but hexParams present. JSON content: {}",
                        command.getCommandId(), params);
                if (debugger != null) {
                    debugger.logWarning("sendLoraHexCommand",
                            String.format("deviceCommand missing for command %d", command.getCommandId()));
                }
                return 0;
            } else {
                LOG.error("Neither deviceCommand nor hexParams found in JSON for command {}. JSON keys: {}",
                        command.getCommandId(), jsonParams.keySet());
                if (debugger != null) {
                    debugger.logWarning("sendLoraHexCommand",
                            String.format("Invalid JSON structure for command %d", command.getCommandId()));
                }
                return 0;
            }

            if (deviceCommand == null || deviceCommand.isEmpty()) {
                LOG.error("deviceCommand is null or empty for command {} after parsing", command.getCommandId());
                if (debugger != null) {
                    debugger.logWarning("sendLoraHexCommand",
                            String.format("Empty deviceCommand for command %d", command.getCommandId()));
                }
                return 0;
            }

            LOG.info("Sending Lora hex command: {} for command ID: {}", deviceCommand, command.getCommandId());

            if (debugger != null) {
                debugger.log(String.format("Lora command: %s", deviceCommand));
            }

            // Determine if this is a read command or write command based on type
            boolean isReadCommand = isReadCommandType(command.getCommandType());

            // Log command attempt
            if (debugger != null) {
                debugger.log(String.format(
                        "📡 [LORA_SEND] Command[%d]: type=%s, isRead=%b, command=%s",
                        command.getCommandId(), command.getCommandType(), isReadCommand,
                        deviceCommand.length() > 50 ? deviceCommand.substring(0, 50) + "..." : deviceCommand
                ));
            }

            // Send via Lora communicator
            String response = responseTimeoutMs > 0
                    ? loraCommunicator.sendMessage(deviceCommand, true, responseTimeoutMs)
                    : loraCommunicator.sendMessage(deviceCommand, true);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (debugger != null) {
                debugger.log(String.format("Lora response received in %d ms: %s", elapsedMs,
                        response != null ? response : "null"));
            }

            if (response != null) {
                LOG.info("Received response: {} for command ID: {}", response, command.getCommandId());

                if (isReadCommand) {
                    // For read commands, any non-null response is considered success
                    LOG.info("Read command successful for command ID: {}", command.getCommandId());

                    if (debugger != null) {
                        debugger.log(String.format(
                                "✅ [LORA_SUCCESS] Command[%d]: read command succeeded in %dms, response=%s",
                                command.getCommandId(), elapsedMs, response
                        ));
                    }
                    return 1;
                } else {
                    // For write commands, verify response matches expected
                    String truncatedCommand = deviceCommand.replaceAll("\\s", "").substring(6);
                    if (response.equals(truncatedCommand)) {
                        LOG.info("Write command response matches expected: {}", truncatedCommand);

                        if (debugger != null) {
                            debugger.log(String.format(
                                    "✅ [LORA_SUCCESS] Command[%d]: write command succeeded in %dms",
                                    command.getCommandId(), elapsedMs
                            ));
                        }
                        return 1;
                    } else {
                        LOG.warn("Write command response does not match expected. Got: {}, Expected: {}",
                                response, truncatedCommand);

                        if (debugger != null) {
                            debugger.logWarning("sendLoraHexCommand",
                                    String.format("Command[%d]: expected=%s, got=%s",
                                            command.getCommandId(), truncatedCommand, response));
                        }
                        return 0;
                    }
                }
            } else {
                LOG.error("No response received for command ID: {}", command.getCommandId());

                if (debugger != null) {
                    debugger.logWarning("sendLoraHexCommand",
                            String.format("Command[%d]: no response after %dms",
                                    command.getCommandId(), elapsedMs));
                }
                return 0;
            }

        } catch (JsonSyntaxException e) {
            LOG.error("JSON Syntax error parsing Lora command params for command {}: {}",
                    command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendLoraHexCommand",
                        String.format("JSON syntax error: %s", e.getMessage()));
            }
            return 0;
        } catch (IllegalStateException e) {
            LOG.error("Illegal state error parsing Lora command params for command {}: {}",
                    command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendLoraHexCommand",
                        String.format("Illegal state: %s", e.getMessage()));
            }
            return 0;
        } catch (IOException e) {
            LOG.error("IO Error sending Lora command for command {}: {}", command.getCommandId(), e.getMessage());
            if (debugger != null) {
                debugger.logError("sendLoraHexCommand",
                        String.format("IO error: %s", e.getMessage()));
            }
            return 0;
        } catch (TimeoutException e) {
            LOG.error("Timeout sending Lora command for command {}: {}", command.getCommandId(), e.getMessage());
            if (debugger != null) {
                debugger.logWarning("sendLoraHexCommand",
                        String.format("Timeout: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Unexpected error parsing command params for command {}: {}", command.getCommandId(), e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendLoraHexCommand",
                        String.format("Unexpected error: %s", e.getMessage()));
            }
            return 0;
        }
    }

    /**
     * Determine if a command type is a read command (query/check) vs write command
     */
    private boolean isReadCommandType(NavigationOrderType type) {
        switch (type) {
            case OP_ELEVATOR_CHECK:
            case OP_ELEVATOR_DOOR_CHECK:
            case OP_ELEVATOR_QUERY_ACCESS:
            case OP_AUTO_DOOR_CHECK:
            case OP_ELEVATOR_ARRIVAL_DOOR_CHECK:
                return true;
            default:
                return false;
        }
    }

    private Position getCurrentPosition() {
        if (latestStatus != null && latestStatus.data != null) {
            LOG.debug("getCurrentPosition - pos: ({}, {}, {})",
                    latestStatus.data.pos.x,
                    latestStatus.data.pos.y,
                    latestStatus.data.pos.theta);
            if (debugger != null) {
                debugger.log(String.format("Current position: (%.2f, %.2f, %.2f)",
                        latestStatus.data.pos.x,
                        latestStatus.data.pos.y,
                        latestStatus.data.pos.theta));
            }
            return new Position(1, "current",
                    latestStatus.data.pos.x,
                    latestStatus.data.pos.y,
                    latestStatus.data.pos.theta);
        }
        LOG.warn("getCurrentPosition - latestStatus or its data is null");
        if (debugger != null) {
            debugger.logWarning("getCurrentPosition", "latestStatus or its data is null");
        }
        return null;
    }

    /**
     * Send robot navigation cancel command (OP_CANCEL)
     */
    int sendCancelCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendCancelCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendCancelCommand - Command ID: {}", command.getCommandId());
        try {
            LOG.info("Sending cancel command to robot");

            if (debugger != null) {
                debugger.log("Sending CANCEL command to robot");
            }

            // Use the command client to cancel the current task
            boolean success = commandClient.cancelTask();

            if (success) {
                LOG.info("Robot cancel command sent successfully");
                if (debugger != null) {
                    debugger.log("✅ CANCEL command sent successfully");
                }

                // Also set soft scram to false to ensure robot stops
                commandClient.setSoftScram(false);
                if (debugger != null) {
                    debugger.log("Soft scram set to false");
                }

                return 1;
            } else {
                LOG.error("Failed to send robot cancel command");
                if (debugger != null) {
                    debugger.logError("sendCancelCommand", "Failed to send CANCEL command");
                }
                return 0;
            }

        } catch (Exception e) {
            LOG.error("Exception sending robot cancel command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendCancelCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    int sendForwardCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendForwardCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendForwardCommand - Command ID: {}", command.getCommandId());
        try {
            LOG.info("Sending forward command to robot");

            if (debugger != null) {
                debugger.log("Sending Forward command to robot");
            }

            Position currentPos = getCurrentPosition();
            Position operationPos = command.getTarget();
            if (currentPos == null || operationPos == null) {
                LOG.error("sendForwardCommand - currentPos or operationPos is null for Command ID: {}", command.getCommandId());
                if (debugger != null) {
                    debugger.logError("sendForwardCommand",
                            String.format("currentPos or operationPos is null for command %d", command.getCommandId()));
                }
                return 0;
            }

            if (debugger != null) {
                debugger.log(String.format("Forward from (%.2f, %.2f, %.2f) to (%.2f, %.2f, %.2f) speed=%.2f",
                        currentPos.getPosX(), currentPos.getPosY(), currentPos.getYaw(),
                        operationPos.getPosX(), operationPos.getPosY(), operationPos.getYaw(), operationPos.getSpeed()));
            }

            commandClient.fixedPathNavigateAsync(
                currentPos.getPosX(),
                currentPos.getPosY(),
                currentPos.getYaw(),
                operationPos.getPosX(),
                operationPos.getPosY(),
                operationPos.getYaw(),
                operationPos.getSpeed(),
                new CommandWebSocketClient.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        LOG.info("BACKWARD successful for command {}: {}",
                                command.getCommandId(), response);
                        if (debugger != null) {
                            debugger.log(String.format("✅ BACKWARD success for command %d - completed all waypoints",
                                    command.getCommandId()));
                        }
                    }

                    @Override
                    public void onError(String error) {
                        LOG.error("BACKWARD (multi-waypoint) failed for command {}: {}",
                                command.getCommandId(), error);
                        if (debugger != null) {
                            debugger.logError("sendBackwardCommand",
                                    String.format("BACKWARD failed for command %d: %s",
                                            command.getCommandId(), error));
                        }
                    }
                });
            return 1;
        } catch (Exception e) {
            LOG.error("Exception sending robot forward command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendForwardCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    int sendBackwardCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("sendBackwardCommand: Command ID=%d", command.getCommandId()));
        }

        LOG.debug("sendBackwardCommand - Command ID: {}", command.getCommandId());
        try {
            LOG.info("Sending backward command to robot");

            if (debugger != null) {
                debugger.log("Sending Backward command to robot");
            }

            Position currentPos = getCurrentPosition();
            Position operationPos = command.getTarget();
            if (currentPos == null || operationPos == null) {
                LOG.error("sendBackwardCommand - currentPos or operationPos is null for Command ID: {}", command.getCommandId());
                if (debugger != null) {
                    debugger.logError("sendBackwardCommand",
                            String.format("currentPos or operationPos is null for command %d", command.getCommandId()));
                }
                return 0;
            }

            if (debugger != null) {
                debugger.log(String.format("Backward from (%.2f, %.2f, %.2f) to (%.2f, %.2f, %.2f) speed=%.2f",
                        currentPos.getPosX(), currentPos.getPosY(), currentPos.getYaw(),
                        operationPos.getPosX(), operationPos.getPosY(), operationPos.getYaw(), operationPos.getSpeed()));
            }

            commandClient.fixedPathNavigateAsync(
                    currentPos.getPosX(),
                    currentPos.getPosY(),
                    currentPos.getYaw(),
                    operationPos.getPosX(),
                    operationPos.getPosY(),
                    operationPos.getYaw(),
                    operationPos.getSpeed(),
                    new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("BACKWARD successful for command {}: {}",
                            command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ BACKWARD success for command %d - completed all waypoints",
                                command.getCommandId()));
                    }
                }

                @Override
                public void onError(String error) {
                    LOG.error("BACKWARD (multi-waypoint) failed for command {}: {}",
                            command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("sendBackwardCommand",
                                String.format("BACKWARD failed for command %d: %s",
                                        command.getCommandId(), error));
                    }
                }
            });
            return 1;
        } catch (Exception e) {
            LOG.error("Exception sending robot backward command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("sendBackwardCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }
}
