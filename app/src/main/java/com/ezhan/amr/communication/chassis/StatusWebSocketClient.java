package com.ezhan.amr.communication.chassis;

import android.content.Context;
import android.util.Log;

import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StatusWebSocketClient extends BaseWebSocketClient {
    private static final String TAG = "StatusWebSocketClient";

    // Status-specific components
    public interface StatusListener {
        void onStatusUpdate(AgvStatusResponse response);
    }

    private List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private AgvStatusResponse lastStatusResponse;


    // Status polling
    private static final long STATUS_REQUEST_INTERVAL_MS = 1000;
    private final Runnable statusRequestRunnable = new Runnable() {
        @Override
        public void run() {
            if (webSocket != null && isConnected) {
                String requestMessage = "{\"cmd\":\"getAgvStatus\"}";
                webSocket.send(requestMessage);
            }

            if (shouldReconnect && isConnected) {
                mainHandler.postDelayed(this, STATUS_REQUEST_INTERVAL_MS);
            }
        }
    };

    public StatusWebSocketClient(Context context, String host, int port) {
        super(context, host, port);
    }

    @Override
    protected void handleIncomingMessage(String text) {
        Log.d(TAG, "Status client received: " + text);
        lastMessageTime.set(System.currentTimeMillis()); // Update base class timestamp

        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            String responseCmd = json.get("cmd").getAsString();

            if ("agvStatus_result".equals(responseCmd)) {
                handleStatusMessage(text);
            } else {
                Log.d(TAG, "Status client ignoring non-status message: " + responseCmd);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing status message", e);
        }
    }

    @Override
    protected void onWebSocketConnected() {
        Log.d(TAG, "Status client connected, starting status polling and health checks");

        // Start base class health monitoring
        super.onWebSocketConnected();

        // Start status-specific polling
        mainHandler.postDelayed(statusRequestRunnable, STATUS_REQUEST_INTERVAL_MS);
    }

    @Override
    protected void onWebSocketDisconnected() {
        Log.d(TAG, "Status client disconnected, stopping status polling and health checks");

        // Stop status polling
        mainHandler.removeCallbacks(statusRequestRunnable);

        // Stop base class health monitoring
        super.onWebSocketDisconnected();
    }


    private void handleStatusMessage(String text) {
        try {
            JsonObject jsonObject = JsonParser.parseString(text).getAsJsonObject();
            JsonElement dataElement = jsonObject.get("data");

            AgvStatusResponse status;
            if (dataElement.isJsonObject()) {
                status = new Gson().fromJson(text, AgvStatusResponse.class);
            } else if (dataElement.isJsonPrimitive() && dataElement.getAsJsonPrimitive().isString()) {
                String dataString = dataElement.getAsString();
                JsonObject reconstructedJson = new JsonObject();
                reconstructedJson.add("cmd", jsonObject.get("cmd"));
                JsonObject parsedData = JsonParser.parseString(dataString).getAsJsonObject();
                reconstructedJson.add("data", parsedData);
                if (jsonObject.has("talk")) reconstructedJson.add("talk", jsonObject.get("talk"));
                if (jsonObject.has("time")) reconstructedJson.add("time", jsonObject.get("time"));
                status = new Gson().fromJson(reconstructedJson, AgvStatusResponse.class);
            } else {
                Log.e(TAG, "Unexpected data format in agvStatus_result");
                if (debugger != null) {
                    debugger.logError("handleStatusMessage", "Unexpected data format in agvStatus_result");
                }
                return;
            }

            if (status != null && status.data != null) {
                long timeSinceLastUpdate = System.currentTimeMillis() - lastValidStatusTime.get();
                if (debugger != null) {
                    debugger.logStatusUpdate(getClass().getSimpleName(), true, timeSinceLastUpdate);
                }

                // Parse and log error information
                if (debugger != null) {
                    // Log error code array (if present)
                    if (status.data.errorCode != null && !status.data.errorCode.isEmpty()) {
                        StringBuilder errorCodeStr = new StringBuilder();
                        for (int i = 0; i < status.data.errorCode.size(); i++) {
                            if (i > 0) errorCodeStr.append(", ");
                            errorCodeStr.append(status.data.errorCode.get(i));
                        }
                        debugger.logWarning("AGV_STATUS",
                                String.format("errorCode: [%s]", errorCodeStr.toString()));
                    }

                    // Log errcode (if present and non-zero)
                    if (status.data.errcode != null && status.data.errcode != 0) {
                        debugger.logWarning("AGV_STATUS",
                                String.format("errcode: %d", status.data.errcode));
                    } else if (status.data.errcode != null) {
                        debugger.log(String.format("AGV_STATUS: errcode = %d (no error)", status.data.errcode));
                    }

                    // Additional context logging when errors are present
                    if ((status.data.errorCode != null && !status.data.errorCode.isEmpty()) ||
                            (status.data.errcode != null && status.data.errcode != 0)) {
                        debugger.logError("AGV_STATUS_ERROR",
                                String.format("Robot has errors! errorCode=%s, errcode=%d, goalFinish=%d, poseProb=%.2f",
                                        status.data.errorCode != null ? status.data.errorCode.toString() : "null",
                                        status.data.errcode != null ? status.data.errcode : 0,
                                        status.data.goalFinish,
                                        status.data.poseProbability));
                    }
                }

                // Update health check timestamp
                updateLastValidStatusTime();
                lastStatusResponse = status;

                // Notify all registered status listeners
                notifyStatusUpdate(status);
            }
        } catch (Exception e) {
            if (debugger != null) {
                debugger.logInvalidMessage(getClass().getSimpleName(), text, e.getMessage());
            }
            Log.e(TAG, "Error processing status message", e);
        }
    }

    // Enhanced health check method for status client
    public boolean isStatusConnectionHealthy() {
        if (!isConnected) return false;

        long timeSinceLastStatus = System.currentTimeMillis() - lastValidStatusTime.get();
        long timeSinceLastMessage = System.currentTimeMillis() - lastMessageTime.get();

        boolean hasRecentStatus = lastValidStatusTime.get() > 0 && timeSinceLastStatus < STATUS_TIMEOUT_MS;
        boolean hasRecentMessages = timeSinceLastMessage < CONNECTION_TIMEOUT_MS;

        return hasRecentStatus || hasRecentMessages;
    }

    // Get detailed connection status
    public ConnectionHealth getConnectionHealth() {
        return new ConnectionHealth(
                isConnected,
                lastValidStatusTime.get() > 0,
                System.currentTimeMillis() - lastMessageTime.get(),
                System.currentTimeMillis() - lastValidStatusTime.get()
        );
    }

    public static class ConnectionHealth {
        public final boolean isConnected;
        public final boolean hasReceivedStatus;
        public final long timeSinceLastMessage;
        public final long timeSinceLastStatus;

        public ConnectionHealth(boolean isConnected, boolean hasReceivedStatus,
                                long timeSinceLastMessage, long timeSinceLastStatus) {
            this.isConnected = isConnected;
            this.hasReceivedStatus = hasReceivedStatus;
            this.timeSinceLastMessage = timeSinceLastMessage;
            this.timeSinceLastStatus = timeSinceLastStatus;
        }
    }

    // Status listener management
    public void addStatusListener(StatusListener listener) {
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
    }

    public void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    private void notifyStatusUpdate(AgvStatusResponse response) {
        for (StatusListener listener : statusListeners) {
            try {
                listener.onStatusUpdate(response);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying status listener", e);
            }
        }
    }

    public AgvStatusResponse getLastStatusResponse() {
        return lastStatusResponse;
    }

    @Override
    protected String getRobotErrorSnapshot() {
        AgvStatusResponse status = lastStatusResponse;
        if (status == null || status.data == null) {
            return "no-cached-status";
        }
        return String.format("errorCode=%s, errcode=%d, goalFinish=%d, poseProb=%.2f",
                status.data.errorCode != null ? status.data.errorCode.toString() : "null",
                status.data.errcode != null ? status.data.errcode : 0,
                status.data.goalFinish,
                status.data.poseProbability);
    }
}
