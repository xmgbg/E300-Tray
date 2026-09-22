// AppLifecycleTracker.java
package com.ezhan.amr.utils;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;
import java.util.HashSet;
import java.util.Set;

public class AppLifecycleTracker implements Application.ActivityLifecycleCallbacks, LifecycleObserver {

    private static AppLifecycleTracker instance;
    private final Set<Class<?>> runningActivities = new HashSet<>();

    public static void initialize(Application application) {
        if (instance == null) {
            instance = new AppLifecycleTracker();
            application.registerActivityLifecycleCallbacks(instance);
            ProcessLifecycleOwner.get().getLifecycle().addObserver(instance);
        }
    }

    public static AppLifecycleTracker getInstance() {
        return instance;
    }

    public boolean isActivityRunning(Class<?> activityClass) {
        return runningActivities.contains(activityClass);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        runningActivities.add(activity.getClass());
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        runningActivities.add(activity.getClass());
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        runningActivities.add(activity.getClass());
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        // Don't remove here to handle configuration changes
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        // Don't remove here to handle configuration changes
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        runningActivities.remove(activity.getClass());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        // Clear all activities when app goes to background
        runningActivities.clear();
    }
}