package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
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

/**
 * The trending carousel above the dock: a backdrop on the right fading into the title, a source badge and the
 * overview on the left. One view drawn by hand, so it costs one layer; it rotates while it is on screen and
 * never touches the network itself (Trending does).
 */
final class TrendingView extends View {
    interface Listener {
        void onOpen(Trending.Item item);
    }

    private static final long ROTATE_MS = 8000;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean animate;

    private List<Trending.Item> items = new ArrayList<>();
    private int index;
    private int previous = -1;
    private float fade = 1f;
    private ValueAnimator fader;
    private Listener listener;
    private boolean rotating;
    private StaticLayout titleLayout;
    private StaticLayout bodyLayout;
    private int layoutFor = -1;

    private final Runnable next = () -> {
        if (rotating && items.size() > 1) show((index + 1) % items.size());
        schedule();
    };

    TrendingView(Context context, boolean animate) {
        super(context);
        this.animate = animate;
        d = context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextSize(32 * d);
        titlePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bodyPaint.setColor(0xCCFFFFFF);
        bodyPaint.setTextSize(15 * d);
        bodyPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        badgePaint.setColor(0xFFFFFFFF);
        badgePaint.setTextSize(13 * d);
        badgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badgePaint.setLetterSpacing(0.1f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3 * d);
        stroke.setColor(0xF2FFFFFF);
        setVisibility(GONE);
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

    void setItems(List<Trending.Item> list) {
        items = list;
        index = 0;
        previous = -1;
        layoutFor = -1;
        invalidate();
    }

    /** Starts or stops the rotation; it only runs while the shelf is shown and the view is not being used. */
    void setRotating(boolean on) {
        rotating = on;
        handler.removeCallbacks(next);
        if (on) schedule();
    }

    private void schedule() {
        handler.removeCallbacks(next);
        if (rotating && items.size() > 1 && !isFocused()) handler.postDelayed(next, ROTATE_MS);
    }

    private void show(int to) {
        if (to == index) return;
        previous = index;
        index = to;
        layoutFor = -1;
        if (fader != null) fader.cancel();
        if (!animate) {
            fade = 1f;
            invalidate();
            return;
        }
        fade = 0f;
        fader = ValueAnimator.ofFloat(0f, 1f).setDuration(450);
        fader.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            invalidate();
        });
        fader.start();
    }

    @Override protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        animate().scaleX(gained ? 1.015f : 1f).scaleY(gained ? 1.015f : 1f).setDuration(animate ? 160 : 0).start();
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
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (listener != null && !items.isEmpty()) listener.onOpen(items.get(index));
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
        if (fader != null) fader.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutFor = -1;
    }

    @Override protected void onDraw(Canvas c) {
        if (items.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        float r = 22 * d;
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fill.setColor(0xFF121214);
        c.drawRect(rect, fill);
        if (previous >= 0 && fade < 1f) drawBackdrop(c, items.get(previous), 1f, w, h);
        drawBackdrop(c, items.get(index), fade, w, h);
        c.restore();
        if (isFocused()) {
            rect.inset(1.5f * d, 1.5f * d);
            c.drawRoundRect(rect, r, r, stroke);
        }
        drawText(c, items.get(index), w, h);
    }

    private void drawBackdrop(Canvas c, Trending.Item it, float alpha, int w, int h) {
        Bitmap b = it.image;
        if (b == null) return;
        float left = w * 0.38f;
        float scale = Math.max((w - left) / b.getWidth(), h / (float) b.getHeight());
        float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
        float x = w - bw, y = (h - bh) / 2f;
        image.setAlpha((int) (255 * alpha));
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        c.drawBitmap(b, 0, 0, image);
        c.restore();
        // Fade the picture's left edge into the card, so the text sits on a plain dark field.
        fill.setShader(new LinearGradient(left - 2 * d, 0, left + (w - left) * 0.5f, 0, 0xFF121214, 0x00121214, Shader.TileMode.CLAMP));
        fill.setAlpha((int) (255 * alpha));
        c.drawRect(left - 2 * d, 0, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        // Cover the part left of the gradient start in solid card colour.
        fill.setColor(0xFF121214);
        c.drawRect(0, 0, left - 2 * d, h, fill);
    }

    private void drawText(Canvas c, Trending.Item it, int w, int h) {
        float pad = 34 * d;
        int textW = (int) (w * 0.42f - pad);
        if (layoutFor != index) {
            titleLayout = StaticLayout.Builder.obtain(it.title, 0, it.title.length(), titlePaint, textW)
                    .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            CharSequence o = it.overview == null ? "" : it.overview;
            bodyLayout = StaticLayout.Builder.obtain(o, 0, o.length(), bodyPaint, textW)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.1f).build();
            layoutFor = index;
        }
        String label = it.pkg.equals(Trending.YOUTUBE) ? "YOUTUBE" : it.pkg.equals(Trending.NUVIO) ? "NUVIO" : "APPLE TV";
        float bx = pad, by = 18 * d;
        float bw = badgePaint.measureText(label) + 24 * d, bh = 26 * d;
        fill.setColor(0x33FFFFFF);
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, bh / 2, bh / 2, fill);
        c.drawText(label, bx + 12 * d, by + bh / 2 + badgePaint.getTextSize() * 0.36f, badgePaint);
        float y = by + bh + 8 * d;
        c.save();
        c.translate(pad, y);
        titleLayout.draw(c);
        c.translate(0, titleLayout.getHeight() + 2 * d);
        TextPaint meta = bodyPaint;
        int saved = meta.getColor();
        meta.setColor(0x99FFFFFF);
        c.drawText(TextUtils.ellipsize(it.meta, meta, textW, TextUtils.TruncateAt.END).toString(), 0, meta.getTextSize(), meta);
        meta.setColor(saved);
        c.translate(0, meta.getTextSize() + 8 * d);
        // The overview only when the card is tall enough for it under the title.
        if (y + titleLayout.getHeight() + 2 * d + meta.getTextSize() + 8 * d + bodyLayout.getHeight() <= h - 10 * d) bodyLayout.draw(c);
        c.restore();
        // Position dots, on the badge's row.
        float dx = bx + bw + 16 * d, dy = by + bh / 2;
        for (int i = 0; i < items.size(); i++) {
            fill.setColor(i == index ? 0xFFFFFFFF : 0x55FFFFFF);
            c.drawCircle(dx + 3 * d, dy, 3 * d, fill);
            dx += 12 * d;
        }
    }
}
