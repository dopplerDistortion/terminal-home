package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keymap screen's choices: per button of {@link RemoteModel}, a tap target, a hold target and the keycode and
 * scancode LEARN KEY recorded. Kept in SharedPreferences ("remote_map") and turned into entries in the app_keys
 * format (RemoteKeys), which InputKeyService adds after config.xml's app_keys, so a choice here wins.
 *
 * Targets are RemoteKeys targets (a package, "inputs", an action word); "default" is no entry, so the key goes
 * where the firmware sends it.
 */
final class RemoteMap {

    static final String PREFS = "remote_map";
    static final String DEFAULT = "default";

    /**
     * The keymap screen while it is in front: InputKeyService hands it every key before its own maps, and
     * consumes the key when this returns true. Null otherwise.
     */
    interface Finder {
        boolean onRemoteKey(KeyEvent event);
    }

    static Finder finder;

    private RemoteMap() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String tapKey(String id) { return "tap." + id; }
    static String holdKey(String id) { return "hold." + id; }
    static String codeKey(String id) { return "code." + id; }
    static String scanKey(String id) { return "scan." + id; }

    /** The tap or hold target saved for a button, or DEFAULT. */
    static String target(Map<String, ?> saved, String key) {
        Object t = saved.get(key);
        return t instanceof String && !((String) t).isEmpty() ? (String) t : DEFAULT;
    }

    static int learned(Map<String, ?> saved, String key) {
        Object n = saved.get(key);
        return n instanceof Integer ? (Integer) n : 0;
    }

    /** The keycodes a button answers to: the learned one, else the model's guesses. */
    static int[] keycodes(Map<String, ?> saved, RemoteModel.Button b) {
        int code = learned(saved, codeKey(b.id));
        return code > 0 ? new int[]{code} : b.keycodes;
    }

    /** The keycode shown for a button: the learned one, else the model's first guess; 0 when unknown. */
    static int keycode(Map<String, ?> saved, RemoteModel.Button b) {
        int[] codes = keycodes(saved, b);
        return codes.length > 0 ? codes[0] : 0;
    }

    /**
     * app_keys entries for every button with a target. A learned scancode with no usable keycode becomes a
     * "scan:" entry; otherwise one entry per keycode the button answers to, since the model's guesses are
     * alternatives the same button may send. Locked buttons never get one.
     */
    static List<String> entries(Map<String, ?> saved) {
        List<String> out = new ArrayList<>();
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            if (b.locked) continue;
            String tap = target(saved, tapKey(b.id)), hold = target(saved, holdKey(b.id));
            if (DEFAULT.equals(tap) && DEFAULT.equals(hold)) continue;
            List<String> keys = new ArrayList<>();
            int scan = learned(saved, scanKey(b.id));
            if (learned(saved, codeKey(b.id)) <= 0 && scan > 0) keys.add("scan:" + scan);
            else for (int k : keycodes(saved, b)) keys.add(String.valueOf(k));
            for (String k : keys) {
                if (!DEFAULT.equals(tap)) out.add(k + ":" + tap);
                if (!DEFAULT.equals(hold)) out.add("hold:" + k + ":" + hold);
            }
        }
        return out;
    }

    /** config.xml's app_keys first, then the keymap's, so the keymap's win (the last entry for a key wins). */
    static List<String> merged(List<String> config, Map<String, ?> saved) {
        List<String> all = new ArrayList<>(config);
        all.addAll(entries(saved));
        return all;
    }

    /**
     * The button a key press belongs to: a learned scancode first, then a learned keycode, then the model's
     * guesses. Null when no button sends it.
     */
    static RemoteModel.Button find(Map<String, ?> saved, int keyCode, int scanCode) {
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            int scan = learned(saved, scanKey(b.id));
            if (scan > 0 && scan == scanCode && learned(saved, codeKey(b.id)) <= 0) return b;
        }
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            if (learned(saved, codeKey(b.id)) == keyCode && keyCode > 0) return b;
        }
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            if (learned(saved, codeKey(b.id)) > 0) continue;
            for (int k : b.keycodes) if (k == keyCode) return b;
        }
        return null;
    }

    /** What the firmware did with a button: its own entry (ECO), else by its keycode; null for the focused app. */
    static RemoteModel.Firmware firmware(Map<String, ?> saved, RemoteModel.Button b) {
        if (b.firmware != null) return b.firmware;
        int code = keycode(saved, b);
        return code > 0 ? RemoteModel.firmware(code) : null;
    }

    /**
     * FIX BROKEN KEYS: a tap target for each broken button that has none, by button id. The app keys are the
     * user's to fill and get nothing; ECO only once its keycode is learned, since its guess is empty.
     */
    static Map<String, String> suggestions(Map<String, ?> saved, java.util.Set<String> broken) {
        Map<String, String> out = new LinkedHashMap<>();
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            if (!broken.contains(b.id) || b.isAppKey() || b.locked) continue;
            if (!DEFAULT.equals(target(saved, tapKey(b.id)))) continue;
            String fix;
            switch (b.id) {
                case "input": case "tv": fix = RemoteKeys.INPUTS; break;
                case "guide": fix = "home"; break;
                case "mic": fix = "none"; break;
                case "eco": fix = keycode(saved, b) > 0 ? "screensaver" : null; break;
                default: fix = null; break;
            }
            if (fix != null) out.put(b.id, fix);
        }
        return out;
    }
}
