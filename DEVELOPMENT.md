# 开发文档 — 链接检测 (linkcheck)

> 基于 Android 无障碍服务（AccessibilityService）的微信链接风险检测工具

***

## 目录

1. [项目概览](#1-项目概览)
2. [环境与构建](#2-环境与构建)
3. [权限配置](#3-权限配置)
4. [项目结构](#4-项目结构)
5. [核心架构](#5-核心架构)
6. [命令系统参考](#6-命令系统参考)
7. [链接检测流程详解](#7-链接检测流程详解)
8. [扩展开发指南](#8-扩展开发指南)

***

## 1. 项目概览

| 属性 | 值 |
| --- | --- |
| 应用名称 | 链接检测 |
| 应用包名 | com.yaonan.qwdelete |
| 命名空间 | com.yaonan |
| 仓库地址 | <https://github.com/leiyuanye/seleteUrl.git> |
| compileSdk / targetSdk | 34 (Android 14) |
| minSdk | 24 (Android 7.0) |
| Java 版本 | 1.8 |

### 核心依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.tencent:mmkv` | 2.3.0 | 跨进程键值存储，命令应答通道 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.2 | JSON 序列化（debug 节点树输出等） |
| `com.google.android.material` | - | Material Design UI 组件 |

***

## 2. 环境与构建

* **IDE**：Android Studio（推荐 Koala 及以上）
* **JDK**：17+（AGP 8.x 要求）
* **Android SDK**：API 34
* **Gradle**：项目内置 wrapper，无需单独安装
* **本地 SDK 配置**：`local.properties` 中 `sdk.dir` 指向本机 SDK（不入库，换机器需修改）

```bash
# Debug 构建
gradlew.bat assembleDebug
# APK 路径: AndroidDemo/build/outputs/apk/debug/AndroidDemo-debug.apk

# 安装（-t 允许 testOnly 标记包）
adb install -t AndroidDemo/build/outputs/apk/debug/AndroidDemo-debug.apk
```

签名配置见 `testks-sign.gradle`（测试密钥 `testks.jks`）。

***

## 3. 权限配置

### 3.1 AndroidManifest 声明的权限

| 权限 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗（ScreenshotView） |

> 项目已精简：无网络、无前台服务、无短信/截屏权限。

### 3.2 运行时需手动授权

| 权限 | 触发时机 | 代码位置 |
| --- | --- | --- |
| 悬浮窗权限 | 点击「显示」悬浮窗 | `WindowHelper.checkOverlay()` |
| 无障碍服务 | 点击「授权」跳转设置 | `MainActivity → btn_start_a` |
| TXT 文件读取 | 点击「选择」系统文件选择器 | `MainActivity.onActivityResult()`（SAF，持久化授权） |

### 3.3 无障碍服务配置

配置文件：`AndroidDemo/src/main/res/xml/accessibility_config.xml`

```xml
android:accessibilityEventTypes="typeAllMask"      <!-- 监听所有事件 -->
android:accessibilityFeedbackType="feedbackGeneric"
android:accessibilityFlags="flagDefault|flagReportViewIds"
android:canRetrieveWindowContent="true"            <!-- 可读取窗口内容 -->
android:canPerformGestures="true"                  <!-- 可执行手势 -->
```

注册位置：`AndroidManifest.xml` 中的 `SelectToSpeakService`：

* 运行在独立进程 `:BackgroundService`
* 优先级 `10000`（最高）
* 绑定权限 `BIND_ACCESSIBILITY_SERVICE`

***

## 4. 项目结构

```
seleteUrl/
├── build.gradle                          # 根构建文件
├── settings.gradle                       # 模块声明
├── testks-sign.gradle                    # 签名配置
├── testks.jks                            # 测试签名证书
├── README.md                             # 使用说明
├── DEVELOPMENT.md                        # 本文档
└── AndroidDemo/                           # 主应用模块
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/
        │   ├── yaonan/
        │   │   ├── App.java              # Application 入口（MMKV 初始化、进程内共享数据）
        │   │   ├── activity/
        │   │   │   └── MainActivity.java         # 主界面：悬浮窗/无障碍授权、TXT选择、主题
        │   │   ├── view/
        │   │   │   └── ScreenshotView.java       # 悬浮球 + 链接检测主循环（runLinkCheck）
        │   │   └── util/
        │   │       ├── WindowHelper.java         # 悬浮窗管理、屏幕尺寸
        │   │       ├── global/Global.java        # 全局常量（TAG）
        │   │       ├── jna/UI.java               # Toast/主线程调度/MMKV/launchApp
        │   │       ├── codec/                    # Codec(JSON/UUID)、CharsetUtil
        │   │       ├── exception/ExceptionUtil.java
        │   │       ├── json/                     # JSON/Array/Element/JsonParseException
        │   │       └── lang/                     # StringUtil/ThreadUtil/TimeUtil
        │   └── google/android/accessibility/selecttospeak/
        │       └── SelectToSpeakService.java ★   # 核心无障碍服务（命令分发与执行）
        └── res/
            ├── xml/accessibility_config.xml
            ├── layout/
            │   ├── activity_main.xml             # 主界面（4张卡片）
            │   └── layout_screenshot_view.xml    # 悬浮球布局
            └── values/                           # strings/colors/styles/themes/dimens
```

***

## 5. 核心架构

### 5.1 进程模型

```
┌─────────────────────────────────────────────────┐
│  主进程 (com.yaonan.qwdelete)                    │
│                                                  │
│  App.java          ← MMKV 初始化                 │
│  MainActivity      ← UI 控制面板 / 文件选择       │
│  ScreenshotView    ← 悬浮球 + 检测主循环           │
│                                                  │
│  通信方式:                                        │
│  1. announceForAccessibility() — 发送无障碍事件    │
│  2. MMKV — 同步命令应答通道                        │
└─────────────────────────────────────────────────┘
          │
          │ MMKV + AccessibilityEvent
          │
┌─────────▼───────────────────────────────────────┐
│  无障碍进程 (:BackgroundService)                  │
│                                                  │
│  SelectToSpeakService                            │
│  - onAccessibilityEvent() 接收并解析 "#@#" 命令    │
│  - 递归查找 UI 节点（输入框/链接消息/网页文本）       │
│  - 执行手势 (dispatchGesture)                     │
│  - 写响应到 MMKV (response 方法)                   │
└──────────────────────────────────────────────────┘
```

### 5.2 命令通信机制

**异步命令**（`cmd`）：发完即走，不等待结果。

```
ScreenshotView
    │ announceForAccessibility("#@#命令")
    ▼
SelectToSpeakService.onAccessibilityEvent() → 执行 → 完成
```

**同步命令**（`cmdWait`）：等待无障碍服务应答。

```
ScreenshotView
    │ 1. 生成 UUID 作为 msgid
    │ 2. MMKV.putString(msgid, "")
    │ 3. announceForAccessibility(msgid + "{msgid}" + 命令)
    │ 4. 每秒轮询 MMKV.getString(msgid)，最多 maxTimes 次
    ▼
SelectToSpeakService
    │ 1. 按 "{msgid}" 拆分出 msgid 与命令
    │ 2. 执行操作
    │ 3. MMKV.putString(msgid, 应答)   ← response()
    ▼
ScreenshotView 读到应答，返回结果（超时返回 "timeout"）
```

### 5.3 关键节点定位方式

微信节点无稳定 resource-id 可依赖（版本间变动），项目采用**特征结构匹配**：

| 目标 | 定位方式 |
| --- | --- |
| 聊天输入框 | 递归遍历节点树，找「只含 1 个 EditText 子节点的 ScrollView」 |
| 发送按钮 | `findAccessibilityNodeInfosByText("发送")` 且 className 含 `Button` |
| 已发送的链接消息 | `findAccessibilityNodeInfosByText(url)` 后精确比对 text == url |
| 网页风险关键词 | 递归遍历当前页全部节点，对每个 getText() 做 contains 匹配 |

调试技巧：发送 `#@#debug#` 命令可将当前界面完整节点树（类名-文本-边界-viewId-可点击）输出到 logcat（TAG=`mylog`）。

***

## 6. 命令系统参考

无障碍服务通过 `onAccessibilityEvent()` 接收 `TYPE_ANNOUNCEMENT` 事件，解析 `#@#` 前缀命令：

### 链接检测命令

| 命令 | 说明 | 同步 | 应答 |
| --- | --- | --- | --- |
| `#@#发送链接#<url>` | 填入输入框并点击「发送」 | 是 | `success` / `error` |
| `#@#检查链接#<url>` | 点击链接消息，等 4s 扫描风险关键词 | 是 | `normal` / `risk:<关键词>` / `nofind` / `error` |

### 基础操作命令

| 命令 | 说明 | 同步 |
| --- | --- | --- |
| `#@#action#home` | Home 键 | 否 |
| `#@#action#back` | 返回键（网页 → 聊天） | 否 |
| `#@#tap#x,y` | 点击坐标 | 否 |
| `#@#longtap#x,y` | 长按坐标 | 否 |
| `#@#debug#` | 输出节点树到 logcat | 否 |

### 风险关键词

定义在 `SelectToSpeakService.RISK_KEYWORDS`：

```java
private static final String[] RISK_KEYWORDS = {
        "诱导分享", "长按网址", "已停止访问", "谨慎访问", "安全性", "存在风险"
};
```

***

## 7. 链接检测流程详解

主循环位于 `ScreenshotView.runLinkCheck()`：

```
1. 从 MMKV "links_uri" 读取 TXT Uri，readLinks() 解析出链接列表
   （仅保留 http/https 开头的行，按原文顺序逐条执行）

2. 写结果文件表头（check_result.txt，UTF-8 追加模式）

3. 对每条链接 link：
   a. cmdWait("#@#发送链接#" + link, 15)
      → 非 success：记录 [发送失败]，按返回键后 continue
   b. sleep 1500ms（等待消息出现在聊天列表）
   c. cmdWait("#@#检查链接#" + link, 25)
      → risk:xxx：记录 [风险-xxx]，Toast 弹窗提醒（长时）
      → normal：记录 [正常]
      → 其他（nofind/error/timeout）：记录 [异常-原因]
   d. sleep 500ms → cmd("#@#action#back") → sleep 2000ms（回聊天界面）

4. 弹窗汇总：共 N 条，风险 M 条；写结果文件结束行
```

### 结果文件

* 路径：`Android/data/com.yaonan.qwdelete/files/check_result.txt`
* 格式（UTF-8，追加）：

```
===== 检测开始 2026-09-22 10:00:00，共3条 =====
[正常] https://example.com/1
[风险-诱导分享] https://example.com/2
[发送失败][timeout] https://example.com/3
===== 检测结束 2026-09-22 10:05:00，风险1/3 =====
```

### 状态与存储键

| 存储 | 用途 |
| --- | --- |
| MMKV `"links_uri"` | 已选 TXT 文件的 content Uri |
| MMKV msgid | 同步命令应答（1s 轮询，3600s 过期） |
| MMKV `"journal_theme"` | 手账风主题开关 |
| `SelectToSpeakService.isRunning` | 服务可用标志（volatile） |
| `ScreenshotView.loopThread` | 检测主循环线程（null=未运行） |

***

## 8. 扩展开发指南

### 8.1 添加新的无障碍命令

1. 在 `SelectToSpeakService.onAccessibilityEvent()` 中添加分支：

```java
} else if (cmd.startsWith("#@#我的命令#")) {
    String param = cmd.substring("#@#我的命令#".length());
    doSomething(param, msgid);   // 同步命令把 msgid 传入，结束时 response(msgid, "结果")
}
```

2. 在 `ScreenshotView` 中调用：

```java
cmd("#@#我的命令#参数");                    // 异步
String res = cmdWait("#@#我的命令#参数", 15); // 同步，最多等15秒
```

### 8.2 增加风险关键词

编辑 `SelectToSpeakService.RISK_KEYWORDS` 数组即可，命中任意关键词即判定风险并弹窗。

### 8.3 调整检测节奏

`runLinkCheck()` 中各 sleep 值：

| 位置 | 当前值 | 作用 |
| --- | --- | --- |
| 发送成功后 | 1500ms | 等待消息出现在聊天列表 |
| 点击链接后（服务内） | 4000ms | 等待网页加载完成 |
| 打开检查后返回前 | 500ms | 确保结果已读取 |
| 返回聊天后 | 2000ms | 等待聊天界面恢复 |

### 8.4 更换目标应用（如企业微信）

节点定位逻辑集中在 `SelectToSpeakService` 的 `findChatEditText()` / `sendLink()` / `checkLink()`，按目标应用的界面特征调整匹配规则即可；主循环无需改动。

### 8.5 版本发布

1. 修改 `strings.xml` 的 `app_name` 与主界面 `btn_about` 文本中的版本号
2. `gradlew.bat assembleDebug` 构建
3. 真机验证：选择 TXT → 悬浮窗 → 无障碍 → 微信聊天 → 开始检测
