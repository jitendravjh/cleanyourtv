package in.jitendravjh.cleanyourtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    /** config frost: the blurred copy of the picture that the dock band and the pills draw; null when off. */
    private final Frost frost;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = bitmapConfig(config);
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = dimColor(config);
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frost = config.frost && config.wallpaperEnabled && !files.isEmpty() ? new Frost(image) : null;
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Picture picture = decode(first);
            if (picture != null) {
                show(picture);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Picture picture = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (picture != null) picture.bitmap.recycle();
                    return;
                }
                if (picture == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(picture);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(context.getResources().getFont(R.font.google_sans_flex_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** wallpaper_true_color's colour depth. */
    static Bitmap.Config bitmapConfig(Config config) {
        return config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
    }

    /** wallpaper_dim as an ARGB black, or 0 for none. */
    static int dimColor(Config config) {
        return config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
    }

    /** The picture Home shows now: the one the rotation last chose, else the collection's first; null for none. */
    static File current(Context context, Config config) {
        if (!config.wallpaperEnabled || config.wallpapers.isEmpty()) return null;
        String shown = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("image", "");
        File file = WallpaperRotation.find(config.wallpapers, shown);
        return file != null ? file : config.wallpapers.get(0);
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** The frosted glass over this player's pictures, or null when frost is off or there is no wallpaper. */
    Frost frost() {
        return frost;
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Picture picture) {
        Bitmap old = current;
        current = picture.bitmap;
        image.setImageBitmap(picture.bitmap);
        if (frost != null) frost.set(picture.blurred);
        if (old != null && old != picture.bitmap) image.postDelayed(old::recycle, 500);
    }

    /** A decoded picture and, with frost on, its blurred copy. */
    private static final class Picture {
        final Bitmap bitmap;
        final Bitmap blurred;

        Picture(Bitmap bitmap, Bitmap blurred) {
            this.bitmap = bitmap;
            this.blurred = blurred;
        }
    }

    /** The blurred copy is made while the picture is still a software bitmap, before it moves to the GPU. */
    private Picture decode(File file) {
        Bitmap decoded = decodeInMemory(file, width, height, bitmapConfig, dimColor);
        if (decoded == null) return null;
        Bitmap blurred = frost != null ? Frost.blur(decoded) : null;
        return new Picture(onGpu(decoded), blurred);
    }

    /**
     * A picture sized to cover a screenW x screenH screen, in config's colour depth with dimColor painted over it, as a hardware bitmap when the TV allows one, or null when it does not decode.
     * Home and the clock screensaver share it.
     */
    static Bitmap decode(File file, int screenW, int screenH, Bitmap.Config config, int dimColor) {
        Bitmap decoded = decodeInMemory(file, screenW, screenH, config, dimColor);
        return decoded == null ? null : onGpu(decoded);
    }

    /** decode() up to its move to the GPU: a software bitmap, which can still be read pixel by pixel. */
    private static Bitmap decodeInMemory(File file, int screenW, int screenH, Bitmap.Config config, int dimColor) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = config;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            return decoded;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /**
     * One copy in GPU memory instead of a software bitmap plus its texture: the decode stays off the UI thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
     * Keeps the software bitmap when the TV gives no hardware copy.
     */
    private static Bitmap onGpu(Bitmap decoded) {
        try {
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return decoded;
        }
    }
}
