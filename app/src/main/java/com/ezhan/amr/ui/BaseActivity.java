// BaseActivity.java
package com.ezhan.amr.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.ezhan.amr.utils.LocaleHelper;

/**
 * 基础Activity类，提供语言环境管理功能
 *
 * 功能:
 * 1. 自动应用用户选择的语言设置
 * 2. 处理语言切换后的界面刷新
 * 3. 确保所有子Activity保持一致的语言设置行为
 */
public class BaseActivity extends AppCompatActivity {
    /** 用户最近一次屏幕交互时间（ms），0 表示从未交互过。供 WebService 闲时判断使用。 */
    private static volatile long lastUserInteractionMillis = 0L;

    /**
     * 返回用户最近一次屏幕交互时间，0 表示 app 启动后从未交互过。
     */
    public static long getLastUserInteractionMillis() {
        return lastUserInteractionMillis;
    }

    /**
     * 用户操作屏幕时刷新交互时间，用于推迟闲时充电/闲时回待命触发。
     */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastUserInteractionMillis = System.currentTimeMillis();
    }

    /**
     * 在附加上下文时设置语言环境
     *
     * 系统在创建Activity前调用此方法
     * 确保语言设置在Activity生命周期之前应用
     *
     * @param newBase 新的上下文对象
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        // 设置语言环境并返回新的上下文
        super.attachBaseContext(LocaleHelper.setLocale(newBase));
    }

    /**
     * 创建Activity时调用
     *
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 确保语言设置正确应用
        LocaleHelper.applyNewLocale(this, LocaleHelper.getLanguage(this));
    }

    /**
     * 语言设置变更时刷新界面
     *
     * @param newConfig 新的配置信息
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 语言变更时刷新当前Activity
        LocaleHelper.applyNewLocale(this, LocaleHelper.getLanguage(this));
        refreshUIForLanguageChange();
    }

    /**
     * 刷新界面以适应语言变更
     *
     * 基类中默认重新创建Activity
     * 子类可重写此方法实现自定义刷新逻辑
     */
    protected void refreshUIForLanguageChange() {
        // 默认行为：重新创建Activity以完全刷新UI
        recreate();
    }
}