package com.google.android.accessibility.selecttospeak;

import static com.yaonan.util.global.Global.TAG;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.tencent.mmkv.MMKV;
import com.yaonan.util.codec.Codec;
import com.yaonan.util.exception.ExceptionUtil;
import com.yaonan.util.jna.UI;
import com.yaonan.util.lang.StringUtil;
import com.yaonan.util.lang.ThreadUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /**
     * 服务连接成功回调：无障碍服务启动后由系统调用，当前仅调用父类默认实现，预留服务启动后的初始化扩展点。
     */
    @Override
    protected void onServiceConnected() {
        //Log.e(TAG, "无障碍服务启动");
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

                if (cmd != null && cmd.startsWith("#@#")) {
                    Log.e(TAG, cmd);
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
                        String url = cmd.substring("#@#发送链接#".length());
                        sendLink(url, msgid);

                    } else if (cmd.startsWith("#@#检查链接#")) { // 检查链接
                        // 点击刚发送的链接消息，等待页面加载后扫描风险关键词，结果通过 msgid 同步回传
                        String url = cmd.substring("#@#检查链接#".length());
                        checkLink(url, msgid);

                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "无障碍回调异常");
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
            AccessibilityNodeInfo editNode = findChatEditText();
            if (editNode == null) {
                Log.e(TAG, "发送链接失败: 未找到聊天输入框");
                response(msgid, "error");
                return;
            }

            // 填入链接文本
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url);
            boolean ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            if (!ok) {
                // 部分版本需先聚焦再填入
                editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ThreadUtil.sleep(300);
                ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
            if (!ok) {
                Log.e(TAG, "发送链接失败: 填入文本失败");
                response(msgid, "error");
                return;
            }
            Log.e(TAG, "输入 " + url);
            ThreadUtil.sleep(800);

            // 点击"发送"并验证：发送成功后微信会清空输入框，
            // 以此为准做闭环校验，未生效自动重试，最多尝试3次
            for (int attempt = 1; attempt <= 3; attempt++) {
                clickSendButton();

                // 等待微信处理发送
                ThreadUtil.sleep(1500);

                // 验证：输入框中已无该链接即认为发送成功
                AccessibilityNodeInfo freshEdit = findChatEditText();
                String remainText = freshEdit == null ? "" : (freshEdit.getText() + "").trim();
                if (!remainText.contains(url)) {
                    Log.e(TAG, "发送成功(第" + attempt + "次尝试)");
                    response(msgid, "success");
                    return;
                }
                Log.e(TAG, "发送未生效(第" + attempt + "次尝试)，重试");
            }

            Log.e(TAG, "发送链接失败: 点击发送后输入框仍未清空");
            response(msgid, "error");
        } catch (Exception e) {
            Log.e(TAG, "发送链接异常: " + e.getMessage());
            ExceptionUtil.getStackTrace(e);
            response(msgid, "error");
        }
    }

    /**
     * 点击"发送"按钮：不依赖控件类型，文本为"发送"即可；
     * 节点可点击时执行 ACTION_CLICK，否则按节点中心坐标手势点击（微信部分版本点击无效）。
     */
    private void clickSendButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.e(TAG, "点击发送失败: 无活动窗口");
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
                Log.e(TAG, "点击 发送(click) " + rect);
            } else {
                _Tap(rect.centerX(), rect.centerY(), 100L, null);
                Log.e(TAG, "点击 发送(tap) " + rect);
            }
            return;
        }
        Log.e(TAG, "点击发送失败: 未找到 发送 按钮");
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
                Log.e(TAG, "检查链接失败: 聊天中未找到 " + url);
                response(msgid, "nofind");
                return;
            }

            Rect rect = new Rect();
            linkNode.getBoundsInScreen(rect);
            _Tap(rect.centerX(), rect.centerY(), 100L, null);
            Log.e(TAG, "点击链接 " + rect);

            // 等待页面加载
            ThreadUtil.sleep(4000);

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
                        Log.e(TAG, "风险页面: 命中关键词[" + keyword + "] " + text);
                        response(msgid, "risk:" + keyword);
                        return;
                    }
                }
            }

            Log.e(TAG, "页面正常");
            response(msgid, "normal");
        } catch (Exception e) {
            Log.e(TAG, "检查链接异常: " + e.getMessage());
            ExceptionUtil.getStackTrace(e);
            response(msgid, "error");
        }
    }

    /**
     * 查找微信聊天输入框：递归遍历节点树，定位"只包含一个 EditText 子节点的 ScrollView"。
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
            Log.d(TAG, line);
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
        Log.d(TAG, "response " + msgid + "->" + res);
    }
}
