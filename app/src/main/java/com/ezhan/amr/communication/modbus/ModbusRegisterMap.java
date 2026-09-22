package com.ezhan.amr.communication.modbus;

/**
 * Modbus 寄存器地址映射表（对应方案 v2 第四章）。
 * 仅定义地址常量，不依赖任何第三方库。
 */
public final class ModbusRegisterMap {

    private ModbusRegisterMap() {
    }

    // ===== 保持寄存器 Holding Register (FC3/FC6/FC16, 可读写) =====
    /** 控制命令字: 1=delivery 2=cruise 3=jack 4=charge 5=park 6=cancel 7=pause 8=resume 9=areaset 10=release */
    public static final int HR_CONTROL_COMMAND = 0;
    /** 目标点位ID/任务名索引（配合命令字） */
    public static final int HR_TARGET_POINT_ID = 1;
    /** 任务类型扩展（jack 操作类型等） */
    public static final int HR_TASK_TYPE_EXT = 2;
    /** 心跳计数器（上位机写入递增, Slave 回 mirror） */
    public static final int HR_HEARTBEAT = 10;
    /** 任务 JSON 块起始（UTF-16, 最多100字符=50寄存器） */
    public static final int HR_TASK_JSON_BLOCK = 100;
    public static final int HR_TASK_JSON_BLOCK_LEN = 50;
    /** 任务 JSON 块结束（含） */
    public static final int HR_TASK_JSON_BLOCK_END = HR_TASK_JSON_BLOCK + HR_TASK_JSON_BLOCK_LEN - 1;
    /** 控制位: bit0=重启服务 bit1=重载配置 */
    public static final int HR_CONTROL_BITS = 1000;
    /** RCS 任务查询 ID 高16位 */
    public static final int HR_QUERY_TASK_ID_HI = 1001;
    /** RCS 任务查询 ID 低16位 */
    public static final int HR_QUERY_TASK_ID_LO = 1002;
    /** HMI 任务查询日期 yyyyMMdd 高16位 */
    public static final int HR_QUERY_DATE_HI = 1003;
    /** HMI 任务查询日期 yyyyMMdd 低16位 */
    public static final int HR_QUERY_DATE_LO = 1004;

    // ===== 输入寄存器 Input Register (FC4 只读) - 状态字段 =====
    /** 机器人状态码: 0=空闲 1=运行 2=充电 3=异常 4=暂停 */
    public static final int IR_ROBOT_STATE = 0;
    /** 电量百分比 0~100 */
    public static final int IR_BATTERY = 1;
    /** 当前任务ID 高16位 */
    public static final int IR_TASK_ID_HI = 2;
    /** 当前任务ID 低16位 */
    public static final int IR_TASK_ID_LO = 3;
    /** 任务执行状态: 0=IDLE 1=EXECUTING 2=COMPLETED 3=FAILED */
    public static final int IR_TASK_EXEC_STATUS = 4;
    /** 任务类型码: 0=None 1=delivery 2=cruise 3=jack 4=charge 5=park */
    public static final int IR_TASK_TYPE_CODE = 5;
    /** 错误码 errCode */
    public static final int IR_ERROR_CODE = 6;
    /** jackState 高16位 */
    public static final int IR_JACK_STATE_HI = 7;
    /** jackState 低16位 */
    public static final int IR_JACK_STATE_LO = 8;
    /** 当前位置 X 浮点(IEEE754双字) 高位 */
    public static final int IR_POS_X_HI = 10;
    public static final int IR_POS_X_LO = 11;
    /** 当前位置 Y */
    public static final int IR_POS_Y_HI = 12;
    public static final int IR_POS_Y_LO = 13;
    /** 当前位置 角度 theta */
    public static final int IR_POS_THETA_HI = 14;
    public static final int IR_POS_THETA_LO = 15;
    /** 当前楼层ID currentGlobalId */
    public static final int IR_FLOOR_ID = 16;
    /** 充电步骤 chargeStep */
    public static final int IR_CHARGE_STEP = 17;
    /** 急停标志 emgStop: 0=正常 1=急停 */
    public static final int IR_EMG_STOP = 18;
    /** 软暂停标志 isSoftPause */
    public static final int IR_SOFT_PAUSE = 19;
    /** 碰撞预警 collisionWarning */
    public static final int IR_COLLISION_WARN = 20;
    /** 到达目标 goalFinish */
    public static final int IR_GOAL_FINISH = 21;

    // ===== 输入寄存器 - 字符串块 =====
    /** 任务名称字符串起始（UTF-16, 40字符=40寄存器） */
    public static final int IR_TASK_NAME = 30;
    public static final int IR_TASK_NAME_LEN = 40;
    public static final int IR_TASK_NAME_END = IR_TASK_NAME + IR_TASK_NAME_LEN - 1;
    /** 当前点位名称（UTF-16, 40字符） */
    public static final int IR_CURRENT_LOCATION = 70;
    public static final int IR_CURRENT_LOCATION_LEN = 40;
    public static final int IR_CURRENT_LOCATION_END = IR_CURRENT_LOCATION + IR_CURRENT_LOCATION_LEN - 1;
    /** 地图名称（UTF-16, 40字符） */
    public static final int IR_MAP_NAME = 110;
    public static final int IR_MAP_NAME_LEN = 40;
    public static final int IR_MAP_NAME_END = IR_MAP_NAME + IR_MAP_NAME_LEN - 1;

    // ===== 输入寄存器 - JSON 块 =====
    /** robotStatus JSON 块（200寄存器=400 UTF-16字符） */
    public static final int IR_JSON_ROBOT_STATUS = 200;
    public static final int IR_JSON_ROBOT_STATUS_LEN = 200;
    /** allMapPoints JSON 块（200寄存器） */
    public static final int IR_JSON_ALL_MAP_POINTS = 400;
    public static final int IR_JSON_ALL_MAP_POINTS_LEN = 200;
    /** currentMapInfo JSON 块（100寄存器） */
    public static final int IR_JSON_CURRENT_MAP_INFO = 600;
    public static final int IR_JSON_CURRENT_MAP_INFO_LEN = 100;
    /** safetyAreas JSON 块（100寄存器） */
    public static final int IR_JSON_SAFETY_AREAS = 700;
    public static final int IR_JSON_SAFETY_AREAS_LEN = 100;
    /** navigatePath JSON 块（100寄存器） */
    public static final int IR_JSON_NAVIGATE_PATH = 800;
    public static final int IR_JSON_NAVIGATE_PATH_LEN = 100;
    /** rcsTaskStatus JSON 块（100寄存器） */
    public static final int IR_JSON_RCS_TASK_STATUS = 900;
    public static final int IR_JSON_RCS_TASK_STATUS_LEN = 100;
    /** hmiTaskStatus JSON 块（200寄存器） */
    public static final int IR_JSON_HMI_TASK_STATUS = 1000;
    public static final int IR_JSON_HMI_TASK_STATUS_LEN = 200;
    /** hmiTaskStatusDetails JSON 块（200寄存器） */
    public static final int IR_JSON_HMI_TASK_DETAILS = 1200;
    public static final int IR_JSON_HMI_TASK_DETAILS_LEN = 200;

    /** 输入寄存器总容量（确保地址覆盖到 1399） */
    public static final int INPUT_REGISTER_SIZE = 1400;
    /** 保持寄存器总容量 */
    public static final int HOLDING_REGISTER_SIZE = 1010;

    // ===== 线圈 Coil (FC1/FC5/FC15 可读写) =====
    /** 任务触发位（边沿触发, 写1触发保持寄存器0的命令, 执行后自动清零） */
    public static final int COIL_TASK_TRIGGER = 0;
    /** 配置保存触发 */
    public static final int COIL_CONFIG_SAVE = 1;
    /** 配置加载触发 */
    public static final int COIL_CONFIG_LOAD = 2;
    /** 任务查询触发（用保持寄存器1001/1002刷新JSON块900） */
    public static final int COIL_TASK_QUERY = 3;
    public static final int COIL_SIZE = 16;

    // ===== 离散输入 Discrete Input (FC2 只读) =====
    public static final int DI_TASK_RUNNING = 0;
    public static final int DI_CHARGING = 1;
    public static final int DI_EMG_STOP = 2;
    public static final int DI_LOW_BATTERY = 3;
    public static final int DI_SOFT_PAUSE = 4;
    public static final int DI_COLLISION_WARN = 5;
    public static final int DI_TASK_COMPLETED = 6;
    public static final int DI_TASK_FAILED = 7;
    public static final int DISCRETE_INPUT_SIZE = 16;

    // ===== 命令字枚举 =====
    public static final int CMD_DELIVERY = 1;
    public static final int CMD_CRUISE = 2;
    public static final int CMD_JACK = 3;
    public static final int CMD_CHARGE = 4;
    public static final int CMD_PARK = 5;
    public static final int CMD_CANCEL = 6;
    public static final int CMD_PAUSE = 7;
    public static final int CMD_RESUME = 8;
    public static final int CMD_ARESET = 9;
    /** 放行：提前结束站点停留倒计时 */
    public static final int CMD_RELEASE = 10;

    /** 任务类型码 -> taskType 字符串（用于 dispatchTask） */
    public static String commandToString(int cmd) {
        switch (cmd) {
            case CMD_DELIVERY: return "delivery";
            case CMD_CRUISE: return "cruise";
            case CMD_JACK: return "jack";
            case CMD_CHARGE: return "charge";
            case CMD_PARK: return "park";
            case CMD_CANCEL: return "cancel";
            case CMD_PAUSE: return "pause";
            case CMD_RESUME: return "resume";
            case CMD_ARESET: return "areaset";
            case CMD_RELEASE: return "release";
            default: return null;
        }
    }
}
