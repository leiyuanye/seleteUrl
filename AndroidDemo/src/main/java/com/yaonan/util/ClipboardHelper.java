package com.yaonan.util;

import static com.yaonan.util.global.Global.TAG;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import com.yaonan.App;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 剪贴板工具类。
 *
 * <p>Android 10+ 限制后台应用写剪贴板（仅默认输入法或持有窗口焦点的应用可写），
 * 本工具通过「1x1 像素可聚焦透明悬浮窗瞬间夺取窗口焦点 → 写入剪贴板 → 读回验证 → 移除悬浮窗」
 * 的方式绕过后台写入限制。悬浮窗位于屏幕外且不拦截触摸，整个过程约 200ms，用户无感知。</p>
 *
 * <p>使用方式（在任意后台线程调用，内部会阻塞等待写入完成）：</p>
 * <pre>     boolean ok = ClipboardHelper.setString("https://example.com");</pre>
 */
public class ClipboardHelper {

    /**
     * 写入剪贴板（绕过后台写入限制）。
     *
     * @param text 待写入文本
     * @return 是否写入并验证成功
     */
    public static boolean setString(String text) {
        try {
            Context ctx = App.getApp();
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean ok = new AtomicBoolean(false);
            Handler main = new Handler(Looper.getMainLooper());

            main.post(() -> {
                View view = new View(ctx);
                try {
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams();
                    params.width = 1;
                    params.height = 1;
                    params.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    // 不带 FLAG_NOT_FOCUSABLE（需要夺取窗口焦点才能写剪贴板），
                    // FLAG_NOT_TOUCH_MODAL + 屏幕外坐标保证不拦截、不遮挡用户操作
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                    params.format = PixelFormat.TRANSLUCENT;
                    params.x = -100;
                    params.y = -100;

                    wm.addView(view, params);
                } catch (Exception e) {
                LogHelper.e(TAG, "剪贴板焦点悬浮窗创建失败: " + e.getMessage());
                latch.countDown();
                return;
                }

                // 等待窗口焦点生效后写入
                view.postDelayed(() -> {
                    try {
                        cm.setPrimaryClip(ClipData.newPlainText("text", text));
                        // 此刻仍持有焦点，可以读回验证
                        ClipData cur = cm.getPrimaryClip();
                        if (cur != null && cur.getItemCount() > 0) {
                            ok.set(text.equals(cur.getItemAt(0).getText() + ""));
                        }
                        LogHelper.d(TAG, "剪贴板写入" + (ok.get() ? "成功" : "失败(读回不一致)"));
                    } catch (Exception e) {
                        LogHelper.e(TAG, "剪贴板写入异常: " + e.getMessage());
                    } finally {
                        try {
                            wm.removeView(view);
                        } catch (Exception ignored) {
                        }
                        latch.countDown();
                    }
                }, 120);
            });

            latch.await(3, TimeUnit.SECONDS);
            return ok.get();
        } catch (Exception e) {
            return false;
        }
    }
}
