package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datastore.DataStoreManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class RobotViewModel extends AndroidViewModel {
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<List<RobotConfig>> robots = new MutableLiveData<>(new ArrayList<>());

    public RobotViewModel (@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        disposables.add(dataStoreManager.getRobotConfigs()
                .subscribe(configs -> {
                    if (configs != null && !configs.isEmpty()) {
                        robots.postValue(configs);
                    }
                }, throwable -> {
                    Log.e("RobotConfig", "加载机器人配置失败", throwable);
                }));
    }

    public LiveData<List<RobotConfig>> getRobots() {
        return robots;
    }

    //---------------------------------------------------------------------------------------------

    public void addRobot(RobotConfig robot) {
        List<RobotConfig> current = robots.getValue();
        if (current == null) {
            current = new ArrayList<>();
        }
        current.add(robot);
        robots.setValue(current);
        saveRobotConfigs(current); // 保存到DataStore
    }

    // 删除机器人
    public void deleteRobot(String robotId) {
        List<RobotConfig> currentRobots = robots.getValue();
        if (currentRobots != null) {
            List<RobotConfig> updatedRobots = new ArrayList<>(currentRobots);
            Iterator<RobotConfig> iterator = updatedRobots.iterator();
            while (iterator.hasNext()) {
                RobotConfig robot = iterator.next();
                if (robot.getId().equals(robotId)) {
                    iterator.remove();
                    break;
                }
            }
            robots.setValue(updatedRobots);
            saveRobotConfigs(updatedRobots); // 保存到DataStore
        }
    }

    private void saveRobotConfigs(List<RobotConfig> configs) {
        dataStoreManager.saveRobotConfigs(configs);
    }

    //---------------------------------------------------------------------------------------------
    public static class RobotConfig {
        private String id;
        private String name;
        private String address;

        public RobotConfig(String id, String name, String address) {
            this.id = id;
            this.name = name;
            this.address = address;
        }

        // getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }

        @Override
        public String toString() {
            return "名称: " + name + " | 地址: " + address;
        }
    }
}
