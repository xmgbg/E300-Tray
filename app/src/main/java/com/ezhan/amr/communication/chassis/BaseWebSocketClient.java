package com.ezhan.amr.communication.chassis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ezhan.amr.navigation.task.NavigationStateDebugger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public abstract class BaseWebSocketClient {
    private static final String TAG = "BaseWebSocketClient";

    // Core connection components
    protected final OkHttpClient client;
    protected WebSocket webSocket;
    protected String currentUrl;
    protected boolean isConnected = false;
    protected boolean shouldReconnect = true;
    protected Context context;

    // Add instance-level connection tracking
    protected volatile boolean isConnecting = false;
    protected final Object connectionLock = new Object();

    // Connection monitoring
    public final AtomicLong lastMessageTime = new AtomicLong(0);
    protected final AtomicLong lastValidStatusTime = new AtomicLong(0);
    protected static final long STATUS_TIMEOUT_MS = 15000;
    protected static final long CONNECTION_TIMEOUT_MS = 20000;

    // Handlers
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Connection parameters
    protected String host;
    protected int port;

    // Reconnection settings
    protected static final long INITIAL_RECONNECT_DELAY_MS = 3000;
    protected int reconnectAttempts = 0;
    protected long currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    protected NavigationStateDebugger debugger;

    // Listener interface for connection state
    public interface ConnectionListener {
        void onConnectionStateChanged(boolean connected);
        void onError(Exception e);
    }

    protected List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    // Connection health check
    protected final Runnable connectionCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldReconnect) return;

            long currentTime = System.currentTimeMillis();
            long timeSinceLastMessage = currentTime - lastMessageTime.get();

            // Only check based on message timing for Ethernet
            if (isConnected && lastMessageTime.get() > 0 &&
                    timeSinceLastMessage > CONNECTION_TIMEOUT_MS) {

                Log.w(TAG, getClass().getSimpleName() + " - No messages for " + timeSinceLastMessage + "ms, reconnecting");
                isConnected = false;
                notifyConnectionState(false);
                onWebSocketDisconnected();
                scheduleReconnect();
            }

            if (shouldReconnect) {
                mainHandler.postDelayed(this, 10000);
            }
        }
    };

    public BaseWebSocketClient(Context context, String host, int port) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.port = port;
        this.currentUrl = "ws://" + host + ":" + port;

        this.client = new OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        // Get debugger instance
        this.debugger = NavigationStateDebugger.getInstance();
        if (context != null) {
            this.debugger.init(context.getApplicationContext());
        }
    }

    // ==================================================================
    // Abstract methods to be implemented by subclasses
    // ==================================================================

    protected void onWebSocketConnected() {
        Log.d(TAG, "WebSocket connected, starting connection health checks");
        // Start connection health monitoring
        mainHandler.postDelayed(connectionCheckRunnable, CONNECTION_TIMEOUT_MS / 2);
    }

    protected void onWebSocketDisconnected() {
        Log.d(TAG, "WebSocket disconnected, stopping connection health checks");
        // Stop connection health monitoring
        mainHandler.removeCallbacks(connectionCheckRunnable);
    }

    // Update lastMessageTime when messages are received
    protected void handleIncomingMessage(String text) {
        lastMessageTime.set(System.currentTimeMillis());
        // Subclasses will implement actual message handling
    }

    /**
     * 【测试一】断连时机器人错误状态快照，子类覆写以读取缓存的 AGV 状态。
     */
    protected String getRobotErrorSnapshot() {
        return "no-cached-status";
    }

    protected void updateLastValidStatusTime() {
        lastValidStatusTime.set(System.currentTimeMillis());
    }

    // ==================================================================
    // Core Connection Management (Base functionality only)
    // ==================================================================

    public void connect() {
        this.shouldReconnect = true;
        establishConnection();
    }

    protected void establishConnection() {
        synchronized (connectionLock) {
            if (!shouldReconnect || isConnecting) {
                Log.d(TAG, getClass().getSimpleName() + " - Skipping connection, already connecting: " + isConnecting);
                return;
            }
            isConnecting = true;
        }

        if (debugger != null) {
            debugger.logWebSocketConnectAttempt(getClass().getSimpleName(), host, port);
        }

        if (webSocket != null) {
            try {
                webSocket.close(1000, "Reconnecting");
            } catch (Exception e) {
                Log.w(TAG, "Error closing previous WebSocket", e);
            }
            webSocket = null;
        }

        Request request = new Request.Builder().url(currentUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                synchronized (connectionLock) {
                    isConnecting = false;
                }
                if (debugger != null) {
                    debugger.logWebSocketConnected(getClass().getSimpleName());
                }
                reconnectAttempts = 0;
                currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
                lastMessageTime.set(System.currentTimeMillis());

                isConnected = true;
                notifyConnectionState(true);
                onWebSocketConnected();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                synchronized (connectionLock) {
                    isConnecting = false;
                }
                if (debugger != null) {
                    String closeReason = String.format("code=%d, reason=%s", code, reason);
                    debugger.logWebSocketDisconnected(getClass().getSimpleName(), closeReason);
                }
                isConnected = false;
                notifyConnectionState(false);
                onWebSocketDisconnected();

                // Only schedule reconnect for unexpected closures
                if (code != 1000) { // 1000 is normal closure
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                synchronized (connectionLock) {
                    isConnecting = false;
                }
                // 【测试一】断连时输出机器人错误状态快照，定位断连与机器人错误的关联
                String robotState = getRobotErrorSnapshot();
                Log.e("测试一", getClass().getSimpleName() + " connection FAILED: " + t.getMessage()
                        + " | robotState=" + robotState);
                if (debugger != null) {
                    debugger.logError("测试一_WEBSOCKET_FAILURE",
                            getClass().getSimpleName() + " " + t.getMessage() + " | " + robotState);
                    debugger.logWebSocketFailure(getClass().getSimpleName(),
                            t.getMessage(), reconnectAttempts);
                }
                isConnected = false;
                notifyConnectionState(false);
                onWebSocketDisconnected();
                scheduleReconnect();
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, getClass().getSimpleName() + " - WebSocket closing: " + code + " - " + reason);
                isConnected = false;
                onWebSocketDisconnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                lastMessageTime.set(System.currentTimeMillis());
                handleIncomingMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                lastMessageTime.set(System.currentTimeMillis());
                handleIncomingMessage(bytes.utf8());
            }
        });
    }
    // ==================================================================
    // Utility Methods
    // ==================================================================

    protected void scheduleReconnect() {
        if (!shouldReconnect) return;

        synchronized (connectionLock) {
            if (isConnecting) {
                Log.d(TAG, getClass().getSimpleName() + " - Already connecting, skipping duplicate reconnect");
                return;
            }
        }

        reconnectAttempts++;
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, 30000);

        Log.d(TAG, getClass().getSimpleName() + " - Scheduling reconnect in " + currentReconnectDelay + "ms, attempt: " + reconnectAttempts);

        mainHandler.postDelayed(() -> {
            if (shouldReconnect) {
                establishConnection();
            }
            // Remove the redundant else-if condition
        }, currentReconnectDelay);
    }

    protected void scheduleImmediateReconnect() {
        if (shouldReconnect) {
            synchronized (connectionLock) {
                if (isConnecting) {
                    Log.d(TAG, getClass().getSimpleName() + " - Already connecting, skipping immediate reconnect");
                    return;
                }
            }

            Log.d(TAG, getClass().getSimpleName() + " - Scheduling immediate reconnect");
            mainHandler.post(this::establishConnection);
        }
    }

    public void disconnect() {
        shouldReconnect = false;

        // Stop all runnables
        mainHandler.removeCallbacks(connectionCheckRunnable);

        if (webSocket != null) {
            webSocket.close(1000, "Normal closure");
        }
        isConnected = false;
        notifyConnectionState(false);
        onWebSocketDisconnected();
    }

    public boolean isConnected() {
        return isConnected && webSocket != null;
    }

    // ==================================================================
    // Listener Management
    // ==================================================================

    public void addConnectionListener(ConnectionListener listener) {
        if (!connectionListeners.contains(listener)) {
            connectionListeners.add(listener);
        }
    }

    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    protected void notifyConnectionState(boolean connected) {
        for (ConnectionListener listener : connectionListeners) {
            try {
                listener.onConnectionStateChanged(connected);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying connection state", e);
            }
        }
    }

    protected void notifyError(Exception e) {
        for (ConnectionListener listener : connectionListeners) {
            try {
                listener.onError(e);
            } catch (Exception ex) {
                Log.e(TAG, "Error notifying error", ex);
            }
        }
    }
}
