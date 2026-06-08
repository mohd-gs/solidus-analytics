package com.solidus.analytics.util;

/**
 * Permission node constants for Solidus Analytics.
 * Naming: solidus.analytics.<category>.<action>
 */
public final class AnalyticsPermissions {

    private AnalyticsPermissions() {}

    // OP 2 (Moderator) — viewing analytics
    public static final String DASHBOARD = "solidus.analytics.dashboard";
    public static final String WEALTH = "solidus.analytics.wealth";
    public static final String INFLATION = "solidus.analytics.inflation";
    public static final String TOP = "solidus.analytics.top";
    public static final String HISTORY = "solidus.analytics.history";
    public static final String HEALTH = "solidus.analytics.health";
    public static final String FRAUD = "solidus.analytics.fraud";
    public static final String INFLATION_CMD = "solidus.analytics.inflation.cmd";

    // OP 3 (Admin) — management operations
    public static final String SNAPSHOT = "solidus.analytics.snapshot";
    public static final String EXPORT = "solidus.analytics.export";
    public static final String DASHBOARD_MANAGE = "solidus.analytics.dashboard.manage";
    public static final String LICENSE = "solidus.analytics.license";
    public static final String FINGERPRINT = "solidus.analytics.fingerprint";
}
