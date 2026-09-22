package com.ezhan.amr.utils;

import android.content.Context;

import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.Position;

public final class PositionDisplayNameHelper {
    private PositionDisplayNameHelper() {
    }

    public static String getDisplayName(Context context, Position position) {
        if (context == null || position == null) {
            return "";
        }
        switch (position.getType()) {
            case 10:
                return LocaleHelper.onServiceGetString(context, R.string.charge_point);
            case 11:
                return LocaleHelper.onServiceGetString(context, R.string.precharge_point);
            case 12:
                return LocaleHelper.onServiceGetString(context, R.string.park_point);
            case 13:
                return LocaleHelper.onServiceGetString(context, R.string.relocalize_point);
            default:
                String name = position.getName();
                return name != null ? name : "";
        }
    }
}
