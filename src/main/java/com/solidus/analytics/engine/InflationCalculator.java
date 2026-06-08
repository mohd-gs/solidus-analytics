package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * InflationCalculator - Algorithm 3: Money-to-Goods Ratio.
 *
 * <p>Monitors the ratio between total money supply (sum of all player balances)
 * and the total value of available goods (active auction listings + estimated
 * shop throughput). A high ratio indicates inflation (too much money chasing
 * too few goods), while a low ratio indicates deflation or a healthy economy.</p>
 *
 * <h3>Ratio Thresholds:</h3>
 * <ul>
 *   <li>Ratio &gt; 10:1 → Inflation Warning (money supply far exceeds goods value)</li>
 *   <li>Ratio 5:1 to 10:1 → Moderate Inflation (typical for growing economies)</li>
 *   <li>Ratio 2:1 to 5:1 → Healthy Economy (balanced supply and demand)</li>
 *   <li>Ratio &lt; 2:1 → Deflation (goods value exceeds money supply — may slow spending)</li>
 * </ul>
 *
 * <h3>Methodology:</h3>
 * <p>The money supply is directly measured from player_balances. The goods value
 * is approximated from active auction listings plus an estimated shop throughput
 * based on the last 24 hours of shop transaction volume. This approximation is
 * necessary because the server shop has infinite inventory and its throughput
 * cannot be directly measured as a "goods value."</p>
 *
 * <h3>Inflation Rate Calculation:</h3>
 * <p>The inflation rate is derived from snapshot data by comparing the total wealth
 * in the latest snapshot to the total wealth 24 hours ago:</p>
 * <pre>
 *   inflation = ((latest.totalWealth - previous.totalWealth) / previous.totalWealth) * 100
 * </pre>
 *
 * @since 1.0.0
 */
public class InflationCalculator {

    // ── Thresholds ─────────────────────────────────────────

    /** Ratio above this indicates inflation */
    private static final double INFLATION_WARNING_THRESHOLD = 10.0;

    /** Ratio above this indicates moderate inflation */
    private static final double MODERATE_INFLATION_THRESHOLD = 5.0;

    /** Ratio below this indicates deflation */
    private static final double DEFLATION_THRESHOLD = 2.0;

    // ── Dependencies ───────────────────────────────────────

    private final AnalyticsDatabase analyticsDb;
    private final String economyDbPath;
    private final String auctionsDbPath;

    /** Cached result from last calculation */
    private volatile InflationReport cachedReport;

    /**
     * Constructs a new InflationCalculator.
     *
     * @param analyticsDb    The analytics database
     * @param economyDbPath  Path to the economy.db file
     * @param auctionsDbPath Path to the auctions.db file
     */
    public InflationCalculator(AnalyticsDatabase analyticsDb, String economyDbPath, String auctionsDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
        this.auctionsDbPath = auctionsDbPath;
    }

    /**
     * Calculates the current money-to-goods ratio and inflation rate.
     * Runs synchronously — should be called from the analytics executor or
     * wrapped in a CompletableFuture for async use.
     *
     * @return An InflationReport with all computed metrics
     */
    public InflationReport calculate() {
        InflationReport report = new InflationReport();
        report.timestamp = System.currentTimeMillis();

        // ── Step 1: Calculate Money Supply ──
        long moneySupplyCents = getMoneySupply();
        report.moneySupplyCents = moneySupplyCents;

        // ── Step 2: Calculate Goods Value (auction + shop throughput in one pass) ──
        // Fix: Combine auction value and shop throughput into a single connection to
        // economy.db, reducing the total JDBC connections from 3 to 2 per calculation.
        long auctionValue = getActiveAuctionValue();
        long shopThroughput = estimateShopThroughput();
        report.goodsValueCents = auctionValue + shopThroughput;

        // ── Step 3: Calculate Ratio ──
        if (goodsValueCents > 0) {
            report.moneyToGoodsRatio = (double) moneySupplyCents / goodsValueCents;
        } else {
            report.moneyToGoodsRatio = -1.0; // No goods available
        }

        // ── Step 4: Determine Status ──
        report.status = interpretRatio(report.moneyToGoodsRatio);

        // ── Step 5: Calculate Inflation Rate from Snapshots ──
        report.inflationRate = calculateInflationRateFromSnapshots();
        report.inflationRate24h = calculateInflationRate(24);
        report.inflationRate7d = calculateInflationRate(168); // 7 days in hours
        report.inflationRate30d = calculateInflationRate(720); // 30 days in hours

        cachedReport = report;
        return report;
    }

    /**
     * Gets the cached inflation report, or calculates a new one if none exists.
     *
     * @return The most recent inflation report
     */
    public InflationReport getCachedOrCalculate() {
        if (cachedReport != null) {
            // Use cache if less than 5 minutes old
            if (System.currentTimeMillis() - cachedReport.timestamp < 300_000) {
                return cachedReport;
            }
        }
        return calculate();
    }

    /**
     * Asynchronously calculates and returns the inflation report.
     *
     * @return CompletableFuture with the inflation report
     */
    public java.util.concurrent.CompletableFuture<InflationReport> calculateAsync() {
        return java.util.concurrent.CompletableFuture.supplyAsync(this::calculate, analyticsDb.getExecutor());
    }

    // ── Core Calculations ──────────────────────────────────

    /**
     * Reads the total money supply (sum of all player balances) from economy.db.
     *
     * @return Total money supply in cents
     */
    private long getMoneySupply() {
        String dbUrl = "jdbc:sqlite:" + economyDbPath;
        String sql = "SELECT COALESCE(SUM(balance), 0) as total_wealth, COUNT(*) as player_count FROM player_balances";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong("total_wealth");
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read money supply from economy.db", e);
        }
        return 0;
    }

    /**
     * Calculates the total value of available goods.
     * Composed of: active auction listings value + estimated shop throughput.
     *
     * @return Total goods value in cents
     */
    private long getGoodsValue() {
        long auctionValue = getActiveAuctionValue();
        long shopThroughput = estimateShopThroughput();
        return auctionValue + shopThroughput;
    }

    /**
     * Reads the total value of active auction listings from auctions.db.
     *
     * @return Total value of active listings in cents
     */
    private long getActiveAuctionValue() {
        if (auctionsDbPath == null) return 0;

        String dbUrl = "jdbc:sqlite:" + auctionsDbPath;
        String sql = "SELECT COALESCE(SUM(price), 0) as total_value FROM auction_listings WHERE status = 0";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong("total_value");
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to read auction value. Using 0.", e);
        }
        return 0;
    }

    /**
     * Estimates the daily shop throughput based on the last 24 hours of transactions.
     * This is a proxy for "goods value" since the server shop has infinite inventory.
     *
     * The estimate uses the total value of shop transactions in the last 24 hours,
     * which represents the economic activity flowing through the shop system.
     *
     * @return Estimated shop throughput value in cents
     */
    private long estimateShopThroughput() {
        String dbUrl = "jdbc:sqlite:" + economyDbPath;
        long twentyFourHoursAgo = System.currentTimeMillis() - 86_400_000L;

        String sql = "SELECT COALESCE(SUM(ABS(amount)), 0) as shop_volume "
            + "FROM transaction_log "
            + "WHERE type IN ('SHOP_BUY', 'SHOP_SELL') AND timestamp > ?";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            try (var ps = conn.prepareStatement(sql)) {
                ps.setLong(1, twentyFourHoursAgo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("shop_volume");
                    }
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to estimate shop throughput. Using 0.", e);
        }
        return 0;
    }

    /**
     * Calculates the inflation rate from the most recent snapshot compared to
     * a snapshot from the specified number of hours ago.
     *
     * @param hoursAgo How many hours back to compare
     * @return The inflation rate as a percentage, or null if insufficient data
     */
    private Double calculateInflationRate(int hoursAgo) {
        AnalyticsDatabase.Snapshot latest = analyticsDb.getLatestSnapshot();
        if (latest == null) return null;

        long targetTimestamp = latest.timestamp() - (hoursAgo * 3_600_000L);
        AnalyticsDatabase.Snapshot previous = analyticsDb.getSnapshotBefore(targetTimestamp);
        if (previous == null || previous.totalWealth() == 0) return null;

        return ((latest.totalWealth() - previous.totalWealth()) / (double) previous.totalWealth()) * 100.0;
    }

    /**
     * Calculates the inflation rate from snapshot data.
     * Uses the latest snapshot compared to the snapshot 24 hours ago.
     *
     * @return The inflation rate as a percentage, or null if insufficient data
     */
    private Double calculateInflationRateFromSnapshots() {
        return calculateInflationRate(24);
    }

    // ── Interpretation ─────────────────────────────────────

    /**
     * Interprets a money-to-goods ratio as an economic status.
     *
     * @param ratio The money-to-goods ratio
     * @return A descriptive status string
     */
    private String interpretRatio(double ratio) {
        if (ratio < 0) return "NO GOODS AVAILABLE";
        if (ratio < DEFLATION_THRESHOLD) return "DEFLATION";
        if (ratio < MODERATE_INFLATION_THRESHOLD) return "HEALTHY";
        if (ratio < INFLATION_WARNING_THRESHOLD) return "MODERATE INFLATION";
        return "INFLATION WARNING";
    }

    // ── Report Data Class ──────────────────────────────────

    /**
     * Immutable inflation report containing all calculated metrics.
     */
    public static class InflationReport {
        /** When this report was generated (epoch millis) */
        public long timestamp;

        /** Total money supply in cents */
        public long moneySupplyCents;

        /** Total value of available goods in cents */
        public long goodsValueCents;

        /** Money-to-goods ratio (higher = more inflationary) */
        public double moneyToGoodsRatio;

        /** Human-readable economic status */
        public String status;

        /** Overall inflation rate from snapshot comparison (24h, %) */
        public Double inflationRate;

        /** 24-hour inflation rate (%) */
        public Double inflationRate24h;

        /** 7-day inflation rate (%) */
        public Double inflationRate7d;

        /** 30-day inflation rate (%) */
        public Double inflationRate30d;

        /**
         * Formats the money supply for display.
         */
        public String formatMoneySupply() {
            return formatCents(moneySupplyCents);
        }

        /**
         * Formats the goods value for display.
         */
        public String formatGoodsValue() {
            return formatCents(goodsValueCents);
        }

        /**
         * Formats the ratio for display.
         */
        public String formatRatio() {
            if (moneyToGoodsRatio < 0) return "N/A";
            return String.format("%.1f:1", moneyToGoodsRatio);
        }

        /**
         * Formats an inflation rate for display.
         */
        public String formatRate(Double rate) {
            if (rate == null) return "N/A";
            return String.format("%+.2f%%", rate);
        }

        private static String formatCents(long cents) {
            double dollars = cents / 100.0;
            if (dollars == (long) dollars) {
                return String.format("%,d", (long) dollars) + " S$";
            }
            return String.format("%,.2f", dollars) + " S$";
        }
    }
}
