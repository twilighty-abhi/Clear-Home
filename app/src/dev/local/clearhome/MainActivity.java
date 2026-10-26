package dev.local.clearhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** The home screen: the Clear Home page in a WebView (see WebPage) over the shared Core. */
public class MainActivity extends Activity {
    private WebPage page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // No TV screensaver while Clear Home is open: the photo-frame view already is one.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FlatService.start(this);
        page = new WebPage(this, "/index.html");
        setContentView(page.view);
        page.view.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we're already in front.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) page.js("ClearHome.onHome()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        page.view.requestFocus();
        page.js("ClearHome.onResume()");
        Core.get(this).shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Core.get(this).hidden();
    }

    @Override
    protected void onDestroy() {
        page.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                page.js(k == KeyEvent.KEYCODE_BACK ? "ClearHome.onBack()" : "ClearHome.onMenu()");
            }
            return true; // a launcher never finishes on Back
        }
        return super.dispatchKeyEvent(e);
    }
}
