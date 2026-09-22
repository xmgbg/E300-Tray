package com.ezhan.amr.communication.modbus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.ezhan.amr.R;
import com.ezhan.amr.utils.ConfigManager;
import com.ezhan.amr.web.SharedRobotDataProvider;

/**
 * Modbus TCP Slave 服务（Android Service）。
 * 复刻 HTTP / WebSocket 接口能力到 Modbus 寄存器。
 *
 * 线程模型（关键）：
 *   所有 Modbus 周期任务（状态刷新、地图刷新、命令轮询）以及任务派发
 *   都运行在专用的 HandlerThread "ModbusWorker" 上，绝不在主线程执行。
 *   原因：SharedRobotDataProvider 的 getRobotStatus/getAllMapPoints 等方法
 *   内部使用 mainHandler.post() + latch.await() 模式，若在主线程调用会
 *   死锁（主线程被 await 阻塞，回调永远排不到主线程执行），导致 ANR。
 *
 * 启动流程：
 *   1) onCreate：启动前台通知 + 创建 HandlerThread
 *   2) onStartCommand：在 workerHandler 上启动 Modbus stack
 *   3) 周期调度：1s 刷新状态、10s 刷新地图数据、200ms 轮询命令
 *
 * 关闭流程：
 *   1) 停止所有 workerHandler 回调
 *   2) 停止 ModbusSlaveServer
 *   3) 退出 HandlerThread
 */
public class ModbusService extends Service {

    private static final String TAG = "ModbusService";

    public static final int DEFAULT_PORT = 5020;
    public static final int DEFAULT_UNIT_ID = 1;

    private static final long STATUS_REFRESH_INTERVAL_MS = 1000L;
    private static final long MAP_REFRESH_INTERVAL_MS = 10000L;
    private static final long COMMAND_POLL_INTERVAL_MS = 200L;

    private static final int NOTIFICATION_ID = 1004;
    private static final String CHANNEL_ID = "modbus_service_channel";

    private ModbusSlaveServer slaveServer;
    private SharedRobotDataProvider provider;
    private ModbusDataBinder dataBinder;
    private ModbusCommandHandler commandHandler;

    // 专用后台线程：所有 Modbus 阻塞操作都在此线程执行，避免阻塞主线程
    private HandlerThread workerThread;
    private Handler workerHandler;

    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (dataBinder != null) {
                dataBinder.refreshStatus();
            }
            workerHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
        }
    };

    private final Runnable mapRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (dataBinder != null) {
                dataBinder.refreshMapData();
            }
            workerHandler.postDelayed(this, MAP_REFRESH_INTERVAL_MS);
        }
    };

    private final Runnable commandPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (commandHandler != null) {
                commandHandler.poll();
            }
            workerHandler.postDelayed(this, COMMAND_POLL_INTERVAL_MS);
        }
    };

    private boolean started = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ModbusService onCreate");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService();
        }

        // 创建专用后台线程（必须在 onStartCommand 之前就绪）
        workerThread = new HandlerThread("ModbusWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "ModbusService onStartCommand");
        if (!started) {
            // 在后台线程启动 Modbus stack，避免阻塞主线程
            workerHandler.post(this::startModbusStack);
        }
        return START_STICKY;
    }

    private synchronized void startModbusStack() {
        if (started) return;
        try {
            int port = ConfigManager.getInstance().getServicePort("modbusService", DEFAULT_PORT);
            int unitId = DEFAULT_UNIT_ID;

            provider = SharedRobotDataProvider.getInstance(this);
            slaveServer = new ModbusSlaveServer(port, unitId);
            slaveServer.start();

            dataBinder = new ModbusDataBinder(slaveServer, provider);
            // 把 workerHandler 传给 CommandHandler，dispatchTask 在后台线程执行
            commandHandler = new ModbusCommandHandler(slaveServer, provider, dataBinder, workerHandler);

            // 首次立即刷新一次状态（在后台线程，安全）
            dataBinder.refreshStatus();
            dataBinder.refreshMapData();

            workerHandler.post(statusRefreshRunnable);
            workerHandler.postDelayed(mapRefreshRunnable, MAP_REFRESH_INTERVAL_MS);
            workerHandler.post(commandPollRunnable);

            started = true;
            Log.i(TAG, "Modbus stack started on port " + port + " unitId=" + unitId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Modbus stack", e);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ModbusService onDestroy");
        if (workerHandler != null) {
            workerHandler.removeCallbacks(statusRefreshRunnable);
            workerHandler.removeCallbacks(mapRefreshRunnable);
            workerHandler.removeCallbacks(commandPollRunnable);
        }

        if (slaveServer != null) {
            try {
                slaveServer.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping slave server", e);
            }
            slaveServer = null;
        }
        dataBinder = null;
        commandHandler = null;
        provider = null;
        started = false;

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startForegroundService() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Modbus Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Modbus Service Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }
}
