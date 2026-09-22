package com.ezhan.amr.communication.lora;

import android.util.Log;

import com.ezhan.amr.navigation.task.NavigationStateDebugger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import android_serialport_api.Device;
import android_serialport_api.SerialPortManager;

public class LoraCommunicator {
    private final String TAG = "LoraCommunicator";
    private SerialPortManager serialPortManager;

    private static final int BAUD_RATE = 115200;
    private static final int RX_TO_TX_DELAY_MS = 0;
    private static final int TX_TO_RX_DELAY_MS = 150;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int READ_RETRY_DELAY_MS = 20;
    private static final int MAX_RESPONSE_SIZE = 32;
    private static final int BUFFER_SIZE = 1024;
    private static final int RESPONSE_IDLE_GAP_MS = 50;

    private volatile PacketListener packetListener;
    private Thread backgroundListenerThread;
    private final AtomicBoolean shouldListen = new AtomicBoolean(false);

    private final Object serialLock = new Object();
    private final Object outputLock = new Object();
    private final Object transactionLock = new Object();
    private final Object pendingLock = new Object();
    private final ByteArrayOutputStream incomingBuffer = new ByteArrayOutputStream();
    private PendingResponse pendingResponse;

    /** 机器人间交管帧监听器 (A5 5A 帧头), 与呼叫盒 (A0 0A) 帧互不影响 */
    private volatile TrafficPacketListener trafficPacketListener;

    /** 交管帧监听接口 */
    public interface TrafficPacketListener {
        void onTrafficPacket(TrafficLoraPacket packet);
    }

    public void setTrafficPacketListener(TrafficPacketListener listener) {
        Log.d(TAG, "Setting TrafficPacketListener: " + listener);
        this.trafficPacketListener = listener;
    }

    private static LoraCommunicator instance;

    private int messagesSent = 0;
    private int messagesReceived = 0;
    private int timeouts = 0;

    private NavigationStateDebugger debugger;

    public static synchronized LoraCommunicator getInstance(String portName) throws IOException {
        if (instance == null) {
            instance = new LoraCommunicator(portName);
        }
        return instance;
    }

    public interface PacketListener {
        void onButtonPressed(int callBoxAddress, int callBoxChannel, int buttonId);
    }

    public void setPacketListener(PacketListener listener) {
        Log.d(TAG, "Setting PacketListener: " + listener);
        this.packetListener = listener;
    }

    public LoraCommunicator(String portName) throws IOException {
        try {
            debugger = NavigationStateDebugger.getInstance();

            Device device = new Device();
            device.path = portName;
            device.speed = BAUD_RATE;
            this.serialPortManager = new SerialPortManager(device);

            clearInputStream();

            Log.i(TAG, "LoraCommunicator initialized on port: " + portName);
            debugger.logInfo("LoraCommunicator initialized on port: " + portName);
        } catch (Exception e) {
            close();
            throw new IOException("Failed to initialize serial port: " + e.getMessage(), e);
        }
    }

    public String sendMessage(String hexMessage, boolean expectResponse)
            throws IOException, TimeoutException {
        return sendMessage(hexMessage, expectResponse, READ_TIMEOUT_MS);
    }

    public String sendMessage(String hexMessage, boolean expectResponse, int timeoutMs)
            throws IOException, TimeoutException {
        if (!isConnected()) {
            throw new IOException("Serial port not properly initialized or not open");
        }

        synchronized (transactionLock) {
            PendingResponse pending = null;
            long startTime = System.currentTimeMillis();
            String messageId = hexMessage.length() > 20 ? hexMessage.substring(0, 20) + "..." : hexMessage;

            try {
                startListening();

                Log.d(TAG, "Sending message: " + hexMessage);
                debugger.logInfo("[LORA] Sending message: " + hexMessage);

                byte[] outData = hexStringToByteArray(hexMessage);

                if (expectResponse) {
                    pending = new PendingResponse();
                    synchronized (pendingLock) {
                        pendingResponse = pending;
                    }
                }

                synchronized (outputLock) {
                    Thread.sleep(RX_TO_TX_DELAY_MS);
                    serialPortManager.sendPacket(outData);
                    messagesSent++;
                }

                long sendDuration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Message sent in " + sendDuration + "ms");
                debugger.logInfo("[LORA] Message sent in " + sendDuration + "ms: " + messageId);

                if (!expectResponse) {
                    debugger.logInfo("[LORA] No response expected for: " + messageId);
                    return null;
                }

                Thread.sleep(TX_TO_RX_DELAY_MS);
                long responseStartTime = System.currentTimeMillis();
                String response = pending.await(timeoutMs);
                long responseDuration = System.currentTimeMillis() - responseStartTime;

                long totalDuration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Response received in " + totalDuration + "ms: " + response);
                debugger.logInfo("[LORA] Response received in " + totalDuration +
                        "ms (read: " + responseDuration + "ms): " + response);

                messagesReceived++;
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                debugger.logError("[LORA] Communication interrupted for message: " + messageId,
                        e.getMessage());
                throw new IOException("Communication interrupted", e);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid hex message: " + hexMessage, e);
                debugger.logError("[LORA] Invalid hex message: " + messageId, e.getMessage());
                throw new IOException("Invalid hex message: " + e.getMessage());
            } catch (TimeoutException e) {
                timeouts++;
                debugger.logError("[LORA] Timeout waiting for response: " + messageId,
                        "Timeout after " + timeoutMs + "ms");
                throw e;
            } finally {
                if (pending != null) {
                    synchronized (pendingLock) {
                        if (pendingResponse == pending) {
                            pendingResponse = null;
                        }
                    }
                }
            }
        }
    }

    public void sendBytes(byte[] data) throws IOException {
        if (!isConnected()) {
            throw new IOException("Serial port not properly initialized or not open");
        }

        synchronized (transactionLock) {
            startListening();

            String hexMessage = bytesToHexString(data, data.length);
            Log.d(TAG, "Sending bytes: " + hexMessage);

            byte[] outData = hexStringToByteArray(hexMessage);

            synchronized (outputLock) {
                try {
                    Thread.sleep(RX_TO_TX_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Communication interrupted", e);
                }
                serialPortManager.sendPacket(outData);
                messagesSent++;
                Log.d(TAG, "Bytes sent successfully - messagesSent: " + messagesSent);
            }
        }
    }

    public void startListening() {
        if (backgroundListenerThread == null || !backgroundListenerThread.isAlive()) {
            startBackgroundListening();
        }
    }

    private void startBackgroundListening() {
        shouldListen.set(true);

        backgroundListenerThread = new Thread(() -> {
            Log.i(TAG, "Background listener started");
            debugger.logInfo("[LORA] Background listener started");

            byte[] buffer = new byte[BUFFER_SIZE];

            while (shouldListen.get()) {
                try {
                    if (!isConnected()) {
                        Log.e(TAG, "Serial port disconnected, stopping background listener");
                        debugger.logError("[LORA] Serial port disconnected, stopping background listener", "");
                        break;
                    }

                    synchronized (serialLock) {
                        if (serialPortManager.mInputStream.available() > 0) {
                            int bytesRead = serialPortManager.mInputStream.read(buffer);
                            if (bytesRead > 0) {
                                handleIncomingBytes(Arrays.copyOf(buffer, bytesRead));
                            }
                        }
                    }

                    completePendingResponseIfIdle();
                    Thread.sleep(READ_RETRY_DELAY_MS);
                } catch (IOException e) {
                    Log.e(TAG, "IO Error in background listener", e);
                    debugger.logError("[LORA] IO Error in background listener", e.getMessage());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Background listener interrupted");
                    debugger.logInfo("[LORA] Background listener interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Log.i(TAG, "Background listener stopped");
            debugger.logInfo("[LORA] Background listener stopped");
        });

        backgroundListenerThread.setName("Lora-Background-Listener");
        backgroundListenerThread.start();
    }

    public void stopBackgroundListening() {
        Log.d(TAG, "Stopping background listener");
        debugger.logInfo("[LORA] Stopping background listener");

        shouldListen.set(false);

        if (backgroundListenerThread != null) {
            try {
                backgroundListenerThread.interrupt();
                backgroundListenerThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping background listener", e);
                debugger.logWarning("[LORA] Interrupted while stopping background listener", e.getMessage());
                Thread.currentThread().interrupt();
            }
            backgroundListenerThread = null;
        }
    }

    private void handleIncomingBytes(byte[] rawData) {
        try {
            incomingBuffer.write(rawData);
            byte[] data = incomingBuffer.toByteArray();
            incomingBuffer.reset();

            for (int i = 0; i < data.length; ) {
                if (data[i] == (byte) 0xA0) {
                    if (i + 1 >= data.length) {
                        incomingBuffer.write(data, i, data.length - i);
                        break;
                    }

                    if (data[i + 1] == (byte) 0x0A) {
                        if (i + 6 > data.length) {
                            incomingBuffer.write(data, i, data.length - i);
                            break;
                        }

                        byte[] packet = Arrays.copyOfRange(data, i, i + 6);
                        if (isValidCallboxPacket(packet)) {
                            dispatchCallboxPacket(packet);
                            i += 6;
                            continue;
                        }
                    }
                } else if (data[i] == (byte) 0xA5) {
                    // 机器人间交管帧 A5 5A (12字节数据体)
                    if (i + 1 >= data.length) {
                        incomingBuffer.write(data, i, data.length - i);
                        break;
                    }
                    if (data[i + 1] == (byte) 0x5A) {
                        if (i + TrafficLoraPacket.PACKET_LEN > data.length) {
                            incomingBuffer.write(data, i, data.length - i);
                            break;
                        }
                        byte[] tp = Arrays.copyOfRange(data, i, i + TrafficLoraPacket.PACKET_LEN);
                        TrafficLoraPacket parsed = TrafficLoraPacket.parse(tp, 0);
                        if (parsed != null) {
                            dispatchTrafficPacket(parsed);
                            i += TrafficLoraPacket.PACKET_LEN;
                            continue;
                        }
                    }
                }

                appendPendingResponseByte(data[i]);
                i++;
            }

            completePendingResponseIfIdle();
        } catch (IOException e) {
            Log.e(TAG, "Failed to process incoming LoRa bytes", e);
            debugger.logError("[LORA] Failed to process incoming bytes", e.getMessage());
        }
    }

    private boolean isValidCallboxPacket(byte[] packet) {
        if (packet == null || packet.length != 6) {
            return false;
        }
        if (packet[0] != (byte) 0xA0 || packet[1] != (byte) 0x0A) {
            return false;
        }
        if (!LoraPacketHandler.verifyChecksum(packet)) {
            return false;
        }
        int buttonId = packet[4] & 0xFF;
        return buttonId == 0xFF || (buttonId >= 0x01 && buttonId <= 0x03);
    }

    private void dispatchCallboxPacket(byte[] packet) {
        int callBoxAddress = packet[2] & 0xFF;
        int callBoxChannel = packet[3] & 0xFF;
        int buttonId = packet[4] & 0xFF;

        Log.d(TAG, "Callbox packet: address=" + callBoxAddress +
                ", channel=" + callBoxChannel + ", button=" + buttonId);

        PacketListener listener = packetListener;
        if (listener != null) {
            listener.onButtonPressed(callBoxAddress, callBoxChannel, buttonId);
        }
    }

    /** 分发机器人间交管帧到监听器 */
    private void dispatchTrafficPacket(TrafficLoraPacket packet) {
        Log.d(TAG, "Traffic packet: " + packet);
        messagesReceived++;
        com.ezhan.amr.navigation.area.TrafficLog.log("LORA",
                "RECV parsed: " + packet);
        TrafficPacketListener listener = trafficPacketListener;
        if (listener != null) {
            listener.onTrafficPacket(packet);
        } else {
            com.ezhan.amr.navigation.area.TrafficLog.log("LORA",
                    "RECV but no listener registered, dropped");
        }
    }

    private void appendPendingResponseByte(byte data) {
        PendingResponse pending;
        synchronized (pendingLock) {
            pending = pendingResponse;
        }

        if (pending != null) {
            pending.append(data);
        } else {
            Log.d(TAG, "Dropping unclaimed LoRa byte: " + String.format("%02X", data & 0xFF));
        }
    }

    private void completePendingResponseIfIdle() {
        PendingResponse pending;
        synchronized (pendingLock) {
            pending = pendingResponse;
        }
        if (pending != null) {
            pending.completeIfIdle(RESPONSE_IDLE_GAP_MS);
        }
    }

    private void clearInputStream() throws IOException {
        if (serialPortManager != null && serialPortManager.mInputStream != null) {
            int available = serialPortManager.mInputStream.available();
            if (available > 0) {
                byte[] clearBuffer = new byte[available];
                int bytesRead = serialPortManager.mInputStream.read(clearBuffer);
                if (bytesRead > 0) {
                    Log.d(TAG, "Cleared: " + bytesToHexString(clearBuffer, bytesRead));
                }
            }
        }
    }

    private byte[] hexStringToByteArray(String hexString) throws IllegalArgumentException {
        String cleanHex = hexString.replaceAll("\\s", "");

        if (!cleanHex.matches("^([0-9A-Fa-f]{2})+$")) {
            if (cleanHex.length() % 2 != 0) {
                cleanHex = "0" + cleanHex;
            } else {
                debugger.logError("[LORA] Invalid hex format", "Hex string: " + hexString);
                throw new IllegalArgumentException("Invalid hex format");
            }
        }

        byte[] data = new byte[cleanHex.length() / 2];
        for (int i = 0; i < cleanHex.length(); i += 2) {
            String byteStr = cleanHex.substring(i, i + 2);
            data[i / 2] = (byte) Integer.parseInt(byteStr, 16);
        }

        return data;
    }

    private String bytesToHexString(byte[] bytes, int length) {
        StringBuilder hexString = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            String hex = Integer.toHexString(0xff & bytes[i]);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString().toUpperCase();
    }

    public void close() {
        Log.i(TAG, "Closing LoraCommunicator");
        debugger.logInfo("[LORA] Closing LoraCommunicator");

        stopBackgroundListening();

        try {
            if (serialPortManager != null) {
                serialPortManager.closeSerialPort();
                serialPortManager = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing serial port", e);
            debugger.logError("[LORA] Error closing serial port", e.getMessage());
        }

        if (instance == this) {
            instance = null;
        }

        String stats = String.format("Stats - Sent: %d, Received: %d, Timeouts: %d",
                messagesSent, messagesReceived, timeouts);
        Log.i(TAG, "Closed. " + stats);
        debugger.logInfo("[LORA] Closed. " + stats);
    }

    public void restart(String portName) throws IOException {
        Log.i(TAG, "Restarting on port: " + portName);
        debugger.logInfo("[LORA] Restarting on port: " + portName);
        close();

        Device device = new Device();
        device.path = portName;
        device.speed = BAUD_RATE;
        this.serialPortManager = new SerialPortManager(device);

        debugger.logInfo("[LORA] Restarted successfully on port: " + portName);
    }

    public boolean isConnected() {
        return serialPortManager != null &&
                serialPortManager.mSerialPort != null &&
                serialPortManager.mInputStream != null &&
                serialPortManager.mOutputStream != null;
    }

    public String getStats() {
        return String.format("Sent: %d, Received: %d, Timeouts: %d",
                messagesSent, messagesReceived, timeouts);
    }

    public void flushInput() throws IOException {
        clearInputStream();
    }

    private class PendingResponse {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(MAX_RESPONSE_SIZE);
        private boolean completed = false;
        private long lastByteTime = 0;
        private String response;

        synchronized void append(byte data) {
            if (completed || buffer.size() >= MAX_RESPONSE_SIZE) {
                return;
            }
            buffer.write(data);
            lastByteTime = System.currentTimeMillis();
            if (buffer.size() >= MAX_RESPONSE_SIZE) {
                completeLocked();
            }
        }

        synchronized void completeIfIdle(long idleGapMs) {
            if (!completed && buffer.size() > 0 &&
                    System.currentTimeMillis() - lastByteTime >= idleGapMs) {
                completeLocked();
            }
        }

        synchronized String await(int timeoutMs) throws TimeoutException, InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!completed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new TimeoutException("No response received within " + timeoutMs + "ms");
                }
                wait(Math.min(remaining, READ_RETRY_DELAY_MS));
                completeIfIdle(RESPONSE_IDLE_GAP_MS);
            }
            return response;
        }

        private void completeLocked() {
            response = bytesToHexString(buffer.toByteArray(), buffer.size());
            completed = true;
            notifyAll();
        }
    }
}
