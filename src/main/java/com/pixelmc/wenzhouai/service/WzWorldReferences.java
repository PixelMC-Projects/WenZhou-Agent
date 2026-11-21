/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.network.WzBlockReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.function.Supplier;

/** Server-thread-only draft references, with their lifetime owned by the player connection. */
public final class WzWorldReferences {
    private static final String STALE = "方块引用已失效，请重新选择。";
    private static final long LIFETIME = 600_000_000_000L;
    private static final double MAX_REACH = 8;
    public record Look(ServerLevel level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {}
    private record Ticket(WzBlockReference ref, Look look, long generation, long expires) {}

    /** One question's short-lived lookup handles. Only the server thread may resolve or inspect them. */
    public final class Ask {
        private final ServerPlayer player;
        private final long generation;
        private final List<WzBlockReference> refs;

        private Ask(ServerPlayer player, long generation, List<WzBlockReference> refs) {
            this.player = player;
            this.generation = generation;
            this.refs = List.copyOf(refs);
        }

        public Supplier<JsonArray> evidence() {
            return this::minimalReferences;
        }

        private JsonArray minimalReferences() {
            checkAskingPlayer();
            var result = new JsonArray();
            for (int i = 0; i < refs.size(); i++) {
                WzBlockReference ref = refs.get(i);
                var ticket = resolve(player, generation, ref);
                var row = new JsonObject();
                row.addProperty("ref", "b" + (i + 1));
                row.addProperty("reference", ref.marker(false));
                row.addProperty("dimension", ticket.look.level.dimension().location().toString());
                var refusal = WzBlockData.authorization(player, ticket.look);
                if (refusal != null && "forbidden".equals(refusal.get("status").getAsString()))
                    row.add("denied", refusal);
                result.add(row);
            }
            return result;
        }

        public JsonObject inspect(JsonObject arguments, boolean read) {
            var query = WzBlockData.Query.parse(arguments, read);
            if (query == null)
                return WzBlockData.error("invalid_request", "请选择现有方块引用、有效字段路径和页范围。");
            try {
                checkAskingPlayer();
                int index = query.ref().charAt(1) - '1';
                if (index >= refs.size())
                    return WzBlockData.error("stale", "当前问题没有这个方块引用，请重新选择。");
                var ticket = resolve(player, generation, refs.get(index));
                return WzBlockData.bounded(WzBlockData.inspect(player, ticket.look, query, read));
            } catch (Rejected stale) {
                return WzBlockData.error("stale", stale.getMessage());
            } catch (RuntimeException | LinkageError unavailable) {
                return WzBlockData.error("unavailable", "当前无法安全读取这个方块的数据。");
            }
        }

        private void checkAskingPlayer() {
            if (player == null || !player.server.isSameThread()
                    || generation != WzService.generation(player.getUUID())) throw new Rejected(STALE);
        }
    }

    private final Map<String, Ticket> drafts = new LinkedHashMap<>();

    public static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }

    /** The client position only prevents the server from selecting a different block on a lagged frame. */
    public static Look look(ServerPlayer player, BlockPos expected) {
        if (!validPlayer(player) || player.getCamera() != player) throw new Rejected("当前无法选择世界方块。");
        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition();
        double reach = Math.min(player.getBlockReach(), MAX_REACH);
        Vec3 end = start.add(player.getViewVector(1).scale(reach));
        if (reach <= 0 || !Double.isFinite(reach) || !loaded(level, start, end))
            throw new Rejected("当前无法读取要选择的方块。");
        var ray = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(expected))
            throw new Rejected("服务器看到的目标已改变，请对准方块后重选。");
        if (ProjectileUtil.getEntityHitResult(player, start, ray.getLocation(),
                player.getBoundingBox().expandTowards(ray.getLocation().subtract(start)).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable(), start.distanceToSqr(ray.getLocation())) != null)
            throw new Rejected("目标方块被实体挡住，请换个角度。");
        BlockPos target = ray.getBlockPos().immutable();
        if (!level.getWorldBorder().isWithinBounds(target)) throw new Rejected("无法引用世界边界外的方块。");
        var state = level.getBlockState(target);
        if (state.isAir()) throw new Rejected("该位置没有可引用的方块。");
        return new Look(level, target, state, loadedEntity(level, target), ray.getDirection());
    }

    public JsonObject capture(ServerPlayer player, Look look, long generation) {
        if (!valid(player, look)) throw new Rejected(STALE);
        var id = BuiltInRegistries.BLOCK.getKey(look.state.getBlock());
        WzBlockReference ref = new WzBlockReference(id, look.pos, UUID.randomUUID().toString());
        long now = System.nanoTime();
        drafts.values().removeIf(saved -> saved.expires < now || saved.generation != generation);
        if (drafts.size() >= 32) drafts.remove(drafts.keySet().iterator().next());
        drafts.put(ref.ticket(), new Ticket(ref, look, generation, now + LIFETIME));
        var result = new JsonObject();
        result.addProperty("reference", ref.marker(true));
        return result;
    }

    public Ask attach(ServerPlayer player, JsonObject body, long generation) {
        var question = body.get("question");
        if (question == null || !question.isJsonPrimitive() || !question.getAsJsonPrimitive().isString()) return null;
        var markers = WzBlockReference.MARKER.matcher(question.getAsString()).results().toList();
        if (markers.isEmpty()) return null;
        if (markers.size() > WzBlockReference.PER_QUESTION) throw new Rejected("一条消息最多可以引用八个方块。");
        var refs = markers.stream().map(WzBlockReference::parse).filter(ref -> ref != null && !ref.ticket().isEmpty()).toList();
        String visible = WzBlockReference.MARKER.matcher(question.getAsString()).replaceAll(marker -> {
            var ref = WzBlockReference.parse(marker);
            return Matcher.quoteReplacement(ref == null ? marker.group() : ref.marker(false));
        });
        body.addProperty("question", visible);
        if (refs.isEmpty()) return null;
        var unique = new LinkedHashMap<String, WzBlockReference>();
        for (WzBlockReference reference : refs) {
            var previous = unique.putIfAbsent(reference.ticket(), reference);
            if (previous != null && !previous.equals(reference)) throw new Rejected(STALE);
        }
        return new Ask(player, generation, new ArrayList<>(unique.values()));
    }

    private Ticket resolve(ServerPlayer player, long generation, WzBlockReference ref) {
        Ticket ticket = drafts.get(ref.ticket());
        if (ticket == null || !ticket.ref.equals(ref) || ticket.expires < System.nanoTime()
                || ticket.generation != generation || !valid(player, ticket.look)) throw new Rejected(STALE);
        return ticket;
    }

    private static boolean valid(ServerPlayer player, Look look) {
        return validPlayer(player) && player.serverLevel() == look.level
                && look.level.getWorldBorder().isWithinBounds(look.pos) && look.level.hasChunkAt(look.pos)
                && look.level.getBlockState(look.pos).getBlock() == look.state.getBlock()
                && loadedEntity(look.level, look.pos) == look.entity;
    }

    static BlockEntity loadedEntity(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        var entity = chunk == null ? null : chunk.getBlockEntities().get(pos);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    private static boolean validPlayer(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean loaded(ServerLevel level, Vec3 start, Vec3 end) {
        BlockPos first = BlockPos.containing(start);
        BlockPos last = BlockPos.containing(end);
        return level.hasChunkAt(first) && level.hasChunkAt(last)
                && level.hasChunkAt(new BlockPos(first.getX(), first.getY(), last.getZ()))
                && level.hasChunkAt(new BlockPos(last.getX(), first.getY(), first.getZ()));
    }
}
