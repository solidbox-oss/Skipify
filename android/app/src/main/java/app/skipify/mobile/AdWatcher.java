package app.skipify.mobile;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Watches Spotify's media session (notification access is what lets an app read other apps'
// sessions). When Spotify reports an ad, the break is skipped by restarting Spotify (Skipper), or
// muted when skipping is not ready (no helper or Shizuku), the mode is "mute", or a skip just failed.
public class AdWatcher extends NotificationListenerService {
    // Every Spotify ad carries a media ID of this form, whatever its title or language.
    static final String AD_ID = "spotify:ad:";
    static final String KEY_CONTEXT = "com.spotify.music.extra.CONTEXT_URI";
    static final String KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT";
    // Fallback: the label Spotify shows on ads ("Advertisement", or "Advertisement • 1 of 3").
    private static final String[] AD_WORDS = {
            "advertisement", "anuncio", "anúncio", "publicidad", "werbung", "publicité", "pubblicità",
            "advertentie", "reklam", "reklama", "mainos", "annonse", "annons", "iklan", "quảng cáo",
            "реклама", "広告", "광고", "广告", "廣告", "विज्ञापन", "إعلان", "פרסומת", "โฆษณา",
    };

    private static volatile AdWatcher instance;
    // The last song that played and the playlist/album it came from, so that a restart that comes
    // back somewhere else can be corrected (see Skipper).
    static volatile String lastTrack, lastContext;

    private final Map<MediaSession.Token, MediaController.Callback> watched = new HashMap<>();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private volatile MediaController spotify;
    // Ad-break state is shared: Android can connect a new listener instance before the old one
    // is disconnected (e.g. after access is revoked and granted again), and a break must still be
    // handled only once.
    private static final Object LOCK = new Object();
    private static boolean inBreak, muting;
    // Keep retrying without waking too frequently forever. The attempt number and next wall-clock
    // time are committed before the alarm and rebind request, so process death does not lose them.
    private static final long[] REBIND_DELAYS_MS = {2_000, 5_000, 10_000, 30_000, 60_000, 300_000, 900_000};
    private static final long DUPLICATE_REBIND_WINDOW_MS = 2_000;
    private static final int RECONNECT_REQUEST_CODE = 41;
    private static boolean rebindAttemptInProgress;
    private static long scheduledRebindAt;

    @Override public void onListenerConnected() {
        AdWatcher old = instance;
        if (old != null && old != this) {
            old.detach();
            if (instance == old) instance = null;
        }
        detach();   // in case this instance was connected before
        MediaSessionManager msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, AdWatcher.class);
        sessionsListener = this::onSessions;
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me);
            List<MediaController> active = msm.getActiveSessions(me);
            synchronized (LOCK) {
                instance = this;
                clearReconnectLocked(this);
            }
            onSessions(active);
        } catch (SecurityException e) {
            // Notification access was turned off at this very moment: Android still delivers the
            // connect, but sessions may no longer be read. Do not publish a false connected state;
            // retry in case this was only a transient permission/settings race.
            if (instance == this) instance = null;
            detach();
            Events.add(this, Events.FAIL, "Ad watcher could not read media sessions; retrying");
            retryNow(this);
        }
    }

    @Override public void onListenerDisconnected() {
        boolean active = instance == this;
        if (active) instance = null;
        detach();
        if (active) {
            Events.add(this, Events.FAIL, "Ad watcher disconnected; requesting reconnect");
        }
        retryNow(this);
    }

    @Override public void onDestroy() {
        boolean active = instance == this;
        if (active) instance = null;
        detach();
        if (active) retryNow(this);
        super.onDestroy();
    }

    // Stops watching: removes the sessions listener and every callback this instance registered.
    private synchronized void detach() {
        if (sessionsListener != null) {
            try { getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (Exception ignored) {}
            sessionsListener = null;
        }
        for (Map.Entry<MediaSession.Token, MediaController.Callback> e : watched.entrySet()) {
            try { new MediaController(this, e.getKey()).unregisterCallback(e.getValue()); } catch (Exception ignored) {}
        }
        watched.clear();
        spotify = null;
    }

    private synchronized void onSessions(List<MediaController> list) {
        if (list == null) return;
        Map<MediaSession.Token, MediaController.Callback> keep = new HashMap<>();
        for (MediaController c : list) {
            if (!Skipify.SPOTIFY.equals(c.getPackageName())) continue;
            spotify = c;
            MediaController.Callback cb = watched.remove(c.getSessionToken());
            if (cb == null) {
                cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata m) { changed(c); }
                    @Override public void onPlaybackStateChanged(PlaybackState s) { changed(c); }
                };
                c.registerCallback(cb);
            }
            keep.put(c.getSessionToken(), cb);
            check(c);
        }
        watched.clear();   // sessions that went away (e.g. Spotify was restarted)
        watched.putAll(keep);
        if (keep.isEmpty()) { spotify = null; BackgroundAudio.idle(); }   // Spotify is gone
    }

    private void changed(MediaController c) {
        if (instance != this) return;   // a stale instance that was replaced
        if (BuildConfig.DEBUG) Trace.log(this, c);
        check(c);
    }

    // Re-evaluates the current Spotify state, e.g. after a skip finished or Skipify was switched.
    static void recheck() {
        AdWatcher w = instance;
        if (w != null && w.spotify != null) w.check(w.spotify);
    }

    static boolean isAd(MediaController c) { return isAd(c.getMetadata()); }

    static boolean inBreak() { synchronized (LOCK) { return inBreak; } }

    static boolean connected() { return instance != null; }

    // A listener can be disconnected by Android or an OEM while notification access remains enabled.
    // The alarm is persisted by Android across process death; boot/package recovery re-arms it.
    static void requestRebindIfNeeded(Context context) {
        Context c = context.getApplicationContext();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (instance != null || !reconnectAllowed(c)) { clearReconnectLocked(c); return; }
            Prefs p = new Prefs(c);
            long next = p.listenerRetryAt();
            if (next == 0) {
                next = now;
                p.listenerRetryState(p.listenerRetryAttempt(), next,
                        p.listenerLastRequestAt(), p.listenerRetryError());
            }
            if (next > now && scheduleReconnectLocked(c, next)) return;
            if (next > now) {
                p.listenerRetryState(p.listenerRetryAttempt(), now,
                        p.listenerLastRequestAt(), p.listenerRetryError());
            }
        }
        attemptRebind(c);
    }

    // Freshly enabling Skipify, starting the app with a pending retry, or rebooting should not wait
    // out an old backoff interval before its first recovery request.
    static void retryNow(Context context) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            if (instance != null || !reconnectAllowed(c)) { clearReconnectLocked(c); return; }
            Prefs p = new Prefs(c);
            long now = System.currentTimeMillis();
            long lastRequest = p.listenerLastRequestAt();
            if (recentRebindRequest(now, lastRequest)) {
                long next = p.listenerRetryAt();
                if (next > 0) scheduleReconnectLocked(c, next);
                return;
            }
            p.listenerRetryState(p.listenerRetryAttempt(), now,
                    p.listenerLastRequestAt(), p.listenerRetryError());
        }
        requestRebindIfNeeded(c);
    }

    static void cancelReconnect(Context context) {
        synchronized (LOCK) { clearReconnectLocked(context.getApplicationContext()); }
    }

    static void onReconnectAlarm(Context context) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            scheduledRebindAt = 0;   // this alarm has been delivered
        }
        requestRebindIfNeeded(c);
    }

    static long rebindDelayMs(int attempt) {
        int index = Math.max(0, Math.min(Math.max(attempt, 1) - 1, REBIND_DELAYS_MS.length - 1));
        return REBIND_DELAYS_MS[index];
    }

    static boolean recentRebindRequest(long now, long lastRequestAt) {
        return lastRequestAt > 0 && now >= lastRequestAt
                && now - lastRequestAt < DUPLICATE_REBIND_WINDOW_MS;
    }

    static String reconnectDiagnostics(Context context) {
        Prefs p = new Prefs(context);
        long now = System.currentTimeMillis();
        long last = p.listenerLastRequestAt(), next = p.listenerRetryAt();
        String lastText = last == 0 ? "never" : formatReconnectTime(last);
        String nextText = next == 0 ? "not scheduled" : next <= now ? "due" : formatReconnectTime(next);
        String error = p.listenerRetryError().replace('\n', ' ').replace('\r', ' ');
        return "attempts=" + p.listenerRetryAttempt() + ", last request=" + lastText + ", next retry=" + nextText
                + (error.isEmpty() ? "" : ", last error=" + error);
    }

    private static String formatReconnectTime(long time) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new java.util.Date(time));
    }

    private static boolean reconnectAllowed(Context c) {
        return new Prefs(c).enabled() && Skipify.listenerEnabled(c);
    }

    private static PendingIntent reconnectIntent(Context c) {
        Intent i = new Intent(c, Skipify.Receiver.class).setAction(Skipify.ACTION_RECONNECT);
        return PendingIntent.getBroadcast(c, RECONNECT_REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean scheduleReconnectLocked(Context c, long at) {
        if (scheduledRebindAt == at) return true;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return false;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reconnectIntent(c));
            scheduledRebindAt = at;
            return true;
        } catch (RuntimeException e) {
            // Do not let an OEM/OS alarm failure prevent the direct requestRebind below.
            scheduledRebindAt = 0;
            android.util.Log.w(Skipify.TAG, "Could not schedule notification-listener retry", e);
            return false;
        }
    }

    private static void clearReconnectLocked(Context c) {
        Prefs prefs = new Prefs(c);
        if (scheduledRebindAt == 0 && prefs.listenerRetryAt() == 0 && prefs.listenerRetryAttempt() == 0
                && prefs.listenerLastRequestAt() == 0 && prefs.listenerRetryError().isEmpty()) {
            rebindAttemptInProgress = false;
            return;
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = reconnectIntent(c);
        if (am != null) am.cancel(pi);
        pi.cancel();
        scheduledRebindAt = 0;
        rebindAttemptInProgress = false;
        prefs.clearListenerRetry();
    }

    private static void attemptRebind(Context c) {
        final int attempt;
        final long retryAt, lastRequestAt;
        final String previousError;
        synchronized (LOCK) {
            if (rebindAttemptInProgress) {
                long next = new Prefs(c).listenerRetryAt();
                if (next > 0) scheduleReconnectLocked(c, next);
                return;
            }
            if (instance != null || !reconnectAllowed(c)) { clearReconnectLocked(c); return; }
            Prefs p = new Prefs(c);
            long now = System.currentTimeMillis();
            long due = p.listenerRetryAt();
            if (due > now && scheduleReconnectLocked(c, due)) return;
            if (due > now) {
                p.listenerRetryState(p.listenerRetryAttempt(), now,
                        p.listenerLastRequestAt(), p.listenerRetryError());
            }
            int previousAttempt = p.listenerRetryAttempt();
            attempt = previousAttempt < 1_000_000 ? previousAttempt + 1 : previousAttempt;
            lastRequestAt = now;
            retryAt = now + rebindDelayMs(attempt);
            previousError = p.listenerRetryError();
            p.listenerRetryState(attempt, retryAt, lastRequestAt, previousError);
            scheduleReconnectLocked(c, retryAt);
            rebindAttemptInProgress = true;
        }

        boolean requestFailed = false;
        try {
            requestRebind(new ComponentName(c, AdWatcher.class));
        } catch (RuntimeException e) {
            requestFailed = true;
            String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            synchronized (LOCK) {
                if (instance == null) {
                    new Prefs(c).listenerRetryState(attempt, retryAt, lastRequestAt, reason);
                    if (!reason.equals(previousError)) Events.add(c, Events.FAIL, "Ad watcher rebind failed: " + reason);
                }
            }
        } finally {
            synchronized (LOCK) {
                rebindAttemptInProgress = false;
                if (instance != null || !reconnectAllowed(c)) {
                    clearReconnectLocked(c);
                } else {
                    Prefs p = new Prefs(c);
                    if (p.listenerRetryAt() == 0) {
                        p.listenerRetryState(attempt, retryAt, lastRequestAt, p.listenerRetryError());
                    }
                    if (!requestFailed && !p.listenerRetryError().isEmpty())
                        p.listenerRetryState(attempt, retryAt, lastRequestAt, "");
                    scheduleReconnectLocked(c, p.listenerRetryAt());
                }
            }
        }
    }

    static boolean spotifyPlaying() {
        AdWatcher w = instance;
        MediaController mc = w == null ? null : w.spotify;
        PlaybackState ps = mc == null ? null : mc.getPlaybackState();
        return ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
    }

    // A skip that could not run (Android Auto was using Spotify, see Skipper): mute this break
    // instead; the usual "ad over" check turns the sound back on.
    static void muteInstead(Context c) {
        synchronized (LOCK) {
            if (muting) return;
            muting = true;
        }
        Skipify.muteAd(c);
        new Prefs(c).add(Prefs.MUTED, 1);
        Skipify.refreshNotification(c);
    }

    @SuppressLint("WrongConstant")   // Spotify-specific metadata keys
    static boolean isAd(MediaMetadata m) {
        if (m == null) return false;
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && id.startsWith(AD_ID)) return true;
        if (m.getLong(KEY_ADVERTISEMENT) != 0) return true;   // standard flag, set by Spotify on ads
        for (String k : new String[]{MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE}) {
            CharSequence v = m.getText(k);
            if (v != null && isAdLabel(v.toString())) return true;
        }
        return false;
    }

    // "Advertisement" alone, or followed by a separator: "Advertisement • 1 of 3". A song that
    // merely starts with the word ("Advertisement Song") does not match.
    static boolean isAdLabel(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (String w : AD_WORDS) {
            if (!t.startsWith(w)) continue;
            String rest = t.substring(w.length()).trim();
            if (rest.isEmpty() || rest.startsWith("•") || rest.startsWith("·") || rest.startsWith("|") || rest.startsWith("-"))
                return true;
        }
        return false;
    }

    private static String describe(MediaMetadata m) {
        if (m == null) return "";
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE), a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        return (t == null ? "" : t) + (a == null || a.length() == 0 ? "" : " · " + a);
    }

    private void check(MediaController c) {
        synchronized (LOCK) { checkLocked(c); }
    }

    @SuppressLint("WrongConstant")   // Spotify-specific metadata keys
    private void checkLocked(MediaController c) {
        MediaMetadata m = c.getMetadata();
        boolean ad = isAd(m);
        PlaybackState ps = c.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        boolean starting = ps != null && (ps.getState() == PlaybackState.STATE_BUFFERING
                || ps.getState() == PlaybackState.STATE_CONNECTING);
        if (m != null && SleepTimer.onTrack(this, m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))) return;
        if (m != null && !Skipper.busy()) {
            // The playlist/album to come back to after a skip. Taken as soon as Spotify reports it,
            // also while the first song is still loading or an ad plays: when you switch playlists
            // and an ad comes before the new one's first song, the new playlist is the right one.
            // Every kind of source counts (a song picked from search plays in a "spotify:track:"
            // context, radio in "spotify:station:", ...): remembering only playlists and albums
            // kept an older playlist here, and the skip then switched to that old playlist.
            String ctx = m.getString(KEY_CONTEXT);
            if (ctx != null && ctx.startsWith("spotify:")) lastContext = ctx;
            String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (!ad && (playing || starting) && id != null && id.startsWith("spotify:")) {
                lastTrack = id;
                if (ctx == null || !ctx.startsWith("spotify:")) lastContext = null;   // no source reported
            }
        }

        Prefs p = new Prefs(this);
        String mode = p.mode();
        // In the car, the user's choice for Android Auto replaces Skip (Mute and Do nothing stay).
        if (ad && Prefs.MODE_RESTART.equals(mode) && Car.connected(this)) mode = p.carMode();
        if (!ad || !p.enabled() || Prefs.MODE_OFF.equals(mode)) {
            if (muting) {   // ad over (or Skipify switched off): sound back on
                muting = false;
                Skipify.unmuteIfNeeded(this);
                Events.add(this, Events.MUTE, "Ad over, sound back on");
            }
            if (!ad && playing) inBreak = false;
            if (!playing && !starting) BackgroundAudio.idle();   // a reset left for when Spotify stops
            return;
        }
        if (Skipper.busy()) return;   // a restart is in progress; its own checks cover this
        if (!playing && !starting) return;   // a paused ad is left alone until it plays
        // "End of song" sleep timer: an ad means the song is over. Pause now instead of skipping the
        // ad and playing on (Spotify can report the ad before its ID, so onTrack may not have fired).
        if (SleepTimer.adAtEndOfSong(this)) return;

        boolean newBreak = !inBreak;
        inBreak = true;
        boolean recentFail = System.currentTimeMillis() - Skipper.lastFailAt < 120_000;
        boolean skipMode = Prefs.MODE_RESTART.equals(mode);
        // A break already muted (a skip could not run) stays muted until it is over. The skip starts
        // before anything is written: every millisecond before Spotify stops is heard as the ad.
        boolean skip = skipMode && Shell.ready() && !recentFail && !muting;
        if (skip) Skipper.start(this, false);
        if (newBreak) {
            p.add(Prefs.SEEN, 1);
            Events.add(this, Events.AD, "Ad break started: " + describe(m));
        }
        if (!skip && !muting) {
            muting = true;
            Skipify.muteAd(this);
            p.add(Prefs.MUTED, 1);
            boolean noShizuku = skipMode && !Shell.ready();
            Events.add(this, Events.MUTE, "Ad muted" + (!skipMode ? "" : noShizuku ? " (skipping not ready)" : " (skip failed)"));
            if (noShizuku) Skipify.skipAlert(this);
            Skipify.refreshNotification(this);
        }
    }
}
