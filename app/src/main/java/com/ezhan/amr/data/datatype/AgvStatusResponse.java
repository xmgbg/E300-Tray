package com.ezhan.amr.data.datatype;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class AgvStatusResponse {

    @SerializedName("cmd")
    public String cmd;

    @SerializedName("data")
    public Data data = new Data();

    @SerializedName("talk")
    public String talk;

    @SerializedName("time")
    public String time;

    // 默认构造函数
    public AgvStatusResponse() {
    }

    // 新增构造函数，用于WebSocketStatusFetcher的连接状态通知
    public AgvStatusResponse(boolean isConnected) {
        this.data = new Data();
        this.data.inBuildMap = isConnected;
        this.cmd = "status_update";
    }

    public static class Data {
        @SerializedName("agvSpeed")
        public AgvSpeed agvSpeed = new AgvSpeed();

        @SerializedName("chargestatu")
        public ChargeStatus chargeStatus = new ChargeStatus();

        @SerializedName("agvStop")
        public int agvStop;

        @SerializedName("movement")
        public int movement;

        @SerializedName("currentBv")
        public int currentBv;

        @SerializedName("currentFv")
        public int currentFv;

        @SerializedName("chargestep")
        public int chargeStep;

        @SerializedName("collisionwarning")
        public boolean collisionWarning;

        @SerializedName("currentglobalid")
        public int currentGlobalId;

        @SerializedName("electriccurrentin")
        public float electricCurrentIn;

        @SerializedName("electriccurrentout")
        public float electricCurrentOut;

        @SerializedName("emgstop")
        public boolean emgStop;

        @SerializedName("errorcode")
        public List<Integer> errorCode;

        @SerializedName("errcode")
        public Integer errcode;  // Note: This is "errcode" in JSON (lowercase), different from errCode below

        @SerializedName("goalfinish")
        public int goalFinish;

        @SerializedName("inmanualcharge")
        public int inManualCharge;

        @SerializedName("innavmap")
        public boolean inNavMap;

        @SerializedName("inbuildmap")
        public boolean inBuildMap;

        @SerializedName("issoftpause")
        public boolean isSoftPause;

        @SerializedName("motorcode")
        public List<Integer> motorCode;

        @SerializedName("odomhz")
        public float odomHz;

        @SerializedName("pos")
        public DataPosition pos = new DataPosition();

        @SerializedName("pose_probability")
        public float poseProbability;

        @SerializedName("power")
        public float power;

        @SerializedName("powerquantity")
        public int powerQuantity;

        @SerializedName("robotid")
        public String robotId;

        @SerializedName("scanhz")
        public float scanHz;

        @SerializedName("version")
        public String version;

        @SerializedName("wheellock")
        public boolean wheelLock;

        // Note: The JSON has "errCode" (camelCase) but your AgvStatusResponse already has this as errCode
        // Keeping for backward compatibility
        @SerializedName("errCode")
        public int errCode;
    }

    public static class AgvSpeed {
        @SerializedName("t")
        public float t;

        @SerializedName("x")
        public float x;
    }

    public static class ChargeStatus {
        @SerializedName("actionresult")
        public int actionResult;

        @SerializedName("againtime")
        public int againTime;

        @SerializedName("chargeio")
        public int chargeIo;

        @SerializedName("chargestu")
        public String chargeState;

        @SerializedName("ele")
        public float ele;

        @SerializedName("failedchargetime")
        public int failedChargeTime;

        @SerializedName("frontpowerdistane")
        public float frontPowerDistance;

        @SerializedName("iotouch")
        public boolean ioTouch;

        @SerializedName("isautoinit")
        public boolean isAutoInit;

        @SerializedName("ismanualinit")
        public boolean isManualInit;

        @SerializedName("manualchargeio")
        public int manualChargeIo;

        @SerializedName("manualtriggerio")
        public int manualTriggerIo;

        @SerializedName("pos")
        public ChargePosition pos = new ChargePosition();

        @SerializedName("powerquantity")
        public int powerQuantity;

        @SerializedName("state")
        public int state;

        @SerializedName("talk")
        public String talk;

        @SerializedName("touchio")
        public int touchIo;

        @SerializedName("touchiosteptime")
        public int touchIoStepTime;

        @SerializedName("touchiotime")
        public int touchIoTime;
    }

    public static class ChargePosition {
        @SerializedName("mapname")
        public String mapName;

        @SerializedName("x")
        public double x;

        @SerializedName("y")
        public double y;

        @SerializedName("theta")
        public double theta;
    }

    public static class DataPosition {
        @SerializedName("mapname")
        public String mapName;

        @SerializedName("x")
        public double x;

        @SerializedName("y")
        public double y;

        @SerializedName("z")
        public double z;

        @SerializedName("theta")
        public double theta;
    }

    // 示例解析方法
    public static AgvStatusResponse fromJson(String json) {
        return new Gson().fromJson(json, AgvStatusResponse.class);
    }
}