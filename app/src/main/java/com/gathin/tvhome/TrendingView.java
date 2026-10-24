package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * (Trending does). Slides change at once: the design has no motion.
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

    private final Runnable next = () -> {
        if (rotating && items.size() > 1) show((index + 1) % items.size());
        schedule();
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
        schedule();
        invalidate();
    }

    /** Starts or stops the paging; it only runs while Home shows the card and the card is not being used. */
    void setRotating(boolean on) {
        rotating = on;
        schedule();
    }

    private void schedule() {
        handler.removeCallbacks(next);
        if (rotating && items.size() > 1 && !isFocused()) handler.postDelayed(next, ROTATE_MS);
    }

    private void show(int to) {
        if (to == index) return;
        index = to;
        layoutFor = -1;
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
        handler.removeCallbacks(next);
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
        if (it != null) drawBackdrop(c, it.image, mid, top, r, b);
        fill.setColor(focused ? Term.TEXT : Term.RULE);
        c.drawRect(mid, top, mid + d, b, fill);

        if (it == null) {
            metaPaint.setColor(Term.MUTED);
            c.drawText("// NO TRENDING YET", l + pad, top + 40 * d, metaPaint);
        } else {
            drawText(c, it, l + pad, top + 18 * d, mid - pad, b - 18 * d, focused);
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

    private void drawText(Canvas c, Trending.Item it, float l, float t, float r, float b, boolean focused) {
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
        c.translate(l, y);
        titleLayout.draw(c);
        c.restore();
        y += titleLayout.getHeight() + 8 * d;

        String meta = meta(it);
        CharSequence m = TextUtils.ellipsize(meta, metaPaint, textW, TextUtils.TruncateAt.END);
        metaPaint.setColor(Term.MUTED);
        c.drawText(m, 0, m.length(), l, y - metaPaint.ascent(), metaPaint);
        y += metaPaint.descent() - metaPaint.ascent() + 10 * d;

        // Pager row along the bottom; the overview only where it fits above it.
        float rowH = 22 * d;
        float rowTop = b - rowH;
        if (y + bodyLayout.getHeight() <= rowTop - 8 * d) {
            c.save();
            c.translate(l, y);
            bodyLayout.draw(c);
            c.restore();
        }

        float cy = rowTop + rowH / 2f;
        float x = l, seg = 10 * d, cur = 22 * d, gap = 3 * d, segH = 3 * d;
        for (int i = 0; i < items.size(); i++) {
            float w = i == index ? cur : seg;
            fill.setColor(i == index ? Term.TEXT : Term.MARK);
            c.drawRect(x, cy - segH / 2, x + w, cy + segH / 2, fill);
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
