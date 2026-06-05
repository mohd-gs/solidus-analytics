package com.solidus.analytics.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * LicenseKeyGenerator - Tool for generating Solidus Analytics license keys.
 *
 * <p>This is a standalone utility class meant to be run by the <b>seller</b>
 * (not included in the distributed mod). It generates HMAC-SHA256 signed
 * license keys that can be verified by {@link LicenseVerifier} on the
 * buyer's Minecraft server.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   # Compile and run:
 *   javac LicenseKeyGenerator.java
 *   java LicenseKeyGenerator &lt;licensee&gt; &lt;expiry&gt; &lt;fingerprint&gt;
 *
 *   # Examples:
 *   java LicenseKeyGenerator "MegaCraft Server" 2026-12-31 ANY
 *   java LicenseKeyGenerator "MegaCraft Server" 2026-12-31 a1b2c3d4e5f67890
 *   java LicenseKeyGenerator "Test Server" 2025-06-01 ANY
 * </pre>
 *
 * <h3>SECURITY NOTE:</h3>
 * <p>The HMAC key is no longer hardcoded in this file. Instead, it must be
 * provided via the {@code SOLIDUS_ANALYTICS_HMAC_KEY} environment variable
 * (hex-encoded, 32+ bytes), or the {@code hmac.key} file, or the runtime-derived
 * fallback key. Use {@code --generate-key} to create one.</p>
 *
 * @since 1.0.0
 */
public class LicenseKeyGenerator {

    // ── Configuration (must match LicenseVerifier) ──────────

    /** Key format version */
    private static final int KEY_VERSION = 1;

    /** HMAC algorithm */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Environment variable for the HMAC key */
    private static final String ENV_HMAC_KEY = "SOLIDUS_ANALYTICS_HMAC_KEY";

    /** Default path for hmac.key file (relative to current directory) */
    private static final String HMAC_KEY_FILE = "hmac.key";

    /**
     * Sealed seed components — must match LicenseVerifier for derived key compatibility.
     * These are NOT the HMAC key; they are mixed at runtime to derive it.
     */
    private static final byte[] SEED_A = {
        (byte) 0x1F, (byte) 0x8B, (byte) 0x4C, (byte) 0xE2,
        (byte) 0xA7, (byte) 0x39, (byte) 0xD5, (byte) 0x6E,
        (byte) 0x0B, (byte) 0xF4, (byte) 0x83, (byte) 0x1A,
        (byte) 0x5D, (byte) 0xC7, (byte) 0x2E, (byte) 0x90
    };
    private static final byte[] SEED_B = {
        (byte) 0xD3, (byte) 0x61, (byte) 0x7F, (byte) 0x0A,
        (byte) 0x95, (byte) 0x2B, (byte) 0x48, (byte) 0xEC,
        (byte) 0x76, (byte) 0x13, (byte) 0xBF, (byte) 0x54,
        (byte) 0x8A, (byte) 0x3D, (byte) 0xE9, (byte) 0x27
    };
    private static final int DERIVATION_ROUNDS = 64;

    /**
     * Canonical application fingerprint — must match LicenseVerifier.
     * This is a fixed string that does NOT depend on the runtime JVM version,
     * ensuring derived keys are consistent across all JVM versions.
     */
    private static final String CANONICAL_APP_FINGERPRINT =
        "com.solidus.analytics.license.LicenseVerifier@Solidus-Analytics/1";

    /** The resolved HMAC key */
    private static byte[] HMAC_SECRET;

    // ── Main Entry Point ─────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     Solidus Analytics — License Key Generator           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        // Handle --generate-key
        if (args.length > 0 && "--generate-key".equals(args[0])) {
            generateAndPrintKey();
            return;
        }

        // Resolve the HMAC key
        HMAC_SECRET = resolveHmacKey();
        if (HMAC_SECRET == null) {
            System.err.println("ERROR: No HMAC key available. Set " + ENV_HMAC_KEY + " environment variable,");
            System.err.println("       create a hmac.key file, or run with --generate-key to create one.");
            System.exit(1);
            return;
        }

        if (args.length < 3) {
            printUsage();
            return;
        }

        String licensee = args[0];
        String expiryStr = args[1];
        String fingerprint = args[2];

        // Validate licensee
        if (licensee.isEmpty()) {
            System.err.println("ERROR: Licensee name cannot be empty.");
            return;
        }

        if (licensee.contains("|")) {
            System.err.println("ERROR: Licensee name cannot contain the '|' character.");
            return;
        }

        // Validate expiry date
        LocalDate expiryDate;
        try {
            expiryDate = LocalDate.parse(expiryStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            System.err.println("ERROR: Invalid expiry date format. Use YYYY-MM-DD (e.g., 2026-12-31)");
            return;
        }

        if (expiryDate.isBefore(LocalDate.now())) {
            System.err.println("WARNING: The expiry date is in the past! This key will be immediately expired.");
        }

        // Validate fingerprint
        if (!"ANY".equals(fingerprint) && !fingerprint.matches("[0-9a-fA-F]{16}")) {
            System.err.println("ERROR: Fingerprint must be 'ANY' or a 16-character hex string.");
            System.err.println("       The buyer can get their fingerprint by running /analytics fingerprint");
            return;
        }

        // Generate the key
        String licenseKey = generateKey(licensee, expiryDate, fingerprint);

        // Display results
        System.out.println("  Licensee:    " + licensee);
        System.out.println("  Expiry:      " + expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        System.out.println("  Fingerprint: " + fingerprint);
        System.out.println();
        System.out.println("  ┌─ License Key ─────────────────────────────────────────┐");
        System.out.println("  │ " + licenseKey);
        System.out.println("  └────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  Send this key to the buyer. They should place it in:");
        System.out.println("  config/solidus-analytics/license.key");
        System.out.println();

        // Also show the key without line breaks for easy copying
        System.out.println("  Raw key (copy-paste friendly):");
        System.out.println("  " + licenseKey);
    }

    // ── Key Resolution (matches LicenseVerifier 3-tier logic) ─────

    private static byte[] resolveHmacKey() {
        // Priority 1: Environment variable
        String envKey = System.getenv(ENV_HMAC_KEY);
        if (envKey != null && !envKey.isBlank()) {
            try {
                byte[] key = hexToBytes(envKey.trim());
                if (key.length >= 32) {
                    System.out.println("  HMAC key loaded from environment variable: " + ENV_HMAC_KEY);
                    return key;
                }
                System.err.println("WARNING: HMAC key from env is too short. Falling back...");
            } catch (Exception e) {
                System.err.println("WARNING: HMAC key from env is not valid hex. Falling back...");
            }
        }

        // Priority 2: hmac.key file
        Path keyFile = Paths.get(HMAC_KEY_FILE);
        if (Files.exists(keyFile)) {
            try {
                String fileKey = Files.readString(keyFile).trim();
                // Skip comment lines
                String[] lines = fileKey.split("\\n");
                String hexKey = null;
                for (String line : lines) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        hexKey = line;
                        break;
                    }
                }
                if (hexKey != null) {
                    byte[] key = hexToBytes(hexKey);
                    if (key.length >= 32) {
                        System.out.println("  HMAC key loaded from file: " + keyFile.toAbsolutePath());
                        return key;
                    }
                    System.err.println("WARNING: HMAC key file exists but key is too short. Falling back...");
                }
            } catch (Exception e) {
                System.err.println("WARNING: Failed to read HMAC key file. Falling back...");
            }
        }

        // Priority 3: Derived key (matches LicenseVerifier fallback)
        System.out.println("  Using runtime-derived HMAC key (no env variable or key file set)");
        return deriveKey();
    }

    private static byte[] deriveKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] state = digest.digest(SEED_A);

            // Use canonical fingerprint — MUST match LicenseVerifier
            digest.update(state);
            digest.update(CANONICAL_APP_FINGERPRINT.getBytes(StandardCharsets.UTF_8));
            state = digest.digest();

            digest.update(state);
            digest.update(SEED_B);
            state = digest.digest();

            for (int i = 0; i < DERIVATION_ROUNDS; i++) {
                digest.update(state);
                digest.update(SEED_A);
                digest.update((byte) (i & 0xFF));
                digest.update(SEED_B);
                state = digest.digest();
            }
            return state;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive HMAC key", e);
        }
    }

    private static void generateAndPrintKey() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        System.out.println("  Generated new HMAC key:");
        System.out.println();
        System.out.println("  Set this as environment variable:");
        System.out.println("  export " + ENV_HMAC_KEY + "=" + bytesToHex(key));
        System.out.println();
        System.out.println("  Or save to file: hmac.key");
        System.out.println("  " + bytesToHex(key));
        System.out.println();
        System.out.println("  IMPORTANT: Keep this key secret! Do NOT commit it to version control.");
    }

    // ── Key Generation ───────────────────────────────────────

    public static String generateKey(String licensee, LocalDate expiryDate, String fingerprint) {
        String payload = KEY_VERSION
            + "|" + licensee
            + "|" + expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            + "|" + fingerprint;

        byte[] signature = computeHMAC(payload);

        String payloadBase64 = Base64.getEncoder().encodeToString(
            payload.getBytes(StandardCharsets.UTF_8));
        String signatureBase64 = Base64.getEncoder().encodeToString(signature);

        return "SA" + KEY_VERSION + "-" + payloadBase64 + "-" + signatureBase64;
    }

    public static boolean validateKey(String key) {
        try {
            if (!key.startsWith("SA" + KEY_VERSION + "-")) return false;

            String body = key.substring(3);
            int lastDash = body.lastIndexOf('-');
            if (lastDash < 0) return false;

            String payloadBase64 = body.substring(0, lastDash);
            String signatureBase64 = body.substring(lastDash + 1);

            String payload = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            byte[] providedSignature = Base64.getDecoder().decode(signatureBase64);

            byte[] expectedSignature = computeHMAC(payload);

            if (expectedSignature.length != providedSignature.length) return false;
            int result = 0;
            for (int i = 0; i < expectedSignature.length; i++) {
                result |= expectedSignature[i] ^ providedSignature[i];
            }
            return result == 0;

        } catch (Exception e) {
            return false;
        }
    }

    // ── Cryptographic Helpers ────────────────────────────────

    private static byte[] computeHMAC(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(HMAC_SECRET, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── Usage ────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("  Usage:");
        System.out.println("    java LicenseKeyGenerator <licensee> <expiry> <fingerprint>");
        System.out.println("    java LicenseKeyGenerator --generate-key");
        System.out.println();
        System.out.println("  Parameters:");
        System.out.println("    licensee    — Buyer's name or server name (use quotes if spaces)");
        System.out.println("    expiry      — Expiry date in YYYY-MM-DD format");
        System.out.println("    fingerprint — Server fingerprint (16 hex chars) or 'ANY'");
        System.out.println();
        System.out.println("  Environment:");
        System.out.println("    " + ENV_HMAC_KEY + " — Hex-encoded HMAC key (32+ bytes)");
        System.out.println("    hmac.key file in current directory — Hex-encoded HMAC key");
        System.out.println();
        System.out.println("  Examples:");
        System.out.println("    java LicenseKeyGenerator \"MegaCraft\" 2026-12-31 ANY");
        System.out.println("    java LicenseKeyGenerator \"My Server\" 2026-06-30 a1b2c3d4e5f67890");
        System.out.println();
        System.out.println("  How to get a server fingerprint:");
        System.out.println("    The buyer runs /analytics fingerprint on their Minecraft server");
    }
}
