package in.jitendravjh.cleanyourtv;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class FrostTest {
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    private static int grey(int level) {
        return 0xFF000000 | level << 16 | level << 8 | level;
    }

    @Test public void aBrightPixelSpreadsEvenlyAlongItsRow() {
        int[] in = {BLACK, BLACK, BLACK, WHITE, BLACK, BLACK, BLACK};
        int[] out = new int[in.length];
        Frost.boxBlur(in, out, 7, 1, true);
        assertArrayEquals(new int[]{BLACK, grey(51), grey(51), grey(51), grey(51), grey(51), BLACK}, out);
    }

    @Test public void columnsBlurDownTheColumnNotAlongTheRow() {
        // A 2x5 picture with a bright pixel in the left column's middle: the right column stays black.
        int[] in = {BLACK, BLACK, BLACK, BLACK, WHITE, BLACK, BLACK, BLACK, BLACK, BLACK};
        int[] out = new int[in.length];
        Frost.boxBlur(in, out, 2, 5, false);
        int g = grey(51);
        assertArrayEquals(new int[]{g, BLACK, g, BLACK, g, BLACK, g, BLACK, g, BLACK}, out);
    }

    @Test public void theEdgePixelCountsForTheSamplesPastTheEdge() {
        int[] in = {WHITE, BLACK, BLACK, BLACK, BLACK};
        int[] out = new int[in.length];
        Frost.boxBlur(in, out, 5, 1, true);
        assertArrayEquals(new int[]{grey(153), grey(102), grey(51), BLACK, BLACK}, out);
    }
}
