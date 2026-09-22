package com.yaonan.util;

import static com.yaonan.util.global.Global.TAG;

import android.util.Log;

import com.yaonan.App;
import com.yaonan.util.lang.TimeUtil;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 应用日志工具类。
 *
 * <p>双写：同时输出到 logcat 和本地日志文件（app私有目录 logs/app_log.txt），
 * 便于用户通过"分享"功能导出日志文件用于问题分析。</p>
 *
 * <p>文件超过 2MB 时自动清空重来（保留最新日志）。线程安全（synchronized），
 * 多进程（主进程 + 无障碍进程）均可写入。</p>
 */
public class LogHelper {

    /** 日志文件（app私有外部存储 logs/app_log.txt） */
    private static final File LOG_FILE = new File(App.getApp().getExternalFilesDir("logs"), "app_log.txt");

    /** 单个日志文件上限：超过后清空重写，保留最新日志 */
    private static final long MAX_SIZE = 2 * 1024 * 1024;

    /**
     * 获取日志文件（供分享/查看）。
     */
    public static File getLogFile() {
        return LOG_FILE;
    }

    /**
     * 清空日志文件。
     */
    public static synchronized void clear() {
        try {
            FileOutputStream fos = new FileOutputStream(LOG_FILE, false);
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 写入 DEBUG 级别日志（logcat + 文件）。
     */
    public static synchronized void d(String tag, String msg) {
        Log.d(tag, msg);
        write("D", tag, msg);
    }

    /**
     * 写入 INFO 级别日志（logcat + 文件）。
     */
    public static synchronized void i(String tag, String msg) {
        Log.i(tag, msg);
        write("I", tag, msg);
    }

    /**
     * 写入 ERROR 级别日志（logcat + 文件）。
     */
    public static synchronized void e(String tag, String msg) {
        Log.e(tag, msg);
        write("E", tag, msg);
    }

    /**
     * 写入一行日志到文件（带时间戳与级别）。
     */
    private static void write(String level, String tag, String msg) {
        try {
            // 超限清空，保留最新日志
            if (LOG_FILE.exists() && LOG_FILE.length() > MAX_SIZE) {
                clear();
            }
            String time = TimeUtil.format(System.currentTimeMillis(), "MM-dd HH:mm:ss");
            String line = time + " " + level + "/" + tag + ": " + msg + "\n";
            FileOutputStream fos = new FileOutputStream(LOG_FILE, true);
            fos.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {
            // 日志写入失败不影响主流程
        }
    }
}
