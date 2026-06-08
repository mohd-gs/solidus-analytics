package com.solidus.analytics.commands;

import com.solidus.analytics.dashboard.DashboardManager;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.license.LicenseVerifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.premium.DiscordWebhookNotifier;
import com.solidus.analytics.premium.WeeklyReportGenerator;
import com.solidus.analytics.util.AnalyticsPermissions;
import com.solidus.analytics.util.PermissionChecker;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * /analytics premium commands - Premium-only features for licensed servers.
 *
 * <p>Provides access to advanced analytics features that require a valid
 * license key: economy health score, fraud detection alerts,
 * license management, and server fingerprint display.</p>
 *
 * <h3>Subcommands:</h3>
 * <ul>
 *   <li>{@code /analytics health} — Economy health score and breakdown</li>
 *   <li>{@code /analytics fraud [list|scan]} — View or scan for fraud alerts</li>
 *   <li>{@code /analytics license} — View license status and details</li>
 *   <li>{@code /analytics fingerprint} — Show this server's fingerprint (for license purchase)</li>
 *   <li>{@code /analytics report weekly} — Force generate a weekly report</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class PremiumCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        dispatcher.register(Commands.literal("analytics")
            // /analytics health
            .then(Commands.literal("health")
                .requires(PermissionChecker.require(AnalyticsPermissions.HEALTH, 2))
                .executes(context -> executeHealth(context, engine)))

            // /analytics fraud
            .then(Commands.literal("fraud")
                .requires(PermissionChecker.require(AnalyticsPermissions.FRAUD, 2))
                .executes(context -> executeFraudList(context, engine))
                .then(Commands.literal("list")
                    .executes(context -> executeFraudList(context, engine)))
                .then(Commands.literal("scan")
                    .requires(PermissionChecker.require(AnalyticsPermissions.FRAUD, 3))
                    .executes(context -> executeFraudScan(context, engine))))

            // /analytics license
            .then(Commands.literal("license")
                .requires(PermissionChecker.require(AnalyticsPermissions.LICENSE, 3))
                .executes(context -> executeLicenseStatus(context, engine)))

            // /analytics fingerprint — Shows server fingerprint for license purchase
            .then(Commands.literal("fingerprint")
                .requires(PermissionChecker.require(AnalyticsPermissions.FINGERPRINT, 3))
                .executes(context -> executeFingerprint(context, engine)))

            // /analytics report weekly — Force generate a weekly report
            .then(Commands.literal("report")
                .requires(PermissionChecker.require(AnalyticsPermissions.HISTORY, 2))
                .then(Commands.literal("weekly")
                    .executes(context -> executeWeeklyReport(context, engine))))

            // ── Dashboard Commands ──────────────────────────

            // /analytics dashboard status — Show dashboard status
            .then(Commands.literal("dashboard")
                .requires(PermissionChecker.require(AnalyticsPermissions.DASHBOARD_MANAGE, 3))
                .executes(context -> executeDashboardStatus(context, engine))

                // /analytics dashboard setup <password>
                .then(Commands.literal("setup")
                    .then(Commands.argument("password", net.minecraft.commands.arguments.MessageArgument.message())
                        .executes(context -> executeDashboardSetup(context, engine))))

                // /analytics dashboard unlock <password>
                .then(Commands.literal("unlock")
                    .then(Commands.argument("password", net.minecraft.commands.arguments.MessageArgument.message())
                        .executes(context -> executeDashboardUnlock(context, engine))))

                // /analytics dashboard github <token> <owner> <repo>
                .then(Commands.literal("github")
                    .then(Commands.argument("token", net.minecraft.commands.arguments.MessageArgument.message())
                        .then(Commands.argument("owner", net.minecraft.commands.arguments.StringArgumentType.word())
                            .then(Commands.argument("repo", net.minecraft.commands.arguments.StringArgumentType.word())
                                .executes(context -> executeDashboardGitHub(context, engine))))))

                // /analytics dashboard publish — Force publish data now
                .then(Commands.literal("publish")
                    .executes(context -> executeDashboardPublish(context, engine))))
        );
    }

    // ── Subcommand: Economy Health Score ────────────────────

    private static int executeHealth(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();

        if (!engine.isPremiumEnabled()) {
            sendFeedback(source, styled("  Premium feature — license required. Use /analytics license to check status.",
                ChatFormatting.RED));
            return 0;
        }

        EconomyHealthScore healthScorer = engine.getHealthScore();
        if (healthScorer == null) {
            sendFeedback(source, styled("  Health score calculator not available.", ChatFormatting.RED));
            return 0;
        }

        sendFeedback(source, styledBold("═══════ Economy Health ═══════", ChatFormatting.GOLD));

        // Calculate asynchronously
        engine.getDatabase().getExecutor().submit(() -> {
            EconomyHealthScore.HealthReport report = healthScorer.compute();

            source.getServer().execute(() -> {
                // Overall score
                ChatFormatting scoreColor = getScoreChatColor(report.overallScore);
                sendFeedback(source, styled("  Overall Score: ", ChatFormatting.GRAY)
                    .append(styledBold(String.format("%.1f", report.overallScore), scoreColor))
                    .append(styled(" / 100 (" + report.getGrade() + ")", ChatFormatting.DARK_GRAY)));

                // Summary
                sendFeedback(source, styled("  " + report.summary, ChatFormatting.WHITE));

                // Factor breakdown
                sendFeedback(source, styledBold("  ── Factor Breakdown ──", ChatFormatting.DARK_AQUA));
                sendFeedback(source, formatFactorLine("Gini Inequality", report.giniScore, 25));
                sendFeedback(source, formatFactorLine("Inflation Rate", report.inflationScore, 25));
                sendFeedback(source, formatFactorLine("Money Growth", report.moneyGrowthScore, 20));
                sendFeedback(source, formatFactorLine("Activity Level", report.activityScore, 15));
                sendFeedback(source, formatFactorLine("Market Liquidity", report.liquidityScore, 15));

                sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
            });
        });

        return 1;
    }

    // ── Subcommand: Fraud Alerts ────────────────────────────

    private static int executeFraudList(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();

        if (!engine.isPremiumEnabled()) {
            sendFeedback(source, styled("  Premium feature — license required.", ChatFormatting.RED));
            return 0;
        }

        FraudDetector detector = engine.getFraudDetector();
        if (detector == null) {
            sendFeedback(source, styled("  Fraud detector not available.", ChatFormatting.RED));
            return 0;
        }

        sendFeedback(source, styledBold("═══════ Fraud Alerts ═══════", ChatFormatting.GOLD));

        List<FraudDetector.FraudAlert> alerts = detector.getRecentAlerts(10);
        if (alerts.isEmpty()) {
            sendFeedback(source, styled("  No recent fraud alerts.", ChatFormatting.GREEN));
        } else {
            sendFeedback(source, styled("  Recent alerts (last 10):", ChatFormatting.YELLOW));
            for (FraudDetector.FraudAlert alert : alerts) {
                ChatFormatting severityColor = switch (alert.severity) {
                    case HIGH -> ChatFormatting.RED;
                    case MEDIUM -> ChatFormatting.YELLOW;
                    case LOW -> ChatFormatting.GRAY;
                };
                sendFeedback(source,
                    styled("  [" + alert.severity + "] ", severityColor)
                        .append(styled(alert.type + " — " + alert.playerName, ChatFormatting.WHITE)));
                sendFeedback(source, styled("    " + alert.description, ChatFormatting.DARK_GRAY));
            }
        }

        int highCount = detector.getHighSeverityCount();
        if (highCount > 0) {
            sendFeedback(source, styled("  High severity alerts: " + highCount, ChatFormatting.RED));
        }

        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Fraud Scan (admin) ──────────────────────

    private static int executeFraudScan(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();

        if (!engine.isPremiumEnabled()) {
            sendFeedback(source, styled("  Premium feature — license required.", ChatFormatting.RED));
            return 0;
        }

        FraudDetector detector = engine.getFraudDetector();
        if (detector == null) {
            sendFeedback(source, styled("  Fraud detector not available.", ChatFormatting.RED));
            return 0;
        }

        sendFeedback(source, styled("  Running fraud scan...", ChatFormatting.YELLOW));

        engine.getDatabase().getExecutor().submit(() -> {
            List<FraudDetector.FraudAlert> newAlerts = detector.runAllChecks();
            source.getServer().execute(() -> {
                if (newAlerts.isEmpty()) {
                    sendFeedback(source, styled("  No suspicious patterns detected.", ChatFormatting.GREEN));
                } else {
                    sendFeedback(source, styled("  Found " + newAlerts.size() + " suspicious pattern(s)!",
                        ChatFormatting.RED));
                    for (FraudDetector.FraudAlert alert : newAlerts) {
                        ChatFormatting color = alert.severity == FraudDetector.FraudAlert.Severity.HIGH
                            ? ChatFormatting.RED : ChatFormatting.YELLOW;
                        sendFeedback(source, styled("  [" + alert.severity + "] " + alert.type
                            + " — " + alert.playerName, color));
                    }
                }
            });
        });

        return 1;
    }

    // ── Subcommand: Weekly Report ───────────────────────────

    private static int executeWeeklyReport(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();

        WeeklyReportGenerator reportGen = engine.getWeeklyReportGenerator();
        if (reportGen == null) {
            sendFeedback(source, styled("  Weekly report generator not available.", ChatFormatting.RED));
            return 0;
        }

        sendFeedback(source, styled("  Generating weekly report...", ChatFormatting.YELLOW));

        engine.getDatabase().getExecutor().submit(() -> {
            Path reportPath = reportGen.forceGenerate();
            source.getServer().execute(() -> {
                if (reportPath != null) {
                    sendFeedback(source, styled("  Weekly report generated successfully!", ChatFormatting.GREEN));
                    sendFeedback(source, styled("  Saved to: " + reportPath.getFileName(), ChatFormatting.GRAY));
                    sendFeedback(source, styled("  Full path: " + reportPath.toAbsolutePath(), ChatFormatting.DARK_GRAY));
                } else {
                    sendFeedback(source, styled("  Failed to generate weekly report. Check server logs.", ChatFormatting.RED));
                }
            });
        });

        return 1;
    }

    // ── Subcommand: License Status ──────────────────────────

    private static int executeLicenseStatus(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        LicenseVerifier verifier = engine.getLicenseVerifier();

        sendFeedback(source, styledBold("═══════ License Status ═══════", ChatFormatting.GOLD));

        if (verifier == null) {
            sendFeedback(source, styled("  License verifier not loaded.", ChatFormatting.RED));
            sendFeedback(source, styled("  Premium features are disabled.", ChatFormatting.GRAY));
        } else {
            LicenseVerifier.VerificationState state = verifier.getState();
            ChatFormatting stateColor = switch (state) {
                case VERIFIED -> ChatFormatting.GREEN;
                case EXPIRED -> ChatFormatting.GOLD;
                case FINGERPRINT_MISMATCH -> ChatFormatting.LIGHT_PURPLE;
                case UNVERIFIED -> ChatFormatting.GRAY;
                case INVALID -> ChatFormatting.RED;
            };

            sendFeedback(source, styled("  Status: ", ChatFormatting.GRAY)
                .append(styledBold(state.name(), stateColor)));

            if (verifier.getLicenseeName() != null) {
                sendFeedback(source, styled("  Licensed to: ", ChatFormatting.GRAY)
                    .append(styled(verifier.getLicenseeName(), ChatFormatting.WHITE)));
            }

            if (verifier.getExpiryDate() != null) {
                LocalDate expiry = verifier.getExpiryDate();
                long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiry);
                ChatFormatting expiryColor = daysLeft > 30 ? ChatFormatting.GREEN
                    : daysLeft > 7 ? ChatFormatting.YELLOW
                    : ChatFormatting.RED;

                sendFeedback(source, styled("  Expires: ", ChatFormatting.GRAY)
                    .append(styled(expiry.format(DateTimeFormatter.ISO_LOCAL_DATE), ChatFormatting.WHITE))
                    .append(styled(" (" + daysLeft + " days remaining)", expiryColor)));
            }

            if (verifier.getFingerprint() != null) {
                String fp = verifier.getFingerprint();
                String fpDisplay = "ANY".equals(fp)
                    ? "Universal (any server)"
                    : fp + " (server-specific)";
                sendFeedback(source, styled("  Fingerprint: ", ChatFormatting.GRAY)
                    .append(styled(fpDisplay, ChatFormatting.WHITE)));
            }

            if (verifier.getErrorMessage() != null) {
                sendFeedback(source, styled("  Error: ", ChatFormatting.GRAY)
                    .append(styled(verifier.getErrorMessage(), ChatFormatting.RED)));
            }

            sendFeedback(source, styled("  Premium features: ", ChatFormatting.GRAY)
                .append(styled(verifier.isPremiumEnabled() ? "ENABLED" : "DISABLED",
                    verifier.isPremiumEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        }

        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Server Fingerprint ──────────────────────

    /**
     * Shows this server's fingerprint. The buyer sends this to the seller
     * so the seller can generate a server-specific license key.
     */
    private static int executeFingerprint(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        String fingerprint = LicenseVerifier.computeServerFingerprint();

        sendFeedback(source, styledBold("═══════ Server Fingerprint ═══════", ChatFormatting.GOLD));
        sendFeedback(source, styled("  Your server fingerprint:", ChatFormatting.GRAY));
        sendFeedback(source, styled("  " + fingerprint, ChatFormatting.AQUA));
        sendFeedback(source, styled("", ChatFormatting.GRAY));
        sendFeedback(source, styled("  Send this fingerprint to the license seller", ChatFormatting.YELLOW));
        sendFeedback(source, styled("  so they can generate a server-specific key.", ChatFormatting.YELLOW));
        sendFeedback(source, styled("", ChatFormatting.GRAY));
        sendFeedback(source, styled("  If you have a universal key (fingerprint: ANY),", ChatFormatting.GRAY));
        sendFeedback(source, styled("  you do not need to provide this fingerprint.", ChatFormatting.DARK_GRAY));
        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Dashboard Status ────────────────────────

    private static int executeDashboardStatus(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        DashboardManager dm = engine.getDashboardManager();

        if (dm == null) {
            sendFeedback(source, styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }

        sendFeedback(source, styledBold("═══════ Dashboard Status ═══════", ChatFormatting.GOLD));
        sendFeedback(source, styled("  Encryption: ", ChatFormatting.GRAY)
            .append(styled(dm.getEncryptionStatus(), ChatFormatting.WHITE)));
        sendFeedback(source, styled("  GitHub Pages: ", ChatFormatting.GRAY)
            .append(styled(dm.getGitHubStatus(), ChatFormatting.WHITE)));
        sendFeedback(source, styled("  Web Server: ", ChatFormatting.GRAY)
            .append(styled(dm.getWebServerStatus(), ChatFormatting.WHITE)));
        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Dashboard Setup ─────────────────────────

    private static int executeDashboardSetup(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        DashboardManager dm = engine.getDashboardManager();

        if (dm == null) {
            sendFeedback(source, styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }

        String password = net.minecraft.commands.arguments.MessageArgument.getMessage(context, "password").getString();
        String result = dm.setupEncryption(password);

        sendFeedback(source, styled("  " + result, ChatFormatting.GREEN));
        return 1;
    }

    // ── Subcommand: Dashboard Unlock ────────────────────────

    private static int executeDashboardUnlock(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        DashboardManager dm = engine.getDashboardManager();

        if (dm == null) {
            sendFeedback(source, styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }

        String password = net.minecraft.commands.arguments.MessageArgument.getMessage(context, "password").getString();
        String result = dm.unlockEncryption(password);

        if (result.contains("unlocked")) {
            sendFeedback(source, styled("  " + result, ChatFormatting.GREEN));
        } else {
            sendFeedback(source, styled("  " + result, ChatFormatting.RED));
        }
        return 1;
    }

    // ── Subcommand: Dashboard GitHub ────────────────────────

    private static int executeDashboardGitHub(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        DashboardManager dm = engine.getDashboardManager();

        if (dm == null) {
            sendFeedback(source, styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }

        String token = net.minecraft.commands.arguments.MessageArgument.getMessage(context, "token").getString();
        String owner = net.minecraft.commands.arguments.StringArgumentType.getString(context, "owner");
        String repo = net.minecraft.commands.arguments.StringArgumentType.getString(context, "repo");

        String result = dm.setupGitHub(token, owner, repo);
        sendFeedback(source, styled("  " + result, ChatFormatting.GREEN));
        return 1;
    }

    // ── Subcommand: Dashboard Publish ───────────────────────

    private static int executeDashboardPublish(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        DashboardManager dm = engine.getDashboardManager();

        if (dm == null) {
            sendFeedback(source, styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }

        sendFeedback(source, styled("  Publishing dashboard data...", ChatFormatting.YELLOW));

        engine.getDatabase().getExecutor().submit(() -> {
            dm.publishData();
            source.getServer().execute(() -> {
                sendFeedback(source, styled("  Dashboard data published successfully!", ChatFormatting.GREEN));
            });
        });

        return 1;
    }

    // ── Component Helpers ───────────────────────────────────

    private static void sendFeedback(CommandSourceStack source, Component message) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            player.sendSystemMessage(message);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendSuccess(() -> message, false);
        }
    }

    private static Component styled(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    private static Component styledBold(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withBold(true));
    }

    private static ChatFormatting getScoreChatColor(double score) {
        if (score >= 80) return ChatFormatting.GREEN;
        if (score >= 60) return ChatFormatting.YELLOW;
        if (score >= 40) return ChatFormatting.GOLD;
        if (score >= 20) return ChatFormatting.RED;
        return ChatFormatting.DARK_RED;
    }

    private static Component formatFactorLine(String name, double score, int weight) {
        ChatFormatting scoreColor = getScoreChatColor(score);
        return styled("    " + name + ": ", ChatFormatting.GRAY)
            .append(styled(String.format("%.0f", score), scoreColor))
            .append(styled("/100 (weight: " + weight + "%)", ChatFormatting.DARK_GRAY));
    }
}
