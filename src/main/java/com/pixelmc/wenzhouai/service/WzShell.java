package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The host starts only the administrator-installed jail. The model's script enters on stdin. */
final class WzShell {
    private static final String RUNNER = "/usr/local/libexec/wenzhou-read";
    private static final int OUTPUT_LIMIT = 12 * 1024;
    private static final List<String> LAUNCH = launch();

    private static List<String> launch() {
        if (!Boolean.getBoolean("wenzhouai.shell.enabled")) return List.of();
        String os = System.getProperty("os.name");
        if (os.startsWith("Linux") && Files.isExecutable(Path.of(RUNNER))) return List.of(RUNNER);
        if (os.startsWith("Windows")) {
            String distro = System.getProperty("wenzhouai.shell.wsl", "");
            if (!distro.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) return List.of();
            Path wsl = Path.of(System.getenv("SystemRoot"), "System32", "wsl.exe");
            if (Files.isExecutable(wsl)) return List.of(wsl.toString(), "--distribution", distro, "--cd", "/",
                    "--user", "wenzhou", "--exec", RUNNER);
        }
        return List.of();
    }

    static boolean available() {
        return !LAUNCH.isEmpty();
    }

    static JsonObject execute(String script) {
        if (!available()) return WenzhouReadTools.message("unavailable", "当前实例未启用玩法资料沙箱。");
        if (script.isBlank() || script.length() > 2042 || script.chars().anyMatch(c ->
                Character.isISOControl(c) && c != '\n' && c != '\t'))
            return WenzhouReadTools.message("invalid_request", "查询脚本为空、过长或含无效字符。");
        Process process = null;
        try {
            var builder = new ProcessBuilder(LAUNCH).redirectError(ProcessBuilder.Redirect.DISCARD);
            String systemRoot = builder.environment().get("SystemRoot");
            builder.environment().clear();
            if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
            long started = System.nanoTime();
            process = builder.start();
            Process child = process;
            CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
                if (child.isAlive()) child.destroyForcibly();
            });
            try (var input = child.getOutputStream()) {
                input.write((script + "\n").getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes;
            try (var output = child.getInputStream()) {
                bytes = output.readNBytes(OUTPUT_LIMIT + 1);
            }
            boolean truncated = bytes.length > OUTPUT_LIMIT;
            if (truncated && child.isAlive()) child.destroyForcibly();
            if (!child.waitFor(2, TimeUnit.SECONDS))
                return WenzhouReadTools.message("unavailable", "沙箱超时，请缩小查询范围。");
            JsonObject result = WenzhouReadTools.message("sandbox_output",
                    truncated ? "已到输出上限，请用筛选或行号继续读取。" : "只读玩法资料的命令输出；请检查退出码。");
            if (!truncated && child.exitValue() == 137
                    && System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(10)) {
                result.addProperty("time_limit_reached", true);
                result.addProperty("message", "查询达到时限。缩小到相关模组和文件，避免再次遍历整个整合包。");
            }
            result.addProperty("exit_code", child.exitValue());
            result.addProperty("truncated", truncated);
            result.addProperty("output", new String(bytes, 0, Math.min(bytes.length, OUTPUT_LIMIT),
                    StandardCharsets.UTF_8));
            return result;
        } catch (IOException e) {
            return WenzhouReadTools.message("unavailable", "玩法沙箱无法启动或命令已超时。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WenzhouReadTools.message("interrupted", "查阅已中止。");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private WzShell() {}
}
