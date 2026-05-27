package com.solidus.analytics.util;

/**
 * GiniCoefficient - Wealth inequality measurement utility.
 *
 * <p>The Gini coefficient is a statistical measure of distribution developed
 * by Italian statistician Corrado Gini in 1912. It is widely used in economics
 * to measure wealth or income inequality within a population.</p>
 *
 * <h3>Interpretation:</h3>
 * <ul>
 *   <li>0.0 = Perfect equality (every player has the exact same balance)</li>
 *   <li>1.0 = Perfect inequality (one player holds all wealth, everyone else has zero)</li>
 *   <li>&lt;0.3 = Relatively equal distribution (healthy for a game economy)</li>
 *   <li>0.3-0.5 = Moderate inequality (typical for mature economies)</li>
 *   <li>&gt;0.5 = High inequality (may indicate inflation or hoarding problems)</li>
 * </ul>
 *
 * <h3>Algorithm:</h3>
 * Uses the absolute differences method (O(n^2)):
 * <pre>
 *   G = sum(|x_i - x_j|) / (2 * n * sum(x_i))
 * </pre>
 * where x_i are individual balances sorted in ascending order.
 *
 * <p>For large player counts (>1000), an optimized O(n log n) variant based on
 * the Lorenz curve can be used instead. The current implementation prioritizes
 * clarity and correctness; the optimized version is planned for a future release.</p>
 *
 * <h3>Thread Safety:</h3>
 * This is a pure utility class with no state. All methods are static and thread-safe.
 */
public final class GiniCoefficient {

    private GiniCoefficient() {
        // Utility class — no instantiation
    }

    /**
     * Calculates the Gini coefficient from an array of balances (in cents).
     *
     * <p>The input array is sorted internally (ascending). The method does NOT
     * modify the caller's array — a defensive copy is made first.</p>
     *
     * <p>Edge cases:</p>
     * <ul>
     *   <li>Empty array → returns 0.0 (undefined, but 0 is a safe default)</li>
     *   <li>Single element → returns 0.0 (no inequality with one person)</li>
     *   <li>All zeros → returns 0.0 (no inequality when everyone has nothing)</li>
     *   <li>Total sum is zero → returns 0.0 (avoids division by zero)</li>
     * </ul>
     *
     * @param balances Array of balance values in cents (will NOT be modified)
     * @return The Gini coefficient between 0.0 (equal) and 1.0 (unequal)
     */
    public static double calculate(long[] balances) {
        if (balances == null || balances.length <= 1) {
            return 0.0;
        }

        // Defensive copy — sort without modifying the caller's array
        long[] sorted = new long[balances.length];
        System.arraycopy(balances, 0, sorted, 0, balances.length);
        java.util.Arrays.sort(sorted);

        // Calculate total sum
        long sum = 0;
        for (long b : sorted) {
            sum += b;
        }

        // Edge case: all balances are zero → no inequality
        if (sum == 0) {
            return 0.0;
        }

        // Calculate sum of absolute differences using the O(n^2) method
        // For economies with <1000 players this is fast enough (<1ms)
        long sumOfAbsoluteDifferences = 0;
        for (int i = 0; i < sorted.length; i++) {
            for (int j = 0; j < sorted.length; j++) {
                sumOfAbsoluteDifferences += Math.abs(sorted[i] - sorted[j]);
            }
        }

        // Gini = sum(|x_i - x_j|) / (2 * n * sum(x_i))
        double n = sorted.length;
        return sumOfAbsoluteDifferences / (2.0 * n * sum);
    }

    /**
     * Calculates the Gini coefficient using the optimized Lorenz curve method.
     *
     * <p>This O(n log n) algorithm is preferred for large datasets (>1000 players).
     * It computes the area under the Lorenz curve using cumulative sums and
     * derives the Gini coefficient as (A) / (A + B) where A is the area between
     * the line of equality and the Lorenz curve.</p>
     *
     * <p>Formula:</p>
     * <pre>
     *   G = 1 - (2 / (n - 1)) * (n - (sum(i * x_i)) / sum(x_i))
     * </pre>
     * where i is the 0-based index after sorting ascending.
     *
     * @param balances Array of balance values in cents (will NOT be modified)
     * @return The Gini coefficient between 0.0 (equal) and 1.0 (unequal)
     */
    public static double calculateOptimized(long[] balances) {
        if (balances == null || balances.length <= 1) {
            return 0.0;
        }

        long[] sorted = new long[balances.length];
        System.arraycopy(balances, 0, sorted, 0, balances.length);
        java.util.Arrays.sort(sorted);

        long sum = 0;
        for (long b : sorted) {
            sum += b;
        }

        if (sum == 0) {
            return 0.0;
        }

        // Calculate weighted sum: sum of (index + 1) * value
        // The +1 makes it 1-based indexing for the standard formula
        long weightedSum = 0;
        for (int i = 0; i < sorted.length; i++) {
            weightedSum += (long) (i + 1) * sorted[i];
        }

        // Gini = (2 * weightedSum) / (n * sum) - (n + 1) / n
        double n = sorted.length;
        double gini = (2.0 * weightedSum) / (n * sum) - (n + 1.0) / n;

        // Clamp to [0.0, 1.0] to handle floating-point edge cases
        return Math.max(0.0, Math.min(1.0, gini));
    }

    /**
     * Interprets a Gini coefficient as a human-readable description.
     *
     * @param gini The Gini coefficient (0.0 to 1.0)
     * @return A descriptive string like "Low Inequality" or "High Inequality"
     */
    public static String interpret(double gini) {
        if (gini < 0.0) return "Invalid";
        if (gini < 0.2) return "Very Low Inequality";
        if (gini < 0.3) return "Low Inequality";
        if (gini < 0.4) return "Moderate Inequality";
        if (gini < 0.5) return "High Inequality";
        if (gini < 0.7) return "Very High Inequality";
        return "Extreme Inequality";
    }

    /**
     * Formats a Gini coefficient for display.
     *
     * @param gini The Gini coefficient
     * @return A formatted string like "0.35 (Moderate Inequality)"
     */
    public static String format(double gini) {
        return String.format("%.4f (%s)", gini, interpret(gini));
    }
}
