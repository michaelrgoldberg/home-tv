package com.hometv.welcome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

// Lets the ☰ Menu button on the remote toggle the floating prop card from inside ANY app
// (Netflix, live TV, etc). This is the only way to capture a remote key while another app
// is focused. It filters key events only — it does not read screen content.
public class GcAccessibilityService extends AccessibilityService {
  @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
  @Override public void onInterrupt() {}

  @Override protected boolean onKeyEvent(KeyEvent e) {
    if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      if (e.getAction() == KeyEvent.ACTION_DOWN) {
        try {
          Intent i = new Intent(this, AlertService.class).setAction("propcard.toggle");
          if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ex) {}
      }
      return true; // consume both down and up so the host app doesn't also react
    }
    return false; // everything else passes through untouched
  }
}
