package com.solidus.analytics.dashboard;

import com.solidus.analytics.AnalyticsConfig;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * DashboardManager - Central coordinator for the web dashboard system.
 *
 * <p>Manages the lifecycle of both publishing modes:</p>
 * <ul>
 *   <li><b>GitHub Pages mode</b> — Pushes encrypted data to GitHub (no VPS needed)</li>
 *   <li><b>Embedded server mode</b> — Serves dashboard from NanoHTTPD (for VPS users)</li>
 * </ul>
 *
 * <h3>Data Flow:</h3>
 * <pre>
 *   AnalyticsEngine
 *        │
 *        ▼
 *   DashboardDataBuilder.buildJson() → raw JSON data
 *        │
 *        ├──► DashboardEncryption.encrypt() → encrypted data
 *        │         │
 *        │         ▼
 *        │   GitHubDataPublisher.publishAsync() → GitHub Pages
 *        │
 *        └──► AnalyticsWebServer (direct API) → embedded web server
 * </pre>
 *
 * <h3>Scheduling:</h3>
 * <p>Data is published at a configurable interval (default: every 60 seconds).
 * The scheduling is driven by the server tick event via AnalyticsEngine.</p>
 *
 * @since 1.1.0
 */
public class DashboardManager {

    // ── Components ──────────────────────────────────────────

    private final DashboardEncryption encryption;
    private final GitHubDataPublisher githubPublisher;
    private AnalyticsWebServer webServer;

    // ── Configuration ───────────────────────────────────────

    private volatile boolean githubPublishEnabled = false;
    private volatile boolean webServerEnabled = false;
    private volatile int publishIntervalSeconds = 60;

    // ── State ───────────────────────────────────────────────

    private final AnalyticsEngine engine;
    private final Path configDir;
    private final Path dashboardConfigPath;

    /** Tick counter for periodic publishing */
    private int tickCounter = 0;

    /** How often to publish data (in ticks: 1200 = ~60 seconds) */
    private int publishIntervalTicks = 1200;

    /** Dashboard configuration properties */
    private final Properties dashboardProps;

    public DashboardManager(AnalyticsEngine engine, Path configDir) {
        this.engine = engine;
        this.configDir = configDir;
        this.dashboardConfigPath = configDir.resolve("dashboard.properties");
        this.encryption = new DashboardEncryption();
        this.githubPublisher = new GitHubDataPublisher();
        this.dashboardProps = new Properties();
    }

    /**
     * Initializes the dashboard manager. Loads configuration and
     * starts enabled services.
     */
    public void initialize() {
        loadConfig();
        applyConfig();

        SolidusAnalyticsMod.LOGGER.info("Dashboard Manager initialized. "
            + "GitHub: {} | Web Server: {} | Encryption: {}",
            githubPublishEnabled ? "ON" : "OFF",
            webServerEnabled ? "ON" : "OFF",
            encryption.isUnlocked() ? "UNLOCKED" : "LOCKED");
    }

    /**
     * Called on every server tick. Handles periodic data publishing.
     *
     * @param currentTick The current server tick count
     */
    public void onTick(int currentTick) {
        if (!githubPublishEnabled && !webServerEnabled) return;

        tickCounter++;
        if (tickCounter >= publishIntervalTicks) {
            tickCounter = 0;
            // Fix: Submit publishData() to the analytics executor instead of
            // running on the server tick thread. The previous version performed
            // synchronous SQLite queries on the tick thread, causing periodic
            // lag spikes every 60 seconds.
            engine.getDatabase().getExecutor().submit(this::publishData);
        }
    }

    /**
     * Publishes analytics data to all enabled destinations.
     */
    public void publishData() {
        try {
            // Build the raw JSON data
            String jsonData = DashboardDataBuilder.buildJson(engine);

            // Publish to GitHub Pages (encrypted)
            if (githubPublishEnabled && githubPublisher.isEnabled()) {
                if (encryption.isUnlocked()) {
                    String encrypted = encryption.encrypt(jsonData);
                    if (encrypted != null) {
                        githubPublisher.publishAsync(encrypted);
                    }
                } else {
                    // Publish unencrypted if encryption is not set up
                    // (admin will be warned on dashboard)
                    githubPublisher.publishAsync(jsonData);
                    SolidusAnalyticsMod.LOGGER.debug("Published unencrypted data to GitHub Pages. "
                        + "Set up encryption with /analytics dashboard setup <password>");
                }
            }

            // Update embedded web server's cached data
            if (webServerEnabled && webServer != null) {
                webServer.updateData(jsonData);
            }

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to publish dashboard data", e);
        }
    }

    /**
     * Shuts down all dashboard services.
     */
    public void shutdown() {
        encryption.lock();
        githubPublisher.shutdown();

        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }

        SolidusAnalyticsMod.LOGGER.info("Dashboard Manager shut down.");
    }

    // ── Configuration ───────────────────────────────────────

    private void loadConfig() {
        if (Files.exists(dashboardConfigPath)) {
            try (InputStream is = Files.newInputStream(dashboardConfigPath)) {
                dashboardProps.load(is);
            } catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to load dashboard config", e);
            }
        }
    }

    private void applyConfig() {
        // GitHub Pages configuration
        githubPublishEnabled = getBool("github.enabled", false);
        String token = dashboardProps.getProperty("github.token", "");
        String owner = dashboardProps.getProperty("github.owner", "");
        String repo = dashboardProps.getProperty("github.repo", "");
        String branch = dashboardProps.getProperty("github.branch", "main");

        // Token is stored obfuscated — decode it
        if (!token.isBlank()) {
            token = decodeToken(token);
        }

        githubPublisher.configure(token, owner, repo, branch, githubPublishEnabled);

        // Web server configuration
        webServerEnabled = getBool("webserver.enabled", false);
        int webPort = getInt("webserver.port", 9090);
        String webPassword = dashboardProps.getProperty("webserver.password_hash", "");

        if (webServerEnabled) {
            try {
                webServer = new AnalyticsWebServer(engine, webPort, webPassword);
                webServer.start();
            } catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to start embedded web server", e);
                webServerEnabled = false;
            }
        }

        // Publish interval
        publishIntervalSeconds = getInt("publish.interval.seconds", 60);
        publishIntervalTicks = publishIntervalSeconds * 20; // 20 ticks per second

        // Try to auto-unlock encryption if password hash exists
        String passwordHash = dashboardProps.getProperty("encryption.password_hash", "");
        if (passwordHash.isBlank()) {
            SolidusAnalyticsMod.LOGGER.info("Dashboard encryption not set up. "
                + "Use /analytics dashboard setup <password> to enable encryption.");
        } else {
            // Try auto-unlock from environment variable first
            boolean autoUnlocked = tryAutoUnlock(passwordHash);
            if (!autoUnlocked) {
                SolidusAnalyticsMod.LOGGER.info("Dashboard encryption hash found. "
                    + "Use /analytics dashboard unlock <password> to unlock, or set "
                    + "SOLIDUS_DASHBOARD_PASSWORD env var for automatic unlock on restart.");
            }
        }
    }

    /**
     * Saves the dashboard configuration.
     */
    public void saveConfig() {
        try {
            Files.createDirectories(dashboardConfigPath.getParent());
            try (OutputStream os = Files.newOutputStream(dashboardConfigPath)) {
                dashboardProps.store(os, """
                    Solidus Analytics Dashboard Configuration
                    =========================================
                    GitHub Pages Mode (no VPS needed):
                      github.enabled — Enable GitHub Pages publishing (default: false)
                      github.owner   — Your GitHub username or organization
                      github.repo    — Repository name for the dashboard
                      github.token   — GitHub Personal Access Token (obfuscated)
                      github.branch  — Branch to publish to (default: main)

                    Embedded Web Server (VPS/dedicated server only):
                      webserver.enabled     — Enable embedded web server (default: false)
                      webserver.port        — Port for the web server (default: 9090)
                      webserver.password_hash — Hashed password for web access

                    Encryption:
                      encryption.password_hash — Hashed admin password (set via command, not manually)

                    Publishing:
                      publish.interval.seconds — How often to update data (default: 60)
                    """);
            }
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to save dashboard config", e);
        }
    }

    // ── Command API ─────────────────────────────────────────

    /**
     * Sets up dashboard encryption with a password.
     *
     * @param password The admin's chosen password
     * @return A status message for the command sender
     */
    public String setupEncryption(String password) {
        String hash = encryption.setupPassword(password.toCharArray());
        dashboardProps.setProperty("encryption.password_hash", hash);
        saveConfig();
        return "Dashboard encryption set up successfully. Your data will be encrypted before publishing.";
    }

    /**
     * Unlocks dashboard encryption with the admin's password.
     *
     * @param password The admin's password
     * @return A status message for the command sender
     */
    public String unlockEncryption(String password) {
        String storedHash = dashboardProps.getProperty("encryption.password_hash", "");
        if (storedHash.isBlank()) {
            return "Encryption is not set up yet. Use /analytics dashboard setup <password> first.";
        }

        if (encryption.unlock(password.toCharArray(), storedHash)) {
            return "Dashboard encryption unlocked. Data will be published encrypted.";
        } else {
            return "Incorrect password. Dashboard encryption remains locked.";
        }
    }

    /**
     * Configures GitHub Pages publishing.
     *
     * @param token GitHub Personal Access Token
     * @param owner Repository owner
     * @param repo  Repository name
     * @return A status message for the command sender
     */
    public String setupGitHub(String token, String owner, String repo) {
        dashboardProps.setProperty("github.enabled", "true");
        dashboardProps.setProperty("github.token", encodeToken(token));
        dashboardProps.setProperty("github.owner", owner);
        dashboardProps.setProperty("github.repo", repo);
        saveConfig();

        // Apply immediately
        githubPublishEnabled = true;
        githubPublisher.configure(token, owner, repo, "main", true);

        return "GitHub Pages publishing configured for " + owner + "/" + repo + ". "
            + "Dashboard files will be published on next update cycle.";
    }

    /**
     * Gets the encryption status for display.
     */
    public String getEncryptionStatus() {
        if (!dashboardProps.containsKey("encryption.password_hash")
            || dashboardProps.getProperty("encryption.password_hash").isBlank()) {
            return "NOT SET UP — Use /analytics dashboard setup <password>";
        }
        return encryption.isUnlocked() ? "UNLOCKED" : "LOCKED — Use /analytics dashboard unlock <password>";
    }

    /**
     * Gets the GitHub publishing status for display.
     */
    public String getGitHubStatus() {
        if (!githubPublishEnabled) return "DISABLED";
        return githubPublisher.isEnabled() ? "ACTIVE" : "MISCONFIGURED";
    }

    /**
     * Gets the web server status for display.
     */
    public String getWebServerStatus() {
        if (!webServerEnabled) return "DISABLED";
        if (webServer != null && webServer.isRunning()) return "RUNNING on port " + webServer.getPort();
        return "STOPPED";
    }

    // ── Accessors ───────────────────────────────────────────

    public DashboardEncryption getEncryption() {
        return encryption;
    }

    public GitHubDataPublisher getGithubPublisher() {
        return githubPublisher;
    }

    public boolean isGithubPublishEnabled() {
        return githubPublishEnabled;
    }

    public boolean isWebServerEnabled() {
        return webServerEnabled;
    }

    // ── Token Obfuscation ───────────────────────────────────

    /**
     * Attempts to automatically unlock dashboard encryption on server startup.
     *
     * <p>This is essential for production servers with scheduled daily restarts,
     * where requiring manual {@code /analytics dashboard unlock} after every restart
     * would cause the dashboard to stop updating during the admin's absence.</p>
     *
     * <h3>Auto-Unlock Priority:</h3>
     * <ol>
     *   <li><b>Environment variable</b> — {@code SOLIDUS_DASHBOARD_PASSWORD}:
     *       Set in the server's startup script (e.g., systemd, Docker, or .sh).
     *       This is the recommended method — the password never touches the
     *       filesystem and is only visible in the server process environment.</li>
     *   <li><b>Key file</b> — {@code .dashboard-key} in the config directory:
     *       A plain-text file containing the password, protected by OS-level
     *       file permissions (chmod 600). Less secure than env var but
     *       simpler for non-containerized setups.</li>
     * </ol>
     *
     * <p>Both methods are opt-in. If neither is configured, the admin must
     * unlock manually after each restart (original behavior).</p>
     *
     * @param storedHash The stored password hash to validate against
     * @return true if auto-unlock succeeded, false otherwise
     */
    private boolean tryAutoUnlock(String storedHash) {
        // Strategy 1: Environment variable
        String envPassword = System.getenv("SOLIDUS_DASHBOARD_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            if (encryption.unlock(envPassword.toCharArray(), storedHash)) {
                SolidusAnalyticsMod.LOGGER.info("Dashboard auto-unlocked via SOLIDUS_DASHBOARD_PASSWORD env var.");
                return true;
            } else {
                SolidusAnalyticsMod.LOGGER.warn("SOLIDUS_DASHBOARD_PASSWORD env var set but password incorrect. "
                    + "Falling back to manual unlock.");
                return false;
            }
        }

        // Strategy 2: Key file (.dashboard-key in config directory)
        Path keyFile = configDir.resolve(".dashboard-key");
        if (Files.exists(keyFile)) {
            try {
                String keyPassword = Files.readString(keyFile).trim();
                if (!keyPassword.isBlank()) {
                    if (encryption.unlock(keyPassword.toCharArray(), storedHash)) {
                        SolidusAnalyticsMod.LOGGER.info("Dashboard auto-unlocked via .dashboard-key file. "
                            + "Ensure this file has restricted permissions (chmod 600).");
                        return true;
                    } else {
                        SolidusAnalyticsMod.LOGGER.warn(".dashboard-key file found but password incorrect. "
                            + "Falling back to manual unlock.");
                    }
                }
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to read .dashboard-key file", e);
            }
        }

        return false;
    }

    // ── Token Obfuscation ───────────────────────────────────

    /**
     * Simple XOR-based obfuscation for storing the GitHub token.
     * This is NOT encryption — it just prevents the token from being
     * readable at a glance in the config file. The real protection
     * comes from file system permissions on the server.
     *
     * <p>For a Minecraft server plugin, this is adequate because:
     * 1) The config file is on the server's filesystem (not public)
     * 2) Anyone with server file access already has full control
     * 3) Adding a real encryption library would bloat the JAR</p>
     */
    private static final byte[] OBFUSCATION_KEY = {
        0x53, 0x4F, 0x4C, 0x49, 0x44, 0x55, 0x53, 0x2D,
        0x44, 0x41, 0x53, 0x48, 0x42, 0x4F, 0x41, 0x52
    };

    private String encodeToken(String token) {
        byte[] bytes = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = (byte) (bytes[i] ^ OBFUSCATION_KEY[i % OBFUSCATION_KEY.length]);
        }
        return java.util.Base64.getEncoder().encodeToString(result);
    }

    private String decodeToken(String encoded) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            byte[] result = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                result[i] = (byte) (bytes[i] ^ OBFUSCATION_KEY[i % OBFUSCATION_KEY.length]);
            }
            return new String(result, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to decode GitHub token", e);
            return "";
        }
    }

    // ── Config Helpers ──────────────────────────────────────

    private boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(dashboardProps.getProperty(key, String.valueOf(defaultValue)));
    }

    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(dashboardProps.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
