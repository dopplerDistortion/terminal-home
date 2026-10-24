package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.widget.TextView;

import java.util.Locale;

/**
 * The terminal home's look, from the goksen.ing design system: one dark field with a dot grid, bone text, square
 * corners, no shadows, gradients or motion. Red belongs to one control only, the trending card's open button.
 */
final class Term {
    private Term() {}

    static final int BG = 0xFF0B0B0C;
    static final int DOT = 0xFF2A2A2B;
    static final int TEXT = 0xFFE6E3DE;
    static final int MUTED = 0xFF8C8A86;
    static final int CARD = 0xFF131314;
    static final int RULE = 0xFF2A2A2B;
    static final int MARK = 0xFF5C5B58;
    static final int AMBER = 0xFFF5B400;
    static final int RED = 0xFFFF3B30;
    static final int HELD = 0xFF6B6A66;

    /** Martian Mono's light, wide instance: titles and app monograms. */
    static final String DISPLAY_AXES = "'wght' 300, 'wdth' 112.5";

    private static Typeface mono, display;

    static Typeface mono(Context c) {
        if (mono == null) mono = c.getResources().getFont(R.font.geist_mono);
        return mono;
    }

    static Typeface display(Context c) {
        if (display == null) display = c.getResources().getFont(R.font.martian_mono);
        return display;
    }

    static TextPaint monoPaint(Context c, float sp, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(mono(c));
        p.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
        p.setColor(color);
        return p;
    }

    static TextPaint displayPaint(Context c, float sp, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(display(c));
        p.setFontVariationSettings(DISPLAY_AXES);
        p.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
        p.setColor(color);
        return p;
    }

    /** A plain label in the house mono; caps are the caller's (the strings are written in capitals). */
    static TextView label(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(mono(c));
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    static String upper(CharSequence s) {
        return String.valueOf(s).toUpperCase(Locale.ROOT);
    }

    /** "YT" for YouTube, "PV" for Prime Video: two words give their initials, one word its first two letters. */
    static String monogram(CharSequence label) {
        String[] words = String.valueOf(label).trim().split("[\\s\\-_:.]+");
        StringBuilder m = new StringBuilder();
        if (words.length >= 2) {
            for (int i = 0; i < 2; i++) if (!words[i].isEmpty()) m.appendCodePoint(words[i].codePointAt(0));
        } else if (!words[0].isEmpty()) {
            m.append(words[0], 0, Math.min(2, words[0].length()));
        }
        return upper(m.length() == 0 ? "··" : m);
    }

    /** The screen's background: the dark field and a faint dot every 7dp, one repeating 7dp tile. */
    static final class DotGrid extends Drawable {
        private final Paint paint = new Paint();

        DotGrid(Context c) {
            float d = c.getResources().getDisplayMetrics().density;
            int step = Math.round(7 * d), dot = Math.max(1, Math.round(d));
            Bitmap tile = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888);
            tile.eraseColor(BG);
            Paint p = new Paint();
            p.setColor(DOT);
            new Canvas(tile).drawRect(0, 0, dot, dot, p);
            paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        @Override public void draw(Canvas c) {
            c.drawRect(getBounds(), paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
