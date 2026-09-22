package com.ezhan.amr.navigation.area;

import android.util.Log;

import androidx.lifecycle.LiveData;

import com.ezhan.amr.communication.lora.LoraRequestScheduler;
import com.ezhan.amr.data.datatype.MapArea;
import com.ezhan.amr.viewmodels.BasicViewModel;

public class MapAreaDeviceDispatcher {
    private static final String TAG = "MapAreaDeviceDispatcher";
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final String DOOR_AREA_TYPE = "\u95e8\u63a7\u533a\u57df";
    private static final String INFO_AREA_TYPE = "\u4fe1\u606f\u533a\u57df";
    private static final String LEGACY_DOOR_AREA_TYPE = "\u95c2\u3126\u5e36\u9356\u54c4\u7159";
    private static final String LEGACY_INFO_AREA_TYPE = "\u6dc7\u2103\u4f05\u9356\u54c4\u7159";

    private final LoraRequestScheduler loraScheduler;
    private final BasicViewModel basicViewModel;

    public MapAreaDeviceDispatcher(LoraRequestScheduler loraScheduler,
                                   BasicViewModel basicViewModel) {
        this.loraScheduler = loraScheduler;
        this.basicViewModel = basicViewModel;
    }

    public void dispatch(MapArea area) {
        if (area == null || area.getType() == null) {
            return;
        }

        if (isDoorArea(area.getType())) {
            sendDoorOpenSignal(area);
        } else if (isInfoArea(area.getType())) {
            sendInfoAreaSignal(area);
        }
    }

    private void sendDoorOpenSignal(MapArea area) {
        String address = normalizeHexByte(area.getAddress(), "address", area);
        String channel = normalizeHexByte(area.getChannel(), "channel", area);
        if (address == null || channel == null) {
            return;
        }

        int robotAddress = getLiveDataValue(basicViewModel.getLoraAddress(), 0);
        int robotChannel = getLiveDataValue(basicViewModel.getLoraChannel(), 0);
        String hexRobotAddress = String.format("%02X", robotAddress & 0xFF);
        String hexRobotChannel = String.format("%02X", robotChannel & 0xFF);

        String mainDoorMessage = "0550" + hexRobotAddress + hexRobotChannel + "21010000";
        String doorOpenMessage = "00" + address + channel
                + mainDoorMessage
                + calculateChecksum(mainDoorMessage);

        Log.d(TAG, "Queue door open LoRa for area " + area.getName() + ": " + doorOpenMessage);
        loraScheduler.enqueue("MAP_AREA_DOOR", doorOpenMessage, false,
                DEFAULT_TIMEOUT_MS, LoraRequestScheduler.PRIORITY_MAP_AREA);
    }

    private void sendInfoAreaSignal(MapArea area) {
        String address = normalizeHexByte(area.getAddress(), "address", area);
        String channel = normalizeHexByte(area.getChannel(), "channel", area);
        if (address == null || channel == null) {
            return;
        }

        int robotAddress = getLiveDataValue(basicViewModel.getLoraAddress(), 0);
        int robotChannel = getLiveDataValue(basicViewModel.getLoraChannel(), 0);
        String hexRobotAddress = String.format("%02X", robotAddress & 0xFF);
        String hexRobotChannel = String.format("%02X", robotChannel & 0xFF);

        // 功能码0x31=控制播放音频, 参数0x01=开始播放
        String mainInfoMessage = "0550" + hexRobotAddress + hexRobotChannel + "31010000";
        String loraMessage = "00" + address + channel
                + mainInfoMessage
                + calculateChecksum(mainInfoMessage);

        Log.d(TAG, "Queue info area LoRa for area " + area.getName() + ": " + loraMessage);
        loraScheduler.enqueue("MAP_AREA_INFO", loraMessage, false,
                DEFAULT_TIMEOUT_MS, LoraRequestScheduler.PRIORITY_INFO_AREA);
    }

    private static boolean isDoorArea(String type) {
        return DOOR_AREA_TYPE.equals(type)
                || LEGACY_DOOR_AREA_TYPE.equals(type)
                || type.contains(DOOR_AREA_TYPE.substring(0, 2))
                || type.contains(LEGACY_DOOR_AREA_TYPE.substring(0, 3));
    }

    private static boolean isInfoArea(String type) {
        return INFO_AREA_TYPE.equals(type)
                || LEGACY_INFO_AREA_TYPE.equals(type)
                || type.contains(INFO_AREA_TYPE.substring(0, 2))
                || type.contains(LEGACY_INFO_AREA_TYPE.substring(0, 3));
    }

    private static int getLiveDataValue(LiveData<Integer> liveData, int defaultValue) {
        Integer value = liveData != null ? liveData.getValue() : null;
        return value != null ? value : defaultValue;
    }

    private static String normalizeHexByte(String value, String fieldName, MapArea area) {
        if (value == null || value.trim().isEmpty()) {
            Log.w(TAG, "Area " + area.getName() + " has empty " + fieldName);
            return null;
        }

        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }

        if (normalized.length() == 1) {
            normalized = "0" + normalized;
        }

        if (normalized.length() != 2 || !normalized.matches("[0-9A-Fa-f]{2}")) {
            Log.w(TAG, "Area " + area.getName() + " has invalid hex "
                    + fieldName + ": " + value);
            return null;
        }

        return normalized.toUpperCase();
    }

    private static String calculateChecksum(String hexMessage) {
        int checksum = 0;
        for (int i = 0; i < hexMessage.length(); i += 2) {
            String byteStr = hexMessage.substring(i, i + 2);
            checksum ^= Integer.parseInt(byteStr, 16);
        }
        return String.format("%02X", checksum);
    }
}
