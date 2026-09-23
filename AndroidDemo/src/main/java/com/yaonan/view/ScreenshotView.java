package com.yaonan.view;
import static com.yaonan.util.global.Global.TAG;

import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
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
import com.yaonan.util.FeishuHelper;
import com.yaonan.util.LogHelper;
import com.yaonan.util.codec.Codec;
import com.yaonan.util.exception.ExceptionUtil;
import com.yaonan.util.jna.UI;
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
 * 职责：作为无障碍服务承载的悬浮 UI，提供链接检测的 开始/停止 控制，支持拖动。
 * 点击「开始」：校验当前页面为"文件传输助手"聊天界面后，进入无限循环检测——
 * 逐条发送 TXT 中的链接，打开并扫描风险关键词，一轮完成后自动从第一条开始下一轮，永不停止；
 * 再次点击悬浮球：停止检测（悬浮球显示"开始"）。
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
     * 未运行时点击=开始；运行中点击=停止。
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
                        if (!mIsMoving) {
                            TextView textView = (TextView) v;
                            if (loopThread == null) {
                                startScript(textView);
                            } else {
                                // 运行中点击：停止脚本
                                LogHelper.e(TAG, "手动停止");
                                UI.invokeLater(() -> UI.alert("正在停止..."));
                                loopThread.interrupt();
                            }
                        }
                        break;
                }
                return true;
            }

            private boolean isMoving(MotionEvent event) {
                return Math.abs(event.getX() - mDownX) > MIN_MOVING_PIXELS || Math.abs(event.getY() - mDownY) > MIN_MOVING_PIXELS;
            }
        });
    }

    /**
     * 启动检测脚本（无限循环，再次点击悬浮球停止）。
     */
    private void startScript(TextView textView) {
        loopThread = ThreadUtil.async(() -> {
            try {
                SelectToSpeakService.isRunning = true;
                UI.invokeLater(() -> textView.setText("停止"));

                // 页面校验：必须处于"文件传输助手"聊天界面
                if (!checkFileHelperChat()) {
                    UI.invokeLater(() -> AlertHelper.showBanner(
                            "请先进入「文件传输助手」聊天界面，再点击开始", true));
                    return;
                }
                UI.invokeLater(() -> UI.alert("已开始"));
                runLinkCheck(textView);
            } catch (InterruptedException e) {
                // 手动停止
                UI.invokeLater(() -> UI.alert("已停止"));
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("interrupt")) {
                    // ThreadUtil.sleep 中断包装出的异常，视为手动停止
                    UI.invokeLater(() -> UI.alert("已停止"));
                } else {
                    UI.invokeLater(() -> UI.alert(e));
                }
            } finally {
                SelectToSpeakService.isRunning = false;
                loopThread = null;
                UI.invokeLater(() -> textView.setText("开始"));
            }
        });
    }

    /**
     * 校验当前页面是否为"文件传输助手"聊天界面（无障碍服务 OCR 识别标题区域）。
     *
     * @return true 表示处于文件传输助手聊天界面
     * @throws InterruptedException 等待过程中被中断
     */
    private boolean checkFileHelperChat() throws InterruptedException {
        String res = cmdWait("#@#检查传输助手#", 15);
        return "yes".equals(res);
    }

    /**
     * 读取步骤间隔（毫秒）：每个检测步骤之间的等待时长，
     * 由首页"检测设置-步骤间隔"配置（秒），范围1~60，默认3。
     */
    private long stepMs() {
        int sec;
        try {
            sec = UI.getMMKV().decodeInt(
                    com.yaonan.util.global.Global.KEY_STEP_INTERVAL_SEC,
                    com.yaonan.util.global.Global.DEFAULT_STEP_INTERVAL_SEC);
        } catch (Exception e) {
            sec = com.yaonan.util.global.Global.DEFAULT_STEP_INTERVAL_SEC;
        }
        if (sec < com.yaonan.util.global.Global.MIN_STEP_INTERVAL_SEC
                || sec > com.yaonan.util.global.Global.MAX_STEP_INTERVAL_SEC) {
            sec = com.yaonan.util.global.Global.DEFAULT_STEP_INTERVAL_SEC;
        }
        return sec * 1000L;
    }

    /**
     * 链接检测主流程：无限循环，逐条发送 TXT 中的链接并检查风险关键词，
     * 一轮完成后自动从第一条开始下一轮，永不停止（直到用户点击悬浮球停止）。
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

        appendResult("===== 检测任务开始 " + TimeUtil.nowTime() + "，共" + links.size()
                + "条，无限循环模式 =====");

        int round = 1;
        // 死循环：一轮完成后自动开启下一轮
        while (true) {
            int riskCount = 0;
            appendResult("----- 第" + round + "轮开始 " + TimeUtil.nowTime() + " -----");

            for (int i = 0; i < links.size(); i++) {
                String link = links.get(i);
                int index = i + 1;
                int total = links.size();
                UI.invokeLater(() -> textView.setText(index + "/" + total));
                LogHelper.e(TAG, "===== [" + index + "/" + total + "] " + link);

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

                // 3、等待消息出现在聊天列表（步骤间隔）
                ThreadUtil.sleep(stepMs());

                // 4、点击链接并扫描风险关键词（同步等待结果）
                String checkRes = cmdWait("#@#检查链接#" + link, 40);
                if (checkRes.startsWith("risk")) {
                    riskCount++;
                    String keyword = checkRes.substring("risk:".length());
                    appendResult("[风险-" + keyword + "] " + link);
                    // 后台时Toast被系统限制，改用悬浮横幅醒目提醒
                    UI.invokeLater(() -> AlertHelper.showBanner(
                            "⚠️ 发现风险链接 " + index + "/" + total
                                    + "\n关键词：" + keyword
                                    + "\n" + link, true));
                    // 推送通知到飞书群聊（配置了机器人地址时），消息末尾@所有人
                    String webhook = UI.getMMKV().getString(
                            com.yaonan.util.global.Global.KEY_FEISHU_WEBHOOK, "");
                    if (StringUtil.isNotEmpty(webhook)) {
                        String msg = "⚠️ 链接检测发现风险链接(" + index + "/" + total + ")"
                                + "\n关键词：" + keyword
                                + "\n链接：" + link
                                + "\n时间：" + TimeUtil.nowTime()
                                + "\n<at user_id=\"all\">所有人</at>";
                        ThreadUtil.async(() -> {
                            boolean ok = FeishuHelper.sendText(webhook, msg);
                            LogHelper.i(TAG, "飞书风险推送: " + (ok ? "成功" : "失败"));
                        });
                    }
                } else if ("normal".equals(checkRes)) {
                    appendResult("[正常] " + link);
                } else {
                    appendResult("[异常-" + checkRes + "] " + link);
                }

                // 5、仅当打开过网页时才需要从网页返回聊天界面
                //    nofind/notopen 表示未发生页面跳转，此时按返回会退出聊天会话，导致后续链接失败
                boolean pageOpened = checkRes.startsWith("risk")
                        || "normal".equals(checkRes)
                        || "error".equals(checkRes);
                if (pageOpened) {
                    cmd("#@#action#back");
                    ThreadUtil.sleep(stepMs());
                } else {
                    LogHelper.e(TAG, "未发生页面跳转(" + checkRes + ")，无需返回");
                }
            }

            // 一轮结束：记录并自动开启下一轮
            appendResult("----- 第" + round + "轮结束 " + TimeUtil.nowTime()
                    + "，风险" + riskCount + "/" + links.size() + " -----");
            int roundNo = round;
            int totalNo = links.size();
            int riskNo = riskCount;
            UI.invokeLater(() -> AlertHelper.showBanner(
                    "第" + roundNo + "轮检测完成：共" + totalNo + "条，风险" + riskNo + "条\n即将开始下一轮...",
                    false));
            LogHelper.e(TAG, "===== 第" + roundNo + "轮结束，风险" + riskNo + "/" + totalNo
                    + "，自动开启第" + (roundNo + 1) + "轮 =====");
            round++;
            ThreadUtil.sleep(stepMs());
        }
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
