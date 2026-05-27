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

    // ── Subcommand: Dashboard ──────────────────────────────

    private static int executeDashboard(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        LiveMetricsTracker metrics = engine.getLiveMetrics();

        player.sendSystemMessage(styledBold("═══════ Solidus Analytics ═══════", ChatFormatting.GOLD));
        player.sendSystemMessage(styled("  Live Economy Dashboard", ChatFormatting.YELLOW));

        // Daily volume
        player.sendSystemMessage(
            styled("  Daily Volume: ", ChatFormatting.GRAY)
                .append(currency(formatCents(metrics.getDailyVolumeCents()))));

        // Transaction count
        player.sendSystemMessage(
            styled("  Transactions Today: ", ChatFormatting.GRAY)
                .append(styled(String.valueOf(metrics.getDailyTransactionCount()), ChatFormatting.WHITE)));

        // Active players
        player.sendSystemMessage(
            styled("  Active Players: ", ChatFormatting.GRAY)
                .append(styled(String.valueOf(metrics.getActivePlayerCount()), ChatFormatting.WHITE)));

        // Breakdown by type
        Map<String, Long> typeCounts = metrics.getTransactionsByType();
        if (!typeCounts.isEmpty()) {
            player.sendSystemMessage(styledBold("  ── Transaction Breakdown ──", ChatFormatting.DARK_AQUA));
            typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .forEach(entry -> player.sendSystemMessage(
                    styled("    " + entry.getKey() + ": ", ChatFormatting.GRAY)
                        .append(styled(String.valueOf(entry.getValue()), ChatFormatting.WHITE))));
        }

        // Top bought items
        Map<String, Long> topBought = metrics.getTopBoughtItems(5);
        if (!topBought.isEmpty()) {
            player.sendSystemMessage(styledBold("  ── Top Bought Items ──", ChatFormatting.DARK_AQUA));
            topBought.forEach((material, qty) -> player.sendSystemMessage(
                styled("    " + material + ": ", ChatFormatting.GRAY)
                    .append(styled(qty + " units", ChatFormatting.WHITE))));
        }

        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Wealth Distribution ────────────────────

    private static int executeWealth(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AnalyticsDatabase db = engine.getDatabase();

        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();

        player.sendSystemMessage(styledBold("═══════ Wealth Distribution ═══════", ChatFormatting.GOLD));

        if (latest == null) {
            player.sendSystemMessage(styled("  No snapshot data available yet.", ChatFormatting.GRAY));
            player.sendSystemMessage(styled("  Snapshots are taken every 30 minutes.", ChatFormatting.GRAY));
            player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
            return 1;
        }

        // Gini coefficient
        player.sendSystemMessage(
            styled("  Gini Coefficient: ", ChatFormatting.GRAY)
                .append(styled(String.format("%.4f", latest.giniCoefficient()), ChatFormatting.WHITE))
                .append(styled(" (" + GiniCoefficient.interpret(latest.giniCoefficient()) + ")", ChatFormatting.YELLOW)));

        // Total wealth
        player.sendSystemMessage(
            styled("  Total Wealth: ", ChatFormatting.GRAY)
                .append(currency(formatCents(latest.totalWealth()))));

        // Player count
        player.sendSystemMessage(
            styled("  Player Count: ", ChatFormatting.GRAY)
                .append(styled(String.valueOf(latest.playerCount()), ChatFormatting.WHITE)));

        // Average balance
        player.sendSystemMessage(
            styled("  Average Balance: ", ChatFormatting.GRAY)
                .append(currency(formatCents(latest.avgBalance()))));

        // Median balance
        player.sendSystemMessage(
            styled("  Median Balance: ", ChatFormatting.GRAY)
                .append(currency(formatCents(latest.medianBalance()))));

        // Top 1% share
        player.sendSystemMessage(
            styled("  Top 1% Wealth Share: ", ChatFormatting.GRAY)
                .append(styled(String.format("%.1f%%", latest.top1PercentShare() * 100),
                    latest.top1PercentShare() > 0.3 ? ChatFormatting.RED : ChatFormatting.GREEN)));

        // Auction data
        player.sendSystemMessage(
            styled("  Active Auctions: ", ChatFormatting.GRAY)
                .append(styled(latest.auctionActiveListings() + " listings ", ChatFormatting.WHITE))
                .append(currency("(" + formatCents(latest.auctionTotalValue()) + ")")));

        // Snapshot timestamp
        long ageSeconds = (System.currentTimeMillis() - latest.timestamp()) / 1000;
        player.sendSystemMessage(
            styled("  Snapshot age: ", ChatFormatting.GRAY)
                .append(styled(formatDuration(ageSeconds), ChatFormatting.DARK_GRAY)));

        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Inflation ──────────────────────────────

    private static int executeInflation(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        InflationCalculator calculator = engine.getInflationCalculator();

        player.sendSystemMessage(styledBold("═══════ Inflation Report ═══════", ChatFormatting.GOLD));

        // Calculate async and display
        calculator.calculateAsync().thenAccept(report -> {
            player.server.execute(() -> {
                // Money supply
                player.sendSystemMessage(
                    styled("  Money Supply: ", ChatFormatting.GRAY)
                        .append(currency(report.formatMoneySupply())));

                // Goods value
                player.sendSystemMessage(
                    styled("  Goods Value: ", ChatFormatting.GRAY)
                        .append(currency(report.formatGoodsValue())));

                // Money-to-Goods ratio
                ChatFormatting ratioColor = getRatioColor(report.moneyToGoodsRatio);
                player.sendSystemMessage(
                    styled("  Money:Goods Ratio: ", ChatFormatting.GRAY)
                        .append(styled(report.formatRatio(), ratioColor)));

                // Status
                ChatFormatting statusColor = getStatusColor(report.status);
                player.sendSystemMessage(
                    styled("  Status: ", ChatFormatting.GRAY)
                        .append(styledBold(report.status, statusColor)));

                // Inflation rates
                player.sendSystemMessage(styledBold("  ── Inflation Rates ──", ChatFormatting.DARK_AQUA));
                player.sendSystemMessage(
                    styled("    24h: ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(report.inflationRate24h),
                            getRateColor(report.inflationRate24h))));
                player.sendSystemMessage(
                    styled("    7d:  ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(report.inflationRate7d),
                            getRateColor(report.inflationRate7d))));
                player.sendSystemMessage(
                    styled("    30d: ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(report.inflationRate30d),
                            getRateColor(report.inflationRate30d))));

                player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
            });
        });

        return 1;
    }

    // ── Subcommand: Top Items ──────────────────────────────

    private static int executeTopItems(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        LiveMetricsTracker metrics = engine.getLiveMetrics();

        player.sendSystemMessage(styledBold("═══════ Top Items ═══════", ChatFormatting.GOLD));

        Map<String, Long> bought = metrics.getTopBoughtItems(10);
        if (!bought.isEmpty()) {
            player.sendSystemMessage(styledBold("  ── Most Bought ──", ChatFormatting.GREEN));
            int rank = 1;
            for (Map.Entry<String, Long> entry : bought.entrySet()) {
                player.sendSystemMessage(
                    styled("    #" + rank + " ", ChatFormatting.YELLOW)
                        .append(styled(entry.getKey() + ": ", ChatFormatting.WHITE))
                        .append(styled(entry.getValue() + " units", ChatFormatting.GRAY)));
                rank++;
            }
        }

        Map<String, Long> sold = metrics.getTopSoldItems(10);
        if (!sold.isEmpty()) {
            player.sendSystemMessage(styledBold("  ── Most Sold ──", ChatFormatting.RED));
            int rank = 1;
            for (Map.Entry<String, Long> entry : sold.entrySet()) {
                player.sendSystemMessage(
                    styled("    #" + rank + " ", ChatFormatting.YELLOW)
                        .append(styled(entry.getKey() + ": ", ChatFormatting.WHITE))
                        .append(styled(entry.getValue() + " units", ChatFormatting.GRAY)));
                rank++;
            }
        }

        if (bought.isEmpty() && sold.isEmpty()) {
            player.sendSystemMessage(styled("  No item data available yet.", ChatFormatting.GRAY));
        }

        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Top Buyers ─────────────────────────────

    private static int executeTopBuyers(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        player.sendSystemMessage(styledBold("═══════ Top Buyers ═══════", ChatFormatting.GOLD));
        player.sendSystemMessage(styled("  Feature coming soon — requires transaction volume tracking per player.", ChatFormatting.GRAY));
        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Top Sellers ────────────────────────────

    private static int executeTopSellers(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        player.sendSystemMessage(styledBold("═══════ Top Sellers ═══════", ChatFormatting.GOLD));
        player.sendSystemMessage(styled("  Feature coming soon — requires transaction volume tracking per player.", ChatFormatting.GRAY));
        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: Force Snapshot ─────────────────────────

    private static int executeSnapshot(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();

        player.sendSystemMessage(styled("Taking analytics snapshot...", ChatFormatting.YELLOW));
        engine.getSnapshotScheduler().forceSnapshot("MANUAL");

        player.server.execute(() -> {
            player.sendSystemMessage(styled("Snapshot taken successfully. Check /analytics wealth for results.", ChatFormatting.GREEN));
        });

        return 1;
    }

    // ── Subcommand: Export ─────────────────────────────────

    private static int executeExport(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        ServerPlayer player = context.getSource().getPlayerOrException();

        player.sendSystemMessage(styledBold("═══════ Data Export ═══════", ChatFormatting.GOLD));
        player.sendSystemMessage(styled("  CSV export feature coming in a future update.", ChatFormatting.GRAY));
        player.sendSystemMessage(styled("  Current data can be queried directly from analytics.db", ChatFormatting.GRAY));
        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
        return 1;
    }

    // ── Subcommand: History ────────────────────────────────

    private static int executeHistory(CommandContext<CommandSourceStack> context, AnalyticsEngine engine, int days) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AnalyticsDatabase db = engine.getDatabase();

        List<AnalyticsDatabase.DailyMetrics> metrics = db.getRecentDailyMetrics(days);

        player.sendSystemMessage(styledBold("═══════ Daily History (" + days + "d) ═══════", ChatFormatting.GOLD));

        if (metrics.isEmpty()) {
            player.sendSystemMessage(styled("  No daily metrics recorded yet.", ChatFormatting.GRAY));
        } else {
            for (AnalyticsDatabase.DailyMetrics day : metrics) {
                String inflationStr = day.inflationRate() != null
                    ? String.format("%+.2f%%", day.inflationRate())
                    : "N/A";

                ChatFormatting inflationColor = day.inflationRate() != null
                    ? (day.inflationRate() > 0 ? ChatFormatting.RED : ChatFormatting.GREEN)
                    : ChatFormatting.GRAY;

                player.sendSystemMessage(
                    styled("  " + day.date() + " ", ChatFormatting.WHITE)
                        .append(styled(day.transactionCount() + " tx ", ChatFormatting.GRAY))
                        .append(currency(formatCents(day.transactionVolume())))
                        .append(styled(" | Inflation: ", ChatFormatting.GRAY))
                        .append(styled(inflationStr, inflationColor)));
            }
        }

        player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
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
