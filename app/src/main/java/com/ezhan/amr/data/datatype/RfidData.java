package com.ezhan.amr.data.datatype;

/**
 * RFID卡片数据结构
 */
public class RfidData {
    private int id;
    private String rfidSerial;
    private String userId;
    private int cardType; // 1-操作卡，2-管理卡
    private int status; // 0-挂失/禁用，1-正常

    public RfidData() {
    }

    public RfidData(int id, String rfidSerial, String userId, int cardType, int status) {
        this.id = id;
        this.rfidSerial = rfidSerial;
        this.userId = userId;
        this.cardType = cardType;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getRfidSerial() {
        return rfidSerial;
    }

    public void setRfidSerial(String rfidSerial) {
        this.rfidSerial = rfidSerial;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getCardType() {
        return cardType;
    }

    public void setCardType(int cardType) {
        this.cardType = cardType;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * 获取卡片类型字符串
     */
    public String getCardTypeString() {
        switch (cardType) {
            case 1:
                return "操作卡";
            case 2:
                return "管理卡";
            default:
                return "未知";
        }
    }

    /**
     * 获取状态字符串
     */
    public String getStatusString() {
        return status == 1 ? "正常" : "挂失/禁用";
    }
}
