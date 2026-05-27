package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.premium.FraudDetector.FraudAlert;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DiscordWebhookNotifier - Sends real-time economic alerts to Discord.
 *
 * <p>Posts webhook messages to a configured Discord channel when significant
 * economic events occur, such as fraud alerts, inflation warnings, or
 * economy health score changes. This allows server administrators to
 * monitor their economy even when not logged into the game.</p>
 *
 * <h3>Supported Events:</h3>
 * <ul>
 *   <li>Fraud/Anomaly alerts (HIGH severity only — avoids spam)</li>
 *   <li>Inflation warnings (when ratio exceeds threshold)</li>
 *   <li>Economy health score drops below configurable level</li>
 *   <li>Daily summary reports</li>
 * </ul>
 *
 * <h3>Configuration:</h3>
 * <p>Webhook URL and notification preferences are read from the analytics
 * config file ({@code config/solidus-analytics/analytics.properties}).</p>
 *
 * <p><b>Premium Feature:</b> This class is part of the paid Solidus Analytics
 * add-on. It requires a valid license key to function.</p>
 *
 * @since 1.0.0
 */
public class DiscordWebhookNotifier {

    // ── Configuration ───────────────────────────────────────

    private volatile String webhookUrl;
    private volatile boolean enabled = false;

    /** Minimum severity to send fraud alerts */
    private volatile FraudAlert.Severity minFraudSeverity = FraudAlert.Severity.HIGH;

    /** Whether to send inflation warnings */
    private volatile boolean notifyInflationWarnings = true;

    /** Whether to send daily summaries */
    private volatile boolean notifyDailySummary = true;

    /** Whether to send health score alerts */
    private volatile boolean notifyHealthScore = true;

    /** Health score threshold — alert when score drops below this */
    private volatile double healthScoreThreshold = 50.0;

    // ── Executor ────────────────────────────────────────────

    private final ExecutorService webhookExecutor;

    /** Rate limit: minimum milliseconds between webhook messages */
    private static final long RATE_LIMIT_MS = 2_000;

    /** Last time a webhook was sent (for rate limiting) */
    private volatile long lastWebhookSent = 0;

    public DiscordWebhookNotifier() {
        this.webhookExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Discord-Webhook");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Configuration ───────────────────────────────────────

    /**
     * Configures the webhook notifier.
     *
     * @param webhookUrl The Discord webhook URL
     * @param enabled    Whether notifications are enabled
     */
    public void configure(String webhookUrl, boolean enabled) {
        this.webhookUrl = webhookUrl;
        this.enabled = enabled && webhookUrl != null && !webhookUrl.isBlank();

        if (this.enabled) {
            SolidusAnalyticsMod.LOGGER.info("Discord webhook notifications enabled.");
        }
    }

    /**
     * Shuts down the webhook executor.
     */
    public void shutdown() {
        webhookExecutor.shutdown();
        try {
            if (!webhookExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                webhookExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            webhookExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Notification Methods ────────────────────────────────

    /**
     * Sends a fraud alert to Discord.
     *
     * @param alert The fraud alert to send
     */
    public void notifyFraudAlert(FraudAlert alert) {
        if (!enabled) return;
        if (alert.severity.ordinal() < minFraudSeverity.ordinal()) return;

        String color = switch (alert.severity) {
            case HIGH -> "15158332";    // Red
            case MEDIUM -> "16776960";  // Yellow
            case LOW -> "3447003";      // Blue
        };

        String json = buildEmbed(
            "Fraud Alert: " + alert.type,
            alert.description,
            alert.playerName,
            color
        );

        sendWebhookAsync(json);
    }

    /**
     * Sends an inflation warning to Discord.
     *
     * @param ratio      The current money-to-goods ratio
     * @param status     The economic status string
     * @param rate24h    The 24-hour inflation rate
     */
    public void notifyInflationWarning(double ratio, String status, Double rate24h) {
        if (!enabled || !notifyInflationWarnings) return;

        String color = "15158332"; // Red for inflation warning
        String rateStr = rate24h != null ? String.format("%.2f%%", rate24h) : "N/A";

        String description = String.format(
            "Money:Goods Ratio: %.1f:1\nStatus: %s\n24h Inflation Rate: %s",
            ratio, status, rateStr);

        String json = buildEmbed(
            "Inflation Warning",
            description,
            "Economy Monitor",
            color
        );

        sendWebhookAsync(json);
    }

    /**
     * Sends an economy health score alert to Discord.
     *
     * @param score The current health score
     * @param grade The letter grade
     * @param summary The summary text
     */
    public void notifyHealthScore(double score, String grade, String summary) {
        if (!enabled || !notifyHealthScore) return;
        if (score >= healthScoreThreshold) return;

        String color = score < 30 ? "15158332" : score < 50 ? "16776960" : "3447003";

        String description = String.format(
            "Health Score: %.1f/100 (Grade: %s)\n%s",
            score, grade, summary);

        String json = buildEmbed(
            "Economy Health Alert",
            description,
            "Health Monitor",
            color
        );

        sendWebhookAsync(json);
    }

    /**
     * Sends a daily summary to Discord.
     *
     * @param transactionCount Number of transactions today
     * @param volumeCents      Total volume in cents
     * @param activePlayers    Number of active players
     * @param healthScore      The economy health score
     */
    public void notifyDailySummary(long transactionCount, long volumeCents,
                                   int activePlayers, double healthScore) {
        if (!enabled || !notifyDailySummary) return;

        String color = "5763719"; // Green

        String description = String.format(
            "Transactions: %,d\nVolume: %,.2f S$\nActive Players: %d\nHealth Score: %.1f/100",
            transactionCount, volumeCents / 100.0, activePlayers, healthScore);

        String json = buildEmbed(
            "Daily Economy Summary",
            description,
            "Solidus Analytics",
            color
        );

        sendWebhookAsync(json);
    }

    // ── Webhook Implementation ──────────────────────────────

    /**
     * Sends a webhook message asynchronously with rate limiting.
     */
    private void sendWebhookAsync(String jsonPayload) {
        webhookExecutor.submit(() -> {
            try {
                // Rate limiting
                long now = System.currentTimeMillis();
                long timeSinceLast = now - lastWebhookSent;
                if (timeSinceLast < RATE_LIMIT_MS) {
                    Thread.sleep(RATE_LIMIT_MS - timeSinceLast);
                }

                HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "SolidusAnalytics/1.0");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                lastWebhookSent = System.currentTimeMillis();

                if (responseCode == 204 || responseCode == 200) {
                    SolidusAnalyticsMod.LOGGER.debug("Discord webhook sent successfully.");
                } else if (responseCode == 429) {
                    SolidusAnalyticsMod.LOGGER.warn("Discord webhook rate limited. Backing off.");
                } else {
                    SolidusAnalyticsMod.LOGGER.warn("Discord webhook returned status: {}", responseCode);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to send Discord webhook: {}", e.getMessage());
            }
        });
    }

    /**
     * Builds a Discord webhook JSON payload with an embed.
     */
    private String buildEmbed(String title, String description, String footer, String color) {
        // Escape special characters in JSON strings
        String escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedDesc = description.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n");
        String escapedFooter = footer.replace("\\", "\\\\").replace("\"", "\\\"");

        return String.format("""
            {
              "embeds": [{
                "title": "%s",
                "description": "%s",
                "color": %s,
                "footer": {
                  "text": "%s | Solidus Analytics"
                },
                "timestamp": "%s"
              }]
            }""",
            escapedTitle, escapedDesc, color, escapedFooter,
            Instant.now().toString());
    }

    // ── Accessors ───────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setMinFraudSeverity(FraudAlert.Severity severity) {
        this.minFraudSeverity = severity;
    }

    public void setNotifyInflationWarnings(boolean enabled) {
        this.notifyInflationWarnings = enabled;
    }

    public void setNotifyDailySummary(boolean enabled) {
        this.notifyDailySummary = enabled;
    }

    public void setNotifyHealthScore(boolean enabled) {
        this.notifyHealthScore = enabled;
    }

    public void setHealthScoreThreshold(double threshold) {
        this.healthScoreThreshold = threshold;
    }
}
