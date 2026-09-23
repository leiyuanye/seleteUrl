package com.yaonan.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.yaonan.App;

/**
 * 悬浮横幅提醒工具。
 *
 * 应用在后台（如微信前台）时 Toast 被系统限制无法显示，
 * 改用 WindowManager 悬浮横幅（TYPE_APPLICATION_OVERLAY）实现醒目提醒，
 * 不受前台/焦点限制。横幅显示在屏幕顶部，点击可关闭，超时自动消失。
 */
public class AlertHelper {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 显示顶部悬浮横幅提醒。
     *
     * @param message 提醒内容
     * @param isLong  true显示6秒，false显示3.5秒
     */
    public static void showBanner(String message, boolean isLong) {
        MAIN.post(() -> {
            try {
                Context ctx = App.getApp();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                TextView banner = buildBanner(message);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.y = 120;
                wm.addView(banner, params);

                long duration = isLong ? 6000L : 3500L;
                MAIN.postDelayed(() -> {
                    try {
                        wm.removeView(banner);
                    } catch (Exception ignored) {
                    }
                }, duration);
            } catch (Exception ignored) {
                // 悬浮窗权限异常等，静默失败
            }
        });
    }

    /**
     * 构建横幅视图：红色圆角背景白字，点击关闭。
     */
    private static TextView buildBanner(String message) {
        Context ctx = App.getApp();
        TextView banner = new TextView(ctx);
        banner.setText(message);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(13);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(36, 28, 36, 28);
        banner.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#D32F2F"));
        background.setCornerRadius(24);
        banner.setBackground(background);

        // 点击关闭
        banner.setOnClickListener(v -> {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        });
        return banner;
    }
}