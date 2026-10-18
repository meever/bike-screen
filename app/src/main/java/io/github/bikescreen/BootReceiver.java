package io.github.bikescreen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the renderer when the bike boots or the app is updated. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { BikeService.start(c); }
}
