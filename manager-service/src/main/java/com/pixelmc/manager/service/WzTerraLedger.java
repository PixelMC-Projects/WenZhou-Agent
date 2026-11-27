package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.ManagerStore.rows;
import static com.pixelmc.manager.service.ManagerStore.update;

import com.google.gson.JsonObject;

import java.sql.Connection;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Manager-owned authorization and request accounting for the Terra project route. */
public final class WzTerraLedger {
    static final Set<String> TABLES =
            Set.of("wz_terra_config", "wz_terra_grants", "wz_terra_balances", "wz_terra_receipts");

    private static final Set<String> EFFORTS = Set.of("high", "xhigh", "max");
    private static final Set<String> QUOTA_MODES = Set.of("inherit", "custom", "unlimited");
    static final long MAX_LIMIT_NANO_USD = 9_007_199_254_740_991L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long LONG_CONTEXT_THRESHOLD = 272_000L;
    private static final long INPUT_PRICE = 1_000L;
    private static final long CACHE_READ_PRICE = 50L;
    private static final long CACHE_WRITE_PRICE = 1_250L;
    private static final long OUTPUT_PRICE = 5_000L;
    private static final int MIN_OUTPUT = 16;
    private final ManagerStore store;
    private final Clock clock;

    public WzTerraLedger(ManagerStore store) {
        this(store, Clock.systemUTC());
    }

    WzTerraLedger(ManagerStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    static void migrate(Connection c) throws Exception {
        update(
                c,
                """
                CREATE TABLE IF NOT EXISTS wz_terra_config (
                  id INT PRIMARY KEY, effort VARCHAR(8) CHARACTER SET ascii NOT NULL,
                  fast BOOLEAN NOT NULL, revision BIGINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB""");
        update(
                c,
                """
ALTER TABLE wz_terra_config
  ADD COLUMN IF NOT EXISTS weekly_limit BIGINT NOT NULL DEFAULT 2000000000,
  ADD COLUMN IF NOT EXISTS monthly_limit BIGINT NOT NULL DEFAULT 8000000000,
  ADD COLUMN IF NOT EXISTS server_monthly_limit BIGINT NOT NULL DEFAULT 160000000000,
  ADD COLUMN IF NOT EXISTS quota_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS server_quota_enabled BOOLEAN NOT NULL DEFAULT TRUE""");
        update(
                c,
                """
CREATE TABLE IF NOT EXISTS wz_terra_grants (
  uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY, granted BOOLEAN NOT NULL DEFAULT FALSE,
  revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB""");
        update(
                c,
                """
                ALTER TABLE wz_terra_grants
                  ADD COLUMN IF NOT EXISTS quota_mode VARCHAR(12) NOT NULL DEFAULT 'inherit',
                  ADD COLUMN IF NOT EXISTS custom_weekly_limit BIGINT NULL,
                  ADD COLUMN IF NOT EXISTS custom_monthly_limit BIGINT NULL""");
        update(
                c,
                """
CREATE TABLE IF NOT EXISTS wz_terra_balances (
  bucket VARCHAR(8) CHARACTER SET ascii NOT NULL, subject VARCHAR(36) CHARACTER SET ascii NOT NULL,
  period_start BIGINT NOT NULL, held_nano_usd BIGINT NOT NULL,
  PRIMARY KEY(bucket,subject,period_start)
) ENGINE=InnoDB""");
        update(
                c,
                """
CREATE TABLE IF NOT EXISTS wz_terra_receipts (
  operation_id CHAR(36) CHARACTER SET ascii PRIMARY KEY, uuid CHAR(36) CHARACTER SET ascii NOT NULL,
  reserved_at BIGINT NOT NULL, week_start BIGINT NOT NULL, month_start BIGINT NOT NULL,
  effort VARCHAR(8) CHARACTER SET ascii NOT NULL, fast BOOLEAN NOT NULL,
  input_bound BIGINT NOT NULL, max_output INT NOT NULL,
  reserved_nano_usd BIGINT NOT NULL, actual_nano_usd BIGINT NULL, settled_at BIGINT NULL,
  INDEX(uuid,reserved_at)
) ENGINE=InnoDB""");
        update(c, "INSERT IGNORE INTO wz_terra_config(id,effort,fast) VALUES(1,'high',FALSE)");
    }

    public JsonObject account(UUID player) throws Exception {
        String id = player.toString();
        return transaction(c -> account(c, id, config(c, false), clock.millis()));
    }

    public JsonObject grant(UUID player, boolean granted, long revision) throws Exception {
        return grant(player, granted, revision, null);
    }

    JsonObject grant(UUID player, boolean granted, long revision, UUID actor) throws Exception {
        String id = player.toString();
        return transaction(
                c -> {
                    // Use the same lock order as admission. A permission change applies to future
                    // calls.
                    Config policy = config(c, true);
                    requireProfile(c, id);
                    if (granted) requireBaseAccess(c, id);
                    update(c, "INSERT IGNORE INTO wz_terra_grants(uuid) VALUES(?)", id);
                    if (update(
                                    c,
                                    """
                                    UPDATE wz_terra_grants SET granted=?,revision=revision+1
                                    WHERE uuid=? AND revision=?""",
                                    granted,
                                    id,
                                    revision)
                            != 1) throw new IllegalStateException("Terra 玩家权限已更新，请刷新后重试");
                    profileChanged(c, id);
                    if (actor != null)
                        ManagerStore.audit(
                                c,
                                actor.toString(),
                                "WENZHOU_TERRA_GRANT",
                                Map.of("playerId", id, "granted", granted));
                    return account(c, id, policy, clock.millis());
                });
    }

    public JsonObject editQuota(
            UUID player, String mode, Long weekly, Long monthly, long revision, UUID actor)
            throws Exception {
        if (mode == null || !QUOTA_MODES.contains(mode))
            throw new IllegalArgumentException("Terra 个人额度模式无效");
        boolean custom = mode.equals("custom");
        if (!custom && (weekly != null || monthly != null))
            throw new IllegalArgumentException("仅自定义模式可设置 Terra 个人额度");
        validateLimit(weekly);
        validateLimit(monthly);
        String id = player.toString();
        return transaction(
                c -> {
                    Config policy = config(c, true);
                    requireProfile(c, id);
                    Authorization prior = authorization(c, id);
                    if (prior.revision() != revision)
                        throw new IllegalStateException("Terra 玩家额度已更新，请刷新后重试");
                    Long effectiveWeekly = weekly != null ? weekly : prior.customWeekly();
                    Long effectiveMonthly = monthly != null ? monthly : prior.customMonthly();
                    if (custom && (effectiveWeekly == null || effectiveMonthly == null))
                        throw new IllegalArgumentException("请设置 Terra 个人周额度和月额度");
                    update(
                            c,
                            """
INSERT INTO wz_terra_grants(uuid,quota_mode,custom_weekly_limit,custom_monthly_limit,revision)
VALUES(?,?,?,?,1)
ON DUPLICATE KEY UPDATE quota_mode=VALUES(quota_mode),
  custom_weekly_limit=VALUES(custom_weekly_limit),
  custom_monthly_limit=VALUES(custom_monthly_limit),revision=revision+1""",
                            id,
                            mode,
                            effectiveWeekly,
                            effectiveMonthly);
                    profileChanged(c, id);
                    if (actor != null) {
                        JsonObject detail = new JsonObject();
                        detail.addProperty("playerId", id);
                        detail.addProperty("mode", mode);
                        detail.addProperty("customWeeklyLimitNanoUsd", effectiveWeekly);
                        detail.addProperty("customMonthlyLimitNanoUsd", effectiveMonthly);
                        ManagerStore.audit(c, actor.toString(), "WENZHOU_TERRA_QUOTA", detail);
                    }
                    return account(c, id, policy, clock.millis());
                });
    }

    private static void profileChanged(Connection c, String id) throws Exception {
        update(c, "UPDATE pm_profile_revisions SET revision=revision+1 WHERE uuid=?", id);
    }

    public JsonObject settings() throws Exception {
        return transaction(c -> settings(config(c, false)));
    }

    public JsonObject configure(String effort, boolean fast, long revision) throws Exception {
        return configure(effort, fast, revision, null);
    }

    JsonObject configure(String effort, boolean fast, long revision, UUID actor) throws Exception {
        return saveSettings(effort, fast, revision, null, null, null, null, null, actor);
    }

    public JsonObject configure(
            String effort,
            boolean fast,
            long revision,
            long weekly,
            long monthly,
            long serverMonthly,
            UUID actor)
            throws Exception {
        return saveSettings(
                effort, fast, revision, weekly, monthly, serverMonthly, null, null, actor);
    }

    public JsonObject configure(
            String effort,
            boolean fast,
            long revision,
            long weekly,
            long monthly,
            long serverMonthly,
            boolean quotaEnabled,
            boolean serverQuotaEnabled,
            UUID actor)
            throws Exception {
        return saveSettings(
                effort,
                fast,
                revision,
                weekly,
                monthly,
                serverMonthly,
                quotaEnabled,
                serverQuotaEnabled,
                actor);
    }

    private JsonObject saveSettings(
            String effort,
            boolean fast,
            long revision,
            Long weekly,
            Long monthly,
            Long serverMonthly,
            Boolean quotaEnabled,
            Boolean serverQuotaEnabled,
            UUID actor)
            throws Exception {
        if (effort == null || !EFFORTS.contains(effort))
            throw new IllegalArgumentException("Terra 推理强度须为 high、xhigh 或 max");
        validateLimit(weekly);
        validateLimit(monthly);
        validateLimit(serverMonthly);
        return transaction(
                c -> {
                    if (update(
                                    c,
                                    """
UPDATE wz_terra_config SET effort=?,fast=?,revision=revision+1,
  weekly_limit=COALESCE(?,weekly_limit),monthly_limit=COALESCE(?,monthly_limit),
  server_monthly_limit=COALESCE(?,server_monthly_limit),
  quota_enabled=COALESCE(?,quota_enabled),
  server_quota_enabled=COALESCE(?,server_quota_enabled)
WHERE id=1 AND revision=?""",
                                    effort,
                                    fast,
                                    weekly,
                                    monthly,
                                    serverMonthly,
                                    quotaEnabled,
                                    serverQuotaEnabled,
                                    revision)
                            != 1) throw new IllegalStateException("Terra 策略已更新，请刷新后重试");
                    JsonObject result = settings(config(c, true));
                    if (actor != null)
                        ManagerStore.audit(c, actor.toString(), "WENZHOU_TERRA_SETTINGS", result);
                    return result;
                });
    }

    private static void validateLimit(Long value) {
        if (value != null && (value < 0 || value > MAX_LIMIT_NANO_USD))
            throw new IllegalArgumentException("Terra 额度超出范围");
    }

    boolean hasReceipt(UUID operation) throws Exception {
        try (var connection = store.open()) {
            return !rows(connection, "SELECT 1 FROM wz_terra_receipts WHERE operation_id=?",
                    operation.toString()).isEmpty();
        }
    }

    public JsonObject reserve(UUID player, UUID operation, long inputBound, int maxOutput)
            throws Exception {
        if (inputBound <= 0 || maxOutput < MIN_OUTPUT)
            throw new IllegalArgumentException("Terra 请求的输入或输出上限无效");
        String id = player.toString();
        String operationId = operation.toString();
        return transaction(
                c -> {
                    // One shared row serializes all admissions and settlements across service
                    // instances.
                    Config policy = config(c, true);
                    requireProfile(c, id);
                    requireBaseAccess(c, id);
                    Authorization access = authorization(c, id);
                    if (!access.granted()) throw new SecurityException("该玩家未获 Terra 授权");
                    if (!rows(
                                    c,
                                    "SELECT operation_id FROM wz_terra_receipts WHERE"
                                            + " operation_id=?",
                                    operationId)
                            .isEmpty()) throw new IllegalStateException("该 Terra 请求已登记");
                    long now = clock.millis();
                    var windows = windows(id, now, policy, access);
                    long remaining = Long.MAX_VALUE;
                    for (Window window : windows)
                        if (window.enabled()) remaining = Math.min(remaining, remaining(c, window));
                    long inputRate = inputPrice(CACHE_WRITE_PRICE, inputBound, policy.fast());
                    if (inputBound > remaining / inputRate) throw insufficientBalance();
                    long inputAmount = inputBound * inputRate;
                    long outputRate = outputPrice(inputBound, policy.fast());
                    int output = (int) Math.min(maxOutput, (remaining - inputAmount) / outputRate);
                    if (output < MIN_OUTPUT) throw insufficientBalance();
                    long amount = inputAmount + output * outputRate;
                    update(
                            c,
                            """
INSERT INTO wz_terra_receipts
(operation_id,uuid,reserved_at,week_start,month_start,effort,fast,input_bound,max_output,reserved_nano_usd)
VALUES(?,?,?,?,?,?,?,?,?,?)""",
                            operationId,
                            id,
                            now,
                            windows.get(0).start(),
                            windows.get(1).start(),
                            policy.effort(),
                            policy.fast(),
                            inputBound,
                            output,
                            amount);
                    for (Window window : windows)
                        adjust(c, window.bucket(), window.subject(), window.start(), amount);
                    JsonObject result = new JsonObject();
                    result.addProperty("effort", policy.effort());
                    result.addProperty("fast", policy.fast());
                    result.addProperty("service_tier", policy.tier());
                    result.addProperty("max_output_tokens", output);
                    result.addProperty("reservedNanoUsd", amount);
                    return result;
                });
    }

    public void settle(UUID player, UUID operation, long actualNanoUsd) throws Exception {
        if (actualNanoUsd < 0) throw new IllegalArgumentException("Terra 实际费用不能为负");
        String id = player.toString();
        String operationId = operation.toString();
        transaction(
                c -> {
                    config(c, true);
                    var receipts =
                            rows(
                                    c,
                                    """
SELECT week_start,month_start,reserved_nano_usd,actual_nano_usd
FROM wz_terra_receipts WHERE operation_id=? AND uuid=? FOR UPDATE""",
                                    operationId,
                                    id);
                    if (receipts.isEmpty()) throw new IllegalArgumentException("Terra 请求回执不存在");
                    JsonObject receipt = receipts.get(0);
                    if (!receipt.get("actual_nano_usd").isJsonNull()) {
                        if (receipt.get("actual_nano_usd").getAsLong() != actualNanoUsd)
                            throw new IllegalStateException("Terra 回执已登记不同的实际费用");
                        return null;
                    }
                    long change = actualNanoUsd - receipt.get("reserved_nano_usd").getAsLong();
                    if (change != 0) {
                        long month = receipt.get("month_start").getAsLong();
                        adjust(c, "week", id, receipt.get("week_start").getAsLong(), change);
                        adjust(c, "month", id, month, change);
                        adjust(c, "server", "", month, change);
                    }
                    update(
                            c,
                            """
                            UPDATE wz_terra_receipts SET actual_nano_usd=?,settled_at=?
                            WHERE operation_id=?""",
                            actualNanoUsd,
                            clock.millis(),
                            operationId);
                    return null;
                });
    }

    static long price(
            long inputTokens,
            long cachedInputTokens,
            long cacheWriteInputTokens,
            long outputTokens,
            boolean fast) {
        if (inputTokens < 0
                || cachedInputTokens < 0
                || cacheWriteInputTokens < 0
                || outputTokens < 0
                || cachedInputTokens > inputTokens
                || cacheWriteInputTokens > inputTokens - cachedInputTokens)
            throw new IllegalArgumentException("Terra 用量回执无效");
        long ordinary = inputTokens - cachedInputTokens - cacheWriteInputTokens;
        long ordinaryAmount =
                Math.multiplyExact(ordinary, inputPrice(INPUT_PRICE, inputTokens, fast));
        long readAmount =
                Math.multiplyExact(
                        cachedInputTokens, inputPrice(CACHE_READ_PRICE, inputTokens, fast));
        long writeAmount =
                Math.multiplyExact(
                        cacheWriteInputTokens, inputPrice(CACHE_WRITE_PRICE, inputTokens, fast));
        long outputAmount = Math.multiplyExact(outputTokens, outputPrice(inputTokens, fast));
        return Math.addExact(
                Math.addExact(ordinaryAmount, readAmount),
                Math.addExact(writeAmount, outputAmount));
    }

    private static long inputPrice(long base, long input, boolean fast) {
        return base * (input > LONG_CONTEXT_THRESHOLD ? 2 : 1) * (fast ? 2 : 1);
    }

    private static long outputPrice(long input, boolean fast) {
        return (input > LONG_CONTEXT_THRESHOLD ? OUTPUT_PRICE * 3 / 2 : OUTPUT_PRICE)
                * (fast ? 2 : 1);
    }

    private record Config(
            String effort,
            boolean fast,
            long revision,
            long weekly,
            long monthly,
            long serverMonthly,
            boolean quotaEnabled,
            boolean serverQuotaEnabled) {
        String tier() {
            return fast ? "fast" : "default";
        }
    }

    private record Authorization(
            boolean granted, long revision, String mode, Long customWeekly, Long customMonthly) {
        boolean limitsApply(Config policy) {
            return policy.quotaEnabled() && !mode.equals("unlimited");
        }
    }

    private record Window(
            String bucket, String subject, long start, long reset, long limit, boolean enabled) {}

    private static Config config(Connection c, boolean lock) throws Exception {
        var settings =
                rows(
                        c,
                        """
SELECT effort,fast,revision,weekly_limit,monthly_limit,server_monthly_limit,
       quota_enabled,server_quota_enabled FROM wz_terra_config WHERE id=1"""
                                + (lock ? " FOR UPDATE" : ""));
        if (settings.isEmpty()) throw new IllegalStateException("Terra 账本尚未初始化");
        JsonObject row = settings.get(0);
        return new Config(
                row.get("effort").getAsString(),
                row.get("fast").getAsBoolean(),
                row.get("revision").getAsLong(),
                row.get("weekly_limit").getAsLong(),
                row.get("monthly_limit").getAsLong(),
                row.get("server_monthly_limit").getAsLong(),
                row.get("quota_enabled").getAsBoolean(),
                row.get("server_quota_enabled").getAsBoolean());
    }

    private static JsonObject settings(Config policy) {
        JsonObject result = new JsonObject();
        result.addProperty("effort", policy.effort());
        result.addProperty("fast", policy.fast());
        result.addProperty("service_tier", policy.tier());
        result.addProperty("revision", policy.revision());
        result.addProperty("weeklyLimitNanoUsd", policy.weekly());
        result.addProperty("monthlyLimitNanoUsd", policy.monthly());
        result.addProperty("serverMonthlyLimitNanoUsd", policy.serverMonthly());
        result.addProperty("quotaEnabled", policy.quotaEnabled());
        result.addProperty("serverQuotaEnabled", policy.serverQuotaEnabled());
        return result;
    }

    private static Authorization authorization(Connection c, String id) throws Exception {
        var grants =
                rows(
                        c,
                        """
                        SELECT granted,revision,quota_mode,custom_weekly_limit,custom_monthly_limit
                        FROM wz_terra_grants WHERE uuid=?""",
                        id);
        if (grants.isEmpty()) return new Authorization(false, 0, "inherit", null, null);
        JsonObject grant = grants.get(0);
        return new Authorization(
                grant.get("granted").getAsBoolean(),
                grant.get("revision").getAsLong(),
                grant.get("quota_mode").getAsString(),
                optionalLimit(grant, "custom_weekly_limit"),
                optionalLimit(grant, "custom_monthly_limit"));
    }

    private static Long optionalLimit(JsonObject grant, String key) {
        var value = grant.get(key);
        return value.isJsonNull() ? null : value.getAsLong();
    }

    boolean hasBridgeAccess(UUID player) throws Exception {
        String id = player.toString();
        return transaction(
                c -> {
                    requireProfile(c, id);
                    return authorization(c, id).granted() && baseAccess(c, id);
                });
    }

    private static boolean baseAccess(Connection c, String id) throws Exception {
        JsonObject field = new JsonObject();
        field.addProperty("player", id);
        field.addProperty("field", "access");
        return PlatformStore.valueInTransaction(c, "wenzhouai", field, false)
                .get("value")
                .getAsBoolean();
    }

    private static void requireBaseAccess(Connection c, String id) throws Exception {
        if (!baseAccess(c, id)) throw new SecurityException("该玩家未获问舟访问权限");
    }

    private static void requireProfile(Connection c, String id) throws Exception {
        if (rows(
                        c,
                        """
                        SELECT uuid FROM pm_profiles WHERE uuid=?
                        AND uuid NOT IN (SELECT uuid FROM pm_profile_exclusions) FOR UPDATE""",
                        id)
                .isEmpty()) throw new IllegalArgumentException("玩家未登记");
    }

    private static JsonObject account(Connection c, String id, Config policy, long now)
            throws Exception {
        Authorization access = authorization(c, id);
        var windows = windows(id, now, policy, access);
        boolean personalLimited = access.limitsApply(policy);
        boolean limited = personalLimited || policy.serverQuotaEnabled();
        JsonObject result = new JsonObject();
        result.addProperty("granted", access.granted());
        result.addProperty("revision", access.revision());
        result.addProperty("enabled", limited);
        result.addProperty("quotaEnabled", policy.quotaEnabled());
        result.addProperty("serverQuotaEnabled", policy.serverQuotaEnabled());
        result.addProperty("personalUnlimited", !personalLimited);
        result.addProperty("unlimited", !limited);
        result.addProperty("mode", access.mode());
        result.addProperty("defaultWeeklyLimitNanoUsd", policy.weekly());
        result.addProperty("defaultMonthlyLimitNanoUsd", policy.monthly());
        result.addProperty("customWeeklyLimitNanoUsd", access.customWeekly());
        result.addProperty("customMonthlyLimitNanoUsd", access.customMonthly());
        String[] labels = {"weekly", "monthly", "serverMonthly"};
        int minimumPercent = 100;
        boolean paused = false;
        long resetAt = 0;
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.get(i);
            long balance = remaining(c, window);
            String label = labels[i];
            result.addProperty(label + "LimitNanoUsd", window.limit());
            result.addProperty(label + "RemainingNanoUsd", balance);
            result.addProperty(label + "ResetAt", window.reset());
            if (!window.enabled()) continue;
            paused |= window.limit() == 0;
            int percent =
                    window.limit() == 0
                            ? 0
                            : (int) ((balance * 100 + window.limit() - 1) / window.limit());
            if (resetAt == 0 || percent < minimumPercent) {
                minimumPercent = percent;
                resetAt = window.reset();
            } else if (percent == 0 && minimumPercent == 0) {
                resetAt = Math.max(resetAt, window.reset());
            }
        }
        result.addProperty("remainingPercent", minimumPercent);
        result.addProperty("exhausted", minimumPercent == 0);
        result.addProperty("resetAt", paused ? 0 : resetAt);
        return result;
    }

    private static List<Window> windows(String id, long now, Config policy, Authorization access) {
        LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(SHANGHAI).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        long monthStart = midnight(firstOfMonth);
        long monthReset = midnight(firstOfMonth.plusMonths(1));
        boolean custom = access.mode().equals("custom");
        long weekly = custom ? access.customWeekly() : policy.weekly();
        long monthly = custom ? access.customMonthly() : policy.monthly();
        boolean personal = access.limitsApply(policy);
        return List.of(
                new Window(
                        "week",
                        id,
                        midnight(monday),
                        midnight(monday.plusWeeks(1)),
                        weekly,
                        personal),
                new Window("month", id, monthStart, monthReset, monthly, personal),
                new Window(
                        "server",
                        "",
                        monthStart,
                        monthReset,
                        policy.serverMonthly(),
                        policy.serverQuotaEnabled()));
    }

    private static long midnight(LocalDate date) {
        return date.atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
    }

    private static long remaining(Connection c, Window window) throws Exception {
        var balances =
                rows(
                        c,
                        """
                        SELECT held_nano_usd FROM wz_terra_balances
                        WHERE bucket=? AND subject=? AND period_start=?""",
                        window.bucket(),
                        window.subject(),
                        window.start());
        return balances.isEmpty()
                ? window.limit()
                : Math.max(0, window.limit() - balances.get(0).get("held_nano_usd").getAsLong());
    }

    private static void adjust(Connection c, String bucket, String id, long start, long delta)
            throws Exception {
        update(
                c,
                """
INSERT INTO wz_terra_balances(bucket,subject,period_start,held_nano_usd) VALUES(?,?,?,?)
ON DUPLICATE KEY UPDATE held_nano_usd=held_nano_usd+VALUES(held_nano_usd)""",
                bucket,
                id,
                start,
                delta);
    }

    private static IllegalStateException insufficientBalance() {
        return new IllegalStateException("Terra 额度不足，无法预留本次请求费用");
    }

    private <T> T transaction(ManagerStore.Work<T> action) throws Exception {
        try (Connection c = store.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                T answer = action.run(c);
                c.commit();
                return answer;
            } catch (Exception failure) {
                c.rollback();
                throw failure;
            }
        }
    }
}
