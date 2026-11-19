package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.ManagerStore.rows;
import static com.pixelmc.manager.service.ManagerStore.update;
import static com.pixelmc.manager.service.Wire.text;
import static com.pixelmc.manager.service.Wire.uuid;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Durable WenZhou budgets. The profile row serializes all mutations for one player. */
final class WenzhouQuotaStore {
    static final Set<String> TABLES = Set.of("wz_quota_config", "wz_quota_accounts", "wz_quota_receipts");
    private static final long WEEK = 7 * 24 * 60 * 60_000L;
    private static final long MAX_AMOUNT = 9_007_199_254_740_991L;

    static void migrate(Connection c) throws Exception {
        update(c, """
                CREATE TABLE IF NOT EXISTS wz_quota_config (
                  id INT PRIMARY KEY, enabled BOOLEAN NOT NULL, weekly_limit BIGINT NOT NULL
                ) ENGINE=InnoDB""");
        update(c, """
                CREATE TABLE IF NOT EXISTS wz_quota_accounts (
                  uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY, mode VARCHAR(12) NOT NULL DEFAULT 'inherit',
                  custom_limit BIGINT NOT NULL DEFAULT 0, period_start BIGINT NOT NULL DEFAULT 0,
                  effective_start BIGINT NOT NULL DEFAULT 0, spent BIGINT NOT NULL DEFAULT 0,
                  lifetime BIGINT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 0,
                  INDEX(period_start)
                ) ENGINE=InnoDB""");
        update(c, """
                CREATE TABLE IF NOT EXISTS wz_quota_receipts (
                  uuid CHAR(36) CHARACTER SET ascii NOT NULL, receipt_id VARCHAR(48) CHARACTER SET ascii NOT NULL,
                  amount BIGINT NOT NULL, request_at BIGINT NOT NULL, PRIMARY KEY(uuid,receipt_id)
                ) ENGINE=InnoDB""");
        update(c, "ALTER TABLE wz_quota_config ADD COLUMN IF NOT EXISTS reset_revision BIGINT NOT NULL DEFAULT 0");
        update(c, "ALTER TABLE wz_quota_accounts ADD COLUMN IF NOT EXISTS limited_mode VARCHAR(12) NULL,"
                + " ADD COLUMN IF NOT EXISTS has_custom BOOLEAN NOT NULL DEFAULT FALSE");
        update(c, "INSERT IGNORE INTO wz_quota_config(id,enabled,weekly_limit) VALUES(1,FALSE,2000000000)");
    }

    static JsonObject request(ManagerStore store, String owner, String op, JsonObject in) throws Exception {
        if (!owner.equals("wenzhouai")) throw new SecurityException("额度存储命名空间无效");
        try (Connection c = store.open()) {
            c.setAutoCommit(false);
            try {
                JsonObject result = transact(c, op, in);
                c.commit();
                return result;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static JsonObject transact(Connection c, String op, JsonObject in) throws Exception {
        long now = System.currentTimeMillis();
        if (op.equals("quota.sweep")) {
            // Indexed, bounded updates. Concurrent opens/resets no longer match this predicate.
            int count = update(c, """
                    UPDATE wz_quota_accounts SET period_start=0,effective_start=0,spent=0
                    WHERE period_start>0 AND period_start<=? ORDER BY period_start LIMIT 256""", now - WEEK);
            JsonObject result = new JsonObject();
            result.addProperty("more", count == 256);
            return result;
        }
        if (op.equals("quota.resetall")) {
            long revision = bounded(in, "resetRevision", 0, MAX_AMOUNT - 1);
            if (update(c, "UPDATE wz_quota_config SET reset_revision=reset_revision+1 WHERE id=1 AND reset_revision=?", revision) != 1)
                throw new IllegalStateException("已有新的重置操作，请刷新后重试");
            int affected = update(c, """
                    UPDATE wz_quota_accounts SET period_start=?,effective_start=?,spent=0,revision=revision+1
                    WHERE period_start>? AND period_start<=?""", now - now % 60_000, now, now - WEEK, now);
            JsonObject result = new JsonObject();
            result.addProperty("resetRevision", revision + 1);
            result.addProperty("affected", affected);
            return result;
        }
        if (op.equals("quota.configure")) {
            if (!(in.get("quotaEnabled") instanceof JsonPrimitive flag) || !flag.isBoolean())
                throw new IllegalArgumentException("需要指定额度开关");
            update(c, "UPDATE wz_quota_config SET enabled=?,weekly_limit=? WHERE id=1",
                    flag.getAsBoolean(), amount(in, "weeklyQuotaNanoYuan"));
        }
        JsonObject config = rows(c, "SELECT enabled,weekly_limit,reset_revision FROM wz_quota_config WHERE id=1").get(0);
        if (op.equals("quota.configure") || op.equals("quota.config")) {
            JsonObject result = new JsonObject();
            result.add("resetRevision", config.get("reset_revision"));
            result.add("quotaEnabled", config.get("enabled"));
            result.add("weeklyQuotaNanoYuan", config.get("weekly_limit"));
            return result;
        }
        if (op.equals("quota.rows")) return batch(c, in, config, now);
        String player = uuid(text(in, "player", 36));
        if (op.equals("quota.view")) return view(find(c, player, false), config, now);
        if (rows(c, """
                SELECT uuid FROM pm_profiles WHERE uuid=?
                AND uuid NOT IN(SELECT uuid FROM pm_profile_exclusions) FOR UPDATE""", player).isEmpty())
            throw new IllegalArgumentException("玩家未登记");
        update(c, "INSERT IGNORE INTO wz_quota_accounts(uuid) VALUES(?)", player);
        JsonObject account = rows(c, "SELECT * FROM wz_quota_accounts WHERE uuid=? FOR UPDATE", player).get(0);
        long start = metric(account, "period_start");
        if (start != 0 && now >= start + WEEK) {
            update(c, "UPDATE wz_quota_accounts SET period_start=0,effective_start=0,spent=0 WHERE uuid=?", player);
            account.addProperty("period_start", 0);
            account.addProperty("spent", 0);
            account.addProperty("effective_start", 0);
        }
        switch (op) {
            case "quota.open" -> {
                JsonObject status = view(account, config, now);
                boolean allowed = !status.get("exhausted").getAsBoolean();
                if (allowed && metric(account, "period_start") == 0) {
                    long received = bounded(in, "receivedAt", 1, Long.MAX_VALUE);
                    long anchor = Math.min(now, received);
                    if (anchor <= now - WEEK) throw new IllegalArgumentException("请求已过期");
                    start(c, player, anchor);
                    status = view(find(c, player, true), config, now);
                }
                status.addProperty("allowed", allowed);
                return status;
            }
            case "quota.charge", "quota.legacy" -> {
                boolean legacy = op.equals("quota.legacy");
                String id = legacy ? "legacy:" + uuid(text(in, "source", 36)) : uuid(text(in, "receiptId", 36));
                long at = legacy ? 0 : bounded(in, "requestAt", 1, Long.MAX_VALUE);
                long cost = amount(in, "amount");
                var prior = rows(c, "SELECT amount,request_at FROM wz_quota_receipts WHERE uuid=? AND receipt_id=? FOR UPDATE", player, id);
                if (prior.isEmpty()) {
                    update(c, "INSERT INTO wz_quota_receipts(uuid,receipt_id,amount,request_at) VALUES(?,?,?,?)", player, id, cost, at);
                    boolean current = !legacy && metric(account, "period_start") > 0 && at >= metric(account, "effective_start");
                    update(c, "UPDATE wz_quota_accounts SET lifetime=lifetime+?,spent=spent+? WHERE uuid=?", cost, current ? cost : 0, player);
                } else if (metric(prior.get(0), "amount") != cost || metric(prior.get(0), "request_at") != at) {
                    throw new IllegalArgumentException("回执与已入账的金额或时间不一致");
                }
            }
            case "quota.edit", "quota.reset" -> {
                if (metric(account, "revision") != bounded(in, "revision", 0, Long.MAX_VALUE))
                    throw new IllegalStateException("额度已更新，请刷新后重试");
                if (op.equals("quota.reset")) start(c, player, now);
                else {
                    String mode = text(in, "mode", 12);
                    if (!Set.of("inherit", "unlimited", "custom").contains(mode))
                        throw new IllegalArgumentException("无效的个人额度模式");
                    String limited = mode.equals("unlimited") ? limitedMode(account) : mode;
                    if (mode.equals("unlimited") && in.has("limitedMode")) limited = text(in, "limitedMode", 12);
                    if (!Set.of("inherit", "custom").contains(limited))
                        throw new IllegalArgumentException("无效的限额来源");
                    boolean hasCustom = hasCustom(account);
                    long limit = metric(account, "custom_limit");
                    if ((mode.equals("custom") || mode.equals("unlimited")) && in.has("limitNanoYuan")) {
                        limit = amount(in, "limitNanoYuan");
                        hasCustom = true;
                    }
                    if (limited.equals("custom") && !hasCustom)
                        throw new IllegalArgumentException("需要指定个人周额度");
                    update(c, "UPDATE wz_quota_accounts SET mode=?,limited_mode=?,has_custom=?,custom_limit=? WHERE uuid=?",
                            mode, limited, hasCustom, limit, player);
                }
                update(c, "UPDATE wz_quota_accounts SET revision=revision+1 WHERE uuid=?", player);
            }
            default -> throw new IllegalArgumentException("无效的额度操作");
        }
        return view(find(c, player, true), config, now);
    }

    private static void start(Connection c, String player, long instant) throws Exception {
        update(c, "UPDATE wz_quota_accounts SET period_start=?,effective_start=?,spent=0 WHERE uuid=?",
                instant - instant % 60_000, instant, player);
    }

    private static JsonObject batch(Connection c, JsonObject in, JsonObject config, long now) throws Exception {
        var source = Wire.array(in, "players", 64);
        List<String> ids = new ArrayList<>();
        JsonObject result = new JsonObject();
        for (var entry : source) {
            String id = uuid(entry.getAsString());
            ids.add(id);
            result.add(id, view(new JsonObject(), config, now));
        }
        if (!ids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            for (var row : rows(c, "SELECT * FROM wz_quota_accounts WHERE uuid IN (" + placeholders + ")", ids.toArray()))
                result.add(row.get("uuid").getAsString(), view(row, config, now));
        }
        return result;
    }

    private static JsonObject find(Connection c, String id, boolean lock) throws Exception {
        var found = rows(c, "SELECT * FROM wz_quota_accounts WHERE uuid=?" + (lock ? " FOR UPDATE" : ""), id);
        return found.isEmpty() ? new JsonObject() : found.get(0);
    }

    static JsonObject view(JsonObject row, JsonObject config, long now) {
        String mode = row.has("mode") ? row.get("mode").getAsString() : "inherit";
        boolean unlimited = mode.equals("unlimited");
        boolean enabled = config.get("enabled").getAsBoolean();
        long limit = unlimited ? 0 : mode.equals("custom") ? metric(row, "custom_limit") : metric(config, "weekly_limit");
        long start = metric(row, "period_start");
        if (start + WEEK <= now) start = 0;
        long spent = start == 0 ? 0 : metric(row, "spent");
        long balance = Math.max(0, limit - spent);
        JsonObject result = new JsonObject();
        result.addProperty("enabled", enabled);
        result.addProperty("mode", mode);
        result.addProperty("limitedMode", limitedMode(row));
        if (hasCustom(row)) result.addProperty("customLimitNanoYuan", metric(row, "custom_limit"));
        else result.add("customLimitNanoYuan", com.google.gson.JsonNull.INSTANCE);
        result.addProperty("defaultLimitNanoYuan", metric(config, "weekly_limit"));
        result.addProperty("unlimited", unlimited);
        result.addProperty("limitNanoYuan", limit);
        result.addProperty("remainingNanoYuan", balance);
        result.addProperty("spentNanoYuan", spent);
        result.addProperty("periodStart", start);
        result.addProperty("resetAt", start == 0 ? 0 : start + WEEK);
        result.addProperty("remainingPercent", balance == 0 ? 0 : Math.min(100, (int)Math.ceil(balance * 100.0 / limit)));
        result.addProperty("exhausted", enabled && !unlimited && balance == 0);
        result.addProperty("lifetimeNanoYuan", metric(row, "lifetime"));
        result.addProperty("revision", metric(row, "revision"));
        return result;
    }

    private static String limitedMode(JsonObject row) {
        if (row.has("limited_mode") && !row.get("limited_mode").isJsonNull()) return row.get("limited_mode").getAsString();
        return row.has("mode") && row.get("mode").getAsString().equals("custom") ? "custom" : "inherit";
    }

    private static boolean hasCustom(JsonObject row) {
        return row.has("has_custom") && row.get("has_custom").getAsBoolean() || limitedMode(row).equals("custom");
    }

    private static long amount(JsonObject in, String key) {
        return bounded(in, key, 0, MAX_AMOUNT);
    }

    private static long bounded(JsonObject in, String key, long min, long max) {
        if (!(in.get(key) instanceof JsonPrimitive value) || !value.isNumber())
            throw new IllegalArgumentException("缺少有效数字");
        long n = value.getAsBigDecimal().longValueExact();
        if (n < min || n > max) throw new IllegalArgumentException("数字超出范围");
        return n;
    }

    private static long metric(JsonObject row, String key) {
        return row.has(key) ? row.get(key).getAsLong() : 0;
    }
}

