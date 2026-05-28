package com.solidus.analytics.engine;

import com.solidus.analytics.AnalyticsConfig;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.dashboard.DashboardManager;
import com.solidus.analytics.integration.SolidusIntegration;
import com.solidus.analytics.license.LicenseVerifier;
import com.solidus.analytics.premium.DiscordWebhookNotifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.premium.WeeklyReportGenerator;
import com.solidus.analytics.storage.AnalyticsDatabase;

import java.nio.file.Path;

/**
 * AnalyticsEngine - Central coordinator for the Solidus Analytics subsystem.
 *
 * <p>Owns and coordinates all analytics components: the database, live metrics
 * tracker, snapshot scheduler, inflation calculator, and premium features
 * (license verifier, health score, fraud detector, Discord notifier,
 * weekly report generator).</p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>Construction — lightweight, no I/O</li>
 *   <li>initialize() — creates DB, starts polling, registers tick handlers</li>
 *   <li>Runtime — commands query live metrics, snapshots, inflation data</li>
 *   <li>shutdown() — stops polling, flushes data, closes connections</li>
 * </ol>
 *
 * @since 1.0.0
 */
public class AnalyticsEngine {

    // ── Core Components ─────────────────────────────────────

    private AnalyticsDatabase database;
    private LiveMetricsTracker liveMetrics;
    private SnapshotScheduler snapshotScheduler;
    private InflationCalculator inflationCalculator;
    private AnalyticsConfig config;

    // ── Premium Components ──────────────────────────────────

    private LicenseVerifier licenseVerifier;
    private EconomyHealthScore healthScore;
    private FraudDetector fraudDetector;
    private DiscordWebhookNotifier discordNotifier;
    private WeeklyReportGenerator weeklyReportGenerator;

    // ── Dashboard Components ─────────────────────────────────

    private DashboardManager dashboardManager;

    private volatile boolean initialized = false;
    private volatile boolean premiumEnabled = false;

    /** Whether the Solidus API integration is available */
    private volatile boolean apiIntegrationAvailable = false;

    /** Paths to the Solidus databases */
    private String economyDbPath;
    private String auctionsDbPath;

    /** Path to the config directory */
    private Path configDirPath;

    /** Tick counter for periodic cleanup */
    private int cleanupTickCounter = 0;

    /** How often to run data cleanup (in ticks: 720000 = ~10 hours) */
    private static final int CLEANUP_INTERVAL_TICKS = 720_000;

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

        configDirPath = Path.of(configDir);

        // ── Step 1: Load configuration ──
        config = new AnalyticsConfig(configDirPath);
        config.load();

        // ── Step 2: Initialize Solidus API integration ──
        apiIntegrationAvailable = SolidusIntegration.initialize();

        // ── Step 3: Resolve database paths ──
        Path solidusConfigDir = Path.of(".").toAbsolutePath().resolve("config").resolve("solidus");
        economyDbPath = solidusConfigDir.resolve("economy.db").toAbsolutePath().toString();
        auctionsDbPath = solidusConfigDir.resolve("auctions.db").toAbsolutePath().toString();

        SolidusAnalyticsMod.LOGGER.info("Economy DB path: {}", economyDbPath);
        SolidusAnalyticsMod.LOGGER.info("Auctions DB path: {}", auctionsDbPath);

        // ── Step 4: Initialize analytics database ──
        database = new AnalyticsDatabase(configDir);
        database.initialize();

        if (!database.isInitialized()) {
            SolidusAnalyticsMod.LOGGER.error("Analytics database failed to initialize. Engine will not start.");
            return;
        }

        // ── Step 5: Initialize live metrics tracker ──
        liveMetrics = new LiveMetricsTracker(database, economyDbPath);
        liveMetrics.start();

        // ── Step 6: Initialize snapshot scheduler ──
        snapshotScheduler = new SnapshotScheduler(database, economyDbPath, auctionsDbPath);
        snapshotScheduler.setEngineRef(this);

        // ── Step 7: Initialize inflation calculator ──
        inflationCalculator = new InflationCalculator(database, economyDbPath, auctionsDbPath);

        // ── Step 8: Initialize weekly report generator (works for all users) ──
        weeklyReportGenerator = new WeeklyReportGenerator(this, configDirPath);

        // ── Step 9: Initialize premium features ──
        initializePremium(configDirPath);

        // ── Step 10: Initialize dashboard manager ──
        dashboardManager = new DashboardManager(this, configDirPath);
        dashboardManager.initialize();

        initialized = true;
        SolidusAnalyticsMod.LOGGER.info("Solidus Analytics Engine initialized successfully.");
        SolidusAnalyticsMod.LOGGER.info("API Integration: {} | Mode: {}",
            apiIntegrationAvailable ? "ACTIVE" : "UNAVAILABLE",
            apiIntegrationAvailable ? "Full Integration" : "Standalone (DB-only)");
        SolidusAnalyticsMod.LOGGER.info("Premium Features: {}", premiumEnabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Initializes premium features (license verification, health score, fraud detector, Discord).
     *
     * <p>License verification is now fully local (HMAC-SHA256 offline verification).
     * No network access or remote server is needed. Verification happens instantly
     * and synchronously during engine initialization.</p>
     */
    private void initializePremium(Path configDir) {
        // Initialize license verifier (local verification — no network needed)
        licenseVerifier = new LicenseVerifier(configDir);

        // Verify synchronously (instant — no CompletableFuture)
        LicenseVerifier.VerificationState state = licenseVerifier.initialize();
        premiumEnabled = licenseVerifier.isPremiumEnabled();

        if (premiumEnabled) {
            SolidusAnalyticsMod.LOGGER.info("Premium license verified. Activating premium features...");

            // Initialize premium components
            healthScore = new EconomyHealthScore(this);
            fraudDetector = new FraudDetector(this, economyDbPath);
            discordNotifier = new DiscordWebhookNotifier();

            // Configure Discord notifier from config
            if (config.isDiscordEnabled()) {
                discordNotifier.configure(config.getDiscordWebhookUrl(), true);
                discordNotifier.setNotifyFraud(config.isNotifyFraud());
                discordNotifier.setFraudMinSeverity(config.getFraudMinSeverity());
                discordNotifier.setNotifyInflationWarnings(config.isNotifyInflation());
                discordNotifier.setNotifyDailySummary(config.isNotifyDailySummary());
                discordNotifier.setNotifyHealthScore(config.isNotifyHealthScore());
                discordNotifier.setHealthScoreThreshold(config.getHealthScoreAlertThreshold());
            }

            // Run initial fraud scan
            database.getExecutor().submit(() -> {
                try {
                    fraudDetector.runAllChecks();
                } catch (Exception e) {
                    SolidusAnalyticsMod.LOGGER.error("Initial fraud scan failed", e);
                }
            });
        } else {
            SolidusAnalyticsMod.LOGGER.info("No valid premium license. Premium features disabled. State: {}", state);
        }
    }

    /**
     * Shuts down the analytics engine gracefully.
     * Stops all polling, flushes pending data, and closes connections.
     */
    public void shutdown() {
        if (!initialized) return;

        SolidusAnalyticsMod.LOGGER.info("Shutting down Solidus Analytics Engine...");

        // Shut down dashboard manager first (stops web server + GitHub publisher)
        if (dashboardManager != null) {
            dashboardManager.shutdown();
        }

        // Stop live metrics polling
        if (liveMetrics != null) {
            liveMetrics.stop();
        }

        // Shut down premium components
        if (licenseVerifier != null) {
            licenseVerifier.shutdown();
        }
        if (discordNotifier != null) {
            discordNotifier.shutdown();
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
     * periodic scheduling (e.g., snapshot scheduler, cleanup).
     *
     * @param currentTick The current server tick count
     */
    public void onServerTick(int currentTick) {
        if (!initialized) return;

        if (snapshotScheduler != null) {
            snapshotScheduler.onTick(currentTick);
        }

        // Dashboard periodic data publishing
        if (dashboardManager != null) {
            dashboardManager.onTick(currentTick);
        }

        // Periodic data cleanup
        cleanupTickCounter++;
        if (cleanupTickCounter >= CLEANUP_INTERVAL_TICKS) {
            cleanupTickCounter = 0;
            if (database != null && config != null) {
                database.getExecutor().submit(() -> database.runCleanup(config.getDataRetentionDays()));
            }
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

    public AnalyticsConfig getConfig() {
        return config;
    }

    // ── Premium Accessors ──────────────────────────────────

    public boolean isPremiumEnabled() {
        return premiumEnabled;
    }

    public LicenseVerifier getLicenseVerifier() {
        return licenseVerifier;
    }

    public EconomyHealthScore getHealthScore() {
        return healthScore;
    }

    public FraudDetector getFraudDetector() {
        return fraudDetector;
    }

    public DiscordWebhookNotifier getDiscordNotifier() {
        return discordNotifier;
    }

    public WeeklyReportGenerator getWeeklyReportGenerator() {
        return weeklyReportGenerator;
    }

    public boolean isApiIntegrationAvailable() {
        return apiIntegrationAvailable;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Path getConfigDirPath() {
        return configDirPath;
    }

    // ── Dashboard Accessor ──────────────────────────────────

    public DashboardManager getDashboardManager() {
        return dashboardManager;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("AnalyticsEngine accessed before initialization!");
        }
    }
}
