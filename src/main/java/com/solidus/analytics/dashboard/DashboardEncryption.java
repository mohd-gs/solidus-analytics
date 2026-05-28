package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * DashboardEncryption - AES-256-GCM encryption for dashboard data.
 *
 * <p>Encrypts analytics data before pushing it to GitHub Pages so that
 * sensitive player/economy data is not publicly readable. The data is
 * decrypted in the browser using the admin's password.</p>
 *
 * <h3>Security Design:</h3>
 * <ul>
 *   <li>The encryption key is NEVER stored in any config file</li>
 *   <li>The admin enters a password during first-time setup</li>
 *   <li>A bcrypt-like hash of the password is stored for verification</li>
 *   <li>The actual password is derived into an AES-256 key using PBKDF2</li>
 *   <li>Each encryption uses a unique random IV (nonce)</li>
 *   <li>AES-256-GCM provides authenticated encryption (integrity + confidentiality)</li>
 * </ul>
 *
 * <h3>Flow:</h3>
 * <ol>
 *   <li>Admin runs {@code /analytics dashboard setup &lt;password&gt;} on first install</li>
 *   <li>Password hash is stored in {@code config/solidus-analytics/dashboard.properties}</li>
 *   <li>Each time the server starts, admin enters password via {@code /analytics dashboard unlock &lt;password&gt;}</li>
 *   <li>The password is held in memory (never written to disk) and used for encryption</li>
 *   <li>In the browser, the admin enters the same password to decrypt the data</li>
 * </ol>
 *
 * <h3>Browser-Side Decryption:</h3>
 * <p>The JavaScript decryption uses the Web Crypto API, which supports
 * PBKDF2 and AES-GCM natively. The encryption format is designed to be
 * compatible with Web Crypto API:</p>
 * <pre>
 *   Base64(salt[16] + iv[12] + ciphertext + tag[16])
 * </pre>
 *
 * @since 1.1.0
 */
public class DashboardEncryption {

    // ── Constants ───────────────────────────────────────────

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;        // bytes (GCM standard)
    private static final int SALT_LENGTH = 16;      // bytes
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int KEY_LENGTH = 256;       // bits (AES-256)

    /** Secure random for salt and IV generation */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── State ───────────────────────────────────────────────

    /** The admin's password (held in memory only — NEVER written to disk) */
    private volatile char[] password;

    /** Whether the encryption is unlocked (password is in memory) */
    private volatile boolean unlocked = false;

    /**
     * Constructs a new DashboardEncryption.
     */
    public DashboardEncryption() {
    }

    // ── Password Management ─────────────────────────────────

    /**
     * Sets up the encryption with a new password.
     * Called during first-time setup via command.
     *
     * @param password The admin's chosen password
     * @return The password hash to store in the config file
     */
    public String setupPassword(char[] password) {
        // Generate a hash for verification
        String hash = hashPassword(password);

        // Hold the password in memory
        this.password = password.clone();
        this.unlocked = true;

        SolidusAnalyticsMod.LOGGER.info("Dashboard encryption password set. Data will be encrypted.");
        return hash;
    }

    /**
     * Unlocks the encryption by verifying the password against the stored hash.
     * Called on server startup or via command.
     *
     * @param password    The admin's password
     * @param storedHash  The stored password hash from config
     * @return true if the password matches and encryption is now unlocked
     */
    public boolean unlock(char[] password, String storedHash) {
        if (verifyPassword(password, storedHash)) {
            // Clear old password if any
            if (this.password != null) {
                java.util.Arrays.fill(this.password, '\0');
            }
            this.password = password.clone();
            this.unlocked = true;
            SolidusAnalyticsMod.LOGGER.info("Dashboard encryption unlocked successfully.");
            return true;
        }
        SolidusAnalyticsMod.LOGGER.warn("Dashboard encryption unlock failed — incorrect password.");
        return false;
    }

    /**
     * Locks the encryption by clearing the password from memory.
     * Called on server shutdown or via command.
     */
    public void lock() {
        if (password != null) {
            java.util.Arrays.fill(password, '\0');
            password = null;
        }
        unlocked = false;
        SolidusAnalyticsMod.LOGGER.info("Dashboard encryption locked. Password cleared from memory.");
    }

    /**
     * Checks if the encryption is currently unlocked.
     */
    public boolean isUnlocked() {
        return unlocked;
    }

    // ── Encryption / Decryption ─────────────────────────────

    /**
     * Encrypts data using AES-256-GCM with the admin's password.
     *
     * <p>Output format (Base64-encoded):</p>
     * <pre>
     *   salt[16] + iv[12] + ciphertext + GCM_tag[16]
     * </pre>
     *
     * <p>This format is designed to be compatible with the Web Crypto API
     * in the browser for client-side decryption.</p>
     *
     * @param plaintext The data to encrypt
     * @return Base64-encoded encrypted data, or null if encryption fails
     */
    public String encrypt(String plaintext) {
        if (!unlocked || password == null) {
            SolidusAnalyticsMod.LOGGER.warn("Cannot encrypt: dashboard encryption is not unlocked.");
            return null;
        }

        try {
            // Generate random salt and IV
            byte[] salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            // Derive the AES key from password using PBKDF2
            SecretKey key = deriveKey(password, salt);

            // Encrypt with AES-256-GCM
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine: salt + iv + ciphertext (GCM tag is appended automatically)
            byte[] output = new byte[salt.length + iv.length + ciphertext.length];
            System.arraycopy(salt, 0, output, 0, salt.length);
            System.arraycopy(iv, 0, output, salt.length, iv.length);
            System.arraycopy(ciphertext, 0, output, salt.length + iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(output);

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to encrypt dashboard data", e);
            return null;
        }
    }

    /**
     * Decrypts data that was encrypted with this class.
     * Primarily for testing — the browser does the real decryption.
     *
     * @param encryptedBase64 The Base64-encoded encrypted data
     * @return The decrypted plaintext, or null if decryption fails
     */
    public String decrypt(String encryptedBase64) {
        if (!unlocked || password == null) return null;

        try {
            byte[] data = Base64.getDecoder().decode(encryptedBase64);

            // Extract salt, IV, and ciphertext
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[data.length - SALT_LENGTH - IV_LENGTH];

            System.arraycopy(data, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(data, SALT_LENGTH, iv, 0, IV_LENGTH);
            System.arraycopy(data, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertext.length);

            // Derive the AES key
            SecretKey key = deriveKey(password, salt);

            // Decrypt
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to decrypt dashboard data", e);
            return null;
        }
    }

    // ── Key Derivation ──────────────────────────────────────

    /**
     * Derives an AES-256 key from the password using PBKDF2WithHmacSHA256.
     *
     * <p>This is the same algorithm used by the Web Crypto API in the browser,
     * ensuring compatibility for client-side decryption.</p>
     *
     * @param password The admin's password
     * @param salt     The random salt
     * @return The derived AES-256 SecretKey
     */
    private SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    // ── Password Hashing ────────────────────────────────────

    /**
     * Hashes the password for storage in the config file.
     * Uses SHA-256 with a random salt.
     *
     * <p>Format: {@code salt:hash} where both are hex-encoded.</p>
     *
     * <p>Note: For a production system, bcrypt or Argon2 would be preferred,
     * but those require external libraries. SHA-256 with salt is sufficient
     * for a Minecraft plugin's threat model — the hash is stored locally
     * on the server and is never exposed to the network.</p>
     *
     * @param password The password to hash
     * @return The hash string for storage
     */
    public static String hashPassword(char[] password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(new String(password).getBytes(StandardCharsets.UTF_8));
            digest.update(salt);
            byte[] hash = digest.digest();

            return bytesToHex(salt) + ":" + bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Verifies a password against a stored hash.
     *
     * @param password   The password to verify
     * @param storedHash The stored hash string (salt:hash format)
     * @return true if the password matches
     */
    public static boolean verifyPassword(char[] password, String storedHash) {
        if (storedHash == null || !storedHash.contains(":")) return false;

        String[] parts = storedHash.split(":", 2);
        byte[] salt = hexToBytes(parts[0]);
        byte[] expectedHash = hexToBytes(parts[1]);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(new String(password).getBytes(StandardCharsets.UTF_8));
            digest.update(salt);
            byte[] actualHash = digest.digest();

            // Constant-time comparison
            if (actualHash.length != expectedHash.length) return false;
            int result = 0;
            for (int i = 0; i < actualHash.length; i++) {
                result |= actualHash[i] ^ expectedHash[i];
            }
            return result == 0;

        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    // ── Hex Conversion Utilities ────────────────────────────

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
}
