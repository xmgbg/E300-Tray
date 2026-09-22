package com.ezhan.amr.communication.rfid;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android_serialport_api.Device;
import android_serialport_api.SerialPortManager;

/**
 * RFID通信控制器
 * 通过串口ttyS7与RFID读写器进行通信
 *
 * 功能：
 * 1. 读取RFID标签
 * 2. 写入RFID标签
 * 3. 接收和解析响应报文
 * 4. 支持同步和异步通信模式
 */
public class RFIDCommunicator {

    private static final String TAG = "RFIDCommunicator";

    // 串口通信参数配置
    private static final int BAUD_RATE = 9600;              // 波特率
    private static final int TX_TO_RX_DELAY_MS = 100;       // 发送后等待响应的延迟
    private static final int READ_TIMEOUT_MS = 2000;        // 读取响应超时时间
    private static final int READ_RETRY_DELAY_MS = 10;      // 轮询检查数据的间隔

    // 串口设备路径
    public static final String DEFAULT_PORT = "/dev/ttyS7";

    // 串口管理器
    private SerialPortManager serialPortManager;
    private final String portName;

    // 单例实例
    private static RFIDCommunicator instance;

    // 后台监听线程（用于异步接收）
    private Thread backgroundListenerThread;
    private final AtomicBoolean shouldListen = new AtomicBoolean(false);
    private static final int BUFFER_SIZE = 1024;

    // 临时读取模式
    private final AtomicBoolean isTemporaryListening = new AtomicBoolean(false);
    private final AtomicReference<String> temporaryRfidData = new AtomicReference<>(null);
    private final Object temporaryLock = new Object();

    // 状态回调监听器
    private volatile RFIDListener rfidListener;

    // 通信统计
    private int messagesSent = 0;
    private int messagesReceived = 0;
    private int timeouts = 0;
    private int checksumErrors = 0;

    /**
     * RFID状态监听器接口
     * 用于接收异步状态变化通知
     */
    public interface RFIDListener {
        /**
         * RFID标签读取回调
         *
         * @param tagId 标签ID
         */
        void onTagRead(String tagId);

        /**
         * 通信错误回调
         *
         * @param error 错误信息
         */
        void onCommunicationError(String error);
    }

    /**
     * RFID数据监听器接口
     * 用于接收RFID原始数据通知
     */
    public interface RFIDDataListener {
        /**
         * RFID数据接收回调
         *
         * @param rfidData RFID原始数据（16进制字符串）
         */
        void onRfidDataReceived(String rfidData);
    }

    // RFID数据监听器列表
    private final List<RFIDDataListener> rfidDataListeners = new ArrayList<>();

    /**
     * 获取单例实例
     *
     * @param portName 串口设备路径，如 "/dev/ttyS7"
     * @return RFIDCommunicator实例
     * @throws IOException 初始化失败时抛出
     */
    public static synchronized RFIDCommunicator getInstance(String portName) throws IOException {
        if (instance == null) {
            instance = new RFIDCommunicator(portName);
        }
        return instance;
    }

    /**
     * 获取默认端口的单例实例
     *
     * @return RFIDCommunicator实例
     * @throws IOException 初始化失败时抛出
     */
    public static synchronized RFIDCommunicator getInstance() throws IOException {
        return getInstance(DEFAULT_PORT);
    }

    /**
     * 私有构造函数
     *
     * @param portName 串口设备路径
     * @throws IOException 串口初始化失败
     */
    private RFIDCommunicator(String portName) throws IOException {
        this.portName = portName;
        try {
            // 创建设备配置
            Device device = new Device();
            device.path = portName;
            device.speed = BAUD_RATE;

            // 初始化串口管理器
            this.serialPortManager = new SerialPortManager(device);

            // 清空输入缓冲区
            clearInputStream();

            Log.i(TAG, "RFIDCommunicator initialized on port: " + portName);

        } catch (Exception e) {
            close();
            throw new IOException("Failed to initialize serial port: " + e.getMessage(), e);
        }
    }

    /**
     * 设置RFID状态监听器
     *
     * @param listener 监听器实例
     */
    public void setRFIDListener(RFIDListener listener) {
        Log.d(TAG, "Setting RFIDListener: " + listener);
        this.rfidListener = listener;
    }

    /**
     * 添加RFID数据监听器
     *
     * @param listener 监听器实例
     */
    public void addRfidDataListener(RFIDDataListener listener) {
        if (listener != null && !rfidDataListeners.contains(listener)) {
            rfidDataListeners.add(listener);
            Log.d(TAG, "Added RFIDDataListener: " + listener);
        }
    }

    /**
     * 移除RFID数据监听器
     *
     * @param listener 监听器实例
     */
    public void removeRfidDataListener(RFIDDataListener listener) {
        if (listener != null && rfidDataListeners.remove(listener)) {
            Log.d(TAG, "Removed RFIDDataListener: " + listener);
        }
    }

    /**
     * 清除所有RFID数据监听器
     */
    public void clearRfidDataListeners() {
        rfidDataListeners.clear();
        Log.d(TAG, "Cleared all RFIDDataListeners");
    }

    // ==================== 核心功能方法 ====================

    /**
     * 读取RFID标签
     *
     * @return 标签ID，如果读取失败返回null
     */
    public String readTag() {
        Log.i(TAG, "Reading RFID tag");
        byte[] packet = RFIDPacketHandler.createReadPacket();
        byte[] response = sendCommand(packet, true);

        if (response == null) {
            return null;
        }

        String tagId = RFIDPacketHandler.parseTagId(response);
        if (tagId != null) {
            Log.i(TAG, "Tag read successfully: " + tagId);
        } else {
            Log.e(TAG, "Failed to parse tag ID");
        }
        return tagId;
    }

    // ==================== 底层通信方法 ====================

    /**
     * 发送命令并可选地等待响应
     *
     * @param packet        要发送的数据包
     * @param expectResponse 是否等待响应
     * @return 响应字节数组，如果不等待响应或失败返回null
     */
    private byte[] sendCommand(byte[] packet, boolean expectResponse) {
        if (!isConnected()) {
            Log.e(TAG, "Serial port not connected");
            notifyError("串口未连接");
            return null;
        }

        long startTime = System.currentTimeMillis();

        try {
            // 1. 清空输入缓冲区，避免读取旧数据
            clearInputStream();

            // 2. 记录发送的报文
            String hexPacket = RFIDPacketHandler.bytesToHexString(packet, packet.length);
            Log.d(TAG, "Sending packet: " + hexPacket);

            // 3. 发送数据
            serialPortManager.sendPacket(packet);
            messagesSent++;

            Log.d(TAG, "Packet sent in " + (System.currentTimeMillis() - startTime) + "ms");

            // 4. 如果不需要响应，直接返回
            if (!expectResponse) {
                return null;
            }

            // 5. 等待收发切换
            Thread.sleep(TX_TO_RX_DELAY_MS);

            // 6. 读取响应
            byte[] response = readResponse();

            if (response != null) {
                messagesReceived++;
                String hexResponse = RFIDPacketHandler.bytesToHexString(response, response.length);
                Log.d(TAG, "Response received in " + (System.currentTimeMillis() - startTime) +
                        "ms: " + hexResponse);
            } else {
                timeouts++;
                Log.e(TAG, "No response received within timeout");
                notifyError("读取响应超时");
            }

            return response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Communication interrupted", e);
            notifyError("通信被中断");
            return null;
        } catch (IOException e) {
            Log.e(TAG, "IO Error while sending packet", e);
            notifyError("IO错误: " + e.getMessage());
            return null;
        }
    }

    /**
     * 读取响应报文
     *
     * @return 响应报文，超时返回null
     */
    private byte[] readResponse() {
        long startTime = System.currentTimeMillis();
        byte[] buffer = new byte[BUFFER_SIZE];
        int totalBytesRead = 0;

        Log.d(TAG, "Waiting for response...");

        while (System.currentTimeMillis() - startTime < READ_TIMEOUT_MS) {
            try {
                // 检查串口是否仍然有效
                if (!isConnected()) {
                    throw new IOException("Serial port disconnected");
                }

                // 检查可用数据
                int bytesAvailable = serialPortManager.mInputStream.available();
                if (bytesAvailable > 0) {
                    // 计算可以读取的字节数
                    int bytesToRead = Math.min(bytesAvailable, buffer.length - totalBytesRead);

                    if (bytesToRead > 0) {
                        // 读取数据
                        int bytesRead = serialPortManager.mInputStream.read(
                                buffer, totalBytesRead, bytesToRead);

                        if (bytesRead > 0) {
                            totalBytesRead += bytesRead;
                            Log.d(TAG, "Read " + bytesRead + " bytes, total: " + totalBytesRead);

                            // 检查是否是完整的响应
                            if (RFIDPacketHandler.isCompleteResponse(buffer, totalBytesRead)) {
                                byte[] response = new byte[totalBytesRead];
                                System.arraycopy(buffer, 0, response, 0, totalBytesRead);
                                return response;
                            }
                        }
                    }
                }

                // 没有数据，短暂等待后重试
                Thread.sleep(READ_RETRY_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Read interrupted");
                return null;
            } catch (IOException e) {
                Log.e(TAG, "IO Error while reading response", e);
                return null;
            }
        }

        // 超时
        Log.w(TAG, "Response timeout after " + READ_TIMEOUT_MS + "ms");
        return null;
    }

    // ==================== 后台监听功能 ====================

    /**
     * 启动后台监听线程
     * 用于异步接收RFID标签读取
     */
    public void startListening() {
        if (backgroundListenerThread == null || !backgroundListenerThread.isAlive()) {
            startBackgroundListening();
        }
    }

    /**
     * 停止后台监听线程
     */
    public void stopListening() {
        Log.d(TAG, "Stopping background listener");
        shouldListen.set(false);

        if (backgroundListenerThread != null) {
            try {
                backgroundListenerThread.interrupt();
                backgroundListenerThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping listener", e);
                Thread.currentThread().interrupt();
            }
            backgroundListenerThread = null;
        }
    }

    /**
     * 启动后台监听线程
     */
    private void startBackgroundListening() {
        shouldListen.set(true);

        backgroundListenerThread = new Thread(() -> {
            Log.i(TAG, "Background listener started");
            byte[] buffer = new byte[BUFFER_SIZE];

            while (shouldListen.get()) {
                try {
                    // 检查串口是否有效
                    if (!isConnected()) {
                        Log.e(TAG, "Serial port disconnected, stopping listener");
                        break;
                    }

                    // 检查是否有数据
                    if (serialPortManager.mInputStream.available() > 0) {
                        int bytesRead = serialPortManager.mInputStream.read(buffer);

                        if (bytesRead > 0) {
                            // 打印数据（仅十六进制格式）
                            String hexData = RFIDPacketHandler.bytesToHexString(buffer, bytesRead);
                            Log.d(TAG, "Background listener received " + bytesRead + " bytes: " + hexData);
                            // 处理临时读取模式
                            if (isTemporaryListening.get()) {
                                Log.d(TAG, "Temporary listening mode: capturing data");
                                temporaryRfidData.set(hexData);
                                synchronized (temporaryLock) {
                                    temporaryLock.notifyAll();
                                }
                            }
                            
                            // 通知所有RFID数据监听器
                            for (RFIDDataListener listener : rfidDataListeners) {
                                if (listener != null) {
                                    listener.onRfidDataReceived(hexData);
                                }
                            }
                            
                            // 处理异步响应
                            handleAsyncResponse(buffer, bytesRead);
                        }
                    } else {
                        // 没有数据，短暂休眠
                        Thread.sleep(20);
                    }

                } catch (IOException e) {
                    Log.e(TAG, "IO Error in background listener", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Background listener interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Log.i(TAG, "Background listener stopped");
        });

        backgroundListenerThread.setName("RFID-Background-Listener");
        backgroundListenerThread.start();
    }

    /**
     * 处理异步响应
     *
     * @param data   接收到的数据
     * @param length 数据长度
     */
    private void handleAsyncResponse(byte[] data, int length) {
        // 检查是否是完整的响应
        if (RFIDPacketHandler.isCompleteResponse(data, length)) {
            byte[] response = new byte[length];
            System.arraycopy(data, 0, response, 0, length);

            // 解析标签ID
            String tagId = RFIDPacketHandler.parseTagId(response);
            if (tagId != null && rfidListener != null) {
                Log.i(TAG, "Async tag read: " + tagId);
                rfidListener.onTagRead(tagId);
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 清空输入缓冲区
     */
    private void clearInputStream() throws IOException {
        if (serialPortManager != null && serialPortManager.mInputStream != null) {
            int available = serialPortManager.mInputStream.available();
            if (available > 0) {
                Log.d(TAG, "Clearing " + available + " bytes from input buffer");
                byte[] clearBuffer = new byte[available];
                int bytesRead = serialPortManager.mInputStream.read(clearBuffer);
                if (bytesRead > 0) {
                    Log.d(TAG, "Cleared: " +
                            RFIDPacketHandler.bytesToHexString(clearBuffer, bytesRead));
                }
            }
        }
    }

    /**
     * 通知错误给监听器
     *
     * @param error 错误信息
     */
    private void notifyError(String error) {
        if (rfidListener != null) {
            rfidListener.onCommunicationError(error);
        }
    }

    /**
     * 检查串口连接状态
     *
     * @return true 已连接，false 未连接
     */
    public boolean isConnected() {
        return serialPortManager != null &&
               serialPortManager.mSerialPort != null &&
               serialPortManager.mInputStream != null &&
               serialPortManager.mOutputStream != null;
    }


    /**
     * 临时读取RFID数据（5秒超时）
     * 用于用户点击"读取RFID"按钮时的场景
     *
     * @return 读取到的RFID数据（16进制字符串），超时返回null
     */
    public String readRfidWithTimeout() {
        Log.i(TAG, "Starting temporary RFID reading with 5s timeout");
        
        // 确保后台监听已启动
        if (!shouldListen.get()) {
            Log.i(TAG, "Background listener not running, starting it");
            startListening();
        }
        
        // 重置临时数据
        temporaryRfidData.set(null);
        isTemporaryListening.set(true);
        
        try {
            synchronized (temporaryLock) {
                // 等待5秒，直到收到数据或超时
                Log.d(TAG, "Waiting for RFID data... (5s timeout)");
                temporaryLock.wait(5000);
            }
            
            // 获取收到的数据
            String data = temporaryRfidData.get();
            if (data != null) {
                Log.i(TAG, "Successfully read RFID data: " + data);
            } else {
                Log.w(TAG, "No RFID data received within 5s timeout");
            }
            return data;
        } catch (InterruptedException e) {
            Log.e(TAG, "Reading interrupted", e);
            Thread.currentThread().interrupt();
            return null;
        } finally {
            // 退出临时监听模式
            isTemporaryListening.set(false);
            Log.i(TAG, "Exited temporary listening mode");
        }
    }

    /**
     * 关闭串口并释放资源
     */
    public void close() {
        Log.i(TAG, "Closing RFIDCommunicator");

        // 停止后台监听
        stopListening();

        // 关闭串口
        try {
            if (serialPortManager != null) {
                serialPortManager.closeSerialPort();
                serialPortManager = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing serial port", e);
        }

        // 清除单例引用
        if (instance == this) {
            instance = null;
        }

        // 输出统计信息
        Log.i(TAG, "Closed. Stats - Sent: " + messagesSent +
              ", Received: " + messagesReceived +
              ", Timeouts: " + timeouts +
              ", Checksum Errors: " + checksumErrors);
    }

    /**
     * 重启串口（用于更换端口或恢复连接）
     *
     * @param newPortName 新的串口路径
     * @throws IOException 初始化失败
     */
    public void restart(String newPortName) throws IOException {
        Log.i(TAG, "Restarting on port: " + newPortName);
        close();

        Device device = new Device();
        device.path = newPortName;
        device.speed = BAUD_RATE;
        this.serialPortManager = new SerialPortManager(device);
    }

    /**
     * 获取通信统计信息
     *
     * @return 统计信息字符串
     */
    public String getStats() {
        return String.format("Sent: %d, Received: %d, Timeouts: %d, Checksum Errors: %d",
                messagesSent, messagesReceived, timeouts, checksumErrors);
    }

    /**
     * 获取当前使用的串口路径
     *
     * @return 串口路径
     */
    public String getPortName() {
        return portName;
    }
}