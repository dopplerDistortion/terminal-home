package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * Screensaver: Home's trending feed full screen (PanScene), each picture panning slowly for 45 s and the next
 * one dissolving in. It answers the remote: OK opens the slide as Home's card does (a YouTube trailer itself;
 * Apple TV and Nuvio only as apps, since TMDB gives no link into them), Left and Right change the slide;
 * any other key wakes the TV. Uses the feed Home already cached, pictures included, so it also runs offline;
 * with no TMDB key or nothing cached yet it shows only the clock over the dot grid.
 */
public class TrendingDream extends DreamService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private PanScene scene;
    private Trending trending;
    private ConnectivityManager cm;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            main.post(TrendingDream.this::updateNetwork);
        }

        @Override public void onLost(Network network) {
            main.post(TrendingDream.this::updateNetwork);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);   // OK and the arrows act here; dispatchKeyEvent wakes for the rest
        setFullscreen(true);
        setScreenBright(false);
        String key = BuildConfig.TMDB_API_KEY;
        boolean on = getResources().getBoolean(R.bool.trending_hero) && !key.isEmpty();
        // Spotify as Home has it, so a refresh from here writes the same list Home would.
        Spotify spotify = Spotify.configured(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_CLIENT_SECRET)
                ? new Spotify(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_CLIENT_SECRET, BuildConfig.SPOTIFY_REFRESH_TOKEN,
                        getString(R.string.trending_region), getSharedPreferences("spotify", MODE_PRIVATE))
                : null;
        trending = on ? new Trending(this, key, getString(R.string.trending_region), spotify) : null;
        scene = new PanScene(this, trending);
        scene.setMotion(motionAllowed());
        setContentView(scene);
        cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        updateNetwork();
    }

    /** Animations on in Config (and not reduced) and in the system's developer settings. */
    private boolean motionAllowed() {
        boolean config = true;
        try {
            Config c = Config.load(this);
            config = c.animations && !c.reducedMotion;
        } catch (RuntimeException ignored) {
        }
        return config && ValueAnimator.areAnimatorsEnabled();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        if (trending != null) trending.load(items -> {
            if (scene != null) scene.setItems(items);
        });
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (scene != null) scene.release();
        scene = null;
        super.onDetachedFromWindow();
    }

    private void updateNetwork() {
        if (scene == null || cm == null) return;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        scene.setOnline(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        Trending.Item item = scene == null ? null : scene.current();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && item != null) scene.previous();
                return item != null || wake(down);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && item != null) scene.next();
                return item != null || wake(down);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (item == null) return wake(down);
                if (down && event.getRepeatCount() == 0) open(item);
                return true;
            default:
                return wake(down);
        }
    }

    /** Any other key: back to what was on screen before the screensaver. The key itself does nothing more. */
    private boolean wake(boolean down) {
        if (down) finish();
        return true;
    }

    /** OK: the press shows for a moment (the one red on this screen), then the title opens in its app. */
    private void open(Trending.Item item) {
        Intent i = intentFor(item);
        if (i == null) {
            finish();
            return;
        }
        scene.showOpening(item);
        main.postDelayed(() -> {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // Uninstalled since the feed was cached: just wake.
            }
            finish();
        }, 240);
    }

    /** The same links Home's trending card opens (MainActivity.openTrending). */
    private Intent intentFor(Trending.Item item) {
        if (item.uri != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).setPackage(item.pkg);
            if (i.resolveActivity(getPackageManager()) != null) return i;
        } else if (item.youtubeKey != null) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + item.youtubeKey)).setPackage(item.pkg);
        }
        return getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
    }
}
