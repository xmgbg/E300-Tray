package com.ezhan.amr.ui;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.R;
import com.ezhan.amr.ui.fragments.BasicSettingsFragment;
import com.ezhan.amr.ui.fragments.DeviceSettingsFragment;
import com.ezhan.amr.ui.fragments.ElevatorFragment;
import com.ezhan.amr.ui.fragments.AvoidanceSettingsFragment;
import com.ezhan.amr.ui.fragments.TaskHistorySettingsFragment;
import com.ezhan.amr.ui.fragments.LocationSettingsFragment;
import com.ezhan.amr.ui.fragments.MapSettingsFragment;
import com.ezhan.amr.ui.fragments.MapAreaFragment;
import com.ezhan.amr.ui.fragments.ShelfSettingsFragment;
import com.ezhan.amr.ui.fragments.VoiceSettingsFragment;
import com.ezhan.amr.ui.fragments.CallSettingsFragment;
import com.ezhan.amr.ui.fragments.DoorLockSettingsFragment;
import com.ezhan.amr.ui.fragments.UserManagementFragment;
import com.ezhan.amr.ui.fragments.RfidManagementFragment;
import com.ezhan.amr.utils.FeatureVisibilitySettings;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity {
    // 颜色常量
    private static final int COLOR_WHITE = android.R.color.white;
    private static final int COLOR_TEXT_DEFAULT = R.color.text_color;
    private static final int COLOR_ICON_DEFAULT = R.color.icon_color;

    // UI组件
    private FrameLayout contentFrame;
    private AppCompatButton currentSelectedButton;
    private List<AppCompatButton> settingButtons = new ArrayList<>();

    // 声明按钮变量
    private AppCompatButton btnLocationSettings;
    private AppCompatButton btnBasicSettings;
    private AppCompatButton btnVoiceSettings;
    private AppCompatButton btnShelfSettings;
    private AppCompatButton btnMapSettings;
    private AppCompatButton btnDeviceSettings;
    private AppCompatButton btnHistoryNewsSettings;
    private AppCompatButton btnCallSettings;
    private AppCompatButton btnMapAreaSettings;
    private AppCompatButton btnAvoidanceSettings;

    private AppCompatButton btnElevatorSettings;
    private AppCompatButton btnDoorLockSettings;
    private AppCompatButton btnUserManagement;
    private AppCompatButton btnRfidManagement;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_setting);

        setupWindow();
        initViews(savedInstanceState); // 传递 savedInstanceState 参数
        setupButtonListeners();
    }

    private void setupWindow() {
        // 设置全屏
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void initViews(Bundle savedInstanceState) { // 添加 savedInstanceState 参数
        contentFrame = findViewById(R.id.contentFrame);

        // 初始化所有按钮
        btnLocationSettings = findViewById(R.id.btnLocationSettings);
        btnBasicSettings = findViewById(R.id.btnBasicSettings);
        btnVoiceSettings = findViewById(R.id.btnVoiceSettings);
        btnShelfSettings = findViewById(R.id.btnShelfSettings);
        btnMapSettings = findViewById(R.id.btnMapSettings);
        btnDeviceSettings = findViewById(R.id.btnDeviceSettings);
        btnHistoryNewsSettings = findViewById(R.id.btnHistoryNewsSettings);
        btnCallSettings = findViewById(R.id.btnCallSettings);
        btnMapAreaSettings = findViewById(R.id.btnMapAreaSettings);
        btnAvoidanceSettings = findViewById(R.id.btnAvoidanceSettings);

        btnElevatorSettings = findViewById(R.id.btnElevatorSettings);
        btnDoorLockSettings = findViewById(R.id.btnDoorLockSettings);
        btnUserManagement = findViewById(R.id.btnUserManagement);
        btnRfidManagement = findViewById(R.id.btnRfidManagement);

        // 根据基础设置中的开关状态控制各模块按钮的显示
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        
        // 料架设置
        boolean isShelfVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, true);
        btnShelfSettings.setVisibility(isShelfVisible ? View.VISIBLE : View.GONE);
        
        // 梯控设置
        boolean isElevatorVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, true);
        btnElevatorSettings.setVisibility(isElevatorVisible ? View.VISIBLE : View.GONE);
        
        // 呼叫盒设置
        boolean isCallBoxVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, true);
        btnCallSettings.setVisibility(isCallBoxVisible ? View.VISIBLE : View.GONE);

        boolean isMapAreaVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, true);
        btnMapAreaSettings.setVisibility(isMapAreaVisible ? View.VISIBLE : View.GONE);

        boolean isAvoidanceVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, true);
        btnAvoidanceSettings.setVisibility(isAvoidanceVisible ? View.VISIBLE : View.GONE);
        
        // 隐藏其他未使用的按钮
        btnDoorLockSettings.setVisibility(View.GONE);
        btnUserManagement.setVisibility(View.GONE);
        btnRfidManagement.setVisibility(View.GONE);

        // 初始化所有设置按钮并添加到集合
        settingButtons.add(btnLocationSettings);
        settingButtons.add(btnBasicSettings);
        settingButtons.add(btnVoiceSettings);
        if (btnShelfSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnShelfSettings);
        }
        settingButtons.add(btnMapSettings);
        settingButtons.add(btnDeviceSettings);
        settingButtons.add(btnHistoryNewsSettings);
        if (btnCallSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnCallSettings);
        }
        if (btnMapAreaSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnMapAreaSettings);
        }
        if (btnAvoidanceSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnAvoidanceSettings);
        }
        if (btnElevatorSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnElevatorSettings);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 检查是否因语言切换而重新创建，以及是否有目标Fragment
        boolean isLanguageChanged = getIntent().getBooleanExtra("language_changed", false);
        String targetFragment = getIntent().getStringExtra("target_fragment");

        if (isLanguageChanged && "voice_settings".equals(targetFragment)) {
            // 语言切换后，自动切换到语音设置
            selectButtonAndLoadFragment(btnVoiceSettings, new VoiceSettingsFragment());
        } else if (isLanguageChanged) {
            // 语言切换后，自动选中并切换到基础设置
            selectButtonAndLoadFragment(btnBasicSettings, new BasicSettingsFragment());
        } else if (savedInstanceState == null) {
            // 默认选中位置设置
            selectButtonAndLoadFragment(btnLocationSettings, new LocationSettingsFragment());
        }
    }

    private void setupButtonListeners() {
        // 为每个按钮设置点击监听器
        btnLocationSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnLocationSettings, new LocationSettingsFragment()));

        btnBasicSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnBasicSettings, new BasicSettingsFragment()));

        btnVoiceSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnVoiceSettings, new VoiceSettingsFragment()));
        btnShelfSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnShelfSettings, new ShelfSettingsFragment()));
        btnMapSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnMapSettings, new MapSettingsFragment()));

        btnHistoryNewsSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnHistoryNewsSettings, new TaskHistorySettingsFragment()));

        btnDeviceSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnDeviceSettings, new DeviceSettingsFragment()));

        btnCallSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnCallSettings, new CallSettingsFragment()));

        btnMapAreaSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnMapAreaSettings, new MapAreaFragment()));

        btnAvoidanceSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnAvoidanceSettings, new AvoidanceSettingsFragment()));

        btnElevatorSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnElevatorSettings, new ElevatorFragment()));

        // 隐藏的按钮不设置监听
       /* btnRobotRunSettings.setOnClickListener(v ->
                selectButtonAndLoadFragment(btnRobotRunSettings, new RobotRunSettingsFragment()));*/

//        btnElevatorSettings.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnElevatorSettings, new ElevatorFragment()));
//
//        btnDoorLockSettings.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnDoorLockSettings, new DoorLockSettingsFragment()));
//
//        btnLocationCode.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnLocationCode, new LocationCodeFragment()));
//
//        btnDepartmentManagement.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnDepartmentManagement, new DepartmentManagementFragment()));
//
//        btnUserManagement.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnUserManagement, new UserManagementFragment()));
//
//        btnRfidManagement.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnRfidManagement, new RfidManagementFragment()));
//
//        btnMapAreaSettings.setOnClickListener(v ->
//                selectButtonAndLoadFragment(btnMapAreaSettings, new MapAreaFragment()));

    }

    private void selectButtonAndLoadFragment(AppCompatButton button, Fragment fragment) {
        updateButtonSelection(button);
        replaceFragment(fragment);
    }

    private void updateButtonSelection(AppCompatButton selectedButton) {
        // 重置所有按钮状态
        resetAllButtons();

        // 设置新选中按钮
        selectedButton.setSelected(true);
        currentSelectedButton = selectedButton;
        updateButtonAppearance(selectedButton, true);
    }

    private void resetAllButtons() {
        if (currentSelectedButton != null) {
            currentSelectedButton.setSelected(false);
            updateButtonAppearance(currentSelectedButton, false);
        }

        for (AppCompatButton button : settingButtons) {
            button.setSelected(false);
            updateButtonAppearance(button, false);
        }
    }

    private void updateButtonAppearance(AppCompatButton button, boolean isSelected) {
        if (isSelected) {
            button.setBackground(ContextCompat.getDrawable(this, R.drawable.setting_button_selected));
            button.setTextColor(ContextCompat.getColor(this, COLOR_WHITE));
            tintButtonIcon(button, COLOR_WHITE);
        } else {
            button.setBackground(ContextCompat.getDrawable(this, R.drawable.setting_button_bg_selector));
            button.setTextColor(ContextCompat.getColor(this, COLOR_TEXT_DEFAULT));
            tintButtonIcon(button, COLOR_ICON_DEFAULT);
        }
    }

    private void tintButtonIcon(AppCompatButton button, int colorRes) {
        if (button.getCompoundDrawables()[0] != null) {
            button.getCompoundDrawables()[0].setTint(ContextCompat.getColor(this, colorRes));
        }
    }

    private void replaceFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.contentFrame, fragment)
                .commit();
    }

    // 刷新按钮可见性
    public void refreshButtonVisibility() {
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        
        // 料架设置
        boolean isShelfVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, true);
        btnShelfSettings.setVisibility(isShelfVisible ? View.VISIBLE : View.GONE);
        
        // 梯控设置
        boolean isElevatorVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, true);
        btnElevatorSettings.setVisibility(isElevatorVisible ? View.VISIBLE : View.GONE);
        
        // 呼叫盒设置
        boolean isCallBoxVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, true);
        btnCallSettings.setVisibility(isCallBoxVisible ? View.VISIBLE : View.GONE);

        boolean isMapAreaVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, true);
        btnMapAreaSettings.setVisibility(isMapAreaVisible ? View.VISIBLE : View.GONE);

        boolean isAvoidanceVisible = prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, true);
        btnAvoidanceSettings.setVisibility(isAvoidanceVisible ? View.VISIBLE : View.GONE);

        // 重新构建按钮列表
        settingButtons.clear();
        settingButtons.add(btnLocationSettings);
        settingButtons.add(btnBasicSettings);
        settingButtons.add(btnVoiceSettings);
        if (btnShelfSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnShelfSettings);
        }
        settingButtons.add(btnMapSettings);
        settingButtons.add(btnDeviceSettings);
        settingButtons.add(btnHistoryNewsSettings);
        if (btnCallSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnCallSettings);
        }
        if (btnMapAreaSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnMapAreaSettings);
        }
        if (btnAvoidanceSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnAvoidanceSettings);
        }
        if (btnElevatorSettings.getVisibility() == View.VISIBLE) {
            settingButtons.add(btnElevatorSettings);
        }

        // 确保当前选中的按钮仍然可见，如果不可见则默认选中基础设置
        if (currentSelectedButton != null && currentSelectedButton.getVisibility() != View.VISIBLE) {
            selectButtonAndLoadFragment(btnBasicSettings, new BasicSettingsFragment());
        }
    }
}
