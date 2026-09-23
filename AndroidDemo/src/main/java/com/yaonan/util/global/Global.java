package com.yaonan.util.global;

/**
 * 全局常量类：集中存放项目内通用的全局常量。
 */
public class Global {
    /** 全局日志 TAG，供各模块统一使用。 */
    public static final String TAG = "mylog";

    /** 默认风险关键词：用户未自定义时使用（页面出现任一关键词即判定为风险链接） */
    public static final String[] DEFAULT_RISK_KEYWORDS = {
            "诱导分享", "长按网址", "已停止访问", "谨慎访问", "安全性", "存在风险"
    };

    /** MMKV键：自定义风险关键词（多行文本，每行一个） */
    public static final String KEY_RISK_KEYWORDS = "risk_keywords";

    /** MMKV键：步骤间隔（秒，每个检测步骤之间的等待时长，越小检测越快） */
    public static final String KEY_STEP_INTERVAL_SEC = "step_interval_sec";

    /** MMKV键：飞书自定义机器人 webhook 地址（发现风险链接时推送通知） */
    public static final String KEY_FEISHU_WEBHOOK = "feishu_webhook";

    /** MMKV键：飞书机器人签名校验密钥（机器人未开启签名校验时留空） */
    public static final String KEY_FEISHU_SECRET = "feishu_secret";

    /** 默认签名校验密钥（可在首页修改） */
    public static final String DEFAULT_FEISHU_SECRET = "1vfjnCjphsXod7QT1IiwYc";

    /** 步骤间隔默认值（秒） */
    public static final int DEFAULT_STEP_INTERVAL_SEC = 3;

    /** 步骤间隔下限（秒） */
    public static final int MIN_STEP_INTERVAL_SEC = 1;

    /** 步骤间隔上限（秒） */
    public static final int MAX_STEP_INTERVAL_SEC = 60;
}
