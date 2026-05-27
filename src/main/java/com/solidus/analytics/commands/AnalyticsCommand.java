package com.solidus.analytics.commands;

import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.storage.AnalyticsDatabase;
import com.solidus.analytics.util.GiniCoefficient;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;

/**
 * /analytics command - Main analytics dashboard with subcommands.
 *
 * <p>Provides server administrators with real-time economic data and historical
 * trend analysis for the Solidus economy. All subcommands are permission-gated
 * to level 2 (op) to prevent casual players from accessing sensitive economic data.</p>
 *
 * <h3>Subcommands:</h3>
 * <ul>
 *   <li>{@code /analytics} — Live dashboard (volume, active players, top items)</li>
 *   <li>{@code /analytics wealth} — Wealth distribution + Gini coefficient</li>
 *   <li>{@code /analytics inflation} — Inflation rate (day/week/month)</li>
 *   <li>{@code /analytics top [buyers|sellers|items]} — Top rankings</li>
 *   <li>{@code /analytics snapshot} — Force a snapshot (admin)</li>
 *   <li>{@code /analytics export} — Export data as CSV (admin)</li>
 *   <li>{@code /analytics history [days]} — Recent daily metrics history</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class AnalyticsCommand {

    private static final int DEFAULT_HISTORY_DAYS = 7;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        dispatcher.register(Commands.literal("analytics")
            .requires(source -> source.hasPermission(2)) // OP level 2+
            .executes(context -> executeDashboard(context, engine))

            // /analytics wealth
            .then(Commands.literal("wealth")
                .executes(context -> executeWealth(context, engine)))

            // /analytics inflation
            .then(Commands.literal("inflation")
                .executes(context -> executeInflation(context, engine)))

            // /analytics top [buyers|sellers|items]
            .then(Commands.literal("top")
                .then(Commands.literal("items")
                    .executes(context -> executeTopItems(context, engine)))
                .then(Commands.literal("buyers")
                    .executes(context -> executeTopBuyers(context, engine)))
                .then(Commands.literal("sellers")
                    .executes(context -> executeTopSellers(context, engine))))

            // /analytics snapshot (admin — force immediate snapshot)
            .then(Commands.literal("snapshot")
                .requires(source -> source.hasPermission(3)) // OP level 3+
                .executes(context -> executeSnapshot(context, engine)))

            // /analytics export (admin — export CSV)
            .then(Commands.literal("export")
                .requires(source -> source.hasPermission(3)) // OP level 3+
                .executes(context -> executeExport(context, engine)))

            // /analytics history [days]
            .then(Commands.literal("history")
                .then(Commands.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 90))
                    .executes(context -> executeHistory(context, engine,
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "days"))))
                .executes(context -> executeHistory(context, engine, DEFAULT_HISTORY_DAYS)))
        );
    }

    // ── Console-Safe Feedback ────────────────────────────

    /**
     * Sends a message to the command source, supporting both in-game players
     * and the server console. When executed by a player, the rich Component
     * (with colors and styling) is sent via {@code sendSystemMessage}. When
     * executed from the console, the message is sent as plain text via
     * {@code sendSuccess} so formatting degrades gracefully.
     */
    private static void sendFeedback(CommandSourceStack source, Component message) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            player.sendSystemMessage(message);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // Console execution — send as plain text
            source.sendSuccess(() -> message, false);
        }
    }

    // ── Subcommand: Dashboard ──────────────────────────────

    private static int executeDashboard(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        LiveMetricsTracker metrics = engine.getLiveMetrics();

        sendFeedback(source, styledBold("═══════ Solidus Analytics ═══════", ChatFormatting.GOLD));
        sendFeedback(source, styled("  Live Economy Dashboard", ChatFormatting.YELLOW));

        // Daily volume
        sendFeedback(source,
            styled("  Daily Volume: ", ChatFormatting.GRAY)
                .append(currency(formatCents(metrics.getDailyVolumeCents()))));

        // Transaction count
        sendFeedback(source,
            styled("  Transactions Today: ", ChatFormatting.GRAY)
                .append(styled(String.valueOf(metrics.getDailyTransactionCount()), ChatFormatting.WHITE)));

        // Active players
        sendFeedback(source,
            styled("  Active Players: ", ChatFormatting.GRAY)
                .append(styled(String.valueOf(metrics.getActivePlayerCount()), ChatFormatting.WHITE)));

        // Breakdown by type
        Map<String, Long> typeCounts = metrics.getTransactionsByType();
        if (!typeCounts.isEmpty()) {
            sendFeedback(source, styledBold("  ── Transaction Breakdown ──", ChatFormatting.DARK_AQUA));
            typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .forEach(entry -> sendFeedback(source,
                    styled("    " + entry.getKey() + ": ", ChatFormatting.GRAY)
                        .append(styled(String.valueOf(entry.getValue()), ChatFormatting.WHITE))));
        }

        // Top bought items
        Map<String, Long> topBought = metrics.getTopBoughtItems(5);
        if (!topBought.isEmpty()) {
            sendFeedback(source, styledBold("  ── Top Bought Items ──", ChatFormatting.DARK_AQUA));
            topBought.forEach((material, qty) -> sendFeedback(source,
                styled("    " + material + ": ", ChatFormatting.GRAY)
                    .append(styled(qty + " units", ChatFormatting.WHITE))));
        }

        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Wealth Distribution ────────────────────

    private static int executeWealth(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        AnalyticsDatabase db = engine.getDatabase();

        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();

        sendFeedback(source, styledBold("═══════ Wealth Distribution ═══════", ChatFormatting.GOLD));

        if (latest == null) {
            sendFeedback(source, styled("  No snapshot data available yet.", ChatFormatting.GRAY));
            sendFeedback(source, styled("  Snapshots are taken every 30 minutes.", ChatFormatting.GRAY));
            sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
            return 1;
        }

        // Gini coefficient
        sendFeedback(source,
            styled("  Gini Coefficient: ", ChatFormatting.GRAY)
                .append(styled(String.format("%.4f", latest.giniCoefficient()), ChatFormatting.WHITE))
                .append(styled(" (" + GiniCoefficient.interpret(latest.giniCoefficient()) + ")", ChatFormatting.YELLOW)));

        // Total wealth
        sendFeedback(source,
            styled("  Total Wealth: ", ChatFormatting.GRAY)
                .append(currency(formatCents(latest.totalWealth()))));

        // Player count
        sendFeedback(source,
            styled("  Player Count: ", ChatFormatting.GRAY)
                .append(styled(String.valueOf(latest.playerCount()), ChatFormatting.WHITE)));

        // Average balance
        sendFeedback(source,
            styled("  Average Balance: ", ChatFormatting.GRAY)
                .append(currency(formatCents(latest.avgBalance()))));

        // Median balance
        sendFeedback(source,
            styled("  Median Balance: ", ChatFormatting.GRAY)
                .append(currency(formatCents(latest.medianBalance()))));

        // Top 1% share
        sendFeedback(source,
            styled("  Top 1% Wealth Share: ", ChatFormatting.GRAY)
                .append(styled(String.format("%.1f%%", latest.top1PercentShare() * 100),
                    latest.top1PercentShare() > 0.3 ? ChatFormatting.RED : ChatFormatting.GREEN)));

        // Auction data
        sendFeedback(source,
            styled("  Active Auctions: ", ChatFormatting.GRAY)
                .append(styled(latest.auctionActiveListings() + " listings ", ChatFormatting.WHITE))
                .append(currency("(" + formatCents(latest.auctionTotalValue()) + ")")));

        // Snapshot timestamp
        long ageSeconds = (System.currentTimeMillis() - latest.timestamp()) / 1000;
        sendFeedback(source,
            styled("  Snapshot age: ", ChatFormatting.GRAY)
                .append(styled(formatDuration(ageSeconds), ChatFormatting.DARK_GRAY)));

        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Inflation ──────────────────────────────

    private static int executeInflation(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        InflationCalculator calculator = engine.getInflationCalculator();

        sendFeedback(source, styledBold("═══════ Inflation Report ═══════", ChatFormatting.GOLD));

        // Calculate async and display
        calculator.calculateAsync().thenAccept(report -> {
            source.getServer().execute(() -> {
                // Money supply
                sendFeedback(source,
                    styled("  Money Supply: ", ChatFormatting.GRAY)
                        .append(currency(report.formatMoneySupply())));

                // Goods value
                sendFeedback(source,
                    styled("  Goods Value: ", ChatFormatting.GRAY)
                        .append(currency(report.formatGoodsValue())));

                // Money-to-Goods ratio
                ChatFormatting ratioColor = getRatioColor(report.moneyToGoodsRatio);
                sendFeedback(source,
                    styled("  Money:Goods Ratio: ", ChatFormatting.GRAY)
                        .append(styled(report.formatRatio(), ratioColor)));

                // Status
                ChatFormatting statusColor = getStatusColor(report.status);
                sendFeedback(source,
                    styled("  Status: ", ChatFormatting.GRAY)
                        .append(styledBold(report.status, statusColor)));

                // Inflation rates
                sendFeedback(source, styledBold("  ── Inflation Rates ──", ChatFormatting.DARK_AQUA));
                sendFeedback(source,
                    styled("    24h: ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(report.inflationRate24h),
                            getRateColor(report.inflationRate24h))));
                sendFeedback(source,
                    styled("    7d:  ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(report.inflationRate7d),
                            getRateColor(report.inflationRate7d))));
                sendFeedback(source,
                    styled("    30d: ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(report.inflationRate30d),
                            getRateColor(report.inflationRate30d))));

                sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
            });
        });

        return 1;
    }

    // ── Subcommand: Top Items ──────────────────────────────

    private static int executeTopItems(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        LiveMetricsTracker metrics = engine.getLiveMetrics();

        sendFeedback(source, styledBold("═══════ Top Items ═══════", ChatFormatting.GOLD));

        Map<String, Long> bought = metrics.getTopBoughtItems(10);
        if (!bought.isEmpty()) {
            sendFeedback(source, styledBold("  ── Most Bought ──", ChatFormatting.GREEN));
            int rank = 1;
            for (Map.Entry<String, Long> entry : bought.entrySet()) {
                sendFeedback(source,
                    styled("    #" + rank + " ", ChatFormatting.YELLOW)
                        .append(styled(entry.getKey() + ": ", ChatFormatting.WHITE))
                        .append(styled(entry.getValue() + " units", ChatFormatting.GRAY)));
                rank++;
            }
        }

        Map<String, Long> sold = metrics.getTopSoldItems(10);
        if (!sold.isEmpty()) {
            sendFeedback(source, styledBold("  ── Most Sold ──", ChatFormatting.RED));
            int rank = 1;
            for (Map.Entry<String, Long> entry : sold.entrySet()) {
                sendFeedback(source,
                    styled("    #" + rank + " ", ChatFormatting.YELLOW)
                        .append(styled(entry.getKey() + ": ", ChatFormatting.WHITE))
                        .append(styled(entry.getValue() + " units", ChatFormatting.GRAY)));
                rank++;
            }
        }

        if (bought.isEmpty() && sold.isEmpty()) {
            sendFeedback(source, styled("  No item data available yet.", ChatFormatting.GRAY));
        }

        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Top Buyers ─────────────────────────────

    private static int executeTopBuyers(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        sendFeedback(source, styledBold("═══════ Top Buyers ═══════", ChatFormatting.GOLD));
        sendFeedback(source, styled("  Feature coming soon — requires transaction volume tracking per player.", ChatFormatting.GRAY));
        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Top Sellers ────────────────────────────

    private static int executeTopSellers(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        sendFeedback(source, styledBold("═══════ Top Sellers ═══════", ChatFormatting.GOLD));
        sendFeedback(source, styled("  Feature coming soon — requires transaction volume tracking per player.", ChatFormatting.GRAY));
        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Force Snapshot ─────────────────────────

    private static int executeSnapshot(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();

        sendFeedback(source, styled("Taking analytics snapshot...", ChatFormatting.YELLOW));
        engine.getSnapshotScheduler().forceSnapshot("MANUAL");

        // Note: The snapshot runs asynchronously. Results will be available
        // after a brief delay. We schedule a delayed confirmation message.
        source.getServer().execute(() -> {
            sendFeedback(source, styled("Snapshot submitted. Check /analytics wealth for results in a few seconds.", ChatFormatting.GREEN));
        });

        return 1;
    }

    // ── Subcommand: Export ─────────────────────────────────

    private static int executeExport(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();

        sendFeedback(source, styledBold("═══════ Data Export ═══════", ChatFormatting.GOLD));
        sendFeedback(source, styled("  CSV export feature coming in a future update.", ChatFormatting.GRAY));
        sendFeedback(source, styled("  Current data can be queried directly from analytics.db", ChatFormatting.GRAY));
        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: History ────────────────────────────────

    private static int executeHistory(CommandContext<CommandSourceStack> context, AnalyticsEngine engine, int days) {
        CommandSourceStack source = context.getSource();
        AnalyticsDatabase db = engine.getDatabase();

        List<AnalyticsDatabase.DailyMetrics> metrics = db.getRecentDailyMetrics(days);

        sendFeedback(source, styledBold("═══════ Daily History (" + days + "d) ═══════", ChatFormatting.GOLD));

        if (metrics.isEmpty()) {
            sendFeedback(source, styled("  No daily metrics recorded yet.", ChatFormatting.GRAY));
        } else {
            for (AnalyticsDatabase.DailyMetrics day : metrics) {
                String inflationStr = day.inflationRate() != null
                    ? String.format("%+.2f%%", day.inflationRate())
                    : "N/A";

                ChatFormatting inflationColor = day.inflationRate() != null
                    ? (day.inflationRate() > 0 ? ChatFormatting.RED : ChatFormatting.GREEN)
                    : ChatFormatting.GRAY;

                sendFeedback(source,
                    styled("  " + day.date() + " ", ChatFormatting.WHITE)
                        .append(styled(day.transactionCount() + " tx ", ChatFormatting.GRAY))
                        .append(currency(formatCents(day.transactionVolume())))
                        .append(styled(" | Inflation: ", ChatFormatting.GRAY))
                        .append(styled(inflationStr, inflationColor)));
            }
        }

        sendFeedback(source, styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Component Helpers ──────────────────────────────────

    private static Component styled(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    private static Component styledBold(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withBold(true));
    }

    private static Component currency(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD);
    }

    private static String formatCents(long cents) {
        double dollars = cents / 100.0;
        if (dollars == (long) dollars) {
            return String.format("%,d", (long) dollars) + " S$";
        }
        return String.format("%,.2f", dollars) + " S$";
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    private static ChatFormatting getRatioColor(double ratio) {
        if (ratio < 0) return ChatFormatting.GRAY;
        if (ratio < 2.0) return ChatFormatting.AQUA;     // Deflation
        if (ratio < 5.0) return ChatFormatting.GREEN;     // Healthy
        if (ratio < 10.0) return ChatFormatting.YELLOW;   // Moderate inflation
        return ChatFormatting.RED;                          // High inflation
    }

    private static ChatFormatting getStatusColor(String status) {
        if (status == null) return ChatFormatting.GRAY;
        return switch (status) {
            case "HEALTHY" -> ChatFormatting.GREEN;
            case "DEFLATION" -> ChatFormatting.AQUA;
            case "MODERATE INFLATION" -> ChatFormatting.YELLOW;
            case "INFLATION WARNING" -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }

    private static ChatFormatting getRateColor(Double rate) {
        if (rate == null) return ChatFormatting.GRAY;
        if (rate > 5.0) return ChatFormatting.RED;       // High inflation
        if (rate > 2.0) return ChatFormatting.YELLOW;    // Moderate inflation
        if (rate > 0) return ChatFormatting.GREEN;       // Mild growth
        if (rate > -2.0) return ChatFormatting.AQUA;     // Mild deflation
        return ChatFormatting.BLUE;                        // Strong deflation
    }
}
