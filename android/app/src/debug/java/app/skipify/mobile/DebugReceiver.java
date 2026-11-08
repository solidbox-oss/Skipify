package app.skipify.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import app.skipify.mobile.helper.Commands;
import app.skipify.mobile.helper.SkipifyHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Debug builds only. Protected by the DUMP permission, which only the shell (adb) holds.
public class DebugReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if ("app.skipify.mobile.TEST_PLAY".equals(i.getAction())) {
            // Ways to start one song in Spotify: --es uri spotify:track:X --es how uri|id|search|prepare
            String uri = i.getStringExtra("uri"), how = i.getStringExtra("how");
            android.os.Bundle extras = new android.os.Bundle();
            String ctx = i.getStringExtra("context");
            if (ctx != null) extras.putString("android.media.extra.CONTEXT_URI", ctx);
            for (android.media.session.MediaController mc : c.getSystemService(android.media.session.MediaSessionManager.class)
                    .getActiveSessions(new android.content.ComponentName(c, AdWatcher.class))) {
                if (!Skipify.SPOTIFY.equals(mc.getPackageName())) continue;
                android.media.session.MediaController.TransportControls t = mc.getTransportControls();
                if ("id".equals(how)) t.playFromMediaId(uri, extras);
                else if ("search".equals(how)) t.playFromSearch(uri, extras);
                else if ("prepare".equals(how)) t.prepareFromUri(android.net.Uri.parse(uri), extras);
                else t.playFromUri(android.net.Uri.parse(uri), extras);
                Log.i("Skipify", "TEST_PLAY " + how + " " + uri + " actions=" + (mc.getPlaybackState() == null ? -1 : mc.getPlaybackState().getActions()));
                break;
            }
        } else if ("app.skipify.mobile.TEST_SKIP".equals(i.getAction())) {
            Skipper.debugForcePlaylistFix = i.getBooleanExtra("playlist", false);
            Skipper.debugNoButton = i.getBooleanExtra("nobutton", false);   // test the fallback
            Skipper.start(c, true);
        } else if ("app.skipify.mobile.TEST_SLEEP".equals(i.getAction())) {
            int m = i.getIntExtra("minutes", 0), s = i.getIntExtra("seconds", 0);
            if (s > 0) SleepTimer.setSeconds(c, s);
            else if (m == 0) SleepTimer.cancel(c); else SleepTimer.set(c, m);
        } else if ("app.skipify.mobile.TEST_PAIR".equals(i.getAction())) {
            // Pairing as the setup screen does it: no extra starts the pairing service; --es code N
            // submits a code as if typed into the notification.
            String code = i.getStringExtra("code");
            if (code == null) PairingService.start(c); else PairingService.submit(c, code);
        } else if ("app.skipify.mobile.TEST_MUTE".equals(i.getAction())) {
            // Skipify's own mute and unmute, as during an ad that is muted: --ei seconds N between them.
            int s = i.getIntExtra("seconds", 3);
            new Thread(() -> {
                Skipify.muteAd(c);
                Log.i("Skipify", "TEST_MUTE muted, mutedByUs=" + new Prefs(c).mutedByUs());
                android.os.SystemClock.sleep(s * 1000L);
                Skipify.unmuteIfNeeded(c);
                android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
                Log.i("Skipify", "TEST_MUTE unmuted, music muted=" + am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
                        + " volume=" + am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
            }, "skipify-test-mute").start();
        } else if ("app.skipify.mobile.TEST_HELPER".equals(i.getAction())) {
            // Helper status. extras: --ez start true writes a start command to files/helper-start.sh
            // (run it with: adb shell "$(adb shell run-as <pkg> cat files/helper-start.sh)"; the token
            // is never logged); --ez forcestop true times a force-stop of Spotify; --ez stop true
            // stops the helper.
            Log.i("Skipify", "HELPER ready=" + SkipifyHelper.ready() + " dev="
                    + android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)
                    + " wifi=" + HelperControl.onWifi(c) + " debugging=" + HelperControl.debuggingOn(c)
                    + " canToggle=" + HelperControl.canToggleDebugging(c) + " car=" + Car.connected(c)
                    + " projection=" + SkipifyHelper.projection() + "\n" + HelperControl.diagnostics(c));
            if (i.getBooleanExtra("start", false)) {
                try (FileOutputStream f = new FileOutputStream(new File(c.getFilesDir(), "helper-start.sh"))) {
                    f.write(SkipifyHelper.startCommand(c).getBytes(StandardCharsets.UTF_8));
                    Log.i("Skipify", "HELPER start command written");
                } catch (IOException e) { Log.w("Skipify", "HELPER start command not written", e); }
            }
            if (SkipifyHelper.ready()) {
                int[] p = SkipifyHelper.ping();
                Log.i("Skipify", "HELPER ping version=" + (p == null ? "?" : p[0]) + " pid=" + (p == null ? "?" : p[1]));
                Log.i("Skipify", "HELPER is-frozen=" + SkipifyHelper.run(Commands.IS_FROZEN, null));
                Object[] f = SkipifyHelper.front();
                long t = android.os.SystemClock.elapsedRealtime();
                String shellFront = Skipper.frontPackage(SkipifyHelper.run(Commands.FRONT, null));
                Log.i("Skipify", "HELPER front direct=" + (f == null ? null : f[0] + " (" + f[1] + " us)")
                        + " shell=" + shellFront + " (" + (android.os.SystemClock.elapsedRealtime() - t) + " ms)");
                if (i.getBooleanExtra("forcestop", false)) {
                    boolean[] d = new boolean[1];
                    long us = SkipifyHelper.forceStopSpotify(d);
                    Log.i("Skipify", "HELPER force-stop micros=" + us + " direct=" + d[0]);
                }
                if (i.getBooleanExtra("stop", false)) SkipifyHelper.stop();
            }
        }
    }
}
