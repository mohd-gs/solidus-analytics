package com.solidus.analytics;

import com.solidus.analytics.commands.AnalyticsCommand;
import com.solidus.analytics.commands.InflationCommand;
import com.solidus.analytics.commands.PremiumCommand;
import com.solidus.analytics.engine.AnalyticsEngine;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solidus Analytics - Data Analytics Engine for Solidus Economy.
 *
 * <p>A companion Fabric mod that provides comprehensive economic analytics
 * for servers running the Solidus economy system. Features include real-time
 * dashboards, wealth distribution tracking (Gini coefficient), inflation
 * monitoring, and historical trend analysis.</p>
 *
 * <h3>Architecture: 100% Server-Side Only</h3>
 * <p>Like Solidus Core, this mod operates entirely server-side. No custom
 * textures, no client-side dependencies. Players interact via chat commands
 * ({@code /analytics}, {@code /inflation}).</p>
 *
 * <h3>Integration with Solidus Core:</h3>
 * <p>Uses reflection-based access to SolidusAPI (zero compile dependency).
 * If Solidus is not loaded, the mod operates in standalone mode, reading
 * the economy databases directly via JDBC. All features work in both modes.</p>
 *
 * <h3>The Three Algorithms:</h3>
 * <ol>
 *   <li><b>Live Metrics Tracker</b> — Incremental counters updated by polling
 *       the transaction log every 30 seconds. Provides O(1) reads for the
 *       live dashboard.</li>
 *   <li><b>Snapshot Scheduler</b> — Periodic computation of wealth distribution
 *       metrics (Gini coefficient, average/median balance, top-1% share).
 *       Snapshots are stored every 30 minutes for trend analysis.</li>
 *   <li><b>Inflation Calculator</b> — Money-to-Goods ratio computation and
 *       inflation rate tracking over 24h, 7d, and 30d periods.</li>
 * </ol>
 *
 * @since 1.0.0
 */
public class SolidusAnalyticsMod implements DedicatedServerModInitializer {

    public static final String MOD_ID = "solidus-analytics";
    public static final String MOD_NAME = "Solidus Analytics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static AnalyticsEngine analyticsEngine;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Solidus Analytics is initializing...");

        // Create the analytics engine
        analyticsEngine = new AnalyticsEngine();

        // Register server started event — initialize engine after Solidus is ready
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Resolve the config directory for this mod
            java.nio.file.Path configDir = java.nio.file.Path.of(".")
                .toAbsolutePath()
                .resolve("config")
                .resolve("solidus-analytics");

            // Ensure config directory exists
            try {
                java.nio.file.Files.createDirectories(configDir);
            } catch (java.io.IOException e) {
                LOGGER.error("Failed to create config directory: {}", configDir, e);
            }

            // Initialize the engine
            analyticsEngine.initialize(configDir.toAbsolutePath().toString());

            LOGGER.info("Solidus Analytics initialized successfully.");
        });

        // Register server tick for snapshot scheduling
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (analyticsEngine.isInitialized()) {
                analyticsEngine.onServerTick(server.getTickCount());
            }
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AnalyticsCommand.register(dispatcher, analyticsEngine);
            InflationCommand.register(dispatcher, analyticsEngine);
            PremiumCommand.register(dispatcher, analyticsEngine);
        });

        // Register server shutdown hook
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Solidus Analytics is shutting down...");
            analyticsEngine.shutdown();
            LOGGER.info("Solidus Analytics shutdown complete. All data saved.");
        });

        LOGGER.info("Solidus Analytics mod entry point registered. "
            + "Engine will initialize after server start.");
    }

    // ── Static Accessors ───────────────────────────────────

    /**
     * Gets the analytics engine instance.
     *
     * @return The analytics engine, or null if not yet initialized
     */
    public static AnalyticsEngine getAnalyticsEngine() {
        return analyticsEngine;
    }
}
