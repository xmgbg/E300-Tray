package com.ezhan.amr.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.ezhan.amr.R;

public class LocalOrbitModeManager {
    private static final String TAG = "LocalOrbitModeManager";
    private static final String PREF_NAME = "OrbitModePrefs";
    private static final String KEY_ORBIT_MODE = "orbit_mode";

    private static LocalOrbitModeManager instance;
    private TextView tvTrackSettings;
    private LocalOrbitModeManager localOrbitModeManager;
    private Button btnVirtualTrack, btnFreePathTrack;
    private SharedPreferences sharedPreferences;

    // 轨道模式枚举
    public enum OrbitMode {
        VIRTUAL_ORBIT("虚拟轨道"),
        FREE_PATH("自由路径");

        private final String name;

        OrbitMode(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

    }

    private LocalOrbitModeManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized LocalOrbitModeManager getInstance(Context context) {
        if (instance == null) {
            instance = new LocalOrbitModeManager(context);
        }
        return instance;
    }

    /**
     * 保存轨道模式到本地
     */
    public void saveOrbitMode(OrbitMode mode) {
        String modeValue = mode == OrbitMode.VIRTUAL_ORBIT ? "virtual_orbit" : "free_path";
        sharedPreferences.edit()
                .putString(KEY_ORBIT_MODE, modeValue)
                .apply();
        Log.d(TAG, "保存轨道模式: " + mode.getName());
    }

    /**
     * 从本地加载轨道模式
     * 默认返回自由路径
     */
    public OrbitMode loadOrbitMode() {
        String savedMode = sharedPreferences.getString(KEY_ORBIT_MODE, "free_path");
        OrbitMode mode = savedMode.equals("virtual_orbit")
                ? OrbitMode.VIRTUAL_ORBIT
                : OrbitMode.FREE_PATH;
        Log.d(TAG, "加载轨道模式: " + mode.getName());
        return mode;
    }

    /**
     * 获取当前轨道模式的显示名称
     */
    public String getCurrentModeName() {
        return loadOrbitMode().getName();
    }

    /**
     * 判断当前是否是虚拟轨道模式
     */
    public boolean isVirtualOrbitMode() {
        return loadOrbitMode() == OrbitMode.VIRTUAL_ORBIT;
    }

    /**
     * 判断当前是否是自由路径模式
     */
    public boolean isFreePathMode() {
        return loadOrbitMode() == OrbitMode.FREE_PATH;
    }

    /**
     * 清除本地存储的模式
     */
    public void clearOrbitMode() {
        sharedPreferences.edit()
                .remove(KEY_ORBIT_MODE)
                .apply();
        Log.d(TAG, "清除轨道模式设置");
    }
}