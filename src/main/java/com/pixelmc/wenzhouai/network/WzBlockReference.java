/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.network;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/** Wire identity for a draft selection. A saved question carries only the public label. */
public record WzBlockReference(ResourceLocation id, BlockPos pos, String ticket) {
    public static final int PER_QUESTION = 8;
    public static final Pattern MARKER = Pattern.compile(
            "\\[\\[block:([a-z0-9_.-]+:[a-z0-9_./-]+)\\|(-?\\d{1,8}),(-?\\d{1,8}),(-?\\d{1,8})(?:\\|([0-9a-f-]{36}))?\\]\\]");

    public static WzBlockReference parse(MatchResult marker) {
        var id = ResourceLocation.tryParse(marker.group(1));
        if (id == null) return null;
        return new WzBlockReference(id, new BlockPos(Integer.parseInt(marker.group(2)),
                Integer.parseInt(marker.group(3)), Integer.parseInt(marker.group(4))),
                marker.group(5) == null ? "" : marker.group(5));
    }

    public static WzBlockReference fromResponse(JsonObject response) {
        if (response == null || !response.has("reference")) return null;
        var match = MARKER.matcher(response.get("reference").getAsString());
        return match.matches() ? parse(match) : null;
    }

    public String coordinates() {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public String marker(boolean includeTicket) {
        return "[[block:" + id + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + (includeTicket && !ticket.isEmpty() ? "|" + ticket : "") + "]]";
    }
}
