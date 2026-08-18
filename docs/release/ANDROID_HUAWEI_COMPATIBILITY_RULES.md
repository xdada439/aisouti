# AI 搜题 App 安卓与华为适配规则

## 总目标

本 App 最终需要兼容主流安卓设备和华为设备，尤其要保证以下能力稳定：

1. 首页正常打开。
2. 悬浮窗正常显示。
3. 无障碍服务正常开启和工作。
4. 截图 / OCR / 无障碍节点提取链路不崩溃。
5. 搜题结果可以稳定显示。
6. 后台控额和授权状态可以正常同步。
7. 在线更新能力可用。
8. 华为设备上不能因为缺少 Google 服务导致核心功能不可用。

## 不允许依赖的能力

除非后续明确决定上架 Google Play，否则客户端核心功能不得强依赖：

- Google Play Services
- Google Play Billing
- Firebase Cloud Messaging
- Google Play In-App Update
- 仅 GMS 可用的 API

原因：
华为设备可能没有 GMS，直接依赖这些能力会导致部分华为设备不可用。

## 推荐方向

核心能力尽量使用标准 Android API：

- AccessibilityService
- SYSTEM_ALERT_WINDOW 悬浮窗权限
- MediaProjection 或系统截图能力
- OCR 作为兜底或校验器
- HTTPS 请求后台
- 本地 Room / SharedPreferences / DataStore 存储配置
- DownloadManager 或自定义下载器下载更新包
- FileProvider / PackageInstaller / 安装 Intent 触发 APK 安装

## 权限适配要求

必须重点检查：

1. Android 8+ 通知渠道 NotificationChannel。
2. Android 8+ 安装未知应用权限。
3. Android 10+ 存储分区适配，避免乱读写外部存储。
4. Android 12+ 悬浮窗、前台服务、后台启动限制。
5. Android 13+ 通知权限 POST_NOTIFICATIONS。
6. Android 14/15 对前台服务、后台行为、隐私权限的兼容。
7. 华为 / HarmonyOS 对自启动、后台保活、悬浮窗、无障碍服务的限制。

## 华为专项要求

华为设备必须重点测试：

1. 首次启动是否崩溃。
2. 开启无障碍服务后是否能稳定获取节点。
3. 悬浮窗是否显示在目标 App 上层。
4. 极简模式悬浮点是否可点击。
5. 点击搜题后是否能触发截图或节点提取。
6. OCR 兜底是否可用。
7. 后台授权状态是否能正常请求。
8. 在线更新下载 APK 是否成功。
9. 更新安装时是否能跳转到系统安装界面。
10. 更新后账号状态和剩余次数是否保留。

## 测试机型建议

至少覆盖：

- 普通安卓 Android 10
- 普通安卓 Android 12
- 普通安卓 Android 13/14
- 华为 EMUI 10/11
- 华为 HarmonyOS 2/3/4/5 中至少一台

## 禁止破坏的核心链路

任何兼容性改造不得破坏：

1. 包名 com.lk.studyassistant.quantum。
2. 无障碍节点优先提取候选题块。
3. OCR 作为当前屏幕校验器或兜底。
4. 后台控额方向。
5. 客户端不硬编码 API Key。
6. 客户端首页不暴露 API 配置。
7. 截图、裁剪、Base64、请求链路。
8. 悬浮窗点击搜题链路。

## 构建与签名要求

1. 正式更新包必须使用同一个 packageName。
2. 正式更新包必须使用同一个签名证书。
3. versionCode 必须递增。
4. versionName 用于展示给用户。
5. debug 包不能作为正式在线更新包。
6. 如果签名不一致，Android 会拒绝覆盖安装。

## 后台兼容提醒

如果后续升级后台、授权接口、版本更新接口，必须考虑老版本客户端：

1. 不得让老版本客户端激活失效。
2. 不得让旧账号体系突然无法登录。
3. 不得直接废弃旧授权校验接口。
4. 新接口上线前必须保留兼容字段。
5. 后台改造时必须记录版本兼容表。
