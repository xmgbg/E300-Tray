package com.ezhan.amr.utils;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextToSpeechHelper implements TextToSpeech.OnInitListener {

    private TextToSpeech textToSpeech;
    private Context context;
    private boolean isReady = false;
    private OnTTSEventListener listener;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnTTSEventListener {
        void onStart(String utteranceId);
        void onDone(String utteranceId);
        void onError(String utteranceId);
        void onTTSInitialized(int status);  // Single abstract method
    }

    public TextToSpeechHelper(Context context, OnTTSEventListener listener) {
        this.context = context;
        this.listener = listener;
        textToSpeech = new TextToSpeech(context, this);
    }

    public void setTtsEventListener(OnTTSEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Locale selectedLocale = applyBestSupportedLanguage();
            if (selectedLocale != null) {
                isReady = true;
                setupUtteranceListener();
                Log.d("TTS", "Language initialized: " + selectedLocale.toLanguageTag());
            } else {
                Log.e("TTS", "No supported language found");
            }
        } else {
            Log.e("TTS", "Initialization failed");
        }
    }

    private Locale applyBestSupportedLanguage() {
        for (Locale locale : getPreferredTtsLocales()) {
            int result = textToSpeech.setLanguage(locale);
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return locale;
            }
            Log.w("TTS", "Language not supported: " + locale.toLanguageTag() + ", result=" + result);
        }
        return null;
    }

    private List<Locale> getPreferredTtsLocales() {
        List<Locale> locales = new ArrayList<>();
        String language = LocaleHelper.getLanguage(context);
        String normalizedLanguage = language != null ? language.trim().replace('_', '-') : "";

        if ("zh-HK".equalsIgnoreCase(normalizedLanguage) || "zh-rHK".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(Locale.forLanguageTag("yue-Hant-HK"));
            locales.add(Locale.forLanguageTag("zh-Hant-HK"));
            locales.add(Locale.TRADITIONAL_CHINESE);
            locales.add(Locale.CHINESE);
        } else if ("zh".equalsIgnoreCase(normalizedLanguage) || "zh-CN".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(Locale.SIMPLIFIED_CHINESE);
            locales.add(Locale.CHINESE);
        } else if ("en".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(Locale.ENGLISH);
            locales.add(Locale.US);
        } else if ("ja".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(Locale.JAPANESE);
        } else if ("ko".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(Locale.KOREAN);
        } else if ("vi".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(new Locale("vi", "VN"));
            locales.add(new Locale("vi"));
        } else if ("in".equalsIgnoreCase(normalizedLanguage) || "id".equalsIgnoreCase(normalizedLanguage)) {
            locales.add(new Locale("id", "ID"));
            locales.add(new Locale("in", "ID"));
        } else if (!normalizedLanguage.isEmpty()) {
            locales.add(Locale.forLanguageTag(normalizedLanguage));
        }

        locales.add(Locale.getDefault());
        return locales;
    }

    public boolean isInitialized() {
        return isReady;
    }

    private void setupUtteranceListener() {
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                mainHandler.post(() -> {
                    if (listener != null) listener.onStart(utteranceId);
                });
            }

            @Override
            public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    if (listener != null) listener.onDone(utteranceId);
                });
            }

            @Override
            public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    if (listener != null) listener.onError(utteranceId);
                });
            }
        });
    }

    public void speak(String text) {
        if (isReady && !text.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId");
            } else {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        }
    }

    public void stop() {
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
    }

    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}
