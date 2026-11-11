package com.hometv.welcome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

// ☰ (Menu) from inside ANY app:
//   double-press  -> props & parlays card (bottom-left)
//   hold ~0.5 s   -> Gamecast panel (top-left)
// A lone short press is consumed and does nothing. Key events only; no screen content is read.
public class GcAccessibilityService extends AccessibilityService {
  // Home button guard: when the Fire TV / Hisense launcher comes to the front, bring the welcome page
  // straight back — unless the corner "Hisense Home" button asked for a short grace period.
  public static volatile long graceUntil = 0;
  @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    try {
      if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
      CharSequence pkg = e.getPackageName(), cls = e.getClassName();
      if (pkg == null || !"com.amazon.tv.launcher".contentEquals(pkg)) return;
      if (cls != null && !cls.toString().contains("HomeActivity")) return;   // settings / app grid: leave alone
      if (System.currentTimeMillis() < graceUntil) return;
      if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("homeGuard", true)) return;
      Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startActivity(i);
    } catch (Exception ex) {}
  }
  @Override public void onInterrupt() {}

  private static final long HOLD_MS = 500, DOUBLE_MS = 450;
  private long menuDownTime = 0, lastShortUp = 0;
  private boolean longFired = false;
  private final Handler h = new Handler(Looper.getMainLooper());

  private void send(String action) {
    try {
      Intent i = new Intent(this, AlertService.class).setAction(action);
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    } catch (Exception ex) {}
  }

  @Override protected boolean onKeyEvent(KeyEvent e) {
    if (e.getKeyCode() != KeyEvent.KEYCODE_MENU) return false;
    if (e.getAction() == KeyEvent.ACTION_DOWN) {
      if (menuDownTime == 0) { menuDownTime = e.getEventTime(); longFired = false; }
      else if (!longFired && e.getEventTime() - menuDownTime >= HOLD_MS) { longFired = true; send("gamecast.toggle"); }
    } else if (e.getAction() == KeyEvent.ACTION_UP) {
      long held = e.getEventTime() - menuDownTime; menuDownTime = 0;
      if (longFired) { longFired = false; return true; }
      if (held >= HOLD_MS) { send("gamecast.toggle"); return true; }
      long now = e.getEventTime();
      if (now - lastShortUp <= DOUBLE_MS) { lastShortUp = 0; send("propcard.toggle"); }
      else { lastShortUp = now; }
    }
    return true;
  }
}
