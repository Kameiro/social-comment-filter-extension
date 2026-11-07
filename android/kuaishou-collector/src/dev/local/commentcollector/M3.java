package dev.local.commentcollector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.widget.TextView;

/**
 * Material 3 design tokens built in code (no Material library in a Xposed module): colour roles — taken from the
 * device's Material You palette on Android 12+ and from an orange-seeded baseline otherwise — the type scale, the
 * shape scale and the handful of icons the UI needs.
 */
final class M3 {
    private M3() {}

    // ------------------------------------------------------------------ colour roles

    static final class Scheme {
        int primary, onPrimary, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer,
            surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest,
            onSurface, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, scrim = Color.BLACK;
        boolean dark;
    }

    static Scheme scheme(Context context, boolean dark) {
        Scheme s = new Scheme(); s.dark = dark;
        if (Build.VERSION.SDK_INT >= 31) {
            try { dynamic(context, s, dark); return s; } catch (Throwable ignored) { s = new Scheme(); s.dark = dark; }
        }
        baseline(s, dark);
        return s;
    }

    private static void dynamic(Context c, Scheme s, boolean dark) {
        int a1_100 = c.getColor(android.R.color.system_accent1_100), a1_200 = c.getColor(android.R.color.system_accent1_200),
            a1_600 = c.getColor(android.R.color.system_accent1_600), a1_700 = c.getColor(android.R.color.system_accent1_700),
            a1_800 = c.getColor(android.R.color.system_accent1_800), a1_900 = c.getColor(android.R.color.system_accent1_900),
            a2_100 = c.getColor(android.R.color.system_accent2_100), a2_700 = c.getColor(android.R.color.system_accent2_700),
            a2_800 = c.getColor(android.R.color.system_accent2_800), a2_900 = c.getColor(android.R.color.system_accent2_900),
            n1_10 = c.getColor(android.R.color.system_neutral1_10), n1_50 = c.getColor(android.R.color.system_neutral1_50),
            n1_100 = c.getColor(android.R.color.system_neutral1_100), n1_800 = c.getColor(android.R.color.system_neutral1_800),
            n1_900 = c.getColor(android.R.color.system_neutral1_900),
            n2_200 = c.getColor(android.R.color.system_neutral2_200), n2_400 = c.getColor(android.R.color.system_neutral2_400),
            n2_500 = c.getColor(android.R.color.system_neutral2_500), n2_700 = c.getColor(android.R.color.system_neutral2_700);
        if (dark) {
            s.primary = a1_200; s.onPrimary = a1_800; s.primaryContainer = a1_700; s.onPrimaryContainer = a1_100;
            s.secondaryContainer = a2_700; s.onSecondaryContainer = a2_100;
            s.surface = mix(n1_900, Color.BLACK, .35f); s.surfaceContainerLow = n1_900;
            s.surfaceContainer = mix(n1_900, n1_800, .25f); s.surfaceContainerHigh = mix(n1_900, n1_800, .5f); s.surfaceContainerHighest = mix(n1_900, n1_800, .75f);
            s.onSurface = n1_100; s.onSurfaceVariant = n2_200; s.outline = n2_400; s.outlineVariant = n2_700;
        } else {
            s.primary = a1_600; s.onPrimary = Color.WHITE; s.primaryContainer = a1_100; s.onPrimaryContainer = a1_900;
            s.secondaryContainer = a2_100; s.onSecondaryContainer = a2_900;
            s.surface = n1_10; s.surfaceContainerLow = n1_50;
            s.surfaceContainer = mix(n1_50, n1_100, .3f); s.surfaceContainerHigh = mix(n1_50, n1_100, .6f); s.surfaceContainerHighest = n1_100;
            s.onSurface = n1_900; s.onSurfaceVariant = n2_700; s.outline = n2_500; s.outlineVariant = n2_200;
        }
        errorRoles(s, dark);
    }

    /** Orange-seeded baseline used when the device has no Material You palette. */
    private static void baseline(Scheme s, boolean dark) {
        if (dark) {
            s.primary = rgb(0xFFB693); s.onPrimary = rgb(0x571E00); s.primaryContainer = rgb(0x7B2F00); s.onPrimaryContainer = rgb(0xFFDBCB);
            s.secondaryContainer = rgb(0x5B4132); s.onSecondaryContainer = rgb(0xFFDBCB);
            s.surface = rgb(0x1A120E); s.surfaceContainerLow = rgb(0x221A16); s.surfaceContainer = rgb(0x271E1A);
            s.surfaceContainerHigh = rgb(0x322824); s.surfaceContainerHighest = rgb(0x3D332F);
            s.onSurface = rgb(0xF0DFD8); s.onSurfaceVariant = rgb(0xD8C2B8); s.outline = rgb(0xA08D84); s.outlineVariant = rgb(0x52443C);
        } else {
            s.primary = rgb(0xA33E00); s.onPrimary = Color.WHITE; s.primaryContainer = rgb(0xFFDBCB); s.onPrimaryContainer = rgb(0x351000);
            s.secondaryContainer = rgb(0xF8DDD0); s.onSecondaryContainer = rgb(0x2B1608);
            s.surface = rgb(0xFFF8F5); s.surfaceContainerLow = rgb(0xFFF1EB); s.surfaceContainer = rgb(0xFBEBE4);
            s.surfaceContainerHigh = rgb(0xF5E5DE); s.surfaceContainerHighest = rgb(0xEFDFD8);
            s.onSurface = rgb(0x221A16); s.onSurfaceVariant = rgb(0x52443C); s.outline = rgb(0x85736B); s.outlineVariant = rgb(0xD8C2B8);
        }
        errorRoles(s, dark);
    }

    private static void errorRoles(Scheme s, boolean dark) {
        if (dark) { s.error = rgb(0xF2B8B5); s.onError = rgb(0x601410); s.errorContainer = rgb(0x8C1D18); s.onErrorContainer = rgb(0xF9DEDC); }
        else { s.error = rgb(0xB3261E); s.onError = Color.WHITE; s.errorContainer = rgb(0xF9DEDC); s.onErrorContainer = rgb(0x410E0B); }
    }

    private static int rgb(int hex) { return Color.rgb((hex >> 16) & 255, (hex >> 8) & 255, hex & 255); }

    static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
            Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ------------------------------------------------------------------ type scale (sp) and weights

    static final int DISPLAY_SMALL = 36, HEADLINE_SMALL = 24, TITLE_LARGE = 22, TITLE_MEDIUM = 16, BODY_LARGE = 16, BODY_MEDIUM = 14,
        BODY_SMALL = 12, LABEL_LARGE = 14, LABEL_MEDIUM = 12, LABEL_SMALL = 11;

    static Typeface medium() { return Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, 500, false) : Typeface.DEFAULT_BOLD; }

    static TextView type(TextView view, int sp, boolean emphasised, int color) {
        view.setTextSize(sp); view.setTextColor(color); view.setTypeface(emphasised ? medium() : Typeface.DEFAULT);
        view.setLineSpacing(0, sp >= 20 ? 1.1f : 1.18f);
        return view;
    }

    // ------------------------------------------------------------------ shapes

    static final int SHAPE_EXTRA_SMALL = 4, SHAPE_SMALL = 8, SHAPE_MEDIUM = 12, SHAPE_LARGE = 16, SHAPE_EXTRA_LARGE = 28;

    static GradientDrawable shape(int color, float radiusPx) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radiusPx); return d; }

    static GradientDrawable topShape(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color);
        d.setCornerRadii(new float[] {radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0}); return d;
    }

    // ------------------------------------------------------------------ icons (24dp grid, drawn in code)

    enum Icon { COLLECT, DATA, ANALYSIS, PERSON, CLOSE, CHAT }

    static final class IconDrawable extends Drawable {
        private final Icon icon; private int color; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();
        IconDrawable(Icon icon, int color) { this.icon = icon; this.color = color; }
        void setIconColor(int value) { color = value; invalidateSelf(); }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds(); float u = Math.min(b.width(), b.height()) / 24f; canvas.save(); canvas.translate(b.left + (b.width() - 24 * u) / 2f, b.top + (b.height() - 24 * u) / 2f);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * u); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            switch (icon) {
                case COLLECT -> { // download into tray
                    path.reset(); path.moveTo(12 * u, 4 * u); path.lineTo(12 * u, 15 * u); path.moveTo(7.5f * u, 10.5f * u); path.lineTo(12 * u, 15 * u); path.lineTo(16.5f * u, 10.5f * u);
                    path.moveTo(5 * u, 19 * u); path.lineTo(19 * u, 19 * u); canvas.drawPath(path, paint);
                }
                case DATA -> { // list
                    for (int i = 0; i < 3; i++) { float y = (7 + i * 5) * u; canvas.drawLine(9 * u, y, 20 * u, y, paint); canvas.drawPoint(4.5f * u, y, dot(u)); }
                }
                case ANALYSIS -> { // bar chart
                    canvas.drawLine(6 * u, 19 * u, 6 * u, 12 * u, paint); canvas.drawLine(12 * u, 19 * u, 12 * u, 5 * u, paint); canvas.drawLine(18 * u, 19 * u, 18 * u, 9 * u, paint);
                }
                case PERSON -> {
                    canvas.drawCircle(12 * u, 8 * u, 3.6f * u, paint);
                    path.reset(); path.moveTo(5 * u, 20 * u); path.cubicTo(5 * u, 15 * u, 8.5f * u, 13.5f * u, 12 * u, 13.5f * u); path.cubicTo(15.5f * u, 13.5f * u, 19 * u, 15 * u, 19 * u, 20 * u); canvas.drawPath(path, paint);
                }
                case CLOSE -> { canvas.drawLine(6 * u, 6 * u, 18 * u, 18 * u, paint); canvas.drawLine(18 * u, 6 * u, 6 * u, 18 * u, paint); }
                case CHAT -> {
                    path.reset(); path.addRoundRect(new RectF(3.5f * u, 4.5f * u, 20.5f * u, 16.5f * u), 3.5f * u, 3.5f * u, Path.Direction.CW);
                    path.moveTo(8 * u, 16.5f * u); path.lineTo(8 * u, 20.5f * u); path.lineTo(12.5f * u, 16.5f * u); canvas.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL); for (int i = 0; i < 3; i++) canvas.drawCircle((8 + i * 4) * u, 10.5f * u, 1.1f * u, paint);
                }
            }
            canvas.restore();
        }
        private Paint dot(float u) { Paint p = new Paint(paint); p.setStrokeWidth(3.2f * u); return p; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
