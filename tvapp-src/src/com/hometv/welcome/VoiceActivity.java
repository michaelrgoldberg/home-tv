package com.hometv.welcome;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

// "Alexa, open Gamecast" / "Alexa, open Prop Tracker": Fire TV launches these tiny entries by name.
// Each one just tells the overlay service what to show, then closes so whatever was playing stays up.
public class VoiceActivity extends Activity {
  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    String which = getIntent() != null ? getIntent().getStringExtra("show") : null;
    if (which == null) {
      String cls = getIntent() != null && getIntent().getComponent() != null ? getIntent().getComponent().getClassName() : "";
      which = cls.endsWith("VoiceProps") ? "propcard.show" : "gamecast.show";
    }
    try {
      Intent i = new Intent(this, AlertService.class).setAction(which);
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    } catch (Exception e) {}
    finish();
  }
}
