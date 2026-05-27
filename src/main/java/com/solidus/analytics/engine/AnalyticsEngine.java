package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.integration.SolidusIntegration;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.nio.file.Path;

/**
 * AnalyticsEngine - Central coordinator for the Solidus Analytics subsystem.
 *
 * <p>Owns and coordinates all analytics components: the database, live metrics
 * tracker, snapshot scheduler, and inflation calculator. Follows the same
 * architectural pattern as Solidus Core's EconomyEngine — lightweight construction
 * with heavy initialization in a separate {@link #initialize()} call.</p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>Construction — lightweight, no I/O</li>
 *   <li>initialize() — creates DB, starts polling, registers tick handlers</li>
 *   <li>Runtime — commands query live metrics, snapshots, inflation data</li>
 *   <li>shutdown() — stops polling, flushes data, closes connections</li>
 * </ol>
 *
 * <h3>Integration Modes:</h3>
 * <ul>
 *   <li><b>Full Integration:</b> Solidus is loaded and API is available.
 *       Analytics uses both the API (for live data) and direct DB reads
 *       (for historical data and batch computations).</li>
 *   <li><b>Standalone Mode:</b> Solidus is NOT loaded or API is unavailable.
 *       Analytics reads the economy.db and auctions.db directly via JDBC.
 *       All features work, just without the real-time API shortcut methods.</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class AnalyticsEngine {

    private AnalyticsDatabase database;
    private LiveMetricsTracker liveMetrics;
    private SnapshotScheduler snapshotScheduler;
    private InflationCalculator inflationCalculator;

    private volatile boolean initialized = false;

    /** Whether the Solidus API integration is available */
    private volatile boolean apiIntegrationAvailable = false;

    /** Paths to the Solidus databases */
    private String economyDbPath;
    private String auctionsDbPath;

    public AnalyticsEngine() {
        // Construction is lightweight; actual work happens in initialize()
    }

    /**
     * Initializes the analytics engine and all subsystems.
     * Must be called once during mod startup.
     *
     * @param configDir The config directory path (e.g., ./config/solidus-analytics/)
     */
    public void initialize(String configDir) {
        SolidusAnalyticsMod.LOGGER.info("Initializing Solidus Analytics Engine...");

        // ── Step 1: Initialize Solidus API integration ──
        apiIntegrationAvailable = SolidusIntegration.initialize();

        // ── Step 2: Resolve database paths ──
        // The Solidus databases are in the server's run directory under config/solidus/
        // We need to read them for analytics queries
        Path solidusConfigDir = Path.of(".").toAbsolutePath().resolve("config").resolve("solidus");
        economyDbPath = solidusConfigDir.resolve("economy.db").toAbsolutePath().toString();
        auctionsDbPath = solidusConfigDir.resolve("auctions.db").toAbsolutePath().toString();

        SolidusAnalyticsMod.LOGGER.info("Economy DB path: {}", economyDbPath);
        SolidusAnalyticsMod.LOGGER.info("Auctions DB path: {}", auctionsDbPath);

        // ── Step 3: Initialize analytics database ──
        database = new AnalyticsDatabase(configDir);
        database.initialize();

        // ── Step 4: Initialize live metrics tracker ──
        liveMetrics = new LiveMetricsTracker(database, economyDbPath);
        liveMetrics.start();

        // ── Step 5: Initialize snapshot scheduler ──
        snapshotScheduler = new SnapshotScheduler(database, economyDbPath, auctionsDbPath);

        // ── Step 6: Initialize inflation calculator ──
        inflationCalculator = new InflationCalculator(database, economyDbPath, auctionsDbPath);

        initialized = true;
        SolidusAnalyticsMod.LOGGER.info("Solidus Analytics Engine initialized successfully.");
        SolidusAnalyticsMod.LOGGER.info("API Integration: {} | Mode: {}",
            apiIntegrationAvailable ? "ACTIVE" : "UNAVAILABLE",
            apiIntegrationAvailable ? "Full Integration" : "Standalone (DB-only)");
    }

    /**
     * Shuts down the analytics engine gracefully.
     * Stops all polling, flushes pending data, and closes connections.
     */
    public void shutdown() {
        if (!initialized) return;

        SolidusAnalyticsMod.LOGGER.info("Shutting down Solidus Analytics Engine...");

        // Stop live metrics polling first
        if (liveMetrics != null) {
            liveMetrics.stop();
        }

        // Close the analytics database (flushes and closes connection)
        if (database != null) {
            database.shutdown();
        }

        initialized = false;
        SolidusAnalyticsMod.LOGGER.info("Solidus Analytics Engine shut down complete.");
    }

    // ── Server Tick Handler ────────────────────────────────

    /**
     * Called on every server tick. Delegates to sub-components that need
     * periodic scheduling (e.g., snapshot scheduler).
     *
     * @param currentTick The current server tick count
     */
    public void onServerTick(int currentTick) {
        if (!initialized) return;

        if (snapshotScheduler != null) {
            snapshotScheduler.onTick(currentTick);
        }
    }

    // ── Accessors ──────────────────────────────────────────

    public AnalyticsDatabase getDatabase() {
        ensureInitialized();
        return database;
    }

    public LiveMetricsTracker getLiveMetrics() {
        ensureInitialized();
        return liveMetrics;
    }

    public SnapshotScheduler getSnapshotScheduler() {
        ensureInitialized();
        return snapshotScheduler;
    }

    public InflationCalculator getInflationCalculator() {
        ensureInitialized();
        return inflationCalculator;
    }

    public boolean isApiIntegrationAvailable() {
        return apiIntegrationAvailable;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("AnalyticsEngine accessed before initialization!");
        }
    }
}
