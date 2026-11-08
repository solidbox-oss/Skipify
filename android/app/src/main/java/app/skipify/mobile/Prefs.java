package app.skipify.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

// Settings and statistics, in one SharedPreferences file.
final class Prefs {
    static final String MODE_RESTART = "restart", MODE_MUTE = "mute", MODE_OFF = "off";
    static final String SEEN = "seen", SKIPPED = "skipped", MUTED = "muted", FAILED = "failed",
            RESUME_MS = "resume_ms", DNS_HITS = "dns_hits";

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getApplicationContext().getSharedPreferences("skipify", Context.MODE_PRIVATE); }

    boolean enabled() { return sp.getBoolean("enabled", false); }
    void enabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    // The DNS filter is optional and off by default; preserve a saved choice across app restarts.
    boolean dns() { return sp.getBoolean("dns", false); }

    // Called before startup; record the fresh-install default once, without overwriting user choice.
    static void keepDnsDefault(Context c) {
        SharedPreferences sp = c.getSharedPreferences("skipify", Context.MODE_PRIVATE);
        if (!sp.contains("dns")) sp.edit().putBoolean("dns", false).apply();
    }
    void dns(boolean v) { sp.edit().putBoolean("dns", v).apply(); }

    String mode() { return sp.getString("mode", MODE_RESTART); }
    void mode(String v) { sp.edit().putString("mode", v).apply(); }
    // What Skipify does while Android Auto is connected: skip, mute or do nothing (MODE_* values).
    String carMode() { return sp.getString("car_mode", MODE_RESTART); }
    void carMode(String v) { sp.edit().putString("car_mode", v).apply(); }

    boolean mutedByUs() { return sp.getBoolean("muted_by_us", false); }
    void mutedByUs(boolean v) { sp.edit().putBoolean("muted_by_us", v).commit(); }
    // Spotify itself was muted (PLAY_AUDIO app-op, used in Android Auto). Unlike the volume, Android
    // keeps this across a reboot, so it is undone as soon as Skipify can, never just forgotten.
    boolean mutedSpotify() { return sp.getBoolean("muted_spotify", false); }
    void mutedSpotify(boolean v) { sp.edit().putBoolean("muted_spotify", v).commit(); }

    // Skipify's own helper: paired with Wireless debugging; automatic restart after a reboot (opt-in);
    // the per-phone test result (TEST_* below).
    static final String TEST_NONE = "", TEST_SAFE = "safe", TEST_NEEDS_ON = "needs_on";
    boolean paired() { return sp.getBoolean("paired", false); }
    void paired(boolean v) { sp.edit().putBoolean("paired", v).apply(); }
    // The helper is started with root (see Root) instead of Wireless debugging. Set once it worked.
    boolean root() { return sp.getBoolean("root", false); }
    void root(boolean v) { sp.edit().putBoolean("root", v).commit(); }
    // The user went through the Developer options step (newer Android versions do not tell apps).
    boolean devStep() { return sp.getBoolean("dev_step", false); }
    void devStep(boolean v) { sp.edit().putBoolean("dev_step", v).apply(); }
    // The user tapped Allow for notification access at least once: if access is still missing, Android
    // may have blocked the switch as a restricted setting (apps installed outside the Play Store).
    boolean listenerAsked() { return sp.getBoolean("listener_asked", false); }
    void listenerAsked(boolean v) { sp.edit().putBoolean("listener_asked", v).apply(); }
    // Persistent listener recovery: alarms survive process death; boot/package-replaced receivers
    // re-arm them. Commit this small state before scheduling so a delivered alarm has its retry data.
    int listenerRetryAttempt() { return sp.getInt("listener_retry_attempt", 0); }
    long listenerRetryAt() { return sp.getLong("listener_retry_at", 0); }
    long listenerLastRequestAt() { return sp.getLong("listener_last_request_at", 0); }
    String listenerRetryError() { return sp.getString("listener_retry_error", ""); }
    @SuppressLint("ApplySharedPref")
    void listenerRetryState(int attempt, long at, long lastRequestAt, String error) {
        sp.edit().putInt("listener_retry_attempt", attempt).putLong("listener_retry_at", at)
                .putLong("listener_last_request_at", lastRequestAt)
                .putString("listener_retry_error", error == null ? "" : error).commit();
    }
    @SuppressLint("ApplySharedPref")
    void clearListenerRetry() {
        sp.edit().remove("listener_retry_attempt").remove("listener_retry_at")
                .remove("listener_last_request_at").remove("listener_retry_error").commit();
    }
    boolean autoRestart() { return sp.getBoolean("auto_restart", false); }
    void autoRestart(boolean v) { sp.edit().putBoolean("auto_restart", v).apply(); }
    String phoneTest() { return sp.getString("phone_test", TEST_NONE); }
    void phoneTest(String v) { sp.edit().putString("phone_test", v).commit(); }
    // Android's boot count when the helper last connected: a different count means the phone has
    // restarted since, so no helper can still be running.
    int helperBoot() { return sp.getInt("helper_boot", -1); }
    void helperBoot(int v) { sp.edit().putInt("helper_boot", v).apply(); }

    // Sleep timer: wall-clock end time, or "end of track" with the song it started on.
    long sleepAt() { return sp.getLong("sleep_at", 0); }
    void sleepAt(long v) { sp.edit().putLong("sleep_at", v).putBoolean("sleep_eot", false).apply(); }
    boolean sleepEndOfTrack() { return sp.getBoolean("sleep_eot", false); }
    String sleepTrack() { return sp.getString("sleep_track", null); }
    void sleepEndOfTrack(boolean on, String track) {
        sp.edit().putBoolean("sleep_eot", on).putString("sleep_track", track).putLong("sleep_at", 0).apply();
    }
    void sleepClear() { sp.edit().remove("sleep_at").remove("sleep_eot").remove("sleep_track").apply(); }

    long get(String stat) { return sp.getLong("stat_" + stat, 0); }
    private static final Object STATS = new Object();

    void add(String stat, long n) {
        synchronized (STATS) { sp.edit().putLong("stat_" + stat, get(stat) + n).commit(); }
    }

    void resetStats() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("stat_")) e.remove(k);
        e.apply();
    }
}
