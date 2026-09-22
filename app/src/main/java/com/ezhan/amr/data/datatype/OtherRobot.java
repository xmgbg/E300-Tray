package com.ezhan.amr.data.datatype;

/**
 * 交管区域内其他参与机器人信息 (同车队其他需协调的机器人)。
 * Gson 序列化, 字段需保持 public 或有 getter/setter。
 */
public class OtherRobot {
    /** 机器人 ID */
    private int robotId;
    /** LoRa 信道 */
    private String channel;
    /** LoRa 地址 */
    private String address;

    public OtherRobot() {
    }

    public OtherRobot(int robotId, String channel, String address) {
        this.robotId = robotId;
        this.channel = channel;
        this.address = address;
    }

    public int getRobotId() {
        return robotId;
    }

    public void setRobotId(int robotId) {
        this.robotId = robotId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "robotId=" + robotId + ", ch=" + channel + ", addr=" + address;
    }
}
