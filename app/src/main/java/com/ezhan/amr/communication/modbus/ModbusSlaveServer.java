package com.ezhan.amr.communication.modbus;

import android.util.Log;

import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

/**
 * j2mod 3.2.1 Modbus TCP 从站封装。
 * 使用 ModbusSlaveFactory.createTCPSlave 创建从站，SimpleProcessImage 维护寄存器映像。
 * 写触发采用轮询方式（由 ModbusCommandHandler 周期检测线圈/保持寄存器变化）。
 *
 * 寄存器数组作为字段保留直接引用——SimpleRegister/SimpleInputRegister 的 setValue
 * 会即时反映到进程映像中，便于 DataBinder/CommandHandler 高频读写。
 */
public class ModbusSlaveServer {

    private static final String TAG = "ModbusSlaveServer";
    private static final int POOL_SIZE = 5;

    private final int port;
    private final int unitId;
    private ModbusSlave slave;

    // 寄存器数组引用，供 DataBinder / CommandHandler 直接读写
    private final SimpleRegister[] holdingRegisters;
    private final SimpleInputRegister[] inputRegisters;
    private final SimpleDigitalOut[] coils;
    private final SimpleDigitalIn[] discreteInputs;

    private volatile boolean running = false;

    public ModbusSlaveServer(int port, int unitId) {
        this.port = port;
        this.unitId = unitId;
        holdingRegisters = new SimpleRegister[ModbusRegisterMap.HOLDING_REGISTER_SIZE];
        inputRegisters = new SimpleInputRegister[ModbusRegisterMap.INPUT_REGISTER_SIZE];
        coils = new SimpleDigitalOut[ModbusRegisterMap.COIL_SIZE];
        discreteInputs = new SimpleDigitalIn[ModbusRegisterMap.DISCRETE_INPUT_SIZE];

        for (int i = 0; i < holdingRegisters.length; i++) {
            holdingRegisters[i] = new SimpleRegister(0);
        }
        for (int i = 0; i < inputRegisters.length; i++) {
            inputRegisters[i] = new SimpleInputRegister(0);
        }
        for (int i = 0; i < coils.length; i++) {
            coils[i] = new SimpleDigitalOut(false);
        }
        for (int i = 0; i < discreteInputs.length; i++) {
            discreteInputs[i] = new SimpleDigitalIn(false);
        }
    }

    /** 启动从站 */
    public synchronized void start() throws Exception {
        if (running) return;
        SimpleProcessImage processImage = new SimpleProcessImage(unitId);
        for (SimpleRegister r : holdingRegisters) processImage.addRegister(r);
        for (SimpleInputRegister r : inputRegisters) processImage.addInputRegister(r);
        for (SimpleDigitalOut c : coils) processImage.addDigitalOut(c);
        for (SimpleDigitalIn d : discreteInputs) processImage.addDigitalIn(d);

        slave = ModbusSlaveFactory.createTCPSlave(port, POOL_SIZE);
        slave.addProcessImage(unitId, processImage);
        slave.open();

        running = true;
        Log.i(TAG, "Modbus TCP slave started on port " + port + ", unitId=" + unitId);
    }

    /** 停止从站 */
    public synchronized void stop() {
        if (!running) return;
        try {
            if (slave != null) {
                slave.close();
                slave = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping slave", e);
        }
        running = false;
        Log.i(TAG, "Modbus TCP slave stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    // ===== 保持寄存器读写（可被 Master 写，本地读） =====
    public int getHoldingRegister(int ref) {
        if (ref < 0 || ref >= holdingRegisters.length) return 0;
        return holdingRegisters[ref].getValue();
    }

    public void setHoldingRegister(int ref, int value) {
        if (ref < 0 || ref >= holdingRegisters.length) return;
        holdingRegisters[ref].setValue(value);
    }

    // ===== 输入寄存器写（只读对 Master，本地刷新） =====
    public void setInputRegister(int ref, int value) {
        if (ref < 0 || ref >= inputRegisters.length) return;
        inputRegisters[ref].setValue(value);
    }

    // ===== 线圈读写 =====
    public boolean getCoil(int ref) {
        if (ref < 0 || ref >= coils.length) return false;
        return coils[ref].isSet();
    }

    public void setCoil(int ref, boolean on) {
        if (ref < 0 || ref >= coils.length) return;
        coils[ref].set(on);
    }

    // ===== 离散输入写（只读对 Master，本地刷新） =====
    public void setDiscreteInput(int ref, boolean on) {
        if (ref < 0 || ref >= discreteInputs.length) return;
        discreteInputs[ref].set(on);
    }
}
