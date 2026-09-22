package com.ezhan.amr.viewmodels;

import static com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP;
import static com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datastore.TaskChainBackupManager;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.Position;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class CruiseViewModel extends AndroidViewModel {
    private static final String TAG = "CruiseViewModel";
    private static final String PREFS_NAME = "app_settings";
    private static final String CRUISE_TASK_BACKUP_KEY = "cruiseTaskMapBackup";

    private final MutableLiveData<Map<String, Position>> positionMap = new MutableLiveData<>();
    private final MutableLiveData<Map<Integer, CruiseTask>> cruiseTaskMap = new MutableLiveData<>(new LinkedHashMap<>());
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final DataStoreManager dataStore;
    private final SharedPreferences backupPreferences;
    private final MutableLiveData<Set<String>> selectedStations = new MutableLiveData<>(new HashSet<>());
    private final MutableLiveData<Boolean> isVisualCruise = new MutableLiveData<>();
    private final MutableLiveData<Map<Integer, Boolean>> taskEnableStatusMap = new MutableLiveData<>(new HashMap<>());
    private final Gson gson = new Gson();

    public CruiseViewModel(@NonNull Application application) {
        super(application);
        dataStore = DataStoreManager.getInstance(application);
        backupPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadInitialData();
    }

    private void loadInitialData() {
        disposables.add(dataStore.getStationMap()
                .subscribe(map -> {
                    positionMap.postValue(map != null ? new HashMap<>(map) : new HashMap<>());
                }, throwable -> {
                    Log.e(TAG, "Failed to load station map", throwable);
                }));

        disposables.add(dataStore.getCruiseTaskMap()
                .subscribe(map -> {
                    Map<Integer, CruiseTask> loadedTasks = map != null
                            ? new LinkedHashMap<>(map)
                            : new LinkedHashMap<>();

                    Map<Integer, CruiseTask> backupTasks = loadCruiseTaskBackup();
                    if (backupTasks.isEmpty()) {
                        backupTasks = TaskChainBackupManager.restoreCruiseTasks(getApplication());
                    }

                    if (loadedTasks.isEmpty()) {
                        if (!backupTasks.isEmpty()) {
                            Log.w(TAG, "Cruise task DataStore is empty; restoring from backup");
                            loadedTasks = backupTasks;
                            persistCruiseTasks(loadedTasks);
                        }
                    } else if (!backupTasks.isEmpty()) {
                        boolean restoredMissingTasks = false;
                        for (Map.Entry<Integer, CruiseTask> entry : backupTasks.entrySet()) {
                            if (!loadedTasks.containsKey(entry.getKey())) {
                                loadedTasks.put(entry.getKey(), entry.getValue());
                                restoredMissingTasks = true;
                            }
                        }

                        if (restoredMissingTasks) {
                            Log.w(TAG, "Cruise task DataStore is missing backup tasks; merging backup");
                            persistCruiseTasks(loadedTasks);
                        } else {
                            saveCruiseTaskBackup(loadedTasks);
                        }
                    } else {
                        saveCruiseTaskBackup(loadedTasks);
                    }

                    cruiseTaskMap.postValue(loadedTasks);
                }, throwable -> {
                    Log.e(TAG, "Failed to load cruise tasks from DataStore", throwable);
                    Map<Integer, CruiseTask> backupTasks = loadCruiseTaskBackup();
                    if (backupTasks.isEmpty()) {
                        backupTasks = TaskChainBackupManager.restoreCruiseTasks(getApplication());
                    }
                    cruiseTaskMap.postValue(backupTasks);
                }));

        disposables.add(dataStore.isVisualCruiseEnabled()
                .subscribe(visualCruise -> {
                    isVisualCruise.postValue(visualCruise != null ? visualCruise : Boolean.FALSE);
                }, throwable -> {
                    Log.e(TAG, "Failed to load visual cruise setting", throwable);
                }));
    }

    public LiveData<Map<String, Position>> getPositionMap() {
        return positionMap;
    }

    public LiveData<Map<Integer, CruiseTask>> getCruiseTaskMap() {
        return cruiseTaskMap;
    }

    public LiveData<Boolean> isVisualCruiseOpen() {
        return isVisualCruise;
    }

    public boolean isTaskEnabled(int taskId) {
        Map<Integer, Boolean> statusMap = taskEnableStatusMap.getValue();
        return statusMap != null && Boolean.TRUE.equals(statusMap.get(taskId));
    }

    public void updateTaskEnableStatus(int taskId, boolean enabled) {
        Map<Integer, Boolean> currentMap = taskEnableStatusMap.getValue();
        Map<Integer, Boolean> newMap = currentMap != null ? new HashMap<>(currentMap) : new HashMap<>();
        newMap.put(taskId, enabled);
        taskEnableStatusMap.setValue(newMap);
    }

    public void saveCruiseTaskMap(CruiseTask newTask) {
        Map<Integer, CruiseTask> currentCruiseTaskMap = cruiseTaskMap.getValue();
        currentCruiseTaskMap = currentCruiseTaskMap != null
                ? new LinkedHashMap<>(currentCruiseTaskMap)
                : new LinkedHashMap<>();
        currentCruiseTaskMap.put(newTask.getId(), newTask);
        cruiseTaskMap.setValue(currentCruiseTaskMap);
        persistCruiseTasks(currentCruiseTaskMap);
        updateTaskEnableStatus(newTask.getId(), newTask.isAuto() ? false : true);
    }

    public void updateCruiseTaskMap(CruiseTask task) {
        Map<Integer, CruiseTask> currentMap = cruiseTaskMap.getValue();
        if (currentMap == null) {
            currentMap = new LinkedHashMap<>();
        }

        Map<Integer, CruiseTask> newMap = new LinkedHashMap<>(currentMap);
        newMap.put(task.getId(), task);
        cruiseTaskMap.setValue(newMap);
        persistCruiseTasks(newMap);
    }

    public void deleteCruiseTaskMap(Integer taskId) {
        Map<Integer, CruiseTask> currentCruiseTaskMap = cruiseTaskMap.getValue();
        currentCruiseTaskMap = currentCruiseTaskMap != null
                ? new LinkedHashMap<>(currentCruiseTaskMap)
                : new LinkedHashMap<>();
        currentCruiseTaskMap.remove(taskId);
        cruiseTaskMap.setValue(currentCruiseTaskMap);
        persistCruiseTasks(currentCruiseTaskMap);
    }

    public void toggleStationSelection(String station) {
        Set<String> selectedStationSet = selectedStations.getValue();
        if (selectedStationSet == null) {
            selectedStationSet = new HashSet<>();
        }

        if (selectedStationSet.contains(station)) {
            selectedStationSet.remove(station);
        } else {
            selectedStationSet.add(station);
        }
        selectedStations.setValue(selectedStationSet);
    }

    public boolean isStationSelected(String station) {
        Set<String> selectedStationSet = selectedStations.getValue();
        return selectedStationSet != null && selectedStationSet.contains(station);
    }

    private void persistCruiseTasks(Map<Integer, CruiseTask> tasks) {
        String json = gson.toJson(tasks, CRUISE_TASK_MAP_TYPE);
        dataStore.putString(CRUISE_TASK_MAP, json);
        saveCruiseTaskBackup(tasks);
        TaskChainBackupManager.backupCruiseTasks(getApplication(), tasks);
    }

    private void saveCruiseTaskBackup(Map<Integer, CruiseTask> tasks) {
        String json = gson.toJson(tasks, CRUISE_TASK_MAP_TYPE);
        backupPreferences.edit().putString(CRUISE_TASK_BACKUP_KEY, json).commit();
    }

    private Map<Integer, CruiseTask> loadCruiseTaskBackup() {
        String json = backupPreferences.getString(CRUISE_TASK_BACKUP_KEY, null);
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }

        try {
            Map<Integer, CruiseTask> backup = gson.fromJson(json, CRUISE_TASK_MAP_TYPE);
            return backup != null ? new LinkedHashMap<>(backup) : new LinkedHashMap<>();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cruise task backup", e);
            return new LinkedHashMap<>();
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.dispose();
    }

    // Public setters for loading data from config file
    public void setPositionMap(Map<String, Position> map) {
        positionMap.setValue(map != null ? new HashMap<>(map) : new HashMap<>());
    }

    public void setCruiseTaskMap(Map<Integer, CruiseTask> map) {
        cruiseTaskMap.setValue(map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>());
        // Persist to DataStore
        persistCruiseTasks(cruiseTaskMap.getValue());
    }
}
