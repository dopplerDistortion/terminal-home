package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Grundig 65 GJQ 9200 Google TV remote as data, for the keymap screen: every button with its place, shape,
 * the keycodes it is thought to send and what the firmware did with them. Traced from the TV's manual (FE6T00,
 * page 20, section 3.7) and the TV's own key routing (`dumpsys window policy`, /vendor/etc/hot-key-info.ini).
 * The remote's own key layout file is not readable over adb, so the keycodes are the Google reference remote's
 * (Vendor_0957_Product_0031.kl on the same TV) until LEARN KEY records the real one.
 *
 * Geometry is in remote widths, standing: x 0..1 across, y 0..{@link #LENGTH} from the top. RemoteView draws it
 * lying down, top to the left, through {@link #u} and {@link #v}. Plain Java, so the tests can walk it.
 */
final class RemoteModel {

    /** Length over width of the remote's outline. */
    static final float LENGTH = 4.5f;

    enum Shape { ROUND, ICON, PILL, HALF_UP, HALF_DOWN, RING_UP, RING_DOWN, RING_LEFT, RING_RIGHT }

    enum Icon {
        POWER, REWIND, PLAY_PAUSE, FAST_FORWARD, RECORD, STOP, GEAR, MIC, INPUT, BACK, HOME, TV, SPEAKER, BOOK, LEAF,
        TEXT, DOT, NONE
    }

    /** Where a key went before this launcher: a label for the details panel and the package that answered it. */
    static final class Firmware {
        final String label;
        /** The package that handled the key; null for keys the focused app or the system gets. */
        final String pkg;

        Firmware(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    static final class Button {
        final String id;
        final String label;
        final Shape shape;
        final Icon icon;
        /** Text for Icon.TEXT ("TXT", "1"), else empty. */
        final String text;
        /** Centre, in remote widths, standing. */
        final float cx, cy;
        /** Size across (x) and along (y) the remote, in remote widths. */
        final float w, h;
        /** Keycodes the button is thought to send, best guess first; empty when unknown (ECO, the app keys). */
        final int[] keycodes;
        /** Navigation and system keys: shown, found by a press, never remapped. */
        final boolean locked;
        /** What the firmware did with this button whatever its keycode (ECO); null to look it up by keycode. */
        final Firmware firmware;

        Button(String id, String label, Shape shape, Icon icon, String text, float cx, float cy, float w, float h,
               int[] keycodes, boolean locked, Firmware firmware) {
            this.id = id;
            this.label = label;
            this.shape = shape;
            this.icon = icon;
            this.text = text;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.keycodes = keycodes;
            this.locked = locked;
            this.firmware = firmware;
        }

        boolean isAppKey() {
            return id.startsWith("app");
        }

        /** The box the tests check for overlap and containment, in remote widths. */
        float left() { return cx - w / 2; }
        float right() { return cx + w / 2; }
        float top() { return cy - h / 2; }
        float bottom() { return cy + h / 2; }
    }

    /** A hairline of the drawing that is not a button: from (x1, y1) to (x2, y2), standing, in remote widths. */
    static final class Line {
        final float x1, y1, x2, y2;

        Line(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    /** A printed mark beside a rocker ("+", "^"): text at a standing point. */
    static final class Mark {
        final String text;
        final float x, y;

        Mark(String text, float x, float y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    // Keycodes (android.view.KeyEvent), spelled out so the model stays plain Java.
    static final int POWER = 26, BACK = 4, HOME = 3, DPAD_UP = 19, DPAD_DOWN = 20, DPAD_LEFT = 21, DPAD_RIGHT = 22,
            DPAD_CENTER = 23, VOLUME_UP = 24, VOLUME_DOWN = 25, VOLUME_MUTE = 164, CHANNEL_UP = 166, CHANNEL_DOWN = 167,
            MEDIA_PLAY_PAUSE = 85, MEDIA_PLAY = 126, MEDIA_PAUSE = 127, MEDIA_STOP = 86, MEDIA_REWIND = 89,
            MEDIA_FAST_FORWARD = 90, MEDIA_RECORD = 130, MEDIA_SKIP_FORWARD = 272, MEDIA_SKIP_BACKWARD = 273,
            SETTINGS = 176, ASSIST = 219, SEARCH = 84, VOICE_ASSIST = 231, TV_INPUT = 178, TV = 170, GUIDE = 172,
            INFO = 165, CAPTIONS = 175, TV_TELETEXT = 233, MEDIA_AUDIO_TRACK = 222, PROG_RED = 183, PROG_GREEN = 184,
            PROG_YELLOW = 185, PROG_BLUE = 186, BUTTON_3 = 190, BUTTON_4 = 191, TV_INPUT_COMPOSITE_1 = 247,
            TV_INPUT_COMPOSITE_2 = 248, TV_INPUT_COMPONENT_1 = 249, TV_INPUT_COMPONENT_2 = 250, FEATURED_APP_1 = 297,
            ALL_APPS = 284, NOTIFICATION = 83, PROFILE_SWITCH = 288, STEM_1 = 265, KEY_0 = 7;

    static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    static final String TV_AGENT = "com.mediatek.tv.agent";

    private static final List<Button> BUTTONS = new ArrayList<>();
    private static final List<Line> LINES = new ArrayList<>();
    private static final List<Mark> MARKS = new ArrayList<>();

    private static void add(String id, String label, Shape shape, Icon icon, String text, float cx, float cy,
                            float w, float h, boolean locked, Firmware fw, int... codes) {
        BUTTONS.add(new Button(id, label, shape, icon, text, cx, cy, w, h, codes, locked, fw));
    }

    private static void round(String id, String label, Icon icon, String text, float cx, float yFraction, float d,
                              boolean locked, int... codes) {
        add(id, label, Shape.ROUND, icon, text, cx, yFraction * LENGTH, d, d, locked, null, codes);
    }

    static {
        float L = LENGTH;
        add("power", "POWER", Shape.ICON, Icon.POWER, "", .34f, .031f * L, .13f, .13f, true, null, POWER);

        round("rewind", "REWIND", Icon.REWIND, "", .20f, .082f, .16f, false, MEDIA_REWIND, MEDIA_SKIP_BACKWARD);
        round("play", "PLAY / PAUSE", Icon.PLAY_PAUSE, "", .50f, .082f, .16f, false, MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE);
        round("forward", "FAST FORWARD", Icon.FAST_FORWARD, "", .80f, .082f, .16f, false, MEDIA_FAST_FORWARD, MEDIA_SKIP_FORWARD);
        round("record", "RECORD", Icon.RECORD, "", .34f, .123f, .16f, false, MEDIA_RECORD);
        round("stop", "STOP", Icon.STOP, "", .65f, .123f, .16f, false, MEDIA_STOP);

        round("settings", "SETTINGS", Icon.GEAR, "", .22f, .171f, .19f, false, SETTINGS);
        round("mic", "MIC", Icon.MIC, "", .50f, .171f, .19f, false, ASSIST, SEARCH, VOICE_ASSIST);
        round("input", "INPUT", Icon.INPUT, "", .78f, .171f, .19f, false, TV_INPUT);
        round("back", "BACK", Icon.BACK, "", .22f, .221f, .19f, true, BACK);
        round("home", "HOME", Icon.HOME, "", .50f, .221f, .19f, true, HOME);
        round("tv", "TV", Icon.TV, "", .78f, .221f, .19f, false, TV);

        // The D-pad ring (outer .84, inner .43) as four arcs around OK; each arc's box stops at the inner circle's
        // width, so no two boxes overlap.
        float dc = .345f * L, ro = .42f, ri = .215f;
        add("up", "UP", Shape.RING_UP, Icon.NONE, "", .5f, dc - (ro + ri) / 2, 2 * ri, ro - ri, true, null, DPAD_UP);
        add("down", "DOWN", Shape.RING_DOWN, Icon.NONE, "", .5f, dc + (ro + ri) / 2, 2 * ri, ro - ri, true, null, DPAD_DOWN);
        add("left", "LEFT", Shape.RING_LEFT, Icon.NONE, "", .5f - (ro + ri) / 2, dc, ro - ri, 2 * ri, true, null, DPAD_LEFT);
        add("right", "RIGHT", Shape.RING_RIGHT, Icon.NONE, "", .5f + (ro + ri) / 2, dc, ro - ri, 2 * ri, true, null, DPAD_RIGHT);
        round("ok", "OK", Icon.NONE, "", .5f, .345f, .43f, true, DPAD_CENTER);

        // The volume and channel band: two rockers lying across the remote, each pressed at its top or bottom edge.
        float band = .476f * L, pill = .12f;
        add("mute", "MUTE", Shape.ICON, Icon.SPEAKER, "", .07f, band, .09f, .09f, false, null, VOLUME_MUTE);
        add("volup", "VOLUME +", Shape.HALF_UP, Icon.NONE, "", .31f, band - pill / 4, .35f, pill / 2, true, null, VOLUME_UP);
        add("voldown", "VOLUME −", Shape.HALF_DOWN, Icon.NONE, "", .31f, band + pill / 4, .35f, pill / 2, true, null, VOLUME_DOWN);
        add("chup", "CHANNEL +", Shape.HALF_UP, Icon.NONE, "", .685f, band - pill / 4, .33f, pill / 2, false, null, CHANNEL_UP);
        add("chdown", "CHANNEL −", Shape.HALF_DOWN, Icon.NONE, "", .685f, band + pill / 4, .33f, pill / 2, false, null, CHANNEL_DOWN);
        add("guide", "GUIDE", Shape.ICON, Icon.BOOK, "", .93f, band, .09f, .09f, false, null, GUIDE);
        MARKS.add(new Mark("+", .31f, band - pill / 2 - .07f));
        MARKS.add(new Mark("−", .31f, band + pill / 2 + .07f));
        MARKS.add(new Mark("^", .685f, band - pill / 2 - .07f));
        MARKS.add(new Mark("v", .685f, band + pill / 2 + .07f));
        LINES.add(new Line(0, .445f * L, 1, .445f * L));
        LINES.add(new Line(0, .506f * L, 1, .506f * L));

        // The media keys sit in an upside-down trapezoid.
        float mt = .27f, mb = .655f;
        LINES.add(new Line(.07f, mt, .93f, mt));
        LINES.add(new Line(.21f, mb, .79f, mb));
        LINES.add(new Line(.07f, mt, .21f, mb));
        LINES.add(new Line(.93f, mt, .79f, mb));

        // Six app keys, blank in the manual: the user labels them by giving them a tap action.
        float[] rows = {.535f, .579f, .620f};
        int n = 1;
        for (float y : rows) {
            for (float x : new float[]{.30f, .71f}) {
                add("app" + n, "APP " + n, Shape.PILL, Icon.NONE, "", x, y * L, .35f, .15f, false, null);
                n++;
            }
        }
        float boxTop = .511f * L, boxBottom = .642f * L;
        LINES.add(new Line(.08f, boxTop, .93f, boxTop));
        LINES.add(new Line(.08f, boxBottom, .93f, boxBottom));
        LINES.add(new Line(.08f, boxTop, .08f, boxBottom));
        LINES.add(new Line(.93f, boxTop, .93f, boxBottom));

        round("audio", "AUDIO", Icon.TEXT, "AUD", .20f, .662f, .15f, false, MEDIA_AUDIO_TRACK);
        add("eco", "ECO", Shape.ROUND, Icon.LEAF, "", .50f, .662f * L, .15f, .15f, false,
                new Firmware("ARÇELIK ECO MODE", "com.arcelik.ecomode"));
        round("info", "INFO", Icon.TEXT, "i", .80f, .662f, .15f, false, INFO);

        String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
        float[] digitRows = {.707f, .751f, .796f};
        for (int i = 0; i < 9; i++) {
            float x = new float[]{.20f, .50f, .80f}[i % 3];
            round("d" + digits[i], digits[i], Icon.TEXT, digits[i], x, digitRows[i / 3], .15f, false, KEY_0 + i + 1);
        }
        round("txt", "TELETEXT", Icon.TEXT, "TXT", .20f, .843f, .15f, false, TV_TELETEXT);
        round("d0", "0", Icon.TEXT, "0", .50f, .843f, .15f, false, KEY_0);
        round("sub", "SUBTITLES", Icon.TEXT, "SUB", .80f, .843f, .15f, false, CAPTIONS);

        String[] colours = {"RED", "GREEN", "YELLOW", "BLUE"};
        int[] colourCodes = {PROG_RED, PROG_GREEN, PROG_YELLOW, PROG_BLUE};
        for (int i = 0; i < 4; i++) {
            round(colours[i].toLowerCase(java.util.Locale.ROOT), colours[i], Icon.DOT, "", .20f + .20f * i, .887f, .15f,
                    false, colourCodes[i]);
        }
    }

    private RemoteModel() {
    }

    static List<Button> buttons() {
        return Collections.unmodifiableList(BUTTONS);
    }

    static List<Line> lines() {
        return Collections.unmodifiableList(LINES);
    }

    static List<Mark> marks() {
        return Collections.unmodifiableList(MARKS);
    }

    static Button byId(String id) {
        for (Button b : BUTTONS) if (b.id.equals(id)) return b;
        return null;
    }

    /** The first button whose keycodes include {@code keyCode}; null when none does. */
    static Button byKeycode(int keyCode) {
        for (Button b : BUTTONS) for (int k : b.keycodes) if (k == keyCode) return b;
        return null;
    }

    /** Lying down, top to the left: across the screen, in remote widths. */
    static float u(float x, float y) {
        return y;
    }

    /** Lying down: down the screen, in remote widths. The remote's right side faces up. */
    static float v(float x, float y) {
        return 1 - x;
    }

    /** Directions of the D-pad, on screen. */
    enum Dir { UP, DOWN, LEFT, RIGHT }

    /**
     * The button the cursor moves to from {@code from} in {@code dir}, on the lying-down drawing: among the
     * buttons whose centre lies that way, the one nearest by distance along the direction plus twice the
     * sideways offset, so a step keeps to its row or column. Null at an edge, where focus leaves for the panel.
     */
    static Button next(Button from, Dir dir) {
        float fu = u(from.cx, from.cy), fv = v(from.cx, from.cy);
        Button best = null;
        float bestScore = Float.MAX_VALUE;
        for (Button b : BUTTONS) {
            if (b == from) continue;
            float du = u(b.cx, b.cy) - fu, dv = v(b.cx, b.cy) - fv;
            float along, side;
            switch (dir) {
                case LEFT: along = -du; side = Math.abs(dv); break;
                case RIGHT: along = du; side = Math.abs(dv); break;
                case UP: along = -dv; side = Math.abs(du); break;
                default: along = dv; side = Math.abs(du); break;
            }
            // Within a cone of the direction, so a step never jumps across the remote to a far corner.
            if (along <= 0.01f || side > 2.5f * along) continue;
            float score = along + 2 * side;
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    /**
     * What the firmware did with a keycode on this TV, from `dumpsys window policy` and hot-key-info.ini; null for
     * keys that simply go to the focused app.
     */
    static Firmware firmware(int keyCode) {
        switch (keyCode) {
            case ASSIST: case SEARCH: case VOICE_ASSIST:
                return new Firmware("GOOGLE ASSISTANT", "com.google.android.katniss");
            case GUIDE: case NOTIFICATION: case ALL_APPS: case PROFILE_SWITCH:
                return new Firmware("GOOGLE TV HOME", LAUNCHERX);
            case FEATURED_APP_1:
                return new Firmware("FREE TV · GOOGLE TV HOME", LAUNCHERX);
            case TV_INPUT:
                // The TV agent opens Google TV's input picker through the stock launcher.
                return new Firmware("INPUT PICKER · GOOGLE TV HOME", LAUNCHERX);
            case TV:
                return new Firmware("LIVE TV", "com.cltv.hybrid");
            case SETTINGS:
                return new Firmware("TV SETTINGS", "com.android.tv.settings");
            case BUTTON_4: case STEM_1:
                return new Firmware("NETFLIX", "com.netflix.ninja");
            case BUTTON_3: case TV_INPUT_COMPOSITE_1:
                return new Firmware("YOUTUBE", "com.google.android.youtube.tv");
            case TV_INPUT_COMPONENT_1:
                return new Firmware("PRIME VIDEO", "com.amazon.amazonvideo.livingroom");
            case TV_INPUT_COMPOSITE_2:
                return new Firmware("GOOGLE TV APP", "com.google.android.videos");
            case TV_INPUT_COMPONENT_2:
                return new Firmware("VUDU", "air.com.vudu.air.DownloaderTablet");
            default:
                return null;
        }
    }
}
