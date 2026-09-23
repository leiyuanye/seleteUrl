package com.yaonan.util;

import static com.yaonan.util.global.Global.TAG;

import com.yaonan.util.codec.Codec;
import com.yaonan.util.lang.StringUtil;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 飞书自定义机器人推送工具。
 *
 * 通过群聊中添加的自定义机器人 webhook 地址发送文本消息，
 * 接口格式（无需签名校验的最简形式）：
 * POST https://open.feishu.cn/open-apis/bot/v2/hook/{token}
 * Content-Type: application/json
 * {"msg_type":"text","content":{"text":"消息内容"}}
 *
 * 必须在后台线程调用（涉及网络请求）。返回 true 表示飞书应答 code=0。
 */
public class FeishuHelper {

    /**
     * 发送文本消息到飞书群聊。
     *
     * @param webhook 机器人 webhook 地址
     * @param message 文本内容
     * @param secret  签名校验密钥（机器人未开启签名校验时传空串）
     * @return 是否推送成功
     */
    public static boolean sendText(String webhook, String message, String secret) {
        if (StringUtil.isEmpty(webhook) || !webhook.startsWith("http")) {
            LogHelper.e(TAG, "飞书推送失败: webhook地址无效");
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(webhook).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            String body;
            if (StringUtil.isEmpty(secret)) {
                // 未开启签名校验：最简格式
                body = "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escape(message) + "\"}}";
            } else {
                // 开启签名校验。
                // 注意飞书官方算法的特殊点：HMAC-SHA256 的 key 为 string_to_sign（timestamp+"\n"+secret），
                // 而待签名的消息内容为空字符串，最后 Base64 编码
                long timestamp = System.currentTimeMillis() / 1000;
                String stringToSign = timestamp + "\n" + secret;
                String sign = Codec.hmacSha256(stringToSign, "");
                body = "{\"timestamp\":\"" + timestamp + "\",\"sign\":\"" + sign
                        + "\",\"msg_type\":\"text\",\"content\":{\"text\":\"" + escape(message) + "\"}}";
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder resp = new StringBuilder();
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        resp.append(line);
                    }
                }
            }

            boolean success = code == 200 && resp.toString().contains("\"code\":0");
            LogHelper.i(TAG, "飞书推送: http=" + code + " resp=" + resp);
            return success;
        } catch (Exception e) {
            LogHelper.e(TAG, "飞书推送异常: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * JSON 字符串转义：反斜杠、双引号、换行、回车、制表符。
     */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}