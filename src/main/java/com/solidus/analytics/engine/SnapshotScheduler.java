package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.AnalyticsDatabase;
import com.solidus.analytics.util.GiniCoefficient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SnapshotScheduler - Algorithm 2: Periodic Gini/Wealth Snapshot Calculation.
 *
 * <p>Periodically reads all player balances from the economy database and
 * computes wealth distribution metrics including the Gini coefficient,
 * average balance, median balance, and top-1% wealth share. Results are
 * stored as snapshots in the analytics database for trend analysis.</p>
 *
 * <h3>Snapshot Schedule:</h3>
 * <ul>
 *   <li>Hourly snapshots: Every 30 minutes (1800 ticks × 50ms ≈ 90 seconds per MC day cycle)</li>
 *   <li>Daily snapshots: Once per calendar day at the first hourly snapshot after midnight</li>
 * </ul>
 *
 * <h3>Gini Coefficient Calculation:</h3>
 * <p>Uses the O(n log n) Lorenz curve method from {@link GiniCoefficient}
 * for better performance with large player bases. The O(n^2) absolute
 * differences method is available but not used in production.</p>
 *
 * <h3>Thread Safety:</h3>
 * Snapshot computation is submitted to the analytics executor thread.
 * The economy.db is read in WAL mode, which allows safe concurrent reads
 * even while Solidus Core is writing.
 *
 * @since 1.0.0
 */
public class SnapshotScheduler {

    /** How often to take hourly snapshots (in server ticks: configurable, default 36000 = ~30 minutes) */
    private int snapshotIntervalTicks = 36000;

    /** The analytics database for storing snapshots */
    private final AnalyticsDatabase analyticsDb;

    /** The path to the economy.db file */
    private final String economyDbPath;

    /** The path to the auctions.db file */
    private final String auctionsDbPath;

    /** Track whether we've taken a daily snapshot today */
    private volatile String lastDailySnapshotDate = "";

    /** Tick counter for scheduling */
    private int tickCounter = 0;

    /** Reference to the analytics engine for weekly report triggering */
    private volatile com.solidus.analytics.engine.AnalyticsEngine engineRef;

    /**
     * Constructs a new SnapshotScheduler.
     *
     * @param analyticsDb    The analytics database
     * @param economyDbPath  Path to the economy.db file
     * @param auctionsDbPath Path to the auctions.db file
     */
    public SnapshotScheduler(AnalyticsDatabase analyticsDb, String economyDbPath, String auctionsDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
        this.auctionsDbPath = auctionsDbPath;
    }

    /**
     * Sets the analytics engine reference for weekly report triggering.
     * Called after engine initialization is complete.
     */
    public void setEngineRef(AnalyticsEngine engine) {
        this.engineRef = engine;
    }

    /**
     * Sets the snapshot interval from configuration.
     *
     * <p>Converts minutes to ticks (1 minute = 1200 ticks at 20 TPS).
     * Values are clamped to a minimum of 1 minute to prevent excessive
     * snapshot frequency that could impact server performance.</p>
     *
     * @param minutes Snapshot interval in minutes (from analytics.properties)
     */
    public void setSnapshotIntervalMinutes(int minutes) {
        minutes = Math.max(1, minutes); // Minimum 1 minute
        this.snapshotIntervalTicks = minutes * 1200; // 20 ticks/sec × 60 sec/min
        SolidusAnalyticsMod.LOGGER.info("Snapshot interval set to {} minutes ({} ticks)", minutes, snapshotIntervalTicks);
    }

    /**
     * Called on every server tick. Checks if it's time for a snapshot.
     * Should be registered with ServerTickEvents.END_SERVER_TICK.
     *
     * @param currentTick The current server tick count
     */
    public void onTick(int currentTick) {
        tickCounter++;
        if (tickCounter >= snapshotIntervalTicks) {
            tickCounter = 0;
            takeSnapshotAsync("HOURLY");
        }
    }

    /**
     * Forces an immediate snapshot (admin command).
     *
     * @param snapshotType The type label for the snapshot
     */
    public void forceSnapshot(String snapshotType) {
        takeSnapshotAsync(snapshotType);
    }

    /**
     * Asynchronously computes and persists a wealth distribution snapshot.
     * Runs on the analytics executor to avoid blocking the server tick thread.
     *
     * @param snapshotType The type of snapshot ("HOURLY", "DAILY", "MANUAL")
     */
    private void takeSnapshotAsync(String snapshotType) {
        analyticsDb.getExecutor().submit(() -> {
            try {
                SnapshotData data = computeSnapshot();
                if (data != null) {
                    AnalyticsDatabase.Snapshot snapshot = new AnalyticsDatabase.Snapshot(
                        System.currentTimeMillis(),
                        snapshotType,
                        data.totalWealth,
                        data.playerCount,
                        data.giniCoefficient,
                        data.avgBalance,
                        data.medianBalance,
                        data.top1PercentShare,
                        data.totalWealth, // money_supply = total_wealth for virtual economy
                        data.auctionActiveListings,
                        data.auctionTotalValue
                    );

                    analyticsDb.insertSnapshot(snapshot);

                    SolidusAnalyticsMod.LOGGER.info(
                        "Snapshot taken [{}]: {} players, total wealth={} cents, Gini={}, top1%={}%",
                        snapshotType, data.playerCount, data.totalWealth,
                        String.format("%.4f", data.giniCoefficient),
                        String.format("%.1f", data.top1PercentShare * 100));

                    // Check if we need a daily snapshot too
                    String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                    if (!today.equals(lastDailySnapshotDate)) {
                        lastDailySnapshotDate = today;
                        if (!"DAILY".equals(snapshotType)) {
                            takeSnapshotAsync("DAILY"); // recursive, but safe — only once per day
                        }

                        // Check if we should generate a weekly report
                        if (engineRef != null && engineRef.getWeeklyReportGenerator() != null) {
                            engineRef.getWeeklyReportGenerator().checkAndGenerate();
                        }
                    }
                }
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to take snapshot", e);
            }
        });
    }

    /**
     * Computes the current snapshot data by reading from the economy and auctions databases.
     *
     * @return SnapshotData with all computed metrics, or null on failure
     */
    private SnapshotData computeSnapshot() {
        SnapshotData data = new SnapshotData();

        // ── Read player balances from economy.db ──
        String dbUrl = "jdbc:sqlite:" + economyDbPath;
        List<Long> balances = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            String sql = "SELECT balance FROM player_balances ORDER BY balance ASC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    balances.add(rs.getLong("balance"));
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read balances for snapshot", e);
            return null;
        }

        if (balances.isEmpty()) {
            SolidusAnalyticsMod.LOGGER.warn("No player balances found. Skipping snapshot.");
            return null;
        }

        // ── Compute wealth metrics ──
        data.playerCount = balances.size();

        // Total wealth
        long totalWealth = 0;
        for (long b : balances) {
            totalWealth += b;
        }
        data.totalWealth = totalWealth;

        // Average balance
        data.avgBalance = totalWealth / data.playerCount;

        // Median balance
        int mid = data.playerCount / 2;
        if (data.playerCount % 2 == 0) {
            data.medianBalance = (balances.get(mid - 1) + balances.get(mid)) / 2;
        } else {
            data.medianBalance = balances.get(mid);
        }

        // Gini coefficient (use optimized algorithm for large datasets)
        long[] balanceArray = balances.stream().mapToLong(Long::longValue).toArray();
        if (data.playerCount > 1000) {
            data.giniCoefficient = GiniCoefficient.calculateOptimized(balanceArray);
        } else {
            data.giniCoefficient = GiniCoefficient.calculate(balanceArray);
        }

        // Top 1% wealth share
        int top1Count = Math.max(1, (int) Math.ceil(data.playerCount * 0.01));
        long top1Wealth = 0;
        for (int i = data.playerCount - top1Count; i < data.playerCount; i++) {
            top1Wealth += balances.get(i);
        }
        data.top1PercentShare = totalWealth > 0 ? (double) top1Wealth / totalWealth : 0.0;

        // ── Read auction data from auctions.db ──
        data.auctionActiveListings = 0;
        data.auctionTotalValue = 0;

        if (auctionsDbPath != null) {
            String auctionUrl = "jdbc:sqlite:" + auctionsDbPath;
            try (Connection conn = DriverManager.getConnection(auctionUrl)) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA query_only = ON");
                }

                String sql = "SELECT COUNT(*) as cnt, COALESCE(SUM(price), 0) as total_val "
                    + "FROM auction_listings WHERE status = 0";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        data.auctionActiveListings = rs.getInt("cnt");
                        data.auctionTotalValue = rs.getLong("total_val");
                    }
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to read auction data for snapshot. "
                    + "Auction metrics will be zero.", e);
            }
        }

        return data;
    }

    /**
     * Internal data class for computed snapshot values.
     */
    private static class SnapshotData {
        long totalWealth;
        int playerCount;
        double giniCoefficient;
        long avgBalance;
        long medianBalance;
        double top1PercentShare;
        int auctionActiveListings;
        long auctionTotalValue;
    }
}
