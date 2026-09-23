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

    /** MMKV键：每个链接的检查等待时长（秒，即点击链接后等待页面加载的时长） */
    public static final String KEY_CHECK_WAIT_SEC = "check_wait_sec";

    /** 检查等待时长默认值（秒） */
    public static final int DEFAULT_CHECK_WAIT_SEC = 6;

    /** 检查等待时长下限（秒） */
    public static final int MIN_CHECK_WAIT_SEC = 3;

    /** 检查等待时长上限（秒） */
    public static final int MAX_CHECK_WAIT_SEC = 60;
}
