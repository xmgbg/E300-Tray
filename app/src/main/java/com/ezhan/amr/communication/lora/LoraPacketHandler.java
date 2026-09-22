package com.ezhan.amr.communication.lora;

public class LoraPacketHandler {

    public static final byte FRAME_HEADER_1 = (byte) 0xA0;
    public static final byte FRAME_HEADER_2 = (byte) 0x0A;
    public static final byte ROBOT_ADDRESS = (byte) 0x0A;
    public static final byte FIXED_RESERVE_1 = (byte) 0x10;
    public static final byte FIXED_RESERVE_2 = (byte) 0x01;

    public static final byte STATUS_IDLE = (byte) 0x0B;
    public static final byte STATUS_BUSY = (byte) 0x0C;
    public static final byte STATUS_CALL_SUCCESS = (byte) 0x0A;
    public static final byte STATUS_CALL_FAILED = (byte) 0x00;

    public static byte[] createIdleResponsePacket(byte callBoxAddress, byte channel) {
        return createResponsePacket(callBoxAddress, channel, STATUS_IDLE);
    }

    public static byte[] createBusyResponsePacket(byte callBoxAddress, byte channel) {
        return createResponsePacket(callBoxAddress, channel, STATUS_BUSY);
    }

    public static byte[] createCallSuccessPacket(byte callBoxAddress, byte channel) {
        return createResponsePacket(callBoxAddress, channel, STATUS_CALL_SUCCESS);
    }

    public static byte[] createCallFailedPacket(byte callBoxAddress, byte channel) {
        return createResponsePacket(callBoxAddress, channel, STATUS_CALL_FAILED);
    }

    private static byte[] createResponsePacket(byte callBoxAddress, byte channel, byte status) {
        byte[] fullPacket = new byte[9];

        fullPacket[0] = 0x00;
        fullPacket[1] = callBoxAddress;
        fullPacket[2] = channel;
        fullPacket[3] = FRAME_HEADER_1;
        fullPacket[4] = FRAME_HEADER_2;
        fullPacket[5] = status;
        fullPacket[6] = FIXED_RESERVE_1;
        fullPacket[7] = FIXED_RESERVE_2;

        // 校验码只计算 Lora 过滤后实际到达呼叫盒的 5 个字节（data[3]~data[7]）
        fullPacket[8] = calculateChecksum(fullPacket, 3, 8);

        return fullPacket;
    }

    public static byte calculateChecksum(byte[] data, int start, int end) {
        byte checksum = 0;
        for (int i = start; i < end; i++) {
            checksum ^= data[i];
        }
        return checksum;
    }

    public static boolean verifyChecksum(byte[] data) {
        if (data == null || data.length < 6) {
            return false;
        }
        byte calculated = calculateChecksum(data, 0, 5);
        return calculated == data[5];
    }

    public static CallBoxPacket parseCallBoxPacket(byte[] data) {
        if (data == null || data.length < 6) {
            return null;
        }

        if (data[0] != FRAME_HEADER_1 || data[1] != FRAME_HEADER_2) {
            return null;
        }

        if (!verifyChecksum(data)) {
            return null;
        }

        byte callBoxAddress = data[2];
        byte callBoxChannel = data[3];
        byte buttonId = data[4];

        return new CallBoxPacket(callBoxAddress, callBoxChannel, buttonId);
    }

    public static class CallBoxPacket {
        public final byte callBoxAddress;
        public final byte callBoxChannel;
        public final byte buttonId;

        public CallBoxPacket(byte callBoxAddress, byte callBoxChannel, byte buttonId) {
            this.callBoxAddress = callBoxAddress;
            this.callBoxChannel = callBoxChannel;
            this.buttonId = buttonId;
        }

        public boolean isQueryCommand() {
            return buttonId == (byte) 0xFF;
        }

        public boolean isButton1Command() {
            return buttonId == 0x01;
        }

        public boolean isButton2Command() {
            return buttonId == 0x02;
        }

        public boolean isButton3Command() {
            return buttonId == 0x03;
        }
    }
}