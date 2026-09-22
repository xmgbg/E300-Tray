package com.ezhan.amr.utils;

import android.content.Context;
import android.util.Log;

import com.ezhan.amr.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Manages app settings stored as a local JSON file on device.
 * <p>
 * On first launch, copies defaults from res/raw/app_settings.json to internal storage.
 * On subsequent launches, reads from the local file so that user changes persist.
 * Provides both getter and setter methods for all settings.
 */
public class AppSettingsConfig {
    private static final String TAG = "AppSettingsConfig";
    private static final String SETTINGS_FILE_NAME = "app_settings.json";

    private static AppSettingsConfig instance;
    private JSONObject config;
    private boolean isLoaded = false;
    private Context appContext;

    private AppSettingsConfig() {}

    public static synchronized AppSettingsConfig getInstance() {
        if (instance == null) {
            instance = new AppSettingsConfig();
        }
        return instance;
    }

    /**
     * Load config from local file. If the file does not exist (first launch),
     * copy defaults from res/raw/app_settings.json to internal storage first.
     * @param forceReload if true, reload even if already loaded
     */
    public void loadConfig(Context context) {
        loadConfig(context, false);
    }

    public void loadConfig(Context context, boolean forceReload) {
        if (isLoaded && !forceReload) return;

        appContext = context.getApplicationContext();
        File localFile = getConfigFile();

        if (localFile.exists()) {
            // Read from local file
            try {
                String content = readFile(localFile);
                config = new JSONObject(content);
                isLoaded = true;
                Log.i(TAG, "App settings loaded from local file: " + localFile.getAbsolutePath());
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to read local settings file, falling back to defaults", e);
            }
        }

        // First launch or file corrupted — copy from res/raw
        try {
            InputStream is = appContext.getResources().openRawResource(R.raw.app_settings);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String jsonStr = sb.toString();
            config = new JSONObject(jsonStr);

            // Save to local file
            writeToFile(localFile, jsonStr);
            isLoaded = true;
            Log.i(TAG, "App settings initialized from raw resource and saved to: " + localFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load app settings config", e);
            createDefaultConfig();
            saveConfig();
        }
    }

    /**
     * Get the config file, stored in external files directory for reliable access.
     * This matches the log file location for consistency.
     */
    private File getConfigFile() {
        // 优先使用外部存储，与日志目录一致
        File baseDir = appContext.getExternalFilesDir(null);
        if (baseDir == null) {
            // Fallback to internal storage
            baseDir = appContext.getFilesDir();
        }
        if (baseDir == null) {
            // Last resort - create in cache dir
            baseDir = appContext.getCacheDir();
        }
        Log.d(TAG, "getConfigFile: using directory: " + (baseDir != null ? baseDir.getAbsolutePath() : "null"));
        return new File(baseDir, SETTINGS_FILE_NAME);
    }

    /**
     * Persist current in-memory config to local file.
     */
    public void saveConfig() {
        if (config == null || appContext == null) return;

        try {
            File localFile = getConfigFile();
            writeToFile(localFile, config.toString(2));
            Log.i(TAG, "App settings saved to local file: " + localFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save config to local file", e);
        }
    }

    /**
     * Get the local config file path, for external inspection.
     */
    public String getConfigFilePath() {
        if (appContext == null) return null;
        return getConfigFile().getAbsolutePath();
    }

    /**
     * Get the current config as a formatted JSON string, for editing.
     */
    public String getConfigJsonString() {
        if (config == null) return "";
        try {
            return config.toString(2);
        } catch (Exception e) {
            return config.toString();
        }
    }

    /**
     * Save config from a raw JSON string. Returns true on success, false if JSON is invalid.
     */
    public boolean saveConfigFromString(String jsonStr) {
        try {
            JSONObject newConfig = new JSONObject(jsonStr);
            config = newConfig;
            saveConfig();
            Log.i(TAG, "Config updated from string and saved");
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON provided for config update", e);
            return false;
        }
    }

    // ==================== File I/O Helpers ====================

    private String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void writeToFile(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    // ==================== Default Config Fallback ====================

    private void createDefaultConfig() {
        try {
            config = new JSONObject();

            JSONObject basic = new JSONObject();
            basic.put("volumeLevel", 6);
            basic.put("speed", 0.4);
            basic.put("deliveryWaitDuration", 30);
            basic.put("queuedTaskCooldown", 6);
            basic.put("lowPower", 20);
            basic.put("recognizeDistance", 1.0);
            basic.put("obstacleTime", 0);
            basic.put("runningMusic", "wa");
            config.put("basicSettings", basic);

            JSONObject charge = new JSONObject();
            charge.put("autoChargeEnabled", false);
            charge.put("idleChargeEnabled", true);
            charge.put("autoChargeStartMinute", 0);
            charge.put("autoChargeEndMinute", 0);
            charge.put("idleChargeWaitMinutes", 10);
            config.put("chargeSettings", charge);

            JSONObject orbit = new JSONObject();
            orbit.put("virtualOrbitMode", true);
            orbit.put("virtualOrbitOneWay", false);
            config.put("orbitSettings", orbit);

            JSONObject comm = new JSONObject();
            comm.put("robotId", 1);
            comm.put("loraChannel", 0);
            comm.put("loraAddress", 0);
            config.put("communicationSettings", comm);

            JSONObject lang = new JSONObject();
            lang.put("language", "zh");
            lang.put("languageChanged", false);
            config.put("languageSettings", lang);

            JSONObject module = new JSONObject();
            module.put("jackModuleEnabled", true);
            module.put("shelfModuleEnabled", true);
            module.put("elevatorModuleEnabled", true);
            module.put("callBoxModuleEnabled", true);
            module.put("mapAreaModuleEnabled", true);
            module.put("avoidanceModuleEnabled", true);
            config.put("moduleVisibility", module);

            JSONObject password = new JSONObject();
            password.put("usePassword", false);
            password.put("password", "0000");
            config.put("passwordSettings", password);

            config.put("visualCruiseEnabled", false);

            // Default elevator config example
            JSONArray elevatorConfigs = new JSONArray();
            JSONObject elevator = new JSONObject();
            elevator.put("id", "1");
            elevator.put("channel", 1);
            elevator.put("address", 1);
            elevator.put("buildingId", "default");
            JSONArray floors = new JSONArray();
            JSONObject floor = new JSONObject();
            floor.put("id", "floor-1");
            floor.put("alias", "1F");
            floor.put("floor", 1);
            floor.put("waitPoint", createDefaultPosition(3, "1"));
            floor.put("preridePoint", createDefaultPosition(2, "1"));
            floor.put("ridePoint", createDefaultPosition(4, "1"));
            floor.put("transitionPoint", createDefaultPosition(14, "1"));
            floor.put("buildingId", "default");
            floors.put(floor);
            elevator.put("floors", floors);
            elevatorConfigs.put(elevator);
            config.put("elevatorConfigs", elevatorConfigs);

            // Default call box config example
            JSONArray callBoxConfigs = new JSONArray();
            JSONObject callBox = new JSONObject();
            callBox.put("id", "callbox-001");
            callBox.put("name", "CallBox-1");
            callBox.put("channel", 1);
            callBox.put("address", 1);
            JSONArray buttons = new JSONArray();
            JSONObject button = new JSONObject();
            button.put("id", "btn-001");
            button.put("name", "Button1");
            button.put("position", "");
            button.put("task", "");
            button.put("buttonByteId", 1);
            buttons.put(button);
            callBox.put("buttons", buttons);
            callBoxConfigs.put(callBox);
            config.put("callBoxConfigs", callBoxConfigs);

            // Default map area config example
            JSONArray mapAreaConfigs = new JSONArray();
            JSONObject mapArea = new JSONObject();
            mapArea.put("name", "Area-1");
            mapArea.put("type", "traffic");
            mapArea.put("channel", "1");
            mapArea.put("address", "1");
            mapArea.put("mapName", "");
            mapArea.put("polygonPoints", new JSONArray());
            mapArea.put("resolution", 0.05);
            mapArea.put("mapOriginX", 0.0);
            mapArea.put("mapOriginY", 0.0);
            mapArea.put("mapOriginZ", 0.0);
            mapArea.put("expand", 0.0);
            mapArea.put("otherRobots", new JSONArray());
            mapAreaConfigs.put(mapArea);
            config.put("mapAreaConfigs", mapAreaConfigs);

            config.put("autoLoadConfigOnStartup", false);
            config.put("taskRecordRetentionDays", 30);

            JSONObject voice = new JSONObject();
            voice.put("voicePromptLanguage", "");
            JSONObject voiceList = new JSONObject();
            voiceList.put("departure_broadcast", "开始配送任务");
            voiceList.put("arrival", "已经到达站点");
            voiceList.put("position_lost", "我好像迷路了，请把我推回充电桩");
            voiceList.put("low_battery", "电量不足，请及时充电");
            voiceList.put("obstacle_alert", "前方有障碍物，请小心");
            voiceList.put("task_completed", "任务已完成，感谢使用");
            voiceList.put("operation_error_prompt", "操作错误，请检查网络并再次启动");
            voiceList.put("charge_complete", "充电已完成，可以开始工作");
            voiceList.put("return_arrival", "我回来了，还有什么事情吩咐吗");
            voiceList.put("task_interrupted", "任务中断了");
            voiceList.put("lead_departure", "请跟我来吧");
            voiceList.put("cruise_departure", "我出发了，请让让我");
            voiceList.put("cruise_arrival", "我已经到达了，请尽快");
            voiceList.put("pedestrian_alert", "运行中");
            voiceList.put("go_charging", "我要去充电了");
            voiceList.put("emergency_stop1", "机器人急停被触发");
            voiceList.put("collision_alert", "机器人发生碰撞,请恢复");
            voiceList.put("navigation_error", "请避让");
            voice.put("voicePromptList", voiceList);
            config.put("voicePrompts", voice);

            isLoaded = true;
            Log.w(TAG, "Created default app settings config (fallback)");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create default config", e);
        }
    }

    // ==================== Basic Settings ====================

    public int getVolumeLevel() {
        return getSectionInt("basicSettings", "volumeLevel", 6);
    }

    public void setVolumeLevel(int value) {
        setSectionValue("basicSettings", "volumeLevel", value);
    }

    public double getSpeed() {
        return getSectionDouble("basicSettings", "speed", 0.4);
    }

    public void setSpeed(double value) {
        setSectionValue("basicSettings", "speed", value);
    }

    public int getDeliveryWaitDuration() {
        return getSectionInt("basicSettings", "deliveryWaitDuration", 30);
    }

    public void setDeliveryWaitDuration(int value) {
        setSectionValue("basicSettings", "deliveryWaitDuration", value);
    }

    public int getQueuedTaskCooldown() {
        return getSectionInt("basicSettings", "queuedTaskCooldown", 6);
    }

    public void setQueuedTaskCooldown(int value) {
        setSectionValue("basicSettings", "queuedTaskCooldown", value);
    }

    public int getLowPower() {
        return getSectionInt("basicSettings", "lowPower", 20);
    }

    public void setLowPower(int value) {
        setSectionValue("basicSettings", "lowPower", value);
    }

    public double getRecognizeDistance() {
        return getSectionDouble("basicSettings", "recognizeDistance", 1.0);
    }

    public void setRecognizeDistance(double value) {
        setSectionValue("basicSettings", "recognizeDistance", value);
    }

    public int getObstacleTime() {
        return getSectionInt("basicSettings", "obstacleTime", 0);
    }

    public void setObstacleTime(int value) {
        setSectionValue("basicSettings", "obstacleTime", value);
    }

    public String getRunningMusic() {
        return getSectionString("basicSettings", "runningMusic", "wa");
    }

    public void setRunningMusic(String value) {
        setSectionValue("basicSettings", "runningMusic", value);
    }

    // ==================== Charge Settings ====================

    public boolean isAutoChargeEnabled() {
        return getSectionBoolean("chargeSettings", "autoChargeEnabled", false);
    }

    public void setAutoChargeEnabled(boolean value) {
        setSectionValue("chargeSettings", "autoChargeEnabled", value);
    }

    public boolean isIdleChargeEnabled() {
        return getSectionBoolean("chargeSettings", "idleChargeEnabled", true);
    }

    public void setIdleChargeEnabled(boolean value) {
        setSectionValue("chargeSettings", "idleChargeEnabled", value);
    }

    public int getAutoChargeStartMinute() {
        return getSectionInt("chargeSettings", "autoChargeStartMinute", 0);
    }

    public void setAutoChargeStartMinute(int value) {
        setSectionValue("chargeSettings", "autoChargeStartMinute", value);
    }

    public int getAutoChargeEndMinute() {
        return getSectionInt("chargeSettings", "autoChargeEndMinute", 0);
    }

    public void setAutoChargeEndMinute(int value) {
        setSectionValue("chargeSettings", "autoChargeEndMinute", value);
    }

    public int getIdleChargeWaitMinutes() {
        return getSectionInt("chargeSettings", "idleChargeWaitMinutes", 10);
    }

    public void setIdleChargeWaitMinutes(int value) {
        setSectionValue("chargeSettings", "idleChargeWaitMinutes", value);
    }

    // ==================== Orbit Settings ====================

    public boolean isVirtualOrbitMode() {
        return getSectionBoolean("orbitSettings", "virtualOrbitMode", true);
    }

    public void setVirtualOrbitMode(boolean value) {
        setSectionValue("orbitSettings", "virtualOrbitMode", value);
    }

    public boolean isVirtualOrbitOneWay() {
        return getSectionBoolean("orbitSettings", "virtualOrbitOneWay", false);
    }

    public void setVirtualOrbitOneWay(boolean value) {
        setSectionValue("orbitSettings", "virtualOrbitOneWay", value);
    }

    // ==================== Communication Settings ====================

    public int getRobotId() {
        return getSectionInt("communicationSettings", "robotId", 1);
    }

    public void setRobotId(int value) {
        setSectionValue("communicationSettings", "robotId", value);
    }

    public int getLoraChannel() {
        return getSectionInt("communicationSettings", "loraChannel", 0);
    }

    public void setLoraChannel(int value) {
        setSectionValue("communicationSettings", "loraChannel", value);
    }

    public int getLoraAddress() {
        return getSectionInt("communicationSettings", "loraAddress", 0);
    }

    public void setLoraAddress(int value) {
        setSectionValue("communicationSettings", "loraAddress", value);
    }

    // ==================== Language Settings ====================

    public String getLanguage() {
        return getSectionString("languageSettings", "language", "zh");
    }

    public void setLanguage(String value) {
        setSectionValue("languageSettings", "language", value);
    }

    public boolean isLanguageChanged() {
        return getSectionBoolean("languageSettings", "languageChanged", false);
    }

    public void setLanguageChanged(boolean value) {
        setSectionValue("languageSettings", "languageChanged", value);
    }

    // ==================== Module Visibility ====================

    public boolean isJackModuleEnabled() {
        return getSectionBoolean("moduleVisibility", "jackModuleEnabled", true);
    }

    public void setJackModuleEnabled(boolean value) {
        setSectionValue("moduleVisibility", "jackModuleEnabled", value);
    }

    public boolean isShelfModuleEnabled() {
        return getSectionBoolean("moduleVisibility", "shelfModuleEnabled", true);
    }

    public void setShelfModuleEnabled(boolean value) {
        setSectionValue("moduleVisibility", "shelfModuleEnabled", value);
    }

    public boolean isElevatorModuleEnabled() {
        return getSectionBoolean("moduleVisibility", "elevatorModuleEnabled", true);
    }

    public void setElevatorModuleEnabled(boolean value) {
        setSectionValue("moduleVisibility", "elevatorModuleEnabled", value);
    }

    public boolean isCallBoxModuleEnabled() {
        return getSectionBoolean("moduleVisibility", "callBoxModuleEnabled", true);
    }

    public void setCallBoxModuleEnabled(boolean value) {
        setSectionValue("moduleVisibility", "callBoxModuleEnabled", value);
    }

    public boolean isMapAreaModuleEnabled() {
        return getSectionBoolean("moduleVisibility", "mapAreaModuleEnabled", true);
    }

    public void setMapAreaModuleEnabled(boolean value) {
        setSectionValue("moduleVisibility", "mapAreaModuleEnabled", value);
    }

    public boolean isAvoidanceModuleEnabled() {
        return getSectionBoolean("moduleVisibility", "avoidanceModuleEnabled", true);
    }

    public void setAvoidanceModuleEnabled(boolean value) {
        setSectionValue("moduleVisibility", "avoidanceModuleEnabled", value);
    }

    // ==================== Password Settings ====================

    public boolean isUsePassword() {
        return getSectionBoolean("passwordSettings", "usePassword", false);
    }

    public void setUsePassword(boolean value) {
        setSectionValue("passwordSettings", "usePassword", value);
    }

    public String getPassword() {
        return getSectionString("passwordSettings", "password", "0000");
    }

    public void setPassword(String value) {
        setSectionValue("passwordSettings", "password", value);
    }

    // ==================== Other Settings ====================

    public boolean isVisualCruiseEnabled() {
        return getRootBoolean("visualCruiseEnabled", false);
    }

    public void setVisualCruiseEnabled(boolean value) {
        setRootValue("visualCruiseEnabled", value);
    }

    public boolean isAutoLoadConfigOnStartup() {
        return getRootBoolean("autoLoadConfigOnStartup", false);
    }

    public void setAutoLoadConfigOnStartup(boolean value) {
        setRootValue("autoLoadConfigOnStartup", value);
    }

    public int getTaskRecordRetentionDays() {
        return getRootInt("taskRecordRetentionDays", 30);
    }

    public void setTaskRecordRetentionDays(int value) {
        setRootValue("taskRecordRetentionDays", value);
    }

    // ==================== Voice Prompts ====================

    public String getVoicePromptLanguage() {
        return getSectionString("voicePrompts", "voicePromptLanguage", "");
    }

    public void setVoicePromptLanguage(String value) {
        setSectionValue("voicePrompts", "voicePromptLanguage", value);
    }

    public Map<String, String> getVoicePromptList() {
        Map<String, String> result = new HashMap<>();
        if (!isLoaded || config == null) return result;

        try {
            JSONObject section = config.optJSONObject("voicePrompts");
            if (section == null) return result;

            JSONObject voiceList = section.optJSONObject("voicePromptList");
            if (voiceList == null) return result;

            Iterator<String> keys = voiceList.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, voiceList.getString(key));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse voice prompt list", e);
        }
        return result;
    }

    public void setVoicePromptList(Map<String, String> voiceList) {
        if (!isLoaded || config == null) return;

        try {
            JSONObject section = config.optJSONObject("voicePrompts");
            if (section == null) {
                section = new JSONObject();
                config.put("voicePrompts", section);
            }

            JSONObject voiceJson = new JSONObject();
            for (Map.Entry<String, String> entry : voiceList.entrySet()) {
                voiceJson.put(entry.getKey(), entry.getValue());
            }
            section.put("voicePromptList", voiceJson);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set voice prompt list", e);
        }
    }

    // ==================== Elevator / CallBox / MapArea Configs ====================

    /**
     * Get elevator configs as a JSON string (for Gson deserialization).
     */
    public String getElevatorConfigsJson() {
        if (!isLoaded || config == null) return "[]";
        JSONArray arr = config.optJSONArray("elevatorConfigs");
        return arr != null ? arr.toString() : "[]";
    }

    public void setElevatorConfigsJson(String jsonStr) {
        if (!isLoaded || config == null) return;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            config.put("elevatorConfigs", arr);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set elevatorConfigs", e);
        }
    }

    /**
     * Get call box configs as a JSON string (for Gson deserialization).
     */
    public String getCallBoxConfigsJson() {
        if (!isLoaded || config == null) return "[]";
        JSONArray arr = config.optJSONArray("callBoxConfigs");
        return arr != null ? arr.toString() : "[]";
    }

    public void setCallBoxConfigsJson(String jsonStr) {
        if (!isLoaded || config == null) return;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            config.put("callBoxConfigs", arr);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set callBoxConfigs", e);
        }
    }

    /**
     * Get map area configs as a JSON string (for Gson deserialization).
     */
    public String getMapAreaConfigsJson() {
        if (!isLoaded || config == null) return "[]";
        JSONArray arr = config.optJSONArray("mapAreaConfigs");
        return arr != null ? arr.toString() : "[]";
    }

    public void setMapAreaConfigsJson(String jsonStr) {
        if (!isLoaded || config == null) return;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            config.put("mapAreaConfigs", arr);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set mapAreaConfigs", e);
        }
    }

    /**
     * Get position map as a JSON string (for Gson deserialization).
     */
    public String getPositionMapJson() {
        if (!isLoaded || config == null) return "{}";
        JSONObject obj = config.optJSONObject("positionMap");
        return obj != null ? obj.toString() : "{}";
    }

    public void setPositionMapJson(String jsonStr) {
        if (!isLoaded || config == null) return;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            config.put("positionMap", obj);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set positionMap", e);
        }
    }

    /**
     * Get cruise task map as a JSON string (for Gson deserialization).
     */
    public String getCruiseTaskMapJson() {
        if (!isLoaded || config == null) return "{}";
        JSONObject obj = config.optJSONObject("cruiseTaskMap");
        return obj != null ? obj.toString() : "{}";
    }

    public void setCruiseTaskMapJson(String jsonStr) {
        if (!isLoaded || config == null) return;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            config.put("cruiseTaskMap", obj);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set cruiseTaskMap", e);
        }
    }

    /**
     * Get jack task map as a JSON string (for Gson deserialization).
     */
    public String getJackTaskMapJson() {
        if (!isLoaded || config == null) return "{}";
        JSONObject obj = config.optJSONObject("jackTaskMap");
        return obj != null ? obj.toString() : "{}";
    }

    public void setJackTaskMapJson(String jsonStr) {
        if (!isLoaded || config == null) return;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            config.put("jackTaskMap", obj);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set jackTaskMap", e);
        }
    }

    /**
     * Get current map points as a JSON string (for Gson deserialization).
     * This contains all positions across buildings and floors.
     */
    public String getCurrentMapPointsJson() {
        if (!isLoaded || config == null) {
            Log.w(TAG, "getCurrentMapPointsJson: config not loaded, returning {}");
            return "{}";
        }
        JSONObject obj = config.optJSONObject("currentMapPoints");
        if (obj == null) {
            Log.w(TAG, "getCurrentMapPointsJson: currentMapPoints is null in config, returning {}");
            return "{}";
        }
        String result = obj.toString();
        Log.d(TAG, "getCurrentMapPointsJson: json length=" + result.length() + ", preview=" +
                (result.length() > 200 ? result.substring(0, 200) + "..." : result));
        return result;
    }

    public void setCurrentMapPointsJson(String jsonStr) {
        if (!isLoaded || config == null) {
            Log.w(TAG, "setCurrentMapPointsJson: config not loaded, skipping");
            return;
        }
        try {
            JSONObject obj = new JSONObject(jsonStr);
            config.put("currentMapPoints", obj);
            Log.d(TAG, "setCurrentMapPointsJson: saved currentMapPoints, json length=" + jsonStr.length() +
                    ", buildings in json=" + (obj.has("buildings") ? obj.optJSONObject("buildings").length() : 0));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set currentMapPoints", e);
        }
    }

    // ==================== Generic Getters ====================

    private int getSectionInt(String section, String key, int defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        try {
            JSONObject s = config.optJSONObject(section);
            return s != null ? s.optInt(key, defaultValue) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double getSectionDouble(String section, String key, double defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        try {
            JSONObject s = config.optJSONObject(section);
            return s != null ? s.optDouble(key, defaultValue) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean getSectionBoolean(String section, String key, boolean defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        try {
            JSONObject s = config.optJSONObject(section);
            return s != null ? s.optBoolean(key, defaultValue) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String getSectionString(String section, String key, String defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        try {
            JSONObject s = config.optJSONObject(section);
            return s != null ? s.optString(key, defaultValue) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int getRootInt(String key, int defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        return config.optInt(key, defaultValue);
    }

    private boolean getRootBoolean(String key, boolean defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        return config.optBoolean(key, defaultValue);
    }

    // ==================== Generic Setters ====================

    private void setSectionValue(String section, String key, Object value) {
        if (!isLoaded || config == null) return;
        try {
            JSONObject s = config.optJSONObject(section);
            if (s == null) {
                s = new JSONObject();
                config.put(section, s);
            }
            s.put(key, value);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set " + section + "." + key, e);
        }
    }

    private void setRootValue(String key, Object value) {
        if (!isLoaded || config == null) return;
        try {
            config.put(key, value);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set root " + key, e);
        }
    }

    /**
     * Create a default Position JSON object for elevator floor points.
     */
    private JSONObject createDefaultPosition(int type, String floor) throws JSONException {
        JSONObject pos = new JSONObject();
        pos.put("id", 0);
        pos.put("name", "");
        pos.put("posX", 0.0);
        pos.put("posY", 0.0);
        pos.put("yaw", 0.0);
        pos.put("type", type);
        pos.put("floor", floor);
        pos.put("mapName", "");
        pos.put("taskType", 0);
        pos.put("isDefaultPoint", false);
        pos.put("isVirtualOrbit", false);
        pos.put("recognizeDistance", 1.0);
        pos.put("doorId", 0);
        pos.put("stayDuration", 0);
        return pos;
    }
}
