package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datatype.RfidData;
import com.ezhan.amr.data.datastore.DataStoreManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * RFID卡片管理ViewModel
 */
public class RfidViewModel extends AndroidViewModel {
    private static final String TAG = "RfidViewModel";
    private MutableLiveData<List<RfidData>> rfidListLiveData;
    private List<RfidData> rfidList;
    private int nextId = 1;
    private DataStoreManager dataStoreManager;
    private CompositeDisposable disposables;

    public RfidViewModel(@NonNull Application application) {
        super(application);
        dataStoreManager = DataStoreManager.getInstance(application);
        disposables = new CompositeDisposable();
        rfidListLiveData = new MutableLiveData<>();
        rfidList = new ArrayList<>();
        loadRfidList();
    }

    private void loadRfidList() {
        try {
            disposables.add(
                dataStoreManager.getRfidList()
                    .subscribeOn(Schedulers.io())
                    .subscribe(rfids -> {
                        try {
                            if (rfids != null && !rfids.isEmpty()) {
                                rfidList.clear();
                                rfidList.addAll(rfids);
                                // 更新nextId为最大ID+1
                                for (RfidData rfid : rfids) {
                                    if (rfid.getId() >= nextId) {
                                        nextId = rfid.getId() + 1;
                                    }
                                }
                            } else {
                                // 如果没有数据，使用默认数据
                                initTestData();
                            }
                            rfidListLiveData.postValue(rfidList);
                            Log.i(TAG, "RFID list loaded: " + rfidList.size() + " cards");
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing RFID list", e);
                            // 处理失败时使用默认数据
                            initTestData();
                            rfidListLiveData.postValue(rfidList);
                        }
                    }, error -> {
                        Log.e(TAG, "Failed to load RFID list", error);
                        // 加载失败时使用默认数据
                        initTestData();
                        rfidListLiveData.postValue(rfidList);
                    })
            );
        } catch (Exception e) {
            Log.e(TAG, "Exception in loadRfidList", e);
            // 初始化失败时使用默认数据
            initTestData();
            rfidListLiveData.postValue(rfidList);
        }
    }

    /**
     * 获取RFID卡片列表
     */
    public LiveData<List<RfidData>> getRfidList() {
        return rfidListLiveData;
    }

    /**
     * 添加RFID卡片
     */
    public boolean addRfid(RfidData rfid) {
        // 检查RFID序列号是否已存在
        for (RfidData existingRfid : rfidList) {
            if (existingRfid.getRfidSerial().equals(rfid.getRfidSerial())) {
                return false;
            }
        }

        // 设置ID
        rfid.setId(nextId++);
        rfidList.add(rfid);
        rfidListLiveData.postValue(rfidList);
        saveRfidList();
        Log.i(TAG, "RFID card added: " + rfid);
        return true;
    }

    /**
     * 更新RFID卡片
     */
    public boolean updateRfid(RfidData updatedRfid) {
        for (int i = 0; i < rfidList.size(); i++) {
            RfidData rfid = rfidList.get(i);
            if (rfid.getId() == updatedRfid.getId()) {
                // 检查新的RFID序列号是否与其他卡片冲突
                for (int j = 0; j < rfidList.size(); j++) {
                    if (j != i && rfidList.get(j).getRfidSerial().equals(updatedRfid.getRfidSerial())) {
                        return false;
                    }
                }
                rfidList.set(i, updatedRfid);
                rfidListLiveData.postValue(rfidList);
                saveRfidList();
                Log.i(TAG, "RFID card updated: " + updatedRfid);
                return true;
            }
        }
        return false;
    }

    /**
     * 删除RFID卡片
     */
    public boolean deleteRfid(int id) {
        for (int i = 0; i < rfidList.size(); i++) {
            if (rfidList.get(i).getId() == id) {
                RfidData removedRfid = rfidList.remove(i);
                rfidListLiveData.postValue(rfidList);
                saveRfidList();
                Log.i(TAG, "RFID card deleted: " + removedRfid);
                return true;
            }
        }
        return false;
    }

    /**
     * 保存RFID卡片列表到DataStore
     */
    private void saveRfidList() {
        dataStoreManager.saveRfidList(rfidList);
        Log.i(TAG, "RFID list saved: " + rfidList.size() + " cards");
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (disposables != null) {
            disposables.clear();
        }
    }

    /**
     * 搜索RFID卡片
     */
    public List<RfidData> searchRfid(String keyword) {
        List<RfidData> result = new ArrayList<>();
        for (RfidData rfid : rfidList) {
            if (rfid.getRfidSerial().contains(keyword) || 
                rfid.getUserId().contains(keyword)) {
                result.add(rfid);
            }
        }
        return result;
    }

    /**
     * 按用户ID筛选
     */
    public List<RfidData> filterByUserId(String userId) {
        List<RfidData> result = new ArrayList<>();
        for (RfidData rfid : rfidList) {
            if (userId.equals("所有用户") || rfid.getUserId().equals(userId)) {
                result.add(rfid);
            }
        }
        return result;
    }

    /**
     * 按卡片类型筛选
     */
    public List<RfidData> filterByCardType(int cardType) {
        List<RfidData> result = new ArrayList<>();
        for (RfidData rfid : rfidList) {
            if (cardType == 0 || rfid.getCardType() == cardType) {
                result.add(rfid);
            }
        }
        return result;
    }

    /**
     * 按状态筛选
     */
    public List<RfidData> filterByStatus(int status) {
        List<RfidData> result = new ArrayList<>();
        for (RfidData rfid : rfidList) {
            if (status == -1 || rfid.getStatus() == status) {
                result.add(rfid);
            }
        }
        return result;
    }

    /**
     * 组合筛选
     */
    public List<RfidData> filterRfid(String keyword, String userId, int cardType, int status) {
        List<RfidData> result = new ArrayList<>();
        for (RfidData rfid : rfidList) {
            boolean match = true;
            
            // 关键词搜索
            if (!keyword.isEmpty() && 
                !rfid.getRfidSerial().contains(keyword) && 
                !rfid.getUserId().contains(keyword)) {
                match = false;
            }
            
            // 用户ID筛选
            if (match && !userId.equals("所有用户") && !rfid.getUserId().equals(userId)) {
                match = false;
            }
            
            // 卡片类型筛选
            if (match && cardType != 0 && rfid.getCardType() != cardType) {
                match = false;
            }
            
            // 状态筛选
            if (match && status != -1 && rfid.getStatus() != status) {
                match = false;
            }
            
            if (match) {
                result.add(rfid);
            }
        }
        return result;
    }

    /**
     * 初始化测试数据
     */
    private void initTestData() {
        rfidList.clear();
        rfidList.add(new RfidData(nextId++, "9000000001", "user001", 1, 1));
        rfidList.add(new RfidData(nextId++, "0987654321", "user002", 2, 1));
        rfidList.add(new RfidData(nextId++, "1122334455", "user003", 1, 0));
        rfidList.add(new RfidData(nextId++, "5544332211", "user001", 2, 1));
        rfidListLiveData.postValue(rfidList);
    }
}
