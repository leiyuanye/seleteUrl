package com.yaonan.activity;

import static com.yaonan.util.global.Global.TAG;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.tencent.mmkv.MMKV;
import com.yaonan.App;
import com.yaonan.R;
import com.yaonan.databinding.ActivityMainBinding;
import com.yaonan.util.LogHelper;
import com.yaonan.util.lang.ThreadUtil;
import com.yaonan.util.WindowHelper;
import com.yaonan.util.jna.UI;
import com.yaonan.util.lang.StringUtil;
import com.yaonan.view.ScreenshotView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用主界面 Activity。
 *
 * <p>职责：管理悬浮弹窗、无障碍服务的授权入口，以及"检测文件"（TXT 链接列表）的选择；
 * 链接检测的执行入口在悬浮窗上（避免依赖本界面存活），由 ScreenshotView 驱动。</p>
 */
public class MainActivity extends AppCompatActivity {

    /** 视图绑定对象，用于访问布局中的控件 */
    private ActivityMainBinding binding;
    /** TXT 文件选择的请求码 */
    private static final int REQ_PICK_TXT = 101;
    /** MMKV 中保存已选 TXT 文件 Uri 的键名 */
    private static final String KEY_LINKS_URI = "links_uri";
    /** 是否处于手账风主题 */
    private boolean isJournalTheme = false;
    /** 最近一次点击开发者标签的时间戳，用于判断是否在 500ms 内连续点击（双击切换主题） */
    private long lastDevTagClickTime = 0;
    /** 最近一次点击版本号按钮的时间戳，用于判断是否在 500ms 内双击（双击触发更新） */
    private long lastAboutClickTime = 0;
    /** MMKV 中保存主题状态的键名 */
    private static final String KEY_JOURNAL_THEME = "journal_theme";

    /**
     * 创建界面：绑定布局、初始化主题、注册各按钮点击事件。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        LogHelper.i(TAG, "===== App启动 =====");

        MMKV kv = UI.getMMKV();

        // 从持久化存储中读取主题状态并应用
        isJournalTheme = kv.decodeBool(KEY_JOURNAL_THEME, false);
        applyTheme();

        // 开发者标签：500ms 内连续点击两次即切换主题（双击在默认/手账风之间切换）
        binding.devTagContainer.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastDevTagClickTime < 500) {
                isJournalTheme = !isJournalTheme;
                kv.encode(KEY_JOURNAL_THEME, isJournalTheme);
                applyTheme();
                UI.alert(isJournalTheme ? "已切换为手账风 ♡" : "已切换为默认风格", this);
            }
            lastDevTagClickTime = now;
        });

        // 弹窗授权
        binding.btnShowScreenshot.setOnClickListener(v -> {
            if (WindowHelper.checkOverlay(this)) {
                WindowHelper.showScreenshotView();
            }
        });
        binding.btnHideScreenshot.setOnClickListener(v -> {
            if (WindowHelper.checkOverlay(this)) {
                WindowHelper.hideScreenshotView();
            }
        });

        // 无障碍授权
        binding.btnStartA.setOnClickListener(v -> {
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am.isEnabled()) {
                UI.alert("无障碍服务已打开");
            } else {
                UI.alert("无障碍服务未打开");
            }

            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        // 选择TXT文件（每行一条链接）
        binding.btnPickFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/*");
            startActivityForResult(intent, REQ_PICK_TXT);
        });

        // 检测设置：飞书机器人地址 + 风险关键词摘要（点击编辑）+ 检查时长
        binding.etFeishuWebhook.setText(kv.getString(com.yaonan.util.global.Global.KEY_FEISHU_WEBHOOK, ""));
        binding.btnFeishuTest.setOnClickListener(v -> testFeishuWebhook());
        refreshKeywordSummary();
        binding.tvKeywordsSummary.setOnClickListener(v -> showKeywordsDialog());
        binding.etCheckWait.setText(String.valueOf(
                kv.decodeInt(com.yaonan.util.global.Global.KEY_STEP_INTERVAL_SEC,
                        com.yaonan.util.global.Global.DEFAULT_STEP_INTERVAL_SEC)));
        binding.btnSettingsSave.setOnClickListener(v -> saveSettings());

        // 运行日志：分享（通过微信/QQ等发送日志文件）
        binding.btnLogShare.setOnClickListener(v -> shareLogFile());

        // 运行日志：清空
        binding.btnLogClear.setOnClickListener(v -> {
            LogHelper.clear();
            UI.alert("日志已清空", this);
        });

        // 恢复上次选择的文件链接数
        refreshFileCount();
    }

    /**
     * TXT 文件选择结果：持久化读取权限并刷新链接计数显示。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_TXT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                // 持久化读取权限，重启应用后仍可读取
                App.getApp().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                LogHelper.e(TAG, "takePersistableUriPermission失败: " + e.getMessage());
            }
            UI.getMMKV().putString(KEY_LINKS_URI, uri.toString());
            int count = ScreenshotView.readLinks(uri).size();
            LogHelper.i(TAG, "选择TXT文件: " + uri + "，共" + count + "条链接");
            refreshFileCount();
        }
    }

    /**
     * 通过系统分享发送日志文件（微信/QQ等任意支持文本文件分享的应用）。
     */
    private void shareLogFile() {
        try {
            File file = LogHelper.getLogFile();
            if (!file.exists() || file.length() == 0) {
                UI.alert("暂无日志文件", this);
                return;
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享运行日志"));
        } catch (Exception e) {
            UI.alert("分享失败: " + e.getMessage(), this);
        }
    }

    /**
     * 刷新关键词摘要展示（固定高度、超出省略）。
     */
    @SuppressLint("SetTextI18n")
    private void refreshKeywordSummary() {
        List<String> items = getKeywordItems();
        if (items.isEmpty()) {
            binding.tvKeywordsSummary.setText("未设置，使用默认关键词");
            binding.tvKeywordsLabel.setText("风险关键词（点击编辑，当前0个）");
            return;
        }
        binding.tvKeywordsSummary.setText(String.join("、", items));
        binding.tvKeywordsLabel.setText("风险关键词（共" + items.size() + "个，点击编辑）");
    }

    /**
     * 读取当前生效的关键词列表（未设置时返回默认关键词）。
     */
    private List<String> getKeywordItems() {
        String defaultKeywords = String.join("\n", com.yaonan.util.global.Global.DEFAULT_RISK_KEYWORDS);
        String saved = UI.getMMKV().getString(
                com.yaonan.util.global.Global.KEY_RISK_KEYWORDS, defaultKeywords);
        List<String> items = new ArrayList<>();
        if (StringUtil.isNotEmpty(saved)) {
            for (String line : saved.replace("\r", "").split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    items.add(trimmed);
                }
            }
        }
        return items;
    }

    /**
     * 弹窗编辑风险关键词：多行文本框，每行一个；支持保存与恢复默认。
     */
    private void showKeywordsDialog() {
        MMKV kv = UI.getMMKV();
        String defaultKeywords = String.join("\n", com.yaonan.util.global.Global.DEFAULT_RISK_KEYWORDS);
        String current = kv.getString(com.yaonan.util.global.Global.KEY_RISK_KEYWORDS, defaultKeywords);

        final EditText input = new EditText(this);
        input.setText(current);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP);
        input.setSelection(current == null ? 0 : current.length());

        new AlertDialog.Builder(this)
                .setTitle("风险关键词（每行一个）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    kv.putString(com.yaonan.util.global.Global.KEY_RISK_KEYWORDS, defaultKeywords);
                    refreshKeywordSummary();
                    UI.alert("已恢复默认关键词", this);
                })
                .setPositiveButton("保存", (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    kv.putString(com.yaonan.util.global.Global.KEY_RISK_KEYWORDS, text);
                    refreshKeywordSummary();
                    LogHelper.i(TAG, "保存风险关键词: " + text.length() + "字");
                    UI.alert("关键词已保存", this);
                })
                .show();
    }

    /**
     * 保存检测设置：飞书机器人地址与检查时长（秒，范围 3~60）。
     */
    private void saveSettings() {
        MMKV kv = UI.getMMKV();

        // 飞书机器人地址：空=不推送；非空必须 https:// 开头
        String webhook = binding.etFeishuWebhook.getText() == null
                ? "" : binding.etFeishuWebhook.getText().toString().trim();
        if (!webhook.isEmpty() && !webhook.startsWith("https://")) {
            UI.alert("机器人地址须为 https:// 开头", this);
            return;
        }

        // 步骤间隔：校验范围 1~60 秒，非法回退默认
        String waitStr = binding.etCheckWait.getText() == null
                ? "" : binding.etCheckWait.getText().toString().trim();
        int waitSec;
        try {
            waitSec = Integer.parseInt(waitStr);
        } catch (NumberFormatException e) {
            waitSec = -1;
        }
        if (waitSec < com.yaonan.util.global.Global.MIN_STEP_INTERVAL_SEC
                || waitSec > com.yaonan.util.global.Global.MAX_STEP_INTERVAL_SEC) {
            UI.alert("步骤间隔须为 " + com.yaonan.util.global.Global.MIN_STEP_INTERVAL_SEC
                    + "~" + com.yaonan.util.global.Global.MAX_STEP_INTERVAL_SEC + " 的整数", this);
            return;
        }

        kv.putString(com.yaonan.util.global.Global.KEY_FEISHU_WEBHOOK, webhook);
        kv.encode(com.yaonan.util.global.Global.KEY_STEP_INTERVAL_SEC, waitSec);

        LogHelper.i(TAG, "保存检测设置: webhook=" + (webhook.isEmpty() ? "未配置" : "已配置")
                + ", 步骤间隔" + waitSec + "s");
        UI.alert("检测设置已保存", this);
    }

    /**
     * 发送飞书测试消息验证机器人地址。
     */
    private void testFeishuWebhook() {
        String webhook = binding.etFeishuWebhook.getText() == null
                ? "" : binding.etFeishuWebhook.getText().toString().trim();
        if (webhook.isEmpty()) {
            UI.alert("请先填写机器人地址", this);
            return;
        }
        UI.alert("正在发送测试消息...", this);
        ThreadUtil.async(() -> {
            boolean ok = com.yaonan.util.FeishuHelper.sendText(webhook, "链接检测测试消息");
            UI.invokeLater(() -> UI.alert(ok ? "测试消息已发送，请查看群聊"
                    : "测试消息发送失败，请检查地址", this));
        });
    }

    /**
     * 刷新文件卡片上的链接计数显示。
     */
    @SuppressLint("SetTextI18n")
    private void refreshFileCount() {
        String uriStr = UI.getMMKV().getString(KEY_LINKS_URI, "");
        if (StringUtil.isEmpty(uriStr)) {
            binding.tvFileCount.setText("未选择文件");
            return;
        }
        int count = ScreenshotView.readLinks(Uri.parse(uriStr)).size();
        binding.tvFileCount.setText("已加载 " + count + " 条链接");
    }

    // ===================== 主题切换 =====================

    /**
     * 根据当前主题标记分发到对应的主题应用方法。
     */
    private void applyTheme() {
        if (isJournalTheme) {
            applyJournalTheme();
        } else {
            applyDefaultTheme();
        }
    }

    /**
     * 应用"手账风"主题：替换各卡片、图标、按钮的背景与文字颜色，并叠加阴影、旋转等装饰效果。
     */
    private void applyJournalTheme() {
        float density = getResources().getDisplayMetrics().density;
        int colorCardTitle = Color.parseColor("#5D4E6D");
        int colorDisabled = Color.parseColor("#B0A5C0");
        int colorDevText = Color.parseColor("#7B6B2D");

        binding.scrollRoot.setBackgroundResource(R.drawable.bg_ha_page);

        binding.tvTitle.setTextColor(Color.parseColor("#FF8FA3"));
        binding.tvTitle.setText("链接检测 ♡");
        binding.tvTitle.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        binding.tvTitle.setShadowLayer(3, 2, 2, Color.parseColor("#FFD6E0"));

        binding.tvSubtitle.setTextColor(Color.parseColor("#A89BBE"));
        binding.tvSubtitle.setText("~ 科技改变生活 ~");

        applyCardJournal(binding.cardPopup, R.drawable.bg_ha_card_1, -0.8f, density);
        binding.iconPopupBg.setBackgroundResource(R.drawable.bg_ha_icon_popup);
        binding.iconPopupImg.setImageTintList(ColorStateList.valueOf(Color.parseColor("#5D9C84")));
        binding.tvPopupTitle.setTextColor(colorCardTitle);

        applyCardJournal(binding.cardAccess, R.drawable.bg_ha_card_2, 0.6f, density);
        binding.iconAccessBg.setBackgroundResource(R.drawable.bg_ha_icon_access);
        binding.iconAccessImg.setImageTintList(ColorStateList.valueOf(Color.parseColor("#6B7BA8")));
        binding.tvAccessTitle.setTextColor(colorCardTitle);

        applyCardJournal(binding.cardFile, R.drawable.bg_ha_card_3, -0.5f, density);
        binding.iconFileBg.setBackgroundResource(R.drawable.bg_ha_icon_timer);
        binding.iconFileImg.setImageTintList(ColorStateList.valueOf(Color.parseColor("#C4845A")));
        binding.tvFileTitle.setTextColor(colorCardTitle);

        applyCardJournal(binding.cardSettings, R.drawable.bg_ha_card_3, 0.5f, density);
        binding.tvSettingsTitle.setTextColor(colorCardTitle);
        binding.tvFeishuLabel.setTextColor(colorCardTitle);
        binding.etFeishuWebhook.setBackgroundResource(R.drawable.bg_ha_btn_disabled);
        binding.etFeishuWebhook.setTextColor(colorDisabled);
        binding.etFeishuWebhook.setHintTextColor(colorDisabled);
        binding.btnFeishuTest.setBackgroundResource(R.drawable.bg_ha_btn_primary);
        binding.btnFeishuTest.setTextColor(Color.WHITE);
        binding.tvKeywordsSummary.setBackgroundResource(R.drawable.bg_ha_btn_disabled);
        binding.tvKeywordsSummary.setTextColor(colorDisabled);
        binding.etCheckWait.setBackgroundResource(R.drawable.bg_ha_btn_disabled);
        binding.etCheckWait.setTextColor(colorDisabled);
        binding.btnSettingsSave.setBackgroundResource(R.drawable.bg_ha_btn_primary);
        binding.btnSettingsSave.setTextColor(Color.WHITE);

        applyCardJournal(binding.cardHelp, R.drawable.bg_ha_card_4, 0.7f, density);
        binding.tvHelpTitle.setTextColor(colorCardTitle);

        applyCardJournal(binding.cardLog, R.drawable.bg_ha_card_2, 0.4f, density);
        binding.tvLogTitle.setTextColor(colorCardTitle);

        binding.cardFooter.setBackgroundResource(R.drawable.bg_ha_footer);
        binding.cardFooter.setRotation(-0.3f);
        binding.cardFooter.setElevation(0);
        binding.btnAbout.setBackgroundResource(R.drawable.bg_ha_btn_disabled);
        binding.btnAbout.setTextColor(colorDisabled);

        binding.devTagContainer.setBackgroundResource(R.drawable.bg_ha_dev_tag);
        binding.devTagText.setTextColor(colorDevText);
        binding.devTagImg.setImageTintList(ColorStateList.valueOf(colorDevText));

        binding.btnShowScreenshot.setBackgroundResource(R.drawable.bg_ha_btn_primary);
        binding.btnShowScreenshot.setTextColor(Color.WHITE);
        binding.btnHideScreenshot.setBackgroundResource(R.drawable.bg_ha_btn_primary);
        binding.btnHideScreenshot.setTextColor(Color.WHITE);
        binding.btnStartA.setBackgroundResource(R.drawable.bg_ha_btn_primary);
        binding.btnStartA.setTextColor(Color.WHITE);
        binding.btnPickFile.setBackgroundResource(R.drawable.bg_ha_btn_primary);
        binding.btnPickFile.setTextColor(Color.WHITE);
    }

    /**
     * 将指定卡片应用为手账风样式（自定义背景、旋转角度、按密度缩放阴影）。
     */
    private void applyCardJournal(View card, int bgRes, float rotation, float density) {
        card.setBackgroundResource(bgRes);
        card.setRotation(rotation);
        card.setElevation(density * 2);
    }

    /**
     * 应用默认主题：恢复各卡片、图标、按钮的默认背景与文字颜色。
     */
    private void applyDefaultTheme() {
        float density = getResources().getDisplayMetrics().density;
        int colorTextPrimary = ContextCompat.getColor(this, R.color.text_primary);
        int colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary);
        int colorBtnDisabledText = ContextCompat.getColor(this, R.color.btn_disabled_text);
        int colorDevTagText = ContextCompat.getColor(this, R.color.dev_tag_text);

        binding.scrollRoot.setBackgroundResource(R.color.page_bg);

        binding.tvTitle.setTextColor(colorTextPrimary);
        binding.tvTitle.setText("链接检测");
        binding.tvTitle.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        binding.tvTitle.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        binding.tvSubtitle.setTextColor(colorTextSecondary);
        binding.tvSubtitle.setText("科技改变生活");

        applyCardDefault(binding.cardPopup, density);
        binding.iconPopupBg.setBackgroundResource(R.drawable.bg_icon_popup);
        binding.iconPopupImg.setImageTintList(null);
        binding.tvPopupTitle.setTextColor(colorTextPrimary);

        applyCardDefault(binding.cardAccess, density);
        binding.iconAccessBg.setBackgroundResource(R.drawable.bg_icon_access);
        binding.iconAccessImg.setImageTintList(null);
        binding.tvAccessTitle.setTextColor(colorTextPrimary);

        applyCardDefault(binding.cardFile, density);
        binding.iconFileBg.setBackgroundResource(R.drawable.bg_icon_timer);
        binding.iconFileImg.setImageTintList(null);
        binding.tvFileTitle.setTextColor(colorTextPrimary);

        applyCardDefault(binding.cardSettings, density);
        binding.tvSettingsTitle.setTextColor(colorTextPrimary);
        binding.tvFeishuLabel.setTextColor(colorTextPrimary);
        binding.etFeishuWebhook.setBackgroundResource(R.drawable.bg_btn_disabled);
        binding.etFeishuWebhook.setTextColor(colorTextPrimary);
        binding.etFeishuWebhook.setHintTextColor(colorTextSecondary);
        binding.btnFeishuTest.setBackgroundResource(R.drawable.bg_btn_primary);
        binding.btnFeishuTest.setTextColor(Color.WHITE);
        binding.tvKeywordsSummary.setBackgroundResource(R.drawable.bg_btn_disabled);
        binding.tvKeywordsSummary.setTextColor(colorTextPrimary);
        binding.etCheckWait.setBackgroundResource(R.drawable.bg_btn_disabled);
        binding.etCheckWait.setTextColor(colorTextPrimary);
        binding.btnSettingsSave.setBackgroundResource(R.drawable.bg_btn_primary);
        binding.btnSettingsSave.setTextColor(Color.WHITE);

        applyCardDefault(binding.cardHelp, density);
        binding.tvHelpTitle.setTextColor(colorTextPrimary);

        applyCardDefault(binding.cardLog, density);
        binding.tvLogTitle.setTextColor(colorTextPrimary);

        binding.cardFooter.setBackgroundResource(R.drawable.bg_card);
        binding.cardFooter.setRotation(0);
        binding.cardFooter.setElevation(density * 1);
        binding.btnAbout.setBackgroundResource(R.drawable.bg_btn_disabled);
        binding.btnAbout.setTextColor(colorBtnDisabledText);

        binding.devTagContainer.setBackgroundResource(R.drawable.bg_dev_tag);
        binding.devTagText.setTextColor(colorDevTagText);
        binding.devTagImg.setImageTintList(null);

        binding.btnShowScreenshot.setBackgroundResource(R.drawable.bg_btn_primary);
        binding.btnShowScreenshot.setTextColor(Color.WHITE);
        binding.btnHideScreenshot.setBackgroundResource(R.drawable.bg_btn_primary);
        binding.btnHideScreenshot.setTextColor(Color.WHITE);
        binding.btnStartA.setBackgroundResource(R.drawable.bg_btn_primary);
        binding.btnStartA.setTextColor(Color.WHITE);
        binding.btnPickFile.setBackgroundResource(R.drawable.bg_btn_primary);
        binding.btnPickFile.setTextColor(Color.WHITE);
    }

    /**
     * 将指定卡片恢复为默认样式（默认背景、无旋转、按密度缩放阴影）。
     */
    private void applyCardDefault(View card, float density) {
        card.setBackgroundResource(R.drawable.bg_card);
        card.setRotation(0);
        card.setElevation(density * 1);
    }
}
