package com.ezhan.amr.navigation;

public enum MainCommand {
    UNKNOWN(0),
    CHARGE(1),
    PARK(2),
    CANCEL(3),
    PAUSE(4),
    RESUME(5);

    private final int code;

    MainCommand(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static MainCommand fromString(String command) {
        if (command == null) return UNKNOWN;

        switch (command.toLowerCase()) {
            case "charge":
                return CHARGE;
            case "park":
                return PARK;
            case "cancel":
                return CANCEL;
            case "pause":
                return PAUSE;
            case "resume":
                return RESUME;
            default:
                return UNKNOWN;
        }
    }

    public static MainCommand fromCode(int code) {
        for (MainCommand cmd : values()) {
            if (cmd.code == code) {
                return cmd;
            }
        }
        return UNKNOWN;
    }
}