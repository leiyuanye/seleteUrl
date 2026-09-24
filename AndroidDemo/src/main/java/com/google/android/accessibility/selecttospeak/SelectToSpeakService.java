package com.google.android.accessibility.selecttospeak;

import static com.yaonan.util.global.Global.TAG;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.tencent.mmkv.MMKV;
import com.yaonan.util.codec.Codec;
import com.yaonan.util.exception.ExceptionUtil;
import com.yaonan.util.global.Global;
import com.yaonan.util.WindowHelper;
import com.yaonan.util.jna.UI;
import com.yaonan.util.LogHelper;
import com.yaonan.util.lang.StringUtil;
import com.yaonan.util.lang.ThreadUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无障碍服务实现类（注册为 SelectToSpeak，实际作为自动化脚本执行器使用）。
 * 职责：以后台无障碍服务的形式常驻运行，监听系统无障碍事件（TYPE_ANNOUNCEMENT），
 * 解析控制端注入的 "#@#" 指令，驱动微信自动完成"发送链接 → 打开链接 → 检测风险页"流程，
 * 并将执行结果通过 MMKV 回传给控制端。
 *
 * 链接检测流程相关命令：
 * - #@#发送链接#&lt;url&gt;   同步：将 url 填入微信聊天输入框并点击"发送"，应答 success/error
 * - #@#检查链接#&lt;url&gt;   同步：点击聊天中该 url 消息，等待页面加载后扫描风险关键词，
 *                       应答 normal / risk:&lt;关键词&gt; / nofind
 * - #@#action#back      异步：返回键，用于从网页关闭回到聊天界面
 */
public class SelectToSpeakService extends AccessibilityService {

    /** 服务运行状态标志：true 表示当前无障碍服务已连接并处于运行状态，供其他线程判断服务是否可用 */
    public static volatile boolean isRunning = false;

    /** 风险页关键词：从 MMKV 读取用户自定义关键词，未配置时使用 Global.DEFAULT_RISK_KEYWORDS */

    /** 截屏回调执行器（takeScreenshot 要求提供 Executor，且回调不能在主线程死等） */
    private static final ExecutorService SCREENSHOT_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 服务连接成功回调：无障碍服务启动后由系统调用，当前仅调用父类默认实现，预留服务启动后的初始化扩展点。
     */
    @Override
    protected void onServiceConnected() {
        //LogHelper.e(TAG, "无障碍服务启动");
        super.onServiceConnected();
    }

    /**
     * 服务被系统中断回调：当无障碍服务被系统关闭或重启时调用，当前未做任何处理。
     */
    @Override
    public void onInterrupt() {

    }

    // 无障碍事件回调入口：捕获 TYPE_ANNOUNCEMENT 事件中携带的 "#@#" 指令，分发到下方对应的脚本操作分支执行
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            int eventType = event.getEventType();

            // 仅处理 TYPE_ANNOUNCEMENT 事件且文本恰好一条的情况：控制端通过无障碍播报事件注入 "#@#" 指令
            if (event.getText().size() == 1 && eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                String cmd = event.getText().get(0) == null ? null : event.getText().get(0).toString();

                // 同步操作id
                String msgid = "";
                if (cmd != null && cmd.contains("{msgid}")) {
                    String[] cmds = StringUtil.split(cmd, "{msgid}");
                    msgid = cmds[0];
                    cmd = cmds[1];
                }
                // lambda 中引用需要 effectively final
                final String fMsgid = msgid;

                if (cmd != null && cmd.startsWith("#@#")) {
                    LogHelper.e(TAG, cmd);
                    if ("#@#debug#".equals(cmd)) {
                        // 调试查找view
                        debugRun();

                    } else if (cmd.startsWith("#@#tap#")) { // #@#tap#320,2185
                        // 点击坐标
                        String[] xy = cmd.substring("#@#tap#".length()).split(",");
                        Tap(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));

                    } else if (cmd.startsWith("#@#longtap#")) { // #@#longtap#320,2185
                        // 长按坐标
                        String[] xy = cmd.substring("#@#longtap#".length()).split(",");
                        LongTap(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));

                    } else if ("#@#action#home".equals(cmd)) {
                        // 模拟全局操作，按home键
                        performGlobalAction(GLOBAL_ACTION_HOME);

                    } else if ("#@#action#back".equals(cmd)) {
                        // 模拟全局操作，按back键（用于从网页返回聊天界面）
                        performGlobalAction(GLOBAL_ACTION_BACK);

                    } else if (cmd.startsWith("#@#发送链接#")) { // 发送链接
                        // 将链接填入聊天输入框并点击"发送"，结果通过 msgid 同步回传
                        // 内部含截屏OCR兜底点击与多次等待，放后台线程执行，避免阻塞主线程
                        String url = cmd.substring("#@#发送链接#".length());
                        ThreadUtil.async(() -> sendLink(url, fMsgid));

                    } else if (cmd.startsWith("#@#检查链接#")) { // 检查链接
                        // 点击刚发送的链接消息，等待页面加载后扫描风险关键词，结果通过 msgid 同步回传
                        String url = cmd.substring("#@#检查链接#".length());
                        ThreadUtil.async(() -> checkLink(url, fMsgid));

                    } else if ("#@#检查传输助手#".equals(cmd)) { // 页面校验
                        // 双重校验：1处于某个聊天界面（存在输入框节点）+ 2 OCR标题区域包含"文件传输助手"
                        // （仅OCR标题会在微信主列表恰好第一条是文件传输助手时误判通过）
                        ThreadUtil.async(() -> {
                            boolean ok = findChatEditText() != null;
                            if (ok) {
                                Text vt = ocrCaptureText();
                                if (vt != null) {
                                    int titleBottom = (int) (WindowHelper.getRealMetrics().heightPixels * 0.10f);
                                    boolean titleMatch = false;
                                    outer:
                                    for (Text.TextBlock block : vt.getTextBlocks()) {
                                        for (Text.Line line : block.getLines()) {
                                            Rect box = line.getBoundingBox();
                                            if (box == null || box.top > titleBottom) {
                                                continue;
                                            }
                                            if (normalizeText(line.getText()).contains("文件传输助手")) {
                                                titleMatch = true;
                                                break outer;
                                            }
                                        }
                                    }
                                    ok = titleMatch;
                                } else {
                                    // OCR失败时退化为仅校验处于聊天界面
                                    LogHelper.e(TAG, "页面校验: OCR失败，仅校验聊天界面");
                                }
                            }
                            LogHelper.e(TAG, "页面校验(文件传输助手): " + ok);
                            response(fMsgid, ok ? "yes" : "no");
                        });

                    }
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "无障碍回调异常");
            ExceptionUtil.getStackTrace(e);
        }
    }

    /* ****************************************** 链接检测 *********************************************/

    /**
     * 发送链接：将 url 填入微信聊天输入框（ACTION_SET_TEXT），再点击"发送"按钮。
     *
     * @param url   待发送的链接
     * @param msgid 同步应答id
     */
    private void sendLink(String url, String msgid) {
        try {
            // 1、定位输入框（不在聊天界面时回复nochat，主循环会提醒用户并等待）
            AccessibilityNodeInfo editNode = findChatEditText();
            if (editNode == null) {
                LogHelper.e(TAG, "发送链接失败: 不在聊天界面(未找到输入框)");
                response(msgid, "nochat");
                return;
            }

            // 2、清空输入框残留：上一条链接若发送失败，残留文本会与本条粘贴拼接发送
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
            ThreadUtil.sleep(400);

                        // 3、方式一（老仓库验证过的方案）：SET_TEXT直填。
            //    全程不聚焦不粘贴不弹键盘，活动窗口保持为微信本体，
            //    "发送"按钮可通过节点搜索+ACTION_CLICK直接点击，不受输入法窗口干扰。
            //    填入后等待2秒让微信渲染"发送"按钮，两轮节点点击+闭环校验
            editNode = findChatEditText();
            if (editNode == null) {
                LogHelper.e(TAG, "发送链接失败: 清空后输入框丢失");
                response(msgid, "nochat");
                return;
            }
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url);
            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            LogHelper.e(TAG, "SET_TEXT填入，等待发送按钮渲染...");
            ThreadUtil.sleep(2000);
            for (int attempt = 1; attempt <= 2; attempt++) {
                clickSendButton();
                ThreadUtil.sleep(1500);
                if (isInputCleared(url)) {
                    LogHelper.e(TAG, "发送成功-节点点击(第" + attempt + "次尝试)");
                    closeKeyboardIfOpen();
                    response(msgid, "success");
                    return;
                }
                if (attempt == 1) {
                    ThreadUtil.sleep(2000); // 再给2s渲染时间
                }
            }

            // 4、方式二：OCR点击"发送"（节点文本被微信8.0.52+混淆时的兜底；无键盘全屏OCR）
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (ocrTapText("发送")) {
                    LogHelper.e(TAG, "OCR已点击发送(第" + attempt + "次尝试)");
                    ThreadUtil.sleep(1500);
                    if (isInputCleared(url)) {
                        LogHelper.e(TAG, "发送成功-OCR点击(第" + attempt + "次尝试)");
                        closeKeyboardIfOpen();
                        response(msgid, "success");
                        return;
                    }
                }
                ThreadUtil.sleep(1500);
            }

            // 5、方式三（兜底）：粘贴路径——会弹软键盘，OCR点击后收起键盘。
            //    粘贴走微信原生输入管线；弹键盘后先清空再粘贴防拼接
            editNode = findChatEditText();
            if (editNode != null) {
                editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ThreadUtil.sleep(500);
                editNode = findChatEditText();
                if (editNode != null) {
                    editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    ThreadUtil.sleep(1000);
                }
                for (int attempt = 1; attempt <= 2; attempt++) {
                    if (ocrTapText("发送")) {
                        ThreadUtil.sleep(1500);
                        if (isInputCleared(url)) {
                            LogHelper.e(TAG, "发送成功-粘贴+OCR点击(第" + attempt + "次尝试)");
                            closeKeyboardIfOpen();
                            response(msgid, "success");
                            return;
                        }
                    } else {
                        break;
                    }
                }
            }

            // 6、失败：记录输入框当前内容（若可见），主循环按发送失败处理
            editNode = findChatEditText();
            LogHelper.e(TAG, "发送链接失败: 各方式均未成功, 输入框="
                    + (editNode == null ? "null" : editNode.getText() + ""));
            response(msgid, "error");
            return;
        } catch (Exception e) {
            LogHelper.e(TAG, "发送链接异常: " + e.getMessage());
            ExceptionUtil.getStackTrace(e);
            response(msgid, "error");
        }
    }

    /**
     * 验证输入框是否已清空（不再包含该链接）：发送成功后微信会清空输入框。
     *
     * <p>注意：输入框节点找不到≠已发送（可能是粘贴后布局瞬时刷新），
     * 必须延迟重查确认，否则会产生"假成功"。</p>
     *
     * @param url 刚填入的链接
     * @return 是否已发送
     */
    private boolean isInputCleared(String url) {
        AccessibilityNodeInfo edit = findChatEditText();
        if (edit == null) {
            // 节点暂时找不到，等待布局稳定后重查一次
            ThreadUtil.sleep(600);
            edit = findChatEditText();
            if (edit == null) {
                LogHelper.e(TAG, "验证发送: 输入框节点不存在，无法确认已发送");
                return false;
            }
        }
        return !(edit.getText() + "").contains(url);
    }

    /**
     * 验证输入框内容是否恰好等于该链接（trim后全等）。
     *
     * <p>用全等而非contains：粘贴是追加语义，若输入框有残留文本，
     * contains会误判通过导致两条链接拼接发送。</p>
     *
     * @param url 链接
     * @return 是否恰好填入该链接
     */
    private boolean isInputExact(String url) {
        AccessibilityNodeInfo edit = findChatEditText();
        if (edit == null) {
            return false;
        }
        return ((edit.getText() + "").trim()).equals(url);
    }

    /**
     * 截屏 + OCR 查找目标文字的坐标并手势点击（无障碍服务 takeScreenshot，无需录屏授权）。
     *
     * @param target 目标文字（如"发送"）
     * @return 是否成功找到并点击
     */
    private boolean ocrTapText(String target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LogHelper.e(TAG, "OCR点击跳过: 需要Android 11+");
            return false;
        }
        try {
            LogHelper.e(TAG, "OCR截屏查找[" + target + "]...");
            Text visionText = ocrCaptureText();
            if (visionText == null) {
                return false;
            }
            Rect rect = findTextRect(visionText, target, false);
            if (rect != null) {
                LogHelper.e(TAG, "OCR命中[" + target + "] " + rect);
                _Tap(rect.centerX(), rect.centerY(), 100L, null);
                return true;
            }
            LogHelper.e(TAG, "OCR未找到[" + target + "]");
            return false;
        } catch (Exception e) {
            LogHelper.e(TAG, "OCR点击异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 点击"发送"按钮：遍历全部窗口（活动窗口可能被输入法抢占），
     * 不依赖控件类型，文本为"发送"即可；节点可点击时执行 ACTION_CLICK，
     * 否则按节点中心坐标手势点击。找不到时输出候选节点诊断信息。
     */
    private void clickSendButton() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            roots.add(active);
            seen.add(active.getWindowId());
        }
        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && !seen.contains(window.getId())) {
                seen.add(window.getId());
                roots.add(root);
            }
        }
        for (AccessibilityNodeInfo root : roots) {
            List<AccessibilityNodeInfo> sendNodes = root.findAccessibilityNodeInfosByText("发送");
            AccessibilityNodeInfo fallback = null;
            Rect fallbackRect = null;
            StringBuilder diag = new StringBuilder();
            for (AccessibilityNodeInfo node : sendNodes) {
                String text = node.getText() + "";
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);
                if (diag.length() > 0) {
                    diag.append(" | ");
                }
                diag.append(text).append(rect).append(node.getClassName());
                if (rect.isEmpty()) {
                    continue;
                }
                if ("发送".equals(text.trim())) {
                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        LogHelper.e(TAG, "点击 发送(click) " + rect);
                    } else {
                        _Tap(rect.centerX(), rect.centerY(), 100L, null);
                        LogHelper.e(TAG, "点击 发送(tap) " + rect);
                    }
                    return;
                }
                if (fallback == null) {
                    fallback = node;
                    fallbackRect = rect;
                }
            }
            if (fallback != null && fallbackRect != null) {
                _Tap(fallbackRect.centerX(), fallbackRect.centerY(), 100L, null);
                LogHelper.e(TAG, "点击 发送(候选tap) " + fallbackRect + " " + (fallback.getText() + ""));
                return;
            }
        }
        LogHelper.e(TAG, "点击发送失败: 各窗口均未找到发送按钮");
    }

    /**
     * 检查链接：OCR 在聊天列表中找到刚发送的链接消息并点击，等待页面加载后
     * OCR 扫描整页文本中的风险关键词。
     *
     * <p>微信 8.0.52+ 对无障碍节点文本做了混淆（节点里读不到任何文本），
     * 因此查找与扫描全部基于截屏 OCR，不依赖节点文本。</p>
     *
     * 应答：normal / risk:&lt;关键词&gt; / nofind(聊天中未找到链接消息，未发生跳转) / notopen(点击了但未离开聊天) / error
     *
     * @param url   待检查的链接
     * @param msgid 同步应答id
     */
    private void checkLink(String url, String msgid) {
        try {
            // 不在聊天界面（无输入框）时结果不可信，回复nochat让主循环提醒用户
            if (findChatEditText() == null) {
                LogHelper.e(TAG, "检查链接失败: 不在聊天界面");
                response(msgid, "nochat");
                return;
            }
            // 收起软键盘（若粘贴时弹出），否则滑动滚屏手势会落在键盘上失效
            closeKeyboardIfOpen();
            // 1、OCR 在聊天列表中查找链接消息（消息气泡渲染有延迟，最多重试5次）
            // 先把聊天滚动到底部：无障碍粘贴发送不会触发微信自动滚动，
            // 聊天停留在历史位置时新消息在可视区之外，且会误点击旧消息（旧链接打不开或非本条）
            swipeUpToBottom();
            ThreadUtil.sleep(800);
            Rect linkRect = null;
            Text lastVisionText = null;
            for (int retry = 0; retry < 5 && linkRect == null; retry++) {
                if (retry > 0) {
                    ThreadUtil.sleep(1500);
                }
                linkRect = ocrFindTextRect(url, true);
                if (linkRect == null) {
                    if (retry >= 2) {
                        swipeUpToBottom();
                        ThreadUtil.sleep(800);
                    }
                    lastVisionText = ocrCaptureText();
                }
            }
            if (linkRect == null) {
                LogHelper.e(TAG, "检查链接失败: 聊天中未找到 " + url);
                // 诊断：输出OCR识别到的文本行，便于核对微信聊天界面的实际显示内容
                if (lastVisionText != null) {
                    StringBuilder diag = new StringBuilder();
                    int n = 0;
                    for (Text.TextBlock block : lastVisionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String t = line.getText().trim();
                            if (!t.isEmpty()) {
                                diag.append("[").append(t).append("]");
                                if (++n >= 15) {
                                    break;
                                }
                            }
                        }
                        if (n >= 15) {
                            break;
                        }
                    }
                    LogHelper.e(TAG, "OCR识别文本行: " + diag);
                }
                response(msgid, "nofind");
                return;
            }

            // 2、点击链接消息打开网页（取消息中心点，避免点到边角）
            _Tap(linkRect.centerX(), linkRect.centerY(), 100L, null);
            LogHelper.e(TAG, "点击链接 " + linkRect);

            // 3、等待页面加载：固定7秒（保证网页渲染完整、关键词不漏报，不受步骤间隔影响）
            LogHelper.e(TAG, "等待页面加载 7s...");
            ThreadUtil.sleep(7000);

            // 4、校验是否真的离开了聊天界面：输入框仍在说明网页未打开成功
            if (findChatEditText() != null) {
                LogHelper.e(TAG, "检查链接失败: 点击后仍在聊天界面(网页未打开)");
                response(msgid, "notopen");
                return;
            }

            // 5、OCR 扫描整页文本中的风险关键词（用户可在首页自定义）
            List<String> keywords = getRiskKeywords();
            LogHelper.e(TAG, "扫描风险关键词: " + keywords);
            Text pageText = ocrCaptureText();
            if (pageText != null) {
                for (String keyword : keywords) {
                    if (containsNormalized(pageText, keyword)) {
                        LogHelper.e(TAG, "风险页面: 命中关键词[" + keyword + "]");
                        response(msgid, "risk:" + keyword);
                        return;
                    }
                }
            } else {
                LogHelper.e(TAG, "页面OCR失败，按正常处理");
            }

            LogHelper.e(TAG, "页面正常");
            response(msgid, "normal");
        } catch (Exception e) {
            LogHelper.e(TAG, "检查链接异常: " + e.getMessage());
            ExceptionUtil.getStackTrace(e);
            response(msgid, "error");
        }
    }

    /**
     * 读取用户自定义的风险关键词列表。
     *
     * <p>从 MMKV "risk_keywords"（多行文本，每行一个）读取；
     * 未配置或为空时使用 Global.DEFAULT_RISK_KEYWORDS。</p>
     *
     * @return 关键词列表（非空）
     */
    private List<String> getRiskKeywords() {
        List<String> keywords = new ArrayList<>();
        String saved = UI.getMMKV().getString(Global.KEY_RISK_KEYWORDS, "");
        if (StringUtil.isNotEmpty(saved)) {
            for (String line : saved.replace("\r", "").split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    keywords.add(trimmed);
                }
            }
        }
        if (keywords.isEmpty()) {
            keywords.addAll(Arrays.asList(Global.DEFAULT_RISK_KEYWORDS));
        }
        return keywords;
    }

    /**
     * 读取步骤间隔（毫秒）。
     *
     * <p>从 MMKV "step_interval_sec" 读取（秒），越界自动回退默认值。
     * 用于控制检测流程各步骤之间的等待时长。</p>
     *
     * @return 步骤间隔毫秒
     */
    private long getStepIntervalMs() {
        int sec;
        try {
            sec = UI.getMMKV().decodeInt(Global.KEY_STEP_INTERVAL_SEC, Global.DEFAULT_STEP_INTERVAL_SEC);
        } catch (Exception e) {
            sec = Global.DEFAULT_STEP_INTERVAL_SEC;
        }
        if (sec < Global.MIN_STEP_INTERVAL_SEC || sec > Global.MAX_STEP_INTERVAL_SEC) {
            sec = Global.DEFAULT_STEP_INTERVAL_SEC;
        }
        return sec * 1000L;
    }

    /**
     * 收起软键盘（若已弹出）。
     *
     * <p>判断依据：输入栏被顶到屏幕上半部（top < 80%屏高）说明软键盘已弹出，
     * 此时按一次返回键收起键盘（仍停留在聊天界面）；输入栏在底部则不动作。</p>
     *
     * <p>用途：粘贴/输入会弹出软键盘，键盘不收起会拦截后续的滑动滚屏手势
     * （手势落在键盘上会变成打字/误触）。</p>
     */
    private void closeKeyboardIfOpen() {
        AccessibilityNodeInfo edit = findChatEditText();
        if (edit == null) {
            return;
        }
        Rect r = new Rect();
        edit.getBoundsInScreen(r);
        DisplayMetrics dm = WindowHelper.getRealMetrics();
        if (!r.isEmpty() && r.top < dm.heightPixels * 0.8) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            ThreadUtil.sleep(600);
            LogHelper.e(TAG, "已收起软键盘");
        }
    }

    /**
     * 向上滑动聊天列表，使其滚动到底部（新发送的消息在列表最底部）。
     */
    private void swipeUpToBottom() {
        DisplayMetrics dm = WindowHelper.getRealMetrics();
        int x = dm.widthPixels / 2;
        int y1 = (int) (dm.heightPixels * 0.75);
        int y2 = (int) (dm.heightPixels * 0.25);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path p = new Path();
        p.moveTo(x, y1);
        p.lineTo(x, y2);
        builder.addStroke(new GestureDescription.StrokeDescription(p, 0L, 300L));
        dispatchGesture(builder.build(), null, null);
        LogHelper.e(TAG, "向上滑动聊天列表到底部");
    }

    /**
     * 截屏并 OCR 识别整屏文本（同步等待）。
     *
     * @return 识别结果（失败返回 null）
     */
    private Text ocrCaptureText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LogHelper.e(TAG, "OCR跳过: 需要Android 11+");
            return null;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Text> resultRef = new AtomicReference<>(null);

            takeScreenshot(Display.DEFAULT_DISPLAY, SCREENSHOT_EXECUTOR, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult result) {
                    try {
                        Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(
                                result.getHardwareBuffer(), result.getColorSpace());
                        if (hwBitmap == null) {
                            throw new IllegalStateException("截屏转换失败");
                        }
                        Bitmap softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        hwBitmap.recycle();
                        result.getHardwareBuffer().close();

                        InputImage image = InputImage.fromBitmap(softBitmap, 0);
                        TextRecognizer recognizer = TextRecognition.getClient(
                                new ChineseTextRecognizerOptions.Builder().build());
                        recognizer.process(image)
                                .addOnSuccessListener(visionText -> resultRef.set(visionText))
                                .addOnCompleteListener(task -> {
                                    recognizer.close();
                                    latch.countDown();
                                });
                    } catch (Exception e) {
                        LogHelper.e(TAG, "OCR处理异常: " + e.getMessage());
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    LogHelper.e(TAG, "OCR截屏失败: code=" + errorCode);
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return resultRef.get();
        } catch (Exception e) {
            LogHelper.e(TAG, "OCR截屏异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 在 OCR 识别结果中查找目标文字，返回其屏幕边界。
     *
     * @param visionText ML Kit 识别结果
     * @param target     目标文字
     * @param last       true取最下方匹配（最新消息），false取最上方匹配
     * @return 目标文字的屏幕边界（未找到返回 null）
     */
    private Rect findTextRect(Text visionText, String target, boolean last) {
        Rect found = null;
        // 忽略大小写：OCR 对混合大小写短链的大小写识别不可靠
        String normTarget = normalizeText(target).toLowerCase();
        if (normTarget.isEmpty()) {
            return null;
        }
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String normLine = normalizeText(line.getText()).toLowerCase();
                Rect box = line.getBoundingBox();
                if (box == null || box.isEmpty()) {
                    continue;
                }
                if (normLine.contains(normTarget)) {
                    // 取最上/最下方匹配；目标为链接时缩窄点击范围到匹配区域中部，避免点到气泡边角
                    if (found == null || (last ? box.top > found.top : box.top < found.top)) {
                        found = box;
                    }
                }
            }
        }
        return found;
    }

    /**
     * 文本归一化：去除空格，用于匹配 OCR 因排版产生的空格差异。
     */
    private String normalizeText(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\u00A0", "").trim();
    }

    /**
     * 判断 OCR 全文是否包含关键词（归一化后匹配）。
     */
    private boolean containsNormalized(Text visionText, String keyword) {
        String normKeyword = normalizeText(keyword);
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            if (normalizeText(block.getText()).contains(normKeyword)) {
                return true;
            }
            for (Text.Line line : block.getLines()) {
                if (normalizeText(line.getText()).contains(normKeyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * OCR 查找目标文字在屏幕上的位置。
     *
     * <p>匹配降级策略：全路径(忽略大小写) → 仅域名部分。
     * 随机短链的路径部分（混合大小写+数字）OCR 极易误读单个字符，
     * 域名部分字符稳定可靠；且每次只检查刚发送的一条链接，
     * 屏幕最下方匹配域名的消息即为刚发送的链接。</p>
     *
     * @param target 目标文字（自动去除协议头以兼容聊天中链接的显示形式）
     * @param last   true取最下方匹配（最新消息）
     * @return 屏幕边界（未找到返回 null）
     */
    private Rect ocrFindTextRect(String target, boolean last) {
        // 链接消息在聊天中可能不带协议头显示，匹配时去除 https:// 前缀
        String matchTarget = target.replaceFirst("^https?://", "");
        String host = matchTarget;
        int slash = matchTarget.indexOf('/');
        if (slash > 0) {
            host = matchTarget.substring(0, slash);
        }
        Text visionText = ocrCaptureText();
        if (visionText == null) {
            return null;
        }
        Rect rect = findTextRect(visionText, matchTarget, last);
        if (rect != null) {
            LogHelper.e(TAG, "OCR命中[" + matchTarget + "] " + rect);
            return rect;
        }
        // 降级：仅按域名匹配
        if (!host.equals(matchTarget)) {
            LogHelper.e(TAG, "全路径未命中，降级按域名匹配: " + host);
            rect = findTextRect(visionText, host, last);
            if (rect != null) {
                LogHelper.e(TAG, "OCR命中[域名:" + host + "] " + rect);
            }
        }
        return rect;
    }

    /**
     * 查找微信聊天输入框。
     *
     * <p>优先结构匹配：定位"只包含一个 EditText 子节点的 ScrollView"；
     * 键盘弹出等场景布局变化时，退化为查找任意可编辑的 EditText（聊天界面中唯一）。</p>
     *
     * @return 输入框节点（未找到返回 null）
     */
    private AccessibilityNodeInfo findChatEditText() {
        List<AccessibilityNodeInfo> nodes = findNodeInfos();
        for (AccessibilityNodeInfo scrollNode : nodes) {
            if ((scrollNode.getClassName() + "").contains("ScrollView") && scrollNode.getChildCount() == 1) {
                AccessibilityNodeInfo editNode = scrollNode.getChild(0);
                if (editNode != null && (editNode.getClassName() + "").contains("EditText")) {
                    return editNode;
                }
            }
        }
        // 兜底：键盘弹出后输入栏结构变化，按"可编辑的EditText"特征查找（聊天界面中唯一）
        for (AccessibilityNodeInfo node : nodes) {
            if ((node.getClassName() + "").contains("EditText") && node.isEditable()) {
                return node;
            }
        }
        return null;
    }

    /* ****************************************** 命令 *********************************************/

    /**
     * 模拟点击事件
     *
     * @param x
     * @param y
     */
    private void Tap(int x, int y) {
        _Tap(x, y, 200L, null);
    }

    /**
     * 模拟长按事件
     *
     * @param x
     * @param y
     */
    private void LongTap(int x, int y) {
        _Tap(x, y, 1000L, null);
    }

    /**
     * 模拟点击事件
     *
     * @param x
     * @param y
     * @param time 长按毫秒
     * @param onCompleted 异步执行完成回调
     */
    private void _Tap(int x, int y, long time, Runnable onCompleted) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path p = new Path();
        p.moveTo(x , y);
        builder.addStroke(new GestureDescription.StrokeDescription(p, 0L, time));
        GestureDescription gesture = builder.build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                if (onCompleted != null) {
                    onCompleted.run();
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
            }
        }, null);
    }

    /**
     * 递归遍历无障碍节点树，把每个子节点序列化为"类名-文本-屏幕边界-viewId-是否可点击"的键，
     * 并将整棵节点树结构写入 parentMap，供调试时打印当前界面的节点树。
     */
    private void _debugGet(AccessibilityNodeInfo node, Map<String, Object> parentMap) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }

            String className = (child.getClassName() + "")
                    .replace("android.widget.", "")
                    .replace("androidx.recyclerview.widget.", "")
                    .replace("androidx.viewpager.widget.", "")
                    .replace("android.webkit.", "")
                    .replace("android.view.", "");
            String text = child.getText() == null ? "" : child.getText().toString();
            Rect rect = new Rect();
            child.getBoundsInScreen(rect);
            String bounds = "[" + rect.left + "," + rect.top + "," +
                    rect.right + "," + rect.bottom + "]"; // x1 y1 x2 y2
            String viewId = Objects.requireNonNullElse(child.getViewIdResourceName(), "");
            String info = className + "-" + text + bounds + viewId + "|" + child.isClickable();

            if (child.getChildCount() == 0) {
                parentMap.put(info, null);
            } else {
                Map<String, Object> childMap = new LinkedHashMap<>();
                parentMap.put(info, childMap);
                _debugGet(child, childMap);
            }
        }
    }

    /**
     * 调试查找view
     */
    private void debugRun() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        Map<String, Object> parentMap = new LinkedHashMap<>();
        _debugGet(rootNode, parentMap);

        String json = Codec.json_encode_pretty(parentMap);
        String[] lines = StringUtil.split(json, "\n");
        for (String line : lines) {
            LogHelper.d(TAG, line);
        }
    }

    /**
     * 返回所有节点集合（递归遍历节点树），用于微信 WebView 等无法直接通过文本定位的场景
     * @return 所有节点
     */
    private List<AccessibilityNodeInfo> findNodeInfos() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        _findNodeInfos(rootNode, list);
        return list;
    }

    /**
     * 递归收集节点树中的所有子节点到 list 中
     */
    private void _findNodeInfos(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> list) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                list.add(child);
                _findNodeInfos(child, list);
            }
        }
    }

    /**
     * 同步操作返回
     * @param msgid
     * @param res
     */
    private synchronized void response(String msgid, String res) {
        MMKV kv = UI.getMMKV();
        kv.putString(msgid, res, 3600);
        LogHelper.d(TAG, "response " + msgid + "->" + res);
    }
}
