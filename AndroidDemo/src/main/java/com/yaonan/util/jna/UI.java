package com.yaonan.util.jna;

import static com.yaonan.util.global.Global.TAG;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.tencent.mmkv.MMKV;
import com.yaonan.App;
import com.yaonan.util.exception.ExceptionUtil;
import com.yaonan.util.lang.ThreadUtil;

/**
 * UI 工具类。
 *
 * <p>提供主线程任务调度、Toast 提示、MMKV 获取、应用启动等界面与系统交互能力。</p>
 */
public class UI {

    /** 主线程 Handler，用于将任务投递到主线程执行。 */
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 当前显示的 Toast，用于在显示新 Toast 前取消旧的。 */
    private static Toast toast;

    /**
     * 将任务投递到主线程执行（任务异常时弹出错误提示）。
     *
     * @param doRun 待执行任务
     */
    public static void invokeLater(Runnable doRun) {
        MAIN.post(() -> {
            try {
                doRun.run();
            } catch (Exception e) {
                alert(e);
            }
        });
    }

    /**
     * 弹出短时 Toast 提示。
     *
     * @param message 提示内容
     */
    public static void alert(String message) {
        _toast(message, false);
    }

    /**
     * 弹出 Toast 提示（可指定长短）。
     *
     * @param message 提示内容
     * @param isLong  是否长时显示
     */
    public static void alert(String message, boolean isLong) {
        _toast(message, isLong);
    }

    /**
     * 弹出异常信息 Toast（长时显示）。
     *
     * @param e 异常对象
     */
    public static void alert(Exception e) {
        String message = ExceptionUtil.getStackTrace(e);
        _toast(message, true);
    }

    /**
     * 在指定上下文中弹出 Toast 提示。
     *
     * @param message 提示内容
     * @param context 上下文
     */
    public static void alert(String message, Context context) {
        _toast(message, false, context);
    }

    /**
     * 使用应用全局上下文弹出 Toast。
     *
     * @param message 提示内容
     * @param isLong  是否长时显示
     */
    private static void _toast(String message, boolean isLong) {
        _toast(message, isLong, App.getApp());
    }

    /**
     * 在指定上下文中弹出 Toast（主线程执行）。
     *
     * @param message 提示内容
     * @param isLong  是否长时显示
     * @param context 上下文
     */
    private static void _toast(String message, boolean isLong, Context context) {
        MAIN.post(() -> {
            try {
                if (toast != null) toast.cancel();
                toast = Toast.makeText(context, message, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                toast.show();
            } catch (Exception e) {
                Log.e(TAG, "toast error", e);
            }
        });
    }

    /**
     * 获取全局 MMKV 实例。
     *
     * @return 默认 MMKV 实例
     */
    public static MMKV getMMKV() {
        return MMKV.defaultMMKV();
    }

    /**
     * 根据包名启动目标应用（未安装时忽略）。
     *
     * @param packageName 目标应用包名
     */
    public static void launchApp(String packageName) {
        Intent intent = App.getApp().getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            App.getApp().startActivity(intent);
        }
    }
}
