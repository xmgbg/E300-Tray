package com.ezhan.amr.navigation.task;

import com.ezhan.amr.data.datatype.Position;

import java.util.ArrayList;
import java.util.List;

public class RecoverySequence {
    long originalCommandId;
    long originalPositionId;
    long originalRouteId;
    RecoveryType recoveryType;
    List<CommandMonitor> recoveryCommands;
    int currentStep;
    long recoveryStartTime;
    String originalOperationParams;
    Position originalWaitPoint;
    Position originalRidePoint;
    String originalCurrentFloor;
    String originalCurrentMap;
    long recoveryPositionId;    // NEW: Position ID for recovery commands
    long recoveryRouteId;       // NEW: Route ID for recovery commands
    // 延迟执行标记：防止主调度循环在延迟等待期间重复调度
    boolean delayScheduled = false;
    // 延迟调度时记录的步骤索引，用于回调时校验步骤未变
    int delayScheduledStep = -1;

    public RecoverySequence(long originalCommandId, long originalPositionId, long originalRouteId,
                            long recoveryPositionId, long recoveryRouteId, RecoveryType recoveryType, String originalOperationParams,
                            Position originalWaitPoint, Position originalRidePoint,
                            String originalCurrentFloor, String originalCurrentMap) {
        this.originalCommandId = originalCommandId;
        this.originalPositionId = originalPositionId;
        this.originalRouteId = originalRouteId;
        this.recoveryPositionId = recoveryPositionId;  // Store recovery position
        this.recoveryRouteId = recoveryRouteId;        // Store recovery route
        this.recoveryType = recoveryType;
        this.originalOperationParams = originalOperationParams;
        this.originalWaitPoint = originalWaitPoint;
        this.originalRidePoint = originalRidePoint;
        this.originalCurrentFloor = originalCurrentFloor;
        this.originalCurrentMap = originalCurrentMap;
        this.recoveryCommands = new ArrayList<>();
        this.currentStep = 0;
        this.recoveryStartTime = System.currentTimeMillis();
    }
}
