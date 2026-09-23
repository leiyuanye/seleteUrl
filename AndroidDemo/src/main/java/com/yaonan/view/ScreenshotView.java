package com.yaonan.view;
import static com.yaonan.util.global.Global.TAG;

import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;
import com.tencent.mmkv.MMKV;
import com.yaonan.App;
import com.yaonan.R;
import com.yaonan.util.AlertHelper;
import com.yaonan.util.ClipboardHelper;
import com.yaonan.util.codec.Codec;
import com.yaonan.util.exception.ExceptionUtil;
import com.yaonan.util.jna.UI;
import com.yaonan.util.LogHelper;
import com.yaonan.util.lang.StringUtil;
import com.yaonan.util.lang.ThreadUtil;
import com.yaonan.util.lang.TimeUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮控制视图（悬浮窗内容）。
 *
 * <p>职责：作为无障碍服务承载的悬浮 UI，提供"开始/停止"链接检测脚本开关，支持拖动。
 * 脚本流程：逐个读取 TXT 中的链接 → 在当前微信聊天界面发送 → 点击打开该链接 →
 * 扫描页面风险关键词（诱导分享/长按网址等）→ 记录结果并返回聊天 → 逐个处理直到结束。</p>
 *
 * <p>使用方式：在主页选择 TXT 文件 → 用户手动打开微信并进入聊天界面 → 点击悬浮球"开始"。</p>
 */
public class ScreenshotView extends FrameLayout {

    /** 执行脚本的后台循环线程（null 表示当前未在运行） */
    public static Thread loopThread = null;

    /** 布局（拖动位置）变化监听器，用于将拖动结果回传给宿主 */
    @Nullable
    private ILayoutListener mListener;

    /** 代码中直接创建视图时使用 */
    public ScreenshotView(@NonNull Context context) {
        super(context);
        init();
    }

    /** 从 XML 布局解析（无样式属性）时使用 */
    public ScreenshotView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /** 从 XML 布局解析（带样式属性）时使用 */
    public ScreenshotView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 初始化视图：加载布局、绑定悬浮球的拖动与点击事件。
     */
    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.layout_screenshot_view, this);
        findViewById(R.id.tv_screenshot).setOnTouchListener(new OnTouchListener() {
            /** 手指按下时的 X 坐标，用于计算拖动距离 */
            private float mDownX = 0F;
            /** 手指按下时的 Y 坐标，用于计算拖动距离 */
            private float mDownY = 0F;
            /** 是否已判定为拖动（而非点击） */
            private boolean mIsMoving = false;
            /** 判定为拖动所需的最小移动像素阈值 */
            private final int MIN_MOVING_PIXELS = getResources().getDimensionPixelSize(R.dimen.min_moving_pixels);
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mDownX = event.getX();
                        mDownY = event.getY();
                        mIsMoving = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        mIsMoving = mIsMoving || isMoving(event);
                        if (mIsMoving) {
                            v.setPressed(false);
                            if (mListener != null) {
                                int x = (int) (event.getRawX() - mDownX);
                                int y = (int) (event.getRawY() - mDownY);
                                mListener.onLayout(x, y);
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        // 未发生拖动时视为点击：未运行则启动脚本，运行中则停止脚本
                        if (!mIsMoving) {
                            TextView textView = (TextView) v;
                            if (loopThread == null) {
                                loopThread = ThreadUtil.async(() -> {
                                    try {
                                        SelectToSpeakService.isRunning = true;
                                        UI.invokeLater(() -> {
                                            UI.alert("已开始");
                                        });
                                        UI.invokeLater(() -> {
                                            textView.setText("停止");
                                        });
                                        runLinkCheck(textView);
                                        UI.invokeLater(() -> {
                                            UI.alert("已全部完成");
                                        });
                                    } catch (InterruptedException e) {
                                        // 手动停止
                                        UI.invokeLater(() -> {
                                            UI.alert("已停止");
                                        });
                                    } catch (Exception e) {
                                        UI.invokeLater(() -> {
                                            UI.alert(e);
                                        });
                                    } finally {
                                        SelectToSpeakService.isRunning = false;
                                        loopThread = null;
                                        UI.invokeLater(() -> {
                                            textView.setText("开始");
                                        });
                                    }
                                });
                            } else {
                                // 暂停脚本
                                LogHelper.e(TAG, "手动停止");
                                loopThread.interrupt();
                            }
                        }
                }
                return true;
            }

            private boolean isMoving(MotionEvent event) {
                return Math.abs(event.getX() - mDownX) > MIN_MOVING_PIXELS || Math.abs(event.getY() - mDownY) > MIN_MOVING_PIXELS;
            }
        });
    }

    /**
     * 链接检测主流程：逐个读取 TXT 中的链接并发送到当前微信聊天，
     * 点击打开链接扫描风险关键词，记录结果后返回聊天，继续处理下一条。
     *
     * @param textView 悬浮球文本控件，用于显示进度
     */
    private void runLinkCheck(TextView textView) throws InterruptedException {
        // 读取TXT链接列表
        String uriStr = UI.getMMKV().getString("links_uri", "");
        List<String> links = StringUtil.isEmpty(uriStr) ? new ArrayList<>() : readLinks(Uri.parse(uriStr));
        if (links.isEmpty()) {
            UI.invokeLater(() -> UI.alert("未读取到链接，请先在主页选择TXT文件（每行一条链接）"));
            return;
        }

        // 写入本次检测结果表头（文件为追加模式，历史结果按表头分块）
        appendResult("===== 检测开始 " + TimeUtil.nowTime() + "，共" + links.size() + "条 =====");

        int riskCount = 0;
        for (int i = 0; i < links.size(); i++) {
            if (ThreadUtil.isInterrupted()) {
                throw new InterruptedException();
            }
            String link = links.get(i);
            int index = i + 1;
            UI.invokeLater(() -> textView.setText(index + "/" + links.size()));
            LogHelper.e(TAG, "===== [" + index + "/" + links.size() + "] " + link);

            // 1、写入剪贴板（无障碍服务在微信内通过粘贴输入，粘贴会触发"发送"按钮显示）
            boolean clipOk = ClipboardHelper.setString(link);
            if (!clipOk) {
                LogHelper.e(TAG, "剪贴板写入失败，服务端将退回SET_TEXT方式");
            }

            // 2、发送链接（同步等待结果，三级输入+重试耗时较长，放宽到30s）
            String sendRes = cmdWait("#@#发送链接#" + link, 30);
            if (!"success".equals(sendRes)) {
                LogHelper.e(TAG, "发送失败: " + sendRes);
                appendResult("[发送失败][" + sendRes + "] " + link);
                // 仍处于聊天界面，无需返回（按返回会退出会话导致后续链接失败）
                continue;
            }

            // 2、等待消息出现在聊天列表（老仓库H5监控在发送后等3s，这里2s折中）
            ThreadUtil.sleep(2000);

            // 3、点击链接并扫描风险关键词（同步等待结果）
            String checkRes = cmdWait("#@#检查链接#" + link, 40);
            if (checkRes.startsWith("risk")) {
                riskCount++;
                String keyword = checkRes.substring("risk:".length());
                appendResult("[风险-" + keyword + "] " + link);
                // 后台时Toast被系统限制，改用悬浮横幅醒目提醒
                int total = links.size();
                UI.invokeLater(() -> AlertHelper.showBanner(
                        "⚠️ 发现风险链接 " + index + "/" + total
                                + "\n关键词：" + keyword
                                + "\n" + link, true));
            } else if ("normal".equals(checkRes)) {
                appendResult("[正常] " + link);
            } else {
                appendResult("[异常-" + checkRes + "] " + link);
            }

            // 4、仅当打开过网页时才需要从网页返回聊天界面
            //    nofind/notopen 表示未发生页面跳转，此时按返回会退出聊天会话，导致后续链接失败
            boolean pageOpened = checkRes.startsWith("risk")
                    || "normal".equals(checkRes)
                    || "error".equals(checkRes);
            if (pageOpened) {
                ThreadUtil.sleep(500);
                cmd("#@#action#back");
                ThreadUtil.sleep(2000);
            } else {
                LogHelper.e(TAG, "未发生页面跳转(" + checkRes + ")，无需返回");
            }
        }

        String summary = "检测完成：共" + links.size() + "条，风险" + riskCount + "条\n结果已保存到 check_result.txt";
        UI.invokeLater(() -> AlertHelper.showBanner(summary, true));
        appendResult("===== 检测结束 " + TimeUtil.nowTime() + "，风险" + riskCount + "/" + links.size() + " =====");
    }

    /**
     * 读取 TXT 文件中的链接（每行一条，仅保留 http/https 开头的行，自动去除首尾空白）。
     *
     * @param uri TXT 文件的 content:// Uri
     * @return 链接列表
     */
    public static List<String> readLinks(Uri uri) {
        List<String> links = new ArrayList<>();
        try (InputStream is = App.getApp().getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return links;
            }
            byte[] bytes = new byte[is.available()];
            int read = is.read(bytes);
            if (read <= 0) {
                return links;
            }
            String content = new String(bytes, 0, read, StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    links.add(trimmed);
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "读取TXT失败");
            ExceptionUtil.getStackTrace(e);
        }
        return links;
    }

    /**
     * 追加一条检测结果到应用私有外部存储的 check_result.txt（UTF-8）。
     *
     * @param line 结果行
     */
    private void appendResult(String line) {
        try {
            File file = new File(App.getApp().getExternalFilesDir(null), "check_result.txt");
            FileOutputStream fos = new FileOutputStream(file, true);
            fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            fos.close();
            LogHelper.d(TAG, "结果: " + line);
        } catch (Exception e) {
            LogHelper.e(TAG, "写入结果失败");
            ExceptionUtil.getStackTrace(e);
        }
    }

    /**
     * 设置布局（拖动）监听器，供宿主注册以接收拖动位移。
     */
    public void setLayoutListener(ILayoutListener listener) {
        mListener = listener;
    }

    /**
     * 布局拖动监听接口。
     */
    public interface ILayoutListener {
        void onLayout(int x, int y);
    }

    /* ****************************************** 命令 *********************************************/

    /**
     * 点击、滑动等操作
     * 异步执行
     * @param str 如"#@#tap#320,2185"
     */
    public void cmd(String str) {
        ScreenshotView.this.announceForAccessibility(str);
    }

    /**
     * 点击、滑动等操作
     * 同步执行，最多等待 maxTimes 秒
     * @param str 如"#@#tap#320,2185"
     * @param maxTimes 等待多少次后继续执行，防止缓存更新失败等导致的死循环
     * @return 执行结果（msgid 对应的应答），超时返回 "timeout"
     * @throws InterruptedException
     */
    public String cmdWait(String str, int maxTimes) throws InterruptedException {
        String msgid = Codec.uuid();

        {
            MMKV kv = UI.getMMKV();
            kv.putString(msgid, "", 3600);
            LogHelper.d(TAG, "cmdWait " + msgid + "<-");

            ScreenshotView.this.announceForAccessibility(msgid + "{msgid}" + str);
        }

        for (int i = 0; i < maxTimes; i++) {
            Thread.sleep(1000);

            MMKV kv = UI.getMMKV();
            String res = kv.getString(msgid, "");
            LogHelper.d(TAG, "cmdWait " + msgid + "<-" + res);
            if (StringUtil.isNotEmpty(res)) {
                kv.remove(msgid);
                return res;
            }
        }

        LogHelper.d(TAG, "cmdWait " + msgid + "<-timeout");
        return "timeout";
    }
}
