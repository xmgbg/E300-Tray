package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datastore.DataStoreManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class AreaViewModel extends AndroidViewModel {
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();

    public static final Preferences.Key<String> AREA_CONFIGS = PreferencesKeys.stringKey("area_configs");
    public static final Type AREA_CONFIG_LIST_TYPE = new TypeToken<List<AreaConfig>>(){}.getType();
    private final Gson gson = new Gson();
    private final MutableLiveData<List<AreaConfig>> areas = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> lastDeletedAreaId = new MutableLiveData<>();

    public AreaViewModel (@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        disposables.add(dataStoreManager.getString(AREA_CONFIGS, "")
                .subscribe(json -> {
                    if (!json.isEmpty()) {
                        Type type = new TypeToken<List<AreaConfig>>(){}.getType();
                        List<AreaConfig> loadedConfigs = gson.fromJson(json, type);
                        if (loadedConfigs != null) {
                            areas.postValue(loadedConfigs);
                        }
                    }
                }, throwable -> {
                    Log.e("AreaConfig", "加载区域配置失败", throwable);
                }));
    }

    public LiveData<List<AreaConfig>> getAreas() {
        return areas;
    }

    public void addArea(AreaConfig area) {
        List<AreaConfig> current = areas.getValue();
        if (current != null) {
            current.add(area);
            areas.postValue(current);
            saveAreaConfigs();
        }
    }

    public void deleteArea(String areaId) {
        List<AreaConfig> current = areas.getValue();
        if (current != null) {
            List<AreaConfig> newList = new ArrayList<>();
            for (AreaConfig config : current) {
                if (!config.getId().equals(areaId)) {
                    newList.add(config);
                }
            }

            // 1. 更新LiveData
            areas.postValue(newList);

            // 2. 记录最后删除的ID（用于去重）
            lastDeletedAreaId.postValue(areaId);

            // 3. 持久化到DataStore
            saveAreaConfigs();

            // 4. 添加：通知所有观察者强制刷新
            new Handler(Looper.getMainLooper()).post(() -> {
                areas.setValue(newList); // 强制刷新LiveData
            });

            Log.d("AreaDelete", "Area deleted: " + areaId);
        }
    }

    // 保存区域配置
    private void saveAreaConfigs() {
        List<AreaConfig> configs = areas.getValue();
        if (configs != null) {
            // 确保使用最新的数据
            String json = gson.toJson(new ArrayList<>(configs), AREA_CONFIG_LIST_TYPE);
            dataStoreManager.putString(AREA_CONFIGS, json);

            // 添加日志验证
            Log.d("DataPersist", "保存区域配置: " + json);
        }
    }

    public static class AreaConfig {
        private String id;
        private String type;
        private String range;

        public AreaConfig(String id, String type, String range) {
            this.id = id;
            this.type = type;
            this.range = range;
        }

        // getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }

        @Override
        public String toString() {
            return "类型: " + type + " | ID: " + id + " | 范围: " + range;
        }
    }
}
