package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * AnalyticsWebServer - Embedded HTTP server for the analytics dashboard.
 *
 * <p>Uses NanoHTTPD to serve a lightweight web dashboard directly from
 * the Minecraft server. This mode requires a VPS or dedicated server
 * with an open port, but provides real-time data access without
 * needing GitHub Pages.</p>
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>{@code /} — Dashboard HTML page</li>
 *   <li>{@code /api/data} — Current analytics data (JSON)</li>
 *   <li>{@code /css/style.css} — Dashboard CSS</li>
 *   <li>{@code /js/app.js} — Dashboard JavaScript</li>
 * </ul>
 *
 * <h3>Security:</h3>
 * <ul>
 *   <li>Password-protected via HTTP Basic Auth</li>
 *   <li>Only serves on the configured port (default: 9090)</li>
 *   <li>API endpoints require authentication</li>
 *   <li>Security headers (CSP, X-Content-Type-Options, etc.)</li>
 *   <li>GZIP compression for all text responses</li>
 *   <li>Static resource caching to avoid re-reading JAR on every request</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class AnalyticsWebServer extends NanoHTTPD {

    private final AnalyticsEngine engine;
    private final String passwordHash;

    /** Cached JSON data for quick API responses */
    private volatile String cachedData = "{}";

    /** Whether the server is currently running */
    private volatile boolean running = false;

    /** Cached static resources (loaded once from JAR) */
    private final Map<String, CachedResource> resourceCache = new HashMap<>();

    /** Maximum age for static resources in seconds (1 hour) */
    private static final int STATIC_CACHE_MAX_AGE = 3600;

    /** Minimum response size to trigger GZIP compression (bytes) */
    private static final int GZIP_THRESHOLD = 512;

    /**
     * Represents a cached static resource with its content and ETag.
     */
    private static class CachedResource {
        final String content;
        final String etag;
        final String mimeType;

        CachedResource(String content, String mimeType) {
            this.content = content;
            this.mimeType = mimeType;
            this.etag = Integer.toHexString(content.hashCode());
        }
    }

    /**
     * Constructs a new AnalyticsWebServer.
     *
     * @param engine       The analytics engine for data access
     * @param port         The port to listen on
     * @param passwordHash The hashed password for authentication
     */
    public AnalyticsWebServer(AnalyticsEngine engine, int port, String passwordHash) {
        super(port);
        this.engine = engine;
        this.passwordHash = passwordHash;
        preloadResources();
    }

    /**
     * Preloads all static resources from the JAR into memory.
     * This avoids filesystem I/O on every request.
     */
    private void preloadResources() {
        cacheResource("/web/index.html", "text/html");
        cacheResource("/web/css/style.css", "text/css");
        cacheResource("/web/js/app.js", "application/javascript");
    }

    private void cacheResource(String path, String mimeType) {
        String content = loadResourceFromJar(path);
        if (content != null) {
            resourceCache.put(path, new CachedResource(content, mimeType));
            SolidusAnalyticsMod.LOGGER.debug("Cached dashboard resource: {}", path);
        }
    }

    /**
     * Starts the web server.
     */
    public void start() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        running = true;
        SolidusAnalyticsMod.LOGGER.info("Analytics web server started on port {}", getListeningPort());
    }

    /**
     * Stops the web server.
     */
    public void stop() {
        super.stop();
        running = false;
        resourceCache.clear();
        SolidusAnalyticsMod.LOGGER.info("Analytics web server stopped.");
    }

    /**
     * Updates the cached data for API responses.
     *
     * @param jsonData The latest JSON data
     */
    public void updateData(String jsonData) {
        this.cachedData = jsonData;
    }

    /**
     * Checks if the server is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the port the server is listening on.
     */
    public int getPort() {
        return getListeningPort();
    }

    // ── Request Handling ────────────────────────────────────

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // CORS preflight
        if (Method.OPTIONS.equals(method)) {
            Response corsResp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
            addCorsHeaders(corsResp);
            addSecurityHeaders(corsResp);
            return corsResp;
        }

        // Authenticate all requests
        if (!isAuthenticated(session)) {
            Response unauthorized = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html",
                "<html><body><h1>401 Unauthorized</h1>"
                + "<p>Valid credentials required. "
                + "Set up a password with /analytics dashboard setup &lt;password&gt;</p>"
                + "</body></html>");
            unauthorized.addHeader("WWW-Authenticate", "Basic realm=\"Solidus Analytics\"");
            addSecurityHeaders(unauthorized);
            return unauthorized;
        }

        // Route and add security + CORS headers
        Response response = routeRequest(uri, session);
        addCorsHeaders(response);
        addSecurityHeaders(response);
        return response;
    }

    /**
     * Routes requests to the appropriate handler.
     */
    private Response routeRequest(String uri, IHTTPSession session) {
        return switch (uri) {
            case "/", "/index.html" -> serveDashboardHtml(session);
            case "/api/data" -> serveApiData(session);
            case "/css/style.css" -> serveCachedResource("/web/css/style.css", session);
            case "/js/app.js" -> serveCachedResource("/web/js/app.js", session);
            default -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
        };
    }

    // ── Response Handlers ───────────────────────────────────

    private Response serveDashboardHtml(IHTTPSession session) {
        return serveCachedResource("/web/index.html", session);
    }

    private Response serveApiData(IHTTPSession session) {
        String data = cachedData;
        return buildCompressedResponse(Response.Status.OK, "application/json", data, session);
    }

    /**
     * Serves a cached static resource with ETag support.
     * If the client sends a matching If-None-Match header,
     * returns 304 Not Modified.
     */
    private Response serveCachedResource(String resourcePath, IHTTPSession session) {
        CachedResource resource = resourceCache.get(resourcePath);
        if (resource == null) {
            // Fallback: load from JAR if not cached
            String content = loadResourceFromJar(resourcePath);
            if (content == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404");
            }
            String mimeType = guessMimeType(resourcePath);
            resource = new CachedResource(content, mimeType);
            resourceCache.put(resourcePath, resource);
        }

        // ETag support: return 304 if client has cached version
        String ifNoneMatch = session.getHeaders().get("if-none-match");
        if (ifNoneMatch != null && ifNoneMatch.equals(resource.etag)) {
            Response notModified = newFixedLengthResponse(Response.Status.NOT_MODIFIED, null, "");
            notModified.addHeader("ETag", resource.etag);
            notModified.addHeader("Cache-Control", "public, max-age=" + STATIC_CACHE_MAX_AGE);
            return notModified;
        }

        Response response = buildCompressedResponse(Response.Status.OK, resource.mimeType, resource.content, session);
        response.addHeader("ETag", resource.etag);
        response.addHeader("Cache-Control", "public, max-age=" + STATIC_CACHE_MAX_AGE);
        return response;
    }

    // ── Compression ────────────────────────────────────────

    /**
     * Builds a response with optional GZIP compression.
     * Compresses the response if the client accepts GZIP and
     * the content exceeds the compression threshold.
     */
    private Response buildCompressedResponse(Response.Status status, String mimeType, String content, IHTTPSession session) {
        String acceptEncoding = session.getHeaders().get("accept-encoding");
        boolean clientAcceptsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");

        if (clientAcceptsGzip && content.length() > GZIP_THRESHOLD) {
            try {
                byte[] compressed = gzipCompress(content.getBytes(StandardCharsets.UTF_8));
                Response response = newFixedLengthResponse(status, mimeType, new ByteArrayInputStream(compressed), compressed.length);
                response.addHeader("Content-Encoding", "gzip");
                response.addHeader("Vary", "Accept-Encoding");
                return response;
            } catch (IOException e) {
                // Fallback to uncompressed
                SolidusAnalyticsMod.LOGGER.debug("GZIP compression failed, serving uncompressed", e);
            }
        }

        return newFixedLengthResponse(status, mimeType, content);
    }

    /**
     * Compresses data using GZIP.
     */
    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 4);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }

    // ── Security Headers ───────────────────────────────────

    /**
     * Adds security-related HTTP headers to the response.
     */
    private void addSecurityHeaders(Response response) {
        // Prevent MIME-type sniffing
        response.addHeader("X-Content-Type-Options", "nosniff");

        // Prevent clickjacking — only allow framing from same origin
        response.addHeader("X-Frame-Options", "SAMEORIGIN");

        // Enable browser XSS filter
        response.addHeader("X-XSS-Protection", "1; mode=block");

        // Content Security Policy — restrict resource loading
        response.addHeader("Content-Security-Policy",
            "default-src 'self'; "
            + "script-src 'self' https://cdn.jsdelivr.net; "
            + "style-src 'self' https://fonts.googleapis.com https://fonts.gstatic.com; "
            + "font-src 'self' https://fonts.gstatic.com; "
            + "connect-src 'self'; "
            + "img-src 'self' data:; "
            + "frame-ancestors 'self'");

        // Referrer policy
        response.addHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permissions policy — deny unnecessary browser features
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    // ── CORS Headers ───────────────────────────────────────

    /**
     * Adds CORS headers. Restricts to same origin by default.
     * Only allows GET and OPTIONS methods (read-only dashboard).
     */
    private void addCorsHeaders(Response response) {
        // Restrict CORS to same origin instead of wildcard
        // This prevents unauthorized cross-origin access to the dashboard
        response.addHeader("Access-Control-Allow-Origin", "sameorigin");
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization");
        response.addHeader("Access-Control-Max-Age", "86400"); // 24h preflight cache
    }

    // ── Authentication ──────────────────────────────────────

    /**
     * Checks if the request has valid HTTP Basic Auth credentials.
     */
    private boolean isAuthenticated(IHTTPSession session) {
        // If no password is set, allow all access (first-time setup)
        if (passwordHash == null || passwordHash.isBlank()) {
            return true;
        }

        // Check Authorization header
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }

        try {
            String decoded = new String(
                java.util.Base64.getDecoder().decode(authHeader.substring(6)),
                StandardCharsets.UTF_8);

            // Format: "username:password" — we only check password
            int colonIndex = decoded.indexOf(':');
            String password = colonIndex >= 0 ? decoded.substring(colonIndex + 1) : decoded;

            return DashboardEncryption.verifyPassword(password.toCharArray(), passwordHash);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Resource Loading ────────────────────────────────────

    /**
     * Loads a resource from the JAR file.
     */
    private String loadResourceFromJar(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to load resource: {}", path);
            return null;
        }
    }

    /**
     * Guesses the MIME type from a file path.
     */
    private String guessMimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
