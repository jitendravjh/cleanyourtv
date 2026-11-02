package in.jitendravjh.cleanyourtv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;
    /** dock_band false: only the gap around the dock row stays, the band itself is not drawn. */
    private final boolean drawBand;
    /** config frost: the blurred wallpaper under the band's tint; null for none. */
    private final Frost frost;
    /** dock_edge: the thin light line around the band. */
    private final boolean drawEdge;
    /** How far the band reaches above and below the dock tiles. */
    static final float BAND_PAD_DP = 14;
    /** The plain band's 1dp edge, a little brighter on a light tint; frost gives the top bar's pills the same one. */
    static int edgeColor(boolean light) {
        return light ? 0x38FFFFFF : 0x1FFFFFFF;
    }

    /** The plain band's tint at dock_opacity percent, white or near-black; frost gives the top bar's pills the same. */
    static int bandColor(int opacity, boolean light) {
        return (Math.round(opacity * 2.55f) << 24) | (light ? 0xFFFFFF : 0x141416);
    }

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     * @param drawBand    dock_band: false keeps the dock row's spacing but draws no band.
     * @param frost       frost: the blurred wallpaper drawn under the band, or null.
     * @param lightTint   dock_tint light: the plain band is tinted white instead of near-black.
     * @param drawEdge    dock_edge: the thin light line around the band.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean drawBand, Frost frost, boolean lightTint, boolean drawEdge) {
        this.drawBand = drawBand;
        this.frost = frost;
        this.drawEdge = drawEdge;
        this.dockSize = dockSize;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        band.setColor(translucent ? 0x8C121014 : bandColor(opacity, lightTint));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : edgeColor(lightTint));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!drawBand) return;
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = BAND_PAD_DP * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat. Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        // 22dp at the sides (the grid's 10dp padding makes room): a focused first or last tile grows about 7dp
        // outwards and still keeps about the band's top margin from its rounded end.
        float sidePad = 22 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -pad);
        rect.bottom += namesRoom;
        float radius = 26 * density;
        if (lightGlass && (rect.top != shaderTop || rect.bottom != shaderBottom)) {
            // The pills' glass (pill_bg: white, 18% fading to 8%), a little fainter over this much larger area.
            band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x0DFFFFFF,
                    Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        if (frost != null) frost.draw(c, parent, rect, radius);
        c.drawRoundRect(rect, radius, radius, band);
        if (drawEdge) c.drawRoundRect(rect, radius, radius, edge);
    }
}
