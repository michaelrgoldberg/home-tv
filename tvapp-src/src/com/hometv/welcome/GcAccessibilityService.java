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

  private long menuDownTime = 0;

  @Override protected boolean onKeyEvent(KeyEvent e) {
    if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      if (e.getAction() == KeyEvent.ACTION_DOWN) {
        if (menuDownTime == 0) menuDownTime = e.getEventTime();
      } else if (e.getAction() == KeyEvent.ACTION_UP) {
        long held = e.getEventTime() - menuDownTime;
        menuDownTime = 0;
        // short press = prop card (bottom-left); long press = Gamecast panel (left)
        String action = held >= 500 ? "gamecast.toggle" : "propcard.toggle";
        try {
          Intent i = new Intent(this, AlertService.class).setAction(action);
          if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ex) {}
      }
      return true; // consume so the host app doesn't also react
    }
    return false; // everything else passes through untouched
  }
}
