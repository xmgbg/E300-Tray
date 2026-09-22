package com.ezhan.amr.navigation.task;

import android.util.Log;

import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;

import com.ezhan.amr.navigation.ConfirmationStateManager;

import java.util.ArrayList;
import java.util.List;

public class NavigationPositionManager extends AbstractNavigationManager {
    private final String TAG = "NavigationPositionManager";
    private static NavigationPositionManager instance;

    // Reference to confirmation manager
    private final ConfirmationStateManager confirmationManager;
    private final NavigationStateDebugger stateDebugger;

    private NavigationPositionManager() {
        this.confirmationManager = ConfirmationStateManager.getInstance();
        this.stateDebugger = NavigationStateDebugger.getInstance();
    }

    public static NavigationPositionManager getInstance() {
        if (instance == null) {
            instance = new NavigationPositionManager();
        }
        return instance;
    }

    @Override
    public void initManager() {
        if (stateDebugger != null) {
            stateDebugger.log("========== NavigationPositionManager.initManager START ==========");
        }

        LOG.info("NavigationPositionManager initialized");

        if (stateDebugger != null) {
            stateDebugger.log("NavigationPositionManager initialized successfully");
            stateDebugger.log("========== NavigationPositionManager.initManager END ==========");
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

        // Find PREREADY positions for this route
        List<PositionMonitor> prereadyPositions = getPositionsByStatus(routeId,
                NavigationOrderStatus.ORDER_PREREADY);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Found %d PREREADY positions for route %d",
                    prereadyPositions.size(), routeId));
        }

        for (PositionMonitor position : prereadyPositions) {
            // Activate the first command for this position
            List<CommandMonitor> commands = getCommandsForPosition(position.getPositionId());
            if (!commands.isEmpty()) {
                CommandMonitor firstCommand = commands.get(0);
                TaskDebug8.log(String.format("[POS] processSingleRoute ACTIVATE posId=%d name=%s type=%d taskType=%d cmd=%s routeId=%d",
                        position.getPositionId(),
                        position.getPosition() != null ? position.getPosition().getName() : "null",
                        position.getPosition() != null ? position.getPosition().getType() : -1,
                        position.getPosition() != null ? position.getPosition().getTaskType() : -1,
                        firstCommand.getCommandType(), routeId));

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Activating position %d (name='%s') with first command %d (type=%s)",
                            position.getPositionId(),
                            position.getPosition().getName(),
                            firstCommand.getCommandId(),
                            firstCommand.getCommandType()));
                }

                synchronized (position) {
                    firstCommand.setCommandStatus(NavigationOrderStatus.ORDER_READY);
                    CommandMonitor.updateCommand(firstCommand);

                    position.setPositionStatus(NavigationOrderStatus.ORDER_READY);
                    PositionMonitor.updatePosition(position);
                }

                LOG.info("Position {} activated for route {}", position.getPositionId(), routeId);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Position %d activated with status READY",
                            position.getPositionId()));
                }
                break; // Only activate one position at a time
            } else {
                if (stateDebugger != null) {
                    stateDebugger.logWarning("processSingleRoute",
                            String.format("Position %d has no commands to execute", position.getPositionId()));
                }
            }
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("processSingleRoute END: routeId=%d", routeId));
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

        // Get current position
        long currentPosId = getCurrentPositionId(route);
        if (currentPosId == -1) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("updateSingleRouteStatus",
                        String.format("No current position found for route %d", routeId));
            }
            return;
        }

        PositionMonitor currentPos = PositionMonitor.getPosition(currentPosId);
        if (currentPos == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("updateSingleRouteStatus",
                        String.format("Position %d not found", currentPosId));
            }
            return;
        }

        // Check if all commands for this position are completed
        List<CommandMonitor> commands = getCommandsForPosition(currentPosId);
        boolean allCommandsCompleted = true;
        int completedCommands = 0;
        int totalCommands = commands.size();

        for (CommandMonitor cmd : commands) {
            if (cmd.getCommandStatus() == NavigationOrderStatus.ORDER_COMPLETED) {
                completedCommands++;
            } else {
                allCommandsCompleted = false;
            }
        }

        // Get positions list for route
        List<PositionMonitor> positions = getPositionsForRoute(route.getRouteId());
        int currentIndex = findPositionIndex(positions, currentPosId);
        boolean isLastPosition = (currentIndex == positions.size() - 1);

        // Check if this is a Cruise task (taskType=2)
        // For Cruise tasks, even the last position should wait for confirmation
        // Jack tasks (taskType=3) keep original behavior - last position auto-completes
        int taskType = currentPos.getPosition().getTaskType();
        int positionType = currentPos.getPosition().getType();
        boolean isCruiseTask = (taskType == 2);
        // type=17 (OP_FORWARD) 是透明过渡点，跳过确认，自动放行
        boolean isForwardPoint = (positionType == 17);
        boolean shouldWaitForConfirmation = isForwardPoint ? false :
                (isLastPosition && isCruiseTask ? true : !isLastPosition);

        boolean isWaitingForConfirmation = confirmationManager.isPositionWaitingForConfirmation(currentPosId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Position %d status update:", currentPosId));
            stateDebugger.log(String.format("  - Name: '%s'", currentPos.getPosition().getName()));
            stateDebugger.log(String.format("  - Commands: %d/%d completed", completedCommands, totalCommands));
            stateDebugger.log(String.format("  - allCommandsCompleted: %b", allCommandsCompleted));
            stateDebugger.log(String.format("  - positionStatus: %s", currentPos.getPositionStatus()));
            stateDebugger.log(String.format("  - isWaitingForConfirmation: %b", isWaitingForConfirmation));
            stateDebugger.log(String.format("  - currentIndex: %d/%d", currentIndex, positions.size() - 1));
            stateDebugger.log(String.format("  - isLastPosition: %b", isLastPosition));
            stateDebugger.log(String.format("  - taskType: %d, isCruiseTask: %b", taskType, isCruiseTask));
            stateDebugger.log(String.format("  - shouldWaitForConfirmation: %b", shouldWaitForConfirmation));
        }

        // Log current state for debugging
        Log.d(TAG, String.format("updateSingleRouteStatus: route=%d, pos=%d, allCommandsCompleted=%b, " +
                        "positionStatus=%s, waitingForConfirmation=%b, isLastPosition=%b, taskType=%d, shouldWaitForConfirmation=%b",
                routeId, currentPosId, allCommandsCompleted,
                currentPos.getPositionStatus(),
                isWaitingForConfirmation,
                isLastPosition, taskType, shouldWaitForConfirmation));

        // Step 1: If all commands completed and position not yet waiting for confirmation
        if (allCommandsCompleted &&
                currentPos.getPositionStatus() != NavigationOrderStatus.ORDER_WAITING_CONFIRMATION &&
                currentPos.getPositionStatus() != NavigationOrderStatus.ORDER_COMPLETED) {

            TaskDebug1.log(String.format("[POS] Step1 WAITING_CONFIRMATION posId=%d name=%s idx=%d/%d taskType=%d",
                    currentPosId,
                    currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                    currentIndex, positions.size() - 1, taskType));
            TaskDebug8.log(String.format("[POS] Step1 WAITING_CONFIRMATION posId=%d name=%s idx=%d/%d type=%d taskType=%d cmds=%d/%d wait=%b isLast=%b",
                    currentPosId,
                    currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                    currentIndex, positions.size() - 1,
                    currentPos.getPosition() != null ? currentPos.getPosition().getType() : -1,
                    taskType, completedCommands, totalCommands,
                    confirmationManager.isPositionWaitingForConfirmation(currentPosId),
                    isLastPosition));

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Step 1: All commands completed for position %d", currentPosId));
                stateDebugger.log(String.format("  Setting position status to WAITING_CONFIRMATION"));
            }

            synchronized (currentPos) {
                currentPos.setPositionStatus(NavigationOrderStatus.ORDER_WAITING_CONFIRMATION);
                currentPos.setCompleteTime(System.currentTimeMillis());
                PositionMonitor.updatePosition(currentPos);

                // Set waiting for confirmation based on task type
                // For Cruise tasks, even last position should wait
                // For normal tasks and Jack tasks, last position auto-completes
                if (shouldWaitForConfirmation) {
                    confirmationManager.setPositionWaitingForConfirmation(currentPosId, true);
                    if (stateDebugger != null) {
                        String reason = isCruiseTask ? "cruise task last position" : "not last position";
                        stateDebugger.log(String.format("  Position %d waiting for confirmation (%s)", currentPosId, reason));
                    }
                    Log.d(TAG, "Position " + currentPosId + " waiting for confirmation (taskType=" + taskType + ")");
                } else {
                    confirmationManager.setPositionWaitingForConfirmation(currentPosId, false);
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("  Position %d is last position (non-loop) - auto completing", currentPosId));
                    }
                    Log.d(TAG, "Position " + currentPosId + " is last position (non-loop) - auto completing");
                }
            }

            if (stateDebugger != null) {
                stateDebugger.log(String.format("updateSingleRouteStatus END (waiting for confirmation): routeId=%d", routeId));
            }
            return; // Wait for next cycle
        }

        // Step 2: For positions that need confirmation, check if user has confirmed
        if (shouldWaitForConfirmation) {
            boolean isWaiting = confirmationManager.isPositionWaitingForConfirmation(currentPosId);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Step 2: Confirmation check - isWaiting=%b", isWaiting));
            }

            // 尚未进入等待确认阶段（命令仍在执行），不要误判为已确认
            NavigationOrderStatus posStatus = currentPos.getPositionStatus();
            if (posStatus != NavigationOrderStatus.ORDER_WAITING_CONFIRMATION
                    && posStatus != NavigationOrderStatus.ORDER_COMPLETED) {
                return;
            }

            // If still waiting for confirmation, don't proceed
            if (isWaiting) {
                TaskDebug1.logThrottled("pos_wait_" + currentPosId, 5000,
                        String.format("[POS] Step2 still waiting confirmation posId=%d name=%s taskType=%d",
                                currentPosId,
                                currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                                taskType));
                TaskDebug8.logThrottled("pos_wait8_" + currentPosId, 5000,
                        String.format("[POS] Step2 STILL_WAITING_CONFIRMATION posId=%d name=%s type=%d taskType=%d cmds=%d/%d idx=%d/%d",
                                currentPosId,
                                currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                                currentPos.getPosition() != null ? currentPos.getPosition().getType() : -1,
                                taskType, completedCommands, totalCommands,
                                currentIndex, positions.size() - 1));
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Position %d still waiting for user confirmation - skipping", currentPosId));
                }
                Log.d(TAG, "Position " + currentPosId + " still waiting for user confirmation");

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("updateSingleRouteStatus END (still waiting): routeId=%d", routeId));
                }
                return;
            }

            // 确认已收到，但命令尚未全部完成（识别举升可能仍在进行）
            if (!allCommandsCompleted) {
                TaskDebug1.logThrottled("pos_confirm_wait_cmd_" + currentPosId, 3000,
                        String.format("[POS] Step2 confirmed but cmds incomplete posId=%d name=%s done=%d/%d status=%s",
                                currentPosId,
                                currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                                completedCommands, totalCommands, posStatus));
                TaskDebug8.logThrottled("pos_confirm_wait_cmd8_" + currentPosId, 3000,
                        String.format("[POS] Step2 CONFIRMED_CMD_INCOMPLETE posId=%d name=%s type=%d taskType=%d done=%d/%d status=%s",
                                currentPosId,
                                currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                                currentPos.getPosition() != null ? currentPos.getPosition().getType() : -1,
                                taskType, completedCommands, totalCommands, posStatus));
                return;
            }

            TaskDebug1.log(String.format("[POS] Step2 confirmation received posId=%d name=%s",
                    currentPosId,
                    currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?"));
            if (stateDebugger != null) {
                stateDebugger.log(String.format("  Position %d confirmation received, proceeding to completion", currentPosId));
            }
        }

        // Step 3: Complete current position and activate next
        if (allCommandsCompleted &&
                currentPos.getPositionStatus() != NavigationOrderStatus.ORDER_COMPLETED) {

            TaskDebug1.log(String.format("[POS] Step3 COMPLETE posId=%d name=%s idx=%d/%d isLast=%b taskType=%d type=%d",
                    currentPosId,
                    currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                    currentIndex, positions.size() - 1, isLastPosition, taskType,
                    currentPos.getPosition() != null ? currentPos.getPosition().getType() : -1));
            TaskDebug8.log(String.format("[POS] Step3 COMPLETE posId=%d name=%s idx=%d/%d isLast=%b type=%d taskType=%d",
                    currentPosId,
                    currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?",
                    currentIndex, positions.size() - 1, isLastPosition,
                    currentPos.getPosition() != null ? currentPos.getPosition().getType() : -1,
                    taskType));
            if (taskType == 3 && currentIndex <= 1) {
                TaskDebug1.log(String.format("[SKIP1ST] jackPoint DONE idx=%d name=%s -> activateNext",
                        currentIndex,
                        currentPos.getPosition() != null ? currentPos.getPosition().getName() : "?"));
            }

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Step 3: Completing position %d", currentPosId));
            }

            synchronized (currentPos) {
                // Clear confirmation state for this position
                confirmationManager.setPositionWaitingForConfirmation(currentPosId, false);

                currentPos.setPositionStatus(NavigationOrderStatus.ORDER_COMPLETED);
                currentPos.setCompleteTime(System.currentTimeMillis());
                PositionMonitor.updatePosition(currentPos);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ Position %d completed at %d",
                            currentPosId, currentPos.getCompleteTime()));
                }

                Log.d(TAG, "Position " + currentPosId + " completed for route " + routeId);
            }

            // Activate next position
            activateNextPosition(route);
        }

        stateDebugger.dumpPositionsForRoute(routeId);
        stateDebugger.dumpCommandsForPosition(currentPosId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("updateSingleRouteStatus END: routeId=%d, position=%d completed",
                    routeId, currentPosId));
        }
    }

    private int findPositionIndex(List<PositionMonitor> positions, long positionId) {
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).getPositionId() == positionId) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("findPositionIndex: position %d found at index %d", positionId, i));
                }
                return i;
            }
        }
        if (stateDebugger != null) {
            stateDebugger.logWarning("findPositionIndex", String.format("position %d not found", positionId));
        }
        return -1;
    }

    private void activateNextPosition(RouteStore route) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("========== activateNextPosition START: routeId=%d", route.getRouteId()));
        }

        List<PositionMonitor> positions = getPositionsForRoute(route.getRouteId());
        int currentIndex = route.getCurrentPositionIndex();

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Current index: %d, Total positions: %d",
                    currentIndex, positions.size()));
        }

        Log.d(TAG, "activateNextPosition: route=" + route.getRouteId() +
                ", currentIndex=" + currentIndex + ", totalPositions=" + positions.size());

        if (currentIndex + 1 < positions.size()) {
            PositionMonitor currPos = positions.get(currentIndex);
            PositionMonitor nextPos = positions.get(currentIndex + 1);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Current position: %d (name='%s', status=%s)",
                        currPos.getPositionId(),
                        currPos.getPosition().getName(),
                        currPos.getPositionStatus()));
                stateDebugger.log(String.format("Next position: %d (name='%s', status=%s)",
                        nextPos.getPositionId(),
                        nextPos.getPosition().getName(),
                        nextPos.getPositionStatus()));
            }

            // Only activate if not already activated
            if (nextPos.getPositionStatus() == NavigationOrderStatus.ORDER_RAW) {
                TaskDebug1.log(String.format("[POS] activateNext PREREADY nextPosId=%d name=%s idx=%d/%d type=%d taskType=%d prev=%s",
                        nextPos.getPositionId(),
                        nextPos.getPosition() != null ? nextPos.getPosition().getName() : "?",
                        currentIndex + 1, positions.size() - 1,
                        nextPos.getPosition() != null ? nextPos.getPosition().getType() : -1,
                        nextPos.getPosition() != null ? nextPos.getPosition().getTaskType() : -1,
                        currPos.getPosition() != null ? currPos.getPosition().getName() : "?"));
                TaskDebug8.log(String.format("[POS] activateNext PREREADY nextPosId=%d name=%s idx=%d/%d type=%d taskType=%d floor=%s prev=%s",
                        nextPos.getPositionId(),
                        nextPos.getPosition() != null ? nextPos.getPosition().getName() : "?",
                        currentIndex + 1, positions.size() - 1,
                        nextPos.getPosition() != null ? nextPos.getPosition().getType() : -1,
                        nextPos.getPosition() != null ? nextPos.getPosition().getTaskType() : -1,
                        nextPos.getPosition() != null ? nextPos.getPosition().getFloor() : "null",
                        currPos.getPosition() != null ? currPos.getPosition().getName() : "?"));
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Activating next position %d (status: RAW -> PREREADY)",
                            nextPos.getPositionId()));
                }

                synchronized (route) {
                    nextPos.setPositionStatus(NavigationOrderStatus.ORDER_PREREADY);
                    PositionMonitor.updatePosition(nextPos);
                    route.setCurrentPositionIndex(currentIndex + 1);
                    route.setCurrentTarget(nextPos.getPosition());
                    RouteStore.updateRoute(route);

                    // Clear any stale confirmation state for the new position
                    confirmationManager.clearPositionState(currPos.getPositionId());

                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("✅ Next position %d activated", nextPos.getPositionId()));
                        stateDebugger.log(String.format("  New current index: %d", currentIndex + 1));
                        stateDebugger.log(String.format("  New current target: '%s'",
                                nextPos.getPosition().getName()));
                    }
                }

                Log.d(TAG, "Next position " + nextPos.getPositionId() + " activated for route " + route.getRouteId());
            } else {
                TaskDebug8.logThrottled("pos_activate_skip_" + nextPos.getPositionId(), 5000,
                        String.format("[POS] activateNext SKIP nextPosId=%d name=%s status=%s (not RAW) idx=%d/%d",
                                nextPos.getPositionId(),
                                nextPos.getPosition() != null ? nextPos.getPosition().getName() : "?",
                                nextPos.getPositionStatus(),
                                currentIndex + 1, positions.size() - 1));
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Next position %d already has status %s, skipping activation",
                            nextPos.getPositionId(), nextPos.getPositionStatus()));
                }
            }
        } else {
            // This is the last position - route will be marked completed when position completes
            if (stateDebugger != null) {
                stateDebugger.log("No next position - this is the last position");
            }
            Log.d(TAG, "No next position - this is the last position");
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("activateNextPosition END: routeId=%d", route.getRouteId()));
        }
    }

    private long getCurrentPositionId(RouteStore route) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("getCurrentPositionId: routeId=%d", route.getRouteId()));
        }

        List<PositionMonitor> positions = getPositionsForRoute(route.getRouteId());
        if (positions.isEmpty()) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("getCurrentPositionId", "No positions found for route");
            }
            return -1;
        }

        // Priority order: EXECUTING > WAITING_CONFIRMATION > READY > PREREADY
        NavigationOrderStatus[] priorityOrder = {
                NavigationOrderStatus.ORDER_EXECUTING,
                NavigationOrderStatus.ORDER_WAITING_CONFIRMATION,
                NavigationOrderStatus.ORDER_READY,
                NavigationOrderStatus.ORDER_PREREADY
        };

        for (NavigationOrderStatus status : priorityOrder) {
            for (PositionMonitor pos : positions) {
                if (pos.getPositionStatus() == status) {
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("  Found position %d (name='%s') with status %s",
                                pos.getPositionId(), pos.getPosition().getName(), status));
                    }
                    Log.d(TAG, "getCurrentPositionId: found position " + pos.getPositionId() +
                            " with status " + status);
                    return pos.getPositionId();
                }
            }
        }

        if (stateDebugger != null) {
            stateDebugger.logWarning("getCurrentPositionId",
                    String.format("No active position found for route %d", route.getRouteId()));
        }

        Log.d(TAG, "getCurrentPositionId: no active position found for route " + route.getRouteId());
        return -1;
    }

    private List<PositionMonitor> getPositionsByStatus(long routeId, NavigationOrderStatus status) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("getPositionsByStatus: routeId=%d, status=%s", routeId, status));
        }

        List<PositionMonitor> result = new ArrayList<>();
        List<PositionMonitor> allPositions = getPositionsForRoute(routeId);

        for (PositionMonitor pos : allPositions) {
            if (pos.getPositionStatus() == status) {
                result.add(pos);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Found position %d (name='%s') with status %s",
                            pos.getPositionId(), pos.getPosition().getName(), status));
                }
            }
        }

        if (stateDebugger != null && result.isEmpty()) {
            stateDebugger.log(String.format("  No positions found with status %s for route %d", status, routeId));
        }

        return result;
    }
}