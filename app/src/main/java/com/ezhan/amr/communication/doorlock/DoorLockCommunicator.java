package com.ezhan.amr.communication.doorlock;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import android_serialport_api.Device;
import android_serialport_api.SerialPortManager;

/**
 * 门锁RS485通信控制器
 * 通过串口ttyS7与门锁控制器进行RS485通信
 *
 * 功能：
 * 1. 发送开锁命令（锁2、锁3）
 * 2. 查询门锁状态
 * 3. 接收和解析响应报文
 * 4. 支持同步和异步通信模式
 */
public class DoorLockCommunicator {

    private static final String TAG = "DoorLockCommunicator";

    // RS485通信参数配置
    private static final int BAUD_RATE = 9600;              // 波特率
    private static final int TX_TO_RX_DELAY_MS = 100;       // 发送后等待响应的延迟（RS485半双工切换时间）
    private static final int READ_TIMEOUT_MS = 2000;        // 读取响应超时时间
    private static final int READ_RETRY_DELAY_MS = 10;      // 轮询检查数据的间隔
    private static final int MAX_RESPONSE_SIZE = 5;         // 响应报文固定长度（5字节）

    // 串口设备路径
    public static final String DEFAULT_PORT = "/dev/ttyS7";

    // 串口管理器
    private SerialPortManager serialPortManager;
    private final String portName;

    // 单例实例
    private static DoorLockCommunicator instance;

    // 后台监听线程（用于异步接收）
    private Thread backgroundListenerThread;
    private final AtomicBoolean shouldListen = new AtomicBoolean(false);
    private static final int BUFFER_SIZE = 1024;

    // 状态回调监听器
    private volatile DoorLockListener doorLockListener;

    // 通信统计
    private int messagesSent = 0;
    private int messagesReceived = 0;
    private int timeouts = 0;
    private int checksumErrors = 0;

    /**
     * 门锁状态监听器接口
     * 用于接收异步状态变化通知
     */
    public interface DoorLockListener {
        /**
         * 门锁状态变化回调
         *
         * @param lockId 锁编号（2或3）
         * @param status 门锁状态
         */
        void onLockStatusChanged(int lockId, DoorLockStatus status);

        /**
         * 通信错误回调
         *
         * @param error 错误信息
         */
        void onCommunicationError(String error);
    }

    /**
     * 获取单例实例
     *
     * @param portName 串口设备路径，如 "/dev/ttyS7"
     * @return DoorLockCommunicator实例
     * @throws IOException 初始化失败时抛出
     */
    public static synchronized DoorLockCommunicator getInstance(String portName) throws IOException {
        if (instance == null) {
            instance = new DoorLockCommunicator(portName);
        }
        return instance;
    }

    /**
     * 获取默认端口的单例实例
     *
     * @return DoorLockCommunicator实例
     * @throws IOException 初始化失败时抛出
     */
    public static synchronized DoorLockCommunicator getInstance() throws IOException {
        return getInstance(DEFAULT_PORT);
    }

    /**
     * 私有构造函数
     *
     * @param portName 串口设备路径
     * @throws IOException 串口初始化失败
     */
    private DoorLockCommunicator(String portName) throws IOException {
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

            Log.i(TAG, "DoorLockCommunicator initialized on port: " + portName);

        } catch (Exception e) {
            close();
            throw new IOException("Failed to initialize serial port: " + e.getMessage(), e);
        }
    }

    /**
     * 设置门锁状态监听器
     *
     * @param listener 监听器实例
     */
    public void setDoorLockListener(DoorLockListener listener) {
        Log.d(TAG, "Setting DoorLockListener: " + listener);
        this.doorLockListener = listener;
    }

    // ==================== 核心功能方法 ====================

    /**
     * 开锁命令 - 门锁2
     *
     * @return true 发送成功，false 发送失败
     */
    public boolean unlockLock2() {
        Log.i(TAG, "Sending unlock command to Lock 2");
        byte[] packet = DoorLockPacketHandler.createUnlockPacket(DoorLockPacketHandler.LOCK_2);
        return sendCommand(packet, false) != null;
    }

    /**
     * 开锁命令 - 门锁3
     *
     * @return true 发送成功，false 发送失败
     */
    public boolean unlockLock3() {
        Log.i(TAG, "Sending unlock command to Lock 3");
        byte[] packet = DoorLockPacketHandler.createUnlockPacket(DoorLockPacketHandler.LOCK_3);
        return sendCommand(packet, false) != null;
    }

    /**
     * 查询门锁2状态
     *
     * @return DoorLockStatus 门锁状态，如果通信失败返回 UNKNOWN
     */
    public DoorLockStatus queryLock2Status() {
        Log.i(TAG, "Querying Lock 2 status");
        byte[] packet = DoorLockPacketHandler.createQueryPacket(DoorLockPacketHandler.LOCK_2);
        byte[] response = sendCommand(packet, true);

        if (response == null) {
            return DoorLockStatus.UNKNOWN;
        }

        DoorLockPacketHandler.DoorLockResponse parsed = DoorLockPacketHandler.parseResponse(response);
        // 验证响应是否对应门锁2
        if (parsed != null && parsed.lockId == DoorLockPacketHandler.LOCK_2) {
            return parsed.status;
        } else {
            Log.e(TAG, "Invalid response for Lock 2: " + (parsed != null ? "lockId=" + parsed.lockId : "null"));
            return DoorLockStatus.UNKNOWN;
        }
    }

    /**
     * 查询门锁3状态
     *
     * @return DoorLockStatus 门锁状态，如果通信失败返回 UNKNOWN
     */
    public DoorLockStatus queryLock3Status() {
        Log.i(TAG, "Querying Lock 3 status");
        byte[] packet = DoorLockPacketHandler.createQueryPacket(DoorLockPacketHandler.LOCK_3);
        byte[] response = sendCommand(packet, true);

        if (response == null) {
            return DoorLockStatus.UNKNOWN;
        }

        DoorLockPacketHandler.DoorLockResponse parsed = DoorLockPacketHandler.parseResponse(response);
        // 验证响应是否对应门锁3
        if (parsed != null && parsed.lockId == DoorLockPacketHandler.LOCK_3) {
            return parsed.status;
        } else {
            Log.e(TAG, "Invalid response for Lock 3: " + (parsed != null ? "lockId=" + parsed.lockId : "null"));
            return DoorLockStatus.UNKNOWN;
        }
    }

    /**
     * 查询指定门锁状态
     *
     * @param lockId 锁编号（2或3）
     * @return DoorLockStatus 门锁状态
     */
    public DoorLockStatus queryLockStatus(int lockId) {
        if (lockId == 2) {
            return queryLock2Status();
        } else if (lockId == 3) {
            return queryLock3Status();
        } else {
            Log.e(TAG, "Invalid lock ID: " + lockId);
            return DoorLockStatus.UNKNOWN;
        }
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
            String hexPacket = DoorLockPacketHandler.bytesToHexString(packet, packet.length);
            Log.d(TAG, "Sending packet: " + hexPacket);

            // 3. 发送数据
            serialPortManager.sendPacket(packet);
            messagesSent++;

            Log.d(TAG, "Packet sent in " + (System.currentTimeMillis() - startTime) + "ms");

            // 4. 如果不需要响应，直接返回
            if (!expectResponse) {
                return null;
            }

            // 5. 等待RS485收发切换
            Thread.sleep(TX_TO_RX_DELAY_MS);

            // 6. 读取响应
            byte[] response = readResponse();

            if (response != null) {
                messagesReceived++;
                String hexResponse = DoorLockPacketHandler.bytesToHexString(response, response.length);
                Log.d(TAG, "Response received in " + (System.currentTimeMillis() - startTime) +
                        "ms: " + hexResponse);

                // 验证响应
                DoorLockPacketHandler.DoorLockResponse parsed = DoorLockPacketHandler.parseResponse(response);
                if (parsed == null) {
                    checksumErrors++;
                    Log.e(TAG, "Failed to parse response or checksum error");
                    notifyError("响应报文校验失败");
                }
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
     * @return 5字节的响应报文，超时返回null
     */
    private byte[] readResponse() {
        long startTime = System.currentTimeMillis();
        byte[] buffer = new byte[MAX_RESPONSE_SIZE];
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

                            // 如果读取到完整的5字节响应，立即返回
                            if (totalBytesRead >= MAX_RESPONSE_SIZE) {
                                byte[] response = new byte[MAX_RESPONSE_SIZE];
                                System.arraycopy(buffer, 0, response, 0, MAX_RESPONSE_SIZE);
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
     * 用于异步接收门锁状态变化
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

                        if (bytesRead >= MAX_RESPONSE_SIZE) {
                            Log.d(TAG, "Background listener received " + bytesRead + " bytes");
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

        backgroundListenerThread.setName("DoorLock-Background-Listener");
        backgroundListenerThread.start();
    }

    /**
     * 处理异步响应
     *
     * @param data   接收到的数据
     * @param length 数据长度
     */
    private void handleAsyncResponse(byte[] data, int length) {
        // 查找完整的5字节响应帧
        for (int i = 0; i <= length - MAX_RESPONSE_SIZE; i++) {
            // 检查是否是有效的响应（以0x80或0x8A开头）
            byte firstByte = data[i];
            if (firstByte == DoorLockPacketHandler.CMD_QUERY ||
                firstByte == DoorLockPacketHandler.CMD_UNLOCK) {

                // 提取5字节响应
                byte[] response = new byte[MAX_RESPONSE_SIZE];
                System.arraycopy(data, i, response, 0, MAX_RESPONSE_SIZE);

                // 解析响应
                DoorLockPacketHandler.DoorLockResponse parsed =
                    DoorLockPacketHandler.parseResponse(response);

                if (parsed != null && doorLockListener != null) {
                    int lockId = parsed.lockId & 0xFF;
                    Log.i(TAG, "Async status update - Lock " + lockId + ": " + parsed.status);
                    doorLockListener.onLockStatusChanged(lockId, parsed.status);
                }
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
                        DoorLockPacketHandler.bytesToHexString(clearBuffer, bytesRead));
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
        if (doorLockListener != null) {
            doorLockListener.onCommunicationError(error);
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
     * 关闭串口并释放资源
     */
    public void close() {
        Log.i(TAG, "Closing DoorLockCommunicator");

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
