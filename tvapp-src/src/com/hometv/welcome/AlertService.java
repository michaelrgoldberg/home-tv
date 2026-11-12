package com.hometv.welcome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

// Draws interactive game-alert cards and a persistent scorebug on top of whatever is playing.
// The laptop score-watcher POSTs events to this service over the LAN (port 8777).
public class AlertService extends Service {
  static AlertService cur;
  static long muteUntil = 0;             // epoch ms; while in the future, alerts are suppressed
  final Handler h = new Handler(Looper.getMainLooper());
  WindowManager wm;
  View card;            // focusable interactive pop-up
  View bug;             // non-focusable persistent scorebug
  WebView propCard;     // non-focusable persistent prop-tracker card (bottom-left)
  WebView gamecast;     // focusable gamecast panel (left), D-pad switches games
  WebView ticker;       // non-focusable full-width score ticker along the bottom (off / all / mine / props)
  volatile boolean homeFront = false;  // welcome screen in front -> it has its own ticker, so hide ours
  TextView bugText; ImageView bugLogo;
  ServerSocket server;
  volatile boolean running = false;

  @Override public IBinder onBind(Intent i) { return null; }

  @Override public int onStartCommand(Intent i, int flags, int id) { self = this;
    cur = this;
    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    Notification note;
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      nm.createNotificationChannel(new NotificationChannel("alerts", "Game alerts", NotificationManager.IMPORTANCE_MIN));
      note = new Notification.Builder(this, "alerts").setContentTitle("Game alerts ready").setSmallIcon(android.R.drawable.ic_menu_info_details).build();
    } else {
      note = new Notification.Builder(this).setContentTitle("Game alerts ready").setSmallIcon(android.R.drawable.ic_menu_info_details).setPriority(Notification.PRIORITY_MIN).build();
    }
    startForeground(8, note);
    final String act = i != null ? i.getAction() : null;
    if ("mute".equals(act)) { int m = i.getIntExtra("min", 60); muteUntil = m <= 0 ? 0 : System.currentTimeMillis() + m * 60000L; if (muteUntil > System.currentTimeMillis()) h.post(new Runnable(){ public void run(){ clearBug(); dismissCard(); } }); }
    else if ("propcard.toggle".equals(act)) h.post(new Runnable(){ public void run(){ togglePropCard(); } });
    else if ("propcard.show".equals(act)) h.post(new Runnable(){ public void run(){ showPropCard(); } });
    else if ("propcard.hide".equals(act)) h.post(new Runnable(){ public void run(){ hidePropCard(); } });
    else if ("gamecast.toggle".equals(act)) h.post(new Runnable(){ public void run(){ toggleGamecast(); } });
    else if ("gamecast.show".equals(act)) h.post(new Runnable(){ public void run(){ showGamecast(); } });
    else if ("gamecast.hide".equals(act)) h.post(new Runnable(){ public void run(){ hideGamecast(); } });
    if (!running) { running = true; new Thread(new Server()).start(); h.postDelayed(new Runnable(){ public void run(){ syncTicker(); } }, 3000); }
    return START_STICKY;
  }

  // ---- helpers ----
  int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
  static AlertService self;
  boolean muted() { return muteUntil > System.currentTimeMillis(); }
  // persistent switches from the dashboard / Gamecast settings: alerts=all game alerts, banners=top banner + scorebug only
  boolean bannersOn() { SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE); return sp.getBoolean("alerts", true) && sp.getBoolean("banners", true); }
  void setAlertPrefs(boolean alerts, boolean banners) { getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("alerts", alerts).putBoolean("banners", banners).apply(); if (!(alerts && banners)) { clearBug(); h.post(new Runnable(){ public void run(){ dismissCard(); } }); }
    tvOverlay("{\"displayFixedNotifications\":" + (alerts && banners) + ",\"displayNotifications\":" + alerts + "}"); }
  // TvOverlay (separate app on this TV, port 5001) shows the laptop watcher's top-right score; keep it in step with our switches
  void tvOverlay(final String json) { new Thread(new Runnable() { public void run() { try {
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL("http://127.0.0.1:5001/set/notifications").openConnection();
    c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(2000); c.setReadTimeout(2000); c.setRequestProperty("Content-Type", "application/json");
    OutputStream o = c.getOutputStream(); o.write(json.getBytes("UTF-8")); o.close(); c.getResponseCode(); c.disconnect(); } catch (Exception e) {} } }).start(); }

  // ---------- view settings: Gamecast size, bottom ticker ----------
  SharedPreferences cfg() { return getSharedPreferences("cfg", MODE_PRIVATE); }
  int[] gcDims() { String s = cfg().getString("gcSize", "m"); if ("s".equals(s)) return new int[]{dp(310), dp(330)}; if ("l".equals(s)) return new int[]{dp(460), dp(468)}; return new int[]{dp(360), dp(400)}; }
  void setGamecastSize(String s) { cfg().edit().putString("gcSize", s).apply(); h.post(new Runnable(){ public void run(){
    if (gamecast == null) return; try { WindowManager.LayoutParams p = (WindowManager.LayoutParams) gamecast.getLayoutParams(); int[] d = gcDims(); p.width = d[0]; p.height = d[1]; wm.updateViewLayout(gamecast, p); } catch (Exception e) {} } }); }
  boolean tickerWanted() { String m = cfg().getString("ticker", "off"); return m.length() > 0 && !"off".equals(m); }
  void setTicker(String m) { cfg().edit().putString("ticker", m).apply(); h.post(new Runnable(){ public void run(){ syncTicker(); } }); }
  void setHomeFront(boolean on) { homeFront = on; h.post(new Runnable(){ public void run(){ syncTicker(); } }); }
  int tickerH() { return dp(44); }
  void syncTicker() { homeFront = MainActivity.resumed; if (tickerWanted() && !homeFront) showTicker(); else hideTicker(); placePropCard(); }
  void showTicker() {
    if (ticker != null) return;
    String base = baseUrl(); if (base.length() == 0) return;
    try {
      WebView wv = makeWeb(base + "props.html?ticker=1&t=" + (System.currentTimeMillis() / 300000L));
      wv.setFocusable(false);
      WindowManager.LayoutParams p = overlayParams(false);
      p.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
      p.gravity = Gravity.BOTTOM | Gravity.START; p.x = 0; p.y = 0;
      p.width = ViewGroup.LayoutParams.MATCH_PARENT; p.height = tickerH();
      ticker = wv; wm.addView(ticker, p);
    } catch (Exception e) { android.util.Log.e("HOMETV", "ticker fail", e); ticker = null; }
  }
  void hideTicker() { if (ticker != null) { try { wm.removeView(ticker); } catch (Exception e) {} try { ticker.destroy(); } catch (Exception e) {} ticker = null; } }
  int propY() { return dp(14) + (ticker != null ? tickerH() + dp(4) : 0); }
  void placePropCard() { if (propCard == null) return; try { WindowManager.LayoutParams p = (WindowManager.LayoutParams) propCard.getLayoutParams(); if (p.y != propY()) { p.y = propY(); wm.updateViewLayout(propCard, p); } } catch (Exception e) {} }

  GradientDrawable bgCard() { GradientDrawable g = new GradientDrawable(); g.setColor(0xF00B1020); g.setCornerRadius(dp(14)); g.setStroke(dp(2), 0xFFFFCC00); return g; }

  void loadLogo(final ImageView iv, final String url) {
    if (url == null || url.length() == 0) { iv.setVisibility(View.GONE); return; }
    new Thread(new Runnable() { public void run() {
      try { InputStream is = new URL(url).openStream(); final Bitmap bm = BitmapFactory.decodeStream(is); is.close();
        if (bm != null) h.post(new Runnable(){ public void run(){ iv.setImageBitmap(bm); } }); } catch (Exception e) {}
    } }).start();
  }

  // ---------- persistent scorebug ----------
  void showBug(final String text, final String logo) {
    h.post(new Runnable() { public void run() {
      if (muted() || !bannersOn()) return;
      if (bug == null) {
        LinearLayout row = new LinearLayout(AlertService.this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xD9000000); g.setCornerRadius(dp(10)); g.setStroke(dp(2), 0xFFFFCC00);
        row.setBackground(g); row.setPadding(dp(10), dp(5), dp(12), dp(5));
        bugLogo = new ImageView(AlertService.this); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26)); lp.rightMargin = dp(8); row.addView(bugLogo, lp);
        bugText = new TextView(AlertService.this); bugText.setTextColor(Color.WHITE); bugText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); bugText.setTypeface(Typeface.DEFAULT_BOLD); row.addView(bugText);
        WindowManager.LayoutParams p = overlayParams(false);
        p.gravity = Gravity.TOP | Gravity.START; p.x = dp(16); p.y = dp(12);
        bug = row; wm.addView(bug, p);
      }
      bugText.setText(text); loadLogo(bugLogo, logo);
    } });
  }
  void clearBug() { h.post(new Runnable(){ public void run(){ if (bug != null) { try { wm.removeView(bug); } catch (Exception e) {} bug = null; } } }); }

  // ---------- floating prop-tracker card (bottom-left, non-focusable so it never steals the remote) ----------
  String baseUrl() { SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE); String home = sp.getString("home", ""); return home.replaceAll("[?#].*$", ""); }
  boolean propFocused = false;
  // ☰ double-press: show -> take the remote -> hide
  void togglePropCard() { if (propCard == null) showPropCard(); else if (!propFocused) focusPropCard(); else hidePropCard(); }
  void setPropFocusable(boolean on) {
    if (propCard == null) return;
    try { WindowManager.LayoutParams p = (WindowManager.LayoutParams) propCard.getLayoutParams();
      if (on) p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else p.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      wm.updateViewLayout(propCard, p); propFocused = on;
      if (on) { propCard.setFocusable(true); propCard.setFocusableInTouchMode(true); propCard.requestFocus(); }
      propCard.evaluateJavascript("window.PROPS&&PROPS.focus&&PROPS.focus(" + (on ? "true" : "false") + ")", null);
    } catch (Exception e) {}
  }
  void focusPropCard() { setPropFocusable(true); }
  void releasePropCard() { setPropFocusable(false); if (gamecast != null) { try { WindowManager.LayoutParams p = (WindowManager.LayoutParams) gamecast.getLayoutParams(); wm.updateViewLayout(gamecast, p); gamecast.requestFocus(); } catch (Exception e) {} } }
  void showPropCard() {
    if (propCard != null) return;
    String base = baseUrl(); if (base.length() == 0) return;
    try {
      WebView wv = new WebView(this);
      wv.setBackgroundColor(0x00000000);
      WebSettings s = wv.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
      try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Exception e) {}
      s.setMediaPlaybackRequiresUserGesture(false);
      wv.setWebViewClient(new WebViewClient());
      wv.loadUrl(base + "props.html?corner=1&t=" + (System.currentTimeMillis() / 300000L));
      wv.addJavascriptInterface(new OverlayBridge(), "OV");
      wv.setOnKeyListener(new android.view.View.OnKeyListener() {
        public boolean onKey(android.view.View v, int code, KeyEvent ev) {
          if (!propFocused) return false;
          if (ev.getAction() != KeyEvent.ACTION_UP) return code == KeyEvent.KEYCODE_BACK;
          if (code == KeyEvent.KEYCODE_BACK) { releasePropCard(); return true; }
          if (code == KeyEvent.KEYCODE_DPAD_UP && gamecast != null) { releasePropCard(); return true; }
          return false;
        }
      });
      WindowManager.LayoutParams p = overlayParams(false);
      p.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
      p.gravity = Gravity.BOTTOM | Gravity.START; p.x = dp(14); p.y = propY();
      p.width = dp(250); p.height = dp(300);
      propCard = wv; wm.addView(propCard, p);
      android.util.Log.i("HOMETV", "propcard shown");
      try { propCard.evaluateJavascript("window.PROPS&&PROPS.summon&&PROPS.summon()", null); } catch (Exception e) {}
    } catch (Exception e) { android.util.Log.e("HOMETV", "propcard fail", e); propCard = null; }
  }
  void hidePropCard() { propFocused = false; if (propCard != null) { try { wm.removeView(propCard); } catch (Exception e) {} try { propCard.destroy(); } catch (Exception e) {} propCard = null; } }

  volatile boolean gcHandoffOk = true;   // gamecast page says whether ▾ should hand off (false while expanded / in settings)
  class OverlayBridge {
    @android.webkit.JavascriptInterface public void handoff(boolean ok) { gcHandoffOk = ok; }
    @android.webkit.JavascriptInterface public void focusProps() { h.post(new Runnable(){ public void run(){ if (propCard == null) showPropCard(); focusPropCard(); } }); }
    @android.webkit.JavascriptInterface public void focusGamecast() { h.post(new Runnable(){ public void run(){ releasePropCard(); } }); }
    @android.webkit.JavascriptInterface public void openGamecast() { h.post(new Runnable(){ public void run(){ if (gamecast == null) showGamecast(); releasePropCard(); } }); }
    @android.webkit.JavascriptInterface public void hideProps() { h.post(new Runnable(){ public void run(){ hidePropCard(); } }); }
    @android.webkit.JavascriptInterface public void hideGamecast() { h.post(new Runnable(){ public void run(){ AlertService.this.hideGamecast(); } }); }
    @android.webkit.JavascriptInterface public boolean propsShown() { return propCard != null; }
    @android.webkit.JavascriptInterface public boolean gamecastShown() { return gamecast != null; }
    @android.webkit.JavascriptInterface public void setAlertPrefs(boolean alerts, boolean banners) { AlertService.this.setAlertPrefs(alerts, banners); }
    @android.webkit.JavascriptInterface public void setTicker(String m) { AlertService.this.setTicker(m); }
    @android.webkit.JavascriptInterface public void setGamecastSize(String s) { AlertService.this.setGamecastSize(s); }
  }
  WebView makeWeb(String url) {
    WebView wv = new WebView(this);
    wv.setBackgroundColor(0x00000000);
    WebSettings s = wv.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
    try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Exception e) {}
    s.setMediaPlaybackRequiresUserGesture(false);
    wv.setWebViewClient(new WebViewClient());
    wv.loadUrl(url);
    return wv;
  }

  // ---------- gamecast panel (focusable, left side): D-pad ◂▸ switches games, OK expands, Back hides ----------
  void toggleGamecast() { if (gamecast != null) hideGamecast(); else showGamecast(); }
  void showGamecast() {
    if (gamecast != null) return;
    String base = baseUrl(); if (base.length() == 0) return;
    try {
      WebView wv = makeWeb(base + "gamecast.html?overlay=1&t=" + (System.currentTimeMillis() / 300000L));
      wv.addJavascriptInterface(new OverlayBridge(), "OV");
      wv.setFocusable(true); wv.setFocusableInTouchMode(true);
      wv.setOnKeyListener(new android.view.View.OnKeyListener() {
        public boolean onKey(android.view.View v, int code, KeyEvent ev) {
          if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { hideGamecast(); return true; }
          if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_UP && propCard != null && gcHandoffOk) { if (gamecast != null) gamecast.evaluateJavascript("window.GC&&GC.blur&&GC.blur()", null); focusPropCard(); return true; }
          return false;
        }
      });
      WindowManager.LayoutParams p = overlayParams(true);
      p.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
      // top-left so it never covers the prop/parlay card that lives bottom-left
      p.gravity = Gravity.START | Gravity.TOP; p.x = dp(14); p.y = dp(20);
      int[] gd = gcDims(); p.width = gd[0]; p.height = gd[1];
      gamecast = wv; wm.addView(gamecast, p); wv.requestFocus();
      android.util.Log.i("HOMETV", "gamecast shown");
    } catch (Exception e) { android.util.Log.e("HOMETV", "gamecast fail", e); gamecast = null; }
  }
  void hideGamecast() { if (gamecast != null) { try { wm.removeView(gamecast); } catch (Exception e) {} try { gamecast.destroy(); } catch (Exception e) {} gamecast = null; } }

  WindowManager.LayoutParams overlayParams(boolean focusable) {
    int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE, flags, android.graphics.PixelFormat.TRANSLUCENT);
    return p;
  }

  // ---------- interactive pop-up card ----------
  Runnable autoClose = new Runnable() { public void run() { dismissCard(); } };

  void showCard(final JSONObject ev) {
    h.post(new Runnable() { public void run() {
      if (muted() || !bannersOn()) return;
      dismissCard();
      final String title = ev.optString("title", "Update");
      final String message = ev.optString("message", "");
      final String logo = ev.optString("logo", "");
      final String watchUrl = ev.optString("watchUrl", "");
      final JSONArray stats = ev.optJSONArray("stats");

      final FrameLayout rootF = new FrameLayout(AlertService.this);
      final LinearLayout cardL = new LinearLayout(AlertService.this); cardL.setOrientation(LinearLayout.VERTICAL);
      cardL.setBackground(bgCard()); cardL.setPadding(dp(14), dp(12), dp(14), dp(12));
      cardL.setMinimumWidth(dp(330));

      // header: logo + title
      LinearLayout head = new LinearLayout(AlertService.this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
      ImageView lg = new ImageView(AlertService.this); LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(34), dp(34)); llp.rightMargin = dp(10); head.addView(lg, llp); loadLogo(lg, logo);
      TextView tv = new TextView(AlertService.this); tv.setText(title); tv.setTextColor(Color.WHITE); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19); tv.setTypeface(Typeface.DEFAULT_BOLD); head.addView(tv);
      cardL.addView(head);

      TextView msg = new TextView(AlertService.this); msg.setText(message); msg.setTextColor(0xFFD5DEF5); msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2); mp.topMargin = dp(6); cardL.addView(msg, mp);

      // expandable area (stats + watch), hidden until expanded
      final LinearLayout extra = new LinearLayout(AlertService.this); extra.setOrientation(LinearLayout.VERTICAL); extra.setVisibility(View.GONE);
      LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2); ep.topMargin = dp(8); cardL.addView(extra, ep);
      if (stats != null) for (int k = 0; k < stats.length(); k++) {
        TextView s = new TextView(AlertService.this); s.setText("• " + stats.optString(k)); s.setTextColor(0xFFBFC9E6); s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); s.setPadding(0, dp(2), 0, dp(2)); extra.addView(s);
      }

      // hint / action row
      final TextView hint = new TextView(AlertService.this);
      hint.setTextColor(0xFF9AA3C0); hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
      LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2); hp.topMargin = dp(10); cardL.addView(hint, hp);

      rootF.addView(cardL);
      final boolean[] expanded = {false};
      final int[] focus = {0}; // 0 = card, 1 = mute
      final Runnable paint = new Runnable() { public void run() {
        cardL.setBackground(bgCard());
        GradientDrawable g = (GradientDrawable) cardL.getBackground();
        g.setStroke(dp(2), focus[0] == 0 ? 0xFFFFFFFF : 0xFFFFCC00);
        hint.setText(focus[0] == 1 ? "🔕 Mute game alerts (1 hr) — press OK"
          : (expanded[0] ? (watchUrl.length() > 0 ? "OK ▶ Watch the game   ·   ▾ Mute   ·   Back closes" : "▾ Mute   ·   Back closes")
            : "OK: more stats" + (watchUrl.length() > 0 ? " + watch" : "") + "   ·   ▾ Mute   ·   Back closes"));
      } };

      rootF.setFocusable(true); rootF.setFocusableInTouchMode(true);
      rootF.setOnKeyListener(new View.OnKeyListener() {
        public boolean onKey(View v, int code, KeyEvent e) {
          if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
          h.removeCallbacks(autoClose);
          if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focus[0] = 1; paint.run(); return true; }
          if (code == KeyEvent.KEYCODE_DPAD_UP) { focus[0] = 0; paint.run(); return true; }
          if (code == KeyEvent.KEYCODE_BACK) { dismissCard(); return true; }
          if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (focus[0] == 1) { muteUntil = System.currentTimeMillis() + 60 * 60000L; clearBug(); dismissCard(); return true; }
            if (!expanded[0]) { expanded[0] = true; extra.setVisibility(View.VISIBLE); paint.run(); return true; }
            if (watchUrl.length() > 0) { openWatch(watchUrl); dismissCard(); return true; }
            return true;
          }
          return false;
        }
      });
      paint.run();
      WindowManager.LayoutParams p = overlayParams(true);
      p.gravity = Gravity.TOP | Gravity.END; p.x = dp(16); p.y = dp(12);
      p.windowAnimations = android.R.style.Animation_Toast;
      card = rootF; wm.addView(card, p); rootF.requestFocus();
      h.postDelayed(autoClose, ev.optBoolean("big", false) ? 14000 : 10000);
    } });
  }
  void dismissCard() { h.removeCallbacks(autoClose); if (card != null) { try { wm.removeView(card); } catch (Exception e) {} card = null; } }

  void openWatch(String url) {
    try { Intent i;
      if (url.startsWith("intent:")) i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
      else i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
    } catch (Exception e) {}
  }

  // ---------- tiny HTTP server ----------
  class Server implements Runnable {
    public void run() {
      try { server = new ServerSocket(8777);
        while (running) { try { Socket s = server.accept(); handle(s); } catch (Exception e) { if (running) {} } }
      } catch (Exception e) {}
    }
    void handle(Socket s) {
      try {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
        String line = r.readLine(); if (line == null) { s.close(); return; }
        String[] parts = line.split(" "); String method = parts.length > 0 ? parts[0] : "GET"; String path = parts.length > 1 ? parts[1] : "/";
        int clen = 0; String h2;
        while ((h2 = r.readLine()) != null && h2.length() > 0) { if (h2.toLowerCase().startsWith("content-length:")) clen = Integer.parseInt(h2.substring(15).trim()); }
        char[] body = new char[clen]; int got = 0; while (got < clen) { int n = r.read(body, got, clen - got); if (n < 0) break; got += n; }
        String b = new String(body, 0, got);
        String resp = "ok";
        if (path.startsWith("/ping")) resp = "ok";
        else if (path.startsWith("/muted")) resp = "{\"muted\":" + muted() + ",\"minutesLeft\":" + (muted() ? (int) Math.ceil((muteUntil - System.currentTimeMillis()) / 60000.0) : 0) + "}";
        else if (path.startsWith("/mute")) { muteUntil = System.currentTimeMillis() + 60 * 60000L; clearBug(); dismissCard(); resp = "muted"; }
        else if (path.startsWith("/unmute")) { muteUntil = 0; resp = "on"; }
        else if (path.startsWith("/popup")) { try { showCard(new JSONObject(b)); } catch (Exception e) {} }
        else if (path.startsWith("/propcard/toggle")) h.post(new Runnable(){ public void run(){ togglePropCard(); } });
        else if (path.startsWith("/propcard/show")) h.post(new Runnable(){ public void run(){ showPropCard(); } });
        else if (path.startsWith("/propcard/hide")) h.post(new Runnable(){ public void run(){ hidePropCard(); } });
        else if (path.startsWith("/gamecast/toggle")) h.post(new Runnable(){ public void run(){ toggleGamecast(); } });
        else if (path.startsWith("/gamecast/show")) h.post(new Runnable(){ public void run(){ showGamecast(); } });
        else if (path.startsWith("/gamecast/hide")) h.post(new Runnable(){ public void run(){ hideGamecast(); } });
        else if (path.startsWith("/ticker/")) { final String m = path.substring(8).replaceAll("[^a-z]", ""); setTicker(m.length() > 0 ? m : "off"); }
        else if (path.startsWith("/bug/clear")) clearBug();
        else if (path.startsWith("/bug")) { try { JSONObject o = new JSONObject(b); showBug(o.optString("text"), o.optString("logo")); } catch (Exception e) {} }
        OutputStream o = s.getOutputStream();
        byte[] rb = resp.getBytes("UTF-8");
        o.write(("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: application/json\r\nContent-Length: " + rb.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        o.write(rb); o.flush(); s.close();
      } catch (Exception e) { try { s.close(); } catch (Exception e2) {} }
    }
  }

  @Override public void onDestroy() { running = false; try { if (server != null) server.close(); } catch (Exception e) {} dismissCard(); clearBug(); hidePropCard(); hideGamecast(); hideTicker(); if (cur == this) cur = null; super.onDestroy(); }
}
