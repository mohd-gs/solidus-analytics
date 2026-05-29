package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * WeeklyReportGenerator - Generates a plain-text weekly economy report.
 *
 * <p>Automatically produces a human-readable report file every week summarizing
 * the server's economic health. The report is saved as a simple {@code .txt} file
 * in the config directory and optionally sent to Discord via webhook.</p>
 *
 * <h3>Report Contents:</h3>
 * <ul>
 *   <li>Executive summary with health score and grade</li>
 *   <li>Key metrics: volume, transactions, active players</li>
 *   <li>Inflation analysis with 24h/7d/30d rates</li>
 *   <li>Wealth distribution (Gini, top 1%, average/median)</li>
 *   <li>Top traded items</li>
 *   <li>Fraud alerts summary (if any)</li>
 *   <li>Recommendations based on detected patterns</li>
 * </ul>
 *
 * <h3>Schedule:</h3>
 * <p>Generated once per week, on the first snapshot after Monday 00:00 UTC.
 * Can also be triggered manually via {@code /analytics report weekly}.</p>
 *
 * @since 1.0.0
 */
public class WeeklyReportGenerator {

    private final AnalyticsEngine engine;
    private final Path reportsDir;
    private final AnalyticsDatabase database;

    /** Track the last week number we generated a report for — persisted in DB for restart resilience */
    private volatile int lastReportWeek = -1;

    public WeeklyReportGenerator(AnalyticsEngine engine, Path configDir) {
        this.engine = engine;
        this.database = engine.getDatabase();
        this.reportsDir = configDir.resolve("reports");

        // Load last report week from DB to prevent duplicate reports after restart
        this.lastReportWeek = loadLastReportWeekFromDB();
    }

    /**
     * Checks if it's time for a weekly report and generates one if so.
     * Called from the snapshot scheduler after each snapshot.
     */
    public void checkAndGenerate() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        int currentWeek = now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        int currentYear = now.getYear();

        // Generate on Monday (dayOfWeek = 1)
        if (now.getDayOfWeek().getValue() != 1) return;

        // Avoid duplicate generation for the same week
        int weekKey = currentYear * 100 + currentWeek;
        if (weekKey == lastReportWeek) return;

        lastReportWeek = weekKey;
        persistLastReportWeek(weekKey);
        generateReport();
    }

    /**
     * Loads the last report week key from the analytics database.
     *
     * <p>This prevents duplicate weekly reports when the server restarts on a Monday.
     * The week key is stored in the {@code analytics_metadata} table, which persists
     * across server restarts. If the table doesn't exist yet, it is created on first access.</p>
     *
     * @return The last week key (year * 100 + week number), or -1 if not found
     */
    private int loadLastReportWeekFromDB() {
        try {
            // Ensure the metadata table exists
            database.execute("CREATE TABLE IF NOT EXISTS analytics_metadata ("
                + "key TEXT PRIMARY KEY, "
                + "value TEXT NOT NULL)");

            var result = database.query("SELECT value FROM analytics_metadata WHERE key = 'last_weekly_report_week'");
            if (result != null && result.next()) {
                int weekKey = result.getInt(1);
                SolidusAnalyticsMod.LOGGER.info("Weekly report: loaded last report week from DB: {}", weekKey);
                return weekKey;
            }
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("Could not load last report week from DB, starting fresh", e);
        }
        return -1;
    }

    /**
     * Persists the last report week key to the analytics database.
     *
     * <p>Uses INSERT OR REPLACE (SQLite upsert) to atomically update the value.
     * This ensures that even if the server crashes after generating a report,
     * the next startup will know that report was already generated.</p>
     *
     * @param weekKey The week key (year * 100 + week number)
     */
    private void persistLastReportWeek(int weekKey) {
        try {
            database.execute("INSERT OR REPLACE INTO analytics_metadata (key, value) "
                + "VALUES ('last_weekly_report_week', '" + weekKey + "')");
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to persist last report week to DB", e);
        }
    }

    /**
     * Forces generation of a weekly report regardless of the day.
     *
     * @return The path to the generated report file, or null on failure
     */
    public Path forceGenerate() {
        return generateReport();
    }

    /**
     * Generates the weekly report and saves it to a text file.
     * Also sends a summary to Discord if configured.
     */
    private Path generateReport() {
        try {
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to create reports directory", e);
            return null;
        }

        String dateStr = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path reportFile = reportsDir.resolve("weekly-report-" + dateStr + ".txt");

        try {
            String content = buildReport();

            try (Writer writer = Files.newBufferedWriter(reportFile)) {
                writer.write(content);
            }

            SolidusAnalyticsMod.LOGGER.info("Weekly report generated: {}", reportFile);

            // Send summary to Discord if configured
            DiscordWebhookNotifier discord = engine.getDiscordNotifier();
            if (discord != null && discord.isEnabled()) {
                sendDiscordSummary(discord, content);
            }

            return reportFile;

        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to write weekly report", e);
            return null;
        }
    }

    /**
     * Builds the report content as a plain-text string.
     */
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        String separator = "═══════════════════════════════════════════════════════";

        LocalDate reportDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate weekStart = reportDate.minusDays(6);

        sb.append(separator).append("\n");
        sb.append("  SOLIDUS ANALYTICS — WEEKLY ECONOMY REPORT\n");
        sb.append("  Period: ").append(weekStart).append(" to ").append(reportDate).append("\n");
        sb.append("  Generated: ").append(java.time.Instant.now()).append("\n");
        sb.append(separator).append("\n\n");

        // ── Section 1: Executive Summary ──
        sb.append("EXECUTIVE SUMMARY\n");
        sb.append("─────────────────\n");

        EconomyHealthScore.HealthReport healthReport = null;
        if (engine.isPremiumEnabled() && engine.getHealthScore() != null) {
            healthReport = engine.getHealthScore().compute();
            sb.append(String.format("  Economy Health Score: %.1f / 100 (Grade: %s)\n",
                healthReport.overallScore, healthReport.getGrade()));
            sb.append("  Assessment: ").append(healthReport.summary).append("\n\n");
        } else {
            sb.append("  (Health score requires premium license)\n\n");
        }

        // ── Section 2: Key Metrics ──
        sb.append("KEY METRICS (Last 7 Days)\n");
        sb.append("─────────────────────────\n");

        AnalyticsDatabase db = engine.getDatabase();
        List<AnalyticsDatabase.DailyMetrics> weekData = db.getRecentDailyMetrics(7);

        if (weekData.isEmpty()) {
            sb.append("  No daily metrics data available yet.\n\n");
        } else {
            long totalVolume = 0;
            int totalTransactions = 0;
            int totalActivePlayers = 0;
            int daysWithData = weekData.size();

            for (AnalyticsDatabase.DailyMetrics day : weekData) {
                totalVolume += day.transactionVolume();
                totalTransactions += day.transactionCount();
                totalActivePlayers = Math.max(totalActivePlayers, day.activePlayers());
            }

            sb.append(String.format("  Total Volume:         %,.2f S$\n", totalVolume / 100.0));
            sb.append(String.format("  Total Transactions:   %,d\n", totalTransactions));
            sb.append(String.format("  Avg Daily Volume:     %,.2f S$\n", (totalVolume / 100.0) / daysWithData));
            sb.append(String.format("  Avg Daily Tx Count:   %,d\n", totalTransactions / daysWithData));
            sb.append(String.format("  Peak Active Players:  %d\n", totalActivePlayers));
            sb.append("\n");

            // Daily breakdown
            sb.append("  Daily Breakdown:\n");
            sb.append("  ┌────────────┬──────────┬─────────────────┬─────────┐\n");
            sb.append("  │ Date       │ Tx Count │ Volume          │ Active  │\n");
            sb.append("  ├────────────┼──────────┼─────────────────┼─────────┤\n");
            for (AnalyticsDatabase.DailyMetrics day : weekData) {
                sb.append(String.format("  │ %s │ %,8d │ %,14.2f S$ │ %,7d │\n",
                    day.date(), day.transactionCount(),
                    day.transactionVolume() / 100.0, day.activePlayers()));
            }
            sb.append("  └────────────┴──────────┴─────────────────┴─────────┘\n\n");
        }

        // ── Section 3: Inflation Analysis ──
        sb.append("INFLATION ANALYSIS\n");
        sb.append("──────────────────\n");

        InflationCalculator.InflationReport inflation = null;
        try {
            inflation = engine.getInflationCalculator().getCachedOrCalculate();
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("Could not get inflation report for weekly summary", e);
        }

        if (inflation != null) {
            sb.append(String.format("  Money Supply:       %s\n", inflation.formatMoneySupply()));
            sb.append(String.format("  Goods Value:        %s\n", inflation.formatGoodsValue()));
            sb.append(String.format("  Money:Goods Ratio:  %s\n", inflation.formatRatio()));
            sb.append(String.format("  Status:             %s\n", inflation.status));
            sb.append("\n");
            sb.append("  Inflation Rates:\n");
            sb.append(String.format("    24h:  %s\n", inflation.formatRate(inflation.inflationRate24h)));
            sb.append(String.format("    7d:   %s\n", inflation.formatRate(inflation.inflationRate7d)));
            sb.append(String.format("    30d:  %s\n", inflation.formatRate(inflation.inflationRate30d)));
            sb.append("\n  Reference:\n");
            sb.append("    Ratio < 2:1   = Deflation\n");
            sb.append("    Ratio 2-5:1   = Healthy\n");
            sb.append("    Ratio 5-10:1  = Moderate Inflation\n");
            sb.append("    Ratio > 10:1  = Inflation Warning\n\n");
        } else {
            sb.append("  Insufficient data for inflation analysis.\n\n");
        }

        // ── Section 4: Wealth Distribution ──
        sb.append("WEALTH DISTRIBUTION\n");
        sb.append("───────────────────\n");

        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();
        if (latest != null) {
            sb.append(String.format("  Total Wealth:       %,.2f S$\n", latest.totalWealth() / 100.0));
            sb.append(String.format("  Player Count:       %d\n", latest.playerCount()));
            sb.append(String.format("  Average Balance:    %,.2f S$\n", latest.avgBalance() / 100.0));
            sb.append(String.format("  Median Balance:     %,.2f S$\n", latest.medianBalance() / 100.0));
            sb.append(String.format("  Gini Coefficient:   %.4f (%s)\n",
                latest.giniCoefficient(), interpretGini(latest.giniCoefficient())));
            sb.append(String.format("  Top 1%% Share:       %.1f%%\n", latest.top1PercentShare() * 100));
            sb.append(String.format("  Active Auctions:    %d (value: %,.2f S$)\n",
                latest.auctionActiveListings(), latest.auctionTotalValue() / 100.0));
            sb.append("\n");
        } else {
            sb.append("  No snapshot data available yet.\n\n");
        }

        // ── Section 5: Top Items ──
        sb.append("TOP TRADED ITEMS (Today)\n");
        sb.append("────────────────────────\n");

        LiveMetricsTracker metrics = engine.getLiveMetrics();
        var topBought = metrics.getTopBoughtItems(5);
        var topSold = metrics.getTopSoldItems(5);

        if (!topBought.isEmpty()) {
            sb.append("  Most Bought:\n");
            int rank = 1;
            for (var entry : topBought.entrySet()) {
                sb.append(String.format("    #%d  %s: %d units\n", rank++, entry.getKey(), entry.getValue()));
            }
        }
        if (!topSold.isEmpty()) {
            sb.append("  Most Sold:\n");
            int rank = 1;
            for (var entry : topSold.entrySet()) {
                sb.append(String.format("    #%d  %s: %d units\n", rank++, entry.getKey(), entry.getValue()));
            }
        }
        if (topBought.isEmpty() && topSold.isEmpty()) {
            sb.append("  No item data available.\n");
        }
        sb.append("\n");

        // ── Section 6: Fraud Alerts ──
        if (engine.isPremiumEnabled() && engine.getFraudDetector() != null) {
            sb.append("FRAUD ALERTS (This Week)\n");
            sb.append("────────────────────────\n");

            List<FraudDetector.FraudAlert> alerts = engine.getFraudDetector().getRecentAlerts(20);
            if (alerts.isEmpty()) {
                sb.append("  No suspicious activity detected. Economy looks clean.\n\n");
            } else {
                long highCount = alerts.stream()
                    .filter(a -> a.severity == FraudDetector.FraudAlert.Severity.HIGH).count();
                long medCount = alerts.stream()
                    .filter(a -> a.severity == FraudDetector.FraudAlert.Severity.MEDIUM).count();

                sb.append(String.format("  Total alerts: %d (HIGH: %d, MEDIUM: %d, LOW: %d)\n\n",
                    alerts.size(), highCount, medCount,
                    alerts.size() - highCount - medCount));

                for (FraudDetector.FraudAlert alert : alerts) {
                    sb.append(String.format("  [%s] %s — %s\n", alert.severity, alert.type, alert.playerName));
                    sb.append(String.format("         %s\n", alert.description));
                }
                sb.append("\n");
            }
        }

        // ── Section 7: Recommendations ──
        sb.append("RECOMMENDATIONS\n");
        sb.append("───────────────\n");

        List<String> recommendations = generateRecommendations(latest, inflation, healthReport);
        if (recommendations.isEmpty()) {
            sb.append("  Economy is healthy. No immediate actions needed.\n");
        } else {
            for (int i = 0; i < recommendations.size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, recommendations.get(i)));
            }
        }

        sb.append("\n").append(separator).append("\n");
        sb.append("  Report generated by Solidus Analytics\n");
        sb.append(separator).append("\n");

        return sb.toString();
    }

    /**
     * Generates actionable recommendations based on the current economic data.
     */
    private List<String> generateRecommendations(
            AnalyticsDatabase.Snapshot snapshot,
            InflationCalculator.InflationReport inflation,
            EconomyHealthScore.HealthReport health) {

        List<String> recs = new java.util.ArrayList<>();

        // Inflation-based recommendations
        if (inflation != null) {
            if (inflation.moneyToGoodsRatio > 10.0) {
                recs.add("High inflation detected. Consider increasing shop prices or adding money sinks "
                    + "(taxes, fees, luxury items) to reduce the money supply.");
            } else if (inflation.moneyToGoodsRatio < 2.0 && inflation.moneyToGoodsRatio > 0) {
                recs.add("Deflation detected. Consider reducing shop prices or adding ways for players "
                    + "to earn money to stimulate spending.");
            }

            if (inflation.inflationRate7d != null && inflation.inflationRate7d > 15.0) {
                recs.add("Weekly inflation rate exceeds 15%. This is concerning — investigate potential "
                    + "money duplication exploits or overly generous reward systems.");
            }
        }

        // Wealth inequality recommendations
        if (snapshot != null) {
            if (snapshot.giniCoefficient() > 0.6) {
                recs.add("High wealth inequality (Gini > 0.6). Consider implementing progressive taxes, "
                    + "welfare systems, or new player bonuses to distribute wealth more evenly.");
            }

            if (snapshot.top1PercentShare() > 0.4) {
                recs.add(String.format("Top 1%% of players hold %.1f%% of all wealth. This may discourage "
                    + "new players. Consider wealth redistribution mechanisms.", snapshot.top1PercentShare() * 100));
            }

            if (snapshot.auctionActiveListings() < 5 && snapshot.playerCount() > 20) {
                recs.add("Very few active auction listings relative to player count. The market may be "
                    + "illiquid. Consider encouraging auctions through incentives or events.");
            }
        }

        // Health score recommendations
        if (health != null) {
            if (health.activityScore < 40) {
                recs.add("Low transaction activity. Players may be hoarding money instead of trading. "
                    + "Consider adding limited-time shop deals or events to stimulate the economy.");
            }
            if (health.liquidityScore < 40) {
                recs.add("Low market liquidity. There are not enough goods available for purchase. "
                    + "Consider adding more items to the server shop or encouraging player trading.");
            }
        }

        if (recs.isEmpty()) {
            recs.add("Economy is healthy. No immediate actions needed.");
        }

        return recs;
    }

    /**
     * Sends a condensed version of the report to Discord.
     */
    private void sendDiscordSummary(DiscordWebhookNotifier discord, String fullReport) {
        // Extract key lines for Discord (don't send the full report — too long)
        StringBuilder summary = new StringBuilder();
        String[] lines = fullReport.split("\n");

        // Take the header, executive summary, key metrics, and recommendations
        boolean inSection = false;
        int sectionCount = 0;

        for (String line : lines) {
            if (line.startsWith("EXECUTIVE") || line.startsWith("KEY METRICS")
                || line.startsWith("RECOMMENDATIONS")) {
                inSection = true;
                sectionCount++;
            } else if (line.startsWith("INFLATION") || line.startsWith("WEALTH")
                || line.startsWith("TOP TRADED") || line.startsWith("FRAUD")) {
                inSection = false;
            }

            if (inSection || line.contains("SOLIDUS ANALYTICS") || line.contains("═══")) {
                summary.append(line).append("\n");
            }

            // Limit Discord message length
            if (summary.length() > 1800) {
                summary.append("... (full report saved to file)\n");
                break;
            }
        }

        String color = "5763719"; // Green
        discord.notifyCustomEmbed("Weekly Economy Report", summary.toString(), color);
    }

    /**
     * Interprets a Gini coefficient as a human-readable label.
     */
    private String interpretGini(double gini) {
        if (gini < 0.2) return "Very Low Inequality";
        if (gini < 0.3) return "Low Inequality";
        if (gini < 0.4) return "Moderate Inequality";
        if (gini < 0.5) return "High Inequality";
        if (gini < 0.7) return "Very High Inequality";
        return "Extreme Inequality";
    }
}
