package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datastore.DataStoreKeys;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.task.NavigationElevatorManager;
import com.ezhan.amr.navigation.task.NavigationStateDebugger;
import com.google.gson.Gson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class ElevatorViewModel extends AndroidViewModel {
    private static final String TAG = "ElevatorViewModel";
    private static final String DEBUG_TAG = "测试一"; // 用于日志过滤排查数据丢失

    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Gson gson = new Gson();

    // Reference to MapViewModel for updating map points
    private final MapViewModel mapViewModel;
    private final BasicViewModel basicViewModel;

    // Elevator configs
    public final MutableLiveData<List<ElevatorConfig>> elevatorConfigs = new MutableLiveData<>(new ArrayList<>());

    private static NavigationStateDebugger debugger;
    private boolean isUpdatingFromMapPoints = false;

    // ============ ELEVATOR OCCUPANCY CONFIGURATION ============
    /**
     * The floor number that should always use the first elevator (index 0)
     * Default: 4
     */
    public static final int FIXED_ELEVATOR_FLOOR = 4;

    /**
     * The elevator index to use for the fixed floor (0-based index)
     * Default: 0 (first elevator)
     */
    public static final int FIXED_ELEVATOR_INDEX = 0;

    /**
     * Cache for elevator occupancy status
     * Key: elevatorConfigId, Value: true if NOT occupied (available), false if occupied
     */
    private final ConcurrentHashMap<String, Boolean> occupancyCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> occupancyCacheTime = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY_MS = 5000; // 5 seconds cache expiry
    private NavigationElevatorManager elevatorManager;

    // Add this flag
    private boolean isProcessingConfigs = false;
    private long lastConfigUpdateTime = 0;
    private static final long CONFIG_UPDATE_COOLDOWN_MS = 2000; // 2 seconds

    public ElevatorViewModel(@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        basicViewModel = MyApplication.getInstance().getBasicViewModel();
        elevatorManager = NavigationElevatorManager.getInstance();
        debugger = NavigationStateDebugger.getInstance();
        loadAllPersistedData();

        // Observe map points changes to sync with elevator configs
        observeMapPoints();
    }

    private void loadAllPersistedData() {
        Log.d(DEBUG_TAG, "[电梯数据加载] 开始加载持久化数据");
        disposables.add(dataStoreManager.getString(DataStoreKeys.ELEVATOR_CONFIGS, "")
                .subscribe(json -> {
                    if (!json.isEmpty()) {
                        Log.d(DEBUG_TAG, "[电梯数据加载] 从DataStore读取到JSON数据，长度: " + json.length());
                        Log.d(DEBUG_TAG, "[电梯数据加载] JSON内容: " + json);
                        Type type = DataStoreKeys.ELEVATOR_CONFIGS_TYPE;
                        List<ElevatorConfig> loadedConfigs = gson.fromJson(json, type);
                        if (loadedConfigs != null) {
                            Log.d(DEBUG_TAG, "[电梯数据加载] 成功解析配置列表，数量: " + loadedConfigs.size());
                            for (int i = 0; i < loadedConfigs.size(); i++) {
                                ElevatorConfig config = loadedConfigs.get(i);
                                Log.d(DEBUG_TAG, "[电梯数据加载] 配置[" + i + "]: id=" + config.getId()
                                        + ", channel=" + config.getChannel()
                                        + ", address=" + config.getAddress()
                                        + ", buildingId=" + config.getBuildingId()
                                        + ", 楼层数=" + config.getFloors().size());
                                for (int j = 0; j < config.getFloors().size(); j++) {
                                    ElevatorFloor floor = config.getFloors().get(j);
                                    Log.d(DEBUG_TAG, "[电梯数据加载]   楼层[" + j + "]: floor=" + floor.getFloor()
                                            + ", alias=" + floor.getAlias()
                                            + ", hasWaitPoint=" + (floor.getWaitPoint() != null)
                                            + ", hasPreridePoint=" + (floor.getPreridePoint() != null)
                                            + ", hasRidePoint=" + (floor.getRidePoint() != null)
                                            + ", hasTransitionPoint=" + (floor.getTransitionPoint() != null));
                                }
                            }
                            // Find the maximum ID from loaded configs
                            int maxId = loadedConfigs.stream()
                                    .mapToInt(config -> Integer.parseInt(config.getId()))
                                    .max()
                                    .orElse(0);
                            // Set the next ID to be maxId + 1
                            ElevatorConfig.nextId.set(maxId + 1);
                            Log.d(DEBUG_TAG, "[电梯数据加载] 设置nextId为: " + (maxId + 1));
                            elevatorConfigs.postValue(loadedConfigs);
                            Log.d(DEBUG_TAG, "[电梯数据加载] 已postValue到LiveData");

                            // Sync elevator points with map points
                            syncElevatorPointsWithMapPoints();
                            Log.d(DEBUG_TAG, "[电梯数据加载] 已同步电梯点位到地图点位");
                        } else {
                            Log.w(DEBUG_TAG, "[电梯数据加载] JSON解析结果为null");
                        }
                    } else {
                        Log.d(DEBUG_TAG, "[电梯数据加载] DataStore中无数据（JSON为空）");
                    }
                }, throwable -> {
                    Log.e(DEBUG_TAG, "[电梯数据加载] 加载电梯配置失败", throwable);
                }));
    }

    private void observeMapPoints() {
        mapViewModel.getCurrentMapPoints().observeForever(mapPoints -> {
            if (mapPoints != null) {
                // Don't update if we're in the middle of processing configs
                if (isProcessingConfigs) {
                    Log.d(TAG, "observeMapPoints: Skipping - currently processing configs");
                    return;
                }

                // Don't update if configs were recently updated (cooldown)
                long now = System.currentTimeMillis();
                if (now - lastConfigUpdateTime < CONFIG_UPDATE_COOLDOWN_MS) {
                    Log.d(TAG, "observeMapPoints: Skipping - cooldown period");
                    return;
                }

                Log.d(DEBUG_TAG, "[数据同步] observeMapPoints触发, 当前configs数量: " +
                        (elevatorConfigs.getValue() != null ? elevatorConfigs.getValue().size() : 0));

                // When map points change, update elevator configs with the latest point data
                updateElevatorPointsFromMapPoints(mapPoints);
            }
        });
    }

    // Add this method to be called before posting configs
    public void beginConfigUpdate() {
        isProcessingConfigs = true;
        Log.d(TAG, "beginConfigUpdate: Processing started");
    }

    // Add this method to be called after posting configs
    public void endConfigUpdate() {
        isProcessingConfigs = false;
        lastConfigUpdateTime = System.currentTimeMillis();
        Log.d(TAG, "endConfigUpdate: Processing ended, cooldown started");
    }

    private void updateElevatorPointsFromMapPoints(MultiBuildingMapPoints mapPoints) {
        // Prevent circular updates
        if (isUpdatingFromMapPoints) {
            Log.d(DEBUG_TAG, "[数据同步] updateElevatorPointsFromMapPoints: 跳过(正在更新中)");
            return;
        }

        List<ElevatorConfig> configs = elevatorConfigs.getValue();
        if (configs == null || configs.isEmpty()) {
            Log.d(DEBUG_TAG, "[数据同步] updateElevatorPointsFromMapPoints: 跳过(configs为空)");
            return;
        }

        Log.d(DEBUG_TAG, "[数据同步] updateElevatorPointsFromMapPoints: 开始, configs数量=" + configs.size());
        boolean hasChanges = false;
        isUpdatingFromMapPoints = true;

        try {
            for (ElevatorConfig config : configs) {
                String buildingId = config.getBuildingId();
                if (buildingId == null || buildingId.isEmpty()) {
                    buildingId = "default";
                }

                Building building = mapPoints.getBuilding(buildingId);
                if (building == null) continue;

                for (ElevatorFloor floor : config.getFloors()) {
                    int floorNum = floor.getFloor();
                    FloorPoints floorPoints = building.getFloorPoints(floorNum);

                    if (floorPoints != null) {
                        // Find preride point - NEW
                        Position mapPreridePoint = findElevatorPointByName(floorPoints,
                                "elevator_preride_" + floorNum + "_" + config.getId());
                        if (mapPreridePoint != null) {
                            Position currentPreridePoint = floor.getPreridePoint();
                            if (mapPreridePoint.getPosX() != 0 || mapPreridePoint.getPosY() != 0) {
                                if (!isSamePosition(mapPreridePoint, currentPreridePoint)) {
                                    floor.setPreridePoint(copyPosition(mapPreridePoint));
                                    hasChanges = true;
                                }
                            }
                        }
                        // Find wait point - match by name
                        Position mapWaitPoint = findElevatorPointByName(floorPoints,
                                "elevator_wait_" + floorNum + "_" + config.getId());
                        if (mapWaitPoint != null) {
                            // Only update if the point has valid coordinates (not all zero)
                            // AND the floor's current point is either null or has all zeros
                            Position currentWaitPoint = floor.getWaitPoint();
                            if (mapWaitPoint.getPosX() != 0 || mapWaitPoint.getPosY() != 0) {
                                // Map point has valid coordinates, use it
                                if (!isSamePosition(mapWaitPoint, currentWaitPoint)) {
                                    floor.setWaitPoint(copyPosition(mapWaitPoint));
                                    hasChanges = true;
                                }
                            }
                            // If map point has zeros but current point has valid coordinates,
                            // preserve the current point (don't overwrite with zeros)
                        }

                        // Similarly for ride points
                        Position mapRidePoint = findElevatorPointByName(floorPoints,
                                "elevator_ride_" + floorNum + "_" + config.getId());
                        if (mapRidePoint != null) {
                            Position currentRidePoint = floor.getRidePoint();
                            if (mapRidePoint.getPosX() != 0 || mapRidePoint.getPosY() != 0) {
                                if (!isSamePosition(mapRidePoint, currentRidePoint)) {
                                    floor.setRidePoint(copyPosition(mapRidePoint));
                                    hasChanges = true;
                                }
                            }
                        }

                        // Similarly for transition points
                        Position mapTransitionPoint = findElevatorPointByName(floorPoints,
                                "elevator_transition_" + floorNum + "_" + config.getId());
                        if (mapTransitionPoint != null) {
                            Position currentTransitionPoint = floor.getTransitionPoint();
                            if (mapTransitionPoint.getPosX() != 0 || mapTransitionPoint.getPosY() != 0) {
                                if (!isSamePosition(mapTransitionPoint, currentTransitionPoint)) {
                                    floor.setTransitionPoint(copyPosition(mapTransitionPoint));
                                    hasChanges = true;
                                }
                            }
                        }
                    }
                }
            }

            if (hasChanges) {
                Log.d(DEBUG_TAG, "[数据同步] updateElevatorPointsFromMapPoints: 有变化, postValue并保存, configs数量=" + configs.size());
                elevatorConfigs.postValue(configs);
                saveElevatorConfigs();
            } else {
                Log.d(DEBUG_TAG, "[数据同步] updateElevatorPointsFromMapPoints: 无变化");
            }
        } finally {
            isUpdatingFromMapPoints = false;
        }
    }

    // Add this helper method to find elevator point by exact name
    private Position findElevatorPointByName(FloorPoints floorPoints, String pointName) {
        // Check preride points
        for (Position pos : floorPoints.getElevatorPreridePoints()) {
            if (pointName.equals(pos.getName())) {
                return pos;
            }
        }
        // Check wait points
        for (Position pos : floorPoints.getElevatorWaitPoints()) {
            if (pointName.equals(pos.getName())) {
                return pos;
            }
        }
        // Check ride points
        for (Position pos : floorPoints.getElevatorRidePoints()) {
            if (pointName.equals(pos.getName())) {
                return pos;
            }
        }
        // Check transition points
        for (Position pos : floorPoints.getElevatorTransitionPoints()) {
            if (pointName.equals(pos.getName())) {
                return pos;
            }
        }
        return null;
    }

    private Position findElevatorPointByType(FloorPoints floorPoints, int type, int floor, String configId) {
        List<Position> points;
        String expectedPrefix;

        if (type == 2) {
            points = floorPoints.getElevatorPreridePoints();
            expectedPrefix = "elevator_preride";
        } else if (type == 3) {
            points = floorPoints.getElevatorWaitPoints();
            expectedPrefix = "elevator_wait";
        } else if (type == 4) {
            points = floorPoints.getElevatorRidePoints();
            expectedPrefix = "elevator_ride";
        } else if (type == 14) {
            points = floorPoints.getElevatorTransitionPoints();
            expectedPrefix = "elevator_transition";
        } else {
            return null;
        }

        String expectedName = expectedPrefix + "_" + floor + "_" + configId;
        for (Position pos : points) {
            if (expectedName.equals(pos.getName())) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Check if two positions are the same
     */
    private boolean isSamePosition(Position p1, Position p2) {
        if (p1 == null || p2 == null) return false;
        return Math.abs(p1.getPosX() - p2.getPosX()) < 0.001 &&
                Math.abs(p1.getPosY() - p2.getPosY()) < 0.001 &&
                Math.abs(p1.getYaw() - p2.getYaw()) < 0.001;
    }

    /**
     * Create a copy of a position
     */
    private Position copyPosition(Position original) {
        Position copy = new Position();
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setPosX(original.getPosX());
        copy.setPosY(original.getPosY());
        copy.setYaw(original.getYaw());
        copy.setType(original.getType());
        copy.setFloor(original.getFloor());
        copy.setMapName(original.getMapName());
        copy.setTaskType(original.getTaskType());
        copy.setElevatorInfo(original.getElevatorInfo());
        return copy;
    }

    /**
     * Sync elevator points with map points when loading from storage
     */
    private void syncElevatorPointsWithMapPoints() {
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints != null) {
            updateElevatorPointsFromMapPoints(mapPoints);
        }
    }

    /**
     * Update elevator wait/ride/transition points in currentMapPoints
     */
    private void updateMapPointsWithElevatorPoints(ElevatorConfig config, ElevatorFloor floor) {
        int floorNum = floor.getFloor();
        String buildingId = config.getBuildingId();
        if (buildingId == null || buildingId.isEmpty()) {
            buildingId = "default";
        }

        String currentMap = mapViewModel.getCurrentMap();
        if (currentMap == null || currentMap.isEmpty()) {
            Log.e(TAG, "Cannot update map points: current map is null");
            return;
        }

        // Update preride point (type 2) - 保留其他电梯的点位
        Position preridePoint = floor.getPreridePoint();
        if (preridePoint != null) {
            preridePoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            preridePoint.setType(2);
            preridePoint.setFloor(String.valueOf(floorNum));
            preridePoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floorNum));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 2, preridePoint);
        }

        // Update wait point (type 3) - 保留其他电梯的点位
        Position waitPoint = floor.getWaitPoint();
        if (waitPoint != null) {
            waitPoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            waitPoint.setType(3);
            waitPoint.setFloor(String.valueOf(floorNum));
            waitPoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floorNum));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 3, waitPoint);
        }

        // Update ride point (type 4) - 保留其他电梯的点位
        Position ridePoint = floor.getRidePoint();
        if (ridePoint != null) {
            ridePoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            ridePoint.setType(4);
            ridePoint.setFloor(String.valueOf(floorNum));
            ridePoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floorNum));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 4, ridePoint);
        }

        // Update transition point (type 14) - 保留其他电梯的点位
        Position transitionPoint = floor.getTransitionPoint();
        if (transitionPoint != null) {
            transitionPoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            transitionPoint.setType(14);
            transitionPoint.setFloor(String.valueOf(floorNum));
            transitionPoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floorNum));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 14, transitionPoint);
        }
    }

    /**
     * 更新单个电梯点位，同时保留该楼层其他电梯的点位
     */
    private void updateSingleElevatorPointPreservingOthers(String buildingId, String currentMap, int floorNum,
                                                            int pointType, Position updatedPoint) {
        List<Position> currentPoints;

        switch (pointType) {
            case 2:
                currentPoints = mapViewModel.getElevatorPreridePoints(buildingId, floorNum);
                break;
            case 3:
                currentPoints = mapViewModel.getElevatorWaitPoints(buildingId, floorNum);
                break;
            case 4:
                currentPoints = mapViewModel.getElevatorRidePoints(buildingId, floorNum);
                break;
            case 14:
                currentPoints = mapViewModel.getElevatorTransitionPoints(buildingId, floorNum);
                break;
            default:
                return;
        }

        if (currentPoints == null) {
            currentPoints = new ArrayList<>();
        }

        // 构建更新后的列表：保留其他电梯的点位，只更新当前电梯的点位
        List<Position> updatedPoints = new ArrayList<>();
        boolean found = false;
        String targetName = updatedPoint.getName();

        for (Position point : currentPoints) {
            if (point.getName() != null && point.getName().equals(targetName)) {
                // 替换当前电梯的点位
                updatedPoints.add(updatedPoint);
                found = true;
            } else {
                // 保留其他电梯的点位
                updatedPoints.add(point);
            }
        }

        if (!found) {
            // 如果没找到，说明是新点位，添加进去
            updatedPoints.add(updatedPoint);
        }

        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, pointType, updatedPoints);
    }

    /**
     * Remove elevator points from currentMapPoints for a specific floor
     * 注意：此方法会删除该楼层所有电梯的点位，仅用于删除整个楼层时调用
     */
    private void removeElevatorPointsFromMapPoints(String buildingId, int floor) {
        String currentMap = mapViewModel.getCurrentMap();
        if (currentMap == null || currentMap.isEmpty()) return;

        // Remove preride points (send empty list) - NEW
        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floor, 2, new ArrayList<>());

        // Remove wait points (send empty list)
        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floor, 3, new ArrayList<>());

        // Remove ride points (send empty list)
        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floor, 4, new ArrayList<>());

        // Remove transition points (send empty list)
        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floor, 14, new ArrayList<>());
    }

    /**
     * Helper method to add elevator points for a specific floor to MapPoints
     */
    private void addElevatorPointsToMapPoints(ElevatorConfig config, ElevatorFloor floor) {
        String currentMap = mapViewModel.getCurrentMap();
        String buildingId = config.getBuildingId();
        if (buildingId == null || buildingId.isEmpty()) {
            buildingId = "default";
        }

        if (currentMap == null || currentMap.isEmpty()) {
            Log.e(TAG, "Cannot add elevator points: current map is null");
            return;
        }

        int floorNum = floor.getFloor();
        String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floorNum);

        // Add preride point - 保留其他电梯的点位
        Position preridePoint = floor.getPreridePoint();
        if (preridePoint != null) {
            if (preridePoint.getName() == null || preridePoint.getName().isEmpty()) {
                preridePoint.setName("elevator_preride_" + floorNum + "_" + config.getId());
            }
            preridePoint.setType(2);
            preridePoint.setFloor(String.valueOf(floorNum));
            preridePoint.setMapName(fullMapName);
            preridePoint.setTaskType(0);
            preridePoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 2, preridePoint);
        }

        // Add wait point - 保留其他电梯的点位
        Position waitPoint = floor.getWaitPoint();
        if (waitPoint != null) {
            if (waitPoint.getName() == null || waitPoint.getName().isEmpty()) {
                waitPoint.setName("elevator_wait_" + floorNum + "_" + config.getId());
            }
            waitPoint.setType(3);
            waitPoint.setFloor(String.valueOf(floorNum));
            waitPoint.setMapName(fullMapName);
            waitPoint.setTaskType(0);
            waitPoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 3, waitPoint);
        }

        // Add ride point - 保留其他电梯的点位
        Position ridePoint = floor.getRidePoint();
        if (ridePoint != null) {
            if (ridePoint.getName() == null || ridePoint.getName().isEmpty()) {
                ridePoint.setName("elevator_ride_" + floorNum + "_" + config.getId());
            }
            ridePoint.setType(4);
            ridePoint.setFloor(String.valueOf(floorNum));
            ridePoint.setMapName(fullMapName);
            ridePoint.setTaskType(0);
            ridePoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 4, ridePoint);
        }

        // Add transition point - 保留其他电梯的点位
        Position transitionPoint = floor.getTransitionPoint();
        if (transitionPoint != null) {
            if (transitionPoint.getName() == null || transitionPoint.getName().isEmpty()) {
                transitionPoint.setName("elevator_transition_" + floorNum + "_" + config.getId());
            }
            transitionPoint.setType(14);
            transitionPoint.setFloor(String.valueOf(floorNum));
            transitionPoint.setMapName(fullMapName);
            transitionPoint.setTaskType(0);
            transitionPoint.setElevatorInfo(new Position.ElevatorInfo(config.getChannel(), config.getAddress(), config.getId()));
            updateSingleElevatorPointPreservingOthers(buildingId, currentMap, floorNum, 14, transitionPoint);
        }
    }

    /**
     * Update position type if near elevator special points
     */
    public Position updatePositionTypeIfNearSpecialPoints(Position currentPosition) {
        int currentFloor = mapViewModel.getCurrentFloor();
        String currentBuilding = mapViewModel.getCurrentBuilding();

        List<Position> preridePointsList = mapViewModel.getElevatorPreridePoints(currentBuilding, currentFloor);
        List<Position> waitPointsList = mapViewModel.getElevatorWaitPoints(currentBuilding, currentFloor);
        List<Position> ridePointsList = mapViewModel.getElevatorRidePoints(currentBuilding, currentFloor);
        List<Position> transitionPointsList = mapViewModel.getElevatorTransitionPoints(currentBuilding, currentFloor);

        final double DISTANCE_THRESHOLD = 0.3; // meters

        // Check against preride points (type 2)
        if (preridePointsList != null) {
            for (Position preridePoint : preridePointsList) {
                if (currentPosition.getFloor().equals(preridePoint.getFloor()) &&
                        calculateDistance(currentPosition, preridePoint) <= DISTANCE_THRESHOLD) {
                    currentPosition.setType(2);
                    currentPosition.setName(preridePoint.getName());
                    currentPosition.setElevatorInfo(preridePoint.getElevatorInfo());
                    currentPosition.setBuildingId(preridePoint.getBuildingId());
                    Log.d("WaitRidePositionTypeUpdate", "Updated position type to 2 (preride point)");
                    break;
                }
            }
        }

        // Check against wait points (type 3)
        if (waitPointsList != null) {
            for (Position waitPoint : waitPointsList) {
                if (currentPosition.getFloor().equals(waitPoint.getFloor()) &&
                        calculateDistance(currentPosition, waitPoint) <= DISTANCE_THRESHOLD) {
                    currentPosition.setType(3);
                    Log.d("WaitRidePositionTypeUpdate", "Updated position type to 3 (wait point)");
                    break;
                }
            }
        }

        // Check against ride points (type 4)
        if (ridePointsList != null) {
            for (Position ridePoint : ridePointsList) {
                if (currentPosition.getFloor().equals(ridePoint.getFloor()) &&
                        calculateDistance(currentPosition, ridePoint) <= DISTANCE_THRESHOLD) {
                    currentPosition.setType(4);
                    Log.d("WaitRidePositionTypeUpdate", "Updated position type to 4 (ride point)");
                    break;
                }
            }
        }

        // Check against transition points (type 14)
        if (transitionPointsList != null) {
            for (Position transitionPoint : transitionPointsList) {
                if (currentPosition.getFloor().equals(transitionPoint.getFloor()) &&
                        calculateDistance(currentPosition, transitionPoint) <= DISTANCE_THRESHOLD) {
                    currentPosition.setType(14);
                    Log.d("WaitRidePositionTypeUpdate", "Updated position type to 14 (transition point)");
                    break;
                }
            }
        }

        return currentPosition;
    }

    private double calculateDistance(Position p1, Position p2) {
        double dx = p1.getPosX() - p2.getPosX();
        double dy = p1.getPosY() - p2.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    // --------------------------------------------------------------------------------------------
    // Public Methods for Config Management

    public LiveData<List<ElevatorConfig>> getElevatorConfigs() {
        return elevatorConfigs;
    }

    public void addElevatorConfig(ElevatorConfig config) {
        Log.d(DEBUG_TAG, "[电梯配置操作] addElevatorConfig 被调用，configId=" + config.getId()
                + ", channel=" + config.getChannel()
                + ", address=" + config.getAddress()
                + ", buildingId=" + config.getBuildingId()
                + ", 楼层数=" + config.getFloors().size());
        List<ElevatorConfig> current = elevatorConfigs.getValue();
        if (current != null) {
            Log.d(DEBUG_TAG, "[电梯配置操作] 当前配置数量: " + current.size());
            // Set the elevator config ID on each floor
            for (ElevatorFloor floor : config.getFloors()) {
                floor.setElevatorConfigId(config.getId());
                floor.setBuildingId(config.getBuildingId());
            }

            current.add(config);
            Log.d(DEBUG_TAG, "[电梯配置操作] 添加后配置数量: " + current.size());
            elevatorConfigs.postValue(current);
            saveElevatorConfigs();
            Log.d(DEBUG_TAG, "[电梯配置操作] addElevatorConfig 完成");
        } else {
            Log.w(DEBUG_TAG, "[电梯配置操作] addElevatorConfig: current为null");
        }
    }

    public void updateElevatorConfig(ElevatorConfig config) {
        Log.d(DEBUG_TAG, "[配置操作] updateElevatorConfig: id=" + config.getId()
                + ", channel=" + config.getChannel() + ", address=" + config.getAddress()
                + ", 楼层数=" + (config.getFloors() != null ? config.getFloors().size() : 0));
        List<ElevatorConfig> current = elevatorConfigs.getValue();
        if (current != null) {
            Log.d(DEBUG_TAG, "[配置操作] updateElevatorConfig: 当前configs数量=" + current.size());
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).getId().equals(config.getId())) {
                    Log.d(DEBUG_TAG, "[配置操作] updateElevatorConfig: 找到匹配config在index=" + i);
                    // Preserve the original config's floors if they're being overwritten
                    ElevatorConfig existingConfig = current.get(i);

                    // If the new config has no floors but the existing one does, preserve them
                    if ((config.getFloors() == null || config.getFloors().isEmpty())
                            && existingConfig.getFloors() != null && !existingConfig.getFloors().isEmpty()) {
                        config.setFloors(existingConfig.getFloors());
                    }

                    // Ensure buildingId is preserved
                    if (config.getBuildingId() == null || config.getBuildingId().isEmpty()) {
                        config.setBuildingId(existingConfig.getBuildingId());
                    }

                    // Ensure each floor has the correct config ID and building ID
                    for (ElevatorFloor floor : config.getFloors()) {
                        if (floor.getElevatorConfigId() == null) {
                            floor.setElevatorConfigId(config.getId());
                        }
                        if (floor.getBuildingId() == null) {
                            floor.setBuildingId(config.getBuildingId());
                        }
                    }

                    current.set(i, config);
                    break;
                }
            }
            elevatorConfigs.postValue(current);
            saveElevatorConfigs();
        }
    }

    public void deleteElevatorConfig(String configId) {
        Log.d(DEBUG_TAG, "[配置操作] deleteElevatorConfig: configId=" + configId);
        List<ElevatorConfig> current = elevatorConfigs.getValue();
        if (current != null) {
            Log.d(DEBUG_TAG, "[配置操作] deleteElevatorConfig: 当前configs数量=" + current.size());
            // Find the config to be deleted
            ElevatorConfig configToDelete = null;
            for (ElevatorConfig config : current) {
                if (config.getId().equals(configId)) {
                    configToDelete = config;
                    break;
                }
            }

            if (configToDelete != null) {
                String buildingId = configToDelete.getBuildingId();
                if (buildingId == null) buildingId = "default";
                Log.d(DEBUG_TAG, "[配置操作] deleteElevatorConfig: 删除点位, buildingId=" + buildingId
                        + ", 楼层数=" + configToDelete.getFloors().size());
                // Remove elevator points for all floors in this config
                for (ElevatorFloor floor : configToDelete.getFloors()) {
                    removeElevatorPointsFromMapPoints(buildingId, floor.getFloor());
                }
            }

            current.removeIf(config -> config.getId().equals(configId));
            Log.d(DEBUG_TAG, "[配置操作] deleteElevatorConfig: 删除后configs数量=" + current.size());
            elevatorConfigs.postValue(current);
            saveElevatorConfigs();
        }
    }

    public void updateElevatorFloor(ElevatorFloor floor) {
        Log.d(DEBUG_TAG, "[楼层操作] updateElevatorFloor: floorId=" + floor.getId()
                + ", floor=" + floor.getFloor() + ", configId=" + floor.getElevatorConfigId());
        List<ElevatorConfig> current = elevatorConfigs.getValue();
        if (current != null) {
            for (ElevatorConfig config : current) {
                for (int i = 0; i < config.getFloors().size(); i++) {
                    if (config.getFloors().get(i).getId().equals(floor.getId())) {
                        Log.d(DEBUG_TAG, "[楼层操作] updateElevatorFloor: 匹配configId=" + config.getId() + ", floorIndex=" + i);
                        ElevatorFloor oldFloor = config.getFloors().get(i);
                        config.getFloors().set(i, floor);

                        // Update map points for this floor
                        updateMapPointsWithElevatorPoints(config, floor);

                        // If floor number changed, remove old floor's points
                        if (oldFloor.getFloor() != floor.getFloor()) {
                            String buildingId = config.getBuildingId();
                            if (buildingId == null) buildingId = "default";
                            removeElevatorPointsFromMapPoints(buildingId, oldFloor.getFloor());
                        }

                        break;
                    }
                }
            }
            elevatorConfigs.postValue(current);
            saveElevatorConfigs();
        }
    }

    public void deleteElevatorFloor(String floorId) {
        Log.d(DEBUG_TAG, "[楼层操作] deleteElevatorFloor: floorId=" + floorId);
        List<ElevatorConfig> current = elevatorConfigs.getValue();
        if (current != null) {
            for (ElevatorConfig config : current) {
                // Find the floor to delete
                ElevatorFloor floorToDelete = null;
                for (ElevatorFloor floor : config.getFloors()) {
                    if (floor.getId().equals(floorId)) {
                        floorToDelete = floor;
                        break;
                    }
                }

                if (floorToDelete != null) {
                    String buildingId = config.getBuildingId();
                    if (buildingId == null) buildingId = "default";
                    Log.d(DEBUG_TAG, "[楼层操作] deleteElevatorFloor: 删除floor=" + floorToDelete.getFloor()
                            + ", buildingId=" + buildingId);
                    // Remove elevator points for this floor
                    removeElevatorPointsFromMapPoints(buildingId, floorToDelete.getFloor());
                }

                int beforeSize = config.getFloors().size();
                config.getFloors().removeIf(floor -> floor.getId().equals(floorId));
                Log.d(DEBUG_TAG, "[楼层操作] deleteElevatorFloor: 楼层删除前后=" + beforeSize + "->" + config.getFloors().size());
            }
            elevatorConfigs.postValue(current);
            saveElevatorConfigs();
        }
    }

    public void addElevatorFloorAfter(String afterFloorId, ElevatorFloor newFloor) {
        Log.d(DEBUG_TAG, "[楼层操作] addElevatorFloorAfter: afterFloorId=" + afterFloorId
                + ", newFloor=" + newFloor.getFloor());
        List<ElevatorConfig> current = elevatorConfigs.getValue();
        if (current != null) {
            for (ElevatorConfig config : current) {
                // Find the floor with the given ID
                int index = -1;
                for (int i = 0; i < config.getFloors().size(); i++) {
                    if (config.getFloors().get(i).getId().equals(afterFloorId)) {
                        index = i;
                        break;
                    }
                }

                if (index != -1) {
                    // Set the elevator config ID on the new floor
                    newFloor.setElevatorConfigId(config.getId());
                    newFloor.setBuildingId(config.getBuildingId());

                    // Insert the new floor after the found floor
                    config.getFloors().add(index + 1, newFloor);
                    Log.d(DEBUG_TAG, "[楼层操作] addElevatorFloorAfter: 插入成功, configId=" + config.getId()
                            + ", 楼层数=" + config.getFloors().size());

                    // Add elevator points for the new floor to MapPoints
                    addElevatorPointsToMapPoints(config, newFloor);

                    Log.d(TAG, "Added new floor after floor ID: " + afterFloorId +
                            ", new floor number: " + newFloor.getFloor() +
                            ", config ID: " + config.getId());
                    break;
                }
            }

            // Update LiveData and save
            elevatorConfigs.postValue(current);
            saveElevatorConfigs();
        }
    }

    public LiveData<ElevatorConfig> getElevatorConfigById(String id) {
        return Transformations.map(elevatorConfigs, configList -> {
            if (configList == null) return null;
            for (ElevatorConfig config : configList) {
                if (id.equals(config.getId())) {
                    return config;
                }
            }
            return null;
        });
    }

    /**
     * Get elevator preride points for a specific floor from currentMapPoints (Multi-building)
     */
    public LiveData<List<Position>> getElevatorPreridePointsForFloor(String buildingId, int floor) {
        MutableLiveData<List<Position>> liveData = new MutableLiveData<>();

        mapViewModel.getCurrentMapPoints().observeForever(mapPoints -> {
            if (mapPoints != null) {
                Building building = mapPoints.getBuilding(buildingId);
                if (building != null) {
                    FloorPoints floorPoints = building.getFloorPoints(floor);
                    if (floorPoints != null) {
                        liveData.postValue(floorPoints.getElevatorPreridePoints());
                    } else {
                        liveData.postValue(new ArrayList<>());
                    }
                } else {
                    liveData.postValue(new ArrayList<>());
                }
            }
        });

        return liveData;
    }

    /**
     * Get elevator wait points for a specific floor from currentMapPoints (Multi-building)
     */
    public LiveData<List<Position>> getElevatorWaitPointsForFloor(String buildingId, int floor) {
        MutableLiveData<List<Position>> liveData = new MutableLiveData<>();

        mapViewModel.getCurrentMapPoints().observeForever(mapPoints -> {
            if (mapPoints != null) {
                Building building = mapPoints.getBuilding(buildingId);
                if (building != null) {
                    FloorPoints floorPoints = building.getFloorPoints(floor);
                    if (floorPoints != null) {
                        liveData.postValue(floorPoints.getElevatorWaitPoints());
                    } else {
                        liveData.postValue(new ArrayList<>());
                    }
                } else {
                    liveData.postValue(new ArrayList<>());
                }
            }
        });

        return liveData;
    }

    /**
     * Get elevator ride points for a specific floor from currentMapPoints (Multi-building)
     */
    public LiveData<List<Position>> getElevatorRidePointsForFloor(String buildingId, int floor) {
        MutableLiveData<List<Position>> liveData = new MutableLiveData<>();

        mapViewModel.getCurrentMapPoints().observeForever(mapPoints -> {
            if (mapPoints != null) {
                Building building = mapPoints.getBuilding(buildingId);
                if (building != null) {
                    FloorPoints floorPoints = building.getFloorPoints(floor);
                    if (floorPoints != null) {
                        liveData.postValue(floorPoints.getElevatorRidePoints());
                    } else {
                        liveData.postValue(new ArrayList<>());
                    }
                } else {
                    liveData.postValue(new ArrayList<>());
                }
            }
        });

        return liveData;
    }

    /**
     * Get elevator transition points for a specific floor from currentMapPoints (Multi-building)
     */
    public LiveData<List<Position>> getElevatorTransitionPointsForFloor(String buildingId, int floor) {
        MutableLiveData<List<Position>> liveData = new MutableLiveData<>();

        mapViewModel.getCurrentMapPoints().observeForever(mapPoints -> {
            if (mapPoints != null) {
                Building building = mapPoints.getBuilding(buildingId);
                if (building != null) {
                    FloorPoints floorPoints = building.getFloorPoints(floor);
                    if (floorPoints != null) {
                        liveData.postValue(floorPoints.getElevatorTransitionPoints());
                    } else {
                        liveData.postValue(new ArrayList<>());
                    }
                } else {
                    liveData.postValue(new ArrayList<>());
                }
            }
        });

        return liveData;
    }

    /**
     * Get all floors that belong to a specific elevator configuration
     */
    public List<ElevatorFloor> getFloorsByElevatorConfigId(String configId) {
        List<ElevatorConfig> configs = elevatorConfigs.getValue();
        if (configs != null) {
            for (ElevatorConfig config : configs) {
                if (config.getId().equals(configId)) {
                    return config.getFloors();
                }
            }
        }
        return new ArrayList<>();
    }

    /**
     * Get all floors that belong to a specific elevator configuration (as LiveData)
     */
    public LiveData<List<ElevatorFloor>> getFloorsByElevatorConfigIdLive(String configId) {
        MutableLiveData<List<ElevatorFloor>> floorsLiveData = new MutableLiveData<>();

        getElevatorConfigById(configId).observeForever(config -> {
            if (config != null) {
                floorsLiveData.postValue(config.getFloors());
            } else {
                floorsLiveData.postValue(new ArrayList<>());
            }
        });

        return floorsLiveData;
    }

    /**
     * Sync all current elevator configs to map points.
     * This ensures the map points reflect the elevator configs,
     * preventing data loss when the fragment rebuilds configs from map points.
     */
    public void syncAllConfigsToMapPoints() {
        syncAllConfigsToMapPoints(elevatorConfigs.getValue());
    }

    /**
     * Sync the given elevator configs to map points.
     * Use this overload when you have the configs list directly (e.g. right after postValue),
     * to avoid the async delay of LiveData.
     */
    public void syncAllConfigsToMapPoints(List<ElevatorConfig> configs) {
        if (configs == null || configs.isEmpty()) return;

        for (ElevatorConfig config : configs) {
            for (ElevatorFloor floor : config.getFloors()) {
                addElevatorPointsToMapPoints(config, floor);
            }
        }
    }

    // Save elevator configs to DataStore
    public void saveElevatorConfigs() {
        List<ElevatorConfig> configs = elevatorConfigs.getValue();
        if (configs != null) {
            Log.d(DEBUG_TAG, "[电梯数据保存] 开始保存配置，数量: " + configs.size());
            for (int i = 0; i < configs.size(); i++) {
                ElevatorConfig config = configs.get(i);
                Log.d(DEBUG_TAG, "[电梯数据保存] 配置[" + i + "]: id=" + config.getId()
                        + ", channel=" + config.getChannel()
                        + ", address=" + config.getAddress()
                        + ", buildingId=" + config.getBuildingId()
                        + ", 楼层数=" + config.getFloors().size());
                for (int j = 0; j < config.getFloors().size(); j++) {
                    ElevatorFloor floor = config.getFloors().get(j);
                    Log.d(DEBUG_TAG, "[电梯数据保存]   楼层[" + j + "]: floor=" + floor.getFloor()
                            + ", alias=" + floor.getAlias()
                            + ", hasWaitPoint=" + (floor.getWaitPoint() != null && floor.getWaitPoint().getPosX() != 0)
                            + ", hasPreridePoint=" + (floor.getPreridePoint() != null && floor.getPreridePoint().getPosX() != 0)
                            + ", hasRidePoint=" + (floor.getRidePoint() != null && floor.getRidePoint().getPosX() != 0)
                            + ", hasTransitionPoint=" + (floor.getTransitionPoint() != null && floor.getTransitionPoint().getPosX() != 0));
                }
            }
            String json = gson.toJson(configs, DataStoreKeys.ELEVATOR_CONFIGS_TYPE);
            Log.d(DEBUG_TAG, "[电梯数据保存] JSON序列化完成，长度: " + json.length());
            // 使用同步写入，防止断电数据丢失
            try {
                dataStoreManager.putStringBlocking(DataStoreKeys.ELEVATOR_CONFIGS, json);
                Log.d(DEBUG_TAG, "[电梯数据保存] DataStore写入成功");
            } catch (Exception e) {
                Log.e(DEBUG_TAG, "[电梯数据保存] 保存失败", e);
            }
        } else {
            Log.w(DEBUG_TAG, "[电梯数据保存] configs为null，跳过保存");
        }
    }

    public List<ElevatorConfig> getElevatorConfigsForBuilding(String buildingId) {
        debugger.log(String.format("getElevatorConfigsForBuilding: buildingId=%s", buildingId));

        List<ElevatorConfig> result = new ArrayList<>();
        List<ElevatorConfig> configs = elevatorConfigs.getValue();

        if (configs == null) {
            debugger.logWarning("getElevatorConfigsForBuilding", "elevatorConfigs is null");
            return result;
        }

        if (buildingId == null) {
            debugger.logWarning("getElevatorConfigsForBuilding", "buildingId is null");
            return result;
        }

        debugger.log(String.format("getElevatorConfigsForBuilding: Total configs=%d", configs.size()));

        for (ElevatorConfig config : configs) {
            String configBuilding = config.getBuildingId();
            if (configBuilding == null) configBuilding = "default";

            if (configBuilding.equals(buildingId)) {
                result.add(config);
                debugger.log(String.format("getElevatorConfigsForBuilding: Found matching config ID=%s, building=%s, channel=%d, address=%d",
                        config.getId(), configBuilding, config.getChannel(), config.getAddress()));
            }
        }

        debugger.log(String.format("getElevatorConfigsForBuilding: Found %d configs for building '%s'",
                result.size(), buildingId));

        return result;
    }

    /**
     * Get elevator config by index from building configs
     */
    public ElevatorConfig getElevatorConfigByIndex(List<ElevatorConfig> buildingConfigs, int index) {
        debugger.log(String.format("getElevatorConfigByIndex: index=%d, buildingConfigs size=%s",
                index, buildingConfigs != null ? buildingConfigs.size() : "null"));

        if (buildingConfigs == null) {
            debugger.logWarning("getElevatorConfigByIndex", "buildingConfigs is null");
            return null;
        }

        if (buildingConfigs.isEmpty()) {
            debugger.logWarning("getElevatorConfigByIndex", "buildingConfigs is empty");
            return null;
        }

        if (index < 0 || index >= buildingConfigs.size()) {
            debugger.logWarning("getElevatorConfigByIndex",
                    String.format("Index %d out of bounds (size=%d), using first (index 0)", index, buildingConfigs.size()));
            ElevatorConfig result = buildingConfigs.get(0);
            debugger.log(String.format("getElevatorConfigByIndex: Returning first config ID=%s (fallback)", result.getId()));
            return result;
        }

        ElevatorConfig result = buildingConfigs.get(index);
        debugger.log(String.format("getElevatorConfigByIndex: Returning config at index %d: ID=%s, channel=%d, address=%d",
                index, result.getId(), result.getChannel(), result.getAddress()));

        return result;
    }

    public Position findPositionWithFloorAndElevatorSelection(
            List<Position> positions,
            String fromFloor,
            String toFloor,
            String buildingId,
            String elevatorId,
            boolean isTo) {

        if (positions == null || buildingId == null) {
            debugger.logWarning("findPositionWithFloorAndElevatorSelection",
                    "positions or buildingId is null");
            return null;
        }

        // Determine which floor to use for elevator selection AND matching
        // When isTo=false, we're looking for positions on fromFloor
        // When isTo=true, we're looking for positions on toFloor
        String targetFloor = isTo ? toFloor : fromFloor;

        int targetFloorNum;
        try {
            targetFloorNum = Integer.parseInt(targetFloor);
        } catch (NumberFormatException e) {
            debugger.logError("findPositionWithFloorAndElevatorSelection",
                    String.format("Invalid target floor number: %s", targetFloor));
            return null;
        }

        debugger.log(String.format("findPositionWithFloorAndElevatorSelection: fromFloor=%s, toFloor=%s, buildingId=%s, elevatorId=%s, isTo=%b, targetFloor=%s",
                fromFloor, toFloor, buildingId, elevatorId, isTo, targetFloor));

        // Get matching positions on the target floor
        List<Position> matchingPositions = new ArrayList<>();
        for (Position pos : positions) {
            if (pos.getFloor() != null && pos.getFloor().equals(targetFloor)) {
                String posBuilding = extractBuildingId(pos.getMapName());
                if (buildingId.equals(posBuilding)) {
                    matchingPositions.add(pos);
                }
            }
        }

        if (matchingPositions.isEmpty()) {
            debugger.logWarning("findPositionWithFloorAndElevatorSelection",
                    String.format("No matching positions for floor %s in building %s", targetFloor, buildingId));
            return null;
        }

        // Ensure elevator manager is initialized
        if (!elevatorManager.isSchedulerRunning()) {
            debugger.logWarning("findPositionWithFloorAndElevatorSelection",
                    "Elevator manager not running, initializing...");
            elevatorManager.initElevatorManager();
        }

        // Check if the selection floor is the fixed floor
        if (targetFloorNum == FIXED_ELEVATOR_FLOOR) {
            debugger.log(String.format("Selection floor %d is FIXED_ELEVATOR_FLOOR, using elevator index %d (first elevator)",
                    targetFloorNum, FIXED_ELEVATOR_INDEX));

            List<ElevatorConfig> buildingConfigs = getElevatorConfigsForBuilding(buildingId);
            ElevatorConfig fixedConfig = getElevatorConfigByIndex(buildingConfigs, FIXED_ELEVATOR_INDEX);

            if (fixedConfig != null) {
                Map<String, String> suffixToConfigIdMap = buildSuffixToConfigIdMap(elevatorConfigs.getValue());
                debugger.log(String.format("suffixToConfigIdMap: %s", suffixToConfigIdMap));

                for (Position pos : matchingPositions) {
                    String posConfigId = extractElevatorConfigIdFromPointWithMapping(pos, elevatorConfigs.getValue(), suffixToConfigIdMap);
                    if (fixedConfig.getId().equals(posConfigId)) {
                        debugger.log(String.format("Found position for fixed (first) elevator %s at floor %d: %s",
                                fixedConfig.getId(), targetFloorNum, pos.getName()));
                        return pos;
                    }
                }
            }
            debugger.logWarning("findPositionWithFloorAndElevatorSelection",
                    String.format("No position found for fixed (first) elevator on floor %d", targetFloorNum));
            return null;
        }

        // Selection floor is NOT the fixed floor - find any available (unoccupied) elevator
        debugger.log(String.format("Selection floor %d is NOT the fixed floor, finding available elevator", targetFloorNum));

        // Get available elevators from NavigationElevatorManager for this selection floor
        Map<String, Boolean> availability = elevatorManager.getElevatorAvailabilityForFloor(buildingId, targetFloorNum);

        // Build list of available elevator configs
        List<ElevatorConfig> availableConfigs = new ArrayList<>();
        List<ElevatorConfig> buildingConfigs = getElevatorConfigsForBuilding(buildingId);

        for (ElevatorConfig config : buildingConfigs) {
            Boolean isAvailable = availability.get(config.getId());
            if (isAvailable != null && isAvailable) {
                availableConfigs.add(config);
            }
        }

        debugger.log(String.format("Found %d available elevators from NavigationElevatorManager for building '%s', target floor %d",
                availableConfigs.size(), buildingId, targetFloorNum));

        // Build suffix mapping once for all configs
        Map<String, String> suffixToConfigIdMap = buildSuffixToConfigIdMap(elevatorConfigs.getValue());

        if (!availableConfigs.isEmpty()) {
            // Try each available elevator in order (first available wins)
            for (ElevatorConfig config : availableConfigs) {
                for (Position pos : matchingPositions) {
                    String posConfigId = extractElevatorConfigIdFromPointWithMapping(pos, elevatorConfigs.getValue(), suffixToConfigIdMap);
                    if (config.getId().equals(posConfigId)) {
                        debugger.log(String.format("Found position for available elevator %s at floor %d: %s",
                                config.getId(), targetFloorNum, pos.getName()));
                        return pos;
                    }
                }
            }
        }

        // If no position found for available elevators, trigger async query
        debugger.log(String.format("No position found for available elevators on floor %d, triggering async query",
                targetFloorNum));
        elevatorManager.triggerImmediateQuery();

        // Fallback: Try to find any matching position (even if elevator is occupied)
        // This prevents navigation from failing completely
        debugger.logWarning("findPositionWithFloorAndElevatorSelection",
                String.format("No available elevator found, using fallback position on floor %s", targetFloor));

        // Try to find a position that matches any elevator config on the correct floor
        for (Position pos : matchingPositions) {
            String posConfigId = extractElevatorConfigIdFromPointWithMapping(pos, elevatorConfigs.getValue(), suffixToConfigIdMap);
            if (posConfigId != null) {
                debugger.log(String.format("Using fallback position with elevator config %s at floor %s: %s",
                        posConfigId, targetFloor, pos.getName()));
                return pos;
            }
        }

        // Absolute fallback: first matching position on the correct floor
        debugger.logWarning("findPositionWithFloorAndElevatorSelection",
                String.format("Using absolute fallback - first matching position for floor %s", targetFloor));
        return matchingPositions.isEmpty() ? null : matchingPositions.get(0);
    }

    /**
     * Build a mapping from point name suffix (extracted config ID) to actual config ID.
     * For example: "5" -> "1", "8" -> "8"
     * This handles cases where point names have stale config IDs.
     */
    private Map<String, String> buildSuffixToConfigIdMap(List<ElevatorConfig> elevatorConfigs) {
        Map<String, String> suffixToConfigIdMap = new HashMap<>();

        if (elevatorConfigs == null || elevatorConfigs.isEmpty()) {
            return suffixToConfigIdMap;
        }

        debugger.log("buildSuffixToConfigIdMap: Building suffix to config ID mapping...");

        for (ElevatorConfig config : elevatorConfigs) {
            debugger.log(String.format("  Checking config ID: %s", config.getId()));

            for (ElevatorFloor floor : config.getFloors()) {
                // Check wait point
                Position waitPoint = floor.getWaitPoint();
                if (waitPoint != null && waitPoint.getName() != null) {
                    String pointName = waitPoint.getName();
                    if (pointName.startsWith("elevator_wait_")) {
                        Matcher matcher = Pattern.compile("^elevator_wait_\\d+_(\\d+)$").matcher(pointName);
                        if (matcher.matches()) {
                            String suffix = matcher.group(1);
                            if (!suffixToConfigIdMap.containsKey(suffix)) {
                                suffixToConfigIdMap.put(suffix, config.getId());
                                debugger.log(String.format("    Mapped suffix '%s' -> config ID '%s' (from wait point: %s)",
                                        suffix, config.getId(), pointName));
                            }
                        }
                    }
                }

                // Check ride point
                Position ridePoint = floor.getRidePoint();
                if (ridePoint != null && ridePoint.getName() != null) {
                    String pointName = ridePoint.getName();
                    if (pointName.startsWith("elevator_ride_")) {
                        Matcher matcher = Pattern.compile("^elevator_ride_\\d+_(\\d+)$").matcher(pointName);
                        if (matcher.matches()) {
                            String suffix = matcher.group(1);
                            if (!suffixToConfigIdMap.containsKey(suffix)) {
                                suffixToConfigIdMap.put(suffix, config.getId());
                                debugger.log(String.format("    Mapped suffix '%s' -> config ID '%s' (from ride point: %s)",
                                        suffix, config.getId(), pointName));
                            }
                        }
                    }
                }
            }
        }

        debugger.log(String.format("buildSuffixToConfigIdMap: Final mapping: %s", suffixToConfigIdMap));
        return suffixToConfigIdMap;
    }

    /**
     * Extract elevator config ID from a position point with mapping support.
     * If the extracted config ID doesn't exist, it uses the suffix mapping.
     */
    private String extractElevatorConfigIdFromPointWithMapping(Position point, List<ElevatorConfig> elevatorConfigs, Map<String, String> suffixToConfigIdMap) {
        if (point == null || elevatorConfigs == null) {
            return null;
        }

        String pointName = point.getName();

        // First, try to extract from point name if it's an elevator point
        if (pointName != null && pointName.startsWith("elevator_")) {
            Matcher matcher = Pattern.compile("^elevator_(?:wait|ride|transition|preride)_(\\d+)_(\\d+)$")
                    .matcher(pointName);
            if (matcher.matches()) {
                String extractedConfigId = matcher.group(2);
                debugger.log(String.format("extractElevatorConfigIdFromPointWithMapping: Extracted configId='%s' from point: %s",
                        extractedConfigId, pointName));

                // Check if this config ID exists
                for (ElevatorConfig config : elevatorConfigs) {
                    if (config.getId().equals(extractedConfigId)) {
                        debugger.log(String.format("extractElevatorConfigIdFromPointWithMapping: Config ID '%s' exists", extractedConfigId));
                        return extractedConfigId;
                    }
                }

                // Config ID not found - try mapping
                if (suffixToConfigIdMap != null && suffixToConfigIdMap.containsKey(extractedConfigId)) {
                    String mappedConfigId = suffixToConfigIdMap.get(extractedConfigId);
                    debugger.log(String.format("extractElevatorConfigIdFromPointWithMapping: Mapped '%s' -> '%s'",
                            extractedConfigId, mappedConfigId));
                    return mappedConfigId;
                }

                debugger.logWarning("extractElevatorConfigIdFromPointWithMapping",
                        String.format("Config ID '%s' not found and no mapping available", extractedConfigId));
            }
        }

        // Fallback to building-based lookup
        String mapName = point.getMapName();
        if (mapName != null && !mapName.isEmpty()) {
            String buildingId = extractBuildingId(mapName);
            if (buildingId != null) {
                for (ElevatorConfig config : elevatorConfigs) {
                    String configBuildingId = config.getBuildingId();
                    if (configBuildingId == null) configBuildingId = "default";
                    if (configBuildingId.equals(buildingId)) {
                        debugger.log(String.format("extractElevatorConfigIdFromPointWithMapping: Found by building lookup: %s",
                                config.getId()));
                        return config.getId();
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extract elevator config ID from a position point
     */
    private String extractElevatorConfigIdFromPoint(Position point, List<ElevatorConfig> elevatorConfigs) {
        debugger.log(String.format("extractElevatorConfigIdFromPoint: point=%s, elevatorConfigs size=%s",
                point != null ? point.getName() : "null",
                elevatorConfigs != null ? elevatorConfigs.size() : "null"));

        if (point == null) {
            debugger.logWarning("extractElevatorConfigIdFromPoint", "point is null");
            return null;
        }

        if (elevatorConfigs == null) {
            debugger.logWarning("extractElevatorConfigIdFromPoint", "elevatorConfigs is null");
            return null;
        }

        String pointName = point.getName();
        debugger.log(String.format("extractElevatorConfigIdFromPoint: pointName='%s', type=%d", pointName, point.getType()));

        // First, try to extract from point name if it's an elevator point
        if (pointName != null && pointName.startsWith("elevator_")) {
            debugger.log("extractElevatorConfigIdFromPoint: Point is an elevator point, parsing name");
            Matcher matcher = Pattern.compile("^elevator_(?:wait|ride|transition|preride)_(\\d+)_(\\d+)$")
                    .matcher(pointName);
            if (matcher.matches()) {
                String configId = matcher.group(2);
                debugger.log(String.format("extractElevatorConfigIdFromPoint: Extracted configId='%s' from elevator point name: %s",
                        configId, pointName));
                return configId;
            } else {
                debugger.logWarning("extractElevatorConfigIdFromPoint",
                        String.format("Elevator point name '%s' does not match expected pattern", pointName));
            }
        }

        // For regular points, extract buildingId from mapName and find matching elevator config
        String mapName = point.getMapName();
        debugger.log(String.format("extractElevatorConfigIdFromPoint: mapName='%s'", mapName));

        if (mapName != null && !mapName.isEmpty()) {
            String buildingId = extractBuildingId(mapName);
            debugger.log(String.format("extractElevatorConfigIdFromPoint: Extracted buildingId='%s' from mapName", buildingId));

            if (buildingId != null) {
                for (ElevatorConfig config : elevatorConfigs) {
                    String configBuildingId = config.getBuildingId();
                    if (configBuildingId == null) configBuildingId = "default";

                    if (configBuildingId.equals(buildingId)) {
                        debugger.log(String.format("extractElevatorConfigIdFromPoint: Found matching config ID='%s' for building='%s'",
                                config.getId(), buildingId));
                        return config.getId();
                    }
                }
                debugger.logWarning("extractElevatorConfigIdFromPoint",
                        String.format("No matching elevator config found for buildingId='%s'", buildingId));
            }
        } else {
            debugger.logWarning("extractElevatorConfigIdFromPoint",
                    String.format("mapName is null or empty for point: %s", pointName));
        }

        debugger.logWarning("extractElevatorConfigIdFromPoint",
                String.format("Could not extract configId for point: name='%s', mapName='%s'", pointName, mapName));
        return null;
    }

    /**
     * Extract building ID from map name (reused from CommandUtils logic)
     */
    private String extractBuildingId(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return "0";
        }

        // Remove file extension
        int dotIndex = fullMapName.lastIndexOf('.');
        String mapName = dotIndex > 0 ? fullMapName.substring(0, dotIndex) : fullMapName;

        // Check multi-building format: prefix + numericBuildingId + _ + floor
        Pattern multiPattern = Pattern.compile("^([A-Za-z]+)(\\d+)_(\\d+)$");
        Matcher multiMatcher = multiPattern.matcher(mapName);
        if (multiMatcher.matches()) {
            return multiMatcher.group(2);
        }

        // Legacy format
        return "0";
    }

    /**
     * Callback interface for query access results
     */
    public interface AccessQueryCallback {
        /**
         * Called when the query completes
         * @param isNotOccupied true if the elevator is not occupied, false if occupied
         */
        void onResult(boolean isNotOccupied);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.clear();
    }

    // --------------------------------------------------------------------------------------------
    // Data Classes

    public static class ElevatorConfig {
        // Thread-safe ID counter
        private static final AtomicInteger nextId = new AtomicInteger(1);

        private String id;
        private int channel;
        private int address;
        private String buildingId;  // New field for building association
        private List<ElevatorFloor> floors = new ArrayList<>();

        public ElevatorConfig() {
            this.id = String.valueOf(nextId.getAndIncrement());
            this.buildingId = "default";
        }

        public String getId() {
            return id;
        }

        public void setId(String newId) {
            id = newId;
        }

        public int getChannel() {
            return channel;
        }

        public void setChannel(int channel) {
            this.channel = channel;
        }

        public int getAddress() {
            return address;
        }

        public void setAddress(int address) {
            this.address = address;
        }

        public String getBuildingId() {
            return buildingId;
        }

        public void setBuildingId(String buildingId) {
            this.buildingId = buildingId;
        }

        public List<ElevatorFloor> getFloors() {
            return floors;
        }

        public void setFloors(List<ElevatorFloor> floors) {
            this.floors = floors;
        }
    }

    public static class ElevatorFloor {
        private String id;
        private String alias;
        private int floor;
        private Position waitPoint;
        private Position preridePoint;  // NEW
        private Position ridePoint;
        private Position transitionPoint;
        private String elevatorConfigId;
        private String buildingId;

        public ElevatorFloor() {
            this.id = UUID.randomUUID().toString();
            this.waitPoint = new Position();
            this.preridePoint = new Position();  // NEW
            this.ridePoint = new Position();
            this.transitionPoint = new Position();

            // Set default types
            this.waitPoint.setType(3);
            this.preridePoint.setType(2);  // NEW
            this.ridePoint.setType(4);
            this.transitionPoint.setType(14);
        }

        // Getters and setters
        public String getElevatorConfigId() {
            return elevatorConfigId;
        }

        public void setElevatorConfigId(String elevatorConfigId) {
            this.elevatorConfigId = elevatorConfigId;
        }

        public String getBuildingId() {
            return buildingId;
        }

        public void setBuildingId(String buildingId) {
            this.buildingId = buildingId;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public int getFloor() {
            return floor;
        }

        public void setFloor(int floor) {
            this.floor = floor;
            // Update floor in positions
            if (waitPoint != null) waitPoint.setFloor(String.valueOf(floor));
            if (preridePoint != null) preridePoint.setFloor(String.valueOf(floor));  // NEW
            if (ridePoint != null) ridePoint.setFloor(String.valueOf(floor));
            if (transitionPoint != null) transitionPoint.setFloor(String.valueOf(floor));
        }

        public Position getWaitPoint() {
            return waitPoint;
        }

        public void setWaitPoint(Position waitPoint) {
            this.waitPoint = waitPoint;
            if (waitPoint != null) {
                waitPoint.setType(3);
                waitPoint.setFloor(String.valueOf(floor));
            }
        }

        public Position getPreridePoint() {  // NEW
            return preridePoint;
        }

        public void setPreridePoint(Position preridePoint) {  // NEW
            this.preridePoint = preridePoint;
            if (preridePoint != null) {
                preridePoint.setType(2);
                preridePoint.setFloor(String.valueOf(floor));
            }
        }

        public Position getRidePoint() {
            return ridePoint;
        }

        public void setRidePoint(Position ridePoint) {
            this.ridePoint = ridePoint;
            if (ridePoint != null) {
                ridePoint.setType(4);
                ridePoint.setFloor(String.valueOf(floor));
            }
        }

        public Position getTransitionPoint() {
            return transitionPoint;
        }

        public void setTransitionPoint(Position transitionPoint) {
            this.transitionPoint = transitionPoint;
            if (transitionPoint != null) {
                transitionPoint.setType(14);
                transitionPoint.setFloor(String.valueOf(floor));
            }
        }
    }
}