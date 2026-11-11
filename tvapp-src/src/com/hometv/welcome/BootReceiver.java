package com.hometv.welcome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Launches the Home TV dashboard automatically when the stick powers on / reboots,
// so the projector shows our screen instead of the Amazon home.
public class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    try {
      Intent a = new Intent(c, MainActivity.class);
      a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      c.startActivity(a);
    } catch (Exception e) {}
  }
}
