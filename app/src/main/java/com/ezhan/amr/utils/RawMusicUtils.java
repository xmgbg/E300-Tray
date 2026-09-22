package com.ezhan.amr.utils;

import android.content.Context;
import android.util.TypedValue;
import android.util.Log;

import com.ezhan.amr.R;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RawMusicUtils {
    private static final String TAG = "RawMusicUtils";
    public static final String DEFAULT_MUSIC_FILE = "wa";

    private RawMusicUtils() {
    }

    public static List<String> getRawMusicNames() {
        List<String> names = new ArrayList<>();
        Field[] fields = R.raw.class.getFields();
        for (Field field : fields) {
            names.add(field.getName());
        }
        Collections.sort(names);
        if (names.remove(DEFAULT_MUSIC_FILE)) {
            names.add(0, DEFAULT_MUSIC_FILE);
        }
        return names;
    }

    public static List<String> getRawMp3MusicNames(Context context) {
        List<String> names = new ArrayList<>();
        if (context == null) {
            return names;
        }

        Field[] fields = R.raw.class.getFields();
        for (Field field : fields) {
            try {
                int resourceId = field.getInt(null);
                TypedValue value = new TypedValue();
                context.getResources().getValue(resourceId, value, true);
                CharSequence rawPath = value.string;
                if (rawPath != null && rawPath.toString().toLowerCase().endsWith(".mp3")) {
                    names.add(field.getName());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to inspect raw resource: " + field.getName(), e);
            }
        }

        Collections.sort(names);
        if (names.remove(DEFAULT_MUSIC_FILE)) {
            names.add(0, DEFAULT_MUSIC_FILE);
        }
        return names;
    }

    public static int getRawResourceId(Context context, String rawName) {
        if (context == null || rawName == null || rawName.trim().isEmpty()) {
            return R.raw.ezhan1;
        }
        int resourceId = context.getResources().getIdentifier(
                rawName.trim(),
                "raw",
                context.getPackageName()
        );
        if (resourceId == 0) {
            Log.w(TAG, "Raw music resource not found: " + rawName + ", fallback to wa");
            return R.raw.ezhan1;
        }
        return resourceId;
    }
}
