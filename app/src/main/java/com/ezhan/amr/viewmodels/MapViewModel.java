package com.ezhan.amr.viewmodels;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.utils.LocaleHelper;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MarkerData;
import com.ezhan.amr.data.datatype.MarkerPoint;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class MapViewModel extends AndroidViewModel {
    private String TAG = "MapViewModel";
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();
    public static final int SPECIAL_TYPE_PRECHARGE = 11;
    public static final int SPECIAL_TYPE_CHARGE = 10;
    public static final int SPECIAL_TYPE_HOME = 12;
    public static final int SPECIAL_TYPE_RELOCALIZE = 13;
    public static final int SPECIAL_TYPE_PHARMACY = 15;
    public static final int SPECIAL_TYPE_PIVAS = 16;

    private final SharedViewModel sharedViewModel;

    private final StatusWebSocketClient statusClient;
    private final CommandWebSocketClient commandClient;

    private static final Pattern MULTI_BUILDING_PATTERN = Pattern.compile("^([A-Za-z]+)(\\d+)_(\\d+)$");
    private static final Pattern LEGACY_TWO_PART_PATTERN = Pattern.compile("^([A-Za-z]+)_(\\d+)$");
    private static final Pattern LEGACY_ONE_PART_PATTERN = Pattern.compile("^([A-Za-z]+)");
    // --------------------------------------------------------------------------------------------
    // Changed from MapPoints to MultiBuildingMapPoints
    private final MutableLiveData<MultiBuildingMapPoints> currentMapPoints = new MutableLiveData<>(new MultiBuildingMapPoints());
    private final MutableLiveData<String> currentBuildingLiveData = new MutableLiveData<>("");
    private final MutableLiveData<String> currentMapLiveData = new MutableLiveData<>("");
    private final MutableLiveData<Integer> currentFloorLiveData = new MutableLiveData<>(0);
    private String lastKnownMap = "";
    private StatusWebSocketClient.StatusListener statusListener;
    private AgvStatusResponse latestStatusResponse;

    // Flag to prevent auto-download from overwriting config data
    private boolean isConfigDataLoaded = false;
    private long configDataLoadTime = 0;

    public MapViewModel(@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        statusClient = sharedViewModel.getStatusClient();
        commandClient = sharedViewModel.getCommandClient();

        loadAllPersistedData();
        startCurrentMapMonitoring();
    }

    // --------------------------------------------------------------------------------------------
    // ************ Load/Save currentMapPoints from/to DataStore ******************
    private void loadAllPersistedData() {
        Log.i("taskDebug7", "[LOAD] loadAllPersistedData START isConfigDataLoaded=" + isConfigDataLoaded);
        disposables.add(dataStoreManager.getCurrentMapPoints()
                .subscribe(loadedPoints -> {
                    int buildingCount = loadedPoints != null ? loadedPoints.getAllBuildings().size() : 0;
                    int totalPoints = loadedPoints != null ? loadedPoints.getTotalPositionCount() : 0;
                    Log.i("taskDebug7", "[LOAD] DataStore read OK: buildings=" + buildingCount +
                            ", totalPoints=" + totalPoints + ", isConfigDataLoaded=" + isConfigDataLoaded);
                    // 诊断断电重启后站点丢失：打印每个楼层的点位明细
                    if (loadedPoints != null) {
                        for (Building b : loadedPoints.getAllBuildings().values()) {
                            for (Map.Entry<Integer, FloorPoints> fe : b.getAllFloorPoints().entrySet()) {
                                FloorPoints fp = fe.getValue();
                                Log.i("taskDebug7", "[LOAD] building=" + b.getBuildingId() +
                                        ", floor=" + fe.getKey() +
                                        ", workPoints=" + (fp.getWorkPoints() != null ? fp.getWorkPoints().size() : 0) +
                                        ", charge=" + (fp.getChargePoint() != null) +
                                        ", park=" + (fp.getParkPoint() != null) +
                                        ", mapName=" + fp.getMapPrefix());
                            }
                        }
                    }
                    if (loadedPoints != null) {
                        currentMapPoints.postValue(loadedPoints);
                    } else {
                        Log.w("taskDebug7", "[LOAD] loadedPoints is null, posting empty MultiBuildingMapPoints");
                        currentMapPoints.postValue(new MultiBuildingMapPoints());
                    }
                }, throwable -> {
                    Log.e("taskDebug7", "[LOAD] DataStore read FAILED", throwable);
                    currentMapPoints.postValue(new MultiBuildingMapPoints());
                }));
    }

    private void saveCurrentMapPoints() {
        MultiBuildingMapPoints points = currentMapPoints.getValue();
        if (points != null) {
            int buildings = points.getAllBuildings().size();
            int totalPoints = points.getTotalPositionCount();
            Log.i("taskDebug7", "[SAVE] saveCurrentMapPoints: buildings=" + buildings +
                    ", totalPoints=" + totalPoints + ", isConfigDataLoaded=" + isConfigDataLoaded);
            dataStoreManager.saveCurrentMapPoints(points);
        } else {
            Log.w("taskDebug7", "[SAVE] saveCurrentMapPoints: points is null, SKIP save");
        }
    }

    // --------------------------------------------------------------------------------------------
    // ************ currentMapPoints management ******************

    /**
     * Update current map points with building-aware structure
     *
     * @param buildingId The building ID (e.g., "A", "B", "01", "02")
     * @param currentMapName The current map name (prefix)
     * @param currentMapFloor The current floor number
     * @param updatePointType Type of point being updated
     * @param updatePointCollection Collection of points to update
     */
    public void updateCurrentMapPoints(String buildingId, String currentMapName, int currentMapFloor,
                                       int updatePointType, List<Position> updatePointCollection) {
        if (currentMapName == null || currentMapName.isEmpty()) {
            Log.e(TAG, "updateCurrentMapPoints: currentMapName is null or empty");
            return;
        }

        if (buildingId == null || buildingId.isEmpty()) {
            Log.e(TAG, "updateCurrentMapPoints: buildingId is null or empty");
            return;
        }

        if (updatePointCollection == null) {
            Log.e(TAG, "updateCurrentMapPoints: updatePointCollection is null");
            return;
        }

        Log.d(TAG, "updateCurrentMapPoints: building=" + buildingId + ", map=" + currentMapName +
                ", floor=" + currentMapFloor + ", type=" + updatePointType +
                ", points count=" + updatePointCollection.size());

        // Get current MapPoints or create new if empty
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) {
            mapPoints = new MultiBuildingMapPoints(currentMapName);
            Log.d(TAG, "updateCurrentMapPoints: currentMapPoints was null, created new instance");
        }

        // Set base map prefix if not set
        if (mapPoints.getBaseMapPrefix() == null) {
            mapPoints.setBaseMapPrefix(currentMapName);
        }

        // Get or create building
        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) {
            building = new Building(buildingId, currentMapName);
            mapPoints.addBuilding(building);
            Log.d(TAG, "updateCurrentMapPoints: created new building " + buildingId);
        }

        // Get or create FloorPoints for the specified floor
        FloorPoints floorPoints = building.getFloorPoints(currentMapFloor);
        if (floorPoints == null) {
            String floorMapName = MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor);
            floorPoints = new FloorPoints(floorMapName);
            Log.d(TAG, "updateCurrentMapPoints: created new FloorPoints for building " + buildingId +
                    ", floor " + currentMapFloor);
        }

        boolean pointsChanged = false;

        // Update points based on updatePointType
        switch (updatePointType) {
            case 1: // workPoint
                Log.d(TAG, "updateCurrentMapPoints: Updating " + updatePointCollection.size() + " work points");

                List<Position> currentWorkPoints = floorPoints.getWorkPoints();

                // Create maps for quick lookup
                Map<Integer, Position> existingById = new HashMap<>();
                Map<String, Position> existingByName = new HashMap<>();

                for (Position pos : currentWorkPoints) {
                    if (pos.getId() > 0) {
                        existingById.put(pos.getId(), pos);
                    }
                    if (pos.getName() != null && !pos.getName().isEmpty()) {
                        existingByName.put(pos.getName(), pos);
                    }
                }

                int updatedCount = 0;
                int addedCount = 0;

                for (Position newPos : updatePointCollection) {
                    boolean found = false;

                    if (newPos.getId() > 0 && existingById.containsKey(newPos.getId())) {
                        Position existingPos = existingById.get(newPos.getId());
                        if (hasPositionChanged(existingPos, newPos)) {
                            updatePositionData(existingPos, newPos);
                            pointsChanged = true;
                            updatedCount++;
                            Log.d(TAG, "  ✓ Updated work point by ID: " + existingPos.getName());
                        }
                        found = true;
                    }
                    else if (newPos.getName() != null && !newPos.getName().isEmpty() &&
                            existingByName.containsKey(newPos.getName())) {
                        Position existingPos = existingByName.get(newPos.getName());
                        if (hasPositionChanged(existingPos, newPos)) {
                            updatePositionData(existingPos, newPos);
                            pointsChanged = true;
                            updatedCount++;
                            Log.d(TAG, "  ✓ Updated work point by name: " + existingPos.getName());
                        }
                        found = true;
                    }

                    if (!found) {
                        if (newPos.getId() <= 0) {
                            newPos.setId(generateNewId());
                        }
                        // Ensure map name is set correctly
                        if (newPos.getMapName() == null) {
                            newPos.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                        }
                        floorPoints.addWorkPoint(newPos);
                        pointsChanged = true;
                        addedCount++;
                        Log.d(TAG, "  + Added new work point: " + newPos.getName());
                    }
                }

                Log.d(TAG, "Work points update: Updated=" + updatedCount + ", Added=" + addedCount);
                break;

            case 2: // elevatorPreridePoint
                pointsChanged = updateElevatorPointsByConfig(
                        floorPoints.getElevatorPreridePoints(),
                        updatePointCollection,
                        floorPoints,
                        "elevatorPreridePoints",
                        currentMapName, buildingId, currentMapFloor
                );
                break;

            case 3: // elevatorWaitPoint
                pointsChanged = updateElevatorPointsByConfig(
                        floorPoints.getElevatorWaitPoints(),
                        updatePointCollection,
                        floorPoints,
                        "elevatorWaitPoints",
                        currentMapName, buildingId, currentMapFloor
                );
                break;

            case 4: // elevatorRidePoint
                pointsChanged = updateElevatorPointsByConfig(
                        floorPoints.getElevatorRidePoints(),
                        updatePointCollection,
                        floorPoints,
                        "elevatorRidePoints",
                        currentMapName, buildingId, currentMapFloor
                );
                break;

            case 10: // chargePoint
                Position chargePoint = updatePointCollection.isEmpty() ? null : updatePointCollection.get(0);
                if (chargePoint != null) {
                    if (chargePoint.getMapName() == null) {
                        chargePoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                    }
                    Position existingCharge = floorPoints.getChargePoint();
                    if (existingCharge == null || hasPositionChanged(existingCharge, chargePoint)) {
                        floorPoints.setChargePoint(chargePoint);
                        pointsChanged = true;
                        Log.d(TAG, "Updated charge point: " + chargePoint.getName());
                    }
                } else if (floorPoints.getChargePoint() != null) {
                    // Send rmCharge command to server to delete charge point
                    String fullMapName = MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor);
                    if (commandClient != null) {
                        boolean sent = commandClient.removeChargePoint(fullMapName);
                        Log.d(TAG, "Sending rmCharge command for map: " + fullMapName + " - success: " + sent);
                    }
                    floorPoints.setChargePoint(null);
                    pointsChanged = true;
                }
                break;

            case 11: // prechargePoint
                Position preChargePoint = updatePointCollection.isEmpty() ? null : updatePointCollection.get(0);
                if (preChargePoint != null) {
                    if (preChargePoint.getMapName() == null) {
                        preChargePoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                    }
                    Position existingPreCharge = floorPoints.getPreChargePoint();
                    if (existingPreCharge == null || hasPositionChanged(existingPreCharge, preChargePoint)) {
                        floorPoints.setPreChargePoint(preChargePoint);
                        pointsChanged = true;
                    }
                } else if (floorPoints.getPreChargePoint() != null) {
                    floorPoints.setPreChargePoint(null);
                    pointsChanged = true;
                }
                break;

            case 12: // parkPoint
                Position parkPoint = updatePointCollection.isEmpty() ? null : updatePointCollection.get(0);
                if (parkPoint != null) {
                    if (parkPoint.getMapName() == null) {
                        parkPoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                    }
                    Position existingPark = floorPoints.getParkPoint();
                    if (existingPark == null || hasPositionChanged(existingPark, parkPoint)) {
                        floorPoints.setParkPoint(parkPoint);
                        pointsChanged = true;
                    }
                } else if (floorPoints.getParkPoint() != null) {
                    floorPoints.setParkPoint(null);
                    pointsChanged = true;
                }
                break;

            case 13: // relocalizePoint
                Position relocalizePoint = updatePointCollection.isEmpty() ? null : updatePointCollection.get(0);
                if (relocalizePoint != null) {
                    if (relocalizePoint.getMapName() == null) {
                        relocalizePoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                    }
                    Position existingRelocalize = floorPoints.getRelocalizePoint();
                    if (existingRelocalize == null || hasPositionChanged(existingRelocalize, relocalizePoint)) {
                        floorPoints.setRelocalizePoint(relocalizePoint);
                        pointsChanged = true;
                    }
                } else if (floorPoints.getRelocalizePoint() != null) {
                    floorPoints.setRelocalizePoint(null);
                    pointsChanged = true;
                }
                break;

            case 14: // elevatorTransitionPoint
                pointsChanged = updateElevatorPointsByConfig(
                        floorPoints.getElevatorTransitionPoints(),
                        updatePointCollection,
                        floorPoints,
                        "elevatorTransitionPoints",
                        currentMapName, buildingId, currentMapFloor
                );
                break;

            case 15: // pharmacyPoint
                Position pharmacyPoint = updatePointCollection.isEmpty() ? null : updatePointCollection.get(0);
                if (pharmacyPoint != null) {
                    if (pharmacyPoint.getMapName() == null) {
                        pharmacyPoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                    }
                    Position existingPharmacy = floorPoints.getPharmacyPoint();
                    if (existingPharmacy == null || hasPositionChanged(existingPharmacy, pharmacyPoint)) {
                        floorPoints.setPharmacyPoint(pharmacyPoint);
                        pointsChanged = true;
                    }
                } else if (floorPoints.getPharmacyPoint() != null) {
                    floorPoints.setPharmacyPoint(null);
                    pointsChanged = true;
                }
                break;

            case 16: // pivasPoint
                Position pivasPoint = updatePointCollection.isEmpty() ? null : updatePointCollection.get(0);
                if (pivasPoint != null) {
                    if (pivasPoint.getMapName() == null) {
                        pivasPoint.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
                    }
                    Position existingPivas = floorPoints.getPivasPoint();
                    if (existingPivas == null || hasPositionChanged(existingPivas, pivasPoint)) {
                        floorPoints.setPivasPoint(pivasPoint);
                        pointsChanged = true;
                    }
                } else if (floorPoints.getPivasPoint() != null) {
                    floorPoints.setPivasPoint(null);
                    pointsChanged = true;
                }
                break;

            default:
                Log.e(TAG, "updateCurrentMapPoints: Unknown point type: " + updatePointType);
                return;
        }

        if (pointsChanged) {
            // Update the floor points in building
            building.setFloorPoints(currentMapFloor, floorPoints);

            // Update building in mapPoints (already reference, but ensure it's there)
            mapPoints.addBuilding(building);

            // Create a new instance to trigger LiveData update
            MultiBuildingMapPoints newMapPoints = new MultiBuildingMapPoints(mapPoints.getBaseMapPrefix());
            for (Map.Entry<String, Building> entry : mapPoints.getAllBuildings().entrySet()) {
                newMapPoints.addBuilding(entry.getValue());
            }

            // Post the new value on main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                currentMapPoints.setValue(newMapPoints);
                saveCurrentMapPoints();
            });

            Log.d(TAG, "updateCurrentMapPoints: Successfully updated and posted new MapPoints");
        } else {
            Log.d(TAG, "updateCurrentMapPoints: No changes detected, skipping update");
        }
    }

    /**
     * Legacy method for backward compatibility - uses default building
     */
    public void updateCurrentMapPoints(String currentMapName, int currentMapFloor,
                                       int updatePointType, List<Position> updatePointCollection) {
        updateCurrentMapPoints("default", currentMapName, currentMapFloor, updatePointType, updatePointCollection);
    }

    /**
     * Set current map points directly from config file.
     * This replaces the entire MultiBuildingMapPoints object.
     */
    public void updateCurrentMapPoints(MultiBuildingMapPoints newMapPoints) {
        int buildings = newMapPoints != null ? newMapPoints.getAllBuildings().size() : 0;
        int totalPoints = newMapPoints != null ? newMapPoints.getTotalPositionCount() : 0;
        Log.i("taskDebug7", "[CFG] updateCurrentMapPoints(fromConfig): buildings=" + buildings +
                ", totalPoints=" + totalPoints + ", isConfigDataLoaded=" + isConfigDataLoaded);

        if (newMapPoints != null && !newMapPoints.getAllBuildings().isEmpty()) {
            // Set flag to prevent point updates from overwriting config data
            isConfigDataLoaded = true;
            configDataLoadTime = System.currentTimeMillis();
            Log.i("taskDebug7", "[CFG] Set isConfigDataLoaded=true, configDataLoadTime=" + configDataLoadTime);

            // IMPORTANT: Do NOT call saveCurrentMapPoints() here!
            // It would save to DataStore which might trigger a reload of old data.
            // The config file is the source of truth, not DataStore.
            currentMapPoints.setValue(newMapPoints);
            Log.i("taskDebug7", "[CFG] setValue done, buildings=" + buildings + " (config NOT saved to DataStore)");
        } else {
            Log.w("taskDebug7", "[CFG] newMapPoints is null or empty, SKIP update");
        }
    }

    /**
     * Helper method to check if position values have changed
     */
    private boolean hasPositionChanged(Position existing, Position newPos) {
        if (existing == null || newPos == null) return true;

        return existing.getPosX() != newPos.getPosX() ||
                existing.getPosY() != newPos.getPosY() ||
                existing.getYaw() != newPos.getYaw() ||
                !existing.getName().equals(newPos.getName()) ||
                !existing.getFloor().equals(newPos.getFloor());
    }

    /**
     * Helper method to update position data while preserving ID
     */
    private void updatePositionData(Position existingPos, Position newPos) {
        int originalId = existingPos.getId();
        existingPos.setName(newPos.getName());
        existingPos.setPosX(newPos.getPosX());
        existingPos.setPosY(newPos.getPosY());
        existingPos.setYaw(newPos.getYaw());
        existingPos.setType(newPos.getType());
        existingPos.setFloor(newPos.getFloor());
        existingPos.setMapName(newPos.getMapName());
        existingPos.setTaskType(newPos.getTaskType());
        existingPos.setSpecialType(newPos.getSpecialType());
        existingPos.setId(originalId);
    }

    public void addPosition(String positionName, String buildingId, String currentMapName, int currentMapFloor) {
        if (currentMapName == null || currentMapName.isEmpty()) {
            Log.e(TAG, "addPosition: currentMapName is null or empty");
            return;
        }

        if (buildingId == null || buildingId.isEmpty()) {
            Log.e(TAG, "addPosition: buildingId is null or empty");
            return;
        }

        Log.d(TAG, "addPosition: Adding current position to building=" + buildingId +
                ", map=" + currentMapName + ", floor=" + currentMapFloor);

        if (latestStatusResponse == null || latestStatusResponse.data == null ||
                latestStatusResponse.data.pos == null) {
            Log.e(TAG, "addPosition: No current position data available");
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_cannot_get_current_position), Toast.LENGTH_SHORT).show();
            return;
        }

        AgvStatusResponse.DataPosition currentPos = latestStatusResponse.data.pos;

        Position newPosition = new Position();
        newPosition.setId(generateNewId());
        newPosition.setName(positionName);
        newPosition.setPosX(currentPos.x);
        newPosition.setPosY(currentPos.y);
        newPosition.setYaw(currentPos.theta);
        newPosition.setType(1);
        newPosition.setFloor(String.valueOf(currentMapFloor));
        newPosition.setMapName(MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, currentMapFloor));
        newPosition.setTaskType(0);
        newPosition.setSpecialType(-1);

        Log.d(TAG, "addPosition: Created new position at (" + currentPos.x + ", " + currentPos.y + ")");

        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) {
            mapPoints = new MultiBuildingMapPoints(currentMapName);
        }

        mapPoints.addPosition(buildingId, newPosition);
        currentMapPoints.postValue(mapPoints);
        saveCurrentMapPoints();

        @SuppressLint("DefaultLocale") String message = LocaleHelper.onServiceGetString(getApplication(), R.string.map_point_added,
                newPosition.getName(),
                String.format("%.2f", newPosition.getPosX()),
                String.format("%.2f", newPosition.getPosY()));
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show();

        Log.d(TAG, "addPosition: Successfully added position");
    }

    public void addPosition(String positionName, String currentMapName, int currentMapFloor) {
        addPosition(positionName, "default", currentMapName, currentMapFloor);
    }

    public void removePosition(String pointName, String buildingId, String currentMapName, int currentMapFloor) {
        if (currentMapName == null || currentMapName.isEmpty()) {
            Log.e(TAG, "removePosition: currentMapName is null or empty");
            return;
        }

        if (pointName == null || pointName.isEmpty()) {
            Log.e(TAG, "removePosition: pointName is null or empty");
            return;
        }

        Log.d(TAG, "removePosition: Removing work point '" + pointName + "' from building=" +
                buildingId + ", map=" + currentMapName + ", floor=" + currentMapFloor);

        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) {
            Log.w(TAG, "removePosition: currentMapPoints is null");
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_no_points_to_delete), Toast.LENGTH_SHORT).show();
            return;
        }

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) {
            Log.w(TAG, "removePosition: Building " + buildingId + " not found");
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_building_not_found), Toast.LENGTH_SHORT).show();
            return;
        }

        FloorPoints floorPoints = building.getFloorPoints(currentMapFloor);
        if (floorPoints == null) {
            Log.w(TAG, "removePosition: No floor points found for floor " + currentMapFloor);
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_no_points_on_floor), Toast.LENGTH_SHORT).show();
            return;
        }

        Position positionToRemove = null;
        for (Position pos : floorPoints.getWorkPoints()) {
            if (pos.getName() != null && pos.getName().equals(pointName)) {
                positionToRemove = pos;
                break;
            }
        }

        if (positionToRemove == null) {
            Log.w(TAG, "removePosition: Work point with name '" + pointName + "' not found");
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_point_not_found_by_name, pointName), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean removed = floorPoints.removeWorkPointById(positionToRemove.getId());

        if (removed) {
            building.setFloorPoints(currentMapFloor, floorPoints);
            mapPoints.addBuilding(building);
            currentMapPoints.postValue(mapPoints);
            saveCurrentMapPoints();
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_point_deleted, pointName), Toast.LENGTH_SHORT).show();
            Log.d(TAG, "removePosition: Successfully removed work point: " + pointName);
        } else {
            Log.e(TAG, "removePosition: Failed to remove work point: " + pointName);
            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_point_delete_failed), Toast.LENGTH_SHORT).show();
        }
    }

    public void removePosition(String pointName, String currentMapName, int currentMapFloor) {
        removePosition(pointName, "default", currentMapName, currentMapFloor);
    }

    public int generateNewId() {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return 1;

        int maxId = 0;
        for (Building building : mapPoints.getAllBuildings().values()) {
            for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
                FloorPoints floorPoints = entry.getValue();
                for (Position pos : floorPoints.getAllPoints()) {
                    if (pos.getId() > maxId) maxId = pos.getId();
                }
            }
        }
        return maxId + 1;
    }

    // --------------------------------------------------------------------------------------------
    // ************ upload currentMapPoints (all buildings and floors) ******************

    public void uploadCurrentMapPoints(String currentMapName) {
        if (currentMapName == null || currentMapName.isEmpty()) {
            Log.e(TAG, "uploadCurrentMapPoints: currentMapName is null or empty");
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_invalid_map_name), Toast.LENGTH_SHORT).show()
            );
            return;
        }

        Log.d(TAG, "uploadCurrentMapPoints: Starting upload for map=" + currentMapName);

        try {
            MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

            // Collect all building-floor combinations to upload
            commandClient.getAllMarkersAsync(new CommandWebSocketClient.MarkerDataCallback() {
                @Override
                public void onSuccess(MarkerData markerData) {
                    // Track which map names exist on server
                    Set<String> serverMapNames = new HashSet<>();
                    if (markerData != null && markerData.getData() != null) {
                        for (MarkerPoint marker : markerData.getData()) {
                            if (marker.getMapName() != null) {
                                serverMapNames.add(marker.getMapName());
                            }
                        }
                    }

                    // Build list of all floor points to upload (building-floor pairs)
                    List<FloorUploadTask> uploadTasks = new ArrayList<>();
                    int totalPoints = 0;

                    if (mapPoints != null) {
                        for (Map.Entry<String, Building> buildingEntry : mapPoints.getAllBuildings().entrySet()) {
                            String buildingId = buildingEntry.getKey();
                            Building building = buildingEntry.getValue();

                            for (Map.Entry<Integer, FloorPoints> floorEntry : building.getAllFloorPoints().entrySet()) {
                                int floor = floorEntry.getKey();
                                FloorPoints floorPoints = floorEntry.getValue();
                                String fullMapName = MultiBuildingMapPoints.generateMapName(currentMapName, buildingId, floor);

                                List<Position> points = floorPoints != null ? floorPoints.getAllPoints() : new ArrayList<>();
                                totalPoints += points.size();

                                uploadTasks.add(new FloorUploadTask(buildingId, floor, fullMapName, floorPoints, points));
                            }
                        }
                    }

                    if (uploadTasks.isEmpty()) {
                        Log.d(TAG, "uploadCurrentMapPoints: No floors to upload");
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_no_points_to_upload), Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }

                    int finalTotalPoints = totalPoints;
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(getApplication(),
                                    LocaleHelper.onServiceGetString(getApplication(), R.string.map_syncing_points, finalTotalPoints, uploadTasks.size()),
                                    Toast.LENGTH_SHORT).show()
                    );

                    // Start sequential upload
                    uploadNextFloorTask(uploadTasks, 0, 0);
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "uploadCurrentMapPoints: Failed to get server markers: " + error);
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_cannot_get_server_data, error), Toast.LENGTH_SHORT).show()
                    );
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "uploadCurrentMapPoints: Exception occurred", e);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_upload_failed, e.getMessage()), Toast.LENGTH_SHORT).show()
            );
        }
    }

    private static class FloorUploadTask {
        String buildingId;
        int floor;
        String fullMapName;
        FloorPoints floorPoints;
        List<Position> points;

        FloorUploadTask(String buildingId, int floor, String fullMapName, FloorPoints floorPoints, List<Position> points) {
            this.buildingId = buildingId;
            this.floor = floor;
            this.fullMapName = fullMapName;
            this.floorPoints = floorPoints;
            this.points = points;
        }
    }

    private void uploadNextFloorTask(List<FloorUploadTask> tasks, int currentIndex, int totalFailed) {
        if (currentIndex >= tasks.size()) {
            Log.d(TAG, "uploadNextFloorTask: All floors processed. Total failed: " + totalFailed);
            new Handler(Looper.getMainLooper()).post(() -> {
                String message = totalFailed == 0 ?
                        LocaleHelper.onServiceGetString(getApplication(), R.string.map_upload_all_success) :
                        LocaleHelper.onServiceGetString(getApplication(), R.string.map_upload_completed_with_failures, totalFailed);
                Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show();
            });
            return;
        }

        FloorUploadTask task = tasks.get(currentIndex);
        Log.d(TAG, "uploadNextFloorTask: Processing building=" + task.buildingId +
                ", floor=" + task.floor + " (" + (currentIndex + 1) + "/" + tasks.size() + ")");

        uploadFloorPoints(task.fullMapName, String.valueOf(task.floor), task.points, new FloorUploadCallback() {
            @Override
            public void onComplete(int failureCount) {
                uploadNextFloorTask(tasks, currentIndex + 1, totalFailed + failureCount);
            }
        });
    }

    private void uploadFloorPoints(String fullMapName, String floorStr, List<Position> points,
                                   FloorUploadCallback callback) {
        try {
            if (points.isEmpty()) {
                Log.d(TAG, "uploadFloorPoints: No points for " + fullMapName + ", clearing existing points");
                clearFloorPoints(fullMapName, floorStr, callback);
                return;
            }

            Log.d(TAG, "uploadFloorPoints: Uploading " + points.size() + " points for " + fullMapName);
            deleteAllPointsForFloor(fullMapName, floorStr, points, callback);

        } catch (Exception e) {
            Log.e(TAG, "uploadFloorPoints: Exception for " + fullMapName, e);
            if (callback != null) {
                callback.onComplete(1);
            }
        }
    }

    private void clearFloorPoints(String fullMapName, String floorStr, FloorUploadCallback callback) {
        Log.d(TAG, "clearFloorPoints: Clearing floor " + fullMapName);

        commandClient.getMapMarkersAsync(fullMapName, new CommandWebSocketClient.MarkerDataCallback() {
            @Override
            public void onSuccess(MarkerData markerData) {
                List<MarkerPoint> markersToDelete = new ArrayList<>();

                if (markerData != null && markerData.getData() != null) {
                    for (MarkerPoint marker : markerData.getData()) {
                        if (floorStr.equals(marker.getFloor())) {
                            markersToDelete.add(marker);
                        }
                    }
                }

                if (markersToDelete.isEmpty()) {
                    Log.d(TAG, "clearFloorPoints: No points to clear for " + fullMapName);
                    if (callback != null) {
                        callback.onComplete(0);
                    }
                    return;
                }

                Log.d(TAG, "clearFloorPoints: Found " + markersToDelete.size() + " points to clear");
                deleteMarkersForFloor(fullMapName, markersToDelete, 0, () -> {
                    if (callback != null) {
                        callback.onComplete(0);
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "clearFloorPoints: Failed to get markers for " + fullMapName + ": " + error);
                if (callback != null) {
                    callback.onComplete(1);
                }
            }
        });
    }

    private void deleteAllPointsForFloor(String fullMapName, String floorStr,
                                         List<Position> pointsToUpload,
                                         FloorUploadCallback callback) {
        Log.d(TAG, "deleteAllPointsForFloor: Processing " + fullMapName);

        commandClient.getMapMarkersAsync(fullMapName, new CommandWebSocketClient.MarkerDataCallback() {
            @Override
            public void onSuccess(MarkerData markerData) {
                List<MarkerPoint> markersToDelete = new ArrayList<>();

                if (markerData != null && markerData.getData() != null) {
                    for (MarkerPoint marker : markerData.getData()) {
                        if (floorStr.equals(marker.getFloor())) {
                            markersToDelete.add(marker);
                        }
                    }
                }

                if (markersToDelete.isEmpty()) {
                    Log.d(TAG, "deleteAllPointsForFloor: No existing points to delete for " + fullMapName);
                    uploadPointsToServer(fullMapName, floorStr, pointsToUpload, callback);
                    return;
                }

                Log.d(TAG, "deleteAllPointsForFloor: Found " + markersToDelete.size() + " points to delete");
                deleteMarkersForFloor(fullMapName, markersToDelete, 0,
                        () -> uploadPointsToServer(fullMapName, floorStr, pointsToUpload, callback));
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "deleteAllPointsForFloor: Failed to get markers for " + fullMapName + ": " + error);
                uploadPointsToServer(fullMapName, floorStr, pointsToUpload, callback);
            }
        });
    }

    private void deleteMarkersForFloor(String fullMapName, List<MarkerPoint> markersToDelete,
                                       int currentIndex, Runnable onComplete) {
        if (currentIndex >= markersToDelete.size()) {
            Log.d(TAG, "deleteMarkersForFloor: All markers processed for " + fullMapName);
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        MarkerPoint marker = markersToDelete.get(currentIndex);
        if (marker == null || marker.getName() == null || marker.getName().isEmpty()) {
            Log.w(TAG, "deleteMarkersForFloor: Skipping invalid marker at index " + currentIndex);
            deleteMarkersForFloor(fullMapName, markersToDelete, currentIndex + 1, onComplete);
            return;
        }

        commandClient.deleteMarkerAsync(fullMapName, marker.getName(), marker.getFloor(),
                new CommandWebSocketClient.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        Log.d(TAG, "✓ Deleted marker: " + marker.getName() + " from " + fullMapName);
                        deleteMarkersForFloor(fullMapName, markersToDelete, currentIndex + 1, onComplete);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "✗ Failed to delete marker: " + marker.getName() + " - " + error);
                        deleteMarkersForFloor(fullMapName, markersToDelete, currentIndex + 1, onComplete);
                    }
                });
    }

    private void uploadPointsToServer(String fullMapName, String floorStr, List<Position> points,
                                      FloorUploadCallback callback) {
        if (points.isEmpty()) {
            Log.d(TAG, "uploadPointsToServer: No points to upload for " + fullMapName);
            if (callback != null) {
                callback.onComplete(0);
            }
            return;
        }

        Log.d(TAG, "uploadPointsToServer: Uploading " + points.size() + " points to " + fullMapName);
        uploadNextPoint(fullMapName, floorStr, points, 0, points.size(), 0, callback);
    }

    /**
     * Update elevator points by elevator configuration, preserving other elevators' existing points.
     *
     * @param existingPoints The existing points list for this type
     * @param updatePoints The update points for this type
     * @param floorPoints The floor points container
     * @param listName The name of the list being updated (for logging)
     * @param currentMapName The current map name
     * @param buildingId The building ID
     * @param currentMapFloor The current floor number
     * @return true if points were changed, false otherwise
     */
    private boolean updateElevatorPointsByConfig(List<Position> existingPoints,
                                                 List<Position> updatePoints,
                                                 FloorPoints floorPoints,
                                                 String listName,
                                                 String currentMapName,
                                                 String buildingId,
                                                 int currentMapFloor) {
        if (updatePoints == null || updatePoints.isEmpty()) {
            // If update list is empty, keep existing points (don't clear)
            Log.d(TAG, "updateElevatorPointsByConfig: " + listName + " - update list is empty, keeping existing points");
            return false;
        }

        // Build a map of elevator configuration IDs to the update points
        // Use elevatorInfo as the key (compare by loraChannel, loraAddress, elevatorId, floorNumber)
        Map<String, Position> updatePointsByConfig = new HashMap<>();
        for (Position pos : updatePoints) {
            if (pos.getElevatorInfo() != null) {
                String configKey = getElevatorConfigKey(pos.getElevatorInfo());
                updatePointsByConfig.put(configKey, pos);
                Log.d(TAG, "updateElevatorPointsByConfig: Update point " + pos.getName() +
                        " with config key: " + configKey);
            } else {
                Log.w(TAG, "updateElevatorPointsByConfig: Update point " + pos.getName() +
                        " has no elevatorInfo, treating as unique configuration");
                // Generate a unique key for points without elevatorInfo
                String fallbackKey = "NO_ELEVATOR_" + pos.getName() + "_" + System.identityHashCode(pos);
                updatePointsByConfig.put(fallbackKey, pos);
            }
        }

        if (updatePointsByConfig.isEmpty()) {
            Log.w(TAG, "updateElevatorPointsByConfig: No valid elevator points to update");
            return false;
        }

        // Create a copy of existing points to work with
        List<Position> newList = new ArrayList<>();

        // First, add all existing points that are NOT in the update configuration
        for (Position existing : existingPoints) {
            boolean shouldKeep = true;

            if (existing.getElevatorInfo() != null) {
                String existingKey = getElevatorConfigKey(existing.getElevatorInfo());
                // Check if this configuration is being updated
                if (updatePointsByConfig.containsKey(existingKey)) {
                    shouldKeep = false;
                    Log.d(TAG, "updateElevatorPointsByConfig: Will replace point " + existing.getName() +
                            " from config " + existingKey);
                } else {
                    Log.d(TAG, "updateElevatorPointsByConfig: Keeping existing point " + existing.getName() +
                            " from config " + existingKey);
                }
            } else {
                // For points without elevatorInfo, check if we're updating them by name
                String fallbackKey = "NO_ELEVATOR_" + existing.getName() + "_" + System.identityHashCode(existing);
                if (updatePointsByConfig.containsKey(fallbackKey)) {
                    shouldKeep = false;
                    Log.d(TAG, "updateElevatorPointsByConfig: Will replace legacy point " + existing.getName());
                } else {
                    Log.d(TAG, "updateElevatorPointsByConfig: Keeping legacy point " + existing.getName());
                }
            }

            if (shouldKeep) {
                newList.add(existing);
            }
        }

        // Now add all update points
        for (Position updatePos : updatePoints) {
            if (updatePos.getElevatorInfo() != null) {
                // Ensure map name is set
                if (updatePos.getMapName() == null) {
                    updatePos.setMapName(MultiBuildingMapPoints.generateMapName(
                            currentMapName, buildingId, currentMapFloor));
                }
                newList.add(updatePos);
                Log.d(TAG, "updateElevatorPointsByConfig: Added/updated point " + updatePos.getName() +
                        " from config " + getElevatorConfigKey(updatePos.getElevatorInfo()));
            } else {
                // For points without elevatorInfo, add them as-is
                if (updatePos.getMapName() == null) {
                    updatePos.setMapName(MultiBuildingMapPoints.generateMapName(
                            currentMapName, buildingId, currentMapFloor));
                }
                newList.add(updatePos);
                Log.d(TAG, "updateElevatorPointsByConfig: Added/updated legacy point " + updatePos.getName());
            }
        }

        // Check if the list actually changed
        boolean changed = listsDiffer(existingPoints, newList);

        // Update the appropriate list in floorPoints
        if (changed) {
            switch (listName) {
                case "elevatorPreridePoints":
                    floorPoints.setElevatorPreridePoints(newList);
                    break;
                case "elevatorWaitPoints":
                    floorPoints.setElevatorWaitPoints(newList);
                    break;
                case "elevatorRidePoints":
                    floorPoints.setElevatorRidePoints(newList);
                    break;
                case "elevatorTransitionPoints":
                    floorPoints.setElevatorTransitionPoints(newList);
                    break;
                default:
                    Log.e(TAG, "updateElevatorPointsByConfig: Unknown list name: " + listName);
                    return false;
            }
            Log.d(TAG, "updateElevatorPointsByConfig: " + listName + " changed - new size: " + newList.size() +
                    " (was: " + existingPoints.size() + ")");
        } else {
            Log.d(TAG, "updateElevatorPointsByConfig: " + listName + " unchanged");
        }

        return changed;
    }

    /**
     * Generate a unique key for an elevator configuration
     * Uses loraChannel, loraAddress, elevatorId, and floorNumber to create a unique identifier
     */
    private String getElevatorConfigKey(Position.ElevatorInfo elevatorInfo) {
        if (elevatorInfo == null) {
            return "";
        }
        // Use loraChannel, loraAddress, elevatorId, and floorNumber to uniquely identify
        return elevatorInfo.getLoraChannel() + "_" +
                elevatorInfo.getLoraAddress() + "_" +
                (elevatorInfo.getElevatorId() != null ? elevatorInfo.getElevatorId() : "unknown") + "_" +
                elevatorInfo.getFloorNumber();
    }

    /**
     * Check if two lists of Positions are different
     */
    private boolean listsDiffer(List<Position> list1, List<Position> list2) {
        if (list1 == null && list2 == null) {
            return false;
        }
        if (list1 == null || list2 == null) {
            return true;
        }
        if (list1.size() != list2.size()) {
            return true;
        }

        // Check by position content (name and coordinates)
        Set<String> set1 = new HashSet<>();
        for (Position p : list1) {
            set1.add(getPositionComparisonKey(p));
        }

        Set<String> set2 = new HashSet<>();
        for (Position p : list2) {
            set2.add(getPositionComparisonKey(p));
        }

        return !set1.equals(set2);
    }

    /**
     * Generate a comparison key for a Position object
     * Used to check if two positions are equivalent
     */
    private String getPositionComparisonKey(Position pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getName() + "|" +
                pos.getPosX() + "|" +
                pos.getPosY() + "|" +
                pos.getYaw() + "|" +
                pos.getType() + "|" +
                (pos.getElevatorInfo() != null ? getElevatorConfigKey(pos.getElevatorInfo()) : "NO_ELEVATOR");
    }

    private void uploadNextPoint(String fullMapName, String floorStr, List<Position> points,
                                 int currentIndex, int totalPoints, int failureCount,
                                 FloorUploadCallback callback) {
        if (currentIndex >= points.size()) {
            Log.d(TAG, "uploadNextPoint: All points uploaded for " + fullMapName + ". Failures: " + failureCount);
            if (callback != null) {
                callback.onComplete(failureCount);
            }
            return;
        }

        Position position = points.get(currentIndex);
        if (position == null || position.getName() == null || position.getName().isEmpty()) {
            Log.w(TAG, "uploadNextPoint: Skipping invalid point at index " + currentIndex);
            uploadNextPoint(fullMapName, floorStr, points, currentIndex + 1, totalPoints,
                    failureCount + 1, callback);
            return;
        }

        position.setMapName(fullMapName);

        Log.d(TAG, "uploadNextPoint: Uploading " + position.getName() + " (" + (currentIndex + 1) +
                "/" + totalPoints + ") to " + fullMapName);

        commandClient.addMarkerAsync(
                fullMapName,
                position.getName(),
                floorStr,
                position.getPosX(),
                position.getPosY(),
                position.getYaw(),
                position.getType(),
                position.getElevatorInfo(),
                new CommandWebSocketClient.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        Log.d(TAG, "✓ Uploaded: " + position.getName());
                        uploadNextPoint(fullMapName, floorStr, points, currentIndex + 1,
                                totalPoints, failureCount, callback);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "✗ Failed to upload: " + position.getName() + " - " + error);
                        uploadNextPoint(fullMapName, floorStr, points, currentIndex + 1,
                                totalPoints, failureCount + 1, callback);
                    }
                });
    }

    private interface FloorUploadCallback {
        void onComplete(int failureCount);
    }

    // --------------------------------------------------------------------------------------------
    // ************ download currentMapPoints (all buildings and floors) ******************

    public void downloadCurrentMapPoints(String currentMapName, boolean isManual,
                                         CommandWebSocketClient.MarkerDataCallback callback) {
        if (currentMapName == null || currentMapName.isEmpty()) {
            String errorMsg = "downloadCurrentMapPoints: currentMapName is null or empty";
            Log.e("taskDebug7", "[AUTO-DL] " + errorMsg);
            if (callback != null) {
                callback.onError(errorMsg);
            }
            return;
        }

        Log.i("taskDebug7", "[AUTO-DL] downloadCurrentMapPoints: map=" + currentMapName +
                ", isManual=" + isManual + ", isConfigDataLoaded=" + isConfigDataLoaded);

        if (isManual) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_downloading_points), Toast.LENGTH_SHORT).show()
            );
        }

        // Step 1: Get all available maps from server
        commandClient.getAllMapsAsync(new CommandWebSocketClient.MapListCallback() {
            @Override
            public void onSuccess(List<String> mapNames) {
                Log.d(TAG, "downloadCurrentMapPoints: Got " + mapNames.size() + " total maps from server");

                // Step 2: Filter maps that match the current map prefix
                List<String> matchedMaps = new ArrayList<>();
                for (String mapName : mapNames) {
                    String mapPrefix = extractMapPrefix(mapName);
                    if (currentMapName.equals(mapPrefix)) {
                        matchedMaps.add(mapName);
                    }
                }

                Log.d(TAG, "downloadCurrentMapPoints: Found " + matchedMaps.size() + " maps matching prefix '" + currentMapName + "'");

                if (matchedMaps.isEmpty()) {
                    // No maps found for this prefix
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (isManual) {
                            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_no_floor_data_for_map, currentMapName), Toast.LENGTH_SHORT).show();
                        }
                        if (callback != null) {
                            callback.onSuccess(new MarkerData());
                        }
                    });
                    return;
                }

                // Step 3: Download markers for each matched map
                downloadMarkersForMaps(matchedMaps, currentMapName, isManual, callback);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "downloadCurrentMapPoints: Failed to get map list: " + error);
                if (isManual) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(getApplication(), LocaleHelper.onServiceGetString(getApplication(), R.string.map_get_map_list_failed, error), Toast.LENGTH_SHORT).show()
                    );
                }
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    private void downloadMarkersForMaps(List<String> mapNames, String targetMapPrefix,
                                        boolean isManual, CommandWebSocketClient.MarkerDataCallback callback) {
        // Map to store building -> floor -> markers
        Map<String, Map<Integer, List<MarkerPoint>>> buildingFloorMarkersMap = new HashMap<>();
        Set<String> processedMaps = new HashSet<>();
        int totalMapsToProcess = mapNames.size();

        // For tracking progress
        class ProgressTracker {
            int completed = 0;
            int totalMaps = totalMapsToProcess;

            synchronized void increment() {
                completed++;
                Log.d(TAG, "Progress: " + completed + "/" + totalMaps + " maps processed");
            }

            synchronized boolean isComplete() {
                return completed >= totalMaps;
            }
        }

        ProgressTracker progress = new ProgressTracker();

        // Process each map name sequentially
        processNextMap(mapNames, 0, buildingFloorMarkersMap, processedMaps, progress,
                targetMapPrefix, isManual, callback);
    }

    private void processNextMap(List<String> mapNames, int index,
                                Map<String, Map<Integer, List<MarkerPoint>>> buildingFloorMarkersMap,
                                Set<String> processedMaps,
                                Object progressTracker, // Use a simple counter instead
                                String targetMapPrefix, boolean isManual,
                                CommandWebSocketClient.MarkerDataCallback callback) {

        if (index >= mapNames.size()) {
            // All maps processed, build the result
            buildResultFromMarkers(buildingFloorMarkersMap, processedMaps, targetMapPrefix, isManual, callback);
            return;
        }

        String fullMapName = mapNames.get(index);
        String buildingId = extractBuildingId(fullMapName);
        int floor = extractMapFloor(fullMapName);
        String mapKey = buildingId + "_" + floor;

        // Mark this map as existing (even if it has no markers)
        processedMaps.add(mapKey);

        // Download markers for this specific map
        commandClient.getMapMarkersAsync(fullMapName, new CommandWebSocketClient.MarkerDataCallback() {
            @Override
            public void onSuccess(MarkerData markerData) {
                Log.d(TAG, "Downloaded markers for map: " + fullMapName +
                        ", markers count: " + (markerData != null && markerData.getData() != null ?
                        markerData.getData().size() : 0));

                // Store markers if any exist
                if (markerData != null && markerData.getData() != null && !markerData.getData().isEmpty()) {
                    buildingFloorMarkersMap
                            .computeIfAbsent(buildingId, k -> new HashMap<>())
                            .computeIfAbsent(floor, k -> new ArrayList<>())
                            .addAll(markerData.getData());
                }
                // Even if no markers, we still recorded the map existence in processedMaps

                // Process next map
                processNextMap(mapNames, index + 1, buildingFloorMarkersMap, processedMaps,
                        progressTracker, targetMapPrefix, isManual, callback);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to download markers for map: " + fullMapName + " - " + error);
                // Still continue with next map, but don't mark this map as having markers
                processNextMap(mapNames, index + 1, buildingFloorMarkersMap, processedMaps,
                        progressTracker, targetMapPrefix, isManual, callback);
            }
        });
    }

    private void buildResultFromMarkers(Map<String, Map<Integer, List<MarkerPoint>>> buildingFloorMarkersMap,
                                        Set<String> processedMaps,
                                        String targetMapPrefix,
                                        boolean isManual,
                                        CommandWebSocketClient.MarkerDataCallback callback) {

        // Create new MultiBuildingMapPoints
        MultiBuildingMapPoints newMapPoints = new MultiBuildingMapPoints(targetMapPrefix);
        int totalMarkers = 0;
        int totalBuildingFloorsWithMarkers = 0;

        // First, add all floors that have markers
        for (Map.Entry<String, Map<Integer, List<MarkerPoint>>> buildingEntry : buildingFloorMarkersMap.entrySet()) {
            String buildingId = buildingEntry.getKey();
            Building building = new Building(buildingId, targetMapPrefix);

            for (Map.Entry<Integer, List<MarkerPoint>> floorEntry : buildingEntry.getValue().entrySet()) {
                int floor = floorEntry.getKey();
                List<MarkerPoint> markers = floorEntry.getValue();
                totalMarkers += markers.size();
                totalBuildingFloorsWithMarkers++;

                FloorPoints floorPoints = convertMarkersToFloorPoints(markers, targetMapPrefix, buildingId, floor);
                building.setFloorPoints(floor, floorPoints);
                Log.d(TAG, "Added building " + buildingId + ", floor " + floor +
                        " with " + markers.size() + " points");
            }

            newMapPoints.addBuilding(building);
        }

        // Second, add all floors that exist on server but have NO markers (from processedMaps)
        for (String mapKey : processedMaps) {
            String[] parts = mapKey.split("_");
            if (parts.length == 2) {
                String buildingId = parts[0];
                int floor = Integer.parseInt(parts[1]);

                // Check if this floor already has markers
                boolean hasMarkers = buildingFloorMarkersMap.containsKey(buildingId)
                        && buildingFloorMarkersMap.get(buildingId).containsKey(floor);

                if (isManual && !hasMarkers) {
                    // 仅手动下载时为"服务器存在但无点位"的楼层创建空对象
                    // 自动下载时跳过，由下方合并逻辑保留本地数据，避免无声清空用户点位
                    Building building = newMapPoints.getBuilding(buildingId);
                    if (building == null) {
                        building = new Building(buildingId, targetMapPrefix);
                        newMapPoints.addBuilding(building);
                    }

                    // Create empty FloorPoints for this floor
                    String floorMapName = MultiBuildingMapPoints.generateMapName(targetMapPrefix, buildingId, floor);
                    FloorPoints emptyFloorPoints = new FloorPoints(floorMapName);
                    building.setFloorPoints(floor, emptyFloorPoints);
                    Log.d(TAG, "Created empty FloorPoints for building " + buildingId +
                            ", floor " + floor + " (exists on server but has no points)");
                }
            }
        }

        // === 自动下载时合并本地数据，避免整批覆盖导致点位丢失 ===
        // 手动下载是用户明确要"用机器人数据覆盖本地"，保持原整批替换语义
        // 自动下载是切地图/切楼层/重连时无声触发，只应更新机器人返回的楼层，保留本地其他数据
        if (!isManual) {
            MultiBuildingMapPoints existing = currentMapPoints.getValue();
            int existingBuildings = existing != null ? existing.getAllBuildings().size() : 0;
            int existingPoints = existing != null ? existing.getTotalPositionCount() : 0;
            int newBuildings = newMapPoints.getAllBuildings().size();
            int newPoints = newMapPoints.getTotalPositionCount();
            Log.i("taskDebug7", "[AUTO-DL] MERGE START: existing(b=" + existingBuildings +
                    ",p=" + existingPoints + ") vs downloaded(b=" + newBuildings + ",p=" + newPoints + ")");
            if (existing != null) {
                for (Building existingBuilding : existing.getAllBuildings().values()) {
                    Building targetBuilding = newMapPoints.getBuilding(existingBuilding.getBuildingId());
                    if (targetBuilding == null) {
                        // 机器人未返回该楼宇，保留本地整个楼宇
                        newMapPoints.addBuilding(existingBuilding);
                        Log.i("taskDebug7", "[AUTO-DL] MERGE: kept local building " + existingBuilding.getBuildingId());
                    } else {
                        // 机器人返回了该楼宇，保留机器人未返回的楼层
                        for (Map.Entry<Integer, FloorPoints> fe : existingBuilding.getAllFloorPoints().entrySet()) {
                            if (targetBuilding.getFloorPoints(fe.getKey()) == null) {
                                targetBuilding.setFloorPoints(fe.getKey(), fe.getValue());
                                Log.i("taskDebug7", "[AUTO-DL] MERGE: kept local floor " + fe.getKey() +
                                        " of building " + existingBuilding.getBuildingId());
                            }
                        }
                    }
                }
            }
            Log.i("taskDebug7", "[AUTO-DL] MERGE END: final buildings=" + newMapPoints.getAllBuildings().size() +
                    ", totalPoints=" + newMapPoints.getTotalPositionCount());
        } else {
            Log.i("taskDebug7", "[AUTO-DL] MANUAL download, NO merge (整批替换): buildings=" +
                    newMapPoints.getAllBuildings().size() + ", totalPoints=" + newMapPoints.getTotalPositionCount());
        }

        int finalTotalMarkers = totalMarkers;
        int finalTotalBuildings = newMapPoints.getAllBuildings().size();
        int finalTotalFloors = processedMaps.size();

        Log.i("taskDebug7", "[AUTO-DL] buildResult: markers=" + finalTotalMarkers +
                ", buildings=" + finalTotalBuildings + ", floors=" + finalTotalFloors +
                ", isManual=" + isManual);

        new Handler(Looper.getMainLooper()).post(() -> {
            Log.i("taskDebug7", "[AUTO-DL] setValue (downloaded result): buildings=" + finalTotalBuildings +
                    ", totalPoints=" + newMapPoints.getTotalPositionCount() + ", isManual=" + isManual);
            currentMapPoints.setValue(newMapPoints);
            saveCurrentMapPoints();

            if (isManual) {
                String message;
                if (finalTotalMarkers == 0 && finalTotalFloors > 0) {
                    message = LocaleHelper.onServiceGetString(getApplication(), R.string.map_download_floors_no_points, finalTotalFloors);
                } else if (finalTotalMarkers == 0) {
                    message = LocaleHelper.onServiceGetString(getApplication(), R.string.map_download_no_points_on_map);
                } else {
                    message = LocaleHelper.onServiceGetString(getApplication(), R.string.map_download_success,
                            finalTotalMarkers, finalTotalBuildings, finalTotalFloors);
                }
                Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show();
            }

            if (callback != null) {
                callback.onSuccess(new MarkerData());
            }
        });
    }

    private FloorPoints convertMarkersToFloorPoints(List<MarkerPoint> markers, String mapPrefix, String buildingId, int floor) {
        String fullMapName = MultiBuildingMapPoints.generateMapName(mapPrefix, buildingId, floor);
        FloorPoints floorPoints = new FloorPoints(fullMapName);

        // Initialize with empty collections
        List<Position> workPoints = new ArrayList<>();
        List<Position> elevatorPreridePoints = new ArrayList<>();
        List<Position> elevatorWaitPoints = new ArrayList<>();
        List<Position> elevatorRidePoints = new ArrayList<>();
        List<Position> elevatorTransitionPoints = new ArrayList<>();
        Position chargePoint = null;
        Position preChargePoint = null;
        Position parkPoint = null;
        Position relocalizePoint = null;
        Position pharmacyPoint = null;
        Position pivasPoint = null;

        // Only process markers if we have them
        if (markers != null && !markers.isEmpty()) {
            for (MarkerPoint marker : markers) {
                Position position = new Position();
                position.setId(marker.getId());
                position.setName(marker.getName());
                position.setPosX(marker.getX());
                position.setPosY(marker.getY());
                position.setYaw(marker.getYaw());
                position.setType(marker.getType());
                position.setFloor(marker.getFloor());
                position.setMapName(fullMapName);
                position.setTaskType(marker.getType() == 2 || marker.getType() == 3 || marker.getType() == 4 || marker.getType() == 14 ? 0 : 1);

                if (marker.getType() == 10) {
                    position.setSpecialType(SPECIAL_TYPE_CHARGE);
                } else if (marker.getType() == 11) {
                    position.setSpecialType(SPECIAL_TYPE_PRECHARGE);
                } else if (marker.getType() == 12) {
                    position.setSpecialType(SPECIAL_TYPE_HOME);
                } else if (marker.getType() == 13) {
                    position.setSpecialType(SPECIAL_TYPE_RELOCALIZE);
                } else if (marker.getType() == 15) {
                    position.setSpecialType(SPECIAL_TYPE_PHARMACY);
                } else if (marker.getType() == 16) {
                    position.setSpecialType(SPECIAL_TYPE_PIVAS);
                } else {
                    position.setSpecialType(-1);
                }

                switch (marker.getType()) {
                    case 1:
                        workPoints.add(position);
                        break;
                    case 2:
                        elevatorPreridePoints.add(position);
                        break;
                    case 3:
                        elevatorWaitPoints.add(position);
                        break;
                    case 4:
                        elevatorRidePoints.add(position);
                        break;
                    case 10:
                        chargePoint = position;
                        break;
                    case 11:
                        preChargePoint = position;
                        break;
                    case 12:
                        parkPoint = position;
                        break;
                    case 13:
                        relocalizePoint = position;
                        break;
                    case 14:
                        elevatorTransitionPoints.add(position);
                        break;
                    case 15:
                        pharmacyPoint = position;
                        break;
                    case 16:
                        pivasPoint = position;
                        break;
                    default:
                        workPoints.add(position);
                        break;
                }
            }
        }

        // Set all point types (including empty lists for those without points)
        floorPoints.setWorkPoints(workPoints);
        floorPoints.setElevatorPreridePoints(elevatorPreridePoints);
        floorPoints.setElevatorWaitPoints(elevatorWaitPoints);
        floorPoints.setElevatorRidePoints(elevatorRidePoints);
        floorPoints.setChargePoint(chargePoint);
        floorPoints.setPreChargePoint(preChargePoint);
        floorPoints.setParkPoint(parkPoint);
        floorPoints.setRelocalizePoint(relocalizePoint);
        floorPoints.setElevatorTransitionPoints(elevatorTransitionPoints);
        floorPoints.setPharmacyPoint(pharmacyPoint);
        floorPoints.setPivasPoint(pivasPoint);

        return floorPoints;
    }

    // --------------------------------------------------------------------------------------------
    // ************ special points management (updated for multi-building) ******************

    public Position getChargePosition(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        Position chargePoint = floorPoints.getChargePoint();
        // Filter out zero coordinate positions
        if (chargePoint != null && isZeroCoordinate(chargePoint)) {
            Log.w(TAG, "getChargePosition: Charge point for building " + buildingId +
                    " floor " + floor + " has zero coordinates, returning null");
            return null;
        }
        return chargePoint;
    }

    public Position getChargePosition(int floor) {
        return getChargePosition(getCurrentBuilding(), floor);
    }

    public Position getPreChargePosition(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        Position preChargePoint = floorPoints.getPreChargePoint();
        // Filter out zero coordinate positions
        if (preChargePoint != null && isZeroCoordinate(preChargePoint)) {
            Log.w(TAG, "getPreChargePosition: Pre-charge point for building " + buildingId +
                    " floor " + floor + " has zero coordinates, returning null");
            return null;
        }
        return preChargePoint;
    }

    public Position getPreChargePosition(int floor) {
        return getPreChargePosition(getCurrentBuilding(), floor);
    }

    public Position getParkPosition(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        Position parkPoint = floorPoints.getParkPoint();
        // Filter out zero coordinate positions
        if (parkPoint != null && isZeroCoordinate(parkPoint)) {
            Log.w(TAG, "getParkPosition: Park point for building " + buildingId +
                    " floor " + floor + " has zero coordinates, returning null");
            return null;
        }
        return parkPoint;
    }

    public Position getParkPosition(int floor) {
        return getParkPosition(getCurrentBuilding(), floor);
    }

    public Position getRelocalizePosition(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        Position relocalizePoint = floorPoints.getRelocalizePoint();
        // Filter out zero coordinate positions
        if (relocalizePoint != null && isZeroCoordinate(relocalizePoint)) {
            Log.w(TAG, "getRelocalizePosition: Relocalize point for building " + buildingId +
                    " floor " + floor + " has zero coordinates, returning null");
            return null;
        }
        return relocalizePoint;
    }

    public Position getRelocalizePosition(int floor) {
        return getRelocalizePosition(getCurrentBuilding(), floor);
    }

    public Position getPharmacyPosition(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        Position pharmacyPoint = floorPoints.getPharmacyPoint();
        // Filter out zero coordinate positions
        if (pharmacyPoint != null && isZeroCoordinate(pharmacyPoint)) {
            Log.w(TAG, "getPharmacyPosition: Pharmacy point for building " + buildingId +
                    " floor " + floor + " has zero coordinates, returning null");
            return null;
        }
        return pharmacyPoint;
    }

    public Position getPivasPosition(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        Position pivasPoint = floorPoints.getPivasPoint();
        // Filter out zero coordinate positions
        if (pivasPoint != null && isZeroCoordinate(pivasPoint)) {
            Log.w(TAG, "getPivasPosition: Pivas point for building " + buildingId +
                    " floor " + floor + " has zero coordinates, returning null");
            return null;
        }
        return pivasPoint;
    }

    public Map<Integer, Position> getAllChargePoints(String buildingId) {
        Map<Integer, Position> chargePoints = new HashMap<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return chargePoints;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return chargePoints;

        for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();

            if (floorPoints != null && floorPoints.getChargePoint() != null) {
                Position chargePoint = floorPoints.getChargePoint();
                // Filter out zero coordinate positions
                if (isPositionValid(chargePoint) && !isZeroCoordinate(chargePoint)) {
                    chargePoints.put(floor, chargePoint);
                } else {
                    Log.d(TAG, "getAllChargePoints: Skipping invalid charge point on floor " + floor);
                }
            }
        }

        return chargePoints;
    }

    public Map<Integer, Position> getAllParkPoints(String buildingId) {
        Map<Integer, Position> parkPoints = new HashMap<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return parkPoints;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return parkPoints;

        for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();

            if (floorPoints != null && floorPoints.getParkPoint() != null) {
                Position parkPoint = floorPoints.getParkPoint();
                // Filter out zero coordinate positions
                if (isPositionValid(parkPoint) && !isZeroCoordinate(parkPoint)) {
                    parkPoints.put(floor, parkPoint);
                } else {
                    Log.d(TAG, "getAllParkPoints: Skipping invalid park point on floor " + floor);
                }
            }
        }

        return parkPoints;
    }

    public Map<Integer, Position> getAllPharmacyPoints(String buildingId) {
        Map<Integer, Position> pharmacyPoints = new HashMap<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return pharmacyPoints;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return pharmacyPoints;

        for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();

            if (floorPoints != null && floorPoints.getPharmacyPoint() != null) {
                Position pharmacyPoint = floorPoints.getPharmacyPoint();
                // Filter out zero coordinate positions
                if (isPositionValid(pharmacyPoint) && !isZeroCoordinate(pharmacyPoint)) {
                    pharmacyPoints.put(floor, pharmacyPoint);
                } else {
                    Log.d(TAG, "getAllPharmacyPoints: Skipping invalid pharmacy point on floor " + floor);
                }
            }
        }

        return pharmacyPoints;
    }

    public Map<Integer, Position> getAllPivasPoints(String buildingId) {
        Map<Integer, Position> pivasPoints = new HashMap<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return pivasPoints;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return pivasPoints;

        for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();

            if (floorPoints != null && floorPoints.getPivasPoint() != null) {
                Position pivasPoint = floorPoints.getPivasPoint();
                // Filter out zero coordinate positions
                if (isPositionValid(pivasPoint) && !isZeroCoordinate(pivasPoint)) {
                    pivasPoints.put(floor, pivasPoint);
                } else {
                    Log.d(TAG, "getAllPivasPoints: Skipping invalid pivas point on floor " + floor);
                }
            }
        }

        return pivasPoints;
    }

    /**
     * Find the nearest floor that has a valid charge point
     * @param currentFloor The current floor number
     * @return The floor number of the nearest charge point, or currentFloor if none found
     */
    public int findNearestFloorWithChargePoint(String buildingId, int currentFloor) {
        Map<Integer, Position> chargePoints = getAllChargePoints(buildingId);

        if (chargePoints.isEmpty()) {
            Log.w(TAG, "findNearestFloorWithChargePoint: No charge points found in any floor");
            return currentFloor;
        }

        // If current floor has a charge point, return it
        if (chargePoints.containsKey(currentFloor)) {
            return currentFloor;
        }

        // Find the nearest floor with a charge point
        return findNearestFloor(currentFloor, chargePoints.keySet());
    }

    /**
     * Find the nearest floor that has a valid park point
     * @param currentFloor The current floor number
     * @return The floor number of the nearest park point, or currentFloor if none found
     */
    public int findNearestFloorWithParkPoint(String buildingId, int currentFloor) {
        Map<Integer, Position> parkPoints = getAllParkPoints(buildingId);

        if (parkPoints.isEmpty()) {
            Log.w(TAG, "findNearestFloorWithParkPoint: No park points found in any floor");
            return currentFloor;
        }

        // If current floor has a park point, return it
        if (parkPoints.containsKey(currentFloor)) {
            return currentFloor;
        }

        // Find the nearest floor with a park point
        return findNearestFloor(currentFloor, parkPoints.keySet());
    }

    /**
     * Helper method to find the nearest floor from a set of available floors
     * @param currentFloor The current floor number
     * @param availableFloors Set of floors that have the required point
     * @return The nearest floor number
     */
    private int findNearestFloor(int currentFloor, Set<Integer> availableFloors) {
        int nearestFloor = currentFloor;
        int minDistance = Integer.MAX_VALUE;

        for (int floor : availableFloors) {
            int distance = Math.abs(floor - currentFloor);
            if (distance < minDistance) {
                minDistance = distance;
                nearestFloor = floor;
            }
        }

        Log.d(TAG, "findNearestFloor: Current floor=" + currentFloor +
                ", nearest floor with point=" + nearestFloor + ", distance=" + minDistance);

        return nearestFloor;
    }

    /**
     * Get charge point for the nearest floor that has one
     * @param currentFloor The current floor number
     * @return The charge point from the nearest floor, or null if none found
     */
    public Position getNearestChargePoint(String buildingId, int currentFloor) {
        int targetFloor = findNearestFloorWithChargePoint(buildingId, currentFloor);

        if (targetFloor != currentFloor) {
            Log.d(TAG, "getNearestChargePoint: Current floor " + currentFloor +
                    " has no charge point, using floor " + targetFloor);
        }

        return getChargePosition(buildingId, targetFloor);
    }

    /**
     * Get park point for the nearest floor that has one
     * @param currentFloor The current floor number
     * @return The park point from the nearest floor, or null if none found
     */
    public Position getNearestParkPoint(String buildingId, int currentFloor) {
        int targetFloor = findNearestFloorWithParkPoint(buildingId, currentFloor);

        if (targetFloor != currentFloor) {
            Log.d(TAG, "getNearestParkPoint: Current floor " + currentFloor +
                    " has no park point, using floor " + targetFloor);
        }

        return getParkPosition(buildingId, targetFloor);
    }

    public List<Position> getElevatorPreridePoints(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return new ArrayList<>();

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return new ArrayList<>();

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return new ArrayList<>();

        List<Position> preridePoints = floorPoints.getElevatorPreridePoints();
        List<Position> filteredPoints = new ArrayList<>();
        if (preridePoints != null) {
            for (Position point : preridePoints) {
                if (point != null && !isZeroCoordinate(point)) {
                    filteredPoints.add(point);
                }
            }
        }
        return filteredPoints;
    }

    public List<Position> getElevatorWaitPoints(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return new ArrayList<>();

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return new ArrayList<>();

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return new ArrayList<>();

        List<Position> waitPoints = floorPoints.getElevatorWaitPoints();
        List<Position> filteredPoints = new ArrayList<>();
        if (waitPoints != null) {
            for (Position point : waitPoints) {
                if (point != null && !isZeroCoordinate(point)) {
                    filteredPoints.add(point);
                }
            }
        }
        return filteredPoints;
    }

    public List<Position> getElevatorRidePoints(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return new ArrayList<>();

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return new ArrayList<>();

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return new ArrayList<>();

        List<Position> ridePoints = floorPoints.getElevatorRidePoints();
        List<Position> filteredPoints = new ArrayList<>();
        if (ridePoints != null) {
            for (Position point : ridePoints) {
                if (point != null && !isZeroCoordinate(point)) {
                    filteredPoints.add(point);
                }
            }
        }
        return filteredPoints;
    }

    public List<Position> getElevatorTransitionPoints(String buildingId, int floor) {
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return new ArrayList<>();

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return new ArrayList<>();

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return new ArrayList<>();

        List<Position> transitionPoints = floorPoints.getElevatorTransitionPoints();
        List<Position> filteredPoints = new ArrayList<>();
        if (transitionPoints != null) {
            for (Position point : transitionPoints) {
                if (point != null && !isZeroCoordinate(point)) {
                    filteredPoints.add(point);
                }
            }
        }
        return filteredPoints;
    }

    public List<Position> getElevatorTransitionPoints(int floor) {
        return getElevatorTransitionPoints(getCurrentBuilding(), floor);
    }

    public List<Position> getAllElevatorTransitionPointsAsList() {
        List<Position> allTransitionPoints = new ArrayList<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return allTransitionPoints;

        for (Map.Entry<String, Building> buildingEntry : mapPoints.getAllBuildings().entrySet()) {
            Building building = buildingEntry.getValue();
            for (Map.Entry<Integer, FloorPoints> floorEntry : building.getAllFloorPoints().entrySet()) {
                FloorPoints floorPoints = floorEntry.getValue();
                if (floorPoints != null) {
                    List<Position> transitionPoints = floorPoints.getElevatorTransitionPoints();
                    if (transitionPoints != null && !transitionPoints.isEmpty()) {
                        for (Position point : transitionPoints) {
                            // Filter out points with zero coordinates
                            if (point != null && !isZeroCoordinate(point)) {
                                allTransitionPoints.add(point);
                            }
                        }
                    }
                }
            }
        }

        return allTransitionPoints;
    }

    public List<Position> getAllElevatorPreridePointsAsList() {
        List<Position> allPreridePoints = new ArrayList<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return allPreridePoints;

        for (Map.Entry<String, Building> buildingEntry : mapPoints.getAllBuildings().entrySet()) {
            Building building = buildingEntry.getValue();
            for (Map.Entry<Integer, FloorPoints> floorEntry : building.getAllFloorPoints().entrySet()) {
                FloorPoints floorPoints = floorEntry.getValue();
                if (floorPoints != null) {
                    List<Position> preridePoints = floorPoints.getElevatorPreridePoints();
                    if (preridePoints != null && !preridePoints.isEmpty()) {
                        for (Position point : preridePoints) {
                            // Filter out points with zero coordinates
                            if (point != null && !isZeroCoordinate(point)) {
                                allPreridePoints.add(point);
                            }
                        }
                    }
                }
            }
        }

        return allPreridePoints;
    }

    public List<Position> getAllElevatorWaitPointsAsList() {
        List<Position> allWaitPoints = new ArrayList<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return allWaitPoints;

        for (Map.Entry<String, Building> buildingEntry : mapPoints.getAllBuildings().entrySet()) {
            Building building = buildingEntry.getValue();
            for (Map.Entry<Integer, FloorPoints> floorEntry : building.getAllFloorPoints().entrySet()) {
                FloorPoints floorPoints = floorEntry.getValue();
                if (floorPoints != null) {
                    List<Position> waitPoints = floorPoints.getElevatorWaitPoints();
                    if (waitPoints != null && !waitPoints.isEmpty()) {
                        for (Position point : waitPoints) {
                            // Filter out points with zero coordinates
                            if (point != null && !isZeroCoordinate(point)) {
                                allWaitPoints.add(point);
                            }
                        }
                    }
                }
            }
        }

        return allWaitPoints;
    }

    public List<Position> getAllElevatorRidePointsAsList() {
        List<Position> allRidePoints = new ArrayList<>();
        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();

        if (mapPoints == null) return allRidePoints;

        for (Map.Entry<String, Building> buildingEntry : mapPoints.getAllBuildings().entrySet()) {
            Building building = buildingEntry.getValue();
            for (Map.Entry<Integer, FloorPoints> floorEntry : building.getAllFloorPoints().entrySet()) {
                FloorPoints floorPoints = floorEntry.getValue();
                if (floorPoints != null) {
                    List<Position> ridePoints = floorPoints.getElevatorRidePoints();
                    if (ridePoints != null && !ridePoints.isEmpty()) {
                        for (Position point : ridePoints) {
                            // Filter out points with zero coordinates
                            if (point != null && !isZeroCoordinate(point)) {
                                allRidePoints.add(point);
                            }
                        }
                    }
                }
            }
        }

        return allRidePoints;
    }

     /* Helper method to check if a position has zero coordinates
     * @param position The position to check
     * @return true if position has zero coordinates (0.0, 0.0) or zero yaw, false otherwise
     */
    private boolean isZeroCoordinate(Position position) {
        if (position == null) return true;

        // Check if position has default/zero coordinates
        boolean hasZeroX = Math.abs(position.getPosX()) < 0.001;
        boolean hasZeroY = Math.abs(position.getPosY()) < 0.001;
        boolean hasZeroYaw = Math.abs(position.getYaw()) < 0.001;

        // Consider it a zero coordinate if both X and Y are zero (or very close to zero)
        // Yaw being zero is common for many points, so we don't filter based on yaw alone
        return hasZeroX && hasZeroY;
    }


    // --------------------------------------------------------------------------------------------
    // ************ current building/map/floor management ******************

    private void startCurrentMapMonitoring() {
        if (statusListener == null) {
            statusListener = new StatusWebSocketClient.StatusListener() {
                @Override
                public void onStatusUpdate(AgvStatusResponse response) {
                    if (response != null && response.data != null) {
                        handleCurrentMapUpdate(response);
                        latestStatusResponse = response;
                    }
                }
            };
            Log.d(TAG, "Registering listener with SharedViewModel");
            sharedViewModel.registerStatusListener(statusListener);
        }
    }

    private void handleCurrentMapUpdate(AgvStatusResponse response) {
        if (response == null || response.data == null || response.data.pos == null) {
            Log.w("taskDebug7", "[AUTO-DL] handleCurrentMapUpdate: Invalid response");
            return;
        }

        String fullMapName = response.data.pos.mapName;
        if (fullMapName == null || fullMapName.isEmpty()) {
            Log.w("taskDebug7", "[AUTO-DL] handleCurrentMapUpdate: fullMapName is null or empty");
            return;
        }

        String currentMap = extractMapPrefix(fullMapName);
        String currentBuilding = extractBuildingId(fullMapName);
        int currentFloor = extractMapFloor(fullMapName);

        Log.i("taskDebug7", "[AUTO-DL] handleCurrentMapUpdate: fullMapName='" + fullMapName +
                "', building='" + currentBuilding + "', map='" + currentMap + "', floor=" + currentFloor +
                ", lastKnownMap='" + lastKnownMap + "', isConfigDataLoaded=" + isConfigDataLoaded);

        currentBuildingLiveData.postValue(currentBuilding);
        currentMapLiveData.postValue(currentMap);
        currentFloorLiveData.postValue(currentFloor);

        if (!fullMapName.equals(lastKnownMap)) {
            Log.w("taskDebug7", "[AUTO-DL] Map CHANGED '" + lastKnownMap + "' -> '" + fullMapName +
                    "', triggering auto-download (isManual=false). isConfigDataLoaded=" + isConfigDataLoaded);
            downloadCurrentMapPoints(currentMap, false, new CommandWebSocketClient.MarkerDataCallback() {
                @Override
                public void onSuccess(MarkerData markerData) {
                    Log.i("taskDebug7", "[AUTO-DL] Auto-download completed for map: " + currentMap);
                }

                @Override
                public void onError(String error) {
                    Log.e("taskDebug7", "[AUTO-DL] Auto-download FAILED for map: " + currentMap + " - " + error);
                }
            });
            lastKnownMap = fullMapName;
        }
    }

    public LiveData<MultiBuildingMapPoints> getCurrentMapPoints() {
        return currentMapPoints;
    }

    public LiveData<String> getCurrentBuildingLiveData() {
        return currentBuildingLiveData;
    }

    public LiveData<String> getCurrentMapLiveData() {
        return currentMapLiveData;
    }

    public LiveData<Integer> getCurrentFloorLiveData() {
        return currentFloorLiveData;
    }

    /**
     * Get current building ID (returns "0" for legacy formats)
     */
    public String getCurrentBuilding() {
        if (latestStatusResponse == null || latestStatusResponse.data == null ||
                latestStatusResponse.data.pos == null) {
            return "0";
        }
        String mapName = latestStatusResponse.data.pos.mapName;
        if (mapName == null) return "0";
        String buildingId = extractBuildingId(mapName);
        return buildingId != null ? buildingId : "0";
    }

    /**
     * Get current floor
     */
    public int getCurrentFloor() {
        if (latestStatusResponse == null || latestStatusResponse.data == null ||
                latestStatusResponse.data.pos == null) {
            return 0;
        }
        String mapName = latestStatusResponse.data.pos.mapName;
        if (mapName == null) return 0;
        Integer floor = extractMapFloor(mapName);
        return floor != null ? floor : 0;
    }

    public String getCurrentMap() {
        if (latestStatusResponse == null || latestStatusResponse.data == null ||
                latestStatusResponse.data.pos == null) {
            return "";
        }
        String mapName = latestStatusResponse.data.pos.mapName;
        if (mapName == null) return "";
        String prefix = extractMapPrefix(mapName);
        return prefix != null ? prefix : "";
    }

    // --------------------------------------------------------------------------------------------
    // ************ helper methods ******************

    /**
     * Extract building ID from map name
     * - "test13_11" -> "13" (multi-building: prefix + numeric buildingId + _ + floor)
     * - "Test_12" -> "0" (legacy two-part: prefix_floor)
     * - "Test26" -> "0" (legacy one-part: prefix only)
     * - "building5_3" -> "5" (multi-building)
     */
    public String extractBuildingId(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return "0";
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix + numericBuildingId + _ + floor
        // Example: "test13_11" -> prefix="test", buildingId="13", floor="11"
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            String buildingId = multiMatcher.group(2);
            Log.d(TAG, "extractBuildingId: Multi-building format, buildingId='" + buildingId +
                    "' from mapName='" + fullMapName + "'");
            return buildingId;
        }

        // Check legacy two-part format: prefix_floor
        // Example: "Test_12" -> returns "0"
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            Log.d(TAG, "extractBuildingId: Legacy two-part format, returning '0' from mapName='" + fullMapName + "'");
            return "0";
        }

        // Legacy one-part format or any other format
        Log.d(TAG, "extractBuildingId: Legacy format, returning '0' from mapName='" + fullMapName + "'");
        return "0";
    }

    /**
     * Extract map prefix from map name
     * - "test13_11" -> "test"
     * - "Test_12" -> "Test"
     * - "Test26" -> "Test"
     */
    public String extractMapPrefix(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return "";
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix + numericBuildingId + _ + floor
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            String prefix = multiMatcher.group(1);
            Log.d(TAG, "extractMapPrefix: Multi-building format, prefix='" + prefix +
                    "' from mapName='" + fullMapName + "'");
            return prefix;
        }

        // Check legacy two-part format: prefix_floor
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            String prefix = twoPartMatcher.group(1);
            Log.d(TAG, "extractMapPrefix: Legacy two-part format, prefix='" + prefix +
                    "' from mapName='" + fullMapName + "'");
            return prefix;
        }

        // Legacy one-part format: extract alphabetic prefix
        Matcher onePartMatcher = LEGACY_ONE_PART_PATTERN.matcher(fullMapName);
        if (onePartMatcher.matches()) {
            String prefix = onePartMatcher.group(1);
            Log.d(TAG, "extractMapPrefix: Legacy one-part format, prefix='" + prefix +
                    "' from mapName='" + fullMapName + "'");
            return prefix;
        }

        // Return as is if no pattern matches
        Log.w(TAG, "extractMapPrefix: Could not parse prefix from '" + fullMapName + "', returning full name");
        return fullMapName;
    }

    /**
     * Extract floor number from map name
     * - "test13_11" -> 11
     * - "Test_12" -> 12
     * - "Test26" -> 0
     */
    public Integer extractMapFloor(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return 0;
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix + numericBuildingId + _ + floor
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            try {
                int floor = Integer.parseInt(multiMatcher.group(3));
                Log.d(TAG, "extractMapFloor: Multi-building format, floor=" + floor +
                        " from mapName='" + fullMapName + "'");
                return floor;
            } catch (NumberFormatException e) {
                Log.e(TAG, "extractMapFloor: Failed to parse floor number", e);
            }
        }

        // Check legacy two-part format: prefix_floor
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            try {
                int floor = Integer.parseInt(twoPartMatcher.group(2));
                Log.d(TAG, "extractMapFloor: Legacy two-part format, floor=" + floor +
                        " from mapName='" + fullMapName + "'");
                return floor;
            } catch (NumberFormatException e) {
                Log.e(TAG, "extractMapFloor: Failed to parse floor from two-part format", e);
            }
        }

        // Legacy one-part format has no floor
        Log.d(TAG, "extractMapFloor: Legacy format, returning 0 from mapName='" + fullMapName + "'");
        return 0;
    }

    /**
     * Generate map name from components
     * - If buildingId is "0": returns "mapPrefix_floor" (legacy two-part format)
     * - Otherwise: returns "mapPrefixbuildingId_floor" (multi-building format)
     */
    public static String generateMapName(String mapPrefix, String buildingId, int floor) {
        if ("0".equals(buildingId) || "default".equals(buildingId)) {
            return mapPrefix + "_" + floor;
        }
        return mapPrefix + buildingId + "_" + floor;
    }

    private boolean isPositionValid(Position position) {
        if (position == null) return false;
        boolean isDefaultPosition = position.getPosX() == 0.000 &&
                position.getPosY() == 0.000 &&
                position.getYaw() == 0.000;
        boolean hasValidMap = position.getMapName() != null && !position.getMapName().isEmpty();
        return !isDefaultPosition && hasValidMap;
    }

    private double calculateDistance(Position p1, Position p2) {
        double dx = p1.getPosX() - p2.getPosX();
        double dy = p1.getPosY() - p2.getPosY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private String removeFileExtension(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            return "";
        }
        return mapName.replaceFirst("\\.[^.]+$", "");
    }

    public void showNotCurrentFloorToast(Context context) {
        Toast.makeText(context, R.string.Unable_to_operate_in_a_current_map_or_floor, Toast.LENGTH_SHORT).show();
    }

    public boolean checkIfAtChargePosition(Position currentPosition) {
        final double DISTANCE_THRESHOLD = 0.3;

        if (currentPosition == null) return false;

        int currentFloor = getCurrentFloor();
        String currentBuilding = getCurrentBuilding();
        Position chargePosition = getChargePosition(currentBuilding, currentFloor);

        if (chargePosition == null) return false;

        double distance = calculateDistance(currentPosition, chargePosition);
        return distance < DISTANCE_THRESHOLD;
    }

    public boolean validatePositionsExist(List<Position> positions) {
        if (positions == null || positions.isEmpty()) return false;

        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return false;

        Set<Integer> excludedTypes = new HashSet<>(Arrays.asList(2, 3, 4, 10, 11, 12, 13, 14));

        for (Position position : positions) {
            if (excludedTypes.contains(position.getType())) continue;

            Position found = mapPoints.findPositionById(position.getId());
            if (found == null) {
                Log.d(TAG, "Position not found: ID=" + position.getId());
                return false;
            }
        }

        return true;
    }

    public Map<Integer, List<Position>> getAllFloorPositionsFromMapPoints() {
        return getAllFloorPositionsFromMapPoints(getCurrentBuilding());
    }

    public Map<Integer, List<Position>> getAllFloorPositionsFromMapPoints(String buildingId) {
        Map<Integer, List<Position>> floorMap = new HashMap<>();

        MultiBuildingMapPoints mapPoints = currentMapPoints.getValue();
        if (mapPoints == null) return floorMap;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return floorMap;

        Set<Integer> excludedTypes = Set.of(2, 3, 4, 10, 11, 12, 13, 14);

        for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();

            List<Position> positions = floorPoints.getAllPoints();
            List<Position> filteredPositions = positions.stream()
                    .filter(p -> !excludedTypes.contains(p.getType()))
                    .collect(Collectors.toList());

            floorMap.put(floor, filteredPositions);
        }

        return floorMap;
    }

    /**
     * Extract building ID from a Position object
     * @param position The position containing map name
     * @return Building ID
     */
    public String extractBuildingIdFromPosition(Position position) {
        if (position == null || position.getMapName() == null) {
            return "default";
        }
        return extractBuildingId(position.getMapName());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.clear();
    }
}
