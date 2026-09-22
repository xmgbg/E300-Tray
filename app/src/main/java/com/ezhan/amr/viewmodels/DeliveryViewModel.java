package com.ezhan.amr.viewmodels;


import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.Position;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class DeliveryViewModel extends AndroidViewModel {

    // LiveData 定义
    private final MutableLiveData<Position> currentPosition = new MutableLiveData<>();
    private final MutableLiveData<Position> homePosition = new MutableLiveData<>();
    private final MutableLiveData<Double> moveSpeed = new MutableLiveData<>();
    private final MutableLiveData<Integer> volumeLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> deliveryWaitDuration = new MutableLiveData<>();
    private final MutableLiveData<Integer> lowPowerLevel = new MutableLiveData<>();
    private final MutableLiveData<Integer> pauseWaitDuration = new MutableLiveData<>();

    private final MutableLiveData<Map<String, Position>> positionMap = new MutableLiveData<>();

    // 依赖组件
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();

    public DeliveryViewModel(@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        // 加载站点地图
        disposables.add(dataStoreManager.getStationMap()
                .subscribe(map -> {
                    positionMap.postValue(map != null ? new HashMap<>(map) : new HashMap<>());
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载站点失败", throwable);
                }));
        // 速度设置
        disposables.add(dataStoreManager.getSetSpeed()
                .subscribe(speed -> {
                    if (speed != null) {
                        moveSpeed.postValue(speed);
                    }
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载速度失败", throwable);
                }));
        // 音量设置
        disposables.add(dataStoreManager.getVolumeLevel()
                .subscribe(level -> {
                    if (level != null) {
                        volumeLevel.postValue(level);
                    }
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载音量失败", throwable);
                }));
        // 放行等待时间
        disposables.add(dataStoreManager.getDeliveryWaitDuration()
                .subscribe(duration -> {
                    if (duration != null) {
                        deliveryWaitDuration.postValue(duration);
                    }
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载配送等待时间失败", throwable);
                }));
        // 低电量设置
        disposables.add(dataStoreManager.getLowPowerThreshold()
                .subscribe(threshold -> {
                    if (threshold != null) {
                        lowPowerLevel.postValue(threshold);
                    }
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载低电量值失败", throwable);
                }));
        // 暂停等待时间
        disposables.add(dataStoreManager.getPauseWaitDuration()
                .subscribe(duration -> {
                    if (duration != null) {
                        pauseWaitDuration.postValue(duration);
                    }
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载暂停等待时间失败", throwable);
                }));
    }

    public LiveData<Map<String, Position>> getPositionMap() {
        return positionMap;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.dispose();
    }
}