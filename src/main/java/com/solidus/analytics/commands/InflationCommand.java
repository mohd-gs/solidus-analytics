package com.solidus.analytics.commands;

import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /inflation command - Quick access to inflation data.
 *
 * <p>Provides a shorthand for the {@code /analytics inflation} subcommand,
 * allowing server administrators to quickly check the economy's inflation
 * status without navigating through the full analytics dashboard.</p>
 *
 * <h3>Usage:</h3>
 * <ul>
 *   <li>{@code /inflation} — Show current inflation report</li>
 *   <li>{@code /inflation day} — 24-hour inflation rate</li>
 *   <li>{@code /inflation week} — 7-day inflation rate</li>
 *   <li>{@code /inflation month} — 30-day inflation rate</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class InflationCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        dispatcher.register(Commands.literal("inflation")
            .requires(source -> source.hasPermission(2)) // OP level 2+
            .executes(context -> executeInflation(context, engine, "24h"))

            // /inflation day
            .then(Commands.literal("day")
                .executes(context -> executeInflation(context, engine, "24h")))

            // /inflation week
            .then(Commands.literal("week")
                .executes(context -> executeInflation(context, engine, "7d")))

            // /inflation month
            .then(Commands.literal("month")
                .executes(context -> executeInflation(context, engine, "30d")))
        );
    }

    private static int executeInflation(CommandContext<CommandSourceStack> context, AnalyticsEngine engine, String period) {
        ServerPlayer player = context.getSource().getPlayerOrException();
        InflationCalculator calculator = engine.getInflationCalculator();

        player.sendSystemMessage(styledBold("═══════ Inflation Report ═══════", ChatFormatting.GOLD));

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

                // Economic status
                ChatFormatting statusColor = getStatusColor(report.status);
                player.sendSystemMessage(
                    styled("  Economic Status: ", ChatFormatting.GRAY)
                        .append(styledBold(report.status, statusColor)));

                // Period-specific inflation rate
                player.sendSystemMessage(styledBold("  ── Inflation Rate ──", ChatFormatting.DARK_AQUA));

                Double rate = switch (period) {
                    case "7d" -> report.inflationRate7d;
                    case "30d" -> report.inflationRate30d;
                    default -> report.inflationRate24h;
                };

                String periodLabel = switch (period) {
                    case "7d" -> "7-Day";
                    case "30d" -> "30-Day";
                    default -> "24-Hour";
                };

                ChatFormatting rateColor = getRateColor(rate);
                player.sendSystemMessage(
                    styled("    " + periodLabel + ": ", ChatFormatting.GRAY)
                        .append(styled(report.formatRate(rate), rateColor)));

                // Also show all periods for reference
                if (!period.equals("24h")) {
                    player.sendSystemMessage(
                        styled("    24h: ", ChatFormatting.DARK_GRAY)
                            .append(styled(report.formatRate(report.inflationRate24h), ChatFormatting.DARK_GRAY)));
                }
                if (!period.equals("7d")) {
                    player.sendSystemMessage(
                        styled("    7d:  ", ChatFormatting.DARK_GRAY)
                            .append(styled(report.formatRate(report.inflationRate7d), ChatFormatting.DARK_GRAY)));
                }
                if (!period.equals("30d")) {
                    player.sendSystemMessage(
                        styled("    30d: ", ChatFormatting.DARK_GRAY)
                            .append(styled(report.formatRate(report.inflationRate30d), ChatFormatting.DARK_GRAY)));
                }

                // Interpretation help
                player.sendSystemMessage(styledBold("  ── Reference ──", ChatFormatting.DARK_AQUA));
                player.sendSystemMessage(styled("    Ratio < 2:1 = Deflation", ChatFormatting.AQUA));
                player.sendSystemMessage(styled("    Ratio 2-5:1 = Healthy", ChatFormatting.GREEN));
                player.sendSystemMessage(styled("    Ratio 5-10:1 = Moderate Inflation", ChatFormatting.YELLOW));
                player.sendSystemMessage(styled("    Ratio > 10:1 = Inflation Warning", ChatFormatting.RED));

                player.sendSystemMessage(styledBold("═══════════════════════════════════", ChatFormatting.GOLD));
            });
        });

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

    private static ChatFormatting getRatioColor(double ratio) {
        if (ratio < 0) return ChatFormatting.GRAY;
        if (ratio < 2.0) return ChatFormatting.AQUA;
        if (ratio < 5.0) return ChatFormatting.GREEN;
        if (ratio < 10.0) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
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
        if (rate > 5.0) return ChatFormatting.RED;
        if (rate > 2.0) return ChatFormatting.YELLOW;
        if (rate > 0) return ChatFormatting.GREEN;
        if (rate > -2.0) return ChatFormatting.AQUA;
        return ChatFormatting.BLUE;
    }
}
