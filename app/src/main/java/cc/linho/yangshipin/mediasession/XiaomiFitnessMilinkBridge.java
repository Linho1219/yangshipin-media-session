package cc.linho.yangshipin.mediasession;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Repairs only the Xiaomi Fitness packet sent to the band, not the phone MediaSession. */
final class XiaomiFitnessMilinkBridge {
    private static final String LOCAL_DEVICE_ID = "local_device_id";

    // Keep the band-side tuple inside the range of an ordinary finite track.
    // Values such as 0xffff can be interpreted as "unknown" or -1 by firmware
    // even though Xiaomi Fitness serializes the field as a protobuf int.
    private static final int LIVE_DURATION_SECONDS = 60 * 60;

    private XiaomiFitnessMilinkBridge() {
    }

    static void repairLivePacket(Object controller, Object shareDevice) {
        try {
            if (controller == null || shareDevice == null) {
                return;
            }

            MediaController mediaController = getLocalYangshipinController(controller);
            if (mediaController == null) {
                return;
            }
            MediaMetadata metadata = mediaController.getMetadata();
            if (metadata == null) {
                return;
            }
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (duration > 0L || TextUtils.isEmpty(title)) {
                return;
            }

            // t3f.c == 4 means that t3f.d contains the z1f audio-device packet.
            if (readInt(shareDevice, "c") != 4) {
                return;
            }
            Object audioDevice = readField(shareDevice, "d");
            if (audioDevice == null) {
                return;
            }

            Object media = readField(audioDevice, "d");
            boolean replacedEmpty = media == null;
            if (media == null) {
                Field mediaField = audioDevice.getClass().getField("d");
                mediaField.setAccessible(true);
                media = mediaField.getType().getDeclaredConstructor().newInstance();
                mediaField.set(audioDevice, media);
            }

            int protocolState = readInt(audioDevice, "c");
            PlaybackState playbackState = mediaController.getPlaybackState();
            long positionMs = playbackState == null ? 0L : playbackState.getPosition();
            int positionSeconds = (int) Math.min(
                    LIVE_DURATION_SECONDS - 1L, Math.max(0L, positionMs / 1000L));
            // Band 10 Pro treats a playing item at exactly 0/finite-duration as
            // unavailable, while paused items at 0 are accepted. Ordinary music
            // packets observed from Xiaomi Fitness always have a positive position.
            if (protocolState == 1 && positionSeconds == 0) {
                positionSeconds = 1;
            }

            writeField(media, "c", title);
            writeInt(media, "d", positionSeconds);
            writeInt(media, "e", LIVE_DURATION_SECONDS);
            writeInt(media, "f", readVolume(controller, media));
            writeField(media, "g", emptyIfNull(metadata.getString(
                    MediaMetadata.METADATA_KEY_ARTIST), "央视频"));
            writeField(media, "h", emptyIfNull(metadata.getString(
                    MediaMetadata.METADATA_KEY_ALBUM), ""));
            Log.i(ModuleMain.TAG, "Rebuilt Xiaomi Fitness live packet for " + title
                    + ", state=" + protocolState + ", position=" + positionSeconds
                    + ", replacedEmpty=" + replacedEmpty);
        } catch (Throwable error) {
            // The packet schema is internal and obfuscated. Never let a future
            // Xiaomi Fitness update break its device process if the schema moves.
            Log.w(ModuleMain.TAG, "Unable to rebuild Xiaomi Fitness live packet", error);
        }
    }

    private static MediaController getLocalYangshipinController(Object controller)
            throws ReflectiveOperationException {
        Object deviceInfo = invokeNoArgs(controller, "getDeviceInfo");
        if (deviceInfo == null
                || !LOCAL_DEVICE_ID.equals(String.valueOf(readField(deviceInfo, "a")))) {
            return null;
        }

        Object value = invokeNoArgs(controller, "getCurrentMediaController");
        if (!(value instanceof MediaController mediaController)) {
            return null;
        }
        return ModuleMain.TARGET_PACKAGE.equals(mediaController.getPackageName())
                ? mediaController : null;
    }

    private static int readVolume(Object controller, Object existingMedia) {
        try {
            if (existingMedia != null) {
                int volume = readInt(existingMedia, "f");
                if (volume > 0) {
                    return volume;
                }
            }
            Object extraAudioData = invokeNoArgs(controller, "getExtraAudioData");
            return extraAudioData == null
                    ? 0 : ((Number) invokeNoArgs(extraAudioData, "getVolume")).intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String emptyIfNull(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static Object invokeNoArgs(Object target, String name)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
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

    private static void writeField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
