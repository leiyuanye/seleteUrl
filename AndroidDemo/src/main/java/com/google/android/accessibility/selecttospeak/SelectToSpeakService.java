package com.google.android.accessibility.selecttospeak;

import static com.yaonan.util.global.Global.TAG;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
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
import com.yaonan.util.jna.UI;
import com.yaonan.util.LogHelper;
import com.yaonan.util.lang.StringUtil;
import com.yaonan.util.lang.ThreadUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 风险页关键词：网页中出现任一关键词即判定为"被微信拦截/风险提示" */
    private static final String[] RISK_KEYWORDS = {
            "诱导分享", "长按网址", "已停止访问", "谨慎访问", "安全性", "存在风险"
    };

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
            // 1、定位输入框
            AccessibilityNodeInfo editNode = findChatEditText();
            if (editNode == null) {
                LogHelper.e(TAG, "发送链接失败: 未找到聊天输入框");
                response(msgid, "error");
                return;
            }

            // 2、方式一：ACTION_FOCUS聚焦（不弹键盘、布局不变）+ 粘贴
            //    粘贴走微信原生输入管线，等价于真实输入，会触发"发送"按钮显示
            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            ThreadUtil.sleep(500);
            boolean pasted = editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            LogHelper.e(TAG, "粘贴动作: " + pasted);
            ThreadUtil.sleep(1000);

            // 3、方式二：SET_TEXT直填
            if (!isInputFilled(url)) {
                LogHelper.e(TAG, "粘贴未生效，退回SET_TEXT方式");
                editNode = findChatEditText();
                if (editNode != null) {
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url);
                    editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                    ThreadUtil.sleep(800);
                }
            }

            // 4、方式三：坐标点击聚焦（弹键盘）+ 重新定位 + 粘贴
            if (!isInputFilled(url)) {
                LogHelper.e(TAG, "SET_TEXT未生效，退回坐标点击+粘贴方式");
                editNode = findChatEditText();
                if (editNode != null) {
                    Rect boxRect = new Rect();
                    editNode.getBoundsInScreen(boxRect);
                    _Tap(boxRect.centerX(), boxRect.centerY(), 50L, null);
                    ThreadUtil.sleep(800);
                    editNode = findChatEditText();
                    if (editNode != null) {
                        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        ThreadUtil.sleep(300);
                        editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        ThreadUtil.sleep(1000);
                    }
                }
            }

            if (!isInputFilled(url)) {
                LogHelper.e(TAG, "发送链接失败: 三种方式均未能填入链接");
                response(msgid, "error");
                return;
            }
            LogHelper.e(TAG, "输入 " + url);

            // 点击"发送"并验证：发送成功后微信会清空输入框，以此为准做闭环校验。
            // 每轮两种方式：1无障碍节点点击；2截屏+OCR查找"发送"文字坐标后手势点击。
            for (int attempt = 1; attempt <= 3; attempt++) {
                // 方式1：无障碍节点点击
                clickSendButton();
                ThreadUtil.sleep(1500);
                if (isInputCleared(url)) {
                    LogHelper.e(TAG, "发送成功-节点点击(第" + attempt + "次尝试)");
                    response(msgid, "success");
                    return;
                }

                // 方式2：截屏 + OCR 查找"发送"文字坐标，手势点击
                if (ocrTapText("发送")) {
                    LogHelper.e(TAG, "OCR已点击发送(第" + attempt + "次尝试)");
                    ThreadUtil.sleep(1500);
                    if (isInputCleared(url)) {
                        LogHelper.e(TAG, "发送成功-OCR点击(第" + attempt + "次尝试)");
                        response(msgid, "success");
                        return;
                    }
                }
                LogHelper.e(TAG, "发送未生效(第" + attempt + "次尝试)，重试");
            }

            LogHelper.e(TAG, "发送链接失败: 多次尝试后输入框仍未清空");
            response(msgid, "error");
        } catch (Exception e) {
            LogHelper.e(TAG, "发送链接异常: " + e.getMessage());
            ExceptionUtil.getStackTrace(e);
            response(msgid, "error");
        }
    }

    /**
     * 验证输入框是否已清空（不再包含该链接）：发送成功后微信会清空输入框；
     * 输入框节点不存在时也视为已发送（界面已切换）。
     *
     * @param url 刚填入的链接
     * @return 是否已发送
     */
    private boolean isInputCleared(String url) {
        AccessibilityNodeInfo edit = findChatEditText();
        if (edit == null) {
            return true;
        }
        return !(edit.getText() + "").contains(url);
    }

    /**
     * 验证输入框中是否已填入该链接。
     *
     * @param url 链接
     * @return 是否已填入
     */
    private boolean isInputFilled(String url) {
        AccessibilityNodeInfo edit = findChatEditText();
        if (edit == null) {
            return false;
        }
        return (edit.getText() + "").contains(url);
    }

    /**
     * 截屏 + OCR 查找目标文字的坐标并手势点击（无障碍服务 takeScreenshot，无需录屏授权）。
     *
     * <p>流程：takeScreenshot 截取整屏 → ML Kit 中文识别 → 找到包含目标文字的元素 →
     * 手势点击其中心坐标。仅支持 Android 11+（takeScreenshot 为 API 30 新增）。</p>
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
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean tapped = new AtomicBoolean(false);

            takeScreenshot(getDisplay().getDisplayId(), SCREENSHOT_EXECUTOR, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult result) {
                    try {
                        // HardwareBitmap 转 software Bitmap 供 ML Kit 识别
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
                                .addOnSuccessListener(visionText -> {
                                    Rect rect = findTextRect(visionText, target);
                                    if (rect != null) {
                                        LogHelper.e(TAG, "OCR命中[" + target + "] " + rect);
                                        _Tap(rect.centerX(), rect.centerY(), 100L, null);
                                        tapped.set(true);
                                    } else {
                                        LogHelper.e(TAG, "OCR未找到[" + target + "]");
                                    }
                                })
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

            // 最多等待8秒识别完成
            latch.await(8, TimeUnit.SECONDS);
            return tapped.get();
        } catch (Exception e) {
            LogHelper.e(TAG, "OCR点击异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 在 OCR 识别结果中查找包含目标文字的元素边界（优先精确匹配，其次包含匹配）。
     *
     * @param visionText ML Kit 识别结果
     * @param target     目标文字
     * @return 目标文字在屏幕上的边界（未找到返回 null）
     */
    private Rect findTextRect(Text visionText, String target) {
        Rect exactRect = null;
        Rect containsRect = null;
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (target.equals(line.getText().trim()) && line.getBoundingBox() != null) {
                    exactRect = line.getBoundingBox();
                }
                for (Text.Element element : line.getElements()) {
                    String text = element.getText().trim();
                    Rect box = element.getBoundingBox();
                    if (box == null) {
                        continue;
                    }
                    if (target.equals(text)) {
                        exactRect = box;
                    } else if (text.contains(target)) {
                        containsRect = box;
                    }
                }
            }
        }
        if (exactRect != null) {
            return exactRect;
        }
        if (containsRect != null) {
            // 包含匹配时取文字所在区域中间偏左的部分（如"发送"在"发送(S)"中）
            return containsRect;
        }
        return null;
    }

    /**
     * 点击"发送"按钮：不依赖控件类型，文本为"发送"即可；
     * 节点可点击时执行 ACTION_CLICK，否则按节点中心坐标手势点击（微信部分版本点击无效）。
     */
    private void clickSendButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            LogHelper.e(TAG, "点击发送失败: 无活动窗口");
            return;
        }
        List<AccessibilityNodeInfo> sendNodes = root.findAccessibilityNodeInfosByText("发送");
        for (AccessibilityNodeInfo node : sendNodes) {
            if (!"发送".equals(node.getText() + "")) {
                continue;
            }
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.isEmpty()) {
                continue;
            }
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                LogHelper.e(TAG, "点击 发送(click) " + rect);
            } else {
                _Tap(rect.centerX(), rect.centerY(), 100L, null);
                LogHelper.e(TAG, "点击 发送(tap) " + rect);
            }
            return;
        }
        LogHelper.e(TAG, "点击发送失败: 未找到 发送 按钮");
    }

    /**
     * 检查链接：点击聊天中该 url 的消息，等待页面加载后扫描所有节点文本中的风险关键词。
     *
     * @param url   待检查的链接
     * @param msgid 同步应答id
     */
    private void checkLink(String url, String msgid) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                response(msgid, "nofind");
                return;
            }

            // 在聊天记录中查找该链接的消息节点并点击（链接消息以 url 文本展示）
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(url);
            AccessibilityNodeInfo linkNode = null;
            for (AccessibilityNodeInfo node : nodes) {
                if (url.equals((node.getText() + "").trim())) {
                    linkNode = node;
                    break;
                }
            }
            if (linkNode == null) {
                LogHelper.e(TAG, "检查链接失败: 聊天中未找到 " + url);
                response(msgid, "nofind");
                return;
            }

            Rect rect = new Rect();
            linkNode.getBoundsInScreen(rect);
            _Tap(rect.centerX(), rect.centerY(), 100L, null);
            LogHelper.e(TAG, "点击链接 " + rect);

            // 等待页面加载（参考老仓库H5监控的10s，这里6s折中）
            ThreadUtil.sleep(6000);

            // 校验是否真的离开了聊天界面：输入框仍在说明网页未打开成功
            if (findChatEditText() != null) {
                LogHelper.e(TAG, "检查链接失败: 点击后仍在聊天界面(网页未打开)");
                response(msgid, "nofind");
                return;
            }

            // 扫描当前页面所有节点文本中的风险关键词
            List<AccessibilityNodeInfo> allNodes = findNodeInfos();
            for (AccessibilityNodeInfo node : allNodes) {
                CharSequence textCs = node.getText();
                if (textCs == null) {
                    continue;
                }
                String text = textCs.toString();
                for (String keyword : RISK_KEYWORDS) {
                    if (text.contains(keyword)) {
                        LogHelper.e(TAG, "风险页面: 命中关键词[" + keyword + "] " + text);
                        response(msgid, "risk:" + keyword);
                        return;
                    }
                }
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
