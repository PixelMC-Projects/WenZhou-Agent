package com.pixelmc.wenzhouai.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelmc.wenzhouai.service.WzService;
import com.pixelmc.wenzhouai.service.WzWorldReferences;
import net.minecraft.core.BlockPos;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class WzNetwork {
    private static final int REQUEST_LIMIT = 8192;
    private static final int RESPONSE_LIMIT = 900_000;
    static final SimpleChannel CHANNEL =
            NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("wenzhouai", "player"),
                    () -> "1",
                    WzNetwork::compatible,
                    WzNetwork::compatible);
    private static final Map<UUID, Connection> PLAYERS = new HashMap<>();
    private static Consumer<Response> receiver = response -> {};

    private static final class Connection {
        final ServerPlayer player;
        final WzWorldReferences worldReferences = new WzWorldReferences();
        int requests;
        int pending;
        long window = System.nanoTime();

        Connection(ServerPlayer player) {
            this.player = player;
        }

        boolean enter() {
            long timestamp = System.nanoTime();
            if (timestamp - window > 10_000_000_000L) {
                window = timestamp;
                requests = 0;
            }
            if (requests >= 24 || pending >= 4) return false;
            requests++;
            pending++;
            return true;
        }
    }

    private static boolean compatible(String version) {
        return "1".equals(version)
                || NetworkRegistry.ABSENT.equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    record Request(long id, String action, String json) {
        static Request decode(FriendlyByteBuf buf) {
            return new Request(buf.readLong(), buf.readUtf(24), buf.readUtf(REQUEST_LIMIT));
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeLong(id);
            buf.writeUtf(action, 24);
            buf.writeUtf(json, REQUEST_LIMIT);
        }
    }

    record Response(long id, String json) {
        static Response decode(FriendlyByteBuf buf) {
            return new Response(buf.readLong(), buf.readUtf(RESPONSE_LIMIT));
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeLong(id);
            buf.writeUtf(json, RESPONSE_LIMIT);
        }
    }

    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::encode)
                .decoder(Request::decode)
                .consumerMainThread(
                        (request, ctx) -> {
                            ServerPlayer player = ctx.get().getSender();
                            if (player != null) handle(player, request);
                        })
                .add();
        CHANNEL.messageBuilder(Response.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Response::encode)
                .decoder(Response::decode)
                .consumerMainThread((response, ctx) -> receiver.accept(response))
                .add();
        MinecraftForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedOutEvent event) -> {
                    if (!(event.getEntity() instanceof ServerPlayer player)) return;
                    Connection existing = PLAYERS.get(player.getUUID());
                    if (existing != null && existing.player == player) {
                        PLAYERS.remove(player.getUUID());
                        WzService.disconnect(player.getUUID());
                    }
                });
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerStoppedEvent event) -> PLAYERS.clear());
    }

    static void receive(Consumer<Response> callback) {
        receiver = callback;
    }

    private static void handle(ServerPlayer player, Request request) {
        MinecraftServer server = player.getServer();
        if (server == null || request.id <= 0 || player.hasDisconnected()) return;
        UUID id = player.getUUID();
        Connection connection =
                PLAYERS.compute(
                        id,
                        (uuid, previous) ->
                                previous != null && previous.player == player
                                        ? previous
                                        : new Connection(player));
        if (!connection.enter()) {
            respond(player, request.id, fail("请求过于频繁，请稍后重试"));
            return;
        }
        JsonObject body;
        try {
            if (request.json.getBytes(StandardCharsets.UTF_8).length > REQUEST_LIMIT)
                throw new IllegalArgumentException();
            body = JsonParser.parseString(request.json).getAsJsonObject();
            if (body.size() > 5
                    || body.entrySet().stream()
                            .anyMatch(entry -> !entry.getValue().isJsonPrimitive()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            connection.pending--;
            respond(player, request.id, fail("问舟操作参数无效"));
            return;
        }
        long generation = WzService.generation(id);
        java.util.concurrent.CompletableFuture<JsonObject> work;
        try {
            if (request.action.equals("block.capture")) {
                if (body.size() != 1 || !body.has("pos") || !body.getAsJsonPrimitive("pos").isString())
                    throw new IllegalArgumentException();
                var look = WzWorldReferences.look(player, BlockPos.of(Long.parseLong(body.get("pos").getAsString())));
                work = WzService.blockReferenceAccess(id, generation).thenCompose(admitted ->
                        server.submit(() -> {
                            if (!admitted || !current(server, connection, id) || generation != WzService.generation(id))
                                return fail("当前没有问舟方块引用权限。");
                            try {
                                return connection.worldReferences.capture(player, look, generation);
                            } catch (WzWorldReferences.Rejected refused) {
                                return fail(refused.getMessage());
                            }
                        }));
            } else {
                var world = request.action.equals("ask") ? connection.worldReferences.attach(player, body, generation) : null;
                work = WzService.request(id, request.action, body, world);
            }
        } catch (WzWorldReferences.Rejected refused) {
            connection.pending--;
            respond(player, request.id, fail(refused.getMessage()));
            return;
        } catch (RuntimeException invalid) {
            connection.pending--;
            respond(player, request.id, fail("问舟操作参数无效"));
            return;
        }
        work
                .whenComplete(
                        (message, error) ->
                                server.execute(
                                        () -> {
                                            connection.pending--;
                                            if (!current(server, connection, id)) return;
                                            if (generation != WzService.generation(id)) {
                                                respond(player, request.id, fail("问舟状态已变更"));
                                                return;
                                            }
                                            respond(
                                                    player,
                                                    request.id,
                                                    error == null ? message : fail("问舟暂不可用"));
                                        }));
    }

    public static void quotaChanged(MinecraftServer server, UUID target) {
        server.execute(() -> {
            JsonObject notice = new JsonObject();
            notice.addProperty("type", "quota.changed");
            PLAYERS.forEach((id, connection) -> {
                if ((target == null || target.equals(id)) && current(server, connection, id))
                    respond(connection.player, 0, notice);
            });
        });
    }

    public static void event(MinecraftServer server, UUID id, long generation, JsonObject event) {
        server.execute(
                () -> {
                    Connection connection = PLAYERS.get(id);
                    if (connection != null
                            && current(server, connection, id)
                            && generation == WzService.generation(id))
                        respond(connection.player, 0, event);
                });
    }

    private static boolean current(MinecraftServer server, Connection connection, UUID id) {
        return PLAYERS.get(id) == connection
                && server.getPlayerList().getPlayer(id) == connection.player
                && !connection.player.hasDisconnected();
    }

    private static void respond(ServerPlayer player, long id, JsonObject message) {
        String encoded = message.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > RESPONSE_LIMIT)
            encoded = fail("会话内容超过传输上限").toString();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(id, encoded));
    }

    static JsonObject fail(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message);
        return result;
    }

    private WzNetwork() {}
}
