package com.sameer.dashserver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Full-screen dashboard. This is the interactive twin of the screensaver:
 * Immortal's "open when you tap to exit" can point here, so tapping the
 * glanceable screensaver lands on the same page with working taps/audio.
 */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent svc = new Intent(this, DashService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // portal://launch/<package>, portal://apps and
                // portal://open?url=… let the
                // dashboard page open real apps natively — Immortal's
                // curated tiles point at Meta's browser/calling shells,
                // so the dashboard carries its own doors.
                if (url != null && url.startsWith("portal://")) {
                    try {
                        String rest = url.substring("portal://".length());
                        if (rest.equals("apps")) {
                            Intent i = new Intent();
                            i.setClassName("com.immortal.launcher",
                                    "com.immortal.launcher.HomeActivity");
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } else if (rest.startsWith("open?url=")) {
                            // portal://open?url=<urlencoded> — show-card
                            // taps land here: open the link in the real
                            // Chrome (WebRTC/pages the WebView can't
                            // carry), default browser as fallback.
                            String u = java.net.URLDecoder.decode(
                                    rest.substring("open?url=".length()), "UTF-8");
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                            i.setPackage("org.chromium.chrome");
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                startActivity(i);
                            } catch (Exception e) {
                                Intent j = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                                j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(j);
                            }
                        } else if (rest.startsWith("launch/")) {
                            String pkg = rest.substring("launch/".length());
                            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                            if (i != null) {
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            }
                        }
                    } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(web);
        // ?app=1 marks the interactive twin: the page shows app-only
        // controls (rail launch buttons, delete, upload) only here.
        web.loadUrl("http://127.0.0.1:" + DashService.PORT + "/index.html?app=1");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.reload();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
