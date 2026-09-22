package com.ezhan.amr.navigation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.ui.CruiseActivity;
import com.ezhan.amr.viewmodels.SharedViewModel;

public class ScheduledCruiseReceiver extends BroadcastReceiver {
    private static final String TAG = "ScheduledCruiseReceiver";
    public static final String ACTION_RUN_SCHEDULED_CRUISE =
            "com.ezhan.amr.action.RUN_SCHEDULED_CRUISE";
    public static final String EXTRA_TASK_ID = "scheduled_task_id";

    public static Intent createIntent(Context context, int taskId) {
        Intent intent = new Intent(context, ScheduledCruiseReceiver.class);
        intent.setAction(ACTION_RUN_SCHEDULED_CRUISE);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        return intent;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int taskId = intent != null ? intent.getIntExtra(EXTRA_TASK_ID, -1) : -1;
        if (taskId == -1) {
            Log.w(TAG, "Scheduled cruise trigger ignored: missing task id");
            return;
        }

        if (isNavigationTaskRunning()) {
            Log.d(TAG, "Scheduled cruise skipped because another task is running: " + taskId);
            return;
        }

        Intent cruiseIntent = new Intent(context, CruiseActivity.class);
        cruiseIntent.putExtra(EXTRA_TASK_ID, taskId);
        cruiseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(cruiseIntent);
    }

    private boolean isNavigationTaskRunning() {
        try {
            MyApplication application = MyApplication.getInstance();
            if (application == null) {
                return false;
            }

            SharedViewModel sharedViewModel = application.getSharedViewModel();
            if (sharedViewModel == null) {
                return false;
            }

            sharedViewModel.initNavigationHandler();
            GeneralNavigationHandler navigationHandler = sharedViewModel.getNavigationHandler();
            return navigationHandler != null && navigationHandler.isTaskRunning();
        } catch (Exception e) {
            Log.e(TAG, "Failed to check navigation state, skipping scheduled cruise", e);
            return true;
        }
    }
}
