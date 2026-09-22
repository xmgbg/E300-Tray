package com.ezhan.amr.navigation.task;

import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.LoraCommand;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// CommandMonitor.java - Similar to TaskInstructionMonitor
public class CommandMonitor {
    private static final Map<Long, CommandMonitor> commandMonitorMap = new ConcurrentHashMap<>();
    private static AtomicLong nextCommandId = new AtomicLong(1);

    private long commandId;                       // Unique ID for this command
    private long positionId;                       // Parent position ID
    private long routeId;                           // Parent route ID
    private int sequenceIndex;                       // Order within position
    private NavigationOrderType commandType;         // Type of command
    private NavigationOrderStatus commandStatus;     // Status of this command
    private String operation;                         // Operation name
    private String operationParams;                   // Parameters (JSON string for LoraCommand)
    private Position target = null;                             // Target position
    private Position preridePoint = null;
    private Position ridePoint = null;
    private LoraCommand loraCommand;                      // Lora command if applicable
    private long startTime = 0L;
    private long completeTime = 0L;
    private int retryCount;
    private String result;
    private boolean waitingForConfirmation = false;
    private boolean isSecondLegSent = false;
    private int currentWaypointIndex = -1;  // -1 = not started, 0=pretarget, 1=oriented, 2=target
    private int localizeSuccessCount = 0;
    private int resendAttemptCount = 0;
    private boolean isExceptionRecovery = false;
    private transient Map<String, Object> extraData = new HashMap<>();
    private long lastStatusTime = 0;
    private RecoveryType recoveryType;

    // GoalFinish state transition tracking (用于识别举升/卸载)
    // 记录上一次检查时的 goalFinish 值，检测 非1→1 的转换
    // Integer.MIN_VALUE = 尚未初始化（首次检查时捕获当前值）
    public static final int GOAL_FINISH_UNINITIALIZED = Integer.MIN_VALUE;
    private int previousGoalFinish = GOAL_FINISH_UNINITIALIZED;
    // 当命令发送时 goalFinish 已为 1，需先等到 0 再检测完成
    private boolean awaitingGoalFinishCycle = false;


    // Create command with Position target
    public static CommandMonitor createCommand(NavigationOrderType type, Position target,
                                               long positionId, long routeId, int index) {
        CommandMonitor cmd = new CommandMonitor();
        cmd.commandId = nextCommandId.getAndIncrement();
        cmd.positionId = positionId;
        cmd.routeId = routeId;
        cmd.sequenceIndex = index;
        cmd.commandType = type;
        cmd.commandStatus = NavigationOrderStatus.ORDER_RAW;
        cmd.target = target;
        cmd.operation = getOperationName(type);
        cmd.operationParams = extractParams(type, target);
        commandMonitorMap.put(cmd.commandId, cmd);
        return cmd;
    }

    // New method: Create command with LoraCommand target
    public static CommandMonitor createCommand(NavigationOrderType type, LoraCommand loraCommand,
                                               long positionId, long routeId, int index) {
        CommandMonitor cmd = new CommandMonitor();
        cmd.commandId = nextCommandId.getAndIncrement();
        cmd.positionId = positionId;
        cmd.routeId = routeId;
        cmd.sequenceIndex = index;
        cmd.commandType = type;
        cmd.commandStatus = NavigationOrderStatus.ORDER_RAW;
        cmd.target = extractLoraCommandTarget(loraCommand);
        cmd.preridePoint = extractLoraCommandPreridePoint(loraCommand);
        cmd.ridePoint = extractLoraCommandRidePoint(loraCommand);
        cmd.loraCommand = loraCommand;
        cmd.operation = getOperationName(type);
        cmd.operationParams = extractLoraCommandParams(loraCommand); // Store LoraCommand params as JSON
        commandMonitorMap.put(cmd.commandId, cmd);
        return cmd;
    }

    private static String getOperationName(NavigationOrderType type) {
        switch (type) {
            case OP_CANCEL: return "CancelRobot";
            case OP_FREE_MOVE: return "FreeMove";
            case OP_FIXED_MOVE: return "FixedMove";
            case OP_FORWARD: return "Forward";
            case OP_BACKWARD: return "Backward";
            case OP_IN_ELEVATOR_MOVE: return "ElevatorIn";
            case OP_OUT_ELEVATOR_MOVE: return "ElevatorOut";
            case OP_RECOGNIZE_LOAD: return "JackPgvLoad";
            case OP_RECOGNIZE_UNLOAD: return "JackDownPgvUnload";
            case OP_NON_RECOGNIZE_LOAD: return "JackLoad";
            case OP_NON_RECOGNIZE_UNLOAD: return "JackUnload";
            case OP_EXIT_SHELF: return "AgvMove";
            case OP_CHARGE_START: return "ChargeOn";
            case OP_CHARGE_STOP: return "ChargeOff";
            case OP_ELEVATOR_CALL: return "ElevatorCall";
            case OP_ELEVATOR_CHECK: return "ElevatorCheck";
            case OP_ELEVATOR_DOOR_CHECK: return "ElevatorDoorCheck";
            case OP_ELEVATOR_OPEN: return "ElevatorOpen";
            case OP_ELEVATOR_CANCEL_OPEN: return "ElevatorCancelOpen";
            case OP_ELEVATOR_ARRIVAL_DOOR_CHECK: return "ElevatorArrivalDoorCheck";
            default: return "AgvMove";
        }
    }

    public boolean isExceptionRecovery() {
        return isExceptionRecovery;
    }

    public void setExceptionRecovery(boolean exceptionRecovery) {
        this.isExceptionRecovery = exceptionRecovery;
    }

    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }

    public void setCurrentWaypointIndex(int currentWaypointIndex) {
        this.currentWaypointIndex = currentWaypointIndex;
    }

    public boolean isSecondLegSent() {
        return isSecondLegSent;
    }

    public void setSecondLegSent(boolean secondLegSent) {
        isSecondLegSent = secondLegSent;
    }

    private static String extractParams(NavigationOrderType type, Position target) {
        if (target != null) {
            return String.valueOf(target.getId());
        }
        return "";
    }

    // New method: Extract target from LoraCommand
    private static Position extractLoraCommandTarget(LoraCommand loraCommand) {
        if (loraCommand == null) {
            return null;
        }

        // Directly return the target from LoraCommand
        // This assumes LoraCommand has a getTarget() method
        return loraCommand.getTarget();
    }

    private static Position extractLoraCommandPreridePoint(LoraCommand loraCommand) {
        if (loraCommand == null) {
            return null;
        }

        // Directly return the target from LoraCommand
        // This assumes LoraCommand has a getTarget() method
        return loraCommand.getPreridePoint();
    }

    private static Position extractLoraCommandRidePoint(LoraCommand loraCommand) {
        if (loraCommand == null) {
            return null;
        }

        // Directly return the target from LoraCommand
        // This assumes LoraCommand has a getTarget() method
        return loraCommand.getRidePoint();
    }

    // New method: Extract LoraCommand parameters into JSON format
    private static String extractLoraCommandParams(LoraCommand loraCommand) {
        if (loraCommand == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Add hexParams if present
        if (loraCommand.hexParams != null && !loraCommand.hexParams.isEmpty()) {
            sb.append("\"hexParams\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : loraCommand.hexParams.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                if (entry.getValue() instanceof String) {
                    sb.append("\"").append(escapeJson((String) entry.getValue())).append("\"");
                } else {
                    sb.append(entry.getValue());
                }
                first = false;
            }
            sb.append("}");
        }

        // Add device command - FIX: Remove the leading comma by checking if we need one
        if (loraCommand.getDeviceCommand() != null) {
            if (sb.length() > 1) { // If we already have content (not just "{")
                sb.append(",");
            }
            sb.append("\"deviceCommand\":\"").append(escapeJson(loraCommand.getDeviceCommand())).append("\"");
        }

        if (loraCommand.getExpectedResponse() != null) {
            if (sb.length() > 1) { // If we already have content (not just "{")
                sb.append(",");
            }
            sb.append("\"expectedResponse\":\"").append(escapeJson(loraCommand.getExpectedResponse())).append("\"");
        }

        // Add description if present
        if (loraCommand.getDescription() != null && !loraCommand.getDescription().isEmpty()) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append("\"description\":\"").append(escapeJson(loraCommand.getDescription())).append("\"");
        }

        // Add isPlayMusic flag
        if (sb.length() > 1) {
            sb.append(",");
        }
        sb.append("\"isPlayMusic\":").append(loraCommand.isPlayMusic());



        sb.append("}");
        return sb.toString();
    }

    // Helper method to escape JSON strings
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static CommandMonitor getCommand(long commandId) {
        return commandMonitorMap.get(commandId);
    }

    public static void updateCommand(CommandMonitor monitor) {
        commandMonitorMap.put(monitor.getCommandId(), monitor);
    }

    // Getters and setters
    public long getCommandId() {
        return commandId;
    }

    public long getPositionId() {
        return positionId;
    }

    public long getRouteId() {
        return routeId;
    }

    public int getSequenceIndex() {
        return sequenceIndex;
    }

    public NavigationOrderType getCommandType() {
        return commandType;
    }

    public NavigationOrderStatus getCommandStatus() {
        return commandStatus;
    }

    public Position getTarget() {
        return target;
    }

    // New getter for LoraCommand
    public LoraCommand getLoraCommand() {
        return loraCommand;
    }

    public void setCommandStatus(NavigationOrderStatus commandStatus) {
        this.commandStatus = commandStatus;
    }

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public static Map<Long, CommandMonitor> getAllCommands() {
        return Collections.unmodifiableMap(commandMonitorMap);
    }

    public static void clearAllCommands() {
        commandMonitorMap.clear();
        nextCommandId = new AtomicLong(1);
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public String getOperationParams() {
        return operationParams;
    }

    public String getOperation() {
        return operation;
    }
    public boolean isWaitingForConfirmation() {
        return waitingForConfirmation;
    }

    public void setWaitingForConfirmation(boolean waiting) {
        this.waitingForConfirmation = waiting;
    }

    public Position getPreridePoint() {
        return preridePoint;
    }

    public void setPreridePoint(Position preridePoint) {
        this.preridePoint = preridePoint;
    }

    public Position getRidePoint() {
        return ridePoint;
    }

    public void setRidePoint(Position ridePoint) {
        this.ridePoint = ridePoint;
    }
    public int getLocalizeSuccessCount() {
        return localizeSuccessCount;
    }

    public void setLocalizeSuccessCount(int count) {
        this.localizeSuccessCount = count;
    }

    public int getResendAttemptCount() {
        return resendAttemptCount;
    }

    public void setResendAttemptCount(int count) {
        this.resendAttemptCount = count;
    }
    public void putExtraData(String key, Object value) {
        if (extraData == null) {
            extraData = new HashMap<>();
        }
        extraData.put(key, value);
    }

    public Object getExtraData(String key) {
        if (extraData == null) {
            return null;
        }
        return extraData.get(key);
    }

    public long getLastStatusTime() {
        return lastStatusTime;
    }

    public void setLastStatusTime(long lastStatusTime) {
        this.lastStatusTime = lastStatusTime;
    }

    public RecoveryType getRecoveryType() {
        return recoveryType;
    }

    public void setRecoveryType(RecoveryType recoveryType) {
        this.recoveryType = recoveryType;
    }

    public int getPreviousGoalFinish() {
        return previousGoalFinish;
    }

    public void setPreviousGoalFinish(int previousGoalFinish) {
        this.previousGoalFinish = previousGoalFinish;
    }

    /** 重置 goalFinish 跟踪状态 */
    public void resetPreviousGoalFinish() {
        this.previousGoalFinish = GOAL_FINISH_UNINITIALIZED;
        this.awaitingGoalFinishCycle = false;
    }

    public boolean isAwaitingGoalFinishCycle() {
        return awaitingGoalFinishCycle;
    }

    public void setAwaitingGoalFinishCycle(boolean awaitingGoalFinishCycle) {
        this.awaitingGoalFinishCycle = awaitingGoalFinishCycle;
    }
}