package cc.linho.yangshipin.mediasession;

import android.media.session.MediaController;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;

/** Repairs the final protobuf produced by MiLink's WearAgent for the band. */
final class MiLinkWearAgentBridge {
    private static final int LIVE_DURATION_SECONDS = 60 * 60;

    private MiLinkWearAgentBridge() {
    }

    static void repairLivePacket(Object localController, Object shareDevice) {
        try {
            MediaController mediaController = findCurrentMediaController(localController);
            if (mediaController == null
                    || !ModuleMain.TARGET_PACKAGE.equals(mediaController.getPackageName())) {
                return;
            }

            Object audioInfo = readField(shareDevice, "audioInfo");
            Object audioData = audioInfo == null ? null : readField(audioInfo, "data");
            if (audioData == null || readInt(audioData, "duration") > 0) {
                return;
            }
            String title = String.valueOf(readField(audioData, "name"));
            if (TextUtils.isEmpty(title) || "null".equals(title)) {
                return;
            }

            int state = readInt(audioInfo, "state");
            int position = readInt(audioData, "currentPositon");
            if (state == 1 && position == 0) {
                position = 1;
                writeInt(audioData, "currentPositon", position);
            }
            writeInt(audioData, "duration", LIVE_DURATION_SECONDS);
            Log.i(ModuleMain.TAG, "Normalized MiLink WearAgent live packet for " + title
                    + ", state=" + state + ", position=" + position);
        } catch (Throwable error) {
            // MiLink is a system service. Schema changes must degrade to a no-op,
            // never interfere with the rest of the device discovery pipeline.
            Log.w(ModuleMain.TAG, "Unable to normalize MiLink WearAgent packet", error);
        }
    }

    private static MediaController findCurrentMediaController(Object localController)
            throws IllegalAccessException {
        if (localController == null) {
            return null;
        }
        for (Class<?> type = localController.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(localController);
                if (value instanceof MediaController controller) {
                    return controller;
                }
                // The local controller owns le.n (WearAgent_LocalMetaInfo), which
                // in turn owns the selected framework MediaController. Match by
                // type instead of the R8 field name so MiLink updates are safer.
                if (value != null && "le.n".equals(value.getClass().getName())) {
                    MediaController nested = findMediaControllerField(value);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return null;
    }

    private static MediaController findMediaControllerField(Object owner)
            throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!MediaController.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value instanceof MediaController controller) {
                    return controller;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int readInt(Object target, String name)
            throws ReflectiveOperationException {
        return ((Number) readField(target, name)).intValue();
    }

    private static void writeInt(Object target, String name, int value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
