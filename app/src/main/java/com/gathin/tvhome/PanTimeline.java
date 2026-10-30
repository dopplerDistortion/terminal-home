package com.gathin.tvhome;

/**
 * The trending screensaver's clock (TrendingDream, PanScene), kept free of Android so it can be tested. Time
 * moves in ticks of {@link #TICK_MS}; every effect is a whole number of ticks, in the house's stepped motion.
 *
 * Start: "~ / idle" and the clock type in on black, then the first picture builds in 40 dp blocks. Each slide
 * holds {@link #HOLD} ticks (45 s) while the picture pans {@link #PAN_DP} dp, 1 dp a step; the panel's meta and
 * overview type in, then the panel folds to its title, one part every {@link #FOLD_STEP} ticks. The next picture
 * dissolves in over {@link #CUT} ticks in the same block order. With motion off: no type-in, pan, build or
 * dissolve; slides cut and the panel shows its final state.
 */
final class PanTimeline {
    static final long TICK_MS = 100;
    /** Ticks: path and clock type in (one character a tick), then the first picture builds. */
    static final int INTRO_TYPE = 14;
    static final int BUILD = 12;
    static final int HOLD = 450;
    static final int CUT = 12;
    static final int FOLD_AT = 200;
    static final int FOLD_STEP = 2;
    /** The key hint shows this long after the start and after each key the screensaver answers. */
    static final int HINT = 40;
    /** One burn-in move a minute. */
    static final int SHIFT = 600;
    static final int PAN_DP = 80;
    static final int PAGER_STEPS = 16;
    static final int BLOCK_DP = 40;
    static final int BLOCKS_X = 24;
    static final int BLOCKS_Y = 14;
    static final int BLOCKS = BLOCKS_X * BLOCKS_Y;
    /** Blocks that landed in the last step: drawn grey for that step, then in colour. */
    static final int FRONT = BLOCKS / BUILD;
    /** The HUD's four burn-in spots, in dp. */
    private static final int[][] SPOTS = {{0, 0}, {4, 0}, {4, 4}, {0, 4}};

    enum Phase { INTRO, HOLD, CUT }

    private final int count;
    private final boolean motion;
    private Phase phase;
    /** Ticks into the current phase. */
    private int t;
    /** Ticks since the start, for the cursor, the hint and the burn-in moves. */
    private int g;
    private int slide;
    private int hintUntil;

    /** typed: the header is already on screen, so the intro goes straight to building the first picture. */
    PanTimeline(int count, boolean motion, boolean typed) {
        this.count = Math.max(1, count);
        this.motion = motion;
        phase = motion ? Phase.INTRO : Phase.HOLD;
        if (motion && typed) t = INTRO_TYPE;
        hintUntil = motion ? INTRO_TYPE + BUILD + HINT : HINT;
    }

    void step() {
        g++;
        t++;
        switch (phase) {
            case INTRO:
                if (t > INTRO_TYPE + BUILD) enter(Phase.HOLD);
                break;
            case CUT:
                if (t > CUT) {
                    slide = (slide + 1) % count;
                    enter(Phase.HOLD);
                }
                break;
            default:
                if (t >= HOLD) next();
        }
    }

    private void enter(Phase p) {
        phase = p;
        t = 0;
    }

    /** Right, or the slide's time is up: dissolves to the next slide (cuts with motion off). */
    void next() {
        if (phase != Phase.HOLD) return;
        if (motion && count > 1) {
            enter(Phase.CUT);
        } else {
            slide = (slide + 1) % count;
            enter(Phase.HOLD);
        }
    }

    /** Left: cuts back to the previous slide. */
    void previous() {
        if (phase != Phase.HOLD) return;
        slide = (slide - 1 + count) % count;
        enter(Phase.HOLD);
    }

    /** A key the screensaver answered: the hint comes back. */
    void keyPressed() {
        hintUntil = g + HINT;
    }

    Phase phase() { return phase; }
    int slide() { return slide; }
    int next(int i) { return (i + 1) % count; }
    int ticks() { return g; }

    /** The slide's picture offset in dp, 0..PAN_DP; odd slides pan the other way. Centred with motion off. */
    int panDp(int forSlide, int ticks) {
        if (!motion) return PAN_DP / 2;
        int x = Math.min(ticks, HOLD) * PAN_DP / HOLD;
        return forSlide % 2 == 1 ? PAN_DP - x : x;
    }

    int panDp() {
        return panDp(slide, phase == Phase.HOLD ? t : phase == Phase.CUT ? HOLD : 0);
    }

    /** The current slide's picture is drawn whole; false while the intro still builds it from black. */
    boolean baseShown() {
        return phase != Phase.INTRO;
    }

    /** Blocks of the incoming picture shown now: the intro's first picture, or the next one in a cut. */
    int revealed() {
        if (phase == Phase.INTRO) return Math.max(0, t - INTRO_TYPE) * BLOCKS / BUILD;
        if (phase == Phase.CUT) return t * BLOCKS / CUT;
        return 0;
    }

    /** Characters of the header (path then clock) typed so far; Integer.MAX_VALUE once it is complete. */
    int headerTyped() {
        return phase == Phase.INTRO && t < INTRO_TYPE ? t : Integer.MAX_VALUE;
    }

    /** The panel shows from halfway through the intro's build. */
    boolean panelShown() {
        return phase != Phase.INTRO || t > INTRO_TYPE + BUILD / 2;
    }

    /** Ticks the current slide has been held, 0 outside the hold. */
    int held() {
        return phase == Phase.HOLD ? t : phase == Phase.CUT ? HOLD : 0;
    }

    /** 0 open; 1 without the overview; 2 without the meta line too; 3 the title alone. Night folds at once. */
    int fold(boolean night) {
        if (night) return 3;
        int h = held();
        if (h < FOLD_AT) return 0;
        return motion ? Math.min(3, (h - FOLD_AT) / FOLD_STEP + 1) : 3;
    }

    /** The meta line's characters typed: one a tick from 0.4 s. */
    int metaTyped(int length) {
        return motion ? clamp(held() - 4, length) : length;
    }

    /** The overview's characters typed: three a tick once the meta line is done. */
    int overviewTyped(int metaLength, int length) {
        return motion ? clamp((held() - 4 - metaLength) * 3, length) : length;
    }

    /** The current pager segment's fill, in steps of 1/PAGER_STEPS. */
    int pagerSteps() {
        return Math.min(PAGER_STEPS, held() * PAGER_STEPS / HOLD);
    }

    /** The header's cursor blinks five times after the start, a second each, then stays on. */
    boolean cursorOn() {
        return !motion || g >= 50 || (g / 5) % 2 == 0;
    }

    boolean hintShown(boolean night) {
        return !night && phase != Phase.INTRO && g < hintUntil;
    }

    /** The HUD's burn-in offset in dp, {x, y}. */
    int[] spot() {
        return motion ? SPOTS[(g / SHIFT) % SPOTS.length] : SPOTS[0];
    }

    /** 23:00 to 06:00: dimmer picture, the panel folded, no hint. */
    static boolean night(int hour) {
        return hour >= 23 || hour < 6;
    }

    /** The dissolve's block order, rank by block index: a fixed shuffle, so every dissolve reads the same. */
    static int[] blockRanks() {
        int[] order = new int[BLOCKS];
        for (int i = 0; i < BLOCKS; i++) order[i] = i;
        long seed = 7;
        for (int i = BLOCKS - 1; i > 0; i--) {
            seed = (seed * 9301 + 49297) % 233280;
            int j = (int) (seed * (i + 1) / 233280);
            int s = order[i];
            order[i] = order[j];
            order[j] = s;
        }
        int[] rank = new int[BLOCKS];
        for (int r = 0; r < BLOCKS; r++) rank[order[r]] = r;
        return rank;
    }

    private static int clamp(int n, int max) {
        return Math.max(0, Math.min(n, max));
    }
}
