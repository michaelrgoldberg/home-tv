package com.hometv.welcome;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
  WebView w; String home;
  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
    Uri d = getIntent() != null ? getIntent().getData() : null;
    if (d != null) { String s = d.toString(); int h = s.indexOf('#'); sp.edit().putString("home", h > 0 ? s.substring(0, h) : s).apply(); }
    home = sp.getString("home", "about:blank");
    w = new WebView(this);
    w.setBackgroundColor(0xFF0B1020);
    WebSettings st = w.getSettings();
    st.setJavaScriptEnabled(true); st.setDomStorageEnabled(true); st.setDatabaseEnabled(true);
    try { st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Exception e) {}
    st.setMediaPlaybackRequiresUserGesture(false); st.setLoadWithOverviewMode(true); st.setUseWideViewPort(true);
    w.setInitialScale(100);
    w.setWebChromeClient(new WebChromeClient());
    w.setWebViewClient(new Client(this));
    try { startSvc(new Intent(this, AlertService.class)); } catch (Exception e) {}
    w.addJavascriptInterface(new Bridge(this), "TV");
    setContentView(w);
    w.requestFocus();
    w.loadUrl(d != null ? d.toString() : home + (home.indexOf('?') < 0 ? "?" : "&") + "t=" + (System.currentTimeMillis() / 300000L));
  }
  public void startSvc(Intent i) { try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); } catch (Exception e) {} }
  boolean handle(String u) {
    try {
      if (u.startsWith("intent:")) { Intent i = Intent.parseUri(u, Intent.URI_INTENT_SCHEME); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; }
      String base = home.replaceAll("[?#].*$", "");
      if (u.startsWith(base) || u.startsWith("about:")) return false;
      if (u.startsWith("https://www.youtube-nocookie.com") || u.startsWith("https://www.youtube.com/embed")) return false;
      Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      try { i.setPackage("com.phlox.tvwebbrowser"); startActivity(i); } catch (Exception e) { i.setPackage(null); startActivity(i); }
      return true;
    } catch (Exception e) { return true; }
  }
  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (i == null || i.getData() == null) return;
    String s = i.getData().toString();
    if (s.endsWith("#dark")) { w.evaluateJavascript("window.W2W_DARK&&W2W_DARK()", null); return; }
    int h = s.indexOf('#'); home = h > 0 ? s.substring(0, h) : s; getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("home", home).apply(); w.loadUrl(s); }
  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
      if (e.getAction() == KeyEvent.ACTION_UP) {
        w.evaluateJavascript("(function(){try{return window.W2W_BACK?W2W_BACK():false}catch(e){return false}})()", new BackCb(this));
      }
      return true;
    }
    if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { try { startSvc(new Intent(this, AlertService.class).setAction("propcard.toggle")); } catch (Exception ex) {} return true; }
    return super.dispatchKeyEvent(e);
  }
  void front() { try { Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(i); } catch (Exception e) {} }
  @Override protected void onResume() { super.onResume(); w.onResume(); w.resumeTimers(); w.requestFocus(); }
  @Override protected void onPause() { w.onPause(); super.onPause(); }
}

class Client extends WebViewClient {
  final MainActivity a; Client(MainActivity a) { this.a = a; }
  @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return a.handle(r.getUrl().toString()); }
  @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return a.handle(u); }
}
@SuppressWarnings("rawtypes") class BackCb implements android.webkit.ValueCallback {
  final MainActivity a; BackCb(MainActivity a) { this.a = a; }
  public void onReceiveValue(Object v) { if (!"true".equals(String.valueOf(v))) a.moveTaskToBack(true); }
}

class Bridge {
  final MainActivity a; final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
  Bridge(MainActivity a) { this.a = a; }
  android.media.AudioManager am() { return (android.media.AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE); }
  void key(int code) { try { am().dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); am().dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)); } catch (Exception e) {} }
  @android.webkit.JavascriptInterface public int version() { return 6; }
  @android.webkit.JavascriptInterface public boolean setGameAlerts(final boolean on) { if (on) AlertService.muteUntil = 0; else AlertService.muteUntil = System.currentTimeMillis() + 60 * 60000L; try { Intent i = new Intent(a, AlertService.class); i.setAction("mute"); i.putExtra("min", on ? 0 : 60); a.startSvc(i); } catch (Exception e) {} return true; }
  @android.webkit.JavascriptInterface public int gameAlertsOn() { return AlertService.muteUntil > System.currentTimeMillis() ? 0 : 1; }
  @android.webkit.JavascriptInterface public int muteMinutesLeft() { long d = AlertService.muteUntil - System.currentTimeMillis(); return d > 0 ? (int) Math.ceil(d / 60000.0) : 0; }
  @android.webkit.JavascriptInterface public void sleepTimer(int minutes, int fadeMinutes) { Intent i = new Intent(a, SleepService.class); i.putExtra("min", minutes); i.putExtra("fade", fadeMinutes); i.putExtra("home", a.home.replaceAll("[?#].*$", "")); a.startSvc(i); }
  @android.webkit.JavascriptInterface public void sleepCancel() { h.post(new CancelSleep()); }
  @android.webkit.JavascriptInterface public boolean sleepRunning() { return SleepService.cur != null; }
  @android.webkit.JavascriptInterface public void keepOn(final boolean on) { h.post(new KeepOn(a, on)); }
  @android.webkit.JavascriptInterface public void pauseMedia() { key(KeyEvent.KEYCODE_MEDIA_PAUSE); }
  @android.webkit.JavascriptInterface public void playMedia() { key(KeyEvent.KEYCODE_MEDIA_PLAY); }
  @android.webkit.JavascriptInterface public int volume() { try { return am().getStreamVolume(android.media.AudioManager.STREAM_MUSIC); } catch (Exception e) { return -1; } }
  @android.webkit.JavascriptInterface public int maxVolume() { try { return am().getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC); } catch (Exception e) { return -1; } }
  @android.webkit.JavascriptInterface public void setVolume(int v) { try { am().setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.max(0, v), 0); } catch (Exception e) {} }
  // open another app (e.g. Spotify playlist) and come back to this screen a few seconds later
  @android.webkit.JavascriptInterface public void openThenReturn(final String uri, final String pkg, int backAfterMs) {
    h.post(new Open(a, uri, pkg));
    if (backAfterMs > 0) h.postDelayed(new Front(a), backAfterMs);
  }
}
class KeepOn implements Runnable { final MainActivity a; final boolean on; KeepOn(MainActivity a, boolean on) { this.a = a; this.on = on; }
  public void run() { if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } }
class Open implements Runnable { final MainActivity a; final String uri, pkg; Open(MainActivity a, String u, String p) { this.a = a; uri = u; pkg = p; }
  public void run() { try { Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)); if (pkg != null && pkg.length() > 0) i.setPackage(pkg); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(i); } catch (Exception e) {} } }
class Front implements Runnable { final MainActivity a; Front(MainActivity a) { this.a = a; } public void run() { a.front(); } }

class CancelSleep implements Runnable { public void run() { if (SleepService.cur != null) SleepService.cur.cancel(); } }
