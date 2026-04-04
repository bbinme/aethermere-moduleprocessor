package com.dnd.processor.converters;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pure-Java implementation of the Canny edge detection algorithm.
 *
 * Pipeline:
 *   1. Grayscale conversion        (luminance-weighted RGB → single channel)
 *   2. Gaussian blur (5×5)         (suppress noise before gradient calculation)
 *   3. Sobel gradient              (magnitude + quantised direction at every pixel)
 *   4. Non-maximum suppression     (thin edges to 1-pixel width along gradient)
 *   5. Hysteresis thresholding     (keep strong edges; keep weak edges only when
 *                                   connected to a strong edge via 8-connectivity)
 *
 * Typical thresholds for scanned document pages at 150 DPI:
 *   lowThreshold  ≈  20–40
 *   highThreshold ≈  60–120
 */
public class CannyEdgeDetector {

    // ── 5×5 Gaussian kernel (σ ≈ 1.4) ───────────────────────────────────────
    private static final float[] GAUSS5 = {
         2,  4,  5,  4,  2,
         4,  9, 12,  9,  4,
         5, 12, 15, 12,  5,
         4,  9, 12,  9,  4,
         2,  4,  5,  4,  2
    };
    private static final float GAUSS5_SUM = 159f;

    // Gradient direction buckets (0–3 map to 0°, 45°, 90°, 135°)
    private static final int DIR_H  = 0;   // horizontal  (0° / 180°)
    private static final int DIR_D1 = 1;   // diagonal    (45° / 225°)
    private static final int DIR_V  = 2;   // vertical    (90° / 270°)
    private static final int DIR_D2 = 3;   // anti-diag   (135° / 315°)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs the full Canny pipeline on {@code src} and returns a binary edge image
     * (white edges on black background).
     *
     * @param src           input image (any BufferedImage type)
     * @param lowThreshold  weak-edge lower bound  (suggested: 20–40 for documents)
     * @param highThreshold strong-edge lower bound (suggested: 3× low threshold)
     */
    public BufferedImage detect(BufferedImage src, float lowThreshold, float highThreshold) {
        int w = src.getWidth();
        int h = src.getHeight();

        int[]   gray     = toGrayscale(src, w, h);
        int[]   blurred  = gaussianBlur(gray, w, h);
        float[] mag      = new float[w * h];
        int[]   dir      = new int[w * h];
        sobelGradient(blurred, w, h, mag, dir);
        boolean[] edge   = nonMaxSuppression(mag, dir, w, h);
        hysteresis(edge, mag, w, h, lowThreshold, highThreshold);
        return renderEdgeImage(edge, w, h);
    }

    // ── Step 1: grayscale ─────────────────────────────────────────────────────

    private int[] toGrayscale(BufferedImage src, int w, int h) {
        int[] gray = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb        & 0xFF;
                // Standard luminance weights
                gray[y * w + x] = (int)(0.299f * r + 0.587f * g + 0.114f * b);
            }
        }
        return gray;
    }

    // ── Step 2: Gaussian blur ─────────────────────────────────────────────────

    private int[] gaussianBlur(int[] gray, int w, int h) {
        int[] out = new int[w * h];
        int half = 2; // kernel radius for 5×5

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int ky = -half; ky <= half; ky++) {
                    for (int kx = -half; kx <= half; kx++) {
                        int sy = clamp(y + ky, 0, h - 1);
                        int sx = clamp(x + kx, 0, w - 1);
                        float k = GAUSS5[(ky + half) * 5 + (kx + half)];
                        sum += k * gray[sy * w + sx];
                    }
                }
                out[y * w + x] = Math.round(sum / GAUSS5_SUM);
            }
        }
        return out;
    }

    // ── Step 3: Sobel gradient ────────────────────────────────────────────────

    private void sobelGradient(int[] blurred, int w, int h, float[] mag, int[] dir) {
        // Sobel kernels
        // Gx: [-1 0 1; -2 0 2; -1 0 1]   Gy: [1 2 1; 0 0 0; -1 -2 -1]
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int tl = blurred[(y-1)*w+(x-1)], tc = blurred[(y-1)*w+x], tr = blurred[(y-1)*w+(x+1)];
                int ml = blurred[    y*w+(x-1)], mr = blurred[    y*w+(x+1)];
                int bl = blurred[(y+1)*w+(x-1)], bc = blurred[(y+1)*w+x], br = blurred[(y+1)*w+(x+1)];

                int gx = -tl + tr - 2*ml + 2*mr - bl + br;
                int gy =  tl + 2*tc + tr - bl - 2*bc - br;

                mag[y*w+x] = (float) Math.sqrt(gx*gx + gy*gy);

                // Quantise angle into 4 directions
                double angle = Math.toDegrees(Math.atan2(gy, gx));
                if (angle < 0) angle += 180;
                if      (angle <  22.5 || angle >= 157.5) dir[y*w+x] = DIR_H;
                else if (angle <  67.5)                   dir[y*w+x] = DIR_D1;
                else if (angle < 112.5)                   dir[y*w+x] = DIR_V;
                else                                      dir[y*w+x] = DIR_D2;
            }
        }
    }

    // ── Step 4: non-maximum suppression ──────────────────────────────────────

    private boolean[] nonMaxSuppression(float[] mag, int[] dir, int w, int h) {
        boolean[] keep = new boolean[w * h];

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float m = mag[i];
                if (m == 0) continue;

                float n1, n2;
                switch (dir[i]) {
                    case DIR_H  -> { n1 = mag[i - 1];     n2 = mag[i + 1]; }
                    case DIR_D1 -> { n1 = mag[i - w + 1]; n2 = mag[i + w - 1]; }
                    case DIR_V  -> { n1 = mag[i - w];     n2 = mag[i + w]; }
                    default     -> { n1 = mag[i - w - 1]; n2 = mag[i + w + 1]; }
                }
                keep[i] = (m >= n1 && m >= n2);
            }
        }
        return keep;
    }

    // ── Step 5: hysteresis thresholding ──────────────────────────────────────

    private void hysteresis(boolean[] edge, float[] mag, int w, int h,
                            float low, float high) {
        // Two-pass: mark strong pixels, then flood-fill through weak pixels
        boolean[] strong = new boolean[w * h];
        boolean[] weak   = new boolean[w * h];

        for (int i = 0; i < edge.length; i++) {
            if (!edge[i]) continue;
            if      (mag[i] >= high) strong[i] = true;
            else if (mag[i] >= low)  weak[i]   = true;
        }

        // Flood-fill from every strong pixel via 8-connected weak neighbours
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < strong.length; i++) {
            if (strong[i]) queue.push(i);
        }

        while (!queue.isEmpty()) {
            int i = queue.pop();
            int x = i % w;
            int y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                    int ni = ny * w + nx;
                    if (weak[ni]) {
                        weak[ni]   = false;
                        strong[ni] = true;
                        queue.push(ni);
                    }
                }
            }
        }

        // Write back: edge pixel iff strong
        System.arraycopy(strong, 0, edge, 0, edge.length);
    }

    // ── Output rendering ──────────────────────────────────────────────────────

    private BufferedImage renderEdgeImage(boolean[] edge, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = edge[y * w + x] ? 0xFFFFFF : 0x000000;
                out.setRGB(x, y, v);
            }
        }
        return out;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
