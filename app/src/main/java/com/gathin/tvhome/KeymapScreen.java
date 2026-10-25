package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "~ / keys": the terminal home's third screen, behind REMOTE KEYS in ls /apps. A line drawing of the remote
 * (RemoteView) with a cursor, the selected button's details, and rows that give it a tap or hold action, learn
 * its real keycode, reset it, fix every broken button at once and turn the key helper (InputKeyService) on.
 * Choices go to RemoteMap, which the helper reads at once. While the screen is in front a press of a real button
 * moves the cursor to it: through the helper (RemoteMap.finder), or through the window when the helper is off.
 */
final class KeymapScreen implements RemoteMap.Finder {

    private final Activity activity;
    private final TerminalHome home;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final LinearLayout screen;
    private final RemoteView remote;
    private final TextView count;
    private final TextView[] values = new TextView[6];
    private final TextView footer;
    private final View tapRow, holdRow, learnRow, resetRow, fixRow, helperRow;

    /** The button LEARN KEY waits on; null when not learning. */
    private RemoteModel.Button learning;
    /** Open the tap picker once the key is learned: TAP ACTION on a button with no keycode yet. */
    private boolean pickAfterLearn;
    /** The last key down this screen took, so its key up is taken too. */
    private int takenKey = -1;
    private View picker;
    private Set<String> broken = new HashSet<>();

    KeymapScreen(Activity activity, TerminalHome home, LinearLayout screen) {
        this.activity = activity;
        this.home = home;
        this.screen = screen;
        pm = activity.getPackageManager();
        prefs = RemoteMap.prefs(activity);

        LinearLayout titleRow = home.row();
        titleRow.setGravity(Gravity.BOTTOM);
        TextView title = new TextView(activity);
        title.setTypeface(Term.display(activity));
        title.setFontVariationSettings(Term.DISPLAY_AXES);
        title.setTextSize(30);
        title.setTextColor(Term.TEXT);
        title.setIncludeFontPadding(false);
        title.setText("keymap");
        titleRow.addView(title);
        count = Term.label(activity, 10, Term.MUTED);
        count.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = home.dp(16);
        clp.bottomMargin = home.dp(4);
        titleRow.addView(count, clp);
        LinearLayout.LayoutParams tlp = TerminalHome.matchWrap();
        tlp.topMargin = home.dp(12);
        screen.addView(titleRow, tlp);

        remote = new RemoteView(activity);
        remote.setListener(new RemoteView.Listener() {
            @Override public void onSelect(RemoteModel.Button b) {
                cancelLearning();
                refresh();
            }

            @Override public void onOk(RemoteModel.Button b) {
                tapAction();
            }
        });
        screen.addView(remote, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout panel = home.row();
        panel.setGravity(Gravity.TOP);
        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        String[] names = {"NAME", "KEY", "FIRMWARE", "TAP", "HOLD", "STATUS"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout line = home.row();
            TextView n = Term.label(activity, 10, Term.MUTED);
            n.setLetterSpacing(0.08f);
            n.setText(names[i]);
            line.addView(n, new LinearLayout.LayoutParams(home.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
            values[i] = Term.label(activity, 10, Term.TEXT);
            values[i].setLetterSpacing(0.08f);
            line.addView(values[i], TerminalHome.weight());
            LinearLayout.LayoutParams llp = TerminalHome.matchWrap();
            if (i > 0) llp.topMargin = home.dp(7);
            details.addView(line, llp);
        }
        int tileOut = home.dp(TermTile.OUT_DP);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.topMargin = tileOut;
        panel.addView(details, dlp);

        TerminalHome.RingLayout left = home.ring(), right = home.ring();
        tapRow = home.menuRow("TAP ACTION", "", this::tapAction);
        holdRow = home.menuRow("HOLD ACTION", "", () -> openPicker(false));
        learnRow = home.menuRow("LEARN KEY", "", () -> startLearning(false));
        resetRow = home.menuRow("RESET BUTTON", "", this::resetButton);
        fixRow = home.menuRow("FIX BROKEN KEYS", "", this::fixBroken);
        helperRow = home.menuRow("KEY HELPER", "", this::keyHelper);
        left.addView(tapRow);
        left.addView(holdRow);
        left.addView(learnRow);
        right.addView(resetRow);
        right.addView(fixRow);
        right.addView(helperRow);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(home.dp(250), ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(home.dp(12) - tileOut, 0, -tileOut, -tileOut);
        panel.addView(left, rlp);
        LinearLayout.LayoutParams rrp = new LinearLayout.LayoutParams(home.dp(250), ViewGroup.LayoutParams.WRAP_CONTENT);
        rrp.setMargins(home.dp(12) - 2 * tileOut, 0, -tileOut, -tileOut);
        panel.addView(right, rrp);
        LinearLayout.LayoutParams plp = TerminalHome.matchWrap();
        plp.topMargin = home.dp(4) - tileOut;
        screen.addView(panel, plp);

        footer = Term.label(activity, 10, Term.MUTED);
        footer.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams flp = TerminalHome.matchWrap();
        flp.topMargin = home.dp(8);
        screen.addView(footer, flp);
    }

    // ---- lifecycle, called by TerminalHome ---------------------------------------------------------

    /** The screen came up (from ls /apps) or the activity resumed with it up. */
    void onShown() {
        RemoteMap.finder = this;
        refresh();
        remote.requestFocus();
    }

    void onHidden() {
        if (RemoteMap.finder == this) RemoteMap.finder = null;
        cancelLearning();
        closePicker();
    }

    void finishAnimations() {
        remote.finishAnimation();
    }

    /** Back: the picker, then learning, then the panel; false when the screen itself should close. */
    boolean onBack() {
        if (picker != null) {
            closePicker();
            return true;
        }
        if (learning != null) {
            cancelLearning();
            refresh();
            return true;
        }
        if (!remote.hasFocus()) {
            remote.requestFocus();
            return true;
        }
        return false;
    }

    /** A key the window got while the helper is off; the helper's keys come through onRemoteKey directly. */
    boolean onWindowKey(KeyEvent event) {
        if (InputKeyService.connected) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
                return false;
            default:
                return onRemoteKey(event);
        }
    }

    // ---- press to find, LEARN KEY ----------------------------------------------------------------

    @Override public boolean onRemoteKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            boolean taken = event.getKeyCode() == takenKey;
            if (event.getAction() == KeyEvent.ACTION_UP && taken) takenKey = -1;
            return taken;
        }
        if (event.getRepeatCount() > 0) return event.getKeyCode() == takenKey;
        if (picker != null) return false;
        if (learning != null) {
            RemoteModel.Button b = learning;
            learning = null;
            prefs.edit().putInt(RemoteMap.codeKey(b.id), Math.max(0, event.getKeyCode()))
                    .putInt(RemoteMap.scanKey(b.id), Math.max(0, event.getScanCode())).apply();
            takenKey = event.getKeyCode();
            remote.find(b);
            refresh();
            if (pickAfterLearn) {
                pickAfterLearn = false;
                openPicker(true);
            }
            return true;
        }
        RemoteModel.Button b = RemoteMap.find(prefs.getAll(), event.getKeyCode(), event.getScanCode());
        if (b == null) {
            footer.setText(String.format(Locale.ROOT, "UNKNOWN KEY %s %d · SCAN %d · SELECT ITS BUTTON, THEN LEARN KEY",
                    KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", ""), event.getKeyCode(), event.getScanCode()));
            return false;
        }
        remote.find(b);
        // Volume and Home still do their job; everything else is only found, never run, while this screen is up.
        if (b.locked) return false;
        takenKey = event.getKeyCode();
        return true;
    }

    private void startLearning(boolean thenPick) {
        RemoteModel.Button b = remote.selected();
        if (b.locked) {
            footer.setText(b.label + " IS A SYSTEM KEY · IT CANNOT BE REMAPPED");
            return;
        }
        learning = b;
        pickAfterLearn = thenPick;
        refresh();
    }

    private void cancelLearning() {
        learning = null;
        pickAfterLearn = false;
    }

    // ---- actions -----------------------------------------------------------------------------------

    private void tapAction() {
        RemoteModel.Button b = remote.selected();
        if (b.locked) {
            footer.setText(b.label + " IS A SYSTEM KEY · IT CANNOT BE REMAPPED");
            return;
        }
        if (RemoteMap.keycode(prefs.getAll(), b) <= 0 && RemoteMap.learned(prefs.getAll(), RemoteMap.scanKey(b.id)) <= 0) {
            startLearning(true);
            return;
        }
        openPicker(true);
    }

    private void resetButton() {
        RemoteModel.Button b = remote.selected();
        prefs.edit().remove(RemoteMap.tapKey(b.id)).remove(RemoteMap.holdKey(b.id))
                .remove(RemoteMap.codeKey(b.id)).remove(RemoteMap.scanKey(b.id)).apply();
        refresh();
    }

    private void fixBroken() {
        Map<String, String> fixes = RemoteMap.suggestions(prefs.getAll(), broken);
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, String> f : fixes.entrySet()) e.putString(RemoteMap.tapKey(f.getKey()), f.getValue());
        e.apply();
        refresh();
        footer.setText(fixes.isEmpty() ? "NOTHING TO FIX" : String.format(Locale.ROOT, "%02d KEYS FIXED", fixes.size()));
    }

    private void keyHelper() {
        if (helperOn()) {
            home.open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            home.open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        try {
            ContentResolver cr = activity.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String me = helper().flattenToShortString();
            String updated = list == null || list.isEmpty() ? me : list + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException refused) {
            home.open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        // Android binds the helper a moment later.
        remote.postDelayed(this::refresh, 600);
        refresh();
    }

    private ComponentName helper() {
        return new ComponentName(activity, InputKeyService.class);
    }

    private boolean helperOn() {
        String list = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = helper();
        for (String entry : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    // ---- picker ------------------------------------------------------------------------------------

    private void openPicker(boolean tap) {
        RemoteModel.Button b = remote.selected();
        if (b.locked) {
            footer.setText(b.label + " IS A SYSTEM KEY · IT CANNOT BE REMAPPED");
            return;
        }
        closePicker();
        Map<String, ?> saved = prefs.getAll();
        String current = RemoteMap.target(saved, tap ? RemoteMap.tapKey(b.id) : RemoteMap.holdKey(b.id));
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(home.boxed());
        int ringRoom = home.dp(TermTile.OUT_DP);
        // The rows' focus ring needs room inside the scroll view, which clips it: the panel's side padding goes to it.
        panel.setPadding(home.dp(16) - ringRoom, home.dp(14), home.dp(16) - ringRoom, home.dp(16) - ringRoom);
        TextView head = Term.label(activity, 10, Term.MUTED);
        head.setLetterSpacing(0.08f);
        head.setText(b.label + (tap ? " · TAP" : " · HOLD"));
        LinearLayout.LayoutParams hlp = TerminalHome.matchWrap();
        hlp.leftMargin = ringRoom;
        panel.addView(head, hlp);
        TerminalHome.RingLayout rows = home.ring();
        View focus = null;
        for (Map.Entry<String, String> t : targets().entrySet()) {
            String target = t.getKey();
            boolean chosen = target.equals(current);
            View row = home.menuRow(t.getValue(), chosen ? "SET" : hintFor(target), () -> {
                String pref = tap ? RemoteMap.tapKey(b.id) : RemoteMap.holdKey(b.id);
                if (RemoteMap.DEFAULT.equals(target)) prefs.edit().remove(pref).apply();
                else prefs.edit().putString(pref, target).apply();
                closePicker();
                refresh();
            });
            rows.addView(row);
            if (chosen) focus = row;
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.addView(rows);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, home.dp(330));
        rlp.topMargin = home.dp(10) - ringRoom;
        panel.addView(scroll, rlp);
        screen.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(home.dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        home.overlay(panel, lp);
        picker = panel;
        footer.setText("↑↓ MOVE · OK SET · BACK CANCEL");
        (focus != null ? focus : rows.getChildAt(0)).requestFocus();
    }

    private void closePicker() {
        if (picker == null) return;
        home.removeOverlay(picker);
        picker = null;
        screen.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        remote.requestFocus();
        updateFooter();
    }

    /** Every target, in the picker's order: the firmware, nothing, the action words, then each app. */
    private Map<String, String> targets() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put(RemoteMap.DEFAULT, "DEFAULT");
        t.put("none", "NOTHING");
        t.put("home", "HOME");
        t.put("back", "BACK");
        t.put("apps", "APPS");
        t.put(RemoteKeys.INPUTS, "INPUTS");
        t.put("settings", "SETTINGS");
        t.put("wifi", "WI-FI");
        t.put("mute", "MUTE");
        t.put("sleep", "SLEEP");
        t.put("screensaver", "SCREENSAVER");
        t.put("recents", "RECENTS");
        Map<String, String> apps = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(activity.getPackageName()) || apps.containsKey(pkg)) continue;
                apps.put(pkg, Term.upper(ri.loadLabel(pm)));
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        Collator collator = Collator.getInstance();
        sorted.sort((a, b) -> collator.compare(a.getValue(), b.getValue()));
        for (Map.Entry<String, String> e : sorted) t.put(e.getKey(), e.getValue());
        return t;
    }

    private String hintFor(String target) {
        if (RemoteMap.DEFAULT.equals(target)) return "FIRMWARE";
        if (target.contains(".")) return "APP";
        return "ACTION";
    }

    /** A target as the panel and the pills show it: an action word in capitals, an app by its name. */
    private String name(String target) {
        switch (target) {
            case RemoteMap.DEFAULT: return "DEFAULT";
            case "none": return "NOTHING";
            case "wifi": return "WI-FI";
            default:
                if (!target.contains(".")) return Term.upper(target);
                try {
                    return Term.upper(pm.getApplicationLabel(pm.getApplicationInfo(target, 0)));
                } catch (PackageManager.NameNotFoundException e) {
                    return Term.upper(target);
                }
        }
    }

    // ---- state -------------------------------------------------------------------------------------

    /** A package that is installed and enabled. */
    private boolean usable(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isBroken(Map<String, ?> saved, RemoteModel.Button b, boolean helper) {
        if (b.locked || !RemoteMap.DEFAULT.equals(RemoteMap.target(saved, RemoteMap.tapKey(b.id)))) return false;
        RemoteModel.Firmware fw = RemoteMap.firmware(saved, b);
        if (fw == null || fw.pkg == null || usable(fw.pkg)) return false;
        int code = RemoteMap.keycode(saved, b);
        // The helper itself sends TV and Input to the input switcher.
        return !(helper && (code == KeyEvent.KEYCODE_TV || code == KeyEvent.KEYCODE_TV_INPUT));
    }

    void refresh() {
        Map<String, ?> saved = prefs.getAll();
        boolean helper = helperOn();
        broken = new HashSet<>();
        Set<String> remapped = new HashSet<>();
        Map<String, String> pills = new HashMap<>();
        int keys = 0;
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            keys++;
            if (isBroken(saved, b, helper)) broken.add(b.id);
            String tap = RemoteMap.target(saved, RemoteMap.tapKey(b.id));
            String hold = RemoteMap.target(saved, RemoteMap.holdKey(b.id));
            if (!RemoteMap.DEFAULT.equals(tap) || !RemoteMap.DEFAULT.equals(hold)) remapped.add(b.id);
            if (b.isAppKey() && !RemoteMap.DEFAULT.equals(tap)) pills.put(b.id, name(tap));
        }
        count.setText(String.format(Locale.ROOT, "GOOGLE TV REMOTE · %d KEYS · %02d BROKEN", keys, broken.size()));

        RemoteModel.Button b = remote.selected();
        int code = RemoteMap.keycode(saved, b);
        int scan = RemoteMap.learned(saved, RemoteMap.scanKey(b.id));
        String key = code > 0 ? KeyEvent.keyCodeToString(code) + " " + code : "?";
        if (scan > 0) key += " · SCAN " + scan;
        if (RemoteMap.learned(saved, RemoteMap.codeKey(b.id)) > 0) key += " · LEARNED";
        else if (code > 0) key += " · GUESS";
        RemoteModel.Firmware fw = RemoteMap.firmware(saved, b);
        String firmware = fw == null ? (code > 0 ? "FOCUSED APP" : "UNKNOWN · LEARN KEY")
                : fw.label + (fw.pkg != null && !usable(fw.pkg) ? " · DISABLED" : "");
        String tap = RemoteMap.target(saved, RemoteMap.tapKey(b.id));
        String hold = RemoteMap.target(saved, RemoteMap.holdKey(b.id));
        String status = b.locked ? "SYSTEM KEY · LOCKED" : remapped.contains(b.id) ? "REMAPPED"
                : broken.contains(b.id) ? "BROKEN" : code <= 0 ? "NOT LEARNED" : "WORKS";
        values[0].setText(b.isAppKey() ? b.label + " · BLANK KEY" : b.label);
        values[1].setText(learning == b ? "PRESS THE BUTTON ON THE REMOTE…" : key);
        values[2].setText(firmware);
        values[3].setText(b.locked ? "—" : "→ " + name(tap));
        values[4].setText(b.locked ? "—" : "→ " + name(hold));
        values[5].setText(status);
        values[5].setTextColor(broken.contains(b.id) ? Term.AMBER : Term.TEXT);
        values[1].setTextColor(learning == b ? Term.AMBER : Term.TEXT);

        hint(tapRow, b.locked ? "LOCKED" : name(tap));
        hint(holdRow, b.locked ? "LOCKED" : name(hold));
        hint(learnRow, learning != null ? "WAITING" : code > 0 ? String.valueOf(code) : "?");
        hint(resetRow, remapped.contains(b.id) || RemoteMap.learned(saved, RemoteMap.codeKey(b.id)) > 0 ? "CLEAR" : "");
        int fixes = RemoteMap.suggestions(saved, broken).size();
        hint(fixRow, fixes == 0 ? "NONE" : String.format(Locale.ROOT, "%02d", fixes));
        hint(helperRow, helper ? "ON" : "OFF");

        String callout = b.label + " · " + (code > 0 ? KeyEvent.keyCodeToString(code) + " " + code : "KEY ?");
        remote.setStates(broken, remapped, pills, callout);
        updateFooter();
    }

    private static void hint(View row, String text) {
        ((TextView) row.findViewWithTag("hint")).setText(text);
    }

    private void updateFooter() {
        if (learning != null) footer.setText("PRESS " + learning.label + " ON THE REMOTE · BACK CANCEL");
        else if (picker == null) footer.setText("↑↓←→ PICK KEY · OK SET TAP ACTION · PRESS A REAL BUTTON TO FIND IT · BACK APPS");
    }
}
