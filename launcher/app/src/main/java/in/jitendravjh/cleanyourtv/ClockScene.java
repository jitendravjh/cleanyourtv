package in.jitendravjh.cleanyourtv;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Random;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * screensaver_wallpaper puts Home's wallpaper behind it instead of the glow, and screensaver_centered keeps the
 * clock in the middle. Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** screensaver_centered: how far above the middle the clock sits, as a share of the screen's height. */
    private static final float CENTRE_RAISE = 0.08f;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final View ambient;
    private final GradientDrawable glow;
    /** screensaver_centered: the time and date stay in the middle of the screen instead of drifting. */
    private final boolean centered;
    private int colorIndex;
    private int glowColor;
    /** The screensaver or idle clock has closed: stop() ends both for good. */
    private boolean stopped;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!centered) moveClock();
            if (ambient.getVisibility() == View.VISIBLE) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        Context context = root.getContext();
        Config config = Config.load(context);
        centered = config.screensaverCentered;
        if (centered) centre();
        if (config.screensaverWallpaper) showWallpaper(root, context, config);
    }

    /** The time row and the date under it, each centred, in the middle of the screen. */
    private void centre() {
        FrameLayout.LayoutParams place = (FrameLayout.LayoutParams) content.getLayoutParams();
        place.gravity = Gravity.CENTER;
        content.setLayoutParams(place);
        ((LinearLayout) content).setGravity(Gravity.CENTER_HORIZONTAL);
        View date = content.findViewById(R.id.dreamDate);
        ViewGroup.MarginLayoutParams indent = (ViewGroup.MarginLayoutParams) date.getLayoutParams();
        indent.setMarginStart(0);
        date.setLayoutParams(indent);
    }

    /**
     * Home's current wallpaper behind the clock, decoded off the UI thread the way Home decodes it.
     * The glow goes once the picture is in, and a picture that does not decode leaves the glow as it was.
     */
    private void showWallpaper(View root, Context context, Config config) {
        File picture = WallpaperPlayer.current(context, config);
        if (picture == null) return;
        ImageView image = root.findViewById(R.id.dreamWallpaper);
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        Bitmap.Config depth = WallpaperPlayer.bitmapConfig(config);
        int dim = WallpaperPlayer.dimColor(config);
        new Thread(() -> {
            Bitmap bitmap = WallpaperPlayer.decode(picture, screen.widthPixels, screen.heightPixels, depth, dim);
            if (bitmap == null) return;
            handler.post(() -> {
                if (stopped) return;   // the screensaver closed while the picture decoded
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
                ambient.setVisibility(View.GONE);
                // Thin white type over a photo needs a soft shade under it to stay readable on its light parts.
                shade(content, 10f * screen.density);
            });
        }, "screensaver-wallpaper").start();
    }

    private static void shade(View view, float radius) {
        if (view instanceof TextView) {
            ((TextView) view).setShadowLayer(radius, 0f, radius / 4f, 0x73000000);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) shade(group.getChildAt(i), radius);
        }
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
    }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        if (centered) {
            // A little above the middle, where a block of type looks centred; dead centre reads as low.
            content.setTranslationY(-((View) content.getParent()).getHeight() * CENTRE_RAISE);
            return;
        }
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
