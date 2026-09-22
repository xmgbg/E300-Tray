package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datastore.DataStoreKeys;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class CallboxViewModel extends AndroidViewModel {
    private String TAG = "CallboxViewModel";
    private final DataStoreManager dataStoreManager;
    private final SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "call_settings";
    private static final String KEY_CALL_BOXES = "call_boxes_json";
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<Map<String, CallButton>> callButtonMap = new MutableLiveData<>(new HashMap<>());
    private final MutableLiveData<Integer> nextButtonId = new MutableLiveData<>(1);
    private static final Preferences.Key<Integer> NEXT_BUTTON_ID = PreferencesKeys.intKey("next_button_id");
    private final Gson gson = new Gson();
    private final MutableLiveData<List<CallBox>> callBoxes = new MutableLiveData<>(new ArrayList<>());

    public CallboxViewModel (@NonNull Application application) throws Exception {
        super(application);
        Context context = application.getApplicationContext();
        dataStoreManager = DataStoreManager.getInstance(context);
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadAllPersistedData();
    }

    private void loadAllPersistedData() {
        disposables.add(dataStoreManager.getString(DataStoreKeys.CALL_BUTTONS, "")
                .subscribe(json -> {
                    try {
                        if (!json.isEmpty()) {
                            Type type = new TypeToken<Map<String, CallButton>>() {}.getType();
                            Map<String, CallButton> map = gson.fromJson(json, type);
                            callButtonMap.postValue(map != null ? map : new HashMap<>());
                        }
                    } catch (Exception e) {
                        Log.e("DataLoad", "解析呼叫按钮数据失败，使用默认值", e);
                        callButtonMap.postValue(new HashMap<>());
                    }
                }, throwable -> {
                    Log.e("DataLoad", "加载呼叫按钮数据失败", throwable);
                    callButtonMap.postValue(new HashMap<>());
                }));

        // 使用 SharedPreferences 加载 CALL_BOXES，避免 DataStore 断电丢失问题
        String callBoxJson = sharedPreferences.getString(KEY_CALL_BOXES, "");
        if (!callBoxJson.isEmpty()) {
            try {
                Type type = new TypeToken<List<CallBox>>(){}.getType();
                List<CallBox> list = gson.fromJson(callBoxJson, type);
                if (list != null) {
                    int maxId = 0;
                    for (CallBox box : list) {
                        for (CallButton btn : box.getButtons()) {
                            if (btn.getButtonByteId() == 0) {
                                int newId = getNextButtonId();
                                btn.setButtonByteId(newId);
                                maxId = Math.max(maxId, newId);
                            } else {
                                maxId = Math.max(maxId, btn.getButtonByteId());
                            }
                        }
                    }
                    saveNextButtonId(maxId + 1);
                    callBoxes.postValue(list);
                }
            } catch (Exception e) {
                Log.e(TAG, "加载呼叫盒数据失败", e);
            }
        }

        disposables.add(dataStoreManager.getInt(NEXT_BUTTON_ID, 1)
                .subscribe(id -> {
                    nextButtonId.postValue(id);
                }, throwable -> {
                    Log.e(TAG, "加载按钮ID失败", throwable);
                    nextButtonId.postValue(1);
                }));
    }

    public LiveData<List<CallBox>> getCallBoxes() {
        return callBoxes;
    }

    public void setCallBoxes(List<CallBox> boxes) {
        if (boxes != null) {
            callBoxes.setValue(boxes);
            saveCallBoxes();
        }
    }

    public LiveData<Map<String, CallButton>> getCallButtonsMap() {
        return callButtonMap;
    }

    //---------------------------------------------------------------------------------------------

    public void addCallBox(CallBox callBox) {
        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            List<CallBox> newList = new ArrayList<>(current);
            newList.add(callBox);
            callBoxes.setValue(newList);
            saveCallBoxes();
        }
    }

    public void updateCallBox(CallBox callBox) {
        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            List<CallBox> newList = new ArrayList<>(current);
            for (int i = 0; i < newList.size(); i++) {
                if (newList.get(i).getId().equals(callBox.getId())) {
                    newList.set(i, callBox);
                    break;
                }
            }
            callBoxes.setValue(newList);
            saveCallBoxes();
        }
    }

    public void deleteCallBox(String id) {
        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            List<CallBox> newList = new ArrayList<>(current);
            newList.removeIf(box -> box.getId().equals(id));
            callBoxes.setValue(newList);
            saveCallBoxes();
        }
    }

    public void addButtonToCallBox(String callBoxId, CallButton button) {
        // 分配新ID
        int newId = getNextButtonId();
        // 创建新按钮并复制属性
        CallButton newButton = new CallButton(newId);
        newButton.setName(button.getName());
        newButton.setPosition(button.getPosition());
        newButton.setTask(button.getTask());

        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            List<CallBox> newList = new ArrayList<>();
            for (CallBox box : current) {
                if (box.getId().equals(callBoxId)) {
                    CallBox newBox = box.deepCopy(); // 创建深拷贝，避免 DiffUtil 比较相同引用
                    newBox.getButtons().add(button);
                    newList.add(newBox);
                } else {
                    newList.add(box);
                }
            }
            callBoxes.setValue(newList);
            saveCallBoxes();
            updateButton(button);
        }
    }

    public void updateButtonInCallBox(String callBoxId, String buttonId, String position, String task) {
        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            List<CallBox> newList = new ArrayList<>();
            for (CallBox box : current) {
                if (box.getId().equals(callBoxId)) {
                    CallBox newBox = box.deepCopy(); // 深拷贝原始（未修改的）数据
                    for (CallButton btn : newBox.getButtons()) {
                        if (btn.getId().equals(buttonId)) {
                            btn.setPosition(position);
                            btn.setTask(task);
                            break;
                        }
                    }
                    newList.add(newBox);
                } else {
                    newList.add(box);
                }
            }
            callBoxes.setValue(newList);
            saveCallBoxes();
        }
    }

    public void deleteButtonFromCallBox(String callBoxId, String buttonId) {
        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            List<CallBox> newList = new ArrayList<>();
            for (CallBox box : current) {
                if (box.getId().equals(callBoxId)) {
                    CallBox newBox = box.deepCopy(); // 创建深拷贝，避免 DiffUtil 比较相同引用
                    newBox.getButtons().removeIf(btn -> btn.getId().equals(buttonId));
                    newList.add(newBox);
                } else {
                    newList.add(box);
                }
            }
            callBoxes.setValue(newList);
            saveCallBoxes();
            deleteButton(buttonId);
        }
    }

    private void saveCallBoxes() {
        List<CallBox> current = callBoxes.getValue();
        if (current != null) {
            try {
                String json = gson.toJson(current, new TypeToken<List<CallBox>>() {}.getType());
                // 使用 SharedPreferences.commit() 同步写入，防止断电数据丢失
                sharedPreferences.edit()
                        .putString(KEY_CALL_BOXES, json)
                        .commit();
            } catch (Exception e) {
                Log.e(TAG, "saveCallBoxes: 保存失败", e);
            }
        }
    }

    /**
     * 更新按钮配置
     * @param button 要更新的按钮对象
     */
    public void updateButton(CallButton button) {
        Map<String, CallButton> currentButtons = callButtonMap.getValue();
        if (currentButtons != null) {
            Map<String, CallButton> newButtons = new HashMap<>(currentButtons);
            newButtons.put(button.getId(), button);
            callButtonMap.setValue(newButtons);

            // 使用同步写入，防止断电数据丢失
            String json = gson.toJson(newButtons, new TypeToken<Map<String, CallButton>>(){}.getType());
            dataStoreManager.putStringBlocking(DataStoreKeys.CALL_BUTTONS, json);
        }
    }


    /**
     * 根据ID获取按钮
     * @param buttonId 按钮ID
     * @return 按钮对象，如果不存在则返回null
     */
    public CallButton getButton(String buttonId) {
        Map<String, CallButton> buttons = callButtonMap.getValue();
        return buttons != null ? buttons.get(buttonId) : null;
    }

    /**
     * 删除按钮
     * @param buttonId 要删除的按钮ID
     */
    public void deleteButton(String buttonId) {
        Map<String, CallButton> currentButtons = callButtonMap.getValue();
        if (currentButtons != null && currentButtons.containsKey(buttonId)) {
            Map<String, CallButton> newButtons = new HashMap<>(currentButtons);
            newButtons.remove(buttonId);
            callButtonMap.setValue(newButtons);

            // 使用同步写入，防止断电数据丢失
            String json = gson.toJson(newButtons, new TypeToken<Map<String, CallButton>>(){}.getType());
            dataStoreManager.putStringBlocking(DataStoreKeys.CALL_BUTTONS, json);
        }
    }
    // 保存下一个按钮ID
    private void saveNextButtonId(int nextId) {
        // 使用同步写入，防止断电数据丢失
        dataStoreManager.setIntBlocking(NEXT_BUTTON_ID, nextId);
        nextButtonId.postValue(nextId);
    }

    // 获取并自增下一个ID (线程安全)
    public int getNextButtonId() {
        int currentId = nextButtonId.getValue() != null ? nextButtonId.getValue() : 1;
        saveNextButtonId(currentId + 1); // 保存递增后的值
        return currentId;
    }

    //---------------------------------------------------------------------------------------------

    public static class CallBox {
        private String id;
        private String name;
        private int channel;
        private int address;
        private List<CallButton> buttons = new ArrayList<>();

        public CallBox() {
            this.id = UUID.randomUUID().toString();
        }

        /**
         * 创建 CallBox 的深拷贝
         */
        public CallBox deepCopy() {
            CallBox copy = new CallBox();
            copy.id = this.id;
            copy.name = this.name;
            copy.channel = this.channel;
            copy.address = this.address;
            copy.buttons = new ArrayList<>();
            for (CallButton btn : this.buttons) {
                copy.buttons.add(btn.deepCopy());
            }
            return copy;
        }

        // Getters and Setters
        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Byte getChannel() { return (byte) channel; }
        public void setChannel(int channel) { this.channel = channel; }
        public int getAddress() { return address; }
        public void setAddress(int address) { this.address = address; }
        public List<CallButton> getButtons() { return buttons; }
    }

    public static class CallButton {
        private String id;
        private String name;
        private String position;
        private String task;

        private int buttonByteId;

        // 添加字节ID的方法
        public int getButtonByteId() {
            return buttonByteId;
        }
        public void setButtonByteId(int buttonByteId) {
            this.buttonByteId = buttonByteId;
        }
        public CallButton(int buttonByteId) {
            this.id = UUID.randomUUID().toString();
            this.buttonByteId = buttonByteId;
        }

        public CallButton() {
            this.id = UUID.randomUUID().toString();
        }

        /**
         * 创建 CallButton 的深拷贝
         */
        public CallButton deepCopy() {
            CallButton copy = new CallButton();
            copy.id = this.id;
            copy.name = this.name;
            copy.position = this.position;
            copy.task = this.task;
            copy.buttonByteId = this.buttonByteId;
            return copy;
        }

        // Getters and Setters
        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPosition() { return position; }
        public void setPosition(String position) { this.position = position; }
        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }
    }
}
