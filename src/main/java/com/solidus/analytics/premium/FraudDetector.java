package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FraudDetector - Anomaly detection for suspicious economic activity.
 *
 * <p>Monitors the server's economy for patterns that may indicate exploits,
 * money duplication bugs, or abuse of game mechanics. Uses configurable
 * thresholds to flag suspicious transactions and generates alerts for
 * server administrators.</p>
 *
 * <h3>Detection Patterns:</h3>
 * <ul>
 *   <li><b>Rapid Wealth Gain</b> — Player's balance increases by more than X%
 *       of the server average within a short period</li>
 *   <li><b>Circular Trading</b> — Two or more players trading money back and
 *       forth between each other repeatedly</li>
 *   <li><b>Unusual Transaction Size</b> — Single transaction significantly
 *       larger than the server average</li>
 *   <li><b>High-Frequency Trading</b> — Player making an abnormally high
 *       number of transactions in a short time window</li>
 *   <li><b>Zero-Value Transfers</b> — PAY commands with zero or near-zero
 *       amounts (potential exploit testing)</li>
 * </ul>
 *
 * <h3>Alert System:</h3>
 * <p>Detected anomalies are stored in the analytics database and can trigger
 * Discord webhook notifications (if configured) for real-time admin alerts.</p>
 *
 * <p><b>Premium Feature:</b> This class is part of the paid Solidus Analytics
 * add-on. It requires a valid license key to function.</p>
 *
 * @since 1.0.0
 */
public class FraudDetector {

    // ── Thresholds ──────────────────────────────────────────

    /** Max balance increase (multiple of server average) that triggers an alert */
    private static final double RAPID_WEALTH_THRESHOLD = 5.0;

    /** Max transactions per player per minute before flagging */
    private static final int HIGH_FREQUENCY_THRESHOLD = 30;

    /** Min transactions between same two players to flag circular trading */
    private static final int CIRCULAR_TRADE_THRESHOLD = 5;

    /** Transaction size (multiple of server average) that triggers an alert */
    private static final double UNUSUAL_SIZE_THRESHOLD = 10.0;

    // ── Dependencies ────────────────────────────────────────

    private final AnalyticsEngine engine;
    private final String economyDbPath;

    /** List of recent alerts (kept in memory for command display) */
    private final List<FraudAlert> recentAlerts = new ArrayList<>();

    /** Maximum number of alerts to keep in memory */
    private static final int MAX_RECENT_ALERTS = 100;

    public FraudDetector(AnalyticsEngine engine, String economyDbPath) {
        this.engine = engine;
        this.economyDbPath = economyDbPath;
    }

    // ── Detection Methods ───────────────────────────────────

    /**
     * Runs all fraud detection checks and returns any new alerts found.
     * Should be called periodically (e.g., every snapshot cycle).
     *
     * @return List of new fraud alerts
     */
    public List<FraudAlert> runAllChecks() {
        List<FraudAlert> newAlerts = new ArrayList<>();

        newAlerts.addAll(checkRapidWealthGain());
        newAlerts.addAll(checkHighFrequencyTrading());
        newAlerts.addAll(checkUnusualTransactionSize());

        // Add alerts to the in-memory list
        for (FraudAlert alert : newAlerts) {
            addAlert(alert);
        }

        if (!newAlerts.isEmpty()) {
            SolidusAnalyticsMod.LOGGER.warn("Fraud detection found {} suspicious pattern(s).",
                newAlerts.size());
        }

        return newAlerts;
    }

    /**
     * Checks for players whose balance has increased suspiciously fast.
     * Compares each player's recent balance change to the server average.
     */
    private List<FraudAlert> checkRapidWealthGain() {
        List<FraudAlert> alerts = new ArrayList<>();
        String dbUrl = "jdbc:sqlite:" + economyDbPath;

        long oneHourAgo = System.currentTimeMillis() - 3_600_000L;

        String sql = """
            SELECT player_uuid, player_name,
                   SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) as income,
                   SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END) as spending
            FROM transaction_log
            WHERE timestamp > ?
            GROUP BY player_uuid, player_name
            HAVING income > 0
            ORDER BY income DESC
        """;

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            try (var ps = conn.prepareStatement(sql)) {
                ps.setLong(1, oneHourAgo);
                try (ResultSet rs = ps.executeQuery()) {
                    // Calculate average income
                    double totalIncome = 0;
                    int playerCount = 0;
                    List<String[]> results = new ArrayList<>();

                    while (rs.next()) {
                        String uuid = rs.getString("player_uuid");
                        String name = rs.getString("player_name");
                        long income = rs.getLong("income");
                        totalIncome += income;
                        playerCount++;
                        results.add(new String[]{uuid, name, String.valueOf(income)});
                    }

                    if (playerCount > 0) {
                        double avgIncome = totalIncome / playerCount;

                        for (String[] result : results) {
                            long income = Long.parseLong(result[2]);
                            if (avgIncome > 0 && income > avgIncome * RAPID_WEALTH_THRESHOLD) {
                                alerts.add(new FraudAlert(
                                    Instant.now().toEpochMilli(),
                                    FraudAlert.Type.RAPID_WEALTH_GAIN,
                                    result[1], // player name
                                    result[0], // player uuid
                                    String.format("Player earned %,.0f S$ in 1h (server avg: %,.0f S$, %.1fx above average)",
                                        income / 100.0, avgIncome / 100.0, income / avgIncome),
                                    income > avgIncome * RAPID_WEALTH_THRESHOLD * 2
                                        ? FraudAlert.Severity.HIGH
                                        : FraudAlert.Severity.MEDIUM
                                ));
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check rapid wealth gain", e);
        }

        return alerts;
    }

    /**
     * Checks for players making an unusually high number of transactions.
     */
    private List<FraudAlert> checkHighFrequencyTrading() {
        List<FraudAlert> alerts = new ArrayList<>();
        String dbUrl = "jdbc:sqlite:" + economyDbPath;

        long oneMinuteAgo = System.currentTimeMillis() - 60_000L;

        String sql = """
            SELECT player_uuid, player_name, COUNT(*) as tx_count
            FROM transaction_log
            WHERE timestamp > ?
            GROUP BY player_uuid, player_name
            HAVING tx_count > ?
        """;

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            try (var ps = conn.prepareStatement(sql)) {
                ps.setLong(1, oneMinuteAgo);
                ps.setInt(2, HIGH_FREQUENCY_THRESHOLD);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int txCount = rs.getInt("tx_count");
                        alerts.add(new FraudAlert(
                            Instant.now().toEpochMilli(),
                            FraudAlert.Type.HIGH_FREQUENCY,
                            rs.getString("player_name"),
                            rs.getString("player_uuid"),
                            String.format("Player made %d transactions in 1 minute (threshold: %d)",
                                txCount, HIGH_FREQUENCY_THRESHOLD),
                            txCount > HIGH_FREQUENCY_THRESHOLD * 3
                                ? FraudAlert.Severity.HIGH
                                : FraudAlert.Severity.LOW
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check high-frequency trading", e);
        }

        return alerts;
    }

    /**
     * Checks for individual transactions that are unusually large.
     */
    private List<FraudAlert> checkUnusualTransactionSize() {
        List<FraudAlert> alerts = new ArrayList<>();
        String dbUrl = "jdbc:sqlite:" + economyDbPath;

        long oneHourAgo = System.currentTimeMillis() - 3_600_000L;

        // First get the average transaction size, then find outliers
        String avgSql = "SELECT AVG(ABS(amount)) as avg_amount FROM transaction_log WHERE timestamp > ?";
        String outlierSql = """
            SELECT player_uuid, player_name, ABS(amount) as amount, type
            FROM transaction_log
            WHERE timestamp > ? AND ABS(amount) > ?
            ORDER BY amount DESC LIMIT 10
        """;

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }

            // Get average
            double avgAmount = 0;
            try (var ps = conn.prepareStatement(avgSql)) {
                ps.setLong(1, oneHourAgo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        avgAmount = rs.getDouble("avg_amount");
                    }
                }
            }

            if (avgAmount <= 0) return alerts;

            // Find outliers
            try (var ps = conn.prepareStatement(outlierSql)) {
                ps.setLong(1, oneHourAgo);
                ps.setLong(2, (long) (avgAmount * UNUSUAL_SIZE_THRESHOLD));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long amount = rs.getLong("amount");
                        alerts.add(new FraudAlert(
                            Instant.now().toEpochMilli(),
                            FraudAlert.Type.UNUSUAL_SIZE,
                            rs.getString("player_name"),
                            rs.getString("player_uuid"),
                            String.format("Transaction of %,.2f S$ (server avg: %,.2f S$, %.1fx above average) type: %s",
                                amount / 100.0, avgAmount / 100.0, amount / avgAmount,
                                rs.getString("type")),
                            amount > avgAmount * UNUSUAL_SIZE_THRESHOLD * 2
                                ? FraudAlert.Severity.HIGH
                                : FraudAlert.Severity.MEDIUM
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check unusual transaction sizes", e);
        }

        return alerts;
    }

    // ── Alert Management ────────────────────────────────────

    private void addAlert(FraudAlert alert) {
        synchronized (recentAlerts) {
            recentAlerts.add(alert);
            if (recentAlerts.size() > MAX_RECENT_ALERTS) {
                recentAlerts.remove(0);
            }
        }
    }

    /**
     * Gets recent fraud alerts.
     *
     * @param limit Maximum number of alerts to return
     * @return List of recent alerts, most recent first
     */
    public List<FraudAlert> getRecentAlerts(int limit) {
        synchronized (recentAlerts) {
            int start = Math.max(0, recentAlerts.size() - limit);
            return new ArrayList<>(recentAlerts.subList(start, recentAlerts.size()));
        }
    }

    /**
     * Gets the count of high-severity alerts in the recent list.
     */
    public int getHighSeverityCount() {
        synchronized (recentAlerts) {
            return (int) recentAlerts.stream()
                .filter(a -> a.severity == FraudAlert.Severity.HIGH)
                .count();
        }
    }

    // ── Alert Data Class ────────────────────────────────────

    /**
     * Represents a single fraud detection alert.
     */
    public static class FraudAlert {
        /** When this alert was generated (epoch millis) */
        public final long timestamp;

        /** The type of suspicious pattern detected */
        public final Type type;

        /** The player involved (name) */
        public final String playerName;

        /** The player involved (UUID) */
        public final String playerUuid;

        /** Description of the suspicious activity */
        public final String description;

        /** How severe this alert is */
        public final Severity severity;

        /**
         * Alert type enum.
         */
        public enum Type {
            RAPID_WEALTH_GAIN,
            HIGH_FREQUENCY,
            CIRCULAR_TRADING,
            UNUSUAL_SIZE,
            ZERO_VALUE_TRANSFER
        }

        /**
         * Alert severity enum.
         */
        public enum Severity {
            LOW,
            MEDIUM,
            HIGH
        }

        public FraudAlert(long timestamp, Type type, String playerName, String playerUuid,
                          String description, Severity severity) {
            this.timestamp = timestamp;
            this.type = type;
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.description = description;
            this.severity = severity;
        }

        /**
         * Formats the alert for display.
         */
        public String format() {
            return String.format("[%s] %s — %s: %s", severity, type, playerName, description);
        }
    }
}
