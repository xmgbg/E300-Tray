package com.ezhan.amr.data.datatype;

import android.content.Context;
import com.ezhan.amr.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    private static final Map<Integer, Integer> errorCodeResMap;

    static {
        Map<Integer, Integer> map = new HashMap<>();
        // 使用资源ID映射错误码
        map.put(10000, R.string.error_code_10000);
        map.put(10001, R.string.error_code_10001);
        map.put(10002, R.string.error_code_10002);
        map.put(10003, R.string.error_code_10003);
        map.put(10010, R.string.error_code_10010);
        map.put(10020, R.string.error_code_10020);
        map.put(10030, R.string.error_code_10030);
        map.put(10040, R.string.error_code_10040);
        map.put(10050, R.string.error_code_10050);
        map.put(10051, R.string.error_code_10051);
        map.put(10052, R.string.error_code_10052);
        map.put(10060, R.string.error_code_10060);
        map.put(10070, R.string.error_code_10070);
        map.put(10080, R.string.error_code_10080);
        map.put(10090, R.string.error_code_10090);
        map.put(10100, R.string.error_code_10100);
        map.put(10110, R.string.error_code_10110);
        map.put(10111, R.string.error_code_10111);
        map.put(10112, R.string.error_code_10112);
        map.put(10113, R.string.error_code_10113);
        map.put(10130, R.string.error_code_10130);
        map.put(10131, R.string.error_code_10131);
        map.put(20000, R.string.error_code_20000);
        map.put(20001, R.string.error_code_20001);
        map.put(20002, R.string.error_code_20002);
        map.put(20003, R.string.error_code_20003);
        map.put(20004, R.string.error_code_20004);
        map.put(20005, R.string.error_code_20005);
        map.put(20006, R.string.error_code_20006);
        map.put(20007, R.string.error_code_20007);
        map.put(20008, R.string.error_code_20008);
        map.put(20009, R.string.error_code_20009);
        map.put(20010, R.string.error_code_20010);
        map.put(20011, R.string.error_code_20011);
        map.put(20100, R.string.error_code_20100);
        map.put(20101, R.string.error_code_20101);
        map.put(20102, R.string.error_code_20102);
        map.put(20103, R.string.error_code_20103);
        map.put(20104, R.string.error_code_20104);
        map.put(20105, R.string.error_code_20105);
        map.put(20106, R.string.error_code_20106);
        map.put(20107, R.string.error_code_20107);
        map.put(20108, R.string.error_code_20108);
        map.put(20109, R.string.error_code_20109);
        map.put(20200, R.string.error_code_20200);
        map.put(20201, R.string.error_code_20201);
        map.put(60001, R.string.error_code_60001);
        map.put(60002, R.string.error_code_60002);
        map.put(60003, R.string.error_code_60003);
        map.put(60004, R.string.error_code_60004);
        map.put(60005, R.string.error_code_60005);
        map.put(60006, R.string.error_code_60006);
        map.put(60007, R.string.error_code_60007);
        map.put(60008, R.string.error_code_60008);
        map.put(60009, R.string.error_code_60009);
        map.put(60010, R.string.error_code_60010);
        map.put(60011, R.string.error_code_60011);
        map.put(60012, R.string.error_code_60012);
        map.put(60013, R.string.error_code_60013);
        map.put(60014, R.string.error_code_60014);
        map.put(60015, R.string.error_code_60015);
        map.put(60105, R.string.error_code_60105);
        map.put(60200, R.string.error_code_60200);
        map.put(60301, R.string.error_code_60301);

        errorCodeResMap = Collections.unmodifiableMap(map);
    }

    // 新增：带上下文的获取方法（推荐）
    public static String getMessage(Context context, int code) {
        Integer resId = errorCodeResMap.get(code);
        if (resId != null) {
            return context.getString(resId);
        }
        return context.getString(R.string.unknown_error);
    }

    // 兼容旧方法（标记为废弃）
    @Deprecated
    public static String getMessage(int code) {
        throw new UnsupportedOperationException("Use getMessage(Context, int) instead");
    }

    public static boolean containsCode(int code) {
        return errorCodeResMap.containsKey(code);
    }

    // 返回资源ID映射（用于高级场景）
    public static Map<Integer, Integer> getAllErrorCodeResIds() {
        return errorCodeResMap;
    }
}
