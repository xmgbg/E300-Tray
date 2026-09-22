package com.ezhan.amr.navigation.area;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.MapArea;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class MapAreaTriggerManager {
    private static final String TAG = "MapAreaTriggerManager";
    private static final String PREFS_NAME = "map_area_settings";
    private static final String KEY_MAP_AREAS = "map_areas_json";
    private static final Type MAP_AREAS_TYPE = new TypeToken<List<MapArea>>(){}.getType();
    private static final long AREA_TRIGGER_INTERVAL_MS = 2000;

    private final Context context;
    private final DataStoreManager dataStoreManager;
    private final SharedViewModel sharedViewModel;
    private final MapViewModel mapViewModel;
    private final MapAreaDeviceDispatcher deviceDispatcher;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final List<MapArea> mapAreas = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Long> lastTriggerTimeMap = new java.util.HashMap<>();
    private final Gson gson = new Gson();
    /** 上一周期机器人所在的区域 key 集合, 用于 enter/exit 边沿检测 */
    private final Set<String> prevInsideAreas = new HashSet<>();
    /** 交管区域处理器 (可选, 为 null 时交管区域走默认 dispatch 逻辑) */
    private volatile TrafficAreaManager trafficAreaManager;
    private boolean started;
    private boolean listenerRegistered;  // 确保只注册一次

    private final StatusWebSocketClient.StatusListener statusListener = this::onStatusUpdate;

    public MapAreaTriggerManager(Context context,
                                 DataStoreManager dataStoreManager,
                                 SharedViewModel sharedViewModel,
                                 MapViewModel mapViewModel,
                                 MapAreaDeviceDispatcher deviceDispatcher) {
        this.context = context;
        this.dataStoreManager = dataStoreManager;
        this.sharedViewModel = sharedViewModel;
        this.mapViewModel = mapViewModel;
        this.deviceDispatcher = deviceDispatcher;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        loadAreas();  // 在 loadAreas 首次完成后才 registerStatusListener
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        listenerRegistered = false;
        sharedViewModel.unregisterStatusListener(statusListener);
        disposables.clear();
        synchronized (lastTriggerTimeMap) {
            lastTriggerTimeMap.clear();
        }
        Log.d(TAG, "Map area trigger manager stopped");
    }

    public void reloadAreas() {
        loadAreas();
    }

    /** 注入交管区域处理器; 注入后交管区域的 enter/exit 会路由到它而非默认 dispatch */
    public void setTrafficAreaManager(TrafficAreaManager manager) {
        this.trafficAreaManager = manager;
    }

    private void loadAreas() {
        // 使用 SharedPreferences 加载，避免 DataStore 断电丢失问题
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String mapAreaJson = sp.getString(KEY_MAP_AREAS, "");
        
        mapAreas.clear();
        if (!mapAreaJson.isEmpty()) {
            try {
                List<MapArea> loadedAreas = gson.fromJson(mapAreaJson, MAP_AREAS_TYPE);
                if (loadedAreas != null) {
                    mapAreas.addAll(loadedAreas);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load map areas from SharedPreferences", e);
            }
        }
        
        Log.d(TAG, "Loaded map areas: " + mapAreas.size());
        TrafficLog.log("MapAreaTrigger LOAD areas count=" + mapAreas.size());
        
        // 首次加载完成后注册状态监听器 (避免空区域列表导致边沿检测失效)
        if (started && !listenerRegistered) {
            listenerRegistered = true;
            sharedViewModel.registerStatusListener(statusListener);
            Log.d(TAG, "Map area trigger manager started");
            TrafficLog.log("MapAreaTrigger STARTED + status listener registered");
        }
    }

    private void onStatusUpdate(AgvStatusResponse response) {
        if (!started || response == null || response.data == null || response.data.pos == null) {
            return;
        }

        Position currentPosition = createPositionFromStatus(response);
        if (currentPosition == null) {
            return;
        }

        handleRobotPosition(currentPosition);
    }

    private Position createPositionFromStatus(AgvStatusResponse status) {
        Position position = new Position(1, "current",
                status.data.pos.x,
                status.data.pos.y,
                status.data.pos.theta);

        String statusMapName = status.data.pos.mapName;
        if (statusMapName != null && !statusMapName.trim().isEmpty()) {
            position.setMapName(statusMapName.trim());
        } else if (mapViewModel != null) {
            int currentFloor = mapViewModel.getCurrentFloor();
            String currentMap = mapViewModel.getCurrentMap();
            String currentBuilding = mapViewModel.getCurrentBuilding();
            position.setMapName(MultiBuildingMapPoints.generateMapName(
                    currentMap, currentBuilding, currentFloor));
            position.setFloor(String.valueOf(currentFloor));
        }

        if (position.getFloor() == null && mapViewModel != null) {
            position.setFloor(String.valueOf(mapViewModel.getCurrentFloor()));
        }

        return position;
    }

    private void handleRobotPosition(Position position) {
        Set<String> currentlyInside = new HashSet<>();
        java.util.Map<String, MapArea> insideAreaMap = new java.util.HashMap<>();

        if (mapAreas.isEmpty()) {
            return;
        }
        // 调试: 机器人位置与区域地图名匹配情况 (仅当存在交管区域时输出)
        boolean hasTraffic = false;
        String posMapName = position.getMapName();
        for (MapArea a : mapAreas) {
            if (isTrafficArea(a)) { hasTraffic = true; break; }
        }
        if (hasTraffic) {
            android.util.Log.d(TAG, "handleRobotPosition: posMap='" + posMapName
                    + "', x=" + position.getPosX() + ", y=" + position.getPosY()
                    + ", areas=" + mapAreas.size());
            for (MapArea a : mapAreas) {
                if (isTrafficArea(a)) {
                    android.util.Log.d(TAG, "  traffic area '" + a.getName()
                            + "' map='" + a.getMapName() + "'"
                            + " sameMap=" + isSameMap(position, a)
                            + " usable=" + isAreaUsable(a));
                }
            }
        }

        for (MapArea area : mapAreas) {
            if (!isAreaUsable(area) || !isSameMap(position, area)) {
                continue;
            }

            boolean inside = PolygonUtils.pointInPolygon(
                    position.getPosX(),
                    position.getPosY(),
                    area.getPolygonPoints(),
                    area.getExpand());

            String areaKey = buildAreaKey(area);
            if (inside) {
                currentlyInside.add(areaKey);
                insideAreaMap.put(areaKey, area);
                handleAreaInside(area, areaKey);
            }
        }

        // enter/exit 边沿检测 (仅对交管区域, 门控/信息区域走原有 2s 重触发)
        if (trafficAreaManager != null) {
            // 新进入的区域
            for (String key : currentlyInside) {
                if (!prevInsideAreas.contains(key)) {
                    MapArea area = insideAreaMap.get(key);
                    if (isTrafficArea(area)) {
                        Log.d(TAG, "Enter traffic area: " + area.getName());
                        TrafficLog.log("MapAreaTrigger ENTER traffic area: " + area.getName()
                                + " map=" + area.getMapName() + " pos.x=" + position.getPosX()
                                + " pos.y=" + position.getPosY());
                        trafficAreaManager.onEnterTrafficArea(area);
                    }
                }
            }
            // 离开的区域
            for (String key : prevInsideAreas) {
                if (!currentlyInside.contains(key)) {
                    MapArea area = findAreaByKey(key);
                    if (area != null && isTrafficArea(area)) {
                        Log.d(TAG, "Exit traffic area: " + area.getName());
                        TrafficLog.log("MapAreaTrigger EXIT traffic area: " + area.getName());
                        trafficAreaManager.onExitTrafficArea(area);
                    }
                }
            }
        }

        prevInsideAreas.clear();
        prevInsideAreas.addAll(currentlyInside);

        synchronized (lastTriggerTimeMap) {
            lastTriggerTimeMap.keySet().retainAll(currentlyInside);
        }
    }

    /** 判断是否为交管区域 */
    private static boolean isTrafficArea(MapArea area) {
        return area != null && area.getType() != null && area.getType().contains("交管");
    }

    /** 根据 areaKey 反查 MapArea (用于 exit 事件) */
    private MapArea findAreaByKey(String key) {
        for (MapArea area : mapAreas) {
            if (key.equals(buildAreaKey(area))) {
                return area;
            }
        }
        return null;
    }

    private void handleAreaInside(MapArea area, String areaKey) {
        // 交管区域由 TrafficAreaManager 状态机处理, 不走 deviceDispatcher 的 2s 重触发
        if (trafficAreaManager != null && isTrafficArea(area)) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lastTriggerTimeMap) {
            Long lastTriggerTime = lastTriggerTimeMap.get(areaKey);
            if (lastTriggerTime != null && now - lastTriggerTime < AREA_TRIGGER_INTERVAL_MS) {
                return;
            }

            lastTriggerTimeMap.put(areaKey, now);
        }

        Log.d(TAG, "Robot inside map area, dispatching device request: "
                + area.getName() + ", type=" + area.getType());
        deviceDispatcher.dispatch(area);
    }

    private static boolean isAreaUsable(MapArea area) {
        return area != null
                && area.getMapName() != null
                && area.getPolygonPoints() != null
                && area.getPolygonPoints().size() >= 6;
    }

    private static boolean isSameMap(Position position, MapArea area) {
        String positionMapName = position.getMapName();
        String areaMapName = area.getMapName();
        return positionMapName != null
                && areaMapName != null
                && positionMapName.trim().equals(areaMapName.trim());
    }

    private static String buildAreaKey(MapArea area) {
        return area.getMapName() + "_" + area.getName();
    }
}
