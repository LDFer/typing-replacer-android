# 打字替换（Android 无障碍版）

一个 Android 小工具：你设置好替换规则后，在任意 App 的输入框里打字，它会在输入框里**强制覆盖**为替换后的文字。

例如规则：

| 原词 | 替换为 |
|---|---|
| 我 | 本喵 |
| 你 | 你喵 |

当你在微信/QQ/短信/备忘录里输入“我 今天很开心”，输入框里会实时变成“本喵 今天很开心”，再点发送时发出去的就是替换后的内容。

## 实现方式

- 使用 Android **无障碍服务（AccessibilityService）**
- 监听 `TYPE_VIEW_TEXT_CHANGED`
- 拿到当前输入框完整文字，按规则替换
- 通过 `ACTION_SET_TEXT` 把替换后的文字强制写回输入框
- 保留你原来习惯的输入法，不需要切换到本 App 的键盘

这种方式适合自己安装、自己使用。若打算上架 Google Play，无障碍服务的用途可能受平台政策限制，请先查阅相关规则。

## 项目结构

```text
typing-replacer-android/
├── app/src/main/java/com/example/typingreplacer/
│   ├── MainActivity.kt          # 设置界面：编辑规则、测试、开启服务
│   ├── GlobalReplaceService.kt  # 无障碍服务：全局实时替换
│   ├── ReplacementRule.kt       # 规则数据结构
│   ├── ReplacementRepository.kt # 规则存储（SharedPreferences + JSON）
│   └── TextReplacer.kt          # 纯文本替换引擎
├── app/src/main/res/xml/
│   └── accessibility_service_config.xml
└── app/src/main/AndroidManifest.xml
```

## 怎么编译

1. 安装 Android Studio（带 Android SDK）。
2. 用 Android Studio 打开 `typing-replacer-android` 目录。
3. 等 Gradle 同步完成，点 Run 运行到手机，或 Build > Build APK(s) 生成安装包。
4. 如果你已单独安装 Gradle，也可以在项目根目录运行：

```bash
gradle assembleDebug
```

生成的 APK 在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

> 本项目没有附 Gradle Wrapper 的二进制 jar，首次打开推荐直接用 Android Studio 自带的 Gradle 完成同步和构建。

## 在 GitHub 上自动构建 APK

仓库已经配置了 GitHub Actions：

```text
.github/workflows/build-apk.yml
```

每次推送到 `main`，或在 Actions 页面手动点击 `Run workflow`，都会自动：

1. 安装 JDK 17 和 Gradle 8.7
2. 执行 `gradle assembleDebug`
3. 把生成的 APK 作为 Artifact 上传

构建完成后，去仓库的 **Actions** 页面，点进最近一次运行，在 **Artifacts** 里下载 `typing-replacer-apk` 即可。

## 怎么使用

1. 安装并打开 App。
2. 点“开启 / 管理无障碍服务”，在系统设置里找到“打字替换”，打开开关。
3. 回到 App，编辑替换规则：
   - 勾选框：是否启用这条规则
   - 原词：要替换的原文
   - 替换为：替换后的文字
   - 点“保存规则”
4. 切到任意输入框打字，替换会实时生效。

## 注意事项

- 需要 Android 6.0（API 23）以上；项目最低版本设为 API 26。
- Android 13 及以上如果从侧载安装，可能在应用详情的“允许受限设置”里需要先打开，才能启用无障碍服务。
- 部分银行/密码输入框或安全键盘可能不允许无障碍修改，这是系统安全限制。
- 如果某些 App 不响应 `ACTION_SET_TEXT`，会表现为不替换；目前属于常见兼容性限制。
- 当前版本是“完整文本替换”，会替换输入框里的所有命中文字；你还可以继续加规则、开关规则。
- 纯文本替换不支持正则；如需要正则/首字母/整词匹配可以继续扩展 `TextReplacer`。
