package com.ezhan.amr.data.datastore;

import static com.ezhan.amr.data.datastore.DataStoreKeys.CRUISE_TASK_MAP_TYPE;
import static com.ezhan.amr.data.datastore.DataStoreKeys.JACK_TASK_MAP_TYPE;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackTask;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps task-chain definitions outside app-private storage so they can be restored
 * after replacing or reinstalling the APK.
 */
public final class TaskChainBackupManager {
    private static final String TAG = "TaskChainBackup";
    private static final String BACKUP_FILE_NAME = "task_chains.json";
    private static final String BACKUP_RELATIVE_DIR = Environment.DIRECTORY_DOCUMENTS + "/ezhan/amr/backup/";
    private static final String BACKUP_SUB_DIR = "ezhan/amr/backup";
    private static final int BACKUP_VERSION = 1;

    private static final Gson GSON = new Gson();

    private TaskChainBackupManager() {
    }

    public static synchronized void backupCruiseTasks(Context context, Map<Integer, CruiseTask> tasks) {
        BackupData backupData = readBackup(context);
        backupData.cruiseTaskMap = copyMap(tasks);
        writeBackup(context, backupData);
    }

    public static synchronized void backupJackTasks(Context context, Map<Integer, JackTask> tasks) {
        BackupData backupData = readBackup(context);
        backupData.jackTaskMap = copyMap(tasks);
        writeBackup(context, backupData);
    }

    public static synchronized Map<Integer, CruiseTask> restoreCruiseTasks(Context context) {
        BackupData backupData = readBackup(context);
        return copyMap(backupData.cruiseTaskMap);
    }

    public static synchronized Map<Integer, JackTask> restoreJackTasks(Context context) {
        BackupData backupData = readBackup(context);
        return copyMap(backupData.jackTaskMap);
    }

    private static BackupData readBackup(Context context) {
        try {
            JsonObject root = readBackupJson(context);
            if (root == null) {
                return new BackupData();
            }

            BackupData backupData = new BackupData();
            if (root.has("version")) {
                backupData.version = root.get("version").getAsInt();
            }
            if (root.has("backupTime")) {
                backupData.backupTime = root.get("backupTime").getAsLong();
            }
            if (root.has("packageName")) {
                backupData.packageName = root.get("packageName").getAsString();
            }
            if (root.has("cruiseTaskMap") && !root.get("cruiseTaskMap").isJsonNull()) {
                Map<Integer, CruiseTask> cruiseTasks = GSON.fromJson(root.get("cruiseTaskMap"), CRUISE_TASK_MAP_TYPE);
                backupData.cruiseTaskMap = copyMap(cruiseTasks);
            }
            if (root.has("jackTaskMap") && !root.get("jackTaskMap").isJsonNull()) {
                Map<Integer, JackTask> jackTasks = GSON.fromJson(root.get("jackTaskMap"), JACK_TASK_MAP_TYPE);
                backupData.jackTaskMap = copyMap(jackTasks);
            }
            return backupData;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read task-chain backup", e);
            return new BackupData();
        }
    }

    private static JsonObject readBackupJson(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri uri = findMediaStoreBackupUri(context, false);
                if (uri != null) {
                    try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                         InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                        return JsonParser.parseReader(reader).getAsJsonObject();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read backup from MediaStore", e);
            }
        }

        File backupFile = getLegacyBackupFile();
        if (!backupFile.exists()) {
            return null;
        }

        try (InputStream inputStream = new FileInputStream(backupFile);
             InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read legacy backup file", e);
            return null;
        }
    }

    private static void writeBackup(Context context, BackupData backupData) {
        backupData.version = BACKUP_VERSION;
        backupData.backupTime = System.currentTimeMillis();
        backupData.packageName = context.getPackageName();

        String json = GSON.toJson(backupData);
        boolean saved = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saved = writeMediaStoreBackup(context, json);
        }

        if (!saved) {
            writeLegacyBackup(json);
        }
    }

    private static boolean writeMediaStoreBackup(Context context, String json) {
        try {
            Uri uri = findMediaStoreBackupUri(context, true);
            if (uri == null) {
                return false;
            }

            try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri, "wt");
                 OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                writer.write(json);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write backup to MediaStore", e);
            return false;
        }
    }

    private static Uri findMediaStoreBackupUri(Context context, boolean createIfMissing) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = new String[]{MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] selectionArgs = new String[]{BACKUP_FILE_NAME, BACKUP_RELATIVE_DIR};

        try (Cursor cursor = resolver.query(collection, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return Uri.withAppendedPath(collection, String.valueOf(id));
            }
        }

        if (!createIfMissing) {
            return null;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, BACKUP_RELATIVE_DIR);
        return resolver.insert(collection, values);
    }

    private static void writeLegacyBackup(String json) {
        File backupFile = getLegacyBackupFile();
        File backupDir = backupFile.getParentFile();
        if (backupDir == null || (!backupDir.exists() && !backupDir.mkdirs())) {
            Log.e(TAG, "Failed to create legacy backup directory");
            return;
        }

        File tempFile = new File(backupDir, BACKUP_FILE_NAME + ".tmp");
        try (OutputStream outputStream = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(json);
            writer.flush();

            if (backupFile.exists() && !backupFile.delete()) {
                Log.w(TAG, "Failed to delete old legacy backup before replace");
            }
            if (!tempFile.renameTo(backupFile)) {
                Log.e(TAG, "Failed to replace legacy backup file");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write legacy backup file", e);
        }
    }

    private static File getLegacyBackupFile() {
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        return new File(new File(documentsDir, BACKUP_SUB_DIR), BACKUP_FILE_NAME);
    }

    private static <T> Map<Integer, T> copyMap(Map<Integer, T> source) {
        return source != null ? new LinkedHashMap<>(source) : new LinkedHashMap<>();
    }

    private static class BackupData {
        int version = BACKUP_VERSION;
        long backupTime;
        String packageName;
        Map<Integer, CruiseTask> cruiseTaskMap = new LinkedHashMap<>();
        Map<Integer, JackTask> jackTaskMap = new LinkedHashMap<>();
    }
}
