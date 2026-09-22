# 链接检测 (linkcheck)

基于 Android 无障碍服务的微信链接风险检测工具：逐个发送 TXT 中的链接到微信聊天，自动打开并扫描页面风险关键词（诱导分享、长按网址等），命中即弹窗提醒，结果保存到本地文件。

## 功能特性

### 核心功能
- **TXT 批量链接检测**：在主页选择 TXT 文件（每行一条链接），逐条自动发送 → 打开 → 检测 → 返回，全程无需人工干预
- **风险关键词扫描**：页面中出现「诱导分享」「长按网址」「已停止访问」「谨慎访问」「安全性」「存在风险」任一关键词即判定为风险链接，实时弹窗提醒
- **进度实时显示**：悬浮球显示检测进度（如 `5/20`），随时点击「停止」中断
- **结果文件**：每条链接的检测结果（正常/风险-关键词/发送失败/异常）追加保存到 `check_result.txt`

### 界面
- **卡片式主页**：悬浮窗、无障碍授权、检测文件选择、使用说明
- **双主题**：默认清新绿色风格 / 手账风（双击底部开发者名称切换），状态持久化

### 技术特点
- 基于 `GestureDescription` + `dispatchGesture()` 系统级手势模拟
- 无障碍服务独立进程（`:BackgroundService`），主进程通过 `announceForAccessibility` + MMKV 与其通信
- 节点递归遍历定位微信聊天输入框与链接消息，兼容微信 WebView 页面文本扫描
- 极简依赖：Material + Jackson + MMKV，无网络权限

## 环境要求

- Android 7.0+（minSdk 24，targetSdk 34）
- 已安装微信
- 允许悬浮窗权限 + 开启无障碍服务

## 快速开始

### 编译构建

```bash
git clone https://github.com/leiyuanye/seleteUrl.git
cd seleteUrl

# Windows
gradlew.bat assembleDebug
# 生成 APK: AndroidDemo/build/outputs/apk/debug/AndroidDemo-debug.apk
```

或使用 Android Studio 打开项目，`Build → Build APK(s)`。

### 安装

```bash
adb install -t AndroidDemo/build/outputs/apk/debug/AndroidDemo-debug.apk
```

### 使用步骤

1. **准备 TXT 文件**：每行一条链接（`http://` 或 `https://` 开头），保存为 `.txt`
2. **选择文件**：打开 App，点击「检测文件」卡片中的「选择」按钮，选中 TXT，卡片显示「已加载 N 条链接」
3. **显示悬浮窗**：点击「悬浮窗」卡片中的「显示」按钮（首次需授权悬浮窗权限）
4. **开启无障碍**：点击「无障碍/已下载的服务」卡片中的「授权」按钮，在系统设置中开启「链接检测模拟点击」
5. **进入微信聊天**：手动打开微信，进入任意一个聊天界面（建议发给"文件传输助手"或自己的小号）
6. **开始检测**：点击悬浮球「开始」——脚本将逐条发送链接、打开并检测，发现风险链接时弹窗提醒
7. **查看结果**：检测完成后弹窗汇总；详细结果在 `Android/data/com.yaonan.qwdelete/files/check_result.txt`

### 检测流程

```
读取TXT第N条链接 → 自动填入输入框 → 点击发送
    → 等待消息出现 → 点击打开链接 → 等待页面加载
    → 扫描页面文本中的风险关键词
        → 命中：弹窗提醒 + 记录 [风险-关键词]
        → 未命中：记录 [正常]
    → 返回键回到聊天 → 处理下一条 → … → 汇总
```

### 主题切换

双击首页底部「生活就是敲敲敲」标签，在默认风格和手账风之间切换。

## 项目结构

```
seleteUrl/
├── AndroidDemo/                        # 主模块
│   └── src/main/
│       ├── AndroidManifest.xml         # 应用配置（仅悬浮窗权限）
│       ├── java/com/
│       │   ├── yaonan/
│       │   │   ├── App.java            # Application 入口 + MMKV 初始化
│       │   │   ├── activity/
│       │   │   │   └── MainActivity.java       # 主界面（文件选择/授权/主题）
│       │   │   ├── view/
│       │   │   │   └── ScreenshotView.java     # 悬浮球 + 链接检测主循环
│       │   │   └── util/               # 工具集（MMKV/线程/JSON/编解码等）
│       │   └── google/android/accessibility/selecttospeak/
│       │       └── SelectToSpeakService.java   # 核心无障碍服务（发送/检查命令）
│       └── res/
│           ├── layout/                 # 主界面、悬浮窗布局
│           ├── values/                 # 颜色、样式、字符串
│           └── xml/accessibility_config.xml    # 无障碍服务配置
├── build.gradle                        # 项目构建配置
├── README.md                           # 本文档
└── DEVELOPMENT.md                      # 开发文档
```

## 技术依赖

| 依赖 | 用途 |
|------|------|
| Material Components | Material Design UI |
| Jackson Databind 2.22.2 | JSON 序列化 |
| MMKV 2.3.0 | 跨进程键值存储（命令应答通道） |

## 注意事项

- 每次 APK 更新后，需在系统设置中**关闭再开启无障碍服务**才能加载新代码
- 检测期间请保持手机亮屏、停留在微信聊天界面，勿切走
- 发送目标建议使用文件传输助手或自己的小号，避免打扰他人
- 本工具仅用于检测自己拥有的链接在微信内的打开状态，请遵守微信使用规范
- 签名密钥 `testks.jks` 为测试密钥，生产环境请替换
