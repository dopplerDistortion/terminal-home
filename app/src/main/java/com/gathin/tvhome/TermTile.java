package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

/**
 * One app tile of the terminal home, for the dock and the apps grid: index top left, a monogram in the middle and
 * the name at the bottom. The view is {@link #OUT_DP} larger than the tile on every side, so the focus outline
 * (2dp, 4dp outside the tile) is drawn inside its own bounds and no parent has to stop clipping.
 *
 * OK opens on release; OK held past the long-press timeout calls onHold instead, and the open never comes.
 */
final class TermTile extends View {
    interface Listener {
        void onOpen(TermTile tile);
        void onHold(TermTile tile);
    }

    static final int OUT_DP = 6;

    private final float d;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final TextPaint small;
    private final TextPaint name;
    private final TextPaint monogram;
    private final float monogramFull;

    String key;
    private String index = "";
    private String tag = "";
    private String mono = "";
    private String label = "";
    private boolean held;
    private boolean longFired;
    private boolean returnPoint;
    private Listener listener;

    TermTile(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        small = Term.monoPaint(c, 9, Term.MARK);
        small.setLetterSpacing(0.08f);
        name = Term.monoPaint(c, 10, Term.MUTED);
        name.setLetterSpacing(0.08f);
        monogram = Term.displayPaint(c, 26, Term.TEXT);
        monogramFull = monogram.getTextSize();
        line.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        setClickable(true);
    }

    void bind(String key, int position, String tag, String monogramText, CharSequence appLabel) {
        this.key = key;
        index = String.format(java.util.Locale.ROOT, "%02d", position + 1);
        this.tag = tag == null ? "" : tag;
        mono = monogramText;
        label = Term.upper(appLabel);
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Up from a dock tile leaves this border behind, so the way back down is plain to see. */
    void setReturnPoint(boolean on) {
        if (returnPoint == on) return;
        returnPoint = on;
        invalidate();
    }

    CharSequence label() {
        return label;
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isOk(keyCode)) return super.onKeyDown(keyCode, event);
        if (event.getRepeatCount() == 0) {
            event.startTracking();
            longFired = false;
            setHeld(true);
        }
        return true;
    }

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (!isOk(keyCode)) return super.onKeyLongPress(keyCode, event);
        longFired = true;
        setHeld(false);
        if (listener != null) listener.onHold(this);
        return true;
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isOk(keyCode)) return super.onKeyUp(keyCode, event);
        boolean open = held && !longFired && !event.isCanceled();
        setHeld(false);
        if (open && listener != null) listener.onOpen(this);
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onOpen(this);
        return true;
    }

    private void setHeld(boolean on) {
        if (held == on) return;
        held = on;
        invalidate();
    }

    @Override protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (!gained) held = false;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float o = OUT_DP * d;
        float l = o, t = o, r = getWidth() - o, b = getHeight() - o;
        boolean focused = isFocused();
        int body = held ? Term.HELD : focused ? Term.TEXT : Term.CARD;
        fill.setColor(body);
        c.drawRect(l, t, r, b, fill);

        if (focused && !held) {
            // 2dp outline, 4dp clear of the tile: the stroke is centred on its path, so the path sits 5dp out.
            line.setStrokeWidth(2 * d);
            line.setColor(Term.TEXT);
            c.drawRect(l - 5 * d, t - 5 * d, r + 5 * d, b + 5 * d, line);
        } else if (!focused) {
            line.setStrokeWidth(d);
            line.setColor(returnPoint ? Term.TEXT : Term.RULE);
            c.drawRect(l + d / 2, t + d / 2, r - d / 2, b - d / 2, line);
        }

        boolean dark = focused || held;
        float pad = 9 * d;
        small.setColor(dark ? Term.BG : Term.MARK);
        c.drawText(index, l + pad, t + pad + small.getTextSize() * 0.8f, small);
        if (!tag.isEmpty()) {
            small.setColor(dark ? Term.BG : Term.AMBER);
            c.drawText(tag, r - pad - small.measureText(tag), t + pad + small.getTextSize() * 0.8f, small);
        }

        monogram.setColor(dark ? Term.BG : Term.TEXT);
        monogram.setTextSize(monogramFull);
        float maxW = r - l - 2 * pad;
        float mw = monogram.measureText(mono);
        if (mw > maxW) {
            monogram.setTextSize(monogramFull * maxW / mw);
            mw = maxW;
        }
        Paint.FontMetrics fm = monogram.getFontMetrics();
        float cy = (t + b) / 2f - 4 * d;
        c.drawText(mono, (l + r - mw) / 2f, cy - (fm.ascent + fm.descent) / 2f, monogram);

        name.setColor(dark ? Term.BG : Term.MUTED);
        CharSequence n = TextUtils.ellipsize(label, name, maxW, TextUtils.TruncateAt.END);
        c.drawText(n, 0, n.length(), l + pad, b - pad, name);
    }
}
