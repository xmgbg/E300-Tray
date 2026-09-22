package com.ezhan.amr.ui.fragments;

import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.utils.LocaleHelper;
import com.ezhan.amr.utils.AndroidBug5497Workaround;
import com.ezhan.amr.utils.TextToSpeechHelper;
import com.ezhan.amr.viewmodels.BasicViewModel;

import java.util.HashMap;
import java.util.Map;

public class VoiceSettingsFragment extends Fragment implements TextToSpeechHelper.OnTTSEventListener {
    // 日志标签
    private static final String TAG = "VoiceSettingsFragment";
    private AudioFocusRequest audioFocusRequest;
    private AudioManager audioManager;

    private Map<String, String> voiceList = new HashMap<>();
    private BasicViewModel basicViewModel;
    private Map<String, String> lastVoiceConfig = new HashMap<>();

    // 语音键映射 (EditText ID -> 语音键资源ID)
    private static final Map<Integer, Integer> VOICE_KEY_MAP = new HashMap<Integer, Integer>() {{
        put(R.id.editTextDeparture, R.string.departure_broadcast);
        put(R.id.editTextArrival, R.string.arrival);
        put(R.id.editTextLost, R.string.position_lost);
        put(R.id.editTextLowPower, R.string.low_battery);
        put(R.id.editTextObstacle, R.string.obstacle_alert);
        put(R.id.editTextTaskDone, R.string.task_completed);
        put(R.id.editTextOpError, R.string.operation_error_prompt);
        put(R.id.editTextChargeDone, R.string.charge_complete);
        put(R.id.editTextReturnArrive, R.string.return_arrival);
        put(R.id.editTextTaskBreak, R.string.task_interrupted);
        //put(R.id.editTextLeadGo, R.string.lead_departure);
        put(R.id.editTextCruiseGo, R.string.cruise_departure);
        put(R.id.editTextCruiseArrive, R.string.cruise_arrival);
        put(R.id.editTextPedestrian, R.string.pedestrian_alert);
        put(R.id.editTextGoCharge, R.string.go_charging);
        put(R.id.editTextEmergencyStop, R.string.emergency_stop1);
        put(R.id.editTextCollisionAlert, R.string.collision_alert);
        put(R.id.editTextNavigationError, R.string.navigation_error);
    }};

    // 默认文本映射 (EditText ID -> 默认文本资源ID)
    private static final Map<Integer, Integer> DEFAULT_TEXT_MAP = new HashMap<Integer, Integer>() {{
        put(R.id.editTextDeparture, R.string.default_departure_text);
        put(R.id.editTextArrival, R.string.default_arrival_text);
        put(R.id.editTextLost, R.string.default_lost_text);
        put(R.id.editTextLowPower, R.string.default_low_power_text);
        put(R.id.editTextObstacle, R.string.default_obstacle_text);
        put(R.id.editTextTaskDone, R.string.default_task_done_text);
        put(R.id.editTextOpError, R.string.default_op_error_text);
        put(R.id.editTextChargeDone, R.string.default_charge_done_text);
        put(R.id.editTextReturnArrive, R.string.default_return_arrive_text);
        put(R.id.editTextTaskBreak, R.string.default_task_break_text);
        put(R.id.editTextCruiseGo, R.string.default_cruise_go_text);
        put(R.id.editTextCruiseArrive, R.string.default_cruise_arrive_text);
        put(R.id.editTextPedestrian, R.string.default_pedestrian_text);
        put(R.id.editTextGoCharge, R.string.default_go_charge_text);
        put(R.id.editTextEmergencyStop, R.string.default_emergency_stop_text);
        put(R.id.editTextCollisionAlert, R.string.default_collision_alert_text);
        put(R.id.editTextNavigationError, R.string.default_navigation_error_text);
    }};

    // 播放按钮与输入框映射
    private final Map<Integer, Integer> voiceButtonsMap = new HashMap<Integer, Integer>() {{
        put(R.id.imageViewDeparture, R.id.editTextDeparture);
        put(R.id.imageViewArrival, R.id.editTextArrival);
        put(R.id.imageViewLost, R.id.editTextLost);
        put(R.id.imageViewLowPower, R.id.editTextLowPower);
        put(R.id.imageViewObstacle, R.id.editTextObstacle);
        put(R.id.imageViewTaskDone, R.id.editTextTaskDone);
        put(R.id.imageViewOpError, R.id.editTextOpError);
        put(R.id.imageViewChargeDone, R.id.editTextChargeDone);
        put(R.id.imageViewReturnArrive, R.id.editTextReturnArrive);
        put(R.id.imageViewTaskBreak, R.id.editTextTaskBreak);
        put(R.id.imageViewCruiseGo, R.id.editTextCruiseGo);
        put(R.id.imageViewCruiseArrive, R.id.editTextCruiseArrive);
        put(R.id.imageViewPedestrian, R.id.editTextPedestrian);
        put(R.id.imageViewGoCharge, R.id.editTextGoCharge);
        put(R.id.imageViewEmergencyStop, R.id.editTextEmergencyStop);
        put(R.id.imageViewCollisionAlert, R.id.editTextCollisionAlert);
        put(R.id.imageViewNavigationError, R.id.editTextNavigationError);
    }};

    private TextToSpeechHelper ttsHelper;
    private String currentLanguage;
    private View rootView;

    @Override
    public void onTTSInitialized(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS failed: " + status);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_voice_settings, container, false);
        audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);

        // 获取当前语言
        currentLanguage = LocaleHelper.getLanguage(requireContext());

        basicViewModel = MyApplication.getInstance().getBasicViewModel();

        // 初始加载数据前初始化资源映射
        initResourceMaps();
        loadInitialData();


        setupVoiceButtons();
        setupAllEditTexts();

        // 设置窗口背景为白色，避免Splash背景闪烁
        if (getActivity() != null && getActivity().getWindow() != null) {
            getActivity().getWindow().setBackgroundDrawableResource(android.R.color.white);
        }
        // 恢复默认按钮
        rootView.findViewById(R.id.resetToDefaultBtn).setOnClickListener(v ->
                restoreAllToDefault());

        // 保存按钮
        rootView.findViewById(R.id.saveBtn).setOnClickListener(v ->
                saveVoiceConfig());

        return rootView;
    }

    // 初始加载数据（只执行一次）
    private void loadInitialData() {
        basicViewModel.getVoicePromptList().observe(getViewLifecycleOwner(), list -> {
            if (list != null && !list.equals(lastVoiceConfig)) {
                lastVoiceConfig = new HashMap<>(list);
                voiceList = list;
                updateUIFromVoiceList();
                Log.d(TAG, "语音设置已加载（有效更新）");
            }
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initTtsEngine();

        // 添加辅助类处理软键盘遮挡
        if (getActivity() != null) {
            AndroidBug5497Workaround.assistActivity(getActivity());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkLanguageChange();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "系统配置已变更");
        checkLanguageChange();
    }

    // 检查语言是否变化
    private void checkLanguageChange() {
        basicViewModel.getLanguageChanged().observe(getViewLifecycleOwner(), changed -> {
            if (changed) {
                Log.d(TAG, "检测到语言已变更");

                // 重置语言变化标志
                basicViewModel.setLanguageChanged(false);

                String newLanguage = LocaleHelper.getLanguage(requireContext());
                currentLanguage = newLanguage;

                // 重新初始化资源映射
                initResourceMaps();
                restoreAllToDefault();
            }
        });
    }

    private void initTtsEngine() {
        if (ttsHelper == null) {
            ttsHelper = new TextToSpeechHelper(requireActivity(), this);
        } else if (!ttsHelper.isInitialized()) {
            ttsHelper.shutdown();
            ttsHelper = new TextToSpeechHelper(requireActivity(), this);
        }
    }

    // 设置播放按钮点击事件
    private void setupVoiceButtons() {
        for (Map.Entry<Integer, Integer> entry : voiceButtonsMap.entrySet()) {
            ImageView playButton = rootView.findViewById(entry.getKey());
            EditText editText = rootView.findViewById(entry.getValue());

            if (playButton != null && editText != null) {
                playButton.setOnClickListener(v ->
                        playVoiceMessage(editText.getText().toString()));
            }
        }
    }

    // 播放语音消息
    private void playVoiceMessage(String message) {
        if (ttsHelper == null || !ttsHelper.isInitialized()) {
            Toast.makeText(requireContext(), R.string.system_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        if (message == null || message.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_visual_prompts_available, Toast.LENGTH_SHORT).show();
            return;
        }

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();

            int result = audioManager.requestAudioFocus(audioFocusRequest);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                ttsHelper.speak(message);
            } else {
            }
        } else {
            int result = audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                ttsHelper.speak(message);
            } else {
            }
        }
    }

    // 音频焦点监听
    private AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            if (ttsHelper != null) {
                ttsHelper.stop();
            }
            releaseAudioFocus();
        }
    };

    // 释放音频焦点
    private void releaseAudioFocus() {
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(focusChangeListener);
            }
        }
    }

    // 音频焦点处理
    @Override
    public void onPause() {
        super.onPause();
    }

    // 替换消息中的占位符
    private String replacePlaceholders(String text) {
        // 示例：将 ${name} 替换为具体的位置名称
        return text.replace("${name}", "目的地");
    }

    // 遍历所有 EditText，设置双击可编辑和回车保存
    private void setupAllEditTexts() {
        if (rootView == null) return;

        setupEditTextsRecursive(rootView);
    }

    private void setupEditTextsRecursive(View view) {
        if (view instanceof EditText) {
            EditText editText = (EditText) view;
            setupDoubleClickToEdit(editText);
            setupDoneAction(editText);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setupEditTextsRecursive(group.getChildAt(i));
            }
        }
    }

    private void setupDoubleClickToEdit(EditText editText) {
        editText.setOnClickListener(v -> {
            // 直接设置为可编辑状态
            editText.setFocusableInTouchMode(true);
            editText.setFocusable(true);
            editText.requestFocus();

            // 移动光标到文本末尾
            //editText.setSelection(editText.getText().length());

            // 显示软键盘
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    // 回车（actionDone）时自动失去焦点并隐藏输入法
    private void setupDoneAction(EditText editText) {
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // 移除焦点并隐藏键盘
                editText.clearFocus();
                InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
    }

    // 恢复所有语音语句为默认内容
    private void restoreAllToDefault() {
        Map<String, String> defaultConfig = new HashMap<>();

        for (Map.Entry<Integer, Integer> entry : VOICE_KEY_MAP.entrySet()) {
            int editTextId = entry.getKey();
            int voiceKeyResId = entry.getValue();
            int defaultTextResId = DEFAULT_TEXT_MAP.get(editTextId);

            // 使用资源名称作为键
            String voiceKey = getResources().getResourceEntryName(voiceKeyResId);

            // 获取当前语言的默认文本
            String defaultText = LocaleHelper.onServiceGetString(requireContext(), defaultTextResId);

            // 更新编辑框显示
            EditText et = rootView.findViewById(editTextId);
            if (et != null) {
                et.setText(defaultText);
            }

            defaultConfig.put(voiceKey, defaultText);
        }

        basicViewModel.saveToPreferences(defaultConfig);
        Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }


    private void saveVoiceConfig() {
        if (rootView == null) return;

        try {
            Map<String, String> updatedConfig = new HashMap<>();

            for (Map.Entry<Integer, Integer> entry : VOICE_KEY_MAP.entrySet()) {
                int editTextId = entry.getKey();
                int voiceKeyResId = entry.getValue();

                String voiceKey = getResources().getResourceEntryName(voiceKeyResId);
                EditText editText = rootView.findViewById(editTextId);

                if (editText != null) {
                    String newValue = editText.getText().toString().trim();
                    updatedConfig.put(voiceKey, newValue);
                }
            }

            // 直接保存到ViewModel
            basicViewModel.saveToPreferences(updatedConfig);

            // 立即更新 ViewModel 中的语音列表，以便其他界面立即生效
            basicViewModel.updateVoicePromptList(updatedConfig);

            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.operation_error_format, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateUIFromVoiceList() {
        for (Map.Entry<Integer, Integer> entry : VOICE_KEY_MAP.entrySet()) {
            int editTextId = entry.getKey();
            int voiceKeyResId = entry.getValue();
            String voiceKey = getResources().getResourceEntryName(voiceKeyResId);

            EditText editText = rootView.findViewById(editTextId);
            if (editText != null) {
                // 优先使用保存的值，否则使用当前语言的默认值
                String text = voiceList.get(voiceKey);
                if (text == null || text.isEmpty()) {
                    int defaultTextResId = DEFAULT_TEXT_MAP.get(editTextId);
                    text = getString(defaultTextResId);
                }

                editText.setText(text);
                editText.setFocusable(false);
                editText.setFocusableInTouchMode(false);
            }
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        releaseAudioFocus();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ttsHelper != null) {
            ttsHelper.shutdown();
            ttsHelper = null;
        }
    }

    @Override
    public void onStart(String utteranceId) {
        // TTS开始播放
    }

    @Override
    public void onDone(String utteranceId) {
        releaseAudioFocus();
    }

    @Override
    public void onError(String utteranceId) {
        releaseAudioFocus();
    }
    private void initResourceMaps() {
        // 清空原有映射
        VOICE_KEY_MAP.clear();
        DEFAULT_TEXT_MAP.clear();

        // 重新构建当前语言的资源映射
        VOICE_KEY_MAP.put(R.id.editTextDeparture, R.string.departure_broadcast);
        VOICE_KEY_MAP.put(R.id.editTextArrival, R.string.arrival);
        VOICE_KEY_MAP.put(R.id.editTextLost, R.string.position_lost);
        VOICE_KEY_MAP.put(R.id.editTextLowPower, R.string.low_battery);
        VOICE_KEY_MAP.put(R.id.editTextObstacle, R.string.obstacle_alert);
        VOICE_KEY_MAP.put(R.id.editTextTaskDone, R.string.task_completed);
        VOICE_KEY_MAP.put(R.id.editTextOpError, R.string.operation_error_prompt);
        VOICE_KEY_MAP.put(R.id.editTextChargeDone, R.string.charge_complete);
        VOICE_KEY_MAP.put(R.id.editTextReturnArrive, R.string.return_arrival);
        VOICE_KEY_MAP.put(R.id.editTextTaskBreak, R.string.task_interrupted);
        VOICE_KEY_MAP.put(R.id.editTextCruiseGo, R.string.cruise_departure);
        VOICE_KEY_MAP.put(R.id.editTextCruiseArrive, R.string.cruise_arrival);
        VOICE_KEY_MAP.put(R.id.editTextPedestrian, R.string.pedestrian_alert);
        VOICE_KEY_MAP.put(R.id.editTextGoCharge, R.string.go_charging);
        VOICE_KEY_MAP.put(R.id.editTextEmergencyStop, R.string.emergency_stop1);
        VOICE_KEY_MAP.put(R.id.editTextCollisionAlert, R.string.collision_alert);
        VOICE_KEY_MAP.put(R.id.editTextNavigationError, R.string.navigation_error);

        DEFAULT_TEXT_MAP.put(R.id.editTextDeparture, R.string.default_departure_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextArrival, R.string.default_arrival_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextLost, R.string.default_lost_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextLowPower, R.string.default_low_power_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextObstacle, R.string.default_obstacle_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextTaskDone, R.string.default_task_done_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextOpError, R.string.default_op_error_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextChargeDone, R.string.default_charge_done_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextReturnArrive, R.string.default_return_arrive_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextTaskBreak, R.string.default_task_break_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextCruiseGo, R.string.default_cruise_go_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextCruiseArrive, R.string.default_cruise_arrive_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextPedestrian, R.string.default_pedestrian_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextGoCharge, R.string.default_go_charge_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextEmergencyStop, R.string.default_emergency_stop_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextCollisionAlert, R.string.default_collision_alert_text);
        DEFAULT_TEXT_MAP.put(R.id.editTextNavigationError, R.string.default_navigation_error_text);
    }
}
