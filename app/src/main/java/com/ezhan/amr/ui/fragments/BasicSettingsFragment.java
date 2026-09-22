package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.utils.AndroidBug5497Workaround;
import com.ezhan.amr.utils.FeatureVisibilitySettings;
import com.ezhan.amr.utils.LocaleHelper;
import com.ezhan.amr.utils.RawMusicUtils;
import com.ezhan.amr.viewmodels.BasicViewModel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class BasicSettingsFragment extends Fragment {
    private static final String TAG = "BasicSettingsFragment";
    private static final String[] LANGUAGE_CODES = {"zh", "en", "zh-HK", "ja", "ko", "vi"};

    private Spinner spinnerLanguage, spinnerMusic;
    private Switch switchCharge, switchIdleCharge, switchFullChargeReturnHome, switchOrbitMode, switchVirtualOrbitOneWay;
    private Switch switchJackModule, switchShelfModule, switchElevatorModule, switchCallBoxModule;
    private Switch switchMapAreaModule, switchAvoidanceModule, switchPassword;
    private Switch switchIdleReturnHome;
    private SeekBar volumeSeekBar, speedSeekBar;

    private TextView tvTrackSettings;
    private TextView volumeValue, speedValue;
    private TextView deliveryStayValue, pauseWaitValue;
    private TextView idleChargeWaitValue;
    private TextView idleReturnWaitSecondsValue;
    private TextView batteryCriticalValue, batteryMinWorkValue;
    private TextView batteryIdleValue, batteryFullValue;
    private EditText etRecognizeDistance;
    private EditText etObstacleTime, etQueuedTaskCooldown, etPassword;
    private BasicViewModel basicViewModel;

    private long lastLanguageChangeTime = 0;
    private boolean isFirstSpinnerSelection = true;
    private AudioManager audioManager;
    private EditText etRobotId, etLoraAddress;
    private Spinner spinnerLoraChannel;
    private EditText etCloudIp, etCloudPort;
    private Toast activeToast;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置窗口背景为白色，避免Splash背景闪烁
        if (getActivity() != null && getActivity().getWindow() != null) {
            getActivity().getWindow().setBackgroundDrawableResource(android.R.color.white);
        }
        basicViewModel = MyApplication.getInstance().getBasicViewModel();
        audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_basic_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        setupSeekBars();
        setupParameterEditors();
        setupBatteryThresholdEditors();
        setupLanguageSpinner();
        setupOrbitModeSwitch();
        setupMusicSpinner();
        setupCommunicationSettings(); // Moved BEFORE loadSavedSettings

        view.findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        view.findViewById(R.id.btnReset).setOnClickListener(v -> resetDefaultSettings());

        // 添加辅助类处理软键盘遮挡
        if (getActivity() != null) {
            AndroidBug5497Workaround.assistActivity(getActivity());
        }

        loadSavedSettings(); // Now called AFTER setupCommunicationSettings
        observeViewModel();
        updateOrbitModeDisplay();
    }

    @SuppressLint("DefaultLocale")
    private void initViews(View view) {
        volumeSeekBar = view.findViewById(R.id.volumeSeekBar);
        speedSeekBar = view.findViewById(R.id.speedSeekBar);
        volumeValue = view.findViewById(R.id.volumeValue);

        Integer volumeLevel = basicViewModel.getVolumeLevel().getValue();
        volumeSeekBar.setProgress(volumeLevel == null ? 40 : volumeLevel);
        volumeValue.setText(String.valueOf(volumeSeekBar.getProgress()));

        speedValue = view.findViewById(R.id.speedValue);
        Double speed = basicViewModel.getSpeed().getValue();
        int speedProgress = speed == null ? 40 : (int)(speed * 100);
        speedSeekBar.setProgress(speedProgress);
        speedValue.setText(String.format(Locale.US, "%.1f", speedProgress / 100.0f));

        deliveryStayValue = view.findViewById(R.id.deliveryStayValue);
        Integer deliveryWait = basicViewModel.getDeliveryWaitDuration().getValue();
        deliveryStayValue.setText(String.valueOf(deliveryWait == null ? 30 : deliveryWait));

        etQueuedTaskCooldown = view.findViewById(R.id.queuedTaskCooldownValue);
        Integer queuedTaskCooldown = basicViewModel.getQueuedTaskCooldown().getValue();
        etQueuedTaskCooldown.setText(String.valueOf(queuedTaskCooldown == null ? 6 : queuedTaskCooldown));

        // 初始化识别距离输入框 - Using ViewModel now
        etRecognizeDistance = view.findViewById(R.id.recognizeDistanceValue); // Keep the same ID
        Double recognizeDistance = basicViewModel.getRecognizeDistance().getValue();
        etRecognizeDistance.setText(String.format(Locale.US, "%.2f", Objects.requireNonNullElse(recognizeDistance, 1.33f)));

        switchOrbitMode = view.findViewById(R.id.switchOrbitMode);
        switchVirtualOrbitOneWay = view.findViewById(R.id.switchVirtualOrbitOneWay);

        // 初始化绕障时间输入框
        etObstacleTime = view.findViewById(R.id.etObstacleTime);
        Integer obstacleTime = basicViewModel.getVirtualOrbitObstacleTime().getValue();
        etObstacleTime.setText(String.valueOf(obstacleTime == null ? 0 : obstacleTime));

        switchCharge = view.findViewById(R.id.switchAutoCharge);
        switchCharge.setChecked(basicViewModel.getIsAutoCharge().getValue() != null && basicViewModel.getIsAutoCharge().getValue());
        switchIdleCharge = view.findViewById(R.id.switchIdleCharge);
        switchIdleCharge.setChecked(basicViewModel.getIsIdleCharge().getValue() == null || Boolean.TRUE.equals(basicViewModel.getIsIdleCharge().getValue()));
        switchFullChargeReturnHome = view.findViewById(R.id.switchFullChargeReturnHome);
        switchFullChargeReturnHome.setChecked(basicViewModel.getIsFullChargeReturnHome().getValue() == null
                || Boolean.TRUE.equals(basicViewModel.getIsFullChargeReturnHome().getValue()));
        idleChargeWaitValue = view.findViewById(R.id.idleChargeWaitValue);
        idleChargeWaitValue.setText(String.valueOf(basicViewModel.getIdleChargeWaitMinutes().getValue() == null ? 10 : basicViewModel.getIdleChargeWaitMinutes().getValue()));

        // 初始化闲时回待命开关和等待时间
        switchIdleReturnHome = view.findViewById(R.id.switchIdleReturnHome);
        switchIdleReturnHome.setChecked(Boolean.TRUE.equals(basicViewModel.getIsIdleReturnHome().getValue()));
        idleReturnWaitSecondsValue = view.findViewById(R.id.idleReturnWaitSecondsValue);
        idleReturnWaitSecondsValue.setText(String.valueOf(basicViewModel.getIdleReturnWaitSeconds().getValue() == null ? 600 : basicViewModel.getIdleReturnWaitSeconds().getValue()));

        batteryCriticalValue = view.findViewById(R.id.batteryCriticalValue);
        batteryMinWorkValue = view.findViewById(R.id.batteryMinWorkValue);
        batteryIdleValue = view.findViewById(R.id.batteryIdleValue);
        batteryFullValue = view.findViewById(R.id.batteryFullValue);
        updateBatteryThresholdViews(
                basicViewModel.getBatteryCriticalLevel().getValue(),
                basicViewModel.getBatterySafeLevel().getValue(),
                basicViewModel.getBatteryIdleLevel().getValue(),
                basicViewModel.getBatteryFullLevel().getValue());

        // 初始化模块开关
        switchJackModule = view.findViewById(R.id.switchJackModule);
        switchShelfModule = view.findViewById(R.id.switchShelfModule);
        switchElevatorModule = view.findViewById(R.id.switchElevatorModule);
        switchCallBoxModule = view.findViewById(R.id.switchCallBoxModule);
        switchMapAreaModule = view.findViewById(R.id.switchMapAreaModule);
        switchAvoidanceModule = view.findViewById(R.id.switchAvoidanceModule);

        spinnerLanguage = view.findViewById(R.id.spinnerLanguage);
        spinnerMusic = view.findViewById(R.id.spinnerMusic);

        tvTrackSettings = view.findViewById(R.id.tvTrackSettings);

        // 初始化密码设置
        switchPassword = view.findViewById(R.id.switchPassword);
        etPassword = view.findViewById(R.id.etPassword);

        // 设置密码开关监听器
        switchPassword.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etPassword.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) {
                etPassword.setText("");
            }
        });
    }

    private void observeViewModel() {
        basicViewModel.getIsVirtualOrbitMode().observe(getViewLifecycleOwner(), isVirtual -> {
            if (isVirtual != null) {
                switchOrbitMode.setChecked(isVirtual);
                updateOrbitModeDisplay();
            }
        });
        basicViewModel.getIsVirtualOrbitOneWayMode().observe(getViewLifecycleOwner(), isOneWay -> {
            if (isOneWay != null) {
                switchVirtualOrbitOneWay.setChecked(isOneWay);
            }
        });

        // Observe recognize distance changes
        basicViewModel.getRecognizeDistance().observe(getViewLifecycleOwner(), distance -> {
            if (distance != null) {
                etRecognizeDistance.setText(String.format(Locale.US, "%.2f", distance));
            }
        });
        basicViewModel.getQueuedTaskCooldown().observe(getViewLifecycleOwner(), duration -> {
            if (duration != null) {
                etQueuedTaskCooldown.setText(String.valueOf(duration));
            }
        });
        basicViewModel.getBatteryCriticalLevel().observe(getViewLifecycleOwner(), level -> {
            if (level != null && batteryCriticalValue != null) {
                batteryCriticalValue.setText(String.valueOf(level));
            }
        });
        basicViewModel.getBatterySafeLevel().observe(getViewLifecycleOwner(), level -> {
            if (level != null && batteryMinWorkValue != null) {
                batteryMinWorkValue.setText(String.valueOf(level));
            }
        });
        basicViewModel.getBatteryIdleLevel().observe(getViewLifecycleOwner(), level -> {
            if (level != null && batteryIdleValue != null) {
                batteryIdleValue.setText(String.valueOf(level));
            }
        });
        basicViewModel.getBatteryFullLevel().observe(getViewLifecycleOwner(), level -> {
            if (level != null && batteryFullValue != null) {
                batteryFullValue.setText(String.valueOf(level));
            }
        });
    }

    private void setupBatteryThresholdEditors() {
        batteryCriticalValue.setOnClickListener(v ->
                showPercentPickerDialog(batteryCriticalValue, DefaultSettings.BATTERY_CRITICAL));
        batteryMinWorkValue.setOnClickListener(v ->
                showPercentPickerDialog(batteryMinWorkValue, DefaultSettings.BATTERY_MIN_WORK));
        batteryIdleValue.setOnClickListener(v ->
                showPercentPickerDialog(batteryIdleValue, DefaultSettings.BATTERY_IDLE));
        batteryFullValue.setOnClickListener(v ->
                showPercentPickerDialog(batteryFullValue, DefaultSettings.BATTERY_FULL));
    }

    private void updateBatteryThresholdViews(Integer critical, Integer minWork, Integer idle, Integer full) {
        if (batteryCriticalValue != null) {
            batteryCriticalValue.setText(String.valueOf(critical == null ? DefaultSettings.BATTERY_CRITICAL : critical));
        }
        if (batteryMinWorkValue != null) {
            batteryMinWorkValue.setText(String.valueOf(minWork == null ? DefaultSettings.BATTERY_MIN_WORK : minWork));
        }
        if (batteryIdleValue != null) {
            batteryIdleValue.setText(String.valueOf(idle == null ? DefaultSettings.BATTERY_IDLE : idle));
        }
        if (batteryFullValue != null) {
            batteryFullValue.setText(String.valueOf(full == null ? DefaultSettings.BATTERY_FULL : full));
        }
    }

    private boolean validateBatteryThresholds(int critical, int minWork, int idle, int full) {
        if (critical < 0 || full > 100) {
            showToast(getString(R.string.battery_threshold_order_error));
            return false;
        }
        if (!(critical <= minWork && minWork <= idle && idle <= full)) {
            showToast(getString(R.string.battery_threshold_order_error));
            return false;
        }
        return true;
    }

    private int parseBatteryThreshold(TextView textView, int defaultValue) {
        try {
            return Integer.parseInt(textView.getText().toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void setupOrbitModeSwitch() {
        // Set initial state from ViewModel
        Boolean isVirtual = basicViewModel.getIsVirtualOrbitMode().getValue();
        if (isVirtual != null) {
            switchOrbitMode.setChecked(isVirtual);
        }

        switchOrbitMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String modeName = isChecked ? getString(R.string.virtual_track) : getString(R.string.free_path_track);
            showOrbitModeConfirmationDialog(modeName, isChecked);
        });
    }

    @SuppressLint("StringFormatInvalid")
    private void showOrbitModeConfirmationDialog(String modeName, boolean isVirtual) {
        Log.d(TAG, "显示轨道切换确认对话框");
        Log.d(TAG, "目标模式: " + modeName);

        // 创建自定义对话框
        final Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_confirm_fetch);

        // 设置对话框宽度为屏幕宽度的45%
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.45);
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        }

        // 初始化视图
        TextView tvTitle = dialog.findViewById(R.id.dialog_title);
        TextView tvMessage = dialog.findViewById(R.id.dialog_message);
        Button btnCancel = dialog.findViewById(R.id.negative_button);
        Button btnConfirm = dialog.findViewById(R.id.positive_button);

        // 设置内容
        tvTitle.setText(modeName);
        tvMessage.setText(getString(R.string.confirm_switch_orbit_mode, modeName));
        tvTitle.setGravity(Gravity.CENTER);

        // 设置按钮文本
        btnCancel.setText(R.string.cancel);
        btnConfirm.setText(R.string.confirm);

        btnCancel.setOnClickListener(v -> {
            // 先关闭弹窗，再还原 Switch 状态（临时移除监听器避免递归弹出新弹窗）
            dialog.dismiss();
            switchOrbitMode.setOnCheckedChangeListener(null);
            switchOrbitMode.setChecked(!isVirtual);
            switchOrbitMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                String name = isChecked ? getString(R.string.virtual_track) : getString(R.string.free_path_track);
                showOrbitModeConfirmationDialog(name, isChecked);
            });
            showSmallToast(getString(R.string.switch_cancelled));
        });

        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            executeOrbitModeSwitch(isVirtual);
        });

        dialog.show();
    }

    private void executeOrbitModeSwitch(boolean isVirtual) {
        Log.d(TAG, "========== 开始执行轨道模式切换 ==========");
        String modeName = isVirtual ? getString(R.string.virtual_track) : getString(R.string.free_path_track);

        // Check if already in target mode
        Boolean currentMode = basicViewModel.getIsVirtualOrbitMode().getValue();
        if (currentMode != null && currentMode == isVirtual) {
            showBlackToast(getString(R.string.already_in_mode) + modeName);
            return;
        }

        // Show switching toast
        @SuppressLint({"StringFormatInvalid", "LocalSuppress"}) String switchingTo = getString(R.string.switching_to_mode, modeName);
        showBlackToast(switchingTo);

        // Save to ViewModel
        basicViewModel.setVirtualOrbitMode(isVirtual);

        // Update display
        updateOrbitModeDisplay();

        // Show success toast
        new Handler().postDelayed(() -> {
            String switchedTo = getString(R.string.switched_to_mode, modeName);
            showBlackToast(switchedTo);
        }, 500);
    }

    // 更新轨道模式显示的方法
    // 更新轨道模式显示的方法 - Simplified version
    private void updateOrbitModeDisplay() {
        if (tvTrackSettings != null && basicViewModel != null) {
            Boolean isVirtual = basicViewModel.getIsVirtualOrbitMode().getValue();
            String modeText = (isVirtual != null && isVirtual) ?
                    getString(R.string.virtual_track) :
                    getString(R.string.free_path_track);

            // Create a simple text: "当前导航模式: 固定路线导航"
            String displayText = getString(R.string.track_settings_with_current, modeText);

            // Use SpannableString to style just the mode text
            SpannableString spannableString = new SpannableString(displayText);

            int modeStart = displayText.indexOf(modeText);
            if (modeStart >= 0) {
                // Apply color to the mode text
                spannableString.setSpan(
                        new ForegroundColorSpan(getResources().getColor(android.R.color.holo_orange_dark)),
                        modeStart,
                        modeStart + modeText.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );

                // Apply bold to the entire text
                spannableString.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        0,
                        displayText.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }

            tvTrackSettings.setText(spannableString);
        }
    }

    private void setupCommunicationSettings() {
        etRobotId = getView().findViewById(R.id.etRobotId);
        spinnerLoraChannel = getView().findViewById(R.id.spinnerLoraChannel);
        etLoraAddress = getView().findViewById(R.id.etLoraAddress);
        etCloudIp = getView().findViewById(R.id.etCloudIp);
        etCloudPort = getView().findViewById(R.id.etCloudPort);

        // Setup LoRa channel spinner
        ArrayAdapter<CharSequence> channelAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.lora_channels, android.R.layout.simple_spinner_item);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLoraChannel.setAdapter(channelAdapter);

        // Observe ViewModel data
        basicViewModel.getRobotId().observe(getViewLifecycleOwner(), id -> {
            if (id != null) {
                etRobotId.setText(String.valueOf(id));
            }
        });

        basicViewModel.getLoraChannel().observe(getViewLifecycleOwner(), channel -> {
            if (channel != null) {
                spinnerLoraChannel.setSelection(channel);
            }
        });

        basicViewModel.getLoraAddress().observe(getViewLifecycleOwner(), address -> {
            if (address != null) {
                etLoraAddress.setText(String.valueOf(address));
            }
        });

        basicViewModel.getCloudIp().observe(getViewLifecycleOwner(), ip -> {
            if (ip != null) {
                etCloudIp.setText(ip);
            }
        });

        basicViewModel.getCloudPort().observe(getViewLifecycleOwner(), port -> {
            if (port != null) {
                etCloudPort.setText(String.valueOf(port));
            }
        });
    }

    //滚动样式
    private void showPercentPickerDialog(TextView targetView, int fallbackValue) {
        // 创建自定义布局的对话框
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_number_pickers);

        // 设置对话框宽度为屏幕宽度的36%
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.36);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // 初始化视图
        NumberPicker numberPicker = dialog.findViewById(R.id.numberPicker);
        Button btnConfirm = dialog.findViewById(R.id.btn_confirm);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel);

        // 设置NumberPicker字体大小
        numberPicker.post(() -> {
            try {
                // 尝试多个可能的字段名
                String[] possibleFieldNames = {"mSelectorWheelPaint", "mTextPaint"};
                Field selectorWheelPaintField = null;

                for (String fieldName : possibleFieldNames) {
                    try {
                        selectorWheelPaintField = NumberPicker.class.getDeclaredField(fieldName);
                        break;
                    } catch (NoSuchFieldException e) {
                        // 尝试下一个字段名
                        continue;
                    }
                }

                if (selectorWheelPaintField != null) {
                    selectorWheelPaintField.setAccessible(true);
                    Paint wheelPaint = (Paint) selectorWheelPaintField.get(numberPicker);

                    // 设置新字体大小
                    float newTextSize = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            28,
                            getResources().getDisplayMetrics()
                    );
                    wheelPaint.setTextSize(newTextSize);
                    numberPicker.invalidate();
                }

                // 同时设置所有子TextView的字体大小
                ViewGroup childView = (ViewGroup) numberPicker.getChildAt(0);
                if (childView != null) {
                    for (int i = 0; i < childView.getChildCount(); i++) {
                        View view = childView.getChildAt(i);
                        if (view instanceof TextView) {
                            TextView textView = (TextView) view;
                            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set NumberPicker text size", e);
            }
        });

        // 设置NumberPicker属性
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(100);
        try {
            numberPicker.setValue(Integer.parseInt(targetView.getText().toString()));
        } catch (NumberFormatException e) {
            numberPicker.setValue(fallbackValue);
        }
        numberPicker.setWrapSelectorWheel(false);
        numberPicker.setFormatter(value -> value + "");

        numberPicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);

        // 防止软键盘弹出
        numberPicker.setOnClickListener(v -> {
            // 空实现，阻止点击事件
        });

        // 禁用软键盘
        try {
            Field[] fields = NumberPicker.class.getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals("mInputText")) {
                    field.setAccessible(true);
                    EditText inputText = (EditText) field.get(numberPicker);
                    inputText.setInputType(InputType.TYPE_NULL);
                    inputText.setFocusable(false);
                    inputText.setClickable(false);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable soft keyboard for NumberPicker", e);
        }

        // 设置按钮点击事件
        btnConfirm.setOnClickListener(v -> {
            int selectedValue = numberPicker.getValue();
            targetView.setText(String.valueOf(selectedValue));
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void setupLanguageSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.languages, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(adapter);

        // 设置当前选中的语言
        String currentLang = LocaleHelper.getLanguage(requireContext());
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(currentLang)) {
                spinnerLanguage.setSelection(i);
                break;
            }
        }

        // 移除原有的即时生效监听器，改为在保存时处理
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 这里不再执行语言切换，只是记录选择
                // 实际的语言切换将在保存设置时执行
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupMusicSpinner() {
        // 动态获取raw目录中的音乐文件
        List<String> musicFilesList = getMp3MusicFiles();
        ArrayList<String> musicNamesList = new ArrayList<>(musicFilesList);

        Log.d("MusicSpinner", "Added " + musicFilesList.size() + " music files");
        
        // 转换为数组
        String[] musicFiles = musicFilesList.toArray(new String[0]);
        String[] musicNames = musicNamesList.toArray(new String[0]);

        // 创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, musicNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMusic.setAdapter(adapter);

        // 设置当前选中的音乐
        String currentMusic = basicViewModel.getRunningMusic().getValue();
        if (currentMusic != null) {
            for (int i = 0; i < musicFiles.length; i++) {
                if (musicFiles[i].equals(currentMusic)) {
                    spinnerMusic.setSelection(i);
                    break;
                }
            }
        }

        // 设置选择监听器
        spinnerMusic.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 这里只是记录选择，实际保存将在保存设置时执行
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupSeekBars() {
        // 获取最大音量并设置SeekBar范围
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volumeSeekBar.setMax(maxVolume);

        // 设置当前音量
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeSeekBar.setProgress(currentVolume);
        volumeValue.setText(String.valueOf(currentVolume));

        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(String.valueOf(progress));
                basicViewModel.setVolumeLevel(progress);
                // 实时调整系统音量
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        progress,
                        AudioManager.FLAG_PLAY_SOUND
                );
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = progress / 100.0f;
                speedValue.setText(String.format(Locale.US, "%.1f", speed));
                basicViewModel.setSpeed((double) speed);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupParameterEditors() {
        setupEditableParameter(deliveryStayValue, "delivery_stay", basicViewModel.getDeliveryWaitDuration().getValue() == null ? 30 : basicViewModel.getDeliveryWaitDuration().getValue(), 0, 10000);
        setupEditableParameter(idleChargeWaitValue, "idle_charge_wait_minutes", basicViewModel.getIdleChargeWaitMinutes().getValue() == null ? 10 : basicViewModel.getIdleChargeWaitMinutes().getValue(), 1, 1440);
        setupEditableParameter(idleReturnWaitSecondsValue, "idle_return_wait_seconds", basicViewModel.getIdleReturnWaitSeconds().getValue() == null ? 600 : basicViewModel.getIdleReturnWaitSeconds().getValue(), 1, 86400);
//        setupEditableParameter(pauseWaitValue, "pause_wait", sharedViewModel.getPauseWaitDuration().getValue() == null ? 30 : sharedViewModel.getPauseWaitDuration().getValue());
    }

    private void setupEditableParameter(TextView textView, String prefKey, int defaultValue) {
        setupEditableParameter(textView, prefKey, defaultValue, 0, 100);
    }

    private void setupEditableParameter(TextView textView, String prefKey, int defaultValue, int minValue, int maxValue) {
        if (textView == null) {
            return;
        }
        SharedPreferences prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        int savedValue = prefs.getInt(prefKey, defaultValue);
        textView.setText(String.valueOf(savedValue));

        textView.setOnClickListener(v -> showEditDialog(textView, prefKey, minValue, maxValue));
    }

    private void showEditDialog(TextView targetView, String prefKey) {
        showEditDialog(targetView, prefKey, 0, 100);
    }

    private void showEditDialog(TextView targetView, String prefKey, int minValue, int maxValue) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.modify_settings));

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(targetView.getText());
        builder.setView(input);

        builder.setPositiveButton((getString(R.string.confirm)), (dialog, which) -> {
            String newValue = input.getText().toString();
            if (!newValue.isEmpty()) {
                try {
                    int numericValue = Integer.parseInt(newValue);
                    if (numericValue >= minValue && numericValue <= maxValue) {
                        targetView.setText(newValue);
                        SharedPreferences.Editor editor = requireContext()
                                .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                .edit();
                        editor.putInt(prefKey, numericValue);
                        editor.apply();
                    } else {
                        showToast(getString(R.string.range_error_format, minValue, maxValue));
                    }
                } catch (NumberFormatException e) {
                    showToast(getString(R.string.error_invalid_number));
                }
            }
        });

        builder.setNegativeButton((getString(R.string.cancel)), null);
        builder.show();
    }

    @SuppressLint("DefaultLocale")
    private void saveSettings() {
        int queuedTaskCooldown;
        try {
            queuedTaskCooldown = Integer.parseInt(etQueuedTaskCooldown.getText().toString());
            if (queuedTaskCooldown < 5 || queuedTaskCooldown > 600) {
                showToast(getString(R.string.queued_task_cooldown_range_error));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.error_invalid_number));
            return;
        }

        // 检查识别距离输入值是否在合法范围内
        int idleChargeWaitMinutes;
        try {
            idleChargeWaitMinutes = Integer.parseInt(idleChargeWaitValue.getText().toString());
            if (idleChargeWaitMinutes < 1 || idleChargeWaitMinutes > 1440) {
                showToast(getString(R.string.idle_charge_wait_range_error));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.error_invalid_number));
            return;
        }

        // 检查闲时回待命等待时间是否在合法范围内
        int idleReturnWaitSeconds;
        try {
            idleReturnWaitSeconds = Integer.parseInt(idleReturnWaitSecondsValue.getText().toString());
            if (idleReturnWaitSeconds < 1 || idleReturnWaitSeconds > 86400) {
                showToast(getString(R.string.idle_return_wait_range_error));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.error_invalid_number));
            return;
        }

        float recognizeDistance = 1.0f;
        try {
            recognizeDistance = Float.parseFloat(normalizeDecimalInput(etRecognizeDistance.getText().toString()));
            if ((recognizeDistance >= -3.0f && recognizeDistance <= -0.5f) ||
                    (recognizeDistance >= 0.5f && recognizeDistance <= 3.0f)) {
                basicViewModel.setRecognizeDistance(recognizeDistance);
            } else {
                showToast(getString(R.string.distance));
                etRecognizeDistance.setText("");
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.distance_valid));
            etRecognizeDistance.setText("");
            return;
        }

        // 检查机器人ID是否有效
        int robotId = 1;
        try {
            robotId = Integer.parseInt(etRobotId.getText().toString());
            if (robotId < 0 || robotId > 255) {
                showToast(getString(R.string.basic_robot_id_range_0_255));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.basic_please_enter_valid_robot_id));
            return;
        }

        // 检查LoRa地址是否有效
        int loraAddress = 0;
        try {
            loraAddress = Integer.parseInt(etLoraAddress.getText().toString());
            if (loraAddress < 0 || loraAddress > 65535) {
                showToast(getString(R.string.basic_lora_address_range_0_65535));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.basic_please_enter_valid_lora_address));
            return;
        }

        // 检查语言是否发生变化
        int selectedLanguagePosition = spinnerLanguage.getSelectedItemPosition();
        if (selectedLanguagePosition < 0 || selectedLanguagePosition >= LANGUAGE_CODES.length) {
            Log.w(TAG, "Invalid language selection position: " + selectedLanguagePosition);
            showToast(getString(R.string.error_invalid_number));
            return;
        }
        String selectedLanguage = LANGUAGE_CODES[selectedLanguagePosition];
        String currentLang = LocaleHelper.getLanguage(requireContext());
        boolean languageChanged = !selectedLanguage.equals(currentLang);

        // 保存当前音量到系统
        int volume = volumeSeekBar.getProgress();
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, AudioManager.FLAG_PLAY_SOUND);

        // 创建 SharedPreferences Editor
        SharedPreferences.Editor editor = requireContext()
                .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit();

        // 保存机器人通信设置
        basicViewModel.setRobotId(robotId);
        editor.putInt("robot_id", robotId);

        int loraChannel = spinnerLoraChannel.getSelectedItemPosition();
        basicViewModel.setLoraChannel(loraChannel);
        editor.putInt("lora_channel", loraChannel);

        basicViewModel.setLoraAddress(loraAddress);
        editor.putInt("lora_address", loraAddress);

        // 保存语言设置
        editor.putString("language", selectedLanguage);

        int batteryCritical = parseBatteryThreshold(batteryCriticalValue, DefaultSettings.BATTERY_CRITICAL);
        int batteryMinWork = parseBatteryThreshold(batteryMinWorkValue, DefaultSettings.BATTERY_MIN_WORK);
        int batteryIdle = parseBatteryThreshold(batteryIdleValue, DefaultSettings.BATTERY_IDLE);
        int batteryFull = parseBatteryThreshold(batteryFullValue, DefaultSettings.BATTERY_FULL);
        if (!validateBatteryThresholds(batteryCritical, batteryMinWork, batteryIdle, batteryFull)) {
            return;
        }

        basicViewModel.setBatteryCriticalLevel(batteryCritical);
        basicViewModel.setBatterySafeLevel(batteryMinWork);
        basicViewModel.setBatteryIdleLevel(batteryIdle);
        basicViewModel.setBatteryFullLevel(batteryFull);

        // 兼容旧版低电量阈值字段
        editor.putInt("low_power", batteryMinWork);
        basicViewModel.setLowPower(batteryMinWork);

        // 保存参数配置
        int deliveryStay = Integer.parseInt(deliveryStayValue.getText().toString());
        editor.putInt("delivery_stay", deliveryStay);
        basicViewModel.setDeliveryWaitDuration(deliveryStay);
        basicViewModel.setQueuedTaskCooldown(queuedTaskCooldown);

        // 保存基础设置
        editor.putInt("volume", volumeSeekBar.getProgress());
        editor.putFloat("speed", speedSeekBar.getProgress() / 100.0f);

        // 保存开关状态
        editor.putBoolean("switch_b", switchCharge.isChecked());
        basicViewModel.setAutoCharge(switchCharge.isChecked());
        editor.putBoolean("idle_charge_enabled", switchIdleCharge.isChecked());
        editor.putInt("idle_charge_wait_minutes", idleChargeWaitMinutes);
        basicViewModel.setIdleCharge(switchIdleCharge.isChecked());
        basicViewModel.setIdleChargeWaitMinutes(idleChargeWaitMinutes);

        editor.putBoolean("full_charge_return_home_enabled", switchFullChargeReturnHome.isChecked());
        basicViewModel.setFullChargeReturnHome(switchFullChargeReturnHome.isChecked());

        // 保存闲时回待命设置
        editor.putBoolean("idle_return_home_enabled", switchIdleReturnHome.isChecked());
        editor.putInt("idle_return_wait_seconds", idleReturnWaitSeconds);
        basicViewModel.setIdleReturnHome(switchIdleReturnHome.isChecked());
        basicViewModel.setIdleReturnWaitSeconds(idleReturnWaitSeconds);

        // 保存轨道模式
        boolean isVirtualOrbit = switchOrbitMode.isChecked();
        editor.putBoolean("virtual_orbit_mode", isVirtualOrbit);
        basicViewModel.setVirtualOrbitMode(isVirtualOrbit);

        boolean isVirtualOrbitOneWay = switchVirtualOrbitOneWay.isChecked();
        editor.putBoolean("virtual_orbit_one_way", isVirtualOrbitOneWay);
        basicViewModel.setVirtualOrbitOneWayMode(isVirtualOrbitOneWay);

        // 保存模块开关设置
        editor.putBoolean(FeatureVisibilitySettings.KEY_SHOW_JACK_MODE, switchJackModule.isChecked());
        editor.putBoolean(FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, switchShelfModule.isChecked());
        editor.putBoolean(FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, switchElevatorModule.isChecked());
        editor.putBoolean(FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, switchCallBoxModule.isChecked());
        editor.putBoolean(FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, switchMapAreaModule.isChecked());
        editor.putBoolean(FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, switchAvoidanceModule.isChecked());

        // 保存绕障时间
        int obstacleTime = 0;
        try {
            obstacleTime = Integer.parseInt(etObstacleTime.getText().toString());
            // 绕障时间范围：-1（不绕障）到 600秒（10分钟）
            if (obstacleTime < -1 || obstacleTime > 600) {
                showToast(getString(R.string.basic_obstacle_time_range));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.basic_please_enter_valid_obstacle_time));
            return;
        }
        editor.putInt("obstacle_time", obstacleTime);
        basicViewModel.setVirtualOrbitObstacleTime(obstacleTime);

        // 保存音乐选择
        int selectedMusicPosition = spinnerMusic.getSelectedItemPosition();
        List<String> musicFilesList = getMp3MusicFiles();
        if (selectedMusicPosition < 0 || selectedMusicPosition >= musicFilesList.size()) {
            showToast(getString(R.string.basic_no_mp3_files));
            return;
        }
        String selectedMusic = musicFilesList.get(selectedMusicPosition);
        editor.putString("running_music", selectedMusic);
        basicViewModel.setRunningMusic(selectedMusic);

        // 保存密码设置 - 通过 ViewModel 保存
        boolean usePassword = switchPassword.isChecked();
        basicViewModel.setUsePassword(usePassword);
        String password = etPassword.getText().toString().trim();
        if (password.isEmpty()) {
            password = "0000";
        } else if (password.length() != 4) {
            showToast(getString(R.string.basic_password_must_be_4_digits));
            return;
        }
        basicViewModel.setPassword(password);

        // 检查云端IP格式（允许为空，表示不启用云端）
        String cloudIp = etCloudIp.getText().toString().trim();
        if (!cloudIp.isEmpty()) {
            String ipPattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
            if (!cloudIp.matches(ipPattern)) {
                showToast(getString(R.string.basic_cloud_ip_invalid));
                return;
            }
        }
        basicViewModel.setCloudIp(cloudIp);
        editor.putString("cloud_ip", cloudIp);

        // 检查云端端口是否有效
        int cloudPort;
        try {
            cloudPort = Integer.parseInt(etCloudPort.getText().toString());
            if (cloudPort < 1 || cloudPort > 65535) {
                showToast(getString(R.string.basic_cloud_port_invalid));
                return;
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.basic_cloud_port_invalid));
            return;
        }
        basicViewModel.setCloudPort(cloudPort);
        editor.putInt("cloud_port", cloudPort);

        // 应用所有保存操作
        if (!editor.commit()) {
            Log.w(TAG, "Failed to persist basic settings");
        }
        if (!languageChanged) {
            showToast(getString(R.string.settings_saved));
        }
        hideKeyboardAndClearFocus();

        // 刷新设置页面的按钮可见性
        if (getActivity() instanceof com.ezhan.amr.ui.SettingActivity) {
            ((com.ezhan.amr.ui.SettingActivity) getActivity()).refreshButtonVisibility();
        }
        // 如果语言发生变化，应用新语言并重启
        if (languageChanged) {
            long now = System.currentTimeMillis();
            if (now - lastLanguageChangeTime > 1000) {
                lastLanguageChangeTime = now;
                AppCompatActivity activity = (AppCompatActivity) getActivity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }

                Intent intent = requireActivity().getIntent();
                intent.putExtra("language_changed", true);
                intent.putExtra("target_fragment", "voice_settings");
                MyApplication.getInstance().getBasicViewModel().setLanguageChanged(true);
                LocaleHelper.applyNewLocale(requireContext(), selectedLanguage);
                MyApplication.getInstance().getBasicViewModel().resetVoicePromptsToCurrentLanguage(requireContext());
                restartApplication(activity);
            }
        }
    }

    private void hideKeyboardAndClearFocus() {
        View focusedView = requireActivity().getCurrentFocus();
        if (focusedView == null) {
            focusedView = getView();
        }
        if (focusedView == null) {
            return;
        }

        focusedView.clearFocus();
        InputMethodManager inputMethodManager =
                (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        }
    }

    private String normalizeDecimalInput(String value) {
        return value == null ? "" : value.trim().replace(',', '.');
    }

    private void restartApplication(AppCompatActivity activity) {
        cancelActiveToast();
        Context appContext = activity.getApplicationContext();
        Intent restartIntent = appContext.getPackageManager()
                .getLaunchIntentForPackage(appContext.getPackageName());
        if (restartIntent == null) {
            activity.recreate();
            return;
        }

        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        new Handler().postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            activity.startActivity(restartIntent);
            activity.finishAffinity();
        }, 250);
    }

    private void resetDefaultSettings() {
        // 创建自定义对话框
        final Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_confirm_fetch);

        // 设置对话框宽度为屏幕宽度的45%
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.45);
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        }

        // 初始化视图
        TextView tvTitle = dialog.findViewById(R.id.dialog_title);
        TextView tvMessage = dialog.findViewById(R.id.dialog_message);
        Button btnCancel = dialog.findViewById(R.id.negative_button);
        Button btnConfirm = dialog.findViewById(R.id.positive_button);

        // 设置内容
        tvTitle.setText(R.string.confirm_reset_title);
        tvMessage.setText(R.string.confirm_reset_message);
        tvTitle.setGravity(Gravity.CENTER);

        // 设置按钮监听器
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            // 恢复默认值的逻辑
            deliveryStayValue.setText(String.valueOf(DefaultSettings.DELIVERY_STAY));
            etQueuedTaskCooldown.setText(String.valueOf(DefaultSettings.QUEUED_TASK_COOLDOWN));
            updateBatteryThresholdViews(
                    DefaultSettings.BATTERY_CRITICAL,
                    DefaultSettings.BATTERY_MIN_WORK,
                    DefaultSettings.BATTERY_IDLE,
                    DefaultSettings.BATTERY_FULL);
            etRecognizeDistance.setText(String.valueOf(DefaultSettings.RECOGNIZE_DISTANCE));

            volumeSeekBar.setProgress(DefaultSettings.VOLUME);
            volumeValue.setText(String.valueOf(DefaultSettings.VOLUME));

            speedSeekBar.setProgress(DefaultSettings.SPEED_PROGRESS);
            speedValue.setText(String.format(Locale.US, "%.1f", DefaultSettings.SPEED_VALUE));
            switchOrbitMode.setChecked(DefaultSettings.VIRTUAL_ORBIT_MODE);
            switchVirtualOrbitOneWay.setChecked(DefaultSettings.VIRTUAL_ORBIT_ONE_WAY);

            switchCharge.setChecked(DefaultSettings.AUTO_CHARGE);
            switchIdleCharge.setChecked(DefaultSettings.IDLE_CHARGE);
            idleChargeWaitValue.setText(String.valueOf(DefaultSettings.IDLE_CHARGE_WAIT_MINUTES));
            switchFullChargeReturnHome.setChecked(DefaultSettings.FULL_CHARGE_RETURN_HOME);

            // 重置闲时回待命设置
            switchIdleReturnHome.setChecked(DefaultSettings.IDLE_RETURN_HOME);
            idleReturnWaitSecondsValue.setText(String.valueOf(DefaultSettings.IDLE_RETURN_WAIT_SECONDS));

            // 重置模块开关为默认值
            switchJackModule.setChecked(DefaultSettings.JACK_MODULE_ENABLED);
            switchShelfModule.setChecked(DefaultSettings.SHELF_MODULE_ENABLED);
            switchElevatorModule.setChecked(true);
            switchCallBoxModule.setChecked(true);
            switchMapAreaModule.setChecked(true);
            switchAvoidanceModule.setChecked(true);
            basicViewModel.setVirtualOrbitMode(DefaultSettings.VIRTUAL_ORBIT_MODE);
            basicViewModel.setVirtualOrbitOneWayMode(DefaultSettings.VIRTUAL_ORBIT_ONE_WAY);

            // 重置音乐选择为默认值
            spinnerMusic.setSelection(0);

            // Reset communication settings
            etRobotId.setText("1");
            spinnerLoraChannel.setSelection(0);
            etLoraAddress.setText("0");

            // Reset cloud connection settings
            etCloudIp.setText("");
            etCloudPort.setText("1883");

            saveSettings();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void loadSavedSettings() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 加载基础设置
        volumeSeekBar.setProgress(prefs.getInt("volume", 6));
        volumeValue.setText(String.valueOf(prefs.getInt("volume", 6)));

        float defaultSpeed = 0.4f;
        speedSeekBar.setProgress((int) (prefs.getFloat("speed", defaultSpeed) * 100));
        speedValue.setText(String.format(Locale.US, "%.1f", prefs.getFloat("speed", defaultSpeed)));

        // 加载参数配置
        deliveryStayValue.setText(String.valueOf(prefs.getInt("delivery_stay", 30)));

        // 加载识别距离 - now from ViewModel (already loaded via observe)
        // The ViewModel will update the UI through observer

        // 加载开关状态
        switchCharge.setChecked(prefs.getBoolean("switch_b", false));
        switchIdleCharge.setChecked(prefs.getBoolean("idle_charge_enabled", true));
        idleChargeWaitValue.setText(String.valueOf(prefs.getInt("idle_charge_wait_minutes", DefaultSettings.IDLE_CHARGE_WAIT_MINUTES)));
        switchFullChargeReturnHome.setChecked(prefs.getBoolean(
                "full_charge_return_home_enabled", DefaultSettings.FULL_CHARGE_RETURN_HOME));

        // 加载闲时回待命设置
        switchIdleReturnHome.setChecked(prefs.getBoolean("idle_return_home_enabled", DefaultSettings.IDLE_RETURN_HOME));
        idleReturnWaitSecondsValue.setText(String.valueOf(prefs.getInt("idle_return_wait_seconds", DefaultSettings.IDLE_RETURN_WAIT_SECONDS)));
        switchVirtualOrbitOneWay.setChecked(
                prefs.getBoolean("virtual_orbit_one_way", DefaultSettings.VIRTUAL_ORBIT_ONE_WAY));

        // 加载模块开关设置
        switchJackModule.setChecked(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_JACK_MODE, true));
        switchShelfModule.setChecked(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_SHELF_SETTINGS, true));
        switchElevatorModule.setChecked(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_ELEVATOR_SETTINGS, true));
        switchCallBoxModule.setChecked(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_CALL_SETTINGS, true));
        switchMapAreaModule.setChecked(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_MAP_AREA_SETTINGS, true));
        switchAvoidanceModule.setChecked(prefs.getBoolean(FeatureVisibilitySettings.KEY_SHOW_AVOIDANCE_SETTINGS, true));
        // Load communication settings
        etRobotId.setText(String.valueOf(prefs.getInt("robot_id", 1)));
        spinnerLoraChannel.setSelection(prefs.getInt("lora_channel", 0));
        etLoraAddress.setText(String.valueOf(prefs.getInt("lora_address", 0)));
        etCloudIp.setText(prefs.getString("cloud_ip", ""));
        etCloudPort.setText(String.valueOf(prefs.getInt("cloud_port", 1883)));

        // 加载音乐选择
        String savedMusic = prefs.getString("running_music", "wa");
        List<String> musicFilesList = getMp3MusicFiles();
        for (int i = 0; i < musicFilesList.size(); i++) {
            if (musicFilesList.get(i).equals(savedMusic)) {
                spinnerMusic.setSelection(i);
                break;
            }
        }

        // 加载密码设置 - 从 ViewModel 加载
        Boolean usePassword = basicViewModel.getIsUsePassword().getValue();
        boolean isUsePassword = usePassword != null && usePassword;
        switchPassword.setChecked(isUsePassword);
        etPassword.setVisibility(isUsePassword ? View.VISIBLE : View.GONE);

        String savedPassword = basicViewModel.getPassword().getValue();
        etPassword.setText(savedPassword != null ? savedPassword : "0000");
    }

    private List<String> getMp3MusicFiles() {
        return RawMusicUtils.getRawMp3MusicNames(requireContext());
    }

    private void showToast(String message) {
        cancelActiveToast();
        activeToast = Toast.makeText(requireContext().getApplicationContext(), message, Toast.LENGTH_SHORT);
        activeToast.show();
    }

    private void cancelActiveToast() {
        if (activeToast != null) {
            activeToast.cancel();
            activeToast = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "Fragment view destroyed");
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reload settings from SharedPreferences in case config was changed externally
        loadSavedSettings();
        // 每次返回页面时更新轨道模式显示
        updateOrbitModeDisplay();
    }

    // 添加黑色字体Toast方法
    private void showBlackToast(String message) {
        try {
            Log.d(TAG, "显示黑色Toast: " + message);

            // 确保在主线程
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        // 创建自定义Toast
                        Toast toast = new Toast(requireContext());
                        toast.setDuration(Toast.LENGTH_SHORT);
                        toast.setGravity(Gravity.CENTER, 0, 0);

                        // 使用黑色字体布局
                        LayoutInflater inflater = LayoutInflater.from(requireContext());
                        View toastView = inflater.inflate(R.layout.custom_toast_black, null);

                        // 设置消息文本
                        TextView textView = toastView.findViewById(R.id.toast_text_black);
                        if (textView != null) {
                            textView.setText(message);
                            textView.setTextColor(Color.BLACK); // 确保黑色
                        } else {
                            // 如果找不到TextView，使用默认Toast
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        toast.setView(toastView);
                        toast.show();

                    } catch (Exception e) {
                        Log.e(TAG, "自定义黑色Toast失败，使用默认Toast", e);
                        Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "显示黑色Toast时发生异常", e);
        }
    }

    /**
     * 显示小提示框（Toast）
     */
    private void showSmallToast(String message) {
        try {
            Log.d(TAG, "显示Toast: " + message);

            // 确保在主线程显示
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        // 创建自定义Toast
                        Toast toast = new Toast(requireContext());
                        toast.setDuration(Toast.LENGTH_SHORT);
                        toast.setGravity(Gravity.CENTER, 0, 0);

                        // 使用布局文件（如果custom_toast存在，就使用它）
                        LayoutInflater inflater = LayoutInflater.from(requireContext());
                        View toastView = inflater.inflate(R.layout.custom_toast, null);

                        // 设置消息文本
                        TextView textView = toastView.findViewById(R.id.toast_text);
                        if (textView != null) {
                            textView.setText(message);
                            textView.setTextColor(Color.BLACK); // 修改为黑色
                        } else {
                            // 如果找不到TextView，使用默认Toast
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 设置背景为浅色
                        toastView.setBackgroundResource(R.drawable.toast_background_light);

                        toast.setView(toastView);
                        toast.show();

                    } catch (Exception e) {
                        Log.e(TAG, "自定义Toast失败，使用默认Toast", e);
                        Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "显示Toast时发生异常", e);
        }
    }

    public class DefaultSettings {
        public static final int DELIVERY_STAY = 30;
        public static final int QUEUED_TASK_COOLDOWN = 6;
        public static final int LOW_POWER = 20;
        public static final int BATTERY_CRITICAL = 5;
        public static final int BATTERY_MIN_WORK = 20;
        public static final int BATTERY_IDLE = 60;
        public static final int BATTERY_FULL = 100;
        public static final float RECOGNIZE_DISTANCE = 1.0f; // Renamed from IDENTIFICATION_DISTANCE
        public static final int VOLUME = 6;
        public static final int SPEED_PROGRESS = 40;  // 对应0.4 (40/100)
        public static final float SPEED_VALUE = SPEED_PROGRESS / 100.0f;
        public static final boolean AUTO_CHARGE = false;
        public static final boolean IDLE_CHARGE = true;
        public static final int AUTO_CHARGE_START_MINUTE = 0;
        public static final int AUTO_CHARGE_END_MINUTE = 0;
        public static final int IDLE_CHARGE_WAIT_MINUTES = 10;
        public static final boolean FULL_CHARGE_RETURN_HOME = true;
        // 闲时回待命默认值
        public static final boolean IDLE_RETURN_HOME = false;
        public static final int IDLE_RETURN_WAIT_SECONDS = 600;
        public static final boolean USE_PASSWORD = false;
        public static final boolean VIRTUAL_ORBIT_MODE = true; // Default to virtual orbit
        public static final boolean VIRTUAL_ORBIT_ONE_WAY = false;
        
        // 模块开关默认值
        public static final boolean JACK_MODULE_ENABLED = true;
        public static final boolean SHELF_MODULE_ENABLED = true;
    }

}

