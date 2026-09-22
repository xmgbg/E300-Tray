package com.ezhan.amr.navigation.task;

import android.util.Log;

import com.ezhan.amr.utils.TaskDebug1;
import com.ezhan.amr.utils.TaskDebug8;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.LoraCommand;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

public class CommandChecker {
    private final String TAG = "CommandChecker";
    private static final Logger LOG = LoggerFactory.getLogger(CommandChecker.class);

    private AgvStatusResponse latestStatus;
    private SharedViewModel sharedViewModel;
    private LoraCommunicator loraCommunicator;
    private StatusWebSocketClient.StatusListener statusListener;
    private CommandWebSocketClient commandClient;
    private NavigationStateDebugger debugger;

    private final double TARGET_DISTANCE_THRESHOLD = 0.3;
    private final double PARK_DISTANCE_THRESHOLD = 1.0;
    private final double PRECHARGE_DISTANCE_THRESHOLD = 1.5;
    private final double ELEVATOR_DISTANCE_THRESHOLD = 0.4;
    private final double ELEVATOR_ANGLE_THRESHOLD = 0.1;
    private final double RELOCALIZE_POSITION_THRESHOLD = 0.2

            ;
    private final double RELOCALIZE_ANGLE_THRESHOLD = 0.2;
    private final double RELOCALIZE_TIMEOUT_THRESHOLD = 30000;
    private final int ELEVATOR_CANCEL_OPEN_THRESHOLD = 25000;
    private final long RECOGNIZE_JACK_TIMEOUT = 90000;  // 识别举升/卸载超时90秒
    private static final long RECOGNIZE_JACK_MIN_EXECUTION_MS = 3000;
    private static final int MAX_RECOGNIZE_JACK_RETRIES = 2;
    private ConcurrentHashMap<Long, ArrivalDoorCheckState> arrivalDoorCheckStates =
            new ConcurrentHashMap<>();


    public CommandChecker() {
        this.sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        this.loraCommunicator = sharedViewModel.loraCommunicator;
        this.debugger = NavigationStateDebugger.getInstance();

        if (debugger != null) {
            debugger.log("CommandChecker initialized");
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
        this.commandClient = sharedViewModel.getCommandClient();
    }

    /**
     * Check command completion status
     * @return 1 for completed, 0 for still executing, -1 for failed
     */
    public int checkCommand(CommandMonitor command) {
        long startTime = System.currentTimeMillis();

        if (debugger != null) {
            debugger.log(String.format("========== checkCommand START =========="));
            debugger.log(String.format("Command ID: %d, Type: %s",
                    command.getCommandId(), command.getCommandType()));
            debugger.log(String.format("Position ID: %d, Route ID: %d",
                    command.getPositionId(), command.getRouteId()));
            debugger.log(String.format("Start time: %d, Elapsed: %d ms",
                    command.getStartTime(), System.currentTimeMillis() - command.getStartTime()));
        }

        int result;
        switch (command.getCommandType()) {
            case OP_FREE_MOVE:
            case OP_FIXED_MOVE:
            case OP_FORWARD:
            case OP_BACKWARD:
            case OP_EXIT_SHELF:
                result = checkMoveCommand(command);
                break;
            case OP_RECOGNIZE_LOAD:
            case OP_RECOGNIZE_ENTRY_ONLY:
                result = checkRecognizeLoadCommand(command);
                break;
            case OP_RECOGNIZE_UNLOAD:
                result = checkRecognizeUnloadCommand(command);
                break;
            case OP_NON_RECOGNIZE_LOAD:
                result = checkUpForkCommand(command);
                break;
            case OP_NON_RECOGNIZE_UNLOAD:
                result = checkDownForkCommand(command);
                break;
            case OP_IN_ELEVATOR_MOVE:
                result = checkInElevatorMoveCommand(command);
                break;
            case OP_OUT_ELEVATOR_MOVE:
                result = checkOutElevatorMoveCommand(command);
                break;
            case OP_CHARGE_START:
                result = checkChargeStartCommand(command);
                break;

            case OP_CHARGE_STOP:
                result = checkChargeStopCommand(command);
                break;

            case OP_ELEVATOR_CALL:
                result = checkElevatorCallCommand(command);
                break;

            case OP_ELEVATOR_CHECK:
                result = checkElevatorCheckCommand(command);
                break;

            case OP_ELEVATOR_DOOR_CHECK:
                result = checkElevatorDoorCheckCommand(command);
                break;

            case OP_ELEVATOR_ARRIVAL_DOOR_CHECK:
                result = checkElevatorArrivalDoorCheckCommand(command);
                break;

            case OP_ELEVATOR_OPEN:
                result = checkElevatorOpenCommand(command);
                break;

            case OP_ELEVATOR_CANCEL_OPEN:
                result = checkElevatorCancelOpenCommand(command);
                break;

            case OP_ELEVATOR_QUERY_ACCESS:
                result = checkElevatorQueryAccessCommand(command);
                break;

            case OP_ELEVATOR_CLAIM_ACCESS:
                result = checkElevatorClaimAccessCommand(command);
                break;

            case OP_ELEVATOR_RELEASE_ACCESS:
                result = checkElevatorReleaseAccessCommand(command);
                break;

            case OP_AUTO_DOOR_OPEN:
                result = checkAutoDoorOpenCommand(command);
                break;

            case OP_AUTO_DOOR_CANCEL_OPEN:
                result = checkAutoDoorCancelOpenCommand(command);
                break;

            case OP_AUTO_DOOR_CHECK:
                result = checkAutoDoorCheckCommand(command);
                break;

            case OP_CHANGE_MAP:
                result = checkChangeMapCommand(command);
                break;

            case OP_RELOCALIZE:
                result = checkRelocalizeCommand(command);
                break;

            default:
                // For commands without async verification, assume completed after some time
                long elapsed = System.currentTimeMillis() - command.getStartTime();
                if (elapsed > 5000) {
                    if (debugger != null) {
                        debugger.log(String.format("Default timeout completion for command %d after %d ms",
                                command.getCommandId(), elapsed));
                    }
                    result = 1;
                } else {
                    result = 0;
                }
                break;
        }

        long elapsedMs = System.currentTimeMillis() - startTime;

        // 识别进入(OP_RECOGNIZE_ENTRY_ONLY)用 upFork 触发料腿识别，完成后立即降下叉子抵消举升
        if (result == 1 && command.getCommandType() == NavigationOrderType.OP_RECOGNIZE_ENTRY_ONLY) {
            LOG.info("OP_RECOGNIZE_ENTRY_ONLY completed, auto jackUnload() to cancel upFork lift");
            if (debugger != null) {
                debugger.log("OP_RECOGNIZE_ENTRY_ONLY completed -> auto jackUnload() to cancel lift");
            }
            commandClient.jackUnload();
        }

        if (debugger != null) {
            String resultStr = result == 1 ? "COMPLETED" : (result == -1 ? "FAILED" : "EXECUTING");
            debugger.log(String.format("Command %d result: %s (check took %d ms)",
                    command.getCommandId(), resultStr, elapsedMs));
            debugger.log("========== checkCommand END ==========");
        }

        return result;
    }

    private int checkMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkMoveCommand: Command ID=%d, Type=%s",
                    command.getCommandId(), command.getCommandType()));
        }

        // Log timestamp and status age
        long currentTime = System.currentTimeMillis();
        long statusAge = command.getLastStatusTime() > 0 ? currentTime - command.getLastStatusTime() : -1;

        if (debugger != null) {
            debugger.log(String.format("Status age: %d ms, last status time: %d",
                    statusAge, command.getLastStatusTime()));
        }

        // Use latest status to check goalFinish
        if (latestStatus != null && latestStatus.data != null) {
            // Update command's last status time
            command.setLastStatusTime(currentTime);

            // Get current position with detailed logging
            Position currentPos = sharedViewModel.getCurrentPosition();

            if (debugger != null) {
                debugger.log(String.format("Latest status - goalFinish=%d, errorCode=%s, poseProb=%.2f",
                        latestStatus.data.goalFinish,
                        latestStatus.data.errorCode != null ? latestStatus.data.errorCode : "none",
                        latestStatus.data.poseProbability));

                if (currentPos != null) {
                    debugger.log(String.format("Current position from status: (%.3f, %.3f, %.3f rad / %.1f°)",
                            currentPos.getPosX(), currentPos.getPosY(),
                            currentPos.getYaw(), Math.toDegrees(currentPos.getYaw())));
                } else {
                    debugger.logWarning("checkMoveCommand", "Current position is NULL despite having status data");
                }
            }

            if (latestStatus.data.goalFinish == 1) {
                Position target = command.getTarget();
                if (currentPos != null && target != null) {
                    double distance = calculateDistance(currentPos, target);
                    double orientationDiff = calculateAngleDifferenceRad(currentPos.getYaw(), target.getYaw());

                    if (debugger != null) {
                        debugger.log(String.format("Target position: (%.3f, %.3f, %.3f rad / %.1f°)",
                                target.getPosX(), target.getPosY(),
                                target.getYaw(), Math.toDegrees(target.getYaw())));
                        debugger.log(String.format("Position delta: dX=%.3f, dY=%.3f",
                                currentPos.getPosX() - target.getPosX(),
                                currentPos.getPosY() - target.getPosY()));
                        debugger.log(String.format("Distance: %.5f, Threshold: %.5f",
                                distance, TARGET_DISTANCE_THRESHOLD));
                        debugger.log(String.format("Orientation diff: %.3f rad (%.1f°), Threshold: %.3f rad (%.1f°)",
                                orientationDiff, Math.toDegrees(orientationDiff),
                                ELEVATOR_ANGLE_THRESHOLD, Math.toDegrees(ELEVATOR_ANGLE_THRESHOLD)));
                    }

                    if (distance < getTargetDistanceThreshold(target)) {
                        if (debugger != null) {
                            debugger.log(String.format("✅ Move command %d COMPLETED - reached target within %.5f",
                                    command.getCommandId(), distance));
                            debugger.log(String.format("   Final position: (%.3f, %.3f), Target: (%.3f, %.3f)",
                                    currentPos.getPosX(), currentPos.getPosY(),
                                    target.getPosX(), target.getPosY()));
                        }
                        TaskDebug1.log(String.format("[MOVE] COMPLETE cmdId=%d dist=%.3f threshold=%.3f targetType=%d goalFinish=1",
                                command.getCommandId(), distance, getTargetDistanceThreshold(target), target.getType()));
                        return 1; // Completed
                    } else {
                        TaskDebug1.logThrottled("move_stuck_" + command.getCommandId(), 3000,
                                String.format("[MOVE] STUCK cmdId=%d goalFinish=1 dist=%.3f threshold=%.3f targetType=%d target=(%.3f,%.3f) cur=(%.3f,%.3f)",
                                        command.getCommandId(), distance, getTargetDistanceThreshold(target),
                                        target.getType(), target.getPosX(), target.getPosY(),
                                        currentPos.getPosX(), currentPos.getPosY()));
                        TaskDebug8.logThrottled("move_stuck8_" + command.getCommandId(), 3000,
                                String.format("[MOVE] STUCK cmdId=%d posId=%d goalFinish=1 dist=%.3f threshold=%.3f target=%s targetType=%d tt=%d target=(%.3f,%.3f) cur=(%.3f,%.3f) floor=%s map=%s err=%s",
                                        command.getCommandId(), command.getPositionId(), distance,
                                        getTargetDistanceThreshold(target),
                                        target.getName(), target.getType(), target.getTaskType(),
                                        target.getPosX(), target.getPosY(),
                                        currentPos.getPosX(), currentPos.getPosY(),
                                        target.getFloor(), target.getMapName(),
                                        latestStatus.data.errorCode != null ? latestStatus.data.errorCode : "none"));
                        if (debugger != null) {
                            debugger.log(String.format("Move command %d: goalFinish=1 but distance=%.5f > threshold (%.5f), waiting",
                                    command.getCommandId(), distance, TARGET_DISTANCE_THRESHOLD));
                            debugger.log(String.format("   Position error: %.5f (need < %.5f)",
                                    distance, TARGET_DISTANCE_THRESHOLD));
                        }
                    }
                } else {
                    if (debugger != null) {
                        debugger.logWarning("checkMoveCommand",
                                String.format("Command %d: goalFinish=1 but position or target is null (currentPos=%s, target=%s)",
                                        command.getCommandId(),
                                        currentPos != null ? "valid" : "null",
                                        command.getTarget() != null ? "valid" : "null"));
                    }
                }
            } else if (latestStatus.data.goalFinish == 0) {
                Position target = command.getTarget();
                if (currentPos != null && target != null) {
                    double distance = calculateDistance(currentPos, target);
                    TaskDebug8.logThrottled("move_progress_" + command.getCommandId(), 3000,
                            String.format("[MOVE] PROGRESS cmdId=%d posId=%d goalFinish=0 dist=%.3f target=%s targetType=%d tt=%d cur=(%.3f,%.3f)",
                                    command.getCommandId(), command.getPositionId(), distance,
                                    target.getName(), target.getType(), target.getTaskType(),
                                    currentPos.getPosX(), currentPos.getPosY()));
                }
                if (debugger != null) {
                    debugger.log(String.format("Move command %d: still executing (goalFinish=0)", command.getCommandId()));
                    // Log position even when not at goal to show movement progress
                    if (currentPos != null && target != null) {
                        double distance = calculateDistance(currentPos, target);
                        debugger.log(String.format("   Current distance to target: %.5f", distance));
                    }
                }
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Move command %d: unexpected goalFinish value: %d",
                            command.getCommandId(), latestStatus.data.goalFinish));
                }
            }
        } else {
            if (debugger != null) {
                debugger.logWarning("checkMoveCommand",
                        String.format("No status data available for command %d (latestStatus=%s)",
                                command.getCommandId(), latestStatus != null ? "exists but data is null" : "null"));
            }
        }
        return 0; // Still executing
    }

    private double getTargetDistanceThreshold(Position target) {
        if (target != null && target.getType() == 12) {
            return PARK_DISTANCE_THRESHOLD;
        }
        if (target != null && target.getType() == 11) {
            return PRECHARGE_DISTANCE_THRESHOLD;
        }
        return TARGET_DISTANCE_THRESHOLD;
    }

    /**
     * Shared completion logic for recognize load/unload jack commands.
     */
    private int checkRecognizeJackCommand(CommandMonitor command, String commandLabel) {
        if (debugger != null) {
            debugger.log(String.format("check%s: Command ID=%d", commandLabel, command.getCommandId()));
        }

        long elapsed = System.currentTimeMillis() - command.getStartTime();

        if (latestStatus == null || latestStatus.data == null) {
            if (debugger != null) {
                debugger.logWarning("check" + commandLabel,
                        String.format("No status data available for command %d", command.getCommandId()));
            }
            return 0;
        }

        int currentGoalFinish = latestStatus.data.goalFinish;

        if (command.getPreviousGoalFinish() == CommandMonitor.GOAL_FINISH_UNINITIALIZED) {
            command.setPreviousGoalFinish(currentGoalFinish);
            if (currentGoalFinish == 1) {
                command.setAwaitingGoalFinishCycle(true);
            }
            TaskDebug1.log(String.format("[JACK] %s baseline cmdId=%d goalFinish=%d awaitingCycle=%b elapsed=%dms",
                    commandLabel, command.getCommandId(), currentGoalFinish,
                    command.isAwaitingGoalFinishCycle(), elapsed));
            if (debugger != null) {
                debugger.log(String.format("%s command %d: baseline goalFinish=%d captured (elapsed=%d ms, awaitingCycle=%b)",
                        commandLabel, command.getCommandId(), currentGoalFinish, elapsed,
                        command.isAwaitingGoalFinishCycle()));
            }
            return 0;
        }

        if (command.isAwaitingGoalFinishCycle()) {
            if (currentGoalFinish == 0) {
                command.setAwaitingGoalFinishCycle(false);
                command.setPreviousGoalFinish(0);
                TaskDebug1.log(String.format("[JACK] %s chassis started cmdId=%d goalFinish 1->0",
                        commandLabel, command.getCommandId()));
            } else {
                command.setPreviousGoalFinish(currentGoalFinish);
                TaskDebug1.logThrottled("jack_cycle_" + command.getCommandId(), 3000,
                        String.format("[JACK] %s awaiting cycle cmdId=%d goalFinish=%d elapsed=%dms",
                                commandLabel, command.getCommandId(), currentGoalFinish, elapsed));
                if (elapsed > RECOGNIZE_JACK_TIMEOUT) {
                    if (debugger != null) {
                        debugger.logError("check" + commandLabel,
                                String.format("❌ %s command %d: timeout waiting for goalFinish cycle (goalFinish=%d)",
                                        commandLabel, command.getCommandId(), currentGoalFinish));
                    }
                    return -1;
                }
                return 0;
            }
        }

        int prevGoalFinish = command.getPreviousGoalFinish();

        // 稳定完成：goalFinish=1 且已执行足够时间且到达目标（防止漏检 0→1 跳变）
        if (currentGoalFinish == 1 && elapsed >= RECOGNIZE_JACK_MIN_EXECUTION_MS) {
            Position currentPos = sharedViewModel.getCurrentPosition();
            Position target = command.getTarget();
            double threshold = getTargetDistanceThreshold(target);
            if (currentPos != null && target != null) {
                double distance = calculateDistance(currentPos, target);
                if (distance < threshold) {
                    TaskDebug1.log(String.format("[JACK] %s COMPLETE cmdId=%d stable goalFinish=1 dist=%.3f elapsed=%dms",
                            commandLabel, command.getCommandId(), distance, elapsed));
                    return 1;
                }
            } else if (elapsed >= RECOGNIZE_JACK_MIN_EXECUTION_MS) {
                TaskDebug1.log(String.format("[JACK] %s COMPLETE cmdId=%d stable goalFinish=1 (no pos check) elapsed=%dms",
                        commandLabel, command.getCommandId(), elapsed));
                return 1;
            }
        }

        if (currentGoalFinish == 1 && prevGoalFinish != 1) {
            Position currentPos = sharedViewModel.getCurrentPosition();
            Position target = command.getTarget();
            double threshold = getTargetDistanceThreshold(target);
            if (currentPos != null && target != null) {
                double distance = calculateDistance(currentPos, target);
                if (distance < threshold) {
                    if (debugger != null) {
                        debugger.log(String.format("✅ %s command %d COMPLETED (goalFinish %d→1 transition, distance=%.3f)",
                                commandLabel, command.getCommandId(), prevGoalFinish, distance));
                    }
                    TaskDebug1.log(String.format("[JACK] %s COMPLETE cmdId=%d transition %d->1 dist=%.3f",
                            commandLabel, command.getCommandId(), prevGoalFinish, distance));
                    return 1;
                }
                TaskDebug1.logThrottled("jack_dist_" + command.getCommandId(), 3000,
                        String.format("[JACK] %s transition %d->1 but dist=%.3f >= threshold=%.3f cmdId=%d",
                                commandLabel, prevGoalFinish, distance, threshold, command.getCommandId()));
            } else {
                if (debugger != null) {
                    debugger.log(String.format("✅ %s command %d COMPLETED (goalFinish %d→1 transition, no position check)",
                            commandLabel, command.getCommandId(), prevGoalFinish));
                }
                TaskDebug1.log(String.format("[JACK] %s COMPLETE cmdId=%d transition %d->1 (no pos check)",
                        commandLabel, command.getCommandId(), prevGoalFinish));
                return 1;
            }
        } else if (currentGoalFinish == -1) {
            if (debugger != null) {
                debugger.logError("check" + commandLabel,
                        String.format("❌ %s command %d: chassis error (goalFinish=-1)", commandLabel, command.getCommandId()));
            }
            return -1;
        }

        if (elapsed > RECOGNIZE_JACK_TIMEOUT) {
            if (debugger != null) {
                debugger.logError("check" + commandLabel,
                        String.format("❌ %s command %d: timeout after %d ms (goalFinish=%d, prev=%d)",
                                commandLabel, command.getCommandId(), elapsed, currentGoalFinish, prevGoalFinish));
            }
            TaskDebug1.log(String.format("[JACK] %s TIMEOUT cmdId=%d elapsed=%dms goalFinish=%d prev=%d awaitingCycle=%b",
                    commandLabel, command.getCommandId(), elapsed, currentGoalFinish, prevGoalFinish,
                    command.isAwaitingGoalFinishCycle()));
            return -1;
        }

        command.setPreviousGoalFinish(currentGoalFinish);

        TaskDebug1.logThrottled("jack_run_" + command.getCommandId(), 3000,
                String.format("[JACK] %s running cmdId=%d goalFinish=%d prev=%d elapsed=%dms",
                        commandLabel, command.getCommandId(), currentGoalFinish, prevGoalFinish, elapsed));

        if (debugger != null) {
            debugger.log(String.format("%s command %d: still executing (goalFinish=%d, prev=%d, elapsed=%d ms)",
                    commandLabel, command.getCommandId(), currentGoalFinish, prevGoalFinish, elapsed));
        }
        return 0;
    }

    /**
     * Check recognize load command (识别举升) completion using goalFinish state transition tracking.
     * No blind delay — instead tracks when goalFinish transitions from non-1 to 1,
     * which means the chassis has actually completed THIS command (not a stale value
     * from a previous task). Returns -1 on timeout (30s) or chassis error to trigger retry.
     */
    private int checkRecognizeLoadCommand(CommandMonitor command) {
        return checkRecognizeJackCommand(command, "RecognizeLoad");
    }

    /**
     * Check recognize unload command (识别卸载) completion using goalFinish state transition tracking.
     * Same logic as checkRecognizeLoadCommand: no blind delay, tracks non-1→1 transition.
     */
    private int checkRecognizeUnloadCommand(CommandMonitor command) {
        return checkRecognizeJackCommand(command, "RecognizeUnload");
    }

    /**
     * 非识别顶升/放下共用完成判定。
     * 修复「跳过第一个点」：goalFinish=1 不代表已到达目标（充电场景下机器人还在充电位时
     * 曾出现 dist=1.66m 被误判完成）。必须同时满足 dist < threshold 才算完成。
     * 自愈：若 goalFinish=1 但距离始终未缩小（底盘未真正执行导航），15 秒后重发导航命令，最多 2 次。
     */
    private int checkNonRecognizeForkCommand(CommandMonitor command, String label) {
        if (debugger != null) {
            debugger.log(String.format("check%sCommand: Command ID=%d", label, command.getCommandId()));
        }

        long now = System.currentTimeMillis();
        long elapsed = now - command.getStartTime();
        if (command.getStartTime() > 0 && elapsed < 5000) {
            if (debugger != null) {
                debugger.log(String.format("%s command %d: waiting before status check (elapsed=%d ms)",
                        label, command.getCommandId(), elapsed));
            }
            return 0;
        }

        if (latestStatus == null || latestStatus.data == null) {
            if (debugger != null) {
                debugger.logWarning("check" + label + "Command",
                        String.format("No status data available for command %d", command.getCommandId()));
            }
            return 0;
        }

        if (latestStatus.data.goalFinish != 1) {
            // 底盘执行中，清除卡死计时
            command.putExtraData("forkStuckSince", null);
            if (debugger != null) {
                debugger.log(String.format("%s command %d: still executing (goalFinish=%d)",
                        label, command.getCommandId(), latestStatus.data.goalFinish));
            }
            return 0;
        }

        Position currentPos = sharedViewModel != null ? sharedViewModel.getCurrentPosition() : null;
        Position target = command.getTarget();
        if (currentPos == null || target == null) {
            if (debugger != null) {
                debugger.logWarning("check" + label + "Command",
                        String.format("Command %d: goalFinish=1 but position unavailable (pos=%s, target=%s)",
                                command.getCommandId(),
                                currentPos != null ? "ok" : "null",
                                target != null ? "ok" : "null"));
            }
            return 0;
        }

        double distance = calculateDistance(currentPos, target);
        double threshold = getTargetDistanceThreshold(target);

        if (distance < threshold) {
            if (debugger != null) {
                debugger.log(String.format("✅ %s command %d COMPLETED - goalFinish=1, distance=%.3f < %.3f",
                        label, command.getCommandId(), distance, threshold));
            }
            TaskDebug1.log(String.format("[SKIP1ST] %s COMPLETE cmdId=%d target=%s dist=%.3f threshold=%.3f elapsed=%dms",
                    label, command.getCommandId(), target.getName(), distance, threshold, elapsed));
            return 1;
        }

        // goalFinish=1 但未到达：可能是残留状态或底盘未执行本次导航
        TaskDebug1.logThrottled("fork_wait_" + command.getCommandId(), 3000,
                String.format("[SKIP1ST] %s WAIT dist=%.3f threshold=%.3f cmdId=%d goalFinish=1 elapsed=%dms",
                        label, distance, threshold, command.getCommandId(), elapsed));

        Object stuckSinceObj = command.getExtraData("forkStuckSince");
        long stuckSince = stuckSinceObj instanceof Long ? (Long) stuckSinceObj : 0L;
        if (stuckSince == 0L) {
            command.putExtraData("forkStuckSince", now);
            return 0;
        }

        if (now - stuckSince >= 15000) {
            Object resendObj = command.getExtraData("forkResendCount");
            int resendCount = resendObj instanceof Integer ? (Integer) resendObj : 0;
            if (resendCount >= 2) {
                TaskDebug1.logThrottled("fork_giveup_" + command.getCommandId(), 10000,
                        String.format("[SKIP1ST] %s STUCK resend exhausted cmdId=%d dist=%.3f",
                                label, command.getCommandId(), distance));
                return 0;
            }
            String action = command.getCommandType() == NavigationOrderType.OP_NON_RECOGNIZE_LOAD
                    ? "upFork" : "downFork";
            boolean sent = commandClient != null && commandClient.navigateToEventPosition(
                    target.getPosX(), target.getPosY(), target.getYaw(),
                    target.getSpeed(), target.getId(), action);
            command.putExtraData("forkResendCount", resendCount + 1);
            command.putExtraData("forkStuckSince", now);
            TaskDebug1.log(String.format(
                    "[SKIP1ST] %s RESEND #%d cmdId=%d dist=%.3f sent=%b (goalFinish=1 but robot not moving)",
                    label, resendCount + 1, command.getCommandId(), distance, sent));
            if (debugger != null) {
                debugger.logWarning("check" + label + "Command",
                        String.format("Command %d stuck at dist=%.3f, resent navigation (#%d)",
                                command.getCommandId(), distance, resendCount + 1));
            }
        }
        return 0;
    }

    private int checkDownForkCommand(CommandMonitor command) {
        return checkNonRecognizeForkCommand(command, "DownFork");
    }

    private int checkUpForkCommand(CommandMonitor command) {
        return checkNonRecognizeForkCommand(command, "UpFork");
    }

    private int checkInElevatorMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkInElevatorMoveCommand: Command ID=%d", command.getCommandId()));
        }

        // Check if we have valid command data
        if (command.getPreridePoint() == null || command.getTarget() == null) {
            if (debugger != null) {
                debugger.logError("checkInElevatorMoveCommand",
                        String.format("Command %d missing preride or target", command.getCommandId()));
            }
            return -1;
        }

        // Get current position (via navigation module) and goal finish state
        Position currentPos = sharedViewModel.getCurrentPosition();
        int getGoalFinish = sharedViewModel.getGoalFinish();
        if (currentPos == null) {
            if (debugger != null) {
                debugger.log(String.format("Command %d: no current position available", command.getCommandId()));
            }
            return 0;
        }

        // Get the current waypoint index from command state (default to 0 = start with first waypoint)
        int currentWaypoint = command.getCurrentWaypointIndex();
        if (currentWaypoint == -1) {
            currentWaypoint = 0;
        }

        // Define waypoints for IN_ELEVATOR_MOVE (waypoints 2, 3, 4 from original)
        Position preride = command.getPreridePoint();
        Position target = command.getTarget();

        // Waypoint 2: Preride with current orientation
        Position waypoint2 = new Position(
                preride.getId(),
                preride.getName() + "_oriented",
                preride.getPosX(),
                preride.getPosY(),
                currentPos.getYaw()  // Use current's orientation
        );

        // Waypoint 3: Preride with target orientation
        Position waypoint3 = new Position(
                preride.getId(),
                preride.getName() + "_oriented",
                preride.getPosX(),
                preride.getPosY(),
                target.getYaw()  // Use target's orientation
        );

        // Waypoint 4: Target
        Position waypoint4 = target;

        Position[] waypoints = {waypoint2, waypoint3, waypoint4};
        String[] waypointNames = {"Preride (oriented with current)", "Preride (oriented with target)", "Target"};

        // Check current waypoint
        if (currentWaypoint < waypoints.length) {
            Position targetWaypoint = waypoints[currentWaypoint];

            if (isAtPosition(currentPos, targetWaypoint)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Command %d reached waypoint %d: %s",
                            command.getCommandId(), currentWaypoint + 2, waypointNames[currentWaypoint]));
                }

                // Move to next waypoint
                currentWaypoint++;
                command.setCurrentWaypointIndex(currentWaypoint);
                CommandMonitor.updateCommand(command);

                // Check if we've completed all waypoints
                if (currentWaypoint >= waypoints.length && getGoalFinish == 0) {
                    if (debugger != null) {
                        debugger.log(String.format("✅ IN_ELEVATOR_MOVE command %d COMPLETED - all waypoints reached",
                                command.getCommandId()));
                    }
                    return 1;
                }
            } else {
                if (debugger != null) {
                    double distance = calculateDistance(currentPos, targetWaypoint);
                    debugger.log(String.format("Command %d: moving to waypoint %d (%s), distance=%.3f",
                            command.getCommandId(), currentWaypoint + 2, waypointNames[currentWaypoint], distance));
                }
            }
        }

        return 0;
    }

    private int checkOutElevatorMoveCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkOutElevatorMoveCommand: Command ID=%d", command.getCommandId()));
        }

        // Check if we have valid command data
        if (command.getPreridePoint() == null) {
            if (debugger != null) {
                debugger.logError("checkOutElevatorMoveCommand",
                        String.format("Command %d missing preride point", command.getCommandId()));
            }
            return -1;
        }

        // Get current position
        Position currentPos = getCurrentPosition();
        if (currentPos == null) {
            if (debugger != null) {
                debugger.log(String.format("Command %d: no current position available", command.getCommandId()));
            }
            return 0;
        }

        // Get the current waypoint index from command state
        int currentWaypoint = command.getCurrentWaypointIndex();
        if (currentWaypoint == -1) {
            currentWaypoint = 0;
        }

        Position preride = command.getPreridePoint();
        Position ride = command.getRidePoint();

        // Define waypoints for CHECKING only (exclude current position)
        java.util.List<Position> waypointsToCheck = new java.util.ArrayList<>();
        java.util.List<String> waypointNames = new java.util.ArrayList<>();
        java.util.List<Boolean> isRotationOnlyList = new java.util.ArrayList<>();

        if (command.isExceptionRecovery()) {
            // Exception Recovery Mode - only add the target waypoints (not current position)
            Position currentPosForCheck = getCurrentPosition();
            if (currentPosForCheck != null) {
                double currentYawDeg = Math.toDegrees(currentPosForCheck.getYaw());
                double targetYawDeg = Math.toDegrees(preride.getYaw());
                double orientationDiff = Math.abs(currentYawDeg - targetYawDeg);
                orientationDiff = Math.min(orientationDiff, 360 - orientationDiff);

                if (debugger != null) {
                    debugger.log(String.format("Exception Recovery - orientation diff: %.2f°", orientationDiff));
                }

                if (orientationDiff > 10.0) {
                    // Three-waypoint scenario in sender:
                    // Waypoint 1: current position (skip in checker)
                    // Waypoint 2: preride with current orientation (CHECK THIS)
                    // Waypoint 3: preride with target orientation (CHECK THIS - rotation)

                    // Waypoint to check #1: preride with current orientation
                    Position secondWaypoint = new Position(
                            preride.getId(),
                            preride.getName() + "_with_current_orientation",
                            preride.getPosX(),
                            preride.getPosY(),
                            currentPosForCheck.getYaw()
                    );
                    waypointsToCheck.add(secondWaypoint);
                    waypointNames.add("Preride with current orientation");
                    isRotationOnlyList.add(false);

                    // Waypoint to check #2: preride with target orientation (rotation-only)
                    Position thirdWaypoint = new Position(
                            preride.getId(),
                            preride.getName() + "_with_target_orientation",
                            preride.getPosX(),
                            preride.getPosY(),
                            preride.getYaw()
                    );
                    waypointsToCheck.add(thirdWaypoint);
                    waypointNames.add("Preride with target orientation (rotate in place)");
                    isRotationOnlyList.add(true);
                } else {
                    // Two-waypoint scenario in sender:
                    // Waypoint 1: current position (skip in checker)
                    // Waypoint 2: preride with target orientation (CHECK THIS)

                    // Waypoint to check: preride with target orientation
                    Position directWaypoint = new Position(
                            preride.getId(),
                            preride.getName() + "_oriented",
                            preride.getPosX(),
                            preride.getPosY(),
                            preride.getYaw()
                    );
                    waypointsToCheck.add(directWaypoint);
                    waypointNames.add("Preride (direct)");
                    isRotationOnlyList.add(false);
                }
            } else {
                // Fallback if can't get current position
                Position fallbackWaypoint = new Position(
                        preride.getId(),
                        preride.getName() + "_oriented",
                        preride.getPosX(),
                        preride.getPosY(),
                        preride.getYaw()
                );
                waypointsToCheck.add(fallbackWaypoint);
                waypointNames.add("Preride (fallback)");
                isRotationOnlyList.add(false);
            }
        } else {
            // Normal Mode waypoints (ride point -> preride point)
            if (ride != null) {
                Position rideWithPrerideOrientation = new Position(
                        ride.getId(),
                        ride.getName() + "_with_preride_orientation",
                        ride.getPosX(),
                        ride.getPosY(),
                        preride.getYaw()
                );
                waypointsToCheck.add(rideWithPrerideOrientation);
                waypointNames.add("Ride with preride orientation");
                isRotationOnlyList.add(false);
            }

            Position prerideOriented = new Position(
                    preride.getId(),
                    preride.getName() + "_oriented",
                    preride.getPosX(),
                    preride.getPosY(),
                    preride.getYaw()
            );
            waypointsToCheck.add(prerideOriented);
            waypointNames.add("Preride (final)");
            isRotationOnlyList.add(false);
        }

        // Check if we've already completed all waypoints
        if (currentWaypoint >= waypointsToCheck.size()) {
            if (debugger != null) {
                debugger.log(String.format("✅ OUT_ELEVATOR_MOVE command %d COMPLETED - all waypoints reached",
                        command.getCommandId()));
            }
            return 1;
        }

        if (debugger != null) {
            debugger.log(String.format("Command %d: Checking waypoint %d of %d (%s)",
                    command.getCommandId(), currentWaypoint + 1, waypointsToCheck.size(),
                    currentWaypoint < waypointNames.size() ? waypointNames.get(currentWaypoint) : "unknown"));
        }

        // Check current waypoint
        Position targetWaypoint = waypointsToCheck.get(currentWaypoint);
        boolean isRotationOnly = isRotationOnlyList.get(currentWaypoint);

        if (isRotationOnly) {
            // This is a rotation-only waypoint (same coordinates, different orientation)
            double distance = calculateDistance(currentPos, targetWaypoint);

            if (debugger != null) {
                debugger.log(String.format("Rotation-only waypoint: distance=%.3f, orientation diff=%.2f°",
                        distance, Math.toDegrees(Math.abs(currentPos.getYaw() - targetWaypoint.getYaw()))));
            }

            // Check if orientation is close enough (within 5 degrees)
            double orientationDiff = Math.abs(currentPos.getYaw() - targetWaypoint.getYaw());
            if (orientationDiff < Math.toRadians(5.0)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Command %d reached rotation target (orientation diff=%.2f°)",
                            command.getCommandId(), Math.toDegrees(orientationDiff)));
                }

                // Move to next waypoint
                currentWaypoint++;
                command.setCurrentWaypointIndex(currentWaypoint);
                CommandMonitor.updateCommand(command);

                // Check if we've completed all waypoints
                if (currentWaypoint >= waypointsToCheck.size()) {
                    if (debugger != null) {
                        debugger.log(String.format("✅ OUT_ELEVATOR_MOVE command %d COMPLETED - all waypoints reached",
                                command.getCommandId()));
                    }
                    return 1;
                }
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Command %d: rotating to target orientation, diff=%.2f°",
                            command.getCommandId(), Math.toDegrees(orientationDiff)));
                }
            }
        } else {
            // Normal position/orientation check
            if (isAtPosition(currentPos, targetWaypoint)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Command %d reached waypoint %d: %s",
                            command.getCommandId(), currentWaypoint + 1, waypointNames.get(currentWaypoint)));
                }

                // Move to next waypoint
                currentWaypoint++;
                command.setCurrentWaypointIndex(currentWaypoint);
                CommandMonitor.updateCommand(command);

                // Check if we've completed all waypoints
                if (currentWaypoint >= waypointsToCheck.size()) {
                    if (debugger != null) {
                        debugger.log(String.format("✅ OUT_ELEVATOR_MOVE command %d COMPLETED - all waypoints reached",
                                command.getCommandId()));
                    }
                    return 1;
                }
            } else {
                if (debugger != null) {
                    double distance = calculateDistance(currentPos, targetWaypoint);
                    double orientationDiff = Math.toDegrees(Math.abs(currentPos.getYaw() - targetWaypoint.getYaw()));
                    debugger.log(String.format("Command %d: moving to waypoint %d (%s), distance=%.3f, orientation diff=%.2f°",
                            command.getCommandId(), currentWaypoint + 1, waypointNames.get(currentWaypoint),
                            distance, orientationDiff));
                }
            }
        }

        return 0;
    }

    /**
     * Check if robot is at a specific position (within threshold)
     */
    private boolean isAtPosition(Position currentPos, Position targetPos) {
        if (currentPos == null || targetPos == null) {
            return false;
        }

        double distance = calculateDistance(currentPos, targetPos);
        boolean isWithinDistance = distance < ELEVATOR_DISTANCE_THRESHOLD;

        if (debugger != null && !isWithinDistance) {
            debugger.log(String.format("Distance check failed: distance=%.3f, threshold=%.3f",
                    distance, ELEVATOR_DISTANCE_THRESHOLD));
        }

        // Check orientation if this is an oriented point
        boolean isOrientationMatch = true;
        if (targetPos.getName() != null && targetPos.getName().contains("oriented")) {
            double angleDiff = calculateAngleDifferenceRad(currentPos.getYaw(), targetPos.getYaw());
            isOrientationMatch = angleDiff < ELEVATOR_ANGLE_THRESHOLD;

            if (debugger != null && !isOrientationMatch) {
                debugger.log(String.format("Orientation check failed: current=%.3f rad (%.1f°), target=%.3f rad (%.1f°), diff=%.3f rad (%.1f°), threshold=%.3f rad (%.1f°)",
                        currentPos.getYaw(), Math.toDegrees(currentPos.getYaw()),
                        targetPos.getYaw(), Math.toDegrees(targetPos.getYaw()),
                        angleDiff, Math.toDegrees(angleDiff),
                        ELEVATOR_ANGLE_THRESHOLD, Math.toDegrees(ELEVATOR_ANGLE_THRESHOLD)));
            } else if (debugger != null && isOrientationMatch) {
                debugger.log(String.format("Orientation check passed: diff=%.3f rad (%.1f°)",
                        angleDiff, Math.toDegrees(angleDiff)));
            }
        }

        boolean result = isWithinDistance && isOrientationMatch;

        if (debugger != null && result) {
            debugger.log(String.format("Position check PASSED for target: %s (distance=%.3f, orientation match=%b)",
                    targetPos.getName(), distance, isOrientationMatch));
        }

        return result;
    }

    private int checkChargeStartCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkChargeStartCommand: Command ID=%d", command.getCommandId()));
        }

        if (latestStatus != null && latestStatus.data != null) {
            int moveCheck = checkMoveCommand(command);

            if (debugger != null) {
                debugger.log(String.format("Charge start - moveCheck=%d, chargeState=%d",
                        moveCheck, latestStatus.data.chargeStatus != null ? latestStatus.data.chargeStatus.state : -999));
            }

            if (latestStatus.data.chargeStatus != null &&
                    latestStatus.data.chargeStatus.state == 100) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Charge start command %d COMPLETED - fully charged", command.getCommandId()));
                }
                return 1; // Fully charged
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Charge start command %d still executing", command.getCommandId()));
                }
                return 0;
            }
        }

        if (debugger != null) {
            debugger.logWarning("checkChargeStartCommand",
                    String.format("No status data for command %d", command.getCommandId()));
        }
        return 0;
    }

    private int checkChargeStopCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkChargeStopCommand: Command ID=%d", command.getCommandId()));
        }

        if (latestStatus != null && latestStatus.data != null) {
            int moveCheck = checkMoveCommand(command);
            boolean chargingStopped = isChargingStopped(latestStatus);

            if (debugger != null) {
                debugger.log(String.format("Charge stop - moveCheck=%d, chargeState=%d, current=%.2f",
                        moveCheck,
                        latestStatus.data.chargeStatus != null ? latestStatus.data.chargeStatus.state : -999,
                        latestStatus.data.electricCurrentIn));
            }

            if (moveCheck == 1 && chargingStopped) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Charge stop command %d COMPLETED - reached precharge and charging stopped",
                            command.getCommandId()));
                }
                TaskDebug1.log(String.format("[SKIP1ST] PreCharge COMPLETE path=move+stop cmdId=%d moveCheck=%d chargeState=%d current=%.2f goalFinish=%d",
                        command.getCommandId(), moveCheck,
                        latestStatus.data.chargeStatus != null ? latestStatus.data.chargeStatus.state : -999,
                        latestStatus.data.electricCurrentIn,
                        latestStatus.data.goalFinish));
                return 1;
            }

            // Fallback: charging already stopped and navigation finished near precharge target.
            Position target = command.getTarget();
            if (chargingStopped
                    && latestStatus.data.goalFinish == 1
                    && target != null
                    && target.getType() == 11) {
                Position currentPos = sharedViewModel != null ? sharedViewModel.getCurrentPosition() : null;
                if (currentPos != null) {
                    double distance = calculateDistance(currentPos, target);
                    if (distance < getTargetDistanceThreshold(target)) {
                        if (debugger != null) {
                            debugger.log(String.format(
                                    "✅ Charge stop command %d COMPLETED - charging stopped near precharge (dist=%.3f)",
                                    command.getCommandId(), distance));
                        }
                        TaskDebug1.log(String.format("[SKIP1ST] PreCharge COMPLETE path=fallback cmdId=%d dist=%.3f threshold=%.3f target=%s chargeState=%d current=%.2f goalFinish=%d",
                                command.getCommandId(), distance, getTargetDistanceThreshold(target),
                                target.getName(),
                                latestStatus.data.chargeStatus != null ? latestStatus.data.chargeStatus.state : -999,
                                latestStatus.data.electricCurrentIn,
                                latestStatus.data.goalFinish));
                        return 1;
                    }
                }
            }

            if (moveCheck == 1 || chargingStopped) {
                if (debugger != null) {
                    debugger.log(String.format("Charge stop command %d still executing", command.getCommandId()));
                }
                return 0;
            }
        }

        if (debugger != null) {
            debugger.logWarning("checkChargeStopCommand",
                    String.format("No status data for command %d", command.getCommandId()));
        }
        return 0;
    }

    private boolean isChargingStopped(AgvStatusResponse status) {
        if (status == null || status.data == null) {
            return false;
        }
        if (status.data.chargeStatus != null && status.data.chargeStatus.state == -1) {
            return true;
        }
        return status.data.electricCurrentIn <= 3;
    }

    private int checkElevatorCallCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorCallCommand: Command ID=%d", command.getCommandId()));
        }

        long now = System.currentTimeMillis();

        Object sendCountObj = command.getExtraData("openSendCount");
        Object lastSendObj = command.getExtraData("lastSendTime");

        int sendCount = (sendCountObj instanceof Integer) ? (Integer) sendCountObj : 1;
        long lastSendTime = (lastSendObj instanceof Long) ? (Long) lastSendObj : command.getStartTime();

        if (debugger != null) {
            debugger.log(String.format("Elevator open command %d: sent %d/10 times, last send %d ms ago",
                    command.getCommandId(), sendCount, now - lastSendTime));
        }

        // Check elevator status via LoRa or other means
        // For now, assume success after timeout
        if (sendCount >= 2) {
            if (debugger != null) {
                debugger.log(String.format("✅ Elevator open command %d COMPLETED after %d sends",
                        command.getCommandId(), sendCount));
            }
            return 1;
        }

        long elapsedSinceLastSend = now - lastSendTime;
        if (elapsedSinceLastSend >= 400) {
            LoraCommand loraCmd = command.getLoraCommand();
            if (loraCmd != null && loraCmd.getDeviceCommand() != null) {
                try {
                    if (debugger != null) {
                        debugger.log(String.format("Resending elevator open command %d (send %d/10)",
                                command.getCommandId(), sendCount + 1));
                    }
                    loraCommunicator.sendMessage(loraCmd.getDeviceCommand(), true);

                    sendCount++;
                    command.putExtraData("openSendCount", sendCount);
                    command.putExtraData("lastSendTime", now);

                    if (debugger != null) {
                        debugger.log(String.format("Elevator open resend %d/10 complete for command %d",
                                sendCount, command.getCommandId()));
                    }
                } catch (IOException | TimeoutException e) {
                    if (debugger != null) {
                        debugger.logWarning("checkElevatorOpenCommand",
                                String.format("Failed to resend elevator open for command %d: %s",
                                        command.getCommandId(), e.getMessage()));
                    }
                }
            } else {
                if (debugger != null) {
                    debugger.logWarning("checkElevatorOpenCommand",
                            String.format("No LoraCommand for command %d", command.getCommandId()));
                }
            }

            if (sendCount >= 1) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Elevator open command %d COMPLETED after %d sends",
                            command.getCommandId(), sendCount));
                }
                return 1;
            }
        }

        return 0;
    }

    private int checkElevatorOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorOpenCommand: Command ID=%d", command.getCommandId()));
        }

        long now = System.currentTimeMillis();

        Object sendCountObj = command.getExtraData("openSendCount");
        Object lastSendObj = command.getExtraData("lastSendTime");
        Object doorVerifiedObj = command.getExtraData("doorVerified");

        int sendCount = (sendCountObj instanceof Integer) ? (Integer) sendCountObj : 0;
        long lastSendTime = (lastSendObj instanceof Long) ? (Long) lastSendObj : 0;
        boolean doorVerified = (doorVerifiedObj instanceof Boolean) ? (Boolean) doorVerifiedObj : false;

        // If we haven't sent yet, send the open door command
        if (sendCount == 0) {
            sendCount = 1;
            command.putExtraData("openSendCount", sendCount);
            command.putExtraData("lastSendTime", now);

            LoraCommand loraCmd = command.getLoraCommand();
            if (loraCmd != null && loraCmd.getDeviceCommand() != null) {
                try {
                    if (debugger != null) {
                        debugger.log(String.format("Sending elevator open command %d (send %d/10)",
                                command.getCommandId(), sendCount));
                    }
                    loraCommunicator.sendMessage(loraCmd.getDeviceCommand(), true);
                } catch (IOException | TimeoutException e) {
                    if (debugger != null) {
                        debugger.logWarning("checkElevatorOpenCommand",
                                String.format("Failed to send elevator open for command %d: %s",
                                        command.getCommandId(), e.getMessage()));
                    }
                }
            }
            return 0;
        }

        // After sending, check if door is actually open using buildCheckElevatorDoor
        if (!doorVerified) {
            // Build door check command using CommandBuilder
            LoraCommand loraCmd = command.getLoraCommand();
            if (loraCmd != null) {
                Map<String, Object> params = buildParamsFromLoraCommand(loraCmd);
                String doorCheckCmd = CommandBuilder.buildCheckElevatorDoor(params);

                try {
                    if (debugger != null) {
                        debugger.log(String.format("Checking door status for command %d", command.getCommandId()));
                    }

                    String response = loraCommunicator.sendMessage(doorCheckCmd, true);

                    if (debugger != null) {
                        debugger.log(String.format("Door check response: %s", response != null ? response : "null"));
                    }

                    // Check if door is open (status "01" at positions 10-11)
                    if (isDoorOpenResponse(response)) {
                        doorVerified = true;
                        command.putExtraData("doorVerified", true);

                        if (debugger != null) {
                            debugger.log(String.format("✅ Elevator door confirmed OPEN for command %d",
                                    command.getCommandId()));
                        }
                        LOG.info("Elevator door confirmed open for command: {}", command.getCommandId());
                        return 1; // Door is open - command complete
                    }
                } catch (IOException | TimeoutException e) {
                    if (debugger != null) {
                        debugger.logWarning("checkElevatorOpenCommand",
                                String.format("Failed to check door status: %s", e.getMessage()));
                    }
                }
            }
        }

        // If door not open, check if we need to resend
        if (!doorVerified) {
            long elapsedSinceLastSend = now - lastSendTime;

            if (debugger != null) {
                debugger.log(String.format("Elevator open command %d: sent %d/10 times, last send %d ms ago, doorVerified=%b",
                        command.getCommandId(), sendCount, elapsedSinceLastSend, doorVerified));
            }

            // Resend if enough time has passed and we haven't exceeded max attempts
            if (elapsedSinceLastSend >= 500 && sendCount < 10) {
                LoraCommand loraCmd = command.getLoraCommand();
                if (loraCmd != null && loraCmd.getDeviceCommand() != null) {
                    try {
                        if (debugger != null) {
                            debugger.log(String.format("Resending elevator open command %d (send %d/10)",
                                    command.getCommandId(), sendCount + 1));
                        }

                        loraCommunicator.sendMessage(loraCmd.getDeviceCommand(), true);
                        sendCount++;
                        command.putExtraData("openSendCount", sendCount);
                        command.putExtraData("lastSendTime", now);

                        if (debugger != null) {
                            debugger.log(String.format("Elevator open resend %d/10 complete for command %d",
                                    sendCount, command.getCommandId()));
                        }
                    } catch (IOException | TimeoutException e) {
                        if (debugger != null) {
                            debugger.logWarning("checkElevatorOpenCommand",
                                    String.format("Failed to resend elevator open for command %d: %s",
                                            command.getCommandId(), e.getMessage()));
                        }
                    }
                }
            }

            // Check if we've exceeded max attempts without door opening
            if (sendCount >= 10) {
                if (debugger != null) {
                    debugger.logError("checkElevatorOpenCommand",
                            String.format("Command %d failed - door not opened after %d attempts",
                                    command.getCommandId(), sendCount));
                }
                return -1; // Permanent failure
            }
        }

        return 0; // Still executing
    }

    /**
     * Check if door open response indicates door is open (status "01" at positions 10-11)
     * Similar to isValidElevatorResponse with expectedFunctionCode "03"
     */
    private boolean isDoorOpenResponse(String response) {
        if (debugger != null) {
            debugger.log(String.format("isDoorOpenResponse: response=%s", response != null ? response : "null"));
        }

        if (response == null || response.isEmpty()) {
            return false;
        }

        // Clean response by removing spaces
        String cleanResponse = response.replace(" ", "");

        if (debugger != null) {
            debugger.log(String.format("Cleaned response: %s (length=%d)", cleanResponse, cleanResponse.length()));
        }

        // Response should be at least 12 characters to have positions 8-11
        if (cleanResponse.length() < 12) {
            LOG.warn("Response too short for door validation. Length: {}, Response: {}",
                    cleanResponse.length(), cleanResponse);
            return false;
        }

        // Extract function code at positions 8-10 (0-based indices: indices 8,9)
        String functionCode = cleanResponse.substring(8, 10);

        // For door check, function code should be "03"
        if (!functionCode.equals("03")) {
            if (debugger != null) {
                debugger.log(String.format("Function code mismatch for door check. Expected: 03, Got: %s", functionCode));
            }
            return false;
        }

        // Extract status code at positions 10-11 (0-based indices: indices 10,11)
        String statusCode = cleanResponse.substring(10, 12);

        // Expected status is "01" for door open
        boolean isOpen = statusCode.equals("01");

        if (debugger != null) {
            debugger.log(String.format("Door status: %s (open=%b)", statusCode, isOpen));
            if (!isOpen) {
                debugger.log(String.format("Expected '01' for open but got '%s'", statusCode));
            }
        }

        return isOpen;
    }

    private int checkElevatorCancelOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorCancelOpenCommand: Command ID=%d", command.getCommandId()));
        }

        long now = System.currentTimeMillis();
        long elapsed = now - command.getStartTime();

        // Check if this is exception recovery mode
        if (command.isExceptionRecovery()) {
            RecoveryType recoveryType = command.getRecoveryType();
            if (recoveryType == RecoveryType.MANUAL_CANCEL) {
                // Manual cancel - just wait 1 second and complete
                if (elapsed > 1000) {
                    if (debugger != null) {
                        debugger.log(String.format("✅ Manual cancel command %d COMPLETED after %d ms",
                                command.getCommandId(), elapsed));
                    }
                    return 1;
                }
                if (debugger != null) {
                    debugger.log(String.format("Manual cancel command %d waiting for 1s timeout (elapsed=%d ms)",
                            command.getCommandId(), elapsed));
                }
                return 0;
            } else {
                // Exception recovery mode - use longer timeout and check door status
                return checkElevatorCancelOpenWithDoorVerification(command, elapsed, ELEVATOR_CANCEL_OPEN_THRESHOLD);
            }
        }

        // Normal mode - need to verify door is closed
        return checkElevatorCancelOpenWithDoorVerification(command, elapsed, 1000000);
    }

    /**
     * Check elevator cancel open with door status verification
     */
    private int checkElevatorCancelOpenWithDoorVerification(CommandMonitor command, long elapsed, long timeoutThreshold) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorCancelOpenWithDoorVerification: Command ID=%d, elapsed=%d, timeout=%d",
                    command.getCommandId(), elapsed, timeoutThreshold));
        }

        // First, check if door is already closed using buildCheckElevatorDoor
        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd != null) {
            try {
                Map<String, Object> params = buildParamsFromLoraCommand(loraCmd);
                String doorCheckCmd = CommandBuilder.buildCheckElevatorDoor(params);

                if (debugger != null) {
                    debugger.log(String.format("Checking door status for cancel command %d", command.getCommandId()));
                }

                String response = loraCommunicator.sendMessage(doorCheckCmd, true);

                if (debugger != null) {
                    debugger.log(String.format("Door check response for cancel: %s", response != null ? response : "null"));
                }

                // Check if door is closed (status "00" at positions 10-11)
                if (isDoorClosedResponse(response)) {
                    if (debugger != null) {
                        debugger.log(String.format("✅ Door confirmed CLOSED for cancel command %d",
                                command.getCommandId()));
                    }
                    LOG.info("Door confirmed closed for cancel command: {}", command.getCommandId());
                    return 1; // Door is closed - command complete
                } else {
                    // Door is still open, check if we need to resend the cancel command
                    Object cancelSendCountObj = command.getExtraData("cancelSendCount");
                    Object cancelLastSendObj = command.getExtraData("cancelLastSendTime");

                    int cancelSendCount = (cancelSendCountObj instanceof Integer) ? (Integer) cancelSendCountObj : 0;
                    long cancelLastSendTime = (cancelLastSendObj instanceof Long) ? (Long) cancelLastSendObj : command.getStartTime();

                    long now = System.currentTimeMillis();
                    long elapsedSinceLastSend = now - cancelLastSendTime;

                    if (debugger != null) {
                        debugger.log(String.format("Cancel command %d: door still open, sent %d times, last send %d ms ago",
                                command.getCommandId(), cancelSendCount, elapsedSinceLastSend));
                    }

                    // Resend cancel command if needed (max 10 attempts)
                    if (elapsedSinceLastSend >= 500 && cancelSendCount < 10) {
                        if (loraCmd.getDeviceCommand() != null) {
                            try {
                                if (debugger != null) {
                                    debugger.log(String.format("Resending cancel open command %d (send %d/10)",
                                            command.getCommandId(), cancelSendCount + 1));
                                }

                                loraCommunicator.sendMessage(loraCmd.getDeviceCommand(), true);
                                cancelSendCount++;
                                command.putExtraData("cancelSendCount", cancelSendCount);
                                command.putExtraData("cancelLastSendTime", now);

                                if (debugger != null) {
                                    debugger.log(String.format("Cancel open resend %d/10 complete for command %d",
                                            cancelSendCount, command.getCommandId()));
                                }
                            } catch (IOException | TimeoutException e) {
                                if (debugger != null) {
                                    debugger.logWarning("checkElevatorCancelOpenWithDoorVerification",
                                            String.format("Failed to resend cancel open for command %d: %s",
                                                    command.getCommandId(), e.getMessage()));
                                }
                            }
                        }
                    }

                    // Check if we've exceeded max attempts
                    if (cancelSendCount >= 10) {
                        if (debugger != null) {
                            debugger.logError("checkElevatorCancelOpenWithDoorVerification",
                                    String.format("Command %d failed - door not closed after %d cancel attempts",
                                            command.getCommandId(), cancelSendCount));
                        }
                        return -1; // Permanent failure
                    }

                    // Check if we've exceeded the timeout threshold
                    if (elapsed > timeoutThreshold) {
                        if (debugger != null) {
                            debugger.logWarning("checkElevatorCancelOpenWithDoorVerification",
                                    String.format("Command %d timed out after %d ms - door still open",
                                            command.getCommandId(), elapsed));
                        }
                        return -1; // Timeout failure
                    }

                    if (debugger != null) {
                        debugger.log(String.format("Cancel command %d: still waiting for door to close",
                                command.getCommandId()));
                    }
                    return 0; // Still waiting
                }

            } catch (IOException | TimeoutException e) {
                LOG.error("Error checking door status for cancel command {}: {}", command.getCommandId(), e.getMessage());
                if (debugger != null) {
                    debugger.logWarning("checkElevatorCancelOpenWithDoorVerification",
                            String.format("Error checking door: %s", e.getMessage()));
                }
                // On error, fall back to timeout-based completion for safety
                if (elapsed > timeoutThreshold) {
                    if (debugger != null) {
                        debugger.log(String.format("⚠️ Command %d completing by timeout due to error (elapsed=%d ms)",
                                command.getCommandId(), elapsed));
                    }
                    return 1;
                }
                return 0;
            }
        } else {
            // No LoraCommand, fall back to timeout-based completion
            if (debugger != null) {
                debugger.logWarning("checkElevatorCancelOpenWithDoorVerification",
                        String.format("No LoraCommand for command %d, using timeout", command.getCommandId()));
            }
            if (elapsed > timeoutThreshold) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Cancel command %d COMPLETED by timeout (elapsed=%d ms)",
                            command.getCommandId(), elapsed));
                }
                return 1;
            }
            return 0;
        }
    }

    /**
     * Check if door closed response indicates door is closed (status "00" at positions 10-11)
     */
    private boolean isDoorClosedResponse(String response) {
        if (debugger != null) {
            debugger.log(String.format("isDoorClosedResponse: response=%s", response != null ? response : "null"));
        }

        if (response == null || response.isEmpty()) {
            return false;
        }

        // Clean response by removing spaces
        String cleanResponse = response.replace(" ", "");

        if (debugger != null) {
            debugger.log(String.format("Cleaned response: %s (length=%d)", cleanResponse, cleanResponse.length()));
        }

        // Response should be at least 12 characters to have positions 8-11
        if (cleanResponse.length() < 12) {
            LOG.warn("Response too short for door validation. Length: {}, Response: {}",
                    cleanResponse.length(), cleanResponse);
            return false;
        }

        // Extract function code at positions 8-10 (0-based indices: indices 8,9)
        String functionCode = cleanResponse.substring(8, 10);

        // For door check, function code should be "03"
        if (!functionCode.equals("03")) {
            if (debugger != null) {
                debugger.log(String.format("Function code mismatch for door check. Expected: 03, Got: %s", functionCode));
            }
            return false;
        }

        // Extract status code at positions 10-11 (0-based indices: indices 10,11)
        String statusCode = cleanResponse.substring(10, 12);

        // Status "00" means door is closed, "01" means open
        boolean isClosed = statusCode.equals("00");

        if (debugger != null) {
            debugger.log(String.format("Door status: %s (closed=%b)", statusCode, isClosed));
            if (!isClosed) {
                debugger.log(String.format("Expected '00' for closed but got '%s'", statusCode));
            }
        }

        return isClosed;
    }

    private int checkElevatorClaimAccessCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorClaimAccessCommand: Command ID=%d - returning 1 (immediate)",
                    command.getCommandId()));
        }
        return 1;
    }

    private int checkElevatorReleaseAccessCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorReleaseAccessCommand: Command ID=%d - returning 1 (immediate)",
                    command.getCommandId()));
        }
        return 1;
    }

    private int checkAutoDoorOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkAutoDoorOpenCommand: Command ID=%d - returning 1 (immediate)",
                    command.getCommandId()));
        }
        return 1;
    }

    private int checkAutoDoorCancelOpenCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkAutoDoorCancelOpenCommand: Command ID=%d - returning 1 (immediate)",
                    command.getCommandId()));
        }
        return 1;
    }

    private int checkChangeMapCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkChangeMapCommand: Command ID=%d", command.getCommandId()));
        }

        // Check if map switch was successful by verifying current map name and position
        if (latestStatus != null && latestStatus.data != null) {
            String currentMap = latestStatus.data.pos.mapName;

            if (debugger != null) {
                debugger.log(String.format("Current map: %s", currentMap));
            }

            LoraCommand loraCmd = command.getLoraCommand();
            if (loraCmd != null && loraCmd.hexParams != null) {
                Object targetMapObj = loraCmd.hexParams.get("mapName");
                Object targetXObj = loraCmd.hexParams.get("x");
                Object targetYObj = loraCmd.hexParams.get("y");
                Object targetYawObj = loraCmd.hexParams.get("yaw");

                if (targetMapObj != null && targetXObj != null && targetYObj != null && targetYawObj != null) {
                    String targetMap = targetMapObj.toString();
                    double targetX = Double.parseDouble(targetXObj.toString());
                    double targetY = Double.parseDouble(targetYObj.toString());
                    double targetYaw = Double.parseDouble(targetYawObj.toString());

                    // Get current position
                    double currentX = latestStatus.data.pos.x;
                    double currentY = latestStatus.data.pos.y;
                    double currentYaw = latestStatus.data.pos.theta;

                    // Calculate position error
                    double posError = Math.sqrt((targetX - currentX) * (targetX - currentX) +
                            (targetY - currentY) * (targetY - currentY));
                    double angError = calculateAngleDifferenceRad(currentYaw, targetYaw);

                    if (debugger != null) {
                        debugger.log(String.format("Target map: %s, Current map: %s", targetMap, currentMap));
                        debugger.log(String.format("Position error: %.3f, Angle error: %.3f rad (%.1f°)",
                                posError, angError, Math.toDegrees(angError)));
                        debugger.log(String.format("Current pos: (%.2f, %.2f, %.2f), Target pos: (%.2f, %.2f, %.2f)",
                                currentX, currentY, currentYaw, targetX, targetY, targetYaw));
                    }

                    // Check both map name AND position/angle
                    if (targetMap.equals(currentMap) &&
                            posError < RELOCALIZE_POSITION_THRESHOLD && angError < RELOCALIZE_ANGLE_THRESHOLD) {
                        // Increment the success counter when condition is met
                        int successCount = command.getLocalizeSuccessCount();
                        successCount++;
                        command.setLocalizeSuccessCount(successCount);

                        if (debugger != null) {
                            debugger.log(String.format("Map switch condition met %d/2 times for command %d",
                                    successCount, command.getCommandId()));
                        }

                        // Check if condition has been met for 2 consecutive periods
                        if (successCount >= 2) {
                            if (debugger != null) {
                                debugger.log(String.format("✅ Map switch command %d COMPLETED - map changed to %s, position verified",
                                        command.getCommandId(), currentMap));
                            }
                            LOG.info("Map switch successful - current map: {}, position: ({}, {}, {})",
                                    currentMap, currentX, currentY, currentYaw);

                            // Reset resend attempt counter on success
                            command.setResendAttemptCount(0);
                            return 1;
                        } else {
                            if (debugger != null) {
                                debugger.log(String.format("Map switch command %d: waiting for second verification",
                                        command.getCommandId()));
                            }
                        }
                    } else {
                        // Reset counter when condition is not met
                        if (command.getLocalizeSuccessCount() > 0) {
                            command.setLocalizeSuccessCount(0);
                            if (debugger != null) {
                                debugger.log(String.format("Map switch condition reset for command %d - posError=%.3f, angError=%.3f",
                                        command.getCommandId(), posError, angError));
                            }
                        }

                        if (debugger != null) {
                            debugger.log(String.format("Map switch command %d: still waiting - map match=%b, posError=%.3f<0.5=%b, angError=%.3f<0.1=%b",
                                    command.getCommandId(),
                                    targetMap.equals(currentMap),
                                    posError, posError < RELOCALIZE_POSITION_THRESHOLD,
                                    angError, angError < RELOCALIZE_ANGLE_THRESHOLD));
                        }
                    }

                    // Check timeout - if exceeded 30 seconds, trigger resend
                    long elapsed = System.currentTimeMillis() - command.getStartTime();
                    if (elapsed > RELOCALIZE_TIMEOUT_THRESHOLD) { // 30 seconds timeout
                        int resendCount = command.getResendAttemptCount();

                        if (resendCount >= 3) {
                            // Max retries exceeded, fail permanently
                            if (debugger != null) {
                                debugger.logError("checkChangeMapCommand",
                                        String.format("Command %d failed after %d resend attempts",
                                                command.getCommandId(), resendCount));
                            }
                            LOG.error("Map switch command {} failed after {} resend attempts",
                                    command.getCommandId(), resendCount);
                            command.setResendAttemptCount(0);
                            command.setLocalizeSuccessCount(0);
                            return -1; // Permanent failure
                        }

                        // Increment resend count
                        resendCount++;
                        command.setResendAttemptCount(resendCount);

                        if (debugger != null) {
                            debugger.log(String.format("⚠️ Map switch timeout for command %d after %d ms, resending (attempt %d/3)",
                                    command.getCommandId(), elapsed, resendCount));
                        }

                        LOG.warn("Map switch timeout for command {} after {} ms, resending (attempt {}/3)",
                                command.getCommandId(), elapsed, resendCount);

                        // Reset success count for new attempt
                        command.setLocalizeSuccessCount(0);

                        // Reset start time for new timeout window
                        command.setStartTime(System.currentTimeMillis());

                        // Resend the command
                        boolean resendSuccess = resendChangeMapCommand(command);

                        if (resendSuccess) {
                            return 0; // Still executing after resend
                        } else {
                            LOG.error("Failed to resend map switch command for command {}", command.getCommandId());
                            return -1;
                        }
                    }

                } else {
                    if (debugger != null) {
                        debugger.logWarning("checkChangeMapCommand",
                                String.format("Missing position parameters in hexParams for command %d. Available keys: %s",
                                        command.getCommandId(), loraCmd.hexParams.keySet()));
                    }
                }
            } else {
                if (debugger != null) {
                    debugger.logWarning("checkChangeMapCommand",
                            String.format("No LoraCommand or hexParams for command %d", command.getCommandId()));
                }
            }
        } else {
            if (debugger != null) {
                debugger.logWarning("checkChangeMapCommand",
                        String.format("No status data for command %d", command.getCommandId()));
            }
        }

        return 0; // Still waiting
    }

    /**
     * Resend change map command when timeout occurs
     */
    private boolean resendChangeMapCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("resendChangeMapCommand: Command ID=%d, Attempt=%d",
                    command.getCommandId(), command.getResendAttemptCount()));
        }

        LOG.debug("resendChangeMapCommand - Command ID: {}, OperationParams: {}",
                command.getCommandId(), command.getOperationParams());

        String params = command.getOperationParams();
        if (params == null || params.isEmpty()) {
            LOG.error("No operation params found for resending change map command: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logError("resendChangeMapCommand",
                        String.format("No operation params for command %d", command.getCommandId()));
            }
            return false;
        }

        try {
            // Parse the JSON parameters
            JsonObject jsonParams = JsonParser.parseString(params).getAsJsonObject();

            if (!jsonParams.has("hexParams")) {
                LOG.error("No hexParams found in change map command. JSON keys: {}", jsonParams.keySet());
                if (debugger != null) {
                    debugger.logError("resendChangeMapCommand",
                            String.format("No hexParams in JSON for command %d", command.getCommandId()));
                }
                return false;
            }

            JsonObject hexParams = jsonParams.getAsJsonObject("hexParams");

            if (!hexParams.has("mapName") || !hexParams.has("x") ||
                    !hexParams.has("y") || !hexParams.has("yaw")) {
                LOG.error("Missing required parameters for map switch. Need: mapName, x, y, yaw. Present keys: {}", hexParams.keySet());
                if (debugger != null) {
                    debugger.logError("resendChangeMapCommand",
                            String.format("Missing required params for command %d", command.getCommandId()));
                }
                return false;
            }

            String mapName = hexParams.get("mapName").getAsString();
            double x = hexParams.get("x").getAsDouble();
            double y = hexParams.get("y").getAsDouble();
            double yaw = hexParams.get("yaw").getAsDouble();

            if (debugger != null) {
                debugger.log(String.format("RESENDING CHANGE_MAP to map='%s', pos=(%.2f,%.2f,%.2f) (attempt %d/3)",
                        mapName, x, y, yaw, command.getResendAttemptCount()));
            }

            LOG.info("Resending map switch command: map={}, x={}, y={}, yaw={}, attempt={}",
                    mapName, x, y, yaw, command.getResendAttemptCount());

            // Send the command synchronously or with callback
            commandClient.mapSwitchAsync(mapName, x, y, yaw, new CommandWebSocketClient.Callback() {
                @Override
                public void onSuccess(String response) {
                    LOG.info("Map switch resend successful for command {}: {}", command.getCommandId(), response);
                    if (debugger != null) {
                        debugger.log(String.format("✅ CHANGE_MAP resend success for command %d: %s",
                                command.getCommandId(), response));
                    }
                }

                @Override
                public void onError(String error) {
                    LOG.error("Map switch resend failed for command {}: {}", command.getCommandId(), error);
                    if (debugger != null) {
                        debugger.logError("resendChangeMapCommand",
                                String.format("CHANGE_MAP resend failed for command %d: %s",
                                        command.getCommandId(), error));
                    }
                }
            });

            return true;

        } catch (Exception e) {
            LOG.error("Error resending change map command: {}", e.getMessage(), e);
            if (debugger != null) {
                debugger.logError("resendChangeMapCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return false;
        }
    }

    private int checkElevatorDoorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorDoorCheckCommand: Command ID=%d", command.getCommandId()));
        }

        // For elevator door check, we need to verify elevator has opened/closed
        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd == null) {
            if (debugger != null) {
                debugger.logError("checkElevatorDoorCheckCommand",
                        String.format("No LoraCommand for command %d", command.getCommandId()));
            }
            return -1;
        }

        try {
            if (debugger != null) {
                debugger.log(String.format("Sending door check command: %s",
                        loraCmd.getDeviceCommand().length() > 50 ?
                                loraCmd.getDeviceCommand().substring(0, 50) + "..." :
                                loraCmd.getDeviceCommand()));
            }

            String response = loraCommunicator.sendMessage(
                    loraCmd.getDeviceCommand(), true);

            if (debugger != null) {
                debugger.log(String.format("Door check response: %s", response != null ? response : "null"));
            }

            if (isValidReadResponse(command, response)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Elevator door check command %d SUCCESS", command.getCommandId()));
                }
                LOG.info("Elevator door check successful for command: {}", command.getCommandId());
                return 1;
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Elevator door check command %d still waiting", command.getCommandId()));
                }
                return 0;
            }

        } catch (IOException e) {
            LOG.error("IO Error checking elevator door status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorDoorCheckCommand",
                        String.format("IO error: %s", e.getMessage()));
            }
            return 0;
        } catch (TimeoutException e) {
            LOG.error("Timeout checking elevator door status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logWarning("checkElevatorDoorCheckCommand",
                        String.format("Timeout: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Error checking elevator door status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorDoorCheckCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int checkElevatorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorCheckCommand: Command ID=%d", command.getCommandId()));
        }

        // For elevator check, we need to verify elevator has arrived at requested floor
        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd == null) {
            if (debugger != null) {
                debugger.logError("checkElevatorCheckCommand",
                        String.format("No LoraCommand for command %d", command.getCommandId()));
            }
            return -1;
        }

        try {
            if (debugger != null) {
                debugger.log(String.format("Sending elevator check command: %s",
                        loraCmd.getDeviceCommand().length() > 50 ?
                                loraCmd.getDeviceCommand().substring(0, 50) + "..." :
                                loraCmd.getDeviceCommand()));
            }

            String response = loraCommunicator.sendMessage(
                    loraCmd.getDeviceCommand(), true);

            if (debugger != null) {
                debugger.log(String.format("Elevator check response: %s", response != null ? response : "null"));
            }

            if (isValidReadResponse(command, response)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Elevator check command %d SUCCESS", command.getCommandId()));
                }
                LOG.info("Elevator check successful for command: {}", command.getCommandId());
                return 1;
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Elevator check command %d still waiting", command.getCommandId()));
                }
                return 0;
            }

        } catch (IOException e) {
            LOG.error("IO Error checking elevator status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorCheckCommand",
                        String.format("IO error: %s", e.getMessage()));
            }
            return 0;
        } catch (TimeoutException e) {
            LOG.error("Timeout checking elevator status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logWarning("checkElevatorCheckCommand",
                        String.format("Timeout: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Error checking elevator status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorCheckCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    /**
     * Check elevator arrival and door open status combined
     * First checks if elevator has arrived at the given floor (buildCheckElevator)
     * Then checks if elevator door has opened (buildCheckElevatorDoor)
     * Retries the sequence if door not open yet, re-verifying arrival each time
     * @return 1 for success (both arrived and door open), 0 for still waiting, -1 for failure
     */
    private int checkElevatorArrivalDoorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorArrivalDoorCheckCommand: Command ID=%d", command.getCommandId()));
        }

        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd == null) {
            if (debugger != null) {
                debugger.logError("checkElevatorArrivalDoorCheckCommand",
                        String.format("No LoraCommand for command %d", command.getCommandId()));
            }
            return -1;
        }

        // Get or initialize the verification state for this command
        ArrivalDoorCheckState state = getArrivalDoorCheckState(command);

        try {
            // Step 1: Check elevator arrival using buildCheckElevator
            if (!state.arrivalVerified) {
                if (debugger != null) {
                    debugger.log(String.format("Checking elevator arrival for command %d (attempt %d/%d)",
                            command.getCommandId(), state.arrivalCheckAttempt + 1, state.maxArrivalAttempts));
                }

                // Build arrival check command using CommandBuilder.buildCheckElevator
                Map<String, Object> params = buildParamsFromLoraCommand(loraCmd);
                String arrivalCheckCmd = CommandBuilder.buildCheckElevator(params);

                if (debugger != null) {
                    debugger.log(String.format("Sending elevator arrival check: %s", arrivalCheckCmd));
                }

                String response = loraCommunicator.sendMessage(arrivalCheckCmd, true);

                if (debugger != null) {
                    debugger.log(String.format("Arrival check response: %s", response != null ? response : "null"));
                }

                if (isValidElevatorResponse(command, "04", response)) {
                    // Elevator has arrived at the floor
                    state.arrivalVerified = true;
                    state.lastArrivalTime = System.currentTimeMillis();

                    if (debugger != null) {
                        debugger.log(String.format("✅ Elevator arrival verified for command %d", command.getCommandId()));
                    }
                    LOG.info("Elevator arrival verified for command: {}", command.getCommandId());
                } else {
                    // Not arrived yet, increment attempt counter
                    state.arrivalCheckAttempt++;

                    if (state.arrivalCheckAttempt >= state.maxArrivalAttempts) {
                        // Max arrival check attempts exceeded - fail
                        if (debugger != null) {
                            debugger.logError("checkElevatorArrivalDoorCheckCommand",
                                    String.format("Command %d: elevator arrival check failed after %d attempts",
                                            command.getCommandId(), state.arrivalCheckAttempt));
                        }
                        clearArrivalDoorCheckState(command);
                        return -1;
                    }

                    if (debugger != null) {
                        debugger.log(String.format("Elevator arrival not yet verified for command %d, attempt %d/%d",
                                command.getCommandId(), state.arrivalCheckAttempt, state.maxArrivalAttempts));
                    }
                    return 0; // Still waiting for arrival
                }
            }

            // Step 2: After arrival, delay 500ms then check door open using buildCheckElevatorDoor
            if (state.arrivalVerified && !state.doorOpenVerified) {
                // Check if we need to wait for the delay
                if (state.lastArrivalTime > 0) {
                    long delayElapsed = System.currentTimeMillis() - state.lastArrivalTime;
                    if (delayElapsed < 500) {
                        if (debugger != null) {
                            debugger.log(String.format("Waiting for 500ms delay before door check: %d ms remaining",
                                    500 - delayElapsed));
                        }
                        return 0; // Still waiting for delay
                    }
                }

                if (debugger != null) {
                    debugger.log(String.format("Checking elevator door open for command %d (attempt %d/%d)",
                            command.getCommandId(), state.doorCheckAttempt + 1, state.maxDoorAttempts));
                }

                // Build door check command using CommandBuilder.buildCheckElevatorDoor
                Map<String, Object> params = buildParamsFromLoraCommand(loraCmd);
                String doorCheckCmd = CommandBuilder.buildCheckElevatorDoor(params);

                if (debugger != null) {
                    debugger.log(String.format("Sending elevator door check: %s", doorCheckCmd));
                }

                String response = loraCommunicator.sendMessage(doorCheckCmd, true);

                if (debugger != null) {
                    debugger.log(String.format("Door check response: %s", response != null ? response : "null"));
                }

                if (isValidElevatorResponse(command, "03", response)) {
                    // Door is open
                    state.doorOpenVerified = true;

                    if (debugger != null) {
                        debugger.log(String.format("✅ Elevator door open verified for command %d", command.getCommandId()));
                    }
                    LOG.info("Elevator door open verified for command: {}", command.getCommandId());

                    // Both arrival and door open verified - command successful
                    if (debugger != null) {
                        debugger.log(String.format("✅✅ Elevator arrival and door open command %d COMPLETED SUCCESSFULLY",
                                command.getCommandId()));
                    }
                    clearArrivalDoorCheckState(command);
                    return 1;
                } else {
                    // Door not open yet
                    state.doorCheckAttempt++;
                    state.lastArrivalTime = 0; // Reset arrival time to trigger re-verification
                    state.arrivalVerified = false; // Need to re-verify arrival before checking door again

                    if (state.doorCheckAttempt >= state.maxDoorAttempts) {
                        // Max door check attempts exceeded - fail
                        if (debugger != null) {
                            debugger.logError("checkElevatorArrivalDoorCheckCommand",
                                    String.format("Command %d: elevator door check failed after %d attempts (arrival was verified but door never opened)",
                                            command.getCommandId(), state.doorCheckAttempt));
                        }
                        clearArrivalDoorCheckState(command);
                        return -1;
                    }

                    if (debugger != null) {
                        debugger.log(String.format("Elevator door not yet open for command %d, will re-verify arrival and try again (door attempt %d/%d)",
                                command.getCommandId(), state.doorCheckAttempt, state.maxDoorAttempts));
                    }
                    return 0; // Will retry from arrival verification
                }
            }

            return 0;

        } catch (IOException e) {
            LOG.error("IO Error checking elevator arrival/door: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorArrivalDoorCheckCommand",
                        String.format("IO error: %s", e.getMessage()));
            }
            return 0;
        } catch (TimeoutException e) {
            LOG.error("Timeout checking elevator arrival/door: {}", e.getMessage());
            if (debugger != null) {
                debugger.logWarning("checkElevatorArrivalDoorCheckCommand",
                        String.format("Timeout: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Error checking elevator arrival/door: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorArrivalDoorCheckCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    /**
     * Build parameters map from LoraCommand for CommandBuilder
     */
    private Map<String, Object> buildParamsFromLoraCommand(LoraCommand loraCmd) {
        Map<String, Object> params = new java.util.HashMap<>();

        if (loraCmd.hexParams != null) {
            if (loraCmd.hexParams.containsKey("channel")) {
                params.put("channel", loraCmd.hexParams.get("channel").toString());
            }
            if (loraCmd.hexParams.containsKey("address")) {
                params.put("address", loraCmd.hexParams.get("address").toString());
            }
            if (loraCmd.hexParams.containsKey("floor")) {
                params.put("floor", loraCmd.hexParams.get("floor").toString());
            }
            if (loraCmd.hexParams.containsKey("robotId")) {
                params.put("robotId", loraCmd.hexParams.get("robotId").toString());
            }
            if (loraCmd.hexParams.containsKey("robotAddress")) {
                params.put("robotAddress", loraCmd.hexParams.get("robotAddress").toString());
            }
            if (loraCmd.hexParams.containsKey("robotChannel")) {
                params.put("robotChannel", loraCmd.hexParams.get("robotChannel").toString());
            }
        }

        return params;
    }

    private int checkAutoDoorCheckCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkAutoDoorCheckCommand: Command ID=%d", command.getCommandId()));
        }

        // For autodoor check, we need to verify autodoor has opened/closed
        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd == null) {
            if (debugger != null) {
                debugger.logError("checkAutoDoorCheckCommand",
                        String.format("No LoraCommand for command %d", command.getCommandId()));
            }
            return -1;
        }

        try {
            if (debugger != null) {
                debugger.log(String.format("Sending auto door check command: %s",
                        loraCmd.getDeviceCommand().length() > 50 ?
                                loraCmd.getDeviceCommand().substring(0, 50) + "..." :
                                loraCmd.getDeviceCommand()));
            }

            String response = loraCommunicator.sendMessage(
                    loraCmd.getDeviceCommand(), true);

            if (debugger != null) {
                debugger.log(String.format("Auto door check response: %s", response != null ? response : "null"));
            }

            if (isValidReadResponse(command, response)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Auto door check command %d SUCCESS", command.getCommandId()));
                }
                LOG.info("Autodoor check successful for command: {}", command.getCommandId());
                return 1;
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Auto door check command %d still waiting", command.getCommandId()));
                }
                return 0;
            }

        } catch (IOException e) {
            LOG.error("IO Error checking autodoor status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkAutoDoorCheckCommand",
                        String.format("IO error: %s", e.getMessage()));
            }
            return 0;
        } catch (TimeoutException e) {
            LOG.error("Timeout checking autodoor status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logWarning("checkAutoDoorCheckCommand",
                        String.format("Timeout: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Error checking autodoor status: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkAutoDoorCheckCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    private int checkElevatorQueryAccessCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkElevatorQueryAccessCommand: Command ID=%d", command.getCommandId()));
        }

        // For query access, verify response contains expected value at positions 17-18
        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd == null) {
            if (debugger != null) {
                debugger.logError("checkElevatorQueryAccessCommand",
                        String.format("No LoraCommand for command %d", command.getCommandId()));
            }
            return -1;
        }

        try {
            if (debugger != null) {
                debugger.log(String.format("Sending query access command: %s",
                        loraCmd.getDeviceCommand().length() > 50 ?
                                loraCmd.getDeviceCommand().substring(0, 50) + "..." :
                                loraCmd.getDeviceCommand()));
            }

            // Send query command to check access status
            String response = loraCommunicator.sendMessage(
                    loraCmd.getDeviceCommand(), true);

            if (debugger != null) {
                debugger.log(String.format("Query access response: %s", response != null ? response : "null"));
            }

            if (isValidReadResponse(command, response)) {
                if (debugger != null) {
                    debugger.log(String.format("✅ Elevator query access command %d SUCCESS", command.getCommandId()));
                }
                LOG.info("Elevator query access successful for command: {}", command.getCommandId());
                return 1;
            } else {
                if (debugger != null) {
                    debugger.log(String.format("Elevator query access command %d still waiting or access not granted",
                            command.getCommandId()));
                }
                return 0;
            }

        } catch (IOException e) {
            LOG.error("IO Error checking elevator query access: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorQueryAccessCommand",
                        String.format("IO error: %s", e.getMessage()));
            }
            return 0;
        } catch (TimeoutException e) {
            LOG.error("Timeout checking elevator query access: {}", e.getMessage());
            if (debugger != null) {
                debugger.logWarning("checkElevatorQueryAccessCommand",
                        String.format("Timeout: %s", e.getMessage()));
            }
            return 0;
        } catch (Exception e) {
            LOG.error("Error checking elevator query access: {}", e.getMessage());
            if (debugger != null) {
                debugger.logError("checkElevatorQueryAccessCommand",
                        String.format("Exception: %s", e.getMessage()));
            }
            return 0;
        }
    }

    /**
     * Validate read command response by checking expectedResponse substring at positions 17-18
     * Also validates function code at positions 8-10 based on command type
     * Response string is expected to be length 24 (0-based indices 0-23)
     */
    private boolean isValidReadResponse(CommandMonitor command, String response) {
        long startTime = System.currentTimeMillis();

        if (debugger != null) {
            debugger.log(String.format("isValidReadResponse: Command ID=%d, response=%s",
                    command.getCommandId(), response != null ? response : "null"));
        }

        if (response == null || response.isEmpty()) {
            LOG.warn("Null or empty response received for read command: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logWarning("isValidReadResponse",
                        String.format("Command[%d]: null or empty response", command.getCommandId()));
            }
            return false;
        }

        // Clean response by removing spaces
        String cleanResponse = response.replace(" ", "");

        if (debugger != null) {
            debugger.log(String.format("Cleaned response: %s (length=%d)", cleanResponse, cleanResponse.length()));
        }

        // Check if response has minimum required length
        if (cleanResponse.length() < 12) {
            LOG.warn("Response too short for read command validation. Length: {}, Response: {}",
                    cleanResponse.length(), cleanResponse);
            if (debugger != null) {
                debugger.logWarning("isValidReadResponse",
                        String.format("Command[%d]: response too short (len=%d)",
                                command.getCommandId(), cleanResponse.length()));
            }
            return false;
        }

        // Determine expected function code based on command type
        String expectedFunctionCode = getExpectedFunctionCode(command.getCommandType());

        // Extract function code at positions 8-10 (0-based indices: indices 8,9)
        String functionCode = cleanResponse.substring(8, 10);

        if (debugger != null) {
            debugger.log(String.format("Function code check - Expected: %s, Got: %s",
                    expectedFunctionCode, functionCode));
        }

        // Check function code
        if (!functionCode.equals(expectedFunctionCode)) {
            LOG.warn("Function code mismatch for command {}. Expected: {}, Got: {}",
                    command.getCommandId(), expectedFunctionCode, functionCode);
            if (debugger != null) {
                debugger.logWarning("isValidReadResponse",
                        String.format("Command[%d]: function code mismatch - expected=%s, got=%s",
                                command.getCommandId(), expectedFunctionCode, functionCode));
            }
            return false;
        }

        // Special handling for OP_ELEVATOR_QUERY_ACCESS
        if (command.getCommandType() == NavigationOrderType.OP_ELEVATOR_QUERY_ACCESS) {
            return validateQueryAccessResponse(command, cleanResponse);
        }

        // For non-query commands, check expectedResponse at positions 17-18
        return validateExpectedResponse(command, cleanResponse);
    }

    /**
     * Validate expected response for non-query commands
     */
    private boolean validateExpectedResponse(CommandMonitor command, String cleanResponse) {
        // Response needs to be at least 20 characters to access positions 17-18
        if (cleanResponse.length() < 20) {
            LOG.warn("Response too short for expected response validation. Length: {}, Response: {}",
                    cleanResponse.length(), cleanResponse);
            if (debugger != null) {
                debugger.logWarning("validateExpectedResponse",
                        String.format("Command[%d]: response too short (len=%d)",
                                command.getCommandId(), cleanResponse.length()));
            }
            return false;
        }

        // Get expected response from operationParams
        String params = command.getOperationParams();
        if (params == null || params.isEmpty()) {
            LOG.warn("No operationParams found for read command: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logWarning("validateExpectedResponse",
                        String.format("Command[%d]: no operationParams", command.getCommandId()));
            }
            return false;
        }

        try {
            // Parse JSON to get expectedResponse
            JsonObject jsonParams = JsonParser.parseString(params).getAsJsonObject();

            if (debugger != null) {
                debugger.log(String.format("JSON params keys: %s", jsonParams.keySet()));
            }

            if (!jsonParams.has("expectedResponse")) {
                LOG.warn("No expectedResponse in operationParams for read command: {}", command.getCommandId());
                if (debugger != null) {
                    debugger.logWarning("validateExpectedResponse",
                            String.format("Command[%d]: no expectedResponse in JSON", command.getCommandId()));
                }
                return false;
            }

            String expectedResponse = jsonParams.get("expectedResponse").getAsString();
            if (expectedResponse == null || expectedResponse.isEmpty()) {
                LOG.warn("Empty expectedResponse for read command: {}", command.getCommandId());
                if (debugger != null) {
                    debugger.logWarning("validateExpectedResponse",
                            String.format("Command[%d]: empty expectedResponse", command.getCommandId()));
                }
                return false;
            }

            // Clean expected response
            String cleanExpected = expectedResponse.replace(" ", "");

            // Extract substring at positions 10-11 (0-based indices)
            String responseSubstring = cleanResponse.substring(10, 12);

            if (debugger != null) {
                debugger.log(String.format("Read command validation - Expected: %s, Response substring (10-11): %s",
                        cleanExpected, responseSubstring));
            }

            // Check if response substring matches expected
            boolean isValid = responseSubstring.equals(cleanExpected);

            long elapsedMs = System.currentTimeMillis() - command.getStartTime();

            if (isValid) {
                LOG.info("Read command validation successful for command: {}", command.getCommandId());
                if (debugger != null) {
                    debugger.log(String.format(
                            "✅ [READ_VALIDATION] Command[%d]: SUCCESS in %dms, expected=%s, got=%s",
                            command.getCommandId(), elapsedMs, cleanExpected, responseSubstring
                    ));
                }
            } else {
                LOG.warn("Read command validation failed. Expected: {}, Got: {}",
                        cleanExpected, responseSubstring);
                if (debugger != null) {
                    debugger.logWarning("validateExpectedResponse",
                            String.format("Command[%d]: expected=%s, got=%s, full response=%s",
                                    command.getCommandId(), cleanExpected, responseSubstring, cleanResponse));
                }
            }

            return isValid;

        } catch (JsonSyntaxException e) {
            LOG.error("JSON parse error in validateExpectedResponse for command {}: {}",
                    command.getCommandId(), e.getMessage());
            if (debugger != null) {
                debugger.logError("validateExpectedResponse",
                        String.format("Command[%d]: JSON parse error - %s", command.getCommandId(), e.getMessage()));
            }
            return false;
        } catch (Exception e) {
            LOG.error("Unexpected error in validateExpectedResponse for command {}: {}",
                    command.getCommandId(), e.getMessage());
            if (debugger != null) {
                debugger.logError("validateExpectedResponse",
                        String.format("Command[%d]: unexpected error - %s", command.getCommandId(), e.getMessage()));
            }
            return false;
        }
    }

    /**
     * Validate query access response - checks robot ID at positions 12-14
     */
    private boolean validateQueryAccessResponse(CommandMonitor command, String cleanResponse) {
        if (debugger != null) {
            debugger.log(String.format("validateQueryAccessResponse: Command ID=%d", command.getCommandId()));
        }

        // Response needs to be at least 16 characters to access positions 10-14
        if (cleanResponse.length() < 16) {
            LOG.warn("Response too short for query access validation. Length: {}, Response: {}",
                    cleanResponse.length(), cleanResponse);
            if (debugger != null) {
                debugger.logWarning("validateQueryAccessResponse",
                        String.format("Command[%d]: response too short (len=%d)",
                                command.getCommandId(), cleanResponse.length()));
            }
            return false;
        }
        // First, try to validate using expectedResponse at positions 10-11
        String params = command.getOperationParams();
        if (params != null && !params.isEmpty()) {
            try {
                JsonObject jsonParams = JsonParser.parseString(params).getAsJsonObject();

                if (jsonParams.has("expectedResponse")) {
                    String expectedResponse = jsonParams.get("expectedResponse").getAsString();
                    if (expectedResponse != null && !expectedResponse.isEmpty()) {
                        String cleanExpected = expectedResponse.replace(" ", "");

                        // Extract substring at positions 10-11 (0-based indices: indices 10,11)
                        if (cleanResponse.length() >= 12) {
                            String responseSubstring = cleanResponse.substring(10, 12);

                            if (debugger != null) {
                                debugger.log(String.format("Query access - First check (expected response): Expected: %s, Response substring (10-11): %s",
                                        cleanExpected, responseSubstring));
                            }

                            // Check if response substring matches expected
                            if (responseSubstring.equals(cleanExpected)) {
                                LOG.info("Query access validation successful for command: {} - Expected response matched: {}",
                                        command.getCommandId(), responseSubstring);
                                if (debugger != null) {
                                    debugger.log(String.format("✅ [QUERY_ACCESS] Command[%d]: expected response matched - expected=%s, got=%s",
                                            command.getCommandId(), cleanExpected, responseSubstring));
                                }
                                return true;
                            }

                            if (debugger != null) {
                                debugger.log(String.format("Query access - First check FAILED, proceeding to robot ID check"));
                            }
                        }
                    }
                }
            } catch (JsonSyntaxException e) {
                if (debugger != null) {
                    debugger.logWarning("validateQueryAccessResponse",
                            String.format("Command[%d]: JSON parse error for expectedResponse - %s",
                                    command.getCommandId(), e.getMessage()));
                }
            } catch (Exception e) {
                if (debugger != null) {
                    debugger.logWarning("validateQueryAccessResponse",
                            String.format("Command[%d]: error checking expectedResponse - %s",
                                    command.getCommandId(), e.getMessage()));
                }
            }
        }

        // Second check: Validate using robot ID at positions 12-13
        if (debugger != null) {
            debugger.log(String.format("Query access - Performing robot ID check (fallback)"));
        }
        // Get robot ID from LoraCommand hexParams
        LoraCommand loraCmd = command.getLoraCommand();
        if (loraCmd == null || loraCmd.hexParams == null) {
            LOG.warn("No LoraCommand or hexParams found for query access command: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logWarning("validateQueryAccessResponse",
                        String.format("Command[%d]: no LoraCommand or hexParams", command.getCommandId()));
            }
            return false;
        }

        // Get robot ID from hexParams (try both "robotId" and "robotAddress" keys)
        StringBuilder expectedRobotId = null;
        if (loraCmd.hexParams.containsKey("robotId")) {
            expectedRobotId = new StringBuilder(loraCmd.hexParams.get("robotId").toString());
        } else if (loraCmd.hexParams.containsKey("robotAddress")) {
            expectedRobotId = new StringBuilder(loraCmd.hexParams.get("robotAddress").toString());
        }

        if (expectedRobotId == null || expectedRobotId.length() == 0) {
            LOG.warn("No robot ID found in hexParams for query access command: {}", command.getCommandId());
            if (debugger != null) {
                debugger.logWarning("validateQueryAccessResponse",
                        String.format("Command[%d]: no robotId in hexParams", command.getCommandId()));
            }
            return false;
        }

        // Extract robot ID from response at positions 12-13 (0-based indices: indices 12,13)
        // This is a 2-character substring
        String responseRobotId = cleanResponse.substring(12, 14);

        // Pad expected robot ID to 2 digits if needed (assuming it's numeric)
        while (expectedRobotId.length() < 2) {
            expectedRobotId.insert(0, "0");
        }
        if (expectedRobotId.length() > 2) {
            expectedRobotId = new StringBuilder(expectedRobotId.substring(0, 2));
        }

        if (debugger != null) {
            debugger.log(String.format("Query access validation - Expected Robot ID: %s, Response Robot ID (12-13): %s",
                    expectedRobotId.toString(), responseRobotId));
        }

        boolean isValid = responseRobotId.equals(expectedRobotId.toString());

        if (isValid) {
            LOG.info("Query access validation successful for command: {} - Robot ID matched: {}",
                    command.getCommandId(), responseRobotId);
            if (debugger != null) {
                debugger.log(String.format("✅ [QUERY_ACCESS] Command[%d]: robot ID matched - expected=%s, got=%s",
                        command.getCommandId(), expectedRobotId.toString(), responseRobotId));
            }
        } else {
            LOG.warn("Query access validation failed. Expected Robot ID: {}, Got: {}",
                    expectedRobotId.toString(), responseRobotId);
            if (debugger != null) {
                debugger.logWarning("validateQueryAccessResponse",
                        String.format("Command[%d]: expected robotId=%s, got=%s, full response=%s",
                                command.getCommandId(), expectedRobotId.toString(), responseRobotId, cleanResponse));
            }
        }

        return isValid;
    }
    /**
     * Validate elevator response (checks if response indicates success)
     * Response should have "01" at positions 10-11 for successful operations
     * Also validates function code at positions 8-10 based on command type
     */
    private boolean isValidElevatorResponse(CommandMonitor command,  String expectedFunctionCode, String response) {
        if (debugger != null) {
            debugger.log(String.format("isValidElevatorResponse: Command ID=%d, response=%s",
                    command.getCommandId(), response != null ? response : "null"));
        }

        if (response == null || response.isEmpty()) {
            return false;
        }

        // Clean response by removing spaces
        String cleanResponse = response.replace(" ", "");

        if (debugger != null) {
            debugger.log(String.format("Cleaned response: %s (length=%d)", cleanResponse, cleanResponse.length()));
        }

        // Response should be at least 12 characters to have positions 8-11
        if (cleanResponse.length() < 12) {
            LOG.warn("Response too short for validation. Length: {}, Response: {}",
                    cleanResponse.length(), cleanResponse);
            return false;
        }

        // Extract function code at positions 8-10 (0-based indices: indices 8,9)
        String functionCode = cleanResponse.substring(8, 10);

        if (debugger != null) {
            debugger.log(String.format("Function code check - Expected: %s, Got: %s",
                    expectedFunctionCode, functionCode));
        }

        // Check function code
        if (!functionCode.equals(expectedFunctionCode)) {
            LOG.warn("Function code mismatch. Expected: {}, Got: {}", expectedFunctionCode, functionCode);
            if (debugger != null) {
                debugger.logWarning("isValidElevatorResponse",
                        String.format("Command[%d]: function code mismatch - expected=%s, got=%s",
                                command.getCommandId(), expectedFunctionCode, functionCode));
            }
            return false;
        }

        // Extract status code at positions 10-11 (0-based indices: indices 10,11)
        String statusCode = cleanResponse.substring(10, 12);

        // Expected status is "01" for success
        boolean isValid = statusCode.equals("01");

        if (debugger != null) {
            debugger.log(String.format("Status code (10-11): %s, isValid: %b", statusCode, isValid));
            if (!isValid) {
                debugger.log(String.format("Expected '01' but got '%s'", statusCode));
            }
        }

        return isValid;
    }

    /**
     * Get expected function code based on command type
     */
    private String getExpectedFunctionCode(NavigationOrderType commandType) {
        switch (commandType) {
            case OP_ELEVATOR_CHECK:
                return "04";
            case OP_ELEVATOR_DOOR_CHECK:
                return "03";
            case OP_ELEVATOR_QUERY_ACCESS:
                return "05";
            default:
                return "00";
        }
    }

    /**
     * Get or create arrival/door check state for a command
     */
    private ArrivalDoorCheckState getArrivalDoorCheckState(CommandMonitor command) {
        if (arrivalDoorCheckStates == null) {
            arrivalDoorCheckStates = new ConcurrentHashMap<>();
        }

        ArrivalDoorCheckState state = arrivalDoorCheckStates.get(command.getCommandId());
        if (state == null) {
            state = new ArrivalDoorCheckState();
            arrivalDoorCheckStates.put(command.getCommandId(), state);
            if (debugger != null) {
                debugger.log(String.format("Created new ArrivalDoorCheckState for command %d", command.getCommandId()));
            }
        }
        return state;
    }

    /**
     * Clear arrival/door check state for a command
     */
    private void clearArrivalDoorCheckState(CommandMonitor command) {
        if (arrivalDoorCheckStates != null) {
            arrivalDoorCheckStates.remove(command.getCommandId());
            if (debugger != null) {
                debugger.log(String.format("Cleared ArrivalDoorCheckState for command %d", command.getCommandId()));
            }
        }
    }

    private int checkRelocalizeCommand(CommandMonitor command) {
        if (debugger != null) {
            debugger.log(String.format("checkRelocalizeCommand: Command ID=%d - returning 1 (immediate)",
                    command.getCommandId()));
        }
        return 1;
    }

    private Position getCurrentPosition() {
        if (latestStatus != null && latestStatus.data != null) {
            return new Position(1, "current",
                    latestStatus.data.pos.x,
                    latestStatus.data.pos.y,
                    latestStatus.data.pos.theta);
        }
        return null;
    }

    private double calculateDistance(Position p1, Position p2) {
        double dx = p1.getPosX() - p2.getPosX();
        double dy = p1.getPosY() - p2.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double calculateAngleDifferenceRad(double angle1, double angle2) {
        // 规范化角度到 [0, 2π)
        double norm1 = ((angle1 % (2 * Math.PI)) + (2 * Math.PI)) % (2 * Math.PI);
        double norm2 = ((angle2 % (2 * Math.PI)) + (2 * Math.PI)) % (2 * Math.PI);

        double diff = Math.abs(norm1 - norm2);
        return Math.min(diff, 2 * Math.PI - diff);
    }

    /**
     * Inner class to track arrival and door check state for a command
     */
    private static class ArrivalDoorCheckState {
        boolean arrivalVerified = false;
        boolean doorOpenVerified = false;
        int arrivalCheckAttempt = 0;
        int doorCheckAttempt = 0;
        long lastArrivalTime = 0;
        final int maxArrivalAttempts = 5000;  // Max arrival check attempts (modified to 5000)
        final int maxDoorAttempts = 200;       // Max 5 door check attempts (each triggers re-verification of arrival)

        ArrivalDoorCheckState() {
            // Initialize
        }
    }
}
