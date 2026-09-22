package com.ezhan.amr.communication.rfid;

import android.util.Log;

/**
 * RFID数据包处理器
 * 用于创建和解析RFID通信数据包
 */
public class RFIDPacketHandler {

    private static final String TAG = "RFIDPacketHandler";

    // 命令类型
    public static final byte CMD_READ = 0x01;      // 读取标签
    public static final byte CMD_SUCCESS = 0x00;   // 成功响应
    public static final byte CMD_ERROR = (byte) 0xFF;     // 错误响应

    // 数据包格式
    // 读取命令: [起始字节][命令类型][长度][校验和]
    // 响应: [起始字节][状态][长度][数据][校验和]

    public static final byte START_BYTE = (byte) 0xAA;    // 起始字节

    /**
     * 创建读取标签命令数据包
     *
     * @return 读取命令数据包
     */
    public static byte[] createReadPacket() {
        byte[] packet = new byte[4];
        packet[0] = START_BYTE;
        packet[1] = CMD_READ;
        packet[2] = 0x00;  // 长度
        packet[3] = calculateChecksum(packet, 0, 3);
        return packet;
    }

    /**
     * 检查是否是完整的响应数据包
     *
     * @param data 接收到的数据
     * @param length 数据长度
     * @return true 是完整的响应，false 不是
     */
    public static boolean isCompleteResponse(byte[] data, int length) {
        if (length < 4) {
            return false;
        }
        
        // 检查起始字节
        if (data[0] != START_BYTE) {
            return false;
        }
        
        // 检查长度
        int expectedLength = 4 + (data[2] & 0xFF);
        return length >= expectedLength;
    }

    /**
     * 解析标签ID
     *
     * @param response 响应数据包
     * @return 标签ID字符串，如果解析失败返回null
     */
    public static String parseTagId(byte[] response) {
        if (response == null || response.length < 4) {
            Log.e(TAG, "Invalid response length");
            return null;
        }
        
        // 检查起始字节
        if (response[0] != START_BYTE) {
            Log.e(TAG, "Invalid start byte");
            return null;
        }
        
        // 检查状态
        if (response[1] != CMD_SUCCESS) {
            Log.e(TAG, "Command failed with status: " + (response[1] & 0xFF));
            return null;
        }
        
        // 检查校验和
        if (!validateChecksum(response)) {
            Log.e(TAG, "Checksum validation failed");
            return null;
        }
        
        // 提取标签数据
        int dataLength = response[2] & 0xFF;
        if (dataLength > 0 && response.length >= 4 + dataLength) {
            byte[] tagData = new byte[dataLength];
            System.arraycopy(response, 3, tagData, 0, dataLength);
            return bytesToHexString(tagData, dataLength);
        }
        
        return null;
    }



    /**
     * 计算校验和
     *
     * @param data 数据
     * @param start 起始位置
     * @param end 结束位置（不包含）
     * @return 校验和
     */
    public static byte calculateChecksum(byte[] data, int start, int end) {
        byte checksum = 0;
        for (int i = start; i < end; i++) {
            checksum ^= data[i];
        }
        return checksum;
    }

    /**
     * 验证校验和
     *
     * @param data 数据
     * @return true 校验和正确，false 校验和错误
     */
    public static boolean validateChecksum(byte[] data) {
        if (data.length < 4) {
            return false;
        }
        
        byte calculatedChecksum = calculateChecksum(data, 0, data.length - 1);
        return calculatedChecksum == data[data.length - 1];
    }

    /**
     * 将字节数组转换为十六进制字符串
     *
     * @param bytes 字节数组
     * @param length 长度
     * @return 十六进制字符串
     */
    public static String bytesToHexString(byte[] bytes, int length) {
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

    /**
     * 将十六进制字符串转换为字节数组
     *
     * @param hexString 十六进制字符串
     * @return 字节数组
     */
    public static byte[] hexStringToByteArray(String hexString) {
        String cleanHex = hexString.replaceAll("\\s", "");
        
        // 检查长度是否为偶数
        if (cleanHex.length() % 2 != 0) {
            cleanHex = "0" + cleanHex;
        }
        
        byte[] data = new byte[cleanHex.length() / 2];
        for (int i = 0; i < cleanHex.length(); i += 2) {
            String byteStr = cleanHex.substring(i, i + 2);
            data[i/2] = (byte) Integer.parseInt(byteStr, 16);
        }
        
        return data;
    }
}