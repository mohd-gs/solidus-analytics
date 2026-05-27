package com.solidus.analytics.commands;

import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.license.LicenseVerifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.premium.DiscordWebhookNotifier;

import com.solidus.analytics.premium.WeeklyReportGenerator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;

/**
 * /analytics premium commands - Premium-only features for licensed servers.
 *
 * <p>Provides access to advanced analytics features that require a valid
 * license key: economy health score, fraud detection alerts, and
 * license management.</p>
 *
 * <h3>Subcommands:</h3>
 * <ul>
 *   <li>{@code /analytics health} — Economy health score and breakdown</li>
 *   <li>{@code /analytics fraud [list]} — View fraud detection alerts</li>
 *   <li>{@code /analytics license} — View license status</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class PremiumCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        dispatcher.register(Commands.literal("analytics")
            // /analytics health
            .then(Commands.literal("health")
                .requires(source -> source.hasPermission(2))
                .executes(context -> executeHealth(context, engine)))

            // /analytics fraud
            .then(Commands.literal("fraud")
                .requires(source -> source.hasPermission(2))
                .executes(context -> executeFraudList(context, engine))
                .then(Commands.literal("list")
                    .executes(context -> executeFraudList(context, engine)))
                .then(Commands.literal("scan")
                    .requires(source -> source.hasPermission(3))
                    .executes(context -> executeFraudScan(context, engine))))

            // /analytics license
            .then(Commands.literal("license")
                .requires(source -> source.hasPermission(3))
                .executes(context -> executeLicenseStatus(context, engine)))

            // /analytics report weekly — Force generate a weekly report
            .then(Commands.literal("report")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("weekly")
                    .executes(context -> executeWeeklyReport(context, engine))))
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
                case GRACE_PERIOD -> ChatFormatting.YELLOW;
                case VERIFYING -> ChatFormatting.AQUA;
                case UNVERIFIED -> ChatFormatting.GRAY;
                case INVALID, NETWORK_ERROR -> ChatFormatting.RED;
            };

            sendFeedback(source, styled("  Status: ", ChatFormatting.GRAY)
                .append(styledBold(state.name(), stateColor)));

            if (verifier.getLicenseeName() != null) {
                sendFeedback(source, styled("  Licensed to: ", ChatFormatting.GRAY)
                    .append(styled(verifier.getLicenseeName(), ChatFormatting.WHITE)));
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
