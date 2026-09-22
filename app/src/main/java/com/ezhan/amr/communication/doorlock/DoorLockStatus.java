package com.ezhan.amr.communication.doorlock;

/**
 * 门锁状态枚举
 * 定义门锁的各种状态
 */
public enum DoorLockStatus {
    /**
     * 门锁开启状态
     */
    OPEN(0x00, "开启"),

    /**
     * 门锁关闭状态
     */
    CLOSED(0x11, "关闭"),

    /**
     * 状态未知（通信失败或解析错误）
     */
    UNKNOWN(-1, "未知");

    private final int code;
    private final String description;

    DoorLockStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据状态码获取对应的状态枚举
     *
     * @param code 状态码（0x00=开启, 0x11=关闭）
     * @return 对应的DoorLockStatus枚举值
     */
    public static DoorLockStatus fromCode(int code) {
        for (DoorLockStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return UNKNOWN;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
