package com.ezhan.amr.viewmodels;

import static com.ezhan.amr.data.datastore.DataStoreKeys.DELIVERY_WAIT_DURATION;
import static com.ezhan.amr.data.datastore.DataStoreKeys.IS_AUTO_CHARGE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.IS_JACK_MODE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.IS_USE_PASSWORD;
import static com.ezhan.amr.data.datastore.DataStoreKeys.IS_VISUAL_CRUISE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.LOW_POWER;
import static com.ezhan.amr.data.datastore.DataStoreKeys.PASSWORD;
import static com.ezhan.amr.data.datastore.DataStoreKeys.PAUSE_WAIT_DURATION;
import static com.ezhan.amr.data.datastore.DataStoreKeys.RUNNING_MUSIC;
import static com.ezhan.amr.data.datastore.DataStoreKeys.SET_SPEED;
import static com.ezhan.amr.data.datastore.DataStoreKeys.USE_LANGUAGE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.VOICE_PROMPT_LIST;
import static com.ezhan.amr.data.datastore.DataStoreKeys.VOICE_PROMPT_MAP_TYPE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.VOLUME_LEVEL;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.R;
import com.ezhan.amr.data.datastore.DataStoreKeys;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.utils.LocaleHelper;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class BasicViewModel extends AndroidViewModel {
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<Double> setSpeed = new MutableLiveData<>();
    private final MutableLiveData<Integer> volumeLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> deliveryWaitDuration = new MutableLiveData<>();
    private final MutableLiveData<Integer> lowPower = new MutableLiveData<>();
    // Battery management thresholds
    private final MutableLiveData<Integer> batteryCriticalLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> batteryLowLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> batterySafeLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> batteryIdleLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> batteryFullLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> pauseWaitDuration = new MutableLiveData<>();
    private final MutableLiveData<Integer> queuedTaskCooldown = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isAutoCharge = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isIdleCharge = new MutableLiveData<>();
    private final MutableLiveData<Integer> autoChargeStartMinute = new MutableLiveData<>();
    private final MutableLiveData<Integer> autoChargeEndMinute = new MutableLiveData<>();
    private final MutableLiveData<Integer> idleChargeWaitMinutes = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isFullChargeReturnHome = new MutableLiveData<>();
    // 闲时回待命功能
    private final MutableLiveData<Boolean> isIdleReturnHome = new MutableLiveData<>();
    private final MutableLiveData<Integer> idleReturnWaitSeconds = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isVisualCruise = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isJackMode = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isUsePassword = new MutableLiveData<>();
    private final MutableLiveData<String> password = new MutableLiveData<>();
    private final MutableLiveData<Integer> useLanguageType = new MutableLiveData<>();
    private final MutableLiveData<Boolean> autoTaskEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> languageChanged = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isVirtualOrbitMode = new MutableLiveData<>(true); // true = virtual orbit, false = free path
    private final MutableLiveData<Boolean> isVirtualOrbitOneWayMode = new MutableLiveData<>(false);
    private final MutableLiveData<Double> recognizeDistance = new MutableLiveData<>();
    private final MutableLiveData<Integer> virtualOrbitObstacleTime = new MutableLiveData<>(); // 虚拟轨道绕障时间：0立即绕障，-1不绕障，>0为延迟秒数
    private final MutableLiveData<String> runningMusic = new MutableLiveData<>();
    // 添加当前配置缓存
    private Map<String, String> currentConfig = new HashMap<>();
    private final MutableLiveData<Map<String, String>> voicePromptList = new MutableLiveData<>();
    private final MutableLiveData<Integer> robotId = new MutableLiveData<>();
    private final MutableLiveData<Integer> loraChannel = new MutableLiveData<>();
    private final MutableLiveData<Integer> loraAddress = new MutableLiveData<>();
    private final MutableLiveData<String> cloudIp = new MutableLiveData<>();
    private final MutableLiveData<Integer> cloudPort = new MutableLiveData<>();
    private final Gson gson = new Gson();
    // 获取默认语音提示词
    private final Map<String, String> DEFAULT_VOICE_TEXTS;
    private final Context appContext;

    public BasicViewModel(@NonNull Application application) {
        super(application);
        appContext = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(appContext);
        DEFAULT_VOICE_TEXTS = dataStoreManager.getDefaultVoiceTexts();
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        disposables.add(Flowable.<Map<String, String>>empty()
                .subscribe(voice -> {
                    currentConfig = voice; // 缓存当前配置
                    voicePromptList.postValue(voice);
                }, throwable -> {
                    Log.e("VoiceSettingsVM", "加载语音失败", throwable);
                    currentConfig = DEFAULT_VOICE_TEXTS; // 失败时使用默认值
                    voicePromptList.postValue(DEFAULT_VOICE_TEXTS);
                }));
        disposables.add(Flowable.combineLatest(
                dataStoreManager.getVoicePromptList(),
                dataStoreManager.getVoicePromptLanguage(),
                VoiceConfigState::new
        ).subscribe(state -> {
            String currentLanguage = LocaleHelper.getLanguage(appContext);
            if (state.language == null || state.language.isEmpty() || !state.language.equals(currentLanguage)) {
                Map<String, String> localizedDefaults = createLocalizedDefaultVoiceTexts(appContext);
                currentConfig = localizedDefaults;
                voicePromptList.postValue(localizedDefaults);
                persistVoicePromptConfig(localizedDefaults, currentLanguage);
                return;
            }

            currentConfig = state.voiceConfig;
            voicePromptList.postValue(state.voiceConfig);
        }, throwable -> Log.e("VoiceSettingsVM", "Voice language validation failed", throwable)));

        disposables.add(dataStoreManager.getSetSpeed()
                .subscribe(speed -> {
                    setSpeed.postValue(speed != null ? speed : 0.6);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载速度失败", throwable);
                }));

        disposables.add(dataStoreManager.getVolumeLevel()
                .subscribe(volume -> {
                    volumeLevel.postValue(volume != null ? volume : 6);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载音量失败", throwable);
                }));

        disposables.add(dataStoreManager.getDeliveryWaitDuration()
                .subscribe(duration -> {
                    deliveryWaitDuration.postValue(duration != null ? duration : 30);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载等待时间失败", throwable);
                }));

        disposables.add(dataStoreManager.getLowPowerThreshold()
                .subscribe(lowPowerLever -> {
                    lowPower.postValue(lowPowerLever != null ? lowPowerLever : 20);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载低电量阈值失败", throwable);
                }));

        // Battery management thresholds initialization
        disposables.add(dataStoreManager.getBatteryCriticalLevel()
                .subscribe(level -> {
                    int val = level != null ? level : 5;
                    batteryCriticalLevel.postValue(val);
                    syncBatteryThresholds();
                }, throwable -> Log.e("BasicSettingsDataLoad", "加载电池临界阈值失败", throwable)));
        disposables.add(dataStoreManager.getBatteryLowLevel()
                .subscribe(level -> {
                    batteryLowLevel.postValue(level != null ? level : 20);
                    syncBatteryThresholds();
                }, throwable -> Log.e("BasicSettingsDataLoad", "加载 legacy 低电量阈值失败", throwable)));
        disposables.add(dataStoreManager.getBatterySafeLevel()
                .subscribe(level -> {
                    int val = level != null ? level : 20;
                    batterySafeLevel.postValue(val);
                    syncBatteryThresholds();
                }, throwable -> Log.e("BasicSettingsDataLoad", "加载最低工作电量失败", throwable)));
        disposables.add(dataStoreManager.getBatteryIdleLevel()
                .subscribe(level -> {
                    int val = level != null ? level : 60;
                    batteryIdleLevel.postValue(val);
                    syncBatteryThresholds();
                }, throwable -> Log.e("BasicSettingsDataLoad", "加载电池闲时阈值失败", throwable)));
        disposables.add(dataStoreManager.getBatteryFullLevel()
                .subscribe(level -> {
                    int val = level != null ? level : 100;
                    batteryFullLevel.postValue(val);
                    syncBatteryThresholds();
                }, throwable -> Log.e("BasicSettingsDataLoad", "加载电池充满阈值失败", throwable)));

        disposables.add(dataStoreManager.getPauseWaitDuration()
                .subscribe(duration -> {
                    pauseWaitDuration.postValue(duration != null ? duration : 30);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载暂停等待时间失败", throwable);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.QUEUED_TASK_COOLDOWN, 6)
                .subscribe(duration -> {
                    queuedTaskCooldown.postValue(duration != null ? duration : 6);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载排队任务冷却时间失败", throwable);
                    queuedTaskCooldown.postValue(6);
                }));

        disposables.add(dataStoreManager.isAutoChargeEnabled()
                .subscribe(autoCharge -> {
                    isAutoCharge.postValue(autoCharge != null ? autoCharge : Boolean.FALSE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载自动充电配置失败", throwable);
                }));

        disposables.add(dataStoreManager.getBoolean(DataStoreKeys.IS_IDLE_CHARGE, true)
                .subscribe(idleCharge -> {
                    isIdleCharge.postValue(idleCharge != null ? idleCharge : Boolean.TRUE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "Load idle charge config failed", throwable);
                    isIdleCharge.postValue(Boolean.TRUE);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.AUTO_CHARGE_START_MINUTE, 0)
                .subscribe(startMinute -> {
                    autoChargeStartMinute.postValue(startMinute != null ? startMinute : 0);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "Load auto charge start time failed", throwable);
                    autoChargeStartMinute.postValue(0);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.AUTO_CHARGE_END_MINUTE, 0)
                .subscribe(endMinute -> {
                    autoChargeEndMinute.postValue(endMinute != null ? endMinute : 0);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "Load auto charge end time failed", throwable);
                    autoChargeEndMinute.postValue(0);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.IDLE_CHARGE_WAIT_MINUTES, 10)
                .subscribe(waitMinutes -> {
                    idleChargeWaitMinutes.postValue(waitMinutes != null ? waitMinutes : 10);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "Load idle charge wait time failed", throwable);
                    idleChargeWaitMinutes.postValue(10);
                }));

        disposables.add(dataStoreManager.getBoolean(DataStoreKeys.IS_FULL_CHARGE_RETURN_HOME, true)
                .subscribe(enabled -> {
                    isFullChargeReturnHome.postValue(enabled != null ? enabled : Boolean.TRUE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载充满电回待命开关失败", throwable);
                    isFullChargeReturnHome.postValue(Boolean.TRUE);
                }));

        // 加载闲时回待命配置
        disposables.add(dataStoreManager.getBoolean(DataStoreKeys.IS_IDLE_RETURN_HOME, false)
                .subscribe(idleReturn -> {
                    isIdleReturnHome.postValue(idleReturn != null ? idleReturn : Boolean.FALSE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载闲时回待命开关失败", throwable);
                    isIdleReturnHome.postValue(Boolean.FALSE);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.IDLE_RETURN_WAIT_SECONDS, 600)
                .subscribe(waitSeconds -> {
                    idleReturnWaitSeconds.postValue(waitSeconds != null ? waitSeconds : 600);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载闲时回待命等待时间失败", throwable);
                    idleReturnWaitSeconds.postValue(600);
                }));

        disposables.add(dataStoreManager.isVisualCruiseEnabled()
                .subscribe(visualCruise -> {
                    isVisualCruise.postValue(visualCruise != null ? visualCruise : Boolean.FALSE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载视觉巡检设置失败", throwable);
                }));

        disposables.add(dataStoreManager.isJackModeEnabled()
                .subscribe(jackMode -> {
                    isJackMode.postValue(jackMode != null ? jackMode : Boolean.FALSE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载顶升设置失败", throwable);
                }));

        disposables.add(dataStoreManager.isUsePasswordEnabled()
                .subscribe(usePassword -> {
                    isUsePassword.postValue(usePassword != null ? usePassword : Boolean.FALSE);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载配置密码失败", throwable);
                }));

        disposables.add(dataStoreManager.getPassword()
                .subscribe(passWordString -> {
                    password.postValue(passWordString != null ? passWordString : "0000");
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载密码失败", throwable);
                }));

        disposables.add(dataStoreManager.getLanguageType()
                .subscribe(type -> {
                    useLanguageType.postValue(type != null ? type : 1);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载语言类型失败", throwable);
                }));
        disposables.add(dataStoreManager.getBoolean(DataStoreKeys.LANGUAGE_CHANGED, false)
                .subscribe(changed -> {
                    languageChanged.postValue(changed != null ? changed : false);
                }, throwable -> {
                    Log.e("DataLoad", "加载语言变化状态失败", throwable);
                }));
        disposables.add(dataStoreManager.getBoolean(DataStoreKeys.IS_VIRTUAL_ORBIT_MODE, false)
                .subscribe(isVirtual -> {
                    isVirtualOrbitMode.postValue(isVirtual != null ? isVirtual : false);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载轨道模式失败", throwable);
                    isVirtualOrbitMode.postValue(false); // 默认虚拟轨道
                }));
        disposables.add(dataStoreManager.getBoolean(DataStoreKeys.IS_VIRTUAL_ORBIT_ONE_WAY, false)
                .subscribe(isOneWay -> {
                    isVirtualOrbitOneWayMode.postValue(isOneWay != null ? isOneWay : false);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载虚拟轨道单向导航设置失败", throwable);
                    isVirtualOrbitOneWayMode.postValue(false);
                }));
        disposables.add(dataStoreManager.getRecognizeDistance()
                .subscribe(distance -> {
                    recognizeDistance.postValue(distance != null ? distance : 1.0);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载识别距离失败", throwable);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.VIRTUAL_ORBIT_OBSTACLE_TIME, -1)
                .subscribe(time -> {
                    virtualOrbitObstacleTime.postValue(time != null ? time : -1); // 默认立即绕障
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载虚拟轨道绕障时间失败", throwable);
                    virtualOrbitObstacleTime.postValue(-1);
                }));

        disposables.add(dataStoreManager.getString(RUNNING_MUSIC, "wa")
                .subscribe(music -> {
                    runningMusic.postValue(music != null ? music : "wa");
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载运行音乐失败", throwable);
                }));
        // Add these methods in the loadAllPersistedData() method
        disposables.add(dataStoreManager.getInt(DataStoreKeys.ROBOT_ID, 1)
                .subscribe(id -> {
                    robotId.postValue(id != null ? id : 1);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载机器人ID失败", throwable);
                    robotId.postValue(1);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.LORA_CHANNEL, 0)
                .subscribe(channel -> {
                    loraChannel.postValue(channel != null ? channel : 0);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载LoRa信道失败", throwable);
                    loraChannel.postValue(0);
                }));

        disposables.add(dataStoreManager.getInt(DataStoreKeys.LORA_ADDRESS, 0)
                .subscribe(address -> {
                    loraAddress.postValue(address != null ? address : 0);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载LoRa地址失败", throwable);
                    loraAddress.postValue(0);
                }));

        disposables.add(dataStoreManager.getCloudIp()
                .subscribe(ip -> {
                    cloudIp.postValue(ip != null ? ip : "");
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载云IP失败", throwable);
                    cloudIp.postValue("");
                }));

        disposables.add(dataStoreManager.getCloudPort()
                .subscribe(port -> {
                    cloudPort.postValue(port != null ? port : 1883);
                }, throwable -> {
                    Log.e("BasicSettingsDataLoad", "加载云端口失败", throwable);
                    cloudPort.postValue(1883);
                }));
    }

    // Add method to save orbit mode
    public void setVirtualOrbitMode(boolean isVirtual) {
        isVirtualOrbitMode.postValue(isVirtual);
        dataStoreManager.setBoolean(DataStoreKeys.IS_VIRTUAL_ORBIT_MODE, isVirtual);
    }

    // Add getter for orbit mode
    public LiveData<Boolean> getIsVirtualOrbitMode() {
        return isVirtualOrbitMode;
    }

    public void setVirtualOrbitOneWayMode(boolean isOneWay) {
        isVirtualOrbitOneWayMode.setValue(isOneWay);
        dataStoreManager.setBoolean(DataStoreKeys.IS_VIRTUAL_ORBIT_ONE_WAY, isOneWay);
    }

    public LiveData<Boolean> getIsVirtualOrbitOneWayMode() {
        return isVirtualOrbitOneWayMode;
    }

    public void setRecognizeDistance(double recognizeDistance) {
        dataStoreManager.setDouble(DataStoreKeys.RECOGNIZE_DISTANCE, recognizeDistance);
    }

    public LiveData<Double> getRecognizeDistance() { return recognizeDistance; }

    public void setVirtualOrbitObstacleTime(int time) {
        virtualOrbitObstacleTime.postValue(time);
        dataStoreManager.setInt(DataStoreKeys.VIRTUAL_ORBIT_OBSTACLE_TIME, time);
    }

    public LiveData<Integer> getVirtualOrbitObstacleTime() {
        return virtualOrbitObstacleTime;
    }

    public void setAutoTaskEnabled(boolean enabled) {
        autoTaskEnabled.setValue(enabled);
    }

    public MutableLiveData<Boolean> getAutoTaskEnabled() {
        return autoTaskEnabled;
    }


    public LiveData<Map<String, String>> getVoicePromptList() {
        return voicePromptList;
    }

    // 获取当前配置（用于保存前获取最新值）
    public Map<String, String> getCurrentConfig() {
        return currentConfig;
    }

    // 保存配置到SharedPreferences
    public void saveToPreferences(Map<String, String> config) {
        // 创建新配置（不使用合并）
        Map<String, String> newConfig = new HashMap<>(config);

        persistVoicePromptConfig(newConfig, LocaleHelper.getLanguage(appContext));

        // 更新当前配置
        currentConfig = newConfig;
        voicePromptList.postValue(newConfig);
    }

    // 重置为默认值
    public void resetToDefault() {
        // 创建包含默认值的新配置
        Map<String, String> defaultConfig = new HashMap<>();
        for (Map.Entry<String, String> entry : DEFAULT_VOICE_TEXTS.entrySet()) {
            defaultConfig.put(entry.getKey(), entry.getValue());
        }
        // 保存默认配置
        saveToPreferences(defaultConfig);
    }

    public void resetVoicePromptsToCurrentLanguage(Context context) {
        Context targetContext = context != null ? context : appContext;
        Map<String, String> localizedDefaults = createLocalizedDefaultVoiceTexts(targetContext);
        persistVoicePromptConfig(localizedDefaults, LocaleHelper.getLanguage(targetContext));
        currentConfig = localizedDefaults;
        voicePromptList.postValue(localizedDefaults);
    }

    private void persistVoicePromptConfig(Map<String, String> config, String language) {
        String json = gson.toJson(config, VOICE_PROMPT_MAP_TYPE);
        dataStoreManager.putString(VOICE_PROMPT_LIST, json);
        dataStoreManager.setVoicePromptLanguage(language);
    }

    private Map<String, String> createLocalizedDefaultVoiceTexts(Context context) {
        Map<String, String> defaults = new HashMap<>();
        defaults.put(VoiceKeyConstants.DEPARTURE_BROADCAST, LocaleHelper.onServiceGetString(context, R.string.default_departure_text));
        defaults.put(VoiceKeyConstants.ARRIVAL, LocaleHelper.onServiceGetString(context, R.string.default_arrival_text));
        defaults.put(VoiceKeyConstants.POSITION_LOST, LocaleHelper.onServiceGetString(context, R.string.default_lost_text));
        defaults.put(VoiceKeyConstants.LOW_BATTERY, LocaleHelper.onServiceGetString(context, R.string.default_low_power_text));
        defaults.put(VoiceKeyConstants.OBSTACLE_ALERT, LocaleHelper.onServiceGetString(context, R.string.default_obstacle_text));
        defaults.put(VoiceKeyConstants.TASK_COMPLETED, LocaleHelper.onServiceGetString(context, R.string.default_task_done_text));
        defaults.put(VoiceKeyConstants.OPERATION_ERROR_PROMPT, LocaleHelper.onServiceGetString(context, R.string.default_op_error_text));
        defaults.put(VoiceKeyConstants.CHARGE_COMPLETE, LocaleHelper.onServiceGetString(context, R.string.default_charge_done_text));
        defaults.put(VoiceKeyConstants.RETURN_ARRIVAL, LocaleHelper.onServiceGetString(context, R.string.default_return_arrive_text));
        defaults.put(VoiceKeyConstants.TASK_INTERRUPTED, LocaleHelper.onServiceGetString(context, R.string.default_task_break_text));
        defaults.put(VoiceKeyConstants.CRUISE_DEPARTURE, LocaleHelper.onServiceGetString(context, R.string.default_cruise_go_text));
        defaults.put(VoiceKeyConstants.CRUISE_ARRIVAL, LocaleHelper.onServiceGetString(context, R.string.default_cruise_arrive_text));
        defaults.put(VoiceKeyConstants.PEDESTRIAN_ALERT, LocaleHelper.onServiceGetString(context, R.string.default_pedestrian_text));
        defaults.put(VoiceKeyConstants.GO_CHARGING, LocaleHelper.onServiceGetString(context, R.string.default_go_charge_text));
        defaults.put(VoiceKeyConstants.EMERGENCY_STOP, LocaleHelper.onServiceGetString(context, R.string.default_emergency_stop_text));
        defaults.put(VoiceKeyConstants.COLLISION_ALERT, LocaleHelper.onServiceGetString(context, R.string.default_collision_alert_text));
        defaults.put(VoiceKeyConstants.NAVIGATION_ERROR, LocaleHelper.onServiceGetString(context, R.string.default_navigation_error_text));
        return defaults;
    }

    private static class VoiceConfigState {
        final Map<String, String> voiceConfig;
        final String language;

        VoiceConfigState(Map<String, String> voiceConfig, String language) {
            this.voiceConfig = voiceConfig;
            this.language = language;
        }
    }

    public LiveData<Double> getSpeed() { return setSpeed; }

    public void setSpeed(Double speed) {
        dataStoreManager.setDouble(SET_SPEED, speed);
    }

    public LiveData<Integer> getVolumeLevel() { return volumeLevel; }
    public void setVolumeLevel(Integer volume) {
        dataStoreManager.setInt(VOLUME_LEVEL, volume);
    }

    public LiveData<Integer> getDeliveryWaitDuration() { return deliveryWaitDuration;}
    public void setDeliveryWaitDuration(Integer duration) {
        dataStoreManager.setInt(DELIVERY_WAIT_DURATION, duration);
    }

    public LiveData<Integer> getLowPower() { return lowPower; }
    public void setLowPower(Integer power) {
        dataStoreManager.setInt(LOW_POWER, power);
    }

    // Battery management threshold getters/setters
    public LiveData<Integer> getBatteryCriticalLevel() { return batteryCriticalLevel; }
    public void setBatteryCriticalLevel(int level) {
        dataStoreManager.setInt(DataStoreKeys.BATTERY_CRITICAL_LEVEL, level);
    }
    public LiveData<Integer> getBatteryLowLevel() { return batteryLowLevel; }
    public void setBatteryLowLevel(int level) {
        dataStoreManager.setInt(DataStoreKeys.BATTERY_LOW_LEVEL, level);
    }
    public LiveData<Integer> getBatterySafeLevel() { return batterySafeLevel; }
    public void setBatterySafeLevel(int level) {
        // 同步更新 LiveData 并立即同步到 BatteryLevelManager，
        // 避免依赖 DataStore 异步回调 + postValue 导致 minWorkingLevel 更新滞后
        batterySafeLevel.setValue(level);
        batteryLowLevel.setValue(level);
        dataStoreManager.setInt(DataStoreKeys.BATTERY_SAFE_LEVEL, level);
        dataStoreManager.setInt(DataStoreKeys.BATTERY_LOW_LEVEL, level);
        syncBatteryThresholds();
    }
    public LiveData<Integer> getBatteryIdleLevel() { return batteryIdleLevel; }
    public void setBatteryIdleLevel(int level) {
        dataStoreManager.setInt(DataStoreKeys.BATTERY_IDLE_LEVEL, level);
    }
    public LiveData<Integer> getBatteryFullLevel() { return batteryFullLevel; }
    public void setBatteryFullLevel(int level) {
        dataStoreManager.setInt(DataStoreKeys.BATTERY_FULL_LEVEL, level);
    }

    /**
     * Sync all battery thresholds to BatteryLevelManager.
     * Called whenever any threshold value changes.
     */
    private void syncBatteryThresholds() {
        Integer critical = batteryCriticalLevel.getValue();
        Integer low = batteryLowLevel.getValue();
        Integer safe = batterySafeLevel.getValue();
        Integer idle = batteryIdleLevel.getValue();
        Integer full = batteryFullLevel.getValue();
        if (critical != null && safe != null && idle != null && full != null) {
            int lowLegacy = low != null ? low : safe;
            com.ezhan.amr.navigation.BatteryLevelManager.getInstance()
                    .updateThresholds(critical, lowLegacy, safe, idle, full);
        }
    }

    public LiveData<Double> getSetSpeed() { return setSpeed; }
    public void setSetSpeed(Double speed) {
        dataStoreManager.setDouble(SET_SPEED, speed);
    }

    public LiveData<Integer> getPauseWaitDuration() { return pauseWaitDuration; }
    public void setPauseWaitDuration(Integer duration) {
        dataStoreManager.setInt(PAUSE_WAIT_DURATION, duration);
    }

    public LiveData<Integer> getQueuedTaskCooldown() {
        return queuedTaskCooldown;
    }

    public void setQueuedTaskCooldown(int duration) {
        queuedTaskCooldown.setValue(duration);
        dataStoreManager.setInt(DataStoreKeys.QUEUED_TASK_COOLDOWN, duration);
    }

    public LiveData<Boolean> getIsAutoCharge() { return isAutoCharge; }

    public LiveData<Boolean> getIsIdleCharge() { return isIdleCharge; }

    public LiveData<Integer> getAutoChargeStartMinute() { return autoChargeStartMinute; }

    public LiveData<Integer> getAutoChargeEndMinute() { return autoChargeEndMinute; }

    public LiveData<Integer> getIdleChargeWaitMinutes() { return idleChargeWaitMinutes; }

    public LiveData<Boolean> getIsVisualCruise() { return isVisualCruise; }

    public LiveData<Boolean> getIsJackMode() { return isJackMode; }
    public void setAutoCharge(Boolean charge) {
        isAutoCharge.setValue(charge);
        dataStoreManager.setBoolean(IS_AUTO_CHARGE, charge);
    }
    public void setIdleCharge(Boolean charge) {
        isIdleCharge.setValue(charge);
        dataStoreManager.setBoolean(DataStoreKeys.IS_IDLE_CHARGE, charge);
    }
    public void setAutoChargeStartMinute(int minute) {
        autoChargeStartMinute.setValue(minute);
        dataStoreManager.setInt(DataStoreKeys.AUTO_CHARGE_START_MINUTE, minute);
    }
    public void setAutoChargeEndMinute(int minute) {
        autoChargeEndMinute.setValue(minute);
        dataStoreManager.setInt(DataStoreKeys.AUTO_CHARGE_END_MINUTE, minute);
    }
    public void setIdleChargeWaitMinutes(int minutes) {
        idleChargeWaitMinutes.setValue(minutes);
        dataStoreManager.setInt(DataStoreKeys.IDLE_CHARGE_WAIT_MINUTES, minutes);
    }

    public LiveData<Boolean> getIsFullChargeReturnHome() { return isFullChargeReturnHome; }

    public void setFullChargeReturnHome(boolean enabled) {
        isFullChargeReturnHome.setValue(enabled);
        dataStoreManager.setBoolean(DataStoreKeys.IS_FULL_CHARGE_RETURN_HOME, enabled);
    }

    // 闲时回待命功能
    public LiveData<Boolean> getIsIdleReturnHome() { return isIdleReturnHome; }

    public LiveData<Integer> getIdleReturnWaitSeconds() { return idleReturnWaitSeconds; }

    public void setIdleReturnHome(boolean enabled) {
        isIdleReturnHome.setValue(enabled);
        dataStoreManager.setBoolean(DataStoreKeys.IS_IDLE_RETURN_HOME, enabled);
    }

    public void setIdleReturnWaitSeconds(int seconds) {
        idleReturnWaitSeconds.setValue(seconds);
        dataStoreManager.setInt(DataStoreKeys.IDLE_RETURN_WAIT_SECONDS, seconds);
    }
    public void setVisualCruise(Boolean visualCruise) {
        dataStoreManager.setBoolean(IS_VISUAL_CRUISE, visualCruise);
    }
    public void setJackMode(Boolean jackMode) {
        dataStoreManager.setBoolean(IS_JACK_MODE, jackMode);
    }

    public LiveData<Boolean> getIsUsePassword() { return isUsePassword; }
    public void setUsePassword(Boolean use) {
        dataStoreManager.setBoolean(IS_USE_PASSWORD, use);
    }

    public LiveData<String> getPassword() { return password; }
    public void setPassword(String password) {
        dataStoreManager.putString(PASSWORD, password);
    }

    public LiveData<Integer> getLanguageType() { return useLanguageType; }
    public void setLanguageType(Integer type) {
        dataStoreManager.setInt(USE_LANGUAGE, type);
    }

    public LiveData<Boolean> getLanguageChanged() {
        return languageChanged;
    }

    public void setLanguageChanged(boolean changed) {
        languageChanged.setValue(changed);

        // 如果需要持久化存储，可以保存到 DataStore
        dataStoreManager.setBoolean(DataStoreKeys.LANGUAGE_CHANGED, changed);
    }

    public LiveData<String> getRunningMusic() { return runningMusic; }
    public void setRunningMusic(String music) {
        dataStoreManager.putString(RUNNING_MUSIC, music);
    }

    /**
     * 更新语音提示列表（供外部调用）
     */
    public void updateVoicePromptList(Map<String, String> newConfig) {
        voicePromptList.postValue(newConfig);
        currentConfig = newConfig; // 同时更新当前配置缓存
    }

    public LiveData<Integer> getRobotId() { return robotId; }
    public void setRobotId(int id) {
        dataStoreManager.setInt(DataStoreKeys.ROBOT_ID, id);
    }

    public LiveData<Integer> getLoraChannel() { return loraChannel; }
    public void setLoraChannel(int channel) {
        dataStoreManager.setInt(DataStoreKeys.LORA_CHANNEL, channel);
    }

    public LiveData<Integer> getLoraAddress() { return loraAddress; }
    public void setLoraAddress(int address) {
        dataStoreManager.setInt(DataStoreKeys.LORA_ADDRESS, address);
    }

    public LiveData<String> getCloudIp() { return cloudIp; }
    public void setCloudIp(String ip) {
        cloudIp.setValue(ip);
        dataStoreManager.saveCloudIp(ip);
    }

    public LiveData<Integer> getCloudPort() { return cloudPort; }
    public void setCloudPort(int port) {
        cloudPort.setValue(port);
        dataStoreManager.saveCloudPort(port);
    }
}
