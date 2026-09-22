package com.ezhan.amr.data.datatype;

public class RobotCommunicationParams {
    private int robotId;
    private int loraChannel;
    private int loraAddress;

    public RobotCommunicationParams(int robotId, int loraChannel, int loraAddress) {
        this.robotId = robotId;
        this.loraChannel = loraChannel;
        this.loraAddress = loraAddress;
    }

    public int getRobotId() {
        return robotId;
    }

    public void setRobotId(int robotId) {
        this.robotId = robotId;
    }

    public int getLoraChannel() {
        return loraChannel;
    }

    public void setLoraChannel(int loraChannel) {
        this.loraChannel = loraChannel;
    }

    public int getLoraAddress() {
        return loraAddress;
    }

    public void setLoraAddress(int loraAddress) {
        this.loraAddress = loraAddress;
    }
}
