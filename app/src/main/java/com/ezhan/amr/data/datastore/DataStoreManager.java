package com.ezhan.amr.data.datastore;

import static com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.MAP_POINTS_TYPE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP_TYPE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.VOICE_PROMPT_MAP_TYPE;

import android.content.Context;
import android.util.Log;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.viewmodels.RobotViewModel;
import com.google.gson.Gson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class DataStoreManager {
    private static DataStoreManager instance;
    private final RxDataStore<Preferences> dataStore;
    private final Gson gson = new Gson();
    private DataStoreManager(Context context) {
        dataStore = new RxPreferenceDataStoreBuilder(context, "app_settings").build();
    }

    // 定义默认语音提示词（使用资源键名）
    private static final Map<String, String> DEFAULT_VOICE_TEXTS = new HashMap<>();
    static {
        DEFAULT_VOICE_TEXTS.put("departure_broadcast", "开始配送任务");
        DEFAULT_VOICE_TEXTS.put("arrival", "已经到达站点");
        DEFAULT_VOICE_TEXTS.put("position_lost", "我好像迷路了，请把我推回充电桩");
        DEFAULT_VOICE_TEXTS.put("lead_start", "开始领航，请跟紧");
        DEFAULT_VOICE_TEXTS.put("lead_arrival", "已经到达您的位置了");
        DEFAULT_VOICE_TEXTS.put("low_battery", "电量不足，请及时充电");
        DEFAULT_VOICE_TEXTS.put("obstacle_alert", "前方有障碍物，请小心");
        DEFAULT_VOICE_TEXTS.put("task_completed", "任务已完成，感谢使用");
        DEFAULT_VOICE_TEXTS.put("operation_error_prompt", "操作错误，请检查网络并再次启动");
        DEFAULT_VOICE_TEXTS.put("charge_complete", "充电已完成，可以开始工作");
        DEFAULT_VOICE_TEXTS.put("return_arrival", "我回来了，还有什么事情吩咐吗");
        DEFAULT_VOICE_TEXTS.put("task_interrupted", "任务中断了");
        DEFAULT_VOICE_TEXTS.put("lead_departure", "请跟我来吧");
        DEFAULT_VOICE_TEXTS.put("cruise_departure", "我出发了，请让让我");
        DEFAULT_VOICE_TEXTS.put("cruise_arrival", "我已经到达了，请尽快");
        DEFAULT_VOICE_TEXTS.put("pedestrian_alert", "运行中");
        DEFAULT_VOICE_TEXTS.put("go_charging", "我要去充电了");
        DEFAULT_VOICE_TEXTS.put("emergency_stop1", "机器人急停被触发");
        DEFAULT_VOICE_TEXTS.put("collision_alert", "机器人发生碰撞,请恢复");
        DEFAULT_VOICE_TEXTS.put("navigation_error", "请避让");
    }

    // 添加任务记录
    public void addTaskRecord(TaskRecord record) {
        getTaskRecords().firstOrError().subscribe(records -> {
            // 添加新记录
            List<TaskRecord> newRecords = new ArrayList<>(records);
            newRecords.add(0, record); // 添加到开头

            // 保存记录并清除过期记录
            saveTaskRecords(newRecords);
            purgeOldRecords(newRecords);
        });
    }

    // 更新任务记录
    public void updateTaskRecord(TaskRecord updatedRecord) {
        getTaskRecords().firstOrError().subscribe(records -> {
            List<TaskRecord> newRecords = new ArrayList<>();
            for (TaskRecord record : records) {
                if (record.getCreateTime() == updatedRecord.getCreateTime() &&
                        record.getTaskName().equals(updatedRecord.getTaskName())) {
                    newRecords.add(updatedRecord);
                } else {
                    newRecords.add(record);
                }
            }
            saveTaskRecords(newRecords);
        });
    }

    // 清除过期记录
    private void purgeOldRecords(List<TaskRecord> records) {
        getTaskRecordRetentionDays().firstOrError().subscribe(days -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);

            List<TaskRecord> validRecords = new ArrayList<>();
            for (TaskRecord record : records) {
                if (record.getCreateTime() > cutoff) {
                    validRecords.add(record);
                }
            }

            if (validRecords.size() < records.size()) {
                saveTaskRecords(validRecords);
            }
        });
    }

    // 获取历史任务保存时长
    public Flowable<Integer> getTaskRecordRetentionDays() {
        return getInt(DataStoreKeys.TASK_RECORD_RETENTION_DAYS, 30); // 默认30天
    }

    public void setTaskRecordRetentionDays(int days) {
        setInt(DataStoreKeys.TASK_RECORD_RETENTION_DAYS, days);
    }

    // 获取历史任务记录
    public Flowable<List<TaskRecord>> getTaskRecords() {
        return getObject(DataStoreKeys.TASK_RECORDS,
                DataStoreKeys.TASK_RECORD_LIST_TYPE,
                Collections.emptyList());
    }

    // 保存历史任务记录
    public void saveTaskRecords(List<TaskRecord> records) {
        // 调试：检查第一条记录的 taskName
        if (!records.isEmpty()) {
            Log.d("DataStore", "保存任务名称: " + records.get(0).getTaskName());
        }
        setObject(DataStoreKeys.TASK_RECORDS, records, DataStoreKeys.TASK_RECORD_LIST_TYPE);
    }

    // 保存巡航任务
    public void saveCruiseTaskMap(Map<Integer, CruiseTask> taskMap) {
        setObject(DataStoreKeys.CRUISE_TASK_MAP, taskMap, DataStoreKeys.CRUISE_TASK_MAP_TYPE);
    }



    public static synchronized DataStoreManager getInstance(Context context) {
        if (instance == null) {
            instance = new DataStoreManager(context.getApplicationContext());
        }
        return instance;
    }

    // 新增方法：获取默认语音提示词
    public Map<String, String> getDefaultVoiceTexts() {
        return new HashMap<>(DEFAULT_VOICE_TEXTS);
    }

    // 基础类型操作
    public @NonNull Flowable<Integer> getInt(Preferences.Key<Integer> key, int defaultValue) {
        return dataStore.data().map(prefs -> prefs.get(key) != null ? prefs.get(key) : defaultValue)
                .subscribeOn(Schedulers.io());
    }

    public void setInt(Preferences.Key<Integer> key, int value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    /**
     * 同步写入整数，防止断电数据丢失
     */
    public void setIntBlocking(Preferences.Key<Integer> key, int value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).blockingGet();
    }

    public Flowable<String> getString(Preferences.Key<String> key, String defaultValue) {
        return dataStore.data()
                .map(prefs -> prefs.get(key) != null ? prefs.get(key) : defaultValue)
                .subscribeOn(Schedulers.io());
    }

    public void putString(Preferences.Key<String> key, String value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    /**
     * 同步写入字符串，防止断电数据丢失
     */
    public void putStringBlocking(Preferences.Key<String> key, String value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).blockingGet();
    }

    public Flowable<Boolean> getBoolean(Preferences.Key<Boolean> key, boolean defaultValue) {
        return dataStore.data()
                .map(prefs -> prefs.get(key) != null ? prefs.get(key) : defaultValue)
                .subscribeOn(Schedulers.io());
    }

    public void setBoolean(Preferences.Key<Boolean> key, boolean value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    public Flowable<Double> getDouble(Preferences.Key<Double> key, Double defaultValue) {
        return dataStore.data()
                .map(prefs -> prefs.get(key) != null ? prefs.get(key) : defaultValue)
                .subscribeOn(Schedulers.io());
    }

    public void setDouble(Preferences.Key<Double> key, Double value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    public Flowable<Float> getFloat(Preferences.Key<Float> key, Float defaultValue) {
        return dataStore.data()
                .map(prefs -> prefs.get(key) != null ? prefs.get(key) : defaultValue)
                .subscribeOn(Schedulers.io());
    }

    public void setFloat(Preferences.Key<Float> key, Float value) {
        dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutablePrefs = prefs.toMutablePreferences();
            mutablePrefs.set(key, value);
            return Single.just(mutablePrefs);
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    public Flowable<String> getCloudIp() {
        return getString(DataStoreKeys.CLOUD_IP, "");
    }

    public void saveCloudIp(String ip) {
        putStringBlocking(DataStoreKeys.CLOUD_IP, ip == null ? "" : ip);
    }

    public Flowable<Integer> getCloudPort() {
        return getInt(DataStoreKeys.CLOUD_PORT, 1883);
    }

    public void saveCloudPort(int port) {
        setIntBlocking(DataStoreKeys.CLOUD_PORT, port);
    }

    // 对象类型操作
    public <T> Flowable<T> getObject(Preferences.Key<String> key, Type type, T defaultValue) {
        return dataStore.data()
                .map(prefs -> {
                    try {
                        String json = prefs.get(key);
                        return json != null ? gson.fromJson(json, type) : defaultValue;
                    } catch (Exception e) {
                        return defaultValue;
                    }
                })
                .onErrorReturnItem(defaultValue)
                .subscribeOn(Schedulers.io());
    }

    public <T> void setObject(Preferences.Key<String> key, T value, Type type) {
        String keyName = key != null ? key.toString() : "null";
        Log.i("taskDebug7", "[DS-SET] setObject START: key=" + keyName);
        try {
            dataStore.updateDataAsync(prefs -> {
                        MutablePreferences mutablePrefs = prefs.toMutablePreferences();
                        String json = gson.toJson(value, type);
                        mutablePrefs.set(key, json);
                        return Single.just(mutablePrefs);
                    })
                    .doOnError(e -> Log.e("taskDebug7", "[DS-SET] setObject FAILED: key=" + keyName, e))
                    .doOnSuccess(prefs -> Log.i("taskDebug7", "[DS-SET] setObject COMPLETE: key=" + keyName))
                    .subscribeOn(Schedulers.io())
                    .subscribe();
        } catch (Exception e) {
            Log.e("taskDebug7", "[DS-SET] setObject EXCEPTION: key=" + keyName, e);
        }
    }

    // 获取语音提示词列表（关键优化）- 修复类型错误
    public Flowable<Map<String, String>> getVoicePromptList() {
        // 使用正确的类型转换
        return getObject(DataStoreKeys.VOICE_PROMPT_LIST, VOICE_PROMPT_MAP_TYPE, Collections.<String, String>emptyMap())
                .map(storedMap -> {
                    // 创建包含默认值的新映射
                    Map<String, String> result = new HashMap<>(DEFAULT_VOICE_TEXTS);

                    // 用存储值覆盖默认值（显式类型转换）
                    if (storedMap != null && storedMap instanceof Map) {
                        try {
                            // 安全类型转换
                            Map<String, String> typedMap = (Map<String, String>) storedMap;
                            result.putAll(typedMap);
                        } catch (ClassCastException e) {
                            Log.e("DataStoreManager", "类型转换错误", e);
                        }
                    }
                    return result;
                });
    }

    // 其他方法保持不变...
    // 是否开启自动充电设置
    public Flowable<String> getVoicePromptLanguage() {
        return getString(DataStoreKeys.VOICE_PROMPT_LANGUAGE, "");
    }

    public void setVoicePromptLanguage(String language) {
        putString(DataStoreKeys.VOICE_PROMPT_LANGUAGE, language != null ? language : "");
    }

    public Flowable<Boolean> isAutoChargeEnabled() {
        return getBoolean(DataStoreKeys.IS_AUTO_CHARGE, false);
    }

    // 是否开启视觉巡检
    public Flowable<Boolean> isVisualCruiseEnabled() {
        return getBoolean(DataStoreKeys.IS_VISUAL_CRUISE, false);
    }

    // 是否开启托盘显示设置
    public Flowable<Boolean> isShowTrayEnabled() {
        return getBoolean(DataStoreKeys.IS_SHOW_TRAY, false);
    }

    // 是否使用顶升模式
    public Flowable<Boolean> isJackModeEnabled() {
        return getBoolean(DataStoreKeys.IS_JACK_MODE, true);
    }

    // 是否开启密码设置
    public Flowable<Boolean> isUsePasswordEnabled() {
        return getBoolean(DataStoreKeys.IS_USE_PASSWORD, false);
    }

    // 获取当前密码
    public Flowable<String> getPassword() {
        return getString(DataStoreKeys.PASSWORD, "");
    }

    // 获取语言类型
    public Flowable<Integer> getLanguageType() {
        return getInt(DataStoreKeys.USE_LANGUAGE, 1);
    }

    // 获取音量大小
    public Flowable<Integer> getVolumeLevel() {
        return getInt(DataStoreKeys.VOLUME_LEVEL, 50);
    }

    // 获取速度大小
    public Flowable<Double> getSetSpeed() {
        return getDouble(DataStoreKeys.SET_SPEED, 0.8);
    }

    // 获取配送等待时间
    public Flowable<Integer> getDeliveryWaitDuration() {
        return getInt(DataStoreKeys.DELIVERY_WAIT_DURATION, 30);
    }

    // 获取低电量阈值
    public Flowable<Integer> getLowPowerThreshold() {
        return getInt(DataStoreKeys.LOW_POWER, 20);
    }

    // Battery management thresholds
    public Flowable<Integer> getBatteryCriticalLevel() {
        return getInt(DataStoreKeys.BATTERY_CRITICAL_LEVEL, 5);
    }
    public Flowable<Integer> getBatteryLowLevel() {
        return getInt(DataStoreKeys.BATTERY_LOW_LEVEL, 15);
    }
    public Flowable<Integer> getBatterySafeLevel() {
        return getInt(DataStoreKeys.BATTERY_SAFE_LEVEL, 20);
    }
    public Flowable<Integer> getBatteryIdleLevel() {
        return getInt(DataStoreKeys.BATTERY_IDLE_LEVEL, 60);
    }
    public Flowable<Integer> getBatteryFullLevel() {
        return getInt(DataStoreKeys.BATTERY_FULL_LEVEL, 100);
    }

    // 获取暂停等待延时
    public Flowable<Integer> getPauseWaitDuration() {
        return getInt(DataStoreKeys.PAUSE_WAIT_DURATION, 300);
    }

    // 获取站点集
    public Flowable<Map<String, Position>> getStationMap() {
        return getObject(DataStoreKeys.POSITION_MAP, POSITION_MAP_TYPE, Collections.emptyMap());
    }

    // 获取顶升任务集合
    public Flowable<Map<Integer, JackTask>> getJackTaskMap() {
        return getObject(DataStoreKeys.JACK_TASK_MAP, JACK_TASK_MAP_TYPE, Collections.emptyMap());
    }

    // 获取巡航任务集合
    public Flowable<Map<Integer, CruiseTask>> getCruiseTaskMap() {
        return getObject(DataStoreKeys.CRUISE_TASK_MAP, CRUISE_TASK_MAP_TYPE, Collections.emptyMap());
    }

    // 获取机器人配置列表
    public Flowable<List<RobotViewModel.RobotConfig>> getRobotConfigs() {
        return getObject(DataStoreKeys.ROBOT_CONFIGS,
                DataStoreKeys.ROBOT_CONFIG_LIST_TYPE,
                Collections.emptyList());
    }

    // 保存机器人配置列表
    public void saveRobotConfigs(List<RobotViewModel.RobotConfig> configs) {
        setObject(DataStoreKeys.ROBOT_CONFIGS, configs, DataStoreKeys.ROBOT_CONFIG_LIST_TYPE);
    }

    // 获取语言变化状态
    public Flowable<Boolean> getLanguageChanged() {
        return getBoolean(DataStoreKeys.LANGUAGE_CHANGED, false);
    }

    // 获取用户列表
    public Flowable<List<com.ezhan.amr.data.datatype.User>> getUserList() {
        return getObject(DataStoreKeys.USER_LIST, DataStoreKeys.USER_LIST_TYPE, Collections.emptyList());
    }

    // 保存用户列表
    public void saveUserList(List<com.ezhan.amr.data.datatype.User> users) {
        setObject(DataStoreKeys.USER_LIST, users, DataStoreKeys.USER_LIST_TYPE);
    }

    // 获取RFID卡片列表
    public Flowable<List<com.ezhan.amr.data.datatype.RfidData>> getRfidList() {
        return getObject(DataStoreKeys.RFID_LIST, DataStoreKeys.RFID_LIST_TYPE, Collections.emptyList());
    }

    // 保存RFID卡片列表
    public void saveRfidList(List<com.ezhan.amr.data.datatype.RfidData> rfidList) {
        setObject(DataStoreKeys.RFID_LIST, rfidList, DataStoreKeys.RFID_LIST_TYPE);
    }

    // 获取科室列表
    public Flowable<List<com.ezhan.amr.data.datatype.Department>> getDepartmentList() {
        return getObject(DataStoreKeys.DEPARTMENT_LIST, DataStoreKeys.DEPARTMENT_LIST_TYPE, Collections.emptyList());
    }

    // 保存科室列表
    public void saveDepartmentList(List<com.ezhan.amr.data.datatype.Department> departments) {
        setObject(DataStoreKeys.DEPARTMENT_LIST, departments, DataStoreKeys.DEPARTMENT_LIST_TYPE);
    }

    public Flowable<String> getHospitalRegisterCode() {
        return getString(DataStoreKeys.HOSPITAL_REGISTER_CODE, "");
    }

    public void saveHospitalRegisterCode(String registerCode) {
        putString(DataStoreKeys.HOSPITAL_REGISTER_CODE, registerCode != null ? registerCode : "");
    }

    public Flowable<Map<Integer, com.ezhan.amr.data.datatype.LocationCodeConfig>> getLocationCodeConfigMap() {
        return getObject(DataStoreKeys.LOCATION_CODE_CONFIG_MAP, DataStoreKeys.LOCATION_CODE_CONFIG_MAP_TYPE, Collections.emptyMap());
    }

    public void saveLocationCodeConfigMap(Map<Integer, com.ezhan.amr.data.datatype.LocationCodeConfig> map) {
        setObject(DataStoreKeys.LOCATION_CODE_CONFIG_MAP, map, DataStoreKeys.LOCATION_CODE_CONFIG_MAP_TYPE);
    }

    // 确保这个方法正确实现
    public Flowable<MultiBuildingMapPoints> getCurrentMapPoints() {
        Log.d("DataStoreDebug", "getCurrentMapPoints 被调用");

        return getObject(DataStoreKeys.CURRENT_MAP_POINTS,
                MAP_POINTS_TYPE,
                new MultiBuildingMapPoints())
                .doOnNext(mapPoints -> {
                    Log.d("DataStoreDebug", "从存储读取 MapPoints: " +
                            (mapPoints != null ? mapPoints.getTotalPositionCount() + " 点位" : "null"));
                })
                .doOnError(throwable -> {
                    Log.e("DataStoreDebug", "读取 MapPoints 失败", throwable);
                });
    }

    // 保存当前地图点位
    public void saveCurrentMapPoints(MultiBuildingMapPoints mapPoints) {
        int buildings = mapPoints != null ? mapPoints.getAllBuildings().size() : 0;
        int totalPoints = mapPoints != null ? mapPoints.getTotalPositionCount() : 0;
        Log.i("taskDebug7", "[DS-SAVE] saveCurrentMapPoints: buildings=" + buildings +
                ", totalPoints=" + totalPoints + " (async, may be lost on power-off)");

        if (mapPoints != null) {
            setObject(DataStoreKeys.CURRENT_MAP_POINTS, mapPoints, MAP_POINTS_TYPE);
        } else {
            Log.e("taskDebug7", "[DS-SAVE] Attempt to save null MultiBuildingMapPoints");
        }
    }

    public Flowable<Double> getRecognizeDistance() {
        return getDouble(DataStoreKeys.RECOGNIZE_DISTANCE, 1.0);
    }

    // 获取地图区域数据
    public Flowable<List<com.ezhan.amr.data.datatype.MapArea>> getMapAreas() {
        return getObject(DataStoreKeys.MAP_AREAS, DataStoreKeys.MAP_AREAS_TYPE, Collections.emptyList());
    }

    // 保存地图区域数据
    public void saveMapAreas(List<com.ezhan.amr.data.datatype.MapArea> areas) {
        setObject(DataStoreKeys.MAP_AREAS, areas, DataStoreKeys.MAP_AREAS_TYPE);
    }

    // 获取全局交管机器人列表 (所有交管区域共用)
    public Flowable<List<com.ezhan.amr.data.datatype.OtherRobot>> getTrafficRobots() {
        return getObject(DataStoreKeys.TRAFFIC_ROBOTS, DataStoreKeys.TRAFFIC_ROBOTS_TYPE, Collections.emptyList());
    }

    // 保存全局交管机器人列表
    public void saveTrafficRobots(List<com.ezhan.amr.data.datatype.OtherRobot> robots) {
        setObject(DataStoreKeys.TRAFFIC_ROBOTS, robots, DataStoreKeys.TRAFFIC_ROBOTS_TYPE);
    }

    // 建图规则浮动提示框位置和查看状态
    public Flowable<Float> getMapRulesTipX() {
        return getFloat(DataStoreKeys.MAP_RULES_TIP_X, -1f);
    }

    public Flowable<Float> getMapRulesTipY() {
        return getFloat(DataStoreKeys.MAP_RULES_TIP_Y, -1f);
    }

    public Flowable<Boolean> isMapRulesViewed() {
        return getBoolean(DataStoreKeys.MAP_RULES_VIEWED, false);
    }

    public void setMapRulesViewed(boolean viewed) {
        setBoolean(DataStoreKeys.MAP_RULES_VIEWED, viewed);
    }

    public void saveMapRulesTipPosition(float x, float y) {
        setFloat(DataStoreKeys.MAP_RULES_TIP_X, x);
        setFloat(DataStoreKeys.MAP_RULES_TIP_Y, y);
    }

    // 电梯操作提示框位置和查看状态
    public Flowable<Float> getElevatorTipX() {
        return getFloat(DataStoreKeys.ELEVATOR_TIP_X, -1f);
    }

    public Flowable<Float> getElevatorTipY() {
        return getFloat(DataStoreKeys.ELEVATOR_TIP_Y, -1f);
    }

    public Flowable<Boolean> isElevatorTipViewed() {
        return getBoolean(DataStoreKeys.ELEVATOR_TIP_VIEWED, false);
    }

    public void setElevatorTipViewed(boolean viewed) {
        setBoolean(DataStoreKeys.ELEVATOR_TIP_VIEWED, viewed);
    }

    public void saveElevatorTipPosition(float x, float y) {
        setFloat(DataStoreKeys.ELEVATOR_TIP_X, x);
        setFloat(DataStoreKeys.ELEVATOR_TIP_Y, y);
    }
}
