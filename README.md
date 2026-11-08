# 央视频媒体会话（LSPosed）

为央视频自带的后台播放通知补充 Android 原生 `MediaSession`，让系统控制中心、锁屏、背屏和可穿戴设备可以读取播放状态并控制播放。

当前针对央视频 `3.5.7.26115`（包名 `com.cctv.yangshipin.app.androidp`）开发。模块包名为 `cc.linho.yangshipin.mediasession`。模块无设置界面，使用 modern libxposed API 102，静态作用域仅包含央视频与系统界面。当前模块版本为 `0.4.0`。

## 已实现

- 同步标题、封面与播放/暂停状态
- 播放和暂停控制
- 对央视频“长视频样式”暴露上一项/下一项控制
- 用原生 `Notification.MediaStyle` 替换央视频自定义的后台通知
- 会话和媒体通知不再跟随应用前后台切换而消失
- 直接复用央视频通知下载到的封面 Bitmap
- 频道切换时强制刷新前台通知封面
- 通过系统 `PlaybackState` 生成单个原生播放/暂停按钮与切换动画
- 媒体副标题只保留“央视频”，不再重复显示为“央视频 - 央视频”
- 真正停止播放时释放会话；后台通知服务销毁时继续保留会话

## 构建

需要 JDK 21 和 Android SDK 37；仓库已包含 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装与测试

1. 安装模块 APK。
2. 在支持 modern Xposed API 102 的 LSPosed 中启用模块；静态作用域为央视频和 `com.android.systemui`。
3. 安装或更新 `0.4.0` 后重启系统界面或手机一次，使系统界面的原生媒体动作钩子生效。
4. 开始电视直播或视频播放，再进入后台/低碳听音模式。
5. 检查控制中心、背屏和手环是否出现“央视频”媒体卡片，并测试播放/暂停。

调试日志标签为 `YspMediaSession`：

```shell
adb logcat -s YspMediaSession
```

## 工作原理

模块直接监听央视频内部的 `BackstagePlayNotificationEvent`，从事件和通知辅助类中读取 `PlayerContext`、标题、封面 URL 与播放状态。系统媒体按键会调用央视频原有的播放控制方法；央视频前台时即使注销了自己的通知广播，控制仍然有效。`BackstagePlayService` 仍负责后台播放所需的前台服务，但它的自定义 `RemoteViews` 通知会被标准 `Notification.MediaStyle` 通知替换。

央视频本体只面向 targetSdk 31，因此 Android 默认从通知 action 而非 `PlaybackState` 生成媒体按钮。模块在 SystemUI 中只为央视频启用 Android 的 `MEDIA_CONTROL_SESSION_ACTIONS` 判定，使播放/暂停按钮、布局与动画由系统生成；不会改变央视频自身的 targetSdk 或其他兼容性行为。
