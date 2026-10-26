package dev.local.clearhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the flat server (House) and who's-home checks running while another app is on screen.
 * TCL's background manager freezes ordinary background apps, which left phones connecting to
 * the web app and waiting forever; a foreground service is left alone.
 */
public class FlatService extends Service {
    private static final String CHANNEL = "flat";

    static void start(Context c) {
        Intent i = new Intent(c, FlatService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException e) {
            android.util.Log.w("ClearHome", "FlatService not started: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Flat app", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setSmallIcon(getApplicationInfo().icon).setContentTitle("Clear Home")
                .setContentText("Flat app on " + House.publicUrl(this)).setOngoing(true).build();
        startForeground(1, n);
        Core.get(this); // starts the flat server if it isn't running yet
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
