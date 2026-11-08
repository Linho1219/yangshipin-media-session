# 央视频 3.5.7.26115 APK 分析记录

- APK SHA-256：`4744BB2608F6181972DA4D575D62E99518559FF8D6AD7B1A0F5BE47EA20AE154`
- 包名：`com.cctv.yangshipin.app.androidp`
- versionCode：`305070`
- versionName：`3.5.7.26115`
- targetSdk：31

关键实现位于：

- `com.tencent.videolite.android.component.player.common.backstageNotification.BackstagePlayService`
- `com.tencent.videolite.android.component.player.common.backstageNotification.BackstagePlayNotificationHelper`
- `com.tencent.videolite.android.component.player.common.backstageNotification.BackstagePlayBroadcastReceiver`

`BackstagePlayService` 是声明了 `mediaPlayback` 类型的前台服务。启动 Intent 中包含：

- `intentTitle`
- `intentImgUrl`
- `intentIsPlay`
- `intentStyle`

原通知按钮发出 action `com.cctv.yangshipin.backstage.play`，extra `intent_event_flag` 的值为：

- 100：播放
- 200：暂停
- 300：关闭
- 400：下一项
- 500：上一项

动态广播接收器最终调用 `BackstagePlayNotificationHelper.controlPlayer()`。播放会按播放器状态选择 `restartPlay()`、直播回看事件或文章朗读入口；暂停统一调用 `pausePlay(PlayerState.PAUSING_BY_HOST)`。因此复用通知广播比直接 Hook 某一个底层播放器更能覆盖直播、长视频和文章朗读。
