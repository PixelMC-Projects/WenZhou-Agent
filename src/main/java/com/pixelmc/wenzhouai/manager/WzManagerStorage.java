package com.pixelmc.wenzhouai.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelmc.pixelmcmanager.api.ManagerBridgeApi;
import java.util.concurrent.CompletableFuture;

public final class WzManagerStorage {
    public static final WzDirectory DIRECTORY = new WzDirectory(WzManagerStorage::request);
    private static final String ID = WzDirectory.ID;

    private WzManagerStorage() {}

    public static void register(String version) {
        ManagerBridgeApi.register(ID, "问舟AI", version, "player-access,agreement,admin-web,weekly-quota");
        ManagerBridgeApi.registerStorage(ID, WzDirectory.STORAGE_MANIFEST);
    }

    private static CompletableFuture<JsonObject> request(JsonObject request) {
        return ManagerBridgeApi.call(ID, "platform", request.toString())
                .thenApply(
                        raw -> {
                            JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
                            if (!response.has("result"))
                                throw new IllegalStateException("Manager 数据暂不可用");
                            return response.getAsJsonObject("result");
                        });
    }
}
