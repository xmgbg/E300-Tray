package com.ezhan.amr.communication.modbus;

import android.util.Log;

import com.ezhan.amr.web.SharedRobotDataProvider;

import org.json.JSONObject;

/**
 * 将 SharedRobotDataProvider 的数据映射到 Modbus 输入寄存器 / 离散输入。
 * 由 ModbusService 周期调度：
 *   - refreshStatus(): 高频（1s）刷新状态字段 + robotStatus JSON + 离散输入
 *   - refreshMapData(): 低频（10s）刷新地图类 JSON 块
 *   - refreshRcsTaskStatus(taskId) / refreshHmiTaskStatus(date): 由线圈触发
 */
public class ModbusDataBinder {

    private static final String TAG = "ModbusDataBinder";

    private final ModbusSlaveServer slave;
    private final SharedRobotDataProvider provider;

    public ModbusDataBinder(ModbusSlaveServer slave, SharedRobotDataProvider provider) {
        this.slave = slave;
        this.provider = provider;
    }

    /** 高频刷新：机器人状态字段 + robotStatus JSON + 离散输入 */
    public void refreshStatus() {
        try {
            JSONObject resp = provider.getRobotStatus();
            if (!"success".equals(resp.optString("status"))) {
                return;
            }
            JSONObject data = resp.optJSONObject("data");
            if (data == null) return;

            String taskStatus = data.optString("task_status", "IDLE");
            String taskType = data.optString("taskType", "None");
            String taskName = data.optString("taskName", "");
            String taskId = data.optString("taskId", "");

            int battery = data.optInt("powerQuantity", 0);
            int errCode = data.optInt("errCode", 0);
            int jackState = data.optInt("jackState", 0);
            int floorId = data.optInt("currentGlobalId", 0);
            int chargeStep = data.optInt("chargeStep", 0);
            boolean emgStop = data.optBoolean("emgStop", false);
            boolean softPause = data.optBoolean("isSoftPause", false);
            boolean collisionWarn = data.optBoolean("collisionWarning", false);
            boolean goalFinish = data.optBoolean("goalFinish", false);

            // 机器人状态码
            int robotState;
            if (emgStop) robotState = 3;
            else if (softPause) robotState = 4;
            else if (chargeStep > 0) robotState = 2;
            else if ("EXECUTING".equals(taskStatus)) robotState = 1;
            else robotState = 0;
            slave.setInputRegister(ModbusRegisterMap.IR_ROBOT_STATE, robotState);

            slave.setInputRegister(ModbusRegisterMap.IR_BATTERY, clamp(battery, 0, 100));
            slave.setInputRegister(ModbusRegisterMap.IR_TASK_EXEC_STATUS, taskExecCode(taskStatus));
            slave.setInputRegister(ModbusRegisterMap.IR_TASK_TYPE_CODE, taskTypeCode(taskType));
            slave.setInputRegister(ModbusRegisterMap.IR_ERROR_CODE, errCode);
            setRegisterPair(ModbusRegisterMap.IR_JACK_STATE_HI, ModbusRegisterMap.IR_JACK_STATE_LO, jackState);

            // 位置（double -> float -> 2 寄存器）
            JSONObject pos = data.optJSONObject("position");
            if (pos != null) {
                setFloatPair(ModbusRegisterMap.IR_POS_X_HI, ModbusRegisterMap.IR_POS_X_LO, pos.optDouble("x", 0));
                setFloatPair(ModbusRegisterMap.IR_POS_Y_HI, ModbusRegisterMap.IR_POS_Y_LO, pos.optDouble("y", 0));
                setFloatPair(ModbusRegisterMap.IR_POS_THETA_HI, ModbusRegisterMap.IR_POS_THETA_LO, pos.optDouble("theta", 0));
                writeInputString(ModbusRegisterMap.IR_MAP_NAME, ModbusRegisterMap.IR_MAP_NAME_LEN,
                        pos.optString("mapName", ""));
            }

            slave.setInputRegister(ModbusRegisterMap.IR_FLOOR_ID, floorId);
            slave.setInputRegister(ModbusRegisterMap.IR_CHARGE_STEP, chargeStep);
            slave.setInputRegister(ModbusRegisterMap.IR_EMG_STOP, emgStop ? 1 : 0);
            slave.setInputRegister(ModbusRegisterMap.IR_SOFT_PAUSE, softPause ? 1 : 0);
            slave.setInputRegister(ModbusRegisterMap.IR_COLLISION_WARN, collisionWarn ? 1 : 0);
            slave.setInputRegister(ModbusRegisterMap.IR_GOAL_FINISH, goalFinish ? 1 : 0);

            // 任务 ID（数字字符串转 int，否则存 0）
            setRegisterPair(ModbusRegisterMap.IR_TASK_ID_HI, ModbusRegisterMap.IR_TASK_ID_LO, parseTaskIdInt(taskId));

            // 字符串块
            writeInputString(ModbusRegisterMap.IR_TASK_NAME, ModbusRegisterMap.IR_TASK_NAME_LEN, taskName);
            writeInputString(ModbusRegisterMap.IR_CURRENT_LOCATION, ModbusRegisterMap.IR_CURRENT_LOCATION_LEN,
                    data.optString("currentLocation", ""));

            // 离散输入
            slave.setDiscreteInput(ModbusRegisterMap.DI_TASK_RUNNING, "EXECUTING".equals(taskStatus));
            slave.setDiscreteInput(ModbusRegisterMap.DI_CHARGING, chargeStep > 0);
            slave.setDiscreteInput(ModbusRegisterMap.DI_EMG_STOP, emgStop);
            slave.setDiscreteInput(ModbusRegisterMap.DI_LOW_BATTERY, battery <= 15);
            slave.setDiscreteInput(ModbusRegisterMap.DI_SOFT_PAUSE, softPause);
            slave.setDiscreteInput(ModbusRegisterMap.DI_COLLISION_WARN, collisionWarn);
            slave.setDiscreteInput(ModbusRegisterMap.DI_TASK_COMPLETED, "COMPLETED".equals(taskStatus));
            slave.setDiscreteInput(ModbusRegisterMap.DI_TASK_FAILED, errCode != 0);

            // robotStatus JSON 块
            writeInputJson(ModbusRegisterMap.IR_JSON_ROBOT_STATUS, ModbusRegisterMap.IR_JSON_ROBOT_STATUS_LEN, resp);
        } catch (Exception e) {
            Log.e(TAG, "refreshStatus error", e);
        }
    }

    /** 低频刷新：地图类 JSON 块 */
    public void refreshMapData() {
        try {
            writeInputJson(ModbusRegisterMap.IR_JSON_ALL_MAP_POINTS,
                    ModbusRegisterMap.IR_JSON_ALL_MAP_POINTS_LEN, provider.getAllMapPoints());
            writeInputJson(ModbusRegisterMap.IR_JSON_CURRENT_MAP_INFO,
                    ModbusRegisterMap.IR_JSON_CURRENT_MAP_INFO_LEN, provider.getCurrentMapInfo());
            writeInputJson(ModbusRegisterMap.IR_JSON_SAFETY_AREAS,
                    ModbusRegisterMap.IR_JSON_SAFETY_AREAS_LEN, provider.getSafetyAreas());
            writeInputJson(ModbusRegisterMap.IR_JSON_NAVIGATE_PATH,
                    ModbusRegisterMap.IR_JSON_NAVIGATE_PATH_LEN, provider.getNavigatePath());
        } catch (Exception e) {
            Log.e(TAG, "refreshMapData error", e);
        }
    }

    /** 由线圈3触发：根据保持寄存器 1001/1002 的任务名派发查询 */
    public void refreshRcsTaskStatus(String taskId) {
        try {
            writeInputJson(ModbusRegisterMap.IR_JSON_RCS_TASK_STATUS,
                    ModbusRegisterMap.IR_JSON_RCS_TASK_STATUS_LEN, provider.getRcsTaskStatus(taskId));
        } catch (Exception e) {
            Log.e(TAG, "refreshRcsTaskStatus error", e);
        }
    }

    /** HMI 任务状态查询 */
    public void refreshHmiTaskStatus(String date) {
        try {
            writeInputJson(ModbusRegisterMap.IR_JSON_HMI_TASK_STATUS,
                    ModbusRegisterMap.IR_JSON_HMI_TASK_STATUS_LEN, provider.getHmiTaskStatus(date));
        } catch (Exception e) {
            Log.e(TAG, "refreshHmiTaskStatus error", e);
        }
    }

    // ===== 工具方法 =====

    private void setRegisterPair(int hiRef, int loRef, int value) {
        slave.setInputRegister(hiRef, (value >>> 16) & 0xFFFF);
        slave.setInputRegister(loRef, value & 0xFFFF);
    }

    private void setFloatPair(int hiRef, int loRef, double value) {
        int bits = Float.floatToIntBits((float) value);
        slave.setInputRegister(hiRef, (bits >>> 16) & 0xFFFF);
        slave.setInputRegister(loRef, bits & 0xFFFF);
    }

    /** 把字符串以 UTF-16 写入输入寄存器块（每个寄存器一个 char），不足补 0 */
    private void writeInputString(int startRef, int len, String s) {
        if (s == null) s = "";
        int n = Math.min(s.length(), len);
        for (int i = 0; i < len; i++) {
            int v = i < n ? (s.charAt(i) & 0xFFFF) : 0;
            slave.setInputRegister(startRef + i, v);
        }
    }

    /** 把 JSON 字符串写入输入寄存器块，超长截断 */
    private void writeInputJson(int startRef, int len, JSONObject json) {
        if (json == null) return;
        writeInputString(startRef, len, json.toString());
    }

    private int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private int taskExecCode(String status) {
        switch (status) {
            case "EXECUTING": return 1;
            case "COMPLETED": return 2;
            default: return 0; // IDLE
        }
    }

    private int taskTypeCode(String taskType) {
        if (taskType == null) return 0;
        switch (taskType.toLowerCase()) {
            case "delivery": return 1;
            case "cruise": return 2;
            case "jack": return 3;
            case "charge": return 4;
            case "park": return 5;
            default: return 0;
        }
    }

    private int parseTaskIdInt(String taskId) {
        if (taskId == null || taskId.isEmpty() || "None".equals(taskId)) return 0;
        try {
            return Integer.parseInt(taskId.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
