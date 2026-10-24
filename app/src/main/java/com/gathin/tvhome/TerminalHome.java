package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The terminal home screen (terminal_home in config.xml): a header, the trending card, an eight-tile dock and a
 * hint line, plus the "ls /apps" screen behind the dock's last tile. Built in code on top of MainActivity, which
 * keeps the remote keys, the launches it shares with the classic home and the trending feed; this class owns
 * the views and the focus between them.
 */
final class TerminalHome {
    interface Host {
        void open(Intent intent);
        void openTrending(Trending.Item item);
        /** Fetches the trending list again even if the cache is fresh; the result arrives through setTrending. */
        void refreshTrending();
    }

    static final String ALL_APPS = "::";
    private static final int DOCK_APPS = 7;
    private static final String PREFS = "terminal_home";
    private static final String KICK = "com.kick.mobile";
    private static final String YT_MUSIC = "com.google.android.youtube.tvmusic";
    private static final String PRIME = "com.amazon.amazonvideo.livingroom";
    private static final String SPOTIFY = "com.spotify.tv.android";
    private static final List<String> DEFAULT_DOCK = Arrays.asList(Trending.YOUTUBE, Trending.APPLE_TV, Trending.NUVIO,
            KICK, YT_MUSIC, PRIME, SPOTIFY);
    /** Short names and monograms for the default dock; any other app gets its label and Term.monogram. */
    private static final Map<String, String[]> KNOWN = new HashMap<>();
    static {
        KNOWN.put(Trending.YOUTUBE, new String[]{"YouTube", "YT"});
        KNOWN.put(Trending.APPLE_TV, new String[]{"Apple TV", "TV+"});
        KNOWN.put(Trending.NUVIO, new String[]{"Nuvio", "NV"});
        KNOWN.put(KICK, new String[]{"Kick", "KK"});
        KNOWN.put(YT_MUSIC, new String[]{"YT Music", "YM"});
        KNOWN.put(PRIME, new String[]{"Prime Video", "PV"});
        KNOWN.put(SPOTIFY, new String[]{"Spotify", "SP"});
    }

    private final Activity activity;
    private final Host host;
    private final float d;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final FrameLayout root;
    private final LinearLayout home;
    private final LinearLayout apps;
    private final TrendingView card;
    private final TermTile[] dock = new TermTile[DOCK_APPS + 1];
    private final TextView footer;
    private final TextView appsFooter;
    private final TextView appsCount;
    private final RecyclerView grid;
    private final AppsAdapter appsAdapter = new AppsAdapter();
    private final LinearLayout systemRows;
    /** Per header: the status square, the status, the date and the clock. */
    private final List<View[]> headers = new ArrayList<>();
    private TextView refreshHint;
    private TextView networkHint;
    private View menu;
    private TermTile menuTile;

    private List<String> dockPkgs;
    private int returnTile;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    TerminalHome(Activity activity, Host host, boolean trendingEnabled) {
        this.activity = activity;
        this.host = host;
        d = activity.getResources().getDisplayMetrics().density;
        pm = activity.getPackageManager();
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dockPkgs = loadDock();

        root = new FrameLayout(activity);
        root.setBackground(new Term.DotGrid(activity));

        // ---- home
        home = screen();
        home.addView(header("home"));
        card = new TrendingView(activity);
        card.setListener(host::openTrending);
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            backToDock();
            return true;
        });
        int cardOut = Math.round(TrendingView.OUT_DP * d);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.setMargins(-cardOut, dp(14) - cardOut, -cardOut, -cardOut);
        home.addView(card, clp);
        if (!trendingEnabled) card.setFocusable(false);

        LinearLayout dockHead = row();
        dockHead.addView(Term.label(activity, 10, Term.TEXT), weight());
        ((TextView) dockHead.getChildAt(0)).setText("DOCK");
        TextView dockCount = Term.label(activity, 10, Term.MUTED);
        dockCount.setText(String.format(Locale.ROOT, "%02d APPS", DOCK_APPS));
        dockHead.addView(dockCount);
        LinearLayout.LayoutParams hlp = matchWrap();
        hlp.topMargin = dp(14);
        home.addView(dockHead, hlp);

        LinearLayout dockRow = row();
        int tileOut = Math.round(TermTile.OUT_DP * d);
        for (int i = 0; i < dock.length; i++) {
            TermTile tile = new TermTile(activity);
            final int slot = i;
            tile.setListener(new TermTile.Listener() {
                @Override public void onOpen(TermTile t) {
                    if (ALL_APPS.equals(t.key)) showApps();
                    else launch(t.key, t.label());
                }

                @Override public void onHold(TermTile t) {
                    if (!ALL_APPS.equals(t.key)) appInfo(t.key);
                }
            });
            tile.setOnKeyListener((v, keyCode, event) -> dockKey(slot, keyCode, event));
            tile.setOnFocusChangeListener((v, focused) -> {
                if (focused) onDockFocused(slot);
            });
            dock[i] = tile;
            dockRow.addView(tile, new LinearLayout.LayoutParams(0, dp(88) + 2 * tileOut, 1f));
        }
        LinearLayout.LayoutParams dlp = matchWrap();
        dlp.setMargins(-tileOut, dp(8) - tileOut, -tileOut, -tileOut);
        home.addView(dockRow, dlp);

        footer = Term.label(activity, 10, Term.MUTED);
        footer.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams flp = matchWrap();
        flp.topMargin = dp(14);
        home.addView(footer, flp);
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- apps
        apps = screen();
        apps.addView(header("apps"));
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.BOTTOM);
        TextView title = new TextView(activity);
        title.setTypeface(Term.display(activity));
        title.setFontVariationSettings(Term.DISPLAY_AXES);
        title.setTextSize(30);
        title.setTextColor(Term.TEXT);
        title.setIncludeFontPadding(false);
        title.setText("ls /apps");
        titleRow.addView(title);
        appsCount = Term.label(activity, 10, Term.MUTED);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(16);
        alp.bottomMargin = dp(4);
        titleRow.addView(appsCount, alp);
        LinearLayout.LayoutParams tlp = matchWrap();
        tlp.topMargin = dp(16);
        apps.addView(titleRow, tlp);

        LinearLayout body = row();
        body.setGravity(Gravity.TOP);
        grid = new RecyclerView(activity);
        grid.setLayoutManager(new GridLayoutManager(activity, 4));
        grid.setAdapter(appsAdapter);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        glp.setMargins(-tileOut, -tileOut, 0, -tileOut);
        body.addView(grid, glp);

        LinearLayout system = new LinearLayout(activity);
        system.setOrientation(LinearLayout.VERTICAL);
        TextView sysHead = Term.label(activity, 10, Term.TEXT);
        sysHead.setText("SYSTEM");
        system.addView(sysHead);
        systemRows = new LinearLayout(activity);
        systemRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams srp = matchWrap();
        srp.topMargin = dp(10);
        system.addView(systemRows, srp);
        systemRows.addView(menuRow("INPUTS", "HDMI", this::openInputs));
        systemRows.addView(menuRow("SETTINGS", "ANDROID TV", () -> host.open(SystemSettings.intent(activity, Settings.ACTION_SETTINGS))));
        View network = menuRow("NETWORK", "", () -> host.open(SystemSettings.intent(activity, Settings.ACTION_WIFI_SETTINGS)));
        networkHint = network.findViewWithTag("hint");
        systemRows.addView(network);
        systemRows.addView(menuRow("SLEEP TIMER", "TIMER", this::openSleepTimer));
        View refresh = menuRow("REFRESH TRENDING", "TMDB", () -> {
            refreshHint.setText("FETCHING…");
            host.refreshTrending();
        });
        refreshHint = refresh.findViewWithTag("hint");
        if (!trendingEnabled) refresh.setVisibility(View.GONE);
        systemRows.addView(refresh);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(24);
        body.addView(system, slp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(20);
        apps.addView(body, blp);

        appsFooter = Term.label(activity, 10, Term.MUTED);
        appsFooter.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams aflp = matchWrap();
        aflp.topMargin = dp(14);
        apps.addView(appsFooter, aflp);
        apps.setVisibility(View.GONE);
        root.addView(apps, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> updateFooter(newF));
        bindDock();
        updateClock();
        updateNetwork();
    }

    View root() {
        return root;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    void onStart() {
        handler.removeCallbacks(tick);
        tick.run();
        updateNetwork();
    }

    void onResume() {
        bindDock();   // an app installed or removed meanwhile changes the labels
        card.setRotating(true);
        if (root.findFocus() == null) focusDock(0);
    }

    void onPause() {
        card.setRotating(false);
        closeMenu();
    }

    void onStop() {
        handler.removeCallbacks(tick);
    }

    /** The remote's Home: back to the dock's first tile, whatever was open. */
    void goHome() {
        closeMenu();
        apps.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        dock[returnTile].setReturnPoint(false);
        focusDock(0);
    }

    /** Back: menu, then the apps screen, then the card; the dock itself has nowhere to go back to. */
    void onBack() {
        if (menu != null) closeMenu();
        else if (apps.getVisibility() == View.VISIBLE) showHome(DOCK_APPS);
        else if (card.hasFocus()) backToDock();
    }

    void setTrending(List<Trending.Item> items, long cachedAt) {
        card.setItems(items, cachedAt);
        if (refreshHint != null && refreshHint.getText().toString().startsWith("FETCHING")) refreshHint.setText("DONE");
        updateFooter(root.findFocus());
    }

    // ---- home ------------------------------------------------------------------------------------

    private boolean dockKey(int slot, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (slot > 0) focusDock(slot - 1);
                return true;   // no wrap at either end
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (slot < dock.length - 1) focusDock(slot + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (card.isFocusable() && card.hasItems()) {
                    returnTile = slot;
                    dock[slot].setReturnPoint(true);
                    card.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    private void onDockFocused(int slot) {
        for (TermTile t : dock) t.setReturnPoint(false);
        String pkg = dock[slot].key;
        boolean feed = Trending.YOUTUBE.equals(pkg) || Trending.NUVIO.equals(pkg) || Trending.APPLE_TV.equals(pkg);
        card.setFeed(feed ? pkg : null, feed ? TrendingView.appName(pkg) : "ALL");
    }

    private void backToDock() {
        focusDock(returnTile);
    }

    private void focusDock(int slot) {
        dock[slot].requestFocus();
    }

    private void showApps() {
        closeMenu();
        appsAdapter.setApps(installedApps());
        appsCount.setText(String.format(Locale.ROOT, "%d ENTRIES", appsAdapter.list.size()));
        updateNetworkHint();
        home.setVisibility(View.GONE);
        apps.setVisibility(View.VISIBLE);
        grid.scrollToPosition(0);
        grid.post(() -> {
            RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
            else systemRows.getChildAt(0).requestFocus();
        });
    }

    private void showHome(int slot) {
        closeMenu();
        apps.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        bindDock();
        focusDock(slot);
    }

    private void updateFooter(View focus) {
        if (focus == null) return;
        if (focus == card) {
            Trending.Item it = card.current();
            footer.setText("←/→ SLIDE · ↓ DOCK" + (it != null ? " · OK OPEN " + TrendingView.appName(it.pkg) : "") + " · BACK DOCK");
        } else if (focus instanceof TermTile && home.getVisibility() == View.VISIBLE) {
            TermTile t = (TermTile) focus;
            boolean all = ALL_APPS.equals(t.key);
            footer.setText("←/→ MOVE" + (card.isFocusable() && card.hasItems() ? " · ↑ TRENDING" : "")
                    + " · OK OPEN " + t.label() + (all ? "" : " · HOLD OK APP INFO"));
        } else if (focus instanceof TermTile) {
            appsFooter.setText("↑↓←→ MOVE · OK OPEN " + ((TermTile) focus).label() + " · HOLD OK PIN / APP INFO · BACK HOME");
        } else if (apps.getVisibility() == View.VISIBLE) {
            appsFooter.setText("↑↓ MOVE · OK SELECT · BACK HOME");
        }
    }

    // ---- dock ------------------------------------------------------------------------------------

    private List<String> loadDock() {
        String saved = prefs.getString("dock", "");
        List<String> out = new ArrayList<>();
        for (String p : saved.split(",")) if (LibraryApps.validPackage(p) && !out.contains(p)) out.add(p);
        for (String p : DEFAULT_DOCK) if (out.size() < DOCK_APPS && !out.contains(p)) out.add(p);
        return new ArrayList<>(out.subList(0, DOCK_APPS));
    }

    /** Pinned apps go in after the three feed apps; the dock's last app makes room. */
    private void pin(String pkg) {
        if (dockPkgs.contains(pkg)) return;
        int at = Math.min(3, dockPkgs.size());
        dockPkgs.add(at, pkg);
        dockPkgs = new ArrayList<>(dockPkgs.subList(0, DOCK_APPS));
        prefs.edit().putString("dock", String.join(",", dockPkgs)).apply();
        bindDock();
    }

    private void bindDock() {
        for (int i = 0; i < DOCK_APPS; i++) {
            String pkg = dockPkgs.get(i);
            String[] known = KNOWN.get(pkg);
            CharSequence label = known != null ? known[0] : appLabel(pkg);
            String mono = known != null ? known[1] : Term.monogram(label);
            boolean feed = Trending.YOUTUBE.equals(pkg) || Trending.NUVIO.equals(pkg) || Trending.APPLE_TV.equals(pkg);
            dock[i].bind(pkg, i, feed ? "feed" : null, mono, label);
        }
        dock[DOCK_APPS].bind(ALL_APPS, DOCK_APPS, null, "::", "All apps");
    }

    private CharSequence appLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg.substring(pkg.lastIndexOf('.') + 1);
        }
    }

    // ---- launching -------------------------------------------------------------------------------

    /** The app's TV entry (LEANBACK_LAUNCHER) or else its phone one, by explicit component like the classic tiles. */
    private Intent launchIntent(String pkg) {
        boolean leanback = true;
        ResolveInfo ri = resolve(pkg, Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (ri == null) {
            ri = resolve(pkg, Intent.CATEGORY_LAUNCHER);
            leanback = false;
        }
        if (ri == null) return null;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (leanback) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        return i;
    }

    private ResolveInfo resolve(String pkg, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);
        List<ResolveInfo> r = pm.queryIntentActivities(q, 0);
        return r.isEmpty() ? null : r.get(0);
    }

    private void launch(String pkg, CharSequence label) {
        Intent i = launchIntent(pkg);
        if (i == null) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label), Toast.LENGTH_SHORT).show();
            return;
        }
        LastOpened.record(activity, pkg);
        host.open(i);
    }

    private void appInfo(String pkg) {
        Intent i = SystemSettings.appInfo(activity, pkg);
        if (i == null) Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        else host.open(i);
    }

    /** The system input switcher (Google TV's own), never our InputsRequestActivity that may answer the same action. */
    void openInputs() {
        List<ResolveInfo> r = pm.queryIntentActivities(new Intent(MainActivity.ACTION_VIEW_INPUTS), 0);
        for (ResolveInfo ri : r) {
            ActivityInfo a = ri.activityInfo;
            if (a.packageName.equals(activity.getPackageName())) continue;
            host.open(new Intent(MainActivity.ACTION_VIEW_INPUTS).setComponent(new ComponentName(a.packageName, a.name)));
            return;
        }
        Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    /** MediaTek TV settings answer the OEM timer action (sleep timer among them); elsewhere plain Settings. */
    private void openSleepTimer() {
        Intent timer = new Intent("android.settings.oem.TIMER_SETTINGS");
        host.open(pm.resolveActivity(timer, 0) != null ? timer : SystemSettings.intent(activity, Settings.ACTION_SETTINGS));
    }

    private List<App> installedApps() {
        Map<String, App> byPkg = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(activity.getPackageName()) || byPkg.containsKey(pkg)) continue;
                String[] known = KNOWN.get(pkg);
                CharSequence label = known != null ? known[0] : ri.loadLabel(pm);
                byPkg.put(pkg, new App(pkg, label, known != null ? known[1] : Term.monogram(label)));
            }
        }
        List<App> out = new ArrayList<>(byPkg.values());
        Collator collator = Collator.getInstance();
        out.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return out;
    }

    // ---- header, clock, network ------------------------------------------------------------------

    private View header(String where) {
        LinearLayout h = row();
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView path = Term.label(activity, 14, Term.TEXT);
        path.setText("~ / " + where);
        h.addView(path);
        View cursor = new View(activity);
        cursor.setBackgroundColor(Term.TEXT);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(8), dp(16));
        cl.leftMargin = dp(6);
        h.addView(cursor, cl);
        h.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1f));

        View square = new View(activity);
        h.addView(square, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView status = Term.label(activity, 10, Term.MUTED);
        status.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(7);
        h.addView(status, sl);
        TextView date = Term.label(activity, 10, Term.MUTED);
        date.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = dp(24);
        h.addView(date, dl);
        TextView clock = Term.label(activity, 30, Term.TEXT);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.leftMargin = dp(18);
        h.addView(clock, kl);
        headers.add(new View[]{square, status, date, clock});
        return h;
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        String date = Term.upper(DateFormat.format("EEE d MMM", now));
        String time = DateFormat.format(DateFormat.is24HourFormat(activity) ? "HH:mm" : "h:mm", now).toString();
        for (View[] h : headers) {
            ((TextView) h[2]).setText(date);
            ((TextView) h[3]).setText(time);
        }
    }

    /** Called from MainActivity's network callback as well as on start. */
    void updateNetwork() {
        boolean online = online();
        for (View[] h : headers) {
            h[0].setBackgroundColor(online ? Term.AMBER : Term.MARK);
            ((TextView) h[1]).setText(online ? "ONLINE" : "OFFLINE");
        }
        updateNetworkHint();
    }

    private void updateNetworkHint() {
        if (networkHint != null) networkHint.setText(online() ? "ONLINE" : "OFFLINE");
    }

    private boolean online() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ---- hold menu (apps grid) -------------------------------------------------------------------

    private void showMenu(TermTile tile) {
        closeMenu();
        menuTile = tile;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(boxed());
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView head = Term.label(activity, 10, Term.MUTED);
        head.setText(tile.label());
        panel.addView(head);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(10);
        panel.addView(rows, rlp);
        String pkg = tile.key;
        if (!dockPkgs.contains(pkg)) {
            rows.addView(menuRow("PIN TO DOCK", "SLOT 04", () -> {
                pin(pkg);
                closeMenu();
            }));
        } else {
            rows.addView(menuRow("IN DOCK", String.format(Locale.ROOT, "SLOT %02d", dockPkgs.indexOf(pkg) + 1), this::closeMenu));
        }
        rows.addView(menuRow("APP INFO", "SETTINGS", () -> {
            closeMenu();
            appInfo(pkg);
        }));
        apps.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, lp);
        menu = panel;
        rows.getChildAt(0).requestFocus();
    }

    private void closeMenu() {
        if (menu == null) return;
        root.removeView(menu);
        menu = null;
        apps.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (menuTile != null && apps.getVisibility() == View.VISIBLE) menuTile.requestFocus();
        menuTile = null;
    }

    private android.graphics.drawable.Drawable boxed() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Term.CARD);
        g.setStroke(Math.max(1, Math.round(d)), Term.TEXT);
        return g;
    }

    /** A row of the SYSTEM list or the hold menu: name left, a muted hint right; bone fill while focused. */
    private View menuRow(String name, String hint, Runnable action) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(12), 0);
        r.setFocusable(true);
        r.setClickable(true);
        TextView n = Term.label(activity, 11, Term.TEXT);
        n.setLetterSpacing(0.08f);
        n.setText(name);
        r.addView(n, weight());
        TextView h = Term.label(activity, 9, Term.MUTED);
        h.setLetterSpacing(0.08f);
        h.setText(hint);
        h.setTag("hint");
        r.addView(h);
        android.graphics.drawable.GradientDrawable idle = new android.graphics.drawable.GradientDrawable();
        idle.setColor(0x00000000);
        idle.setStroke(Math.max(1, Math.round(d)), Term.RULE);
        r.setBackground(idle);
        r.setOnFocusChangeListener((v, focused) -> {
            if (focused) v.setBackgroundColor(Term.TEXT);
            else v.setBackground(idle);
            n.setTextColor(focused ? Term.BG : Term.TEXT);
            h.setTextColor(focused ? Term.BG : Term.MUTED);
        });
        r.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    // ---- apps grid -------------------------------------------------------------------------------

    private static final class App {
        final String pkg;
        final CharSequence label;
        final String mono;

        App(String pkg, CharSequence label, String mono) {
            this.pkg = pkg;
            this.label = label;
            this.mono = mono;
        }
    }

    private final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<App> list = new ArrayList<>();

        void setApps(List<App> apps) {
            list = apps;
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            TermTile tile = new TermTile(activity);
            tile.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(88) + 2 * Math.round(TermTile.OUT_DP * d)));
            tile.setListener(new TermTile.Listener() {
                @Override public void onOpen(TermTile t) {
                    launch(t.key, t.label());
                }

                @Override public void onHold(TermTile t) {
                    showMenu(t);
                }
            });
            return new RecyclerView.ViewHolder(tile) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            App a = list.get(position);
            ((TermTile) h.itemView).bind(a.pkg, position, null, a.mono, a.label);
        }

        @Override public int getItemCount() {
            return list.size();
        }
    }

    // ---- layout helpers --------------------------------------------------------------------------

    private LinearLayout screen() {
        LinearLayout s = new LinearLayout(activity);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(dp(48), dp(27), dp(48), dp(27));
        s.setClipChildren(false);
        s.setClipToPadding(false);
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }
}
