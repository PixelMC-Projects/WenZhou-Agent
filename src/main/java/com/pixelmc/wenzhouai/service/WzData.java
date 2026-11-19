package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;

final class WzData {
    static JsonObject read(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (NoSuchFileException missing) {
            return new JsonObject();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("问舟存储读取失败", failure);
        }
    }

    static void write(Path path, JsonObject data) {
        try {
            Files.createDirectories(path.getParent());
            Path pending = Files.createTempFile(path.getParent(), ".wenzhou-", ".tmp");
            try {
                Files.writeString(pending, data.toString());
                Files.move(
                        pending,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("问舟存储保存失败", failure);
        }
    }

    static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key)
                : new JsonObject();
    }

    static String string(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    static long number(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsLong()
                : 0;
    }

    static boolean flag(JsonObject object, String key) {
        var value = object.get(key);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message);
        return result;
    }

    private WzData() {}
}
