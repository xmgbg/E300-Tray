package com.ezhan.amr.navigation.task;

import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NavigationCommandManager extends AbstractNavigationManager {
    private static NavigationCommandManager instance;
    private static final int MAX_RECOGNIZE_JACK_RETRIES = 2;

    // Get debugger instance
    private final NavigationStateDebugger stateDebugger = NavigationStateDebugger.getInstance();

    public static NavigationCommandManager getInstance() {
        if (instance == null) {
            instance = new NavigationCommandManager();
        }
        return instance;
    }

    // Command senders (similar to OperationSend)
    private final CommandSender commandSender = new CommandSender();
    // Command checkers (similar to OperationCheck)
    private final CommandChecker commandChecker = new CommandChecker();

    @Override
    public void initManager() {
        if (stateDebugger != null) {
            stateDebugger.log("========== NavigationCommandManager.initManager START ==========");
        }

        LOG.info("NavigationCommandManager initialized");

        if (stateDebugger != null) {
            stateDebugger.log("NavigationCommandManager initialized successfully");
            stateDebugger.log("========== NavigationCommandManager.initManager END ==========");
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

        // Find READY commands for this route
        List<CommandMonitor> readyCommands = getReadyCommandsForRoute(routeId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Found %d READY commands for route %d", readyCommands.size(), routeId));
            for (CommandMonitor cmd : readyCommands) {
                stateDebugger.log(String.format("  READY command: id=%d, type=%s, positionId=%d",
                        cmd.getCommandId(), cmd.getCommandType(), cmd.getPositionId()));
            }
        }

        for (CommandMonitor command : readyCommands) {
            processSingleCommand(command, route);
            break; // Only process one command at a time
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("processSingleRoute END: routeId=%d", routeId));
        }
    }

    private void processSingleCommand(CommandMonitor command, RouteStore route) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("========== processSingleCommand START: commandId=%d, routeId=%d",
                    command.getCommandId(), route.getRouteId()));
            stateDebugger.log(String.format("Command type: %s, positionId: %d, status before: %s",
                    command.getCommandType(), command.getPositionId(), command.getCommandStatus()));
        }

        int sendResult = commandSender.sendCommand(command);

        TaskDebug1.log(String.format("[CMD] send cmdId=%d type=%s posId=%d result=%d",
                command.getCommandId(), command.getCommandType(), command.getPositionId(), sendResult));
        TaskDebug8.log(String.format("[CMD] SEND cmdId=%d type=%s posId=%d result=%d target=%s targetType=%d tt=%d routeId=%d",
                command.getCommandId(), command.getCommandType(), command.getPositionId(), sendResult,
                command.getTarget() != null ? command.getTarget().getName() : "null",
                command.getTarget() != null ? command.getTarget().getType() : -1,
                command.getTarget() != null ? command.getTarget().getTaskType() : -1,
                route.getRouteId()));

        if (sendResult == 1) {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("✅ Command %d sent successfully (sendResult=1)", command.getCommandId()));
            }

            synchronized (command) {
                command.setCommandStatus(NavigationOrderStatus.ORDER_EXECUTING);
                command.setStartTime(System.currentTimeMillis());
                NavigationOrderType commandType = command.getCommandType();
                if (commandType == NavigationOrderType.OP_RECOGNIZE_LOAD
                        || commandType == NavigationOrderType.OP_RECOGNIZE_UNLOAD) {
                    command.resetPreviousGoalFinish();
                    TaskDebug1.log(String.format("[CMD] recognize jack reset goalFinish track cmdId=%d type=%s",
                            command.getCommandId(), commandType));
                }
                CommandMonitor.updateCommand(command);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Command %d status updated to EXECUTING at %d",
                            command.getCommandId(), command.getStartTime()));
                }

                // Update parent position status
                updateParentPositionStatus(command.getPositionId(),
                        NavigationOrderStatus.ORDER_EXECUTING);

                // Update route status
                route.setRouteStatus(NavigationOrderStatus.ORDER_EXECUTING);
                if (route.getStartTime() == 0L) {
                    route.setStartTime(System.currentTimeMillis());
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("Route %d start time set to %d",
                                route.getRouteId(), route.getStartTime()));
                    }
                }
                RouteStore.updateRoute(route);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Route %d status updated to EXECUTING", route.getRouteId()));
                    stateDebugger.log(String.format("Command %d executing for route %d",
                            command.getCommandId(), route.getRouteId()));
                }

                LOG.info("Command {} executing for route {}",
                        command.getCommandId(), route.getRouteId());
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.logError("processSingleCommand",
                        String.format("❌ Failed to send command %d (sendResult=%d) for route %d",
                                command.getCommandId(), sendResult, route.getRouteId()));
            }
            TaskDebug1.log(String.format("[CMD] SEND_FAILED cmdId=%d type=%s routeId=%d sendResult=%d",
                    command.getCommandId(), command.getCommandType(), route.getRouteId(), sendResult));
            TaskDebug8.log(String.format("[CMD] SEND_FAILED cmdId=%d type=%s posId=%d routeId=%d sendResult=%d target=%s targetType=%d tt=%d",
                    command.getCommandId(), command.getCommandType(), command.getPositionId(),
                    route.getRouteId(), sendResult,
                    command.getTarget() != null ? command.getTarget().getName() : "null",
                    command.getTarget() != null ? command.getTarget().getType() : -1,
                    command.getTarget() != null ? command.getTarget().getTaskType() : -1));
            LOG.warn("Failed to send command {} for route {}",
                    command.getCommandId(), route.getRouteId());
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("processSingleCommand END: commandId=%d", command.getCommandId()));
        }
    }

    @Override
    public void updateSingleRouteStatus(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("========== updateSingleRouteStatus START: routeId=%d", routeId));
        }

        // Check executing commands
        List<CommandMonitor> executingCommands = getExecutingCommandsForRoute(routeId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Found %d EXECUTING commands for route %d",
                    executingCommands.size(), routeId));
            for (CommandMonitor cmd : executingCommands) {
                stateDebugger.log(String.format("  EXECUTING command: id=%d, type=%s, positionId=%d, startTime=%d",
                        cmd.getCommandId(), cmd.getCommandType(), cmd.getPositionId(), cmd.getStartTime()));
            }
        }

        for (CommandMonitor command : executingCommands) {
            int checkResult = commandChecker.checkCommand(command);

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Checking command %d: checkResult=%d", command.getCommandId(), checkResult));
            }

            if (checkResult == 1) { // Command completed
                TaskDebug1.log(String.format("[CMD] COMPLETE cmdId=%d type=%s routeId=%d",
                        command.getCommandId(), command.getCommandType(), routeId));
                TaskDebug8.log(String.format("[CMD] COMPLETE cmdId=%d type=%s posId=%d routeId=%d target=%s targetType=%d tt=%d elapsed=%dms",
                        command.getCommandId(), command.getCommandType(), command.getPositionId(), routeId,
                        command.getTarget() != null ? command.getTarget().getName() : "null",
                        command.getTarget() != null ? command.getTarget().getType() : -1,
                        command.getTarget() != null ? command.getTarget().getTaskType() : -1,
                        System.currentTimeMillis() - command.getStartTime()));
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ Command %d completed successfully", command.getCommandId()));
                }

                synchronized (command) {
                    command.setCommandStatus(NavigationOrderStatus.ORDER_COMPLETED);
                    command.setCompleteTime(System.currentTimeMillis());
                    CommandMonitor.updateCommand(command);

                    long duration = command.getCompleteTime() - command.getStartTime();

                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("Command %d completed at %d (duration: %d ms)",
                                command.getCommandId(), command.getCompleteTime(), duration));

                        if (duration > 5000) {
                            stateDebugger.logWarning("updateSingleRouteStatus",
                                    String.format("Command %d took %d ms to complete (slow)", command.getCommandId(), duration));
                        }
                    }

                    LOG.info("Command {} completed for route {}",
                            command.getCommandId(), routeId);
                }
            } else if (checkResult == -1) { // Command failed
                NavigationOrderType failedType = command.getCommandType();
                boolean isRecognizeJack = failedType == NavigationOrderType.OP_RECOGNIZE_LOAD
                        || failedType == NavigationOrderType.OP_RECOGNIZE_UNLOAD;
                int retryCount = command.getRetryCount();
                if (isRecognizeJack && retryCount < MAX_RECOGNIZE_JACK_RETRIES) {
                    synchronized (command) {
                        command.setRetryCount(retryCount + 1);
                        command.resetPreviousGoalFinish();
                        command.setCommandStatus(NavigationOrderStatus.ORDER_READY);
                        command.setStartTime(0L);
                        CommandMonitor.updateCommand(command);
                    }
                    TaskDebug1.log(String.format("[CMD] RETRY recognize jack cmdId=%d type=%s attempt=%d/%d routeId=%d",
                            command.getCommandId(), failedType, retryCount + 1, MAX_RECOGNIZE_JACK_RETRIES, routeId));
                } else {
                    TaskDebug1.log(String.format("[CMD] FAILED cmdId=%d type=%s routeId=%d",
                            command.getCommandId(), command.getCommandType(), routeId));
                    TaskDebug8.log(String.format("[CMD] FAILED cmdId=%d type=%s posId=%d routeId=%d target=%s targetType=%d tt=%d elapsed=%dms",
                            command.getCommandId(), command.getCommandType(), command.getPositionId(), routeId,
                            command.getTarget() != null ? command.getTarget().getName() : "null",
                            command.getTarget() != null ? command.getTarget().getType() : -1,
                            command.getTarget() != null ? command.getTarget().getTaskType() : -1,
                            System.currentTimeMillis() - command.getStartTime()));
                    if (stateDebugger != null) {
                        stateDebugger.logError("updateSingleRouteStatus",
                                String.format("❌ Command %d failed for route %d", command.getCommandId(), routeId));
                    }

                    synchronized (command) {
                        command.setCommandStatus(NavigationOrderStatus.ORDER_FAILED);
                        CommandMonitor.updateCommand(command);

                        if (stateDebugger != null) {
                            stateDebugger.logError("updateSingleRouteStatus",
                                    String.format("Command %d status set to FAILED", command.getCommandId()));
                        }

                        LOG.error("Command {} failed for route {}",
                                command.getCommandId(), routeId);
                    }
                }
            }
        }

        // Activate next command after current completes
        activateNextCommands(routeId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("updateSingleRouteStatus END: routeId=%d", routeId));
        }
    }

    private void activateNextCommands(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("========== activateNextCommands START: routeId=%d", routeId));
        }

        List<PositionMonitor> positions = getPositionsForRoute(routeId);

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Checking %d positions for route %d", positions.size(), routeId));
        }

        for (PositionMonitor position : positions) {
            if (position.getPositionStatus() != NavigationOrderStatus.ORDER_EXECUTING &&
                    position.getPositionStatus() != NavigationOrderStatus.ORDER_READY) {
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("Skipping position %d (name='%s') - status=%s (not EXECUTING or READY)",
                            position.getPositionId(), position.getPosition().getName(), position.getPositionStatus()));
                }
                continue;
            }

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Processing position %d (name='%s') with status=%s",
                        position.getPositionId(), position.getPosition().getName(), position.getPositionStatus()));
            }

            List<CommandMonitor> commands = getCommandsForPosition(position.getPositionId());

            if (stateDebugger != null) {
                stateDebugger.log(String.format("Position %d has %d commands", position.getPositionId(), commands.size()));
                for (int i = 0; i < commands.size(); i++) {
                    CommandMonitor cmd = commands.get(i);
                    stateDebugger.log(String.format("  Command[%d]: id=%d, type=%s, status=%s",
                            i, cmd.getCommandId(), cmd.getCommandType(), cmd.getCommandStatus()));
                }
            }

            for (int i = 0; i < commands.size() - 1; i++) {
                CommandMonitor current = commands.get(i);
                CommandMonitor next = commands.get(i + 1);

                if (current.getCommandStatus() == NavigationOrderStatus.ORDER_COMPLETED &&
                        next.getCommandStatus() == NavigationOrderStatus.ORDER_RAW) {

                    TaskDebug8.log(String.format("[CMD] activateNext current=%d(%s) -> next=%d(%s) posId=%d target=%s targetType=%d",
                            current.getCommandId(), current.getCommandType(),
                            next.getCommandId(), next.getCommandType(), position.getPositionId(),
                            next.getTarget() != null ? next.getTarget().getName() : "null",
                            next.getTarget() != null ? next.getTarget().getType() : -1));

                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("Activating next command: current=%d (COMPLETED) -> next=%d (RAW -> READY)",
                                current.getCommandId(), next.getCommandId()));
                        stateDebugger.log(String.format("  Command types: %s -> %s",
                                current.getCommandType(), next.getCommandType()));
                    }

                    synchronized (next) {
                        next.setCommandStatus(NavigationOrderStatus.ORDER_READY);
                        CommandMonitor.updateCommand(next);

                        if (stateDebugger != null) {
                            stateDebugger.log(String.format("✅ Next command %d activated for position %d",
                                    next.getCommandId(), position.getPositionId()));
                        }

                        LOG.info("Next command {} activated for position {}",
                                next.getCommandId(), position.getPositionId());
                    }
                    break; // Only activate one command at a time
                } else if (current.getCommandStatus() != NavigationOrderStatus.ORDER_COMPLETED) {
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("Command %d not yet completed (status=%s), cannot activate next",
                                current.getCommandId(), current.getCommandStatus()));
                    }
                } else if (next.getCommandStatus() != NavigationOrderStatus.ORDER_RAW) {
                    if (stateDebugger != null) {
                        stateDebugger.log(String.format("Next command %d already has status %s, skipping activation",
                                next.getCommandId(), next.getCommandStatus()));
                    }
                }
            }
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("activateNextCommands END: routeId=%d", routeId));
        }
    }

    private void updateParentPositionStatus(long positionId, NavigationOrderStatus status) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("updateParentPositionStatus: positionId=%d, newStatus=%s",
                    positionId, status));
        }

        PositionMonitor position = PositionMonitor.getPosition(positionId);
        if (position != null && position.getPositionStatus() != status) {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("Updating position %d status from %s to %s",
                        positionId, position.getPositionStatus(), status));
            }

            synchronized (position) {
                position.setPositionStatus(status);
                PositionMonitor.updatePosition(position);

                if (stateDebugger != null) {
                    stateDebugger.log(String.format("✅ Position %d status updated to %s", positionId, status));
                }
            }
        } else if (position == null) {
            if (stateDebugger != null) {
                stateDebugger.logWarning("updateParentPositionStatus",
                        String.format("Position %d not found", positionId));
            }
        } else {
            if (stateDebugger != null) {
                stateDebugger.log(String.format("Position %d already has status %s, no update needed",
                        positionId, status));
            }
        }
    }

    private List<CommandMonitor> getReadyCommandsForRoute(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("getReadyCommandsForRoute: routeId=%d", routeId));
        }

        List<CommandMonitor> result = new ArrayList<>();
        for (CommandMonitor cmd : CommandMonitor.getAllCommands().values()) {
            if (cmd.getRouteId() == routeId &&
                    cmd.getCommandStatus() == NavigationOrderStatus.ORDER_READY) {
                result.add(cmd);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Found READY command: id=%d, type=%s, positionId=%d",
                            cmd.getCommandId(), cmd.getCommandType(), cmd.getPositionId()));
                }
            }
        }
        result.sort(Comparator.comparingLong(CommandMonitor::getCommandId));

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Total READY commands found: %d", result.size()));
        }

        return result;
    }

    private List<CommandMonitor> getExecutingCommandsForRoute(long routeId) {
        if (stateDebugger != null) {
            stateDebugger.log(String.format("getExecutingCommandsForRoute: routeId=%d", routeId));
        }

        List<CommandMonitor> result = new ArrayList<>();
        for (CommandMonitor cmd : CommandMonitor.getAllCommands().values()) {
            if (cmd.getRouteId() == routeId &&
                    cmd.getCommandStatus() == NavigationOrderStatus.ORDER_EXECUTING) {
                result.add(cmd);
                if (stateDebugger != null) {
                    stateDebugger.log(String.format("  Found EXECUTING command: id=%d, type=%s, positionId=%d, startTime=%d",
                            cmd.getCommandId(), cmd.getCommandType(), cmd.getPositionId(), cmd.getStartTime()));
                }
            }
        }

        if (stateDebugger != null) {
            stateDebugger.log(String.format("Total EXECUTING commands found: %d", result.size()));
        }

        return result;
    }
}