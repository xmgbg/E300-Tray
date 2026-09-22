package com.ezhan.amr.navigation.task;

import android.os.Handler;
import android.os.Looper;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.ConfirmationStateManager;
import com.ezhan.amr.navigation.LoraCommand;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigation Exception Manager
 * Handles exceptions like timeout, emergency state, and lock state
 * Similar to HandleExceptionManager but for a single robot
 */
public class NavigationExceptionManager extends AbstractNavigationManager {
    private static final Logger LOG = LoggerFactory.getLogger(NavigationExceptionManager.class);
    private static final long ELEVATOR_CALL_RETRY_DELAY_MS = 15000L;
    private static NavigationExceptionManager instance;
    private static final Gson gson = new Gson();

    // Dependencies
    private ElevatorViewModel elevatorViewModel;
    private MapViewModel mapViewModel;
    private SharedViewModel sharedViewModel;
    private final CommandSender commandSender = new CommandSender();
    private final CommandChecker commandChecker = new CommandChecker();
    private Handler mainHandler;
    private ConfirmationStateManager confirmationManager;
    private NavigationStateDebugger stateDebugger;

    // Single robot state tracking (since we only manage one robot)
    private boolean robotEmergencyState = false;
    private int robotCurrentLockState = 0;
    private RecoverySequence currentRecoverySequence = null;
    private CommandMonitor suspendedCommand = null;
    private long recoveryRouteId = -1;
    private CommandMonitor lockedCancelledCommand = null;

    // Timeout configurations (in milliseconds)
    private static final long ELEVATOR_MOVE_TIMEOUT = 1 * 60 * 1000; // 60 seconds
    private static final long RECOVERY_STEP_TIMEOUT = 5 * 60 * 1000; // 5 minutes per step

    // Statistics tracking for diagnostics
    private int totalRecoverySequencesStarted = 0;
    private int totalRecoverySequencesCompleted = 0;
    private int totalRecoverySequencesFailed = 0;

    private NavigationExceptionManager() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.confirmationManager = ConfirmationStateManager.getInstance();
        this.stateDebugger = NavigationStateDebugger.getInstance();
    }

    public static NavigationExceptionManager getInstance() {
        if (instance == null) {
            instance = new NavigationExceptionManager();
        }
        return instance;
    }

    @Override
    public void initManager() {
        this.elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        this.mapViewModel = MyApplication.getInstance().getMapViewModel();
        this.sharedViewModel = MyApplication.getInstance().getSharedViewModel();

        // Reset state
        robotEmergencyState = false;
        robotCurrentLockState = 0;
        currentRecoverySequence = null;
        lockedCancelledCommand = null;

        // Reset statistics
        totalRecoverySequencesStarted = 0;
        totalRecoverySequencesCompleted = 0;
        totalRecoverySequencesFailed = 0;

        LOG.info("NavigationExceptionManager initialized");
        if (stateDebugger != null) {
            stateDebugger.logInfo("========== NAVIGATION EXCEPTION MANAGER INITIALIZED ==========");
            stateDebugger.logInfo(String.format("Configuration: ELEVATOR_MOVE_TIMEOUT=%d ms (%d seconds)",
                    ELEVATOR_MOVE_TIMEOUT, ELEVATOR_MOVE_TIMEOUT / 1000));
            stateDebugger.logInfo(String.format("Configuration: RECOVERY_STEP_TIMEOUT=%d ms (%d minutes)",
                    RECOVERY_STEP_TIMEOUT, RECOVERY_STEP_TIMEOUT / 60000));
            stateDebugger.log("=========================================");
        }
    }

    @Override
    public void processSingleRoute(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("🔍 processSingleRoute called for routeId=%d, recoveryActive=%s",
                    routeId, currentRecoverySequence != null ? "YES" : "NO"));
        }

        // Check for timeouts (but only if not in recovery)
        if (currentRecoverySequence == null) {
            checkTimeoutCommands(routeId);
        }

        // Process recovery sequence (separate from main route)
        processRecoverySequence();
    }

    @Override
    public void updateSingleRouteStatus(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("🔄 updateSingleRouteStatus called for routeId=%d, recoveryActive=%s",
                    routeId, currentRecoverySequence != null ? "YES" : "NO"));
        }
        updateRecoveryStatus();
    }

    /**
     * Check for commands that have timed out
     */
    private void checkTimeoutCommands(long routeId) {
        if (currentRecoverySequence != null) {
            if (stateDebugger != null) {
                stateDebugger.log("⏭️ Already in recovery, skipping timeout check");
            }
            LOG.debug("Already in recovery, skipping timeout check");
            return;
        }

        RouteStore route = RouteStore.getRoute(routeId);
        if (route == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("checkTimeoutCommands", "Route " + routeId + " not found");
            }
            return;
        }

        PositionMonitor currentPosition = getCurrentPositionForRoute(routeId);
        if (currentPosition == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("checkTimeoutCommands", "No current position found for route " + routeId);
            }
            return;
        }

        List<CommandMonitor> commands = getCommandsForPosition(currentPosition.getPositionId());

        if (stateDebugger != null) {
            stateDebugger.log(String.format("🔍 Checking %d commands for timeout at position %d (route %d)",
                    commands.size(), currentPosition.getPositionId(), routeId));
        }

        for (CommandMonitor command : commands) {
            if (command.getCommandStatus() != NavigationOrderStatus.ORDER_EXECUTING) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Command %d status=%s (not executing, skipping)",
                            command.getCommandId(), command.getCommandStatus()));
                }
                continue;
            }

            long startTime = command.getStartTime();
            if (startTime == 0) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Command %d startTime=0, skipping timeout check",
                            command.getCommandId()));
                }
                continue;
            }

            long executionTime = System.currentTimeMillis() - startTime;

            if (stateDebugger != null) {
                stateDebugger.log(String.format("  Command %d type=%s, executionTime=%d ms (timeout=%d ms)",
                        command.getCommandId(), command.getCommandType(), executionTime, ELEVATOR_MOVE_TIMEOUT));
            }

            if (executionTime > ELEVATOR_MOVE_TIMEOUT) {
                NavigationOrderType commandType = command.getCommandType();
                if (commandType != null && (commandType.equals(NavigationOrderType.OP_IN_ELEVATOR_MOVE))) {

                    LOG.warn("Command {} timed out after {}ms, starting recovery sequence",
                            command.getCommandId(), executionTime);

                    if (stateDebugger != null) {
                        stateDebugger.logError("checkTimeoutCommands",
                                String.format("🚨 TIMEOUT DETECTED! Command %d timed out after %dms (type=%s, timeout=%dms)",
                                        command.getCommandId(), executionTime, commandType, ELEVATOR_MOVE_TIMEOUT));
                        stateDebugger.logInfo(String.format("⚠️ TIMEOUT ALERT: Command %d (%s) timed out after %dms - starting recovery",
                                command.getCommandId(), commandType, executionTime));
                        stateDebugger.log("=========================================");;
                    }

                    startRecoverySequence(command, RecoveryType.ELEVATOR_IN_TIMEOUT);
                    break;
                } else if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Command %d type=%s not subject to timeout recovery",
                            command.getCommandId(), commandType));
                }
            }
        }
    }

    /**
     * Parse elevator parameters from CommandMonitor's operationParams JSON
     * In hexParams, preridePoint and ridePoint are Position objects
     */
    private ElevatorParams parseElevatorParamsFromCommand(CommandMonitor command) {
        String operationParams = command.getOperationParams();
        if (operationParams == null || operationParams.isEmpty()) {
            LOG.error("No operationParams found for command: {}", command.getCommandId());
            if (stateDebugger != null) {
                stateDebugger.logError("parseElevatorParamsFromCommand",
                        String.format("No operationParams found for command %d", command.getCommandId()));
            }
            return null;
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("📋 Parsing elevator params from command %d", command.getCommandId()));
            stateDebugger.log(String.format("  operationParams length: %d chars", operationParams.length()));
        }

        try {
            JsonObject jsonParams = JsonParser.parseString(operationParams).getAsJsonObject();

            if (!jsonParams.has("hexParams")) {
                LOG.error("No hexParams found in operationParams for command: {}", command.getCommandId());
                if (stateDebugger != null) {
                    stateDebugger.logError("parseElevatorParamsFromCommand",
                            String.format("No hexParams in operationParams for command %d", command.getCommandId()));
                }
                return null;
            }

            JsonObject hexParams = jsonParams.getAsJsonObject("hexParams");
            ElevatorParams params = new ElevatorParams();

            // Parse floor
            if (hexParams.has("floor")) {
                params.currentFloor = hexParams.get("floor").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed floor: %s", params.currentFloor));
                }
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("parseElevatorParamsFromCommand", "No 'floor' field in hexParams");
                }
            }

            // Parse map name
            if (hexParams.has("mapName")) {
                params.currentMap = hexParams.get("mapName").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed mapName: %s", params.currentMap));
                }
            }

            // Parse channel and address for Lora communication
            if (hexParams.has("channel")) {
                params.channel = hexParams.get("channel").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed channel: %s", params.channel));
                }
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("parseElevatorParamsFromCommand", "No 'channel' field in hexParams");
                }
            }

            if (hexParams.has("address")) {
                params.address = hexParams.get("address").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed address: %s", params.address));
                }
            }

            if (hexParams.has("robotChannel")) {
                params.robotChannel = hexParams.get("robotChannel").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed robotChannel: %s", params.robotChannel));
                }
            }

            if (hexParams.has("robotAddress")) {
                params.robotAddress = hexParams.get("robotAddress").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed robotAddress: %s", params.robotAddress));
                }
            }

            if (hexParams.has("robotId")) {
                params.robotId = hexParams.get("robotId").getAsString();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed robotId: %s", params.robotId));
                }
            }

            // Parse preridePoint - this is a Position object in hexParams
            if (command.getPreridePoint() != null) {
                params.preridePoint = command.getPreridePoint();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed preridePoint: id=%d, name='%s', floor=%s, pos=(%.2f, %.2f)",
                            params.preridePoint.getId(), params.preridePoint.getName(),
                            params.preridePoint.getFloor(),
                            params.preridePoint.getPosX(), params.preridePoint.getPosY()));
                }
                LOG.debug("Parsed preridePoint: id={}, name={}, floor={}",
                        params.preridePoint.getId(), params.preridePoint.getName(), params.preridePoint.getFloor());
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("parseElevatorParamsFromCommand",
                            String.format("preridePoint is NULL for command %d", command.getCommandId()));
                }
            }

            // Parse ridePoint - this is a Position object in hexParams
            if (command.getRidePoint() != null) {
                params.ridePoint = command.getRidePoint();
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  ✓ Parsed ridePoint: id=%d, name='%s', floor=%s, pos=(%.2f, %.2f)",
                            params.ridePoint.getId(), params.ridePoint.getName(),
                            params.ridePoint.getFloor(),
                            params.ridePoint.getPosX(), params.ridePoint.getPosY()));
                }
                LOG.debug("Parsed ridePoint: id={}, name={}, floor={}",
                        params.ridePoint.getId(), params.ridePoint.getName(), params.ridePoint.getFloor());
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("parseElevatorParamsFromCommand",
                            String.format("ridePoint is NULL for command %d", command.getCommandId()));
                }
            }

            // Fallback to elevator config if not found
            if ((params.channel == null || params.address == null) && elevatorViewModel != null) {
                ElevatorViewModel.ElevatorConfig config =
                        elevatorViewModel.getElevatorConfigById("1").getValue();
                if (config != null) {
                    params.channel = String.format("%02X", config.getChannel());
                    params.address = String.format("%02X", config.getAddress());
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("  ⚠️ Using fallback elevator config: channel=%s, address=%s",
                                params.channel, params.address));
                    }
                } else {
                    if (stateDebugger != null) {
                        stateDebugger.logWarning("parseElevatorParamsFromCommand",
                                "No elevator config found for fallback");
                    }
                }
            }

            if (stateDebugger != null) {
                stateDebugger.log(String.format("✅ Parameter parsing complete for command %d: floor=%s, channel=%s, address=%s, robotId=%s",
                        command.getCommandId(), params.currentFloor, params.channel, params.address, params.robotId));
            }

            return params;

        } catch (Exception e) {
            LOG.error("Failed to parse elevator parameters: {}", e.getMessage(), e);
            if (stateDebugger != null) {
                stateDebugger.logError("parseElevatorParamsFromCommand",
                        String.format("Exception parsing command %d: %s", command.getCommandId(), e.getMessage()));
            }
            return null;
        }
    }

    /**
     * Create a recovery command using LoraCommand constructor
     * Properly passes Position objects for preridePoint and ridePoint in hexParams
     */
    private CommandMonitor createRecoveryCommandWithLora(RecoverySequence recoverySeq,
                                                         LoraCommand loraCommand,
                                                         int step) {
        CommandMonitor command = CommandMonitor.createCommand(
                loraCommand.type,
                loraCommand,
                recoverySeq.recoveryPositionId,
                recoverySeq.recoveryRouteId,
                step
        );
        command.setCommandStatus(NavigationOrderStatus.ORDER_RAW);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("📝 Created recovery command %d: type=%s, step=%d/%d, routeId=%d",
                    command.getCommandId(), loraCommand.type, step,
                    recoverySeq.recoveryCommands.size() + 1, recoverySeq.originalRouteId));
            if (loraCommand.getTarget() != null) {
                stateDebugger.log(String.format("  Target: name='%s', pos=(%.2f, %.2f)",
                        loraCommand.getTarget().getName(),
                        loraCommand.getTarget().getPosX(), loraCommand.getTarget().getPosY()));
            }
        }
        LOG.debug("Created recovery command {}: type={}, step={}",
                command.getCommandId(), loraCommand.type, step);

        return command;
    }

    /**
     * Create emergency recovery instructions using LoraCommand
     * Passes Position objects for preridePoint and ridePoint in hexParams
     */
    private void createEmergencyRecoveryInstructions(RecoverySequence recoverySeq, ElevatorParams params) {
        LOG.info("Creating emergency recovery instructions");
        if (stateDebugger != null) {
            stateDebugger.logInfo("🚨 Creating EMERGENCY recovery instructions");
            stateDebugger.log("=========================================");;
        }

        // 1. Move back to elevator preride point (OUT_ELEVATOR_MOVE)
        if (params.preridePoint != null) {
            LoraCommand moveToPrerideLora = new LoraCommand.Builder(
                    NavigationOrderType.OP_OUT_ELEVATOR_MOVE,
                    params.preridePoint.getName())
                    .withHexParam("channel", params.channel)
                    .withHexParam("address", params.address)
                    .withHexParam("floor", params.currentFloor)
                    .withTarget(params.preridePoint)
                    .withPreridePoint(params.preridePoint)
                    .withRidePoint(params.ridePoint)
                    .withDescription("Move back to elevator preride point")
                    .withPlayMusic(false)
                    .build();

            CommandMonitor moveToPreride = createRecoveryCommandWithLora(recoverySeq, moveToPrerideLora, 1);
            moveToPreride.setExceptionRecovery(true);
            recoverySeq.recoveryCommands.add(moveToPreride);
            LOG.info("Added move to preride point: {}", params.preridePoint.getName());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("  Step 1: OP_OUT_ELEVATOR_MOVE to preride point '%s'",
                        params.preridePoint.getName()));
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logError("createEmergencyRecoveryInstructions",
                        "❌ CRITICAL: preridePoint is null - cannot add move to preride point command!");
            }
        }

        // 2. Call elevator
        LoraCommand callElevatorLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_CALL,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withHexParam("floor", params.currentFloor)
                .withRetries(15, ELEVATOR_CALL_RETRY_DELAY_MS)
                .withDescription("Call elevator to floor " + params.currentFloor)
                .withPlayMusic(false)
                .build();

        CommandMonitor callElevator = createRecoveryCommandWithLora(recoverySeq, callElevatorLora, 2);
        recoverySeq.recoveryCommands.add(callElevator);
        LOG.info("Added elevator call command for floor: {}", params.currentFloor);
        if (stateDebugger != null) {
            stateDebugger.log(String.format("  Step 2: OP_ELEVATOR_CALL to floor '%s'", params.currentFloor));
        }

        // 3. Check elevator status
        LoraCommand checkElevatorLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_CHECK,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withHexParam("floor", params.currentFloor)
                .withRetries(30, 2000)
                .withDescription("Check elevator arrival at floor " + params.currentFloor)
                .withPlayMusic(false)
                .build();

        CommandMonitor checkElevator = createRecoveryCommandWithLora(recoverySeq, checkElevatorLora, 3);
        recoverySeq.recoveryCommands.add(checkElevator);
        LOG.info("Added elevator check command for floor: {}", params.currentFloor);
        if (stateDebugger != null) {
            stateDebugger.log(String.format("  Step 3: OP_ELEVATOR_CHECK for floor '%s'", params.currentFloor));
        }

        // 4. Open elevator
        LoraCommand openElevatorLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_OPEN,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withDescription("Open elevator door")
                .withPlayMusic(false)
                .build();

        CommandMonitor openElevator = createRecoveryCommandWithLora(recoverySeq, openElevatorLora, 4);
        recoverySeq.recoveryCommands.add(openElevator);
        LOG.info("Added elevator open command");
        if (stateDebugger != null) {
            stateDebugger.log("  Step 4: OP_ELEVATOR_OPEN");
        }

        // 5. Move into elevator ride point
        if (params.ridePoint != null) {
            LoraCommand moveToRideLora = new LoraCommand.Builder(
                    NavigationOrderType.OP_IN_ELEVATOR_MOVE,
                    params.ridePoint.getName())
                    .withHexParam("channel", params.channel)
                    .withHexParam("address", params.address)
                    .withHexParam("floor", params.currentFloor)
                    .withTarget(params.ridePoint)
                    .withPreridePoint(params.preridePoint)
                    .withRidePoint(params.ridePoint)
                    .withDescription("Move inside elevator to ride point")
                    .withPlayMusic(false)
                    .build();

            CommandMonitor moveToRide = createRecoveryCommandWithLora(recoverySeq, moveToRideLora, 5);
            recoverySeq.recoveryCommands.add(moveToRide);
            LOG.info("Added move to ride point: {}", params.ridePoint.getName());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("  Step 5: OP_IN_ELEVATOR_MOVE to ride point '%s'",
                        params.ridePoint.getName()));
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logError("createEmergencyRecoveryInstructions",
                        "❌ CRITICAL: ridePoint is null - cannot add move to ride point command!");
            }
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("✅ Created %d emergency recovery commands",
                    recoverySeq.recoveryCommands.size()));
            stateDebugger.log("=========================================");;
        }
    }

    /**
     * Create timeout recovery instructions
     */
    private void createTimeoutRecoveryInstructions(RecoverySequence recoverySeq, ElevatorParams params) {
        LOG.info("Creating timeout recovery instructions");
        if (stateDebugger != null) {
            stateDebugger.logInfo("⏰ Creating TIMEOUT recovery instructions");
            stateDebugger.log("=========================================");;
            stateDebugger.log(String.format("Recovery parameters: floor=%s, channel=%s, address=%s, robotId=%s",
                    params.currentFloor, params.channel, params.address, params.robotId));
            if (params.preridePoint != null) {
                stateDebugger.log(String.format("  preridePoint: '%s' (id=%d)",
                        params.preridePoint.getName(), params.preridePoint.getId()));
            } else {
                stateDebugger.logWarning("createTimeoutRecoveryInstructions", "  preridePoint is NULL!");
            }
            if (params.ridePoint != null) {
                stateDebugger.log(String.format("  ridePoint: '%s' (id=%d)",
                        params.ridePoint.getName(), params.ridePoint.getId()));
            } else {
                stateDebugger.logWarning("createTimeoutRecoveryInstructions", "  ridePoint is NULL!");
            }
        }

        // 1. Cancel robot
        LoraCommand cancelRobotLora = new LoraCommand.Builder(
                NavigationOrderType.OP_CANCEL,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withDescription("Cancel robot current task")
                .withPlayMusic(false)
                .build();

        CommandMonitor cancelRobot = createRecoveryCommandWithLora(recoverySeq, cancelRobotLora, 1);
        recoverySeq.recoveryCommands.add(cancelRobot);
        LOG.info("Added robot cancel command");
        if (stateDebugger != null) {
            stateDebugger.log("  Step 1: OP_CANCEL (robot stops immediately)");
        }

        // 2. Move to preride point
        if (params.preridePoint != null) {
            LoraCommand moveToPrerideLora = new LoraCommand.Builder(
                    NavigationOrderType.OP_OUT_ELEVATOR_MOVE,
                    params.preridePoint.getName())
                    .withHexParam("channel", params.channel)
                    .withHexParam("address", params.address)
                    .withHexParam("floor", params.currentFloor)
                    .withTarget(params.preridePoint)
                    .withPreridePoint(params.preridePoint)
                    .withRidePoint(params.ridePoint)
                    .withDescription("Move to elevator preride point")
                    .withPlayMusic(false)
                    .build();

            CommandMonitor moveToPreride = createRecoveryCommandWithLora(recoverySeq, moveToPrerideLora, 2);
            moveToPreride.setExceptionRecovery(true);
            recoverySeq.recoveryCommands.add(moveToPreride);
            LOG.info("Added move to preride point: {}", params.preridePoint.getName());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("  Step 2: OP_OUT_ELEVATOR_MOVE to '%s'",
                        params.preridePoint.getName()));
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logError("createTimeoutRecoveryInstructions",
                        "❌ CRITICAL: preridePoint is null - cannot move out of elevator!");
            }
        }

        // 3. Cancel elevator call (function code 7)
        LoraCommand cancelCallLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("floor", params.currentFloor)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withHexParam("functionCode", "07")
                .withDescription("Cancel elevator call")
                .withPlayMusic(false)
                .build();

        CommandMonitor cancelCall = createRecoveryCommandWithLora(recoverySeq, cancelCallLora, 3);
        recoverySeq.recoveryCommands.add(cancelCall);
        LOG.info("Added elevator cancel call command");
        if (stateDebugger != null) {
            stateDebugger.log("  Step 3: Cancel elevator call (function code 7)");
        }

        // 4. Cancel elevator open (function code 2, F2=0 for close door)
        LoraCommand cancelOpenLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("floor", params.currentFloor)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withHexParam("functionCode", "02")
                .withHexParam("doorControl", "00")
                .withDescription("Cancel elevator door open")
                .withPlayMusic(false)
                .build();

        CommandMonitor cancelOpen = createRecoveryCommandWithLora(recoverySeq, cancelOpenLora, 4);
        recoverySeq.recoveryCommands.add(cancelOpen);
        LOG.info("Added elevator cancel open command");
        if (stateDebugger != null) {
            stateDebugger.log("  Step 4: OP_ELEVATOR_CANCEL_OPEN (close doors after reaching preride)");
        }

        // 5. Call elevator
        LoraCommand callElevatorLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_CALL,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("floor", params.currentFloor)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withRetries(15, ELEVATOR_CALL_RETRY_DELAY_MS)
                .withDescription("Call elevator to floor " + params.currentFloor)
                .withPlayMusic(false)
                .build();

        CommandMonitor callElevator = createRecoveryCommandWithLora(recoverySeq, callElevatorLora, 5);
        // Step 5（重新呼梯）需在Step 4（取消电梯开门）后延迟20秒执行，确保门完全关闭
        callElevator.putExtraData("delayBeforeExecuteMs", 20000L);
        recoverySeq.recoveryCommands.add(callElevator);
        LOG.info("Added elevator call command (with 20s delay before execution)");
        if (stateDebugger != null) {
            stateDebugger.log(String.format("  Step 5: OP_ELEVATOR_CALL to floor '%s' (delay 20s after Step 4)", params.currentFloor));
        }

        // 6. Check elevator
        LoraCommand checkElevatorLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("floor", params.currentFloor)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withExpectedResponse("01")
                .withRetries(15, 2000)
                .withDescription("Check elevator arrival and door to floor " + params.currentFloor)
                .withPlayMusic(false)
                .build();

        CommandMonitor checkElevator = createRecoveryCommandWithLora(recoverySeq, checkElevatorLora, 6);
        recoverySeq.recoveryCommands.add(checkElevator);
        LOG.info("Added elevator arrival and door check command");
        if (stateDebugger != null) {
            stateDebugger.log(String.format("  Step 6: OP_ELEVATOR_ARRIVAL_DOOR_CHECK for floor '%s'", params.currentFloor));
        }

//        // 6. Check elevator door
//        LoraCommand checkElevatorDoorLora = new LoraCommand.Builder(
//                NavigationOrderType.OP_ELEVATOR_DOOR_CHECK,
//                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
//                .withHexParam("channel", params.channel)
//                .withHexParam("address", params.address)
//                .withHexParam("floor", params.currentFloor)
//                .withHexParam("robotChannel", params.robotChannel)
//                .withHexParam("robotAddress", params.robotAddress)
//                .withHexParam("robotId", params.robotId)
//                .withExpectedResponse("01")
//                .withRetries(15, 2000)
//                .withDescription("Check elevator door to floor " + params.currentFloor)
//                .withPlayMusic(false)
//                .build();
//
//        CommandMonitor checkElevatorDoor = createRecoveryCommandWithLora(recoverySeq, checkElevatorDoorLora, 7);
//        recoverySeq.recoveryCommands.add(checkElevatorDoor);
//        LOG.info("Added elevator door check command");
//        if (stateDebugger != null) {
//            stateDebugger.log(String.format("  Step 7: OP_ELEVATOR_DOOR_CHECK for floor '%s'", params.currentFloor));
//        }


        // 7. Open elevator
        LoraCommand openElevatorLora = new LoraCommand.Builder(
                NavigationOrderType.OP_ELEVATOR_OPEN,
                params.preridePoint != null ? params.preridePoint.getName() : "elevator")
                .withHexParam("channel", params.channel)
                .withHexParam("address", params.address)
                .withHexParam("floor", params.currentFloor)
                .withHexParam("robotChannel", params.robotChannel)
                .withHexParam("robotAddress", params.robotAddress)
                .withHexParam("robotId", params.robotId)
                .withRetries(15, 2000)
                .withDescription("Open elevator to floor " + params.currentFloor)
                .withPlayMusic(false)
                .build();

        CommandMonitor openElevator = createRecoveryCommandWithLora(recoverySeq, openElevatorLora, 7);
        recoverySeq.recoveryCommands.add(openElevator);
        LOG.info("Added elevator open command");
        if (stateDebugger != null) {
            stateDebugger.log(String.format("  Step 8: OP_ELEVATOR_OPEN at floor '%s'", params.currentFloor));
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("✅ Created %d timeout recovery commands",
                    recoverySeq.recoveryCommands.size()));
            stateDebugger.log("=========================================");;
        }
    }

    /**
     * Start a recovery sequence for a timed-out command
     */
    private void startRecoverySequence(CommandMonitor command, RecoveryType recoveryType) {
        long startTime = System.currentTimeMillis();

        // 检测上次恢复未清理的残留状态，强制清理并标记失败，避免状态冲突
        if (currentRecoverySequence != null) {
            LOG.warn("startRecoverySequence called but previous recovery sequence still active (step {}/{}), force cleaning",
                    currentRecoverySequence.currentStep + 1,
                    currentRecoverySequence.recoveryCommands.size());
            if (stateDebugger != null) {
                stateDebugger.logError("startRecoverySequence",
                        String.format("⚠️ Previous recovery sequence still active (step %d/%d, delayScheduled=%b) - force cleaning",
                                currentRecoverySequence.currentStep + 1,
                                currentRecoverySequence.recoveryCommands.size(),
                                currentRecoverySequence.delayScheduled));
            }
            // 标记上次恢复失败
            totalRecoverySequencesFailed++;
            // 强制清理残留状态
            currentRecoverySequence = null;
        }

        // STEP 1: SUSPEND the original command
        suspendedCommand = command;
        command.setCommandStatus(NavigationOrderStatus.ORDER_SUSPENDED);
        command.setStartTime(0);
        CommandMonitor.updateCommand(command);

        // Suspend position
        PositionMonitor originalPosition = PositionMonitor.getPosition(command.getPositionId());
        if (originalPosition != null && originalPosition.getPositionStatus() == NavigationOrderStatus.ORDER_EXECUTING) {
            originalPosition.setPositionStatus(NavigationOrderStatus.ORDER_SUSPENDED);
            PositionMonitor.updatePosition(originalPosition);
        }

        // Suspend route
        RouteStore originalRoute = RouteStore.getRoute(command.getRouteId());
        if (originalRoute != null && originalRoute.getRouteStatus() == NavigationOrderStatus.ORDER_EXECUTING) {
            originalRoute.setRouteStatus(NavigationOrderStatus.ORDER_SUSPENDED);
            RouteStore.updateRoute(originalRoute);
        }

        totalRecoverySequencesStarted++;

        if (stateDebugger != null) {
            stateDebugger.log("=========================================");
            stateDebugger.logError("startRecoverySequence",
                    String.format("🚨 STARTING RECOVERY SEQUENCE #%d for command %d",
                            totalRecoverySequencesStarted, command.getCommandId()));
            stateDebugger.logInfo("═══ ORIGINAL COMMAND SUSPENDED ═══");
            stateDebugger.log(String.format("  Original Command ID: %d (type=%s) - STATUS: SUSPENDED",
                    command.getCommandId(), command.getCommandType()));
            stateDebugger.log(String.format("  Original Route ID: %d - STATUS: SUSPENDED", command.getRouteId()));
            stateDebugger.log("=========================================");
        }

        ElevatorParams params = parseElevatorParamsFromCommand(command);
        if (params == null) {
            LOG.error("Failed to parse elevator parameters for recovery");
            if (stateDebugger != null) {
                stateDebugger.logError("startRecoverySequence",
                        "❌ FAILED to parse elevator parameters - recovery cannot proceed!");
            }
            resumeOriginalCommandAsFailed();
            totalRecoverySequencesFailed++;
            return;
        }

        // STEP 2: Create NEW recovery route
        RouteStore recoveryRoute = new RouteStore();
        long newRouteId = generateNewRouteId();
        recoveryRoute.setRouteId(newRouteId);
        recoveryRoute.setRouteName("RECOVERY_" + command.getCommandId() + "_" + System.currentTimeMillis());
        recoveryRoute.setRouteStatus(NavigationOrderStatus.ORDER_READY);
        recoveryRoute.setPositionIds(new ArrayList<>());  // Initialize empty list
        RouteStore.addRoute(recoveryRoute);
        recoveryRouteId = newRouteId;

        if (stateDebugger != null) {
            stateDebugger.log(String.format("📋 Created recovery route: ID=%d, Name=%s",
                    newRouteId, recoveryRoute.getRouteName()));
        }

        // STEP 3: Create recovery position
        // Fix: Use correct createPosition signature
        PositionMonitor recoveryPosition = PositionMonitor.createRecoveryPosition(newRouteId, 0);
        recoveryPosition.setPositionStatus(NavigationOrderStatus.ORDER_READY);
        PositionMonitor.updatePosition(recoveryPosition);

        // Add position to route
        recoveryRoute.getPositionIds().add(recoveryPosition.getPositionId());
        RouteStore.updateRoute(recoveryRoute);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("📍 Created recovery position: ID=%d", recoveryPosition.getPositionId()));
        }

        // STEP 4: Create recovery sequence with the new position/route
        RecoverySequence recoverySeq = new RecoverySequence(
                command.getCommandId(),
                command.getPositionId(),      // original position ID
                command.getRouteId(),         // original route ID
                recoveryPosition.getPositionId(),  // NEW: recovery position ID
                newRouteId,                   // NEW: recovery route ID
                recoveryType,
                command.getOperationParams(),
                params.preridePoint,
                params.ridePoint,
                params.currentFloor,
                params.currentMap
        );

        createTimeoutRecoveryInstructions(recoverySeq, params);
        currentRecoverySequence = recoverySeq;

        if (stateDebugger != null) {
            stateDebugger.log(String.format("📋 Recovery sequence created with %d steps for original command %d",
                    recoverySeq.recoveryCommands.size(), command.getCommandId()));
            stateDebugger.logInfo("Starting execution of first recovery step...");
        }

        // Update route status to EXECUTING
        recoveryRoute.setRouteStatus(NavigationOrderStatus.ORDER_EXECUTING);
        RouteStore.updateRoute(recoveryRoute);

        executeNextRecoveryStep();
    }

    /**
     * Generate a new unique route ID
     */
    private long generateNewRouteId() {
        // Simple implementation - use timestamp + random
        // In production, you might want a more robust ID generator
        return System.currentTimeMillis() * 1000 + (long)(Math.random() * 1000);
    }

    /**
     * Process recovery sequence - execute next steps
     */
    private void processRecoverySequence() {
        if (currentRecoverySequence == null) {
            if (stateDebugger != null) {
                // Only log if we were in recovery before - prevents spam
//                if (stateDebugger.getLastDebugMessage() != null &&
//                        stateDebugger.getLastDebugMessage().contains("recovery")) {
//                    // Don't log repeatedly
//                }
            }
            return;
        }

        if (currentRecoverySequence.currentStep < currentRecoverySequence.recoveryCommands.size()) {
            CommandMonitor currentCommand = currentRecoverySequence.recoveryCommands
                    .get(currentRecoverySequence.currentStep);

            if (currentCommand.getCommandStatus() == NavigationOrderStatus.ORDER_RAW) {
                // 延迟等待期间直接返回，不重复调度
                if (currentRecoverySequence.delayScheduled) {
                    return;
                }
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("📌 Processing recovery sequence: step %d/%d, command type=%s, id=%d",
                            currentRecoverySequence.currentStep + 1,
                            currentRecoverySequence.recoveryCommands.size(),
                            currentCommand.getCommandType(),
                            currentCommand.getCommandId()));
                }
                LOG.info("Executing recovery step {}/{}: type={}",
                        currentRecoverySequence.currentStep + 1,
                        currentRecoverySequence.recoveryCommands.size(),
                        currentCommand.getCommandType());
                executeNextRecoveryStep();
            } else if (stateDebugger != null) {
                stateDebugger.log(String.format("⏸️ Recovery command %d status is %s (not RAW), waiting...",
                        currentCommand.getCommandId(), currentCommand.getCommandStatus()));
            }
        }
    }

    /**
     * Execute next recovery step
     * 支持delayBeforeExecuteMs参数，用于延迟执行（如电梯超时恢复Step5需延迟20秒呼梯）
     * 使用delayScheduled标记防止主调度循环在延迟等待期间重复调度
     */
    private void executeNextRecoveryStep() {
        if (currentRecoverySequence == null ||
                currentRecoverySequence.currentStep >= currentRecoverySequence.recoveryCommands.size()) {
            if (stateDebugger != null) {
                if (currentRecoverySequence == null) {
                    stateDebugger.log("⚠️ executeNextRecoveryStep called but currentRecoverySequence is null");
                } else {
                    stateDebugger.log("✅ All recovery steps completed - sequence finished");
                }
            }
            LOG.info("No more recovery steps to execute");
            return;
        }

        // 检查是否已经调度了延迟执行，已调度则直接返回，防止重复调度
        if (currentRecoverySequence.delayScheduled) {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("⏸️ Recovery step %d already scheduled for delayed execution, skipping re-schedule",
                        currentRecoverySequence.currentStep + 1));
            }
            return;
        }

        CommandMonitor command = currentRecoverySequence.recoveryCommands
                .get(currentRecoverySequence.currentStep);

        if (command.getCommandStatus() == NavigationOrderStatus.ORDER_RAW) {
            // 检查是否需要延迟执行
            Object delayObj = command.getExtraData("delayBeforeExecuteMs");
            if (delayObj instanceof Long) {
                long delayMs = (Long) delayObj;
                if (delayMs > 0) {
                    // 设置延迟调度标记，防止主循环重复调用
                    currentRecoverySequence.delayScheduled = true;
                    currentRecoverySequence.delayScheduledStep = currentRecoverySequence.currentStep;

                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("⏳ Recovery step %d/%d delayed by %d ms before execution (command %d type=%s)",
                                currentRecoverySequence.currentStep + 1,
                                currentRecoverySequence.recoveryCommands.size(),
                                delayMs,
                                command.getCommandId(), command.getCommandType()));
                    }
                    LOG.info("Recovery step {}/{} delayed by {} ms",
                            currentRecoverySequence.currentStep + 1,
                            currentRecoverySequence.recoveryCommands.size(),
                            delayMs);
                    // 记录回调所需的校验信息
                    final int scheduledStep = currentRecoverySequence.currentStep;
                    final RecoverySequence seqRef = currentRecoverySequence;
                    mainHandler.postDelayed(() -> {
                        // 三重安全检查：
                        // 1. currentRecoverySequence 仍为同一个对象且不为 null
                        if (currentRecoverySequence == null || currentRecoverySequence != seqRef) {
                            if (stateDebugger != null) {
                                stateDebugger.log("⚠️ Delayed recovery step cancelled - sequence no longer active or changed");
                            }
                            if (seqRef != null) {
                                seqRef.delayScheduled = false;
                            }
                            return;
                        }
                        // 2. currentStep 未变（步骤索引一致）
                        if (currentRecoverySequence.currentStep != scheduledStep) {
                            if (stateDebugger != null) {
                                stateDebugger.log(String.format("⚠️ Delayed recovery step cancelled - step changed from %d to %d",
                                        scheduledStep, currentRecoverySequence.currentStep));
                            }
                            currentRecoverySequence.delayScheduled = false;
                            return;
                        }
                        // 3. 命令状态仍为 ORDER_RAW
                        if (command.getCommandStatus() != NavigationOrderStatus.ORDER_RAW) {
                            if (stateDebugger != null) {
                                stateDebugger.log(String.format("⚠️ Delayed recovery command %d status changed to %s, skipping",
                                        command.getCommandId(), command.getCommandStatus()));
                            }
                            currentRecoverySequence.delayScheduled = false;
                            return;
                        }
                        // 清除标记，真正执行命令
                        currentRecoverySequence.delayScheduled = false;
                        currentRecoverySequence.delayScheduledStep = -1;
                        if (stateDebugger != null) {
                            stateDebugger.log(String.format("🎯 Executing delayed recovery step %d/%d: command %d type=%s",
                                    currentRecoverySequence.currentStep + 1,
                                    currentRecoverySequence.recoveryCommands.size(),
                                    command.getCommandId(), command.getCommandType()));
                        }
                        executeRecoveryCommand(command);
                    }, delayMs);
                    return;
                }
            }
            if (stateDebugger != null) {
                stateDebugger.log(String.format("🎯 Executing next recovery step %d/%d: command %d type=%s",
                        currentRecoverySequence.currentStep + 1,
                        currentRecoverySequence.recoveryCommands.size(),
                        command.getCommandId(), command.getCommandType()));
            }
            LOG.info("Executing next recovery step {}/{}: command {} type={}",
                    currentRecoverySequence.currentStep + 1,
                    currentRecoverySequence.recoveryCommands.size(),
                    command.getCommandId(), command.getCommandType());
            executeRecoveryCommand(command);
        } else {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("⚠️ Recovery command %d already has status %s (expected RAW), skipping execution",
                        command.getCommandId(), command.getCommandStatus()));
            }
        }
    }

    /**
     * Execute a recovery command with retry logic (max 10 attempts)
     */
    private void executeRecoveryCommand(CommandMonitor command) {
        if (commandSender == null) {
            LOG.error("CommandSender is null, cannot execute recovery command");
            if (stateDebugger != null) {
                stateDebugger.logError("executeRecoveryCommand",
                        "❌ CRITICAL: CommandSender is null for command " + command.getCommandId());
            }
            return;
        }

        // Add retry counter to command if not present
        Integer retryCount = (Integer) command.getExtraData("retryCount");
        if (retryCount == null) {
            retryCount = 0;
            command.putExtraData("retryCount", retryCount);
        }

        final int MAX_RETRIES = 20;

        long sendStartTime = System.currentTimeMillis();

        LOG.info("Sending recovery command: ID={}, type={}, attempt={}/{}",
                command.getCommandId(), command.getCommandType(), retryCount + 1, MAX_RETRIES + 1);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("📤 Sending recovery command %d: type=%s, step=%d/%d, attempt=%d/%d",
                    command.getCommandId(), command.getCommandType(),
                    currentRecoverySequence.currentStep + 1,
                    currentRecoverySequence.recoveryCommands.size(),
                    retryCount + 1, MAX_RETRIES + 1));
            stateDebugger.log(String.format("  Route ID: %d, Position ID: %d",
                    command.getRouteId(), command.getPositionId()));
        }

        int sendResult = commandSender.sendCommand(command);
        long sendElapsed = System.currentTimeMillis() - sendStartTime;

        if (sendResult == 1) {
            // Success - clear retry count and proceed
            command.putExtraData("retryCount", 0);
            command.setCommandStatus(NavigationOrderStatus.ORDER_EXECUTING);
            command.setStartTime(System.currentTimeMillis());
            CommandMonitor.updateCommand(command);
            LOG.info("Recovery command {} sent successfully", command.getCommandId());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("✅ Recovery command %d sent successfully in %d ms, status=EXECUTING",
                        command.getCommandId(), sendElapsed));
                stateDebugger.log(String.format("  Start time: %d", command.getStartTime()));
            }
        } else {
            // Failed - check if we should retry
            if (retryCount < MAX_RETRIES) {
                // Retry after a delay
                int nextRetry = retryCount + 1;
                long delayMs = calculateRetryDelay(retryCount); // Progressive delay

                LOG.warn("Failed to send recovery command {} (attempt {}/{}), retrying in {} ms...",
                        command.getCommandId(), retryCount + 1, MAX_RETRIES + 1, delayMs);

                if (stateDebugger != null) {
                    stateDebugger.logWarning("executeRecoveryCommand",
                            String.format("⚠️ Failed to send recovery command %d (attempt %d/%d, elapsed=%d ms), retrying in %d ms",
                                    command.getCommandId(), retryCount + 1, MAX_RETRIES + 1, sendElapsed, delayMs));
                }

                // Increment retry count
                command.putExtraData("retryCount", nextRetry);

                // Schedule retry
                mainHandler.postDelayed(() -> {
                    if (currentRecoverySequence != null &&
                            currentRecoverySequence.currentStep < currentRecoverySequence.recoveryCommands.size() &&
                            currentRecoverySequence.recoveryCommands.get(currentRecoverySequence.currentStep) == command) {
                        executeRecoveryCommand(command);
                    } else {
                        LOG.warn("Recovery context changed, aborting retry for command {}", command.getCommandId());
                        if (stateDebugger != null) {
                            stateDebugger.logWarning("executeRecoveryCommand",
                                    "Recovery context changed, aborting retry");
                        }
                    }
                }, delayMs);

            } else {
                // Max retries exceeded - abort the entire recovery sequence
                LOG.error("Failed to send recovery command {} after {} attempts, aborting recovery",
                        command.getCommandId(), MAX_RETRIES + 1);
                command.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
                CommandMonitor.updateCommand(command);

                if (stateDebugger != null) {
                    stateDebugger.logError("executeRecoveryCommand",
                            String.format("❌ Failed to send recovery command %d after %d attempts (elapsed=%d ms) - ABORTING RECOVERY",
                                    command.getCommandId(), MAX_RETRIES + 1, sendElapsed));
                }

                // Abort the entire recovery sequence on send failure
                if (stateDebugger != null) {
                    stateDebugger.logError("executeRecoveryCommand",
                            "⚠️ Aborting recovery sequence due to send failure after max retries");
                }
                currentRecoverySequence = null;
                totalRecoverySequencesFailed++;
            }
        }
    }

    /**
     * Calculate retry delay with exponential backoff
     * @param retryAttempt Current retry attempt (0-based)
     * @return Delay in milliseconds
     */
    private long calculateRetryDelay(int retryAttempt) {
        // Exponential backoff: 500ms, 1000ms, 2000ms, 4000ms, etc., capped at 10 seconds
        long delay = 500L * (1L << Math.min(retryAttempt, 5)); // Cap exponent at 5 for max 16 seconds
        return Math.min(delay, 10000L); // Cap at 10 seconds
    }

    /**
     * Update recovery sequence status
     */
    private void updateRecoveryStatus() {
        if (currentRecoverySequence == null) return;

        // Check if all steps are complete
        if (currentRecoverySequence.currentStep >= currentRecoverySequence.recoveryCommands.size()) {
            if (stateDebugger != null) {
                long totalRecoveryTime = System.currentTimeMillis() - currentRecoverySequence.recoveryStartTime;
                stateDebugger.logInfo(String.format("🎉 RECOVERY SEQUENCE COMPLETED SUCCESSFULLY! 🎉"));
                stateDebugger.logInfo(String.format("  Total steps: %d", currentRecoverySequence.recoveryCommands.size()));
                stateDebugger.logInfo(String.format("  Total recovery time: %d ms (%d seconds)",
                        totalRecoveryTime, totalRecoveryTime / 1000));
            }
            completeRecoverySequence();
            totalRecoverySequencesCompleted++;
            currentRecoverySequence = null;
            return;
        }

        // Check current step status
        CommandMonitor currentCommand = currentRecoverySequence.recoveryCommands
                .get(currentRecoverySequence.currentStep);

        long stepStartTime = currentCommand.getStartTime();
        long stepElapsed = stepStartTime > 0 ? System.currentTimeMillis() - stepStartTime : 0;

        // Check for step timeout
        if (stepStartTime > 0 && stepElapsed > RECOVERY_STEP_TIMEOUT) {
            if (stateDebugger != null) {
                stateDebugger.logError("updateRecoveryStatus",
                        String.format("❌ Recovery step %d timed out after %d ms - aborting!",
                                currentRecoverySequence.currentStep + 1, stepElapsed));
            }
            // Recovery failed - resume original as FAILED
            resumeOriginalCommandAsFailed();
            currentRecoverySequence = null;
            totalRecoverySequencesFailed++;
            return;
        }

        NavigationOrderStatus cmdStatus = currentCommand.getCommandStatus();

        if (cmdStatus == NavigationOrderStatus.ORDER_EXECUTING) {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("🔍 Checking recovery command %d (step %d/%d, elapsed=%d ms)",
                        currentCommand.getCommandId(),
                        currentRecoverySequence.currentStep + 1,
                        currentRecoverySequence.recoveryCommands.size(),
                        stepElapsed));
            }

            int checkResult = commandChecker.checkCommand(currentCommand);

            if (checkResult == 1) {
                // Step completed successfully
                currentCommand.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
                CommandMonitor.updateCommand(currentCommand);

                long stepCompletionTime = System.currentTimeMillis() - currentCommand.getStartTime();

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ Recovery step %d completed in %d ms",
                            currentRecoverySequence.currentStep + 1, stepCompletionTime));
                }

                currentRecoverySequence.currentStep++;
                executeNextRecoveryStep();

            } else if (checkResult == -1) {
                // Step failed
                if (stateDebugger != null) {
                    stateDebugger.logError("updateRecoveryStatus",
                            String.format("❌ Recovery step %d FAILED - aborting sequence",
                                    currentRecoverySequence.currentStep + 1));
                }
                resumeOriginalCommandAsFailed();
                currentRecoverySequence = null;
                totalRecoverySequencesFailed++;
            }
        } else if (cmdStatus == NavigationOrderStatus.ORDER_COMPLETED) {
            // Already completed, move to next step
            if (stateDebugger != null) {
                stateDebugger.log(String.format("ℹ️ Recovery command %d already completed, moving to next step",
                        currentCommand.getCommandId()));
            }
            currentRecoverySequence.currentStep++;
            executeNextRecoveryStep();
        } else if (cmdStatus == NavigationOrderStatus.ORDER_RAW) {
            // 延迟等待期间直接返回，不重复调度
            if (currentRecoverySequence.delayScheduled) {
                return;
            }
            // Not yet started, execute it (via executeNextRecoveryStep to support delay mechanism)
            if (stateDebugger != null) {
                stateDebugger.log(String.format("🎯 Executing recovery step %d/%d",
                        currentRecoverySequence.currentStep + 1,
                        currentRecoverySequence.recoveryCommands.size()));
            }
            executeNextRecoveryStep();
        }
    }

    /**
     * Complete recovery sequence and resume original suspended command
     */
    private void completeRecoverySequence() {
        if (currentRecoverySequence == null) return;

        LOG.info("Completing recovery sequence for original command: {}",
                currentRecoverySequence.originalCommandId);
        if (stateDebugger != null) {
            stateDebugger.logInfo(String.format("🔄 Completing recovery sequence for original command: %d",
                    currentRecoverySequence.originalCommandId));
            stateDebugger.log("=========================================");
        }

        // Resume the suspended original command
        if (suspendedCommand != null) {
            // Set back to READY so it will be re-executed
            suspendedCommand.setCommandStatus(NavigationOrderStatus.ORDER_READY);
            suspendedCommand.setStartTime(0);
            CommandMonitor.updateCommand(suspendedCommand);

            LOG.info("Original command {} reset to READY for re-execution", suspendedCommand.getCommandId());
            if (stateDebugger != null) {
                stateDebugger.log(String.format("✅ Original command %d resumed from SUSPENDED to READY for re-execution",
                        suspendedCommand.getCommandId()));
            }

            // Also resume the position if it was suspended
            PositionMonitor position = PositionMonitor.getPosition(suspendedCommand.getPositionId());
            if (position != null && position.getPositionStatus() == NavigationOrderStatus.ORDER_SUSPENDED) {
                position.setPositionStatus(NavigationOrderStatus.ORDER_EXECUTING);
                PositionMonitor.updatePosition(position);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("📍 Position %d resumed to EXECUTING", position.getPositionId()));
                }
            }

            // Resume the route
            RouteStore route = RouteStore.getRoute(suspendedCommand.getRouteId());
            if (route != null && route.getRouteStatus() == NavigationOrderStatus.ORDER_SUSPENDED) {
                route.setRouteStatus(NavigationOrderStatus.ORDER_EXECUTING);
                RouteStore.updateRoute(route);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("📋 Route %d resumed to EXECUTING", route.getRouteId()));
                }
            }
        } else {
            LOG.warn("Original command {} not found", currentRecoverySequence.originalCommandId);
            if (stateDebugger != null) {
                stateDebugger.logWarning("completeRecoverySequence",
                        "Original command " + currentRecoverySequence.originalCommandId + " not found");
            }
        }

        // Clean up recovery commands (mark as completed)
        cleanupRecoveryCommands();

        // Clean up recovery route (optional - can also leave for history)
        cleanupRecoveryRoute();

        if (stateDebugger != null) {
            stateDebugger.logInfo("=== RECOVERY SEQUENCE COMPLETE ===");
            stateDebugger.logInfo(String.format("Original command %d ready for re-execution",
                    currentRecoverySequence.originalCommandId));
            stateDebugger.logInfo(String.format("Statistics: Started=%d, Completed=%d, Failed=%d",
                    totalRecoverySequencesStarted, totalRecoverySequencesCompleted, totalRecoverySequencesFailed));
            stateDebugger.log("=========================================");
        }

        // Clear references
        suspendedCommand = null;
        recoveryRouteId = -1;
    }

    /**
     * Resume original command as FAILED (when recovery fails)
     */
    private void resumeOriginalCommandAsFailed() {
        if (suspendedCommand != null) {
            suspendedCommand.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
            CommandMonitor.updateCommand(suspendedCommand);

            if (stateDebugger != null) {
                stateDebugger.logError("resumeOriginalCommandAsFailed",
                        String.format("Original command %d marked as FAILED due to recovery failure",
                                suspendedCommand.getCommandId()));
            }

            // Also mark position and route as failed
            PositionMonitor position = PositionMonitor.getPosition(suspendedCommand.getPositionId());
            if (position != null) {
                position.setPositionStatus(NavigationOrderStatus.ORDER_FAILED);
                PositionMonitor.updatePosition(position);
            }

            RouteStore route = RouteStore.getRoute(suspendedCommand.getRouteId());
            if (route != null) {
                route.setRouteStatus(NavigationOrderStatus.ORDER_FAILED);
                RouteStore.updateRoute(route);
            }

            suspendedCommand = null;
        }
    }

    /**
     * Clean up recovery route after completion
     */
    private void cleanupRecoveryRoute() {
        if (recoveryRouteId != -1) {
            RouteStore recoveryRoute = RouteStore.getRoute(recoveryRouteId);
            if (recoveryRoute != null) {
                // Mark as completed (or delete)
                recoveryRoute.setRouteStatus(NavigationOrderStatus.ORDER_COMPLETED);
                RouteStore.updateRoute(recoveryRoute);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("🧹 Recovery route %d marked as COMPLETED", recoveryRouteId));
                }
            }
            recoveryRouteId = -1;
        }
    }

    /**
     * Clean up recovery commands
     */
    private void cleanupRecoveryCommands() {
        if (currentRecoverySequence == null) return;

        if (stateDebugger != null) {
            stateDebugger.log(String.format("🧹 Cleaning up %d recovery commands",
                    currentRecoverySequence.recoveryCommands.size()));
        }

        for (CommandMonitor cmd : currentRecoverySequence.recoveryCommands) {
            cmd.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
            CommandMonitor.updateCommand(cmd);
        }

        if (stateDebugger != null) {
            stateDebugger.log("✅ Recovery commands cleaned up successfully");
        }
    }

    private PositionMonitor getCurrentPositionForRoute(long routeId) {
        RouteStore route = RouteStore.getRoute(routeId);
        if (route == null) return null;

        for (Long posId : route.getPositionIds()) {
            PositionMonitor pos = PositionMonitor.getPosition(posId);
            if (pos != null && (pos.getPositionStatus() == NavigationOrderStatus.ORDER_EXECUTING ||
                    pos.getPositionStatus() == NavigationOrderStatus.ORDER_READY)) {
                return pos;
            }
        }
        return null;
    }

    public void reset() {
        robotEmergencyState = false;
        robotCurrentLockState = 0;

        if (currentRecoverySequence != null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("reset", "⚠️ Clearing active recovery sequence during reset");
                stateDebugger.log(String.format("  Recovery sequence had %d/%d steps completed",
                        currentRecoverySequence.currentStep,
                        currentRecoverySequence.recoveryCommands.size()));
            }
            currentRecoverySequence = null;
        }
        lockedCancelledCommand = null;

        LOG.info("NavigationExceptionManager reset");
        if (stateDebugger != null) {
            stateDebugger.logInfo("NavigationExceptionManager reset");
            stateDebugger.logInfo(String.format("Final statistics - Recoveries: Started=%d, Completed=%d, Failed=%d",
                    totalRecoverySequencesStarted, totalRecoverySequencesCompleted, totalRecoverySequencesFailed));
        }
    }

    /**
     * Get recovery statistics for diagnostics
     */
    public String getRecoveryStatistics() {
        return String.format("Recovery Stats - Started: %d, Completed: %d, Failed: %d, Active: %s",
                totalRecoverySequencesStarted, totalRecoverySequencesCompleted, totalRecoverySequencesFailed,
                currentRecoverySequence != null ? "YES" : "NO");
    }

    /**
     * Cancel any active recovery sequence and clean up all exception manager state
     * Since there's only one robot, we can clean everything regardless of route ID
     */
    public void cancelAllRecoveryAndCleanup() {
        if (stateDebugger != null) {
            stateDebugger.log("🔧 CANCELLING ALL EXCEPTION MANAGER STATE (single robot mode)");
        }

        // Cancel active recovery sequence if any
        if (currentRecoverySequence != null) {
            LOG.info("Cancelling active recovery sequence for command {}",
                    currentRecoverySequence.originalCommandId);

            if (stateDebugger != null) {
                stateDebugger.logWarning("cancelAllRecoveryAndCleanup",
                        String.format("⚠️ Cancelling active recovery sequence (command %d, %d/%d steps completed)",
                                currentRecoverySequence.originalCommandId,
                                currentRecoverySequence.currentStep,
                                currentRecoverySequence.recoveryCommands.size()));
            }

            // Mark all recovery commands as CANCELLED
            for (CommandMonitor cmd : currentRecoverySequence.recoveryCommands) {
                if (cmd.getCommandStatus() == NavigationOrderStatus.ORDER_EXECUTING ||
                        cmd.getCommandStatus() == NavigationOrderStatus.ORDER_RAW ||
                        cmd.getCommandStatus() == NavigationOrderStatus.ORDER_READY) {
                    cmd.setCommandStatus(NavigationOrderStatus.ORDER_CANCELLED);
                    CommandMonitor.updateCommand(cmd);
                }
            }

            // Clean up recovery route
            if (recoveryRouteId != -1) {
                RouteStore recoveryRoute = RouteStore.getRoute(recoveryRouteId);
                if (recoveryRoute != null) {
                    recoveryRoute.setRouteStatus(NavigationOrderStatus.ORDER_CANCELLED);
                    RouteStore.updateRoute(recoveryRoute);
                }
            }

            // Clear recovery sequence
            currentRecoverySequence = null;
            recoveryRouteId = -1;
        }

        // Clear any suspended command
        if (suspendedCommand != null) {
            LOG.info("Clearing suspended command {} (will not be resumed)", suspendedCommand.getCommandId());

            // Mark as CANCELLED if it's not already in a terminal state
            if (suspendedCommand.getCommandStatus() != NavigationOrderStatus.ORDER_COMPLETED &&
                    suspendedCommand.getCommandStatus() != NavigationOrderStatus.ORDER_CANCELLED &&
                    suspendedCommand.getCommandStatus() != NavigationOrderStatus.ORDER_FAILED) {
                suspendedCommand.setCommandStatus(NavigationOrderStatus.ORDER_CANCELLED);
                CommandMonitor.updateCommand(suspendedCommand);
            }

            suspendedCommand = null;
        }

        // Clear any locked cancelled command
        if (lockedCancelledCommand != null) {
            lockedCancelledCommand = null;
        }

        // Reset robot state flags
        robotEmergencyState = false;
        robotCurrentLockState = 0;

        // Reset statistics (optional - keep for diagnostics)
        // totalRecoverySequencesStarted = 0;
        // totalRecoverySequencesCompleted = 0;
        // totalRecoverySequencesFailed = 0;

        if (stateDebugger != null) {
            stateDebugger.log("✅ Exception manager state fully cleaned up");
            stateDebugger.logInfo("All recovery sequences, suspended commands, and flags have been reset");
        }
    }

    /**
     * Check if exception manager is currently active (processing any recovery or has suspended commands)
     */
    public boolean isActive() {
        return currentRecoverySequence != null || suspendedCommand != null;
    }

    /**
     * Reset just the robot state flags (for soft reset)
     */
    public void resetRobotState() {
        robotEmergencyState = false;
        robotCurrentLockState = 0;
        if (stateDebugger != null) {
            stateDebugger.log("Robot state flags reset (emergency=false, lockState=0)");
        }
    }

    // ========== Inner Classes ==========

    private static class ElevatorParams {
        String currentFloor = "";
        String currentMap = "";
        Position preridePoint = null;
        Position waitPoint = null;
        Position ridePoint = null;
        String channel = null;
        String address = null;
        String robotChannel = null;
        String robotAddress = null;
        String robotId = null;
    }
}