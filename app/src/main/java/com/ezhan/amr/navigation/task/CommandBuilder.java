package com.ezhan.amr.navigation.task;

import java.util.Map;

public class CommandBuilder {
    /**
     * Builds hex message based on command type and parameters
     * @param type Elevator command type
     * @param params Key-value pairs (e.g., {"floor":"3", "direction":"up"})
     * @return Space-separated hex string (e.g., "00 01 09 05 50")
     */
    public static String buildHexMessage(NavigationOrderType type, Map<String, Object> params) {
        switch (type) {
            case OP_ELEVATOR_CALL:
                return buildCallElevator(params);
            case OP_ELEVATOR_CANCEL_CALL:
                return buildCancelCallElevator(params);
            case OP_ELEVATOR_OPEN:
                return buildOpenDoor(params);
            case OP_ELEVATOR_CANCEL_OPEN:
                return buildCancelOpenDoor(params);
            case OP_ELEVATOR_DOOR_CHECK:
                return buildCheckElevatorDoor(params);
            case OP_ELEVATOR_CHECK:
                return buildCheckElevator(params);
            case OP_ELEVATOR_ARRIVAL_DOOR_CHECK:
                return buildCheckElevatorArrivalAndDoor(params);
            case OP_ELEVATOR_QUERY_ACCESS:
                return buildQueryAccess(params);
            case OP_ELEVATOR_CLAIM_ACCESS:
                return buildClaimAccess(params);
            case OP_ELEVATOR_RELEASE_ACCESS:
                return buildReleaseAccess(params);
            case OP_FREE_MOVE:
            case OP_FIXED_MOVE:
            case OP_RECOGNIZE_LOAD:
            case OP_RECOGNIZE_UNLOAD:
            case OP_RECOGNIZE_ENTRY_ONLY:
            case OP_EXIT_SHELF:
            case OP_NON_RECOGNIZE_LOAD:
            case OP_NON_RECOGNIZE_UNLOAD:
            case OP_CHARGE_START:
            case OP_CHARGE_STOP:
            case OP_IN_ELEVATOR_MOVE:
            case OP_OUT_ELEVATOR_MOVE:
            case OP_FORWARD:
            case OP_BACKWARD:
                return buildNavigateMove(params);
            case OP_CHANGE_MAP:
                return buildChangeMap(params);
            case OP_CANCEL:
                return buildCancelRobot(params);
            default:
                throw new IllegalArgumentException("Unsupported hex command type: " + type);
        }
    }

    // Specific command builders
    public static String buildCallElevator(Map<String, Object> params) {
        String floor = (String) params.get("floor");
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x01, parseHexByte(floor), 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildCancelCallElevator(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x07, 0x00, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildOpenDoor(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x02, 0x01, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildCancelOpenDoor(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x02, 0x00, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildCheckElevatorDoor(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x03, 0x00, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildCheckElevator(Map<String, Object> params) {
        String floor = (String) params.get("floor");
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x04, parseHexByte(floor), 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildCheckElevatorArrivalAndDoor(Map<String, Object> params) {
        return buildCheckElevator(params);
    }

    public static String buildQueryAccess(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x05, 0x00, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildClaimAccess(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x06, 0x01, parseHexByte(robotId), 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildReleaseAccess(Map<String, Object> params) {
        String channel = (String) params.get("channel");
        String address = (String) params.get("address");
        String robotId = (String) params.get("robotId");
        String robotAddress = (String) params.get("robotAddress");
        String robotChannel = (String) params.get("robotChannel");
        int[] dynamicBytes = {0x05, 0x50, parseHexByte(robotAddress), parseHexByte(robotChannel), 0x06, 0x00, parseHexByte(robotId), 0x00};
        return buildHexCommand(new int[]{0x00, parseHexByte(address), parseHexByte(channel)}, dynamicBytes);
    }

    public static String buildNavigateMove(Map<String, Object> params) {
        int[] dynamicBytes = {0x05, 0x50, 0x00, 0x00, 0x05, 0xFF, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, 0x01, 0x09}, dynamicBytes);
    }

    public static String buildChangeMap(Map<String, Object> params) {
        int[] dynamicBytes = {0x05, 0x50, 0x00, 0x00, 0x05, 0xFF, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, 0x01, 0x09}, dynamicBytes);
    }

    public static String buildCancelRobot(Map<String, Object> params) {
        int[] dynamicBytes = {0x05, 0x50, 0x00, 0x00, 0x05, 0xFF, 0x00, 0x00};
        return buildHexCommand(new int[]{0x00, 0x01, 0x09}, dynamicBytes);
    }

    /**
     * Converts a hex string byte (e.g., "0A", "FF", "1F") to an integer value
     * @param hexString The hex string (can be with or without "0x" prefix)
     * @return The integer value of the hex byte
     */
    private static int parseHexByte(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return 0;
        }
        // Remove "0x" prefix if present
        String cleanHex = hexString.trim();
        if (cleanHex.startsWith("0x") || cleanHex.startsWith("0X")) {
            cleanHex = cleanHex.substring(2);
        }
        return Integer.parseInt(cleanHex, 16);
    }

    /**
     * Builds a hex command with automatic checksum calculation
     * @param headerBytes First 3 fixed bytes (can be int or String hex values)
     * @param dynamicBytes Dynamic part starting from 4th byte (can be int or String hex values)
     * @return Space-separated hex string with checksum
     */
    public static String buildHexCommand(Object[] headerBytes, Object[] dynamicBytes) {
        // Convert header bytes to int array
        int[] headerInts = new int[headerBytes.length];
        for (int i = 0; i < headerBytes.length; i++) {
            headerInts[i] = toIntValue(headerBytes[i]);
        }

        // Convert dynamic bytes to int array
        int[] dynamicInts = new int[dynamicBytes.length];
        for (int i = 0; i < dynamicBytes.length; i++) {
            dynamicInts[i] = toIntValue(dynamicBytes[i]);
        }

        return buildHexCommand(headerInts, dynamicInts);
    }

    /**
     * Builds a hex command with automatic checksum calculation (int array version)
     * @param headerBytes First 3 fixed bytes (e.g., [0x00, 0x01, 0x09])
     * @param dynamicBytes Dynamic part starting from 4th byte
     * @return Space-separated hex string with checksum
     */
    public static String buildHexCommand(int[] headerBytes, int[] dynamicBytes) {
        // Combine header and dynamic bytes
        int[] fullMessage = new int[headerBytes.length + dynamicBytes.length];
        System.arraycopy(headerBytes, 0, fullMessage, 0, headerBytes.length);
        System.arraycopy(dynamicBytes, 0, fullMessage, headerBytes.length, dynamicBytes.length);

        // Calculate XOR checksum from 4th byte onward
        int checksum = 0;
        for (int i = 3; i < fullMessage.length; i++) {
            checksum ^= fullMessage[i];
        }

        // Format all bytes + checksum
        StringBuilder sb = new StringBuilder();
        for (int b : headerBytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        for (int b : dynamicBytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        sb.append(String.format("%02X", checksum & 0xFF));

        return sb.toString().trim();
    }

    /**
     * Helper method to convert various types to integer byte value
     * @param value Can be Integer, String (hex), or other Number types
     * @return Integer value suitable for byte operations
     */
    private static int toIntValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return parseHexByte((String) value);
        }
        throw new IllegalArgumentException("Unsupported type for hex conversion: " + value.getClass());
    }
}