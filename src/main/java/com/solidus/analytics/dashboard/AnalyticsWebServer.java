package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        }

        // Authenticate all requests (except static resources from same origin)
        if (!isAuthenticated(session)) {
            Response unauthorized = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html",
                "<html><body><h1>401 Unauthorized</h1>"
                + "<p>Valid credentials required. "
                + "Set up a password with /analytics dashboard setup &lt;password&gt;</p>"
                + "</body></html>");
            unauthorized.addHeader("WWW-Authenticate", "Basic realm=\"Solidus Analytics\"");
            return unauthorized;
        }

        // Add CORS headers for authenticated requests
        Response response = routeRequest(uri, session);
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization");
        return response;
    }

    /**
     * Routes requests to the appropriate handler.
     */
    private Response routeRequest(String uri, IHTTPSession session) {
        return switch (uri) {
            case "/", "/index.html" -> serveDashboardHtml();
            case "/api/data" -> serveApiData();
            case "/css/style.css" -> serveCss();
            case "/js/app.js" -> serveJs();
            default -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
        };
    }

    // ── Response Handlers ───────────────────────────────────

    private Response serveDashboardHtml() {
        String html = loadResource("/web/index.html");
        if (html != null) {
            return newFixedLengthResponse(Response.Status.OK, "text/html", html);
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html",
            "<html><body><h1>Solidus Analytics Dashboard</h1>"
            + "<p>Dashboard files not found in JAR. Using API-only mode.</p>"
            + "<p>API endpoint: <a href='/api/data'>/api/data</a></p>"
            + "</body></html>");
    }

    private Response serveApiData() {
        return newFixedLengthResponse(Response.Status.OK, "application/json", cachedData);
    }

    private Response serveCss() {
        String css = loadResource("/web/css/style.css");
        if (css != null) {
            return newFixedLengthResponse(Response.Status.OK, "text/css", css);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404");
    }

    private Response serveJs() {
        String js = loadResource("/web/js/app.js");
        if (js != null) {
            return newFixedLengthResponse(Response.Status.OK, "application/javascript", js);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404");
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
    private String loadResource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to load resource: {}", path);
            return null;
        }
    }
}
