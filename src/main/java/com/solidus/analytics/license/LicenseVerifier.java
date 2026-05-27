package com.solidus.analytics.license;

import com.solidus.analytics.SolidusAnalyticsMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LicenseVerifier - Online license key verification for Solidus Analytics Premium.
 *
 * <p>This class handles the verification of license keys for the paid version
 * of Solidus Analytics. It uses a server-side verification system to ensure
 * that only licensed servers can use premium features.</p>
 *
 * <h3>Verification Flow:</h3>
 * <ol>
 *   <li>Read license key from config/solidus-analytics/license.key</li>
 *   <li>Send verification request to the license server with the key + server hash</li>
 *   <li>Server validates the key and returns a signed token with expiry</li>
 *   <li>Token is cached locally and re-validated periodically</li>
 *   <li>If verification fails, premium features are disabled gracefully</li>
 * </ol>
 *
 * <h3>Security:</h3>
 * <ul>
 *   <li>License keys are never logged or exposed in chat</li>
 *   <li>Server identity is hashed (not raw IP) for privacy</li>
 *   <li>Cached tokens have a limited validity period (24 hours)</li>
 *   <li>Offline grace period of 72 hours for network outages</li>
 *   <li>All verification runs asynchronously to avoid blocking the server</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class LicenseVerifier {

    // ── Configuration ──────────────────────────────────────

    /** The license verification server endpoint */
    private static final String LICENSE_SERVER_URL = "https://license.solidusanalytics.com/api/v1/verify";

    /** How often to re-verify the license (in hours) */
    private static final int REVERIFY_INTERVAL_HOURS = 24;

    /** Grace period for offline verification (in hours) */
    private static final int OFFLINE_GRACE_HOURS = 72;

    /** Path to the license key file */
    private final Path licenseKeyPath;

    // ── State ───────────────────────────────────────────────

    private volatile VerificationState state = VerificationState.UNVERIFIED;
    private volatile String licenseKey;
    private volatile Instant lastVerifiedAt;
    private volatile Instant tokenExpiry;
    private volatile String serverToken;
    private volatile String licenseeName;
    private volatile String errorMessage;

    private final ScheduledExecutorService scheduler;

    /**
     * Verification state enum.
     */
    public enum VerificationState {
        /** Not yet verified (startup) */
        UNVERIFIED,
        /** Currently verifying with the server */
        VERIFYING,
        /** Successfully verified and active */
        VERIFIED,
        /** Verified but using offline grace period */
        GRACE_PERIOD,
        /** Verification failed — premium features disabled */
        INVALID,
        /** Network error during verification */
        NETWORK_ERROR
    }

    // ── Constructor ─────────────────────────────────────────

    public LicenseVerifier(Path configDir) {
        this.licenseKeyPath = configDir.resolve("license.key");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Solidus-License-Verifier");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Initialization ──────────────────────────────────────

    /**
     * Initializes the license verifier. Reads the license key file
     * and starts the verification process.
     *
     * @return CompletableFuture that resolves to the verification state
     */
    public CompletableFuture<VerificationState> initialize() {
        // Step 1: Read the license key
        if (!readLicenseKey()) {
            state = VerificationState.INVALID;
            errorMessage = "No license key found. Place your key in " + licenseKeyPath;
            SolidusAnalyticsMod.LOGGER.error(errorMessage);
            SolidusAnalyticsMod.LOGGER.error(
                "Solidus Analytics Premium requires a valid license key. "
                + "Create the file '{}' with your license key on a single line.",
                licenseKeyPath);
            return CompletableFuture.completedFuture(state);
        }

        // Step 2: Attempt online verification
        state = VerificationState.VERIFYING;
        return verifyWithServer().thenApply(result -> {
            state = result;

            if (result == VerificationState.VERIFIED) {
                // Schedule periodic re-verification
                scheduler.scheduleAtFixedRate(
                    this::scheduledReverification,
                    REVERIFY_INTERVAL_HOURS,
                    REVERIFY_INTERVAL_HOURS,
                    TimeUnit.HOURS
                );
                SolidusAnalyticsMod.LOGGER.info("License verified for: {}", licenseeName);
            } else if (result == VerificationState.NETWORK_ERROR) {
                // Check if we can use offline grace period
                if (checkOfflineGrace()) {
                    state = VerificationState.GRACE_PERIOD;
                    SolidusAnalyticsMod.LOGGER.warn(
                        "License server unreachable. Operating in grace period ({}h remaining).",
                        getRemainingGraceHours());
                } else {
                    state = VerificationState.INVALID;
                    SolidusAnalyticsMod.LOGGER.error(
                        "License server unreachable and grace period expired. Premium features disabled.");
                }
            }

            return state;
        });
    }

    /**
     * Shuts down the verification scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Public API ──────────────────────────────────────────

    /**
     * Checks whether the license is currently valid (verified or in grace period).
     *
     * @return true if premium features should be enabled
     */
    public boolean isPremiumEnabled() {
        return state == VerificationState.VERIFIED || state == VerificationState.GRACE_PERIOD;
    }

    /**
     * Gets the current verification state.
     */
    public VerificationState getState() {
        return state;
    }

    /**
     * Gets the licensee name (server owner who purchased the license).
     */
    public String getLicenseeName() {
        return licenseeName;
    }

    /**
     * Gets the last verification error message, if any.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Forces an immediate re-verification.
     *
     * @return CompletableFuture with the new verification state
     */
    public CompletableFuture<VerificationState> forceReverify() {
        return verifyWithServer();
    }

    // ── License Key File ────────────────────────────────────

    /**
     * Reads the license key from the key file.
     *
     * @return true if the key was read successfully
     */
    private boolean readLicenseKey() {
        try {
            if (!Files.exists(licenseKeyPath)) {
                return false;
            }

            String key = Files.readString(licenseKeyPath).trim();

            // Basic format validation: license keys are SA-XXXX-XXXX-XXXX-XXXX
            if (!key.matches("SA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")) {
                SolidusAnalyticsMod.LOGGER.error("Invalid license key format. Expected: SA-XXXX-XXXX-XXXX-XXXX");
                return false;
            }

            this.licenseKey = key;
            return true;

        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read license key file", e);
            return false;
        }
    }

    // ── Server Verification ─────────────────────────────────

    /**
     * Verifies the license key with the remote server.
     */
    private CompletableFuture<VerificationState> verifyWithServer() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String serverHash = computeServerHash();
                String requestBody = "{\"key\":\"" + licenseKey + "\",\"server\":\"" + serverHash + "\"}";

                HttpURLConnection conn = (HttpURLConnection) URI.create(LICENSE_SERVER_URL).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "SolidusAnalytics/1.0");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setDoOutput(true);

                // Send request
                conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    // Read response
                    String response;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        response = sb.toString();
                    }

                    return parseVerificationResponse(response);

                } else if (responseCode == 401 || responseCode == 403) {
                    errorMessage = "Invalid or expired license key";
                    return VerificationState.INVALID;

                } else if (responseCode == 429) {
                    // Rate limited — use grace period
                    if (checkOfflineGrace()) {
                        return VerificationState.GRACE_PERIOD;
                    }
                    errorMessage = "Rate limited by license server. Grace period expired.";
                    return VerificationState.INVALID;

                } else {
                    SolidusAnalyticsMod.LOGGER.warn(
                        "License server returned unexpected status: {}", responseCode);
                    return handleNetworkError();
                }

            } catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to connect to license server: {}", e.getMessage());
                return handleNetworkError();
            }
        });
    }

    /**
     * Parses the verification response from the license server.
     * Expected JSON format: {"status":"valid","licensee":"Name","expires":"2025-12-31T23:59:59Z","token":"..."}
     */
    private VerificationState parseVerificationResponse(String response) {
        try {
            // Simple JSON parsing (no external dependency needed)
            String status = extractJsonValue(response, "status");
            if (!"valid".equals(status)) {
                errorMessage = "License server rejected the key: " + status;
                return VerificationState.INVALID;
            }

            licenseeName = extractJsonValue(response, "licensee");
            String expiresStr = extractJsonValue(response, "expires");
            serverToken = extractJsonValue(response, "token");

            if (expiresStr != null) {
                tokenExpiry = Instant.parse(expiresStr);
            }

            lastVerifiedAt = Instant.now();
            return VerificationState.VERIFIED;

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to parse license server response", e);
            errorMessage = "Invalid response from license server";
            return VerificationState.INVALID;
        }
    }

    /**
     * Extracts a value from a simple JSON string.
     * This is intentionally simple — we don't want a JSON library dependency.
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /**
     * Handles a network error during verification.
     * Checks the offline grace period before failing.
     */
    private VerificationState handleNetworkError() {
        if (checkOfflineGrace()) {
            return VerificationState.GRACE_PERIOD;
        }
        errorMessage = "Cannot reach license server and grace period expired";
        return VerificationState.NETWORK_ERROR;
    }

    // ── Offline Grace Period ────────────────────────────────

    /**
     * Checks if the offline grace period is still valid.
     * The grace period allows the server to run without internet for 72 hours.
     */
    private boolean checkOfflineGrace() {
        if (lastVerifiedAt == null) {
            // Never verified — no grace period
            return false;
        }
        Duration sinceLastVerified = Duration.between(lastVerifiedAt, Instant.now());
        return sinceLastVerified.toHours() < OFFLINE_GRACE_HOURS;
    }

    /**
     * Gets the remaining hours in the offline grace period.
     */
    private long getRemainingGraceHours() {
        if (lastVerifiedAt == null) return 0;
        Duration sinceLastVerified = Duration.between(lastVerifiedAt, Instant.now());
        long remaining = OFFLINE_GRACE_HOURS - sinceLastVerified.toHours();
        return Math.max(0, remaining);
    }

    /**
     * Scheduled re-verification task.
     */
    private void scheduledReverification() {
        try {
            SolidusAnalyticsMod.LOGGER.info("Performing scheduled license re-verification...");
            VerificationState result = verifyWithServer().get(30, TimeUnit.SECONDS);
            state = result;

            if (result == VerificationState.VERIFIED) {
                SolidusAnalyticsMod.LOGGER.info("License re-verified successfully for: {}", licenseeName);
            } else if (result == VerificationState.NETWORK_ERROR && checkOfflineGrace()) {
                state = VerificationState.GRACE_PERIOD;
                SolidusAnalyticsMod.LOGGER.warn("License server unreachable. Grace period: {}h remaining.",
                    getRemainingGraceHours());
            } else {
                SolidusAnalyticsMod.LOGGER.error("License re-verification failed: {}", errorMessage);
            }
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Error during scheduled license re-verification", e);
            if (checkOfflineGrace()) {
                state = VerificationState.GRACE_PERIOD;
            }
        }
    }

    // ── Server Identity Hash ────────────────────────────────

    /**
     * Computes a hash of the server's identity for license verification.
     * This uniquely identifies the server without exposing the actual IP.
     * Uses a combination of server properties for fingerprinting.
     */
    private String computeServerHash() {
        try {
            // Combine multiple server identity sources for a unique fingerprint
            String identity = System.getProperty("user.name", "unknown")
                + ":" + java.net.InetAddress.getLocalHost().getHostName()
                + ":" + Path.of(".").toAbsolutePath().hashCode();

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16); // First 16 hex chars
        } catch (NoSuchAlgorithmException | IOException e) {
            // Fallback — less unique but still functional
            return "fallback-" + Integer.toHexString(System.identityHashCode(this));
        }
    }
}
