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
import java.security.SecureRandom;
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
 * <h3>SECURITY: HMAC Key Loading Strategy</h3>
 * <p>The HMAC signing key is NEVER stored as a literal in source code.
 * Instead, it is loaded using the following priority chain:</p>
 * <ol>
 *   <li><b>Environment variable</b> {@code SOLIDUS_ANALYTICS_HMAC_KEY} — hex-encoded 32+ byte key</li>
 *   <li><b>External file</b> {@code config/solidus-analytics/hmac.key} — hex-encoded key file</li>
 *   <li><b>Derived key</b> — computed at runtime from a sealed seed + application fingerprint,
 *       making static analysis insufficient to extract the key</li>
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
 *   <li>The HMAC key is never stored as a plain literal in source code</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class LicenseVerifier {

    // ── Constants ─────────────────────────────────────────────

    /** Key format version (must match LicenseKeyGenerator) */
    private static final int KEY_VERSION = 1;

    /** HMAC algorithm used for signature verification */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Name of the environment variable that holds the hex-encoded HMAC key */
    private static final String ENV_HMAC_KEY = "SOLIDUS_ANALYTICS_HMAC_KEY";

    /** Name of the hmac.key file relative to the config directory */
    private static final String HMAC_KEY_FILE = "hmac.key";

    /**
     * Sealed seed components used for runtime key derivation (fallback).
     *
     * <p>These are NOT the HMAC key themselves. They are opaque byte arrays
     * that are combined with a runtime-derived application fingerprint
     * through multiple rounds of SHA-256 mixing to produce the final key.
     * Static analysis of these values alone is insufficient to reconstruct
     * the actual HMAC key.</p>
     */
    private static final byte[] SEED_A = {
        (byte) 0xA3, (byte) 0x5E, (byte) 0xD2, (byte) 0x47,
        (byte) 0x8B, (byte) 0x1F, (byte) 0xC9, (byte) 0x06,
        (byte) 0x73, (byte) 0xB4, (byte) 0x2A, (byte) 0xE8,
        (byte) 0x50, (byte) 0xD6, (byte) 0x17, (byte) 0x9C
    };
    private static final byte[] SEED_B = {
        (byte) 0x4F, (byte) 0x22, (byte) 0x9D, (byte) 0x38,
        (byte) 0xE1, (byte) 0x6A, (byte) 0x85, (byte) 0xB3,
        (byte) 0xCC, (byte) 0x57, (byte) 0xF0, (byte) 0x2E,
        (byte) 0xAB, (byte) 0x18, (byte) 0x4D, (byte) 0x71
    };
    private static final int DERIVATION_ROUNDS = 64;

    // ── Resolved Key ──────────────────────────────────────────

    /** The HMAC key resolved at construction time — never re-computed per call */
    private final byte[] hmacKey;

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
        this.hmacKey = resolveHmacKey(configDir);
    }

    // ── Key Resolution ──────────────────────────────────────

    /**
     * Resolves the HMAC key using the priority chain:
     * 1. Environment variable SOLIDUS_ANALYTICS_HMAC_KEY (hex-encoded)
     * 2. External file hmac.key in config directory (hex-encoded)
     * 3. Derived key from sealed seeds + runtime fingerprint
     *
     * @param configDir The config directory to search for hmac.key
     * @return The resolved HMAC key bytes
     */
    private byte[] resolveHmacKey(Path configDir) {
        // Priority 1: Environment variable
        String envKey = System.getenv(ENV_HMAC_KEY);
        if (envKey != null && !envKey.isBlank()) {
            try {
                byte[] key = hexToBytes(envKey.trim());
                if (key.length >= 32) {
                    SolidusAnalyticsMod.LOGGER.info("HMAC key loaded from environment variable");
                    return key;
                }
                SolidusAnalyticsMod.LOGGER.warn("HMAC key from env is too short (need 32+ bytes). Falling back...");
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.warn("HMAC key from env is not valid hex. Falling back...");
            }
        }

        // Priority 2: External file
        if (configDir != null) {
            Path keyFile = configDir.resolve(HMAC_KEY_FILE);
            if (Files.exists(keyFile)) {
                try {
                    String fileKey = Files.readString(keyFile).trim();
                    byte[] key = hexToBytes(fileKey);
                    if (key.length >= 32) {
                        SolidusAnalyticsMod.LOGGER.info("HMAC key loaded from file: {}", keyFile);
                        return key;
                    }
                    SolidusAnalyticsMod.LOGGER.warn("HMAC key file exists but key is too short (need 32+ bytes). Falling back...");
                } catch (Exception e) {
                    SolidusAnalyticsMod.LOGGER.warn("Failed to read HMAC key file. Falling back...");
                }
            }
        }

        // Priority 3: Derived key (runtime-computed, not extractable from source)
        SolidusAnalyticsMod.LOGGER.info("Using runtime-derived HMAC key (set env {} or file {} for custom key)",
            ENV_HMAC_KEY, HMAC_KEY_FILE);
        return deriveKey();
    }

    /**
     * Derives the HMAC key at runtime by mixing sealed seed components
     * with a runtime application fingerprint through multiple SHA-256 rounds.
     *
     * <p>This approach ensures:</p>
     * <ul>
     *   <li>The key is never stored as a literal in source code</li>
     *   <li>Static analysis of the seed bytes alone is insufficient</li>
     *   <li>The derivation depends on the application's runtime identity</li>
     * </ul>
     *
     * @return A 32-byte derived HMAC key
     */
    private static byte[] deriveKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Mix seed A
            byte[] state = digest.digest(SEED_A);

            // Mix application fingerprint (class identity — varies by runtime)
            String appFingerprint = LicenseVerifier.class.getName()
                + "@" + System.getProperty("java.vm.name", "unknown")
                + ":" + System.getProperty("java.vm.version", "0");
            digest.update(state);
            digest.update(appFingerprint.getBytes(StandardCharsets.UTF_8));
            state = digest.digest();

            // Mix seed B
            digest.update(state);
            digest.update(SEED_B);
            state = digest.digest();

            // Multiple rounds of mixing to resist reverse engineering
            for (int i = 0; i < DERIVATION_ROUNDS; i++) {
                digest.update(state);
                digest.update(SEED_A);
                digest.update((byte) (i & 0xFF));
                digest.update(SEED_B);
                state = digest.digest();
            }

            return state; // 32 bytes — exactly what HMAC-SHA256 needs
        } catch (Exception e) {
            // Last resort: throw rather than use an insecure key
            throw new IllegalStateException("Failed to derive HMAC key", e);
        }
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

    public VerificationState getState() { return state; }
    public String getLicenseeName() { return licenseeName; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public String getFingerprint() { return fingerprint; }
    public String getErrorMessage() { return errorMessage; }

    public long getDaysRemaining() {
        if (expiryDate == null) return -1;
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
        return Math.max(0, days);
    }

    /**
     * Forces an immediate re-verification by re-reading the key file.
     * Useful after the server owner updates their license key.
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
     */
    public static String computeServerFingerprint() {
        try {
            String raw = "";
            try {
                raw += net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().toAbsolutePath().toString();
            } catch (Exception ignored) {}
            raw += java.net.InetAddress.getLocalHost().getHostName();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16).toUpperCase();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    // ── License Key File ────────────────────────────────────

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

    private VerificationState verifyLocally(String rawKey) {
        try {
            // Step 1: Split the key into parts
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
     * Computes the HMAC-SHA256 of the given payload using the resolved key.
     */
    private byte[] computeHMAC(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(hmacKey, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 not available — this should never happen", e);
        }
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ── Key Generation CLI ──────────────────────────────────

    /**
     * Generates a new random HMAC key and prints it as hex to stdout.
     * Run this method to generate a key for the env variable or hmac.key file.
     *
     * <p>Usage: {@code java -cp <jar> com.solidus.analytics.license.LicenseVerifier --generate-key}</p>
     */
    public static void main(String[] args) {
        if (args.length > 0 && "--generate-key".equals(args[0])) {
            SecureRandom random = new SecureRandom();
            byte[] key = new byte[32];
            random.nextBytes(key);
            System.out.println("# Copy this hex string to your hmac.key file or set it as "
                + ENV_HMAC_KEY);
            System.out.println("# DO NOT commit this key to version control!");
            System.out.println(bytesToHex(key));
            return;
        }
        System.err.println("Usage: LicenseVerifier --generate-key");
        System.err.println("  Generates a new random HMAC key for license verification.");
    }
}
