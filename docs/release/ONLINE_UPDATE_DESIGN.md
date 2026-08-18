# AI 搜题 App 在线更新设计

## 目标

本 App 需要支持非应用市场分发场景下的在线更新能力。

场景：
1. 用户已经安装旧版本 APK。
2. 后台上传新版本 APK。
3. 客户端检查到新版本。
4. 用户看到更新提示。
5. 用户下载新 APK。
6. 系统弹出安装确认。
7. 用户确认后覆盖安装。
8. 更新后保留账号、授权状态、剩余次数、本地设置。

## 重要前提

Android 普通 App 不能静默安装 APK。
即使 App 下载好了 APK，最终也需要用户确认安装。
如果设备未允许当前 App 安装未知应用，需要引导用户去设置中开启权限。

## 后台版本接口建议

客户端请求：

GET /api/app/version/check

请求参数建议：

- app_key：应用标识，例如 ai_souti
- package_name：包名，例如 com.lk.studyassistant.quantum
- version_code：当前客户端 versionCode
- version_name：当前客户端 versionName
- channel：stable / beta / internal
- device_brand：设备品牌
- device_model：设备型号
- android_version：安卓版本
- huawei_or_harmony：是否华为 / HarmonyOS

返回 JSON 建议：

{
  "has_update": true,
  "latest_version_code": 12,
  "latest_version_name": "1.2.0",
  "min_supported_version_code": 8,
  "force_update": false,
  "apk_url": "https://example.com/downloads/ai-souti-1.2.0.apk",
  "apk_sha256": "APK文件SHA256",
  "apk_size": 35200000,
  "release_notes": "修复华为设备悬浮窗兼容问题，优化搜题结果显示。",
  "published_at": "2026-05-13 12:00:00",
  "signature_digest": "正式签名证书摘要",
  "download_page_url": "备用下载页"
}

## 客户端更新流程

1. App 启动后延迟检查更新。
2. 用户中心或设置页提供"检查更新"按钮。
3. 如果没有更新，提示"已是最新版本"。
4. 如果有普通更新，弹窗提示：
   - 新版本号
   - 更新内容
   - 包大小
   - 稍后更新
   - 立即更新
5. 如果是强制更新：
   - 只允许"立即更新"或"退出应用"
   - 但必须保留异常提示，避免用户卡死。
6. 下载 APK。
7. 下载完成后校验 SHA256。
8. 校验 packageName。
9. 校验 versionCode 是否更高。
10. 校验签名证书摘要是否符合预期。
11. 触发系统安装界面。
12. 用户确认安装。

## 安装方式

非应用市场分发建议：

- 使用 DownloadManager 或安全下载器下载 APK。
- 使用 FileProvider 或 PackageInstaller / 系统安装 Intent 打开 APK。
- Android 8+ 检查 canRequestPackageInstalls。
- 如果没有安装未知应用权限，引导用户打开当前 App 的"允许安装未知应用"。

## 安全要求

1. apk_url 必须使用 HTTPS。
2. 必须校验 SHA256。
3. 必须校验包名。
4. 必须校验版本号递增。
5. 必须校验签名证书摘要。
6. 下载失败要允许重试。
7. 安装失败要提示用户手动下载安装。
8. 不得从不可信 URL 下载 APK。
9. 不得自动静默安装。
10. 不得绕过系统安装确认。

## 后台上传功能要求

后台应用管理页面应支持：

1. 上传 APK 文件。
2. 自动读取或手动填写 versionCode。
3. 自动读取或手动填写 versionName。
4. 填写更新公告。
5. 选择是否强制更新。
6. 选择发布渠道 stable / beta / internal。
7. 计算 APK SHA256。
8. 保存 APK 文件大小。
9. 保存发布时间。
10. 保留历史版本。
11. 支持回滚到旧版本。
12. 支持停用某个版本。

## 客户端展示要求

首页不应该频繁弹更新。
建议：

- 启动后静默检查。
- 有更新时在用户中心或顶部状态显示"小红点"。
- 重要更新再弹窗。
- 强制更新才阻断使用。

## 和授权后台的关系

在线更新不能破坏授权体系：

1. 更新接口和授权接口分开。
2. 授权失败不能误判为更新失败。
3. 更新失败不能导致账号退出。
4. 后台升级时必须兼容老版本客户端。
5. 老版本客户端即使不支持新更新字段，也应能正常解析基础字段。

## 第一阶段最小可用方案

第一版可以先做：

1. 后台手动上传 APK。
2. 客户端启动检查 versionCode。
3. 发现新版本后弹窗。
4. 点击立即更新后浏览器打开下载页或直接下载 APK。
5. 用户手动安装。

第二阶段再做：

1. App 内下载进度。
2. SHA256 校验。
3. 安装权限引导。
4. 强制更新。
5. 多渠道灰度发布。
