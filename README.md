# 打字替换 V2（Android 无障碍版）

这是对原项目的稳定性重构版本。目标仍然是：

- 不更换用户原来的输入法；
- 在任意普通文本输入框中实时替换；
- 不依赖 Activity 必须在前台；
- 尽可能兼容部分应用/ROM 停止上报 `TYPE_VIEW_TEXT_CHANGED` 的情况。

## V2 为什么重构

旧版本同时存在：

- `AccessibilityService`
- 独立 `KeepAliveService`
- 常驻前台通知
- 每 200ms 全树扫描第一个 `Editable`
- 复杂的“锁定替换”状态

常驻通知只能说明 `KeepAliveService` 在运行，不能证明真正负责替换的
`GlobalReplaceService` 仍然在接收无障碍事件。

Android 的 `AccessibilityService` 本身由系统绑定和管理。V2 因此不再把前台
通知当作保活手段，而是直接诊断并强化无障碍服务本身。

## V2 架构

```text
Android Accessibility Framework
              │
              ▼
     GlobalReplaceService
       │             │
       │             └── 0.7s focused-node compatibility scan
       │
       ├── TYPE_VIEW_TEXT_CHANGED fast path
       ├── explicit FOCUS_INPUT resolution
       ├── per-input session / self-write guard
       ├── in-memory cached rules
       └── ACTION_SET_TEXT + cursor restore
              │
              ▼
       ServiceRuntimeState
              │
              ▼
          MainActivity
   显示：心跳 / 事件 / 输入框 / 写入状态
```

## 相比旧版的主要变化

1. 删除 `KeepAliveService` 和常驻通知依赖。
2. 不再扫描“页面中的第一个输入框”，只处理当前获得输入焦点的节点。
3. 文本事件优先；事件异常时才低频扫描焦点输入框。
4. 规则缓存到内存，保存规则后自动刷新。
5. 替换引擎改为单次最长匹配，避免规则链式级联。
6. 使用输入会话防止 `ACTION_SET_TEXT` 产生的事件再次处理旧结果。
7. 删除不稳定的“发送时替换”和“锁定替换”功能。
8. 主界面增加后台诊断：
   - 服务是否连接
   - 最近心跳
   - 最近无障碍事件
   - 是否找到焦点输入框
   - 最近 ACTION_SET_TEXT 是否成功

## 如何判断后台 5 秒后失效的真正原因

打开 App 查看诊断：

### A. 心跳也停止

说明 `GlobalReplaceService` 本身没有继续执行。可能是：

- 无障碍服务被系统解绑；
- 厂商 ROM 冻结应用；
- 系统无障碍框架异常。

这时前台通知是否存在没有决定意义。

### B. 心跳正常，但“最近系统事件”停止

说明服务还活着，但目标 App/ROM 不再发送文本变化事件。

V2 的兼容扫描会每 0.7 秒直接读取当前焦点输入框，因此这种情况仍有机会继续替换。

### C. 心跳、事件都正常，但提示找不到输入框

说明目标 App 的输入控件没有正确暴露 Accessibility 节点。

### D. 找到输入框，但 ACTION_SET_TEXT 失败

说明目标 App 明确拒绝无障碍写入。银行、安全键盘、部分 WebView/Compose
自定义控件可能出现这种情况。

## 构建

推荐 Android Studio + JDK 17。

也可以使用仓库 GitHub Actions。V2 workflow 会先运行：

```bash
gradle testDebugUnitTest
```

再运行：

```bash
gradle assembleDebug
```

生成 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 注意

Android 无障碍服务的标准用途是辅助残障用户。若计划发布到 Google Play，
需要确认当前 Google Play 对 Accessibility API 的申报和用途政策。

本项目更适合自用、测试或受控环境。
