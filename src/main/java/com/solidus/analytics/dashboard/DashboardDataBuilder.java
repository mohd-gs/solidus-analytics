package com.solidus.analytics.dashboard;

import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.util.List;
import java.util.Map;

/**
 * DashboardDataBuilder - Builds JSON data from the analytics engine
 * for the web dashboard.
 *
 * <p>Collects all relevant analytics data (live metrics, snapshots,
 * inflation, health score, fraud alerts) and formats it as a JSON
 * string suitable for publishing to GitHub Pages or serving via the
 * embedded web server.</p>
 *
 * <h3>Data Structure:</h3>
 * <pre>
 * {
 *   "timestamp": 1716900000000,
 *   "server": { "name": "...", "fingerprint": "..." },
 *   "liveMetrics": { ... },
 *   "latestSnapshot": { ... },
 *   "inflation": { ... },
 *   "healthScore": { ... },
 *   "fraudAlerts": [ ... ],
 *   "dailyHistory": [ ... ],
 *   "topItems": { ... }
 * }
 * </pre>
 *
 * @since 1.1.0
 */
public class DashboardDataBuilder {

    /**
     * Builds a JSON string containing all dashboard data.
     *
     * @param engine The analytics engine to read data from
     * @return A JSON string ready for encryption/publishing
     */
    public static String buildJson(AnalyticsEngine engine) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        // Timestamp
        json.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");

        // Server info
        json.append("\"server\":{");
        json.append("\"name\":").append(escapeJson(getServerName(engine))).append(",");
        json.append("\"fingerprint\":").append(escapeJson(
            com.solidus.analytics.license.LicenseVerifier.computeServerFingerprint()));
        json.append("},");

        // Live metrics
        buildLiveMetrics(json, engine);
        json.append(",");

        // Latest snapshot
        buildLatestSnapshot(json, engine);
        json.append(",");

        // Inflation data
        buildInflationData(json, engine);
        json.append(",");

        // Health score (premium)
        buildHealthScore(json, engine);
        json.append(",");

        // Fraud alerts (premium)
        buildFraudAlerts(json, engine);
        json.append(",");

        // Daily history
        buildDailyHistory(json, engine);
        json.append(",");

        // Top items
        buildTopItems(json, engine);

        json.append("}");
        return json.toString();
    }

    // ── Section Builders ────────────────────────────────────

    private static void buildLiveMetrics(StringBuilder json, AnalyticsEngine engine) {
        LiveMetricsTracker metrics = engine.getLiveMetrics();

        json.append("\"liveMetrics\":{");
        json.append("\"dailyVolume\":").append(metrics.getDailyVolumeCents()).append(",");
        json.append("\"dailyTransactionCount\":").append(metrics.getDailyTransactionCount()).append(",");
        json.append("\"activePlayerCount\":").append(metrics.getActivePlayerCount()).append(",");

        // Transactions by type
        Map<String, Long> byType = metrics.getTransactionsByType();
        json.append("\"transactionsByType\":{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : byType.entrySet()) {
            if (!first) json.append(",");
            json.append(escapeJson(entry.getKey())).append(":").append(entry.getValue());
            first = false;
        }
        json.append("}");

        json.append("}");
    }

    private static void buildLatestSnapshot(StringBuilder json, AnalyticsEngine engine) {
        AnalyticsDatabase.Snapshot snapshot = engine.getDatabase().getLatestSnapshot();

        json.append("\"latestSnapshot\":");
        if (snapshot == null) {
            json.append("null");
        } else {
            json.append("{");
            json.append("\"timestamp\":").append(snapshot.timestamp()).append(",");
            json.append("\"type\":").append(escapeJson(snapshot.snapshotType())).append(",");
            json.append("\"totalWealth\":").append(snapshot.totalWealth()).append(",");
            json.append("\"playerCount\":").append(snapshot.playerCount()).append(",");
            json.append("\"giniCoefficient\":").append(snapshot.giniCoefficient()).append(",");
            json.append("\"avgBalance\":").append(snapshot.avgBalance()).append(",");
            json.append("\"medianBalance\":").append(snapshot.medianBalance()).append(",");
            json.append("\"top1PercentShare\":").append(snapshot.top1PercentShare()).append(",");
            json.append("\"moneySupply\":").append(snapshot.moneySupply()).append(",");
            json.append("\"auctionActiveListings\":").append(snapshot.auctionActiveListings()).append(",");
            json.append("\"auctionTotalValue\":").append(snapshot.auctionTotalValue());
            json.append("}");
        }
    }

    private static void buildInflationData(StringBuilder json, AnalyticsEngine engine) {
        InflationCalculator.InflationReport report = null;
        try {
            report = engine.getInflationCalculator().getCachedOrCalculate();
        } catch (Exception e) {
            // Ignore — will output null
        }

        json.append("\"inflation\":");
        if (report == null) {
            json.append("null");
        } else {
            json.append("{");
            json.append("\"moneySupplyCents\":").append(report.moneySupplyCents).append(",");
            json.append("\"goodsValueCents\":").append(report.goodsValueCents).append(",");
            json.append("\"moneyToGoodsRatio\":").append(report.moneyToGoodsRatio).append(",");
            json.append("\"status\":").append(escapeJson(report.status)).append(",");
            json.append("\"inflationRate24h\":").append(report.inflationRate24h != null ? report.inflationRate24h : "null").append(",");
            json.append("\"inflationRate7d\":").append(report.inflationRate7d != null ? report.inflationRate7d : "null").append(",");
            json.append("\"inflationRate30d\":").append(report.inflationRate30d != null ? report.inflationRate30d : "null");
            json.append("}");
        }
    }

    private static void buildHealthScore(StringBuilder json, AnalyticsEngine engine) {
        json.append("\"healthScore\":");
        if (!engine.isPremiumEnabled() || engine.getHealthScore() == null) {
            json.append("null");
        } else {
            try {
                EconomyHealthScore.HealthReport report = engine.getHealthScore().compute();
                json.append("{");
                json.append("\"overallScore\":").append(report.overallScore).append(",");
                json.append("\"grade\":").append(escapeJson(report.getGrade())).append(",");
                json.append("\"summary\":").append(escapeJson(report.summary)).append(",");
                json.append("\"giniScore\":").append(report.giniScore).append(",");
                json.append("\"inflationScore\":").append(report.inflationScore).append(",");
                json.append("\"moneyGrowthScore\":").append(report.moneyGrowthScore).append(",");
                json.append("\"activityScore\":").append(report.activityScore).append(",");
                json.append("\"liquidityScore\":").append(report.liquidityScore);
                json.append("}");
            } catch (Exception e) {
                json.append("null");
            }
        }
    }

    private static void buildFraudAlerts(StringBuilder json, AnalyticsEngine engine) {
        json.append("\"fraudAlerts\":");
        if (!engine.isPremiumEnabled() || engine.getFraudDetector() == null) {
            json.append("null");
        } else {
            List<FraudDetector.FraudAlert> alerts = engine.getFraudDetector().getRecentAlerts(20);
            json.append("[");
            for (int i = 0; i < alerts.size(); i++) {
                if (i > 0) json.append(",");
                FraudDetector.FraudAlert alert = alerts.get(i);
                json.append("{");
                json.append("\"timestamp\":").append(alert.timestamp).append(",");
                json.append("\"type\":").append(escapeJson(alert.type.name())).append(",");
                json.append("\"playerName\":").append(escapeJson(alert.playerName)).append(",");
                json.append("\"severity\":").append(escapeJson(alert.severity.name())).append(",");
                json.append("\"description\":").append(escapeJson(alert.description));
                json.append("}");
            }
            json.append("]");
        }
    }

    private static void buildDailyHistory(StringBuilder json, AnalyticsEngine engine) {
        List<AnalyticsDatabase.DailyMetrics> history = engine.getDatabase().getRecentDailyMetrics(30);

        json.append("\"dailyHistory\":[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) json.append(",");
            AnalyticsDatabase.DailyMetrics day = history.get(i);
            json.append("{");
            json.append("\"date\":").append(escapeJson(day.date())).append(",");
            json.append("\"transactionCount\":").append(day.transactionCount()).append(",");
            json.append("\"transactionVolume\":").append(day.transactionVolume()).append(",");
            json.append("\"activePlayers\":").append(day.activePlayers()).append(",");
            json.append("\"inflationRate\":").append(day.inflationRate() != null ? day.inflationRate() : "null");
            json.append("}");
        }
        json.append("]");
    }

    private static void buildTopItems(StringBuilder json, AnalyticsEngine engine) {
        LiveMetricsTracker metrics = engine.getLiveMetrics();

        json.append("\"topItems\":{");

        // Top bought
        Map<String, Long> topBought = metrics.getTopBoughtItems(10);
        json.append("\"bought\":[");
        int i = 0;
        for (Map.Entry<String, Long> entry : topBought.entrySet()) {
            if (i > 0) json.append(",");
            json.append("{\"item\":").append(escapeJson(entry.getKey()))
                .append(",\"quantity\":").append(entry.getValue()).append("}");
            i++;
        }
        json.append("],");

        // Top sold
        Map<String, Long> topSold = metrics.getTopSoldItems(10);
        json.append("\"sold\":[");
        i = 0;
        for (Map.Entry<String, Long> entry : topSold.entrySet()) {
            if (i > 0) json.append(",");
            json.append("{\"item\":").append(escapeJson(entry.getKey()))
                .append(",\"quantity\":").append(entry.getValue()).append("}");
            i++;
        }
        json.append("]");

        json.append("}");
    }

    // ── Utility ─────────────────────────────────────────────

    private static String getServerName(AnalyticsEngine engine) {
        try {
            // Try to get the server MOTD as a name
            return "Solidus Server";
        } catch (Exception e) {
            return "Unknown Server";
        }
    }

    /**
     * Escapes a string for JSON.
     */
    static String escapeJson(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
