package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * The keymap screen's line drawing of the remote (RemoteModel), lying down with its top to the left, and a cursor
 * the D-pad moves from button to button. One focusable view: a direction with no button that way returns false,
 * so focus leaves for the panel below. Above the remote, a callout names the selected button and its key, like a
 * label on a technical drawing.
 *
 * States: idle, an outline; selected, a bone fill with a 2dp ring 4dp outside (8dp on its first step, as the
 * tiles); remapped, a small amber square beside it; broken (its firmware handler is disabled and nothing is
 * mapped), a dashed outline. Glyphs stay upright, so digits and labels read as on the real remote.
 */
final class RemoteView extends View {
    interface Listener {
        void onSelect(RemoteModel.Button button);
        void onOk(RemoteModel.Button button);
    }

    /** Muted stand-ins for the colour keys: red stays the trending card's alone. */
    private static final int[] COLOUR_STRIPS = {0xFF7A4A45, 0xFF4F6B4A, 0xFF7D7048, 0xFF465A73};
    private static final int CALLOUT_DP = 38;

    private final float d;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leader = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text;
    private final TextPaint pillText;
    private final TextPaint callout;
    private final Path path = new Path();
    private final RectF r = new RectF();

    private RemoteModel.Button selected = RemoteModel.byId("input");
    private Listener listener;
    private Set<String> broken = Collections.emptySet();
    private Set<String> remapped = Collections.emptySet();
    private Map<String, String> pillLabels = Collections.emptyMap();
    private String calloutText = "";

    /** Remote widths to pixels, and the drawing's top left corner. */
    private float s, ox, oy;

    private float ringGap = 4;
    private final Runnable snap = () -> {
        ringGap = 4;
        invalidate();
    };
    /** Press to find: the found button blinks twice, a step on and a step off. */
    private int flashSteps;
    private final Runnable flash = new Runnable() {
        @Override public void run() {
            flashSteps--;
            invalidate();
            if (flashSteps > 0) postDelayed(this, Term.STEP_MS * 2);
        }
    };

    RemoteView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * d);
        outline.setColor(Term.TEXT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(d);
        line.setColor(Term.MARK);
        dashed.set(line);
        selLine.set(line);
        selLine.setColor(Term.TEXT);
        leader.set(line);
        leader.setColor(Term.MUTED);
        dashed.setPathEffect(new DashPathEffect(new float[]{3 * d, 3 * d}, 0));
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(1.5f * d);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        ring.setColor(Term.TEXT);
        text = Term.monoPaint(c, 10, Term.MUTED);
        text.setTextAlign(Paint.Align.CENTER);
        pillText = Term.monoPaint(c, 7, Term.MUTED);
        pillText.setLetterSpacing(0.08f);
        pillText.setTextAlign(Paint.Align.CENTER);
        callout = Term.monoPaint(c, 10, Term.TEXT);
        callout.setLetterSpacing(0.08f);
        setFocusable(true);
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    RemoteModel.Button selected() {
        return selected;
    }

    void select(RemoteModel.Button b) {
        if (b == null || b == selected) return;
        selected = b;
        if (listener != null) listener.onSelect(b);
        invalidate();
    }

    /** A real button was pressed: move to it and blink it twice. */
    void find(RemoteModel.Button b) {
        select(b);
        removeCallbacks(flash);
        flashSteps = Term.motion() ? 4 : 0;
        if (flashSteps > 0) postDelayed(flash, Term.STEP_MS * 2);
        invalidate();
    }

    void setStates(Set<String> broken, Set<String> remapped, Map<String, String> pillLabels, String calloutText) {
        this.broken = broken;
        this.remapped = remapped;
        this.pillLabels = pillLabels;
        this.calloutText = calloutText;
        invalidate();
    }

    void finishAnimation() {
        removeCallbacks(flash);
        flashSteps = 0;
        if (ringGap != 4) {
            removeCallbacks(snap);
            ringGap = 4;
        }
        invalidate();
    }

    // ---- keys ------------------------------------------------------------------------------------

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        RemoteModel.Dir dir = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = RemoteModel.Dir.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = RemoteModel.Dir.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = RemoteModel.Dir.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = RemoteModel.Dir.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0 && listener != null) listener.onOk(selected);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
        RemoteModel.Button next = RemoteModel.next(selected, dir);
        if (next == null) return false;
        select(next);
        snapRing();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onOk(selected);
        return true;
    }

    @Override protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (gained) snapRing();
        else ringGap = 4;
        invalidate();
    }

    private void snapRing() {
        removeCallbacks(snap);
        ringGap = Term.motion() ? 8 : 4;
        if (ringGap != 4) postDelayed(snap, Term.STEP_MS);
    }

    // ---- geometry --------------------------------------------------------------------------------

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float top = CALLOUT_DP * d, pad = 12 * d;
        s = Math.min((w - 2 * pad) / RemoteModel.LENGTH, h - top - pad);
        ox = (w - s * RemoteModel.LENGTH) / 2f;
        oy = top + (h - top - s) / 2f;
    }

    private float sx(float x, float y) {
        return ox + RemoteModel.u(x, y) * s;
    }

    private float sy(float x, float y) {
        return oy + RemoteModel.v(x, y) * s;
    }

    /** A button's box on screen: lying down, its length along the remote runs across the screen. */
    private RectF box(RemoteModel.Button b, RectF out) {
        float cx = sx(b.cx, b.cy), cy = sy(b.cx, b.cy);
        out.set(cx - b.h * s / 2, cy - b.w * s / 2, cx + b.h * s / 2, cy + b.w * s / 2);
        return out;
    }

    /** The whole rocker a half belongs to: both halves' boxes together. */
    private RectF rocker(RemoteModel.Button b, RectF out) {
        box(b, out);
        float half = out.width();
        if (b.shape == RemoteModel.Shape.HALF_UP) out.right += half;
        else out.left -= half;
        return out;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas c) {
        if (s <= 0) return;
        r.set(ox, oy, ox + RemoteModel.LENGTH * s, oy + s);
        c.drawRoundRect(r, s / 2, s / 2, outline);
        for (RemoteModel.Line l : RemoteModel.lines()) {
            c.drawLine(sx(l.x1, l.y1), sy(l.x1, l.y1), sx(l.x2, l.y2), sy(l.x2, l.y2), line);
        }
        text.setColor(Term.MARK);
        for (RemoteModel.Mark m : RemoteModel.marks()) drawText(c, text, m.text, sx(m.x, m.y), sy(m.x, m.y));
        // The D-pad ring, once.
        RemoteModel.Button ok = RemoteModel.byId("ok"), up = RemoteModel.byId("up");
        float dcx = sx(ok.cx, ok.cy), dcy = sy(ok.cx, ok.cy), ro = (up.h + ok.w / 2) * s, ri = ok.w / 2 * s;
        c.drawCircle(dcx, dcy, ro, line);

        for (RemoteModel.Button b : RemoteModel.buttons()) drawButton(c, b, dcx, dcy, ro, ri);
        drawCallout(c);
    }

    private void drawButton(Canvas c, RemoteModel.Button b, float dcx, float dcy, float ro, float ri) {
        boolean sel = b == selected;
        boolean lit = sel && (isFocused() || flashSteps > 0) && flashSteps % 2 == 0;
        // Selected but not lit (focus is on the panel): a bone outline keeps the panel's button in view.
        Paint stroke = sel ? selLine : broken.contains(b.id) ? dashed : line;
        fill.setColor(Term.TEXT);
        box(b, r);
        float cx = r.centerX(), cy = r.centerY();
        switch (b.shape) {
            case ROUND:
                if (lit) c.drawCircle(cx, cy, r.width() / 2, fill);
                else c.drawCircle(cx, cy, r.width() / 2, stroke);
                if (lit && isFocused()) c.drawCircle(cx, cy, r.width() / 2 + (ringGap + 1) * d, ring);
                break;
            case ICON:
                if (lit) c.drawCircle(cx, cy, r.width() / 2 + 2 * d, fill);
                else if (sel || broken.contains(b.id)) c.drawCircle(cx, cy, r.width() / 2 + 2 * d, stroke);
                if (lit && isFocused()) c.drawCircle(cx, cy, r.width() / 2 + (ringGap + 3) * d, ring);
                break;
            case PILL: {
                float rad = Math.min(r.width(), r.height()) / 2;
                if (lit) c.drawRoundRect(r, rad, rad, fill);
                else c.drawRoundRect(r, rad, rad, stroke);
                if (lit && isFocused()) {
                    float g = (ringGap + 1) * d;
                    RectF o = new RectF(r.left - g, r.top - g, r.right + g, r.bottom + g);
                    c.drawRoundRect(o, rad + g, rad + g, ring);
                }
                break;
            }
            case HALF_UP: case HALF_DOWN: {
                RectF whole = rocker(b, new RectF());
                float rad = Math.min(whole.width(), whole.height()) / 2;
                if (b.shape == RemoteModel.Shape.HALF_UP) c.drawRoundRect(whole, rad, rad, line);
                if (lit || sel || broken.contains(b.id)) {
                    c.save();
                    path.reset();
                    path.addRoundRect(whole, rad, rad, Path.Direction.CW);
                    c.clipPath(path);
                    if (lit) c.drawRect(r, fill);
                    else c.drawRect(r, stroke);
                    c.restore();
                }
                if (lit && isFocused()) {
                    float g = (ringGap + 1) * d;
                    c.drawRect(r.left - g, r.top - g, r.right + g, r.bottom + g, ring);
                }
                break;
            }
            default: {
                // A quarter of the D-pad ring, centred on its direction.
                float angle = (float) Math.toDegrees(Math.atan2(cy - dcy, cx - dcx));
                path.reset();
                path.arcTo(new RectF(dcx - ro, dcy - ro, dcx + ro, dcy + ro), angle - 45, 90);
                path.arcTo(new RectF(dcx - ri, dcy - ri, dcx + ri, dcy + ri), angle + 45, -90);
                path.close();
                if (lit) {
                    c.drawPath(path, fill);
                    if (isFocused()) {
                        float g = (ringGap + 1) * d;
                        c.drawArc(new RectF(dcx - ro - g, dcy - ro - g, dcx + ro + g, dcy + ro + g), angle - 45, 90, false, ring);
                    }
                } else if (sel) {
                    c.drawPath(path, stroke);
                }
                break;
            }
        }
        drawGlyph(c, b, cx, cy, Math.min(r.width(), r.height()), lit);
        if (remapped.contains(b.id)) {
            fill.setColor(Term.AMBER);
            float q = 3 * d, gap = 3 * d;
            RectF bb = b.shape == RemoteModel.Shape.HALF_UP || b.shape == RemoteModel.Shape.HALF_DOWN ? r : box(b, new RectF());
            c.drawRect(bb.right + gap - q, bb.top - gap - q, bb.right + gap, bb.top - gap, fill);
        }
    }

    private void drawGlyph(Canvas c, RemoteModel.Button b, float cx, float cy, float size, boolean lit) {
        int colour = lit ? Term.BG : Term.MUTED;
        glyph.setColor(colour);
        glyph.setStyle(Paint.Style.STROKE);
        float g = size * 0.26f;
        switch (b.icon) {
            case POWER:
                c.drawArc(new RectF(cx - g, cy - g, cx + g, cy + g), -60, 300, false, glyph);
                c.drawLine(cx, cy - g * 1.25f, cx, cy - g * 0.1f, glyph);
                break;
            case REWIND:
                triangle(c, cx - g * 0.05f, cy, g * 0.7f, false);
                triangle(c, cx + g * 0.75f, cy, g * 0.7f, false);
                break;
            case FAST_FORWARD:
                triangle(c, cx - g * 0.75f, cy, g * 0.7f, true);
                triangle(c, cx + g * 0.05f, cy, g * 0.7f, true);
                break;
            case PLAY_PAUSE:
                triangle(c, cx - g * 0.6f, cy, g * 0.7f, true);
                c.drawLine(cx + g * 0.35f, cy - g * 0.6f, cx + g * 0.35f, cy + g * 0.6f, glyph);
                c.drawLine(cx + g * 0.75f, cy - g * 0.6f, cx + g * 0.75f, cy + g * 0.6f, glyph);
                break;
            case RECORD:
                glyph.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, g * 0.5f, glyph);
                break;
            case STOP:
                glyph.setStyle(Paint.Style.FILL);
                c.drawRect(cx - g * 0.45f, cy - g * 0.45f, cx + g * 0.45f, cy + g * 0.45f, glyph);
                break;
            case GEAR:
                c.drawCircle(cx, cy, g * 0.45f, glyph);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(cx + cos * g * 0.65f, cy + sin * g * 0.65f, cx + cos * g * 0.95f, cy + sin * g * 0.95f, glyph);
                }
                break;
            case MIC:
                c.drawRoundRect(new RectF(cx - g * 0.32f, cy - g, cx + g * 0.32f, cy + g * 0.2f), g * 0.32f, g * 0.32f, glyph);
                c.drawArc(new RectF(cx - g * 0.65f, cy - g * 0.5f, cx + g * 0.65f, cy + g * 0.6f), 0, 180, false, glyph);
                c.drawLine(cx, cy + g * 0.6f, cx, cy + g, glyph);
                break;
            case INPUT:
                c.drawRect(cx - g * 0.3f, cy - g * 0.75f, cx + g * 0.85f, cy + g * 0.75f, glyph);
                c.drawLine(cx - g, cy, cx + g * 0.3f, cy, glyph);
                c.drawLine(cx + g * 0.3f, cy, cx, cy - g * 0.3f, glyph);
                c.drawLine(cx + g * 0.3f, cy, cx, cy + g * 0.3f, glyph);
                break;
            case BACK:
                c.drawLine(cx - g * 0.8f, cy, cx + g * 0.8f, cy, glyph);
                c.drawLine(cx - g * 0.8f, cy, cx - g * 0.3f, cy - g * 0.5f, glyph);
                c.drawLine(cx - g * 0.8f, cy, cx - g * 0.3f, cy + g * 0.5f, glyph);
                break;
            case HOME:
                path.reset();
                path.moveTo(cx - g * 0.8f, cy - g * 0.05f);
                path.lineTo(cx, cy - g * 0.85f);
                path.lineTo(cx + g * 0.8f, cy - g * 0.05f);
                path.moveTo(cx - g * 0.55f, cy - g * 0.3f);
                path.lineTo(cx - g * 0.55f, cy + g * 0.75f);
                path.lineTo(cx + g * 0.55f, cy + g * 0.75f);
                path.lineTo(cx + g * 0.55f, cy - g * 0.3f);
                c.drawPath(path, glyph);
                break;
            case TV:
                c.drawRect(cx - g * 0.85f, cy - g * 0.6f, cx + g * 0.85f, cy + g * 0.45f, glyph);
                c.drawLine(cx - g * 0.4f, cy + g * 0.8f, cx + g * 0.4f, cy + g * 0.8f, glyph);
                break;
            case SPEAKER:
                path.reset();
                path.moveTo(cx - g * 0.9f, cy - g * 0.3f);
                path.lineTo(cx - g * 0.5f, cy - g * 0.3f);
                path.lineTo(cx, cy - g * 0.8f);
                path.lineTo(cx, cy + g * 0.8f);
                path.lineTo(cx - g * 0.5f, cy + g * 0.3f);
                path.lineTo(cx - g * 0.9f, cy + g * 0.3f);
                path.close();
                c.drawPath(path, glyph);
                c.drawArc(new RectF(cx - g * 0.5f, cy - g * 0.6f, cx + g * 0.6f, cy + g * 0.6f), -50, 100, false, glyph);
                break;
            case BOOK:
                path.reset();
                path.moveTo(cx, cy - g * 0.6f);
                path.lineTo(cx - g * 0.9f, cy - g * 0.75f);
                path.lineTo(cx - g * 0.9f, cy + g * 0.6f);
                path.lineTo(cx, cy + g * 0.75f);
                path.lineTo(cx + g * 0.9f, cy + g * 0.6f);
                path.lineTo(cx + g * 0.9f, cy - g * 0.75f);
                path.close();
                path.moveTo(cx, cy - g * 0.6f);
                path.lineTo(cx, cy + g * 0.75f);
                c.drawPath(path, glyph);
                break;
            case LEAF:
                path.reset();
                path.moveTo(cx - g * 0.8f, cy + g * 0.8f);
                path.quadTo(cx - g * 0.8f, cy - g * 0.8f, cx + g * 0.8f, cy - g * 0.8f);
                path.quadTo(cx + g * 0.8f, cy + g * 0.8f, cx - g * 0.8f, cy + g * 0.8f);
                path.moveTo(cx - g * 0.8f, cy + g * 0.8f);
                path.lineTo(cx + g * 0.2f, cy - g * 0.2f);
                c.drawPath(path, glyph);
                break;
            case TEXT:
                text.setColor(colour);
                text.setTextSize((b.text.length() > 1 && !Character.isDigit(b.text.charAt(0)) ? 7 : 10)
                        * getResources().getDisplayMetrics().scaledDensity);
                drawText(c, text, b.text, cx, cy);
                text.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
                break;
            case DOT: {
                int i = b.id.equals("red") ? 0 : b.id.equals("green") ? 1 : b.id.equals("yellow") ? 2 : 3;
                fill.setColor(lit ? Term.BG : COLOUR_STRIPS[i]);
                c.drawRect(cx - size * 0.28f, cy - 1.5f * d, cx + size * 0.28f, cy + 1.5f * d, fill);
                fill.setColor(Term.TEXT);
                break;
            }
            default:
                break;
        }
        if (b.isAppKey()) {
            String label = pillLabels.get(b.id);
            if (label != null) {
                // Along the pill, which lies across the screen's height.
                pillText.setColor(colour);
                c.save();
                c.rotate(-90, cx, cy);
                Paint.FontMetrics fm = pillText.getFontMetrics();
                CharSequence fit = android.text.TextUtils.ellipsize(label, pillText, size * 2.2f,
                        android.text.TextUtils.TruncateAt.END);
                c.drawText(fit, 0, fit.length(), cx, cy - (fm.ascent + fm.descent) / 2, pillText);
                c.restore();
            }
        }
    }

    private void triangle(Canvas c, float x, float cy, float h, boolean right) {
        path.reset();
        float w = h * 0.85f;
        if (right) {
            path.moveTo(x, cy - h);
            path.lineTo(x + w, cy);
            path.lineTo(x, cy + h);
        } else {
            path.moveTo(x, cy - h);
            path.lineTo(x - w, cy);
            path.lineTo(x, cy + h);
        }
        path.close();
        c.drawPath(path, glyph);
    }

    private static void drawText(Canvas c, TextPaint p, String t, float x, float y) {
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, x, y - (fm.ascent + fm.descent) / 2, p);
    }

    /** The selected button's name and key over the drawing, joined to it by a leader line. */
    private void drawCallout(Canvas c) {
        if (selected == null || calloutText.isEmpty()) return;
        box(selected, r);
        if (selected.shape.name().startsWith("HALF")) rocker(selected, r);
        float x = r.centerX(), top = r.top - 4 * d;
        if (selected.shape.name().startsWith("RING")) {
            RemoteModel.Button ok = RemoteModel.byId("ok"), up = RemoteModel.byId("up");
            top = sy(ok.cx, ok.cy) - (up.h + ok.w / 2) * s - 4 * d;
        }
        float base = CALLOUT_DP * d - 14 * d;
        c.drawLine(x, top, x, base + 4 * d, leader);
        float tw = callout.measureText(calloutText);
        boolean right = x + 6 * d + tw > getWidth() - 4 * d;
        float tx = right ? x - 6 * d - tw : x + 6 * d;
        c.drawLine(x, base + 4 * d, right ? x - 4 * d : x + 4 * d, base + 4 * d, leader);
        c.drawText(calloutText, tx, base + 8 * d, callout);
    }
}
