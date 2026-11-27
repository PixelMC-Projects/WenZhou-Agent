/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;

/** Local client preference for item tooltip quick-ask visibility and hotkey scope. */
final class WzQuickAskPrefs {
    enum Mode {
        GLOBAL("global", "全局"),
        JEI("jei", "JEI"),
        OFF("off", "关闭");

        final String wire;
        final String label;

        Mode(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        static Mode parse(String value) {
            if (value == null) return GLOBAL;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.wire.equals(normalized)) return mode;
            }
            return GLOBAL;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile boolean loaded;
    private static volatile Mode mode = Mode.GLOBAL;

    private WzQuickAskPrefs() {}

    static Mode mode() {
        ensureLoaded();
        return mode;
    }

    static void setMode(Mode next, Consumer<String> errorNotice) {
        if (next == null) return;
        ensureLoaded();
        if (mode == next) return;
        mode = next;
        save(errorNotice);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (WzQuickAskPrefs.class) {
            if (loaded) return;
            loaded = true;
            Path path = configPath();
            if (path == null || !Files.isRegularFile(path)) return;
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                var parsed = JsonParser.parseString(raw);
                if (parsed.isJsonObject()) {
                    var obj = parsed.getAsJsonObject();
                    if (obj.has("quickAskMode") && obj.get("quickAskMode").isJsonPrimitive()) {
                        mode = Mode.parse(obj.get("quickAskMode").getAsString());
                    }
                }
            } catch (Exception ignored) {
                mode = Mode.GLOBAL;
            }
        }
    }

    private static void save(Consumer<String> errorNotice) {
        Path path = configPath();
        if (path == null) return;
        synchronized (WzQuickAskPrefs.class) {
            try {
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                JsonObject root = new JsonObject();
                if (Files.isRegularFile(path)) {
                    try {
                        var parsed =
                                JsonParser.parseString(
                                        Files.readString(path, StandardCharsets.UTF_8));
                        if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
                    } catch (Exception ignored) {
                        root = new JsonObject();
                    }
                }
                root.addProperty("quickAskMode", mode.wire);
                Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
                Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                if (errorNotice != null) errorNotice.accept("未能保存快捷提问设置，请重试。");
            }
        }
    }

    private static Path configPath() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return null;
        return mc.gameDirectory.toPath().resolve("config").resolve("wenzhouai-client.json");
    }
}
