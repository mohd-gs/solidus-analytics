package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.storage.AnalyticsDatabase;

/**
 * EconomyHealthScore - Composite economy health assessment algorithm.
 *
 * <p>Computes a single, easy-to-understand score (0-100) that summarizes
 * the overall health of the server's economy. This score is derived from
 * multiple weighted factors including wealth inequality, inflation rate,
 * money supply growth, transaction activity, and market liquidity.</p>
 *
 * <h3>Score Interpretation:</h3>
 * <ul>
 *   <li>90-100: Excellent — Well-balanced, thriving economy</li>
 *   <li>70-89: Good — Healthy with minor concerns</li>
 *   <li>50-69: Fair — Some imbalances that may need attention</li>
 *   <li>30-49: Poor — Significant economic problems</li>
 *   <li>0-29: Critical — Economy is in distress</li>
 * </ul>
 *
 * <h3>Scoring Factors (weighted):</h3>
 * <ul>
 *   <li>Gini Coefficient (25%) — Lower inequality = healthier</li>
 *   <li>Inflation Rate (25%) — Mild inflation (2-5%) is healthy</li>
 *   <li>Money Supply Growth (20%) — Moderate growth is positive</li>
 *   <li>Transaction Activity (15%) — Active trading = healthy</li>
 *   <li>Market Liquidity (15%) — Available goods in auctions/shops</li>
 * </ul>
 *
 * <p><b>Premium Feature:</b> This class is part of the paid Solidus Analytics
 * add-on. It requires a valid license key to function.</p>
 *
 * @since 1.0.0
 */
public class EconomyHealthScore {

    // ── Weight Configuration ────────────────────────────────

    private static final double WEIGHT_GINI = 0.25;
    private static final double WEIGHT_INFLATION = 0.25;
    private static final double WEIGHT_MONEY_GROWTH = 0.20;
    private static final double WEIGHT_ACTIVITY = 0.15;
    private static final double WEIGHT_LIQUIDITY = 0.15;

    // ── Dependencies ────────────────────────────────────────

    private final AnalyticsEngine engine;

    public EconomyHealthScore(AnalyticsEngine engine) {
        this.engine = engine;
    }

    // ── Public API ──────────────────────────────────────────

    /**
     * Computes the composite economy health score.
     *
     * @return A HealthReport containing the score and breakdown
     */
    public HealthReport compute() {
        HealthReport report = new HealthReport();
        report.timestamp = System.currentTimeMillis();

        AnalyticsDatabase db = engine.getDatabase();
        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();

        if (latest == null) {
            report.overallScore = 50.0; // Neutral score when no data available
            report.summary = "Insufficient data — no snapshots available yet";
            report.giniScore = 50.0;
            report.inflationScore = 50.0;
            report.moneyGrowthScore = 50.0;
            report.activityScore = 50.0;
            report.liquidityScore = 50.0;
            return report;
        }

        // Factor 1: Gini Coefficient (lower = better)
        report.giniScore = computeGiniScore(latest.giniCoefficient());

        // Factor 2: Inflation Rate
        InflationCalculator.InflationReport inflationReport = engine.getInflationCalculator().getCachedOrCalculate();
        report.inflationScore = computeInflationScore(inflationReport);

        // Factor 3: Money Supply Growth
        report.moneyGrowthScore = computeMoneyGrowthScore(latest, db);

        // Factor 4: Transaction Activity
        report.activityScore = computeActivityScore();

        // Factor 5: Market Liquidity
        report.liquidityScore = computeLiquidityScore(latest);

        // Weighted composite score
        report.overallScore = Math.max(0, Math.min(100,
            report.giniScore * WEIGHT_GINI
            + report.inflationScore * WEIGHT_INFLATION
            + report.moneyGrowthScore * WEIGHT_MONEY_GROWTH
            + report.activityScore * WEIGHT_ACTIVITY
            + report.liquidityScore * WEIGHT_LIQUIDITY
        ));

        // Generate summary
        report.summary = interpretScore(report.overallScore);

        return report;
    }

    // ── Factor Computations ─────────────────────────────────

    /**
     * Gini coefficient scoring.
     * Optimal range: 0.2-0.4 (moderate inequality is natural in game economies).
     * Too low (<0.1) means no differentiation, too high (>0.6) means extreme inequality.
     */
    private double computeGiniScore(double gini) {
        if (gini < 0.0 || gini > 1.0) return 50.0;

        // Bell curve centered around 0.3 (optimal Gini for game economies)
        if (gini <= 0.1) return 40.0; // Too equal — no incentive to trade
        if (gini <= 0.2) return 70.0; // Somewhat equal
        if (gini <= 0.3) return 95.0; // Sweet spot
        if (gini <= 0.4) return 85.0; // Good
        if (gini <= 0.5) return 65.0; // Moderate inequality
        if (gini <= 0.6) return 45.0; // Concerning
        if (gini <= 0.7) return 30.0; // High inequality
        return 15.0; // Extreme inequality
    }

    /**
     * Inflation rate scoring.
     * Optimal: mild positive inflation (2-5%) — encourages spending.
     * Deflation (<0%) and high inflation (>10%) are both negative.
     */
    private double computeInflationScore(InflationCalculator.InflationReport report) {
        if (report == null || report.inflationRate24h == null) return 50.0;

        double rate = report.inflationRate24h;

        if (rate < -5.0) return 20.0;  // Severe deflation — spending stops
        if (rate < -2.0) return 40.0;  // Moderate deflation
        if (rate < 0.0) return 60.0;   // Mild deflation
        if (rate < 2.0) return 80.0;   // Low inflation — stable
        if (rate < 5.0) return 95.0;   // Sweet spot — healthy growth
        if (rate < 10.0) return 60.0;  // Moderate inflation
        if (rate < 20.0) return 35.0;  // High inflation
        if (rate < 50.0) return 15.0;  // Very high inflation
        return 5.0;                     // Hyperinflation
    }

    /**
     * Money supply growth scoring.
     * Compares current total wealth to wealth 7 days ago.
     * Moderate growth (5-15% weekly) is healthy.
     */
    private double computeMoneyGrowthScore(AnalyticsDatabase.Snapshot latest, AnalyticsDatabase db) {
        // Get a snapshot from approximately 7 days ago
        long sevenDaysAgo = latest.timestamp() - (7L * 86_400_000L);
        AnalyticsDatabase.Snapshot weekAgo = db.getSnapshotBefore(sevenDaysAgo);

        if (weekAgo == null || weekAgo.totalWealth() == 0) return 50.0;

        double growthRate = ((latest.totalWealth() - weekAgo.totalWealth())
            / (double) weekAgo.totalWealth()) * 100.0;

        // Weekly growth rate scoring
        if (growthRate < -10.0) return 15.0;  // Severe contraction
        if (growthRate < -5.0) return 35.0;   // Significant contraction
        if (growthRate < 0.0) return 55.0;    // Mild contraction
        if (growthRate < 5.0) return 75.0;    // Slow growth
        if (growthRate < 15.0) return 95.0;   // Healthy growth
        if (growthRate < 25.0) return 70.0;   // Fast growth
        if (growthRate < 50.0) return 40.0;   // Very fast — may indicate money printing
        return 20.0;                            // Extreme — definitely problematic
    }

    /**
     * Transaction activity scoring.
     * Based on the ratio of active players to total players and transaction frequency.
     */
    private double computeActivityScore() {
        long dailyTx = engine.getLiveMetrics().getDailyTransactionCount();
        int activePlayers = engine.getLiveMetrics().getActivePlayerCount();

        if (activePlayers == 0) return 30.0; // No activity at all

        // Average transactions per active player
        double txPerPlayer = (double) dailyTx / activePlayers;

        // Score based on engagement level
        if (txPerPlayer < 1.0) return 35.0;   // Very low engagement
        if (txPerPlayer < 3.0) return 55.0;    // Low engagement
        if (txPerPlayer < 5.0) return 75.0;    // Moderate engagement
        if (txPerPlayer < 10.0) return 90.0;   // Good engagement
        if (txPerPlayer < 20.0) return 95.0;   // High engagement
        return 70.0;                             // Very high — might indicate bot activity
    }

    /**
     * Market liquidity scoring.
     * Based on the ratio of auction listings to active players.
     * More listings = healthier market.
     */
    private double computeLiquidityScore(AnalyticsDatabase.Snapshot latest) {
        int playerCount = latest.playerCount();
        if (playerCount == 0) return 30.0;

        int auctionListings = latest.auctionActiveListings();
        double listingsPerPlayer = (double) auctionListings / playerCount;

        if (listingsPerPlayer < 0.1) return 35.0;   // Very illiquid
        if (listingsPerPlayer < 0.5) return 55.0;    // Low liquidity
        if (listingsPerPlayer < 1.0) return 75.0;    // Moderate liquidity
        if (listingsPerPlayer < 2.0) return 90.0;    // Good liquidity
        if (listingsPerPlayer < 5.0) return 95.0;    // High liquidity
        return 80.0;                                   // Very high — might indicate market dumping
    }

    // ── Interpretation ──────────────────────────────────────

    /**
     * Interprets the overall health score as a human-readable summary.
     */
    private String interpretScore(double score) {
        if (score >= 90) return "Excellent — Economy is thriving with healthy balance";
        if (score >= 80) return "Good — Economy is stable with minor concerns";
        if (score >= 70) return "Above Average — Generally healthy, some areas to watch";
        if (score >= 60) return "Fair — Moderate imbalances detected";
        if (score >= 50) return "Average — Mixed signals, consider monitoring";
        if (score >= 40) return "Below Average — Several economic concerns";
        if (score >= 30) return "Poor — Significant economic problems detected";
        if (score >= 20) return "Critical — Economy is in distress";
        return "Emergency — Immediate intervention needed";
    }

    /**
     * Gets a color name for the score (used in command output).
     */
    public static String getScoreColor(double score) {
        if (score >= 80) return "GREEN";
        if (score >= 60) return "YELLOW";
        if (score >= 40) return "GOLD";
        if (score >= 20) return "RED";
        return "DARK_RED";
    }

    // ── Report Data Class ───────────────────────────────────

    /**
     * Immutable health report containing the composite score and factor breakdowns.
     */
    public static class HealthReport {
        /** When this report was generated (epoch millis) */
        public long timestamp;

        /** Overall economy health score (0-100) */
        public double overallScore;

        /** Gini coefficient factor score (0-100) */
        public double giniScore;

        /** Inflation rate factor score (0-100) */
        public double inflationScore;

        /** Money supply growth factor score (0-100) */
        public double moneyGrowthScore;

        /** Transaction activity factor score (0-100) */
        public double activityScore;

        /** Market liquidity factor score (0-100) */
        public double liquidityScore;

        /** Human-readable summary of the economy's health */
        public String summary;

        /**
         * Gets the letter grade for the overall score.
         */
        public String getGrade() {
            if (overallScore >= 90) return "A+";
            if (overallScore >= 80) return "A";
            if (overallScore >= 70) return "B+";
            if (overallScore >= 60) return "B";
            if (overallScore >= 50) return "C+";
            if (overallScore >= 40) return "C";
            if (overallScore >= 30) return "D";
            return "F";
        }

        /**
         * Formats the report as a multi-line string for display.
         */
        public String formatReport() {
            return String.format("""
                Economy Health Score: %.1f/100 (%s)
                Summary: %s

                Factor Breakdown:
                  Gini Inequality:    %.0f/100 (weight: 25%%)
                  Inflation Rate:     %.0f/100 (weight: 25%%)
                  Money Growth:       %.0f/100 (weight: 20%%)
                  Activity Level:     %.0f/100 (weight: 15%%)
                  Market Liquidity:   %.0f/100 (weight: 15%%)
                """,
                overallScore, getGrade(), summary,
                giniScore, inflationScore, moneyGrowthScore, activityScore, liquidityScore);
        }
    }
}
