package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GitHubDataPublisher - Pushes analytics data to a GitHub repository
 * for serving via GitHub Pages.
 *
 * <p>This class handles the two-step update process required by the GitHub
 * Content API:</p>
 * <ol>
 *   <li>GET the current file to retrieve its SHA hash</li>
 *   <li>PUT the updated file with that SHA to avoid 409 conflicts</li>
 * </ol>
 *
 * <h3>Conflict Handling:</h3>
 * <p>GitHub's Content API uses optimistic concurrency: every update must
 * include the SHA of the file being replaced. If two updates happen
 * concurrently, the second will receive HTTP 409 Conflict. This class
 * automatically retries with an exponential backoff strategy.</p>
 *
 * <h3>Security:</h3>
 * <p>The GitHub Personal Access Token is stored in the config file, NOT
 * as plain text. The admin enters it once during setup, and it is stored
 * in an obfuscated form. The actual token is only held in memory during
 * runtime.</p>
 *
 * @since 1.1.0
 */
public class GitHubDataPublisher {

    // ── Configuration ───────────────────────────────────────

    private volatile String githubToken;
    private volatile String repoOwner;
    private volatile String repoName;
    private volatile String branch;
    private volatile boolean enabled = false;

    // ── State ───────────────────────────────────────────────

    /** Cache of file SHA hashes keyed by file path (for conflict avoidance) */
    private final ConcurrentHashMap<String, String> shaCache = new ConcurrentHashMap<>();

    /** Dedicated single-thread executor for GitHub API calls */
    private final ExecutorService githubExecutor;

    /** Maximum number of retry attempts on 409 Conflict */
    private static final int MAX_RETRIES = 3;

    /** Base delay for exponential backoff (in milliseconds) */
    private static final long BASE_RETRY_DELAY_MS = 1_000;

    /** Maximum time to wait for a single HTTP request (in milliseconds) */
    private static final int HTTP_TIMEOUT_MS = 15_000;

    /** Rate limit: minimum time between publish attempts */
    private static final long MIN_PUBLISH_INTERVAL_MS = 30_000;
    private volatile long lastPublishAttempt = 0;

    /**
     * Constructs a new GitHubDataPublisher.
     */
    public GitHubDataPublisher() {
        this.githubExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-GitHub-Publisher");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Configuration ───────────────────────────────────────

    /**
     * Configures the publisher with GitHub repository details.
     *
     * @param token     GitHub Personal Access Token (with repo scope)
     * @param repoOwner The repository owner (username or organization)
     * @param repoName  The repository name
     * @param branch    The branch to push to (usually "main" or "gh-pages")
     * @param enabled   Whether publishing is enabled
     */
    public void configure(String token, String repoOwner, String repoName,
                          String branch, boolean enabled) {
        this.githubToken = token;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.branch = branch != null ? branch : "main";
        this.enabled = enabled && token != null && !token.isBlank()
            && repoOwner != null && !repoOwner.isBlank()
            && repoName != null && !repoName.isBlank();

        if (this.enabled) {
            SolidusAnalyticsMod.LOGGER.info("GitHub Pages publisher enabled for {}/{}",
                repoOwner, repoName);
        }
    }

    /**
     * Shuts down the publisher executor.
     */
    public void shutdown() {
        githubExecutor.shutdown();
        try {
            if (!githubExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                githubExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            githubExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Public API ──────────────────────────────────────────

    /**
     * Checks if the publisher is configured and enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Publishes data to GitHub Pages asynchronously.
     * Handles SHA retrieval and conflict retries automatically.
     *
     * <p>The data is written to the file path: {@code data/analytics-data.json}
     * in the repository. The dashboard HTML/JS files are expected to be
     * already present in the repository root.</p>
     *
     * @param jsonData The JSON data string to publish
     */
    public void publishAsync(String jsonData) {
        if (!enabled) return;

        // Rate limiting
        long now = System.currentTimeMillis();
        if (now - lastPublishAttempt < MIN_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastPublishAttempt = now;

        githubExecutor.submit(() -> {
            try {
                publishWithRetry("data/analytics-data.json", jsonData, MAX_RETRIES);
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to publish data to GitHub Pages after retries", e);
            }
        });
    }

    /**
     * Publishes the initial dashboard files to the repository.
     * Called once during setup to push the HTML/CSS/JS dashboard.
     *
     * @param htmlContent The dashboard HTML content
     * @param cssContent  The dashboard CSS content
     * @param jsContent   The dashboard JavaScript content
     */
    public void publishDashboardFiles(String htmlContent, String cssContent, String jsContent) {
        if (!enabled) return;

        githubExecutor.submit(() -> {
            try {
                publishWithRetry("index.html", htmlContent, MAX_RETRIES);
                publishWithRetry("css/style.css", cssContent, MAX_RETRIES);
                publishWithRetry("js/app.js", jsContent, MAX_RETRIES);
                SolidusAnalyticsMod.LOGGER.info("Dashboard files published to GitHub Pages.");
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to publish dashboard files to GitHub Pages", e);
            }
        });
    }

    // ── Core Publishing Logic ───────────────────────────────

    /**
     * Publishes data with automatic retry on 409 Conflict.
     *
     * <p>The two-step process:</p>
     * <ol>
     *   <li>GET the current file to obtain its SHA</li>
     *   <li>PUT the new content with the obtained SHA</li>
     * </ol>
     *
     * <p>If a 409 Conflict occurs, the SHA is re-fetched and the
     * update is retried with exponential backoff.</p>
     *
     * @param filePath  The file path in the repository
     * @param content   The file content to publish
     * @param retries   Number of retries remaining
     */
    private void publishWithRetry(String filePath, String content, int retries) throws Exception {
        if (retries <= 0) {
            throw new Exception("Max retries exceeded for " + filePath);
        }

        // Step 1: GET the current file SHA
        String sha = getFileSha(filePath);

        // Step 2: PUT the new content with the SHA
        int responseCode = putFileContent(filePath, content, sha);

        if (responseCode == 200 || responseCode == 201) {
            // Success — invalidate cached SHA (will be re-fetched next time)
            shaCache.remove(filePath);
            SolidusAnalyticsMod.LOGGER.debug("Successfully published {} to GitHub Pages.", filePath);
        } else if (responseCode == 409) {
            // Conflict — another update happened between our GET and PUT
            SolidusAnalyticsMod.LOGGER.warn("GitHub API 409 Conflict for {}. Retrying... ({} attempts left)",
                filePath, retries - 1);

            // Exponential backoff
            long delay = BASE_RETRY_DELAY_MS * (1L << (MAX_RETRIES - retries));
            Thread.sleep(delay);

            // Invalidate cached SHA and retry
            shaCache.remove(filePath);
            publishWithRetry(filePath, content, retries - 1);
        } else if (responseCode == 404 && sha == null) {
            // File doesn't exist yet — create it without SHA
            int createResponse = putFileContent(filePath, content, null);
            if (createResponse != 201) {
                throw new Exception("Failed to create " + filePath + " (HTTP " + createResponse + ")");
            }
            SolidusAnalyticsMod.LOGGER.info("Created new file {} on GitHub Pages.", filePath);
        } else {
            throw new Exception("GitHub API returned HTTP " + responseCode + " for " + filePath);
        }
    }

    /**
     * Gets the SHA of a file in the GitHub repository.
     *
     * @param filePath The file path in the repository
     * @return The SHA hash, or null if the file doesn't exist (404)
     */
    private String getFileSha(String filePath) {
        // Use cached SHA if available for this specific file
        String cached = shaCache.get(filePath);
        if (cached != null) return cached;

        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
            repoOwner, repoName, filePath, branch);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "token " + githubToken);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "SolidusAnalytics/1.1");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                String response = readResponseBody(conn);
                // Parse the SHA from the JSON response
                String sha = extractJsonField(response, "sha");
                if (sha != null) {
                    shaCache.put(filePath, sha);
                }
                return sha;
            } else if (responseCode == 404) {
                // File doesn't exist yet — that's okay for first publish
                return null;
            } else {
                SolidusAnalyticsMod.LOGGER.warn("GitHub API GET returned {} for {}", responseCode, filePath);
                return null;
            }
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to get SHA for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Puts (creates or updates) a file in the GitHub repository.
     *
     * @param filePath The file path in the repository
     * @param content  The file content (will be Base64-encoded)
     * @param sha      The SHA of the existing file (null for new files)
     * @return The HTTP response code
     */
    private int putFileContent(String filePath, String content, String sha) throws IOException {
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s",
            repoOwner, repoName, filePath);

        String base64Content = java.util.Base64.getEncoder()
            .encodeToString(content.getBytes(StandardCharsets.UTF_8));

        // Build the JSON body
        StringBuilder jsonBody = new StringBuilder();
        jsonBody.append("{");
        jsonBody.append("\"message\":\"Update ").append(filePath).append(" - Solidus Analytics auto-publish\",");
        jsonBody.append("\"branch\":\"").append(branch).append("\",");
        jsonBody.append("\"content\":\"").append(base64Content).append("\"");
        if (sha != null) {
            jsonBody.append(",\"sha\":\"").append(sha).append("\"");
        }
        jsonBody.append("}");

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "token " + githubToken);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "SolidusAnalytics/1.1");
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setDoOutput(true);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();

        // Read and discard the response body (important for connection reuse)
        readResponseBody(conn);

        return responseCode;
    }

    // ── Utility Methods ─────────────────────────────────────

    /**
     * Reads the response body from an HttpURLConnection.
     */
    private String readResponseBody(HttpURLConnection conn) {
        try {
            var inputStream = conn.getResponseCode() < 400
                ? conn.getInputStream()
                : conn.getErrorStream();

            if (inputStream == null) return "";

            try (inputStream) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Extracts a string field value from a simple JSON response.
     * This is a lightweight alternative to pulling in a full JSON library.
     * Only works for top-level string fields — not nested objects.
     *
     * @param json The JSON string
     * @param field The field name to extract
     * @return The field value, or null if not found
     */
    private String extractJsonField(String json, String field) {
        String searchKey = "\"" + field + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
