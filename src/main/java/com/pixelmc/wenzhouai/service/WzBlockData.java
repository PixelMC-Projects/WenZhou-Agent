/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import com.pixelmc.pixelmcmanager.api.WorldBlockGameplayApi;

import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;

/** Resolves WenZhou's public field query to an existing question reference before dispatch. */
final class WzBlockData {
    private static final int MAX_BYTES = 6 * 1024;

    record Query(String ref, String path, int offset, int limit) {
        static Query parse(JsonObject args, boolean read) {
            if (args == null
                    || args.keySet().stream()
                            .anyMatch(
                                    key ->
                                            !key.equals("ref")
                                                    && !key.equals("path")
                                                    && !key.equals("offset")
                                                    && !key.equals("limit"))) return null;
            String ref = text(args, "ref", "");
            String path = text(args, "path", "");
            if (ref == null || !ref.matches("b[1-8]") || path == null || path.length() > 256)
                return null;
            if (read && path.isEmpty() || !path.isEmpty() && !path.startsWith("/")) return null;
            String[] tokens = path.isEmpty() ? new String[0] : path.substring(1).split("/", -1);
            if (tokens.length > 12) return null;
            for (String token : tokens) {
                if (token.isEmpty()
                        || token.matches(".*~(?![01]).*")
                        || token.chars().anyMatch(Character::isISOControl)) return null;
            }
            try {
                int offset = integer(args, "offset", 0);
                int limit = integer(args, "limit", 12);
                return offset >= 0 && limit >= 1 && limit <= 32
                        ? new Query(ref, path, offset, limit)
                        : null;
            } catch (ArithmeticException | IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    /** Preflight only. The provider independently revalidates access on each read. */
    static JsonObject authorization(ServerPlayer player, WzWorldReferences.Look look) {
        try {
            if (WorldBlockGameplayApi.protocolVersion() != 1) return unavailable();
            var result = WorldBlockGameplayApi.authorize(player, target(look));
            return "ok".equals(result.get("status").getAsString()) ? null : result;
        } catch (LinkageError missing) {
            return unavailable();
        }
    }

    static JsonObject inspect(
            ServerPlayer player, WzWorldReferences.Look look, Query query, boolean read) {
        try {
            if (WorldBlockGameplayApi.protocolVersion() != 1) return unavailable(query);
            var result =
                    WorldBlockGameplayApi.inspect(
                            player, target(look), query.path, query.offset, query.limit, read);
            if (result.has("path")) result.addProperty("ref", query.ref);
            return result;
        } catch (LinkageError missing) {
            return unavailable(query);
        }
    }

    private static WorldBlockGameplayApi.Target target(WzWorldReferences.Look look) {
        return new WorldBlockGameplayApi.Target(
                look.level(), look.pos(), look.state().getBlock(), look.entity(), look.face());
    }

    private static JsonObject unavailable() {
        return error("unavailable", "当前服务端 Manager 尚未提供 world_block.read。");
    }

    private static JsonObject unavailable(Query query) {
        var result = unavailable();
        result.addProperty("ref", query.ref);
        result.addProperty("path", query.path);
        return result;
    }

    private WzBlockData() {}

    static JsonObject bounded(JsonObject result) {
        return utf8(result) <= MAX_BYTES ? result : error("unavailable", "字段回执超出单次大小限制，请缩小查询范围。");
    }

    private static int utf8(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static JsonObject error(String status, String message) {
        var error = new JsonObject();
        error.addProperty("status", status);
        error.addProperty("message", message);
        return error;
    }

    private static String text(JsonObject args, String field, String fallback) {
        var value = args.get(field);
        if (value == null) return fallback;
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static int integer(JsonObject args, String field, int fallback) {
        var value = args.get(field);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }
}
