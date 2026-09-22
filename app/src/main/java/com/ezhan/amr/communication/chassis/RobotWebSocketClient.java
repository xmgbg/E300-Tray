package com.ezhan.amr.communication.chassis;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class RobotWebSocketClient extends WebSocketClient {
    private WebSocketListener listener;

    public interface WebSocketListener {
        void onMessageReceived(String message);
        void onConnectionEstablished();
        void onConnectionClosed();
        void onError(Exception ex);
    }

    public RobotWebSocketClient(URI serverUri, WebSocketListener listener) {
        super(serverUri);
        this.listener = listener;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        if (listener != null) {
            listener.onConnectionEstablished();
        }
    }

    @Override
    public void onMessage(String message) {
        if (listener != null) {
            listener.onMessageReceived(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (listener != null) {
            listener.onConnectionClosed();
        }
    }

    @Override
    public void onError(Exception ex) {
        if (listener != null) {
            listener.onError(ex);
        }
    }
}