package com.ezhan.amr.utils;

import android.content.Context;
import android.util.Log;

import com.ezhan.amr.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static ConfigManager instance;
    private JSONObject config;
    private boolean isLoaded = false;

    private ConfigManager() {}

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public void loadConfig(Context context) {
        if (isLoaded) return;

        try {
            InputStream is = context.getResources().openRawResource(R.raw.app_config);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            config = new JSONObject(sb.toString());
            isLoaded = true;

            Log.i(TAG, "Configuration loaded successfully");
            Log.i(TAG, "Environment: " + getEnvironment());
            Log.i(TAG, "WebService enabled: " + isWebServiceEnabled());
            Log.i(TAG, "RcsHttpService enabled: " + isRcsHttpServiceEnabled());
            Log.i(TAG, "RcsWebSocketService enabled: " + isRcsWebSocketServiceEnabled());
            Log.i(TAG, "CallboxService enabled: " + isCallboxServiceEnabled());
            Log.i(TAG, "ModbusService enabled: " + isModbusServiceEnabled());

        } catch (Exception e) {
            Log.e(TAG, "Failed to load configuration", e);
            createDefaultConfig();
        }
    }

    private void createDefaultConfig() {
        try {
            config = new JSONObject();
            JSONObject services = new JSONObject();

            JSONObject webService = new JSONObject();
            webService.put("enabled", true);
            webService.put("port", 8080);
            services.put("webService", webService);

            JSONObject rcsHttp = new JSONObject();
            rcsHttp.put("enabled", false);
            rcsHttp.put("port", 8068);
            services.put("rcsHttpService", rcsHttp);

            JSONObject rcsWs = new JSONObject();
            rcsWs.put("enabled", false);
            rcsWs.put("port", 8070);
            services.put("rcsWebSocketService", rcsWs);

            JSONObject callbox = new JSONObject();
            callbox.put("enabled", true);
            services.put("callboxService", callbox);

            JSONObject modbus = new JSONObject();
            modbus.put("enabled", false);
            modbus.put("port", 5020);
            services.put("modbusService", modbus);

            config.put("services", services);
            config.put("environment", "production");
            config.put("debugMode", true);

            Log.w(TAG, "Created default configuration");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create default config", e);
        }
    }

    // Service enablement checks
    public boolean isWebServiceEnabled() {
        return getServiceEnabled("webService", true);
    }

    public boolean isRcsHttpServiceEnabled() {
        return getServiceEnabled("rcsHttpService", false);
    }

    public boolean isRcsWebSocketServiceEnabled() {
        return getServiceEnabled("rcsWebSocketService", false);
    }

    public boolean isCallboxServiceEnabled() {
        return getServiceEnabled("callboxService", true);
    }

    public boolean isModbusServiceEnabled() {
        return getServiceEnabled("modbusService", false);
    }

    private boolean getServiceEnabled(String serviceName, boolean defaultValue) {
        if (!isLoaded || config == null) return defaultValue;
        try {
            return config.getJSONObject("services")
                    .optJSONObject(serviceName)
                    .optBoolean("enabled", defaultValue);
        } catch (JSONException e) {
            return defaultValue;
        }
    }

    public int getServicePort(String serviceName, int defaultPort) {
        if (!isLoaded || config == null) return defaultPort;
        try {
            return config.getJSONObject("services")
                    .optJSONObject(serviceName)
                    .optInt("port", defaultPort);
        } catch (JSONException e) {
            return defaultPort;
        }
    }

    public String getEnvironment() {
        if (!isLoaded || config == null) return "production";
        return config.optString("environment", "production");
    }

    public boolean isDebugMode() {
        if (!isLoaded || config == null) return true;
        return config.optBoolean("debugMode", true);
    }
}