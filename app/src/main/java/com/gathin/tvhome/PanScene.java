package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The trending screensaver's screen (TrendingDream), drawn in one view: the slide's backdrop panning under a dim
 * and the dot grid, the incoming picture in blocks, and the HUD (path, network, date and clock; the slide's
 * panel; the key hint). PanTimeline decides what shows when. Every piece of text sits on solid ground, and the
 * HUD moves 4 dp once a minute, so a TV left on overnight keeps no ghost of it.
 *
 * Pictures: the large backdrop (Trending.large) of the current and the next slide, decoded off the main thread;
 * until it arrives, or offline without it cached, Home's smaller one is drawn instead.
 */
final class PanScene extends View {

    /** Dim over the picture, by day and at night, out of 255. */
    private static final int DIM_DAY = Math.round(0.30f * 255);
    private static final int DIM_NIGHT = Math.round(0.68f * 255);
    private static final int SOFT = 0xFFB4B1AC;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final float d;
    private final int[] ranks = PanTimeline.blockRanks();
    private final Trending trending;

    private final Paint fill = new Paint();
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint grey = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint nightPicture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint();
    private final Paint dots = new Paint();
    private final TextPaint path, label, clockPaint, chip, meta, overview, title, titleSmall;

    private List<Trending.Item> items = new ArrayList<>();
    /** Large pictures by slide index: the current and the next one, at most. */
    private final Map<Integer, Bitmap> large = new HashMap<>();
    private PanTimeline timeline;
    private boolean motion = true;
    private boolean online = true;
    private String opening;
    private int openingUntil;

    private String clock = "", date = "";
    private int clockMinute = -1;
    private int hour;

    private final Matrix matrix = new Matrix();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (timeline == null) return;
            int before = timeline.slide();
            timeline.step();
            if (timeline.slide() != before) prefetch();
            invalidate();
            handler.postDelayed(this, PanTimeline.TICK_MS);
        }
    };

    PanScene(Context context, Trending trending) {
        super(context);
        this.trending = trending;
        d = context.getResources().getDisplayMetrics().density;
        ColorMatrix g = new ColorMatrix();
        g.setSaturation(0f);
        ColorMatrix dark = new ColorMatrix();
        dark.setScale(0.8f, 0.8f, 0.8f, 1f);
        g.postConcat(dark);
        grey.setColorFilter(new ColorMatrixColorFilter(g));
        ColorMatrix n = new ColorMatrix();
        n.setSaturation(0.6f);
        nightPicture.setColorFilter(new ColorMatrixColorFilter(n));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1, Math.round(d)));

        // The house dot grid, one dot every 7 dp, drawn over the picture at 45 %.
        int step = Math.round(7 * d), dot = Math.max(1, Math.round(d));
        Bitmap tile = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setColor(Term.DOT);
        new Canvas(tile).drawRect(0, 0, dot, dot, p);
        dots.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        dots.setAlpha(Math.round(0.45f * 255));

        path = Term.monoPaint(context, 14, Term.TEXT);
        label = caps(Term.monoPaint(context, 10, Term.MUTED));
        clockPaint = Term.monoPaint(context, 30, Term.TEXT);
        chip = caps(Term.monoPaint(context, 10, SOFT));
        meta = Term.monoPaint(context, 12, SOFT);
        meta.setLetterSpacing(0.06f);
        overview = Term.monoPaint(context, 13, SOFT);
        title = Term.displayPaint(context, 28, Term.TEXT);
        titleSmall = Term.displayPaint(context, 20, Term.TEXT);
    }

    private static TextPaint caps(TextPaint p) {
        p.setLetterSpacing(0.08f);
        return p;
    }

    /** Pictures from Trending's list; Spotify's square covers are left to Home. */
    void setItems(List<Trending.Item> all) {
        List<Trending.Item> list = new ArrayList<>();
        for (Trending.Item i : all) if (!Trending.SPOTIFY.equals(i.pkg) && i.image != null) list.add(i);
        boolean first = items.isEmpty();
        int before = items.size();
        items = list;
        large.clear();
        // The first pictures build in as the intro does (the header stays typed if it already was); a list of
        // another length starts over at its first slide.
        if (timeline == null) timeline = new PanTimeline(items.size(), motion, false);
        else if (first) timeline = new PanTimeline(items.size(), motion, timeline.headerTyped() == Integer.MAX_VALUE);
        else if (items.size() != before) timeline = new PanTimeline(items.size(), motion, true);
        prefetch();
        invalidate();
    }

    void setMotion(boolean motion) {
        this.motion = motion;
    }

    void setOnline(boolean online) {
        this.online = online;
        invalidate();
    }

    void start() {
        if (timeline == null) timeline = new PanTimeline(items.size(), motion, false);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, PanTimeline.TICK_MS);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
    }

    void release() {
        stop();
        io.shutdownNow();
        large.clear();
    }

    /** The slide on screen, or null while there is none. */
    Trending.Item current() {
        return items.isEmpty() || timeline == null ? null : items.get(timeline.slide());
    }

    String appName(Trending.Item item) {
        if (Trending.YOUTUBE.equals(item.pkg)) return "YOUTUBE";
        if (Trending.NUVIO.equals(item.pkg)) return "NUVIO";
        if (Trending.APPLE_TV.equals(item.pkg)) return "APPLE TV";
        return "APP";
    }

    void next() {
        if (timeline == null) return;
        timeline.next();
        timeline.keyPressed();
        prefetch();
        invalidate();
    }

    void previous() {
        if (timeline == null) return;
        timeline.previous();
        timeline.keyPressed();
        prefetch();
        invalidate();
    }

    /** OK: a red square marks the press while the app opens. */
    void showOpening(Trending.Item item) {
        opening = "OK · OPENING " + appName(item);
        openingUntil = timeline == null ? 0 : timeline.ticks() + 12;
        invalidate();
    }

    /** Decodes the large pictures of the current and next slide; drops the rest. */
    private void prefetch() {
        if (items.isEmpty() || timeline == null || trending == null) return;
        int cur = timeline.slide(), nxt = timeline.next(cur);
        large.keySet().retainAll(java.util.Arrays.asList(cur, nxt));
        for (int i : new int[]{cur, nxt}) {
            if (large.containsKey(i)) continue;
            final int index = i;
            final Trending.Item item = items.get(i);
            final List<Trending.Item> list = items;
            io.execute(() -> {
                Bitmap bm = trending.large(item);
                if (bm == null) return;
                handler.post(() -> {
                    // Still the same list and still wanted: keep it.
                    if (items != list || timeline == null) return;
                    int c = timeline.slide();
                    if (index == c || index == timeline.next(c)) {
                        large.put(index, bm);
                        invalidate();
                    }
                });
            });
        }
    }

    private Bitmap picture(int i) {
        Bitmap bm = large.get(i);
        return bm != null ? bm : items.get(i).image;
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (minute == clockMinute) return;
        clockMinute = minute;
        hour = now.get(Calendar.HOUR_OF_DAY);
        date = Term.upper(DateFormat.format("EEE d MMM", now));
        clock = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", now).toString();
    }

    @Override
    protected void onDraw(Canvas c) {
        updateClock();
        int w = getWidth(), h = getHeight();
        fill.setColor(Term.BG);
        c.drawRect(0, 0, w, h, fill);
        if (timeline == null) return;
        boolean night = PanTimeline.night(hour);
        boolean any = !items.isEmpty();

        if (any) {
            int cur = timeline.slide();
            if (timeline.baseShown()) drawPicture(c, picture(cur), timeline.panDp(), night ? nightPicture : picture);
            int shown = timeline.revealed();
            if (shown > 0) {
                int in = timeline.phase() == PanTimeline.Phase.INTRO ? cur : timeline.next(cur);
                drawBlocks(c, picture(in), timeline.panDp(in, 0), shown);
            }
            fill.setColor(Term.BG);
            fill.setAlpha(night ? DIM_NIGHT : DIM_DAY);
            c.drawRect(0, 0, w, h, fill);
            fill.setAlpha(255);
        }
        c.drawRect(0, 0, w, h, dots);

        int[] spot = timeline.spot();
        c.save();
        c.translate(dp(spot[0]), dp(spot[1]));
        drawHeader(c, w);
        if (any && timeline.panelShown()) drawPanel(c, h, night);
        if (opening != null && timeline.ticks() < openingUntil) drawOpening(c, w, h);
        else if (any && timeline.hintShown(night)) drawHint(c, w, h);
        c.restore();
    }

    /** The picture covers the screen plus the pan's spare width, centred vertically, never scaled by the pan. */
    private float cover(Bitmap bm, int w, int h) {
        return Math.max((w + dp(PanTimeline.PAN_DP)) / (float) bm.getWidth(), h / (float) bm.getHeight());
    }

    private void drawPicture(Canvas c, Bitmap bm, int panDp, Paint paint) {
        if (bm == null) return;
        int w = getWidth(), h = getHeight();
        float s = cover(bm, w, h);
        matrix.setScale(s, s);
        matrix.postTranslate(-dp(panDp), (h - bm.getHeight() * s) / 2f);
        c.drawBitmap(bm, matrix, paint);
    }

    /** The first {@code shown} blocks in the fixed order; the newest ones grey for this step. */
    private void drawBlocks(Canvas c, Bitmap bm, int panDp, int shown) {
        if (bm == null) return;
        int w = getWidth(), h = getHeight();
        float s = cover(bm, w, h);
        float ox = -dp(panDp), oy = (h - bm.getHeight() * s) / 2f;
        float size = dp(PanTimeline.BLOCK_DP);
        for (int b = 0; b < PanTimeline.BLOCKS; b++) {
            int r = ranks[b];
            if (r >= shown) continue;
            float x = (b % PanTimeline.BLOCKS_X) * size, y = (b / PanTimeline.BLOCKS_X) * size;
            dst.set(x, y, Math.min(w, x + size), Math.min(h, y + size));
            if (dst.isEmpty()) continue;
            src.set(Math.round((dst.left - ox) / s), Math.round((dst.top - oy) / s),
                    Math.round((dst.right - ox) / s), Math.round((dst.bottom - oy) / s));
            c.drawBitmap(bm, src, dst, r >= shown - PanTimeline.FRONT ? grey : picture);
        }
    }

    // ---- HUD -------------------------------------------------------------------------------------

    private void solid(Canvas c, float l, float t, float r, float b) {
        fill.setColor(Term.BG);
        c.drawRect(l, t, r, b, fill);
    }

    private void frame(Canvas c, float l, float t, float r, float b, int color) {
        stroke.setColor(color);
        float half = stroke.getStrokeWidth() / 2f;
        c.drawRect(l + half, t + half, r - half, b - half, stroke);
    }

    /** "~ / idle" with its cursor at the left; network, date and clock at the right; typed in at the start. */
    private void drawHeader(Canvas c, int w) {
        String pathText = "~ / idle";
        int typed = timeline.headerTyped();
        String p = typed >= pathText.length() ? pathText : pathText.substring(0, Math.max(0, typed));
        String k = typed == Integer.MAX_VALUE ? clock : clock.substring(0, Math.max(0, Math.min(clock.length(), typed - pathText.length())));

        float left = dp(44), top = dp(24);
        float pw = path.measureText(p);
        float chipH = dp(28);
        solid(c, left, top, left + dp(10) + pw + dp(6) + dp(8) + dp(10), top + chipH);
        float base = top + chipH / 2f - (path.ascent() + path.descent()) / 2f;
        c.drawText(p, left + dp(10), base, path);
        if (timeline.cursorOn()) {
            fill.setColor(Term.TEXT);
            float cx = left + dp(10) + pw + dp(6);
            c.drawRect(cx, top + (chipH - dp(16)) / 2f, cx + dp(8), top + (chipH + dp(16)) / 2f, fill);
        }

        String status = online ? "ONLINE" : "OFFLINE · CACHED";
        float right = w - dp(44), ctop = dp(18), ch = dp(44);
        float kw = clockPaint.measureText(clock);   // the full width, so the block does not grow while typing
        float dw = label.measureText(date), sw = label.measureText(status);
        float inner = dp(7) + dp(7) + sw + dp(24) + dw + dp(18) + kw;
        float l = right - dp(12) - inner - dp(12);
        solid(c, l, ctop, right, ctop + ch);
        float mid = ctop + ch / 2f;
        float x = l + dp(12);
        fill.setColor(online ? Term.AMBER : Term.MARK);
        c.drawRect(x, mid - dp(3.5f), x + dp(7), mid + dp(3.5f), fill);
        x += dp(14);
        float lb = mid - (label.ascent() + label.descent()) / 2f;
        c.drawText(status, x, lb, label);
        x += sw + dp(24);
        c.drawText(date, x, lb, label);
        x += dw + dp(18);
        c.drawText(k, x, mid - (clockPaint.ascent() + clockPaint.descent()) / 2f, clockPaint);
    }

    /** The slide's panel: feed and counter, chip, title, meta, overview and pager; folds part by part. */
    private void drawPanel(Canvas c, int h, boolean night) {
        Trending.Item item = items.get(timeline.slide());
        int fold = timeline.fold(night);
        float pw = dp(460), pad = dp(14), inner = pw - 2 * pad;
        float left = dp(44);

        String feed = "TRENDING / " + appName(item);
        String counter = String.format(java.util.Locale.ROOT, "%02d / %02d", timeline.slide() + 1, items.size());
        String chipText = Term.upper(chipText(item));
        String metaText = Term.upper(metaText(item));
        String over = item.overview == null ? "" : item.overview;

        TextPaint tp = fold >= 3 ? titleSmall : title;
        StaticLayout titleLayout = layout(item.title, tp, inner, 2, 1.08f);
        String metaShown = metaText.substring(0, timeline.metaTyped(metaText.length()));
        String overShown = over.substring(0, timeline.overviewTyped(metaText.length(), over.length()));
        StaticLayout overLayout = fold < 1 && !overShown.isEmpty() ? layout(overShown, overview, inner, 2, 1.25f) : null;

        float headH = dp(30), gap = dp(10);
        float bodyH = dp(14) + titleLayout.getHeight() + gap + dp(3) + dp(12);
        float chipH = dp(20);
        if (fold < 3) bodyH += chipH + gap;
        if (fold < 2) bodyH += dp(15) + gap;
        if (overLayout != null) bodyH += overLayout.getHeight() + gap;
        float bottom = h - dp(44), top = bottom - headH - bodyH;

        solid(c, left, top, left + pw, bottom);
        frame(c, left, top, left + pw, bottom, Term.RULE);
        fill.setColor(Term.RULE);
        c.drawRect(left, top + headH - Math.max(1, Math.round(d)), left + pw, top + headH, fill);
        float hb = top + headH / 2f - (label.ascent() + label.descent()) / 2f;
        c.drawText(feed, left + pad, hb, label);
        c.drawText(counter, left + pw - pad - label.measureText(counter), hb, label);

        float y = top + headH + dp(14);
        if (fold < 3) {
            float cw = chip.measureText(chipText) + dp(16);
            frame(c, left + pad, y, left + pad + cw, y + chipH, Term.MARK);
            c.drawText(chipText, left + pad + dp(8), y + chipH / 2f - (chip.ascent() + chip.descent()) / 2f, chip);
            y += chipH + gap;
        }
        c.save();
        c.translate(left + pad, y);
        titleLayout.draw(c);
        c.restore();
        y += titleLayout.getHeight() + gap;
        if (fold < 2) {
            c.drawText(metaShown, left + pad, y - meta.ascent(), meta);
            y += dp(15) + gap;
        }
        if (overLayout != null) {
            c.save();
            c.translate(left + pad, y);
            overLayout.draw(c);
            c.restore();
            y += overLayout.getHeight() + gap;
        }
        drawPager(c, left + pad, y + dp(2), inner);
    }

    /** One segment a slide; the current one fills in sixteen steps over its 45 s. */
    private void drawPager(Canvas c, float x, float y, float width) {
        int n = items.size();
        float gap = n > 16 ? dp(2) : dp(4);
        float seg = (width - gap * (n - 1)) / n;
        float hh = dp(3);
        int cur = timeline.slide();
        for (int i = 0; i < n; i++) {
            float sx = x + i * (seg + gap);
            fill.setColor(Term.RULE);
            c.drawRect(sx, y, sx + seg, y + hh, fill);
            float f = i < cur ? 1f : i == cur ? timeline.pagerSteps() / (float) PanTimeline.PAGER_STEPS : 0f;
            if (f > 0) {
                fill.setColor(Term.TEXT);
                c.drawRect(sx, y, sx + seg * f, y + hh, fill);
            }
        }
    }

    private void drawHint(Canvas c, int w, int h) {
        Trending.Item item = items.get(timeline.slide());
        String text = "OK · OPEN IN " + appName(item) + "   ←/→ · SLIDE   BACK · EXIT";
        float tw = chip.measureText(text);
        float right = w - dp(44), bottom = h - dp(44), top = bottom - dp(28), left = right - tw - dp(24);
        solid(c, left, top, right, bottom);
        frame(c, left, top, right, bottom, Term.RULE);
        c.drawText(text, left + dp(12), (top + bottom) / 2f - (chip.ascent() + chip.descent()) / 2f, chip);
    }

    /** The one red on this screen: OK's press, as on Home's open button. */
    private void drawOpening(Canvas c, int w, int h) {
        float tw = chip.measureText(opening);
        float right = w - dp(44), bottom = h - dp(44), top = bottom - dp(28), left = right - tw - dp(12) - dp(18) - dp(12);
        solid(c, left, top, right, bottom);
        frame(c, left, top, right, bottom, Term.RED);
        float mid = (top + bottom) / 2f;
        fill.setColor(Term.RED);
        c.drawRect(left + dp(12), mid - dp(4), left + dp(20), mid + dp(4), fill);
        int was = chip.getColor();
        chip.setColor(Term.TEXT);
        c.drawText(opening, left + dp(30), mid - (chip.ascent() + chip.descent()) / 2f, chip);
        chip.setColor(was);
    }

    private static String chipText(Trending.Item item) {
        String app = Trending.YOUTUBE.equals(item.pkg) ? "YouTube" : Trending.NUVIO.equals(item.pkg) ? "Nuvio"
                : Trending.APPLE_TV.equals(item.pkg) ? "Apple TV" : "";
        return item.kind == null || item.kind.isEmpty() ? app : app + " · " + item.kind;
    }

    /** "2026 · SCI-FI · TMDB 6.8", from the parts Trending keeps apart; older lists fall back to their meta. */
    private static String metaText(Trending.Item item) {
        StringBuilder m = new StringBuilder();
        if (item.year != null && !item.year.isEmpty()) m.append(item.year);
        if (item.genre != null && !item.genre.isEmpty()) m.append(m.length() > 0 ? " · " : "").append(item.genre);
        if (item.rating > 0) m.append(m.length() > 0 ? " · " : "").append(String.format(java.util.Locale.ROOT, "TMDB %.1f", item.rating));
        return m.length() > 0 ? m.toString() : item.meta == null ? "" : item.meta;
    }

    private static StaticLayout layout(String text, TextPaint paint, float width, int lines, float spacing) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.round(width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacing)
                .setIncludePad(false)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }
}
