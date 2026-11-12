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
  // Home button: from an app -> welcome screen; double-press on the welcome screen -> back to the last app.
  private static final java.util.Set<String> IGNORE = new java.util.HashSet<String>(java.util.Arrays.asList(
      "com.android.systemui", "android", "com.amazon.tv.launcher", "com.amazon.vizzini", "com.amazon.tv.settings.v2",
      "com.amazon.device.software.ota", "com.amazon.tv.notificationcenter", "com.amazon.tv.quicksettings.ui",
      "com.amazon.ftv.profilepicker", "com.tabdeveloper.tvoverlay", "com.spocky.projengmenu", "com.amazon.tv.oobe", "com.amazon.ftv.screensaver"));
  private String curFg = null;
  private long seqStart = 0; private int seqCount = 0; private String seqOrigin = null;

  @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    try {
      if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
      CharSequence pk = e.getPackageName(), cl = e.getClassName(); if (pk == null) return;
      String pkg = pk.toString(), cls = cl == null ? "" : cl.toString();
      if (pkg.equals("com.amazon.tv.launcher")) { if (cls.contains("HomeActivity")) onHome(); return; }
      if (pkg.equals(getPackageName())) { if (cls.endsWith("MainActivity")) curFg = pkg; return; }   // ignore our own overlays
      if (IGNORE.contains(pkg) || cls.startsWith("android.widget") || cls.startsWith("android.view") || cls.startsWith("android.app.Dialog")) return;
      curFg = pkg;
      getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("lastApp", pkg).apply();
    } catch (Exception ex) {}
  }
  private void onHome() {
    if (System.currentTimeMillis() < graceUntil) return;                                   // "Hisense Home" button was used
    if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("homeGuard", true)) return;
    long now = android.os.SystemClock.uptimeMillis();
    if (now - seqStart > 900) { seqStart = now; seqCount = 1; seqOrigin = curFg; } else seqCount++;
    if (seqCount >= 2 && getPackageName().equals(seqOrigin) && returnToLastApp(this)) { seqStart = 0; return; }
    try { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch (Exception ex) {}
  }
  public static boolean returnToLastApp(android.content.Context c) {
    try {
      String pkg = c.getSharedPreferences("cfg", MODE_PRIVATE).getString("lastApp", null); if (pkg == null) return false;
      android.content.pm.PackageManager pm = c.getPackageManager();
      Intent li = pm.getLeanbackLaunchIntentForPackage(pkg); if (li == null) li = pm.getLaunchIntentForPackage(pkg); if (li == null) return false;
      li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(li); return true;
    } catch (Exception ex) { return false; }
  }
  public static String lastAppLabel(android.content.Context c) {
    try { String pkg = c.getSharedPreferences("cfg", MODE_PRIVATE).getString("lastApp", null); if (pkg == null) return "";
      android.content.pm.PackageManager pm = c.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ex) { return ""; }
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
