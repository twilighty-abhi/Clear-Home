package dev.local.clearhome;

import android.service.dreams.DreamService;

/**
 * Clear Home as the TV's screensaver: the dashboard's photo-frame view (photos, clock,
 * weather, notes, alerts, who's home), shown full time. Any remote key ends it.
 * Select it under the TV's screensaver setting (see README).
 */
public class ClearHomeDream extends DreamService {
    private WebPage page;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // the first key press wakes the TV, as with any screensaver
        setFullscreen(true);
        setScreenBright(true);
        page = new WebPage(this, "/index.html?dream=1");
        setContentView(page.view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Core.get(this).shown();
    }

    @Override
    public void onDreamingStopped() {
        Core.get(this).hidden();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (page != null) page.destroy();
        page = null;
        super.onDetachedFromWindow();
    }
}
