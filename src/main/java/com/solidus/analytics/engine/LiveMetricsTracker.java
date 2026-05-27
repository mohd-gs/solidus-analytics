package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LiveMetricsTracker - Algorithm 1: Incremental Live Metrics.
 *
 * <p>Maintains in-memory counters that are updated incrementally by polling
 * the Solidus economy database for new transactions every 30-60 seconds.
 * This provides O(1) reads for dashboard commands without needing to query
 * the transaction log on every command invocation.</p>
 *
 * <h3>Architecture:</h3>
 * <ul>
 *   <li>Polls the transaction_log table for new rows since the last poll</li>
 *   <li>Updates in-memory counters atomically on each poll cycle</li>
 *   <li>Resets daily counters at midnight (server timezone)</li>
 *   <li>Tracks: daily volume, transaction counts by type, top items, active players</li>
 * </ul>
 *
 * <h3>Polling Strategy (Option A from handoff doc):</h3>
 * <p>Direct DB polling is the simplest, safest approach for v1. It requires
 * no modification to Solidus Core and no Mixin. The trade-off is a 30-60
 * second delay before new transactions appear in analytics, which is
 * perfectly acceptable for a dashboard that players check periodically.</p>
 *
 * <h3>Thread Safety:</h3>
 * All counters use AtomicLong or ConcurrentHashMap for thread-safe updates.
 * The DB polling runs on the analytics executor thread, while reads can
 * happen on the server tick thread (from command execution).
 *
 * @since 1.0.0
 */
public class LiveMetricsTracker {

    // ── Polling Configuration ──────────────────────────────

    /** How often to poll for new transactions (in milliseconds) */
    private static final long POLL_INTERVAL_MS = 30_000L; // 30 seconds

    /** Timestamp of the last transaction we processed */
    private final AtomicLong lastPolledTimestamp = new AtomicLong(0);

    /** Date string for the current tracking period (YYYY-MM-DD) */
    private volatile String currentDate = LocalDate.now(ZoneOffset.UTC).toString();

    // ── Live Counters (reset daily) ────────────────────────

    /** Total transaction volume in cents for today */
    private final AtomicLong dailyVolumeCents = new AtomicLong(0);

    /** Total number of transactions today */
    private final AtomicLong dailyTransactionCount = new AtomicLong(0);

    /** Transaction count by type (e.g., SHOP_BUY → 42) */
    private final ConcurrentHashMap<String, AtomicLong> transactionsByType = new ConcurrentHashMap<>();

    /** Top items by quantity bought (SHOP_BUY + AUCTION_BOUGHT) */
    private final ConcurrentHashMap<String, AtomicLong> topItemsBought = new ConcurrentHashMap<>();

    /** Top items by quantity sold (SHOP_SELL) */
    private final ConcurrentHashMap<String, AtomicLong> topItemsSold = new ConcurrentHashMap<>();

    /** Active players today (players with at least 1 transaction) */
    private final ConcurrentHashMap<String, Boolean> activePlayers = new ConcurrentHashMap<>();

    // ── Dependencies ───────────────────────────────────────

    private final AnalyticsDatabase analyticsDb;
    private final String economyDbPath;

    /** Whether the tracker has been started */
    private volatile boolean running = false;

    /**
     * Constructs a new LiveMetricsTracker.
     *
     * @param analyticsDb   The analytics database for persistent storage
     * @param economyDbPath The path to the economy.db file (for direct reads)
     */
    public LiveMetricsTracker(AnalyticsDatabase analyticsDb, String economyDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
    }

    /**
     * Starts the periodic polling loop.
     * Called once during mod startup.
     */
    public void start() {
        if (running) return;
        running = true;

        // Initialize lastPolledTimestamp to the most recent transaction
        initializeLastTimestamp();

        // Schedule periodic polling
        analyticsDb.getExecutor().submit(this::pollingLoop);

        SolidusAnalyticsMod.LOGGER.info("LiveMetricsTracker started. Polling interval: {}ms", POLL_INTERVAL_MS);
    }

    /**
     * Stops the polling loop.
     */
    public void stop() {
        running = false;
        SolidusAnalyticsMod.LOGGER.info("LiveMetricsTracker stopped.");
    }

    // ── Public Read Methods (O(1) from memory) ─────────────

    /**
     * Gets today's total transaction volume in dollars.
     */
    public double getDailyVolume() {
        return dailyVolumeCents.get() / 100.0;
    }

    /**
     * Gets today's total transaction volume in cents.
     */
    public long getDailyVolumeCents() {
        return dailyVolumeCents.get();
    }

    /**
     * Gets today's total transaction count.
     */
    public long getDailyTransactionCount() {
        return dailyTransactionCount.get();
    }

    /**
     * Gets the count of transactions by type for today.
     *
     * @return An unmodifiable copy of the type counts
     */
    public Map<String, Long> getTransactionsByType() {
        Map<String, Long> result = new HashMap<>();
        transactionsByType.forEach((type, count) -> result.put(type, count.get()));
        return result;
    }

    /**
     * Gets the top N bought items by quantity today.
     *
     * @param limit Maximum number of items to return
     * @return Map of material name to quantity bought
     */
    public Map<String, Long> getTopBoughtItems(int limit) {
        return getTopEntries(topItemsBought, limit);
    }

    /**
     * Gets the top N sold items by quantity today.
     *
     * @param limit Maximum number of items to return
     * @return Map of material name to quantity sold
     */
    public Map<String, Long> getTopSoldItems(int limit) {
        return getTopEntries(topItemsSold, limit);
    }

    /**
     * Gets the number of active players today (players with at least 1 transaction).
     */
    public int getActivePlayerCount() {
        return activePlayers.size();
    }

    // ── Polling Implementation ─────────────────────────────

    /**
     * Main polling loop. Runs on the analytics executor thread.
     */
    private void pollingLoop() {
        while (running) {
            try {
                pollNewTransactions();
                checkDailyReset();
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Error during transaction poll", e);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Polls the economy database for new transactions since the last poll.
     * Updates in-memory counters with the results.
     */
    private void pollNewTransactions() {
        long since = lastPolledTimestamp.get();
        if (since == 0) {
            // First poll — don't process historical data, just record current timestamp
            lastPolledTimestamp.set(System.currentTimeMillis());
            return;
        }

        String dbUrl = "jdbc:sqlite:" + economyDbPath;
        String sql = """
            SELECT timestamp, type, player_uuid, player_name, amount, item_material, item_quantity
            FROM transaction_log
            WHERE timestamp > ?
            ORDER BY timestamp ASC
        """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // Open in read-only mode (we're only reading from economy.db)
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            ps.setLong(1, since);

            long maxTimestamp = since;
            int processed = 0;

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long timestamp = rs.getLong("timestamp");
                    String type = rs.getString("type");
                    String playerUuid = rs.getString("player_uuid");
                    long amountCents = rs.getLong("amount");
                    String itemMaterial = rs.getString("item_material");
                    int itemQuantity = rs.getInt("item_quantity");

                    // Update counters
                    dailyVolumeCents.addAndGet(Math.abs(amountCents));
                    dailyTransactionCount.incrementAndGet();

                    // Track by type
                    transactionsByType.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();

                    // Track items
                    if (itemMaterial != null && itemQuantity > 0) {
                        if ("SHOP_BUY".equals(type) || "AUCTION_BOUGHT".equals(type)) {
                            topItemsBought.computeIfAbsent(itemMaterial, k -> new AtomicLong(0))
                                .addAndGet(itemQuantity);
                        } else if ("SHOP_SELL".equals(type)) {
                            topItemsSold.computeIfAbsent(itemMaterial, k -> new AtomicLong(0))
                                .addAndGet(itemQuantity);
                        }
                    }

                    // Track active player
                    if (playerUuid != null) {
                        activePlayers.put(playerUuid, Boolean.TRUE);
                    }

                    maxTimestamp = Math.max(maxTimestamp, timestamp);
                    processed++;
                }
            }

            if (processed > 0) {
                lastPolledTimestamp.set(maxTimestamp);
                SolidusAnalyticsMod.LOGGER.debug("Processed {} new transactions. Daily total: {} tx, S${}",
                    processed, dailyTransactionCount.get(),
                    String.format("%,.2f", dailyVolumeCents.get() / 100.0));
            }

        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to poll transactions from economy.db", e);
        }
    }

    /**
     * Checks if the date has rolled over and resets daily counters if so.
     * Also persists the previous day's metrics to the analytics database.
     */
    private void checkDailyReset() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (!today.equals(currentDate)) {
            SolidusAnalyticsMod.LOGGER.info("Date changed from {} to {}. Persisting daily metrics and resetting counters.",
                currentDate, today);

            // Persist previous day's metrics before resetting
            persistDailyMetrics(currentDate);

            // Reset all daily counters
            dailyVolumeCents.set(0);
            dailyTransactionCount.set(0);
            transactionsByType.clear();
            topItemsBought.clear();
            topItemsSold.clear();
            activePlayers.clear();
            currentDate = today;
        }
    }

    /**
     * Persists the current daily metrics to the analytics database.
     *
     * @param date The date string (YYYY-MM-DD) for these metrics
     */
    private void persistDailyMetrics(String date) {
        Map<String, Long> typeCounts = getTransactionsByType();

        int shopBuyCount = typeCounts.getOrDefault("SHOP_BUY", 0L).intValue();
        int shopSellCount = typeCounts.getOrDefault("SHOP_SELL", 0L).intValue();
        int auctionCount = (int) (
            typeCounts.getOrDefault("AUCTION_LIST", 0L) +
            typeCounts.getOrDefault("AUCTION_SOLD", 0L) +
            typeCounts.getOrDefault("AUCTION_BOUGHT", 0L) +
            typeCounts.getOrDefault("AUCTION_EXPIRED", 0L)
        );
        int payTransferCount = typeCounts.getOrDefault("PAY_SEND", 0L).intValue();

        // Find top bought and sold items
        String topBought = getTopBoughtItems(1).entrySet().stream()
            .findFirst().map(Map.Entry::getKey).orElse(null);
        String topSold = getTopSoldItems(1).entrySet().stream()
            .findFirst().map(Map.Entry::getKey).orElse(null);

        // Calculate inflation rate from snapshots
        Double inflationRate = calculateInflationRate();

        AnalyticsDatabase.DailyMetrics metrics = new AnalyticsDatabase.DailyMetrics(
            date,
            (int) dailyTransactionCount.get(),
            dailyVolumeCents.get(),
            shopBuyCount,
            shopSellCount,
            auctionCount,
            payTransferCount,
            0,  // newPlayers - requires join tracking, placeholder for now
            activePlayers.size(),
            inflationRate,
            topBought,
            topSold
        );

        analyticsDb.upsertDailyMetricsAsync(metrics);
        SolidusAnalyticsMod.LOGGER.info("Persisted daily metrics for date: {}", date);
    }

    /**
     * Calculates the inflation rate from the most recent and 24h-ago snapshots.
     *
     * @return The inflation rate as a percentage, or null if insufficient data
     */
    private Double calculateInflationRate() {
        AnalyticsDatabase.Snapshot latest = analyticsDb.getLatestSnapshot();
        if (latest == null) return null;

        long twentyFourHoursAgo = latest.timestamp() - 86_400_000L;
        AnalyticsDatabase.Snapshot previous = analyticsDb.getSnapshotBefore(twentyFourHoursAgo);
        if (previous == null || previous.totalWealth() == 0) return null;

        return ((latest.totalWealth() - previous.totalWealth()) / (double) previous.totalWealth()) * 100.0;
    }

    /**
     * Initializes the lastPolledTimestamp to the most recent transaction
     * in the database, so we only process truly new transactions.
     */
    private void initializeLastTimestamp() {
        String dbUrl = "jdbc:sqlite:" + economyDbPath;
        String sql = "SELECT MAX(timestamp) as max_ts FROM transaction_log";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            // Set read-only mode BEFORE executing any queries
            try (var pragmaStmt = conn.createStatement()) {
                pragmaStmt.execute("PRAGMA query_only = ON");
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                long maxTs = rs.getLong("max_ts");
                if (!rs.wasNull()) {
                    lastPolledTimestamp.set(maxTs);
                    SolidusAnalyticsMod.LOGGER.info("Last known transaction timestamp: {}", maxTs);
                } else {
                    lastPolledTimestamp.set(System.currentTimeMillis());
                    SolidusAnalyticsMod.LOGGER.info("No transactions found. Starting from current time.");
                }
            }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to initialize last polled timestamp", e);
            lastPolledTimestamp.set(System.currentTimeMillis());
        }
    }

    // ── Utility ────────────────────────────────────────────

    private Map<String, Long> getTopEntries(ConcurrentHashMap<String, AtomicLong> map, int limit) {
        Map<String, Long> result = new HashMap<>();
        map.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> -e.getValue().get()))
            .limit(limit)
            .forEach(e -> result.put(e.getKey(), e.getValue().get()));
        return result;
    }

    /**
     * Forces a persist of the current daily metrics.
     * Called by the /analytics snapshot admin command.
     */
    public void forcePersist() {
        persistDailyMetrics(currentDate);
    }
}
