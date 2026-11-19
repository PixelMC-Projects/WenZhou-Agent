package com.pixelmc.wenzhouai.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "wenzhouai", value = Dist.CLIENT)
public final class WzClient {
    private static final Map<Long, CompletableFuture<JsonObject>> PENDING = new HashMap<>();
    private static long sequence;
    private static Consumer<JsonObject> listener = ignored -> {};

    private WzClient() {}

    public static boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && WzNetwork.CHANNEL.isRemotePresent(connection.getConnection());
    }

    public static void onEvent(Consumer<JsonObject> callback) {
        listener = callback;
    }

    public static CompletableFuture<JsonObject> request(String action, JsonObject payload) {
        if (!available()) return CompletableFuture.completedFuture(WzNetwork.fail("当前服务器未提供问舟"));
        if (PENDING.size() >= 8) return CompletableFuture.completedFuture(WzNetwork.fail("请稍候再试"));
        WzNetwork.receive(WzClient::receive);
        long id = ++sequence;
        var request = new CompletableFuture<JsonObject>();
        PENDING.put(id, request);
        WzNetwork.CHANNEL.sendToServer(new WzNetwork.Request(id, action, payload.toString()));
        request.completeOnTimeout(
                        WzNetwork.fail("问舟请求超时"), action.equals("ask") ? 15 : 1, TimeUnit.MINUTES)
                .whenComplete(
                        (result, error) ->
                                Minecraft.getInstance().execute(() -> PENDING.remove(id, request)));
        return request;
    }

    private static void receive(WzNetwork.Response response) {
        JsonObject json = JsonParser.parseString(response.json()).getAsJsonObject();
        if (response.id() == 0) {
            listener.accept(json);
            return;
        }
        CompletableFuture<JsonObject> pending = PENDING.remove(response.id());
        if (pending != null) pending.complete(json);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        var old = new HashMap<>(PENDING);
        PENDING.clear();
        old.values().forEach(request -> request.complete(WzNetwork.fail("游戏连接已断开")));
    }
}
