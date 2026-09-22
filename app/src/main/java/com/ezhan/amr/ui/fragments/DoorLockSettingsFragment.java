package com.ezhan.amr.ui.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.doorlock.DoorLockCommunicator;
import com.ezhan.amr.communication.doorlock.DoorLockStatus;

import java.io.IOException;

/**
 * 门锁设置Fragment
 * 显示门锁2和门锁3的状态，并提供按钮控制功能
 */
public class DoorLockSettingsFragment extends Fragment {

    private static final String TAG = "DoorLockSettingsFragment";

    // UI组件
    private TextView tvLock2Status;
    private TextView tvLock3Status;
    private Button btnLock2Open;
    private Button btnLock2Close;
    private Button btnLock3Open;
    private Button btnLock3Close;

    // 门锁通信控制器
    private DoorLockCommunicator doorLockCommunicator;

    // 主线程Handler，用于定时刷新状态
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final long REFRESH_INTERVAL_MS = 1000; // 每1秒刷新一次状态
    private static final int MAX_FAILURE_COUNT = 5; // 连续失败次数阈值

    // 状态更新时间戳
    private long lastLock2UpdateTime = 0;
    private long lastLock3UpdateTime = 0;
    
    // 连续失败计数器
    private int lock2FailureCount = 0;
    private int lock3FailureCount = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_door_lock_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化视图
        initViews(view);

        // 初始化门锁通信控制器
        initDoorLockCommunicator();

        // 设置按钮监听器
        setupButtonListeners();

        // 立即刷新一次状态
        refreshLockStatus();

        // 启动定时刷新
        startAutoRefresh();
    }

    /**
     * 初始化视图组件
     */
    private void initViews(View view) {
        tvLock2Status = view.findViewById(R.id.tvLock2Status);
        tvLock3Status = view.findViewById(R.id.tvLock3Status);
        btnLock2Open = view.findViewById(R.id.btnLock2Open);
        btnLock2Close = view.findViewById(R.id.btnLock2Close);
        btnLock3Open = view.findViewById(R.id.btnLock3Open);
        btnLock3Close = view.findViewById(R.id.btnLock3Close);

        // 设置标题
        TextView title = view.findViewById(R.id.tvTitle);
        if (title != null) {
            title.setText(R.string.Door_lock_settings);
        }
    }

    /**
     * 初始化门锁通信控制器
     */
    private void initDoorLockCommunicator() {
        try {
            doorLockCommunicator = MyApplication.getInstance().getDoorLockCommunicator();
            Log.d(TAG, "门锁通信控制器初始化成功");
        } catch (IOException e) {
            Log.e(TAG, "门锁通信控制器初始化失败: " + e.getMessage(), e);
            showToast(getString(R.string.door_lock_init_failed));
        }
    }

    /**
     * 设置按钮监听器
     */
    private void setupButtonListeners() {
        // 门锁2开启按钮
        btnLock2Open.setOnClickListener(v -> unlockLock2());

        // 门锁2关闭按钮
        btnLock2Close.setOnClickListener(v -> {
            showToast(getString(R.string.door_lock_close_manual_hint));
            refreshLockStatus();
        });

        // 门锁3开启按钮
        btnLock3Open.setOnClickListener(v -> unlockLock3());

        // 门锁3关闭按钮
        btnLock3Close.setOnClickListener(v -> {
            showToast(getString(R.string.door_lock_close_manual_hint));
            refreshLockStatus();
        });
    }

    /**
     * 打开门锁2
     */
    private void unlockLock2() {
        if (doorLockCommunicator == null) {
            showToast(getString(R.string.door_lock_not_initialized));
            return;
        }

        new Thread(() -> {
            boolean success = doorLockCommunicator.unlockLock2();
            requireActivity().runOnUiThread(() -> {
                if (success) {
                    showToast(getString(R.string.door_lock2_unlock_success));
                    // 延迟刷新状态
                    refreshHandler.postDelayed(this::refreshLockStatus, 500);
                } else {
                    showToast(getString(R.string.door_lock2_unlock_failed));
                }
            });
        }).start();
    }

    /**
     * 打开门锁3
     */
    private void unlockLock3() {
        if (doorLockCommunicator == null) {
            showToast(getString(R.string.door_lock_not_initialized));
            return;
        }

        new Thread(() -> {
            boolean success = doorLockCommunicator.unlockLock3();
            requireActivity().runOnUiThread(() -> {
                if (success) {
                    showToast(getString(R.string.door_lock3_unlock_success));
                    refreshHandler.postDelayed(this::refreshLockStatus, 500);
                } else {
                    showToast(getString(R.string.door_lock3_unlock_failed));
                }
            });
        }).start();
    }

    /**
     * 刷新门锁状态
     */
    private void refreshLockStatus() {
        if (doorLockCommunicator == null) {
            updateLock2UI(DoorLockStatus.UNKNOWN);
            updateLock3UI(DoorLockStatus.UNKNOWN);
            return;
        }

        // 分别查询两个门锁的状态
        new Thread(() -> {
            try {
                // 查询门锁2状态
                DoorLockStatus status2 = doorLockCommunicator.queryLock2Status();
                // 查询门锁3状态
                DoorLockStatus status3 = doorLockCommunicator.queryLock3Status();

                requireActivity().runOnUiThread(() -> {
                    updateLock2UI(status2);
                    updateLock3UI(status3);
                });
            } catch (Exception e) {
                Log.e(TAG, "刷新门锁状态失败: " + e.getMessage(), e);
                requireActivity().runOnUiThread(() -> {
                    updateLock2UI(DoorLockStatus.UNKNOWN);
                    updateLock3UI(DoorLockStatus.UNKNOWN);
                });
            }
        }).start();
    }

    /**
     * 更新门锁2的UI显示
     */
    private void updateLock2UI(DoorLockStatus status) {
        long currentTime = System.currentTimeMillis();
        
        if (status == DoorLockStatus.OPEN || status == DoorLockStatus.CLOSED) {
            // 状态正常，重置失败计数器
            lock2FailureCount = 0;
            
            if (status == DoorLockStatus.OPEN) {
                tvLock2Status.setText(R.string.door_lock_status_open);
                tvLock2Status.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            } else {
                tvLock2Status.setText(R.string.door_lock_status_closed);
                tvLock2Status.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            }
            lastLock2UpdateTime = currentTime;
        } else {
            // 状态未知，增加失败计数
            lock2FailureCount++;
            Log.d(TAG, "门锁2查询失败，连续失败次数: " + lock2FailureCount);
            
            // 检查是否达到连续失败阈值
            if (lock2FailureCount >= MAX_FAILURE_COUNT) {
                tvLock2Status.setText(getString(R.string.door_lock_comm_lost));
                tvLock2Status.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            } else {
                // 保持上次状态
            }
        }
    }

    /**
     * 更新门锁3的UI显示
     */
    private void updateLock3UI(DoorLockStatus status) {
        long currentTime = System.currentTimeMillis();
        
        if (status == DoorLockStatus.OPEN || status == DoorLockStatus.CLOSED) {
            // 状态正常，重置失败计数器
            lock3FailureCount = 0;
            
            if (status == DoorLockStatus.OPEN) {
                tvLock3Status.setText(R.string.door_lock_status_open);
                tvLock3Status.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            } else {
                tvLock3Status.setText(R.string.door_lock_status_closed);
                tvLock3Status.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            }
            lastLock3UpdateTime = currentTime;
        } else {
            // 状态未知，增加失败计数
            lock3FailureCount++;
            Log.d(TAG, "门锁3查询失败，连续失败次数: " + lock3FailureCount);
            
            // 检查是否达到连续失败阈值
            if (lock3FailureCount >= MAX_FAILURE_COUNT) {
                tvLock3Status.setText(getString(R.string.door_lock_comm_lost));
                tvLock3Status.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            } else {
                // 保持上次状态
            }
        }
    }

    /**
     * 启动自动刷新
     */
    private void startAutoRefresh() {
        refreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshLockStatus();
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }, REFRESH_INTERVAL_MS);
    }

    /**
     * 停止自动刷新
     */
    private void stopAutoRefresh() {
        refreshHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 显示Toast提示
     */
    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 恢复时立即刷新一次状态
        refreshLockStatus();
        // 重新启动定时刷新
        startAutoRefresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        // 暂停时停止定时刷新
        stopAutoRefresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清理Handler
        stopAutoRefresh();
    }
}
