package dev.local.clearhome;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Shared data for every Clear Home screen (the home screen and the screensaver):
 * photos, the flat (notes, flatmates, chores, TV time), alerts, traffic and AC plugs.
 * Created once per process; screens subscribe to changes and say when they're visible.
 */
final class Core {
    interface Page { void js(String code); }

    private static Core instance;

    final GoogleDrive photos;
    final Tapo tapo;
    final Commute commute;
    final Place place;
    final Alerts alerts;
    final House house;
    final NowPlaying nowPlaying;
    final Timers timers;

    private final Set<Page> pages = new CopyOnWriteArraySet<>();
    private int visible = 0;
    // Which optional cards the dashboard shows; hidden ones aren't polled (Tapo logs in every minute).
    private volatile boolean wantAc = false, wantTraffic = false;

    static synchronized Core get(Context c) {
        if (instance == null) instance = new Core(c.getApplicationContext());
        return instance;
    }

    private Core(Context app) {
        photos = new GoogleDrive(app, () -> broadcast("ClearHome.photosChanged()"));
        tapo = new Tapo(app, () -> broadcast("ClearHome.acChanged()"));
        place = new Place(app, () -> {
            if (instance == null) return;
            instance.alerts.placeChanged();
            instance.commute.placeChanged();
            broadcast("ClearHome.placeChanged && ClearHome.placeChanged()");
        });
        commute = new Commute(app, place, () -> broadcast("ClearHome.acChanged()"));
        alerts = new Alerts(place, () -> broadcast("ClearHome.acChanged()"));
        house = new House(app, () -> { broadcast("ClearHome.acChanged()"); if (instance != null) instance.timers.changed(); });
        house.photos = photos;
        nowPlaying = new NowPlaying(app, () -> broadcast("ClearHome.mediaChanged && ClearHome.mediaChanged()"));
        nowPlaying.start();
        timers = new Timers(app, () -> house.state().optJSONArray("timers"), () -> visible > 0);
        timers.changed();
        house.start();
    }

    void attach(Page p) { pages.add(p); }

    void detach(Page p) { pages.remove(p); }

    void broadcast(String code) {
        for (Page p : pages) p.js(code);
    }

    /** A screen became visible: poll plugs/traffic/alerts while anything is on screen. */
    synchronized void shown() {
        if (visible++ > 0) return;
        photos.maybeSync();
        if (wantAc) tapo.start();
        if (wantTraffic) commute.start();
        alerts.start();
    }

    /** The dashboard says which optional cards it shows. */
    synchronized void wantCards(boolean ac, boolean traffic) {
        if (ac == wantAc && traffic == wantTraffic) return;
        wantAc = ac;
        wantTraffic = traffic;
        if (visible == 0) return;
        if (ac) tapo.start(); else tapo.stop();
        if (traffic) commute.start(); else commute.stop();
    }

    synchronized void hidden() {
        if (visible == 0 || --visible > 0) return;
        tapo.stop();
        commute.stop();
        alerts.stop();
    }
}
