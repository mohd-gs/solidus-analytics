package com.solidus.analytics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * AnalyticsConfig - Configuration manager for Solidus Analytics.
 *
 * <p>Reads and writes configuration from a Java Properties file at
 * {@code config/solidus-analytics/analytics.properties}. Provides sensible
 * defaults for all settings, so the mod works out of the box even without
 * a config file.</p>
 *
 * <h3>Configurable Settings:</h3>
 * <ul>
 *   <li>Snapshot interval (how often to take economy snapshots)</li>
 *   <li>Polling interval (how often to check for new transactions)</li>
 *   <li>Data retention period (how long to keep old data)</li>
 *   <li>Discord webhook URL and notification preferences</li>
 *   <li>Fraud detection sensitivity</li>
 *   <li>Health score alert threshold</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class AnalyticsConfig {

    // ── Default Values ──────────────────────────────────────

    /** Default snapshot interval in minutes */
    private static final int DEFAULT_SNAPSHOT_INTERVAL_MINUTES = 30;

    /** Default polling interval in seconds */
    private static final int DEFAULT_POLLING_INTERVAL_SECONDS = 30;

    /** Default data retention in days */
    private static final int DEFAULT_DATA_RETENTION_DAYS = 90;

    /** Default cleanup interval in hours */
    private static final int DEFAULT_CLEANUP_INTERVAL_HOURS = 24;

    // ── Fields ──────────────────────────────────────────────

    private final Path configPath;
    private final Properties properties;

    // ── Snapshot Settings ───────────────────────────────────

    private int snapshotIntervalMinutes = DEFAULT_SNAPSHOT_INTERVAL_MINUTES;

    // ── Polling Settings ────────────────────────────────────

    private int pollingIntervalSeconds = DEFAULT_POLLING_INTERVAL_SECONDS;

    // ── Data Retention ──────────────────────────────────────

    private int dataRetentionDays = DEFAULT_DATA_RETENTION_DAYS;
    private int cleanupIntervalHours = DEFAULT_CLEANUP_INTERVAL_HOURS;

    // ── Discord Webhook ─────────────────────────────────────

    private String discordWebhookUrl = "";
    private boolean discordEnabled = false;
    private boolean notifyFraud = true;
    private boolean notifyInflation = true;
    private boolean notifyDailySummary = true;
    private boolean notifyHealthScore = true;
    private double healthScoreAlertThreshold = 50.0;
    private String fraudMinSeverity = "HIGH";



    /**
     * Constructs a new AnalyticsConfig.
     *
     * @param configDir The config directory path
     */
    public AnalyticsConfig(Path configDir) {
        this.configPath = configDir.resolve("analytics.properties");
        this.properties = new Properties();
    }

    /**
     * Loads configuration from the properties file.
     * Creates a default config file if one doesn't exist.
     */
    public void load() {
        // Load existing config or create default
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                properties.load(is);
                SolidusAnalyticsMod.LOGGER.info("Loaded config from {}", configPath);
            } catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to load config, using defaults", e);
            }
        } else {
            SolidusAnalyticsMod.LOGGER.info("No config file found. Creating default config at {}", configPath);
            createDefaultConfig();
        }

        // Apply loaded values
        snapshotIntervalMinutes = getInt("snapshot.interval.minutes", DEFAULT_SNAPSHOT_INTERVAL_MINUTES);
        pollingIntervalSeconds = getInt("polling.interval.seconds", DEFAULT_POLLING_INTERVAL_SECONDS);
        dataRetentionDays = getInt("data.retention.days", DEFAULT_DATA_RETENTION_DAYS);
        cleanupIntervalHours = getInt("cleanup.interval.hours", DEFAULT_CLEANUP_INTERVAL_HOURS);

        discordWebhookUrl = properties.getProperty("discord.webhook.url", "");
        discordEnabled = getBoolean("discord.enabled", false);
        notifyFraud = getBoolean("discord.notify.fraud", true);
        notifyInflation = getBoolean("discord.notify.inflation", true);
        notifyDailySummary = getBoolean("discord.notify.daily_summary", true);
        notifyHealthScore = getBoolean("discord.notify.health_score", true);
        healthScoreAlertThreshold = getDouble("discord.health_score.threshold", 50.0);
        fraudMinSeverity = properties.getProperty("discord.fraud.min_severity", "HIGH");

        // Note: premium.enabled config is removed — premium is controlled
        // exclusively by the license key file (config/solidus-analytics/license.key)
    }

    /**
     * Saves the current configuration to the properties file.
     */
    public void save() {
        try (OutputStream os = Files.newOutputStream(configPath)) {
            properties.store(os, "Solidus Analytics Configuration\n"
                + "Modifying this file while the server is running may not take effect until restart.");
            SolidusAnalyticsMod.LOGGER.info("Saved config to {}", configPath);
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * Creates a default configuration file with comments.
     */
    private void createDefaultConfig() {
        Properties defaults = new Properties();

        // Snapshot settings
        defaults.setProperty("snapshot.interval.minutes", String.valueOf(DEFAULT_SNAPSHOT_INTERVAL_MINUTES));

        // Polling settings
        defaults.setProperty("polling.interval.seconds", String.valueOf(DEFAULT_POLLING_INTERVAL_SECONDS));

        // Data retention
        defaults.setProperty("data.retention.days", String.valueOf(DEFAULT_DATA_RETENTION_DAYS));
        defaults.setProperty("cleanup.interval.hours", String.valueOf(DEFAULT_CLEANUP_INTERVAL_HOURS));

        // Discord integration
        defaults.setProperty("discord.enabled", "false");
        defaults.setProperty("discord.webhook.url", "");
        defaults.setProperty("discord.notify.fraud", "true");
        defaults.setProperty("discord.notify.inflation", "true");
        defaults.setProperty("discord.notify.daily_summary", "true");
        defaults.setProperty("discord.notify.health_score", "true");
        defaults.setProperty("discord.health_score.threshold", "50.0");
        defaults.setProperty("discord.fraud.min_severity", "HIGH");

        // Premium
        // No premium.enabled config — premium is controlled by license key only

        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream os = Files.newOutputStream(configPath)) {
                defaults.store(os, """
                    Solidus Analytics Configuration
                    ================================
                    This file controls the behavior of Solidus Analytics.
                    Changes take effect after server restart unless noted otherwise.

                    Snapshot Settings:
                      snapshot.interval.minutes — How often to take economy snapshots (default: 30)

                    Polling Settings:
                      polling.interval.seconds — How often to check for new transactions (default: 30)

                    Data Retention:
                      data.retention.days — How many days of data to keep (default: 90)
                      cleanup.interval.hours — How often to run data cleanup (default: 24)

                    Discord Integration (Premium):
                      discord.enabled — Enable/disable Discord notifications (default: false)
                      discord.webhook.url — Your Discord webhook URL
                      discord.notify.fraud — Send fraud alerts (default: true)
                      discord.notify.inflation — Send inflation warnings (default: true)
                      discord.notify.daily_summary — Send daily economy summaries (default: true)
                      discord.notify.health_score — Send health score alerts (default: true)
                      discord.health_score.threshold — Alert when score drops below this (default: 50.0)
                      discord.fraud.min_severity — Min severity for fraud alerts: LOW, MEDIUM, HIGH (default: HIGH)

                    Premium Features:
                      Premium is controlled by the license key file, not this config.
                      Place your license key in: config/solidus-analytics/license.key
                    """);
            }
            // Copy defaults into the active properties
            properties.putAll(defaults);
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to create default config file", e);
        }
    }

    // ── Helper Methods ──────────────────────────────────────

    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    private double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ── Getters (synchronized for thread safety) ──────────

    public synchronized int getSnapshotIntervalMinutes() { return snapshotIntervalMinutes; }
    public synchronized int getPollingIntervalSeconds() { return pollingIntervalSeconds; }
    public synchronized int getDataRetentionDays() { return dataRetentionDays; }
    public synchronized int getCleanupIntervalHours() { return cleanupIntervalHours; }
    public synchronized String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public synchronized boolean isDiscordEnabled() { return discordEnabled; }
    public synchronized boolean isNotifyFraud() { return notifyFraud; }
    public synchronized boolean isNotifyInflation() { return notifyInflation; }
    public synchronized boolean isNotifyDailySummary() { return notifyDailySummary; }
    public synchronized boolean isNotifyHealthScore() { return notifyHealthScore; }
    public synchronized double getHealthScoreAlertThreshold() { return healthScoreAlertThreshold; }
    public synchronized String getFraudMinSeverity() { return fraudMinSeverity; }


    // ── Setters (synchronized for thread safety) ───────────

    public synchronized void setSnapshotIntervalMinutes(int minutes) {
        this.snapshotIntervalMinutes = minutes;
        properties.setProperty("snapshot.interval.minutes", String.valueOf(minutes));
    }

    public synchronized void setDiscordWebhookUrl(String url) {
        this.discordWebhookUrl = url;
        properties.setProperty("discord.webhook.url", url);
    }

    public synchronized void setDiscordEnabled(boolean enabled) {
        this.discordEnabled = enabled;
        properties.setProperty("discord.enabled", String.valueOf(enabled));
    }


}
