package com.solidus.analytics.storage;

import com.solidus.analytics.SolidusAnalyticsMod;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AnalyticsDatabase - Separate SQLite database for analytics data storage.
 *
 * <p>Uses its own {@code analytics.db} file, completely independent from
 * Solidus Core's {@code economy.db} and {@code auctions.db}. This separation
 * ensures that analytics operations never interfere with the main economy
 * database's write performance or lock contention.</p>
 *
 * <h3>Architecture:</h3>
 * <ul>
 *   <li>Single-threaded executor for serialized DB access (same pattern as Solidus Core)</li>
 *   <li>WAL mode for crash resilience and safe concurrent reads from the economy DB</li>
 *   <li>Persistent connection shared across all executor operations</li>
 *   <li>Three tables: snapshots, daily_metrics, item_metrics</li>
 * </ul>
 *
 * <h3>Thread Safety:</h3>
 * All database operations (both reads and writes) are synchronized via a shared
 * lock object to prevent concurrent access to the persistent JDBC connection.
 * Write operations are also submitted to a single-threaded executor for serialization.
 * The persistent connection is never accessed from multiple threads simultaneously.
 *
 * @since 1.0.0
 */
public class AnalyticsDatabase {

    private static final String DATABASE_NAME = "analytics.db";

    // ── Table Creation SQL ─────────────────────────────────

    private static final String CREATE_SNAPSHOTS_TABLE = """
        CREATE TABLE IF NOT EXISTS analytics_snapshots (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            snapshot_type TEXT NOT NULL,
            total_wealth INTEGER NOT NULL,
            player_count INTEGER NOT NULL,
            gini_coefficient REAL NOT NULL,
            avg_balance INTEGER NOT NULL,
            median_balance INTEGER NOT NULL,
            top1_percent_share REAL NOT NULL,
            money_supply INTEGER NOT NULL,
            auction_active_listings INTEGER NOT NULL,
            auction_total_value INTEGER NOT NULL
        )
    """;

    private static final String CREATE_SNAPSHOTS_INDEX = """
        CREATE INDEX IF NOT EXISTS idx_snapshots_type_time
        ON analytics_snapshots (snapshot_type, timestamp DESC)
    """;

    private static final String CREATE_DAILY_METRICS_TABLE = """
        CREATE TABLE IF NOT EXISTS analytics_daily_metrics (
            date TEXT PRIMARY KEY,
            transaction_count INTEGER NOT NULL,
            transaction_volume INTEGER NOT NULL,
            shop_buy_count INTEGER NOT NULL,
            shop_sell_count INTEGER NOT NULL,
            auction_count INTEGER NOT NULL,
            pay_transfer_count INTEGER NOT NULL,
            new_players INTEGER NOT NULL,
            active_players INTEGER NOT NULL,
            inflation_rate REAL,
            top_item_bought TEXT,
            top_item_sold TEXT
        )
    """;

    private static final String CREATE_ITEM_METRICS_TABLE = """
        CREATE TABLE IF NOT EXISTS analytics_item_metrics (
            date TEXT NOT NULL,
            material TEXT NOT NULL,
            buy_count INTEGER NOT NULL,
            sell_count INTEGER NOT NULL,
            total_quantity INTEGER NOT NULL,
            total_value INTEGER NOT NULL,
            PRIMARY KEY (date, material)
        )
    """;

    // ── Fields ─────────────────────────────────────────────

    private final ExecutorService asyncExecutor;
    private final String databaseUrl;
    private volatile Connection persistentConnection;
    private volatile boolean initialized = false;

    /** Lock object for synchronized access to the persistent connection */
    private final Object connectionLock = new Object();

    /**
     * Immutable record representing a single analytics snapshot.
     */
    public record Snapshot(
        long timestamp,
        String snapshotType,
        long totalWealth,
        int playerCount,
        double giniCoefficient,
        long avgBalance,
        long medianBalance,
        double top1PercentShare,
        long moneySupply,
        int auctionActiveListings,
        long auctionTotalValue
    ) {}

    /**
     * Immutable record representing a day's aggregated metrics.
     */
    public record DailyMetrics(
        String date,
        int transactionCount,
        long transactionVolume,
        int shopBuyCount,
        int shopSellCount,
        int auctionCount,
        int payTransferCount,
        int newPlayers,
        int activePlayers,
        Double inflationRate,
        String topItemBought,
        String topItemSold
    ) {}

    /**
     * Immutable record representing item metrics for a specific day and material.
     */
    public record ItemMetrics(
        String date,
        String material,
        int buyCount,
        int sellCount,
        int totalQuantity,
        long totalValue
    ) {}

    /**
     * Constructs a new AnalyticsDatabase with the given config directory path.
     *
     * @param configDir The directory where the database file will be stored
     */
    public AnalyticsDatabase(String configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir + "/" + DATABASE_NAME;
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Analytics-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initializes the database: creates tables, indexes, configures WAL mode,
     * and opens the persistent connection.
     */
    public void initialize() {
        try {
            persistentConnection = DriverManager.getConnection(databaseUrl);

            // Enable WAL mode for crash resilience
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA temp_store=MEMORY");
                stmt.execute("PRAGMA mmap_size=67108864"); // 64MB
                stmt.execute("PRAGMA cache_size=-2000");     // 2MB cache
            }

            // Create tables
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute(CREATE_SNAPSHOTS_TABLE);
                stmt.execute(CREATE_SNAPSHOTS_INDEX);
                stmt.execute(CREATE_DAILY_METRICS_TABLE);
                stmt.execute(CREATE_ITEM_METRICS_TABLE);
            }

            initialized = true;
            SolidusAnalyticsMod.LOGGER.info("Analytics database initialized successfully.");

        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("CRITICAL: Failed to initialize analytics database!", e);
            throw new RuntimeException("Solidus Analytics database initialization failed", e);
        }
    }

    /**
     * Shuts down the async executor and closes the persistent connection.
     */
    public void shutdown() {
        if (initialized) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                    SolidusAnalyticsMod.LOGGER.warn("Analytics executor forced shutdown after timeout.");
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            if (persistentConnection != null) {
                try {
                    persistentConnection.close();
                    SolidusAnalyticsMod.LOGGER.info("Analytics database connection closed.");
                } catch (SQLException e) {
                    SolidusAnalyticsMod.LOGGER.error("Failed to close analytics database connection", e);
                }
            }
        }
        SolidusAnalyticsMod.LOGGER.info("Analytics database shut down complete.");
    }

    // ── Snapshot Operations ────────────────────────────────

    /**
     * Inserts a new snapshot into the analytics_snapshots table.
     *
     * @param snapshot The snapshot to persist
     */
    public void insertSnapshot(Snapshot snapshot) {
        ensureInitialized();
        String sql = """
            INSERT INTO analytics_snapshots
            (timestamp, snapshot_type, total_wealth, player_count, gini_coefficient,
             avg_balance, median_balance, top1_percent_share, money_supply,
             auction_active_listings, auction_total_value)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, snapshot.timestamp());
                ps.setString(2, snapshot.snapshotType());
                ps.setLong(3, snapshot.totalWealth());
                ps.setInt(4, snapshot.playerCount());
                ps.setDouble(5, snapshot.giniCoefficient());
                ps.setLong(6, snapshot.avgBalance());
                ps.setLong(7, snapshot.medianBalance());
                ps.setDouble(8, snapshot.top1PercentShare());
                ps.setLong(9, snapshot.moneySupply());
                ps.setInt(10, snapshot.auctionActiveListings());
                ps.setLong(11, snapshot.auctionTotalValue());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to insert analytics snapshot", e);
            }
        }
    }

    /**
     * Asynchronously inserts a snapshot into the database.
     *
     * @param snapshot The snapshot to persist
     */
    public void insertSnapshotAsync(Snapshot snapshot) {
        ensureInitialized();
        asyncExecutor.submit(() -> insertSnapshot(snapshot));
    }

    /**
     * Gets the most recent snapshot.
     *
     * @return The latest snapshot, or null if none exist
     */
    public Snapshot getLatestSnapshot() {
        ensureInitialized();
        String sql = "SELECT * FROM analytics_snapshots ORDER BY timestamp DESC LIMIT 1";
        synchronized (connectionLock) {
            try (Statement stmt = persistentConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return mapSnapshot(rs);
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get latest snapshot", e);
            }
        }
        return null;
    }

    /**
     * Gets the snapshot closest to the specified timestamp (looking backwards).
     *
     * @param timestamp The target timestamp (epoch millis)
     * @return The snapshot closest to but not after the timestamp, or null
     */
    public Snapshot getSnapshotBefore(long timestamp) {
        ensureInitialized();
        String sql = "SELECT * FROM analytics_snapshots WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1";
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, timestamp);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapSnapshot(rs);
                    }
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get snapshot before timestamp", e);
            }
        }
        return null;
    }

    /**
     * Gets the last N snapshots of a specific type.
     *
     * @param snapshotType The type filter (e.g., "HOURLY", "DAILY"), or null for all
     * @param limit        Maximum number of snapshots to return
     * @return List of snapshots, most recent first
     */
    public List<Snapshot> getSnapshots(String snapshotType, int limit) {
        ensureInitialized();
        List<Snapshot> snapshots = new ArrayList<>();
        synchronized (connectionLock) {
            try {
                if (snapshotType != null) {
                    String sql = "SELECT * FROM analytics_snapshots WHERE snapshot_type = ? ORDER BY timestamp DESC LIMIT ?";
                    try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                        ps.setString(1, snapshotType);
                        ps.setInt(2, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                snapshots.add(mapSnapshot(rs));
                            }
                        }
                    }
                } else {
                    String sql = "SELECT * FROM analytics_snapshots ORDER BY timestamp DESC LIMIT ?";
                    try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                        ps.setInt(1, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                snapshots.add(mapSnapshot(rs));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get snapshots", e);
            }
        }
        return snapshots;
    }

    // ── Daily Metrics Operations ───────────────────────────

    /**
     * Inserts or replaces daily metrics for a specific date.
     *
     * @param metrics The daily metrics to persist
     */
    public void upsertDailyMetrics(DailyMetrics metrics) {
        ensureInitialized();
        String sql = """
            INSERT OR REPLACE INTO analytics_daily_metrics
            (date, transaction_count, transaction_volume, shop_buy_count, shop_sell_count,
             auction_count, pay_transfer_count, new_players, active_players,
             inflation_rate, top_item_bought, top_item_sold)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, metrics.date());
                ps.setInt(2, metrics.transactionCount());
                ps.setLong(3, metrics.transactionVolume());
                ps.setInt(4, metrics.shopBuyCount());
                ps.setInt(5, metrics.shopSellCount());
                ps.setInt(6, metrics.auctionCount());
                ps.setInt(7, metrics.payTransferCount());
                ps.setInt(8, metrics.newPlayers());
                ps.setInt(9, metrics.activePlayers());
                if (metrics.inflationRate() != null) {
                    ps.setDouble(10, metrics.inflationRate());
                } else {
                    ps.setNull(10, java.sql.Types.REAL);
                }
                ps.setString(11, metrics.topItemBought());
                ps.setString(12, metrics.topItemSold());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to upsert daily metrics", e);
            }
        }
    }

    /**
     * Asynchronously upserts daily metrics.
     */
    public void upsertDailyMetricsAsync(DailyMetrics metrics) {
        ensureInitialized();
        asyncExecutor.submit(() -> upsertDailyMetrics(metrics));
    }

    /**
     * Gets the daily metrics for a specific date.
     *
     * @param date The date string in YYYY-MM-DD format
     * @return The daily metrics, or null if no data exists for that date
     */
    public DailyMetrics getDailyMetrics(String date) {
        ensureInitialized();
        String sql = "SELECT * FROM analytics_daily_metrics WHERE date = ?";
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, date);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapDailyMetrics(rs);
                    }
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get daily metrics for date: {}", date, e);
            }
        }
        return null;
    }

    /**
     * Gets the last N days of daily metrics.
     *
     * @param limit Maximum number of days to return
     * @return List of daily metrics, most recent first
     */
    public List<DailyMetrics> getRecentDailyMetrics(int limit) {
        ensureInitialized();
        List<DailyMetrics> metrics = new ArrayList<>();
        String sql = "SELECT * FROM analytics_daily_metrics ORDER BY date DESC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        metrics.add(mapDailyMetrics(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get recent daily metrics", e);
            }
        }
        return metrics;
    }

    // ── Item Metrics Operations ────────────────────────────

    /**
     * Inserts or replaces item metrics for a specific date and material.
     *
     * @param metrics The item metrics to persist
     */
    public void upsertItemMetrics(ItemMetrics metrics) {
        ensureInitialized();
        String sql = """
            INSERT OR REPLACE INTO analytics_item_metrics
            (date, material, buy_count, sell_count, total_quantity, total_value)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, metrics.date());
                ps.setString(2, metrics.material());
                ps.setInt(3, metrics.buyCount());
                ps.setInt(4, metrics.sellCount());
                ps.setInt(5, metrics.totalQuantity());
                ps.setLong(6, metrics.totalValue());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to upsert item metrics", e);
            }
        }
    }

    /**
     * Asynchronously upserts item metrics.
     */
    public void upsertItemMetricsAsync(ItemMetrics metrics) {
        ensureInitialized();
        asyncExecutor.submit(() -> upsertItemMetrics(metrics));
    }

    /**
     * Gets the item metrics for a specific date.
     *
     * @param date The date string in YYYY-MM-DD format
     * @return List of item metrics for that date
     */
    public List<ItemMetrics> getItemMetrics(String date) {
        ensureInitialized();
        List<ItemMetrics> metrics = new ArrayList<>();
        String sql = "SELECT * FROM analytics_item_metrics WHERE date = ? ORDER BY total_value DESC";
        synchronized (connectionLock) {
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, date);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        metrics.add(mapItemMetrics(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get item metrics for date: {}", date, e);
            }
        }
        return metrics;
    }

    // ── Executor Access (for async operations) ─────────────

    /**
     * Gets the async executor for scheduling database operations.
     * Used by AnalyticsEngine and related components.
     *
     * @return The single-threaded executor
     */
    public ExecutorService getExecutor() {
        return asyncExecutor;
    }

    /**
     * Checks if the database is initialized and ready.
     */
    public boolean isInitialized() {
        return initialized;
    }

    // ── ResultSet Mapping ──────────────────────────────────

    private Snapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new Snapshot(
            rs.getLong("timestamp"),
            rs.getString("snapshot_type"),
            rs.getLong("total_wealth"),
            rs.getInt("player_count"),
            rs.getDouble("gini_coefficient"),
            rs.getLong("avg_balance"),
            rs.getLong("median_balance"),
            rs.getDouble("top1_percent_share"),
            rs.getLong("money_supply"),
            rs.getInt("auction_active_listings"),
            rs.getLong("auction_total_value")
        );
    }

    private DailyMetrics mapDailyMetrics(ResultSet rs) throws SQLException {
        double inflationRate = rs.getDouble("inflation_rate");
        return new DailyMetrics(
            rs.getString("date"),
            rs.getInt("transaction_count"),
            rs.getLong("transaction_volume"),
            rs.getInt("shop_buy_count"),
            rs.getInt("shop_sell_count"),
            rs.getInt("auction_count"),
            rs.getInt("pay_transfer_count"),
            rs.getInt("new_players"),
            rs.getInt("active_players"),
            rs.wasNull() ? null : inflationRate,
            rs.getString("top_item_bought"),
            rs.getString("top_item_sold")
        );
    }

    private ItemMetrics mapItemMetrics(ResultSet rs) throws SQLException {
        return new ItemMetrics(
            rs.getString("date"),
            rs.getString("material"),
            rs.getInt("buy_count"),
            rs.getInt("sell_count"),
            rs.getInt("total_quantity"),
            rs.getLong("total_value")
        );
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("AnalyticsDatabase accessed before initialization!");
        }
    }
}
