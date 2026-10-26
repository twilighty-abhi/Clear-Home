package dev.local.clearhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Clear Home may read what's playing on the TV (Android ties that to notification
 * access). It ignores notifications themselves. Enable once with:
 *   adb shell cmd notification allow_listener dev.local.clearhome/dev.local.clearhome.MediaWatcher
 */
public class MediaWatcher extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        Core.get(this).nowPlaying.start(); // access was just granted: start following players
    }
}
