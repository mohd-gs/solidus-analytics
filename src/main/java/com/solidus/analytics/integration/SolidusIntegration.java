package com.solidus.analytics.integration;

import com.solidus.analytics.SolidusAnalyticsMod;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SolidusIntegration - Reflection-based bridge to the Solidus economy API.
 *
 * <p>This class provides ZERO compile-time dependency on Solidus Core.
 * All access to Solidus internals is done via Java reflection, following
 * the pattern documented in SolidusAPI's Javadoc. If Solidus is not loaded,
 * every method gracefully returns null/empty results instead of crashing.</p>
 *
 * <h3>Design Rationale:</h3>
 * <ul>
 *   <li>No compile dependency on Solidus JAR — this mod can be built independently</li>
 *   <li>Reflection calls are cached on first use for acceptable performance</li>
 *   <li>All methods are null-safe — callers must check for null returns</li>
 *   <li>Singleton pattern mirrors SolidusAPI's own design</li>
 * </ul>
 *
 * <h3>Thread Safety:</h3>
 * All reflected API calls return CompletableFuture and execute on Solidus's
 * dedicated database worker thread. Callers on the server tick thread must
 * use {@code .thenAccept()} + {@code server.execute()} for any game-state updates.
 */
public final class SolidusIntegration {

    private static volatile SolidusIntegration instance;

    private final Object apiInstance;
    private final Class<?> apiClass;

    // Cached method handles (resolved once, reused forever)
    private Method getTopBalancesMethod;
    private Method getTransactionLogMethod;
    private Method getTransactionsMethod;
    private Method getEconomyEngineMethod;
    private Method getStorageMethod;
    private Method getCachedPlayerCountMethod;

    /** Path to the economy database for direct fallback queries */
    private volatile String economyDbPath;

    private SolidusIntegration(Object apiInstance, Class<?> apiClass) {
        this.apiInstance = apiInstance;
        this.apiClass = apiClass;
        cacheMethodHandles();
    }

    /**
     * Initializes the integration bridge. Called once during mod startup.
     * If Solidus is not loaded, the integration remains unavailable.
     */
    public static synchronized boolean initialize() {
        if (instance != null) {
            SolidusAnalyticsMod.LOGGER.warn("SolidusIntegration already initialized.");
            return true;
        }

        // Check if Solidus is loaded
        if (!FabricLoader.getInstance().isModLoaded("solidus")) {
            SolidusAnalyticsMod.LOGGER.warn(
                "Solidus is NOT loaded. Solidus Analytics will operate in standalone mode "
                + "(reads databases directly, no API access).");
            return false;
        }

        try {
            // Get the API class and instance via reflection
            Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            Object apiInstance = getInstanceMethod.invoke(null);

            if (apiInstance == null) {
                SolidusAnalyticsMod.LOGGER.warn(
                    "Solidus is loaded but SolidusAPI.getInstance() returned null. "
                    + "Solidus may not be fully initialized yet.");
                return false;
            }

            instance = new SolidusIntegration(apiInstance, apiClass);
            SolidusAnalyticsMod.LOGGER.info(
                "SolidusIntegration initialized successfully. Connected to Solidus API.");
            return true;

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error(
                "Failed to initialize SolidusIntegration. Analytics will use DB-only mode.", e);
            return false;
        }
    }

    /**
     * Checks whether the Solidus API integration is available.
     */
    public static boolean isAvailable() {
        return instance != null && instance.apiInstance != null;
    }

    /**
     * Gets the integration singleton.
     *
     * @return The integration instance, or null if Solidus is not available
     */
    public static SolidusIntegration getInstance() {
        return instance;
    }

    // ── Reflected API Methods ──────────────────────────────

    /**
     * Gets the top N players by balance via the Solidus API.
     *
     * @param limit Maximum number of entries
     * @return CompletableFuture with a list of balance entries, or null on failure
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<?>> getTopBalances(int limit) {
        if (!isAvailable() || getTopBalancesMethod == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return (CompletableFuture<List<?>>) getTopBalancesMethod.invoke(apiInstance, limit);
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to call getTopBalances via reflection", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Gets the TransactionLog instance from Solidus.
     *
     * @return The TransactionLog object, or null if unavailable
     */
    public Object getTransactionLog() {
        if (!isAvailable()) return null;
        try {
            return getTransactionLogMethod.invoke(apiInstance);
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to call getTransactionLog via reflection", e);
            return null;
        }
    }

    /**
     * Gets recent transactions for a player via the Solidus API.
     *
     * @param playerUuid The player's UUID
     * @param limit      Maximum number of entries
     * @return CompletableFuture with a list of transaction entries, or null on failure
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<?>> getTransactions(UUID playerUuid, int limit) {
        if (!isAvailable() || getTransactionsMethod == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Object transactionLog = getTransactionLog();
            if (transactionLog == null) return CompletableFuture.completedFuture(null);
            return (CompletableFuture<List<?>>) getTransactionsMethod.invoke(transactionLog, playerUuid, limit);
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to call getTransactions via reflection", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Gets the cached player count from Solidus's SQLiteStorage.
     * Falls back to a direct database query if the reflected method is unavailable.
     *
     * @return The number of cached players, or -1 if unavailable
     */
    public int getCachedPlayerCount() {
        // Strategy 1: Try reflected API method (if available)
        if (isAvailable() && getCachedPlayerCountMethod != null) {
            try {
                Object engine = getEconomyEngineMethod.invoke(apiInstance);
                if (engine == null) return -1;
                Object storage = getStorageMethod.invoke(engine);
                if (storage == null) return -1;
                return (int) getCachedPlayerCountMethod.invoke(storage);
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.debug("Reflected getCachedPlayerCount failed, trying DB fallback", e);
            }
        }

        // Strategy 2: Direct database fallback (always available)
        return getPlayerCountFromDB();
    }

    /**
     * Sets the economy database path for direct fallback queries.
     * Called by AnalyticsEngine after database paths are resolved.
     *
     * @param economyDbPath The path to the economy.db file
     */
    public void setEconomyDbPath(String economyDbPath) {
        this.economyDbPath = economyDbPath;
    }

    /**
     * Gets the player count directly from the economy database.
     * This is a fallback for when the reflected API method is unavailable.
     *
     * @return The number of players in the database, or -1 on error
     */
    private int getPlayerCountFromDB() {
        if (economyDbPath == null) return -1;

        String dbUrl = "jdbc:sqlite:" + economyDbPath;
        String sql = "SELECT COUNT(*) as player_count FROM player_balances";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt("player_count");
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.debug("Failed to query player count from economy.db", e);
        }
        return -1;
    }

    // ── Internal ───────────────────────────────────────────

    private void cacheMethodHandles() {
        try {
            // SolidusAPI methods
            getTopBalancesMethod = apiClass.getMethod("getTopBalances", int.class);
            getTransactionLogMethod = apiClass.getMethod("getTransactionLog");
            getEconomyEngineMethod = apiClass.getMethod("getEconomyEngine");

            // EconomyEngine methods
            Class<?> engineClass = Class.forName("com.solidus.economy.EconomyEngine");
            getStorageMethod = engineClass.getMethod("getStorage");

            // SQLiteStorage methods
            Class<?> storageClass = Class.forName("com.solidus.economy.SQLiteStorage");
            try {
                getCachedPlayerCountMethod = storageClass.getMethod("getCachedPlayerCount");
            } catch (NoSuchMethodException e) {
                // This method may not exist in older versions of Solidus Core.
                // The getCachedPlayerCount() method will use a direct DB query fallback.
                SolidusAnalyticsMod.LOGGER.info(
                    "SQLiteStorage.getCachedPlayerCount() not found in Solidus Core. "
                    + "Will use direct DB query fallback for player counts.");
            }

            // TransactionLog methods
            Class<?> transactionLogClass = Class.forName("com.solidus.economy.TransactionLog");
            getTransactionsMethod = transactionLogClass.getMethod("getTransactions", UUID.class, int.class);

            SolidusAnalyticsMod.LOGGER.info("All Solidus API method handles cached successfully.");

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error(
                "Failed to cache Solidus API method handles. Some features may be unavailable.", e);
        }
    }
}
