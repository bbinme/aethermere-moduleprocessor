package com.dnd.processor.converters;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java Hough line transform for detecting straight lines in a binary edge image.
 *
 * The accumulator is indexed by (theta, rho) where:
 *   rho = x * cos(theta) + y * sin(theta)
 *
 * Theta is quantised to 1° bins (0–179°). Rho ranges from -diagonal to +diagonal.
 */
public class HoughTransform {

    /** A detected line in Hough space. */
    public record Line(double theta, double rho, int votes) {}

    private static final int THETA_BINS = 180;

    // Pre-computed sin/cos tables for 0°–179°
    private final double[] cosTable = new double[THETA_BINS];
    private final double[] sinTable = new double[THETA_BINS];

    public HoughTransform() {
        for (int t = 0; t < THETA_BINS; t++) {
            double rad = Math.toRadians(t);
            cosTable[t] = Math.cos(rad);
            sinTable[t] = Math.sin(rad);
        }
    }

    /**
     * Runs the Hough transform on a binary edge image.
     *
     * @param edges  edge pixels (true = edge), row-major [y * w + x]
     * @param w      image width
     * @param h      image height
     * @return the raw accumulator, indexed [theta][rho + rhoOffset]
     */
    public int[][] accumulate(boolean[] edges, int w, int h) {
        int diagonal = (int) Math.ceil(Math.sqrt((double) w * w + (double) h * h));
        int rhoRange = 2 * diagonal + 1;
        int rhoOffset = diagonal;

        int[][] acc = new int[THETA_BINS][rhoRange];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!edges[y * w + x]) continue;
                for (int t = 0; t < THETA_BINS; t++) {
                    int rho = (int) Math.round(x * cosTable[t] + y * sinTable[t]);
                    acc[t][rho + rhoOffset]++;
                }
            }
        }
        return acc;
    }

    /**
     * Extracts lines from the accumulator that exceed a vote threshold.
     *
     * @param acc        accumulator from {@link #accumulate}
     * @param minVotes   minimum votes to qualify as a line
     * @param rhoOffset  diagonal value (half of rho range - 1)
     * @return detected lines
     */
    public List<Line> extractLines(int[][] acc, int minVotes, int rhoOffset) {
        List<Line> lines = new ArrayList<>();
        for (int t = 0; t < THETA_BINS; t++) {
            for (int ri = 0; ri < acc[t].length; ri++) {
                if (acc[t][ri] >= minVotes) {
                    double rho = ri - rhoOffset;
                    lines.add(new Line(t, rho, acc[t][ri]));
                }
            }
        }
        return lines;
    }

    /**
     * Builds a 180-bin angle histogram by summing votes across all rho values
     * for each theta bin. Normalised to [0, 1].
     */
    public double[] angleHistogram(int[][] acc) {
        double[] hist = new double[THETA_BINS];
        double max = 0;
        for (int t = 0; t < THETA_BINS; t++) {
            long sum = 0;
            for (int v : acc[t]) sum += v;
            hist[t] = sum;
            if (sum > max) max = sum;
        }
        if (max > 0) {
            for (int t = 0; t < THETA_BINS; t++) hist[t] /= max;
        }
        return hist;
    }

    /** Returns ceil(sqrt(w^2 + h^2)) — the rho offset for the accumulator. */
    public static int diagonal(int w, int h) {
        return (int) Math.ceil(Math.sqrt((double) w * w + (double) h * h));
    }
}
