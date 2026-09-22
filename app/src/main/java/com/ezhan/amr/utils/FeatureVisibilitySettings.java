package com.ezhan.amr.utils;

import android.content.Context;
import android.content.SharedPreferences;

public final class FeatureVisibilitySettings {
    private static final String PREFS_NAME = "app_settings";

    public static final String KEY_SHOW_JACK_MODE = "feature_show_jack_mode";
    public static final String KEY_SHOW_ELEVATOR_SETTINGS = "feature_show_elevator_settings";
    public static final String KEY_SHOW_CALL_SETTINGS = "feature_show_call_settings";
    public static final String KEY_SHOW_DOOR_LOCK_SETTINGS = "feature_show_door_lock_settings";
    public static final String KEY_SHOW_USER_MANAGEMENT = "feature_show_user_management";
    public static final String KEY_SHOW_RFID_MANAGEMENT = "feature_show_rfid_management";
    public static final String KEY_SHOW_SHELF_SETTINGS = "feature_show_shelf_settings";
    public static final String KEY_SHOW_MAP_AREA_SETTINGS = "feature_show_map_area_settings";
    public static final String KEY_SHOW_AVOIDANCE_SETTINGS = "feature_show_avoidance_settings";

    private FeatureVisibilitySettings() {
    }

    public static boolean isFeatureVisible(Context context, String key) {
        return getPreferences(context).getBoolean(key, true);
    }

    public static void setFeatureVisible(SharedPreferences.Editor editor, String key, boolean visible) {
        editor.putBoolean(key, visible);
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
