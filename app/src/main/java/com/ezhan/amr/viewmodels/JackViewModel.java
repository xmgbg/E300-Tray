package com.ezhan.amr.viewmodels;

import static com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP;
import static com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE;

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
import com.ezhan.amr.data.datatype.JackTask;
import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class JackViewModel extends AndroidViewModel {
    private static final String TAG = "JackViewModel";
    private static final String PREFS_NAME = "app_settings";
    private static final String JACK_TASK_BACKUP_KEY = "jackTaskMapBackup";

    private final DataStoreManager dataStoreManager;
    private final Context appContext;
    private final SharedPreferences backupPreferences;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<Map<Integer, JackTask>> jackTaskMap = new MutableLiveData<>();
    private final Gson gson = new Gson();

    public JackViewModel(@NonNull Application application) {
        super(application);
        appContext = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(appContext);
        backupPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        disposables.add(dataStoreManager.getJackTaskMap()
                .subscribe(taskMap -> {
                            Map<Integer, JackTask> loadedTasks = taskMap != null
                                    ? new LinkedHashMap<>(taskMap)
                                    : new LinkedHashMap<>();
                            Map<Integer, JackTask> backupTasks = loadJackTaskBackup();
                            if (backupTasks.isEmpty()) {
                                backupTasks = TaskChainBackupManager.restoreJackTasks(appContext);
                            }

                            if (loadedTasks.isEmpty()) {
                                if (!backupTasks.isEmpty()) {
                                    Log.w(TAG, "Jack task DataStore is empty; restoring from backup");
                                    loadedTasks = backupTasks;
                                    persistJackTasks(loadedTasks);
                                }
                            } else if (!backupTasks.isEmpty()) {
                                boolean restoredMissingTasks = false;
                                for (Map.Entry<Integer, JackTask> entry : backupTasks.entrySet()) {
                                    if (!loadedTasks.containsKey(entry.getKey())) {
                                        loadedTasks.put(entry.getKey(), entry.getValue());
                                        restoredMissingTasks = true;
                                    }
                                }

                                if (restoredMissingTasks) {
                                    Log.w(TAG, "Jack task DataStore is missing backup tasks; merging backup");
                                    persistJackTasks(loadedTasks);
                                } else {
                                    saveJackTaskBackup(loadedTasks);
                                    TaskChainBackupManager.backupJackTasks(appContext, loadedTasks);
                                }
                            } else {
                                saveJackTaskBackup(loadedTasks);
                                TaskChainBackupManager.backupJackTasks(appContext, loadedTasks);
                            }
                            jackTaskMap.postValue(loadedTasks);
                        },
                        throwable -> {
                            Log.e(TAG, "Failed to load jack task list", throwable);
                            Map<Integer, JackTask> backupTasks = loadJackTaskBackup();
                            if (backupTasks.isEmpty()) {
                                backupTasks = TaskChainBackupManager.restoreJackTasks(appContext);
                            }
                            jackTaskMap.postValue(backupTasks);
                        }
                ));
    }

    public LiveData<Map<Integer, JackTask>> getJackTaskMap() {
        return jackTaskMap;
    }

    public void saveJackTaskMap(JackTask newTask) {
        Map<Integer, JackTask> currentJackTaskMap = jackTaskMap.getValue();
        currentJackTaskMap = currentJackTaskMap != null
                ? new LinkedHashMap<>(currentJackTaskMap)
                : new LinkedHashMap<>();
        currentJackTaskMap.put(newTask.getTaskId(), newTask);
        jackTaskMap.setValue(currentJackTaskMap);
        persistJackTasks(currentJackTaskMap);
    }

    public void deleteJackTaskMap(Integer taskId) {
        Map<Integer, JackTask> currentJackTaskMap = jackTaskMap.getValue();
        currentJackTaskMap = currentJackTaskMap != null
                ? new LinkedHashMap<>(currentJackTaskMap)
                : new LinkedHashMap<>();
        currentJackTaskMap.remove(taskId);
        jackTaskMap.setValue(currentJackTaskMap);
        persistJackTasks(currentJackTaskMap);
    }

    private void persistJackTasks(Map<Integer, JackTask> tasks) {
        Map<Integer, JackTask> safeTasks = tasks != null ? tasks : new LinkedHashMap<>();
        String json = gson.toJson(safeTasks, JACK_TASK_MAP_TYPE);
        dataStoreManager.putString(JACK_TASK_MAP, json);
        saveJackTaskBackup(safeTasks);
        TaskChainBackupManager.backupJackTasks(appContext, safeTasks);
    }

    private void saveJackTaskBackup(Map<Integer, JackTask> tasks) {
        String json = gson.toJson(tasks != null ? tasks : new LinkedHashMap<>(), JACK_TASK_MAP_TYPE);
        backupPreferences.edit().putString(JACK_TASK_BACKUP_KEY, json).commit();
    }

    private Map<Integer, JackTask> loadJackTaskBackup() {
        String json = backupPreferences.getString(JACK_TASK_BACKUP_KEY, null);
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }

        try {
            Map<Integer, JackTask> backup = gson.fromJson(json, JACK_TASK_MAP_TYPE);
            return backup != null ? new LinkedHashMap<>(backup) : new LinkedHashMap<>();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load jack task backup", e);
            return new LinkedHashMap<>();
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.dispose();
    }

    // Public setter for loading data from config file
    public void setJackTaskMap(Map<Integer, JackTask> map) {
        jackTaskMap.setValue(map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>());
        // Persist to DataStore
        persistJackTasks(jackTaskMap.getValue());
    }
}
