package com.ezhan.amr.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.StringRes;

import java.util.Locale;

public class LocaleHelper {
    private static final String SELECTED_LANGUAGE = "Locale.Helper.Selected.Language";
    private static final String DEFAULT_LANGUAGE = "zh";

    public static Context setLocale(Context context) {
        return updateResources(context, getPersistedLanguage(context));
    }

    public static String getLanguage(Context context) {
        return getPersistedLanguage(context);
    }

    public static void applyNewLocale(Context context, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        persistLanguage(context, normalizedLanguage);
        updateResources(context, normalizedLanguage);
    }

    private static String getPersistedLanguage(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return normalizeLanguage(prefs.getString(SELECTED_LANGUAGE, DEFAULT_LANGUAGE));
    }

    private static void persistLanguage(Context context, String language) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(SELECTED_LANGUAGE, normalizeLanguage(language));
        if (!editor.commit()) {
            Log.e("LocaleHelper", "Failed to persist language: " + language);
        }
    }

    private static Context updateResources(Context context, String language) {
        Locale locale = createLocale(language);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
            context = context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
        }

        res.updateConfiguration(config, res.getDisplayMetrics());
        return context;
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        return language.trim().replace('_', '-');
    }

    private static Locale createLocale(String language) {
        String normalizedLanguage = normalizeLanguage(language);
        String[] langParts = normalizedLanguage.split("-");
        if (langParts.length > 1) {
            return new Locale(langParts[0], langParts[1]);
        }
        return new Locale(normalizedLanguage);
    }

    public static void updateServiceResources(Context context, Resources res) {
        if (context == null || res == null) {
            Log.e("LocaleHelper", "Invalid context or resources");
            return;
        }

        Locale locale = createLocale(getPersistedLanguage(context));
        Locale.setDefault(locale);

        Configuration config = new Configuration(res.getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
        } else {
            config.locale = locale;
        }
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    public static String onServiceGetString(Context context, @StringRes int resId) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(createLocale(getPersistedLanguage(context)));
        return context.createConfigurationContext(config).getText(resId).toString();
    }

    public static String onServiceGetString(Context context, @StringRes int resId, Object... formatArgs) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(createLocale(getPersistedLanguage(context)));
        return String.format(context.createConfigurationContext(config).getText(resId).toString(), formatArgs);
    }
}
