package in.jitendravjh.cleanyourtv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.StateSet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

/**
 * Frosted glass over Home's wallpaper (config frost): a small blurred copy of the picture, made once when the picture is decoded.
 * The dock band and the top bar's pills each draw the part of it that lies behind them, so the glass costs one textured shape per frame and no live blur.
 */
final class Frost {
    /** Width of the blurred copy: about an eighth of the picture, a light blur that lets the picture's shapes show through. */
    private static final int WIDTH = 240;
    /** The box blur run over the small copy, twice each way. */
    private static final int BLUR_RADIUS = 2;

    private final ImageView picture;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] hostAt = new int[2];
    private final int[] pictureAt = new int[2];
    private Bitmap blurred;
    private Runnable changed;

    /** picture is the view that shows Home's wallpaper. */
    Frost(ImageView picture) {
        this.picture = picture;
    }

    /** Runs when a new picture's glass is in, so the views that draw it can redraw. */
    void setOnChange(Runnable changed) {
        this.changed = changed;
    }

    /** The blurred copy (blur()) of the picture now on screen; null leaves the glass out. */
    void set(Bitmap blurredPicture) {
        blurred = blurredPicture;
        paint.setShader(blurred == null ? null
                : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        if (changed != null) changed.run();
    }

    /**
     * Fills rect, in host's own coordinates, with the blurred picture as it lies behind host on the screen.
     * Screen positions, not window ones, so a popup's panel lines up too: it is a window of its own.
     * Returns false, drawing nothing, while there is no picture.
     */
    boolean draw(Canvas canvas, View host, RectF rect, float radius) {
        Drawable shown = picture.getDrawable();
        if (blurred == null || shown == null) return false;
        host.getLocationOnScreen(hostAt);
        picture.getLocationOnScreen(pictureAt);
        // The blurred copy stands in for the picture, so it goes through the picture's own placement on screen.
        matrix.setScale(shown.getIntrinsicWidth() / (float) blurred.getWidth(),
                shown.getIntrinsicHeight() / (float) blurred.getHeight());
        matrix.postConcat(picture.getImageMatrix());
        matrix.postTranslate(pictureAt[0] - hostAt[0], pictureAt[1] - hostAt[1]);
        paint.getShader().setLocalMatrix(matrix);
        canvas.drawRoundRect(rect, radius, radius, paint);
        return true;
    }

    /**
     * A small blurred copy of a software bitmap, or null when it cannot be made.
     * Each halving with filtering averages 2x2 pixels, and a box blur over the small copy smooths what is left.
     */
    static Bitmap blur(Bitmap source) {
        try {
            Bitmap small = source;
            int w = source.getWidth(), h = source.getHeight();
            while (w / 2 >= WIDTH) {
                w /= 2;
                h = Math.max(1, h / 2);
                Bitmap half = Bitmap.createScaledBitmap(small, w, h, true);
                if (small != source) small.recycle();
                small = half;
            }
            int[] pixels = new int[w * h];
            small.getPixels(pixels, 0, w, 0, 0, w, h);
            if (small != source) small.recycle();
            int[] scratch = new int[pixels.length];
            for (int pass = 0; pass < 2; pass++) {
                boxBlur(pixels, scratch, w, h, true);
                boxBlur(scratch, pixels, w, h, false);
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** One pass of the box blur along the rows or down the columns, edges clamped, every pixel opaque. */
    static void boxBlur(int[] in, int[] out, int w, int h, boolean rows) {
        int lines = rows ? h : w, length = rows ? w : h, span = 2 * BLUR_RADIUS + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int red = 0, green = 0, blue = 0;
                for (int k = -BLUR_RADIUS; k <= BLUR_RADIUS; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int pixel = in[rows ? line * w + j : j * w + line];
                    red += (pixel >> 16) & 0xFF;
                    green += (pixel >> 8) & 0xFF;
                    blue += pixel & 0xFF;
                }
                out[rows ? line * w + i : i * w + line] =
                        0xFF000000 | (red / span) << 16 | (green / span) << 8 | blue / span;
            }
        }
    }

    /**
     * A background of frosted glass under the dock band's tint and edge, for the top bar's pills and the popups' panels.
     * The view's own background still draws while the view is focused or activated (Reorder during reordering), and wherever there is no picture to frost.
     */
    static final class Glass extends Drawable {
        /** As a corner radius: half the height, a pill's round ends. */
        static final float ROUND = -1f;
        private static final int[] FOCUSED = {android.R.attr.state_focused};
        private static final int[] ACTIVATED = {android.R.attr.state_activated};
        private final Frost frost;
        private final Drawable own;
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float halfStroke;
        private final float cornerRadius;
        private boolean ownLook;

        /** cornerRadius in px, or ROUND; an edgeColor of 0 draws no edge. */
        Glass(Frost frost, Drawable own, float cornerRadius, int tintColor, int edgeColor, float strokeWidth) {
            this.frost = frost;
            this.own = own;
            this.cornerRadius = cornerRadius;
            tint.setColor(tintColor);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(strokeWidth);
            edge.setColor(edgeColor);
            halfStroke = strokeWidth / 2f;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Object host = getCallback();
            rect.set(getBounds());
            float radius = cornerRadius == ROUND ? rect.height() / 2f : cornerRadius;
            if (ownLook || !(host instanceof View) || !frost.draw(canvas, (View) host, rect, radius)) {
                own.draw(canvas);
                return;
            }
            canvas.drawRoundRect(rect, radius, radius, tint);
            if (edge.getAlpha() == 0) return;   // dock_edge off
            rect.inset(halfStroke, halfStroke);
            canvas.drawRoundRect(rect, radius - halfStroke, radius - halfStroke, edge);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            own.setBounds(bounds);
        }

        /** The pill's own shape, which the focused pill's shadow follows. */
        @Override
        public void getOutline(@NonNull Outline outline) {
            own.getOutline(outline);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            boolean ownChanged = own.setState(state);
            boolean useOwn = StateSet.stateSetMatches(FOCUSED, state) || StateSet.stateSetMatches(ACTIVATED, state);
            if (useOwn == ownLook) return ownChanged;
            ownLook = useOwn;
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            own.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            own.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
