package com.ezhan.amr.data.datastore;

import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;

import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.LocationCodeConfig;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.RobotViewModel;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class DataStoreKeys {
    // 基础类型键
    public static final Preferences.Key<Integer> VOLUME_LEVEL =
            PreferencesKeys.intKey("volumeLevel");

    public static final Preferences.Key<Double> SET_SPEED =
            PreferencesKeys.doubleKey("setSpeed");

    public static final Preferences.Key<Integer> DELIVERY_WAIT_DURATION =
            PreferencesKeys.intKey("deliveryWaitDuration");

    public static final Preferences.Key<Integer> LOW_POWER =
            PreferencesKeys.intKey("lowPower");

    // Battery level management thresholds (电量管理阈值)
    public static final Preferences.Key<Integer> BATTERY_CRITICAL_LEVEL =
            PreferencesKeys.intKey("batteryCriticalLevel");  // 极低电量 (default 5)
    public static final Preferences.Key<Integer> BATTERY_LOW_LEVEL =
            PreferencesKeys.intKey("batteryLowLevel");       // legacy, merged into min working
    public static final Preferences.Key<Integer> BATTERY_SAFE_LEVEL =
            PreferencesKeys.intKey("batterySafeLevel");      // 最低工作电量 (default 20)
    public static final Preferences.Key<Integer> BATTERY_IDLE_LEVEL =
            PreferencesKeys.intKey("batteryIdleLevel");      // 闲时充电上限 (default 60)
    public static final Preferences.Key<Integer> BATTERY_FULL_LEVEL =
            PreferencesKeys.intKey("batteryFullLevel");      // 充满判定 (default 100)

    public static final Preferences.Key<Integer> PAUSE_WAIT_DURATION =
            PreferencesKeys.intKey("pauseWaitDuration");

    public static final Preferences.Key<Integer> QUEUED_TASK_COOLDOWN =
            PreferencesKeys.intKey("queuedTaskCooldown");

    public static final Preferences.Key<Boolean> IS_AUTO_CHARGE =
            PreferencesKeys.booleanKey("isAutoCharge");

    public static final Preferences.Key<Boolean> IS_IDLE_CHARGE =
            PreferencesKeys.booleanKey("isIdleCharge");

    public static final Preferences.Key<Integer> AUTO_CHARGE_START_MINUTE =
            PreferencesKeys.intKey("autoChargeStartMinute");

    public static final Preferences.Key<Integer> AUTO_CHARGE_END_MINUTE =
            PreferencesKeys.intKey("autoChargeEndMinute");

    public static final Preferences.Key<Integer> IDLE_CHARGE_WAIT_MINUTES =
            PreferencesKeys.intKey("idleChargeWaitMinutes");

    /** 充满电后自动回待命点 */
    public static final Preferences.Key<Boolean> IS_FULL_CHARGE_RETURN_HOME =
            PreferencesKeys.booleanKey("isFullChargeReturnHome");

    // 闲时回待命功能相关键
    public static final Preferences.Key<Boolean> IS_IDLE_RETURN_HOME =
            PreferencesKeys.booleanKey("isIdleReturnHome");

    public static final Preferences.Key<Integer> IDLE_RETURN_WAIT_SECONDS =
            PreferencesKeys.intKey("idleReturnWaitSeconds");

    public static final Preferences.Key<Boolean> IS_VISUAL_CRUISE =
            PreferencesKeys.booleanKey("isVisualCruise");

    public static final Preferences.Key<Boolean> IS_JACK_MODE =
            PreferencesKeys.booleanKey("isJackMode");

    public static final Preferences.Key<Boolean> IS_SHOW_TRAY =
            PreferencesKeys.booleanKey("isShowTray");

    public static final Preferences.Key<Boolean> IS_USE_PASSWORD =
            PreferencesKeys.booleanKey("isUsePassword");

    public static final Preferences.Key<String> PASSWORD =
            PreferencesKeys.stringKey("password");

    public static final Preferences.Key<Integer> USE_LANGUAGE =
            PreferencesKeys.intKey("useLanguage");

    public static final Preferences.Key<String> HOSPITAL_REGISTER_CODE =
            PreferencesKeys.stringKey("hospital_register_code");

    public static final Type POSITION_TYPE = new TypeToken<Position>(){}.getType();
    public static final Preferences.Key<String> POSITION_MAP =
            PreferencesKeys.stringKey("stationMap");
    public static final Type POSITION_MAP_TYPE =
            new TypeToken<Map<String, Position>>(){}.getType();

    public static final Preferences.Key<String> VOICE_PROMPT_LIST =
            PreferencesKeys.stringKey("voicePromptList");
    public static final Preferences.Key<String> VOICE_PROMPT_LANGUAGE =
            PreferencesKeys.stringKey("voicePromptLanguage");
    public static final Type VOICE_PROMPT_MAP_TYPE =
            new TypeToken<Map<String, String>>(){}.getType();

    public static final Preferences.Key<String> JACK_TASK_MAP =
            PreferencesKeys.stringKey("jackTaskMap");
    public static final Type JACK_TASK_MAP_TYPE =
            new TypeToken<Map<Integer, JackTask>>(){}.getType();

    public static final Preferences.Key<String> CRUISE_TASK_MAP =
            PreferencesKeys.stringKey("cruiseTaskMap");
    public static final Type CRUISE_TASK_MAP_TYPE =
            new TypeToken<Map<Integer, CruiseTask>>(){}.getType();

    public static final Preferences.Key<String> CALL_BUTTONS =
            PreferencesKeys.stringKey("call_buttons");

    public static final Preferences.Key<String> ELEVATOR_CONFIGS = PreferencesKeys.stringKey("elevator_configs");
    // 在DataStoreKeys.java中添加
    public static final Preferences.Key<String> CALL_BOXES = new Preferences.Key<>("call_boxes");
   // public static final Type ELEVATOR_CONFIGS_TYPE = new TypeToken<List<SharedViewModel.ElevatorConfig>>(){}.getType();

    // 历史任务记录
    public static final Preferences.Key<String> TASK_RECORDS = new Preferences.Key<>("task_records");
    // 历史任务保存时长（天）
    public static final Preferences.Key<Integer> TASK_RECORD_RETENTION_DAYS = new Preferences.Key<>("task_record_retention_days");

    // 历史任务记录类型
    public static final Type TASK_RECORD_LIST_TYPE = new TypeToken<List<TaskRecord>>() {}.getType();
    public static final Type ELEVATOR_CONFIGS_TYPE = new TypeToken<List<ElevatorViewModel.ElevatorConfig>>(){}.getType();
    // 在 DataStoreKeys.java 的适当位置添

    public static final Preferences.Key<String> ROBOT_CONFIGS =
            PreferencesKeys.stringKey("robot_configs");


    public static final Type ROBOT_CONFIG_LIST_TYPE =
            new TypeToken<List<RobotViewModel.RobotConfig>>(){}.getType();
    // 添加语言变化状态键
    public static final Preferences.Key<Boolean> LANGUAGE_CHANGED =
            PreferencesKeys.booleanKey("language_changed");

    public static final Preferences.Key<String> LAST_MACHINE_ID =
            PreferencesKeys.stringKey("last_machine_id");
    // 在对象类型键部分添加两个新点位的键
    // 用户列表
    public static final Preferences.Key<String> USER_LIST =
            PreferencesKeys.stringKey("user_list");
    public static final Type USER_LIST_TYPE =
            new TypeToken<List<com.ezhan.amr.data.datatype.User>>(){}.getType();

    // RFID卡片列表
    public static final Preferences.Key<String> RFID_LIST =
            PreferencesKeys.stringKey("rfid_list");
    public static final Type RFID_LIST_TYPE =
            new TypeToken<List<com.ezhan.amr.data.datatype.RfidData>>(){}.getType();

    // 科室列表
    public static final Preferences.Key<String> DEPARTMENT_LIST =
            PreferencesKeys.stringKey("department_list");
    public static final Type DEPARTMENT_LIST_TYPE =
            new TypeToken<List<com.ezhan.amr.data.datatype.Department>>(){}.getType();

    public static final Preferences.Key<String> LOCATION_CODE_CONFIG_MAP =
            PreferencesKeys.stringKey("location_code_config_map");
    public static final Type LOCATION_CODE_CONFIG_MAP_TYPE =
            new TypeToken<Map<Integer, LocationCodeConfig>>(){}.getType();

    public static final Preferences.Key<String> CURRENT_MAP_POINTS =
            PreferencesKeys.stringKey("current_map_points");

    public static final Type MAP_POINTS_TYPE =
            new TypeToken<MultiBuildingMapPoints>(){}.getType();
    public static final Preferences.Key<Boolean> IS_VIRTUAL_ORBIT_MODE =
            PreferencesKeys.booleanKey("is_virtual_orbit_mode");

    public static final Preferences.Key<Boolean> IS_VIRTUAL_ORBIT_ONE_WAY =
            PreferencesKeys.booleanKey("is_virtual_orbit_one_way");

    public static final Preferences.Key<Double> RECOGNIZE_DISTANCE =
            PreferencesKeys.doubleKey("recognize_distance");

    // 虚拟轨道绕障时间（秒）：0为立即绕障，-1不绕障，大于0是多少秒后绕障
    public static final Preferences.Key<Integer> VIRTUAL_ORBIT_OBSTACLE_TIME =
            PreferencesKeys.intKey("virtual_orbit_obstacle_time");

    // 音乐选择
    public static final Preferences.Key<String> RUNNING_MUSIC = 
            PreferencesKeys.stringKey("running_music");

    // 地图区域数据
    public static final Preferences.Key<String> MAP_AREAS =
            PreferencesKeys.stringKey("map_areas");
    public static final Type MAP_AREAS_TYPE =
            new TypeToken<List<com.ezhan.amr.data.datatype.MapArea>>(){}.getType();

    // 交管机器人列表 (全局, 所有交管区域共用, 非按区域配置)
    public static final Preferences.Key<String> TRAFFIC_ROBOTS =
            PreferencesKeys.stringKey("traffic_robots");
    public static final Type TRAFFIC_ROBOTS_TYPE =
            new TypeToken<List<com.ezhan.amr.data.datatype.OtherRobot>>(){}.getType();

    public static final Preferences.Key<Integer> ROBOT_ID =
            PreferencesKeys.intKey("robot_id");

    public static final Preferences.Key<Integer> LORA_CHANNEL =
            PreferencesKeys.intKey("lora_channel");

    public static final Preferences.Key<Integer> LORA_ADDRESS =
            PreferencesKeys.intKey("lora_address");

    // 云端连接配置
    public static final Preferences.Key<String> CLOUD_IP =
            PreferencesKeys.stringKey("cloud_ip");

    public static final Preferences.Key<Integer> CLOUD_PORT =
            PreferencesKeys.intKey("cloud_port");

    // 建图规则浮动提示框位置和查看状态
    public static final Preferences.Key<Float> MAP_RULES_TIP_X =
            PreferencesKeys.floatKey("map_rules_tip_x");

    public static final Preferences.Key<Float> MAP_RULES_TIP_Y =
            PreferencesKeys.floatKey("map_rules_tip_y");

    public static final Preferences.Key<Boolean> MAP_RULES_VIEWED =
            PreferencesKeys.booleanKey("map_rules_viewed");

    // 电梯操作提示框位置和查看状态
    public static final Preferences.Key<Float> ELEVATOR_TIP_X =
            PreferencesKeys.floatKey("elevator_tip_x");

    public static final Preferences.Key<Float> ELEVATOR_TIP_Y =
            PreferencesKeys.floatKey("elevator_tip_y");

    public static final Preferences.Key<Boolean> ELEVATOR_TIP_VIEWED =
            PreferencesKeys.booleanKey("elevator_tip_viewed");
}
