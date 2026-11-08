package app.skipify.mobile;

import android.content.Context;
import app.skipify.mobile.helper.Commands;

// Keeps a restarted Spotify audible in the background.
//  - Android 15 and later mute media apps that play from the background without a foreground
//    service ("AudioHardening", enforced through the CONTROL_AUDIO app-ops). After a restart-skip
//    Spotify plays before it has registered its playback service, so it would go silent about 10
//    seconds later, and with the phone locked it stays silent until the phone is unlocked. Skipify
//    allows these ops for Spotify while Skipify is on, and returns them to Android's default when Skipify
//    is turned off, once Spotify is not playing: done at once, Android mutes the music that is
//    playing right then (a Spotify restarted by Skipify has no playback service in the car, for one).
//  - For the same reason Android counts the restarted Spotify as a cached app and freezes its
//    process: the media session still says "playing", but no sound comes out until Spotify is
//    opened. UNFREEZE exempts the running Spotify process from the freezer for its lifetime only
//    (Android 14+; older versions ignore the command), so it is applied to every new process.
// The command lines are in Commands.
final class BackgroundAudio {
    private BackgroundAudio() {}

    // Off the main thread: each runs one privileged command (no-op without a backend).
    static void allow() { new Thread(() -> Shell.run(Commands.ALLOW_AND_UNFREEZE), "skipify-audio").start(); }
    static void reset() { new Thread(() -> Shell.run(Commands.RESET_AUDIO), "skipify-audio").start(); }
    static void unfreeze() { new Thread(() -> Shell.run(Commands.UNFREEZE), "skipify-audio").start(); }

    static boolean frozen() { return Shell.run(Commands.IS_FROZEN).equals("true"); }

    // Skipify was turned off while Spotify played: the ops go back to default when it stops playing.
    private static volatile boolean resetPending;

    // Skipify was turned off: back to Android's default now, or once Spotify stops playing.
    static void resetWhenIdle() {
        if (AdWatcher.spotifyPlaying()) resetPending = true;
        else { resetPending = false; reset(); }
    }

    // Spotify stopped playing (or went away) while Skipify is off.
    static void idle() {
        if (!resetPending) return;
        resetPending = false;
        reset();
    }

    static void apply(Context c) {
        if (new Prefs(c).enabled()) { resetPending = false; allow(); }
        else resetWhenIdle();
    }
}
