package dev.local.clearhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the flat server when the TV boots, so phones can reach it before Clear Home is opened. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) FlatService.start(c);
    }
}
