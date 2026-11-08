package cc.linho.yangshipin.mediasession;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/** Owns the MediaSession created inside Yangshipin's process and UID. */
@SuppressLint("StaticFieldLeak")
final class SessionBridge {
    private static final String HELPER_CLASS =
            "com.tencent.videolite.android.component.player.common.backstageNotification.BackstagePlayNotificationHelper";
    private static final String ACTION_MODULE_CONTROL =
            "cc.linho.yangshipin.mediasession.CONTROL";
    private static final String EXTRA_EVENT_FLAG = "event";

    private static final String ACTION_STOP_SERVICE = "intent_stop_service_action";
    private static final String EXTRA_TITLE = "intentTitle";
    private static final String EXTRA_IMAGE_URL = "intentImgUrl";
    private static final String EXTRA_IS_PLAYING = "intentIsPlay";
    private static final String EXTRA_STYLE = "intentStyle";

    private static final String MEDIA_CHANNEL_ID = "ysp_native_media";
    private static final int DEFAULT_ORIGINAL_NOTIFICATION_ID = 1;
    private static final int MEDIA_NOTIFICATION_ID = 11_002;
    private static final int EVENT_PLAY = 100;
    private static final int EVENT_PAUSE = 200;
    private static final int EVENT_CLOSE = 300;
    private static final int EVENT_NEXT = 400;
    private static final int EVENT_PREVIOUS = 500;

    private static final long BASE_ACTIONS =
            PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE;
    private static final long LONG_VIDEO_ACTIONS =
            BASE_ACTIONS
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private static final Object LOCK = new Object();

    private static Context appContext;
    private static NotificationManager notificationManager;
    private static MediaSession mediaSession;
    private static Service foregroundService;
    private static Object helper;
    private static Object playerContext;
    private static String title = "央视频";
    private static String imageUrl = "";
    private static boolean playing;
    private static boolean active;
    private static int style = 1;
    private static int originalNotificationId = DEFAULT_ORIGINAL_NOTIFICATION_ID;
    private static Bitmap artwork;
    private static BroadcastReceiver controlReceiver;

    private SessionBridge() {
    }

    static void initialize(Application application) {
        synchronized (LOCK) {
            if (appContext != null) {
                return;
            }
            appContext = application.getApplicationContext();
            notificationManager = appContext.getSystemService(NotificationManager.class);
            createNotificationChannelLocked();
            registerControlReceiverLocked();
            Log.i(ModuleMain.TAG, "Application bridge initialized");
        }
    }

    static void attachService(Service service) {
        synchronized (LOCK) {
            initializeIfNeededLocked(service);
            foregroundService = service;
            rememberOriginalNotificationIdLocked(service);
            if (active) {
                ensureSessionLocked();
                injectMediaNotificationLocked(service);
                postMediaNotificationLocked();
            }
        }
    }

    static void beforeStartCommand(Service service, Intent intent) {
        synchronized (LOCK) {
            initializeIfNeededLocked(service);
            foregroundService = service;
            rememberOriginalNotificationIdLocked(service);

            if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
                // This is also Yangshipin's normal foreground-transition path. Actual
                // user stops are handled by the playback event or our media callback.
                Log.i(ModuleMain.TAG, "Retaining MediaSession while foreground service stops");
                return;
            }

            if (intent != null) {
                String nextTitle = intent.getStringExtra(EXTRA_TITLE);
                String nextImageUrl = intent.getStringExtra(EXTRA_IMAGE_URL);
                if (!TextUtils.isEmpty(nextTitle)) {
                    title = nextTitle;
                }
                updateImageUrlLocked(nextImageUrl);
                playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, playing);
                style = intent.getIntExtra(EXTRA_STYLE, style);
                active = true;
            }
            if (active) {
                ensureSessionLocked();
                // BackstagePlayService immediately calls startForeground(requestCode,
                // notify) inside its own onStartCommand. Replace both fields first so
                // even that initial call can never expose its empty placeholder.
                injectMediaNotificationLocked(service);
                publishAllLocked();
            }
        }
    }

    static void afterStartCommand(Service service, Intent intent) {
        synchronized (LOCK) {
            if (!active || (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction()))) {
                return;
            }
            foregroundService = service;
            // Defensively restore these fields in case this app version rewrote them.
            injectMediaNotificationLocked(service);
            postMediaNotificationLocked();
        }
    }

    static void onPlaybackEvent(Object event) {
        if (event == null) {
            return;
        }
        try {
            Object eventPlayerContext = readField(event, "mPlayerContext");
            boolean show = readBooleanField(event, "mIsShowNotify", false);
            if (eventPlayerContext == null) {
                synchronized (LOCK) {
                    if (active) {
                        // Foreground transitions intentionally carry a null PlayerContext.
                        // The old PlayerContext often reports a transient STOP at this point;
                        // it must not be interpreted as the end of playback.
                        publishAllLocked();
                        Log.i(ModuleMain.TAG,
                                "Retaining MediaSession for null-context lifecycle event");
                    }
                }
                return;
            }
            if (!show) {
                synchronized (LOCK) {
                    if (active && isPlayerTerminalLocked()) {
                        releaseLocked();
                    } else if (active) {
                        publishAllLocked();
                    }
                }
                return;
            }

            Class<?> helperClass = Class.forName(
                    HELPER_CLASS, false, event.getClass().getClassLoader());
            Object helperInstance = helperClass.getDeclaredMethod("getInstance").invoke(null);
            Method setIntentData = helperClass.getDeclaredMethod("setIntentData", event.getClass());
            setIntentData.invoke(helperInstance, event);
        } catch (Throwable error) {
            Log.w(ModuleMain.TAG, "Unable to consume playback event", error);
        }
    }

    static void onHelperUpdated(Object helperInstance, Object event) {
        if (helperInstance == null || event == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Context context = contextFromCurrentApplication();
                if (appContext == null && context != null) {
                    initializeIfNeededLocked(context);
                }
                Object nextPlayerContext = readField(event, "mPlayerContext");
                if (nextPlayerContext == null || appContext == null) {
                    return;
                }

                helper = helperInstance;
                playerContext = nextPlayerContext;
                String nextTitle = (String) readField(helperInstance, "mTitle");
                String nextImageUrl = (String) readField(helperInstance, "mImgUrl");
                if (!TextUtils.isEmpty(nextTitle)) {
                    title = nextTitle;
                }
                updateImageUrlLocked(nextImageUrl);
                playing = readBooleanField(event, "mIsPlay", playing);
                style = readIntField(event, "style", style);
                active = true;
                ensureSessionLocked();
                publishAllLocked();
                Log.i(ModuleMain.TAG, "Playback event: " + title + ", playing=" + playing
                        + ", style=" + style);
            } catch (Throwable error) {
                Log.w(ModuleMain.TAG, "Unable to update session from helper", error);
            }
        }
    }

    static void updateArtwork(Bitmap bitmap, String url) {
        if (bitmap == null) {
            return;
        }
        synchronized (LOCK) {
            if (!active || mediaSession == null) {
                return;
            }
            // The loader may normalize or decorate the URL. The Bitmap callback itself is
            // the reliable association with the notification currently being constructed.
            artwork = copyAndScaleArtwork(bitmap, 512);
            publishMetadataLocked();
            postMediaNotificationLocked();
            Log.i(ModuleMain.TAG, "Artwork captured: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        }
    }

    static void linkForegroundNotification(Service service) {
        synchronized (LOCK) {
            initializeIfNeededLocked(service);
            foregroundService = service;
            if (!active) {
                return;
            }
            ensureSessionLocked();
            injectMediaNotificationLocked(service);
            postMediaNotificationLocked();
            Log.i(ModuleMain.TAG, "Suppressed Yangshipin custom foreground notification");
        }
    }

    static void detachService(Service service) {
        synchronized (LOCK) {
            if (foregroundService == service) {
                foregroundService = null;
            }
            if (active) {
                // stopForeground(true) has just removed the service notification. Repost the
                // same MediaStyle notification as an ordinary notification while in foreground.
                postMediaNotificationLocked();
            }
        }
    }

    private static void ensureSessionLocked() {
        if (mediaSession != null || appContext == null) {
            return;
        }
        mediaSession = new MediaSession(appContext, ModuleMain.TAG);
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new TransportCallback(), new Handler(Looper.getMainLooper()));
        setSessionActivityLocked();
        mediaSession.setActive(true);
        Log.i(ModuleMain.TAG, "MediaSession created");
    }

    private static void publishAllLocked() {
        if (mediaSession == null) {
            return;
        }
        publishMetadataLocked();
        publishPlaybackStateLocked();
        postMediaNotificationLocked();
    }

    private static void publishMetadataLocked() {
        if (mediaSession == null) {
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                // Keep one secondary field. Supplying both artist and album made
                // Xiaomi render the redundant line "央视频 - 央视频".
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "央视频");
        if (!TextUtils.isEmpty(imageUrl)) {
            builder.putString(MediaMetadata.METADATA_KEY_ART_URI, imageUrl);
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, imageUrl);
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, imageUrl);
        }
        if (artwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        mediaSession.setMetadata(builder.build());
    }

    private static void publishPlaybackStateLocked() {
        if (mediaSession == null) {
            return;
        }
        long actions = style == 2 ? LONG_VIDEO_ACTIONS : BASE_ACTIONS;
        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        float speed = playing ? 1.0f : 0.0f;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed,
                        SystemClock.elapsedRealtime());
        mediaSession.setPlaybackState(builder.build());
        mediaSession.setActive(true);
    }

    private static void postMediaNotificationLocked() {
        if (!active || mediaSession == null || appContext == null || notificationManager == null) {
            return;
        }
        Notification notification = buildMediaNotificationLocked();
        try {
            if (foregroundService != null) {
                foregroundService.startForeground(MEDIA_NOTIFICATION_ID, notification);
                // Some Xiaomi builds do not invalidate the large icon when an existing
                // foreground notification is updated only through startForeground().
                // notify() with the same ID is the documented update path and forces the
                // notification pipeline to consume the new artwork as well.
                notificationManager.notify(MEDIA_NOTIFICATION_ID, notification);
                if (originalNotificationId != MEDIA_NOTIFICATION_ID) {
                    notificationManager.cancel(originalNotificationId);
                }
            } else if (Build.VERSION.SDK_INT < 33
                    || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(MEDIA_NOTIFICATION_ID, notification);
            }
        } catch (Throwable error) {
            Log.w(ModuleMain.TAG, "Unable to post MediaStyle notification", error);
        }
    }

    private static void rememberOriginalNotificationIdLocked(Service service) {
        int notificationId = readIntField(
                service, "requestCode", DEFAULT_ORIGINAL_NOTIFICATION_ID);
        if (notificationId != MEDIA_NOTIFICATION_ID) {
            originalNotificationId = notificationId;
        }
    }

    private static void injectMediaNotificationLocked(Service service) {
        if (!active || mediaSession == null || appContext == null) {
            return;
        }
        try {
            writeIntField(service, "requestCode", MEDIA_NOTIFICATION_ID);
            writeField(service, "notify", buildMediaNotificationLocked());
        } catch (Throwable error) {
            Log.w(ModuleMain.TAG, "Unable to inject native foreground notification", error);
        }
    }

    private static Notification buildMediaNotificationLocked() {
        Notification.Builder builder = new Notification.Builder(appContext, MEDIA_CHANNEL_ID)
                .setSmallIcon(appContext.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText("央视频")
                .setSubText("央视频")
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setContentIntent(createLaunchPendingIntentLocked());
        if (artwork != null) {
            builder.setLargeIcon(artwork);
        }

        int playPauseIndex;
        if (style == 2) {
            builder.addAction(mediaAction(
                    android.R.drawable.ic_media_previous, "上一项", EVENT_PREVIOUS));
            playPauseIndex = 1;
        } else {
            playPauseIndex = 0;
        }
        builder.addAction(mediaAction(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放",
                playing ? EVENT_PAUSE : EVENT_PLAY));
        if (style == 2) {
            builder.addAction(mediaAction(android.R.drawable.ic_media_next, "下一项", EVENT_NEXT));
        }

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken());
        if (style == 2) {
            mediaStyle.setShowActionsInCompactView(0, playPauseIndex, 2);
        } else {
            mediaStyle.setShowActionsInCompactView(playPauseIndex);
        }
        builder.setStyle(mediaStyle);
        return builder.build();
    }

    private static Notification.Action mediaAction(int iconRes, String label, int event) {
        Icon icon = Icon.createWithResource("android", iconRes);
        Notification.Action.Builder builder = new Notification.Action.Builder(
                icon, label, controlPendingIntent(event));
        if (Build.VERSION.SDK_INT >= 37) {
            int semanticAction = event == EVENT_PLAY
                    ? Notification.Action.SEMANTIC_ACTION_PLAY
                    : event == EVENT_PAUSE
                    ? Notification.Action.SEMANTIC_ACTION_PAUSE
                    : Notification.Action.SEMANTIC_ACTION_NONE;
            builder.setSemanticAction(semanticAction);
        }
        return builder.build();
    }

    private static PendingIntent controlPendingIntent(int event) {
        Intent intent = new Intent(ACTION_MODULE_CONTROL)
                .setPackage(ModuleMain.TARGET_PACKAGE)
                .putExtra(EXTRA_EVENT_FLAG, event);
        return PendingIntent.getBroadcast(
                appContext,
                20_000 + event,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent createLaunchPendingIntentLocked() {
        Intent launchIntent = appContext.getPackageManager().getLaunchIntentForPackage(
                ModuleMain.TARGET_PACKAGE);
        if (launchIntent == null) {
            launchIntent = new Intent();
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                appContext,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setSessionActivityLocked() {
        mediaSession.setSessionActivity(createLaunchPendingIntentLocked());
    }

    private static void executeControl(int event) {
        synchronized (LOCK) {
            if (!active || helper == null || playerContext == null) {
                Log.w(ModuleMain.TAG, "Ignoring media control without an active PlayerContext");
                return;
            }
            try {
                Field playerContextField = findField(helper.getClass(), "mPlayerContext");
                playerContextField.set(helper, playerContext);

                if (event == EVENT_NEXT) {
                    helper.getClass().getDeclaredMethod("playNext").invoke(helper);
                } else if (event == EVENT_PREVIOUS) {
                    helper.getClass().getDeclaredMethod("playUp").invoke(helper);
                } else {
                    Class<?> controlTypeClass = Class.forName(
                            HELPER_CLASS + "$ControlType", false, helper.getClass().getClassLoader());
                    String controlName = event == EVENT_PLAY
                            ? "PLAY" : event == EVENT_PAUSE ? "PAUSE" : "CLOSE";
                    Object controlType = controlTypeClass.getDeclaredField(controlName).get(null);
                    Method controlPlayer = helper.getClass().getDeclaredMethod(
                            "controlPlayer", controlTypeClass);
                    controlPlayer.invoke(helper, controlType);
                }

                if (event == EVENT_CLOSE) {
                    releaseLocked();
                    return;
                }
                if (event == EVENT_PLAY || event == EVENT_PAUSE) {
                    playing = event == EVENT_PLAY;
                    publishPlaybackStateLocked();
                    postMediaNotificationLocked();
                }
                Log.i(ModuleMain.TAG, "Media control dispatched: " + event);
            } catch (Throwable error) {
                Log.e(ModuleMain.TAG, "Unable to dispatch media control " + event, error);
            }
        }
    }

    private static boolean isPlayerTerminalLocked() {
        if (playerContext == null) {
            return true;
        }
        try {
            Object playerInfo = playerContext.getClass().getDeclaredMethod("getPlayerInfo")
                    .invoke(playerContext);
            if (playerInfo == null) {
                return false;
            }
            Object state = playerInfo.getClass().getDeclaredMethod("getState").invoke(playerInfo);
            String stateName = String.valueOf(state).toUpperCase(Locale.ROOT);
            return stateName.contains("STOP")
                    || stateName.contains("COMPLETION")
                    || stateName.contains("ERROR")
                    || stateName.contains("RELEASE")
                    || stateName.contains("IDLE");
        } catch (Throwable error) {
            Log.d(ModuleMain.TAG, "Player state unavailable during lifecycle transition", error);
            return false;
        }
    }

    private static void releaseLocked() {
        active = false;
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Throwable ignored) {
                // Host process may already be tearing down.
            }
        }
        mediaSession = null;
        if (notificationManager != null) {
            notificationManager.cancel(MEDIA_NOTIFICATION_ID);
            notificationManager.cancel(originalNotificationId);
        }
        foregroundService = null;
        helper = null;
        playerContext = null;
        title = "央视频";
        imageUrl = "";
        playing = false;
        style = 1;
        artwork = null;
        Log.i(ModuleMain.TAG, "MediaSession released");
    }

    private static void updateImageUrlLocked(String nextImageUrl) {
        String normalized = nextImageUrl == null ? "" : nextImageUrl;
        if (!TextUtils.equals(imageUrl, normalized)) {
            imageUrl = normalized;
            artwork = null;
        }
    }

    private static Bitmap copyAndScaleArtwork(Bitmap source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int largest = Math.max(width, height);
        if (largest > maxSide) {
            float ratio = (float) maxSide / largest;
            return Bitmap.createScaledBitmap(
                    source,
                    Math.max(1, Math.round(width * ratio)),
                    Math.max(1, Math.round(height * ratio)),
                    true);
        }
        Bitmap.Config config = source.getConfig() == null
                ? Bitmap.Config.ARGB_8888 : source.getConfig();
        Bitmap copy = source.copy(config, false);
        return copy == null ? source : copy;
    }

    private static void initializeIfNeededLocked(Context context) {
        if (appContext != null) {
            return;
        }
        appContext = context.getApplicationContext();
        notificationManager = appContext.getSystemService(NotificationManager.class);
        createNotificationChannelLocked();
        registerControlReceiverLocked();
    }

    private static void createNotificationChannelLocked() {
        if (notificationManager == null || Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                MEDIA_CHANNEL_ID,
                "媒体播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("央视频的系统媒体控制");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void registerControlReceiverLocked() {
        if (controlReceiver != null || appContext == null) {
            return;
        }
        controlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_MODULE_CONTROL.equals(intent.getAction())) {
                    executeControl(intent.getIntExtra(EXTRA_EVENT_FLAG, 0));
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_MODULE_CONTROL);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(controlReceiver, filter);
        }
    }

    private static Context contextFromCurrentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Application) activityThread.getDeclaredMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object instance, String name) throws ReflectiveOperationException {
        return findField(instance.getClass(), name).get(instance);
    }

    private static boolean readBooleanField(Object instance, String name, boolean fallback) {
        try {
            return findField(instance.getClass(), name).getBoolean(instance);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int readIntField(Object instance, String name, int fallback) {
        try {
            return findField(instance.getClass(), name).getInt(instance);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void writeField(Object instance, String name, Object value)
            throws ReflectiveOperationException {
        findField(instance.getClass(), name).set(instance, value);
    }

    private static void writeIntField(Object instance, String name, int value)
            throws ReflectiveOperationException {
        findField(instance.getClass(), name).setInt(instance, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class TransportCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {
            executeControl(EVENT_PLAY);
        }

        @Override
        public void onPause() {
            executeControl(EVENT_PAUSE);
        }

        @Override
        public void onSkipToNext() {
            executeControl(EVENT_NEXT);
        }

        @Override
        public void onSkipToPrevious() {
            executeControl(EVENT_PREVIOUS);
        }
    }
}
