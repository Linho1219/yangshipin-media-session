package cc.linho.yangshipin.mediasession;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed entry point. All target app interaction is reflection-based so that
 * no Yangshipin classes are linked into the module APK.
 */
public final class ModuleMain extends XposedModule {
    static final String TAG = "YspMediaSession";
    static final String TARGET_PACKAGE = "com.cctv.yangshipin.app.androidp";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private static final String SERVICE_CLASS =
            "com.tencent.videolite.android.component.player.common.backstageNotification.BackstagePlayService";
    private static final String APPLICATION_CLASS =
            "com.tencent.videolite.android.application.VideoLiteApplication";
    private static final String HELPER_CLASS =
            "com.tencent.videolite.android.component.player.common.backstageNotification.BackstagePlayNotificationHelper";
    private static final String EVENT_CLASS =
            "com.tencent.videolite.android.component.player.longvideo_player.event.pay_event.BackstagePlayNotificationEvent";
    private static final String PLAYER_CONTEXT_CLASS =
            "com.tencent.videolite.android.component.player.meta.PlayerContext";
    private static final String ARTICLE_BEAN_CLASS =
            "com.tencent.videolite.android.component.player.longvideo_player.event.pay_event.ArticleReadBean";
    private static final String DEFAULT_IMAGE_CALLBACK =
            "com.tencent.videolite.android.component.player.common.backstageNotification.ui.DefaultUI$1";
    private static final String LONG_VIDEO_IMAGE_CALLBACK =
            "com.tencent.videolite.android.component.player.common.backstageNotification.ui.LongVideoUi$1";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (SYSTEM_UI_PACKAGE.equals(param.getPackageName())
                && SYSTEM_UI_PACKAGE.equals(Application.getProcessName())) {
            try {
                installSystemUiMediaActionsHook(param.getClassLoader());
                log(Log.INFO, TAG, "Enabled session-derived media actions for Yangshipin");
            } catch (Throwable error) {
                // A failure here must never affect SystemUI startup. The target-app
                // hooks still provide a fully functional compatibility notification.
                log(Log.ERROR, TAG, "Unable to install SystemUI media-actions hook", error);
            }
            return;
        }

        if (!TARGET_PACKAGE.equals(param.getPackageName())
                || !TARGET_PACKAGE.equals(Application.getProcessName())) {
            return;
        }

        try {
            ClassLoader classLoader = param.getClassLoader();
            installApplicationHook(classLoader);
            installPlaybackEventHooks(classLoader);
            installHelperHooks(classLoader);
            installServiceHooks(classLoader);
            installArtworkHook(classLoader, DEFAULT_IMAGE_CALLBACK);
            installArtworkHook(classLoader, LONG_VIDEO_IMAGE_CALLBACK);
            log(Log.INFO, TAG, "Hooks installed for Yangshipin " + TARGET_PACKAGE);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install hooks", error);
        }
    }

    private void installSystemUiMediaActionsHook(ClassLoader classLoader)
            throws ReflectiveOperationException {
        Class<?> statusBarManager = Class.forName(
                "android.app.StatusBarManager", false, classLoader);
        Method useSessionActions = statusBarManager.getDeclaredMethod(
                "useMediaSessionActionsForApp", String.class, UserHandle.class);
        useSessionActions.setAccessible(true);
        hook(useSessionActions).intercept(chain -> {
            if (TARGET_PACKAGE.equals(chain.getArg(0))) {
                return true;
            }
            return chain.proceed();
        });
    }

    private void installApplicationHook(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> applicationClass = Class.forName(APPLICATION_CLASS, false, classLoader);
        Method onCreate = applicationClass.getMethod("onCreate");
        hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            SessionBridge.initialize((Application) chain.getThisObject());
            return result;
        });
    }

    private void installPlaybackEventHooks(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName(EVENT_CLASS, false, classLoader);
        Class<?> playerContextClass = Class.forName(PLAYER_CONTEXT_CLASS, false, classLoader);
        Class<?> articleBeanClass = Class.forName(ARTICLE_BEAN_CLASS, false, classLoader);

        Constructor<?> normalConstructor = eventClass.getDeclaredConstructor(
                playerContextClass, boolean.class, boolean.class, int.class);
        hook(normalConstructor).intercept(chain -> {
            Object result = chain.proceed();
            SessionBridge.onPlaybackEvent(chain.getThisObject());
            return result;
        });

        Constructor<?> articleConstructor = eventClass.getDeclaredConstructor(
                playerContextClass, boolean.class, boolean.class, int.class, articleBeanClass);
        hook(articleConstructor).intercept(chain -> {
            Object result = chain.proceed();
            SessionBridge.onPlaybackEvent(chain.getThisObject());
            return result;
        });

        Method setStyle = eventClass.getDeclaredMethod("setStyle", int.class);
        hook(setStyle).intercept(chain -> {
            Object result = chain.proceed();
            SessionBridge.onPlaybackEvent(chain.getThisObject());
            return result;
        });
    }

    private void installHelperHooks(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> helperClass = Class.forName(HELPER_CLASS, false, classLoader);
        Class<?> eventClass = Class.forName(EVENT_CLASS, false, classLoader);

        Method setIntentData = helperClass.getDeclaredMethod("setIntentData", eventClass);
        hook(setIntentData).intercept(chain -> {
            Object result = chain.proceed();
            SessionBridge.onHelperUpdated(chain.getThisObject(), chain.getArg(0));
            return result;
        });
    }

    private void installServiceHooks(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> serviceClass = Class.forName(SERVICE_CLASS, false, classLoader);

        Method onCreate = serviceClass.getDeclaredMethod("onCreate");
        hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            SessionBridge.attachService((Service) chain.getThisObject());
            return result;
        });

        Method onStartCommand = serviceClass.getDeclaredMethod(
                "onStartCommand", Intent.class, int.class, int.class);
        hook(onStartCommand).intercept(chain -> {
            Service service = (Service) chain.getThisObject();
            Intent intent = (Intent) chain.getArg(0);
            SessionBridge.beforeStartCommand(service, intent);
            try {
                return chain.proceed();
            } finally {
                SessionBridge.afterStartCommand(service, intent);
            }
        });

        Method startForeground = serviceClass.getDeclaredMethod(
                "startForegroundSer",
                android.widget.RemoteViews.class,
                android.widget.RemoteViews.class,
                android.app.Notification.Builder.class,
                Intent.class);
        startForeground.setAccessible(true);
        hook(startForeground).intercept(chain -> {
            // This method only builds and posts Yangshipin's custom RemoteViews
            // notification. The service has already been promoted with our native
            // MediaStyle notification in onStartCommand, so do not let the async
            // artwork callback overwrite it later.
            SessionBridge.linkForegroundNotification((Service) chain.getThisObject());
            return null;
        });

        Method onDestroy = serviceClass.getDeclaredMethod("onDestroy");
        hook(onDestroy).intercept(chain -> {
            try {
                return chain.proceed();
            } finally {
                SessionBridge.detachService((Service) chain.getThisObject());
            }
        });
    }

    private void installArtworkHook(ClassLoader classLoader, String className) {
        try {
            Class<?> callbackClass = Class.forName(className, false, classLoader);
            Method onSuccess = callbackClass.getDeclaredMethod("onSuccess", Bitmap.class, String.class);
            hook(onSuccess).intercept(chain -> {
                Object result = chain.proceed();
                SessionBridge.updateArtwork((Bitmap) chain.getArg(0), (String) chain.getArg(1));
                return result;
            });
        } catch (Throwable error) {
            // Artwork is optional. Title, state and transport controls still work.
            log(Log.WARN, TAG, "Artwork hook unavailable for " + className, error);
        }
    }
}
