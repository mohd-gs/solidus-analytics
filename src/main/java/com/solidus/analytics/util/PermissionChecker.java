package com.solidus.analytics.util;

import com.solidus.analytics.SolidusAnalyticsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Local permission checking utility for Solidus Analytics.
 * Integrates with LuckPerms via reflection when available,
 * falls back to vanilla OP levels otherwise.
 */
public final class PermissionChecker {

    private static Boolean luckPermsLoaded = null;
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionFailed = false;
    private static java.lang.reflect.Method getApiMethod;
    private static java.lang.reflect.Method getUserManagerMethod;
    private static java.lang.reflect.Method getUserMethod;
    private static java.lang.reflect.Method getCachedDataMethod;
    private static java.lang.reflect.Method getPermissionDataMethod;
    private static java.lang.reflect.Method checkPermissionMethod;

    private PermissionChecker() {}

    public static boolean check(CommandSourceStack source, String permission, int defaultOpLevel) {
        if (isLuckPermsLoaded()) {
            try {
                ServerPlayer player = source.getPlayer();
                if (player != null) {
                    return checkLuckPerms(player.getUUID(), player, permission, defaultOpLevel);
                }
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {}
        }
        return source.hasPermission(defaultOpLevel);
    }

    public static boolean check(ServerPlayer player, String permission, int defaultOpLevel) {
        if (isLuckPermsLoaded()) {
            return checkLuckPerms(player.getUUID(), player, permission, defaultOpLevel);
        }
        return player.hasPermission(defaultOpLevel);
    }

    public static Predicate<CommandSourceStack> require(String permission, int defaultOpLevel) {
        return source -> check(source, permission, defaultOpLevel);
    }

    public static boolean isLuckPermsAvailable() {
        return isLuckPermsLoaded();
    }

    private static boolean isLuckPermsLoaded() {
        if (luckPermsLoaded == null) {
            luckPermsLoaded = FabricLoader.getInstance().isModLoaded("luckperms");
            if (luckPermsLoaded) {
                SolidusAnalyticsMod.LOGGER.info("[Solidus-Analytics] LuckPerms detected — fine-grained permission nodes active.");
            }
        }
        return luckPermsLoaded;
    }

    private static synchronized void initLuckPermsReflection() {
        if (reflectionInitialized || reflectionFailed) return;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            getApiMethod = providerClass.getMethod("get");
            Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms");
            getUserManagerMethod = apiClass.getMethod("getUserManager");
            Class<?> userManagerClass = Class.forName("net.luckperms.api.manager.UserManager");
            getUserMethod = userManagerClass.getMethod("getUser", UUID.class);
            Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
            getCachedDataMethod = userClass.getMethod("getCachedData");
            Class<?> cachedDataClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
            getPermissionDataMethod = cachedDataClass.getMethod("getPermissionData");
            Class<?> permissionDataClass = Class.forName("net.luckperms.api.cacheddata.PermissionData");
            checkPermissionMethod = permissionDataClass.getMethod("checkPermission", String.class);
            reflectionInitialized = true;
        } catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.warn("[Solidus-Analytics] LuckPerms reflection init failed: {}", e.getMessage());
            reflectionFailed = true;
            luckPermsLoaded = false;
        }
    }

    private static boolean checkLuckPerms(UUID playerUuid, ServerPlayer player, String permission, int defaultOpLevel) {
        if (!reflectionInitialized) {
            initLuckPermsReflection();
            if (!reflectionInitialized) return player.hasPermission(defaultOpLevel);
        }
        try {
            Object api = getApiMethod.invoke(null);
            Object userManager = getUserManagerMethod.invoke(api);
            Object user = getUserMethod.invoke(userManager, playerUuid);
            if (user == null) return player.hasPermission(defaultOpLevel);
            Object cachedData = getCachedDataMethod.invoke(user);
            Object permissionData = getPermissionDataMethod.invoke(cachedData);
            Object result = checkPermissionMethod.invoke(permissionData, permission);
            String resultStr = result.toString();
            return switch (resultStr) {
                case "TRUE" -> true;
                case "FALSE" -> false;
                default -> player.hasPermission(defaultOpLevel);
            };
        } catch (Exception e) {
            return player.hasPermission(defaultOpLevel);
        }
    }
}
