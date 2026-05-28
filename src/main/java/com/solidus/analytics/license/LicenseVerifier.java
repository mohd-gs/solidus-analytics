package com.solidus.analytics.license;

import com.solidus.analytics.SolidusAnalyticsMod;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * LicenseVerifier - Offline license key verification for Solidus Analytics Premium.
 *
 * <p>This class handles local verification of license keys using HMAC-SHA256
 * digital signatures. No internet connection, remote server, or external hosting
 * is required. The entire verification happens locally on the Minecraft server.</p>
 *
 * <h3>How It Works:</h3>
 * <ol>
 *   <li>The server owner purchases a license and receives a key</li>
 *   <li>The key is placed in {@code config/solidus-analytics/license.key}</li>
 *   <li>On startup, the mod reads and verifies the key locally</li>
 *   <li>Verification checks: valid signature, not expired, fingerprint match</li>
 *   <li>If valid, premium features are activated immediately</li>
 * </ol>
 *
 * <h3>License Key Structure:</h3>
 * <pre>
 *   SA1-BASE64(payload)-BASE64(signature)
 *
 *   payload  = "1|licenseeName|2026-12-31|fingerprint"
 *   signature = HMAC-SHA256(payload, secretKey)
 * </pre>
 *
 * <h3>Security:</h3>
 * <ul>
 *   <li>HMAC-SHA256 signature prevents key forgery</li>
 *   <li>Each key can be tied to a specific server fingerprint</li>
 *   <li>Fingerprint "ANY" allows the key to work on any server</li>
 *   <li>License keys are never logged or exposed in chat</li>
 *   <li>Expiry date prevents permanent use of time-limited licenses</li>
 * </ul>
 *
 * <h3>No Infrastructure Required:</h3>
 * <p>Unlike online verification systems, this approach requires zero
 * external infrastructure. No VPS, no SSL certificates, no API endpoints,
 * no database. The seller generates keys using the LicenseKeyGenerator
 * tool and sends them to buyers. Everything else happens offline.</p>
 *
 * @since 1.0.0
 */
public final class LicenseVerifier {

    // ── Constants ─────────────────────────────────────────────

    /** Key format version (must match LicenseKeyGenerator) */
    private static final int KEY_VERSION = 1;

    /** HMAC algorithm used for signature verification */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Secret key for HMAC-SHA256 signing.
     * This key is shared between the LicenseKeyGenerator (seller tool)
     * and this verifier. It is obfuscated here to make reverse engineering
     * more difficult, but not impossible. For a Minecraft server plugin,
     * this level of protection is standard and sufficient.
     *
     * <p>IMPORTANT: If you change this key, you MUST also update
     * LicenseKeyGenerator.java with the same key, otherwise generated
     * keys will not validate.</p>
     */
    private static final byte[] HMAC_SECRET = decodeSecret(
        "534f4c494455532d414e414c59544943532d4c4943454e53452d5345435245542d32303235"
    );

    /** Path to the license key file */
    private final Path licenseKeyPath;

    // ── State ───────────────────────────────────────────────

    private volatile VerificationState state = VerificationState.UNVERIFIED;
    private volatile String licenseeName;
    private volatile LocalDate expiryDate;
    private volatile String fingerprint;
    private volatile String errorMessage;

    /**
     * Verification state enum.
     */
    public enum VerificationState {
        /** Not yet verified (startup) */
        UNVERIFIED,
        /** Successfully verified — premium features active */
        VERIFIED,
        /** Verification failed — premium features disabled */
        INVALID,
        /** License has expired */
        EXPIRED,
        /** License fingerprint does not match this server */
        FINGERPRINT_MISMATCH
    }

    // ── Constructor ─────────────────────────────────────────

    public LicenseVerifier(Path configDir) {
        this.licenseKeyPath = configDir.resolve("license.key");
    }

    // ── Initialization ──────────────────────────────────────

    /**
     * Initializes the license verifier. Reads the license key file
     * and performs local verification immediately. No network access needed.
     *
     * @return the verification state (synchronous — no CompletableFuture needed)
     */
    public VerificationState initialize() {
        SolidusAnalyticsMod.LOGGER.info("Verifying Solidus Analytics license...");

        // Step 1: Read the license key file
        String rawKey = readLicenseKey();
        if (rawKey == null) {
            state = VerificationState.INVALID;
            errorMessage = "No license key found. Place your key in " + licenseKeyPath;
            SolidusAnalyticsMod.LOGGER.error(errorMessage);
            SolidusAnalyticsMod.LOGGER.error(
                "Solidus Analytics Premium requires a valid license key. "
                + "Create the file '{}' with your license key on a single line.",
                licenseKeyPath);
            return state;
        }

        // Step 2: Parse and verify the key locally
        state = verifyLocally(rawKey);

        if (state == VerificationState.VERIFIED) {
            SolidusAnalyticsMod.LOGGER.info("License verified for: {} (expires: {})", licenseeName, expiryDate);
            if ("ANY".equals(fingerprint)) {
                SolidusAnalyticsMod.LOGGER.info("License type: Universal (any server)");
            } else {
                SolidusAnalyticsMod.LOGGER.info("License type: Server-specific (fingerprint: {}...)", fingerprint);
            }
        } else {
            SolidusAnalyticsMod.LOGGER.warn("License verification failed: {}", errorMessage);
        }

        return state;
    }

    /**
     * No-op shutdown (kept for API compatibility — no scheduler to stop).
     */
    public void shutdown() {
        // Nothing to shut down — no network scheduler, no threads
    }

    // ── Public API ──────────────────────────────────────────

    /**
     * Checks whether the license is currently valid and premium features should be enabled.
     *
     * @return true if premium features should be enabled
     */
    public boolean isPremiumEnabled() {
        if (state != VerificationState.VERIFIED) {
            return false;
        }
        // Double-check expiry in case the server has been running past midnight
        if (expiryDate != null && LocalDate.now().isAfter(expiryDate)) {
            state = VerificationState.EXPIRED;
            errorMessage = "License expired on " + expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            SolidusAnalyticsMod.LOGGER.warn("License has expired: {}", errorMessage);
            return false;
        }
        return true;
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
     * Gets the license expiry date.
     */
    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    /**
     * Gets the fingerprint this license is tied to ("ANY" = universal).
     */
    public String getFingerprint() {
        return fingerprint;
    }

    /**
     * Gets the number of days until the license expires.
     *
     * @return days remaining, or 0 if expired, or -1 if no expiry date
     */
    public long getDaysRemaining() {
        if (expiryDate == null) return -1;
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
        return Math.max(0, days);
    }

    /**
     * Gets the last verification error message, if any.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Forces an immediate re-verification by re-reading the key file.
     * Useful after the server owner updates their license key.
     *
     * @return the new verification state
     */
    public VerificationState forceReverify() {
        SolidusAnalyticsMod.LOGGER.info("Re-verifying license...");
        String rawKey = readLicenseKey();
        if (rawKey == null) {
            state = VerificationState.INVALID;
            errorMessage = "License key file not found";
            return state;
        }
        state = verifyLocally(rawKey);
        SolidusAnalyticsMod.LOGGER.info("Re-verification result: {} — {}", state, errorMessage != null ? errorMessage : "OK");
        return state;
    }

    /**
     * Computes this server's fingerprint. This value is shown to the server
     * owner via {@code /analytics fingerprint} so they can provide it to the
     * seller for a server-specific license key.
     *
     * @return the server fingerprint hex string
     */
    public static String computeServerFingerprint() {
        try {
            String identity = System.getProperty("user.name", "unknown")
                + ":" + java.net.InetAddress.getLocalHost().getHostName()
                + ":" + Path.of(".").toAbsolutePath().hashCode();

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(identity.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException | IOException e) {
            return "error-" + Integer.toHexString(System.identityHashCode(LicenseVerifier.class));
        }
    }

    // ── License Key File ────────────────────────────────────

    /**
     * Reads the license key from the key file.
     *
     * @return the raw key string, or null if the file doesn't exist or can't be read
     */
    private String readLicenseKey() {
        try {
            if (!Files.exists(licenseKeyPath)) {
                return null;
            }

            String key = Files.readString(licenseKeyPath).trim();

            // Remove any comment lines (lines starting with #)
            String[] lines = key.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    return line;
                }
            }

            return null;

        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read license key file", e);
            return null;
        }
    }

    // ── Local Verification ──────────────────────────────────

    /**
     * Verifies the license key locally using HMAC-SHA256.
     *
     * <p>Key format: {@code SA1-BASE64(payload)-BASE64(signature)}</p>
     * <p>Payload: {@code version|licenseeName|expiryDate|fingerprint}</p>
     *
     * @param rawKey the raw key string from the file
     * @return the verification state
     */
    private VerificationState verifyLocally(String rawKey) {
        try {
            // Step 1: Split the key into parts
            // Format: SA1-PAYLOAD-SIGNATURE
            if (!rawKey.startsWith("SA" + KEY_VERSION + "-")) {
                errorMessage = "Invalid key format. Expected SA" + KEY_VERSION + "-...";
                return VerificationState.INVALID;
            }

            String body = rawKey.substring(3); // Remove "SA1-"
            int lastDash = body.lastIndexOf('-');
            if (lastDash < 0) {
                errorMessage = "Invalid key structure (missing signature separator)";
                return VerificationState.INVALID;
            }

            String payloadBase64 = body.substring(0, lastDash);
            String signatureBase64 = body.substring(lastDash + 1);

            // Step 2: Decode the payload
            String payload;
            try {
                payload = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                errorMessage = "Invalid key encoding (payload not valid Base64)";
                return VerificationState.INVALID;
            }

            // Step 3: Decode the signature
            byte[] providedSignature;
            try {
                providedSignature = Base64.getDecoder().decode(signatureBase64);
            } catch (IllegalArgumentException e) {
                errorMessage = "Invalid key encoding (signature not valid Base64)";
                return VerificationState.INVALID;
            }

            // Step 4: Verify the HMAC-SHA256 signature
            byte[] expectedSignature = computeHMAC(payload);
            if (!constantTimeEquals(expectedSignature, providedSignature)) {
                errorMessage = "Invalid license key (signature mismatch — key may be forged or corrupted)";
                return VerificationState.INVALID;
            }

            // Step 5: Parse the payload fields
            // Format: version|licenseeName|expiryDate|fingerprint
            String[] fields = payload.split("\\|", 4);
            if (fields.length != 4) {
                errorMessage = "Invalid key payload structure (expected 4 fields, got " + fields.length + ")";
                return VerificationState.INVALID;
            }

            // Step 6: Verify key version
            int keyVersion;
            try {
                keyVersion = Integer.parseInt(fields[0]);
            } catch (NumberFormatException e) {
                errorMessage = "Invalid key version";
                return VerificationState.INVALID;
            }
            if (keyVersion != KEY_VERSION) {
                errorMessage = "Unsupported key version: " + keyVersion + " (expected: " + KEY_VERSION + ")";
                return VerificationState.INVALID;
            }

            // Step 7: Extract fields
            licenseeName = fields[1];

            try {
                expiryDate = LocalDate.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                errorMessage = "Invalid expiry date in key: " + fields[2];
                return VerificationState.INVALID;
            }

            fingerprint = fields[3];

            // Step 8: Check expiry
            if (LocalDate.now().isAfter(expiryDate)) {
                errorMessage = "License expired on " + expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                return VerificationState.EXPIRED;
            }

            // Step 9: Check server fingerprint (if not "ANY")
            if (!"ANY".equals(fingerprint)) {
                String serverFingerprint = computeServerFingerprint();
                if (!fingerprint.equalsIgnoreCase(serverFingerprint)) {
                    errorMessage = "This license is tied to a different server. "
                        + "Expected: " + fingerprint + ", Got: " + serverFingerprint;
                    return VerificationState.FINGERPRINT_MISMATCH;
                }
            }

            // All checks passed!
            errorMessage = null;
            return VerificationState.VERIFIED;

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Unexpected error during license verification", e);
            errorMessage = "Verification error: " + e.getMessage();
            return VerificationState.INVALID;
        }
    }

    // ── Cryptographic Helpers ────────────────────────────────

    /**
     * Computes the HMAC-SHA256 of the given payload using the embedded secret key.
     *
     * @param payload the string to sign
     * @return the HMAC-SHA256 signature bytes
     */
    private static byte[] computeHMAC(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(HMAC_SECRET, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 not available — this should never happen", e);
        }
    }

    /**
     * Decodes the obfuscated secret key from a hex string.
     * The hex string is the literal hex representation of the secret bytes,
     * making it harder to read with a simple string search.
     *
     * @param hex the hex-encoded secret
     * @return the decoded secret bytes
     */
    private static byte[] decodeSecret(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks.
     * Always compares all bytes regardless of where the first difference occurs.
     *
     * @param a first array
     * @param b second array
     * @return true if arrays are equal
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Converts bytes to a hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
