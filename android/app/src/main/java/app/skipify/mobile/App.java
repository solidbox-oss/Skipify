package app.skipify.mobile;

import android.app.Application;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.keepDnsDefault(this);
        Skipify.createChannels(this);
        Rules.installDefaults(this);
        SleepTimer.restore(this);
        HelperControl.init(this);
        AdWatcher.retryNow(this);
    }
}
