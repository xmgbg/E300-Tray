package com.ezhan.amr.web;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 云端路径规划共享工具类。
 * 向云端路径规划服务 POST { mapName, startName, endName }，返回 waypoints JSON。
 * RcsHttpService 和 DeliveryActivity 共用。
 */
public class CloudPathPlanner {

    private static final String TAG = "CloudPathPlanner";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * 同步请求云端路径规划服务。
     *
     * @param url       云端规划服务完整 URL（如 http://192.0.2.10:8080/device/path/plan）
     * @param mapName   地图名称（如 e2_16）
     * @param startName 起点名称
     * @param endName   终点名称
     * @return 云端返回的完整 JSON 对象（含 code / data / waypoints）；失败返回 null
     */
    public static JSONObject requestPath(String url, String mapName, String startName, String endName) {
        JSONObject planRequest = new JSONObject();
        try {
            planRequest.put("mapName", mapName);
            planRequest.put("startName", startName);
            planRequest.put("endName", endName);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build request JSON", e);
            return null;
        }

        RequestBody body = RequestBody.create(
                planRequest.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "Cloud path planner HTTP " + response.code()
                        + " for " + startName + " → " + endName + " on " + mapName);
                return null;
            }
            String responseBody = response.body().string();
            Log.d(TAG, "Cloud response (" + startName + " → " + endName + "): " + responseBody);
            return new JSONObject(responseBody);
        } catch (IOException e) {
            Log.e(TAG, "Cloud request failed (" + startName + " → " + endName + "): " + e.getMessage());
            return null;
        } catch (JSONException e) {
            Log.e(TAG, "Cloud response parse failed", e);
            return null;
        }
    }
}
