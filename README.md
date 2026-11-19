# 央视频媒体会话 LSPosed 模块

为央视频 app（`com.cctv.yangshipin.app.androidp`）后台播放通知补充 Android 原生 `MediaSession`，使得系统控制中心、锁屏、蓝牙设备等可以读取播放状态并控制播放。另外还 hook 了小米运动健康和设备互联服务，以实现直播等无进度媒体在小米手环上的推送。

在央视频 `3.5.7.26115`、小米运动健康 `3.61.0` 与 MiLink `18.2.3.0` 上工作良好。模块使用 libxposed API 102，声明央视频、系统界面、小米运动健康与 MiLink 四个静态作用域，无配置界面。

## 已实现

- 同步标题、封面与播放/暂停状态
- 播放和暂停控制
- 对央视频 “长视频样式” 暴露上一项/下一项控制
- 用原生 `Notification.MediaStyle` 替换央视频自定义的后台通知
- 复用央视频通知下载到的封面图片
- 通过系统 `PlaybackState` 生成单个原生播放/暂停按钮与切换动画
- 切换到前台时仍然保留会话
- 将直播流的未知进度规范为 0
- 只在小米运动健康发往手环的数据包中重建央视频直播元数据，消除启动时的空包/旧包，并补全可被手环识别的位置与占位时长
- 在 MiLink 的 WearAgent 最后一跳中补全央视频直播时长；这是手环进入媒体控制页时实际读取的数据源

## 构建

需要 JDK 21 和 Android SDK 37；仓库已包含 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装

1. 安装模块 APK。
2. 在支持 modern Xposed API 102 的 LSPosed 中启用模块，并勾选央视频、系统界面。如果需要小米手环上的推送，需要勾选小米运动健康和设备互联。
3. 重启系统界面、央视频、小米运动健康和设备互联进程；最稳妥的方式是直接重启手机。
4. 开始电视直播或视频播放，再进入后台播放或“低碳听音”模式。
5. 检查控制中心、背屏和手环是否出现“央视频”媒体卡片，并测试播放/暂停。

调试日志标签为 `YspMediaSession`：

```shell
adb logcat -s YspMediaSession
```

## 工作原理

模块直接监听央视频内部的 `BackstagePlayNotificationEvent`，从事件和通知辅助类中读取 `PlayerContext`、标题、封面 URL 与播放状态。系统媒体按键会调用央视频原有的播放控制方法；央视频前台时即使注销了自己的通知广播，控制仍然有效。`BackstagePlayService` 仍负责后台播放所需的前台服务，但它的自定义 `RemoteViews` 通知会被标准 `Notification.MediaStyle` 通知替换。

央视频本体为 targetSdk 31，因此 Android 默认从通知 action 而非 `PlaybackState` 生成媒体按钮。模块在 SystemUI 中只为央视频启用 Android 的 `MEDIA_CONTROL_SESSION_ACTIONS` 判定，使播放/暂停按钮、布局与动画由系统生成。

小米的 SystemUI 媒体导出层会在播放态每秒同步一次进度。Android 的未知进度值为 `-1`，模块先在央视频创建媒体会话时把直播进度规范为 0。小米运动健康会在 `com.mi.health:device` 进程中把系统媒体转换为手环协议，但切换媒体源时可能先异步发送一个没有元数据或带着上一媒体元数据的包，模块会直接从当前 Android `MediaController` 重建这类央视频包。手环进入控制页时还会通过 `com.milink.service:ui` 的 WearAgent 再次读取原始媒体会话，绕过运动健康的转换结果，因此模块也在这一最终输出中把央视频播放态位置规范为至少 1 秒、总时长规范为 3600 秒。
