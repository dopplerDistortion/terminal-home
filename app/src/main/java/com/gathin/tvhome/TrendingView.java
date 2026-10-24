package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The trending card of the terminal home: label row, then the title, meta and overview on the left and the TMDB
 * backdrop on the right, with a ten-segment pager. One view drawn by hand, so it costs one layer; it pages on its
 * own every {@link #ROTATE_MS} while it is on screen and not focused, and never touches the network itself
 * (Trending does). Motion is stepped, never eased: the current pager segment fills in sixteen steps over the
 * interval, and a new slide comes in through a 240 ms monochrome glitch (three frames, the slide shifted
 * sideways, a bone or gray band across it) before its meta line types in. The red button never moves.
 *
 * The view is {@link #OUT_DP} larger than the card on every side, room for the focus outline and the arrow tabs.
 */
final class TrendingView extends View {
    interface Listener {
        void onOpen(Trending.Item item);
    }

    static final int OUT_DP = 10;
    private static final long ROTATE_MS = 8000;
    private static final int MAX_SLIDES = 10;
    private static final int PAGER_STEPS = 16;
    private static final long GLITCH_FRAME_MS = 80;
    private static final int GLITCH_FRAMES = 3;
    private static final int META_STEPS = 8;
    private static final long META_STEP_MS = 25;
    private static final float[] GLITCH_DX = {6, -4, 2};

    private final float d;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint labelPaint;
    private final TextPaint chipPaint;
    private final TextPaint titlePaint;
    private final TextPaint metaPaint;
    private final TextPaint bodyPaint;
    private final TextPaint buttonPaint;
    private final TextPaint arrowPaint;
    private final float titleFull;
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final RectF dst = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ColorMatrixColorFilter mono;

    private List<Trending.Item> all = new ArrayList<>();
    private List<Trending.Item> items = new ArrayList<>();
    private String feedPkg;
    private String feedName = "ALL";
    private long cachedAt;
    private int index;
    private Listener listener;
    private boolean rotating;
    private StaticLayout titleLayout;
    private StaticLayout bodyLayout;
    private int layoutFor = -1;
    private int layoutWidth = -1;
    /** Time the current slide has been shown and paging was running, in whole pager steps. */
    private long elapsed;
    /** Glitch frame on screen, or -1 once the slide has settled. */
    private int glitch = -1;
    /** Characters of the meta line typed so far. */
    private int metaShown = Integer.MAX_VALUE;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running()) return;
            elapsed += ROTATE_MS / PAGER_STEPS;
            if (elapsed >= ROTATE_MS) show((index + 1) % items.size());
            else invalidate();
            schedule();
        }
    };
    private final Runnable glitchStep = new Runnable() {
        @Override public void run() {
            glitch++;
            if (glitch < GLITCH_FRAMES) {
                handler.postDelayed(this, GLITCH_FRAME_MS);
            } else {
                glitch = -1;
                metaShown = 0;
                handler.postDelayed(metaStep, META_STEP_MS);
            }
            invalidate();
        }
    };
    private final Runnable metaStep = new Runnable() {
        @Override public void run() {
            Trending.Item it = current();
            int len = it == null ? 0 : meta(it).length();
            metaShown += Math.max(1, (len + META_STEPS - 1) / META_STEPS);
            if (metaShown < len) handler.postDelayed(this, META_STEP_MS);
            else metaShown = Integer.MAX_VALUE;
            invalidate();
        }
    };

    TrendingView(Context context) {
        super(context);
        d = context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        labelPaint = Term.monoPaint(context, 10, Term.TEXT);
        labelPaint.setLetterSpacing(0.1f);
        chipPaint = Term.monoPaint(context, 9, Term.MUTED);
        chipPaint.setLetterSpacing(0.1f);
        titlePaint = Term.displayPaint(context, 38, Term.TEXT);
        titleFull = titlePaint.getTextSize();
        metaPaint = Term.monoPaint(context, 10, Term.MUTED);
        metaPaint.setLetterSpacing(0.08f);
        bodyPaint = Term.monoPaint(context, 12, Term.TEXT);
        buttonPaint = Term.monoPaint(context, 10, Term.TEXT);
        buttonPaint.setLetterSpacing(0.1f);
        arrowPaint = Term.monoPaint(context, 14, Term.BG);
        line.setStyle(Paint.Style.STROKE);
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0);
        mono = new ColorMatrixColorFilter(grey);
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    Trending.Item current() {
        return items.isEmpty() ? null : items.get(index);
    }

    void setItems(List<Trending.Item> list, long cachedAt) {
        all = list;
        this.cachedAt = cachedAt;
        filter();
    }

    /**
     * Which app's trending the card pages through: a package of the feed (YouTube, Nuvio, Apple TV), or null for
     * the mixed feed, the first ten of all three interleaved.
     */
    void setFeed(String pkg, String name) {
        if (TextUtils.equals(pkg, feedPkg) && name.equals(feedName)) return;
        feedPkg = pkg;
        feedName = name;
        filter();
    }

    private void filter() {
        List<Trending.Item> out = new ArrayList<>();
        for (Trending.Item it : all) {
            if (feedPkg == null || feedPkg.equals(it.pkg)) out.add(it);
            if (out.size() == MAX_SLIDES) break;
        }
        items = out;
        index = 0;
        layoutFor = -1;
        elapsed = 0;
        startGlitch();
        schedule();
        invalidate();
    }

    /** Starts or stops the paging; it only runs while Home shows the card and the card is not being used. */
    void setRotating(boolean on) {
        rotating = on;
        schedule();
    }

    private boolean running() {
        return rotating && items.size() > 1 && !isFocused();
    }

    /** Paging resumes where it stopped: the progress kept in elapsed is not lost to a focus visit. */
    private void schedule() {
        handler.removeCallbacks(tick);
        if (running()) handler.postDelayed(tick, ROTATE_MS / PAGER_STEPS);
    }

    private void show(int to) {
        if (to == index) return;
        index = to;
        layoutFor = -1;
        elapsed = 0;
        startGlitch();
        invalidate();
    }

    private void startGlitch() {
        handler.removeCallbacks(glitchStep);
        handler.removeCallbacks(metaStep);
        if (!Term.motion() || items.isEmpty()) {
            glitch = -1;
            metaShown = Integer.MAX_VALUE;
            return;
        }
        glitch = 0;
        metaShown = 0;
        handler.postDelayed(glitchStep, GLITCH_FRAME_MS);
    }

    /** A new key press: the slide settles at once, so the card never makes the remote wait. */
    void finishAnimations() {
        if (glitch < 0 && metaShown == Integer.MAX_VALUE) return;
        handler.removeCallbacks(glitchStep);
        handler.removeCallbacks(metaStep);
        glitch = -1;
        metaShown = Integer.MAX_VALUE;
        invalidate();
    }

    @Override protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        schedule();
        invalidate();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (items.size() > 1) show((index + items.size() - 1) % items.size());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (items.size() > 1) show((index + 1) % items.size());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;   // the header above takes no focus
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0 && listener != null && !items.isEmpty()) listener.onOpen(items.get(index));
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override public boolean performClick() {
        if (listener != null && !items.isEmpty()) listener.onOpen(items.get(index));
        return super.performClick();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(glitchStep);
        handler.removeCallbacks(metaStep);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutFor = -1;
    }

    static String appName(String pkg) {
        if (Trending.YOUTUBE.equals(pkg)) return "YOUTUBE";
        if (Trending.NUVIO.equals(pkg)) return "NUVIO";
        if (Trending.APPLE_TV.equals(pkg)) return "APPLE TV";
        return "APP";
    }

    private String cachedLabel() {
        if (cachedAt <= 0) return "TMDB";
        long min = Math.max(0, (System.currentTimeMillis() - cachedAt) / 60000);
        String age = min < 1 ? "JUST NOW" : min < 60 ? min + "M AGO" : (min / 60) + "H AGO";
        return "TMDB · CACHED " + age;
    }

    @Override protected void onDraw(Canvas c) {
        float o = OUT_DP * d;
        float l = o, t = o, r = getWidth() - o, b = getHeight() - o;
        boolean focused = isFocused();

        fill.setColor(Term.CARD);
        c.drawRect(l, t, r, b, fill);

        // Label row: "TRENDING / NUVIO" and the cache age; bone with dark text while the card has focus.
        float rowH = 28 * d;
        float pad = 22 * d;
        if (focused) {
            fill.setColor(Term.TEXT);
            c.drawRect(l, t, r, t + rowH, fill);
        }
        float baseline = t + rowH / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f;
        labelPaint.setColor(focused ? Term.BG : Term.TEXT);
        String head = "TRENDING / " + feedName;
        c.drawText(head, l + 14 * d, baseline, labelPaint);
        labelPaint.setColor(focused ? Term.BG : Term.MUTED);
        String age = cachedLabel();
        c.drawText(age, r - 14 * d - labelPaint.measureText(age), baseline, labelPaint);
        fill.setColor(focused ? Term.TEXT : Term.RULE);
        c.drawRect(l, t + rowH - d, r, t + rowH, fill);

        float mid = Math.round((l + r) / 2f);
        float top = t + rowH;
        Trending.Item it = items.isEmpty() ? null : items.get(index);
        float dx = glitch >= 0 ? GLITCH_DX[glitch] * d : 0;
        if (it != null) {
            c.save();
            c.clipRect(mid, top, r, b);
            image.setColorFilter(glitch >= 0 ? mono : null);
            drawBackdrop(c, it.image, mid + dx, top, r + dx, b);
            if (glitch == 0) {
                fill.setColor(Term.TEXT);
                c.drawRect(mid, top + (b - top) * 0.34f, r, top + (b - top) * 0.42f, fill);
            }
            c.restore();
        }
        fill.setColor(focused ? Term.TEXT : Term.RULE);
        c.drawRect(mid, top, mid + d, b, fill);

        if (it == null) {
            metaPaint.setColor(Term.MUTED);
            c.drawText("// NO TRENDING YET", l + pad, top + 40 * d, metaPaint);
        } else {
            drawText(c, it, l + pad, top + 18 * d, mid - pad, b - 18 * d, focused, dx);
            if (glitch == 1) {
                // A gray slice across the text half, as if a scanline dropped out.
                fill.setColor(Term.HELD);
                c.drawRect(l + d, top + (b - top) * 0.46f, mid, top + (b - top) * 0.52f, fill);
            }
        }

        // Border: 1dp inside the card, and the 2dp focus outline 4dp clear of it.
        line.setStrokeWidth(d);
        line.setColor(focused ? Term.TEXT : Term.RULE);
        c.drawRect(l + d / 2, t + d / 2, r - d / 2, b - d / 2, line);
        if (focused) {
            line.setStrokeWidth(2 * d);
            c.drawRect(l - 5 * d, t - 5 * d, r + 5 * d, b + 5 * d, line);
            if (items.size() > 1) {
                drawTab(c, l, (t + b) / 2f, "←");
                drawTab(c, r, (t + b) / 2f, "→");
            }
        }
    }

    /** A bone tab across the card's edge with a dark arrow: Left and Right page the card. */
    private void drawTab(Canvas c, float x, float cy, String arrow) {
        float w = 18 * d, h = 44 * d;
        fill.setColor(Term.TEXT);
        c.drawRect(x - w / 2, cy - h / 2, x + w / 2, cy + h / 2, fill);
        c.drawText(arrow, x - arrowPaint.measureText(arrow) / 2f, cy - (arrowPaint.ascent() + arrowPaint.descent()) / 2f, arrowPaint);
    }

    /** The backdrop fills the right half, centre-cropped; TMDB's are 16:9, about the half's own shape. */
    private void drawBackdrop(Canvas c, Bitmap bm, float l, float t, float r, float b) {
        if (bm == null) {
            metaPaint.setColor(Term.MARK);
            String s = "// NO BACKDROP";
            c.drawText(s, (l + r - metaPaint.measureText(s)) / 2f, (t + b) / 2f, metaPaint);
            return;
        }
        float bw = bm.getWidth(), bh = bm.getHeight();
        float boxW = r - l, boxH = b - t;
        float scale = Math.max(boxW / bw, boxH / bh);
        float sw = boxW / scale, sh = boxH / scale;
        src.set(Math.round((bw - sw) / 2f), Math.round((bh - sh) / 2f), Math.round((bw + sw) / 2f), Math.round((bh + sh) / 2f));
        dst.set(l, t, r, b);
        c.drawBitmap(bm, src, dst, image);
    }

    private void drawText(Canvas c, Trending.Item it, float l, float t, float r, float b, boolean focused, float dx) {
        int textW = (int) (r - l);
        if (layoutFor != index || layoutWidth != textW) {
            layoutTitle(it.title, textW);
            CharSequence o = it.overview == null ? "" : it.overview;
            bodyLayout = StaticLayout.Builder.obtain(o, 0, o.length(), bodyPaint, textW)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.25f).build();
            layoutFor = index;
            layoutWidth = textW;
        }

        // Source chip, outlined.
        String chip = appName(it.pkg) + (it.kind.isEmpty() ? "" : " · " + Term.upper(it.kind));
        float chipH = 20 * d, chipW = chipPaint.measureText(chip) + 16 * d;
        line.setStrokeWidth(d);
        line.setColor(Term.MARK);
        c.drawRect(l + d / 2, t + d / 2, l + chipW - d / 2, t + chipH - d / 2, line);
        c.drawText(chip, l + 8 * d, t + chipH / 2f - (chipPaint.ascent() + chipPaint.descent()) / 2f, chipPaint);

        float y = t + chipH + 12 * d;
        c.save();
        c.translate(l + dx, y);
        if (glitch >= 0) titlePaint.setColor(glitch == 1 ? Term.MUTED : Term.TEXT);
        titleLayout.draw(c);
        titlePaint.setColor(Term.TEXT);
        c.restore();
        y += titleLayout.getHeight() + 8 * d;

        String meta = meta(it);
        CharSequence m = TextUtils.ellipsize(meta, metaPaint, textW, TextUtils.TruncateAt.END);
        int shown = Math.min(m.length(), metaShown);
        metaPaint.setColor(Term.MUTED);
        if (glitch < 0) c.drawText(m, 0, shown, l, y - metaPaint.ascent(), metaPaint);
        y += metaPaint.descent() - metaPaint.ascent() + 10 * d;

        // Pager row along the bottom; the overview only where it fits above it.
        float rowH = 22 * d;
        float rowTop = b - rowH;
        if (glitch < 0 && y + bodyLayout.getHeight() <= rowTop - 8 * d) {
            c.save();
            c.translate(l, y);
            bodyLayout.draw(c);
            c.restore();
        }

        float cy = rowTop + rowH / 2f;
        float x = l, seg = 10 * d, cur = 22 * d, gap = 3 * d, segH = 3 * d;
        // The current segment fills with the interval, one of sixteen steps at a time; alone, it is simply full.
        float progress = items.size() > 1 ? (float) (elapsed * PAGER_STEPS / ROTATE_MS) / PAGER_STEPS : 1f;
        for (int i = 0; i < items.size(); i++) {
            float w = i == index ? cur : seg;
            fill.setColor(i == index ? Term.MUTED : Term.MARK);
            c.drawRect(x, cy - segH / 2, x + w, cy + segH / 2, fill);
            if (i == index && progress > 0) {
                fill.setColor(Term.TEXT);
                c.drawRect(x, cy - segH / 2, x + w * progress, cy + segH / 2, fill);
            }
            x += w + gap;
        }
        String count = String.format(Locale.ROOT, "%02d / %02d", index + 1, items.size());
        metaPaint.setColor(Term.MUTED);
        c.drawText(count, x + 8 * d, cy - (metaPaint.ascent() + metaPaint.descent()) / 2f, metaPaint);

        if (focused) {
            String open = "OK · OPEN " + appName(it.pkg);
            float bw = buttonPaint.measureText(open) + 20 * d;
            fill.setColor(Term.RED);
            c.drawRect(r - bw, rowTop, r, b, fill);
            buttonPaint.setColor(Term.TEXT);
            c.drawText(open, r - bw + 10 * d, cy - (buttonPaint.ascent() + buttonPaint.descent()) / 2f, buttonPaint);
        }
    }

    /** One line at 38sp when the title fits, otherwise two at 30sp, ellipsized past that. */
    private void layoutTitle(String title, int width) {
        titlePaint.setTextSize(titleFull);
        boolean one = titlePaint.measureText(title) <= width;
        if (!one) titlePaint.setTextSize(titleFull * 30f / 38f);
        titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, width)
                .setMaxLines(one ? 1 : 2).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
    }

    private static String meta(Trending.Item it) {
        StringBuilder m = new StringBuilder();
        if (!it.year.isEmpty()) m.append(it.year);
        if (!it.genre.isEmpty()) m.append(m.length() > 0 ? " · " : "").append(Term.upper(it.genre));
        if (it.rating > 0) m.append(m.length() > 0 ? " · " : "").append(String.format(Locale.ROOT, "TMDB %.1f", it.rating));
        return m.length() > 0 ? m.toString() : Term.upper(it.meta);
    }
}
