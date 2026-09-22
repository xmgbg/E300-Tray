package com.ezhan.amr.communication.lora;

import android.util.Log;

/**
 * 机器人间交管 LoRa 通信帧 (固定 17 字节, 全 16 进制)。
 *
 * 发送格式: 00 {dstAddr} {channel} + 数据体(14字节)
 *   - dstAddr: 目标地址, 广播用 0xFF
 *   - channel: LoRa 信道
 *
 * 数据体(14字节):
 *   偏移  长度  字段        说明
 *   0     2     header      帧头 A5 5A
 *   2     1     cmd         命令字, 固定 0x01 = 交管
 *   3     2     robotId     机器人 ID (大端)
 *   5     4     mapHash     地图名 hash (大端, 固定 4 字节)
 *   9     1     building    楼栋号 (无符号 0-255)
 *   10    1     floor       楼层号 (有符号 -128~127, 支持地下层 -1)
 *   11    1     areaNum     区域编号 (从区域名 _traffic{N} 解析)
 *   12    1     state       状态: 0x01=APPROACHING, 0x02=INSIDE, 0x03=LEAVING
 *   13    1     checksum    校验和 (前13字节累加和低8位)
 *
 * 状态语义:
 *   APPROACHING: 正在接近/等待进入, 500ms 心跳
 *   INSIDE:      已在区域内拥有路权, 1000ms 心跳
 *   LEAVING:     正在离开, 连发3次后释放
 */
public final class TrafficLoraPacket {
    private static final String TAG = "TrafficLoraPacket";

    public static final byte FRAME_HEADER_1 = (byte) 0xA5;
    public static final byte FRAME_HEADER_2 = (byte) 0x5A;
    public static final byte CMD_TRAFFIC = 0x01;
    public static final byte BROADCAST_ADDRESS = (byte) 0xFF;

    public static final byte STATE_APPROACHING = 0x01;
    public static final byte STATE_INSIDE = 0x02;
    public static final byte STATE_LEAVING = 0x03;

    public static final int PACKET_LEN = 14;

    public final int robotId;
    public final int mapHash;
    public final int building;
    public final int floor;
    public final int areaNum;
    public final byte state;

    public TrafficLoraPacket(int robotId, int mapHash, int building, int floor,
                             int areaNum, byte state) {
        this.robotId = robotId;
        this.mapHash = mapHash;
        this.building = building;
        this.floor = floor;
        this.areaNum = areaNum;
        this.state = state;
    }

    /**
     * 构造完整发送 hex 串 (含 00 + 广播地址 + 信道 + 14字节数据体)。
     */
    public static String buildHex(int robotId, int mapHash, int areaNum, byte state, int channel) {
        return buildHex(robotId, mapHash, 0, 0, areaNum, state, channel, BROADCAST_ADDRESS);
    }

    /**
     * 构造完整发送 hex 串 (指定目标地址, 用于按配置逐个发送)。
     *
     * @param robotId    本机机器人 ID
     * @param mapHash    地图名 hash
     * @param building   楼栋号 (0-255)
     * @param floor      楼层号 (-128~127)
     * @param areaNum    区域编号
     * @param state      状态 (APPROACHING/INSIDE/LEAVING)
     * @param channel    LoRa 信道 (目标机器人信道)
     * @param destAddr   目标地址 (OtherRobot.address), 广播用 0xFF
     * @return hex 字符串 (固定 34 字符 = 17 字节)
     */
    public static String buildHex(int robotId, int mapHash, int building, int floor,
                                  int areaNum, byte state, int channel, int destAddr) {
        byte[] body = new byte[PACKET_LEN];
        body[0] = FRAME_HEADER_1;
        body[1] = FRAME_HEADER_2;
        body[2] = CMD_TRAFFIC;
        body[3] = (byte) ((robotId >> 8) & 0xFF);
        body[4] = (byte) (robotId & 0xFF);
        body[5] = (byte) ((mapHash >> 24) & 0xFF);
        body[6] = (byte) ((mapHash >> 16) & 0xFF);
        body[7] = (byte) ((mapHash >> 8) & 0xFF);
        body[8] = (byte) (mapHash & 0xFF);
        body[9] = (byte) (building & 0xFF);
        body[10] = (byte) floor;  // 有符号转 byte, 地下层 -1 -> 0xFF
        body[11] = (byte) (areaNum & 0xFF);
        body[12] = state;
        body[13] = calculateChecksum(body, 0, PACKET_LEN - 1);

        StringBuilder sb = new StringBuilder(36);
        // 前缀: 00 + 目标地址 + 信道
        sb.append("00");
        sb.append(String.format("%02X", destAddr & 0xFF));
        sb.append(String.format("%02X", channel & 0xFF));
        for (byte b : body) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 从接收字节流解析交管帧。
     *
     * @param data   字节数组
     * @param offset 起始偏移
     * @return 解析成功返回 packet, 否则 null
     */
    public static TrafficLoraPacket parse(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + PACKET_LEN > data.length) {
            return null;
        }
        // 帧头校验
        if (data[offset] != FRAME_HEADER_1 || data[offset + 1] != FRAME_HEADER_2) {
            return null;
        }
        // 命令字校验
        if (data[offset + 2] != CMD_TRAFFIC) {
            return null;
        }
        // 校验和校验
        byte expected = calculateChecksum(data, offset, offset + PACKET_LEN - 1);
        if (expected != data[offset + PACKET_LEN - 1]) {
            Log.w(TAG, "Checksum mismatch: expected=" + (expected & 0xFF)
                    + ", actual=" + (data[offset + PACKET_LEN - 1] & 0xFF));
            return null;
        }
        int robotId = ((data[offset + 3] & 0xFF) << 8) | (data[offset + 4] & 0xFF);
        int mapHash = ((data[offset + 5] & 0xFF) << 24)
                | ((data[offset + 6] & 0xFF) << 16)
                | ((data[offset + 7] & 0xFF) << 8)
                | (data[offset + 8] & 0xFF);
        int building = data[offset + 9] & 0xFF;           // 无符号 0-255
        int floor = (byte) data[offset + 10];             // 有符号, 地下层 -1
        int areaNum = data[offset + 11] & 0xFF;
        byte state = data[offset + 12];
        return new TrafficLoraPacket(robotId, mapHash, building, floor, areaNum, state);
    }

    /** 计算校验和: 范围内字节累加取低8位 */
    private static byte calculateChecksum(byte[] data, int start, int endExclusive) {
        int sum = 0;
        for (int i = start; i < endExclusive; i++) {
            sum += (data[i] & 0xFF);
        }
        return (byte) (sum & 0xFF);
    }

    public boolean isApproaching() {
        return state == STATE_APPROACHING;
    }

    public boolean isInside() {
        return state == STATE_INSIDE;
    }

    public boolean isLeaving() {
        return state == STATE_LEAVING;
    }

    /** 状态字转字符串 (日志用) */
    public String stateString() {
        switch (state) {
            case STATE_APPROACHING: return "APPROACHING";
            case STATE_INSIDE:      return "INSIDE";
            case STATE_LEAVING:     return "LEAVING";
            default:                return "UNKNOWN(" + state + ")";
        }
    }

    @Override
    public String toString() {
        String stateStr;
        switch (state) {
            case STATE_APPROACHING: stateStr = "APPROACHING"; break;
            case STATE_INSIDE:      stateStr = "INSIDE"; break;
            case STATE_LEAVING:     stateStr = "LEAVING"; break;
            default:                stateStr = "UNKNOWN(" + state + ")"; break;
        }
        return "TrafficLoraPacket{robotId=" + robotId
                + ", mapHash=0x" + Integer.toHexString(mapHash)
                + ", building=" + building
                + ", floor=" + floor
                + ", areaNum=" + areaNum
                + ", state=" + stateStr + "}";
    }
}
