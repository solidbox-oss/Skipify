package app.skipify.mobile;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Debug/staging-only probe for Spotify's separately broadcast Device Broadcast Status signal.
// This only records and compares evidence; it never calls or changes AdWatcher behavior.
public final class SpotifyBroadcastTrace extends BroadcastReceiver {
    private static final String TAG = "SkipifySpotifyBroadcast";
    private static final String PREFIX = "com.spotify.music.";
    private static final String METADATA_CHANGED = PREFIX + "metadatachanged";
    private static final String PLAYBACK_CHANGED = PREFIX + "playbackstatechanged";
    private static final String QUEUE_CHANGED = PREFIX + "queuechanged";
    private static final String KEY_CONTEXT = "com.spotify.music.extra.CONTEXT_URI";
    private static final String KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!METADATA_CHANGED.equals(action) && !PLAYBACK_CHANGED.equals(action) && !QUEUE_CHANGED.equals(action)) return;

        long timeSent = intent.getLongExtra("timeSent", 0L);
        StringBuilder line = new StringBuilder("action=").append(action)
                .append(" timeSent=").append(timeSent)
                .append(" ageMs=").append(timeSent == 0 ? "unknown" : (System.currentTimeMillis() - timeSent));
        if (METADATA_CHANGED.equals(action)) {
            String id = intent.getStringExtra("id");
            line.append(" broadcastId=").append(value(id))
                    .append(" broadcastIdLooksLikeAd=").append(id != null && id.startsWith(AdWatcher.AD_ID))
                    .append(" track=").append(value(intent.getStringExtra("track")))
                    .append(" artist=").append(value(intent.getStringExtra("artist")))
                    .append(" album=").append(value(intent.getStringExtra("album")))
                    .append(" lengthSec=").append(intent.getIntExtra("length", 0));
        } else if (PLAYBACK_CHANGED.equals(action)) {
            line.append(" playing=").append(intent.getBooleanExtra("playing", false))
                    .append(" playbackPositionMs=").append(intent.getIntExtra("playbackPosition", 0));
        }
        line.append(" | ").append(currentSession(context));
        append(context, line.toString());
    }

    @SuppressLint("WrongConstant") // Spotify-specific metadata keys; this is debug-only.
    private static String currentSession(Context context) {
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            if (manager == null) return "sessionManager=unavailable";
            List<MediaController> sessions = manager.getActiveSessions(new ComponentName(context, AdWatcher.class));
            if (sessions == null) return "sessionList=null";
            for (MediaController controller : sessions) {
                if (!Skipify.SPOTIFY.equals(controller.getPackageName())) continue;
                MediaMetadata metadata = controller.getMetadata();
                String mediaId = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                String mediaUri = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
                String contextUri = metadata == null ? null : metadata.getString(KEY_CONTEXT);
                long adFlag = metadata == null ? 0L : metadata.getLong(KEY_ADVERTISEMENT);
                PlaybackState state = controller.getPlaybackState();
                Bundle extras = controller.getExtras();
                String extraContext = extras == null ? null : extras.getString(KEY_CONTEXT);
                return "sessionMediaId=" + value(mediaId)
                        + " sessionMediaUri=" + value(mediaUri)
                        + " sessionContext=" + value(contextUri == null ? extraContext : contextUri)
                        + " sessionAdFlag=" + adFlag
                        + " sessionIsAdByCurrentLogic=" + AdWatcher.isAd(metadata)
                        + " sessionPlaybackState=" + (state == null ? "null" : state.getState());
            }
            return "session=none";
        } catch (SecurityException e) {
            return "session=unavailable(SecurityException; notification access may be off)";
        } catch (Exception e) {
            return "sessionError=" + value(e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private static String value(String value) {
        if (value == null) return "<null>";
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return clean.length() > 512 ? clean.substring(0, 512) + "…" : clean;
    }

    private static synchronized void append(Context context, String message) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\t" + message;
        Log.i(TAG, line);
        try {
            File directory = context.getExternalFilesDir(null);
            if (directory == null) directory = context.getFilesDir();
            File file = new File(directory, "spotify-broadcast.log");
            if (file.length() > 1_000_000L) file.delete();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not append diagnostic file", e);
        }
    }
}
