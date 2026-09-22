package com.ezhan.amr.communication.doorlock;

/**
 * 门锁通信协议数据包处理器
 * 负责构建请求报文、解析响应报文和校验和计算
 *
 * 报文格式说明（5字节固定长度）：
 * 字节0: 命令码
 *   - 0x8A: 开锁命令
 *   - 0x80: 查询状态命令
 * 字节1: 设备地址（固定0x01）
 * 字节2: 锁编号（0x02=锁2, 0x03=锁3）
 * 字节3: 操作码/状态码
 *   - 发送时: 0x11=开锁, 0x33=查询
 *   - 响应时: 0x00=开启, 0x11=关闭
 * 字节4: 校验和（字节0-3累加和取低8位）
 */
public class DoorLockPacketHandler {

    private static final String TAG = "DoorLockPacketHandler";

    // 命令码定义
    public static final byte CMD_UNLOCK = (byte) 0x8A;      // 开锁命令
    public static final byte CMD_QUERY = (byte) 0x80;       // 查询状态命令

    // 设备地址
    public static final byte DEVICE_ADDRESS = 0x01;

    // 锁编号
    public static final byte LOCK_2 = 0x02;                 // 门锁2
    public static final byte LOCK_3 = 0x03;                 // 门锁3

    // 操作码
    public static final byte OP_UNLOCK = 0x11;              // 开锁操作
    public static final byte OP_QUERY = 0x33;               // 查询操作

    // 状态码（响应中）
    public static final byte STATUS_OPEN = 0x00;            // 开启状态
    public static final byte STATUS_CLOSED = 0x11;          // 关闭状态

    /**
     * 创建开锁命令报文
     *
     * @param lockId 锁编号（LOCK_2=0x02 或 LOCK_3=0x03）
     * @return 5字节的开锁命令报文
     */
    public static byte[] createUnlockPacket(byte lockId) {
        byte[] packet = new byte[5];
        packet[0] = CMD_UNLOCK;           // 命令码: 开锁
        packet[1] = DEVICE_ADDRESS;       // 设备地址
        packet[2] = lockId;               // 锁编号
        packet[3] = OP_UNLOCK;            // 操作码: 开锁
        packet[4] = calculateChecksum(packet, 0, 4);  // 校验和
        return packet;
    }

    /**
     * 创建查询状态命令报文
     *
     * @param lockId 锁编号（LOCK_2=0x02 或 LOCK_3=0x03）
     * @return 5字节的查询状态命令报文
     */
    public static byte[] createQueryPacket(byte lockId) {
        byte[] packet = new byte[5];
        packet[0] = CMD_QUERY;            // 命令码: 查询
        packet[1] = DEVICE_ADDRESS;       // 设备地址
        packet[2] = lockId;               // 锁编号
        packet[3] = OP_QUERY;             // 操作码: 查询
        packet[4] = calculateChecksum(packet, 0, 4);  // 校验和
        return packet;
    }

    /**
     * 解析响应报文
     *
     * @param response 响应字节数组
     * @return 解析后的DoorLockResponse对象，如果解析失败返回null
     */
    public static DoorLockResponse parseResponse(byte[] response) {
        if (response == null || response.length != 5) {
            return null;
        }

        // 验证校验和
        byte calculatedChecksum = calculateChecksum(response, 0, 4);
        if (calculatedChecksum != response[4]) {
            return null;  // 校验和错误
        }

        DoorLockResponse result = new DoorLockResponse();
        result.command = response[0];
        result.deviceAddress = response[1];
        result.lockId = response[2];
        result.statusCode = response[3];
        result.checksum = response[4];

        // 根据状态码判断门锁状态
        result.status = DoorLockStatus.fromCode(result.statusCode & 0xFF);

        return result;
    }

    /**
     * 计算校验和
     * 将字节0到字节3进行按位异或，结果作为校验和
     *
     * @param data  数据数组
     * @param start 起始位置（包含）
     * @param end   结束位置（不包含）
     * @return 计算得到的校验和字节
     */
    public static byte calculateChecksum(byte[] data, int start, int end) {
        if (data == null || data.length == 0 || start >= end || start >= data.length) {
            return 0;
        }
        
        int checksum = data[start] & 0xFF;
        for (int i = start + 1; i < end && i < data.length; i++) {
            checksum ^= (data[i] & 0xFF);  // 按位异或计算
        }
        return (byte) checksum;
    }

    /**
     * 将字节数组转换为十六进制字符串（用于日志输出）
     *
     * @param bytes  字节数组
     * @param length 要转换的长度
     * @return 十六进制字符串，如 "8A 01 02 11 98"
     */
    public static String bytesToHexString(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder(length * 3);
        for (int i = 0; i < length && i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String hex = Integer.toHexString(bytes[i] & 0xFF);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex.toUpperCase());
        }
        return sb.toString();
    }

    /**
     * 将十六进制字符串转换为字节数组
     *
     * @param hexString 十六进制字符串，如 "8A 01 02 11 98" 或 "8A01021198"
     * @return 转换后的字节数组
     * @throws IllegalArgumentException 如果格式无效
     */
    public static byte[] hexStringToByteArray(String hexString) {
        // 移除所有空格
        String cleanHex = hexString.replaceAll("\\s", "");

        // 验证格式
        if (!cleanHex.matches("^([0-9A-Fa-f]{2})+$")) {
            throw new IllegalArgumentException("Invalid hex format: " + hexString);
        }

        byte[] data = new byte[cleanHex.length() / 2];
        for (int i = 0; i < cleanHex.length(); i += 2) {
            String byteStr = cleanHex.substring(i, i + 2);
            data[i / 2] = (byte) Integer.parseInt(byteStr, 16);
        }
        return data;
    }

    /**
     * 门锁响应数据类
     * 封装解析后的响应信息
     */
    public static class DoorLockResponse {
        public byte command;           // 命令码
        public byte deviceAddress;     // 设备地址
        public byte lockId;            // 锁编号
        public byte statusCode;        // 状态码
        public byte checksum;          // 校验和
        public DoorLockStatus status;  // 解析后的状态枚举

        @Override
        public String toString() {
            return String.format(
                "DoorLockResponse{cmd=0x%02X, addr=0x%02X, lock=0x%02X, status=0x%02X, checksum=0x%02X, state=%s}",
                command & 0xFF, deviceAddress & 0xFF, lockId & 0xFF,
                statusCode & 0xFF, checksum & 0xFF, status
            );
        }
    }
}
