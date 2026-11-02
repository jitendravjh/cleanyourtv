package in.jitendravjh.cleanyourtv;

/** Physical left/right boundaries for a grid whose first column follows its reading direction. */
final class GridNavigation {
    private GridNavigation() { }

    static boolean atHorizontalEdge(int position, int count, int columns, boolean rtl, boolean left) {
        boolean towardStart = left != rtl;
        if (towardStart) return position % columns == 0;
        return position % columns == columns - 1 || position == count - 1;
    }

    /**
     * Where focus goes from a row's end in reading order: forward to the next row's first tile, back to the previous row's last one, or -1 from the very first or last tile.
     * The first row is the dock, whose first dockFilled positions hold apps (any after them are empty slots), so the grid starts at position columns.
     */
    static int wrapTarget(int position, int count, int columns, int dockFilled, boolean forward) {
        if (forward) {
            int next = position < columns ? columns : position + 1;
            return next < count ? next : -1;
        }
        if (position == columns) return dockFilled > 0 ? dockFilled - 1 : -1;
        return position > 0 ? position - 1 : -1;
    }
}
