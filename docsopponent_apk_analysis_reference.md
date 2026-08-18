**只读分析报告**

范围：已先读取 [ANALYSIS_NOTES.md](</D:/拆解分析/medical_apk_analysis/ANALYSIS_NOTES.md>)，随后分析 [jadx_out](</D:/拆解分析/medical_apk_analysis/jadx_out>) 与 [raw_apk](</D:/拆解分析/medical_apk_analysis/raw_apk>)。未修改文件，未生成代码。

**1. APK 主识别链路**

强证据链路是：

悬浮窗点击 -> 无障碍截图 -> 本地 ML Kit OCR -> 上传服务器查题 -> 显示服务器返回 options。

证据：

- 业务包是 `com.testten.project`：Manifest 包名见 [AndroidManifest.xml](</D:/拆解分析/medical_apk_analysis/jadx_out/resources/AndroidManifest.xml:7>)。
- 悬浮窗服务：`com.testten.project.FloatingWindowService`，Manifest 注册见 [AndroidManifest.xml](</D:/拆解分析/medical_apk_analysis/jadx_out/resources/AndroidManifest.xml:83>)。
- 无障碍服务：`com.testten.project.MyAccessibilityService`，Manifest 注册并绑定 `android.accessibilityservice.AccessibilityService`，见 [AndroidManifest.xml](</D:/拆解分析/medical_apk_analysis/jadx_out/resources/AndroidManifest.xml:88>)。
- 悬浮窗按钮触发 `takeScreenshot()`：普通模式按钮见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:260>)，点击后调用见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:269>)；极简模式点击见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:221>) 和 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:229>)。
- `takeScreenshot()` 只转到 `performAccessibilityScreenshot()`：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:275>)。
- `performAccessibilityScreenshot()` 调用 `MyAccessibilityService.takeScreenshotWithAccessibility()`：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:306>)。
- `MyAccessibilityService.takeScreenshotWithAccessibility()` 在 Android 11+ 调用系统 `AccessibilityService.takeScreenshot(...)`：见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:142>) 和 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:145>)。
- 截图成功后 `Bitmap.wrapHardwareBuffer(...)` 转为 Bitmap：见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:150>)。
- 截图保存前先转 Base64：`mBase64Data = bitmapToBase64(bitmap)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:329>)；Base64 前缀是 `data:image/png;base64,`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:351>)。
- OCR 使用本地 ML Kit 中文识别：`TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:77>)。
- OCR 输入来自截图文件 Uri：`InputImage.fromFilePath(this, uri)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:361>)。
- OCR 文本清洗后作为 `question` 上传：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:364>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:373>)。
- 服务器接口是 `/answer/question/search`：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:405>)。
- Base URL 是 `https://5g618.com/`：见 [MyApplication.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyApplication.java:14>)。
- 客户端解析 `InfoBean`，显示 `data.options`：解析见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:420>)，取 options 见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:433>)，显示见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:457>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:462>)。

**2. 截图链路判断**

结论：截图来自 `AccessibilityService.takeScreenshot`，不是业务代码里的 `MediaProjection`。

证据：

- 实际调用点是 `takeScreenshot(0, getMainExecutor(), new AccessibilityService.TakeScreenshotCallback(){...})`，见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:145>)。
- `FloatingWindowService.takeScreenshot()` 没有 MediaProjection 逻辑，只调用 `performAccessibilityScreenshot()`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:275>)。
- 业务代码中未发现 `MediaProjectionManager.getMediaProjection`、`createVirtualDisplay`、`ImageReader.newInstance`、`setOnImageAvailableListener`。
- `MyAccessibilityService` 虽然声明/import 了 `VirtualDisplay` 和 `ImageReader`，见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:7>)、[MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:22>)，但只在 `onDestroy()` 释放/关闭，见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:106>) 和 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:111>)，未看到创建或消费图像帧。
- Manifest 给 `FloatingWindowService` 标了 `android:foregroundServiceType="mediaProjection"`，见 [AndroidManifest.xml](</D:/拆解分析/medical_apk_analysis/jadx_out/resources/AndroidManifest.xml:86>)，但这是声明层证据，不等于业务实现中使用了 MediaProjection。
- 第三方库 `com.pedro.rtplibrary.base.DisplayBase` 中存在 MediaProjection 代码，但业务包 `com.testten.project` 没有引用它；不能把它算作本 APK 的搜题截图链路。

是否存在第二套截图方案：无强证据。代码中的第二路径不是截图方案，而是截图/OCR失败后的无障碍文字读取 fallback：`onError()` 调 `getScreenTextWithAccessibility()`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:316>)；OCR 空或过短也 fallback，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:365>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:371>)。

`canTakeScreenshot` 判断：当前 jadx 解出的 XML **存在** `android:canTakeScreenshot="true"`，见 [accessibility_service_config.xml](</D:/拆解分析/medical_apk_analysis/jadx_out/resources/res/xml-v22/accessibility_service_config.xml:10>)。因此，本次分析中不存在“代码调用 takeScreenshot 但 XML 没有 canTakeScreenshot”的矛盾。`ANALYSIS_NOTES.md` 中“未看到 canTakeScreenshot”的记录应以当前实际文件证据修正。

**3. OCR 链路判断**

结论：OCR 是本地 ML Kit 中文 OCR；assets/lib 中确实带有 ML Kit OCR 模型和 native pipeline。

证据：

- 业务代码直接 import：
  - `InputImage`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:31>)
  - `TextRecognition`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:33>)
  - `TextRecognizer`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:34>)
  - `ChineseTextRecognizerOptions`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:35>)
- 初始化中文识别器：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:77>)。
- 处理截图文件：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:361>)。
- raw APK assets 中存在 `assets/mlkit-google-ocr-models/...`，包括中文/拉丁 OCR、tflite、LabelMap 等模型文件，例如：
  - `raw_apk\assets\mlkit-google-ocr-models\gocr\layout\line_clustering_custom_ops\model.tflite`
  - `raw_apk\assets\mlkit-google-ocr-models\taser\detector\rpn_text_detector_mobile_space_to_depth_quantized_mbv2_v1.tflite`
  - `raw_apk\assets\mlkit-google-ocr-models\gocr\gocr_models\line_recognition_legacy_mobile\Hani_ctc\optical\lstm_model.fb`
- native lib 中存在 `libmlkit_google_ocr_pipeline.so`，多 ABI 均有，例如 `raw_apk\lib\arm64-v8a\libmlkit_google_ocr_pipeline.so`。

排除项：

- 业务包 `com.testten.project` 中未发现 `opencv`、`tesseract`、`paddle`、`onnx`、`TensorFlow Interpreter` 等额外 OCR/图像识别调用。
- `libpl_droidsonroids_gif.so` 是 GIF 相关库，不是 OCR 业务证据。
- tflite 模型位于 `mlkit-google-ocr-models` 下，应归为 ML Kit bundled OCR 资源，不能解释为自研题库或独立 OCR 引擎。

**4. 服务器查题链路**

上传参数强证据：

- `uuid`：`map.put("uuid", MainActivity.uuid)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:399>)。
- `question`：OCR 文本或无障碍文本，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:400>)。
- `imageBase64`：截图 Base64，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:401>)。
- `type` 固定为 `"4"`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:402>)。
- headers：`sign = MD5(uuid + acc + timestamp)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:405>)；`acc` 常量见 [MainActivity.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MainActivity.java:74>)。

是否上传截图 Base64：

- 截图成功路径会上传：`mBase64Data = bitmapToBase64(bitmap)` 后进入 OCR 和 `requestData()`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:329>)。
- 无障碍文字 fallback 路径会把 `mBase64Data = null` 后上传纯文本，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:557>)。

是否上传 OCR 文本：

- 是。OCR 的 `visionText.getText()` 清洗后作为 `requestData(strReplace)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:364>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:373>)。

服务器返回内容：

- `InfoBean` 字段包括 `code`、`message`、`data`，见 [InfoBean.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/InfoBean.java:7>) 到 [InfoBean.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/InfoBean.java:9>)。
- `DataBean` 包括 `options`、`ticket`、`expireDate`，见 [InfoBean.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/InfoBean.java:35>) 到 [InfoBean.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/InfoBean.java:38>)。
- 客户端主要显示 `options` 拼接结果；没有看到本地判题逻辑。见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:444>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:462>)。

**5. 无障碍文字读取链路**

结论：业务代码只证明了递归读取 `node.getText()`，没有证据显示读取 `contentDescription`、`viewIdResourceName`、`extras` 或枚举窗口。

证据：

- 获取根节点：`getRootInActiveWindow()`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:515>)。
- 递归入口：`extractTextFromNode(rootInActiveWindow, sb)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:528>)。
- 读取节点包名并过滤系统包：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:582>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:586>)。
- 读取 `node.getText()`：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:589>)。
- 遍历 child：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:597>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:603>)。
- `node.refresh()` 仅在 retry 前几次调用，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:576>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:577>)。

排除项：

- 业务包搜索未发现 `getContentDescription`。
- 未发现 `getViewIdResourceName`。
- 未发现 `getExtras`。
- 未发现 `findAccessibilityNodeInfosByText` / `findAccessibilityNodeInfosByViewId`。
- 未发现 `getWindows` / `AccessibilityWindowInfo`。

**6. 其他可能输入源**

无强证据发现其他输入源：

- 未发现业务代码读取剪贴板：未命中 `ClipboardManager` / `getPrimaryClip`。
- 未发现 WebView/JS 页面读取：未命中 `WebView` / `evaluateJavascript`。
- 未发现输入法服务：Manifest 未注册 `InputMethodService`。
- 未发现通知监听：Manifest 未注册 `NotificationListenerService`。
- 未发现本地题库数据库：`raw_apk` 未发现 `.db` / `.sqlite` / `.dat` 数据库文件。
- `raw_apk\res\raw` 不存在。
- assets 主要是 `dexopt/baseline.prof`、字体 `fonts/gd.ttf`、ML Kit OCR 模型；没有看到题库资源。
- APK 包含 AndroidX Room/SQLite 的版本元数据，例如 `META-INF/androidx.room_room-runtime.version`、`META-INF/androidx.sqlite_sqlite.version`，但业务包没有 Room/SQLite 数据访问代码证据，不能据此认定有本地题库。

**7. 防检测真实实现**

结论：防检测开关实际控制“让自身悬浮窗 Surface 尝试跳过截图/录屏”和一些隐藏/关闭悬浮窗交互；没有证据表明能越过目标 App 的 `FLAG_SECURE` 或安全 Surface。

证据：

- 开关 key：`PREF_ANTI_DETECTION = "anti_detection"`，见 [MainActivity.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MainActivity.java:69>)。
- 默认开启：`getBoolean(PREF_ANTI_DETECTION, true)`，见 [MainActivity.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MainActivity.java:240>)。
- UI 开关保存到 `SharedPreferences`，见 [MainActivity.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MainActivity.java:229>) 到 [MainActivity.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MainActivity.java:249>)。
- 创建悬浮窗后如果开启防检测，则调用 `setSkipScreenshot(this.easyWindow)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:151>)。
- `setSkipScreenshot` 限 Android 12+：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:157>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:160>)。
- 通过 `HiddenApiBypass.addHiddenApiExemptions("")`、反射 `View.getViewRootImpl()`、取 `mSurfaceControl`、调用 `SurfaceControl.Transaction.setSkipScreenshot(surfaceControl, true)`：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:177>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:193>)。
- 调用成功后 `transaction.apply()`：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:202>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:204>)。
- 悬浮窗是通过 `EasyWindow.with(MyAccessibilityService.getInstance())` 创建，第三方 `EasyWindow(AccessibilityService)` 在 Android 22+ 使用 type `2032`，即 `TYPE_ACCESSIBILITY_OVERLAY`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:130>) 和 [EasyWindow.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/hjq/window/EasyWindow.java:146>) 到 [EasyWindow.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/hjq/window/EasyWindow.java:175>)。
- 长按透明隐藏：普通悬浮窗长按将 `setWindowAlpha(0.06f)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:130>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:145>)；极简答案区长按也有同样逻辑，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:239>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:254>)。
- 音量下键双击关闭：`onKeyEvent()` 捕获 KEYCODE_VOLUME_DOWN，见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:45>) 到 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:48>)；双击后 `dismissFloatWindow()`，见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:63>) 到 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:69>)。
- 回收所有悬浮窗：`EasyWindowManager.recycleAllWindow()`，见 [MyAccessibilityService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MyAccessibilityService.java:82>)；主界面关闭按钮也调用，见 [MainActivity.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/MainActivity.java:151>)。
- 无障碍服务销毁时回收悬浮窗：见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:644>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:657>)。

**8. 已确认的证据**

强证据：

- 截图链路是 `AccessibilityService.takeScreenshot`。
- XML 存在 `android:canTakeScreenshot="true"`。
- OCR 是本地 ML Kit 中文 OCR。
- 截图 Base64 与 OCR/无障碍文本会上传到 `/answer/question/search`。
- 返回 `InfoBean.data.options` 后客户端仅拼接显示答案。
- 无障碍 fallback 递归读取 `node.getText()`。
- 防检测开关控制自身悬浮窗 `setSkipScreenshot` 尝试，以及透明/关闭/回收悬浮窗逻辑。

弱证据：

- Manifest 中 `foregroundServiceType="mediaProjection"` 可能是历史遗留或权限声明冗余；业务代码没有对应 MediaProjection 实现。
- `MyAccessibilityService` 中残留 `VirtualDisplay` / `ImageReader` 字段，但未见创建和使用，不能认定为第二套截图方案。

无证据：

- 无证据表明使用业务层 MediaProjection 截图。
- 无证据表明使用 OpenCV / Tesseract / Paddle / ONNX 作为业务 OCR。
- 无证据表明读取剪贴板、WebView JS、输入法、通知。
- 无证据表明内置本地题库数据库。
- 无证据表明读取 `contentDescription`、`viewIdResourceName`、`extras` 或枚举 `AccessibilityWindowInfo`。

**9. FLAG_SECURE / 安全 Surface 结论**

未发现它能越过目标 App `FLAG_SECURE` / 安全 Surface 的证据。

已发现的 `setSkipScreenshot` 是作用在**自身悬浮窗的 SurfaceControl** 上：代码从悬浮窗 contentView 获取 `ViewRootImpl.mSurfaceControl`，再对这个 `surfaceControl` 调 `setSkipScreenshot(..., true)`，见 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:162>) 到 [FloatingWindowService.java](</D:/拆解分析/medical_apk_analysis/jadx_out/sources/com/testten/project/FloatingWindowService.java:193>)。这只能证明它试图让自己的悬浮窗不进入截图/录屏，不能证明它能读取或绕过目标 App 的安全 Surface。