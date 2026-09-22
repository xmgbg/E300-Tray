package com.ezhan.amr.communication.modbus;

import android.os.Handler;
import android.util.Log;

import com.ezhan.amr.web.SharedRobotDataProvider;

import org.json.JSONObject;

/**
 * Modbus 命令处理器：通过轮询保持寄存器 / 线圈变化触发任务派发。
 * 由 ModbusService 周期调度（默认 200ms 一次）。
 *
 * 线程模型：
 *   poll() 在 ModbusService 的 workerHandler（后台线程）上执行。
 *   dispatchTask / refreshRcsTaskStatus 等阻塞调用也 post 到 workerHandler，
 *   绝不使用主线程——SharedRobotDataProvider 内部有 latch.await() 会阻塞。
 *
 * 触发机制：
 *  1) 上位机写 HR_CONTROL_COMMAND(0) 为非零命令字 + HR_TASK_JSON_BLOCK(100..) 写任务名 UTF-16，
 *     再置 COIL_TASK_TRIGGER(0)=1。
 *  2) Handler 检测到 COIL_TASK_TRIGGER 上升沿（或 HR_CONTROL_COMMAND 由 0 变非 0），
 *     读取命令字与任务名，调用 SharedRobotDataProvider.dispatchTask(...) 派发，
 *     完成后自动清零 HR_CONTROL_COMMAND 与 COIL_TASK_TRIGGER。
 *
 * 其它寄存器：
 *  - HR_HEARTBEAT(10)：上位机写入递增，Slave 立即回 mirror
 *  - HR_CONTROL_BITS(1000)：bit0=重启服务 bit1=重载配置（暂仅日志）
 *  - COIL_TASK_QUERY(3)：置 1 触发 RCS 任务状态刷新（HR_QUERY_TASK_ID_HI/LO）
 */
public class ModbusCommandHandler {

    private static final String TAG = "ModbusCommandHandler";

    private final ModbusSlaveServer slave;
    private final SharedRobotDataProvider provider;
    private final ModbusDataBinder binder;
    // 后台线程 Handler（由 ModbusService 传入），dispatchTask 等阻塞操作 post 到此线程
    private final Handler workerHandler;

    // 上一次线圈状态（用于边沿检测）
    private boolean lastTriggerCoil = false;
    private boolean lastQueryCoil = false;
    private boolean lastConfigSaveCoil = false;
    private boolean lastConfigLoadCoil = false;

    // 上一次心跳值
    private int lastHeartbeat = 0;
    private boolean heartbeatInitialized = false;

    // 上一次控制命令字（用于检测 HR_CONTROL_COMMAND 0→非0 跳变）
    private int lastControlCommand = 0;

    // 任务派发去抖：避免同一命令短时间内重复触发
    private static final long DISPATCH_DEBOUNCE_MS = 500L;
    private long lastDispatchAt = 0L;
    private int lastDispatchCmd = 0;
    private String lastDispatchTaskName = null;

    public ModbusCommandHandler(ModbusSlaveServer slave,
                                 SharedRobotDataProvider provider,
                                 ModbusDataBinder binder,
                                 Handler workerHandler) {
        this.slave = slave;
        this.provider = provider;
        this.binder = binder;
        this.workerHandler = workerHandler;
    }

    /** 周期调用入口（由 ModbusService 的 Handler 调度） */
    public void poll() {
        try {
            handleHeartbeat();
            handleControlBits();
            handleTaskDispatch();
            handleTaskQuery();
            handleConfigCoils();
        } catch (Exception e) {
            Log.e(TAG, "poll error", e);
        }
    }

    // ===== 心跳镜像 =====
    private void handleHeartbeat() {
        int cur = slave.getHoldingRegister(ModbusRegisterMap.HR_HEARTBEAT);
        if (!heartbeatInitialized) {
            lastHeartbeat = cur;
            heartbeatInitialized = true;
            return;
        }
        if (cur != lastHeartbeat) {
            // 上位机写入新值，回 mirror 到输入侧并不需要——直接保持寄存器本身就是可读的，
            // 这里仅记录，不额外动作。如需镜像到输入寄存器可在此扩展。
            lastHeartbeat = cur;
        }
    }

    // ===== 控制位 =====
    private void handleControlBits() {
        int bits = slave.getHoldingRegister(ModbusRegisterMap.HR_CONTROL_BITS);
        if ((bits & 0x1) != 0) {
            Log.i(TAG, "Control bit0 set: restart service requested (not implemented)");
            slave.setHoldingRegister(ModbusRegisterMap.HR_CONTROL_BITS, bits & ~0x1);
        }
        if ((bits & 0x2) != 0) {
            Log.i(TAG, "Control bit1 set: reload config requested (not implemented)");
            slave.setHoldingRegister(ModbusRegisterMap.HR_CONTROL_BITS, bits & ~0x2);
        }
    }

    // ===== 任务派发 =====
    private void handleTaskDispatch() {
        boolean trigger = slave.getCoil(ModbusRegisterMap.COIL_TASK_TRIGGER);
        int cmd = slave.getHoldingRegister(ModbusRegisterMap.HR_CONTROL_COMMAND);

        boolean cmdRisingEdge = (cmd != 0 && lastControlCommand == 0);
        boolean triggerRisingEdge = trigger && !lastTriggerCoil;
        lastTriggerCoil = trigger;
        lastControlCommand = cmd;

        if (!triggerRisingEdge && !cmdRisingEdge) {
            return;
        }
        if (cmd == 0) {
            Log.w(TAG, "Task trigger fired but HR_CONTROL_COMMAND=0, ignored");
            if (trigger) slave.setCoil(ModbusRegisterMap.COIL_TASK_TRIGGER, false);
            return;
        }

        String taskType = ModbusRegisterMap.commandToString(cmd);
        if (taskType == null) {
            Log.w(TAG, "Unknown command code: " + cmd);
            clearDispatchFlags();
            return;
        }

        // 读取任务名：优先从地址100+（HR_TASK_JSON_BLOCK）读取，为空则从地址1（HR_TARGET_POINT_ID）读取
        String taskName = readTaskNameFromHoldingBlock();
        if (taskName == null || taskName.isEmpty()) {
            // 从地址1读取目标点位ID/任务名
            int targetPointId = slave.getHoldingRegister(ModbusRegisterMap.HR_TARGET_POINT_ID);
            if (targetPointId > 0) {
                taskName = String.valueOf(targetPointId);
            }
            // 如果地址1也没有，尝试读取地址2（HR_TASK_TYPE_EXT）作为任务名
            if (taskName == null || taskName.isEmpty()) {
                int extValue = slave.getHoldingRegister(ModbusRegisterMap.HR_TASK_TYPE_EXT);
                if (extValue > 0) {
                    taskName = String.valueOf(extValue);
                }
            }
        }
        Log.i(TAG, "handleTaskDispatch: cmd=" + cmd + " taskType=" + taskType + " finalTaskName='" + taskName + "'");
        // 任务类型为 control 类（cancel/pause/resume/areaset）时不需要 taskName
        boolean isControlCmd = isControlCommand(taskType);

        // 去抖：同一命令 + 同一任务名 500ms 内不重复派发
        long now = System.currentTimeMillis();
        if (isSameDispatch(cmd, taskName, now)) {
            Log.d(TAG, "Duplicate dispatch ignored (debounce): cmd=" + cmd + " name=" + taskName);
            clearDispatchFlags();
            return;
        }
        lastDispatchCmd = cmd;
        lastDispatchTaskName = taskName;
        lastDispatchAt = now;

        Log.i(TAG, "Dispatching task: type=" + taskType + " name=" + taskName);
        // 异步派发到 workerHandler（后台线程），避免阻塞 poll 线程；
        // 更关键的是 dispatchTask 内部有 latch.await()，绝不能在主线程执行
        final String finalTaskName = taskName;
        workerHandler.post(() -> {
            try {
                JSONObject params = null;
                if ("areaset".equals(taskType)) {
                    params = readAreaSetParams();
                }
                JSONObject result = provider.dispatchTask(taskType, finalTaskName, params);
                Log.i(TAG, "Dispatch result: " + result);
            } catch (Exception e) {
                Log.e(TAG, "Dispatch error", e);
            }
        });

        clearDispatchFlags();
    }

    private boolean isControlCommand(String taskType) {
        return "cancel".equals(taskType) || "pause".equals(taskType)
                || "resume".equals(taskType) || "areaset".equals(taskType)
                || "release".equals(taskType);
    }

    private boolean isSameDispatch(int cmd, String taskName, long now) {
        if (cmd != lastDispatchCmd) return false;
        if (taskName == null && lastDispatchTaskName == null) {
            return now - lastDispatchAt < DISPATCH_DEBOUNCE_MS;
        }
        if (taskName == null || lastDispatchTaskName == null) return false;
        return taskName.equals(lastDispatchTaskName) && (now - lastDispatchAt < DISPATCH_DEBOUNCE_MS);
    }

    private void clearDispatchFlags() {
        slave.setCoil(ModbusRegisterMap.COIL_TASK_TRIGGER, false);
        lastTriggerCoil = false;
        slave.setHoldingRegister(ModbusRegisterMap.HR_CONTROL_COMMAND, 0);
        lastControlCommand = 0;
    }

    /** 从 HR_TASK_JSON_BLOCK 读取 UTF-16 任务名（截到首个 \0） */
    private String readTaskNameFromHoldingBlock() {
        StringBuilder sb = new StringBuilder();
        int start = ModbusRegisterMap.HR_TASK_JSON_BLOCK;
        int len = ModbusRegisterMap.HR_TASK_JSON_BLOCK_LEN;
        Log.d(TAG, "readTaskNameFromHoldingBlock: start=" + start + " len=" + len);
        for (int i = 0; i < len; i++) {
            int v = slave.getHoldingRegister(start + i);
            if (i < 10) { // 只打印前10个寄存器的值
                Log.d(TAG, "  register[" + (start + i) + "] = " + v + " (0x" + String.format("%04X", v & 0xFFFF) + ")");
            }
            if (v == 0) break;
            sb.append((char) (v & 0xFFFF));
        }
        String result = sb.toString().trim();
        Log.d(TAG, "readTaskNameFromHoldingBlock: result='" + result + "'");
        return result;
    }

    /** areaset 参数：暂用保持寄存器扩展字段，可后续扩展为完整 JSON */
    private JSONObject readAreaSetParams() {
        try {
            JSONObject params = new JSONObject();
            int ext = slave.getHoldingRegister(ModbusRegisterMap.HR_TASK_TYPE_EXT);
            params.put("ext", ext);
            return params;
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 任务查询触发 =====
    private void handleTaskQuery() {
        boolean query = slave.getCoil(ModbusRegisterMap.COIL_TASK_QUERY);
        if (query && !lastQueryCoil) {
            int hi = slave.getHoldingRegister(ModbusRegisterMap.HR_QUERY_TASK_ID_HI);
            int lo = slave.getHoldingRegister(ModbusRegisterMap.HR_QUERY_TASK_ID_LO);
            int taskIdInt = ((hi & 0xFFFF) << 16) | (lo & 0xFFFF);
            String taskId = String.valueOf(taskIdInt);

            // 同时检查 HR_QUERY_DATE 是否有 HMI 日期查询
            int dHi = slave.getHoldingRegister(ModbusRegisterMap.HR_QUERY_DATE_HI);
            int dLo = slave.getHoldingRegister(ModbusRegisterMap.HR_QUERY_DATE_LO);
            String dateStr = null;
            if (dHi != 0 || dLo != 0) {
                int dateInt = ((dHi & 0xFFFF) << 16) | (dLo & 0xFFFF);
                dateStr = formatDateString(dateInt);
            }

            final String finalTaskId = taskId;
            final String finalDate = dateStr;
            workerHandler.post(() -> {
                try {
                    binder.refreshRcsTaskStatus(finalTaskId);
                    if (finalDate != null) {
                        binder.refreshHmiTaskStatus(finalDate);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Task query refresh error", e);
                }
            });
        }
        lastQueryCoil = query;
        if (query) {
            slave.setCoil(ModbusRegisterMap.COIL_TASK_QUERY, false);
            lastQueryCoil = false;
        }
    }

    private String formatDateString(int dateInt) {
        // yyyyMMdd 整数 → yyyy-MM-dd
        try {
            int year = dateInt / 10000;
            int month = (dateInt / 100) % 100;
            int day = dateInt % 100;
            return String.format("%04d-%02d-%02d", year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 配置保存 / 加载（占位） =====
    private void handleConfigCoils() {
        boolean save = slave.getCoil(ModbusRegisterMap.COIL_CONFIG_SAVE);
        boolean load = slave.getCoil(ModbusRegisterMap.COIL_CONFIG_LOAD);
        if (save && !lastConfigSaveCoil) {
            Log.i(TAG, "COIL_CONFIG_SAVE triggered (not implemented)");
        }
        if (load && !lastConfigLoadCoil) {
            Log.i(TAG, "COIL_CONFIG_LOAD triggered (not implemented)");
        }
        lastConfigSaveCoil = save;
        lastConfigLoadCoil = load;
        if (save) slave.setCoil(ModbusRegisterMap.COIL_CONFIG_SAVE, false);
        if (load) slave.setCoil(ModbusRegisterMap.COIL_CONFIG_LOAD, false);
    }
}
