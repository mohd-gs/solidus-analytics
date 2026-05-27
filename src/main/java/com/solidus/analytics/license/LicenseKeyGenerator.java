package com.solidus.analytics.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
 * <h3>Parameters:</h3>
 * <ul>
 *   <li><b>licensee</b> — The buyer's name or server name (shown in /analytics license)</li>
 *   <li><b>expiry</b> — License expiry date in YYYY-MM-DD format</li>
 *   <li><b>fingerprint</b> — Server fingerprint, or "ANY" for universal keys</li>
 * </ul>
 *
 * <h3>How to get a server fingerprint:</h3>
 * <p>The buyer runs {@code /analytics fingerprint} on their Minecraft server
 * and sends you the displayed fingerprint. You use that fingerprint when
 * generating the key to tie it to their specific server.</p>
 *
 * <h3>Universal vs Server-Specific Keys:</h3>
 * <table>
 *   <tr><th>Feature</th><th>Universal (ANY)</th><th>Server-Specific</th></tr>
 *   <tr><td>Works on any server</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Prevents key sharing</td><td>No</td><td>Yes</td></tr>
 *   <tr><td>Buyer needs to provide fingerprint</td><td>No</td><td>Yes</td></tr>
 *   <tr><td>Recommended for</td><td>Trusted buyers / trials</td><td>Paid licenses</td></tr>
 * </table>
 *
 * @since 1.0.0
 */
public class LicenseKeyGenerator {

    // ── Configuration (must match LicenseVerifier) ──────────

    /** Key format version */
    private static final int KEY_VERSION = 1;

    /** HMAC algorithm */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Secret key for HMAC-SHA256 signing.
     * MUST be identical to the one in LicenseVerifier.java.
     *
     * <p>Hex representation of: "SOLIDUS-ANALYTICS-LICENSE-SECRET-2025"</p>
     */
    private static final byte[] HMAC_SECRET = decodeSecret(
        "534f4c494455532d414e414c59544943532d4c4943454e53452d5345435245542d32303235"
    );

    // ── Main Entry Point ─────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     Solidus Analytics — License Key Generator           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

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

    // ── Key Generation ───────────────────────────────────────

    /**
     * Generates a signed license key.
     *
     * @param licensee    the buyer's name
     * @param expiryDate  the license expiry date
     * @param fingerprint the server fingerprint or "ANY"
     * @return the complete license key string
     */
    public static String generateKey(String licensee, LocalDate expiryDate, String fingerprint) {
        // Build the payload: version|licensee|expiry|fingerprint
        String payload = KEY_VERSION
            + "|" + licensee
            + "|" + expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            + "|" + fingerprint;

        // Compute HMAC-SHA256 signature
        byte[] signature = computeHMAC(payload);

        // Encode both parts as Base64
        String payloadBase64 = Base64.getEncoder().encodeToString(
            payload.getBytes(StandardCharsets.UTF_8));
        String signatureBase64 = Base64.getEncoder().encodeToString(signature);

        // Combine: SA{version}-{payload}-{signature}
        return "SA" + KEY_VERSION + "-" + payloadBase64 + "-" + signatureBase64;
    }

    /**
     * Verifies that a generated key can be parsed back correctly.
     * Useful for testing the generator.
     *
     * @param key the key to validate
     * @return true if the key is structurally valid and signature matches
     */
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

            // Constant-time comparison
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

    /**
     * Computes the HMAC-SHA256 of the given payload using the shared secret key.
     */
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

    /**
     * Decodes a hex string to bytes (must match LicenseVerifier.decodeSecret).
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

    // ── Usage ────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("  Usage:");
        System.out.println("    java LicenseKeyGenerator <licensee> <expiry> <fingerprint>");
        System.out.println();
        System.out.println("  Parameters:");
        System.out.println("    licensee    — Buyer's name or server name (use quotes if spaces)");
        System.out.println("    expiry      — Expiry date in YYYY-MM-DD format");
        System.out.println("    fingerprint — Server fingerprint (16 hex chars) or 'ANY'");
        System.out.println();
        System.out.println("  Examples:");
        System.out.println("    java LicenseKeyGenerator \"MegaCraft\" 2026-12-31 ANY");
        System.out.println("    java LicenseKeyGenerator \"My Server\" 2026-06-30 a1b2c3d4e5f67890");
        System.out.println();
        System.out.println("  How to get a server fingerprint:");
        System.out.println("    The buyer runs /analytics fingerprint on their Minecraft server");
    }
}
