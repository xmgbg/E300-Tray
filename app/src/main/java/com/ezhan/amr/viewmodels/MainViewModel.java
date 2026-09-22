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

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.disposables.CompositeDisposable;


public class MainViewModel extends AndroidViewModel {
    private final MutableLiveData<Position> homePosition = new MutableLiveData<>();
    private final MutableLiveData<Position> chargePosition = new MutableLiveData<>();
    private final DataStoreManager dataStoreManager;

    private final MutableLiveData<Boolean> isUseJackMode = new MutableLiveData<>();
    private final MutableLiveData<Map<String, Position>> positionMap = new MutableLiveData<>();
    private CompositeDisposable disposables = new CompositeDisposable();
    public MainViewModel(@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        loadAllPersistedData();   // 初始化时从DataStore加载数据
    }

    private void loadAllPersistedData() {
        // 加载站点地图
        disposables.add(dataStoreManager.getStationMap()
                .subscribe(map -> {
                    positionMap.postValue(map != null ? new HashMap<>(map) : new HashMap<>());
                }, throwable -> {
                    Log.e("DeliveryDataLoad", "加载站点失败", throwable);
                }));
        disposables.add(dataStoreManager.isJackModeEnabled()
                .subscribe(open -> {
                    isUseJackMode.postValue(open != null ? open : Boolean.FALSE);
                }, throwable -> {
                    Log.e("CruiseDataLoad", "加载视觉巡检设置失败", throwable);
                }));
    }
    public LiveData<Position> getHomePosition() {
        return homePosition;
    }
    public LiveData<Position> getChargePosition() {
        return chargePosition;
    }

    public LiveData<Boolean> isOpenJackMode() {
        return isUseJackMode;
    }

    public LiveData<Map<String, Position>> getPositionMap() {
        return positionMap;
    }
}
