package com.hometv.welcome;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

// Sleep timer that keeps running while another app (SmartTube) plays the scene:
// fades the picture (black overlay) and the volume over the last minutes, then pauses playback and returns to a dark screen.
public class SleepService extends Service implements Runnable {
  static SleepService cur;
  final Handler h = new Handler(Looper.getMainLooper());
  long end, fadeMs; int vol0 = -1; View dim; String home;
  @Override public IBinder onBind(Intent i) { return null; }
  @Override public int onStartCommand(Intent i, int f, int id) {
    if (i == null) { stopSelf(); return START_NOT_STICKY; }
    if (cur != null && cur != this) cur.cancel();
    cur = this;
    int min = i.getIntExtra("min", 30), fade = i.getIntExtra("fade", 5);
    home = i.getStringExtra("home");
    end = System.currentTimeMillis() + min * 60000L; fadeMs = Math.min(fade * 60000L, min * 30000L);
    Notification note;
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      nm.createNotificationChannel(new NotificationChannel("sleep", "Sleep timer", NotificationManager.IMPORTANCE_LOW));
      note = new Notification.Builder(this, "sleep").setContentTitle("Sleep timer running").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).build();
    } else {
      note = new Notification.Builder(this).setContentTitle("Sleep timer running").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setPriority(Notification.PRIORITY_LOW).build();
    }
    startForeground(7, note);
    h.removeCallbacks(this); h.postDelayed(this, 5000);
    return START_NOT_STICKY;
  }
  AudioManager am() { return (AudioManager) getSystemService(Context.AUDIO_SERVICE); }
  public void run() {
    long left = end - System.currentTimeMillis();
    try {
      if (left < fadeMs) {
        float k = Math.max(0f, (float) left / (float) fadeMs);
        if (dim == null) {
          dim = new View(this); dim.setBackgroundColor(0xFF000000); dim.setAlpha(0f);
          WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
          ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(dim, lp);
          vol0 = am().getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        dim.animate().alpha(1f - k).setDuration(4500).start();
        if (vol0 > 0) am().setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, Math.round(vol0 * k)), 0);
      }
    } catch (Exception e) {}
    if (left <= 0) { finish(); return; }
    h.postDelayed(this, 5000);
  }
  void key(int c) { try { am().dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, c)); am().dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, c)); } catch (Exception e) {} }
  void finish() {
    key(KeyEvent.KEYCODE_MEDIA_PAUSE);
    try { Intent i = new Intent(this, MainActivity.class); i.setAction(Intent.ACTION_VIEW); i.setData(Uri.parse(home + "#dark")); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); } catch (Exception e) {}
    h.postDelayed(new Cleanup(this), 4000);
  }
  void cancel() { h.removeCallbacks(this); cleanup(); }
  void cleanup() {
    try { if (vol0 > 0) am().setStreamVolume(AudioManager.STREAM_MUSIC, vol0, 0); } catch (Exception e) {}
    try { if (dim != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(dim); } catch (Exception e) {}
    dim = null; vol0 = -1; if (cur == this) cur = null; stopForeground(true); stopSelf();
  }
  @Override public void onDestroy() { h.removeCallbacks(this); super.onDestroy(); }
}
class Cleanup implements Runnable { final SleepService s; Cleanup(SleepService s) { this.s = s; } public void run() { s.cleanup(); } }
