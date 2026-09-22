package com.ezhan.amr;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.ezhan.amr.communication.lora.LoraRequestScheduler;
import com.ezhan.amr.communication.doorlock.DoorLockCommunicator;
import com.ezhan.amr.communication.doorlock.DoorLockStatus;
import com.ezhan.amr.communication.rfid.RFIDCommunicator;
import com.ezhan.amr.communication.camera.CameraManager;
import com.ezhan.amr.navigation.BroadcastHelper;
import com.ezhan.amr.navigation.TaskExecutor;
import com.ezhan.amr.navigation.area.MapAreaDeviceDispatcher;
import com.ezhan.amr.navigation.area.MapAreaTriggerManager;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.ezhan.amr.utils.AppSettingsConfig;
import com.ezhan.amr.utils.ConfigManager;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.CallboxViewModel;
import com.ezhan.amr.viewmodels.CruiseViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.ui.GlobalTaskRunningFabManager;
import com.ezhan.amr.utils.AppLifecycleTracker;
import com.ezhan.amr.navigation.CruiseTaskScheduler;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.TaskViewModel;
import com.ezhan.amr.viewmodels.UserViewModel;
import com.ezhan.amr.viewmodels.RfidViewModel;
import com.ezhan.amr.web.CallboxService;
import com.ezhan.amr.web.RcsHttpService;
import com.ezhan.amr.web.RcsWebSocketService;
import com.ezhan.amr.web.WebService;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelStoreOwner;
import android.content.Intent;

public class MyApplication extends Application implements ViewModelStoreOwner  {
    private static MyApplication instance;
    private static Context appContext;
    private ViewModelStore mViewModelStore;
    private SharedViewModel sharedViewModel;
    private MapViewModel mapViewModel;
    private ElevatorViewModel elevatorViewModel;
    private JackViewModel jackViewModel;
    private CruiseViewModel cruiseViewModel;
    private BasicViewModel basicViewModel;
    private UserViewModel userViewModel;
    private RfidViewModel rfidViewModel;
    private CallboxViewModel callboxViewModel;
    private TaskViewModel taskViewModel;
    private WebService webService;
    private LoraCommunicator loraCommunicator;
    private LoraRequestScheduler loraRequestScheduler;
    private MapAreaTriggerManager mapAreaTriggerManager;
    private com.ezhan.amr.navigation.area.TrafficAreaManager trafficAreaManager;
    private DoorLockCommunicator doorLockCommunicator;
    private RFIDCommunicator rfidCommunicator;
    private TaskExecutor taskExecutor;
    private final String serialPort = "/dev/ttyS4";
    private final String doorLockSerialPort = "/dev/ttyS7";
    private final String rfidSerialPort = "/dev/ttyS7";
    private RcsHttpService rcsHttpService;
    private RcsWebSocketService rcsWebSocketService;
    private CallboxService callboxService;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        appContext = getApplicationContext();

        // Load configuration FIRST
        ConfigManager.getInstance().loadConfig(this);
        AppSettingsConfig appSettingsConfig = AppSettingsConfig.getInstance();
        appSettingsConfig.loadConfig(this);

        mViewModelStore = new ViewModelStore();
        initializeViewModels();

        // Apply config to view models if auto-load is enabled
        if (appSettingsConfig.isAutoLoadConfigOnStartup()) {
            applyConfigToViewModels(appSettingsConfig);
        }

        // Initialize navigation state debugger (only in debug mode if configured)
        if (ConfigManager.getInstance().isDebugMode()) {
            NavigationStateDebugger debugger = NavigationStateDebugger.getInstance();
            debugger.init(this);
            debugger.setEnabled(true);
            debugger.setWriteToFile(true);
            debugger.startNewLogSession();
        }

        // Conditionally start services based on configuration
        startServicesConditionally();

        BroadcastHelper.initialize(this);
        CruiseTaskScheduler.initialize(this);
        AppLifecycleTracker.initialize(this);
        GlobalTaskRunningFabManager.initialize(this);

        taskExecutor = TaskExecutor.getInstance(
                this,
                getJackViewModel(),
                getCruiseViewModel()
        );

        try {
            loraCommunicator = LoraCommunicator.getInstance(serialPort);
            com.ezhan.amr.navigation.area.TrafficLog.init(this);
            initializeMapAreaTriggerManager();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 初始化门锁RS485通信
        try {
            doorLockCommunicator = DoorLockCommunicator.getInstance(doorLockSerialPort);
            // 设置门锁状态监听器
            doorLockCommunicator.setDoorLockListener(new DoorLockCommunicator.DoorLockListener() {
                @Override
                public void onLockStatusChanged(int lockId, DoorLockStatus status) {
                    Log.d("MyApplication", "门锁" + lockId + "状态变化: " + status.getDescription());
                }

                @Override
                public void onCommunicationError(String error) {
                    Log.e("MyApplication", "门锁通信错误: " + error);
                }
            });
            Log.i("MyApplication", "门锁RS485通信已初始化，端口: " + doorLockSerialPort);

        } catch (IOException e) {
            Log.e("MyApplication", "门锁RS485通信初始化失败: " + e.getMessage(), e);
        }

        // 初始化RFID通信
        try {
            rfidCommunicator = RFIDCommunicator.getInstance(rfidSerialPort);
            // 设置RFID状态监听器
            rfidCommunicator.setRFIDListener(new RFIDCommunicator.RFIDListener() {
                @Override
                public void onTagRead(String tagId) {
                    Log.d("MyApplication", "RFID标签读取: " + tagId);
                }

                @Override
                public void onCommunicationError(String error) {
                    Log.e("MyApplication", "RFID通信错误: " + error);
                }
            });
            // 设置RFID数据监听器 - 全局监听
            RFIDCommunicator.RFIDDataListener globalListener = new RFIDCommunicator.RFIDDataListener() {
                @Override
                public void onRfidDataReceived(String rfidData) {
                    Log.d("MyApplication", "全局收到RFID数据: " + rfidData);
                    
                    // 解析卡片序列号（第9-12字节）
                    if (rfidData.length() >= 24) {
                        String cardSerial = rfidData.substring(16, 24);
                        Log.d("MyApplication", "解析到卡片序列号: " + cardSerial);
                        
                        // 查找RFID卡片信息
                        List<com.ezhan.amr.data.datatype.RfidData> rfidList = rfidViewModel.getRfidList().getValue();
                        if (rfidList != null) {
                            for (com.ezhan.amr.data.datatype.RfidData rfid : rfidList) {
                                if (rfid.getRfidSerial().equals(cardSerial)) {
                                    String userId = rfid.getUserId();
                                    Log.d("MyApplication", "找到RFID卡片，关联用户ID: " + userId);
                                    
                                    // 查找用户名称
                                    List<com.ezhan.amr.data.datatype.User> userList = userViewModel.getUserList().getValue();
                                    if (userList != null) {
                                        for (com.ezhan.amr.data.datatype.User user : userList) {
                                            if (user.getUserId().equals(userId)) {
                                                String userName = user.getName();
                                                Log.i("MyApplication", "RFID刷卡 - 序列号: " + cardSerial + ", 用户ID: " + userId + ", 用户名称: " + userName);
                                                return;
                                            }
                                        }
                                    }
                                    Log.w("MyApplication", "未找到用户ID: " + userId + " 对应的用户信息");
                                    return;
                                }
                            }
                        }
                        Log.w("MyApplication", "未找到RFID序列号: " + cardSerial + " 对应的卡片信息");
                    }
                }
            };
            rfidCommunicator.addRfidDataListener(globalListener);
            // 启动RFID后台监听
            rfidCommunicator.startListening();
            Log.i("MyApplication", "RFID通信已初始化，端口: " + rfidSerialPort);

        } catch (IOException e) {
            Log.e("MyApplication", "RFID通信初始化失败: " + e.getMessage(), e);
        }

        // 初始化相机并开始10秒录制
        new Handler(Looper.getMainLooper()).postDelayed(this::startCameraRecording, 2000);
    }

    private void startServicesConditionally() {
        ConfigManager config = ConfigManager.getInstance();

        // Start WebService if enabled
        if (config.isWebServiceEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent serviceIntent = new Intent(this, WebService.class);
                    startService(serviceIntent);
                    Log.d("MyApplication", "WebService started (enabled by config)");
                } catch (Exception e) {
                    Log.e("MyApplication", "Failed to start WebService", e);
                }
            }, 1000);
        } else {
            Log.d("MyApplication", "WebService disabled by configuration");
        }

        // Start RcsHttpService if enabled
        if (config.isRcsHttpServiceEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent serviceIntent = new Intent(this, RcsHttpService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Log.d("MyApplication", "RcsHttpService started (enabled by config)");
                } catch (Exception e) {
                    Log.e("MyApplication", "Failed to start RcsHttpService", e);
                }
            }, 1000);
        } else {
            Log.d("MyApplication", "RcsHttpService disabled by configuration");
        }

        // Start RcsWebSocketService if enabled
        if (config.isRcsWebSocketServiceEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent serviceIntent = new Intent(this, RcsWebSocketService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Log.d("MyApplication", "RcsWebSocketService started (enabled by config)");
                } catch (Exception e) {
                    Log.e("MyApplication", "Failed to start RcsWebSocketService", e);
                }
            }, 1000);
        } else {
            Log.d("MyApplication", "RcsWebSocketService disabled by configuration");
        }

        // Start CallboxService if enabled
        if (config.isCallboxServiceEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent serviceIntent = new Intent(this, CallboxService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Log.d("MyApplication", "CallboxService started (enabled by config)");
                } catch (Exception e) {
                    Log.e("MyApplication", "Failed to start CallboxService", e);
                }
            }, 1000);
        } else {
            Log.d("MyApplication", "CallboxService disabled by configuration");
        }

        // Start ModbusService if enabled
        if (config.isModbusServiceEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent serviceIntent = new Intent(this, com.ezhan.amr.communication.modbus.ModbusService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Log.d("MyApplication", "ModbusService started (enabled by config)");
                } catch (Exception e) {
                    Log.e("MyApplication", "Failed to start ModbusService", e);
                }
            }, 1500);
        } else {
            Log.d("MyApplication", "ModbusService disabled by configuration");
        }
    }

    private CameraManager cameraManager;

    private void startCameraRecording() {
        cameraManager = new CameraManager(this);
        Log.d("MyApplication", "开始检测可用摄像头");
        
        java.util.List<CameraManager.CameraInfo> cameras = cameraManager.listAvailableCameras();
        if (!cameras.isEmpty()) {
            Log.d("MyApplication", "找到 " + cameras.size() + " 个摄像头");
            int cameraId = cameras.get(0).id;
            Log.d("MyApplication", "使用摄像头 ID: " + cameraId);
            
            // 1. 打开摄像头并保持运行
            if (cameraManager.openCamera(cameraId)) {
                Log.d("MyApplication", "摄像头已启动并保持运行");
                
                // 2. 开始录制
                if (cameraManager.startRecording()) {
                    Log.d("MyApplication", "开始录制视频");
                    
                    // 10秒后停止录制
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        cameraManager.stopRecording();
                        Log.d("MyApplication", "10秒录制完成，文件保存到: " + cameraManager.getVideoFilePath());
                        // 保持摄像头打开状态，不释放
                        Log.d("MyApplication", "摄像头保持运行状态");
                    }, 10000);
                } else {
                    Log.e("MyApplication", "无法开始录制");
                }
            } else {
                Log.e("MyApplication", "无法打开摄像头");
            }
        } else {
            Log.e("MyApplication", "没有找到可用摄像头");
        }
    }

    // 在应用退出时释放摄像头资源
    @Override
    public void onTerminate() {
        super.onTerminate();
        if (cameraManager != null) {
            cameraManager.release();
            Log.d("MyApplication", "摄像头资源已释放");
        }
        if (rfidCommunicator != null) {
            rfidCommunicator.close();
            Log.d("MyApplication", "RFIDCommunicator已关闭");
        }
        if (doorLockCommunicator != null) {
            doorLockCommunicator.close();
            Log.d("MyApplication", "DoorLockCommunicator已关闭");
        }
        if (mapAreaTriggerManager != null) {
            mapAreaTriggerManager.stop();
        }
        if (trafficAreaManager != null) {
            trafficAreaManager.stop();
        }
        if (loraRequestScheduler != null) {
            loraRequestScheduler.shutdown();
        }
    }

    @NonNull
    @Override
    public ViewModelStore getViewModelStore() {
        return mViewModelStore;
    }

    public static Context getAppContext() {
        return appContext;
    }

    public static MyApplication getInstance() {
        return instance;
    }

    public com.ezhan.amr.data.datastore.DataStoreManager getDataStoreManager() {
        return com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this);
    }

    public void bindWebService(WebService service) {
        this.webService = service;
        Log.d("MyApplication", "WebService实例已绑定");
    }

    public void bindRcsHttpService(RcsHttpService service) {
        this.rcsHttpService = service;
    }

    public RcsHttpService getRcsHttpService() {
        return rcsHttpService;
    }

    public void bindRcsWebSocketService(RcsWebSocketService service) {
        this.rcsWebSocketService = service;
    }

    public RcsWebSocketService getRcsWebSocketService() {
        return rcsWebSocketService;
    }

    public void bindCallboxService(CallboxService service) {
        this.callboxService = service;
        Log.d("MyApplication", "CallboxService实例已绑定");
    }

    public String getWebServerUrl() {
        if (webService == null) {
            return null;
        }
        return "http://" + getLocalIpAddress() + ":" + webService.getPort(); // 使用实际端口
    }

    private void initializeViewModels() {
        ViewModelProvider provider = new ViewModelProvider(
                this, // Application as ViewModelStoreOwner
                ViewModelProvider.AndroidViewModelFactory.getInstance(this)
        );

        sharedViewModel = provider.get(SharedViewModel.class);

        sharedViewModel.initializeWebSocket();
        Log.d("MyApplication", "WebSocket initialized on app startup");

        callboxViewModel = provider.get(CallboxViewModel.class);
        mapViewModel = provider.get(MapViewModel.class);
        elevatorViewModel = provider.get(ElevatorViewModel.class);
        jackViewModel = provider.get(JackViewModel.class);
        cruiseViewModel = provider.get(CruiseViewModel.class);
        basicViewModel = provider.get(BasicViewModel.class);
        taskViewModel = provider.get(TaskViewModel.class);
        userViewModel = provider.get(UserViewModel.class);
        rfidViewModel = provider.get(RfidViewModel.class);
    }

    private void applyConfigToViewModels(AppSettingsConfig cfg) {
        if (basicViewModel == null) return;

        // Apply to ViewModel (persists to DataStore)
        basicViewModel.setVolumeLevel(cfg.getVolumeLevel());
        basicViewModel.setSpeed(cfg.getSpeed());
        basicViewModel.setDeliveryWaitDuration(cfg.getDeliveryWaitDuration());
        basicViewModel.setQueuedTaskCooldown(cfg.getQueuedTaskCooldown());
        basicViewModel.setLowPower(cfg.getLowPower());
        basicViewModel.setRecognizeDistance(cfg.getRecognizeDistance());
        basicViewModel.setVirtualOrbitObstacleTime(cfg.getObstacleTime());
        basicViewModel.setAutoCharge(cfg.isAutoChargeEnabled());
        basicViewModel.setIdleCharge(cfg.isIdleChargeEnabled());
        basicViewModel.setAutoChargeStartMinute(cfg.getAutoChargeStartMinute());
        basicViewModel.setAutoChargeEndMinute(cfg.getAutoChargeEndMinute());
        basicViewModel.setIdleChargeWaitMinutes(cfg.getIdleChargeWaitMinutes());
        basicViewModel.setVirtualOrbitMode(cfg.isVirtualOrbitMode());
        basicViewModel.setVirtualOrbitOneWayMode(cfg.isVirtualOrbitOneWay());
        basicViewModel.setRobotId(cfg.getRobotId());
        basicViewModel.setLoraChannel(cfg.getLoraChannel());
        basicViewModel.setLoraAddress(cfg.getLoraAddress());
        basicViewModel.setRunningMusic(cfg.getRunningMusic());
        basicViewModel.setUsePassword(cfg.isUsePassword());
        basicViewModel.setPassword(cfg.getPassword());
        basicViewModel.setVisualCruise(cfg.isVisualCruiseEnabled());
        basicViewModel.setLanguageChanged(cfg.isLanguageChanged());
        if (cfg.getLanguage() != null) {
            // Apply language change to system
            com.ezhan.amr.utils.LocaleHelper.applyNewLocale(this, cfg.getLanguage());
            // Reset voice prompts to new language
            basicViewModel.resetVoicePromptsToCurrentLanguage(this);
            // Reset languageChanged flag after applying
            cfg.setLanguageChanged(false);
            cfg.saveConfig();
        }

        // Apply voice prompts
        java.util.Map<String, String> voiceList = cfg.getVoicePromptList();
        if (voiceList != null && !voiceList.isEmpty()) {
            basicViewModel.saveToPreferences(voiceList);
        }

        // Apply elevator configs from config file
        String elevatorJson = cfg.getElevatorConfigsJson();
        if (elevatorJson != null && !elevatorJson.equals("[]")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.ELEVATOR_CONFIGS, elevatorJson);
                java.lang.reflect.Type type = com.ezhan.amr.data.datastore.DataStoreKeys.ELEVATOR_CONFIGS_TYPE;
                java.util.List<com.ezhan.amr.viewmodels.ElevatorViewModel.ElevatorConfig> elevatorConfigs =
                        new com.google.gson.Gson().fromJson(elevatorJson, type);
                if (elevatorConfigs != null) {
                    elevatorViewModel.elevatorConfigs.postValue(elevatorConfigs);
                    // Sync elevator points to map points so they persist across fragment refreshes
                    // Pass configs directly to avoid async LiveData delay
                    elevatorViewModel.syncAllConfigsToMapPoints(elevatorConfigs);
                }
            } catch (Exception e) {
                Log.e("MyApplication", "Failed to apply elevator configs on startup", e);
            }
        }

        // Apply call box configs from config file - 使用 SharedPreferences 避免 DataStore 断电丢失
        String callBoxJson = cfg.getCallBoxConfigsJson();
        if (callBoxJson != null && !callBoxJson.equals("[]")) {
            try {
                // 使用 SharedPreferences.commit() 同步写入，防止断电数据丢失
                android.content.SharedPreferences sp = getSharedPreferences("call_settings", Context.MODE_PRIVATE);
                sp.edit().putString("call_boxes_json", callBoxJson).commit();
                
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox>>() {}.getType();
                java.util.List<com.ezhan.amr.viewmodels.CallboxViewModel.CallBox> callBoxes =
                        new com.google.gson.Gson().fromJson(callBoxJson, type);
                if (callBoxes != null) {
                    callboxViewModel.setCallBoxes(callBoxes);
                }
            } catch (Exception e) {
                Log.e("MyApplication", "Failed to apply call box configs on startup", e);
            }
        }

        // Apply map area configs from config file - 使用 SharedPreferences 避免 DataStore 断电丢失
        String mapAreaJson = cfg.getMapAreaConfigsJson();
        if (mapAreaJson != null && !mapAreaJson.equals("[]")) {
            try {
                // 使用 SharedPreferences.commit() 同步写入，防止断电数据丢失
                android.content.SharedPreferences sp = getSharedPreferences("map_area_settings", Context.MODE_PRIVATE);
                sp.edit().putString("map_areas_json", mapAreaJson).commit();
                
                // 通知 MapAreaTriggerManager 重新加载区域
                if (mapAreaTriggerManager != null) {
                    mapAreaTriggerManager.reloadAreas();
                }
            } catch (Exception e) {
                Log.e("MyApplication", "Failed to apply map area configs on startup", e);
            }
        }

        // Apply position map from config file
        String positionMapJson = cfg.getPositionMapJson();
        if (positionMapJson != null && !positionMapJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP, positionMapJson);
                java.util.Map<String, com.ezhan.amr.data.datatype.Position> positionMap =
                        new com.google.gson.Gson().fromJson(positionMapJson, com.ezhan.amr.data.datastore.DataStoreKeys.POSITION_MAP_TYPE);
                if (positionMap != null) {
                    cruiseViewModel.setPositionMap(new java.util.HashMap<>(positionMap));
                }
            } catch (Exception e) {
                Log.e("MyApplication", "Failed to apply position map on startup", e);
            }
        }

        // Apply cruise task map from config file
        String cruiseTaskMapJson = cfg.getCruiseTaskMapJson();
        if (cruiseTaskMapJson != null && !cruiseTaskMapJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP, cruiseTaskMapJson);
                java.util.Map<Integer, com.ezhan.amr.data.datatype.CruiseTask> cruiseTaskMap =
                        new com.google.gson.Gson().fromJson(cruiseTaskMapJson, com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE);
                if (cruiseTaskMap != null) {
                    cruiseViewModel.setCruiseTaskMap(new java.util.LinkedHashMap<>(cruiseTaskMap));
                }
            } catch (Exception e) {
                Log.e("MyApplication", "Failed to apply cruise task map on startup", e);
            }
        }

        // Apply jack task map from config file
        String jackTaskMapJson = cfg.getJackTaskMapJson();
        if (jackTaskMapJson != null && !jackTaskMapJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP, jackTaskMapJson);
                java.util.Map<Integer, com.ezhan.amr.data.datatype.JackTask> jackTaskMap =
                        new com.google.gson.Gson().fromJson(jackTaskMapJson, com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE);
                if (jackTaskMap != null) {
                    jackViewModel.setJackTaskMap(new java.util.LinkedHashMap<>(jackTaskMap));
                }
            } catch (Exception e) {
                Log.e("MyApplication", "Failed to apply jack task map on startup", e);
            }
        }

        // Apply current map points (点位设置) from config file
        String currentMapPointsJson = cfg.getCurrentMapPointsJson();
        Log.i("taskDebug7", "[APP-CFG] applyOnStartup: currentMapPointsJson len=" +
                (currentMapPointsJson != null ? currentMapPointsJson.length() : "null"));
        if (currentMapPointsJson != null && !currentMapPointsJson.equals("{}")) {
            try {
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
                        .putString(com.ezhan.amr.data.datastore.DataStoreKeys.CURRENT_MAP_POINTS, currentMapPointsJson);
                com.ezhan.amr.data.datatype.MultiBuildingMapPoints mapPoints =
                        new com.google.gson.Gson().fromJson(currentMapPointsJson, com.ezhan.amr.data.datastore.DataStoreKeys.MAP_POINTS_TYPE);
                int buildings = mapPoints != null ? mapPoints.getAllBuildings().size() : 0;
                int totalPoints = mapPoints != null ? mapPoints.getTotalPositionCount() : 0;
                Log.i("taskDebug7", "[APP-CFG] deserialized: buildings=" + buildings + ", totalPoints=" + totalPoints);
                if (mapPoints != null && !mapPoints.getAllBuildings().isEmpty()) {
                    mapViewModel.updateCurrentMapPoints(mapPoints);
                } else {
                    Log.w("taskDebug7", "[APP-CFG] mapPoints is null or empty, skipping update");
                }
            } catch (Exception e) {
                Log.e("taskDebug7", "[APP-CFG] Failed to apply current map points on startup", e);
            }
        } else {
            Log.w("taskDebug7", "[APP-CFG] currentMapPointsJson is null or empty '{}' - NO config points loaded");
        }

        // Sync to SharedPreferences so UI can load values via loadSavedSettings()
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("volume", cfg.getVolumeLevel());
        editor.putFloat("speed", (float) cfg.getSpeed());
        editor.putInt("delivery_stay", cfg.getDeliveryWaitDuration());
        editor.putInt("low_power", cfg.getLowPower());
        editor.putBoolean("switch_b", cfg.isAutoChargeEnabled());
        editor.putBoolean("idle_charge_enabled", cfg.isIdleChargeEnabled());
        editor.putInt("auto_charge_start_minute", cfg.getAutoChargeStartMinute());
        editor.putInt("auto_charge_end_minute", cfg.getAutoChargeEndMinute());
        editor.putInt("idle_charge_wait_minutes", cfg.getIdleChargeWaitMinutes());
        editor.putBoolean("virtual_orbit_mode", cfg.isVirtualOrbitMode());
        editor.putBoolean("virtual_orbit_one_way", cfg.isVirtualOrbitOneWay());
        editor.putInt("robot_id", cfg.getRobotId());
        editor.putInt("lora_channel", cfg.getLoraChannel());
        editor.putInt("lora_address", cfg.getLoraAddress());
        editor.putInt("obstacle_time", cfg.getObstacleTime());
        editor.putString("running_music", cfg.getRunningMusic());
        editor.putString("language", cfg.getLanguage());
        // Module visibility
        editor.putBoolean(com.ezhan.amr.utils.FeatureVisibilitySettings.KEY_SHOW_JACK_MODE, cfg.isJackModuleEnabled());
        editor.putBoolean(com.ezhan.amr.utils.FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, cfg.isShelfModuleEnabled());
        editor.putBoolean(com.ezhan.amr.utils.FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, cfg.isElevatorModuleEnabled());
        editor.putBoolean(com.ezhan.amr.utils.FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, cfg.isCallBoxModuleEnabled());
        editor.putBoolean(com.ezhan.amr.utils.FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, cfg.isMapAreaModuleEnabled());
        editor.putBoolean(com.ezhan.amr.utils.FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, cfg.isAvoidanceModuleEnabled());
        editor.commit();

        Log.i("MyApplication", "App settings config applied to view models and SharedPreferences on startup");
    }

    private void initializeMapAreaTriggerManager() {
        if (mapAreaTriggerManager != null) {
            return;
        }

        loraRequestScheduler = LoraRequestScheduler.getInstance(loraCommunicator);
        MapAreaDeviceDispatcher dispatcher = new MapAreaDeviceDispatcher(
                loraRequestScheduler,
                getBasicViewModel()
        );
        mapAreaTriggerManager = new MapAreaTriggerManager(
                this,
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this),
                getSharedViewModel(),
                getMapViewModel(),
                dispatcher
        );

        // 初始化交管区域状态机
        trafficAreaManager = new com.ezhan.amr.navigation.area.TrafficAreaManager(
                loraRequestScheduler,
                getBasicViewModel(),
                getSharedViewModel(),
                loraCommunicator,
                com.ezhan.amr.data.datastore.DataStoreManager.getInstance(this)
        );
        // 接线: LoRa 接收 -> TrafficAreaManager
        loraCommunicator.setTrafficPacketListener(trafficAreaManager::onTrafficPacket);
        // 注入: 交管区域 enter/exit 路由到 TrafficAreaManager
        mapAreaTriggerManager.setTrafficAreaManager(trafficAreaManager);
        trafficAreaManager.start();

        mapAreaTriggerManager.start();
        Log.d("MyApplication", "MapAreaTriggerManager + TrafficAreaManager initialized");
    }

    private void startWebServer() {
        // 替换原来的实现
        try {
            Intent serviceIntent = new Intent(this, WebService.class);
            startService(serviceIntent);
            Log.d("MyApplication", "已启动Web服务");
        } catch (Exception e) {
            Log.e("MyApplication", "Failed to start CallboxService", e);
            // Consider retry logic or user notification
        }
    }

    private void startRcsHttpServer() {
        try {
            Intent serviceIntent = new Intent(this, RcsHttpService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d("MyApplication", "已启动RCS-HTTP服务");
        } catch (Exception e) {
            Log.e("MyApplication", "Failed to start RcsHttpService", e);
            // Consider retry logic or user notification
        }
    }
    private void startRcsWebSocketServer() {
        try {
            Intent serviceIntent = new Intent(this, RcsWebSocketService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d("MyApplication", "已启动RCS-WebSocket服务");
        } catch (Exception e) {
            Log.e("MyApplication", "Failed to start RcsWebSocketService", e);
            // Consider retry logic or user notification
        }
    }

    private void startCallboxServer() {
        try {
            Intent serviceIntent = new Intent(this, CallboxService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d("MyApplication", "已启动Callbox服务");
        } catch (Exception e) {
            Log.e("MyApplication", "Failed to start CallboxService", e);
            // Consider retry logic or user notification
        }
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                // 优先检查WiFi接口，通常名为"wlan0"或类似
                if (intf.getName().startsWith("wlan")) {
                    Log.d("MyApplication", "检查 WiFi 接口: " + intf.getName());
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                         enumIpAddr.hasMoreElements();) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                            String ip = inetAddress.getHostAddress();
                            Log.d("MyApplication", "找到 WiFi 接口 IP: " + ip);
                            return ip;
                        }
                    }
                }
            }


            // 未找到 WiFi IP，回退到遍历所有网络接口
            Log.d("MyApplication", "未找到 WiFi IP，回退到所有接口遍历");
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                Log.d("MyApplication", "检查接口: " + intf.getName());
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                     enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        Log.d("MyApplication", "找到 IP: " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MyApplication", "获取 IP 地址失败", e);
        }
        Log.d("MyApplication", "未找到有效的 IPv4 地址，返回 127.0.0.1");
        return "127.0.0.1";
    }

    public String getFullWebUrl() {
        String ip = getLocalIpAddress();
        return ip != null ? "http://" + ip + ":8080" : null;
    }

    public SharedViewModel getSharedViewModel() {
        if (sharedViewModel == null) {
            sharedViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(SharedViewModel.class);
        }
        return sharedViewModel;
    }

    public MapViewModel getMapViewModel() {
        if (mapViewModel == null) {
            mapViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(MapViewModel.class);
        }
        return mapViewModel;
    }

    public ElevatorViewModel getElevatorViewModel() {
        if (elevatorViewModel == null) {
            elevatorViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(ElevatorViewModel.class);
        }
        return elevatorViewModel;
    }

    public JackViewModel getJackViewModel() {
        if (jackViewModel == null) {
            jackViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(JackViewModel.class);
        }
        return jackViewModel;
    }

    public CruiseViewModel getCruiseViewModel() {
        if (cruiseViewModel == null) {
            cruiseViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(CruiseViewModel.class);
        }
        return cruiseViewModel;
    }

    /**
     * Apply config from file to all ViewModels.
     * Called by HTTP endpoint /config/load
     */
    public void applyConfigFromFile(AppSettingsConfig cfg) {
        applyConfigToViewModels(cfg);
    }

    public BasicViewModel getBasicViewModel() {
        if (basicViewModel == null) {
            basicViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(BasicViewModel.class);
        }
        return basicViewModel;
    }

    public CallboxViewModel getCallboxViewModel() {
        if (callboxViewModel == null) {
            callboxViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(CallboxViewModel.class);
        }
        return callboxViewModel;
    }

    public TaskViewModel getTaskViewModel() {
        if (taskViewModel == null) {
            taskViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(TaskViewModel.class);
        }
        return taskViewModel;
    }

    public RfidViewModel getRfidViewModel() {
        if (rfidViewModel == null) {
            rfidViewModel = new ViewModelProvider.AndroidViewModelFactory(this)
                    .create(RfidViewModel.class);
        }
        return rfidViewModel;
    }

    public TaskExecutor getTaskExecutor() {
        if (taskExecutor == null) {
            taskExecutor = TaskExecutor.getInstance(
                    this,
                    getJackViewModel(),
                    getCruiseViewModel()
            );
        }
        return taskExecutor;
    }

    public LoraCommunicator getLoraCommunicator() throws IOException {
        if (loraCommunicator == null) {
            loraCommunicator = LoraCommunicator.getInstance(serialPort);
        }
        return loraCommunicator;
    }

    public LoraRequestScheduler getLoraRequestScheduler() throws IOException {
        if (loraRequestScheduler == null) {
            loraRequestScheduler = LoraRequestScheduler.getInstance(getLoraCommunicator());
        }
        return loraRequestScheduler;
    }

    public MapAreaTriggerManager getMapAreaTriggerManager() {
        if (mapAreaTriggerManager == null && loraCommunicator != null) {
            initializeMapAreaTriggerManager();
        }
        return mapAreaTriggerManager;
    }

    public com.ezhan.amr.navigation.area.TrafficAreaManager getTrafficAreaManager() {
        return trafficAreaManager;
    }

    /**
     * 获取门锁RS485通信控制器
     *
     * @return DoorLockCommunicator实例
     * @throws IOException 初始化失败时抛出
     */
    public DoorLockCommunicator getDoorLockCommunicator() throws IOException {
        if (doorLockCommunicator == null) {
            doorLockCommunicator = DoorLockCommunicator.getInstance(doorLockSerialPort);
        }
        return doorLockCommunicator;
    }

    /**
     * 获取RFID通信控制器
     *
     * @return RFIDCommunicator实例
     * @throws IOException 初始化失败时抛出
     */
    public RFIDCommunicator getRfidCommunicator() throws IOException {
        if (rfidCommunicator == null) {
            rfidCommunicator = RFIDCommunicator.getInstance(rfidSerialPort);
        }
        return rfidCommunicator;
    }

    public static synchronized BroadcastHelper getBroadcastHelper() {
        try {
            return BroadcastHelper.getInstance();
        } catch (IllegalStateException e) {
            Log.e("MyApplication", "BroadcastHelper not initialized properly", e);
            // Emergency fallback - should never happen if initialized in onCreate
            if (appContext != null) {
                BroadcastHelper.initialize(appContext);
                return BroadcastHelper.getInstance();
            }
            throw new RuntimeException("Application context not available");
        }
    }
}
